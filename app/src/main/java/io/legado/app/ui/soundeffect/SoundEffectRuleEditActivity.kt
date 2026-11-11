package io.legado.app.ui.soundeffect

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivitySoundEffectRuleEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.soundeffect.SoundEffectRuleEditViewModel.Companion.EXTRA_GROUP_ID
import io.legado.app.ui.soundeffect.SoundEffectRuleEditViewModel.Companion.EXTRA_GROUP_IDS
import io.legado.app.ui.soundeffect.SoundEffectRuleEditViewModel.Companion.EXTRA_GROUP_NAMES
import io.legado.app.ui.soundeffect.SoundEffectRuleEditViewModel.Companion.EXTRA_RULE
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.imeHeight
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 编辑 / 新建音效替换规则。
 *
 * 「替换为」里填写的通常是音效标记，例如 `(打雷音效)`，
 * 朗读时会把它换成实际的音效播放。
 */
class SoundEffectRuleEditActivity :
    VMBaseActivity<ActivitySoundEffectRuleEditBinding, SoundEffectRuleEditViewModel>(),
    KeyboardToolPop.CallBack {

    override val binding by viewBinding(ActivitySoundEffectRuleEditBinding::inflate)
    override val viewModel by viewModels<SoundEffectRuleEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    private val textEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
            val view = window.decorView.findFocus()
            if (view is EditText) {
                view.setText(text)
                view.setSelection(result.data!!.getIntExtra("cursorPosition", 0))
            } else {
                toastOnUi(R.string.focus_lost_on_textbox)
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) { upRuleView() }
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
        SoundEffectPlayer.stop()
    }

    private fun initView() {
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
        binding.ivSelectGroup.setOnClickListener { selectGroup() }
        binding.ivPlayEffect.setOnClickListener {
            SoundEffectPlayer.play(binding.etReplacement.text.toString()) { error ->
                error?.let { toastOnUi(it) }
            }
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateTestResult()
        }
        binding.etPattern.addTextChangedListener(watcher)
        binding.etReplacement.addTextChangedListener(watcher)
        binding.etTestText.addTextChangedListener(watcher)
        binding.cbUseRegex.setOnCheckedChangeListener { _, _ -> updateTestResult() }
    }

    private fun upRuleView() {
        val rule = viewModel.rule ?: return
        binding.etName.setText(rule.name)
        binding.etPattern.setText(rule.pattern)
        binding.etReplacement.setText(rule.replacement)
        binding.etTestText.setText(rule.sampleText)
        binding.cbUseRegex.isChecked = rule.isRegex
        binding.cbEnabled.isChecked = rule.isEnabled
        binding.etGroup.setText(
            viewModel.groupNames.getOrNull(
                viewModel.groupIds.indexOf(viewModel.groupId)
            ).orEmpty()
        )
        updateTestResult()
    }

    private fun selectGroup() {
        val names = viewModel.groupNames
        if (names.isEmpty()) {
            toastOnUi("没有可选的分组")
            return
        }
        selector(names.toList()) { _, selected, _ ->
            val text = selected.toString()
            val index = viewModel.groupNames.indexOf(text)
            viewModel.groupId = viewModel.groupIds.getOrNull(index) ?: viewModel.groupId
            binding.etGroup.setText(text)
        }
    }

    private fun buildRule(): SoundEffectRule {
        val rule = viewModel.rule ?: SoundEffectRule(id = System.currentTimeMillis())
        rule.name = binding.etName.text.toString().trim()
        rule.pattern = binding.etPattern.text.toString()
        rule.replacement = binding.etReplacement.text.toString()
        rule.isRegex = binding.cbUseRegex.isChecked
        rule.isEnabled = binding.cbEnabled.isChecked
        rule.sampleText = binding.etTestText.text.toString()
        return rule
    }

    private fun updateTestResult() {
        val testText = binding.etTestText.text.toString()
        if (testText.isEmpty()) {
            binding.etReplaceResult.setText("")
            return
        }
        // 预览只读界面当前值，不调用 buildRule()，避免把界面默认值写回 viewModel.rule
        val pattern = binding.etPattern.text.toString()
        val replacement = binding.etReplacement.text.toString()
        val isRegex = binding.cbUseRegex.isChecked
        binding.etReplaceResult.setText(
            try {
                if (isRegex) {
                    SoundEffectRuleManager.compilePattern(pattern)
                        .replace(testText, replacement)
                } else {
                    testText.replace(pattern, replacement)
                }
            } catch (e: Exception) {
                "正则无效：${e.localizedMessage}"
            }
        )
    }

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.sound_effect_rule_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> {
                val rule = buildRule()
                viewModel.save(rule) {
                    setResult(
                        RESULT_OK,
                        Intent().apply {
                            putExtra(EXTRA_RULE, rule)
                            putExtra(EXTRA_GROUP_ID, viewModel.groupId)
                        }
                    )
                    finish()
                }
            }

            R.id.menu_fullscreen_edit -> fullEdit()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun fullEdit() {
        val view = window.decorView.findFocus()
        if (view !is EditText) {
            toastOnUi(R.string.please_focus_cursor_on_textbox)
            return
        }
        val hint = findParentTextInputLayout(view)?.hint?.toString()
        textEditLauncher.launch(
            Intent(this, CodeEditActivity::class.java).apply {
                putExtra("text", view.text.toString())
                putExtra("title", hint)
                putExtra("cursorPosition", view.selectionStart)
            }
        )
    }

    // ---------------- KeyboardToolPop.CallBack ----------------

    override fun helpActions(): List<SelectItem<String>> = arrayListOf(
        SelectItem("正则教程", "regexHelp")
    )

    override fun onHelpActionSelect(action: String) {
        if (action == "regexHelp") showHelp("regexHelp")
    }

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = window?.decorView?.findFocus()
        if (view !is EditText) return
        var start = view.selectionStart
        var end = view.selectionEnd
        if (start > end) {
            val temp = start
            start = end
            end = temp
        }
        val edit = view.editableText
        if (start < 0 || start >= edit.length) {
            edit.append(text)
        } else {
            edit.replace(start, end, text)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onUndoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) editText.onTextContextMenuItem(android.R.id.undo)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onRedoClicked() {
        val editText = window.decorView.findFocus()
        if (editText is EditText) editText.onTextContextMenuItem(android.R.id.redo)
    }

    companion object {

        fun startIntent(
            context: Context,
            rule: SoundEffectRule?,
            groupId: Long,
            groupIds: LongArray,
            groupNames: Array<String>
        ): Intent = Intent(context, SoundEffectRuleEditActivity::class.java).apply {
            putExtra(EXTRA_RULE, rule)
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_GROUP_IDS, groupIds)
            putExtra(EXTRA_GROUP_NAMES, groupNames)
        }
    }
}
