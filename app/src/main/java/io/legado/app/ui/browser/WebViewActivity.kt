package io.legado.app.ui.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.core.view.size
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppConst
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.ActivityWebViewBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.ACache
import io.legado.app.utils.gone
import io.legado.app.utils.invisible
import io.legado.app.utils.keepScreenOn
import io.legado.app.utils.longSnackbar
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.openUrl
import io.legado.app.utils.sendToClip
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.toggleSystemBar
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import android.webkit.JavascriptInterface
import io.legado.app.constant.AppLog
import io.legado.app.help.video.VideoTextLocalizeJs
import io.legado.app.help.webView.WebJsExtensions
import io.legado.app.help.webView.WebJsExtensions.Companion.basicJs
import io.legado.app.help.webView.WebJsExtensions.Companion.nameBasic
import io.legado.app.help.webView.WebJsExtensions.Companion.nameJava
import io.legado.app.help.http.CookieManager as AppCookieManager
import androidx.core.net.toUri
import io.legado.app.help.webView.PooledWebView
import io.legado.app.help.webView.WebViewPool
import io.legado.app.help.webView.WebViewPool.BLANK_HTML
import io.legado.app.help.webView.WebViewPool.DATA_HTML
import java.lang.ref.WeakReference
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import io.legado.app.ui.timetech.player.LocalVideo
import io.legado.app.ui.book.video.SourceVideoPlayerSession

class WebViewActivity : VMBaseActivity<ActivityWebViewBinding, WebViewModel>() {
    companion object {
        // 是否输出日志
        var sessionShowWebLog = false

        // 视频模式同一时刻只允许一个实例：新开时销毁旧的，避免多个内置播放器叠加、残留后台
        private var currentVideoInstance: WebViewActivity? = null

        /** 重建页面时传递"剩余自动重建次数" */
        const val EXTRA_AUTO_REBUILD = "autoRebuildLeft"

        /** 销毁当前存活的视频模式内置浏览器（打开时光播放器或新视频时调用） */
        fun finishVideoInstance() {
            currentVideoInstance?.let {
                if (!it.isFinishing) it.finish()
            }
        }
    }

    private lateinit var pooledWebView: PooledWebView
    private lateinit var currentWebView: WebView

    override val binding by viewBinding(ActivityWebViewBinding::inflate)
    override val viewModel by viewModels<WebViewModel>()
    private var customWebViewCallback: WebChromeClient.CustomViewCallback? = null
    private var webPic: String? = null
    private var isCloudflareChallenge = false
    private var isFullScreen = false
    /** 全屏状态的可观察版本：右上角浮层要靠它决定排在顶部还是下移 */
    private var fullScreenState by mutableStateOf(false)
    private var isfullscreen = false
    private var needClearHistory = true
    private var videoMode = false
    /** 视频模式会话预热地址：先加载它把源站会话 cookie 种进全局 WebView CookieManager，再加载真正的播放页 */
    private var preloadUrl: String? = null
    private var awaitingPreload = false
    private var realLoadUrl: String? = null
    private var realHeaderMap: HashMap<String, String>? = null
    private val saveImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ACache.get().put(imagePathKey, uri.toString())
            viewModel.saveImage(webPic, uri.toString())
        }
    }

    private fun refresh() {
        currentWebView.reload()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        currentWebView.post {
            currentWebView.clearHistory()
        }
        binding.titleBar.title = intent.getStringExtra("title") ?: getString(R.string.loading)
        binding.titleBar.subtitle = intent.getStringExtra("sourceName")
        // 视频书源播放模式：无顶栏、沉浸式全屏，返回键直接关闭
        videoMode = intent.getBooleanExtra("videoMode", false)
        preloadUrl = intent.getStringExtra("preloadUrl")
        // 勾过全屏就一直全屏：切换视频会重建页面，靠偏好把状态带过来
        if (getPrefBoolean(PreferKey.browserFullScreen, false)) {
            applyFullScreen(true)
        }

        if (videoMode) {
            // 视频模式单实例：把仍存活的旧实例先销毁，避免叠加/残留后台
            if (currentVideoInstance != null && currentVideoInstance !== this) {
                currentVideoInstance?.finish()
            }
            currentVideoInstance = this
            isFullScreen = true
            toggleSystemBar(false)
            supportActionBar?.hide()
            // 内置浏览器型视频源：在播放界面叠加「加入书架」与「目录」图标，
            // 行为与时光播放器（SourceVideoPlayerActivity）对齐——不自动存书架，由用户手动点。
            if (SourceVideoPlayerSession.getBook() != null) {
                val overlay = ComposeView(this)
                overlay.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.TOP or Gravity.END }
                binding.root.addView(overlay)
                overlay.setContent {
                    val state by SourceVideoPlayerSession.uiState.collectAsState()
                    VideoModeActionOverlay(
                        episodes = state.episodes,
                        currentIndex = state.currentIndex,
                        onAddShelf = { SourceVideoPlayerSession.addToShelf() },
                        onPickEpisode = { index -> playEpisode(index) },
                        onClearCache = { clearCacheAndReload() },
                        fullscreen = fullScreenState,
                        onToggleFullScreen = { toggleFullScreen() },
                    )
                }
            }
        }
        viewModel.initData(intent) {
            val url = viewModel.baseUrl
            val headerMap = viewModel.headerMap
            initWebView(url, headerMap)
            if (videoMode) {
                currentWebView.setOnTouchListener(videoSwipeListener)
                binding.customWebView.setOnTouchListener(videoSwipeListener)
            }
            val html = viewModel.html
            realLoadUrl = url
            realHeaderMap = headerMap
            // 视频模式：若该 host 还没有会话 cookie（例如书架直开、没先在源里逛过），
            // 先加载源站列表页把会话 cookie 种进全局 WebView CookieManager，再加载真正的播放页，
            // 否则源站会因缺会话直接回 4xx。预热页与播放页必须同 host 才有意义。
            val startUrl = if (videoMode
                && preloadUrl != null
                && preloadUrl != url
                && NetworkUtils.getBaseUrl(preloadUrl) == NetworkUtils.getBaseUrl(url)
                && !hasWebViewCookie(url)
            ) {
                awaitingPreload = true
                preloadUrl!!
            } else {
                url
            }
            if (html.isNullOrEmpty()) {
                currentWebView.loadUrl(startUrl, headerMap)
            } else {
                if (viewModel.localHtml) {
                    viewModel.source?.let {
                        val webJsExtensions = WebJsExtensions(it, this, currentWebView)
                        currentWebView.addJavascriptInterface(webJsExtensions, nameJava)
                    }
                }
                currentWebView.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
            }
        }
        currentWebView.clearHistory()
        onBackPressedDispatcher.addCallback(this) {
            // 视频播放页：按返回直接关闭，不走网页回退逻辑
            if (videoMode) {
                finish()
                return@addCallback
            }
            if (binding.customWebView.size > 0) { //网页全屏
                customWebViewCallback?.onCustomViewHidden()
                return@addCallback
            }
            if (isFullScreen) { //按钮全屏
                toggleFullScreen()
                return@addCallback
            }
            if (currentWebView.canGoBack()) {
                val list = currentWebView.copyBackForwardList()
                val size = list.size
                if (size == 1) {
                    finish()
                    return@addCallback
                }
                val currentIndex = list.currentIndex
                val currentItem = list.currentItem
                val currentUrl = currentItem?.originalUrl ?: BLANK_HTML
                val currentTitle = currentItem?.title
                var steps = 1
                for (i in currentIndex - 1 downTo 0) {
                    val item = list.getItemAtIndex(i)
                    val itemUrl = item.originalUrl
                    if (itemUrl == BLANK_HTML) {
                        finish()
                        return@addCallback
                    }
                    if (itemUrl != currentUrl || currentTitle != item.title) {
                        break
                    }
                    if (currentUrl == DATA_HTML) {
                        break
                    }
                    steps++
                }
                if (steps == size) {
                    finish()
                    return@addCallback
                }
                currentWebView.goBackOrForward(-steps)
                return@addCallback
            }
            finish()
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_view, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        if (viewModel.sourceOrigin.isNotEmpty()) {
            menu.findItem(R.id.menu_disable_source)?.isVisible = true
            menu.findItem(R.id.menu_delete_source)?.isVisible = true
        }
        menu.findItem(R.id.menu_show_web_log)?.isChecked = sessionShowWebLog
        // 全屏是持久偏好，这里把勾选状态同步到菜单上
        menu.findItem(R.id.menu_full_screen)?.isChecked = isFullScreen
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_web_refresh -> refresh()
            R.id.menu_open_in_browser -> openUrl(viewModel.baseUrl)
            R.id.menu_copy_url -> sendToClip(viewModel.baseUrl)
            R.id.menu_ok -> {
                if (viewModel.sourceVerificationEnable) {
                    viewModel.saveVerificationResult(currentWebView) {
                        finish()
                    }
                } else {
                    finish()
                }
            }

            R.id.menu_full_screen -> toggleFullScreen()
            R.id.menu_clear_cache -> clearCacheAndReload()
            R.id.menu_show_web_log -> {
                sessionShowWebLog = !sessionShowWebLog
                item.isChecked = sessionShowWebLog
            }
            R.id.menu_disable_source -> {
                viewModel.disableSource {
                    finish()
                }
            }

            R.id.menu_delete_source -> {
                alert(R.string.draw) {
                    setMessage(getString(R.string.sure_del) + "\n" + viewModel.sourceName)
                    noButton()
                    yesButton {
                        viewModel.deleteSource {
                            finish()
                        }
                    }
                }
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    //实现starBrowser调起页面全屏
    private fun toggleFullScreen() {
        val next = !isFullScreen
        applyFullScreen(next)
        // 记住选择：切换视频会重建本 Activity，只靠内存里的状态带不过去，必须落盘。
        // 勾过一次之后，每次打开内置浏览器/内置播放器都直接是全屏。
        putPrefBoolean(PreferKey.browserFullScreen, next)
    }

    /** 403/401 时自动清缓存重开，只做一次，避免失效源反复重试 */
    private var autoClearedOnError = false
    /** WebView 是否已归还池（防重复释放） */
    private var webViewReleased = false
    /** 上次滑动切集/切视频的时间：连续快滑时丢掉过密的请求 */
    private var lastSwitchAt = 0L
    /** 是否开启"检测到问题就自动重建"：由删除图标切换，第一次点开启、再点停止 */
    private var autoRebuildEnabled = false
    /** 本页是否已成功加载过：重建后据此判断"还是不是白屏" */
    private var pageLoaded = false

    /**
     * 清除本页缓存并重开。
     *
     * 合成认证/登录态失效后，站点常把它缓存成 403 页面（openresty 那种
     * "403 Forbidden … Hit-status"），之后怎么刷新都是同一张错误页。
     * 这里清掉 WebView 缓存与该站点的 cookie，让页面重新走一次认证。
     */
    /**
     * 废弃当前 WebView、换一个新实例并重新加载。
     *
     * 白屏的根因常常不是缓存，而是这个 WebView 实例本身废了（典型是渲染进程崩溃），
     * 那时 clearCache / reload 都不会有任何效果——必须换实例。
     * 坏实例走 discard 直接销毁且不回池，避免毒化整个池。
     */
    private fun recreateWebView() {
        val url = realLoadUrl ?: viewModel.baseUrl
        val headerMap = realHeaderMap ?: viewModel.headerMap
        // 原地销毁旧实例、换一个新的并重新加载（最开始的那一版做法）
        runCatching {
            binding.webViewContainer.removeView(currentWebView)
            // 坏实例直接销毁且不回池，避免毒化整个池
            WebViewPool.discard(pooledWebView)
            webViewReleased = true // 已丢弃，onDestroy 不要再释放一次
        }
        pooledWebView = WebViewPool.acquire(this)
        currentWebView = pooledWebView.realWebView
        binding.webViewContainer.addView(currentWebView)
        initWebView(url, headerMap)
        if (videoMode) {
            currentWebView.setOnTouchListener(videoSwipeListener)
            binding.customWebView.setOnTouchListener(videoSwipeListener)
        }
        currentWebView.loadUrl(url, headerMap)
    }

    private fun clearCacheAndReload() {
        // 先放开全局定时器：若之前有 Activity 把进程留在"全局暂停"状态，
        // 所有 WebView 都不会加载，光清缓存没用，这一步能把白屏直接救回来
        runCatching {
            currentWebView.resumeTimers()
            currentWebView.onResume()
        }
        runCatching { currentWebView.clearCache(true) }
        // cookie 里过期的认证信息同样是 403 的常见来源，一并清掉触发重新认证
        runCatching {
            val base = NetworkUtils.getBaseUrl(realLoadUrl ?: viewModel.baseUrl)
            if (!base.isNullOrBlank()) {
                val cm = android.webkit.CookieManager.getInstance()
                cm.getCookie(base)?.split(";")?.forEach { kv ->
                    val name = kv.substringBefore('=').trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(
                            base,
                            "$name=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/",
                        )
                    }
                }
            }
        }
        // 删除图标 = 自动重建模式开关：第一次点开启，再点停止。
        // 开启后由"检测到问题"（渲染进程崩溃）触发重建，不再一上来就重建出一张空白页。
        autoRebuildEnabled = !autoRebuildEnabled
        toastOnUi(
            if (autoRebuildEnabled) "已开启自动重建（再点一次停止）" else "已停止自动重建"
        )
    }



    /** 应用/取消全屏：隐藏顶栏 + 沉浸式 */
    private fun applyFullScreen(full: Boolean) {
        isFullScreen = full
        fullScreenState = full
        toggleSystemBar(!full)
        if (full) {
            supportActionBar?.hide()
            // 直接把顶栏视图去掉：TitleBar 的 setSupportActionBar 晚于 onCreate，
            // 此刻 supportActionBar 常常还是 null，只调 hide() 会隐藏不掉
            // （表现就是"勾了全屏但工具栏还在"）
            binding.titleBar.gone()
        } else {
            binding.titleBar.visible()
            supportActionBar?.show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(url: String, headerMap: HashMap<String, String>) {
        binding.progressBar.fontColor = accentColor
        currentWebView.webChromeClient = CustomWebChromeClient()
        // 添加 JavaScript 接口
        currentWebView.addJavascriptInterface(JSInterface(this), nameBasic)
        currentWebView.webViewClient = CustomWebViewClient()
        currentWebView.settings.apply {
            useWideViewPort = true
            loadWithOverviewMode = true
            // 视频模式：禁用 WebView 缓存。否则源站偶发的 4xx（防盗链/限频/token 失效）
            // 会被 Chromium 写进缓存并回放，表现为"网页无法加载 net::ERR_HTTP_RESPONSE_CODE_FAILURE"，
            // 且清空应用数据前一直复现。禁用后每次都重新联网，也保证直播始终拉到最新播放列表。
            cacheMode = if (videoMode) {
                android.webkit.WebSettings.LOAD_NO_CACHE
            } else {
                android.webkit.WebSettings.LOAD_DEFAULT
            }
            headerMap[AppConst.UA_NAME]?.let {
                userAgentString = it
            }
        }
        AppCookieManager.applyToWebView(url)
        currentWebView.setOnLongClickListener {
            val hitTestResult = currentWebView.hitTestResult
            if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE ||
                hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.let { webPic ->
                    selector(
                        arrayListOf(
                            SelectItem(getString(R.string.action_save), "save"),
                            SelectItem(getString(R.string.select_folder), "selectFolder")
                        )
                    ) { _, charSequence, _ ->
                        when (charSequence.value) {
                            "save" -> saveImage(webPic)
                            "selectFolder" -> selectSaveFolder()
                        }
                    }
                    return@setOnLongClickListener true
                }
            }
            return@setOnLongClickListener false
        }
    }

    private fun saveImage(webPic: String) {
        this.webPic = webPic
        val path = ACache.get().getAsString(imagePathKey)
        if (path.isNullOrEmpty()) {
            selectSaveFolder()
        } else {
            viewModel.saveImage(webPic, path)
        }
    }

    private fun selectSaveFolder() {
        val default = arrayListOf<SelectItem<Int>>()
        val path = ACache.get().getAsString(imagePathKey)
        if (!path.isNullOrEmpty()) {
            default.add(SelectItem(path, -1))
        }
        saveImage.launch {
            otherActions = default
        }
    }

    override fun finish() {
        SourceVerificationHelp.checkResult(viewModel.sourceOrigin)
        super.finish()
    }

    private fun close() {
        if (!isCloudflareChallenge) {
            if (viewModel.sourceVerificationEnable) {
                viewModel.saveVerificationResult(currentWebView) {
                    finish()
                }
            }
            else {
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 注意：不要再调 currentWebView.pauseTimers()。
        // pauseTimers() 是**全局**静态方法（虽然写成了实例调用），会暂停进程内所有 WebView 的
        // 布局/解析/JS 定时器。快速切换时本页先 finish 触发 onPause，而新播放页是异步拉起的，
        // 一旦它被单实例逻辑杀掉或还没起来，配对的 resumeTimers() 就永远不会执行——
        // 结果就是进程内所有 WebView 都不再加载（到处白屏，只能重启 App）。
        // 这里只做"当前这一个"的暂停即可。
        currentWebView.onPause()
    }

    override fun onResume() {
        super.onResume()
        // 兜底：视频模式下若已经有更新的实例在前台，本页就是残留的重复页。
        // 两层叠着时，看到的是下面那张空白页，而右上角图标属于上面这层——
        // 于是"点了重建却没反应"。这里让旧的重复页自己退掉，保证只剩一层。
        if (videoMode && currentVideoInstance != null && currentVideoInstance !== this) {
            AppLog.put("[内置播放器] 发现重复的视频页实例，关闭残留页")
            finish()
            return
        }
        // 兜底：无论之前是谁暂停的，回到前台都把全局定时器放开
        runCatching { currentWebView.resumeTimers() }
        currentWebView.onResume()
    }

    override fun onDestroy() {
        if (currentVideoInstance === this) currentVideoInstance = null
        // 页面销毁时把全局定时器恢复，避免任何路径把进程留在"全局暂停"状态
        runCatching { currentWebView.resumeTimers() }
        // 只释放一次：重复 release 会把已回池的实例再销毁一次，
        // 池里就会留下死 WebView，之后所有内置页面都空白、只能重启 App 恢复
        if (!webViewReleased && ::pooledWebView.isInitialized) {
            webViewReleased = true
            WebViewPool.release(pooledWebView)
        }
        super.onDestroy()
    }

    @Suppress("unused")
    class JSInterface(activity: WebViewActivity) {
        private val activityRef: WeakReference<WebViewActivity> = WeakReference(activity)
        @JavascriptInterface
        fun lockOrientation(orientation: String) {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    if (ctx.isfullscreen) {
                        ctx.requestedOrientation = when (orientation) {
                            "portrait", "portrait-primary" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            "portrait-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            "landscape", "landscape-primary" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            "landscape-secondary" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                            "any", "unspecified" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                    }
                }
            }

        }

        @JavascriptInterface
        fun onCloseRequested() {
            val ctx = activityRef.get()
            if (ctx != null && !ctx.isFinishing && !ctx.isDestroyed) {
                ctx.runOnUiThread {
                    ctx.close()
                }
            }
        }
    }

    inner class CustomWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.progressBar.setDurProgress(newProgress)
            binding.progressBar.gone(newProgress == 100)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            isfullscreen = true
            binding.llView.invisible()
            binding.customWebView.addView(view)
            customWebViewCallback = callback
            keepScreenOn(true)
            toggleSystemBar(false)
        }

        override fun onHideCustomView() {
            isfullscreen = false
            binding.customWebView.removeAllViews()
            binding.llView.visible()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            keepScreenOn(false)
            toggleSystemBar(true)
        }

        /* 覆盖window.close() */
        override fun onCloseWindow(window: WebView?) {
            close()
        }

        /* 监听网页日志 */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            viewModel.source?.let {
                if (sessionShowWebLog) {
                    val consoleException = Exception("${consoleMessage.messageLevel().name}: \n${consoleMessage.message()}\n-Line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                    val message = viewModel.sourceName + ": ${consoleMessage.message()}"
                    when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.LOG -> AppLog.put(message)
                        ConsoleMessage.MessageLevel.DEBUG -> AppLog.put(message, consoleException)
                        ConsoleMessage.MessageLevel.WARNING -> AppLog.put(message, consoleException)
                        ConsoleMessage.MessageLevel.ERROR -> AppLog.put(message, consoleException)
                        ConsoleMessage.MessageLevel.TIP -> AppLog.put(message)
                        else -> AppLog.put(message)
                    }
                    return true
                }
            }
            return false
        }
        
    }

    inner class CustomWebViewClient : WebViewClient() {

        /**
         * 渲染进程崩溃。**仅在删除图标开启"自动重建"后才重建**：
         * 池化复用的 WebView 在不可见/被回收时也会收到该回调（并非真的崩了），
         * 无条件重建会在正常播放时反复换实例、导致播不了。
         */
        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            if (!autoRebuildEnabled) return false
            AppLog.put("[内置播放器] WebView 渲染进程崩溃，自动重建")
            runOnUiThread { recreateWebView() }
            return true
        }

        /**
         * 主文档返回 401/403：多半是认证过期后被缓存住了，自动清一次缓存重开。
         * 只自动重试一次，源本身失效时不再反复刷新。
         */
        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            val code = errorResponse?.statusCode ?: return
            if (code != 403 && code != 401) return
            if (request?.isForMainFrame != true) return
            if (autoClearedOnError) return
            autoClearedOnError = true
            AppLog.put("[内置播放器] 主文档返回 $code，自动清缓存重开")
            clearCacheAndReload()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            request?.let {
                return shouldOverrideUrlLoading(it.url)
            }
            return true
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION", "KotlinRedundantDiagnosticSuppress")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            url?.let {
                return shouldOverrideUrlLoading(it.toUri())
            }
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (needClearHistory) {
                needClearHistory = false
                currentWebView.clearHistory() //清除历史
            }
            super.onPageStarted(view, url, favicon)
            currentWebView.evaluateJavascript(basicJs, null)
        }
        
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val cookieManager = CookieManager.getInstance()
            url?.let {
                CookieStore.setCookie(it, cookieManager.getCookie(it))
            }
            // 视频播放模式：会话预热页加载完成，立即加载真正的播放页。
            // 此刻全局 WebView CookieManager 已带上源站会话，播放页及其 .m3u8 子资源即可通过鉴权。
            if (videoMode && awaitingPreload) {
                awaitingPreload = false
                val real = realLoadUrl
                if (real != null) {
                    currentWebView.loadUrl(real, realHeaderMap ?: HashMap())
                    return
                }
            }
            // 真正的页面（非会话预热页）加载完成：标记为"不是白屏"，
            // 重建序列据此判断还需不需要继续重建
            pageLoaded = true
            // 视频播放模式：注入书源播放器文案中文化脚本
            if (videoMode) {
                view?.evaluateJavascript(VideoTextLocalizeJs.SCRIPT, null)
            }
            view?.title?.let { title ->
                if (title != url && title != view.url && title.isNotBlank()) {
                    binding.titleBar.title = title
                } else {
                    binding.titleBar.title = intent.getStringExtra("title")
                }
                view.evaluateJavascript("!!window._cf_chl_opt") {
                    if (it == "true") {
                        isCloudflareChallenge = true
                    } else if (isCloudflareChallenge && viewModel.sourceVerificationEnable) {
                        viewModel.saveVerificationResult(currentWebView) {
                            finish()
                        }
                    }
                }
            }
        }

        private fun shouldOverrideUrlLoading(url: Uri): Boolean {
            return when (url.scheme) {
                "http", "https" -> false
                "legado", "yuedu" -> {
                    startActivity<OnLineImportActivity> {
                        data = url
                    }
                    return true
                }

                else -> {
                    binding.root.longSnackbar(R.string.jump_to_another_app, R.string.confirm) {
                        openUrl(url)
                    }
                    true
                }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?
        ) {
            handler?.proceed()
        }
    }

    // 视频模式：在视频区域上下滑动切换上一集/下一集。
    // WebView 的 HTML5 视频不走 FullscreenVideoPlayer 的 VerticalPager，故在此用触摸监听实现；
    // 监听不消费事件（返回 false），网页自带的播放/暂停等控制仍可用。
    private var swipeStartY = 0f
    private var swipeStartX = 0f
    private var swipeHandled = false
    private val videoSwipeThresholdPx get() = 60f * resources.displayMetrics.density
    private val videoSwipeListener = View.OnTouchListener { v, event ->
        if (!videoMode || SourceVideoPlayerSession.getBook() == null) return@OnTouchListener false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartY = event.y
                swipeStartX = event.x
                swipeHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeHandled) {
                    val dy = event.y - swipeStartY
                    if (kotlin.math.abs(dy) > videoSwipeThresholdPx) {
                        swipeHandled = true
                        val delta = if (dy < 0) 1 else -1
                        // 与时光播放器同一套分区：右侧 20% 竖滑 = 换一个视频；
                        // 其余区域 = 换当前视频的上一集/下一集
                        if (v.width > 0 && swipeStartX >= v.width * 0.8f) {
                            switchVideo(delta)
                        } else {
                            switchEpisode(delta)
                        }
                    }
                }
            }
        }
        false
    }

    /**
     * 滑动切换的节流。快滑时每一次越过阈值都会拉起一个新的播放页，
     * 几下就把 WebView 池（容量 5）打满、并在竞态下重复释放，
     * 表现为页面空白、进度条卡住，且要重启 App 才恢复。这里丢掉过密的请求。
     */
    private fun allowSwitch(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSwitchAt < 700) return false
        lastSwitchAt = now
        return true
    }

    /**
     * 切换到列表里的上一个/下一个视频（不是切集）。
     * 右侧 20% 竖滑会走到这里；只有一集时普通竖滑也会走到这里。
     */
    private fun switchVideo(delta: Int, checkThrottle: Boolean = true) {
        if (checkThrottle && !allowSwitch()) return
        val state = SourceVideoPlayerSession.uiState.value
        if (!state.feedEnabled) {
            toastOnUi("当前列表只有这一个视频")
            return
        }
        val before = state.bookUrl
        SourceVideoPlayerSession.feedSwitch(delta)
        // feedSwitch 内部是异步 load，等新视频就绪再拉起它的播放页
        lifecycleScope.launch {
            val ready = withTimeoutOrNull(20_000L) {
                SourceVideoPlayerSession.uiState.first { it.bookUrl != before && it.episodes.isNotEmpty() }
            }
            if (ready == null) {
                toastOnUi("切换视频超时")
                return@launch
            }
            playEpisode(0, delta)
        }
    }

    /**
     * @param direction 翻页方向：>0 下一个（新页从下方进）、<0 上一个（新页从上方进）、0 不做动画
     */
    private fun playEpisode(index: Int, direction: Int = 0) {
        SourceVideoPlayerSession.selectEpisode(index)
        // 把当前集写回书籍，否则内置浏览器切集后退出再打开仍从固定那一集开始
        SourceVideoPlayerSession.saveProgress(index, 0L)
        SourceVideoPlayerSession.playWithSourcePlayer(index)
        finish()
        // 竖向翻页动画，方向跟着手指走：下一个从下方进、旧的向上出；上一个反之
        when {
            direction > 0 -> overridePendingTransition(
                R.anim.anim_readbook_bottom_in, R.anim.anim_readbook_top_out
            )

            direction < 0 -> overridePendingTransition(
                R.anim.anim_readbook_top_in, R.anim.anim_readbook_bottom_out
            )
        }
    }

    /** 全局 WebView CookieManager 里该地址所属 host 是否已有 cookie（用于判断是否需要先预热会话） */
    private fun hasWebViewCookie(url: String): Boolean {
        val base = NetworkUtils.getBaseUrl(url) ?: return false
        return !android.webkit.CookieManager.getInstance().getCookie(base).isNullOrBlank()
    }

    private fun switchEpisode(delta: Int) {
        if (!allowSwitch()) return
        val state = SourceVideoPlayerSession.uiState.value
        // 只有一集时没有"上下集"可切：改成切换上一个/下一个视频
        if (state.episodes.size <= 1) {
            switchVideo(delta, checkThrottle = false)
            return
        }
        if (state.episodes.isEmpty()) return
        val next = (state.currentIndex + delta).coerceIn(0, state.episodes.lastIndex)
        if (next == state.currentIndex) return
        playEpisode(next, delta)
    }

    /**
     * 内置浏览器视频模式下的悬浮操作层：右上角「加入书架」+「目录」两个图标，
     * 对齐时光播放器（SourceVideoPlayerActivity）的能力。仅 videoMode 且会话已加载书籍时显示。
     */
    @Composable
    private fun VideoModeActionOverlay(
        episodes: List<LocalVideo>,
        currentIndex: Int,
        onAddShelf: () -> Unit,
        onPickEpisode: (Int) -> Unit,
        onClearCache: () -> Unit,
        fullscreen: Boolean,
        onToggleFullScreen: () -> Unit,
    ) {
        var showCatalog by remember { mutableStateOf(false) }
        // 顶部横排；顶栏（功能栏）在的时候整体下移，避免图标压住功能按钮
        Row(
            modifier = Modifier
                .padding(top = if (fullscreen) 10.dp else 64.dp, end = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onAddShelf,
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = "加入书架",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(
                onClick = { showCatalog = true },
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Icon(
                    Icons.Filled.List,
                    contentDescription = "目录",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            // 认证过期被缓存成 403 时用；全屏下顶栏菜单点不到，必须有这个入口
            IconButton(
                onClick = onClearCache,
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "清除缓存并重开",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
            // 全屏开关：点一下彻底去掉/恢复顶栏，并记住选择
            IconButton(
                onClick = onToggleFullScreen,
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0x66000000), CircleShape)
            ) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    contentDescription = if (fullscreen) "退出全屏" else "全屏",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (showCatalog) {
            AlertDialog(
                onDismissRequest = { showCatalog = false },
                confirmButton = {},
                containerColor = Color(0xFF1E1E1E),
                title = { Text("选择剧集", color = Color.White) },
                text = {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        itemsIndexed(episodes) { index, ep ->
                            val selected = index == currentIndex
                            Text(
                                text = ep.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showCatalog = false
                                        onPickEpisode(index)
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                color = if (selected) Color(0xFF4FC3F7) else Color.White,
                            )
                        }
                    }
                }
            )
        }
    }
}