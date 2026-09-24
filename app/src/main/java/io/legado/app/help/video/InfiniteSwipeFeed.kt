package io.legado.app.help.video

import io.legado.app.data.appDb
import io.legado.app.data.dao.getVideoSourceParts
import io.legado.app.data.entities.BookSourcePart
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * 无限刷视频的列表供给站。
 *
 * 与 [VideoFeedStore] 配合：把"随机混源"后的列表喂给播放器的切集列表（feed），
 * 复用原播放器的右侧竖滑切视频、上下集等交互，只替换"列表从哪来"。
 *
 * 列表构建规则：
 * - 每次挑若干视频源，各自取发现分类（[ExploreKind.Type.url]），每分类抓 [CATEGORY_PAGES] 页；
 * - **按源分桶 + 轮询交织**：每个视频源一个桶，最终列表按"每个源轮流取 1 条"交织而成，
 *   保证不会连续出现同一个视频源的多个视频（至少有两个非空桶时）；
 * - **启动加速**：首批只要攒够 [MIN_READY] 条（且来自 ≥2 个源）就立刻返回播放，剩余后台继续补；
 *   单源整体预算 [SOURCE_TIMEOUT]（5 秒），拿不到列表直接跳过换下一个，绝不一直转圈；
 * - **取不到就重试**：若这一轮随机选的源都没产出，清空"近期用过"后重新随机选源再试，直到产出内容为止
 *   （最多 [MAX_GENERATE_ATTEMPTS] 轮；全程有 [INIT_WAIT] 兜底）；
 * - **持久化**：每次生成的列表都覆盖保存到本地（[ACACHE_NAME]），重启后仍在；下次点"无限刷"时，
 *   在生成新列表之前先用上一次保存的列表顶上（立刻出视频），新列表就绪后覆盖旧的；
 * - 当前播放位置之后剩余内容少于 [REFILL_REMAIN_THRESHOLD] 时，后台自动再随机开 [REFILL_SOURCES] 个视频源混进来（双保险）；
 * - 去重：仅与"最近看过的少量([RECENT_DEDUP]) + 失效屏蔽([BLOCKED_MAX])"做去重；
 *   全量历史([HISTORY_MAX])只做记录，不再用于生成去重，否则会把整个推荐池吃光、列表只剩几条。
 *   遇到播放失效的视频通过 [markFailed] 加入屏蔽集，之后既不会重新抓到，也不会再出现在列表里。
 *
 * 计数、拼接、兜底都在 [urls] 里完成（播放器每次切视频/预取都会调它），
 * 通过读取当前正在播放的视频（由调用方传入）定位，无需额外回调。
 */
object InfiniteSwipeFeed {

    /** 单源整体预算：5 秒内拿不到列表就跳过换下一个，防止失效源一直转圈 */
    private const val SOURCE_TIMEOUT = 5_000L
    /** 单分类单页超时 */
    private const val PAGE_TIMEOUT = 4_000L
    private const val MAX_CATEGORIES_PER_SOURCE = 6
    private const val CATEGORY_PAGES = 4
    /** 每个视频源在最终列表里最多占多少条：防止某一个源数量远超其它源，
     *  轮询交织时小桶先空、退化成长段同一个源（用户体感"连续同一个源/分类"）。 */
    private const val PER_SOURCE_CAP = 15
    /** 首批每次尝试随机选的源数量 */
    private const val INITIAL_POOL = 8
    /** 取不到内容时，最多重新随机选源尝试几轮 */
    private const val MAX_GENERATE_ATTEMPTS = 6
    /** 刷满阈值后续接时，后台随机开的源数量 */
    private const val REFILL_SOURCES = 5
    /** 攒够这么多条（且来自 ≥2 个源）就立刻返回播放 */
    private const val MIN_READY = 5
    /** 整个会话桶内视频总数上限，避免无限增长 */
    private const val MAX_TOTAL = 300
    /** 无论如何最多等这么久就返回（哪怕还没攒够） */
    private const val INIT_WAIT = 12_000L
    /** 当前播放位置之后剩余内容少于此数就后台自动续接（不再用"看完多少再续"，避免短列表永远不续接） */
    private const val REFILL_REMAIN_THRESHOLD = 80
    /** 全量历史只做记录，不用于生成去重 */
    private const val HISTORY_MAX = 200
    /** 生成去重只用"最近看过的少量"，避免把整个推荐池吃光导致列表只剩几条 */
    private const val RECENT_DEDUP = 100
    private const val BLOCKED_MAX = 500
    private const val ACACHE_NAME = "infinite_swipe"
    private const val HISTORY_ACACHE_KEY = "infinite_swipe_history"
    private const val BLOCKED_ACACHE_KEY = "infinite_swipe_blocked"
    private const val SAVED_ACACHE_KEY = "infinite_swipe_feed"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    /** 各视频源一个桶：bookSourceUrl -> 该源抓到的 bookUrl 列表（已去重、未含失效/历史） */
    private val buckets = LinkedHashMap<String, MutableList<String>>()
    /** 当前已进入桶（或历史/屏蔽）的全部 url，用于去重 */
    private val seenAll = LinkedHashSet<String>()
    /** 已看历史（持久化，仅做记录，不再用于生成去重） */
    private val history = LinkedHashSet<String>()
    /** 最近看过的少量 url（容量 [RECENT_DEDUP]），仅用于生成去重，避免立刻重播刚看过的 */
    private val recentSeen = LinkedHashSet<String>()
    /** 失效屏蔽（持久化）：解析/播放失败过的 url，不再抓、不再出现 */
    private val blocked = LinkedHashSet<String>()
    /** 上一次生成的列表（持久化），下次启动先用它顶上 */
    private var savedList = mutableListOf<String>()

    /** 当前会话的混合列表（bookUrl），由播放器 feed 动态读取（= 桶交织后的结果） */
    private var mixedUrls = mutableListOf<String>()
    /** 抓取完成、等待拼接到 [mixedUrls] 的新批次（= 新一轮交织结果） */
    private var pendingBatch: List<String>? = null
    private var fetching = false
    /** 已刷视频计数 */
    private var watchedCount = 0
    private var lastCurrent: String? = null
    /** 最近用过的视频源（避免相邻批次重复选同一源） */
    private val recentlyUsedSources = ArrayDeque<String>()
    /** 每个 (源|分类) 已抓取到的最后一页：续接时从下一页接着抓，
     *  否则每次都抓前几页、后续页永远不抓，列表很快就到头、续不上 */
    private val categoryPage = mutableMapOf<String, Int>()
    private fun catKey(src: String, cat: String) = "$src|$cat"
    /** 当前正在播放的那一条（prepareFirst 时本地缓存的第一条），mergeFeed 时锚在最前，保证切集导航不丢锚点 */
    private var keepUrl: String? = null

    init {
        loadHistory()
        loadBlocked()
        loadSaved()
    }

    // ---------- 对外接口 ----------

    /**
     * 准备新一轮无限刷：
     * 1) 若本地有上次保存的列表，先把它作为当前列表顶上，立刻返回第一条（秒出视频）；
     * 2) 后台随机混源生成新列表，就绪后用新列表覆盖（保留当前正在播放的那一条），并覆盖保存。
     * 3) 若本地无保存列表，则等到攒够 [MIN_READY] 条（或 [INIT_WAIT] 兜底）再返回；
     *    途中若这一轮选的源都没产出，会重新随机选源再试，直到有内容（或尝试 [MAX_GENERATE_ATTEMPTS] 轮）。
     * 完全没有可用视频源时返回 null。
     */
    suspend fun prepareFirst(): String? {
        synchronized(lock) {
            buckets.clear()
            seenAll.clear()
            recentSeen.clear()
            recentSeen.addAll(history.toList().takeLast(RECENT_DEDUP))
            // 抓取去重(seenAll)只保留"本次会话已入桶 + 失效屏蔽"，不再并入 savedList / history：
            // 否则续接(triggerFetch)抓取新视频源时，新 url 会被旧列表/旧历史挡死，
            // 列表无法增长、一直固定循环、看起来"没有续接"。
            // 播放列表自身的不重复由 mergeFeed 的 baseSet 去重 + recentSeen 保证。
            seenAll.addAll(blocked)
            // 重启/重新打开时，先用上次保存的列表顶上；但去掉已看过的最近若干条（recentSeen），
            // 从剩下的未看视频开始，避免反复从某个刚看过的视频重新播放。
            mixedUrls = savedList.filter { it !in recentSeen }.toMutableList()
            pendingBatch = null
            fetching = false
            watchedCount = 0
            lastCurrent = null
            recentlyUsedSources.clear()
            categoryPage.clear()
            keepUrl = mixedUrls.firstOrNull()
        }
        val seeded = synchronized(lock) { mixedUrls.firstOrNull() }
        val ready = CompletableDeferred<Unit>()
        var signaled = false
        scope.launch {
            var attempts = 0
            while (attempts < MAX_GENERATE_ATTEMPTS) {
                attempts++
                synchronized(lock) { recentlyUsedSources.clear() } // 重新随机选源再试
                val sources = pickSources(INITIAL_POOL)
                if (sources.isEmpty()) break
                // 并行抓取这轮选中的源（每个源内部已按页超时，互不拖累），远比串行快，
                // 这样后台重算的"均衡交织列表"能在几秒内顶替旧的（可能不均衡的）缓存列表。
                sources.map { src -> async { fetchInto(src) } }.awaitAll()
                synchronized(lock) {
                    // 仅"首启无本地缓存"时需要实时把 mixedUrls 织大，以便尽快返回第一条；
                    // 有缓存时保持缓存列表不动（避免中途把缓存列表清空成只剩 keep），等收尾再整体覆盖。
                    if (seeded == null) {
                        mergeFeed(buildFresh())
                        if (!signaled && hasEnough()) {
                            signaled = true
                            ready.complete(Unit)
                        }
                    }
                }
                val enough = synchronized(lock) { seeded == null && hasEnough() }
                if (enough) break // 首启：已拿到像样的列表就停
            }
            // 收尾：若这次真的抓到了新内容，把新内容合并进当前队列（mergeFeed 会锚住 keepUrl），并覆盖保存；
            // 若全失败（缓存模式下）则保留本地缓存旧列表，不动 mixedUrls / savedList。
            synchronized(lock) {
                if (totalBucketSize() > 0) {
                    mergeFeed(buildFresh())
                    if (mixedUrls.isNotEmpty()) {
                        savedList = mixedUrls.toMutableList()
                        keepUrl?.let { savedList.remove(it) } // 保存给下次启动时不要从"正在播的这条"开始
                        saveSaved()
                    }
                }
                if (!signaled) ready.complete(Unit)
            }
        }
        if (seeded != null) return seeded
        withTimeoutOrNull(INIT_WAIT) { ready.await() }
        return synchronized(lock) { mixedUrls.firstOrNull() }
    }

    /**
     * 播放器读取当前列表（同时完成"计数 + 拼接新批次 + 兜底"）。
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
                    saveHistory()
                    // 同步维护"最近看过"去重窗口
                    recentSeen.add(prev)
                    while (recentSeen.size > RECENT_DEDUP) {
                        val it = recentSeen.iterator()
                        if (it.hasNext()) it.remove()
                    }
                }
            }
        }
        // 新批次就绪：合并（追加）到当前列表，而不是整体覆盖——避免把正在播放/已排队的视频丢掉、列表突然缩水
        pendingBatch?.let { batch ->
            if (batch.isNotEmpty()) {
                mergeFeed(batch)
            }
            pendingBatch = null
            // 把"尚未观看的尾巴"覆盖保存，供下次启动顶上（至少保留 2 条，否则不覆盖，沿用上一份较大的）
            val idx = if (lastCurrent != null) mixedUrls.indexOf(lastCurrent) else -1
            val tail = if (idx >= 0) mixedUrls.drop(idx + 1) else mixedUrls.toList()
            if (tail.size >= 2) {
                savedList = tail.toMutableList()
                saveSaved()
            }
        }
        // 续接：当前播放位置之后剩余内容不足阈值，就后台自动补一批。
        // 用"剩余量"而不是"看过多少"来判断，避免列表本身不到阈值（如只有 70 条）就永远不续接。
        // triggerFetch 自带"正在抓/已有待合并批次"防重入，可放心每次调用。
        val curIdx = if (lastCurrent != null) mixedUrls.indexOf(lastCurrent) else -1
        val remaining = if (curIdx >= 0) mixedUrls.size - curIdx - 1 else mixedUrls.size
        if (remaining < REFILL_REMAIN_THRESHOLD) triggerFetch()
        // 兜底：若列表为空但确实有当前视频（生成尚未就绪的瞬间），至少保住当前这条，不闪退
        if (mixedUrls.isEmpty() && currentUrl != null) listOf(currentUrl) else mixedUrls.toList()
    }

    /** 播放器接近列表末尾时由 [io.legado.app.help.video.VideoFeedStore.Feed.loadMore] 调用 */
    fun requestMore() = triggerFetch()

    /** 标记某条视频失效：加入屏蔽集，之后不再抓取、不再出现在列表里（当前正在播放的那条仍保留在原位，便于切下一个） */
    fun markFailed(url: String) {
        if (url.isBlank()) return
        synchronized(lock) {
            if (!blocked.add(url)) return
            for (bucket in buckets.values) bucket.remove(url)
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
     * 彻底重置无限刷：清空已保存列表、已看历史与最近去重窗口（持久化一并删除）。
     * 这样"删除列表 / 重新开始"后不会残留旧历史把新抓到的视频又过滤掉（否则列表被砍短、刷两下就到底）；
     * 不影响失效屏蔽（[BLOCKED_ACACHE_KEY]），避免反复遇到坏源。
     */
    fun clearSavedList() {
        synchronized(lock) {
            savedList.clear()
            history.clear()
            recentSeen.clear()
            ACache.get(ACACHE_NAME).remove(SAVED_ACACHE_KEY)
            ACache.get(ACACHE_NAME).remove(HISTORY_ACACHE_KEY)
        }
    }

    // ---------- 内部：抓取与拼接 ----------

    private fun triggerFetch() {
        synchronized(lock) {
            if (fetching || pendingBatch != null) return
            fetching = true
        }
        scope.launch {
            val sources = pickSources(REFILL_SOURCES)
            sources.map { src -> async { fetchInto(src) } }.awaitAll()
            synchronized(lock) { pendingBatch = buildFresh(); fetching = false }
        }
    }

    /**
     * 抓取单个源并填入对应桶（与失效屏蔽/已见去重），并记录"近期用过"。
     * 关键点：每个 (源|分类) 记录已抓到的最后一页，续接时从下一页接着抓，
     * 而不是每次都从头抓前几页——否则开启的源少时每次抓到的都是同样的视频，
     * 去重后和原列表一样，列表无法增长（"到最后一个就不续接"）。
     */
    private suspend fun fetchInto(src: BookSourcePart) {
        if (totalBucketSize() >= MAX_TOTAL) return
        val source = appDb.bookSourceDao.getBookSource(src.bookSourceUrl) ?: return
        val kinds = runCatching { source.exploreKinds() }
            .getOrDefault(emptyList())
            .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
            .filter { InfiniteSwipeFilter.isCategoryEnabled(src.bookSourceUrl, it.title) }
            .shuffled()
            .take(MAX_CATEGORIES_PER_SOURCE)
        if (kinds.isEmpty()) return
        val books = runCatching {
            withTimeout(SOURCE_TIMEOUT) {
                coroutineScope {
                    kinds.map { kind ->
                        async {
                            val url = kind.url!!
                            val start = synchronized(lock) {
                                categoryPage.getOrPut(catKey(src.bookSourceUrl, url)) { 0 }
                            } + 1
                            val list = (0 until CATEGORY_PAGES).map { i ->
                                runCatching {
                                    withTimeout(PAGE_TIMEOUT) {
                                        WebBook.exploreBookAwait(source, url, start + i)
                                    }
                                }.getOrDefault(emptyList())
                            }.flatten()
                            if (list.isNotEmpty()) {
                                synchronized(lock) {
                                    categoryPage[catKey(src.bookSourceUrl, url)] = start + CATEGORY_PAGES - 1
                                }
                            }
                            list
                        }
                    }.awaitAll().flatten()
                }
            }
        }.getOrDefault(emptyList())
        if (books.isEmpty()) return
        // 入库：播放器解析视频要靠 searchBook 表（同 ExploreShowViewModel）
        runCatching { appDb.searchBookDao.insert(*books.toTypedArray()) }
        synchronized(lock) {
            val bucket = buckets.getOrPut(src.bookSourceUrl) { mutableListOf() }
            for (b in books) {
                val u = b.bookUrl
                if (u !in blocked && seenAll.add(u)) bucket.add(u)
            }
            recentlyUsedSources.addLast(src.bookSourceUrl)
            if (recentlyUsedSources.size > 20) recentlyUsedSources.removeFirst()
        }
    }

    /**
     * 仅由各源桶交织出"新内容"（不含当前播放队列）：轮询保证不连续同源，
     * 且每个源最多 [PER_SOURCE_CAP] 条（防止某源数量过多撑爆混合）；跳过最近看过与失效屏蔽。
     */
    private fun buildFresh(): List<String> = synchronized(lock) {
        val result = mutableListOf<String>()
        // 每个源最多取 PER_SOURCE_CAP 条：先达到上限的源在这一轮轮询里"停手"，
        // 其余源继续轮转，从而整张列表里各源数量大致均衡，不会退化成"一长段同一个源"。
        val taken = buckets.keys.associateWith { 0 }.toMutableMap()
        val iters = buckets.mapValues { it.value.listIterator() }
        while (true) {
            var progressed = false
            for ((key, it) in iters) {
                if (taken[key]!! >= PER_SOURCE_CAP) continue
                if (it.hasNext()) {
                    val u = it.next()
                    taken[key] = taken[key]!! + 1
                    progressed = true
                    if (u !in recentSeen && u !in blocked) result.add(u)
                }
            }
            if (!progressed) break
        }
        result
    }

    /**
     * 把"新抓取内容"合并进当前播放队列，而不是整体替换（避免把正在播放/已排队的视频丢掉、列表突然缩水）：
     * - 丢弃当前位置之前的已看部分，保留从当前正在播放那条开始的未看尾巴；
     * - 新内容接在未看尾巴之后（新内容内部已按源均衡交织）；
     * - 把当前正在播放的那条重新锚到最前，保证切集导航（feedSwitch 按 indexOf 定位）不丢锚点。
     */
    private fun mergeFeed(fresh: List<String>): List<String> = synchronized(lock) {
        val cur = lastCurrent ?: keepUrl
        val base = if (cur != null) {
            val i = mixedUrls.indexOf(cur)
            if (i >= 0) mixedUrls.subList(i, mixedUrls.size).toList() else mixedUrls.toList()
        } else {
            mixedUrls.toList()
        }
        val baseSet = base.toSet()
        val freshFiltered = fresh.filter { it !in baseSet && it !in recentSeen && it !in blocked }
        val result = (base + freshFiltered).toMutableList()
        if (cur != null && result.firstOrNull() != cur) {
            result.remove(cur)
            result.add(0, cur)
        }
        mixedUrls = result
        result
    }

    private fun totalBucketSize(): Int = synchronized(lock) { buckets.values.sumOf { it.size } }
    private fun nonEmptyBucketCount(): Int = synchronized(lock) { buckets.values.count { it.isNotEmpty() } }
    /** 是否已攒够可立刻返回的"像样"列表：数量够 且 至少来自 2 个源（才能交织） */
    private fun hasEnough(): Boolean = totalBucketSize() >= MIN_READY && nonEmptyBucketCount() >= 2

    // 抓取与分页续抓逻辑已内联到 fetchInto（见上方），不再单独保留 fetchFromSource。

    private fun pickSources(n: Int): List<BookSourcePart> {
        val all = appDb.bookSourceDao.getVideoSourceParts()
            .filter { InfiniteSwipeFilter.isSourceEnabled(it.bookSourceUrl) }
        if (all.isEmpty()) return emptyList()
        val recent = synchronized(lock) { recentlyUsedSources.toSet() }
        val pool = all.filter { it.bookSourceUrl !in recent }.ifEmpty { all }
        return pool.shuffled().take(n)
    }

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
        runCatching { ACache.get(ACACHE_NAME).put(SAVED_ACACHE_KEY, GSON.toJson(list)) }
    }
}
