package io.legado.app.ui.main.video

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.source.SourceHelp
import io.legado.app.model.webBook.SearchModel
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.ConflateLiveData
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapLatest
import java.util.concurrent.ConcurrentHashMap

/**
 * 视频发现页。
 *
 * 顶部搜索框搜的是**视频名**：在所有视频源里并发搜索（范围见 [SearchScope.video]），
 * 结果流式返回——搜到一批就推一批，不用等所有源跑完。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModel(application: Application) : BaseViewModel(application) {

    /** 书架里的书（用于结果标记"已在书架"） */
    private val bookshelf: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** 搜索结果：分批推送，直接塞给 SearchAdapter */
    val searchBooksLiveData = ConflateLiveData<List<SearchBook>>(1000)
    val isSearchingLiveData = MutableLiveData<Boolean>()
    /** 搜索结束；true = 一个都没搜到 */
    val searchFinishLiveData = MutableLiveData<Boolean>()

    private var searchID = 0L
    private var searchKey = ""
    private val searchScope = SearchScope.video()
    private val searchModel = SearchModel(viewModelScope, object : SearchModel.CallBack {

        override fun getSearchScope(): SearchScope = searchScope

        override fun onSearchStart() {
            isSearchingLiveData.postValue(true)
        }

        override fun onSearchSuccess(searchBooks: List<SearchBook>) {
            searchBooksLiveData.postValue(searchBooks)
        }

        override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
            isSearchingLiveData.postValue(false)
            searchFinishLiveData.postValue(isEmpty)
        }

        override fun onSearchCancel(exception: Throwable?) {
            isSearchingLiveData.postValue(false)
            exception?.let { context.toastOnUi(it.localizedMessage) }
        }
    })

    init {
        execute {
            appDb.bookDao.flowAll().mapLatest { books ->
                val keys = arrayListOf<String>()
                books.filterNot { it.isNotShelf }.forEach {
                    keys.add("${it.name}-${it.author}")
                    keys.add(it.name)
                    keys.add(it.bookUrl)
                }
                keys
            }.catch {
                AppLog.put("视频页获取书架列表失败\n${it.localizedMessage}", it)
            }.collect {
                bookshelf.clear()
                bookshelf.addAll(it)
            }
        }.onError {
            AppLog.put("视频页加载书架数据失败", it)
        }
    }

    fun isInBookShelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return bookshelf.contains(key) || bookshelf.contains(book.bookUrl)
    }

    /** 在所有视频源里搜 [key] */
    fun search(key: String) {
        execute {
            searchModel.cancelSearch()
            searchID = System.currentTimeMillis()
            searchBooksLiveData.postValue(emptyList())
            searchKey = key
            searchModel.search(searchID, searchKey)
        }
    }

    fun stopSearch() {
        searchModel.cancelSearch()
    }

    fun topSource(bookSource: io.legado.app.data.entities.BookSourcePart) {
        execute {
            val minXh = appDb.bookSourceDao.minOrder
            bookSource.customOrder = minXh - 1
            appDb.bookSourceDao.upOrder(bookSource)
        }
    }

    fun deleteSource(source: io.legado.app.data.entities.BookSourcePart) {
        execute {
            SourceHelp.deleteBookSource(source.bookSourceUrl)
        }
    }

    override fun onCleared() {
        super.onCleared()
        searchModel.close()
    }
}
