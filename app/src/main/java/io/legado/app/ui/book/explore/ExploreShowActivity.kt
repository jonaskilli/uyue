package io.legado.app.ui.book.explore

import android.os.Bundle
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityExploreShowBinding
import io.legado.app.databinding.ViewLoadMoreBinding
import io.legado.app.help.book.addType
import io.legado.app.help.book.ensureVideoType
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.removeType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.source.isVideoSource
import io.legado.app.help.video.VideoFeedStore
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.video.SourceVideoPlayerActivity
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.recycler.LoadMoreView
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 发现列表
 */
class ExploreShowActivity : VMBaseActivity<ActivityExploreShowBinding, ExploreShowViewModel>(),
    ExploreShowAdapter.CallBack {
    override val binding by viewBinding(ActivityExploreShowBinding::inflate)
    override val viewModel by viewModels<ExploreShowViewModel>()

    private val adapter by lazy { ExploreShowAdapter(this, this) }
    private val loadMoreView by lazy { LoadMoreView(this) }
    private val loadMoreViewTop by lazy { LoadMoreView(this) }
    private var oldPage = -1
    private var isClearAll = false
    private val menuPage by lazy {
        binding.titleBar.menu.add(getString(R.string.menu_page, 1)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val page = viewModel.pageLiveData.value ?: 1
                NumberPickerDialog(this@ExploreShowActivity)
                    .setTitle(getString(R.string.change_page))
                    .setMaxValue(999)
                    .setMinValue(1)
                    .setValue(page)
                    .show {
                        if (page != it) {
                            if (oldPage == -1 && it != 1) { //初次添加头
                                adapter.addHeaderView {
                                    ViewLoadMoreBinding.bind(loadMoreViewTop)
                                }
                            } else if (it != 1) { //把头显示出来
                                val layoutParams = loadMoreViewTop.layoutParams
                                if (layoutParams?.height == 0) {
                                    layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    loadMoreViewTop.layoutParams = layoutParams
                                }
                            }
                            oldPage = it
                            viewModel.skipPage(it)
                            isClearAll = true
                            adapter.clearItems() //清空，然后会自动触发scrollToBottom
                            if (!loadMoreView.hasMore) { //强制触发
                                scrollToBottom(true)
                            }
                        }
                    }
                true
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = intent.getStringExtra("exploreName")
        initRecyclerView()
        viewModel.booksData.observe(this) { upData(it) }
        viewModel.addBooksData.observe(this) { upDataTop(it) }
        viewModel.initData(intent)
        viewModel.errorLiveData.observe(this) {
            loadMoreView.error(it)
        }
        viewModel.errorTopLiveData.observe(this) {
            loadMoreViewTop.error(it)
        }
        viewModel.upAdapterLiveData.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf(it to null))
        }
        io.legado.app.help.book.ListCoverLoader.coverRefresh.observe(this) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount, bundleOf("cover" to null))
        }
        viewModel.pageLiveData.observe(this) {
            menuPage.title = getString(R.string.menu_page, it)
        }
    }

    private fun initRecyclerView() {
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        adapter.addFooterView {
            ViewLoadMoreBinding.bind(loadMoreView)
        }
        loadMoreView.startLoad()
        loadMoreView.setOnClickListener {
            if (!loadMoreView.isLoading) {
                scrollToBottom(true)
            }
        }
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!recyclerView.canScrollVertically(1)) {
                    scrollToBottom()
                } else if (!recyclerView.canScrollVertically(-1) && dy < 0) {
                    scrollToTop()
                }
            }
        })
    }

    private fun scrollToBottom(forceLoad: Boolean = false) {
        if ((loadMoreView.hasMore && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreView.hasMore()
            viewModel.explore()
        }
    }

    private fun scrollToTop(forceLoad: Boolean = false) {
        if ((oldPage > 1 && !loadMoreView.isLoading && !loadMoreViewTop.isLoading) || forceLoad) {
            loadMoreViewTop.hasMore()
            oldPage--
            viewModel.explore(oldPage)
        }
    }

    private fun upData(books: List<SearchBook>) {
        loadMoreView.stopLoad()
        if (books.isEmpty() && adapter.isEmpty()) {
            loadMoreView.noMore(getString(R.string.empty))
        } else if (adapter.getActualItemCount() == books.size) {
            loadMoreView.noMore()
        } else {
            adapter.setItems(books)
            if (isClearAll) { //全清空后,加了头,位置下移一个
                val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
                layoutManager.scrollToPositionWithOffset(1, 0)
                isClearAll = false
            }
        }
    }

    private fun upDataTop(books: List<SearchBook>) {
        loadMoreViewTop.stopLoad()
        adapter.addItems(0, books)
        val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
        if (layoutManager.findFirstVisibleItemPosition() <= 1) { //顶部刷新,未滚动，矫正位置
            layoutManager.scrollToPositionWithOffset(books.size, 0)
        }
        if (oldPage <= 1) { //已到顶,隐藏头
            val layoutParams = loadMoreViewTop.layoutParams
            if (layoutParams != null) {
                layoutParams.height = 0
                loadMoreViewTop.layoutParams = layoutParams
            }
        }
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        return viewModel.isInBookShelf(book)
    }

    override fun showBookInfo(book: SearchBook) {
        lifecycleScope.launch(IO) {
            // 视频源结果：封面即播（复刻时光播放器的交互），不再绕道详情页
            // 视频源特性关闭时不做这个分流，聚合源的结果照常进详情页
            val isVideo = AppConfig.videoFeatureEnabled &&
                ((book.type and BookType.video > 0) ||
                    appDb.bookSourceDao.getBookSource(book.origin)?.isVideoSource() == true)
            if (isVideo) {
                playVideo(book)
            } else {
                withContext(Dispatchers.Main) {
                    startActivity<BookInfoActivity> {
                        putExtra("name", book.name)
                        putExtra("author", book.author)
                        putExtra("bookUrl", book.bookUrl)
                    }
                }
            }
        }
    }

    /**
     * 长按：视频点击即播，长按进详情页，好在里面加书架、换源、改信息等。
     */
    override fun openBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
        }
    }

    /**
     * 复刻时光播放器 VideoViewModel.prepareBook 的入库逻辑：
     * 确保书籍入库且带 video 位，然后直接进剧集播放页。
     * 目录准备、白名单分流由播放页自身完成（SourceVideoPlayerViewModel.load）。
     */
    private fun playVideo(searchBook: SearchBook) {
        lifecycleScope.launch(IO) {
            try {
                // 当前发现列表整体作为播放器的切换列表（动态供给 + 后台加载下一页：
                // 右侧 30% 竖滑切换，可一直切到列表末尾）
                VideoFeedStore.put(
                    { viewModel.booksData.value?.map { it.bookUrl } ?: emptyList() },
                    searchBook.bookUrl,
                    loadMore = { runCatching { viewModel.explore() } }
                )
                val now = System.currentTimeMillis()
                val exist = appDb.bookDao.getBook(searchBook.bookUrl)
                val book = if (exist != null) {
                    exist.ensureVideoType()
                    // 刷新"最近看过"时间，保证继续阅读排序正确
                    exist.durChapterTime = now
                    appDb.bookDao.update(exist)
                    exist
                } else {
                    searchBook.toBook().apply {
                        // notShelf：看过的视频不自动上书架，播放器内点图标才加入
                        addType(BookType.video)
                        addType(BookType.notShelf)
                        durChapterTime = now
                        if (order == 0) {
                            order = appDb.bookDao.minOrder - 1
                        }
                    }.also { appDb.bookDao.insert(it) }
                }
                withContext(Dispatchers.Main) {
                    startActivity(
                        SourceVideoPlayerActivity.createIntent(
                            this@ExploreShowActivity,
                            book.bookUrl,
                            book.durChapterIndex
                        )
                    )
                }
            } catch (e: Exception) {
                AppLog.put("打开视频失败\n${e.localizedMessage}", e, true)
                withContext(Dispatchers.Main) {
                    toastOnUi("打开视频失败：${e.localizedMessage}")
                }
            }
        }
    }
}
