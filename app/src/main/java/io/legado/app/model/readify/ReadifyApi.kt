package io.legado.app.model.readify

import cn.hutool.crypto.digest.DigestUtil
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 悦读（Readify）云书架接口客户端。
 *
 * 双域名（同一套前端/同一后端，会话互通）：
 * - `BASE`     = app.readifyai.com      （AWS 美国，原始主站）
 * - `BASE_CN`  = readifyapp.voiceclub.cn （阿里云国内，上传/下载更快）
 *
 * 所有接口按 `API_BASES` 顺序尝试：**国内优先，国外兜底**。
 * 只有「连接层失败」（域名不可达/超时/DNS）才换域名重试；只要拿到了 HTTP 响应
 * （哪怕是业务错误码）就认定请求已到达服务端，不再换域名——避免 bind 这类
 * 非幂等操作被重复执行。
 *
 * 必需头：x-token、x-app-name: readifyai、x-lang: zh-CN
 * 业务成功码：0；200000 = userToken 失效（可续期重试）；200001 = refreshToken 失效（需重新登录）
 *
 * 上传链路：init → (秒传则跳过) → 分块 PUT → commit → 绑定书架
 */
@Suppress("unused")
object ReadifyApi {

    const val BASE = "https://app.readifyai.com"

    /** 国内镜像：同一后端，实测上传/下载带宽明显更好 */
    const val BASE_CN = "https://readifyapp.voiceclub.cn"

    /** API 调用顺序：国内优先，连接失败自动回退国外 */
    private val API_BASES = listOf(BASE_CN, BASE)

    private const val CODE_OK = 0
    const val CODE_TOKEN_INVALID = 200000
    const val CODE_REFRESH_INVALID = 200001

    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    /** 续期互斥锁：服务端每次调用都轮换 refreshToken，并发续期会互相作废 */
    private val renewMutex = Mutex()
    private var cachedToken: String? = null
    private var cachedTokenExpireAt = 0L

    class ApiException(val code: Int, message: String) : Exception(message)

    private class TokenInvalidException(message: String) : Exception(message)

    /** 连接层失败（域名不可达/超时/DNS/SSL），可换域名重试 */
    private class NetworkFailureException(val reason: Throwable) : Exception(reason)

    // ------------------------------------------------------------------ 数据

    /** 云端书架的一本书 */
    class CloudBook(json: JSONObject) {
        val bookId: String = json.optString("bookId")
        val title: String = json.optString("title")
        val author: String = json.optString("author")
        /** 全书文件 md5，本地文件 md5 命中即认为已同步（秒传依据） */
        val md5: String = json.optString("sourceFileMd5")
        val fileType: String = json.optString("sourceFileType")
        /** 无签名的公网下载直链，Range 可用 */
        val downloadUrl: String = json.optString("sourceUrl")
        /**
         * 悦读自动转换出的 epub 直链（同为 /oss-proxy/ 无签名链接）。
         * 云端对 pdf 等格式会做 epub 转换，转换完成后服务端才会填充该字段；
         * 为空（或字符串 "null"）表示没有可下载的 epub。
         */
        val epubUrl: String =
            json.optString("epubUrl").let { if (it == "null") "" else it }
        /** epub 转换状态（官网前端常量）；转换完成后才会有 epubUrl */
        val epubStatus: Int = json.optInt("epubStatus", 0)
        val cover: String = json.optString("cover").let { if (it == "null") "" else it }
        val progress: Double = json.optDouble("progress", 0.0)
        val lastReadAt: Long = json.optLong("lastReadAt", 0L)
        /**
         * 服务端书籍状态。悦读官网前端常量：0=已删除 1=正常 2/3=转换中 4/5=不可见。
         * 删除是**软删除**：记录仍在，deletedAt 被置上且 sourceFileType 被清空。
         */
        val status: Int = json.optInt("status", 1)
        val deletedAt: String = json.optString("deletedAt").takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }.orEmpty()
        /** 与官网列表过滤规则一致：deletedAt 非空，或 status 属于 0/4/5 */
        val isDeleted: Boolean
            get() = deletedAt.isNotBlank() || status == 0 || status == 4 || status == 5

        /** 是否有可下载的 epub（直链非空即转换完成，可作为独立条目展示/下载） */
        val hasEpub: Boolean
            get() = epubUrl.isNotBlank()
    }

    /** 上传初始化结果 */
    class UploadInit(
        val uploadId: String,
        /** 0 = 需要传块；2 = 秒传（服务端已有该文件） */
        val uploadStatus: Int,
        /** 断点续传起点索引（0 起） */
        val startIndex: Int,
    ) {
        val isInstant: Boolean get() = uploadStatus == 2
    }

    // ------------------------------------------------------------------ 账号 / 令牌

    /**
     * 账号密码直接登录（逆向自官网前端 `main-app-*.js` 的 `/users/email/login`）。
     *
     * 官网登录框提交的就是这个接口：密码传 **SHA-256 十六进制串**（无盐、无 RSA、
     * 无验证码），响应 `data` 直接带回 `refreshToken`，与网页登录提取
     * `localStorage.user` 拿到的凭据完全等价——省掉整个 Next.js SPA 的加载
     * （主站 AWS 美国 + 36 个 JS chunk 共 2.8MB，手机上要几十秒；直连接口 1 秒内）。
     *
     * 成功后按网页登录同款格式落盘会话（userId/readerId/nickname/refreshToken），
     * 并顺手缓存响应里的 userToken，省掉一次续期请求。
     *
     * @return 登录账号的显示名（nickname/email）
     */
    suspend fun loginWithPassword(email: String, password: String): String {
        val account = email.trim()
        if (account.isBlank() || password.isEmpty()) {
            throw ApiException(-1, "请输入账号和密码")
        }
        val body = JSONObject().apply {
            put("email", account)
            put("password", DigestUtil.sha256Hex(password))
        }
        var lastError: Throwable? = null
        for (base in API_BASES) {
            try {
                val data = request(
                    base = base,
                    httpMethod = "POST",
                    path = "/api/users/email/login",
                    body = body
                ).data()

                val refreshToken = data.optString("refreshToken")
                if (refreshToken.isBlank()) throw ApiException(-1, "登录未返回 refreshToken")
                // 组装成与网页登录（localStorage.user）同构的会话 JSON，复用同一套存储
                val session = JSONObject().apply {
                    put("userId", data.optString("userId"))
                    put("readerId", data.optString("readerId"))
                    put("nickname", data.optString("nickname"))
                    put("email", account)
                    put("refreshToken", refreshToken)
                }
                if (!ReadifySession.save(session.toString())) {
                    throw ApiException(-1, "会话保存失败")
                }
                // 登录响应已带短期令牌，直接入缓存，后续接口不必立刻续期
                val token = data.optString("userToken")
                if (token.isNotBlank()) {
                    cachedToken = token
                    cachedTokenExpireAt = toMillis(
                        data.optString("userTokenExpire", "0").toLongOrNull() ?: 0L
                    )
                }
                return ReadifySession.displayName.ifBlank { account }
            } catch (e: NetworkFailureException) {
                lastError = e.reason
            } catch (e: CancellationException) {
                throw e
            }
            // 业务错误（账号/密码错误等）与域名无关，直接抛出服务端 message
        }
        throw lastError ?: ApiException(-1, "悦读服务不可用")
    }

    /**
     * 退出登录：清掉内存里的短期令牌缓存 + 清掉本机会话。
     * 不调服务端 `/users/logout`——那会把 refreshToken 在服务端也作废，
     * 影响用户在网页/其他设备上的登录态；本地退出足够。
     */
    fun logout() {
        cachedToken = null
        cachedTokenExpireAt = 0L
        ReadifySession.clear()
    }

    /** 拿到可用的短期 userToken（不够新鲜就续期，续期全程持锁） */
    suspend fun ensureToken(): String {
        freshToken()?.let { return it }
        return renewMutex.withLock {
            // 等锁期间别的调用可能已经续期成功，直接复用新令牌
            freshToken()?.let { return it }
            renewToken()
        }
    }

    private fun freshToken(): String? {
        val token = cachedToken?.takeIf { it.isNotBlank() } ?: return null
        return token.takeIf { System.currentTimeMillis() < cachedTokenExpireAt - 60_000L }
    }

    private suspend fun renewToken(): String {
        val refreshToken = ReadifySession.refreshToken
            ?: throw ApiException(CODE_REFRESH_INVALID, "未登录悦读账号")
        var lastError: Throwable? = null
        for (base in API_BASES) {
            try {
                val data = request(
                    base = base,
                    httpMethod = "POST",
                    path = "/api/users/tokens",
                    body = JSONObject(),
                    extraHeaders = mapOf("x-refresh-token" to refreshToken)
                ).data()

                val token = data.optString("userToken")
                if (token.isBlank()) throw ApiException(-1, "续期未返回 userToken")
                cachedToken = token
                cachedTokenExpireAt =
                    toMillis(data.optString("userTokenExpire", "0").toLongOrNull() ?: 0L)

                // 服务端轮换了 refreshToken，必须回填，否则下次冷启动凭据已作废
                val newRefresh = data.optString("refreshToken")
                if (newRefresh.isNotBlank() && newRefresh != refreshToken) {
                    ReadifySession.updateRefreshToken(newRefresh)
                }
                return token
            } catch (e: NetworkFailureException) {
                lastError = e.reason
            } catch (e: CancellationException) {
                throw e
            }
            // 业务错误（含 200001）与域名无关，直接抛出
        }
        throw lastError ?: ApiException(-1, "悦读服务不可用")
    }

    // ------------------------------------------------------------------ 云书架

    suspend fun getCloudBooks(): List<CloudBook> {
        val json = authorized("GET", "/api/bookshelves/books")
        val array = json.opt("data") as? JSONArray ?: return emptyList()
        val books = mutableListOf<CloudBook>()
        for (i in 0 until array.length()) {
            (array.opt(i) as? JSONObject)?.let { books.add(CloudBook(it)) }
        }
        // 悦读是软删除，官网前端也会过滤掉已删除的书，这里保持一致
        return books.filter { !it.isDeleted }
    }

    // ------------------------------------------------------------------ 上传链路

    suspend fun initUpload(
        fileName: String,
        ext: String,
        fileSize: Long,
        fileHash: String,
        chunkSize: Int,
        totalChunks: Int,
    ): UploadInit {
        val body = JSONObject().apply {
            put("fileName", fileName)
            put("ext", ext)
            put("fileSize", fileSize)
            put("fileHash", fileHash)
            put("chunkSize", chunkSize)
            put("totalChunks", totalChunks)
            put("bizType", "book")
        }
        val data = authorized("POST", "/api/files/uploads/init", body).data()
        val uploadId = data.optString("uploadId")
        if (uploadId.isBlank()) throw ApiException(-1, "上传初始化未返回 uploadId")
        val currentIndex = data.optInt("currentIndex", -1)
        return UploadInit(
            uploadId = uploadId,
            uploadStatus = data.optInt("uploadStatus", 0),
            startIndex = if (currentIndex >= 0) currentIndex + 1 else 0
        )
    }

    /** multipart 字段名固定为 chunk，索引 0 起 */
    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray) {
        val body = MultipartBody.Builder()
            .addFormDataPart("chunk", "chunk-$index", bytes.toRequestBody(null))
            .build()
        authorized(
            httpMethod = "PUT",
            path = "/api/files/uploads/chunk/$uploadId/$index",
            multipartBody = body
        )
    }

    /** 返回 fileId；秒传场景下直接拿 init 的 uploadId 当 fileId 用，不用调这里 */
    suspend fun commitUpload(uploadId: String, finalHash: String): String {
        val body = JSONObject().apply { put("finalHash", finalHash) }
        val data = authorized("POST", "/api/files/uploads/commit/$uploadId", body).data()
        val fileId = data.optString("fileId")
        if (fileId.isBlank()) throw ApiException(-1, "提交上传未返回 fileId")
        return fileId
    }

    suspend fun bindBook(fileIds: List<String>) {
        val body = JSONObject().apply {
            put("fileId", JSONArray().apply { fileIds.forEach { put(it) } })
            put("source", 0)
        }
        authorized("POST", "/api/bookshelves/books", body)
    }

    /**
     * 删除前预检：返回**不允许删除**的 bookId 集合（如已分享的书）。
     * 解析失败或接口异常时返回空集合——预检只是体验优化，不能反过来阻断删除。
     */
    suspend fun preDeleteCheck(bookIds: List<String>): Set<String> {
        if (bookIds.isEmpty()) return emptySet()
        val body = JSONObject().apply {
            put("bookIds", JSONArray().apply { bookIds.forEach { put(it) } })
        }
        val json = runCatching { authorized("POST", "/api/bookshelves/books/pre-check", body) }
            .getOrNull() ?: return emptySet()
        val data = json.optJSONObject("data") ?: return emptySet()
        if (data.optBoolean("canDeleteAll", true)) return emptySet()
        val blocked = mutableSetOf<String>()
        val reasons = data.optJSONArray("cannotDeleteReasons") ?: return emptySet()
        for (i in 0 until reasons.length()) {
            val ids = reasons.optJSONObject(i)?.optJSONArray("bookIds") ?: continue
            for (j in 0 until ids.length()) {
                ids.optString(j).takeIf { it.isNotBlank() }?.let { blocked.add(it) }
            }
        }
        return blocked
    }

    /**
     * 云端重命名（官网端点）。注意服务端存的是「书名」，不带扩展名：
     * 传 "斗罗大陆" 而不是 "斗罗大陆.txt"。
     */
    suspend fun renameBook(bookId: String, name: String) {
        val body = JSONObject().apply {
            put("bookId", bookId)
            put("name", name)
        }
        authorized("POST", "/api/bookshelves/books/rename", body)
    }

    /** 删除云端书籍：bookId 数组必须放 body 里，放路径参数无效 */
    suspend fun deleteBooks(bookIds: List<String>) {
        val body = JSONArray().apply { bookIds.forEach { put(it) } }
        authorized(
            httpMethod = "DELETE",
            path = "/api/bookshelves/books",
            jsonArrayBody = body
        )
    }

    /** epub 转换状态查询结果 */
    class EpubConvertStatus(json: JSONObject) {
        /** 0=未转换 1=转换中 2=转换完成（悦读官网前端同款常量） */
        val epubStatus: Int = json.optInt("epubStatus", 0)
        val epubUrl: String =
            json.optString("epubUrl").let { if (it == "null") "" else it }
        val isDone: Boolean get() = epubStatus == 2 || epubUrl.isNotBlank()
    }

    /**
     * 触发云端把这本书转换成 epub（txt/pdf 等均可，官网阅读器打开书时
     * 自动走的就是这个接口）。服务端**异步**转换，需配合
     * [getEpubConvertStatus] 轮询结果；已转换过的不会重复转换。
     */
    suspend fun triggerEpubConvert(bookId: String) {
        val body = JSONObject().apply { put("bookId", bookId) }
        authorized("POST", "/api/bookshelves/books/toepub", body)
    }

    /** 查询 epub 转换状态/结果 */
    suspend fun getEpubConvertStatus(bookId: String): EpubConvertStatus {
        val data = authorized("GET", "/api/bookshelves/books/toepub/$bookId").data()
        return EpubConvertStatus(data)
    }

    // ------------------------------------------------------------------ 底层请求

    private suspend fun authorized(
        httpMethod: String,
        path: String,
        body: JSONObject? = null,
        jsonArrayBody: JSONArray? = null,
        multipartBody: MultipartBody? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val token = ensureToken()
        var lastError: Throwable? = null
        for (base in API_BASES) {
            try {
                return request(
                    base, httpMethod, path, token, body, jsonArrayBody, multipartBody, extraHeaders
                )
            } catch (e: TokenInvalidException) {
                // userToken 过期：作废缓存重新续期后原样重试一次（换域名无济于事）
                cachedToken = null
                return request(
                    base, httpMethod, path, ensureToken(), body, jsonArrayBody, multipartBody,
                    extraHeaders
                )
            } catch (e: NetworkFailureException) {
                // 连接层失败才换域名；拿到 HTTP 响应的不当成失败，避免 bind 被重复执行
                lastError = e.reason
            } catch (e: CancellationException) {
                throw e
            }
            // 业务错误码直接向上抛
        }
        throw lastError ?: ApiException(-1, "悦读服务不可用")
    }

    private suspend fun request(
        base: String,
        httpMethod: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
        jsonArrayBody: JSONArray? = null,
        multipartBody: MultipartBody? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val headers = mutableMapOf(
            "x-app-name" to "readifyai",
            "x-lang" to "zh-CN",
            "x-user-agent" to "web",
            "Accept" to "application/json",
            "Origin" to base,
            "Referer" to "$base/library"
        )
        token?.let { headers["x-token"] = it }
        headers.putAll(extraHeaders)

        val requestBody = when {
            body != null -> body.toString().toRequestBody(jsonType)
            jsonArrayBody != null -> jsonArrayBody.toString().toRequestBody(jsonType)
            else -> multipartBody
        }
        val response = try {
            okHttpClient.newCallResponse {
                url(base + path)
                method(httpMethod, requestBody)
                addHeaders(headers)
            }
        } catch (e: IOException) {
            // 连接层失败：让上层换域名重试
            throw NetworkFailureException(e)
        }
        val json = runCatching { JSONObject(response.body.string()) }
            .getOrElse { throw ApiException(-1, "返回不是 JSON：$path") }
        val code = json.optInt("code", -1)
        when (code) {
            CODE_OK -> return json
            CODE_TOKEN_INVALID -> throw TokenInvalidException(json.optString("message", "userToken 失效"))
            CODE_REFRESH_INVALID -> throw ApiException(
                CODE_REFRESH_INVALID,
                json.optString("message", "登录已失效，请重新登录悦读账号")
            )
            else -> throw ApiException(code, json.optString("message", "接口返回 $code"))
        }
    }

    private fun JSONObject.data(): JSONObject {
        val data = opt("data") as? JSONObject ?: throw ApiException(-1, "返回缺少 data")
        return data
    }

    /** 服务端过期时间可能是秒也可能是毫秒，统一转毫秒 */
    private fun toMillis(raw: Long): Long = when {
        raw <= 0L -> System.currentTimeMillis() + 30 * 60 * 1000L
        raw < 1_000_000_000_000L -> raw * 1000L
        else -> raw
    }
}
