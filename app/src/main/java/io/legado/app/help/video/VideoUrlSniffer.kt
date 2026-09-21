package io.legado.app.help.video

import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.utils.runOnUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网页播放器地址嗅探：把加密/校验过的视频地址从网页里"钓"出来。
 *
 * 有些视频源给的播放页是个网页（站点自己的播放器 + 解密 JS），真正的流地址由页面里的 JS
 * 算出来、或由播放器动态请求，ExoPlayer 直接播必然失败。这里在后台用真实 WebView 打开
 * 这个页面，让站点的 JS 正常跑起来，再从页面发出的请求里截获可播地址，交回 ExoPlayer。
 *
 * 做法与 1DM+ 这类嗅探下载器同源（浏览器 + 拦截全部资源请求），区别只是目的不同：
 * 它们拿到地址后要自己解析 m3u8、下全部分片、处理 EXT-X-KEY 再合并成本地文件；
 * 我们只要拿到直链，分片与密钥请求由 ExoPlayer 自己发（且已带书源 header）。
 *
 * 两个识别口径，缺一不可：
 * 1. 地址像媒体（[VideoSourcePlayer.isPlayableUrl]：m3u8/mp4/m4v/webm/authPath…）
 * 2. 地址不像媒体但**内容确实是媒体**——用 HEAD 探测 Content-Type。
 *    /api/stream/123 这种没有后缀的加密接口全靠这一条，否则直接漏掉。
 */
object VideoUrlSniffer {

    /** 嗅探结果：地址 + 发起该请求时的 Referer（给 ExoPlayer 带上，过 Referer 校验） */
    data class Result(val url: String, val referer: String? = null)

    /** 整个页面的嗅探上限 */
    private const val TIMEOUT_MS = 20_000L
    /** 首次命中后再等一会儿：高清轨/m3u8 往往比首个请求晚一点，避免抓到低码率那条 */
    private const val GRACE_MS = 1_500L
    /** 探测可疑地址时最多试几条 */
    private const val MAX_PROBE = 6

    private data class Candidate(
        val url: String,
        val referer: String?,
        val playable: Boolean,
        val score: Int,
    )

    suspend fun sniff(
        pageUrl: String,
        headers: Map<String, String> = emptyMap(),
    ): Result? = withContext(Dispatchers.IO) {
        if (pageUrl.isBlank() || !pageUrl.startsWith("http", true)) return@withContext null
        runCatching { sniffInternal(pageUrl, headers) }
            .onFailure { AppLog.put("[视频嗅探] 失败：${it.localizedMessage}", it) }
            .getOrNull()
    }

    private suspend fun sniffInternal(
        pageUrl: String,
        headers: Map<String, String>,
    ): Result? {
        val found = Collections.synchronizedList(mutableListOf<Candidate>())
        val pooled = withContext(Dispatchers.Main) { acquire(pageUrl, headers, found) }
            ?: return null
        try {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var firstHitAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val playable = found.filter { it.playable }
                if (playable.isNotEmpty()) {
                    if (firstHitAt == 0L) {
                        firstHitAt = System.currentTimeMillis()
                    } else if (System.currentTimeMillis() - firstHitAt > GRACE_MS) {
                        return best(playable)
                    }
                }
                delay(200)
            }
            // 到点没有"像媒体"的地址：退而求其次，对可疑地址 HEAD 探测内容类型
            found.filter { it.playable }.let { if (it.isNotEmpty()) return best(it) }
            return probe(found, headers)
        } finally {
            withContext(Dispatchers.Main) { WebViewPool.release(pooled) }
        }
    }

    /** 起一个后台 WebView 打开页面，并挂上拦截回调收集请求 */
    private fun acquire(
        pageUrl: String,
        headers: Map<String, String>,
        sink: MutableList<Candidate>,
    ): PooledWebView? {
        val pooled = runCatching { WebViewPool.acquire(appCtx) }.getOrNull() ?: return null
        val webView = pooled.realWebView
        webView.resumeTimers()
        webView.onResume()
        webView.settings.userAgentString = headers[AppConst.UA_NAME] ?: AppConfig.userAgent
        // 会话：缺 cookie 的源会被直接 4xx
        runCatching { CookieManager.applyToWebView(pageUrl) }
        val seen = AtomicBoolean(false)
        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val u = request?.url?.toString()
                if (!u.isNullOrBlank()) {
                    val referer = request.requestHeaders["Referer"]
                    val playable = VideoSourcePlayer.isPlayableUrl(u)
                    val score = scoreOf(u)
                    if (playable || score > 0) {
                        synchronized(sink) {
                            if (sink.none { it.url == u }) {
                                sink.add(Candidate(u, referer, playable, score))
                            }
                        }
                        if (playable && seen.compareAndSet(false, true)) {
                            AppLog.put("[视频嗅探] 命中：$u")
                        }
                    }
                }
                // 原样放行：不能返回假响应，否则页面自己会加载坏
                return super.shouldInterceptRequest(view, request)
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?,
            ) {
                // 与内置播放器一致：自签/过期证书一律放行，否则这类源根本起不来
                handler?.proceed()
            }
        }
        runCatching {
            if (headers.isEmpty()) webView.loadUrl(pageUrl)
            else webView.loadUrl(pageUrl, HashMap(headers))
        }.onFailure {
            runOnUI { WebViewPool.release(pooled) }
            return null
        }
        return pooled
    }

    /** 多个命中里挑最好的：m3u8 优先（自适应码率），其次关键字得分高的 */
    private fun best(list: List<Candidate>): Result {
        val pick = list.maxWithOrNull(
            compareBy<Candidate> { if (it.url.contains("m3u8", true)) 1 else 0 }
                .thenBy { it.score }
        ) ?: list.first()
        return Result(pick.url, pick.referer)
    }

    /** 地址不像媒体但可能是媒体的可疑度；0 表示不用管 */
    private fun scoreOf(url: String): Int {
        val lower = url.lowercase()
        if (STATIC_SUFFIX.any { lower.substringBefore('?').endsWith(it) }) return 0
        var s = 0
        if (lower.contains("m3u8")) s += 5
        if (lower.contains("mpd")) s += 4
        if (lower.contains("video") || lower.contains("stream")) s += 3
        if (lower.contains("play") || lower.contains("media") || lower.contains("hls")) s += 2
        if (lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".webm")) s += 3
        return s
    }

    /** HEAD 探测：只按后缀认不出 "/api/stream/123" 这类接口，必须看 Content-Type */
    private fun probe(list: List<Candidate>, headers: Map<String, String>): Result? {
        val candidates = list.filter { !it.playable && it.score > 0 }
            .sortedByDescending { it.score }
            .take(MAX_PROBE)
        for (c in candidates) {
            val type = runCatching {
                val req = Request.Builder().url(c.url).head().apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                okHttpClient.newCall(req).execute().use { it.header("Content-Type").orEmpty() }
            }.getOrNull() ?: continue
            if (isMediaType(type)) {
                AppLog.put("[视频嗅探] HEAD 确认：$type -> ${c.url}")
                return Result(c.url, c.referer)
            }
        }
        return null
    }

    private fun isMediaType(type: String): Boolean {
        val t = type.lowercase()
        return t.contains("mpegurl") ||
            t.startsWith("video/") ||
            t.startsWith("audio/") ||
            t.contains("dash") ||
            t.contains("mp2t")
    }

    /** 明显不是媒体的静态资源，直接不参与嗅探 */
    private val STATIC_SUFFIX = listOf(
        ".js", ".css", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
        ".woff", ".woff2", ".ttf", ".ico", ".json", ".xml", ".html", ".htm", ".txt",
    )
}
