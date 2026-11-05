package io.legado.app.model

/**
 * 朗读场景特效公共数据模型。
 *
 * 复用 AI 弹幕的整套机制：整章按字数区间提前生成、绑定段落锚点、
 * 朗读到该段时播放一段时间的特效（飘雪/下雨/落叶/花瓣/闪电/萤火虫/火星/风）。
 *
 * 与弹幕的差异：内容是特效类型+强度而非文字，播放是持续 durationMs 而非飘过一次。
 *
 * 后续新增两类：
 * - 实体/环境类共 34 种（见 SceneEffectView / SceneEffectExtraRenderer）；
 * - 瞬时爆发类 EXPLOSION/SLASH/IMPACT/SHOCKWAVE，由音效触发事件同句联动绘制。
 */

/** 场景特效类型 */
enum class SceneEffectKind {
    // 原有 8 种：按 tftgamer 原版 s_()/Xg()/Cn() 实现
    RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND,
    // 站点「环境系统 / 人物表现」中的屏幕实体类（Cn() ambient、Xg()、Kg()）
    FOG, SMOKE, BUBBLES, RAYS, DUST, BUTTERFLIES,
    BIRDS_GULL, BIRDS_CROW, BIRD_FLOCK,
    SPARKLES, QUESTION, SWEAT, HEART, ANGER, ZZZ, LIGHTBULB, COMIC,
    // tsParticles（MIT）预设中适合小说场景的效果，按通用粒子算法独立实现
    STARS, METEORS, FIREWORKS, FIRE, CONFETTI, FOUNTAIN, BOKEH,
    FEATHERS, DANDELION,
    // 瞬时爆发类：音效命中同一句时触发（算法参考 pixelfx / OGSlash 的爆炸、斩击、打击）
    EXPLOSION, SLASH, IMPACT, SHOCKWAVE;

    companion object {
        fun from(name: String?): SceneEffectKind? =
            values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

        /** 由 SceneEffectExtraRenderer 绘制（主 View 只保留原 8 种的老实现） */
        fun isExtra(kind: SceneEffectKind): Boolean = kind !in setOf(
            RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND
        )

        /** 是否属于「室外才有」的天气/植物/天象类特效 */
        fun isOutdoorOnly(kind: SceneEffectKind): Boolean = when (kind) {
            RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FOG, DUST,
            BIRDS_GULL, BIRDS_CROW, BIRD_FLOCK, BUTTERFLIES,
            STARS, METEORS, FIREWORKS, FEATHERS, DANDELION -> true
            else -> false
        }

        /** 瞬时爆发型：短时长、跟音效同句、不进段落缓存 */
        fun isBurst(kind: SceneEffectKind): Boolean = when (kind) {
            EXPLOSION, SLASH, IMPACT, SHOCKWAVE -> true
            else -> false
        }
    }
}

/** 一条场景特效：触发段落 + 类型 + 强度 + 持续时长 */
data class SceneEffectCue(
    /** 触发段落在章节 contentList 中的下标 */
    val para: Int,
    val kind: SceneEffectKind,
    /** 强度 1..4，映射粒子数量与下落速度（与站点 weather.json 同一套数值） */
    val level: Int = 2,
    /** 播放时长（毫秒），到时自动淡出停止 */
    val durationMs: Long = 8000L,
    /** 是否在室内：室内不播雨雪/落叶/花瓣/闪电 */
    val indoors: Boolean = false,
    /** 是否有风：影响横向漂移与雨丝倾角 */
    val wind: Boolean = false,
    /** 是否叠加闪电轨道（与雨雪同时出现） */
    val lightning: Boolean = false,
    /** 来源：local（关键词规则）/ ai（大模型增强）/ rule-effect / ai-effect（音效联动） */
    val source: String = "local",
    /** 拟声文字/符号类特效显示的内容（COMIC 用） */
    val text: String? = null,
    /** 瞬时爆发层：true 时由 SceneEffectView.burst() 绘制，不占用持续型 currentCue */
    val burst: Boolean = false,
    /** 触发点在该段内的比例（音效联动时带过来，便于排查与去重） */
    val ratio: Float? = null,
    /** 触发它的音效名（去掉「音效」后缀），用于日志与同句去重 */
    val sourceEffectName: String? = null,
    /**
     * 可选序列帧素材 id：assets/fx/<fxId>.png（+同名 .json）。
     * 存在则优先播开源特效导出的精灵图，不存在自动回退到 Canvas 算法绘制。
     */
    val fxId: String? = null,
    /** 常驻 idle 层统一放大倍率；触发型特效默认 1，不改动原强度 */
    val sizeScale: Float = 1f
)

/** 事件 EventBus.SCENE_EFFECT_READY 的载荷：章节 key + 该章当前全部特效 */
data class SceneEffectReady(
    val key: String,
    val items: List<SceneEffectCue>
)

/**
 * 事件 EventBus.SOUND_EFFECT_FIRED 的载荷：一个音效被真正触发。
 *
 * 由 BaseReadAloudService.checkAndPlayEffects() 在规则音效 / AI 音效命中时发出，
 * 不改动 ttsrv-replaces*.json 与 ai_effect_index_kw.json 的任何内容。
 */
data class SoundEffectFired(
    val para: Int,
    val ratio: Float,
    val name: String,
    val fileName: String,
    val isAiEffect: Boolean,
    val charOffset: Int
)

/**
 * 当前章节特效的内存暂存：界面打开/重建时无需等服务重新生成。
 * 完全镜像 [DanmakuStore] 的行为。
 */
object SceneEffectStore {

    @Volatile
    var key: String? = null
        private set

    @Volatile
    private var items: List<SceneEffectCue> = emptyList()

    fun put(newKey: String, newItems: List<SceneEffectCue>) {
        key = newKey
        items = newItems
    }

    /** 合并一个区间的生成结果并返回该章全量特效；key 不匹配（已切章）时替换 */
    @Synchronized
    fun merge(newKey: String, newItems: List<SceneEffectCue>): List<SceneEffectCue> {
        if (key != newKey) {
            key = newKey
            items = newItems
        } else {
            items = (items + newItems).distinctBy { it.para to it.kind }
        }
        return items
    }

    fun get(forKey: String): List<SceneEffectCue> =
        if (key == forKey) items else emptyList()

    fun clear() {
        key = null
        items = emptyList()
    }
}
