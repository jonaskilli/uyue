package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectKind
import io.legado.app.ui.book.toc.AiCacheFileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 场景特效生成器：整章按字数区间提前分析，每条绑定触发段落。
 *
 * 完全复用 [DanmakuGenerator] 的调度与缓存骨架，但内容不同：
 * - **本地关键词规则是主路径**：不配任何 AI 也能「读到下雪就飘雪」；
 * - **AI 增强可选**：复用 BGM 的 AI Provider（与弹幕同通道），只用于更准地识别
 *   室内外、强度、以及「是不是比喻」；
 * - 段落级缓存落盘，contentHash 不一致时整体失效，重读不重复分析。
 *
 * 参考 /workspace/scene-effects/mapping/weather.json 的 lv 1..4 数值。
 */
object SceneEffectGenerator {

    /** 默认播放时长；闪电是短促叠加，单独给短时长 */
    private const val DEFAULT_DURATION_MS = 8000L
    /** 站点 lightning 预设为 8 秒：场景渐暗 → 四道分叉闪电依次照亮 → 渐亮 */
    private const val LIGHTNING_DURATION_MS = 8000L
    private const val WIND_DURATION_MS = 6000L

    private val INDOOR_WORDS = listOf(
        "屋内", "室内", "房里", "房内", "屋里", "殿内", "车厢", "舱内",
        "洞内", "帐内", "宫里", "府里", "屋子里", "室内"
    )
    private val WIND_WORDS = listOf("狂风", "大风", "风声", "风呼啸", "刮风", "起风", "风骤", "冷风", "夜风")
    private val LIGHTNING_WORDS = listOf("闪电", "电闪", "惊雷", "雷鸣", "雷声", "雷电")

    private val SNOW_WORDS = listOf(
        "飘雪", "雪花", "下雪", "大雪", "暴雪", "小雪", "细雪", "积雪",
        "雪片", "风雪", "雪地", "雪夜", "落雪", "雪中", "白雪", "雪峰"
    )
    private val RAIN_WORDS = listOf(
        "下雨", "雨幕", "雨点", "暴雨", "细雨", "大雨", "小雨", "雨丝",
        "雨声", "风雨", "雨中", "雨水", "倾盆", "骤雨", "阴雨"
    )
    private val LEAVES_WORDS = listOf("落叶", "秋叶", "树叶飘", "枯叶", "叶子落", "叶落")
    private val PETALS_WORDS = listOf("花瓣", "落花", "樱花", "花瓣雨", "花雨", "桃花落")
    private val FIREFLIES_WORDS = listOf("萤火虫", "流萤", "萤光")
    private val EMBERS_WORDS = listOf("火星", "余烬", "篝火", "火星四溅", "灰烬")

    fun getCacheFile(bookName: String, chapterTitle: String): File =
        File(
            File(
                "/storage/emulated/0/Download/chajian/scene_effect/" +
                    AiCacheFileUtil.sanitizeBookName(bookName)
            ).apply { mkdirs() },
            AiCacheFileUtil.sanitizeChapterFileName(chapterTitle) + ".json"
        )

    /**
     * 生成一个区间的场景特效。
     * @param aiEnhanced 是否调用大模型增强（关闭弹幕 / 无 AI Provider 时自动降级为纯本地规则）
     */
    suspend fun generateRange(
        bookName: String,
        chapterTitle: String,
        contentHash: String,
        paragraphs: List<Pair<Int, String>>,
        aiEnhanced: Boolean
    ): List<SceneEffectCue> = withContext(Dispatchers.IO) {
        if (paragraphs.isEmpty()) return@withContext emptyList()
        val cacheFile = getCacheFile(bookName, chapterTitle)
        val records = readCacheRecords(cacheFile, contentHash)

        // 「已检查但无特效」的段落也要记下来，避免每次重读都重复分析
        val todo = paragraphs.filter { !records.containsKey(it.first) }
        if (todo.isNotEmpty()) {
            val local = detectLocal(todo)
            val ai = if (aiEnhanced) requestAi(bookName, chapterTitle, todo) else emptyList()
            // AI 优先覆盖本地（同一段落同一类型只留一条）
            val merged = (ai + local).distinctBy { it.para to it.kind }
            merged.forEach { cue ->
                records.getOrPut(cue.para) { mutableListOf() }.add(cue)
            }
            todo.forEach { (index, _) -> records.getOrPut(index) { mutableListOf() } }
            writeCacheRecords(cacheFile, contentHash, records)
            AppLog.put("场景特效: 区间[${todo.first().first}~${todo.last().first}] 命中${merged.size}条")
        }

        paragraphs.flatMap { (index, _) -> records[index].orEmpty() }
            .sortedBy { it.para }
    }

    /** 本地关键词规则：每段最多产出一条主特效 */
    private fun detectLocal(paragraphs: List<Pair<Int, String>>): List<SceneEffectCue> =
        paragraphs.mapNotNull { (index, text) -> detectOne(index, text) }

    private fun detectOne(para: Int, text: String): SceneEffectCue? {
        val t = text.take(300)
        val indoor = INDOOR_WORDS.any { t.contains(it) }
        val wind = WIND_WORDS.any { t.contains(it) }
        val hasLightning = LIGHTNING_WORDS.any { t.contains(it) }

        val hit: Pair<SceneEffectKind, Int> = when {
            SNOW_WORDS.any { t.contains(it) } ->
                SceneEffectKind.SNOW to snowLevel(t)
            RAIN_WORDS.any { t.contains(it) } ->
                SceneEffectKind.RAIN to rainLevel(t)
            LEAVES_WORDS.any { t.contains(it) } -> SceneEffectKind.LEAVES to 2
            PETALS_WORDS.any { t.contains(it) } -> SceneEffectKind.PETALS to 2
            FIREFLIES_WORDS.any { t.contains(it) } -> SceneEffectKind.FIREFLIES to 2
            EMBERS_WORDS.any { t.contains(it) } -> SceneEffectKind.EMBERS to 2
            hasLightning -> SceneEffectKind.LIGHTNING to 3
            wind -> SceneEffectKind.WIND to if (t.contains("狂风")) 4 else 2
            else -> return null
        }

        val kind = hit.first
        // 室内不播室外天气/植物类，避免「屋里下雪」的违和感
        if (indoor && SceneEffectKind.isOutdoorOnly(kind)) return null

        val duration = when (kind) {
            SceneEffectKind.LIGHTNING -> LIGHTNING_DURATION_MS
            SceneEffectKind.WIND -> WIND_DURATION_MS
            else -> DEFAULT_DURATION_MS
        }
        return SceneEffectCue(
            para = para,
            kind = kind,
            level = hit.second.coerceIn(1, 4),
            durationMs = duration,
            indoors = indoor,
            wind = wind,
            // 雨雪段落里若同时写了雷电，叠加闪电轨道
            lightning = hasLightning && (kind == SceneEffectKind.RAIN || kind == SceneEffectKind.SNOW),
            source = "local"
        )
    }

    private fun snowLevel(t: String): Int = when {
        t.contains("暴雪") || t.contains("鹅毛") -> 4
        t.contains("大雪") -> 3
        t.contains("小雪") || t.contains("细雪") -> 1
        else -> 2
    }

    private fun rainLevel(t: String): Int = when {
        t.contains("暴雨") || t.contains("倾盆") -> 4
        t.contains("大雨") -> 3
        t.contains("细雨") || t.contains("小雨") -> 1
        else -> 2
    }

    // ==================== AI 增强 ====================

    private suspend fun requestAi(
        bookName: String,
        chapterTitle: String,
        paragraphs: List<Pair<Int, String>>
    ): List<SceneEffectCue> {
        val provider = appDb.bgmAIProviderDao.getEnabled() ?: return emptyList()
        val result = try {
            BgmAIService.callOpenAIApi(
                provider,
                buildPrompt(bookName, chapterTitle, paragraphs)
            )
        } catch (e: Exception) {
            AppLog.put("场景特效: AI增强失败 ${e.localizedMessage}")
            return emptyList()
        }
        return parseAiResult(result, paragraphs.map { it.first }.toSet())
    }

    private fun buildPrompt(
        bookName: String,
        chapterTitle: String,
        paragraphs: List<Pair<Int, String>>
    ): String {
        val sb = StringBuilder()
        paragraphs.forEach { (index, text) ->
            sb.append("【段落").append(index).append("】")
                .append(text.trim().take(160)).append('\n')
        }
        return """你是小说演出标注器。下面是《$bookName》章节「$chapterTitle」的部分段落，每段前有【段落N】编号。

## 任务
只标注**真实发生**的场景环境，供朗读时播放对应的画面特效（飘雪、下雨、落叶、花瓣、闪电、萤火虫、火星、风）。

## 规则
1. 只标正文明确写出的天气/景物，不推测；「心在下雪」「泪如雨下」「气氛冰冷」等比喻一律不标。
2. 室内（屋内、殿内、车厢等）不要标 RAIN/SNOW/PETALS/LEAVES/LIGHTNING，把 indoors 填 true 即可。
3. 同一段落最多一条；没有合适特效的段落不要输出。
4. para 必须照抄【段落N】里的 N，是该段的真实编号，不是序号。
5. level 只能 1~4：1=微弱，2=正常，3=明显，4=暴雨/暴雪/狂风。
6. 雨雪段落若同时出现雷电，把 lightning 填 true；有风把 wind 填 true。
7. durationMs 用 8000；闪电单独出现时用 1600。

## 输出
只输出 JSON 数组，不要解释、不要 markdown 代码块：
[{"para":12,"kind":"SNOW","level":2,"indoors":false,"wind":true,"lightning":false,"durationMs":8000}]

kind 白名单：RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND

## 段落正文
$sb"""
    }

    private fun parseAiResult(result: String, validParas: Set<Int>): List<SceneEffectCue> {
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
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val para = o.optInt("para", -1)
                val kind = SceneEffectKind.from(o.optString("kind")) ?: return@mapNotNull null
                if (para !in validParas) return@mapNotNull null
                val cue = SceneEffectCue(
                    para = para,
                    kind = kind,
                    level = o.optInt("level", 2).coerceIn(1, 4),
                    durationMs = o.optLong("durationMs", DEFAULT_DURATION_MS).coerceIn(1000L, 20000L),
                    indoors = o.optBoolean("indoors", false),
                    wind = o.optBoolean("wind", false),
                    lightning = o.optBoolean("lightning", false),
                    source = "ai"
                )
                if (cue.indoors && SceneEffectKind.isOutdoorOnly(cue.kind)) return@mapNotNull null
                cue
            }
        } catch (e: Exception) {
            AppLog.put("场景特效: AI结果解析失败 ${e.localizedMessage}")
            emptyList()
        }
    }

    // ==================== 段落级缓存 ====================

    private fun readCacheRecords(
        file: File,
        contentHash: String
    ): MutableMap<Int, MutableList<SceneEffectCue>> {
        val result = HashMap<Int, MutableList<SceneEffectCue>>()
        runCatching {
            if (!file.exists()) return result
            val json = JSONObject(file.readText())
            if (json.optString("contentHash") != contentHash) return result
            val records = json.optJSONObject("records") ?: return result
            records.keys().forEach { key ->
                val para = key.toIntOrNull() ?: return@forEach
                val arr = records.optJSONArray(key) ?: return@forEach
                val list = (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { cueFromJson(para, it) }
                }
                result[para] = list.toMutableList()
            }
        }
        return result
    }

    private fun writeCacheRecords(
        file: File,
        contentHash: String,
        records: Map<Int, List<SceneEffectCue>>
    ) {
        runCatching {
            val json = JSONObject().apply {
                put("contentHash", contentHash)
                put("records", JSONObject().apply {
                    records.forEach { (para, cues) ->
                        put(para.toString(), JSONArray().also { arr ->
                            cues.forEach { arr.put(cueToJson(it)) }
                        })
                    }
                })
            }
            file.writeText(json.toString())
        }
    }

    private fun cueToJson(c: SceneEffectCue) = JSONObject().apply {
        put("kind", c.kind.name)
        put("level", c.level)
        put("durationMs", c.durationMs)
        put("indoors", c.indoors)
        put("wind", c.wind)
        put("lightning", c.lightning)
        put("source", c.source)
    }

    private fun cueFromJson(para: Int, o: JSONObject): SceneEffectCue? {
        val kind = SceneEffectKind.from(o.optString("kind")) ?: return null
        return SceneEffectCue(
            para = para,
            kind = kind,
            level = o.optInt("level", 2).coerceIn(1, 4),
            durationMs = o.optLong("durationMs", DEFAULT_DURATION_MS),
            indoors = o.optBoolean("indoors", false),
            wind = o.optBoolean("wind", false),
            lightning = o.optBoolean("lightning", false),
            source = o.optString("source", "local")
        )
    }
}
