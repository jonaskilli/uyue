package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PageAnim.scrollPageAnim
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecord
import io.legado.app.help.AppWebDav
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isPdf
import io.legado.app.help.book.isSameNameAuthor
import io.legado.app.help.book.readSimulating
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.globalExecutor
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.webBook.WebBook
import io.legado.app.service.AiChapterFixService
import io.legado.app.service.BaseReadAloudService
import io.legado.app.service.CacheBookService
import io.legado.app.ui.book.read.page.AudioCacheIndicatorManager
import io.legado.app.ui.book.read.page.DialogRoleManager
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.utils.FileUtils
import io.legado.app.utils.postEvent
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min


@Suppress("MemberVisibilityCanBePrivate")
object ReadBook : CoroutineScope by MainScope() {
    var book: Book? = null
    var callBack: CallBack? = null
    var inBookshelf = false
    var chapterSize = 0
    var simulatedChapterSize = 0
    var durChapterIndex = 0
    var durChapterPos = 0
    var isLocalBook = true
    var chapterChanged = false
    var prevTextChapter: TextChapter? = null
    var curTextChapter: TextChapter? = null
    var nextTextChapter: TextChapter? = null
    var bookSource: BookSource? = null
    var msg: String? = null
    private val loadingChapters = arrayListOf<Int>()
    private val readRecord = ReadRecord()
    private val chapterLoadingJobs = ConcurrentHashMap<Int, Coroutine<*>>()
    private val prevChapterLoadingLock = Mutex()
    private val curChapterLoadingLock = Mutex()
    private val nextChapterLoadingLock = Mutex()
    var readStartTime: Long = System.currentTimeMillis()

    /* TTS 文本 JSON 上次保存状态（含净化开关，勾选变化要重新净化写入 data.json） */
    private var lastSavedBookName: String? = null
    private var lastSavedChapterIndex: Int = -1
    private var lastSavedFixQuote: Boolean? = null
    private var lastSavedReSegment: Boolean? = null

    /* 跳转进度前进度记录 */
    var lastBookProgress: BookProgress? = null

    /* web端阅读进度记录 */
    var webBookProgress: BookProgress? = null

    var preDownloadTask: Job? = null
    val downloadedChapters = hashSetOf<Int>()
    val downloadFailChapters = hashMapOf<Int, Int>()
    var contentProcessor: ContentProcessor? = null
    val downloadScope = CoroutineScope(SupervisorJob() + IO)
    val preDownloadSemaphore = Semaphore(2)
    val executor = globalExecutor

    fun resetData(book: Book) {
        // 换书：旧书没跑完的自动分析立即作废，避免切章后还往旧书正文里写回
        ReadBook.book?.bookUrl
            ?.takeIf { it.isNotBlank() && it != book.bookUrl }
            ?.let { AiChapterFixService.cancelForBook(it) }
        ReadBook.book = book
        readRecord.bookName = book.name
        readRecord.readTime = appDb.readRecordDao.getReadTime(book.name) ?: 0
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        contentProcessor = ContentProcessor.get(book)
        durChapterIndex = book.durChapterIndex
        durChapterPos = book.durChapterPos
        isLocalBook = book.isLocal
        clearTextChapter()
        callBack?.upContent()
        callBack?.upMenuView()
        callBack?.upPageAnim()
        upWebBook(book)
        lastBookProgress = null
        webBookProgress = null
        TextFile.clear()
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upData(book: Book) {
        ReadBook.book = book
        chapterSize = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        simulatedChapterSize = if (book.readSimulating()) {
            book.simulatedTotalChapterNum()
        } else {
            chapterSize
        }
        if (durChapterIndex != book.durChapterIndex) {
            durChapterIndex = book.durChapterIndex
            durChapterPos = book.durChapterPos
            clearTextChapter()
        }
        if (curTextChapter?.isCompleted == false) {
            curTextChapter = null
        }
        if (nextTextChapter?.isCompleted == false) {
            nextTextChapter = null
        }
        if (prevTextChapter?.isCompleted == false) {
            prevTextChapter = null
        }
        callBack?.upMenuView()
        upWebBook(book)
        synchronized(this) {
            loadingChapters.clear()
            downloadedChapters.clear()
            downloadFailChapters.clear()
        }
    }

    fun upWebBook(book: Book) {
        if (book.isLocal) {
            bookSource = null
            if (book.getImageStyle().isNullOrBlank() && (book.isImage || book.isPdf)) {
                book.setImageStyle(Book.imgStyleFull)
            }
        } else {
            appDb.bookSourceDao.getBookSource(book.origin)?.let {
                bookSource = it
                SourceCallBack.callBackBook(SourceCallBack.START_READ, it, book, curTextChapter?.chapter)
                if (book.getImageStyle().isNullOrBlank()) {
                    var imageStyle = it.getContentRule().imageStyle
                    if (imageStyle.isNullOrBlank() && (book.isImage || book.isPdf)) {
                        imageStyle = Book.imgStyleFull
                    }
                    book.setImageStyle(imageStyle)
                    if (imageStyle.equals(Book.imgStyleSingle, true)) {
                        book.setPageAnim(0)
                    }
                }
            } ?: let {
                bookSource = null
            }
        }
    }

    fun upReadBookConfig(book: Book) {
        val oldIndex = ReadBookConfig.styleSelect
        ReadBookConfig.isComic = book.isImage
        if (oldIndex != ReadBookConfig.styleSelect) {
            postEvent(EventBus.UP_CONFIG, arrayListOf(1, 2, 5))
            if (AppConfig.readBarStyleFollowPage) {
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        }
    }

    fun setProgress(progress: BookProgress) {
        if (progress.durChapterIndex < chapterSize &&
            (durChapterIndex != progress.durChapterIndex
                    || durChapterPos != progress.durChapterPos)
        ) {
            durChapterIndex = progress.durChapterIndex
            durChapterPos = progress.durChapterPos
            rescheduleAiChapterFix()
            saveRead()
            clearTextChapter()
            callBack?.upContent()
            loadContent(resetPageOffset = true)
        }
    }

    //暂时保存跳转前进度
    fun saveCurrentBookProgress() {
        if (lastBookProgress != null) return //避免进度条连续跳转不能覆盖最初的进度记录
        lastBookProgress = book?.let { BookProgress(it) }
    }

    //恢复跳转前进度
    fun restoreLastBookProgress() {
        lastBookProgress?.let {
            setProgress(it)
            lastBookProgress = null
        }
    }

    fun clearTextChapter() {
        clearExpiredChapterLoadingJob(true)
        prevTextChapter = null
        curTextChapter = null
        nextTextChapter = null
    }

    /**
     * 章节正文被外部改写后（例如 AI 处理章节），
     * 丢弃该章已经排好版的旧内容，重新从缓存里读一遍，
     * 否则界面会一直显示修改前的正文，要重启 APP 才能看到改动。
     *
     * 只处理当前章和前后一章（阅读器只缓存这三章）。
     */
    fun reloadChapter(index: Int) {
        if (book == null) return
        if (index !in durChapterIndex - 1..durChapterIndex + 1) return
        // 取消该章可能仍在跑的旧加载：否则 addLoading 会把新的加载直接挡掉
        chapterLoadingJobs.remove(index)?.cancel()
        removeLoading(index)
        when (index) {
            durChapterIndex -> {
                // 正在显示的章节：重新排版，保持阅读进度
                loadContent(index, upContent = true, resetPageOffset = false)
            }

            durChapterIndex + 1 -> {
                nextTextChapter = null
                loadContent(index, upContent = false)
            }

            else -> {
                prevTextChapter = null
                loadContent(index, upContent = false)
            }
        }
    }

    /**
     * 外部清空 AI 缓存后重挂当前章标注（11-28）。
     *
     * 目录页「删除全部AI缓存」/单章删除、朗读设置的「删除本章角色缓存」删的是
     * 磁盘 json，而标注是挂在已排版 TextChapter 的行对象上的。清空后若不重挂，
     * 旧标注会一直留在屏上，要等切章/重启（重排版）才消失——即用户报的
     * 「清空后正文标注不会立马消失」。
     *
     * [loadRoleAnnotations] 内部会 clearChapterCache 再 attachAnnotations；
     * attachAnnotations 在缓存为空时清空各行标注，故这里直接复用。
     */
    fun reloadRoleAnnotations() {
        loadRoleAnnotations(curTextChapter)
    }

    /**
     * 异步加载指定章节的角色标注，并在完成后刷新视图。
     * 切换章节时调用，可确保读取到 AI 最新保存的数据。
     */
    private fun loadRoleAnnotations(textChapter: TextChapter?) {
        if (!AppConfig.showRoleAnnotation) return
        textChapter ?: return
        val bookName = book?.name ?: return
        // 11-21 20:3x：缓存目录观察器随书挂载（切书自动挪目标目录）。
        // 内部判 watchedBook 同目录直接返回，幂等零开销；观察器常驻期间
        // 朗读脚本/分析服务写盘即触发防抖重挂（即时显示标注）。
        DialogRoleManager.watchBook(bookName)
        Coroutine.async(this) {
            DialogRoleManager.clearChapterCache(bookName, textChapter.title)
            DialogRoleManager.attachAnnotations(textChapter, bookName)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                callBack?.upContent()
            }
        }
    }

    /**
     * 异步扫描当前章节的音频缓存状态，并刷新视图。
     */
    fun loadAudioCacheIndicators(textChapter: TextChapter?) {
        // 11-21：开关关闭时清掉已画的标记再刷新视图——此前直接 return，
        // 关开关后旧标记残留到下次翻页（用户关了开关图标还在）。
        // 开关路径（READ_ALOUD_AUDIO_CACHE_REFRESH 事件）开着时会走完整重扫。
        if (!AppConfig.showAudioCacheIndicator) {
            textChapter?.pages?.forEach { page ->
                page.lines.forEach { line ->
                    line.hasAudioCache = false
                }
            }
            callBack?.upContent()
            return
        }
        textChapter ?: return
        Coroutine.async(this) {
            AudioCacheIndicatorManager.scanChapter(textChapter)
            textChapter.pages.forEach { page ->
                page.lines.forEach { line ->
                    if (line.paragraphNum > 0) {
                        // 不再依赖 audioParagraphIndex 判断首行；
                        // 只要有 paragraphNum 就查缓存，绘制时再用 indentSize 限制只画首行。
                        line.audioParagraphIndex = line.paragraphNum
                        line.hasAudioCache = AudioCacheIndicatorManager.hasCache(line.paragraphNum)
                    }
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                callBack?.upContent()
            }
        }
    }

    fun clearSearchResult() {
        curTextChapter?.clearSearchResult()
        prevTextChapter?.clearSearchResult()
        nextTextChapter?.clearSearchResult()
    }

    fun uploadProgress(toast: Boolean = false, successAction: (() -> Unit)? = null) {
        book?.let {
            launch(IO) {
                AppWebDav.uploadBookProgress(it, toast) {
                    successAction?.invoke()
                }
                ensureActive()
                it.update()
            }
        }
    }

    /**
     * 同步阅读进度
     * 如果当前进度快于服务器进度或者没有进度进行上传，如果慢与服务器进度则执行传入动作
     */
    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onError {
            AppLog.put("拉取阅读进度失败", it)
        }.onSuccess { progress ->
            // 云端进度是本机自己之前传的 → 用户主动退回重看章节，属正常行为。
            // 既不弹窗打断，也不拿云端旧位置覆盖用户当前选择；以本地为准。
            if (progress != null && progress.isFromSameDevice()) {
                return@onSuccess
            }
            if (progress == null || progress.durChapterIndex < book.durChapterIndex ||
                (progress.durChapterIndex == book.durChapterIndex
                        && progress.durChapterPos < book.durChapterPos)
            ) {
                // 服务器没有进度或者进度比服务器快，上传现有进度
                Coroutine.async {
                    AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                    book.update()
                }
            } else if (progress.durChapterIndex > book.durChapterIndex ||
                progress.durChapterPos > book.durChapterPos
            ) {
                // 进度比服务器慢，执行传入动作
                newProgressAction?.invoke(progress)
            } else {
                syncSuccessAction?.invoke()
            }
        }
    }

    fun upReadTime() {
        if (!AppConfig.enableReadRecord) {
            return
        }
        executor.execute {
            readRecord.readTime = readRecord.readTime + System.currentTimeMillis() - readStartTime
            readStartTime = System.currentTimeMillis()
            readRecord.lastRead = System.currentTimeMillis()
            appDb.readRecordDao.insert(readRecord)
        }
    }

    fun upMsg(msg: String?) {
        if (ReadBook.msg != msg) {
            ReadBook.msg = msg
            callBack?.upContent()
        }
    }

    fun moveToNextPage(): Boolean {
        var hasNextPage = false
        curTextChapter?.let {
            val nextPagePos = it.getNextPageLength(durChapterPos)
            if (nextPagePos >= 0) {
                hasNextPage = true
                it.getPage(durPageIndex)?.removePageAloudSpan()
                durChapterPos = nextPagePos
                callBack?.cancelSelect()
                callBack?.upContent()
                saveRead(true)
                // 仅滚动模式跳过（同 setPageIndex）
                if (!isScroll) {
                    loadRoleAnnotations(curTextChapter)
                    loadAudioCacheIndicators(curTextChapter)
                }
            }
        }
        return hasNextPage
    }

    fun moveToPrevPage(): Boolean {
        var hasPrevPage = false
        curTextChapter?.let {
            val prevPagePos = it.getPrevPageLength(durChapterPos)
            if (prevPagePos >= 0) {
                hasPrevPage = true
                durChapterPos = prevPagePos
                callBack?.upContent()
                saveRead(true)
                // 仅滚动模式跳过（同 setPageIndex）
                if (!isScroll) {
                    loadRoleAnnotations(curTextChapter)
                    loadAudioCacheIndicators(curTextChapter)
                }
            }
        }
        return hasPrevPage
    }

    /**
     * 章节切换的统一钩子：先停掉上一章预热出来、还没跑完的自动分析（不写回、不落盘），
     * 再按新章号重新排一轮。手动翻页、进度条/目录跳章、朗读自动切章都会经过这里。
     * 调度在单例服务里完成，锁屏、关屏、阅读界面销毁都不受影响。
     */
    private fun rescheduleAiChapterFix() {
        AiChapterFixService.onChapterChanged()    }

    fun moveToNextChapter(upContent: Boolean, upContentInPlace: Boolean = true): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            rescheduleAiChapterFix()
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContent()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            // 切章补全：用「离开的那一章」（durChapterIndex-1）的正文与姓名补档案
            AiProfileService.onChapterSwitched(book?.name.orEmpty(), durChapterIndex - 1)
            loadRoleAnnotations(curTextChapter)
            loadAudioCacheIndicators(curTextChapter)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    suspend fun moveToNextChapterAwait(
        upContent: Boolean,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndex < simulatedChapterSize - 1) {
            durChapterPos = 0
            durChapterIndex++
            rescheduleAiChapterFix()
            clearExpiredChapterLoadingJob()
            prevTextChapter = curTextChapter
            curTextChapter = nextTextChapter
            nextTextChapter = null
            if (curTextChapter == null) {
                AppLog.putDebug("moveToNextChapter-章节未加载,开始加载")
                if (upContentInPlace) callBack?.upContentAwait()
                loadContentAwait(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                AppLog.putDebug("moveToNextChapter-章节已加载,刷新视图")
                callBack?.upContentAwait()
            }
            loadContent(durChapterIndex.plus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            AppLog.putDebug("moveToNextChapter-curPageChanged()")
            curPageChanged()
            // 切章补全：用「离开的那一章」（durChapterIndex-1）的正文与姓名补档案
            AiProfileService.onChapterSwitched(book?.name.orEmpty(), durChapterIndex - 1)
            loadRoleAnnotations(curTextChapter)
            loadAudioCacheIndicators(curTextChapter)
            return true
        } else {
            AppLog.putDebug("跳转下一章失败,没有下一章")
            return false
        }
    }

    fun moveToPrevChapter(
        upContent: Boolean,
        toLast: Boolean = true,
        upContentInPlace: Boolean = true
    ): Boolean {
        if (durChapterIndex > 0) {
            // 11-20 尾巴页规则2：向前跨章 = 从后往前到达。落点由 toLast 决定：
            // toLast=true 落上一章末尾（章末半截段，须整读段首，不许被规则1跳章），
            // 置一次性标志让服务端 newReadAloud 放行；toLast=false 落章头，不涉及。
            // 服务未运行（instance=null）时标志无人消费，无害。
            if (toLast) {
                BaseReadAloudService.instance?.markReverseTailArrival()
            }
            durChapterPos = if (toLast) prevTextChapter?.lastReadLength ?: Int.MAX_VALUE else 0
            durChapterIndex--
            rescheduleAiChapterFix()
            clearExpiredChapterLoadingJob()
            nextTextChapter = curTextChapter
            curTextChapter = prevTextChapter
            prevTextChapter = null
            if (curTextChapter == null) {
                if (upContentInPlace) callBack?.upContent()
                loadContent(durChapterIndex, upContent, resetPageOffset = false)
            } else if (upContent && upContentInPlace) {
                callBack?.upContent()
            }
            loadContent(durChapterIndex.minus(1), upContent, false)
            saveRead()
            callBack?.upMenuView()
            curPageChanged()
            // 切章补全：用「离开的那一章」（本次是往后翻，即 index+1）的正文与姓名补档案
            AiProfileService.onChapterSwitched(book?.name.orEmpty(), durChapterIndex + 1)
            loadRoleAnnotations(curTextChapter)
            loadAudioCacheIndicators(curTextChapter)
            return true
        } else {
            return false
        }
    }

    fun skipToPage(index: Int, success: (() -> Unit)? = null) {
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        callBack?.upContent {
            success?.invoke()
        }
        curPageChanged()
        saveRead(true)
        loadRoleAnnotations(curTextChapter)
        loadAudioCacheIndicators(curTextChapter)
    }

    fun setPageIndex(index: Int) {
        recycleRecorders(durPageIndex, index)
        durChapterPos = curTextChapter?.getReadLength(index) ?: index
        saveRead(true)
        curPageChanged(true)
        // 仅滚动模式跳过：fling 连续翻页时重复扫描(listFiles)+重绑(遍历行列)
        // 会触发 upContent 重绘导致掉帧，进而 fling 跳过多页。
        // 其他模式为离散翻页，需正常刷新姓名/小喇叭。
        if (!isScroll) {
            loadRoleAnnotations(curTextChapter)
            loadAudioCacheIndicators(curTextChapter)
        }
    }

    fun recycleRecorders(beforeIndex: Int, afterIndex: Int) {
        if (!AppConfig.optimizeRender) {
            return
        }
        executor.execute {
            val textChapter = curTextChapter ?: return@execute
            if (afterIndex > beforeIndex) {
                textChapter.getPage(afterIndex - 2)?.recycleRecorders()
            }
            if (afterIndex < beforeIndex) {
                textChapter.getPage(afterIndex + 3)?.recycleRecorders()
            }
        }
    }

    fun openChapter(
        index: Int,
        durChapterPos: Int = 0,
        upContent: Boolean = true,
        success: (() -> Unit)? = null
    ) {
        if (index < chapterSize) {
            clearTextChapter()
            if (upContent) callBack?.upContent()
            durChapterIndex = index
            rescheduleAiChapterFix()
            ReadBook.durChapterPos = durChapterPos
            saveRead()
            loadContent(resetPageOffset = true) {
                success?.invoke()
            }
        }
    }

    /**
     * 一次性标记：**抑制下一次「翻页跟随」重定位**。
     *
     * 场景（11-14 晚，方案B 的自我触发）：点朗读时服务端已经把落点定在「该页第一段的
     * 段首」；若这个段首在**上一页**（半截段），syncPageToReadAloudNumber 会把页面翻过去，
     * 而翻页本身会触发 curPageChanged → readAloud(fromPageTurn=true)，反过来按
     * 「新页的页首」重新定位——落点从「段25 段首」被改回「段24 的后半截」，
     * 方案B 白翻一趟还念错了段。
     *
     * 故方案B 翻页前把它置 true，curPageChanged 消费一次即清（**无论走哪条分支都清**，
     * 避免残留成「某次手动翻页不重新定位」）。
     *
     * 手动翻页、自动朗读翻页从不设置它，行为完全不变——连续翻页照样一页页往下走。
     */
    var suppressNextPageTurnReadAloud = false

    /**
     * 当前页面变化
     */
    private fun curPageChanged(pageChanged: Boolean = false) {
        // 先消费一次性标记：下面无论走哪个分支都必须清掉，否则残留会让
        // 之后某次真正的翻页跟随被误跳过（例如滚动模式走的是 pause 分支）。
        val suppressReadAloud = suppressNextPageTurnReadAloud
        suppressNextPageTurnReadAloud = false
        callBack?.pageChanged()
        curTextChapter?.let {
            if (BaseReadAloudService.isRun && it.isCompleted) {
                val scrollPageAnim = pageAnim() == 3
                if (scrollPageAnim && pageChanged) {
                    ReadAloud.pause(appCtx)
                } else if (suppressReadAloud) {
                    // 这次翻页是「点朗读后为对齐段首」自己翻的：落点已由服务端定在
                    // 段首，不能再按新页页首重新定位（那样会从上一段的尾巴念起）。
                    AppLog.putDebug("翻页跟随抑制: 方案B对齐段首页的这次翻页，跳过重定位")
                } else {
                    // 翻页跟随：落点段还没读过，必须从段首照读（不再跳过当前段）
                    readAloud(!BaseReadAloudService.pause, fromPageTurn = true)
                }
            }
        }
        upReadTime()
        preDownload()
    }

    /**
     * 朗读
     *
     * @param fromPageTurn 是否由「翻页跟随」触发（curPageChanged）。
     *   翻页时落点段尚未朗读，服务端不应按「接着上次听」的语义跳过它。
     */
    fun readAloud(
        play: Boolean = true,
        startPos: Int = 0,
        fromPageTurn: Boolean = false,
        pageIndex: Int? = null,
        startParagraph: Int = 0
    ) {
        book ?: return
        val textChapter = curTextChapter ?: return
        if (textChapter.isCompleted) {
            ReadAloud.play(
                appCtx,
                play,
                pageIndex = pageIndex ?: durPageIndex,
                startPos = startPos,
                fromPageTurn = fromPageTurn,
                startParagraph = startParagraph
            )
        }
    }

    /**
     * 当前页数
     */
    val durPageIndex: Int
        get() {
            return curTextChapter?.getPageIndexByCharIndex(durChapterPos) ?: durChapterPos
        }

    /**
     * 是否排版到了当前阅读位置
     */
    val isLayoutAvailable inline get() = durPageIndex >= 0

    val isScroll inline get() = pageAnim() == scrollPageAnim

    val contentLoadFinish get() = curTextChapter != null || msg != null

    /**
     * chapterOnDur: 0为当前页,1为下一页,-1为上一页
     */
    fun textChapter(chapterOnDur: Int = 0): TextChapter? {
        return when (chapterOnDur) {
            0 -> curTextChapter
            1 -> nextTextChapter
            -1 -> prevTextChapter
            else -> null
        }
    }

    /**
     * 加载当前章节和前后一章内容
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 当前章节加载完成回调
     */
    fun loadContent(
        resetPageOffset: Boolean,
        success: (() -> Unit)? = null
    ) {
        loadContent(durChapterIndex, resetPageOffset = resetPageOffset) {
            success?.invoke()
        }
        loadContent(durChapterIndex + 1, resetPageOffset = resetPageOffset)
        loadContent(durChapterIndex - 1, resetPageOffset = resetPageOffset)
    }

    fun loadOrUpContent() {
        if (curTextChapter == null) {
            loadContent(durChapterIndex)
        } else {
            callBack?.upContent()
            // 11-20（角色标注补挂）：同一本书重新打开且章节已排好版时，不会走
            // loadContent ⇒ contentLoadFinish 里的 loadRoleAnnotations 不触发。
            // 而角色缓存是朗读过程中由脚本逐段写盘的——读的那一章在「上次排版」
            // 之后又有了新缓存，重进却什么都不挂，用户看到的就是「退出重进没有
            // 标注，要重启才有」（重启会清 TextChapter 强制重排版，挂载才跑）。
            // 这里排版复用，但标注挂载是独立于排版的轻量操作（读一个小 json +
            // 遍历行列），补挂一次即可，与 538a625 整页绘制的兼容性无关。
            loadRoleAnnotations(curTextChapter)
            // 同一本书重新打开（upData 路径）且内容已就绪时，不会走 loadContent，
            // 因此这里主动通知内容加载完成，确保 ReadBookActivity.contentLoadFinish()
            // 能消费 readAloud 等 extra
            callBack?.contentLoadFinish()
        }
        if (nextTextChapter == null) {
            loadContent(durChapterIndex + 1)
        }
        if (prevTextChapter == null) {
            loadContent(durChapterIndex - 1)
        }
    }

    /**
     * 加载章节内容
     * @param index 章节序号
     * @param upContent 是否更新视图
     * @param resetPageOffset 滚动阅读是否重置滚动位置
     * @param success 加载完成回调
     */
    fun loadContent(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        Coroutine.async {
            val book = book!!
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return@async
            if (addLoading(index)) {
                BookHelp.getContent(book, chapter)?.let {
                    contentLoadFinish(
                        book,
                        chapter,
                        it,
                        upContent,
                        resetPageOffset,
                        success = success
                    )
                } ?: download(
                    downloadScope,
                    chapter,
                    resetPageOffset
                )
            }
        }.onError {
            AppLog.put("加载正文出错\n${it.localizedMessage}")
        }
    }

    suspend fun loadContentAwait(
        index: Int,
        upContent: Boolean = true,
        resetPageOffset: Boolean = false,
        success: (() -> Unit)? = null
    ) = withContext(IO) {
        if (addLoading(index)) {
            try {
                val book = book!!
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index)!!
                val content = BookHelp.getContent(book, chapter) ?: downloadAwait(chapter)
                contentLoadFinishAwait(book, chapter, content, upContent, resetPageOffset)
                success?.invoke()
            } catch (e: Exception) {
                AppLog.put("加载正文出错\n${e.localizedMessage}")
            } finally {
                removeLoading(index)
            }
        }
    }

    /**
     * 下载正文
     */
    private suspend fun downloadIndex(index: Int) {
        if (index < 0) return
        if (index > chapterSize - 1) {
            upToc()
            return
        }
        val book = book ?: return
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: return
        if (BookHelp.hasContent(book, chapter)) {
            downloadedChapters.add(chapter.index)
        } else {
            delay(1000)
            if (addLoading(index)) {
                download(downloadScope, chapter, false, preDownloadSemaphore)
            }
        }
    }

    /**
     * 下载正文
     */
    private fun download(
        scope: CoroutineScope,
        chapter: BookChapter,
        resetPageOffset: Boolean,
        semaphore: Semaphore? = null,
        success: (() -> Unit)? = null
    ) {
        val book = book ?: return removeLoading(chapter.index)
        val bookSource = bookSource
        if (bookSource != null) {
            CacheBook.getOrCreate(bookSource, book).download(scope, chapter, semaphore)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            contentLoadFinish(
                book,
                chapter,
                "加载正文失败\n$msg",
                resetPageOffset = resetPageOffset,
                success = success
            )
        }
    }

    private suspend fun downloadAwait(chapter: BookChapter): String {
        val book = book!!
        val bookSource = bookSource
        if (bookSource != null) {
            return CacheBook.getOrCreate(bookSource, book).downloadAwait(chapter)
        } else {
            val msg = if (book.isLocal) "无内容" else "没有书源"
            return "加载正文失败\n$msg"
        }
    }

    @Synchronized
    private fun addLoading(index: Int): Boolean {
        if (loadingChapters.contains(index)) return false
        loadingChapters.add(index)
        return true
    }

    @Synchronized
    fun removeLoading(index: Int) {
        loadingChapters.remove(index)
    }

    /**
     * 内容加载完成
     */
    @Synchronized
    fun contentLoadFinish(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean,
        canceled: Boolean = false,
        success: (() -> Unit)? = null
    ) {
        removeLoading(chapter.index)
        if (canceled || chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        chapterLoadingJobs[chapter.index]?.cancel()
        val job = Coroutine.async(this, start = CoroutineStart.LAZY) {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule()
            )
            val contents = contentProcessor
                .getContent(book, chapter, content, includeTitle = false)
            ensureActive()
            val textChapter = ChapterProvider.getTextChapterAsync(
                this, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> curChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        curTextChapter = textChapter
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    callBack?.contentLoadFinish()
                    loadRoleAnnotations(textChapter)
                    loadAudioCacheIndicators(textChapter)
                }

                -1 -> prevChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        prevTextChapter = textChapter
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> nextChapterLoadingLock.withLock {
                    withContext(Main) {
                        ensureActive()
                        nextTextChapter = textChapter
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                }
            }

            return@async
        }.onError {
            if (it is CancellationException) {
                return@onError
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }.onSuccess {
            success?.invoke()
            // 内容真正加载完成后要重落盘 TTS JSON：正文可能已被 AI 处理章节改写，
            // 不能靠节流变量判断是否需要重写。
            // 换书时当前+前+后三章都会回调这里，「逐章读缓存 + 正则 + 写文件」这段
            // 同步 IO 原本会在主线程跑三遍，是最容易卡住返回手势的一段。
            if (chapter.index == durChapterIndex) {
                // 当前章：立即写一次，保证朗读脚本读到最新窗口
                saveTtsTextJson(force = true)
            } else {
                // 预加载章：主线程只让节流失效（极轻量），后续 saveRead() 在后台线程、
                // 或切章/开始朗读时的 force 落盘会把完整窗口补齐，不丢后续章节内容
                invalidateTtsTextJson()
            }
        }
        chapterLoadingJobs[chapter.index] = job
        job.start()
    }

    suspend fun contentLoadFinishAwait(
        book: Book,
        chapter: BookChapter,
        content: String,
        upContent: Boolean = true,
        resetPageOffset: Boolean
    ) {
        removeLoading(chapter.index)
        if (chapter.index !in durChapterIndex - 1..durChapterIndex + 1) {
            return
        }
        kotlin.runCatching {
            val contentProcessor = ContentProcessor.get(book.name, book.origin)
            val displayTitle = chapter.getDisplayTitle(
                contentProcessor.getTitleReplaceRules(),
                book.getUseReplaceRule()
            )
            val contents = contentProcessor
                .getContent(book, chapter, content, includeTitle = false)
            val textChapter = ChapterProvider.getTextChapterAsync(
                this@ReadBook, book, chapter, displayTitle, contents, simulatedChapterSize
            )
            when (val offset = chapter.index - durChapterIndex) {
                0 -> {
                    curTextChapter?.cancelLayout()
                    withContext(Main) {
                        curTextChapter = textChapter
                    }
                    callBack?.upMenuView()
                    var available = false
                    for (page in textChapter.layoutChannel) {
                        val index = page.index
                        if (!available && page.containPos(durChapterPos)) {
                            if (upContent) {
                                callBack?.upContent(offset, resetPageOffset)
                            }
                            available = true
                        }
                        if (upContent && isScroll) {
                            if (max(index - 3, 0) < durPageIndex) {
                                callBack?.upContent(offset, false)
                            }
                        }
                        callBack?.onLayoutPageCompleted(index, page)
                    }
                    if (upContent) callBack?.upContent(offset, !available && resetPageOffset)
                    curPageChanged()
                    callBack?.contentLoadFinish()
                }

                -1 -> {
                    prevTextChapter?.cancelLayout()
                    withContext(Main) {
                        prevTextChapter = textChapter
                    }
                    textChapter.layoutChannel.receiveAsFlow().collect()
                    if (upContent) callBack?.upContent(offset, resetPageOffset)
                }

                1 -> {
                    nextTextChapter?.cancelLayout()
                    withContext(Main) {
                        nextTextChapter = textChapter
                    }
                    for (page in textChapter.layoutChannel) {
                        if (page.index > 1) {
                            continue
                        }
                        if (upContent) callBack?.upContent(offset, resetPageOffset)
                    }
                }
            }
        }.onFailure {
            if (it is CancellationException) {
                return@onFailure
            }
            AppLog.put("ChapterProvider ERROR", it)
            appCtx.toastOnUi("ChapterProvider ERROR:\n${it.stackTraceStr}")
        }
    }

    @Synchronized
    fun upToc() {
        val bookSource = bookSource ?: return
        val book = book ?: return
        if (!book.canUpdate) return
        if (System.currentTimeMillis() - book.lastCheckTime < 600000) return
        book.lastCheckTime = System.currentTimeMillis()
        WebBook.getChapterList(this, bookSource, book).onSuccess(IO) { cList ->
            if (book.bookUrl == ReadBook.book?.bookUrl
                && cList.size > chapterSize
            ) {
                appDb.bookChapterDao.delByBook(book.bookUrl)
                appDb.bookChapterDao.insert(*cList.toTypedArray())
                saveRead()
                chapterSize = cList.size
                simulatedChapterSize = book.simulatedTotalChapterNum()
                nextTextChapter ?: loadContent(durChapterIndex + 1)
            }
        }
    }

    fun pageAnim(): Int {
        return book?.getPageAnim() ?: ReadBookConfig.pageAnim
    }

    fun setCharset(charset: String) {
        book?.let {
            it.charset = charset
            callBack?.loadChapterList(it)
        }
        saveRead()
    }

    fun saveRead(pageChanged: Boolean = false) {
        val book = book ?: return
        executor.execute {
            kotlin.runCatching {
                book.lastCheckCount = 0
                book.durChapterTime = System.currentTimeMillis()
                val chapterChanged = book.durChapterIndex != durChapterIndex
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durChapterPos
                if (!pageChanged || chapterChanged) {
                    appDb.bookChapterDao.getChapter(book.bookUrl, durChapterIndex)?.let {
                        book.durChapterTitle = it.getDisplayTitle(
                            ContentProcessor.get(book.name, book.origin).getTitleReplaceRules(),
                            book.getUseReplaceRule()
                        )
                        SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, bookSource, book, it)
                    }
                }
                book.update()
                // 保存 TTS 文本 JSON（变化时自动写入）
                saveTtsTextJson()
            }.onFailure {
                AppLog.put("保存书籍阅读进度信息出错\n$it", it)
            }
        }
    }

    /**
     * 外部改写正文后调用：让下一次 data.json 保存不再被节流跳过。
     * AI 处理章节改写的是缓存正文，不会改变书名/章号/净化开关这几个节流变量，
     * 不失效的话切章、保存进度等处的无 force 调用会直接 return，data.json 一直停在旧文本。
     */
    fun invalidateTtsTextJson() {
        lastSavedBookName = ""
        lastSavedChapterIndex = -1
    }

    /**
     * 保存当前及后续章节文本到 JSON，供 TTS 使用
     * 只有书名或章节发生变化时才真正写入
     *
     * @param force true 时跳过"书名+章节未变"的节流，强制重新收集并写入。
     *   章节刚加载完成时后续章节常未下载，此时写入的内容不完整，
     *   但节流已被记录，导致后续章节补齐后也不会重写。
     *   开始朗读等需要确保数据完整的场景应传 true。
     */
    fun saveTtsTextJson(force: Boolean = false) {
        val book = book ?: return
        val bookName = book.name
        val chapterIndex = durChapterIndex
        val fixQuote = book.getFixQuote()
        val reSegment = book.getReSegment()
        if (!force &&
            lastSavedBookName == bookName &&
            lastSavedChapterIndex == chapterIndex &&
            lastSavedFixQuote == fixQuote &&
            lastSavedReSegment == reSegment
        ) {
            return
        }
        kotlin.runCatching {
            val limit = AppConfig.audioPreDownloadNum + 2
            // 往前多带一章：朗读脚本的「上文历史」只在 data.json 里往前截 1000 字，
            // 窗口若从当前章开始，章首就读不到上一章末尾的角色信息。
            val prevCount = if (durChapterIndex > 0) 1 else 0
            val fromIndex = durChapterIndex - prevCount
            val sb = StringBuilder()
            var chapterCount = 0
            for (i in 0 until limit + prevCount) {
                val idx = fromIndex + i
                if (idx < 0 || idx >= chapterSize) break
                val ch = appDb.bookChapterDao.getChapter(book.bookUrl, idx) ?: break
                val raw = BookHelp.getContent(book, ch)
                if (!raw.isNullOrBlank()) {
                    val clean = cleanContentForTts(book, ch, raw)
                    if (clean.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        // data.json 每章第一行统一放原始章节名：脚本按这个标题定位章节，
                        // 角色识别 json 也按原始章节名命名，三处必须是同一个名字。
                        val lines = clean.split("\n").toMutableList()
                        lines[0] = ch.title.replace(Regex("[\r\n]"), "")
                        sb.append(lines.joinToString("\n"))
                        chapterCount++
                    }
                }
            }
            // 正文尚未加载完成，跳过本次保存，下次还会重试
            if (sb.isEmpty()) {
                return@runCatching
            }
            val json = JSONObject()
            json.put("bookName", bookName)
            json.put("texts", sb.toString())
            val dir = File(io.legado.app.help.EngineDataDir.baseDir())
            dir.mkdirs()
            val success = FileUtils.writeText(
                File(dir, "data.json").absolutePath,
                json.toString()
            )
            if (success) {
                lastSavedBookName = bookName
                lastSavedChapterIndex = chapterIndex
                lastSavedFixQuote = fixQuote
                lastSavedReSegment = reSegment
                AppLog.putDebug(
                    "保存 TTS JSON 成功: $bookName 第${fromIndex}章起(当前${chapterIndex}), 实收${chapterCount}/${limit + prevCount}章"
                )
            } else {
                AppLog.put("保存 TTS JSON 失败: $bookName 第${chapterIndex}章起")
            }
        }.onFailure {
            AppLog.put("保存 TTS JSON 出错\n$it", it)
        }
    }

    /**
     * 全书分析：把「从 [startIndex] 起的上下文窗口」写入 data.json 并返回窗口文本。
     *
     * 与 [saveTtsTextJson] 的差异：不节流、不动朗读进度记录（lastSaved*），
     * 窗口起点由分析位置决定——脚本（locateParagraphInFullText 等）在分析每个
     * 章节时都能拿到"该位置"的上下文，与朗读时的行为完全一致。
     *
     * @return 窗口拼接文本（清洗后），空表示无可分析内容
     */
    fun saveTtsTextJsonFrom(startIndex: Int): String? {
        val book = book ?: return null
        return kotlin.runCatching {
            val limit = AppConfig.audioPreDownloadNum + 2
            val sb = StringBuilder()
            for (i in 0 until limit) {
                val idx = startIndex + i
                if (idx >= chapterSize) break
                val ch = appDb.bookChapterDao.getChapter(book.bookUrl, idx) ?: break
                val raw = BookHelp.getContent(book, ch)
                if (!raw.isNullOrBlank()) {
                    val clean = cleanContentForTts(book, ch, raw)
                    if (clean.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(clean)
                    }
                }
            }
            if (sb.isEmpty()) return@runCatching null
            val json = JSONObject()
            json.put("bookName", book.name)
            json.put("texts", sb.toString())
            val dir = File(io.legado.app.help.EngineDataDir.baseDir())
            dir.mkdirs()
            FileUtils.writeText(File(dir, "data.json").absolutePath, json.toString())
            sb.toString()
        }.onFailure {
            AppLog.put("全书分析保存 data.json 出错\n$it", it)
        }.getOrNull()
    }

    /**
     * 章节正文朗读口径清洗（ContentProcessor 替换净化 + 清理残留 HTML/实体/占位符）。
     * data.json 写入与全书分析逐章取文共用同一份清洗结果，保证脚本上下文一致。
     * 「双引号修复」「重新分段」按阅读界面右上角菜单的按书勾选状态显式传入，
     * 勾选时由 ContentProcessor 保证先 QuoteFix.fix 再 ContentHelp.reSegment。
     */
    fun cleanContentForTts(book: Book, ch: BookChapter, raw: String): String {
        // 先经过 ContentProcessor 替换净化、去重标题、双引号修复、重新分段、简繁转换
        val processed = ContentProcessor.get(book).getContent(
            book, ch, raw,
            fixQuote = book.getFixQuote(),
            reSegment = book.getReSegment()
        ).toString()
        // 再清理残留的 HTML 标签、图片链接、段评占位符
        return processed
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</p>"), "\n")
            // 11-14：段评注释必须**先于**通用的 `<[^>]+>` 清理。
            // 通用清理只吃掉第一个 `>` 之前的部分，若注释体里含 `>`（如
            // `<!-- 段评：他说了一句话 > 然后走了 -->`）就会残留半截，被 TTS 念出来。
            .replace(Regex("(?is)<!--.*?-->"), "")
            .replace(Regex("<[^>]+>"), "")
            // 11-14：裸图片数据——不在标签里，上面的通用清理碰不到，会整段念成乱码。
            //   · `data:image/svg+xml;base64,PHN2Zy...` 段评里最常见的形态；
            //   · 裸图片 URL（`https://.../a.png`），部分源站直接写在正文里。
            .replace(Regex("(?i)data:image/[^\\s<>\"']+"), "")
            .replace(
                Regex("(?i)\\bhttps?://[^\\s<>\"']+\\.(?:avif|bmp|gif|jpe?g|png|svg|webp)"),
                ""
            )
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&[a-zA-Z#0-9]+;"), "")
            .replace(Regex("[袮꧁]"), " ")
            // 去掉多余连续空行，但保留缩进和单换行格式
            .replace(Regex("\n[ \\t\u3000]*\n+"), "\n")
            .trim()
    }

    /**
     * 预下载
     */
    private fun preDownload() {
        if (book?.isLocal == true) return
        executor.execute {
            if (AppConfig.preDownloadNum < 2) {
                return@execute
            }
            preDownloadTask?.cancel()
            preDownloadTask = launch(IO) {
                //预下载
                launch {
                    val maxChapterIndex =
                        min(durChapterIndex + AppConfig.preDownloadNum, chapterSize)
                    for (i in durChapterIndex.plus(2)..maxChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
                launch {
                    val minChapterIndex = durChapterIndex - min(5, AppConfig.preDownloadNum)
                    for (i in durChapterIndex.minus(2) downTo minChapterIndex) {
                        if (downloadedChapters.contains(i)) continue
                        if ((downloadFailChapters[i] ?: 0) >= 3) continue
                        downloadIndex(i)
                    }
                }
            }
        }
    }

    fun cancelPreDownloadTask() {
        if (contentLoadFinish) {
            preDownloadTask?.cancel()
            downloadScope.coroutineContext.cancelChildren()
        }
    }

    fun onChapterListUpdated(newBook: Book) {
        if (newBook.isSameNameAuthor(book)) {
            book = newBook
            chapterSize = newBook.totalChapterNum
            simulatedChapterSize = newBook.simulatedTotalChapterNum()
            if (simulatedChapterSize > 0 && durChapterIndex > simulatedChapterSize - 1) {
                durChapterIndex = simulatedChapterSize - 1
            }
            if (callBack == null) {
                clearTextChapter()
            } else {
                loadContent(true)
            }
        }
    }

    private fun clearExpiredChapterLoadingJob(clearAll: Boolean = false) {
        val iterator = chapterLoadingJobs.iterator()
        while (iterator.hasNext()) {
            val (index, job) = iterator.next()
            if (clearAll || index !in durChapterIndex - 1..durChapterIndex + 1) {
                job.cancel()
                iterator.remove()
            }
        }
    }

    /**
     * 注册回调
     */
    fun register(cb: CallBack) {
        callBack?.notifyBookChanged()
        callBack = cb
    }

    /**
     * 取消注册回调
     */
    fun unregister(cb: CallBack) {
        if (callBack === cb) {
            callBack = null
        }
        msg = null
        preDownloadTask?.cancel()
        downloadScope.coroutineContext.cancelChildren()
        coroutineContext.cancelChildren()
        ImageProvider.clear()
        clearExpiredChapterLoadingJob(true)
        if (!CacheBookService.isRun) {
            CacheBook.close()
        }
    }

    interface CallBack : LayoutProgressListener {
        fun upMenuView()

        fun loadChapterList(book: Book)

        fun upContent(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        suspend fun upContentAwait(
            relativePosition: Int = 0,
            resetPageOffset: Boolean = true,
            success: (() -> Unit)? = null
        )

        fun pageChanged()

        fun contentLoadFinish()

        fun upPageAnim(upRecorder: Boolean = false)

        fun notifyBookChanged()

        fun sureNewProgress(progress: BookProgress)

        fun cancelSelect()
    }

}