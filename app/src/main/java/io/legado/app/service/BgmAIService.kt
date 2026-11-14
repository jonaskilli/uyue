package io.legado.app.service

import io.legado.app.data.appDb
import io.legado.app.data.dao.BgmAIProviderDao
import io.legado.app.data.dao.BgmAIPromptDao
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.util.concurrent.TimeUnit

/**
 * BGM AI服务
 */
object BgmAIService {

    // 混元太极本地网关：命中 127.0.0.1/localhost:<混元太极端口> 时进程内直连，无需先启动端口。
    // 本处是独立 OkHttpClient（不走 HttpHelper 全局客户端），故需单独注册。
    private val client = OkHttpClient.Builder()
        .addInterceptor(io.legado.app.help.hunyuan.HunyuanLocalGateway)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 默认整体超时（秒）：从请求发起到响应结束的总时限 */
    const val DEFAULT_CALL_TIMEOUT_SECONDS = 180L

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 根据正文内容AI识别推荐的背景音乐文件名
     * @param content 正文内容
     * @return 推荐的背景音乐文件名，如果没有则返回null
     */
    suspend fun analyzeContent(content: String, currentBgm: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            io.legado.app.utils.LogUtils.d("BgmAIService", "开始分析内容，内容长度=${content.length}")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: 开始分析内容，长度=${content.length}")
            
            val provider = appDb.bgmAIProviderDao.getEnabled()
            if (provider == null) {
                io.legado.app.utils.LogUtils.d("BgmAIService", "未启用AI提供商")
                io.legado.app.constant.AppLog.put("AI背景音乐: 未启用AI提供商")
                appCtx.toastOnUi("未启用AI提供商")
                return@withContext null
            }
            
            io.legado.app.utils.LogUtils.d("BgmAIService", "AI提供商: ${provider.name}, 模型: ${provider.modelId}")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: AI提供商=${provider.name}, 模型=${provider.modelId}")

            val prompt = getPrompt(content, currentBgm)
            io.legado.app.utils.LogUtils.d("BgmAIService", "提示词长度=${prompt.length}")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: 提示词长度=${prompt.length}")
            
            val result = callOpenAIApi(provider, prompt)
            io.legado.app.utils.LogUtils.d("BgmAIService", "API返回结果: $result")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: API返回=$result")
            
            // 解析AI返回的结果，提取文件名。
            // 候选列表必须与提示词一致（已排除当前播放+最近播放）：如果用全量列表兜底，
            // AI 回复里只要沾到被排除的歌名就又会命中切回去，排除机制等于白做
            val fileName = extractFileName(result, candidateFiles())
            io.legado.app.utils.LogUtils.d("BgmAIService", "提取的文件名: $fileName")
            io.legado.app.constant.AppLog.putDebug("AI背景音乐: 提取文件名=$fileName")
            fileName
        } catch (e: Exception) {
            io.legado.app.utils.LogUtils.d("BgmAIService", "AI分析失败: ${e.message}")
            io.legado.app.constant.AppLog.put("AI背景音乐: 分析异常: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * 参与选曲的候选文件列表：全量列表排除「当前播放 + 最近播放（最多3首）」。
     * getPrompt 与 analyzeContent 的提取兜底共用同一份，保证"提示词里没有的歌，
     * 结果解析也不会命中"。
     */
    private fun candidateFiles(currentBgm: String? = null): List<String> {
        val excluded = mutableSetOf<String>()
        currentBgm?.takeIf { it.isNotBlank() }?.let {
            excluded.add(it.substringBeforeLast(".").trim())
        }
        // 最近播放（关键词路径维护的记录，AI 路径此前完全没接，导致反复推荐同一首）
        io.legado.app.service.BgmKeywordMatcher.getRecentBgms().forEach { excluded.add(it) }
        return BgmManager.getAudioFileList().filter { name ->
            val base = name.substringBeforeLast(".").trim()
            excluded.none { it.equals(base, ignoreCase = true) }
        }
    }

    /**
     * 获取完整的提示词
     * @param currentBgm 当前正在播放的BGM文件名（主线程传入，避免在IO线程访问ExoPlayer）
     */
    private fun getPrompt(content: String, currentBgm: String? = null): String {
        val customPrompt = appDb.bgmAIPromptDao.getDefault()
        val basePrompt = customPrompt?.prompt ?: getDefaultPrompt()

        // 获取实际可用的音频文件列表，排除当前播放与最近播放
        val audioFileList = candidateFiles(currentBgm)

        // 将音频文件列表添加到提示词中
        val audioFilesSection = if (audioFileList.isNotEmpty()) {
            "\n\n可用文件列表（已排除当前播放与最近播放）：\n${audioFileList.joinToString("、")}"
        } else {
            ""
        }

        return "$basePrompt$audioFilesSection\n\n【小说正文】\n$content"
    }

    /**
     * 获取默认提示词
     */
    private fun getDefaultPrompt(): String {
        return """你是小说朗读背景音乐选择器。
你的任务：根据【小说正文】,从【音乐库】中选出1个最合适的文件名。

## 选择逻辑（优先级从高到低）
1. 题材/时代 → 判断是古风、科幻、现代、民国等
2. 场景类型 → 如打斗、日常、转场、独白、探案等
3. 情绪氛围 → 紧张、悲情、温馨、热血、诡异等
4. 用途 → 过场/转场、叙事/回忆、战斗/对峙等
5. 配器/音色 → 仅在前4项相近时用于区分

## 场景-情绪-音乐快速映射
- 过场/场景切换 → 含“过场/转场”的音乐
- 大段旁白/独白/回忆 → 含“叙事/静谧/抒情/回忆”的音乐
- 探案/悬疑/刑侦/反转 → 含“悬疑/案情/诡异/紧张/压迫感”的音乐
- 打斗/对峙/战斗/爆发 → 含“热血/战歌/鼓点/史诗/震撼/压迫感”的音乐
- 离别/伤感/孤独/夜晚/回忆 → 含“悲情/凄凉/静谧/空旷/孤寂/惆怅”的音乐
- 日常/轻松/温馨/治愈 → 含“轻快/清新/文雅/轻缓/温柔”的音乐
- 信息不足时 → 选择最中性、最不冲突的“叙事/静谧/过场”类音乐

## 输出规则

- 只返回一个文件名，不要解释，不要标点，不要额外文字。
请直接返回推荐的文件名。

---

当前可用的背景音乐文件列表如下请根据正文内容直接返回以下文件名其中一个："""
    }

    /**
     * 调用OpenAI格式的API
     *
     * 供 BGM 选曲与本文件的 AI 音效分析共用，故开放为 internal。
     *
     * @param maxTokens 输出 token 上限。**传 null（默认）表示不限制**：不下发 max_tokens 字段，
     * 由模型自行决定输出长度。
     * 推理型模型（DeepSeek-R1、QwQ、Xing4.0 等）会先输出大段思考内容再给结论，
     * 一旦给上限，预算很容易被思考吃光（finish_reason=length、正文为空），
     * 结果连一个文件名/半截 JSON 都拿不到。AI 音效分析一次要返回整批段落的 JSON，
     * 同理不能限制。
     * @param callTimeoutSeconds 单次请求的整体超时（秒，从发起到响应结束的总时限），
     * 可按调用场景调大（如长章节的 AI 分析生成较慢）。
     */
    internal suspend fun callOpenAIApi(
        provider: BgmAIProvider,
        prompt: String,
        maxTokens: Int? = null,
        callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS,
        /** 可选：登记本次 Call，供弹幕可见性闸门在硬停止时取消在途请求 */
        onCallCreated: ((Call) -> Unit)? = null    ): String = withContext(Dispatchers.IO) {
        val url = if (provider.url.endsWith("/")) {
            "${provider.url}chat/completions"
        } else {
            "${provider.url}/chat/completions"
        }

        val requestBody = JSONObject().apply {
            put("model", provider.modelId.ifEmpty { "gpt-3.5-turbo" })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.7)
            // 关闭思考模式：各家服务端认的字段不一样，按朗读脚本的做法整套一起发，
            // 服务端忽略不认识的字段即可（实测 SiliconFlow 只有 thinking.type 生效）。
            if (!AppConfig.bgmThinkingEnabled) {
                put("thinking", JSONObject().apply { put("type", "disabled") })
                put("thinking_mode", false)
                put("disable_think", true)
                put("no_chain_of_thought", true)
                put("do_sample", false)
            }
            // 不限制时整个字段都不下发，避免被服务端默认值截断
            if (maxTokens != null && maxTokens > 0) {
                put("max_tokens", maxTokens)
            }
        }.toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${provider.apiKey}")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // 超时按调用场景调整：非流式请求在服务器生成完毕前没有任何数据返回，
        // readTimeout 会先于 callTimeout 触发，两者需一起调大。
        // newBuilder 共享连接池与线程池，单次请求级配置开销极小
        val callClient = client.newBuilder()
            .readTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        val call = callClient.newCall(request)
        onCallCreated?.invoke(call)
        val response: Response = call.execute()
        
        if (!response.isSuccessful) {
            throw Exception("API调用失败: ${response.code}")
        }

        val responseBody = response.body?.string() ?: throw Exception("响应为空")
        
        // 解析OpenAI格式响应
        val jsonResponse = JSONObject(responseBody)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw Exception("无法解析API响应")
            val content = message.optString("content").orEmpty().trim()
            // 推理型模型把正文写在 reasoning_content，content 是空串。
            // 此时回退取思考内容，避免"有响应却判成空结果"。
            val reasoning = message.optString("reasoning_content").orEmpty().trim()
            content.ifEmpty { reasoning }
        } else {
            throw Exception("无法解析API响应")
        }
    }

    /**
     * 从AI返回的结果中提取文件名。
     *
     * 兼容多种返回形态，尽量别匹配不到：
     * - 推理模型带 `<think>…` 思考块 / `<|...|>` 特殊标记
     * - markdown 代码块与反引号包裹
     * - 序号前缀（1. / 1、/ - / *）与文字前缀（文件名：/ 推荐：/ 答案：/ Answer:）
     * - 引号、书名号《》、方括号、括号等包裹符号
     * - 整段话里夹着文件名（直接在 [candidates] 里命中）
     *
     * @param result AI 返回的原文
     * @param candidates 当前可用的音频文件名；传入时优先在其中命中，命中不了再返回清洗后的文本
     */
    private fun extractFileName(result: String, candidates: List<String> = emptyList()): String? {
        if (result.isBlank()) return null

        // 去掉思考块、特殊标记与 markdown 符号
        val cleaned = result
            .replace(Regex("(?s)<think>.*?</think>"), " ")
            .replace(Regex("(?s)<\\|.*?\\|>"), " ")
            .replace("```", " ")
            .replace("`", "")

        // 优先：原文里只要出现某个可用文件名就直接用它（取最长匹配，避免短名误命中）
        matchInCandidates(cleaned, candidates)?.let { return it }

        // 逐行清洗，从后往前找最像文件名的（模型常在最后一句给结论）
        val lines = cleaned.lines().map { cleanLine(it) }.filter { it.length >= 2 }
        val picked = lines.lastOrNull { isLikelyFileName(it) } ?: lines.lastOrNull()

        // 整段没有换行时按整段清洗
        return (picked ?: cleanLine(cleaned)).takeIf { it.length >= 2 }
    }

    /** 在候选文件名里找被原文包含的那个，取名字最长的匹配项 */
    private fun matchInCandidates(text: String, candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val lower = text.lowercase()
        return candidates
            .filter { it.isNotBlank() }
            .map { it to it.substringBeforeLast(".").trim().lowercase() }
            .filter { (_, name) -> name.length >= 2 && lower.contains(name) }
            .maxByOrNull { (_, name) -> name.length }
            ?.first
    }

    /** 清洗单行：去序号/文字前缀、包裹符号、句末标点与扩展名 */
    private fun cleanLine(line: String): String {
        var s = line.trim()
        // 序号前缀：1. / 1、/ 1) / - / * / •
        s = s.replace(Regex("^(\\d+[.、)]|[\\-*•])\\s*"), "")
        // 文字前缀：文件名：/ 推荐音乐：/ 答案：/ Answer: / filename: ...
        s = s.replace(
            Regex(
                "^(推荐(的)?(背景音乐|音乐|曲目|文件)?|文件名|文件|答案|结果|选择|music|answer|filename|file)\\s*[:：]?\\s*",
                RegexOption.IGNORE_CASE
            ),
            ""
        )
        // 包裹符号与句末标点
        s = s.trim().trim(
            '"', '\'', '`', '《', '》', '〈', '〉', '【', '】',
            '[', ']', '（', '）', '(', ')', '，', ',', '。', '、',
            '！', '!', '？', '?', '；', ';', '：', ':'
        )
        // 去掉扩展名
        s = s.substringBeforeLast(".").trim()
        return s.trim()
    }

    /** 是否像文件名：长度合理且不是一整句解释性文字 */
    private fun isLikelyFileName(s: String): Boolean {
        if (s.length < 2 || s.length > 60) return false
        return !s.contains("。") && !s.contains("，") && !s.contains("！") && !s.contains("？")
    }

    /**
     * 测试AI提供商配置是否有效
     */
    suspend fun testProvider(provider: BgmAIProvider): Boolean = withContext(Dispatchers.IO) {
        try {
            val testPrompt = "请返回文件名：test"
            val result = callOpenAIApi(provider, testPrompt)
            result.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
