package io.legado.app.ui.book.read.config

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.model.ReadAloud
import io.legado.app.utils.StringUtils
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

object CharacterVoiceHelper {

    // 引擎判定结果缓存：key 为当前引擎 id（引擎切换频率极低，弹窗触发频率高，
    // 命中缓存时零 IO，避免每次打开发音人弹窗都同步查库造成卡顿）
    @Volatile
    private var cachedTtsEngineId: String? = null

    @Volatile
    private var cachedIsBuiltinPlugin = false

    private val baseDir: String
        get() = io.legado.app.help.EngineDataDir.baseDir()

    private fun readTxtFile(fileName: String): String {
        return try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTxtFile(fileName: String, content: String) {
        try {
            val file = File(baseDir, fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCharacterRecords(bookName: String): JSONArray {
        val charRecordsContent = readTxtFile("characterRecords.json")
        val cunfangContent = readTxtFile("cunfang.txt").ifEmpty { "默认" }.trim()
        return if (bookName == cunfangContent && charRecordsContent.isNotEmpty()) {
            try {
                JSONArray(charRecordsContent)
            } catch (e: Exception) {
                JSONArray()
            }
        } else {
            val fileName = "shuming.$bookName.json"
            val content = readTxtFile(fileName)
            try {
                if (content.isNotEmpty()) JSONArray(content) else JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }
        }
    }

    fun findCharacterVoice(bookName: String, name: String): String? {
        val records = getCharacterRecords(bookName)
        val normalizedName = normalizeString(name)
        for (i in 0 until records.length()) {
            val char = records.optJSONObject(i) ?: continue
            val charName = normalizeString(char.optString("name", ""))
            if (charName == normalizedName) {
                return char.optString("voice", "").takeIf { it.isNotBlank() }
            }
            val aliasesStr = char.optString("aliases", "")
            val aliases = aliasesStr.split("|").map { normalizeString(it) }
            if (aliases.contains(normalizedName)) {
                return char.optString("voice", "").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    fun saveCharacterVoice(bookName: String, name: String, voice: String) {
        val records = getCharacterRecords(bookName)
        val normalizedName = normalizeString(name)
        var found = false
        for (i in 0 until records.length()) {
            val char = records.optJSONObject(i) ?: continue
            val charName = normalizeString(char.optString("name", ""))
            val aliasesStr = char.optString("aliases", "")
            val aliases = aliasesStr.split("|").map { normalizeString(it) }
            if (charName == normalizedName || aliases.contains(normalizedName)) {
                char.put("voice", voice)
                found = true
                break
            }
        }
        if (!found) {
            val newChar = JSONObject().apply {
                put("name", name)
                put("aliases", name)
                put("gender", "")
                put("age", "")
                put("voice", voice)
                put("usageCount", 0)
            }
            records.put(newChar)
        }
        val jsonData = records.toString()
        writeTxtFile("characterRecords.json", jsonData)
        writeTxtFile("shuming.$bookName.json", jsonData)
        createGengxinFile(records)
    }

    private fun createGengxinFile(records: JSONArray) {
        try {
            val saveRecords = JSONArray()
            for (i in 0 until records.length()) {
                val char = records.optJSONObject(i) ?: continue
                val record = JSONObject().apply {
                    put("name", char.optString("name", ""))
                    put("aliases", char.optString("aliases", ""))
                    put("voice", char.optString("voice", ""))
                    put("gender", char.optString("gender", ""))
                    put("age", char.optString("age", ""))
                    put("usageCount", char.optInt("usageCount", 0))
                    if (char.has("fixedVoice")) put("fixedVoice", char.optBoolean("fixedVoice", false))
                    if (char.has("fixedGenderAge")) put("fixedGenderAge", char.optBoolean("fixedGenderAge", false))
                    if (char.has("genderAgeHistory")) put("genderAgeHistory", char.opt("genderAgeHistory"))
                }
                saveRecords.put(record)
            }
            writeTxtFile("gengxin.json", saveRecords.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun normalizeString(str: String): String {
        return str.replace(Regex("[\u200B-\u200D\uFEFF]"), "").trim()
    }

    fun showVoiceCategoryDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onCategorySelected: (String) -> Unit
    ) {
        val categories = arrayOf(
            "女童", "少女", "女青年", "女中年", "女老年",
            "男童", "少年", "男青年", "男中年", "男老年",
            "男主", "女主", "特殊"
        )
        AlertDialog.Builder(context)
            .setTitle("选择发音人分类")
            .setItems(categories) { _, which ->
                val category = categories[which]
                onCategorySelected(category)
            }
            .setNegativeButton("全部") { _, _ ->
                showVoiceListDialog(context, lifecycleOwner, "全部", onCategorySelected)
            }
            .setNeutralButton("搜索") { _, _ ->
                showVoiceSearchDialog(context, lifecycleOwner, onCategorySelected)
            }
            .setPositiveButton("取消", null)
            .show()
    }

    fun showVoiceSearchDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        onVoiceSelected: (String) -> Unit
    ) {
        val editText = androidx.appcompat.widget.AppCompatEditText(context).apply {
            hint = "输入关键词搜索发音人"
        }
        AlertDialog.Builder(context)
            .setTitle("搜索发音人")
            .setView(editText)
            .setPositiveButton("搜索") { _, _ ->
                val keyword = editText.text?.toString()?.trim() ?: ""
                if (keyword.isNotEmpty()) {
                    showVoiceListDialog(context, lifecycleOwner, keyword, onVoiceSelected)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun showVoiceListDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        category: String,
        onVoiceSelected: (String) -> Unit
    ) {
        val allVoices = getVoiceList()
        val filteredVoices = when {
            category == "全部" -> allVoices
            category.isNotEmpty() -> allVoices.filter { v -> v.contains(category) }
            else -> allVoices
        }
        val displayList = if (filteredVoices.isEmpty()) allVoices else filteredVoices
        if (displayList.isEmpty()) {
            Toast.makeText(context, "发音人列表为空", Toast.LENGTH_SHORT).show()
            return
        }
        val title = if (category == "全部") "选择发音人 (全部)" else "选择发音人 ($category)"
        val adapter = buildVoiceListAdapter(context, lifecycleOwner, displayList)
        AlertDialog.Builder(context)
            .setTitle(title)
            // 用自定义 Adapter 替代 setItems，使每行右侧可挂试听按钮。
            // 点行本身 = 选中（dialog 自动关闭）；点试听按钮 = 试听且不关闭。
            .setAdapter(adapter) { _, which -> onVoiceSelected(displayList[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 发音人列表 Adapter：左侧名称，右侧试听按钮。
     *
     * 试听按钮设了 focusable=false，因此不会抢走 ListView 的条目焦点，
     * 其点击事件在 getView 内单独消费，与条目的选中点击互不干扰。
     */
    private fun buildVoiceListAdapter(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        voices: List<String>
    ): BaseAdapter {
        return object : BaseAdapter() {
            override fun getCount(): Int = voices.size
            override fun getItem(position: Int): String = voices[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.item_voice_preview, parent, false)
                val voice = getItem(position)
                view.findViewById<TextView>(R.id.tv_voice_name).text = voice
                view.findViewById<TextView>(R.id.btn_preview_voice).setOnClickListener {
                    previewVoice(context, lifecycleOwner, voice) { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                return view
            }
        }
    }

    /**
     * 试听指定发音人。
     *
     * 发音人通过 `<<voice>>` 标记内嵌进文本传给引擎，与角色管理中点角色名试听
     * （[CharacterManagerDialog.previewCharacterVoice]）保持同一套合成路径，
     * 因此试听效果与实际朗读一致。
     */
    fun previewVoice(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        voice: String,
        onToast: (String) -> Unit
    ) {
        if (voice.isBlank()) {
            onToast("该发音人为空，无法试听")
            return
        }
        TtsPreviewHelper.previewVoice(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewText = buildPreviewText(context, voice),
            voiceId = voice,
            onToast = onToast
        )
    }

    /** 拼装试听文本：`“ <<发音人>>自定义试听文本”` */
    private fun buildPreviewText(context: Context, voice: String): String {
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val customText =
            prefs.getString("tts_preview_text", "你好，这是一段试听语音") ?: "你好，这是一段试听语音"
        return "\u201C <<$voice>>$customText\u201D"
    }

    /**
     * 当前朗读引擎是否选择了内置TTS插件源。
     * 判定：ReadAloud.ttsEngine（已含书籍级覆盖）为数字 id → 查对应 HttpTTS 源，
     * 名称为"内置TTS插件"，或 url 以 @js: 开头且脚本调用插件合成 API（synthesize）。
     */
    fun isUsingBuiltinTtsPlugin(): Boolean {
        return try {
            val engineId = ReadAloud.ttsEngine ?: return false
            if (!StringUtils.isNumeric(engineId)) return false
            if (engineId == cachedTtsEngineId) return cachedIsBuiltinPlugin
            val result = appDb.httpTTSDao.get(engineId.toLong())?.let { httpTTS ->
                httpTTS.name == "内置TTS插件" ||
                    (httpTTS.url.startsWith("@js:") && httpTTS.url.contains("synthesize"))
            } ?: false
            cachedTtsEngineId = engineId
            cachedIsBuiltinPlugin = result
            result
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 角色管理可分配的发音人列表。
     * 朗读引擎选择内置TTS插件时：实时从内置配置列表提取启用项的 voiceTag，不走 fayinren.json；
     * 其他引擎（系统TTS/普通HTTP源）：维持原状读取 fayinren.json。
     */
    fun getAvailableVoices(): List<String> {
        if (isUsingBuiltinTtsPlugin()) {
            val tags = try {
                // listConfigs 优先命中 JReadVoiceEngine 内存缓存（配置写盘后由 invalidateConfigCache
                // 失效重建，永远最新）；ensureBuiltIns=false 跳过预设播种检查，读取路径最轻量。
                JReadVoiceEngine.listConfigs(appCtx, ensureBuiltIns = false)
                    .filter { it.enabled }
                    .map { it.voiceTag }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
            } catch (e: Exception) {
                emptyList()
            }
            return tags.ifEmpty { listOf("auto") }
        }
        return try {
            val content = readTxtFile("fayinren.json")
            if (content.isNotEmpty()) {
                val arr = JSONArray(content)
                (0 until arr.length()).map { arr.getString(it) }
            } else {
                listOf("auto")
            }
        } catch (e: Exception) {
            listOf("auto")
        }
    }

    private fun getVoiceList(): List<String> {
        return getAvailableVoices()
    }

    fun previewCharacterVoice(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        bookName: String,
        name: String,
        onToast: (String) -> Unit
    ) {
        val voice = findCharacterVoice(bookName, name)
        if (voice.isNullOrBlank()) {
            onToast("未找到该角色的发音人")
            return
        }
        val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val customText = prefs.getString("tts_preview_text", "你好，这是一段试听语音") ?: "你好，这是一段试听语音"
        val previewText = "\u201C <<$voice>>$customText\u201D"
        TtsPreviewHelper.previewVoice(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewText = previewText,
            voiceId = voice,
            onToast = onToast
        )
    }
}
