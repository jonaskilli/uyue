package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.databinding.DialogAiImageTemplateListBinding
import io.legado.app.model.ReadBook
import io.legado.app.service.AiChapterFixService
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AiChapterFixTemplateListDialog :
    BaseDialogFragment(R.layout.dialog_ai_image_template_list) {

    private val binding by viewBinding(DialogAiImageTemplateListBinding::bind)

    private var currentEditId: Long = 0
    private var onSelectCallback: ((AiChapterFixTemplate) -> Unit)? = null
    private var onBindChangedCallback: (() -> Unit)? = null

    companion object {
        private const val ARG_CURRENT_ID = "current_id"

        fun newInstance(
            currentEditId: Long,
            onSelect: (AiChapterFixTemplate) -> Unit,
            onBindChanged: () -> Unit = {}
        ): AiChapterFixTemplateListDialog {
            return AiChapterFixTemplateListDialog().apply {
                arguments = Bundle().apply { putLong(ARG_CURRENT_ID, currentEditId) }
                onSelectCallback = onSelect
                onBindChangedCallback = onBindChanged
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        currentEditId = arguments?.getLong(ARG_CURRENT_ID, 0) ?: 0
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.btnAdd.setOnClickListener { showNewTemplateDialog() }
        loadTemplates()
    }

    private fun loadTemplates() {
        val book = ReadBook.book
        val bookName = book?.name.orEmpty()
        val bookUrl = book?.bookUrl.orEmpty()
        execute {
            appDb.aiChapterFixTemplateDao.getAllList()
        }.onSuccess(Dispatchers.Main) { list ->
            val adapter = AiChapterFixTemplateListAdapter(
                selectedId = currentEditId,
                bookName = bookName,
                bookUrl = bookUrl,
                onSelect = { template ->
                    onSelectCallback?.invoke(template)
                    dismiss()
                },
                onMore = { template, anchor -> showTemplateMenu(anchor, template) },
                onBindToggle = { template, isChecked ->
                    if (bookName.isBlank() && bookUrl.isBlank()) {
                        toastOnUi("未打开书籍，无法绑定")
                        return@AiChapterFixTemplateListAdapter
                    }
                    if (isChecked) {
                        AiChapterFixService.setBoundTemplateId(bookName, bookUrl, template.id)
                        toastOnUi("当前书籍已绑定「${template.name}」")
                    } else if (AiChapterFixService.getBoundTemplateId(bookName, bookUrl) == template.id) {
                        AiChapterFixService.setBoundTemplateId(bookName, bookUrl, null)
                        toastOnUi("当前书籍已解绑，回到默认模板")
                    }
                    loadTemplates()
                    onBindChangedCallback?.invoke()
                }
            )
            binding.recyclerView.adapter = adapter
            adapter.setData(list)
        }
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
                execute {
                    val template = AiChapterFixTemplate(name = name)
                    val minSort = appDb.aiChapterFixTemplateDao.getMinSortOrderOfNonDefault() ?: 0
                    template.sortOrder = minSort - 1
                    appDb.aiChapterFixTemplateDao.insert(template)
                    template
                }.onSuccess(Dispatchers.Main) { newTemplate ->
                    onSelectCallback?.invoke(newTemplate)
                    dismiss()
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
                execute {
                    val updated = template.copy(
                        name = name, lastUpdateTime = System.currentTimeMillis()
                    )
                    appDb.aiChapterFixTemplateDao.update(updated)
                    updated
                }.onSuccess(Dispatchers.Main) { updated ->
                    toastOnUi("已重命名为「${updated.name}」")
                    loadTemplates()
                    if (updated.id == currentEditId) onSelectCallback?.invoke(updated)
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
                execute {
                    val newTemplate = template.copy(
                        id = System.currentTimeMillis(),
                        name = name,
                        isDefault = false,
                        lastUpdateTime = System.currentTimeMillis(),
                        sortOrder = (appDb.aiChapterFixTemplateDao.getMinSortOrderOfNonDefault() ?: 0) - 1
                    )
                    appDb.aiChapterFixTemplateDao.insert(newTemplate)
                    newTemplate
                }.onSuccess(Dispatchers.Main) { newTemplate ->
                    toastOnUi("已复制为「${newTemplate.name}」")
                    onSelectCallback?.invoke(newTemplate)
                    dismiss()
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
                execute {
                    appDb.aiChapterFixTemplateDao.deleteById(template.id)
                    AiChapterFixService.unbindTemplateFromAllBooks(template.id)
                }.onSuccess(Dispatchers.Main) {
                    toastOnUi("已删除模板「${template.name}」")
                    loadTemplates()
                    onBindChangedCallback?.invoke()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
