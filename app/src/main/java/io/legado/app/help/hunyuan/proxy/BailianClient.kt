package io.legado.app.help.hunyuan.proxy

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 阿里云百炼（模型体验中心）客户端。认证链路（逆向自网页抓包）：
 *
 * 1. 控制台 RPC（bailian-cs.console.aliyun.com/data/api.json）认证靠
 *    阿里云登录 Cookie（login_aliyunid_ticket 等）+ 三个无痕验证风控头
 *    （bx-ua / bx_et / bx-umidtoken，浏览器页面 JS 生成，随配置粘贴）。
 * 2. POST generateAccessToken → 响应【体】里拿短时令牌 accessToken（不在响应头）。
 * 3. 对话 POST https://bailian-stream-api.aliyuncs.com/sse/console4Json/{accessToken}
 *    —— 认证全在 URL 路径里，无需任何 header/cookie。SSE 每块
 *    data:{"data":[{"type":"JSON_TEXT","value":"<内嵌JSON>"}]}，
 *    正文为内嵌 data.messageList 最后一项 contentList 中 type=text 元素的
 *    【累积全文】（非增量，由调用方做前缀切分）。
 *
 * 配置 JSON：{"cookies":"...", "bx_ua":"...", "bx_et":"...", "bx_umidtoken":"...",
 *            "model_id":"qwen3.8-max", "user_agent":"可选"}
 */
object BailianClient {
    private const val TAG = "Bailian"

    @Volatile var cookies: String = ""
    @Volatile var bxUa: String = ""
    @Volatile var bxEt: String = ""
    @Volatile var bxUmidToken: String = ""
    @Volatile var modelId: String = "qwen3.8-max"
    @Volatile var userAgent: String =
        "Mozilla/5.0 (Linux; Android 15; V2352A Build/AP3A.240905.015.A2) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.6478.71 Mobile Safari/537.36"

    private const val CS_BASE = "https://bailian-cs.console.aliyun.com/data/api.json"
    private const val STREAM_BASE = "https://bailian-stream-api.aliyuncs.com/sse/console4Json/"
    private const val FE_URL = "https://bailian.console.aliyun.com/cn-beijing/model/experience/llm"

    /** 解析配置 JSON；空串复位为未配置状态。 */
    fun applyConfig(json: String) {
        if (json.isBlank()) {
            cookies = ""
            bxUa = ""
            bxEt = ""
            bxUmidToken = ""
            modelId = "qwen3.8-max"
            return
        }
        try {
            val o = JSONObject(json)
            cookies = o.optString("cookies", "")
            bxUa = o.optString("bx_ua", "")
            bxEt = o.optString("bx_et", "")
            bxUmidToken = o.optString("bx_umidtoken", "")
            modelId = o.optString("model_id", "qwen3.8-max").ifBlank { "qwen3.8-max" }
            o.optString("user_agent", "").takeIf { it.isNotBlank() }?.let { userAgent = it }
        } catch (_: Exception) {
        }
    }

    /** 配置是否完整（能发起一次对话）。 */
    fun isConfigured(): Boolean =
        cookies.isNotBlank() && bxUa.isNotBlank() && bxEt.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private fun uuid() = UUID.randomUUID().toString()

    /** 阿里云控制台 RPC 的公共 cornerstoneParam 块（照抄网页抓包）。 */
    private fun cornerstone(): JSONObject = JSONObject().apply {
        put("feTraceId", uuid())
        put("feURL", FE_URL)
        put("protocol", "V2")
        put("console", "ONE_CONSOLE")
        put("productCode", "p_efm")
        put("switchUserType", 3)
        put("domain", "bailian.console.aliyun.com")
        put("consoleSite", "BAILIAN_ALIYUN")
        put("xsp_lang", "zh-CN")
    }

    /** 控制台 RPC 请求头。缺 bx 三件套会返回 Illegal.Request。 */
    private fun rpcHeaders(): okhttp3.Headers = okhttp3.Headers.Builder().apply {
        add("User-Agent", userAgent)
        add("Content-Type", "application/x-www-form-urlencoded")
        add("Accept", "*/*")
        add("Origin", "https://bailian.console.aliyun.com")
        add("Referer", FE_URL)
        add("X-Requested-With", "XMLHttpRequest")
        if (bxUa.isNotBlank()) add("bx-ua", bxUa)
        if (bxEt.isNotBlank()) add("bx_et", bxEt)
        if (bxUmidToken.isNotBlank()) add("bx-umidtoken", bxUmidToken)
        if (cookies.isNotBlank()) add("Cookie", cookies)
    }.build()

    /** 调用控制台 zelda RPC，返回内层 data.data 的 JSONObject；失败抛异常。 */
    private fun rpc(api: String, data: JSONObject): JSONObject {
        val params = JSONObject().apply {
            put("Api", api)
            put("V", "1.0")
            put("Data", data)
        }
        val form = "params=" + URLEncoder.encode(params.toString(), "UTF-8")
        val url = CS_BASE + "?action=BroadScopeAspnGateway&product=sfm_bailian&api=" +
            URLEncoder.encode(api, "UTF-8") + "&_v="
        val req = Request.Builder().url(url)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .headers(rpcHeaders())
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("百炼 RPC 失败 HTTP ${resp.code}: ${text.take(200)}")
            }
            val o = runCatching { JSONObject(text) }.getOrElse {
                throw RuntimeException("百炼 RPC 响应非 JSON: ${text.take(200)}")
            }
            if (o.optString("code") != "200") {
                throw RuntimeException("百炼 RPC 失败: ${text.take(200)}")
            }
            val dataV2 = o.optJSONObject("data")?.optJSONObject("DataV2")
            val inner = dataV2?.optJSONObject("data")
            if (dataV2?.optString("success") != "true" && inner?.optBoolean("success", true) != true) {
                throw RuntimeException("百炼 RPC 失败 code=${inner?.optString("code")}: ${text.take(200)}")
            }
            return inner?.optJSONObject("data") ?: JSONObject()
        }
    }

    /** 签发短时对话令牌 accessToken（每次对话前调用，认证靠 Cookie + bx 三件套）。 */
    private fun generateAccessToken(): String {
        val data = JSONObject().apply {
            put("accessTokenRequest", JSONObject().put("source", ""))
            put("cornerstoneParam", cornerstone())
        }
        val data_ = rpc(
            "zeldaEasy.cornerstoneStreamGateway.streamGatewayConsoleService.generateAccessToken",
            data
        )
        return data_.optString("accessToken", "").ifBlank {
            throw RuntimeException("百炼未返回 accessToken")
        }
    }

    /** 创建百炼对话会话，返回 sessionId。 */
    private fun createSession(label: String): String {
        val data = JSONObject().apply {
            put("session", JSONObject().apply {
                put("bizId", modelId)
                put("bizType", "AgentInference")
                put("label", label.take(50))
            })
            put("cornerstoneParam", cornerstone())
        }
        val ret = rpc("zeldaEasy.bmp.agentSessionRpcService.createApi", data)
        return ret.optString("sessionId", "").ifBlank {
            throw RuntimeException("百炼未返回 sessionId")
        }
    }

    /** 组装内嵌 predict JSON（JSON_TEXT 的 value）。 */
    private fun predictInner(content: String, sessionId: String, tabCode: String): String {
        return JSONObject().apply {
            put("Api", "zeldaEasy.bmp.agentPredictRpcService.predict")
            put("Data", JSONObject().apply {
                put("predictRequest", JSONObject().apply {
                    put("modelId", modelId)
                    put("ancestorModelId", "")
                    put("modelTypeIds", JSONArray().put("Reasoning").put("VU").put("TG"))
                    put("contentList", JSONArray().put(JSONObject().apply {
                        put("type", "text")
                        put("content", content)
                    }))
                    put("predictConfig", JSONObject().apply {
                        put("modelParam", JSONObject().apply {
                            put("enable_thinking", false)
                            put("thinking_budget", 4000)
                        })
                        put("chatType", "t2t")
                    })
                    put("reGenerate", false)
                    put("chatLogCode", uuid())
                    put("sessionId", sessionId)
                    put("isAiCenterRequest", false)
                    put("tabCode", tabCode)
                })
                put("cornerstoneParam", cornerstone())
            })
            put("V", "1.0")
        }.toString()
    }

    /** 调用聊天补全，返回原始 SSE 响应（调用方负责读取并关闭）。正文为累积全文流。 */
    fun chatCompletion(messages: List<ChatMessage>, model: String): Response {
        if (!isConfigured()) {
            throw RuntimeException(
                "百炼未配置：需要浏览器抓包的 cookies 与 bx_ua / bx_et / bx_umidtoken（无痕验证三件套）"
            )
        }
        val content = messages.joinToString("\n\n") { m ->
            val c = m.content
            when (m.role.lowercase()) {
                "system" -> "system: $c"
                "assistant" -> "assistant: $c"
                else -> "user: $c"
            }
        }
        // 1. 创建会话
        val sessionId = createSession(content)
        // 2. 签发短时对话令牌（放在 URL 路径，stream 接口无 header 认证）
        val accessToken = generateAccessToken()
        // 3. 流式 predict
        val payload = JSONObject().apply {
            put("messageId", uuid())
            put("data", JSONArray().put(JSONObject().apply {
                put("type", "JSON_TEXT")
                put("value", predictInner(content, sessionId, uuid().replace("-", "")))
            }))
        }
        val req = Request.Builder().url(STREAM_BASE + accessToken)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .headers(okhttp3.Headers.Builder().apply {
                add("User-Agent", userAgent)
                add("Content-Type", "application/json")
                add("Accept", "text/event-stream")
                add("Origin", "https://bailian.console.aliyun.com")
                add("Referer", FE_URL)
            }.build())
            .build()
        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            val err = resp.body?.string() ?: ""
            resp.close()
            throw RuntimeException("百炼对话失败 HTTP ${resp.code}: ${err.take(300)}")
        }
        // stream 接口鉴权失败等业务错误也是 200 + JSON（非 SSE）
        peekBusinessError(resp)?.let { err ->
            resp.close()
            throw RuntimeException(err)
        }
        return resp
    }

    /** stream 接口的错误响应（200 + JSON，非 SSE）。 */
    private fun peekBusinessError(resp: Response): String? {
        val head = runCatching { resp.peekBody(512).string().trimStart() }.getOrNull() ?: return null
        if (!head.startsWith("{")) return null
        val o = runCatching { JSONObject(head) }.getOrNull() ?: return null
        if (o.optInt("code", 0) == 0 && o.optString("codeEnum", "SUCCESS") == "SUCCESS") return null
        return "百炼对话失败: ${o.optString("message", head.take(200))}"
    }
}
