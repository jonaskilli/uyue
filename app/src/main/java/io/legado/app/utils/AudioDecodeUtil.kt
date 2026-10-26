package io.legado.app.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import com.google.common.collect.ImmutableList
import io.legado.app.help.audio.ExoAudioDecoder
import io.legado.app.help.audio.Sonic
import io.legado.app.help.audiobook.PostAudioParams
import io.legado.app.help.config.AppConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频解码与重采样工具。
 * 将任意格式音频（MP3/AAC/WAV/OGG/FLAC 等）解码为 PCM，
 * 重采样为统一参数（24000Hz, 16bit, 单声道），输出标准 WAV 文件。
 */
object AudioDecodeUtil {

    private const val TAG = "AudioDecodeUtil"
    private const val TARGET_SAMPLE_RATE = 24000
    private const val TARGET_CHANNELS = 1
    private const val TARGET_BITS = 16

    /**
     * 快节奏播放（去除静音）参数。
     * 对齐 tts_server_android 的 SynthesizerConfig.silenceSkip*：
     * 在合成阶段、PCM 域用 SilenceSkippingAudioProcessor 裁剪静音段。
     *
     * @param enabled 是否启用
     * @param minDurationMs 最小静音时长（毫秒），低于该时长的停顿不处理，有效范围 150~2000
     */
    data class SilenceSkipConfig(
        val enabled: Boolean,
        val minDurationMs: Int,
    )

    /**
     * 解码完整性阈值：解出的 PCM 时长低于按源音频估算时长的该比例时，
     * 判定为「解码中途截断」，按失败处理。
     *
     * 背景：各解码兜底层（Exo / MediaExtractor / MediaCodec 裸流）碰到中途坏帧时
     * 都是"保留已解码部分、静默结束"，残缺 PCM 会以"完全成功"的姿态返回，
     * 落盘后表现为「只读前几个字就跳下一段」且全程无任何报错。
     * 0.65 的宽容度可吸收 VBR 波动与个别 WAV 头声明值偏大的情况。
     */
    private const val MIN_DECODE_COMPLETENESS_RATIO = 0.65

    /**
     * 校验解码结果是否完整（是否中途截断）。
     *
     * 时长对比基准：源字节数估算时长（MP3 逐帧累加样本数；WAV 用头里声明的
     * data 长度 / byteRate）。无法估算时返回 true 放行（如 OGG/FLAC/AAC）。
     * 不完整时打日志并返回 false，调用方继续下一级兜底或判失败。
     */
    private fun validateDecodedPcm(
        source: ByteArray,
        result: Triple<ByteArray, Int, Int>,
    ): Triple<ByteArray, Int, Int>? {
        val (pcm, sampleRate, channels) = result
        if (sampleRate <= 0 || channels <= 0) return result
        val expectedSec = estimateAudioDurationSec(source) ?: return result
        // decodeViaMediaCodec 返回的是源采样率/声道数的 PCM
        val decodedSec = (pcm.size / 2.0 / channels) / sampleRate
        if (decodedSec >= expectedSec * MIN_DECODE_COMPLETENESS_RATIO) {
            return result
        }
        val msg = "音频解码不完整: 解出 ${"%.1f".format(decodedSec)}s / 预期 ${"%.1f".format(expectedSec)}s " +
                "src=${source.size}B，按截断处理"
        Log.w(TAG, msg)
        io.legado.app.constant.AppLog.putDebug("[AudioDecodeUtil] $msg")
        return null
    }

    /**
     * 估算源音频的总时长（秒）。
     * - WAV：遍历 chunk 取 data 声明长度 / fmt 的 byteRate
     * - MP3：从首个合法帧起逐帧累加样本数（可跨坏帧区继续，VBR 也准确）
     * - 其他格式：返回 null（跳过完整性校验）
     */
    private fun estimateAudioDurationSec(data: ByteArray): Double? {
        return estimateWavDurationSec(data) ?: estimateMp3DurationSec(data)
    }

    private fun estimateWavDurationSec(data: ByteArray): Double? {
        if (data.size < 12 ||
            String(data.copyOfRange(0, 4)) != "RIFF" ||
            String(data.copyOfRange(8, 12)) != "WAVE"
        ) {
            return null
        }
        var pos = 12
        var byteRate = 0
        var dataSize = 0L
        while (pos + 8 <= data.size) {
            val chunkId = String(data.copyOfRange(pos, pos + 4))
            val chunkSize = readUint32Le(data, pos + 4)
            when (chunkId) {
                "fmt " -> {
                    if (pos + 20 <= data.size) {
                        byteRate = readUint32Le(data, pos + 16)
                    }
                }
                "data" -> dataSize = chunkSize.toLong() and 0xFFFFFFFFL
            }
            if (byteRate > 0 && dataSize > 0) break
            pos += 8 + chunkSize + (chunkSize and 1)
        }
        if (byteRate <= 0 || dataSize <= 0) return null
        return dataSize.toDouble() / byteRate
    }

    private fun estimateMp3DurationSec(data: ByteArray): Double? {
        val start = indexOfFirstMp3Frame(data) ?: return null
        var pos = start
        var samples = 0L
        var sampleRate = 0
        while (pos + 4 <= data.size) {
            val info = parseMp3FrameAt(data, pos)
            if (info == null) {
                // 坏帧区：向前扫下一个同步帧（最多 8KB），扫不到当作流尾
                val limit = minOf(data.size - 4, pos + 8192)
                var next = -1
                var scan = pos + 1
                while (scan <= limit) {
                    if (parseMp3FrameAt(data, scan) != null) {
                        next = scan
                        break
                    }
                    scan++
                }
                if (next < 0) break
                pos = next
                continue
            }
            samples += info.samplesPerFrame
            sampleRate = info.sampleRate
            pos += if (info.frameLength > 0) info.frameLength else 1
        }
        if (samples <= 0L || sampleRate <= 0) return null
        return samples.toDouble() / sampleRate
    }

    /**
     * 将任意格式的音频 ByteArray 解码并重采样为标准 WAV PCM。
     *
     * @param audioData 原始音频字节数据
     * @param gain 音量增益，1.0 为原始音量。**只应在「最终封装」那一趟传实际增益**；
     *             中间处理趟必须传 1.0f，否则同一段音频会被反复放大 gain^n 倍导致削波
     * @param postParams 变速变调参数，为 null 时不做变速变调处理
     * @param silenceSkip 快节奏播放（去除静音）参数，不为 null 且 enabled 时在 PCM 域裁剪静音
     * @return 标准 WAV 文件的 ByteArray，失败返回 null
     */
    fun decodeToStandardWav(
        audioData: ByteArray,
        gain: Float = AppConfig.convertCacheToWavGain,
        postParams: PostAudioParams? = null,
        silenceSkip: SilenceSkipConfig? = null,
    ): ByteArray? {
        if (audioData.isEmpty()) {
            return null
        }

        // 先检测是否为 WAV 格式
        val isWav = audioData.size >= 12 &&
                String(audioData.copyOfRange(0, 4)) == "RIFF" &&
                String(audioData.copyOfRange(8, 12)) == "WAVE"

        return try {
            var pcmResult: Triple<ByteArray, Int, Int>? = null
            if (isWav) {
                pcmResult = extractWavPcm(audioData)
                if (pcmResult != null) {
                    // WAV 头声明的数据量远多于实际解出的 PCM（文件被截断）→ 不能当成功
                    pcmResult = validateDecodedPcm(audioData, pcmResult)
                }
                if (pcmResult == null) {
                    pcmResult = decodeViaMediaCodec(audioData)
                }
            } else {
                pcmResult = decodeViaMediaCodec(audioData)
            }

            if (pcmResult == null) {
                return null
            }

            val (pcm, srcRate, srcChannels) = pcmResult

            // 重采样到目标参数
            val resampled = if (srcRate == TARGET_SAMPLE_RATE && srcChannels == TARGET_CHANNELS) {
                pcm
            } else {
                resamplePcm(pcm, srcRate, TARGET_SAMPLE_RATE, srcChannels, TARGET_CHANNELS)
            }

            // Sonic 后处理（语速/音量/音高，三者独立）
            val processed = if (postParams != null && !postParams.isDefault()) {
                applySonicProcessing(resampled, TARGET_SAMPLE_RATE, postParams)
            } else {
                resampled
            }

            // 快节奏播放：在 PCM 域裁剪静音段（对齐 tts_server_android 的 DefaultResultProcessor 流程）
            val skipped = if (silenceSkip != null && silenceSkip.enabled) {
                applySilenceSkip(processed, TARGET_SAMPLE_RATE, silenceSkip.minDurationMs)
            } else {
                processed
            }

            val amplified = if (gain != 1.0f) applyVolumeGain(skipped, gain) else skipped
            writeWavHeaderAndData(amplified, TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_BITS)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 将多段音频统一解码、重采样后合并为一个 WAV 文件。
     *
     * @param segments 待合并的音频段（可能是 MP3/WAV 混合）
     * @param gain 整体音量增益，只在最终封装应用一次
     * @param postParams 所有段共用的后处理参数；段级参数更精确时会被 [perSegmentParams] 覆盖
     * @param perSegmentParams 与 [segments] 等长的逐段后处理参数，元素为 null 表示该段
     *        已处理过、不补做。用于「同一段朗读里不同角色用了不同参数」的场景，
     *        避免用一套参数调所有角色。数量与 [segments] 不一致时自动退回 [postParams]。
     * @param perSegmentGain 与 [segments] 等长的逐段附加音量增益，元素为 null 时按 1.0f。
     *        用于音效段：它不该套角色的语速/音调，但要按「音效音量」滑块调整音量。
     *        与整体 [gain] 是相乘关系（两个设置叠加）。
     * @param silenceSkip 快节奏播放（去除静音）参数
     */
    fun mergeSegmentsToWav(
        segments: List<ByteArray>,
        gain: Float = AppConfig.convertCacheToWavGain,
        postParams: PostAudioParams? = null,
        perSegmentParams: List<PostAudioParams?>? = null,
        perSegmentGain: List<Float?>? = null,
        silenceSkip: SilenceSkipConfig? = null,
    ): ByteArray? {
        if (segments.isEmpty()) {
            return null
        }

        // 逐段参数不可信（数量不符）时退回整体参数，避免张冠李戴
        val perSegment = perSegmentParams?.takeIf { it.size == segments.size }
        val perGain = perSegmentGain?.takeIf { it.size == segments.size }

        val allPcm = mutableListOf<ByteArray>()
        try {
            segments.forEachIndexed { index, segment ->
                val segParams = perSegment?.get(index) ?: postParams
                // 逐段附加增益（音效段非 1.0）。这里只乘段级增益，
                // 整体 gain 仍由下方合并后统一应用一次，两者相乘不重复
                val segGain = perGain?.get(index) ?: 1.0f
                // 每一段合成后立即去除空音频（快节奏），所有处理在合并之前完成。
                // gain 传 segGain：逐段解码属于中间趟，整体增益由下方合并后应用
                val wavBytes = decodeToStandardWav(
                    segment,
                    gain = segGain,
                    postParams = segParams,
                    silenceSkip = silenceSkip,
                )
                if (wavBytes == null) {
                    return@forEachIndexed
                }
                // 去掉 WAV 头，取 PCM 数据
                val pcm = extractPcmFromWav(wavBytes)
                if (pcm != null) {
                    allPcm.add(pcm)
                }
            }

            if (allPcm.isEmpty()) {
                return null
            }

            val totalSize = allPcm.sumOf { it.size }
            val mergedPcm = ByteArray(totalSize)
            var offset = 0
            allPcm.forEach {
                System.arraycopy(it, 0, mergedPcm, offset, it.size)
                offset += it.size
            }

            // Sonic 后处理（语速/音量/音高，三者独立）
            val processed = if (postParams != null && !postParams.isDefault()) {
                applySonicProcessing(mergedPcm, TARGET_SAMPLE_RATE, postParams)
            } else {
                mergedPcm
            }

            val amplified = if (gain != 1.0f) applyVolumeGain(processed, gain) else processed
            return writeWavHeaderAndData(amplified, TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_BITS)
        } catch (e: Exception) {
            return null
        }
    }

    // ========== 内部方法 ==========

    /**
     * 对 16bit 小端 PCM 数据应用音量增益。
     * 每个采样点乘以 gain，并裁剪到 [-32768, 32767] 防止爆音。
     * gain 为 NaN 或 1.0 时不做处理直接返回原数据。
     */
    private fun applyVolumeGain(pcm: ByteArray, gain: Float): ByteArray {
        if (gain.isNaN() || gain == 1.0f) return pcm
        val effectiveGain = gain.coerceAtLeast(0.0f)
        if (effectiveGain == 0.0f) {
            // 静音
            return ByteArray(pcm.size)
        }
        val out = ByteArray(pcm.size)
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            var sample = lo or (hi shl 8)
            if (sample > 32767) sample -= 65536
            val amplified = (sample * effectiveGain).toInt().coerceIn(-32768, 32767)
            out[i] = (amplified and 0xFF).toByte()
            out[i + 1] = ((amplified shr 8) and 0xFF).toByte()
            i += 2
        }
        return out
    }

    /**
     * 使用 Sonic 算法对 PCM 进行后处理（语速/音量/音高三者独立调节）。
     * 输入和输出都是 16bit 小端 PCM，单声道。
     */
    private fun applySonicProcessing(pcm: ByteArray, sampleRate: Int, params: PostAudioParams): ByteArray {
        val resolved = params.resolved()
        if (resolved.isDefault()) return pcm

        val numSamples = pcm.size / 2
        val sonic = Sonic(sampleRate, TARGET_CHANNELS)
        sonic.setSpeed(resolved.speed)
        sonic.setVolume(resolved.volume)
        sonic.setPitch(resolved.pitch)
        sonic.setRate(1.0f)

        // 输入 short 数组
        val inputSamples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            var s = lo or (hi shl 8)
            if (s > 32767) s -= 65536
            inputSamples[i] = s.toShort()
        }

        sonic.writeShortToStream(inputSamples, numSamples)
        sonic.flushStream()

        // 输出
        val maxOutSamples = (numSamples / resolved.speed.coerceAtLeast(0.01f)).toInt() + 1024
        val outputSamples = ShortArray(maxOutSamples)
        val outLen = sonic.readShortFromStream(outputSamples, maxOutSamples)

        val result = ByteArray(outLen * 2)
        for (i in 0 until outLen) {
            val s = outputSamples[i].toInt()
            result[i * 2] = (s and 0xFF).toByte()
            result[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return result
    }

    /**
     * 在 PCM 域裁剪静音段，实现「快节奏播放」（去除静音）。
     * 原样复刻 tts_server_android 的 DefaultResultProcessor：
     * 用 AudioProcessingPipeline 驱动 SilenceSkippingAudioProcessor，
     * 参数只透传最小静音时长，其余全部用 Media3 默认值，不擅自调整。
     *
     * @param pcm 16bit 小端 PCM，单声道
     * @param sampleRate 真实采样率（此处已统一为 TARGET_SAMPLE_RATE）
     * @param minDurationMs 最小静音时长（毫秒），对应原版 silenceSkipMinDurationMs
     * @return 裁剪后的 PCM（仍为 16bit 小端、单声道）
     */
    @OptIn(UnstableApi::class)
    private fun applySilenceSkip(pcm: ByteArray, sampleRate: Int, minDurationMs: Int): ByteArray {
        if (pcm.isEmpty()) return pcm
        try {
            // 仅调整最小静音时长，其余参数保持 Media3 默认值（原样复刻）
            val skipProcessor = SilenceSkippingAudioProcessor(
                minDurationMs * 1000L,
                SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
                SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
                SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
                SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL,
            )
            skipProcessor.setEnabled(true)

            val pipelines = ImmutableList.of<AudioProcessor>(skipProcessor)
            val pipeline = AudioProcessingPipeline(pipelines)
            pipeline.configure(
                AudioProcessor.AudioFormat(
                    sampleRate,
                    TARGET_CHANNELS,
                    C.ENCODING_PCM_16BIT,
                )
            )
            pipeline.flush()

            val out = ByteArrayOutputStream(pcm.size)
            val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            // 分批喂入并取输出（原版写法）
            while (input.hasRemaining()) {
                pipeline.queueInput(input)
                pipeline.output?.let { buf ->
                    val len = buf.remaining()
                    if (len > 0) {
                        val arr = ByteArray(len)
                        buf.get(arr)
                        out.write(arr)
                    }
                }
            }
            // 结束流并取剩余输出
            pipeline.queueEndOfStream()
            pipeline.output?.let { buf ->
                val len = buf.remaining()
                if (len > 0) {
                    val arr = ByteArray(len)
                    buf.get(arr)
                    out.write(arr)
                }
            }
            return out.toByteArray()
        } catch (e: Exception) {
            return pcm
        }
    }

    /**
     * 计算音频数据的归一化能量（RMS，0.0~1.0），用于静音/空音频检测。
     *
     * 只做「提取 PCM + 平方均值」，不重采样、不做增益与后处理，
     * 因此比 [decodeToStandardWav] 轻得多，可安全用于缓存命中时的逐段校验。
     *
     * WAV 直接读 PCM 数据（无需解码）；其他格式先经 MediaCodec 解码。
     * 采样时按 [step] 间隔抽样，大文件也不必遍历全部采样点。
     *
     * @return 归一化 RMS；样本为空或解码失败时返回 null
     */
    @Suppress("unused")
    fun calculateNormalizedRms(audioData: ByteArray, step: Int = 8): Float? {
        if (audioData.isEmpty()) return null
        val isWav = audioData.size >= 12 &&
                String(audioData.copyOfRange(0, 4)) == "RIFF" &&
                String(audioData.copyOfRange(8, 12)) == "WAVE"

        // WAV 直接读 PCM 失败时（非常规 chunk 布局）回退到解码
        val pcmResult = try {
            if (isWav) {
                extractWavPcm(audioData) ?: decodeViaMediaCodec(audioData)
            } else {
                decodeViaMediaCodec(audioData)
            }
        } catch (e: Exception) {
            null
        } ?: return null

        val (pcm, _, channels) = pcmResult
        if (pcm.size < 2 || channels <= 0) return null

        val frameSize = channels * 2
        val frames = pcm.size / frameSize
        if (frames <= 0) return null

        // 抽样遍历：每 step 帧取一帧，降低大文件的检测开销
        var sumSquares = 0.0
        var count = 0
        var frame = 0
        while (frame < frames) {
            val base = frame * frameSize
            // 各通道取平均，得到该帧的单声道幅度
            var sample = 0.0
            for (ch in 0 until channels) {
                val idx = base + ch * 2
                if (idx + 1 >= pcm.size) break
                val lo = pcm[idx].toInt() and 0xFF
                val hi = pcm[idx + 1].toInt()
                val raw = lo or (hi shl 8)
                sample += if (raw > 32767) (raw - 65536).toDouble() else raw.toDouble()
            }
            sample /= channels
            val normalized = sample / 32768.0
            sumSquares += normalized * normalized
            count++
            frame += step
        }

        if (count == 0) return null
        return kotlin.math.sqrt(sumSquares / count).toFloat()
    }

    /** 从 WAV ByteArray 中提取 PCM 数据 */
    private fun extractWavPcm(data: ByteArray): Triple<ByteArray, Int, Int>? {
        return try {
            val riff = String(data.copyOfRange(0, 4))
            val wave = String(data.copyOfRange(8, 12))
            if (riff != "RIFF" || wave != "WAVE") return null

            var pos = 12
            var sampleRate = 0
            var numChannels = 0
            var bitsPerSample = 0
            var dataOffset = 0
            var dataSize = 0
            var audioFormat = 1
            var fmtChunkSize = 0

            while (pos < data.size - 8) {
                val chunkId = String(data.copyOfRange(pos, pos + 4))
                val chunkSize = readUint32Le(data, pos + 4)
                pos += 8

                when (chunkId) {
                    "fmt " -> {
                        fmtChunkSize = chunkSize
                        if (pos + 16 <= data.size) {
                            audioFormat = readUint16Le(data, pos)
                            numChannels = readUint16Le(data, pos + 2)
                            sampleRate = readUint32Le(data, pos + 4)
                            bitsPerSample = readUint16Le(data, pos + 14)
                        }
                    }
                    "data" -> {
                        dataOffset = pos
                        dataSize = chunkSize
                        break
                    }
                }
                // WAV chunk 数据长度必须是偶数，奇数时补一个 pad 字节
                pos += chunkSize + (chunkSize and 1)
            }

            if (sampleRate == 0 || dataSize == 0 || dataOffset == 0) {
                return null
            }

            // dataSize 可能大于实际数据（某些 WAV 生成工具声明值偏大），取实际可用大小
            val actualSize = dataSize.coerceAtMost(data.size - dataOffset)
            if (actualSize <= 0) {
                return null
            }

            // 【关键修复】支持 WAVEFORMATEXTENSIBLE (格式码 0xFFFE)
            // 很多现代 TTS 引擎返回的是 EXTENSIBLE 格式
            val isFloat: Boolean
            val effectiveFormat: Int
            if (audioFormat == 0xFFFE && fmtChunkSize >= 40 && pos >= 40 + 12) {
                // 读取 SubFormat GUID 的前 4 字节（小端）
                val guidLo = readUint32Le(data, 12 + 8 + 24) // fmt chunk 内偏移 24
                effectiveFormat = guidLo
                isFloat = (guidLo == 3)
            } else {
                effectiveFormat = audioFormat
                isFloat = audioFormat == 3
            }

            // 支持 PCM (1)、float (3)、EXTENSIBLE(底层为PCM/float)；其他回退到 MediaCodec
            if (effectiveFormat != 1 && effectiveFormat != 3) {
                return null
            }

            val pcmData = when {
                bitsPerSample == 8 -> {
                    val pcm8 = data.copyOfRange(dataOffset, dataOffset + actualSize)
                    val pcm16 = ByteArray(pcm8.size * 2)
                    for (i in pcm8.indices) {
                        val sample = ((pcm8[i].toInt() and 0xFF) - 128) shl 8
                        pcm16[i * 2] = (sample and 0xFF).toByte()
                        pcm16[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
                    }
                    pcm16
                }
                bitsPerSample == 16 && !isFloat -> {
                    data.copyOfRange(dataOffset, dataOffset + actualSize)
                }
                bitsPerSample == 24 -> {
                    val pcm24 = data.copyOfRange(dataOffset, dataOffset + actualSize)
                    val samples = pcm24.size / 3
                    val pcm16 = ByteArray(samples * 2)
                    for (i in 0 until samples) {
                        val b0 = pcm24[i * 3].toInt() and 0xFF
                        val b1 = pcm24[i * 3 + 1].toInt() and 0xFF
                        val b2 = pcm24[i * 3 + 2].toInt() and 0xFF
                        val value = b0 or (b1 shl 8) or (b2 shl 16)
                        val signed = if (value > 0x7FFFFF) value - 0x1000000 else value
                        val scaled = (signed shr 8).coerceIn(-32768, 32767)
                        pcm16[i * 2] = (scaled and 0xFF).toByte()
                        pcm16[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }
                    pcm16
                }
                bitsPerSample == 32 -> {
                    val pcm32 = data.copyOfRange(dataOffset, dataOffset + actualSize)
                    val samples = pcm32.size / 4
                    val pcm16 = ByteArray(samples * 2)
                    val bb = java.nio.ByteBuffer.wrap(pcm32).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until samples) {
                        val intBits = bb.getInt(i * 4)
                        val intVal = if (isFloat) {
                            (java.lang.Float.intBitsToFloat(intBits) * 32767f).toInt()
                        } else {
                            (intBits shr 16).coerceIn(-32768, 32767)
                        }
                        val scaled = intVal.coerceIn(-32768, 32767)
                        pcm16[i * 2] = (scaled and 0xFF).toByte()
                        pcm16[i * 2 + 1] = ((scaled shr 8) and 0xFF).toByte()
                    }
                    pcm16
                }
                else -> {
                    return null
                }
            }

            Triple(pcmData, sampleRate, numChannels)
        } catch (e: Exception) {
            null
        }
    }

    /** 使用 MediaExtractor + MediaCodec 解码任意格式音频 */
    private fun decodeViaMediaCodec(audioData: ByteArray): Triple<ByteArray, Int, Int>? {
        // ★ 第一优先：ExoPlayer（media3 自带 extractor：纯 Java、打包在 APK 内）解码。
        // 平台 MediaExtractor 的 MP3 解析由 OEM 维护、随 Android 版本/ROM 变化，
        // 对 ID3 标签尤其挑剔，是「时好时坏」的根源；media3 跨设备行为一致，对
        // ID3/裸帧/VBR 容忍度高——移植自 TTS_Server_Android，它的合成转码从不
        // 失败就是因为用了这套。失败才回退下面的兜底路径。
        try {
            ExoAudioDecoder.decode(audioData)?.let { return validateDecodedPcm(audioData, it) }
        } catch (e: Exception) {
            // Exo 解码异常：静默进入兜底（合成不中断优先）
        }

        // ★ 兜底一：净化输入后重试 Exo。
        // 偶发失败多由头部垃圾引起——ID3 残留、分块拼接留下的残帧。
        // 剥干净再喂，media3 的容错解析通常就能通过。
        // 这一趟不能省：一旦这里放弃，后续的 Sonic 音量/语速调节就整段失效
        // （原始 MP3 拿不到 PCM，无法后处理），表现为「调了参数偶尔有一句没反应」。
        sanitizeAudioInput(audioData)?.let { cleaned ->
            try {
                ExoAudioDecoder.decode(cleaned)?.let { return validateDecodedPcm(audioData, it) }
            } catch (e: Exception) {
                // 同样静默进入下一级兜底
            }
        }

        var data = audioData
        var ext = detectAudioExtension(audioData)
        // MP3 裸流（含头部带垃圾/扩展名未识别的）先对齐到第一个合法帧头：
        // MediaExtractor 对首帧极敏感，分块拼接或截断产生的头部残帧会让整个解析失败，
        // 表现为「时好时坏」——流完整就能解，头部带残帧就整体失败返回 null。
        if (ext == ".mp3" || ext == ".tmp") {
            val aligned = alignToFirstMp3Frame(data)
            if (aligned != null) {
                data = aligned
                ext = ".mp3"
            }
        }
        val tempFile = File.createTempFile("audio_decode", ext)
        tempFile.writeBytes(data)
        try {
            decodeFileViaMediaCodec(tempFile)?.let { return validateDecodedPcm(audioData, it) }
        } finally {
            tempFile.delete()
        }

        // ★ 兜底二：不经 MediaExtractor，把 MP3 裸流直接喂给 MediaCodec。
        // MediaExtractor 解析失败（封装非标/无 track）时，MediaCodec 本身只认帧，
        // 直喂整块数据往往仍能解出 PCM。
        return decodeRawMp3ViaMediaCodec(audioData)?.let { validateDecodedPcm(audioData, it) }
    }

    /**
     * 为兜底重试准备「干净」的输入：
     * - 剥掉 ID3v2 头（`ID3` + 版本 + size，size 为 synchsafe integer）
     * - 剥掉尾部 ID3v1（末 128 字节 `TAG`）
     * - MP3 裸流对齐到第一个合法帧头
     *
     * @return 净化后的数据；无需处理时返回 null
     */
    private fun sanitizeAudioInput(data: ByteArray): ByteArray? {
        if (data.size < 16) return null
        var bytes = data
        var changed = false

        // ID3v2 头
        if (data.size >= 10 &&
            data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()
        ) {
            val size = ((data[6].toInt() and 0x7F) shl 21) or
                ((data[7].toInt() and 0x7F) shl 14) or
                ((data[8].toInt() and 0x7F) shl 7) or
                (data[9].toInt() and 0x7F)
            val end = 10 + size
            if (end in 1 until data.size) {
                bytes = data.copyOfRange(end, data.size)
                changed = true
            }
        }

        // 尾部 ID3v1
        if (bytes.size > 128 &&
            bytes[bytes.size - 128] == 'T'.code.toByte() &&
            bytes[bytes.size - 127] == 'A'.code.toByte() &&
            bytes[bytes.size - 126] == 'G'.code.toByte()
        ) {
            bytes = bytes.copyOfRange(0, bytes.size - 128)
            changed = true
        }

        // MP3 首帧对齐
        alignToFirstMp3Frame(bytes)?.let {
            bytes = it
            changed = true
        }

        return if (changed) bytes else null
    }

    /** MPEG 音频帧关键信息（用于 MediaCodec 裸流直解） */
    private data class Mp3FrameInfo(
        val sampleRate: Int,
        val channels: Int,
        /** 帧长度（字节）；无法可靠计算时为 0 */
        val frameLength: Int,
        /** 每帧采样点数，用于按时长估算解码完整性 */
        val samplesPerFrame: Int,
    )

    // MPEG-1 Layer I/II/III 与 MPEG-2/2.5 的比特率表（kbps）
    private val MPEG1_BITRATES =
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_BITRATES =
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val MPEG1_RATES = intArrayOf(44100, 48000, 32000)
    private val MPEG2_RATES = intArrayOf(22050, 24000, 16000)
    private val MPEG25_RATES = intArrayOf(11025, 12000, 8000)

    /** 解析位置 i 处的 MPEG 音频帧头；不是合法帧头时返回 null */
    private fun parseMp3FrameAt(data: ByteArray, i: Int): Mp3FrameInfo? {
        if (i < 0 || i + 4 > data.size) return null
        val b0 = data[i].toInt() and 0xFF
        val b1 = data[i + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val versionBits = (b1 shr 3) and 0x03
        if (versionBits == 1) return null          // 版本保留位
        val layerBits = (b1 shr 1) and 0x03
        if (layerBits == 0) return null            // 层保留位
        val b2 = data[i + 2].toInt() and 0xFF
        val bitrateIdx = (b2 shr 4) and 0x0F
        if (bitrateIdx == 0 || bitrateIdx == 15) return null   // 比特率 free/损坏
        val rateIdx = (b2 shr 2) and 0x03
        if (rateIdx == 3) return null              // 采样率保留位
        val padding = (b2 shr 1) and 0x01

        val sampleRate = when (versionBits) {
            3 -> MPEG1_RATES[rateIdx]              // MPEG-1
            2 -> MPEG2_RATES[rateIdx]              // MPEG-2
            else -> MPEG25_RATES[rateIdx]          // MPEG-2.5
        }
        val bitrateKbps = if (versionBits == 3) MPEG1_BITRATES[bitrateIdx] else MPEG2_BITRATES[bitrateIdx]
        val b3 = data[i + 3].toInt() and 0xFF
        val channels = if ((b3 shr 6) and 0x03 == 3) 1 else 2

        // 帧长（字节）：Layer III/II 用 samplesPerFrame/8 公式，Layer I 需 *4
        val samplesPerFrame = when (layerBits) {
            3 -> 384                                  // Layer I
            2 -> 1152                                 // Layer II
            else -> if (versionBits == 3) 1152 else 576 // Layer III
        }
        val frameLength = when (layerBits) {
            3 -> ((samplesPerFrame / 8) * bitrateKbps * 1000 / sampleRate + padding) * 4
            else -> (samplesPerFrame / 8) * bitrateKbps * 1000 / sampleRate + padding
        }
        return Mp3FrameInfo(sampleRate, channels, frameLength, samplesPerFrame)
    }

    /** 找到第一个「连续两帧都合法」的 MPEG 帧头位置，避免误判数据里的伪同步字 */
    private fun indexOfFirstMp3Frame(data: ByteArray): Int? {
        if (data.size < 16) return null
        val limit = minOf(data.size - 4, 8192)
        var i = 0
        while (i < limit) {
            val info = parseMp3FrameAt(data, i)
            if (info != null && info.frameLength > 0 &&
                i + info.frameLength + 4 <= data.size &&
                parseMp3FrameAt(data, i + info.frameLength) != null
            ) {
                return i
            }
            i++
        }
        return null
    }

    /**
     * 兜底二：不经 MediaExtractor，把 MP3 裸流直接喂给 MediaCodec。
     *
     * 平台 MediaExtractor 的 MP3 解析由 OEM 维护，对封装完整性极挑剔；
     * MediaCodec 本身只认帧，把整块数据按 64KB 分次 queue 进去反而稳。
     * 仅处理 MP3（TTS 插件返回的压缩格式绝大多数是它）。
     */
    private fun decodeRawMp3ViaMediaCodec(data: ByteArray): Triple<ByteArray, Int, Int>? {
        if (data.size < 16) return null
        val start = indexOfFirstMp3Frame(data) ?: return null
        val info = parseMp3FrameAt(data, start) ?: return null
        val decoder = try {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_MPEG)
        } catch (e: Exception) {
            return null
        }
        try {
            decoder.configure(
                MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_MPEG,
                    info.sampleRate,
                    info.channels
                ),
                null,
                null,
                0
            )
            decoder.start()

            val pcmChunks = mutableListOf<ByteArray>()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pos = start
            var inputStall = 0
            var outputIdle = 0
            val chunkSize = 64 * 1024

            while (!outputDone) {
                try {
                    if (!inputDone) {
                        val inputIdx = decoder.dequeueInputBuffer(10000)
                        if (inputIdx >= 0) {
                            inputStall = 0
                            val buffer = decoder.getInputBuffer(inputIdx)!!
                            val remaining = data.size - pos
                            val size = minOf(remaining, chunkSize)
                            buffer.clear()
                            buffer.put(data, pos, size)
                            pos += size
                            val flags =
                                if (remaining <= chunkSize) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            decoder.queueInputBuffer(inputIdx, 0, size, 0, flags)
                            inputDone = flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        } else if (++inputStall > 3) {
                            break
                        }
                    }

                    val outputIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        outputIdx >= 0 -> {
                            outputIdle = 0
                            val buffer = decoder.getOutputBuffer(outputIdx)!!
                            val chunk = ByteArray(bufferInfo.size)
                            buffer.get(chunk)
                            pcmChunks.add(chunk)
                            decoder.releaseOutputBuffer(outputIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (inputDone) {
                                if (++outputIdle > 2000) break
                                Thread.sleep(5)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 损坏帧：保留已解码部分
                    break
                }
            }

            runCatching { decoder.stop() }
            runCatching { decoder.release() }

            val totalSize = pcmChunks.sumOf { it.size }
            if (totalSize == 0) return null
            val pcm = ByteArray(totalSize)
            var offset = 0
            pcmChunks.forEach {
                System.arraycopy(it, 0, pcm, offset, it.size)
                offset += it.size
            }
            return Triple(pcm, info.sampleRate, info.channels)
        } catch (e: Exception) {
            runCatching { decoder.release() }
            return null
        }
    }

    /**
     * 把 MP3 流对齐到第一个合法帧头（剥掉头部垃圾/残帧字节）。
     * @return 对齐后的数据；首帧即合法、或找不到合法帧头时返回 null（按原样解）
     */
    private fun alignToFirstMp3Frame(data: ByteArray): ByteArray? {
        if (data.size < 8) return null
        if (isMp3FrameHeaderAt(data, 0)) return null
        val limit = minOf(data.size - 1, 8192)
        for (i in 1 until limit) {
            if (isMp3FrameHeaderAt(data, i)) {
                return data.copyOfRange(i, data.size)
            }
        }
        return null
    }

    /** 位置 i 是否是合法的 MPEG 音频帧头（sync + 版本/层/比特率/采样率均合法） */
    private fun isMp3FrameHeaderAt(data: ByteArray, i: Int): Boolean {
        if (i + 4 > data.size) return false
        val b1 = data[i + 1].toInt() and 0xFF
        if ((data[i].toInt() and 0xFF) != 0xFF || (b1 and 0xE0) != 0xE0) return false
        if (((b1 shr 3) and 0x03) == 1) return false      // 版本保留位
        if (((b1 shr 1) and 0x03) == 0) return false      // 层保留位
        val b2 = data[i + 2].toInt() and 0xFF
        val bitrateIdx = (b2 shr 4) and 0x0F
        if (bitrateIdx == 0 || bitrateIdx == 15) return false  // 比特率 free/损坏
        if (((b2 shr 2) and 0x03) == 3) return false      // 采样率保留位
        return true
    }

    /**
     * 根据音频数据前几个字节检测格式，返回正确的扩展名，
     * 帮助 MediaExtractor 正确识别格式。
     */
    private fun detectAudioExtension(audioData: ByteArray): String {
        if (audioData.size < 4) return ".tmp"
        val b0 = audioData[0].toInt() and 0xFF
        val b1 = audioData[1].toInt() and 0xFF
        val b2 = audioData[2].toInt() and 0xFF
        val b3 = audioData[3].toInt() and 0xFF
        return when {
            // WAV
            String(audioData.copyOfRange(0, 4)) == "RIFF" -> ".wav"
            // FLAC
            String(audioData.copyOfRange(0, 4)) == "fLaC" -> ".flac"
            // OGG
            String(audioData.copyOfRange(0, 4)) == "OggS" -> ".ogg"
            // MP3 (ID3 tag)
            b0 == 0x49 && b1 == 0x44 && b2 == 0x33 -> ".mp3"
            // MP3 ( MPEG sync word 0xFFE/0xFFF )
            b0 == 0xFF && (b1 and 0xE0) == 0xE0 -> ".mp3"
            // M4A/AAC (ftyp at offset 4)
            audioData.size >= 8 && String(audioData.copyOfRange(4, 8)) == "ftyp" -> ".m4a"
            else -> ".tmp"
        }
    }

    private fun decodeFileViaMediaCodec(file: File): Triple<ByteArray, Int, Int>? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue

                extractor.selectTrack(i)
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

                // 【关键修复】PCM / WAV (audio/raw) 不需要 MediaCodec 解码，直接读取
                if (mime.startsWith("audio/raw") || mime.contains("wav")) {
                    val pcmChunks = mutableListOf<ByteArray>()
                    val buf = ByteArray(1024 * 1024)
                    val bb = ByteBuffer.wrap(buf)
                    while (true) {
                        val sampleSize = extractor.readSampleData(bb, 0)
                        if (sampleSize < 0) break
                        pcmChunks.add(buf.copyOfRange(0, sampleSize))
                        bb.clear()
                        extractor.advance()
                    }
                    val totalSize = pcmChunks.sumOf { it.size }
                    val pcm = ByteArray(totalSize)
                    var offset = 0
                    pcmChunks.forEach {
                        System.arraycopy(it, 0, pcm, offset, it.size)
                        offset += it.size
                    }
                    // MediaExtractor 对 PCM 可能报告位深为 0，默认按 16bit 处理
                    return Triple(pcm, sampleRate, channels)
                }

                val decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(format, null, null, 0)
                decoder.start()

                val pcmChunks = mutableListOf<ByteArray>()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var inputStall = 0
                var outputIdle = 0

                // 容错解码：单帧损坏/解码器报错只中断后续，保留已解码的 PCM。
                // 整段返回 null 在混合格式场景会连锁丢整段对话，而「丢个尾巴」对听感几乎无影响。
                while (!outputDone) {
                    try {
                        if (!inputDone) {
                            val inputIdx = decoder.dequeueInputBuffer(10000)
                            if (inputIdx >= 0) {
                                inputStall = 0
                                val buffer = decoder.getInputBuffer(inputIdx)!!
                                val sampleSize = extractor.readSampleData(buffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    decoder.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            } else if (++inputStall > 3) {
                                // dequeueInputBuffer 已阻塞 10s 仍拿不到缓冲区：解码器大概率卡死
                                break
                            }
                        }

                        val outputIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                        when {
                            outputIdx >= 0 -> {
                                outputIdle = 0
                                val buffer = decoder.getOutputBuffer(outputIdx)!!
                                val chunk = ByteArray(bufferInfo.size)
                                buffer.get(chunk)
                                pcmChunks.add(chunk)
                                decoder.releaseOutputBuffer(outputIdx, false)
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    outputDone = true
                                }
                            }
                            outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                // 获取输出格式，通常与输入一致
                            }
                            outputIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                if (inputDone) {
                                    // 输入已 EOS 但解码器一直不给输出：约 10s 后放弃，保留已解码部分
                                    if (++outputIdle > 2000) {
                                        break
                                    }
                                    Thread.sleep(5)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // 损坏帧/解码器异常：不再整体失败，保留已解码的 PCM
                        break
                    }
                }

                runCatching { decoder.stop() }
                runCatching { decoder.release() }

                val totalSize = pcmChunks.sumOf { it.size }
                if (totalSize == 0) {
                    return null
                }
                val pcm = ByteArray(totalSize)
                var offset = 0
                pcmChunks.forEach {
                    System.arraycopy(it, 0, pcm, offset, it.size)
                    offset += it.size
                }

                // MediaCodec 解码出来的 PCM 是 16bit 的
                return Triple(pcm, sampleRate, channels)
            }
        } catch (e: Exception) {
            // 轨道解析/解码器创建等初始化阶段失败：返回 null，由调用方回退
        } finally {
            extractor.release()
        }
        return null
    }

    /** 重采样：线性插值，16bit PCM */
    private fun resamplePcm(
        pcm: ByteArray,
        srcRate: Int, dstRate: Int,
        srcChannels: Int, dstChannels: Int
    ): ByteArray {
        val srcSamples = pcm.size / 2 / srcChannels
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val dstSamples = (srcSamples / ratio).toInt()

        val result = ByteArray(dstSamples * 2 * dstChannels)
        var dstIdx = 0

        for (i in 0 until dstSamples) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val srcIdxNext = (srcIdx + 1).coerceAtMost(srcSamples - 1)

            // 取所有声道的平均值（混音为单声道）
            val sample0 = getSampleMono(pcm, srcIdx, srcChannels)
            val sample1 = getSampleMono(pcm, srcIdxNext, srcChannels)
            val interpolated = (sample0 * (1 - frac) + sample1 * frac).toInt().coerceIn(-32768, 32767)

            // 写入 16bit 小端
            result[dstIdx++] = (interpolated and 0xFF).toByte()
            result[dstIdx++] = ((interpolated shr 8) and 0xFF).toByte()

            // 如果目标是双声道，重复写入
            if (dstChannels == 2) {
                result[dstIdx++] = (interpolated and 0xFF).toByte()
                result[dstIdx++] = ((interpolated shr 8) and 0xFF).toByte()
            }
        }

        return result
    }

    /** 从多声道 PCM 中取一个采样点（混音为单声道） */
    private fun getSampleMono(pcm: ByteArray, sampleIndex: Int, channels: Int): Double {
        var sum = 0.0
        for (ch in 0 until channels) {
            val idx = sampleIndex * channels * 2 + ch * 2
            if (idx + 1 >= pcm.size) return 0.0
            val lo = pcm[idx].toInt() and 0xFF
            val hi = pcm[idx + 1].toInt()
            val value = lo or (hi shl 8)
            sum += if (value > 32767) (value - 65536).toDouble() else value.toDouble()
        }
        return sum / channels
    }

    /** 从 WAV 字节数组中提取 PCM 数据 */
    private fun extractPcmFromWav(wavData: ByteArray): ByteArray? {
        if (wavData.size < 44) return null
        var pos = 12
        while (pos < wavData.size - 8) {
            val chunkId = String(wavData.copyOfRange(pos, pos + 4))
            val chunkSize = readUint32Le(wavData, pos + 4)
            pos += 8
            if (chunkId == "data") {
                val end = (pos + chunkSize).coerceAtMost(wavData.size)
                return wavData.copyOfRange(pos, end)
            }
            // WAV chunk 数据长度必须是偶数，奇数时补一个 pad 字节
            pos += chunkSize + (chunkSize and 1)
        }
        return null
    }

    /** 写入标准 WAV 头 + PCM 数据 */
    private fun writeWavHeaderAndData(
        pcm: ByteArray, sampleRate: Int, channels: Int, bits: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val totalLen = 36 + pcm.size

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(intToLe(totalLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToLe(16))
        out.write(shortToLe(1)) // PCM = 1
        out.write(shortToLe(channels.toShort()))
        out.write(intToLe(sampleRate))
        out.write(intToLe(byteRate))
        out.write(shortToLe(blockAlign.toShort()))
        out.write(shortToLe(bits.toShort()))
        out.write("data".toByteArray())
        out.write(intToLe(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun readUint16Le(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUint32Le(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun intToLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun shortToLe(v: Short): ByteArray = byteArrayOf(
        (v.toInt() and 0xFF).toByte(),
        ((v.toInt() shr 8) and 0xFF).toByte()
    )
}
