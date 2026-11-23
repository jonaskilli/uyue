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
 * { "01_通用母库": { "拟音": ["炒菜|烹饪|做饭|热油", ...], "环境声": [...] }, ... }
 * ```
 *
 * 每个条目形如 `正名|关键词|关键词…`：
 * - **正名**（`|` 之前）即音频文件名，`路径 = 分类/子分类/正名.mp3`，**永不改动**；
 * - **关键词**（`|` 之后）只参与检索打分，不进文件名、不发给 AI 挑选。
 *   存在的意义：AI 第一次只吐 1~2 字关键字，而音效名中位数 4 字，
 *   靠「字面子串」检索会漏掉所有同义改写（AI 给"走路"、库里叫"脚步声"）。
 *   挂上同义/关联词后，这类改写也能命中。
 * - 没有 `|` 的条目按「无关键词」处理，与老格式完全兼容。
 *
 * 数据源优先级（**本地文件优先，不是远端优先**）：
 * 1. 外部文件 [indexFile] —— 存在就直接用，**不联网**（用户可在「音效规则管理」里编辑、增删）
 * 2. 外部文件不存在（或损坏）时，先把老版本文件迁移过来并补关键词（见 [migrateFromOld]）
 * 3. 仍拿不到时，从远端直链下载到该目录，再使用
 * 4. 下载失败（无网/超时）时，把 assets 内置索引导出到该目录，再使用
 *
 * 这样：已有文件的用户不受影响、不耗流量、编辑不会被覆盖；
 * 新装/清过目录的用户能拿到最新的远端索引，而不是 APK 里可能过期的一份。
 */
object AiEffectIndex {

    private const val TAG = "AI音效"
    private const val ASSET_FILE = "ai_effect_index_kw.json"

    /**
     * 外部索引文件名，放在音效库根目录下。
     *
     * 带 `_kw` 后缀是**有意为之**：本版索引在音效名后用 `|` 挂了检索关键词
     * （如 `炒菜|烹饪|做饭|热油`）。老版本会把整串当音效名去拼 `.mp3` 路径，
     * 结果远端 404、该音效静默失效。换文件名让新旧版本各读各的、互不干扰，
     * 需要回滚时删掉本文件即可回到老版本的行为。
     */
    const val INDEX_FILE_NAME = "ai_effect_index_kw.json"

    /**
     * 老版本索引文件名。**只读不写、永不删除**，仅用于一次性迁移：
     * 新文件不存在而它存在时，以它为基础生成新文件（见 [migrateFromOld]），
     * 保住用户在老文件里的增删改名。
     */
    private const val OLD_INDEX_FILE_NAME = "ai_effect_index.json"

    /**
     * 正名与关键词的分隔符。
     * 选 `|` 是因为：音效库 4524 个名字里不含它，
     * 且界面改名逻辑（`renameAiSound`）会把 `|` 过滤成 `_`，
     * 等于从源头保证了正名里不会出现分隔符。
     */
    private const val KEYWORD_SEP = '|'

    /** 索引条目里的禁用前缀：`@disabled|正名|关键词…`，旧格式默认启用 */
    private const val DISABLED_MARK = "@disabled"

    /**
     * 外部索引文件，位于音效库目录下。
     * **只要它存在就用它**，不联网、不覆盖，用户可在「音效规则管理」里自由编辑。
     */
    fun indexFile(): File = File(AppConfig.aiEffectLocalDir, INDEX_FILE_NAME)

    /**
     * 远端索引直链（中文文件名已 percent-encode）。
     * 仅在外部文件不存在（且迁移也没产出）时下载，作为「比 APK 内置更新」的默认来源。
     *
     * 指向的是**带关键词的新版** `广播剧音效库_目录索引_带关键词.json`，
     * 与老版本下载的 `广播剧音效库_目录索引.json` 是两个文件：
     * 老版本即便全新安装也只会拿到老格式，不会碰到带 `|` 的名字。
     */
    private const val REMOTE_INDEX_URL =
        "https://cnb.cool/mingwuyan/cnb-chat/-/git/raw/main/" +
            "%E5%B9%BF%E6%92%AD%E5%89%A7%E9%9F%B3%E6%95%88%E5%BA%93_" +
            "%E7%9B%AE%E5%BD%95%E7%B4%A2%E5%BC%95_" +
            "%E5%B8%A6%E5%85%B3%E9%94%AE%E8%AF%8D.json?download=true"

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
     * @param name 音效**正名**（不含关键词），如 "炒菜"。它同时是音频文件名，不可随意改动
     * @param path 相对路径（含文件名），如 "01_通用母库/拟音/炒菜.mp3"，由 [name] 拼成
     * @param keywords 检索关键词（2 字为主），只参与 [search] 打分，
     *                 不进文件名、不发给 AI 挑选。如 ["烹饪", "做饭", "热油", "厨房"]
     */
    data class Entry(
        val name: String,
        val path: String,
        val category: String,
        val sub: String,
        val keywords: List<String> = emptyList(),
        val isEnabled: Boolean = true
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
     * 加载索引：外部文件 -> 老版本迁移 -> 远端下载 -> 内置导出。
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
        // 2. 新文件还没有：把老版本那份迁过来，别让用户之前的编辑白做
        if (migrateFromOld()) {
            try {
                parse(file.readText())
                loaded = true
                AppLog.put("$TAG: 已从老版本索引迁移，共 ${entries.size} 个音效")
                return
            } catch (e: Exception) {
                AppLog.put("$TAG: 迁移结果解析失败，继续取远端: ${e.localizedMessage}", e)
            }
        }
        // 3. 仍然没有：从远端下载到该目录
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
        // 4. 下载失败（无网等）：导出内置索引到该目录
        loadFromAssets(file)
    }

    /**
     * 把老版本索引文件（[OLD_INDEX_FILE_NAME]）迁成新版文件。
     *
     * 只在**新文件不存在**时执行，因此天然幂等：迁移过一次之后就不会再跑。
     * 做法是「以老文件的条目为准 + 从内置资源按正名补关键词」——
     * 老文件里用户删掉的条目不会被远端/内置塞回来，改过名也不会被冲掉，
     * 迁完之后再走 [load] 的正常读取，等于只多做了一次关键词补全。
     *
     * 关键词取自内置资源而不是远端，是为了**离线也能迁**，
     * 且不因为一次迁移就触发联网。
     *
     * 老文件保持只读，不删不改，随时可回滚。
     * @return 是否成功写出了新文件
     */
    private fun migrateFromOld(): Boolean {
        val file = indexFile()
        if (file.exists() && file.length() > 0L) return false
        val old = File(AppConfig.aiEffectLocalDir, OLD_INDEX_FILE_NAME)
        if (!old.exists() || old.length() == 0L) return false
        return try {
            val oldEntries = parseToEntries(old.readText())
            if (oldEntries.isEmpty()) return false
            val kwMap = assetKeywordMap()
            val grouped = LinkedHashMap<String, LinkedHashMap<String, ArrayList<String>>>()
            oldEntries.forEach { e ->
                val subs = grouped.getOrPut(e.category) { LinkedHashMap() }
                val list = subs.getOrPut(e.sub) { ArrayList() }
                // 老文件里的名字本身就是正名（没有 |），直接查表补关键词
                val kws = kwMap["${e.category}/${e.sub}/${e.name}"].orEmpty()
                list.add(serialize(e.name, kws))
            }
            val obj = JSONObject()
            grouped.forEach { (cat, subs) ->
                val subObj = JSONObject()
                subs.forEach { (sub, list) -> if (list.isNotEmpty()) subObj.put(sub, JSONArray(list)) }
                if (subObj.length() > 0) obj.put(cat, subObj)
            }
            writeExternal(obj.toString())
            AppLog.put("$TAG: 已迁移老版本索引 ${oldEntries.size} 条 -> ${file.absolutePath}")
            true
        } catch (e: Exception) {
            AppLog.put("$TAG: 老版本索引迁移失败 ${e.localizedMessage}", e)
            false
        }
    }

    /** 内置索引里「分类/子分类/正名 -> 关键词」的查表，供迁移补关键词用 */
    private fun assetKeywordMap(): Map<String, List<String>> = try {
        val text = appCtx.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        parseToEntries(text).associate { "${it.category}/${it.sub}/${it.name}" to it.keywords }
    } catch (e: Exception) {
        AppLog.put("$TAG: 内置关键词表读取失败 ${e.localizedMessage}")
        emptyMap()
    }

    /** 条目序列化回索引里的字符串：`正名|关键词|关键词…`，禁用项加 `@disabled|` 前缀 */
    private fun serialize(name: String, keywords: List<String>, isEnabled: Boolean = true): String {
        val body = if (keywords.isEmpty()) name else name + "|" + keywords.joinToString("|")
        return if (isEnabled) body else "$DISABLED_MARK|$body"
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
        val list = parseToEntries(raw)
        val map = HashMap<String, Entry>(7000)
        list.forEach { e -> if (!map.containsKey(e.name)) map[e.name] = e }
        entries = list
        byName = map
    }

    /**
     * 解析索引文本为条目列表，不改动内存状态（供 [parse] 与迁移复用）。
     *
     * 单条格式 `正名|关键词|关键词…`：`|` 之前是正名（= 文件名），之后是检索关键词。
     * 没有 `|` 就是老格式，关键词为空。
     */
    fun parseToEntries(raw: String): List<Entry> {
        val obj = JSONObject(raw)
        val list = ArrayList<Entry>(7000)
        val cats = obj.keys()
        while (cats.hasNext()) {
            val cat = cats.next()
            val subObj = obj.optJSONObject(cat) ?: continue
            val subs = subObj.keys()
            while (subs.hasNext()) {
                val sub = subs.next()
                val arr = subObj.optJSONArray(sub) ?: continue
                for (i in 0 until arr.length()) {
                    val rawName = arr.optString(i).trim()
                    if (rawName.isEmpty()) continue
                    var parts = rawName.split(KEYWORD_SEP)
                    var isEnabled = true
                    if (parts.firstOrNull()?.trim() == DISABLED_MARK) {
                        isEnabled = false
                        parts = parts.drop(1)
                    }
                    val name = parts.firstOrNull()?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    val keywords = parts.drop(1)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    val entry = Entry(name, "$cat/$sub/$name.mp3", cat, sub, keywords, isEnabled)
                    list.add(entry)
                }
            }
        }
        return list
    }

    /** 按音效名精确查找，未加载、已禁用或找不到返回 null */
    fun findByName(name: String): Entry? {
        if (!loaded) return null
        return byName[name.trim()]?.takeIf { it.isEnabled }
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
        val seen = HashSet<String>()
        for (e in newEntries) {
            if (!seen.add("${e.category}/${e.sub}/${e.name}")) continue
            val subs = grouped.getOrPut(e.category) { LinkedHashMap() }
            val names = subs.getOrPut(e.sub) { ArrayList() }
            // 必须把关键词和启用状态一起写回，否则用户在界面里改一条再保存，
            // 四千多条音效的关键词/禁用项会被整批冲掉
            names.add(serialize(e.name, e.keywords, e.isEnabled))
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
     * 按单个关键字搜索。同分按名字长度升序。
     * 打分见 [scoreOf]。
     */
    fun search(keyword: String, limit: Int): List<Entry> {
        if (!loaded || entries.isEmpty()) return emptyList()
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Entry, Int>>()
        for (e in entries) {
            if (!e.isEnabled) continue
            val s = scoreOf(e, kw)
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

    /**
     * 单个关键字对单个音效的打分，**名字命中永远压过关键词命中**。
     *
     * ```
     * 3  正名 == 关键字        （最准：AI 给的正是库里的名字）
     * 2  正名 包含 关键字      （"脚步" -> "木地板脚步声"）
     * 2  某个关键词 == 关键字  （"走路" -> "脚步声|走路|步行"）
     * 1  某个关键词 包含 关键字（"走路" -> "…|走路声|…"）
     * 1  关键字 包含 正名      （正名 1~2 字时的兜底，沿用旧行为）
     * 0  无关
     * ```
     *
     * 为什么关键词命中必须低于同级的名字命中：候选池只有
     * `aiEffectMaxCandidates`（默认 300）、每个关键字取前
     * `aiEffectCandidatesPerKeyword`（默认 40）条。加了关键词后命中面变宽，
     * 若不给名字命中优先权，泛词关键字（"厨房""声音"）会靠关键词命中挤掉
     * 名字直接命中的结果，加了关键词反而更不准。
     */
    private fun scoreOf(e: Entry, keyword: String): Int {
        val name = e.name
        if (name == keyword) return 3
        if (name.contains(keyword)) return 2
        if (e.keywords.isEmpty()) return if (keyword.contains(name)) 1 else 0
        if (e.keywords.any { it == keyword }) return 2
        if (e.keywords.any { it.contains(keyword) }) return 1
        return if (keyword.contains(name)) 1 else 0
    }
}
