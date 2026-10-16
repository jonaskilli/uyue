package io.legado.app.help.hunyuan.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 腾讯混元（aistudio.tencent.com 网页版）接口客户端。
 *
 * 与 [CnbClient]（内置「混元3」）是**两个不同入口**：
 *   - CnbClient：cnb.cool 的 NPC 通道，模型 id 为 hy3-preview
 *   - 本类：腾讯官方 aistudio 网页端，可同时用混元3 与混元4
 *
 * 协议（逆向自 hunyuan_api.py 抓包）：
 *   - 新建会话：POST https://api.hunyuan.tencent.com/api/new-portal/generate/id
 *   - 对话    ：POST https://api.hunyuan.tencent.com/api/new-portal/chat/{conversationId} （SSE 流式）
 *   - 删除会话：POST https://api.hunyuan.tencent.com/api/new-portal/chat/conversation/delete
 *   - 鉴权：Cookie 里的 hunyuan_user / hunyuan_token / hunyuan_source
 *   - 模型由 chatModelId 决定，思考开关由 effort 决定
 *
 * chatModelId 实测结论（2026-10 验证，其余 id 一律返回 40100 通用错误）：
 *   - "hy3"         → 混元3   （funcName: Hy3）
 *   - "hy4-preview" → 混元4   （funcName: Hy4 preview，modelId=327）
 * effort: "no_think" 快速回答，"high" 深度思考
 */
object HunyuanStudioClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private const val API = "https://api.hunyuan.tencent.com"
    private const val ORIGIN = "https://aistudio.tencent.com"
    private const val REFERER = "https://aistudio.tencent.com/"
    // 与抓包一致的移动端 UA；网页端对 UA 无强校验，但保持与登录时一致更稳
    private const val UA = "Mozilla/5.0 (Linux; Android 15; V2352A Build/AP3A.240905.015.A2) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36"

    private const val AGENT_ID = "HunyuanDefault"
    /** 网页端固定传的 model 字段，与实际走哪个 chatModelId 无关，两个模型共用。 */
    private const val MODEL_FIELD = "gpt_175B_0404"
    /** 对话接口的内容类型（抓包实测，删除/新建会话用的是 application/json）。 */
    private const val CHAT_CONTENT_TYPE = "text/plain;charset=UTF-8"
    private const val JSON_CONTENT_TYPE = "application/json"

    /** 混元3 的 chatModelId。 */
    const val MODEL_ID_HY3 = "hy3"
    /** 混元4 的 chatModelId（实测唯一可用入口，hy4 / hy4-pro 等均不可用）。 */
    const val MODEL_ID_HY4 = "hy4-preview"

    /** effort：快速回答（关闭深度思考）。 */
    const val EFFORT_FAST = "no_think"
    /** effort：深度思考。 */
    const val EFFORT_THINK = "high"

    /**
     * 下拉模型名 → 上游 (chatModelId, effort)。
     * 未在白名单内的模型名按「混元3 + 快速」处理，与千问的策略一致。
     */
    fun resolve(model: String): Pair<String, String> {
        val lower = model.lowercase()
        val isHy4 = lower.contains("hunyuan4") || lower.contains("hy4")
        val think = lower.contains("think") || lower.contains("reason") || lower.contains("思考")
        val chatModelId = if (isHy4) MODEL_ID_HY4 else MODEL_ID_HY3
        val effort = if (think) EFFORT_THINK else EFFORT_FAST
        return chatModelId to effort
    }

    /**
     * 建请求。
     * 内容类型按抓包分两种，别统一：
     *  - 对话 `/chat/{convId}` 用 `text/plain;charset=UTF-8`（还要带 chat_version: v1）
     *  - 新建会话 / 删除会话 用 `application/json`
     * 实测两种服务端都收，但照官方请求发最稳。
     */
    private fun request(
        path: String,
        body: String,
        cookie: String,
        contentType: String = CHAT_CONTENT_TYPE,
        extra: Map<String, String>? = null
    ): Request {
        val builder = Request.Builder()
            .url(API + path)
            .post(body.toRequestBody(contentType.toMediaType()))
            .header("Content-Type", contentType)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("chat_version", "v1")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("User-Agent", UA)
            .header("Cookie", cookie)
        extra?.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    /** 新建会话，返回 conversationId。 */
    fun newConversation(cookie: String): String {
        val body = JSONObject().apply {
            put("agentId", AGENT_ID)
            put("characterId", "")
            put("cloneId", "")
            put("forceNew", true)
        }.toString()
        client.newCall(
            request(
                "/api/new-portal/generate/id", body, cookie, JSON_CONTENT_TYPE,
                mapOf("X-AgentID" to AGENT_ID)
            )
        ).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("混元 新建会话 HTTP ${resp.code}: ${text.take(200)}")
            }
            val id = runCatching { JSONObject(text) }.getOrNull()?.optString("id", "").orEmpty()
            if (id.isBlank()) {
                throw RuntimeException("混元 新建会话未返回 id: ${text.take(200)}")
            }
            return id
        }
    }

    /**
     * 发起一轮对话（SSE 流式）。返回的 Response 由调用方读取并关闭。
     * 会话路由在 URL 里，同时按抓包带 X-AgentID。
     */
    fun chatCompletion(
        convId: String,
        prompt: String,
        cookie: String,
        chatModelId: String,
        effort: String,
        maxOutputTokens: Int = 65536
    ): Response {
        val body = JSONObject().apply {
            put("model", MODEL_FIELD)
            put("prompt", prompt)
            put("plugin", "Adaptive")
            put("displayPrompt", prompt)
            put("displayPromptType", 1)
            put("options", JSONObject().put(
                "imageIntention",
                JSONObject().put("needIntentionModel", true).put("backendUpdateFlag", 2)
            ))
            put("targetLang", JSONObject.NULL)
            put("targetLangLabel", JSONObject.NULL)
            put("sourceLang", JSONObject.NULL)
            put("sourceLangLabel", JSONObject.NULL)
            put("translateModelList", org.json.JSONArray())
            put("podcast", JSONObject().put("voices", org.json.JSONArray()))
            put("effort", effort)
            put("displayImageIntentionLabels", org.json.JSONArray())
            put("multimedia", org.json.JSONArray())
            put("agentId", AGENT_ID)
            put("customParams", JSONObject()
                .put("temperature", 0.9)
                .put("top_p", 1)
                .put("repetition_penalty", 1)
                .put("max_output_tokens", maxOutputTokens))
            put("supportHint", 1)
            put("version", "v2")
            put("chatModelId", chatModelId)
        }.toString()

        val resp = client.newCall(
            request(
                path = "/api/new-portal/chat/$convId",
                body = body,
                cookie = cookie,
                contentType = CHAT_CONTENT_TYPE,
                extra = mapOf("X-AgentID" to "$AGENT_ID/$convId")
            )
        ).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string().orEmpty()
            resp.close()
            throw RuntimeException("混元 对话 HTTP ${resp.code}: ${err.take(200)}")
        }
        return resp
    }

    /**
     * 解析混元 SSE：逐行 `data: {...}`。
     *  - type=text    → 正文增量，回调 onText
     *  - type=thought → 思考链，丢弃（不混入正文）
     *  - type=error   → 抛异常，让上层把「用户对该模型没有权限」这类真实原因显示出来
     * `data: thought` / `data: status` 这类非 JSON 负载（event: speech_type）直接跳过。
     */
    suspend fun parseSse(src: okio.BufferedSource, onText: suspend (String) -> Unit) {
        while (true) {
            val line = src.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.substring(5).trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            val obj = try {
                JSONObject(payload)
            } catch (_: Exception) {
                continue
            }
            when (obj.optString("type")) {
                "text" -> {
                    val msg = obj.optString("msg", "")
                    if (msg.isNotEmpty()) onText(msg)
                }
                "error" -> {
                    val code = obj.optString("code", "")
                    val msg = obj.optString("msg", "上游错误")
                    throw RuntimeException("混元错误${if (code.isNotBlank()) " [$code]" else ""}: $msg")
                }
            }
        }
    }

    /**
     * 删除会话（静默执行，失败只记日志）。
     *
     * 注意两件事（实测）：
     *  - 必须带 `X-AgentID: HunyuanDefault/{convId}`，与抓包一致；
     *  - 只发过消息的会话才真正存在于服务端。空会话（建完还没发消息）删除会返回
     *    `{"code":1,"msg":"会话不存在"}`，属正常，不必当错误处理——所以调用方要
     *    在「对话结束之后」再删，别在建完会话就删。
     */
    fun deleteConversation(convId: String, cookie: String) {
        if (convId.isBlank()) return
        try {
            val body = JSONObject().put("conversationId", convId).toString()
            client.newCall(
                request(
                    "/api/new-portal/chat/conversation/delete", body, cookie, JSON_CONTENT_TYPE,
                    mapOf("X-AgentID" to "$AGENT_ID/$convId")
                )
            ).execute().use { resp ->
                val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                LogStore.i("Hunyuan", "删除会话 $convId: HTTP ${resp.code} $text")
            }
        } catch (e: Exception) {
            LogStore.e("Hunyuan", "删除会话异常: ${e.localizedMessage}")
        }
    }
}
