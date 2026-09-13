package io.legado.app.model

import android.content.Context
import android.media.MediaPlayer
import io.legado.app.help.audiobook.JReadVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 角色语音回复播放器。
 *
 * 用角色绑定的发音人（JReadVoiceEngine 的 voiceTag）把文本合成音频，
 * 再用 MediaPlayer 播放。合成与角色管理的「试听」共用同一条
 * [JReadVoiceEngine.synthesizeLineAudio] 链路，
 * 因此发音人 tag 的解析、降级逻辑完全一致。
 */
object RoleVoiceSpeaker {

    private val playerRef = AtomicReference<MediaPlayer?>(null)

    /**
     * 用 voiceTag 朗读文本。voiceTag 为空（角色未绑定发音人）直接跳过。
     * 角色扮演回复里的（动作描写）先剥离，避免把括号内容念出来。
     */
    suspend fun speak(context: Context, text: String, voiceTag: String) {
        if (voiceTag.isBlank()) return
        val clean = stripBrackets(text).takeIf { it.isNotBlank() } ?: return
        val pointer = JSONObject().put("voiceTag", voiceTag).toString()
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                JReadVoiceEngine.synthesizeLineAudio(
                    context.applicationContext,
                    clean,
                    pointer,
                    "rolechat_${System.currentTimeMillis()}"
                )
            }.getOrNull() ?: return@withContext
            if (bytes.isEmpty()) return@withContext
            playWav(context.applicationContext, bytes)
        }
    }

    /** 剥离角色扮演的动作括号：（）全角 / () 半角 / <> 标记 */
    private fun stripBrackets(s: String): String =
        s.replace(Regex("[（(].*?[)）]"), "")
            .replace(Regex("<[^>]*>"), "")
            .trim()

    private fun playWav(ctx: Context, bytes: ByteArray) {
        runCatching { playerRef.getAndSet(null)?.release() }
        val file = File(ctx.cacheDir, "role_chat/reply_${System.currentTimeMillis()}.wav")
        runCatching { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { mp -> mp.release(); playerRef.compareAndSet(mp, null) }
            player.setOnErrorListener { mp, _, _ ->
                mp.release(); playerRef.compareAndSet(mp, null); true
            }
            player.prepare()
            player.start()
            playerRef.set(player)
        } catch (e: Exception) {
            runCatching { player.release() }
        }
    }

    fun release() {
        runCatching { playerRef.getAndSet(null)?.release() }
    }
}
