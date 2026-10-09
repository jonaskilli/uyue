package io.legado.app.ui.tts.plugin

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ItemTtsPluginLogBinding

/**
 * 日志页的一行，对齐 TTS_Server_Android 的条目样式：
 * 第一行「时间 + 级别字母」（小字、次要色），第二行「内容」（14sp、按级别着色），
 * 条目间细分隔线；新日志在列表底部，页面会自动滚到底。
 *
 * [text] 是整行纯文本（时间 + 级别 + 内容），供「复制」按钮整页导出用。
 */
data class LogRow(
    val time: Long,
    val timeText: String,
    val levelChar: String,
    val message: String,
    val color: Int,
) {
    val text: String
        get() = "$timeText $levelChar $message"
}

class PluginLogAdapter(context: Context) :
    RecyclerAdapter<LogRow, ItemTtsPluginLogBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemTtsPluginLogBinding =
        ItemTtsPluginLogBinding.inflate(inflater, parent, false)

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemTtsPluginLogBinding,
        item: LogRow,
        payloads: MutableList<Any>
    ) {
        binding.tvLogTime.text = item.timeText
        binding.tvLogLevel.text = item.levelChar
        binding.tvLog.text = item.message
        binding.tvLog.setTextColor(item.color)
        // 分隔线与参考项目一致：最后一条不显示（INVISIBLE 保持行高稳定，避免滚动跳动）
        binding.logDivider.visibility =
            if (holder.layoutPosition < itemCount - 1) View.VISIBLE else View.INVISIBLE
    }

    // 整行没有额外动作（内容已 textIsSelectable，可直接选中复制）
    override fun registerListener(holder: ItemViewHolder, binding: ItemTtsPluginLogBinding) {
    }
}
