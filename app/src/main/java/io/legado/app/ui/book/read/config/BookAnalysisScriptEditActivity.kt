package io.legado.app.ui.book.read.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.MenuItem
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.code.BaseCodeEditorActivity

/**
 * 全书分析专用脚本编辑页。
 *
 * 代码存在默认 SharedPreferences 的 [io.legado.app.constant.PreferKey.bookAnalysisScript]，
 * 不进入朗读脚本列表，也不会被普通朗读执行。
 */
class BookAnalysisScriptEditActivity : BaseCodeEditorActivity() {

    companion object {
        fun startIntent(context: Context): Intent =
            Intent(context, BookAnalysisScriptEditActivity::class.java)
    }

    override val extraMenuRes: Int = R.menu.book_analysis_script_edit

    override fun editorTitle(): String = "分析脚本"

    override fun foldStateKey(): String = "bookAnalysisScript"

    override fun saveAsFileName(): String = "book-analysis-script.js"

    override fun loadInitialCode(): String = AppConfig.bookAnalysisScript

    override fun onSaveClick(code: String) {
        AppConfig.bookAnalysisScript = code
        setResult(Activity.RESULT_OK)
        finishDirectly()
    }

    override fun onExtraMenuSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_restore_default -> {
                setCode(AppConfig.defaultBookAnalysisScript())
                true
            }
            else -> false
        }
    }
}
