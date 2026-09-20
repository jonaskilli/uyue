package io.legado.app.ui.main.video

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.dao.flowVideoSourcesPart
import io.legado.app.data.dao.getVideoSourceParts
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.constant.BookType
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.withContext
import io.legado.app.databinding.FragmentVideoBinding
import io.legado.app.help.book.addType
import io.legado.app.help.book.ensureVideoType
import io.legado.app.help.config.AppConfig
import io.legado.app.help.video.VideoFeedStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchAdapter
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.video.SourceVideoPlayerActivity
import io.legado.app.ui.book.video.VideoDownloadActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.explore.ExploreAdapter
import io.legado.app.ui.main.explore.ExploreDiffItemCallBack
import io.legado.app.utils.applyTint
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 视频发现界面，展示视频书源及其发现分类。
 *
 * 选源口径由 BookSourceDao.flowVideoSourcesPart() 决定（类型=4 ∥ 分组关键词 ∥ 规则含 m3u8/mp4），
 * 与 BookSource.isVideoSource()、路由判定共用同一套标准，避免"主页能选、播放路由错位"。
 */
class VideoFragment() : VMBaseFragment<VideoViewModel>(R.layout.fragment_video),
    MainFragmentInterface,
    ExploreAdapter.CallBack,
    SearchAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<VideoViewModel>()
    private val binding by viewBinding(FragmentVideoBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    /** 搜索结果列表：搜视频名时用，与书源列表共用同一个 RecyclerView */
    private val searchAdapter by lazy { SearchAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private var videoFlowJob: Job? = null
    /** true = 正在展示搜索结果，下面列表归搜索结果用 */
    private var searchMode = false
    /** 参与搜索的视频源（用于筛选按钮） */
    private var searchSources: List<BookSourcePart> = emptyList()
    /** 全部视频源（库里读，用来给结果里的源补名字） */
    private var allVideoSources: List<BookSourcePart> = emptyList()
    /** 未过滤的搜索结果 */
    private var allResults: List<SearchBook> = emptyList()
    /** 当前选中的源；null = 全部 */
    private var selectedSourceUrl: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initSearchView()
        initRecyclerView()
        upVideoData()
        initSearchObserve()
    }

    override fun onPause() {
        super.onPause()
        searchView.clearFocus()
        adapter.onPause()
    }

    /**
     * 顶部搜索框搜的是**视频名**（在所有视频源里搜），不是过滤书源。
     * 只在点搜索键时发起——视频源可能几十个，边输入边发请求会打爆网络。
     */
    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_video_name)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                val key = query?.trim().orEmpty()
                if (key.isEmpty()) {
                    exitSearchMode()
                } else {
                    enterSearchMode()
                    viewModel.search(key)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // 清空输入框就退回书源列表；输入过程中不发起任何请求
                if (newText.isNullOrBlank() && searchMode) exitSearchMode()
                return true
            }
        })
    }

    private fun enterSearchMode() {
        searchMode = true
        // 停掉书源列表的刷新，免得它把列表和空态提示又改回去
        videoFlowJob?.cancel()
        binding.rvFind.adapter = searchAdapter
        binding.tvEmptyMsg.isVisible = true
        binding.tvEmptyMsg.text = getString(R.string.searching)
        selectedSourceUrl = null
        allResults = emptyList()
        // 参与搜索的源（要读库，放后台）——先取全量视频源，名字用来补结果里的源
        lifecycleScope.launch(IO) {
            val sources = appDb.bookSourceDao.getVideoSourceParts()
            withContext(Dispatchers.Main) {
                if (!searchMode) return@withContext
                allVideoSources = sources
                rebuildSourceChips()
            }
        }
    }

    private fun exitSearchMode() {
        if (!searchMode) return
        searchMode = false
        viewModel.stopSearch()
        binding.chipScroll.isGone = true
        binding.chipContainer.removeAllViews()
        searchSources = emptyList()
        allVideoSources = emptyList()
        allResults = emptyList()
        selectedSourceUrl = null
        binding.rvFind.adapter = adapter
        upVideoData()
    }

    /**
     * 顶部源标签：全部 + 实际返回了结果的视频源。
     * 直接从搜索结果里收集出现过的源（origins），名字优先取视频源列表的 bookSourceName，
     * 取不到再退用结果自带的 originName——标签只反映"搜出来的源"，可逐个切换。
     */
    private fun rebuildSourceChips() {
        val nameMap = allVideoSources.associate { it.bookSourceUrl to it.bookSourceName }
        val sources = allResults.flatMap { book ->
            book.origins.map { url ->
                url to (nameMap[url]?.takeIf { n -> n.isNotBlank() }
                    ?: book.originName.takeIf { n -> n.isNotBlank() } ?: url)
            }
        }.distinctBy { it.first }
            .map { (url, name) -> BookSourcePart(bookSourceUrl = url, bookSourceName = name) }
        searchSources = sources
        upSourceChips()
    }

    /** 筛选按钮行：全部 + 每个参与搜索的视频源 */
    private fun upSourceChips() {
        val container = binding.chipContainer
        container.removeAllViews()
        addChip(getString(R.string.all_source), selectedSourceUrl == null) {
            selectedSourceUrl = null
            applyFilter()
        }
        searchSources.forEach { source ->
            addChip(source.bookSourceName, selectedSourceUrl == source.bookSourceUrl) {
                selectedSourceUrl = source.bookSourceUrl
                applyFilter()
            }
        }
        binding.chipScroll.isVisible = true
    }

    private fun addChip(text: String, selected: Boolean, onClick: () -> Unit) {
        val label = if (text.length > 10) text.take(10) + "…" else text
        val tv = TextView(requireContext()).apply {
            this.text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (6 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 14 * resources.displayMetrics.density
                // 选中用主题色，未选中用半透明白底——跟随主题，不写死颜色
                setColor(if (selected) primaryColor else Color.parseColor("#33FFFFFF"))
            }
            setTextColor(if (selected) Color.WHITE else primaryTextColor)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
        binding.chipContainer.addView(tv, lp)
    }

    /** 按选中的源过滤结果；null = 全部 */
    private fun applyFilter() {
        val url = selectedSourceUrl
        val list = if (url == null) {
            allResults
        } else {
            allResults.filter { it.origins.contains(url) }
        }
        searchAdapter.setItems(list)
        if (list.isEmpty()) {
            binding.tvEmptyMsg.isVisible = true
            binding.tvEmptyMsg.text = getString(R.string.search_video_empty)
        } else {
            binding.tvEmptyMsg.isGone = true
        }
    }

    private fun initSearchObserve() {
        viewModel.searchBooksLiveData.observe(viewLifecycleOwner) { list ->
            if (!searchMode) return@observe
            allResults = list
            rebuildSourceChips()
            applyFilter()
        }
        viewModel.isSearchingLiveData.observe(viewLifecycleOwner) { searching ->
            if (!searchMode || !searching) return@observe
            binding.tvEmptyMsg.isVisible = true
            binding.tvEmptyMsg.text = getString(R.string.searching)
        }
        viewModel.searchFinishLiveData.observe(viewLifecycleOwner) { isEmpty ->
            if (!searchMode) return@observe
            if (isEmpty && allResults.isEmpty()) {
                binding.tvEmptyMsg.isVisible = true
                binding.tvEmptyMsg.text = getString(R.string.search_video_empty)
            }
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
    }

    private fun upVideoData(searchKey: String? = null) {
        videoFlowJob?.cancel()
        videoFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            if (searchKey.isNullOrBlank()) {
                appDb.bookSourceDao.flowVideoSourcesPart()
            } else {
                appDb.bookSourceDao.flowVideoSourcesPart(searchKey)
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("视频发现界面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || searchView.query.isNotEmpty()
                adapter.setItems(it, diffItemCallBack)
                delay(500)
            }
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_download -> startActivity<VideoDownloadActivity>()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_video_download, menu)
        // 菜单图标按主题着色，否则浅色主题下白色矢量图看不见
        menu.applyTint(requireContext())
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        startActivity<SearchActivity> {
            putExtra("searchScope", SearchScope(bookSource).toString())
        }
    }

    // ---------- 搜索结果（SearchAdapter.CallBack） ----------

    override fun isInBookshelf(book: SearchBook): Boolean = viewModel.isInBookShelf(book)

    /**
     * 点搜索结果：视频没有看简介的必要，直接进播放页的「首页模式」
     * （播放器在上、选集在下），可以先挑集再看。
     */
    override fun showBookInfo(name: String, author: String, bookUrl: String) {
        lifecycleScope.launch(IO) {
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
                // 搜索结果整体交给播放器，右侧竖滑可以在结果之间切
                val urls = searchAdapter.getItems().map { it.bookUrl }
                withContext(Dispatchers.Main) {
                    VideoFeedStore.put({ urls }, book.bookUrl)
                    startActivity(
                        SourceVideoPlayerActivity.createIntent(
                            requireContext(),
                            book.bookUrl,
                            book.durChapterIndex,
                            homeMode = true,
                        )
                    )
                }
            }.onFailure {
                AppLog.put("打开搜索结果失败\n${it.localizedMessage}", it)
                toastOnUi(it.localizedMessage ?: "打开失败")
            }
        }
    }

    fun compressVideo() {
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }
}
