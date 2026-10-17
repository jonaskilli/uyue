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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 悦读（Readify）云端书架 —— 接进既有的 RemoteBookManager 抽象，
 * 从而直接复用「远程书籍」浏览界面 RemoteBookActivity（列表/多选/加入书架/恢复/删除）。
 *
 * 与 WebDav 版的差异：
 * - 云书架是**扁平列表**，没有目录层级，path 直接放 bookId，getRemoteBookList 忽略 path 参数
 * - 悦读会自动把 pdf 等格式转换成 epub：有 epub 的书会在列表里多出一条
 *   `bookId#epub` 条目（走 epubUrl 下载），与源文件条目各自独立下载
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

    /** 文件直链 → 字节数；云端列表接口不返回大小，用 HEAD 预取，失败保持 0 */
    private val sizeCache = mutableMapOf<String, Long>()

    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        val books = ReadifyApi.getCloudBooks()
        cloudBooks = books.associateBy { it.bookId }
        // 悦读列表接口不返回文件大小，拉列表时并发 HEAD 预取（失败静默保持 0）
        fetchFileSizes(books)
        val result = mutableListOf<RemoteBook>()
        books.forEach { book ->
            result.add(book.toRemoteBook())
            // 悦读会对 pdf 等格式自动转换出 epub，作为同一本书的另一个条目展示，可单独下载；
            // 源文件本身就是 epub 时不再重复添加
            if (book.hasEpub && !book.fileType.equals("epub", ignoreCase = true)) {
                result.add(book.toEpubRemoteBook())
            }
        }
        return result
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        val book = cloudBooks[bookIdOf(path)]
        if (book != null && (!isEpubPath(path) || book.hasEpub)) {
            return if (isEpubPath(path)) book.toEpubRemoteBook() else book.toRemoteBook()
        }
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
        // epub 条目与源文件条目同属一本书，删除时都归一到真实 bookId
        ReadifyApi.deleteBooks(listOf(bookIdOf(remoteBookUrl)))
    }

    /** 删除前预检（已分享的书不允许删）；解析失败不阻断删除 */
    suspend fun preDeleteCheck(bookIds: List<String>): Set<String> =
        ReadifyApi.preDeleteCheck(bookIds)

    /** 云端重命名：name 传书名，不要带扩展名；epub 条目重命名同样作用于整本书 */
    suspend fun rename(bookId: String, name: String) {
        ReadifyApi.renameBook(bookIdOf(bookId), name)
    }

    /**
     * 触发云端把这本书转换成 EPUB 并等待完成（txt/pdf 等均可）。
     * 服务端异步转换，这里轮询到 epubStatus=2 或拿到 epubUrl 为止；
     * 超时不等于失败——云端可能仍在处理，刷新列表即可看到结果。
     *
     * @return true=本次触发并转换完成；false=云端已有 EPUB，无需重复转换
     */
    suspend fun convertToEpub(path: String): Boolean {
        val bookId = bookIdOf(path)
        val book = cloudBooks[bookId]
            ?: throw NoStackTraceException("书籍信息不存在，请刷新列表后重试")
        if (book.hasEpub) return false
        ReadifyApi.triggerEpubConvert(bookId)
        val deadline = System.currentTimeMillis() + CONVERT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            if (ReadifyApi.getEpubConvertStatus(bookId).isDone) return true
        }
        throw NoStackTraceException(
            "转换超时（${CONVERT_TIMEOUT_MS / 1000} 秒），云端可能仍在处理，请稍后刷新列表查看"
        )
    }

    private suspend fun resolveDownloadUrl(path: String): String {
        val wantEpub = isEpubPath(path)
        val bookId = bookIdOf(path)
        fun urlOf(book: ReadifyApi.CloudBook) =
            (if (wantEpub) book.epubUrl else book.downloadUrl).takeIf { it.isNotBlank() }
        cloudBooks[bookId]?.let { book -> urlOf(book)?.let { return it } }
        // 缓存失效（如界面重建）时重新拉一次列表
        getRemoteBookList("")
        return cloudBooks[bookId]?.let { urlOf(it) }
            ?: throw NoStackTraceException(
                if (wantEpub) "云端 epub 不存在或尚未转换完成，请刷新列表后重试"
                else "云端书籍地址不存在，请刷新列表后重试"
            )
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
            size = sizeCache[downloadUrl] ?: 0L,
            lastModify = lastReadAt,
            contentType = ext,
            isOnBookShelf = LocalBook.isOnBookShelf(name)
        )
    }

    /**
     * 并发 HEAD 预取文件大小（源文件与 epub 各一条直链）。
     * 下载直链是公网可 HEAD 的（返回 Content-Length）；单个链接失败
     * 只让对应条目的大小显示为 0，不影响列表本身。
     */
    private suspend fun fetchFileSizes(books: List<ReadifyApi.CloudBook>) {
        val urls = books.flatMap { listOf(it.downloadUrl, it.epubUrl) }
            .filter { it.isNotBlank() && !sizeCache.containsKey(it) }
            .distinct()
        if (urls.isEmpty()) return
        withContext(Dispatchers.IO) {
            coroutineScope {
                urls.map { url ->
                    async {
                        sizeCache[url] = runCatching { headContentLength(url) }.getOrDefault(0L)
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun headContentLength(url: String): Long =
        okHttpClient.newCallResponse {
            url(url)
            method("HEAD", null)
            addHeaders(
                mapOf(
                    "User-Agent" to PC_UA,
                    "Origin" to ReadifyApi.BASE,
                    "Referer" to "${ReadifyApi.BASE}/library"
                )
            )
        }.use { response ->
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull() ?: 0L
            } else {
                0L
            }
        }

    /**
     * 同一本书的 epub 转换版本。path 加 [EPUB_PATH_SUFFIX] 后缀与源文件条目区分，
     * 下载/删除/重命名时再归一回真实 bookId。
     */
    private fun ReadifyApi.CloudBook.toEpubRemoteBook(): RemoteBook {
        val name = if (title.endsWith(".epub", true)) title else "$title.epub"
        return RemoteBook(
            filename = name,
            path = bookId + EPUB_PATH_SUFFIX,
            size = sizeCache[epubUrl] ?: 0L,
            lastModify = lastReadAt,
            contentType = "epub",
            isOnBookShelf = LocalBook.isOnBookShelf(name)
        )
    }

    companion object {
        /**
         * epub 条目在 path 上的后缀：悦读会对 pdf 等格式自动转换 epub，
         * 列表里同一本书会出现「源文件」和「epub」两个条目，用此后缀区分。
         */
        const val EPUB_PATH_SUFFIX = "#epub"

        /** 列表条目 path → 云端 bookId（去掉 epub 后缀） */
        fun bookIdOf(path: String): String = path.removeSuffix(EPUB_PATH_SUFFIX)

        /** 是否是 epub 条目 */
        fun isEpubPath(path: String): Boolean = path.endsWith(EPUB_PATH_SUFFIX)

        /** 转换轮询间隔与超时（服务端异步转换，实测 txt 约 30 秒内完成） */
        private const val POLL_INTERVAL_MS = 2_000L
        private const val CONVERT_TIMEOUT_MS = 180_000L

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
