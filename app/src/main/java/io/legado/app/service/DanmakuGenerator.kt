package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.model.DanmakuItem
import io.legado.app.ui.book.toc.AiCacheFileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一条 AI 弹幕。
 * @param para 触发段落下标（contentList 中的下标，段落开始朗读时发射）
 * @param text 弹幕文本
 */
data class DanmakuItem(
    val para: Int,
    val text: String
)

/**
 * AI 弹幕生成器（区间模式，与 AI 音效同款调度）：
 * 整章按字数切成区间，每个区间一次 AI 调用，弹幕锚定到区间内具体段落。
 * 记录按「段落 -> 文本列表」落盘 JSON 缓存，重读/重入区间直接复用不再请求。
 * 大模型通道复用 [BgmAIService.callOpenAIApi]（与 BGM 选曲共用同一个 Provider）。
 */
object DanmakuGenerator {

    private const val TAG = "DanmakuGenerator"

    /** 单条弹幕文本长度限制（过短无意义、过长飘不完） */
    private const val MIN_TEXT_LEN = 2
    private const val MAX_TEXT_LEN = 25

    /** 单个段落最多保留几条弹幕记录 */
    private const val MAX_RECORDS_PER_PARA = 2

    fun getCacheFile(bookName: String, chapterTitle: String): File =
        File(
            File(
                "/storage/emulated/0/Download/chajian/danmaku/" +
                    AiCacheFileUtil.sanitizeBookName(bookName)
            ).apply { mkdirs() },
            AiCacheFileUtil.sanitizeChapterFileName(chapterTitle) + ".json"
        )

    /** 删除某章弹幕缓存（强制重新生成时用） */
    fun deleteCache(bookName: String, chapterTitle: String): Boolean =
        runCatching { getCacheFile(bookName, chapterTitle).delete() }.getOrDefault(false)

    /**
     * 生成一个区间的弹幕（带段落级记录缓存）。
     * @param contentHash 整章内容 hash（章节内容变化时缓存自动失效）
     * @param paragraphs 本区间的段落（contentList 下标 -> 文本）
     * @param count 本区间目标条数
     * @return 本区间（含历史记录）的全部弹幕，按 para 升序
     */
    suspend fun generateRange(
        bookName: String,
        chapterTitle: String,
        contentHash: String,
        paragraphs: List<Pair<Int, String>>,
        count: Int
    ): List<DanmakuItem> = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext emptyList()
        val cacheFile = getCacheFile(bookName, chapterTitle)
        val records = readCacheRecords(cacheFile, contentHash)

        // 已有记录的段落直接复用，只对没记录的段落请求 AI（与 AI 音效同款）
        val todo = paragraphs.filter { (index, _) -> records[index].isNullOrEmpty() }
        if (todo.isNotEmpty()) {
            val items = request(bookName, chapterTitle, todo, count)
            items.forEach { item ->
                val list = records.getOrPut(item.para) { mutableListOf() }
                if (list.size < MAX_RECORDS_PER_PARA && item.text !in list) {
                    list.add(item.text)
                }
            }
            writeCacheRecords(cacheFile, contentHash, records)
            AppLog.put("AI弹幕: 区间[${paragraphs.first().first}~${paragraphs.last().first}] 生成${items.size}条")
        }

        paragraphs.flatMap { (index, _) ->
            records[index].orEmpty().map { DanmakuItem(index, it) }
        }.sortedBy { it.para }
    }

    /** 调用 LLM 为指定段落集合生成弹幕 */
    private suspend fun request(
        bookName: String,
        chapterTitle: String,
        paragraphs: List<Pair<Int, String>>,
        count: Int
    ): List<DanmakuItem> {
        val provider = appDb.bgmAIProviderDao.getEnabled()
        if (provider == null) {
            AppLog.put("AI弹幕: 未启用AI提供商，跳过生成")
            return emptyList()
        }
        val result = try {
            BgmAIService.callOpenAIApi(
                provider,
                buildPrompt(bookName, chapterTitle, count, paragraphs),
                // 登记在途 Call：连续失活 60 秒硬停止时可掐断，不必等 180 秒超时
                onCallCreated = DanmakuRuntime::registerCall            )
        } catch (e: Exception) {
            AppLog.put("AI弹幕: 请求失败 ${e.localizedMessage}")
            return emptyList()
        } finally {
            DanmakuRuntime.clearInFlight()        }
        val validParas = paragraphs.map { it.first }.toSet()
        return redistributeIfClustered(parseResult(result, validParas), validParas)
    }

    /** 组装提示词：段落带真实下标编号，para 照抄编号 */
    private fun buildPrompt(
        bookName: String,
        chapterTitle: String,
        count: Int,
        paragraphs: List<Pair<Int, String>>
    ): String {
        val sb = StringBuilder()
        paragraphs.forEach { (index, text) ->
            sb.append("【段落").append(index).append("】").append(text.trim().take(160)).append('\n')
        }
        return """你是小说读者弹幕生成器。下面是《$bookName》章节「$chapterTitle」的一部分段落，每段前有【段落N】编号。

## 任务
站在读者角度，生成${count}条弹幕评论，跟着剧情走，在合适的段落出现。

## 要求
1. 弹幕必须贴合该段落的具体剧情、对话或情绪（吐槽、感叹、期待、心疼角色、玩梗均可），不要剧透后文。
2. 每条弹幕8~16个字，口语化，简短有力，不要出现"第几段"之类的字眼。
3. para 填该条弹幕对应段落开头的编号，必须照抄正文里【段落N】中的N。
   注意 N 是段落编号（通常是较大的数字，如 3、47、128），不是"第几条弹幕"的序号，
   绝不能填 1,2,3... 这样的连续小序号。
4. 弹幕分散到不同段落，不要都堆在同一段。
5. 只输出 JSON 数组，不要任何解释或markdown代码块，格式：
[{"para":段落编号,"text":"弹幕内容"}]

## 段落正文
$sb"""
    }

    /**
     * 解析 AI 返回：兼容 <think> 思考块、markdown 包裹、JSON 前后夹杂文字。
     * 只接受落在 [validParas] 内的锚点，同文本去重。
     */
    private fun parseResult(result: String, validParas: Set<Int>): List<DanmakuItem> {
        if (result.isBlank() || validParas.isEmpty()) return emptyList()
        val cleaned = result
            .replace(Regex("(?s)<think>.*?</think>"), " ")
            .replace(Regex("(?s)<\\|.*?\\|>"), " ")
            .replace("```", " ")
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        return try {
            val arr = JSONArray(cleaned.substring(start, end + 1))
            val seen = HashSet<String>()
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val para = obj.optInt("para", -1)
                val text = obj.optString("text", "").trim()
                if (para !in validParas) return@mapNotNull null
                if (text.length !in MIN_TEXT_LEN..MAX_TEXT_LEN) return@mapNotNull null
                if (!seen.add(text)) return@mapNotNull null
                DanmakuItem(para, text)
            }
        } catch (e: Exception) {
            AppLog.put("AI弹幕: JSON解析失败 ${e.localizedMessage}")
            emptyList()
        }
    }

    /**
     * 锚点聚类检测：模型把 para 当成"第几条弹幕"（返回 1,2,3... 连续小序号）时，
     * 弹幕会堆在区间开头，后面的段落永远等不到。检测到锚点全部挤在
     * 区间前 30% 内时，按弹幕顺序均匀重排到区间各段落。
     */
    private fun redistributeIfClustered(
        items: List<DanmakuItem>,
        validParas: Set<Int>
    ): List<DanmakuItem> {
        if (items.size < 3) return items
        val span = validParas.sorted()
        val spanSize = span.size
        val maxIdx = items.maxOf { span.indexOf(it.para) }
        if (maxIdx >= spanSize * 0.3) return items
        return items.sortedBy { span.indexOf(it.para) }
            .mapIndexed { i, item ->
                val newIdx = (i * (spanSize - 1)) / (items.size - 1).coerceAtLeast(1)
                item.copy(para = span[newIdx])
            }.also {
                AppLog.put("AI弹幕: 检测到锚点聚在区间前段，已均匀重排")
            }
    }

    // ==================== 段落级记录缓存 ====================
    // 文件格式：{"contentHash":"...","records":{"12":["弹幕1"],"47":["弹幕2"]}}
    // 内容 hash 不一致（章节更新）时整体失效重新生成。

    private fun readCacheRecords(
        file: File,
        contentHash: String
    ): MutableMap<Int, MutableList<String>> {
        val result = HashMap<Int, MutableList<String>>()
        runCatching {
            if (!file.exists()) return result
            val json = JSONObject(file.readText())
            if (json.optString("contentHash") != contentHash) return result
            val records = json.optJSONObject("records") ?: return result
            records.keys().forEach { key ->
                val para = key.toIntOrNull() ?: return@forEach
                val arr = records.optJSONArray(key) ?: return@forEach
                val texts = (0 until arr.length())
                    .mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } }
                if (texts.isNotEmpty()) result[para] = texts.toMutableList()
            }
        }
        return result
    }

    private fun writeCacheRecords(
        file: File,
        contentHash: String,
        records: Map<Int, List<String>>
    ) {
        runCatching {
            val json = JSONObject().apply {
                put("contentHash", contentHash)
                put("records", JSONObject().apply {
                    records.forEach { (para, texts) ->
                        if (texts.isNotEmpty()) {
                            put(para.toString(), JSONArray(texts))
                        }
                    }
                })
            }
            file.writeText(json.toString())
        }
    }
}
