package io.legado.app.help.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer

/**
 * 只收 PCM、不播放的 [AudioSink]（移植自 TTS_Server_Android 的 DecoderAudioSink）。
 *
 * 挂在 ExoPlayer 渲染器上，把解码后的 PCM Buffer 通过回调抛出来，
 * 用于「把任意格式音频转成 PCM」而不是播放。
 *
 * 三个关键声明（与参考实现一致，生产验证过）：
 *  - [supportsFormat] 只收 `audio/raw`：即只要解码后的 PCM；
 *  - [getFormatSupport] 返回 `SINK_FORMAT_SUPPORTED_WITH_TRANSCODING`：
 *    告诉渲染器「我什么都能收，必要时先转码」，解码→PCM 的转换由 media3 完成；
 *  - [isEnded] 恒为 true：音源 EOF 时播放器立即收尾，不等 AudioTrack 排空。
 */
@OptIn(UnstableApi::class)
class DecoderAudioSink(
    private val onPcmBuffer: (ByteBuffer) -> Unit,
    private val onFormat: (sampleRate: Int, channels: Int) -> Unit,
    private val onEndOfStream: () -> Unit,
) : AudioSink {

    override fun setListener(listener: AudioSink.Listener) {}

    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == "audio/raw"

    override fun getFormatSupport(format: Format): Int =
        AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = 0L

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        // 解码后的 PCM 格式在此给出：记下采样率/声道数，调用方按它重采样
        onFormat(inputFormat.sampleRate, inputFormat.channelCount)
    }

    override fun play() {}

    override fun handleDiscontinuity() {}

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // 注意：buffer 会被播放器复用，消费方必须立即拷贝
        onPcmBuffer(buffer)
        return true
    }

    override fun playToEndOfStream() {
        onEndOfStream()
    }

    override fun isEnded(): Boolean = true

    override fun hasPendingData(): Boolean = true

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(1f)

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

    override fun getSkipSilenceEnabled(): Boolean = false

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}

    override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

    override fun setAudioSessionId(audioSessionId: Int) {}

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

    override fun getAudioTrackBufferSizeUs(): Long = 0L

    override fun enableTunnelingV21() {}

    override fun disableTunneling() {}

    override fun setVolume(volume: Float) {}

    override fun pause() {}

    override fun flush() {}

    override fun reset() {}
}
