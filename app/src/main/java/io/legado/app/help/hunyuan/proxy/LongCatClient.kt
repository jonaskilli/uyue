package io.legado.app.help.hunyuan.proxy

import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 美团 LongCat（长猫助手）接口客户端，移植自 longcat_proxy.py。
 * 认证为抓包得到的静态值：mtgsig（JSON 串）、m-traceid、Cookie(passport_token_key)，
 * 直接作为请求头发送，无需动态签名算法（与 MiniMax 不同，这里没有 HMAC/md5 计算）。
 *
 *  - 建会话：POST https://longcat.chat/api/v1/session-create
 *  - 聊天：  POST https://longcat.chat/api/v1/chat-completion-V2（SSE 流式）
 */
object LongCatClient {
    private const val TAG = "LongCat"
    /** mtgsig 签名串（JSON 字符串，抓包获得）。 */
    @Volatile var mtgsig: String = ""
    /** 请求追踪 ID（数字字符串，抓包获得）。 */
    @Volatile var mTraceId: String = ""
    /** Cookie 字符串，含 passport_token_key（抓包获得）。 */
    @Volatile var cookies: String = ""
    /** 智能体 ID，默认 "1"。 */
    @Volatile var agentId: String = "1"
    @Volatile var baseUrl: String =
        "https://longcat.chat/api/v1/chat-completion-V2?yodaReady=h5&csecplatform=4&csecversion=4.3.0"

    /** 解析配置 JSON 并应用到本对象的静态字段；空串则复位为默认（未配置）状态。 */
    fun applyConfig(json: String) {
        if (json.isBlank()) {
            mtgsig = ""
            mTraceId = ""
            cookies = ""
            agentId = "1"
            baseUrl =
                "https://longcat.chat/api/v1/chat-completion-V2?yodaReady=h5&csecplatform=4&csecversion=4.3.0"
            return
        }
        try {
            val o = org.json.JSONObject(json)
            mtgsig = o.optString("mtgsig", "")
            mTraceId = o.optString("m_traceid", "")
            cookies = o.optString("cookies", "")
            agentId = o.optString("agent_id", "1").ifBlank { "1" }
            val bu = o.optString("base_url", "")
            if (bu.isNotBlank()) {
                // 防御：抓到的请求可能是 task-check 等轮询接口，强制改写为聊天接口地址
                baseUrl = if (bu.contains("/task-check")) {
                    bu.replace("/task-check", "/chat-completion-V2")
                } else bu
            }
        } catch (_: Exception) {
        }
    }

    private val SESSION_URL =
        "https://longcat.chat/api/v1/session-create?yodaReady=h5&csecplatform=4&csecversion=4.3.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 将全部 messages 拼成单段文本（对齐 longcat_proxy.py 的 _build_content）。 */
    private fun buildContent(messages: List<ChatMessage>): String {
        return messages.joinToString("\n\n") { m ->
            val c = m.content
            when (m.role.lowercase()) {
                "system" -> "system: $c"
                "assistant" -> "assistant: $c"
                else -> "user: $c"
            }
        }
    }

    private fun makeHeaders(): Headers = Headers.Builder().apply {
        add("Host", "longcat.chat")
        add("m-traceid", mTraceId)
        add("mtgsig", mtgsig)
        add("User-Agent", "Mozilla/5.0 (Linux; Android 15; V2352A Build/AP3A.240905.015.A2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36")
        add("Content-Type", "application/json")
        add("Accept", "text/event-stream,application/json")
        add("X-Requested-With", "XMLHttpRequest")
        add("m-appkey", "fe_com.sankuai.friday.fe.longcat")
        add("x-client-language", "zh")
        add("Origin", "https://longcat.chat")
        add("Referer", "https://longcat.chat/t")
        add("Cookie", cookies)
    }.build()

    /** 创建会话，返回 conversationId；失败抛异常。 */
    private fun createSession(): String {
        val req = Request.Builder().url(SESSION_URL)
            .post(
                JSONObject().apply { put("model", ""); put("agentId", agentId) }
                    .toString().toRequestBody("application/json".toMediaType())
            )
            .headers(makeHeaders())
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("LongCat 创建会话失败 HTTP ${resp.code}: ${text.take(300)}")
            }
            val o = JSONObject(text)
            if (o.optInt("code", -1) == 0) {
                val cid = o.optJSONObject("data")?.optString("conversationId")?.takeIf { it.isNotBlank() }
                if (cid != null) return cid
            }
            throw RuntimeException("LongCat 创建会话失败: ${o.optString("message", text.take(200))}")
        }
    }

    /** 调用聊天补全，返回原始 SSE 响应（调用方负责读取并关闭）。 */
    fun chatCompletion(messages: List<ChatMessage>, model: String): Response {
        val searchEnabled = if (model == "longcat-search") 1 else 0
        // 优先登录路径：session-create 建会话 → chat-completion-V2。
        // 未登录时 session-create 返回 401（"Please log in to continue"），
        // 降级为免登录(oversea)模式：/api/v1/chat-completion-oversea-V2，
        // 不带 conversationId，服务端自动创建会话（实测匿名可正常对话）。
        val convId: String? = try {
            createSession()
        } catch (e: Exception) {
            LogStore.w(TAG, "创建会话失败, 降级为免登录(oversea)模式: ${e.message}")
            null
        }
        return if (convId != null) {
            val payload = JSONObject().apply {
                put("conversationId", convId)
                put("content", buildContent(messages))
                put("agentId", agentId)
                put("files", JSONArray())
                put("creationParam", JSONObject())
                put("reasonEnabled", 0)
                put("searchEnabled", searchEnabled)
                put("parentMessageId", 0)
                put("location", JSONArray())
            }
            executeChat(baseUrl, payload)
        } else {
            executeChat(anonymousUrl(), anonymousPayload(messages, searchEnabled))
        }
    }

    /** 免登录(oversea)接口地址：沿用配置 baseUrl 的 query（含 csec 等参数）。 */
    private fun anonymousUrl(): String {
        val query = baseUrl.substringAfter('?', "")
        return "https://longcat.chat/api/v1/chat-completion-oversea-V2" +
            (if (query.isNotEmpty()) "?$query" else "")
    }

    /**
     * 免登录请求体：无 conversationId，以 messages 数组携带"当前用户消息 + assistant 占位"，
     * 服务端响应的 SSE 块中会回传新建的 conversationId。结构照抄网页匿名抓包。
     */
    private fun anonymousPayload(messages: List<ChatMessage>, searchEnabled: Int): JSONObject {
        val content = buildContent(messages)
        return JSONObject().apply {
            put("content", content)
            put("agentId", agentId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("events", JSONArray().put(JSONObject().apply {
                        put("type", "userMsg")
                        put("content", content)
                        put("status", "FINISHED")
                    }))
                    put("chatStatus", "FINISHED")
                    put("messageId", (Math.random() * 1e8).toInt())
                    put("idType", "custom")
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("events", JSONArray())
                    put("chatStatus", "LOADING")
                    put("messageId", (Math.random() * 1e8).toInt())
                    put("idType", "custom")
                })
            })
            put("reasonEnabled", 0)
            put("searchEnabled", searchEnabled)
            put("regenerate", 0)
        }
    }

    private fun executeChat(url: String, payload: JSONObject): Response {
        val req = Request.Builder().url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .headers(makeHeaders())
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: ""
            resp.close()
            throw RuntimeException("LongCat 聊天失败 HTTP ${resp.code}: ${err.take(300)}")
        }
        // 服务端对未登录/会话失效等业务错误返回 HTTP 200 + JSON（非 SSE），
        // 不检查的话错误体会被当作 SSE 流交给调用方，表现为"没有回复/解析出错"。
        peekBusinessError(resp)?.let { err ->
            resp.close()
            throw RuntimeException(err)
        }
        return resp
    }

    /**
     * 检查 HTTP 200 响应是否实为业务错误 JSON（{"code":非0,...}）。
     * 是则返回错误描述并关闭响应；正常 SSE 流返回 null。
     */
    private fun peekBusinessError(resp: Response): String? {
        val head = runCatching { resp.peekBody(512).string().trimStart() }.getOrNull() ?: return null
        if (!head.startsWith("{")) return null
        val o = runCatching { org.json.JSONObject(head) }.getOrNull() ?: return null
        val code = o.optInt("code", 0)
        if (code == 0) return null
        val msg = o.optString("message", head.take(200))
        return when {
            msg.contains("Please log in") ->
                "LongCat 免登录不可用（服务端要求登录）: $msg。请更新抓包凭证（mtgsig/cookies）后重试"
            msg.contains("登录") ->
                "LongCat 需要登录才能继续: $msg"
            msg.contains("对话不存在") ->
                "LongCat 会话已失效: $msg"
            else ->
                "LongCat 聊天失败 code=$code: $msg"
        }
    }
}
