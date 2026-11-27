package io.legado.app.service

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.legado.app.R
import io.legado.app.exception.SynthesisGiveUpException
import io.legado.app.constant.AppConst
import io.legado.app.help.audio.GainAudioProcessor
import io.legado.app.constant.AppLog
import io.legado.app.model.VnParagraph
import io.legado.app.model.VnSubtitlePos
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.MediaHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.AudioCacheValidator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SubTitleUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地朗读（系统TTS合成 + 转发器播放/缓存架构）
 *
 * 系统TTS仅负责把文本 synthesizeToFile 合成到本地缓存文件，
 * 播放调度、ExoPlayer 管理、字级进度、缓存清理全部复用 HttpReadAloudService 模式。
 * 音频缓存目录：externalCacheDir/systemTTS/
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener, Player.Listener {

    companion object {
        private const val TAG = "TTSReadAloudService"
        private const val PRELOAD_TRIGGER_REMAIN = 5

        /**
         * 11-22 14:4x：`countBufferedAhead` 结果的短时有效期（毫秒）。
         *
         * 该函数在播放主路径上**同步**跑，跨章累加后一次要扫 400+ 次 stat
         * （详细成本见 `bufferedCountCache` 注释）。同一段被反复重报
         * （重试/并发抢跑）时没必要重算，500ms 内复用上次结果。
         */
        private const val BUFFERED_COUNT_TTL_MS = 500L

        /**
         * 11-22 21:4x：`缓冲上报诊断` 的**心跳**间隔（毫秒）。
         *
         * 诊断只在「内容变了」或「距上次 ≥ 5 秒」时落一行：预加载每 2 秒一笔、播放每段一笔，
         * 每笔都打一场朗读就上千行，会把 appLog 淹掉。留 5 秒心跳是为了能看出
         * 「数字卡住不动」——那正是本次要抓的现场（暂停后缓冲停在 5 段 52 秒没变）。
         */
        private const val BUF_DIAG_HEARTBEAT_MS = 5_000L

        // ===== 跨服务生命周期的跨章预加载状态（11-21 14:1x）=====
        // 三张表挂在伴生对象上：服务 stop→重建（切章/重新定位）不清零，
        // 已备进度/段表/重试时刻全部延续——重建后跳到没备过的段续备，
        // 不再「1/81 重来」。换书时由轮函数 validRange 清理范围外的章。
        private val preloadProgressStore = HashMap<Int, Int>()
        private val preloadCacheStore = HashMap<Int, PreloadedChapter>()
        private val preloadRetryStore = HashMap<Int, Long>()

        /**
         * 「该章哪些段**确实落进了正式缓存**」——11-23 新增。
         *
         * 为什么需要这本账：跨章缓冲计数一直是「按当前设置重算缓存文件名再 stat」，
         * 真机 18:32 实锤它与预加载两套口径对不上：第2章已备 82 段，计数却长期报
         * 0 / 27（`跨章明细=[第2章=0] 中断=第2章整章 0 段`，而 66ms 前刚打
         * 「跨章预加载已备: 第2章 第1/93 段」）。计数真正关心的是「后台已备好、
         * 接着能直接播的段数」，而预加载在落盘那一刻**确切知道**是哪一章哪一段
         * ⇒ 直接记下来，计数读这本账，不再靠重算文件名猜。
         *
         * 11-23 23:5x 改口径（用户实拍「读 n 章、备到 n+1 章时缓冲恒 0」）：
         * **记账不再验文件存在**。原实现记账前要 stat 一次自己重算的文件名，而
         * 写入侧与重算侧的 title/voice/index 只要有一处不同就 miss ⇒ 一笔都不记
         *（真机 22:49 第23章连报「已备 1..12 段」，账本却是 0/0，跨章恒 0）。
         * ok=true（引擎回了音频）就是「这段能直接播」的证据——真落盘改名失败时，
         * 播放会改走 UTTS 的 synth_cache，照样连听。文件扫描只留作计数侧兜底与对照。
         */
        private val preloadPreparedStore = HashMap<Int, MutableSet<Int>>()

        /**
         * 上一次预加载属于哪本书（bookUrl）——11-23 新增，用来按**书身份**判换书。
         *
         * 为什么需要：上面几张晓表挂在伴生对象上、清理只按「章号范围」
         *（validRange = 当前章 .. 当前章+目标章数），而**两本书的章号都从 0 起**：
         * 切书后旧书的段表/进度/已备账原样停在同号章上，轮函数照旧拿旧书段表提交。
         * 真机 18:38:38 现场：已切到「强占男配肥村姑」第001章，预加载却还在报
         * 「第2章 54/93 段 · 第2章 永远的外人」（上一本书的章），提交的也是上一本书
         * 的正文 ⇒ 规则收到「新书+旧书段落」→ 上文探针定位失败（段不在窗口）→
         * 滑窗 AI 兜底 → 把旧书角色分析进新书角色表 → saveRecords 写前过滤每轮
         * 都要剔除（用户看到的「串书防护又来了」）。
         */
        private var preloadBookUrl: String? = null

        /**
         * 批量预热每批段数（11-15，路 C）。
         *
         * 为什么是 4：取消粒度＝整批。用户跳段时这一批的预热成果全部作废
         * （系统 TTS 的 PCM 流无段边界，UTTS 侧也只能整批丢），
         * 4 段 × 约 3.4s 的最坏损失可接受；再大就会「跳一下损失十几秒算力」。
         */
        private const val PRELOAD_BATCH_SIZE = 4
        /**
         * 11-16：播放请求的「首回调看门狗」（毫秒）。
         *
         * 引擎提交返回 SUCCESS 却连 onStart 都不回调时，旧逻辑要等满
         * `sysTtsSynthesizeTimeout`（默认 120 秒）——真机 06:20 就是这样卡住的。
         *
         * 11-17 二调（03:28 Legado24 实锤「慢被误判成死」）：固定 8 秒对快插件够用，
         * 但慢链路会冤枉——规则姓名分析 31.2s + UTTS 内部排队 3s，UTTS 实际已在
         * 处理（内部 onSynthesizeStart 有记录），系统 onStart 却没在窗口内到达。
         *
         * 11-17 三调：改为**跟随总超时联动**，但不低于 8s。
         *
         * 11-18 五调（用户令「这个 30s 是你觉得合理的时间」+「不要等太久」）：
         * **去掉 30 秒硬上限**。旧公式 `min(总超时, 30s)` 里的 30s 是拍脑袋写的，
         * 与用户任何设置都无关——用户把总超时设成 120s，看门狗照样 30s 就判死，
         * 于是「慢但还能成」的请求被清引擎重来（实测：规则姓名分析 31.2s +
         * UTTS 排队，onStart 必然晚于 30s；02:26:38 Legado53 首回调超时即此形态）。
         *
         * 新公式：**总超时的 80%**，下限 8s、上限留 1s 给总超时兜底。实际取值：
         *   总超时 120s（默认）→ 看门狗 **96s**；
         *   总超时 60s → **48s**；20s → **16s**；10s → **8s**（撞下限）。
         * 语义不变：看门狗只是总超时的**前置快筛**——占总超时的大头，
         * 留一点尾巴给总超时本身（onStart 到了之后还有完整总超时等结果）。
         *
         * 代价（如实记）：总超时设得越大，真·死引擎被发现得越晚。
         * 用户当前按「不想等太久」方向调总超时，本公式会跟着收敛，不会反向拖长。
         *
         * 11-17 四调（用户问「要不要专门设个键」）：**不设键**。理由——
         * ① 它是内部快筛，不承载用户意图；用户真正想调的是「最多等多久」＝总超时，
         *    看门狗跟着它走就够，多一个键等于让用户面对两个含义重叠的超时；
         * ② 加键就要加文案、加范围校验、加「两者不许打架」的约束，成本远大于收益；
         * ③ 实际需要调的场景已有出口：想让它更宽容 → 调大总超时。
         */
        private val firstCallbackTimeoutMs: Long
            get() {
                val total = AppConfig.sysTtsSynthesizeTimeout * 1000L
                // 80% 给看门狗；至少留 1s 给总超时兜底（用户设得极小时也不例外）
                val cap = (total - 1_000L).coerceAtLeast(1_000L)
                // 原 8s 保底保留，但不能顶穿 cap（用户设 5s 时 8s 会比总超时还长）
                val floor = 8_000L.coerceAtMost(cap)
                return (total * 4 / 5).coerceIn(floor, cap)
            }

        /** 11-16：播放推进到本段之后才允许后台预热（用户令「开头不预热」）。 */
        private const val PRELOAD_START_AFTER = 3
        /**
         * 单个预热段的等待上限（毫秒）。
         *
         * 远短于播放侧（`sysTtsSynthesizeTimeout`，默认 120 秒）：预热是纯优化，
         * 一段卡住就跳过，绝不能让后台挂 2 分钟（真机 06:40 就是这样假死 6 分钟的）。
         */
        private const val PRELOAD_SEGMENT_TIMEOUT_MS = 15_000L
        /**
         * 11-20：跨章预加载「正文未就绪」后的重试间隔（毫秒）。
         * 未来章的正文靠 ReadBook 的正文预下载（preDownloadNum，默认 10 章）异步落盘，
         * 预加载跑到时可能还没下完——不能永久放弃，也不能每轮都重解析刷日志。
         */
        private const val PRELOAD_CHAPTER_RETRY_MS = 15_000L

        /**
         * 11-20：单段预加载**真失败**后的退避时长（毫秒）。
         *
         * 「被播放打断」不算失败（不计次、不退避，立刻重试）；只有引擎/插件真
         * 合不出才退避，避免每轮空撞。太短会连续撞，太长会拖慢覆盖。
         */
        private const val PRELOAD_SEGMENT_RETRY_MS = 800L

        /** 单段连续失败多少次才放弃（跳过它继续下一段），防死磕合不出的段 */
        private const val PRELOAD_SEGMENT_MAX_FAILS = 3

        /**
         * 11-27 根治「跨章预加载无限重扫」（真机 19:28–22:58 实锤）。
         *
         * ## 现场（第10章 107 段，被完整重扫 213 遍）
         * ```
         * 19:46:12.206  跨章预加载已备: 第10章 第106/107 段   ← 记账推到 106
         * 19:46:13.823  跨章预加载已备: 第11章 第1/105 段     ← 进下一章
         * 19:46:13.984  第10章第1段 提交preload_only         ← 立刻回头重扫本章
         * 19:46:14.227  跨章预加载已备: 第10章 第1/107 段     ← 记账从 1 重来
         * ```
         * 3.5 小时提交 **25126 次**，去重后唯一 (章,段) 只有 **393** 个
         * ⇒ **98.9% 是白重复**；单段最多提交 225 次。
         * 伴生：应用冻结 38 次（最长 4087ms）、Dispatchers.Default/IO/Main
         * 三条 5000ms 同时超时（线程池被逐段探测占满）。
         *
         * ## 根因：两套判据打架
         * 备货**记账**是「引擎回成功即记」（11-23 定的，治的是「计数偏低」）；
         * 而**探测**是「文件 exists()」。真机 2451 条缓冲诊断里
         * `文件0/记录93`、`文件0/记录106` 共 2400+ 条，`文件[1-9]` **一条都没有**
         * ⇒ 探测命中率严格为 0。于是：
         *   记账推进到 total → 下轮 `from>=total` 归零 → 逐段探测第一个就 miss
         *   → `scan=0` → 回到第 1 段重扫 → 又推到 total → **死循环**。
         *
         * ## 改法：加「连续空探测」闸，不再无限重扫
         * 每章记一个连续「本轮探测一个文件都没命中」的轮次数。达到阈值就
         * **退避整章**（[PRELOAD_DESYNC_RETRY_MS]）并打一行 WARN 级日志，
         * 而不是立刻从头再扫。这样即使键真的对不上（探测永远 miss），
         * 重扫频率也会从「约 43 秒一次、不停」降到「每 5 分钟一次」，
         * 不再把 CPU/IO 与在途合成拖垮；日志上也能一眼看到这条告警。
         *
         * 为什么不直接把探测判据改成记账：记账是内存账本，进程重启/换书就没了，
         * 而「这段到底能不能直接播」最终仍取决于文件。两者必须**同源**才是根治，
         * 本次会话先上兜底闸止血，同源改造单独一批做。
         */
        private const val PRELOAD_DESYNC_MAX_ROUNDS = 3

        /** 探测连续落空达到阈值后的整章退避时长（毫秒） */
        private const val PRELOAD_DESYNC_RETRY_MS = 5 * 60_000L

        /**
         * 11-27：同一段「被播放打断」后的退避基数（毫秒）。
         *
         * 真机第13章第18段一晚被打断 **8 次**（19 段 5 次）：每次都是
         * 「预热被打断 → 下轮立刻重试同一段 → 又被下一笔播放打断」。
         * 这里从第 2 次起退避 `基数 × (次数-1)`，封顶上限，避免同一段
         * 反复与播放抢引擎、进而触发引擎实例反复重建。
         */
        private const val PRELOAD_INTERRUPT_BACKOFF_BASE_MS = 500L

        /** 打断退避封顶（毫秒）——再高会明显拖慢备货覆盖 */
        private const val PRELOAD_INTERRUPT_BACKOFF_MAX_MS = 3_000L

        /**
         * 11-18：预热让路判据——播放请求提交后超过这么久还没收尾，就判定上游慢/卡，
         * 本轮预热链直接放弃，不再逐段各干等 15 秒。
         *
         * 为什么需要（14:26 那轮实测）：播放段撞云之声慢上游，连吃 3 次 20 秒超时、
         * 共 68 秒才换钥成功。这段时间系统 TTS 的合成队列被播放请求占死，UTTS 连
         * `onSynthesizeText` 都没被调到（debug.log 里连「预热让路」这行都没有），
         * U阅 这边 5 段预热逐段等满 15 秒判负：链空转 76 秒、5 段全废，
         * 请求还一直堆在队列里。判据只看「播放请求在飞且已慢」，
         * 正常 1~3 秒的合成完全不受影响。
         */
        private const val PRELOAD_YIELD_PLAYBACK_STUCK_MS = 6_000L
        /**
         * 11-22 13:2x（用户拍板「照你推荐来吧」）：让路时**先等一小会儿**再抢，
         * 而不是当场放弃整轮。
         *
         * ## 现场（真机 12:08–12:21 一场次，定量）
         * 同一批日志里 `让路放弃（播放请求在飞）` 出现 **354 次、全部是这一个理由**，
         * 而这一场只备下 89 段——轮询 354 次换来 89 段，效率极低。
         * 更关键的是**预加载完全没有提前量**（逐段对齐，差仅几毫秒）：
         * ```
         * 260章2  预加载提交 12:14:37.213 / 播放开始 12:14:37.218
         * 260章4  预加载提交 12:14:44.288 / 播放开始 12:14:44.296
         * 260章8  预加载提交 12:15:05.615 / 播放开始 12:15:05.625
         * ```
         * 预加载备的那一段，正好就是播放马上要播的那一段——「跨章预加载」
         * 退化成「跟着播放走」，一秒都没领先。姓名分析搭在这条链上，
         * 于是必然赶不上：轮到该段时才开始分析，这一段就干等 6~7 秒。
         *
         * ## 为什么「直接 return false」抢不到窗口
         * 播放每 1~3 秒就提交一笔，而调度器「没活干就 delay 1.5s」再回来。
         * 等到它回来时，下一笔播放往往**又已经提交**（`playbackSubmitAt` 刚刷新），
         * 判据再次成立 ⇒ 再放弃 ⇒ 无限循环。它只在「恰好有一瞬间播放落定」时
         * 才能挤进去，这就是它总与播放同时提交的机械原因。
         *
         * ## 改法（有界等待，不长期占播放路径）
         * 判据命中时**不立刻放弃**，而是小步轮询等播放落定；落定后立刻跑这一轮，
         * 抢在下一笔播放提交之前把 1 段备上。最多等 [PRELOAD_YIELD_WAIT_MS]：
         *   · 等到落定 → 正常往下走，本段备货（此时播放正在播，不排队）；
         *   · 等满仍未落定（播放确实在持续请求）→ 照旧 return false，
         *     保持 11-21「播放即掐」的原意，绝不把备货塞进播放的串行队列。
         *
         * 为什么安全（不会重蹈「起播等 3 秒」那类问题）：
         *   · 等待期**不持有** preloadGate（本函数在锁外 return），不阻塞任何东西；
         *   · 等待期**不发请求**，只在检测到播放落定后才提交 1 段；
         *   · 上界 1.2s 比调度器自身 1.5s 的轮询间隔还短，等于把「下次再试」
         *     提前到「本轮的尾巴」，不增加任何额外负载。
         */
        private const val PRELOAD_YIELD_WAIT_MS = 1_200L
        /** 让路等待的小步轮询间隔：够细以抓住播放落定的瞬间，又不至于忙等 */
        private const val PRELOAD_YIELD_POLL_MS = 60L
        /**
         * 11-22 00:3x（用户实报「点击朗读到开始播放要花好久」）：**首段优先窗口**。
         *
         * 起播瞬间预热协程往往比播放笔早几十毫秒抢到 UTTS 的引擎串行门，一段就要
         * 2~3 秒，首段只能排队——真机实测首段晚了 3.1 秒才出声（4.82 秒总延迟里
         * 占 64%）。预热协程启动时先等这个窗口，把起跑让给播放笔。
         *
         * 为什么是 600ms：播放笔在 `act=play` 之后通常 50~300ms 内提交（真机
         * 23:29:38.211 收到 play → .542 提交 Legado0，约 330ms）。600ms 覆盖绝大多数
         * 情况，又短到备货几乎无感——而 `awaitFirstPlaybackWindow` 一旦检测到
         * 播放笔已提交就**立即返回**，不硬等满窗口。
         */
        private const val PRELOAD_FIRST_WINDOW_MS = 600L
        /**
         * 11-17：慢重试总预算——「永不跳句」模式下给失败段原地重来的总时长。
         *
         * 为什么要有上限：用户原话「只能满足一定的条件它才跳，不然一直等着一个不会合成的
         * 卡在那儿也不行」。真合不出来的段（插件永久拒绝该文本等）不能无限占着播放队列。
         *
         * 11-18 六调（用户令「第一个按你推荐的来」）：**不再写死 60 秒，改为跟随
         * 朗读设置里的「系统TTS合成超时」**——而且是从**进入本段合成**就起算的
         * 整段预算，快重试那几次也计入。理由：
         *   ① 用户设那个值的语义就是「一段最多等多久」，慢重试另开一个 60 秒
         *      等于让实际等待比他设的值更久（设 20s 照样能等到 60s+），自相矛盾；
         *   ② 写死常量界面上看不到，用户无从调节，只能找我改代码；
         *   ③ 现在「设 60 就 60 秒见分晓」，一个数字管全部。
         *
         * 已知不精确（如实记）：单轮合成一旦发出就无法中途掐断（看门狗/网络都已
         * 在里面），所以最后可能**超出预算不到一轮**的时间才进终局。一轮的下限
         * 是「一次快重试链」，快速失败型约 1~2 秒，卡死型约 看门狗×次数。
         * 判据仍用「发新一轮之前」检查，不做中途打断——那样要改 synthesizeText
         * 的签名把剩余时间透传进去，收益不抵复杂度。
         *
         * 超预算后的行为仍看用户那个开关（「失败后跳过继续朗读」）：
         *   开 → 静音占位跳过；关 → 暂停朗读。与开关文案完全一致。
         */
        private val slowRetryBudgetMs: Long
            get() = AppConfig.sysTtsSynthesizeTimeout * 1000L

    }

    // ====== TTS 核心 ======
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    /** 标记主线程已经请求播放但 TTS 尚未初始化完成，onInit 回调中据此补救调用 play() */
    private var pendingPlay = false

    // ====== ExoPlayer & 播放（完全复制转发器模式）======
    // 朗读播放音量增益处理器：注入 ExoPlayer 音频管线，解码后 PCM 上做乘性增益 + tanh 软限幅
    private val gainAudioProcessor: GainAudioProcessor by lazy {
        GainAudioProcessor(AppConfig.readAloudVolumeGain)
    }

    private val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return DefaultAudioSink.Builder(this@TTSReadAloudService)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainAudioProcessor))
                    .build()
            }
        }
        ExoPlayer.Builder(this, renderersFactory).build()
    }
    private var playIndexJob: Job? = null
    /** 11-16：后台预热链（串行，一次只允许一条；见 launchPreloadBatch）。 */
    private var preloadChainJob: Job? = null
    /** 11-16：STATE_ENDED 后重新拉起合成时的重入守卫（见该分支注释）。 */
    private var resumeGuardAfterEnded = false

    /**
     * 11-19：章末判定拦下后的自愈重播计数。
     *
     * 「段表只有章名」时不能跳章（否则整章被吞），也不能就此停住（用户听感是
     * 「突然没声了」）。这里安排一次延迟重播：重新走 play() 会重建 contentList，
     * 正文若已就位就能正常接着念。计数防死循环——连续 [TITLE_ONLY_RETRY_MAX] 次
     * 仍只有章名，说明这本源真的给不出正文，打日志停手，不再空转。
     * 正常开播成功（走到合成循环并产出段落）时清零。
     */
    private var titleOnlyRetryCount = 0
    private val TITLE_ONLY_RETRY_MAX = 5
    private val TITLE_ONLY_RETRY_DELAY_MS = 800L

    /** 11-16：首回调看门狗超时的内部信号（不走 Exception 抛给上层，仅用于跳出等待）。 */
    private class SynthesisFirstCallbackTimeout : Exception("first callback timeout")
    private var playErrorNo = 0
    // 11-13：framework 双 onError 重载去重（见 onError 内注释）
    private val handledErrorCallbacks = java.util.Collections.synchronizedSet(HashSet<String>())
    private var itemRetryPending = false

    /**
     * 11-13：主动中断代次。
     *
     * play()（翻页/跳段/重放）每次 +1 并 stop() 引擎，在途那笔随即收到 -3。
     * 但它的重试循环跑在 synthScope（与播放任务刻意解耦，见 inFlightSyntheses 注释），
     * 不看这个代次就会把"我们自己掐的"当成失败继续重试：
     * 真机日志 08:06 段实测——8 个 -3 全部紧跟在跳段后 0.01~1.2 秒内，
     * 用户越点下一段越卡，三次耗尽后写静音占位（那段就哑了）。
     *
     * 判据：进入 doSynthesizeText 时记下代次，等待回调为空时若代次已变
     * → 抛 CancellationException 走取消分支（不重试、不占位、不重建引擎）。
     */
    @Volatile
    private var abortGeneration = 0

    /**
     * 11-13 深夜：上一动作刚调过 playStop()（已 abortGeneration++ 并 textToSpeech.stop()）。
     *
     * 背景（真机日志实锤「每次跳段必然多一次重复提交」）：
     *   nextP(): stopReadAloudInternal() → playStop()
     *                ↑ abortGeneration++、textToSpeech.stop()
     *            nowSpeak++
     *            playReadAloudInternal() → play()
     *                ↑ **又** abortGeneration++、**又** textToSpeech.stop()
     *
     * play() 里那次 stop() 本意是掐掉「正在朗读的那一笔」，但紧接着的上一步
     * playStop() 已经掐过并自增过代次了，这次是重复动作。而 stop() 是异步的：
     * 引擎收到 stop 会把队列里的 utterance 全部回 -3——此时新段的请求可能已经
     * 提交进去了，于是新段刚提交就被自己这记 stop 打断，回一个 -3，
     * 再被重试链重新提交一次。
     *
     * 真机日志（23:27:34）：
     *   .095 nextP: 9→10        .099 play: nowSpeak=10
     *   .109 onError Legado10 -3     ← 10ms 后新段被自己掐掉
     *   .429 提交引擎 index=10   ← 0.3s 后重新提交
     * 一轮操作 15 次提交只覆盖 9 个段落，5 个段落被取消后重提。
     *
     * 修法：playStop() 置位，play() 消费一次后复位并跳过那两行。
     * 不影响「play() 单独被调用」（如首次播放、resume）的路径——那时标志是 false，
     * 照旧执行 stop + 代次自增。
     */
    private var lastActionWasStop = false

    // ====== 缓存目录 ======
    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        val dir = File(baseDir, "systemTTS")
        if (!dir.exists()) dir.mkdirs()
        dir.absolutePath + File.separator
    }

    // ====== 合成核心 ======
    /**
     * 合成专用服务级协程作用域（10-12 在途去重配套）。
     * 与调用方 job 解耦：某一方（翻页时的老 play 任务）被 cancel 时，
     * 已提交给引擎的那笔合成仍要跑完并落缓存，等在它上面的人也要拿到结果。
     * 生命周期与 service 同步，onDestroy 里取消。
     */
    private val synthScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            // 兜底：任何漏网的未捕获异常只记日志，不走默认 handler 崩应用
            AppLog.put("TTS合成作用域未捕获异常\n${e.localizedMessage}", e)
        }
    )
    /** 把 TTS onDone/onError 回调转成挂起函数的 CompletableDeferred */
    private val synthesisCompleters = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    /**
     * 11-16 用户实报（06:20 卡死）：`Legado51` 提交返回 SUCCESS 后，
     * 引擎**既没有 onStart 也没有 onDone/onError**，播放链一直等到
     * `sysTtsSynthesizeTimeout`（默认 120 秒）才放弃，用户看到的就是「卡住不动」。
     *
     * 所以补一个「首回调看门狗」：提交后 [firstCallbackTimeoutMs] 内
     * 连 onStart 都没来，就判定引擎根本没接收这笔请求 → 立刻清实例走重试，
     * 不必陪它干等两分钟。
     *
     * 为什么盯 onStart 而不是 onDone：onStart 是「引擎已接手」的最早信号；
     * 长句合成本身可能超过看门狗时长，所以只在**没有任何回调**时才触发。
     */
    private val startedCallbacks = ConcurrentHashMap.newKeySet<String>()
    /** utteranceId -> 合成目标临时文件 */
    private val synthesisTargetFiles = ConcurrentHashMap<String, File>()
    /**
     * 在途合成（10-12 修「同一段文字被合成两次」）：key = 目标缓存文件绝对路径。
     *
     * 为什么必须加：`synthesizeText` 开头只查「缓存文件是否已存在且有效」，
     * **在途那一笔查不出来**——上一笔还没写盘（合成中/等待回调），新的调用就会
     * 判定「没缓存」再提交一次，同一段文字被送进引擎两遍、各合成一次。
     * 翻页/跳段时命中率最高：`seekToParagraphByProgress` 改完 `nowSpeak` 直接 `play()`，
     * 而 `play()` 的 `synthesisTask?.cancel()` 只能撤协程，撤不掉**已提交给 TTS 引擎**的请求，
     * 于是老任务在途的那一笔与新任务重头那一笔撞成两笔（真机日志：同文本间隔 74ms 两笔，
     * 两笔都成功、都没有 `_retry` 后缀 ⇒ 不是超时重试，是两次独立提交）。
     * 预加载走自己的 utteranceId 也会撞上同一段，按「缓存文件」而非 utteranceId 做键才能一起盖住。
     */
    private val inFlightSyntheses = ConcurrentHashMap<String, CompletableDeferred<File?>>()

    /**
     * 11-22 14:4x：`countBufferedAhead` 的短时缓存（治它自己的 IO 放大）。
     *
     * ## 为什么需要
     * 该函数在 [synthesizeText] 里**同步**执行（播放主路径），每笔合成都调一次。
     * 11-22 改成「跨章累加」后，一次统计要扫：本章剩余段（最多 ~100）
     * + 后续每章全部段（第260章78 + 261章132 + 262章110 = 320）≈ **419 次 stat**。
     * 真机一场 272 次提交 ⇒ 最坏 11 万次 stat 全压在播放路径上。
     *
     * ## 做法
     * 同一个「起点段 + 章名」在 [BUFFERED_COUNT_TTL_MS] 内复用上次结果。
     * 播放每段推进一次起点就会换 key，所以缓存天然按「段」失效；
     * TTL 只用于兜住**同一段被反复重报**（重试/并发抢跑同段）的情况。
     * 缓存只是「少算几次」，算不准的代价是进度条数字晚一个心跳更新——可接受。
     */
    private data class BufferedCountCache(val key: String, val at: Long, val value: Int)

    @Volatile
    private var bufferedCountCache: BufferedCountCache? = null

    // 11-22 21:4x：缓冲上报诊断的节流状态（**临时诊断**，定位后连同 logBufDiag 一起删）
    @Volatile
    private var lastBufDiagKey = ""

    @Volatile
    private var lastBufDiagAt = 0L

    /**
     * 11-14 修「连点跳段时同一段被反复重提」（方案 A：取消后短期保留登记 + 冷却）。
     *
     * 现场（真机 01:08:34~50，用户连点下一段 16 次）：i17 被提交 6 次、取消 5 次。
     * 原因不是去重失效，而是**去重表被自己的 stop() 绕过**：
     *   play() → stop() → 在途那笔收到 -3 → 走取消分支 → inFlightSyntheses.remove()
     *   → 下一次 play() 进来查表为空 → 判「这段没在合」→ 又提交一遍 → 再被 stop 掐…
     * 于是每点一次就多一笔完整请求（UTTS 侧同一句「已取消」后重来）。
     *
     * 修法：取消时**不立即销账**，改为留一个冷却记录（cacheKey → 取消时刻）。
     * 之后同 key 的调用方先短路等待一小会儿、再看一眼缓存：
     *   · 上一笔其实已经合成完（只是没走到 renameTo 就被掐）→ 命中缓存/tmp 直接复用；
     *   · 仍在冷却窗口内 → 不重复提交，等窗口过去再定；
     *   · 冷却过期 → 正常重新提交（保证不会永久卡住）。
     * 判据只用「时间」，不猜内容，失败时最坏退化成现状（多等 ~200ms），不会更糟。
     */
    private val canceledAt = ConcurrentHashMap<String, Long>()
    /** 取消后的冷却窗口：短到用户感觉不到，长到足以覆盖连续点击的间隔 */
    private val canceledCooldownMs = 1200L

    /**
     * 11-15 用户实况（原话）：「我基本上没有老老实实的听，我都是听着动着翻页呀、
     * 跳章呀，然后朗读上一段、下一段就这样一直不停的动」——即**连续操作是常态**，
     * 不是偶发。真机日志实锤（appLog-26-11-15_01-27，13 秒内 8 次跳转）：
     * 80 次提交只覆盖 59 个段，其中 `Legado59` 被提交 **7 次**，每次 SUCCESS
     * 后立刻 -3（被下一次 play() 的 stop() 掐掉），音频没落盘、下次还得重来。
     *
     * 机制：合成循环是 `for (i in nowSpeak until endIndex)`，从当前段一路往后合成
     * 到章末。每点一次跳转，这个循环就**从头重跑**，把后面几十段重新提交一遍；
     * 而每笔都要等引擎回调、每次都花钱。
     *
     * 修法（不动播放速度）：**当前段立即合成**（用户感到"点哪播哪"的关键），
     * 后面的段落等这么久没有新跳转才开始预合成。连续操作时每跳转只合成 1 段，
     * 停下来后才一次性补齐后续；不操作时行为与原来完全一致（多等 500ms，
     * 而那时用户正在听当前段，感知不到）。
     */
    private val forwardSynthQuietMs = 500L    /** 主合成协程 */
    private var synthesisTask: Job? = null

    // ==================== 跨章预加载（11-20 重写） ====================

    /**
     * 跨章预加载**常驻调度器**（旧版是一次性协程 + play() 无条件 cancel）。
     *
     * 旧版致命点（11-20 日志实锤 20:06:00 / 20:07:08）：`play()` 每次翻章都
     * `preloadJob?.cancel()`，而 `preloadNextChapter()` 只在「当前章全部合成完」
     * 时启动一次 ⇒ 链刚起步就被下一章掐死，永远只备得上第 1 个目标章的头几段，
     * offset=2、3 两章一次都没轮到（用户看到的「开了 3 章却没有缓存」）。
     *
     * 现在改成常驻循环：play() 只唤醒、不取消；每轮以**当前章**为基准重算目标
     * （当前章 +1 .. +audioPreDownloadNum），按章记录进度、进度最小者优先，
     * 天然轮转（三章轮流推进，不会死在第一章）。只有 onDestroy 才停。
     */
    private var preloadJob: Job? = null

    /** 当前跨章预加载目标章（-1=空闲），只用于对外发状态，不参与逻辑 */
    @Volatile private var preloadActiveChapter = -1

    /**
     * 章索引 → 已备到第几段（0=未开始）。只在 preloadJob 协程内读写，单线程无需加锁。
     *
     * 11-21 14:1x（真机 14:06:40 stop→14:07:02 play 实锤）：原为服务成员变量，
     * 服务 stop→重建（用户切章/重新定位）即清零——245 章已备 7 段的进度归零，
     * 重建后从第 1 段重推（已备段虽秒回但逐段出卡），日志页出现「1/81 重来」的
     * 进度回退观感。改为**伴生对象级**：跨服务生命周期保留，重建后直接跳到
     * 没备过的段续备。范围外章仍由轮函数的 validRange 清理兜底，不会拿旧书章空转。
     */
    private val preloadChapterProgress get() = Companion.preloadProgressStore

    /** 章索引 → 排版好的段表。排版成本高（整章 StaticLayout），一章只解一次 */
    private val preloadChapterCache get() = Companion.preloadCacheStore

    /** 章索引 → 正文未就绪时的下次重试时刻（毫秒），避免每轮重复解析/刷日志 */
    private val preloadChapterRetryAt get() = Companion.preloadRetryStore

    /**
     * 11-20：单段失败计数（key = "章索引|段号"）。
     *
     * 为什么需要：边播边预加载时，**播放请求会把正在跑的预加载那一笔掐掉**
     *（UTTS 的「播放优先、预热让路」，原设计如此）。被掐掉的那段如果也算「已处理」，
     * 就永久漏备——预加载跑得越快、被掐得越频繁，漏得越多。
     * 现在的口径：失败**不推进进度**，退避后重试同一段；连续失败 3 次才跳过
     * （真正合不出来的段不能无限占着后台）。
     */
    private val preloadSegmentFailCount = HashMap<String, Int>()

    /**
     * 11-27：章索引 → 「连续空探测」轮次数（见 [PRELOAD_DESYNC_MAX_ROUNDS]）。
     *
     * 一轮里若逐段探测**一个文件都没命中**，计 1 次；任何一次命中即清零。
     * 连续达到阈值 ⇒ 判「该章探测判据已失配」，整章退避而不是从头重扫。
     */
    private val preloadDesyncRounds = HashMap<Int, Int>()

    /**
     * 11-27：段索引 → 「被播放打断」次数（key = "章索引|段号"）。
     *
     * 与 [preloadSegmentFailCount] 是**两套账**：打断不是失败（引擎没出错，
     * 是我方主动让路），所以不计失败、不进「连续 3 次跳过」逻辑；
     * 但同一段反复被打断要退避，否则会与播放反复抢引擎（见打断分支注释）。
     */
    private val preloadInterruptCount = HashMap<String, Int>()

    /**
     * 预加载用的段表。
     *
     * ⚠️ 11-22 18:4x（用户实报「同一章，播放卡写 /133、预加载卡写 /132，看着乱」）：
     * 这份段表必须与主播放的 `contentList` **逐项同口径**，包括末尾那个章末占位段。
     *
     * 主播放侧（BaseReadAloudService ~2032 行）建表时：
     *     contentList = getNeedReadAloud(0,false,0).split("\n").filter{...}.toMutableList()
     *         .apply { add(" ") }        // ← 末尾补一个 " " 当章末标记
     * 而预加载这边只做了 split+filter、**没补**，于是同一章两套口径差 1：
     * 播放 133 段、预加载 132 段。日志页同一段的 N/M 分母不同，用户会觉得"乱"；
     * 更实际的影响是 `para_total` 不一致，进度条/折叠统计的基数都跟着偏。
     *
     * 在构建处统一补齐（而不是在每个使用点 +1），两条源路径都覆盖。
     */
    private data class PreloadedChapter(
        val title: String,
        val segments: List<String>
    )

    /**
     * 11-20：预热请求串行门。
     *
     * 为什么必须加：跨章预加载（preloadJob）与同章滚动预热（preloadChainJob）是两条
     * 独立协程，各自「一次备 1 段」。它们同时跑就是事实上的**并发 2 路**预热，
     * 而 11-15/11-16 的定案是「一次只发 1 个请求，绝不并发撞插件串行门」
     * （06:40 那次 6 分钟假死的根因就是 4 并发）。加这把门后两者天然串行：
     * 谁先拿到谁跑，另一条等它跑完再发——总并发恒为 1。
     */
    private val preloadGate = kotlinx.coroutines.sync.Mutex()

    /**
     * 11-18：播放请求最近一次**提交**引擎、最近一次**收尾**（成功/失败/取消都算）的时刻。
     * 只服务「预热让路」一个判断，不参与播放逻辑：
     * 提交后超过 [PRELOAD_YIELD_PLAYBACK_STUCK_MS] 仍未收尾 ⇒ 上游慢，预热本轮放弃。
     */
    @Volatile private var playbackSubmitAt = 0L
    @Volatile private var playbackSettledAt = 0L

    // ====== 字幕同步 ======
    private var subtitleSyncJob: Job? = null
    /** 音效规则计算任务。与字幕同步循环解耦，避免长段落计算阻塞字幕显示 */
    private var effectCalcJob: Job? = null
    private var currentSubtitles: List<String> = emptyList()
    private var currentSubtitleCharRanges: List<Pair<Int, Int>> = emptyList()

    // ====== 静音超时检测 ======
    private var silentPlayCheckJob: Job? = null

    // ====== 生命周期 ======

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        BgmManager.init(this)
        // loadBgmFiles 移到 play() / playByIndex() 的协程中后台执行，
        // 避免 onCreate 主线程同步遍历文件阻塞朗读启动
        initTts()
        // ⚠️ 这里**不能**调 upSpeechRate()：它末行无条件 play()。
        // onCreate 期 TextToSpeech 尚未初始化完成，那次 play() 只会置 pendingPlay 等
        // onInit 回调补播；而 onStartCommand(play) → newReadAloud() 随后还会再 play() 一次。
        // 两次都从 nowSpeak=0 起跑，play() 开头的 synthesisTask?.cancel() 只能撤协程、
        // 撤不掉已提交给 TTS 引擎的合成请求 ⇒ 第 0 段（章首）被合成两遍。
        // 真机日志实证：同一 utteranceId:Legado0 连续两轮 onStart/onDone（无 _retry 后缀
        // ⇒ 非重试，是两次独立提交），且「TtsEngineActivator 尝试激活」连打两次
        // （该调用在 play() 内仅一处）。仅新起服务时命中（服务已在跑则 onCreate 不再执行）。
        // 语速改到 onInit 里应用（见下），播放一律交给 onStartCommand 链路。
        observeEvent<Int>(EventBus.READ_ALOUD_SEEK_PARAGRAPH) { progress ->
            seekToParagraphByProgress(progress)
        }
    }

    /** 只把当前语速应用到 TTS 引擎，不触发 [play]（须在引擎初始化完成后调用） */
    private fun applySpeechRateToEngine() {
        if (!AppConfig.ttsFlowSys) {
            val rate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(rate)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
        clearTTS()
        BgmManager.release()
        synthesisTask?.cancel()
        playIndexJob?.cancel()
        preloadJob?.cancel()
        silentPlayCheckJob?.cancel()
        // 合成专用作用域：与 service 同生命周期（10-12 在途去重配套）
        synthScope.cancel()
        stopSubtitleSync()
        // 停止朗读后在后台清理过期音频缓存：进程级协程，界面/服务销毁都不受影响；
        // 单次删除有数量与时间上限，不会和朗读 / AI 章节分析抢磁盘
        Coroutine.async { removeCacheFile() }
    }

    // ====== TTS 初始化 ======

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val ttsEngineStr = ReadAloud.ttsEngine
        val engine = when {
            ttsEngineStr.isNullOrBlank() -> null
            ttsEngineStr.startsWith("{") -> {
                GSON.fromJsonObject<SelectItem<String>>(ttsEngineStr).getOrNull()?.value
            }
            else -> ttsEngineStr
        }
        val finalEngine = engine?.takeIf { it.isNotBlank() } ?: AppConfig.sysTtsPackageName
        LogUtils.d(TAG, "initTts engine:$engine finalEngine:$finalEngine")
        textToSpeech = if (finalEngine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            AppConfig.sysTtsPackageName = finalEngine
            TextToSpeech(this, this, finalEngine)
        }
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        pendingPlay = false
        // 11-27：连 TtsEngineActivator 的常驻探测连接一起释放。
        // 本函数的语义就是「这个引擎实例不要了」，探测连接留着会继续占着
        // UTTS 侧的一条连接；释放后再 activateTtsEngine 会重新建，符合预期。
        runCatching { io.legado.app.help.tts.TtsEngineActivator.releaseProbeConnection() }
        // 11-18 用户报障修复（15:09 实锤卡 96 秒）：清实例时**顺手了结所有在途请求**。
        //
        // 为什么必须做：系统 TTS 引擎一旦 shutdown，已提交的那几笔就**再也不会回调**
        //（onStart/onDone/onError 全无）。以前只把实例置空，于是每一笔只能各自等满
        // 「首回调看门狗」（默认 96 秒 = 总超时 80%）：
        //   15:08:00 提交 Legado54 → 15:08:02 用户点播放、跳过该段 → 请求悬挂
        //   15:09:36 提交 Legado75（正常段）
        //   15:09:36 Legado54 看门狗到点 → clearTTS() → 把刚提交 750ms 的 Legado75 一起作废
        //   15:11:12 Legado75 也等满 96 秒 → 重试 0.8 秒成功
        // 用户听到的就是中间那 98 秒空白（UTTS 侧一行日志都没有：请求根本没到）。
        //
        // 现在：清实例的同时把这些请求全部 complete(null) ⇒ 立刻走「失败→重试」，
        // 由重建后的引擎重新合成，不再白等看门狗。
        // 安全性：clearTTS 的每个调用点都意味着「这个引擎实例不要了」，在途请求本就收不到
        // 回调，主动了结只是让失败路径提前，不会误杀仍然有效的请求。
        val pending = synthesisCompleters.values.toList()
        synthesisCompleters.clear()
        synthesisTargetFiles.clear()
        pending.forEach { d -> runCatching { d.complete(null) } }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                if (AppConfig.sysTtsPackageName.isNullOrBlank()) {
                    val actualEngine = it.defaultEngine
                    if (!actualEngine.isNullOrBlank()) {
                        AppConfig.sysTtsPackageName = actualEngine
                        LogUtils.d(TAG, "系统TTS引擎包名已保存: $actualEngine")
                    }
                }
                val voiceName = it.voice?.name
                if (!voiceName.isNullOrBlank()) {
                    AppConfig.sysTtsVoiceName = voiceName
                    LogUtils.d(TAG, "系统TTS voice 已保存: $voiceName")
                }
                ttsInitFinish = true
                // 引擎就绪后再应用语速（原在 onCreate 经 upSpeechRate 应用，但那里的
                // play() 副作用会导致章首重复合成，故语速挪到这里，只设语速不起播）
                applySpeechRateToEngine()
                if (pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    // ====== 合成回调监听 ======

    private val ttsUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(s: String?) {
            LogUtils.d(TAG, "onStart synthesis utteranceId:$s")
            // 11-16：首回调看门狗用（见 startedCallbacks 注释）
            s?.let { startedCallbacks.add(it) }
        }

        override fun onDone(s: String?) {
            LogUtils.d(TAG, "onDone synthesis utteranceId:$s")
            // 【诊断日志】回调到达（10-12 加，只记录不改逻辑）
            AppLog.putDebug("TTS回调 onDone id=$s")
            s ?: return
            startedCallbacks.remove(s)
            val targetFile = synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            if (completer != null) {
                if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                    completer.complete(targetFile)
                } else {
                    completer.complete(null)
                }
            }
            // 11-22 21:5x（用户实报「正在播第 8 段、第 12 段已经合成完，缓冲却写 3 段」）：
            // 缓冲数原来只在「提交合成」和「段切换」两处算，合成**完成**这一刻不刷新
            // ⇒ 第 12 段落盘了，数字还停在上一笔提交时的 3，要等下一笔提交（2~4 秒）
            // 才跳成 4。在完成回调里补一次（IO 线程；countBufferedAhead 内部有 500ms
            // TTL 兜重复扫描），数字就跟着落盘即时走。
            refreshBufferedAhead()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(TAG, "onError utteranceId:$utteranceId errorCode:$errorCode")
            // 【诊断日志】回调到达（10-12 加，只记录不改逻辑）
            AppLog.putDebug("TTS回调 onError id=$utteranceId code=$errorCode")
            // 11-13 巡检实锤：framework 会先回调新签名 onError(utteranceId, errorCode)
            // 再回调旧签名 onError(s)（同一事件两次），日志每条 -3 出现两行。
            // 这里登记已处理 id，旧重载据此幂等跳过（completer 只 complete 一次）。
            if (utteranceId != null) {
                handledErrorCallbacks.add(utteranceId)
                startedCallbacks.remove(utteranceId)
            }
            synthesisTargetFiles.remove(utteranceId)
            val completer = synthesisCompleters.remove(utteranceId)
            completer?.complete(null)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String?) {
            LogUtils.d(TAG, "onError utteranceId:$s")
            // 新签名已处理过同一事件则跳过（防双 complete/双日志，见上方注释）
            if (s != null && s in handledErrorCallbacks) {
                handledErrorCallbacks.remove(s)
                return
            }
            startedCallbacks.remove(s)
            synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            completer?.complete(null)
        }
    }

    // ====== 核心合成方法 ======

    /**
     * 合成入口（10-12 加「在途去重」）：同一段文字若已有一笔在合成，本次**不再提交第二笔**，
     * 直接等那一笔的结果——主播放、跨章预加载、翻页重发三条路都走这里，天然共用。
     *
     * 去重键 = 目标缓存文件绝对路径，与 `doSynthesizeText` 内部的缓存键完全同源，
     * 只按「段落文本 + 下标 + 章节标题」判同一段，不受 utteranceId（各调用方自己生成）影响。
     */
    /**
     * 11-22（用户令「跨章备的量得显示」）：统计**从 [fromSeg] 往后、本章内已备好的段数**。
     *
     * ## 为什么需要它
     * 吸顶进度条要表达「还能连着听多少」。UTTS 侧只知道「合成前沿在第几章第几段」，
     * 算不出「往后备了多少」——而后者才是用户要的那个数。所以由 U阅 侧算好上报。
     *
     * ## 为什么只数本章、不跨章累加
     * 跨章累加需要逐章遍历下一章的段表并查缓存文件，而下一章的段表在本章合成时
     * **未必已构建**（ReadBook 只保证当前章就绪）。强行算会引入「段表未就绪」的
     * 空窗与额外磁盘扫描，收益却只在「已备超一整章」时才有。
     * 现实节奏下（合成循环追不上播放）几乎不会备满整章，先把本章这个主战场做准；
     * 真备满一章时 UTTS 侧会显示「整章已备」，语义仍然正确。
     *
     * ## 判据
     * 纯**缓存文件存在性**，不读音频内容：
     *   · 与播放路径 `synthesizeText` 开头那条命中判据同源（file.exists && length>0），
     *     保证「我数成已备的，播放时一定真能秒播」；
     *   · 不调 AudioCacheValidator 深检——那是命中瞬间的一次性校验，放这里会变成
     *     O(N) 次全文件读，纯烧 IO。
     *
     * 复杂度 O(剩余段数)，典型的 list/stat 级开销，几十~几百段无感。
     * 任何异常都吞掉返回 -1（进度条少画一个数字，不影响朗读）。
     */
    /**
     * 11-22 21:5x（用户：「一章 75 段，章节标题算第 1 段、74 是最后一段，播到 74 停了
     * 我还以为没播完这一章」）——**对外报的章内段总数**：
     *
     * 段表末尾那段是 [BaseReadAloudService] 建的「章末占位段」（一个空格），
     * 只负责在章尾补 50ms 静音、让播放链自然收口，**不是可朗读正文**。
     * 把它算进分母，就会出现「播完正文停在 74/75」的假未完成。
     *
     * 口径（三条路必须同源，否则日志卡的分母、吸顶进度行的分母、预加载的分母
     * 又会各拍一个数——11-22 d0af83f 刚把三处收成一致，这里只做「同时减 1」）：
     *   · 播放：[ReadProgressFile.report] 的 totalSegs、请求的 para_total；
     *   · 预加载：[ReadProgressFile.reportSynthFront] 的 totalSegs、para_total；
     *   · 段号本身不变（标题段仍是第 1 段，正文最后一段 = 分母值 ⇒ 播满即 N/N）。
     *
     * 为什么用「末尾是否空白」判定而不是 size-1：预加载段表由
     * `buildPreloadSegments` 的 `seal()` 补同一个 " "，两处一条判据即可；
     * 万一将来不补了，这里也不会把真实正文段错删掉。
     */
    private val speakTotalSegs: Int
        get() {
            val last = contentList.lastOrNull()
            return if (last != null && last.isBlank()) contentList.size - 1
            else contentList.size
        }

    private fun countBufferedAhead(fromSeg: Int, chapterTitle: String): Int {
        return runCatching {
            if (fromSeg < 0 || contentList.isEmpty()) {
                logBufDiag(
                    key = "early|$fromSeg|${contentList.size}",
                    line = "缓冲上报诊断: 返回-1 原因=入参无效 from=$fromSeg contentList=${contentList.size}",
                )
                return -1
            }
            // ===== 11-22 15:2x：切章窗口保护 =====
            //
            // `contentList` 未必属于当前章：BaseReadAloudService 用
            // `contentListChapterIndex` 记录「段表建于第几章」，切章瞬间
            // textChapter 已换成新章、而 contentList 还是旧章的（真机 18:21 实证，
            // 该字段正是为修这个 bug 才加的）。此时若拿新章的 nowSpeak
            // 去索引旧章的段表，段号全错位 ⇒ 算出一个毫无意义的数字。
            //
            // 处理：段表章号与本笔章号不一致时直接返回 -1（不显示数字）。
            // 宁可这一瞬间少一个数字，也不显示假数据——UTTS 侧对 -1 的
            // 处理是「退回旧画法、不画缓冲数」，语义正好。
            if (contentListChapterIndex >= 0 &&
                contentListChapterIndex != ReadBook.durChapterIndex
            ) {
                logBufDiag(
                    key = "cut|$contentListChapterIndex|${ReadBook.durChapterIndex}",
                    line = "缓冲上报诊断: 返回-1 原因=切章窗口 " +
                        "contentListChapterIndex=$contentListChapterIndex " +
                        "durChapterIndex=${ReadBook.durChapterIndex}",
                )
                return -1
            }
            // 11-22 14:4x：同一段 500ms 内不重复全扫（成本见 bufferedCountCache 注释）。
            // 播放每推进一段起点就变，key 自然失效；这里只兜「同段被反复重报」。
            val cacheKey = "$fromSeg|$chapterTitle"
            bufferedCountCache?.let { c ->
                if (c.key == cacheKey &&
                    SystemClock.elapsedRealtime() - c.at < BUFFERED_COUNT_TTL_MS
                ) {
                    return c.value
                }
            }
            val start = (fromSeg + 1).coerceAtLeast(0)
            // ===== 11-22 17:5x（用户实报「缓冲到下一章了，进章数字却归 0」）=====
            //
            // ## 现场（真机 15:52:49 截图 + TTS 日志对账）
            // 第6章当时已备 65/69 段，吸顶行却写「第5章 51/52 **缓冲 0 段**」，
            // 而且没有「·跨章」后缀 —— 后面的跨章累加整段没执行。
            //
            // ## 根因：这行 `return 0` 把「章末」误判成「没有缓冲」
            // `start = fromSeg + 1`，播放到本章最后一段时 `start > lastIndex` 成立，
            // 于是直接 return 0 —— 可跨章累加**恰恰就是从这里开始的**：
            // 本章已播完 ⇒ 缓冲全在后续章里 ⇒ 该报的正是「后续章已备多少段」。
            // 早退把唯一有意义的情况掐掉了，所以越接近章末数字越假（先是 0，
            // 带「·跨章」后缀的分支也永远走不到）。
            //
            // ## 修法：删掉提前 return，本章已读完时让本章循环自然空转（start..lastIndex
            // 为空区间，Kotlin 不迭代），n 保持 0，后续『跨章累加』照常执行
            // —— 这才是章末应有的读数（缓冲全在后续章里）。
            // ===== 11-22 13:0x（用户实报「缓冲范围不对，基本没显示过」+ 截图「6/105 缓冲 0 段」）=====
            //
            // ## 现场
            // 用户截图吸顶行恒为「6/105 缓冲 0 段」，浅绿段从来没出现过。
            // U阅 AppLog 里已备段号是**断续**的（第259章备了 31 段、最大段号 81，
            // 但中间有 50 个空洞；第4章 13 段 / 51 个空洞）——因为跨章备货每轮
            // 都被「播放请求在飞」让路（真机同一场次让路 354 次），只能备到零星几段。
            //
            // ## 旧判据为什么恒 0
            // 旧实现从 fromSeg+1 往后逐段查，**遇到第一个缺失就 break**，算的是
            //「连续已备长度」。而上面已备本身就是断续的 ⇒ 当前段紧跟的下一段
            // 十有八九没备 ⇒ 循环第一次就 break ⇒ 恒为 0。
            // 数字本身没算错，是**语义选错了**：用户要的是「往后一共还能连着听多少」，
            // 那是「已备好的段数」，不是「从下一段起不断档的长度」。
            //
            // ## 新口径（用户原话「当前正在播放之后的段数都属于缓冲段数」）
            // 数**计数**：从 fromSeg+1 扫到本章末，遇到已备就 +1，遇到空洞**继续扫**
            // 不中断。这样「第 8、14、17、18、20 段备好了」就报 5，进度条有浅绿可画。
            //
            // ===== 11-22 13:4x（用户令）：「当前段之后的全算缓冲，包括下一章」=====
            //
            // 用户原话：「假如我当前在第三段朗读，那第三段之后的都属于缓冲内容，
            // 包括下一章。现在只把下一章算缓冲这不合适。」
            //
            // 旧实现**只数本章**（本函数上一版注释还写着「跨章累加暂不做」），于是：
            //   · 本章尾巴备完、下一章已备好几段时，那些段在数字上完全消失；
            //   · UTTS 侧只好用「跨章细条」这种间接表达，用户看到的是两套口径。
            // 现改为**跨章累加**：本章剩余 + 后续每一章的已备段数，全并进同一个数。
            // 这正是「还能连着听多少」的字面含义，也和 UTTS 侧「缓冲 N 段」单数字
            // 口径统一（细条仍在，但数字不再漏掉后续章）。
            //
            // ## 怎么拿到后续章的段表（成本控制）
            // 只读 `preloadChapterCache`——那是预加载已经建好的段表，**不新建**。
            // 没建过的章直接跳过（它必然还没备过货，计数为 0，跳过不丢信息）。
            // 这样既不触发排版/IO 风暴，也不会因为「下一章段表未就绪」而卡住。
            // 扫描上界取用户设置的目标章数（`audioPreDownloadNum`），
            // 与预加载自己的范围完全一致，不会扫到用户没要求的章。
            //
            // ## 顺带保留 11-22 自查修的 chapterTitle 口径
            // 本章段必须用**调用方传入的** chapterTitle（跨章预加载笔的章名是下一章，
            // 用错标题会让哈希不匹配 ⇒ 全部判成没备好）；后续章用各自段表的 title。
            // 11-22 21:4x（真机 20:46:35 实锤「暂停后缓冲只算本章、跨章 0 段」）：
            // 本章与跨章**分开计数**，各自留一个诊断字符串。这里只加统计与日志，
            // **不改数值语义**——合计仍是 localN + crossN，与改造前逐字一致。
            // ===== 11-23 23:5x（用户实拍「进 n+1 章后本章仍报缓冲 0 段」）=====
            // 本章计数改为「预加载记账 ∪ 缓存文件命中」，**按段号去重**：
            //   · 记账：预加载落盘那一刻记的 (章, 段)，是本轮真备过的权威事实；
            //   · 文件：上一场/上一轮就已落盘的缓存（记账会因切书清空、段表重建而丢），
            //     判据与播放侧 cacheHit 同函数（getCacheFileForText + exists + 长度>0）。
            // 旧实现只有文件这一路，重算名字与写入侧不一致时（多音色/换声/章名差异）
            // 整章全 miss —— 真机 22:51 第23章卡片已备到 37 段，本章却报 1，就是这一路。
            // 记账按「章内段号」存，与 contentList 下标同口径（段表末尾占位段是空串，
            // 与播放侧 speakTotalSegs 的减一规则一致，正文段下标不受影响）。
            val chapterIdxForCount =
                if (contentListChapterIndex >= 0) contentListChapterIndex
                else ReadBook.durChapterIndex
            val recordedLocal = Companion.preloadPreparedStore[chapterIdxForCount]
            var localN = 0
            var localFileN = 0
            for (i in start..contentList.lastIndex) {
                if (recordedLocal?.contains(i) == true) {
                    localN++
                    continue
                }
                val raw = runCatching { getRawText(i) }.getOrNull() ?: continue
                if (raw.isBlank()) continue
                val f = runCatching {
                    getCacheFileForText(raw, i, chapterTitle)
                }.getOrNull() ?: continue
                if (f.exists() && f.length() > 0) {
                    localN++
                    localFileN++
                }
            }
            // ===== 跨章累加：本章之后，一直到「已备到的最远章」 =====
            // 只统计**已建过段表**的章（见上，不新建）；遇到第一个没建表的章就停——
            // 预加载是「近到远」顺序备的，后面章没建表说明还没轮到它，
            // 继续往后扫不会数到任何已备段，纯浪费 stat。
            //
            // ⚠️ 11-22 21:4x：把原来的 `break` 改成「记下中断原因再跳出」，方便诊断
            // 分清「这段扫了但 0 段」和「根本没扫」（表不在 / 章号对不上）。
            var crossN = 0
            var crossDetail = ""
            var crossStop = "未进入"
            var diagCurIdx = -1
            var diagLimit = -1
            runCatching {
                val book = ReadBook.book
                val curIdx = ReadBook.durChapterIndex
                val limit = AppConfig.audioPreDownloadNum.coerceAtLeast(0)
                diagCurIdx = curIdx
                diagLimit = limit
                if (book == null) {
                    crossStop = "book=null"
                    return@runCatching
                }
                if (limit <= 0) {
                    crossStop = "limit<=0"
                    return@runCatching
                }
                for (offset in 1..limit) {
                    val idx = curIdx + offset
                    val pre = preloadChapterCache[idx]
                    if (pre == null) {          // 没建表 ⇒ 还没备到
                        crossStop = "第${idx + 1}章无段表"
                        return@runCatching
                    }
                    val segs = pre.segments
                    if (segs.isEmpty()) {
                        crossStop = "第${idx + 1}章段表为空"
                        return@runCatching
                    }
                    // ===== 11-23 23:5x：跨章也改成「记账 ∪ 文件」按段号去重 =====
                    // 原来直接 maxOf(文件数, 记账数)——两者都只覆盖一部分时（记账只记本轮
                    // 备过的、文件只命中名字对得上的）取大会低估。按段号求并集才是
                    // 「这一章能直接播多少段」的真值。
                    val recordedSet = Companion.preloadPreparedStore[idx]
                    val hitSegs = HashSet<Int>(recordedSet?.size ?: 16)
                    recordedSet?.let { hitSegs.addAll(it) }
                    var fileHit = 0
                    for (si in segs.indices) {
                        val raw = segs[si]
                        if (raw.isBlank()) continue
                        val f = runCatching {
                            getCacheFileForText(raw, si, pre.title)
                        }.getOrNull() ?: continue
                        if (f.exists() && f.length() > 0) {
                            fileHit++
                            hitSegs.add(si)
                        }
                    }
                    val hit = hitSegs.size
                    crossN += hit
                    crossDetail += (if (crossDetail.isEmpty()) "" else ", ") +
                        "第${idx + 1}章=$hit(文件$fileHit/记录${recordedSet?.size ?: 0})"
                    // 整章 0 段已备 ⇒ 备货前沿还没进这一章，再往后也不会有，停。
                    if (hit == 0) {
                        crossStop = "第${idx + 1}章整章 0 段"
                        return@runCatching
                    }
                }
                crossStop = "扫完 $limit 章"
            }
            val totalN = localN + crossN
            // 一行把「本章/跨章/合计 + 章号 + 已建表章 + 中断原因」打全：
            // 下次真机复现时，只看这一行就能分清是「表不在」还是「文件判未备」。
            logBufDiag(
                key = "$fromSeg|$localN|$crossN|$crossStop|$diagCurIdx|${preloadChapterCache.keys.sorted()}",
                line = "缓冲上报诊断: from=$fromSeg 章=$chapterTitle 本章=$localN 跨章=$crossN " +
                    "合计=$totalN 本章明细=文件$localFileN/记录${recordedLocal?.size ?: 0}" +
                    " curIdx=$diagCurIdx limit=$diagLimit " +
                    "跨章明细=[$crossDetail] 中断=$crossStop " +
                    "已建表章=${preloadChapterCache.keys.sorted()} contentList章=$contentListChapterIndex",
            )
            // 11-22 14:4x：写回短时缓存（同一段 500ms 内不重复全扫）
            bufferedCountCache = BufferedCountCache(
                key = cacheKey,
                at = SystemClock.elapsedRealtime(),
                value = totalN,
            )
            totalN
        }.getOrElse { -1 }
    }

    /**
     * 11-22 21:4x：缓冲上报诊断（**临时**，定位完连同调用点一起删）。
     *
     * ## 为什么加它
     * 真机 20:46:35 截图：暂停在第3章 127/134 时，吸顶写「第4章 0/0 缓冲 5 段·跨章」。
     * 5 段正好等于「本章 128~132 已备」，而当时第4章已备 15 段（0~14）一段也没进数字
     * ——说明这一笔 `跨章` 那截算出来就是 0，且此后每笔上报都是 5（否则数字会涨到 19）。
     *
     * 静态逐环核过：`limit=2` 传对了、段表确实建了（第4章 108 段）、写入与计数用同一套
     * 文件名算法（`getCacheFileForText` → `TtsCacheFileName.sysTts`，title 都用
     * `preloaded.title`）、本章能数到 5 也证明 index/voice 口径一致、伴生对象静态表
     * 不会被暂停清空（唯一删除点只清 validRange 外的章）、TTL 只有 500ms 解释不了 52 秒
     * 不动。**静态排不出**，只剩两种可能：
     *   ① 那一笔 `preloadCacheStore[curIdx+1]` 取不到表（键/生命周期问题）；
     *   ② 表取到了，但跨章文件被判「未备」（title 或 voice 与写入侧不一致）。
     * 这一行日志把两类原因都带出来，一次复现即可定性。
     *
     * ## 节流
     * 内容（from/本章/跨章/中断原因/表键）没变时每 [BUF_DIAG_HEARTBEAT_MS] 才落一行；
     * 变了立刻落。这样既不会淹掉 appLog，又能看到「卡住不动」的现场。
     */
    /**
     * 11-22 21:5x：合成完成后把缓冲段数刷新到 readProgress.json（见 onDone 调用点）。
     *
     * 为什么不在回调线程直接算：`countBufferedAhead` 最多 400+ 次 stat，回调来自
     * 引擎的 binder 线程，压这里会拖慢下一笔提交。丢到 IO 线程，失败静默
     *（写盘失败只影响进度显示，不影响朗读）。
     */
    private fun refreshBufferedAhead() {
        if (textToSpeech == null) return
        synthScope.launch(Dispatchers.IO) {
            runCatching {
                val n = countBufferedAhead(
                    nowSpeak,
                    textChapter?.title ?: textChapter?.chapter?.title ?: "",
                )
                io.legado.app.help.ReadProgressFile.reportBufferedAhead(n)
            }
        }
    }

    private fun logBufDiag(key: String, line: String) {
        val now = SystemClock.elapsedRealtime()
        if (key == lastBufDiagKey && now - lastBufDiagAt < BUF_DIAG_HEARTBEAT_MS) return
        lastBufDiagKey = key
        lastBufDiagAt = now
        AppLog.put(line)
    }

    private suspend fun synthesizeText(
        text: String,
        utteranceId: String,
        index: Int = -1,
        chapterTitle: String = "",
        rawText: String = text,
        // 11-14 合成前沿上报用：该笔所属章的 index 与总段数。null=调用方没报，
        // 上报时退回 ReadBook.durChapterIndex/contentList.size（主播放路径恒正确；
        // 跨章预加载路径必须显式传自己的章序与段数，否则前沿章/段全是播放章的值）。
        synthChapterIdx: Int? = null,
        synthTotalSegs: Int? = null,
        // 11-20：本笔是「后台预加载」，标记只用于让 UTTS **别破窗**、别占播放槽位。
        // 其余一律同正常播放：真实音频、落 U阅 本地缓存、日志页照常。
        preloadOnly: Boolean = false,
    ): File? {
        val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
        if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
            // B 方案 + 判定以文字为准（11-16 用户定稿）：**段里有文字**（汉字/英文字母/
            // 数字等可读内容，即不匹配 notReadAloudRegex）时，静音/超短音频才可能是
            // 「合成失败」留下的垃圾缓存——空文件/半截/全零静音，每次命中都播静音。
            // 此时深检（复用 AudioCacheValidator：阈值=「超短音频判定阈值」设置，0=关闭），
            // 判坏删除，落到下面重新合成。
            // 纯标点段读出来就是静音，是合法产物，**永不判坏**——文字为准，不看文件。
            // （AudioCacheValidator 里另有 844B 静音占位豁免：那是给看不到文字的
            // HTTP 侧兜底用的；本入口拿得到 text，文字判据更硬，两处口径见各自注释。）
            // 深检只在命中瞬间做：合成新音频的验收仍是宽松口径，不会循环打回。
            if (!text.matches(AppPattern.notReadAloudRegex) &&
                AudioCacheValidator.isBadCache(cacheFile)
            ) {
                AppLog.put("TTS缓存无效（有文字但空/超短/全静音），删除重新合成: ${cacheFile.name}")
                cacheFile.delete()
            } else {
                return cacheFile
            }
        }
        val cacheKey = cacheFile.absolutePath

        // ① 已有在途登记：本次不提交第二笔，只等那一笔的结果。
        // 用 NonCancellable 包住**等待**（只包等待，不包合成）：翻页时老任务被 cancel
        // 不该把「这一笔的结果」丢掉；而真正的合成仍归提交它的那个调用方管，可被 cancel。
        inFlightSyntheses[cacheKey]?.let { pending ->
            return withContext(NonCancellable) { pending.await() }
        }

        // ①-b 11-14（方案 A）：刚被取消过的那一笔不立刻重提。
        // 连点跳段时上一笔往往**已经合成完**、只差交付就被 stop() 掐掉，若直接重提
        // 等于把成品扔掉再花 2~3 秒重来（真机：i17 一次连点被提交 6 次）。
        // 这里先等一小会儿（给上一笔留出落盘时间），再复查缓存：
        //   · 命中 → 直接复用，省掉整次请求；
        //   · 没命中 → 清掉记录、正常走下面的提交（不能无限等，否则真失败就卡死）。
        // 只用时间判据，不赌内容；最坏情况退化成现状（多等最多 0.6 秒），不会更糟。
        canceledAt[cacheKey]?.let { at ->
            val waited = System.currentTimeMillis() - at
            if (waited < canceledCooldownMs) {
                delay((canceledCooldownMs - waited).coerceAtMost(canceledCooldownMs / 2))
                val recheck = getCacheFileForText(rawText, index, chapterTitle)
                if (recheck.exists() && recheck.length() > 0 && isValidAudioFile(recheck)) {
                    AppLog.putDebug("TTS冷却期内缓存已就绪，直接复用: ${recheck.name}")
                    canceledAt.remove(cacheKey)
                    return recheck
                }
                // 缓存仍没出来（或窗口已过）→ 放行，交给下面的正常提交
                canceledAt.remove(cacheKey)
            } else {
                canceledAt.remove(cacheKey)
            }
        }
        // ② 自己成为这一笔的持有者
        val mine = CompletableDeferred<File?>()
        val winner = inFlightSyntheses.putIfAbsent(cacheKey, mine)
        if (winner != null) {
            // 输给并发调用（与 ① 之间只隔一瞬）：改为等对方那笔
            return withContext(NonCancellable) { winner.await() }
        }
        // 【诊断日志】真正提交引擎的一笔（10-12 加，只记录不改逻辑）
        AppLog.putDebug("TTS提交引擎 index=$index id=$utteranceId text=${text.take(20)}")
        // 11-18：给预热让路判断记下这笔播放请求的提交时刻
        playbackSubmitAt = SystemClock.elapsedRealtime()
        // 11-14：合成前沿上报（UTTS 进度行「多合几段/正在合第几段」的数据源）。
        // 每提交一笔就刷新前沿；幂等由 ReadProgressFile 内部处理。失败静默。
        io.legado.app.help.ReadProgressFile.reportSynthFront(
            synthChapterIdx ?: ReadBook.durChapterIndex,
            index,
            chapterTitle,
            synthTotalSegs ?: speakTotalSegs,
            // ===== 11-22 15:0x（用户指出「双重动态值，基准要统一」）=====
            //
            // 缓冲段数必须锚在**正在播放的段**，不能锚在这一笔合成的前沿。
            //
            // ## 现场问题（用户追问「跳段/翻页/跨章这些计算都没问题吗」时揪出）
            // 旧写法传的是 `index`——那是**本笔正在合成的段**，而合成循环
            // （`for (i in nowSpeak until endIndex)`）**跑在播放前面**，
            // 于是数字的基准是「合成到哪」，不是「听到哪」。
            // 而 UTTS 渲染端（TtsLogScreen 约 2569 行）是这样画的：
            //     val curSegIdx = playDone - 1              // 播放段（playDone 来自 snap.segIndex）
            //     val bufEndAbs = curSegIdx + bufferedAhead  // 加到播放段上
            // **两个坐标混用**：数字从合成前沿之后开始数，却加到播放位置上画。
            // 合成领先播放多少段，浅绿就少算多少段（领先 20 段 ⇒ 少算 20 段）。
            //
            // ## 为什么必须统一到播放位置
            // 这个数字要回答的是「从**我正在听的地方**往后，还能连续听多久」。
            // 用户明确：「缓存段数都是以正在播放开始计算的，还是个双重动态值」——
            // 播放位置在动、已备集合也在动，两个动态量的坐标必须是同一个，
            // 否则怎么算都有偏差。播放位置是唯一的「用户此刻在哪」的真相。
            //
            // ## 取哪个位置
            // 用 `nowSpeak`（BaseReadAloudService 的当前播放段，0-based），
            // 与 UTTS 侧 `snap.segIndex` 同源（U阅 report() 上报的就是它）。
            // 章节标题也必须跟着用**播放章**的标题——旧代码传的 `chapterTitle` 是
            // 「本笔所属章」，跨章预加载笔时是下一章，与播放位置不同章会让缓存键
            // 哈希不匹配 ⇒ 全部判成没备好。两处必须同改，缺一不可。
            countBufferedAhead(
                nowSpeak,
                textChapter?.title ?: textChapter?.chapter?.title ?: chapterTitle,
            ),
        )
        // 登记后跑的**局部协程**：用服务级 scope（不挂在任何调用方的 job 下），
        // 这样某一方被 cancel 不会掐断这一笔——否则 mine 永不 complete，等它的人会永久挂住。
        // 外层 NonCancellable 只为「把子协程启起来」：调用方在 launch 这一瞬被取消，
        // 也要保证这笔合成有归属、mine 一定被 complete。服务销毁时在 onDestroy 取消它。
        val job = withContext(NonCancellable) {
            synthScope.launch {
                // 必须**无条件** complete：doSynthesizeText 若抛异常（而非返回 null），
                // 一旦跳过 complete，持有者与所有等待方就会永久挂在 mine.await() 上；
                // 且裸 SupervisorJob 子协程的未捕获异常会走默认 handler 直接崩应用。
                //
                // 11-13 关键修正：`runCatching` 会把 CancellationException 一并吞成 null，
                // 调用方拿到 null 就判「合成失败」并写静音占位——于是「用户点停止 /
                // 翻页 / 切章」这类正常的主动取消，会把在途那几段永久静音
                // （真机日志：stop → onDestroy → 「TTS合成异常 Job was cancelled」→ 静音占位）。
                // 取消必须原样抛出，让调用方走取消分支而不是失败分支。
                val result = try {
                    doSynthesizeText(
                        text, utteranceId, index, chapterTitle, rawText, preloadOnly
                    )
                } catch (e: CancellationException) {
                    // 11-14（方案 A）：取消不立即销账——留一个冷却记录，让紧接着的
                    // 同 key 调用方先复查缓存再决定，避免「掐掉→重提→再掐」空转。
                    // 只登记时间，不保留 CompletableDeferred（那个已完成，留着会误导等待方）。
                    canceledAt[cacheKey] = System.currentTimeMillis()
                    inFlightSyntheses.remove(cacheKey)
                    mine.completeExceptionally(e)
                    // 11-18：取消也算收尾，别让预热把「已取消的慢请求」当成还在卡
                    playbackSettledAt = SystemClock.elapsedRealtime()
                    throw e
                } catch (e: Exception) {
                    AppLog.put("TTS合成异常\n${e.localizedMessage}", e)
                    null
                }
                inFlightSyntheses.remove(cacheKey)
                // 正常出结果（成功或失败）就清掉冷却，免得 1.2 秒内重播被误挡
                canceledAt.remove(cacheKey)
                mine.complete(result)
                // 11-18：播放请求已收尾，预热让路判断清零
                playbackSettledAt = SystemClock.elapsedRealtime()
            }
        }
        // 持有者（即提交这一笔的人）可被取消，发给引擎的那笔请求照旧跑完并落缓存
        return try {
            job.join()
            mine.await()
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * 通用 TTS 合成方法。
     * 先写入 .tmp 临时文件，合成成功且验证为有效音频后，再重命名为正式缓存文件。
     * 若验证失败，会自动重试最多2次。
     */
    private suspend fun doSynthesizeText(
        text: String,
        utteranceId: String,
        index: Int = -1,
        chapterTitle: String = "",
        rawText: String = text,
        preloadOnly: Boolean = false
    ): File? {
        val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
        if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
            return cacheFile
        }

        // 11-13：进入时的主动中断代次（见 abortGeneration 注释）。
        // 之后任何返回点只要发现代次已变，一律抛 CancellationException——
        // 这次失败是我们自己 stop() 掐的，不重试、不写占位、不重建引擎。
        val genAtEntry = abortGeneration

        suspend fun ensureNotAborted() {
            if (abortGeneration != genAtEntry) {
                throw CancellationException("synthesis aborted by play() reset: $utteranceId")
            }
        }

        suspend fun tryOnce(attempt: Int): File? {
            val tts = textToSpeech ?: return null
            val currentUtteranceId = if (attempt == 0) utteranceId else "${utteranceId}_retry$attempt"
            val tempFile = File(ttsFolderPath, "${cacheFile.name}.${currentUtteranceId}.tmp")

            if (cacheFile.exists() && !isValidAudioFile(cacheFile)) {
                cacheFile.delete()
            }
            // 11-14 修「跳段白花 API」（用户实测：跳段 1:1 掐在途合成，UTTS 侧
            // 同一句请求两次、第一次「已取消」）：
            // 被 stop() 掐掉的那笔，音频常常**已经写完**，只是没走到下面的
            // renameTo 就被取消。旧写法在这里无条件删除所有 .tmp，
            // 等于把刚合成好的音频扔掉再花 2~3 秒重新请求一遍。
            // 真机实证（01:08:32.994 请求 → 33.7s 已成 → 34.111「已取消」→
            // 45.444 重新请求同一句）：那次「已取消」的体积已接近正常音频。
            // 改法：删之前先认领——同 cacheFile 名下若有**有效音频**的 .tmp，
            // 直接收编成正式缓存，省掉一次完整 API 请求；
            // 无效/半截的照旧删掉重来（不赌，只认 isValidAudioFile 的判定）。
            // 收编用**严格**校验（isVerifiedAudioFile）：半截 .tmp 若被宽松放过，
            // 会变成"看起来有效的正式缓存"，之后每次命中都让 ExoPlayer 报
            // UnrecognizedInputFormatException（真机 11-14 20:55:20 实证）。
            val reusableTmp = FileUtils.listDirsAndFiles(ttsFolderPath)?.firstOrNull {
                it.isFile && it.name.startsWith(cacheFile.name) && it.name.endsWith(".tmp") &&
                    isVerifiedAudioFile(it)
            }
            if (reusableTmp != null && reusableTmp.renameTo(cacheFile)) {
                AppLog.putDebug("TTS收编上次中断的临时音频: ${cacheFile.name}")
                return cacheFile
            }
            FileUtils.listDirsAndFiles(ttsFolderPath)?.filter {
                it.isFile && it.name.startsWith(cacheFile.name) && it.name.endsWith(".tmp")
            }?.forEach { it.delete() }

            val completer = CompletableDeferred<File?>()
            synthesisCompleters[currentUtteranceId] = completer
            synthesisTargetFiles[currentUtteranceId] = tempFile

            val params = Bundle().apply {
                putInt("stream", AudioManager.STREAM_MUSIC)
                // 11-14：段落位置（用户令「每个请求音频日志都标 1/5 这种段落标记」）。
                // UTTS 日志页按这个给每张请求卡标 N/M；paraIndex 0-based，
                // paraTotal 口径与 readProgress.json 的 totalSegs 一致（contentList.size，
                // 含末尾章末占位段——UTTS 进度行分母就是它，两处必须同源）。
                // index<0（无段上下文的调用方）不写，UTTS 侧落盘该两段为空，渲染跳过。
                if (index >= 0) {
                    putInt("para_index", index)
                    // 11-22 21:5x：分母去掉末尾章末占位段（见 speakTotalSegs 注释），
                    // 与 readProgress 的 totalSegs 同源，否则卡片与吸顶会差 1。
                    putInt("para_total", speakTotalSegs)
                }
                // 11-21（书章随请求走）：书名/章名随请求交给 UTTS，请求级事实
                // 优先于规则 JS 自己定位——JS 定位失败「沿用上次章名」在多引擎
                // 并发下会把预加载笔串到上一章（真机 17:00:50 实锤 245 章段
                // 66/81 被标 244 章）。书名用 ReadBook.book.name，章名与缓存键
                // 同源（textChapter.title），播放/预加载两条路径都带。
                putString("book_name", ReadBook.book?.name ?: "")
                putString("chapter_title", chapterTitle)
                // 11-20：后台预加载标记（UTTS 侧据此不落日志页、不破窗、不占播放槽位，
                // 但仍走正常单段合成出真实音频）。
                if (preloadOnly) putBoolean("preload_only", true)
            }

            val result = tts.runCatching {
                synthesizeToFile(text, params, tempFile, currentUtteranceId)
            }.getOrElse {
                AppLog.put("tts合成提交失败(attempt=$attempt)\n${it.localizedMessage}", it, true)
                TextToSpeech.ERROR
            }
            // 【诊断日志】提交结果（10-12 加，只记录不改逻辑）：
            // 用来回答「超时那一刻引擎到底收到请求没有」——SUCCESS 表示已交给引擎，
            // 之后若既无 onDone/onError 又超时，就是引擎侧没回调；ERROR 则是提交就失败。
            AppLog.putDebug(
                "TTS提交结果 id=$currentUtteranceId attempt=$attempt result=" +
                    (if (result == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR($result)") +
                    " text=${text.take(20)}"
            )

            val synthesizedFile = if (result == TextToSpeech.SUCCESS) {
                try {
                    // 11-16：先等「首回调」——引擎若根本没接手，没必要陪它等满 120 秒。
                    // 判据：窗口内既无 onStart、completer 也未完成 ⇒ 引擎没反应。
                    val firstCb = withTimeoutOrNull(firstCallbackTimeoutMs) {
                        while (!completer.isCompleted && !startedCallbacks.contains(currentUtteranceId)) {
                            delay(100L)
                        }
                        true
                    }
                    if (firstCb == null && !completer.isCompleted) {
                        synthesisCompleters.remove(currentUtteranceId)
                        synthesisTargetFiles.remove(currentUtteranceId)
                        clearTTS()
                        AppLog.put(
                            "TTS首回调超时：提交 ${firstCallbackTimeoutMs / 1000} 秒内引擎无 onStart，" +
                                "已清除实例待重试: $currentUtteranceId"
                        )
                        throw SynthesisFirstCallbackTimeout()
                    }
                    withTimeout(AppConfig.sysTtsSynthesizeTimeout * 1000L) { completer.await() }
                } catch (_: SynthesisFirstCallbackTimeout) {
                    null
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // 超时：引擎可能已死，清除实例以便重试时重新初始化
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    if (tempFile.exists() && isValidAudioFile(tempFile)) {
                        tempFile
                    } else {
                        clearTTS()
                        AppLog.put(
                            "TTS合成超时，引擎无响应，已清除实例待重试: $currentUtteranceId" +
                                "（提交时 result 见上一条「TTS提交结果」；到此刻仍无 onDone/onError）"
                        )
                        null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 真正的协程取消（用户暂停/停止）
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    throw e
                } catch (_: Exception) {
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    null
                }
            } else {
                synthesisCompleters.remove(currentUtteranceId)
                synthesisTargetFiles.remove(currentUtteranceId)
                null
            }

            // 11-13：等回的是 null（-3 / 超时），先确认不是被自己 stop 掐的。
            // 引擎日志实测：一次失败常是 play() 里 stop() 引发的回调丢失，不是合成真的挂了；
            // 直接抛取消走上级分支，避免"重试→再被掐→耗尽→静音占位"的死循环。
            if (synthesizedFile == null) ensureNotAborted()

            return if (synthesizedFile != null && isValidAudioFile(synthesizedFile)) {
                if (synthesizedFile.renameTo(cacheFile)) {
                    cacheFile
                } else {
                    synthesizedFile.delete()
                    null
                }
            } else {
                synthesizedFile?.delete()
                null
            }
        }

        repeat(AppConfig.ttsRetryCount) { attempt ->
            // 11-13：代次已变 = 中途被 play() 重置过，本段放弃（不重试、不写占位）
            ensureNotAborted()
            // 如果引擎实例已丢失或未就绪，在主线程重新初始化并等待完成
            if (textToSpeech == null || !ttsInitFinish) {
                withContext(Dispatchers.Main) {
                    clearTTS()
                    initTts()
                }
                var waitCount = 0
                while (!ttsInitFinish && waitCount < 100) {
                    delay(50)
                    waitCount++
                }
            }
            val result = tryOnce(attempt)
            if (result != null) {
                // 11-17：重试后成功也要留痕（用户令「重试记录要有」）——
                // 否则日志上只看到一次成功，看不出前面失败过几轮。
                if (attempt > 0) {
                    AppLog.put("[阅读→引擎] 重试成功：第 ${attempt + 1} 次尝试拿到音频（第 ${index + 1} 段，前 ${attempt} 次失败）")
                }
                return result
            }
            // 11-17：每次失败都留一行（用户令「日志上要有重试记录，不然搞不清楚为啥停顿」）。
            // 这是**段级**日志（非 debug），会进日志页——用户就是靠它看懂「卡在哪一轮」。
            AppLog.put(
                "[阅读→引擎] 合成失败：第 ${attempt + 1}/${AppConfig.ttsRetryCount} 次尝试（第 ${index + 1} 段，文本：${text.take(20)}）"
            )

            // 合成失败（非最后一次重试）：清除实例，让下一次重试时强制重建
            if (attempt < AppConfig.ttsRetryCount - 1) {
                clearTTS()
                delay(500)
            }
        }
        return null
    }

    private fun getRawText(index: Int): String {
        var text = contentList.getOrNull(index) ?: return ""
        if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
            text = text.substring(paragraphStartPos)
        }
        return text
    }

    /**
     * @param precomputedText 调用方已跑过规则脚本的结果（省一次 Rhino 执行）。
     *   传 null 则本函数自己跑。11-14 晚：合成主循环改为「命中缓存不跑脚本」后，
     *   未命中的段由调用方算好再传进来，避免同一段脚本跑两遍。
     */
    /**
     * 后台批量预热（11-15，默认关闭；任务书-U阅批量提交多段 · 路 C）。
     *
     * 把 `start` 起若干**未命中本地缓存**的段打包成一条 `batch_json` 请求交给 UTTS：
     * UTTS 内部按段并行合成，结果写进**它自己的文本缓存**，回给我们的是一段极短静音
     * （协议约定，见 UTTS 侧 preloadSegments 注释）。这里不等结果、不落盘、不入播放列表。
     *
     * 为什么不直接把返回音频当缓存用：系统 TTS 一次只产出一个文件，N 段音频拼成的
     * 连续流没有段边界，没法按段切分落盘。真正的收益在后续：逐段请求到达 UTTS 时
     * 命中它的缓存，本来 3~8 秒的合成变成读文件。
     *
     * 失败一律静默（只记 debug 日志）：预热是优化，坏了也不能影响正常朗读。
     */
    /**
     * 11-16 用户令（终版口径）：「希望后台缓存一部分让速度快一点，但不要因为失败卡顿，
     * 也不要请求段数太多导致卡顿——它是辅助提速，不是拖后腿」。
     *
     * 因此从「一批 4 段并发」改为**逐段串行、成功再下一段**：
     *   · 一次只发 1 个请求 ⇒ 永远撞不上「同插件串行门」（4 并发撞门是 06:40
     *     那 6 分钟假死的唯一根因）；
     *   · 每一段都等它真的完成再发下一段 ⇒ 后台瞬时压力恒为 1，
     *     不会因为请求堆积把播放请求挤在后头；
     *   · 某段失败**跳过继续**（不是停摆）：预热是纯优化，一段没备上，
     *     播放到该段时现场合成即可，绝不能因为它让整条预热链死掉。
     *
     * 返回速度：单段请求本身是毫秒级提交，UTTS 侧串行 1 路合成，
     * 真正占时间的是云端合成（约 1.3 秒/段），与本函数是否分多段无关。
     */
    private fun launchPreloadBatch(start: Int, end: Int, chapterTitle: String) {
        if (singleParagraphMode) return
        val tts = textToSpeech ?: return
        if (!ttsInitFinish) return
        // 已有预热链在跑就不再叠一条：两条串行链会变成事实上的并发 2 路，
        // 又回到「抢引擎」的老路（用户明确不要这种）。
        if (preloadChainJob?.isActive == true) return

        preloadChainJob = lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // 11-23：原「用户可调 span（默认5）」设置已随旧滚动链退役，本回退参考
                // 链固定按 5 段一轮（与退役前默认行为一致）。
                val limit = minOf(start + 5, end)
                var okCount = 0
                for (i in start until limit) {
                    if (!isActive) break
                    ensureActive()
                    // 11-18：播放请求在飞且已慢 → 上游卡住，系统 TTS 队列多半被它占着，
                    // 这时发预热只会「排队 15 秒 × 每段」空转（实测 5 段 76 秒全废）。
                    // 本轮直接放弃，等下一轮滚动补货再试。
                    val playbackPending = playbackSubmitAt > playbackSettledAt
                    val playbackStuckMs = SystemClock.elapsedRealtime() - playbackSubmitAt
                    if (playbackPending && playbackStuckMs >= PRELOAD_YIELD_PLAYBACK_STUCK_MS) {
                        AppLog.putDebug(
                            "预热让路：播放请求已等 ${playbackStuckMs / 1000} 秒未返回，" +
                                "本轮预热放弃（起点 $start，已备 $okCount 段）"
                        )
                        break
                    }
                    val raw = getRawText(i)
                    if (raw.isBlank()) continue
                    // 已有 U阅 自己的缓存，不必再花一次请求
                    val cached = getCacheFileForText(raw, i, chapterTitle)
                    val hit = cached.exists() && cached.length() > 0 && isValidAudioFile(cached)
                    if (hit) continue

                    if (preloadOneSegment(i, raw, tts)) okCount++ else
                        AppLog.putDebug("预热第 ${i + 1} 段失败，跳过继续")
                }
                AppLog.putDebug("预热链结束: 备好 $okCount 段（起点 $start）")
            }.onFailure {
                if (it is CancellationException) throw it
                AppLog.putDebug("预热链异常: ${it.localizedMessage}")
            }
        }
    }

    /**
     * 预热单段：提交一段 preload_only（单段预加载），阻塞等它的完成回调。
     *
     * 为什么必须等回调而不是只看返回码：Android 的 `synthesizeToFile` 是**异步**的，
     * 返回 SUCCESS 只代表「已交给引擎」，真正的结果在 `onDone/onError` 里。
     * 「成功再下一段」的语义要求拿到真结果，否则又会变成事实上的并发提交。
     *
     * 超时取 [PRELOAD_SEGMENT_TIMEOUT_MS]（比播放侧短得多）：预热不值得等 2 分钟引擎超时。
     *
     * @return true=该段已备进 UTTS 缓存
     */
    private suspend fun preloadOneSegment(index: Int, rawText: String, tts: TextToSpeech): Boolean =
        preloadGate.withLock {
            preloadOneSegmentLocked(index, rawText, tts)
        }

    private suspend fun preloadOneSegmentLocked(
        index: Int,
        rawText: String,
        tts: TextToSpeech,
        // 本章总段数：随 preload_only 传 para_total，UTTS 逐段卡标「N/M」。
        // -1 = 调用方没带（滚动链旧调用），UTTS 侧不标段号，行为与旧版一致。
        segmentTotal: Int = -1,
        // 11-21（书章随请求走）：本笔所属章名，随 Bundle 交 UTTS——请求级事实
        // 优先于规则 JS 定位，防预加载笔串章（真机 245 章段被标 244 章实锤）。
        // 书名取 ReadBook.book.name（预加载只在朗读中发生，book 必非空）。
        chapterTitle: String = ""
    ): Boolean {
        // ===== 11-22 03:0x（真机日志实锤）：预加载**也要跳过纯符号段** =====
        //
        // 现场（01:46:01）：
        //   01:46:01.539 预加载 第1章第87段 提交preload_only（文本=　　...... ）
        //   01:46:01.972 UTTS 拿到 61B 回应 → 01:46:01.976「预合成失败（第 87 段）：服务端错误」
        //   01:46:03.316 重试又一次 61B → 01:46:03.319 再失败
        //   01:46:04.980 第三次才成功（此时切成备用发音人）
        //
        // 而播放路径早就跳过这类段：`synthesizeSingle` / 滚动补货链都在送引擎前判
        // `text.matches(AppPattern.notReadAloudRegex)` → 打「纯符号段静音跳过」直接返回。
        // **预加载路径漏了这道闸**，于是「......」「——」这类段被真的送去合成：
        // 引擎对纯符号返回 61B 的错误体（插件侧 5xx 归因），白烧 2~4 秒 + 两次重试，
        // 日志里还留两条吓人的「服务端错误」。
        //
        // 判据与播放路径**完全同一函数**（AppPattern.notReadAloudRegex），保证两条路径
        // 对「这段该不该念」的判断永远一致——这也是用户 11-22 反复强调的
        //「两处口径必须统一，不能各拍一个数」。
        // 用 rawText（不是 applyTtsScripts 之后的）与播放侧 synthesizeSingle 同源。
        if (rawText.matches(AppPattern.notReadAloudRegex)) {
            AppLog.putDebug(
                "跨章预加载诊断: index=$index 纯符号段，跳过合成（${rawText.trim().take(12)}）"
            )
            return true // 视同「本段无需备货」，与已备同等对待：不计失败、继续下一段
        }
        // 11-21 12:1x（用户拍板「逐段卡要改」）：不再打 batch_json（UTTS 批量预热通道，
        // 只回静音、emitHitLog=false+isPreload=true 双闸拦日志页），改打**单段
        // preload_only**——UTTS 11-20 定稿的口径：走正常单段合成、出真实音频落
        // U阅 缓存、日志页照常出「请求音频+获取成功（预合成）」卡（含正文全文、
        // 音色标注、段号 N/M、成功丸），失败出红行。不破窗、不占播放槽位不变。
        // 11-21 14:1x（真机 244 章段卡标 4/81 实锤）：滚动链调用不带 segmentTotal
        //（默认 -1），此前仍写 para_total=-1 → UTTS 侧 MDC 残留上一笔的 paraTotal=81
        // 落盘，卡上分母串成别章的总数。改为 **-1 就不写**：UTTS 侧 reqSnapshot 读到
        // 0 → 落盘 MDC 也不写 → 渲染层只标 N 不标 M，与播放路径 index<0 不写的口径
        // 完全一致。
        //
        // ===== 11-22 13:0x：预加载「在途去重」——治「同一段被合成两遍」=====
        //
        // ## 现场（用户实报「一段话两张卡」，真机定量）
        // 同一段文字被提交给引擎两次、出两张卡：
        //   12:18:53.619 播放笔   index=17 → 12:18:56.945 成功 127.6 KB · 3.1s
        //   12:18:57.482 预加载笔 index=18(文本=同一段) → 12:19:03.038「（预合成）」
        // 两份日志合起来看：272 次提交里 **85 次是白重复**（187 个不同文本、79 个重复，
        // 最多一段被提交 4 次）。
        //
        // ## 根因
        // 播放走的是 [synthesizeText]，它开头有三道保护：缓存命中 / `inFlightSyntheses`
        // 在途去重 / `canceledAt` 冷却复查。而预加载走的是**本函数直提引擎**
        //（`tts.synthesizeToFile`），这三道**一道都不经过**。外层 `runChapterPreloadRound`
        // 虽然有一道「已有缓存就跳过」，但它只判 `file.exists()`——
        // 播放那笔当时**还在请求中**（53.6→56.9 才落盘），文件尚不存在 ⇒ 预加载判定
        //「没备过」⇒ 又提交一遍。于是只要预加载扫描位置追到正在播放的段附近，
        // 就必然成对重复。
        //
        // ## 修法
        // 提交前先查 `inFlightSyntheses`（键与 [synthesizeText] 完全同源的缓存绝对路径）：
        // 同一段正有别人在合成 ⇒ 本笔直接跳过（返回 true = 视同已备，不计失败、
        // 继续下一段）。等那一笔落盘后，下一轮预加载的 `file.exists()` 判据自然命中，
        // 不会漏备；真失败了也会在下一轮重来，语义不变。
        //
        // 为什么不用「等那一笔」（像 synthesizeText 那样 await）：预加载是后台备货，
        // 等只会把备货节奏拖到跟播放同步，反而失去提前量；跳过更快、且下一轮必复用。
        val finalCacheForDedup = getCacheFileForText(rawText, index, chapterTitle)
        if (inFlightSyntheses.containsKey(finalCacheForDedup.absolutePath)) {
            AppLog.putDebug(
                "预热跳过：该段正在合成中（避免重复提交）index=$index text=${rawText.take(12)}"
            )
            return true
        }
        val params = Bundle().apply {
            putBoolean("preload_only", true)
            putInt("para_index", index)
            if (segmentTotal > 0) putInt("para_total", segmentTotal)
            // 11-21（书章随请求走）：预加载笔也带书章，与播放路径同一口径。
            // 书名随书取、章名用调用方传入的本笔章名（preloaded.title）。
            putString("book_name", ReadBook.book?.name ?: "")
            if (chapterTitle.isNotBlank()) putString("chapter_title", chapterTitle)
        }
        // ===== 11-22 17:2x：tmp 必须与正式缓存**同目录**，否则收编必失败 =====
        //
        // ## 现场（用户实报「缓存到下一章了，进章却从第一段重合成」）
        // 预加载全程报「成功」（system_tts 里全是 `获取成功（预合成）`），
        // 但播放到同一段仍是 MISS —— 说明音频合出来了、**没落进 正式缓存**。
        //
        // ## 根因：跨目录 renameTo 必返回 false，而返回值没人看
        //   · 正式缓存目录 = ttsFolderPath = `externalCacheDir/systemTTS/`
        //   · 旧 tmp 目录   = cacheDir = `cache/`（**父目录，不是 systemTTS**）
        // `File.renameTo` 只能保证**同一文件系统内**成功；Android 上
        // `/data/data/<pkg>/cache/` 与 `/storage/emulated/0/Android/data/<pkg>/cache/`
        // 通常就不是一个卷（externalCacheDir 指向后者），跨卷 rename 直接 false。
        // 于是 `runCatching { delivered.renameTo(finalCache) }` 吞掉 false（renameTo
        // 失败**不抛异常**，只返回 false）→ 一行日志都没有 → finally 里 tmp.delete()
        // 把合好的音频删了。下一轮探测 `finalCache.exists()` 仍 false ⇒ 再备一遍。
        // 这就是真机 1060 笔预加载里 35% 是重复（同章同段同文本备 2~3 遍）的成因，
        // 也是「进章全 MISS」的成因——两件事同一个根。
        //
        // 对照：播放路径的 tmp 写在 `File(ttsFolderPath, ...)`（同目录），
        // 所以播放那侧一直正常，只有预加载这条支线中招。
        //
        // 修法：tmp 落到 ttsFolderPath（同目录、同卷），rename 退化为纯改名。
        // 文件名加 preload_ 前缀 + 时间戳，与 .tmp 后缀区分，不混入播放侧收编扫描。
        val tmp = File(ttsFolderPath, "preload_${System.currentTimeMillis()}.wav")
        val uid = "PRELOAD_${System.currentTimeMillis()}"
        // 借用主合成链的完成回调机制：onDone 会 complete(目标文件)、onError 会 complete(null)。
        // ⚠️ 类型必须与 synthesisCompleters 声明的 CompletableDeferred<File?> 一致（CI 实锤过一次）。
        val completer = CompletableDeferred<File?>()
        synthesisCompleters[uid] = completer
        synthesisTargetFiles[uid] = tmp
        // 11-21 20:5x（用户报「19:50 已成功合成，20:28 点播放又走一遍请求」实锤）：
        // 音频落点收编。此前 tmp 在 finally 里无条件 delete——预加载「备好了」只备在
        // UTTS 的 synth_cache，U阅 本地缓存永远是空的；播放到该段查本地缓存必 miss，
        // 只好再发一遍请求靠 UTTS 缓存兜住（日志成对刷 PRE+「预合成」，且多一程 IPC）。
        // 现在成功回调后把 tmp **改名**到正式缓存文件名（与 getCacheFileForText 同键），
        // 播放直接命中本地缓存秒播，UTTS 请求整趟省掉。
        //
        // 11-22 13:0x：沿用上面在途去重算出的同一个 File（同一函数同一参数，结果必然相同），
        // 不再重复算一次哈希。
        val finalCache = finalCacheForDedup
        return try {
            val ret = tts.synthesizeToFile(rawText, params, tmp, uid)
            if (ret != TextToSpeech.SUCCESS) {
                synthesisCompleters.remove(uid)
                false
            } else {
                // 判据只看回调本身：onDone 回文件即算引擎走完，onError/超时回 null 都算没备上。
                // ⚠️ 不能用「临时文件存在」兜底：预热路径上 UTTS 只回 50ms 静音，
                // 临时文件必然存在，拿它判成功等于恒真（假成功会让下一段提前发，
                // 又回到事实上的并发提交）。
                val delivered = withTimeoutOrNull(PRELOAD_SEGMENT_TIMEOUT_MS) { completer.await() }
                if (delivered != null && delivered.exists() && delivered.length() > 0) {
                    // 收编：正式缓存已存在（播放刚写过/上次收编过）就不覆盖；
                    // 有效音频校验沿用宽松口径（isValidAudioFile），失败不阻塞本轮成功。
                    //
                    // 11-22 17:3x：**renameTo 失败必须可见、且要降级复制**。
                    // 旧写法只 runCatching，而 renameTo 失败是返回 false、不抛异常——
                    // 于是「合成功了但没落进正式缓存」这件事在日志里一个字都没有
                    //（真机就是这样跑了两个多小时，1060 笔预加载里 35% 白重复）。
                    // 现在：① 记返回值；② 失败退回 byte 复制（tmp 已在同目录，这一步
                    // 只为万一同卷假设仍不成立时兜底）；③ 仍失败就打 WARN 级日志，
                    // 让「预加载假成功」下次能直接从日志定位。
                    if (!finalCache.exists() && isValidAudioFile(delivered)) {
                        val renamed = runCatching { delivered.renameTo(finalCache) }
                            .getOrDefault(false)
                        if (!renamed) {
                            val copied = runCatching {
                                delivered.copyTo(finalCache, overwrite = true).length() > 0
                            }.getOrDefault(false)
                            if (!copied) {
                                AppLog.put(
                                    "预热音频收编失败：" +
                                        "rename=false copy=false（tmp=${delivered.absolutePath}，" +
                                        "final=${finalCache.absolutePath}）"
                                )
                            } else {
                                AppLog.putDebug("预热音频收编：rename 失败，已复制落地")
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: CancellationException) {
            synthesisCompleters.remove(uid)
            throw e
        } catch (e: Exception) {
            AppLog.putDebug("预热段提交异常: ${e.localizedMessage}")
            false
        } finally {
            synthesisCompleters.remove(uid)
            synthesisTargetFiles.remove(uid)
            // 11-21 20:5x：收编成功时 tmp 已改名走人（delete 无害）；失败/超时/被打断
            // 时照旧清理半截文件，不留垃圾。
            runCatching { tmp.delete() }
            // 每次只有 1 段，等它落定再返回 —— 这就是「串行」的物理保证
        }
    }

    private suspend fun synthesizeSingle(index: Int, precomputedText: String? = null): File? {
        val rawText = getRawText(index)
        val text = precomputedText ?: applyTtsScripts(rawText, paragraphIndex = index)
        if (text.matches(AppPattern.notReadAloudRegex)) {
            // 11-18 用户令（02:25 排查实证）：纯符号段走静音文件，此前**零日志**——
            // 用户只听到空一下、日志页 0 行，完全查不出「为什么这段没声」
            //（29 章实测：47/53~55/70~85/89 等 20+ 段全静默消失）。
            // 补一行：段号 + 原文缩略，明确指出「静音跳过」而非合成失败。
            AppLog.put("纯符号段静音跳过: index=$index text=${rawText.trim().take(12)}")
            val fileName = getFileNameHelper(textChapter?.chapter?.title ?: "", rawText, index)
            if (!hasSpeakFile(fileName)) createSilentSound(fileName)
            return getSpeakFileAsMd5(fileName)
        }
        val utteranceId = "${AppConst.APP_TAG}$index"
        val chapterTitle = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        // 11-18 六调：整段预算从**进入本段**就起算（快重试也计入），这样「设 60 秒」
        // 就是真的 60 秒见分晓，而不是「快重试 3 次 + 慢重试再 60 秒」。
        val segmentDeadline = SystemClock.elapsedRealtime() + slowRetryBudgetMs
        // 11-17 用户红线「绝对不能接受跳句」+ 用户二修「但也不能一直等一个合不出来的段」：
        // 重试耗尽后不再**立刻**写静音占位，改「原地慢速循环重合成」，但**有总时长上限**。
        //
        // 链路（三档，全部沿用朗读设置里已有的键，不新增）：
        //   ① 快重试：synthesizeText 内部按「TTS合成失败重试次数」（用户设，默认 3）跑，
        //      每轮带首回调看门狗 + 引擎重建；
        //   ② 慢重试：①全失败后，每 10 秒再来一轮，累计到 segmentDeadline 为止
        //      （= 朗读设置里的「系统TTS合成超时」，11-18 起不再写死 60 秒）；
        //      —— 这一档覆盖「插件抖一下、几十秒后恢复」的绝大多数情况；
        //   ③ 超限 → 看用户那个开关怎么设：
        //        「失败后跳过继续朗读」= 开 → 静音占位跳过，继续下一段（旧行为）；
        //        = 关 → **暂停朗读**（与该开关键文案「关闭后将暂停朗读」一致），
        //              不写占位、不漏内容，用户手动继续后会重新合成。
        var attemptRound = 0
        // 11-18 全量核对实锤：这行原本无条件打在 while 之前，于是**每段**都先冒一条
        // 「开始慢重试」，哪怕第一次提交就成功（99 条慢重试行对 99 条提交行，98 条
        // 在 0.16 秒内 attempt=0 SUCCESS）——日志在说谎，看起来像全程在重试。
        // 现在挪到**第一次合成真返回 null 之后**才打：那时确实「快重试已耗尽」。
        // 证据链仍是「开始 + 每轮 + 结局」三处，只是开始行不再预告不存在的事。
        var slowRetryAnnounced = false
        while (true) {
            val result = synthesizeText(text, utteranceId, index, chapterTitle, rawText)
            if (result != null) {
                if (attemptRound > 0) {
                    AppLog.put("[阅读→引擎] 慢重试成功：第 $attemptRound 轮后拿到音频（第 ${index + 1} 段）")
                }
                return result
            }
            if (!slowRetryAnnounced) {
                slowRetryAnnounced = true
                AppLog.put(
                    "[阅读→引擎] 开始慢重试：快重试已耗尽，" +
                        (if (AppConfig.ttsRetrySkipOnFail) "开关已开启 → 走静音占位跳过"
                        else "开关已关闭 → 每 10 秒重来一轮，总预算 ${slowRetryBudgetMs / 1000} 秒") +
                        "（第 ${index + 1} 段，文本：${text.take(20)}）"
                )
            }
            attemptRound++
            val overBudget = SystemClock.elapsedRealtime() >= segmentDeadline
            if (AppConfig.ttsRetrySkipOnFail) {
                AppLog.put("[阅读→引擎] 慢重试第 $attemptRound 轮耗尽，走静音占位跳过（第 ${index + 1} 段）")
                val fileName = silentPlaceholderName(rawText, chapterTitle, index)
                return getSpeakFileAsMd5(fileName)
            }
            if (overBudget) {
                // 关开关 + 超预算：不写占位，交上层暂停朗读
                AppLog.put(
                    "[阅读→引擎] 慢重试第 $attemptRound 轮耗尽、已等满 ${slowRetryBudgetMs / 1000} 秒，" +
                        "开关「失败后跳过继续朗读」已关闭 → 暂停朗读（第 ${index + 1} 段，文本：${text.take(20)}）"
                )
                throw SynthesisGiveUpException(index, text)
            }
            val remainMs = (segmentDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            val remainSec = remainMs / 1000
            // 11-17 用户令「按你推荐的来」：每轮都打太吵（满预算会打 6 条），
            // 只在**跨过整十秒节点**时打一条（60/50/40/30/20/10），保留进展感又不刷屏。
            if (remainSec % 10 == 9L || remainSec <= 10) {
                AppLog.put(
                    "[阅读→引擎] 慢重试第 $attemptRound 轮耗尽，${remainSec.coerceAtMost(10)} 秒后重来（剩余 ${remainSec} 秒预算，第 ${index + 1} 段）"
                )
            }
            // 11-18 六调配套：睡到预算耗尽为止，不盲目睡满 10 秒。
            // 否则用户把总超时设成 20s 时，最后一轮会「预算只剩 3 秒却先睡 10 秒」，
            // 白等 7 秒才进终局——又变成「设多少都不准」。
            delay(remainMs.coerceAtMost(10_000L))
        }
    }

    // ====== 播放调度（复制/适配转发器）======

    /** 本次服务生命周期内，开始朗读时只补清一次，避免切章 / 恢复播放反复触发 */
    private var startCleanDone = false

    @Synchronized
    override fun play(affectBgm: Boolean) {
        // 兜底上次直接杀后台（没走 onDestroy）没清成的缓存；延后几秒，让朗读先启动
        if (!startCleanDone) {
            startCleanDone = true
            Coroutine.async {
                delay(3000)
                removeCacheFile()
            }
        }
        if (!ttsInitFinish) {
            pendingPlay = true
            // ⚠️ 早退也要消费掉「刚停过」标记：否则它会泄漏到下一次 play()，
            // 让那次本该执行的 stop()+代次自增被跳过（在途合成没被掐、代次没变，
            // 旧段 -3 会被误判成新段的真失败）。见 lastActionWasStop 注释。
            lastActionWasStop = false
            return
        }
        pendingPlay = false
        pageChanged = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (!requestFocus()) {
            lastActionWasStop = false
            return
        }
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            lastActionWasStop = false
            ReadBook.readAloud()
            return
        }
        super.play(affectBgm)
        val sysTtsPkg = AppConfig.sysTtsPackageName
        if (!sysTtsPkg.isNullOrBlank()) {
            TtsEngineActivator.activateTtsEngine(this, sysTtsPkg)
        }
        // BGM移到onPlaybackStateChanged(STATE_READY)中触发，避免和朗读启动争抢资源

        synthesisTask?.cancel()
        playIndexJob?.cancel()
        // 11-20：跨章预加载改为**常驻调度**，这里只唤醒、不再 cancel。
        // 旧版在这里 cancel 整条链，而链只在「本章合成完」时启动一次 ⇒ 每次翻章都把
        // 已备进度连根拔掉，后两个目标章永远轮不到（11-20 日志实锤 20:06:00 / 20:07:08）。
        // 已提交给 TTS 引擎的那一笔仍会跑完并写 UTTS 缓存；进度按章保留，翻章后继续推。
        // 11-21 03:4x 旧口径恢复：常驻调度器保留，但只备后面整章（见 ensureChapterPreload）；
        // 翻章后旧章落出 validRange，进度自动清理，不会拿旧章空转。
        ensureChapterPreload()
        // 11-13：翻页/重新定位时同步掐掉引擎在途请求。
        // 此前只 cancel 协程，撤不掉已提交给 TTS 引擎的那笔——UTTS 的
        // onSynthesizeText 是 runBlocking 串行队列，旧段网络耗时（2~8s）期间
        // 新段只能干等（真机日志：翻页后新段 onStart 迟 4s+）。
        // textToSpeech.stop() 让引擎侧 onCancel 掐掉在途合成，新段立即上队。
        // 注意只在此处 stop：onDestroy/pauseReadAloud 各有自己的 stop 时机。
        //
        // 11-13 补：先把「中断代次」+1，让在途那笔知道这次 -3 是"我们自己掐的"，
        // 走取消而不是失败重试（否则翻页/跳段 → -3 → 重试 → 再被掐，用户越点越卡）。
        //
        // 11-13 深夜修（真机日志 23:27:34 实锤「每次跳段必然多一次重复提交」）：
        // 如果紧接着上一步是 playStop()（prevP/nextP 都是这个模式），它已经
        // abortGeneration++ 且 textToSpeech.stop() 过了，这里再停一次是**重复动作**。
        // 而 stop() 是异步的 —— 引擎收到 stop 会把队列里所有 utterance 回 -3，
        // 此时下面刚提交的新段请求可能已在队列里，于是新段刚提交就被自己掐掉、
        // 回 -3、再被重试链重新提交（日志：15 次提交只覆盖 9 个段落）。
        // 故：上一个动作是 stop 就跳过这两行（代次已加过、stop 已发过），
        // 否则（首次播放/resume 等直接调 play 的路径）照旧执行。
        val skipRedundantStop = lastActionWasStop
        lastActionWasStop = false
        if (!skipRedundantStop) {
            abortGeneration++
            textToSpeech?.runCatching { stop() }
        }
        playErrorNo = 0
        itemRetryPending = false

        val chapterTitle = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        val endIndex = if (singleParagraphMode) (nowSpeak + 1).coerceAtMost(contentList.size) else contentList.size
        // 11-16 用户令：「开头我不想要预热，我想在朗读一段时间后才有」。
        // 原此处「点朗读就把开头 4 段打包预热」已删除——真机实证它会与首段播放请求
        // 抢同一插件引擎，撞上「请等待当前请求完成」后进入 6 分钟重试链（06:40:18–06:46:07），
        // 期间日志页一行不显示，表现为「点了很久没声音」。
        // 预热改由合成循环里的**滚动补货**触发，且加播放门槛（见下方 preloadAllowedNow）。
        // v1.7.43 U阅侧（11-18 22:4x 定位）：切章/翻页重建引擎（TtsEngineActivator / initTts /
        // clearTTS）时 UTTS 合成通道被 close，已提交的在途请求从此**不再回调**（onStart/onDone/
        // onError 全无），只能各自等满首回调看门狗（默认 96s）才判死——22:09:44 段22播完到
        // 22:10:19 的 35s 静默就是这么来的。这里在进入合成循环前主动对在途 synthesisCompleters
        // 全部 complete(null)：通道若已被重建、这些请求本就收不到回调，主动了结让失败路径
        // 立刻走「失败→重试」，不再白等看门狗。安全性同 clearTTS()：只有「引擎实例要换」才走
        // play() 重建路径，正常排队（通道未断）不影响；误了结也只触发一次无害重试。
        val pendingPre = synthesisCompleters.values.toList()
        pendingPre.forEach { d -> runCatching { d.complete(null) } }
        synthesisCompleters.clear()

        synthesisTask = lifecycleScope.launch(Dispatchers.IO) {
            var needPreload = false
            // 11-19：正常开播（段表已重建）时清零「只有章名」的重播计数，
            // 否则计数会跨章累积，遇到下一章偶发未就绪就直接判死不再自愈。
            titleOnlyRetryCount = 0
            // 首段=用户正要听的那段，立即做；其余是「往后备货」，见下方
            // forwardSynthQuietMs 处的等待（**只等一次、且只在真要花钱时等**）。
            var firstIteration = true
            for (i in nowSpeak until endIndex) {
                ensureActive()

                // 11-16 用户令「开头不预热，朗读一段时间后才有」：
                // 预热的触发点从**合成循环**移到了**播放推进**（updateNextPos）。
                // 为什么必须挪：合成循环是预取、跑在播放前面，循环里 nowSpeak 不动，
                // 用「播放段号」当门槛永远不会成立；而且它在 play() 一进来就跑，
                // 正是「点朗读立刻预热」的来源——真机实证会与首段播放请求抢同一插件
                // 引擎，撞「请等待当前请求完成」后进 6 分钟重试链（06:40:18–06:46:07），
                // 期间日志页一行不显示。

                val rawText = getRawText(i)
                val isEndMarker = i == contentList.lastIndex
                // 11-14 晚（修「连点下一段顿一下才出声」，原因经日志量化）：
                // **缓存命中时直接排队播放，不再跑规则脚本、不再走静音判定**。
                //
                // 依据：规则脚本结果只服务两件事——判断这段该不该念、以及送引擎的
                // 文本。两者在「命中缓存」时都不需要：该不该念在当初合成时已判过，
                // 送引擎则根本不发生。而 applyTtsScripts 是 Rhino **同步**执行
                // （脚本里还可能触发 AI 姓名分析），在 IO 线程里每次几十~几百 ms。
                // 真机日志（44 次跳段）实测 `play→提交引擎` 中位 327ms、最大 3.2s，
                // 连点时这段空窗就是「顿一下」的来源。
                //
                // 安全性：缓存键由 getCacheFileForText(rawText, i, chapterTitle) 决定，
                // **不含规则脚本结果**，故跳过脚本不会影响命中/漏判。
                val file = if (isEndMarker) {
                    // 章末占位段**优先于缓存判定**（与改造前顺序一致）：它永远走静音文件，
                    // 不能因为磁盘上恰好有同名缓存就去播它。
                    AppLog.putDebug("章节末尾静音占位符，使用静音文件")
                    val fileName = getFileNameHelper(chapterTitle, rawText, i)
                    if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                    getSpeakFileAsMd5(fileName)
                } else {
                    val cachedFile = getCacheFileForText(rawText, i, chapterTitle)
                    val cacheHit = cachedFile.exists() &&
                        cachedFile.length() > 0 && isValidAudioFile(cachedFile)
                    if (cacheHit) {
                        // 11-18 全量核对实锤：命中行不带段号，导致「段序缺号」无法对冲——
                        // 29 章有 47/53~55/70~85/89 等 86 段在日志里既无提交行也无命中行，
                        // 只能靠文件名 hash 反推，等于查不了。补 index 后才能直接对账：
                        // 缺号数 == 命中数即可判定「不是缺失，是秒回」。
                        AppLog.putDebug("TTS缓存命中: index=$i ${cachedFile.name}")
                        cachedFile
                    } else {
                        // ===== 11-15：往后备货前等一次静默期（只等一次）=====
                        // 位置刻意放在「确认未命中缓存」之后——命中的段不花钱，
                        // 等它反而拖慢翻页时播放队列的填充（用户要求「点哪播哪要快」）。
                        // 首段（用户马上要听的）永远不等。
                        // 等待可被取消：期间有新 play() → synthesisTask cancel →
                        // delay 立即抛 CancellationException，这次备货整体作废。
                        if (!firstIteration && forwardSynthQuietMs > 0) {
                            delay(forwardSynthQuietMs)
                        }
                        // 未命中缓存才跑规则脚本：它决定「这段该不该念」和送引擎的文本
                        val speakText = applyTtsScripts(rawText, paragraphIndex = i)
                        if (speakText.matches(AppPattern.notReadAloudRegex)) {
                            // 11-18：主播放循环同一处静音分支也补日志（上面 synthesizeSingle
                            // 那处与之口径一致，两处都要，否则命中缓存的段仍无痕迹）。
                            AppLog.put("纯符号段静音跳过: index=$i text=${rawText.trim().take(12)}")
                            val fileName = getFileNameHelper(chapterTitle, rawText, i)
                            if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                            getSpeakFileAsMd5(fileName)
                        } else {
                            try {
                                // 传已算好的 speakText：上面刚跑过一次脚本，别跑第二遍
                                synthesizeSingle(i, speakText) ?: run {
                                    // 11-17 永不跳句模式下这里只在 ttsRetrySkipOnFail=true 时到达；
                                    // false 时 synthesizeSingle 内部已原地循环，永不返回 null。
                                    AppLog.put("TTS合成失败，使用静音文件占位: index=$i")
                                    // 占位走独立文件名，绝不覆盖正式缓存，下次仍会重新合成
                                    getSpeakFileAsMd5(silentPlaceholderName(rawText, chapterTitle, i))
                                }
                            } catch (e: SynthesisGiveUpException) {
                                // 11-17：「失败后跳过继续朗读」= 关，且慢重试 1 分钟预算耗尽。
                                // 按该开关的文案「关闭后将暂停朗读」执行——暂停而不是静音占位，
                                // 那段不会被写成哑文件，用户手动继续后会重新合成。
                                withContext(Dispatchers.Main) {
                                    pauseReadAloud(abandonFocus = false)
                                }
                                return@launch
                            } catch (e: Exception) {
                                // 11-13：主动停止/翻页/切章导致的协程取消**不是合成失败**，
                                // 绝不能写静音占位——此前统一走 withDefault 的静音兜底，
                                // 真机日志实测「用户点停止 → onDestroy 取消 → 写静音占位」，
                                // 那几段就永久哑了（占位文件写进正式缓存路径，重读也命中静音）。
                                // 11-17 永不跳句模式：synthesizeSingle 不再抛"超过最大重试"，
                                // 此 catch 只会接到真正的意外异常（磁盘满等）——按取消上报，
                                // 不再吞掉写占位（旧逻辑吞异常导致开关"关闭后暂停朗读"形同虚设）。
                                if (e is CancellationException) throw e
                                throw e
                            }
                        }
                    }
                }

                if (file != null && file.exists()) {
                    withContext(Dispatchers.Main) {
                        val mediaItem = MediaItem.Builder()
                            .setMediaId("$i")
                            .setUri(Uri.fromFile(file))
                            .build()
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }

                firstIteration = false

                if (!singleParagraphMode && contentList.size - i <= PRELOAD_TRIGGER_REMAIN) {
                    needPreload = true
                }
            }
            // 当前章节全部合成并加入播放列表后，唤醒跨章预加载调度器，
            // 避免与当前章节末尾争夺同一个 TTS 实例的 FIFO 队列。
            // 注意这里只是「唤醒」：调度器是常驻的，翻章不会重建它。
            if (needPreload) {
                ensureChapterPreload()
            }
        }
    }

    // 朗读播放音量增益实时生效：由 BaseReadAloudService 事件监听触发
    override fun updateVolumeGain(gain: Float) {
        gainAudioProcessor.setGain(gain)
    }

    override fun playStop(affectBgm: Boolean) {
        exoPlayer.stop()
        synthesisTask?.cancel()
        playIndexJob?.cancel()
        // 11-20：**不在这里取消跨章预加载**。nextP()/prevP() 也走 playStop()，
        // 在这里 cancel 等于「每跳一段就把预加载链掐一次」，与 play() 里那处同罪。
        // 真正的停止由 onDestroy() 统一收口（那时服务都没了，链条自然结束）。
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        // 11-13 S1/S2 实锤：nextP() 先走 playStop() 再进 play()，代次自增却只挂在
        // play() 里——stop() 掐出的 -3 回调抢先到达，合成协程查代次未变，
        // 把「自己掐的」误判成真失败 → 原段 _retry 重合成（白付一次 API），
        // 且同插件串行门让下一段排在重试后面干等（日志实测乱序+卡 15 秒）。
        // 所以代次必须先于 stop() 自增，覆盖 nextP/nextChapter/停止等一切走本函数的入口。
        abortGeneration++
        textToSpeech?.runCatching { stop() }
        // 11-13 深夜：标记「刚停过」。紧随其后的 play() 会消费它、跳过重复的
        // stop()+代次自增，避免新段刚提交就被这记 stop 打断（真机每次跳段都中招）。
        // 见 lastActionWasStop 字段注释与 play() 里的消费点。
        lastActionWasStop = true
        if (affectBgm) {
            BgmManager.pause()
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        // 预加载需要 TTS 引擎继续工作，放弃音频焦点会导致某些引擎清空合成队列，
        // 所以暂停朗读时保留焦点，避免跨章节预加载被中断。
        super.pauseReadAloud(false)
        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        exoPlayer.pause()
        BgmManager.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
            startBgm()
        }
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                    upPlayPos()
                    startSubtitleSync()
                } else {
                    play()
                }
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val rate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(rate)
        }
        play()
    }

    // ====== ExoPlayer 监听器（完全复制转发器）======

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                if (pause) return
                exoPlayer.play()
                upPlayPos()
                startSubtitleSync()
                postEvent(EventBus.READ_ALOUD_AUDIO_CACHE_REFRESH, true)
                // 朗读开始播放后再启动BGM匹配，避免和朗读启动争抢资源
                if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
                    startBgm()
                }
            }
            Player.STATE_ENDED -> {
                playIndexJob?.cancel()
                silentPlayCheckJob?.cancel()
                playErrorNo = 0
                if (singleParagraphMode) {
                    singleParagraphMode = false
                    pauseReadAloud()
                    return
                }
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                // 11-16 用户实报（06:20 卡死）：「队列播空后整段死等，只能手动停」。
                //
                // 根因：合成循环是 `for (i in nowSpeak until endIndex)`，**只跑一轮**。
                // 二次点朗读会把 nowSpeak 重置回当前落点（如 37），循环从 37 跑到 51；
                // 若这段全命中缓存、秒回入队，循环很快结束。等 51 播完触发 STATE_ENDED，
                // updateNextPos 把 nowSpeak 推到 52 —— **但 52 段从未被合成**，
                // 且已无循环在跑，队列永远空着，表现为「播完一句就静音，等很久也不动」。
                //
                // 修法：推进段号后，若还有后续段落而合成循环已结束，就**重新拉起一轮**
                // （复用 play() 这条已验证的主路径，它从当前 nowSpeak 起合成到章末）。
                // 守卫避免递归：仅在「确实还有段 + 无循环在跑」时触发，且清空标志防重入。
                if (!singleParagraphMode &&
                    nowSpeak < contentList.lastIndex &&
                    synthesisTask?.isActive != true &&
                    !resumeGuardAfterEnded
                ) {
                    resumeGuardAfterEnded = true
                    AppLog.putDebug("播放队列播空但段未合成，重新拉起合成: nowSpeak=$nowSpeak") 
                    play(affectBgm = false)
                    resumeGuardAfterEnded = false
                }
            }
            else -> {}
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }
            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
            itemRetryPending = false
        }
        if (mediaItem != null) {
            val mediaIndex = mediaItem.mediaId.toIntOrNull() ?: -1
            if (mediaIndex >= 0 && mediaIndex < contentList.size) {
                // 记录旧段落索引，用于计算跳过的字数触发 AI 生图切换
                val oldIndex = nowSpeak
                syncToParagraph(mediaIndex)
                // syncToParagraph 路径也需要触发 AI 生图检查（正常播放走此路径）
                if (mediaIndex > oldIndex) {
                    var advancedLen = 0
                    for (i in oldIndex until mediaIndex) {
                        if (i < contentList.size) advancedLen += contentList[i].length + 1
                    }
                    checkAiImageTrigger(advancedLen)
                    // ===== 11-16 补（真机实证「开关开着却一条预热日志都没有」）=====
                    //
                    // 预热钩子**挂错位置**了：`maybePreloadFromPlayback()` 此前只被
                    // `updateNextPos()` 调用，而 updateNextPos 在 TTS 服务里只有 5 个
                    // 调用点，**全是边界/异常分支**（STATE_ENDED 队列播空、mediaIndex 越界、
                    // 媒体项异常、onPlayerError 重试）。正常连播时段落推进走的本函数
                    // → syncToParagraph(mediaIndex)，**根本不经过 updateNextPos**，
                    // 于是预热永远不触发：日志里 `preloadSegments`/`预热链结束` 恒为 0，
                    // UTTS 侧 batchSize 恒为 0（114 次采样全 0）。
                    //
                    // 放在「播放真在前进」分支内（mediaIndex > oldIndex）：
                    //   · 回退/跳页不触发，符合「朗读一段时间后才有」；
                    //   · maybePreloadFromPlayback 自带两道闸（nowSpeak>=3、
                    //     每 4 段一次）+ preloadChainJob 防重入，多次调用不会叠链。
                    maybePreloadFromPlayback()
                }
            } else {
                updateNextPos()
            }
            checkSilentPlay()
            val currentFileName = mediaItem.mediaId
            if (currentFileName != null && isSilentFile(currentFileName)) {
                postEvent(EventBus.READ_ALOUD_FADE_OUT, true)
            }
        }
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("TTS播放错误", error)
        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        playErrorNo++
        // 同一段出错只重建重试一次：从实际报错位置重建当前项并续播，不丢内容
        val currentItem = exoPlayer.currentMediaItem
        if (playErrorNo < 5 && !itemRetryPending && currentItem != null) {
            val errorPosition = exoPlayer.currentPosition
            val index = exoPlayer.currentMediaItemIndex
            AppLog.putDebug("TTS朗读出错，从实际报错位置(${errorPosition}ms)重建续播当前段")
            // 11-14 晚：解析失败（UnrecognizedInputFormatException）说明**文件本身坏了**，
            // 重建同一样东西必然再报一次（真机 20:55:20.834 与 20:55:20.908 连报两次
            // 就是这个原因）。这种坏缓存要删掉，否则每次重读都命中它、每次都报错，
            // 只能靠用户手动清缓存恢复。
            // 判定：Source error + UnrecognizedInputFormatException ⇒ 删文件 + 判下一段。
            if (isCorruptAudioError(error)) {
                deleteCorruptCacheFor(currentItem)
                exoPlayer.removeMediaItem(index)
                itemRetryPending = false
                if (exoPlayer.hasNextMediaItem()) {
                    exoPlayer.seekToNextMediaItem()
                    exoPlayer.prepare()
                } else {
                    exoPlayer.clearMediaItems()
                    updateNextPos()
                }
                return
            }
            exoPlayer.removeMediaItem(index)
            exoPlayer.addMediaItem(index, currentItem)
            exoPlayer.seekTo(index, errorPosition)
            exoPlayer.play()
            itemRetryPending = true
            return
        }
        if (playErrorNo >= 5) {
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    /**
     * 播放错误是否属于「音频文件本身损坏/不可解析」。
     *
     * 这类错误重建同一个 mediaItem 没有意义（文件没变，必然再报），
     * 与「读取超时/解码抖动」那种重试一下能过去的错误要分开处理。
     */
    private fun isCorruptAudioError(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException) return true
            // 类名匹配兜底：混淆构建下类型判断可能失效（R8 keep 未覆盖时）
            if (cause.javaClass.simpleName.contains("UnrecognizedInputFormat")) return true
            cause = cause.cause
        }
        return false
    }

    /**
     * 删除播放失败的坏缓存。只删 `.wav` 正式缓存，且必须能在缓存目录里对上
     * mediaItem 的 uri——防止误删用户其它文件。
     */
    private fun deleteCorruptCacheFor(mediaItem: MediaItem) {
        val uri = mediaItem.localConfiguration?.uri ?: return
        val path = uri.path ?: return
        if (!path.startsWith(ttsFolderPath)) return
        runCatching {
            val f = File(path)
            if (f.exists()) {
                val size = f.length()
                if (f.delete()) {
                    AppLog.put("已删除损坏的音频缓存（下次重读会重新合成）: ${f.name} ${size}B")
                }
            }
            // in-flight 登记的键是 cacheFile.absolutePath（见 cacheKey），
            // 用 File.absolutePath 清才命中，直接拿 uri.path 可能差一层前缀
            inFlightSyntheses.remove(f.absolutePath)
        }.onFailure {
            AppLog.put("删除损坏音频缓存失败: ${it.localizedMessage}")
        }
    }

    // ====== 状态推进 ======

    /**
     * 11-16 用户令：「开头我不想要预热，我想在朗读一段时间后才有」。
     *
     * 预热改由**播放推进**驱动：真正播到第 [PRELOAD_START_AFTER] 段之后，
     * 且每走过 [PRELOAD_BATCH_SIZE] 段，才为再往后的一条链备一段。
     *
     * 好处（对应用户两个诉求）：
     *  1. 开头数字永远不预热 ⇒ 不与首段播放请求抢插件引擎（06:40 那次 6 分钟卡死
     *     就是「点朗读立刻预热 + 同插件串行门」撞出来的）；
     *  2. 「朗读一段时间后」即字面：用户已经在听、播放真在走，后台才开始备货。
     *
     * 触发频率与旧的合成循环口径一致（每 4 段一批），只是驱动源换了。
     */
    private fun maybePreloadFromPlayback() {
        // ===== 11-21 15:4x（用户终裁「单链线性补货」）停用 =====
        // 备货统一走跨章轮函数（runChapterPreloadRound），扫描范围已扩到
        // offset 0..limitChapter（含当前章、起点=播放位置后第一段），从正文
        // 顺序上连续推进——不再有「滚动链 nowSpeak+4 与跨章链各自为政」的
        // 错序（快进几段没备上、下一章先备了）。本函数保留代码作回退参考，
        // 入口直接 return，不产生任何提交。
        return
        // 11-21 03:4x 旧口径恢复：滚动补货**恒启用**（随 11-23 设置退役，回退参考
        // 链不再读 preloadSpan；真开关改由调用方判定）。
        // 不再被跨章预加载停用——旧版分工是「滚动补货管当前章剩余、跨章管后面
        // 整章」，两路范围不相交（跨章 offset 从 1 起），不存在两路备同一段。
        // 2852b91 的「连续流吃掉当前章」口径随旧版一并作废。
        if (singleParagraphMode) return
        if (nowSpeak < PRELOAD_START_AFTER) return
        if ((nowSpeak - PRELOAD_START_AFTER) % PRELOAD_BATCH_SIZE != 0) return
        val chapterTitle = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        val endIndex = contentList.size
        // ===== 11-16 二修（真机两轮实锤「预合成 8 段命中 1 段 → 0/3」）=====
        //
        // 一修（f035aed）只解决了「预热不触发」；这轮暴露第二个问题：
        // **备货起点是 nowSpeak —— 正在播的这一段**。预热串行备 nowSpeak..nowSpeak+2，
        // 备完第 1 段（约 1.6~5s）时播放早就越过它了：真机 17:26 时间线——
        //   17:26:23.311 播放请求段44「青年：现在的目的？」
        //   17:26:24.767 预热请求   「　　青年：现在的目的？」 ← 备的就是它！
        //   17:26:26.150 预合成完成段44                      ← 段已开播，白做
        //   17:26:26.533 播放请求段45「哲人：你的朋友…」      ← 这段从未被预热
        // 慢插件轮（17:11）同款：段14/25/30/34 播放先到 4~10 秒。两轮两个插件
        // 模式一致 ⇒ 备的是「现在时」，永远慢播放一拍。
        //
        // 修法：起点前移 [PRELOAD_BATCH_SIZE] 段（跳过正在播的和马上要播的）。
        // 预热串行 3 段 ≈5s（快插件）~14s（慢插件），播放走 4 段 ≈12s+——
        // 快插件富余 7s，慢插件虽勉强但那本来就是预热追不上的插件，命中
        // 与否先由插件速度决定，不因起点错位再额外损失。
        // 出界保护：launchPreloadBatch 内 limit=minOf(start+SPAN, end)，
        // start ≥ end 时链空转（okCount=0），无副作用。
        launchPreloadBatch(nowSpeak + PRELOAD_BATCH_SIZE, endIndex, chapterTitle)
    }

    private fun updateNextPos() {
        val advancedLen = contentList[nowSpeak].length + 1 - paragraphStartPos
        readAloudNumber += advancedLen
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            // 段落推进后作废被跨过的BGM记录（保留为导出时的候选切换点）
            onBgmParagraphAdvanced()
            // 段落推进后检查是否触发AI生图切换
            checkAiImageTrigger(advancedLen)
            maybePreloadFromPlayback()
        } else {
            // ===== 11-19（真机 13:18:36 / 13:23:15 两连）：章末判定必须确认「真读完了」 =====
            //
            // 现场：切章后 contentList 只有 [章名, " "]（正文一段都没进来），
            // 末尾 " " 是章末标记 → 第一段音频播完 STATE_ENDED → 走到这里 →
            // nextChapter()，整章一个字没念就被跳过（两次分别只用了 255ms / 600ms）。
            // 日志铁证：那两次 newReadAloud 都打出 `readAloudNumber=2→0`
            //（段表只有 2 字的章名一项），且 `排版未完成` 一次都没打
            // ——说明不是排版慢，是段表就那样被建出来了。
            //
            // 修法：段表只剩章名时不认章末，**按未就绪处理**——不跳章、不清队列，
            // 等下一轮 play（翻页/重进/自动重试都会重新构建内容）再来。
            // 宁可少念一次，也不能把整章吞掉：用户要的是「正文什么样就读成什么样」。
            if (isContentListTitleOnly()) {
                val size = contentList.size
                AppLog.putDebug(
                    "章末判定拦下：段表只有章名（contentList=$size，" +
                        "首段=${contentList.firstOrNull()?.take(12)}），不跳章，等正文就位"
                )
                // 队列已空：停止播放等重来，避免空转刷屏
                playIndexJob?.cancel()
                // 自愈：延迟重播一次。play() 会重新读 curTextChapter 重建 contentList，
                // 正文一旦就位就能正常念；有上限，防止源站真给不出正文时无限空转。
                if (titleOnlyRetryCount < TITLE_ONLY_RETRY_MAX) {
                    titleOnlyRetryCount++
                    val attempt = titleOnlyRetryCount
                    AppLog.putDebug(
                        "章末判定拦下后 ${TITLE_ONLY_RETRY_DELAY_MS}ms 重播" +
                            "（第 $attempt/$TITLE_ONLY_RETRY_MAX 次）"
                    )
                    lifecycleScope.launch(Dispatchers.Main) {
                        delay(TITLE_ONLY_RETRY_DELAY_MS)
                        play(affectBgm = false)
                    }
                } else {
                    titleOnlyRetryCount = 0
                    AppLog.put("章末判定连续 $TITLE_ONLY_RETRY_MAX 次只拿到章名，停止自动重播" +
                        "（本章内容可能源站未返回，请手动翻页或换源）")
                }
                return
            }
            // ===== 11-19 追加（真机 13:18:36 / 13:23:15 的真根因，凌驾于上面那条拦截） =====
            //
            // 上面那段只拦「段表只剩章名」。但日志显示吞章的**第一跳**根本不是那个形态：
            // 13:18:36.144 第3章播完 → nextChapter() 切到第4章并 resumeReadAloud()，
            // 而 resumeReadAloud() **只发状态、不重建 contentList**——重建要等
            // IntentAction.play → newReadAloud()。于是 36.397 那次「章节末尾静音占位符」
            // 播的是**第3章段表末尾的 " "**（此时章节已是第4章），占位符播完
            // STATE_ENDED → 又判章末 → 36.652 再跳第5章。整章被吞就是这么来的。
            //
            // 修法：切章保护窗口内走到末段，先看段表是否还属于旧章；是则**不跳章**，
            // 主动重建段表再接着念（tryRebuildAfterChapterSwitch 内部有 5s 上限，
            // 超时落回原逻辑，不会死循环）。
            if (tryRebuildAfterChapterSwitch()) {
                // 不 cancel playIndexJob：那一轮已经播完（走到末段才会来这儿），
                // 取消只会误伤 400ms 后补发的那次
                return
            }
            nextChapter()
            return
        }
    }

    override fun nextChapter() {
        val currentBook = ReadBook.book
        val currentChapter = textChapter?.chapter
        val currentChapterIndex = ReadBook.durChapterIndex

        // 11-19 21:4x 加：切章前**清空播放队列里的旧章残留**。
        //
        // 背景（真机 18:21:30 / 20:15 反复复现）：切章是「先播完末段→STATE_ENDED→
        // updateNextPos→nextChapter」，而 ExoPlayer 队列里往往还排着旧章末尾那个
        // " "（章末静音占位符）。它一旦在切章之后才被播出去，就再触发一次
        // STATE_ENDED → 又判一次章末 → 连跳两章。
        // 旧章的东西已经不该再出声，这里直接停播+清队列，让「旧章尾巴」根本播不到。
        //
        // 为什么放在 moveToNextChapter 之前：那时段表还是旧章（contentList 未重建），
        // 清掉不会伤到新章任何音频；新章的合成与入队由随后的 newReadAloud 从头来。
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()

        // 切换章节前重置AI生图状态，新章节 sceneIndex 从0开始，避免跨章 sceneIndex 错位导致缓存/保存错乱
        resetAiImageState()
        // 先切换章节，再触发朗读，避免 resumeReadAloud 时 contentList 还是旧章节内容
        ReadBook.upReadTime()
        AppLog.putDebug("${currentChapter?.title} 朗读结束跳转下一章并朗读")
        isAutoSwitchingChapter = true
        val hasNext = ReadBook.moveToNextChapter(true)

        postEvent(EventBus.READ_ALOUD_CHAPTER_CHANGED, ReadBook.curTextChapter?.title ?: "")

        if (AppConfig.autoMergeAudioOnChapterEnd && currentBook != null && currentChapter != null) {
            Coroutine.async {
                HttpTtsAudioCache.mergeChapterAudioAuto(currentBook, currentChapter, currentChapterIndex, this@TTSReadAloudService)
            }
        }

        if (hasNext) {
            // 切章时的「取消旧分析 + 按新章号重新排期」已由 ReadBook 的统一钩子完成。
            // 这里不能再取消：moveToNextChapter 内部已经同步启动新一轮分析，
            // 在此取消会把刚排出来的新任务杀掉，朗读自动换章就永远处理不了后续章节。
            //
            // 11-19：开保护窗口。resumeReadAloud() 只发状态不重建段表，从这一刻到
            // 下一次 newReadAloud 之间，末尾那个 " "（旧章的章末标记）若被播放就会
            // 触发「再跳一章」。窗口内由 updateNextPos → tryRebuildAfterChapterSwitch
            // 判定并自愈（见该函数注释与 13:18:36 日志）。
            beginChapterSwitchGuard()
            // 自动切章：下一章通常已排版好，不会走 newReadAloud / contentLoadFinish，
            // 必须在恢复合成前同步重落盘 data.json，保证朗读脚本读到的是新窗口内容
            ReadBook.saveTtsTextJson(force = true)
            // 跨章接续：上一章放到一半的歌，本章接着放后半段
            recordBgmChapterStart(ReadBook.durChapterIndex)
            resumeReadAloud()
        } else {
            stopSelf()
        }
    }

    // ====== 字级进度同步（完全复制转发器）======

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val segLength = contentList[nowSpeak].length
            if (segLength <= 0) {
                return@launch
            }
            // ===== 11-13 深夜：翻页改为「追踪字」驱动（用户令「你就做到追踪字翻页」）=====
            //
            // 旧实现的问题：只在「本段内字数 i 越过下一页起点」时翻一次页，
            // 且 i 的起点由播放进度倒推、终点是段末。一旦跨页点正好落在段尾/段交界，
            // 或播放中途重启，i 走不到那个值 —— 页就不翻，屏幕停在第 N 页而声音已进第 N+1 页。
            //
            // 现改为**每 tick 都按真实播放位置算一次『章节内绝对位置』**：
            //   absPos = readAloudNumber + (真实播放进度换算的字数)
            // 再让 followPageFor() 把 pageIndex 追平到 absPos 所属那页。
            //
            // 与参考版（墨听 syncPageForProgress）对照：
            //   翻页判据的**思路**一致（按绝对位置追平页码、往后翻用 >=）；
            //   但边界口径有一处**刻意不同**，见 followPageFor 的注释。
            // 架构差异（不能照抄的部分）：墨听走系统 TTS，能用 onRangeStart 拿到真实字偏移；
            // U阅 走 ExoPlayer 播音频文件，没有 onRangeStart，只能用播放进度换算。
            // 故这里**每次循环都重读 exoPlayer.currentPosition**，而不是用固定的 delay(sleep)
            // 累加推算——累加会随时间漂移，重读真实位置不会。
            //
            // ===== 11-14（用户令「给口令」）：高亮与翻页拆开喂 =====
            //
            // 真机症状（用户实报）：「高亮不对准，错位一两段，然后又校准恢复」。
            // 根因：本循环的 absPos 是**线性估算**（段长×播放进度比例）——声音和字数
            // 不是均匀对应（标点停顿、数字念得慢、段尾静音），估算在段内漂，漂过段
            // 边界时 upTtsProgress(absPos) 把**别的段的位置**推给了高亮，红字就画到
            // 隔壁段；下一段切换时 upPlayPos 开头那笔精确值（readAloudNumber+1，
            // 段表累加，必然对）接管，又弹回来。时序迟滞治不了「指错」。
            //
            // 修法（用户拍板「整段常亮」）：**高亮只吃精确口径**——tick 里不再调
            // upTtsProgress（TTS_PROGRESS 全仓唯一消费方就是界面高亮），段切换时
            // 开头那笔精确值照推，红字整段常亮、绝不指错段。
            // 线性估算**只留给 followPageFor 翻页**：页跟随本来就该平滑，且页翻错
            // 会被下一 tick 立即拉回，用户不盯页看红字，漂移无感。
            while (isActive) {
                val position = exoPlayer.currentPosition
                val duration = exoPlayer.duration
                if (duration <= 0) break
                // 本段内已播到第几个字（按播放进度线性换算）。
                // ⚠️ position/duration 都是 Long，整式结果是 Long——必须显式 toInt()，
                // 否则 absPos 被推断成 Long，传给 followPageFor/upTtsProgress（均收 Int）
                // 直接编译错（CI 实锤 1029:81 / 1031:31 / 1032:31）。
                val playedChars = (segLength * position / duration)
                    .toInt()
                    .coerceIn(0, segLength)
                val absPos = readAloudNumber + playedChars
                followPageFor(absPos, textChapter)
                // 段已播完就退出，让下一段自己的 upPlayPos 接手
                if (playedChars >= segLength) break
                delay(60L)
            }
        }
    }

    /**
     * 11-13 深夜：把 pageIndex / 屏幕页追平到「absPos 所在的那一页」。
     *
     * 与旧实现的区别：旧代码假设「一次最多翻一页、且只在跨页那一刻翻」；
     * 这里允许**连续翻多页**（长段跨三页也能跟上），也允许**往回翻**
     * （播放进度估算抖动时不至于卡住）。
     * 每翻一页都同步 pageIndex 并驱动界面换页，两者必须一起动——
     * 只改 pageIndex 不改界面就会出现「页没变但高亮跳了」。
     *
     * 边界口径（**与墨听不完全相同，这里是我踩过的坑**）：
     *   往后翻 `absPos >= getReadLength(pageIndex+1)`（到达页首即翻）
     *   往回翻 `absPos <  getReadLength(pageIndex)`（**严格小于**）
     *
     * ⚠️ 为什么往回翻不能用墨听的 `<=`：墨听那两处循环是分开在不同上下文里调用的
     *（onStart / onRangeStart / syncPageForProgress），从不连在一起跑。
     * 而我这里是同一个函数里先后执行两段循环，若两边都用「含等号」：
     *   absPos 正好等于页首时 → 前一段翻到下一页、后一段立刻翻回来，
     *   下一 tick 再翻过去……**来回振荡**，界面疯狂闪。
     * 实测八种边界场景验证过：改用严格小于后，absPos==页首只往前翻一次、不再回弹。
     *
     * 上限 64 页防御异常数据导致的死循环；正常一段最多跨 2~3 页。
     */
    private fun followPageFor(absPos: Int, textChapter: TextChapter) {
        var flipped = false
        var guard = 0
        while (pageIndex + 1 < textChapter.pageSize
            && absPos >= textChapter.getReadLength(pageIndex + 1)
            && guard++ < 64
        ) {
            pageIndex++
            ReadBook.moveToNextPage()
            flipped = true
        }
        guard = 0
        while (pageIndex > 0
            && absPos < textChapter.getReadLength(pageIndex)
            && guard++ < 64
        ) {
            pageIndex--
            ReadBook.moveToPrevPage()
            flipped = true
        }
        // 11-14 晚（用户实报「第 2 页末尾的半截段自然朗读到翻页后，后半截没有红字」）：
        // 翻页后**必须补推一次高亮**，否则翻过去那半截永远不亮。
        //
        // 为什么：高亮（TTS_PROGRESS）只吃两笔精确值——upPlayPos 开头的
        // readAloudNumber+1，和段切换。ba5df9f 把 tick 里的估算推送删掉后，
        // **段内跨页就没有任何高亮事件了**：
        //   · 覆盖了本页的旧高亮被 moveToNextPage → removePageAloudSpan() 清掉
        //     （它假设翻页后马上会重新推，见 TextPageFactory.nextPage 的注释）；
        //   · 而本段是跨页段，段还没结束 ⇒ upPlayPos 不会重进 ⇒ 不会重推；
        //   · 于是新页上一个 isReadAloud 都没有 ⇒ 整段后半截无红字。
        // 自然朗读只有「段切换」时才会补齐，所以症状恰好是「翻页那一下之后到本段
        // 结束前都不亮，下一段开始又正常」。
        //
        // 推的值必须是**当前页内**的位置——这里传 absPos（估算的本章绝对位置），
        // 它由上面两段循环的不变式保证落在当前页区间内
        //（往后翻：页起点 ≤ absPos < 下一页起点；往回翻：同理）。
        // ⚠️ 绝不能传 `readAloudNumber + 1`（段真实起点）：跨页段的段起点在**上一页**，
        // 而消费端 ReadBookActivity 收到 TTS_PROGRESS 第一句就是
        // `ReadBook.durChapterPos = chapterStart`，再用 durPageIndex 反查页——
        // 传段起点会把页面翻回上一页，触发 curPageChanged → fromPageTurn
        // → 在跨页段两侧来回翻，死循环（newReadAloud 注释里记过同一个坑）。
        // absPos 落在当前页内 ⇒ 反查页仍是本页 ⇒ 不翻页；
        // 高亮落点 = absPos − 页起点，而 upPageAloudSpan 会从落点行向回扩到段首、
        // 向后扩到段末，故本页这半截整段点亮。
        if (flipped) {
            // +1 与段开头那一笔 `upTtsProgress(readAloudNumber + 1)` 保持同一口径。
            // 但要再夹一道「别等于下一页起点」：消费端会把这个值直接写进
            // ReadBook.durChapterPos 并按它反查页，等于下一页起点就会让界面提前
            // 翻过一页（声音还没到），所以钳到下一页起点 −1。
            val nextStart = if (pageIndex + 1 < textChapter.pageSize) {
                textChapter.getReadLength(pageIndex + 1)
            } else {
                Int.MAX_VALUE
            }
            // 下限也夹一次：页表异常时 nextStart−1 可能小于本页起点，不能让 anchor
            // 掉到上一页去（否则又变成"翻回上页"）。
            val anchor = maxOf((absPos + 1).coerceAtMost(nextStart - 1), textChapter.getReadLength(pageIndex))
            upTtsProgress(anchor)
        }
    }

    // ====== 字幕同步 ======

    private fun startSubtitleSync() {
        if (subtitleSyncJob?.isActive == true) return
        subtitleSyncJob = lifecycleScope.launch {
            while (isActive) {
                if (!pause && exoPlayer.isPlaying) {
                    if (nowSpeak != lastNowSpeak) {
                        prepareSubtitles()
                        // 11-14：段落切换即上报播放进度（供 UTTS 日志页定位「正在朗读」
                        // 与算合成进度）。只在段切换时写一次，不是定时写；失败静默。
                        // segText=本段正文前 60 字，UTTS 靠它匹配日志卡片（日志无段号字段）。
                        io.legado.app.help.ReadProgressFile.report(
                            ReadBook.durChapterIndex, nowSpeak, speakTotalSegs,
                            contentList.getOrNull(nowSpeak).orEmpty(),
                            // 11-22 15:2x：段切换时同步刷新缓冲段数。
                            // 缓冲数的基准是「正在播放的段」，播放推进后基准就变了；
                            // 不在这里重算，数字会卡在上一段（甚至更早）的旧值上，
                            // 而 UTTS 拿旧数字往新播放位置画 —— 越听越偏。
                            // 与 reportSynthFront 同一函数、同一基准，两条路写同一字段不打架。
                            countBufferedAhead(
                                nowSpeak,
                                textChapter?.title ?: textChapter?.chapter?.title ?: "",
                            ),
                            // 11-23 21:5x：书名/章名与段表同源（黄条指错书的根治）。
                            // bookName 来自 readAloudBookName（与 textChapter 同一次快照，
                            // bookUrl 对不上时为空串=UTTS 不显示黄条）；chapterTitle 就是
                            // 本服务段表所属那一章的标题，不再让读方另读 ReadBook.curTextChapter。
                            readAloudBookName,
                            textChapter?.title ?: textChapter?.chapter?.title ?: "",
                        )
                        lastNowSpeak = nowSpeak
                        lastProgressHeartbeatMs = System.currentTimeMillis()
                    } else if (isPlay()) {
                        // 11-15 心跳：长段播超过 STALE_MS(15s) 时，[report] 不会再写
                        //（它只在段切换时写），读的人（UTTS 日志页）就会判定过期——
                        // 「正在朗读」行与「合成进度」行整块消失，要等下一段切换才回来
                        //（用户实报「有时候正在朗读日志不显示，有时候合成进度也不显示」）。
                        // 这里每 loop（200ms）检查一次，节流到 10 秒刷新一回 `at`
                        //（留 5 秒余量）。暂停/停止不刷：读的人本就该判过期。
                        val now = System.currentTimeMillis()
                        if (now - lastProgressHeartbeatMs >= PROGRESS_HEARTBEAT_MS) {
                            io.legado.app.help.ReadProgressFile.touch()
                            lastProgressHeartbeatMs = now
                        }
                    }
                    updateSubtitle()
                    // 检查并触发音效
                    checkAndPlayEffects(exoPlayer.duration, exoPlayer.currentPosition)
                }
                delay(200)
            }
        }
    }

    private fun stopSubtitleSync() {
        subtitleSyncJob?.cancel()
        subtitleSyncJob = null
        effectCalcJob?.cancel()
        effectCalcJob = null
        lastNowSpeak = -1
        currentSubtitles = emptyList()
        currentSubtitleCharRanges = emptyList()
        currentParagraphTotalChars = 0
        // 11-14：停止/暂停时立刻清掉播放进度文件——否则 UTTS 要等 15 秒超时
        // 才知道「不在播了」，日志页那条「正在朗读」会挂着旧内容不动。
        io.legado.app.help.ReadProgressFile.clear()
    }

    private fun prepareSubtitles() {
        if (nowSpeak >= contentList.size) return
        val raw = contentList[nowSpeak]
        var text = raw
        // 短段落要靠上一段衔接才能匹配上正则；从段落中间起播时，上文就是本段被截掉的那部分
        val prevText: String
        if (paragraphStartPos > 0 && paragraphStartPos < raw.length) {
            prevText = raw.substring(0, paragraphStartPos)
            text = raw.substring(paragraphStartPos)
        } else {
            prevText = contentList.getOrNull(nowSpeak - 1).orEmpty()
        }
        // 字幕用原始文本立即构建并交给本轮 updateSubtitle 发送。
        // 注意本函数不能挂起：调用方 while 循环里 updateSubtitle 排在它之后，
        // 一旦挂起等待音效计算，字幕就会被推迟到计算结束才显示。
        val baseText = purifySpeakText(text)
        buildSubtitles(baseText)

        // 视觉小说舞台：段落开始朗读即推送（说话人归属由 UI 侧反查章节角色缓存）
        postEvent(EventBus.VN_PARAGRAPH, VnParagraph(text, nowSpeak))

        val effectiveMode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
            AppConfig.getEffectiveSoundEffectMode(it)
        } ?: AppConfig.soundEffectMode
        if (effectiveMode != "off") {
            scheduleSoundEffects(text, baseText, nowSpeak, prevText)
        }
        // AI 音效：按字数区间调度，永远多备一个区间，
        // 保证读到某一段时它的音效早已分析完
        scheduleAiEffects()
        // AI 弹幕：同一套区间调度（按字数切区间、领先预排、到位触发）
        scheduleDanmaku()
    }

    /**
     * 异步计算音效规则，不阻塞字幕同步循环。
     * 替换结果只用于 MediaPlayer 音效播放，不影响合成文本。
     */
    private fun scheduleSoundEffects(
        text: String,
        baseText: String,
        index: Int,
        prevText: String = ""
    ) {
        effectCalcJob?.cancel()
        effectCalcJob = lifecycleScope.launch(Dispatchers.IO) {
            val (cleanText, effects) = parseSoundEffects(applySoundEffectRules(text, prevText))
            val effectText = purifySpeakText(cleanText)
            withContext(Dispatchers.Main) {
                // 计算期间段落已切换，丢弃结果，交由下一轮重新计算
                if (index != nowSpeak) return@withContext
                paragraphEffects[index] = effects
                // 规则若改写了正文（不只是插入音效标记），按替换后文本刷新一次字幕
                if (effectText != baseText) {
                    buildSubtitles(effectText)
                }
            }
        }
    }

    /** 按净化后的文本切片字幕并统计字数区间 */
    private fun buildSubtitles(purifiedText: String) {
        currentSubtitles = SubTitleUtils.splitToSubtitles(purifiedText, 15)
        var acc = 0
        currentSubtitleCharRanges = currentSubtitles.map { subtitle ->
            val chars = SubTitleUtils.countChinese(subtitle)
            val range = acc to (acc + chars)
            acc += chars
            range
        }
        currentParagraphTotalChars = acc
    }

    private fun updateSubtitle() {
        if (currentSubtitles.isEmpty()) return
        // 兜底：若 ExoPlayer 读不出时长，按字数估算总时长，避免字幕完全不更新
        val totalDuration = if (exoPlayer.duration > 0) {
            exoPlayer.duration
        } else {
            val rate = (AppConfig.ttsSpeechRate + 5).coerceAtLeast(1)
            val msPerChar = (1250f / rate).toLong()
            (currentParagraphTotalChars * msPerChar).coerceAtLeast(1)
        }
        if (totalDuration <= 0) return
        val ratio = exoPlayer.currentPosition.toFloat() / totalDuration
        val currentCharCount = (ratio * currentParagraphTotalChars).toInt()

        var currentSubtitle = currentSubtitles.lastOrNull() ?: ""
        var currentRange = currentSubtitleCharRanges.lastOrNull() ?: (0 to 0)
        for (i in currentSubtitleCharRanges.indices) {
            val (startChars, endChars) = currentSubtitleCharRanges[i]
            if (currentCharCount in startChars until endChars) {
                currentSubtitle = currentSubtitles[i]
                currentRange = startChars to endChars
                break
            }
        }
        postEvent(EventBus.READ_ALOUD_SUBTITLE, currentSubtitle)
        // 视觉小说舞台：片段位置（段内字符区间），驱动对话文字跟随朗读进度
        postEvent(
            EventBus.VN_SUBTITLE_POS,
            VnSubtitlePos(currentSubtitle, currentRange.first, currentRange.second)
        )

        val textChapter = textChapter ?: return
        val totalChapterChars = textChapter.getContent().length.coerceAtLeast(1)
        val paragraphReadChars = (ratio * currentParagraphTotalChars).toInt()
        val currentTotalRead = readAloudNumber + paragraphReadChars
        val progress = (currentTotalRead.toFloat() / totalChapterChars * 1000)
            .toInt()
            .coerceIn(0, 1000)
        postEvent(EventBus.READ_ALOUD_CHAPTER_PROGRESS, progress)
    }

    // ====== 静音超时检测 ======

    private fun checkSilentPlay() {
        silentPlayCheckJob?.cancel()
        val currentFileName = exoPlayer.currentMediaItem?.mediaId
        if (currentFileName != null && isSilentFile(currentFileName)) {
            silentPlayCheckJob = lifecycleScope.launch {
                delay(200L)
                try {
                    if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.playWhenReady
                        && exoPlayer.currentMediaItem?.mediaId == currentFileName
                        && isSilentFile(currentFileName)
                    ) {
                        AppLog.putDebug("静音播放超时，强制跳过: $currentFileName")
                        playErrorNo = 0
                        if (exoPlayer.hasNextMediaItem()) {
                            exoPlayer.seekToNextMediaItem()
                        } else {
                            updateNextPos()
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            silentPlayCheckJob?.cancel()
        }
    }

    // ==================== 跨章节预加载（11-20 重写） ====================

    /**
     * 唤醒跨章预加载调度器（幂等）。
     *
     * 语义（11-21 03:4x 旧口径恢复，用户终裁）：**旧式章末跨章备货**——只备
     * 「当前章 +1 .. +N」的**整章**（N = audioPreDownloadNum；11-23 起当前章
     * 剩余也归本调度器单链逐段备，旧滚动补货链与「预合成段数」设置一并退役）。
     * 常驻调度器形态保留（不取消在跑链，翻章由 validRange 清理旧章），
     * 但范围与 2852b91「从当前位置连续流」已彻底切割：后者是互掐翻车根因。
     * 合成动作走 batch_json（UTTS 后院备货），与播放零引擎冲突。
     * **暂停朗读时照备**（暂停不掐调度器，只掐播放）。
     *
     * 分工（旧版口径）：滚动补货管当前章剩余、跨章管后面整章，两路范围
     * 不相交，互斥守卫不再需要（maybePreloadFromPlayback 恒启用）。
     */
    private fun ensureChapterPreload() {
        // 11-21 03:4x（用户拍板「现在就一起恢复」）：恢复**旧式章末跨章预加载**——
        // 用户「当时」（预加热5段+并发2时期）顺畅的完整配方 = UTTS预取并发2（一直在）
        // + 同章滚动补货5段（28cbf1d 已恢复）+ 旧式跨章预加载（本函数）。
        // 与翻车的 2852b91「连续流」两个决定性差异：
        //  · 范围：只备**后面整章**（offset 1..N）——当前章剩余归滚动补货，两路不抢同一段；
        //  · 时机：章末（合成循环 needPreload，剩 5 段时）唤醒，常驻调度器自然只在
        //    备货期活动，平时不与播放抢引擎；合成动作走 batch_json（UTTS 后院备货），
        //    引擎槽不冲突（1f4b863 已实证 batch_json 路径本身无互掐）。
        // 28cbf1d 的停用 return 撤销；2852b91 的「从当前位置连续流」不再恢复。
        if (AppConfig.audioPreDownloadNum <= 0) {
            postPreloadState("")
            return
        }
        // 11-21 03:5x 诊断：真机 03:54 场次（7c48e1a 包）调度器零日志、UTTS 零
        // batch_json 请求——play() 与章末两处都该唤醒它却毫无痕迹。四个疑点
        // （设置值/引擎null/让路/排版挂死）代码层面都排不掉，加两行一次性
        // 诊断日志：入口必打（确认函数有没有被调到、limitChapter 实际值），
        // 协程启动必打（确认 launch 有没有执行）；轮函数内的静默 return
        // 再各打一行，复现一次就能锁死卡点。
        AppLog.put(
            "跨章预加载诊断: ensureChapterPreload 被调用, limitChapter=${AppConfig.audioPreDownloadNum}, " +
                "已有协程=${preloadJob?.isActive == true}, textToSpeech=${textToSpeech != null}, ttsInitFinish=$ttsInitFinish"
        )
        if (preloadJob?.isActive == true) return
        AppLog.putDebug("跨章预加载诊断: 启动调度协程")
        preloadJob = lifecycleScope.launch(Dispatchers.IO) {
            // 11-22 00:3x（用户实报「点击朗读到开始播放要花好久」）：**首轮之前**先让
            // 600ms 给播放笔——起播瞬间预热比播放早 31ms 抢到引擎串行门、独占 2.6 秒，
            // 首段因此晚 3.1 秒出声（详见 awaitFirstPlaybackWindow 注释）。
            // 只等**一次**：后续轮次照旧按让路判据跑，反复等待只会拖慢整体备货。
            runCatching { awaitFirstPlaybackWindow() }
            while (isActive) {
                val worked = try {
                    // 11-21 真机 05:13/05:21 两场次实锤：vivo 冻结（暂停+退后台）把
                    // 协程冻在 preloadOneSegment 的 completer.await() 里，解冻后
                    // 时钟坏掉 → withTimeoutOrNull(15s) 永不触发 → 协程变僵尸
                    //（isActive=true 但永不干活），幂等守卫挡住一切后续调度——
                    // 播放态也不动。自愈：每轮套 60s 总超时，卡死的挂起点被掐，
                    // while 继续下一轮，轮函数内层已重试/让路的语义不受影响
                    //（60s > 单段 15s×3 重试 + 段表构建的合理上限）。
                    withTimeout(60_000L) {
                        runChapterPreloadRound()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    AppLog.putDebug("跨章预加载诊断: 轮次 60s 超时自愈重启")
                    false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.putDebug("跨章预加载轮次异常: ${e.localizedMessage}")
                    false
                }
                // 有活立刻继续（按「近到远」一段接一段）；没活才歇一会儿再查。
                // 歇着也要轮询：暂停/跳段后起点会变，不能只靠外部事件唤醒（会漏）。
                //
                // ===== 11-22 01:0x（用户令「改吧」）：暂停时的间歇 2.5s → 0.3s =====
                //
                // 旧口径 `if (isPlay()) 1_500 else 2_500` 在**暂停态反而等更久**，
                // 逻辑是反的。用户问「不朗读暂停朗读就缓存得更快吗」，核算后：
                //   · 朗读中：合成 1.6s + 间歇 1.5s + 让路等待(0~3s) = 3.1~6.1s/段
                //     （让路等待占大头，且会出现 12 秒级长空档）
                //   · 暂停后：合成 1.6s + 间歇 2.5s + 无让路        = 4.1s/段（稳定）
                // 暂停的本质优势是「没有播放抢占」，理应急着备——给它更长的间歇
                // 等于把优势抵消掉。
                //
                // 新口径：
                //   · 朗读中 1.5s 不变——要频繁回来检查是否该为播放让路；
                //   · 暂停后 0.3s——没有任何东西需要让路，备货是唯一任务，连续跑最快。
                //     速度约从 4.1s/段 → 1.9s/段（翻倍多）。
                // 0.3 而非 0：留一点余量，避免「没活可干」时（章尾备完/正文未就绪）
                // 变成忙等空转烧 CPU。
                if (!worked) delay(if (isPlay()) 1_500L else 300L)
            }
        }
    }

    /**
     * 11-22 00:3x（用户实报「点击朗读到开始播放要花好久」）：**首段优先窗口**。
     *
     * ## 现场（appLog + debug.log 实锤，23:29:38 那次起播）
     * ```
     * 23:29:38.511  预热协程提交「第254章第2段」preload_only
     * 23:29:38.542  播放笔 Legado0（标题段）提交 ← 晚 31ms
     * 23:29:41.121  预热段合成完成（跑了 2.6 秒，期间独占引擎串行门）
     * 23:29:41.658  Legado0 才真正开始合成
     * 23:29:43.365  出声
     * ```
     * 合计 4.82 秒，其中约 3.1 秒纯粹在**排队等预热让路**。
     *
     * ## 为什么现有的让路机制没拦住
     * `runChapterPreloadRound` 里的判据是 `playbackSubmitAt > playbackSettledAt`
     * ——依赖「播放已经提交过」这个事实。可起播那一瞬，预热协程比播放笔早 31ms
     * 抢占，那时 playbackSubmitAt 还是 0（本次播放还没提交过任何一笔），
     * 让路判据根本不成立。它随后独占了 UTTS 的 onSynthesizeText 串行队列。
     * 于是「让路」在整段预热跑完之后才开始生效（日志里那两条
     * 「让路放弃（播放请求在飞）」出现在 23:29:41.659 之后，正是第一轮结束后）。
     *
     * ## 修法（用户 11-22 拍板 A 案：首段优先）
     * 在**预热协程刚起来那一轮**之前先等一小段（[PRELOAD_FIRST_WINDOW_MS]），
     * 把起跑机会让给播放笔：
     *   · 播放笔一旦提交，playbackSubmitAt 被置位 ⇒ 本轮让路判据立刻生效，正常让路；
     *   · 若这段时间里没有播放（比如预热是章末触发的、播放正在跑），
     *     就按原逻辑继续——只是晚 600ms 开工，备货总量不变。
     * 只作用于**首次**（每个 preloadJob 生命周期一次），后续轮次照旧不等待，
     * 否则每次让路后再跑都要多等 600ms，反而拖慢整体备货。
     *
     * 代价：起播瞬间的备货晚 600ms 起跑。备货本来就领先播放十几段，
     * 600ms 在整章尺度上可忽略；换来的是**首段早 3 秒出声**——用户直接感知的收益。
     */
    private suspend fun awaitFirstPlaybackWindow() {
        // 记住进入窗口前的提交时刻：只有**窗口期间新提交的**才算「播放来了」，
        // 否则上一次播放留下的旧值（playbackSubmitAt>0）会让窗口一进来就误判退出，
        // 等于没等——那正是要修的竞态本身。
        val baselineSubmitAt = playbackSubmitAt
        val deadline = SystemClock.elapsedRealtime() + PRELOAD_FIRST_WINDOW_MS
        // 逐 50ms 探一次：一旦播放笔提交就立刻退出等待，不必死等满窗口。
        while (SystemClock.elapsedRealtime() < deadline) {
            if (playbackSubmitAt > baselineSubmitAt) {
                AppLog.putDebug("跨章预加载诊断: 首段优先窗口——播放已提交，让路")
                return
            }
            delay(50L)
        }
        AppLog.putDebug("跨章预加载诊断: 首段优先窗口结束（期间无播放提交），开始备货")
    }

    /**
     * 跑一轮：按「近到远」找第一个还没备完的章，往后备 **1 段**，返回是否做了工作。
     *
     * 为什么每轮只备 1 段：播放请求一来，最多只损失一段的在途时间（让路粒度）；
     * 同时保证「进度始终连续推进」——用户随时看都能看到前沿往前走。
     */
    private suspend fun runChapterPreloadRound(): Boolean {
        val book = ReadBook.book ?: run {
            AppLog.putDebug("跨章预加载诊断: 轮次退出 book=null")
            return false
        }
        // ===== 11-23（用户实报「新书一直出上文探针定位失败、串书防护一直剔除角色」）=====
        // 按**书身份**判换书：章号范围清理治不了换书（两本书章号都从 0 起），
        // 旧书段表会挂着同号章继续备货 ⇒ 新书里提交旧书段落 ⇒ 规则窗口对不上
        // ⇒ 探针失败 + AI 拿旧书段落的名字污染新书角色表（写前过滤才拦得住）。
        // 换了书就整组清空，本轮直接退出，下一轮用新书重建。
        val bookUrl = book.bookUrl
        val prevBookUrl = Companion.preloadBookUrl
        if (prevBookUrl != null && prevBookUrl != bookUrl) {
            AppLog.put(
                "跨章预加载诊断: 换书，清空预加载状态（旧=${prevBookUrl.takeLast(24)}，" +
                    "新=${bookUrl.takeLast(24)}）"
            )
            preloadChapterProgress.clear()
            preloadChapterCache.clear()
            preloadChapterRetryAt.clear()
            Companion.preloadPreparedStore.clear()
            preloadSegmentFailCount.clear()
            preloadDesyncRounds.clear()
            preloadInterruptCount.clear()
            Companion.preloadBookUrl = bookUrl
            // 11-24 02:0x（用户实报「换书后吸顶还显示缓冲 34 段·跨章」）：
            // 清内存账本**不够**——`bufferedAheadSegs` 是落盘字段，只有段切换/合成提交/
            // 合成完成三条路会改它，换书时没人动 ⇒ 它停在换书前最后一笔的值上，
            // 真机 01:22:47.160 写进旧书第5章的 34，到 :55.018 下一笔重算才归零，
            // 中间 8 秒吸顶一直显示旧书的跨章数。这里主动清零，新书没备过段 ⇒ 0 就是真值。
            runCatching { io.legado.app.help.ReadProgressFile.clearBufferedAhead() }
            return false
        }
        if (prevBookUrl == null) Companion.preloadBookUrl = bookUrl
        if (textToSpeech == null) {
            AppLog.putDebug("跨章预加载诊断: 轮次退出 textToSpeech=null")
            return false
        }
        if (!ttsInitFinish) {
            AppLog.putDebug("跨章预加载诊断: 轮次退出 引擎未就绪")
            return false
        }
        // 用户设置「听书预加载数量(章节)」多少就备多少章——**不加人为上限**。
        // 用户 11-20 明确：有人会填 100 章，希望真的能加载 100 章；
        // 「每个人按自己的情况设置」是他对这个键的定义。
        val limitChapter = AppConfig.audioPreDownloadNum.coerceAtLeast(0)
        if (limitChapter <= 0) {
            postPreloadState("")
            return false
        }
        val currentIdx = ReadBook.durChapterIndex
        val validRange = currentIdx..(currentIdx + limitChapter)
        // 落在目标范围外的章不再需要：进度、段表、重试时刻一起丢掉
        preloadChapterProgress.keys.filter { it !in validRange }.forEach {
            preloadChapterProgress.remove(it)
            preloadChapterCache.remove(it)
            preloadChapterRetryAt.remove(it)
            Companion.preloadPreparedStore.remove(it)
            preloadDesyncRounds.remove(it)
            // 11-27 顺手修既有笔误：原来 `remove(it)` 传的是**章号**而不是 key `k`，
            // 于是这些失败计数从来没被真正删掉（只会越积越多）。
            preloadSegmentFailCount.keys.filter { k -> k.startsWith("$it|") }.forEach { k ->
                preloadSegmentFailCount.remove(k)
            }
            preloadInterruptCount.keys.filter { k -> k.startsWith("$it|") }.forEach { k ->
                preloadInterruptCount.remove(k)
            }
        }
        // 让路判据①：播放请求在飞 ⇒ 先短等它落定，等不到才放弃。
        //
        // 11-21 15:5x（用户终裁「播放即掐」）：判据从「已飞 6 秒才算卡」收紧为
        // **只要在飞就让**——播放提交后到 UTTS 开始合成之间有空档，此刻塞备货
        // 进串行队列就会排在播放前面，播放开头等 2~3s。
        //
        // ===== 11-22 13:2x（用户拍板「照你推荐来吧」）：直接放弃 → 有界等待 =====
        // 旧写法命中就 `return false`，交给调度器 1.5s 后再来；可播放每 1~3 秒就
        // 提交一笔，等它回来时下一笔往往又已提交 ⇒ 判据再次成立 ⇒ 真机上
        // **354 次让路全部是这一个理由**，而预加载与播放逐段几乎同时提交（无提前量）。
        // 现改为：小步轮询等播放落定（[PRELOAD_YIELD_WAIT_MS] 为界），
        // 等到了就立刻跑这一轮、抢在下一笔播放之前备 1 段；等不到照旧放弃，
        // 「播放即掐」的原意完全保留（绝不把备货塞进播放队列）。
        // 等待期不持锁、不发请求，只是观察，代价可忽略。
        if (playbackSubmitAt > playbackSettledAt) {
            val yieldWaitStart = SystemClock.elapsedRealtime()
            var settledInTime = false
            while (SystemClock.elapsedRealtime() - yieldWaitStart < PRELOAD_YIELD_WAIT_MS) {
                // ⚠️ 这里不能用裸 `isActive`：runChapterPreloadRound 不是 CoroutineScope
                // 的扩展函数，isActive 的两个候选（CoroutineContext / CoroutineScope 扩展）
                // 都没有接收者 ⇒ 编译报「Unresolved reference 'not' for operator '!'」。
                // 11-22 CI 从 602324a 起连红 5 次就是这个原因（本地没编译所以没发现）。
                if (!currentCoroutineContext().isActive) return false  // 服务/协程被停：立即收口
                if (playbackSubmitAt <= playbackSettledAt) {
                    settledInTime = true             // 播放落定，让出机会了
                    break
                }
                delay(PRELOAD_YIELD_POLL_MS)
            }
            if (!settledInTime) {
                AppLog.putDebug("跨章预加载诊断: 让路放弃（播放请求在飞，等待超时）")
                return false
            }
            AppLog.putDebug(
                "跨章预加载诊断: 让路转为等待——播放已落定，" +
                    "抢跑（等了 ${SystemClock.elapsedRealtime() - yieldWaitStart}ms）"
            )
        }
        // 旧判据（已飞 6 秒才算卡）随「播放即掐」口径废止，保留作历史参考：
        if (playbackSubmitAt > playbackSettledAt &&
            SystemClock.elapsedRealtime() - playbackSubmitAt >= PRELOAD_YIELD_PLAYBACK_STUCK_MS
        ) {
            AppLog.put(
                "跨章预加载诊断: 让路放弃（播放请求已飞 ${ (SystemClock.elapsedRealtime() - playbackSubmitAt) / 1000 }s）"
            )
            return false
        }
        // 让路判据②（11-21 真机实锤 04:58 卡死）：滚动补货链正在跑 ⇒ 它的段正
        // 占着 UTTS onSynthesizeText 串行队列（每笔 2.7s×N）。跨章那笔此时走
        // synthesizeToFile 是【无超时的 binder 同步提交】，会排在队尾堵死——
        // 15s 的 completer 超时管不到提交阶段，轮函数整个挂死、零日志。
        // 同门互让：滚动链跑完本轮再来，两链本就共用 preloadGate 串行门，
        // 只是把「等锁」提前到「本轮放弃」，避免无超时 binder 阻塞。
        // 11-21 15:4x：滚动链已停用（maybePreloadFromPlayback 入口 return），
        // 本判据恒不触发，保留代码作回退参考。
        if (preloadChainJob?.isActive == true) {
            // 11-21 20:2x（用户口径「提示让路这种没必要显示」）：put→putDebug，
            // 与本函数其余两处让路日志同级别——排障开调试日志仍可见，平时不刷屏。
            AppLog.putDebug("跨章预加载诊断: 让路放弃（滚动补货链在跑）")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        // ===== 11-21 15:4x（用户终裁「单链线性补货」）=====
        // 用户口径：从当前位置起按正文顺序连续备——第 n 段在播就备 n+1、n+2…
        // 本章尾 → 下一章第 1 段 →…，永远备「正文顺序上第一个没备过的段」；
        // 快进时起点跟着播放位置走。旧格局（滚动链 nowSpeak+4 备当前章 +
        // 跨章链只备下一章）会产生「快进几段没备上、下一章先备了」的错序。
        //
        // 改法：轮函数扫描范围改为 offset 0..limitChapter（**含当前章**），
        // 当前章内从「播放位置之后的第一段」找第一个没备过的段；下一章起
        // 从头扫。滚动补货链 maybePreloadFromPlayback 停用（见该函数），
        // 全部备货走本函数单一来源，串行门 preloadGate 天然保序。
        //
        // 进度表 preloadChapterProgress 的语义相应放宽：当前章记的是
        // 「从章首起已备到的段下标」，但快进/跳段后进度可能落后于真实缓存
        // 状态——所以当前章不用进度表直接定位，改为**逐段探测**（缓存命中
        // 即跳过），探测到的位置回写进度表，下轮从那继续，O(1) 续跑。
        for (offset in 0..limitChapter) {
            val idx = currentIdx + offset
            if ((preloadChapterRetryAt[idx] ?: 0L) > now) continue
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, idx) ?: run {
                AppLog.putDebug("跨章预加载诊断: 第${idx + 1}章 getChapter=null")
                continue
            }
            val preloaded = preloadChapterCache[idx] ?: run {
                AppLog.putDebug("跨章预加载诊断: 第${idx + 1}章 开始构建段表")
                val built = buildPreloadSegments(book, chapter)
                AppLog.put(
                    "跨章预加载诊断: 第${idx + 1}章 段表构建完成 结果=${built?.segments?.size ?: "null"}章"
                )
                if (built == null) {
                    // 正文没下载到本地（预下载还没轮到 / 排版不可用）：记重试时刻，稍后再来
                    preloadChapterRetryAt[idx] = now + PRELOAD_CHAPTER_RETRY_MS
                    AppLog.putDebug(
                        "跨章预加载跳过: 第${chapter.index + 1}章正文未就绪（${chapter.title.take(12)}），稍后重试"
                    )
                    return false
                }
                preloadChapterCache[idx] = built
                // 11-23：段表重建后旧的分段口径可能已变，旧账不再可信，清掉重记。
                Companion.preloadPreparedStore.remove(idx)
                built
            }
            val total = preloaded.segments.size
            if (total <= 0) continue
            // 11-22 21:5x（用户「播到 74 停了我还以为没播完」）：对外报的章内总段数
            // 去掉末尾章末占位段，与播放侧 speakTotalSegs 同口径。内部扫描仍用 total
            //（占位段也在段表里，只是不报给日志页当分母）。
            val displayTotal = if (preloaded.segments.last().isBlank()) total - 1 else total
            // ===== 11-22 14:2x（用户实报「到章节末尾就不再加载了」）=====
            //
            // ## 现场（真机 12:08 场次，第259章 104 段）
            // 已备段号：3 8 21 23 25 29~41 43 45 50 60 63 67 69~72 75 79 81
            // **82 以后的 23 段一个都没备**，且 82~98 这 17 段在全量日志里
            // **从来没被任何请求合成过**（不是被播放路径补上了，是真漏了）。
            // 12:14:08 备完第 81 段，12:14:35 直接跳去备第260章。
            //
            // ## 根因：`from = nowSpeak + 1` 会把未备段永久跳过
            // 旧代码：
            //   ```
            //   var from = preloadChapterProgress[idx] ?: 0
            //   if (offset == 0 && from <= playPos) from = playPos + 1
            //   ```
            // 用户跳段时 `nowSpeak` 直接跳到 99，于是 `from` 被推成 100；
            // `preloadChapterProgress` 随后也只会记 100 之后的值。
            // **82~98 这些从没备过、也没被播放过的段从此再也扫不到**——
            // 「起点已经越过它们」不等于「它们已经备好了」，这是纯粹的状态混淆。
            // 越靠近章末越明显：播放位置冲到 total 附近后 from>=total ⇒ `continue`
            // 直接进下一章，当前章剩下的一整截就此放弃，这正是用户观感
            //「到章末就不加载了」。
            //
            // ## 修法：以「已备」为唯一判据，不以「播放到过」为判据
            // 不再用 nowSpeak 推 from。改为从 `preloadChapterProgress` 起**逐段探测**：
            //   · 该段缓存已存在 ⇒ 跳过（继续往后找一个真没有的）；
            //   · 该段没备 ⇒ 就是本轮要备的目标（不论它相对播放位置是前是后）。
            // 探测上界为本章末；一次探测最多扫 total 段（list/stat 级，几十~百次），
            // 且每个已备段探测一次后就推进进度表，不会重复付。
            //
            // 为什么要探测、不能只看进度表：跳段/回跳后进度表可能领先或落后真实
            // 缓存状态（真机 12:14「进度 81、实际已备 81」看似一致，但 82~98 是
            // 从没进过进度表也没备过的空洞）。逐段探测是唯一能发现空洞的办法，
            // 也是旧注释里「逐段探测缓存兜住」原本就想做、但被 nowSpeak 短路掉的事。
            var from = preloadChapterProgress[idx] ?: 0
            if (from >= total) from = 0   // 进度表越界（旧值遗留）：从头探测，别放弃整章
            // ===== 11-22 17:0x（用户实报「从章节中间开始朗读，却先缓存这章的标题」）=====
            //
            // ## 现场（真机 15:43~16:04 四章实锤，只看 TTS 日志即可复现）
            //   第4章 入公门      首笔预加载=第1段「第4章 入公门」，播放起点=57/71
            //   第8章 东珠        首笔预加载=第1段「第8章 东珠」，   播放起点=63/92
            //   第261章 离开曼德拉  首笔预加载=第1段「第261章 离开曼德拉」，播放起点=13/133
            //   0077 前两个男主    首笔预加载=第1段「0077…」，      播放起点=15/36
            // 而从头读的章（第5/6/0079/0080…）播放起点=1，那时先备标题段是对的。
            //
            // ## 根因：14:2x 修「章末不再加载」时，把「当前章从播放位置起备」一起删了
            // 旧代码有一段 `if (offset == 0 && from <= playPos) from = playPos + 1`，
            // 它确实有 bug（跳段后会把没备过的段永久跳过，真机 82~98 段漏备），
            // 于是整段被换成「从进度表/0 起逐段探测」。修好了漏备，却把
            // **「标题段及播放位置之前的段永远读不到，不必备」** 这个正确约束丢了：
            // 你从第57段开读，段1~56 到本章结束都不会被播放，备它们纯属白烧。
            //
            // ## 修法：只给「当前章」加下限，不动逐段探测
            //   · offset == 0（当前章）且已开始播放（nowSpeak>0）⇒ 探测起点钳到 nowSpeak，
            //     标题段/播放前段不再备；
            //   · nowSpeak==0（还没播/正从头读）⇒ 仍从 0 起，标题段照旧先备（正常行为）；
            //   · offset>0（后续章）⇒ 从头备，没有下限。
            // **不恢复旧的 `from = playPos + 1`**：那会把 from 直接推过空洞（漏备根因）。
            // 这里只改探测的**起点**，探测本身仍是「跳过已备、停在第一个真没备的段」，
            // 所以跳段后章末继续加载（B7 已验）不受影响——跳段后 nowSpeak 变大，
            // 起点也跟着变大，恰恰是想要的行为。
            //
            // ===== 11-23 修（用户实报「预加载先备下一章章头、回头又把本章从第1段重备」）=====
            //
            // ## 现场（真机 20:47:01 那场，689KB 日志）
            //   20:47:01.109  第3章读完，自动跳第4章
            //   20:47:01.221  预加载轮开始，此刻 nowSpeak 仍停在第3章末尾的 **997**
            //   20:47:01.239  直接开始构建**第5章**段表（第4章被判「已备完」跳过）
            //   20:47:01.318  下一轮 from=108≥total 命中归零 → 第4章**从第1段重来**
            //
            // ## 根因：下限用了不属于本章的 nowSpeak
            // `nowSpeak` 是单个 Int，切章瞬间还是上一章的值。旧写法无条件拿它当下限：
            // 上一章比新章**长**（997 > 108）⇒ 起点越界 ⇒ 逐段探测一行都不跑 ⇒
            // `scan >= total` 成立 ⇒ 判「本章全备完」、把进度写成 total 就去下一章。
            // 那个「已备完」是纯伪结论。旧章比新章**短**时更阴：floor 落在章中间，
            // 前面整截永不探测 ⇒ 静默漏备（不报错、日志上表现为"第一段起就没备"）。
            // 旧注释警告的「跳过已备、停在第一个真没备的段」在这个窗口里根本没机会执行。
            //
            // ## 修法：下限先验章号
            // `nowSpeakChapterIndex`（BaseReadAloudService 新增）记录 nowSpeak 属于哪一章，
            // 只有它 == 本章时才采用 nowSpeak 当下限；否则下限按 0 起。
            // 从 0 起不会误判：逐段探测本来就会跳过已备段，代价只是几次 list/stat。
            // 章号为 -1（服务刚起、还没落过点）时同样按 0 起——宁可多探、不可错判。
            val nowSpeakHere = nowSpeakChapterIndex == idx
            val currentChapterFloor =
                if (offset == 0 && nowSpeakHere) nowSpeak.coerceAtLeast(0) else 0
            if (!nowSpeakHere && offset == 0 && nowSpeak > 0) {
                // 只在真踩到「跨章旧值」时留一行：正常稳态零噪音，复现时一眼能认
                AppLog.putDebug(
                    "跨章预加载下限按0: nowSpeak=$nowSpeak 属第${nowSpeakChapterIndex + 1}章，" +
                        "当前第${idx + 1}章（段表章=$contentListChapterIndex）"
                )
            }
            if (from < currentChapterFloor) from = currentChapterFloor
            // 逐段推进：跳过已备好的段，停在第一个真没备的段上
            var scan = from
            // 11-27：本轮探测命中数（见 PRELOAD_DESYNC_MAX_ROUNDS）
            var probeHit = 0
            while (scan < total) {
                val probeRaw = preloaded.segments[scan]
                if (probeRaw.isBlank()) { scan++; continue }
                val probe = runCatching {
                    getCacheFileForText(probeRaw, scan, preloaded.title)
                }.getOrNull()
                if (probe != null && probe.exists() && probe.length() > 0) {
                    scan++          // 已备：跳过，继续找下一个空洞
                    probeHit++
                    continue
                }
                break               // 找到第一个没备的段 = 本轮目标
            }
            if (scan >= total) {
                // 本章真的全备完了（逐段探测确认过），才允许进下一章
                preloadChapterProgress[idx] = total
                preloadDesyncRounds.remove(idx)
                continue
            }
            // ===== 11-27：防「无限重扫」兜底闸（详见 PRELOAD_DESYNC_MAX_ROUNDS）=====
            //
            // 判据：本轮从 from 起探测了多段、却**一个文件都没命中**，且进度表
            // 已经推进过（from>下限）——这正是「记账说有、文件探测说没有」的
            // 失配特征。此时若照旧从 0 重扫，就会变成每 40 秒扫一遍全章的死循环
            // （真机第10章 213 遍、25126 次提交里 98.9% 是重复）。
            //
            // 处置：连续 3 轮都这样 ⇒ 整章退避 5 分钟，并打 WARN 级日志。
            // 正常情况（键一致）永远不会走到这里：探测会命中，probeHit>0。
            if (probeHit == 0 && from > currentChapterFloor) {
                val rounds = (preloadDesyncRounds[idx] ?: 0) + 1
                preloadDesyncRounds[idx] = rounds
                if (rounds >= PRELOAD_DESYNC_MAX_ROUNDS) {
                    preloadDesyncRounds[idx] = 0
                    preloadChapterRetryAt[idx] =
                        SystemClock.elapsedRealtime() + PRELOAD_DESYNC_RETRY_MS
                    // 用 put（WARN 级）而不是 putDebug：这是「判据失配」的告警，
                    // 出问题时用户导出日志就能直接看到，不必先开调试开关。
                    AppLog.put(
                        "跨章预加载探测失配：第${idx + 1}章 连续 $rounds 轮逐段探测零命中" +
                            "（记账=${preloadChapterProgress[idx] ?: 0}/$total，" +
                            "from=$from），已退避 ${PRELOAD_DESYNC_RETRY_MS / 1000} 秒。" +
                            "正常不该出现——出现即说明缓存文件名口径与写入侧不一致"
                    )
                    return false
                }
            } else {
                preloadDesyncRounds.remove(idx)
            }
            // 进度表对齐到探测位置：下次从这里继续，O(1) 续跑
            preloadChapterProgress[idx] = scan
            from = scan
            preloadActiveChapter = idx
            val rawText = preloaded.segments[from]
            if (rawText.isBlank()) {
                preloadChapterProgress[idx] = from + 1
                return true
            }
            // 兜底复查：上面的逐段探测已保证 from 是「第一个没备的段」，
            // 正常不会命中这里。保留是防御——探测与本行之间若被播放路径
            // 抢先写好了同一段（并发），这里直接放行、不重复提交。
            val cacheFile = getCacheFileForText(rawText, from, preloaded.title)
            if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
                preloadChapterProgress[idx] = from + 1
                return true
            }
            // 11-21 12:1x（用户拍板「逐段卡要改」）：合成动作改走**单段 preload_only**
            // （原 03:4x 口径 batch_json 只回静音、日志页双闸拦截，用户看不到任何
            // 下一章痕迹）。preload_only 是 UTTS 11-20 定稿的预加载正路：正常单段
            // 合成、真实音频落 U阅 缓存、日志页出「请求音频+获取成功（预合成）」卡
            // （正文全文+音色标注+段号 N/M）、失败出红行；不破窗、不占播放槽位、
            // 不被 onStop 掐——暂停/播放都不影响备货。传【原始文本】：UTTS 自己跑
            // 规则，不双跑脚本（bcea7a8 原判）。
            val tts = textToSpeech ?: return false
            // 11-21 11:5x（用户报「顶部合成进度不显示下一章」）：跨章备货此前绕开
            // synthesizeText 直提引擎，从不刷新 readProgress.json 的合成前沿 →
            // UTTS 日志页顶部恒停播放章。补播放同款上报（reportSynthFront 759 行
            // 同源）：UTTS 渲染端本就支持跨章格式「后台合成 第X章 n/63（当前：
            // 第Y章 m/99）」，数据到了它就动。章节序/标题/总段数取本章段表。
            io.legado.app.help.ReadProgressFile.reportSynthFront(
                idx,
                from,
                preloaded.title,
                displayTotal,
                // ===== 11-22 15:0x：预加载笔也要上报缓冲段数 =====
                //
                // 旧调用只传了前 4 个参数，第 5 个 `bufferedAhead` 吃默认值 -1，
                // UTTS 侧收到 -1 就退回旧画法、**不显示「缓冲 N 段」**。
                // 而跨章预加载恰恰是备货最活跃的时候，这期间数字一直是空的，
                // 用户看到「缓冲 0 段 / 无数字」正是这个原因。
                //
                // 基准同样锚**正在播放的段**（nowSpeak）+ **播放章**标题，
                // 与主播放路径完全同源——两条路若用不同基准，
                // 同一时刻会算出两个不同的数，数字就会来回跳。
                countBufferedAhead(
                    nowSpeak,
                    textChapter?.title ?: textChapter?.chapter?.title ?: "",
                ),
            )
            AppLog.put(
                "跨章预加载诊断: 第${idx + 1}章第${from + 1}段 提交preload_only前（文本=${rawText.take(10)}）"
            )
            var ok = false
            var interruptedByPlayback = false
            // 播放打断判据：play() 会自增 abortGeneration（见 doSynthesizeText）。
            // batch_json 路径下播放打断表现为引擎 stop → onError 回 null（不是
            // 协程取消），所以改用代次对比识别「这 15 秒里播放重置过」。
            val genBefore = abortGeneration
            try {
                ok = preloadGate.withLock {
                    val submitAt = SystemClock.elapsedRealtime()
                    // 11-21 11:1x 实锤修复：这里已持 preloadGate，必须调**不加锁的
                    // 内层** preloadOneSegmentLocked。fadcaf1 误调带锁壳子
                    // preloadOneSegment → Mutex 不可重入 → 与自己死锁：插桩行
                    //（在锁内）永远到不了，60s 看门狗每轮掐掉重来，日志上表现为
                    //「提交前→60s 自愈」无限循环、preloadOneSegment 返回行零出现。
                    val result = preloadOneSegmentLocked(from, rawText, tts, displayTotal, preloaded.title)
                    AppLog.put(
                        "跨章预加载诊断: 第${idx + 1}章第${from + 1}段 preloadOneSegment 返回=$result " +
                            "耗时=${(SystemClock.elapsedRealtime() - submitAt) / 1000}s " +
                            "代次变化=${abortGeneration != genBefore}"
                    )
                    result
                }
            } catch (e: CancellationException) {
                // 两种取消必须分开（沿用 11-20 二次定案）：
                //  · 调度器自己被取消（onDestroy）→ 原样上抛，让协程正常结束；
                //  · 播放触发的协程中断 → **不是失败**：不计失败、不退避，下一轮
                //    立刻重试同一段。
                //  判据：preloadJob 还活着 ⇒ 这个取消来自播放路径，不是我们自己。
                if (preloadJob?.isActive != true) throw e
                interruptedByPlayback = true
            } catch (e: Exception) {
                AppLog.putDebug("跨章预加载合成异常: ${e.localizedMessage}")
            }
            if (!ok && !interruptedByPlayback && abortGeneration != genBefore) {
                // 等待期间播放重置过（play() 重提播放链会 stop 引擎，在途预热
                // 被掐）→ 与协程中断同待遇：不计失败、不退避。
                interruptedByPlayback = true
            }
            // 11-21 13:2x（用户报「预合成顺序不对」瑕疵 B）：服务 stop（onDestroy
            // 已置 isRun=false）会把在途那笔一起掐掉，completer 收 onError → 返回
            // false——这不是合成失败，不该记失败次数、更不该让日志页出现
            // 「1/81、2/81 重来一遍」的进度回退观感。服务都没了，本轮就地收口。
            if (!ok && !interruptedByPlayback && !isRun) {
                AppLog.putDebug("跨章预加载诊断: 第${idx + 1}章第${from + 1}段 服务停止，不计失败")
                return false
            }
            when {
                ok -> {
                    preloadChapterProgress[idx] = from + 1
                    preloadSegmentFailCount.remove("$idx|$from")
                    preloadInterruptCount.remove("$idx|$from")
                    // ===== 11-23 23:5x（用户实拍「读 n 章、后台备到 n+1 章，缓冲报 0」）=====
                    // 记账**不再以「自己重算的文件名存在」为条件**。
                    // 真机 23:49 实锤：第23章 22:49:42–22:50:10 连报「已备 1..12 段」，
                    // 同一时段的缓冲上报却是 `跨章明细=[第23章=0(文件0/记录0)]`——
                    // 就是因为这里 stat 自己重算的名字（title/voice/index 任一与写入侧
                    // 不同就 miss）验不到 ⇒ 一笔都不记 ⇒ 跨章恒 0。
                    // 引擎已回成功（下一行「已备」在涨）就是「这段能直接播」的充足证据：
                    // 真落盘改名失败时播放会改走 UTTS 的 synth_cache，照样能连听。
                    // 所以这里**只记账、不验文件**；文件扫描留在计数侧做兜底与对照。
                    Companion.preloadPreparedStore
                        .getOrPut(idx) { java.util.Collections.synchronizedSet(mutableSetOf()) }
                        .add(from)
                    AppLog.putDebug(
                        "跨章预加载已备: 第${idx + 1}章 第${from + 1}/$total 段（${preloaded.title.take(12)}）"
                    )
                }
                interruptedByPlayback -> {
                    // 播放优先是设计内行为：不推进、不计数，下轮立刻重试同一段。
                    // 那段确实没备上，必须补；但也不留任何「失败」痕迹。
                    //
                    // ===== 11-27：同段反复被打断要退避（真机第13章第18段被打断 8 次）=====
                    //
                    // 为什么要加：`play()` 里每笔播放都会 `ensureChapterPreload()` +
                    // `activateTtsEngine()`，而旧激活实现每次建连再 shutdown，会把
                    // 正在跑的那笔预热一起掐掉 ⇒ 同段「被打断 → 下轮立刻重试 →
                    // 又被下一笔播放打断」来回空转，同时不断触发引擎重建，
                    // 最终把播放笔本身也投到死实例上（23:07:19 那次 36 秒零回调）。
                    //
                    // 退避只针对**反复**被打断的同一段（打不断就正常推进，不受影响）：
                    // 第 1 次照旧立刻重试（保持原有「不丢段」语义），第 2 次起按
                    // 500ms×次数递增，封顶 [PRELOAD_INTERRUPT_BACKOFF_MAX_MS]。
                    // 这与「真失败」的计数是两套账，互不污染。
                    val intKey = "$idx|$from"
                    val ints = (preloadInterruptCount[intKey] ?: 0) + 1
                    if (ints >= 2) {
                        preloadInterruptCount[intKey] = ints
                        val backoff = (PRELOAD_INTERRUPT_BACKOFF_BASE_MS * (ints - 1))
                            .coerceAtMost(PRELOAD_INTERRUPT_BACKOFF_MAX_MS)
                        preloadChapterRetryAt[idx] = SystemClock.elapsedRealtime() + backoff
                        AppLog.putDebug(
                            "跨章预加载诊断: 第${idx + 1}章第${from + 1}段 被播放打断（第 $ints 次），" +
                                "退避 ${backoff}ms 再试"
                        )
                    } else {
                        preloadInterruptCount[intKey] = ints
                        AppLog.putDebug(
                            "跨章预加载诊断: 第${idx + 1}章第${from + 1}段 被播放打断"
                        )
                    }
                }
                else -> {
                    AppLog.putDebug("跨章预加载诊断: 第${idx + 1}章第${from + 1}段 合成返回失败")
                    // 真失败**不推进进度**，退避后重试同一段；
                    // 连续 3 次仍失败才跳过，避免死磕合不出的段。
                    val failKey = "$idx|$from"
                    val fails = (preloadSegmentFailCount[failKey] ?: 0) + 1
                    if (fails >= PRELOAD_SEGMENT_MAX_FAILS) {
                        preloadSegmentFailCount.remove(failKey)
                        preloadChapterProgress[idx] = from + 1
                        AppLog.putDebug(
                            "跨章预加载第 ${from + 1} 段连续失败 ${fails} 次，跳过（${preloaded.title.take(12)}）"
                        )
                    } else {
                        preloadSegmentFailCount[failKey] = fails
                        preloadChapterRetryAt[idx] =
                            SystemClock.elapsedRealtime() + PRELOAD_SEGMENT_RETRY_MS
                        AppLog.putDebug(
                            "跨章预加载第 ${from + 1} 段失败（第 $fails 次），稍后重试（${preloaded.title.take(12)}）"
                        )
                    }
                }
            }
            postPreloadState(summarizePreloadState(currentIdx, limitChapter))
            return true
        }
        // 走完一圈：全部章都备完了
        preloadActiveChapter = -1
        AppLog.putDebug("跨章预加载诊断: 一圈走完全部已备（currentIdx=$currentIdx, limit=$limitChapter）")
        postPreloadState(summarizePreloadState(currentIdx, limitChapter))
        return false
    }

    /**
     * 给界面/通知栏的一句话进度。
     *
     * 只看「第一处还没备完的位置」（那就是这条流此刻推到的地方）：
     * 当前章没备完就报当前章 x/y 段，备完则报后面正在推的那章；
     * 另附「N 章已完成几章」。文案与日志一样连续。
     */
    private fun summarizePreloadState(currentIdx: Int, limitChapter: Int): String {
        // 「后续 N 章」的完成数（不含当前章：当前章是跟着播放走的，不构成目标）
        var laterDone = 0
        for (offset in 1..limitChapter) {
            val idx = currentIdx + offset
            val total = preloadChapterCache[idx]?.segments?.size ?: 0
            val done = preloadChapterProgress[idx] ?: 0
            if (total > 0 && done >= total) laterDone++
        }
        var report: String? = null
        // 11-21 03:4x 旧口径：跨章只管后面整章，报告也从 1 起（当前章归滚动补货）
        for (offset in 1..limitChapter) {
            val idx = currentIdx + offset
            val cached = preloadChapterCache[idx]
            val total = cached?.segments?.size ?: 0
            val done = preloadChapterProgress[idx] ?: 0
            if (total > 0 && done >= total) continue
            report = if (total > 0) {
                val title = cached?.title?.take(10).orEmpty()
                val titlePart = if (title.isNotBlank()) " · $title" else ""
                "预加载 第${idx + 1}章 ${done}/$total 段$titlePart"
            } else {
                "预加载 准备中 · 第${idx + 1}章"
            }
            break
        }
        if (report == null) {
            return if (limitChapter > 0) "预加载完成 · 后续 $laterDone/$limitChapter 章已备"
            else "预加载完成"
        }
        return if (limitChapter > 0) "$report（后续 $laterDone/$limitChapter 章）" else report
    }

    /** 跨章预加载进度文案（给父类通知副标题用；空串=不显示） */
    @Volatile private var preloadStateSnapshot = ""

    override fun preloadStateText(): String = ""

    private fun postPreloadState(text: String) {
        // 11-21 16:5x（用户令「通知栏只用以前长久用的那种，只显示当前章节正在朗读的，
        // 不显示已加载的」）：预加载进度撤出通知栏。prelaodStateText 恒空串 →
        // notificationSubText() 走纯「朗读」分支，通知栏回到 11-20 之前的形态。
        // 预加载进度的可见位置保留两处：UTTS 日志页顶部「合成进度：第N章 n/81」
        // （34888c1 起）与朗读面板行（READ_ALOUD_PRELOAD_STATE 事件照发）。
        val changed = text != preloadStateSnapshot
        preloadStateSnapshot = text
        postEvent(EventBus.READ_ALOUD_PRELOAD_STATE, text)
    }

    /**
     * 为「未来章」构造与主播放**完全同口径**的段表。
     *
     * 为什么必须同口径（这是预加载有没有意义的唯一判据）：UTTS 的合成缓存键 =
     * 音色参数 + **文本**（日志实证：preWrite 与 playRead 同 key 才出现 `cache hit`）。
     * 段文本差一个字、或段号错位（`titleMode==2` 隐藏标题时主播放没有标题段），
     * 预热就打在了另一个键上——播放照样现场合成，钱花了账没记上。
     *
     * 三条路径按优先级：
     *  ① 已排版的本章/下一章（curTextChapter / nextTextChapter）→ 与主播放同一个对象，零分叉；
     *  ② 自己排版（ChapterProvider.getTextChapterAsync）→ 与主播放同一条排版流水线；
     *  ③ 排版不可用（阅读页尺寸没初始化等）才降级正文直切，并打日志标明是降级口径。
     */
    private suspend fun buildPreloadSegments(book: Book, chapter: BookChapter): PreloadedChapter? {
        // 11-22 18:4x：与主播放 contentList 同口径——末尾补章末占位段 " "。
        // 主播放建表处 `.apply { add(" ") }`（BaseReadAloudService ~2032），
        // 预加载此前漏了这一步，导致同章分母差 1（播放 133 / 预加载 132）。
        // 只在这里收口一次，三条 return 路径全部覆盖。
        fun seal(title: String, segs: List<String>): PreloadedChapter =
            PreloadedChapter(title, segs.toMutableList().apply { add(" ") })
        // ① 当前章 / 下一章通常已经排好版（ReadBook 常驻 curTextChapter 与 nextTextChapter），
        // 直接复用它的段表：零排版成本、且与主播放是同一个对象，段文本逐字一致。
        val offset = chapter.index - ReadBook.durChapterIndex
        if (offset == 0 || offset == 1) {
            val tc = ReadBook.textChapter(offset)
            if (tc != null && tc.chapter.index == chapter.index && tc.pages.isNotEmpty()) {
                val segments = tc.getNeedReadAloud(0, false, 0).split("\n").filter { it.isNotEmpty() }
                if (segments.isNotEmpty()) return seal(tc.title, segments)
            }
        }
        val rawContent = BookHelp.getContent(book, chapter) ?: return null
        val contentProcessor = ContentProcessor.get(book)
        val bookContent = contentProcessor.getContent(
            book = book,
            chapter = chapter,
            content = rawContent,
            includeTitle = false
        )
        if (bookContent.textList.isEmpty()) return null
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        // ② 自己排版：与 ReadBook.contentLoadFinish → ChapterProvider 同一条流水线，
        // 段表因此与主播放的 contentList 逐字一致。代价是一次整章排版（IO 线程）。
        if (ChapterProvider.viewWidth > 0 && ChapterProvider.visibleWidth > 0) {
            val laidOut = runCatching {
                val textChapter = ChapterProvider.getTextChapterAsync(
                    lifecycleScope,
                    book,
                    chapter,
                    displayTitle,
                    bookContent,
                    if (ReadBook.simulatedChapterSize > 0) ReadBook.simulatedChapterSize
                    else ReadBook.chapterSize
                )
                // 消费排版通道直到关闭（= 排版完成），否则 pages 还是空的
                for (page in textChapter.layoutChannel) {
                    // 只要它跑完，页对象本身不用
                }
                val segments = textChapter.getNeedReadAloud(0, false, 0)
                    .split("\n").filter { it.isNotEmpty() }
                if (segments.isEmpty()) null else seal(displayTitle, segments)
            }.getOrNull()
            if (laidOut != null) return laidOut
        }
        // ③ 降级口径（有已知分叉：图片/HTML 期替换等）。如实打日志，不假装无事发生。
        AppLog.putDebug("跨章预加载降级取段: 第${chapter.index + 1}章排版不可用，用正文直切")
        val bodySegments = bookContent.toString()
            .replace(Regex("[袮꧁]"), " ")
            .split("\n").filter { it.isNotEmpty() }
        val segments = if (ReadBookConfig.titleMode != 2 || chapter.isVolume) {
            listOf(displayTitle.replace(Regex("[袮꧁]"), " ")) + bodySegments
        } else {
            bodySegments
        }
        return if (segments.isEmpty()) null else seal(displayTitle, segments)
    }

    // ====== 缓存管理 ======

    /**
     * 开始 / 停止朗读时在后台清理过期音频缓存。
     *
     * 两条约束叠加：
     *  1) 保留水位：音频文件不超过 AUDIO_CACHE_KEEP_FLOOR 个就完全不清，
     *     超出也只删多出来的那部分，始终留够水位；
     *  2) 单次限额：一次最多删 AUDIO_CACHE_MAX_PER_RUN 个、最多跑 60 秒，
     *     每 50 个歇 1 秒，避免历史缓存堆积时磁盘 IO 峰值拖慢朗读和 AI 分析。
     * 候选按文件修改时间升序（最旧先删），仍只删超过保留时间、
     * 且不在当前章 / 上一章 / 后续预下载章保护前缀里的文件。
     * 系统 TTS 的静音占位文件沿用原有规则：无论是否过期都删。
     */
    private suspend fun removeCacheFile() {
        val startedAt = System.currentTimeMillis()
        val keepTime = AppConfig.audioCacheCleanTime
        // UTTS 共享 synth_cache 清旧与本地清理**独立进行**（11-16 复核修正）：
        // 本地缓存不足水位时本函数在下方提前 return，历史缓存少的用户永远走不到
        // 函数末尾——共享缓存清旧若挂在末尾就跟着失效（装机验证 G1 会验不出）。
        // 故挂在开头：无论本地清不清、本地目录可不可读，都执行。
        // 它有自己的单次限额与时间预算，不会挤占下面的本地清理。
        cleanUtssSynthCache(keepTime)
        val entries = FileUtils.listDirsAndFiles(ttsFolderPath) ?: return
        // 水位只按真正的音频文件算：.tmp / 子目录都不计入
        val audioFiles = entries.filter {
            it.isFile && (it.name.endsWith(".mp3") || it.name.endsWith(".wav"))
        }
        val total = audioFiles.size
        if (total <= BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR) {
            AppLog.put(
                "音频缓存清理: 当前 $total 个，未超过保留水位 " +
                        "${BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR}，跳过"
            )
            return
        }
        val excess = total - BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR
        val quota = minOf(excess, BaseReadAloudService.AUDIO_CACHE_MAX_PER_RUN)
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        val protectedPrefixes = mutableSetOf<String>()
        val currentTitle = this.textChapter?.chapter?.title ?: ""
        if (currentTitle.isNotEmpty()) {
            protectedPrefixes.add(MD5Utils.md5Encode16(currentTitle.trim()))
        }
        // 开始朗读时 textChapter 可能还没就绪，再按章号补一次当前章
        appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx)?.let {
            protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
        }
        // 保护上一章，避免回退时被清理
        appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx - 1)?.let {
            protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
        }
        for (i in 1..limit) {
            appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i)?.let {
                protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
            }
        }
        var deleted = 0
        var failed = 0
        var stoppedByLimit = false
        // 最旧优先：只删多出来的那部分时，必须保证先淘汰最早的缓存
        val candidates = audioFiles.sortedBy { it.lastModified() }
        for (index in candidates.indices) {
            if (deleted >= quota ||
                System.currentTimeMillis() - startedAt >= BaseReadAloudService.AUDIO_CACHE_MAX_DURATION_MS
            ) {
                stoppedByLimit = true
                break
            }
            val fileItem = candidates[index]
            val fName = fileItem.name
            // 文件名固定以 16 位章标题 MD5 开头，直接查集合即可
            val isProtected = fName.take(16) in protectedPrefixes
            val isSilentSound = fileItem.length() == BaseReadAloudService.SILENT_SOUND_SIZE
            val shouldDelete = isSilentSound ||
                    (!isProtected && System.currentTimeMillis() - fileItem.lastModified() > keepTime)
            if (!shouldDelete) continue
            val ok = runCatching { FileUtils.delete(fileItem.absolutePath) }.getOrDefault(false)
            if (!ok) {
                failed++
                continue
            }
            deleted++
            // 每批歇 1 秒；最后一项和已达配额时不再白等
            if (deleted % BaseReadAloudService.AUDIO_CACHE_BATCH_SIZE == 0 &&
                deleted < quota && index < candidates.lastIndex
            ) {
                delay(BaseReadAloudService.AUDIO_CACHE_BATCH_DELAY_MS)
            }
        }
        val tail = if (stoppedByLimit) "，已达单次限额或时间预算，剩余下次继续" else "，本轮扫描完毕"
        AppLog.put(
            "音频缓存清理: 共 $total 个、超出水位 $excess 个，" +
                    "本次删除 $deleted 个、失败 $failed 个，" +
                    "耗时 ${System.currentTimeMillis() - startedAt}ms$tail"
        )
    }

    /**
     * 自动清理 UTTS 共享预合成缓存（/storage/emulated/0/Download/chajian/uyue-utts/synth-cache 的 .pcm）。
     *
     * 由 [removeCacheFile] **开头**调用（11-16 复核修正：挂末尾会随本地水位提前
     * return 而失效）；只有系统 TTS 链路调用——synth_cache 只由 UTTS 引擎产生，
     * HTTP 朗读服务不经过它。
     *
     * 与本地缓存清理的差异（为什么不同，两套口径都有意为之）：
     *  - 本地缓存按「水位 + 保留时间」清：水位防抖动，避免刚合成的段被马上清掉又重合成；
     *  - synth_cache 的缓存键是「文本+音色+参数」，没有章节概念，播放永远走本地缓存，
     *    对它没有任何依赖，旧 .pcm 只占磁盘——过了保留时间直接删，不吃水位；
     *  - 没有章节保护前缀可查（键里没有章节），也不存在静音占位文件。
     * 纪律与本地清理一致：最旧先删、单次限额、时间预算、每 50 个歇 1 秒，
     * 绝不因清理拖慢朗读；只删 .pcm（UTTS 缓存键文件），不碰子目录与其它文件。
     * 手动「清除全部缓存音频」走 [AppConfig.notifyUtssClearSynthCache] 整池清，与本函数无关。
     */
    private suspend fun cleanUtssSynthCache(keepTime: Long) {
        val startedAt = System.currentTimeMillis()
        val entries = AppConfig.listUtssSynthCacheEntries() ?: return
        val pcmFiles = entries.filter { it.isFile && it.name.endsWith(".pcm") }
        if (pcmFiles.isEmpty()) return
        var deleted = 0
        var failed = 0
        var stoppedByLimit = false
        // 最旧优先。按修改时间升序排好后，一旦遇到未过期的文件即可整段收工：
        // 它后面的只会更新，不存在「后面的反而更旧」的情况（本地清理不能这样断，
        // 是因为那里还有保护前缀和静音占位两类与时间无关的删除条件）。
        val candidates = pcmFiles.sortedBy { it.lastModified() }
        for (index in candidates.indices) {
            val fileItem = candidates[index]
            if (System.currentTimeMillis() - fileItem.lastModified() <= keepTime) break
            if (deleted >= BaseReadAloudService.AUDIO_CACHE_MAX_PER_RUN ||
                System.currentTimeMillis() - startedAt >= BaseReadAloudService.AUDIO_CACHE_MAX_DURATION_MS
            ) {
                stoppedByLimit = true
                break
            }
            val ok = runCatching { FileUtils.delete(fileItem.absolutePath) }.getOrDefault(false)
            if (ok) deleted++ else failed++
            if (deleted % BaseReadAloudService.AUDIO_CACHE_BATCH_SIZE == 0 &&
                deleted < BaseReadAloudService.AUDIO_CACHE_MAX_PER_RUN &&
                index < candidates.lastIndex
            ) {
                delay(BaseReadAloudService.AUDIO_CACHE_BATCH_DELAY_MS)
            }
        }
        if (deleted == 0 && failed == 0 && !stoppedByLimit) return
        val tail = if (stoppedByLimit) "，已达单次限额或时间预算，剩余下次继续" else "，本轮扫描完毕"
        AppLog.put(
            "UTTS共享缓存清理: 共 ${pcmFiles.size} 个 .pcm，" +
                    "本次删除 $deleted 个、失败 $failed 个，" +
                    "耗时 ${System.currentTimeMillis() - startedAt}ms$tail"
        )
    }

    private fun deleteCurrentSpeakFile() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    // ====== 工具方法 ======

    private fun getSpeakText(index: Int): String {
        val rawText = getRawText(index)
        // 快速路径：后续段落的缓存已准备好时，跳过脚本执行，避免阻塞播放流程
        if (index > nowSpeak) {
            val chapterTitle = textChapter?.chapter?.title ?: ""
            val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
            if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
                return rawText
            }
        }
        return applyTtsScripts(rawText, paragraphIndex = index)
    }

    private fun getFileNameHelper(title: String, content: String, index: Int = -1): String {
        // 11-21：算法收敛到 TtsCacheFileName（唯一权威实现），此处只剩转发。
        // voice 与 getCacheFileForText 同口径：引擎实例的 voice.name 优先。
        return TtsCacheFileName.sysTts(
            title = title,
            content = content,
            index = index,
            engine = ReadAloud.ttsEngine,
            voice = textToSpeech?.voice?.name,
        )
    }

    /**
     * gengxin 触发处理：删除所有系统TTS缓存（.wav）。
     */
    /**
     * 删除指定段之后的系统TTS缓存（.wav）。
     * @param fromIndex 从此 index 之后（含 fromIndex+1）开始删除；-1 删除全部。
     */
    override fun deleteGengxinCachesAfter(fromIndex: Int) {
        if (fromIndex < 0) {
            // 未朗读：删除全部
            var deleted = 0
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
                if (fileItem.isFile) {
                    FileUtils.delete(fileItem.absolutePath)
                    deleted++
                }
            }
            AppLog.put("gengxin: 已删除所有 系统TTS 缓存，共 $deleted 个文件")
            return
        }
        // 正在朗读：以当前朗读音频的生成时间为基准，删除所有生成时间晚于它的缓存（跨章节）
        val title = textChapter?.chapter?.title ?: textChapter?.title ?: return
        if (fromIndex > contentList.lastIndex) {
            AppLog.put("gengxin: fromIndex 越界 (fromIndex=$fromIndex)")
            return
        }
        val currentFn = getFileNameHelper(title, contentList[fromIndex], fromIndex)
        val currentWav = File("${ttsFolderPath}$currentFn.wav")
        val baseTime = if (currentWav.exists()) currentWav.lastModified() else 0L
        if (baseTime == 0L) {
            // 兜底：取不到基准时间时，退回按 index 删本章后续
            val deleteFrom = fromIndex + 1
            if (deleteFrom > contentList.lastIndex) {
                AppLog.put("gengxin: 无后续段落需删除 (fromIndex=$fromIndex)")
                return
            }
            var deleted = 0
            for (i in deleteFrom..contentList.lastIndex) {
                val fn = getFileNameHelper(title, contentList[i], i)
                val wav = File("${ttsFolderPath}$fn.wav")
                if (wav.exists()) { wav.delete(); deleted++ }
            }
            AppLog.put("gengxin: 基准时间缺失，已删除本章段落 $deleteFrom..${contentList.lastIndex} 的 系统TTS 缓存，共 $deleted 个文件")
            return
        }
        // 按生成时间删除：遍历整个缓存目录，删除所有 lastModified() 晚于基准时间的文件
        // 当前正在朗读的音频通过文件名排除，确保不被误删
        var deleted = 0
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
            if (!fileItem.isFile) return@forEach
            if (fileItem.name == "$currentFn.wav") return@forEach
            if (fileItem.lastModified() > baseTime) {
                FileUtils.delete(fileItem.absolutePath)
                deleted++
            }
        }
        AppLog.put("gengxin: 已按时间删除 baseTime=$baseTime 之后的 系统TTS 缓存，共 $deleted 个文件")
    }

    private fun getCacheFileForText(text: String, index: Int = -1, chapterTitle: String = ""): File {
        // 11-21：算法收敛到 TtsCacheFileName（唯一权威实现），此处只剩转发。
        // voice 取引擎实例的 voice.name 优先（写入时引擎已初始化）；
        // 阅读页扫描侧拿不到引擎实例、回落设置项——正常情况两者一致。
        val name = TtsCacheFileName.sysTts(
            title = chapterTitle,
            content = text,
            index = index,
        )
        return File(ttsFolderPath, "$name.wav")
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.wav")
    }

    private fun isSilentFile(fileName: String): Boolean {
        val file = File("${ttsFolderPath}$fileName.wav")
        if (!file.exists()) return false
        return file.length() == BaseReadAloudService.SILENT_SOUND_SIZE.toLong()
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.wav")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.wav")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(BaseReadAloudService.generateSilentWavBytes(50))
    }

    /**
     * 合成失败时的静音占位文件名。
     *
     * 必须与正式缓存**文件名不同**：正式缓存键由 [getCacheFileForText] 生成，
     * 一旦把静音写进同一个名字，这段文字以后每次播放都会「缓存命中」到静音——
     * 重读、重进章节、清了重下都救不回来（11-13 真机实锤：index=1/29 已写坏）。
     *
     * 这里加 `_silent` 后缀，与正式文件的纯 MD5 名字不冲突：
     * 播放队列照常用它占位（不会卡进度），但下一轮合成仍判定「无正式缓存」并重新请求引擎。
     */
    private fun silentPlaceholderName(rawText: String, chapterTitle: String, index: Int): String {
        val name = getFileNameHelper(chapterTitle, rawText, index) + "_silent"
        val file = File("${ttsFolderPath}$name.wav")
        if (!file.exists()) createSilentSound(name)
        return name
    }

    private fun purifySpeakText(text: String): String {
        return text
            .replace(Regex("<img[^>]*>"), "")
            .replace(Regex("<[a-zA-Z][^>]*>|</[a-zA-Z][^>]*>"), "")
            .replace(Regex("&[a-zA-Z#0-9]+;"), "")
            .replace(Regex("[袮꧁]"), "")
    }

    // ====== 音频验证 ======

    private fun isValidAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 44) return false
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            retriever.release()
            // 宽松校验：只要 MediaMetadataRetriever 不抛异常即视为有效音频。
            // 很多 TTS 引擎合成的音频 ExoPlayer 能正常播放，但 Retriever 读不出时长，
            // 或不是标准 WAV 头，之前这种音频会被误判为无效而重新合成。
            true
        } catch (e: Exception) {
            // Retriever 抛异常也不直接判无效，只要文件大小合理就放行
            true
        }
    }

    /**
     * 严格校验：用于「把上次中断的 .tmp 收编成正式缓存」这种**不可逆**的决定。
     *
     * 与 [isValidAudioFile] 的区别：后者是**宽松**的（Retriever 抛异常也放行），
     * 因为对「已存在的正式缓存」宁可放行也别白花一次 API。但把它用在收编半截
     * 临时文件上就出事了——真机 11-14 实锤（20:55:20.154 → 20:55:20.834）：
     *   20:55:20.154 TTS收编上次中断的临时音频: f8837cf519e16e0c_c9f9f119b9ea38fe.wav
     *   20:55:20.781 TTS缓存命中: 同一文件
     *   20:55:20.834 TTS播放错误 UnrecognizedInputFormatException:
     *                None of the available extractors ... could be found
     * 即：**半截的 .tmp 被宽松校验放过 → 扶正成正式缓存 → ExoPlayer 解析不了**。
     * 更麻烦的是它现在成了「有效缓存」，后续每次都命中、每次都报错，
     * 只能靠用户清缓存恢复。
     *
     * 故收编前要求：Retriever 能读出**正时长**。读不出就是半截/无头文件，宁可删掉
     * 重新合成（多花一次 API），也不能污染缓存。
     */
    private fun isVerifiedAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 44) return false
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            retriever.release()
            duration != null && duration > 0
        } catch (e: Exception) {
            false
        }
    }

    private fun parseWavDurationMs(file: File): Long? {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(44)
                if (stream.read(header) < 44) return null
                if (!(header[0] == 'R'.toByte() && header[1] == 'I'.toByte() &&
                            header[2] == 'F'.toByte() && header[3] == 'F'.toByte() &&
                            header[8] == 'W'.toByte() && header[9] == 'A'.toByte() &&
                            header[10] == 'V'.toByte() && header[11] == 'E'.toByte())) {
                    return null
                }
                val sampleRate = java.nio.ByteBuffer.wrap(header, 24, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val channels = java.nio.ByteBuffer.wrap(header, 22, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bitsPerSample = java.nio.ByteBuffer.wrap(header, 34, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
                val byteRate = sampleRate * channels * bitsPerSample / 8
                if (byteRate <= 0) return null
                val dataSize = file.length() - 44
                (dataSize * 1000L / byteRate)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ====== 段落跳转 ======

    private fun seekToParagraphByProgress(progress: Int) {
        val tc = textChapter ?: return
        if (contentList.isEmpty()) return
        val totalChars = tc.getContent().length.coerceAtLeast(1)
        val targetPos = (totalChars * progress / 1000f).toInt()

        var acc = 0
        var targetParagraph = 0
        for (i in contentList.indices) {
            val len = contentList[i].length + 1
            if (acc + len > targetPos) {
                targetParagraph = i
                break
            }
            acc += len
        }

        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        playErrorNo = 0

        nowSpeak = targetParagraph
        paragraphStartPos = 0

        readAloudNumber = 0
        for (i in 0 until targetParagraph) {
            readAloudNumber += contentList[i].length + 1
        }

        pageIndex = tc.getPageIndexByCharIndex(readAloudNumber)

        exoPlayer.stop()
        play()
    }

    // ====== 段落时长估算 ======

    override fun estimateParagraphDuration(index: Int): Long {
        if (index < 0 || index >= contentList.size) return 0L
        var text = contentList[index]
        if (text.isEmpty()) return 0L

        if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
            text = text.substring(paragraphStartPos)
        }
        if (text.isEmpty()) return 0L

        val fileName = getFileNameHelper(textChapter?.chapter?.title ?: "", text, index)
        val file = getSpeakFileAsMd5(fileName)
        if (file.exists() && file.length() > 0) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                if (duration != null && duration > 0) return duration
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }

        val rate = AppConfig.speechRatePlay + 5
        val msPerChar = (1250f / rate).toLong()
        return text.length * msPerChar
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }
}
