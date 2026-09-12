package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

    /** 内置默认提示词：以后想让 AI 扩写/改写，直接改提示词即可，代码不用动 */
    val DEFAULT_PROMPT = """
        你是小说正文校对助手。请检查下面编号的每一段【正文段落】，并修正以下问题：
        1. 广告、推广、求票、求关注、微信公众号/QQ群/网址/作者说等与正文无关的内容——整段删除；
        2. 错别字、多字、漏字、乱码、标点误用、明显的书写错误——改正。

        严格按下面的要求输出：
        - 只输出一个 JSON 对象，不要解释，不要 markdown 代码块，不要多余文字。
        - 键是段落编号（字符串形式，必须与给出的编号一致），值是该段修正后的完整文本。
        - 只输出需要修改的段落；没有问题的段落不要出现在 JSON 里。
        - 整段广告需要删除时，该段落的值写空字符串 ""。
        - 值里允许包含换行符 \n：一段需要拆成多段时就用 \n 分隔。
        - 如果整章都没有问题，输出 {}。

        示例：{"3":"修正后的第三段文字","7":"拆出来的第一小段\n拆出来的第二小段","12":""}
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

    /**
     * 处理若干章节。
     *
     * 提示信息一律用**章节标题**（章节序号只是列表下标，书里有卷、序章时和「第N章」并不一致，
     * 直接报下标会让人误解处理错了章节）。
     *
     * @param startIndex 起始章节下标
     * @param count 处理数量；>1 时视为批量，[onlyCached] 会跳过没缓存的章节
     * @param apiConfig AI 接口配置，格式与「AI 切歌」一致：接口地址@@模型名@@密钥
     * @param prompt 用户提示词，为空时用 [DEFAULT_PROMPT]
     * @param force 忽略「已处理过」的记录强制重跑（换了提示词，比如改成扩写时用）
     * @param onlyCached 只处理本地已有缓存的章节，没缓存的不处理（也不下载）
     */
    suspend fun fixChapters(
        book: Book,
        bookSource: BookSource?,
        startIndex: Int,
        count: Int,
        apiConfig: String,
        prompt: String,
        force: Boolean = false,
        onlyCached: Boolean = false,
        onProgress: ((String) -> Unit)? = null
    ): FixResult = withContext(Dispatchers.IO) {
        val provider = parseApiConfig(apiConfig)
            ?: return@withContext FixResult(false, "接口格式错误，应为：接口地址@@模型名字@@密钥")
        val effectivePrompt = prompt.ifBlank { DEFAULT_PROMPT }
        val n = count.coerceIn(1, MAX_COUNT)
        if (n == 1) {
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, startIndex)
                ?: return@withContext FixResult(false, "找不到对应的章节")
            val r = fixOneChapter(
                book, bookSource, chapter, startIndex,
                provider, effectivePrompt, force, onlyCached, onProgress
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
                provider, effectivePrompt, force, onlyCached, onProgress
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
        provider: BgmAIProvider,
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
        // 3. 上下文参考：正文上、下分别附上一章结尾 / 下一章开头该字数的内容，
        //    供 AI 理解断章处的情节；只用已缓存的正文，不额外下载
        val contextLength = AppConfig.aiChapterFixContextLength.coerceAtLeast(0)
        var contextBefore = ""
        var contextAfter = ""
        if (contextLength > 0) {
            if (chapterIndex > 0) {
                contextBefore = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex - 1)
                    ?.let { BookHelp.getContent(book, it) }
                    ?.takeLast(contextLength)
                    .orEmpty()
            }
            contextAfter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex + 1)
                ?.let { BookHelp.getContent(book, it) }
                ?.take(contextLength)
                .orEmpty()
        }
        // 4. 超过阈值的长章先均分成多块（阈值 <= 0 表示不拆分），逐块送 AI；
        //    块内编号独立，解析结果按块起始段偏移映射回整章段落；
        //    每一块都带紧邻的前后文：第一块上文是上一章结尾、最后一块下文是下一章开头，
        //    中间块的前后文取相邻块的内容——拆分本来就更容易打断情节
        val blocks = splitParagraphs(paragraphs, AppConfig.aiChapterFixMaxLength)
        val fixes = HashMap<Int, String>()
        if (blocks == null) {
            onProgress?.invoke(title)
            val (result, err) = callAndParse(
                provider, prompt, paragraphs, title, contextBefore, contextAfter
            )
            if (result == null) {
                AiChapterFixStore.mark(book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_ERROR)
                return OneOutcome(Outcome.FAILED, "《$title》$err")
            }
            fixes.putAll(result)
        } else {
            var offset = 0
            blocks.forEachIndexed { blockIndex, block ->
                val label = "$title(${blockIndex + 1}/${blocks.size})"
                onProgress?.invoke(label)
                val before = if (blockIndex == 0) contextBefore
                    else blocks[blockIndex - 1].joinToString("\n").takeLast(contextLength)
                val after = if (blockIndex == blocks.size - 1) contextAfter
                    else blocks[blockIndex + 1].joinToString("\n").take(contextLength)
                val (result, err) = callAndParse(
                    provider, prompt, block, label, before, after
                )
                if (result == null) {
                    AiChapterFixStore.mark(book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_ERROR)
                    return OneOutcome(
                        Outcome.FAILED, "《$title》第 ${blockIndex + 1}/${blocks.size} 块$err"
                    )
                }
                result.forEach { (index, text) -> fixes[offset + index] = text }
                offset += block.size
            }
        }
        // 7. 按序号原位替换；值为空 = 删除该段，值含换行 = 一段展开成多段
        val newParagraphs = ArrayList<String>(paragraphs.size)
        paragraphs.forEachIndexed { i, p ->
            val fixed = fixes[i + 1]
            if (fixed == null) {
                newParagraphs.add(p)
            } else {
                fixed.split("\n")
                    .map { line ->
                        val t = line.trim()
                        if (indent.isNotEmpty() && t.startsWith(indent)) t.removePrefix(indent) else t
                    }
                    .filter { it.isNotEmpty() }
                    .forEach { newParagraphs.add(it) }
            }
        }
        val newContent = newParagraphs.joinToString("\n")
        if (newContent == paragraphs.joinToString("\n")) {
            // AI 回了内容但实际没改动，按「无需修改」记，避免以后重复花钱
            AiChapterFixStore.mark(book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_CLEAN)
            return OneOutcome(Outcome.CLEAN, "《$title》检查完毕：无需修改")
        }
        // 8. 写回缓存正文
        BookHelp.saveText(book, chapter, newContent)
        AiChapterFixStore.mark(
            book.bookUrl, chapterIndex, AiChapterFixStore.STATUS_FIXED, fixes.size
        )
        // 9. 同步刷新朗读用的 data.json
        kotlin.runCatching { ReadBook.saveTtsTextJson(force = true) }
            .onFailure { AppLog.put("$TAG: 刷新朗读JSON失败 ${it.localizedMessage}", it) }
        return OneOutcome(
            Outcome.FIXED,
            "《$title》已处理${if (blocks != null) "（分 ${blocks.size} 块）" else ""}：修改 ${fixes.size} 段"
        )
    }

    /**
     * 调用大模型并解析一块（或整章）段落的修正结果。
     * 请求失败或返回无法解析时，按「AI请求失败重试次数」自动重试。
     *
     * @param label 日志用的标识（章节标题，分块时带块号）
     * @param contextBefore 正文上方附带的前文（上一章结尾或前一块结尾，参考，不会被修改）
     * @param contextAfter 正文下方附带的后文（下一章开头或后一块开头，参考，不会被修改）
     * @return 序号 -> 修正后文本；重试后仍失败时第一个值为 null，第二个值是失败原因
     */
    private suspend fun callAndParse(
        provider: BgmAIProvider,
        prompt: String,
        paragraphs: List<String>,
        label: String,
        contextBefore: String = "",
        contextAfter: String = ""
    ): Pair<Map<Int, String>?, String> {
        // 逐段编号
        val numbered = StringBuilder()
        paragraphs.forEachIndexed { i, p -> numbered.append("【${i + 1}】").append(p).append('\n') }
        // 组装提示词：上下文只是参考，真正要修改的只有编号的正文段落
        val fullPrompt = buildString {
            append(prompt)
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
        val retries = AppConfig.aiChapterFixRetryCount.coerceAtLeast(0)
        val timeoutSeconds = AppConfig.aiChapterFixTimeoutSeconds.coerceAtLeast(10)
        var lastError = ""
        repeat(retries + 1) { attempt ->
            if (attempt > 0) {
                AppLog.put("$TAG: 《$label》请求失败，第 $attempt/$retries 次重试")
            }
            AppLog.putDebug("$TAG: 《$label》发起AI请求，超时设置=${timeoutSeconds}秒")
            // 调用大模型（不限制 max_tokens，否则长回复会被截断成半截 JSON；
            // 超时时间从设置读取，章节大、AI 生成慢时可调大等待）
            val response = try {
                BgmAIService.callOpenAIApi(
                    provider, fullPrompt, maxTokens = null,
                    callTimeoutSeconds = timeoutSeconds.toLong()
                )
            } catch (e: Exception) {
                // 异常类型 + 实际生效的超时值一起记录，便于区分客户端超时与服务端断开
                AppLog.put(
                    "$TAG: 《$label》AI调用失败(${e.javaClass.simpleName})：${e.localizedMessage}" +
                        "，超时设置=${timeoutSeconds}秒"
                )
                lastError = "AI调用失败：${e.localizedMessage}（超时设置${timeoutSeconds}秒）"
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
                    val index = obj.optInt("index", obj.optInt("num", obj.optInt("id", -1)))
                    if (index in 1..paragraphCount) {
                        result[index] = valueToText(obj.opt("text") ?: obj.opt("content"))
                    }
                }
            } else {
                val obj = JSONObject(jsonText)
                for (key in obj.keys()) {
                    val index = key.trim().toIntOrNull() ?: continue
                    if (index in 1..paragraphCount) {
                        result[index] = valueToText(obj.opt(key))
                    }
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
     * 解析接口配置，格式与「AI 切歌」一致：接口地址@@模型名@@密钥
     */
    private fun parseApiConfig(config: String): BgmAIProvider? {
        val parts = config.trim().split("@@")
        if (parts.size < 2) return null
        val url = parts[0].trim().let {
            when {
                it.endsWith("/chat/completions") ->
                    it.removeSuffix("/chat/completions").removeSuffix("/")
                it.endsWith("/") -> it.removeSuffix("/")
                else -> it
            }
        }
        if (url.isEmpty()) return null
        return BgmAIProvider(
            id = 0L,
            name = "AI章节处理",
            url = url,
            apiKey = if (parts.size >= 3) parts[2].trim() else "",
            modelId = parts[1].trim(),
            enabled = true
        )
    }
}
