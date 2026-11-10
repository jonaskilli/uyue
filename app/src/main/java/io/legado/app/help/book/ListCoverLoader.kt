package io.legado.app.help.book

import androidx.lifecycle.MutableLiveData
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 搜索 / 发现列表里某本书没有封面时，异步去它的详情页把封面取回来显示。
 *
 * 触发前提是「解析阶段就能确定列表没有封面」：SearchBook.coverUrl 为空。
 * 有些站点搜索页与分类页压根没有 <img>，只有详情页有封面图，这时列表规则怎么写都取不到；
 * 补取复用书源自己的 ruleBookInfo.coverUrl，因此对所有书源通用，不针对特定站点。
 *
 * 有封面的书源一次额外请求都不会发；只有列表封面 URL 加载失败时（404/403）
 * 才由 CoverImageView 的失败回调走 [fillOnLoadFailed] 兜底。
 */
object ListCoverLoader {

    /** 单次结果最多补多少本，避免一页几十本同时打详情页 */
    private const val MAX_PER_PAGE = 20

    /** 单本详情页请求超时 */
    private const val FILL_TIMEOUT = 10_000L

    /** 不挂在 Activity 上：退出界面也不会把补取任务取消掉 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 并发上限，避免和列表封面图片抢带宽 */
    private val semaphore = Semaphore(3)

    /** 本次进程已尝试过的 bookUrl：成功不必重试，失败也不反复打站点 */
    private val tried = Collections.synchronizedSet(mutableSetOf<String>())

    /** 补到封面后通知各列表界面重载该项封面（postValue 可在后台线程调用） */
    val coverRefresh = MutableLiveData<String>()

    /**
     * 递增的「封面版本号」。Compose 列表的 item 参数是 SearchBook，
     * 而它的 equals 只按 bookUrl 判等 —— 就地改 coverUrl 不会让卡片重组，
     * 所以需要一个显式变化的状态把可见项重新推过一次组合。
     */
    private val version = AtomicInteger(0)
    val coverVersion = MutableLiveData(0)

    private val enabled: Boolean
        get() = AppConfig.listCoverFromDetail &&
            !(AppConfig.loadCoverOnlyWifi && !appCtx.isWifiConnect)

    /**
     * 结果入库前调用：把库里已补到的封面合并回本次解析出的新对象。
     * 搜索与发现页每次都用新结果做 REPLACE（SearchBookDao.insert），
     * 不先合并的话，上次补好的封面会被本次的空值覆盖，缓存等于没有。
     */
    fun restoreCachedCovers(books: List<SearchBook>) {
        if (!AppConfig.listCoverFromDetail) return
        for (book in books) {
            if (!book.coverUrl.isNullOrBlank() || book.bookUrl.isBlank()) continue
            val cached = runCatching { appDb.searchBookDao.getSearchBook(book.bookUrl) }
                .getOrNull()?.coverUrl
            if (!cached.isNullOrBlank()) {
                book.coverUrl = cached
            }
        }
    }

    /** 结果已交给 UI 之后调用：对仍然没有封面的条目排队异步补取 */
    fun maybeFill(books: List<SearchBook>) {
        if (!enabled) return
        val targets = ArrayList<SearchBook>()
        for (book in books) {
            if (targets.size >= MAX_PER_PAGE) break
            if (!book.coverUrl.isNullOrBlank() || book.bookUrl.isBlank()) continue
            if (!tried.add(book.bookUrl)) continue
            targets.add(book)
        }
        targets.forEach { launchFill(it) }
    }

    /** 列表已有 coverUrl 但图片加载失败时，强制补一次（同样受 tried 去重保护） */
    fun fillOnLoadFailed(book: SearchBook) {
        if (!enabled || !AppConfig.listCoverOnFailed) return
        if (book.bookUrl.isBlank() || !tried.add(book.bookUrl)) return
        launchFill(book)
    }

    private fun launchFill(book: SearchBook) = scope.launch {
        semaphore.withPermit {
            withTimeout(FILL_TIMEOUT) {
                val source = runCatching { appDb.bookSourceDao.getBookSource(book.origin) }
                    .getOrNull() ?: return@withTimeout
                // 详情页本身没有封面规则就没必要请求
                if (source.getBookInfoRule().coverUrl.isNullOrBlank()) return@withTimeout
                val probe = Book(
                    name = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    origin = book.origin
                )
                // getBookInfoAwait 只发一次详情页请求，不拉目录/正文
                val cover = runCatching {
                    WebBook.getBookInfoAwait(source, probe, canReName = false).coverUrl
                }.getOrNull()?.trim()?.takeIf { it.startsWith("http", true) }
                    ?: return@withTimeout
                // 就地更新列表持有的对象，UI 收到刷新事件后重绑即可显示
                book.coverUrl = cover
                val dbBook = runCatching { appDb.searchBookDao.getSearchBook(book.bookUrl) }
                    .getOrNull() ?: book
                dbBook.coverUrl = cover
                appDb.searchBookDao.insert(dbBook)
                coverRefresh.postValue(book.bookUrl)
                coverVersion.postValue(version.incrementAndGet())
            }
        }
    }
}
