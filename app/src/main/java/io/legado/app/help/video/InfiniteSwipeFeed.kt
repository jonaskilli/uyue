package io.legado.app.help.video

import com.google.gson.reflect.TypeToken
import io.legado.app.data.appDb
import io.legado.app.data.dao.getVideoSourceParts
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.source.exploreKinds
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashSet

/**
 * 无限刷视频的列表供给站（后台持续补货，列表一直往前续）。
 *
 * 与 [VideoFeedStore] 配合：把"随机混源"后的列表喂给播放器的切集列表（feed），
 * 复用原播放器的竖滑切视频交互，只替换"列表从哪来"。
 *
 * ## 取视频的规则：**随机选分类 → 取完就绕回第 1 页重抓 → 只补符合条件的**
 * - 分类池 = 所有视频源的发现分类（[ExploreKind.Type.url]）摊平；
 *   每批**随机**挑 [INITIAL_CATEGORIES] / [BATCH_CATEGORIES] 个分类，且尽量跨不同视频源（多样化）；
 * - 正在取的这几个分类会一路把它们**所有页取完**；
 * - 取到最后一页（空页 / 到页上限 / 站点忽略分页参数）后，该分类的页码**绕回第 1 页**，
 *   之后仍然可以被随机选中、从第 1 页重新抓；
 * - **重新抓出来的视频只要符合规则就可以续接**：不在当前列表里、没看过（[history]）、没失效（[blocked]）。
 *   因此"抓过但用户没看过"的视频会在下一轮重新进入列表，列表永远续得上、且不断有新面孔；
 * - 分页进度 [categoryPage] 持久化，绕回也持久化，**绝不重复抓同一页**；
 * - 第一页就空的分类（源故障/该分类没内容）记 [emptyCats] 冷却 [EMPTY_COOLDOWN_MS]，
 *   冷却期内不再随机选它，避免把请求浪费在坏源上。
 *
 * ## 补货 = 水位驱动：**后续不够就一直抓下去**
 * - [needsMore] 判断"未播剩余 + 待合并 + 桶内候补"是否低于 [TARGET_UNPLAYED]：不够就抓，够了就停；
 * - [buildFresh] 交织后**立即出桶**，桶里永远只留"还没进播放列表的候补"；
 * - **抓不到符合规则的内容、或抓到的太少不够续播时不停**：继续随机抓下一批分类
 *   （抓完的分类页码已归 0，会回来重抓），最多连试 [MAX_EMPTY_ROUNDS] 轮才歇一次；
 *   歇了也会被下次 [urls] 重新唤起 —— "后续不够就一直抓下去"是持续生效的；
 * - 全空时按需 [relaxSeen] 放开去重窗口（只保留"当前列表 + 看过 + 失效"），
 *   让重抓能把"抓过但没看过"的视频带回来（带 [RELAX_COOLDOWN_MS] 冷却，避免空转刷请求）。
 *
 * ## 交织：**源间轮流 + 源内分类轮流**（保证多样化，且不丢内容）
 * [buildFresh] 先按视频源轮流取 1 条，同一源的多个分类之间再轮流；
 * **不设"每个分类取多少条"的上限**（写死数量会把抓到的内容丢掉），
 * 桶里抓到多少就交织多少，只是大桶自然排在小桶后面 —— 既不会连续同一个分类，
 * 也不会连续同一个视频源。
 *
 * 计数、拼接、续接都在 [urls] 里完成（播放器每次切视频/预取都会调它），
 * 通过读取当前正在播放的视频（由调用方传入）定位，无需额外回调。
 */
object InfiniteSwipeFeed {

    // ---------- 参数 ----------

    /** 单分类单页超时 */
    private const val PAGE_TIMEOUT = 4_000L
    /** 每批随机选多少个分类（单位 = 分类，不是视频源） */
    private const val INITIAL_CATEGORIES = 8
    /** 首批每个分类只抓多少页：只为最快凑出先行播放列表 */
    private const val CATEGORY_PAGES = 4
    /** 续页阶段：每个分类一轮最多抓多少页（一直翻到空页，即把该分类的页取完） */
    private const val CONTINUE_PAGES = 10
    /** 单个分类的页数安全上限（正常由"空页"结束，这个只是防异常站点无限翻页） */
    private const val MAX_CATEGORY_PAGE = 100
    /** 后台续接：每一轮并行处理多少个分类 */
    private const val BATCH_CATEGORIES = 5
    /** 首批一个都没产出时，最多重新随机选分类尝试几轮 */
    private const val MAX_GENERATE_ATTEMPTS = 6
    // 注意：**不限制"每个分类每轮取多少条"** —— 写死数量会把抓到的内容丢掉。
    // 交织本身是"源间轮流 + 源内分类轮流"，大桶自然排在后面，不会一上来就霸占列表。
    /** 攒够这么多条就先做成"先行播放列表"立刻开播 */
    private const val MIN_READY = 15
    /**
     * 列表里保留最近多少个**已播过**的视频。
     *
     * 不保留的话，每次并入新内容都会把"当前这条之前的"全裁掉，
     * 于是"往回滑看上一个"能不能用，取决于那一刻后台有没有刚好续接上（随机）；
     * 后台一续接，上一个立刻消失、只能提示「已经是列表第一个」。
     * 保留最近 15 个后，往回滑始终可用（与抖音下滑回看一致），
     * 更久以前的照旧丢弃，列表不会无限增长。
     */
    private const val KEEP_PLAYED = 15
    /** 水位：始终保证"当前播放位置之后还有这么多条可播" */
    private const val TARGET_UNPLAYED = 150
    /** 无论如何最多等这么久就返回（哪怕还没攒够） */
    private const val INIT_WAIT = 15_000L
    /**
     * 已播放记录条数（持久化）。
     *
     * **这是"这个视频我看过没有"的判定依据**：切到下一个视频时把上一个记进来，
     * 每轮无限刷开始时 `seenAll.addAll(history)`，所以看过的不会被重抓进列表。
     * 窗口（200 条）之外更早看过的视频有可能再次出现 —— 内容池有限时这是必要的妥协。
     */
    private const val HISTORY_MAX = 200
    /**
     * "最近看过"窗口（从 [history] 尾部取），只用于**避免刚看过的马上又冒出来**，
     * 是短期去重，不是"看过"的判定依据（判定看过用 [history]）。
     */
    private const val RECENT_DEDUP = 100
    private const val BLOCKED_MAX = 500
    /** 连续多少页"全是抓过的内容"就判定该分类已取完（区别于站点忽略分页参数） */
    private const val NO_NEW_PAGE_LIMIT = 5
    /** 第一页就空的分类冷却多久不再随机选中 */
    private const val EMPTY_COOLDOWN_MS = 10 * 60_000L
    /** 放开去重窗口的最小间隔，避免空转刷请求 */
    private const val RELAX_COOLDOWN_MS = 20_000L
    /**
     * 连续多少轮"一个都没补进来"才停一次（保险：源全挂时不要无限刷请求）。
     * 停了也没关系 —— 播放器每次切视频都会调 [urls] → [triggerFetch] 重新唤起，
     * 所以"后续视频不够就一直抓下去"是持续生效的。
     */
    private const val MAX_EMPTY_ROUNDS = 12
    /** 空轮之间的间隔：换一批随机分类再试，也避免坏源被连续猛刷 */
    private const val EMPTY_ROUND_DELAY_MS = 1_500L
    /** 落盘节流：切视频很频繁，没必要每次都写 ACache */
    private const val SAVE_THROTTLE_MS = 3_000L
    private const val ACACHE_NAME = "infinite_swipe"
    private const val HISTORY_ACACHE_KEY = "infinite_swipe_history"
    private const val BLOCKED_ACACHE_KEY = "infinite_swipe_blocked"
    private const val SAVED_ACACHE_KEY = "infinite_swipe_feed"
    private const val PAGES_ACACHE_KEY = "infinite_swipe_pages"
    private const val EMPTY_ACACHE_KEY = "infinite_swipe_empty"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 一个"分类"：以 (视频源 + 发现分类 url) 为最小单位参与随机抓取 */
    private data class CatRef(
        val sourceUrl: String,
        val url: String,
        val title: String,
    ) {
        val key: String get() = "$sourceUrl|$url"
    }

    /** 分类池：所有视频源的 url 型发现分类摊平后的候选（进程内缓存，每次 prepareFirst 刷新） */
    private val categoryPool = mutableListOf<CatRef>()
    /** key -> CatRef，便于后台续页时找回分类 */
    private val categoryRefs = mutableMapOf<String, CatRef>()

    /** 各分类一个桶：分类 key -> 该分类抓到的、**还没进播放列表**的 bookUrl（已去重） */
    private val buckets = LinkedHashMap<String, MutableList<String>>()
    /** 正在开采中（还没取完）的分类 */
    private val activeCats = LinkedHashSet<String>()
    /** 每个分类已取到的最后一页（持久化）；取到最后一页后归 0，下次从第 1 页重新抓 */
    private val categoryPage = mutableMapOf<String, Int>()
    /** 第一页就空的分类 -> 记录时间（持久化）；冷却期内不再随机选它 */
    private val emptyCats = mutableMapOf<String, Long>()

    /** 已抓过 / 已看过 / 已失效的 url，用于抓取去重；[relaxSeen] 会放开它 */
    private val seenAll = LinkedHashSet<String>()
    /** 已看历史（持久化）：看过的视频不会再被补进列表 */
    private val history = LinkedHashSet<String>()
    /** 最近看过的少量 url（容量 [RECENT_DEDUP]），用于生成去重 */
    private val recentSeen = LinkedHashSet<String>()
    /** 失效屏蔽（持久化）：解析/播放失败过的 url，不再抓、不再出现 */
    private val blocked = LinkedHashSet<String>()
    /** 上一次生成的列表（持久化），下次启动先用它顶上 */
    private var savedList = mutableListOf<String>()

    /** 当前会话的混合列表（bookUrl），由播放器 feed 动态读取 */
    private var mixedUrls = mutableListOf<String>()
    /** 已交织、等待并入 [mixedUrls] 的新内容（累加队列） */
    private val pendingUrls = mutableListOf<String>()
    private var producing = false
    private var preparing = false
    private var watchedCount = 0
    private var lastCurrent: String? = null
    /** 当前正在播放的那一条，mergeFeed 时锚在最前，保证切集导航不丢锚点 */
    private var keepUrl: String? = null
    /** 上一次放开去重窗口的时间 */
    private var lastRelaxMs = 0L
    private var lastSavedMs = 0L
    private var lastHistoryMs = 0L

    init {
        loadHistory()
        loadBlocked()
        loadSaved()
        loadProgress()
    }

    // ---------- 对外接口 ----------

    /**
     * 准备新一轮无限刷：
     * 1) 若有上次保存的列表，先拿它顶上，立刻返回第一条（秒出视频）；
     * 2) **随机**挑 [INITIAL_CATEGORIES] 个分类（尽量跨源），每个分类先抓 [CATEGORY_PAGES] 页；
     *    攒够 [MIN_READY] 条就立刻做成"先行播放列表"开播；
     * 3) 开播后由 [triggerFetch] 在后台把这些分类的页取完 → 再随机挑下一批
     *    （取完的分类页码已归 0，随时可能被再次随机选中、从第 1 页重新抓），
     *    只把"符合规则"的视频补进列表。
     * 没有可用分类时返回 null。
     */
    suspend fun prepareFirst(): String? {
        refreshCategoryPool()
        // 上一轮正在播的那一条（退出时不会被记进 history，因为只有"切走"才记）
        val lastPlayed = synchronized(lock) { lastCurrent }
        synchronized(lock) {
            buckets.clear()
            activeCats.clear()
            pendingUrls.clear()
            seenAll.clear()
            recentSeen.clear()
            recentSeen.addAll(history.toList().takeLast(RECENT_DEDUP))
            // 抓取去重底线：失效屏蔽 + 已看过（历史）+ 当前列表。
            // 看过的不会再进列表；不并入 savedList（那是还没播的尾巴，并入会把自己挡死）。
            seenAll.addAll(blocked)
            seenAll.addAll(history)
            // 分页进度 [categoryPage] **不清空**：继续从上次的位置往后取，取完的分类已归 0 可重抓
            // 去掉"最近看过"以及"上一轮正在播的那一条"：
            // 用同一个视频重新开局会命中播放器的"剧集已就绪"早退分支，体验上等于没换视频
            mixedUrls = savedList.filter { it !in recentSeen && it != lastPlayed }.toMutableList()
            producing = false
            watchedCount = 0
            lastCurrent = null
            keepUrl = mixedUrls.firstOrNull()
        }
        val seeded = synchronized(lock) { mixedUrls.firstOrNull() }
        val ready = CompletableDeferred<Unit>()
        var signaled = false
        synchronized(lock) { preparing = true }
        scope.launch {
            // 每抓到一页就增量并入播放列表：够 MIN_READY 条立刻放行先播
            val onProgress: () -> Unit = {
                synchronized(lock) {
                    drainPending()
                    if (seeded == null && !signaled && hasEnough()) {
                        signaled = true
                        ready.complete(Unit)
                    }
                }
            }
            try {
                var attempts = 0
                while (attempts < MAX_GENERATE_ATTEMPTS) {
                    attempts++
                    val cats = pickCategories(INITIAL_CATEGORIES)
                    if (cats.isEmpty()) break
                    cats.map { cat -> async { fetchCategory(cat, CATEGORY_PAGES, onProgress) } }.awaitAll()
                    synchronized(lock) {
                        drainPending()
                        if (seeded == null && !signaled && hasEnough()) {
                            signaled = true
                            ready.complete(Unit)
                        }
                    }
                    if (synchronized(lock) { totalBucketSize() + pendingUrls.size > 0 }) break
                }
                synchronized(lock) {
                    drainPending()
                    if (mixedUrls.isNotEmpty()) {
                        savedList = mixedUrls.toMutableList()
                        keepUrl?.let { savedList.remove(it) }
                        saveSaved()
                    }
                    saveProgress()
                    if (!signaled) ready.complete(Unit)
                }
            } finally {
                synchronized(lock) { preparing = false }
                triggerFetch()
            }
        }
        if (seeded != null) return seeded
        withTimeoutOrNull(INIT_WAIT) { ready.await() }
        return synchronized(lock) { mixedUrls.firstOrNull() }
    }

    /**
     * 播放器读取当前列表（同时完成"计数 + 合并新内容 + 触发补货"）。
     * @param currentUrl 当前正在播放的视频 bookUrl（由播放器会话传入）。
     */
    fun urls(currentUrl: String?): List<String> = synchronized(lock) {
        if (currentUrl != null) {
            if (lastCurrent == null) {
                lastCurrent = currentUrl
            } else if (currentUrl != lastCurrent) {
                val prev = lastCurrent
                lastCurrent = currentUrl
                watchedCount++
                if (prev != null) {
                    history.add(prev)
                    while (history.size > HISTORY_MAX) {
                        val it = history.iterator()
                        if (it.hasNext()) it.remove()
                    }
                    saveHistoryThrottled()
                    recentSeen.add(prev)
                    while (recentSeen.size > RECENT_DEDUP) {
                        val it = recentSeen.iterator()
                        if (it.hasNext()) it.remove()
                    }
                }
            }
        }
        if (drainPending()) {
            val idx = if (lastCurrent != null) mixedUrls.indexOf(lastCurrent) else -1
            val tail = if (idx >= 0) mixedUrls.drop(idx + 1) else mixedUrls.toList()
            if (tail.size >= 2) {
                savedList = tail.toMutableList()
                saveSavedThrottled()
            }
        }
        if (needsMore()) triggerFetch()
        if (mixedUrls.isEmpty() && currentUrl != null) listOf(currentUrl) else mixedUrls.toList()
    }

    /** 播放器接近列表末尾时由 [io.legado.app.help.video.VideoFeedStore.Feed.loadMore] 调用 */
    fun requestMore() = triggerFetch()

    /** 标记某条视频失效：加入屏蔽集，之后不再抓取、不再出现在列表里 */
    fun markFailed(url: String) {
        if (url.isBlank()) return
        synchronized(lock) {
            if (!blocked.add(url)) return
            for (bucket in buckets.values) bucket.remove(url)
            pendingUrls.remove(url)
            savedList.remove(url)
            while (blocked.size > BLOCKED_MAX) {
                val it = blocked.iterator()
                if (it.hasNext()) it.remove()
            }
            saveBlocked()
            saveSaved()
        }
    }

    /**
     * 重置无限刷：清空已保存列表、已看历史、分页进度与分类冷却（持久化一并删除）。
     * 不影响失效屏蔽。
     */
    fun clearSavedList() {
        synchronized(lock) {
            savedList.clear()
            history.clear()
            recentSeen.clear()
            activeCats.clear()
            resetProgressLocked()
            ACache.get(ACACHE_NAME).remove(SAVED_ACACHE_KEY)
            ACache.get(ACACHE_NAME).remove(HISTORY_ACACHE_KEY)
        }
    }

    /**
     * 只重置"取视频的进度"（分页位置 + 分类冷却），不清空已保存列表与已看历史。
     * 用于用户重新调整分类筛选之后：按新的筛选规则从各分类第 1 页重新开始取。
     */
    fun resetProgress() {
        synchronized(lock) { resetProgressLocked() }
    }

    /**
     * 切换筛选模式（模式一 ↔ 模式二）后调用：**清空旧模式抓出来的播放列表**，
     * 并重置分页进度，下次无限刷按新模式的书源/分类从零开始抓。
     *
     * 不清空已看历史与失效屏蔽 —— 那是跨模式通用的（看过的、失效的在任何模式下都不该再出现）。
     */
    fun onModeSwitched() {
        synchronized(lock) {
            savedList.clear()
            mixedUrls = mutableListOf()
            pendingUrls.clear()
            buckets.clear()
            activeCats.clear()
            seenAll.clear()
            seenAll.addAll(blocked)
            seenAll.addAll(history)
            lastCurrent = null
            keepUrl = null
            ACache.get(ACACHE_NAME).remove(SAVED_ACACHE_KEY)
            resetProgressLocked()
        }
    }

    private fun resetProgressLocked() {
        categoryPage.clear()
        emptyCats.clear()
        saveProgress()
    }

    // ---------- 内部：分类池与随机选取 ----------

    /** 刷新分类池：把所有视频源的 url 型发现分类摊平（过滤筛选配置） */
    private suspend fun refreshCategoryPool() {
        val sources = appDb.bookSourceDao.getVideoSourceParts()
            .filter { InfiniteSwipeFilter.isSourceEnabled(it.bookSourceUrl) }
        val refs = coroutineScope {
            sources.map { src ->
                async {
                    val source = appDb.bookSourceDao.getBookSource(src.bookSourceUrl)
                        ?: return@async emptyList<CatRef>()
                    runCatching { source.exploreKinds() }
                        .getOrDefault(emptyList())
                        .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
                        .filter { InfiniteSwipeFilter.isCategoryEnabled(src.bookSourceUrl, it.title) }
                        .map { CatRef(src.bookSourceUrl, it.url!!, it.title) }
                }
            }.awaitAll().flatten()
        }
        synchronized(lock) {
            categoryPool.clear()
            categoryPool.addAll(refs)
            categoryRefs.clear()
            refs.forEach { categoryRefs[it.key] = it }
        }
    }

    /**
     * **随机**挑 n 个分类（不在开采中、不在冷却期内）。
     * 多样性：先保证每个视频源只出一个分类，不够再补同源的其它分类，
     * 避免一轮里 5 个分类全来自同一个源（列表会变成同一批内容）。
     */
    private fun pickCategories(n: Int): List<CatRef> {
        if (n <= 0) return emptyList()
        val now = System.currentTimeMillis()
        var usable = synchronized(lock) {
            categoryPool.filter {
                it.key !in activeCats && now - (emptyCats[it.key] ?: 0L) > EMPTY_COOLDOWN_MS
            }
        }
        if (usable.isEmpty()) {
            // 全在冷却中（源集体故障/内容都取过一轮）：清掉冷却重来，否则永远选不出分类
            synchronized(lock) { emptyCats.clear() }
            usable = synchronized(lock) { categoryPool.filter { it.key !in activeCats } }
        }
        if (usable.isEmpty()) return emptyList()
        val shuffled = usable.shuffled()
        val picked = mutableListOf<CatRef>()
        val usedSources = HashSet<String>()
        for (c in shuffled) {
            if (c.sourceUrl !in usedSources) {
                picked.add(c)
                usedSources.add(c.sourceUrl)
            }
        }
        for (c in shuffled) {
            if (picked.size >= n) break
            if (c !in picked) picked.add(c)
        }
        return picked.shuffled().take(n)
    }

    /**
     * 后台续页要处理的分类：先把"正在开采"的分类取完
     * （进度最少的优先、其中再随机，避免总是同一批），不够再随机补新分类。
     * 取完的分类页码已归 0，会重新回到 [pickCategories] 的随机候选里。
     */
    private fun pickContinueTargets(n: Int): List<CatRef> {
        val active = synchronized(lock) {
            activeCats.mapNotNull { categoryRefs[it] }.sortedBy { categoryPage[it.key] ?: 0 }
        }
        val least = active.take((n * 2).coerceAtLeast(n)).shuffled().take(n)
        val result = least.toMutableList()
        if (result.size < n) {
            for (cat in pickCategories(n - result.size)) {
                if (cat !in result) result.add(cat)
            }
        }
        return result
    }

    // ---------- 内部：抓取、补货与拼接 ----------

    /** 后台补货（常驻水位驱动）：水位不足就抓，抓够就停；播放器切视频时会重新唤起 */
    private fun triggerFetch() {
        synchronized(lock) {
            if (producing || preparing) return
            producing = true
        }
        scope.launch {
            try {
                var poolRefreshed = false
                var emptyRounds = 0
                while (isActive) {
                    if (synchronized(lock) { !needsMore() }) break
                    var targets = pickContinueTargets(BATCH_CATEGORIES)
                    if (targets.isEmpty()) {
                        // 选不出分类：重刷一次分类池（用户可能刚改了筛选/新加了视频源）
                        if (poolRefreshed) break
                        poolRefreshed = true
                        refreshCategoryPool()
                        targets = pickContinueTargets(BATCH_CATEGORIES)
                        if (targets.isEmpty()) break
                    }
                    val before = synchronized(lock) { totalBucketSize() + pendingUrls.size }
                    targets.map { cat -> async { fetchCategory(cat, CONTINUE_PAGES) } }.awaitAll()
                    val after = synchronized(lock) {
                        saveProgress()
                        totalBucketSize() + pendingUrls.size
                    }
                    if (after == before) {
                        // 这一轮一个都没补进来（或者补得太少、续接后不够播）：
                        // **不停，继续随机抓下一批分类** —— 抓完的分类页码已归 0，
                        // 会被重新随机选中从第 1 页重抓；同时放开去重窗口，
                        // 让"抓过但用户没看过"的视频能重新进入列表（看过的仍按 [history] 排除）。
                        emptyRounds++
                        val canRelax = synchronized(lock) {
                            System.currentTimeMillis() - lastRelaxMs > RELAX_COOLDOWN_MS
                        }
                        if (canRelax) synchronized(lock) { relaxSeen() }
                        // 连续很多轮全空才停一次（源全挂时不要无限刷请求）；
                        // 停了也会被下次 [urls] 重新唤起，所以"不够就一直抓"是持续生效的
                        if (emptyRounds >= MAX_EMPTY_ROUNDS) break
                        delay(EMPTY_ROUND_DELAY_MS)
                        continue
                    }
                    emptyRounds = 0
                }
            } finally {
                synchronized(lock) { producing = false }
            }
        }
    }

    /**
     * 抓取单个分类的若干页并填入对应桶。
     *
     * - 首批 [CATEGORY_PAGES] 页只求快；续页每轮 [CONTINUE_PAGES] 页，一直翻到空页（取完）；
     * - 取到最后一页后页码**归 0**，该分类重新回到随机候选，下次从第 1 页重新抓；
     * - 重抓出来的视频只有在 [insertBooks] 里通过规则（没在列表里 / 没看过 / 没失效）才会进桶；
     * - 第一页就空的分类记冷却 [EMPTY_COOLDOWN_MS]，期间不再随机选它（坏源不浪费请求）。
     */
    private suspend fun fetchCategory(
        ref: CatRef,
        maxPages: Int,
        onProgress: (() -> Unit)? = null,
    ) {
        val source = appDb.bookSourceDao.getBookSource(ref.sourceUrl) ?: return
        val start = synchronized(lock) { categoryPage[ref.key] ?: 0 } + 1
        if (start > MAX_CATEGORY_PAGE) {
            synchronized(lock) { categoryPage[ref.key] = 0 } // 已到最后一页：绕回第 1 页
            return
        }
        val end = minOf(start + maxPages - 1, MAX_CATEGORY_PAGE)
        synchronized(lock) { activeCats.add(ref.key) }
        var page = start
        var noNewStreak = 0
        var gotAny = false
        var attempted = false
        var reachedEnd = false
        var prevPageKeys: Set<String>? = null
        while (page <= end) {
            if (synchronized(lock) { !needsMore() }) break
            attempted = true
            val books = runCatching {
                withTimeout(PAGE_TIMEOUT) { WebBook.exploreBookAwait(source, ref.url, page) }
            }.getOrDefault(emptyList())
            if (books.isEmpty()) {
                reachedEnd = true // 后面没有页了
                break
            }
            val pageKeys = books.map { it.bookUrl }.toSet()
            val sameAsPrev = prevPageKeys != null && prevPageKeys == pageKeys
            prevPageKeys = pageKeys
            val added = insertBooks(ref.key, books)
            gotAny = true
            synchronized(lock) { categoryPage[ref.key] = page }
            if (added == 0) {
                if (sameAsPrev) {
                    reachedEnd = true // 站点忽略分页参数：每页都是同一批
                    break
                }
                noNewStreak++
                if (noNewStreak >= NO_NEW_PAGE_LIMIT) {
                    reachedEnd = true // 连续多页都是抓过的内容
                    break
                }
            } else {
                noNewStreak = 0
            }
            synchronized(lock) { pendingUrls.addAll(buildFresh()) }
            onProgress?.invoke()
            page++
        }
        synchronized(lock) {
            if (reachedEnd || (categoryPage[ref.key] ?: 0) >= MAX_CATEGORY_PAGE) {
                // 取到最后一页：绕回第 1 页，之后仍可被随机选中重新抓
                categoryPage[ref.key] = 0
                activeCats.remove(ref.key)
            }
            if (attempted && !gotAny) {
                // 第一页就是空的（源故障 / 该分类没内容）：冷却，短期内不再随机选它
                emptyCats[ref.key] = System.currentTimeMillis()
                activeCats.remove(ref.key)
            }
        }
    }

    /** 入库并填入分类桶，返回"新增条数"（不符合规则的算 0） */
    private fun insertBooks(key: String, books: List<SearchBook>): Int {
        runCatching { appDb.searchBookDao.insert(*books.toTypedArray()) }
        synchronized(lock) {
            val bucket = buckets.getOrPut(key) { mutableListOf() }
            var added = 0
            for (b in books) {
                val u = b.bookUrl
                if (u !in blocked && seenAll.add(u)) {
                    bucket.add(u)
                    added++
                }
            }
            return added
        }
    }

    /**
     * 交织出新内容（**出桶**）：**源间轮流 + 源内分类轮流**，
     * 保证列表里不会连续出现同一个分类，也不会连续出现同一个视频源的多个视频。
     */
    private fun buildFresh(): List<String> = synchronized(lock) {
        val result = mutableListOf<String>()
        val consumed = LinkedHashSet<String>()
        val iters = buckets.mapValues { it.value.listIterator() }
        // 源 -> 该源下的分类（顺序打散，保证随机多样）
        val bySource = LinkedHashMap<String, MutableList<String>>()
        for (k in buckets.keys.shuffled()) {
            val src = categoryRefs[k]?.sourceUrl ?: k.substringBefore("|")
            bySource.getOrPut(src) { mutableListOf() }.add(k)
        }
        val sources = bySource.keys.toMutableList().shuffled()
        val srcPos = bySource.mapValues { 0 }.toMutableMap()
        var progressed = true
        while (progressed) {
            progressed = false
            for (src in sources) {
                val cats = bySource[src] ?: continue
                for (t in cats.indices) {
                    val idx = (srcPos[src]!! + t) % cats.size
                    val key = cats[idx]
                    val it = iters[key] ?: continue
                    if (!it.hasNext()) continue
                    val u = it.next()
                    consumed.add(u)
                    srcPos[src] = (idx + 1) % cats.size
                    progressed = true
                    if (u !in recentSeen && u !in blocked) result.add(u)
                    break // 这一轮该源只出 1 条，换下一个源
                }
            }
        }
        if (consumed.isNotEmpty()) {
            for (bucket in buckets.values) bucket.removeAll(consumed)
            val emptyKeys = buckets.filterValues { it.isEmpty() }.keys.toList()
            emptyKeys.forEach { buckets.remove(it) }
        }
        result
    }

    /** 把待合并队列并入播放列表（[mergeFeed] 去重并保留最近若干个已播项） */
    private fun drainPending(): Boolean = synchronized(lock) {
        if (pendingUrls.isEmpty()) return false
        val batch = pendingUrls.toList()
        pendingUrls.clear()
        mergeFeed(batch)
        true
    }

    /**
     * 把新内容合并进当前播放队列：
     * - 保留**最近 [KEEP_PLAYED] 个已播过的** + 从当前那条开始的未看尾巴，
     *   更久以前已播的丢弃（列表不会无限增长）；
     * - 新内容接在尾巴后面（新内容内部已按「源间轮流 + 源内分类轮流」交织）；
     * - 当前那条保持在原位（不再强行挪到最前），这样往回滑（feedSwitch(-1)）才有目标。
     */
    private fun mergeFeed(fresh: List<String>): List<String> = synchronized(lock) {
        val cur = lastCurrent ?: keepUrl
        val base = if (cur != null) {
            val i = mixedUrls.indexOf(cur)
            if (i >= 0) {
                // 多留 KEEP_PLAYED 个已播的，保证"上一个视频"随时可回看
                val from = (i - KEEP_PLAYED).coerceAtLeast(0)
                mixedUrls.subList(from, mixedUrls.size).toList()
            } else {
                mixedUrls.toList()
            }
        } else {
            mixedUrls.toList()
        }
        val baseSet = base.toSet()
        val freshFiltered = fresh.filter { it !in baseSet && it !in recentSeen && it !in blocked }
        val result = (base + freshFiltered).toMutableList()
        mixedUrls = result
        result
    }

    /**
     * 放开去重窗口：只保留"当前列表 + 待合并 + 桶内候补 + 最近看过 + 已看历史 + 失效屏蔽"，
     * 其余（之前抓过但用户没看过的）允许在下一轮重抓时重新进入列表。
     * 这样"取到最后一页 → 绕回第 1 页重抓"才能真的补进内容，列表永远续得上。
     */
    private fun relaxSeen() {
        val keep = HashSet<String>(mixedUrls)
        keep.addAll(pendingUrls)
        for (b in buckets.values) keep.addAll(b)
        keep.addAll(recentSeen)
        keep.addAll(history)
        keep.addAll(blocked)
        seenAll.clear()
        seenAll.addAll(keep)
        lastRelaxMs = System.currentTimeMillis()
    }

    private fun totalBucketSize(): Int = synchronized(lock) { buckets.values.sumOf { it.size } }

    /** 当前播放位置之后还剩多少条没播（当前视频不在列表里时按 0 处理） */
    private fun unplayedRemaining(): Int {
        val idx = if (lastCurrent != null) mixedUrls.indexOf(lastCurrent) else -1
        return if (idx >= 0) mixedUrls.size - idx - 1 else 0
    }

    private fun needsMore(): Boolean {
        return unplayedRemaining() + pendingUrls.size + totalBucketSize() < TARGET_UNPLAYED
    }

    private fun hasEnough(): Boolean = mixedUrls.size >= MIN_READY

    // ---------- 持久化 ----------

    private fun loadHistory() {
        runCatching {
            val str = ACache.get(ACACHE_NAME).getAsString(HISTORY_ACACHE_KEY)
            if (!str.isNullOrBlank()) {
                GSON.fromJson(str, Array<String>::class.java)?.let { history.addAll(it) }
            }
        }
    }

    private fun saveHistory() {
        val list = synchronized(lock) { history.toList() }
        runCatching { ACache.get(ACACHE_NAME).put(HISTORY_ACACHE_KEY, GSON.toJson(list)) }
    }

    private fun saveHistoryThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastHistoryMs < SAVE_THROTTLE_MS) return
        lastHistoryMs = now
        saveHistory()
    }

    private fun loadBlocked() {
        runCatching {
            val str = ACache.get(ACACHE_NAME).getAsString(BLOCKED_ACACHE_KEY)
            if (!str.isNullOrBlank()) {
                GSON.fromJson(str, Array<String>::class.java)?.let { blocked.addAll(it) }
            }
        }
    }

    private fun saveBlocked() {
        val list = synchronized(lock) { blocked.toList() }
        runCatching { ACache.get(ACACHE_NAME).put(BLOCKED_ACACHE_KEY, GSON.toJson(list)) }
    }

    private fun loadSaved() {
        runCatching {
            val str = ACache.get(ACACHE_NAME).getAsString(SAVED_ACACHE_KEY)
            if (!str.isNullOrBlank()) {
                GSON.fromJson(str, Array<String>::class.java)?.let { savedList.addAll(it) }
            }
        }
    }

    private fun saveSaved() {
        val list = synchronized(lock) { savedList.toList() }
        lastSavedMs = System.currentTimeMillis()
        runCatching { ACache.get(ACACHE_NAME).put(SAVED_ACACHE_KEY, GSON.toJson(list)) }
    }

    private fun saveSavedThrottled() {
        if (System.currentTimeMillis() - lastSavedMs < SAVE_THROTTLE_MS) return
        saveSaved()
    }

    /**
     * 分页进度与"空分类冷却"持久化：下次无限刷接着上次的位置往后取，
     * 取完的分类页码已归 0（会被重新随机选中、从第 1 页重抓）。
     */
    private fun loadProgress() {
        runCatching {
            val pagesStr = ACache.get(ACACHE_NAME).getAsString(PAGES_ACACHE_KEY)
            if (!pagesStr.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, Int>>() {}.type
                GSON.fromJson<Map<String, Int>>(pagesStr, type)?.let { categoryPage.putAll(it) }
            }
            val emptyStr = ACache.get(ACACHE_NAME).getAsString(EMPTY_ACACHE_KEY)
            if (!emptyStr.isNullOrBlank()) {
                val type = object : TypeToken<Map<String, Long>>() {}.type
                GSON.fromJson<Map<String, Long>>(emptyStr, type)?.let { emptyCats.putAll(it) }
            }
        }
    }

    private fun saveProgress() {
        val pages = synchronized(lock) { categoryPage.toMap() }
        val empty = synchronized(lock) { emptyCats.toMap() }
        runCatching {
            ACache.get(ACACHE_NAME).put(PAGES_ACACHE_KEY, GSON.toJson(pages))
            ACache.get(ACACHE_NAME).put(EMPTY_ACACHE_KEY, GSON.toJson(empty))
        }
    }
}
