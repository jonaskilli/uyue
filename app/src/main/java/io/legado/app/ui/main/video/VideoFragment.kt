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
import io.legado.app.help.video.InfiniteSwipeFeed
import io.legado.app.help.video.InfiniteSwipeFilter
import io.legado.app.help.video.VideoFeedStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.book.search.SearchAdapter
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.book.video.SourceVideoPlayerActivity
import io.legado.app.ui.book.video.SourceVideoPlayerSession
import io.legado.app.ui.book.video.VideoDownloadActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.explore.ExploreAdapter
import io.legado.app.ui.main.explore.ExploreDiffItemCallBack
import io.legado.app.ui.main.video.VideoHistoryActivity
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
        initInfiniteSwipe()
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
        val ctx = requireContext()
        // 未选中态底色/文字色跟随「芯片真实背景」明暗：
        // 浅底(如浅色主题)用半透明黑底+黑字，深底用半透明白底+白字，避免浅底白字看不清。
        val surfaceLight = ColorUtils.isColorLight(ctx.backgroundColor)
        val normalBg = if (surfaceLight) Color.parseColor("#1F000000") else Color.parseColor("#26FFFFFF")
        val normalText = if (surfaceLight) Color.BLACK else Color.WHITE
        val tv = TextView(ctx).apply {
            this.text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            val padH = (12 * resources.displayMetrics.density).toInt()
            val padV = (6 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 14 * resources.displayMetrics.density
                setColor(if (selected) primaryColor else normalBg)
            }
            setTextColor(if (selected) Color.WHITE else normalText)
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
            R.id.menu_video_history -> startActivity<VideoHistoryActivity>()
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
    override fun showBookInfo(searchBook: SearchBook) {
        // 顶部切到了某个源时，要播的是「那个源」返回的那条。
        // 同名结果是多源合并的，SearchBook.bookUrl 只保留了最先返回的那个源，
        // 直接用会无视用户选的源——这里先定下目标源，免得之后切源改了值。
        val origin = selectedSourceUrl
        val targetUrl = searchBook.getBookUrlByOrigin(origin)
        lifecycleScope.launch(IO) {
            runCatching {
                val now = System.currentTimeMillis()
                val exist = appDb.bookDao.getBook(targetUrl)
                val book = if (exist != null) {
                    exist.ensureVideoType()
                    exist.durChapterTime = now
                    appDb.bookDao.update(exist)
                    exist
                } else {
                    appDb.searchBookDao.getSearchBook(targetUrl)?.toBook()?.apply {
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
                // 搜索结果整体交给播放器，右侧 30% 竖滑可在结果之间切。
                // 用未过滤的全部结果(allResults)做动态供给，后续源陆续返回的新结果会自动
                // 进入可切换范围；选了具体源时只在该源的结果内切，且用各源自己的链接。
                withContext(Dispatchers.Main) {
                    VideoFeedStore.put(
                        provider = { allResults.urlsOfOrigin(origin) },
                        currentBookUrl = book.bookUrl,
                    )
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

    /**
     * 长按搜索结果：视频点击即播，长按进详情页，好在里面加书架、换源、改信息等。
     * 与点击时一样按顶部选中的源取链接，否则详情页打开的会是另一个源的那一本。
     */
    override fun openBookInfo(book: SearchBook) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.getBookUrlByOrigin(selectedSourceUrl))
        }
    }

    /**
     * 取各结果在 [origin] 这个源下的链接。
     * origin 为空（选了「全部」）则不做筛选，取各结果自身链接。
     */
    private fun List<SearchBook>.urlsOfOrigin(origin: String?): List<String> {
        if (origin.isNullOrBlank()) return map { it.bookUrl }
        return mapNotNull { book ->
            if (book.origins.contains(origin)) book.getBookUrlByOrigin(origin) else null
        }
    }

    /**
     * 无限刷入口：以"分类"为单位，随机挑 8 个视频源发现分类，每个分类先只抓 4 页，
     * 够 15 条就先做成先行播放列表立刻开播；随后后台把每个分类的**全部页取完**，
     * 取完再随机挑下一批分类继续取（只往前取，不回头、不循环），
     * 并按水位（当前位置之后始终留 150 条可播）自动续接到播放列表尾部。
     * 全部由 [InfiniteSwipeFeed] 负责。
     */
    private fun initInfiniteSwipe() {
        // 长按：打开分类筛选面板（每个视频源总开关 + 各分类开关），确认后开始无限刷
        binding.fabInfiniteSwipe.setOnLongClickListener {
            InfiniteSwipeFilterDialog { startInfiniteSwipe() }
                .show(childFragmentManager, "infinite_swipe_filter")
            true
        }
        // 短按：按当前筛选配置直接开始无限刷
        binding.fabInfiniteSwipe.setOnClickListener { startInfiniteSwipe() }
    }

    /** 按当前筛选配置执行无限刷（短按按钮、面板「开始无限刷」都会走到这里） */
    private fun startInfiniteSwipe() {
        // 一个书源都没选过（全新安装默认全关 / 用户全关了）：
        // 直接提示去长按按钮选源，别白跑一轮抓取、也不用干等超时
        if (InfiniteSwipeFilter.hasNoEnabledSource()) {
            toastOnUi(getString(R.string.infinite_swipe_no_source_selected))
            return
        }
        binding.fabInfiniteSwipe.isEnabled = false
        lifecycleScope.launch(IO) {
            var toastMsg: String? = null
            try {
                val first = InfiniteSwipeFeed.prepareFirst()
                if (first == null) {
                    toastMsg = getString(R.string.infinite_swipe_no_source)
                } else {
                    VideoFeedStore.put(
                        provider = { InfiniteSwipeFeed.urls(SourceVideoPlayerSession.getBook()?.bookUrl) },
                        currentBookUrl = first,
                        loadMore = { InfiniteSwipeFeed.requestMore() }
                    )
                    // 常驻一份：进程被杀/页面重建后播放页能把这张动态列表重新挂回去，
                    // 否则会退化成"只能播当前一个视频、竖滑切不动"
                    VideoFeedStore.putPersistent(
                        provider = { InfiniteSwipeFeed.urls(SourceVideoPlayerSession.getBook()?.bookUrl) },
                        currentBookUrl = first,
                        loadMore = { InfiniteSwipeFeed.requestMore() }
                    )
                    withContext(Dispatchers.Main) {
                        startActivity(
                            SourceVideoPlayerActivity.createIntent(
                                requireContext(),
                                first,
                                0,
                                infiniteSwipe = true
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                AppLog.put("准备无限刷失败\n${e.localizedMessage}", e)
                toastMsg = e.localizedMessage ?: "准备失败"
            }
            withContext(Dispatchers.Main) {
                binding.fabInfiniteSwipe.isEnabled = true
                toastMsg?.let { toastOnUi(it) }
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
