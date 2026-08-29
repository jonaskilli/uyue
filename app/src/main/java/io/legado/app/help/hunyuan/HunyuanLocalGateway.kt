package io.legado.app.help.hunyuan

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import io.legado.app.help.hunyuan.proxy.CnbClient
import io.legado.app.help.hunyuan.proxy.LogStore
import io.legado.app.help.hunyuan.proxy.LongCatClient
import io.legado.app.help.hunyuan.proxy.ProxyServer
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import splitties.init.appCtx

/**
 * 混元太极「本地网关」。
 *
 * 目的：让 APP 内部发往 http://127.0.0.1:<端口> 或 http://localhost:<端口> 的请求，
 * 不需要先在混元太极界面点「启动」就能生效。
 *
 * 做法：作为 OkHttp 拦截器挂在发请求的地方，命中目标地址后不再往外发，
 * 直接调用 ProxyServer 的进程内入口算出结果，按 OpenAI 格式原样返回。
 *
 * 覆盖三处调用方：
 *  - 朗读（HTTP TTS）  → AnalyzeUrl → HttpHelper.okHttpClient
 *  - AI 生图           → AiImageGenerator → HttpHelper.okHttpClient
 *  - 背景音乐 AI       → BgmAIService 自带的 OkHttpClient（需单独注册）
 *
 * 端口实时读取，混元太极界面改端口后立即生效，无需重启 APP。
 */
object HunyuanLocalGateway : Interceptor {

    private const val PREFS = "ds_proxy"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 混元太极设置的端口（每次请求实时读取）。 */
    fun proxyPort(context: Context = appCtx): Int =
        prefs(context).getInt("proxy_port", 8801)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 供**非 OkHttp 通道**调用（jsoup / java.net.HttpURLConnection 等）。
     *
     * 这类通道不继承全局拦截器，且 `URL.setURLStreamHandlerFactory` 存在 handler 缓存陷阱
     * （App 启动早期若有代码先创建过 http URL，后续 URL 会永久走系统 HttpURLConnection，
     * 绕开 OkHttp），因此不能只靠拦截器，需在此直接接管。
     *
     * 命中混元太极本地端口时，进程内直接处理并返回响应体；
     * 未命中或不支持则返回 null，调用方照常走原逻辑。
     */
    fun takeOver(
        urlStr: String,
        method: String = "POST",
        body: String? = null,
        headers: Map<String, Any>? = null
    ): String? {
        val parsed = urlStr.toHttpUrlOrNull() ?: return null
        if (requestUrl(parsed)) {
            LogStore.i(
                "Gateway",
                "收到本地请求 ${method} ${parsed.host}:${parsed.port}${parsed.encodedPath}" +
                        " | 网关端口=${proxyPort()} | 命中=${shouldHandle(parsed)}"
            )
        }
        if (!shouldHandle(parsed)) return null
        // 端口已启动 → 交给真实端口，保持原有行为
        if (ProxyServer.isRunning()) return null
        // 进程内直连靠 runBlocking 同步等待，主线程上会卡死界面，宁可放行也不 ANR
        if (Looper.myLooper() == Looper.getMainLooper()) {
            LogStore.w("Gateway", "takeOver 处于主线程，放行走真实端口")
            return null
        }

        ensureConfig()

        val path = parsed.encodedPath
        val payload = body ?: ""
        val result = when {
            path.endsWith("/chat/completions") ->
                runBlocking { ProxyServer.chatInProcess(payload) }
            path.endsWith("/images/generations") ->
                runBlocking { ProxyServer.imageInProcess(payload) }
            path.endsWith("/models") ->
                ProxyServer.modelsInProcess()
            else -> null
        }

        // 未识别路径，或进程内不支持（DeepSeek 依赖 PoW）→ 放行走真实端口
        if (result == null || result.code == 503) {
            LogStore.i("Gateway", "takeOver 未接管，放行: $path (code=${result?.code})")
            return null
        }

        LogStore.i("Gateway", "takeOver 进程内接管: $path -> code=${result.code}")
        return if (result.code == 200) result.body else null
    }

    /** 是否指向本地回环（仅用于日志，便于确认请求是否到达网关）。 */
    private fun requestUrl(url: HttpUrl): Boolean =
        url.host == "127.0.0.1" || url.host == "localhost" || url.host == "[::1]"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 诊断日志：任何指向本地回环的请求都记录，便于确认请求是否到达网关
        if (request.url.host == "127.0.0.1"
            || request.url.host == "localhost"
            || request.url.host == "[::1]"
        ) {
            LogStore.i(
                "Gateway",
                "本地请求 ${request.method} ${request.url} | 网关端口=${proxyPort()}"
            )
        }
        if (!shouldHandle(request.url)) return chain.proceed(request)

        // 端口真的已启动 → 交给真实端口，行为与以前完全一致（DeepSeek 等也照常可用）
        if (ProxyServer.isRunning()) return chain.proceed(request)

        // 进程内直连靠 runBlocking 同步等待，主线程上会卡死界面。
        // 三个调用方（朗读/生图/BGM）本就都在 Dispatchers.IO，这里只作兜底防护：
        // 万一将来有人在主线程发起，宁可放行（退化为「需先启动端口」）也不 ANR。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            LogStore.w("Gateway", "主线程发起，跳过进程内直连，放行走真实端口: ${request.url}")
            return chain.proceed(request)
        }

        ensureConfig()

        val path = request.url.encodedPath
        val body = request.body?.let { b ->
            val buf = Buffer()
            b.writeTo(buf)
            buf.readUtf8()
        } ?: ""

        val result = when {
            path.endsWith("/chat/completions") ->
                runBlocking { ProxyServer.chatInProcess(body) }
            path.endsWith("/images/generations") ->
                runBlocking { ProxyServer.imageInProcess(body) }
            path.endsWith("/models") ->
                ProxyServer.modelsInProcess()
            else -> null
        }

        // 未识别的路径，或进程内不支持（DeepSeek 依赖 PoW）→ 放行走真实端口
        if (result == null || result.code == 503) {
            LogStore.i("Gateway", "未接管，放行: $path (code=${result?.code})")
            return chain.proceed(request)
        }

        LogStore.i("Gateway", "进程内接管: $path -> code=${result.code}")
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(result.code)
            .message(if (result.code == 200) "OK" else "Error")
            .header("Content-Type", "application/json; charset=utf-8")
            .body(result.body.toResponseBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** 命中条件：本地回环地址 + 端口等于混元太极设置的端口。 */
    private fun shouldHandle(url: HttpUrl): Boolean {
        val host = url.host
        if (host != "127.0.0.1" && host != "localhost" && host != "[::1]") return false
        return url.port == proxyPort()
    }

    @Volatile
    private var configLoaded = false

    /**
     * 首次命中时，把混元太极保存的凭证与设置还原到 ProxyServer
     * ——等价于「点启动时由 ProxyService 传参」那一步，进程内直连没有这一步，故需补上。
     * 幂等，只执行一次。
     */
    @Synchronized
    fun ensureConfig(context: Context = appCtx) {
        if (configLoaded) return
        val p = prefs(context)
        CnbClient.storageDir = context.cacheDir
        ProxyServer.streamMode = p.getBoolean("stream_mode", true)
        ProxyServer.defaultImageSize = p.getString("image_size", "9:16") ?: "9:16"
        ProxyServer.deepseekMode = p.getString("deepseek_mode", "default") ?: "default"
        ProxyServer.deleteSessionAfterReply = p.getBoolean("delete_session_after_reply", true)

        ProxyServer.setDoubaoCookieList(loadList(p, "doubao_cookies", "doubao_cookie"))
        ProxyServer.setKimiCookieList(loadList(p, "kimi_cookies", "kimi_tokens"))
        ProxyServer.setQwenCookieList(loadList(p, "qwen_cookies", "qwen_cookie"))
        ProxyServer.setDeepSeekTokenList(loadList(p, "deepseek_tokens", "deepseek_token"))

        val longcat = loadList(p, "longcat_configs", "longcat_config")
        ProxyServer.setLongCatConfigList(longcat)
        val lcIdx = p.getInt("longcat_config_selected", 0)
        longcat.getOrNull(lcIdx)?.let { LongCatClient.applyConfig(it) }

        configLoaded = true
        LogStore.i("Gateway", "已还原混元太极凭证（端口未启动，走进程内直连）")
    }

    /** 凭证在混元太极界面被修改后调用，使下次请求立即使用新凭证。 */
    fun invalidateConfig() {
        configLoaded = false
    }

    /** 与 ProxyViewModel.loadList 一致：读取 JSON 列表，兼容旧的单值 key。 */
    private fun loadList(
        p: SharedPreferences,
        key: String,
        legacyKey: String
    ): List<String> {
        val legacy = p.getString(legacyKey, null)
        val raw = p.getString(key, null)
        return if (raw != null) {
            try {
                val arr = org.json.JSONArray(raw)
                val list = (0 until arr.length()).map { arr.optString(it, "") }
                if (list.isEmpty()) listOf("") else list
            } catch (_: Exception) {
                listOf(legacy ?: "")
            }
        } else if (!legacy.isNullOrEmpty()) {
            listOf(legacy)
        } else {
            listOf("")
        }
    }
}
