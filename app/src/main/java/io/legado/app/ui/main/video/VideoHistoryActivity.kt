package io.legado.app.ui.main.video

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.SearchBook
import io.legado.app.databinding.ActivityVideoHistoryBinding
import io.legado.app.help.book.addType
import io.legado.app.help.book.ensureVideoType
import io.legado.app.help.video.VideoFeedStore
import io.legado.app.ui.book.search.SearchAdapter
import io.legado.app.ui.book.video.SourceVideoPlayerActivity
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 观看历史：复用书架 / 搜索结果那套「封面 + 标题 + 信息」列表，
 * 展示最近打开过的视频（books 表里 video 类型、按 durChapterTime 倒序，最多 100 条）。
 *
 * 记录本身无需额外存储：视频被打开时（发现页 / 搜索结果）已经作为 notShelf+video 的书写入
 * books 表，并把 durChapterTime 刷成当前时间，这里只是换一种视图呈现。
 */
class VideoHistoryActivity : BaseActivity<ActivityVideoHistoryBinding>(),
    SearchAdapter.CallBack {

    override val binding by viewBinding(ActivityVideoHistoryBinding::inflate)

    private val adapter by lazy { SearchAdapter(this, this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.watch_history)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val books = appDb.bookDao.flowVideo().first()
                .sortedByDescending { it.durChapterTime }
                .take(100)
            val items = books.map { it.toSearchBook() }
            withContext(Dispatchers.Main) {
                adapter.setItems(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        val b = appDb.bookDao.getBook(book.bookUrl) ?: return false
        return (b.type and BookType.notShelf) == 0
    }

    override fun showBookInfo(name: String, author: String, bookUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val exist = appDb.bookDao.getBook(bookUrl)
                val book = if (exist != null) {
                    exist.ensureVideoType()
                    exist.durChapterTime = now
                    appDb.bookDao.update(exist)
                    exist
                } else {
                    appDb.searchBookDao.getSearchBook(bookUrl)?.toBook()?.apply {
                        // notShelf：看过的视频不自动上书架，播放器里点图标才加入
                        addType(BookType.video)
                        addType(BookType.notShelf)
                        durChapterTime = now
                        if (order == 0) {
                            order = appDb.bookDao.minOrder - 1
                        }
                    }?.also { appDb.bookDao.insert(it) }
                        ?: throw IllegalStateException("没有找到该视频")
                }
                withContext(Dispatchers.Main) {
                    // 历史列表整体交给播放器：播放器右侧 20% 区域竖滑即可在历史记录间切换。
                    // provider 实时查询数据库，始终是最新的历史顺序，且刚打开的视频一定在列表内。
                    VideoFeedStore.put(
                        provider = {
                            runBlocking(Dispatchers.IO) {
                                appDb.bookDao.flowVideo().first()
                                    .sortedByDescending { it.durChapterTime }
                                    .take(100)
                                    .map { it.bookUrl }
                            }
                        },
                        currentBookUrl = book.bookUrl,
                    )
                    startActivity(
                        SourceVideoPlayerActivity.createIntent(
                            this@VideoHistoryActivity,
                            book.bookUrl,
                            book.durChapterIndex,
                            homeMode = true,
                        )
                    )
                }
            }.onFailure {
                AppLog.put("打开观看历史失败\n${it.localizedMessage}", it)
                withContext(Dispatchers.Main) {
                    toastOnUi(it.localizedMessage ?: "打开失败")
                }
            }
        }
    }
}
