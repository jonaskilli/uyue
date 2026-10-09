package io.legado.app.help.video

import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON
import splitties.init.appCtx

/**
 * 无限刷分类筛选配置（**两套独立模式**）。
 *
 * 每个视频源一个**总开关**，每个视频源下的每一个发现分类一个**开关**；
 * 只有开启的源、以及开启的分类才会参与 [InfiniteSwipeFeed] 的后台随机获取。
 *
 * ## 模式一 / 模式二
 * 两套配置**完全独立、互不干扰**：模式一里选的书源分类与模式二无关，
 * 切到模式二就是另一套（默认一个都没开），可以随时来回切。
 * 切换后由调用方清空已保存的播放列表与分页进度，再按新模式重新抓。
 *
 * 持久化到 SharedPreferences（每个模式一份），跨重启保留。
 * 未配置过的源/分类一律默认**关闭**，由用户自己挑要用哪些书源。
 */
object InfiniteSwipeFilter {

    const val MODE_1 = 1
    const val MODE_2 = 2

    private const val SP_NAME = "infinite_swipe_filter"
    private const val KEY_MODE = "current_mode"
    private const val KEY_PREFIX = "config_v2_m"
    /** 老版本只有一份配置，升级后作为模式一的初始值，避免配置丢失 */
    private const val KEY_LEGACY = "config_v1"
    private const val DEFAULT_MODE = MODE_1

    private val sp = appCtx.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)

    /** 单个视频源的配置：总开关 + 各分类（按 [io.legado.app.data.entities.rule.ExploreKind.kindName]）的开关 */
    class SourceEntry(
        var enabled: Boolean = true,
        val categories: MutableMap<String, Boolean> = mutableMapOf(),
    )

    private val type = object : TypeToken<Map<String, SourceEntry>>() {}.type

    /** 当前模式（[MODE_1] / [MODE_2]），持久化 */
    @Volatile
    var mode: Int = DEFAULT_MODE
        private set

    /** 当前模式的配置：视频源 url -> 该源的开关配置 */
    private var config: MutableMap<String, SourceEntry> = mutableMapOf()

    init {
        mode = if (sp.getInt(KEY_MODE, DEFAULT_MODE) == MODE_2) MODE_2 else MODE_1
        config = loadConfig(mode)
    }

    private fun keyOf(m: Int): String = "$KEY_PREFIX$m"

    private fun loadConfig(m: Int): MutableMap<String, SourceEntry> {
        readMap(keyOf(m))?.let { return it }
        if (m == MODE_1) readMap(KEY_LEGACY)?.let { return it }
        return mutableMapOf()
    }

    private fun readMap(key: String): MutableMap<String, SourceEntry>? = runCatching {
        val str = sp.getString(key, null) ?: return@runCatching null
        val map = GSON.fromJson<Map<String, SourceEntry>>(str, type) ?: return@runCatching null
        mutableMapOf<String, SourceEntry>().apply { putAll(map) }
    }.getOrNull()

    private fun save() {
        runCatching { sp.edit().putString(keyOf(mode), GSON.toJson(config)).apply() }
    }

    /**
     * 切换模式：换用另一套独立配置（模式一与模式二互不影响）。
     * 切换后需要清空旧模式抓出来的播放列表，再按新模式重新抓（见 [InfiniteSwipeFeed.onModeSwitched]）。
     */
    fun setMode(m: Int) {
        val target = if (m == MODE_2) MODE_2 else MODE_1
        if (target == mode) return
        mode = target
        config = loadConfig(target)
        runCatching { sp.edit().putInt(KEY_MODE, target).apply() }
    }

    /**
     * 该视频源的总开关。**默认关闭**：没在筛选面板里选过的源一律不参与无限刷，
     * 由用户长按「无限刷」按钮自己挑要用哪些书源。
     */
    fun isSourceEnabled(url: String): Boolean = config[url]?.enabled ?: false

    /** 该视频源下某分类是否开启（源关闭时一律 false） */
    fun isCategoryEnabled(url: String, kindName: String): Boolean {
        val entry = config[url] ?: return false
        if (!entry.enabled) return false
        // 老配置：只开了源、没记分类明细 → 视为全部开启，避免升级后突然一个分类都没有
        if (entry.categories.isEmpty()) return true
        return entry.categories[kindName] ?: true
    }

    /**
     * 该分类**自身**是否被勾选（**不受视频源总开关影响**）。
     *
     * 面板里源的总开关是"该源所有分类的聚合状态"，读取时必须用这个原始值，
     * 否则源一关 → [isCategoryEnabled] 全部返回 false → 总开关算出来永远是"关闭"，
     * 单独勾分类时总开关就不会有任何变化。
     */
    fun isCategoryChecked(url: String, kindName: String): Boolean {
        val entry = config[url] ?: return false // 没配置过的源：分类一律未勾选
        if (entry.categories.isEmpty()) return true // 老配置：视为全部勾选
        return entry.categories[kindName] ?: true  // 后来新增的分类默认跟随开启
    }

    /**
     * 当前模式下是否一个视频源都没开启（全新安装 / 用户全部关掉 / 新模式还没选）。
     * 此时直接点「无限刷」刷不出任何东西，应当提示先长按按钮去选书源。
     */
    fun hasNoEnabledSource(): Boolean = config.values.none { it.enabled }

    /** 取/建该源的配置条目（用于 UI 读写） */
    fun getEntry(url: String): SourceEntry = config.getOrPut(url) { SourceEntry() }

    /** 该源下参与抓取的分类名集合；null 表示"全部开启"，空集合表示"全部关闭" */
    fun enabledCategoryNames(url: String): Set<String>? {
        val entry = config[url] ?: return null
        if (!entry.enabled) return emptySet()
        if (entry.categories.isEmpty()) return null
        return entry.categories.filterValues { it }.keys.toSet().ifEmpty { emptySet() }
    }

    fun setSourceEnabled(url: String, enabled: Boolean) {
        getEntry(url).enabled = enabled
        save()
    }

    fun setCategoryEnabled(url: String, kindName: String, enabled: Boolean) {
        getEntry(url).categories[kindName] = enabled
        save()
    }
}
