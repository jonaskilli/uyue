package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAiChapterFixBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.service.AiChapterFixService
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 「AI处理章节」对话框：接口输入框（格式与 AI 切歌一致：
 * 接口地址@@模型名@@密钥）、提示词输入框、处理章节数量、自动处理开关。
 *
 * 点「处理当前章节」/「处理下一章」后保存各项设置，把参数回传给调用方执行，
 * 自身先关闭——真正的处理耗时较长，不适合挂在对话框的生命周期上。
 *
 * 设置项的保存时机（避免"填了没生效"）：
 *  - 自动处理开关：拨动即保存，并且打开时顺手把其它输入框一起保存；
 *  - 其它输入框：点处理按钮时保存，另外界面关闭时（取消/返回/点外部）也保存一次。
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
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnCurrent.setOnClickListener { confirm(fromCurrent = true) }
        binding.btnNext.setOnClickListener { confirm(fromCurrent = false) }
        // 自动处理开关"拨动即保存"：只拨开关、不点处理按钮就退出，下次进来也要保持
        binding.switchAuto.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.aiChapterFixAuto = isChecked
            if (isChecked) saveSettings()
        }
        binding.switchAuto.isChecked = AppConfig.aiChapterFixAuto
        // 界面关闭时再兜一次：填了内容没点处理按钮就退出，也不该丢
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
