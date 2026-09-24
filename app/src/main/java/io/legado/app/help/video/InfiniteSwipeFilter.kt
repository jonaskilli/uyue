package io.legado.app.help.video

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON
import splitties.init.appCtx

/**
 * 无限刷分类筛选配置。
 *
 * 每个视频源一个**总开关**，每个视频源下的每一个发现分类一个**开关**；
 * 只有开启的源、以及开启的分类才会参与 [InfiniteSwipeFeed] 的后台随机获取。
 *
 * 持久化到 SharedPreferences，跨重启保留。未配置过的源/分类一律默认**开启**，
 * 因此老用户（或全新安装）不打开面板时，行为与改动前完全一致（所有源所有分类都参与）。
 */
object InfiniteSwipeFilter {

    private const val SP_NAME = "infinite_swipe_filter"
    private const val KEY = "config_v1"

    /** 视频源 url -> 该源的开关配置 */
    private val config = mutableMapOf<String, SourceEntry>()

    private val sp = appCtx.getSharedPreferences(SP_NAME, android.content.Context.MODE_PRIVATE)

    /** 单个视频源的配置：总开关 + 各分类（按 [io.legado.app.data.entities.rule.ExploreKind.kindName]）的开关 */
    class SourceEntry(
        var enabled: Boolean = true,
        val categories: MutableMap<String, Boolean> = mutableMapOf(),
    )

    private val type = object : TypeToken<Map<String, SourceEntry>>() {}.type

    init {
        runCatching {
            val str = sp.getString(KEY, null) ?: return@runCatching
            val map = GSON.fromJson<Map<String, SourceEntry>>(str, type) ?: return@runCatching
            config.clear()
            config.putAll(map)
        }
    }

    private fun save() {
        runCatching { sp.edit().putString(KEY, GSON.toJson(config)).apply() }
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
     * 是否一个视频源都没开启（全新安装 / 用户全部关掉）。
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
