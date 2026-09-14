package io.legado.app.help.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * 视觉小说舞台「镜像渐模糊」：把图片沿接缝做垂直镜像，拼到清晰层上/下方留白带。
 * 接缝处（与清晰层同一行内容）清晰，往屏幕边缘方向渐隐，透出背后的模糊镜像层，
 * 形成「越来越模糊」的过渡；整条带不透明，绝不露黑底。
 *
 * @param side    0=上方（拼在清晰层顶边之上）/ 1=下方（拼在清晰层底边之下）
 * @param fitMode 0=fitCenter（上下留白相等）/ 1=fitStart（贴顶，仅下方留白）
 * @param fade    是否做「接缝清晰→边缘透明」的渐隐。
 *               清晰镜像层用 true；模糊镜像层用 false（全不透明打底，供清晰层渐隐后透出）。
 */
class VnMirrorTransform(
    private val side: Int,
    private val fitMode: Int,
    private val fade: Boolean
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        source: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        if (outWidth <= 0 || outHeight <= 0 || source.isRecycled) return source
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val scale = minOf(outWidth / srcW, outHeight / srcH)
        val dw = srcW * scale
        val dh = srcH * scale
        val dx = (outWidth - dw) / 2f
        val dy = if (fitMode == 1) 0f else (outHeight - dh) / 2f
        // 接缝 y：上方 = 清晰层顶边(dy)，下方 = 清晰层底边(dy+dh)
        val seamY = if (side == 1) dy + dh else dy

        val result = if (source.width == outWidth && source.height == outHeight) {
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        } else {
            pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        }
        result.setHasAlpha(true)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        // 沿水平轴翻转（scaleY=-1），再把翻转后的图平移到接缝处：
        // - 上方：原图顶行(srcH=0)落在接缝 seamY；
        // - 下方：原图底行(srcH)落在接缝 seamY。
        // 这样接缝处内容 = 清晰层同位置内容，过渡天然连续（镜像反射）。
        val m = Matrix()
        m.setScale(scale, -scale)
        m.postTranslate(dx, if (side == 1) seamY + dh else seamY)
        canvas.drawBitmap(source, m, Paint(Paint.ANTI_ALIAS_FLAG))

        if (fade) {
            val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                // 接缝处 BLACK(不透明，保留) → 屏幕边缘 TRANSPARENT(渐隐，透出底层模糊)
                shader = if (side == 1) {
                    LinearGradient(
                        0f, seamY, 0f, outHeight.toFloat(),
                        Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
                    )
                } else {
                    LinearGradient(
                        0f, seamY, 0f, 0f,
                        Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP
                    )
                }
            }
            if (side == 1) {
                canvas.drawRect(0f, seamY, outWidth.toFloat(), outHeight.toFloat(), mask)
            } else {
                canvas.drawRect(0f, 0f, outWidth.toFloat(), seamY, mask)
            }
        }
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("vn_mirror_${side}_${fitMode}_$fade".toByteArray())
    }
}
