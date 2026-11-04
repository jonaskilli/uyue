@file:Suppress("unused")

package io.legado.app.model.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * 场景特效决策层（Android/Kotlin 版）。
 *
 * 对应 renderer/effect-resolver.ts 与 renderer/weather-mapper.ts，
 * 数据流：语义标注 JSON → 规范化 → cue 映射 → 效果 ID → preset tracks → 渲染轨道。
 *
 * 用法：
 *   val resolver = SceneEffectResolver(presets, semanticEffects, cueMapJson, weatherMapJson)
 *   val annotation = resolver.parse(aiOutputJson)
 *   val tracks = resolver.tracksFor(annotation, action)
 *   weatherOverlay.setTracks(tracks)
 */
class SceneEffectResolver(
    private val presets: List<Preset>,
    private val semanticEffects: List<SemanticEffect>,
    private val cueMap: JSONObject,
    private val weatherMap: JSONObject,
) {
    private val cueToIds: Map<String, List<String>> by lazy {
        cueMap.optJSONObject("cueToEffectIds")?.let { o ->
            o.keys().asSequence().associateWith { k -> o.optJSONArray(k).toStringList() }
        } ?: emptyMap()
    }
    private val emotionToId: Map<String, String> by lazy {
        cueMap.optJSONObject("emotionToEffectId")?.let { o ->
            o.keys().asSequence().associateWith { k -> o.optString(k) }
        } ?: emptyMap()
    }
    private val cuePriority: Map<String, Int> by lazy {
        cueMap.optJSONObject("cuePriority")?.let { o ->
            o.keys().asSequence().associateWith { k -> o.optInt(k, 0) }
        } ?: emptyMap()
    }
    private val rainCounts: IntArray by lazy {
        weatherMap.optJSONObject("particleCountByLevel")?.optJSONArray("rain").toIntArray(intArrayOf(90, 180, 340, 600))
    }
    private val snowCounts: IntArray by lazy {
        weatherMap.optJSONObject("particleCountByLevel")?.optJSONArray("snow").toIntArray(intArrayOf(60, 110, 230, 400))
    }
    private val fallSpeeds: FloatArray by lazy {
        weatherMap.optJSONArray("fallSpeedByLevel").toFloatArray(floatArrayOf(0.6f, 1f, 1.4f, 1.9f))
    }

    // ---------- 对外入口 ----------

    /** 解析大模型输出 / 缓存的标注 JSON（支持 v1 与 v2） */
    fun parse(json: String): SceneEffectAnnotation {
        val o = JSONObject(json)
        val version = o.optInt("v", 2)
        val weather = o.optJSONArray("w").toWeatherList(version)
        val rawActions = o.optJSONArray("a").toActionAnnotations()
        val opening = o.optJSONObject("op")?.let { EffectRef(normalizeId(it.optString("id"))) }

        // Gde：把 cues 转成效果 ID，按 group 去重
        val actions = rawActions.mapNotNull { a ->
            val used = mutableSetOf<String>()
            val w = weather.firstOrNull { it.covers(a.chapter, a.order) }
            if (w != null && !w.indoors && w.lightning) used.add("lightning")

            val cues = a.cues.sortedWith(
                compareByDescending<Cue> { cuePriority[it.kind] ?: 0 }
                    .thenBy { it.kind }
            )
            val fx = mutableListOf<EffectRef>()
            fun push(id: String, cue: Cue? = null) {
                val effect = dc(id) ?: return
                if (effect.groups.any { used.contains(it) }) return
                used.addAll(effect.groups)
                val ref = EffectRef(
                    id = effect.id,
                    color = if (effect.id == "a18") cue?.color else null,
                    opacity = if (effect.id == "a18") cue?.opacity ?: 0.2f else null,
                )
                fx.add(ref)
            }
            for (cue in cues) {
                val ids = specialIds(cue) ?: cueToIds[cue.kind].orEmpty()
                ids.forEach { push(it, cue) }
            }
            if (a.tone == "lighthearted" && !used.contains("camera") && !used.contains("impact")) {
                cues.forEach { cue -> emotionToId[cue.kind]?.let { push(it, cue) } }
            }
            if (fx.isEmpty()) null else ActionEffect(a.chapter, a.order, fx)
        }

        return SceneEffectAnnotation(actions, weather, opening)
    }

    /** 某个动作此刻需要渲染的全部轨道 */
    fun tracksFor(
        annotation: SceneEffectAnnotation,
        action: StoryAction,
        reducedMotion: Boolean = false,
    ): List<Track> {
        val out = mutableListOf<Track>()
        // 1. 动作效果
        annotation.actions
            .filter { it.chapter == action.chapter && it.order == action.order }
            .flatMap { it.fx }
            .forEach { out.addAll(i3(it)) }
        // 2. 天气区间（室内不生成降水）
        val w = annotation.weather.firstOrNull { it.covers(action.chapter, action.order) }
        weatherToParticleTrack(w)?.let { out.add(it) }
        if (!reducedMotion && w != null && w.lightning && !w.indoors) {
            out.addAll(i3(EffectRef("a07")))
        }
        return out
    }

    // ---------- 内部实现 ----------

    /** Zde：v2 weather 对象 → v1 规范区间 */
    private fun JSONArray?.toWeatherList(version: Int): List<WeatherRange> {
        if (this == null) return emptyList()
        val out = mutableListOf<WeatherRange>()
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val s = o.optJSONArray("s") ?: continue
            val e = o.optJSONArray("e") ?: continue
            val startChapter = s.optInt(0, 1); val startOrder = s.optInt(1, 1)
            val endChapter = e.optInt(0, 1); val endOrder = e.optInt(1, 1)
            val range = if (version == 2 && o.has("weather")) {
                val type = when (o.optString("weather")) {
                    "rain" -> WeatherType.RAIN
                    "snow" -> WeatherType.SNOW
                    else -> WeatherType.NONE
                }
                if (type == WeatherType.NONE) continue
                val lv = when (o.optString("intensity")) {
                    "light" -> 1; "moderate" -> 2; "heavy" -> 3; "violent" -> 4; else -> 2
                }
                WeatherRange(
                    startChapter, startOrder, endChapter, endOrder, type, lv,
                    o.optBoolean("lightning", false), o.optBoolean("wind", false),
                    o.optBoolean("indoors", false),
                )
            } else {
                WeatherRange(
                    startChapter, startOrder, endChapter, endOrder,
                    if (o.optString("t") == "s") WeatherType.SNOW else WeatherType.RAIN,
                    o.optInt("lv", 2).coerceIn(1, 4),
                    o.optInt("l", 0) == 1, o.optInt("w", 0) == 1,
                    o.optInt("indoors", 0) == 1,
                )
            }
            // 区间冲突：不同类型重叠时保留先出现的，跳过冲突项
            if (out.any { it.type != range.type && it.covers(startChapter, startOrder) }) continue
            out.add(range)
        }
        return out
    }

    private fun JSONArray?.toActionAnnotations(): List<ActionAnnotation> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            val cues = o.optJSONArray("cues")?.let { arr ->
                (0 until arr.length()).mapNotNull { j ->
                    arr.optJSONObject(j)?.let { c ->
                        Cue(
                            kind = c.optString("kind"),
                            intensity = c.optString("intensity"),
                            color = c.optString("color").takeIf { it.isNotBlank() },
                            opacity = c.optDouble("opacity", Double.NaN)
                                .takeIf { !it.isNaN() }?.toFloat(),
                        )
                    }
                }
            } ?: emptyList()
            ActionAnnotation(
                chapter = o.optInt("cs", 1),
                order = o.optInt("o", 0),
                cues = cues,
                tone = o.optString("tone"),
            )
        }
    }

    /** pue：e1 → e01 */
    private fun normalizeId(id: String): String =
        Regex("^([eas])([1-9])$").replace(id) { "${it.groupValues[1]}0${it.groupValues[2]}" }

    /** dc：语义效果目录查找 */
    private fun dc(id: String): SemanticEffect? {
        val key = if (id == "e21") "e01" else normalizeId(id)
        return semanticEffects.firstOrNull { it.id == key }
    }

    /** i3：效果 ID → tracks */
    private fun i3(ref: EffectRef): List<Track> {
        val effect = dc(ref.id)
        val fromPreset = presets.firstOrNull { it.id == effect?.preset }
        val tracks = (effect?.tracks?.takeIf { it.isNotEmpty() } ?: fromPreset?.tracks ?: emptyList())
            .map { it.copy() }
        if (ref.id == "a18") {
            return tracks.map {
                if (it.kind == "overlay" && it.variant == "tint")
                    it.copy(color = ref.color ?: it.color, strength = (ref.opacity ?: 0.2f))
                else it
            }
        }
        return tracks
    }

    /** shock / fear / affection 的强度特例 */
    private fun specialIds(cue: Cue): List<String>? {
        val special = cueMap.optJSONObject("intensitySpecialRules")?.optJSONObject(cue.kind)
            ?: return null
        return when (cue.kind) {
            "shock" -> special.optJSONArray(cue.intensity.ifEmpty { "mild" }).toStringList()
                .ifEmpty { special.optJSONArray("strong").toStringList() }
            else -> if (cue.intensity.isNotEmpty() && cue.intensity != "mild")
                special.optJSONArray("not_mild").toStringList()
            else emptyList()
        }
    }

    /** 天气 → 持续型 particles 轨道；室内返回 null */
    private fun weatherToParticleTrack(w: WeatherRange?): Track? {
        if (w == null || w.indoors || w.type == WeatherType.NONE) return null
        val lv = w.level.coerceIn(1, 4)
        val isRain = w.type == WeatherType.RAIN
        val counts = if (isRain) rainCounts else snowCounts
        val presetId = if (isRain) "rain" else "snow"
        val base = presets.firstOrNull { it.id == presetId }?.tracks
            ?.firstOrNull { it.kind == "particles" }
        return Track(
            kind = "particles",
            variant = if (isRain) "rain" else "snow",
            at = 0f,
            duration = -1f, // 持续到区间结束
            count = counts.getOrElse(lv - 1) { counts.last() },
            size = base?.size ?: 1f,
            opacity = base?.opacity ?: 0.9f,
            wind = if (w.wind) 1f else 0f,
            fallSpeed = fallSpeeds.getOrElse(lv - 1) { 1f },
        )
    }

    private fun JSONArray?.toIntArray(fallback: IntArray): IntArray {
        if (this == null) return fallback
        return IntArray(length()) { optInt(it, fallback.getOrElse(it) { 100 }) }
    }

    private fun JSONArray?.toFloatArray(fallback: FloatArray): FloatArray {
        if (this == null) return fallback
        return FloatArray(length()) { optDouble(it, fallback.getOrElse(it) { 1.0 }.toDouble()).toFloat() }
    }
}
