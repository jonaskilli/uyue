package io.legado.app.ui.soundeffect

import io.legado.app.constant.AppLog
import io.legado.app.help.EngineDataDir
import io.legado.app.help.http.okHttpClient
import io.legado.app.service.BaseReadAloudService
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 音效规则文件的读写与下载。
 *
 * 规则文件直接决定朗读时插入哪些音效标记，因此这里不使用数据库，
 * 而是原样读写 JSON 文件，保证与朗读服务
 * `BaseReadAloudService.applySoundEffectRules()` 读取的是同一份数据。
 *
 * 两个文件：
 * - [MODE_ALL]    全部音效 → ttsrv-replaces3.json
 * - [MODE_NORMAL] 普通音效 → ttsrv-replaces5.json
 *
 * 所在目录由 [EngineDataDir] 决定（内置TTS插件用 mingwuyanZ，其它引擎用 mingwuyan）。
 */
object SoundEffectRuleManager {

    const val MODE_ALL = "all"
    const val MODE_NORMAL = "normal"

    private const val BASE_URL = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/"
    private const val FILE_ALL = "ttsrv-replaces3.json"
    private const val FILE_NORMAL = "ttsrv-replaces5.json"

    /** 默认分组名：文件为空或规则没有分组时使用 */
    const val DEFAULT_GROUP_NAME = "默认分组"

    fun fileNameOf(mode: String): String =
        if (mode == MODE_NORMAL) FILE_NORMAL else FILE_ALL

    fun fileOf(mode: String): File =
        File(EngineDataDir.baseDir() + fileNameOf(mode))

    fun urlOf(mode: String): String =
        BASE_URL + fileNameOf(mode) + "?download=true"

    fun allModes(): List<String> = listOf(MODE_ALL, MODE_NORMAL)

    fun modeTitle(mode: String): String =
        if (mode == MODE_NORMAL) "普通音效" else "全部音效"

    /**
     * 确保规则文件存在且内容有效，否则下载。
     * @param onProgress 已下载字节数 / 总字节数（总长度可能为 -1）
     * @return 成功返回 null，失败返回错误信息
     */
    fun ensureFile(mode: String, onProgress: ((Long, Long) -> Unit)? = null): String? {
        val file = fileOf(mode)
        if (isValidFile(file)) return null
        if (file.exists()) file.delete()
        val tmp = File(file.absolutePath + ".tmp")
        return try {
            val request = Request.Builder().url(urlOf(mode)).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "下载失败：HTTP ${response.code}"
                }
                val body = response.body ?: return "下载失败：响应为空"
                file.parentFile?.mkdirs()
                val total = body.contentLength()
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        var sum = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            sum += n
                            onProgress?.invoke(sum, total)
                        }
                    }
                }
            }
            val text = tmp.readText()
            if (text.isBlank() || !text.trimStart().startsWith("[")) {
                tmp.delete()
                return "下载的内容不是有效的规则文件"
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
            AppLog.put("[音效规则] 下载完成：${file.absolutePath}")
            null
        } catch (e: Exception) {
            tmp.delete()
            AppLog.put("[音效规则] 下载失败：${e.message}", e)
            "下载失败：${e.message}"
        }
    }

    private fun isValidFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return try {
            val text = file.readText()
            text.isNotBlank() && text.trimStart().startsWith("[")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 读取规则文件。文件不存在时返回空列表。
     */
    fun load(mode: String): MutableList<SoundEffectGroupWithRules> {
        val file = fileOf(mode)
        if (!file.exists()) return mutableListOf()
        return parseJson(file.readText())
    }

    /**
     * 解析规则 JSON 文本（与导出的格式一致）。
     * 解析失败时返回空列表并把错误写入日志。
     */
    fun parseJson(raw: String): MutableList<SoundEffectGroupWithRules> {
        val result = mutableListOf<SoundEffectGroupWithRules>()
        try {
            val arr = JSONArray(raw)
            var groupSeq = 0L
            var ruleSeq = 0L
            for (i in 0 until arr.length()) {
                val groupObj = arr.optJSONObject(i) ?: continue
                val groupJson = groupObj.optJSONObject("group")
                val group = if (groupJson != null) {
                    SoundEffectRuleGroup(
                        id = groupJson.optLong("id").takeIf { it != 0L }
                            ?: System.currentTimeMillis() + groupSeq++,
                        name = groupJson.optString("name")
                            .takeIf { it.isNotBlank() } ?: DEFAULT_GROUP_NAME,
                        order = groupJson.optInt("order", i),
                        onExecution = groupJson.optInt("onExecution", 0)
                    )
                } else {
                    SoundEffectRuleGroup(
                        id = System.currentTimeMillis() + groupSeq++,
                        name = groupObj.optString("name")
                            .takeIf { it.isNotBlank() } ?: DEFAULT_GROUP_NAME,
                        order = i
                    )
                }
                val rules = mutableListOf<SoundEffectRule>()
                val listArr = groupObj.optJSONArray("list")
                if (listArr != null) {
                    for (j in 0 until listArr.length()) {
                        val item = listArr.optJSONObject(j) ?: continue
                        rules.add(
                            SoundEffectRule(
                                id = item.optLong("id").takeIf { it != 0L }
                                    ?: System.currentTimeMillis() + ruleSeq++,
                                name = item.optString("name"),
                                isEnabled = item.optBoolean("isEnabled", true),
                                isRegex = item.optBoolean("isRegex", false),
                                pattern = item.optString("pattern"),
                                replacement = item.optString("replacement"),
                                order = item.optInt("order", j),
                                sampleText = item.optString("sampleText")
                            )
                        )
                    }
                }
                rules.sortBy { it.order }
                renumberRules(rules)
                result.add(SoundEffectGroupWithRules(group, rules))
            }
        } catch (e: Exception) {
            AppLog.put("[音效规则] 解析失败：${e.message}", e)
        }
        return result
    }

    /**
     * 把规则序列化为 JSON 文本（结构与朗读服务读取的格式一致）。
     */
    fun toJson(data: List<SoundEffectGroupWithRules>): String {
        val arr = JSONArray()
        data.forEachIndexed { groupIndex, gwr ->
            val groupObj = JSONObject()
            groupObj.put(
                "group",
                JSONObject().apply {
                    put("id", gwr.group.id)
                    put("name", gwr.group.name)
                    put("order", groupIndex)
                    put("onExecution", gwr.group.onExecution)
                }
            )
            val listArr = JSONArray()
            gwr.list.forEachIndexed { ruleIndex, rule ->
                listArr.put(
                    JSONObject().apply {
                        put("id", rule.id)
                        put("name", rule.name)
                        put("isEnabled", rule.isEnabled)
                        put("isRegex", rule.isRegex)
                        put("pattern", rule.pattern)
                        put("replacement", rule.replacement)
                        put("order", ruleIndex)
                        put("sampleText", rule.sampleText)
                    }
                )
            }
            groupObj.put("list", listArr)
            arr.put(groupObj)
        }
        return arr.toString()
    }

    /**
     * 保存规则到文件。
     * 先写临时文件，再把原文件备份为 .bak（只保留最近一份），最后替换。
     * 保存成功后立即让朗读服务的规则缓存失效，使改动马上生效。
     * @return 成功返回 null，失败返回错误信息
     */
    fun save(mode: String, data: List<SoundEffectGroupWithRules>): String? {
        val file = fileOf(mode)
        val tmp = File(file.absolutePath + ".tmp")
        return try {
            val json = toJson(data)
            file.parentFile?.mkdirs()
            tmp.writeText(json)
            if (file.exists()) {
                kotlin.runCatching {
                    file.copyTo(File(file.absolutePath + ".bak"), true)
                }
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
            BaseReadAloudService.invalidateSoundEffectRuleCache()
            null
        } catch (e: Exception) {
            tmp.delete()
            AppLog.put("[音效规则] 保存失败：${e.message}", e)
            "保存失败：${e.message}"
        }
    }

    /** 组内规则重新按 0..n 编号 */
    fun renumberRules(rules: MutableList<SoundEffectRule>) {
        rules.forEachIndexed { index, rule -> rule.order = index }
    }

    /** 生成一个新的、尽量不与现有 id 冲突的规则 id */
    fun newRuleId(data: List<SoundEffectGroupWithRules>): Long {
        var id = System.currentTimeMillis()
        val used: HashSet<Long> =
            data.flatMapTo(HashSet()) { gwr -> gwr.list.map { it.id } }
        while (used.contains(id)) id++
        return id
    }

    /** 生成一个新的、尽量不与现有 id 冲突的分组 id */
    fun newGroupId(data: List<SoundEffectGroupWithRules>): Long {
        var id = System.currentTimeMillis()
        val used: HashSet<Long> = data.mapTo(HashSet()) { it.group.id }
        while (used.contains(id)) id++
        return id
    }

    /**
     * 按朗读服务的方式编译正则：支持 JS 风格 `/pattern/flags` 与 `\u{XXXX}`。
     */
    fun compilePattern(pattern: String): Regex {
        var (cleanPattern, flags) = parsePatternAndFlags(pattern)
        cleanPattern = convertEs6RegexToJava(cleanPattern)
        return if (flags.isEmpty()) {
            Regex(cleanPattern)
        } else {
            val flagSet = mutableSetOf<RegexOption>()
            if (flags.contains('i')) flagSet.add(RegexOption.IGNORE_CASE)
            if (flags.contains('m')) flagSet.add(RegexOption.MULTILINE)
            if (flags.contains('s')) flagSet.add(RegexOption.DOT_MATCHES_ALL)
            Regex(cleanPattern, flagSet)
        }
    }

    private fun parsePatternAndFlags(pattern: String): Pair<String, String> {
        if (pattern.length >= 2 && pattern.startsWith("/") && !pattern.substring(1).contains("/")) {
            return pattern to ""
        }
        if (pattern.length >= 2 && pattern.startsWith("/")) {
            val lastSlash = pattern.lastIndexOf("/")
            if (lastSlash > 0) {
                return pattern.substring(1, lastSlash) to pattern.substring(lastSlash + 1)
            }
        }
        return pattern to ""
    }

    private fun convertEs6RegexToJava(pattern: String): String =
        pattern.replace(Regex("""\\u\{([0-9a-fA-F]+)\}""")) { match ->
            "\\x{${match.groupValues[1]}}"
        }
}
