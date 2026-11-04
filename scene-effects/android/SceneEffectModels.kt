@file:Suppress("unused")

package io.legado.app.model.scene

import org.json.JSONArray
import org.json.JSONObject

/**
 * 场景特效数据模型。
 * 与 scene-effects/schema/*.json 一一对应，可直接复制到 Android 项目。
 * 用 Android 内置 org.json 解析，不引入额外依赖。
 */

/** 渲染轨道：一个效果由 1~N 条轨道组成。duration <= 0 表示持续到区间结束。 */
data class Track(
    val kind: String,
    val variant: String = "",
    val at: Float = 0f,
    val duration: Float = 7f,
    val count: Int = 100,
    val size: Float = 1f,
    val opacity: Float = 0.9f,
    val wind: Float = 0f,
    val fallSpeed: Float = 1f,
    val strength: Float = 0.75f,
    val color: String = "#FFFFFF",
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val angle: Float = 0f,
) {
    companion object {
        fun fromJson(o: JSONObject): Track = Track(
            kind = o.optString("kind"),
            variant = o.optString("variant"),
            at = o.optDouble("at", 0.0).toFloat(),
            duration = if (o.isNull("duration")) -1f else o.optDouble("duration", 7.0).toFloat(),
            count = o.optInt("count", 100),
            size = o.optDouble("size", 1.0).toFloat(),
            opacity = o.optDouble("opacity", 0.9).toFloat(),
            wind = o.optDouble("wind", 0.0).toFloat(),
            fallSpeed = o.optDouble("fallSpeed", 1.0).toFloat(),
            strength = o.optDouble("strength", 0.75).toFloat(),
            color = o.optString("color", "#FFFFFF"),
            scale = o.optDouble("scale", 1.0).toFloat(),
            panX = o.optDouble("panX", 0.0).toFloat(),
            panY = o.optDouble("panY", 0.0).toFloat(),
            angle = o.optDouble("angle", 0.0).toFloat(),
        )
    }
}

/** 视觉预设（74 个） */
data class Preset(
    val id: String,
    val name: String,
    val category: String,
    val duration: Float,
    val tracks: List<Track>,
) {
    companion object {
        fun fromJson(o: JSONObject): Preset = Preset(
            id = o.optString("id"),
            name = o.optString("name"),
            category = o.optString("category"),
            duration = o.optDouble("duration", 7.0).toFloat(),
            tracks = o.optJSONArray("tracks").toTracks(),
        )
    }
}

/** 语义效果目录（50 条）：id → preset / groups / guide */
data class SemanticEffect(
    val id: String,
    val name: String,
    val preset: String,
    val groups: List<String>,
    val guide: String = "",
    val hold: Boolean = false,
    val target: Boolean = false,
    val renderer: String = "",
    /** 少数效果自带轨道；没有时由 i3() 回退到 presets/effects.json 查找 */
    val tracks: List<Track> = emptyList(),
) {
    companion object {
        fun fromJson(o: JSONObject): SemanticEffect = SemanticEffect(
            id = o.optString("id"),
            name = o.optString("name"),
            preset = o.optString("preset"),
            groups = o.optJSONArray("groups").toStringList(),
            guide = o.optString("guide"),
            hold = o.optBoolean("hold", false),
            target = o.optBoolean("target", false),
            renderer = o.optString("renderer"),
            tracks = o.optJSONArray("tracks").toTracks(),
        )
    }
}

/** 天气类型 */
enum class WeatherType { RAIN, SNOW, NONE }

/** 规范化后的天气区间 */
data class WeatherRange(
    val startChapter: Int,
    val startOrder: Int,
    val endChapter: Int,
    val endOrder: Int,
    val type: WeatherType,
    val level: Int,          // 1..4
    val lightning: Boolean,
    val wind: Boolean,
    val indoors: Boolean,
) {
    fun covers(chapter: Int, order: Int): Boolean {
        val afterStart = chapter > startChapter || (chapter == startChapter && order >= startOrder)
        val beforeEnd = chapter < endChapter || (chapter == endChapter && order <= endOrder)
        return afterStart && beforeEnd
    }
}

data class EffectRef(val id: String, val color: String? = null, val opacity: Float? = null)

data class ActionEffect(val chapter: Int, val order: Int, val fx: List<EffectRef>)

data class Cue(val kind: String, val intensity: String = "", val color: String? = null, val opacity: Float? = null)

data class ActionAnnotation(
    val chapter: Int,
    val order: Int,
    val cues: List<Cue> = emptyList(),
    val tone: String = "",
    val fx: List<EffectRef> = emptyList(),
)

data class SceneEffectAnnotation(
    val actions: List<ActionEffect> = emptyList(),
    val weather: List<WeatherRange> = emptyList(),
    val opening: EffectRef? = null,
)

/** 小说脚本里的一个动作（由 storyScript / 朗读段落映射而来） */
data class StoryAction(
    val chapter: Int,
    val order: Int,
    val fromLine: Int = 0,
    val toLine: Int = 0,
    val text: String = "",
)

fun JSONArray?.toTracks(): List<Track> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let { Track.fromJson(it) }
    }
}

fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }
}

/** presets/effects.json 顶层是数组 */
fun JSONArray?.toPresetList(): List<Preset> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { Preset.fromJson(it) } }
}

/** presets/semantic-effects.json 顶层也是数组 */
fun JSONArray?.toSemanticEffectList(): List<SemanticEffect> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optJSONObject(i)?.let { SemanticEffect.fromJson(it) } }
}
