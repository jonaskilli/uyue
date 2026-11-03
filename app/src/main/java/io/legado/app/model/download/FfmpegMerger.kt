package io.legado.app.model.download

import com.arthenica.mobileffmpeg.Config
import com.arthenica.mobileffmpeg.FFmpeg
import com.arthenica.mobileffmpeg.Statistics
import io.legado.app.utils.LogUtils
import java.io.File

/**
 * FFmpeg 封装（shiguangyuedu 同款，min 精简包）：
 * 把 m3u8/DASH 下载下来的分片合并（转封装）成单一文件。
 *
 * 优先用 concat 解复用器 + `-c copy`（不重编码，速度极快），
 * 失败时去掉 `aac_adtstoasc` 位流过滤器再试一次。
 *
 * 为什么不直接顺序拼接改名 .mp4：MPEG-TS 是流式容器没有 moov 索引，
 * 拼接产物只是"后缀为 .mp4 的 TS 流"——ExoPlayer 拿不到时长与 seek 索引，
 * 拖动要边播边扫描、起播解码慢。经 FFmpeg `-c copy` 转封装后才是标准 MP4。
 */
object FfmpegMerger {

    private const val TAG = "FfmpegMerger"

    /** 合并为单一文件，成功返回 true */
    fun merge(
        parts: List<File>,
        output: File,
        isFmp4: Boolean = false,
        totalDurationMs: Long = 0L,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        if (parts.isEmpty()) return false
        val dir = output.parentFile ?: return false
        val listFile = File(dir, "concat_${System.currentTimeMillis()}.txt")
        runCatching {
            listFile.writeText(
                parts.joinToString("\n") { "file '${it.absolutePath}'" }
            )
        }.onFailure { return false }

        // fMP4 分片没有 aac_adtstoasc 的问题，直接用 mp4 容器复制
        val commands = if (isFmp4) {
            listOf(
                arrayOf(
                    "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                    "-c", "copy", "-movflags", "+faststart", output.absolutePath
                )
            )
        } else {
            listOf(
                arrayOf(
                    "-y", "-analyzeduration", "15M", "-f", "concat", "-safe", "0",
                    "-i", listFile.absolutePath, "-c", "copy",
                    "-bsf:a", "aac_adtstoasc", output.absolutePath
                ),
                arrayOf(
                    "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                    "-c", "copy", output.absolutePath
                ),
            )
        }

        var result = false
        if (onProgress != null) enableProgress(totalDurationMs, onProgress)
        try {
            for (command in commands) {
                val rc = runCatching { FFmpeg.execute(command) }.getOrElse { -1 }
                LogUtils.d(TAG, "ffmpeg rc=$rc -> ${output.name}")
                if (rc == 0 && output.exists() && output.length() > 0) {
                    result = true
                    break
                }
            }
        } finally {
            if (onProgress != null) Config.enableStatisticsCallback(null)
        }
        listFile.delete()
        return result
    }

    /** 把已拼接好的单文件转封装为 mp4（例如 .ts → .mp4） */
    fun remux(
        input: File,
        output: File,
        totalDurationMs: Long = 0L,
        onProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        if (!input.exists() || input.length() == 0L) return false
        // 带 aac_adtstoasc 位流过滤器（TS 里的 AAC 多为 ADTS 封装，需转成 MP4 的 ASC）；
        // 若音轨不是 AAC（如 AC3），带 bsf 会失败，去掉再试一次。
        val commands = listOf(
            arrayOf(
                "-y", "-analyzeduration", "15M", "-i", input.absolutePath,
                "-c", "copy", "-movflags", "+faststart",
                "-bsf:a", "aac_adtstoasc", output.absolutePath
            ),
            arrayOf(
                "-y", "-analyzeduration", "15M", "-i", input.absolutePath,
                "-c", "copy", "-movflags", "+faststart", output.absolutePath
            )
        )
        var result = false
        if (onProgress != null) enableProgress(totalDurationMs, onProgress)
        try {
            for (command in commands) {
                val rc = runCatching { FFmpeg.execute(command) }.getOrElse { -1 }
                LogUtils.d(TAG, "ffmpeg rc=$rc remux -> ${output.name}")
                if (rc == 0 && output.exists() && output.length() > 0) {
                    result = true
                    break
                }
            }
        } finally {
            if (onProgress != null) Config.enableStatisticsCallback(null)
        }
        return result
    }

    /** FFmpeg 统计回调：按已处理媒体时长换算 0-100 进度 */
    private fun enableProgress(totalDurationMs: Long, onProgress: (Int) -> Unit) {
        Config.enableStatisticsCallback { stats: Statistics ->
            if (totalDurationMs > 0) {
                val p = ((stats.time * 100) / totalDurationMs).toInt().coerceIn(0, 100)
                onProgress(p)
            }
        }
    }

    fun version(): String = runCatching {
        Config.getFFmpegVersion()
    }.getOrDefault("unknown")
}
