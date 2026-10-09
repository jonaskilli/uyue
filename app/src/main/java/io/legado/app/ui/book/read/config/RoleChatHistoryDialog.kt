package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogRoleChatHistoryBinding
import io.legado.app.databinding.ItemRoleChatHistoryBinding
import io.legado.app.model.AiChatManager
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 角色对话历史查看弹窗。
 *
 * 主对话界面（galgame 式）只显示 AI 的最近一次回复；本弹窗展示完整的双方
 * 会话记录（读者靠右、角色靠左），支持一键清空。
 */
class RoleChatHistoryDialog : BaseDialogFragment(R.layout.dialog_role_chat_history) {

    private val binding by viewBinding(DialogRoleChatHistoryBinding::bind)

    private val bookName get() = requireArguments().getString(EXTRA_BOOK) ?: "默认"
    private val characterName get() = requireArguments().getString(EXTRA_CHARACTER) ?: ""

    /** 清空后回调宿主（主界面需同步清掉内存中的会话） */
    var onCleared: (() -> Unit)? = null

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = HistoryAdapter()
        binding.tvClose.setOnClickListener { dismissAllowingStateLoss() }
        binding.tvClear.setOnClickListener {
            android.app.AlertDialog.Builder(requireContext())
                .setTitle("清空对话")
                .setMessage("将删除与「$characterName」的全部对话记录，确定？")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    AiChatManager.clearHistory(bookName, characterName)
                    binding.rvHistory.adapter = HistoryAdapter()
                    toastOnUi("已清空")
                    onCleared?.invoke()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private inner class HistoryAdapter :
        RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private val messages = AiChatManager.loadHistory(bookName, characterName)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(
                ItemRoleChatHistoryBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun getItemCount(): Int = messages.size

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val msg = messages[position]
            holder.bind(msg)
        }

        inner class Holder(private val item: ItemRoleChatHistoryBinding) :
            RecyclerView.ViewHolder(item.root) {

            @SuppressLint("SetTextI18n")
            fun bind(msg: AiChatManager.ChatMessage) {
                val isUser = msg.role == "user"
                if (isUser) {
                    item.llUser.visibility = View.VISIBLE
                    item.llAi.visibility = View.GONE
                    item.tvUser.visibility = View.VISIBLE
                    item.tvUser.text = msg.content
                } else {
                    item.llUser.visibility = View.GONE
                    item.llAi.visibility = View.VISIBLE
                    item.tvAi.visibility = View.VISIBLE
                    item.tvAi.text = "$characterName：${msg.content}"
                }
            }
        }
    }

    companion object {
        private const val EXTRA_BOOK = "bookName"
        private const val EXTRA_CHARACTER = "characterName"

        fun newInstance(bookName: String, characterName: String): RoleChatHistoryDialog {
            return RoleChatHistoryDialog().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_BOOK, bookName)
                    putString(EXTRA_CHARACTER, characterName)
                }
            }
        }
    }
}
