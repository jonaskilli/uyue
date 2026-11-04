@file:Suppress("unused")

package io.legado.app.model.scene

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 场景特效粒子层：放在「背景图/立绘之上、对话框之下」。
 *
 * 对应 renderer/particle-renderer.ts 的 Canvas 2D 实现，逻辑与站点一致：
 *   - 确定性伪随机 hash(i, seed)，重绘/旋转屏幕不跳变
 *   - 位置取模回卷，粒子无限循环，不每帧 new 对象
 *   - duration <= 0 表示持续到区间结束（天气），只做 0.5s 淡入
 *   - supports rain / snow / petals / leaves / fireflies / embers / dust /
 *     bubbles / fog / smoke / wind / rays
 *
 * 布局接入示例（dialog_vn_stage.xml）：
 *   <io.legado.app.model.scene.WeatherOverlay
 *       android:id="@+id/weather_overlay"
 *       android:layout_width="match_parent"
 *       android:layout_height="match_parent" />
 *
 * 使用：
 *   binding.weatherOverlay.setTracks(resolver.tracksFor(annotation, action))
 *   binding.weatherOverlay.clear()   // 区间结束 / 离开视觉小说
 */
class WeatherOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tracks: List<Track> = emptyList()
    private var startMs: Long = SystemClock.uptimeMillis()
    private var running = false

    /** 只在轨道真正变化时重置起始时间，避免天气切换时整屏粒子闪一下 */
    fun setTracks(newTracks: List<Track>) {
        if (newTracks == tracks) return
        tracks = newTracks
        startMs = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    fun clear() = setTracks(emptyList())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tracks.isEmpty()) return
        val t = (SystemClock.uptimeMillis() - startMs) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        for (track in tracks) {
            val elapsed = t - track.at
            if (elapsed < 0) continue
            if (track.duration > 0 && elapsed > track.duration) continue
            drawTrack(canvas, track, elapsed, w, h)
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun drawTrack(canvas: Canvas, track: Track, t: Float, w: Float, h: Float) {
        if (track.kind != "particles" && track.kind != "ambient") return
        val fadeIn = (t / 0.5f).coerceAtMost(1f)
        val fadeOut = if (track.duration > 0) ((track.duration - t) / 0.5f).coerceAtLeast(0f) else 1f
        val alpha = (track.opacity * fadeIn * fadeOut).coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val count = track.count.coerceIn(1, 1200)
        val size = track.size.coerceIn(0.1f, 4f)
        val wind = track.wind
        val fall = track.fallSpeed.coerceIn(0f, 4f)

        paint.alpha = (alpha * 255).toInt()
        when (track.variant) {
            "rain" -> drawRain(canvas, count, size, wind, fall, t, w, h)
            "snow" -> drawSnow(canvas, count, size, wind, fall, t, w, h)
            "petals", "leaves", "butterflies" ->
                drawFalling(canvas, track.variant, count, size, wind, fall, t, w, h)
            "fireflies", "embers", "dust", "bubbles" ->
                drawSparks(canvas, track.variant, count, size, wind, t, w, h)
            "fog", "smoke" -> drawFog(canvas, track.variant, count, size, wind, t, w, h)
            "wind" -> drawWind(canvas, count, size, wind, t, w, h)
            "rays" -> drawRays(canvas, count, size, w, h)
        }
        paint.alpha = 255
    }

    private fun drawRain(c: Canvas, n: Int, size: Float, wind: Float, fall: Float, t: Float, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.1f * size
        paint.color = Color.parseColor("#CFE4F5")
        val tilt = atan2(wind * 0.7, fall.toDouble()).toFloat() * 0.55f
        for (i in 0 until n) {
            val depth = (0.55 + hash(i, 7) * 0.9).toFloat()
            val x = wrap(hash(i, 1) * w + t * wind * 80f * depth + sin(t * 1.2 + i) * 6f, w)
            val y = wrap(hash(i, 2) * h + t * fall * 190f * depth, h)
            val len = (10 + hash(i, 3) * 16).toFloat() * size * depth
            c.drawLine(x, y, x + sin(tilt) * len, y + cos(tilt) * len, paint)
        }
    }

    private fun drawSnow(c: Canvas, n: Int, size: Float, wind: Float, fall: Float, t: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        for (i in 0 until n) {
            val depth = (0.5 + hash(i, 11)).toFloat()
            val x = wrap(hash(i, 1) * w + t * wind * 26f * depth + sin(t * 0.8 + i) * 18f, w)
            val y = wrap(hash(i, 2) * h + t * fall * 55f * depth, h)
            val r = (0.9 + hash(i, 3) * 2.1).toFloat() * size * depth
            c.drawCircle(x, y, r, paint)
        }
    }

    private fun drawFalling(
        c: Canvas, variant: String, n: Int, size: Float,
        wind: Float, fall: Float, t: Float, w: Float, h: Float,
    ) {
        paint.style = Paint.Style.FILL
        val isLeaf = variant == "leaves"
        for (i in 0 until n) {
            val m = (0.45 + hash(i, 6) * 0.9).toFloat()
            val drift = t * (if (isLeaf) 33f else 22f) * fall * m
            val x = wrap(hash(i, 5) * w + sin(t * 0.9 + i) * 38f * m + sin(t * 0.4 + i) * 18f * wind, w)
            val y = wrap(hash(i, 2) * h + drift, h)
            val flip = cos(t * 1.6 + i)
            val angle = (t * (if (isLeaf) 0.8f else 0.45f) * m + sin(t * 1.1 + i) * 0.7f).toFloat()
            val scale = ((0.18f + abs(flip) * 0.82f) * size * m).toFloat()

            c.save()
            c.translate(x, y)
            c.rotate(angle * 180f / PI.toFloat())
            c.scale(scale, scale)
            paint.color = when {
                isLeaf && flip < 0 -> Color.parseColor("#8A5A2B")
                isLeaf -> Color.parseColor("#D98B3A")
                variant == "butterflies" -> Color.parseColor("#F4C2D7")
                else -> Color.parseColor("#F2A6BD")
            }
            c.drawOval(-5f, -10f, 5f, 10f, paint)
            c.restore()
        }
    }

    private fun drawSparks(
        c: Canvas, variant: String, n: Int, size: Float,
        wind: Float, t: Float, w: Float, h: Float,
    ) {
        val rising = variant == "embers" || variant == "bubbles"
        paint.color = when (variant) {
            "fireflies" -> Color.parseColor("#EAFF8F")
            "embers" -> Color.parseColor("#FF8A3D")
            "bubbles" -> Color.parseColor("#BFE9F5")
            else -> Color.parseColor("#C9B48F")
        }
        for (i in 0 until n) {
            val phase = wrap(t / (2.3f + hash(i, 9).toFloat() * 2.4f) + hash(i, 12).toFloat(), 1f)
            val y = if (rising) h * (1.04f - phase * 0.98f) else wrap(hash(i, 2) * h + t * 8f, h)
            val x = wrap(hash(i, 1) * w + phase * 100f * wind + sin(phase * 7f + i) * 14f, w)
            val r = (1f + hash(i, 3).toFloat() * 2.4f) * size
            if (variant == "bubbles") {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                c.drawCircle(x, y, r * 4f, paint)
            } else {
                paint.style = Paint.Style.FILL
                c.drawCircle(x, y, r, paint)
            }
        }
    }

    private fun drawFog(
        c: Canvas, variant: String, n: Int, size: Float,
        wind: Float, t: Float, w: Float, h: Float,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = if (variant == "fog") Color.parseColor("#E6F0F0") else Color.parseColor("#656B73")
        for (i in 0 until n) {
            val x = wrap(hash(i, 1) * w + t * wind * 14f + sin(t * 0.3 + i) * 20f, w)
            val y = if (variant == "smoke") wrap(h - t * 30f * (0.5f + hash(i, 4).toFloat()), h)
                    else h * (0.2f + hash(i, 2).toFloat() * 0.7f)
            val rx = (50f + hash(i, 3).toFloat() * 70f) * size
            val ry = (16f + hash(i, 5).toFloat() * 22f) * size
            c.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
        }
    }

    private fun drawWind(c: Canvas, n: Int, size: Float, wind: Float, t: Float, w: Float, h: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size
        paint.color = Color.parseColor("#E6F0F0")
        for (i in 0 until n) {
            val x = wrap(hash(i, 1) * w + t * 120f * wind, w)
            val y = h * (0.1f + hash(i, 2).toFloat() * 0.8f)
            c.drawLine(x, y, x + 120f * size, y - 8f, paint)
        }
    }

    private fun drawRays(c: Canvas, n: Int, size: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#FFF3C4")
        for (i in 0 until n) {
            val x = w * (0.2f + hash(i, 1).toFloat() * 0.7f)
            val path = android.graphics.Path().apply {
                moveTo(x, -20f)
                lineTo(x + 40f * size, h)
                lineTo(x + 100f * size, h)
                lineTo(x + 60f * size, -20f)
                close()
            }
            c.drawPath(path, paint)
        }
    }

    /** 站点同款确定性伪随机 */
    private fun hash(i: Int, seed: Int): Double {
        val v = sin(i * 127.1 + seed * 311.7) * 43758.5453
        return v - floor(v)
    }

    private fun wrap(v: Double, m: Float): Float = ((v % m) + m).toFloat() % m
    private fun wrap(v: Float, m: Float): Float = ((v % m) + m) % m
}
