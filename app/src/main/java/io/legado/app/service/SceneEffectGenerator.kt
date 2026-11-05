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
    /** 规则表版本：新增特效后旧缓存里「已检查无特效」的段落要重新分析 */
    private const val CACHE_VERSION = 4

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
    private val COMIC_WORDS = listOf(
        "砰", "轰隆", "哐当", "哗啦", "咔嚓", "嘭", "咚", "噼啪", "嗖", "唰"
    )

    /**
     * 新增实体类特效的关键词规则，按「具体 → 泛化」排序，第一个命中生效。
     * 站点环境/人物表现中的屏幕实体（fog/smoke/bubbles/rays/dust/butterflies/
     * birds/漫画符号）与 tsParticles(MIT) 预设里适合小说的效果类型。
     */
    private val EXTRA_RULES: List<Triple<SceneEffectKind, List<String>, Int>> = listOf(
        // 瞬时爆发类：音效关闭时也能由正文兜底触发，画 1.2 秒就收，不拖到整段结束
        Triple(SceneEffectKind.EXPLOSION, listOf("爆炸", "爆裂", "炸开", "炸毁", "爆破", "轰隆", "轰然", "轰炸", "炸", "砰", "嘭"), 4),
        Triple(SceneEffectKind.SLASH, listOf("拔刀", "出鞘", "剑气", "刀光", "剑鸣", "挥刀", "挥剑", "一刀斩", "斩下", "斩击", "劈下", "劈开", "砍下", "刀锋", "剑锋", "比武", "过招", "唰"), 3),
        Triple(SceneEffectKind.IMPACT, listOf("暴击", "重击", "重创", "击中", "命中", "撞击", "打斗", "格挡", "拳脚", "一拳", "一掌", "咚", "哐"), 3),
        Triple(SceneEffectKind.SHOCKWAVE, listOf("冲击波", "气浪", "震波", "震荡", "余波", "威压", "地动山摇"), 3),
        Triple(SceneEffectKind.FIREWORKS, listOf("烟花", "焰火", "礼花", "爆竹", "烟花绽放"), 3),
        Triple(SceneEffectKind.METEORS, listOf("流星", "陨星", "划过天际", "星雨", "流星雨"), 2),
        Triple(SceneEffectKind.BIRDS_CROW, listOf("乌鸦", "寒鸦", "鸦群", "老鸹", "乌鹊"), 2),
        Triple(SceneEffectKind.BIRDS_GULL, listOf("海鸥", "白鸥", "鸥鸟", "海鸟", "沙鸥"), 2),
        Triple(SceneEffectKind.BIRD_FLOCK, listOf("鸟群", "群鸟", "雁阵", "大雁", "飞鸟", "惊鸟", "雀群", "归鸟"), 2),
        Triple(SceneEffectKind.BUTTERFLIES, listOf("蝴蝶", "彩蝶", "粉蝶", "蝶舞", "蝶翼", "花蝶"), 2),
        Triple(SceneEffectKind.BUBBLES, listOf("气泡", "水泡", "冒泡", "泡沫", "气泡上浮", "吐泡泡"), 2),
        Triple(SceneEffectKind.FOUNTAIN, listOf("喷泉", "水花", "水柱", "浪花", "溅起", "水雾", "飞溅"), 2),
        Triple(SceneEffectKind.FIRE, listOf("火焰", "烈火", "火光", "燃烧", "火苗", "火舌", "着火", "熊熊"), 3),
        Triple(SceneEffectKind.FOG, listOf("薄雾", "雾气", "白雾", "迷雾", "雾霭", "晨雾", "浓雾", "云雾", "起雾"), 2),
        Triple(SceneEffectKind.SMOKE, listOf("炊烟", "青烟", "烟雾", "烟气", "烟尘", "轻烟", "浓烟", "香烟"), 2),
        Triple(SceneEffectKind.RAYS, listOf("光束", "晨光", "斜射", "透过窗", "光线", "霞光", "夕照", "光柱", "阳光洒"), 2),
        Triple(SceneEffectKind.DUST, listOf("尘土", "扬尘", "黄沙", "沙尘", "飞沙", "灰土", "尘烟", "浮尘"), 2),
        Triple(SceneEffectKind.STARS, listOf("星空", "星辰", "繁星", "星河", "银河", "夜空", "星子", "满天星", "星光"), 2),
        Triple(SceneEffectKind.CONFETTI, listOf("彩纸", "彩带", "纸屑", "庆祝", "欢呼", "喜庆", "彩旗"), 2),
        Triple(SceneEffectKind.DANDELION, listOf("蒲公英", "柳絮", "飞絮", "杨花", "芦花", "绒球"), 2),
        Triple(SceneEffectKind.FEATHERS, listOf("羽毛", "白羽", "羽落", "翎毛", "鹤羽", "羽绒"), 2),
        Triple(SceneEffectKind.BOKEH, listOf("光斑", "光点", "微光", "朦胧", "斑驳", "光影", "粼粼"), 2),
        Triple(SceneEffectKind.SPARKLES, listOf("闪光", "闪耀", "亮晶晶", "晶莹", "闪烁", "熠熠", "亮光"), 2),
        Triple(SceneEffectKind.LIGHTBULB, listOf("灵光一闪", "恍然", "顿悟", "茅塞顿开", "灵机一动", "忽然想到"), 2),
        Triple(SceneEffectKind.HEART, listOf("心动", "怦然", "脸红", "心跳加速", "脸颊发烫", "心头一跳"), 2),
        Triple(SceneEffectKind.SWEAT, listOf("冷汗", "冒汗", "汗珠", "汗流", "额角汗", "捏了把汗", "冷汗直流"), 2),
        Triple(SceneEffectKind.ANGER, listOf("青筋", "怒火", "恼怒", "气得", "暴跳", "咬牙切齿", "怒气"), 2),
        Triple(SceneEffectKind.ZZZ, listOf("睡意", "打盹", "呼噜", "入眠", "困倦", "打哈欠", "昏昏欲睡"), 2),
        Triple(SceneEffectKind.QUESTION, listOf("满头问号", "疑惑不解", "一头雾水", "纳闷", "不明所以", "百思不得"), 2),
        Triple(SceneEffectKind.COMIC, COMIC_WORDS, 2)
    )

    /** 无命中时交给按 VN 场景常驻的 idle 层，不再随机插入短特效打断它 */
    private const val AMBIENT_FALLBACK_RATE = 0.0
    private fun frac(v: Double): Double = v - kotlin.math.floor(v)
    private fun pseudo(i: Int, seed: Int): Double =
        frac(kotlin.math.sin(i * 127.1 + seed * 311.7) * 43758.5453)

    private fun ambientFallback(para: Int): Pair<SceneEffectKind, Int>? {
        if (pseudo(para, 77) >= AMBIENT_FALLBACK_RATE) return null
        val kinds = listOf(
            SceneEffectKind.BOKEH, SceneEffectKind.FOG,
            SceneEffectKind.DUST, SceneEffectKind.SPARKLES
        )
        val idx = (pseudo(para, 91) * kinds.size).toInt().coerceIn(0, kinds.size - 1)
        return kinds[idx] to 2
    }

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

        val hit: Pair<SceneEffectKind, Int>? = when {
            SNOW_WORDS.any { t.contains(it) } ->
                SceneEffectKind.SNOW to snowLevel(t)
            RAIN_WORDS.any { t.contains(it) } ->
                SceneEffectKind.RAIN to rainLevel(t)
            LEAVES_WORDS.any { t.contains(it) } -> SceneEffectKind.LEAVES to 2
            PETALS_WORDS.any { t.contains(it) } -> SceneEffectKind.PETALS to 2
            FIREFLIES_WORDS.any { t.contains(it) } -> SceneEffectKind.FIREFLIES to 2
            EMBERS_WORDS.any { t.contains(it) } -> SceneEffectKind.EMBERS to 2
            else -> {
                val rule = EXTRA_RULES.firstOrNull { (_, words, _) ->
                    words.any { t.contains(it) }
                }
                when {
                    rule != null -> rule.first to rule.third
                    hasLightning -> SceneEffectKind.LIGHTNING to 3
                    wind -> SceneEffectKind.WIND to if (t.contains("狂风")) 4 else 2
                    else -> ambientFallback(para)
                }
            }
        }
        if (hit == null) return null

        val kind = hit.first
        // 室内不播室外天气/植物类，避免「屋里下雪」的违和感
        if (indoor && SceneEffectKind.isOutdoorOnly(kind)) return null

        val duration = when (kind) {
            SceneEffectKind.LIGHTNING -> LIGHTNING_DURATION_MS
            SceneEffectKind.WIND -> WIND_DURATION_MS
            SceneEffectKind.SPARKLES, SceneEffectKind.QUESTION, SceneEffectKind.SWEAT,
            SceneEffectKind.HEART, SceneEffectKind.ANGER, SceneEffectKind.ZZZ,
            SceneEffectKind.LIGHTBULB, SceneEffectKind.COMIC -> 4000L
            SceneEffectKind.EXPLOSION, SceneEffectKind.SLASH,
            SceneEffectKind.IMPACT, SceneEffectKind.SHOCKWAVE -> 1200L
            SceneEffectKind.FIREWORKS, SceneEffectKind.METEORS,
            SceneEffectKind.CONFETTI -> 6000L
            else -> DEFAULT_DURATION_MS
        }
        val comicText = if (kind == SceneEffectKind.COMIC)
            COMIC_WORDS.firstOrNull { t.contains(it) } else null
        return SceneEffectCue(
            para = para,
            kind = kind,
            level = hit.second.coerceIn(1, 4),
            durationMs = duration,
            indoors = indoor,
            wind = wind,
            // 雨雪段落里若同时写了雷电，叠加闪电轨道
            lightning = hasLightning && (kind == SceneEffectKind.RAIN || kind == SceneEffectKind.SNOW),
            source = "local",
            text = comicText
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
只标注**真实发生**的场景环境，供朗读时播放对应的画面特效（雨雪、飘落物、雾烟气泡光束、蝴蝶与鸟群、漫画符号、星空流星烟花等）。

## 规则
1. 只标正文明确写出的天气/景物，不推测；「心在下雪」「泪如雨下」「气氛冰冷」等比喻一律不标。
2. 室内（屋内、殿内、车厢等）不要标 RAIN/SNOW/PETALS/LEAVES/LIGHTNING，把 indoors 填 true 即可。
3. 同一段落最多一条；没有合适特效的段落不要输出。
4. para 必须照抄【段落N】里的 N，是该段的真实编号，不是序号。
5. level 只能 1~4：1=微弱，2=正常，3=明显，4=暴雨/暴雪/狂风。
6. 雨雪段落若同时出现雷电，把 lightning 填 true；有风把 wind 填 true。
7. durationMs 用 8000；闪电、漫画符号用 4000；烟花/流星/彩屑用 6000。
8. EXPLOSION/SLASH/IMPACT/SHOCKWAVE 只在明确写出爆炸、挥刀斩击、重击命中、冲击波时标，durationMs 用 1200；没有这些动作一律不标。

## 输出
只输出 JSON 数组，不要解释、不要 markdown 代码块：
[{"para":12,"kind":"SNOW","level":2,"indoors":false,"wind":true,"lightning":false,"durationMs":8000}]

kind 白名单：RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND,
FOG, SMOKE, BUBBLES, RAYS, DUST, BUTTERFLIES, BIRDS_GULL, BIRDS_CROW, BIRD_FLOCK,
SPARKLES, QUESTION, SWEAT, HEART, ANGER, ZZZ, LIGHTBULB, COMIC,
STARS, METEORS, FIREWORKS, FIRE, CONFETTI, FOUNTAIN, BOKEH, FEATHERS, DANDELION,
EXPLOSION, SLASH, IMPACT, SHOCKWAVE

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
            if (json.optInt("version", 1) != CACHE_VERSION) return result
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
                put("version", CACHE_VERSION)
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
        c.text?.let { put("text", it) }
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
            source = o.optString("source", "local"),
            text = o.optString("text", "").takeIf { it.isNotEmpty() }
        )
    }
}
