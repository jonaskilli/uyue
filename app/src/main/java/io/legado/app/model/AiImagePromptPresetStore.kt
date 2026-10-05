package io.legado.app.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 「题材 / 画风」下拉预设的存储与增删改。
 *
 * 内置数据来自 [AiImagePresets]，首次打开时作为种子写入 SharedPreferences，
 * 之后用户的修改、删除、置顶、新增都只作用于本地这份列表。
 *
 * 合并规则（每次读取都会跑）：
 *  1. 以本地列表为准；
 *  2. 内置里出现、本地还没有的条目自动补进来 —— App 更新新增了内置题材/画风时能自动出现；
 *  3. 内置条目被用户删除后，本地保留一条 deleted 记录，不会被第 2 步复活；
 *  4. 置顶项排在最前，其余保持原有相对顺序。
 */
object AiImagePromptPresetStore {

    const val KIND_GENRE = "genre"
    const val KIND_ART_STYLE = "artStyle"

    private const val PREF_FILE = "aiImagePromptPresets"
    private const val KEY_ITEMS = "items"

    /** 一条预设。[content] 对题材是提示词模板，对画风是风格描述。 */
    data class Item(
        val id: String,
        val name: String,
        val content: String,
        val kind: String,
        val builtin: Boolean = false,
        val deleted: Boolean = false,
        val pinned: Boolean = false
    )

    private val gson = Gson()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    /** 读取某个类别的可用预设，已按「置顶优先」排好序。 */
    fun list(context: Context, kind: String): List<Item> {
        val all = readAll(context)
        val mine = all.filter { it.kind == kind && !it.deleted }
        val existingIds = all.filter { it.kind == kind }.map { it.id }.toSet()
        val builtins = builtinItems(kind).filter { it.id !in existingIds }
        return (mine + builtins).sortedByDescending { it.pinned }
    }

    /** 按名字取一条预设；重名时取第一条。 */
    fun findByName(context: Context, kind: String, name: String): Item? =
        list(context, kind).firstOrNull { it.name == name }

    private fun readAll(context: Context): List<Item> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<Item>>(raw, object : TypeToken<List<Item>>() {}.type)
        }.getOrNull().orEmpty()
    }

    private fun writeAll(context: Context, items: List<Item>) {
        prefs(context).edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    // ---------------- 内置种子 ----------------

    private fun builtinItems(kind: String): List<Item> = when (kind) {
        KIND_GENRE -> AiImagePresets.genres.map {
            Item("builtin_genre_${it.label}", it.label, it.template, KIND_GENRE, builtin = true)
        }

        KIND_ART_STYLE -> AiImagePresets.artStyles.map {
            Item("builtin_style_${it.name}", it.name, it.stylePrompt, KIND_ART_STYLE, builtin = true)
        }

        else -> emptyList()
    }

    // ---------------- 增 / 删 / 改 / 复制 / 置顶 ----------------

    /**
     * 新增一条自定义预设。按需求**自动置顶**，所以会直接出现在列表最上面。
     * 名字为空时用「我的预设N」兜底，避免下拉里出现空白项。
     */
    fun add(context: Context, kind: String, name: String, content: String): Item {
        val items = readAll(context).toMutableList()
        val sameKindCount = items.count { it.kind == kind && !it.deleted }
        val item = Item(
            id = "custom_${kind}_${System.currentTimeMillis()}",
            name = name.trim().ifBlank { "我的预设${sameKindCount + 1}" },
            content = content,
            kind = kind,
            builtin = false,
            pinned = true
        )
        items.add(item)
        writeAll(context, items)
        return item
    }

    /** 修改名称与内容。内置条目也能改，改动只落在本地列表上，不动 [AiImagePresets] 源码。 */
    fun update(context: Context, item: Item, name: String, content: String) {
        val items = readAll(context).toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) {
            // 本地还没有（内置条目被改的情况）：先补一条再改，避免改动丢失
            items.add(item.copy(name = name.trim(), content = content, builtin = false))
        } else {
            items[index] = items[index].copy(
                name = name.trim().ifBlank { items[index].name },
                content = content
            )
        }
        writeAll(context, items)
    }

    /**
     * 删除。内置条目不物理删除，只标记 [Item.deleted]，这样内置补齐逻辑不会把它复活。
     */
    fun delete(context: Context, item: Item) {
        val items = readAll(context).toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = items[index].copy(deleted = true, pinned = false)
        } else {
            items.add(item.copy(deleted = true, pinned = false))
        }
        writeAll(context, items)
    }

    /** 复制一条预设，副本同样自动置顶，名字加「 副本」后缀。 */
    fun duplicate(context: Context, item: Item): Item {
        return add(context, item.kind, "${item.name} 副本", item.content)
    }

    /** 置顶/取消置顶。 */
    fun setPinned(context: Context, item: Item, pinned: Boolean) {
        val items = readAll(context).toMutableList()
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            items[index] = items[index].copy(pinned = pinned)
        } else {
            items.add(item.copy(pinned = pinned))
        }
        writeAll(context, items)
    }
}
