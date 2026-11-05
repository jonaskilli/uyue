package io.legado.app.ui.book.read.config

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.databinding.ItemAiChapterFixTemplateBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.getCompatColor

/**
 * AI 章节处理模板列表 adapter。
 *
 * 每项两行：第一行单选 + 模板标题 + 更多，第二行提示 + 绑定开关。
 * radioButton = 当前正在编辑的模板；switchBind = 当前书显式绑定的模板。
 * 标题只显示模板名，不再把「编辑中」塞进标题导致折行。
 */
class AiChapterFixTemplateListAdapter(
    private val selectedId: Long,
    private val boundId: Long,
    private val bindVisible: Boolean,
    private val onSelect: (AiChapterFixTemplate) -> Unit,
    /** 点击更多按钮，第二参数为锚点 View，用于挂载 PopupMenu */
    private val onMore: (AiChapterFixTemplate, View) -> Unit,
    private val onBindToggle: (AiChapterFixTemplate, Boolean) -> Unit,
) : ListAdapter<AiChapterFixTemplate, AiChapterFixTemplateListAdapter.ViewHolder>(
    DiffCallback()
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAiChapterFixTemplateBinding.inflate(
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

    inner class ViewHolder(private val binding: ItemAiChapterFixTemplateBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(template: AiChapterFixTemplate) {
            val selected = template.id == selectedId
            val context = binding.root.context
            binding.tvName.text = buildString {
                append(template.name)
                if (template.isDefault) append("（默认）")
            }
            binding.tvName.setTextColor(
                if (selected) context.accentColor
                else context.getCompatColor(R.color.primaryText)
            )
            binding.radioButton.isChecked = selected
            binding.radioButton.contentDescription = "编辑模板：${template.name}"

            binding.bindRow.visibility = if (bindVisible) View.VISIBLE else View.GONE
            binding.tvBindLabel.text = if (selected) "编辑中 · 绑定本书" else "绑定本书"
            binding.switchBind.contentDescription = "绑定本书：${template.name}"
            // 避免复用触发回调
            binding.switchBind.setOnCheckedChangeListener(null)
            binding.switchBind.isChecked = bindVisible && boundId == template.id
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
