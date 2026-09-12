package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAiChapterFixBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.service.AiChapterFixService
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 「AI处理章节」对话框：接口输入框（格式与 AI 切歌一致：
 * 接口地址@@模型名@@密钥）、提示词输入框、处理章节数量、自动处理开关。
 *
 * 点「处理当前章节」/「处理下一章」后保存各项设置，把参数回传给调用方执行，
 * 自身先关闭——真正的处理耗时较长，不适合挂在对话框的生命周期上。
 *
 * 设置项的保存时机（避免"填了没生效"）：
 *  - 所有输入框：输入即保存（doAfterTextChanged），改完直接退出也不会回到旧值；
 *  - 自动处理开关：拨动即保存；
 *  - 界面关闭时（取消/返回/点外部）再兜底保存一次。
 */
class AiChapterFixDialog : BaseDialogFragment(R.layout.dialog_ai_chapter_fix) {

    private val binding by viewBinding(DialogAiChapterFixBinding::bind)

    /**
     * 点处理按钮后回调：
     * (接口配置, 提示词, 处理数量, 是否从当前章开始, 是否忽略已完成记录)
     */
    var onConfirm: ((
        api: String,
        prompt: String,
        count: Int,
        fromCurrent: Boolean,
        force: Boolean
    ) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.etApi.setText(AppConfig.aiChapterFixApi)
        binding.etPrompt.setText(
            AppConfig.aiChapterFixPrompt.ifBlank { AiChapterFixService.DEFAULT_PROMPT }
        )
        binding.etCount.setText(AppConfig.aiChapterFixCount.toString())
        binding.etMaxLength.setText(AppConfig.aiChapterFixMaxLength.toString())
        binding.etRetryCount.setText(AppConfig.aiChapterFixRetryCount.toString())
        binding.etContextLength.setText(AppConfig.aiChapterFixContextLength.toString())
        binding.etTimeout.setText(AppConfig.aiChapterFixTimeoutSeconds.toString())
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnCurrent.setOnClickListener { confirm(fromCurrent = true) }
        binding.btnNext.setOnClickListener { confirm(fromCurrent = false) }
        // 自动处理开关"拨动即保存"：只拨开关、不点处理按钮就退出，下次进来也要保持
        binding.switchAuto.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.aiChapterFixAuto = isChecked
            if (isChecked) saveSettings()
        }
        binding.switchAuto.isChecked = AppConfig.aiChapterFixAuto
        // 右上角保存按钮：手动确认保存并给出反馈，不关闭对话框
        binding.toolBar.inflateMenu(R.menu.save)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_save) {
                saveSettings()
                context?.toastOnUi("设置已保存")
            }
            true
        }
        // 输入即保存：任何输入框一改就落盘，改完直接退出也不会回到旧值。
        // 监听注册在初始化回显之后，回显不会误触发。
        binding.etApi.doAfterTextChanged { saveSettings() }
        binding.etPrompt.doAfterTextChanged { saveSettings() }
        binding.etCount.doAfterTextChanged { saveSettings() }
        binding.etMaxLength.doAfterTextChanged { saveSettings() }
        binding.etRetryCount.doAfterTextChanged { saveSettings() }
        binding.etContextLength.doAfterTextChanged { saveSettings() }
        binding.etTimeout.doAfterTextChanged { saveSettings() }
        // 关闭时再兜一次（覆盖切换配置等极端情况）
        setOnDismissListener { kotlin.runCatching { saveSettings() } }
    }

    /**
     * 把输入框里的内容落到配置里。
     * 空值不覆盖，避免误清空（比如提示词框被清空后仍保留上一次的设置）。
     */
    private fun saveSettings() {
        binding.etApi.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { AppConfig.aiChapterFixApi = it }
        binding.etPrompt.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { AppConfig.aiChapterFixPrompt = it }
        binding.etCount.text?.toString()?.trim()?.toIntOrNull()
            ?.let { AppConfig.aiChapterFixCount = it }
        binding.etMaxLength.text?.toString()?.trim()?.toIntOrNull()
            ?.let { AppConfig.aiChapterFixMaxLength = it }
        binding.etRetryCount.text?.toString()?.trim()?.toIntOrNull()
            ?.let { AppConfig.aiChapterFixRetryCount = it }
        binding.etContextLength.text?.toString()?.trim()?.toIntOrNull()
            ?.let { AppConfig.aiChapterFixContextLength = it }
        binding.etTimeout.text?.toString()?.trim()?.toIntOrNull()
            ?.let { AppConfig.aiChapterFixTimeoutSeconds = it }
        AppConfig.aiChapterFixAuto = binding.switchAuto.isChecked
    }

    private fun confirm(fromCurrent: Boolean) {
        val api = binding.etApi.text?.toString()?.trim().orEmpty()
        val prompt = binding.etPrompt.text?.toString()?.trim().orEmpty()
        val force = binding.switchForce.isChecked
        if (api.isEmpty()) {
            binding.etApi.error = "请填写接口地址"
            return
        }
        if (api.split("@@").size < 2) {
            binding.etApi.error = "格式错误，应为：接口地址@@模型名字@@密钥"
            return
        }
        if (prompt.isEmpty()) {
            binding.etPrompt.error = "请填写提示词"
            return
        }
        val count = binding.etCount.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(1, AiChapterFixService.MAX_COUNT) ?: 1
        saveSettings()
        dismissAllowingStateLoss()
        onConfirm?.invoke(api, prompt, count, fromCurrent, force)
    }
}
