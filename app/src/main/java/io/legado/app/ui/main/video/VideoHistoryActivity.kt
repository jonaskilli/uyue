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
import android.graphics.Color
import android.preference.PreferenceManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
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

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val KEY_INCOGNITO = "video_history_incognito"
    private val KEY_BASELINE = "video_history_incognito_baseline"

    /** 标题栏右侧的「无痕」按钮，由 addHistoryActions() 创建 */
    private lateinit var incognitoBtn: TextView

    private fun isIncognitoOn(): Boolean = prefs.getBoolean(KEY_INCOGNITO, false)
    private fun incognitoBaseline(): Long = prefs.getLong(KEY_BASELINE, 0L)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.watch_history)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        addHistoryActions()
        updateIncognitoUI()
        loadHistory()
    }

    /**
     * 把「清空 / 无痕」两个按钮加进标题栏内部的 toolbar 右侧（与「观看历史」平行），
     * 不能放在 TitleBar(AppBarLayout) 直接子级——它是垂直布局，会落到标题栏下方；
     * 也不能在外部叠放，会被 TitleBar 盖住。动态 add 到 toolbar 最稳。
     */
    private fun addHistoryActions() {
        val tb = binding.titleBar.toolbar
        val density = resources.displayMetrics.density
        val px = { dp: Int -> (dp * density).toInt() }
        fun makeBtn(label: String, onClick: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(px(14), px(8), px(14), px(8))
                setOnClickListener { onClick() }
            }
        val clearBtn = makeBtn("清空") { hideCurrentHistory() }
        incognitoBtn = makeBtn("无痕") { toggleIncognito() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(clearBtn)
            addView(incognitoBtn)
        }
        val lp = Toolbar.LayoutParams(Toolbar.LayoutParams.WRAP_CONTENT, Toolbar.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        tb.addView(row, lp)
    }

    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val all = appDb.bookDao.flowVideo().first()
            // 无痕开启：列表直接空（记录仍照常写入，只不显示）
            // 无痕关闭：只显示 durChapterTime 晚于基线的记录（关闭前所有记录都不显示）
            val items = if (isIncognitoOn()) {
                emptyList()
            } else {
                val baseline = incognitoBaseline()
                all.filter { it.durChapterTime > baseline }
                    .sortedByDescending { it.durChapterTime }
                    .take(100)
                    .map { it.toSearchBook() }
            }
            withContext(Dispatchers.Main) {
                adapter.setItems(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateIncognitoUI() {
        val on = isIncognitoOn()
        incognitoBtn.text = if (on) "无痕·开" else "无痕"
        incognitoBtn.setTextColor(
            if (on) Color.parseColor("#FFB300") else Color.WHITE
        )
    }

    private fun toggleIncognito() {
        val on = !isIncognitoOn()
        prefs.edit().putBoolean(KEY_INCOGNITO, on).apply()
        if (!on) {
            // 关闭无痕：把基线推到此刻，关闭前已有的记录都不显示，
            // 只显示关闭之后新观看的视频
            prefs.edit().putLong(KEY_BASELINE, System.currentTimeMillis()).apply()
        }
        updateIncognitoUI()
        loadHistory()
    }

    /**
     * 「清空」按钮：不真删数据库记录，只把显示基线推到此刻，
     * 当前窗口里显示的所有历史都不显示，但记录仍保存在 books 表；
     * 之后新观看的视频（durChapterTime > 基线）才会重新显示出来。
     * 等价于「开启无痕再立刻关闭」。
     */
    private fun hideCurrentHistory() {
        prefs.edit().putLong(KEY_BASELINE, System.currentTimeMillis()).apply()
        loadHistory()
        toastOnUi("已隐藏当前历史（记录仍保留）")
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        val b = appDb.bookDao.getBook(book.bookUrl) ?: return false
        return (b.type and BookType.notShelf) == 0
    }

    override fun showBookInfo(searchBook: SearchBook) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val exist = appDb.bookDao.getBook(searchBook.bookUrl)
                val book = if (exist != null) {
                    exist.ensureVideoType()
                    exist.durChapterTime = now
                    appDb.bookDao.update(exist)
                    exist
                } else {
                    appDb.searchBookDao.getSearchBook(searchBook.bookUrl)?.toBook()?.apply {
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
