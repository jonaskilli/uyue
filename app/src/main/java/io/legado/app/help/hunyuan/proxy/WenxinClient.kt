package io.legado.app.help.hunyuan.proxy

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 文心（chat.baidu.com）网页端匿名会话客户端：**免登录、免 Cookie、免 API Key**。
 *
 * 流程：
 *  ① GET https://chat.baidu.com/ → 内联 JSON 取匿名 token + lid（isUserLogin=0）
 *  ② 签名 chat_token = base64(token|md5(query)|ts|lid) + "-" + lid + "-3"
 *  ③ POST /aichat/api/conversation（SSE text/event-stream，读超时必须 0）
 *  ④ 解析 data.message.content.generator：
 *       - component=markdown-yiyan → 正文在 data.value（注意 generator.text 恒为空）
 *       - component=image-generate → 图片在 data.items[].originUrl（一次 4 张）
 *  ⑤ 命中 kunlun 降级 → 丢弃 token 缓存、退避后换新 token 重试
 *
 * 生图两个硬条件：
 *  1. prompt 必须含生图意图词（画/绘制/生成图片…），否则被判成搜索问答不出图；
 *  2. items[].loading == 1 是占位帧（无 URL），必须跳过。
 */
object WenxinClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(300, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE 长流，不能设读超时
        .build()

    private const val BASE = "https://chat.baidu.com"
    private const val API = "$BASE/aichat/api/conversation"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    /** 聊天默认模型（官方拼写是 ERINE，不是 ERNIE）。 */
    const val DEFAULT_MODEL = "ERINE-5.1"
    private const val IMAGE_MODEL = "MIAOTU"
    private const val IMAGE_SCENE = "MIAOTU_ANSWER"
    private const val IMAGE_INTENT = 13 // QUERY_INTENT.GENERATE_IMAGE

    /** 生图意图词：prompt 不含时自动补「画」前缀。 */
    private val INTENT_WORDS = listOf(
        "画", "绘制", "绘图", "作图", "生成", "图片", "海报", "插画",
        "draw", "generate", "image"
    )

    /** 匿名凭据缓存时长：过短会被限流，过长可能失效。 */
    private const val CREDENTIAL_CACHE_MS = 6 * 60 * 1000L
    private const val MAX_RETRY = 3

    private val baseDataRe =
        Regex("""name="aiTabFrameBaseData"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    private val tokenRe = Regex(""""token"\s*:\s*"([^"]+)"""")
    private val lidRe = Regex(""""lid"\s*:\s*"([^"]+)"""")

    @Volatile private var tokenCache = ""
    @Volatile private var lidCache = ""
    @Volatile private var credentialAt = 0L

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** 取匿名凭据；force=true 时忽略缓存重新获取（降级重试用）。 */
    @Synchronized
    private fun credential(force: Boolean): Pair<String, String> {
        if (!force && tokenCache.isNotBlank() && lidCache.isNotBlank()
            && System.currentTimeMillis() - credentialAt < CREDENTIAL_CACHE_MS
        ) {
            return tokenCache to lidCache
        }
        val req = Request.Builder().url("$BASE/")
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        val html = client.newCall(req).execute().use { it.body?.string() ?: "" }
        var token = ""
        var lid = ""
        runCatching {
            baseDataRe.find(html)?.groupValues?.get(1)?.let {
                val o = JSONObject(it)
                token = o.optString("token", "")
                lid = o.optString("lid", "")
            }
        }
        // 兜底：页面结构变化时按正则抓
        if (token.isBlank()) token = tokenRe.find(html)?.groupValues?.get(1) ?: ""
        if (lid.isBlank()) lid = lidRe.find(html)?.groupValues?.get(1) ?: ""
        if (token.isBlank() || lid.isBlank()) {
            throw RuntimeException("文心未取到匿名凭据(token/lid)，页面结构可能已变化")
        }
        tokenCache = token
        lidCache = lid
        credentialAt = System.currentTimeMillis()
        return token to lid
    }

    /** 生图时补齐意图词，否则服务端按搜索问答处理、不出图。 */
    private fun ensureImageIntent(prompt: String): String {
        val p = prompt.trim()
        if (p.isEmpty()) return "画一张图片"
        val lower = p.lowercase()
        return if (INTENT_WORDS.any { lower.contains(it) }) p else "画$p"
    }

    /** 发起一次对话请求（SSE），返回的 Response 由调用方关闭。 */
    private fun openStream(
        query: String,
        model: String,
        scene: String,
        intent: Int?,
        force: Boolean
    ): Response {
        val (token, lid) = credential(force)
        val chatToken = Base64.encodeToString(
            "$token|${md5(query)}|${System.currentTimeMillis()}|$lid".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        ) + "-$lid-3"

        val searchInfo = JSONObject().apply {
            put("sa", "bkb")
            put("enter_type", "yiyan_site")
            put("re_rank", "1")
            put("landingPage", "aitab")
            put("chatParams", JSONObject().apply {
                put("setype", "csaitab")
                put("chat_samples", "WISE_NEW_CSAITAB")
                put("chat_token", chatToken)
                put("scene", scene)
            })
            put("usedModel", JSONObject().apply {
                put("modelName", model)
                put("modelFunction", JSONObject().apply {
                    put("deepSearch", "0")
                    put("internetSearch", "0")
                })
                put("showModelName", model)
            })
            put("deepDecisionInfo", JSONObject().put("isDeepDecision", 0))
            if (intent != null) put("search_intent", intent)
        }
        val body = JSONObject().apply {
            put("message", JSONObject().apply {
                put("inputMethod", "chat_search")
                put("content", JSONObject().apply {
                    put("query", "")
                    put("qtype", 0)
                    put("agentInfo", JSONObject().apply {
                        put("agent_id", org.json.JSONArray().put(""))
                        put("params", "{\"agt_rk\":1,\"agt_sess_cnt\":1}")
                    })
                })
                put("searchInfo", searchInfo)
                put("source", "pc_csaitab")
                put("query", org.json.JSONArray().put(JSONObject().apply {
                    put("type", "TEXT")
                    put("data", JSONObject().put("text", JSONObject().apply {
                        put("query", query)
                        put("extData", "{}")
                        put("text_type", "")
                    }))
                }))
                put("anti_ext", JSONObject().apply {
                    put("inputT", JSONObject.NULL)
                    put("ck1", 162)
                    put("ck9", 496)
                    put("ck10", 350)
                })
            })
            put("sa", "bkb")
            put("setype", "csaitab")
            put("rank", 1)
        }.toString()

        val anti = "{\"inputT\":null,\"ck1\":162,\"ck9\":496,\"ck10\":350}"
        val xChatMessage =
            "query:${java.net.URLEncoder.encode(query, "UTF-8")}," +
                    "anti_ext:${java.net.URLEncoder.encode(anti, "UTF-8")}," +
                    "enter_type:yiyan_site,re_rank:1,modelName:$model,sa:bkb"

        val req = Request.Builder().url(API)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("accept", "text/event-stream")
            .header("content-type", "application/json")
            .header("source", "pc_csaitab")
            .header("X-Chat-Message", xChatMessage)
            .header("Origin", BASE)
            .header("Referer", "$BASE/")
            .header("User-Agent", UA)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        return client.newCall(req).execute()
    }

    private data class Result(val text: String, val images: List<String>, val degraded: Boolean)

    /** 解析 SSE：正文自适应合并（累计全文 / 增量 / 乱序均能兼容），图片跳过占位帧。 */
    private fun parse(resp: Response): Result {
        val src = resp.body!!.source()
        val text = StringBuilder()
        val images = mutableListOf<String>()
        var degraded = false
        val data = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                data.setLength(0)
                if (payload.isBlank()) continue
                runCatching {
                    val o = JSONObject(payload)
                    if (o.optString("chatHitKunlun") == "kunlun_degrade") degraded = true
                    val content = o.optJSONObject("data")
                        ?.optJSONObject("message")?.optJSONObject("content")
                    content?.optJSONObject("hints")?.optJSONArray("parts")?.let { parts ->
                        for (i in 0 until parts.length()) {
                            if (parts.optJSONObject(i)?.optString("type") == "kunlunHint") {
                                degraded = true
                            }
                        }
                    }
                    val g = content?.optJSONObject("generator") ?: return@runCatching
                    val d = g.optJSONObject("data") ?: JSONObject()
                    when (g.optString("component")) {
                        "markdown-yiyan" -> mergeText(text, d.optString("value", ""))
                        "image-generate" -> {
                            val items = d.optJSONArray("items") ?: return@runCatching
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i) ?: continue
                                // loading=1 是占位帧，没有 URL
                                if (item.optInt("loading", 0) == 1) continue
                                val url = item.optString("originUrl", "")
                                    .ifBlank { item.optString("previewUrl", "") }
                                // 剔除 48x48 头像缩略图
                                if (url.startsWith("http") && !url.contains("f48_48")
                                    && !images.contains(url)
                                ) {
                                    images.add(url)
                                }
                            }
                        }
                    }
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
        return Result(text.toString(), images, degraded)
    }

    /** 正文合并：累计全文直接替换、增量拼接、重复/乱序旧帧忽略。 */
    private fun mergeText(cur: StringBuilder, v: String) {
        if (v.isEmpty()) return
        val c = cur.toString()
        when {
            c.isEmpty() -> {
                cur.setLength(0)
                cur.append(v)
            }
            v == c || c.startsWith(v) -> Unit
            v.startsWith(c) -> {
                cur.setLength(0)
                cur.append(v)
            }
            else -> cur.append(v)
        }
    }

    /**
     * 免登录生图。返回图片 URL 列表（服务端一次返回 4 张，调用方按需取用）。
     * 命中 kunlun 降级时换新 token 退避重试，最多 [MAX_RETRY] 次。
     */
    fun generateImage(prompt: String): List<String> {
        val query = ensureImageIntent(prompt)
        var last: List<String> = emptyList()
        for (i in 0 until MAX_RETRY) {
            val force = i > 0
            val r = openStream(query, IMAGE_MODEL, IMAGE_SCENE, IMAGE_INTENT, force).use { parse(it) }
            if (r.images.isNotEmpty()) {
                LogStore.i("Wenxin", "生图成功，图片数=${r.images.size}")
                return r.images
            }
            last = r.images
            LogStore.w("Wenxin", "生图第 ${i + 1} 次未出图（降级=${r.degraded}）")
            if (!r.degraded && r.text.isNotBlank()) {
                // 未降级但没出图：多半是意图没命中，重试意义不大
                LogStore.w("Wenxin", "未降级但未返回图片，服务端返回文本: ${r.text.take(120)}")
                break
            }
            if (i < MAX_RETRY - 1) {
                runCatching { Thread.sleep(1500L * (i + 1)) }
            }
        }
        return last
    }

    /**
     * 免登录聊天。网页端只接受单段文本，多轮需调用方先拼好再传。
     * 同样带降级重试，返回聚合后的正文。
     */
    fun chat(prompt: String, model: String = DEFAULT_MODEL): String {
        var last = ""
        for (i in 0 until MAX_RETRY) {
            val force = i > 0
            val r = openStream(prompt, model, "", null, force).use { parse(it) }
            if (r.text.isNotBlank() && !r.degraded) return r.text
            if (r.text.isNotBlank()) last = r.text
            LogStore.w("Wenxin", "聊天第 ${i + 1} 次未取到正文（降级=${r.degraded}）")
            if (i < MAX_RETRY - 1) {
                runCatching { Thread.sleep(1500L * (i + 1)) }
            }
        }
        return last
    }
}
