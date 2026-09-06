package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * 广播剧音效库索引。
 *
 * 结构为
 * ```json
 * { "01_通用母库": { "拟音": ["炒菜", ...], "环境声": [...] }, ... }
 * ```
 *
 * 数据源优先级：
 * 1. 外部文件 [indexFile]（用户可在「音效规则管理」里编辑、增删）
 * 2. 不存在时从 assets 内置文件导出一份到该路径，再加载
 *
 * 这样用户既能直接用内置库，也能自行裁剪（比如删掉用不上的音效、缩短 AI 候选范围）。
 */
object AiEffectIndex {

    private const val TAG = "AiEffectIndex"
    private const val ASSET_FILE = "ai_effect_index.json"

    /** 外部索引文件名，放在音效库根目录下 */
    const val INDEX_FILE_NAME = "ai_effect_index.json"

    /** 外部索引文件。可被用户编辑，优先于内置资源 */
    fun indexFile(): File = File(AppConfig.aiEffectLocalDir, INDEX_FILE_NAME)

    /**
     * 一个音效条目。
     * @param name 音效名，如 "炒菜"
     * @param path 相对路径（含文件名），如 "01_通用母库/拟音/炒菜.mp3"
     */
    data class Entry(
        val name: String,
        val path: String,
        val category: String,
        val sub: String
    )

    @Volatile
    private var loaded = false

    private val lock = Mutex()

    private var entries: List<Entry> = emptyList()
    private var byName: Map<String, Entry> = emptyMap()

    fun isLoaded(): Boolean = loaded

    fun size(): Int = entries.size

    /**
     * 加载索引，已加载则直接返回。可在任意线程调用，IO 在 [Dispatchers.IO] 执行。
     *
     * 优先读外部文件；不存在或不可读时，把 assets 内置索引导出到该路径再加载。
     */
    suspend fun ensureLoaded() {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) { load() }
        }
    }

    /**
     * 丢弃内存数据并重新加载。
     * 供用户在「音效规则管理」里编辑完索引后立即生效。
     */
    suspend fun reload() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                entries = emptyList()
                byName = emptyMap()
                loaded = false
                load()
            }
        }
    }

    private fun load() {
        val file = indexFile()
        // 1. 外部文件优先
        if (file.exists() && file.length() > 0L) {
            try {
                parse(file.readText())
                loaded = true
                AppLog.put("$TAG: 从外部文件加载索引，共 ${entries.size} 个音效")
                return
            } catch (e: Exception) {
                AppLog.put("$TAG: 外部索引解析失败，回退内置: ${e.localizedMessage}", e)
            }
        }
        // 2. 导出内置索引到外部（便于用户查看与修改），失败则直接用内置
        val text = try {
            appCtx.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            AppLog.put("$TAG: 内置索引读取失败 ${e.localizedMessage}", e)
            return
        }
        try {
            file.parentFile?.mkdirs()
            file.writeText(text)
            AppLog.put("$TAG: 已导出内置索引到 ${file.absolutePath}")
        } catch (e: Exception) {
            // 导出失败不致命（多为分区存储权限问题），仍用内置内容继续
            AppLog.put("$TAG: 内置索引导出失败，仅使用内存数据: ${e.localizedMessage}")
        }
        parse(text)
        loaded = true
        AppLog.put("$TAG: 索引加载完成，共 ${entries.size} 个音效")
    }

    /** 解析索引 JSON，替换内存中的数据 */
    fun parse(raw: String) {
        val obj = JSONObject(raw)
        val list = ArrayList<Entry>(7000)
        val map = HashMap<String, Entry>(7000)
        val cats = obj.keys()
        while (cats.hasNext()) {
            val cat = cats.next()
            val subObj = obj.optJSONObject(cat) ?: continue
            val subs = subObj.keys()
            while (subs.hasNext()) {
                val sub = subs.next()
                val arr = subObj.optJSONArray(sub) ?: continue
                for (i in 0 until arr.length()) {
                    val name = arr.optString(i).trim()
                    if (name.isEmpty()) continue
                    val entry = Entry(name, "$cat/$sub/$name.mp3", cat, sub)
                    list.add(entry)
                    if (!map.containsKey(name)) map[name] = entry
                }
            }
        }
        entries = list
        byName = map
    }

    /** 按音效名精确查找，未加载或找不到返回 null */
    fun findByName(name: String): Entry? {
        if (!loaded) return null
        return byName[name.trim()]
    }

    /** 所有音效条目，供界面展示与编辑 */
    fun allEntries(): List<Entry> = entries

    /**
     * 用外界编辑好的条目整体替换索引并写入外部文件。
     * 索引文件优先于内置资源，因此写盘后下次启动仍是这份。
     * @return 成功返回 null，失败返回错误信息
     */
    fun replaceAll(newEntries: List<Entry>): String? {
        val grouped = LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>>()
        for (e in newEntries) {
            val subs = grouped.getOrPut(e.category) { LinkedHashMap() }
            val names = subs.getOrPut(e.sub) { ArrayList() }
            if (e.name !in names) names.add(e.name)
        }
        val obj = JSONObject()
        grouped.forEach { (cat, subs) ->
            val subObj = JSONObject()
            subs.forEach { (sub, names) ->
                if (names.isNotEmpty()) subObj.put(sub, JSONArray(names))
            }
            if (subObj.length() > 0) obj.put(cat, subObj)
        }
        return try {
            val file = indexFile()
            file.parentFile?.mkdirs()
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(obj.toString())
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
            entries = newEntries
            byName = newEntries.associateBy { it.name }
            AppLog.put("$TAG: 索引已保存，共 ${entries.size} 个音效")
            null
        } catch (e: Exception) {
            AppLog.put("$TAG: 索引保存失败 ${e.localizedMessage}", e)
            "保存失败：${e.localizedMessage}"
        }
    }

    /**
     * 按单个关键字搜索。
     * 打分：完全相等 3 > 名字包含关键字 2 > 关键字包含名字 1；同分按名字长度升序。
     */
    fun search(keyword: String, limit: Int): List<Entry> {
        if (!loaded || entries.isEmpty()) return emptyList()
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Entry, Int>>()
        for (e in entries) {
            val s = scoreOf(e.name, kw)
            if (s > 0) hits.add(e to s)
        }
        hits.sortWith(
            compareByDescending<Pair<Entry, Int>> { it.second }
                .thenBy { it.first.name.length }
        )
        return hits.take(limit).map { it.first }
    }

    /**
     * 用多个关键字搜索并合并候选。
     *
     * 采用**轮转分配**：先取每个关键字的最佳匹配，再取各自的次优，依次类推，
     * 直到凑满 [maxTotal]。这样关键字变多（6 个）时不会让靠前的关键字占满名额，
     * 保证每个关键字在候选列表里都有代表。
     *
     * 同一音效被多个关键字命中时保留首次（即排名更靠前）的位置。
     * @param perKeyword 每个关键字最多取几条
     * @param maxTotal 候选总量上限，防止泛词（如"声"）撑爆 prompt
     */
    fun searchByKeywords(
        keywords: List<String>,
        perKeyword: Int,
        maxTotal: Int
    ): List<Entry> {
        if (!loaded || entries.isEmpty()) return emptyList()
        // 每个关键字各自的按分数排序的候选
        val perKwLists = ArrayList<List<Entry>>()
        for (kw in keywords) {
            val hits = search(kw, perKeyword)
            if (hits.isNotEmpty()) perKwLists.add(hits)
        }
        if (perKwLists.isEmpty()) return emptyList()

        val result = ArrayList<Entry>(minOf(maxTotal, 64))
        val seen = HashSet<String>()
        var round = 0
        while (result.size < maxTotal) {
            var addedThisRound = false
            for (list in perKwLists) {
                if (round >= list.size) continue
                val e = list[round]
                if (seen.add(e.name)) {
                    result.add(e)
                    addedThisRound = true
                    if (result.size >= maxTotal) break
                }
            }
            if (!addedThisRound) break // 所有关键字的候选都取完了
            round++
        }
        return result
    }

    private fun scoreOf(name: String, keyword: String): Int = when {
        name == keyword -> 3
        name.contains(keyword) -> 2
        keyword.contains(name) -> 1
        else -> 0
    }
}
