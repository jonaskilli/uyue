package io.legado.app.service

import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 广播剧音效库索引。
 *
 * 数据来自内置 assets：`ai_effect_index.json`，结构为
 * ```json
 * { "01_通用母库": { "拟音": ["炒菜", ...], "环境声": [...] }, ... }
 * ```
 * 加载后展开为扁平列表 [Entry]，音效名全局唯一，因此可用名字直接反查路径。
 */
object AiEffectIndex {

    private const val TAG = "AiEffectIndex"
    private const val ASSET_FILE = "ai_effect_index.json"

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

    /** 加载索引，已加载则直接返回。可在任意线程调用，解析在 IO 线程执行。 */
    suspend fun ensureLoaded() {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) { loadFromAssets() }
        }
    }

    private fun loadFromAssets() {
        try {
            val text = appCtx.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            parse(text)
            loaded = true
            AppLog.put("$TAG: 索引加载完成，共 ${entries.size} 个音效")
        } catch (e: Exception) {
            AppLog.put("$TAG: 索引加载失败 ${e.localizedMessage}", e)
        }
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
