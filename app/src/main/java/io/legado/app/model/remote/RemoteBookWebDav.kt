package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.constant.AppPattern.bookFileRegex
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.book.getLocalUri
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.inputStream
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class RemoteBookWebDav(
    val rootBookUrl: String,
    val authorization: Authorization,
    val serverID: Long? = null
) : RemoteBookManager() {

    init {
        runBlocking {
            WebDav(rootBookUrl, authorization).makeAsDir()
        }
    }


    @Throws(Exception::class)
    override suspend fun getRemoteBookList(path: String): MutableList<RemoteBook> {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val remoteBooks = mutableListOf<RemoteBook>()
        //读取文件列表
        val remoteWebDavFileList: List<WebDavFile> = WebDav(path, authorization).listFiles()
        //转化远程文件信息到本地对象
        remoteWebDavFileList.forEach { webDavFile ->
            if (webDavFile.isDir
                || bookFileRegex.matches(webDavFile.displayName)
                || archiveFileRegex.matches(webDavFile.displayName)
            ) {
                //扩展名符合阅读的格式则认为是书籍
                remoteBooks.add(RemoteBook(webDavFile))
            }
        }
        return remoteBooks
    }

    override suspend fun getRemoteBook(path: String): RemoteBook? {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val webDavFile = WebDav(path, authorization).getWebDavFile()
            ?: return null
        return RemoteBook(webDavFile)
    }

    override suspend fun downloadRemoteBook(remoteBook: RemoteBook): Uri {
        AppConfig.defaultBookTreeUri
            ?: throw NoStackTraceException("没有设置书籍保存位置!")
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        val webdav = WebDav(remoteBook.path, authorization)
        return webdav.downloadInputStream().let { inputStream ->
            // .zip 包装的上传(见 upload)在此流式解出内部书籍文件保存, 本地不落压缩包
            LocalBook.saveRemoteBookFile(inputStream, remoteBook.filename)
        }
    }

    override suspend fun upload(book: Book) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        // 通过 getLocalUri 的完整定位链拿上传源:
        // 缓存 -> SAF令牌校验 -> 书籍保存目录 -> 导入目录 -> OriginPathLocator自愈
        // (候选路径校验 + 令牌明文直译 + MediaStore全盘按文件名[+大小]搜索)。
        // 本地文件被移动/重装备份后SAF授权失效时自动找回原文件, 而不是直接上传失败;
        // 找回命中后 recover 会把命中路径置顶 originPaths 落库, 书的定位信息也被顺带修复。
        val uploadUri = withContext(IO) {
            runCatching { book.getLocalUri() }.getOrElse {
                throw NoStackTraceException(
                    "本地文件丢失且自动定位失败: ${it.localizedMessage ?: "未知错误"}"
                )
            }
        }
        // txt 体积大且压缩率高, 上传前压成 zip 省流量省网盘空间:
        // 斗罗大陆.txt -> 斗罗大陆.txt.zip; 下载端 saveRemoteBookFile 会自动解压还原
        val putUrl: String
        if (book.isLocalTxt) {
            putUrl = "$rootBookUrl${book.originName}.zip"
            val zipCache = File(appCtx.cacheDir, "webdav_upload_${System.currentTimeMillis()}.zip")
            try {
                java.util.zip.ZipOutputStream(
                    java.io.BufferedOutputStream(java.io.FileOutputStream(zipCache))
                ).use { zos ->
                    zos.putNextEntry(java.util.zip.ZipEntry(book.originName))
                    uploadUri.inputStream(appCtx).getOrThrow().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            zos.write(buf, 0, n)
                        }
                    }
                    zos.closeEntry()
                }
                WebDav(putUrl, authorization).upload(zipCache)
            } finally {
                zipCache.delete()
            }
        } else {
            putUrl = "$rootBookUrl${book.originName}"
            val webDav = WebDav(putUrl, authorization)
            if (uploadUri.isContentScheme()) {
                webDav.upload(uploadUri)
            } else {
                webDav.upload(uploadUri.path!!)
            }
        }
        book.origin = BookType.webDavTag + CustomUrl(putUrl)
            .putAttribute("serverID", serverID)
            .toString()
        book.update()
    }

    override suspend fun delete(remoteBookUrl: String) {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        WebDav(remoteBookUrl, authorization).delete()
    }

    /**
     * 移动/重命名远程文件或目录（转发到底层 WebDav MOVE）
     * @param path 源的完整 dav 路径
     * @param destPath 目标的完整 dav 路径
     */
    suspend fun move(path: String, destPath: String, overwrite: Boolean = false): Boolean {
        if (!NetworkUtils.isAvailable()) throw NoStackTraceException("网络不可用")
        return WebDav(path, authorization).move(destPath, overwrite)
    }

}
