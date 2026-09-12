package io.legado.app.ui.book.read

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide
import java.io.File
import java.util.Timer
import java.util.TimerTask
import kotlin.math.abs
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.atomic.AtomicReference
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.get
import androidx.core.view.size
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.IntentData
import io.legado.app.help.TTS
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isEpub
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.isMobi
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.loadGif
import io.legado.app.help.source.getSourceType
import io.legado.app.help.storage.Backup
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.help.loadGif
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.BookCover
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl.Companion.paramPattern
import io.legado.app.utils.GSON
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.MobiFile
import io.legado.app.receiver.NetworkChangedListener
import io.legado.app.receiver.TimeBatteryReceiver
import io.legado.app.service.AiChapterFixService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.ReadAloudFloatService
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.bookmark.BookmarkDialog
import io.legado.app.ui.book.changesource.ChangeBookSourceDialog
import io.legado.app.ui.book.changesource.ChangeChapterSourceDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.read.config.AiChapterFixDialog
import io.legado.app.ui.book.read.config.AutoReadDialog
import io.legado.app.ui.book.read.config.CharacterManagerDialog
import io.legado.app.ui.book.read.config.CharacterVoiceHelper
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.BG_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_ACCENT_COLOR
import io.legado.app.ui.book.read.config.BgTextConfigDialog.Companion.TEXT_COLOR
import io.legado.app.ui.book.read.config.BgmConfigDialog
import io.legado.app.ui.book.read.config.FullscreenAiImageDialog
import io.legado.app.ui.book.read.config.MoreConfigDialog
import io.legado.app.ui.book.read.config.ReadAloudDialog
import io.legado.app.ui.book.read.config.ReadStyleDialog
import io.legado.app.ui.book.read.config.SpeakEngineDialog
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_COLOR
import io.legado.app.ui.book.read.config.TipConfigDialog.Companion.TIP_DIVIDER_COLOR
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.DialogRoleManager
import io.legado.app.ui.book.read.page.ReadView
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.searchContent.SearchContentActivity
import io.legado.app.ui.book.searchContent.SearchResult
import io.legado.app.model.SourceCallBack
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.toc.TocActivityResult
import io.legado.app.ui.book.toc.rule.TxtTocRuleDialog
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.dict.DictDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.replace.edit.ReplaceEditActivity
import io.legado.app.ui.widget.PopupAction
import io.legado.app.ui.widget.dialog.PhotoDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.Debounce
import io.legado.app.utils.LogUtils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.StartActivityContract
import io.legado.app.utils.applyOpenTint
import io.legado.app.utils.buildMainHandler
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.iconItemOnLongClick
import io.legado.app.utils.invisible
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isTrue
import io.legado.app.utils.launch
import io.legado.app.utils.navigationBarGravity
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.sysScreenOffTime
import io.legado.app.utils.throttle
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import com.script.rhino.runScriptWithContext
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.net.toUri

/**
 * 阅读界面
 */
class ReadBookActivity : BaseReadBookActivity(),
    View.OnTouchListener,
    ReadView.CallBack,
    TextActionMenu.CallBack,
    ContentTextView.CallBack,
    PopupMenu.OnMenuItemClickListener,
    ReadMenu.CallBack,
    SearchMenu.CallBack,
    ReadAloudDialog.CallBack,
    ChangeBookSourceDialog.CallBack,
    ChangeChapterSourceDialog.CallBack,
    ReadBook.CallBack,
    AutoReadDialog.CallBack,
    TxtTocRuleDialog.CallBack,
    ColorPickerDialogListener,
    LayoutProgressListener {

    private val tocActivity =
        registerForActivityResult(TocActivityResult()) {
            it?.let {
                viewModel.openChapter(it[0] as Int, it[1] as Int)
            }
        }
    private val sourceEditActivity =
        registerForActivityResult(StartActivityContract(BookSourceEditActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                viewModel.upBookSource {
                    upMenuView()
                }
            }
        }
    private val replaceActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.replaceRuleChanged()
            }
        }
    private val searchContentActivity =
        registerForActivityResult(StartActivityContract(SearchContentActivity::class.java)) {
            val data = it.data ?: return@registerForActivityResult
            val key = data.getLongExtra("key", System.currentTimeMillis())
            val index = data.getIntExtra("index", 0)
            val searchResult = IntentData.get<SearchResult>("searchResult$key")
            val searchResultList = IntentData.get<List<SearchResult>>("searchResultList$key")
            if (searchResult != null && searchResultList != null) {
                viewModel.searchContentQuery = searchResult.query
                binding.searchMenu.upSearchResultList(searchResultList)
                isShowingSearchResult = true
                viewModel.searchResultIndex = index
                binding.searchMenu.updateSearchResultIndex(index)
                binding.searchMenu.selectedSearchResult?.let { currentResult ->
                    ReadBook.saveCurrentBookProgress() //退出全文搜索恢复此时进度
                    skipToSearch(currentResult)
                    showActionMenu()
                }
            }
        }
    private val bookInfoActivity =
        registerForActivityResult(StartActivityContract(BookInfoActivity::class.java)) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_DELETED)
                super.finish()
            } else {
                ReadBook.loadOrUpContent()
            }
        }
    private val selectImageDir = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(AppConst.imagePathKey, uri.toString())
            viewModel.saveImage(it.value, uri)
        }
    }
    private var menu: Menu? = null
    private var backupJob: Job? = null
    private var tts: TTS? = null
    val textActionMenu: TextActionMenu by lazy {
        TextActionMenu(this, this)
    }
    private val popupAction: PopupAction by lazy {
        PopupAction(this)
    }
    override val isInitFinish: Boolean get() = viewModel.isInitFinish
    override val isScroll: Boolean get() = binding.readView.isScroll
    private val isAutoPage get() = binding.readView.isAutoPage
    override var isShowingSearchResult = false
    override var isSelectingSearchResult = false
        set(value) {
            field = value && isShowingSearchResult
        }
    private val timeBatteryReceiver = TimeBatteryReceiver()
    private var screenTimeOut: Long = 0
    private var loadStates: Boolean = false
    // AI处理章节：是否有任务在跑
    private var aiFixRunning = false
    // AI自动处理：上一次触发时的章节下标，避免翻页时重复触发
    private var lastAiAutoChapterIndex = -1
    override val pageFactory get() = binding.readView.pageFactory
    override val pageDelegate get() = binding.readView.pageDelegate
    override val headerHeight: Int get() = binding.readView.curPage.headerHeight
    override val imgBgPaddingStart: Int get() = binding.readView.curPage.imgBgPaddingStart
    private val nextPageDebounce by lazy { Debounce { keyPage(PageDirection.NEXT) } }
    private val prevPageDebounce by lazy { Debounce { keyPage(PageDirection.PREV) } }
    private var bookChanged = false

    // ===== 阅读界面顶部 AI 图片（与朗读界面同源）=====
    /** 可切换的历史图片列表 */
    private var readAiImages: List<File> = emptyList()
    /** 当前显示的历史图片下标 */
    private var readAiImageIndex = -1
    /** 手动切换后 30 秒自动回到最新图片的定时器 */
    private var readAiImageResetTimer: Timer? = null
    /** 当前显示的图片路径，避免重复加载 */
    private var lastReadAiImagePath: String = ""
    private var pageChanged = false
    private val handler by lazy { buildMainHandler() }
    private val screenOffRunnable by lazy { Runnable { keepScreenOn(false) } }
    private val executor = ReadBook.executor
    private val upSeekBarThrottle = throttle(200) {
        runOnUiThread {
            upSeekBarProgress()
            binding.readMenu.upSeekBar()
        }
    }

    //恢复跳转前进度对话框的交互结果
    private var confirmRestoreProcess: Boolean? = null
    private val networkChangedListener by lazy {
        NetworkChangedListener(this)
    }
    private var justInitData: Boolean = false
    private var syncDialog: AlertDialog? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        binding.cursorLeft.setColorFilter(accentColor)
        binding.cursorRight.setColorFilter(accentColor)
        binding.cursorLeft.setOnTouchListener(this)
        binding.cursorRight.setOnTouchListener(this)
        setupReadAiImageGesture()
        upReadAiImageBar()
        window.setBackgroundDrawable(null)
        upScreenTimeOut()
        ReadBook.register(this)
        onBackPressedDispatcher.addCallback(this) {
            if (isShowingSearchResult) {
                exitSearchMenu()
                restoreLastBookProcess()
                return@addCallback
            }
            //拦截返回供恢复阅读进度
            if (ReadBook.lastBookProgress != null && confirmRestoreProcess != false) {
                restoreLastBookProcess()
                return@addCallback
            }
            if (BaseReadAloudService.isPlay()) {
                if (!getPrefBoolean(PreferKey.readAloudBackToBackground, true)) {
                    ReadAloud.pause(this@ReadBookActivity)
                    toastOnUi(R.string.read_aloud_pause)
                    return@addCallback
                }
            }
            if (isAutoPage) {
                autoPageStop()
                return@addCallback
            }
            if (getPrefBoolean("disableReturnKey") && !menuLayoutIsVisible) {
                return@addCallback
            }
            finish()
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        viewModel.initReadBookConfig(intent)
        Looper.myQueue().addIdleHandler {
            // readAloud extra 由 contentLoadFinish() 统一消费，
            // 覆盖 resetData 与 upData（同书重开）两条路径
            viewModel.initData(intent)
            false
        }
        justInitData = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Activity 实例复用时（如停止朗读后再次点击书架耳机按钮），
        // readAloud extra 只通过 onNewIntent 进入；由 contentLoadFinish() 统一消费
        viewModel.initData(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        upSystemUiVisibility()
        if (hasFocus) {
            binding.readMenu.upBrightnessState()
        } else if (!menuLayoutIsVisible) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        upSystemUiVisibility()
        binding.readView.upStatusBar()
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        if (!isTopResumedActivity) {
            ReadBook.cancelPreDownloadTask()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onResume() {
        super.onResume()
        ReadAloudFloatService.isReadBookActivityForeground = true
        ReadAloudFloatService.updateVisibility(this)
        upReadAiImageBar()
        ReadBook.readStartTime = System.currentTimeMillis()
        if (bookChanged) {
            bookChanged = false
            ReadBook.callBack = this
            viewModel.initData(intent)
            justInitData = true
        } else {
            //web端阅读时，app处于阅读界面，本地记录会覆盖web保存的进度，在此处恢复
            ReadBook.webBookProgress?.let {
                ReadBook.setProgress(it)
                ReadBook.webBookProgress = null
            }
        }
        upSystemUiVisibility()
        registerReceiver(timeBatteryReceiver, timeBatteryReceiver.filter)
        binding.readView.upTime()
        screenOffTimerStart()
        // 网络监听，当从无网切换到网络环境时同步进度（注意注册的同时就会收到监听，因此界面激活时无需重复执行同步操作）
        networkChangedListener.register()
        networkChangedListener.onNetworkChanged = {
            // 当网络是可用状态且无需初始化时同步进度（初始化中已有同步进度逻辑）
            if (AppConfig.syncBookProgressPlus && NetworkUtils.isAvailable() && !justInitData) {
                ReadBook.syncProgress({ progress -> sureNewProgress(progress) })
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 注意：这里不要再调用 applyTextSizeForImageMode(false)。
        // 减小量只存内存（ReadBookConfig.textSizeReduceForImage），从不落盘，
        // 进程被杀也不会污染用户的字号设置，无需"还原"。
        // 而在这里清掉会立刻触发 UP_CONFIG(8,5) 重新生成画笔并重新分页，
        // 表现为切后台瞬间字号变回原样、页面重排，朗读再从当前页开头播一遍。
        ReadAloudFloatService.isReadBookActivityForeground = false
        ReadAloudFloatService.updateVisibility(this)
        autoPageStop()
        backupJob?.cancel()
        ReadBook.saveRead()
        ReadBook.cancelPreDownloadTask()
        unregisterReceiver(timeBatteryReceiver)
        upSystemUiVisibility()
        if (!BuildConfig.DEBUG) {
            if (AppConfig.syncBookProgressPlus) {
                ReadBook.syncProgress()
            } else {
                ReadBook.uploadProgress()
            }
            Backup.autoBack(this)
        }
        justInitData = false
        networkChangedListener.unRegister()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_read, menu)
        menu.iconItemOnLongClick(R.id.menu_change_source) {
            PopupMenu(this, it).apply {
                inflate(R.menu.book_read_change_source)
                this.menu.applyOpenTint(this@ReadBookActivity)
                setOnMenuItemClickListener(this@ReadBookActivity)
            }.show()
        }
        menu.iconItemOnLongClick(R.id.menu_refresh) {
            PopupMenu(this, it).apply {
                inflate(R.menu.book_read_refresh)
                this.menu.applyOpenTint(this@ReadBookActivity)
                setOnMenuItemClickListener(this@ReadBookActivity)
            }.show()
        }
        binding.readMenu.refreshMenuColorFilter()
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        this.menu = menu
        upMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        menu.findItem(R.id.menu_same_title_removed)?.isChecked =
            ReadBook.curTextChapter?.sameTitleRemoved == true
        return super.onMenuOpened(featureId, menu)
    }

    /**
     * 更新菜单
     */
    private fun upMenu() {
        val menu = menu ?: return
        val book = ReadBook.book ?: return
        val onLine = !book.isLocal
        for (i in 0 until menu.size) {
            val item = menu[i]
            when (item.groupId) {
                R.id.menu_group_on_line -> item.isVisible = onLine
                R.id.menu_group_local -> item.isVisible = !onLine
                R.id.menu_group_text -> item.isVisible = book.isLocalTxt
                R.id.menu_group_epub -> item.isVisible = book.isEpub
                else -> when (item.itemId) {
                    R.id.menu_enable_replace -> item.isChecked = book.getUseReplaceRule()
                    R.id.menu_re_segment -> item.isChecked = book.getReSegment()
//                    R.id.menu_enable_review -> {
//                        item.isVisible = BuildConfig.DEBUG
//                        item.isChecked = AppConfig.enableReview
//                    }

                    R.id.menu_reverse_content -> item.isVisible = onLine
                    // AI处理章节：只要是文字正文都能处理（在线文字书、本地 txt/epub 等），
                    // 音频/视频/图片/PDF 没有可处理的正文，隐藏
                    R.id.menu_ai_fix_chapter ->
                        item.isVisible = !book.isAudio && !book.isVideo &&
                                !book.isImage && !book.isPdf
                    R.id.menu_del_ruby_tag -> item.isChecked = book.getDelTag(Book.rubyTag)
                    R.id.menu_del_h_tag -> item.isChecked = book.getDelTag(Book.hTag)
                }
            }
        }
        lifecycleScope.launch {
            menu.findItem(R.id.menu_get_progress)?.isVisible = withContext(IO) {
                AppWebDav.isOk
            }
            menu.findItem(R.id.menu_cover_progress)?.isVisible = withContext(IO) {
                AppWebDav.isOk
            }
        }
    }

    /**
     * 菜单
     */
    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_change_source,
            R.id.menu_book_change_source -> {
                binding.readMenu.runMenuOut()
                ReadBook.book?.let {
                    showDialogFragment(ChangeBookSourceDialog(it.name, it.author))
                }
            }

            R.id.menu_chapter_change_source -> lifecycleScope.launch {
                val book = ReadBook.book ?: return@launch
                val chapter =
                    appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
                        ?: return@launch
                binding.readMenu.runMenuOut()
                showDialogFragment(
                    ChangeChapterSourceDialog(book.name, book.author, chapter.index, chapter.title)
                )
            }

            R.id.menu_refresh,
            R.id.menu_refresh_dur -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        ReadBook.curTextChapter = null
                        binding.readView.upContent()
                        viewModel.refreshContentDur(it)
                    }
                }
            }

            R.id.menu_refresh_after -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        ReadBook.clearTextChapter()
                        binding.readView.upContent()
                        viewModel.refreshContentAfter(it)
                    }
                }
            }

            R.id.menu_refresh_all -> {
                if (ReadBook.bookSource == null) {
                    upContent()
                } else {
                    ReadBook.book?.let {
                        refreshContentAll(it)
                    }
                }
            }

            R.id.menu_download -> showDownloadDialog()
            R.id.menu_add_bookmark -> addBookmark()
            R.id.menu_simulated_reading -> showSimulatedReading()
            R.id.menu_edit_content -> showDialogFragment(ContentEditDialog())
            R.id.menu_ai_fix_chapter -> showAiChapterFixDialog()
            R.id.menu_update_toc -> ReadBook.book?.let {
                if (it.isEpub) {
                    BookHelp.clearCache(it)
                    EpubFile.clear()
                }
                if (it.isMobi) {
                    MobiFile.clear()
                }
                loadChapterList(it)
            }

            R.id.menu_enable_replace -> changeReplaceRuleState()
            R.id.menu_re_segment -> ReadBook.book?.let {
                it.setReSegment(!it.getReSegment())
                item.isChecked = it.getReSegment()
                ReadBook.loadContent(false)
            }

//            R.id.menu_enable_review -> {
//                AppConfig.enableReview = !AppConfig.enableReview
//                item.isChecked = AppConfig.enableReview
//                ReadBook.loadContent(false)
//            }

            R.id.menu_del_ruby_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.rubyTag)
                } else {
                    it.removeDelTag(Book.rubyTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_del_h_tag -> ReadBook.book?.let {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    it.addDelTag(Book.hTag)
                } else {
                    it.removeDelTag(Book.hTag)
                }
                refreshContentAll(it)
            }

            R.id.menu_page_anim -> showPageAnimConfig {
                binding.readView.upPageAnim()
                ReadBook.loadContent(false)
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_toc_regex -> showDialogFragment(
                TxtTocRuleDialog(ReadBook.book?.tocUrl)
            )

            R.id.menu_reverse_content -> ReadBook.book?.let {
                viewModel.reverseContent(it)
            }

            R.id.menu_set_charset -> showCharsetConfig()
            R.id.menu_image_style -> {
                val imgStyles =
                    arrayListOf(
                        Book.imgStyleDefault, Book.imgStyleFull, Book.imgStyleText,
                        Book.imgStyleSingle
                    )
                selector(
                    R.string.image_style,
                    imgStyles
                ) { _, index ->
                    val imageStyle = imgStyles[index]
                    ReadBook.book?.setImageStyle(imageStyle)
                    if (imageStyle == Book.imgStyleSingle) {
                        ReadBook.book?.setPageAnim(0)  // 切换图片样式single后，自动切换为覆盖
                        binding.readView.upPageAnim()
                    }
                    ReadBook.loadContent(false)
                }
            }

            R.id.menu_get_progress -> ReadBook.book?.let {
                viewModel.syncBookProgress(it) { progress ->
                    sureSyncProgress(progress)
                }
            }

            R.id.menu_cover_progress -> ReadBook.book?.let {
                ReadBook.uploadProgress(true) { toastOnUi(R.string.upload_book_success) }
            }

            R.id.menu_same_title_removed -> {
                ReadBook.book?.let {
                    val contentProcessor = ContentProcessor.get(it)
                    val textChapter = ReadBook.curTextChapter
                    if (textChapter != null
                        && !textChapter.sameTitleRemoved
                        && !contentProcessor.removeSameTitleCache.contains(
                            textChapter.chapter.getFileName("nr")
                        )
                    ) {
                        toastOnUi("未找到可移除的重复标题")
                    }
                }
                viewModel.reverseRemoveSameTitle()
            }

            R.id.menu_effective_replaces -> showDialogFragment<EffectiveReplacesDialog>()

            R.id.menu_help -> showHelp()
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun refreshContentAll(book: Book) {
        ReadBook.clearTextChapter()
        binding.readView.upContent()
        viewModel.refreshContentAll(book)
    }

    /**
     * AI处理章节：弹框填接口、提示词、处理数量
     */
    private fun showAiChapterFixDialog() {
        val book = ReadBook.book ?: return
        binding.readMenu.runMenuOut()
        AiChapterFixDialog().apply {
            onConfirm = { api, prompt, count, fromCurrent, force ->
                val start = ReadBook.durChapterIndex + if (fromCurrent) 0 else 1
                startAiChapterFix(book, start, count, api, prompt, force, silent = false)
            }
        }.let { showDialogFragment(it) }
    }

    /**
     * AI处理章节。
     * 数量为 1 时处理单章（没缓存会自动下载）；大于 1 时批量处理，只处理已缓存的章节。
     *
     * @param silent 自动处理时不弹等待框，免得挡着阅读
     */
    private fun startAiChapterFix(
        book: Book,
        startIndex: Int,
        count: Int,
        api: String,
        prompt: String,
        force: Boolean,
        silent: Boolean
    ) {
        if (aiFixRunning) {
            if (!silent) toastOnUi("上一个AI处理任务还没结束")
            return
        }
        if (startIndex >= ReadBook.chapterSize) {
            if (!silent) toastOnUi("后面没有章节了")
            return
        }
        val n = count.coerceIn(1, AiChapterFixService.MAX_COUNT)
        aiFixRunning = true
        val onlyCached = n > 1
        val startTitle = appDb.bookChapterDao.getChapter(book.bookUrl, startIndex)?.title.orEmpty()
        val waitDialog = if (silent) null else {
            WaitDialog(this).setText("AI处理《$startTitle》…").apply {
                setCancelable(true)
                show()
            }
        }
        val job = lifecycleScope.launch {
            val result = AiChapterFixService.fixChapters(
                book, ReadBook.bookSource, startIndex, n, api, prompt, force, onlyCached
            ) { progress ->
                // 回调发生在 IO 线程，更新弹窗必须切回主线程
                runOnUiThread {
                    // 弹窗实时显示当前处理的章节与块号（如《第1章》(2/5)）
                    waitDialog?.setText("AI处理：$progress")
                }
            }
            waitDialog?.dismiss()
            // 改过的章节要丢弃旧排版重新读取，否则界面还是修改前的正文
            result.changedIndexes.forEach { ReadBook.reloadChapter(it) }
            // 最终结果（成功/失败都算）写入应用日志：toast 展示时间短且长消息会截断，日志便于回看
            AppLog.put("AI处理章节: ${result.message}")
            toastOnUi(result.message)
        }
        // 调用可能比较久，允许返回键中断
        waitDialog?.setOnCancelListener { job.cancel() }
        job.invokeOnCompletion { aiFixRunning = false }
    }

    /**
     * 自动处理：章节一切换就处理后续章节（下标没变时不重复触发）。
     */
    private fun autoAiFixChapter() {
        if (!AppConfig.aiChapterFixAuto) return
        val index = ReadBook.durChapterIndex
        if (index == lastAiAutoChapterIndex) return
        lastAiAutoChapterIndex = index
        val book = ReadBook.book ?: return
        val api = AppConfig.aiChapterFixApi
        if (api.isBlank()) return
        val start = index + 1
        if (start >= ReadBook.chapterSize) return
        startAiChapterFix(
            book, start, AppConfig.aiChapterFixCount, api, AppConfig.aiChapterFixPrompt,
            force = false, silent = true
        )
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onCompatOptionsItemSelected(item)
    }

    /**
     * 按键拦截,显示菜单
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action
        val isDown = action == 0

        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.runMenuIn()
                return true
            }
            if (!isDown && !binding.readMenu.canShowMenu) {
                binding.readMenu.canShowMenu = true
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 鼠标滚轮事件
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (0 != (event.source and InputDevice.SOURCE_CLASS_POINTER)) {
            if (event.action == MotionEvent.ACTION_SCROLL) {
                val axisValue = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                LogUtils.d("onGenericMotionEvent", "axisValue = $axisValue")
                // 获得垂直坐标上的滚动方向
                if (axisValue < 0.0f) { // 滚轮向下滚
                    mouseWheelPage(PageDirection.NEXT, axisValue)
                } else { // 滚轮向上滚
                    mouseWheelPage(PageDirection.PREV, axisValue)
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * 按键事件
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (menuLayoutIsVisible) {
            return super.onKeyDown(keyCode, event)
        }
        val longPress = event.repeatCount > 0
        when {
            isPrevKey(keyCode) -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            isNextKey(keyCode) -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeyPage(PageDirection.PREV, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeyPage(PageDirection.NEXT, longPress)) {
                return true
            }

            KeyEvent.KEYCODE_PAGE_UP -> {
                handleKeyPage(PageDirection.PREV, longPress)
                return true
            }

            KeyEvent.KEYCODE_PAGE_DOWN -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }

            KeyEvent.KEYCODE_SPACE -> {
                handleKeyPage(PageDirection.NEXT, longPress)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    /**
     * 松开按键事件
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (volumeKeyPage(PageDirection.NONE, false)) {
                    return true
                }
            }

        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * view触摸,文字选择
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean = binding.run {
        if (!binding.readView.isTextSelected) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> textActionMenu.dismiss()
            MotionEvent.ACTION_MOVE -> {
                when (v.id) {
                    R.id.cursor_left -> if (!readView.curPage.getReverseStartCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }

                    R.id.cursor_right -> if (readView.curPage.getReverseEndCursor()) {
                        readView.curPage.selectStartMove(
                            event.rawX + cursorLeft.width,
                            event.rawY - cursorLeft.height
                        )
                    } else {
                        readView.curPage.selectEndMove(
                            event.rawX - cursorRight.width,
                            event.rawY - cursorRight.height
                        )
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                readView.curPage.resetReverseCursor()
                showTextActionMenu()
            }
        }
        return true
    }

    /**
     * 更新文字选择开始位置
     */
    override fun upSelectedStart(x: Float, y: Float, top: Float) = binding.run {
        cursorLeft.x = x - cursorLeft.width
        cursorLeft.y = y
        cursorLeft.visible(true)
        textMenuPosition.x = x
        textMenuPosition.y = top
    }

    /**
     * 更新文字选择结束位置
     */
    override fun upSelectedEnd(x: Float, y: Float) = binding.run {
        cursorRight.x = x
        cursorRight.y = y
        cursorRight.visible(true)
    }

    /**
     * 取消文字选择
     */
    override fun onCancelSelect() = binding.run {
        cursorLeft.invisible()
        cursorRight.invisible()
        textActionMenu.dismiss()
    }

    override fun onLongScreenshotTouchEvent(event: MotionEvent): Boolean {
        return binding.readView.onTouchEvent(event)
    }

    /**
     * 显示文本操作菜单
     */
    override fun showTextActionMenu() {
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        textActionMenu.show(
            binding.textMenuPosition,
            binding.root.height + navigationBarHeight,
            binding.textMenuPosition.x.toInt(),
            binding.textMenuPosition.y.toInt(),
            binding.cursorLeft.y.toInt() + binding.cursorLeft.height,
            binding.cursorRight.x.toInt(),
            binding.cursorRight.y.toInt() + binding.cursorRight.height
        )
    }

    /**
     * 当前选择的文本
     */
    override val selectedText: String get() = binding.readView.getSelectText()

    /**
     * 文本选择菜单操作
     */
    override fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.menu_aloud -> when (AppConfig.contentSelectSpeakMod) {
                1 -> lifecycleScope.launch {
                    binding.readView.aloudStartSelect()
                }

                else -> speak(binding.readView.getSelectText())
            }

            R.id.menu_bookmark -> binding.readView.curPage.let {
                val bookmark = it.createBookmark()
                if (bookmark == null) {
                    toastOnUi(R.string.create_bookmark_error)
                } else {
                    showDialogFragment(BookmarkDialog(bookmark))
                }
                return true
            }

            R.id.menu_replace -> {
                val scopes = arrayListOf<String>()
                ReadBook.book?.name?.let {
                    scopes.add(it)
                }
                ReadBook.bookSource?.bookSourceUrl?.let {
                    scopes.add(it)
                }
                val text = selectedText.lineSequence().map { it.trim() }.joinToString("\n")
                replaceActivity.launch(
                    ReplaceEditActivity.startIntent(
                        this,
                        pattern = text,
                        scope = scopes.joinToString(";")
                    )
                )
                return true
            }

            R.id.menu_search_content -> {
                viewModel.searchContentQuery = selectedText
                openSearchActivity(selectedText)
                return true
            }

            R.id.menu_dict -> {
                showDialogFragment(DictDialog(selectedText))
                return true
            }
        }
        return false
    }

    /**
     * 文本选择菜单操作完成
     */
    override fun onMenuActionFinally() = binding.run {
        textActionMenu.dismiss()
        readView.cancelSelect()
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TTS()
        }
        tts?.speak(text)
    }

    /**
     * 鼠标滚轮翻页
     */
    private fun mouseWheelPage(direction: PageDirection, distance: Float) {
        if (menuLayoutIsVisible || !AppConfig.mouseWheelPage) {
            return
        }
        if (binding.readView.isScroll) {
            // 滚动视图时滚动,否则翻页
            (binding.readView.pageDelegate as? ScrollPageDelegate)?.curPage?.scroll((distance * 50).toInt())
        } else {
            keyPageDebounce(direction, mouseWheel = true, longPress = false)
        }
    }

    /**
     * 音量键翻页
     */
    private fun volumeKeyPage(direction: PageDirection, longPress: Boolean): Boolean {
        if (!AppConfig.volumeKeyPage) {
            return false
        }
        if (!AppConfig.volumeKeyPageOnPlay && BaseReadAloudService.isPlay()) {
            return false
        }
        handleKeyPage(direction, longPress)
        return true
    }

    private fun handleKeyPage(direction: PageDirection, longPress: Boolean) {
        if (AppConfig.keyPageOnLongPress || direction == PageDirection.NONE) {
            keyPage(direction)
        } else {
            keyPageDebounce(direction, longPress = longPress)
        }
    }

    private fun keyPageDebounce(
        direction: PageDirection,
        mouseWheel: Boolean = false,
        longPress: Boolean
    ) {
        if (longPress) {
            return
        }
        nextPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        prevPageDebounce.apply {
            wait = if (mouseWheel) 200L else 600L
            leading = !mouseWheel
            trailing = mouseWheel
        }
        when (direction) {
            PageDirection.NEXT -> nextPageDebounce.invoke()
            PageDirection.PREV -> prevPageDebounce.invoke()
            else -> {}
        }
    }

    private fun keyPage(direction: PageDirection) {
        binding.readView.cancelSelect()
        binding.readView.pageDelegate?.isCancel = false
        binding.readView.pageDelegate?.keyTurnPage(direction)
    }

    override fun upMenuView() {
        handler.post {
            upMenu()
            binding.readMenu.upBookView()
        }
    }

    override fun loadChapterList(book: Book) {
        ReadBook.upMsg(getString(R.string.toc_updateing))
        viewModel.loadChapterList(book)
    }

    /**
     * 内容加载完成
     */
    override fun contentLoadFinish() {
        // 书籍加载完成时再应用一次：进入界面时 ReadBook.book 可能还是 null，
        // 那时图片区域会被跳过，导致"打开书是全屏文字、没有图片"。
        // 该回调可能在后台线程触发，必须切回主线程再操作 View。
        runOnUiThread { upReadAiImageBar() }
        if (intent.getBooleanExtra("readAloud", false)) {
            intent.removeExtra("readAloud")
            ReadBook.readAloud()
        }
        loadStates = true
    }

    /**
     * 更新内容
     */
    override fun upContent(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) {
        lifecycleScope.launch {
            binding.readView.upContent(relativePosition, resetPageOffset)
            if (relativePosition == 0) {
                upSeekBarProgress()
            }
            loadStates = false
            success?.invoke()
        }
        autoAiFixChapter()
    }

    override suspend fun upContentAwait(
        relativePosition: Int,
        resetPageOffset: Boolean,
        success: (() -> Unit)?
    ) = withContext(Main.immediate) {
        binding.readView.upContent(relativePosition, resetPageOffset)
        if (relativePosition == 0) {
            upSeekBarProgress()
        }
        loadStates = false
    }

    override fun upPageAnim(upRecorder: Boolean) {
        lifecycleScope.launch {
            binding.readView.upPageAnim(upRecorder)
        }
    }

    override fun notifyBookChanged() {
        bookChanged = true
        if (!ReadBook.inBookshelf) {
            viewModel.removeFromBookshelf { super.finish() }
        }
    }

    override fun cancelSelect() {
        runOnUiThread {
            binding.readView.cancelSelect()
        }
    }

    /**
     * 页面改变
     */
    override fun pageChanged() {
        pageChanged = true
        // 翻页后立刻按当前屏幕显示的段落重新定位 AI 图片。
        // 不依赖「阅读界面显示AI图片」开关：朗读界面、全屏大图等其它显示点共用同一定位结果。
        AiImageGenerator.locateImageByCurrentPage()
        binding.readView.onPageChange()
        handler.post {
            upSeekBarProgress()
        }
        executor.execute {
            startBackupJob()
        }
    }

    /**
     * 更新进度条位置
     */
    private fun upSeekBarProgress() {
        val progress = when (AppConfig.progressBarBehavior) {
            "page" -> ReadBook.durPageIndex
            else /* chapter */ -> ReadBook.durChapterIndex
        }
        binding.readMenu.setSeekPage(progress)
    }

    /**
     * 显示菜单
     */
    override fun showMenuBar() {
        binding.readMenu.runMenuIn()
    }

    /**
     * 显示背景音乐配置
     */
    override fun showBgmConfig() {
        BgmConfigDialog().show(supportFragmentManager, "bgmConfig")
    }

    override val oldBook: Book?
        get() = ReadBook.book

    override fun changeTo(source: BookSource, book: Book, toc: List<BookChapter>) {
        if (!book.isAudio) {
            viewModel.changeTo(book, toc)
        } else {
            ReadAloud.stop(this)
            lifecycleScope.launch {
                withContext(IO) {
                    ReadBook.book?.migrateTo(book, toc)
                    book.removeType(BookType.updateError)
                    ReadBook.book?.delete()
                    appDb.bookDao.insert(book)
                }
                startActivityForBook(book)
                finish()
            }
        }
    }

    override fun replaceContent(content: String) {
        ReadBook.book?.let {
            viewModel.saveContent(it, content)
        }
    }

    override fun showActionMenu() {
        when {
            BaseReadAloudService.isRun -> showReadAloudDialog()
            isAutoPage -> showDialogFragment<AutoReadDialog>()
            isShowingSearchResult -> binding.searchMenu.runMenuIn()
            else -> binding.readMenu.runMenuIn()
        }
    }

    /**
     * 显示朗读菜单
     */
    override fun showReadAloudDialog() {
        showDialogFragment<ReadAloudDialog>()
    }

    /**
     * 朗读界面是否已弹出。
     *
     * 半屏模式下朗读界面窗口只占屏幕下半部分并开启触摸穿透，上半屏仍属阅读页。
     * 阅读页据此把手势接管为朗读专用（上滑朗读 / 下滑关闭 / 单击关闭），
     * 否则上半屏的滑动会去操作阅读页本身。
     *
     * 用类型查找而非 tag，避免与 [showDialogFragment] 内部使用的
     * `T::class.simpleName` 标签产生耦合。
     */
    override fun isReadAloudDialogShowing(): Boolean {
        val dialog = supportFragmentManager.fragments
            .filterIsInstance<ReadAloudDialog>()
            .firstOrNull() ?: return false
        return dialog.dialog?.isShowing == true
    }

    override fun dismissReadAloudDialog() {
        supportFragmentManager.fragments
            .filterIsInstance<ReadAloudDialog>()
            .firstOrNull()
            ?.dismissAllowingStateLoss()
    }

    /**
     * 显示朗读引擎选择
     */
    override fun showSpeakEngineDialog() {
        showDialogFragment<SpeakEngineDialog>()
    }

    /**
     * 清理 TTS 缓存
     */
    override fun clearTtsCache() {
        SpeakEngineDialog.clearCacheStatic(this)
    }

    /**
     * 自动翻页
     */
    override fun autoPage() {
        ReadAloud.stop(this)
        if (isAutoPage) {
            autoPageStop()
        } else {
            binding.readView.autoPager.start()
            screenTimeOut = -1L
            screenOffTimerStart()
        }
    }

    /**
     * AI 分析当前章节：长按底部菜单第二个图标触发，
     * 等价于「AI处理章节」对话框里的「处理当前章节」。
     * 接口未配置时直接打开配置对话框，避免用户不知从哪填。
     */
    override fun aiAnalyzeCurrentChapter() {
        val book = ReadBook.book ?: return
        val api = AppConfig.aiChapterFixApi
        if (api.isBlank()) {
            toastOnUi("请先填写 AI 接口配置")
            binding.readMenu.runMenuOut()
            showDialogFragment(AiChapterFixDialog().apply {
                onConfirm = { a, prompt, count, fromCurrent, force ->
                    val start = ReadBook.durChapterIndex + if (fromCurrent) 0 else 1
                    startAiChapterFix(book, start, count, a, prompt, force, silent = false)
                }
            })
            return
        }
        startAiChapterFix(
            book, ReadBook.durChapterIndex, 1, api,
            AppConfig.aiChapterFixPrompt, force = false, silent = false
        )
    }

    override fun autoPageStop() {
        if (isAutoPage) {
            binding.readView.autoPager.stop()
            dismissDialogFragment<AutoReadDialog>()
            upScreenTimeOut()
        }
    }

    override fun openSourceEditActivity() {
        ReadBook.bookSource?.let {
            sourceEditActivity.launch {
                putExtra("sourceUrl", it.bookSourceUrl)
            }
        }
    }

    override fun openBookInfoActivity() {
        ReadBook.book?.let {
            bookInfoActivity.launch {
                putExtra("name", it.name)
                putExtra("author", it.author)
            }
        }
    }

    /**
     * 替换
     */
    override fun openReplaceRule() {
        replaceActivity.launch(Intent(this, ReplaceRuleActivity::class.java))
    }

    /**
     * 打开目录
     */
    override fun openChapterList() {
        ReadBook.book?.let {
            tocActivity.launch(it.bookUrl)
        }
    }

    /**
     * 打开搜索界面
     */
    override fun openSearchActivity(searchWord: String?) {
        val book = ReadBook.book ?: return
        searchContentActivity.launch {
            putExtra("bookUrl", book.bookUrl)
            putExtra("searchWord", searchWord ?: viewModel.searchContentQuery)
            putExtra("searchResultIndex", viewModel.searchResultIndex)
            viewModel.searchResultList?.first()?.let {
                if (it.query == viewModel.searchContentQuery) {
                    IntentData.put("searchResultList", viewModel.searchResultList)
                }
            }
        }
    }

    /**
     * 禁用书源
     */
    override fun disableSource() {
        viewModel.disableSource()
    }

    /**
     * 显示阅读样式配置
     */
    override fun showReadStyle() {
        showDialogFragment<ReadStyleDialog>()
    }

    /**
     * 显示更多设置
     */
    override fun showMoreSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    override fun showSearchSetting() {
        showDialogFragment<MoreConfigDialog>()
    }

    /**
     * 更新状态栏,导航栏
     */
    override fun upSystemUiVisibility() {
        upSystemUiVisibility(isInMultiWindow, !menuLayoutIsVisible, bottomDialog > 0)
        upNavigationBarColor()
    }

    // 退出全文搜索
    override fun exitSearchMenu() {
        if (isShowingSearchResult) {
            isShowingSearchResult = false
            binding.searchMenu.invalidate()
            binding.searchMenu.invisible()
            ReadBook.clearSearchResult()
            binding.readView.cancelSelect(true)
        }
    }

    /* 恢复到 全文搜索/进度条跳转前的位置 */
    private fun restoreLastBookProcess() {
        if (confirmRestoreProcess == true) {
            ReadBook.restoreLastBookProgress()
        } else if (confirmRestoreProcess == null) {
            alert(R.string.draw) {
                setMessage(R.string.restore_last_book_process)
                yesButton {
                    confirmRestoreProcess = true
                    ReadBook.restoreLastBookProgress() //恢复启动全文搜索前的进度
                }
                noButton {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
                onCancelled {
                    ReadBook.lastBookProgress = null
                    confirmRestoreProcess = false
                }
            }
        }
    }

    override fun showLogin() {
        ReadBook.bookSource?.let {
            startActivity<SourceLoginActivity> {
                putExtra("bookType", BookType.text)
            }
        }
    }

    override fun payAction() {
        val book = ReadBook.book ?: return
        if (book.isLocal) return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex)
        if (chapter == null) {
            toastOnUi("no chapter")
            return
        }
        alert(R.string.chapter_pay) {
            setMessage(chapter.title)
            yesButton {
                Coroutine.async(lifecycleScope) {
                    val source =
                        ReadBook.bookSource ?: throw NoStackTraceException("no book source")
                    val payAction = source.getContentRule().payAction
                    if (payAction.isNullOrBlank()) {
                        throw NoStackTraceException("no pay action")
                    }
                    val analyzeRule = AnalyzeRule(book, source)
                    analyzeRule.setCoroutineContext(coroutineContext)
                    analyzeRule.setBaseUrl(chapter.url)
                    analyzeRule.setChapter(chapter)
                    analyzeRule.evalJS(payAction).toString()
                }.onSuccess(IO) {
                    if (it.isAbsUrl()) {
                        startActivity<WebViewActivity> {
                            val bookSource = ReadBook.bookSource
                            putExtra("title", getString(R.string.chapter_pay))
                            putExtra("url", it)
                            putExtra("sourceOrigin", bookSource?.bookSourceUrl)
                            putExtra("sourceName", bookSource?.bookSourceName)
                            putExtra("sourceType", bookSource?.getSourceType())
                        }
                    } else if (it.isTrue()) {
                        //购买成功后刷新目录
                        ReadBook.book?.let {
                            ReadBook.curTextChapter = null
                            BookHelp.delContent(book, chapter)
                            loadChapterList(book)
                        }
                    }
                }.onError {
                    AppLog.put("执行购买操作出错\n${it.localizedMessage}", it, true)
                }
            }
            noButton()
        }
    }

    /**
     * 点击图片
     */
    override fun oldClickImg(src: String): Boolean {
        val urlMatcher = paramPattern.matcher(src)
        if (urlMatcher.find()) {
            val urlOptionStr = src.substring(urlMatcher.end())
            val urlOptionMap = GSON.fromJsonObject<Map<String, String>>(urlOptionStr).getOrNull()
            val click = urlOptionMap?.get("click")
            if (click != null) {
                Coroutine.async(lifecycleScope,IO) {
                    val source = ReadBook.bookSource ?: return@async
                    val java = SourceLoginJsExtensions(this@ReadBookActivity, source, bookType = BookType.text)
                    val book = ReadBook.book ?: return@async
                    val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                    runScriptWithContext {
                        source.evalJS(click) {
                            put("java", java)
                            put("book", book)
                            put("chapter", chapter)
                            put("result", src)
                        }
                    }
                }.onError {
                    AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
                }
                return true
            }
            val jsStr = urlOptionMap?.get("js") ?: return false
            Coroutine.async(lifecycleScope, IO) {
                val source = ReadBook.bookSource ?: return@async
                val book = ReadBook.book ?: return@async
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
                val urlNoOption = src.take(urlMatcher.start())
                AnalyzeRule(book, source).apply {
                    setCoroutineContext(coroutineContext)
                    setBaseUrl(chapter.url)
                    setChapter(chapter)
                    evalJS(jsStr, urlNoOption)
                }
            }.onError {
                AppLog.put("执行图片链接js键值出错\n${it.localizedMessage}", it, true)
            }
            return true
        }
        return false
    }

    override fun clickImg(click: String, src: String) {
        Coroutine.async(lifecycleScope,IO) {
            val source = ReadBook.bookSource ?: return@async
            val java = SourceLoginJsExtensions(this@ReadBookActivity, source, bookType = BookType.text)
            val book = ReadBook.book ?: return@async
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, ReadBook.durChapterIndex) ?: throw Exception("no find chapter")
            runScriptWithContext {
                source.evalJS(click) {
                    put("java", java)
                    put("book", book)
                    put("chapter", chapter)
                    put("result", src)
                }
            }
        }.onError {
            AppLog.put("执行图片链接click键值出错\n${it.localizedMessage}", it, true)
        }
    }

    override fun showRoleEditDialog(
        bookName: String,
        chapterTitle: String,
        seq: Int,
        name: String,
        gender: String,
        age: String,
        onSave: (String, String, String) -> Unit
    ) {
        val context = this
        val dialogRef = AtomicReference<AlertDialog>()

        // 加载相邻角色（跨章节，前4后5凑齐9个，排除当前名字）
        val book = ReadBook.book
        val chapterTitles = if (book != null) {
            appDb.bookChapterDao.getChapterList(book.bookUrl).map { it.title }
        } else {
            emptyList()
        }
        val currentChapterIndex = chapterTitles.indexOf(chapterTitle)
        val allNeighborRoles = DialogRoleManager.collectNeighborRoles(
            bookName, chapterTitle, seq, name, chapterTitles, currentChapterIndex
        )

        val scrollView = ScrollView(context)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32.dpToPx(), 16.dpToPx(), 32.dpToPx(), 16.dpToPx())
        }
        scrollView.addView(layout)

        // 先创建输入控件（供按钮引用）
        val etName = EditText(context).apply {
            hint = "名字"
            setText(name)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val genderOptions = listOf("男", "女", "特殊", "旁白", "系统", "")
        val spGender = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                genderOptions
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(genderOptions.indexOf(gender).coerceAtLeast(0))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(2.dpToPx(), context.getColor(R.color.btn_bg_press_2))
                cornerRadius = 8.dpToPx().toFloat()
            }
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        }
        val ageOptions = listOf(
            "", "男童", "少年", "男青年", "男中年", "男老年",
            "女童", "少女", "女青年", "女中年", "女老年", "主角", "特殊", "旁白", "系统"
        )
        val spAge = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                ageOptions
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(ageOptions.indexOf(age).coerceAtLeast(0))
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke(2.dpToPx(), context.getColor(R.color.btn_bg_press_2))
                cornerRadius = 8.dpToPx().toFloat()
            }
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
        }

        // 试听按钮
        val btnPreview = Button(context).apply {
            text = "试听"
            textSize = 12f
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                val currentName = etName.text.toString().trim()
                if (currentName.isNotEmpty()) {
                    CharacterVoiceHelper.previewCharacterVoice(
                        context = context,
                        lifecycleOwner = this@ReadBookActivity,
                        bookName = bookName,
                        name = currentName,
                        onToast = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                    )
                } else {
                    Toast.makeText(context, "名字不能为空", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 发音人选择按钮
        val btnVoice = Button(context).apply {
            textSize = 12f
            setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(96.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
            val initialVoice = CharacterVoiceHelper.findCharacterVoice(bookName, name) ?: "未设置"
            text = initialVoice
            setOnClickListener {
                val currentName = etName.text.toString().trim()
                if (currentName.isEmpty()) {
                    Toast.makeText(context, "名字不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                CharacterVoiceHelper.showVoiceCategoryDialog(context, this@ReadBookActivity) { category ->
                    CharacterVoiceHelper.showVoiceListDialog(context, this@ReadBookActivity, category) { voice ->
                        CharacterVoiceHelper.saveCharacterVoice(bookName, currentName, voice)
                        text = voice
                        Toast.makeText(context, "已保存发音人: $voice", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 名字输入框 + 按钮行
        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nameRow.addView(etName)
        nameRow.addView(btnPreview)
        nameRow.addView(btnVoice)

        // 相邻角色按钮（3x3 网格布局）
        if (allNeighborRoles.isNotEmpty()) {
            val tvLabel = TextView(context).apply {
                text = "相邻角色"
                textSize = 14f
                setPadding(0, 0, 0, 8.dpToPx())
            }
            layout.addView(tvLabel)

            val gridContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            var currentRow: LinearLayout? = null
            allNeighborRoles.take(9).forEachIndexed { index, role ->
                if (index % 3 == 0) {
                    currentRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    gridContainer.addView(
                        currentRow,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
                val btn = Button(context).apply {
                    text = role.name
                    textSize = 11f
                    setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                    setOnClickListener {
                        etName.setText(role.name)
                        spGender.setSelection(
                            genderOptions.indexOf(role.gender).coerceAtLeast(0)
                        )
                        spAge.setSelection(
                            ageOptions.indexOf(role.age).coerceAtLeast(0)
                        )
                        val v = CharacterVoiceHelper.findCharacterVoice(bookName, role.name) ?: "未设置"
                        btnVoice.text = v
                    }
                }
                currentRow?.addView(
                    btn,
                    LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply {
                        if (index % 3 != 2) marginEnd = 4.dpToPx()
                        if (index >= 3) topMargin = 4.dpToPx()
                    }
                )
            }

            layout.addView(
                gridContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12.dpToPx() }
            )
        }

        layout.addView(
            nameRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 性别 + 年龄 上下两排占左半边，右半边放删除同名角色音频按钮（占两排高度）
        val genderAgeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val genderAgeLeft = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        spGender.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4.dpToPx() }
        spAge.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        genderAgeLeft.addView(spGender)
        genderAgeLeft.addView(spAge)

        val btnDeleteRoleAudio = Button(context).apply {
            text = "删除本章同名角色音频"
            textSize = 11f
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginStart = 8.dpToPx() }
            setOnClickListener { _ ->
                val deleted = AppConfig.deleteCurrentChapterRoleAudioCache(name)
                android.widget.Toast.makeText(
                    context,
                    if (deleted > 0) "已删除 $deleted 个同名角色段落的音频缓存"
                    else "未找到同名角色对应的音频缓存",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        genderAgeRow.addView(genderAgeLeft)
        genderAgeRow.addView(btnDeleteRoleAudio)
        layout.addView(genderAgeRow)

        // 底部按钮行：保存 / 替换整章 / 替换全书（取消放在 AlertDialog 标准按钮）
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16.dpToPx() }
        }

        fun readInputs(): Triple<String, String, String> {
            val n = etName.text.toString().trim()
            val g = genderOptions.getOrNull(spGender.selectedItemPosition) ?: ""
            val a = ageOptions.getOrNull(spAge.selectedItemPosition) ?: ""
            return Triple(n, g, a)
        }

        val doSave: () -> Unit = {
            val (n, g, a) = readInputs()
            onSave(n, g, a)
            dialogRef.get()?.dismiss()
        }
        val btnSave = Button(context).apply {
            text = "保存"
            textSize = 13f
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 6.dpToPx() }
            setOnClickListener { _ -> doSave() }
        }
        val btnReplaceChapter = Button(context).apply {
            text = "替换整章"
            textSize = 13f
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 6.dpToPx() }
            setOnClickListener { _ ->
                val (n, g, a) = readInputs()
                AlertDialog.Builder(context)
                    .setTitle("确认替换整章")
                    .setMessage("将把本章所有名为「$name」的角色（姓名/性别/年龄）替换为当前输入的值，确定？")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DialogRoleManager.replaceChapterRole(bookName, chapterTitle, name, n, g, a)
                        onSave(n, g, a)
                        dialogRef.get()?.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        val btnReplaceBook = Button(context).apply {
            text = "替换全书"
            textSize = 13f
            setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { _ ->
                val (n, g, a) = readInputs()
                AlertDialog.Builder(context)
                    .setTitle("确认替换全书")
                    .setMessage("将把本书所有章节中名为「$name」的角色（姓名/性别/年龄）替换为当前输入的值，确定？")
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DialogRoleManager.replaceBookRole(bookName, name, n, g, a)
                        onSave(n, g, a)
                        dialogRef.get()?.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        btnRow.addView(btnSave)
        btnRow.addView(btnReplaceChapter)
        btnRow.addView(btnReplaceBook)
        layout.addView(btnRow)

        val dialog = AlertDialog.Builder(context)
            .setTitle("编辑角色")
            .setView(scrollView)
            // Neutral 位于按钮栏最左侧（排列顺序为 Neutral | Negative | Positive），
            // 即「角色管理 | 取消 | 保存」。
            // 与「取消」一致：不保存当前修改，仅关闭本对话框后进入角色管理。
            .setNeutralButton("角色管理") { _, _ ->
                dialogRef.get()?.dismiss()
                showDialogFragment(CharacterManagerDialog())
            }
            .setPositiveButton("保存") { _, _ -> doSave() }
            .setNegativeButton("取消", null)
            .show()
        dialogRef.set(dialog)
    }

    /**
     * 朗读按钮
     */
    override fun onClickReadAloud() {
        autoPageStop()
        when {
            !BaseReadAloudService.isRun -> {
                ReadAloud.upReadAloudClass()
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim) {
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadBook.readAloud()
                }
            }

            BaseReadAloudService.pause -> {
                val scrollPageAnim = ReadBook.pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    pageChanged = false
                    val pos = binding.readView.getReadAloudPos()
                    if (pos != null) {
                        val (index, line) = pos
                        if (ReadBook.durChapterIndex != index) {
                            ReadBook.openChapter(index, line.chapterPosition, false) {
                                ReadBook.readAloud(startPos = line.pagePosition)
                            }
                        } else {
                            ReadBook.durChapterPos = line.chapterPosition
                            ReadBook.readAloud(startPos = line.pagePosition)
                        }
                    } else {
                        ReadBook.readAloud()
                    }
                } else {
                    ReadAloud.resume(this)
                }
            }

            else -> ReadAloud.pause(this)
        }
    }

    override fun showHelp() {
        showHelp("readMenuHelp")
    }

    /**
     * 长按图片
     */
    @SuppressLint("RtlHardcoded")
    override fun onImageLongPress(x: Float, y: Float, src: String) {
        popupAction.setItems(
            listOf(
                SelectItem(getString(R.string.show), "show"),
                SelectItem(getString(R.string.refresh), "refresh"),
                SelectItem(getString(R.string.action_save), "save"),
                SelectItem(getString(R.string.menu), "menu"),
                SelectItem(getString(R.string.select_folder), "selectFolder")
            )
        )
        popupAction.onActionClick = {
            when (it) {
                "show" -> showDialogFragment(PhotoDialog(src))
                "refresh" -> viewModel.refreshImage(src)
                "save" -> {
                    val path = ACache.get().getAsString(AppConst.imagePathKey)
                    if (path.isNullOrEmpty()) {
                        selectImageDir.launch {
                            value = src
                        }
                    } else {
                        viewModel.saveImage(src, path.toUri())
                    }
                }

                "menu" -> showActionMenu()
                "selectFolder" -> selectImageDir.launch()
            }
            popupAction.dismiss()
        }
        val navigationBarHeight =
            if (!ReadBookConfig.hideNavigationBar && navigationBarGravity == Gravity.BOTTOM)
                binding.navigationBar.height else 0
        popupAction.showAtLocation(
            binding.readView, Gravity.BOTTOM or Gravity.LEFT, x.toInt(),
            binding.root.height + navigationBarHeight - y.toInt()
        )
    }

    /**
     * colorSelectDialog
     */
    override fun onColorSelected(dialogId: Int, color: Int) = ReadBookConfig.durConfig.run {
        when (dialogId) {
            TEXT_COLOR -> {
                setCurTextColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TEXT_ACCENT_COLOR -> {
                setCurTextAccentColor(color)
                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6, 9, 11))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            BG_COLOR -> {
                setCurBg(0, "#${color.hexString}")
                postEvent(EventBus.UP_CONFIG, arrayListOf(1))
                if (AppConfig.readBarStyleFollowPage) {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            }

            TIP_COLOR -> {
                ReadTipConfig.tipColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }

            TIP_DIVIDER_COLOR -> {
                ReadTipConfig.tipDividerColor = color
                postEvent(EventBus.TIP_COLOR, "")
                postEvent(EventBus.UP_CONFIG, arrayListOf(2))
            }
        }
    }

    /**
     * colorSelectDialog
     */
    override fun onDialogDismissed(dialogId: Int) = Unit

    override fun onTocRegexDialogResult(tocRegex: String) {
        ReadBook.book?.let {
            it.tocUrl = tocRegex
            loadChapterList(it)
        }
    }

    private fun sureSyncProgress(progress: BookProgress) {
        alert(R.string.get_book_progress) {
            setMessage(R.string.current_progress_exceeds_cloud)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    /* 进度条跳转到指定章节 */
    override fun skipToChapter(index: Int) {
        ReadBook.saveCurrentBookProgress() //退出章节跳转恢复此时进度
        viewModel.openChapter(index)
    }

    /* 全文搜索跳转 */
    override fun navigateToSearch(searchResult: SearchResult, index: Int) {
        viewModel.searchResultIndex = index
        skipToSearch(searchResult)
    }

    override fun onMenuShow() {
        binding.readView.autoPager.pause()
    }

    override fun onMenuHide() {
        binding.readView.autoPager.resume()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upSeekBarThrottle.invoke()
        binding.readView.onLayoutPageCompleted(index, page)
    }

    /* 全文搜索跳转 */
    private fun skipToSearch(searchResult: SearchResult) {
        if (searchResult.chapterIndex != ReadBook.durChapterIndex) {
            viewModel.openChapter(searchResult.chapterIndex) {
                jumpToPosition(searchResult)
            }
        } else {
            jumpToPosition(searchResult)
        }
    }

    private fun jumpToPosition(searchResult: SearchResult) {
        val curTextChapter = ReadBook.curTextChapter ?: return
        binding.searchMenu.updateSearchInfo()
        val searchResultPositions =
            viewModel.searchResultPositions(curTextChapter, searchResult)
        val (pageIndex, lineIndex, charIndex, addLine, charIndex2) = searchResultPositions
        ReadBook.skipToPage(pageIndex) {
            isSelectingSearchResult = true
            binding.readView.curPage.selectStartMoveIndex(0, lineIndex, charIndex)
            when (addLine) {
                0 -> binding.readView.curPage.selectEndMoveIndex(
                    0,
                    lineIndex,
                    charIndex + searchResultPositions[5] - 1
                )

                1 -> binding.readView.curPage.selectEndMoveIndex(
                    0, lineIndex + 1, charIndex2
                )
                //consider change page, jump to scroll position
                -1 -> binding.readView.curPage.selectEndMoveIndex(1, 0, charIndex2)
            }
            binding.readView.isTextSelected = true
            isSelectingSearchResult = false
        }
    }

    override fun addBookmark() {
        val book = ReadBook.book
        val page = ReadBook.curTextChapter?.getPage(ReadBook.durPageIndex)
        if (book != null && page != null) {
            val bookmark = book.createBookMark().apply {
                chapterIndex = ReadBook.durChapterIndex
                chapterPos = ReadBook.durChapterPos
                chapterName = page.title
                bookText = page.text.trim()
            }
            showDialogFragment(BookmarkDialog(bookmark))
        }
    }

    override fun changeReplaceRuleState() {
        ReadBook.book?.let {
            it.setUseReplaceRule(!it.getUseReplaceRule())
            ReadBook.saveRead()
            menu?.findItem(R.id.menu_enable_replace)?.isChecked = it.getUseReplaceRule()
            viewModel.replaceRuleChanged()
        }
    }

    private fun startBackupJob() {
        backupJob?.cancel()
        backupJob = lifecycleScope.launch(IO) {
            delay(300000)
            ReadBook.book?.let {
                AppWebDav.uploadBookProgress(it)
                ensureActive()
                it.update()
                Backup.autoBack(this@ReadBookActivity)
            }
        }
    }

    override fun sureNewProgress(progress: BookProgress) {
        syncDialog?.dismiss()
        syncDialog = alert(R.string.get_book_progress) {
            setMessage(R.string.cloud_progress_exceeds_current)
            okButton {
                ReadBook.setProgress(progress)
            }
            noButton()
        }
    }

    override fun finish() {
        val book = ReadBook.book ?: return super.finish()
        if (ReadBook.inBookshelf) {
            callBackBookEnd()
            return super.finish()
        }
        if (!AppConfig.showAddToShelfAlert) {
            callBackBookEnd()
            viewModel.removeFromBookshelf { super.finish() }
        } else {
            alert(title = getString(R.string.add_to_bookshelf)) {
                setMessage(getString(R.string.check_add_bookshelf, book.name))
                okButton {
                    ReadBook.book?.removeType(BookType.notShelf)
                    ReadBook.book?.save()
                    SourceCallBack.callBackBook(SourceCallBack.ADD_BOOK_SHELF, ReadBook.bookSource, ReadBook.book)
                    ReadBook.inBookshelf = true
                    setResult(RESULT_OK)
                }
                noButton {
                    callBackBookEnd()
                    viewModel.removeFromBookshelf { super.finish() }
                }
            }
        }
    }

    private fun callBackBookEnd() {
        SourceCallBack.callBackBook(SourceCallBack.END_READ, ReadBook.bookSource, ReadBook.book, ReadBook.curTextChapter?.chapter)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 先停掉图片回位定时器，避免销毁后仍在 runOnUiThread 里触发 Glide 加载
        cancelReadAiImageReset()
        // 清掉图片界面的字号减小量，避免影响离开阅读页之后的排版
        ReadBookConfig.textSizeReduceForImage = 0
        tts?.clearTts()
        textActionMenu.dismiss()
        popupAction.dismiss()
        binding.readView.onDestroy()
        ReadBook.unregister(this)
        handler.removeCallbacksAndMessages(null) // 清理Handler消息
        if (!ReadBook.inBookshelf && !isChangingConfigurations) {
            viewModel.removeFromBookshelf(null)
        }
        if (!BuildConfig.DEBUG) {
            Backup.autoBack(this)
        }
    }

    override fun observeLiveBus() = binding.run {
        observeEvent<String>(EventBus.TIME_CHANGED) { readView.upTime() }
        observeEvent<Int>(EventBus.BATTERY_CHANGED) { readView.upBattery(it) }
        observeEvent<Boolean>(EventBus.MEDIA_BUTTON) {
            if (it) {
                onClickReadAloud()
            } else {
                ReadBook.readAloud(!BaseReadAloudService.pause)
            }
        }
        observeEvent<ArrayList<Int>>(EventBus.UP_CONFIG) {
            it.forEach { value ->
                when (value) {
                    0 -> upSystemUiVisibility()
                    1 -> {
                        readView.upBg()
                        upReadAiImageBg()
                    }
                    2 -> readView.upStyle()
                    3 -> readView.upBgAlpha()
                    4 -> readView.upPageSlopSquare()
                    5 -> if (isInitFinish) ReadBook.loadContent(resetPageOffset = false)
                    6 -> readView.upContent(resetPageOffset = false)
                    8 -> ChapterProvider.upStyle()
                    9 -> readView.invalidateTextPage()
                    10 -> ChapterProvider.upLayout()
                    11 -> readView.submitRenderTask()
                    12 -> readView.upPageTouchClick()
                }
            }
        }
        observeEvent<Int>(EventBus.ALOUD_STATE) {
            if (it == Status.STOP || it == Status.PAUSE) {
                ReadBook.curTextChapter?.let { textChapter ->
                    val page = textChapter.getPageByReadPos(ReadBook.durChapterPos)
                    if (page != null) {
                        page.removePageAloudSpan()
                        readView.upContent(resetPageOffset = false)
                    }
                }
            }
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_AUDIO_CACHE_REFRESH) {
            ReadBook.loadAudioCacheIndicators(ReadBook.curTextChapter)
        }
        observeEventSticky<Int>(EventBus.TTS_PROGRESS) { chapterStart ->
            lifecycleScope.launch(IO) {
                if (BaseReadAloudService.isPlay()) {
                    ReadBook.curTextChapter?.let { textChapter ->
                        ReadBook.durChapterPos = chapterStart
                        val pageIndex = ReadBook.durPageIndex
                        val aloudSpanStart = chapterStart - textChapter.getReadLength(pageIndex)
                        textChapter.getPage(pageIndex)
                            ?.upPageAloudSpan(aloudSpanStart)
                        upContent()
                    }
                }
            }
        }
        observeEvent<Boolean>(PreferKey.keepLight) {
            upScreenTimeOut()
        }
        observeEvent<Boolean>(PreferKey.textSelectAble) {
            readView.curPage.upSelectAble(it)
        }
        observeEvent<String>(PreferKey.showBrightnessView) {
            readMenu.upBrightnessState()
        }
        observeEvent<List<SearchResult>>(EventBus.SEARCH_RESULT) {
            viewModel.searchResultList = it
        }
        observeEvent<Boolean>(EventBus.UPDATE_READ_ACTION_BAR) {
            readMenu.reset()
        }
        observeEvent<Boolean>(EventBus.UP_SEEK_BAR) {
            readMenu.upSeekBar()
        }
        // 朗读界面同源的 AI 图片：随朗读进度自动切换到阅读界面顶部
        observeEvent<String>(EventBus.AI_IMAGE_CHANGED) {
            updateReadAiImage(it)
        }
        observeEvent<Boolean>(EventBus.READ_ALOUD_CONFIG_CHANGED) {
            upReadAiImageBar()
        }
    }

    // ==================================================================
    // 阅读界面顶部 AI 图片（数据来源与朗读界面完全一致：AiImageGenerator）
    // 上半屏显示图片，下半屏仍是正常正文，翻页/滚动/选中等操作均不受影响
    // ==================================================================

    /** 应用开关与尺寸：顶部图片区域显示与朗读界面同款图片 */
    private fun upReadAiImageBar() {
        val iv = binding.readAiImage
        if (!AppConfig.readAloudImageInReadPage) {
            setReadAiImageVisible(false)
            return
        }
        val book = ReadBook.book
        if (book == null) {
            setReadAiImageVisible(false)
            return
        }
        applyReadAiImageSize()

        // 与朗读界面同逻辑：优先最近生成的 AI 图，没有则显示书籍封面
        val aiImage = if (AppConfig.readAloudAiImage) {
            lastReadAiImagePath.takeIf { it.isNotBlank() }
                ?.let { File(it) }?.takeIf { it.exists() }
                ?: AiImageGenerator.getLastGeneratedImage()
        } else null
        if (aiImage != null) {
            loadReadAiImage(aiImage)
        } else {
            loadReadBookCover()
        }
        upReadAiImageBg()
        setReadAiImageVisible(true)
        if (AppConfig.readAloudAiImage) {
            // 请求 Service 同步当前场景图片，避免显示过期图片
            postEvent(EventBus.AI_IMAGE_REQUEST, "0")
        }
    }

    /**
     * 计算并应用顶部图片的显示尺寸：宽度取「阅读界面AI图片宽度」，高度按 340/240 比例。
     * 高度上限为屏幕高度的 45%，超限则按比例回缩宽度，避免图片占满整屏遮挡正文。
     *
     * 注意：显示图片前必须先调用本方法。ImageView 布局是 wrap_content，
     * 未设定尺寸就加载图片时会按原图像素尺寸撑开，表现为一张超大图覆盖全屏。
     */
    private fun applyReadAiImageSize() {
        // 后台线程调用会抛 CalledFromWrongThreadException，这里统一切回主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            binding.readAiImage.post { applyReadAiImageSize() }
            return
        }
        val iv = binding.readAiImage
        val metrics = resources.displayMetrics
        val screenHeightDp = (metrics.heightPixels / metrics.density).toInt()
        val maxHeightDp = (screenHeightDp * 0.45f).toInt().coerceAtLeast(80)
        var widthDp = AppConfig.readAloudImageInReadPageWidth.coerceIn(80, 600)
        var heightDp = (widthDp * 340f / 240f).toInt()
        if (heightDp > maxHeightDp) {
            heightDp = maxHeightDp
            widthDp = (heightDp * 240f / 340f).toInt()
        }
        val lp = (iv.layoutParams as? LinearLayout.LayoutParams)
            ?: LinearLayout.LayoutParams(widthDp.dpToPx(), heightDp.dpToPx())
        lp.width = widthDp.dpToPx()
        lp.height = heightDp.dpToPx()
        lp.gravity = Gravity.CENTER_HORIZONTAL
        iv.layoutParams = lp
    }

    /**
     * 顶部下滑手势：完全进入图片界面。
     * 等价于把「阅读界面显示AI图片」开关打开，状态持久化，不会因为重新朗读而恢复。
     */
    override fun onTopSwipeDown() {
        if (AppConfig.readAloudImageInReadPage
            && binding.readAiImage.visibility == View.VISIBLE
        ) return
        AppConfig.readAloudImageInReadPage = true
        upReadAiImageBar()
        toastOnUi("已进入图片界面")
    }

    /**
     * 图片上滑手势：完全退出图片界面。
     * 等价于把「阅读界面显示AI图片」开关关闭，状态持久化，直到再次下滑进入。
     */
    private fun exitReadAiImageBar() {
        if (binding.readAiImage.visibility != View.VISIBLE) return
        AppConfig.readAloudImageInReadPage = false
        setReadAiImageVisible(false)
        toastOnUi("已退出图片界面")
    }

    /**
     * 顶部图片区域的底色跟随阅读界面背景，避免出现黑色底色。
     * 纯色背景直接取该颜色；图片背景取其均值色（bgMeanColor，由 ReadBookConfig.upBg 计算）。
     * 强制不透明：阅读背景有「背景透明度」设置（bgAlpha 可低于 100），
     * 若沿用半透明色，图片两侧仍会透出下层内容，这里统一置为全不透明。
     */
    private fun upReadAiImageBg() {
        val color = (ReadBookConfig.bg as? ColorDrawable)?.color ?: ReadBookConfig.bgMeanColor
        binding.llReadContent.setBackgroundColor(
            if (color != 0) color or 0xFF000000.toInt() else getCompatColor(R.color.background)
        )
    }

    /** 显示书籍封面（无 AI 图片时的兜底，与朗读界面一致） */
    private fun loadReadBookCover() {
        if (!isActivityAlive()) return
        val book = ReadBook.book ?: return
        val iv = binding.readAiImage
        // 用 applicationContext 加载，避免与 Activity 生命周期绑定导致销毁后加载崩溃
        // 同样用当前图占位，避免切回封面时闪烁
        val placeholder = iv.drawable
        BookCover.load(applicationContext, book.getDisplayCover())
            .placeholder(placeholder)
            .error(placeholder)
            .into(iv)
    }

    /** Activity 是否仍可用于图片加载（定时任务/回调可能在销毁之后才触发） */
    private fun isActivityAlive(): Boolean = !isFinishing && !isDestroyed

    /** 收到生图事件后更新顶部图片 */
    private fun updateReadAiImage(imagePath: String) {
        if (!AppConfig.readAloudImageInReadPage) return
        if (imagePath.isBlank()) {
            // 无 AI 图片时回退到书籍封面，保持与朗读界面一致
            lastReadAiImagePath = ""
            readAiImages = emptyList()
            readAiImageIndex = -1
            loadReadBookCover()
            setReadAiImageVisible(true)
            return
        }
        val file = File(imagePath)
        if (!file.exists()) return
        lastReadAiImagePath = imagePath
        readAiImages = AiImageGenerator.getDisplayGallery()
        // 按朗读顺序定位当前图，不能再假设下标 0
        readAiImageIndex = AiImageGenerator.getGalleryIndexOfCurrent(readAiImages)
        loadReadAiImage(file)
        setReadAiImageVisible(true)
        cancelReadAiImageReset()
    }

    private fun loadReadAiImage(file: File) {
        // Activity 已销毁时 Glide 会抛 "You cannot start a load for a destroyed activity"
        if (!isActivityAlive()) return
        val iv = binding.readAiImage
        if (file.extension.equals("gif", ignoreCase = true)) {
            iv.loadGif(file, ImageView.ScaleType.CENTER_CROP)
            return
        }
        // 以当前图作为占位：Glide 无占位时会先清空 ImageView 再加载，
        // 表现为"先露出背景再显示新图"的闪烁；与全屏大图界面保持一致。
        val placeholder = iv.drawable
        Glide.with(iv)
            .load(file)
            .placeholder(placeholder)
            .error(placeholder)
            .into(iv)
    }

    /**
     * 显隐顶部图片区域。高度变化会让 ReadView 重新测量，
     * 由 ContentTextView.onSizeChanged -> ChapterProvider.upViewSize 自动重新分页。
     */
    private fun setReadAiImageVisible(show: Boolean) {
        // 同样防后台线程调用
        if (Looper.myLooper() != Looper.getMainLooper()) {
            binding.readAiImage.post { setReadAiImageVisible(show) }
            return
        }
        val iv = binding.readAiImage
        val target = if (show) View.VISIBLE else View.GONE
        // 显示前必须先确定尺寸：ImageView 是 wrap_content，
        // 未设定尺寸就加载会按原图像素尺寸撑开成一张超大图。
        if (show) applyReadAiImageSize()
        if (iv.visibility == target) return
        iv.visibility = target
        iv.requestLayout()
        applyTextSizeForImageMode(show)
    }

    /**
     * 图片界面显示时按「半屏字号减小」把正文字号调小，让下半屏尽量多显示文字。
     *
     * **只设置显示用的减小量，不改动 [ReadBookConfig.textSize]**：
     * 排版取字号时统一走 `ReadBookConfig.effectiveTextSize`，
     * 用户在设置里定的字号始终保持原值，进/出图片界面不会影响它。
     */
    private fun applyTextSizeForImageMode(show: Boolean) {
        val reduce = if (show) {
            AppConfig.readAloudImageInReadPageTextSizeReduce.coerceIn(0, 10)
        } else {
            0
        }
        if (ReadBookConfig.textSizeReduceForImage == reduce) return
        ReadBookConfig.textSizeReduceForImage = reduce
        // 8: 重新生成画笔(字号)  5: 重新分页
        postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5))
    }

    /** 快捷设置「半屏字号减小」 */
    private fun showReadAiImageTextSizeReduceSetting() {
        val current = AppConfig.readAloudImageInReadPageTextSizeReduce
        val editText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            hint = "字号减小量（sp），默认2，范围0-10，0为不减小"
        }
        AlertDialog.Builder(this)
            .setTitle("半屏字号减小（sp）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val value = editText.text.toString().toIntOrNull()?.coerceIn(0, 10) ?: 2
                AppConfig.readAloudImageInReadPageTextSizeReduce = value
                // 直接按新值重算显示用的减小量，不再动 ReadBookConfig.textSize
                applyTextSizeForImageMode(binding.readAiImage.visibility == View.VISIBLE)
                toastOnUi("半屏字号减小已设为 ${value}sp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 切换历史图片。图库按朗读顺序排列：
     * direction = +1 后一张（后续场景），-1 前一张（已读过的场景）
     */
    private fun switchReadAiImage(direction: Int) {
        if (readAiImages.isEmpty()) {
            readAiImages = AiImageGenerator.getDisplayGallery()
            if (readAiImages.isEmpty()) {
                toastOnUi("暂无历史图片")
                return
            }
            // 按朗读顺序定位当前显示的那张
            readAiImageIndex = AiImageGenerator.getGalleryIndexOfCurrent(readAiImages)
        }
        val newIndex = readAiImageIndex + direction
        if (newIndex < 0) {
            toastOnUi("已是第一张")
            return
        }
        if (newIndex >= readAiImages.size) {
            toastOnUi("已是最后一张")
            return
        }
        readAiImageIndex = newIndex
        val file = readAiImages[readAiImageIndex]
        if (file.exists()) {
            lastReadAiImagePath = file.absolutePath
            loadReadAiImage(file)
        }
        scheduleReadAiImageReset()
    }

    /** 手动切换后 30 秒无操作，自动回到最新图片并恢复自动切换 */
    private fun scheduleReadAiImageReset() {
        readAiImageResetTimer?.cancel()
        readAiImageResetTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    runOnUiThread {
                        readAiImages = AiImageGenerator.getDisplayGallery()
                        readAiImageIndex = AiImageGenerator.getGalleryIndexOfCurrent(readAiImages)
                        readAiImages.getOrNull(readAiImageIndex)?.let { current ->
                            if (current.exists()) {
                                lastReadAiImagePath = current.absolutePath
                                loadReadAiImage(current)
                            }
                        }
                        readAiImageResetTimer?.cancel()
                        readAiImageResetTimer = null
                    }
                }
            }, 30000L)
        }
    }

    private fun cancelReadAiImageReset() {
        readAiImageResetTimer?.cancel()
        readAiImageResetTimer = null
    }

    /**
     * 顶部图片手势（与朗读界面保持一致）：
     * - 左右滑动：切换上一张 / 下一张
     * - 单击左 1/3：上一张；单击右 1/3：下一张；单击中 1/3：全屏预览
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupReadAiImageGesture() {
        val iv = binding.readAiImage
        var downX = 0f
        var downY = 0f
        var moved = false
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (moved) return false
                val viewWidth = iv.width.takeIf { it > 0 } ?: return false
                when {
                    // 左 1/3：前一张（已读过的场景）；右 1/3：后一张（后续场景）
                    e.x < viewWidth / 3f -> switchReadAiImage(-1)
                    e.x > viewWidth * 2f / 3f -> switchReadAiImage(1)
                    else -> showDialogFragment(FullscreenAiImageDialog())
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (moved) return false
                val startX = e1?.x ?: downX
                val dx = e2.x - startX
                val dy = e2.y - (e1?.y ?: downY)
                if (abs(dx) > 50 && abs(dx) > abs(dy)) {
                    // 从右向左滑 = 后一张（后续场景）；从左向右滑 = 前一张（已读过的场景）
                    switchReadAiImage(if (dx > 0) -1 else 1)
                    moved = true
                }
                // 从下向上滑动：退出图片界面，正文恢复整屏
                if (dy < -50 && abs(dy) > abs(dx)) {
                    exitReadAiImageBar()
                    moved = true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                // 发生滑动则不弹菜单
                if (moved) return
                showReadAiImageMenu()
            }
        })
        iv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
            }
            gesture.onTouchEvent(event)
            true
        }
    }

    /** 当前显示的图片文件（历史浏览中的优先） */
    private fun currentReadAiImageFile(): File? {
        return readAiImages.getOrNull(readAiImageIndex)
            ?: AiImageGenerator.getCurrentDisplayImage()
            ?: lastReadAiImagePath.takeIf { it.isNotBlank() }
                ?.let { File(it) }?.takeIf { it.exists() }
    }

    /** 长按图片弹出的菜单 */
    private fun showReadAiImageMenu() {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        items.add("图片宽度设置")
        actions.add { showReadAiImageWidthSetting() }
        items.add("半屏字号减小（当前 ${AppConfig.readAloudImageInReadPageTextSizeReduce}sp）")
        actions.add { showReadAiImageTextSizeReduceSetting() }
        items.add("退出图片界面")
        actions.add { exitReadAiImageBar() }
        if (AppConfig.readAloudAiImage) {
            items.add("保存当前图片")
            actions.add { saveReadAiImage(currentReadAiImageFile()) }
            items.add("保存全部图片")
            actions.add { saveAllReadAiImages() }
        }
        AlertDialog.Builder(this)
            .setTitle("图片设置")
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 快捷设置阅读界面图片宽度 */
    private fun showReadAiImageWidthSetting() {
        val editText = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(AppConfig.readAloudImageInReadPageWidth.toString())
            hint = "宽度（dp），范围 80~600"
        }
        AlertDialog.Builder(this)
            .setTitle("阅读界面图片宽度（dp）")
            .setView(editText)
            .setPositiveButton("确定") { _, _ ->
                val width = editText.text.toString().toIntOrNull()?.coerceIn(80, 600) ?: 240
                AppConfig.readAloudImageInReadPageWidth = width
                upReadAiImageBar()
                toastOnUi("图片宽度已设为 ${width}dp")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存单张图片到 /Download/AI生图/ */
    private fun saveReadAiImage(file: File?) {
        if (file == null || !file.exists()) {
            toastOnUi("图片不存在")
            return
        }
        val targetDir = File("/storage/emulated/0/Download/AI生图/")
        if (!targetDir.exists()) targetDir.mkdirs()
        val target = File(targetDir, "${file.lastModified()}.png")
        try {
            file.copyTo(target, overwrite = true)
            toastOnUi("已保存到 ${target.absolutePath}")
        } catch (e: Exception) {
            toastOnUi("保存失败：${e.localizedMessage}")
        }
    }

    /** 保存全部历史图片 */
    private fun saveAllReadAiImages() {
        val allImages = AiImageGenerator.getDisplayGallery()
        if (allImages.isEmpty()) {
            toastOnUi("暂无 AI 生图可保存")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("保存所有 AI 生图")
            .setMessage("将保存 ${allImages.size} 张历史图片到 /storage/emulated/0/Download/AI生图/，是否继续？")
            .setPositiveButton("保存全部") { _, _ ->
                var count = 0
                allImages.forEach { if (it.exists()) { saveReadAiImage(it); count++ } }
                toastOnUi("已保存 $count 张")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toInt() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    /**
     * 重置黑屏时间
     */
    override fun screenOffTimerStart() {
        handler.post {
            if (screenTimeOut < 0) {
                keepScreenOn(true)
                return@post
            }
            val t = screenTimeOut - sysScreenOffTime
            if (t > 0) {
                keepScreenOn(true)
                handler.removeCallbacks(screenOffRunnable)
                handler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepScreenOn(false)
            }
        }
    }

    companion object {
        const val RESULT_DELETED = 100
    }

}
