package io.legado.app.help.hunyuan.proxy

import android.util.Base64
import io.legado.app.utils.GSON
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * StudioGlobal（www.studioglobal.ai）生图客户端：**免登录、免 Cookie、免 API Key**。
 *
 * 流程：
 *  ① POST /api/creator/anonymous-session（body `{}`）→ 响应头 set-cookie 里拿
 *     `studio_anonymous_session`（JWT，30 天有效）
 *  ② POST /api/creator/chat/send-chat（SSE），metadata 走 image 模式
 *  ③ 解析 SSE 的 `event: token`，里面是 `[{"url":"https://....png"}]`
 *  ④ 401（会话过期或额度用尽）→ 丢弃会话重新申请，重试一次
 *
 * 说明：
 *  - 该站**没有** OpenAI 兼容的 `/v1/images/generations`（实测 404），所以不能当
 *    普通 OpenAI 接口转发，生图必须走它内部的 send-chat 协议，由本客户端转换
 *  - `turnId` 必须是 v4 UUID，否则接口返回 400 `Must be UUID`
 *  - 一个匿名会话只有 20 张图额度，所以会话只在内存里临时缓存，丢了就现申请
 *  - 尺寸/质量按需求固定：肖像竖版 1024×1536 + 低质量
 */
object StudioGlobalClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长流，不能设读超时
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://www.studioglobal.ai"
    private const val API_SESSION = "$BASE/api/creator/anonymous-session"
    private const val API_SEND_CHAT = "$BASE/api/creator/chat/send-chat"
    private const val COOKIE_NAME = "studio_anonymous_session"

    /** 站点是移动端页面，带手机 UA 才不被风控 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; V2352A Build/AP3A.240905.015.A2) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 对外模型名（OpenAI 风格，写进混元太极的模型列表）→ 站内真实 modelValue */
    const val MODEL_GPT_IMAGE_2 = "gpt-image-2"
    const val MODEL_GPT_IMAGE_15 = "gpt-image-1.5"
    /** GPT-Image-2.5（站点 UI 显示名 gpt-image-2.5 / gpt-image-2.5-flare，实测 2026-10 可用） */
    const val MODEL_GPT_IMAGE_25 = "gpt-image-2.5"
    private const val INTERNAL_GPT_IMAGE_2 = "gptImage2"
    private const val INTERNAL_GPT_IMAGE_15 = "gptImage15"
    private const val INTERNAL_GPT_IMAGE_25 = "gptImage25"

    /** 固定肖像竖版 + 低质量 */
    const val SIZE_PORTRAIT = "1024x1536"
    const val QUALITY_LOW = "low"

    /** 会话在内存里临时留一份（同进程复用），不落盘：20 张额度到了本来就该换新的 */
    @Volatile
    private var cachedSession: String? = null

    private class AuthExpired : Exception("匿名会话失效")

    /**
     * 生图，返回图片 URL 列表（正常只有一张）。
     *
     * @param prompt 生图提示词
     * @param model [MODEL_GPT_IMAGE_2] / [MODEL_GPT_IMAGE_15] / [MODEL_GPT_IMAGE_25]，其它值按 GPT-Image-2 处理
     */
    fun generateImage(prompt: String, model: String = MODEL_GPT_IMAGE_2): List<String> {
        var lastError: Throwable? = null
        // 两轮：第一轮正常跑；会话失效或匿名额度用尽 → 换一份全新会话再来一次。
        // 实测：新申请的匿名会话额度是独立的，换一下就能继续生。
        for (round in 0..1) {
            try {
                val session = obtainSession()
                val url = sendChat(session, prompt, model)
                if (url.isNotBlank()) return listOf(url)
                throw IllegalStateException("未返回图片")
            } catch (e: AuthExpired) {
                lastError = e
                cachedSession = null
                LogStore.i("StudioGlobal", "会话失效或匿名额度用尽，换新会话重试")
                if (round == 1) break
            } catch (e: Exception) {
                throw e
            }
        }
        throw lastError ?: IllegalStateException("StudioGlobal 生图失败")
    }

    // ------------------------------------------------------------ 生图请求

    private fun sendChat(session: String, prompt: String, model: String): String {
        val internal = when (model.lowercase()) {
            MODEL_GPT_IMAGE_15, INTERNAL_GPT_IMAGE_15.lowercase() -> INTERNAL_GPT_IMAGE_15
            MODEL_GPT_IMAGE_25, INTERNAL_GPT_IMAGE_25.lowercase() -> INTERNAL_GPT_IMAGE_25
            else -> INTERNAL_GPT_IMAGE_2
        }
        val body = buildString {
            append("{\"turnId\":\"").append(UUID.randomUUID().toString()).append("\"")
            append(",\"aiModel\":\"").append(internal).append("\"")
            append(",\"description\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":")
            append(GSON.toJson(prompt))
            append("}],\"metadata\":{\"routeMode\":\"image\",\"appMode\":\"image\"")
            append(",\"aiModel\":\"").append(internal).append("\"")
            append(",\"imageSize\":\"").append(SIZE_PORTRAIT).append("\"")
            append(",\"imageQuality\":\"").append(QUALITY_LOW).append("\"")
            append(",\"chatUseCase\":\"general\"}}]")
            append(",\"chatContext\":\"chat\",\"inputTokens\":")
            append(prompt.length)
            append(",\"outputLanguage\":\"Default\",\"brandVoice\":\"\"}")
        }
        val request = Request.Builder()
            .url(API_SEND_CHAT)
            .post(body.toRequestBody(JSON_MEDIA))
            .header("User-Agent", UA)
            .header("Accept", "text/event-stream")
            .header("Origin", BASE)
            .header("Referer", "$BASE/zh-cn/image")
            .header("x-studio-guest-id", UUID.randomUUID().toString())
            .header("x-studio-guest-request", "1")
            .header("Cookie", "$COOKIE_NAME=$session")
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || text.contains("\"status\":401") ||
                text.contains("session expired", ignoreCase = true)
            ) {
                throw AuthExpired()
            }
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            }
            // 关键：匿名额度用尽时 HTTP 仍是 200，失败藏在 SSE 的 `event: failed` 里。
            // 不在解析阶段认出来，就会被当成「未返回图片」反复空转，永远出不来。
            return when (val r = parseSse(text)) {
                is SseResult.Ok -> r.url
                SseResult.NeedNewSession -> throw AuthExpired()
                is SseResult.Failed -> throw IllegalStateException(r.message)
            }
        }
    }

    /** SSE 解析结果 */
    private sealed class SseResult {
        data class Ok(val url: String) : SseResult()

        /** 会话过期或匿名额度用尽：换一份新会话就能继续 */
        object NeedNewSession : SseResult()

        /** 其它失败，[message] 是服务端给的原因 */
        data class Failed(val message: String) : SseResult()
    }

    /**
     * 解析 SSE，三类事件都要认：
     *  - `event: token` / `event: completed`：成品图 URL 在 payload 里，形如
     *    `[{"url":"https://....png"}]`
     *  - `event: failed`：服务端失败，原因在 message 字段。其中「额度/会话」类
     *    （实测文案 `You've exceeded your current plan's usage limit. To continue...`）
     *    归到 [SseResult.NeedNewSession] 换新会话；其余按真实原因上报，
     *    免得所有失败都显示成"未返回图片"，看不出到底哪里错了
     */
    private fun parseSse(sse: String): SseResult {
        val urlRegex = Regex("https://[^\\\"\\\\\\s]+\\.(?:png|jpg|jpeg|webp)")
        val msgRegex = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        var event = ""
        var failedMsg = ""
        sse.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") return@forEach
                    when (event) {
                        "failed" -> {
                            msgRegex.find(payload)?.let { m ->
                                failedMsg = m.groupValues.getOrNull(1).orEmpty()
                            }
                        }

                        "token", "completed" -> {
                            urlRegex.find(payload)?.let { m -> return SseResult.Ok(m.value) }
                        }
                    }
                }
            }
        }
        if (failedMsg.isNotBlank()) {
            return if (isQuotaOrSessionMsg(failedMsg)) {
                LogStore.e("StudioGlobal", "命中额度/会话限制：$failedMsg")
                SseResult.NeedNewSession
            } else {
                SseResult.Failed("StudioGlobal 生图失败：$failedMsg")
            }
        }
        return SseResult.Failed("StudioGlobal 未返回图片（未识别到失败原因）")
    }

    /**
     * 服务端失败原因是否属于「换一份新会话就能解决」的类别。
     * 实测：额度用尽时 message 是 `You've exceeded your current plan's usage limit`，
     * 换一个新申请的匿名会话即可继续出图。
     */
    private fun isQuotaOrSessionMsg(msg: String): Boolean {
        val m = msg.lowercase()
        return m.contains("usage limit") || m.contains("quota") ||
            m.contains("rate limit") || m.contains("too many") ||
            m.contains("session") || m.contains("expired") || m.contains("limit")
    }

    // ------------------------------------------------------------ 会话管理

    private fun obtainSession(): String {
        cachedSession?.takeIf { it.isNotBlank() }?.let { return it }
        val request = Request.Builder()
            .url(API_SESSION)
            .post("{}".toRequestBody(JSON_MEDIA))
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Origin", BASE)
            .header("Referer", "$BASE/zh-cn")
            .build()
        client.newCall(request).execute().use { resp ->
            val setCookie = resp.header("set-cookie").orEmpty()
            val session = Regex("${Regex.escape(COOKIE_NAME)}=([^;,\\s]+)")
                .find(setCookie)?.groupValues?.get(1).orEmpty()
            if (session.isBlank()) {
                throw IllegalStateException("未取得匿名会话（响应头无 $COOKIE_NAME）")
            }
            cachedSession = session
            LogStore.i("StudioGlobal", "已取得匿名会话")
            return session
        }
    }

    /** 会话是 JWT，payload 里带 expiresAt（毫秒）；解析不出返回 0 */
    @Suppress("unused")
    private fun expireAtOf(session: String): Long = runCatching {
        val payload = session.split(".").getOrNull(1) ?: return 0L
        var t = payload.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        val json = String(Base64.decode(t, Base64.NO_WRAP))
        Regex("\"expiresAt\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLong() ?: 0L
    }.getOrDefault(0L)
}
