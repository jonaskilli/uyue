package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.databinding.DialogAiImageTemplateListBinding
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.ReadBook
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * AI 生图模板列表对话框。
 * 单选某个模板用于在设置界面编辑；左下角新建模板；每项右侧可删除（默认模板除外）。
 * 每项右侧带「绑定」开关，可统一管理当前书籍绑定的多个模板。
 */
class AiImageTemplateListDialog : BaseDialogFragment(R.layout.dialog_ai_image_template_list) {

    private val binding by viewBinding(DialogAiImageTemplateListBinding::bind)

    /** 当前正在编辑的模板 id */
    private var currentEditId: Long = 0

    /** 选择模板后的回调 */
    private var onSelectCallback: ((AiImageTemplate) -> Unit)? = null

    /** 绑定状态变化后的回调（用于通知外部刷新） */
    private var onBindChangedCallback: (() -> Unit)? = null

    /**
     * 重命名后的回调。仅当改名的正好是上层正在编辑的模板时才需要刷新，
     * 用于同步设置界面顶部的模板名。
     */
    private var onRenamedCallback: ((AiImageTemplate) -> Unit)? = null

    companion object {
        private const val ARG_CURRENT_ID = "current_id"

        fun newInstance(
            currentEditId: Long,
            onSelect: (AiImageTemplate) -> Unit,
            onBindChanged: () -> Unit = {},
            onRenamed: (AiImageTemplate) -> Unit = {}
        ): AiImageTemplateListDialog {
            return AiImageTemplateListDialog().apply {
                arguments = Bundle().apply { putLong(ARG_CURRENT_ID, currentEditId) }
                onSelectCallback = onSelect
                onBindChangedCallback = onBindChanged
                onRenamedCallback = onRenamed
            }
        }
    }

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        currentEditId = arguments?.getLong(ARG_CURRENT_ID, 0) ?: 0

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        binding.btnAdd.setOnClickListener {
            showNewTemplateDialog()
        }

        loadTemplates()
    }

    private fun loadTemplates() {
        val book = ReadBook.book
        val bookName = book?.name.orEmpty()
        val bookUrl = book?.bookUrl.orEmpty()
        execute {
            appDb.aiImageTemplateDao.getAllList()
        }.onSuccess(Dispatchers.Main) { list ->
            val adapter = AiImageTemplateListAdapter(
                selectedId = currentEditId,
                bookName = bookName,
                bookUrl = bookUrl,
                onSelect = { template ->
                    onSelectCallback?.invoke(template)
                    dismiss()
                },
                onMore = { template, anchor ->
                    showTemplateMenu(anchor, template)
                },
                onBindToggle = { template, isChecked ->
                    if (bookName.isBlank() && bookUrl.isBlank()) {
                        toastOnUi("未打开书籍，无法绑定")
                        return@AiImageTemplateListAdapter
                    }
                    if (isChecked) {
                        val ok = AiImageGenerator.addBoundTemplateId(bookName, bookUrl, template.id)
                        if (ok) {
                            toastOnUi("已将「${template.name}」加入当前书籍")
                        } else {
                            toastOnUi("每本书最多绑定 ${AiImageGenerator.MAX_BOUND_TEMPLATES} 个模板")
                            loadTemplates()
                            onBindChangedCallback?.invoke()
                            return@AiImageTemplateListAdapter
                        }
                    } else {
                        AiImageGenerator.removeBoundTemplateId(bookName, bookUrl, template.id)
                        toastOnUi("已将「${template.name}」从当前书籍移除")
                    }
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
                    val template = AiImageTemplate(
                        name = name,
                        modelUrl = AiImageGenerator.DEFAULT_MODEL_URL,
                        modelName = AiImageGenerator.DEFAULT_MODEL_NAME,
                        imageSize = "784x1168",
                        imageStyle = AiImageGenerator.DEFAULT_STYLE_SUFFIX,
                        promptTemplate = AiImageGenerator.DEFAULT_PROMPT_TEMPLATE,
                        negativePrompt = AiImageGenerator.DEFAULT_NEGATIVE_PROMPT,
                        isDefault = false
                    )
                    // 新建模板固定在默认模板之后的第 2 位
                    val minSort = appDb.aiImageTemplateDao.getMinSortOrderOfNonDefault() ?: 0
                    template.sortOrder = minSort - 1
                    appDb.aiImageTemplateDao.insert(template)
                    template
                }.onSuccess(Dispatchers.Main) { newTemplate ->
                    onSelectCallback?.invoke(newTemplate)
                    dismiss()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 每项右侧的更多选项菜单：重命名 / 复制模板 / 删除。
     * 默认模板全局唯一，菜单中不显示「删除」。
     */
    private fun showTemplateMenu(anchor: View, template: AiImageTemplate) {
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

    /** 重命名模板 */
    private fun showRenameDialog(template: AiImageTemplate) {
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
                if (name.isEmpty()) {
                    toastOnUi("模板名称不能为空")
                    return@setPositiveButton
                }
                if (name == template.name) return@setPositiveButton
                execute {
                    val updated = template.copy(
                        name = name,
                        lastUpdateTime = System.currentTimeMillis()
                    )
                    appDb.aiImageTemplateDao.update(updated)
                    updated
                }.onSuccess(Dispatchers.Main) { updated ->
                    toastOnUi("已重命名为「${updated.name}」")
                    loadTemplates()
                    // 改名的正好是上层正在编辑的模板，同步刷新设置界面顶部的模板名
                    if (updated.id == currentEditId) {
                        onRenamedCallback?.invoke(updated)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 复制模板：先弹出名称输入框，默认填入被复制模板的名字。
     * 未改名直接确定则保存为「原名副本」，改了名则用填写的名称。
     */
    private fun showDuplicateDialog(template: AiImageTemplate) {
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
                        // 默认模板复制出来的是普通模板，避免出现两个默认模板
                        isDefault = false,
                        lastUpdateTime = System.currentTimeMillis(),
                        // 与新建模板一致，固定在默认模板之后的第 2 位
                        sortOrder = (appDb.aiImageTemplateDao.getMinSortOrderOfNonDefault() ?: 0) - 1
                    )
                    appDb.aiImageTemplateDao.insert(newTemplate)
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

    private fun confirmDelete(template: AiImageTemplate) {
        AlertDialog.Builder(requireContext())
            .setTitle("删除模板")
            .setMessage("确认删除模板「${template.name}」？将同时解除所有书籍对该模板的绑定，删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                execute {
                    appDb.aiImageTemplateDao.deleteById(template.id)
                    // 清理所有书籍的绑定记录，避免残留失效 id 被生图轮换选中
                    AiImageGenerator.unbindTemplateFromAllBooks(template.id)
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
