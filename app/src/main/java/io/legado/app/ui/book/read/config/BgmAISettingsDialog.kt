package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogBgmAiSettingsBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BgmAISettingsDialog : BaseDialogFragment(R.layout.dialog_bgm_ai_settings) {

    private val binding by viewBinding(DialogBgmAiSettingsBinding::bind)

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
    }

    private fun initData() = binding.run {
        switchAI.isChecked = AppConfig.bgmAIEnabled
        switchThinking.isChecked = AppConfig.bgmThinkingEnabled
        seekAIInterval.progress = charToProgress(AppConfig.bgmAICharInterval)
        tvAIIntervalValue.text = "${AppConfig.bgmAICharInterval}字"
    }

    private fun initView() = binding.run {
        switchAI.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.bgmAIEnabled = isChecked
        }

        // 思考模式：关闭后请求里带上关闭思考的字段（见 BgmAIService.callOpenAIApi）
        switchThinking.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.bgmThinkingEnabled = isChecked
        }

        btnAIProvider.setOnClickListener {
            BgmAIProviderDialog().show(parentFragmentManager, "bgmAIProvider")
        }

        btnAIPrompt.setOnClickListener {
            BgmAIPromptDialog().show(parentFragmentManager, "bgmAIPrompt")
        }

        btnAiEffectKeywordPrompt.setOnClickListener {
            editAiEffectPrompt(
                io.legado.app.service.AiEffectAnalyzer.PROMPT_KEYWORD,
                io.legado.app.service.AiEffectAnalyzer.KEYWORD_PROMPT
            )
        }

        btnAiEffectPickPrompt.setOnClickListener {
            editAiEffectPrompt(
                io.legado.app.service.AiEffectAnalyzer.PROMPT_PICK,
                io.legado.app.service.AiEffectAnalyzer.PICK_PROMPT
            )
        }

        seekAIInterval.setOnSeekBarChangeListener(object : SeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val chars = progressToChar(progress)
                    AppConfig.bgmAICharInterval = chars
                    tvAIIntervalValue.text = "${chars}字"
                }
            }
        })
    }

    /**
     * 编辑一条 AI 音效提示词。
     *
     * 与 BGM 选曲共用 `bgmAIPrompt` 表，靠 name 区分用途
     * （[AiEffectAnalyzer.PROMPT_KEYWORD] / [PROMPT_PICK]）。
     * 正常情况 App 启动时已写入，这里再兜一层：万一被删了，用内置默认重建一条，
     * 免得点进去是个空白框。
     *
     * 名称锁住不让改——它是运行时查表的键，改名会导致取不到、静默回退到内置默认。
     */
    private fun editAiEffectPrompt(name: String, builtinDefault: String) {
        execute {
            appDb.bgmAIPromptDao.getByName(name) ?: io.legado.app.data.entities.BgmAIPrompt(
                name = name,
                prompt = builtinDefault,
                isDefault = false
            ).also { appDb.bgmAIPromptDao.insert(it) }
        }.onSuccess(Dispatchers.Main) { prompt ->
            BgmAIPromptEditDialog.newInstance(
                prompt = prompt,
                onSaved = { },
                showAudioList = false,
                lockName = true
            ).show(parentFragmentManager, "aiEffectPrompt")
        }
    }

    private fun charToProgress(chars: Int): Int {
        return ((chars - 100) / 10).coerceIn(0, 90)
    }

    private fun progressToChar(progress: Int): Int {
        return (progress * 10 + 100).coerceIn(100, 1000)
    }
}
