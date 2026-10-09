package io.legado.app.ui.book.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebViewClient
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityWebViewLoginBinding
import io.legado.app.help.http.CookieStore
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 需要登录才能看正文的站点：在 WebView 里完成登录，把 cookie 按域名存入 [CookieStore]。
 *
 * 存储后有两处自动生效：
 * 1. [io.legado.app.model.AiBookSourceGenerator] 采集页面时按域名带 Cookie 请求；
 * 2. legado 阅读引擎（AnalyzeUrl）请求书源时同样按域名自动注入 Cookie，
 *    因此生成出的书源直接就是登录态，无需额外配置。
 *
 * 生成的 **书源 JSON 会写入 loginUrl 字段**，cookie 失效后可在「书源管理→登录」里重新登录。
 */
class WebViewLoginActivity : BaseActivity<ActivityWebViewLoginBinding>() {

    override val binding by viewBinding(ActivityWebViewLoginBinding::inflate)

    private val loginUrl by lazy { intent.getStringExtra(EXTRA_URL).orEmpty() }
    private val sourceUrl by lazy { intent.getStringExtra(EXTRA_SOURCE_URL).orEmpty() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        val host = runCatching { java.net.URI(loginUrl).host }.getOrNull().orEmpty()
        binding.titleBar.title = if (host.isBlank()) "登录" else "登录：$host"
        binding.btnSaveLogin.setOnClickListener { saveLoginState() }
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.webView.webViewClient = WebViewClient()
        if (loginUrl.isBlank()) {
            toastOnUi("登录地址为空")
            finish()
            return
        }
        binding.webView.loadUrl(loginUrl)
    }

    private fun saveLoginState() {
        val cookieManager = CookieManager.getInstance()
        val currentUrl = binding.webView.url ?: loginUrl
        val cookie = cookieManager.getCookie(currentUrl)
            ?: cookieManager.getCookie(loginUrl)
            ?: ""
        if (cookie.isBlank()) {
            toastOnUi("未获取到 Cookie，请确认已登录")
            return
        }
        // 按书源根地址（域名）存储，与书源 key、阅读引擎的域名匹配保持一致
        CookieStore.replaceCookie(sourceUrl.ifBlank { loginUrl }, cookie)
        toastOnUi("登录状态已保存，可返回生成书源")
        finish()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_SOURCE_URL = "sourceUrl"

        fun start(context: Context, loginUrl: String, sourceUrl: String) {
            context.startActivity(
                Intent(context, WebViewLoginActivity::class.java)
                    .putExtra(EXTRA_URL, loginUrl)
                    .putExtra(EXTRA_SOURCE_URL, sourceUrl)
            )
        }
    }
}
