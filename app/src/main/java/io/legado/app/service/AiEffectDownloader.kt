package io.legado.app.service

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/**
 * 广播剧音效库文件获取：本地优先，缺失时从远端下载并落盘缓存。
 *
 * 远端仓库：`https://cnb.cool/mingwuyan/cnb-chat/-/tree/main/广播剧音效库`
 * 本地目录：[AppConfig.aiEffectLocalDir]，默认
 * `/storage/emulated/0/Download/chajian/广播剧音效库`，目录结构与远端一致。
 */
object AiEffectDownloader {

    private const val TAG = "AiEffectDownloader"

    private const val REMOTE_PREFIX =
        "https://cnb.cool/mingwuyan/cnb-chat/-/git/raw/main/"
    private const val LIBRARY_DIR = "广播剧音效库"

    /** 相对路径 → 本地文件，如 "01_通用母库/拟音/炒菜.mp3" */
    fun localFileOf(relativePath: String): File =
        File(AppConfig.aiEffectLocalDir, relativePath)

    /**
     * 相对路径 → 远端直链。中文路径段逐个 percent-encode，"/" 保留，末尾加 ?download=true。
     */
    fun remoteUrlOf(relativePath: String): String {
        val encoded = Uri.encode("$LIBRARY_DIR/$relativePath", "/")
        return "$REMOTE_PREFIX$encoded?download=true"
    }

    /**
     * 取得可播放的本地文件。
     * 本地已存在直接返回；否则下载后返回；失败返回 null（调用方静默跳过，不阻塞朗读）。
     * 必须在 IO 线程语义下调用（内部已切 Dispatchers.IO）。
     */
    suspend fun obtain(relativePath: String): File? = withContext(Dispatchers.IO) {
        val file = localFileOf(relativePath)
        if (file.exists() && file.length() > 0L) return@withContext file
        try {
            file.parentFile?.mkdirs()
            val request = Request.Builder().url(remoteUrlOf(relativePath)).build()
            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.put("$TAG: 下载失败 HTTP ${resp.code}: $relativePath")
                    return@withContext null
                }
                val body = resp.body
                if (body == null) {
                    AppLog.put("$TAG: 下载响应为空: $relativePath")
                    return@withContext null
                }
                val tmp = File(file.absolutePath + ".tmp")
                try {
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                } finally {
                    resp.close()
                }
                if (tmp.length() == 0L) {
                    tmp.delete()
                    AppLog.put("$TAG: 下载内容为空: $relativePath")
                    return@withContext null
                }
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, true)
                    tmp.delete()
                }
                AppLog.put("$TAG: 下载完成 ${file.absolutePath} (${file.length()} 字节)")
                file
            }
        } catch (e: Exception) {
            AppLog.put("$TAG: 下载异常 $relativePath, ${e.localizedMessage}")
            null
        }
    }
}
