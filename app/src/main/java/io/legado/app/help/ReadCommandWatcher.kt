package io.legado.app.help

import android.os.FileObserver
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.AudioCacheIndicatorManager
import io.legado.app.utils.StringUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * UTTS「日志栏喇叭」的 **U阅 侧接收端**（11-20 新增）。
 *
 * ## 它解决什么
 * 用户在 UTTS 日志页点某张请求卡的喇叭 → 选「从这段开始读」/「重新合成」。
 * UTTS 只能写一个命令文件（见其 `ReadCommandFile`），真正能把某段读出来、
 * 跳页、高亮、跨章续播的只有本 App 的朗读服务。本对象负责监听命令并执行。
 *
 * ## 为什么必须校验书名
 * 命令文件按「当前启用朗读规则」的目录存放，用户换书后旧命令可能还在。
 * 只按段号执行会跳错书。所以只接受 `book == ReadBook.book?.name` 的命令；
 * 不一致直接丢弃（并记一行日志），绝不跳书。
 *
 * ## 两个动作
 *  - `singleParagraph=false`（从这段开始读）：定位到该段所在页并调 `ReadAloud.play`，
 *    与阅读界面长按段落小喇叭的「连续朗读」完全同一条路径（同一参数、同一落点算法）。
 *  - `singleParagraph=true`（重新合成）：先**删掉该段的音频缓存**（否则 U阅 自己
 *    也会直接命中旧 wav，重新合成白做），再调 `ReadAloud.play(singleParagraph=true)`
 *    重新请求合成并播放这一段。
 *
 * ## 与 UTTS 侧合成缓存的关系
 * UTTS 还有一层它自己的 synth_cache（按最终文本 + 音色参数哈希）。U阅 这里删的是
 * **自己的 wav**，光删它不够——UTTS 命中自己缓存会秒回旧音频。所以命令里带
 * `purgeCache=true` 时，本对象同时向 UTTS 的旁路名单登记（见下方 `armSynthBypass`）。
 * 旁路名单走的是 UTTS 的 `SynthCacheBypass`——但那是 UTTS 进程内的内存状态，
 * 本 App 改不到。故实际做法：U阅 删共享缓存目录里对应条目是做不到键的，
 * 只能靠 UTTS 收到**新的重合成请求**时自己按文本放行。
 * ⇒ 因此本命令携带 `purgeCache` 语义，由 UTTS 侧在收到该请求前已 arm 好；
 *   本对象只负责「删 U阅 自己的 wav + 触发一次单段重新请求」。
 *
 * ## 时序
 * UTTS 写命令 → FileObserver（盯目录，因写入用 tmp+rename）→ 读文件 → 校验 →
 * 切主线程执行。失败全程静默（只留 AppLog），绝不影响正常阅读。
 */
object ReadCommandWatcher {

    private const val TAG = "ReadCommandWatcher"
    private const val FILE_NAME = "readCommand.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dir: File get() = File(EngineDataDir.baseDir())

    /** 已执行的命令序号，防 FileObserver 对同一次写派发多次事件导致重复执行 */
    @Volatile
    private var lastSeq: Long = 0L

    @Volatile
    private var watching = false
    private val lock = Any()

    private val observer: FileObserver by lazy {
        // 盯**目录**而不是文件：写入方用 tmp+rename（inode 会换），
        // 盯文件在 rename 后静默失效——与 UTTS ReadProgressReader 同因同解。
        object : FileObserver(dir.path, CLOSE_WRITE or MOVED_TO or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == FILE_NAME) handleCommandFile()
            }
        }
    }

    /** App 启动时调用（幂等）。目录不存在时先建好，否则 FileObserver 立刻抛异常。 */
    fun start() {
        if (watching) return
        synchronized(lock) {
            if (watching) return
            runCatching {
                val d = dir
                if (!d.exists()) d.mkdirs()
                observer.startWatching()
                watching = true
            }.onFailure {
                AppLog.putDebug("朗读命令监听启动失败: ${it.localizedMessage}")
            }
        }
    }

    private fun handleCommandFile() {
        val f = File(dir, FILE_NAME)
        if (!f.exists()) return
        val text = runCatching { f.readText() }.getOrNull() ?: return
        if (text.isBlank()) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return

        val seq = obj.optLong("seq", 0L)
        if (seq <= lastSeq) return // 同一条命令的重复事件
        lastSeq = seq

        val cmd = obj.optString("cmd")
        if (cmd != "playFrom") return

        val book = obj.optString("book")
        val chapterTitle = obj.optString("chapterTitle")
        val segIndex = obj.optInt("segIndex", -1)
        val singleParagraph = obj.optBoolean("singleParagraph", false)

        if (book.isBlank() || chapterTitle.isBlank() || segIndex < 0) {
            AppLog.putDebug("朗读命令参数不全，丢弃: book=$book ch=$chapterTitle seg=$segIndex")
            return
        }

        // 只接受当前正在读的这本书——换书后的残留命令绝不跳书
        val currentBook = ReadBook.book?.name
        if (currentBook != book) {
            AppLog.putDebug("朗读命令书不匹配，丢弃: 命令=$book 当前=$currentBook")
            return
        }

        scope.launch {
            runCatching {
                val chapter = resolveChapter(book, chapterTitle)
                if (chapter == null) {
                    AppLog.putDebug("朗读命令找不到章节: $book / $chapterTitle")
                    return@launch
                }
                // 定位到该段所在页；跨章时先切章
                val chapterIndex = chapter.index
                if (ReadBook.durChapterIndex != chapterIndex) {
                    withContext(Dispatchers.Main) {
                        ReadBook.openChapter(chapterIndex, 0)
                    }
                    // 等排版就绪（openChapter 内部异步加载），最多 3 秒
                    var waited = 0
                    while (waited < 60 && (ReadBook.curTextChapter == null ||
                                !ReadBook.curTextChapter!!.isCompleted)
                    ) {
                        delay(50)
                        waited++
                    }
                }
                val textChapter = ReadBook.curTextChapter ?: return@launch

                // 段号 → 页内偏移。段表口径与朗读服务一致（整章段落，pageSplit=false）
                val pageIndex = findPageOfSegment(textChapter, segIndex)
                if (pageIndex < 0) {
                    AppLog.putDebug("朗读命令定位段落失败: seg=$segIndex pages=${textChapter.pageSize}")
                    return@launch
                }
                val pageStart = textChapter.getReadLength(pageIndex)
                val paraStart = paragraphStartPos(textChapter, segIndex)
                val startPos = (paraStart - pageStart).coerceAtLeast(0)

                if (singleParagraph) {
                    // 「重新合成」：先删本 App 自己的该段 wav，否则会直接播旧音频
                    runCatching {
                        AudioCacheIndicatorManager.clearChapterState()
                        AudioCacheIndicatorManager.scanChapter(textChapter)
                        AudioCacheIndicatorManager.deleteCache(textChapter, segIndex + 1)
                    }
                }

                withContext(Dispatchers.Main) {
                    ReadAloud.play(
                        context = appCtx,
                        play = true,
                        pageIndex = pageIndex,
                        startPos = startPos,
                        singleParagraph = singleParagraph,
                        continueFromParagraph = !singleParagraph,
                    )
                }
                AppLog.putDebug(
                    "朗读命令已执行: $book / $chapterTitle seg=$segIndex " +
                        "page=$pageIndex startPos=$startPos single=$singleParagraph"
                )
            }.onFailure {
                AppLog.putDebug("朗读命令执行失败: ${it.localizedMessage}")
            }
        }
    }

    /** 按书名 + 章标题反查章节（日志行不带章序，章标题是唯一可用的键）。 */
    private suspend fun resolveChapter(
        bookName: String,
        chapterTitle: String,
    ): io.legado.app.data.entities.BookChapter? = withContext(Dispatchers.IO) {
        val book = ReadBook.book ?: return@withContext null
        val list = appDb.bookChapterDao.getChapterList(book.bookUrl)
        // 先精确匹配，再退一步按「去掉空白后相等」（日志里的章名可能被清洗过）
        list.firstOrNull { it.title == chapterTitle }
            ?: list.firstOrNull {
                it.title.replace(Regex("\\s+"), "") == chapterTitle.replace(Regex("\\s+"), "")
            }
    }

    /** 段号 → 所在页下标；找不到返回 -1。 */
    private fun findPageOfSegment(
        textChapter: io.legado.app.ui.book.read.page.entities.TextChapter,
        segIndex: Int,
    ): Int {
        // 段号口径：日志里的 paraIndex 是 0-based（contentList 下标），
        // 而 TextLine.paragraphNum / TextParagraph.num 是 1-based，故 +1 比对。
        val targetNum = segIndex + 1
        for (i in 0 until textChapter.pageSize) {
            val page = textChapter.getPage(i) ?: continue
            if (page.lines.any { it.paragraphNum == targetNum }) return i
        }
        return -1
    }

    /** 该段在整章段表里的字符起点（跨页段取段首）。 */
    private fun paragraphStartPos(
        textChapter: io.legado.app.ui.book.read.page.entities.TextChapter,
        segIndex: Int,
    ): Int {
        val paragraph = textChapter.getParagraphs(false)
            .firstOrNull { it.num == segIndex + 1 }
            ?: return 0
        return paragraph.firstLine.chapterPosition
    }
}
