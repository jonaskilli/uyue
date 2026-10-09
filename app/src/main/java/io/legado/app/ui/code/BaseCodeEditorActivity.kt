package io.legado.app.ui.code

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.EditorSearcher.SearchOptions
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityCodeEditorBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.code.config.ChangeThemeDialog
import io.legado.app.ui.code.config.SettingsDialog
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.imeHeight
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.setOnApplyWindowInsetsListenerCompat
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

/**
 * sora 代码编辑器的共用外壳：插件代码编辑器与朗读脚本编辑器共用。
 *
 * 统一提供：[CodeEditor]（JS 语法高亮）、搜索/替换/全部替换（正则）、
 * **函数级定位与整函数替换**、代码折叠（箭头 + 菜单 + 状态持久化）、
 * 撤销/重做、格式化、主题、自动换行、编辑器设置、另存为文件、未保存退出确认。
 *
 * 子类只需实现几个钩子：初始代码、标题、保存动作、可选的表头与额外菜单。
 */
abstract class BaseCodeEditorActivity :
    VMBaseActivity<ActivityCodeEditorBinding, CodeEditViewModel>(),
    KeyboardToolPop.CallBack, ChangeThemeDialog.CallBack, SettingsDialog.CallBack {

    override val binding by viewBinding(ActivityCodeEditorBinding::inflate)
    override val viewModel by viewModels<CodeEditViewModel>()

    protected val editor: CodeEditor by lazy { binding.editor }
    protected val editorSearcher: EditorSearcher by lazy { editor.searcher }
    protected val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }
    protected lateinit var foldingManager: CodeFoldingManager
    protected lateinit var functionMatcher: FunctionMatcher

    /** 打开时载入的代码，用于判断「有改动未保存」 */
    protected var initialCode: String = ""

    // 函数匹配状态（搜索框粘贴多行代码时，按第一行匹配同名函数）
    private var functionMatches: List<FunctionMatcher.FunctionBlock> = emptyList()
    private var currentMatchIndex = -1

    private var options: SearchOptions = SearchOptions(false, true)
    private var isRegex = true
    private var findText = ""
    private var replaceText = ""

    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/javascript")
    ) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use {
                it.write(editor.text.toString().toByteArray())
            }
            toastOnUi("已导出: ${saveAsFileName()}")
        }.onFailure { toastOnUi("导出失败：${it.message}") }
    }

    // ---------------------------------------------------------------- 子类钩子

    /** 打开时载入的代码。异步加载的子类可返回空串，之后用 [setCode] 填充 */
    protected open fun loadInitialCode(): String = ""

    /** 标题栏文字 */
    protected abstract fun editorTitle(): String

    /** 点保存。返回 true 表示已处理（基类据此决定是否直接关闭由子类自己控制） */
    protected abstract fun onSaveClick(code: String)

    /** 子类额外菜单资源，为 0 表示没有 */
    protected open val extraMenuRes: Int = 0

    /** 子类额外菜单点击，未处理返回 false */
    protected open fun onExtraMenuSelected(item: MenuItem): Boolean = false

    /** 子类表头（如朗读脚本的名称输入框），返回 null 表示没有 */
    protected open fun createHeaderView(): View? = null

    /** 另存为的默认文件名 */
    protected open fun saveAsFileName(): String = "code.js"

    /** 折叠状态持久化 key，返回 null 表示不持久化 */
    protected open fun foldStateKey(): String? = null

    /** 编辑器就绪（代码已载入）后的子类初始化 */
    protected open fun onEditorReady() = Unit

    // ---------------------------------------------------------------- 生命周期

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        binding.root.setOnApplyWindowInsetsListenerCompat { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
        createHeaderView()?.let {
            binding.headerContainer.addView(it)
            binding.headerContainer.visibility = View.VISIBLE
        }

        editor.colorScheme = TextMateColorScheme2.create(ThemeRegistry.getInstance())
        foldingManager = CodeFoldingManager(editor)
        functionMatcher = FunctionMatcher(editor)
        binding.foldIndicator.editor = editor
        binding.foldIndicator.foldingManager = foldingManager
        binding.foldIndicator.onFoldChanged = { persistFoldStates() }

        upEdit(AppConfig.editFontScale, null, AppConfig.editAutoWrap)
        viewModel.language?.let { editor.setEditorLanguage(it) }
        editor.isUndoEnabled = true
        setCode(loadInitialCode())
        binding.titleBar.title = editorTitle()
        editor.requestFocus()
        foldStateKey()?.let { key -> foldingManager.scheduleRestore(loadFoldStates(key)) }
        onEditorReady()
    }

    /** 载入代码并把它记为「未修改」的基线 */
    protected fun setCode(code: String) {
        initialCode = code
        editor.setText(code)
    }

    protected fun currentCode(): String = editor.text.toString()

    override fun onDestroy() {
        runCatching { editorSearcher.stopSearch() }
        softKeyboardTool.dismiss()
        editor.release()
        super.onDestroy()
    }

    override fun finish() {
        if (currentCode() == initialCode) {
            super.finish()
            return
        }
        alert(getString(R.string.plugin_editor_exit)) {
            setMessage(getString(R.string.plugin_editor_exit_no_save))
            positiveButton(R.string.yes)
            negativeButton(R.string.no) { finishDirectly() }
        }
    }

    /** 跳过未保存确认直接关闭（Kotlin 不允许在 lambda 里用 super） */
    protected fun finishDirectly() {
        super.finish()
    }

    // ---------------------------------------------------------------- 搜索 / 函数匹配

    protected fun openSearchBar() {
        if (binding.searchGroup.isVisible) return
        binding.switchRegex.setOnCheckedChangeListener { _, checked ->
            isRegex = checked
            options = SearchOptions(!isRegex, isRegex)
            searchTxt(binding.etFind.text.toString())
        }
        binding.switchRegex.isChecked = isRegex
        val receiptSearch =
            editor.subscribeEvent(PublishSearchResultEvent::class.java) { event, _ ->
                if (event.editor == editor) updateSearchResults()
            }
        val receiptChange = editor.subscribeEvent(SelectionChangeEvent::class.java) { event, _ ->
            if (event.cause == SelectionChangeEvent.CAUSE_SEARCH) updateSearchResults()
        }
        binding.searchGroup.visibility = View.VISIBLE
        binding.btnCloseFind.setOnClickListener {
            closeSearchBar()
            receiptSearch.unsubscribe()
            receiptChange.unsubscribe()
        }
        binding.etFind.addTextChangedListener { text ->
            findText = text?.toString().orEmpty()
            if (isFunctionMode) {
                editorSearcher.stopSearch()
                functionMatches = emptyList()
                currentMatchIndex = -1
                updateFunctionInfo()
            } else if (findText.isNotEmpty()) {
                searchTxt(findText)
            } else {
                editorSearcher.stopSearch()
                editor.invalidate()
            }
        }
        binding.etReplace.addTextChangedListener { text ->
            replaceText = text?.toString().orEmpty()
        }
        binding.btnPrevious.setOnClickListener { gotoPrevious() }
        binding.btnNext.setOnClickListener { gotoNext() }
        binding.btnReplace.setOnClickListener {
            if (isFunctionMode) {
                updateFunctionInfo()
                return@setOnClickListener
            }
            if (binding.replaceGroup.isGone) {
                binding.replaceGroup.visibility = View.VISIBLE
                binding.btnReplaceAll.isEnabled = true
                binding.etReplace.requestFocus()
            } else if (editorSearcher.hasQuery()) {
                editorSearcher.replaceCurrentMatch(replaceText)
            }
        }
        binding.btnCloseReplace.setOnClickListener {
            binding.replaceGroup.visibility = View.GONE
            binding.btnReplaceAll.isEnabled = false
            binding.etFind.requestFocus()
        }
        binding.btnReplaceAll.setOnClickListener {
            if (editorSearcher.hasQuery()) editorSearcher.replaceAll(replaceText)
        }
        binding.btnReplaceFunction.setOnClickListener { replaceCurrentFunction() }
        binding.etFind.setText(findText)
        binding.etReplace.setText(replaceText)
        binding.etFind.requestFocus()
        updateSearchResults()
        updateFunctionInfo()
    }

    protected fun closeSearchBar() {
        binding.searchGroup.visibility = View.GONE
        editorSearcher.stopSearch()
        functionMatches = emptyList()
        currentMatchIndex = -1
        editor.requestFocus()
        editor.invalidate()
    }

    private val isFunctionMode: Boolean get() = findText.contains("\n")

    private fun searchTxt(txt: String) {
        if (txt.isEmpty() || isFunctionMode) return
        runCatching { editorSearcher.search(txt, options) }
            .onFailure { editorSearcher.stopSearch() }
        editor.invalidate()
    }

    private fun gotoNext() {
        if (isFunctionMode) {
            if (functionMatches.isEmpty()) {
                runFunctionSearch()
                return
            }
            if (currentMatchIndex < functionMatches.size - 1) {
                currentMatchIndex++
                focusMatch(currentMatchIndex)
            }
            return
        }
        if (editorSearcher.hasQuery()) runCatching { editorSearcher.gotoNext() }
    }

    private fun gotoPrevious() {
        if (isFunctionMode) {
            if (functionMatches.isNotEmpty() && currentMatchIndex > 0) {
                currentMatchIndex--
                focusMatch(currentMatchIndex)
            }
            return
        }
        if (editorSearcher.hasQuery()) runCatching { editorSearcher.gotoPrevious() }
    }

    private fun runFunctionSearch() {
        val signature = functionMatcher.extractFunctionSignature(findText)
        if (signature.isBlank()) {
            toastOnUi(R.string.function_not_found)
            return
        }
        functionMatches = functionMatcher.findFunctions(signature)
        currentMatchIndex = if (functionMatches.isEmpty()) -1 else 0
        if (functionMatches.isEmpty()) toastOnUi(R.string.function_not_found)
        else focusMatch(0)
        updateFunctionInfo()
    }

    private fun focusMatch(index: Int) {
        val block = functionMatches.getOrNull(index) ?: return
        editor.setSelection(block.startLine, 0, true)
        runCatching { editor.ensurePositionVisible(block.startLine, 0) }
        updateFunctionInfo()
    }

    /** 用搜索框里的代码整段替换当前匹配到的函数 */
    private fun replaceCurrentFunction() {
        val block = functionMatches.getOrNull(currentMatchIndex)
        if (block == null) {
            toastOnUi(R.string.function_not_found)
            return
        }
        val signature = functionMatcher.extractFunctionSignature(findText)
        // 若该函数处于折叠状态，先展开，否则替换范围会算错
        val wasFolded = foldingManager.isFolded(block.startLine)
        if (wasFolded) foldingManager.unfoldAt(block.startLine)
        // 展开后行号已变化，必须重新查找当前函数的真实范围再替换
        val refreshed = functionMatcher.findFunctions(signature)
        val target = refreshed.firstOrNull { it.startLine == block.startLine }
            ?: refreshed.getOrNull(currentMatchIndex) ?: block
        functionMatcher.replaceFunction(target.startLine, target.endLine, findText)
        functionMatches = functionMatcher.findFunctions(signature)
        currentMatchIndex = 0
        if (functionMatches.isNotEmpty()) {
            val newBlock = functionMatches[0]
            editor.setSelection(newBlock.startLine, 0, true)
            runCatching { editor.ensurePositionVisible(newBlock.startLine, 0) }
            if (wasFolded) foldingManager.fold(newBlock.startLine, newBlock.endLine)
        }
        refreshFoldUi()
        toastOnUi(R.string.function_replaced)
        updateFunctionInfo()
    }

    private fun updateSearchResults() {
        if (editorSearcher.hasQuery()) {
            val total = editorSearcher.matchedPositionCount
            val current = editorSearcher.currentMatchedPositionIndex + 1
            binding.tvSearchResult.text = "${if (current > 0) "$current/" else ""}$total"
        }
    }

    private fun updateFunctionInfo() {
        val show = isFunctionMode && functionMatches.isNotEmpty() && currentMatchIndex >= 0
        binding.functionGroup.isVisible = show
        binding.btnReplaceAll.isEnabled = !isFunctionMode && editorSearcher.hasQuery()
        if (!show) return
        val block = functionMatches[currentMatchIndex]
        binding.tvFunctionInfo.text = getString(
            R.string.function_match_count,
            currentMatchIndex + 1,
            functionMatches.size,
            block.startLine + 1
        )
    }

    // ---------------------------------------------------------------- 折叠

    private fun toggleFoldAtCursor() {
        val line = editor.cursor?.leftLine ?: return
        if (foldingManager.isFolded(line)) {
            foldingManager.unfoldAt(line)
        } else {
            val block = foldingManager.findBlockAtLine(line)
            if (block == null) {
                toastOnUi("光标所在行没有可折叠的代码块")
                return
            }
            foldingManager.fold(block.first, block.last)
        }
        refreshFoldUi()
    }

    private fun foldAll() {
        lifecycleScope.launch {
            foldingManager.foldAllProgressive()
            refreshFoldUi()
        }
    }

    private fun unfoldAll() {
        lifecycleScope.launch {
            foldingManager.unfoldAllProgressive()
            refreshFoldUi()
        }
    }

    /** 折叠状态变化后重绘编辑器与行号区箭头，并持久化折叠状态 */
    protected fun refreshFoldUi() {
        editor.invalidate()
        binding.foldIndicator.invalidate()
        persistFoldStates()
    }

    /** 保存前调用：展开所有折叠，确保拿到的是完整原始代码 */
    protected fun unfoldAllBeforeSave() {
        persistFoldStates()
        foldingManager.unfoldAll()
    }

    private fun persistFoldStates() {
        val key = foldStateKey() ?: return
        foldPrefs().edit()
            .putString(key, FoldMark.listToJson(foldingManager.exportFoldMarks()))
            .apply()
    }

    private fun loadFoldStates(key: String): List<FoldMark> =
        FoldMark.listFromJson(foldPrefs().getString(key, "").orEmpty())

    private fun foldPrefs() = getSharedPreferences(FOLD_PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- 其它共用动作

    private fun copySelection() {
        val cursor = editor.cursor
        if (cursor == null || !cursor.isSelected()) {
            toastOnUi(R.string.please_select_code)
            return
        }
        val text = foldingManager.getUnfoldedSelectionText(
            cursor.leftLine, cursor.leftColumn, cursor.rightLine, cursor.rightColumn
        )
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("code", text))
        toastOnUi(getString(R.string.copied_lines, text.lineSequence().count()))
    }

    // ---------------------------------------------------------------- 菜单

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.code_editor, menu)
        if (extraMenuRes != 0) menuInflater.inflate(extraMenuRes, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_auto_wrap)?.isChecked = AppConfig.editAutoWrap
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> onSaveClick(currentCode())
            R.id.menu_search -> openSearchBar()
            R.id.menu_undo -> editor.undo()
            R.id.menu_redo -> editor.redo()
            R.id.menu_fold_current -> toggleFoldAtCursor()
            R.id.menu_fold_all -> foldAll()
            R.id.menu_unfold_all -> unfoldAll()
            R.id.menu_copy_code -> copySelection()
            R.id.menu_format_code -> viewModel.formatCode(editor)
            R.id.menu_save_as_file -> saveFileLauncher.launch(saveAsFileName())
            R.id.menu_change_theme -> showDialogFragment(ChangeThemeDialog())
            R.id.menu_config_settings -> showDialogFragment(SettingsDialog(this, this))
            R.id.menu_auto_wrap -> {
                item.isChecked = !AppConfig.editAutoWrap
                upEdit(autoWarp = !AppConfig.editAutoWrap)
                putPrefBoolean(PreferKey.editAutoWrap, !AppConfig.editAutoWrap)
            }
            else -> if (!onExtraMenuSelected(item)) {
                return super.onCompatOptionsItemSelected(item)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 主题 / 编辑器设置

    private val isDark get() = AppConfig.editTemeAuto && ThemeConfig.isDarkTheme()
    private var themeIndex = -1

    override fun initTheme() {
        super.initTheme()
        if (!isSoraInitialized) {
            viewModel.initSora()
            isSoraInitialized = true
        }
        // 语言在这里就准备好，upTheme() 里切换主题后需要重新设置语言才不会丢高亮
        viewModel.languageName = "source.js"
        viewModel.language = TextMateLanguage.create("source.js", AppConfig.editAutoComplete)
        val index = if (isDark) AppConfig.editThemeDark else AppConfig.editTheme
        upTheme(index)
        themeIndex = index
    }

    override fun upTheme(index: Int) {
        if (themeIndex != index) {
            viewModel.loadTextMateThemes(index)
            // 语言对象可能还没创建（首次进入时），此时不要传 null 给 sora
            viewModel.language?.let { editor.setEditorLanguage(it) }
            themeIndex = index
        }
    }

    override fun upEdit(fontSize: Int?, autoComplete: Boolean?, autoWarp: Boolean?, editNonPrintable: Int?) {
        if (fontSize != null) editor.setTextSize(fontSize.toFloat())
        if (autoComplete != null) {
            viewModel.language?.isAutoCompleteEnabled = autoComplete
            editor.setEditorLanguage(viewModel.language)
        }
        if (autoWarp != null) editor.isWordwrap = autoWarp
        if (editNonPrintable != null) editor.nonPrintablePaintingFlags = editNonPrintable
    }

    // ---------------------------------------------------------------- 键盘工具条

    override fun sendText(text: String) {
        if (text.isEmpty()) return
        val view = window.decorView.findFocus()
        if (view is TextInputEditText) {
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
        } else {
            editor.insertText(text, text.length)
        }
    }

    override fun onUndoClicked() {
        editor.undo()
    }

    override fun onRedoClicked() {
        editor.redo()
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(SelectItem("JS教程", "jsHelp"))
    }

    override fun onHelpActionSelect(action: String) {
        if (action == "jsHelp") showHelp("jsHelp")
    }

    companion object {
        private const val FOLD_PREFS_NAME = "code_editor_fold"

        /** sora 的语法表只需要初始化一次 */
        private var isSoraInitialized = false
    }
}
