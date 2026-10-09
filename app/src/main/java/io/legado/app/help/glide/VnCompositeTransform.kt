package io.legado.app.help.glide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import io.legado.app.utils.stackBlur
import java.security.MessageDigest

/**
 * 视觉小说舞台「镜像渐模糊」合成变换：把背景在一张全屏 Bitmap 上一次画好——
 * 全屏模糊底（防露黑）+ 清晰主图（fitCenter / fitStart）+ 上下镜像带
 * （接缝清晰、向屏幕边缘渐隐透出模糊镜像，形成自然反射 + 越来越模糊）。
 *
 * 关键：整张背景是「一个 drawable」一次性交给 ImageView，于是切图时主图与镜像带
 * 同帧出现，不会先出主图、隔一会再补上下拼接（割裂闪烁）。
 * 产物用独立 Bitmap.createBitmap（不取自 BitmapPool），避免切后台恢复时被 Glide
 * 复用成 recycled bitmap。
 *
 * @param top    是否上方镜像带（模式 1 不裁左右·上下）
 * @param bottom 是否下方镜像带（模式 1、2 都有）
 * @param fitMode 0=fitCenter（上下留白相等）/ 1=fitStart（贴顶，仅下方留白）
 */
class VnCompositeTransform(
    private val top: Boolean,
    private val bottom: Boolean,
    private val fitMode: Int
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        source: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        if (outWidth <= 0 || outHeight <= 0 || source.isRecycled) return source
        val blurred = source.stackBlur(20)
        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        result.setHasAlpha(true)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val scale = minOf(outWidth / srcW, outHeight / srcH)
        val dw = srcW * scale
        val dh = srcH * scale
        val dx = (outWidth - dw) / 2f
        val dy = if (fitMode == 1) 0f else (outHeight - dh) / 2f

        // 1) 全屏模糊底（centerCrop 铺满），任何未覆盖处（侧边/极端比例）也不露黑
        run {
            val s = maxOf(outWidth / srcW, outHeight / srcH)
            val w = srcW * s
            val h = srcH * s
            val x = (outWidth - w) / 2f
            val y = (outHeight - h) / 2f
            canvas.drawBitmap(
                blurred, null,
                RectF(x, y, x + w, y + h), Paint(Paint.ANTI_ALIAS_FLAG)
            )
        }
        // 2) 清晰主图
        canvas.drawBitmap(source, null, RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.ANTI_ALIAS_FLAG))
        // 3) 镜像带
        if (top) drawBand(canvas, source, blurred, side = 0, dx, dy, dw, dh, scale, outWidth, outHeight)
        if (bottom) drawBand(canvas, source, blurred, side = 1, dx, dy, dw, dh, scale, outWidth, outHeight)

        blurred.recycle()
        return result
    }

    /**
     * 画一条镜像带：先在带内铺「模糊镜像」（不透明打底），再叠「清晰镜像」并做
     * 接缝→边缘的透明度渐变（DST_IN），透出底层模糊 → 越来越模糊。
     */
    private fun drawBand(
        canvas: Canvas,
        source: Bitmap,
        blurred: Bitmap,
        side: Int,
        dx: Float, dy: Float, dw: Float, dh: Float, scale: Float,
        outW: Int, outH: Int
    ) {
        val seamY = if (side == 1) dy + dh else dy
        // 模糊镜像打底
        drawFlipped(canvas, blurred, side, dx, seamY, dw, dh, scale)
        // 清晰镜像（带渐隐）画到离屏层，再合成回来，避免 DST_IN 误伤已画内容
        val layer = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        layer.setHasAlpha(true)
        val lc = Canvas(layer)
        lc.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawFlipped(lc, source, side, dx, seamY, dw, dh, scale)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            // 接缝 BLACK(保留) → 屏幕边缘 TRANSPARENT(渐隐，透出底层模糊)
            shader = if (side == 1) {
                LinearGradient(0f, seamY, 0f, outH.toFloat(), Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, seamY, 0f, 0f, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            }
        }
        if (side == 1) lc.drawRect(0f, seamY, outW.toFloat(), outH.toFloat(), mask)
        else lc.drawRect(0f, 0f, outW.toFloat(), seamY, mask)
        canvas.drawBitmap(layer, 0f, 0f, null)
        layer.recycle()
    }

    /** 把图沿水平轴翻转（scaleY=-scale），平移到接缝处：接缝即清晰层同位置内容（天然反射）。 */
    private fun drawFlipped(
        canvas: Canvas,
        bmp: Bitmap,
        side: Int,
        dx: Float,
        seamY: Float,
        dw: Float,
        dh: Float,
        scale: Float
    ) {
        val m = Matrix()
        m.setScale(scale, -scale)
        m.postTranslate(dx, if (side == 1) seamY + dh else seamY)
        canvas.drawBitmap(bmp, m, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("vn_composite_${top}_${bottom}_$fitMode".toByteArray())
    }
}
