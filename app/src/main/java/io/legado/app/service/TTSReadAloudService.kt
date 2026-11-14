package io.legado.app.service

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
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
import io.legado.app.constant.AppConst
import io.legado.app.help.audio.GainAudioProcessor
import io.legado.app.constant.AppLog
import io.legado.app.model.VnParagraph
import io.legado.app.model.VnSubtitlePos
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    /** 主合成协程 */
    private var synthesisTask: Job? = null
    /** 跨章预加载协程 */
    private var preloadJob: Job? = null

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
        // 取消自动清理：停止朗读时不应删除已缓存的音频文件
        // Coroutine.async { removeCacheFile() }
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
        }

        override fun onDone(s: String?) {
            LogUtils.d(TAG, "onDone synthesis utteranceId:$s")
            // 【诊断日志】回调到达（10-12 加，只记录不改逻辑）
            AppLog.putDebug("TTS回调 onDone id=$s")
            s ?: return
            val targetFile = synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            if (completer != null) {
                if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                    completer.complete(targetFile)
                } else {
                    completer.complete(null)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(TAG, "onError utteranceId:$utteranceId errorCode:$errorCode")
            // 【诊断日志】回调到达（10-12 加，只记录不改逻辑）
            AppLog.putDebug("TTS回调 onError id=$utteranceId code=$errorCode")
            // 11-13 巡检实锤：framework 会先回调新签名 onError(utteranceId, errorCode)
            // 再回调旧签名 onError(s)（同一事件两次），日志每条 -3 出现两行。
            // 这里登记已处理 id，旧重载据此幂等跳过（completer 只 complete 一次）。
            if (utteranceId != null) handledErrorCallbacks.add(utteranceId)
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
    ): File? {
        val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
        if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
            return cacheFile
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
        // 11-14：合成前沿上报（UTTS 进度行「多合几段/正在合第几段」的数据源）。
        // 每提交一笔就刷新前沿；幂等由 ReadProgressFile 内部处理。失败静默。
        io.legado.app.help.ReadProgressFile.reportSynthFront(
            synthChapterIdx ?: ReadBook.durChapterIndex,
            index,
            chapterTitle,
            synthTotalSegs ?: contentList.size,
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
                    doSynthesizeText(text, utteranceId, index, chapterTitle, rawText)
                } catch (e: CancellationException) {
                    // 11-14（方案 A）：取消不立即销账——留一个冷却记录，让紧接着的
                    // 同 key 调用方先复查缓存再决定，避免「掐掉→重提→再掐」空转。
                    // 只登记时间，不保留 CompletableDeferred（那个已完成，留着会误导等待方）。
                    canceledAt[cacheKey] = System.currentTimeMillis()
                    inFlightSyntheses.remove(cacheKey)
                    mine.completeExceptionally(e)
                    throw e
                } catch (e: Exception) {
                    AppLog.put("TTS合成异常\n${e.localizedMessage}", e)
                    null
                }
                inFlightSyntheses.remove(cacheKey)
                // 正常出结果（成功或失败）就清掉冷却，免得 1.2 秒内重播被误挡
                canceledAt.remove(cacheKey)
                mine.complete(result)
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
        rawText: String = text
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
            val reusableTmp = FileUtils.listDirsAndFiles(ttsFolderPath)?.firstOrNull {
                it.isFile && it.name.startsWith(cacheFile.name) && it.name.endsWith(".tmp") &&
                    isValidAudioFile(it)
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
                    putInt("para_total", contentList.size)
                }
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
                    withTimeout(AppConfig.sysTtsSynthesizeTimeout * 1000L) { completer.await() }
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
            if (result != null) return result

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

    private suspend fun synthesizeSingle(index: Int): File? {
        val rawText = getRawText(index)
        val text = applyTtsScripts(rawText, paragraphIndex = index)
        if (text.matches(AppPattern.notReadAloudRegex)) {
            val fileName = getFileNameHelper(textChapter?.chapter?.title ?: "", rawText, index)
            if (!hasSpeakFile(fileName)) createSilentSound(fileName)
            return getSpeakFileAsMd5(fileName)
        }
        val utteranceId = "${AppConst.APP_TAG}$index"
        val chapterTitle = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        return synthesizeText(text, utteranceId, index, chapterTitle, rawText) ?: run {
            if (!AppConfig.ttsRetrySkipOnFail) {
                throw NoStackTraceException(
                    "TTS合成失败，超过最大重试次数${AppConfig.ttsRetryCount}次，文本：$text"
                )
            }
            val fileName = silentPlaceholderName(rawText, chapterTitle, index)
            getSpeakFileAsMd5(fileName)
        }
    }

    // ====== 播放调度（复制/适配转发器）======

    @Synchronized
    override fun play(affectBgm: Boolean) {
        // 11-13 诊断（配 nextP 那行一起看，定位「连点同段重复提交」）：
        // 这行能回答「play 进来时 nowSpeak 是多少、引擎是否就绪、是否走了早退」。
        LogUtils.d(
            TAG,
            "play: nowSpeak=$nowSpeak ttsInitFinish=$ttsInitFinish pendingPlay=$pendingPlay " +
                "contentSize=${contentList.size}"
        )
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
        // 取消自动清理：开始朗读时不应删除已缓存的音频文件
        // lifecycleScope.launch(Dispatchers.IO) {
        //     removeCacheFile()
        // }

        synthesisTask?.cancel()
        playIndexJob?.cancel()
        // 取消预加载协程，不再继续提交新请求。
        // 已提交到TTS引擎的请求继续完成并写入缓存，
        // 新章节合成时检查缓存命中则直接复用。
        preloadJob?.cancel()
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
        synthesisTask = lifecycleScope.launch(Dispatchers.IO) {
            var needPreload = false
            for (i in nowSpeak until endIndex) {
                ensureActive()

                val rawText = getRawText(i)
                val speakText = applyTtsScripts(rawText, paragraphIndex = i)
                val isEndMarker = i == contentList.lastIndex
                val file = if (isEndMarker || speakText.matches(AppPattern.notReadAloudRegex)) {
                    if (isEndMarker) {
                        AppLog.putDebug("章节末尾静音占位符，使用静音文件")
                    }
                    val fileName = getFileNameHelper(chapterTitle, rawText, i)
                    if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                    getSpeakFileAsMd5(fileName)
                } else {
                    val cacheFile = getCacheFileForText(rawText, i, chapterTitle)
                    if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
                        AppLog.putDebug("TTS缓存命中: ${cacheFile.name}")
                        cacheFile
                    } else {
                        try {
                            synthesizeSingle(i) ?: run {
                                AppLog.put("TTS合成失败，使用静音文件占位: index=$i")
                                // 占位走独立文件名，绝不覆盖正式缓存，下次仍会重新合成
                                getSpeakFileAsMd5(silentPlaceholderName(rawText, chapterTitle, i))
                            }
                        } catch (e: Exception) {
                            // 11-13：主动停止/翻页/切章导致的协程取消**不是合成失败**，
                            // 绝不能写静音占位——此前统一走 withDefault 的静音兜底，
                            // 真机日志实测「用户点停止 → onDestroy 取消 → 写静音占位」，
                            // 那几段就永久哑了（占位文件写进正式缓存路径，重读也命中静音）。
                            if (e is CancellationException) throw e
                            AppLog.put("TTS合成异常，使用静音文件占位: index=$i, ${e.message}")
                            // 占位走独立文件名，绝不覆盖正式缓存，下次仍会重新合成
                            getSpeakFileAsMd5(silentPlaceholderName(rawText, chapterTitle, i))
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

                if (!singleParagraphMode && contentList.size - i <= PRELOAD_TRIGGER_REMAIN) {
                    needPreload = true
                }
            }
            // 当前章节全部合成并加入播放列表后，再启动预加载，
            // 避免与当前章节末尾争夺同一个 TTS 实例的 FIFO 队列。
            if (needPreload) {
                preloadNextChapter()
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
        preloadJob?.cancel()
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

    // ====== 状态推进 ======

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
        } else {
            nextChapter()
            return
        }
    }

    override fun nextChapter() {
        val currentBook = ReadBook.book
        val currentChapter = textChapter?.chapter
        val currentChapterIndex = ReadBook.durChapterIndex

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
            // 下一章若还在自动分析中，直接停掉：分析完再写回会把刚落盘的内容弄乱
            ReadBook.book?.let { AiChapterFixService.cancelAutoJob(it.bookUrl) }
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
                            ReadBook.durChapterIndex, nowSpeak, contentList.size,
                            contentList.getOrNull(nowSpeak).orEmpty()
                        )
                        lastNowSpeak = nowSpeak
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

    // ====== 跨章节预加载 ======

    private fun preloadNextChapter() {
        if (preloadJob?.isActive == true) return
        preloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val book = ReadBook.book ?: return@launch
            val currentIdx = ReadBook.durChapterIndex
            val limitChapter = AppConfig.audioPreDownloadNum

            for (offset in 1..limitChapter) {
                ensureActive()
                val nextIdx = currentIdx + offset
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, nextIdx)
                    ?: continue

                // 优先使用 ReadBook 已排版的 TextChapter 生成段列表，和主播放来源一致
                val textChapter = ReadBook.textChapter(offset)
                val segments: List<String>
                val segmentTitle: String
                if (textChapter != null && textChapter.pages.isNotEmpty()) {
                    // 11-13：主播放 contentList 已恒按整章段落切（BaseReadAloudService），
                    // 预加载必须同口径——否则缓存键 index 分叉，预加载白合成
                    segments = textChapter.getNeedReadAloud(0, false, 0)
                        .split("\n")
                        .filter { it.isNotEmpty() }
                    segmentTitle = textChapter.title
                } else {
                    val rawContent = BookHelp.getContent(book, chapter) ?: continue
                    val contentProcessor = ContentProcessor.get(book)
                    // includeTitle=true 与 TextChapter.getNeedReadAloud() 输出保持一致：
                    // TextChapterLayout 排版时会把标题也写入 TextPage.text，
                    // 所以 getNeedReadAloud().split("\n") 的第一个元素就是标题。
                    val bookContent = contentProcessor.getContent(
                        book = book,
                        chapter = chapter,
                        content = rawContent,
                        includeTitle = true,
                        useReplace = AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
                        chineseConvert = AppConfig.chineseConverterType != 0,
                        fixQuote = book.getFixQuote(),
                        reSegment = book.getReSegment()
                    )
                    // 同步 TextChapter.getNeedReadAloud() 中对 [袮꧁] 的替换，确保缓存命中
                    segments = bookContent.toString()
                        .replace(Regex("[袮꧁]"), " ")
                        .split("\n")
                        .filter { it.isNotEmpty() }
                    // 11-13 修缓存键分叉：标题必须走与主播放完全同一公式。
                    // 主播放的 textChapter.title 来自 ChapterProvider 构造时的
                    // getDisplayTitle(getTitleReplaceRules(), useReplaceRule)（ReadBook.kt:844），
                    // 原始 chapter.title 未过标题替换规则/繁简转换——开了标题替换规则的书，
                    // 两条路径 md5 不同 → 预加载白合成、主播放再付一次（真金白银的 API 浪费）。
                    // contentProcessor 本分支已取得，直接同口径调用。
                    segmentTitle = chapter.getDisplayTitle(
                        contentProcessor.getTitleReplaceRules(),
                        book.getUseReplaceRule()
                    )
                }

                if (segments.isEmpty()) continue

                for (i in segments.indices) {
                    ensureActive()
                    val rawText = segments[i]
                    val text = applyTtsScripts(
                        rawText,
                        paragraphIndex = i,
                        chapterTitle = segmentTitle,
                        chapterParagraphs = segments
                    )
                    if (text.matches(AppPattern.notReadAloudRegex)) continue

                    val cacheFile = getCacheFileForText(rawText, i, segmentTitle)
                    if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) continue

                    val utteranceId = "PRELOAD_${AppConst.APP_TAG}_${System.currentTimeMillis()}_${offset}_$i"
                    // 11-14：预加载跨章，显式报自己的章序/总段数——不传的话
                    // 上报会退回播放章的值，进度行「第六章 3/25」会张冠李戴。
                    synthesizeText(
                        text, utteranceId, i, segmentTitle, rawText,
                        synthChapterIdx = currentIdx + offset,
                        synthTotalSegs = segments.size,
                    )
                }
            }
        }
    }

    // ====== 缓存管理 ======

    /**
     * 移除缓存文件
     * 如果时间设置为0，则不再保护当前章节，退出即全删。
     * 保护当前章节及后续 audioPreDownloadNum 章的预加载缓存，避免已合成但未播放的音频被误删。
     */
    private fun removeCacheFile() {
        val keepTime = AppConfig.audioCacheCleanTime
        if (keepTime == 0L) {
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
            return
        }

        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        val protectedPrefixes = mutableSetOf<String>()
        val currentTitle = this.textChapter?.chapter?.title ?: ""
        if (currentTitle.isNotEmpty()) {
            protectedPrefixes.add(MD5Utils.md5Encode16(currentTitle.trim()))
        }
        runBlocking {
            // 保护上一章，避免回退时被清理
            val prevChapter = appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx - 1)
            if (prevChapter != null) {
                protectedPrefixes.add(MD5Utils.md5Encode16(prevChapter.title.trim()))
            }
            for (i in 1..limit) {
                val nextChapter = appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i)
                if (nextChapter != null) {
                    protectedPrefixes.add(MD5Utils.md5Encode16(nextChapter.title.trim()))
                }
            }
        }

        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
            val isSilentSound = fileItem.length() == BaseReadAloudService.SILENT_SOUND_SIZE.toLong()
            val isProtected = protectedPrefixes.any { fileItem.name.startsWith(it) }
            val shouldDelete = if (isProtected) {
                false
            } else {
                (System.currentTimeMillis() - fileItem.lastModified() > keepTime)
            }
            if (shouldDelete || isSilentSound) {
                FileUtils.delete(fileItem.absolutePath)
            }
        }
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
        val t = title.trim()
        val c = content.trim()
        val engine = ReadAloud.ttsEngine ?: "default"
        val voice = textToSpeech?.voice?.name ?: AppConfig.sysTtsVoiceName ?: "default"
        val rate = (AppConfig.ttsSpeechRate + 5) / 10f
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$engine|$voice|$rate$indexPart|$c")
        val titlePart = if (t.isNotEmpty()) MD5Utils.md5Encode16(t) + "_" else ""
        return "$titlePart$key"
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
        val engine = ReadAloud.ttsEngine ?: "default"
        val rate = (AppConfig.ttsSpeechRate + 5) / 10f
        val voice = textToSpeech?.voice?.name ?: AppConfig.sysTtsVoiceName ?: "default"
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$engine|$voice|$rate$indexPart|$text")
        val titlePart = if (chapterTitle.isNotEmpty()) MD5Utils.md5Encode16(chapterTitle.trim()) + "_" else ""
        return File(ttsFolderPath, "$titlePart$key.wav")
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
