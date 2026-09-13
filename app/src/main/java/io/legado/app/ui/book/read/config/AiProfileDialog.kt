package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogAiProfileBinding
import io.legado.app.model.CharacterProfileManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 「AI 角色档案」设置弹窗：接口配置（输入框直填，格式同 AI 处理章节）、
 * 增量频率、重试/超时、自动建档开关。保存写入 aiProfileTemplate 默认模板。
 */
class AiProfileDialog : BaseDialogFragment(R.layout.dialog_ai_profile) {

    private val binding by viewBinding(DialogAiProfileBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        execute {
            appDb.aiProfileTemplateDao.getDefault()
                ?: CharacterProfileManager.builtinDefaultTemplate()
        }.onSuccess(Dispatchers.Main) { template ->
            binding.etApi.setText(template.apiConfig)
            binding.etPrompt.setText(template.prompt)
            binding.etChapterGap.setText(template.chapterGap.toString())
            binding.etRetry.setText(template.retryCount.toString())
            binding.etTimeout.setText(template.timeoutSeconds.toString())
            binding.switchAuto.isChecked = template.autoEnable
            binding.tvSave.setOnClickListener { save(template.id) }
        }
        binding.tvCancel.setOnClickListener { dismissAllowingStateLoss() }
    }

    private fun save(id: Long) {
        val api = binding.etApi.text?.toString()?.trim().orEmpty()
        if (api.isNotEmpty()) {
            val providers =
                io.legado.app.service.AiChapterFixService.parseApiConfigs(api)
            if (providers.isEmpty()) {
                binding.etApi.error = "格式应为：接口@@模型@@密钥"
                return
            }
        }
        val template = CharacterProfileManager.builtinDefaultTemplate().copy(
            id = id,
            apiConfig = api,
            prompt = binding.etPrompt.text?.toString()?.trim().orEmpty(),
            chapterGap = binding.etChapterGap.text?.toString()?.trim()?.toIntOrNull()
                ?: 5,
            retryCount = binding.etRetry.text?.toString()?.trim()?.toIntOrNull() ?: 1,
            timeoutSeconds = binding.etTimeout.text?.toString()?.trim()?.toIntOrNull()
                ?: 240,
            autoEnable = binding.switchAuto.isChecked,
            isDefault = true,
            lastUpdateTime = System.currentTimeMillis()
        )
        appDb.aiProfileTemplateDao.insert(template)
        CharacterProfileManager.invalidateTemplateCache()
        toastOnUi("档案设置已保存")
        dismissAllowingStateLoss()
    }
}
