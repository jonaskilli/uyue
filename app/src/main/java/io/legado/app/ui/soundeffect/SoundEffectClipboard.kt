package io.legado.app.ui.soundeffect

import io.legado.app.constant.AppLog
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音效规则的临时内存（剪贴板）。
 *
 * 用于把一个模式（或某个分组）的规则暂存起来，再导入到另一个模式：
 * 例如把「普通音效」的分组导出到临时内存，切到「全部音效」后追加到末尾。
 * 数据以 JSON 存在应用私有目录，重启应用不会丢失；最多保留 [MAX_ENTRIES] 份，
 * 新的排在最前面，超出后丢弃最旧的一份。
 */
object SoundEffectClipboard {

    /** 一份暂存内容：来源标签 + 保存时间 + 若干分组 */
    data class Entry(
        val id: String,
        val label: String,
        val time: Long,
        val groups: List<SoundEffectGroupWithRules>
    ) {
        val groupCount: Int get() = groups.size
        val ruleCount: Int get() = groups.sumOf { it.list.size }

        fun timeText(): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

        fun summary(): String = "$label · ${groupCount}组/${ruleCount}条 · ${timeText()}"
    }

    const val MAX_ENTRIES = 10

    private val storeFile: File by lazy { File(appCtx.filesDir, "sound_effect_clipboard.json") }
    private val entries = mutableListOf<Entry>()
    private var loaded = false

    @Synchronized
    fun all(): List<Entry> {
        ensureLoaded()
        return entries.toList()
    }

    /** 暂存一份规则，写入时做深拷贝，避免后续编辑影响暂存内容 */
    @Synchronized
    fun add(label: String, groups: List<SoundEffectGroupWithRules>) {
        ensureLoaded()
        val copy = groups.map { gwr ->
            SoundEffectGroupWithRules(
                gwr.group.copy(),
                gwr.list.map { it.copy() }.toMutableList()
            )
        }
        val now = System.currentTimeMillis()
        entries.add(
            0,
            Entry(
                id = "${now}-${entries.size}",
                label = label.trim().ifBlank { "未命名" },
                time = now,
                groups = copy
            )
        )
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.lastIndex)
        }
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        ensureLoaded()
        if (entries.removeAll { it.id == id }) persist()
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        if (entries.isEmpty()) return
        entries.clear()
        persist()
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!storeFile.exists()) return
        runCatching {
            val arr = JSONArray(storeFile.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val groups = obj.optJSONArray("groups") ?: continue
                entries.add(
                    Entry(
                        id = obj.optString("id").ifBlank { i.toString() },
                        label = obj.optString("label").ifBlank { "未命名" },
                        time = obj.optLong("time"),
                        groups = SoundEffectRuleManager.parseJson(groups.toString())
                    )
                )
            }
        }.onFailure {
            AppLog.put("[音效规则] 读取临时内存失败：${it.message}", it)
        }
    }

    @Synchronized
    private fun persist() {
        runCatching {
            val arr = JSONArray()
            entries.forEach { entry ->
                arr.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("label", entry.label)
                        put("time", entry.time)
                        put("groups", JSONArray(SoundEffectRuleManager.toJson(entry.groups)))
                    }
                )
            }
            storeFile.writeText(arr.toString())
        }.onFailure {
            AppLog.put("[音效规则] 保存临时内存失败：${it.message}", it)
        }
    }
}
