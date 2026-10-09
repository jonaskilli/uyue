package io.legado.app.help

import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 密钥（miyue）的解析 / 连通性测试 / 模型列表获取。
 *
 * 密钥文件里并不只有一个 API：按脚本侧（朗读规则2.js、朗读脚本代码规范 7.5）的约定，
 * 一份密钥文本用 `@@` 分隔成若干组，每 3 段一组 —— `地址@@模型@@密钥`；
 * 再用 `##` 分成两套场景（姓名分析 / 别名分析）。这里把这套解析在 Kotlin 侧复刻一份，
 * 供「恢复密钥」界面的折叠列表使用。
 *
 * 只做纯逻辑（解析 + 网络），不碰 UI。
 */
object MiyueKeyTester {

    /** 与脚本侧默认配置保持一致 */
    const val DEFAULT_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    const val DEFAULT_MODEL = "glm-4-flash"

    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 15L
    /** 并发上限：密钥多的时候别一次打爆连接池 */
    private const val MAX_CONCURRENT = 5

    // ==================== 解析 ====================

    /**
     * 把一份密钥文本解析成若干个 API。
     *
     * - 有 `##`：前面是姓名分析场景，后面是别名分析场景，两段都解析（标注来源）
     * - 每段内：有 `@@` 按 `地址@@模型@@密钥` 每 3 段一组；没有则整段当纯密钥
     */
    fun parse(content: String): List<ApiEntry> {
        val text = content.trim()
        if (text.isEmpty()) return emptyList()
        val scenes = text.split("##")
        val result = mutableListOf<ApiEntry>()
        scenes.forEachIndexed { index, part ->
            val scene = when {
                scenes.size == 1 -> "通用"
                index == 0 -> "姓名分析"
                index == 1 -> "别名分析"
                else -> "其它${index + 1}"
            }
            result.addAll(parseGroup(part.trim(), scene, index))
        }
        return result
    }

    private fun parseGroup(content: String, scene: String, sceneIndex: Int): List<ApiEntry> {
        if (content.isEmpty()) return emptyList()
        if (!content.contains("@@")) {
            return listOf(
                ApiEntry(DEFAULT_ENDPOINT, DEFAULT_MODEL, content, scene, sceneIndex, 0)
            )
        }
        val arr = content.split("@@")
        val list = mutableListOf<ApiEntry>()
        var i = 0
        var order = 0
        while (i < arr.size) {
            val endpoint = arr[i].trim()
            val model = arr.getOrNull(i + 1)?.trim().orEmpty()
            val key = arr.getOrNull(i + 2)?.trim().orEmpty()
            // 与脚本一致：密钥为空的组不参与
            if (key.isNotEmpty()) {
                list.add(
                    ApiEntry(
                        endpoint = normalizeEndpoint(endpoint),
                        model = model.ifEmpty { DEFAULT_MODEL },
                        key = key,
                        scene = scene,
                        sceneIndex = sceneIndex,
                        indexInScene = order++,
                    )
                )
            }
            i += 3
        }
        return list
    }

    /** 地址规范化：去尾 `/`、去 `/chat/completions` 后缀，再统一补回 `/chat/completions` */
    fun normalizeEndpoint(endpoint: String): String {
        var e = endpoint.trim()
        if (e.isEmpty()) return DEFAULT_ENDPOINT
        if (e.endsWith("/")) e = e.dropLast(1)
        if (e.endsWith("/chat/completions")) e = e.removeSuffix("/chat/completions")
        return "$e/chat/completions"
    }

    // ==================== 测试 ====================

    /**
     * 测一个 API 能不能用：发一条极小请求（max_tokens=1），只看能不能正常返回。
     * 不使用流式、不请求过多 token，尽量少消耗额度。
     */
    suspend fun testOne(entry: ApiEntry): ApiTestResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("model", entry.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "hi")
                })
            })
            put("max_tokens", 1)
        }.toString()
        val request = Request.Builder()
            .url(entry.endpoint)
            .addHeader("Authorization", "Bearer ${entry.key}")
            .addHeader("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val client = okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                val cost = System.currentTimeMillis() - start
                val text = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                resultOf(resp.code, text, cost)
            }
        } catch (e: Exception) {
            ApiTestResult(
                ok = false,
                code = 0,
                message = when {
                    e is java.net.SocketTimeoutException -> "超时"
                    e is java.net.UnknownHostException -> "域名无法解析"
                    e is java.net.ConnectException -> "连接失败"
                    else -> e.message?.take(40) ?: "请求失败"
                },
                costMs = System.currentTimeMillis() - start,
            )
        }
    }

    private fun resultOf(code: Int, text: String, cost: Long): ApiTestResult {
        val brief = text.replace(Regex("\\s+"), " ").take(60)
        return when {
            code == 200 -> ApiTestResult(true, code, "正常", cost)
            code == 401 || code == 403 ->
                ApiTestResult(false, code, "密钥无效/无权限", cost)
            code == 404 ->
                ApiTestResult(false, code, "地址或模型不存在", cost)
            code == 429 ->
                ApiTestResult(false, code, "被限流", cost)
            code >= 500 ->
                ApiTestResult(false, code, "服务端错误", cost)
            else -> ApiTestResult(false, code, "HTTP $code $brief", cost)
        }
    }

    /**
     * 并发测试一批，返回与入参同顺序的结果。
     *
     * @param onProgress 每完成一批回调一次（已完成数 / 总数），供界面显示进度。
     *  回调发生在调用方线程（界面层在主线程调用即可直接刷 UI）。
     */
    suspend fun testAll(
        entries: List<ApiEntry>,
        /** 每批完成后回调：给出本批起始下标与本批结果，界面据此逐个点亮状态点 */
        onBatch: ((startIndex: Int, results: List<ApiTestResult>) -> Unit)? = null,
    ): List<ApiTestResult> = coroutineScope {
        if (entries.isEmpty()) return@coroutineScope emptyList()
        // 分批并发：避免一次性开几十个连接
        val out = ArrayList<ApiTestResult>(entries.size)
        var start = 0
        entries.chunked(MAX_CONCURRENT).forEach { chunk ->
            val rs = chunk.map { async { testOne(it) } }.awaitAll()
            out.addAll(rs)
            onBatch?.invoke(start, rs)
            start += chunk.size
        }
        out
    }

    /**
     * 把指定 API 的模型换成新的，返回新文本。
     *
     * 与 [removeApi] 同样按 [ApiEntry.sceneIndex] / [ApiEntry.indexInScene] 精确定位，
     * 只重写目标所在的那一小段。目前生效的模型就是目标本身时不改动（返回原文本）。
     */
    fun replaceModel(content: String, target: ApiEntry, newModel: String): String {
        val scenes = content.split("##").toMutableList()
        val si = target.sceneIndex
        if (si !in scenes.indices) return content
        val list = parseGroup(scenes[si].trim(), target.scene, si).toMutableList()
        val idx = target.indexInScene
        if (idx !in list.indices) return content
        if (list[idx].model == newModel) return content
        list[idx] = list[idx].copy(model = newModel)
        scenes[si] = list.joinToString("@@") {
            "${it.endpoint}@@${it.model}@@${it.key}"
        }
        return scenes.joinToString("##")
    }

    /**
     * 从密钥文本里剔除指定的那一个 API，返回新文本（其余内容与格式尽量保持原样）。
     *
     * 走 [ApiEntry.sceneIndex] / [ApiEntry.indexInScene] 精确定位，
     * 只重写目标所在的那一小段，`##` 另一侧不受影响。
     */
    fun removeApi(content: String, target: ApiEntry): String {
        val scenes = content.split("##").toMutableList()
        val si = target.sceneIndex
        if (si !in scenes.indices) return content
        val list = parseGroup(scenes[si].trim(), target.scene, si).toMutableList()
        if (target.indexInScene !in list.indices) return content
        list.removeAt(target.indexInScene)
        scenes[si] = if (list.isEmpty()) "" else list.joinToString("@@") {
            "${it.endpoint}@@${it.model}@@${it.key}"
        }
        return scenes.joinToString("##")
    }

    // ==================== 模型列表 ====================

    /**
     * 取该接口上可用的模型 id。
     *
     * 端点形如 `{base}/chat/completions`，去掉尾巴得到 base，再拼 `/models`
     * （OpenAI 的 `.../v1`、智谱的 `.../api/paas/v4` 都适用）。
     * 部分中转站路径不一致，404 时再试一次 `{base}/v1/models`。
     *
     * @return 模型 id 列表；失败时返回错误说明
     */
    suspend fun fetchModels(entry: ApiEntry): ModelsResult = withContext(Dispatchers.IO) {
        val base = entry.endpoint.removeSuffix("/chat/completions")
        val urls = listOf("$base/models", "$base/v1/models")
        val client = okHttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
        val errors = mutableListOf<String>()
        urls.forEach { url ->
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${entry.key}")
                .get()
                .build()
            val text = try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        errors.add("HTTP ${resp.code}")
                        return@use null
                    }
                    resp.body?.string()
                }
            } catch (e: Exception) {
                errors.add(e.message?.take(40) ?: "请求失败")
                null
            }
            val models = text?.let { parseModels(it) }
            if (!models.isNullOrEmpty()) {
                return@withContext ModelsResult(models, null)
            }
        }
        ModelsResult(emptyList(), errors.firstOrNull() ?: "该接口未提供模型列表")
    }

    /** 兼容 {data:[{id:...}]} 与 [{id:...}] / ["id",...] 三种返回 */
    private fun parseModels(text: String): List<String> {
        return try {
            val json = JSONObject(text)
            val arr = json.optJSONArray("data") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id")
                    ?: arr.optString(i)
                if (!id.isNullOrBlank()) out.add(id)
            }
            out.sorted()
        } catch (e: Exception) {
            try {
                val arr = JSONArray(text)
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id") ?: arr.optString(i)
                    if (!id.isNullOrBlank()) out.add(id)
                }
                out.sorted()
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }
}

/** 一份密钥里解析出来的单个 API */
data class ApiEntry(
    val endpoint: String,
    val model: String,
    val key: String,
    /** 来源场景：姓名分析 / 别名分析 / 通用 */
    val scene: String,
    /** 该 API 属于 `##` 分隔后的第几段（用于精确删除） */
    val sceneIndex: Int = 0,
    /** 该 API 在所属段里的序号（用于精确删除） */
    val indexInScene: Int = 0,
) {
    /** 展示用域名：地址普遍很长，只留主机名末尾几段 */
    fun host(): String = try {
        val host = java.net.URI(endpoint).host ?: endpoint
        host.removePrefix("www.")
    } catch (e: Exception) {
        endpoint.take(24)
    }

    /** `{base}`，去掉 /chat/completions 后的前缀，取模型列表用 */
    fun base(): String = endpoint.removeSuffix("/chat/completions")

    /** 脱敏后的密钥，仅用于展示，避免整串密钥出现在界面上 */
    fun keyMask(): String =
        if (key.length <= 8) "****" else key.take(4) + "****" + key.takeLast(4)
}

/** 单个 API 的测试结果 */
data class ApiTestResult(
    val ok: Boolean,
    /** HTTP 状态码；0 表示根本没拿到响应（超时/网络错误） */
    val code: Int,
    val message: String,
    val costMs: Long,
)

/** 模型列表结果 */
data class ModelsResult(
    val models: List<String>,
    val error: String?,
)
