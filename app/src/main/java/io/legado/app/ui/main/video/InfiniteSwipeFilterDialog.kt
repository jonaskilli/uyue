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
 * 长按「无限刷」按钮弹出：列出所有视频源（每个源一个**三态**总开关），
 * 展开源后显示其发现分类（每个分类一个开关）。底部「开始无限刷」会保存筛选并触发刷新。
 * 只有开启的源 / 分类才会参与 [io.legado.app.help.video.InfiniteSwipeFeed] 的后台随机获取。
 *
 * 源总开关的三态（[SourceStateCircleView.State]）：
 * - 关闭：源被关掉；
 * - 开启：源开着，且下面所有分类都开着；
 * - 部分开启：源开着，但只勾了一部分分类（点它会把该源的所有分类一次性勾上）。
 */
class InfiniteSwipeFilterDialog(
    private val onStartSwipe: () -> Unit,
) : BaseDialogFragment(R.layout.dialog_infinite_swipe_filter) {

    /** 列表行模型 */
    private sealed class Row {
        data class Source(
            val source: BookSourcePart,
            var state: SourceStateCircleView.State,
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
    /** 用户是否改动过筛选配置（改过则开始无限刷时重置取视频的进度） */
    private var configChanged = false

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val progress = view.findViewById<View>(R.id.progress)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = FilterAdapter(
            items = items,
            onSourceClick = { pos -> onSourceClick(pos) },
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
            // 筛选规则被改过：按新规则从各分类第 1 页重新取
            // （否则之前"已取完"的分类会一直不参与，看起来像没有新内容）
            if (configChanged) InfiniteSwipeFeed.resetProgress()
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

    /** 该源下的可用分类（url 型发现分类） */
    private fun categoriesOf(sourceUrl: String): List<ExploreKind> =
        kindsMap[sourceUrl].orEmpty().filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }

    /**
     * 计算源总开关的三态：**完全由该源下面的分类决定**（不再有独立的"源开关"覆盖分类）：
     * - 所有分类都勾上 → 开启（红实心圆）
     * - 只勾了一部分 → 部分开启（红圆内白色空心圆）
     * - 一个都没勾 → 关闭（空心灰圆）
     * 没有分类可勾的源，退化成用源自身的开关表示。
     */
    private fun sourceState(sourceUrl: String): SourceStateCircleView.State {
        val cats = categoriesOf(sourceUrl)
        if (cats.isEmpty()) {
            return if (InfiniteSwipeFilter.isSourceEnabled(sourceUrl)) {
                SourceStateCircleView.State.ON
            } else {
                SourceStateCircleView.State.OFF
            }
        }
        // 必须用 isCategoryChecked（分类自身勾选），不能用 isCategoryEnabled：
        // 后者在源关闭时一律返回 false，会让总开关永远算成"关闭"
        val on = cats.count { InfiniteSwipeFilter.isCategoryChecked(sourceUrl, it.title) }
        return when (on) {
            0 -> SourceStateCircleView.State.OFF
            cats.size -> SourceStateCircleView.State.ON
            else -> SourceStateCircleView.State.PARTIAL
        }
    }

    /**
     * 让"源开关"跟随分类：至少勾了一个分类才算开启。
     * 后台抓取是先按 [InfiniteSwipeFilter.isSourceEnabled] 过滤源、再按分类过滤，
     * 所以两边必须同步，否则会出现"分类都勾了但源还是关着 → 一个都抓不到"。
     */
    private fun syncSourceEnabled(sourceUrl: String) {
        val cats = categoriesOf(sourceUrl)
        if (cats.isEmpty()) return
        val on = cats.count { InfiniteSwipeFilter.isCategoryChecked(sourceUrl, it.title) }
        InfiniteSwipeFilter.setSourceEnabled(sourceUrl, on > 0)
    }

    /** 仅放入所有源行（默认折叠） */
    private fun rebuildItems() {
        items.clear()
        sourceList.forEach { src ->
            items.add(
                Row.Source(
                    src,
                    sourceState(src.bookSourceUrl),
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
            val cats = categoriesOf(row.source.bookSourceUrl).map {
                Row.Category(
                    row.source.bookSourceUrl,
                    it,
                    // 与总开关同一口径（分类自身勾选），否则源一关分类就全显示成关闭
                    InfiniteSwipeFilter.isCategoryChecked(row.source.bookSourceUrl, it.title),
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

    /**
     * 点源总开关（三态循环）：
     * 开启 / 部分开启 → 关闭整个源（并收起分类）；
     * 关闭 → 开启源，并把该源下所有分类一次性勾上。
     */
    private fun onSourceClick(pos: Int) {
        val row = items[pos] as? Row.Source ?: return
        configChanged = true
        val url = row.source.bookSourceUrl
        if (row.state == SourceStateCircleView.State.ON) {
            // 关闭 = 该源所有分类一起取消勾选（总开关是分类的聚合，不能只关源不关分类，
            // 否则分类还勾着、总开关又显示成关闭，两边不一致）
            categoriesOf(url).forEach {
                InfiniteSwipeFilter.setCategoryEnabled(url, it.title, false)
            }
            InfiniteSwipeFilter.setSourceEnabled(url, false)
            row.state = SourceStateCircleView.State.OFF
            // 整源关掉就收起分类行（下面已经全取消了，留着也没意义）
            val removed = removeCategories(pos)
            if (removed > 0) {
                row.expanded = false
                adapter.notifyItemRangeRemoved(pos + 1, removed)
            }
        } else {
            InfiniteSwipeFilter.setSourceEnabled(url, true)
            categoriesOf(url).forEach {
                InfiniteSwipeFilter.setCategoryEnabled(url, it.title, true)
            }
            row.state = sourceState(url)
            refreshExpandedCategories(pos)
        }
        adapter.notifyItemChanged(pos)
    }

    private fun onCategoryToggle(pos: Int, enabled: Boolean) {
        val row = items[pos] as? Row.Category ?: return
        configChanged = true
        InfiniteSwipeFilter.setCategoryEnabled(row.sourceUrl, row.kind.title, enabled)
        // 分类变了，源开关要跟着同步（全勾→开启，勾一部分→开启且源可用，全不勾→源关闭）
        syncSourceEnabled(row.sourceUrl)
        row.enabled = enabled
        adapter.notifyItemChanged(pos)
        // 分类变了，所属源的三态也要跟着变（全勾→开启，勾一部分→部分开启，全不勾→关闭）
        val parent = parentSourcePos(pos)
        if (parent >= 0) {
            val p = items[parent] as? Row.Source ?: return
            val newState = sourceState(p.source.bookSourceUrl)
            if (newState != p.state) {
                p.state = newState
                adapter.notifyItemChanged(parent)
            }
        }
    }

    /** 向上找 pos 所属源行的下标 */
    private fun parentSourcePos(pos: Int): Int {
        for (i in pos - 1 downTo 0) {
            if (items[i] is Row.Source) return i
            if (items[i] !is Row.Category) break
        }
        return -1
    }

    /** 源开关变化后，把已展开的分类行开关同步成最新配置 */
    private fun refreshExpandedCategories(pos: Int) {
        val row = items[pos] as? Row.Source ?: return
        if (!row.expanded) return
        var i = pos + 1
        var changed = 0
        while (i < items.size) {
            val r = items[i] as? Row.Category ?: break
            if (r.sourceUrl != row.source.bookSourceUrl) break
            r.enabled = InfiniteSwipeFilter.isCategoryChecked(r.sourceUrl, r.kind.title)
            changed++
            i++
        }
        if (changed > 0) adapter.notifyItemRangeChanged(pos + 1, changed)
    }

    /** 全部展开 / 折叠 */
    private fun setAllExpanded(expand: Boolean) {
        val newItems = mutableListOf<Row>()
        for (src in sourceList) {
            val state = sourceState(src.bookSourceUrl)
            val doExpand = expand && state != SourceStateCircleView.State.OFF
            newItems.add(Row.Source(src, state, doExpand))
            if (doExpand) {
                newItems.addAll(
                    categoriesOf(src.bookSourceUrl).map {
                        Row.Category(
                            src.bookSourceUrl,
                            it,
                            InfiniteSwipeFilter.isCategoryChecked(src.bookSourceUrl, it.title),
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
        configChanged = true
        for (src in sourceList) {
            // 分类与源开关一起设置：全开时全部勾上，全关时全部取消（保持聚合一致）
            categoriesOf(src.bookSourceUrl).forEach {
                InfiniteSwipeFilter.setCategoryEnabled(src.bookSourceUrl, it.title, on)
            }
            InfiniteSwipeFilter.setSourceEnabled(src.bookSourceUrl, on)
        }
        rebuildItems()
        adapter.notifyDataSetChanged()
    }

    private class FilterAdapter(
        private val items: MutableList<Row>,
        private val onSourceClick: (Int) -> Unit,
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
                    holder.state.state = row.state
                    // 开关自己吃掉点击（行整体的点击是展开/折叠）
                    holder.state.setOnClickListener { onSourceClick(position) }
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
            val state: SourceStateCircleView = view.findViewById(R.id.state_view)
        }

        class CategoryVH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_name)
            val switch: SwitchCompat = view.findViewById(R.id.switch_enabled)
        }
    }
}
