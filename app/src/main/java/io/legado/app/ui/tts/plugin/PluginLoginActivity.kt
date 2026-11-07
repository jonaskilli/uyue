package io.legado.app.ui.tts.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import org.apache.commons.text.StringEscapeUtils
import org.json.JSONObject
import java.util.UUID

/**
 * 插件变量登录页（移植自 TTS_Server_Android PluginLoginActivity，View 版实现）：
 * 打开插件 vars 声明里的 loginUrl，用户在 WebView 里完成登录，
 * 点右上角"保存"后按 binding 声明提取值并 setResult 返回：
 * - cookies          → 目标域整串 cookie
 * - cookies.xxx      → 指定名称的 cookie
 * - locals.xxx       → localStorage.getItem('xxx')
 * - sessions.xxx     → sessionStorage.getItem('xxx')
 * binding 为 user_input / 空或取值为空时返回空串，由调用方保持原值。
 *
 * 顶栏按钮（从左到右）：[UA模式切换] 刷新 保存。
 * UA 模式默认电脑模式（vars 里 ua=mobile/android 时默认手机模式，ua 为自定义串时作为电脑模式），
 * 点模式按钮在「电脑模式 / 安卓手机模式」间切换：切换即清缓存重载页面（不动 Cookie，登录态保留）。
 *
 * 账号/密码记忆：注入 JS 监听登录页输入框，用户一输入就把值经 JS 桥存到 App 私有
 * SharedPreferences（按 loginUrl 域名隔离，网页脚本读不到）；下次打开同一站点时注入 JS
 * 用原生 setter + input 事件回填，兼容 React/Vue 受控输入框及两步式登录页。
 * 系统 WebView 的 saveFormData 已废弃且对这类 SPA 无效，所以不用它。
 * 注意：凭据是明文存在本机私有目录（与已保存的 cookie 同级），长按顶栏说明文字可清除。
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

        /** 安卓手机模式 UA（固定值，不随设备系统 UA 波动，行为可预期） */
        private const val UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"

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
         *
         * 注意：只在电脑模式注入（onPageStarted/onPageFinished 按 uaMode 判断）。
         * 手机模式若也注入，页面会被压成 1200 桌面宽，切了 UA 看起来仍像电脑版。
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

        private const val AUTOFILL_PREFS = "plugin_login_autofill"
        private const val AUTOFILL_BRIDGE = "JReadAutoFill"

        /**
         * 定位登录表单：密码框 = 第一个可见的 password（或 autocomplete=current-password），
         * 账号框 = 它之前最后一个可见文本输入框（排除验证码/隐藏域/按钮等）。
         * 两步式登录页只有账号框时也能命中，密码框随后出现由 MutationObserver 补填。
         */
        private val FIND_FIELDS_JS = """
            function __jreadVisible(el) {
              var r = el.getBoundingClientRect();
              var s = window.getComputedStyle(el);
              return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
            }
            function __jreadIsPwd(el) {
              var t = (el.type || '').toLowerCase();
              var a = (el.getAttribute('autocomplete') || '').toLowerCase();
              return t === 'password' || a === 'current-password' || a === 'new-password';
            }
            function __jreadIsUser(el) {
              if (__jreadIsPwd(el)) return false;
              var t = (el.type || 'text').toLowerCase();
              if (!t || 'hidden checkbox radio button submit reset file image range color'.indexOf(t) >= 0) return false;
              var hint = ((el.getAttribute('placeholder') || '') + ' ' +
                (el.getAttribute('name') || '') + ' ' +
                (el.getAttribute('autocomplete') || '')).toLowerCase();
              if (/验证码|校验码|otp|one-time|captcha|code/.test(hint)) return false;
              return __jreadVisible(el);
            }
            function __jreadFields() {
              var list = Array.prototype.slice.call(document.querySelectorAll('input'));
              var pwd = null, user = null, i;
              for (i = 0; i < list.length; i++) {
                if (__jreadIsPwd(list[i]) && __jreadVisible(list[i])) { pwd = list[i]; break; }
              }
              var end = pwd ? list.indexOf(pwd) : list.length;
              for (i = end - 1; i >= 0; i--) {
                if (__jreadIsUser(list[i])) { user = list[i]; break; }
              }
              if (!user) {
                for (i = 0; i < list.length; i++) {
                  if (__jreadIsUser(list[i])) { user = list[i]; break; }
                }
              }
              return { user: user, pwd: pwd };
            }
        """

        /** 常驻监听输入：账号/密码变化即回传本机保存；点页面任意按钮时同步补一次，防跳转来不及 */
        private fun captureScript(host: String, token: String): String = """
            (function () {
              if (location.hostname !== ${JSONObject.quote(host)}) return;
              if (window.__jreadAutofillCapture) return;
              window.__jreadAutofillCapture = true;
              $FIND_FIELDS_JS
              var timer = null;
              function report() {
                timer = null;
                var f = __jreadFields();
                var account = f.user ? String(f.user.value || '') : '';
                var password = f.pwd ? String(f.pwd.value || '') : '';
                if (!account && !password) return;
                try {
                  $AUTOFILL_BRIDGE.onCredentials(${JSONObject.quote(token)}, account, password);
                } catch (e) {}
              }
              function schedule() {
                if (timer) clearTimeout(timer);
                timer = setTimeout(report, 400);
              }
              document.addEventListener('input', schedule, true);
              document.addEventListener('change', schedule, true);
              document.addEventListener('click', function () {
                if (timer) { clearTimeout(timer); timer = null; }
                report();
              }, true);
            })();
        """.trimIndent()

        /** 回填：React/Vue 受控输入框必须用原型 setter + input 事件，页面 state 才会更新 */
        private fun fillScript(host: String, account: String, password: String): String {
            if (account.isBlank() && password.isBlank()) return ""
            return """
                (function () {
                  if (location.hostname !== ${JSONObject.quote(host)}) return;
                  if (window.__jreadAutofillFill) return;
                  window.__jreadAutofillFill = true;
                  $FIND_FIELDS_JS
                  var account = ${JSONObject.quote(account)};
                  var password = ${JSONObject.quote(password)};
                  var filling = false, touchedUser = false, touchedPwd = false, tries = 0;
                  document.addEventListener('input', function (e) {
                    if (filling) return;
                    var f = __jreadFields();
                    if (e.target === f.user) touchedUser = true;
                    if (e.target === f.pwd) touchedPwd = true;
                  }, true);
                  function setValue(el, value) {
                    if (!el || !value || el.value === value) return;
                    var proto = window.HTMLInputElement && window.HTMLInputElement.prototype;
                    var desc = proto && Object.getOwnPropertyDescriptor(proto, 'value');
                    filling = true;
                    try {
                      if (desc && desc.set) desc.set.call(el, value); else el.value = value;
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                    } finally { filling = false; }
                  }
                  function attempt() {
                    var f = __jreadFields();
                    if (!touchedUser) setValue(f.user, account);
                    if (!touchedPwd) setValue(f.pwd, password);
                    return (!account || touchedUser || (f.user && f.user.value === account)) &&
                      (!password || touchedPwd || (f.pwd && f.pwd.value === password));
                  }
                  if (attempt()) return;
                  var observer = null;
                  try {
                    observer = new MutationObserver(function () {
                      if (attempt()) observer.disconnect();
                    });
                    observer.observe(document.documentElement, { childList: true, subtree: true });
                  } catch (e) {}
                  var interval = setInterval(function () {
                    if (++tries > 30 || attempt()) {
                      clearInterval(interval);
                      if (observer) observer.disconnect();
                    }
                  }, 300);
                })();
            """.trimIndent()
        }
    }

    private var binding = ""
    private var loginUrl = ""
    /** UA 模式：pc=电脑模式（默认），mobile=安卓手机模式；顶栏按钮可实时切换 */
    private var uaMode = "pc"
    /** 插件 vars 里传入的自定义 UA（既不是 pc 也不是 mobile 时），切换时作为电脑模式使用 */
    private var customUa: String? = null
    private val scope = MainScope()

    /** 记住的账号密码：按登录域名隔离，存 App 私有目录，网页 JS 无法直接读取 */
    private val autofillPrefs by lazy { getSharedPreferences(AUTOFILL_PREFS, MODE_PRIVATE) }
    private val autofillHost by lazy {
        runCatching { java.net.URI(loginUrl).host }.getOrNull().orEmpty()
    }

    /** 每次打开页面随机生成，防止网页脚本随意调用 JS 桥写入凭据 */
    private val autofillToken = UUID.randomUUID().toString()

    private fun readAutofill(): JSONObject = runCatching {
        JSONObject(autofillPrefs.getString(autofillHost, null) ?: "{}")
    }.getOrDefault(JSONObject())

    /** JavaBridge 线程回调：只覆盖非空且与旧值不同的字段，避免反复写盘 */
    private fun onCredentialsFromPage(token: String, account: String, password: String) {
        if (token != autofillToken || autofillHost.isBlank()) return
        val saved = readAutofill()
        var changed = false
        if (account.isNotEmpty() && account != saved.optString("account")) {
            saved.put("account", account)
            changed = true
        }
        if (password.isNotEmpty() && password != saved.optString("password")) {
            saved.put("password", password)
            changed = true
        }
        if (changed) autofillPrefs.edit().putString(autofillHost, saved.toString()).apply()
    }

    inner class AutoFillBridge {
        @JavascriptInterface
        fun onCredentials(token: String, account: String, password: String) =
            onCredentialsFromPage(token, account, password)
    }

    /** 当前模式对应的 UA */
    private fun currentUa(): String = when (uaMode) {
        "mobile" -> UA_MOBILE
        else -> customUa ?: UA_PC
    }

    /** 模式切换按钮文字：显示点下去会切到的模式 */
    private fun uaModeButtonText(): String = if (uaMode == "pc") "手机模式" else "电脑模式"

    private suspend fun WebView.evaluateJavascriptSuspending(script: String): String =
        suspendCancellableCoroutine { cont ->
            evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value ?: "")
            }
        }

    /**
     * 解码 WebView.evaluateJavascript 的回调值。
     *
     * evaluateJavascript 返回的是 JS 表达式结果的 JSON 编码：字符串 abc 回来是 "\"abc\""；
     * 值内引号/反斜杠/换行会以 \" \\ \n 等转义形式返回（例如 localStorage 里保存的
     * JSON 账号会话）。必须先 unescapeJson 解除内部转义，再剥掉最外层引号——
     * 只 trim('"') 会把内部 \" 原样留给插件，插件侧 JSON.parse 直接失败
     * （本悦读 v12 的 readifyAccountSession 即此问题：登录取值双重转义，我的克隆加载不出）。
     */
    private fun String.unescapeWebViewValue(): String =
        StringEscapeUtils.unescapeJson(this).trim('"')

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
                    .unescapeWebViewValue()
            }

            "sessions" -> {
                webView.evaluateJavascriptSuspending("sessionStorage.getItem('$end')")
                    .unescapeWebViewValue()
            }

            else -> ""
        }
    }

    private suspend fun finished(webView: WebView, binding: String) {
        val value = parseBinding(webView, binding)
        if (value.isBlank()) {
            android.widget.Toast.makeText(this, "未取到值，请确认已完成登录", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        withContext(Dispatchers.Main) {
            // 确保 WebView 登录 cookie 落盘：下次打开若登录态还在，连账号密码都不用再填
            CookieManager.getInstance().flush()
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
        when (ua.lowercase()) {
            "mobile", "android" -> uaMode = "mobile"
            "pc", "" -> uaMode = "pc"
            else -> {
                // 插件自带自定义 UA：作为电脑模式使用，仍可按钮切到手机模式
                uaMode = "pc"
                customUa = ua
            }
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

        // 顶栏：说明 + UA模式切换 + 刷新 + 保存
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
        // 长按说明文字清除本机记住的账号密码
        tvDesc.setOnLongClickListener {
            autofillPrefs.edit().remove(autofillHost).apply()
            android.widget.Toast.makeText(this, "已清除记住的账号密码", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        // UA 模式切换：默认电脑模式，点一下切安卓手机模式，再点切回；切换后清缓存重载
        // （只清缓存不动 Cookie，登录态保留）
        val btnUaMode = Button(this).apply {
            text = uaModeButtonText()
            textSize = 12f
            minWidth = 0
            minHeight = 0
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
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
        topBar.addView(btnUaMode)
        topBar.addView(btnReload)
        topBar.addView(btnSave)

        var webView: WebView? = null
        val webContainer = FrameLayout(this)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 注入脚本通过它把账号密码写回本机；minSdk 23，仅 @JavascriptInterface 方法对页面可见
            addJavascriptInterface(this@PluginLoginActivity.AutoFillBridge(), AUTOFILL_BRIDGE)
            // 直接写 settings.userAgentString（而非仅首帧请求头）：页面内跳转/子请求也保持该 UA
            settings.userAgentString = currentUa()
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

                /**
                 * 登录页加载加速：拦掉字体/音视频等与登录无关的资源。
                 * 登录页主体是表单（输入框+按钮），不依赖这些资源渲染；
                 * JS/CSS/XHR/图片一律放行（微信登录的二维码就是图片，不能拦）。
                 * 对悦读这种 Next.js SPA 登录页尤其明显——跨境网络下每个
                 * 请求都要多付一个跨境 RTT。
                 */
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    val url = request.url.toString()
                    val path = url.substringBefore('?').lowercase()
                    val blocked = path.endsWith(".woff") || path.endsWith(".woff2") ||
                        path.endsWith(".ttf") || path.endsWith(".otf") ||
                        path.endsWith(".eot") ||
                        path.endsWith(".mp4") || path.endsWith(".webm") ||
                        path.endsWith(".mp3") || path.endsWith(".wav")
                    if (blocked) {
                        return android.webkit.WebResourceResponse(
                            "text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return null
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    // 仅电脑模式强制桌面视口；手机模式不注入，让页面按自带 viewport 渲染移动版布局
                    if (uaMode == "pc") {
                        view.evaluateJavascript(FIX_VIEWPORT_JS, null)
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (uaMode == "pc") {
                        view.evaluateJavascript(FIX_VIEWPORT_JS, null)
                    }
                    if (autofillHost.isBlank()) return
                    // 1) 常驻监听输入框，用户一输入就把账号/密码存到本机
                    view.evaluateJavascript(captureScript(autofillHost, autofillToken), null)
                    // 2) 回填上次保存的值；输入框异步渲染或两步式登录时用 MutationObserver 重试
                    val saved = readAutofill()
                    fillScript(
                        autofillHost,
                        saved.optString("account"),
                        saved.optString("password"),
                    ).takeIf { it.isNotBlank() }?.let { view.evaluateJavascript(it, null) }
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
        btnUaMode.setOnClickListener {
            uaMode = if (uaMode == "pc") "mobile" else "pc"
            btnUaMode.text = uaModeButtonText()
            webView.settings.userAgentString = currentUa()
            // 清缓存让服务端按新 UA 重新下发页面；clearCache 不动 Cookie，登录态保留
            webView.clearCache(true)
            webView.loadUrl(loginUrl, mapOf("User-Agent" to currentUa()))
            val modeName = if (uaMode == "pc") "电脑模式" else "安卓手机模式"
            android.widget.Toast.makeText(this, "已切换为$modeName，重新加载中", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnSave.setOnClickListener {
            scope.launch { finished(webView, binding) }
        }

        webView.loadUrl(loginUrl, mapOf("User-Agent" to currentUa()))
    }
}
