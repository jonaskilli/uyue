package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.core.graphics.PathParser
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * 朗读场景特效浮层：放在「场景背景之上、台词/弹幕之下」。
 *
 * 绘制算法**全部按 tftgamer 原版移植**（见 /workspace/tftgamer-site/assets/MusicCatalogPicker-tesK0yHr.js）：
 * - 雨/雪：s_() 的离屏纹理（32x128 柔光雨滴 / 64x64 不规则雪花）+ populate/draw 运动公式；
 * - 花瓣/落叶：Xg() 的 6 种不对称花瓣与 3 种漫画秋叶纹理（渐变/卷边/叶脉），
 *   运动按 i() 的相位、摆动、翻转公式；
 * - 闪电：Mn() 中点位移生成 4 组分叉路径 + jg() 闪光曲线 + Cn() 的四层描边与全屏罩；
 * - 萤火虫/火星/风：Cn() ambient switch 对应的分支。
 *
 * 站点用 PixiJS 的「离屏 canvas 生成纹理 → Sprite 变换」，这里等价地用
 * Bitmap 预生成 + Canvas.save/translate/rotate/scale/drawBitmap，只解析一次。
 * 坐标系与尺寸统一乘 [unit]/3，让原版 1000px 逻辑坐标适配实际屏幕密度。
 */
class SceneEffectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val SIZE_BOOST = 1.3f
        /** 点状粒子（雪/萤火/火星）额外放大倍率：原版 1000px 逻辑坐标下这些只有几 px */
        private const val SPARK_BOOST = 2.6

        // ===== 原版 Xg() 的 6 种花瓣 / 6 种卷边 / 3 种落叶轮廓 =====
        private val PETAL_OUTLINES = arrayOf(
            "M 8 39 C -4 28 -28 15 -31 -7 C -35 -25 -24 -40 -10 -39 Q -4 -38 0 -32 L 5 -38 C 18 -39 30 -24 27 -9 C 24 12 11 24 8 39 Z",
            "M -12 37 C -24 18 -34 -1 -23 -24 C -15 -39 1 -44 14 -35 L 16 -28 L 23 -29 C 37 -14 31 3 17 15 C 4 25 -5 25 -12 37 Z",
            "M -29 22 C -14 20 -4 8 0 -11 C 3 -28 20 -39 34 -30 C 22 -20 23 -3 12 12 C 0 27 -18 32 -29 22 Z",
            "M -33 -5 C -12 -25 10 -32 31 -16 Q 35 -11 31 -7 L 25 -7 C 11 -7 8 5 -7 12 C -19 17 -30 10 -33 -5 Z",
            "M 12 36 C -3 24 -23 25 -29 8 C -36 -9 -23 -31 -8 -35 L -3 -30 L 3 -36 C 19 -32 28 -18 21 -4 C 13 11 5 17 12 36 Z",
            "M -28 19 C -5 16 9 -1 16 -23 Q 20 -30 28 -29 C 31 -9 16 18 -3 26 C -15 30 -23 25 -28 19 Z"
        )
        private val PETAL_SHADES = arrayOf(
            "M 8 39 C 5 21 20 5 27 -9 C 19 14 16 29 8 39 Z",
            "M -12 37 C -6 22 12 21 23 8 C 14 23 -3 30 -12 37 Z",
            "M -29 22 C -10 28 8 16 15 3 C 6 24 -16 34 -29 22 Z",
            "M -33 -5 C -20 7 -6 9 8 0 C -9 21 -27 13 -33 -5 Z",
            "M 12 36 C -1 21 7 9 16 1 C 3 20 13 28 12 36 Z",
            "M -28 19 C -7 26 13 10 23 -8 C 12 22 -12 36 -28 19 Z"
        )
        private val LEAF_OUTLINES = arrayOf(
            "M 2 41 Q -37 22 -31 -6 Q -27 -29 -4 -45 Q 4 -29 20 -20 Q 41 10 2 41 Z",
            "M 1 40 L -13 23 -30 27 -25 10 -43 -3 -21 -9 -27 -30 -9 -21 0 -49 12 -23 30 -32 25 -9 43 -3 27 12 31 27 13 24 Z",
            "M 3 39 Q -27 29 -25 9 Q -40 -3 -25 -19 Q -15 -22 -8 -42 Q 10 -35 13 -23 Q 34 -23 28 -4 Q 38 17 3 39 Z"
        )
        private const val LEAF_MAIN_VEIN = "M 2 42 Q -7 2 -3 -35"
        private const val LEAF_SIDE_VEINS =
            "M -2 24 Q 9 20 22 11 M -2 24 Q -9 20 -22 11 " +
                "M -2 6 Q 9 2 19 -7 M -2 6 Q -9 2 -19 -7 " +
                "M -2 -12 Q 9 -16 16 -25 M -2 -12 Q -9 -16 -16 -25"
        private const val LEAF_STEM = "M 2 40 Q 0 47 6 52"
        private val LEAF_COLORS = arrayOf(
            intArrayOf(0xFFE7B85B.toInt(), 0xFFC58C40.toInt(), 0xFF68422F.toInt()),
            intArrayOf(0xFFDC8051.toInt(), 0xFFBB5C3F.toInt(), 0xFF63352E.toInt()),
            intArrayOf(0xFFC6B669.toInt(), 0xFFA29450.toInt(), 0xFF535037.toInt())
        )

        private fun svg(s: String): Path = PathParser.createPathFromPathData(s)
        private fun fract(v: Double): Double = ((v % 1.0) + 1.0) % 1.0
        /** 站点 qt(r,t) */
        private fun qt(r: Double, t: Double): Double = fract(sin(r * 127.1 + t * 311.7) * 43758.5453)
        /** 站点 dt(r) */
        private fun dt(r: Double): Double = fract(sin(r * 127.1) * 43758.5453)

        private fun bitmap(w: Int, h: Int, block: (Canvas) -> Unit): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            block(Canvas(b))
            return b
        }

        private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; this.color = color
        }
        private fun strokePaint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = color; strokeWidth = width
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        // ===== 原版 s_() 的雨纹理：32x128，scale(1,4) 后画 16 半径径向渐变 =====
        val rainTexture: Bitmap by lazy {
            bitmap(32, 128) { c ->
                c.scale(1f, 4f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        16f, 16f, 16f,
                        intArrayOf(
                            Color.argb(242, 240, 247, 255),
                            Color.argb(166, 240, 247, 255),
                            Color.argb(0, 240, 247, 255)
                        ),
                        floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP
                    )
                }
                c.drawRect(0f, 0f, 32f, 32f, p)
            }
        }

        // ===== 原版 s_() 的 4 种雪纹理：3 张 16 边形不规则雪花 + 1 张柔焦圆 =====
        val snowTextures: List<Bitmap> by lazy {
            (0..3).map { n ->
                bitmap(64, 64) { c ->
                    c.translate(32f, 32f)
                    // 4 张全是清晰的 16 边形雪花；不再使用原版那种柔焦模糊圆
                    val path = Path()
                    for (k in 0 until 16) {
                        val a = k / 16.0 * PI * 2
                        val r = 15 + qt(k.toDouble(), n + 7.0) * 8
                        val x = (cos(a) * r).toFloat()
                        val y = (sin(a) * r).toFloat()
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    // 全部纯白、完全不透明，没有任何半透明渐变或描边
                    c.drawPath(path, fillPaint(Color.WHITE))
                }
            }
        }

        // ===== 原版 Xg() 花瓣纹理：径向渐变 + 卷边阴影 + 细点 + 描边 =====
        val petalTextures: List<Bitmap> by lazy {
            PETAL_OUTLINES.mapIndexed { n, data ->
                bitmap(128, 128) { c ->
                    c.translate(64f, 64f)
                    val outline = svg(data)
                    val shade = svg(PETAL_SHADES[n])
                    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            0f, -14f, 62f,
                            intArrayOf(
                                Color.parseColor("#FFF6F5"),
                                Color.parseColor("#F9DCE3"),
                                Color.parseColor("#DEA0B6")
                            ),
                            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                        )
                    }
                    c.drawPath(outline, body)
                    c.save()
                    c.clipPath(outline)
                    c.drawPath(shade, fillPaint(Color.argb(61, 186, 120, 150)))
                    // 站点在 clip 内撒 180 个细点做纸纹
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG)
                    for (g in 0 until 90) {
                        dot.color = if (g % 3 == 0) Color.argb(11, 186, 120, 150)
                        else Color.argb(28, 255, 247, 245)
                        c.drawOval(
                            (qt(g.toDouble(), n + 1.0) * 80 - 40).toFloat(),
                            (qt(g.toDouble(), 9.0) * 88 - 44).toFloat(),
                            (qt(g.toDouble(), n + 1.0) * 80 - 40 + 1.5).toFloat(),
                            (qt(g.toDouble(), 9.0) * 88 - 44 + 0.9).toFloat(), dot
                        )
                    }
                    c.restore()
                    c.drawPath(outline, strokePaint(Color.argb(56, 184, 117, 141), 0.65f))
                }
            }
        }

        // ===== 原版 Xg() 落叶纹理：双色平涂 + 主脉/侧脉 + 描边 + 叶柄 =====
        val leafTextures: List<Bitmap> by lazy {
            LEAF_OUTLINES.mapIndexed { n, data ->
                bitmap(128, 128) { c ->
                    c.translate(64f, 64f)
                    val f = svg(data)
                    val col = LEAF_COLORS[n]
                    c.drawPath(f, fillPaint(col[0]))
                    c.save()
                    c.clipPath(f)
                    // 站点用一条曲线把右半边填深色，形成两级平涂
                    val half = Path().apply {
                        moveTo(-4f, -49f); quadTo(-10f, 1f, 2f, 43f)
                        lineTo(55f, 45f); lineTo(55f, -52f); close()
                    }
                    c.drawPath(half, fillPaint(col[1]))
                    c.drawPath(svg(LEAF_MAIN_VEIN), strokePaint(col[2], 2.3f))
                    c.drawPath(svg(LEAF_SIDE_VEINS), strokePaint(col[2], 1.5f))
                    c.restore()
                    c.drawPath(f, strokePaint(col[2], 2.4f))
                    c.drawPath(svg(LEAF_STEM), strokePaint(col[2], 2.6f))
                }
            }
        }

        // ===== 原版 Mn() 中点位移 + Bh 四组分叉闪电（归一化坐标） =====
        private data class Bolt(val x: Double, val paths: List<Pair<List<DoubleArray>, Double>>)

        private fun mn(a: DoubleArray, b: DoubleArray, seed: Double, amp: Double): List<DoubleArray> {
            var pts = listOf(a, b)
            for (n in 0 until 5) {
                val out = mutableListOf<DoubleArray>()
                out.add(pts[0])
                for (i in 1 until pts.size) {
                    val l = pts[i - 1]; val h = pts[i]
                    val d = (dt(seed + n * 73 + i * 19) - 0.5) * amp / 2.0.pow(n)
                    out.add(doubleArrayOf((l[0] + h[0]) / 2 + d, (l[1] + h[1]) / 2 + d * 0.18))
                    out.add(h)
                }
                pts = out
            }
            return pts
        }

        private val bolts: List<Bolt> by lazy {
            doubleArrayOf(0.7, 0.28, 0.55, 0.83).mapIndexed { t, r ->
                val e = mn(
                    doubleArrayOf(r, -0.025),
                    doubleArrayOf(r + (if (t % 2 == 1) 1 else -1) * 0.12, 0.48 + t * 0.025),
                    t * 137.0 + 9, 0.14
                )
                val paths = mutableListOf<Pair<List<DoubleArray>, Double>>()
                paths.add(e to 1.0)
                for (s in 0 until 4) {
                    val n = e[6 + s * 5]
                    val o = if ((s + t) % 2 == 1) 1 else -1
                    val a = doubleArrayOf(
                        n[0] + o * (0.05 + dt(t * 9.0 + s) * 0.09),
                        n[1] + 0.09 + dt(s + 18.0) * 0.09
                    )
                    val l = mn(n, a, t * 71.0 + s * 13, 0.055)
                    paths.add(l to 0.42)
                    if (s % 2 == 0) {
                        val h = l[15]
                        paths.add(
                            mn(h, doubleArrayOf(h[0] - o * 0.05, h[1] + 0.065), t * 31.0 + s, 0.025) to 0.2
                        )
                    }
                }
                Bolt(r, paths)
            }
        }

        /** 站点 jg()：闪电瞬时亮度曲线 */
        private fun jg(r: Double): Double {
            if (r < 0) return 0.0
            val a = minOf(1.0, r / 0.018) * exp(-r / 0.105)
            val e = r - 0.15
            return minOf(1.0, a + if (e > 0) 0.45 * minOf(1.0, e / 0.012) * exp(-e / 0.06) else 0.0)
        }
    }

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val extraRenderer = SceneEffectExtraRenderer()

    private var currentCue: SceneEffectCue? = null
    private var startMs = 0L
    private var lastFrameTime = 0L
    private val drawAreaRatio = 0.78f

    private val unit: Float
        get() = resources.displayMetrics.density * SIZE_BOOST *
            (minOf(width, height) / 900f).coerceIn(0.85f, 2.0f)

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (currentCue == null) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (currentCue == null) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun show(cue: SceneEffectCue) {
        val old = currentCue
        if (old != null && old.kind == cue.kind) {
            currentCue = old.copy(
                durationMs = (old.durationMs + cue.durationMs / 2).coerceAtMost(20000L)
            )
        } else {
            currentCue = cue
            startMs = SystemClock.uptimeMillis()
        }
        lastFrameTime = 0L
        invalidate()
        startFrameLoop()
    }

    fun clearSceneEffects() {
        currentCue = null
        lastFrameTime = 0L
        stopFrameLoop()
        invalidate()
    }

    /** Sprite 等价绘制：居中 → 旋转 → 缩放 → 定位 */
    private fun sprite(
        c: Canvas, bmp: Bitmap, px: Double, py: Double,
        sx: Double, sy: Double, deg: Double, alpha: Double
    ) {
        if (alpha <= 0.002) return
        c.save()
        c.translate(px.toFloat(), py.toFloat())
        c.rotate(deg.toFloat())
        c.scale(sx.toFloat(), sy.toFloat())
        bitmapPaint.alpha = (alpha.coerceIn(0.0, 1.0) * 255).toInt()
        c.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, bitmapPaint)
        c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val cue = currentCue ?: return
        val now = SystemClock.uptimeMillis()
        val t = ((now - startMs) / 1000.0).coerceAtLeast(0.0)
        val dur = cue.durationMs / 1000.0
        if (t >= dur) {
            clearSceneEffects()
            return
        }
        lastFrameTime = now
        val w = width.toDouble()
        val h = (height * drawAreaRatio).toDouble()
        if (w <= 0 || h <= 0) return
        val k = unit.toDouble() / 3.0

        when (cue.kind) {
            SceneEffectKind.RAIN -> drawRain(canvas, cue, t, dur, w, h, k)
            SceneEffectKind.SNOW -> drawSnow(canvas, cue, t, dur, w, h, k)
            SceneEffectKind.PETALS, SceneEffectKind.LEAVES ->
                drawFalling(canvas, cue, t, dur, w, h, k)
            SceneEffectKind.LIGHTNING -> drawLightning(canvas, t, dur, w, h, k, 0.75)
            SceneEffectKind.FIREFLIES -> drawFireflies(canvas, cue, t, dur, w, h, k)
            SceneEffectKind.EMBERS -> drawEmbers(canvas, cue, t, dur, w, h, k)
            SceneEffectKind.WIND -> drawWind(canvas, cue, t, dur, w, h, k)
            else -> extraRenderer.draw(canvas, cue, t, dur, w, h, k)
        }
        if (cue.lightning && cue.kind != SceneEffectKind.LIGHTNING) {
            drawLightning(canvas, t, dur, w, h, k, 0.5)
        }
        bitmapPaint.alpha = 255
        shapePaint.alpha = 255
        linePaint.alpha = 255
        glowPaint.alpha = 255
        bgPaint.alpha = 255
    }

    /** 站点 s_().draw 的淡入淡出 m */
    private fun fade(t: Double, dur: Double): Double =
        minOf(1.0, t / 0.7, (dur - t) / 0.7).coerceAtLeast(0.0)

    private fun countFor(cue: SceneEffectCue): Int {
        val lv = cue.level.coerceIn(1, 4) - 1
        return when (cue.kind) {
            SceneEffectKind.RAIN -> intArrayOf(90, 180, 340, 600)[lv]
            SceneEffectKind.SNOW -> intArrayOf(60, 110, 230, 400)[lv]
            SceneEffectKind.PETALS, SceneEffectKind.LEAVES -> intArrayOf(40, 70, 100, 140)[lv]
            SceneEffectKind.FIREFLIES, SceneEffectKind.EMBERS -> intArrayOf(20, 35, 50, 70)[lv]
            SceneEffectKind.WIND -> intArrayOf(10, 16, 24, 32)[lv]
            SceneEffectKind.LIGHTNING -> 0
            else -> 40
        }.coerceAtMost(600)
    }

    private fun windOf(cue: SceneEffectCue): Double = if (cue.wind) 1.0 else 0.0
    private fun fallOf(cue: SceneEffectCue): Double =
        doubleArrayOf(0.6, 1.0, 1.4, 1.9)[cue.level.coerceIn(1, 4) - 1]

    // ===== 雨：原版 s_().draw 的 p=rain 分支 =====
    private fun drawRain(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val m = fade(t, dur)
        val wind = windOf(cue); val fall = fallOf(cue)
        // 全屏冷色雨幕
        bgPaint.color = Color.parseColor("#233D53")
        bgPaint.alpha = (0.23 * m * 255).toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val tex = rainTexture
        val spanX = w + 160 * k
        val spanY = h + 160 * k
        for (i in 0 until countFor(cue)) {
            val big = i % 9 == 0
            val y0 = ((i * 73 + 19) % 997) / 997.0
            val wgt = if (big) 1.5 else 0.25 + (i % 7) * 0.1
            val s = 140 * wgt * wind * k
            val cc = 680 * wgt * fall * k
            val mm = sin(t * 0.65 + i * 0.13) * 14 * wgt * wind * k
            val px = wrap(y0 * spanX + t * s + mm + 1e5, spanX) - 80 * k
            val py = wrap(((i * 137) % 991) / 991.0 * spanY + t * cc, spanY) - 80 * k
            val bw = (if (big) 2.8 else 1.1) * wgt * k
            val bh = (if (big) 80 else 34) * wgt * k
            val deg = -atan2(s, cc) * 180 / PI
            val a = m * (if (big) 0.19 else 0.45) * 0.9 * 1.5
            sprite(c, tex, px, py, bw / 32.0, bh / 128.0, deg, a)
        }
    }

    // ===== 雪：原版 s_().draw 的 !p 分支 =====
    private fun drawSnow(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val m = fade(t, dur)
        val wind = windOf(cue); val fall = fallOf(cue)
        bgPaint.color = Color.parseColor("#CBD9E4")
        bgPaint.alpha = (0.09 * m * 255).toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val spanX = w + 160 * k
        val spanY = h + 160 * k
        for (i in 0 until countFor(cue)) {
            val big = qt(i.toDouble(), 10.0) > 0.95
            val p = if (big) 1.8 else 0.45 + qt(i.toDouble(), 2.0) * 0.85
            val phase = qt(i.toDouble(), 3.0) * PI * 2
            val drift = t * 24 * p * wind * k
            val sway = sin(t * (0.45 + qt(i.toDouble(), 4.0) * 0.6) + phase) * (4 + p * 9) * k
            val px = wrap(qt(i.toDouble(), 5.0) * spanX + drift + sway + 1e5, spanX) - 80 * k
            val py = wrap(
                qt(i.toDouble(), 6.0) * spanY +
                    t * (23 + qt(i.toDouble(), 7.0) * 17) * p * fall * k,
                spanY
            ) - 80 * k
            val d = (if (big) 10 + qt(i.toDouble(), 8.0) * 4
            else 2.5 + qt(i.toDouble(), 8.0) * 5) * k * SPARK_BOOST
            val bw = d
            val bh = d * (0.78 + 0.22 * sin(t * 0.8 + phase).pow(2.0))
            val deg = (phase + t * (qt(i.toDouble(), 9.0) - 0.5) * 1.1) * 180 / PI
            // 全部接近不透明，只保留淡入淡出
            val a = m * 0.95
            val tex = snowTextures[if (big) 3 else i % 3]
            sprite(c, tex, px, py, bw / 64.0, bh / 64.0, deg, a)
        }
    }

    // ===== 花瓣/落叶：原版 Xg().i() 的相位、摆动与翻转公式 =====
    private fun drawFalling(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val m = fade(t, dur)
        val isLeaf = cue.kind == SceneEffectKind.LEAVES
        val wind = windOf(cue)
        val size = k
        val pad = 180 * size
        val spanX = w + pad * 2
        val spanY = h + pad * 2
        for (i in 0 until countFor(cue)) {
            val p = 0.45 + qt(i.toDouble(), 2.0) * 0.8
            val phase = qt(i.toDouble(), 3.0) * PI * 2
            val g = 0.65 + qt(i.toDouble(), 4.0) * 0.7
            val ph = t * g + phase
            val base = size * p * (if (isLeaf) 0.34 else 0.25)
            var bx = qt(i.toDouble(), 5.0) * spanX + t * 26 * wind * p * k
            var by = qt(i.toDouble(), 6.0) * spanY
            var sx: Double; var sy: Double; var deg: Double; var tinted = false
            if (isLeaf) {
                bx += sin(ph) * 32 * p * k + sin(ph * 0.43) * 18 * wind * k
                by += t * 33 * p * k + sin(ph * 1.7) * 9 * k
                val fl = cos(ph * 1.6)
                val s = 0.18 + 0.82 * abs(fl)
                deg = (phase + t * 0.8 * g + sin(ph) * 0.65) * 180 / PI
                sx = base * s
                sy = base * (0.85 + 0.15 * sin(ph + 0.8))
                tinted = fl < 0
            } else {
                val pw = t * (0.65 + qt(i.toDouble(), 7.0) * 0.45) + phase
                bx += sin(pw) * 38 * p * k + sin(pw * 0.47) * 17 * wind * k
                by += t * (17 + qt(i.toDouble(), 8.0) * 12) * p * k + sin(pw * 1.3) * 5 * k
                deg = (phase + sin(pw) * 0.55 + sin(pw * 0.61) * 0.18) * 180 / PI
                sx = base * (0.8 + 0.2 * cos(pw))
                sy = base * (0.94 + 0.06 * sin(pw + 0.8))
            }
            val px = wrap(bx, spanX) - pad
            val py = wrap(by, spanY) - pad
            val a = m * 0.9 * (0.65 + 0.35 * minOf(1.0, p))
            val tex = if (isLeaf) leafTextures[i % 3] else petalTextures[i % 6]
            if (tinted) {
                bitmapPaint.colorFilter = android.graphics.PorterDuffColorFilter(
                    0xFFE5CFCA.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY
                )
            }
            // 站点 x 本身就是 sprite.scale（纹理 128px），不是像素尺寸
            sprite(c, tex, px, py, sx, sy, deg, a)
            if (tinted) bitmapPaint.colorFilter = null
        }
    }

    // ===== 闪电：原版 Bh 分叉路径 + jg 曲线 + Cn overlay.lightning =====
    private fun drawLightning(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, strength: Double
    ) {
        val times = doubleArrayOf(0.22, 0.43, 0.62, 0.81)
        val f = times.map { jg(t - it * dur) }
        val d = f.maxOrNull() ?: 0.0
        val p = minOf(1.0, t / 0.8, (dur - t) / 0.8).coerceAtLeast(0.0)
        val lw = k

        bgPaint.color = Color.parseColor("#0C172B")
        bgPaint.alpha = (0.82 * strength * p * (1 - d * 0.95) * 255).toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)
        bgPaint.color = Color.parseColor("#E4EFFF")
        bgPaint.alpha = (d * strength * 0.38 * 255).toInt()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val layers = arrayOf(
            doubleArrayOf(13.0, 0.045, 0xFF769BFF.toInt().toDouble()),
            doubleArrayOf(7.0, 0.12, 0xFF9CBDFF.toInt().toDouble()),
            doubleArrayOf(3.0, 0.45, 0xFFCBDFFF.toInt().toDouble()),
            doubleArrayOf(1.25, 1.0, 0xFFFFFFFF.toInt().toDouble())
        )
        for (idx in bolts.indices) {
            val g = f[idx] * strength
            if (g < 0.008) continue
            val bolt = bolts[idx]
            // 光晕 8 层椭圆
            glowPaint.color = Color.parseColor("#C8DDFF")
            for (x in 8 downTo 1) {
                glowPaint.alpha = (g * 0.025 * 255).toInt()
                c.drawOval(
                    (w * bolt.x - w * 0.21 * x / 8).toFloat(),
                    (h * 0.06 - h * 0.24 * x / 8).toFloat(),
                    (w * bolt.x + w * 0.21 * x / 8).toFloat(),
                    (h * 0.06 + h * 0.24 * x / 8).toFloat(), glowPaint
                )
            }
            for ((points, weight) in bolt.paths) {
                for (s in 1 until points.size) {
                    val a = points[s - 1]; val b = points[s]
                    val taper = 1 - s.toDouble() / points.size * 0.7
                    for (layer in layers) {
                        linePaint.color = layer[2].toInt()
                        linePaint.strokeWidth = (layer[0] * weight * taper * lw).toFloat()
                        linePaint.alpha = (g * layer[1] * 255).toInt()
                        c.drawLine(
                            (a[0] * w).toFloat(), (a[1] * h).toFloat(),
                            (b[0] * w).toFloat(), (b[1] * h).toFloat(), linePaint
                        )
                    }
                }
            }
        }
    }

    // ===== ambient：原版 Cn() 的公共变量与 fireflies/embers/wind 分支 =====
    private fun ambientBase(
        i: Int, t: Double, w: Double, h: Double, k: Double, wind: Double, rising: Boolean
    ): DoubleArray {
        val p = (i * 73 % 997) / 997.0
        val m = 0.35 + (i % 7) / 7.0
        val g = m * k
        val px = wrap(p * w + t * 25 * wind * m * k, w + 120 * k) - 60 * k
        val py = wrap(
            (i * 137 % 991) / 991.0 * h + t * (if (rising) -25 else 13) * m * k,
            h + 100 * k
        ) - 50 * k
        val v = sin(t * 1.4 + i) * 16 * g
        return doubleArrayOf(px, py, p, m, g, v)
    }

    private fun drawFireflies(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val f = fade(t, dur)
        val wind = windOf(cue)
        for (i in 0 until countFor(cue)) {
            val b = ambientBase(i, t, w, h, k, wind, false)
            val px = b[0] + b[5]; val py = b[1]; val g = b[4]
            val lum = f * (0.3 + 0.7 * sin(t * 2 + i).pow(2.0))
            glowPaint.color = Color.parseColor("#E9F3A1")
            for (q in 5 downTo 1) {
                glowPaint.alpha = (lum * 0.09 * 255).toInt()
                c.drawCircle(
                    px.toFloat(), py.toFloat(),
                    (q * 2 * g * SPARK_BOOST).toFloat(), glowPaint
                )
            }
            shapePaint.color = Color.parseColor("#E9F3A1")
            shapePaint.alpha = (lum * 255).toInt()
            c.drawCircle(px.toFloat(), py.toFloat(), (1.4 * g * SPARK_BOOST).toFloat(), shapePaint)
            shapePaint.color = Color.WHITE
            shapePaint.alpha = (lum * 0.85 * 255).toInt()
            c.drawCircle(px.toFloat(), py.toFloat(), (0.55 * g * SPARK_BOOST).toFloat(), shapePaint)
        }
    }

    private fun drawEmbers(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val f = fade(t, dur)
        val wind = windOf(cue)
        for (i in 0 until countFor(cue)) {
            val b = ambientBase(i, t, w, h, k, wind, true)
            val p = b[2]; val m = b[3]; val g = b[4]
            val period = 2.3 + dt(i + 41.0) * 2.4
            val y = wrap(t / period + dt(i + 17.0), 1.0)
            val life = minOf(1.0, y * 12) * minOf(1.0, (1 - y) * 3)
            val s = f * life * (0.75 + 0.25 * sin(t * 19 + i).pow(2.0))
            val bend = sin(y * 7 + i) * 14 * m
            val mx = wrap(p * w + y * 100 * wind * m * k + bend * k, w + 80 * k) - 40 * k
            val my = h * (1.04 - y * 0.98)
            val size = (5 + dt(i + 63.0) * 12) * g
            val tail = wind * size * 0.3 + cos(y * 7 + i) * size * 0.25
            // 三层光晕
            glowPaint.color = Color.parseColor("#FF671F")
            for (q in 3 downTo 1) {
                glowPaint.alpha = (s * 0.065 * 255).toInt()
                c.drawOval(
                    (mx - 2.4 * q * g * SPARK_BOOST * 0.7).toFloat(),
                    (my - 3.2 * q * g * SPARK_BOOST * 0.7).toFloat(),
                    (mx + 2.4 * q * g * SPARK_BOOST * 0.7).toFloat(),
                    (my + 3.2 * q * g * SPARK_BOOST * 0.7).toFloat(), glowPaint
                )
            }
            // 拖尾三段
            for (q in 3 downTo 1) {
                linePaint.color = if (q == 1) Color.parseColor("#FFB74E") else Color.parseColor("#E9511E")
                linePaint.strokeWidth =
                    ((1.9 - q * 0.35) * g * SPARK_BOOST * 0.55).toFloat()
                linePaint.alpha = (s * (1 - q * 0.22) * 255).toInt()
                c.drawLine(
                    (mx - tail * q / 3).toFloat(), (my + size * q / 3).toFloat(),
                    (mx - tail * (q - 1) / 3).toFloat(), (my + size * (q - 1) / 3).toFloat(),
                    linePaint
                )
            }
            // 菱形火星
            val path = Path().apply {
                moveTo(mx.toFloat(), (my - 2.5 * g * SPARK_BOOST * 0.75).toFloat())
                lineTo((mx + 1.1 * g * SPARK_BOOST * 0.75).toFloat(), my.toFloat())
                lineTo(mx.toFloat(), (my + 3 * g * SPARK_BOOST * 0.75).toFloat())
                lineTo((mx - 0.8 * g * SPARK_BOOST * 0.75).toFloat(), (my + g).toFloat())
                close()
            }
            shapePaint.color = if (y < 0.55) Color.parseColor("#FFDC83") else Color.parseColor("#E86B2D")
            shapePaint.alpha = (s * 255).toInt()
            c.drawPath(path, shapePaint)
            shapePaint.color = Color.parseColor("#FFF4CA")
            shapePaint.alpha = (s * (1 - y) * 255).toInt()
            c.drawCircle(
                mx.toFloat(), my.toFloat(),
                (0.95 * g * SPARK_BOOST).toFloat(), shapePaint
            )
        }
    }

    /**
     * 风：回退到上一版的 dust 粒子，只保留粒子本体。
     * 运动沿用站点 Cn() dust 分支的生命周期/亮度包络/横向平流公式；
     * 不画任何线条、波纹带、玻璃薄片或柔光晕。
     */
    private fun drawWind(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val f = fade(t, dur)
        val wind = 1.0 + windOf(cue) * 0.6
        val count = countFor(cue).coerceAtLeast(80)
        for (i in 0 until count) {
            val p = (i * 73 % 997) / 997.0
            val m = 0.35 + (i % 7) / 7.0
            val g = m * k * SPARK_BOOST * 0.7
            val period = 3.4 + dt(i + 31.0) * 2
            val b = wrap(t / period + dt(i + 12.0), 1.0)
            val lum = sin(b * PI).pow(1.5)
            val px = wrap(
                p * w + b * 190 * wind * m * k + sin(b * 5 + i) * 24 * k,
                w + 160 * k
            ) - 80 * k
            val py = h * (0.88 - b * (0.32 + dt(i + 47.0) * 0.2)) +
                sin(b * 6 + i) * 12 * k
            val ex = px + sin(b * 11 + i) * 16 * k

            shapePaint.color = if (i % 3 == 0)
                Color.parseColor("#B99B70") else Color.parseColor("#806B50")
            shapePaint.alpha = (f * lum * 0.65 * 255).toInt()
            c.drawOval(
                (ex - 1.1 * g).toFloat(), (py - 0.7 * g).toFloat(),
                (ex + 1.1 * g).toFloat(), (py + 0.7 * g).toFloat(), shapePaint
            )
        }
    }

    private fun wrap(v: Double, m: Double): Double = ((v % m) + m) % m

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            stopFrameLoop()
            lastFrameTime = 0L
        } else if (currentCue != null) {
            startFrameLoop()
        }
    }
}
