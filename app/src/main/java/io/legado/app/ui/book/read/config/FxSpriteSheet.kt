package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import io.legado.app.model.SceneEffectCue

/**
 * 特效素材接入层：现在由 [FxVectorRenderer] 直接按特效 id 做参数化矢量绘制，
 * 不再解码 assets/fx 里的 PNG 序列帧。
 *
 * 这样没有像素锯齿、不占包体（原 154 张精灵图约 21.7 MB 已移出 assets，
 * 备份在 /workspace/tmp/fx-src/backup-fx-assets 可随时恢复），
 * 但接口与 show/clear 生命周期保持不变，SceneEffectView 三处调用无需改动。
 *
 * 素材的中文名/分类/帧数仍由 assets/fx/catalog.json 提供，供管理界面列表使用。
 */
object FxSpriteSheet {

    fun draw(
        c: Canvas,
        context: Context,
        cue: SceneEffectCue,
        t: Double,
        w: Double,
        h: Double,
        k: Double,
        paint: Paint
    ): Boolean {
        val id = cue.fxId ?: return false
        return FxVectorRenderer.draw(
            c, id, t, cue.durationMs / 1000.0, w, h, k, cue.level
        )
    }

    /** 矢量绘制没有 Bitmap 缓存，保留接口以免调用方报错 */
    fun clearCache() = Unit
}
