package io.legado.app.ui.tts.plugin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.widget.Switch
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityTtsPluginBinding
import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.help.audiobook.PluginLog
import io.legado.app.help.audiobook.PostAudioParams
import io.legado.app.help.audiobook.TtsSynthLog
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.LogUtils
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Date
import java.util.UUID

class TtsPluginActivity : BaseActivity<ActivityTtsPluginBinding>() {

    override val binding by viewBinding(ActivityTtsPluginBinding::inflate)
    private val adapter by lazy { TtsPluginAdapter() }
    private val logAdapter by lazy { PluginLogAdapter(this) }
    private var currentTab = TAB_CONFIGS
    /** 上次装载日志时的指纹（AppLog 条数 + PluginLog 版本 + 开关状态），用于跳过无变化的轮询 */
    private var logSignature = -1
    /** 日志 Tab 可见时的自动刷新轮询：AppLog / PluginLog 都在内存里，没有监听器，只能轮询 */
    private val logPollRunnable = object : Runnable {
        override fun run() {
            refreshLogs()
            binding.logContainer.postDelayed(this, LOG_POLL_INTERVAL_MS)
        }
    }
    private var allConfigs: List<JReadVoiceEngine.VoiceConfig> = emptyList()
    private var allPlugins: List<JReadVoiceEngine.VoicePlugin> = emptyList()
    private var allGroups: List<JReadVoiceEngine.VoiceGroup> = emptyList()
    private var pluginsMap: Map<String, JReadVoiceEngine.VoicePlugin> = emptyMap()
    private var expandedGroups = mutableSetOf<String>()
    private var expandedSubGroups = mutableSetOf<Pair<String, String>>()
    private var searchQuery = ""
    private var mediaPlayer: MediaPlayer? = null

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { doImport(it) } }
    /** 正在导出的单个插件；null 表示走整体导出（见 doExport） */
    private var pendingExportPlugin: JReadVoiceEngine.VoicePlugin? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { doExport(it) } }
    // 编辑/新增配置保存后回传，父页面据此刷新内置发音人列表缓存
    private val configEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) invalidateEnabledTagsCache()
    }
    // 变量设置 / 预览UI / 音频参数 / 清空数据：与插件代码编辑器共用同一套对话框实现，
    // 避免列表页和编辑器各写一份
    private val pluginDialogs = PluginEditDialogs(this)
    // 新增/编辑插件统一走 PluginEditorActivity（同一个界面，只以插件 id 区分）
    private val pluginEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        // 编辑器只回传 id（整包 JSON 会撑爆 Binder，见 PluginEditorActivity 契约说明）
        val id = result.data?.getStringExtra(PluginEditorActivity.RESULT_PLUGIN_ID)
        if (id.isNullOrBlank()) {
            reloadListsLight()
            return@registerForActivityResult
        }
        val name = JReadVoiceEngine.listPlugins(this).firstOrNull { it.id == id }?.name
        toastOnUi("已保存插件: ${name ?: id}")
        reloadListsLight()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        // 卡片自带描边 + 阴影做条目分隔，不再加横向分隔线（横线叠在卡片上会显得脏）
        // 长按拖拽排序配置项
        adapter.onConfigMoved = { orderedIds ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { JReadVoiceEngine.saveConfigsSortOrder(this@TtsPluginActivity, orderedIds) }
                // 同步内存态：onResume 的快照判断只看 configs/plugins，若写盘后内容指纹没变
                // 就不会重新读盘，此时仍用旧的 allConfigs 重建会把刚拖动的结果还原回去
                val idToOrder = orderedIds.mapIndexed { index, id -> id to index }.toMap()
                allConfigs = allConfigs.map { config ->
                    val order = idToOrder[config.id]
                    if (order != null && order != config.sortOrder) config.copy(sortOrder = order) else config
                }
                invalidateEnabledTagsCache()
            }
        }
        // 长按拖拽排序大分组/子分组：一次性提交，串行合并后只写盘一次
        adapter.onGroupsOrderChanged = { orderedGroupNames, orderedSubNames ->
            lifecycleScope.launch {
                val updated = withContext(Dispatchers.IO) {
                    applyGroupsSortOrder(this@TtsPluginActivity, orderedGroupNames, orderedSubNames)
                }
                // 落盘结果直接回填内存态，否则退出页面再进来仍按旧 sortOrder 重建
                allGroups = updated
                invalidateEnabledTagsCache()
            }
        }
        adapter.createItemTouchHelper().attachToRecyclerView(binding.recyclerView)

        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("配置列表"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("日志"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("插件管理"))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            // 切换 tab 不改数据内容，直接重建当前列表行即可，无需重新读盘
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentTab = tab.position
                // 分组只在配置列表里存在，插件管理 Tab 下要把文件夹图标收起来
                syncAddGroupMenuVisibility()
                refreshCurrentTab()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        // ---- 日志 Tab ----
        binding.rvLog.layoutManager = LinearLayoutManager(this)
        binding.rvLog.adapter = logAdapter
        // 对齐 TTS_Server_Android：不在底部时显示「回到底部」按钮，点击跳到最新日志
        binding.fabLogBottom.setOnClickListener {
            val last = logAdapter.itemCount - 1
            if (last >= 0) binding.rvLog.smoothScrollToPosition(last)
        }
        binding.rvLog.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateFabLogBottom()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                updateFabLogBottom()
            }
        })
        binding.swPluginLog.setOnCheckedChangeListener { _, _ ->
            // 开关变化必须强制重建：日志条数没变，但显示内容变了
            refreshLogs(force = true)
        }
        binding.btnCopyLog.setOnClickListener { copyLogs() }
        binding.btnClearLog.setOnClickListener { clearLogs() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s.toString().trim()
                binding.ivClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                rebuildRows()
            }
        })
        binding.ivClear.setOnClickListener { binding.etSearch.setText(""); searchQuery = ""; rebuildRows() }

        adapter.setCallbacks(
            onConfigClick = { config -> openConfigEditor(config.id, false) },
            onConfigPreview = { config -> previewConfig(config) },
            onConfigDelete = { config -> deleteConfig(config) },
            onConfigCopy = { config -> copyConfig(config) },
            onConfigExport = { config -> exportConfig(config) },
            onConfigToggle = { config, enabled -> toggleConfig(config, enabled) },
            onGroupToggle = { groupName -> toggleGroup(groupName) },
            onGroupToggleEnabled = { groupName, enabled -> setGroupEnabled(groupName, null, enabled) },
            onSubGroupToggle = { groupName, subGroupName -> toggleSubGroup(groupName, subGroupName) },
            onSubGroupToggleEnabled = { groupName, subGroupName, enabled -> setGroupEnabled(groupName, subGroupName, enabled) },
            onGroupRename = { groupName, subGroupName -> showRenameDialog(groupName, subGroupName) },
            onGroupDelete = { groupName, subGroupName -> showDeleteGroupDialog(groupName, subGroupName) },
            onGroupCopy = { groupName, subGroupName -> copyGroup(groupName, subGroupName) },
            onGroupDeleteEnabled = { groupName, subGroupName -> deleteGroupEnabledDisabled(groupName, subGroupName, enabled = true) },
            onGroupDeleteDisabled = { groupName, subGroupName -> deleteGroupEnabledDisabled(groupName, subGroupName, enabled = false) },
            onGroupReplacePlugin = { groupName, subGroupName -> showReplacePluginDialog(groupName, subGroupName) },
            onGroupOrganizeTags = { groupName, subGroupName -> showOrganizeTagsDialog(groupName, subGroupName) },
            onGroupAudioParams = { groupName, subGroupName -> showGroupAudioParamsDialog(groupName, subGroupName) },
            onGroupConvertToSub = { groupName -> convertGroupToSub(groupName) },
            onGroupConvertToGroup = { groupName, subGroupName -> convertSubToGroup(groupName, subGroupName) },
            onGroupMergeSubs = { groupName -> mergeSubGroups(groupName) },
            onPluginClick = { plugin -> showPluginOptions(plugin) },
            onPluginEdit = { plugin -> showPluginEditor(plugin) },
            onPluginVars = { plugin -> showPluginVarsDialog(plugin) },
            onPluginToggle = { plugin, enabled -> togglePlugin(plugin, enabled) },
            onPluginDelete = { plugin -> deletePlugin(plugin) },
            onPluginAudioParams = { plugin -> showPluginAudioParams(plugin) },
            onPluginExport = { plugin -> exportSinglePlugin(plugin) },
        )
        loadData()
    }

    // 上次全量加载时的数据快照版本；从编辑器返回时版本未变则跳过重读盘
    private var lastConfigSnapshot: String? = null

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                JReadVoiceEngine.voiceConfigSnapshotVersion(this@TtsPluginActivity)
            }
            if (snap != lastConfigSnapshot || allConfigs.isEmpty()) {
                // 数据有变化（首次进入/编辑保存/导入等）→ 全量加载
                lastConfigSnapshot = snap
                loadData()
            } else {
                // 无变化（如从对话框返回）→ 只重建列表行，不读盘
                refreshCurrentTab()
            }
        }
    }

    /**
     * 把列表里当前的大分组/子分组顺序落到分组记录的 sortOrder 上：一次读、一次改、一次写盘。
     *
     * 关键点：
     * 1. 必须把大分组顺序和所有子分组顺序合并后再统一写盘。原先每个分组各自
     *    "读快照 → 改 → 写" 且并发提交，后完成的写入基于旧快照，会把先写入的结果覆盖掉，
     *    表现就是分组拖动后看着生效，退出页面再进来又回到原顺序。
     * 2. 列表里的分组名是展示口径（空名已替换成"默认分组"/"默认"），落盘匹配时也统一用
     *    展示口径建索引，否则空名分组永远匹配不上，sortOrder 保持原值。
     * 3. 只存在于配置项中、还没有分组记录的大分组/子分组补建一条记录，
     *    否则新顺序无处可存。
     *
     * @return 写盘后的分组列表（已含新 sortOrder），可直接回填内存态
     */
    private fun applyGroupsSortOrder(
        context: Context,
        orderedGroupNames: List<String>,
        orderedSubNames: Map<String, List<String>>,
    ): List<JReadVoiceEngine.VoiceGroup> {
        val groupOrder = linkedMapOf<String, Int>()
        orderedGroupNames
            .map { it.trim().ifBlank { DEFAULT_GROUP_NAME } }
            .distinct()
            .forEachIndexed { index, name -> groupOrder[name] = index }
        val subOrders = linkedMapOf<String, Map<String, Int>>()
        orderedSubNames.forEach { (groupName, subNames) ->
            val subOrder = linkedMapOf<String, Int>()
            subNames
                .map { it.trim().ifBlank { DEFAULT_SUB_GROUP_NAME } }
                .distinct()
                .forEachIndexed { index, name -> subOrder[name] = index }
            subOrders[groupName.trim().ifBlank { DEFAULT_GROUP_NAME }] = subOrder
        }

        val groups = JReadVoiceEngine.listGroups(context)
        // 补建记录时把展示用分组名还原成存储口径（空分组名在存储里就是空串）：
        // 否则会同时存在 ("", "男声") 与 ("默认分组", "男声") 两条重复记录
        val rawGroupNameOf = HashMap<String, String>()
        groups.forEach { group ->
            rawGroupNameOf.putIfAbsent(group.groupName.ifBlank { DEFAULT_GROUP_NAME }, group.groupName)
        }
        fun rawGroupName(displayName: String): String {
            return rawGroupNameOf[displayName]
                ?: if (displayName == DEFAULT_GROUP_NAME) "" else displayName
        }

        val updated = ArrayList<JReadVoiceEngine.VoiceGroup>(groups.size + groupOrder.size)
        val handled = HashSet<Triple<String, String, String>>()
        groups.forEach { group ->
            val groupKey = group.groupName.ifBlank { DEFAULT_GROUP_NAME }
            val subKey = group.subGroupName.ifBlank { DEFAULT_SUB_GROUP_NAME }
            updated += if (group.thirdGroupName.isBlank()) {
                // 三级分组不在本页列表中，保持原值
                val newOrder = if (group.subGroupName.isBlank()) {
                    groupOrder[groupKey] ?: group.sortOrder
                } else {
                    subOrders[groupKey]?.get(subKey) ?: group.sortOrder
                }
                group.copy(sortOrder = newOrder)
            } else {
                group
            }
            handled += Triple(groupKey, subKey, group.thirdGroupName)
        }
        groupOrder.forEach { (groupName, order) ->
            if (handled.add(Triple(groupName, DEFAULT_SUB_GROUP_NAME, ""))) {
                updated += JReadVoiceEngine.VoiceGroup(groupName = rawGroupName(groupName), sortOrder = order)
            }
        }
        subOrders.forEach { (groupName, subOrder) ->
            subOrder.forEach { (subName, order) ->
                if (handled.add(Triple(groupName, subName, ""))) {
                    updated += JReadVoiceEngine.VoiceGroup(
                        groupName = rawGroupName(groupName),
                        subGroupName = if (subName == DEFAULT_SUB_GROUP_NAME) "" else subName,
                        sortOrder = order,
                    )
                }
            }
        }
        JReadVoiceEngine.saveGroups(context, updated)
        return updated
    }

    private fun toggleGroup(groupName: String) {
        if (expandedGroups.contains(groupName)) expandedGroups.remove(groupName) else expandedGroups.add(groupName)
        rebuildRows()
    }

    private fun toggleSubGroup(groupName: String, subGroupName: String) {
        val key = Pair(groupName, subGroupName)
        if (expandedSubGroups.contains(key)) expandedSubGroups.remove(key) else expandedSubGroups.add(key)
        rebuildRows()
    }

    private fun loadData() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.ensureBuiltInVoicePresets(this@TtsPluginActivity) }
            // 全量加载完成后记录快照版本，onResume 据此判断能否走轻量刷新
            lastConfigSnapshot = withContext(Dispatchers.IO) {
                JReadVoiceEngine.voiceConfigSnapshotVersion(this@TtsPluginActivity)
            }
            // 并行加载 plugins/groups/configs，避免串行 3 次 IO
            val pluginsDeferred = async(Dispatchers.IO) { JReadVoiceEngine.listPlugins(this@TtsPluginActivity) }
            val groupsDeferred = async(Dispatchers.IO) { JReadVoiceEngine.listGroups(this@TtsPluginActivity) }
            val configsDeferred = async(Dispatchers.IO) { JReadVoiceEngine.listConfigs(this@TtsPluginActivity) }
            allPlugins = pluginsDeferred.await()
            pluginsMap = buildPluginsIndex(allPlugins)
            adapter.setPluginsMap(pluginsMap)
            allGroups = groupsDeferred.await()
            when (currentTab) {
                TAB_CONFIGS -> {
                    allConfigs = configsDeferred.await()
                    android.util.Log.d("TtsPluginActivity", "loadData: configs=${allConfigs.size} groups=${allGroups.size} plugins=${allPlugins.size}")
                    binding.searchBar.visibility = View.VISIBLE
                    rebuildRows()
                }
                TAB_LOGS -> {
                    binding.searchBar.visibility = View.GONE
                    refreshLogs(force = true)
                }
                TAB_PLUGINS -> {
                    binding.searchBar.visibility = View.GONE
                    rebuildPluginRows()
                }
            }
        }
    }

    /** 防止快速连续触发时旧的后台构建结果覆盖新的 */
    @Volatile
    private var rebuildGeneration = 0

    private fun rebuildRows() {
        // 快照主线程状态后在后台线程构建，避免大列表（数千配置）排序/分组阻塞 UI
        val gen = ++rebuildGeneration
        val configs = allConfigs
        val groups = allGroups
        val query = searchQuery
        val expandedG = HashSet(expandedGroups)
        val expandedS = HashSet(expandedSubGroups)
        lifecycleScope.launch(Dispatchers.Default) {
            val rows = buildConfigRows(configs, groups, query, expandedG, expandedS)
            withContext(Dispatchers.Main) {
                if (gen != rebuildGeneration) return@withContext
                updateEmpty(rows.isEmpty())
                adapter.setRows(rows)
            }
        }
    }

    private fun rebuildPluginRows() {
        val gen = ++rebuildGeneration
        val plugins = allPlugins
        lifecycleScope.launch(Dispatchers.Default) {
            val rows = plugins.map { ConfigListRow.PluginRow(it) }
            withContext(Dispatchers.Main) {
                if (gen != rebuildGeneration) return@withContext
                updateEmpty(rows.isEmpty())
                adapter.setRows(rows)
            }
        }
    }

    private fun buildConfigRows(
        configs: List<JReadVoiceEngine.VoiceConfig>,
        groups: List<JReadVoiceEngine.VoiceGroup>,
        searchQuery: String,
        expandedGroups: Set<String>,
        expandedSubGroups: Set<Pair<String, String>>,
    ): List<ConfigListRow> {
        val filtered = if (searchQuery.isEmpty()) configs
        else configs.filter {
            it.voiceTag.contains(searchQuery, true) || it.displayName.contains(searchQuery, true) ||
            it.groupName.contains(searchQuery, true) || it.subGroupName.contains(searchQuery, true) ||
            it.voice.contains(searchQuery, true) || it.locale.contains(searchQuery, true)
        }
        // 合并：从配置项中提取的分组 + 从 listGroups() 读取的分组（含空分组）
        // 按 sortOrder 排序，sortOrder 相同则按名称
        val configGroupNames = filtered.map { it.groupName.ifBlank { "默认分组" } }.toMutableSet()
        val savedGroupNames = groups.map { it.groupName.ifBlank { "默认分组" } }.toSet()
        configGroupNames.addAll(savedGroupNames)
        val groupSortMap = groups
            .filter { it.subGroupName.isBlank() && it.thirdGroupName.isBlank() }
            .associate { it.groupName.ifBlank { "默认分组" } to it.sortOrder }
        val sortedGroupNames = configGroupNames.sortedWith(compareBy(
            { groupSortMap[it] ?: Int.MAX_VALUE },
            { it }
        ))
        // 预分组：一次 groupBy 消除循环内重复 filter（O(N×G) → O(N)）
        val configsByGroup = filtered.groupBy { it.groupName.ifBlank { "默认分组" } }
        val groupsByGroupName = groups.groupBy { it.groupName.ifBlank { "默认分组" } }
        val rows = mutableListOf<ConfigListRow>()
        for (groupName in sortedGroupNames) {
            val groupConfigs = configsByGroup[groupName] ?: emptyList()
            // 默认展开所有分组（用户可手动折叠）
            val groupExpanded = expandedGroups.contains(groupName) || searchQuery.isNotEmpty()
            val allOn = groupConfigs.isNotEmpty() && groupConfigs.all { it.enabled }
            val someOn = groupConfigs.any { it.enabled }
            rows.add(ConfigListRow.GroupHeader(groupName, groupExpanded, groupConfigs.size, allOn, someOn))
            if (groupExpanded) {
                // 合并子分组：配置项中的 + listGroups 中该分组下的
                val configSubNames = groupConfigs.map { it.subGroupName.ifBlank { "默认" } }.toMutableSet()
                // 关键：分组记录里 subGroupName 为空的那条「默认」记录，只有在本分组确实存在
                // 空子分组的配置项时才算一个真实子分组。否则拖动排序时补建的大分组记录
                // 会让界面凭空多出一个 0 项的「默认」子分组（甚至让原本不显示子分组头的
                // 单子分组分组突然多出一层子分组头）。
                val savedSubNames = (groupsByGroupName[groupName] ?: emptyList())
                    .map { it.subGroupName.ifBlank { "默认" } }
                    .filter { it != "默认" || configSubNames.contains("默认") }
                    .toSet()
                configSubNames.addAll(savedSubNames)
                val subSortMap = (groupsByGroupName[groupName] ?: emptyList())
                    .filter { it.subGroupName.isNotBlank() && it.thirdGroupName.isBlank() }
                    .associate { it.subGroupName.ifBlank { "默认" } to it.sortOrder }
                val sortedSubNames = configSubNames.sortedWith(compareBy(
                    { subSortMap[it] ?: Int.MAX_VALUE },
                    { it }
                ))
                // 预分组子组
                val configsBySub = groupConfigs.groupBy { it.subGroupName.ifBlank { "默认" } }
                for (subGroupName in sortedSubNames) {
                    val subConfigs = configsBySub[subGroupName] ?: emptyList()
                    val subExpanded = expandedSubGroups.contains(Pair(groupName, subGroupName)) || searchQuery.isNotEmpty()
                    val hasMultipleSubs = sortedSubNames.size > 1
                    if (hasMultipleSubs) {
                        val sAllOn = subConfigs.isNotEmpty() && subConfigs.all { it.enabled }
                        val sSomeOn = subConfigs.any { it.enabled }
                        rows.add(ConfigListRow.SubGroupHeader(groupName, subGroupName, subExpanded, subConfigs.size, sAllOn, sSomeOn))
                    }
                    if (subExpanded || !hasMultipleSubs) {
                        subConfigs.sortedWith(compareBy({ it.sortOrder }, { it.voiceTag }, { it.displayName })).forEach {
                            // 有子分组头时缩进2级，没有时缩进1级
                            rows.add(ConfigListRow.ConfigRow(it, if (hasMultipleSubs) 2 else 1))
                        }
                    }
                }
            }
        }
        return rows
    }

    private fun updateEmpty(empty: Boolean) {
        binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmptyHint.text = when (currentTab) {
            TAB_CONFIGS -> "点击右下角按钮新增配置"
            TAB_PLUGINS -> "点击右下角按钮或右上角导入插件"
            else -> ""
        }
    }

    // ------------------------------------------------------------------ 日志 Tab

    /** 按当前 Tab 刷新内容：日志页走日志装载，另外两页重建列表行 */
    private fun refreshCurrentTab() {
        when (currentTab) {
            TAB_LOGS -> showLogTab()
            TAB_PLUGINS -> {
                hideLogTab()
                binding.searchBar.visibility = View.GONE
                rebuildPluginRows()
            }
            else -> {
                hideLogTab()
                binding.searchBar.visibility = View.VISIBLE
                rebuildRows()
            }
        }
    }

    private fun showLogTab() {
        binding.searchBar.visibility = View.GONE
        binding.layoutEmpty.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.logContainer.visibility = View.VISIBLE
        refreshLogs(force = true)
        startLogPolling()
    }

    private fun hideLogTab() {
        binding.logContainer.visibility = View.GONE
        stopLogPolling()
    }

    private fun startLogPolling() {
        binding.logContainer.removeCallbacks(logPollRunnable)
        binding.logContainer.postDelayed(logPollRunnable, LOG_POLL_INTERVAL_MS)
    }

    private fun stopLogPolling() {
        binding.logContainer.removeCallbacks(logPollRunnable)
    }

    /**
     * 装载日志。
     *
     * 默认只显示「合成日志」（TtsSynthLog）：合成了哪段文本、成功还是失败、失败的原因——
     * 对齐 TTS_Server_Android 默认日志只看合成结果的习惯，**不会**把整个 App 的 AppLog 倒进来。
     * 打开「插件日志」开关后，再把插件脚本自己 console.log 的内容（PluginLog）合并显示，
     * 便于排查插件内部逻辑，按时间倒序混排。
     */
    private fun refreshLogs(force: Boolean = false) {
        val showPluginLog = binding.swPluginLog.isChecked
        val signature = TtsSynthLog.version * 1_000_003 + PluginLog.version * 7 + if (showPluginLog) 1 else 0
        if (!force && signature == logSignature) return
        logSignature = signature

        val wasAtBottom = isLogAtBottom()

        val rows = ArrayList<LogRow>()
        // 合成日志（默认显示）。级别对齐 TTS_Server_Android：
        // 合成开始=D(蓝)、合成成功=S(跟随主题)、重试=W(黄)、失败=E(红)、普通信息=I(绿)
        TtsSynthLog.list().forEach { entry ->
            val (levelChar, color) = when (entry.level) {
                TtsSynthLog.Level.DEBUG -> "D" to colorDebug
                TtsSynthLog.Level.INFO -> "I" to colorInfo
                TtsSynthLog.Level.SUCCESS -> "S" to colorNormal
                TtsSynthLog.Level.WARN -> "W" to colorWarn
                TtsSynthLog.Level.ERROR -> "E" to colorError
            }
            rows.add(
                LogRow(
                    entry.time,
                    LogUtils.logTimeFormat.format(Date(entry.time)),
                    levelChar,
                    entry.message,
                    color
                )
            )
        }
        // 插件脚本日志（开关打开时才显示）
        if (showPluginLog) {
            PluginLog.list().forEach { entry ->
                val (levelChar, color) = when (entry.level) {
                    PluginLog.Level.DEBUG -> "D" to colorDebug
                    PluginLog.Level.INFO -> "I" to colorInfo
                    PluginLog.Level.WARN -> "W" to colorWarn
                    PluginLog.Level.ERROR -> "E" to colorError
                }
                rows.add(
                    LogRow(
                        entry.time,
                        LogUtils.logTimeFormat.format(Date(entry.time)),
                        levelChar,
                        "[插件] ${entry.message}",
                        color
                    )
                )
            }
        }
        // 新日志从底部追加（对齐 TTS_Server_Android），所以按时间**升序**排列
        rows.sortBy { it.time }
        logAdapter.setItems(rows)
        binding.tvLogEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        // 只有本来就停在底部（在看最新内容）时才自动滚到底，向上翻历史时不打扰
        if (wasAtBottom) scrollToLogBottom()
        updateFabLogBottom()
    }

    /** 是否停在列表底部附近（对齐参考实现：最后可见条目进入末尾 5 条以内即算在底部） */
    private fun isLogAtBottom(): Boolean {
        val lm = binding.rvLog.layoutManager as? LinearLayoutManager ?: return true
        val last = lm.findLastVisibleItemPosition()
        return last < 0 || last >= logAdapter.itemCount - 5
    }

    private fun scrollToLogBottom() {
        val last = logAdapter.itemCount - 1
        if (last < 0) return
        binding.rvLog.post {
            (binding.rvLog.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(last, 0)
        }
    }

    private fun updateFabLogBottom() {
        binding.fabLogBottom.visibility =
            if (logAdapter.itemCount > 0 && !isLogAtBottom()) View.VISIBLE else View.GONE
    }

    private fun copyLogs() {
        val text = logAdapter.getItems().joinToString("\n") { it.text }
        if (text.isBlank()) {
            toastOnUi("暂无日志")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("TtsPluginLog", text))
        toastOnUi("已复制 ${logAdapter.getItems().size} 条日志")
    }

    private fun clearLogs() {
        // 只清本页显示的两类日志，不动 AppLog（那是应用日志界面的事）
        TtsSynthLog.clear()
        PluginLog.clear()
        refreshLogs(force = true)
        toastOnUi("已清空日志")
    }

    // ------------------------------------------------------------------ 日志配色
    // 对齐 TTS_Server_Android 的 LogLevel.toArgb()：请求中(D)蓝、信息(I)绿、
    // 成功(S)跟随主题、警告(W)黄、错误(E)红，浅色/深色主题各一套（同一色相，深色更亮）。
    private val isDarkTheme by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    private val colorNormal by lazy {
        resources.getColor(R.color.primaryText, theme) // SUCCESS / 默认：跟随主题
    }
    // D（合成开始/请求中）：墨绿，取自 TTS_Server_Android 绿色主题的 primary（Color2.kt 0xFF006D3A）
    private val colorDebug by lazy { Color.parseColor(if (isDarkTheme) "#4CAF50" else "#006D3A") }
    private val colorInfo by lazy { Color.parseColor(if (isDarkTheme) "#81C784" else "#4CAF50") }
    private val colorWarn by lazy { Color.parseColor(if (isDarkTheme) "#FFD54F" else "#FFC107") }
    private val colorError by lazy { Color.parseColor(if (isDarkTheme) "#E57373" else "#F44336") }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tts_plugin, menu)
        // 「新建分组」只对配置列表有意义，插件管理 Tab 下隐藏
        menu.findItem(R.id.menu_add_group)?.isVisible = currentTab == TAB_CONFIGS
        // 日志页没有可新增的东西，收起加号
        menu.findItem(R.id.menu_add)?.isVisible = currentTab != TAB_LOGS
        return super.onCompatCreateOptionsMenu(menu)
    }

    /** 三个 Tab 切换时同步「新建分组 / 新增」图标的显隐 */
    private fun syncAddGroupMenuVisibility() {
        invalidateOptionsMenu()
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_group -> showAddGroupDialog()
            R.id.menu_add -> when (currentTab) {
                TAB_CONFIGS -> openConfigEditor(null, true)
                TAB_PLUGINS -> showPluginEditor(null)
                else -> Unit
            }
            R.id.menu_init_builtins -> lifecycleScope.launch {
                withContext(Dispatchers.IO) { JReadVoiceEngine.ensureBuiltInVoicePresets(this@TtsPluginActivity) }
                toastOnUi("内置配置已初始化"); loadData()
            }
            R.id.menu_import -> importLauncher.launch("*/*")
            R.id.menu_export -> {
                // 清掉可能残留的单插件目标，确保这次走整体导出
                pendingExportPlugin = null
                val suffix = if (currentTab == TAB_CONFIGS) "configs" else "plugins"
                exportLauncher.launch("tts_${suffix}_${System.currentTimeMillis()}.json")
            }
            R.id.menu_disable_all -> lifecycleScope.launch {
                alert("确认", "确定要禁用所有配置吗？") {
                    positiveButton("确定") {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                JReadVoiceEngine.disableAllConfigs(this@TtsPluginActivity)
                            }
                            allConfigs = allConfigs.map { it.copy(enabled = false) }
                            rebuildRows()
                            invalidateEnabledTagsCache()
                            toastOnUi("已禁用所有配置")
                        }
                    }
                    negativeButton("取消") {}
                }
            }
            R.id.menu_list_tags -> lifecycleScope.launch {
                val tags = withContext(Dispatchers.IO) {
                    JReadVoiceEngine.listConfigs(this@TtsPluginActivity).filter { it.enabled }.map { it.voiceTag }.distinct()
                }
                if (tags.isEmpty()) toastOnUi("暂无可用标签")
                else alert("可用标签 (${tags.size})") { setMessage(tags.joinToString("\n")); okButton() }
            }
            R.id.menu_global_audio_params -> showGlobalAudioParamsDialog()
            R.id.menu_fast_play -> showFastPlayDialog()
            R.id.menu_help -> showHelp("ttsPluginHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun openConfigEditor(configId: String?, isNew: Boolean) {
        configEditorLauncher.launch(Intent(this, TtsConfigEditorActivity::class.java).apply {
            putExtra(TtsConfigEditorActivity.EXTRA_CONFIG_ID, configId)
            putExtra(TtsConfigEditorActivity.EXTRA_IS_NEW, isNew)
        })
    }

    private fun previewConfig(config: JReadVoiceEngine.VoiceConfig) {
        // 试听文本与「编辑TTS配置」里为该条设置的保持一致，未设置才用默认文案
        val previewText = config.previewText.ifBlank { JReadVoiceEngine.DEFAULT_PREVIEW_TEXT }
        toastOnUi("正在试听: ${config.voiceTag}")
        lifecycleScope.launch {
            val requestId = UUID.randomUUID().toString()
            val pointerJson = JSONObject().put("voiceTag", config.voiceTag).toString()
            val audioBytes = withContext(Dispatchers.IO) {
                runCatching {
                    JReadVoiceEngine.synthesizeConfigLineAudio(
                        this@TtsPluginActivity, config, previewText, pointerJson, requestId
                    )
                }.getOrNull()
            }
            if (audioBytes == null || audioBytes.isEmpty()) { toastOnUi("试听失败"); return@launch }
            // 与朗读同一套后处理链路（条目 → 分组 → 插件 → 全局），
            // 否则试听听不出条目/分组/插件级参数的效果
            val postParams = JReadVoiceEngine.resolvePostAudioParams(this@TtsPluginActivity, config)
            val playedBytes = if (postParams.isDefault()) {
                audioBytes
            } else {
                withContext(Dispatchers.IO) {
                    io.legado.app.utils.AudioDecodeUtil
                        .decodeToStandardWav(audioBytes, postParams = postParams)
                } ?: audioBytes
            }
            if (isFinishing || isDestroyed) return@launch
            val tempFile = withContext(Dispatchers.IO) {
                java.io.File(cacheDir, "preview_${requestId}.wav").apply { writeBytes(playedBytes) }
            }
            playAudio(tempFile.absolutePath)
        }
    }

    private fun playAudio(path: String) {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(path); prepare()
            setOnCompletionListener { it.release(); mediaPlayer = null }
            start()
        }
    }

    private fun deleteConfig(config: JReadVoiceEngine.VoiceConfig) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.deleteConfig(this@TtsPluginActivity, config.id) }
            // 仅重读配置列表并轻量刷新，不 loadData() 全量重载
            allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
            toastOnUi("已删除: ${config.voiceTag}")
            refreshListLight()
        }
    }

    private fun toggleConfig(config: JReadVoiceEngine.VoiceConfig, enabled: Boolean) {
        lifecycleScope.launch {
            // 事务化：锁内完成读改写并直接返回最新全量列表（连点安全），无需二次回读
            allConfigs = withContext(Dispatchers.IO) {
                JReadVoiceEngine.setConfigEnabled(this@TtsPluginActivity, config.id, enabled)
            }
            refreshListLight()
        }
    }

    private fun setGroupEnabled(groupName: String, subGroupName: String?, enabled: Boolean) {
        lifecycleScope.launch {
            // 事务化批量启停：引擎内一次读改写（禁用零互斥扫描），返回最新全量列表
            allConfigs = withContext(Dispatchers.IO) {
                val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                val ids = configs.filter { c ->
                    c.groupName.ifBlank { "默认分组" } == groupName &&
                        (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                }.map { it.id }
                if (ids.isEmpty()) configs
                else JReadVoiceEngine.setConfigsEnabled(this@TtsPluginActivity, ids, enabled)
            }
            refreshListLight()
        }
    }

    /**
     * 轻量刷新：内存已是最新，只重排可见列表并刷新内置发音人列表缓存，
     * 不再走 loadData()（不读盘、不解析整表、不做内置预设/迁移）。
     */
    private fun refreshListLight() {
        refreshCurrentTab()
        invalidateEnabledTagsCache()
    }

    /**
     * 发音人启用状态变化后调用：让内置发音人列表的缓存立即失效，
     * 朗读脚本下一次读取就能拿到最新列表（否则最多要等缓存自然过期）。
     *
     * 原先这里还会把标签写到外部存储的 fayinren.json，
     * 现已改为 APP 内部实时提供，不再产生任何磁盘写入。
     */
    private fun invalidateEnabledTagsCache() {
        io.legado.app.help.VirtualExternalFiles.invalidate()
    }

    /**
     * 构建 pluginId → VoicePlugin 索引，供列表卡片显示"所属插件名"。
     *
     * 必须双键（记录 id + 业务 pluginId）：配置落库时 pluginId 取的是
     * `plugin.pluginId.ifBlank { plugin.id }`（见更换插件/迁移音色分组逻辑），
     * 而插件自身还有一条独立的记录 id（UUID 或 obj.id），两者并不相等。
     * 若只按 `it.id` 建索引，卡片用 `config.pluginId` 反查就会全部落空，
     * 表现为列表里其它信息正常、唯独右下角插件名空白。
     * 这里与 JReadVoiceEngine.findPlugin 的索引策略保持一致（id 优先，pluginId 补位）。
     */
    private fun buildPluginsIndex(
        plugins: List<JReadVoiceEngine.VoicePlugin>
    ): Map<String, JReadVoiceEngine.VoicePlugin> {
        val index = LinkedHashMap<String, JReadVoiceEngine.VoicePlugin>(plugins.size * 2)
        plugins.forEach { index[it.id] = it }
        plugins.forEach { if (it.pluginId.isNotBlank()) index.getOrPut(it.pluginId) { it } }
        return index
    }

    /**
     * 仅重读列表数据（groups/configs/plugins 各读盘一次），不执行 ensureBuiltIn。
     * 用于仅改动分组/插件元信息、configs 内容不变的轻量场景。
     */
    private fun reloadListsLight() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                allPlugins = JReadVoiceEngine.listPlugins(this@TtsPluginActivity)
                pluginsMap = buildPluginsIndex(allPlugins)
            }
            adapter.setPluginsMap(pluginsMap)
            refreshCurrentTab()
        }
    }

    private fun showRenameDialog(groupName: String, subGroupName: String?) {
        val etNewName = EditText(this).apply { setText(subGroupName ?: groupName); setPadding(48, 24, 48, 24) }
        alert("重命名") {
            customView { etNewName }
            okButton {
                val newName = etNewName.text.toString().trim()
                if (newName.isBlank()) { toastOnUi("名称不能为空"); return@okButton }
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val toUpdate = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == groupName &&
                            (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                        }
                        val updated = if (subGroupName == null) toUpdate.map { it.copy(groupName = newName) }
                        else toUpdate.map { it.copy(subGroupName = newName) }
                        JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                        // 同步内存，避免 loadData() 全量重载
                        val updateIds = toUpdate.map { it.id }.toSet()
                        allConfigs = configs.map { if (updateIds.contains(it.id)) it.copy(groupName = if (subGroupName == null) newName else it.groupName, subGroupName = if (subGroupName == null) it.subGroupName else newName) else it }
                        // 删除原分组对应的 VoiceGroup 记录，避免残留空分组（新名字会在 upsertGroup 时自动生成记录）
                        // 注意：row 传入的 groupName/subGroupName 是显示值（"默认分组"/"默认"），
                        // 而存储里真实值是空串，因此用真实存储值精确匹配，避免 ifBlank 比较遗漏。
                        val realGroupKey = if (groupName == "默认分组") "" else groupName
                        val realSubKey = subGroupName?.let { if (it == "默认") "" else it }
                        val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                        if (subGroupName == null) {
                            groups.removeAll {
                                it.groupName == realGroupKey &&
                                it.subGroupName.isBlank() && it.thirdGroupName.isBlank()
                            }
                        } else {
                            groups.removeAll {
                                it.groupName == realGroupKey &&
                                it.subGroupName == realSubKey
                            }
                        }
                        JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                        // 同步内存中的分组记录，否则界面会残留旧名的空分组（要切走再回来才消失）
                        allGroups = groups
                    }
                    toastOnUi("已重命名")
                    refreshListLight()
                }
            }
            cancelButton()
        }
    }

    private fun showDeleteGroupDialog(groupName: String, subGroupName: String?) {
        alert("删除确认") {
            setMessage("确定要删除分组「${subGroupName ?: groupName}」及其下所有配置吗？")
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // 一次性读盘，算出该分组下所有配置 id，仅全量写盘一次（避免逐条 deleteConfig 的 N 次全量读写）
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val deleteIds = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == groupName &&
                            (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                        }.map { it.id }.toSet()
                        allConfigs = JReadVoiceEngine.deleteConfigs(this@TtsPluginActivity, deleteIds)
                        // 删除分组记录本身
                        JReadVoiceEngine.deleteGroup(this@TtsPluginActivity, groupName, subGroupName)
                        // 同步内存中的分组列表，避免界面残留空分组头（不重新 loadData）
                        allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                    }
                    toastOnUi("已删除分组")
                    refreshListLight()
                }
            }
            noButton()
        }
    }

    /**
     * 复制单个配置：生成新 id（避免与源配置冲突），其余内容完全照搬，
     * 先写入存储，再以「编辑」方式打开（编辑页按 id 加载并保留 id 保存），
     * 用户可在编辑页确认/微调。逻辑对齐 TTS Server 的 onCopy（打开副本编辑页）。
     */
    private fun copyConfig(config: JReadVoiceEngine.VoiceConfig) {
        val copy = config.copy(id = java.util.UUID.randomUUID().toString())
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, listOf(copy))
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
            }
            toastOnUi("已复制，请在编辑页确认保存")
            refreshListLight()
            openConfigEditor(copy.id, isNew = false)
        }
    }

    /**
     * 导出单个配置为 JSON 文件，并通过系统分享/打开。对齐 TTS Server 的 onExport。
     */
    private fun exportConfig(config: JReadVoiceEngine.VoiceConfig) {
        val json = org.json.JSONObject().apply {
            put("data", org.json.JSONObject().apply {
                // 把字段平铺到 data 内，与导入格式保持一致（TTS Server 导出即为单个配置对象）
                put("id", config.id); put("voiceTag", config.voiceTag)
                put("groupName", config.groupName); put("subGroupName", config.subGroupName)
                put("thirdGroupName", config.thirdGroupName); put("displayName", config.displayName)
                put("pluginId", config.pluginId); put("locale", config.locale)
                put("voice", config.voice); put("previewText", config.previewText)
                put("data", org.json.JSONObject(config.dataJson))
                put("speed", config.speed); put("volume", config.volume); put("pitch", config.pitch)
                put("method", config.method); put("urlTemplate", config.urlTemplate)
                put("headersText", config.headersText); put("bodyTemplate", config.bodyTemplate)
                put("responseAudioPath", config.responseAudioPath); put("enabled", config.enabled)
                put("postSpeed", config.postSpeed); put("postVolume", config.postVolume); put("postPitch", config.postPitch)
            })
        }.toString(2)
        val dir = java.io.File(io.legado.app.help.EngineDataDir.baseDir())
        try {
            if (!dir.exists()) dir.mkdirs()
            val safeName = config.displayName.ifBlank { config.voice }.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
            val file = java.io.File(dir, "tts_config_${safeName}_${config.id.takeLast(6)}.json")
            file.writeText(json)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileProvider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, "导出配置"))
        } catch (e: Exception) {
            toastOnUi("导出失败：${e.message}")
        }
    }

    /**
     * 复制分组（含子分组）：源分组下的每个配置都生成新 id 并归入新分组名，
     * 新分组名追加「副本」后缀。对齐 TTS Server 的 onCopy（分组）。
     */
    private fun copyGroup(groupName: String, subGroupName: String?) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                val matched = configs.filter { c ->
                    c.groupName.ifBlank { "默认分组" } == srcGroup &&
                    (srcSub == null || c.subGroupName.ifBlank { "默认" } == srcSub)
                }
                if (matched.isEmpty()) return@withContext
                val newGroupName = if (srcSub == null) "$srcGroup 副本" else srcGroup
                val newSubName = if (srcSub == null) null else "${srcSub} 副本"
                val copies = matched.map { c ->
                    c.copy(
                        id = java.util.UUID.randomUUID().toString(),
                        groupName = newGroupName,
                        subGroupName = newSubName ?: c.subGroupName
                    )
                }
                // 确保新分组记录存在（便于空分组也能显示）
                if (srcSub == null) {
                    JReadVoiceEngine.saveGroup(this@TtsPluginActivity, JReadVoiceEngine.VoiceGroup(groupName = newGroupName))
                } else {
                    JReadVoiceEngine.saveGroup(this@TtsPluginActivity, JReadVoiceEngine.VoiceGroup(groupName = newGroupName, subGroupName = newSubName ?: ""))
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, copies)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
            }
            toastOnUi("已复制分组")
            refreshListLight()
        }
    }

    /**
     * 删除某分组（或子分组）下全部「启用」或「停用」的配置。对齐 TTS Server 的
     * onDeleteEnabled / onDeleteDisabled。
     */
    private fun deleteGroupEnabledDisabled(groupName: String, subGroupName: String?, enabled: Boolean) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName
        val label = if (enabled) "启用项" else "停用项"
        alert("删除确认") {
            setMessage("确定删除分组「${srcSub ?: srcGroup}」下的全部${label}吗？")
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val deleteIds = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == srcGroup &&
                            (srcSub == null || c.subGroupName.ifBlank { "默认" } == srcSub) &&
                            c.enabled == enabled
                        }.map { it.id }.toSet()
                        if (deleteIds.isEmpty()) return@withContext
                        allConfigs = JReadVoiceEngine.deleteConfigs(this@TtsPluginActivity, deleteIds)
                    }
                    toastOnUi("已删除${label}")
                    refreshListLight()
                }
            }
            noButton()
        }
    }

    /**
     * 更换插件的选择弹窗。
     * 原先直接用系统 [android.app.AlertDialog.Builder.setItems]，它渲染出来就是一行纯文字，
     * 行与行之间只有空白间距、没有底色也没有边界，插件一多就全糊成一片分不清哪个是哪个。
     * 这里换成 ListView + 自定义适配器，每行渲染成一张卡片（和配置列表同一套卡片视觉），
     * 滚动与点击仍由系统 ListView 保证，不额外手写。
     */
    private fun showReplacePluginDialog(groupName: String, subGroupName: String?) {
        lifecycleScope.launch {
            val plugins = allPlugins
            if (plugins.isEmpty()) { toastOnUi("暂无可用插件"); return@launch }
            val names = plugins.map { TtsPluginAdapter.displayNameForConfigCard(it) }
            val listView = android.widget.ListView(this@TtsPluginActivity).apply {
                divider = null
                dividerHeight = 0
                // 卡片自身已有 3dp 上下间距，这里再补一点，让首尾卡片不贴着弹窗边缘
                val pad = TtsPluginAdapter.dpToPx(context, 4)
                setPadding(0, pad, 0, pad)
                clipToPadding = false
                adapter = PluginPickAdapter(names, plugins)
            }
            val dialog = android.app.AlertDialog.Builder(this@TtsPluginActivity)
                .setTitle("选择新插件")
                .setView(listView)
                .setNegativeButton("取消", null)
                .create()
            listView.setOnItemClickListener { _, _, which, _ ->
                val newPlugin = plugins[which]
                dialog.dismiss()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                        val toUpdate = configs.filter { c ->
                            c.groupName.ifBlank { "默认分组" } == groupName &&
                                (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                        }.map { it.copy(pluginId = newPlugin.pluginId.ifBlank { newPlugin.id }) }
                        JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, toUpdate)
                        val updateIds = toUpdate.map { it.id }.toSet()
                        allConfigs = configs.map { if (updateIds.contains(it.id)) it.copy(pluginId = newPlugin.pluginId.ifBlank { newPlugin.id }) else it }
                    }
                    toastOnUi("已更换插件为 ${newPlugin.name}")
                    refreshListLight()
                }
            }
            dialog.show()
        }
    }

    /**
     * "选择新插件"弹窗的每行适配器：主行插件名，副行「作者 | 版本」。
     * 副行为空时整行隐藏，不留一条只有空白的行。
     */
    private inner class PluginPickAdapter(
        private val names: List<String>,
        private val plugins: List<JReadVoiceEngine.VoicePlugin>
    ) : android.widget.BaseAdapter() {

        override fun getCount(): Int = plugins.size

        override fun getItem(position: Int): Any = plugins[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(
            position: Int,
            convertView: View?,
            parent: android.view.ViewGroup
        ): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.item_select_plugin, parent, false)
            val plugin = plugins[position]
            view.findViewById<TextView>(R.id.tv_pick_name).text =
                names[position].ifBlank { "未命名插件" }
            val sub = listOf(plugin.author, plugin.version)
                .filter { it.isNotBlank() }
                .joinToString(" | ")
            view.findViewById<TextView>(R.id.tv_pick_sub).apply {
                text = sub
                visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
            }
            return view
        }
    }

    /**
     * 一键整理标签：对标 TTS Server 的 reassignTagsWithPrefix。
     * 让用户输入一个前缀，把目标分组（大分组含其下所有项；子分组仅该子分组）下
     * 的【启用】配置项按列表当前顺序从 01 开始连续编号，voiceTag = 前缀 + 两位序号。
     */
    private fun showOrganizeTagsDialog(groupName: String, subGroupName: String?) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName
        val input = android.widget.EditText(this).apply {
            hint = "如：女中 / 男青 / 旁白"
            setText("")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("重新分配标签（前缀）")
            .setMessage("将「${srcSub ?: srcGroup}」下启用项按当前顺序编号")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val prefix = input.text.toString().trim()
                if (prefix.isEmpty()) { toastOnUi("前缀不能为空"); return@setPositiveButton }
                reassignTagsWithPrefix(srcGroup, srcSub, prefix)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 按当前列表顺序（sortOrder）对启用项重新连续编号，逻辑对齐 TTS Server reassignTagsWithPrefix。
     */
    private fun reassignTagsWithPrefix(groupName: String, subGroupName: String?, prefix: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                val matched = configs.filter { c ->
                    c.groupName.ifBlank { "默认分组" } == groupName &&
                    (subGroupName == null || c.subGroupName.ifBlank { "默认" } == subGroupName)
                }.sortedWith(compareBy({ it.sortOrder }, { it.voiceTag }, { it.displayName }))
                val enabled = matched.filter { it.enabled }
                if (enabled.isEmpty()) return@withContext
                val updated = configs.map { c ->
                    val idx = enabled.indexOfFirst { it.id == c.id }
                    if (idx >= 0) c.copy(voiceTag = prefix + String.format("%02d", idx + 1))
                    else c
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
            }
            toastOnUi("已重新分配标签")
            refreshListLight()
        }
    }

    /**
     * 大分组 → 子分组：对齐 TTS Server 的 convertToSubGroup。
     * 选中一个大分组 srcGroup，选一个目标大分组 dstGroup（从所有配置的大分组名派生，排除自己）。
     * 把 srcGroup 下【所有配置项】（含其下子分组项）整体迁入 dstGroup，
     * 子分组名变更为 "srcGroup/sub原分组名"（原无子分组的直接为 srcGroup）。
     */
    private fun convertGroupToSub(groupName: String) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        lifecycleScope.launch {
            val allConfigsNow = withContext(Dispatchers.IO) {
                JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
            }
            // 目标大分组：配置中出现过的大分组名 ∪ 分组记录中的大分组（含空分组），排除自身
            // 否则空大分组（只有 VoiceGroup 记录、无配置项）无法作为目标，造成死循环。
            val configGroups = allConfigsNow
                .map { it.groupName.ifBlank { "默认分组" } }
                .toSet()
            val recordGroups = allGroups
                .filter { it.subGroupName.isBlank() && it.thirdGroupName.isBlank() }
                .map { it.groupName.ifBlank { "默认分组" } }
                .toSet()
            val targetGroups = (configGroups + recordGroups)
                .filter { it != srcGroup }
                .distinct()
            if (targetGroups.isEmpty()) {
                toastOnUi("没有其他大分组可作为目标")
                return@launch
            }
            withContext(Dispatchers.Main) {
                androidx.appcompat.app.AlertDialog.Builder(this@TtsPluginActivity)
                    .setTitle("转为子分组：选择目标大分组")
                    .setItems(targetGroups.toTypedArray()) { _, which ->
                        val target = targetGroups[which]
                        doConvertGroupToSub(srcGroup, target, allConfigsNow)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun doConvertGroupToSub(srcGroup: String, targetGroup: String, allConfigsNow: List<JReadVoiceEngine.VoiceConfig>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // 把显示值还原为真实存储值（"默认分组" → ""），避免把字面量写入 groupName
                val realTargetGroup = if (targetGroup == "默认分组") "" else targetGroup
                // srcGroup 下所有配置项整体迁入 targetGroup，子分组名前插 srcGroup
                val updated = allConfigsNow.map { c ->
                    if (c.groupName.ifBlank { "默认分组" } == srcGroup) {
                        val newSub = buildString {
                            append(srcGroup)
                            if (c.subGroupName.isNotBlank()) append("/").append(c.subGroupName)
                        }
                        c.copy(groupName = realTargetGroup, subGroupName = newSub)
                    } else c
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                // 源大分组已迁空，删除其分组记录，避免残留空分组
                val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                groups.removeAll {
                    it.groupName.ifBlank { "默认分组" } == srcGroup &&
                    it.subGroupName.isBlank() && it.thirdGroupName.isBlank()
                }
                JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
            }
            toastOnUi("已转为「$targetGroup」的子分组")
            refreshListLight()
        }
    }

    /**
     * 合并子分组：选中一个大分组，列出其下所有子分组（支持多选），
     * 将选中的子分组合并为一个子分组（目标名取第一个勾选项的名称，
     * 其余子分组下的配置项统一归属到目标子分组，被合并的子分组消失）。
     */
    private fun mergeSubGroups(groupName: String) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        lifecycleScope.launch {
            val allConfigsNow = withContext(Dispatchers.IO) {
                JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
            }
            // 该大分组下出现过的子分组真实值集合，空串代表「默认」
            val realSubs = allConfigsNow
                .filter { it.groupName.ifBlank { "默认分组" } == srcGroup }
                .map { it.subGroupName }
                .toSet()
            if (realSubs.size < 2) {
                toastOnUi("该大分组下子分组不足 2 个，无法合并")
                return@launch
            }
            // 展示用名称：真实空串显示为「默认」
            val displaySubs = realSubs.map { it.ifBlank { "默认" } }.distinct().sorted()
            val checked = BooleanArray(displaySubs.size)
            withContext(Dispatchers.Main) {
                androidx.appcompat.app.AlertDialog.Builder(this@TtsPluginActivity)
                    .setTitle("合并子分组：多选要合并的子分组")
                    .setMultiChoiceItems(displaySubs.toTypedArray(), checked) { _, _, _ -> }
                    .setPositiveButton("合并") { _, _ ->
                        val selectedDisplay = displaySubs.filterIndexed { i, _ -> checked[i] }
                        if (selectedDisplay.size < 2) {
                            toastOnUi("请至少选择 2 个子分组")
                            return@setPositiveButton
                        }
                        doMergeSubGroups(srcGroup, selectedDisplay, allConfigsNow)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun doMergeSubGroups(
        srcGroup: String,
        selectedDisplay: List<String>,
        allConfigsNow: List<JReadVoiceEngine.VoiceConfig>
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // 目标子分组取第一个勾选项；展示名「默认」→ 真实空串
                val targetDisplay = selectedDisplay.first()
                val targetReal = if (targetDisplay == "默认") "" else targetDisplay
                val selectedReal = selectedDisplay.map { if (it == "默认") "" else it }.toSet()
                val updated = allConfigsNow.map { c ->
                    if (c.groupName.ifBlank { "默认分组" } == srcGroup &&
                        c.subGroupName in selectedReal && c.subGroupName != targetReal
                    ) {
                        c.copy(subGroupName = targetReal)
                    } else c
                }
                JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                // 清理被合并掉的多余子分组记录（subGroupName 不再有配置项存在时移除）
                val remainSubs = updated
                    .filter { it.groupName.ifBlank { "默认分组" } == srcGroup }
                    .map { it.subGroupName }
                    .toSet()
                val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                groups.removeAll {
                    it.groupName.ifBlank { "默认分组" } == srcGroup &&
                    it.subGroupName.isNotBlank() && it.thirdGroupName.isBlank() &&
                    it.subGroupName !in remainSubs
                }
                JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
            }
            toastOnUi("已合并为「${if (selectedDisplay.first() == "默认") "默认" else selectedDisplay.first()}」")
            refreshListLight()
        }
    }

    /**
     * 子分组 → 大分组（B）：把该子分组整体升级为一个【独立】的新大分组。
     * 这些配置项的 groupName 改为子分组自己的名字（作为新大分组名），
     * subGroupName 清空，从而脱离原大分组、成为独立大分组。
     */
    private fun convertSubToGroup(groupName: String, subGroupName: String) {
        val srcGroup = groupName.ifBlank { "默认分组" }
        val srcSub = subGroupName // 原始子分组名（空串表示"默认"子分组）
        alert("转为大分组") {
            setMessage("将子分组「$srcSub」从「$srcGroup」中独立出来，成为新的大分组？")
            yesButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val configs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                        val newGroupName = if (srcSub.isBlank()) "${srcGroup}（独立）" else srcSub
                        val updated = configs.map { c ->
                            if (c.groupName.ifBlank { "默认分组" } == srcGroup &&
                                c.subGroupName == srcSub) {
                                // 整组脱离原大分组，以子分组名作为新大分组名
                                c.copy(groupName = newGroupName, subGroupName = "")
                            } else c
                        }
                        JReadVoiceEngine.saveConfigsBatch(this@TtsPluginActivity, updated)
                        // 删除原子分组的 VoiceGroup 记录，避免残留空子分组（新大分组名会在 upsertGroup 时自动生成记录）
                        val groups = JReadVoiceEngine.listGroups(this@TtsPluginActivity).toMutableList()
                        groups.removeAll {
                            it.groupName.ifBlank { "默认分组" } == srcGroup &&
                            it.subGroupName == srcSub
                        }
                        JReadVoiceEngine.saveGroups(this@TtsPluginActivity, groups)
                        allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity, ensureBuiltIns = false)
                        allGroups = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                    }
                    toastOnUi("已转为独立大分组「${if (srcSub.isBlank()) "${srcGroup}（独立）" else srcSub}」")
                    refreshListLight()
                }
            }
            noButton()
        }
    }

    private fun showGroupAudioParamsDialog(groupName: String, subGroupName: String?) {
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) { JReadVoiceEngine.listGroups(this@TtsPluginActivity) }
            val group = groups.firstOrNull {
                it.groupName == groupName &&
                    (if (subGroupName == null) it.subGroupName.isBlank() else it.subGroupName == subGroupName) &&
                    it.thirdGroupName.isBlank()
            }
            val curSpeed = group?.postSpeed ?: PostAudioParams.FOLLOW
            val curVolume = group?.postVolume ?: PostAudioParams.FOLLOW
            val curPitch = group?.postPitch ?: PostAudioParams.FOLLOW

            val container = LinearLayout(this@TtsPluginActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
            }
            container.addView(TextView(this@TtsPluginActivity).apply {
                text = "音频后处理调节（合成后生效）\n0 = 跟随上级，1.0 = 不调整"
                textSize = 12f; setPadding(4, 4, 4, 12)
            })
            val sbSpeed = buildSliderRow("语速", if (curSpeed <= 0f) 0 else (curSpeed * 100).toInt(), true)
            val sbVol = buildSliderRow("音量", if (curVolume <= 0f) 0 else (curVolume * 100).toInt(), true)
            val sbPitch = buildSliderRow("音高", if (curPitch <= 0f) 0 else (curPitch * 100).toInt(), true)
            listOf(sbSpeed, sbVol, sbPitch).forEach { container.addView(it.second) }
            alert("音频调节 - ${subGroupName ?: groupName}") {
                customView { container }
                okButton {
                    val speed = sbSpeed.first.progress / 100f
                    val volume = sbVol.first.progress / 100f
                    val pitch = sbPitch.first.progress / 100f
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val existing = JReadVoiceEngine.listGroups(this@TtsPluginActivity)
                                .firstOrNull {
                                    it.groupName == groupName &&
                                        (if (subGroupName == null) it.subGroupName.isBlank() else it.subGroupName == subGroupName) &&
                                        it.thirdGroupName.isBlank()
                                }
                            val updated = (existing ?: JReadVoiceEngine.VoiceGroup(groupName = groupName, subGroupName = subGroupName ?: "")).copy(
                                postSpeed = speed, postVolume = volume, postPitch = pitch
                            )
                            JReadVoiceEngine.saveGroup(this@TtsPluginActivity, updated)
                        }
                        toastOnUi("已更新音频调节"); reloadListsLight()
                    }
                }
                cancelButton()
            }.show()
        }
    }

    private fun showGlobalAudioParamsDialog() {
        val curSpeed = AppConfig.ttsPostSpeed
        val curVolume = AppConfig.ttsPostVolume
        val curPitch = AppConfig.ttsPostPitch

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0)
        }
        container.addView(TextView(this).apply {
            text = "全局音频后处理调节（合成后生效）\n1.0 = 不调整，范围 0.1~3.0"
            textSize = 12f; setPadding(4, 4, 4, 12)
        })
        val sbSpeed = buildSliderRow("语速", (curSpeed * 100).toInt().coerceIn(1, 300), false)
        val sbVol = buildSliderRow("音量", (curVolume * 100).toInt().coerceIn(1, 300), false)
        val sbPitch = buildSliderRow("音高", (curPitch * 100).toInt().coerceIn(1, 300), false)
        listOf(sbSpeed, sbVol, sbPitch).forEach { container.addView(it.second) }
        alert("全局音频调节") {
            customView { container }
            okButton {
                AppConfig.ttsPostSpeed = sbSpeed.first.progress / 100f
                AppConfig.ttsPostVolume = sbVol.first.progress / 100f
                AppConfig.ttsPostPitch = sbPitch.first.progress / 100f
                toastOnUi("已更新全局音频调节")
            }
            cancelButton()
        }.show()
    }

    /**
     * 快节奏播放（去除静音）全局设置弹窗。
     * 复刻 tts_server_android 的 SilenceSkipAudioDialog：
     * 总开关 + 最小静音时长（毫秒，范围 150~2000，默认 200）。
     */
    private fun showFastPlayDialog() {
        val enabled = AppConfig.ttsSilenceSkipEnabled
        val minMs = AppConfig.ttsSilenceSkipMinMs

        // 总开关行
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(48, 16, 48, 8)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val switchText = TextView(this).apply {
            text = "启用快节奏播放（去除静音）"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = android.widget.Switch(this).apply { isChecked = enabled }
        switchRow.addView(switchText)
        switchRow.addView(switch)

        // 最小静音时长输入
        val etMin = EditText(this).apply {
            setText(minMs.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "最小静音时长 (毫秒)"
            setPadding(48, 8, 48, 8)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(switchRow)
            addView(TextView(this@TtsPluginActivity).apply {
                text = "低于该时长的停顿不处理（范围 150~2000 毫秒）"
                textSize = 12f; setPadding(48, 0, 48, 8)
            })
            addView(etMin)
        }

        alert("快节奏播放") {
            customView { container }
            okButton {
                val parsed = etMin.text.toString().toIntOrNull() ?: minMs
                AppConfig.ttsSilenceSkipEnabled = switch.isChecked
                AppConfig.ttsSilenceSkipMinMs = parsed.coerceIn(150, 2000)
                toastOnUi("已更新快节奏播放设置")
            }
            cancelButton()
        }.show()
    }

    private fun showAddGroupDialog() {
        val etGroup = EditText(this).apply { hint = "一级分组名称"; setPadding(48, 24, 48, 24) }
        val etSubGroup = EditText(this).apply { hint = "二级分组名称 (可选)"; setPadding(48, 24, 48, 24) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 0)
            addView(etGroup); addView(etSubGroup)
        }
        alert("新增分组") {
            customView { container }
            okButton {
                val groupName = etGroup.text.toString().trim()
                if (groupName.isBlank()) { toastOnUi("分组名称不能为空"); return@okButton }
                val subGroupName = etSubGroup.text.toString().trim()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        JReadVoiceEngine.saveGroup(this@TtsPluginActivity, JReadVoiceEngine.VoiceGroup(groupName = groupName, subGroupName = subGroupName))
                    }
                    toastOnUi("已新增分组: $groupName"); reloadListsLight()
                }
            }
            cancelButton()
        }
    }

    private fun showPluginOptions(plugin: JReadVoiceEngine.VoicePlugin) {
        val options = arrayOf("编辑插件", "设置变量", "预览界面", "音频参数", "清空数据", "删除插件")
        android.app.AlertDialog.Builder(this)
            .setTitle(plugin.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPluginEditor(plugin)
                    1 -> showPluginVarsDialog(plugin)
                    2 -> showPluginPreviewUi(plugin)
                    3 -> showPluginAudioParams(plugin)
                    4 -> clearPluginData(plugin)
                    5 -> deletePlugin(plugin)
                }
            }.show()
    }

    /**
     * 设置变量：执行插件 JS，调用其 EditorJS/PluginJS.onLoadUI 自绘变量 UI
     * （支持任意多个输入框/下拉框），确认后把值写回插件 userVarsJson，
     * 合成时脚本通过 ttsrv.data 读取。插件未实现 onLoadUI 时提示无可设置变量。
     */
    private fun showPluginVarsDialog(plugin: JReadVoiceEngine.VoicePlugin) {
        pluginDialogs.showVars(plugin) { reloadListsLight() }
    }

    private fun clearPluginData(plugin: JReadVoiceEngine.VoicePlugin) {
        pluginDialogs.clearData(plugin) { reloadListsLight() }
    }

    /** 预览插件自绘界面（列表页直接预览，不必先进入编辑器） */
    private fun showPluginPreviewUi(plugin: JReadVoiceEngine.VoicePlugin) {
        pluginDialogs.showPreviewUi(plugin)
    }

    /**
     * 新增 / 编辑插件：统一打开 [PluginEditorActivity]，界面完全共用。
     *
     * 对齐 TTS_Server_Android 的做法——新增只是传一个空的 VoicePlugin，
     * 两者的唯一差别是「代码为空时由编辑器填入内置模板」
     * （见 PluginEditorActivity.loadTemplateCode）。
     * 之前这里是 if (plugin == null) 两套 EditText 对话框，
     * 既没有语法高亮/搜索/折叠，又要把几千行 JS 塞进 ScrollView 里的 EditText，
     * 输入时还要跑全文正则，越输越卡。
     */
    private fun showPluginEditor(plugin: JReadVoiceEngine.VoicePlugin?) {
        // 只传 id，不传整包 JSON：插件 code 动辄几 MB，塞进 Intent 会触发
        // TransactionTooLargeException（Binder 事务上限约 1MB）。新增时 id 为空，
        // 编辑器会新建一个 VoicePlugin 并填入内置模板。
        pluginEditorLauncher.launch(Intent(this, PluginEditorActivity::class.java).apply {
            plugin?.id?.takeIf { it.isNotBlank() }
                ?.let { putExtra(PluginEditorActivity.EXTRA_PLUGIN_ID, it) }
        })
    }

    private fun togglePlugin(plugin: JReadVoiceEngine.VoicePlugin, enabled: Boolean) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { JReadVoiceEngine.savePlugin(this@TtsPluginActivity, plugin.copy(enabled = enabled)) } }
    }

    private fun deletePlugin(plugin: JReadVoiceEngine.VoicePlugin) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { JReadVoiceEngine.deletePlugin(this@TtsPluginActivity, plugin.id) }
            toastOnUi("已删除插件: ${plugin.name}"); reloadListsLight()
        }
    }

    /**
     * 构建一个滑块行：标签+数值居中在上方，减号+滑块+加号在下方
     * @param label 标签名（语速/音量/音高/音调）
     * @param initialProgress 初始进度 (0~300)
     * @param allowFollow 是否支持"跟随"（progress=0 时显示"跟随"）
     * @return Triple(SeekBar, TextView, 更新显示的函数)
     */
    private fun showPluginAudioParams(plugin: JReadVoiceEngine.VoicePlugin) {
        pluginDialogs.showAudioParams(plugin) { reloadListsLight() }
    }

    /** 滑块行统一由 [PluginEditDialogs] 提供，分组/全局音频调节复用同一套控件 */
    private fun buildSliderRow(
        label: String,
        initialProgress: Int,
        allowFollow: Boolean,
    ): Pair<SeekBar, android.view.View> =
        PluginEditDialogs.buildSliderRow(this, label, initialProgress, allowFollow)

    private fun doImport(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: run { toastOnUi("无法读取文件"); return@withContext 0 }
                    when (currentTab) {
                        TAB_CONFIGS -> {
                            val text = bytes.toString(Charsets.UTF_8)
                            // 使用稳定 id + 一次性写盘（避免逐条 saveConfig 的 N 次全量写盘导致导入卡顿）
                            JReadVoiceEngine.importConfigsFromJson(this@TtsPluginActivity, text, useStableId = true)
                        }
                        TAB_PLUGINS -> JReadVoiceEngine.importPluginsFromPackageBytes(this@TtsPluginActivity, bytes)
                        else -> 0
                    }
                }
                if (count > 0) {
                    // 仅重读配置/插件列表（不全量 loadData），再局部刷新，避免卡顿
                    allConfigs = JReadVoiceEngine.listConfigs(this@TtsPluginActivity)
                    allPlugins = JReadVoiceEngine.listPlugins(this@TtsPluginActivity)
                    pluginsMap = buildPluginsIndex(allPlugins)
                    adapter.setPluginsMap(pluginsMap)
                }
                toastOnUi("导入了 $count 条")
                refreshListLight()
            } catch (e: Exception) {
                toastOnUi("导入失败: ${e.message}")
            }
        }
    }

    /**
     * 导出单个插件：复用整体导出那个 CreateDocument launcher，
     * 用 [pendingExportPlugin] 区分「导这一个」与「整包导出」两种目标。
     */
    private fun exportSinglePlugin(plugin: JReadVoiceEngine.VoicePlugin) {
        pendingExportPlugin = plugin
        // 名字里可能带 / \ : 等文件名非法字符，先洗掉再当默认文件名
        val safeName = plugin.name.trim().replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .ifBlank { "plugin" }
        exportLauncher.launch("ttsrv-plugin-$safeName.json")
    }

    private fun doExport(uri: android.net.Uri) {
        // 取一次就清掉：用户可能在选文件框里取消，残留会让下一次整体导出误导成单插件
        val single = pendingExportPlugin
        pendingExportPlugin = null
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                if (single != null) {
                    // 单插件导出与整包同格式（plugins 数组里只有这一个），可直接再导入
                    JReadVoiceEngine.exportPluginJson(this@TtsPluginActivity, single.id)
                } else {
                    when (currentTab) {
                        TAB_CONFIGS -> JReadVoiceEngine.exportConfigsJson(this@TtsPluginActivity)
                        TAB_PLUGINS -> JReadVoiceEngine.exportPluginsJson(
                            this@TtsPluginActivity, includeUserVars = false
                        )
                        else -> "[]"
                    }
                }
            }
            if (json == null) {
                toastOnUi("导出失败：找不到该插件")
                return@launch
            }
            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            toastOnUi(if (single != null) "已导出插件：${single.name}" else "导出完成")
        }
    }

    override fun onDestroy() {
        stopLogPolling()
        super.onDestroy()
        mediaPlayer?.release(); mediaPlayer = null
    }

    companion object {
        private const val TAB_CONFIGS = 0
        private const val TAB_LOGS = 1
        private const val TAB_PLUGINS = 2
        /** 日志页自动刷新间隔：内存日志没有监听器，靠轮询把新日志刷出来 */
        private const val LOG_POLL_INTERVAL_MS = 500L
        /** 空分组名在列表中的展示名，落盘匹配时沿用同一口径 */
        private const val DEFAULT_GROUP_NAME = "默认分组"
        private const val DEFAULT_SUB_GROUP_NAME = "默认"
    }
}
