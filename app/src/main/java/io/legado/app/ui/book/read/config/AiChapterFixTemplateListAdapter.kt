package io.legado.app.ui.book.read.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.databinding.ItemAiImageTemplateBinding
import io.legado.app.service.AiChapterFixService

/**
 * AI 章节处理模板列表 adapter。
 * 与生图模板不同：一本书只能绑定一个模板（单选语义），未绑定时使用默认模板。
 */
class AiChapterFixTemplateListAdapter(
    private val selectedId: Long,
    private val bookName: String,
    private val bookUrl: String,
    private val onSelect: (AiChapterFixTemplate) -> Unit,
    /** 点击更多按钮，第二参数为锚点 View，用于挂载 PopupMenu */
    private val onMore: (AiChapterFixTemplate, View) -> Unit,
    private val onBindToggle: (AiChapterFixTemplate, Boolean) -> Unit,
) : ListAdapter<AiChapterFixTemplate, AiChapterFixTemplateListAdapter.ViewHolder>(
    DiffCallback()
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAiImageTemplateBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setData(newData: List<AiChapterFixTemplate>) {
        submitList(newData)
    }

    inner class ViewHolder(private val binding: ItemAiImageTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(template: AiChapterFixTemplate) {
            binding.tvName.text = if (template.isDefault) "${template.name}（默认）" else template.name
            binding.radioButton.isChecked = template.id == selectedId

            // 绑定开关：未打开书籍时隐藏
            val bindVisible = bookName.isNotBlank() || bookUrl.isNotBlank()
            binding.tvBindLabel.visibility = if (bindVisible) View.VISIBLE else View.GONE
            binding.switchBind.visibility = if (bindVisible) View.VISIBLE else View.GONE
            // 绑定状态：显式绑定了该模板，或未绑定任何模板且这是默认模板（默认模板兜底生效）
            val boundId = AiChapterFixService.getBoundTemplateId(bookName, bookUrl)
            val bound = bindVisible &&
                (boundId == template.id || (boundId == 0L && template.isDefault))
            // 避免复用触发回调
            binding.switchBind.setOnCheckedChangeListener(null)
            binding.switchBind.isChecked = bound
            binding.switchBind.setOnCheckedChangeListener { _, isChecked ->
                onBindToggle(template, isChecked)
            }

            val selectListener = View.OnClickListener { onSelect(template) }
            binding.root.setOnClickListener(selectListener)
            binding.radioButton.setOnClickListener(selectListener)

            binding.btnMore.setOnClickListener { onMore(template, it) }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AiChapterFixTemplate>() {
        override fun areItemsTheSame(oldItem: AiChapterFixTemplate, newItem: AiChapterFixTemplate): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AiChapterFixTemplate, newItem: AiChapterFixTemplate): Boolean {
            return oldItem == newItem
        }
    }
}
