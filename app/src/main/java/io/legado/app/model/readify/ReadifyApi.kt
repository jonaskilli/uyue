package io.legado.app.model.readify

import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 悦读（Readify）云书架接口客户端。
 *
 * Base：https://app.readifyai.com
 * 必需头：x-token、x-app-name: readifyai、x-lang: zh-CN
 * 业务成功码：0；200000 = userToken 失效（可续期重试）；200001 = refreshToken 失效（需重新登录）
 *
 * 上传链路：init → (秒传则跳过) → 分块 PUT → commit → 绑定书架
 */
@Suppress("unused")
object ReadifyApi {

    const val BASE = "https://app.readifyai.com"

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
        val cover: String = json.optString("cover").let { if (it == "null") "" else it }
        val progress: Double = json.optDouble("progress", 0.0)
        val lastReadAt: Long = json.optLong("lastReadAt", 0L)
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
        val data = request(
            httpMethod = "POST",
            path = "/api/users/tokens",
            body = JSONObject(),
            extraHeaders = mapOf("x-refresh-token" to refreshToken)
        ).data()

        val token = data.optString("userToken")
        if (token.isBlank()) throw ApiException(-1, "续期未返回 userToken")
        cachedToken = token
        cachedTokenExpireAt = toMillis(data.optString("userTokenExpire", "0").toLongOrNull() ?: 0L)

        // 服务端轮换了 refreshToken，必须回填，否则下次冷启动凭据已作废
        val newRefresh = data.optString("refreshToken")
        if (newRefresh.isNotBlank() && newRefresh != refreshToken) {
            ReadifySession.updateRefreshToken(newRefresh)
        }
        return token
    }

    // ------------------------------------------------------------------ 云书架

    suspend fun getCloudBooks(): List<CloudBook> {
        val json = authorized("GET", "/api/bookshelves/books")
        val array = json.opt("data") as? JSONArray ?: return emptyList()
        val books = mutableListOf<CloudBook>()
        for (i in 0 until array.length()) {
            (array.opt(i) as? JSONObject)?.let { books.add(CloudBook(it)) }
        }
        return books
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

    /** 删除云端书籍：bookId 数组必须放 body 里，放路径参数无效 */
    suspend fun deleteBooks(bookIds: List<String>) {
        val body = JSONArray().apply { bookIds.forEach { put(it) } }
        authorized(
            httpMethod = "DELETE",
            path = "/api/bookshelves/books",
            jsonArrayBody = body
        )
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
        return runCatching {
            request(httpMethod, path, token, body, jsonArrayBody, multipartBody, extraHeaders)
        }.getOrElse { e ->
            if (e is TokenInvalidException) {
                // userToken 过期：作废缓存重新续期后原样重试一次
                cachedToken = null
                request(
                    httpMethod, path, ensureToken(), body, jsonArrayBody, multipartBody, extraHeaders
                )
            } else {
                throw e
            }
        }
    }

    private suspend fun request(
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
            "Origin" to BASE,
            "Referer" to "$BASE/library"
        )
        token?.let { headers["x-token"] = it }
        headers.putAll(extraHeaders)

        val requestBody = when {
            body != null -> body.toString().toRequestBody(jsonType)
            jsonArrayBody != null -> jsonArrayBody.toString().toRequestBody(jsonType)
            else -> multipartBody
        }
        val response = okHttpClient.newCallResponse {
            url(BASE + path)
            method(httpMethod, requestBody)
            addHeaders(headers)
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
