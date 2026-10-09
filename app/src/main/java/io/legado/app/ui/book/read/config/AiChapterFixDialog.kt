package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.databinding.DialogAiChapterFixBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.service.AiChapterFixService
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers

/**
 * 「AI处理章节」对话框（模板化）。
 *
 * 顶部显示当前书生效的模板（绑定的模板或默认模板），可打开模板列表切换编辑目标/绑定。
 * 所有输入框编辑的就是当前生效模板的字段，输入即保存（写入数据库）。
 * 多组接口轮换格式：接口@@模型@@密钥@@接口@@模型@@密钥@@...（3 段一组，可无限追加）。
 *
 * 点「处理当前章节」/「处理下一章」后回调 [onConfirm]，由调用方执行处理。
 */
class AiChapterFixDialog : BaseDialogFragment(R.layout.dialog_ai_chapter_fix) {

    private val binding by viewBinding(DialogAiChapterFixBinding::bind)

    /** 当前编辑的模板（当前书生效的模板） */
    private var currentTemplate: AiChapterFixTemplate? = null

    /** 点处理按钮后回调：(生效模板, 是否从当前章开始, 是否忽略已完成记录) */
    var onConfirm: ((template: AiChapterFixTemplate, fromCurrent: Boolean, force: Boolean) -> Unit)? =
        null

    /** 自动处理开关变化回调（按书生效，用于同步工具栏图标状态） */
    var onAutoChangedCallback: ((Boolean) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnCurrent.setOnClickListener { confirm(fromCurrent = true) }
        binding.btnNext.setOnClickListener { confirm(fromCurrent = false) }
        // 右上角保存按钮：手动确认保存并给出反馈，不关闭对话框
        binding.toolBar.inflateMenu(R.menu.save)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_save) {
                saveSettings()
                context?.toastOnUi("设置已保存到模板「${currentTemplate?.name.orEmpty()}」")
            }
            true
        }
        // 模板列表：切换编辑目标 / 绑定当前书
        binding.btnTemplateList.setOnClickListener {
            AiChapterFixTemplateListDialog.newInstance(
                currentEditId = currentTemplate?.id ?: 0,
                onSelect = { template ->
                    currentTemplate = template
                    fillInputs()
                },
                onBindChanged = { refreshCurrentTemplate() }
            ).let { showDialogFragmentSafely(it) }
        }
        // 自动处理开关按书生效，与底部工具栏的 AI 图标状态同步
        val book = ReadBook.book
        binding.switchAuto.isChecked = if (book != null) {
            AiChapterFixService.isAutoFixEnabled(book.name, book.bookUrl)
        } else {
            AppConfig.aiChapterFixAuto
        }
        binding.switchAuto.setOnCheckedChangeListener { _, isChecked ->
            if (book != null) {
                AiChapterFixService.setAutoFixEnabled(book.name, book.bookUrl, isChecked)
            } else {
                AppConfig.aiChapterFixAuto = isChecked
            }
            onAutoChangedCallback?.invoke(isChecked)
        }
        // 输入即保存到当前模板（监听注册在回显之后，回显不会误触发）
        binding.etApi.doAfterTextChanged { saveSettings() }
        binding.etPrompt.doAfterTextChanged { saveSettings() }
        binding.etCount.doAfterTextChanged { saveSettings() }
        binding.etConcurrency.doAfterTextChanged { saveSettings() }
        binding.etMaxLength.doAfterTextChanged { saveSettings() }
        binding.etRetryCount.doAfterTextChanged { saveSettings() }
        binding.etContextLength.doAfterTextChanged { saveSettings() }
        binding.etTimeout.doAfterTextChanged { saveSettings() }
        // 关闭时再兜一次
        setOnDismissListener { kotlin.runCatching { saveSettings() } }
        // 异步读取当前书生效的模板并回显
        refreshCurrentTemplate()
    }

    private fun refreshCurrentTemplate() {
        val book = ReadBook.book
        execute {
            if (book != null) {
                AiChapterFixService.getEffectiveTemplate(book.name, book.bookUrl)
            } else {
                appDb.aiChapterFixTemplateDao.getDefault()
                    ?: AiChapterFixService.builtinDefaultTemplate()
            }
        }.onSuccess(Dispatchers.Main) { template ->
            currentTemplate = template
            binding.tvTemplateName.text = buildString {
                append("当前模板：${template.name}")
                val boundId = if (book != null) {
                    AiChapterFixService.getBoundTemplateId(book.name, book.bookUrl)
                } else 0L
                if (boundId == 0L && template.isDefault) append("（未绑定，使用默认）")
            }
            fillInputs()
        }
    }

    private fun fillInputs() {
        val t = currentTemplate ?: return
        binding.etApi.setText(t.apiConfig)
        binding.etPrompt.setText(t.prompt.ifBlank { AiChapterFixService.DEFAULT_PROMPT })
        binding.etCount.setText(t.chapterCount.toString())
        binding.etConcurrency.setText(t.concurrency.toString())
        binding.etMaxLength.setText(t.maxLength.toString())
        binding.etRetryCount.setText(t.retryCount.toString())
        binding.etContextLength.setText(t.contextLength.toString())
        binding.etTimeout.setText(t.timeoutSeconds.toString())
    }

    private fun showDialogFragmentSafely(dialog: AiChapterFixTemplateListDialog) {
        (activity as? androidx.fragment.app.FragmentActivity)?.let {
            it.supportFragmentManager.beginTransaction()
                .add(dialog, "aiChapterFixTemplateList")
                .commitAllowingStateLoss()
        }
    }

    /**
     * 把输入框里的内容写回当前模板并落库。
     * 空值不覆盖，避免误清空（比如提示词框被清空后仍保留上一次的设置）。
     */
    private fun saveSettings() {
        val t = currentTemplate ?: return
        var changed = false
        fun newTemplate(block: (AiChapterFixTemplate) -> AiChapterFixTemplate) {
            val updated = block(t)
            currentTemplate = updated
            execute { appDb.aiChapterFixTemplateDao.update(updated) }
            changed = true
        }
        binding.etApi.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeIf { it != t.apiConfig }?.let { api -> newTemplate { it.copy(apiConfig = api) } }
        binding.etPrompt.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.takeIf { it != t.prompt }?.let { p -> newTemplate { it.copy(prompt = p) } }
        binding.etCount.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it != t.chapterCount }?.let { v -> newTemplate { it.copy(chapterCount = v) } }
        binding.etConcurrency.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it != t.concurrency }?.let { v -> newTemplate { it.copy(concurrency = v) } }
        binding.etMaxLength.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it != t.maxLength }?.let { v -> newTemplate { it.copy(maxLength = v) } }
        binding.etRetryCount.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it != t.retryCount }?.let { v -> newTemplate { it.copy(retryCount = v) } }
        binding.etContextLength.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it != t.contextLength }?.let { v -> newTemplate { it.copy(contextLength = v) } }
        binding.etTimeout.text?.toString()?.trim()?.toIntOrNull()
            ?.takeIf { it != t.timeoutSeconds }?.let { v -> newTemplate { it.copy(timeoutSeconds = v) } }
        AppConfig.aiChapterFixAuto = binding.switchAuto.isChecked
    }

    private fun confirm(fromCurrent: Boolean) {
        val template = currentTemplate ?: return
        val api = binding.etApi.text?.toString()?.trim().orEmpty()
        val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
        val force = binding.switchForce.isChecked
        if (api.isEmpty()) {
            binding.etApi.error = "请填写接口地址"
            return
        }
        if (prompt.isEmpty()) {
            binding.etPrompt.error = "请填写提示词"
            return
        }
        saveSettings()
        dismissAllowingStateLoss()
        onConfirm?.invoke(currentTemplate ?: template, fromCurrent, force)
    }
}
