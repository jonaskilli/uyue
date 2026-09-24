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

    /** 该视频源的总开关（默认开启） */
    fun isSourceEnabled(url: String): Boolean = config[url]?.enabled ?: true

    /** 该视频源下某分类是否开启（源关闭时一律 false；分类未配置过默认开启） */
    fun isCategoryEnabled(url: String, kindName: String): Boolean {
        val entry = config[url] ?: return true
        if (!entry.enabled) return false
        return entry.categories[kindName] ?: true
    }

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
