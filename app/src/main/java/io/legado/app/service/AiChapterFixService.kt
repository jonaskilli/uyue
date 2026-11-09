package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 章节处理（去广告 / 错别字 / 书写错误）。
 *
 * 流程：
 *  1. 取目标章节正文（缓存优先，没有就下载），按阅读所见做一遍替换净化；
 *  2. 逐段编号 `【1】【2】…` 交给大模型，要求**只**返回需要修改的段落，
 *     且必须是 JSON：`{"段落序号": "修正后的段落文本"}`；
 *  3. 解析 JSON，按序号**原位替换**。值里允许带换行：
 *     一段被改写/扩写成多段时，会按位置展开成多段；返回空串表示删掉该段；
 *  4. 重新拼成正文写回缓存，并同步刷新「朗读文本」data.json。
 *
 * 处理结果按「已修复 / 无需修改 / 失败」记入 [AiChapterFixStore]，
 * 前两者不再重复调用 AI，失败的下次仍会重试。
 */
object AiChapterFixService {

    private const val TAG = "AiChapterFixService"

    /** 单次最多处理的章节数量 */
    const val MAX_COUNT = 50

    /** 并发请求数上限 */
    const val MAX_CONCURRENCY = 20

    /** 兜底判定「单键值吞并了别的段落」时被比对段落的最小长度，避免短对话误判 */
    private const val MIN_SWALLOW_LEN = 8

    private val PROMPT_BLOCK_REGEX = Regex("^【\\d+】")

    /**
     * 返回格式硬性契约：无论任务提示词怎么改，这一段都会追加进请求。
     * 只约定 JSON 怎么填、段落怎么落位，不干涉要修什么。
     */
    private val FORMAT_CONTRACT = """
        【返回格式契约（必须严格遵守）】
        - 只输出一个 JSON 对象，不要解释，不要 markdown 代码块，不要多余文字。
        - 键是段落编号（字符串），值是该段修改后的完整文本。
        - 没有出现在 JSON 里的段落，程序会原样保留。
        - 修改单段："3":"修改后的完整文本"。
        - 删除整段："3":""。
        - 一段拆成多段："3":"第一小段\n第二小段"（用 \n 分隔）。
        - 合并连续的若干段：键写成「开始号-结束号」，例如 "3-5" 表示把第 3、4、5 段合并成一段，值是合并后的完整文本。程序会自动把这几段替换成这个值，不需要再为被合并的段单独写 ""。合并的段落必须连续，不连续的分开写。
        - 除合并键外，键必须是单个段落编号。
        - 整章无需修改就输出 {}。
    """.trimIndent()

    /** 默认模板的固定 id，首次初始化时使用 */
    const val DEFAULT_TEMPLATE_ID = 1L

    /** 内置默认提示词：只负责修错误、去广告和翻页提示；需要扩写请自行修改本提示词 */
    val DEFAULT_PROMPT = """
        你是小说正文校对助手。请检查下面编号的每一段【正文段落】，修正以下问题：
        1. 广告、推广、求票、求关注、微信公众号/QQ群/网址/作者说等与正文无关的内容——整段删除；
        2. 翻页提示、分页提示，例如「本章未完，请点击下一页」「当前章节已完，请点击下一页」「未完待续」「请翻页继续阅读」等——整段删除；
        3. 错别字、多字、漏字、乱码、标点误用、明显的书写错误——改正。

        【返回格式规范】
        - 只输出一个 JSON 对象，键是段落编号（字符串，必须与给出的编号一致），值是该段修正后的完整文本。
        - 只输出需要修改的段落；没有变化的段落不要出现在 JSON 里（程序会原样保留）。
        - 删除整段：该编号的值写空字符串 ""。
        - 一段拆成多段：值里用 \n 分隔。
        - 合并连续的若干段：键写成「开始号-结束号」，例如 "3-5" 表示把第 3、4、5 段合并；值写合并后的完整文本。程序会自动把这几段替换成这个值，不要再给被合并的段单独写 ""。合并的段落必须连续。
        - 除合并键外，键必须是单个段落编号。
        - 如果整章都没有问题，输出 {}。

        示例：{"3":"修正后的第三段","7":"拆出的第一小段\n拆出的第二小段","4-6":"第4到6段合并后的完整文本","12":""}
    """.trimIndent()

    /**
     * @param changedIndexes 正文真正被改写过的章节下标，调用方据此刷新阅读界面
     */
    data class FixResult(
        val success: Boolean,
        val message: String,
        val changedIndexes: List<Int> = emptyList()
    )

    private enum class Outcome { FIXED, CLEAN, SKIPPED, FAILED }

    private data class OneOutcome(val outcome: Outcome, val message: String)

    /** 多组 API 轮换计数器：每次请求（含重试）按顺序取下一组 */
    private val apiCounter = AtomicInteger(0)

    // ---------- 书籍绑定（一本书绑定一个模板，未绑定使用默认模板） ----------

    private fun bindKey(bookName: String, bookUrl: String): String? = when {
        bookName.isNotBlank() -> PreferKey.aiChapterFixTemplateBindPrefix + bookName
        bookUrl.isNotBlank() -> PreferKey.aiChapterFixTemplateBindPrefix + bookUrl
        else -> null
    }

    /** 读取书籍绑定的模板 id，0 表示未绑定（使用默认模板） */
    fun getBoundTemplateId(bookName: String, bookUrl: String): Long {
        val key = bindKey(bookName, bookUrl) ?: return 0L
        return appCtx.getPrefString(key, "")?.toLongOrNull() ?: 0L
    }

    /** 设置书籍绑定的模板 id（单选：直接覆盖），null 表示解绑回到默认模板 */
    fun setBoundTemplateId(bookName: String, bookUrl: String, templateId: Long?) {
        val key = bindKey(bookName, bookUrl) ?: return
        if (templateId == null) {
            appCtx.defaultSharedPreferences.edit().remove(key).apply()
        } else {
            appCtx.putPrefString(key, templateId.toString())
        }
    }

    /** 删除模板时清理所有书籍对该模板的绑定 */
    fun unbindTemplateFromAllBooks(templateId: Long) {
        val prefs = appCtx.defaultSharedPreferences
        val editor = prefs.edit()
        var affected = 0
        for ((key, value) in prefs.all) {
            if (!key.startsWith(PreferKey.aiChapterFixTemplateBindPrefix)) continue
            if ((value as? String)?.toLongOrNull() == templateId) {
                editor.remove(key)
                affected++
            }
        }
        if (affected > 0) editor.apply()
    }

    /**
     * 获取书籍当前生效的模板：绑定的模板优先，其次默认模板，
     * 都不存在时返回内置默认模板（不落库）。
     * 这是给运行时（自动处理）用的只读版本，可能在主线程调用，不写数据库。
     */
    fun getEffectiveTemplate(bookName: String, bookUrl: String): AiChapterFixTemplate {
        val boundId = getBoundTemplateId(bookName, bookUrl)
        if (boundId > 0) {
            appDb.aiChapterFixTemplateDao.getById(boundId)?.let { return it }
        }
        return appDb.aiChapterFixTemplateDao.getDefault() ?: builtinDefaultTemplate()
    }

    /**
     * 与 [getEffectiveTemplate] 相同，但保证返回的是已落库的模板（id > 0）。
     * 绑定 id 失效时顺手解绑；默认模板不存在时补建，
     * 避免「显示内置默认模板、保存却写不进去」以及异步初始化竞态。
     * 需在 IO 线程调用。
     */
    fun getOrCreateEffectiveTemplate(
        bookName: String,
        bookUrl: String
    ): AiChapterFixTemplate {
        val boundId = getBoundTemplateId(bookName, bookUrl)
        if (boundId > 0) {
            appDb.aiChapterFixTemplateDao.getById(boundId)?.let { return it }
            setBoundTemplateId(bookName, bookUrl, null)
        }
        return getOrCreateDefaultTemplate()
    }

    /** 取得（必要时补建）默认模板；需在 IO 线程调用 */
    fun getOrCreateDefaultTemplate(): AiChapterFixTemplate {
        val dao = appDb.aiChapterFixTemplateDao
        dao.getDefault()?.let { return it }
        // id=1 可能被备份恢复成普通模板：直接复用并标记为默认，避免 REPLACE 覆盖用户配置
        val existing = dao.getById(DEFAULT_TEMPLATE_ID)
        val template = if (existing != null) {
            existing.copy(
                isDefault = true,
                name = existing.name.ifBlank { "默认模板" },
                lastUpdateTime = System.currentTimeMillis()
            )
        } else {
            builtinDefaultTemplate().copy(
                id = DEFAULT_TEMPLATE_ID,
                isDefault = true,
                lastUpdateTime = System.currentTimeMillis()
            )
        }
        // REPLACE 与 App 启动时的默认模板初始化并发也只会留下同一条 id=1 记录
        dao.insert(template)
        return dao.getDefault() ?: template
    }

    /** 内置默认模板（兜底）：字段取自旧版全局配置，保证老用户升级后配置不丢 */
    fun builtinDefaultTemplate(): AiChapterFixTemplate = AiChapterFixTemplate(
        id = 0L,
        name = "默认模板",
        apiConfig = AppConfig.aiChapterFixApi,
        prompt = AppConfig.aiChapterFixPrompt,
        chapterCount = AppConfig.aiChapterFixCount,
        maxLength = AppConfig.aiChapterFixMaxLength,
        retryCount = AppConfig.aiChapterFixRetryCount,
        timeoutSeconds = AppConfig.aiChapterFixTimeoutSeconds,
        contextLength = AppConfig.aiChapterFixContextLength,
        isDefault = true
    )

    // ---------- 自动处理开关（按书生效） ----------

    private fun autoFixKey(bookName: String, bookUrl: String): String? = when {
        bookName.isNotBlank() -> PreferKey.aiChapterFixAutoBookPrefix + bookName
        bookUrl.isNotBlank() -> PreferKey.aiChapterFixAutoBookPrefix + bookUrl
        else -> null
    }

    /**
     * 当前书是否开启自动处理：只按书读取，未单独设置过一律关闭。
     * 旧版全局开关（包含此前误写入的全局值）直接丢弃并清理，
     * 绝不把它继承给当前书或之后新打开的书。
     */
    fun isAutoFixEnabled(bookName: String, bookUrl: String): Boolean {
        val key = autoFixKey(bookName, bookUrl) ?: return false
        val prefs = appCtx.defaultSharedPreferences
        if (prefs.contains(PreferKey.aiChapterFixAuto)) {
            prefs.edit().remove(PreferKey.aiChapterFixAuto).apply()
        }
        return appCtx.getPrefBoolean(key, false)
    }

    /** 设置当前书的自动处理开关（按书存储，切换书籍后互不影响） */
    fun setAutoFixEnabled(bookName: String, bookUrl: String, enabled: Boolean) {
        val key = autoFixKey(bookName, bookUrl) ?: return
        appCtx.putPrefBoolean(key, enabled)
    }

    /**
     * 处理若干章节。
     *
     * 提示信息一律用**章节标题**（章节序号只是列表下标，书里有卷、序章时和「第N章」并不一致，
     * 直接报下标会让人误解处理错了章节）。
     *
     * @param template 处理模板（接口/提示词/拆分/并发/重试/超时/上下文）
     * @param startIndex 起始章节下标
     * @param count 处理数量；>1 时视为批量，[onlyCached] 会跳过没缓存的章节
     * @param force 忽略「已处理过」的记录强制重跑（换了提示词，比如改成扩写时用）
     * @param onlyCached 只处理本地已有缓存的章节，没缓存的不处理（也不下载）
     */
    suspend fun fixChapters(
        book: Book,
        bookSource: BookSource?,
        template: AiChapterFixTemplate,
        startIndex: Int,
        count: Int,
        force: Boolean = false,
        onlyCached: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): FixResult = withContext(Dispatchers.IO) {
        val providers = parseApiConfigs(template.apiConfig)
        if (providers.isEmpty()) {
            return@withContext FixResult(
                false, "接口格式错误，应为：接口地址@@模型名字@@密钥（可@@追加多组轮换）"
            )
        }
        val effectivePrompt = template.prompt.ifBlank { DEFAULT_PROMPT }
        val n = template.chapterCount.coerceIn(1, MAX_COUNT)
        if (n == 1) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, startIndex)
                ?: return@withContext FixResult(false, "找不到对应的章节")
            val r = fixOneChapter(
                book, bookSource, chapter, startIndex,
                template, providers, effectivePrompt, force, onlyCached, onProgress
            )
            val changed = if (r.outcome == Outcome.FIXED) listOf(startIndex) else emptyList()
            return@withContext FixResult(r.outcome != Outcome.FAILED, r.message, changed)
        }
        var changed = 0
        var clean = 0
        var skipped = 0
        var failed = 0
        var firstFail = ""
        val changedIndexes = ArrayList<Int>()
        for (i in 0 until n) {
            val index = startIndex + i
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: break
            val r = fixOneChapter(
                book, bookSource, chapter, index,
                template, providers, effectivePrompt, force, onlyCached, onProgress
            )
            when (r.outcome) {
                Outcome.FIXED -> {
                    changed++
                    changedIndexes.add(index)
                }
                Outcome.CLEAN -> clean++
                Outcome.SKIPPED -> skipped++
                Outcome.FAILED -> {
                    failed++
                    if (firstFail.isEmpty()) firstFail = r.message
                }
            }
        }
        val sb = StringBuilder(
            "处理完成：修改 $changed 章、无需修改 $clean 章、跳过 $skipped 章、失败 $failed 章"
        )
        if (failed > 0 && firstFail.isNotEmpty()) {
            sb.append("\n").append(firstFail)
        } else if (changed == 0 && clean == 0) {
            sb.append("\n（没有可处理的章节：未缓存的不会处理，已处理过的会跳过）")
        }
        FixResult(failed == 0, sb.toString(), changedIndexes)
    }

    /**
     * 处理单个章节。出错原因不抛异常，统一用 [OneOutcome.message] 带回。
     */
    private suspend fun fixOneChapter(
        book: Book,
        bookSource: BookSource?,
        chapter: BookChapter,
        chapterIndex: Int,
        template: AiChapterFixTemplate,
        providers: List<BgmAIProvider>,
        prompt: String,
        force: Boolean,
        onlyCached: Boolean,
        onProgress: ((String) -> Unit)? = null
    ): OneOutcome {
        val title = chapter.title
        if (!force && AiChapterFixStore.isDone(book.bookUrl, chapterIndex)) {
            return OneOutcome(Outcome.SKIPPED, "《$title》已处理过，跳过")
        }
        val cached = BookHelp.getContent(book, chapter)
        if (cached.isNullOrBlank() && onlyCached) {
            return OneOutcome(Outcome.SKIPPED, "《$title》未缓存，跳过")
        }
        // 1. 原始正文：缓存优先，没有就下载
        val raw = cached?.takeIf { it.isNotBlank() }
            ?: getDownloadedContent(book, bookSource, chapter, chapterIndex)
            ?: return OneOutcome(Outcome.FAILED, "《$title》正文获取失败")
        // 2. 与阅读所见一致的净化（不含标题），并去掉展示用的段首缩进
        val indent = ReadBookConfig.paragraphIndent
        val paragraphs = runCatching {
            ContentProcessor.get(book)
                .getContent(book, chapter, raw, includeTitle = false)
                .textList
                .map { if (indent.isNotEmpty() && it.startsWith(indent)) it.removePrefix(indent) else it }
                .filter { it.isNotBlank() }
        }.getOrElse {
            AppLog.put("$TAG: 正文净化失败 ${it.localizedMessage}", it)
            return OneOutcome(Outcome.FAILED, "《$title》正文解析失败：${it.localizedMessage}")
        }
        if (paragraphs.isEmpty()) {
            return OneOutcome(Outcome.FAILED, "《$title》正文为空")
        }
        // 3. 上下文参考：与编号正文完全同口径（同样过一遍净化），
        //    避免 AI 同时看到「净化后正文 + 未净化原文」两种文本而误抄
        val contextLength = template.contextLength.coerceAtLeast(0)
        var contextBefore = ""
        var contextAfter = ""
        if (contextLength > 0) {
            if (chapterIndex > 0) {
                contextBefore = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex - 1)
                    ?.let { purifiedExcerpt(book, it, contextLength, tail = true) }
                    .orEmpty()
            }
            contextAfter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex + 1)
                ?.let { purifiedExcerpt(book, it, contextLength, tail = false) }
                .orEmpty()
        }
        // 4. 超过阈值的长章先均分成多块（阈值 <= 0 表示不拆分），逐块送 AI；
        //    块内编号独立，解析结果按块起始段偏移映射回整章段落；
        //    每一块都带紧邻的前后文：第一块上文是上一章结尾、最后一块下文是下一章开头，
        //    中间块的前后文取相邻块的内容——拆分本来就更容易打断情节
        val blocks = splitParagraphs(paragraphs, template.maxLength)
        val fixes = HashMap<Int, String>()
        if (blocks == null) {
            onProgress?.invoke(title)
            val (result, err) = callAndParse(
                providers, prompt, paragraphs, title, contextBefore, contextAfter,
                template.retryCount, template.timeoutSeconds
            )
            if (result == null) {
                AiChapterFixStore.mark(book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_ERROR)
                return OneOutcome(Outcome.FAILED, "《$title》$err")
            }
            fixes.putAll(result)
        } else {
            // 并发处理：每块独立请求（块内编号独立、上下文取自相邻块固定内容，与请求顺序无关），
            // 用信号量限制同时进行的请求数；全部块成功才合并写回，任何一块失败整章失败
            val concurrency = template.concurrency.coerceIn(1, MAX_CONCURRENCY)
            val semaphore = Semaphore(concurrency)
            val completed = AtomicInteger(0)
            // 各块在整章段落中的起始偏移
            val offsets = IntArray(blocks.size)
            var offsetAcc = 0
            blocks.forEachIndexed { i, block ->
                offsets[i] = offsetAcc
                offsetAcc += block.size
            }
            val results = coroutineScope {
                blocks.mapIndexed { blockIndex, block ->
                    async {
                        semaphore.withPermit {
                            val label = "$title(${blockIndex + 1}/${blocks.size})"
                            val before = if (blockIndex == 0) contextBefore
                                else blocks[blockIndex - 1].joinToString("\n").takeLast(contextLength)
                            val after = if (blockIndex == blocks.size - 1) contextAfter
                                else blocks[blockIndex + 1].joinToString("\n").take(contextLength)
                            val (result, err) = callAndParse(
                                providers, prompt, block, label, before, after,
                                template.retryCount, template.timeoutSeconds
                            )
                            onProgress?.invoke("$title 已完成 ${completed.incrementAndGet()}/${blocks.size}")
                            (blockIndex to result) to err
                        }
                    }
                }.awaitAll()
            }
            for ((pair, err) in results) {
                val (blockIndex, result) = pair
                if (result == null) {
                    AiChapterFixStore.mark(book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_ERROR)
                    return OneOutcome(
                        Outcome.FAILED, "《$title》第 ${blockIndex + 1}/${blocks.size} 块$err"
                    )
                }
                result.forEach { (index, text) -> fixes[offsets[blockIndex] + index] = text }
            }
        }
        // 7. 以「净化后段落列表」为唯一基准落位。只规整返回格式，不评判内容：
        //    空串删段、\n 拆段、扩写/改写原样保留；
        //    单键若吞并了别的段落，静默补删除，防 AI 没按合并键写时产生重复。
        val effectiveFixes = normalizeFixes(paragraphs, fixes, title)
        val newParagraphs = ArrayList<String>(paragraphs.size)
        paragraphs.forEachIndexed { i, p ->
            val fixed = effectiveFixes[i + 1]
            if (fixed == null) {
                newParagraphs.add(p)
                return@forEachIndexed
            }
            if (fixed.isBlank()) return@forEachIndexed   // 空串 = 删除该段
            fixed.split("\n")
                .map { cleanFixedLine(it, indent) }
                .map { stripPromptMark(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .forEach { newParagraphs.add(it) }
        }
        if (newParagraphs == paragraphs) {
            // AI 回了内容但实际没改动，按「无需修改」记，避免以后重复花钱
            AiChapterFixStore.mark(book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_CLEAN)
            return OneOutcome(Outcome.CLEAN, "《$title》检查完毕：无需修改")
        }
        // 8. 整章写回：AI 的修正值本就是净化后的段落文本，必须整份落回同一口径，
        //    不能把净化后的段落混进未净化的原文行里（两者行数/文字不对应会错位）
        val newContent = newParagraphs.joinToString("\n")
        BookHelp.saveText(book, chapter, newContent)
        AiChapterFixStore.mark(
            book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_FIXED, effectiveFixes.size
        )
        // 9. 同步刷新朗读用的 data.json
        kotlin.runCatching { ReadBook.saveTtsTextJson(force = true) }
            .onFailure { AppLog.put("$TAG: 刷新朗读JSON失败 ${it.localizedMessage}", it) }
        return OneOutcome(
            Outcome.FIXED,
            "《$title》已处理${if (blocks != null) "（分 ${blocks.size} 块）" else ""}：修改 ${effectiveFixes.size} 段"
        )
    }

    /** 与编号正文完全同口径地净化相邻章，再截取前/后若干字作为上下文参考 */
    private fun purifiedExcerpt(
        book: Book,
        chapter: BookChapter,
        length: Int,
        tail: Boolean
    ): String {
        val raw = BookHelp.getContent(book, chapter) ?: return ""
        if (raw.isBlank()) return ""
        val text = runCatching {
            ContentProcessor.get(book)
                .getContent(book, chapter, raw, includeTitle = false)
                .textList.joinToString("\n")
        }.getOrDefault(raw)
        return if (tail) text.takeLast(length) else text.take(length)
    }

    /** 清掉 AI 返回行里的段首缩进与首尾空白 */
    private fun cleanFixedLine(line: String, indent: String): String {
        var t = line.trim()
        if (indent.isNotEmpty() && t.startsWith(indent)) t = t.removePrefix(indent)
        return t.trim()
    }

    /** 剥离模型误带进来的格式标记，正文内容原样保留；整行是提示词块标题时返回空串 */
    private fun stripPromptMark(line: String): String {
        val t = line.trim()
        if (t.startsWith("【上文") || t.startsWith("【后续") ||
            t.startsWith("【正文段落")
        ) return ""
        return PROMPT_BLOCK_REGEX.replace(t, "").trim()
    }

    /**
     * 静默兜底：正常合并请让 AI 用 "3-5" 这类合并键。若它仍按老习惯把某段原文
     * 并进单段的值里、又没给那段发删除指令，就自动补一个 ""，避免该段被原样保留后重复。
     * 只补格式，不丢弃任何 AI 内容（扩写、改写、新增一律放行）。
     */
    private fun normalizeFixes(
        paragraphs: List<String>,
        fixes: Map<Int, String>,
        title: String
    ): Map<Int, String> {
        val result = HashMap(fixes)
        val normalized = paragraphs.map { it.trim() }
        for ((index, value) in fixes) {
            if (value.isBlank()) continue
            val text = value.trim()
            for (j in normalized.indices) {
                if (j + 1 == index) continue
                val other = normalized[j]
                if (other.length < MIN_SWALLOW_LEN) continue
                if (!text.contains(other)) continue
                if (result.containsKey(j + 1)) continue
                result[j + 1] = ""
                AppLog.putDebug("$TAG: 《$title》第${j + 1}段被第${index}段并入，自动补删除")
            }
        }
        return result
    }

    /**
     * 调用大模型并解析一块（或整章）段落的修正结果。
     * 请求失败或返回无法解析时，按重试次数自动重试，**每次请求（含重试）轮换下一组 API**。
     *
     * @param label 日志用的标识（章节标题，分块时带块号）
     * @param contextBefore 正文上方附带的前文（上一章结尾或前一块结尾，参考，不会被修改）
     * @param contextAfter 正文下方附带的后文（下一章开头或后一块开头，参考，不会被修改）
     * @param retryCount 失败重试次数
     * @param timeoutSeconds 单次请求整体超时（秒）
     * @return 序号 -> 修正后文本；重试后仍失败时第一个值为 null，第二个值是失败原因
     */
    private suspend fun callAndParse(
        providers: List<BgmAIProvider>,
        prompt: String,
        paragraphs: List<String>,
        label: String,
        contextBefore: String = "",
        contextAfter: String = "",
        retryCount: Int = 0,
        timeoutSeconds: Int = 180
    ): Pair<Map<Int, String>?, String> {
        // 逐段编号
        val numbered = StringBuilder()
        paragraphs.forEachIndexed { i, p -> numbered.append("【${i + 1}】").append(p).append('\n') }
        // 组装提示词：上下文只是参考，真正要修改的只有编号的正文段落
        val fullPrompt = buildString {
            append(prompt)
            append("\n\n")
            append(FORMAT_CONTRACT)
            if (contextBefore.isNotBlank()) {
                append("\n\n【上文（前文情节，仅供理解上下文衔接，不要修改，也不要输出到结果里）】\n")
                append(contextBefore)
            }
            append("\n\n【正文段落（共${paragraphs.size}段，编号即段落序号，勿改动编号顺序）】\n")
            append(numbered)
            if (contextAfter.isNotBlank()) {
                append("\n\n【后续（后文情节，仅供理解上下文衔接，不要修改，也不要输出到结果里）】\n")
                append(contextAfter)
            }
        }
        val retries = retryCount.coerceAtLeast(0)
        val timeout = timeoutSeconds.coerceAtLeast(10)
        var lastError = ""
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                AppLog.put("$TAG: 《$label》请求失败，第 $attempt/$retries 次重试")
            }
            // 每次请求（含重试）按顺序轮换一组 API：分散限流压力，失败自动换下一组
            val apiIndex = apiCounter.getAndIncrement().mod(providers.size)
            val provider = providers[apiIndex]
            AppLog.putDebug(
                "$TAG: 《$label》发起AI请求（第${attempt + 1}/${retries + 1}次，" +
                        "API第${apiIndex + 1}/${providers.size}组），超时设置=${timeout}秒"
            )
            // 调用大模型（不限制 max_tokens，否则长回复会被截断成半截 JSON；
            // 超时时间从模板读取，章节大、AI 生成慢时可调大等待）
            val response = try {
                BgmAIService.callOpenAIApi(
                    provider, fullPrompt, maxTokens = null,
                    callTimeoutSeconds = timeout.toLong()
                )
            } catch (e: Exception) {
                // 异常类型 + 实际生效的超时值一起记录，便于区分客户端超时与服务端断开
                AppLog.put(
                    "$TAG: 《$label》AI调用失败(${e.javaClass.simpleName})：${e.localizedMessage}" +
                            "，API第${apiIndex + 1}组，超时设置=${timeout}秒"
                )
                lastError = "AI调用失败：${e.localizedMessage}（超时设置${timeout}秒）"
                return@repeat
            }
            AppLog.putDebug("$TAG: 《$label》AI返回：$response")
            // 解析 JSON，无法解析视为失败，与调用失败一样参与重试
            val fixes = parseFixes(response, paragraphs.size)
            if (fixes == null) {
                // 把 AI 的原始返回打进应用日志，便于区分是返回内容有问题还是解析有问题
                AppLog.put("$TAG: 《$label》AI返回内容无法解析（共${response.length}字符），原始返回如下：\n$response")
                lastError = "AI返回内容无法解析，已标记为待重试（原始返回见应用日志）"
                return@repeat
            }
            return fixes to ""
        }
        return null to lastError
    }

    /**
     * 把段落列表按阈值拆成若干块（总字数不超过阈值时不拆分）。
     *
     * 拆分规则参考本地书籍「拆分超长章节」的就近换行下刀，并做均分修正：
     *  - 块数 N = ⌈总字数 ÷ 阈值⌉，均分目标 = 总字数 ÷ N；
     *  - 按段落累加字数，加入下一段会超过阈值（硬顶）或当前块已达均分目标时，
     *    在段落边界（换行处）切块，绝不切断段落内部；
     *  - 单段自身就超过阈值的，单独成一块。
     *
     * @return 不需要拆分（阈值 <= 0 / 未超阈值 / 只切出一块）时返回 null
     */
    internal fun splitParagraphs(
        paragraphs: List<String>,
        maxLength: Int
    ): List<List<String>>? {
        if (maxLength <= 0) return null
        val total = paragraphs.sumOf { it.length }
        if (total <= maxLength) return null
        val blockCount = (total + maxLength - 1) / maxLength
        val blocks = ArrayList<List<String>>(blockCount)
        var current = ArrayList<String>()
        var currentLen = 0
        for (paragraph in paragraphs) {
            val paragraphLen = paragraph.length
            if (current.isNotEmpty() && (currentLen + paragraphLen > maxLength ||
                    currentLen.toLong() * blockCount >= total.toLong())
            ) {
                blocks.add(current)
                current = ArrayList()
                currentLen = 0
            }
            current.add(paragraph)
            currentLen += paragraphLen
        }
        if (current.isNotEmpty()) blocks.add(current)
        return if (blocks.size > 1) blocks else null
    }

    /** 缓存里没有时联网下载（同时落缓存） */
    private suspend fun getDownloadedContent(
        book: Book,
        bookSource: BookSource?,
        chapter: BookChapter,
        chapterIndex: Int
    ): String? {
        val source = bookSource ?: return null
        val nextUrl = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex + 1)?.url
        return try {
            WebBook.getContentAwait(source, book, chapter, nextUrl, needSave = true)
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            AppLog.put("$TAG: 下载正文失败 ${e.localizedMessage}", e)
            null
        }
    }

    /**
     * 解析 AI 返回的 JSON。
     *
     * 兼容几种常见写法：
     *  - `{"3":"文本"}` 或 `{"3":{"text":"文本"}}` 或 `{"3":["段1","段2"]}`
     *  - 数组形式 `[{"index":3,"text":"文本"}]`
     *  - 外面裹了 ```json 代码块或前后多说了几句解释（只截取最外层 JSON）
     *
     * @return 序号 -> 修正后文本；**无法解析时返回 null**（视为失败，下次重试）；
     *         解析成功但没有条目（如 `{}`）返回空 Map，表示 AI 判定无需修改。
     */
    private fun parseFixes(response: String, paragraphCount: Int): Map<Int, String>? {
        val jsonText = extractJson(response) ?: return null
        return try {
            val result = HashMap<Int, String>()
            if (jsonText.startsWith("[")) {
                val arr = JSONArray(jsonText)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawKey = obj.optString(
                        "index", obj.optString("num", obj.optString("id", ""))
                    )
                    val text = valueToText(obj.opt("text") ?: obj.opt("content"))
                    putFix(result, rawKey, text, paragraphCount)
                }
            } else {
                val obj = JSONObject(jsonText)
                for (key in obj.keys()) {
                    putFix(result, key, valueToText(obj.opt(key)), paragraphCount)
                }
            }
            result
        } catch (e: Exception) {
            AppLog.put("$TAG: JSON解析失败 ${e.localizedMessage}")
            null
        }
    }

    private fun valueToText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is JSONObject -> value.optString("text").ifBlank { value.optString("content") }
        is JSONArray -> (0 until value.length()).joinToString("\n") { value.optString(it) }
        else -> value.toString()
    }

    /**
     * 把一条返回值落到「段落号 -> 文本」表里。
     * 单段键直接落；"3-5" / "3,4,5" 这种合并键一看到多个连续段号就知道是合并：
     * 首段放合并后的完整文本，其余段由代码自动置空删除，AI 不必再单独发删除指令。
     */
    private fun putFix(
        result: HashMap<Int, String>,
        rawKey: String,
        text: String,
        paragraphCount: Int
    ) {
        val key = rawKey.trim()
        val single = key.toIntOrNull()
        if (single != null) {
            if (single in 1..paragraphCount) result[single] = text
            return
        }
        val merged = parseMergeKey(key, paragraphCount)
        if (merged.size < 2) {
            AppLog.put("$TAG: 忽略不合规的键 $key")
            return
        }
        result[merged.first()] = text
        merged.drop(1).forEach { result[it] = "" }
    }

    /** 解析合并键："3-5" 或 "3,4,5"，要求至少两段、编号连续且都在有效范围内 */
    private fun parseMergeKey(key: String, paragraphCount: Int): List<Int> {
        val nums: List<Int> = when {
            key.contains('-') -> {
                val parts = key.split('-')
                if (parts.size != 2) return emptyList()
                val a = parts[0].trim().toIntOrNull() ?: return emptyList()
                val b = parts[1].trim().toIntOrNull() ?: return emptyList()
                if (a < 1 || b < a || b > paragraphCount) return emptyList()
                (a..b).toList()
            }

            key.contains(',') -> key.split(',').mapNotNull { it.trim().toIntOrNull() }
            else -> emptyList()
        }
        if (nums.size < 2) return emptyList()
        if (nums.any { it !in 1..paragraphCount }) return emptyList()
        // 合并只允许连续段落
        if (nums != (nums.first()..nums.last()).toList()) return emptyList()
        return nums
    }

    /** 截取最外层 JSON（去掉 ``` 代码块标记和前后多余的说明文字） */
    private fun extractJson(text: String): String? {
        val body = text.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val startObj = body.indexOf('{')
        val startArr = body.indexOf('[')
        val start = when {
            startObj < 0 -> startArr
            startArr < 0 -> startObj
            else -> minOf(startObj, startArr)
        }
        if (start < 0) return null
        val endChar = if (body[start] == '[') ']' else '}'
        val end = body.lastIndexOf(endChar)
        if (end <= start) return null
        return body.substring(start, end + 1)
    }

    /**
     * 解析接口配置，支持多组轮换，3 段一组（可无限追加）：
     * 接口地址@@模型名@@密钥@@接口地址@@模型名@@密钥@@...
     * 尾部凑不满 3 段的残余：2 段视为省略密钥的一组；1 段不完整，忽略并提示。
     * 兼容旧的单组 2 段写法（接口地址@@模型名）。
     */
    internal fun parseApiConfigs(config: String): List<BgmAIProvider> {
        val parts = config.trim().split("@@").map { it.trim() }
        if (parts.size < 2 || parts[0].isEmpty()) return emptyList()
        val providers = ArrayList<BgmAIProvider>()
        fun buildProvider(url: String, model: String, key: String): BgmAIProvider? {
            val normalized = url.let {
                when {
                    it.endsWith("/chat/completions") ->
                        it.removeSuffix("/chat/completions").removeSuffix("/")
                    it.endsWith("/") -> it.removeSuffix("/")
                    else -> it
                }
            }
            if (normalized.isEmpty() || model.isEmpty()) return null
            return BgmAIProvider(
                id = 0L,
                name = "AI章节处理",
                url = normalized,
                apiKey = key,
                modelId = model,
                enabled = true
            )
        }
        var i = 0
        // 完整组：每 3 段一组
        while (i + 3 <= parts.size) {
            buildProvider(parts[i], parts[i + 1], parts[i + 2])?.let { providers.add(it) }
            i += 3
        }
        // 尾部残余：2 段视为省略密钥的一组；1 段不完整，忽略并提示
        if (i < parts.size) {
            if (parts.size - i >= 2) {
                buildProvider(parts[i], parts[i + 1], "")?.let { providers.add(it) }
                i += 2
            }
            if (i < parts.size) {
                AppLog.put("$TAG: 接口配置尾部有一段不完整（缺少模型名），已忽略")
            }
        }
        return providers
    }
}
