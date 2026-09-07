package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 广播剧音效库索引。
 *
 * 结构为
 * ```json
 * { "01_通用母库": { "拟音": ["炒菜", ...], "环境声": [...] }, ... }
 * ```
 *
 * 数据源优先级（**本地文件优先，不是远端优先**）：
 * 1. 外部文件 [indexFile] —— 存在就直接用，**不联网**（用户可在「音效规则管理」里编辑、增删）
 * 2. 外部文件不存在（或损坏）时，从远端直链下载到该目录，再使用
 * 3. 下载失败（无网/超时）时，把 assets 内置索引导出到该目录，再使用
 *
 * 这样：已有文件的用户不受影响、不耗流量、编辑不会被覆盖；
 * 新装/清过目录的用户能拿到最新的远端索引，而不是 APK 里可能过期的一份。
 */
object AiEffectIndex {

    private const val TAG = "AiEffectIndex"
    private const val ASSET_FILE = "ai_effect_index.json"

    /** 外部索引文件名，放在音效库根目录下 */
    const val INDEX_FILE_NAME = "ai_effect_index.json"

    /**
     * 外部索引文件，位于音效库目录下。
     * **只要它存在就用它**，不联网、不覆盖，用户可在「音效规则管理」里自由编辑。
     */
    fun indexFile(): File = File(AppConfig.aiEffectLocalDir, INDEX_FILE_NAME)

    /**
     * 远端索引直链（中文文件名已 percent-encode）。
     * 仅在外部文件不存在时下载，作为「比 APK 内置更新」的默认来源。
     */
    private const val REMOTE_INDEX_URL =
        "https://cnb.cool/mingwuyan/cnb-chat/-/git/raw/main/" +
            "%E5%B9%BF%E6%92%AD%E5%89%A7%E9%9F%B3%E6%95%88%E5%BA%93_" +
            "%E7%9B%AE%E5%BD%95%E7%B4%A2%E5%BC%95.json?download=true"

    /**
     * 拉取索引用的短超时客户端。
     * [ensureLoaded] 在朗读启动时同步调用，超时必须短，否则无网/弱网会拖慢启动。
     */
    private val remoteClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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
     *
     * [load] 的优先级是「外部文件优先、存在即不联网」，所以这里复用它是安全的：
     * 编辑保存后文件必然存在，会直接读回刚写入的内容，不会被远端或内置覆盖。
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

    /**
     * 加载索引：外部文件 -> 远端下载 -> 内置导出。
     *
     * 前一级成功就直接用，不再走后面的分支。
     */
    private fun load() {
        val file = indexFile()
        // 1. 外部文件已存在：直接用，不联网。用户可能编辑过，绝不能覆盖
        if (file.exists() && file.length() > 0L) {
            try {
                parse(file.readText())
                loaded = true
                AppLog.put("$TAG: 从外部文件加载索引，共 ${entries.size} 个音效")
                return
            } catch (e: Exception) {
                // 文件损坏：备份后继续往下取一份好的
                AppLog.put("$TAG: 外部索引解析失败，重新获取: ${e.localizedMessage}", e)
            }
        }
        // 2. 外部文件不存在或已损坏：从远端下载到该目录
        val remote = downloadRemote()
        if (remote != null) {
            try {
                parse(remote)
                writeExternal(remote)
                loaded = true
                AppLog.put("$TAG: 已从远端下载索引，共 ${entries.size} 个音效")
                return
            } catch (e: Exception) {
                AppLog.put("$TAG: 远端索引解析失败，回退内置: ${e.localizedMessage}", e)
            }
        }
        // 3. 下载失败（无网等）：导出内置索引到该目录
        loadFromAssets(file)
    }

    /** 从 assets 读出内置索引，导出到外部文件并加载 */
    private fun loadFromAssets(file: File) {
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

    /** 下载远端索引，失败返回 null（调用方负责回退） */
    private fun downloadRemote(): String? = try {
        val request = Request.Builder().url(REMOTE_INDEX_URL).build()
        // 用分支表达式而非 return：避免在表达式体函数里做 non-local return
        remoteClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.put("$TAG: 远端索引下载失败 HTTP ${resp.code}")
                null
            } else {
                val text = resp.body?.string()
                if (text.isNullOrBlank()) {
                    AppLog.put("$TAG: 远端索引内容为空")
                    null
                } else {
                    text
                }
            }
        }
    } catch (e: Exception) {
        AppLog.put("$TAG: 远端索引下载异常 ${e.localizedMessage}")
        null
    }

    /**
     * 把远端索引写入外部文件。
     *
     * 正常流程只在文件不存在时走到这里（存在的话 [load] 第一步就返回了），
     * 唯一会覆盖已有文件的情况是文件损坏、解析失败。此时先备份为 `.bak`，
     * 避免用户手工编辑过的内容被静默丢弃。
     */
    private fun writeExternal(text: String) {
        val file = indexFile()
        try {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > 0L && file.readText() != text) {
                runCatching { file.copyTo(File(file.absolutePath + ".bak"), true) }
            }
            val tmp = File(file.absolutePath + ".tmp")
            tmp.writeText(text)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 落盘失败不致命：内存里已是最新索引，仅下次无网时回退到旧文件
            AppLog.put("$TAG: 远端索引落盘失败 ${e.localizedMessage}")
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
