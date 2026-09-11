package io.legado.app.ui.book.changesource

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemChangeSourceBinding
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.init.appCtx
import splitties.views.onLongClick


class ChangeBookSourceAdapter(
    context: Context,
    val viewModel: ChangeBookSourceViewModel,
    val callBack: CallBack
) : DiffRecyclerAdapter<SearchBook, ItemChangeSourceBinding>(context) {

    override val diffItemCallback = object : DiffUtil.ItemCallback<SearchBook>() {
        override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.bookUrl == newItem.bookUrl
        }

        override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
            return oldItem.originName == newItem.originName
                    && oldItem.getDisplayLastChapterTitle() == newItem.getDisplayLastChapterTitle()
                    && oldItem.chapterWordCountText == newItem.chapterWordCountText
                    && oldItem.respondTime == newItem.respondTime
        }

    }

    override fun getViewBinding(parent: ViewGroup): ItemChangeSourceBinding {
        return ItemChangeSourceBinding.inflate(inflater, parent, false)
    }

    // bookUrl -> 本地已缓存章节数
    private val cacheCountMap = HashMap<String, Int>()
    private val cacheCountLoading = HashSet<String>()

    // 未经缓存排序的原始列表，用于关闭开关时恢复原顺序
    private var sourceItems: List<SearchBook>? = null

    // 提交列表后是否需要在列表生效时强制刷新一遍条目。
    // RecyclerView 对"移动"的 ViewHolder 只移动位置不会重新绑定(onBindViewHolder)，
    // 缓存统计完成后有缓存的条目会被移动到顶部，只移动不刷新会导致缓存数量显示不出来。
    private var cacheRefreshPending = false

    /**
     * 设置条目列表。
     * 开启“显示本地缓存章节数”时，有缓存的条目按缓存数量倒序置顶，
     * 并后台统计各条目的缓存章节数，统计完成后再重新排序。
     */
    fun setItemsWithCacheSort(items: List<SearchBook>?) {
        sourceItems = items
        submitItemsAndRefresh(sortedByCacheCount(items))
        if (AppConfig.changeSourceShowCacheCount) {
            scanCacheCount(items ?: emptyList())
        }
    }

    /**
     * 缓存数量显示开关变化时调用，开启时置顶排序，关闭时恢复原顺序
     */
    fun resortByCacheCount() {
        val items = sourceItems ?: getItems().toList()
        submitItemsAndRefresh(sortedByCacheCount(items))
        if (AppConfig.changeSourceShowCacheCount) {
            scanCacheCount(items)
        }
    }

    /**
     * 提交列表，并在列表生效后强制刷新一遍可见条目。
     * 否则被"移动"到顶部的条目不会重新绑定，缓存数量不会显示。
     */
    private fun submitItemsAndRefresh(items: List<SearchBook>?) {
        if (items != null && items == getItems()) {
            // 列表内容没变化时 submitList 会被直接短路，不会触发回调，这里直接刷新
            if (AppConfig.changeSourceShowCacheCount) {
                notifyItemRangeChanged(0, itemCount)
            }
            return
        }
        cacheRefreshPending = AppConfig.changeSourceShowCacheCount
        setItems(items)
    }

    override fun onCurrentListChanged() {
        if (cacheRefreshPending) {
            cacheRefreshPending = false
            notifyItemRangeChanged(0, itemCount)
        }
    }

    /**
     * 开启开关时按缓存章节数倒序排列，有缓存的条目集中在最上面。
     * 排序稳定，缓存数相同的条目保持原相对顺序。
     */
    private fun sortedByCacheCount(items: List<SearchBook>?): List<SearchBook>? {
        items ?: return null
        if (!AppConfig.changeSourceShowCacheCount) return items
        return items.sortedByDescending { cacheCountMap[it.bookUrl] ?: 0 }
    }

    /**
     * 获取条目对应书源/书籍的本地已缓存章节数（仅读取已统计结果）
     */
    private fun getCachedChapterCount(item: SearchBook): Int {
        return cacheCountMap[item.bookUrl] ?: 0
    }

    /**
     * 后台统计未统计过的条目的缓存章节数。
     * 统计出有缓存的条目时重新排序，使有缓存的条目集中置顶。
     */
    private fun scanCacheCount(items: List<SearchBook>) {
        val uncounted = items.filter {
            it.bookUrl !in cacheCountMap && cacheCountLoading.add(it.bookUrl)
        }
        if (uncounted.isEmpty()) return
        Coroutine.async {
            uncounted.forEach { item ->
                val count = BookHelp.getCachedChapterCount(item.toBook())
                cacheCountMap[item.bookUrl] = count
                cacheCountLoading.remove(item.bookUrl)
            }
        }.onSuccess {
            if (AppConfig.changeSourceShowCacheCount) {
                submitItemsAndRefresh(sortedByCacheCount(getItems().toList()))
            }
        }.onError {
            uncounted.forEach { cacheCountLoading.remove(it.bookUrl) }
        }
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemChangeSourceBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        binding.apply {
            if (payloads.isEmpty()) {
                tvOrigin.text = item.originName
                tvAuthor.text = item.author
                tvLast.text = item.getDisplayLastChapterTitle()
                tvCurrentChapterWordCount.text = item.chapterWordCountText
                tvRespondTime.text = context.getString(R.string.respondTime, item.respondTime)
                if (callBack.oldBookUrl == item.bookUrl) {
                    ivChecked.visible()
                } else {
                    ivChecked.invisible()
                }
            } else {
                for (i in payloads.indices) {
                    val bundle = payloads[i] as Bundle
                    bundle.keySet().forEach {
                        when (it) {
                            "name" -> tvOrigin.text = item.originName
                            "latest" -> tvLast.text = item.getDisplayLastChapterTitle()
                            "upCurSource" -> if (callBack.oldBookUrl == item.bookUrl) {
                                ivChecked.visible()
                            } else {
                                ivChecked.invisible()
                            }
                        }
                    }
                }
            }
            val score = callBack.getBookScore(item)
            if (score > 0) {
                binding.ivBad.gone()
                binding.ivGood.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            } else if (score < 0) {
                binding.ivGood.gone()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
            } else {
                binding.ivGood.visible()
                binding.ivBad.visible()
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
            }

            if (AppConfig.changeSourceLoadWordCount && !item.chapterWordCountText.isNullOrBlank()) {
                tvCurrentChapterWordCount.visible()
            } else {
                tvCurrentChapterWordCount.gone()
            }

            if (AppConfig.changeSourceLoadWordCount && item.respondTime >= 0) {
                tvRespondTime.visible()
            } else {
                tvRespondTime.gone()
            }

            if (AppConfig.changeSourceShowCacheCount) {
                val cacheCount = getCachedChapterCount(item)
                if (cacheCount > 0) {
                    tvCacheCount.visible()
                    tvCacheCount.text = context.getString(R.string.cache_chapter_count, cacheCount)
                    ivDeleteCache.visible()
                } else {
                    tvCacheCount.gone()
                    ivDeleteCache.gone()
                }
            } else {
                tvCacheCount.gone()
                ivDeleteCache.gone()
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemChangeSourceBinding) {
        binding.ivGood.setOnClickListener {
            if (binding.ivBad.isVisible) {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_A200)
                )
                binding.ivBad.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivGood.drawable,
                    appCtx.getCompatColor(R.color.md_red_100)
                )
                binding.ivBad.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        binding.ivBad.setOnClickListener {
            if (binding.ivGood.isVisible) {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_A200)
                )
                binding.ivGood.gone()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, -1)
                }
            } else {
                DrawableCompat.setTint(
                    binding.ivBad.drawable,
                    appCtx.getCompatColor(R.color.md_blue_100)
                )
                binding.ivGood.visible()
                getItem(holder.layoutPosition)?.let {
                    callBack.setBookScore(it, 0)
                }
            }
        }
        holder.itemView.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                if (it.bookUrl != callBack.oldBookUrl) {
                    callBack.changeTo(it)
                }
            }
        }
        binding.ivDeleteCache.setOnClickListener {
            getItem(holder.layoutPosition)?.let { item ->
                context.alert(R.string.delete_cached_chapters) {
                    setMessage(
                        context.getString(R.string.sure_del_cached_chapters, item.originName)
                    )
                    noButton()
                    yesButton {
                        callBack.deleteCache(item)
                        cacheCountMap[item.bookUrl] = 0
                        if (AppConfig.changeSourceShowCacheCount) {
                            // 缓存已清除，取消置顶并恢复排序
                            submitItemsAndRefresh(sortedByCacheCount(getItems().toList()))
                        } else {
                            notifyItemChanged(holder.layoutPosition)
                        }
                    }
                }
            }
        }
        holder.itemView.onLongClick {
            showMenu(holder.itemView, getItem(holder.layoutPosition))
        }
    }

    private fun showMenu(view: View, searchBook: SearchBook?) {
        searchBook ?: return
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.change_source_item)
        popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_top_source -> {
                    callBack.topSource(searchBook)
                }

                R.id.menu_bottom_source -> {
                    callBack.bottomSource(searchBook)
                }

                R.id.menu_edit_source -> {
                    callBack.editSource(searchBook)
                }

                R.id.menu_disable_source -> {
                    callBack.disableSource(searchBook)
                }

                R.id.menu_delete_source -> context.alert(R.string.draw) {
                    setMessage(context.getString(R.string.sure_del) + "\n" + searchBook.originName)
                    noButton()
                    yesButton {
                        callBack.deleteSource(searchBook)
                        updateItems(0, itemCount, listOf<Int>())
                    }
                }
            }
            true
        }
        popupMenu.show()
    }

    interface CallBack {
        val oldBookUrl: String?
        fun changeTo(searchBook: SearchBook)
        fun topSource(searchBook: SearchBook)
        fun bottomSource(searchBook: SearchBook)
        fun editSource(searchBook: SearchBook)
        fun disableSource(searchBook: SearchBook)
        fun deleteSource(searchBook: SearchBook)
        fun deleteCache(searchBook: SearchBook)
        fun setBookScore(searchBook: SearchBook, score: Int)
        fun getBookScore(searchBook: SearchBook): Int
    }
}