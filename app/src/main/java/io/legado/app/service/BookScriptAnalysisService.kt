package io.legado.app.service

import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.EngineDataDir
import io.legado.app.help.JsExtensions
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.toc.AiCacheFileUtil
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.systemservices.notificationManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 全书分析服务：调用「全书分析专用脚本」按章节顺序做姓名识别与别名合并。
 *
 * 与旧实现的区别：
 * 1. 不再复用朗读脚本，也不读写 data.json / 进度指针 / 段落历史；
 * 2. 启动时快照书籍与引擎目录，运行中允许朗读其它书甚至切换书；
 * 3. 三章上下文由 Kotlin 直接以内存 binding 传入脚本；
 * 4. 姓名批次与上一批别名校验通过有界 Channel 流水线并行，别名阶段严格有序写角色表；
 * 5. 脚本自己读取 cunfang.txt 判断当前书，决定写 characterRecords.json 或 shuming.<书名>.json。
 */
class BookScriptAnalysisService : BaseService() {

    companion object {
        const val EXTRA_START_INDEX = "startIndex"
        private const val RACE_DELAY = 25_000L
        private const val EXTRACT_LENGTH = 1000
        private const val DOWNLOAD_ROOT = "/storage/emulated/0/Download/"
        private val CHAPTER_TITLE_REGEX =
            Regex("^\\s*第\\s*[0-9一二三四五六七八九十百千零〇两]+\\s*章")
        @Volatile
        var isRun = false

        @Volatile
        var progressIndex = 0

        @Volatile
        var totalCount = 0

        @Volatile
        var runningBookName: String? = null

        @Volatile
        var statusText: String? = null

        @Volatile
        var currentChapterTitle: String? = null

        @Volatile
        var currentChapterIndex = 0

        @Volatile
        var currentParagraphIndex = 0

        @Volatile
        var currentParagraphTotal = 0

        @Volatile
        var analyzedBookName: String? = null

        @Volatile
        var analyzedTotalCount = 0

        @Volatile
        var analyzedChapterCount = 0

        private fun padZero(num: Int, len: Int = 2): String = num.toString().padStart(len, '0')

        private fun countDialogues(text: String): Int = text.count { it == '“' }

        private data class ChapterCacheInfo(
            val cachedSeq: Int,
            val totalDialogs: Int?,
            val complete: Boolean
        )

        private fun readChapterCacheMaxSeq(bookName: String, chapterTitle: String): Int? {
            return runCatching {
                val file = File(
                    AiCacheFileUtil.getBookDirByName(bookName),
                    AiCacheFileUtil.sanitizeChapterFileName(chapterTitle) + ".json"
                )
                if (!file.isFile) return null
                val obj = JSONObject(file.readText())
                val results = obj.optJSONObject("results") ?: return@runCatching 0
                val seqs = mutableSetOf<Int>()
                for (key in results.keys()) {
                    key.toString().toIntOrNull()?.let { seqs.add(it) }
                }
                var contiguous = 0
                while (contiguous + 1 in seqs) contiguous++
                contiguous
            }.getOrNull()
        }

        private fun readChapterCacheInfo(bookName: String, chapterTitle: String): ChapterCacheInfo? {
            return runCatching {
                val file = File(
                    AiCacheFileUtil.getBookDirByName(bookName),
                    AiCacheFileUtil.sanitizeChapterFileName(chapterTitle) + ".json"
                )
                if (!file.isFile) return null
                val obj = JSONObject(file.readText())
                val results = obj.optJSONObject("results") ?: JSONObject()
                val seqs = mutableSetOf<Int>()
                for (key in results.keys()) {
                    key.toString().toIntOrNull()?.let { seqs.add(it) }
                }
                var contiguous = 0
                while (contiguous + 1 in seqs) contiguous++
                val totalDialogs = if (obj.has("totalDialogs")) {
                    obj.optInt("totalDialogs", -1).takeIf { it >= 0 }
                } else null
                ChapterCacheInfo(
                    cachedSeq = contiguous,
                    totalDialogs = totalDialogs,
                    complete = totalDialogs != null && contiguous >= totalDialogs
                )
            }.getOrNull()
        }

        private fun countAnalyzedChapterFiles(book: Book, total: Int): Int {
            val dir = AiCacheFileUtil.getBookDirByName(book.name)
            val files = runCatching {
                dir.listFiles { file ->
                    file.isFile
                            && file.extension.equals("json", ignoreCase = true)
                            && !file.name.endsWith(".tmp", ignoreCase = true)
                }
            }.getOrNull()
            return (files?.size ?: 0).coerceIn(0, total)
        }

        suspend fun refreshAnalyzedCount(book: Book?) = withContext(Dispatchers.IO) {
            if (book == null || isRun) return@withContext
            val total = appDb.bookChapterDao.getChapterCount(book.bookUrl)
            analyzedBookName = book.name
            analyzedTotalCount = total
            analyzedChapterCount = countAnalyzedChapterFiles(book, total)
        }
    }

    private var analyzeJob: Job? = null
    private val keyIndex = AtomicInteger(0)
    private val aiSemaphore = Semaphore(2)
    private val jsDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val nl = System.lineSeparator()

    /** 角色记录内存快照：分析开始时读一次磁盘，之后所有读取与修改只基于它 */
    private var recordsSnapshot: String = "[]"
    private val analysisJsExtensions = object : JsExtensions {
        override fun getSource(): BaseSource? = null
        override fun getTag(): String = "BookAnalysis"

        override fun readExternalFile(path: String): String {
            val file = File(path)
            if (!file.canonicalPath.startsWith(DOWNLOAD_ROOT)) {
                throw SecurityException("非法路径")
            }
            return if (file.isFile) file.readText() else ""
        }

        override fun writeExternalFile(path: String, content: String): Boolean {
            val file = File(path)
            if (!file.canonicalPath.startsWith(DOWNLOAD_ROOT)) {
                throw SecurityException("非法路径")
            }
            return try {
                file.parentFile?.mkdirs()
                file.writeText(content)
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_search)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle("全书分析（角色标注）")
            .addAction(
                R.drawable.ic_stop_black_24dp,
                getString(R.string.cancel),
                servicePendingIntent<BookScriptAnalysisService>(IntentAction.stop)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private data class DialogInfo(
        val localSeq: String,
        val realSeq: Int,
        val text: String
    )

    private data class ParagraphInfo(
        val index: Int,
        val text: String,
        val start: Int,
        val end: Int
    )

    private data class ChapterSpan(
        val chapterIndex: Int,
        val title: String,
        val text: String,
        val bodyStart: Int,
        val bodyText: String,
        val start: Int,
        val end: Int,
        val dialogueCount: Int,
        val cachePath: String
    )

    private data class ChapterWindow(
        val fullText: String,
        val spans: List<ChapterSpan>
    )

    private data class ChapterPatch(
        val path: String,
        val title: String,
        val totalDialogs: Int,
        val results: JSONObject
    )

    private data class AnalysisTask(
        val chapterIndex: Int,
        val chapterTitle: String,
        val paragraphIndex: Int,
        val paragraphTotal: Int,
        val paragraph: String,
        val prevParagraph: String,
        val paragraphStartSeq: Int,
        val dialogs: List<DialogInfo>,
        val above: String,
        val below: String,
        val belowStart: Int,
        val nextRealSeq: Int,
        val chapterDialogueCount: Int,
        val chapterSpans: List<ChapterSpan>,
        val keyIndex: Int
    )

    private data class NameOutcome(
        val task: AnalysisTask,
        val nameResultJson: String,
        val pairsJson: String,
        val chapterPatches: List<ChapterPatch>
    )

    private fun splitParagraphsWithOffsets(text: String): List<ParagraphInfo> {
        val out = mutableListOf<ParagraphInfo>()
        var pos = 0
        text.split(nl).forEach { line ->
            val start = pos
            pos += line.length
            if (line.isNotBlank()) {
                out.add(ParagraphInfo(out.size, line, start, pos))
            }
            pos += nl.length
        }
        return out
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> {
                analyzeJob?.cancel()
                isRun = false
                AppLog.put("全书分析: 已手动停止")
                stopSelf()
            }
            else -> {
                val startIndex = intent?.getIntExtra(EXTRA_START_INDEX, 0) ?: 0
                val book = ReadBook.book
                if (book == null) {
                    AppLog.put("全书分析: 未打开书籍，无法分析")
                    stopSelf()
                    return super.onStartCommand(intent, flags, startId)
                }
                if (analyzeJob?.isActive == true) {
                    AppLog.put("全书分析: 已有分析任务进行中，忽略本次请求")
                    return super.onStartCommand(intent, flags, startId)
                }
                isRun = true
                runningBookName = book.name
                keyIndex.set(0)
                startForegroundNotification()
                analyzeJob = lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        runAnalysis(book, startIndex)
                    }.onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            AppLog.put("全书分析: 异常终止$nl$it", it)
                        }
                    }
                    isRun = false
                    stopSelf()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        analyzeJob?.cancel()
        isRun = false
        jsDispatcher.close()
        super.onDestroy()
    }

    private suspend fun runAnalysis(book: Book, startIndex: Int) {
        val total = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        if (startIndex >= total) {
            AppLog.put("全书分析: 起始章超出一书范围，无需分析")
            return
        }
        totalCount = total
        analyzedBookName = book.name
        analyzedTotalCount = total
        analyzedChapterCount = countAnalyzedChapterFiles(book, total)
        val engineDir = EngineDataDir.baseDir()
        AppLog.put("全书分析: 《${book.name}》全书共${total}章，从第${startIndex + 1}章开始，目录 $engineDir")

        // 分析开始时按 cunfang.txt 判定读源，角色记录只读这一次磁盘，之后全程使用内存快照
        val initAnalysisJson = JSONObject()
            .put("bookName", book.name)
            .put("engineDir", engineDir)
            .put("cunfangPath", engineDir + "cunfang.txt")
            .put("characterRecordsPath", engineDir + "characterRecords.json")
            .put("shumingPath", engineDir + "shuming." + book.name + ".json")
            .toString()
        recordsSnapshot = readFileOrEmpty(
            resolveRecordsTarget(initAnalysisJson, JSONObject(initAnalysisJson))
        ).ifBlank { "[]" }
        for (idx in startIndex until total) {
            currentCoroutineContext().ensureActive()
            val ch = appDb.bookChapterDao.getChapter(book.bookUrl, idx) ?: continue
            currentChapterIndex = idx + 1
            currentChapterTitle = ch.title
            currentParagraphIndex = 0
            currentParagraphTotal = 0

            // 轻量跳章：缓存文件自带 totalDialogs 且连续序号已覆盖，就直接跳过，
            // 不再读正文、不再拼三章窗口、不再拆段。
            val cacheInfo = readChapterCacheInfo(book.name, ch.title)
            if (cacheInfo?.complete == true) {
                progressIndex = idx + 1
                upNotification(progressText(idx, total, "已有完整缓存，跳过"))
                continue
            }

            val raw = BookHelp.getContent(book, ch)
            val clean = if (raw.isNullOrBlank()) "" else ReadBook.cleanContentForTts(book, ch, raw)
            if (clean.isBlank()) {
                progressIndex = idx + 1
                upNotification(progressText(idx, total, "空章节跳过"))
                continue
            }

            val window = buildChapterWindow(book, idx, total, clean)
            val currentSpan = window.spans.firstOrNull { it.chapterIndex == idx }
                ?: continue
            val newMetaFiles = ensureChapterCacheMeta(currentSpan)
            bumpAnalyzedChapterCount(book.name, newMetaFiles)

            if (currentSpan.dialogueCount == 0) {
                progressIndex = idx + 1
                upNotification(progressText(idx, total, "无可分析对话，跳过"))
                continue
            }

            val tasks = buildTasks(book, ch, idx, clean, window)
            val analysisJson = buildAnalysisJson(book, ch, engineDir)
            val initialCachedSeq = readChapterCacheMaxSeq(book.name, ch.title) ?: 0
            if (tasks.isNotEmpty()) {
                analyzeChapterPipeline(analysisJson, tasks, idx, total, initialCachedSeq)
            } else {
                upNotification(progressText(idx, total, "已有完整缓存，跳过"))
            }

            progressIndex = idx + 1
            upNotification(progressText(idx, total, ch.title.take(16)))
        }

        AppLog.put("全书分析: 《${book.name}》全部分析完成，角色与别名结果已写入脚本章节缓存")
        notificationManager.cancel(NotificationId.BookScriptAnalysisService)
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_search)
            .setContentTitle("全书分析完成")
            .setContentText("《${book.name}》角色与别名已写入脚本缓存，共 $total 章")
            .setAutoCancel(true)
            .build()
            .let {
                notificationManager.notify(
                    NotificationId.BookScriptAnalysisService + 1000,
                    it
                )
            }
    }

    @Synchronized
    private fun bumpAnalyzedChapterCount(bookName: String, delta: Int) {
        if (delta > 0 && analyzedBookName == bookName) {
            analyzedChapterCount = (analyzedChapterCount + delta).coerceAtMost(analyzedTotalCount)
        }
    }

    private fun buildAnalysisJson(book: Book, ch: BookChapter, engineDir: String): String {
        return JSONObject()
            .put("bookName", book.name)
            .put("chapterTitle", ch.title)
            .put("engineDir", engineDir)
            .put("cunfangPath", engineDir + "cunfang.txt")
            .put("characterRecordsPath", engineDir + "characterRecords.json")
            .put("shumingPath", engineDir + "shuming." + book.name + ".json")
            .put("keyPath", engineDir + "miyue.txt")
            .put(
                "chapterCachePath",
                AiCacheFileUtil.getChapterCacheFileByName(book.name, ch.title).absolutePath
            )
            .toString()
    }

    private fun buildChapterWindow(
        book: Book,
        idx: Int,
        total: Int,
        currentClean: String
    ): ChapterWindow {
        val sb = StringBuilder()
        val spans = mutableListOf<ChapterSpan>()
        var pos = 0
        listOf(idx - 1, idx, idx + 1)
            .filter { it in 0 until total }
            .forEach { chapterIndex ->
                val ch = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                    ?: return@forEach
                val text = if (chapterIndex == idx) {
                    currentClean
                } else {
                    val raw = BookHelp.getContent(book, ch)
                    if (raw.isNullOrBlank()) "" else ReadBook.cleanContentForTts(book, ch, raw)
                }
                if (text.isBlank()) return@forEach
                if (sb.isNotEmpty()) {
                    sb.append(nl)
                    pos += nl.length
                }
                val start = pos
                sb.append(text)
                pos += text.length
                val end = pos
                val titleLine = text.substringBefore(nl)
                val title = titleLine.trim().ifBlank { ch.title }
                val bodyStartInText = (titleLine.length + nl.length).coerceAtMost(text.length)
                val bodyText = if (bodyStartInText < text.length) {
                    text.substring(bodyStartInText)
                } else ""
                spans.add(
                    ChapterSpan(
                        chapterIndex = chapterIndex,
                        title = title,
                        text = text,
                        bodyStart = start + bodyStartInText,
                        bodyText = bodyText,
                        start = start,
                        end = end,
                        dialogueCount = countDialogues(bodyText),
                        cachePath = AiCacheFileUtil
                            .getChapterCacheFileByName(book.name, ch.title).absolutePath
                    )
                )
            }
        return ChapterWindow(sb.toString(), spans)
    }

    /** 对话文本归一化，用于校验章节缓存里的 dialogText 是否就是当前这一句 */
    private fun normalizeDialog(s: String): String = s.replace(
        Regex("[\\s\\u3000]+|【\\d+】|[“”‘’\"'。，、；：！？（）【】《》…—·]"),
        ""
    )

    /** 从该章的缓存文件里取章内序号对应的说话人名字；文本对不上则不认 */
    private fun readCachedSpeakerName(span: ChapterSpan, realSeq: Int, dialogText: String): String? {
        return runCatching {
            val file = File(span.cachePath)
            if (!file.isFile) return null
            val results = JSONObject(file.readText()).optJSONObject("results") ?: return null
            val item = results.optJSONObject(realSeq.toString()) ?: return null
            val name = item.optString("name", "").trim()
            if (name.isEmpty()) return null
            val cached = item.optString("dialogText", "")
            if (cached.isNotBlank() && normalizeDialog(cached) != normalizeDialog(dialogText)) return null
            name
        }.getOrNull()
    }

    /**
     * 给上文 1000 字里已分析过的对话，在左引号前插入 [说话人]。
     * 与朗读脚本 annotateAboveContext 同口径：绝对位置 → 所属章 → 章内对话序号。
     * 窗口已包含上一章，章首也能拿到上一章末尾的标注。
     */
    private fun annotateAboveContext(
        above: String,
        aboveStart: Int,
        spans: List<ChapterSpan>
    ): String {
        if (above.isEmpty() || spans.isEmpty()) return above
        val inserts = mutableListOf<Pair<Int, String>>()
        val lines = above.split('\n')
        var lineStart = 0
        for (line in lines) {
            if (!CHAPTER_TITLE_REGEX.containsMatchIn(line)) {
                var idx = 0
                while (idx < line.length) {
                    val left = line.indexOf('“', idx)
                    if (left < 0) break
                    val right = line.indexOf('”', left + 1)
                    val text = if (right >= 0) {
                        line.substring(left + 1, right)
                    } else {
                        line.substring(left + 1).take(1000)
                    }
                    if (text.isNotBlank()) {
                        val absPos = aboveStart + lineStart + left
                        val span = spans.firstOrNull { absPos >= it.bodyStart && absPos < it.end }
                        if (span != null) {
                            val offset = (absPos - span.bodyStart).coerceIn(0, span.bodyText.length)
                            val realSeq = countDialogues(span.bodyText.substring(0, offset)) + 1
                            val name = readCachedSpeakerName(span, realSeq, text)
                            if (!name.isNullOrBlank()) inserts.add((lineStart + left) to "[$name]")
                        }
                    }
                    idx = if (right >= 0) right + 1 else line.length
                }
            }
            lineStart += line.length + 1
        }
        if (inserts.isEmpty()) return above
        val out = StringBuilder(above)
        inserts.sortedByDescending { it.first }.forEach { (pos, mark) ->
            out.insert(pos, mark)
        }
        return out.toString()
    }
    private fun buildTasks(
        book: Book,
        ch: BookChapter,
        chapterIndex: Int,
        clean: String,
        window: ChapterWindow
    ): List<AnalysisTask> {
        val fullText = window.fullText
        val currentSpan = window.spans.firstOrNull { it.chapterIndex == chapterIndex }
            ?: return emptyList()
        val cachedSeq = readChapterCacheMaxSeq(book.name, ch.title) ?: 0
        val allParagraphs = splitParagraphsWithOffsets(clean)
        // 第一行是 cleanContentForTts 重新加入的章节标题，不参与对话编号。
        val paragraphs = allParagraphs.drop(1)
        val tasks = mutableListOf<AnalysisTask>()
        var cumDialogues = 0
        for ((pIdx, paraInfo) in paragraphs.withIndex()) {
            val para = paraInfo.text
            val texts = extractDialogs(para)
            if (texts.isEmpty()) continue
            val paragraphFirstSeq = cumDialogues + 1
            if (cumDialogues + texts.size <= cachedSeq) {
                cumDialogues += texts.size
                continue
            }
            // 用循环累计的固定序号定位，不再用 indexOf(para) 反推。
            val dialogs = mutableListOf<DialogInfo>()
            texts.forEachIndexed { i, text ->
                val realSeq = cumDialogues + i + 1
                if (realSeq > cachedSeq) {
                    dialogs.add(DialogInfo(padZero(dialogs.size + 1, 2), realSeq, text))
                }
            }
            if (dialogs.isEmpty()) {
                cumDialogues += texts.size
                continue
            }
            val paraPosInChapter = paraInfo.start
            val paraPosFull = currentSpan.start + paraPosInChapter
            val aboveStart = (paraPosFull - EXTRACT_LENGTH).coerceAtLeast(0)
            val aboveRaw = if (paraPosFull > 0) fullText.substring(aboveStart, paraPosFull) else ""
            val above = annotateAboveContext(aboveRaw, aboveStart, window.spans)
            val belowStart = (paraPosFull + para.length).coerceAtMost(fullText.length)
            val belowEnd = (belowStart + EXTRACT_LENGTH).coerceAtMost(fullText.length)
            val below = if (belowStart < fullText.length) {
                fullText.substring(belowStart, belowEnd)
            } else ""
            val prevParagraph = paragraphs.getOrNull(pIdx - 1)?.text ?: ""
            tasks.add(
                AnalysisTask(
                    chapterIndex = chapterIndex,
                    chapterTitle = ch.title,
                    paragraphIndex = pIdx,
                    paragraphTotal = paragraphs.size,
                    paragraph = para,
                    prevParagraph = prevParagraph,
                    paragraphStartSeq = paragraphFirstSeq,
                    dialogs = dialogs,
                    above = above,
                    below = below,
                    belowStart = belowStart,
                    nextRealSeq = cumDialogues + texts.size + 1,
                    chapterDialogueCount = currentSpan.dialogueCount,
                    chapterSpans = window.spans,
                    keyIndex = keyIndex.getAndIncrement()
                )
            )
            cumDialogues += texts.size
        }
        return tasks
    }

    private fun extractDialogs(paragraph: String): List<String> {
        val out = mutableListOf<String>()
        var idx = 0
        while (idx < paragraph.length) {
            val left = paragraph.indexOf('“', idx)
            if (left < 0) break
            val right = paragraph.indexOf('”', left + 1)
            val text = if (right >= 0) {
                paragraph.substring(left + 1, right)
            } else {
                paragraph.substring(left + 1).take(1000)
            }
            if (text.isNotBlank()) out.add(text)
            idx = if (right >= 0) right + 1 else paragraph.length
        }
        return out
    }

    private fun buildTaskJson(task: AnalysisTask): String {
        val dialogs = JSONArray()
        task.dialogs.forEach {
            dialogs.put(
                JSONObject()
                    .put("localSeq", it.localSeq)
                    .put("realSeq", it.realSeq)
                    .put("text", it.text)
            )
        }
        return JSONObject()
            .put("chapterIndex", task.chapterIndex)
            .put("chapterTitle", task.chapterTitle)
            .put("paragraphIndex", task.paragraphIndex)
            .put("paragraphTotal", task.paragraphTotal)
            .put("paragraph", task.paragraph)
            .put("prevParagraph", task.prevParagraph)
            .put("paragraphStartSeq", task.paragraphStartSeq)
            .put("dialogs", dialogs)
            .put("above", task.above)
            .put("below", task.below)
            .put("keyIndex", task.keyIndex)
            .toString()
    }

    private fun findSpanByAbsPos(spans: List<ChapterSpan>, absPos: Int): ChapterSpan? =
        spans.firstOrNull { absPos >= it.start && absPos < it.end }

    private fun buildChapterPatches(
        task: AnalysisTask,
        nameResultJson: String,
        pairsJson: String
    ): List<ChapterPatch> {
        val root = runCatching { JSONObject(nameResultJson) }.getOrNull()
            ?: return emptyList()
        val items = root.optJSONObject("items") ?: return emptyList()
        val pairs = runCatching { JSONArray(pairsJson) }.getOrNull()
            ?: return emptyList()
        val grouped = LinkedHashMap<String, ChapterPatch>()
        for (i in 0 until pairs.length()) {
            val pair = pairs.optJSONObject(i) ?: continue
            val localSeq = pair.optString("localSeq")
            val isCurrent = pair.optBoolean("isCurrent", i < task.dialogs.size)
            val info = items.optJSONObject(localSeq) ?: continue
            val span: ChapterSpan
            val realSeq: Int
            if (isCurrent) {
                val dialog = task.dialogs.firstOrNull { it.localSeq == localSeq }
                    ?: continue
                span = task.chapterSpans.firstOrNull { it.chapterIndex == task.chapterIndex }
                    ?: continue
                realSeq = dialog.realSeq
            } else {
                val sourceOffset = pair.optInt("sourceOffset", 0)
                val absPos = task.belowStart + sourceOffset
                span = findSpanByAbsPos(task.chapterSpans, absPos) ?: continue
                // 章节标题行不参与对话编号；只按正文 bodyText 计算目标章内序号。
                if (absPos < span.bodyStart) continue
                val offsetInBody = (absPos - span.bodyStart).coerceIn(0, span.bodyText.length)
                realSeq = countDialogues(span.bodyText.substring(0, offsetInBody)) + 1
            }
            if (realSeq > span.dialogueCount) continue
            val patch = grouped.getOrPut(span.cachePath) {
                ChapterPatch(span.cachePath, span.title, span.dialogueCount, JSONObject())
            }
            patch.results.put(
                realSeq.toString(),
                JSONObject()
                    .put("name", info.optString("name"))
                    .put("gender", info.optString("gender"))
                    .put("age", info.optString("age"))
                    .put("dialogText", pair.optString("text"))
            )
        }
        return grouped.values.toList()
    }

    private suspend fun analyzeChapterPipeline(
        analysisJson: String,
        tasks: List<AnalysisTask>,
        idx: Int,
        total: Int,
        initialCachedSeq: Int
    ) = coroutineScope {
        val coveredSeq = AtomicInteger(initialCachedSeq)
        val analysisObj = JSONObject(analysisJson)
        val nameChannel = Channel<NameOutcome>(2)
        val producer = async(Dispatchers.Default) {
            try {
                tasks.forEach { task ->
                    ensureActive()
                    // 上一次姓名请求已经把后面若干段落的对话落盘，这里整段跳过
                    if (task.dialogs.maxOf { it.realSeq } <= coveredSeq.get()) return@forEach
                    nameChannel.send(doName(task, analysisJson))
                }
            } finally {
                nameChannel.close()
            }
        }
        val consumer = async(Dispatchers.IO) {
            for (outcome in nameChannel) {
                ensureActive()
                currentParagraphIndex = outcome.task.paragraphIndex + 1
                currentParagraphTotal = outcome.task.paragraphTotal
                doAliasAndWrite(outcome, analysisJson)
                val newCached = readChapterCacheMaxSeq(
                    analysisObj.optString("bookName"),
                    analysisObj.optString("chapterTitle")
                ) ?: coveredSeq.get()
                coveredSeq.set(newCached)
                upNotification(
                    progressText(
                        idx,
                        total,
                        "第${outcome.task.paragraphIndex + 1}/${outcome.task.paragraphTotal}段"
                    )
                )
            }
        }
        awaitAll(producer, consumer)
    }

    private suspend fun doName(task: AnalysisTask, analysisJson: String): NameOutcome {
        val taskJson = buildTaskJson(task)
        val values = mutableMapOf<String, Any?>(
            "analysisJson" to analysisJson,
            "taskJson" to taskJson
        )
        val prepJson = evalString("prepareNameRequest(taskJson)", values) ?: "{}"
        val prepObj = runCatching { JSONObject(prepJson) }.getOrDefault(JSONObject())
        val requests = requestArray(prepObj.optJSONArray("requests")?.toString() ?: "[]")
        val pairsJson = prepObj.optJSONArray("pairs")?.toString() ?: "[]"
        val body = race(requests)
        val nameResultJson = if (body.isNullOrBlank()) {
            "{}"
        } else {
            values["body"] = body
            evalString("parseNameResponse(taskJson, body)", values) ?: "{}"
        }
        return NameOutcome(
            task = task,
            nameResultJson = nameResultJson,
            pairsJson = pairsJson,
            chapterPatches = buildChapterPatches(task, nameResultJson, pairsJson)
        )
    }

    private suspend fun doAliasAndWrite(outcome: NameOutcome, analysisJson: String) {
        val analysisObj = JSONObject(analysisJson)
        val aliasTaskJson = JSONObject(buildTaskJson(outcome.task))
            .put("keyIndex", keyIndex.getAndIncrement())
            .toString()
        val recordsJson = recordsSnapshot
        val values = mutableMapOf<String, Any?>(
            "analysisJson" to analysisJson,
            "taskJson" to aliasTaskJson,
            "recordsJson" to recordsJson,
            "nameResultJson" to outcome.nameResultJson
        )
        val aliasPrep = evalString(
            "prepareAliasRequest(taskJson, recordsJson, nameResultJson)",
            values
        ) ?: "{}"
        val requests = requestArray(aliasPrep)
        val body = if (requests.isNotEmpty()) race(requests) else null
        values["body"] = body ?: ""
        values["aliasMetaJson"] = aliasPrep
        val merged = evalString(
            "parseAliasResponse(taskJson, body, recordsJson, nameResultJson, aliasMetaJson)",
            values
        )
        if (!merged.isNullOrBlank()) {
            recordsSnapshot = merged
            writeRecords(merged, analysisObj, analysisJson)        }
        val newFiles = writeChapterPatches(outcome.chapterPatches)
        bumpAnalyzedChapterCount(analysisObj.optString("bookName"), newFiles)
    }

    private suspend fun evalString(expr: String, values: Map<String, Any?>): String? =
        withContext(jsDispatcher) {
            val script = AppConfig.bookAnalysisScript
            if (script.isBlank()) {
                AppLog.put("全书分析: 专用脚本为空")
                return@withContext null
            }
            val bindings = buildScriptBindings { b ->
                b["java"] = analysisJsExtensions
                values.forEach { (k, v) -> if (v != null) b[k] = v }
            }
            runCatching {
                RhinoScriptEngine.eval(script + nl + expr, bindings)?.toString()
            }.onFailure {
                AppLog.put("全书分析: 脚本执行错误$nl${it.localizedMessage}", it)
            }.getOrNull()
        }

    private suspend fun race(requests: Array<String>): String? = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext null
        aiSemaphore.acquire()
        try {
            analysisJsExtensions.ajaxRaceDelayedValidated(requests, RACE_DELAY)
        } finally {
            aiSemaphore.release()
        }
    }

    private fun requestArray(json: String?): Array<String> {
        if (json.isNullOrBlank()) return emptyArray()
        val arr = runCatching<JSONArray?> {
            val text = json.trim()
            if (text.startsWith("[")) JSONArray(text)
            else JSONObject(text).optJSONArray("requests")
        }.getOrNull() ?: return emptyArray()
        return Array(arr.length()) { arr.getString(it) }
    }

    private fun readFileOrEmpty(path: String): String = runCatching {
        val file = File(path)
        if (file.isFile) file.readText() else ""
    }.getOrDefault("")

    /**
     * 每次调用都会重新读取 cunfang.txt：是当前分析的书 → characterRecords.json，
     * 否则 → shuming.<书名>.json。启动时用于选定读源，落盘前用于选定写目标。
     */
    private suspend fun resolveRecordsTarget(analysisJson: String, analysisObj: JSONObject): String {
        val engineDir = analysisObj.optString("engineDir")
        return evalString(
            "resolveRecordsTarget()",
            mapOf("analysisJson" to analysisJson)
        )?.takeIf { it.startsWith(engineDir) }
            ?: analysisObj.optString("shumingPath")
    }

    /** 落盘前按 cunfang.txt 当前内容重新判定目标，只写这一个文件 */
    private suspend fun writeRecords(merged: String, analysisObj: JSONObject, analysisJson: String) {
        val target = resolveRecordsTarget(analysisJson, analysisObj)
        if (!target.startsWith(analysisObj.optString("engineDir"))) return
        writeAtomic(File(target), merged)    }

    private fun writeChapterPatches(patches: List<ChapterPatch>): Int {
        var created = 0
        patches.forEach { patch ->
            val file = File(patch.path)
            val existed = file.isFile
            val obj = runCatching {
                if (file.isFile) JSONObject(file.readText()) else JSONObject()
            }.getOrDefault(JSONObject())
            obj.put("title", patch.title)
            obj.put("totalDialogs", patch.totalDialogs)
            val results = obj.optJSONObject("results")
                ?: JSONObject().also { obj.put("results", it) }
            for (key in patch.results.keys()) {
                results.put(key, patch.results.getJSONObject(key))
            }
            writeChapterCacheObject(file, obj)
            if (!existed) created++
        }
        return created
    }

    private fun ensureChapterCacheMeta(span: ChapterSpan): Int {
        val file = File(span.cachePath)
        val existed = file.isFile
        val obj = runCatching {
            if (file.isFile) JSONObject(file.readText()) else JSONObject()
        }.getOrDefault(JSONObject())
        obj.put("title", span.title)
        obj.put("totalDialogs", span.dialogueCount)
        if (!obj.has("results")) obj.put("results", JSONObject())
        writeChapterCacheObject(file, obj)
        return if (existed) 0 else 1
    }

    /**
     * 章节缓存按朗读脚本的观感写盘：
     * 1. results 里的数字序号按数值升序排列，补写中间缺失序号时不会追加到末尾；
     * 2. 用 2 空格缩进和换行输出，方便直接打开 JSON 查看。
     */
    private fun writeChapterCacheObject(file: File, source: JSONObject) {
        val ordered = JSONObject()
        ordered.put("title", source.opt("title") ?: "")
        if (source.has("totalDialogs")) {
            ordered.put("totalDialogs", source.opt("totalDialogs") ?: 0)
        }
        val results = source.optJSONObject("results") ?: JSONObject()
        ordered.put("results", sortResultsByNumericKey(results))
        val reserved = setOf("title", "totalDialogs", "results")
        val extraKeys = source.keys()
        while (extraKeys.hasNext()) {
            val key = extraKeys.next()
            if (key !in reserved) ordered.put(key, source.opt(key) ?: JSONObject.NULL)
        }
        val text = runCatching { ordered.toString(2) }.getOrDefault(ordered.toString())
        writeAtomic(file, text)
    }

    private fun sortResultsByNumericKey(results: JSONObject): JSONObject {
        val keys = mutableListOf<String>()
        val it = results.keys()
        while (it.hasNext()) keys.add(it.next())
        val numericKeys = keys.filter { it.toIntOrNull() != null }.sortedBy { it.toInt() }
        val otherKeys = keys.filter { it.toIntOrNull() == null }.sorted()
        val sorted = JSONObject()
        for (key in numericKeys + otherKeys) {
            sorted.put(key, results.get(key))
        }
        return sorted
    }

    private fun writeAtomic(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (file.exists() && !file.delete()) {
                file.writeText(text)
                tmp.delete()
                return@runCatching
            }
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }.onFailure {
            AppLog.put("全书分析: 写入 ${file.name} 失败$nl${it.localizedMessage}", it)
        }
    }

    private fun progressText(idx: Int, total: Int, detail: String): String {
        val analyzed = analyzedChapterCount.coerceIn(0, total.coerceAtLeast(1))
        return "已分析 $analyzed/$total 章 · 第${idx + 1}章 · $detail"
    }

    private fun upNotification(text: String) {
        statusText = text
        val total = analyzedTotalCount.coerceAtLeast(1)
        val analyzed = analyzedChapterCount.coerceIn(0, total)
        notificationBuilder.setProgress(total, analyzed, false)
        notificationBuilder.setContentText(text)
        notificationManager.notify(
            NotificationId.BookScriptAnalysisService,
            notificationBuilder.build()
        )
    }

    override fun startForegroundNotification() {
        upNotification("准备中…")
        startForeground(NotificationId.BookScriptAnalysisService, notificationBuilder.build())
    }
}
