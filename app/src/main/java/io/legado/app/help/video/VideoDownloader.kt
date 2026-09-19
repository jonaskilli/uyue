package io.legado.app.help.video

import android.content.Context
import android.net.Uri
import android.os.Environment
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 视频选集下载器。
 *
 * - mp4 等单文件直链：流式保存（支持 Range 断点续传）
 * - m3u8：逐段下载（支持 AES-128 加密与 EXT-X-MAP 初始化段），按顺序拼接成完整文件
 * - 保存位置：/storage/emulated/0/Download/保存视频/<视频名>/，后缀统一 .mp4
 *
 * 断点续传：m3u8 下载时用 .idx 文件记录播放清单指纹与每段字节数，
 * 中断后重新下载会先校验指纹、截掉写了一半的尾巴，从缺失的片段继续，补齐后合并为 .mp4。
 *
 * 两种入口共用核心 [downloadCore]：
 * - [enqueue]：手动多选下载，队列串行
 * - [watchStart]：边看边存——正在播的选集进队列，播完/切集后由 worker
 *   检测缺失片段补齐再合并，保证最终保存的是完整视频
 */
object VideoDownloader {

    data class Task(
        val book: Book,
        val source: BookSource,
        val index: Int,
        val chapter: BookChapter,
    )

    private data class WatchItem(val task: Task, val knownUrl: String?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channel = Channel<Task>(Channel.UNLIMITED)
    private val watchChannel = Channel<WatchItem>(Channel.UNLIMITED)
    /** 同一来源的请求头，避免每次分段请求都重新构建 */
    private var cachedHeaders: Pair<String, Map<String, String>>? = null
    /** 正在写入的临时文件路径（下载队列与边看边存互斥同一选集） */
    private val activePaths = HashSet<String>()
    /** 已入队待补齐的边看边存任务（去重） */
    private val watchQueued = HashSet<String>()
    private val prefs by lazy {
        appCtx.getSharedPreferences("timetech_video_player", Context.MODE_PRIVATE)
    }

    init {
        scope.launch {
            for (task in channel) {
                runCatching { download(task) }
            }
        }
        // 边看边存 worker：串行补齐缺失片段，完成后合并为 .mp4
        scope.launch {
            for (w in watchChannel) {
                runCatching { downloadCore(w.task, dirOf(w.task), titleOf(w.task), w.knownUrl) }
                    .onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            AppLog.put("边看边存补齐失败：${w.task.chapter.title}", it)
                        }
                    }
                synchronized(watchQueued) { watchQueued.remove(watchKey(w.task)) }
            }
        }
    }

    fun enqueue(tasks: List<Task>) {
        tasks.forEach { channel.trySend(it) }
    }

    fun hasStoragePermission(): Boolean =
        Environment.isExternalStorageManager()

    fun openStoragePermissionSetting() {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${appCtx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(intent)
        }.onFailure {
            AppLog.put("打开存储权限设置失败", it)
        }
    }

    /**
     * 边看边存：把正在播放的选集加入补齐队列。
     * 已完整保存的跳过；同一集去重；worker 会检测缺失片段（断点续传）补齐后合并。
     */
    fun watchStart(task: Task, knownUrl: String? = null) {
        if (!isWatchSaveEnabled()) return
        if (File(dirOf(task), "${titleOf(task)}.mp4").exists()) return
        val key = watchKey(task)
        synchronized(watchQueued) {
            if (!watchQueued.add(key)) return
        }
        watchChannel.trySend(WatchItem(task, knownUrl))
    }

    /**
     * 完整保存策略下无需取消：任务在后台把缺失片段补齐后再合并，保证视频完整。
     * （保留空实现以兼容旧调用点）
     */
    fun watchStop() = Unit

    fun isWatchSaveEnabled(): Boolean =
        prefs.getBoolean("watchSave", false)

    fun setWatchSaveEnabled(on: Boolean) {
        prefs.edit().putBoolean("watchSave", on).apply()
    }

    /** 查找已完整下载的本地缓存（后缀统一 .mp4），命中则免网络直接播放 */
    fun findLocalFile(bookName: String, index: Int, title: String): File? {
        val name = bookName.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "保存视频/$name"
        )
        val t = title.replace(AppPattern.fileNameRegex, "").ifBlank { "第${index + 1}集" }
        return File(dir, "$t.mp4").takeIf { it.exists() && it.length() > 0 }
    }

    private fun dirOf(task: Task): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "保存视频/${task.book.name.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }}"
    )

    private fun titleOf(task: Task): String =
        task.chapter.title.replace(AppPattern.fileNameRegex, "").ifBlank { "第${task.index + 1}集" }

    private fun watchKey(task: Task): String =
        "${dirOf(task).absolutePath}/${titleOf(task)}"

    private suspend fun download(task: Task) {
        val title = titleOf(task)
        appCtx.toastOnUi("开始下载：${task.chapter.title}")
        runCatching { downloadCore(task, dirOf(task), title, knownUrl = null) }
            .onSuccess {
                AppLog.put("下载完成：${it.absolutePath}")
                appCtx.toastOnUi("下载完成：$title")
            }
            .onFailure { e ->
                AppLog.put("下载失败：${task.chapter.title}", e)
                appCtx.toastOnUi("下载失败：${task.chapter.title}\n${e.localizedMessage}")
            }
    }

    /**
     * 下载核心：解析直链 → （m3u8 分段拼接 / 直链流式，均支持断点续传）→ 合并为 <标题>.mp4。
     */
    private suspend fun downloadCore(task: Task, dir: File, title: String, knownUrl: String?): File {
        val out = File(dir, "$title.mp4")
        if (out.exists() && out.length() > 0) return out // 已有完整缓存

        // 1. 直链：优先用已知的（播放中已在播的地址），否则现场解析
        val url = knownUrl?.takeIf { it.startsWith("http") }
            ?: VideoSourcePlayer.resolveChapterUrl(task.book, task.source, task.chapter)
        if (url.isNullOrBlank()) {
            throw IllegalStateException("未获取到直链（网页播放器型源无法下载）")
        }
        val headers = getHeaders(task.source)
        dir.mkdirs()
        val tmp = File(dir, "$title.mp4.part")
        synchronized(activePaths) {
            if (!activePaths.add(tmp.path)) {
                throw IllegalStateException("该选集正在下载中")
            }
        }
        try {
            if (url.contains(".m3u8", true) || url.contains("/m3u8/", true)) {
                downloadHls(url, headers, tmp, resume = true)
            } else {
                downloadDirect(url, headers, tmp, resume = true)
            }
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            File(dir, "$title.mp4.idx").delete()
            return out
        } finally {
            synchronized(activePaths) { activePaths.remove(tmp.path) }
        }
    }

    private fun getHeaders(source: BookSource): Map<String, String> {
        if (cachedHeaders?.first == source.bookSourceUrl) return cachedHeaders!!.second
        val headers = runCatching { source.getHeaderMap(true) }.getOrDefault(emptyMap())
        cachedHeaders = source.bookSourceUrl to headers
        return headers
    }

    /** 单文件直链下载（Range 断点续传） */
    private fun downloadDirect(url: String, headers: Map<String, String>, out: File, resume: Boolean) {
        val existing = if (resume) out.takeIf { it.exists() && it.length() > 0 } else null
        val request = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            existing?.let { header("Range", "bytes=${it.length()}-") }
        }.build()
        okHttpClient.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            // 206 = 从断点继续追加；200 = 服务器不支持断点，重头写
            val append = existing != null && resp.code == 206
            if (existing != null && !append) out.delete()
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(out, append).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("空响应")
        }
    }

    /** m3u8 分段下载并顺序拼接（.idx 记录指纹与每段字节数，支持从缺失片段续传） */
    private fun downloadHls(playlistUrl: String, headers: Map<String, String>, out: File, resume: Boolean) {
        val playlist = fetchText(playlistUrl, headers)
        var keyUri: String? = null
        var ivHex: String? = null
        var mediaSequence = 0L
        var initSegment: String? = null
        val segments = ArrayList<Triple<String, String?, String?>>() // url to (iv, keyUri)

        playlist.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-MAP") ->
                    initSegment = parseAttribute(line, "URI")

                line.startsWith("#EXT-X-KEY") -> {
                    val method = parseAttribute(line, "METHOD")
                    if (method.equals("NONE", true)) {
                        keyUri = null
                        ivHex = null
                    } else {
                        keyUri = parseAttribute(line, "URI")
                        ivHex = parseAttribute(line, "IV")
                    }
                }

                !line.startsWith("#") && line.isNotBlank() ->
                    segments.add(
                        Triple(
                            NetworkUtils.getAbsoluteURL(playlistUrl, line),
                            ivHex,
                            keyUri
                        )
                    )
            }
        }
        check(segments.isNotEmpty()) { "m3u8 中没有分片" }

        val md5 = MessageDigest.getInstance("MD5")
            .digest(playlistUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val idxFile = File(out.parentFile, out.nameWithoutExtension + ".idx")

        // 断点续传：校验 idx 指纹与长度，截掉写了一半的尾巴，从缺失的片段继续
        var resumeOk = false
        var startIndex = 0
        if (resume && idxFile.exists()) {
            val lines = runCatching { idxFile.readLines() }.getOrDefault(emptyList())
            val lens = lines.drop(1).mapNotNull { it.toLongOrNull() }
            val expectInit = if (initSegment != null) 1 else 0
            if (lines.firstOrNull() == md5 && lens.size >= expectInit && lens.sum() <= out.length()) {
                resumeOk = true
                startIndex = lens.size - expectInit
                if (out.length() > lens.sum()) {
                    RandomAccessFile(out, "rw").use { it.setLength(lens.sum()) }
                }
            }
        }
        if (!resumeOk) {
            out.delete()
            idxFile.delete()
            startIndex = 0
        }

        val keyCache = HashMap<String, ByteArray>()
        FileOutputStream(out, resumeOk).use { output ->
            FileOutputStream(idxFile, resumeOk).use { idxOut ->
                if (!resumeOk) {
                    idxOut.write("$md5\n".toByteArray())
                    initSegment?.let { init ->
                        val initUrl = NetworkUtils.getAbsoluteURL(playlistUrl, init)
                        val bytes = fetchBytes(initUrl, headers)
                        output.write(bytes)
                        idxOut.write("${bytes.size}\n".toByteArray())
                    } ?: idxOut.write("0\n".toByteArray())
                    idxOut.flush()
                }
                segments.forEachIndexed { index, (segUrl, segIv, segKeyUri) ->
                    if (index < startIndex) return@forEachIndexed
                    val (body, key) = if (segKeyUri != null) {
                        val keyUrl = NetworkUtils.getAbsoluteURL(playlistUrl, segKeyUri)
                        val k = keyCache.getOrPut(keyUrl) { fetchBytes(keyUrl, headers) }
                        fetchBytes(segUrl, headers) to k
                    } else {
                        fetchBytes(segUrl, headers) to null
                    }
                    if (key != null) {
                        output.write(decryptAes128(body, key, segIv, mediaSequence + index))
                    } else {
                        output.write(body)
                    }
                    idxOut.write("${body.size}\n".toByteArray())
                    idxOut.flush()
                    if ((index + 1) % 20 == 0) {
                        AppLog.putDebug("下载进度 ${index + 1}/${segments.size} 段")
                    }
                }
            }
        }
        // 全部片段补齐：idx 完成使命，由调用方把 .part 合并为 .mp4
        idxFile.delete()
    }

    /** AES-128-CBC 解密；未指定 IV 时按 HLS 规范用分片序号（大端 16 字节） */
    private fun decryptAes128(
        data: ByteArray,
        key: ByteArray,
        ivHex: String?,
        sequence: Long,
    ): ByteArray {
        val iv = if (ivHex != null) {
            val hex = ivHex.removePrefix("0x").removePrefix("0X")
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            ByteBuffer.allocate(16).putLong(sequence).array()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun parseAttribute(line: String, name: String): String? {
        val regex = Regex("""$name=("([^"]*)"|[^,]*)""")
        return regex.find(line)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
            ?: regex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val request = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        okHttpClient.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            return resp.body?.string() ?: ""
        }
    }

    private fun fetchBytes(url: String, headers: Map<String, String>, retry: Int = 2): ByteArray {
        repeat(retry) { attempt ->
            try {
                val request = okhttp3.Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                }.build()
                okHttpClient.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    return resp.body?.bytes() ?: ByteArray(0)
                }
            } catch (e: Exception) {
                if (attempt == retry - 1) throw e
            }
        }
        throw IllegalStateException("unreachable")
    }
}
