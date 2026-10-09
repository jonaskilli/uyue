package io.legado.app.ui.tts.script

import android.content.Context
import android.content.Intent
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.data.entities.TtsScript
import io.legado.app.ui.code.BaseCodeEditorActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi

/**
 * 朗读脚本编辑器。
 *
 * 原先是「名称输入框 + 一个几百行的 EditText」，要编辑代码还得再点「全屏编辑」
 * 跳到 CodeEditActivity，编辑完再把整段文本塞回 EditText——两套编辑体验。
 * 现在改用与 TTS 插件编辑器同一套外壳（[BaseCodeEditorActivity]）：
 * 内联 sora 编辑器 + 语法高亮 + 搜索替换 + 代码折叠 + 格式化 + 主题。
 */
class TtsScriptEditActivity : BaseCodeEditorActivity() {

    private val scriptViewModel by viewModels<TtsScriptEditViewModel>()

    private lateinit var etName: TextInputEditText

    private var scriptId: Long = -1

    companion object {
        fun startIntent(context: Context, id: Long = -1): Intent {
            return Intent(context, TtsScriptEditActivity::class.java).apply {
                putExtra("id", id)
            }
        }
    }

    // ---------------------------------------------------------------- 基类钩子

    override fun editorTitle(): String = getString(R.string.tts_script_edit)

    override fun foldStateKey(): String = "ttsScript_$scriptId"

    override fun saveAsFileName(): String =
        "ttsrv-script-${etName.text?.toString()?.trim().orEmpty().ifBlank { "script" }}.js"

    override val extraMenuRes: Int = R.menu.tts_script_edit

    override fun createHeaderView(): View {
        val til = TextInputLayout(this).apply {
            hint = getString(R.string.tts_script_name)
            setPadding(24, 12, 24, 8)
        }
        etName = TextInputEditText(this)
        til.addView(etName)
        return til
    }

    override fun onEditorReady() {
        scriptId = intent.getLongExtra("id", -1)
        scriptViewModel.initData(intent) { upScriptView(it) }
    }

    override fun onSaveClick(code: String) {
        val script = buildTtsScript(code)
        if (script.name.isBlank()) {
            toastOnUi("脚本名称不能为空")
            return
        }
        scriptViewModel.save(script) {
            setResult(RESULT_OK)
            finishDirectly()
        }
    }

    override fun onExtraMenuSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_copy_rule -> sendToClip(GSON.toJson(buildTtsScript(currentCode())))
            R.id.menu_paste_rule -> scriptViewModel.pasteScript { upScriptView(it) }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- 数据

    private fun upScriptView(script: TtsScript) {
        etName.setText(script.name)
        setCode(script.code)
    }

    private fun buildTtsScript(code: String): TtsScript {
        val script = scriptViewModel.ttsScript ?: TtsScript(name = "")
        script.name = etName.text?.toString()?.trim().orEmpty()
        script.code = code
        return script
    }
}
