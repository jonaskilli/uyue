package io.legado.app.ui.tts.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 插件变量登录页（移植自 TTS_Server_Android PluginLoginActivity，View 版实现）：
 * 打开插件 vars 声明里的 loginUrl，用户在 WebView 里完成登录，
 * 点右上角"保存"后按 binding 声明提取值并 setResult 返回：
 * - cookies          → 目标域整串 cookie
 * - cookies.xxx      → 指定名称的 cookie
 * - locals.xxx       → localStorage.getItem('xxx')
 * - sessions.xxx     → sessionStorage.getItem('xxx')
 * binding 为 user_input / 空或取值为空时返回空串，由调用方保持原值。
 */
class PluginLoginActivity : Activity() {

    companion object {
        const val ARG_LOGIN_URL = "PluginLoginActivity.login_url"
        const val ARG_BINDING = "PluginLoginActivity.binding"
        const val ARG_DESC = "PluginLoginActivity.description"
        const val ARG_UA = "PluginLoginActivity.ua"

        const val RESULT = "PluginLoginActivity.result"
        const val OK: Int = 1

        private const val UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 Edg/133.0.0.0"

        /**
         * 改写页面 viewport meta（强制桌面视口，等同浏览器"请求桌面版网站"）：
         * 1) 移除页面自带 meta —— user-scalable=no / maximum-scale=1 会禁用捏合缩放；
         *    width=device-width 会把 PC 专属页压到手机宽度渲染，内容被裁成"一角"、
         *    高度也不足，导致拖动/滚动范围极小。
         * 2) 写入 width=1200（桌面典型布局宽度）：PC 页按真实桌面布局渲染，
         *    配合 loadWithOverviewMode 初始整页 fit 显示全貌。
         * 3) 不写 initial-scale（交给 overview fit 决定初始缩放），
         *    放开 0.05~10 倍缩放范围 → 可放大、也可缩小回全貌。
         * 4) 末尾派发 resize 事件，让 Chromium 立即按新 viewport 重新布局。
         */
        private const val DESKTOP_VIEWPORT_WIDTH = 1200

        private val FIX_VIEWPORT_JS = """
            (function(){
              var metas = document.querySelectorAll('meta[name="viewport"]');
              for (var i = 0; i < metas.length; i++) {
                var m0 = metas[i];
                if (m0.parentNode) m0.parentNode.removeChild(m0);
              }
              var m = document.createElement('meta');
              m.setAttribute('name', 'viewport');
              m.setAttribute('content',
                'width=$DESKTOP_VIEWPORT_WIDTH, minimum-scale=0.05, maximum-scale=10, user-scalable=yes');
              (document.head || document.documentElement).appendChild(m);
              try { window.dispatchEvent(new Event('resize')); } catch (e) {}
            })();
        """.trimIndent()

        /** "a=1; b=2" → {a:1, b:2}（移植自原版 StringUtils.fromCookie） */
        private fun String.fromCookie(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (cookie in split(";")) {
                val parts = cookie.split("=", limit = 2)
                when (parts.size) {
                    2 -> map[parts[0].trim()] = parts[1].trim()
                    1 -> map[parts[0].trim()] = ""
                }
            }
            return map
        }
    }

    private var binding = ""
    private var loginUrl = ""
    private val scope = MainScope()

    private suspend fun WebView.evaluateJavascriptSuspending(script: String): String =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value ?: "")
            }
        }

    private suspend fun parseBinding(webView: WebView, binding: String): String = withContext(Dispatchers.Main) {
        val split = binding.trim().split('.')
        val start = split[0]
        val end: String = split.getOrElse(1) { "" }
        val all = end.isBlank()

        when (start) {
            "cookies" -> {
                val cookies = CookieManager.getInstance().getCookie(loginUrl) ?: ""
                if (all) cookies else cookies.fromCookie()[end] ?: ""
            }

            "locals" -> {
                webView.evaluateJavascriptSuspending("localStorage.getItem('$end')")
            }

            "sessions" -> {
                webView.evaluateJavascriptSuspending("sessionStorage.getItem('$end')")
            }

            else -> ""
        }.trim('"')
    }

    private suspend fun finished(webView: WebView, binding: String) {
        val value = parseBinding(webView, binding)
        if (value.isBlank()) {
            android.widget.Toast.makeText(this, "未取到值，请确认已完成登录", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        withContext(Dispatchers.Main) {
            setResult(OK, Intent().apply { putExtra(RESULT, value) })
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loginUrl = intent.getStringExtra(ARG_LOGIN_URL) ?: ""
        binding = intent.getStringExtra(ARG_BINDING) ?: ""
        val description = intent.getStringExtra(ARG_DESC) ?: ""
        val ua = intent.getStringExtra(ARG_UA) ?: ""

        val userAgent = when (ua.lowercase()) {
            "pc", "" -> UA_PC
            "mobile", "android" -> ""
            else -> ua
        }

        if (loginUrl.isBlank() || binding.isBlank()) {
            android.widget.Toast.makeText(this, "loginUrl or binding is null", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 顶栏：说明 + 刷新 + 保存
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        val tvDesc = TextView(this).apply {
            text = description.ifBlank { "完成登录后点击保存" }
            textSize = 12f
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnReload = Button(this).apply {
            text = "刷新"
            textSize = 13f
            minWidth = 0
            minHeight = 0
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        }
        val btnSave = Button(this).apply {
            text = "保存"
            textSize = 13f
            minWidth = 0
            minHeight = 0
            setPadding((16 * density).toInt(), (4 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        }
        topBar.addView(tvDesc)
        topBar.addView(btnReload)
        topBar.addView(btnSave)

        var webView: WebView? = null
        val webContainer = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (userAgent.isNotBlank()) settings.userAgentString = userAgent
            // 桌面版页面按 PC 视口渲染，宽度远超手机屏：必须允许缩放 + 宽视口，
            // 否则页面只能看到一角且无法拖动/缩放（useWideViewPort 让 viewport 按页面自身宽度走）
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // 允许网页内部滚动，父容器不拦截触摸事件
            isVerticalScrollBarEnabled = true
            isHorizontalScrollBarEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return false
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    // 加载开始即写入，让页面 CSS 解析时就按桌面视口布局
                    view.evaluateJavascript(FIX_VIEWPORT_JS, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    // 加载完成再写一次（跳转/异步渲染场景），并确保按新视口重新布局
                    view.evaluateJavascript(FIX_VIEWPORT_JS, null)
                }
            }
        }
        webContainer.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(webContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        btnReload.setOnClickListener { webView.reload() }
        btnSave.setOnClickListener {
            scope.launch { finished(webView, binding) }
        }

        if (userAgent.isNotBlank()) webView.loadUrl(loginUrl, mapOf("User-Agent" to userAgent))
        else webView.loadUrl(loginUrl)
    }
}
