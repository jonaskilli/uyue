package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.help.EngineDataDir
import io.legado.app.help.config.AppConfig
import io.legado.app.service.AiChapterFixService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 「与角色对话」的 AI 对话管理器。
 *
 * - 接口配置与「AI处理章节」同格式：接口@@模型@@密钥（3 段一组，可多组自动轮换），
 *   存在 [AppConfig.roleChatApiConfig]，解析复用 [AiChapterFixService.parseApiConfigs]。
 * - 请求为 OpenAI 兼容 /chat/completions，开启 stream=true 做 SSE 流式返回，
 *   每收到一段增量文本通过 [ChatCallback.onDelta] 回调，实现打字机效果。
 * - 会话按「书名+角色名」持久化到朗读数据目录 rolechat/ 下（与角色数据同源，便于备份）。
 */
object AiChatManager {

    private const val TAG = "角色对话"

    /** 一条对话消息。role 为 OpenAI 消息角色（user / assistant / system） */
    data class ChatMessage(
        val role: String,
        val content: String,
        val ts: Long = System.currentTimeMillis()
    )

    /** 流式回调 */
    interface ChatCallback {
        /** 收到一段增量文本（打字机追加用） */
        fun onDelta(delta: String)

        /** 流式结束，fullText 为完整回复 */
        fun onDone(fullText: String)

        /** 失败（网络/配置/中断） */
        fun onError(message: String)
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 清理回复文本里混入的 null 字面量。
     *
     * 部分模型/上游在流式分片里 content 为 JSON null，个别解析路径会把它转成
     * 字符串 "null" 拼进正文——表现为回复开头一长串 nullnullnull、句尾孤零零
     * 一个 null（[RoleChatActivity] 实际截图的规律）。正常中文对话里 "null"
     * 作为独立词成串出现只可能是这种解析残渣，直接清掉；普通英文单词
     * （如 nullable）不受影响。
     *
     * public：界面在流式打字机结束后还要对缓冲整体清理一次（跨分片拼接的
     * null 单看每个分片认不出来），历史读取时也用它把旧会话里的残渣修掉
     * ——残渣留在上下文里会被模型当成正常格式学着输出，越聊越多。
     */
    fun sanitizeReply(text: String): String {
        if (!text.contains("null")) return text
        return text
            // 正文中间的成串 null（nullnullnull / llnullnullnull，中间可夹空白）。
            // 刻意不加 \b：残渣常紧贴在前一个字符后面，加了边界反而认不出来；
            // 误伤面也极小——"nullable" 这类词里 null 只出现一次，{2,} 要求成串
            .replace(Regex("(?:null\\s*){2,}"), "")
            // 开头/结尾孤立的 null（对应首尾各一个 finish/role 分片）
            .replace(Regex("^(?:\\s*null\\s*)+"), "")
            .replace(Regex("(?:\\s*null\\s*)+$"), "")
            .trim()
    }

    /**
     * 独立客户端（与 BgmAIService 同款，挂混元太极本地网关拦截器）。
     * 流式回复总时长不可预估，禁用 callTimeout；readTimeout 是单次读取超时，
     * 只要两个增量之间间隔不超过它即视为存活。
     */
    private val client = OkHttpClient.Builder()
        .addInterceptor(io.legado.app.help.hunyuan.HunyuanLocalGateway)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 多组 API 轮换计数器 */
    private val apiCounter = AtomicLong(0)

    /** 是否已配置接口（未配置时 UI 引导去「接口设置」） */
    fun hasApiConfig(): Boolean =
        AppConfig.roleChatApiConfig.isNotBlank() &&
                AiChapterFixService.parseApiConfigs(AppConfig.roleChatApiConfig).isNotEmpty()

    // ------------------------------------------------------------------
    // 流式对话
    // ------------------------------------------------------------------

    /**
     * 发起流式对话。
     *
     * @param messages 完整消息列表（含 system 人设与历史上下文）
     * @param callback 流式回调，onDelta 在 IO 线程回调，UI 侧自行切换线程
     * @return 成功时为完整回复文本
     */
    suspend fun chatStream(
        messages: List<ChatMessage>,
        callback: ChatCallback
    ): Result<String> = withContext(Dispatchers.IO) {
        val providers = AiChapterFixService.parseApiConfigs(AppConfig.roleChatApiConfig)
        if (providers.isEmpty()) {
            val msg = "未配置对话接口，请在台词框上方「⚙ 接口」里填写（格式同AI处理章节：接口@@模型@@密钥）"
            callback.onError(msg)
            return@withContext Result.failure(IllegalStateException(msg))
        }
        // 多组 API 按顺序轮换，失败自动换下一组重试一轮
        val lastError = StringBuilder()
        repeat(providers.size) { attempt ->
            val provider = providers[apiCounter.getAndIncrement().mod(providers.size)]
            try {
                val full = doStreamOnce(provider, messages, callback)
                return@withContext Result.success(full)
            } catch (e: Exception) {
                val err = "第${attempt + 1}/${providers.size}组接口失败(${e.javaClass.simpleName})：${e.localizedMessage}"
                AppLog.put("$TAG: $err")
                lastError.append(err).append('\n')
            }
        }
        val msg = lastError.toString().trim()
        callback.onError(msg)
        Result.failure(RuntimeException(msg))
    }

    private fun doStreamOnce(
        provider: BgmAIProvider,
        messages: List<ChatMessage>,
        callback: ChatCallback
    ): String {
        val body = JSONObject().apply {
            put("model", provider.modelId)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.forEach {
                    put(JSONObject().put("role", it.role).put("content", it.content))
                }
            })
        }
        val request = Request.Builder()
            .url("${provider.url}/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        AppLog.putDebug("$TAG: 请求 ${provider.url}（模型 ${provider.modelId}，消息 ${messages.size} 条，流式）")
        var response: Response? = null
        try {
            response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    .take(500)
                throw RuntimeException("HTTP ${response.code} $errBody")
            }
            val source = response.body?.source() ?: throw RuntimeException("响应体为空")
            val full = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank() || line.startsWith(":")) continue
                if (line.startsWith("data:")) {
                    // 标准 SSE 流式增量
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        val choices = JSONObject(data).optJSONArray("choices") ?: return@runCatching ""
                        val deltaObj = choices.optJSONObject(0)?.optJSONObject("delta")
                        deltaObj?.optString("content").orEmpty()
                    }.getOrDefault("")
                    // 分片恰好是 null 字面量：上游把 JSON null 的 content 转成了字符串，
                    // 不是正文。思考型模型的 reasoning 阶段与结尾 finish 分片都长这样，
                    // 不丢的话就是用户看到的开头一串 null + 句尾一个 null
                    if (delta.isNotEmpty() && delta.trim() != "null") {
                        full.append(delta)
                        callback.onDelta(delta)
                    }
                } else if (line.trimStart().startsWith("{")) {
                    // 非流式整段 JSON（混元太极本地网关直连兜底时可能不走 SSE）
                    val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val content = obj.optJSONArray("choices")?.optJSONObject(0)
                        ?.optJSONObject("message")?.optString("content").orEmpty()
                    // 同上：整段回复为 null 字面量是解析残渣，丢弃让上层换下一组接口重试
                    if (content.isNotEmpty() && content.trim() != "null") {
                        full.append(content)
                        callback.onDelta(content)
                    }
                    break
                }
            }
            val text = sanitizeReply(full.toString())
            if (text.isBlank()) throw RuntimeException("AI 未返回任何内容（流式无增量）")
            return text
        } finally {
            runCatching { response?.close() }
        }
    }

    // ------------------------------------------------------------------
    // 人设 prompt
    // ------------------------------------------------------------------

    /**
     * 组装角色扮演的 system 人设。
     *
     * @param profileSummary 角色档案摘要（A 功能「角色档案」完成后传入身份/性格/说话风格/背景）；
     *   为空时退化为依据原著的通用扮演指令。
     */
    fun buildPersonaPrompt(
        bookName: String,
        characterName: String,
        gender: String = "",
        age: String = "",
        profileSummary: String? = null
    ): String {
        val genderAge = listOf(gender.trim(), age.trim()).filter { it.isNotBlank() }
            .joinToString("")
        val suffix = if (genderAge.isBlank()) "" else "（$genderAge）"
        val setting = profileSummary?.takeIf { it.isNotBlank() }
            ?: "暂无详细档案。请依据小说《$bookName》原著，按读者普遍认知的「$characterName」性格、经历与说话方式扮演。"
        return buildString {
            append("你正在与读者进行沉浸式的小说角色扮演互动。\n")
            append("你将扮演小说《$bookName》中的角色「$characterName」$suffix。\n\n")
            append("【人物设定】\n$setting\n\n")
            append("【扮演要求】\n")
            append("1. 始终以「$characterName」的第一人称口吻与读者对话，保持原著的性格与说话风格；\n")
            append("2. 熟悉小说剧情，可基于剧情展开对话，但不要刻意剧透读者尚未读到的内容；\n")
            append("3. 回复口语化、有情感，长度适中（一般 1~4 句话），不要写成叙述文或总结；\n")
            append("4. 动作与神态可以用圆括号（）简单描述，如：（皱眉）你怎么才来；\n")
            append("5. 不要跳出角色，不要提及你是 AI、模型或程序。")
        }
    }

    /** 组装发送给 AI 的消息列表：人设 + 裁剪后的历史 + 本轮用户输入 */
    fun buildRequestMessages(
        persona: String,
        history: List<ChatMessage>,
        userText: String
    ): List<ChatMessage> {
        val limit = AppConfig.roleChatContextLimit.coerceAtLeast(2)
        val trimmed = history.takeLast(limit)
        return ArrayList<ChatMessage>().apply {
            add(ChatMessage("system", persona))
            addAll(trimmed)
            add(ChatMessage("user", userText))
        }
    }

    // ------------------------------------------------------------------
    // 会话持久化（rolechat/<书名>_<角色名>.json）
    // ------------------------------------------------------------------

    fun historyFile(bookName: String, characterName: String): File {
        val dir = File(EngineDataDir.baseDir(), "rolechat")
        val safe = { s: String -> s.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_") }
        return File(dir, "${safe(bookName)}_${safe(characterName)}.json")
    }

    fun loadHistory(bookName: String, characterName: String): MutableList<ChatMessage> {
        val file = historyFile(bookName, characterName)
        if (!file.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("messages") ?: JSONArray()
            val list = ArrayList<ChatMessage>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = o.optString("role")
                // 旧会话里可能已经存进了 null 残渣：读出来顺手清掉。
                // 不清的话残渣会一直留在上下文里，模型学着输出 null，越聊越多
                val content = sanitizeReply(o.optString("content"))
                if (role.isBlank() || content.isBlank()) continue
                list.add(ChatMessage(role, content, o.optLong("ts")))
            }
            list
        }.getOrDefault(mutableListOf())
    }

    fun saveHistory(bookName: String, characterName: String, history: List<ChatMessage>) {
        runCatching {
            val file = historyFile(bookName, characterName)
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            history.forEach {
                arr.put(JSONObject().put("role", it.role).put("content", it.content).put("ts", it.ts))
            }
            file.writeText(JSONObject().put("messages", arr).toString())
        }.onFailure {
            AppLog.put("$TAG: 会话保存失败：${it.localizedMessage}")
        }
    }

    fun clearHistory(bookName: String, characterName: String) {
        runCatching { historyFile(bookName, characterName).delete() }
    }

    /** 供界面显示当前生效的接口组摘要（如：3 组接口已配置） */
    fun apiConfigSummary(): String {
        val n = AiChapterFixService.parseApiConfigs(AppConfig.roleChatApiConfig).size
        return if (n == 0) "未配置接口" else "已配置 $n 组接口"
    }

    /** 取默认模型名（用于界面提示），无配置返回空 */
    fun defaultModelName(): String =
        AiChapterFixService.parseApiConfigs(AppConfig.roleChatApiConfig).firstOrNull()?.modelId.orEmpty()
}
