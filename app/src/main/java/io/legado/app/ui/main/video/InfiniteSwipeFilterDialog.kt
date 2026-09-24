package io.legado.app.ui.main.video

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.SwitchCompat
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.dao.getVideoSourceParts
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.video.InfiniteSwipeFilter
import io.legado.app.help.video.InfiniteSwipeFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 无限刷分类筛选面板。
 *
 * 长按「无限刷」按钮弹出：列出所有视频源（每个源一个总开关），
 * 展开源后显示其发现分类（每个分类一个开关）。底部「开始无限刷」会保存筛选并触发刷新。
 * 只有开启的源 / 分类才会参与 [io.legado.app.help.video.InfiniteSwipeFeed] 的后台随机获取。
 */
class InfiniteSwipeFilterDialog(
    private val onStartSwipe: () -> Unit,
) : BaseDialogFragment(R.layout.dialog_infinite_swipe_filter) {

    /** 列表行模型 */
    private sealed class Row {
        data class Source(
            val source: BookSourcePart,
            var enabled: Boolean,
            var expanded: Boolean,
        ) : Row()

        data class Category(
            val sourceUrl: String,
            val kind: ExploreKind,
            var enabled: Boolean,
        ) : Row()
    }

    private val items = mutableListOf<Row>()
    private val sourceList = mutableListOf<BookSourcePart>()
    private val kindsMap = mutableMapOf<String, List<ExploreKind>>()
    private lateinit var adapter: FilterAdapter

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val progress = view.findViewById<View>(R.id.progress)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = FilterAdapter(
            items = items,
            onSourceToggle = { pos, enabled -> onSourceToggle(pos, enabled) },
            onCategoryToggle = { pos, enabled -> onCategoryToggle(pos, enabled) },
            onExpand = { pos -> toggleExpand(pos) },
        )
        recycler.adapter = adapter

        view.findViewById<View>(R.id.tv_expand_all).setOnClickListener { setAllExpanded(true) }
        view.findViewById<View>(R.id.tv_collapse_all).setOnClickListener { setAllExpanded(false) }
        view.findViewById<View>(R.id.tv_select_all).setOnClickListener { selectAll(true) }
        view.findViewById<View>(R.id.tv_clear_all).setOnClickListener { selectAll(false) }
        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_start).setOnClickListener {
            dismiss()
            onStartSwipe()
        }
        view.findViewById<View>(R.id.btn_delete_list).setOnClickListener {
            InfiniteSwipeFeed.clearSavedList()
            Toast.makeText(requireContext(), R.string.infinite_swipe_list_cleared, Toast.LENGTH_SHORT).show()
        }

        // 后台并行加载所有视频源及其发现分类（探索规则可能需解析/联网，故异步）
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val sources = appDb.bookSourceDao.getVideoSourceParts()
                val map = coroutineScope {
                    sources.map { src ->
                        async {
                            src.bookSourceUrl to runCatching { src.exploreKinds() }
                                .getOrDefault(emptyList())
                        }
                    }.awaitAll().toMap(mutableMapOf())
                }
                withContext(Dispatchers.Main) {
                    sourceList.clear()
                    sourceList.addAll(sources)
                    kindsMap.clear()
                    kindsMap.putAll(map)
                    rebuildItems()
                    adapter.notifyDataSetChanged()
                    progress.isGone = true
                    recycler.isVisible = true
                }
            }.onFailure {
                withContext(Dispatchers.Main) { progress.isGone = true }
            }
        }
    }

    /** 仅放入所有源行（默认折叠） */
    private fun rebuildItems() {
        items.clear()
        sourceList.forEach { src ->
            items.add(
                Row.Source(
                    src,
                    InfiniteSwipeFilter.isSourceEnabled(src.bookSourceUrl),
                    false,
                )
            )
        }
    }

    /** 切换源的展开/折叠：展开时载入该源分类行 */
    private fun toggleExpand(pos: Int) {
        val row = items[pos] as? Row.Source ?: return
        row.expanded = !row.expanded
        if (row.expanded) {
            val cats = kindsMap[row.source.bookSourceUrl].orEmpty()
                .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
                .map {
                    Row.Category(
                        row.source.bookSourceUrl,
                        it,
                        InfiniteSwipeFilter.isCategoryEnabled(row.source.bookSourceUrl, it.title),
                    )
                }
            items.addAll(pos + 1, cats)
            adapter.notifyItemRangeInserted(pos + 1, cats.size)
        } else {
            val removed = removeCategories(pos)
            if (removed > 0) adapter.notifyItemRangeRemoved(pos + 1, removed)
        }
        adapter.notifyItemChanged(pos)
    }

    /** 移除 pos 源行下方、属于该源的分类行，返回移除数量 */
    private fun removeCategories(pos: Int): Int {
        val row = items[pos] as? Row.Source ?: return 0
        var count = 0
        var i = pos + 1
        while (i < items.size) {
            val r = items[i]
            if (r is Row.Category && r.sourceUrl == row.source.bookSourceUrl) {
                items.removeAt(i)
                count++
            } else {
                break
            }
        }
        return count
    }

    private fun onSourceToggle(pos: Int, enabled: Boolean) {
        val row = items[pos] as? Row.Source ?: return
        InfiniteSwipeFilter.setSourceEnabled(row.source.bookSourceUrl, enabled)
        row.enabled = enabled
        if (!enabled) {
            val removed = removeCategories(pos)
            if (removed > 0) {
                row.expanded = false
                adapter.notifyItemRangeRemoved(pos + 1, removed)
            }
        }
        adapter.notifyItemChanged(pos)
    }

    private fun onCategoryToggle(pos: Int, enabled: Boolean) {
        val row = items[pos] as? Row.Category ?: return
        InfiniteSwipeFilter.setCategoryEnabled(row.sourceUrl, row.kind.title, enabled)
        row.enabled = enabled
        adapter.notifyItemChanged(pos)
    }

    /** 全部展开 / 折叠 */
    private fun setAllExpanded(expand: Boolean) {
        val newItems = mutableListOf<Row>()
        for (src in sourceList) {
            val enabled = InfiniteSwipeFilter.isSourceEnabled(src.bookSourceUrl)
            val doExpand = expand && enabled
            newItems.add(Row.Source(src, enabled, doExpand))
            if (doExpand) {
                newItems.addAll(
                    kindsMap[src.bookSourceUrl].orEmpty()
                        .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
                        .map {
                            Row.Category(
                                src.bookSourceUrl,
                                it,
                                InfiniteSwipeFilter.isCategoryEnabled(src.bookSourceUrl, it.title),
                            )
                        }
                )
            }
        }
        items.clear()
        items.addAll(newItems)
        adapter.notifyDataSetChanged()
    }

    /** 全开 / 全关：批量设置所有源与分类开关，并重建列表 */
    private fun selectAll(on: Boolean) {
        for (src in sourceList) {
            InfiniteSwipeFilter.setSourceEnabled(src.bookSourceUrl, on)
            if (on) {
                kindsMap[src.bookSourceUrl].orEmpty()
                    .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
                    .forEach { InfiniteSwipeFilter.setCategoryEnabled(src.bookSourceUrl, it.title, true) }
            }
        }
        rebuildItems()
        adapter.notifyDataSetChanged()
    }

    private class FilterAdapter(
        private val items: MutableList<Row>,
        private val onSourceToggle: (Int, Boolean) -> Unit,
        private val onCategoryToggle: (Int, Boolean) -> Unit,
        private val onExpand: (Int) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_SOURCE = 0
            private const val TYPE_CATEGORY = 1
        }

        override fun getItemViewType(position: Int): Int =
            if (items[position] is Row.Source) TYPE_SOURCE else TYPE_CATEGORY

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_SOURCE) {
                SourceVH(inflater.inflate(R.layout.item_filter_source, parent, false))
            } else {
                CategoryVH(inflater.inflate(R.layout.item_filter_category, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is SourceVH -> {
                    val row = items[position] as Row.Source
                    holder.name.text = row.source.bookSourceName.ifBlank { row.source.bookSourceUrl }
                    holder.arrow.rotation = if (row.expanded) 0f else -90f
                    holder.switch.setOnCheckedChangeListener(null)
                    holder.switch.isChecked = row.enabled
                    holder.switch.setOnCheckedChangeListener { _, isChecked ->
                        onSourceToggle(position, isChecked)
                    }
                    holder.itemView.setOnClickListener { onExpand(position) }
                }
                is CategoryVH -> {
                    val row = items[position] as Row.Category
                    holder.name.text = row.kind.title
                    holder.switch.setOnCheckedChangeListener(null)
                    holder.switch.isChecked = row.enabled
                    holder.switch.setOnCheckedChangeListener { _, isChecked ->
                        onCategoryToggle(position, isChecked)
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size

        class SourceVH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_name)
            val arrow: TextView = view.findViewById(R.id.tv_arrow)
            val switch: SwitchCompat = view.findViewById(R.id.switch_enabled)
        }

        class CategoryVH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_name)
            val switch: SwitchCompat = view.findViewById(R.id.switch_enabled)
        }
    }
}
