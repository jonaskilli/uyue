package io.legado.app.model.readify

import android.net.Uri
import io.legado.app.data.entities.Book
import io.legado.app.help.book.getLocalUri
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.inputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * 本地书籍 → 悦读云端书架的上传编排。
 *
 * 流程：book.getLocalUri()（沿用本地书定位自愈）→ 流式算 md5 与长度 → init → 分块 PUT → commit → 绑定书架。
 * 服务端若已有同 md5 文件，init 直接返回 uploadStatus=2（秒传），不再传任何字节。
 * 注意：**不压缩**。悦读按源文件存储，WebDav 那套 txt→zip 的做法在这里不适用。
 */
@Suppress("unused")
object ReadifySync {

    private const val CHUNK_SIZE = 5 * 1024 * 1024

    /**
     * 把单本本地书籍原样上传到悦读云端书架（**不做 zip 压缩**：悦读存的是源文件，
     * 压缩后云端无法直接按 sourceFileType 识别；这与 WebDav 上传不同）。
     *
     * 服务端若已有相同 md5 的文件，init 会返回 uploadStatus=2 走秒传，不重复传输字节。
     * @param onProgress 进度文案回调，已在主线程执行
     */
    suspend fun uploadBook(
        book: Book,
        onProgress: suspend (String) -> Unit,
    ) {
        if (!NetworkUtils.isAvailable()) {
            throw ReadifyApi.ApiException(-1, "网络不可用")
        }
        // 与上传到 WebDav 同一条定位链（缓存 → SAF令牌 → 保存目录 → 导入目录 → 自愈）
        val uri = withContext(Dispatchers.IO) { book.getLocalUri() }
        val (size, md5) = withContext(Dispatchers.IO) { digest(uri) }
        if (size <= 0L) throw ReadifyApi.ApiException(-1, "本地文件为空")
        uploadOne(uri, book, size, md5, onProgress)
    }

    private suspend fun uploadOne(
        uri: Uri,
        book: Book,
        size: Long,
        md5: String,
        onStep: suspend (String) -> Unit,
    ) {
        val ext = book.originName.substringAfterLast('.', "")
        val totalChunks = ceil(size.toDouble() / CHUNK_SIZE).toInt().coerceAtLeast(1)
        val init = ReadifyApi.initUpload(
            fileName = book.originName,
            ext = ext,
            fileSize = size,
            fileHash = md5,
            chunkSize = CHUNK_SIZE,
            totalChunks = totalChunks
        )
        if (init.isInstant) {
            ReadifyApi.bindBook(listOf(init.uploadId))
            emit(onStep, "云端已有相同文件，秒传完成")
            return
        }
        val startIndex = init.startIndex.coerceIn(0, totalChunks - 1)
        if (startIndex > 0) emit(onStep, "从第 ${startIndex + 1} 块续传")
        val fileId = withContext(Dispatchers.IO) {
            uri.inputStream(appCtx).getOrThrow().buffered().use { input ->
                if (startIndex > 0) input.skip(startIndex.toLong() * CHUNK_SIZE)
                for (index in startIndex until totalChunks) {
                    coroutineContext.ensureActive()
                    val remain = size - index.toLong() * CHUNK_SIZE
                    val need = remain.coerceAtMost(CHUNK_SIZE.toLong()).toInt()
                    val buf = ByteArray(need)
                    readFully(input, buf)
                    ReadifyApi.uploadChunk(init.uploadId, index, buf)
                    emit(onStep, "上传 ${(index + 1) * 100 / totalChunks}%")
                }
            }
            ReadifyApi.commitUpload(init.uploadId, md5)
        }
        ReadifyApi.bindBook(listOf(fileId))
    }

    /** md5 与分块读取都在 IO 线程，进度文案必须切回主线程再回调 UI */
    private suspend fun emit(onProgress: suspend (String) -> Unit, text: String) {
        withContext(Dispatchers.Main) { onProgress(text) }
    }

    /** 一次读完拿到文件长度与 md5（完成一遍流式读取，随后[i]块传输再从头读一遍） */
    private fun digest(uri: Uri): Pair<Long, String> {
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(1 shl 16)
        var size = 0L
        uri.inputStream(appCtx).getOrThrow().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                size += n
            }
        }
        val hex = md.digest().joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        return size to hex
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val n = input.read(buf, offset, buf.size - offset)
            if (n < 0) throw ReadifyApi.ApiException(-1, "本地文件读取中断")
            offset += n
        }
    }
}
