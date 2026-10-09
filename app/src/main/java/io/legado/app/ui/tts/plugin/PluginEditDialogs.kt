package io.legado.app.ui.tts.plugin

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.help.audiobook.JReadVoicePluginRuntime
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * TTS 插件编辑相关的共用对话框。
 *
 * 「设置变量」「预览插件界面」「音频参数」「清空数据」这四项在插件列表页和插件代码编辑器里
 * 都会用到，原先在 TtsPluginActivity 里写了一份，移植编辑器时如果再抄一份就会出现两套实现。
 * 这里抽成共用类：登录页的 ActivityResult 由本类内部持有（构造时即注册，符合
 * registerForActivityResult 必须在 STARTED 之前调用的要求），两个页面各自持有一个实例即可。
 */
class PluginEditDialogs(private val activity: ComponentActivity) {

    private var loginHolder: JReadVoicePluginRuntime.VarLoginRequestHolder? = null
    private var loginKey: String? = null

    private val loginLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PluginLoginActivity.OK) {
            val value = result.data?.getStringExtra(PluginLoginActivity.RESULT).orEmpty()
            val holder = loginHolder
            val key = loginKey
            if (holder != null && key != null && value.isNotBlank()) {
                holder.values[key] = value
                holder.editViews[key]?.setText(value)
                activity.toastOnUi("已获取登录信息")
            }
        }
        loginHolder = null
        loginKey = null
    }

    // ---------------------------------------------------------------- 设置变量

    /**
     * 弹出「设置变量」对话框：执行插件 JS，调用其 EditorJS/PluginJS.onLoadUI 自绘变量 UI
     * （支持任意多个输入框/下拉框），确认后把值写回插件 userVarsJson。
     */
    fun showVars(
        plugin: JReadVoiceEngine.VoicePlugin,
        onSaved: (JReadVoiceEngine.VoicePlugin) -> Unit,
    ) {
        activity.lifecycleScope.launch {
            val dataMap = buildDataMap(plugin)
            val userVarsMap = buildUserVarsMap(plugin, dataMap)
            val container = buildVarsContainer(plugin, dataMap, userVarsMap)
            if (container == null) {
                activity.toastOnUi(R.string.plugin_no_vars)
                return@launch
            }
            bindLoginButtons(container)
            val scrollView = ScrollView(activity).apply { addView(container) }
            activity.alert(activity.getString(R.string.plugin_vars) + " - " + plugin.name) {
                customView { scrollView }
                okButton {
                    activity.lifecycleScope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            val json = JSONObject()
                            userVarsMap.forEach { (k, v) -> json.put(k, v ?: "") }
                            val updated = plugin.copy(userVarsJson = json.toString())
                            JReadVoiceEngine.savePlugin(activity, updated)
                            updated
                        }
                        activity.toastOnUi("变量已保存: ${plugin.name}")
                        onSaved(saved)
                    }
                }
                cancelButton()
            }
        }
    }

    // ---------------------------------------------------------------- 预览插件界面

    /**
     * 预览插件自绘界面（EditorJS.onLoadUI / PluginJS.vars），用于确认插件 UI 是否按预期渲染。
     * @param onDataChanged 用户在预览 UI 里改动的值（ttsrv.data），一般无需落库
     */
    fun showPreviewUi(
        plugin: JReadVoiceEngine.VoicePlugin,
        onDataChanged: ((Map<String, String>) -> Unit)? = null,
    ) {
        activity.lifecycleScope.launch {
            val dataMap = buildDataMap(plugin)
            val userVarsMap = buildUserVarsMap(plugin, dataMap)
            val container = buildVarsContainer(plugin, dataMap, userVarsMap)
            if (container == null) {
                activity.toastOnUi(R.string.plugin_no_vars)
                return@launch
            }
            bindLoginButtons(container)
            val scrollView = ScrollView(activity).apply { addView(container) }
            activity.alert(activity.getString(R.string.plugin_preview_ui)) {
                customView { scrollView }
                okButton { onDataChanged?.invoke(dataMap) }
                cancelButton()
            }
        }
    }

    // ---------------------------------------------------------------- 音频参数

    /**
     * 插件级音频参数：合成参数与每个音色配置里的参数相乘；后处理参数再与分组/全局链路相乘。
     */
    fun showAudioParams(
        plugin: JReadVoiceEngine.VoicePlugin,
        onSaved: (JReadVoiceEngine.VoicePlugin) -> Unit,
    ) {
        val ctx = activity
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(ctx).apply {
            text = plugin.name
            textSize = 14f
            setPadding(4, 4, 4, 4)
        })
        container.addView(TextView(ctx).apply {
            text = "合成参数：与每个音色配置里的参数相乘，不会覆盖配置本身的值"
            textSize = 12f
            setPadding(4, 4, 4, 12)
        })
        val pluginSpeed = if (plugin.speed <= 0f) 1f else plugin.speed
        val pluginVolume = if (plugin.volume <= 0f) 1f else plugin.volume
        val pluginPitch = if (plugin.pitch <= 0f) 1f else plugin.pitch
        val sbSpeed = buildSliderRow(ctx, "语速", (pluginSpeed * 100).toInt().coerceIn(1, 300), false)
        val sbVolume = buildSliderRow(ctx, "音量", (pluginVolume * 100).toInt().coerceIn(1, 300), false)
        val sbPitch = buildSliderRow(ctx, "音调", (pluginPitch * 100).toInt().coerceIn(1, 300), false)
        listOf(sbSpeed, sbVolume, sbPitch).forEach { container.addView(it.second) }
        container.addView(TextView(ctx).apply {
            text = "合成完毕后（后处理）：与分组/全局后处理参数相乘\n跟随 = 不干预"
            textSize = 12f
            setPadding(4, 16, 4, 12)
        })
        val sbPostSpeed = buildSliderRow(
            ctx, "后语速", if (plugin.postSpeed <= 0f) 0 else (plugin.postSpeed * 100).toInt(), true
        )
        val sbPostVolume = buildSliderRow(
            ctx, "后音量", if (plugin.postVolume <= 0f) 0 else (plugin.postVolume * 100).toInt(), true
        )
        val sbPostPitch = buildSliderRow(
            ctx, "后音高", if (plugin.postPitch <= 0f) 0 else (plugin.postPitch * 100).toInt(), true
        )
        listOf(sbPostSpeed, sbPostVolume, sbPostPitch).forEach { container.addView(it.second) }
        ScrollView(ctx).apply { addView(container) }.let { scrollView ->
            activity.alert(activity.getString(R.string.plugin_audio_params)) {
                customView { scrollView }
                okButton {
                    val speed = sbSpeed.first.progress / 100f
                    val volume = sbVolume.first.progress / 100f
                    val pitch = sbPitch.first.progress / 100f
                    val postSpeed = sbPostSpeed.first.progress / 100f
                    val postVolume = sbPostVolume.first.progress / 100f
                    val postPitch = sbPostPitch.first.progress / 100f
                    activity.lifecycleScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            JReadVoiceEngine.updatePluginAudioParams(
                                activity, plugin, speed, volume, pitch, postSpeed, postVolume, postPitch
                            )
                        }
                        if (!ok) {
                            activity.toastOnUi("插件不存在，更新失败")
                            return@launch
                        }
                        activity.toastOnUi("已更新音频参数")
                        onSaved(
                            plugin.copy(
                                speed = speed,
                                volume = volume,
                                pitch = pitch,
                                postSpeed = postSpeed,
                                postVolume = postVolume,
                                postPitch = postPitch,
                            )
                        )
                    }
                }
                cancelButton()
            }
        }
    }

    // ---------------------------------------------------------------- 清空数据

    fun clearData(plugin: JReadVoiceEngine.VoicePlugin, onDone: () -> Unit = {}) {
        activity.alert(activity.getString(R.string.plugin_clear_data)) {
            setMessage("确定清空 ${plugin.name} 的所有缓存数据？")
            positiveButton("清空") {
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val storageId = plugin.pluginId.ifBlank { plugin.id }
                        val dir = File(activity.filesDir, "jread_voice_engine/plugin_files/$storageId")
                        if (dir.exists()) {
                            dir.listFiles()?.forEach { it.delete() }
                            dir.delete()
                        }
                    }
                    activity.toastOnUi("已清空 ${plugin.name} 的数据")
                    onDone()
                }
            }
            negativeButton("取消")
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /** userVarsJson → dataMap（自绘控件 onLoadUI / ttsrv.data 用） */
    private fun buildDataMap(plugin: JReadVoiceEngine.VoicePlugin): MutableMap<String, String> {
        return runCatching {
            val obj = JSONObject(plugin.userVarsJson.ifBlank { "{}" })
            val map = linkedMapOf<String, String>()
            obj.keys().forEach { map[it] = obj.optString(it) }
            map
        }.getOrDefault(linkedMapOf())
    }

    /** 声明式变量（PluginJS.vars / ttsrv.userVars）预填：已保存值 > defVarsJson 默认值 > 空 */
    private fun buildUserVarsMap(
        plugin: JReadVoiceEngine.VoicePlugin,
        dataMap: MutableMap<String, String>,
    ): MutableMap<String, String> {
        val map = dataMap.toMutableMap()
        runCatching { JSONObject(plugin.defVarsJson.ifBlank { "{}" }) }.getOrNull()?.let { dv ->
            dv.keys().forEach { k ->
                if (!map.containsKey(k)) {
                    val v = dv.opt(k)
                    map[k] = when (v) {
                        is JSONObject -> v.optString("value", v.optString("default", v.optString("def", "")))
                        null -> ""
                        else -> v.toString()
                    }
                }
            }
        }
        return map
    }

    private suspend fun buildVarsContainer(
        plugin: JReadVoiceEngine.VoicePlugin,
        dataMap: MutableMap<String, String>,
        userVarsMap: MutableMap<String, String>,
    ): ViewGroup? {
        if (plugin.code.isBlank()) return null
        val container = withContext(Dispatchers.IO) {
            JReadVoicePluginRuntime.loadPluginEditorUI(activity, plugin, dataMap, userVarsMap)
        } ?: return null
        if (container.childCount == 0) return null
        return container
    }

    /** 声明式变量带 loginUrl/binding → 给「登录」按钮绑定回调 */
    private fun bindLoginButtons(container: View) {
        val holder = container.tag as? JReadVoicePluginRuntime.VarLoginRequestHolder ?: return
        holder.infos.forEach { info ->
            holder.buttons[info.key]?.setOnClickListener {
                loginKey = info.key
                loginHolder = holder
                loginLauncher.launch(Intent(activity, PluginLoginActivity::class.java).apply {
                    putExtra(PluginLoginActivity.ARG_LOGIN_URL, info.loginUrl)
                    putExtra(PluginLoginActivity.ARG_BINDING, info.binding)
                    putExtra(PluginLoginActivity.ARG_DESC, info.desc)
                    putExtra(PluginLoginActivity.ARG_UA, info.ua)
                })
            }
        }
    }

    companion object {

        /**
         * 构建一个滑块行：标签+数值居中在上方，减号+滑块+加号在下方
         * @param allowFollow 是否支持"跟随"（progress=0 时显示"跟随"）
         * @return Pair(SeekBar, 外层布局)
         */
        fun buildSliderRow(
            context: Context,
            label: String,
            initialProgress: Int,
            allowFollow: Boolean,
        ): Pair<SeekBar, View> {
            val density = context.resources.displayMetrics.density
            val sb = SeekBar(context).apply {
                max = 300
                progress = initialProgress.coerceIn(0, 300)
            }
            val tvVal = TextView(context).apply {
                textSize = 13f
                text = if (allowFollow && initialProgress == 0) "跟随"
                else String.format("%.2f", initialProgress / 100f)
            }
            val updateText: () -> Unit = {
                val p = sb.progress
                tvVal.text = if (allowFollow && p == 0) "跟随" else String.format("%.2f", p / 100f)
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = updateText()
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
            val labelRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                addView(TextView(context).apply {
                    text = label
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#FF333333"))
                })
                addView(TextView(context).apply { text = "  "; textSize = 13f })
                addView(tvVal)
            }
            val sliderRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val btnParams = LinearLayout.LayoutParams((40 * density).toInt(), (36 * density).toInt())
                val btnMinus = android.widget.Button(context).apply {
                    text = "−"
                    textSize = 18f
                    layoutParams = btnParams
                    minWidth = 0
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { sb.progress = (sb.progress - 1).coerceAtLeast(0) }
                }
                val btnPlus = android.widget.Button(context).apply {
                    text = "+"
                    textSize = 18f
                    layoutParams = btnParams
                    minWidth = 0
                    minHeight = 0
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { sb.progress = (sb.progress + 1).coerceAtMost(300) }
                }
                addView(btnMinus)
                addView(sb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(btnPlus)
            }
            val outer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(labelRow)
                addView(sliderRow)
            }
            return sb to outer
        }
    }
}
