package io.legado.app.help.audio

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import splitties.init.appCtx
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 基于 ExoPlayer(media3) 的音频解码器：把任意格式音频（MP3/AAC/OGG/FLAC/WAV…）
 * 解码为 16bit PCM。移植自 TTS_Server_Android 的 `ExoAudioDecoder` + `DecoderAudioSink`。
 *
 * **为什么不用系统 MediaExtractor/MediaCodec**：平台的 MP3 解析由各 OEM 维护，随
 * Android 版本/ROM 变化，对 ID3 标签尤其挑剔，表现为「时好时坏」；media3 自带的
 * extractor（Mp3Extractor 等）是纯 Java 实现、打包在 APK 内，跨设备行为完全一致，
 * 对 ID3/裸帧/VBR 容忍度高——TTS_Server_Android 的合成转码从不失败就是这个原因。
 *
 * 用法：[decode] 为阻塞调用，内部单飞执行；返回 (PCM, 采样率, 声道数)，失败返回 null
 * （调用方回退平台解码或原始字节）。播放器在专用 HandlerThread 上懒创建并长期复用。
 */
@OptIn(UnstableApi::class)
object ExoAudioDecoder {
    private const val TAG = "ExoAudioDecoder"
    private const val DECODE_TIMEOUT_MS = 30_000L

    private val lock = Any()

    private var playerThread: HandlerThread? = null
    private var playerHandler: Handler? = null
    private var exoPlayer: ExoPlayer? = null

    // 单次解码的中间状态（仅在 lock 保护下访问）
    private val pcmOut = ByteArrayOutputStream()
    private var decodedSampleRate = 0
    private var decodedChannels = 0
    private var decodeError: String? = null
    private var decodeLatch: CountDownLatch? = null

    private val sink = DecoderAudioSink(
        onPcmBuffer = { buffer ->
            // buffer 会被播放器复用，必须立即拷贝
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            pcmOut.write(chunk)
        },
        onFormat = { rate, channels ->
            decodedSampleRate = rate
            decodedChannels = channels
        },
        onEndOfStream = { decodeLatch?.countDown() }
    )

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) decodeLatch?.countDown()
        }

        override fun onPlayerError(error: PlaybackException) {
            decodeError = error.message ?: "ExoPlayer 解码错误"
            decodeLatch?.countDown()
        }
    }

    /** 阻塞解码；失败返回 null（调用方回退平台解码或原始字节） */
    fun decode(audioData: ByteArray): Triple<ByteArray, Int, Int>? {
        if (audioData.isEmpty()) return null
        synchronized(lock) {
            pcmOut.reset()
            decodedSampleRate = 0
            decodedChannels = 0
            decodeError = null
            val latch = CountDownLatch(1)
            decodeLatch = latch
            try {
                if (!ensurePlayer()) return null
                val handler = playerHandler ?: return null
                handler.post {
                    val player = exoPlayer
                    if (player == null) {
                        decodeError = "播放器未初始化"
                        latch.countDown()
                        return@post
                    }
                    try {
                        player.stop()
                        // 字节数组直喂（ByteArrayDataSource），不落临时文件、不靠扩展名嗅探
                        val dataSourceFactory = DataSource.Factory { ByteArrayDataSource(audioData) }
                        val mediaSource = DefaultMediaSourceFactory(
                            appCtx,
                            DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
                        ).setDataSourceFactory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(""))
                        player.setMediaSource(mediaSource)
                        player.prepare()
                        player.play()
                    } catch (e: Exception) {
                        decodeError = e.message ?: "解码启动失败"
                        latch.countDown()
                    }
                }
                if (!latch.await(DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    decodeError = "解码超时(${DECODE_TIMEOUT_MS}ms)"
                }
            } catch (e: Exception) {
                decodeError = e.message ?: "解码异常"
            } finally {
                decodeLatch = null
            }
            if (decodeError != null) {
                return null
            }
            if (pcmOut.size() == 0 || decodedSampleRate <= 0) {
                return null
            }
            return Triple(
                pcmOut.toByteArray(),
                decodedSampleRate,
                if (decodedChannels > 0) decodedChannels else 1
            )
        }
    }

    /**
     * 懒初始化：专用 HandlerThread 上创建 ExoPlayer。
     * 播放器只在其所在 Looper 线程创建/操作，故所有播放器调用都 post 到该线程。
     */
    private fun ensurePlayer(): Boolean {
        if (exoPlayer != null) return true
        val thread = playerThread ?: HandlerThread("ExoAudioDecoder").also {
            it.start()
            playerThread = it
        }
        val handler = playerHandler ?: Handler(thread.looper).also { playerHandler = it }
        val latch = CountDownLatch(1)
        var ok = false
        handler.post {
            try {
                val renderersFactory = object : DefaultRenderersFactory(appCtx) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink {
                        return sink
                    }
                }
                exoPlayer = ExoPlayer.Builder(appCtx, renderersFactory).build().apply {
                    addListener(playerListener)
                }
                ok = true
            } catch (e: Exception) {
                // 播放器创建失败：decode() 走 ensurePlayer 的 false 分支返回 null
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        return ok && exoPlayer != null
    }
}
