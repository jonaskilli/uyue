package io.legado.app.model

import io.legado.app.help.EngineDataDir
import org.json.JSONArray
import java.io.File

/**
 * 角色 → 发音人 绑定读取。
 *
 * 绑定由「角色管理」（[io.legado.app.ui.book.read.config.CharacterManagerDialog]）
 * 在「固定发音人」时写入 characterRecords.json / shuming.<书名>.json 的角色
 * JSON 的 `voice` 字段（与角色管理页同源，避免重复存储导致不一致）。
 * 这里只读，供「与角色对话」功能取出发音人 tag 去合成语音。
 *
 * 目录随朗读引擎切换（内置TTS插件 → mingwuyanZ/，其他 → mingwuyan/），
 * 与角色管理读写 [EngineDataDir] 保持同源。
 */
object CharacterVoiceBinding {

    /** 取某角色绑定的发音人 tag；未绑定返回空串 */
    fun getVoice(bookName: String, name: String): String {
        val target = name.trim()
        if (target.isBlank()) return ""
        return findVoice("shuming.$bookName.json", target)
            ?: findVoice("characterRecords.json", target)
            ?: ""
    }

    /**
     * 取某角色的性别与年龄段（角色管理「修改性别年龄」写入的字段）。
     * @return first=性别（男/女，可能为空） second=年龄段（少年/青年/中年…，可能为空）
     */
    fun getGenderAge(bookName: String, name: String): Pair<String, String> {
        val target = name.trim()
        if (target.isBlank()) return "" to ""
        val json = readFile("shuming.$bookName.json").ifEmpty { readFile("characterRecords.json") }
        if (json.isBlank()) return "" to ""
        return runCatching {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("name", "").trim() == target) {
                    return@runCatching o.optString("gender", "").trim() to o.optString("age", "").trim()
                }
                val aliases = o.optString("aliases", "")
                    .split("|").map { it.trim() }.filter { it.isNotBlank() }
                if (aliases.contains(target)) {
                    return@runCatching o.optString("gender", "").trim() to o.optString("age", "").trim()
                }
            }
            "" to ""
        }.getOrDefault("" to "")
    }

    private fun findVoice(fileName: String, name: String): String? {
        val text = readFile(fileName)
        if (text.isBlank()) return null
        return runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("name", "").trim()
                if (n == name) {
                    val v = o.optString("voice", "").trim()
                    if (v.isNotBlank()) return@runCatching v
                    continue
                }
                // 别名命中：把该角色绑定的发音人也用于别名
                val aliases = o.optString("aliases", "")
                    .split("|").map { it.trim() }.filter { it.isNotBlank() }
                if (aliases.contains(name)) {
                    val v = o.optString("voice", "").trim()
                    if (v.isNotBlank()) return@runCatching v
                }
            }
            null
        }.getOrNull()
    }

    private fun readFile(fileName: String): String = runCatching {
        val f = File(EngineDataDir.baseDir(), fileName)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")
}
