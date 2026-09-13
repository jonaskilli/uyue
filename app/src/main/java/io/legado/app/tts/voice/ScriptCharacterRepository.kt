package io.legado.app.tts.voice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 朗读脚本分析结果仓库。
 *
 * 朗读脚本（JS 插件）在朗读过程中会把 AI 分析出的角色归属与角色档案写到外部存储：
 *
 * - 章节对话分析缓存
 *   `/storage/emulated/0/Download/chajian/xiaoshuo/<书名>/<章节>.json`
 *   ```json
 *   { "title": "第一章", "results": {
 *       "01": { "dialogText": "…", "name": "张三",
 *               "voiceDisplay": "…", "genderAge": "男男青年" } } }
 *   ```
 *   key 为对话在段落内的序号，value 的 `name` 即归属角色，
 *   `name == "旁白"` 表示该句为旁白。
 *
 * - 角色档案
 *   `/storage/emulated/0/Download/chajian/<插件目录>/characterRecords.json`
 *   ```json
 *   [ { "name": "张三", "aliases": "小张|三儿", "gender": "男", "age": "青年" } ]
 *   ```
 *
 * 本仓库把这些结构化数据读出来，供角色档案建档（专属上下文）与
 * 气泡朗读（对话归属）使用。
 *
 * 设计原则：
 * 1. **只读且全容错**：文件不存在、格式变化、解析失败一律返回空，
 *    上层功能自动回退，绝不因读文件失败影响朗读。
 * 2. **内存缓存**：按 (书名, 章节) 缓存解析结果，避免高频刷新反复读文件。
 */
object ScriptCharacterRepository {

    /** 外部存储根目录（与脚本 EXT_DIR / CACHE_ROOT 保持一致） */
    private const val CHAJIAN_DIR = "/storage/emulated/0/Download/chajian/"
    private const val CACHE_ROOT = "/storage/emulated/0/Download/chajian/xiaoshuo/"

    /** 章节缓存上限：避免长章节缓存长期占用 */
    private const val MAX_CACHED_CHAPTERS = 6

    /** 一条对话的归属信息 */
    data class DialogRole(
        /** 说话人姓名；"旁白" 表示旁白 */
        val name: String,
        /** 性别，如 "男"、"女" */
        val gender: String = "",
        /** 年龄段，如 "青年"、"少年"、"中年"、"老年"、"童" */
        val age: String = "",
        /** 发音人显示名（部分版本写入） */
        val voiceDisplay: String = "",
        /** 对话原文（脚本写入，用于按文本反查归属） */
        val dialogText: String = ""
    ) {
        val isNarration: Boolean get() = name == "旁白"

        /** 性别 + 年龄段，用于 AI 生图提示词，如 "男青年" */
        val genderAge: String
            get() = when {
                gender.isBlank() && age.isBlank() -> ""
                gender.isBlank() -> age
                age.isBlank() -> gender
                else -> if (age.startsWith(gender)) age else gender + age
            }
    }

    /** 角色档案 */
    data class CharacterRecord(
        val name: String,
        /** 别名，多个用 | 分隔 */
        val aliases: String = "",
        val gender: String = "",
        val age: String = ""
    )

    /**
     * 章节缓存条目。
     *
     * 必须记录文件指纹：朗读脚本是**流式追加**写章节缓存的——一次 AI 分析会顺带
     * 把下文若干段对话一起分析并写盘，后续段落直接命中缓存不再调 AI。
     * 若像以前那样"读一次就永久缓存"，整章归属会停在开始朗读那一刻的旧快照上。
     */
    private class ChapterRolesEntry(
        val roles: Map<String, DialogRole>,
        /** 已解析的文件绝对路径，避免每次重复做文件名候选扫描 */
        val path: String,
        /** 文件指纹：修改时间 + 长度 */
        val stamp: Long,
        /** 上次核对时间 */
        var checkedAt: Long
    ) {
        /** 按对话文本反查归属的索引（懒构建，随条目一起失效） */
        private var textIndex: Map<String, DialogRole>? = null

        @Synchronized
        fun textIndex(): Map<String, DialogRole> =
            textIndex ?: buildTextIndex(roles).also { textIndex = it }
    }

    /** 对话文本前缀索引长度：脚本写入的对话可能被截断，长对话靠前缀命中 */
    private const val TEXT_PREFIX_LEN = 12

    /** 前缀索引 key 前缀，避免与"全文精确匹配"的 key 撞车 */
    private const val TEXT_PREFIX_KEY = "\u0000p:"

    /**
     * 构建「对话文本 → 归属」索引。
     * 脚本自身也是先按序号取、再用文本校验，说明文本才是两边同源的锚点。
     */
    private fun buildTextIndex(roles: Map<String, DialogRole>): Map<String, DialogRole> {
        val index = HashMap<String, DialogRole>(roles.size * 2 + 1)
        for (role in roles.values) {
            val n = normalizeDialog(role.dialogText)
            if (n.isEmpty()) continue
            index[n] = role
            if (n.length >= TEXT_PREFIX_LEN) {
                index[TEXT_PREFIX_KEY + n.substring(0, TEXT_PREFIX_LEN)] = role
            }
        }
        return index
    }

    private fun normalizeDialog(s: String): String =
        s.replace(Regex("\\s+"), "").trim()

    private val chapterCache = LinkedHashMap<String, ChapterRolesEntry>(
        8, 0.75f, true
    )

    /** 文件指纹：修改时间 + 长度。脚本追加写入后两者至少其一会变 */
    private fun stampOf(file: File): Long =
        file.lastModified() * 1_000_003L + file.length()

    /**
     * 指纹核对最小间隔。
     * 脚本每段都会写盘，没必要每段都 stat；这里取 800ms 保证
     * "下一段一定能看到上一段的分析结果"。
     */
    private const val CHAPTER_RECHECK_INTERVAL_MS = 800L

    @Volatile
    private var recordsCache: Map<String, CharacterRecord>? = null

    @Volatile
    private var recordsCacheBook: String? = null

    /**
     * 读取指定章节的对话归属映射。
     *
     * @return key 为对话序号字符串（脚本按全局对话序号写入，如 "1"、"12"），
     *         无缓存文件时返回 null
     */
    @Synchronized
    fun getChapterRoles(bookName: String, chapterTitle: String): Map<String, DialogRole>? {
        if (bookName.isBlank() || chapterTitle.isBlank()) return null
        val key = "$bookName\u0000$chapterTitle"
        val now = System.currentTimeMillis()

        val cached = chapterCache[key]
        if (cached != null) {
            // 刚核对过且在间隔内 → 直接复用，零开销
            if (now - cached.checkedAt < CHAPTER_RECHECK_INTERVAL_MS) return cached.roles
            val file = File(cached.path)
            if (file.isFile) {
                val stamp = stampOf(file)
                if (stamp == cached.stamp) {
                    // 文件没变
                    cached.checkedAt = now
                    return cached.roles
                }
                // 脚本已追加新结果 → 重新解析，让后续段落拿到最新归属
                cached.checkedAt = now
                readChapterRoles(file)?.let {
                    chapterCache[key] = ChapterRolesEntry(it, cached.path, stamp, now)
                    return it
                }
                // 解析失败（多半是写盘写到一半）→ 继续用旧结果，下一段再试
                return cached.roles
            }
            // 文件被清理（如用户清了分析缓存）→ 丢弃重新定位
            chapterCache.remove(key)
        }

        val file = resolveChapterFile(bookName, chapterTitle) ?: return null
        val roles = readChapterRoles(file) ?: return null
        // 简单 LRU：超出上限淘汰最早的
        while (chapterCache.size >= MAX_CACHED_CHAPTERS) {
            val first = chapterCache.keys.firstOrNull() ?: break
            chapterCache.remove(first)
        }
        chapterCache[key] = ChapterRolesEntry(roles, file.absolutePath, stampOf(file), now)
        return roles
    }

    /**
     * 全书范围收集某个角色的台词，供角色建档兜底。
     *
     * [getChapterRoles] 依赖章节标题精确匹配，一旦标题口径对不上（或该章尚未产生
     * 缓存文件）就拿不到任何台词，角色建档会因「无专属上下文」被跳过。
     * 这里直接扫描本书目录下所有章节缓存 json，把该角色的台词聚合出来。
     *
     * 全容错：目录不存在或解析失败一律返回空列表。
     */
    @Synchronized
    fun collectDialogsByName(
        bookName: String,
        name: String,
        maxChapters: Int = 40,
        maxLines: Int = 20
    ): List<String> {
        if (bookName.isBlank() || name.isBlank()) return emptyList()
        val target = name.trim()
        val out = ArrayList<String>(maxLines)
        for (dir in bookDirCandidates(bookName)) {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
                ?: continue
            for (file in files.sortedByDescending { it.lastModified() }.take(maxChapters)) {
                val roles = readChapterRoles(file) ?: continue
                for (role in roles.values) {
                    if (role.isNarration) continue
                    if (role.name.trim() != target) continue
                    val text = role.dialogText.trim()
                    if (text.isNotBlank()) out.add(text)
                }
                if (out.size >= maxLines) break
            }
            if (out.isNotEmpty()) break
        }
        return out.take(maxLines)
    }

    /**
     * 使指定章节的缓存失效。
     * AI 分析出新结果后调用，保证下一次取用立即重新读盘。
     */
    @Synchronized
    fun invalidateChapter(bookName: String, chapterTitle: String) {
        if (bookName.isBlank() || chapterTitle.isBlank()) return
        chapterCache.remove("$bookName\u0000$chapterTitle")
    }

    /**
     * 按**对话文本**反查章节归属。
     *
     * 序号口径只要有一处不同就会整体错位；而对话文本两边同源，
     * 命中即代表"这句确实是这个人说的"。
     * 因此气泡归属在按序号查不到时，再用文本兜一次底。
     */
    @Synchronized
    fun matchChapterRoleByText(
        bookName: String,
        chapterTitle: String,
        dialogText: String
    ): DialogRole? {
        if (bookName.isBlank() || chapterTitle.isBlank()) return null
        val n = normalizeDialog(dialogText)
        if (n.isEmpty()) return null
        val key = "$bookName\u0000$chapterTitle"
        var entry = chapterCache[key]
        if (entry == null) {
            // 尚未加载过（或文件刚生成）：顺带触发一次加载
            getChapterRoles(bookName, chapterTitle)
            entry = chapterCache[key] ?: return null
        }
        val index = entry.textIndex()
        index[n]?.let { return it }
        if (n.length >= TEXT_PREFIX_LEN) {
            index[TEXT_PREFIX_KEY + n.substring(0, TEXT_PREFIX_LEN)]?.let { return it }
        }
        return null
    }

    private fun readChapterRoles(file: File): Map<String, DialogRole>? {
        if (!file.isFile) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) return null
            val obj = JSONObject(text)
            val results = obj.optJSONObject("results") ?: return null
            val map = HashMap<String, DialogRole>(results.length() + 1)
            val it = results.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = results.optJSONObject(k) ?: continue
                val name = v.optString("name").trim()
                if (name.isEmpty()) continue
                map[k] = DialogRole(
                    name = name,
                    gender = v.optString("gender").trim(),
                    age = v.optString("age").trim()
                        .ifEmpty { v.optString("genderAge").trim() },
                    voiceDisplay = v.optString("voiceDisplay").trim(),
                    dialogText = v.optString("dialogText").trim()
                )
            }
            map.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析章节缓存文件路径。
     * 脚本写入时会净化文件名（非法字符替换为 _ 或去除），
     * 这里依次尝试原样、替换、去除三种形式，命中即用。
     */
    private fun resolveChapterFile(bookName: String, chapterTitle: String): File? {
        val bookDirCandidates = bookDirCandidates(bookName)
        val nameCandidates = fileNameCandidates(chapterTitle)
        for (dir in bookDirCandidates) {
            for (n in nameCandidates) {
                val f = File(dir, "$n.json")
                if (f.isFile) return f
            }
        }
        return null
    }

    private fun bookDirCandidates(bookName: String): List<File> {
        val out = LinkedHashSet<File>()
        listOf(bookName, bookName.trim(), sanitize(bookName), strip(bookName))
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .forEach { out.add(File(CACHE_ROOT + it)) }
        return out.toList()
    }

    private fun fileNameCandidates(title: String): List<String> {
        val out = LinkedHashSet<String>()
        listOf(title, sanitize(title), strip(title)).forEach {
            if (it.isNotBlank() && it != "." && it != "..") out.add(it)
        }
        return out.toList()
    }

    private fun sanitize(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    private fun strip(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()

    /**
     * 读取角色档案（name → 档案）。
     * 脚本的 characterRecords.json 位于各插件目录下（如 mingwuyan/、mingwuyanZ/），
     * 这里扫描 chajian 下所有子目录合并读取，同名以首个为准。
     */
    fun getCharacterRecords(bookName: String): Map<String, CharacterRecord> {
        val cached = recordsCache
        if (cached != null && recordsCacheBook == bookName) return cached
        val map = readCharacterRecords()
        recordsCache = map
        recordsCacheBook = bookName
        return map
    }

    private fun readCharacterRecords(): Map<String, CharacterRecord> {
        val base = File(CHAJIAN_DIR)
        if (!base.isDirectory) return emptyMap()
        val map = LinkedHashMap<String, CharacterRecord>()
        val dirs = base.listFiles()?.filter { it.isDirectory } ?: return emptyMap()
        for (dir in dirs) {
            val f = File(dir, "characterRecords.json")
            if (!f.isFile) continue
            try {
                val text = f.readText()
                if (text.isBlank()) continue
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isEmpty() || map.containsKey(name)) continue
                    map[name] = CharacterRecord(
                        name = name,
                        aliases = o.optString("aliases").trim(),
                        gender = o.optString("gender").trim(),
                        age = o.optString("age").trim()
                    )
                }
            } catch (_: Exception) {
                // 单个插件目录解析失败不影响其他目录
            }
        }
        return map
    }

    /**
     * 按姓名查找角色档案，支持别名匹配。
     */
    fun findRecord(name: String, bookName: String): CharacterRecord? {
        if (name.isBlank()) return null
        val records = getCharacterRecords(bookName)
        records[name]?.let { return it }
        for (rec in records.values) {
            if (rec.aliases.isBlank()) continue
            if (rec.aliases.split("|").any { it.trim() == name }) return rec
        }
        return null
    }

    /** 清除全部缓存（切书/清缓存时调用） */
    @Synchronized
    fun clear() {
        chapterCache.clear()
        recordsCache = null
        recordsCacheBook = null
    }
}
