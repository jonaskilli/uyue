package io.legado.app.help.video

import android.content.Context
import splitties.init.appCtx

/**
 * 跳过片头 / 片尾 的设置。
 *
 * 按 **bookUrl** 存，也就是"一部剧一部设置"，所有集共享；换个视频互不影响。
 *
 * 故意和「倒序」共用同一个 SharedPreferences（`video_reverse_order`）——
 * 都是"某个视频的播放偏好"，放在一起便于日后一起迁移/清理。
 *
 * 存的是**毫秒**，滑块拖出来的是秒，落盘前再乘回去。
 */
object SkipEdge {

    /** 与倒序同一个 prefs 文件 */
    private const val PREFS = "video_reverse_order"
    private const val KEY_INTRO = "_skip_intro_ms"
    private const val KEY_OUTRO = "_skip_outro_ms"

    /** 滑块上限：片头片尾都是秒级广告，10 分钟足够 */
    const val MAX_MS = 10 * 60 * 1000

    /** 步进 1 秒 */
    const val STEP_SEC = 1

    private val prefs by lazy {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 读一部剧的片头/片尾跳过时长（毫秒，0=不跳） */
    fun introMs(bookUrl: String): Int = if (bookUrl.isBlank()) 0
    else prefs.getInt(bookUrl + KEY_INTRO, 0).coerceIn(0, MAX_MS)

    fun outroMs(bookUrl: String): Int = if (bookUrl.isBlank()) 0
    else prefs.getInt(bookUrl + KEY_OUTRO, 0).coerceIn(0, MAX_MS)

    fun save(bookUrl: String, introMs: Int, outroMs: Int) {
        if (bookUrl.isBlank()) return
        prefs.edit()
            .putInt(bookUrl + KEY_INTRO, introMs.coerceIn(0, MAX_MS))
            .putInt(bookUrl + KEY_OUTRO, outroMs.coerceIn(0, MAX_MS))
            .apply()
    }

    /** 是否启用（任一端非零） */
    fun isEnabled(bookUrl: String): Boolean = introMs(bookUrl) > 0 || outroMs(bookUrl) > 0
}