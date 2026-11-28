package io.legado.app.ui.book.read.config

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.MiyueKeyManager
import io.legado.app.model.CharacterProfileManager
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.DialogRoleManager
import io.legado.app.ui.book.toc.AiCacheFileUtil
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.defaultSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CharacterManagerDialog : DialogFragment() {

    private lateinit var rootView: View
    private lateinit var contentLayout: LinearLayout
    private lateinit var rvCharacters: RecyclerView
    private lateinit var tvCharacterLabel: TextView
    private lateinit var tvCharacterCount: TextView
    private lateinit var spinnerBook: Spinner
    private lateinit var btnSaveKey: Button
    private lateinit var btnRestoreKey: Button
    private lateinit var btnAddCharacter: Button
    private lateinit var btnMerge: Button
    private lateinit var btnChangeVoice: Button
    private lateinit var btnRelease: Button
    private lateinit var btnDeleteCharacter: Button
    private lateinit var btnCreateBook: Button
    private lateinit var btnBackupRestore: Button
    private lateinit var btnManageBooks: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSearch: ImageButton
    private lateinit var etSearchKeyword: EditText

    private var currentBook = "默认"
    private var bookList = mutableListOf<String>()
    private var characterRecords = JSONArray()
    // 发音人标签 -> 配置列表里的条目（仅内置TTS引擎插件下有值），打开界面时取一次
    private var voiceTagRefs: Map<String, CharacterVoiceHelper.VoiceTagRef> = emptyMap()
    // 是否跳转过配置编辑页：返回后需要重取标签映射、并让发音人缓存失效
    private var pendingVoiceConfigEdit = false
    private var markedIndices = mutableListOf<Int>()
    private var selectedIndex = -1
    private var longPressedIndex = -1
    private var spinnerInitialized = false  // 用于防止Spinner初始化时触发切换
    // 列表显示用的真实索引集合（筛选/搜索时只含命中项；无搜索时为全量索引）
    private var displayList: MutableList<Int> = mutableListOf()
    private var isSearchMode = false

    private lateinit var characterAdapter: CharacterAdapter

    // 打开界面瞬间按「当前页最下方引号」往回推测出的置顶主名（只影响显示顺序）
    private var pinnedMainNames: List<String> = emptyList()

    companion object {
        /** 往回推测时要凑够的「不同主名」数量；去重按主名，不按引号或别名 */
        private const val PINNED_MAIN_NAME_LIMIT = 10
    }

    // 颜色常量（适配深色模式）
    private var colorDefault = Color.parseColor("#333333")  // 默认文字色（浅色模式，深色模式会改为白色）
    private var colorMarked = Color.parseColor("#333333")   // 已标记未选中（浅色模式，深色模式会改为白色）
    private val colorSelected = Color.parseColor("#FF5722") // 已选中橙色
    private val colorOnlySelected = Color.parseColor("#1976D2") // 未标记已选中蓝色
    private val bgMarked = Color.parseColor("#FFF9C4")      // 已标记背景（浅色模式）
    private val bgMarkedDark = Color.parseColor("#4A4A4A")   // 已标记背景（深色模式）
    private val bgOnlySelected = Color.parseColor("#E3F2FD") // 未标记已选中背景（浅色模式）
    private val bgOnlySelectedDark = Color.parseColor("#1A3A5C") // 未标记已选中背景（深色模式）
    private val bgNormalDark = Color.parseColor("#2D2D2D")    // 普通背景（深色模式）

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        rootView = requireActivity().layoutInflater.inflate(
            R.layout.dialog_character_manager,
            null
        )
        initViews()
        initFileSystem()
        loadCharacters()
        setupListeners()
        setupRecyclerView()

        val dialog = Dialog(requireActivity(), R.style.dialog_style)
        dialog.setContentView(rootView)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.window?.setGravity(Gravity.CENTER)
        
        // 关键：在根布局上拦截触摸事件，确保RecyclerView区域的事件正确分发
        rootView.setOnTouchListener { v, event ->
            // 获取触摸点相对于 RecyclerView 的位置
            val rvBounds = android.graphics.Rect()
            rvCharacters.getGlobalVisibleRect(rvBounds)
            val touchX = event.rawX
            val touchY = event.rawY
            
            // 如果触摸点在 RecyclerView 区域内，返回 false 让 RecyclerView 处理滚动和点击
            if (touchX >= rvBounds.left && touchX <= rvBounds.right &&
                touchY >= rvBounds.top && touchY <= rvBounds.bottom) {
                // 在 RecyclerView 区域内，不拦截事件，让 RecyclerView 处理点击和滚动
                false
            } else {
                // 在其他区域，消费事件但不影响点击（按钮有自己的点击监听）
                true
            }
        }
        
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // 页面渲染完毕后，切换到当前阅读书籍
        autoSwitchToCurrentBook()
        // 切书时 useBook 会写盘，写完之后再按当前页推测一次，只改内存顺序；
        // 「置顶」本身不触发落盘，只有用户修改并保存时才把当前显示顺序写进三份文件
        collectPinnedMainNames()
        applyPinnedToRecords()
        if (::characterAdapter.isInitialized) {
            updateCharacterList()
            updateLabel()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从配置编辑页返回：标签/显示名可能已改动，重取映射并刷新列表
        if (!pendingVoiceConfigEdit) return
        pendingVoiceConfigEdit = false
        voiceTagRefs = CharacterVoiceHelper.getVoiceTagRefsForCurrentEngine()
        // 朗读脚本侧的发音人缓存同步失效，避免拿到编辑前的旧列表
        io.legado.app.help.VirtualExternalFiles.invalidate()
        if (::characterAdapter.isInitialized) characterAdapter.notifyDataSetChanged()
    }

    private fun initViews() {
        contentLayout = rootView.findViewById(R.id.content_layout)
        rvCharacters = rootView.findViewById(R.id.rv_characters)
        tvCharacterLabel = rootView.findViewById(R.id.tv_character_label)
        tvCharacterCount = rootView.findViewById(R.id.tv_character_count)
        spinnerBook = rootView.findViewById(R.id.spinner_book)
        btnSaveKey = rootView.findViewById(R.id.btn_save_key)
        btnRestoreKey = rootView.findViewById(R.id.btn_restore_key)
        btnAddCharacter = rootView.findViewById(R.id.btn_add_character)
        btnMerge = rootView.findViewById(R.id.btn_merge)
        btnChangeVoice = rootView.findViewById(R.id.btn_change_voice)
        btnRelease = rootView.findViewById(R.id.btn_release)
        btnDeleteCharacter = rootView.findViewById(R.id.btn_delete_character)
        btnCreateBook = rootView.findViewById(R.id.btn_create_book)
        btnBackupRestore = rootView.findViewById(R.id.btn_backup_restore)
        btnManageBooks = rootView.findViewById(R.id.btn_manage_books)
        btnRefresh = rootView.findViewById(R.id.btn_refresh)
        btnSearch = rootView.findViewById(R.id.btn_search)
        etSearchKeyword = rootView.findViewById(R.id.et_search_keyword)

        // 搜索模式切换：点搜索图标进入搜索（Spinner 变输入框），再次点击退出
        btnSearch.setOnClickListener { toggleSearchMode() }

        // 输入框实时筛选
        etSearchKeyword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateCharacterList()
                updateLabel()
            }
        })
        // 输入完成（回车/完成）收起键盘
        etSearchKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else false
        }

        // 初始化颜色（适配深色模式）
        initColors()
    }

    // 初始化颜色（适配深色模式）
    private fun initColors() {
        val isDarkMode = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (isDarkMode) {
            colorDefault = Color.WHITE
            colorMarked = Color.WHITE
        } else {
            colorDefault = Color.parseColor("#333333")
            colorMarked = Color.parseColor("#333333")
        }
    }

    private fun initFileSystem() {
        val dir = File(baseDir)
        if (!dir.exists()) dir.mkdirs()

        // 确保cunfang.txt存在
        var cunfangContent = readTxtFile("cunfang.txt").ifEmpty { "默认" }
        currentBook = cunfangContent.trim()
        writeTxtFile("cunfang.txt", currentBook)

        // 确保liebiao.json存在且有效
        var liebiaoContent = readTxtFile("liebiao.json").ifEmpty { "[]" }
        bookList = try {
            val arr = JSONArray(liebiaoContent)
            if (arr.length() == 0) {
                writeTxtFile("liebiao.json", "[\"默认\"]")
                mutableListOf("默认")
            } else {
                val list = (0 until arr.length()).map { arr.getString(it).trim() }.toMutableList()
                // 确保包含"默认"
                if (!list.contains("默认")) {
                    list.add(0, "默认")
                    writeTxtFile("liebiao.json", JSONArray(list).toString())
                }
                list
            }
        } catch (e: Exception) {
            writeTxtFile("liebiao.json", "[\"默认\"]")
            mutableListOf("默认")
        }

        // 如果当前书籍不在列表中，切换到"默认"
        if (!bookList.contains(currentBook)) {
            currentBook = "默认"
            writeTxtFile("cunfang.txt", currentBook)
        }
    }

    // 自动切换到当前阅读书籍（完全复刻手动切换）
    private fun autoSwitchToCurrentBook() {
        try {
            val readBookName = io.legado.app.model.ReadBook.book?.name
            if (readBookName.isNullOrEmpty()) return
            
            // 检查书籍是否在列表中
            val matchedBook = bookList.find { it == readBookName }
            
            if (matchedBook != null) {
                // 书籍在列表中，直接切换
                if (matchedBook != currentBook) {
                    saveCurrentBookBeforeSwitch(matchedBook)
                }
            } else {
                // 书籍不在列表中（新读取的书籍），直接切换过去
                if (readBookName != currentBook) {
                    // 直接切换，不保存到列表
                    useBook(readBookName)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadCharacters() {
        // 优先从characterRecords.json加载（当前正在使用的书籍）
        val charRecordsContent = readTxtFile("characterRecords.json")
        val cunfangContent = readTxtFile("cunfang.txt").ifEmpty { "默认" }
        val cunfangBook = cunfangContent.trim()

        if (currentBook == cunfangBook && charRecordsContent.isNotEmpty()) {
            // 当前书籍等于cunfang.txt中的书籍，使用characterRecords.json
            characterRecords = try {
                JSONArray(charRecordsContent)
            } catch (e: Exception) {
                JSONArray()
            }
        } else {
            // 其他情况从shuming.{book}.json加载
            val fileName = "shuming.$currentBook.json"
            val content = readTxtFile(fileName)
            characterRecords = try {
                if (content.isNotEmpty()) JSONArray(content) else JSONArray()
            } catch (e: Exception) {
                JSONArray()
            }
        }

        // 只有内置TTS引擎下发音人标签才与配置列表一一对应，其他引擎不做关联显示
        voiceTagRefs = CharacterVoiceHelper.getVoiceTagRefsForCurrentEngine()
        // 打开/刷新时按当前页往回推测一次，并把顺序落到内存数据上（不写盘）
        collectPinnedMainNames()
        applyPinnedToRecords()
    }

    /**
     * 打开/刷新界面时，按「当前阅读页最下面那个显示了名字的左双引号」往回推测置顶角色。
     *
     * - 起点是当前页的引号序号，不是朗读进度，所以没在朗读时同样可用；
     * - 标注里的名字可能是别名或简称，统一归一到角色记录的主名，同一主名只收一次；
     * - 当前章扫完仍凑不够才跨到上一章，最多跨这一个章节，不碰上上章；
     * - 结果只用于 [updateCharacterList] 的显示重排，绝不改动 characterRecords 或写文件。
     */
    private fun collectPinnedMainNames() {
        pinnedMainNames = emptyList()
        val book = ReadBook.book ?: return
        val chapter = ReadBook.curTextChapter ?: return
        val startSeq = currentPageLastQuoteSeq(chapter) ?: return
        val pinned = LinkedHashSet<String>()
        // 角色缓存的键是「缓存口径标题」（chapterCacheTitle），不是原始/显示标题：
        // TextChapter.title 是显示标题（已去引号），chapter.title 是原始标题，
        // 本书配了改标题的替换规则时两者都与缓存文件名不符 ⇒ 读不到缓存。
        collectBackward(
            DialogRoleManager.loadChapterCache(
                book.name,
                AiCacheFileUtil.chapterCacheTitle(book, chapter.chapter)
            ),
            startSeq, pinned
        )
        // 当前章没凑够 10 个不同主名，才跨到上一章（最多这一章）
        if (pinned.size < PINNED_MAIN_NAME_LIMIT && ReadBook.durChapterIndex > 0) {
            val prevTitle = runCatching {
                appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex - 1)
            }.getOrNull()
            if (prevTitle != null) {
                val prevCache = DialogRoleManager.loadChapterCache(
                    book.name,
                    AiCacheFileUtil.chapterCacheTitle(book, prevTitle)
                )
                val lastSeq = prevCache.keys.maxOrNull() ?: 0
                if (lastSeq > 0) collectBackward(prevCache, lastSeq, pinned)
            }
        }
        pinnedMainNames = pinned.toList()
    }

    /** 当前页上 seq 最大的标注，就是最下面那个显示了名字的左双引号 */
    private fun currentPageLastQuoteSeq(chapter: TextChapter): Int? {
        val pageIndex = ReadBook.durPageIndex
        val page = chapter.getPage(pageIndex)
        var maxSeq = 0
        page?.lines?.forEach { line ->
            synchronized(line.roleAnnotations) {
                line.roleAnnotations.forEach { anno ->
                    if (anno.seq > maxSeq) maxSeq = anno.seq
                }
            }
        }
        if (maxSeq > 0) return maxSeq
        // 标注未加载（开关关闭 / 本章还没分析）时，按排版数左引号，复现 attachAnnotations 的序号
        var seq = 0
        for (p in 0..pageIndex) {
            val pg = chapter.getPage(p) ?: continue
            pg.lines.forEach { line ->
                line.columns.forEach { column ->
                    if (column is TextColumn && column.charData == "“") seq++
                }
            }
        }
        return seq.takeIf { it > 0 }
    }

    /** 从序号往回逐个取名字 → 归一主名 → 去重，凑够上限或到本章开头即止 */
    private fun collectBackward(
        cache: Map<Int, DialogRoleManager.RoleInfo>,
        fromSeq: Int,
        out: LinkedHashSet<String>
    ) {
        var seq = fromSeq
        while (seq > 0 && out.size < PINNED_MAIN_NAME_LIMIT) {
            val name = cache[seq]?.name.orEmpty()
            if (name.isNotEmpty()) {
                resolveMainName(name)?.let { out.add(it) }
            }
            seq--
        }
    }

    /** 标注名（可能是别名/简称）→ 角色记录里的主名；匹配不到返回 null，不参与置顶 */
    private fun resolveMainName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        for (i in 0 until characterRecords.length()) {
            val obj = characterRecords.optJSONObject(i) ?: continue
            val main = obj.optString("name", "").trim()
            if (main == n) return main
        }
        for (i in 0 until characterRecords.length()) {
            val obj = characterRecords.optJSONObject(i) ?: continue
            val main = obj.optString("name", "").trim()
            if (main.isEmpty()) continue
            val aliases = obj.optString("aliases", "").split("|").map { it.trim() }
            if (aliases.any { it == n }) return main
        }
        return null
    }

    /**
     * 把推测出的置顶顺序真正落到内存里的 characterRecords 上（**不写盘**）。
     *
     * 之后界面显示、标记、合并/拆分/释放/删除，以及保存时落盘，全部以这个内存顺序为准，
     * 释放出来的新角色才会紧跟当前角色，不会被文件原顺序打乱。
     * 只有用户做了修改触发 saveCharacters() 时，这个顺序才写进三份文件。
     */
    private fun applyPinnedToRecords() {
        if (pinnedMainNames.isEmpty()) return
        val rank = pinnedMainNames.mapIndexed { index, name -> name to index }.toMap()
        val pinned = mutableListOf<Pair<Int, JSONObject>>()
        val rest = mutableListOf<JSONObject>()
        for (i in 0 until characterRecords.length()) {
            val obj = characterRecords.optJSONObject(i) ?: continue
            val main = obj.optString("name", "").trim()
            val r = rank[main]
            if (r != null) pinned.add(r to obj) else rest.add(obj)
        }
        if (pinned.isEmpty()) return
        pinned.sortBy { it.first }
        val newArray = JSONArray()
        pinned.forEach { newArray.put(it.second) }
        rest.forEach { newArray.put(it) }
        characterRecords = newArray
        // 真实索引已经变化，旧的标记/选中状态作废，避免指到别的角色
        markedIndices.clear()
        selectedIndex = -1
    }

    private fun setupRecyclerView() {
        characterAdapter = CharacterAdapter()
        rvCharacters.layoutManager = LinearLayoutManager(requireContext())
        rvCharacters.adapter = characterAdapter
        rvCharacters.setHasFixedSize(false)
        rvCharacters.isNestedScrollingEnabled = true
        
        characterAdapter.setOnItemClickListener { position ->
            val realIndex = getRealIndex(position)
            if (realIndex >= 0) handleItemClick(realIndex)
        }

        characterAdapter.setOnItemLongClickListener { position ->
            val realIndex = getRealIndex(position)
            if (realIndex >= 0) handleItemLongClick(realIndex)
            true
        }

        characterAdapter.setOnVoiceClickListener { position ->
            val realIndex = getRealIndex(position)
            if (realIndex >= 0) handleVoiceClick(realIndex)
        }

        updateCharacterList()
    }

    // 将列表中的位置映射回 characterRecords 中的真实索引
    private fun getRealIndex(position: Int): Int {
        if (position < 0 || position >= displayList.size) return -1
        return displayList[position]
    }

    private fun handleItemClick(position: Int) {
        if (markedIndices.contains(position)) {
            markedIndices.remove(position)
            if (selectedIndex == position) {
                selectedIndex = -1
            }
        } else {
            markedIndices.add(position)
            selectedIndex = position
        }
        updateCharacterList()
        updateLabel()
    }

    private fun handleVoiceClick(position: Int) {
        // 仅对当前点击的角色生效，设为选中角色（不再自动标记）
        selectedIndex = position
        updateCharacterList()
        updateLabel()
        // 直接弹出更换发音人对话框（仅对当前角色生效）
        showChangeVoiceDialog()
    }

    private fun previewCharacterVoice(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val voice = character.optString("voice", "")
        if (voice.isBlank()) {
            toast("该角色未设置发音人，无法试听")
            return
        }
        // 与发音人列表里的试听按钮共用同一实现，保证两处试听效果一致
        CharacterVoiceHelper.previewVoice(requireContext(), this, voice) { msg -> toast(msg) }
    }

    /**
     * 打开内置 TTS 配置的编辑页（即「配置列表」里点某条配置进入的同一页面），
     * 可直接改显示名字、标签、分组、插件、音色、语速音量音高等。
     * 打不开（页面不存在/未使用内置引擎）时提示，不影响角色管理本身。
     */
    private fun openVoiceConfigEditor(configId: String?) {
        if (configId.isNullOrBlank()) {
            toast("未找到该发音人对应的配置")
            return
        }
        try {
            val intent = android.content.Intent(
                requireContext(),
                io.legado.app.ui.tts.plugin.TtsConfigEditorActivity::class.java
            ).apply {
                putExtra(io.legado.app.ui.tts.plugin.TtsConfigEditorActivity.EXTRA_CONFIG_ID, configId)
                putExtra(io.legado.app.ui.tts.plugin.TtsConfigEditorActivity.EXTRA_IS_NEW, false)
            }
            startActivity(intent)
            // 编辑页可能改了标签/显示名，返回后需要重取映射并让发音人缓存失效
            pendingVoiceConfigEdit = true
        } catch (e: Exception) {
            toast("无法打开配置编辑页")
        }
    }

    // 字符串标准化（去特殊字符、trim）
    private fun normalizeString(str: String): String {
        return str.replace(Regex("[\u200B-\u200D\uFEFF]"), "").trim()
    }

    private fun handleItemLongClick(position: Int) {
        longPressedIndex = position
        showCharacterMenu(position)
    }

    private fun showCharacterMenu(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val name = character.optString("name", "")

        val options = arrayOf(
            "与角色对话",
            "角色档案",
            "修改角色",
            "删除角色",
            "设为主角",
            "固定发音人",
            "固定当前发音人",
            "修改性别年龄",
            "释放角色",
            "执行合并",
            "取消所有标记",
            "置顶角色"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("角色操作: $name")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // 无档案时后台建档并补立绘，不阻塞进入对话页
                        io.legado.app.model.AiProfileService.ensureProfileAndAvatar(
                            currentBook, name,
                            io.legado.app.model.ReadBook.durChapterIndex,
                            io.legado.app.model.ReadBook.curTextChapter?.chapter?.title.orEmpty()
                        )
                        RoleChatActivity.start(requireActivity(), currentBook, name)
                    }
                    1 -> CharacterProfileDialog.newInstance(currentBook, name)
                        .show(childFragmentManager, "characterProfileDialog")
                    2 -> showEditCharacterDialog(position)
                    3 -> deleteCharacter(position)
                    4 -> setMainCharacter(position)
                    5 -> showFixVoiceDialog(position)
                    6 -> fixCurrentVoice(position)
                    7 -> showGenderAgeDialog(position)
                    8 -> releaseCharacter(position)
                    9 -> doMerge()
                    10 -> clearAllMarks()
                    11 -> pinMarkedToTop(position)
                }
            }
            .show()
    }

    // 把标记变色的角色（或单条长按项）置顶，支持多个一起置顶
    private fun pinMarkedToTop(longPressedIndex: Int) {
        // 需要置顶的真实索引集合（按原列表顺序）
        val toPin = markedIndices.toMutableList().apply { sort() }
        if (toPin.isEmpty()) {
            // 没有任何标记时，置顶当前长按项
            toPin.add(longPressedIndex)
        }

        val moving = toPin.mapNotNull { characterRecords.optJSONObject(it) }
        val remaining = (0 until characterRecords.length())
            .filter { it !in toPin }
            .mapNotNull { characterRecords.optJSONObject(it) }

        val newArray = org.json.JSONArray()
        moving.forEach { newArray.put(it) }
        remaining.forEach { newArray.put(it) }
        characterRecords = newArray

        // 重新映射标记与选中到新索引（置顶后它们位于 [0, moving.size)）
        val wasSelectedMarked = selectedIndex in toPin
        markedIndices.clear()
        for (i in moving.indices) markedIndices.add(i)
        selectedIndex = if (wasSelectedMarked) toPin.indexOf(selectedIndex) else -1

        saveCharacters()
        updateCharacterList()
        toast(if (moving.size > 1) "已置顶 ${moving.size} 个角色" else "已置顶角色")
    }

    private fun updateCharacterList() {
        // 根据当前搜索关键词重建显示列表（displayList 存 characterRecords 的真实索引）
        val keyword = if (isSearchMode) {
            etSearchKeyword.text?.toString()?.trim().orEmpty()
        } else ""
        val keywords = keyword.split("|").map { it.trim() }.filter { it.isNotEmpty() }

        displayList.clear()
        for (i in 0 until characterRecords.length()) {
            val character = characterRecords.optJSONObject(i) ?: continue
            if (keywords.isEmpty()) {
                displayList.add(i)
            } else {
                // OR 匹配：主名或任一别名包含任一关键词（忽略大小写）即命中
                val name = character.optString("name", "").lowercase()
                val aliases = character.optString("aliases", "")
                    .split("|").map { it.trim().lowercase() }
                val hit = keywords.any { kw ->
                    name.contains(kw) || aliases.any { it.contains(kw) }
                }
                if (hit) displayList.add(i)
            }
        }
        characterAdapter.notifyDataSetChanged()
        // 只统计角色主名（每条记录一个主名，aliases 里的别名不单独计数）
        tvCharacterCount.text = "共 ${characterRecords.length()} 个角色"
    }

    private fun updateLabel() {
        val matched = displayList.size
        val total = characterRecords.length()
        tvCharacterLabel.text = if (isSearchMode) {
            "角色列表 (匹配 $matched / 共 $total，已标记 ${markedIndices.size}):"
        } else {
            "角色列表 (已标记 ${markedIndices.size}):"
        }
    }

    // 进入/退出搜索模式：Spinner 与 搜索输入框 互斥显示
    private fun toggleSearchMode() {
        isSearchMode = !isSearchMode
        if (isSearchMode) {
            spinnerBook.visibility = View.GONE
            etSearchKeyword.visibility = View.VISIBLE
            etSearchKeyword.setText("")
            etSearchKeyword.requestFocus()
            showKeyboard(etSearchKeyword)
        } else {
            etSearchKeyword.visibility = View.GONE
            spinnerBook.visibility = View.VISIBLE
            hideKeyboard()
        }
        updateCharacterList()
        updateLabel()
    }

    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearchKeyword.windowToken, 0)
    }

    private fun setupListeners() {
        // 密钥管理
        btnSaveKey.setOnClickListener { saveKey() }
        btnRestoreKey.setOnClickListener { restoreKey() }

        // 角色操作
        btnAddCharacter.setOnClickListener { showAddCharacterDialog() }
        btnMerge.setOnClickListener { doMerge() }
        btnChangeVoice.setOnClickListener { showChangeVoiceDialog() }
        btnRelease.setOnClickListener { doRelease() }
        btnDeleteCharacter.setOnClickListener { doDelete() }

        // 书籍操作
        btnCreateBook.setOnClickListener { createNewBook() }
        btnBackupRestore.setOnClickListener { backupRestore() }
        btnManageBooks.setOnClickListener { manageBooks() }
        btnRefresh.setOnClickListener { refresh() }

        // 书籍选择
        setupBookSpinner()
    }

    private fun setupBookSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            bookList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBook.adapter = adapter
        
        val selectedIdx = bookList.indexOf(currentBook)
        if (selectedIdx >= 0) {
            spinnerBook.setSelection(selectedIdx)
        }

        spinnerBook.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (!spinnerInitialized) {
                    // 第一次初始化时不触发切换
                    spinnerInitialized = true
                    return
                }
                val selectedBook = bookList[pos]
                val currentBookName = readTxtFile("cunfang.txt").ifEmpty { "默认" }.trim()
                if (selectedBook != currentBookName) {
                    // 切换书籍
                    saveCurrentBookBeforeSwitch(selectedBook)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // 1. 切换书籍前保存当前书籍数据（复刻JS版本）
    private fun saveCurrentBookBeforeSwitch(newBookName: String) {
        try {
            // 从 characterRecords.json 读取当前角色数据，或使用内存中的数据
            var characterData = readTxtFile("characterRecords.json")
            if (characterData.isEmpty()) {
                characterData = characterRecords.toString()
            }
            val currentBookName = readTxtFile("cunfang.txt").ifEmpty { "默认" }.trim()
            if (currentBookName.isNotEmpty()) {
                val shumingFileName = "shuming.$currentBookName.json"
                writeTxtFile(shumingFileName, characterData)
                createGengxinFile()
            }
            useBook(newBookName)
        } catch (e: Exception) {
            e.printStackTrace()
            useBook(newBookName)
        }
    }

    // 2. 加载目标书籍数据（复刻JS版本）
    private fun useBook(bookName: String) {
        try {
            writeTxtFile("cunfang.txt", bookName)
            val shumingFileName = "shuming.$bookName.json"
            var bookData = readTxtFile(shumingFileName)
            if (bookData.isEmpty()) {
                bookData = "[]"
            }
            val parsedData = try {
                JSONArray(bookData)
            } catch (e: Exception) {
                JSONArray()
            }
            characterRecords = parsedData

            
            // 切换书籍后刷新UI
            currentBook = bookName
            markedIndices.clear()
            selectedIndex = -1
            saveCharacters()
            updateCharacterList()
            updateLabel()
            updateSpinner()
            toast("已切换到书籍: $bookName")
        } catch (e: Exception) {
            e.printStackTrace()
            characterRecords = JSONArray()
            writeTxtFile("characterRecords.json", "[]")
            updateCharacterList()
            toast("切换失败，已重置角色数据")
        }
    }

    // 创建 gengxin.json（复刻JS版本的createGengxinFile逻辑）
    private fun createGengxinFile() {
        try {
            val saveRecords = JSONArray()
            for (i in 0 until characterRecords.length()) {
                val char = characterRecords.optJSONObject(i) ?: continue
                val record = JSONObject().apply {
                    put("name", char.optString("name", ""))
                    put("aliases", char.optString("aliases", ""))
                    put("voice", char.optString("voice", ""))  // 暂不做映射
                    put("gender", char.optString("gender", ""))
                    put("age", char.optString("age", ""))
                    put("usageCount", char.optInt("usageCount", 100))
                    // 保留其他字段
                    if (char.has("fixedVoice")) put("fixedVoice", char.optBoolean("fixedVoice", false))
                    if (char.has("fixedGenderAge")) put("fixedGenderAge", char.optBoolean("fixedGenderAge", false))
                    if (char.has("genderAgeHistory")) put("genderAgeHistory", char.opt("genderAgeHistory"))
                }
                saveRecords.put(record)
            }
            writeGengxinEnvelope(saveRecords)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 写邮筒 `gengxin.json`——**统一走信封格式**（串书根治第三步，11-20）。
     *
     * 与 [io.legado.app.ui.book.read.config.CharacterVoiceHelper] 里的同名函数同格式同口径：
     * UTTS 侧早已信封化（`{"book":…,"records":[…]}`），消费端规则 v1.7.53+ 验章；
     * U阅 侧此前写裸数组，一写就把信封盖掉，消费端只能降级走语料核验，「抓写方」能力失效。
     *
     * 章取**写这一刻**的 `cunfang.txt`。本处调用方（saveCurrentBookBeforeSwitch / saveCharacters）
     * 都在「主表归属 == cunfang」前提下写，故章==当前书。
     */
    private fun writeGengxinEnvelope(records: JSONArray) {
        try {
            val book = readTxtFile("cunfang.txt").trim()
            val envelope = JSONObject().apply {
                put("book", book)
                put("records", records)
            }
            writeTxtFile("gengxin.json", envelope.toString())
            AppLog.put("gengxin 写入(book=$book, n=${records.length()}) 信封盖章")
        } catch (e: Exception) {
            AppLog.put("gengxin 信封写入失败: ${e.localizedMessage}", e)
        }
    }

    // ==================== 密钥管理 ====================
    // 保存密钥 / 恢复密钥的完整逻辑已抽到 [MiyueKeyManager]，
    // 角色管理页与朗读脚本页共用同一份实现，避免两处维护不一致。

    private fun saveKey() {
        MiyueKeyManager.saveKey(requireContext())
    }

    private fun restoreKey() {
        MiyueKeyManager.restoreKey(requireContext())
    }

    // ==================== 角色管理 ====================

    private fun showAddCharacterDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "输入角色名称"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("新增角色")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    addCharacter(name)
                } else {
                    toast("名称不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addCharacter(name: String) {
        val character = JSONObject().apply {
            put("name", name)
            put("aliases", name) // 别名字符串格式
            put("gender", "")
            put("age", "")
            put("voice", "")
            put("usageCount", 100) // 新角色默认100
        }
        characterRecords.put(character)
        saveCharacters()
        updateCharacterList()
        toast("已添加角色: $name")
    }

    private fun showEditCharacterDialog(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val currentName = character.optString("name", "")
        val currentAliases = character.optString("aliases", "").ifEmpty { currentName }

        // 创建包含两个输入框的布局
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        // 主名输入框
        val nameLabel = TextView(requireContext()).apply {
            text = "主名"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val nameInput = TextInputEditText(requireContext()).apply {
            hint = "输入角色主名"
            setText(currentName)
            setPadding(0, 0, 0, 20)
        }

        // 别名输入框
        val aliasesLabel = TextView(requireContext()).apply {
            text = "别名（多个用|分隔）"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val aliasesInput = TextInputEditText(requireContext()).apply {
            hint = "输入角色别名，多个别名用|分隔"
            setText(currentAliases)
        }

        container.addView(nameLabel)
        container.addView(nameInput)
        container.addView(aliasesLabel)
        container.addView(aliasesInput)

        AlertDialog.Builder(requireContext())
            .setTitle("修改角色")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val newName = nameInput.text?.toString()?.trim() ?: ""
                val newAliases = aliasesInput.text?.toString()?.trim() ?: ""
                if (newName.isNotEmpty()) {
                    character.put("name", newName)
                    character.put("aliases", newAliases)
                    saveCharacters()
                    updateCharacterList()
                    toast("已修改角色: $newName")
                } else {
                    toast("主名不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCharacter(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val name = character.optString("name", "")

        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除角色 '$name' 吗？")
            .setPositiveButton("删除") { _, _ ->
                characterRecords.remove(position)
                // 调整标记索引
                val newMarkedIndices = mutableListOf<Int>()
                markedIndices.forEach { idx ->
                    when {
                        idx < position -> newMarkedIndices.add(idx)
                        idx > position -> newMarkedIndices.add(idx - 1)
                    }
                }
                markedIndices.clear()
                markedIndices.addAll(newMarkedIndices)
                if (selectedIndex == position) {
                    selectedIndex = -1
                } else if (selectedIndex > position) {
                    selectedIndex--
                }
                saveCharacters()
                updateCharacterList()
                updateLabel()
                toast("已删除角色: $name")
                // 同步联动：删除 AI 角色档案（并记入删除名单，朗读不再自动重建）
                io.legado.app.model.CharacterProfileManager.delete(currentBook, name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setMainCharacter(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        character.put("age", "主角")
        character.put("usageCount", 100)
        saveCharacters()
        updateCharacterList()
        toast("已设为主角")
    }

    private fun showFixVoiceDialog(position: Int) {
        longPressedIndex = position
        showVoiceCategoryDialog { category ->
            showVoiceListDialog(category) { voice ->
                fixVoice(position, voice)
            }
        }
    }

    // 以下三个发音人弹窗统一委托给 CharacterVoiceHelper 实现。
    // 此处曾各有一份完全相同的副本，试听按钮需要在列表每行挂点击事件，
    // 若两份实现不同步会出现「角色管理里能试听、编辑角色里不能」的不一致，
    // 故收敛为单一实现，本 DialogFragment 作为 LifecycleOwner 传入。

    private fun showVoiceCategoryDialog(onCategorySelected: (String) -> Unit) {
        CharacterVoiceHelper.showVoiceCategoryDialog(requireContext(), this, onCategorySelected)
    }

    private fun showVoiceSearchDialog(onVoiceSelected: (String) -> Unit) {
        CharacterVoiceHelper.showVoiceSearchDialog(requireContext(), this, onVoiceSelected)
    }

    // 发音人列表选择弹窗（根据分类筛选，每行右侧带试听按钮）
    private fun showVoiceListDialog(category: String, onVoiceSelected: (String) -> Unit) {
        CharacterVoiceHelper.showVoiceListDialog(requireContext(), this, category, onVoiceSelected)
    }

    private fun fixVoice(position: Int, voice: String) {
        val character = characterRecords.optJSONObject(position) ?: return
        character.put("voice", voice)
        character.put("fixedVoice", true)
        saveCharacters()
        updateCharacterList()
        toast("已固定发音人: $voice")
    }

    private fun fixCurrentVoice(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val currentVoice = character.optString("voice", "auto")
        character.put("fixedVoice", true)
        saveCharacters()
        updateCharacterList()
        toast("已固定当前发音人: $currentVoice")
    }

    /**
     * 修改性别年龄：弹出选择框，选中后写入角色的 gender / age 并标记为已固定。
     * 选项与「编辑角色」对话框的年龄段保持一致，便于两处取值互认。
     */
    private fun showGenderAgeDialog(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val name = character.optString("name", "")
        val options = listOf(
            "男童", "少年", "男青年", "男中年", "男老年",
            "女童", "少女", "女青年", "女中年", "女老年",
            "男主", "女主", "主角", "特殊", "旁白", "系统"
        )
        val current = character.optString("age", "").ifBlank {
            character.optString("gender", "")
        }
        val checkedIndex = if (current.isBlank()) -1 else options.indexOf(current)

        AlertDialog.Builder(requireContext())
            .setTitle("修改性别年龄: $name")
            .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, which ->
                applyGenderAge(position, options[which])
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 写入性别年龄：选项字符串 -> gender / age 两个字段 */
    private fun applyGenderAge(position: Int, value: String) {
        val character = characterRecords.optJSONObject(position) ?: return
        val gender = when {
            value.startsWith("女") -> "女"
            value.startsWith("男") || value == "少年" -> "男"
            value == "特殊" || value == "旁白" || value == "系统" -> value
            else -> ""
        }
        // 男主 / 女主 的年龄段统一记为主角
        val age = if (value == "男主" || value == "女主") "主角" else value
        character.put("gender", gender)
        character.put("age", age)
        // 主角权重最高，其余按常规权重，与「设为主角」逻辑保持一致
        character.put("usageCount", if (age == "主角") 100 else 50)
        character.put("fixedGenderAge", true)
        saveCharacters()
        updateCharacterList()
        toast("已修改性别年龄: $value")
    }

    private fun releaseCharacter(position: Int) {
        val character = characterRecords.optJSONObject(position) ?: return
        val charName = character.optString("name", "")
        val normalizedName = normalizeString(charName)
        val aliasesStr = character.optString("aliases", "")

        if (aliasesStr.isEmpty() || aliasesStr.trim() == "") {
            toast("该角色没有别名，无需释放")
            return
        }

        val aliases = aliasesStr.split("|")
        val newCharacters = mutableListOf<JSONObject>()

        aliases.forEach { alias ->
            val normalizedAlias = normalizeString(alias.trim())
            if (normalizedAlias == normalizedName) {
                return@forEach
            }

            // 检查是否已存在同名角色
            var exists = false
            for (i in 0 until characterRecords.length()) {
                val recordName = normalizeString(characterRecords.optJSONObject(i)?.optString("name", "") ?: "")
                if (recordName == normalizedAlias) {
                    exists = true
                    break
                }
            }

            if (!exists) {
                val newRecord = JSONObject().apply {
                    put("name", alias.trim())
                    put("aliases", alias.trim())
                    put("voice", "")
                    put("gender", "")
                    put("age", "")
                    put("usageCount", 100)
                }
                newCharacters.add(newRecord)
            }
        }

        if (newCharacters.isEmpty()) {
            toast("所有别名角色已存在，无需释放")
            return
        }

        // 原角色仅保留自身名称作为别名
        character.put("aliases", charName)

        // 使用新数组正确插入
        val newRecords = JSONArray()
        for (i in 0 until characterRecords.length()) {
            if (i == position) {
                // 添加原角色
                newRecords.put(character)
                // 添加释放出来的新角色
                newCharacters.forEach { newRecords.put(it) }
            } else {
                characterRecords.optJSONObject(i)?.let { newRecords.put(it) }
            }
        }
        characterRecords = newRecords

        markedIndices.clear()
        selectedIndex = -1
        saveCharacters()
        updateCharacterList()
        updateLabel()
        toast("角色释放成功")
    }

    private fun clearAllMarks() {
        markedIndices.clear()
        selectedIndex = -1
        updateCharacterList()
        updateLabel()
        toast("已取消所有标记")
    }

    // ==================== 批量操作 ====================

    private fun doMerge() {
        if (markedIndices.isEmpty()) {
            toast("请先标记角色（单击）")
            return
        }

        // 使用最后标记的角色作为主角色
        val mainIndex = markedIndices.last()
        if (markedIndices.size == 1) {
            toast("至少需要标记两个角色才能合并")
            return
        }
        performMerge(mainIndex)
    }

    private fun performMerge(mainIndex: Int) {
        val mainChar = characterRecords.optJSONObject(mainIndex) ?: return
        val mainName = mainChar.optString("name", "")

        // 收集待合并的角色（排除主角色）
        val mergeIndices = markedIndices.filter { it != mainIndex }
        if (mergeIndices.isEmpty()) {
            toast("请标记至少一个要合并的角色")
            return
        }

        // 收集所有别名
        val allAliasesSet = mutableSetOf<String>()

        // 收集主角色现有的别名（字符串格式，用|分割）
        val mainAliasesStr = mainChar.optString("aliases", "")
        if (mainAliasesStr.isNotEmpty()) {
            mainAliasesStr.split("|").forEach { allAliasesSet.add(it.trim()) }
        } else {
            allAliasesSet.add(mainName)
        }

        // 收集待合并角色的别名（在移除前完成，顺带记下被合并方角色名供档案合并联动）
        val mergedNames = mutableListOf<String>()
        mergeIndices.sortedDescending().forEach { pos ->
            val char = characterRecords.optJSONObject(pos) ?: return@forEach
            char.optString("name", "").takeIf { it.isNotEmpty() }?.let { mergedNames.add(it) }
            val aliasesStr = char.optString("aliases", "")
            if (aliasesStr.isNotEmpty()) {
                aliasesStr.split("|").forEach { allAliasesSet.add(it.trim()) }
            } else {
                allAliasesSet.add(char.optString("name", ""))
            }
        }

        // 移除待合并的角色（倒序删除避免索引混乱）
        mergeIndices.sortedDescending().forEach { pos ->
            characterRecords.remove(pos)
        }

        // 更新主角色
        mainChar.put("name", mainName)
        mainChar.put("aliases", allAliasesSet.joinToString("|"))

        markedIndices.clear()
        selectedIndex = -1
        saveCharacters()
        updateCharacterList()
        updateLabel()
        toast("已合并角色: $mainName")
        // 同步联动：AI 角色档案合并（被合并方档案并入主名，后台由 AI 重新整理）
        io.legado.app.model.CharacterProfileManager.mergeProfiles(
            currentBook, mainName, mergedNames, null
        )
    }

    private fun showChangeVoiceDialog() {
        if (selectedIndex < 0) {
            toast("请先选中一个角色")
            return
        }

        showVoiceCategoryDialog { category ->
            showVoiceListDialog(category) { voice ->
                changeVoiceForSelected(voice)
            }
        }
    }

    private fun changeVoiceForSelected(voice: String) {
        val pos = selectedIndex
        if (pos < 0) return
        characterRecords.optJSONObject(pos)?.put("voice", voice)
        saveCharacters()
        updateCharacterList()
        toast("已更换发音人: $voice")
    }

    private fun doRelease() {
        if (markedIndices.isEmpty()) {
            toast("请先标记角色")
            return
        }

        val releaseList = markedIndices.sortedDescending()
        val newRecords = JSONArray()
        var originalIndex = 0

        while (originalIndex < characterRecords.length()) {
            // 检查当前索引是否是待释放的角色
            if (releaseList.contains(originalIndex)) {
                val character = characterRecords.optJSONObject(originalIndex)
                if (character != null) {
                    val charName = character.optString("name", "")
                    val normalizedName = normalizeString(charName)
                    val aliasesStr = character.optString("aliases", "")

                    // 先添加原角色
                    newRecords.put(character)

                    // 如果有别名，释放为新角色
                    if (aliasesStr.isNotEmpty() && aliasesStr.trim() != "") {
                        val aliases = aliasesStr.split("|")
                        aliases.forEach { alias ->
                            val normalizedAlias = normalizeString(alias.trim())
                            if (normalizedAlias != normalizedName) {
                                // 检查是否已存在同名角色
                                var exists = false
                                for (i in 0 until characterRecords.length()) {
                                    val recordName = normalizeString(characterRecords.optJSONObject(i)?.optString("name", "") ?: "")
                                    if (recordName == normalizedAlias) {
                                        exists = true
                                        break
                                    }
                                }

                                if (!exists) {
                                    val newRecord = JSONObject().apply {
                                        put("name", alias.trim())
                                        put("aliases", alias.trim())
                                        put("voice", "")
                                        put("gender", "")
                                        put("age", "")
                                        put("usageCount", 100)
                                    }
                                    newRecords.put(newRecord)
                                }
                            }
                        }

                        // 原角色仅保留自身名称作为别名
                        character.put("aliases", charName)
                    }
                }
            } else {
                // 非释放角色，直接复制
                characterRecords.optJSONObject(originalIndex)?.let {
                    newRecords.put(it)
                }
            }
            originalIndex++
        }

        characterRecords = newRecords
        markedIndices.clear()
        selectedIndex = -1
        saveCharacters()
        updateCharacterList()
        updateLabel()
        toast("角色释放成功")
    }

    private fun doDelete() {
        if (markedIndices.isEmpty()) {
            toast("请先标记角色")
            return
        }

        val count = markedIndices.size
        AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除 $count 个角色吗？")
            .setPositiveButton("删除") { _, _ ->
                markedIndices.sortedDescending().forEach { pos ->
                    characterRecords.remove(pos)
                }
                markedIndices.clear()
                selectedIndex = -1
                saveCharacters()
                updateCharacterList()
                updateLabel()
                toast("已删除 $count 个角色")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 书籍管理 ====================

    private fun createNewBook() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "输入新书名称"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("创建新书")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text?.toString()?.trim() ?: ""
                if (name.isNotEmpty()) {
                    if (!bookList.contains(name)) {
                        bookList.add(name)
                        saveBookList()
                        currentBook = name
                        writeTxtFile("cunfang.txt", currentBook)
                        characterRecords = JSONArray()
                        saveCharacters()
                        updateSpinner()
                        markedIndices.clear()
                        selectedIndex = -1
                        updateCharacterList()
                        updateLabel()
                        toast("已创建新书: $name")
                    } else {
                        toast("书籍已存在")
                    }
                } else {
                    toast("名称不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manageBooks() {
        val options = arrayOf("删除书籍")
        
        AlertDialog.Builder(requireContext())
            .setTitle("管理书籍")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> deleteBook()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteBook() {
        if (bookList.size <= 1) {
            toast("至少保留一本书")
            return
        }

        val bookNames = bookList.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("选择要删除的书籍")
            .setItems(bookNames) { _, which ->
                val bookToDelete = bookList[which]
                AlertDialog.Builder(requireContext())
                    .setTitle("确认删除")
                    .setMessage(
                        "确定要删除书籍 '$bookToDelete' 吗？\n\n" +
                            "这会同时删除这本书关联的所有 AI 数据，且不可恢复：\n" +
                            "• 所有角色与角色档案\n" +
                            "• AI 生成的插图和头像\n" +
                            "• 识别的角色缓存（xiaoshuo 目录）\n" +
                            "• 角色对话记录与 AI 生图绑定设置"
                    )
                    .setPositiveButton("删除") { _, _ ->
                        // 先清理这本书的 AI 关联数据（文件/数据库/设置），再删书籍本身
                        deleteBookAiData(bookToDelete)
                        if (bookToDelete == currentBook) {
                            currentBook = bookList.filter { it != bookToDelete }.first()
                            writeTxtFile("cunfang.txt", currentBook)
                            loadCharacters()
                        }
                        bookList.remove(bookToDelete)
                        saveBookList()
                        deleteFile("shuming.$bookToDelete.json")
                        updateSpinner()
                        markedIndices.clear()
                        selectedIndex = -1
                        updateCharacterList()
                        updateLabel()
                        toast("已删除书籍及其 AI 数据: $bookToDelete")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 删除某本书关联的所有 AI 生成数据，且只删与这本书有关的：
     * - AI 生图持久目录：/Download/AI生图/{书名}
     * - AI 头像目录：/Download/AI头像/{书名}（含各角色子目录与历史）
     * - 识别的角色缓存：/Download/chajian/xiaoshuo/{书名} 的各种目录写法
     * - 角色对话记录：EngineDataDir/rolechat/{书名}_*.json
     * - 角色档案数据库：CharacterProfileManager.clearBook(书名)
     * - AI 生图按书绑定设置：SharedPreferences 中按书名的 key（及旧 bookUrl key 兜底）
     *
     * 全部在 IO 线程执行，避免阻塞 UI；单项失败不影响其它项。
     */
    private fun deleteBookAiData(bookName: String) {
        val prefs = requireContext().defaultSharedPreferences
        // 用 Fragment 的 lifecycleScope，不用 viewLifecycleOwner：
        // 删除确认弹窗点击时本 DialogFragment 的 View 可能已销毁（getView()==null），
        // 访问 viewLifecycleOwner 会抛 IllegalStateException
        lifecycleScope.launch(Dispatchers.IO) {
            // 1) AI 生图持久目录：/Download/AI生图/{书名}
            runCatching {
                val sanitized = bookName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim().ifEmpty { "unknown" }
                File("/storage/emulated/0/Download/AI生图", sanitized)
                    .takeIf { it.exists() }?.deleteRecursively()
            }
            // 2) AI 头像目录：/Download/AI头像/{书名}
            runCatching {
                val avatarName = bookName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(40)
                File("/storage/emulated/0/Download/AI头像", avatarName)
                    .takeIf { it.exists() }?.deleteRecursively()
            }
            // 3) 识别的角色缓存：xiaoshuo 下书名的几种目录写法（与脚本候选规则一致）
            runCatching {
                val xiaoRoot = "/storage/emulated/0/Download/chajian/xiaoshuo/"
                listOf(
                    bookName,
                    bookName.trim(),
                    bookName.replace(Regex("[\\\\/:*?\"<>|]"), "_"),
                    bookName.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
                ).filter { it.isNotBlank() && it != "." && it != ".." }
                    .map { File(xiaoRoot + it) }
                    .forEach { it.takeIf { d -> d.exists() }?.deleteRecursively() }
                // 11-28：删完目录不等于标注就没了——内存缓存与已画的 roleAnnotations
                // 还在（同「清空 AI 缓存后正文标注不消失」那个 bug 的同一根因）。
                io.legado.app.ui.book.read.page.DialogRoleManager
                    .notifyCacheCleared(bookName)
            }
            // 4) 角色对话记录：rolechat/{书名}_*.json
            runCatching {
                val roleChatDir = File(io.legado.app.help.EngineDataDir.baseDir(), "rolechat")
                roleChatDir.listFiles { f -> f.isFile && f.name.startsWith("${bookName}_") && f.name.endsWith(".json") }
                    ?.forEach { it.delete() }
            }
            // 5) 角色档案数据库（含删除名单，防止朗读时自动重建）
            runCatching { CharacterProfileManager.clearBook(bookName) }
            // 6) AI 生图按书绑定设置：清书名 key；旧 bookUrl key 无法从书名反推，
            //    这里只清当前正在读的这本书对应的 bookUrl（读到的是当前书时）
            runCatching {
                val editor = prefs.edit()
                val namePrefixes = listOf(
                    PreferKey.aiImageEnableNamePrefix,
                    PreferKey.aiImageTemplateBindNamePrefix,
                    PreferKey.aiImageSaveNamePrefix
                )
                prefs.all.keys.filter { key -> namePrefixes.any { key == it + bookName } }
                    .forEach { editor.remove(it) }
                val currentUrl = ReadBook.book?.takeIf { it.name == bookName }?.bookUrl
                if (!currentUrl.isNullOrBlank()) {
                    val urlPrefixes = listOf(
                        PreferKey.aiImageEnablePrefix,
                        PreferKey.aiImageTemplateBindPrefix,
                        PreferKey.aiImageSavePrefix
                    )
                    prefs.all.keys.filter { key -> urlPrefixes.any { key == it + currentUrl } }
                        .forEach { editor.remove(it) }
                }
                editor.apply()
            }
            // 不做全局缓存清理：AI 生图的目录已删，读取时目录不存在自然不会命中，
            // 清全局缓存会影响其它书。
        }
    }

    private fun updateSpinner() {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            bookList
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerBook.adapter = adapter
        val selectedIdx = bookList.indexOf(currentBook)
        if (selectedIdx >= 0) {
            spinnerBook.setSelection(selectedIdx)
        }
    }

    // ==================== 备份恢复 ====================

    private fun backupRestore() {
        val options = arrayOf("备份到剪贴板", "从文本恢复", "密钥恢复")

        AlertDialog.Builder(requireContext())
            .setTitle("备份恢复")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> backupToClipboard()
                    1 -> restoreFromInput()
                    2 -> restoreKeysFromJson()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 从JSON恢复密钥
    private fun restoreKeysFromJson() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "粘贴包含密钥的JSON数据"
            minLines = 5
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("密钥恢复")
            .setView(input)
            .setPositiveButton("恢复") { _, _ ->
                val json = input.text?.toString()?.trim() ?: ""
                if (json.isNotEmpty()) {
                    try {
                        val importedCount = parseAndSaveKeys(json)
                        toast("已恢复 $importedCount 个密钥")
                    } catch (e: Exception) {
                        toast("JSON格式错误: ${e.message}")
                    }
                } else {
                    toast("请输入JSON数据")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // 解析JSON并保存密钥
    private fun parseAndSaveKeys(json: String): Int {
        // 解析外层JSON数组
        val outerArray = JSONArray(json)
        val keyMap = MiyueKeyManager.getKeyMap() // 保留现有密钥
        var importCount = 0

        for (i in 0 until outerArray.length()) {
            val item = outerArray.optJSONObject(i) ?: continue
            val config = item.optJSONObject("config") ?: continue
            val source = config.optJSONObject("source") ?: continue
            val data = source.optJSONObject("data") ?: continue
            val keyListJson = data.optString("keyListJson", "")

            if (keyListJson.isEmpty()) continue

            // 解析内层JSON字符串
            val innerObj = JSONObject(keyListJson)

            // 使用for循环遍历keys
            for (keyName in innerObj.keys()) {
                val keyObj = innerObj.optJSONObject(keyName) ?: continue
                val keyValue = keyObj.optString("value", "")

                if (keyValue.isNotEmpty()) {
                    // URL解码
                    val decodedValue = try {
                        java.net.URLDecoder.decode(keyValue, "UTF-8")
                    } catch (e: Exception) {
                        keyValue
                    }
                    keyMap.put(keyName, decodedValue)
                    importCount++
                }
            }
        }

        MiyueKeyManager.saveKeyMap(keyMap)
        return importCount
    }

    private fun backupToClipboard() {
        val json = characterRecords.toString()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("角色备份", json))
        toast("已复制到剪贴板")
    }

    private fun restoreFromInput() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "粘贴JSON数据"
            minLines = 5
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("从文本恢复")
            .setView(input)
            .setPositiveButton("恢复") { _, _ ->
                val json = input.text?.toString()?.trim() ?: ""
                if (json.isNotEmpty()) {
                    try {
                        characterRecords = JSONArray(json)
                        saveCharacters()
                        markedIndices.clear()
                        selectedIndex = -1
                        updateCharacterList()
                        updateLabel()
                        toast("已恢复角色数据")
                    } catch (e: Exception) {
                        toast("JSON格式错误")
                    }
                } else {
                    toast("请输入JSON数据")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 辅助方法 ====================

    /**
     * 刷新 = 与进入界面完全一致：全部角色重新从文件读取，再按当前阅读页重新定位置顶。
     * 后台脚本实时写入的新角色、新书籍列表都在这里生效；推测置顶本身不写盘。
     */
    private fun refresh() {
        // 1) 重读 cunfang.txt / liebiao.json，重置当前书与书籍列表
        initFileSystem()
        // 2) 从文件读全部角色，并按当前页推测一次（只改内存，不落盘）
        loadCharacters()
        // 3) 切回当前正在阅读的书（与 onStart 一致；useBook 内部会写盘并重建列表）
        autoSwitchToCurrentBook()
        // 切书后数据已换，按当前页重新推测一次，保证显示与后续修改/保存都按这个顺序
        collectPinnedMainNames()
        applyPinnedToRecords()
        markedIndices.clear()
        selectedIndex = -1
        updateSpinner()
        updateCharacterList()
        updateLabel()
        toast("已重新读取文件并按当前页定位")
    }

    private fun saveCharacters() {
        // 同时保存到三个文件
        val jsonData = characterRecords.toString()
        writeTxtFile("characterRecords.json", jsonData)
        val fileName = "shuming.$currentBook.json"
        writeTxtFile(fileName, jsonData)
        createGengxinFile()
    }

    private fun saveBookList() {
        val arr = JSONArray()
        bookList.forEach { arr.put(it) }
        writeTxtFile("liebiao.json", arr.toString())
    }

    // ==================== 文件操作 ====================

    private val baseDir: String
        get() = io.legado.app.help.EngineDataDir.baseDir()

    private fun readTxtFile(fileName: String): String {
        return try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTxtFile(fileName: String, content: String) {
        try {
            val file = File(baseDir, fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteFile(fileName: String) {
        try {
            val file = File(baseDir, fileName)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    // ==================== RecyclerView 适配器 ====================

    inner class CharacterAdapter : RecyclerView.Adapter<CharacterAdapter.ViewHolder>() {

        private var onItemClick: ((Int) -> Unit)? = null
        private var onItemLongClick: ((Int) -> Unit)? = null
        private var onVoiceClick: ((Int) -> Unit)? = null

        fun setOnItemClickListener(listener: (Int) -> Unit) {
            onItemClick = listener
        }

        fun setOnItemLongClickListener(listener: (Int) -> Unit) {
            onItemLongClick = listener
        }

        fun setOnVoiceClickListener(listener: (Int) -> Unit) {
            onVoiceClick = listener
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_character, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            // position 是列表位置；只把列表位置传给 bind，真实索引统一在 bind 内换算
            val realIndex = if (position in displayList.indices) displayList[position] else -1
            val character = if (realIndex >= 0) characterRecords.optJSONObject(realIndex) else null
            holder.bind(character, position)
        }

        // 供外部根据列表位置取真实索引
        fun getRealIndex(position: Int): Int {
            return if (position in displayList.indices) displayList[position] else -1
        }

        override fun getItemCount(): Int = displayList.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById(R.id.tv_character_name)
            private val tvAliases: TextView = itemView.findViewById(R.id.tv_character_aliases)
            private val llVoice: LinearLayout = itemView.findViewById(R.id.ll_voice_info)
            private val tvVoice: TextView = itemView.findViewById(R.id.tv_voice_info)
            private val tvVoiceName: TextView = itemView.findViewById(R.id.tv_voice_name)
            private val ivFixed: ImageView = itemView.findViewById(R.id.iv_fixed_mark)

            fun bind(character: JSONObject?, position: Int) {
                if (character == null) return
                // position 参数为列表位置；换算成真实索引用于高亮判断
                val realIndex = if (position in displayList.indices) displayList[position] else -1

                val name = character.optString("name", "")
                val aliasesStr = character.optString("aliases", "")
                val voice = character.optString("voice", "")
                val usageCount = character.optInt("usageCount", 100)
                val fixedVoice = character.optBoolean("fixedVoice", false)
                val age = character.optString("age", "")

                // 主角皇冠放在主名字右边
                var displayName = name
                if (age == "主角") {
                    displayName += "👑"
                }
                // usageCount==50 时名字加【】（与JS保持一致）
                if (usageCount == 50) {
                    displayName = "【$displayName】"
                }
                tvName.text = displayName

                // 别名：格式为 (别名1|别名2|...)，排除主名字
                val aliasesList = aliasesStr.split("|").filter { it.trim() != name }.map { it.trim() }
                if (aliasesList.isNotEmpty()) {
                    tvAliases.visibility = View.VISIBLE
                    tvAliases.text = "(${aliasesList.joinToString("|")})"
                } else {
                    tvAliases.visibility = View.GONE
                }

                // 发音人：格式为【发音人名称】（usageCount==100 时发音人加【】）
                val voiceDisplay = if (usageCount == 100 && voice.isNotEmpty()) "【$voice】" else if (voice.isNotEmpty()) "【$voice】" else ""
                tvVoice.text = voiceDisplay
                // 标签下方补一行：该配置的「显示名称」（编辑页「显示名称」输入框里的值）
                val tagRef = voiceTagRefs[voice.trim()]
                val voiceName = tagRef?.displayName.orEmpty()
                if (voiceName.isNotEmpty()) {
                    tvVoiceName.visibility = View.VISIBLE
                    tvVoiceName.text = voiceName
                    // 点配置名直接进该配置的编辑页（改显示名/标签/插件/音色等）
                    tvVoiceName.setOnClickListener { openVoiceConfigEditor(tagRef?.configId) }
                } else {
                    tvVoiceName.visibility = View.GONE
                    tvVoiceName.text = ""
                    tvVoiceName.setOnClickListener(null)
                }

                // 固定标记 - 隐藏（不再显示✓）
                ivFixed.visibility = View.GONE

                // 背景颜色（适配深色模式）—— marked/selected 均以真实索引为准
                val isMarked = markedIndices.contains(realIndex)
                val isSelected = realIndex == selectedIndex
                val isDarkMode = (resources.configuration.uiMode and
                        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES

                val bgMark = if (isDarkMode) bgMarkedDark else bgMarked
                val bgSel = if (isDarkMode) bgOnlySelectedDark else bgOnlySelected
                val bgNormal = if (isDarkMode) bgNormalDark else Color.TRANSPARENT

                when {
                    isMarked && isSelected -> {
                        itemView.setBackgroundColor(bgMark)
                        tvName.setTextColor(colorSelected)
                    }
                    isMarked -> {
                        itemView.setBackgroundColor(bgMark)
                        tvName.setTextColor(colorMarked)
                    }
                    isSelected -> {
                        itemView.setBackgroundColor(bgSel)
                        tvName.setTextColor(colorOnlySelected)
                    }
                    else -> {
                        itemView.setBackgroundColor(bgNormal)
                        tvName.setTextColor(colorDefault)
                    }
                }

                // 点击回调传列表位置，真实索引由 setupRecyclerView 的 getRealIndex 统一换算
                itemView.setOnClickListener { onItemClick?.invoke(position) }
                itemView.setOnLongClickListener { onItemLongClick?.invoke(position); true }
                llVoice.setOnClickListener { onVoiceClick?.invoke(position) }
                tvName.setOnClickListener { previewCharacterVoice(realIndex) }
            }
        }
    }
}
