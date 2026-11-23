package io.legado.app.ui.soundeffect

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.databinding.ActivitySoundEffectRuleBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.audio.Mp3GainUtil
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.service.AiEffectDownloader
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private var targetAiHandled = false

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
            showImportModeDialog(parsed, "本地文件")
        }
    }

    private val exportDoc = registerForActivityResult(HandleFileContract()) { result ->
        if (result.uri != null) toastOnUi("导出成功")
    }

    /**
     * 新增音效：多选音频，全部写入一个 JSON，播放时按顺序轮换。
     * 选完后需要输入音效名。
     */
    private val pickAudiosForSingle =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            showInputDialog("新增音效", "", "音效名，例如：打雷音效") { name ->
                writeSoundEffect(name, uris)
            }
        }

    /**
     * 批量添加音效：多选音频，每个文件各自生成一个 JSON，
     * 音效名直接取文件名，无需输入。
     */
    private val pickAudiosForBatch =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            writeSoundEffectBatch(uris)
        }

    /** 音效文件编辑弹窗：添加音频时记住要追加到哪个音效 JSON */
    private var soundEffectFileDialog: SoundEffectVolumeDialog? = null
    private var pendingAppendEffectName: String? = null

    private val pickAudiosForAppend =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val name = pendingAppendEffectName ?: return@registerForActivityResult
            pendingAppendEffectName = null
            if (uris.isEmpty()) return@registerForActivityResult
            showLoading("正在读取音频…")
            lifecycleScope.launch {
                val audios = withContext(IO) {
                    uris.mapNotNull { SoundEffectFileManager.readBytes(this@SoundEffectRuleActivity, it) }
                }
                hideLoading()
                if (audios.isEmpty()) {
                    toastOnUi("没有读到音频内容")
                    return@launch
                }
                if (audios.size < uris.size) {
                    toastOnUi("有 ${uris.size - audios.size} 个文件读取失败，将跳过")
                }
                soundEffectFileDialog?.addAudios(name, audios)
            }
        }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSearchView()
        initSelectActionView()
        initModeToggle()
        if (intent.getStringExtra(EXTRA_MODE) == SoundEffectRuleManager.MODE_AI) {
            switchMode(SoundEffectRuleManager.MODE_AI, onSwitched = ::openTargetAiFromIntent)
        } else {
            prepare()
        }
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
            val newMode = modeOfCheckedId(checkedId)
            if (newMode != viewModel.mode) switchMode(newMode)
        }
    }

    private fun modeOfCheckedId(checkedId: Int): String = when (checkedId) {
        R.id.rb_mode_normal -> SoundEffectRuleManager.MODE_NORMAL
        R.id.rb_mode_transition -> SoundEffectRuleManager.MODE_TRANSITION
        R.id.rb_mode_ai -> SoundEffectRuleManager.MODE_AI
        else -> SoundEffectRuleManager.MODE_ALL
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

    private fun switchMode(newMode: String, onSwitched: (() -> Unit)? = null) {
        if (busy) {
            revertToggle()
            return
        }
        busy = true
        val doSwitch = {
            viewModel.switchMode(newMode) { error ->
                busy = false
                error?.let { toastOnUi(it) }
                invalidateOptionsMenu()
                adapter.clearSelection()
                searchView.setQuery("", false)
                binding.rgMode.check(checkedIdOfMode(viewModel.mode))
                upPathView()
                refreshList(keepExpanded = false)
                onSwitched?.invoke()
            }
        }
        // 改动已即时落盘；只是切换/浏览（没改过东西）时不要陪写一次文件
        if (!viewModel.dirty) {
            doSwitch()
            return
        }
        showLoading("正在保存…")
        viewModel.save { saveError ->
            hideLoading()
            if (saveError != null) {
                busy = false
                toastOnUi(saveError)
                revertToggle()
                return@save
            }
            viewModel.clearDirty()
            doSwitch()
        }
    }

    private fun revertToggle() {
        binding.rgMode.check(checkedIdOfMode(viewModel.mode))
    }

    private fun checkedIdOfMode(mode: String): Int = when (mode) {
        SoundEffectRuleManager.MODE_NORMAL -> R.id.rb_mode_normal
        SoundEffectRuleManager.MODE_TRANSITION -> R.id.rb_mode_transition
        SoundEffectRuleManager.MODE_AI -> R.id.rb_mode_ai
        else -> R.id.rb_mode_all
    }

    private fun upPathView() {
        binding.tvPath.text =
            if (viewModel.mode == SoundEffectRuleManager.MODE_AI) {
                SoundEffectRuleManager.aiIndexPath()
            } else {
                SoundEffectRuleManager.fileOf(viewModel.mode).absolutePath
            }
    }

    private fun refreshList(keepExpanded: Boolean = true) {
        // AI 模式改变试听与点击事件的行为，必须在提交数据前设置
        adapter.aiMode = viewModel.mode == SoundEffectRuleManager.MODE_AI
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
        // AI 音效是音效库目录，不是规则文件：新增规则/分组、导入、
        // 以及「新增音效」(写 base64 JSON) 都不适用，只保留展开收起与导出
        if (viewModel.mode == SoundEffectRuleManager.MODE_AI) {
            listOf(
                R.id.menu_add_rule,
                R.id.menu_add_group,
                R.id.menu_add_sound_effect,
                R.id.menu_batch_add_sound_effect,
                R.id.menu_import_local,
                R.id.menu_import_clipboard,
                R.id.menu_export_clipboard,
                R.id.menu_manage_clipboard
            ).forEach { menu.findItem(it)?.isVisible = false }
        }
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_rule -> startEdit(null, defaultGroupId())
            R.id.menu_add_group -> showAddGroupDialog()
            R.id.menu_add_sound_effect -> pickAudiosForSingle.launch(arrayOf(AUDIO_MIME))
            R.id.menu_batch_add_sound_effect -> pickAudiosForBatch.launch(arrayOf(AUDIO_MIME))
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("json", "txt")
            }
            R.id.menu_import_clipboard -> importFromClipboard()
            R.id.menu_export -> exportCurrent()
            R.id.menu_export_clipboard -> exportToClipboard(
                "${SoundEffectRuleManager.modeTitle(viewModel.mode)} · 全部分组",
                viewModel.data
            )
            R.id.menu_manage_clipboard -> manageClipboard()
            R.id.menu_download_sound_and_music ->
                io.legado.app.ui.book.read.config.BgmDownloadDialog()
                    .show(supportFragmentManager, "soundEffectDownload")
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
                if (index >= 0) {
                    // 编辑页什么都没改却点了保存：只刷新列表，不写文件
                    if (current.list[index] == rule) {
                        refreshList()
                        return
                    }
                    current.list[index] = rule
                }
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
        // AI 音效没有正则类字段，编辑入口就是「正名 + 检索关键词」这一行，
        // 改了正名会连同本地文件一起改名（见 [showRenameAiSoundDialog]）
        if (viewModel.mode == SoundEffectRuleManager.MODE_AI) {
            showRenameAiSoundDialog(rule)
        } else {
            startEdit(rule, findGroupOfRule(rule.id)?.group?.id ?: defaultGroupId())
        }
    }

    /**
     * 前三种模式：修改音效文件。
     * `replacement` 里的每个 `(音效名)` 对应一个 JSON，按 JSON 分组显示组内的多个 MP3。
     */
    override fun editSoundFiles(rule: SoundEffectRule) {
        val segments = buildSoundFileSegments(rule)
        soundEffectFileDialog = SoundEffectVolumeDialog(
            activity = this,
            sourceSegments = segments,
            onPickAudios = { name ->
                pendingAppendEffectName = name
                pickAudiosForAppend.launch(arrayOf("audio/*"))
            },
            onSave = { states -> saveVolumes(states) },
            onDismiss = { soundEffectFileDialog = null }
        ).also { it.show() }
    }

    /** 把规则里引用的所有音效 JSON 展开成弹窗用的段列表。 */
    private fun buildSoundFileSegments(rule: SoundEffectRule): List<SoundEffectVolumeDialog.Segment> {
        val cn = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        val displayName = rule.name.ifBlank { rule.pattern }
        val segments = ArrayList<SoundEffectVolumeDialog.Segment>()
        val labelIndexByEffect = HashMap<String, Int>()

        SoundEffectPlayer.extractEffectNames(rule.replacement).forEach { name ->
            val audio = SoundEffectFileManager.readAudioJson(name) ?: return@forEach
            var labelIndex = labelIndexByEffect[name] ?: 0
            audio.audios.forEachIndexed { index, item ->
                val info = Mp3GainUtil.readGain(item)
                val v = Mp3GainUtil.validate(item)
                val label = "音效" + (cn.getOrNull(labelIndex) ?: (labelIndex + 1).toString())
                labelIndex++
                segments.add(
                    SoundEffectVolumeDialog.Segment(
                        effectName = name,
                        indexInFile = index,
                        displayName = displayName,
                        effectLabel = "($name)",
                        label = label,
                        originalBytes = item,
                        supported = v.ok,
                        reason = v.reason,
                        currentGain = info.current
                    )
                )
            }
            labelIndexByEffect[name] = labelIndex
        }
        return segments
    }

    /** AI 音效模式仍是裸 MP3，只保留原来的音量调整。 */
    override fun adjustVolume(rule: SoundEffectRule) {
        val displayName = rule.name.ifBlank { rule.pattern }
        val segments = ArrayList<SoundEffectVolumeDialog.Segment>()
        val file = AiEffectDownloader.localFileOf(rule.pattern)
        val bytes = if (file.exists() && file.length() > 0L) {
            runCatching { file.readBytes() }.getOrNull()
        } else null
        if (bytes != null) {
            val info = Mp3GainUtil.readGain(bytes)
            val v = Mp3GainUtil.validate(bytes)
            segments.add(
                SoundEffectVolumeDialog.Segment(
                    effectName = rule.pattern,
                    indexInFile = 0,
                    displayName = displayName,
                    effectLabel = "",
                    label = "音效一",
                    originalBytes = bytes,
                    supported = v.ok,
                    reason = v.reason,
                    currentGain = info.current,
                    audioFile = file
                )
            )
        }
        SoundEffectVolumeDialog(
            activity = this,
            sourceSegments = segments,
            onPickAudios = {},
            onSave = { states -> saveVolumes(states) }
        ).show()
    }

    /** 点「保存」才落盘：按音效名分组，应用删除、音量修改与新增，再写回 JSON。 */
    private fun saveVolumes(states: List<SoundEffectVolumeDialog.SegmentState>) {
        if (states.isEmpty()) {
            toastOnUi("没有可保存的音效")
            return
        }
        val hasJsonFiles = states.any { it.segment.audioFile == null }
        showLoading(if (hasJsonFiles) "正在保存音效文件…" else "正在保存音量…")
        lifecycleScope.launch {
            val errors = withContext(IO) {
                val errs = ArrayList<String>()
                // AI 音效（裸 MP3）：调整结果直接写回原文件
                states.filter { it.segment.audioFile != null }.forEach { st ->
                    val file = st.segment.audioFile ?: return@forEach
                    writeAiEffectMp3(file, st.bytes)?.let { errs.add("${file.name}: $it") }
                }
                // 前三种（base64 JSON）：按弹窗当前显示顺序重写 audios，删除项已被弹窗过滤。
                // 段对象带着自己的 label 移动，所以未保存时「音效六」拖到最上面仍叫「音效六」；
                // 保存后数组顺序改变，下次打开会重新按 index 编号为音效一、音效二……
                states.filter { it.segment.audioFile == null }
                    .groupBy { it.segment.effectName }
                    .forEach { (name, list) ->
                        val json = SoundEffectFileManager.readAudioJson(name)
                        if (json == null) {
                            errs.add(name)
                            return@forEach
                        }
                        val orderedAudios = list.map { it.bytes }.toMutableList()
                        // 尽量保持原来 currentIndex 指向的那一段仍然是下一段要播的音频
                        val anchorState = list.firstOrNull {
                            !it.segment.isNew && it.segment.indexInFile == json.currentIndex
                        }
                        val newIndex = when {
                            orderedAudios.isEmpty() -> 0
                            anchorState != null -> list.indexOf(anchorState).coerceIn(0, orderedAudios.lastIndex)
                            else -> json.currentIndex.coerceIn(0, orderedAudios.lastIndex)
                        }
                        SoundEffectFileManager.saveAudioJson(name, newIndex, orderedAudios)
                            ?.let { errs.add("$name: $it") }
                    }
                errs
            }
            hideLoading()
            if (errors.isEmpty()) {
                toastOnUi(if (hasJsonFiles) "音效文件已保存" else "音量已保存")
            } else {
                toastOnUi("保存失败：${errors.joinToString("、")}")
            }
        }
    }

    /** AI 音效：把调整后的 MP3 字节原子写回原文件（同一目录 tmp + rename，不产生额外副本） */
    private fun writeAiEffectMp3(file: File, bytes: ByteArray): String? {
        val tmp = File(file.absolutePath + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
            AppLog.put("[音效文件] AI音效音量已写回：${file.absolutePath}")
            null
        } catch (e: Exception) {
            tmp.delete()
            AppLog.put("[音效文件] AI音效写入失败：${e.message}", e)
            e.message ?: "保存失败"
        }
    }

    /** 从阅读页 AI 音效标记跳转过来时，自动打开对应 AI 条目的编辑弹窗。 */
    private fun openTargetAiFromIntent() {
        if (targetAiHandled) return
        val targetPath = intent.getStringExtra(EXTRA_TARGET_AI_PATH).orEmpty()
        val targetName = intent.getStringExtra(EXTRA_TARGET_AI_NAME).orEmpty()
        if (targetPath.isBlank() && targetName.isBlank()) return
        targetAiHandled = true

        val rule = viewModel.data.flatMap { it.list }.firstOrNull { candidate ->
            (targetPath.isNotBlank() && candidate.pattern == targetPath) ||
                (targetName.isNotBlank() && candidate.name == targetName)
        }
        if (rule == null) {
            toastOnUi("没有找到对应的 AI 音效条目")
            return
        }
        showRenameAiSoundDialog(rule)
    }

    // ---------------- AI 音效改名 ----------------

    /**
     * 改名并同步重命名本地音效文件。
     *
     * 顺序很重要：先改文件，成功后再更新内存数据与索引。
     * 文件改名不可逆，若先改内存再改文件失败，索引会指向不存在的文件。
     */
    /**
     * AI 音效的编辑入口：改的是**一整行** `正名|关键词|关键词…`。
     *
     * - `|` 之前是正名，同时是音频文件名，改了会同步重命名本地 mp3；
     * - `|` 之后是检索关键词，只写进索引，不碰文件。
     *
     * 两样放在一个输入框里，是因为它们本来就是同一条索引记录的两半，
     * 分开两个框反而容易改了一个忘了另一个。
     */
    private fun showRenameAiSoundDialog(rule: SoundEffectRule) {
        val oldPath = rule.pattern
        val oldKeywords = SoundEffectRuleManager.parseAiKeywords(rule.sampleText)
        val oldText = SoundEffectRuleManager.joinAiName(rule.name, oldKeywords)
        val input = android.widget.EditText(this).apply {
            setText(oldText)
            setSelectAllOnFocus(true)
            setSingleLine(false)
            setMaxLines(3)
            hint = "正名|关键词|关键词"
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        alert("AI音效（改名与关键词）") {
            customView { container }
            neutralButton("重置为原名")
            noButton()
            yesButton {
                val newText = input.text?.toString()?.trim().orEmpty()
                if (newText.isEmpty() || newText == oldText) return@yesButton
                renameAiSound(rule, oldPath, newText)
            }
        }.apply {
            // 重置：把曾经改过的名字改回原名（原名在 pattern 的文件名部分丢失前可用），
            // 关键词保持当前这版不动
            getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val original = oldPath.substringAfterLast('/').removeSuffix(".mp3")
                val reset = SoundEffectRuleManager.joinAiName(original, oldKeywords)
                if (original.isNotBlank() && reset != oldText) {
                    renameAiSound(rule, oldPath, reset)
                }
                dismiss()
            }
        }
    }

    /**
     * 应用编辑结果：先重命名本地文件（仅在正名真的变了时），再更新索引。
     *
     * **只改关键词时不需要本地音频文件**——不碰文件名就谈不上远端 404，
     * 所以文件是否存在的校验推迟到这里，按是否改名决定。
     */
    private fun renameAiSound(rule: SoundEffectRule, oldPath: String, rawText: String) {
        val (rawName, keywords) = SoundEffectRuleManager.splitAiName(rawText)
        if (rawName.isBlank()) {
            toastOnUi("名字不能为空")
            return
        }
        val safeName = rawName.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val nameChanged = safeName != rule.name
        if (nameChanged) {
            // 改名会同步改本地文件名，而远端只存有原名的文件，
            // 改后 AI 再选中它就会按新名下载并 404。先试听一次即可把文件下载到本地。
            val localFile = java.io.File(AppConfig.aiEffectLocalDir, oldPath)
            if (!localFile.exists()) {
                alert("无法改名") {
                    setMessage(
                        "本地还没有这个音效的音频文件，无法改名。\n\n" +
                            "改名会同时修改本地文件名，而远端仓库只保存原名的文件，" +
                            "直接改名会导致以后按新名字下载失败。\n\n" +
                            "请先点试听把它下载到本地，再改名。\n" +
                            "（只改关键词不受影响，可以照常保存。）"
                    )
                    noButton()
                    yesButton { }
                }
                return
            }
        }
        val prefix = oldPath.substringBeforeLast('/', "")
        val newPath = if (prefix.isEmpty()) "$safeName.mp3" else "$prefix/$safeName.mp3"
        showLoading(if (nameChanged) "正在改名…" else "正在保存…")
        lifecycleScope.launch {
            val error = withContext(IO) {
                runCatching {
                    if (nameChanged) {
                        val oldFile = java.io.File(AppConfig.aiEffectLocalDir, oldPath)
                        val newFile = java.io.File(AppConfig.aiEffectLocalDir, newPath)
                        // 调用前已确保 oldFile 存在（否则不允许改名）
                        if (oldFile.exists() && !newFile.exists()) {
                            newFile.parentFile?.mkdirs()
                            if (!oldFile.renameTo(newFile)) {
                                // 跨存储卷时 renameTo 会失败，退化为复制
                                oldFile.copyTo(newFile)
                                oldFile.delete()
                            }
                        }
                    }
                }.exceptionOrNull()?.localizedMessage
            }
            hideLoading()
            if (error != null) {
                toastOnUi("改名失败：$error")
                return@launch
            }
            update(
                rule.copy(
                    name = safeName,
                    pattern = newPath,
                    sampleText = keywords.joinToString("|")
                )
            )
        }
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

    /** 真改动才走这里：标记未落盘 → 即时保存 → 刷新列表；浏览类操作不会调到这里 */
    override fun onDataChanged() {
        viewModel.markDirty()
        viewModel.save { msg ->
            if (msg == null) viewModel.clearDirty() else toastOnUi(msg)
        }
        adapter.resyncSelection()
        refreshList()
    }

    // ---------------- 分组操作 ----------------

    override fun onGroupEnableToggle(groupId: Long, enable: Boolean) {
        val gwr = findGroup(groupId) ?: return
        var changed = false
        gwr.list.forEachIndexed { index, rule ->
            if (rule.isEnabled != enable) {
                gwr.list[index] = rule.copy(isEnabled = enable)
                changed = true
            }
        }
        if (changed) onDataChanged()
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
            if (text == gwr.group.name) return@showInputDialog
            gwr.group.name = text
            onDataChanged()
        }
    }

    override fun onGroupAddRule(groupId: Long) = startEdit(null, groupId)

    override fun onGroupExportClipboard(groupId: Long) {
        val gwr = findGroup(groupId) ?: return
        exportToClipboard(
            "${SoundEffectRuleManager.modeTitle(viewModel.mode)} · ${gwr.group.name}",
            listOf(gwr)
        )
    }

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
        hint: String = "",
        onConfirm: (String) -> Unit
    ) {
        val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setText(default)
            editView.setSelection(default.length)
            if (hint.isNotEmpty()) editView.hint = hint
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

    // ---------------- 音效文件 ----------------

    /**
     * 新增音效：把选中的多个音频写入一个音效文件。
     * 多个音频在播放时按 currentIndex 顺序轮换。
     */
    private fun writeSoundEffect(name: String, uris: List<Uri>) {
        showLoading("正在读取音频…")
        lifecycleScope.launch {
            val audios = withContext(IO) {
                uris.mapNotNull { SoundEffectFileManager.readBytes(this@SoundEffectRuleActivity, it) }
            }
            hideLoading()
            if (audios.isEmpty()) {
                toastOnUi("没有读到音频内容")
                return@launch
            }
            if (audios.size < uris.size) {
                toastOnUi("有 ${uris.size - audios.size} 个文件读取失败，将跳过")
            }
            showLoading("正在写入 ${audios.size} 个音频…")
            val result = withContext(IO) {
                SoundEffectFileManager.save(name, audios)
            }
            hideLoading()
            if (result.error != null) {
                toastOnUi(result.error)
            } else {
                toastOnUi(
                    buildString {
                        append("已保存「$name」，共 ${audios.size} 个音频")
                        if (result.cleanedMp3 > 0) {
                            append("，已清除旧音频 ${result.cleanedMp3} 个")
                        }
                        append("\n规则中填写：($name)")
                    }
                )
            }
        }
    }

    /**
     * 批量添加音效：每个音频各自生成一个音效文件，文件名取自音频文件名。
     */
    private fun writeSoundEffectBatch(uris: List<Uri>) {
        showLoading("正在处理 0/${uris.size}…")
        lifecycleScope.launch {
            var success = 0
            var replaced = 0
            val failed = mutableListOf<String>()
            val names = mutableListOf<String>()
            withContext(IO) {
                uris.forEachIndexed { index, uri ->
                    val name = SoundEffectFileManager.displayNameOf(
                        this@SoundEffectRuleActivity, uri
                    )
                    val audio = SoundEffectFileManager.readBytes(
                        this@SoundEffectRuleActivity, uri
                    )
                    withContext(Dispatchers.Main) {
                        showLoading("正在处理 ${index + 1}/${uris.size}…")
                    }
                    if (audio == null) {
                        failed.add(name)
                        return@forEachIndexed
                    }
                    val result = SoundEffectFileManager.saveSingle(name, audio)
                    if (result.error == null) {
                        success++
                        names.add(name)
                        if (result.cleanedMp3 > 0) replaced += result.cleanedMp3
                    } else {
                        failed.add(name)
                    }
                }
            }
            hideLoading()
            val msg = buildString {
                append("成功 $success 个")
                if (replaced > 0) append("，已清除旧音频 $replaced 个")
                if (failed.isNotEmpty()) {
                    append("，失败 ${failed.size} 个：${failed.joinToString("、")}")
                }
            }
            toastOnUi(msg)
            if (names.isNotEmpty()) {
                AppLog.put("[音效文件] 批量添加成功：${names.joinToString("、")}")
            }
        }
    }

    // ---------------- 导入 / 导出 ----------------

    private fun exportCurrent() {
        showLoading("正在生成文件…")
        lifecycleScope.launch {
            val isAi = viewModel.mode == SoundEffectRuleManager.MODE_AI
            val fileName: String
            val json = if (isAi) {
                // AI 索引有自己的结构，直接导出索引文件原文，便于备份与在电脑上编辑
                fileName = io.legado.app.service.AiEffectIndex.INDEX_FILE_NAME
                withContext(IO) {
                    runCatching {
                        io.legado.app.service.AiEffectIndex.indexFile().readText()
                    }.getOrDefault(SoundEffectRuleManager.toJson(viewModel.data))
                }
            } else {
                fileName = SoundEffectRuleManager.fileNameOf(viewModel.mode)
                withContext(IO) { SoundEffectRuleManager.toJson(viewModel.data) }
            }
            hideLoading()
            exportDoc.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    fileName,
                    json.toByteArray(),
                    "application/json"
                )
            }
        }
    }

    /**
     * 导入前先选方式：追加到末尾（保留现有）或覆盖当前分类的全部规则。
     */
    private fun showImportModeDialog(
        parsed: List<SoundEffectGroupWithRules>,
        source: String
    ) {
        if (parsed.isEmpty()) {
            toastOnUi("没有可导入的分组")
            return
        }
        val count = parsed.sumOf { it.list.size }
        // 不能同时传 message 和 items：AppCompat 1.7.1 的 AlertController 在 message 非空时
        // 不会把 setItems 的 ListView 挂进对话框，列表项会完全不显示。信息只能放在标题里。
        alert("$source：${parsed.size} 组 / $count 条 · 选择导入方式") {
            items(listOf("追加到当前分组末尾（保留现有）", "覆盖当前所有分组")) { _, index ->
                applyImport(parsed, append = index == 0)
            }
            cancelButton()
        }
    }

    private fun applyImport(parsed: List<SoundEffectGroupWithRules>, append: Boolean) {
        if (append) viewModel.appendGroups(parsed) else viewModel.replaceAll(parsed)
        adapter.clearSelection()
        onDataChanged()
        toastOnUi(
            if (append) "已在末尾追加 ${parsed.size} 个分组，可拖动调整顺序"
            else "已覆盖当前规则"
        )
    }

    /** 右上角：从临时内存导入。只有一份时跳过选择，直接问导入方式 */
    private fun importFromClipboard() {
        val entries = SoundEffectClipboard.all()
        if (entries.isEmpty()) {
            toastOnUi("临时内存为空，请先导出规则或分组")
            return
        }
        if (entries.size == 1) {
            showImportModeDialog(entries[0].groups, "临时内存「${entries[0].label}」")
            return
        }
        alert("从临时内存导入（共 ${entries.size} 份）") {
            items(entries.map { it.summary() }) { _, index ->
                val entry = entries[index]
                showImportModeDialog(entry.groups, "临时内存「${entry.label}」")
            }
            neutralButton("管理临时内存") { manageClipboard() }
            cancelButton()
        }
    }

    /** 导出当前列表或某个分组到临时内存，供其它分类导入 */
    private fun exportToClipboard(label: String, groups: List<SoundEffectGroupWithRules>) {
        if (groups.isEmpty()) {
            toastOnUi("没有可导出的内容")
            return
        }
        val count = groups.sumOf { it.list.size }
        lifecycleScope.launch(IO) {
            SoundEffectClipboard.add(label, groups)
            withContext(Dispatchers.Main) {
                toastOnUi("已导出到临时内存：$label（${groups.size} 组 / $count 条）")
            }
        }
    }

    /** 删除单份或清空临时内存 */
    private fun manageClipboard() {
        val entries = SoundEffectClipboard.all()
        if (entries.isEmpty()) {
            toastOnUi("临时内存为空")
            return
        }
        alert("管理临时内存（${entries.size}/${SoundEffectClipboard.MAX_ENTRIES} 份）") {
            items(entries.map { it.summary() }) { _, index ->
                val entry = entries[index]
                alert("删除暂存", "删除「${entry.label}」？") {
                    yesButton {
                        lifecycleScope.launch(IO) { SoundEffectClipboard.remove(entry.id) }
                    }
                    noButton()
                }
            }
            neutralButton("清空全部") {
                alert("清空临时内存", "确定清空全部 ${entries.size} 份暂存？") {
                    yesButton {
                        lifecycleScope.launch(IO) { SoundEffectClipboard.clear() }
                    }
                    noButton()
                }
            }
            cancelButton()
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

    companion object {
        /** 选择音频文件时使用的 MIME，放开所有音频格式 */
        private const val AUDIO_MIME = "audio/*"

        /** 阅读页音效标记跳转：模式、AI 音效名、AI 音效路径 */
        const val EXTRA_MODE = "extraMode"
        const val EXTRA_TARGET_AI_NAME = "extraTargetAiName"
        const val EXTRA_TARGET_AI_PATH = "extraTargetAiPath"
    }
}
