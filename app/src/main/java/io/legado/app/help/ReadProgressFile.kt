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
    fun report(chapterIndex: Int, segIndex: Int, totalSegs: Int, segText: String) {
        // 同一段重复上报直接跳过：字幕同步循环 200ms 一跳，不挡会产生大量无谓写盘
        val book = ReadBook.book?.name.orEmpty()
        if (book == lastBook && chapterIndex == lastChapterIdx && segIndex == lastSeg) return
        lastBook = book
        lastChapterIdx = chapterIndex
        lastSeg = segIndex
        runCatching {
            val json = JSONObject().apply {
                put("book", book)
                put("chapterTitle", ReadBook.curTextChapter?.title.orEmpty())
                // 播放组：UTTS 用它定位「正在朗读」那条卡
                put("playing", true)
                put("chapterIndex", chapterIndex)
                put("segIndex", segIndex)
                put("totalSegs", totalSegs)
                // 正文前缀（截 60 字）：日志行没有段号字段，「哪张卡是正在朗读」
                // 靠章名+正文前缀匹配——正文是两边唯一的公共键（请求音频行 <b>正文</b>=原文）
                put("segText", segText.take(60))
                put("at", System.currentTimeMillis())
            }
            val dir = File(EngineDataDir.baseDir())
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_NAME).writeText(json.toString())
        }.onFailure {
            // 辅助信息，失败不打扰用户，只在 AppLog 留痕
            AppLog.putDebug("朗读进度写入失败: ${it.localizedMessage}")
        }
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
    fun reportSynthFront(chapterIndex: Int, segIndex: Int, chapterTitle: String, totalSegs: Int) {
        runCatching {
            val dir = File(EngineDataDir.baseDir())
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, FILE_NAME)
            val json = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            json.put("synthChapterIndex", chapterIndex)
            json.put("synthSegIndex", segIndex)
            json.put("synthChapterTitle", chapterTitle)
            // 前沿章总段数：UTTS 进度行「第x章 3/25」的分母（该章已完成段数的上限）
            json.put("synthTotalSegs", totalSegs)
            f.writeText(json.toString())
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
            File(dir, FILE_NAME).writeText(json.toString())
        }
    }
}
