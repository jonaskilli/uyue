package io.legado.app.ui.book.changesource

import android.app.Application
import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.dao.getVideoSourceParts
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.primaryStr
import io.legado.app.help.book.releaseHtmlData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.SourceConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.isVideoSource
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.internString
import io.legado.app.utils.mapParallel
import io.legado.app.utils.mapParallelSafe
import io.legado.app.utils.onEachIndexed
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

@Suppress("MemberVisibilityCanBePrivate")
open class ChangeBookSourceViewModel(application: Application) : BaseViewModel(application) {
    private val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    val searchStateData = MutableLiveData<Boolean>()
    var searchFinishCallback: ((isEmpty: Boolean) -> Unit)? = null
    var name: String = ""
    var author: String = ""
    private var fromReadBookActivity = false
    private var oldBook: Book? = null
    private var screenKey: String = ""
    private var bookSourceParts = arrayListOf<BookSourcePart>()
    val totalSourceCount: Int
        get() = bookSourceParts.size
    private var searchBookList = arrayListOf<SearchBook>()
    private val searchBooks = Collections.synchronizedList(arrayListOf<SearchBook>())
    private val tocMap = ConcurrentHashMap<String, List<BookChapter>>()

    /**
     * 各书源结果的章节数（key: primaryStr），用于"按章节数排序"
     */
    private val chapterCountMap = ConcurrentHashMap<String, Int>()
    private val _changeSourceProgress = MutableStateFlow(0 to "")
    val changeSourceProgress = _changeSourceProgress.asStateFlow()
    private var tocMapChapterCount = 0
    private val contentProcessor by lazy {
        ContentProcessor.get(oldBook!!)
    }
    private var searchCallback: SourceCallback? = null
    private val chapterNumRegex = "^\\[(\\d+)]".toRegex()
    private val whitespaceRegex = Regex("\\s+")

    /**
     * 关键词校验参数，仅保存在内存中，换源界面关闭（ViewModel 销毁）即失效，不持久化
     */
    private var keywordCheck: KeywordCheck? = null
    val isKeywordCheckActive: Boolean
        get() = keywordCheck != null

    private data class KeywordCheck(
        val chapterKey: String,
        val mustHave: List<String>,
        val forbidden: List<String>
    )

    private val comparatorBase by lazy {
        compareByDescending<SearchBook> { getBookScore(it) }
            .thenByDescending { SourceConfig.getSourceScore(it.origin) }
    }
    private val defaultComparator by lazy {
        comparatorBase.thenBy { it.originOrder }
    }
    private val wordCountComparator by lazy {
        comparatorBase.thenByDescending { it.chapterWordCount > 1000 }
            .thenByDescending { getChapterNum(it.chapterWordCountText) }
            .thenByDescending { it.chapterWordCount }
            .thenBy { it.originOrder }
    }
    private val chapterCountComparator by lazy {
        comparatorBase.thenByDescending { chapterCountMap[it.primaryStr()] ?: 0 }
            .thenBy { it.originOrder }
    }
    private var task: Job? = null
    val bookMap = ConcurrentHashMap<String, Book>()

    /**
     * 视频换源时只搜这些 origin 的视频源；null = 不限制（普通换源，搜全部启用书源）。
     * 懒加载：第一次用到时才查库（dialog 打开时先读历史结果，也要按它过滤）。
     */
    private var videoSourceOrigins: Set<String>? = null
    private var videoSourceOriginsReady = false

    /**
     * 是否按视频源范围换源：当前书是视频（书架里的视频分类、视频详情页长按进来的都是），
     * 或它的书源按视频口径判定（类型=4 ∥ 分组/书名关键词 ∥ 规则含 m3u8/mp4）。
     *
     * 全局换源会把几千个小说源全跑一遍（每个源发一次搜索请求），视频名在小说源里
     * 基本搜不到东西却要干等超时，慢到几乎不可用，所以视频只搜视频源。
     * 口径与视频主页、搜索页的「所有视频源」完全一致（见 [getVideoSourceParts]）。
     */
    private fun videoOrigins(): Set<String>? {
        if (!videoSourceOriginsReady) {
            videoSourceOriginsReady = true
            val book = oldBook
            videoSourceOrigins = if (book != null &&
                (book.isVideo || book.getBookSource()?.isVideoSource() == true)
            ) {
                appDb.bookSourceDao.getVideoSourceParts()
                    .map { it.bookSourceUrl }
                    .toSet()
            } else {
                null
            }
        }
        return videoSourceOrigins
    }

    /** 参与搜索的书源：视频换源只拿视频源，其余拿全部启用书源 */
    private fun enabledParts(videoOrigins: Set<String>?): List<BookSourcePart> =
        if (videoOrigins == null) {
            appDb.bookSourceDao.allEnabledPart
        } else {
            appDb.bookSourceDao.getVideoSourceParts()
        }

    val searchDataFlow = callbackFlow {

        searchCallback = object : SourceCallback {

            override fun searchSuccess(searchBook: SearchBook) {
                searchBook.releaseHtmlData()
                appDb.searchBookDao.insert(searchBook)
                when {
                    screenKey.isEmpty() -> searchBooks.add(searchBook)
                    searchBook.name.contains(screenKey) -> searchBooks.add(searchBook)
                    else -> return
                }
                trySend(arrayOf(searchBooks))
            }

            override fun upAdapter() {
                trySend(arrayOf(searchBooks))
            }

        }

        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            trySend(arrayOf(searchBooks))
        }

        if (searchBooks.isEmpty()) {
            startSearch()
        }

        awaitClose {
            searchCallback = null
        }
    }.map {
        kotlin.runCatching {
            val comparator = when {
                AppConfig.changeSourceSortByChapterCount -> chapterCountComparator
                AppConfig.changeSourceLoadWordCount -> wordCountComparator
                else -> defaultComparator
            }
            searchBooks.sortedWith(comparator)
        }.onFailure {
            AppLog.put("换源排序出错\n${it.localizedMessage}", it)
        }.getOrDefault(searchBooks)
    }.flowOn(IO)

    override fun onCleared() {
        super.onCleared()
        searchPool?.close()
    }

    @CallSuper
    open fun initData(arguments: Bundle?, book: Book?, fromReadBookActivity: Boolean) {
        arguments?.let { bundle ->
            bundle.getString("name")?.let {
                name = it
            }
            bundle.getString("author")?.let {
                author = it.replace(AppPattern.authorRegex, "")
            }
            this.fromReadBookActivity = fromReadBookActivity
            oldBook = book
        }
    }

    private fun initSearchPool() {
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun refresh(): Boolean {
        getDbSearchBooks().let {
            searchBooks.clear()
            searchBooks.addAll(it)
            searchCallback?.upAdapter()
        }
        return searchBooks.isEmpty()
    }

    /**
     * 搜索书籍
     */
    fun startSearch() {
        execute {
            stopSearch()
            if (searchBooks.isNotEmpty()) {
                appDb.searchBookDao.delete(*searchBooks.toTypedArray())
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            _changeSourceProgress.value = 0 to ""
            val searchGroup = AppConfig.searchGroup
            val videoOrigins = videoOrigins()
            if (searchGroup.isBlank()) {
                bookSourceParts.addAll(enabledParts(videoOrigins))
            } else {
                var sources = appDb.bookSourceDao.getEnabledPartByGroup(searchGroup)
                if (videoOrigins != null) {
                    sources = sources.filter { it.bookSourceUrl in videoOrigins }
                }
                if (sources.isEmpty()) {
                    // 该分组下没有可用源（视频换源时即该分组没有视频源）：重置为全部分组
                    AppConfig.searchGroup = ""
                    sources = enabledParts(videoOrigins)
                }
                bookSourceParts.addAll(sources)
            }
            initSearchPool()
            search()
        }
    }

    fun startSearch(origin: String) {
        execute {
            stopSearch()
            bookSourceParts.clear()
            tocMap.clear()
            bookMap.clear()
            tocMapChapterCount = 0
            bookSourceParts.add(appDb.bookSourceDao.getBookSourcePart(origin)!!)
            searchBooks.removeIf { it.origin == origin }
            initSearchPool()
            search()
        }
    }

    /**
     * 关键词校验搜索：只保留校验通过的书源结果。
     * 校验参数仅保存在内存中，随换源界面关闭而失效。
     */
    fun startKeywordSearch(chapterKey: String, mustHave: List<String>, forbidden: List<String>) {
        keywordCheck = KeywordCheck(chapterKey, mustHave, forbidden)
        startSearch()
    }

    private fun search() {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallel(threadCount) {
                try {
                    withTimeout(60000L) {
                        search(it)
                    }
                } catch (_: Throwable) {
                    currentCoroutineContext().ensureActive()
                }
                it
            }.onEachIndexed { index, value ->
                _changeSourceProgress.update { _ ->
                    index + 1 to value.bookSourceName
                }
            }.onCompletion {
                ensureActive()
                searchStateData.postValue(false)
                searchFinishCallback?.invoke(searchBooks.isEmpty())
            }.catch {
                AppLog.put("换源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend fun search(source: BookSource) {
        val checkAuthor = AppConfig.changeSourceCheckAuthor
        val loadInfo = AppConfig.changeSourceLoadInfo
        val loadToc = AppConfig.changeSourceLoadToc
        val loadWordCount = AppConfig.changeSourceLoadWordCount
        val resultBooks = WebBook.searchBookAwait(
            source, name,
            filter = { fName, fAuthor ->
                fName == name && (!checkAuthor || fAuthor.contains(author))
            })
        resultBooks.forEach { searchBook ->
            when {
                loadInfo || loadToc || loadWordCount
                        || keywordCheck != null
                        || AppConfig.changeSourceSortByChapterCount -> {
                    loadBookInfo(source, searchBook.toBook())
                }

                else -> {
                    searchCallback?.searchSuccess(searchBook)
                }
            }
        }
    }

    private suspend fun loadBookInfo(source: BookSource, book: Book) {
        if (book.tocUrl.isEmpty()) {
            WebBook.getBookInfoAwait(source, book)
        }
        if (AppConfig.changeSourceLoadToc || AppConfig.changeSourceLoadWordCount
            || AppConfig.changeSourceSortByChapterCount || keywordCheck != null
        ) {
            loadBookToc(source, book)
        } else {
            //从详情页里获取最新章节
            val searchBook = book.toSearchBook()
            searchCallback?.searchSuccess(searchBook)
        }
    }

    private suspend fun loadBookToc(source: BookSource, book: Book) {
        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        for (chapter in chapters) {
            chapter.internString()
        }
        if (tocMapChapterCount < 30000) {
            tocMapChapterCount += chapters.size
            tocMap[book.primaryStr()] = chapters
        }
        chapterCountMap[book.primaryStr()] = chapters.size
        bookMap[book.primaryStr()] = book
        book.releaseHtmlData()
        when {
            keywordCheck != null -> {
                loadBookKeywordCheck(source, book, chapters)
            }

            AppConfig.changeSourceLoadWordCount -> {
                loadBookWordCount(source, book, chapters)
            }

            else -> {
                val searchBook = book.toSearchBook()
                searchCallback?.searchSuccess(searchBook)
            }
        }
    }

    /**
     * 关键词校验：在目录中定位校验章节，取正文检查禁词/必含词。
     * 只有校验通过的结果才会进列表，不通过（含禁词、缺必含词、找不到章节、取正文失败）直接丢弃。
     */
    private suspend fun loadBookKeywordCheck(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ) {
        val check = keywordCheck ?: return
        val key = check.chapterKey.replace(whitespaceRegex, "")
        val index = chapters.indexOfFirst {
            !it.isVolume && it.title.replace(whitespaceRegex, "").contains(key)
        }
        if (index < 0) {
            AppLog.put("关键词校验：${source.bookSourceName} 未找到包含「${check.chapterKey}」的章节，已过滤")
            return
        }
        val content = try {
            WebBook.getContentAwait(
                source, book, chapters[index],
                chapters.getOrNull(index + 1)?.url,
                false // 不写入本地缓存，避免给待淘汰书源留下垃圾缓存
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppLog.put("关键词校验：${source.bookSourceName} 获取正文出错\n${t.localizedMessage}", t)
            return
        }
        val passed = check.forbidden.none { content.contains(it) }
                && check.mustHave.all { content.contains(it) }
        if (passed) {
            searchCallback?.searchSuccess(book.toSearchBook())
        }
    }

    private suspend fun loadBookWordCount(
        source: BookSource,
        book: Book,
        chapters: List<BookChapter>
    ) = coroutineScope {
        val chapterIndex = if (fromReadBookActivity) {
            BookHelp.getDurChapter(oldBook!!, chapters)
        } else {
            chapters.lastIndex
        }
        val bookChapter = chapters[chapterIndex]
        var title = bookChapter.title.trim()
        if (title.length > 20) {
            title = title.substring(0, 20) + "…"
        }
        val startTime = System.currentTimeMillis()
        val pair = try {
            val nextChapterUrl = chapters.getOrNull(chapterIndex + 1)?.url
            var content = WebBook.getContentAwait(source, book, bookChapter, nextChapterUrl, false)
            content = contentProcessor.getContent(oldBook!!, bookChapter, content, false).toString()
            val len = content.length
            len to "[${chapterIndex + 1}] ${title}\n字数：${len}"
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            -1 to "[${chapterIndex + 1}] ${title}\n获取字数失败：${t.localizedMessage}"
        }
        val endTime = System.currentTimeMillis()
        val searchBook = book.toSearchBook().apply {
            chapterWordCountText = pair.second
            chapterWordCount = pair.first
            respondTime = (endTime - startTime).toInt()
        }
        searchCallback?.searchSuccess(searchBook)
    }

    fun onLoadWordCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList(true)
        }
    }

    /**
     * 勾选"按章节数排序"后，重新加载列表以统计各结果的章节数（数量多的排前面）
     */
    fun onLoadSortByChapterCountChecked(isChecked: Boolean) {
        if (isChecked) {
            startRefreshList()
        }
    }

    /**
     * 刷新列表
     */
    fun startRefreshList(onlyRefreshNoWordCountBook: Boolean = false) {
        execute {
            stopSearch()
            searchBookList.clear()
            if (onlyRefreshNoWordCountBook) {
                searchBooks.filterTo(searchBookList) {
                    it.chapterWordCountText == null
                }
                searchBooks.removeIf { it.chapterWordCountText == null }
            } else {
                searchBookList.addAll(searchBooks)
                searchBooks.clear()
            }
            searchCallback?.upAdapter()
            initSearchPool()
            refreshList()
        }
    }

    private fun refreshList() {
        task = viewModelScope.launch(searchPool!!) {
            flow {
                for (searchBook in searchBookList) {
                    emit(searchBook)
                }
            }.onStart {
                searchStateData.postValue(true)
            }.mapParallelSafe(threadCount) {
                val source = appDb.bookSourceDao.getBookSource(it.origin)!!
                withTimeout(60000L) {
                    loadBookInfo(source, it.toBook())
                }
            }.onCompletion {
                searchStateData.postValue(false)
            }.catch {
                AppLog.put("换源刷新列表出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private fun getDbSearchBooks(): List<SearchBook> {
        val list = if (screenKey.isEmpty()) {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceByGroup(
                    name, author, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceByGroup(
                    name, "", AppConfig.searchGroup
                )
            }
        } else {
            if (AppConfig.changeSourceCheckAuthor) {
                appDb.searchBookDao.changeSourceSearch(
                    name, author, screenKey, AppConfig.searchGroup
                )
            } else {
                appDb.searchBookDao.changeSourceSearch(
                    name, "", screenKey, AppConfig.searchGroup
                )
            }
        }
        // 视频换源时，库里的历史结果也可能混着小说源（搜索记录是全应用共用的），
        // 按视频源 origin 过滤，保证列表干净
        val videoOrigins = videoOrigins()
        return if (videoOrigins == null) list else list.filter { it.origin in videoOrigins }
    }

    /**
     * 筛选
     */
    fun screen(key: String?) {
        screenKey = key?.trim() ?: ""
        execute {
            getDbSearchBooks().let {
                searchBooks.clear()
                searchBooks.addAll(it)
                searchCallback?.upAdapter()
            }
        }
    }

    fun startOrStopSearch() {
        if (task == null || !task!!.isActive) {
            startSearch()
        } else {
            stopSearch()
        }
    }

    fun stopSearch() {
        task?.cancel()
        searchPool?.close()
        searchStateData.postValue(false)
    }

    fun getToc(
        book: Book,
        onSuccess: (toc: List<BookChapter>, source: BookSource) -> Unit,
        onError: (e: Throwable) -> Unit
    ): Coroutine<Pair<List<BookChapter>, BookSource>> {
        return execute {
            val toc = tocMap[book.primaryStr()]
            if (toc != null) {
                val source = appDb.bookSourceDao.getBookSource(book.origin)
                return@execute Pair(toc, source!!)
            }
            val result = getToc(book).getOrThrow()
            tocMap[book.primaryStr()] = result.first
            return@execute result
        }.onSuccess {
            onSuccess.invoke(it.first, it.second)
        }.onError {
            onError.invoke(it)
        }
    }

    suspend fun getToc(book: Book): Result<Pair<List<BookChapter>, BookSource>> {
        return kotlin.runCatching {
            val source = appDb.bookSourceDao.getBookSource(book.origin)
                ?: throw NoStackTraceException("书源不存在")
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
            val toc = WebBook.getChapterListAwait(source, book).getOrThrow()
            Pair(toc, source)
        }
    }

    fun disableSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                source.enabled = false
                appDb.bookSourceDao.update(source)
            }
            searchBooks.remove(searchBook)
            searchCallback?.upAdapter()
        }
    }

    fun topSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val minOrder = appDb.bookSourceDao.minOrder - 1
                source.customOrder = minOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun bottomSource(searchBook: SearchBook) {
        execute {
            appDb.bookSourceDao.getBookSource(searchBook.origin)?.let { source ->
                val maxOrder = appDb.bookSourceDao.maxOrder + 1
                source.customOrder = maxOrder
                searchBook.originOrder = source.customOrder
                appDb.bookSourceDao.update(source)
                updateSource(searchBook)
            }
            searchCallback?.upAdapter()
        }
    }

    fun updateSource(searchBook: SearchBook) {
        appDb.searchBookDao.update(searchBook)
    }

    fun del(searchBook: SearchBook) {
        execute {
            SourceHelp.deleteBookSource(searchBook.origin)
            appDb.searchBookDao.delete(searchBook)
        }
        searchBooks.remove(searchBook)
        searchCallback?.upAdapter()
    }

    /**
     * 删除该书源条目对应书籍的本地已缓存章节
     */
    fun deleteCache(searchBook: SearchBook) {
        execute {
            BookHelp.clearCache(searchBook.toBook())
        }
    }

    fun autoChangeSource(
        bookType: Int?,
        onSuccess: (book: Book, toc: List<BookChapter>, source: BookSource) -> Unit
    ) {
        execute {
            searchBooks.forEach {
                if (it.type == bookType) {
                    val book = it.toBook()
                    val result = getToc(book).getOrNull()
                    if (result != null) {
                        return@execute Triple(book, result.first, result.second)
                    }
                }
            }
            throw NoStackTraceException("没有有效源")
        }.onSuccess {
            onSuccess.invoke(it.first, it.second, it.third)
        }.onError {
            context.toastOnUi("自动换源失败\n${it.localizedMessage}")
        }
    }

    fun setBookScore(searchBook: SearchBook, score: Int) {
        execute {
            SourceConfig.setBookScore(searchBook.origin, searchBook.name, searchBook.author, score)
            searchCallback?.upAdapter()
        }
    }

    fun getBookScore(searchBook: SearchBook): Int {
        return SourceConfig.getBookScore(searchBook.origin, searchBook.name, searchBook.author)
    }

    private fun getChapterNum(wordCountText: String?): Int {
        wordCountText ?: return -1
        return chapterNumRegex.find(wordCountText)?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    interface SourceCallback {

        fun searchSuccess(searchBook: SearchBook)

        fun upAdapter()

    }

}