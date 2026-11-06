package io.legado.app.ui.main.explore

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.data.appDb
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.source.exploreKinds
import kotlinx.coroutines.CancellationException
import java.util.Collections
import io.legado.app.model.webBook.WebBook

/**
 * 左侧 75% 内容区的 ViewModel：一个书源 = 一组分类 + 一页带封面的书籍。
 * 复用项目原有的 exploreKinds() 分类解析与 WebBook.exploreBookAwait() 请求，
 * 不改动旧版 ExploreShowActivity / ExploreShowViewModel。
 */
class ExplorePaneViewModel(application: Application) : BaseViewModel(application) {

    val sourceName = MutableLiveData("")
    val kinds = MutableLiveData<List<ExploreKind>>(emptyList())
    val books = MutableLiveData<List<SearchBook>>(emptyList())
    val loading = MutableLiveData(false)
    val message = MutableLiveData<String?>(null)
    val page = MutableLiveData(1)

    private var source: BookSource? = null
    private var sourceUrl: String? = null
    private var currentUrl: String? = null
    /** 仅用于区分发现页 / 视频页各自的网格列表偏好键 */
    var videoPage = false
    /** 自动分页：原子防重复 + 空结果停止 + 换源代际校验 */
    /** 网格/列表状态按页面分别记住，退出 APP 再进不再回到列表 */
    var gridMode: Boolean
        get() = context.getPrefBoolean(
            if (videoPage) "videoPaneGridMode" else "explorePaneGridMode", false
        )
        set(value) {
            context.putPrefBoolean(
                if (videoPage) "videoPaneGridMode" else "explorePaneGridMode", value
            )
        }

    @Volatile
    private var loadingMore = false
    private var hasMore = true
    private var generation = 0
    /** 当前源所有在飞的请求；切源时全部 cancel，OkHttp 底层会真的 call.cancel() */
    private val inflight = Collections.synchronizedSet(mutableSetOf<Coroutine<*>>())

    /**
     * 切源 / 切分类 / 刷新：强行关闭上一个源的全部活动。
     * 旧版与上游 Max 版都只做「丢弃旧结果」，后台请求仍会跑完并占用连接，
     * 慢网络下连点几个源就会堆积、拖慢甚至卡住页面。这里直接掐断协程。
     */
    private fun cancelInflight() {
        synchronized(inflight) {
            inflight.forEach { runCatching { it.cancel() } }
            inflight.clear()
        }
        generation++
        loadingMore = false
        hasMore = true
        loading.postValue(false)
        message.postValue(null)
    }

    private fun track(job: Coroutine<*>) {
        synchronized(inflight) {
            inflight.removeAll { !it.isActive }
            inflight.add(job)
        }
    }

    /** 右侧点书源名时调用：清旧数据、解析分类、加载第一个 url 类分类 */
    fun selectSource(url: String) {
        sourceUrl = url
        cancelInflight()
        track(execute {
            try {
                val bookSource = appDb.bookSourceDao.getBookSource(url)
                if (bookSource == null || sourceUrl != url) return@execute
                source = bookSource
                sourceName.postValue(bookSource.bookSourceName)
                books.postValue(emptyList())
                message.postValue(null)
                val list = bookSource.exploreKinds()
                if (sourceUrl != url) return@execute
                // 保留全部分类：展开面板要按书源原本的 Flexbox 排布显示
                // （url / button / text / select / toggle），横向条另行过滤
                kinds.postValue(list)
                val first = list.firstOrNull {
                    it.type == ExploreKind.Type.url && !it.url.isNullOrBlank()
                }
                if (first?.url != null) {
                    loadBooks(first.url, 1)
                } else {
                    message.postValue("该书源没有可加载的分类")
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@execute
                message.postValue(e.localizedMessage ?: "加载失败")
            }
        })
    }

    fun loadKind(kind: ExploreKind) {
        val url = kind.url ?: return
        cancelInflight()
        page.postValue(1)
        loadBooks(url, 1)
    }

    fun refresh() {
        val url = currentUrl ?: return
        cancelInflight()
        loadBooks(url, page.value ?: 1)
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        val url = currentUrl ?: return
        val src = source ?: return
        val target = (page.value ?: 1) + 1
        val gen = ++generation
        loadingMore = true
        loading.postValue(true)
        track(execute {
            try {
                val result = WebBook.exploreBookAwait(src, url, target)
                // 切源 / 切分类后，旧请求的结果不能追加到新列表里
                if (gen != generation) return@execute
                appDb.searchBookDao.insert(*result.toTypedArray())
                if (result.isEmpty()) hasMore = false
                val all = books.value.orEmpty() + result
                books.postValue(all)
                page.postValue(target)
                message.postValue(null)
            } catch (e: Exception) {
                if (e is CancellationException || gen != generation) return@execute
                message.postValue(e.localizedMessage ?: "加载失败")
            } finally {
                // 被取消或已过代的请求不能关掉新请求的 loading
                if (gen == generation) {
                    loadingMore = false
                    loading.postValue(false)
                }
            }
        })
    }

    private fun loadBooks(url: String, targetPage: Int, append: Boolean = false) {
        val bookSource = source ?: return
        // 每次换源 / 换分类 / 刷新都作废旧请求，避免网络慢时上一个源的
        // 结果延迟返回，突然冒到新源的列表里
        val gen = ++generation
        val srcKey = sourceUrl
        currentUrl = url
        if (!append) books.postValue(emptyList())
        message.postValue(null)
        loading.postValue(true)
        track(execute {
            try {
                val result = WebBook.exploreBookAwait(bookSource, url, targetPage)
                // 只有「仍是当前这一次请求」才允许写界面
                if (gen != generation || sourceUrl != srcKey || currentUrl != url) {
                    return@execute
                }
                // 详情页 BookInfoViewModel 只查库，发现结果必须入库
                appDb.searchBookDao.insert(*result.toTypedArray())
                val old = if (append) books.value.orEmpty() else emptyList()
                val all = old + result
                books.postValue(all)
                page.postValue(targetPage)
                message.postValue(null)
            } catch (e: Exception) {
                if (e is CancellationException) return@execute
                if (gen == generation) {
                    message.postValue(e.localizedMessage ?: "加载失败")
                }
            } finally {
                // 旧请求不能把新请求的 loading 状态提前关掉
                if (gen == generation) loading.postValue(false)
            }
        })
    }
}
