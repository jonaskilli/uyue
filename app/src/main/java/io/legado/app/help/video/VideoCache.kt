package io.legado.app.help.video

import android.annotation.SuppressLint
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import io.legado.app.constant.AppLog
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 播放与下载**共用**的视频缓存（[SimpleCache]）。
 *
 * 之前两条链路各走各的：ExoPlayer 直接连源站播，下载器用 okhttp 另下一遍，
 * 同一集要花两份流量。现在：
 * - 播放侧：[FullscreenVideoPlayer] 的 http 数据源被 [wrap] 包一层，
 *   播放缓冲过的分片会落进这份缓存；
 * - 下载侧：[VideoDownloader] 取分片/直链都经由 [open]，命中缓存就是零流量，
 *   没命中才联网，且联网的结果同样写进缓存供播放复用。
 *
 * 于是「边看边存」几乎不再产生额外流量：看过的部分已经在缓存里，
 * 收尾时只需补齐没看过的尾巴再合并成 mp4。
 *
 * 只有 http/https 会走到这层——缓存包在 http 数据源外面，
 * 本地 file:// / content:// 仍由系统数据源直读，不会被复制一份进缓存。
 */
object VideoCache {

    /**
     * 缓存上限：超出后按 LRU 淘汰最久未用的分片。
     * 调大到 1G 也带着性能考虑——每次 commit 都会跑一次 LRU 淘汰（持锁删文件），
     * 上限太小会让淘汰频繁触发，拖慢正在播放的那一路读缓存。
     */
    private const val MAX_BYTES = 1024L * 1024 * 1024

    /** [readBytes] 的单次上限：超过就直接报错，避免把大文件读进内存 */
    private const val MAX_READ_BYTES = 16 * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null
    private val lock = Any()

    private fun get(): SimpleCache {
        cache?.let { return it }
        return synchronized(lock) {
            cache ?: SimpleCache(
                File(appCtx.cacheDir, "video_cache"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(appCtx)
            ).also { cache = it }
        }
    }

    /**
     * 给 http 数据源套上缓存。
     *
     * @param write false = **只读缓存、不写缓存**。播放器正在写缓存时，下载器再写一份
     * 不但多余，还要跟播放器抢 [SimpleCache] 那把锁（每次 commit 都含文件 rename 与
     * 索引落库），是边播边存卡顿的主要来源之一。所以下载侧一律只读。
     *
     * 缓存初始化失败（存储不可用时）直接退回原始数据源——不缓存，但不影响播放与下载。
     */
    fun wrap(upstream: HttpDataSource.Factory, write: Boolean = true): DataSource.Factory {
        val c = runCatching { get() }.onFailure {
            AppLog.put("视频缓存初始化失败，本次不缓存", it)
        }.getOrNull() ?: return upstream
        val cached = CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            // 缓存内容出错时不硬啃缓存，回源重新取
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (!write) cached.setCacheWriteDataSinkFactory(null)
        // 播放列表（.m3u8 / .mpd）不进缓存、始终拉最新：
        // 直播 HLS 的播放列表是滑动窗口，ExoPlayer 会反复用同一 URL 重新拉取以追上最新切片，
        // 一旦被缓存就返回首份旧列表，CDN 回收旧切片后请求 404，表现为"播了一段就报错"。
        // 媒体切片（.ts/.m4s/.mp4 等）仍走缓存，点播的边看边存不受影响。
        return DataSource.Factory { PlaylistAwareDataSource(upstream, cached) }
    }

    /**
     * 按请求地址分流的 DataSource：播放列表走原始 http，其余走缓存。
     *
     * 同一 [DataSource.Factory.createDataSource] 只产出一份实例，真正的下游在 [open]
     * 拿到 [DataSpec.uri] 后才按后缀决定——直播/点播的播放列表与切片都共用这一个工厂，
     * 必须延迟到请求粒度才能区分。
     */
    @SuppressLint("UnsafeOptInUsageError")
    private class PlaylistAwareDataSource(
        private val raw: DataSource.Factory,
        private val cached: DataSource.Factory,
    ) : DataSource {

        private var delegate: DataSource? = null
        // 在 open() 之前就可能被挂上传输监听，先存着，open 时转交给真正的 delegate
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            delegate?.addTransferListener(transferListener)
        }

        @Throws(IOException::class)
        override fun open(dataSpec: DataSpec): Long {
            val ds = (if (isPlaylist(dataSpec.uri.toString())) raw else cached)
                .createDataSource()
            for (listener in listeners) ds.addTransferListener(listener)
            delegate = ds
            return ds.open(dataSpec)
        }

        @Throws(IOException::class)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate!!.read(buffer, offset, length)

        @Throws(IOException::class)
        override fun close() {
            delegate?.close()
            delegate = null
        }

        override fun getUri(): Uri? = delegate?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            delegate?.responseHeaders ?: emptyMap()
    }

    /** 播放列表不能缓存：直播 HLS/DASH 的列表是滑动窗口，旧列表会让切片过期 404 */
    private fun isPlaylist(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/m3u8/") ||
            lower.substringBefore('?').endsWith(".mpd")
    }

    private fun httpFactory(headers: Map<String, String>): HttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .apply { if (headers.isNotEmpty()) setDefaultRequestProperties(headers) }

    /** [open] 的结果：[source] 由调用方负责 close，[length] 可能是 [C.LENGTH_UNSET] */
    class Source internal constructor(
        val source: DataSource,
        val length: Long,
    )

    /**
     * 打开一个走缓存的读取流。
     *
     * @param position 起始偏移；默认从头读，配合缓存可以让已经看过的部分直接命中。
     * @return 已 open 的 [Source]
     */
    fun open(
        url: String,
        headers: Map<String, String>,
        position: Long = 0L,
        write: Boolean = true,
    ): Source {
        val ds = wrap(httpFactory(headers), write).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(url)
            .setPosition(position)
            .apply { if (headers.isNotEmpty()) setHttpRequestHeaders(headers) }
            .build()
        val length = ds.open(spec)
        return Source(ds, length)
    }

    /**
     * 读取全部字节：命中缓存即零流量，未命中则联网（[write] 为真时顺带写入缓存）。
     *
     * **只适合密钥这类小数据**。整体读进内存没有上限，曾因拿它读视频分片而在
     * 256MB 堆的机器上 OOM（分片几 MB 起步、并发一叠就爆）。大块数据一律走 [open] 流式处理。
     */
    fun readBytes(url: String, headers: Map<String, String>, write: Boolean = true): ByteArray {
        val s = open(url, headers, write = write)
        return try {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val read = s.source.read(buf, 0, buf.size)
                if (read == C.RESULT_END_OF_INPUT) break
                if (out.size() + read > MAX_READ_BYTES) {
                    throw IllegalStateException(
                        "单次读取超过 ${MAX_READ_BYTES / 1024 / 1024}MB，请用流式读取"
                    )
                }
                out.write(buf, 0, read)
            }
            out.toByteArray()
        } finally {
            runCatching { s.source.close() }
        }
    }

    /** 该地址是否已有缓存（供调试/判断用） */
    fun isCached(url: String): Boolean = runCatching {
        get().getCachedSpans(url).isNotEmpty()
    }.getOrDefault(false)

    /** 清空缓存（释放空间；mp4 成品不受影响） */
    fun clear() {
        runCatching { get().release() }
        synchronized(lock) {
            cache = null
            File(appCtx.cacheDir, "video_cache").deleteRecursively()
        }
    }
}
