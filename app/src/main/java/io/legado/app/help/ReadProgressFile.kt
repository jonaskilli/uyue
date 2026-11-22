package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.model.ReadBook
import org.json.JSONObject
import java.io.File

/**
 * 朗读进度落盘（11-14 新增），供 UTTS 日志页做「正在朗读」定位与合成进度。
 *
 * ## 为什么要有这个文件
 * UTTS 的日志是**按请求顺序**写的，而请求由预取提前发出，所以日志顺序与播放进度
 * 天然错位——用户在日志页看到的「最新一条」经常不是正在念的那句。
 * 两边唯一的共享面是 `chajian/mingwuyan/`（data.json 已经这么走了），
 * 就在同一个目录里加一份小文件，UTTS 轮询读即可，不需要权限、不需要常驻、不改通知。
 *
 * ## 播放与合成是两个位置
 * 预取可能跑到**后一两章**：播放停在第五章第 39 段时，合成可能已经在合第七章第 3 段。
 * 所以这份文件里播放(playing)与合成(synthesizing)是两组独立字段，绝不混用——
 * 「正在朗读」定位用 playing 组；合成进度显示用 synthesizing 组。
 * 播放组由本文件负责写；合成组由 UTTS 自己记（它知道在合什么），这里不代写。
 *
 * ## 为什么只写数字不写文字
 * 章名会随播放推进而变，写死文本到文件里，UTTS 渲染时就已经过时了。
 * 这里只落 index，显示层的「第N章」由读的人现拼——与墨听 PanelLog 的章节锚同思路。
 *
 * ## 写入频率
 * 只在**段落切换**时写一次（不是定时写），一段通常 3~5 秒，开销可以忽略。
 * 失败一律静默：这是辅助信息，绝不能因为它出错而影响朗读。
 */
object ReadProgressFile {

    const val FILE_NAME = "readProgress.json"

    /**
     * 11-14 晚：读改写 readProgress.json 的互斥锁。
     *
     * 真机实锤（20:51:16）：`合成前沿写入失败: End of input at character 0 of` ——
     * [report]（播放段上报，字幕同步循环每 200ms 一次）与 [reportSynthFront]
     * （合成前沿上报，每提交一笔）**并发**做「readText → JSONObject → writeText」：
     * A 刚把文件截成 0 字节正在写，B 的 readText 读到空串 ⇒ JSONObject("") 抛
     * "End of input at character 0"。写失败那方的前沿就丢了，UTTS 进度行会卡在
     * 旧值（表现为「合成进度不动」）。
     * 两个函数都是纯内存计算+一次小文件写，加锁开销可忽略。
     */
    private val ioLock = Any()

    /** 读的人可以用它判断数据新鲜度：超过这个时间视为没有在朗读 */
    const val STALE_MS = 15_000L

    @Volatile
    private var lastBook = ""
    @Volatile
    private var lastChapterIdx = -1
    @Volatile
    private var lastSeg = -1

    /**
     * 段落开始朗读时调用。
     *
     * @param chapterIndex 本书内的章序（ReadBook.durChapterIndex）
     * @param segIndex     当前朗读段在**本章**contentList 里的下标
     * @param totalSegs    本章总段数
     * @param segText      当前段正文（内部截 60 字）：UTTS 日志行没有段号字段，
     *                     靠章名+正文前缀匹配定位「正在朗读」那张卡
     */
    fun report(
        chapterIndex: Int,
        segIndex: Int,
        totalSegs: Int,
        segText: String,
        // ===== 11-22 15:2x：播放推进时同步刷新缓冲段数 =====
        //
        // ## 为什么必须在这里补
        // `bufferedAheadSegs` 原本只在**合成**时写入（reportSynthFront）。
        // 但缓冲数的基准是「正在播放的段」（用户要求的口径），播放推进后
        // 基准变了、数字却没跟着变——听几段之后数字会一直卡在旧值上，
        // 而 UTTS 侧是拿这个旧数字往**新的播放位置**上画的，越听越偏。
        // 长段播放时更明显：只靠 15s 心跳 `touch()` 续命，数字整段纹丝不动。
        //
        // ## 口径
        // 与 reportSynthFront 完全同一函数、同一基准（nowSpeak + 播放章标题），
        // 两条路写同一个字段不会打架：谁最后写谁生效，但两者算的是同一个值。
        // 传 -1 = 本笔没算（调用方没提供），保持旧值不动。
        bufferedAhead: Int = -1,
    ) {
        // 同一段重复上报直接跳过：字幕同步循环 200ms 一跳，不挡会产生大量无谓写盘
        val book = ReadBook.book?.name.orEmpty()
        if (book == lastBook && chapterIndex == lastChapterIdx && segIndex == lastSeg) return
        lastBook = book
        lastChapterIdx = chapterIndex
        lastSeg = segIndex
        runCatching {
            val dir = File(EngineDataDir.baseDir())
            if (!dir.exists()) dir.mkdirs()
            synchronized(ioLock) {
                val f = File(dir, FILE_NAME)
                // 11-15 修：**读改写**，不再新建空对象覆盖。
                //
                // 旧写法 `JSONObject().apply{...}` 只写播放组字段，等于把合成前沿
                // （synthChapterIndex/synthSegIndex/synthChapterTitle/synthTotalSegs）
                // 整片抹掉——段一切换，UTTS 日志页的「合成进度」行就变空，要等下一笔
                // 合成上报才回来。真机表现＝进度行时有时无（用户 11-15 截图实报）。
                // 现沿用文件里已有的合成字段，只覆盖播放组。
                val json = if (f.exists()) {
                    runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
                } else JSONObject()
                json.put("book", book)
                json.put("chapterTitle", ReadBook.curTextChapter?.title.orEmpty())
                // 播放组：UTTS 用它定位「正在朗读」那条卡
                json.put("playing", true)
                json.put("chapterIndex", chapterIndex)
                json.put("segIndex", segIndex)
                json.put("totalSegs", totalSegs)
                // 正文前缀（截 60 字）：日志行没有段号字段，「哪张卡是正在朗读」
                // 靠章名+正文前缀匹配——正文是两边唯一的公共键（请求音频行 <b>正文</b>=原文）
                json.put("segText", segText.take(60))
                // 11-22 15:2x：段切换时同步刷新缓冲数（-1=没算，保持旧值）
                if (bufferedAhead >= 0) json.put("bufferedAheadSegs", bufferedAhead)
                json.put("at", System.currentTimeMillis())
                f.writeText(json.toString())
            }
        }.onFailure {
            // 辅助信息，失败不打扰用户，只在 AppLog 留痕
            AppLog.putDebug("朗读进度写入失败: ${it.localizedMessage}")
        }
    }

    /**
     * 11-22 21:5x（用户实报「正在播第 8 段、第 12 段已经合成完，缓冲却写 3 段」）：
     * **只刷新缓冲段数**（外加 `at`），不动播放组/合成组任何字段。
     *
     * ## 为什么需要它
     * `bufferedAheadSegs` 有三处写入：合成提交（[reportSynthFront]）、预加载提交
     * （同）、段切换（[report]）。**合成"完成"这一刻没人写**——于是第 12 段落盘了，
     * 数字还停在上一笔提交时的 3，要等下一笔提交（约 2~4 秒后）才跳成 4。
     * 用户看到的就是「明明已经合成好了，缓冲数不涨」。
     *
     * ## 与 [touch] 的区别
     * touch 只续 `at`，不重算缓冲（字幕同步循环 200ms 一跳，重算太贵）；
     * 本函数由**合成完成回调**调用（2~4 秒一次），自带重算，只写这一个字段。
     *
     * @param bufferedAhead -1 = 本次没算（调用方未提供），直接跳过
     */
    fun reportBufferedAhead(bufferedAhead: Int) {
        if (bufferedAhead < 0) return
        runCatching {
            val dir = File(EngineDataDir.baseDir())
            if (!dir.exists()) return@runCatching
            synchronized(ioLock) {
                val f = File(dir, FILE_NAME)
                if (!f.exists()) return@runCatching
                val json = runCatching { JSONObject(f.readText()) }
                    .getOrElse { return@runCatching }
                json.put("bufferedAheadSegs", bufferedAhead)
                json.put("at", System.currentTimeMillis())
                f.writeText(json.toString())
            }
        }.onFailure {
            AppLog.putDebug("缓冲数刷新写入失败: ${it.localizedMessage}")
        }
    }

    /**
     * 心跳：只刷新时间戳 `at`，不动段号/正文/合成字段。
     *
     * 为什么需要它（11-15 用户实报「有时候正在朗读日志不显示」）：
     * [report] 只在**段切换**时写一次；读的人（UTTS 日志页）判新鲜度用的是
     * `now - at <= STALE_MS`（15 秒）。于是两种情况必然断档：
     *   ① **长段**：某段播放超过 15 秒（很正常），`at` 不再更新 → 读的人判定过期
     *      → 「正在朗读」行与「合成进度」行**整块消失**，要等下一段开始才回来；
     *   ② **暂停后继续**：同一段恢复播放时 `nowSpeak == lastNowSpeak`，[report]
     *      因幂等直接 return，`at` 一直停在暂停前那一刻 → 恢复后依然被判过期。
     * 由字幕同步循环（200ms 一跳）节流调用本函数即可同时解决两者。
     *
     * @return true=真的写了盘（调用方节流用）
     */
    fun touch(): Boolean {
        // 有在播的段才刷：从没 report 过（lastSeg<0）就不该写出 playing=true
        if (lastSeg < 0) return false
        val dir = File(EngineDataDir.baseDir())
        if (!dir.exists()) return false
        return runCatching {
            synchronized(ioLock) {
                val f = File(dir, FILE_NAME)
                if (!f.exists()) return false
                val json = runCatching { JSONObject(f.readText()) }.getOrElse { return false }
                // 已停（clear 写过 playing=false）就别把它救活
                if (!json.optBoolean("playing", false)) return false
                json.put("at", System.currentTimeMillis())
                f.writeText(json.toString())
            }
            true
        }.getOrElse { false }
    }

    /**
     * 11-14 追加：合成前沿上报（UTTS 进度行「多合几段/正在合第几段」的数据源）。
     *
     * 为什么由 U阅 报：UTTS 的日志只有请求顺序，不知道每笔请求对应第几段；
     * 而 U阅 的合成循环（for i in nowSpeak until endIndex）天然知道正在合的是
     * 第几段、哪一章。合成循环每提交一笔就调一次本函数（幂等：同段跳过）。
     *
     * @param chapterIndex 前沿所在章（合成循环的章节上下文）
     * @param segIndex     前沿段在该章 contentList 的下标
     * @param chapterTitle 前沿章名（预取跨章时与播放章不同，渲染层据此分显）
     */
    fun reportSynthFront(
        chapterIndex: Int,
        segIndex: Int,
        chapterTitle: String,
        totalSegs: Int,
        // ===== 11-22（用户令「跨章备的量得显示」）：往后已备总段数，**跨章累加** =====
        //
        // 为什么由 U阅 算：UTTS 侧只有 `synthChapterIndex + synthSegIndex` 一组字段
        // （合成前沿所在的那一章），压根不知道前沿之后还有几段已备好，更不知道
        // 前沿已经跨到第几章。而「我还能连着听多少」恰恰是用户唯一真正关心的问题——
        // 只报「已备至第5章」无法区分「第5章备了1条」和「第5章备了60条」，
        // 那两种情况的听感天差地别。
        //
        // 算法（U阅 侧天然有全部信息）：从**当前播放段**往后数，数到末尾为止，
        // 统计缓存文件已存在的段数（跨章累加，因为合成循环本来就是跨章连续的）。
        // 纯文件存在性判断，不读音频内容，几十~几百段也只有一次 list/stat 开销。
        //
        // 传 -1 = 本笔没算（调用方未提供），UTTS 侧据此退回旧显示（不画缓冲数）。
        bufferedAhead: Int = -1,
    ) {
        runCatching {
            val dir = File(EngineDataDir.baseDir())
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, FILE_NAME)
            // 读改写必须整体持锁：否则与 [report] 的整文件覆写交错，
            // 可能读到 0 字节（真机 20:51:16「End of input at character 0」）
            // 或把对方刚写的字段覆盖回去。
            synchronized(ioLock) {
                val json = if (f.exists()) JSONObject(f.readText()) else JSONObject()
                json.put("synthChapterIndex", chapterIndex)
                json.put("synthSegIndex", segIndex)
                json.put("synthChapterTitle", chapterTitle)
                // 前沿章总段数：UTTS 进度行「第x章 3/25」的分母（该章已完成段数的上限）
                json.put("synthTotalSegs", totalSegs)
                // 11-22：往后已备总段数（跨章累加）——吸顶进度条「浅绿段」的数据源，
                // 直接回答「还能连着听多少段」。-1 表示本笔没算，UTTS 侧不画该数字。
                json.put("bufferedAheadSegs", bufferedAhead)
                f.writeText(json.toString())
            }
        }.onFailure {
            AppLog.putDebug("合成前沿写入失败: ${it.localizedMessage}")
        }
    }

    /** 停止/暂停朗读时调用：让读的人立刻知道「不在播了」，不必等 STALE_MS 超时 */
    fun clear() {
        lastBook = ""
        lastChapterIdx = -1
        lastSeg = -1
        runCatching {
            val dir = File(EngineDataDir.baseDir())
            if (!dir.exists()) dir.mkdirs()
            val json = JSONObject().apply {
                put("playing", false)
                put("at", System.currentTimeMillis())
            }
            synchronized(ioLock) {
                File(dir, FILE_NAME).writeText(json.toString())
            }
        }
    }
}
