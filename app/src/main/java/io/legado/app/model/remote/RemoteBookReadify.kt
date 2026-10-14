package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.readify.ReadifyApi
import io.legado.app.model.readify.ReadifySession
import io.legado.app.model.readify.ReadifySync

/**
 * 悦读（Readify）云端书架 —— 接进既有的 RemoteBookManager 抽象，
 * 从而直接复用「远程书籍」浏览界面 RemoteBookActivity（列表/多选/加入书架/恢复/删除）。
 *
 * 与 WebDav 版的差异：
 * - 云书架是**扁平列表**，没有目录层级，path 直接放 bookId，getRemoteBookList 忽略 path 参数
 * - 下载走 `sourceUrl`（无签名 OSS 直链，支持 Range），用既有的 LocalBook.saveBookFile 落盘导入
 * - 上传不压缩：悦读存源文件（见 ReadifySync.uploadBook）
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
        // 复用既有的在线导入能力：按 URL 流式写入书籍保存目录，返回本地文件 Uri
        return LocalBook.saveBookFile(url, remoteBook.filename)
    }

    override suspend fun upload(book: Book) {
        ReadifySync.uploadBook(book) { }
    }

    override suspend fun delete(remoteBookUrl: String) {
        ReadifyApi.deleteBooks(listOf(remoteBookUrl))
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
        /** 未登录时返回 null，由调用方提示先登录 */
        fun createOrNull(): RemoteBookReadify? =
            if (ReadifySession.isLogin) RemoteBookReadify() else null
    }
}
