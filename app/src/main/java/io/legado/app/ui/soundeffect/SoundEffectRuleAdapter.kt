package io.legado.app.ui.soundeffect

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.databinding.ItemReplaceGroupBinding
import io.legado.app.databinding.ItemSoundEffectRuleBinding
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.setTintMutate

sealed class SoundEffectFlatItem {
    data class GroupHeader(
        val groupId: Long,
        val name: String,
        val ruleCount: Int,
        val enabledCount: Int,
        val isExpanded: Boolean
    ) : SoundEffectFlatItem()

    data class RuleItem(
        val groupId: Long,
        val rule: SoundEffectRule
    ) : SoundEffectFlatItem()
}

/**
 * 音效规则列表适配器。
 *
 * 列表结构是「分组 + 组内规则」，与规则 JSON 文件的结构一一对应：
 * 分组的先后顺序即规则执行顺序，组内按 order 升序执行。
 */
class SoundEffectRuleAdapter(
    private val context: Context,
    var callBack: CallBack
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), ItemTouchCallback.Callback {

    companion object {
        private const val TYPE_GROUP = 0
        private const val TYPE_RULE = 1
    }

    private val inflater = android.view.LayoutInflater.from(context)

    /**
     * AI 音效（广播剧音效库）模式。
     * 该模式下列表展示的是音效库目录，而非规则：
     * 分组名是「分类/子分类」，条目名是音效名、pattern 是音效相对路径。
     * 试听直接按路径取文件，不走规则里的 JSON 索引轮换。
     */
    var aiMode: Boolean = false

    /** 当前模式的完整数据，直接被增删改操作就地修改 */
    var data: MutableList<SoundEffectGroupWithRules> = mutableListOf()
        private set

    private var flatItems = listOf<SoundEffectFlatItem>()

    val expandedGroups = linkedSetOf<Long>()
    private val selected = linkedSetOf<SoundEffectRule>()

    /** 搜索状态下命中的规则 id，为空表示未搜索 */
    private var matchedRuleIds: Set<Long>? = null

    val selection: List<SoundEffectRule>
        get() = selected.toList()

    val ruleItemCount: Int
        get() = data.sumOf { it.list.size }

    fun submit(
        data: MutableList<SoundEffectGroupWithRules>,
        searchKey: String,
        keepExpanded: Boolean = true
    ) {
        if (!keepExpanded) expandedGroups.clear()
        this.data = data
        matchedRuleIds = if (searchKey.isBlank()) null else matchRules(searchKey)
        // 搜索时展开所有有命中的分组
        if (matchedRuleIds != null) {
            data.forEach { gwr ->
                if (gwr.list.any { matchedRuleIds!!.contains(it.id) }) {
                    expandedGroups.add(gwr.group.id)
                }
            }
        }
        rebuild()
    }

    private fun matchRules(key: String): Set<Long> {
        val lower = key.trim().lowercase()
        if (lower.isEmpty()) return emptySet()
        val result = hashSetOf<Long>()
        data.forEach { gwr ->
            val groupHit = gwr.group.name.lowercase().contains(lower)
            gwr.list.forEach { rule ->
                if (groupHit
                    || rule.name.lowercase().contains(lower)
                    || rule.pattern.lowercase().contains(lower)
                    || rule.replacement.lowercase().contains(lower)
                    // AI 模式下这里存的是检索关键词，一起搜，方便按同义词找音效
                    || rule.sampleText.lowercase().contains(lower)
                ) {
                    result.add(rule.id)
                }
            }
        }
        return result
    }

    private fun rebuild() {
        val result = mutableListOf<SoundEffectFlatItem>()
        data.forEach { gwr ->
            val visibleRules = if (matchedRuleIds == null) {
                gwr.list
            } else {
                gwr.list.filter { matchedRuleIds!!.contains(it.id) }
            }
            // 搜索时隐藏没有任何命中的分组
            if (matchedRuleIds != null && visibleRules.isEmpty()) return@forEach
            val enabledCount = gwr.list.count { it.isEnabled }
            val isExpanded = matchedRuleIds != null || expandedGroups.contains(gwr.group.id)
            result.add(
                SoundEffectFlatItem.GroupHeader(
                    groupId = gwr.group.id,
                    name = gwr.group.name,
                    ruleCount = gwr.list.size,
                    enabledCount = enabledCount,
                    isExpanded = isExpanded
                )
            )
            if (isExpanded) {
                visibleRules.forEach { rule ->
                    result.add(SoundEffectFlatItem.RuleItem(gwr.group.id, rule))
                }
            }
        }
        flatItems = result
        notifyDataSetChanged()
        callBack.upCountView()
    }

    fun toggleGroup(groupId: Long) {
        if (!expandedGroups.add(groupId)) expandedGroups.remove(groupId)
        rebuild()
    }

    fun expandAll() {
        data.forEach { expandedGroups.add(it.group.id) }
        rebuild()
    }

    fun collapseAll() {
        expandedGroups.clear()
        rebuild()
    }

    fun selectAll() {
        data.forEach { gwr -> selected.addAll(gwr.list) }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun revertSelection() {
        val all = data.flatMapTo(mutableListOf()) { it.list }
        all.forEach { if (!selected.add(it)) selected.remove(it) }
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    fun clearSelection() {
        selected.clear()
        notifyItemRangeChanged(0, itemCount, bundleOf(Pair("selected", null)))
        callBack.upCountView()
    }

    /**
     * 列表中的规则对象被 copy() 替换后（例如改启用状态），
     * 选中集合里保存的还是旧对象，按数据类相等比较会失配，
     * 这里按 id 重新对齐。
     */
    fun resyncSelection() {
        if (selected.isEmpty()) return
        val ids = selected.mapTo(HashSet()) { it.id }
        selected.clear()
        data.forEach { gwr ->
            gwr.list.forEach { rule -> if (ids.contains(rule.id)) selected.add(rule) }
        }
    }

    fun isGroupHeader(position: Int): Boolean =
        flatItems.getOrNull(position) is SoundEffectFlatItem.GroupHeader

    override fun getItemCount() = flatItems.size

    override fun getItemViewType(position: Int): Int =
        when (flatItems[position]) {
            is SoundEffectFlatItem.GroupHeader -> TYPE_GROUP
            is SoundEffectFlatItem.RuleItem -> TYPE_RULE
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        when (viewType) {
            TYPE_GROUP -> GroupViewHolder(
                ItemReplaceGroupBinding.inflate(inflater, parent, false)
            )
            else -> RuleViewHolder(
                ItemSoundEffectRuleBinding.inflate(inflater, parent, false)
            )
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        onBindViewHolder(holder, position, mutableListOf())
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        when (val item = flatItems[position]) {
            is SoundEffectFlatItem.GroupHeader -> (holder as GroupViewHolder).bind(item, payloads)
            is SoundEffectFlatItem.RuleItem -> (holder as RuleViewHolder).bind(item, payloads)
        }
    }

    inner class GroupViewHolder(private val binding: ItemReplaceGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.GroupHeader ?: return@setOnClickListener
                toggleGroup(item.groupId)
            }
            binding.ivGroupStatus.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.GroupHeader ?: return@setOnClickListener
                val newChecked = when {
                    item.enabledCount == 0 -> true
                    item.enabledCount == item.ruleCount -> false
                    else -> true
                }
                callBack.onGroupEnableToggle(item.groupId, newChecked)
            }
            binding.ivGroupMore.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.GroupHeader ?: return@setOnClickListener
                showGroupMenu(it, item.groupId)
            }
        }

        fun bind(item: SoundEffectFlatItem.GroupHeader, payloads: MutableList<Any>) {
            if (payloads.isEmpty() || payloads.any { it == null }) {
                binding.tvGroupName.text = "${item.name}(${item.ruleCount})"
                binding.ivExpand.rotation = if (item.isExpanded) 180f else 0f
            }
            binding.ivGroupStatus.setImageResource(
                when {
                    item.ruleCount == 0 || item.enabledCount == 0 -> R.drawable.ic_checkbox_empty
                    item.enabledCount == item.ruleCount -> R.drawable.ic_checkbox_checked
                    else -> R.drawable.ic_checkbox_indeterminate
                }
            )
        }
    }

    /** 试听一条。AI 模式按相对路径取文件，规则模式解析音效标记 */
    private fun playItem(item: SoundEffectFlatItem.RuleItem, cb: CallBack) {
        if (aiMode) {
            SoundEffectPlayer.playAiEffect(item.rule.pattern) { error ->
                error?.let { cb.onPlayError(it) }
            }
        } else {
            SoundEffectPlayer.play(item.rule.replacement) { error ->
                error?.let { cb.onPlayError(it) }
            }
        }
    }

    inner class RuleViewHolder(private val binding: ItemSoundEffectRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.ivPlay.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.RuleItem ?: return@setOnClickListener
                playItem(item, callBack)
            }
            binding.root.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.RuleItem ?: return@setOnClickListener
                // AI 模式没有「编辑规则」的概念，整行点击直接试听
                if (aiMode) playItem(item, callBack) else callBack.edit(item.rule)
            }
            binding.swtEnabled.setOnCheckedChangeListener { buttonView, isChecked ->
                if (!buttonView.isPressed) return@setOnCheckedChangeListener
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.RuleItem ?: return@setOnCheckedChangeListener
                callBack.update(item.rule.copy(isEnabled = isChecked))
            }
            binding.ivEdit.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.RuleItem ?: return@setOnClickListener
                // 编辑入口在 Activity 里按模式分流：AI 模式改名，规则模式打开编辑页
                callBack.edit(item.rule)
            }
            binding.cbName.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.RuleItem ?: return@setOnClickListener
                if (binding.cbName.isChecked) selected.add(item.rule) else selected.remove(item.rule)
                callBack.upCountView()
            }
            binding.ivMenuMore.setOnClickListener {
                val item = flatItems.getOrNull(bindingAdapterPosition)
                        as? SoundEffectFlatItem.RuleItem ?: return@setOnClickListener
                showRuleMenu(it, item.rule)
            }
        }

        fun bind(item: SoundEffectFlatItem.RuleItem, payloads: MutableList<Any>) {
            binding.run {
                if (payloads.isEmpty() || payloads.any { it == null }) {
                    root.setBackgroundColor(ColorUtils.withAlpha(context.backgroundColor, 0.5f))
                    cbName.text = item.rule.name.ifBlank { item.rule.pattern }
                    swtEnabled.isChecked = item.rule.isEnabled
                    cbName.isChecked = selected.contains(item.rule)
                } else {
                    payloads.forEach { payload ->
                        val bundle = payload as? Bundle ?: return@forEach
                        bundle.keySet().forEach { key ->
                            when (key) {
                                "selected" -> cbName.isChecked = selected.contains(item.rule)
                                "upName" -> cbName.text =
                                    item.rule.name.ifBlank { item.rule.pattern }

                                "enabled" -> swtEnabled.isChecked = item.rule.isEnabled
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showGroupMenu(view: View, groupId: Long) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.sound_effect_rule_group)
        // AI 音效是目录索引，不是规则分组，不能暂存成规则导入到其它模式
        popupMenu.menu.findItem(R.id.menu_group_export_clipboard)?.isVisible = !aiMode
        // PopupMenu 默认不显示 item 图标，这里用反射打开图标显示
        // 注意：不能用 applyTint（它强转 androidx MenuItemImpl，对系统 PopupMenu 会 ClassCastException）
        popupMenu.menu.applyOpenTint(context)
        // 图标本身是固定色（ic_delete 为白色），反射着色在高版本系统可能失效，
        // 这里显式按主题文字色着色：白天黑色、深色主题白色
        val iconTint = context.getCompatColor(R.color.primaryText)
        popupMenu.menu.findItem(R.id.menu_group_export_clipboard)?.icon?.setTintMutate(iconTint)
        popupMenu.menu.findItem(R.id.menu_group_del)?.icon?.setTintMutate(iconTint)
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_group_top -> callBack.onGroupToTop(groupId)
                R.id.menu_group_up -> callBack.onGroupUp(groupId)
                R.id.menu_group_down -> callBack.onGroupDown(groupId)
                R.id.menu_group_bottom -> callBack.onGroupToBottom(groupId)
                R.id.menu_group_rename -> callBack.onGroupRename(groupId)
                R.id.menu_group_add_rule -> callBack.onGroupAddRule(groupId)
                R.id.menu_group_export_clipboard -> callBack.onGroupExportClipboard(groupId)
                R.id.menu_group_del -> callBack.onDeleteGroup(groupId)
            }
            true
        }
        popupMenu.show()
    }

    private fun showRuleMenu(view: View, rule: SoundEffectRule) {
        val popupMenu = PopupMenu(context, view)
        popupMenu.inflate(R.menu.sound_effect_rule_item)
        // AI 音效是裸 MP3，没有 audios JSON，不提供增删；保留原来的音量调整
        popupMenu.menu.findItem(R.id.menu_edit_sound_files)?.isVisible = !aiMode
        popupMenu.menu.findItem(R.id.menu_adjust_volume)?.isVisible = aiMode
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_top -> callBack.toTop(rule)
                R.id.menu_bottom -> callBack.toBottom(rule)
                R.id.menu_edit_sound_files -> callBack.editSoundFiles(rule)
                R.id.menu_adjust_volume -> callBack.adjustVolume(rule)
                R.id.menu_del -> {
                    callBack.delete(rule)
                    selected.remove(rule)
                }
            }
            true
        }
        popupMenu.show()
    }

    private var dragDirty = false

    override fun swap(srcPosition: Int, targetPosition: Int): Boolean {
        val src = flatItems.getOrNull(srcPosition) ?: return false
        val target = flatItems.getOrNull(targetPosition) ?: return false

        // 规则之间拖动：仅限同一分组内
        if (src is SoundEffectFlatItem.RuleItem && target is SoundEffectFlatItem.RuleItem) {
            if (src.groupId != target.groupId) return false
            val gwr = data.find { it.group.id == src.groupId } ?: return false
            val from = gwr.list.indexOfFirst { it.id == src.rule.id }
            val to = gwr.list.indexOfFirst { it.id == target.rule.id }
            if (from < 0 || to < 0) return false
            val moved = gwr.list.removeAt(from)
            gwr.list.add(to, moved)
            SoundEffectRuleManager.renumberRules(gwr.list)
            val mutable = flatItems.toMutableList()
            mutable[srcPosition] = target
            mutable[targetPosition] = src
            flatItems = mutable
            notifyItemMoved(srcPosition, targetPosition)
            dragDirty = true
            return true
        }

        // 分组之间拖动
        if (src is SoundEffectFlatItem.GroupHeader && target is SoundEffectFlatItem.GroupHeader) {
            val from = data.indexOfFirst { it.group.id == src.groupId }
            val to = data.indexOfFirst { it.group.id == target.groupId }
            if (from < 0 || to < 0) return false
            val moved = data.removeAt(from)
            data.add(to, moved)
            val mutable = flatItems.toMutableList()
            mutable[srcPosition] = target
            mutable[targetPosition] = src
            flatItems = mutable
            notifyItemMoved(srcPosition, targetPosition)
            dragDirty = true
            return true
        }

        return false
    }

    override fun onClearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        if (dragDirty) {
            dragDirty = false
            callBack.onDataChanged()
        }
    }

    interface CallBack {
        fun update(vararg rule: SoundEffectRule)
        fun delete(rule: SoundEffectRule)
        fun edit(rule: SoundEffectRule)
        fun toTop(rule: SoundEffectRule)
        fun toBottom(rule: SoundEffectRule)
        fun editSoundFiles(rule: SoundEffectRule)
        fun adjustVolume(rule: SoundEffectRule)
        fun upCountView()
        fun onPlayError(message: String)
        fun onDataChanged()
        fun onGroupEnableToggle(groupId: Long, enable: Boolean)
        fun onGroupToTop(groupId: Long)
        fun onGroupToBottom(groupId: Long)
        fun onGroupUp(groupId: Long)
        fun onGroupDown(groupId: Long)
        fun onGroupRename(groupId: Long)
        fun onGroupAddRule(groupId: Long)
        fun onGroupExportClipboard(groupId: Long)
        fun onDeleteGroup(groupId: Long)
    }
}
