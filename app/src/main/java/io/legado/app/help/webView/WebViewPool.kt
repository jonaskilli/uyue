package io.legado.app.help.webView

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameCache
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.webView.WebJsExtensions.Companion.nameSource
import io.legado.app.model.Download
import io.legado.app.ui.rss.read.VisibleWebView
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.setDarkeningAllowed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.net.URLDecoder
import java.util.Stack
import kotlin.math.max
import kotlin.random.Random

object WebViewPool {
    const val BLANK_HTML = "about:blank"
    const val DATA_HTML = "data:text/html;charset=utf-8;base64,"
    // 未使用的、已预初始化的WebView池 (使用栈结构，后进先出，复用缓存)
    private val idlePool = Stack<PooledWebView>()
    // 正在使用的WebView集合
    private val inUsePool = mutableMapOf<String, PooledWebView>()

    private var needInitialize = true
    private val CACHED_WEB_VIEW_MAX_NUM = max(AppConfig.threadCount / 10, 5) // 池子总容量（闲置+使用）
    private const val IDLE_TIME_OUT: Long = 5 * 60 * 1000 // 闲置5分钟后销毁
    private const val IDLE_TIME_OUT_LAST: Long = 30 * 60 * 1000 // 最后一个闲置30分钟后销毁
    private val cleanupScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private var cleanupJob: Job? = null

    // 获取一个WebView
    @Synchronized
    fun acquire(context: Context): PooledWebView {
        var reused: PooledWebView? = null
        while (idlePool.isNotEmpty()) {
            val pooled = idlePool.pop()
            pooled.inIdlePool = false
            // 兜底：已被销毁的实例直接丢弃，绝不交给调用方（否则页面永远空白）
            if (pooled.destroyed) continue
            reused = pooled.upContext(context)
            break
        }
        val pooledWebView = reused ?: run {
            if (needInitialize) {
                needInitialize = false
                startCleanupTimer()
            }
            createNewWebView().upContext(context) // 创建新实例
        }
        pooledWebView.let {
            it.isInUse = true
            inUsePool[it.id] = it
        }
        // 升级后首次取用 WebView：清一次磁盘缓存与旧 cookie（仅一次）。
        // 解决旧版把源站 4xx / 过期会话写进 WebView 缓存、清空数据前一直复现的问题。
        if (LocalConfig.needClearWebViewCache) {
            runCatching {
                pooledWebView.realWebView.clearCache(true)
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
            }
            LocalConfig.needClearWebViewCache = false
        }
        return pooledWebView
    }

    // 释放WebView回池
    @Synchronized
    fun release(pooledWebView: PooledWebView) {
        if (pooledWebView.destroyed) return
        if (inUsePool.remove(pooledWebView.id) == null) {
            // 不在"在用"集合里 = 已经被释放过一次（重复释放）。
            // 若它此刻正躺在闲置池里，绝不能销毁：否则池里会留下一个死实例，
            // 之后每次 acquire 都拿到它 —— 页面永远空白、进度条卡死，只能重启 App 恢复。
            if (!pooledWebView.inIdlePool) {
                destroyPooled(pooledWebView)
            }
            return
        }
        pooledWebView.upContext(MutableContextWrapper(appCtx))
        // 重置WebView状态
        resetWebView(pooledWebView.realWebView)
        pooledWebView.isInUse = false
        if (idlePool.size < CACHED_WEB_VIEW_MAX_NUM - inUsePool.size) {
            pooledWebView.lastUseTime = System.currentTimeMillis()
            pooledWebView.inIdlePool = true
            idlePool.push(pooledWebView)
        } else {
            // 池子已满，直接销毁
            destroyPooled(pooledWebView)
        }
    }

    /**
     * 丢弃：直接销毁且**不回池**。
     * 实例已损坏（渲染进程崩溃、白屏）时必须用这个，否则坏实例会被后续 acquire 取到，
     * 表现就是"换多少个页面都是白的、只能重启 App"。
     */
    @Synchronized
    fun discard(pooledWebView: PooledWebView) {
        inUsePool.remove(pooledWebView.id)
        destroyPooled(pooledWebView)
    }

    /** 统一销毁入口：打上标记，避免同一实例被销毁两次或销毁后仍留在池里 */
    private fun destroyPooled(pooled: PooledWebView) {
        if (pooled.destroyed) return
        pooled.destroyed = true
        pooled.inIdlePool = false
        runCatching { pooled.realWebView.destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resetWebView(webView: WebView) = with(webView) {
        try {
            (parent as? ViewGroup)?.removeView(webView)
            stopLoading()
            clearFocus() //清除焦点
            webView.setOnLongClickListener { _ ->
                return@setOnLongClickListener false
            }
            webChromeClient = null
            webViewClient = WebViewClient()

//            webView.clearCache(false) //清除缓存
//            webView.clearHistory() //清除历史记录
            clearFormData() //清除表单数据
            clearMatches() //清除查找匹配项
//            webView.clearSslPreferences() //清除SSL首选项
//            clearDisappearingChildren() //清除消失中的子视图
            clearAnimation() //清除动画
            removeJavascriptInterface(nameBasic)
            removeJavascriptInterface(nameJava)
            removeJavascriptInterface(nameSource)
            removeJavascriptInterface(nameCache)
            settings.apply {
                javaScriptEnabled = false
                javaScriptEnabled = true // 禁用再启用来重置js环境，注意需要禁用的订阅源需要再次执行
                blockNetworkImage = false // 确保允许加载网络图片
                cacheMode = WebSettings.LOAD_DEFAULT // 重置缓存模式
            }
            loadUrl(BLANK_HTML)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createNewWebView(): PooledWebView {
        val webView = VisibleWebView(MutableContextWrapper(appCtx))
        preInitWebView(webView)
        return PooledWebView(webView, generateId())
    }

    private fun generateId(): String {
        return "web_${System.currentTimeMillis()}_${Random.nextLong()}"
    }

    // 初始化
    @SuppressLint("SetJavaScriptEnabled")
    private fun preInitWebView(webView: WebView) {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.settings.apply {
            javaScriptEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowContentAccess = true
            builtInZoomControls = true
            displayZoomControls = false
            setDarkeningAllowed(AppConfig.isNightTheme)
            textZoom = 100
        }
        webView.setDownloadListener { url, _, contentDisposition, _, _ ->
            var fileName = URLUtil.guessFileName(url, contentDisposition, null)
            fileName = URLDecoder.decode(fileName, "UTF-8")
            webView.longSnackbar(fileName, appCtx.getString(R.string.action_download)) {
                Download.start(appCtx, url, fileName)
            }
        }
        webView.setOnLongClickListener { _ ->
            return@setOnLongClickListener false
        }
    }

    // 定时清理闲置过久的WebView
    private fun startCleanupTimer() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = cleanupScope.launch {
            while (true) {
                delay(30_000) // 每30秒执行一次清理
                val now = System.currentTimeMillis()
                val toRemove = mutableListOf<PooledWebView>()
                var shouldCancel = false
                synchronized(this@WebViewPool) {
                    for ((index, pooled) in idlePool.withIndex()) {
                        val timeout = if (index == 0) {
                            IDLE_TIME_OUT_LAST
                        } else {
                            IDLE_TIME_OUT
                        }
                        if (now - pooled.lastUseTime > timeout) {
                            toRemove.add(pooled)
                        }
                    }
                    toRemove.forEach { pooled ->
                        idlePool.remove(pooled)
                        pooled.inIdlePool = false
                        destroyPooled(pooled)
                    }
                    if (idlePool.isEmpty()) {
                        shouldCancel = true
                    }
                }
                if (shouldCancel) {
                    needInitialize = true
                    this@launch.cancel()
                }
            }
        }
    }

}