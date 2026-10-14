package io.legado.app.ui.tts.plugin

import android.content.Intent
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.help.audiobook.JReadVoicePluginRuntime
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.code.BaseCodeEditorActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TTS 引擎插件的代码编辑器（新增插件 / 编辑插件 共用同一个界面）。
 *
 * 编辑器的外壳（sora 编辑器、搜索替换、代码折叠、格式化、主题…）全部在
 * [BaseCodeEditorActivity]，朗读脚本编辑器用的是同一套，这里只保留插件特有的：
 * 插件信息、变量设置、预览插件界面、音频参数、清空数据、运行调试。
 *
 * 契约：
 *  - 入参：EXTRA_PLUGIN_ID（插件 id，为空表示新增）
 *  - 出参：RESULT_PLUGIN_ID（保存后的插件 id，仅供调用方提示/刷新用）
 *
 * ⚠️ 入参/出参都只传 id，不传插件 JSON：插件的 code 动辄几 MB，
 * 整包塞进 Intent 会直接触发 TransactionTooLargeException（Binder 上限约 1MB）。
 * 插件数据统一由编辑器自己从 JReadVoiceEngine 读取。
 */
class PluginEditorActivity : BaseCodeEditorActivity() {

    // 注意：必须在字段初始化时创建（registerForActivityResult 要求在 STARTED 之前注册）
    private val pluginDialogs = PluginEditDialogs(this)

    /** 当前编辑中的插件（代码以编辑器内容为准，这里保存名称/ID/变量等元信息） */
    private lateinit var plugin: JReadVoiceEngine.VoicePlugin
    private var foldKey: String = "plugin_new"

    private val logBuilder = StringBuilder()
    private var logTextView: TextView? = null

    // ---------------------------------------------------------------- 基类钩子

    override fun loadInitialCode(): String {
        plugin = readPluginFromIntent()
        foldKey = "plugin_${plugin.id}"
        // 新增插件时代码为空 → 填入内置模板，这就是「新增」与「编辑」的唯一差别
        return plugin.code.ifBlank { loadTemplateCode() }
    }

    override fun editorTitle(): String =
        plugin.name.ifBlank { getString(R.string.plugin_editor_title) }

    override fun foldStateKey(): String = foldKey

    override val extraMenuRes: Int = R.menu.plugin_editor

    override fun saveAsFileName(): String =
        "ttsrv-plugin-${plugin.name.ifBlank { "plugin" }}.js"

    override fun onSaveClick(code: String) {
        val updated = buildPluginForSave(code) ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.savePlugin(this@PluginEditorActivity, updated) }
            // 只回传 id：整包 JSON 同样会撑爆 Binder，而调用方只需要知道"保存成功了"
            setResult(RESULT_OK, Intent().putExtra(RESULT_PLUGIN_ID, updated.id))
            toastOnUi("已保存插件: ${updated.name}")
            finishDirectly()
        }
    }

    override fun onExtraMenuSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_run -> showRunDialog()
            R.id.menu_plugin_meta -> showPluginMetaDialog()
            R.id.menu_plugin_vars -> pluginDialogs.showVars(plugin.copy(code = currentCode())) {
                plugin = it
            }
            R.id.menu_plugin_preview_ui -> pluginDialogs.showPreviewUi(plugin.copy(code = currentCode()))
            R.id.menu_plugin_audio_params -> pluginDialogs.showAudioParams(plugin) { plugin = it }
            R.id.menu_clear_data -> pluginDialogs.clearData(plugin)
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- 数据 / 保存

    private fun readPluginFromIntent(): JReadVoiceEngine.VoicePlugin {
        // 只按 id 从存储里读，避免把几 MB 的插件 JSON 走 Intent 传来传去
        val id = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        if (id.isNotBlank()) {
            JReadVoiceEngine.listPlugins(this).firstOrNull { it.id == id }?.let { return it }
        }
        return JReadVoiceEngine.VoicePlugin()
    }

    private fun loadTemplateCode(): String {
        return runCatching {
            assets.open(DEFAULT_TEMPLATE_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault(DEFAULT_TEMPLATE_FALLBACK)
    }

    /**
     * 保存前展开所有折叠（保证落库的是完整原始代码），并补全 name / pluginId。
     * @return 待保存的插件；名称仍为空时返回 null（已提示用户去填插件信息）
     */
    private fun buildPluginForSave(code: String): JReadVoiceEngine.VoicePlugin? {
        unfoldAllBeforeSave()
        val realCode = editor.text.toString()
        var updated = plugin.copy(code = realCode)
        if (updated.name.isBlank()) {
            val (metaName, metaId) = extractPluginMeta(realCode)
            updated = updated.copy(name = metaName.orEmpty())
            if (updated.pluginId.isBlank()) {
                updated = updated.copy(pluginId = metaId ?: updated.name)
            }
        }
        if (updated.name.isBlank()) {
            toastOnUi("插件名称不能为空（且代码中未识别到 PluginJS.name）")
            showPluginMetaDialog()
            return null
        }
        return updated
    }

    // ---------------------------------------------------------------- 插件信息

    private fun showPluginMetaDialog() {
        val ctx = this
        val etName = TextInputEditText(ctx).apply {
            setText(plugin.name)
            hint = getString(R.string.plugin_name_hint)
            textSize = 14f
        }
        val etId = TextInputEditText(ctx).apply {
            setText(plugin.pluginId)
            hint = getString(R.string.plugin_id_hint)
            textSize = 14f
        }
        val etApi = TextInputEditText(ctx).apply {
            setText(readApiKey())
            hint = getString(R.string.plugin_api_key_hint)
            textSize = 14f
        }
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(etName)
            addView(etId)
            addView(etApi)
        }
        alert(getString(R.string.plugin_meta)) {
            customView { ScrollView(ctx).apply { addView(container) } }
            okButton {
                plugin = plugin.copy(
                    name = etName.text?.toString()?.trim().orEmpty(),
                    pluginId = etId.text?.toString()?.trim().orEmpty(),
                )
                val api = etApi.text?.toString()?.trim().orEmpty()
                val merged = JSONObject(plugin.userVarsJson.ifBlank { "{}" })
                API_KEYS.forEach { merged.put(it, api) }
                plugin = plugin.copy(userVarsJson = merged.toString())
                binding.titleBar.title = editorTitle()
            }
            cancelButton()
        }
    }

    private fun readApiKey(): String {
        val vars = runCatching { JSONObject(plugin.userVarsJson.ifBlank { "{}" }) }.getOrNull()
            ?: return ""
        return API_KEYS.firstNotNullOfOrNull { vars.optString(it).takeIf { s -> s.isNotBlank() } }.orEmpty()
    }

    // ---------------------------------------------------------------- 运行调试

    private fun showRunDialog() {
        val etText = TextInputEditText(this).apply {
            setText(debugPrefs().getString(KEY_DEBUG_TEXT, DEFAULT_DEBUG_TEXT))
            hint = getString(R.string.plugin_debug_text_param)
            textSize = 14f
        }
        alert(getString(R.string.plugin_debug)) {
            customView { etText }
            okButton {
                val text = etText.text?.toString()?.trim().orEmpty().ifBlank { DEFAULT_DEBUG_TEXT }
                debugPrefs().edit().putString(KEY_DEBUG_TEXT, text).apply()
                runDebug(text)
            }
            cancelButton()
        }
    }

    private fun runDebug(sampleText: String) {
        val target = plugin.copy(code = currentCode())
        logBuilder.clear()
        appendLog("=== 开始调试 ===")
        val (metaName, metaId) = extractPluginMeta(target.code)
        appendLog("PluginJS.name = ${metaName ?: "(未识别)"}")
        appendLog("PluginJS.id   = ${metaId ?: "(未识别)"}")
        appendLog("示例文本：$sampleText")
        showLogDialog()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val config = JReadVoiceEngine.VoiceConfig(
                        voiceTag = "debug",
                        displayName = target.name,
                        pluginId = target.pluginId.ifBlank { target.id },
                        dataJson = "{}",
                    )
                    val bytes = JReadVoicePluginRuntime.synthesize(
                        this@PluginEditorActivity,
                        target,
                        config,
                        sampleText,
                        "debug",
                        JSONObject(),
                    )
                    "合成成功：${bytes.size} 字节"
                }.getOrElse { "调试失败：${it.message}" }
            }
            appendLog(result)
            appendLog("=== 调试结束 ===")
        }
    }

    private fun showLogDialog() {
        val tv = TextView(this).apply {
            textSize = 12f
            setPadding(32, 24, 32, 24)
            setTextIsSelectable(true)
        }
        logTextView = tv
        tv.text = logBuilder.toString()
        alert(getString(R.string.plugin_debug_log)) {
            customView { ScrollView(this@PluginEditorActivity).apply { addView(tv) } }
            okButton { logTextView = null }
        }
    }

    private fun appendLog(line: String) {
        logBuilder.append(line).append("\n")
        logTextView?.text = logBuilder.toString()
    }

    private fun debugPrefs() = getSharedPreferences(DEBUG_PREFS_NAME, MODE_PRIVATE)

    companion object {
        const val EXTRA_PLUGIN_ID = "pluginId"
        const val RESULT_PLUGIN_ID = "pluginId"

        private const val DEFAULT_TEMPLATE_ASSET = "defaultData/ttsrv_plugin_template.js"
        private const val DEFAULT_TEMPLATE_FALLBACK = """let PluginJS = {
    "name": "新插件",
    "id": "com.example.plugin",
    "author": "",
    "version": 1,
    "vars": {
    },

    "onLoad": function () {
    },

    "getAudio": function (text, locale, voice, rate, volume, pitch) {
        throw "请在 getAudio() 里返回音频 InputStream 或 ByteArray"
    },
}
"""
        private const val DEBUG_PREFS_NAME = "tts_plugin_debug"
        private const val KEY_DEBUG_TEXT = "debugText"
        private const val DEFAULT_DEBUG_TEXT = "你好，这是插件调试测试。"

        private val API_KEYS = listOf(
            "api", "apiKey", "api_key", "key", "token", "accessToken", "secret"
        )

        /**
         * 从插件 JS 代码中提取 PluginJS 声明对象里的 name / id 字段。
         * 匹配 `PluginJS = {` 声明块开头范围内的 `name: "xxx"` 与 `id: "xxx"`（兼容单双引号）。
         */
        fun extractPluginMeta(code: String): Pair<String?, String?> {
            val idx = code.indexOf("PluginJS")
            if (idx < 0) return null to null
            val scope = code.substring(idx, minOf(code.length, idx + 2000))
            val name = Regex("(?<![\\w$])[\"']?name[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
                .find(scope)?.groupValues?.get(1)?.trim()
            val id = Regex("(?<![\\w$])[\"']?id[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
                .find(scope)?.groupValues?.get(1)?.trim()
            return name?.takeIf { it.isNotBlank() } to id?.takeIf { it.isNotBlank() }
        }
    }
}
