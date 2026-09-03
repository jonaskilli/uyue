package io.legado.app.ui.soundeffect

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivitySoundEffectRuleBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.ui.soundeffect.SoundEffectRuleEditViewModel.Companion.EXTRA_GROUP_ID
import io.legado.app.ui.soundeffect.SoundEffectRuleEditViewModel.Companion.EXTRA_RULE
import io.legado.app.utils.applyTint
import io.legado.app.utils.readText
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音效规则管理。
 *
 * 顶部「全部音效 / 普通音效」决定编辑的是哪一个规则文件：
 * - 全部音效 → ttsrv-replaces3.json
 * - 普通音效 → ttsrv-replaces5.json
 *
 * 打开界面时两个文件都会检查，缺失的自动下载。
 * 规则只保存在 JSON 文件中（朗读服务直接读文件），因此每次改动都即时写回。
 */
class SoundEffectRuleActivity :
    VMBaseActivity<ActivitySoundEffectRuleBinding, SoundEffectRuleViewModel>(),
    SearchView.OnQueryTextListener,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    SoundEffectRuleAdapter.CallBack {

    override val binding by viewBinding(ActivitySoundEffectRuleBinding::inflate)
    override val viewModel by viewModels<SoundEffectRuleViewModel>()

    private val adapter by lazy { SoundEffectRuleAdapter(this, this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }

    private var busy = false

    private val editRule =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            @Suppress("DEPRECATION")
            val rule = data.getParcelableExtra(EXTRA_RULE)
                    as? SoundEffectRule ?: return@registerForActivityResult
            val groupId = data.getLongExtra(EXTRA_GROUP_ID, -1L)
            applyEditedRule(rule, groupId)
        }

    private val importDoc = registerForActivityResult(HandleFileContract()) { result ->
        val uri = result.uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val text = withContext(IO) {
                runCatching { uri.readText(this@SoundEffectRuleActivity) }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                toastOnUi("读取文件失败")
                return@launch
            }
            val parsed = withContext(IO) {
                runCatching { SoundEffectRuleManager.parseJson(text) }.getOrNull()
            }
            if (parsed == null) {
                toastOnUi("不是有效的规则文件")
                return@launch
            }
            confirmImport(parsed)
        }
    }

    private val exportDoc = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi("导出成功")
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSearchView()
        initSelectActionView()
        initModeToggle()
        prepare()
    }

    // ---------------- 初始化 ----------------

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        val itemTouchCallback = ItemTouchCallback(adapter).apply { isCanDrag = true }
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = "搜索名称 / 匹配 / 替换为 / 分组"
        searchView.setOnQueryTextListener(this)
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.sound_effect_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initModeToggle() {
        binding.rgMode.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.rb_mode_normal) {
                SoundEffectRuleManager.MODE_NORMAL
            } else {
                SoundEffectRuleManager.MODE_ALL
            }
            if (newMode != viewModel.mode) switchMode(newMode)
        }
    }

    // ---------------- 载入 / 切换模式 ----------------

    private fun prepare() {
        showLoading("正在检查规则文件…")
        viewModel.prepare(
            onProgress = { showLoading(it) },
            onDone = { error ->
                hideLoading()
                error?.let { toastOnUi(it) }
                upPathView()
                refreshList()
            }
        )
    }

    private fun switchMode(newMode: String) {
        if (busy) {
            revertToggle()
            return
        }
        busy = true
        showLoading("正在保存…")
        viewModel.save { saveError ->
            if (saveError != null) {
                busy = false
                hideLoading()
                toastOnUi(saveError)
                revertToggle()
                return@save
            }
            viewModel.switchMode(newMode) { error ->
                busy = false
                hideLoading()
                error?.let { toastOnUi(it) }
                adapter.clearSelection()
                searchView.setQuery("", false)
                upPathView()
                refreshList(keepExpanded = false)
            }
        }
    }

    private fun revertToggle() {
        binding.rgMode.check(
            if (viewModel.mode == SoundEffectRuleManager.MODE_NORMAL) {
                R.id.rb_mode_normal
            } else {
                R.id.rb_mode_all
            }
        )
    }

    private fun upPathView() {
        binding.tvPath.text = SoundEffectRuleManager.fileOf(viewModel.mode).absolutePath
    }

    private fun refreshList(keepExpanded: Boolean = true) {
        adapter.submit(viewModel.data, searchView.query?.toString().orEmpty(), keepExpanded)
    }

    private fun showLoading(text: String) {
        binding.llLoading.visibility = View.VISIBLE
        binding.tvLoading.text = text
    }

    private fun hideLoading() {
        binding.llLoading.visibility = View.GONE
    }

    // ---------------- 菜单 ----------------

    override fun onCompatCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.sound_effect_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_rule -> startEdit(null, defaultGroupId())
            R.id.menu_add_group -> showAddGroupDialog()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("json", "txt")
            }
            R.id.menu_export -> exportCurrent()
            R.id.menu_expand_all -> adapter.expandAll()
            R.id.menu_collapse_all -> adapter.collapseAll()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        val selection = adapter.selection
        when (item?.itemId) {
            R.id.menu_enable_selection -> setSelectionEnabled(selection, true)
            R.id.menu_disable_selection -> setSelectionEnabled(selection, false)
            R.id.menu_del_selection -> confirmDeleteSelection(selection)
        }
        return false
    }

    // ---------------- SelectActionBar ----------------

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) adapter.selectAll() else adapter.revertSelection()
    }

    override fun revertSelection() = adapter.revertSelection()

    override fun onClickSelectBarMainAction() = confirmDeleteSelection(adapter.selection)

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selection.size, adapter.ruleItemCount)
    }

    override fun onPlayError(message: String) = toastOnUi(message)

    // ---------------- 查找辅助 ----------------

    private fun findGroup(groupId: Long): SoundEffectGroupWithRules? =
        viewModel.data.find { it.group.id == groupId }

    private fun findGroupOfRule(ruleId: Long): SoundEffectGroupWithRules? =
        viewModel.data.find { gwr -> gwr.list.any { it.id == ruleId } }

    /** 没有分组时先建一个默认分组，保证规则总有归属 */
    private fun defaultGroupId(): Long {
        viewModel.data.firstOrNull()?.let { return it.group.id }
        val group = SoundEffectRuleGroup(
            id = SoundEffectRuleManager.newGroupId(viewModel.data),
            name = SoundEffectRuleManager.DEFAULT_GROUP_NAME
        )
        viewModel.data.add(SoundEffectGroupWithRules(group, mutableListOf()))
        return group.id
    }

    // ---------------- 规则增删改 ----------------

    private fun startEdit(rule: SoundEffectRule?, groupId: Long) {
        val ids = viewModel.data.map { it.group.id }.toLongArray()
        val names = viewModel.data.map { it.group.name }.toTypedArray()
        editRule.launch(SoundEffectRuleEditActivity.startIntent(this, rule, groupId, ids, names))
    }

    private fun applyEditedRule(rule: SoundEffectRule, groupId: Long) {
        val current = findGroupOfRule(rule.id)
        when {
            current == null -> {
                // 新增
                val target = findGroup(groupId)
                    ?: findGroup(defaultGroupId())
                    ?: return
                target.list.add(rule)
                SoundEffectRuleManager.renumberRules(target.list)
            }

            current.group.id != groupId -> {
                // 换了分组
                current.list.removeAll { it.id == rule.id }
                val target = findGroup(groupId) ?: current
                target.list.add(rule)
                SoundEffectRuleManager.renumberRules(current.list)
                SoundEffectRuleManager.renumberRules(target.list)
            }

            else -> {
                val index = current.list.indexOfFirst { it.id == rule.id }
                if (index >= 0) current.list[index] = rule
            }
        }
        onDataChanged()
    }

    override fun update(vararg rule: SoundEffectRule) {
        var changed = false
        rule.forEach { r ->
            val gwr = findGroupOfRule(r.id) ?: return@forEach
            val index = gwr.list.indexOfFirst { it.id == r.id }
            if (index >= 0) {
                gwr.list[index] = r
                changed = true
            }
        }
        if (changed) onDataChanged()
    }

    override fun edit(rule: SoundEffectRule) {
        startEdit(rule, findGroupOfRule(rule.id)?.group?.id ?: defaultGroupId())
    }

    override fun delete(rule: SoundEffectRule) {
        alert("删除规则", rule.name.ifBlank { rule.pattern }) {
            noButton()
            yesButton { removeRule(rule.id) }
        }
    }

    private fun removeRule(ruleId: Long) {
        val gwr = findGroupOfRule(ruleId) ?: return
        gwr.list.removeAll { it.id == ruleId }
        SoundEffectRuleManager.renumberRules(gwr.list)
        onDataChanged()
    }

    private fun confirmDeleteSelection(selection: List<SoundEffectRule>) {
        if (selection.isEmpty()) return
        alert("删除规则", "确定删除选中的 ${selection.size} 条规则？") {
            noButton()
            yesButton {
                val ids = selection.mapTo(HashSet()) { it.id }
                viewModel.data.forEach { gwr ->
                    gwr.list.removeAll { ids.contains(it.id) }
                    SoundEffectRuleManager.renumberRules(gwr.list)
                }
                adapter.clearSelection()
                onDataChanged()
            }
        }
    }

    private fun setSelectionEnabled(selection: List<SoundEffectRule>, enabled: Boolean) {
        if (selection.isEmpty()) return
        val ids = selection.mapTo(HashSet()) { it.id }
        var changed = false
        viewModel.data.forEach { gwr ->
            gwr.list.forEachIndexed { index, rule ->
                if (ids.contains(rule.id) && rule.isEnabled != enabled) {
                    gwr.list[index] = rule.copy(isEnabled = enabled)
                    changed = true
                }
            }
        }
        if (changed) onDataChanged()
    }

    override fun toTop(rule: SoundEffectRule) {
        val gwr = findGroupOfRule(rule.id) ?: return
        val index = gwr.list.indexOfFirst { it.id == rule.id }
        if (index > 0) {
            gwr.list.removeAt(index)
            gwr.list.add(0, rule)
            SoundEffectRuleManager.renumberRules(gwr.list)
            onDataChanged()
        }
    }

    override fun toBottom(rule: SoundEffectRule) {
        val gwr = findGroupOfRule(rule.id) ?: return
        val index = gwr.list.indexOfFirst { it.id == rule.id }
        if (index >= 0 && index != gwr.list.lastIndex) {
            gwr.list.removeAt(index)
            gwr.list.add(rule)
            SoundEffectRuleManager.renumberRules(gwr.list)
            onDataChanged()
        }
    }

    /** 任何改动都走这里：即时写回文件并刷新列表 */
    override fun onDataChanged() {
        viewModel.save { it?.let { msg -> toastOnUi(msg) } }
        adapter.resyncSelection()
        refreshList()
    }

    // ---------------- 分组操作 ----------------

    override fun onGroupEnableToggle(groupId: Long, enable: Boolean) {
        val gwr = findGroup(groupId) ?: return
        gwr.list.forEachIndexed { index, rule ->
            if (rule.isEnabled != enable) gwr.list[index] = rule.copy(isEnabled = enable)
        }
        onDataChanged()
    }

    override fun onGroupToTop(groupId: Long) = moveGroup(groupId, 0)

    override fun onGroupToBottom(groupId: Long) = moveGroup(groupId, viewModel.data.lastIndex)

    override fun onGroupUp(groupId: Long) {
        val index = indexOfGroup(groupId)
        if (index > 0) moveGroup(groupId, index - 1)
    }

    override fun onGroupDown(groupId: Long) {
        val index = indexOfGroup(groupId)
        if (index >= 0 && index < viewModel.data.lastIndex) moveGroup(groupId, index + 1)
    }

    private fun indexOfGroup(groupId: Long) = viewModel.data.indexOfFirst { it.group.id == groupId }

    private fun moveGroup(groupId: Long, toIndex: Int) {
        val index = indexOfGroup(groupId)
        if (index < 0 || toIndex < 0 || toIndex >= viewModel.data.size || index == toIndex) return
        val moved = viewModel.data.removeAt(index)
        viewModel.data.add(toIndex, moved)
        onDataChanged()
    }

    override fun onGroupRename(groupId: Long) {
        val gwr = findGroup(groupId) ?: return
        showInputDialog("重命名分组", gwr.group.name) { text ->
            gwr.group.name = text
            onDataChanged()
        }
    }

    override fun onGroupAddRule(groupId: Long) = startEdit(null, groupId)

    override fun onDeleteGroup(groupId: Long) {
        val gwr = findGroup(groupId) ?: return
        alert("删除分组", "删除分组「${gwr.group.name}」及其中的 ${gwr.list.size} 条规则？") {
            noButton()
            yesButton {
                viewModel.data.removeAll { it.group.id == groupId }
                onDataChanged()
            }
        }
    }

    private fun showAddGroupDialog() = showInputDialog("新建分组", "") { text ->
        val group = SoundEffectRuleGroup(
            id = SoundEffectRuleManager.newGroupId(viewModel.data),
            name = text
        )
        viewModel.data.add(SoundEffectGroupWithRules(group, mutableListOf()))
        onDataChanged()
    }

    @SuppressLint("InflateParams")
    private fun showInputDialog(
        title: String,
        default: String,
        onConfirm: (String) -> Unit
    ) {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setText(default)
            editView.setSelection(default.length)
        }
        alert(title) {
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    toastOnUi("名称不能为空")
                    return@okButton
                }
                onConfirm(text)
            }
            cancelButton()
        }
    }

    // ---------------- 导入 / 导出 ----------------

    private fun exportCurrent() {
        showLoading("正在生成文件…")
        lifecycleScope.launch {
            val json = withContext(IO) { SoundEffectRuleManager.toJson(viewModel.data) }
            hideLoading()
            exportDoc.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    SoundEffectRuleManager.fileNameOf(viewModel.mode),
                    json.toByteArray(),
                    "application/json"
                )
            }
        }
    }

    private fun confirmImport(parsed: List<SoundEffectGroupWithRules>) {
        val count = parsed.sumOf { it.list.size }
        alert("导入规则", "将用导入的 ${parsed.size} 个分组、${count} 条规则覆盖当前的规则，是否继续？") {
            noButton()
            yesButton {
                viewModel.replaceAll(parsed)
                adapter.clearSelection()
                onDataChanged()
            }
        }
    }

    // ---------------- 搜索 ----------------

    override fun onQueryTextChange(newText: String?): Boolean {
        adapter.submit(viewModel.data, newText.orEmpty(), keepExpanded = false)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean = false

    override fun onDestroy() {
        super.onDestroy()
        SoundEffectPlayer.stop()
    }
}
