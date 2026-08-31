package io.legado.app.utils

import io.legado.app.constant.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 背景音乐混音工具：把 BGM 铺进"已写好正文的章节 WAV"。
 *
 * 设计要点：
 * - 每条 [Track] 只记录「从章节内容时间轴的哪一毫秒开始播哪首歌」，
 *   它的实际区间一直延续到下一首歌的起点（最后一首到章节结尾），
 *   中间没有轨道的段落自动归前一首管，不需要额外补空。
 * - 歌曲不够长时按 (songOffsetMs + 已铺时长) % 歌曲时长 循环取帧，
 *   跨章节「上一章放了半首、这一章接后半首」也由 songOffsetMs 自然覆盖。
 * - BGM 解码后落到**临时 PCM 文件**而非内存，混音按 1 秒分块顺序读写，
 *   内存峰值始终只有一块（一首 3 分钟立体声 PCM 约 31MB，多首也不能全量驻留）。
 * - 每首歌开头淡入、被下一首替换前淡出、章节结尾淡出，避免硬切爆音。
 */
object BgmChapterMixer {

    private const val TAG = "BgmChapterMixer"

    /** 混音分块时长（毫秒） */
    private const val BLOCK_MS = 1000L
    /** 单首开头淡入时长 */
    private const val FADE_IN_MS = 1500L
    /** 被下一首替换前 / 章节结尾的淡出时长 */
    private const val FADE_OUT_MS = 2000L

    /**
     * 一条背景音乐轨道。
     *
     * @param bgmName BGM 名（不含扩展名）
     * @param startMs 在章节内容时间轴上的起点（毫秒）
     * @param songOffsetMs 这首歌从自己的第几毫秒开始取（跨章接续 / 回跳接续用）
     * @param volume 音量 0..1，一般取 AppConfig.bgmVolume / 100f
     * @param fadeIn 开头是否淡入；跨章接续（上一章已在放）时应为 false
     */
    data class Track(
        val bgmName: String,
        val startMs: Long,
        val songOffsetMs: Long = 0L,
        val volume: Float = 0.3f,
        val fadeIn: Boolean = true
    )

    /** 已解码到磁盘的 BGM 裸 PCM（16-bit 小端，已重采样到正文格式） */
    private class BgmSource(
        val file: File,
        val raf: RandomAccessFile,
        val frames: Int,
        val durationMs: Long
    )

    /**
     * 把背景音乐混入已写好正文的 WAV 文件。
     *
     * @param raf 已以 "rw" 打开的 WAV 文件，正文从偏移 44 字节开始
     * @param sampleRate / channels / baseFrames 正文音频格式与总帧数
     * @param tracks BGM 轨道（无需排序，内部按 startMs 排序）
     * @param fileProvider 按 BGM 名取本地可访问的音频文件
     * @param workDir 解码中间文件目录，混音结束后清理
     * @return true 表示至少混入了一首
     */
    fun mixIntoWav(
        raf: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        baseFrames: Int,
        tracks: List<Track>,
        fileProvider: (String) -> File?,
        workDir: File,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Boolean {
        if (tracks.isEmpty() || baseFrames <= 0 || sampleRate <= 0 || channels <= 0) return false
        val ordered = tracks.sortedBy { it.startMs }
        val sources = LinkedHashMap<String, BgmSource>()
        return try {
            workDir.mkdirs()
            // 1. 逐首解码成 PCM 临时文件（同一首只解码一次）
            ordered.forEach { track ->
                if (sources.containsKey(track.bgmName)) return@forEach
                val srcFile = fileProvider(track.bgmName)
                if (srcFile == null) {
                    AppLog.put("$TAG: 找不到BGM文件 ${track.bgmName}")
                    return@forEach
                }
                val pcm = AudioConcatUtil.decodeToPcm(srcFile)
                if (pcm == null) {
                    AppLog.put("$TAG: BGM解码失败 ${srcFile.name}")
                    return@forEach
                }
                val res = AudioConcatUtil.resamplePcm(pcm, sampleRate, channels)
                val frames = res.bytes.size / (channels * 2)
                if (frames <= 0) return@forEach
                val pcmFile = File(
                    workDir,
                    "bgmpcm_${Integer.toHexString(track.bgmName.hashCode())}.pcm"
                )
                pcmFile.outputStream().use { it.write(res.bytes) }
                sources[track.bgmName] = BgmSource(
                    file = pcmFile,
                    raf = RandomAccessFile(pcmFile, "r"),
                    frames = frames,
                    durationMs = frames * 1000L / sampleRate
                )
            }
            if (sources.isEmpty()) {
                AppLog.put("$TAG: 没有可用的BGM，跳过混音")
                return false
            }

            // 2. 分块混入：读一块正文 -> 叠 BGM -> 写回，内存只留一块
            val frameBytes = channels * 2
            val blockFrames = (BLOCK_MS * sampleRate / 1000L).toInt().coerceAtLeast(1)
            val chapterMs = baseFrames * 1000L / sampleRate
            var offset = 0
            while (offset < baseFrames) {
                val n = minOf(blockFrames, baseFrames - offset)
                val region = ByteArray(n * frameBytes)
                raf.seek(44L + offset.toLong() * frameBytes)
                raf.readFully(region)
                mixBlock(region, offset, n, sampleRate, channels, ordered, sources, chapterMs)
                raf.seek(44L + offset.toLong() * frameBytes)
                raf.write(region)
                offset += n
                progressCallback?.invoke(offset.coerceAtMost(baseFrames), baseFrames)
            }
            AppLog.put("$TAG: BGM混音完成, 轨道${ordered.size}条, 章节${chapterMs}ms")
            true
        } catch (e: Exception) {
            AppLog.put("$TAG: BGM混音失败 ${e.localizedMessage}", e)
            false
        } finally {
            sources.values.forEach { src ->
                try { src.raf.close() } catch (_: Exception) {}
                try { src.file.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * 对单个正文块混入 BGM。
     * 块内按「本块尾 / 下一首起点 / 歌曲回绕点」三者最小切段，
     * 保证每段内源文件读取是顺序的（只在回绕处断开）。
     */
    private fun mixBlock(
        region: ByteArray,
        blockStartFrame: Int,
        n: Int,
        sampleRate: Int,
        channels: Int,
        tracks: List<Track>,
        sources: Map<String, BgmSource>,
        chapterMs: Long
    ) {
        val baseBuf = ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN)
        var f = 0
        while (f < n) {
            val absFrame = blockStartFrame + f
            val tMs = absFrame * 1000L / sampleRate
            val track = trackAt(tracks, tMs)
            if (track == null) {
                // 还没到第一首歌的起点，前面保持原样
                f++
                continue
            }
            val src = sources[track.bgmName]
            if (src == null || src.frames <= 0) {
                f++
                continue
            }

            var endF = n
            val nextStartMs = nextTrackStartMs(tracks, tMs)
            if (nextStartMs != Long.MAX_VALUE) {
                val nf = (nextStartMs * sampleRate / 1000L).toInt() - blockStartFrame
                if (nf > f && nf < endF) endF = nf
            }
            val songPosMs = (track.songOffsetMs + (tMs - track.startMs)) % src.durationMs
            val srcFrame = (songPosMs * sampleRate / 1000L).toInt().coerceIn(0, src.frames - 1)
            val remainFrames = src.frames - srcFrame
            if (remainFrames > 0 && remainFrames < endF - f) {
                endF = f + remainFrames
            }
            val count = (endF - f).coerceAtLeast(1)
            readAndMix(
                src = src,
                srcFrame = srcFrame,
                count = count,
                baseBuf = baseBuf,
                baseStartFrameInBlock = f,
                channels = channels,
                track = track,
                sampleRate = sampleRate,
                startTMs = tMs,
                endMs = minOf(nextStartMs, chapterMs)
            )
            f += count
        }
    }

    /** 顺序读一段源 PCM，逐帧按增益叠加到正文块 */
    private fun readAndMix(
        src: BgmSource,
        srcFrame: Int,
        count: Int,
        baseBuf: ByteBuffer,
        baseStartFrameInBlock: Int,
        channels: Int,
        track: Track,
        sampleRate: Int,
        startTMs: Long,
        endMs: Long
    ) {
        val frameBytes = channels * 2
        val buf = ByteArray(count * frameBytes)
        src.raf.seek(srcFrame.toLong() * frameBytes)
        src.raf.readFully(buf)
        val srcBuf = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val curMs = startTMs + i * 1000L / sampleRate
            var gain = 1f
            val elapsed = curMs - track.startMs
            if (track.fadeIn && elapsed < FADE_IN_MS) {
                gain *= elapsed.toFloat() / FADE_IN_MS
            }
            val remain = endMs - curMs
            if (remain < FADE_OUT_MS) {
                gain *= (remain.toFloat() / FADE_OUT_MS).coerceIn(0f, 1f)
            }
            val g = track.volume * gain
            if (g <= 0f) continue
            for (c in 0 until channels) {
                val idx = (baseStartFrameInBlock + i) * channels + c
                val bv = baseBuf.getShort(idx * 2).toInt()
                val ev = (srcBuf.getShort((i * channels + c) * 2).toInt() * g).toInt()
                baseBuf.putShort(idx * 2, (bv + ev).coerceIn(-32768, 32767).toShort())
            }
        }
    }

    /** 返回 tMs 时刻生效的轨道（startMs <= tMs 的最后一条） */
    private fun trackAt(tracks: List<Track>, tMs: Long): Track? {
        var result: Track? = null
        for (t in tracks) {
            if (t.startMs <= tMs) result = t else break
        }
        return result
    }

    /** 返回 tMs 之后的下一条轨道起点，没有则 Long.MAX_VALUE */
    private fun nextTrackStartMs(tracks: List<Track>, tMs: Long): Long {
        for (t in tracks) {
            if (t.startMs > tMs) return t.startMs
        }
        return Long.MAX_VALUE
    }
}
