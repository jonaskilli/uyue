package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogSoundEffectPreviewBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.service.AiEffectAnalyzer
import io.legado.app.service.AiEffectStore
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.soundeffect.SoundEffectPlayer
import io.legado.app.ui.soundeffect.SoundEffectRuleManager
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 阅读页长按「音效」后的正文预览。
 *
 * 正则音效来自规则替换后的 `(xxx音效)` 标记；
 * AI 音效没有正文标记，这里按 AiEffectStore 的记录虚拟插入 `[xxx]` 标记，使用固定底色。
 */
class SoundEffectPreviewDialog : BaseDialogFragment(R.layout.dialog_sound_effect_preview) {

    private val binding by viewBinding(DialogSoundEffectPreviewBinding::bind)

    private val originalText: String get() = requireArguments().getString(EXTRA_TEXT).orEmpty()
    private val prevText: String get() = requireArguments().getString(EXTRA_PREV_TEXT).orEmpty()
    private val mode: String get() = requireArguments().getString(EXTRA_MODE).orEmpty()
    private val bookUrl: String get() = requireArguments().getString(EXTRA_BOOK_URL).orEmpty()
    private val chapterIndex: Int get() = requireArguments().getInt(EXTRA_CHAPTER_INDEX, -1)
    private val paraIndex: Int get() = requireArguments().getInt(EXTRA_PARA_INDEX, -1)

    /** 点击“音效规则管理”时由 Activity 打开规则管理界面。 */
    var onManageRules: (() -> Unit)? = null

    var onEditRule: ((
        effectName: String,
        marker: String,
        markerIndex: Int,
        isAiEffect: Boolean,
        effectPath: String,
        sourceMode: String,
        previewTestText: String?
    ) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.92f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnManageRules.setOnClickListener { onManageRules?.invoke() }
        binding.btnRefresh.setOnClickListener { refresh() }
        refresh()
    }

    /** 重新按当前规则文件计算并渲染；规则编辑保存后会清缓存，这里即可看到最新结果。 */
    private fun refresh() {
        if (originalText.isBlank()) {
            binding.tvPreview.text = "没有取到段落文字"
            return
        }

        binding.tvPreview.text = "正在应用音效规则…"
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // 确保读到规则文件的最新内容（编辑保存后可能已清过，这里再清一次保证刷新有效）
            SoundEffectRuleManager.invalidateRuleCaches()
            val previewResult = runCatching {
                BaseReadAloudService.extractSoundEffectsForPreview(originalText, prevText)
            }.getOrNull()

            val aiPicks = if (AppConfig.aiEffectEnabled && bookUrl.isNotBlank() && chapterIndex >= 0 && paraIndex >= 0) {
                runCatching {
                    AiEffectStore.loadParagraph(bookUrl, chapterIndex, paraIndex)
                }.getOrNull()
            } else {
                null
            }

            val cleanText = previewResult?.first ?: originalText
            // 本段真正命中的替换规则名（复用朗读服务缓存，不重复读规则文件）
            val ruleNames = previewResult?.third.orEmpty().map { hit ->
                RuleNameItem(
                    name = hit.name,
                    markerName = hit.marker,
                    mode = hit.sourceMode,
                    inputText = hit.inputText
                )
            }
            val items = ArrayList<PreviewEffect>()
            previewResult?.second?.forEach { effect ->
                items.add(
                    PreviewEffect(
                        name = effect.name,
                        marker = "(${effect.name})",
                        charOffset = effect.charOffset,
                        isAiEffect = false,
                        path = effect.fileName
                    )
                )
            }
            aiPicks?.forEach { pick ->
                if (pick.name.isBlank()) return@forEach
                val ratio = AiEffectAnalyzer.ratioOfPos(originalText, pick.pos, pick.at)
                val offset = (ratio * cleanText.length).roundToInt()
                    .coerceIn(0, cleanText.length)
                items.add(
                    PreviewEffect(
                        name = pick.name,
                        marker = "[${pick.name}]",
                        charOffset = offset,
                        isAiEffect = true,
                        path = pick.path
                    )
                )
            }
            items.sortBy { it.charOffset }

            withContext(Dispatchers.Main) {
                if (previewResult == null && aiPicks.isNullOrEmpty()) {
                    binding.tvPreview.text = "音效规则读取失败\n\n$originalText"
                } else {
                    render(cleanText, items, ruleNames)
                }
            }
        }
    }

    /** 规则名 → 颜色，保证上方标记与底部名字颜色一致。每次渲染重建。 */
    private val nameColors = LinkedHashMap<String, Int>()

    private fun render(cleanText: String, effects: List<PreviewEffect>, ruleNames: List<RuleNameItem>) {
        nameColors.clear()
        binding.flowContainer.removeAllViews()
        val textColor = requireContext().getCompatColor(R.color.primaryText)
        if (effects.isEmpty()) {
            binding.tvPreview.text = if (ruleNames.isEmpty()) {
                "当前段落没有匹配到音效，也没有替换规则生效。\n\n$cleanText"
            } else {
                "没有生成音效标记，但本段有 ${ruleNames.size} 条替换规则生效。\n\n$cleanText"
            }
            renderNameChips(ruleNames, effects, textColor)
            return
        }

        val builder = SpannableStringBuilder(cleanText)
        var shift = 0
        effects.forEachIndexed { index, effect ->
            val pos = (effect.charOffset + shift).coerceIn(0, builder.length)
            builder.insert(pos, effect.marker)

            val occurrenceIndex = effects.take(index).count { it.name == effect.name }
            val backgroundColor = if (effect.isAiEffect) {
                AI_TAG_COLOR
            } else {
                nameColors.getOrPut(effect.name) {
                    TAG_COLORS[nameColors.size % TAG_COLORS.size]
                }
            }

            val bgSpan = BackgroundColorSpan(backgroundColor)
            val clickSpan = EffectClickSpan(
                effectName = effect.name,
                marker = effect.marker,
                occurrenceIndex = occurrenceIndex,
                paragraphIndex = index,
                isAiEffect = effect.isAiEffect,
                effectPath = effect.path,
                textColor = textColor,
                onClick = { name, mark, markIndex, isAi, path ->
                    openEditor(name, mark, markIndex, isAi, path)
                }
            )
            builder.setSpan(bgSpan, pos, pos + effect.marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(clickSpan, pos, pos + effect.marker.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            shift += effect.marker.length
        }

        binding.tvPreview.apply {
            setText(builder, TextView.BufferType.SPANNABLE)
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setTextIsSelectable(false)
        }
        renderNameChips(ruleNames, effects, textColor)
    }

    /** 底部显示当前模式参与替换的规则名；含音效标记的与上方标记同色，点击可编辑规则。 */
    private fun renderNameChips(
        ruleNames: List<RuleNameItem>,
        effects: List<PreviewEffect>,
        textColor: Int
    ) {
        val density = resources.displayMetrics.density
        ruleNames.forEachIndexed { index, rule ->
            val markerName = rule.markerName.ifBlank { null }
            val modeLabel = when (rule.mode) {
                "all" -> "全部音效"
                "normal" -> "普通音效"
                "transition" -> "衔接音效"
                else -> ""
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    setColor(
                        if (markerName != null) nameColors[markerName] ?: NEUTRAL_COLOR
                        else NEUTRAL_COLOR
                    )
                    cornerRadius = 8f * density
                }
                setPadding((8 * density).toInt(), (6 * density).toInt(),
                    (8 * density).toInt(), (6 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 0, (4 * density).toInt()) }
            }
            val label = TextView(requireContext()).apply {
                text = if (modeLabel.isEmpty()) "${index + 1}. ${rule.name}"
                       else "${index + 1}. [${modeLabel}] ${rule.name}"
                setTextColor(textColor)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val marker = if (markerName != null) "($markerName)" else rule.name
                    openEditor(rule.name, marker, 0, false, "", rule.mode, rule.inputText)
                }
            }
            row.addView(label)
            if (markerName != null) {
                row.addView(TextView(requireContext()).apply {
                    text = "试听"
                    setTextColor(textColor)
                    textSize = 12f
                    setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
                    setOnClickListener {
                        SoundEffectPlayer.play("($markerName)") { error ->
                            error?.let { requireContext().toastOnUi(it) }
                        }
                    }
                })
            }
            binding.flowContainer.addView(row)
        }
    }

    private fun openEditor(
        effectName: String,
        marker: String,
        occurrenceIndex: Int,
        isAiEffect: Boolean,
        effectPath: String,
        sourceMode: String = "",
        previewTestText: String? = null
    ) {
        AppLog.put(
            "[音效预览] 打开编辑 name=$effectName marker=$marker " +
                "occurrence=$occurrenceIndex ai=$isAiEffect path=$effectPath mode=$sourceMode"
        )
        // 不关闭预览弹窗：进入编辑页后返回，预览仍保持显示，方便继续查看/点击其它标记
        onEditRule?.invoke(
            effectName, marker, occurrenceIndex, isAiEffect, effectPath, sourceMode, previewTestText
        )
    }

    private data class PreviewEffect(
        val name: String,
        val marker: String,
        val charOffset: Int,
        val isAiEffect: Boolean,
        val path: String
    )

    private data class RuleNameItem(
        val name: String,
        val markerName: String,
        val mode: String,
        val inputText: String
    )

    private class EffectClickSpan(
        private val effectName: String,
        private val marker: String,
        private val occurrenceIndex: Int,
        private val paragraphIndex: Int,
        private val isAiEffect: Boolean,
        private val effectPath: String,
        private val textColor: Int,
        private val onClick: (String, String, Int, Boolean, String) -> Unit
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            AppLog.put(
                "[音效预览] 点击标记 paragraphIndex=$paragraphIndex " +
                    "occurrence=$occurrenceIndex ai=$isAiEffect name=$effectName marker=$marker"
            )
            onClick(effectName, marker, occurrenceIndex, isAiEffect, effectPath)
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.isUnderlineText = false
            ds.color = textColor
        }
    }

    companion object {
        private const val EXTRA_TEXT = "extraText"
        private const val EXTRA_PREV_TEXT = "extraPrevText"
        private const val EXTRA_MODE = "extraMode"
        private const val EXTRA_BOOK_URL = "extraBookUrl"
        private const val EXTRA_CHAPTER_INDEX = "extraChapterIndex"
        private const val EXTRA_PARA_INDEX = "extraParaIndex"

        private val TAG_COLORS = intArrayOf(
            Color.parseColor("#E3F2FD"),
            Color.parseColor("#E8F5E9"),
            Color.parseColor("#FFF3E0"),
            Color.parseColor("#F3E5F5"),
            Color.parseColor("#E0F7FA"),
            Color.parseColor("#FBE9E7")
        )
        private val AI_TAG_COLOR = Color.parseColor("#D9C7FF")
        private val NEUTRAL_COLOR = Color.parseColor("#E0E0E0")

        fun create(
            text: String,
            prevText: String,
            mode: String,
            bookUrl: String,
            chapterIndex: Int,
            paraIndex: Int
        ): SoundEffectPreviewDialog = SoundEffectPreviewDialog().apply {
            arguments = Bundle().apply {
                putString(EXTRA_TEXT, text)
                putString(EXTRA_PREV_TEXT, prevText)
                putString(EXTRA_MODE, mode)
                putString(EXTRA_BOOK_URL, bookUrl)
                putInt(EXTRA_CHAPTER_INDEX, chapterIndex)
                putInt(EXTRA_PARA_INDEX, paraIndex)
            }
        }
    }
}
