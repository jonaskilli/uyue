package io.legado.app.ui.soundeffect

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.legado.app.constant.AppLog
import io.legado.app.help.audio.GainAudioProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.service.AiEffectDownloader
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

/**
 * 音效试听播放。
 *
 * 用于音效规则管理界面里试听「替换为」中的音效标记，
 * 复用朗读服务同一套下载/解码/索引轮换与 PCM 增益逻辑，
 * 因此试听效果与实际朗读时完全一致。
 *
 * 朗读服务不运行时也能播放，播放器独立持有，使用 [appCtx] 避免界面泄漏。
 */
object SoundEffectPlayer {

    /** 音效标记正则，与朗读服务 `BaseReadAloudService` 中的一致 */
    private val effectPattern = Regex("\\(([\\u4e00-\\u9fa5]*音效)\\)")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var player: ExoPlayer? = null
    private var gainProcessor: GainAudioProcessor? = null
    private var loadJob: Job? = null
    private var releaseJob: Job? = null

    /**
     * 按出现顺序提取文本中的音效名，例如 "(冲击波音效)xxx(打雷音效)"
     * → ["冲击波音效", "打雷音效"]，对应 冲击波音效.json / 打雷音效.json。
     */
    fun extractEffectNames(text: String): List<String> =
        effectPattern.findAll(text).map { it.groupValues[1] }.toList()

    /**
     * 播放文本中的音效标记，多个按出现顺序依次播放。
     * @param onResult 结果为 null 表示已开始播放，否则为错误提示（主线程回调）
     */
    fun play(text: String, onResult: ((String?) -> Unit)? = null) {
        val names = extractEffectNames(text)
        if (names.isEmpty()) {
            onResult?.invoke("没有找到音效标记，例如 (冲击波音效)")
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val files = withContext(Dispatchers.IO) {
                names.mapNotNull { name ->
                    runCatching {
                        BaseReadAloudService.getSoundEffectAudioFile("$name.json")
                    }.onFailure {
                        AppLog.put("[音效试听] 加载失败：$name, ${it.message}")
                    }.getOrNull()
                }
            }
            if (files.isEmpty()) {
                onResult?.invoke("音效加载失败：${names.joinToString("、")}")
                return@launch
            }
            if (files.size < names.size) {
                AppLog.put("[音效试听] 部分音效加载失败：${names.size} 个中成功 ${files.size} 个")
            }
            startPlay(files, onResult)
        }
    }

    /**
     * 试听广播剧音效库里的某个音效。
     * 与 [play] 的区别：直接给相对路径（如 "01_通用母库/拟音/炒菜.mp3"），
     * 由 [AiEffectDownloader] 负责本地优先/远端下载，不经 JSON 索引轮换。
     *
     * @param relativePath 音效在库中的相对路径
     */
    fun playAiEffect(relativePath: String, onResult: ((String?) -> Unit)? = null) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { AiEffectDownloader.obtain(relativePath) }
                    .onFailure { AppLog.put("[音效试听] AI音效加载失败：$relativePath, ${it.message}") }
                    .getOrNull()
            }
            if (file == null) {
                onResult?.invoke("音效加载失败：$relativePath")
                return@launch
            }
            startPlay(listOf(file), onResult)
        }
    }

    private fun startPlay(files: List<File>, onResult: ((String?) -> Unit)?) {
        releaseJob?.cancel()
        releaseJob = null
        val p = getOrCreatePlayer()
        if (p == null) {
            onResult?.invoke("播放器创建失败")
            return
        }
        try {
            // 每次播放前刷新增益，使音量设置实时生效
            gainProcessor?.setGain(AppConfig.soundEffectVolume / 100f)
            p.clearMediaItems()
            p.setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            p.prepare()
            p.play()
            AppLog.put("[音效试听] 开始播放 ${files.size} 个音效")
            onResult?.invoke(null)
        } catch (e: Exception) {
            AppLog.put("[音效试听] 播放失败：${e.message}", e)
            onResult?.invoke("播放失败：${e.message}")
        }
    }

    private fun getOrCreatePlayer(): ExoPlayer? {
        player?.let { return it }
        return try {
            val processor = GainAudioProcessor(AppConfig.soundEffectVolume / 100f)
            val renderersFactory = object : DefaultRenderersFactory(appCtx) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink? = DefaultAudioSink.Builder(appCtx)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
            }
            val newPlayer = ExoPlayer.Builder(appCtx, renderersFactory).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            scheduleRelease()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        AppLog.put("[音效试听] 播放错误：${error.message}")
                        // 出错的条目无法自动跳过，移除后继续播剩下的
                        if (currentMediaItemIndex in 0 until mediaItemCount) {
                            removeMediaItem(currentMediaItemIndex)
                        }
                        if (mediaItemCount > 0) {
                            prepare()
                            play()
                        }
                    }
                })
            }
            gainProcessor = processor
            player = newPlayer
            newPlayer
        } catch (e: Exception) {
            AppLog.put("[音效试听] 播放器创建失败：${e.message}", e)
            null
        }
    }

    /** 停止当前试听 */
    fun stop() {
        loadJob?.cancel()
        loadJob = null
        player?.run {
            stop()
            clearMediaItems()
        }
        scheduleRelease()
    }

    /**
     * 播放结束后延迟释放播放器，既避免反复创建，
     * 也不会在界面退出后长期占用音频资源。
     */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(60_000)
            release()
        }
    }

    fun release() {
        loadJob?.cancel()
        loadJob = null
        releaseJob?.cancel()
        releaseJob = null
        player?.release()
        player = null
        gainProcessor = null
    }
}
