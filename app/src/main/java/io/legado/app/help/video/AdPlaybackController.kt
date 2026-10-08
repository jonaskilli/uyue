package io.legado.app.help.video

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放侧的广告识别与跳过。
 *
 * ## 为什么 ad hoc 地分成"预防/治疗"两步
 * 播放模式下没法提前知道一个分片是不是广告——不下载就无从比对内容，
 * 而下载正是我们要省的。所以这里顺应 [VideoCache]：
 * **让 ExoPlayer 照常缓冲，数据落到本地后再用 [AdFilter] 比对**，
 * 命中就登记成一段跳过区间，等播放位置进入时 seek 过去（画面会闪一下）。
 *
 * 这不是"播到当场才算"——ExoPlayer 提前 15~50 秒缓冲，
 * 第 N 片下载完时播放器还在播第 N-15 片左右，判定有几十秒的时间窗，
 * 因此哈希计算可以完全放在后台，不影响播放。
 *
 * ## 与安全有关的三道护栏
 * 1. **size 初筛在前**：`AdFilter.mayMatch` 不产生任何 IO，绝大多数分片在这一步就被放行，
 *    命中size的才去读缓存算哈希；
 * 2. **跳过跨度上限**：单次跳过不得超过 [MAX_SKIP_MS]。若算出更大的跨度，
 *    说明 chunk 的时间轴映射有问题（片源不受支持/直播滑动窗口），宁可不跳也不能把正片跳掉；
 * 3. **同一区间最多尝试 [MAX_ATTEMPTS] 次**：避免 seek 不精确时来回反复跳。
 */
class AdPlaybackController {

    /** 一个已经下载完成的分片 */
    data class ChunkInfo(
        val url: String,
        val size: Long,
        val startMs: Long,
        val endMs: Long,
    )

    /** 一段待跳过的广告区间 */
    private class SkipRange(
        val start: Long,
        var end: Long,
        var attempts: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** url -> 已判定的结果（true=广告）。同一个 URL 只算一次哈希 */
    private val verdicts = ConcurrentHashMap<String, Boolean>()

    /** 已下载完成的分片，按时间轴索引（供标记当期分片用） */
    private val chunks = ArrayList<ChunkInfo>()

    /**
     * 「size 初筛没命中、因而没被判过」的分片。
     *
     * 关键：初筛没命中只代表"指纹库里还没有这条"，不代表"它不是广告"，
     * 所以绝不能把 false 当结论缓存（见 [onChunkReady] 里的说明）。
     * 用户标记新广告后，[recheckPending] 会回来重判这些片。
     */
    private val pending = ArrayList<ChunkInfo>()

    /** 登记待跳过的区间 */
    private val ranges = ArrayList<SkipRange>()

    /** 正在判定的 URL，避免并发去重 */
    private val inFlight = HashSet<String>()

    @Volatile
    var lastNotice: String? = null

    private var player: ExoPlayer? = null
    private var listener: AnalyticsListener? = null

    // ---------------------------------------------------------------- 挂载

    fun attach(player: ExoPlayer, headers: Map<String, String>) {
        detach()
        this.player = player
        val impl = object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                // 这是 ExoPlayer 在主线程派发的事件，**绝不能让它抛出任何异常**：
                // 一旦抛出就会打断 ExoPlayer 的事件分发链，表现为视频直接播不了。
                // 广告识别是纯附加功能，宁可整体失效也不许影响播放。
                runCatching { onChunkReady(loadEventInfo, mediaLoadData, headers) }
                    .onFailure { AppLog.putDebug("广告识别回调异常：${it.message}") }
            }
        }
        listener = impl
        player.addAnalyticsListener(impl)
    }

    fun detach() {
        listener?.let { player?.removeAnalyticsListener(it) }
        listener = null
        player = null
    }

    fun release() {
        detach()
        scope.cancel()
        synchronized(ranges) { ranges.clear() }
        synchronized(chunks) { chunks.clear() }
        synchronized(pending) { pending.clear() }
        verdicts.clear()
    }

    /** 切换视频时清空：不同视频的时间轴不通用 */
    fun resetForNewVideo() {
        synchronized(ranges) { ranges.clear() }
        synchronized(chunks) { chunks.clear() }
        synchronized(pending) { pending.clear() }
        // verdicts 保留：同一条广告跨集是同一份字节，不必重新判定
    }

    // ---------------------------------------------------------------- 识别

    private fun onChunkReady(
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        headers: Map<String, String>,
    ) {
        // 保险丝：识别/跳过整条链路都出问题时，只放弃功能，不影响播放
        if (!AdFilter.enabled) return
        onChunkReadyInner(loadEventInfo, mediaLoadData, headers)
    }

    private fun onChunkReadyInner(
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        headers: Map<String, String>,
    ) {
        val url = loadEventInfo.dataSpec.uri.toString()
        if (isPlaylistLike(url)) return // 清单/密钥/初始化段不是分片
        val size = loadEventInfo.bytesLoaded
        if (size <= 0) return
        val startMs = mediaLoadData.mediaStartTimeMs
        val endMs = mediaLoadData.mediaEndTimeMs
        val timed = startMs != C.TIME_UNSET && endMs != C.TIME_UNSET && endMs > startMs

        // 本地分片不参与指纹判定：它们是下载时已剔过广告的产物，再判一遍毫无意义；
        // 而且判定会去打开缓存（CacheDataSource 只认 http/https），对 file:// 只会抛异常。
        // 但**必须登记进 chunks**：播放本地缓存时长按也要能弹出分片列表——
        // 下载时指纹库还没有的漏网广告，正是要用户在这里挑出来标掉的。
        if (url.startsWith("file:") || url.startsWith("content:")) {
            if (timed) synchronized(chunks) {
                if (chunks.none { it.url == url }) {
                    chunks.add(ChunkInfo(url, size, startMs, endMs))
                }
            }
            return
        }

        synchronized(chunks) {
            if (chunks.none { it.url == url }) {
                chunks.add(ChunkInfo(url, size, if (timed) startMs else -1L, if (timed) endMs else -1L))
            }
        }

        if (verdicts.containsKey(url)) {
            if (verdicts[url] == true && timed) registerRange(startMs, endMs)
            return
        }
        synchronized(inFlight) { if (!inFlight.add(url)) return }

        // 注意：本回调跑在**主线程**，所以这里只提交任务，判定一律在后台做。
        // 尤其 AdFilter 的首次 warmUp 要扫目录算哈希，放到主线程就是实打实的卡顿。
        scope.launch {
            runCatching { AdFilter.warmUp() }
            synchronized(inFlight) { inFlight.remove(url) }

            // 一级判据（只看字节数）没命中，**只能说明"指纹库里现在没这条"，
            // 不能说明"确认它不是广告"**。所以此处既不下结论、也不写 [verdicts]，
            // 只挂进 [pending]——等用户手动标记了广告，再由 [recheckPending] 回头重判。
            // （早先在这里直接 verdicts[url]=false，导致用户标记完从头播放仍然看到广告：
            //   同一条广告的头尾分片早被缓存成了否定结论，再也不会被重新看一眼。）
            val matched = runCatching { AdFilter.mayMatch(size, hostOf(url)) }.getOrDefault(false)
            if (!matched) {
                synchronized(pending) {
                    if (pending.none { it.url == url }) {
                        pending.add(ChunkInfo(url, size, startMs, endMs))
                    }
                }
                return@launch
            }

            val isAd = runCatching { AdFilter.isAd(url, size, headers) }.getOrDefault(false)
            verdicts[url] = isAd
            if (isAd) {
                AppLog.putDebug("识别到广告分片：${size}B $url")
                if (timed) registerRange(startMs, endMs)
            }
        }
    }

    /**
     * 指纹库刚更新，回头把之前挂起的分片重判一遍。
     *
     * 一条广告通常是**连续几个分片**（实测 ffzy 的一条广告 = 3 片）。
     * 用户停在中间那片按下标记时，同一条广告的头一片、尾一片此刻已经在 [pending] 里，
     * 必须一并认出来，否则表现为"明明标记过了，从头播放还是先看到十几秒广告"。
     */
    private fun recheckPending(headers: Map<String, String>) {
        val todo = synchronized(pending) { pending.toList() }
        if (todo.isEmpty()) return
        scope.launch {
            var hit = 0
            for (c in todo) {
                if (verdicts[c.url] == true) continue
                val matched = runCatching { AdFilter.mayMatch(c.size, hostOf(c.url)) }
                    .getOrDefault(false)
                if (!matched) continue
                val isAd = runCatching { AdFilter.isAd(c.url, c.size, headers) }.getOrDefault(false)
                if (!isAd) continue
                verdicts[c.url] = true
                hit++
                if (c.startMs >= 0 && c.endMs > c.startMs) registerRange(c.startMs, c.endMs)
            }
            if (hit > 0) {
                AppLog.put("已标记广告：连带认出同一条广告的另外 $hit 个分片")
            }
        }
    }

    private fun registerRange(startMs: Long, endMs: Long) {
        synchronized(ranges) {
            // 连续的广告片合并成一段一次跳过，避免 seek 多次导致画面连续抖动
            val adjacent = ranges.firstOrNull { it.end >= startMs - MERGE_GAP_MS && it.start <= endMs }
            if (adjacent != null) {
                adjacent.end = maxOf(adjacent.end, endMs)
            } else {
                ranges.add(SkipRange(startMs, endMs))
            }
        }
    }

    // ---------------------------------------------------------------- 跳过

    /**
     * 在播放器的进度循环里调用。
     * @return true 表示本轮发起了跳过（调用方据此把 position 同步更新，避免进度条回跳）
     */
    fun checkSkip(player: Player, positionMs: Long, durationMs: Long): Long? {
        if (positionMs < 0) return null
        val target = synchronized(ranges) {
            val hit = ranges.firstOrNull {
                positionMs >= it.start && positionMs < it.end && it.attempts < MAX_ATTEMPTS
            } ?: return null
            // 吸附相邻区间：把紧跟着的几片一次性跳完
            var end = hit.end
            var guard = 0
            while (guard++ < 32) {
                val next = ranges.firstOrNull { it.start <= end + MERGE_GAP_MS && it.end > end }
                    ?: break
                end = next.end
            }
            hit.attempts++
            lastNotice = "已跳过广告片段"
            end
        }
        // 护栏：跨度过大说明时间轴映射不可信，宁可不跳
        if (target - positionMs > MAX_SKIP_MS) {
            AppLog.putDebug("跳过跨度异常(${target - positionMs}ms)，放弃本次跳过")
            return null
        }
        val clamped = if (durationMs > 0) target.coerceAtMost(durationMs) else target
        if (clamped <= positionMs) return null
        player.seekTo(clamped)
        return clamped
    }

    /** 是否已登记了跳过区间（供 UI 提示用） */
    fun hasPendingSkip(): Boolean = synchronized(ranges) { ranges.isNotEmpty() }

    // ---------------------------------------------------------------- 标记

    /** 当前播放位置落在哪个分片上：供"标记为广告"用 */
    fun currentChunk(positionMs: Long): ChunkInfo? = synchronized(chunks) {
        chunks.lastOrNull { it.startMs >= 0 && positionMs >= it.startMs && positionMs < it.endMs }
            ?: chunks.lastOrNull { it.startMs >= 0 && it.endMs <= positionMs + 2_000L }
    }

    /**
     * 以 [positionMs] 为中心，返回前后各 [count] 个分片（含自身），按时间轴升序。
     *
     * 供"标记广告"列表用：用户暂停后长按播放键，弹出的列表就以当前位置为锚，
     * 把前后若干个分片都列出来，靠缩略图把一条连续广告的每一片都挑出来。
     * 没有时间信息的分片（startMs<0）不参与窗口计算——它既没法定位、也没法登记跳过区间。
     */
    fun chunksAround(positionMs: Long, count: Int): List<ChunkInfo> = synchronized(chunks) {
        val sorted = chunks.filter { it.startMs >= 0 && it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (sorted.isEmpty()) return@synchronized emptyList()
        val centerIdx = sorted.indexOfFirst { positionMs >= it.startMs && positionMs < it.endMs }
            .let { if (it >= 0) it else sorted.indexOfLast { c -> c.endMs <= positionMs + 2_000L } }
            .let { if (it < 0) sorted.size / 2 else it }
        val from = (centerIdx - count).coerceAtLeast(0)
        val to = (centerIdx + count).coerceAtMost(sorted.lastIndex)
        sorted.subList(from, to + 1)
    }

    /** 该分片是否已被标记为广告（供列表预勾选用） */
    fun isMarkedAd(url: String): Boolean = verdicts[url] == true

    /**
 * 把当前分片登记为广告（会保存样本文件，必须后台调用）。
 *
 * @param includeNeighbors 连带标记的相邻片数。**这一项很关键**：一条广告通常是连续
 *   几个分片（实测 ffzy 的一条广告 = 3 片），只标停住的那一片，另外两片在指纹库里
 *   依然查不到，从头播放时照样会播出来。取 1 正好覆盖前后各一片。
 * @return 保存出的样本文件列表（文件名, 字节数），失败返回空列表
 */
    suspend fun markAsAd(
        positionMs: Long,
        headers: Map<String, String>,
        includeNeighbors: Int = 1,
        playlistUrl: String? = null,
    ): List<kotlin.Pair<String, Long>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val snapshot = synchronized(chunks) { chunks.toList() }
        val center = currentChunk(positionMs) ?: return@withContext emptyList()
        val idx = snapshot.indexOfFirst { it.url == center.url }
        if (idx < 0) return@withContext emptyList()

        val from = (idx - includeNeighbors).coerceAtLeast(0)
        val to = (idx + includeNeighbors).coerceAtMost(snapshot.lastIndex)

        val saved = ArrayList<kotlin.Pair<String, Long>>()
        for (i in from..to) {
            val c = snapshot.getOrNull(i) ?: continue
            // 没有时间信息的不处理：既无法登记跳过区间，也无法确认它是否相邻
            if (c.startMs < 0 || c.endMs <= c.startMs) continue
            val file = AdFilter.register(c.url, headers, c.size) ?: continue
            verdicts[c.url] = true
            registerRange(c.startMs, c.endMs)
            saved.add(file.name to c.size)
        }
        // 顺带记下这组分片的 #EXTINF 序列。
        // 这一步决定了"以后播别的剧集还会不会遇到这条广告"：新一集的 URL 和本地缓存
        // 全都是陌生的，只有 EXTINF 序列能跨集对上（实测 [4.867,3.333,5.6] 两集完全一致）。
        if (saved.isNotEmpty()) {
            registerExtInfSequence(playlistUrl, center.url, includeNeighbors)
        }
        // 指纹库刚多了一条，立刻回头重判此前挂起的分片（覆盖缓冲得更早、没被上面的
        // 相邻窗口覆盖到的那些片）
        recheckPending(headers)
        if (saved.isNotEmpty()) {
            AppLog.put("已标记 ${saved.size} 个广告分片：${saved.joinToString { it.first }}")
        }
        saved
    }

    /**
     * 把用户**手动勾选**的若干分片逐个登记为广告。
     *
     * 与 [markAsAd] 的固定相邻窗口不同：列表界面里用户是看着缩略图逐片勾选的，
     * 勾了哪些就标哪些，不预设"连续几片"。[centerUrl] 仅用于推算 EXTINF 序列的登记半径，
     * 让"第一集标记完，第二集直接无广告"仍生效（半径取选中片里离中心最远的距离）。
     *
     * @return 保存出的样本文件列表（文件名, 字节数），失败返回空列表
     */
    suspend fun markChunksAsAd(
        urls: List<String>,
        headers: Map<String, String>,
        playlistUrl: String?,
        centerUrl: String?,
    ): List<kotlin.Pair<String, Long>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (urls.isEmpty()) return@withContext emptyList()
        val snapshot = synchronized(chunks) { chunks.toList() }
        val urlSet = urls.toSet()
        val centerIdx = centerUrl?.let { u -> snapshot.indexOfFirst { it.url == u } } ?: -1
        // 播放本地缓存（playlistUrl 是 file:// 的 local.m3u8）时：标记结果还要写进
        // 分片文件夹的 skipped-ads.json，否则下次播放重建 local.m3u8 时广告原样回来
        val epFolder = localPlaylistFolder(playlistUrl)
        val localIndices = HashMap<Int, AdFilter.Fingerprint>()
        val saved = ArrayList<kotlin.Pair<String, Long>>()
        var maxRadius = 0
        for ((i, c) in snapshot.withIndex()) {
            if (c.url !in urlSet) continue
            // 没有时间信息的不处理：既无法登记跳过区间，也无法确认它是否相邻
            if (c.startMs < 0 || c.endMs <= c.startMs) continue
            val isLocal = c.url.startsWith("file:") || c.url.startsWith("content:")
            val file = if (isLocal) {
                // 本地分片：直接读文件登记（VideoCache 只认 http/https）
                val f = Uri.parse(c.url).path?.let { java.io.File(it) }
                if (f != null && f.exists()) AdFilter.registerFile(f, null) else null
            } else {
                AdFilter.register(c.url, headers, c.size)
            } ?: continue
            verdicts[c.url] = true
            registerRange(c.startMs, c.endMs)
            saved.add(file.name to c.size)
            // 把这次标记记进本地剔除记录（序号 + 指纹依据）
            if (epFolder != null) {
                val fp = if (isLocal) {
                    Uri.parse(c.url).path
                        ?.let { AdFilter.matchedFingerprint(java.io.File(it), null) }
                } else {
                    AdFilter.matchedFingerprintFor(c.url, c.size, headers)
                }
                val idx = VideoDownloader.indexOfSegmentInPlaylist(epFolder, c.url)
                if (fp != null && idx >= 0) localIndices[idx] = fp
            }
            if (centerIdx >= 0) maxRadius = kotlin.math.max(maxRadius, kotlin.math.abs(i - centerIdx))
        }
        if (saved.isNotEmpty() && centerUrl != null && !playlistUrl.isNullOrBlank() &&
            !playlistUrl.startsWith("file:")
        ) {
            // 远程播放才登记 EXTINF 序列指纹；本地清单的 EXTINF 已被剔除过，序列对不上
            registerExtInfSequence(playlistUrl, centerUrl, maxRadius.coerceAtLeast(1))
        }
        if (localIndices.isNotEmpty() && epFolder != null) {
            VideoDownloader.applyLocalAdMarks(epFolder, localIndices)
        }
        // 指纹库刚多了一条，立刻回头重判此前挂起的分片
        recheckPending(headers)
        if (saved.isNotEmpty()) {
            AppLog.put("已标记 ${saved.size} 个广告分片：${saved.joinToString { it.first }}")
        }
        saved
    }

    /**
     * 播放的是本地缓存时，从 local.m3u8 的 file:// 地址反推分片文件夹。
     * 认不出（不是本地播放 / 文件夹里没有 playlist.m3u8）返回 null。
     */
    private fun localPlaylistFolder(playlistUrl: String?): java.io.File? {
        if (playlistUrl.isNullOrBlank() || !playlistUrl.startsWith("file:")) return null
        val parent = Uri.parse(playlistUrl).path
            ?.let { java.io.File(it).parentFile } ?: return null
        return parent.takeIf { java.io.File(it, "playlist.m3u8").exists() }
    }

    /**
     * 从清单快照里取出"以 [centerUrl] 为中心"的 EXTINF 序列，登记为清单层指纹。
     *
     * 半径必须与 [AdPlaylistFilter] 过滤时的窗口一致，否则两边对不上，等于没记。
     */
    private fun registerExtInfSequence(
        playlistUrl: String?,
        centerUrl: String,
        radius: Int,
    ) {
        if (playlistUrl.isNullOrBlank()) return
        val info = AdPlaylistFilter.infoOf(playlistUrl) ?: return
        val idx = info.indexOf(centerUrl)
        if (idx < 0) return
        val seq = info.sequenceAround(idx, radius) ?: return
        val host = runCatching { java.net.URI(playlistUrl).host }.getOrNull()
        AdFilter.registerExtInfSequence(seq, host)
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 清单/密钥/初始化段不是视频分片，跳过。
     * 与 [VideoCache.isPlaylist] 同一口径，避免把 key.bin 也拿去算哈希。
     */
    private fun isPlaylistLike(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/m3u8/") ||
            lower.substringBefore('?').endsWith(".mpd") ||
            lower.substringBefore('?').endsWith(".key") ||
            lower.contains("key.bin")
    }

    companion object {
        /** 区间合并的容差：HLS 相邻分片之间可能有毫秒级间隙 */
        private const val MERGE_GAP_MS = 1_500L

        /** 单次跳过的最大跨度。广告一般 15~60 秒，超过这个值几乎肯定是时间轴错乱 */
        private const val MAX_SKIP_MS = 90_000L

        /** 同一区间最多尝试跳过的次数 */
        private const val MAX_ATTEMPTS = 2
    }
}

private fun hostOf(url: String): String? =
    runCatching { java.net.URI(url).host }.getOrNull()
