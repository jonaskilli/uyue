package io.legado.app.ui.book.video

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.video.VideoConfig
import io.legado.app.help.video.VideoDownloader
import io.legado.app.help.video.VideoFeedStore
import io.legado.app.help.video.VideoSourcePlayer
import io.legado.app.help.video.VideoUrlSniffer
import io.legado.app.ui.timetech.player.LocalVideo
import io.legado.app.ui.timetech.player.NextVideoPreload
import io.legado.app.ui.timetech.player.SharedPlayer
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

data class SourceVideoPlayerUiState(
    val loading: Boolean = true,
    val bookName: String = "",
    /** 书籍与源标识，用于跳转经典播放页（首页：视频+选集方块） */
    val bookUrl: String = "",
    val sourceOrigin: String = "",
    /** 当前正在播放的集（扁平章节序号），供首页/其它入口续播 */
    val currentIndex: Int = 0,
    /** 搜索/发现入口的列表切换（右侧 20% 竖滑） */
    val feedEnabled: Boolean = false,
    /** 该视频的剧集列表是否为「倒序」播放（持久化标记，与小说「反转目录」同效） */
    val reverseOrder: Boolean = false,
    /** 书籍封面：加载/解析时铺底显示，便于快速切换时辨认视频 */
    val coverUrl: String = "",
    /** 封面所属书源 origin：用于与列表一致的图片缓存 key，避免封面重新联网拉取导致黑屏 */
    val coverOrigin: String = "",
    /**
     * 剧集列表。uri 为 [Uri.EMPTY] 表示这一集的地址还没解析出来，
     * 播放器会先显示占位，滚到/点到它时才真正去解析。
     */
    val episodes: List<LocalVideo> = emptyList(),
    val startIndex: Int = 0,
    /** 上一次看到该集的第几毫秒，用于续播 */
    val startPositionMs: Long = 0L,
    /** 书源配置的请求头，m3u8 与分片普遍要校验 UA/Referer */
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null,
    /** 播放阶段出错（ExoPlayer 报错：无法播放/播放出错/网址问题等），
     *  有列表可切（feedEnabled）时由上层自动切到下一个视频 */
    val playbackError: String? = null,
    /** 这一集解析不出直链，需要退回书源自带的播放器 */
    val fallbackIndex: Int? = null,
    /** 书源播放器已拉起，页面可以关闭了 */
    val fallbackLaunched: Boolean = false,
    /** 连书源播放器也没拉起来 */
    val fallbackFailed: String? = null,
)

/**
 * 在线剧集播放会话（全局单例）。
 *
 * 全屏播放页与首页（视频+选集方块）**共用这一个会话**：
 * 剧集列表、解析出的直链、当前集与进度只有一份记录，
 * 两边切换时不再重复解析、不再各记各的进度。
 *
 * 两类视频书源在这里被统一：
 * - 章节地址本身就是流地址（如猫豆的 m3u8 代理链接）：目录一拉下来就能播，零额外请求；
 * - 章节地址要经过正文规则才能换出流地址（如蜜桃的编码 payload）：按需解析，
 *   因为 N 集就是 N 次网络请求，一次性解析会让用户干等。
 *
 * 都拿不到直链时退回 [VideoSourcePlayer.play]，让书源拉起它自己的播放器。
 */
object SourceVideoPlayerSession {

    private val _uiState = MutableStateFlow(SourceVideoPlayerUiState())
    val uiState = _uiState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var book: Book? = null
    private var source: BookSource? = null
    private var chapters: List<BookChapter> = emptyList()
    /** 搜索/发现入口带来的视频列表（动态供给：分页加载出来的也会进入切换范围） */
    private var feed: VideoFeedStore.Feed? = null
    /** 最靠前的那一个预取结果（N+1），给播放器建缓冲用；跨线程读写（UI 取用 / IO 解析） */
    @Volatile
    private var prefetchedNext: NextVideoPreload? = null
    /**
     * 已解析出首集地址的后续视频，按 bookUrl 索引（N+1、N+2…）。
     *
     * 解析很便宜、建缓冲很贵，所以这里可以多存几部：只有最靠前那部会真正预缓冲，
     * 其余先只留地址，等它变成 N+1 时直接接着用。
     */
    private val prefetchedAhead = ConcurrentHashMap<String, NextVideoPreload>()
    /** 预取「下一个视频」的互斥锁：会话层与播放器层可能同时来要，只解析一次 */
    private val prefetchMutex = Mutex()
    /**
     * 预取结果的后台任务。
     *
     * 必须挂在会话自己的 scope 上、不挂在界面的 LaunchedEffect 里：
     * 加密源要走 WebView 嗅探，最长 20 秒以上，而那个 effect 的 key 含剧集列表，
     * 列表一更新就重启、把里面的协程取消（取消异常不写日志，完全静默）。
     */
    private var prefetchJob: Job? = null
    /**
     * 当前这一轮「加载视频」的任务。
     *
     * 解析常常要好几秒，而这期间用户就能竖滑切走；不持有它就无法取消上一轮，
     * 两轮并发结束时，慢的那一轮会把新视频的状态覆盖回旧视频（串台）。
     */
    private var loadJob: Job? = null
    /**
     * 预取地址的存活时间。
     *
     * 嗅探出来的加密直链常带一次性签名/短时效 token，隔太久再用必然 403。
     * 超时就丢弃、走正常解析重新拿，而不是一直抱着过期地址。
     */
    private const val PREFETCH_URL_TTL = 10 * 60_000L
    /**
     * 预取深度：一次解析后续几部的地址。
     *
     * 只解析不缓冲，成本是几次规则请求，换来连续下滑两部都不必现场拉目录。
     * 取 2 而不是更大：再往后用户的下一次滑动大概率已经超出预取范围，
     * 多解析只是白白给源站加压（部分源还有频控）。
     */
    private const val PREFETCH_LOOKAHEAD = 2
    /** 预取结果缓存上限：换了分类/搜索后旧的永不再命中，堆多了整体清一次 */
    private const val PREFETCH_AHEAD_MAX = 16
    /** 上一次预取**失败**的时间 */
    @Volatile
    private var prefetchFailedAt = 0L
    /**
     * 失败后的冷却时间。
     *
     * 预取失败时 [prefetchedNext] 一直是 null，而播放器每 2 秒就来要一次，
     * 会变成「每 2 秒重跑一次拉目录→正文规则→嗅探」的死循环（日志里几十条连续的
     * "预取下一个视频失败"，流量与 WebView 都白白消耗）。失败后先歇一会儿再试。
     */
    private const val PREFETCH_RETRY_COOLDOWN = 30_000L
    /** 每部视频「是否倒序」的持久化标记，按 bookUrl 存，重启后沿用（与小说反转目录同效） */
    private val reversePrefs by lazy {
        appCtx.getSharedPreferences("video_reverse_order", Context.MODE_PRIVATE)
    }

    fun isReverseOrder(bookUrl: String): Boolean = reversePrefs.getBoolean(bookUrl, false)

    /** 点「倒序」后调用：把新的倒序状态持久化，并同步进 uiState 供界面初始化沿用 */
    fun setReverseOrder(bookUrl: String, reversed: Boolean) {
        reversePrefs.edit().putBoolean(bookUrl, reversed).apply()
        _uiState.update { it.copy(reverseOrder = reversed) }
    }
    /** 预取到的首集地址：切过去后直接填进剧集列表，省一次正文规则请求 */
    private val prefetchedUrls = ConcurrentHashMap<String, String>()
    /** 预取地址的取得时间，配合 [PREFETCH_URL_TTL] 判断还能不能用 */
    private val prefetchedUrlTimes = ConcurrentHashMap<String, Long>()
    /**
     * 预取地址额外要带的请求头（主要是嗅探出的 Referer）。
     *
     * 加密源的直链是嗅探钓出来的，站点普遍按 Referer 防盗链；而书源配置的 header 里
     * 没有它。少了这一条，缓冲能建起来、切过去却 403，表现为"预缓存了但还是转圈/报错"。
     */
    private val prefetchedHeaders = ConcurrentHashMap<String, Map<String, String>>()
    /** 正在解析的集，避免同一集被重复发起 */
    private val resolving = Collections.synchronizedSet(mutableSetOf<Int>())
    /** 预取（下一集）正在解析的集，与 [resolving] 分开：
     *  否则预取会占用"正在解析"名额，导致用户滑到那一集时真实解析被 `resolving.add` 拦掉、兜底播放永远不触发 */
    private val prefetching = Collections.synchronizedSet(mutableSetOf<Int>())
    /** 边看边存：当前正在播放的集，切集时用来先收尾上一集 */
    @Volatile
    private var activeWatchIndex = -1
    /** 节流用：上一次写库的集与进度，避免每 5 秒都打一次数据库 */
    private var savedIndex = -1
    private var savedPositionMs = -1L

    init {
        // 某一集下载完成 → 把它的播放地址换成本地文件，后续播放不再联网
        VideoDownloader.addCompletionListener { task -> onDownloadCompleted(task) }
        // 恢复上次退出 APP 时丢失的下载记录（已完成的成品 + 下了一半的 .part）
        VideoDownloader.restoreIfNeeded()
    }

    private fun onDownloadCompleted(task: VideoDownloader.Task) {
        val b = book ?: return
        if (task.book.bookUrl != b.bookUrl) return
        val index = chapters.indexOfFirst { it.url == task.chapter.url }
            .takeIf { it >= 0 } ?: task.index
        // 正在播的那一集不换：换 uri 会重建播放器打断播放，等切走时再换
        refreshLocalUri(index, includeCurrent = false)
    }

    /**
     * 已下载完的集改用本地文件播放（免网络重新缓冲）。
     *
     * @param includeCurrent false 时跳过正在播放的那一集：替换 uri 会让播放器重建，
     * 正在看的时候不能打断，等切到别集时（[onEpisodeActivated]）再换。
     */
    private fun refreshLocalUri(index: Int, includeCurrent: Boolean) {
        if (index !in chapters.indices) return
        val b = book ?: return
        val file = VideoDownloader.findLocalFile(b.name, index, chapters[index].title) ?: return
        val localUri = Uri.fromFile(file).toString()
        _uiState.update { s ->
            if (index !in s.episodes.indices) return@update s
            if (!includeCurrent && index == s.currentIndex) return@update s
            val old = s.episodes[index]
            if (old.uri.toString() == localUri) return@update s
            val merged = s.episodes.toMutableList()
            merged[index] = old.copy(uri = Uri.parse(localUri), size = file.length())
            s.copy(episodes = merged)
        }
    }

    /** 把所有已下载完的集刷新为本地地址（重新进入页面时兜底一次） */
    private fun refreshLocalUris() {
        for (i in _uiState.value.episodes.indices) refreshLocalUri(i, includeCurrent = false)
    }

    /**
     * 播放过程中**周期性**调用：把已经缓冲进缓存的分片搬进 .part（零额外流量）。
     *
     * 只在切集/退出时各试一次是不够的——刚切到一集的瞬间缓存里往往还没有分片，
     * 收集会立刻空手而归，之后播放期间再没人触发它，进度条就一直停在"等待中"。
     * 由界面每 20 秒调一次，播放多久就收多久。
     */
    fun collectCurrentEpisode() {
        val index = activeWatchIndex
        if (index < 0) return
        if (!VideoDownloader.isWatchSaveEnabled()) return
        watchTask(index)?.let { (task, knownUrl) ->
            VideoDownloader.watchStart(task, knownUrl)
        }
    }

    /**
     * 退出播放器：把正在播的这一集收尾（补齐缺失片段后合并落盘）。
     * 不切集直接退出时也要存下，否则这集只留下一半的 .part 缓存。
     */
    fun finalizeCurrentEpisode() {
        val index = activeWatchIndex
        if (index < 0) return
        // 不再播放：解除让路，剩下的缺口全速补齐
        VideoDownloader.setPlaybackActive(false)
        watchTask(index)?.let { (task, knownUrl) ->
            VideoDownloader.watchFinalize(task, knownUrl)
        }
    }

    /** 构造边看边存任务；knownUrl 用已解析出的直链，省一次正文规则请求 */
    private fun watchTask(index: Int): Pair<VideoDownloader.Task, String?>? {
        val b = book ?: return null
        val src = source ?: return null
        val chapter = chapters.getOrNull(index) ?: return null
        val knownUrl = _uiState.value.episodes.getOrNull(index)?.uri?.toString()
            ?.takeIf { it.startsWith("http") }
        return VideoDownloader.Task(b, src, index, chapter) to knownUrl
    }

    /**
     * 当前播放的集发生变化（进入 / 切集 / 点选集）——**边看边存的唯一触发点**。
     *
     * 必须放在会话层而不是某个界面里：之前只在首页面板触发，全屏播完一集什么都没存下。
     *
     * 1. 上一集：先收尾（补齐缺失片段后合并保存到 Download/保存视频/），走优先队列，
     *    排在刚入队的当前集之前——播完的那集先落盘，不会被新集压在后面；
     * 2. 当前集：已经下完的直接用本地文件播，不再联网；否则入队边看边存。
     */
    fun onEpisodeActivated(index: Int) {
        val s = _uiState.value
        if (index !in s.episodes.indices) return
        val prev = activeWatchIndex
        activeWatchIndex = index
        if (prev >= 0 && prev != index) {
            // 切走的上一集：它已不是当前集，可以安全换成刚下好的本地文件
            refreshLocalUri(prev, includeCurrent = true)
            watchTask(prev)?.let { (task, knownUrl) ->
                VideoDownloader.watchFinalize(task, knownUrl)
            }
        }
        // 当前集：已下载完就播本地文件，"已经下过就不重新缓存"
        refreshLocalUri(index, includeCurrent = true)
        // 播放器还在播：后台下载进入让路模式（只收集缓存 + 低并发节流）
        VideoDownloader.setPlaybackActive(true)
        watchTask(index)?.let { (task, knownUrl) ->
            VideoDownloader.watchStart(task, knownUrl)
        }
    }

    fun load(bookUrl: String, startIndex: Int) {
        // **必须在早退分支之前把"切换列表"挂上**。
        // 重新进入播放器时若还是同一部视频（无限刷的续播列表第一条常常就是上次正在看的那条），
        // 下面会走"剧集已就绪"的早退分支；feed 只在 loadInternal 里取的话就永远挂不上，
        // 表现成"只有这一个视频，竖滑提示已经是列表最后一个"。
        ensureFeed(bookUrl)
        // 立刻把"能否竖滑切换"写进状态：解析往往要好几秒，若等剧集就绪才写，
        // 这段时间里界面拿到的还是上一部（或 false）的旧值，
        // 表现就是"解析中滑不动，解析完了才能滑"。
        _uiState.update { s -> s.copy(feedEnabled = (feed?.urls()?.size ?: 0) > 1) }
        // 乐观铺底：书库/搜索缓存里通常已有该视频的封面（本地元数据，无需联网），
        // 让解析/加载期间显示封面+转圈，而不是纯黑屏
        val bookInDb = appDb.bookDao.getBook(bookUrl)
        val searchBook = appDb.searchBookDao.getSearchBook(bookUrl)
        val incoming = bookInDb ?: searchBook?.toBook()
        val incomingOrigin = bookInDb?.origin ?: searchBook?.origin
        incoming?.coverUrl?.let { cover ->
            if (cover.isNotBlank()) {
                _uiState.update { s -> s.copy(coverUrl = cover, coverOrigin = incomingOrigin ?: "") }
            }
        }
        // 同一本书且剧集已就绪（退出后再打开）：同步索引并重置解析结果。
        // 关键点：重新进入必须把上次的直链/网页地址清空、重新解析，
        // 否则带时效的 token 失效、网络波动等造成的"播放错误"会被记住，
        // 表现成"退出重开还是报错，只能重启 APP 才恢复"。
        if (book?.bookUrl == bookUrl && _uiState.value.episodes.isNotEmpty()) {
            val idx = startIndex.coerceIn(0, chapters.lastIndex)
            // 把每集已解析的地址清空（已下载到本地的集保留本地地址），让它们重新解析
            val resetEpisodes = _uiState.value.episodes.map { ep ->
                if (ep.uri.toString().startsWith("file:")) ep else ep.copy(uri = Uri.EMPTY)
            }
            _uiState.update { s -> s.copy(episodes = resetEpisodes) }
            // 期间可能又有几集下载完了，补一次本地地址
            refreshLocalUris()
            // 清掉上一轮可能残留的"解析中"标记，否则会被误判为正在解析而跳过重新解析
            resolving.clear()
            prefetching.clear()
            savedIndex = idx
            savedPositionMs = book?.durChapterPos?.toLong() ?: 0L
            _uiState.update {
                it.copy(
                    loading = false,
                    error = null,
                    fallbackIndex = null,
                    fallbackLaunched = false,
                    fallbackFailed = null,
                    // 早退分支没走 loadInternal，这里必须同步刷新"能否竖滑切换"，
                    // 否则沿用上一次的旧值（要么该滑不能滑，要么能滑却提示最后一个）
                    feedEnabled = (feed?.urls()?.size ?: 0) > 1,
                    currentIndex = idx,
                    startIndex = idx,
                    startPositionMs = book?.durChapterPos?.toLong()?.coerceAtLeast(0) ?: 0L,
                )
            }
            // 列表刚挂上：立刻确认后续够不够、并预取下一个视频
            ensureMore()
            ensureNextVideoPrefetch()
            return
        }
        // 必须清掉上一部视频遗留的所有错误状态，否则失败一次后
        // 每个新视频都会直接弹出同样的旧错误
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                playbackError = null,
                fallbackIndex = null,
                fallbackLaunched = false,
                fallbackFailed = null,
            )
        }
        // 解析途中也可能被竖滑切走：必须先取消上一轮，
        // 否则两轮 loadInternal 并发，慢的那一轮后写入会把新视频的状态覆盖成旧的
        loadJob?.cancel()
        loadJob = scope.launch {
            runCatching { loadInternal(bookUrl, startIndex) }
                .onFailure { e ->
                    // 协程取消不是错误，页面销毁/重新加载时会走到这里，不能当成失败弹给用户
                    if (e is CancellationException) throw e
                    AppLog.put("加载剧集失败\n${e.localizedMessage}", e, true)
                    _uiState.update { s -> s.copy(loading = false, error = e.localizedMessage) }
                }
        }
    }

    /**
     * 确保"切换列表"已挂到当前视频上：命中现有列表就沿用，否则依次尝试
     * 一次性 feed（[VideoFeedStore.take]）与无限刷常驻 feed（[VideoFeedStore.peekPersistent]）。
     */
    private fun ensureFeed(bookUrl: String) {
        if (feed?.urls()?.contains(bookUrl) == true) return
        // take() 无论命中与否都会清空暂存，两次并发 load（如横竖屏重建）时
        // 第二次拿到 null。这时**不能**把已有的 feed 覆盖成 null，
        // 否则这一整轮都没有切换列表，预取下一部必然为空。
        VideoFeedStore.take(bookUrl)?.let { feed = it }
        // 一次性 feed 已被取走（进程被杀后重建、或并发 load 抢先消费）：
        // 无限刷登记过常驻 feed，这里重新挂上，避免退化成"单视频、滑不动"
        if (feed == null) {
            VideoFeedStore.peekPersistent(bookUrl)?.let { feed = it }
        }
    }

    private suspend fun loadInternal(bookUrl: String, startIndex: Int) {
        ensureFeed(bookUrl)
        val feedEnabled = (feed?.urls()?.size ?: 0) > 1
        // 同步写进状态：解析（拉详情/目录/直链）可能持续数秒，
        // 期间界面就靠这个值决定右侧竖滑能不能用，不能等最后才写。
        _uiState.update { s -> s.copy(feedEnabled = feedEnabled) }
        val book = appDb.bookDao.getBook(bookUrl)
            // 竖滑切到的视频可能还没入库：从搜索缓存转正并插入书库（带 notShelf：不自动上书架）
            ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.also { newBook ->
                newBook.addType(BookType.notShelf)
                appDb.bookDao.insert(newBook)
            }
            ?: throw IllegalStateException("书籍不存在")
        val source = book.getBookSource()
            ?: throw IllegalStateException("书源不存在")
        val chapters = VideoSourcePlayer.prepareChapters(book, source)
        if (chapters.isEmpty()) throw IllegalStateException("没有可播放的剧集")
        this@SourceVideoPlayerSession.book = book
        this@SourceVideoPlayerSession.source = source
        this@SourceVideoPlayerSession.chapters = chapters
        resolving.clear()
        // 换了视频：上一轮预取的「下一个视频」作废（它现在就是当前视频）
        prefetchedNext = null
        // 目标变了，还在跑的那一轮（可能正卡在 20 秒的嗅探里）没必要继续
        prefetchJob?.cancel()
        prefetchJob = null
        // 换了视频就是换了预取目标，上一部的失败冷却不该拖住这一部
        prefetchFailedAt = 0L
        // 换了视频：上一部剧的"当前集"不能带过来当收尾对象
        activeWatchIndex = -1
        // 白名单书源本来就不该被拦截：它的播放页自带选集/清晰度/倍速，
        // 直接交给书源自己拉起，与视频主页的处理保持一致
        if (VideoConfig.isWhitelisted(source.bookSourceUrl)) {
            _uiState.update {
                it.copy(
                    loading = false,
                    bookName = book.name,
                    bookUrl = book.bookUrl,
                    sourceOrigin = source.bookSourceUrl,
                    currentIndex = startIndex.coerceIn(0, chapters.lastIndex),
                    feedEnabled = feedEnabled,
                    reverseOrder = isReverseOrder(book.bookUrl),
                    coverUrl = book.coverUrl ?: "",
                    coverOrigin = book.origin,
                    fallbackIndex = startIndex.coerceIn(0, chapters.lastIndex),
                )
            }
            return
        }
        // 续播：书籍里记的集内进度（毫秒）
        savedIndex = startIndex
        savedPositionMs = book.durChapterPos.toLong()
        // 书源请求头。预取（嗅探）来的那一集若还要额外的头（典型是 Referer），一并合进来：
        // 加密源钓出的直链普遍按 Referer 防盗链，少了就是"缓冲好了却 403"。
        val headers = HashMap(source.getHeaderMap(true))
        val episodes = chapters.mapIndexed { i, chapter ->
            // 先看本地缓存：已下载过的选集直接用本地文件，免网络
            val local = VideoDownloader.findLocalFile(book.name, i, chapter.title)
            // 上一个视频播到最后一集时已经预取过这一部：直接采信，省一次正文规则请求。
            // 加密直链常带短时效签名，过期的不用——宁可重新解析，也别抱着坏地址播。
            val prefKey = "${book.bookUrl}#$i"
            val prefetched = prefetchedUrls[prefKey]?.takeIf { !isPrefetchExpired(prefKey) }
            if (prefetched != null) {
                prefetchedHeaders[prefKey]?.let { headers.putAll(it) }
            }
            val url = local?.let { Uri.fromFile(it).toString() }
                ?: chapter.url.trim().takeIf { VideoSourcePlayer.isPlayableUrl(it) }
                ?: prefetched
            LocalVideo(
                uri = url?.let { Uri.parse(it) } ?: Uri.EMPTY,
                name = chapter.title,
                path = chapter.url.trim(),
                size = local?.length() ?: 0L,
                dateModified = 0L,
            )
        }
        val index = startIndex.coerceIn(0, episodes.lastIndex)
        _uiState.update {
            it.copy(
                loading = false,
                bookName = book.name,
                bookUrl = book.bookUrl,
                sourceOrigin = source.bookSourceUrl,
                currentIndex = index,
                feedEnabled = feedEnabled,
                reverseOrder = isReverseOrder(book.bookUrl),
                coverUrl = book.coverUrl ?: "",
                coverOrigin = book.origin,
                episodes = episodes,
                startIndex = index,
                startPositionMs = book.durChapterPos.toLong().coerceAtLeast(0),
                headers = headers,
            )
        }
        // 进入/切换到当前视频时，立即保证后续列表已加载（当前页加载下一页）
        ensureMore()
        // 立即后台预取列表里的下一个视频（目录 + 首集直链，加密源还会提前嗅探）：
        // 切视频最耗时的是「拉目录→跑正文规则解析直链→必要时开 WebView 嗅探」，
        // 把它提前到现在做，不必等用户滑走才开始，播放器随后据此预缓冲，切过去直接接管。
        // 走 ensureNextVideoPrefetch（fire-and-forget）而不是直接 await：嗅探可能要 20 秒，
        // 这里 await 会拖住本次加载的收尾，且结果没人等。
        ensureNextVideoPrefetch()
    }

    /**
     * 预取地址是否已过期。
     *
     * 加密源嗅探出来的直链多是一次性签名/短时效 token，隔太久再用就是 403。
     * 过期即丢弃（连带的额外请求头一起清），让这一集走正常解析重新拿。
     */
    private fun isPrefetchExpired(key: String): Boolean {
        val time = prefetchedUrlTimes[key]
        if (time == null || System.currentTimeMillis() - time > PREFETCH_URL_TTL) {
            prefetchedUrls.remove(key)
            prefetchedHeaders.remove(key)
            prefetchedUrlTimes.remove(key)
            return true
        }
        return false
    }

    /**
     * 把「看到第几集 + 集内进度」写回书籍，下次进入时从这里续播。
     *
     * [durChapterPos] 对小说是字符索引，但视频书不会走小说阅读页，
     * 这里借它存集内毫秒进度不会与既有逻辑冲突。
     */
    fun saveProgress(index: Int, positionMs: Long) {
        val chapters = chapters
        if (index !in chapters.indices) return
        val pos = positionMs.coerceAtLeast(0)
        // 当前集先同步进状态（大屏↔首页切换依赖它）
        _uiState.update { it.copy(currentIndex = index) }
        // 节流：集数没变且进度推进不足 3 秒就不写库
        if (index == savedIndex && kotlin.math.abs(pos - savedPositionMs) < 3000L) return
        savedIndex = index
        savedPositionMs = pos
        // 捕获调用时刻所属的书：切视频后全局 book 会被换成下一部，
        // 上一个播放器延迟一帧的销毁上报若仍带着旧集序号进来，必须丢弃，
        // 否则会把上一部的进度写进下一部的记录（串台），或索引越界被直接丢。
        val saveBookUrl = book?.bookUrl ?: return
        scope.launch {
            // 期间已切换到别的视频（全局 book 已换）→ 这条上报属于上一视频，不应写进新书
            val cur = book ?: return@launch
            if (cur.bookUrl != saveBookUrl) return@launch
            val record = appDb.bookDao.getBook(saveBookUrl) ?: return@launch
            if (record.durChapterIndex == index
                && record.durChapterPos == pos.toInt()
                && chapters.getOrNull(index)?.title == record.durChapterTitle
            ) return@launch
            record.durChapterIndex = index
            record.durChapterPos = pos.toInt()
            record.durChapterTitle = chapters[index].title
            record.durChapterTime = System.currentTimeMillis()
            appDb.bookDao.update(record)
        }
    }

    /**
     * 切走/退出前，趁全局状态还是当前视频，把进度立刻落库（绕过 3 秒节流）。
     * 否则 [feedSwitch]→[load] 会把全局 book 换成下一部，上一视频的进度就写不进自己的记录，
     * 表现为「切回来从头播 / 从别的视频的时间点播」。
     */
    fun flushCurrentProgress() {
        val idx = _uiState.value.currentIndex
        val pos = savedPositionMs.coerceAtLeast(0)
        val book = book ?: return
        val saveBookUrl = book.bookUrl
        val curChapters = chapters
        if (idx !in curChapters.indices) return
        scope.launch {
            val record = appDb.bookDao.getBook(saveBookUrl) ?: return@launch
            record.durChapterIndex = idx
            record.durChapterPos = pos.toInt()
            record.durChapterTitle = curChapters[idx].title
            record.durChapterTime = System.currentTimeMillis()
            appDb.bookDao.update(record)
        }
    }

    /**
     * 解析某一集的地址。已就绪、正在解析或越界的集会直接跳过。
     *
     * @param prefetch true 表示来自"预取下一集"：只解析、缓存直链，**绝不**触发内置播放器兜底。
     *   内置播放器类源拿不到直链，预取若直接弹窗就会出现"还没滑到就先弹出下一集"的串台现象；
     *   真正要播这一集（用户滑到/点选）时，以 prefetch=false 再触发一次，才会回退内置播放器。
     */
    fun resolveEpisode(index: Int, prefetch: Boolean = false) {
        val state = _uiState.value
        if (index !in state.episodes.indices) return
        // 已下载的集：直接播本地文件，连正文规则都不必再跑一次
        val local = book?.let { b ->
            chapters.getOrNull(index)?.let { c ->
                VideoDownloader.findLocalFile(b.name, index, c.title)
            }
        }
        if (local != null) {
            refreshLocalUri(index, includeCurrent = true)
            return
        }
        if (state.episodes[index].uri != Uri.EMPTY) return
        // 预取与真实解析用各自独立的集合，互不挡路
        val guard = if (prefetch) prefetching else resolving
        if (!guard.add(index)) return
        scope.launch {
            runCatching {
                val book = book ?: return@runCatching null
                val source = source ?: return@runCatching null
                val chapter = chapters.getOrNull(index) ?: return@runCatching null
                // 先看本地缓存：命中则免网络直接返回本地文件
                VideoDownloader.findLocalFile(book.name, index, chapter.title)
                    ?.let { return@runCatching Uri.fromFile(it).toString() }
                val resolve = VideoSourcePlayer.resolveChapter(book, source, chapter)
                var url = resolve.directUrl
                // 网页播放器型源：地址要跑站点自己的 JS 才拿得到，ExoPlayer 直接播必然失败。
                // 这里后台开一次真实浏览器把直链"钓"出来，成功后仍由 ExoPlayer 播放——
                // 竖滑切集、倍速、选集这些交互全部保留；钓不到才退回内置浏览器。
                // 预取不嗅探：避免还没滑到就先跑浏览器、还把下一集的地址算出来造成串台。
                if (url.isNullOrBlank() && !resolve.webPlayerUrl.isNullOrBlank() && !prefetch) {
                    url = VideoUrlSniffer.sniff(
                        resolve.webPlayerUrl!!,
                        source.getHeaderMap(true),
                    )?.url
                }
                url
            }.onSuccess { url ->
                guard.remove(index)
                // 本地文件与直链均可播；拿不到就交给书源自己处理（比如它给的是网页播放器）
                // 解析侧已经确认过：要么后缀就是可播的流，要么真实探测过 Content-Type/内容。
                // 这里只校验它确实是个地址——再用 isPlayableUrl 过一遍会把
                // "文件名藏在末尾参数里"的代理直链（如 ?url=xxx&f=1.m3u8）误判成网页播放器，
                // 结果明明能直播却退回内置浏览器。
                if (url.isNullOrBlank()
                    || (!url.startsWith("file:") && !url.startsWith("http", ignoreCase = true))
                ) {
                    // 预取：只解析不播，避免还没滑到就弹出内置播放器
                    if (!prefetch) {
                        _uiState.update { it.copy(fallbackIndex = index) }
                    }
                    return@onSuccess
                }
                _uiState.update { current ->
                    if (index !in current.episodes.indices) return@update current
                    val merged = current.episodes.toMutableList()
                    merged[index] = merged[index].copy(uri = Uri.parse(url))
                    current.copy(episodes = merged)
                }
            }.onFailure { e ->
                guard.remove(index)
                // 取消不是失败：不要因为页面切走就把这一集判成"解析不出直链"弹出兜底播放器
                if (e is CancellationException) return@onFailure
                AppLog.put("解析第 ${index + 1} 集失败\n${e.localizedMessage}", e)
                // 预取失败不弹兜底，等用户真滑到这一集再重试
                if (!prefetch) {
                    _uiState.update { s -> s.copy(fallbackIndex = index) }
                }
            }
        }
    }

    /**
     * 预取后续几部视频的首集：只解析、不切换会话、不拉起任何界面。
     *
     * 把「解析」与「建缓冲」分开——解析（拉目录→跑正文规则→必要时嗅探）只是几次网络请求，
     * 一次多解析几部很划算；真正占带宽的建缓冲只对最靠前的一部做。
     * 于是连续下滑两部都不必现场等解析，流量却只多花一份。
     *
     * 两个触发点：[loadInternal] 切到当前视频时（主路径）与播放稳定后的兜底轮询。
     */
    suspend fun prefetchNextVideo(): NextVideoPreload? = withContext(Dispatchers.IO) {
        // 会话层（load 完成时）与播放器层可能同时来要：
        // 加锁串行化，保证整条「拉目录→解析直链→必要时嗅探」对同一目标只跑一遍
        prefetchMutex.withLock {
            val targets = nextVideoUrls(PREFETCH_LOOKAHEAD)
            if (targets.isEmpty()) return@withLock null
            var first: NextVideoPreload? = null
            for (url in targets) {
                // 已解析过就跳过：切集/重组会反复来问，别重复联网。
                // 加密直链有寿命，过期的不算数。
                val cached = prefetchedAhead[url]
                    ?.takeIf { !isPrefetchExpired("${it.bookUrl}#${it.index}") }
                if (cached != null) {
                    if (first == null) first = cached
                    continue
                }
                val result = runCatching { resolveVideo(url) }
                    .onFailure { AppLog.put("预取下一个视频失败\n${it.localizedMessage}", it) }
                    .getOrNull()
                if (result != null) {
                    prefetchedAhead[url] = result
                    if (first == null) first = result
                }
            }
            // 换了分类/搜索后，旧列表里的结果再也不会被命中，堆多了就整体清一次
            if (prefetchedAhead.size > PREFETCH_AHEAD_MAX) prefetchedAhead.clear()
            prefetchedNext = first
            first
        }
    }

    /**
     * 解析某一部的首集，给出可直接预缓冲的结果；拿不到直链则为 null。
     *
     * 顺带把该部的目录预热进库，真正切过去时 [load] 也不必再联网拉目录。
     */
    private suspend fun resolveVideo(bookUrl: String): NextVideoPreload? {
        // 搜索缓存里的这一部必须**先转正入库**：chapters.bookUrl 外键引用 books.bookUrl，
        // 未入库的书直接插章节会 FOREIGN KEY constraint failed（code 787）。
        // 与 [loadInternal] 对齐：带 notShelf，不自动上书架。
        val nextBook = appDb.bookDao.getBook(bookUrl)
            ?: appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.also { newBook ->
                newBook.addType(BookType.notShelf)
                appDb.bookDao.insert(newBook)
            }
            ?: return null
        val nextSource = nextBook.getBookSource() ?: return null
        val chapters = VideoSourcePlayer.prepareChapters(nextBook, nextSource)
        if (chapters.isEmpty()) return null
        val index = feed?.startIndexOf(bookUrl)?.coerceIn(0, chapters.lastIndex) ?: 0
        val resolve = VideoSourcePlayer.resolveChapter(nextBook, nextSource, chapters[index])
        var url = resolve.directUrl
        // 请求头以书源配置为基础，嗅探到 Referer 时再补上（见下）
        val headers = HashMap(nextSource.getHeaderMap(true))
        // 网页播放器型/加密源：直链为空但 webPlayerUrl 存在时，
        // 预取阶段就提前嗅探出真直链，竖滑切过去直接接管、不再现场跑浏览器。
        if (url.isNullOrBlank() && !resolve.webPlayerUrl.isNullOrBlank()) {
            val sniffed = VideoUrlSniffer.sniff(resolve.webPlayerUrl!!, headers)
            url = sniffed?.url
            // 嗅探到的那次请求自带 Referer：加密源普遍按它防盗链，
            // 必须原样交给 ExoPlayer，否则缓冲建起来了、切过去还是 403。
            sniffed?.referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        }
        if (url.isNullOrBlank()) return null
        // 这部之前看过：预缓冲时就定位到上次的进度，切过去接着看
        val startPosition = if (nextBook.durChapterIndex == index) {
            nextBook.durChapterPos.toLong().coerceAtLeast(0)
        } else {
            0L
        }
        prefetchedUrls["$bookUrl#$index"] = url
        prefetchedUrlTimes["$bookUrl#$index"] = System.currentTimeMillis()
        prefetchedHeaders["$bookUrl#$index"] = headers
        return NextVideoPreload(bookUrl, index, url, startPosition, headers)
    }

    /**
     * 播放器层取用预取结果：**非挂起**。
     *
     * 只交出已经就绪的结果；还没就绪就确认一次后台任务在跑，然后返回 null，
     * 下一轮（切集/列表更新、且当前集自身缓冲充足后）再来取。
     * 关键是别在这里 await：加密源要开 WebView 嗅探 20 秒以上，
     * 一旦 await 就等于把它挂到界面的 LaunchedEffect 上，随时被重组取消且静默失败。
     */
    fun peekNextVideo(): NextVideoPreload? {
        val cached = prefetchedNext
        if (cached == null) {
            ensureNextVideoPrefetch()
            return null
        }
        // 目标已经变了（用户切到了别的视频）：作废，重新去取
        if (cached.bookUrl != nextVideoUrl()) {
            prefetchedNext = null
            ensureNextVideoPrefetch()
            return null
        }
        // 加密直链多为短时效签名，放久了再用就是 403：过期当没有，重新解析
        if (isPrefetchExpired("${cached.bookUrl}#${cached.index}")) {
            prefetchedNext = null
            ensureNextVideoPrefetch()
            return null
        }
        return cached
    }

    /**
     * 确认后台预取在跑；重复调用无副作用。
     * 同一目标只解析一次（[prefetchedNext] 缓存 + [prefetchMutex] 双重判重），
     * 且任务挂在会话 scope 上，界面怎么重组都打断不了它。
     */
    private fun ensureNextVideoPrefetch() {
        if (prefetchedNext != null) return
        if (nextVideoUrl() == null) return
        if (prefetchJob?.isActive == true) return
        // 刚失败过：冷却期内不再重试
        if (System.currentTimeMillis() - prefetchFailedAt < PREFETCH_RETRY_COOLDOWN) return
        prefetchJob = scope.launch {
            val result = runCatching { prefetchNextVideo() }.getOrNull()
            // 拿不到结果（异常或解析不出直链）都算失败，进入冷却
            prefetchFailedAt = if (result == null) System.currentTimeMillis() else 0L
        }
    }

    /** 切换列表里当前视频的下一个（没有则 null） */
    private fun nextVideoUrl(): String? = nextVideoUrls(1).firstOrNull()

    /** 列表里紧跟着当前的后面几部（不足则少给几个） */
    private fun nextVideoUrls(count: Int): List<String> {
        val urls = feed?.urls() ?: return emptyList()
        val currentUrl = book?.bookUrl ?: return emptyList()
        val pos = urls.indexOf(currentUrl)
        if (pos < 0) return emptyList()
        val end = (pos + 1 + count).coerceAtMost(urls.size)
        return if (pos + 1 >= end) emptyList() else urls.subList(pos + 1, end)
    }

    /**
     * 退回书源自带的播放器（内置浏览器）。
     *
     * 会话与页面生命周期解耦，切换页面不会掐断这里的协程。
     */
    fun playWithSourcePlayer(index: Int) {
        mainScope.launch {
            val result = runCatching {
                val book = book
                    ?: throw IllegalStateException("书籍未加载")
                val source = source
                    ?: throw IllegalStateException("书源不存在")
                val chapter = chapters.getOrNull(index)
                    ?: throw IllegalStateException("没有可播放的剧集")
                VideoSourcePlayer.play(book, source, chapter)
            }
            result.onFailure {
                AppLog.put("拉起书源播放器失败\n${it.localizedMessage}", it, true)
                _uiState.update { s -> s.copy(fallbackFailed = it.localizedMessage) }
            }.onSuccess {
                _uiState.update { s -> s.copy(fallbackLaunched = true) }
            }
        }
    }

    /** 供首页下载等场景取当前书籍/书源/章节列表 */
    fun getBook(): Book? = book
    fun getSource(): BookSource? = source
    fun getChapters(): List<BookChapter> = chapters

    /** 清空会话中的错误状态（错误框关闭时调用，避免旧错误污染后续视频） */
    fun clearError() {
        _uiState.update { it.copy(error = null, fallbackFailed = null, playbackError = null) }
    }

    /** 记录播放阶段出错（ExoPlayer 报错），交由上层在可切换列表时自动跳下一个视频 */
    fun setPlaybackError(msg: String?) {
        _uiState.update { it.copy(playbackError = msg) }
    }

    /**
     * 无限刷模式用：不拉起书源自带播放器，直接跳过该视频（清掉回退标志）。
     * 与 [clearError] 一起把"需要回退/失败"的状态复位，交给上层自动切下一个视频。
     */
    fun skipFallback() {
        _uiState.update { it.copy(fallbackIndex = null, fallbackLaunched = false, fallbackFailed = null) }
    }

    /** 加入书架：清除 notShelf 标记（看过的视频默认不上书架，仅此入口手动添加） */
    fun addToShelf() {
        val b = book ?: return
        scope.launch {
            val record = appDb.bookDao.getBook(b.bookUrl) ?: return@launch
            if (record.type and BookType.notShelf == 0) {
                appCtx.toastOnUi("已在书架中")
                return@launch
            }
            record.removeType(BookType.notShelf)
            appDb.bookDao.update(record)
            appCtx.toastOnUi("已加入书架")
        }
    }

    /** 首页选集方块点击：切到指定集（未解析的先解析），并保证后续列表已加载 */
    fun selectEpisode(index: Int) {
        if (index !in _uiState.value.episodes.indices) return
        // 已下载完的集直接播本地文件：先换成 file:// 再切，避免先用 http 起播又重建一次
        refreshLocalUri(index, includeCurrent = true)
        if (_uiState.value.episodes[index].uri == Uri.EMPTY) resolveEpisode(index)
        _uiState.update {
            it.copy(currentIndex = index, startIndex = index, startPositionMs = 0)
        }
        ensureMore()
    }

    /**
     * 确保列表能继续向后切：当前视频后面不足 2 个时，立即后台加载下一页。
     * 新分页进入列表后可一直向下切换，不必等滑到最后才加载。
     */
    private fun ensureMore() {
        val f = feed ?: return
        val urls = f.urls()
        val cur = book?.bookUrl?.let { urls.indexOf(it) } ?: -1
        // 当前视频不在列表里（列表刚被换掉）也按"到底了"处理，立即补货；
        // 剩余不足 5 条就开始补，不再等到只剩最后 2 条才补（补货要联网，来不及）
        if (cur < 0 || urls.size - cur - 1 < 5) {
            f.loadMore?.invoke()
        }
    }

    /** 退出播放器时清空切换列表：下次从任意分类/搜索进入都使用当时的列表 */
    fun clearFeed() {
        feed = null
        VideoFeedStore.clearPersistent()
        prefetchedNext = null
        prefetchJob?.cancel()
        prefetchJob = null
        // 列表都清了，为「下一个视频」预缓冲的那个播放器实例已无对应目标，一并放掉，
        // 否则退出后还留着一个 prepare 过的 ExoPlayer 占资源
        SharedPlayer.releaseNextVideo()
    }

    /**
     * 右侧 20% 竖滑切换列表视频：+1 下一个 / -1 上一个。
     *
     * 列表是动态供给的：每次切换实时读取当前列表（含发现页/搜索页
     * 新分页加载出来的视频），可以一直滑到列表真正的末尾。
     * 重新走 load 流程（新书籍的目录需要联网拉取）；上一个视频若还留在
     * SharedPlayer 交接池里，可无缝接管继续播。
     */
    /** 计算竖滑切到的目标视频 URL（无有效目标返回 null） */
    private fun resolveFeedTarget(offset: Int): String? {
        val feed = feed ?: return null
        val urls = feed.urls()
        val currentUrl = book?.bookUrl ?: return null
        val pos = urls.indexOf(currentUrl)
        if (pos < 0) return null
        return urls.getOrNull(pos + offset)
    }

    /** 切视频前先乐观地把目标（第二个）视频封面设为铺底：本地书库/搜索缓存即可取到，无需联网，
     *  让滑动全程露出的都是即将播放的视频封面，而非第一个视频的封面；
     *  返回是否存在有效目标（无则已是列表首尾）。 */
    fun previewFeed(offset: Int): Boolean {
        val url = resolveFeedTarget(offset) ?: return false
        val bookInDb = appDb.bookDao.getBook(url)
        val searchBook = appDb.searchBookDao.getSearchBook(url)
        val incoming = bookInDb ?: searchBook?.toBook()
        val incomingOrigin = bookInDb?.origin ?: searchBook?.origin
        incoming?.coverUrl?.let { cover ->
            if (cover.isNotBlank()) {
                _uiState.update { s -> s.copy(coverUrl = cover, coverOrigin = incomingOrigin ?: "") }
            }
        }
        return true
    }

    fun feedSwitch(offset: Int): Boolean {
        // 切走前先把当前视频进度落库（此时全局状态还是当前视频，写入正确；
        // 否则 load(下一部) 换掉全局 book 后，上一路的销毁上报会被丢/串台）
        flushCurrentProgress()
        val url = resolveFeedTarget(offset) ?: run {
            appCtx.toastOnUi(
                if (offset > 0) "已经是列表最后一个" else "已经是列表第一个"
            )
            return false
        }
        // 目标封面已在 previewFeed 中铺底；这里再保险设一次（如跳过 previewFeed 直接调用时）
        val bookInDb = appDb.bookDao.getBook(url)
        val searchBook = appDb.searchBookDao.getSearchBook(url)
        val incoming = bookInDb ?: searchBook?.toBook()
        val incomingOrigin = bookInDb?.origin ?: searchBook?.origin
        incoming?.coverUrl?.let { cover ->
            if (cover.isNotBlank()) {
                _uiState.update { s -> s.copy(coverUrl = cover, coverOrigin = incomingOrigin ?: "") }
            }
        }
        load(url, feed?.startIndexOf(url) ?: 0)
        return true
    }
}
