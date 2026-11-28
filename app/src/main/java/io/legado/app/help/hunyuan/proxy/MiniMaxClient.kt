package io.legado.app.help.hunyuan.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 大陆 MiniMax 会员协议客户端（对应 APK 里的 minimax_member.py）。
 *
 * 授权使用 OAuth 2.0 Device Authorization Grant + PKCE：
 *   device/code -> 浏览器 account.minimax.cn/oauth-authorize -> 轮询 oauth2/token。
 * 推理使用 Anthropic Messages 兼容接口：
 *   POST https://agent.minimax.cn/mavis/api/v1/llm/v1/messages
 * 鉴权为 Bearer access_token，不是 API Key，也不是网页 Cookie。
 */
object MiniMaxClient {
    private const val CLIENT_ID = "mcode-public"
    private const val SCOPE = "agent.default"
    private const val AUDIENCE = "agent-backend"
    private const val AUTH_ORIGIN = "https://account.minimax.cn"
    private const val RESOURCE = "https://agent.minimax.cn/mavis/api/v1/llm/v1"
    private const val USER_AGENT = "ReadingMiniMax/1.0.3 (Android; MiniMaxCode-protocol)"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    data class Pending(
        val verifier: String,
        val deviceCode: String,
        val userCode: String,
        val url: String,
        val interval: Int,
        val expiresIn: Int
    )

    data class Grant(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Int,
        val scope: String
    )

    private fun verifier(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun challenge(v: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun postForm(path: String, form: FormBody): Response {
        val req = Request.Builder()
            .url(AUTH_ORIGIN + path)
            .post(form)
            .header("User-Agent", USER_AGENT)
            .build()
        return http.newCall(req).execute()
    }

    private fun form(vararg pairs: String): FormBody {
        val b = FormBody.Builder()
        var i = 0
        while (i < pairs.size) {
            b.add(pairs[i], pairs[i + 1])
            i += 2
        }
        return b.build()
    }

    /** 申请设备码。返回的 url 必须在内置浏览器打开，verifier 留作轮询用。 */
    suspend fun startDeviceAuth(): Pending = withContext(Dispatchers.IO) {
        val v = verifier()
        val body = form(
            "client_id", CLIENT_ID,
            "scope", SCOPE,
            "audience", AUDIENCE,
            "code_challenge", challenge(v),
            "code_challenge_method", "S256"
        )
        postForm("/oauth2/device/code", body).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("MiniMax 设备码请求失败 HTTP ${resp.code}: ${text.take(200)}")
            }
            val o = JSONObject(text)
            val deviceCode = o.optString("device_code")
            val userCode = o.optString("user_code")
            if (deviceCode.isBlank() || userCode.isBlank()) {
                throw IllegalStateException("MiniMax 未返回完整登录信息")
            }
            var url = o.optString("verification_uri_complete")
            if (url.isBlank()) {
                val base = o.optString("verification_uri").ifBlank { "$AUTH_ORIGIN/oauth-authorize" }
                url = "$base?user_code=$userCode"
            }
            val interval = o.optInt("interval", 5).coerceAtLeast(1)
            val expires = o.optInt("expires_in", 300).coerceAtLeast(60)
            Pending(v, deviceCode, userCode, url, interval, expires)
        }
    }

    /** 轮询一次 token。返回 null 表示用户还没确认。 */
    suspend fun pollToken(p: Pending): Grant? = withContext(Dispatchers.IO) {
        val body = form(
            "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
            "client_id", CLIENT_ID,
            "code_verifier", p.verifier,
            "device_code", p.deviceCode
        )
        postForm("/oauth2/token", body).use { resp ->
            val text = resp.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            val status = o?.optString("status").orEmpty().ifBlank { o?.optString("error").orEmpty() }
            when (status) {
                "authorization_pending", "slow_down", "pending" -> return@withContext null
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("MiniMax 授权失败: ${status.ifBlank { "HTTP ${resp.code}" }}")
            }
            if (o == null) throw IllegalStateException("MiniMax 未返回有效授权数据")
            val token = o.optString("access_token")
            if (token.isBlank()) throw IllegalStateException("MiniMax 未返回 access_token")
            Grant(
                accessToken = token,
                refreshToken = o.optString("refresh_token"),
                expiresIn = o.optInt("expires_in", 0),
                scope = o.optString("scope")
            )
        }
    }

    /** 用 refresh_token 续期。 */
    suspend fun refresh(refreshToken: String): Grant = withContext(Dispatchers.IO) {
        val body = form(
            "grant_type", "refresh_token",
            "client_id", CLIENT_ID,
            "scope", SCOPE,
            "audience", AUDIENCE,
            "refresh_token", refreshToken
        )
        postForm("/oauth2/token", body).use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("MiniMax 续期失败 HTTP ${resp.code}: ${text.take(200)}")
            }
            val o = JSONObject(text)
            val token = o.optString("access_token")
            if (token.isBlank()) throw IllegalStateException("MiniMax 续期未返回 access_token")
            Grant(token, o.optString("refresh_token"), o.optInt("expires_in", 0), o.optString("scope"))
        }
    }

    /** 构造 Anthropic Messages 兼容请求并发起流式调用。 */
    fun chat(messages: List<ChatMessage>, model: String, token: String, maxTokens: Int?): Response {
        val root = JSONObject()
        root.put("model", model)
        root.put("stream", true)
        root.put("max_tokens", (maxTokens ?: 8192).coerceIn(1, 128000))

        // 思考模式按模型分流：
        // - M3.1 强制开启思考且不能关闭（disabled/none 均返回 400），只能用
        //   Anthropic 协议的 output_config.effort 调到最低档 low，官方默认值为 max。
        // - 其余模型显式关闭思考，避免无端产生 thinking token 占用输出预算。
        if (model.contains("M3.1", ignoreCase = true)) {
            root.put("output_config", JSONObject().put("effort", "low"))
        } else {
            root.put("thinking", JSONObject().put("type", "disabled"))
        }

        val system = StringBuilder()
        val chatArray = JSONArray()
        messages.forEach { m ->
            when (m.role.lowercase()) {
                "system", "developer" -> {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(m.content)
                }
                else -> chatArray.put(
                    JSONObject()
                        .put("role", if (m.role.equals("assistant", true)) "assistant" else "user")
                        .put("content", m.content)
                )
            }
        }
        if (system.isNotEmpty()) root.put("system", system.toString())
        root.put("messages", chatArray)

        val req = Request.Builder()
            .url("$RESOURCE/messages")
            .post(root.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $token")
            .header("anthropic-version", "2023-06-01")
            .header("X-Mavis-Agent-Id", "reading-character")
            .header("X-Mavis-Session-Id", UUID.randomUUID().toString())
            .build()
        return http.newCall(req).execute()
    }

    /** 兼容 SSE 与 JSON 两种响应，逐段回调正文。 */
    suspend fun consume(resp: Response, onDelta: suspend (String) -> Unit) {
        val ct = resp.header("Content-Type").orEmpty()
        if (ct.contains("application/json", ignoreCase = true)) {
            val text = resp.body!!.source().readUtf8()
            val o = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IllegalStateException("MiniMax 返回格式不正确: ${text.take(200)}")
            val err = o.optJSONObject("error")
            if (err != null) throw IllegalStateException("MiniMax 错误: ${err.optString("message", err.toString())}")
            val arr = o.optJSONArray("content")
            val sb = StringBuilder()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val block = arr.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
            }
            if (sb.isNotEmpty()) onDelta(sb.toString())
            return
        }
        parseSse(resp.body!!.source(), onDelta)
    }

    /** Anthropic 风格 SSE：message_start / content_block_delta / message_stop。 */
    suspend fun parseSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit) {
        while (true) {
            val line = src.readUtf8Line() ?: break
            val t = line.trimStart()
            if (!t.startsWith("data:")) continue
            val data = t.substring(5).trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val o = runCatching { JSONObject(data) }.getOrNull() ?: continue
            when (o.optString("type")) {
                "error" -> {
                    val msg = o.optString("message").ifBlank { o.toString() }
                    throw IllegalStateException("MiniMax 返回错误: $msg")
                }
                "content_block_delta" -> {
                    val text = o.optJSONObject("delta")?.optString("text").orEmpty()
                    if (text.isNotEmpty()) onDelta(text)
                }
            }
        }
    }
}
