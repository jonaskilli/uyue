package io.legado.app.ui.book.video

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
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import splitties.init.appCtx
import java.util.Collections

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
    /** 书籍封面：加载/解析时铺底显示，便于快速切换时辨认视频 */
    val coverUrl: String = "",
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
                    currentIndex = idx,
                    startIndex = idx,
                    startPositionMs = book?.durChapterPos?.toLong()?.coerceAtLeast(0) ?: 0L,
                )
            }
            return
        }
        // 必须清掉上一部视频遗留的所有错误状态，否则失败一次后
        // 每个新视频都会直接弹出同样的旧错误
        _uiState.update {
            it.copy(
                loading = true,
                error = null,
                fallbackIndex = null,
                fallbackLaunched = false,
                fallbackFailed = null,
            )
        }
        scope.launch {
            runCatching { loadInternal(bookUrl, startIndex) }
                .onFailure { e ->
                    // 协程取消不是错误，页面销毁/重新加载时会走到这里，不能当成失败弹给用户
                    if (e is CancellationException) throw e
                    AppLog.put("加载剧集失败\n${e.localizedMessage}", e, true)
                    _uiState.update { s -> s.copy(loading = false, error = e.localizedMessage) }
                }
        }
    }

    private suspend fun loadInternal(bookUrl: String, startIndex: Int) {
        // 列表随时跟随来源：当前视频不在已记录列表中（换了分类/搜索），
        // 就重新消费入口列表；在同一列表内切集/切换则沿用
        if (feed?.urls()?.contains(bookUrl) != true) {
            feed = VideoFeedStore.take(bookUrl)
        }
        val feedEnabled = (feed?.urls()?.size ?: 0) > 1
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
                    coverUrl = book.coverUrl ?: "",
                    fallbackIndex = startIndex.coerceIn(0, chapters.lastIndex),
                )
            }
            return
        }
        // 续播：书籍里记的集内进度（毫秒）
        savedIndex = startIndex
        savedPositionMs = book.durChapterPos.toLong()
        val headers = source.getHeaderMap(true)
        val episodes = chapters.mapIndexed { i, chapter ->
            // 先看本地缓存：已下载过的选集直接用本地文件，免网络
            val local = VideoDownloader.findLocalFile(book.name, i, chapter.title)
            val url = local?.let { Uri.fromFile(it).toString() }
                ?: chapter.url.trim().takeIf { VideoSourcePlayer.isPlayableUrl(it) }
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
                coverUrl = book.coverUrl ?: "",
                episodes = episodes,
                startIndex = index,
                startPositionMs = book.durChapterPos.toLong().coerceAtLeast(0),
                headers = headers,
            )
        }
        // 进入/切换到当前视频时，立即保证后续列表已加载（当前页加载下一页）
        ensureMore()
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
        val book = book ?: return
        scope.launch {
            val record = appDb.bookDao.getBook(book.bookUrl) ?: return@launch
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
        _uiState.update { it.copy(error = null, fallbackFailed = null) }
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
        val cur = book?.bookUrl?.let { urls.indexOf(it) } ?: return
        if (urls.isEmpty() || cur + 2 >= urls.size) {
            f.loadMore?.invoke()
        }
    }

    /** 退出播放器时清空切换列表：下次从任意分类/搜索进入都使用当时的列表 */
    fun clearFeed() {
        feed = null
    }

    /**
     * 右侧 20% 竖滑切换列表视频：+1 下一个 / -1 上一个。
     *
     * 列表是动态供给的：每次切换实时读取当前列表（含发现页/搜索页
     * 新分页加载出来的视频），可以一直滑到列表真正的末尾。
     * 重新走 load 流程（新书籍的目录需要联网拉取）；上一个视频若还留在
     * SharedPlayer 交接池里，可无缝接管继续播。
     */
    fun feedSwitch(offset: Int) {
        val feed = feed ?: return
        val urls = feed.urls()
        val currentUrl = book?.bookUrl ?: return
        val pos = urls.indexOf(currentUrl)
        if (pos < 0) return
        val next = pos + offset
        val url = urls.getOrNull(next) ?: run {
            appCtx.toastOnUi(
                if (offset > 0) "已经是列表最后一个" else "已经是列表第一个"
            )
            return
        }
        load(url, feed.startIndexOf(url))
    }
}
