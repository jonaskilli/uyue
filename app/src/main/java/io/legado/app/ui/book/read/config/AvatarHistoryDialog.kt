package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogAvatarHistoryBinding
import io.legado.app.databinding.ItemAvatarHistoryBinding
import io.legado.app.model.AiImageGenerator
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.io.File

/**
 * 角色立绘历史。
 *
 * 重新生成立绘时旧图会归档保存（见 [AiImageGenerator.archiveCurrentAvatar]），
 * 这里把它们列出来：点任意一张可「使用这张」换回去，或「删除这张」清掉。
 * 新图不满意时就能找回原来那张。
 *
 * 用法：[newInstance] → show，使用后可监听 [RESULT_KEY] 刷新外部立绘。
 */
class AvatarHistoryDialog : BaseDialogFragment(R.layout.dialog_avatar_history) {

    private val binding by viewBinding(DialogAvatarHistoryBinding::bind)

    private val bookName get() = arguments?.getString(EXTRA_BOOK).orEmpty()
    private val characterName get() = arguments?.getString(EXTRA_CHARACTER).orEmpty()

    private val items = mutableListOf<File>()
    private val adapter = HistoryAdapter()

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvTitle.text = "立绘历史 · $characterName"
        binding.rvHistory.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvHistory.adapter = adapter
        binding.tvClose.setOnClickListener { dismissAllowingStateLoss() }
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(AiImageGenerator.getAvatarHistory(bookName, characterName))
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onItemClick(file: File) {
        AlertDialog.Builder(requireContext())
            .setItems(arrayOf("使用这张", "删除这张")) { _, which ->
                if (which == 0) useIt(file) else deleteIt(file)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun useIt(file: File) {
        if (AiImageGenerator.useAvatarHistory(bookName, characterName, file)) {
            toastOnUi("已换回该立绘，原图已存入历史")
            // 通知档案弹窗刷新预览
            parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
            reload()
        } else {
            toastOnUi("使用失败，文件可能已损坏")
        }
    }

    private fun deleteIt(file: File) {
        if (AiImageGenerator.deleteAvatarHistory(file)) {
            toastOnUi("已删除该历史立绘")
            reload()
        } else {
            toastOnUi("删除失败")
        }
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                ItemAvatarHistoryBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
            )

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val f = items[position]
            Glide.with(holder.binding.ivAvatar)
                .load(f)
                .centerCrop()
                .into(holder.binding.ivAvatar)
            holder.itemView.setOnClickListener { onItemClick(f) }
        }

        inner class Holder(val binding: ItemAvatarHistoryBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        /** 立绘被换回后发出的结果 key，供调用方刷新预览 */
        const val RESULT_KEY = "avatarHistoryChanged"

        private const val EXTRA_BOOK = "bookName"
        private const val EXTRA_CHARACTER = "characterName"

        fun newInstance(bookName: String, characterName: String): AvatarHistoryDialog =
            AvatarHistoryDialog().apply {
                arguments = Bundle().apply {
                    putString(EXTRA_BOOK, bookName)
                    putString(EXTRA_CHARACTER, characterName)
                }
            }
    }
}
