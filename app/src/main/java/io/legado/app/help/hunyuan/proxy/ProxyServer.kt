package io.legado.app.help.hunyuan.proxy

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleNonStreamUpstream
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleStreamUpstream
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleDoubaoChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleZhipuChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleWenxinChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleLongCatChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleBailianChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleKimiChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleDeepSeekChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleHunyuanChat
import io.legado.app.help.hunyuan.proxy.ProxyServer.handleQwenChat

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionRequest(
    val model: String = "hy3-preview",
    val messages: List<ChatMessage> = emptyList(),
    val stream: Boolean = false,
    val temperature: Double? = null,
    val max_tokens: Int? = null,
    val conversation_id: String? = null
)

@Serializable
data class ChoiceDelta(val role: String? = null, val content: String? = null)

@Serializable
data class ChunkChoice(
    val index: Int = 0,
    val delta: ChoiceDelta,
    val finish_reason: String? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Int,
    val model: String,
    val choices: List<ChunkChoice>
)

@Serializable
data class Msg(val role: String, val content: String)

@Serializable
data class Choice(
    val index: Int = 0,
    val message: Msg,
    val finish_reason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
data class ChatCompletion(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Int,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage,
    val conversation_id: String
)

@Serializable
data class ImageDataItem(val url: String, val b64_json: String? = null)

@Serializable
data class ImageGenResponse(val created: Int, val data: List<ImageDataItem>)

/**
 * 本地 OpenAI 兼容中转服务（端口默认 8800）。
 * 上游按模型名路由：
 *   - 豆包（Doubao）模型  → 豆包接口（聊天 / 生图）
 *   - 其他（含混元 hy3）  → CNB 混元3
 */

/**
 * 混元太极网关的 ktor 应用配置（11-13 从 ProxyServer.start 的尾 lambda 提取为顶层具名函数）。
 *
 * 为什么必须提取：R8 会把 object 方法内的无捕获 lambda 迁到合成类并混淆（如 nx.o），
 * ktor 3.0.3 启动时对该 lambda 反射调用走 CallableUtils.executeModuleFunction 的
 * declaredConstructors.single()——合成类被横向合并出多个构造器即抛
 * "Array has more than one element"，服务启动失败（11-12 的 keep 修复按类名
 * ProxyServer$* 匹配，但合成类名已变，keep 落空）。
 * 提取为顶层函数后传 ::configureApplication 函数引用：ktor 的 methodString 对
 * KFunction 引用返回真实类名+函数名（ProxyServerKt.configureApplication），
 * executeModuleFunction 在 staticFunctions 分支（顶层函数=静态方法）直接命中调用，
 * 永不进入 declaredConstructors.single()，从原理上绕开崩溃。
 */
internal fun configureApplication(application: io.ktor.server.application.Application) {
    with(application) {
            install(ContentNegotiation) { json(ProxyServer.json) }
            install(CORS) {
                anyHost()
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
                allowHeader(HttpHeaders.Authorization)
                allowHeader(HttpHeaders.ContentType)
            }
            routing {
                get("/") {
                    call.respond(
                        mapOf(
                            "service" to "Hunyuan/Doubao Proxy (OpenAI-compatible)",
                            "endpoint" to "POST /v1/chat/completions",
                            "image_endpoint" to "POST /v1/images/generations"
                        )
                    )
                }
                get("/v1/models") {
                    // 兜底列表：任何异常时仍返回合法 200 JSON，避免客户端报 500。
                    // gpt-image-2 / gpt-image-1.5 / gpt-image-2.5 不进列表：免密钥通道使用者太多会明显变慢，
                    // 隐藏后仍可在客户端手动填模型名调用（识别靠 isStudioGlobalImageModel）。
                    val fallbackJson = """{"object":"list","data":[
                        {"id":"hy3-preview","object":"model","owned_by":"cnb"},
                        {"id":"hunyuan3","object":"model","owned_by":"hunyuan"},
                        {"id":"hunyuan3-think","object":"model","owned_by":"hunyuan"},
                        {"id":"hunyuan4","object":"model","owned_by":"hunyuan"},
                        {"id":"hunyuan4-think","object":"model","owned_by":"hunyuan"},
                        {"id":"hunyuan4-flash","object":"model","owned_by":"hunyuan"},
                        {"id":"hunyuan4-flash-think","object":"model","owned_by":"hunyuan"},
                        {"id":"hunyuan-image","object":"model","owned_by":"hunyuan"},
                        {"id":"MiniMax-M3.1-Flash-Preview","object":"model","owned_by":"max"},
                        {"id":"MiniMax-M3","object":"model","owned_by":"max"},
                        {"id":"MiniMax-M2.7","object":"model","owned_by":"max"},
                        {"id":"MiniMax-M2.7-highspeed","object":"model","owned_by":"max"},
                        {"id":"longcat","object":"model","owned_by":"longcat"},
                        {"id":"longcat-search","object":"model","owned_by":"longcat"},
                        {"id":"bailian","object":"model","owned_by":"bailian"},
                        {"id":"doubao","object":"model","owned_by":"doubao"},
                        {"id":"doubao-think","object":"model","owned_by":"doubao"},
                        {"id":"doubao-image","object":"model","owned_by":"doubao"},
                        {"id":"Kimi","object":"model","owned_by":"kimi"},
                        {"id":"deepseek","object":"model","owned_by":"deepseek"},
                        {"id":"deepseek-pro","object":"model","owned_by":"deepseek"},
                        {"id":"gml","object":"model","owned_by":"zhipu"},
                        {"id":"gml-plus","object":"model","owned_by":"zhipu"},
                        {"id":"glm-image","object":"model","owned_by":"zhipu"},
                        {"id":"glm-image-hd","object":"model","owned_by":"zhipu"},
                        {"id":"qwen","object":"model","owned_by":"qwen"},
                        {"id":"qwen-think","object":"model","owned_by":"qwen"},
                        {"id":"wenxin","object":"model","owned_by":"wenxin"}
                    ]}"""
                    try {
                        val data = org.json.JSONArray()
                        val entries = listOf(
                            CnbClient.DEFAULT_MODEL to "cnb",          // hy3-preview
                            "hunyuan3" to "hunyuan",
                            "hunyuan3-think" to "hunyuan",
                            "hunyuan4" to "hunyuan",
                            "hunyuan4-think" to "hunyuan",
                            "hunyuan4-flash" to "hunyuan",
                            "hunyuan4-flash-think" to "hunyuan",
                            "hunyuan-image" to "hunyuan",
                            "longcat" to "longcat",
                            "longcat-search" to "longcat",
                            "bailian" to "bailian",
                            "doubao" to "doubao",
                            "doubao-think" to "doubao",
                            "doubao-image" to "doubao",
                            "Kimi" to "kimi",
                            "deepseek" to "deepseek",
                            "deepseek-pro" to "deepseek",
                            "gml" to "zhipu",
                            "gml-plus" to "zhipu",
                            "glm-image" to "zhipu",
                            "glm-image-hd" to "zhipu",
                            "qwen" to "qwen",
                            "qwen-think" to "qwen",
                            "wenxin" to "wenxin"
                        )
                        for ((id, owner) in entries) {
                            data.put(
                                org.json.JSONObject()
                                    .put("id", id)
                                    .put("object", "model")
                                    .put("owned_by", owner)
                            )
                        }
                        val json = org.json.JSONObject()
                            .put("object", "list")
                            .put("data", data)
                            .toString()
                        call.respondText(json, ContentType.Application.Json)
                    } catch (e: Exception) {
                        LogStore.e("Proxy", "构建 /v1/models 失败，返回兜底列表: ${e.stackTraceToString()}")
                        call.respondText(fallbackJson, ContentType.Application.Json)
                    }
                }
                post("/v1/chat/completions") { handleChatCompletions() }
                post("/v1/images/generations") { handleImageGen() }
            }
    }
}


object ProxyServer {
    internal val json = Json { ignoreUnknownKeys = true }
    private var engine: EmbeddedServer<*, *>? = null

    /** 是否向上游（CNB）使用流式请求。由 UI 开关实时控制。 */
    @Volatile internal var streamMode: Boolean = false

    /** 完成回复后是否删除会话（Kimi / DeepSeek 通用）。由 UI 开关实时控制，默认开启。 */
    @Volatile var deleteSessionAfterReply: Boolean = true

    /** 生图默认比例（豆包 ratio 串），由设置界面实时控制，默认 9:16 竖屏。 */
    @Volatile internal var defaultImageSize: String = "9:16"

    // ── 豆包 Cookie 轮换（豆包接口使用 Cookie 鉴权）──
    private val doubaoLock = Any()
    @Volatile private var doubaoCookieList: List<String> = emptyList()
    private var doubaoCookieNames: List<String> = emptyList()
    private val doubaoRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置参与轮换的豆包 Cookie 列表（自动过滤空值，并保留对应名称）。 */
    fun setDoubaoCookieList(list: List<String>) {
        synchronized(doubaoLock) {
            val pairs = list.mapIndexed { i, c ->
                val name = if (i == 0) "默认Cookie" else "Cookie ${i + 1}"
                name to c
            }.filter { it.second.isNotBlank() }
            doubaoCookieList = pairs.map { it.second }
            doubaoCookieNames = pairs.map { it.first }
            if (doubaoRotationIdx.get() >= doubaoCookieList.size) doubaoRotationIdx.set(0)
        }
    }

    /** 取下一个用于豆包请求的 Cookie（在已保存的非空 Cookie 间轮询）。 */
    fun nextDoubaoCookie(): String {
        val list = synchronized(doubaoLock) { doubaoCookieList }
        if (list.isEmpty()) {
            LogStore.i("Doubao", "轮换：暂无可用 Cookie（列表为空）")
            return ""
        }
        val idx = doubaoRotationIdx.getAndUpdate { (it + 1) % list.size }
        val c = list[idx]
        val name = synchronized(doubaoLock) { doubaoCookieNames.getOrNull(idx) ?: "Cookie ${idx + 1}" }
        val preview = if (c.length <= 16) c else c.take(16) + "...(len=${c.length})"
        LogStore.i(
            "Doubao",
            "轮换：使用 [$name]（第 $idx 个，共 ${list.size} 个），预览=${preview}"
        )
        return c
    }

    // ── Kimi Cookie 轮换（Kimi Cookie 含登录 JWT，调用时由 KimiClient 提取 Bearer）──
    private val kimiLock = Any()
    @Volatile private var kimiCookieList: List<String> = emptyList()
    private var kimiCookieNames: List<String> = emptyList()
    private val kimiRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置参与轮换的 Kimi Cookie 列表（自动过滤空值，并保留对应名称）。 */
    fun setKimiCookieList(list: List<String>) {
        synchronized(kimiLock) {
            val pairs = list.mapIndexed { i, c ->
                val name = if (i == 0) "默认Cookie" else "Cookie ${i + 1}"
                name to c
            }.filter { it.second.isNotBlank() }
            kimiCookieList = pairs.map { it.second }
            kimiCookieNames = pairs.map { it.first }
            if (kimiRotationIdx.get() >= kimiCookieList.size) kimiRotationIdx.set(0)
        }
    }

    /** 取下一个用于 Kimi 请求的 Cookie（在已保存的非空 Cookie 间轮询）。 */
    fun nextKimiCookie(): String {
        val list = synchronized(kimiLock) { kimiCookieList }
        if (list.isEmpty()) {
            LogStore.i("Kimi", "轮换：暂无可用 Cookie（列表为空），将使用兜底 JWT")
            return ""
        }
        val idx = kimiRotationIdx.getAndUpdate { (it + 1) % list.size }
        val c = list[idx]
        val name = synchronized(kimiLock) { kimiCookieNames.getOrNull(idx) ?: "Cookie ${idx + 1}" }
        val preview = if (c.length <= 16) c else c.take(16) + "...(len=${c.length})"
        LogStore.i(
            "Kimi",
            "轮换：使用 [$name]（第 $idx 个，共 ${list.size} 个），预览=${preview}"
        )
        return c
    }

    // ── 千问（Qwen）Cookie 轮换（千问使用 Cookie 鉴权，含 tongyi_sso_ticket）──
    private val qwenLock = Any()
    @Volatile private var qwenCookieList: List<String> = emptyList()
    private var qwenCookieNames: List<String> = emptyList()
    private val qwenRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置参与轮换的千问 Cookie 列表（自动过滤空值，并保留对应名称）。 */
    fun setQwenCookieList(list: List<String>) {
        synchronized(qwenLock) {
            val pairs = list.mapIndexed { i, c ->
                val name = if (i == 0) "默认Cookie" else "Cookie ${i + 1}"
                name to c
            }.filter { it.second.isNotBlank() }
            qwenCookieList = pairs.map { it.second }
            qwenCookieNames = pairs.map { it.first }
            if (qwenRotationIdx.get() >= qwenCookieList.size) qwenRotationIdx.set(0)
        }
    }

    /** 取下一个用于千问请求的 Cookie（在已保存的非空 Cookie 间轮询）。 */
    fun nextQwenCookie(): String {
        val list = synchronized(qwenLock) { qwenCookieList }
        if (list.isEmpty()) {
            LogStore.i("Qwen", "轮换：暂无可用 Cookie（列表为空）")
            return ""
        }
        val idx = qwenRotationIdx.getAndUpdate { (it + 1) % list.size }
        val c = list[idx]
        val name = synchronized(qwenLock) { qwenCookieNames.getOrNull(idx) ?: "Cookie ${idx + 1}" }
        val preview = if (c.length <= 16) c else c.take(16) + "...(len=${c.length})"
        LogStore.i(
            "Qwen",
            "轮换：使用 [$name]（第 $idx 个，共 ${list.size} 个），预览=${preview}"
        )
        return c
    }

    // ── 混元（aistudio.tencent.com 网页版）Cookie 轮换（含 hunyuan_user / hunyuan_token）──
    private val hunyuanLock = Any()
    @Volatile private var hunyuanCookieList: List<String> = emptyList()
    private var hunyuanCookieNames: List<String> = emptyList()
    private val hunyuanRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置参与轮换的混元 Cookie 列表（自动过滤空值，并保留对应名称）。 */
    fun setHunyuanCookieList(list: List<String>) {
        synchronized(hunyuanLock) {
            val pairs = list.mapIndexed { i, c ->
                val name = if (i == 0) "默认Cookie" else "Cookie ${i + 1}"
                name to c
            }.filter { it.second.isNotBlank() }
            hunyuanCookieList = pairs.map { it.second }
            hunyuanCookieNames = pairs.map { it.first }
            if (hunyuanRotationIdx.get() >= hunyuanCookieList.size) hunyuanRotationIdx.set(0)
        }
    }

    /** 取下一个用于混元请求的 Cookie（在已保存的非空 Cookie 间轮询）。 */
    fun nextHunyuanCookie(): String {
        val list = synchronized(hunyuanLock) { hunyuanCookieList }
        if (list.isEmpty()) {
            LogStore.i("Hunyuan", "轮换：暂无可用 Cookie（列表为空）")
            return ""
        }
        val idx = hunyuanRotationIdx.getAndUpdate { (it + 1) % list.size }
        val c = list[idx]
        val name = synchronized(hunyuanLock) { hunyuanCookieNames.getOrNull(idx) ?: "Cookie ${idx + 1}" }
        val preview = if (c.length <= 16) c else c.take(16) + "...(len=${c.length})"
        LogStore.i(
            "Hunyuan",
            "轮换：使用 [$name]（第 $idx 个，共 ${list.size} 个），预览=${preview}"
        )
        return c
    }

    // ── DeepSeek Token 轮换（DeepSeek 使用 Bearer token 鉴权）──
    private val deepseekLock = Any()
    @Volatile private var deepseekTokenList: List<String> = emptyList()
    private var deepseekTokenNames: List<String> = emptyList()
    /** DeepSeek 网页中转模式：default（快速模式）/ expert（专家模式）。 */
    @Volatile var deepseekMode: String = "default"
    private val deepseekRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    fun setDeepSeekTokenList(list: List<String>) {
        synchronized(deepseekLock) {
            val pairs = list.mapIndexed { i, c ->
                val name = if (i == 0) "默认Token" else "Token ${i + 1}"
                name to c
            }.filter { it.second.isNotBlank() }
            deepseekTokenList = pairs.map { it.second }
            deepseekTokenNames = pairs.map { it.first }
            if (deepseekRotationIdx.get() >= deepseekTokenList.size) deepseekRotationIdx.set(0)
        }
    }

    fun nextDeepSeekToken(): String {
        val list = synchronized(deepseekLock) { deepseekTokenList }
        if (list.isEmpty()) {
            LogStore.i("DeepSeek", "轮换：暂无可用 Token（列表为空）")
            return ""
        }
        val idx = deepseekRotationIdx.getAndUpdate { (it + 1) % list.size }
        val c = list[idx]
        val name = synchronized(deepseekLock) { deepseekTokenNames.getOrNull(idx) ?: "Token ${idx + 1}" }
        LogStore.i("DeepSeek", "轮换：使用 [$name]（第 $idx 个，共 ${list.size} 个）")
        return c
    }

    // ── MiniMax（max）凭证轮换 ──
    private data class MiniMaxCred(
        val token: String,
        val refresh: String,
        val expiresAt: Long
    )

    private val minimaxLock = Any()
    @Volatile private var minimaxCreds: List<MiniMaxCred> = emptyList()
    private val minimaxRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** token 刷新后回写给 ViewModel 持久化：(旧 access_token, 新 access_token, 新 refresh_token, expires_at)。 */
    var onMiniMaxTokenRefreshed: ((String, String, String, Long) -> Unit)? = null

    /**
     * @param tokens 与 max_tokens 对应的 access_token 列表
     * @param details 与 tokens 对齐的 JSON 字符串列表，可含 refresh_token / expires_at
     */
    fun setMiniMaxTokenList(tokens: List<String>, details: List<String> = emptyList()) {
        synchronized(minimaxLock) {
            val refreshed = minimaxCreds.associateBy { it.token }
            minimaxCreds = tokens.mapIndexed { i, token ->
                val o = runCatching { org.json.JSONObject(details.getOrNull(i) ?: "{}") }
                    .getOrElse { org.json.JSONObject() }
                val old = refreshed[token]
                MiniMaxCred(
                    token = token,
                    refresh = o.optString("refresh_token").ifBlank { old?.refresh.orEmpty() },
                    expiresAt = if (o.has("expires_at")) o.optLong("expires_at") else old?.expiresAt ?: 0L
                )
            }.filter { it.token.isNotBlank() }
            if (minimaxRotationIdx.get() >= minimaxCreds.size) minimaxRotationIdx.set(0)
        }
    }

    private suspend fun nextMiniMaxCred(): MiniMaxCred {
        var cred: MiniMaxCred
        synchronized(minimaxLock) {
            if (minimaxCreds.isEmpty()) throw IllegalStateException("MiniMax 未配置，请在 max 页面点「获取授权」")
            val idx = minimaxRotationIdx.getAndUpdate { (it + 1) % minimaxCreds.size }
            cred = minimaxCreds[idx]
        }
        if (cred.expiresAt > 0 && System.currentTimeMillis() / 1000 >= cred.expiresAt - 60 &&
            cred.refresh.isNotBlank()
        ) {
            try {
                val grant = MiniMaxClient.refresh(cred.refresh)
                val expires = System.currentTimeMillis() / 1000 + grant.expiresIn
                synchronized(minimaxLock) {
                    minimaxCreds = minimaxCreds.map {
                        if (it.token == cred.token) {
                            MiniMaxCred(grant.accessToken, grant.refreshToken.ifBlank { cred.refresh }, expires)
                        } else it
                    }
                }
                onMiniMaxTokenRefreshed?.invoke(cred.token, grant.accessToken, grant.refreshToken.ifBlank { cred.refresh }, expires)
                cred = MiniMaxCred(grant.accessToken, grant.refreshToken.ifBlank { cred.refresh }, expires)
            } catch (e: Exception) {
                LogStore.w("MiniMax", "续期失败: ${e.localizedMessage}")
            }
        }
        return cred
    }

    /** 识别为豆包模型的名称（前缀匹配，大小写不敏感）。 */
    private val DOUBAO_MODELS = listOf("doubao", "豆包")

    /** 识别为 Kimi 模型的名称（前缀匹配，大小写不敏感）。 */
    private val KIMI_MODELS = listOf("kimi")

    /** 识别为千问（Qwen）模型的名称（前缀匹配，大小写不敏感）。 */
    private val QWEN_MODELS = listOf("qwen")

    /** 识别为 DeepSeek 模型的名称（前缀匹配，大小写不敏感）。 */
    private val DEEPSEEK_MODELS = listOf("deepseek")

    /**
     * 识别为「混元」（aistudio.tencent.com 网页版）模型的名称。
     * 与内置「混元3」(cnb / hy3-preview) 是两个入口：cnb 走 CnbClient，
     * 这里走 HunyuanStudioClient。故不能匹配 hy3，否则会抢走 cnb 的请求。
     */
    private val HUNYUAN_MODELS = listOf("hunyuan", "混元")

    /**
     * 识别为智谱（GLM）文本模型的名称（免登录）。
     * 本地接口用 gml/glm/zhipu 调用；UI 上的「智谱」(zhipu) 即智谱文本入口。
     */
    private val ZHIPU_MODELS = listOf("gml", "zhipu")

    /** 识别为智谱生图模型的名称（免登录）。UI 上的 zhipu 点「生图」同样走智谱生图。 */
    private val ZHIPU_IMAGE_MODELS = listOf("智谱生图", "zhipu-image", "glm-image", "gml-image", "cogview")

    /**
     * 识别为 StudioGlobal 生图模型的名称（免登录）。
     * 站内没有 OpenAI 兼容的 /v1/images/generations（实测 404），生图必须由
     * [StudioGlobalClient] 转成它内部的 send-chat 协议，所以单列一支。
     */
    private val STUDIO_GLOBAL_IMAGE_MODELS = listOf("gpt-image")

    /**
     * 识别为混元生图模型（aistudio 网页版混元图像3.0）。
     * 与混元文本模型走同一套 Cookie 轮换（hunyuan_cookies），通道是 HunyuanStudioClient。
     */
    private val HUNYUAN_IMAGE_MODELS = listOf("hunyuan-image", "混元生图")

    /** 识别为 LongCat（美团龙猫）模型的名称（前缀匹配，大小写不敏感）。 */
    private val LONGCAT_MODELS = listOf("longcat")

    /** 识别为百炼（阿里云）模型的名称（前缀匹配，大小写不敏感）。 */
    private val BAILIAN_MODELS = listOf("bailian")

    /** 识别为 MiniMax 模型的名称（前缀匹配，大小写不敏感）。 */
    private val MINIMAX_MODELS = listOf("minimax")

    // ── 百炼配置轮换（百炼配置为整段 JSON：cookies / bx_ua / bx_et / bx_umidtoken / model_id）──
    private val bailianLock = Any()
    @Volatile private var bailianConfigList: List<String> = emptyList()
    private val bailianRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置参与轮换的百炼配置列表（自动过滤空值）。 */
    fun setBailianConfigList(list: List<String>) {
        synchronized(bailianLock) {
            bailianConfigList = list.filter { it.isNotBlank() }
            if (bailianRotationIdx.get() >= bailianConfigList.size) bailianRotationIdx.set(0)
        }
    }

    /** 取下一个百炼配置并应用到 BailianClient（在已保存的非空配置间轮询）。 */
    fun applyNextBailianConfig() {
        val list = synchronized(bailianLock) { bailianConfigList }
        if (list.isEmpty()) return
        val idx = bailianRotationIdx.getAndUpdate { (it + 1) % list.size }
        BailianClient.applyConfig(list[idx])
    }

    /** 识别为文心模型的名称（免登录，chat.baidu.com 匿名会话）。 */
    private val WENXIN_MODELS = listOf("wenxin")

    // ── LongCat 配置轮换（龙猫配置为整段 JSON：mtgsig / m-traceid / Cookie）──
    private val longcatLock = Any()
    @Volatile private var longcatConfigList: List<String> = emptyList()
    private val longcatRotationIdx = java.util.concurrent.atomic.AtomicInteger(0)

    /** 设置混元3（CNB）的登录 Cookie 列表（浏览器登录后提取，含 CNBSESSION / csrfkey）。 */
    fun setCnbCookieList(list: List<String>) {
        CnbClient.setCookieList(list)
        // Cookie 变更后异步强制刷新一次登录态 csrftoken，
        // 否则会继续沿用旧会话凭证（表现为刚提取完仍提示未登录）。
        thread(name = "cnb-auth-refresh") {
            runCatching { CnbClient.ensureAuth(force = true) }
        }
    }

    /** 设置参与轮换的 LongCat 配置列表（自动过滤空值）。 */
    fun setLongCatConfigList(list: List<String>) {
        synchronized(longcatLock) {
            longcatConfigList = list.filter { it.isNotBlank() }
            if (longcatRotationIdx.get() >= longcatConfigList.size) longcatRotationIdx.set(0)
        }
    }

    /** 取下一个 LongCat 配置并应用到 LongCatClient（在已保存的非空配置间轮询）。 */
    fun applyNextLongCatConfig() {
        val list = synchronized(longcatLock) { longcatConfigList }
        if (list.isEmpty()) return
        val idx = longcatRotationIdx.getAndUpdate { (it + 1) % list.size }
        LongCatClient.applyConfig(list[idx])
    }

    fun isRunning() = engine != null

    fun start(
        port: Int, csrfkeyProvider: () -> String,
        doubaoCookieProvider: () -> List<String>,
        kimiCookieProvider: () -> List<String>,
        qwenCookieProvider: () -> List<String>,
        storageDir: File
    ) {
        stop()
        CnbClient.csrfkeySeed = csrfkeyProvider()
        CnbClient.storageDir = storageDir
        setDoubaoCookieList(doubaoCookieProvider())
        setKimiCookieList(kimiCookieProvider())
        setQwenCookieList(qwenCookieProvider())
        // 启动服务时强制刷新一次混元凭证（APP 重新打开 / 服务（重）启动场景）
        thread(name = "cnb-auth-init") {
            runCatching { CnbClient.ensureAuth(force = true) }
        }
        // 11-13：传具名函数引用而非尾 lambda——绕开 R8 合成类反射（详见 configureApplication 注释）
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0", module = ::configureApplication)
            .start(wait = false)    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
    }

    internal fun mapModel(m: String): String {
        return when (m) {
            "hy3-preview", "glm-5.2", "deepseek-v4-pro", "deepseek-v4-flash" -> m
            else -> CnbClient.DEFAULT_MODEL
        }
    }


    /** 上游非流式：拿到完整内容后，按客户端要求返回 JSON 或单块 SSE。 */
    internal suspend fun RoutingContext.handleNonStreamUpstream(
        req: ChatCompletionRequest, model: String, body: String,
        completionId: String, created: Int
    ) {
        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    CnbClient.chatCompletion(body).use { resp ->
                        val text = resp.body!!.string()
                        val api = org.json.JSONObject(text)
                        // 不能用 getString：org.json 遇到 content 为 JSON null 会返回
                        // 字面量 "null"（JSON.toString(NULL)="null"），混进正文用户就看到
                        // 回复里全是 nullnullnull。义务用 optString：null/缺失都归空串
                        val c = api.getJSONArray("choices").getJSONObject(0)
                            .optJSONObject("message")?.optString("content").orEmpty()
                        c to null
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Proxy", "CNB 请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("Proxy", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    val sb = StringBuilder()
                    try {
                        val chunk = ChatCompletionChunk(
                            completionId, "chat.completion.chunk", created, model,
                            listOf(ChunkChoice(delta = ChoiceDelta(content = contentText)))
                        )
                        sb.append("data: ")
                            .append(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
                            .append("\n\n")
                        val stop = ChatCompletionChunk(
                            completionId, "chat.completion.chunk", created, model,
                            listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                        )
                        sb.append("data: ")
                            .append(json.encodeToString(ChatCompletionChunk.serializer(), stop))
                            .append("\n\n")
                        sb.append("data: [DONE]\n\n")
                        channel.writeFully(sb.toString().toByteArray())
                        channel.flush()
                    } catch (e: Exception) {
                        LogStore.e("Proxy", "流式写出异常: ${e.stackTraceToString()}")
                    }
                }
            })
        } else {
            LogStore.i("Proxy", "返回完成结果，长度=${contentText.length}")
            call.respond(
                ChatCompletion(
                    completionId, "chat.completion", created, model,
                    listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                    Usage(), ""
                )
            )
        }
    }

    /** 上游流式：直接透传 CNB 的 SSE；若客户端要非流式则累加后返回 JSON。 */
    internal suspend fun RoutingContext.handleStreamUpstream(
        req: ChatCompletionRequest, model: String, body: String,
        completionId: String, created: Int
    ) {
        if (!req.stream) {
            val acc = StringBuilder()
            val errMsg = withContext(Dispatchers.IO) {
                try {
                    withTimeout(300_000) {
                        CnbClient.chatCompletion(body).use { resp ->
                            val src = resp.body!!.source()
                            parseCnbSse(src) { acc.append(it) }
                            null
                        }
                    }
                } catch (e: Exception) {
                    LogStore.e("Proxy", "CNB 流式请求异常: ${e.stackTraceToString()}")
                    e.localizedMessage ?: e.toString()
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            call.respond(
                ChatCompletion(
                    completionId, "chat.completion", created, model,
                    listOf(Choice(message = Msg("assistant", acc.toString()), finish_reason = "stop")),
                    Usage(), ""
                )
            )
            return
        }

        call.respond(object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Text.EventStream
            override val headers = Headers.build {
                append("Cache-Control", "no-cache")
                append("Connection", "keep-alive")
                append("X-Accel-Buffering", "no")
            }

            override suspend fun writeTo(channel: ByteWriteChannel) {
                withContext(Dispatchers.IO) {
                    try {
                        withTimeout(300_000) {
                            CnbClient.chatCompletion(body).use { resp ->
                                val src = resp.body!!.source()
                                while (true) {
                                    val line = src.readUtf8Line() ?: break
                                    channel.writeFully((line + "\n").toByteArray())
                                    if (line.isEmpty()) channel.flush()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LogStore.e("Proxy", "流式转发异常: ${e.stackTraceToString()}")
                    }
                }
            }
        })
    }

    /** 解析 CNB 的 SSE，逐块回调 delta.content（多行 data 字段会拼接）。 */
    private fun parseCnbSse(src: okio.BufferedSource, onDelta: (String) -> Unit) {
        val data = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                data.setLength(0)
                if (payload.isNotEmpty() && payload != "[DONE]") {
                    try {
                        val o = org.json.JSONObject(payload)
                        val choices = o.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            delta?.optString("content")?.let { if (it.isNotEmpty()) onDelta(it) }
                        }
                    } catch (_: Exception) {
                    }
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
    }

    // ── 豆包（Doubao）相关 ───────────────────────────────────

    internal fun isDoubaoModel(m: String): Boolean {
        val lower = m.lowercase()
        return DOUBAO_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    internal fun isKimiModel(m: String): Boolean {
        val lower = m.lowercase()
        return KIMI_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    internal fun isQwenModel(m: String): Boolean {
        val lower = m.lowercase()
        return QWEN_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    internal fun isDeepSeekModel(m: String): Boolean {
        val lower = m.lowercase()
        return DEEPSEEK_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否「混元」（aistudio 网页版）模型：hunyuan3 / hunyuan3-think / hunyuan4 / hunyuan4-think。 */
    internal fun isHunyuanModel(m: String): Boolean {        // 生图模型（hunyuan-image）名字里同样含 hunyuan，先排除，避免被聊天通道误吞
        if (isHunyuanImageModel(m)) return false
        val lower = m.lowercase()
        return HUNYUAN_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否混元生图模型（hunyuan-image / 混元生图）。 */
    internal fun isHunyuanImageModel(m: String): Boolean {        val lower = m.lowercase()
        return HUNYUAN_IMAGE_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否 LongCat（美团龙猫）模型。 */
    internal fun isLongCatModel(m: String): Boolean {
        val lower = m.lowercase()
        return LONGCAT_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否百炼（阿里云）模型。 */
    internal fun isBailianModel(m: String): Boolean {
        val lower = m.lowercase()
        return BAILIAN_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否 MiniMax 模型（MiniMax-M3.1-Flash-Preview 等）。 */
    internal fun isMiniMaxModel(m: String): Boolean {
        val lower = m.lowercase()
        return MINIMAX_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否文心模型（免登录匿名会话，聊天与生图同一入口）。 */
    internal fun isWenxinModel(m: String): Boolean {
        val lower = m.lowercase()
        return WENXIN_MODELS.any { lower == it || lower.startsWith(it) || lower.contains(it) }
    }

    /**
     * 是否智谱文本模型（免登录）。
     *  - 本地接口：model 名以 gml/zhipu 开头或包含，或恰为 "glm"（不匹配 "glm-5.2" 等透传给 CNB 的名字）
     *  - UI 入口：恰为 "zhipu"（即界面「智谱」）
     */
    internal fun isZhipuModel(m: String): Boolean {
        val lower = m.lowercase()
        if (lower == "zhipu") return true
        if (lower == "glm") return true
        // 「智谱-电影级生图」(glm-image-hd) 选项：发文本也归智谱文本模型
        if (isZhipuCinematicImageModel(lower)) return true
        return ZHIPU_MODELS.any { lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否智谱生图模型（免登录）。UI「智谱」(zhipu) 点生图同样命中。 */
    internal fun isZhipuImageModel(m: String): Boolean {
        val lower = m.lowercase()
        if (lower == "zhipu") return true
        return ZHIPU_IMAGE_MODELS.any { lower == it || lower.startsWith(it) || lower.contains(it) }
    }

    /** 是否智谱电影级生图模型（glm-image-hd）：走 GLM Image 电影级模式。 */
    internal fun isZhipuCinematicImageModel(m: String): Boolean {
        val lower = m.lowercase()
        return lower.contains("glm-image-hd") || lower.contains("gml-image-hd")
    }

    /** 是否 StudioGlobal 生图模型（免登录）：model 名含 gpt-image（gpt-image-2 / gpt-image-1.5 / gpt-image-2.5）。 */
    internal fun isStudioGlobalImageModel(m: String): Boolean {        val lower = m.lowercase()
        return STUDIO_GLOBAL_IMAGE_MODELS.any { lower.contains(it) }
    }

    /** 是否智谱增强（plus/思考）模型：model 名含 plus/thinking/reason 即走增强模型。
     *  另：「智谱-电影级生图」(glm-image-hd) 选项发文本时也走思考模式。 */
    internal fun isZhipuPlusModel(m: String): Boolean {
        val lower = m.lowercase()
        if (isZhipuCinematicImageModel(lower)) return true
        return lower.contains("plus") || lower.contains("thinking") || lower.contains("reason")
    }

    /**
     * 将全部聊天消息拼成单段文本（对齐 doubao2api._extract_prompt）：
     * 每条消息格式为 "[role]: content"，用换行连接，保留 system 提示与多轮上下文。
     * 仅单条消息时直接使用其内容，避免多余的 "[role]:" 前缀。
     */
    private fun extractDoubaoPrompt(messages: List<ChatMessage>): String {
        if (messages.size == 1) return messages[0].content
        return messages.joinToString("\n") { "[${it.role}]: ${it.content}" }
    }

    /** OpenAI size（如 1024x1024）映射到豆包 ratio。 */
    private fun sizeToRatio(size: String): String? {
        return when (size) {
            "768x768", "1024x1024", "512x512", "256x256" -> "1:1"
            "768x1152", "1024x1792", "1024x1536" -> "9:16"
            "1152x768", "1792x1024", "1536x1024" -> "16:9"
            "1056x768", "1024x768" -> "4:3"
            "768x1024" -> "3:4"
            "768x1920" -> "2:5"
            "1920x768" -> "5:2"
            else -> null
        }
    }

    /**
     * 豆包 ratio（9:16 等）映射到混元生图的 scale（如 720x1280）。
     * 网页端生图只认常用比例，2:5 / 5:2 用最近的竖屏/横屏近似。
     */
    internal fun ratioToHunyuanScale(ratio: String): String = when (ratio) {        "1:1" -> "1024x1024"
        "3:4" -> "768x1024"
        "4:3" -> "1024x768"
        "16:9" -> "1280x720"
        "5:2" -> "1280x720"   // 近似长横屏
        else -> "720x1280"    // 9:16 / 2:5 及兜底
    }

    /** 解析生图比例：原始 ratio（如 9:16）直接返回，否则按 OpenAI size 映射，兜底 9:16。 */
    internal fun resolveRatio(size: String): String {
        if (size.isBlank()) return "9:16"
        if (size.contains(":")) return size
        return sizeToRatio(size) ?: "9:16"
    }

    /**
     * 豆包聊天：上游恒为 SSE 流式。
     *  - 客户端要求流式 → 边解析边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 累加完整文本后返回单个 JSON
     */
    /** 豆包模型名 → need_deep_think（对齐 doubao2api CHAT_MODELS：0 极速/1 思考/2 自动/3 专家）。 */
    private fun doubaoDeepThink(model: String): Int {
        return when (model.lowercase()) {
            "doubao-think" -> 1
            "doubao-auto" -> 2
            "doubao-expert", "doubao-pro" -> 3
            else -> 0
        }
    }

    internal suspend fun RoutingContext.handleDoubaoChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val cookies = DoubaoClient.parseCookie(nextDoubaoCookie())
        val text = extractDoubaoPrompt(req.messages)
        val needDeep = doubaoDeepThink(req.model)
        LogStore.i("Doubao", "调用 /chat/completion，输入长度=${text.length} needDeepThink=$needDeep")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                val resp = DoubaoClient.chatCompletion(text, cookies, needDeep)
                                try {
                                    parseDoubaoSse(resp.body!!.source()) { delta ->
                                        val chunk = ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, req.model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                        )
                                        val sb = StringBuilder()
                                        sb.append("data: ")
                                            .append(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
                                            .append("\n\n")
                                        channel.writeFully(sb.toString().toByteArray())
                                        channel.flush()
                                    }
                                } finally {
                                    resp.close()
                                }
                                channel.writeFully("data: [DONE]\n\n".toByteArray())
                                channel.flush()
                            }
                        } catch (e: Exception) {
                            LogStore.e("Doubao", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    val resp = DoubaoClient.chatCompletion(text, cookies, needDeep)
                    try {
                        val sb = StringBuilder()
                        parseDoubaoSse(resp.body!!.source()) { sb.append(it) }
                        sb.toString() to null
                    } finally {
                        resp.close()
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Doubao", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("Doubao", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        LogStore.i("Doubao", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /**
     * 智谱（GLM）聊天：游客模式（免登录）。上游恒为 SSE 流式，但智谱每次推送的是
     * 「累积完整文本」而非增量 delta，故本地计算增量后转发为 OpenAI 格式 chunk。
     */
    internal suspend fun RoutingContext.handleZhipuChat(
        req: ChatCompletionRequest, completionId: String, created: Int, plus: Boolean = false
    ) {
        // 与 DeepSeek 一致：把 system 人设 + 全部历史对话拼成一个完整 prompt 一并发送
        val text = extractDeepSeekPrompt(req.messages)
        LogStore.i("Zhipu", "调用 /assistant/stream，输入长度=${text.length} 消息数=${req.messages.size} plus=$plus")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                val resp = ZhipuClient.chatCompletion(text, plusModel = plus)
                                try {
                                    parseZhipuSse(resp.body!!.source()) { delta ->
                                        val chunk = ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, req.model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                        )
                                        val sb = StringBuilder()
                                        sb.append("data: ")
                                            .append(json.encodeToString(ChatCompletionChunk.serializer(), chunk))
                                            .append("\n\n")
                                        channel.writeFully(sb.toString().toByteArray())
                                        channel.flush()
                                    }
                                } finally {
                                    resp.close()
                                }
                                channel.writeFully("data: [DONE]\n\n".toByteArray())
                                channel.flush()
                            }
                        } catch (e: Exception) {
                            LogStore.e("Zhipu", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    val resp = ZhipuClient.chatCompletion(text, plusModel = plus)
                    try {
                        val sb = StringBuilder()
                        // 直接取上游最后一次推送的完整文本：智谱偶发推送非前缀累积的帧，
                        // 增量拼接会错位成乱码，最终完整文本才是最可靠的模型输出。
                        val finalText = parseZhipuSse(resp.body!!.source()) { sb.append(it) }
                        (finalText.ifEmpty { sb.toString() }) to null
                    } finally {
                        resp.close()
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Zhipu", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("Zhipu", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        LogStore.i("Zhipu", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /**
     * 文心（免登录匿名会话）聊天。
     *
     * 网页端接口只接受单段文本，故先按 [extractDeepSeekPrompt] 把 system 人设与历史
     * 合并成一段，再一次性取回完整正文。上游已在客户端聚合完成，客户端要求流式时
     * 按「整段一次性下发 + stop」返回，保证 OpenAI 格式兼容。
     */
    internal suspend fun RoutingContext.handleWenxinChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val text = extractDeepSeekPrompt(req.messages)
        LogStore.i("Wenxin", "调用聊天，输入长度=${text.length} 消息数=${req.messages.size}")

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(UPSTREAM_TIMEOUT_MS) { WenxinClient.chat(text) } to null
            } catch (e: Exception) {
                LogStore.e("Wenxin", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }
        val contentText = content ?: ""
        if (contentText.isBlank()) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to "文心未返回内容（可能触发限流，请稍后重试）")
            )
            return
        }

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    try {
                        val chunk = ChatCompletionChunk(
                            completionId, "chat.completion.chunk", created, req.model,
                            listOf(ChunkChoice(delta = ChoiceDelta(content = contentText)))
                        )
                        channel.writeFully(
                            ("data: " + json.encodeToString(
                                ChatCompletionChunk.serializer(), chunk
                            ) + "\n\n").toByteArray()
                        )
                        val stop = ChatCompletionChunk(
                            completionId, "chat.completion.chunk", created, req.model,
                            listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                        )
                        channel.writeFully(
                            ("data: " + json.encodeToString(
                                ChatCompletionChunk.serializer(), stop
                            ) + "\n\n").toByteArray()
                        )
                        channel.writeFully("data: [DONE]\n\n".toByteArray())
                        channel.flush()
                    } catch (e: Exception) {
                        LogStore.e("Wenxin", "流式写出异常: ${e.stackTraceToString()}")
                    }
                }
            })
            return
        }

        LogStore.i("Wenxin", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /**
     * LongCat（美团龙猫）聊天：上游恒为 SSE 流式。
     *  - 客户端要求流式 → 边解析边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 聚合完整文本后返回单个 JSON
     */
    internal suspend fun RoutingContext.handleLongCatChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        // 每次请求轮换到下一个龙猫配置（多账号），并应用到 LongCatClient
        applyNextLongCatConfig()
        if (LongCatClient.mtgsig.isBlank() || LongCatClient.cookies.isBlank()) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to "LongCat 未配置：请在「中转」页选择龙猫并填写抓包得到的配置(JSON)")
            )
            return
        }
        val model = req.model
        LogStore.i("LongCat", "调用 chat-completion-V2，消息数=${req.messages.size} model=$model")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                LongCatClient.chatCompletion(req.messages, model).use { resp ->
                                    parseLongCatSse(resp.body!!.source()) { delta ->
                                        val chunk = ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                        )
                                        channel.writeFully(
                                            ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray()
                                        )
                                        channel.flush()
                                    }
                                }
                                channel.writeFully(
                                    ("data: " + json.encodeToString(
                                        ChatCompletionChunk.serializer(),
                                        ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                                        )
                                    ) + "\n\n").toByteArray()
                                )
                                channel.writeFully("data: [DONE]\n\n".toByteArray())
                                channel.flush()
                            }
                        } catch (e: Exception) {
                            LogStore.e("LongCat", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    LongCatClient.chatCompletion(req.messages, model).use { resp ->
                        val sb = StringBuilder()
                        parseLongCatSse(resp.body!!.source()) { sb.append(it) }
                        sb.toString() to null
                    }
                }
            } catch (e: Exception) {
                LogStore.e("LongCat", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        LogStore.i("LongCat", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /** 解析 LongCat SSE：event.type=content（status!=FINISHED）累加为增量；finish 事件的 finalContentX 作为兜底完整文本。 */
    private suspend fun parseLongCatSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit) {
        val data = StringBuilder()
        var emitted = false
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                data.setLength(0)
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val o = org.json.JSONObject(payload)
                    val event = o.optJSONObject("event")
                    val etype = event?.optString("type", "") ?: ""
                    if (etype == "content") {
                        val c = event?.optString("content", "") ?: ""
                        val status = event?.optString("status", "") ?: ""
                        if (c.isNotEmpty() && status != "FINISHED") {
                            emitted = true
                            onDelta(c)
                        }
                    } else if (etype == "finish") {
                        val fcx = event?.optString("finalContentX", "") ?: ""
                        if (fcx.isNotEmpty() && !emitted) onDelta(fcx)
                    }
                } catch (_: Exception) {
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
    }

    /**
     * 百炼（阿里云）聊天：上游恒为 SSE 流式，正文为累积全文（由 parseBailianSse 切增量）。
     *  - 客户端要求流式 → 边解析边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 聚合完整文本后返回单个 JSON
     */
    internal suspend fun RoutingContext.handleBailianChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        // 每次请求轮换到下一个百炼配置（多账号），并应用到 BailianClient
        applyNextBailianConfig()
        if (!BailianClient.isConfigured()) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf(
                    "error" to
                        "百炼未配置：请在「中转」页选择百炼并粘贴抓包 JSON" +
                        "（cookies + bx_ua / bx_et / bx_umidtoken）"
                )
            )
            return
        }
        val model = req.model
        val effectiveModel = BailianClient.effectiveModelId(model)
        LogStore.i("Bailian", "调用百炼控制台对话，消息数=${req.messages.size} model=$model 实际模型=$effectiveModel")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                BailianClient.chatCompletion(req.messages, model).use { resp ->
                                    parseBailianSse(resp.body!!.source()) { delta ->
                                        val chunk = ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                        )
                                        channel.writeFully(
                                            ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray()
                                        )
                                        channel.flush()
                                    }
                                }
                                channel.writeFully(
                                    ("data: " + json.encodeToString(
                                        ChatCompletionChunk.serializer(),
                                        ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                                        )
                                    ) + "\n\n").toByteArray()
                                )
                                channel.writeFully("data: [DONE]\n\n".toByteArray())
                                channel.flush()
                            }
                        } catch (e: Exception) {
                            LogStore.e("Bailian", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    BailianClient.chatCompletion(req.messages, model).use { resp ->
                        val sb = StringBuilder()
                        parseBailianSse(resp.body!!.source()) { sb.append(it) }
                        sb.toString() to null
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Bailian", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        LogStore.i("Bailian", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    internal suspend fun RoutingContext.handleMiniMaxChat(
        req: ChatCompletionRequest,
        completionId: String,
        created: Int
    ) {
        val cred = try {
            nextMiniMaxCred()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (e.localizedMessage ?: "MiniMax 未配置，请点「获取授权」"))
            )
            return
        }
        LogStore.i("MiniMax", "调用 /messages model=${req.model} msgs=${req.messages.size}")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                MiniMaxClient.chat(req.messages, req.model, cred.token, req.max_tokens).use { resp ->
                                    val roleChunk = ChatCompletionChunk(
                                        completionId, "chat.completion.chunk", created, req.model,
                                        listOf(ChunkChoice(delta = ChoiceDelta(role = "assistant")))
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), roleChunk) + "\n\n").toByteArray()
                                    )
                                    channel.flush()
                                    MiniMaxClient.consume(resp) { delta ->
                                        if (delta.isNotEmpty()) {
                                            val chunk = ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(content = delta)))
                                            )
                                            channel.writeFully(
                                                ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray()
                                            )
                                            channel.flush()
                                        }
                                    }
                                }
                                channel.writeFully(
                                    ("data: " + json.encodeToString(
                                        ChatCompletionChunk.serializer(),
                                        ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, req.model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                                        )
                                    ) + "\n\n").toByteArray()
                                )
                                channel.writeFully("data: [DONE]\n\n".toByteArray())
                                channel.flush()
                            }
                        } catch (e: Exception) {
                            LogStore.e("MiniMax", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    MiniMaxClient.chat(req.messages, req.model, cred.token, req.max_tokens).use { resp ->
                        val sb = StringBuilder()
                        MiniMaxClient.consume(resp) { sb.append(it) }
                        sb.toString() to null
                    }
                }
            } catch (e: Exception) {
                LogStore.e("MiniMax", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }
        if (errMsg != null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", content.orEmpty()), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /**
     * 解析百炼 SSE。每块 data:{"data":[{"type":"JSON_TEXT","value":"<内嵌JSON>"}]}，
     * 内嵌 JSON 的 data.messageList 最后一项 contentList 中 type=text 的 content 为
     * **累积完整文本**（非增量），本地做前缀切分回调增量；非前缀帧整帧丢弃
     * （与 parseZhipuSse 同策略，避免位置错乱/重复）。
     */
    private suspend fun parseBailianSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit) {
        var prevFull = ""
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            try {
                val o = org.json.JSONObject(payload)
                val arr = o.optJSONArray("data") ?: return
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    if (d.optString("type") != "JSON_TEXT") continue
                    val inner = runCatching { org.json.JSONObject(d.optString("value")) }
                        .getOrNull() ?: continue
                    val dataObj = inner.optJSONObject("data") ?: continue
                    val messageList = dataObj.optJSONArray("messageList") ?: continue
                    val lastMsg = messageList.optJSONObject(messageList.length() - 1) ?: continue
                    val contentList = lastMsg.optJSONArray("contentList") ?: continue
                    // 取 contentList 中最后一个 type=text 的累积全文
                    var full: String? = null
                    for (j in 0 until contentList.length()) {
                        val c = contentList.optJSONObject(j) ?: continue
                        if (c.optString("type") == "text") full = c.optString("content", "")
                    }
                    val f = full ?: continue
                    if (f.isEmpty()) continue
                    if (f.length >= prevFull.length && f.startsWith(prevFull)) {
                        val delta = f.substring(prevFull.length)
                        if (delta.isNotEmpty()) {
                            prevFull = f
                            onDelta(delta)
                        }
                    } else if (prevFull.isEmpty()) {
                        // 首帧即非空：整体作为增量
                        prevFull = f
                        onDelta(f)
                    }
                    // 非前缀且已有基准 → 整帧丢弃
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 解析智谱 SSE。上游每个事件推送的是**累积完整文本**（不是增量），本地算增量后回调。
     *
     * ⚠ 实测智谱并非始终严格前缀累积：偶发推送与上一帧不同源的文本（重发 / 乱序 / 回退）。
     * 原实现直接 `full.substring(prevFull.length)` 按「字符数」切分，一旦两者不是前缀关系，
     * 就会从错误的字符位置截取，拼出 ` ```n `、`"ame"`、`"age": "青年 "age"` 这类位置错乱的
     * 乱码——表现为「只有开头第一条损坏，其余正常」。
     *
     * 修正：非前缀帧**整帧丢弃**。
     *  - 按字符数切   → 位置错乱，拼出乱码（原 bug）；
     *  - 按公共前缀切 → 会把已发过的内容重发一遍，导致回复重复两遍；
     * 两者都不可取。丢弃后基准 prevFull 保持不变，实测智谱后续帧会继续推送以该基准为
     * 前缀的累积文本，内容会被自动补齐，因此既不乱码也不重复。
     *
     * @return 上游最后一次推送的完整文本（模型最终输出，未经增量拼接，最可靠）
     */
    private suspend fun parseZhipuSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit): String {
        var prevFull = ""
        val data = StringBuilder()
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                data.setLength(0)
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val o = org.json.JSONObject(payload)
                    val parts = o.optJSONArray("parts") ?: continue
                    var full = prevFull
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val content = part.optJSONArray("content") ?: continue
                        for (j in 0 until content.length()) {
                            val c = content.optJSONObject(j) ?: continue
                            if (c.optString("type") == "text") {
                                val t = c.optString("text", "")
                                if (t.isNotEmpty()) full = t
                            }
                        }
                    }
                    // 只在「严格前缀累积」时发增量；非前缀帧整帧丢弃（见函数注释）
                    if (full != prevFull && full.startsWith(prevFull)) {
                        if (full.length > prevFull.length) {
                            onDelta(full.substring(prevFull.length))
                        }
                        prevFull = full
                    }
                } catch (_: Exception) {
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
        return prevFull
    }


    /** 生图接口：POST /v1/images/generations → 豆包生图 / 智谱生图 / 文心生图（按 model 区分）。 */


    /**
     * Kimi 聊天：上游恒为 Connect 二进制帧流（见 KimiClient）。
     *  - 客户端要求流式 → 边解析帧边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 聚合完整文本后返回单个 JSON
     */
    internal suspend fun RoutingContext.handleKimiChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val rawToken = KimiClient.extractToken(nextKimiCookie())
        LogStore.i("Kimi", "调用 /Chat，消息数=${req.messages.size} token空白=${rawToken.isBlank()}")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                val resp = KimiClient.chatCompletion(req.messages, rawToken)
                                try {
                                    val roleChunk = ChatCompletionChunk(
                                        completionId, "chat.completion.chunk", created, req.model,
                                        listOf(ChunkChoice(delta = ChoiceDelta(role = "assistant")))
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), roleChunk) + "\n\n").toByteArray()
                                    )
                                    channel.flush()

                                    var chatId: String? = null
                                    KimiClient.decodeFrames(
                                        resp.body!!.source(),
                                        onText = { t ->
                                            val chunk = ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(content = t)))
                                            )
                                            channel.writeFully(
                                                ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray()
                                            )
                                            channel.flush()
                                        },
                                        onChatId = { chatId = it }
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(
                                            ChatCompletionChunk.serializer(),
                                            ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                                            )
                                        ) + "\n\n").toByteArray()
                                    )
                                    channel.writeFully("data: [DONE]\n\n".toByteArray())
                                    channel.flush()
                                    if (deleteSessionAfterReply) chatId?.let { KimiClient.deleteChat(it, rawToken) }
                                } finally {
                                    resp.close()
                                }
                            }
                        } catch (e: Exception) {
                            LogStore.e("Kimi", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    val resp = KimiClient.chatCompletion(req.messages, rawToken)
                    try {
                        val sb = StringBuilder()
                        var chatId: String? = null
                        KimiClient.decodeFrames(
                            resp.body!!.source(),
                            onText = { sb.append(it) },
                            onChatId = { chatId = it }
                        )
                        if (deleteSessionAfterReply) chatId?.let { KimiClient.deleteChat(it, rawToken) }
                        sb.toString() to null
                    } finally {
                        resp.close()
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Kimi", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("Kimi", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        if (contentText.isBlank()) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Kimi 未返回内容"))
            return
        }
        LogStore.i("Kimi", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /**
     * 将 OpenAI 格式 messages 拼成单个 prompt（含 system 人设 + 全部历史对话）。
     * 与豆包策略一致：多轮用 `[role]: content` 拼接；单条直接返回；system 作为前置人设。
     */
    private fun extractDeepSeekPrompt(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        val system = messages.firstOrNull { it.role == "system" }?.content?.takeIf { it.isNotBlank() }
        val rest = messages.filter { it.role != "system" }
        val sb = StringBuilder()
        if (system != null) sb.append(system).append("\n\n")
        when {
            rest.isEmpty() -> return system ?: ""
            rest.size == 1 -> sb.append(rest[0].content)
            else -> sb.append(rest.joinToString("\n\n") { "[${it.role}]: ${it.content}" })
        }
        return sb.toString().trim().ifBlank { messages.lastOrNull { it.role == "user" }?.content ?: "" }
    }

    /**
     * DeepSeek 聊天：每次请求需完成一次 PoW（见 DeepSeekPow），再把答案写入
     * `x-ds-pow-response` 头。上游恒为 SSE 流式。
     *  - 客户端要求流式 → 边解析边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 聚合完整文本后返回单个 JSON
     */
    internal suspend fun RoutingContext.handleDeepSeekChat(
        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val prompt = extractDeepSeekPrompt(req.messages)
        if (prompt.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "empty prompt"))
            return
        }

        // 根据客户端请求里的 model 名字决定 DeepSeek 网页端 model_type：
        //   deepseek-pro / deepseek-reasoner / deepseek-expert / deepseek-think / deepseek-r1 → 专家模式(expert)
        //   其余（deepseek / deepseek-chat，或不带以上关键字）→ 沿用 UI 设定的 deepseekMode（默认 default）
        val lowerModel = req.model.lowercase()
        val modelType = if (lowerModel.contains("pro") || lowerModel.contains("reasoner")
            || lowerModel.contains("expert") || lowerModel.contains("think") || lowerModel.contains("r1")
        ) "expert" else deepseekMode
        LogStore.i("DeepSeek", "model=${req.model} → model_type=$modelType")

        LogStore.i("DeepSeek", "调用 /chat/completion，输入长度=${prompt.length}")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                DeepSeekClient.token = nextDeepSeekToken()
                                val sessionId = DeepSeekClient.createSession()
                                val ch = DeepSeekClient.createPowChallenge("/api/v0/chat/completion")
                                val answer = DeepSeekPow.solve(ch.challenge, ch.salt, ch.expireAt, ch.difficulty)
                                val powHeader = DeepSeekClient.buildPowHeader(ch, answer, "/api/v0/chat/completion")
                                DeepSeekClient.chatCompletion(sessionId, prompt, powHeader, modelType).use { resp ->
                                    val roleChunk = ChatCompletionChunk(
                                        completionId, "chat.completion.chunk", created, req.model,
                                        listOf(ChunkChoice(delta = ChoiceDelta(role = "assistant")))
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), roleChunk) + "\n\n").toByteArray()
                                    )
                                    channel.flush()

                                    // 流式逐块转发：parseSse 每解析到一个 v 字段就立即回传给本地接口。
                                    // 现在解析正确（仅取 v 字段），无需等完整响应，流式更实时。
                                    DeepSeekClient.parseSse(resp.body!!.source()) { t ->
                                        if (t.isNotEmpty()) {
                                            val chunk = ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(content = t)))
                                            )
                                            channel.writeFully(
                                                ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray()
                                            )
                                            channel.flush()
                                        }
                                    }
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(
                                            ChatCompletionChunk.serializer(),
                                            ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                                            )
                                        ) + "\n\n").toByteArray()
                                    )
                                    channel.writeFully("data: [DONE]\n\n".toByteArray())
                                    channel.flush()
                                }
                                if (deleteSessionAfterReply) DeepSeekClient.deleteSession(sessionId)
                            }
                        } catch (e: Exception) {
                            LogStore.e("DeepSeek", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    DeepSeekClient.token = nextDeepSeekToken()
                    val sessionId = DeepSeekClient.createSession()
                    val ch = DeepSeekClient.createPowChallenge("/api/v0/chat/completion")
                    val answer = DeepSeekPow.solve(ch.challenge, ch.salt, ch.expireAt, ch.difficulty)
                    val powHeader = DeepSeekClient.buildPowHeader(ch, answer, "/api/v0/chat/completion")
                    val dsPair = DeepSeekClient.chatCompletion(sessionId, prompt, powHeader, modelType).use { resp ->
                        val sb = StringBuilder()
                        DeepSeekClient.parseSse(resp.body!!.source()) { sb.append(it) }
                        sb.toString() to null
                    }
                    if (deleteSessionAfterReply) DeepSeekClient.deleteSession(sessionId)
                    dsPair
                }
            } catch (e: Exception) {
                LogStore.e("DeepSeek", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("DeepSeek", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        LogStore.i("DeepSeek", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    /**
     * 解析豆包 SSE，逐块回调文本增量（思考/正文均作为 content 输出）。
     * 对齐 doubao2api 的 iter_sse_events：同时解析 event: 与 data: 行，
     * 处理 gateway-error / STREAM_ERROR 事件，并提取 block_type 10040（深度思考）。
     */
    private suspend fun parseDoubaoSse(src: okio.BufferedSource, onDelta: suspend (String) -> Unit) {
        val data = StringBuilder()
        var eventName = "message"
        while (true) {
            val line = src.readUtf8Line() ?: break
            if (line.isEmpty()) {
                val payload = data.toString().trim()
                val ev = eventName
                data.setLength(0)
                eventName = "message"
                if (payload.isEmpty()) continue
                try {
                    val obj = org.json.JSONObject(payload)
                    // 网关级错误（event: gateway-error，登录失效/用户失效）
                    if (ev == "gateway-error") {
                        val code = obj.optString("code", "")
                        val msg = obj.optString("message", payload.take(200))
                        throw RuntimeException("豆包网关错误: code=$code message=$msg")
                    }
                    // STREAM_ERROR 事件或 data 内 error_code 限流/业务错误
                    if (ev == "STREAM_ERROR" || obj.has("error_code")) {
                        val code = obj.optInt("error_code", 0)
                        if (code != 0) {
                            val msg = obj.optString("error_msg", "")
                            if (code == 710022002 || code == 710022004) {
                                throw RuntimeException("豆包限流($code): $msg")
                            }
                            LogStore.e("Doubao", "error_code=$code $msg")
                            return
                        }
                    }
                    // 直接文本字段
                    if (obj.has("text") && obj.optString("text", "").isNotEmpty()) {
                        onDelta(obj.getString("text"))
                    }
                    // content_block 解析
                    val blocks = mutableListOf<org.json.JSONObject>()
                    val patchOps = obj.optJSONArray("patch_op")
                    if (patchOps != null) {
                        for (i in 0 until patchOps.length()) {
                            val pv = patchOps.optJSONObject(i)?.optJSONObject("patch_value") ?: continue
                            val cb = pv.optJSONArray("content_block") ?: continue
                            for (j in 0 until cb.length()) blocks.add(cb.getJSONObject(j))
                        }
                    }
                    val contentObj = obj.optJSONObject("content")
                    if (contentObj != null) {
                        val cb = contentObj.optJSONArray("content_block")
                        if (cb != null) for (j in 0 until cb.length()) blocks.add(cb.getJSONObject(j))
                    }
                    for (cb in blocks) {
                        val bt = cb.optInt("block_type", 0)
                        val c = cb.optJSONObject("content") ?: org.json.JSONObject()
                        when (bt) {
                            10000 -> {
                                val t = c.optJSONObject("text_block")?.optString("text", "")
                                if (!t.isNullOrEmpty()) onDelta(t)
                            }
                            10040 -> { // 深度思考（思考过程）同样作为内容输出
                                val t = c.optJSONObject("text_block")?.optString("text", "")
                                if (!t.isNullOrEmpty()) onDelta(t)
                            }
                            10101 -> {
                                val t = c.optJSONObject("loading_block")?.optJSONObject("text_loading")?.optString("text", "")
                                if (!t.isNullOrEmpty()) onDelta(t)
                            }
                            10024 -> {
                                val t = c.optJSONObject("generic_tool_block")?.optString("title", "")
                                if (!t.isNullOrEmpty()) onDelta("[tool] $t")
                            }
                        }
                    }
                    // patch_value.content 字符串内嵌 JSON
                    if (patchOps != null) {
                        for (i in 0 until patchOps.length()) {
                            val pv = patchOps.optJSONObject(i)?.optJSONObject("patch_value") ?: continue
                            val pvContent = pv.optString("content", "")
                            if (pvContent.isNotEmpty()) {
                                try {
                                    val co = org.json.JSONObject(pvContent)
                                    val t = co.optString("text", "")
                                    if (t.isNotEmpty()) onDelta(t)
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e.message?.startsWith("豆包") == true) throw e
                }
            } else if (line.startsWith("event:")) {
                eventName = line.substring(6).trim()
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trimStart()).append("\n")
            }
        }
    }

    /**
     * 千问（Qwen）聊天：上游为 SSE 流式。
     *  - 客户端要求流式 → 边解析帧边转发 OpenAI 格式 chunk
     *  - 客户端要求非流式 → 聚合完整文本后返回单个 JSON
     * 普通 / 思考由 model 名是否含 think/reason 决定（deep_search）。
     * 回复结束后按 deleteSessionAfterReply 开关删除该会话。
     */
    /**
     * 混元（aistudio 网页版）聊天。
     * 会话路由挂在 URL 上，故每次请求先新建一个会话，回复完成后按开关删除（与千问一致，避免堆积历史）。
     * 思考链（type=thought）不混入正文，只取 type=text。
     */
    internal suspend fun RoutingContext.handleHunyuanChat(        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val cookie = nextHunyuanCookie()
        val text = extractDoubaoPrompt(req.messages)
        val (chatModelId, effort) = HunyuanStudioClient.resolve(req.model)
        LogStore.i(
            "Hunyuan",
            "调用聊天，输入长度=${text.length} model=${req.model} " +
                    "chatModelId=$chatModelId effort=$effort cookie空白=${cookie.isBlank()}"
        )
        if (cookie.isBlank()) {
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to "混元未配置 Cookie：请在「浏览器」页登录 aistudio.tencent.com 后点「提取Cookie」")
            )
            return
        }

        val convId = try {
            withContext(Dispatchers.IO) { HunyuanStudioClient.newConversation(cookie) }
        } catch (e: Exception) {
            LogStore.e("Hunyuan", "新建会话失败: ${e.localizedMessage}")
            call.respond(
                HttpStatusCode.BadGateway,
                mapOf("error" to (e.localizedMessage ?: "混元新建会话失败"))
            )
            return
        }

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                val resp = HunyuanStudioClient.chatCompletion(
                                    convId, text, cookie, chatModelId, effort
                                )
                                try {
                                    val roleChunk = ChatCompletionChunk(
                                        completionId, "chat.completion.chunk", created, req.model,
                                        listOf(ChunkChoice(delta = ChoiceDelta(role = "assistant")))
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(
                                            ChatCompletionChunk.serializer(), roleChunk
                                        ) + "\n\n").toByteArray()
                                    )
                                    channel.flush()

                                    HunyuanStudioClient.parseSse(resp.body!!.source()) { t ->
                                        val chunk = ChatCompletionChunk(
                                            completionId, "chat.completion.chunk", created, req.model,
                                            listOf(ChunkChoice(delta = ChoiceDelta(content = t)))
                                        )
                                        channel.writeFully(
                                            ("data: " + json.encodeToString(
                                                ChatCompletionChunk.serializer(), chunk
                                            ) + "\n\n").toByteArray()
                                        )
                                        channel.flush()
                                    }

                                    channel.writeFully(
                                        ("data: " + json.encodeToString(
                                            ChatCompletionChunk.serializer(),
                                            ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(
                                                    ChunkChoice(
                                                        delta = ChoiceDelta(),
                                                        finish_reason = "stop"
                                                    )
                                                )
                                            )
                                        ) + "\n\n").toByteArray()
                                    )
                                    channel.writeFully("data: [DONE]\n\n".toByteArray())
                                    channel.flush()
                                } finally {
                                    resp.close()
                                }
                            }
                        } catch (e: Exception) {
                            LogStore.e("Hunyuan", "流式转发异常: ${e.stackTraceToString()}")
                        } finally {
                            if (deleteSessionAfterReply) {
                                HunyuanStudioClient.deleteConversation(convId, cookie)
                            }
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    val resp = HunyuanStudioClient.chatCompletion(
                        convId, text, cookie, chatModelId, effort
                    )
                    try {
                        val sb = StringBuilder()
                        HunyuanStudioClient.parseSse(resp.body!!.source()) { sb.append(it) }
                        sb.toString() to null
                    } finally {
                        resp.close()
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Hunyuan", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }
        // 删除会话是阻塞网络调用，切到 IO，别占着 Ktor 的事件循环线程
        if (deleteSessionAfterReply) {
            withContext(Dispatchers.IO) {
                HunyuanStudioClient.deleteConversation(convId, cookie)
            }
        }

        if (errMsg != null) {
            LogStore.e("Hunyuan", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        if (contentText.isBlank()) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "混元未返回内容"))
            return
        }
        LogStore.i("Hunyuan", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    internal suspend fun RoutingContext.handleQwenChat(        req: ChatCompletionRequest, completionId: String, created: Int
    ) {
        val cookie = nextQwenCookie()
        val text = extractDoubaoPrompt(req.messages)
        val deepSearch = if (req.model.lowercase().let { it.contains("think") || it.contains("reason") }) "1" else "0"
        val qwenModel = if (req.model.lowercase() in setOf("qwen", "qwen-think")) QwenClient.DEFAULT_MODEL else req.model
        LogStore.i("Qwen", "调用 /chat，输入长度=${text.length} deepSearch=$deepSearch model=$qwenModel cookie空白=${cookie.isBlank()}")

        if (req.stream) {
            call.respond(object : OutgoingContent.WriteChannelContent() {
                override val contentType = ContentType.Text.EventStream
                override val headers = Headers.build {
                    append("Cache-Control", "no-cache")
                    append("Connection", "keep-alive")
                    append("X-Accel-Buffering", "no")
                }

                override suspend fun writeTo(channel: ByteWriteChannel) {
                    withContext(Dispatchers.IO) {
                        try {
                            withTimeout(300_000) {
                                val resp = QwenClient.chatCompletion(text, cookie, deepSearch, qwenModel)
                                try {
                                    val roleChunk = ChatCompletionChunk(
                                        completionId, "chat.completion.chunk", created, req.model,
                                        listOf(ChunkChoice(delta = ChoiceDelta(role = "assistant")))
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), roleChunk) + "\n\n").toByteArray()
                                    )
                                    channel.flush()

                                    var sessionId: String? = null
                                    QwenClient.parseQwenSse(
                                        resp.body!!.source(),
                                        onText = { t ->
                                            val chunk = ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(content = t)))
                                            )
                                            channel.writeFully(
                                                ("data: " + json.encodeToString(ChatCompletionChunk.serializer(), chunk) + "\n\n").toByteArray()
                                            )
                                            channel.flush()
                                        },
                                        onSessionId = { sessionId = it }
                                    )
                                    channel.writeFully(
                                        ("data: " + json.encodeToString(
                                            ChatCompletionChunk.serializer(),
                                            ChatCompletionChunk(
                                                completionId, "chat.completion.chunk", created, req.model,
                                                listOf(ChunkChoice(delta = ChoiceDelta(), finish_reason = "stop"))
                                            )
                                        ) + "\n\n").toByteArray()
                                    )
                                    channel.writeFully("data: [DONE]\n\n".toByteArray())
                                    channel.flush()
                                    if (deleteSessionAfterReply) sessionId?.let { QwenClient.deleteSession(it, cookie) }
                                } finally {
                                    resp.close()
                                }
                            }
                        } catch (e: Exception) {
                            LogStore.e("Qwen", "流式转发异常: ${e.stackTraceToString()}")
                        }
                    }
                }
            })
            return
        }

        val (content, errMsg) = withContext(Dispatchers.IO) {
            try {
                withTimeout(300_000) {
                    val resp = QwenClient.chatCompletion(text, cookie, deepSearch, qwenModel)
                    try {
                        val sb = StringBuilder()
                        var sessionId: String? = null
                        QwenClient.parseQwenSse(
                            resp.body!!.source(),
                            onText = { sb.append(it) },
                            onSessionId = { sessionId = it }
                        )
                        if (deleteSessionAfterReply) sessionId?.let { QwenClient.deleteSession(it, cookie) }
                        sb.toString() to null
                    } finally {
                        resp.close()
                    }
                }
            } catch (e: Exception) {
                LogStore.e("Qwen", "请求异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }

        if (errMsg != null) {
            LogStore.e("Qwen", "返回错误给客户端: $errMsg")
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }

        val contentText = content ?: ""
        if (contentText.isBlank()) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "千问未返回内容"))
            return
        }
        LogStore.i("Qwen", "返回完成结果，长度=${contentText.length}")
        call.respond(
            ChatCompletion(
                completionId, "chat.completion", created, req.model,
                listOf(Choice(message = Msg("assistant", contentText), finish_reason = "stop")),
                Usage(), ""
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  进程内直连入口（免启动端口）
    //
    //  供 HunyuanLocalGateway 调用：不经过 Ktor，也不发起本地 HTTP 请求，
    //  直接复用上面的 Client / 解析逻辑算出结果，返回 OpenAI 格式的 JSON 字符串。
    //  分流顺序与非流式聚合逻辑均与 Ktor 路由完全一致，两种路径结果相同。
    // ══════════════════════════════════════════════════════════════════════

    /** 进程内调用结果：HTTP 状态码 + 响应体（JSON 字符串）。 */
    data class InProcessResult(val code: Int, val body: String)

    /** 与 Ktor handler 一致的上游超时。 */
    private const val UPSTREAM_TIMEOUT_MS = 300_000L

    /**
     * DeepSeek 依赖 PoW（须用 WebView 加载 wasm 计算，只能由 ProxyService 在主线程初始化），
     * 进程内直连无法提供，需回退到已启动的真实端口。
     */
    val DEEPSEEK_NEEDS_SERVER = InProcessResult(
        503,
        """{"error":"DeepSeek 需要混元太极服务处于「已启动」状态（PoW 依赖 WebView），请在混元太极界面点「启动」后重试"}"""
    )

    private fun errJson(msg: String): String =
        org.json.JSONObject().put("error", msg).toString()

    /** 统一的上游调用包裹：切到 IO 线程 + 300s 超时 + 异常落日志。 */
    private suspend fun <T> upstream(tag: String, block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                withTimeout(UPSTREAM_TIMEOUT_MS) { block() }
            } catch (e: Exception) {
                LogStore.e(tag, "进程内请求异常: ${e.stackTraceToString()}")
                throw e
            }
        }

    /**
     * 聊天（进程内）。分流顺序与 [handleChatCompletions] 完全一致。
     * 恒按「非流式」处理：一次性聚合完整文本后返回 OpenAI JSON
     *（朗读 / 背景音乐 / 生图三处均为「发完等完整结果」，不需要 SSE）。
     */
    suspend fun chatInProcess(bodyJson: String): InProcessResult {
        val req = try {
            json.decodeFromString<ChatCompletionRequest>(bodyJson)
        } catch (e: Exception) {
            return InProcessResult(400, errJson("请求体解析失败: ${e.localizedMessage}"))
        }
        val model = req.model
        LogStore.i("Proxy", "进程内聊天 | model=$model msgs=${req.messages.size}")
        return try {
            val content = when {
                isHunyuanModel(model) -> hunyuanText(req)
                isKimiModel(model) -> kimiText(req)
                isZhipuModel(model) -> zhipuText(req, isZhipuPlusModel(model))
                isWenxinModel(model) -> wenxinText(req)
                isLongCatModel(model) -> longCatText(req)
                isBailianModel(model) -> bailianText(req)
                isQwenModel(model) -> qwenText(req)
                isDeepSeekModel(model) -> return DEEPSEEK_NEEDS_SERVER
                isDoubaoModel(model) -> doubaoText(req)
                else -> cnbText(req)
            }
            if (content.isBlank()) {
                InProcessResult(502, errJson("模型未返回内容"))
            } else {
                InProcessResult(200, chatJson(model, content))
            }
        } catch (e: Exception) {
            InProcessResult(502, errJson(e.localizedMessage ?: e.toString()))
        }
    }

    /** 生图（进程内）。返回 OpenAI images 格式 JSON。 */
    suspend fun imageInProcess(bodyJson: String): InProcessResult {
        val o = try {
            org.json.JSONObject(bodyJson)
        } catch (e: Exception) {
            return InProcessResult(400, errJson("请求体解析失败: ${e.localizedMessage}"))
        }
        val prompt = o.optString("prompt", "")
        val size = o.optString("size", "")
        val model = o.optString("model", "")
        val ratio = resolveRatio(if (size.isNotBlank()) size else defaultImageSize)
        LogStore.i("Proxy", "进程内生图 | model=$model ratio=$ratio")
        return try {
            val urls = if (isWenxinModel(model)) {
                // 文心（免登录）：一次返回 4 张，只取第一张
                upstream("Wenxin") {
                    WenxinClient.generateImage(prompt).take(1)
                }
            } else if (isZhipuImageModel(model)) {
                upstream("Zhipu") {
                    ZhipuClient.generateImage(prompt, ratio, isZhipuCinematicImageModel(model))
                }
            } else if (isStudioGlobalImageModel(model)) {
                // StudioGlobal（免登录）：站内无 OpenAI 兼容端点，由 client 转 send-chat 协议
                upstream("StudioGlobal") {
                    StudioGlobalClient.generateImage(prompt, model)
                }
            } else if (isHunyuanImageModel(model)) {
                // 混元生图（aistudio 网页版）：与混元文本同一套 Cookie
                upstream("Hunyuan") {
                    val cookie = nextHunyuanCookie()
                    if (cookie.isBlank()) {
                        error("混元未配置 Cookie：请在「浏览器」页登录 aistudio.tencent.com 后点「提取Cookie」")
                    }
                    HunyuanStudioClient.generateImage(prompt, ratioToHunyuanScale(ratio), cookie)
                }
            } else {
                upstream("Doubao") {
                    val cookies = DoubaoClient.parseCookie(nextDoubaoCookie())
                    DoubaoClient.generateImage(prompt, cookies, ratio)
                        .images
                        .map { if (it.rawUrl.isNotBlank()) it.rawUrl else it.oriUrl }
                }
            }
            if (urls.isEmpty()) {
                InProcessResult(502, errJson("生图未返回图片"))
            } else {
                InProcessResult(
                    200,
                    json.encodeToString(
                        ImageGenResponse.serializer(),
                        ImageGenResponse(
                            created = (System.currentTimeMillis() / 1000).toInt(),
                            data = urls.map { ImageDataItem(url = it) }
                        )
                    )
                )
            }
        } catch (e: Exception) {
            InProcessResult(502, errJson(e.localizedMessage ?: e.toString()))
        }
    }

    /** /v1/models（进程内）。与真实端口返回同一份列表，供各配置页「测试连接」使用。 */
    fun modelsInProcess(): InProcessResult {
        val entries = listOf(
            CnbClient.DEFAULT_MODEL to "cnb",
            "hunyuan3" to "hunyuan",
            "hunyuan3-think" to "hunyuan",
            "hunyuan4" to "hunyuan",
            "hunyuan4-think" to "hunyuan",
            "hunyuan4-flash" to "hunyuan",
            "hunyuan4-flash-think" to "hunyuan",
            "hunyuan-image" to "hunyuan",
            "MiniMax-M3.1-Flash-Preview" to "max",
            "MiniMax-M3" to "max",
            "MiniMax-M2.7" to "max",
            "MiniMax-M2.7-highspeed" to "max",
            "longcat" to "longcat",
            "longcat-search" to "longcat",
            "bailian" to "bailian",
            "doubao" to "doubao",
            "doubao-think" to "doubao",
            "doubao-image" to "doubao",
            "Kimi" to "kimi",
            "deepseek" to "deepseek",
            "deepseek-pro" to "deepseek",
            "gml" to "zhipu",
            "gml-plus" to "zhipu",
            "glm-image" to "zhipu",
            "glm-image-hd" to "zhipu",
            "qwen" to "qwen",
            "qwen-think" to "qwen",
            "wenxin" to "wenxin"
        )
        val data = org.json.JSONArray()
        entries.forEach { (id, owner) ->
            data.put(
                org.json.JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("owned_by", owner)
            )
        }
        return InProcessResult(
            200,
            org.json.JSONObject().put("object", "list").put("data", data).toString()
        )
    }

    private fun chatJson(model: String, content: String): String =
        json.encodeToString(
            ChatCompletion.serializer(),
            ChatCompletion(
                id = "chatcmpl-${UUID.randomUUID().toString().take(12)}",
                `object` = "chat.completion",
                created = (System.currentTimeMillis() / 1000).toInt(),
                model = model,
                choices = listOf(
                    Choice(message = Msg("assistant", content), finish_reason = "stop")
                ),
                usage = Usage(),
                conversation_id = ""
            )
        )

    // ── 各上游的「非流式聚合」：与对应 handleXxxChat 的非流式分支逻辑一致 ──

    private suspend fun kimiText(req: ChatCompletionRequest): String = upstream("Kimi") {
        val rawToken = KimiClient.extractToken(nextKimiCookie())
        val resp = KimiClient.chatCompletion(req.messages, rawToken)
        var chatId: String? = null
        val sb = StringBuilder()
        try {
            KimiClient.decodeFrames(
                resp.body!!.source(),
                onText = { sb.append(it) },
                onChatId = { chatId = it }
            )
        } finally {
            resp.close()
        }
        if (deleteSessionAfterReply) chatId?.let { KimiClient.deleteChat(it, rawToken) }
        sb.toString()
    }

    private suspend fun zhipuText(req: ChatCompletionRequest, plus: Boolean): String =
        upstream("Zhipu") {
            val text = extractDeepSeekPrompt(req.messages)
            val resp = ZhipuClient.chatCompletion(text, plusModel = plus)
            val sb = StringBuilder()
            try {
                parseZhipuSse(resp.body!!.source()) { sb.append(it) }
            } finally {
                resp.close()
            }
            sb.toString()
        }

    /** 文心（免登录匿名会话，进程内）：网页端只接受单段文本，先合并再请求。 */
    private suspend fun wenxinText(req: ChatCompletionRequest): String = upstream("Wenxin") {
        WenxinClient.chat(extractDeepSeekPrompt(req.messages))
    }

    private suspend fun longCatText(req: ChatCompletionRequest): String = upstream("LongCat") {
        applyNextLongCatConfig()
        if (LongCatClient.mtgsig.isBlank() || LongCatClient.cookies.isBlank()) {
            throw IllegalStateException(
                "LongCat 未配置：请在混元太极「中转」页选择龙猫并填写抓包得到的配置(JSON)"
            )
        }
        val resp = LongCatClient.chatCompletion(req.messages, req.model)
        val sb = StringBuilder()
        try {
            parseLongCatSse(resp.body!!.source()) { sb.append(it) }
        } finally {
            resp.close()
        }
        sb.toString()
    }

    /** 百炼（阿里云，进程内）：聚合完整文本。 */
    private suspend fun bailianText(req: ChatCompletionRequest): String = upstream("Bailian") {
        applyNextBailianConfig()
        if (!BailianClient.isConfigured()) {
            throw IllegalStateException(
                "百炼未配置：请在混元太极「中转」页选择百炼并粘贴抓包 JSON（cookies + bx 三件套）"
            )
        }
        val resp = BailianClient.chatCompletion(req.messages, req.model)
        val sb = StringBuilder()
        try {
            parseBailianSse(resp.body!!.source()) { sb.append(it) }
        } finally {
            resp.close()
        }
        sb.toString()
    }

    private suspend fun hunyuanText(req: ChatCompletionRequest): String = upstream("Hunyuan") {
        val cookie = nextHunyuanCookie()
        if (cookie.isBlank()) {
            throw RuntimeException(
                "混元未配置 Cookie：请在「浏览器」页登录 aistudio.tencent.com 后点「提取Cookie」"
            )
        }
        val text = extractDoubaoPrompt(req.messages)
        val (chatModelId, effort) = HunyuanStudioClient.resolve(req.model)
        val convId = HunyuanStudioClient.newConversation(cookie)
        val sb = StringBuilder()
        try {
            val resp = HunyuanStudioClient.chatCompletion(convId, text, cookie, chatModelId, effort)
            try {
                HunyuanStudioClient.parseSse(resp.body!!.source()) { sb.append(it) }
            } finally {
                resp.close()
            }
        } finally {
            if (deleteSessionAfterReply) HunyuanStudioClient.deleteConversation(convId, cookie)
        }
        sb.toString()
    }

    private suspend fun qwenText(req: ChatCompletionRequest): String = upstream("Qwen") {
        val cookie = nextQwenCookie()
        val text = extractDoubaoPrompt(req.messages)
        val lower = req.model.lowercase()
        val deepSearch = if (lower.contains("think") || lower.contains("reason")) "1" else "0"
        val qwenModel =
            if (lower in setOf("qwen", "qwen-think")) QwenClient.DEFAULT_MODEL else req.model
        val resp = QwenClient.chatCompletion(text, cookie, deepSearch, qwenModel)
        var sessionId: String? = null
        val sb = StringBuilder()
        try {
            QwenClient.parseQwenSse(
                resp.body!!.source(),
                onText = { sb.append(it) },
                onSessionId = { sessionId = it }
            )
        } finally {
            resp.close()
        }
        if (deleteSessionAfterReply) sessionId?.let { QwenClient.deleteSession(it, cookie) }
        sb.toString()
    }

    private suspend fun doubaoText(req: ChatCompletionRequest): String = upstream("Doubao") {
        val cookies = DoubaoClient.parseCookie(nextDoubaoCookie())
        val text = extractDoubaoPrompt(req.messages)
        val resp = DoubaoClient.chatCompletion(text, cookies, doubaoDeepThink(req.model))
        val sb = StringBuilder()
        try {
            parseDoubaoSse(resp.body!!.source()) { sb.append(it) }
        } finally {
            resp.close()
        }
        sb.toString()
    }

    /**
     * 混元3 / CNB（进程内）。
     *
     * 上游请求体与 Ktor 路由完全一致（同样用 [streamMode]），保证开/不开服务结果一致；
     * 进程内只是把结果一次性聚合成 JSON 返回。
     * 解析兼容两种上游返回：SSE（streamMode=true）或 JSON（streamMode=false）。
     */
    private suspend fun cnbText(req: ChatCompletionRequest): String {
        val model = mapModel(req.model)
        val body = org.json.JSONObject().apply {
            put("model", model)
            put("stream", streamMode)
            put(
                "messages",
                org.json.JSONArray(
                    req.messages.map {
                        org.json.JSONObject().put("role", it.role).put("content", it.content)
                    }
                )
            )
            req.temperature?.let { put("temperature", it) }
            req.max_tokens?.let { put("max_tokens", it) }
        }.toString()
        return upstream("CNB") {
            CnbClient.chatCompletion(body).use { resp ->
                parseCnbContent(resp.body!!.string())
            }
        }
    }

    /** 解析 CNB 返回体：优先按非流式 JSON 取 content，失败则按 SSE 聚合兜底。 */
    private fun parseCnbContent(text: String): String {
        val t = text.trim()
        if (t.startsWith("{")) {
            runCatching {
                // optString 而非 getString：content 为 JSON null 时 getString 会返回
                // 字面量 "null"（org.json 的坑），混进正文就成了用户看到的 nullnullnull
                return org.json.JSONObject(t)
                    .getJSONArray("choices").getJSONObject(0)
                    .optJSONObject("message")?.optString("content").orEmpty()
            }.onFailure {
                LogStore.w("CNB", "JSON 解析失败，改按 SSE 兜底解析: ${it.message}")
            }
        }
        val sb = StringBuilder()
        parseCnbSse(okio.Buffer().apply { writeUtf8(text) }) { sb.append(it) }
        return sb.toString()
    }
}

internal suspend fun RoutingContext.handleChatCompletions() {
    try {
        val req = call.receive<ChatCompletionRequest>()
        LogStore.i(
            "Proxy",
            "收到请求 | clientStream=${req.stream} upstreamStream=$ProxyServer.streamMode model=${req.model} msgs=${req.messages.size}"
        )

        // 混元（aistudio 网页版）模型 → 走腾讯混元官方接口（混元3 / 混元4，含思考模式）
        if (ProxyServer.isHunyuanModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleHunyuanChat(req, completionId, created)
            return
        }

        // Kimi 模型 → 走 Kimi 接口（聊天）
        if (ProxyServer.isKimiModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleKimiChat(req, completionId, created)
            return
        }

        // 智谱（GLM）文本模型 → 走智谱游客接口（免登录）
        if (ProxyServer.isZhipuModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleZhipuChat(req, completionId, created, ProxyServer.isZhipuPlusModel(req.model))
            return
        }

        // 文心模型 → 走文心网页端匿名会话（免登录）
        if (ProxyServer.isWenxinModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleWenxinChat(req, completionId, created)
            return
        }

        // LongCat（美团龙猫）模型 → 走 LongCat 接口
        if (ProxyServer.isLongCatModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleLongCatChat(req, completionId, created)
            return
        }

        // 百炼（阿里云）模型 → 走百炼控制台接口
        if (ProxyServer.isBailianModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleBailianChat(req, completionId, created)
            return
        }

        // MiniMax（max）模型 → 走 MiniMax Code 会员接口（设备码授权 + 凭证轮换）
        if (ProxyServer.isMiniMaxModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleMiniMaxChat(req, completionId, created)
            return
        }

        // 千问模型 → 走千问接口（普通 / 思考，由模型名决定）
        if (ProxyServer.isQwenModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleQwenChat(req, completionId, created)
            return
        }

        // DeepSeek 模型 → 走 DeepSeek 接口（含 PoW）
        if (ProxyServer.isDeepSeekModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleDeepSeekChat(req, completionId, created)
            return
        }

        // 豆包模型 → 走豆包接口（聊天）
        if (ProxyServer.isDoubaoModel(req.model)) {
            val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
            val created = (System.currentTimeMillis() / 1000).toInt()
            handleDoubaoChat(req, completionId, created)
            return
        }

        val model = ProxyServer.mapModel(req.model)
        val bodyJson = org.json.JSONObject().apply {
            put("model", model)
            put("stream", ProxyServer.streamMode)
            put(
                "messages",
                org.json.JSONArray(
                    req.messages.map {
                        org.json.JSONObject().put("role", it.role).put("content", it.content)
                    }
                )
            )
            req.temperature?.let { put("temperature", it) }
            req.max_tokens?.let { put("max_tokens", it) }
        }

        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"
        val created = (System.currentTimeMillis() / 1000).toInt()

        if (ProxyServer.streamMode) {
            handleStreamUpstream(req, model, bodyJson.toString(), completionId, created)
        } else {
            handleNonStreamUpstream(req, model, bodyJson.toString(), completionId, created)
        }
    } catch (e: Exception) {
        LogStore.e("Proxy", "请求处理失败: ${e.stackTraceToString()}")
        if (!call.response.isSent) {
            try {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.localizedMessage ?: e.toString()))
                )
            } catch (_: Exception) {
            }
        }
    }
}

internal suspend fun RoutingContext.handleImageGen() {
    try {
        val raw = call.receiveText()
        val o = org.json.JSONObject(raw)
        val prompt = o.optString("prompt", "")
        val size = o.optString("size", "")
        val model = o.optString("model", "")
        // 外部指定且可识别 → 用之；否则用设置界面尺寸；再不行兜底 9:16 竖版
        val ratio = ProxyServer.resolveRatio(if (size.isNotBlank()) size else ProxyServer.defaultImageSize)

        // 文心生图（免登录）：model 命中 wenxin → 走文心妙图，只返回第一张
        if (ProxyServer.isWenxinModel(model)) {
            LogStore.i("Wenxin", "调用生图，prompt=${prompt.take(60)}")
            val (urls, errMsg) = withContext(Dispatchers.IO) {
                try {
                    WenxinClient.generateImage(prompt) to null
                } catch (e: Exception) {
                    LogStore.e("Wenxin", "生图异常: ${e.stackTraceToString()}")
                    emptyList<String>() to (e.localizedMessage ?: e.toString())
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            if (urls.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "文心生图未返回图片（可能触发限流，请稍后重试）")
                )
                return
            }
            // 服务端一次返回 4 张，只返回第一张
            call.respond(
                ImageGenResponse(
                    created = (System.currentTimeMillis() / 1000).toInt(),
                    data = listOf(ImageDataItem(url = urls.first()))
                )
            )
            return
        }

        // 智谱生图（免登录）：model 命中智谱生图 / UI「智谱」(zhipu) → 走智谱，只返回第一张
        if (ProxyServer.isZhipuImageModel(model)) {
            val cinematic = ProxyServer.isZhipuCinematicImageModel(model)
            LogStore.i("Zhipu", "调用生图，prompt=${prompt.take(60)} ratio=$ratio 电影级=$cinematic")
            val (urls, errMsg) = withContext(Dispatchers.IO) {
                try {
                    ZhipuClient.generateImage(prompt, ratio, cinematic) to null
                } catch (e: Exception) {
                    LogStore.e("Zhipu", "生图异常: ${e.stackTraceToString()}")
                    emptyList<String>() to (e.localizedMessage ?: e.toString())
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            if (urls.isEmpty()) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to "智谱生图未返回图片"))
                return
            }
            // 仅返回第一张
            call.respond(
                ImageGenResponse(
                    created = (System.currentTimeMillis() / 1000).toInt(),
                    data = listOf(ImageDataItem(url = urls.first()))
                )
            )
            return
        }

        // StudioGlobal 生图（免登录）：model 命中 gpt-image → 走 send-chat 通道，只返回第一张
        if (ProxyServer.isStudioGlobalImageModel(model)) {
            LogStore.i("StudioGlobal", "调用生图，model=$model prompt=${prompt.take(60)}")
            val (urls, errMsg) = withContext(Dispatchers.IO) {
                try {
                    StudioGlobalClient.generateImage(prompt, model) to null
                } catch (e: Exception) {
                    LogStore.e("StudioGlobal", "生图异常: ${e.stackTraceToString()}")
                    emptyList<String>() to (e.localizedMessage ?: e.toString())
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            if (urls.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "StudioGlobal 生图未返回图片（匿名会话额度有限，可稍后重试）")
                )
                return
            }
            // 仅返回第一张
            call.respond(
                ImageGenResponse(
                    created = (System.currentTimeMillis() / 1000).toInt(),
                    data = listOf(ImageDataItem(url = urls.first()))
                )
            )
            return
        }

        // 混元生图（aistudio 网页版混元图像3.0）：与混元文本共用 Cookie 轮换通道
        if (ProxyServer.isHunyuanImageModel(model)) {
            val cookie = ProxyServer.nextHunyuanCookie()
            LogStore.i("Hunyuan", "调用生图，prompt=${prompt.take(60)} ratio=$ratio cookie空白=${cookie.isBlank()}")
            if (cookie.isBlank()) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "混元未配置 Cookie：请在「浏览器」页登录 aistudio.tencent.com 后点「提取Cookie」")
                )
                return
            }
            val (urls, errMsg) = withContext(Dispatchers.IO) {
                try {
                    HunyuanStudioClient.generateImage(prompt, ProxyServer.ratioToHunyuanScale(ratio), cookie) to null
                } catch (e: Exception) {
                    LogStore.e("Hunyuan", "生图异常: ${e.stackTraceToString()}")
                    emptyList<String>() to (e.localizedMessage ?: e.toString())
                }
            }
            if (errMsg != null) {
                call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
                return
            }
            if (urls.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("error" to "混元生图未返回图片（可能 Cookie 失效或触发限流）")
                )
                return
            }
            // 仅返回第一张
            call.respond(
                ImageGenResponse(
                    created = (System.currentTimeMillis() / 1000).toInt(),
                    data = listOf(ImageDataItem(url = urls.first()))
                )
            )
            return
        }

        val cookies = DoubaoClient.parseCookie(ProxyServer.nextDoubaoCookie())
        LogStore.i("Doubao", "调用生图，prompt=${prompt.take(60)} ratio=$ratio")

        val (result, errMsg) = withContext(Dispatchers.IO) {
            try {
                DoubaoClient.generateImage(prompt, cookies, ratio) to null
            } catch (e: Exception) {
                LogStore.e("Doubao", "生图异常: ${e.stackTraceToString()}")
                null to (e.localizedMessage ?: e.toString())
            }
        }
        if (errMsg != null) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to errMsg))
            return
        }
        val data = (result?.images ?: emptyList()).map {
            ImageDataItem(url = if (it.rawUrl.isNotBlank()) it.rawUrl else it.oriUrl)
        }
        if (data.isEmpty()) {
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "豆包生图未返回图片"))
            return
        }
        call.respond(
            ImageGenResponse(
                created = (System.currentTimeMillis() / 1000).toInt(),
                data = data
            )
        )
    } catch (e: Exception) {
        LogStore.e("Doubao", "生图处理失败: ${e.stackTraceToString()}")
        if (!call.response.isSent) {
            try {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to (e.localizedMessage ?: e.toString()))
                )
            } catch (_: Exception) {
            }
        }
    }
}
