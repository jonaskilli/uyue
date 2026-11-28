package io.legado.app.ui.soundeffect

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
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

private const val MAX_HIGHLIGHTS = 200
private val HIGHLIGHT_COLOR = Color.parseColor("#66FFB74D")
private val EFFECT_MARKER_REGEX = Regex("\\(([\\u4e00-\\u9fa5]*音效)\\)")

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

    /** 给测试文本加 Span 时避免再次触发 TextWatcher */
    private var applyingHighlight = false

    /** 从预览页带入的临时测试文本：只用于显示/测试，保存时不写回 sampleText。 */
    private val previewTestText: String? by lazy {
        intent.getStringExtra(EXTRA_PREVIEW_TEST_TEXT)
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
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!applyingHighlight) updateTestResult()
            }
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
        // 预览页带入的“规则执行前文本”优先显示，但 rule.sampleText 保持原值不被污染
        binding.etTestText.setText(previewTestText ?: rule.sampleText)
        binding.cbUseRegex.isChecked = rule.isRegex
        binding.cbEnabled.isChecked = rule.isEnabled
        binding.etGroup.setText(
            viewModel.groupNames.getOrNull(
                viewModel.groupIds.indexOf(viewModel.groupId)
            ).orEmpty()
        )
        updateTestResult()
        scrollToBottom()
    }

    /** 进入编辑页时直接滚到最底部（替换结果），不聚焦输入框、不弹出输入法。 */
    private fun scrollToBottom() {
        binding.scrollView.post {
            val child = binding.scrollView.getChildAt(0) ?: return@post
            // 不用 fullScroll：该容器禁用了 requestChildRectangleOnScreen，fullScroll 不生效
            binding.scrollView.scrollTo(0, child.height)
        }
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
        // 预览带入的测试文本只是临时演示用，不写回规则的 sampleText
        if (previewTestText == null) {
            rule.sampleText = binding.etTestText.text.toString()
        }
        return rule
    }

    private fun updateTestResult() {
        val testText = binding.etTestText.text.toString()
        if (testText.isEmpty()) {
            binding.etReplaceResult.setText("")
            clearTestHighlight()
            clearResultMarkerHighlight()
            return
        }
        // 预览只读界面当前值，不调用 buildRule()，避免把界面默认值写回 viewModel.rule
        val pattern = binding.etPattern.text.toString()
        val replacement = binding.etReplacement.text.toString()
        val isRegex = binding.cbUseRegex.isChecked

        val compiled = if (isRegex) {
            try {
                SoundEffectRuleManager.compilePattern(pattern)
            } catch (e: Exception) {
                clearTestHighlight()
                clearResultMarkerHighlight()
                binding.etReplaceResult.setText("正则无效：${e.localizedMessage}")
                return
            }
        } else {
            null
        }

        val outcome = try {
            replaceWithTrackedMarkers(testText, pattern, replacement, compiled)
        } catch (e: Exception) {
            ReplaceOutcome("正则无效：${e.localizedMessage}", emptyList())
        }
        binding.etReplaceResult.setText(outcome.text)

        if (outcome.text.startsWith("正则无效：")) {
            clearTestHighlight()
            clearResultMarkerHighlight()
        } else {
            applyTestHighlight(testText, pattern, compiled, isRegex)
            applyResultMarkerHighlight(outcome.newMarkerRanges)
        }
    }

    /**
     * 执行替换，同时记录每次替换中新插入文本里的音效标记位置。
     * 这样结果里只有“本次替换产生”的标记会加底色，测试文本里原有的标记不加。
     */
    private fun replaceWithTrackedMarkers(
        testText: String,
        pattern: String,
        replacement: String,
        compiled: Regex?
    ): ReplaceOutcome {
        if (pattern.isEmpty()) return ReplaceOutcome(testText, emptyList())
        val sb = StringBuilder()
        val ranges = ArrayList<IntRange>()
        var last = 0

        // 只把 replacement 模板里字面写出的音效标记计入底色；
        // $1 等捕获组从测试文本带回的旧标记不算“本次替换产生”。
        fun appendReplacement(match: MatchResult?) {
            var i = 0
            while (i < replacement.length) {
                val literal = EFFECT_MARKER_REGEX.find(replacement, i)
                if (literal == null || literal.range.first >= replacement.length) break
                // 先输出标记之前的模板片段（含 $捕获 展开）
                val prefix = replacement.substring(i, literal.range.first)
                sb.append(if (match != null) expandReplacement(match, prefix) else prefix)
                // 标记本身是模板字面写的，记录其在结果中的位置并加底色
                val markerStart = sb.length
                sb.append(literal.value)
                ranges.add(markerStart..(markerStart + literal.value.length - 1))
                i = literal.range.last + 1
            }
            val tail = replacement.substring(i)
            sb.append(if (match != null && tail.isNotEmpty()) expandReplacement(match, tail) else tail)
        }

        if (compiled != null) {
            compiled.findAll(testText).forEach { match ->
                if (match.range.first < last) return@forEach
                sb.append(testText, last, match.range.first)
                appendReplacement(match)
                last = match.range.last + 1
            }
        } else {
            var from = 0
            while (from <= testText.length - pattern.length) {
                val pos = testText.indexOf(pattern, from)
                if (pos < 0) break
                sb.append(testText, from, pos)
                appendReplacement(null)
                from = pos + pattern.length
            }
            last = from
        }
        sb.append(testText, last, testText.length)
        return ReplaceOutcome(sb.toString(), ranges)
    }

    /** 展开替换串里的 $0/$1..$9/${name}，与 Regex.replace 的捕获组语义一致。 */
    private fun expandReplacement(match: MatchResult, template: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            if (template[i] != '$') {
                sb.append(template[i++])
                continue
            }
            if (i + 1 >= template.length) {
                sb.append('$')
                i++
                continue
            }
            if (template[i + 1] == '$') {
                sb.append('$')
                i += 2
                continue
            }
            if (template[i + 1] == '{') {
                val end = template.indexOf('}', i + 2)
                if (end > i + 2) {
                    val name = template.substring(i + 2, end)
                    val value = match.groups[name]?.value.orEmpty()
                    sb.append(value)
                    i = end + 1
                    continue
                }
            }
            val c = template[i + 1]
            if (c.isDigit()) {
                sb.append(match.groupValues.getOrNull(c - '0').orEmpty())
            } else {
                sb.append('$').append(c)
            }
            i += 2
        }
        return sb.toString()
    }

    private data class ReplaceOutcome(
        val text: String,
        val newMarkerRanges: List<IntRange>
    )

    /** 在测试文本里给命中的原文加深色背景，只影响显示，不改变文本内容。 */
    private fun applyTestHighlight(
        testText: String,
        pattern: String,
        compiled: Regex?,
        isRegex: Boolean
    ) {
        clearTestHighlight()
        if (pattern.isEmpty()) return
        val editable = binding.etTestText.text as? Spannable ?: return
        val ranges = ArrayList<IntRange>()

        if (isRegex && compiled != null) {
            compiled.findAll(testText).take(MAX_HIGHLIGHTS).forEach { match ->
                if (match.range.first <= match.range.last) {
                    ranges.add(match.range)
                }
            }
        } else {
            var from = 0
            while (ranges.size < MAX_HIGHLIGHTS && from <= testText.length - pattern.length) {
                val pos = testText.indexOf(pattern, from)
                if (pos < 0) break
                ranges.add(pos..(pos + pattern.length - 1))
                from = pos + pattern.length
            }
        }

        applyingHighlight = true
        ranges.forEach { range ->
            if (range.first >= 0 && range.last < editable.length) {
                editable.setSpan(
                    MatchHighlightSpan(HIGHLIGHT_COLOR),
                    range.first,
                    range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        applyingHighlight = false
    }

    private fun clearTestHighlight() {
        val editable = binding.etTestText.text as? Spannable ?: return
        editable.getSpans(0, editable.length, MatchHighlightSpan::class.java)
            .forEach { editable.removeSpan(it) }
    }

    /** 只给本次替换新产生的 (xxx音效) 标记加背景色；测试文本里原有的标记不加。 */
    private fun applyResultMarkerHighlight(ranges: List<IntRange>) {
        val editable = binding.etReplaceResult.text as? Spannable ?: return
        clearResultMarkerHighlight()
        ranges.take(MAX_HIGHLIGHTS).forEach { range ->
            if (range.first >= 0 && range.last < editable.length) {
                editable.setSpan(
                    ResultMarkerSpan(HIGHLIGHT_COLOR),
                    range.first,
                    range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun clearResultMarkerHighlight() {
        val editable = binding.etReplaceResult.text as? Spannable ?: return
        editable.getSpans(0, editable.length, ResultMarkerSpan::class.java)
            .forEach { editable.removeSpan(it) }
    }

    private class MatchHighlightSpan(color: Int) : BackgroundColorSpan(color)
    private class ResultMarkerSpan(color: Int) : BackgroundColorSpan(color)

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
                            intent.getStringExtra(EXTRA_SOURCE_MODE)
                                ?.let { putExtra(EXTRA_SOURCE_MODE, it) }
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
        const val EXTRA_TARGET_MARKER = "extraTargetMarker"
        const val EXTRA_TARGET_MARKER_INDEX = "extraTargetMarkerIndex"
        const val EXTRA_SOURCE_MODE = "extraSourceMode"
        const val EXTRA_PREVIEW_TEST_TEXT = "extraPreviewTestText"

        fun startIntent(
            context: Context,
            rule: SoundEffectRule?,
            groupId: Long,
            groupIds: LongArray,
            groupNames: Array<String>,
            targetMarker: String? = null,
            targetMarkerIndex: Int = 0,
            sourceMode: String? = null,
            previewTestText: String? = null
        ): Intent = Intent(context, SoundEffectRuleEditActivity::class.java).apply {
            putExtra(EXTRA_RULE, rule)
            putExtra(EXTRA_GROUP_ID, groupId)
            putExtra(EXTRA_GROUP_IDS, groupIds)
            putExtra(EXTRA_GROUP_NAMES, groupNames)
            targetMarker?.let { putExtra(EXTRA_TARGET_MARKER, it) }
            putExtra(EXTRA_TARGET_MARKER_INDEX, targetMarkerIndex)
            sourceMode?.let { putExtra(EXTRA_SOURCE_MODE, it) }
            previewTestText?.let { putExtra(EXTRA_PREVIEW_TEST_TEXT, it) }
        }
    }
}
