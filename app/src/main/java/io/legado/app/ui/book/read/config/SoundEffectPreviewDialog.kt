package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.View
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
import io.legado.app.utils.getCompatColor
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

    var onEditRule: ((
        effectName: String,
        marker: String,
        markerIndex: Int,
        isAiEffect: Boolean,
        effectPath: String
    ) -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.92f, 0.78f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnClose.setOnClickListener { dismissAllowingStateLoss() }
        if (originalText.isBlank()) {
            binding.tvPreview.text = "没有取到段落文字"
            return
        }

        binding.tvPreview.text = "正在应用音效规则…"
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val regexResult = runCatching {
                BaseReadAloudService.extractSoundEffectsForMerge(originalText, prevText)
            }.getOrNull()

            val aiPicks = if (AppConfig.aiEffectEnabled && bookUrl.isNotBlank() && chapterIndex >= 0 && paraIndex >= 0) {
                runCatching {
                    AiEffectStore.loadParagraph(bookUrl, chapterIndex, paraIndex)
                }.getOrNull()
            } else {
                null
            }

            val cleanText = regexResult?.first ?: originalText
            val items = ArrayList<PreviewEffect>()
            regexResult?.second?.forEach { effect ->
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
                if (regexResult == null && aiPicks.isNullOrEmpty()) {
                    binding.tvPreview.text = "音效规则读取失败\n\n$originalText"
                } else {
                    render(cleanText, items)
                }
            }
        }
    }

    private fun render(cleanText: String, effects: List<PreviewEffect>) {
        if (effects.isEmpty()) {
            binding.tvPreview.text = "当前段落没有匹配到音效。\n\n$originalText"
            return
        }

        val builder = SpannableStringBuilder(cleanText)
        val textColor = requireContext().getCompatColor(R.color.primaryText)
        var shift = 0
        var regexColorIndex = 0
        effects.forEachIndexed { index, effect ->
            val pos = (effect.charOffset + shift).coerceIn(0, builder.length)
            builder.insert(pos, effect.marker)

            val occurrenceIndex = effects.take(index).count { it.name == effect.name }
            val backgroundColor = if (effect.isAiEffect) {
                AI_TAG_COLOR
            } else {
                TAG_COLORS[regexColorIndex % TAG_COLORS.size].also { regexColorIndex++ }
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
    }

    private fun openEditor(
        effectName: String,
        marker: String,
        occurrenceIndex: Int,
        isAiEffect: Boolean,
        effectPath: String
    ) {
        AppLog.put(
            "[音效预览] 打开编辑 name=$effectName marker=$marker " +
                "occurrence=$occurrenceIndex ai=$isAiEffect path=$effectPath"
        )
        onEditRule?.invoke(effectName, marker, occurrenceIndex, isAiEffect, effectPath)
        dismissAllowingStateLoss()
    }

    private data class PreviewEffect(
        val name: String,
        val marker: String,
        val charOffset: Int,
        val isAiEffect: Boolean,
        val path: String
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
