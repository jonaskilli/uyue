package io.legado.app.utils

import android.media.MediaMetadataRetriever
import io.legado.app.help.config.AppConfig
import java.io.File
import java.io.RandomAccessFile

/**
 * 朗读缓存音频有效性校验。
 *
 * 背景：TTS 合成失败时可能写出空文件、残缺文件或静音文件并被持久缓存。
 * 缓存命中处此前只判断 exists()，导致下次朗读到这段时播放静音，
 * 表现为「半章内容被快速跳过」。
 *
 * 本类在**命中缓存时**做分层校验，判定为坏缓存则删除并重新合成。
 * 队列内正常朗读时不做任何检测，合成失败就让它播过去，不打断朗读节奏。
 *
 * 检测按开销从小到大分层，前一层通过才进入下一层：
 *   L0 大小   —— 一次 length()，纳秒级
 *   L1 格式与时长 —— 读文件头，微秒级
 *   L2 能量   —— WAV 直接读 PCM，其他格式解码，毫秒级
 *
 * 大小上限为「超过即放行」，因此调大不会误删，只影响覆盖率与性能；
 * 调小不会误删，但会漏检较长的空音频。上限由「超短音频判定阈值」控制，设为 0 关闭检测。
 */
object AudioCacheValidator {

    private const val TAG = "AudioCacheValidator"

    /** WAV 头最小长度，小于此值连文件头都不完整 */
    private const val MIN_WAV_HEADER = 44L

    /** 最短有效时长：低于此值判定为坏缓存（挡的是引擎返回的超短垃圾；
     *  自带静音占位由 [SILENT_WAV_SIZE] 在 L0 短路豁免，走不到这里） */
    private const val MIN_DURATION_MS = 100L

    /** 归一化能量阈值，低于此值视为静音（约 -60dB） */
    private const val SILENCE_RMS_THRESHOLD = 0.001f

    /**
     * 自带静音占位文件的固定大小：generateSilentWavBytes(50)（8kHz·16bit·单声道·50ms
     * = 44 头 + 800 数据）。与 BaseReadAloudService.SILENT_SOUND_SIZE 同源——
     * utils 包不能反向引用 service，故在此复制数值，两处任一改动都要同步。
     *
     * 豁免口径（11-16 用户定稿「判定以文字为准」）：系统 TTS 入口拿得到段文本，
     * 用**文字判据**（不含文字的段静音即合法，见 TTSReadAloudService.synthesizeText）；
     * 本校验器只看得到文件，是给 HTTP 侧兜底的近似口径——844B 恰为纯标点段
     * 自带静音的固定大小，超过此大小的「有文字段垃圾」仍由上层文字判据拦。
     * 844 < 默认 100KB 深检范围，必须在深检前短路放行。
     */
    private const val SILENT_WAV_SIZE = 844L

    /**
     * 判定是否为坏缓存。
     *
     * 检测大小上限复用「超短音频判定阈值」（[AppConfig.shortAudioMinDuration]），
     * 按「值 × 1000」换算为 KB：0.1（默认）→ 100KB 以下，0.3 → 300KB 以下。
     * 设为 0 表示关闭检测，此时一律视为有效缓存、直接放行。
     * 静音占位（SILENT_WAV_SIZE，纯标点段合法静音）短路豁免，不判坏。
     *
     * @return true 表示该缓存应删除并重新合成
     */
    @JvmStatic
    fun isBadCache(file: File): Boolean {
        // ---- L0：大小 ----
        val thresholdKb = AppConfig.shortAudioMinDuration * 1000f
        if (thresholdKb <= 0f) return false         // 设为 0：不检测
        val maxCheckSize = (thresholdKb * 1024f).toLong()

        val size = file.length()
        if (size == 0L) return true                 // 空流写出的文件
        if (size < MIN_WAV_HEADER) return true      // 连 WAV 头都不完整
        if (size == SILENT_WAV_SIZE) return false   // 自带静音占位：合法静音，豁免（见常量注释）
        if (size > maxCheckSize) return false       // 超过上限，正常音频

        // ---- L1：格式与时长 ----
        val durationMs = when (detectFormat(file)) {
            AudioFormat.WAV -> parseWavDurationMs(file, size)
            AudioFormat.MP3, AudioFormat.OGG,
            AudioFormat.FLAC, AudioFormat.M4A -> parseDurationViaRetriever(file)

            AudioFormat.UNKNOWN -> return true      // 不是可识别的音频
        }
        if (durationMs == null || durationMs <= 0) return true  // 文件头损坏
        if (durationMs < MIN_DURATION_MS) return true           // 过短

        // ---- L2：能量 ----
        // 时长正常的静音（外置 TTS 失败时可能返回与文本等长的全零音频）
        // 只能靠能量识别。返回 null 表示解析失败，按坏缓存处理。
        val rms = AudioDecodeUtil.calculateNormalizedRms(readBytes(file)) ?: return true
        return rms < SILENCE_RMS_THRESHOLD
    }

    // ---------------- L1：格式识别与时长 ----------------

    private enum class AudioFormat { WAV, MP3, OGG, FLAC, M4A, UNKNOWN }

    /**
     * 按内容 magic 识别格式。
     * 注意缓存文件扩展名固定为 .mp3，但内容可能是 WAV，因此不能按扩展名判断。
     */
    private fun detectFormat(file: File): AudioFormat {
        val header = readHeader(file, 12) ?: return AudioFormat.UNKNOWN
        if (header.size < 12) return AudioFormat.UNKNOWN

        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF

        return when {
            String(header.copyOfRange(0, 4)) == "RIFF" &&
                    String(header.copyOfRange(8, 12)) == "WAVE" -> AudioFormat.WAV

            String(header.copyOfRange(0, 4)) == "fLaC" -> AudioFormat.FLAC
            String(header.copyOfRange(0, 4)) == "OggS" -> AudioFormat.OGG
            b0 == 0x49 && b1 == 0x44 && b2 == 0x33 -> AudioFormat.MP3       // ID3
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> AudioFormat.MP3          // MPEG sync
            String(header.copyOfRange(4, 8)) == "ftyp" -> AudioFormat.M4A
            else -> AudioFormat.UNKNOWN
        }
    }

    /** 解析 WAV 头计算时长（毫秒） */
    private fun parseWavDurationMs(file: File, size: Long): Long? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                // 只取数据区起始部分即可算出 byteRate
                val head = ByteArray(MIN_WAV_HEADER.toInt())
                raf.readFully(head)

                var pos = 12
                var sampleRate = 0
                var channels = 0
                var bitsPerSample = 0

                while (pos + 8 <= head.size) {
                    val chunkId = String(head.copyOfRange(pos, pos + 4))
                    val chunkSize = readUint32Le(head, pos + 4)
                    pos += 8
                    if (chunkId == "fmt ") {
                        if (pos + 16 <= head.size) {
                            channels = readUint16Le(head, pos + 2)
                            sampleRate = readUint32Le(head, pos + 4)
                            bitsPerSample = readUint16Le(head, pos + 14)
                        }
                        break
                    }
                    pos += chunkSize
                }

                if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
                val byteRate = sampleRate * channels * bitsPerSample / 8
                if (byteRate <= 0) return null
                (size - MIN_WAV_HEADER) * 1000L / byteRate
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 用 MediaMetadataRetriever 读取非 WAV 格式的时长（毫秒） */
    private fun parseDurationViaRetriever(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
            duration?.takeIf { it > 0 }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---------------- 文件读取 ----------------

    /** 读取文件头部若干字节 */
    private fun readHeader(file: File, count: Int): ByteArray? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(count)
                val read = raf.read(buf)
                if (read <= 0) null else buf.copyOfRange(0, read)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 读取完整文件内容。
     * 调用前已由 L0 保证大小不超过检测上限，内存开销可控。
     */
    private fun readBytes(file: File): ByteArray {
        return try {
            file.readBytes()
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    // ---------------- 小端读取 ----------------

    private fun readUint16Le(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUint32Le(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
