package io.legado.app.ui.book.search

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ItemSearchBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.gone
import io.legado.app.utils.visible


class SearchAdapter(context: Context, val callBack: CallBack) :
    DiffRecyclerAdapter<SearchBook, ItemSearchBinding>(context) {

    override val keepScrollPosition = true

    override val diffItemCallback: DiffUtil.ItemCallback<SearchBook>
        get() = object : DiffUtil.ItemCallback<SearchBook>() {

            override fun areItemsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
                return when {
                    oldItem.name != newItem.name -> false
                    oldItem.author != newItem.author -> false
                    else -> true
                }
            }

            override fun areContentsTheSame(oldItem: SearchBook, newItem: SearchBook): Boolean {
                return false
            }

            override fun getChangePayload(oldItem: SearchBook, newItem: SearchBook): Any {
                val payload = Bundle()
                payload.putInt("origins", newItem.origins.size)
                if (oldItem.coverUrl != newItem.coverUrl)
                    payload.putString("cover", newItem.coverUrl)
                if (oldItem.kind != newItem.kind)
                    payload.putString("kind", newItem.kind)
                if (oldItem.latestChapterTitle != newItem.latestChapterTitle)
                    payload.putString("last", newItem.latestChapterTitle)
                if (oldItem.intro != newItem.intro)
                    payload.putString("intro", newItem.intro)
                return payload
            }

        }

    override fun getViewBinding(parent: ViewGroup): ItemSearchBinding {
        return ItemSearchBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemSearchBinding,
        item: SearchBook,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bindChange(binding, item, bundle)
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemSearchBinding) {
        binding.root.setOnClickListener {
            getItem(holder.layoutPosition)?.let {
                // 传整个对象：同名结果可能是多源合并的，接收方要能按用户选的源取对应链接
                callBack.showBookInfo(it)
            }
        }
        binding.root.setOnLongClickListener {
            getItem(holder.layoutPosition)?.let {
                callBack.openBookInfo(it)
            }
            true
        }
    }

    private fun bind(binding: ItemSearchBinding, searchBook: SearchBook) {
        binding.run {
            tvName.text = searchBook.name
            tvAuthor.text = context.getString(R.string.author_show, searchBook.author)
            ivInBookshelf.isVisible = callBack.isInBookshelf(searchBook)
            bvOriginCount.setBadgeCount(searchBook.origins.size)
            upSource(binding, searchBook)
            upLasted(binding, searchBook.latestChapterTitle)
            tvIntroduce.text = searchBook.trimIntro(context)
            upKind(binding, searchBook.getKindList())
            ivCover.load(
                searchBook,
                AppConfig.loadCoverOnlyWifi
            )
        }
    }

    private fun bindChange(binding: ItemSearchBinding, searchBook: SearchBook, bundle: Bundle) {
        binding.run {
            bundle.keySet().forEach {
                when (it) {
                    "origins" -> {
                bvOriginCount.setBadgeCount(searchBook.origins.size)
                upSource(binding, searchBook)
            }
                    "last" -> upLasted(binding, searchBook.latestChapterTitle)
                    "intro" -> tvIntroduce.text = searchBook.trimIntro(context)
                    "kind" -> upKind(binding, searchBook.getKindList())
                    "isInBookshelf" -> ivInBookshelf.isVisible = callBack.isInBookshelf(searchBook)
                    "cover" -> ivCover.load(
                        searchBook,
                        false
                    )
                }
            }
        }
    }

    private fun upLasted(binding: ItemSearchBinding, latestChapterTitle: String?) {
        binding.run {
            if (latestChapterTitle.isNullOrEmpty()) {
                tvLasted.gone()
            } else {
                tvLasted.text =
                    context.getString(R.string.lasted_show, latestChapterTitle)
                tvLasted.visible()
            }
        }
    }

    /** 显示来源书源名（视频搜索结果也能直接看出是哪个源返回的） */
    private fun upSource(binding: ItemSearchBinding, searchBook: SearchBook) = binding.run {
        if (searchBook.originName.isBlank()) {
            tvSource.gone()
        } else {
            tvSource.text = context.getString(R.string.source_show, searchBook.originName)
            tvSource.visible()
        }
    }

    private fun upKind(binding: ItemSearchBinding, kinds: List<String>) = binding.run {
        if (kinds.isEmpty()) {
            llKind.gone()
        } else {
            llKind.visible()
            llKind.setLabels(kinds)
        }
    }

    interface CallBack {

        /**
         * 是否已经加入书架
         */
        fun isInBookshelf(book: SearchBook): Boolean

        /**
         * 显示书籍详情。
         * 传整个 [SearchBook] 而非三个字符串：同一条目可能是多个源的同名结果合并而来，
         * 接收方需要能按用户选中的源取到该源自己的链接（见 [SearchBook.getBookUrlByOrigin]）。
         */
        fun showBookInfo(book: SearchBook)

        /**
         * 长按：打开书籍详情页，方便加书架、换源等后续操作。
         * 默认与点击行为一致，需要区分的地方单独实现
         * （如视频列表：点击即播、长按进详情页）。
         */
        fun openBookInfo(book: SearchBook) {
            showBookInfo(book)
        }
    }
}