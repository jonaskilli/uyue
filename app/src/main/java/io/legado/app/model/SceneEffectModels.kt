package io.legado.app.model

/**
 * 朗读场景特效公共数据模型。
 *
 * 复用 AI 弹幕的整套机制：整章按字数区间提前生成、绑定段落锚点、
 * 朗读到该段时播放一段时间的特效（飘雪/下雨/落叶/花瓣/闪电/萤火虫/火星/风）。
 *
 * 与弹幕的差异：内容是特效类型+强度而非文字，播放是持续 durationMs 而非飘过一次。
 */

/** 场景特效类型 */
enum class SceneEffectKind {
    RAIN, SNOW, PETALS, LEAVES, LIGHTNING, FIREFLIES, EMBERS, WIND;

    companion object {
        fun from(name: String?): SceneEffectKind? =
            values().firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }

        /** 是否属于「室外才有」的天气/植物类特效 */
        fun isOutdoorOnly(kind: SceneEffectKind): Boolean = when (kind) {
            RAIN, SNOW, PETALS, LEAVES, LIGHTNING -> true
            FIREFLIES, EMBERS, WIND -> false
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
    /** 来源：local（关键词规则）/ ai（大模型增强），便于排查 */
    val source: String = "local"
)

/** 事件 EventBus.SCENE_EFFECT_READY 的载荷：章节 key + 该章当前全部特效 */
data class SceneEffectReady(
    val key: String,
    val items: List<SceneEffectCue>
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
