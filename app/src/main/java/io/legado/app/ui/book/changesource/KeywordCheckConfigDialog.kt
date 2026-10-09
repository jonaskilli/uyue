package io.legado.app.ui.book.changesource

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogKeywordCheckConfigBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 关键词校验配置对话框。
 *
 * 输入：校验章节名（用于在目录中定位章节，从阅读界面进入时默认填充当前章节标题）、
 * 必含词、禁词（多词用 | 分隔，同正则表达式的习惯）。
 * 点保存后回调 [onConfirm]，由换源界面重新搜索并只保留校验通过的书源。
 * 输入内容不持久化，仅本次使用。
 */
class KeywordCheckConfigDialog : BaseDialogFragment(R.layout.dialog_keyword_check_config),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogKeywordCheckConfigBinding::bind)

    /** 校验章节名输入框的默认填充值（一般为当前阅读章节的标题） */
    var defaultChapterKey: String? = null

    /** 确定回调：(校验章节名, 必含词列表, 禁词列表) */
    var onConfirm: ((chapterKey: String, mustHave: List<String>, forbidden: List<String>) -> Unit)? =
        null

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.inflateMenu(R.menu.save)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        if (binding.tvChapterKey.text.isNullOrBlank()) {
            binding.tvChapterKey.setText(defaultChapterKey)
        }
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_save -> {
                val chapterKey = binding.tvChapterKey.text?.toString()?.trim() ?: ""
                if (chapterKey.isEmpty()) {
                    context?.toastOnUi(getString(R.string.cannot_empty))
                    return true
                }
                onConfirm?.invoke(
                    chapterKey,
                    splitWords(binding.tvMustHave.text?.toString()),
                    splitWords(binding.tvForbidden.text?.toString())
                )
                dismissAllowingStateLoss()
            }
        }
        return true
    }

    private fun splitWords(text: String?): List<String> {
        return text?.split("|", "｜")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

}
