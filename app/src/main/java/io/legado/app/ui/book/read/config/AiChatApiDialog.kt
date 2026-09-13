package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAiChatApiBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AiChatManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 「与角色对话」接口设置弹窗。
 *
 * 输入格式与「AI处理章节」完全一致（接口@@模型@@密钥，3 段一组，可多组轮换），
 * 解析层直接复用 [AiChapterFixService] 的同款解析，用户零学习成本。
 */
class AiChatApiDialog : BaseDialogFragment(R.layout.dialog_ai_chat_api) {

    private val binding by viewBinding(DialogAiChatApiBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.etApi.setText(AppConfig.roleChatApiConfig)
        binding.etContextLimit.setText(AppConfig.roleChatContextLimit.toString())
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvSave.setOnClickListener {
            val api = binding.etApi.text?.toString()?.trim().orEmpty()
            if (api.isNotEmpty()) {
                val providers = io.legado.app.service.AiChapterFixService.parseApiConfigs(api)
                if (providers.isEmpty()) {
                    binding.etApi.error = "格式应为：接口@@模型@@密钥"
                    return@setOnClickListener
                }
            }
            AppConfig.roleChatApiConfig = api
            binding.etContextLimit.text?.toString()?.trim()?.toIntOrNull()?.let {
                AppConfig.roleChatContextLimit = it
            }
            toastOnUi("已保存（${AiChatManager.apiConfigSummary()}）")
            dismissAllowingStateLoss()
        }
    }
}
