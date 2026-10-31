package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResultListener
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.BgmAIPrompt
import io.legado.app.databinding.DialogBgmAiPromptEditBinding
import io.legado.app.service.BgmManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAIPromptEditDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_prompt_edit) {

    private val binding by viewBinding(DialogBgmAiPromptEditBinding::bind)
    private var prompt: BgmAIPrompt? = null
    private var onSavedCallback: (() -> Unit)? = null
    /** 编辑既有提示词时传入的 id；0 表示新建 */
    private var editPromptId = 0L

    companion object {
        private const val ARG_PROMPT_ID = "prompt_id"
        private const val ARG_SHOW_AUDIO_LIST = "show_audio_list"
        private const val ARG_LOCK_NAME = "lock_name"

        /**
         * @param showAudioList BGM 选曲提示词编辑时附带"可用音频文件列表"预览；
         *                      AI 音效提示词不需要，传 false
         * @param lockName 名称是运行时按名查表的键（AI 音效提示词就是），
         *                 改了会导致取不到而回退到内置默认，故锁住不让改
         */
        fun newInstance(
            prompt: BgmAIPrompt?,
            showAudioList: Boolean = true,
            lockName: Boolean = false,
            onSaved: () -> Unit
        ): BgmAIPromptEditDialog {
            return BgmAIPromptEditDialog().apply {
                arguments = Bundle().apply {
                    prompt?.id?.let { putLong(ARG_PROMPT_ID, it) }
                    putBoolean(ARG_SHOW_AUDIO_LIST, showAudioList)
                    putBoolean(ARG_LOCK_NAME, lockName)
                }
                onSavedCallback = onSaved
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val promptId = arguments?.getLong(ARG_PROMPT_ID, 0) ?: 0
        val showAudioList = arguments?.getBoolean(ARG_SHOW_AUDIO_LIST, true) ?: true
        val lockName = arguments?.getBoolean(ARG_LOCK_NAME, false) ?: false
        editPromptId = promptId
        binding.etName.isEnabled = !lockName
        if (promptId > 0) {
            execute {
                appDb.bgmAIPromptDao.getById(promptId)
            }.onSuccess(Dispatchers.Main) {
                it?.let { loadedPrompt ->
                    prompt = loadedPrompt
                    binding.etName.setText(loadedPrompt.name)
                    // 显示提示词时，添加音频文件列表预览（仅用于显示，保存时不会保存）。
                    // 只有 BGM 选曲提示词需要，AI 音效提示词传 showAudioList=false 跳过
                    val audioFileList =
                        if (showAudioList) BgmManager.getAudioFileList() else emptyList()
                    val displayPrompt = if (audioFileList.isNotEmpty()) {
                        val listSection = "\n\n可用文件列表（已排除当前播放）：\n${audioFileList.joinToString("、")}"
                        if (!loadedPrompt.prompt.contains("可用文件列表")) {
                            loadedPrompt.prompt + listSection
                        } else {
                            loadedPrompt.prompt
                        }
                    } else {
                        loadedPrompt.prompt
                    }
                    binding.etPrompt.setText(displayPrompt)
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSave.setOnClickListener {
            savePrompt()
        }
    }

    private fun savePrompt() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        var promptText = binding.etPrompt.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            binding.etName.error = "标题不能为空"
            return
        }

        if (promptText.isEmpty()) {
            binding.etPrompt.error = "提示词不能为空"
            return
        }

        // 去掉动态添加的音频文件列表部分，只保存提示词主体
        val audioFileListSectionStart = "\n\n可用文件列表（已排除当前播放）：\n"
        val audioFileListSectionIndex = promptText.indexOf(audioFileListSectionStart)
        if (audioFileListSectionIndex >= 0) {
            promptText = promptText.substring(0, audioFileListSectionIndex).trim()
        }

        execute {
            // 目标记录兜底：异步加载未完成（用户秒点保存）或加载失败时，
            // 按 id 重新查一次；查得到必须沿用原记录的 id/isDefault 走 REPLACE 更新。
            // 否则 id 落到 System.currentTimeMillis()，编辑旧提示词会被存成
            // 一条全新的记录，列表里凭空多出重复条目。
            var target = prompt
            if (target == null && editPromptId > 0) {
                target = appDb.bgmAIPromptDao.getById(editPromptId)
            }
            val newPrompt = BgmAIPrompt(
                id = target?.id ?: System.currentTimeMillis(),
                name = name,
                prompt = promptText,
                isDefault = target?.isDefault ?: false
            )

            // 如果设置为默认，取消其他提示词的默认状态
            if (newPrompt.isDefault) {
                val all = appDb.bgmAIPromptDao.getAllList()
                all.forEach { it.isDefault = false }
                appDb.bgmAIPromptDao.update(*all.toTypedArray())
            }

            appDb.bgmAIPromptDao.insert(newPrompt)
        }.onSuccess(Dispatchers.Main) {
            onSavedCallback?.invoke()
            dismiss()
        }
    }
}
