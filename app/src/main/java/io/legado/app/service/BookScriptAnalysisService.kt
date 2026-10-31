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
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.toc.AiCacheFileUtil
import io.legado.app.utils.StringUtils
import org.json.JSONObject
import java.io.File
import io.legado.app.utils.servicePendingIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import splitties.systemservices.notificationManager

/**
 * 全书分析服务：调用朗读脚本把整本书按章节顺序"只分析不合成"跑一遍。
 *
 * 脚本的角色标注（`<<音色>>`）、别名合并（characterRecords.json）、章节缓存
 * （xiaoshuo/<书名>/<章节标题>.json）全部由脚本自身完成，本服务只负责：
 *  1. 按章节顺序逐章驱动；
 *  2. 【关键】每章分析前把 data.json 刷成"该章起的上下文窗口"
 *     （[ReadBook.saveTtsTextJsonFrom]）——脚本的章节定位完全依赖 data.json，
 *     分析到哪个位置就要保存哪个位置的上下文，否则脚本拿不到章节/对话序号；
 *  3. 逐段调用与朗读完全相同的脚本链路（同一批启用脚本、同一组 bindings），
 *     返回的标注文本直接丢弃，不触碰任何 TTS/播放链路。
 *
 * 冲突保护：data.json 是朗读与分析共享的文件，分析期间若检测到朗读开启
 * （会覆盖 data.json），立即中止分析并记录日志。
 */
class BookScriptAnalysisService : BaseService() {

    companion object {
        const val EXTRA_START_INDEX = "startIndex"

        @Volatile
        var isRun = false
            private set

        /** 当前进度（章节下标） */
        @Volatile
        var progressIndex = 0
            private set

        /** 总章数 */
        @Volatile
        var totalCount = 0
            private set

        @Volatile
        var runningBookName: String? = null
            private set
    }

    private var analyzeJob: Job? = null

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.stop -> {
                analyzeJob?.cancel()
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
                startForegroundNotification()
                analyzeJob = lifecycleScope.launch(Dispatchers.IO) {
                    kotlin.runCatching {
                        runAnalysis(book.bookUrl, book.name, startIndex)
                    }.onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            AppLog.put("全书分析: 异常终止\n$it", it)
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
        super.onDestroy()
    }

    private suspend fun runAnalysis(bookUrl: String, bookName: String, startIndex: Int) {
        val total = ReadBook.chapterSize
        if (startIndex >= total) {
            AppLog.put("全书分析: 起始章超出一书范围，无需分析")
            return
        }
        totalCount = total
        AppLog.put("全书分析: 《$bookName》从第${startIndex + 1}章开始，共${total - startIndex}章")

        for (idx in startIndex until total) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            // data.json 共存策略：朗读进行时分析自动暂停（等待，不中止），
            // data.json 永远只属于当前活动方——朗读用它拿上下文、分析恢复时重写回分析位置
            waitForReading(idx, total, resumeRewrite = false)

            // 用户退出阅读界面（ReadBook.book 被清）或切换了书：立即中止，
            // 避免拿错书的正文/上下文
            val book = ReadBook.book
            if (book == null || book.bookUrl != bookUrl) {
                AppLog.put("全书分析: 当前书籍已退出或切换，分析中止")
                return
            }

            val ch = appDb.bookChapterDao.getChapter(bookUrl, idx) ?: continue
            val raw = BookHelp.getContent(book, ch)
            val clean = if (raw.isNullOrBlank()) "" else ReadBook.cleanContentForTts(book, ch, raw)
            if (clean.isBlank()) {
                progressIndex = idx + 1
                upNotification(progressText(idx, total, "空章节跳过"), idx + 1)
                continue
            }

            // 提速：脚本章节缓存已完整覆盖本章对话的（results 数 >= 对话数），
            // 整章直接跳过，不逐段重跑 JS/AI。部分覆盖的章节仍会跑——
            // 脚本内部对已缓存对话会命中缓存，只对新增名字调 AI
            val cachedResults = readScriptChapterCacheResults(book.name, ch.title)
            val dialogueCount = countDialogues(clean)
            if (cachedResults != null && dialogueCount > 0 && cachedResults >= dialogueCount) {
                AppLog.putDebug(
                    "全书分析: 第${idx + 1}章「${ch.title}」已有完整缓存($cachedResults/$dialogueCount)，跳过"
                )
                progressIndex = idx + 1
                upNotification(progressText(idx, total, "已有缓存，跳过"), idx + 1)
                continue
            }

            // 【关键】按分析位置刷新 data.json，脚本据此拿到章节/对话序号等上下文
            ReadBook.saveTtsTextJsonFrom(idx)

            val paragraphs = clean.split("\n").filter { it.isNotBlank() }
            var pIdx = 0
            while (pIdx < paragraphs.size) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()

                // 章内任意时刻朗读开启：暂停等待；朗读结束后 data.json 已被朗读
                // 重写过，恢复到本章分析位置再继续，当前段重新分析
                if (BaseReadAloudService.isRun) {
                    waitForReading(idx, total, resumeRewrite = true)
                    continue
                }

                // 只分析不合成：标注结果直接丢弃
                applyTtsScripts(paragraphs[pIdx])
                // 每段刷新一次：一章内的 AI 请求可能耗时数分钟，
                // 不刷新会像卡死（进度条按章节粒度，文字带段内进度）
                upNotification(
                    progressText(idx, total, "第${pIdx + 1}/${paragraphs.size}段"),
                    idx + 1
                )
                pIdx++
            }

            progressIndex = idx + 1
            upNotification(progressText(idx, total, ch.title.take(16)), idx + 1)
            if (idx % 10 == 0) {
                AppLog.putDebug("全书分析: 已完成第${idx + 1}章，本章${paragraphs.size}段")
            }
        }
        AppLog.put("全书分析: 《$bookName》全部分析完成，角色与别名结果已写入脚本章节缓存")
        // 撤掉常驻进度通知，发一条可划掉的完成通知
        notificationManager.cancel(NotificationId.BookScriptAnalysisService)
        NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_search)
            .setContentTitle("全书分析完成")
            .setContentText("《$bookName》角色与别名已写入脚本缓存，共 $total 章")
            .setAutoCancel(true)
            .build()
            .let {
                notificationManager.notify(
                    NotificationId.BookScriptAnalysisService + 1000,
                    it
                )
            }
    }

    /**
     * 读脚本的章节缓存结果条数（xiaoshuo/<书名>/<章节标题>.json 的 results 数）。
     * null 表示无缓存。文件命名与清洗规则复用 [AiCacheFileUtil]（与脚本一致）。
     */
    private fun readScriptChapterCacheResults(bookName: String, chapterTitle: String): Int? {
        return runCatching {
            val bookDir = AiCacheFileUtil.getBookDirByName(bookName)
            val file = File(
                bookDir,
                AiCacheFileUtil.sanitizeChapterFileName(chapterTitle) + ".json"
            )
            if (!file.isFile) return null
            val obj = JSONObject(file.readText())
            obj.optJSONObject("results")?.length()
        }.getOrNull()
    }

    /** 章节对话数估算：中文左引号数（与脚本 extractDialogs 的引号切分口径一致） */
    private fun countDialogues(text: String): Int = text.count { it == '“' }

    private fun progressText(idx: Int, total: Int, detail: String): String {
        val percent = if (total > 0) (idx + 1) * 100 / total else 0
        return "第${idx + 1}/$total 章 ($percent%) · $detail"
    }

    /**
     * 朗读进行时挂起等待（不中止分析），朗读停止后自动返回。
     *
     * @param resumeRewrite true 表示朗读期间 data.json 已被朗读重写过，
     *        返回前恢复到 [idx] 的分析位置上下文；false 用于刚要开始新章时
     *        （data.json 随后本来就会按本章重写，无需恢复）。
     */
    private suspend fun waitForReading(idx: Int, total: Int, resumeRewrite: Boolean) {
        if (!BaseReadAloudService.isRun) return
        AppLog.put("全书分析: 朗读进行中，分析暂停（朗读停止后自动继续）")
        upNotification(progressText(idx, total, "朗读进行中，分析已暂停"), idx)
        while (BaseReadAloudService.isRun) {
            kotlinx.coroutines.delay(2000)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        }
        AppLog.put("全书分析: 朗读已停止，分析继续")
        if (resumeRewrite) {
            ReadBook.saveTtsTextJsonFrom(idx)
        }
    }

    private fun upNotification(text: String, doneChapters: Int = -1) {
        // 进度条：doneChapters >= 0 时按章节总数显示确定进度，否则转圈
        if (doneChapters >= 0 && totalCount > 0) {
            notificationBuilder.setProgress(totalCount, doneChapters, false)
        } else {
            notificationBuilder.setProgress(0, 0, true)
        }
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

    // ========== 朗读脚本执行（与 BaseReadAloudService.applyTtsScripts 同口径） ==========

    private val jsExtensions by lazy {
        object : JsExtensions {
            override fun getSource() = ReadAloud.httpTTS
            override fun getTag() = "TTS"
        }
    }

    private fun currentTtsEngineName(): String? {
        val ttsEngine = ReadAloud.ttsEngine
        return if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            "http:$ttsEngine"
        } else {
            val pkg = AppConfig.sysTtsPackageName
            if (pkg.isNullOrBlank()) null else "sys:$pkg"
        }
    }

    /**
     * 与朗读链路完全一致的脚本执行：启用的脚本按顺序跑，标注结果随返回值替换；
     * 引擎绑定过滤、bindings 键（java/cache/cookie/text/source/speakText）全部对齐。
     */
    private fun applyTtsScripts(text: String): String {
        if (text.isEmpty() || text == " ") return text
        val scripts = try {
            appDb.ttsScriptDao.all.filter { it.isEnabled }.sortedBy { it.order }
        } catch (e: Exception) {
            return text
        }
        if (scripts.isEmpty()) return text
        val currentEngineName = currentTtsEngineName()
        var result = text
        scripts.forEach { script ->
            if (script.code.isBlank()) return@forEach
            if (script.bindTtsEngines.isNotBlank()) {
                val boundEngines = script.bindTtsEngines.split(",").map { it.trim() }
                if (currentEngineName != null && !boundEngines.contains(currentEngineName)) {
                    return@forEach
                }
            }
            try {
                val jsResult = RhinoScriptEngine.run {
                    val bindings = buildScriptBindings { bindings ->
                        bindings["java"] = jsExtensions
                        bindings["cache"] = CacheManager
                        bindings["cookie"] = CookieStore
                        bindings["text"] = result
                        bindings["source"] = ReadAloud.httpTTS
                        bindings["speakText"] = text
                    }
                    eval(script.code, bindings)
                }
                if (jsResult != null) {
                    result = jsResult.toString()
                }
            } catch (e: Exception) {
                AppLog.put("全书分析: 脚本[${script.name}]执行错误", e)
            }
        }
        return result
    }
}
