package io.legado.app.model

import android.content.Context
import splitties.init.appCtx

/**
 * 屏幕特效的「启用/禁用」配置与展示目录。
 *
 * 用法与音效规则管理类似：每条特效一个开关，关闭后正常朗读时不再生成、
 * 不再绘制该类画面；但管理界面里仍可试播（SceneEffectView.previewMode）。
 *
 * 只持久化「被禁用」的 kind 名，默认全部启用；新增特效类型自动启用。
 */
object SceneEffectSettings {

    private const val PREF_NAME = "scene_effect_settings"
    private const val KEY_DISABLED = "disabled_kinds"
    private const val KEY_ALGO_DISABLED = "disabled_algo_kinds"
    private const val KEY_RESET_MIGRATION = "reset_migration_v1"

    /** 算法版默认全部启用：素材删掉的大类也要能靠原版矢量绘制正常触发 */
    private val DEFAULT_ALGO_DISABLED = emptySet<String>()

    /** 已确认不要的大类：界面不显示、运行时也不触发；复位不会把它们带回来 */
    private val REMOVED_KINDS = setOf("FROST", "RIPPLE", "SHIMMER", "QUESTION")

    data class Entry(
        val kind: SceneEffectKind,
        val name: String,
        val group: String,
        val desc: String
    )

    /** 52 种效果的中文名与分组，顺序即列表顺序 */
    private val allEntries: List<Entry> = listOf(
        // 天气与自然
        Entry(SceneEffectKind.RAIN, "下雨", "天气与自然", "倾斜雨丝，强度 1~4 控制密度与速度"),
        Entry(SceneEffectKind.SNOW, "飘雪", "天气与自然", "多形状雪花，带横摆与翻转"),
        Entry(SceneEffectKind.PETALS, "花瓣", "天气与自然", "不对称花瓣，正弦摆动飘落"),
        Entry(SceneEffectKind.LEAVES, "落叶", "天气与自然", "漫画秋叶，正反面换色"),
        Entry(SceneEffectKind.LIGHTNING, "闪电", "天气与自然", "分叉闪电 + 全屏照亮"),
        Entry(SceneEffectKind.FIREFLIES, "萤火虫", "天气与自然", "呼吸式黄绿光点"),
        Entry(SceneEffectKind.EMBERS, "火星余烬", "天气与自然", "上升火星，带拖尾与光晕"),
        Entry(SceneEffectKind.WIND, "风", "天气与自然", "横向平流的尘埃粒子"),
        // 环境与生物
        Entry(SceneEffectKind.FOG, "雾", "环境与生物", "多层流动雾带"),
        Entry(SceneEffectKind.SMOKE, "烟", "环境与生物", "深灰烟团缓慢流动"),
        Entry(SceneEffectKind.BUBBLES, "气泡", "环境与生物", "折射环气泡，从下往上飘"),
        Entry(SceneEffectKind.RAYS, "光束", "环境与生物", "体积光梯形 + 光尘"),
        Entry(SceneEffectKind.DUST, "浮尘", "环境与生物", "三景深逆光浮尘"),
        Entry(SceneEffectKind.BUTTERFLIES, "蝴蝶", "环境与生物", "扇翅蝴蝶群"),
        Entry(SceneEffectKind.BIRDS_GULL, "海鸥", "环境与生物", "白鸥滑翔"),
        Entry(SceneEffectKind.BIRDS_CROW, "乌鸦", "环境与生物", "黑鸦盘旋"),
        Entry(SceneEffectKind.BIRD_FLOCK, "鸟群", "环境与生物", "成群飞鸟掠过"),
        // 漫画符号
        Entry(SceneEffectKind.SPARKLES, "闪光", "漫画符号", "星芒闪烁"),
        Entry(SceneEffectKind.SWEAT, "冷汗", "漫画符号", "额角汗滴"),
        Entry(SceneEffectKind.HEART, "心动", "漫画符号", "飘心"),
        Entry(SceneEffectKind.ANGER, "怒气", "漫画符号", "青筋怒符号"),
        Entry(SceneEffectKind.ZZZ, "睡意", "漫画符号", "Zzz 浮动"),
        Entry(SceneEffectKind.LIGHTBULB, "灵光一闪", "漫画符号", "灯泡顿悟"),
        Entry(SceneEffectKind.COMIC, "拟声字", "漫画符号", "砰/轰/唰 等漫画字"),
        // 粒子与演出
        Entry(SceneEffectKind.STARS, "星空", "粒子与演出", "银河带 + 十字闪烁星"),
        Entry(SceneEffectKind.METEORS, "流星", "粒子与演出", "拖尾流星"),
        Entry(SceneEffectKind.FIREWORKS, "烟花", "粒子与演出", "升空→爆裂→消散三段式"),
        Entry(SceneEffectKind.FIRE, "火焰", "粒子与演出", "项目原版：底部上升的暖色火光点 + 柔光晕"),
        Entry(SceneEffectKind.CONFETTI, "彩屑", "粒子与演出", "旋转彩带，正反面明暗"),
        Entry(SceneEffectKind.FOUNTAIN, "喷泉", "粒子与演出", "抛物线水珠 + 落地水花"),
        Entry(SceneEffectKind.BOKEH, "光斑", "粒子与演出", "景深柔光斑，空闲时常驻"),
        Entry(SceneEffectKind.FEATHERS, "羽毛", "粒子与演出", "白羽缓慢飘落"),
        Entry(SceneEffectKind.DANDELION, "蒲公英", "粒子与演出", "绒球飞絮"),
        // 战斗与爆发
        Entry(SceneEffectKind.EXPLOSION, "爆炸", "战斗与爆发", "闪光+热核+冲击环+碎片+震屏"),
        Entry(SceneEffectKind.SLASH, "斩击", "战斗与爆发", "多层发光刀光 + 速度线"),
        Entry(SceneEffectKind.IMPACT, "命中", "战斗与爆发", "集中线 + 爆点 + 冲击环"),
        Entry(SceneEffectKind.SHOCKWAVE, "冲击波", "战斗与爆发", "五圈扩散环 + 泛白"),
        // 法术与奇景
        Entry(SceneEffectKind.ARCANE, "法术", "法术与奇景", "旋转符文环 + 能量核"),
        Entry(SceneEffectKind.HOLY, "圣光", "法术与奇景", "自上而下的光柱与光尘"),
        Entry(SceneEffectKind.SHADOW, "暗影", "法术与奇景", "扩散黑雾"),
        Entry(SceneEffectKind.PORTAL, "传送门", "法术与奇景", "椭圆裂隙 + 旋转光点"),
        Entry(SceneEffectKind.PROJECTILE, "飞矢", "法术与奇景", "多道拖尾弹丸"),
        Entry(SceneEffectKind.MAGIC_CIRCLE, "法阵", "法术与奇景", "多层法阵 + 符文"),
        Entry(SceneEffectKind.WAVE, "波浪", "法术与奇景", "横向水波起伏"),
        Entry(SceneEffectKind.SPARK_JET, "火花喷射", "法术与奇景", "扇形喷射火花"),
        Entry(SceneEffectKind.RED_PACKET, "红包雨", "法术与奇景", "红包/金币飘落"),
        Entry(SceneEffectKind.KOI, "锦鲤", "法术与奇景", "游鱼巡游 + 水纹"),
        Entry(SceneEffectKind.DOOM_FIRE, "烈焰", "法术与奇景", "大面积真实感火焰")
    )

    /** 全部 52 个大类都显示：素材删掉的只剩原版算法效果，仍可开关与测试 */
    val entries: List<Entry> = allEntries

    val groups: List<String> = entries.map { it.group }.distinct()

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    init {
        // 覆盖安装后，图片素材时代留下的「素材禁用 / 算法版关闭 / 大类关闭」
        // 会让新版本里很多效果触发不了，首次启动自动清一次。
        runCatching {
            if (!prefs.contains(KEY_RESET_MIGRATION)) resetAll()
        }
    }

    private fun disabled(): Set<String> =
        prefs.getStringSet(KEY_DISABLED, emptySet())?.toSet() ?: emptySet()

    fun isEnabled(kind: SceneEffectKind): Boolean =
        kind.name !in REMOVED_KINDS && kind.name !in disabled()

    fun setEnabled(kind: SceneEffectKind, enabled: Boolean) {
        val next = disabled().toMutableSet()
        if (enabled) next.remove(kind.name) else next.add(kind.name)
        prefs.edit().putStringSet(KEY_DISABLED, next).apply()
    }

    fun setAll(enabled: Boolean) {
        prefs.edit().putStringSet(
            KEY_DISABLED,
            if (enabled) emptySet() else entries.mapTo(HashSet()) { it.kind.name }
        ).apply()
    }

    fun disabledCount(): Int = entries.count { !isEnabled(it.kind) }

    /** 复位：清空三组开关，恢复默认「全部启用」，并记下已完成迁移 */
    fun resetAll() {
        prefs.edit()
            .putStringSet(KEY_DISABLED, emptySet())
            .putStringSet(KEY_DISABLED_FX, emptySet())
            .putStringSet(KEY_ALGO_DISABLED, emptySet())
            .putBoolean(KEY_RESET_MIGRATION, true)
            .apply()
    }

    // ===== 原版矢量/算法绘制：每个大类一条，独立开关 =====

    private fun algoDisabled(): Set<String> =
        prefs.getStringSet(KEY_ALGO_DISABLED, null)?.toSet() ?: DEFAULT_ALGO_DISABLED

    fun isAlgoEnabled(kind: SceneEffectKind): Boolean = kind.name !in algoDisabled()

    fun setAlgoEnabled(kind: SceneEffectKind, enabled: Boolean) {
        val next = algoDisabled().toMutableSet()
        if (enabled) next.remove(kind.name) else next.add(kind.name)
        prefs.edit().putStringSet(KEY_ALGO_DISABLED, next).apply()
    }

    fun setAllAlgo(enabled: Boolean) {
        prefs.edit().putStringSet(
            KEY_ALGO_DISABLED,
            if (enabled) emptySet() else entries.mapTo(HashSet()) { it.kind.name }
        ).apply()
    }

    fun algoDisabledCount(): Int = entries.count { !isAlgoEnabled(it.kind) }

    // ===== 第二级：单个具体序列帧特效的启用/禁用 =====

    private const val KEY_DISABLED_FX = "disabled_fx"

    private fun disabledFx(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_FX, emptySet())?.toSet() ?: emptySet()

    fun isFxEnabled(id: String): Boolean = id !in disabledFx()

    fun setFxEnabled(id: String, enabled: Boolean) {
        val next = disabledFx().toMutableSet()
        if (enabled) next.remove(id) else next.add(id)
        prefs.edit().putStringSet(KEY_DISABLED_FX, next).apply()
    }

    /** 批量设置具体特效（id 清单由调用方从 FxCatalog 取，避免配置层依赖 assets） */
    fun setAllFx(ids: List<String>, enabled: Boolean) {
        prefs.edit().putStringSet(
            KEY_DISABLED_FX,
            if (enabled) emptySet() else ids.toHashSet()
        ).apply()
    }

    fun disabledFxCount(): Int = disabledFx().size
}
