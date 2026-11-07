package io.legado.app.help.video

import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.addType
import io.legado.app.help.book.ensureVideoType
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.http.CookieManager
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 视频播放。
 *
 * 视频/短剧书源的正文规则普遍会自行调用 `java.startBrowser(播放页地址)`，
 * 由书源拉起浏览器的同时带上自己的 header/UA/cookie —— 这是它们的视频能正常播放的前提。
 * 因此这里提供两条路：
 *
 * 1. [play]：原样执行正文规则，让书源拉起内置浏览器播放（书源页面自带选集/播放逻辑时走这条）；
 * 2. [resolveChapterUrl]：拦截书源的 startBrowser，只把地址取回来交给 App 自己的播放器。
 *
 * 走第 2 条时 App 才能接管进度、倍速与选集，但前提是拿到的地址确实是可以直接播的流
 * （见 [isPlayableUrl]）；拿不到或拿到的是网页，就应当退回第 1 条。
 */
object VideoSourcePlayer {

    @Volatile
    private var watching = false

    @Volatile
    private var launched = false

    @Volatile
    private var lastUrl: String? = null

    /** true 表示只取地址、不让书源拉起浏览器 */
    @Volatile
    private var intercept = false

    /**
     * begin/end 是全局状态，[resolveChapterUrl] 若被并发调用（例如同时解析当前集与下一集）
     * 会互相踩踏，这里串行化。
     */
    private val resolveLock = Mutex()

    /** 开始一次「等待书源拉起播放器」的观察窗口 */
    @Synchronized
    private fun begin(interceptBrowser: Boolean) {
        launched = false
        lastUrl = null
        intercept = interceptBrowser
        watching = true
    }

    /** 由 JsExtensions.startBrowser 调用，仅记录，不影响书源行为 */
    @Synchronized
    fun onSourceBrowser(url: String?) {
        if (watching && !url.isNullOrBlank()) {
            launched = true
            // 一次正文规则里书源可能多次拉起浏览器（中间跳转页、封面页等）。
            // 认准能直接播的那个，别让后来的网页地址把它盖掉。
            val current = lastUrl
            if (current == null || (isPlayableUrl(url) && !isPlayableUrl(current))) {
                lastUrl = url
            }
            AppLog.putDebug("书源已拉起播放器: $url")
        }
    }

    /**
     * 当前是否处于「等待书源拉起播放器」的窗口内。
     *
     * 书源自行调用 java.startBrowser 时，JsExtensions 据此决定是否以
     * 无顶栏全屏的方式打开浏览器 —— 绝大多数视频/短剧源走的正是这条路，
     * 若不带这个标志，用户会先看到一个带标题栏的网页再手动点全屏。
     */
    @Synchronized
    fun isWatching(): Boolean = watching

    /**
     * 是否应当拦下书源的 startBrowser。
     *
     * 只有解析模式（[resolveChapterUrl]）才拦截；[play] 需要书源照常拉起浏览器。
     */
    @Synchronized
    fun shouldIntercept(): Boolean = watching && intercept

    /** 结束观察，返回书源拉起播放器时给出的地址；未拉起则为 null */
    @Synchronized
    private fun end(): String? {
        watching = false
        intercept = false
        return if (launched) lastUrl else null
    }

    /**
     * 判断一个地址是否是可以交给 ExoPlayer 直接播放的流。
     *
     * 判定宽松但不放过网页：书源给的可能是 m3u8/mp4 直链，也可能是个网页播放器
     * （例如番茄的 `/api/v1/player?...` 返回 text/html），后者必须由书源自己处理。
     */
    fun isPlayableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith("http", ignoreCase = true)) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") ||
            path.endsWith(".mp4") ||
            path.endsWith(".m4v") ||
            path.endsWith(".webm") ||
            url.contains("/m3u8/", ignoreCase = true) ||
            url.contains("authPath", ignoreCase = true) ||
            url.contains(".m3u8?", ignoreCase = true)
    }

    /** 从正文文本里摘出第一个可播放地址。正文可能是裸地址，也可能是「播放地址：xxx」这类提示文本。 */
    fun extractPlayableUrl(content: String?): String? {
        val text = content?.trim() ?: return null
        if (text.startsWith("http", ignoreCase = true)) {
            val head = text.takeWhile { !it.isWhitespace() }
            return head.takeIf { isPlayableUrl(it) }
        }
        val match = URL_PATTERN.find(text) ?: return null
        return match.value.takeIf { isPlayableUrl(it) }
    }

    private val URL_PATTERN = Regex("""https?://[^\s"'<>\\）)】\]，,]+""")

    /**
     * 准备目录：本地没有章节时才联网拉取并落库。
     *
     * 抽出来是为了让选集 UI 与播放共用同一份目录，不必各拉一次。
     *
     * @param forceRefresh 跳过本地缓存，强制联网重拉。
     * 剧集会持续更新（11 集→12 集），只认本地缓存的话更新出来的新集永远进不来。
     */
    suspend fun prepareChapters(
        book: Book,
        source: BookSource,
        forceRefresh: Boolean = false
    ): List<BookChapter> {
        // 保住 notShelf：下面的 getBookInfoAwait / getChapterListAwait 会 removeAllBookType 清掉该位，
        // 若不补回，原本"不自动上书架"的视频书会被当成普通书入库（表现为自动入架）。
        val wasNotShelf = book.isNotShelf
        if (book.tocUrl.isEmpty()) {
            runCatching { WebBook.getBookInfoAwait(source, book) }
                .onFailure {
                    // 只记日志、不弹提示：详情页规则里的"下载链接"是给 txt/epub 下载用的，
                    // 视频/短剧源压根没有这个字段，解析必然报"下载链接为空"，对播放是噪声。
                    // 真要失败（比如目录也取不到），下面的 getChapterListAwait 会抛出并由上层提示。
                    AppLog.put("详情页出错: ${it.localizedMessage}", it)
                }
        }
        // ===== 后台刷新：只追加新集，绝不覆盖/删除本地缓存目录 =====
        // 原来的 forceRefresh 会 delByBook + 全量 insert。站点延迟、关站或返回
        // 半份列表时，缓存目录就被毁掉、集号错位，已缓存的视频下次就播不了。
        // 刷新失败/结果变短一律沿用旧目录；成功也只把多出来的集追加到尾部，
        // 这样旧集序号恒定，正在播的与已缓存的都不会串位。
        if (forceRefresh) {
            val cached = appDb.bookChapterDao.getChapterList(book.bookUrl)
            val fresh = try {
                WebBook.getChapterListAwait(source, book).getOrThrow()
            } catch (e: Exception) {
                AppLog.put("后台刷新目录失败，沿用本地目录\n${e.localizedMessage}", e)
                return cached
            }
            if (fresh.size <= cached.size) return cached
            book.ensureVideoType()
            if (wasNotShelf) book.addType(BookType.notShelf)
            if (appDb.bookDao.getBook(book.bookUrl) == null) {
                appDb.bookDao.insert(book)
            } else {
                appDb.bookDao.update(book)
            }
            val added = fresh.subList(cached.size, fresh.size).mapIndexed { offset, ch ->
                ch.copy(bookUrl = book.bookUrl, index = cached.size + offset)
            }
            appDb.bookChapterDao.insert(*added.toTypedArray())
            return appDb.bookChapterDao.getChapterList(book.bookUrl)
        }
        var list = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (list.isEmpty()) {
            val oldBook = book.copy()
            val newList = WebBook.getChapterListAwait(source, book).getOrThrow()
            // getBookInfoAwait / getChapterListAwait 会按书源声明的类型重置 type，
            // 清掉 video / notShelf 位。必须在落库前补回，否则看过的视频会被当成普通书入库（自动入架）。
            book.ensureVideoType()
            if (wasNotShelf) book.addType(BookType.notShelf)
            if (oldBook.bookUrl == book.bookUrl) {
                appDb.bookDao.update(book)
            } else {
                appDb.bookDao.replace(oldBook, book)
            }
            appDb.bookChapterDao.delByBook(book.bookUrl)
            // chapters.bookUrl 外键引用 books.bookUrl：书不在书库时，上面的 update/replace
            // 都是静默的（update 影响 0 行不报错），紧接着的 insert 才会炸
            // FOREIGN KEY constraint failed（未入库的搜索结果直接拉目录就会踩到）。
            // 这里统一兜底补一次，免得每个调用点都得自己记着"先入库"。
            if (appDb.bookDao.getBook(book.bookUrl) == null) {
                appDb.bookDao.insert(book)
            }
            appDb.bookChapterDao.insert(*newList.toTypedArray())
            list = appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            // 章节已存在：上方的 getBookInfoAwait 可能已清掉 type/notShelf，统一补回并落库
            book.ensureVideoType()
            if (wasNotShelf) book.addType(BookType.notShelf)
            appDb.bookDao.update(book)
        }
        return list
    }

    /**
     * 准备目录并播放指定书籍。
     *
     * @return true 表示已成功拉起播放
     */
    suspend fun openBook(book: Book, source: BookSource): Boolean {
        val list = prepareChapters(book, source)
        val index = book.durChapterIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        val chapter = list.getOrNull(index)
            ?: throw IllegalStateException("没有可播放的章节")
        return play(book, source, chapter)
    }

    /**
     * 执行正文规则，由书源自行拉起播放器（内置浏览器）。
     *
     * 书源没有拉起但正文返回了地址时，用同一个 [SourceVerificationHelp.startBrowser]
     * 打开，确保请求头、UA、cookie 与书源一致。
     */
    suspend fun play(book: Book, source: BookSource, chapter: BookChapter): Boolean {
        begin(interceptBrowser = true)
        val content = try {
            WebBook.getContentAwait(source, book, chapter, needSave = false)
        } catch (e: Exception) {
            // 必须收尾，否则 watching 会一直挂着，后续任何 startBrowser 都被误判成视频模式
            end()
            throw e
        }
        // 书源若在正文规则里调了 startBrowser/startBrowserAwait，此处即为拉起的地址
        var launched = end()
        if (launched == null && !content.trim().startsWith("http", ignoreCase = true)) {
            // 兼容"首次执行只做初始化、刷新后才拉起播放器"的源（如返回"请刷新查看内容"）
            begin(interceptBrowser = true)
            try {
                WebBook.getContentAwait(source, book, chapter, needSave = false)
                launched = end()
            } catch (e: Exception) {
                end()
                throw e
            }
        }
        val url = launched ?: content.trim().takeIf { it.startsWith("http", ignoreCase = true) }
        if (url != null) {
            SourceVerificationHelp.startBrowser(
                source = source,
                url = url,
                title = chapter.title,
                videoMode = true,
                // 书架直开前先逛源站列表页（tocUrl）把会话 cookie 种上，避免缺会话被 4xx 拒
                preloadUrl = book.tocUrl.takeIf { it.isNotBlank() }
            )
            return true
        }
        throw IllegalStateException(
            "书源未拉起播放器，也没有返回播放地址。\n\n正文返回：\n${content.take(200)}"
        )
    }

    /**
     * 某一集的解析结果。
     *
     * @param directUrl 可直接交给 ExoPlayer 的流地址（m3u8/mp4 等），为 null 表示拿不到直链
     * @param webPlayerUrl 书源拉起的网页播放器地址（不是直链）。
     *   这种源的正确播法是用全屏视频模式浏览器原样打开它，而不是丢给 ExoPlayer
     */
    class ChapterResolve internal constructor(
        val directUrl: String?,
        val webPlayerUrl: String?,
    )

    /**
     * 执行一次正文规则并回收拦截结果。
     * @return (正文文本, 书源拉起的播放器地址)
     */
    private suspend fun runRule(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
    ): Pair<String, String?> {
        begin(interceptBrowser = true)
        val content = try {
            WebBook.getContentAwait(source, book, chapter, needSave = false)
        } catch (e: Exception) {
            end()
            AppLog.putDebug("解析播放地址失败：${e.localizedMessage}")
            return "" to null
        }
        return content to end()
    }

    /**
     * 解析某一集的播放方案，不拉起任何界面。
     *
     * 执行正文规则（拦截窗口期内），根据书源行为给出结果：
     * - 书源 startBrowser/startBrowserAwait 了直链，或正文里写了直链 → [ChapterResolve.directUrl]
     * - 书源 startBrowser/startBrowserAwait 了网页播放器 → [ChapterResolve.webPlayerUrl]
     * - 都没有 → 两者皆 null，调用方走原解析链路（mpd 文本/正文求值）
     *
     * 有些源第一次执行规则只做初始化（返回"请刷新查看内容"等占位文本），
     * 刷新后才会拉起播放器——第一次什么都没拿到时自动补一次刷新。
     */
    suspend fun resolveChapter(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
    ): ChapterResolve = resolveLock.withLock {
        val (content, intercepted) = runRule(book, source, chapter)
        val direct = intercepted?.takeIf { isPlayableUrl(it) } ?: playableFromContent(content, source)
        val webPlayer = if (direct == null) intercepted else null
        if (direct != null || webPlayer != null) {
            return@withLock ChapterResolve(direct, webPlayer)
        }
        // 自动补一次"刷新"
        val (content2, intercepted2) = runRule(book, source, chapter)
        val direct2 = intercepted2?.takeIf { isPlayableUrl(it) }
            ?: playableFromContent(content2, source)
        ChapterResolve(
            directUrl = direct2,
            webPlayerUrl = if (direct2 == null) intercepted2 else null
        )
    }

    /**
     * 从正文文本里取可播放地址：后缀认得出就直接采信；认不出、但正文就是一个裸地址时，
     * 用一次真实请求确认它到底是不是媒体。
     *
     * 视频源普遍把真实文件名藏在末尾参数里（如 `https://x/proxy?url=...&f=1.m3u8`），
     * 这类地址本身就是 m3u8 清单，只看后缀会被误判成网页播放器，白白退回内置浏览器。
     * 真的是网页时探测会返回 false，行为与之前一致（仍走内置浏览器）。
     */
    private suspend fun playableFromContent(content: String, source: BookSource): String? {
        val url = extractPlayableUrl(content)
        if (url != null) return url
        val text = content.trim()
        if (!text.startsWith("http", ignoreCase = true) || text.any { it.isWhitespace() }) {
            return null
        }
        val headers = HashMap(source.getHeaderMap(true))
        // 会话 cookie：缺了会被源站直接 4xx，探测结果自然也不准
        if (source.enabledCookieJar == true) {
            headers[CookieManager.cookieJarHeader] = "1"
        }
        return text.takeIf { VideoUrlSniffer.isMediaUrl(it, headers) }
    }

    /**
     * 解析某一集的播放地址，不拉起任何界面。
     *
     * 只适用于正文规则会自行 `java.startBrowser(播放地址)`、或正文直接返回地址的源。
     * 返回 null 表示这条路走不通（例如书源给的是网页播放器），调用方应退回 [play]。
     */
    suspend fun resolveChapterUrl(
        book: Book,
        source: BookSource,
        chapter: BookChapter,
    ): String? = resolveChapter(book, source, chapter).directUrl
}
