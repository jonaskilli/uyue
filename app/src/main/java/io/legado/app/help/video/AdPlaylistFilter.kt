package io.legado.app.help.video

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.utils.NetworkUtils
import java.io.File
import java.net.URI

/**
 * 播放清单的广告分片剔除（预防层）。
 *
 * 与 [AdPlaybackController] 的关系是"治疗"与"预防"：
 * - 治疗：片已经下完了，比对指纹，命中就 seek 跳过（[AdPlaybackController]）
 * - 预防：**在 ExoPlayer 请求这一片之前**就把它从清单里删掉，它根本不会被下载、不会缓冲
 *
 * ## 为什么必须"预防"
 * 只做治疗的话，每次播放仍会把广告完整下一遍，占流量、占缓冲，而且识别依赖
 * `AdPlaybackController` 那个内存态的判定表——App 一重启就得重新认一遍。
 * 做了预防，重启后照样拦得住。
 *
 * ## 难点：清单里没有字节数
 * 一份 media playlist 只有 `#EXTINF`（时长）和 URL，**拿不到 size / 内容**，
 * 而指纹是「size + 采样哈希」。所以过滤前必须先把 size 弄到手。
 *
 * 这里走的是一条几乎零成本的路：**[VideoCache] 里已经缓存的分片可以直接读出长度**，
 * 不发任何网络请求。于是天然形成两阶段：
 * - 第一次播某片：清单原样通过（缓存里还没有它），广告被下一遍，随后被识别入库
 * - 之后每次：改写清单时从缓存读 size 命中 → 该片不再出现在清单里
 *
 * ## 为什么"不删缓存"
 * 删了缓存，下次改写清单就读不到 size 了，只能重新下载才能重新认出它——
 * 等于每次重启都要把广告重下一遍。缓存是识别的依据来源，**必须留着**。
 *
 * ## 安全性
 * size 初筛命中后还会读该片缓存的前 [AdFilter] 采样字节做哈希二次确认，
 * 与判定链路的口径完全一致，绝不只凭 size 就删正片。
 */
object AdPlaylistFilter {

    /**
     * 一次清单解析的结果：分片顺序 + 每片的 `#EXTINF` 时长（毫秒）。
     *
     * 这是"清单层指纹"的原料。只靠 size 必须先下载才拿得到，而 URL 又不可用
     * （实测同一条广告在两集里的文件名完全不同），`#EXTINF` 序列是唯一
     * **只读清单就能比对**的跨集稳定特征。
     */
    class PlaylistInfo(val entries: List<Entry>) {
        class Entry(val url: String, val extInfMs: Long)

        /** url -> 它在清单里的下标；认不出返回 -1 */
        fun indexOf(url: String): Int = entries.indexOfFirst { it.url == url }

        /**
         * 取以 [centerIndex] 为中心、左右各 [radius] 片的 EXTINF 序列。
         * 越界就取到头/尾——用户把播放头停在广告第一片时就是这样。
         */
        fun sequenceAround(centerIndex: Int, radius: Int): List<Long>? {
            if (entries.isEmpty()) return null
            val from = (centerIndex - radius).coerceAtLeast(0)
            val to = (centerIndex + radius).coerceAtMost(entries.lastIndex)
            if (to < from) return null
            return entries.subList(from, to + 1).map { it.extInfMs }
        }
    }

    /** 键：把毫秒序列拼成稳定字符串 */
    fun seqKey(seq: List<Long>): String = seq.joinToString(",")

    /**
     * 最近若干次解析出的清单，供"标记为广告"时反查该片在清单里的位置与 EXTINF。
     * 清单每次请求都会重新解析，这里只是留个 handy 的快照，不承担正确性。
     */
    private val infos = HashMap<String, PlaylistInfo>()

    /** 取某份清单的解析结果（标记时用来提取 EXTINF 序列） */
    fun infoOf(playlistUrl: String): PlaylistInfo? = infos[playlistUrl]

    // ---------------------------------------------------------------- 解析

    /**
     * 解析媒体清单，记录每片的 URL 与 EXTINF 时长。
     * 非媒体清单返回 null。
     */
    fun parse(text: String, playlistUrl: String): PlaylistInfo? {
        if (text.contains("#EXT-X-STREAM-INF")) return null
        if (!text.contains("#EXTINF")) return null
        val entries = ArrayList<PlaylistInfo.Entry>()
        var pendingMs = -1L
        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    // #EXTINF:<秒>[,<标题>]
                    val num = line.substringAfter(':', "")
                        .substringBefore(',')
                        .trim()
                    pendingMs = num.toDoubleOrNull()?.let { Math.round(it * 1000) } ?: -1L
                }

                line.isEmpty() || line.startsWith("#") -> Unit

                else -> {
                    val abs = runCatching {
                        NetworkUtils.getAbsoluteURL(playlistUrl, line)
                    }.getOrNull() ?: line
                    entries.add(PlaylistInfo.Entry(abs, pendingMs))
                    pendingMs = -1L
                }
            }
        }
        return if (entries.isEmpty()) null else PlaylistInfo(entries).also {
            if (infos.size > 8) infos.clear()
            infos[playlistUrl] = it
        }
    }

    /**
     * 过滤 media playlist。
     *
     * 两级判据，**都只读清单、不发任何网络请求**：
     * 1. `#EXTINF` 序列指纹（[AdFilter.matchExtInfSequence]）——跨集通用，第二次播放即生效
     * 2. 本地缓存里的字节数（[VideoCache.cachedLength]）——本集内即时生效
     *
     * @return 改写后的清单文本；返回 null 表示**不要改动**，调用方应原样透传。
     */
    fun filter(text: String, playlistUrl: String, headers: Map<String, String>): String? {
        if (!AdFilter.enabled) return null
        val info = parse(text, playlistUrl) ?: return null
        val host = runCatching { URI(playlistUrl).host }.getOrNull()
        val lines = text.lines()
        val out = StringBuilder(text.length)
        val sizeCache = HashMap<String, Long>()

        var removed = 0
        /** 攒着的 #EXTINF：得等看到跟随的分片才能决定留不留 */
        var pendingExtInf: String? = null
        /** 广告块被摘掉后，前后 DISCONTINUITY 会连成串，压成一个即可 */
        var lastDisc = false
        var index = -1

        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTINF") -> pendingExtInf = raw

                line.startsWith("#") -> {
                    if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                        if (!lastDisc) {
                            out.append(raw).append('\n')
                            lastDisc = true
                        }
                    } else {
                        out.append(raw).append('\n')
                    }
                }

                else -> {
                    index++
                    val entry = info.entries.getOrNull(index)
                    val isAd = entry != null && isAdSegment(entry, index, info, host, sizeCache)
                    if (isAd) {
                        // 连带把它的 #EXTINF 一起丢掉：只删 URL 会留下一条无主的 EXTINF
                        pendingExtInf = null
                        removed++
                    } else {
                        pendingExtInf?.let { out.append(it).append('\n') }
                        out.append(raw).append('\n')
                        lastDisc = false
                    }
                }
            }
        }

        if (removed == 0) return null
        AppLog.put("播放清单已剔除 $removed 个广告分片：$playlistUrl")
        return out.toString()
    }

    /** 逐片判定。命中率极低，绝大多数片在这里只是两次 HashMap 查询 */
    private fun isAdSegment(
        entry: PlaylistInfo.Entry,
        index: Int,
        info: PlaylistInfo,
        host: String?,
        sizeCache: HashMap<String, Long>,
    ): Boolean {
        // 一级：EXTINF 序列指纹。整段一起比，天然不会误伤单片的巧合时长
        if (entry.extInfMs > 0 && AdFilter.matchExtInfSequence(info.sequenceAround(index, SEQ_RADIUS), host)) {
            return true
        }
        val cached = sizeCache.getOrPut(entry.url) { AdFilter.cachedLength(entry.url) }
        if (cached <= 0) return false
        return runCatching { AdFilter.isAdBySample(cached, AdFilter.readSampleFor(entry.url), host) }
            .getOrDefault(false)
    }

    /** 序列指纹的匹配半径：与标记时的保存半径保持一致 */
    private const val SEQ_RADIUS = 1
}