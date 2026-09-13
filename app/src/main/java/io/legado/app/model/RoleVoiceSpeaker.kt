package io.legado.app.model

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import io.legado.app.ui.book.read.config.TtsPreviewHelper

/**
 * 角色语音回复播放器。
 *
 * 复用「角色管理 - 按角色主名字试听」同一条合成链路
 * [TtsPreviewHelper.previewVoice]：依据用户实际配置的朗读引擎（内置TTS插件 /
 * 系统TTS / 猫箱等）合成，并把发音人内嵌成 `<<voice>>` 标记交给引擎解析，
 * 因此回复朗读的音色与试听、与正常朗读完全一致。
 *
 * 早期版本直接调 [io.legado.app.help.audiobook.JReadVoiceEngine.synthesizeLineAudio]
 * 并传 pointer 声码，绕过了用户实际配置的引擎、且与「试听」音色不一致，
 * 故改为走 previewVoice。
 */
object RoleVoiceSpeaker {

    /**
     * 朗读角色回复文本。
     * @param text 原始回复；角色扮演的动作括号（…）/ 尖括号标记会被剥离，避免念出来
     * @param voiceTag 角色绑定的发音人（空 = 未绑定，不朗读）
     */
    fun speak(context: Context, lifecycleOwner: LifecycleOwner, text: String, voiceTag: String) {
        if (voiceTag.isBlank()) return
        val clean = stripBrackets(text).takeIf { it.isNotBlank() } ?: return
        // 内嵌发音人标记，与角色管理「试听」拼装方式完全一致：
        // 前后包中文全角双引号、标记紧跟左引号后（引擎约定此写法才按角色对话音合成）
        val previewText = "\u201C <<$voiceTag>>$clean\u201D"
        TtsPreviewHelper.previewVoice(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewText = previewText,
            voiceId = voiceTag,
            onToast = { }
        )
    }

    /** 剥离角色扮演的动作括号：（）全角 / () 半角 / <> 标记 */
    private fun stripBrackets(s: String): String =
        s.replace(Regex("[（(].*?[)）]"), "")
            .replace(Regex("<[^>]*>"), "")
            .trim()

    fun release() {
        // 播放由 TtsPreviewHelper 内部管理，此处无需释放
    }
}
