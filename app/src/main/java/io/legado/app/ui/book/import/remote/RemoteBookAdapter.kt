package io.legado.app.ui.book.import.remote

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppConst
import io.legado.app.databinding.ItemImportBookBinding
import io.legado.app.model.remote.RemoteBook
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible


/**
 * 适配器
 * @author qianfanguojin
 */
class RemoteBookAdapter(context: Context, val callBack: CallBack) :
    RecyclerAdapter<RemoteBook, ItemImportBookBinding>(context) {
    var selected = hashSetOf<RemoteBook>()
    var checkableCount = 0

    /** 是否支持 EPUB 转换（悦读云书架专属，WebDav 不显示该入口） */
    var supportEpubConvert = false

    override fun getViewBinding(parent: ViewGroup): ItemImportBookBinding {
        return ItemImportBookBinding.inflate(inflater, parent, false)
    }

    override fun onCurrentListChanged() {
        upCheckableCount()
    }

    /**
     * 绑定RecycleView 中每一个项的视图和数据
     */
    override fun convert(
        holder: ItemViewHolder,
        binding: ItemImportBookBinding,
        item: RemoteBook,
        payloads: MutableList<Any>
    ) {
        binding.run {
            if (payloads.isEmpty()) {
                if (item.isDir) {
                    ivIcon.setImageResource(R.drawable.ic_folder)
                    ivIcon.visible()
                    cbSelect.invisible()
                    llBrief.gone()
                    cbSelect.isChecked = false
                } else {
                    // 所有文件条目均可勾选(已上架的勾选后可批量"恢复到书架")
                    ivIcon.invisible()
                    cbSelect.visible()
                    tvTag.text = if (item.isOnBookShelf) "${item.contentType}·已上架" else item.contentType
                    llBrief.visible()
                    tvSize.text = ConvertUtils.formatFileSize(item.size)
                    tvDate.text = AppConst.dateFormat.format(item.lastModify)
                    cbSelect.isChecked = selected.contains(item)
                }
                tvName.text = item.filename
            } else {
                cbSelect.isChecked = selected.contains(item)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemImportBookBinding) {
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.isDir) {
                    callBack.openDir(it)
                } else if (!it.isOnBookShelf) {
                    if (!selected.contains(it)) {
                        selected.add(it)
                    } else {
                        selected.remove(it)
                    }
                    notifyItemChanged(holder.layoutPosition, true)
                    callBack.upCountView()
                } else {
                    /* 点击开始阅读 */
                    callBack.startRead(it)
                }
            }
        }
        // checkbox 单独点击: 已上架的书也可勾选, 供批量恢复到书架
        binding.cbSelect.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.isDir) return@setOnClickListener
                if (!selected.contains(it)) {
                    selected.add(it)
                } else {
                    selected.remove(it)
                }
                notifyItemChanged(holder.layoutPosition, true)
                callBack.upCountView()
            }
        }
        holder.itemView.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let { remoteBook ->
                showItemActions(remoteBook)
            }
            true
        }
    }

    /**
     * 长按操作菜单: 重命名 / 删除 / (已上架时)重新加入书架 / (悦读)转 EPUB
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun showItemActions(remoteBook: RemoteBook) {
        val actions = arrayListOf<Pair<String, () -> Unit>>()
        if (remoteBook.isOnBookShelf) {
            actions.add("重新加入书架" to { callBack.addToBookShelfAgain(remoteBook) })
        }
        if (supportEpubConvert) {
            actions.add("转 EPUB" to { callBack.convertToEpub(remoteBook) })
        }
        actions.add("重命名" to { callBack.renameRemoteBook(remoteBook) })
        actions.add("删除" to { callBack.deleteRemoteBooks(listOf(remoteBook)) })
        val titles = actions.map { it.first }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(remoteBook.filename)
            .setItems(titles) { _, which ->
                actions[which].second.invoke()
            }
            .show()
    }

    private fun upCheckableCount() {
        checkableCount = 0
        getItems().forEach {
            if (!it.isDir) {
                checkableCount++
            }
        }
        callBack.upCountView()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            getItems().forEach {
                if (!it.isDir) {
                    selected.add(it)
                }
            }
        } else {
            selected.clear()
        }
        notifyDataSetChanged()
        callBack.upCountView()
    }

    fun revertSelection() {
        getItems().forEach {
            if (!it.isDir) {
                if (selected.contains(it)) {
                    selected.remove(it)
                } else {
                    selected.add(it)
                }
            }
        }
        notifyItemRangeChanged(0, itemCount, true)
        callBack.upCountView()
    }

    fun removeSelection() {
        for (i in getItems().lastIndex downTo 0) {
            if (getItem(i) in selected) {
                removeItem(i)
            }
        }
    }


    interface CallBack {
        fun openDir(remoteBook: RemoteBook)
        fun upCountView()
        fun startRead(remoteBook: RemoteBook)
        fun addToBookShelfAgain(remoteBook: RemoteBook)

        /** 触发云端把这本书转换成 EPUB（仅悦读云书架） */
        fun convertToEpub(remoteBook: RemoteBook)

        /** 删除远程书籍/目录(含确认) */
        fun deleteRemoteBooks(remoteBooks: Collection<RemoteBook>)

        /** 重命名远程书籍/目录(弹输入框) */
        fun renameRemoteBook(remoteBook: RemoteBook)
    }
}
