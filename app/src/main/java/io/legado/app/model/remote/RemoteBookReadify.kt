package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.readify.ReadifyApi
import io.legado.app.model.readify.ReadifySession
import io.legado.app.model.readify.ReadifySync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 悦读（Readify）云端书架 —— 接进既有的 RemoteBookManager 抽象，
 * 从而直接复用「远程书籍」浏览界面 RemoteBookActivity（列表/多选/加入书架/恢复/删除）。
 *
 * 与 WebDav 版的差异：
 * - 云书架是**扁平列表**，没有目录层级，path 直接放 bookId，getRemoteBookList 忽略 path 参数
 * - 下载优先走国内 `/oss-proxy/` 代理（同一 bucket 透传，无需令牌、支持 Range），
 *   失败自动回退 API 返回的 OSS 原链；上传不压缩（悦读存源文件，见 ReadifySync.uploadBook）
 *
 * 双域名说明：`app.readifyai.com` 与 `readifyapp.voiceclub.cn` 是同一套前端/同一后端
 * （`/api` 相对路径反代），`x-token` 认证与域名无关，会话互通；
 * 仅文件下载走 voiceclub 的代理更快（实测约 1.6 倍且稳定）。
 */
@Suppress("unused")
class RemoteBookReadify : RemoteBookManager() {

    /** bookId → 云书信息（含下载直链）；列表拉取后缓存，供下载/删除复用 */
    private var cloudBooks: Map<String, ReadifyApi.CloudBook> = emptyMap()

    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        val books = ReadifyApi.getCloudBooks()
        cloudBooks = books.associateBy { it.bookId }
        return books.map { it.toRemoteBook() }.toMutableList()
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        cloudBooks[path]?.let { return it.toRemoteBook() }
        return getRemoteBookList("").firstOrNull { it.path == path }
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoStackTraceException("没有设置书籍保存位置!")
        val url = resolveDownloadUrl(remoteBook.path)
        // 优先走国内代理（实测快约 1.6 倍且稳定得多），失败自动回退 OSS 原链
        var lastError: Throwable? = null
        for (downloadUrl in buildDownloadUrls(url)) {
            runCatching { downloadToBookFile(downloadUrl, remoteBook.filename) }
                .onSuccess { return it }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    lastError = e
                }
        }
        throw lastError ?: NoStackTraceException("下载失败")
    }

    /**
     * 生成下载地址候选：国内 /oss-proxy/ 代理优先，原始 OSS 直链兜底。
     * 代理是 nginx 透传到同一个 OSS bucket（响应头带 x-oss-request-id），
     * 无需令牌、支持 Range，失败时回退不影响可用性。
     */
    private fun buildDownloadUrls(sourceUrl: String): List<String> {
        if (sourceUrl.isBlank()) return emptyList()
        val proxy = sourceUrl.replace(OSS_HOST, "$PROXY_HOST/oss-proxy")
        return if (proxy != sourceUrl) listOf(proxy, sourceUrl) else listOf(sourceUrl)
    }

    private suspend fun downloadToBookFile(url: String, fileName: String): Uri =
        withContext(Dispatchers.IO) {
            okHttpClient.newCallResponse {
                url(url)
                addHeaders(
                    mapOf(
                        "User-Agent" to PC_UA,
                        "Origin" to ReadifyApi.BASE,
                        "Referer" to "${ReadifyApi.BASE}/library"
                    )
                )
            }.use { response ->
                if (!response.isSuccessful) {
                    throw NoStackTraceException("下载失败 HTTP ${response.code}")
                }
                LocalBook.saveBookFile(response.body.byteStream(), fileName)
            }
        }

    override suspend fun upload(book: Book) {
        ReadifySync.uploadBook(book) { }
    }

    override suspend fun delete(remoteBookUrl: String) {
        ReadifyApi.deleteBooks(listOf(remoteBookUrl))
    }

    /** 删除前预检（已分享的书不允许删）；解析失败不阻断删除 */
    suspend fun preDeleteCheck(bookIds: List<String>): Set<String> =
        ReadifyApi.preDeleteCheck(bookIds)

    /** 云端重命名：name 传书名，不要带扩展名 */
    suspend fun rename(bookId: String, name: String) {
        ReadifyApi.renameBook(bookId, name)
    }

    private suspend fun resolveDownloadUrl(bookId: String): String {
        cloudBooks[bookId]?.downloadUrl?.takeIf { it.isNotBlank() }?.let { return it }
        // 缓存失效（如界面重建）时重新拉一次列表
        getRemoteBookList("")
        return cloudBooks[bookId]?.downloadUrl?.takeIf { it.isNotBlank() }
            ?: throw NoStackTraceException("云端书籍地址不存在，请刷新列表后重试")
    }

    private fun ReadifyApi.CloudBook.toRemoteBook(): RemoteBook {
        // sourceFileType 为 JSON null 时 optString 会返回字符串 "null"，必须挡掉，
        // 否则书名会被拼成 "xxx.null"（软删除/文件信息被清空时就会出现）
        val ext = fileType.trim().trimStart('.')
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            .orEmpty()
        val name = when {
            ext.isBlank() -> title
            title.endsWith(".$ext", true) -> title
            else -> "$title.$ext"
        }
        return RemoteBook(
            filename = name,
            path = bookId,
            size = 0,
            lastModify = lastReadAt,
            contentType = ext,
            isOnBookShelf = LocalBook.isOnBookShelf(name)
        )
    }

    companion object {
        /** OSS 原始主机（API 返回的 sourceUrl 主机） */
        private const val OSS_HOST = "https://readify-storage-public.oss-us-east-1.aliyuncs.com"

        /** 国内代理主机：nginx 透传到同一个 bucket，实测下载更快更稳 */
        private const val PROXY_HOST = "https://readifyapp.voiceclub.cn"

        /** 下载用的桌面 UA（与登录页一致，避免被 UA 分流） */
        private const val PC_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        /** 未登录时返回 null，由调用方提示先登录 */
        fun createOrNull(): RemoteBookReadify? =
            if (ReadifySession.isLogin) RemoteBookReadify() else null
    }
}
