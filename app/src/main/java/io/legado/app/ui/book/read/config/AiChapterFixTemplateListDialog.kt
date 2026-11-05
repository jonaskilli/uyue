package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.dao.AiChapterFixTemplateDao
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.databinding.DialogAiChapterFixTemplateListBinding
import io.legado.app.service.AiChapterFixService
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 章节处理模板列表：单选模板用于编辑，开关用于把模板绑定到当前书。
 *
 * 对齐 AI 生图 [AiImageTemplateListDialog] 的职责划分：
 * 选择/新建/复制/重命名只改变「编辑目标」，绑定/解绑只改变「当前书绑定的模板」。
 * 变更通过 FragmentResult 通知主弹窗，Fragment 重建后回调不会丢失；
 * 绑定变化只刷新主弹窗的提示，不会把正在编辑的模板强行切走。
 */
class AiChapterFixTemplateListDialog :
    BaseDialogFragment(R.layout.dialog_ai_chapter_fix_template_list) {

    private val binding by viewBinding(DialogAiChapterFixTemplateListBinding::bind)

    private var currentEditId: Long = 0
    private var bookName: String = ""
    private var bookUrl: String = ""
    private var hasBook: Boolean = false
    private var loadVersion = 0

    companion object {
        const val TAG = "aiChapterFixTemplateList"

        const val RESULT_KEY = "aiChapterFixTemplateResult"
        const val RESULT_ACTION = "action"
        const val RESULT_TEMPLATE_ID = "templateId"

        const val ACTION_SELECT = "select"
        const val ACTION_RENAMED = "renamed"
        const val ACTION_DELETED = "deleted"
        const val ACTION_BIND_CHANGED = "bindChanged"

        private const val ARG_CURRENT_ID = "current_id"
        private const val ARG_BOOK_NAME = "book_name"
        private const val ARG_BOOK_URL = "book_url"

        fun newInstance(
            currentEditId: Long,
            bookName: String,
            bookUrl: String
        ): AiChapterFixTemplateListDialog {
            return AiChapterFixTemplateListDialog().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CURRENT_ID, currentEditId)
                    putString(ARG_BOOK_NAME, bookName)
                    putString(ARG_BOOK_URL, bookUrl)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            currentEditId = it.getLong(ARG_CURRENT_ID, 0)
            bookName = it.getString(ARG_BOOK_NAME).orEmpty()
            bookUrl = it.getString(ARG_BOOK_URL).orEmpty()
        }
        hasBook = bookName.isNotBlank() || bookUrl.isNotBlank()
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.btnAdd.setOnClickListener { showNewTemplateDialog() }
        loadTemplates()
    }

    private fun loadTemplates() {
        val request = ++loadVersion
        lifecycleScope.launch(Dispatchers.IO) {
            val dao = appDb.aiChapterFixTemplateDao
            val list = dao.getAllList()
            val boundId = if (hasBook) {
                AiChapterFixService.getBoundTemplateId(bookName, bookUrl)
            } else {
                0L
            }
            withContext(Dispatchers.Main) {
                if (request != loadVersion || !isAdded || view == null) return@withContext
                val adapter = AiChapterFixTemplateListAdapter(
                    selectedId = currentEditId,
                    boundId = boundId,
                    bindVisible = hasBook,
                    onSelect = { template -> selectTemplate(template) },
                    onMore = { template, anchor -> showTemplateMenu(anchor, template) },
                    onBindToggle = { template, isChecked -> toggleBind(template, isChecked) }
                )
                binding.recyclerView.adapter = adapter
                adapter.setData(list)
            }
        }
    }

    private fun notify(action: String, templateId: Long = 0L) {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            Bundle().apply {
                putString(RESULT_ACTION, action)
                putLong(RESULT_TEMPLATE_ID, templateId)
            }
        )
    }

    /** 只切换编辑目标，不改当前书绑定（与 AI 生图一致） */
    private fun selectTemplate(template: AiChapterFixTemplate) {
        currentEditId = template.id
        notify(ACTION_SELECT, template.id)
        toastOnUi("已切换编辑模板「${template.name}」；自动处理模板请用「绑定本书」设置")
        dismissAllowingStateLoss()
    }

    private fun toggleBind(template: AiChapterFixTemplate, isChecked: Boolean) {
        if (!hasBook) {
            toastOnUi("未打开书籍，无法绑定")
            loadTemplates()
            return
        }
        val boundId = AiChapterFixService.getBoundTemplateId(bookName, bookUrl)
        if (isChecked) {
            AiChapterFixService.setBoundTemplateId(bookName, bookUrl, template.id)
            toastOnUi("当前书籍已绑定「${template.name}」")
        } else if (boundId == template.id) {
            AiChapterFixService.setBoundTemplateId(bookName, bookUrl, null)
            toastOnUi("当前书籍已解绑，回到默认模板")
        } else {
            // 关闭的不是当前绑定项：只把开关复原，不产生多余写入
            loadTemplates()
            return
        }
        loadTemplates()
        notify(ACTION_BIND_CHANGED, template.id)
    }

    private fun showNewTemplateDialog() {
        val input = EditText(requireContext()).apply { hint = "模板名称" }
        AlertDialog.Builder(requireContext())
            .setTitle("新建模板")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toastOnUi("模板名称不能为空")
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = appDb.aiChapterFixTemplateDao
                    // 新模板继承默认模板的可执行配置，避免建出来是空白不可用
                    val base = dao.getDefault()
                        ?: AiChapterFixService.builtinDefaultTemplate()
                    val template = base.copy(
                        id = newTemplateId(dao),
                        name = name,
                        isDefault = false,
                        lastUpdateTime = System.currentTimeMillis(),
                        sortOrder = (dao.getMinSortOrderOfNonDefault() ?: 0) - 1
                    )
                    dao.insert(template)
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        toastOnUi("已创建模板「${template.name}」")
                        selectTemplate(template)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTemplateMenu(anchor: View, template: AiChapterFixTemplate) {
        val popupMenu = PopupMenu(requireContext(), anchor)
        popupMenu.inflate(R.menu.ai_image_template_item)
        popupMenu.menu.findItem(R.id.menu_delete).isVisible = !template.isDefault
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_rename -> showRenameDialog(template)
                R.id.menu_copy -> showDuplicateDialog(template)
                R.id.menu_delete -> confirmDelete(template)
            }
            true
        }
        popupMenu.show()
    }

    private fun showRenameDialog(template: AiChapterFixTemplate) {
        val input = EditText(requireContext()).apply {
            setText(template.name)
            hint = "模板名称"
            setSelection(template.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("重命名模板")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty() || name == template.name) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = appDb.aiChapterFixTemplateDao
                    val base = dao.getById(template.id) ?: template
                    val updated = base.copy(
                        name = name,
                        lastUpdateTime = System.currentTimeMillis()
                    )
                    dao.insert(updated)
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        toastOnUi("已重命名为「${updated.name}」")
                        loadTemplates()
                        notify(ACTION_RENAMED, updated.id)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDuplicateDialog(template: AiChapterFixTemplate) {
        val input = EditText(requireContext()).apply {
            setText(template.name)
            hint = "模板名称"
            setSelection(template.name.length)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("复制模板")
            .setMessage("不改名直接确定，将保存为「${template.name}副本」。")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val inputName = input.text?.toString()?.trim().orEmpty()
                val name = if (inputName.isEmpty() || inputName == template.name) {
                    "${template.name}副本"
                } else {
                    inputName
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = appDb.aiChapterFixTemplateDao
                    val newTemplate = template.copy(
                        id = newTemplateId(dao),
                        name = name,
                        isDefault = false,
                        lastUpdateTime = System.currentTimeMillis(),
                        sortOrder = (dao.getMinSortOrderOfNonDefault() ?: 0) - 1
                    )
                    dao.insert(newTemplate)
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        toastOnUi("已复制为「${newTemplate.name}」")
                        selectTemplate(newTemplate)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(template: AiChapterFixTemplate) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除模板")
            .setMessage("确认删除模板「${template.name}」？将同时解除所有书籍对该模板的绑定，删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    appDb.aiChapterFixTemplateDao.deleteById(template.id)
                    AiChapterFixService.unbindTemplateFromAllBooks(template.id)
                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        toastOnUi("已删除模板「${template.name}」")
                        loadTemplates()
                        notify(ACTION_DELETED, template.id)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 避免同一毫秒内新建/复制多个模板时主键冲突 */
    private fun newTemplateId(dao: AiChapterFixTemplateDao): Long {
        var id = System.currentTimeMillis()
        while (dao.getById(id) != null) {
            id++
        }
        return id
    }
}
