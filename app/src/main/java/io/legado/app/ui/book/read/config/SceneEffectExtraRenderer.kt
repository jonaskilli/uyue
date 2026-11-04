package io.legado.app.ui.book.read.config

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * 新增实体类特效绘制器。
 *
 * 复刻来源（两类，均只取算法与形状思路，用 Android Canvas 独立实现）：
 * 1. tftgamer 原版 Cn() ambient 分支（fog/smoke/rays/bubbles/dust）、
 *    Xg() 蝴蝶翅膀、Kg() 漫画符号；
 * 2. tsParticles（MIT）官方预设里适合小说的效果类型：stars / meteors /
 *    fireworks / fire / confetti / fountain / bokeh，用通用粒子公式实现。
 *
 * 不含遮罩、镜头、色调、人物立绘操作类（按要求暂不添加）。
 */
class SceneEffectExtraRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val typo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val typoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        strokeJoin = Paint.Join.ROUND
    }

    /** 站点 qt(r,t) 同款确定性伪随机 */
    private fun qt(r: Double, t: Double): Double =
        fract(sin(r * 127.1 + t * 311.7) * 43758.5453)

    private fun dt(r: Double): Double = fract(sin(r * 127.1) * 43758.5453)
    private fun fract(v: Double): Double = ((v % 1.0) + 1.0) % 1.0
    private fun wrap(v: Double, m: Double): Double = ((v % m) + m) % m
    private fun fade(t: Double, dur: Double): Double =
        minOf(1.0, t / 0.6, (dur - t) / 0.7).coerceAtLeast(0.0)

    /** 站点 Cn() ambient 的公共漂移量：返回 [x, y, p, m, g, sway] */
    private fun ambientBase(
        i: Int, t: Double, w: Double, h: Double, k: Double,
        wind: Double, rising: Boolean
    ): DoubleArray {
        val p = (i * 73 % 997) / 997.0
        val m = 0.35 + (i % 7) / 7.0
        val g = m * k * SPARK
        val x = wrap(p * w + t * 25 * wind * m * k, w + 120 * k) - 60 * k
        val y = wrap(
            (i * 137 % 991) / 991.0 * h + t * (if (rising) -25 else 13) * m * k,
            h + 100 * k
        ) - 50 * k
        val sway = sin(t * 1.4 + i) * 16 * g
        return doubleArrayOf(x, y, p, m, g, sway)
    }

    fun draw(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ) {
        val f = fade(t, dur)
        val wind = if (cue.wind) 1.0 else 0.0
        when (cue.kind) {
            SceneEffectKind.FOG -> drawFogSmoke(c, false, t, w, h, k, f, wind)
            SceneEffectKind.SMOKE -> drawFogSmoke(c, true, t, w, h, k, f, wind)
            SceneEffectKind.BUBBLES -> drawBubbles(c, t, w, h, k, f, wind)
            SceneEffectKind.RAYS -> drawRays(c, t, w, h, k, f, wind)
            SceneEffectKind.DUST -> drawDust(c, t, w, h, k, f, wind)
            SceneEffectKind.BUTTERFLIES -> drawButterflies(c, t, w, h, k, f)
            SceneEffectKind.BIRDS_GULL -> drawBirds(c, false, 4, t, w, h, k, f)
            SceneEffectKind.BIRDS_CROW -> drawBirds(c, true, 2, t, w, h, k, f)
            SceneEffectKind.BIRD_FLOCK -> drawBirds(c, false, 14, t, w, h, k, f)
            SceneEffectKind.STARS -> drawStars(c, t, w, h, k, f)
            SceneEffectKind.METEORS -> drawMeteors(c, t, w, h, k, f)
            SceneEffectKind.FIREWORKS -> drawFireworks(c, t, w, h, k, f)
            SceneEffectKind.FIRE -> drawFire(c, t, w, h, k, f)
            SceneEffectKind.CONFETTI -> drawConfetti(c, t, w, h, k, f)
            SceneEffectKind.FOUNTAIN -> drawFountain(c, t, w, h, k, f)
            SceneEffectKind.BOKEH -> drawBokeh(c, t, w, h, k, f)
            SceneEffectKind.FEATHERS -> drawFallingShapes(c, true, t, w, h, k, f, wind)
            SceneEffectKind.DANDELION -> drawFallingShapes(c, false, t, w, h, k, f, wind)
            SceneEffectKind.SPARKLES, SceneEffectKind.QUESTION, SceneEffectKind.SWEAT,
            SceneEffectKind.HEART, SceneEffectKind.ANGER, SceneEffectKind.ZZZ,
            SceneEffectKind.LIGHTBULB, SceneEffectKind.COMIC ->
                drawSymbol(c, cue, t, dur, w, h, k, f)
            else -> Unit
        }
    }

    // ===== 站点 Cn()：fog / smoke（5 层椭圆，alpha .025 逐层叠加） =====
    private fun drawFogSmoke(
        c: Canvas, smoke: Boolean, t: Double, w: Double, h: Double,
        k: Double, f: Double, wind: Double
    ) {
        val color = if (smoke) Color.parseColor("#65717B") else Color.parseColor("#E6EDF0")
        fill.color = color
        for (i in 0 until 26) {
            val b = ambientBase(i, t, w, h, k, wind, false)
            val g = b[4] * 2.4
            val y = if (smoke) h - wrap(t * 40 + i * 63, h + 140) else b[1]
            for (q in 5 downTo 1) {
                fill.alpha = (f * 0.025 * 255).toInt()
                val rx = 65 * g * q / 5
                val ry = 24 * g * q / 5
                c.drawOval((b[0] - rx).toFloat(), (y - ry).toFloat(),
                    (b[0] + rx).toFloat(), (y + ry).toFloat(), fill)
            }
        }
    }

    // ===== 站点 Cn()：bubbles（描边圆 + 高光点） =====
    private fun drawBubbles(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        line.color = Color.parseColor("#D0EFFA")
        line.strokeWidth = (0.8 * k).toFloat()
        for (i in 0 until 34) {
            val b = ambientBase(i, t, w, h, k, wind, true)
            val x = b[0] + b[5]
            val y = b[1]
            val g = b[4]
            line.alpha = (f * 0.6 * 255).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(), (8 * g).toFloat(), line)
            fill.color = Color.WHITE
            fill.alpha = (f * 255).toInt()
            c.drawCircle((x - 3 * g).toFloat(), (y - 3 * g).toFloat(),
                (1.5 * g).toFloat(), fill)
        }
    }

    // ===== 站点 Cn()：rays（斜射光束四边形） =====
    private fun drawRays(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        fill.color = Color.parseColor("#FFECC3")
        for (i in 0 until 7) {
            val b = ambientBase(i, t, w, h, k, wind, false)
            val g = b[4]
            val path = Path().apply {
                moveTo((w * 0.7).toFloat(), -20f)
                lineTo(b[0].toFloat(), h.toFloat())
                lineTo((b[0] + 60 * g).toFloat(), h.toFloat())
                lineTo((w * 0.73).toFloat(), -20f)
                close()
            }
            fill.alpha = (f * 0.06 * 255).toInt()
            c.drawPath(path, fill)
        }
    }

    // ===== 站点 Cn()：dust（生命周期 + 横向平流 + 柔光） =====
    private fun drawDust(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        for (i in 0 until 64) {
            val b = ambientBase(i, t, w, h, k, wind, false)
            val g = b[4]
            val period = 3.4 + dt(i + 31.0) * 2
            val life = wrap(t / period + dt(i + 12.0), 1.0)
            val lum = sin(life * PI).pow(1.5)
            val dx = wrap(
                b[2] * w + life * 190 * wind * b[3] * k + sin(life * 5 + i) * 24 * k,
                w + 160 * k
            ) - 80 * k
            val dy = h * (0.88 - life * (0.32 + dt(i + 47.0) * 0.2)) +
                sin(life * 6 + i) * 12 * k
            if (i % 4 == 0) {
                val cr = (14 + life * 43) * g * 0.25
                glow.color = Color.parseColor("#CBB594")
                for (q in 5 downTo 1) {
                    glow.alpha = (f * lum * 0.035 * 255).toInt()
                    val r = cr * q / 5
                    c.drawOval((dx - r).toFloat(), (dy - r * 0.55).toFloat(),
                        (dx + r).toFloat(), (dy + r * 0.55).toFloat(), glow)
                }
            }
            fill.color = if (i % 3 == 0)
                Color.parseColor("#B99B70") else Color.parseColor("#806B50")
            fill.alpha = (f * lum * 0.65 * 255).toInt()
            c.drawOval(
                (dx - 1.1 * g).toFloat(), (dy - 0.7 * g).toFloat(),
                (dx + 1.1 * g).toFloat(), (dy + 0.7 * g).toFloat(), fill
            )
        }
    }

    // ===== 站点 Xg() 蝴蝶：wing 轮廓 + 振翅缩放 + i() 飞动路径 =====
    private fun drawButterflies(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val palettes = arrayOf(
            intArrayOf(0xFFF9DBA0.toInt(), 0xFFD98B34.toInt(), 0xFF3F2922.toInt()),
            intArrayOf(0xFFD4EEF5.toInt(), 0xFF589ABA.toInt(), 0xFF243752.toInt()),
            intArrayOf(0xFFFFF6D9.toInt(), 0xFFD8C795.toInt(), 0xFF625347.toInt())
        )
        for (i in 0 until 7) {
            val g = i % 3
            val col = palettes[g]
            val p = 0.45 + qt(i.toDouble(), 2.0) * 0.8
            val phase = qt(i.toDouble(), 3.0) * PI * 2
            val speed = t * (0.55 + qt(i.toDouble(), 7.0) * 0.4) + phase
            val x = wrap(
                qt(i.toDouble(), 5.0) * w + sin(speed) * 65 * k +
                    sin(speed * 0.47) * 30 * k, w + 100 * k
            ) - 50 * k
            val y = wrap(
                qt(i.toDouble(), 6.0) * h + cos(speed * 0.83) * 42 * k +
                    sin(speed * 2.1) * 9 * k, h + 100 * k
            ) - 50 * k
            // 站点 i() butterflies 的振翅系数
            val flap = 0.12 + 0.88 * (0.5 + 0.5 * sin(t * (16 + qt(i.toDouble(), 8.0) * 9) + phase))
            val size = (16 + qt(i.toDouble(), 4.0) * 12) * k
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.rotate((sin(speed + 0.6) * 0.4).toFloat() * 180f / PI.toFloat())
            // 左翼
            c.save()
            c.scale((-flap * 0.9).toFloat(), 1f)
            drawWing(c, col, size, f)
            c.restore()
            // 右翼
            c.save()
            c.scale((flap * 0.9).toFloat(), 1f)
            drawWing(c, col, size, f)
            c.restore()
            // 身体
            fill.color = col[2]
            fill.alpha = (f * 255).toInt()
            c.drawOval(
                (-1.4 * k).toFloat(), (-7 * k).toFloat(),
                (1.4 * k).toFloat(), (8 * k).toFloat(), fill
            )
            c.restore()
        }
    }

    private fun drawWing(c: Canvas, col: IntArray, size: Double, f: Double) {
        val path = Path().apply {
            moveTo(1f, -6f)
            cubicTo(9f, -23f, 34f, -52f, 48f, -48f)
            cubicTo(60f, -35f, 51f, -10f, 33f, 0f)
            cubicTo(48f, 8f, 44f, 32f, 27f, 42f)
            cubicTo(12f, 45f, 5f, 23f, 1f, 8f)
            close()
        }
        val s = (size / 55.0).toFloat()
        c.save()
        c.scale(s, s)
        fill.color = col[0]
        fill.alpha = (f * 235).toInt()
        c.drawPath(path, fill)
        line.color = col[2]
        line.strokeWidth = 5f
        line.alpha = (f * 210).toInt()
        c.drawPath(path, line)
        fill.color = col[1]
        fill.alpha = (f * 200).toInt()
        c.drawCircle(28f, 22f, 7f, fill)
        c.restore()
    }

    // ===== 鸟类：海鸥 / 乌鸦 / 远处鸟群（振翅剪影 + 弧线飞行） =====
    private fun drawBirds(
        c: Canvas, crow: Boolean, count: Int, t: Double,
        w: Double, h: Double, k: Double, f: Double
    ) {
        val color = if (crow) Color.parseColor("#2B2B33") else Color.parseColor("#F2F6F8")
        line.color = color
        for (i in 0 until count) {
            val phase = wrap(t / 9.0 + qt(i.toDouble(), 1.0), 1.0)
            val x = w + 120 * k - phase * (w + 320 * k)
            val baseY = h * (0.08 + qt(i.toDouble(), 2.0) * 0.5)
            val y = baseY - sin(phase * PI) * 70 * k
            val far = if (count > 8) 0.45 else 1.0
            val span = (14 + qt(i.toDouble(), 3.0) * 10) * k * far * (1.1 - phase * 0.45)
            val flap = sin(t * 7.0 + i) * 0.55
            line.strokeWidth = (2.2 * k * far).toFloat()
            line.alpha = (f * (if (crow) 0.85 else 0.7) * 255).toInt()
            val path = Path().apply {
                moveTo((x - span).toFloat(), (y - span * flap).toFloat())
                quadTo((x - span * 0.5).toFloat(), (y - span * 0.15).toFloat(),
                    x.toFloat(), y.toFloat())
                quadTo((x + span * 0.5).toFloat(), (y - span * 0.15).toFloat(),
                    (x + span).toFloat(), (y - span * flap).toFloat())
            }
            c.drawPath(path, line)
        }
    }

    // ===== tsParticles(MIT) 预设类型：stars =====
    private fun drawStars(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 90) {
            val x = qt(i.toDouble(), 1.0) * w
            val y = qt(i.toDouble(), 2.0) * h * 0.75
            val r = (0.6 + qt(i.toDouble(), 3.0) * 1.3) * k
            val tw = 0.35 + 0.65 * sin(t * 1.5 + i).pow(2.0)
            fill.color = Color.parseColor("#FFFFFF")
            fill.alpha = (f * tw * 255).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), fill)
            if (i % 9 == 0) {
                line.color = Color.parseColor("#DCEAFF")
                line.strokeWidth = (0.5 * k).toFloat()
                line.alpha = (f * tw * 0.6 * 255).toInt()
                val cr = r * 3
                c.drawLine((x - cr).toFloat(), y.toFloat(), (x + cr).toFloat(), y.toFloat(), line)
                c.drawLine(x.toFloat(), (y - cr).toFloat(), x.toFloat(), (y + cr).toFloat(), line)
            }
        }
    }

    // ===== meteors：斜向划过 + 拖尾 =====
    private fun drawMeteors(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 5) {
            val phase = wrap(t / 3.0 + qt(i.toDouble(), 1.0), 1.0)
            if (phase > 0.72) continue
            val x = w * (0.15 + qt(i.toDouble(), 2.0) * 0.6) + phase * w * 0.45
            val y = h * (0.04 + qt(i.toDouble(), 3.0) * 0.3) + phase * h * 0.32
            val len = (70 + qt(i.toDouble(), 4.0) * 90) * k
            val a = 0.55
            line.color = Color.parseColor("#CFE4FF")
            line.strokeWidth = (1.6 * k).toFloat()
            line.alpha = (f * 0.8 * 255).toInt()
            c.drawLine(
                x.toFloat(), y.toFloat(),
                (x - len * cos(a)).toFloat(), (y - len * sin(a)).toFloat(), line
            )
            fill.color = Color.WHITE
            fill.alpha = (f * 255).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(), (2.2 * k).toFloat(), fill)
        }
    }

    // ===== fireworks：多组放射爆开 =====
    private fun drawFireworks(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        val colors = intArrayOf(
            Color.parseColor("#FFD36E"), Color.parseColor("#FF7BA8"),
            Color.parseColor("#8FD3FF"), Color.parseColor("#B6FF9E")
        )
        for (burst in 0 until 3) {
            val phase = wrap(t / 2.6 + burst / 3.0, 1.0)
            if (phase > 0.7) continue
            val cx = w * (0.2 + qt(burst.toDouble(), 1.0) * 0.6)
            val cy = h * (0.12 + qt(burst.toDouble(), 2.0) * 0.38)
            val r = phase * 170 * k
            for (j in 0 until 34) {
                val a = j / 34.0 * PI * 2
                val x = cx + cos(a) * r
                val y = cy + sin(a) * r + phase * phase * 40 * k
                fill.color = colors[burst % colors.size]
                fill.alpha = (f * (1 - phase / 0.7) * 255).toInt().coerceIn(0, 255)
                c.drawCircle(x.toFloat(), y.toFloat(), ((2.6 - phase * 1.6) * k).toFloat(), fill)
            }
        }
    }

    // ===== fire：底部上升的火焰光点 =====
    private fun drawFire(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 42) {
            val phase = wrap(t / (1.2 + qt(i.toDouble(), 1.0)) + qt(i.toDouble(), 2.0), 1.0)
            val x = qt(i.toDouble(), 3.0) * w + sin(t * 3 + i) * 8 * k
            val y = h * 1.02 - phase * h * 0.42
            val r = (5 + qt(i.toDouble(), 4.0) * 9) * k * (1 - phase * 0.7)
            glow.color = Color.parseColor("#FF671F")
            glow.alpha = (f * 0.12 * 255).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(), (r * 2.1).toFloat(), glow)
            fill.color = if (phase < 0.45) Color.parseColor("#FFDC83") else Color.parseColor("#E86B2D")
            fill.alpha = (f * (1 - phase) * 220).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), fill)
        }
    }

    // ===== confetti：彩色矩形旋转飘落 =====
    private fun drawConfetti(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        val colors = intArrayOf(
            Color.parseColor("#FF7BA8"), Color.parseColor("#FFD36E"),
            Color.parseColor("#8FD3FF"), Color.parseColor("#B6FF9E"),
            Color.parseColor("#D7A6FF")
        )
        for (i in 0 until 64) {
            val x = wrap(qt(i.toDouble(), 1.0) * w + sin(t * 1.2 + i) * 26 * k, w)
            val y = wrap(qt(i.toDouble(), 2.0) * h + t * (40 + qt(i.toDouble(), 3.0) * 40) * k, h)
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.rotate((t * 120 + i * 37).toFloat())
            fill.color = colors[i % colors.size]
            fill.alpha = (f * 220).toInt()
            c.drawRect(
                (-3.5 * k).toFloat(), (-7 * k).toFloat(),
                (3.5 * k).toFloat(), (7 * k).toFloat(), fill
            )
            c.restore()
        }
    }

    // ===== fountain：抛物线水珠 =====
    private fun drawFountain(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 54) {
            val phase = wrap(t / 1.6 + qt(i.toDouble(), 1.0), 1.0)
            val vx = (qt(i.toDouble(), 2.0) - 0.5) * w * 0.42
            val x = w * 0.5 + vx * phase
            val y = h * 0.96 - 4 * phase * (1 - phase) * h * 0.6
            fill.color = Color.parseColor("#D0EFFA")
            fill.alpha = (f * (0.35 + 0.65 * sin(phase * PI)) * 255).toInt()
            c.drawCircle(x.toFloat(), y.toFloat(),
                ((1 + qt(i.toDouble(), 3.0) * 2) * k).toFloat(), fill)
        }
    }

    // ===== bokeh / ambient：大尺寸柔光斑缓慢漂浮 =====
    private fun drawBokeh(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 18) {
            val x = wrap(qt(i.toDouble(), 1.0) * w + t * 12 * k * (0.5 + qt(i.toDouble(), 2.0)), w)
            val y = wrap(qt(i.toDouble(), 3.0) * h - t * 8 * k, h)
            val r = (16 + qt(i.toDouble(), 4.0) * 26) * k
            fill.color = Color.parseColor("#FFF3D0")
            for (q in 4 downTo 1) {
                fill.alpha = (f * 0.035 * 255).toInt()
                c.drawCircle(x.toFloat(), y.toFloat(), (r * q / 4).toFloat(), fill)
            }
        }
    }

    // ===== 羽毛 / 蒲公英：站点 i() 花瓣相位公式 + 自定义形状 =====
    private fun drawFallingShapes(
        c: Canvas, feather: Boolean, t: Double, w: Double, h: Double,
        k: Double, f: Double, wind: Double
    ) {
        for (i in 0 until 34) {
            val p = 0.45 + qt(i.toDouble(), 2.0) * 0.8
            val phase = qt(i.toDouble(), 3.0) * PI * 2
            val g = 0.65 + qt(i.toDouble(), 4.0) * 0.7
            val ph = t * g + phase
            val x = wrap(
                qt(i.toDouble(), 5.0) * w + sin(ph) * 34 * p * k +
                    sin(ph * 0.43) * 16 * wind * k + t * 20 * wind * p * k,
                w
            )
            val y = wrap(qt(i.toDouble(), 6.0) * h + t * 20 * p * k, h)
            val size = (10 + qt(i.toDouble(), 8.0) * 12) * k
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.rotate((phase + sin(ph) * 0.6).toFloat() * 180f / PI.toFloat())
            c.scale((size / 24.0).toFloat(), (size / 24.0).toFloat())
            if (feather) {
                fill.color = Color.parseColor("#F4F7FA")
                fill.alpha = (f * 220).toInt()
                c.drawOval(-5f, -20f, 5f, 20f, fill)
                line.color = Color.parseColor("#B9C9D6")
                line.strokeWidth = 1.2f
                line.alpha = (f * 200).toInt()
                c.drawLine(0f, -18f, 0f, 20f, line)
            } else {
                line.color = Color.parseColor("#E8EEF2")
                line.strokeWidth = 1f
                line.alpha = (f * 200).toInt()
                for (a in 0 until 8) {
                    val ang = a / 8.0 * PI * 2
                    c.drawLine(0f, 0f, (cos(ang) * 16).toFloat(), (sin(ang) * 16 - 6).toFloat(), line)
                    fill.color = Color.WHITE
                    fill.alpha = (f * 200).toInt()
                    c.drawCircle((cos(ang) * 16).toFloat(), (sin(ang) * 16 - 6).toFloat(), 1.6f, fill)
                }
            }
            c.restore()
        }
    }

    // ===== 站点 Kg() 漫画符号 + 拟声文字 =====
    private fun drawSymbol(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double, f: Double
    ) {
        val x = w * 0.78
        val y = h * 0.24
        // 弹出回弹后淡出
        val pop = minOf(1.0, t / 0.25) * (1.0 + 0.16 * sin(t * 10) * exp(-t * 3))
        val s = k * 2.1 * pop
        val alpha = f * minOf(1.0, (dur - t) / 0.5).coerceAtLeast(0.0)
        c.save()
        c.translate(x.toFloat(), y.toFloat())
        c.scale(s.toFloat(), s.toFloat())

        when (cue.kind) {
            SceneEffectKind.HEART -> {
                fill.color = Color.parseColor("#B64B6A")
                fill.alpha = (alpha * 255).toInt()
                val p = Path().apply {
                    moveTo(0f, 26f)
                    cubicTo(-45f, -4f, -19f, -39f, 0f, -15f)
                    cubicTo(23f, -42f, 44f, -3f, 0f, 26f)
                    close()
                }
                c.drawPath(p, fill)
                line.color = Color.parseColor("#FFF0F4")
                line.strokeWidth = 2f
                line.alpha = (alpha * 220).toInt()
                c.drawPath(p, line)
            }
            SceneEffectKind.SWEAT -> {
                fill.color = Color.parseColor("#C3E4F8")
                fill.alpha = (alpha * 255).toInt()
                val p = Path().apply {
                    moveTo(0f, -28f)
                    cubicTo(-4f, -8f, -22f, 13f, -10f, 24f)
                    cubicTo(8f, 38f, 21f, 16f, 12f, 4f)
                    close()
                }
                c.drawPath(p, fill)
                line.color = Color.parseColor("#26394A")
                line.strokeWidth = 2f
                line.alpha = (alpha * 220).toInt()
                c.drawPath(p, line)
            }
            SceneEffectKind.SPARKLES -> {
                line.color = Color.parseColor("#FFF2B0")
                for ((dx, dy, n) in listOf(
                    Triple(0f, 0f, 19f), Triple(29f, -32f, 10f), Triple(-34f, 20f, 9f)
                )) {
                    for (wdt in listOf(4f, 1.7f)) {
                        line.strokeWidth = wdt
                        line.alpha = (alpha * (if (wdt == 4f) 0.18f else 1f) * 255).toInt()
                        val star = Path().apply {
                            moveTo(dx, dy - n)
                            quadTo(dx + n * 0.18f, dy - n * 0.18f, dx + n, dy)
                            quadTo(dx + n * 0.18f, dy + n * 0.18f, dx, dy + n)
                            quadTo(dx - n * 0.18f, dy + n * 0.18f, dx - n, dy)
                            quadTo(dx - n * 0.18f, dy - n * 0.18f, dx, dy - n)
                        }
                        c.drawPath(star, line)
                    }
                }
            }
            SceneEffectKind.ANGER -> {
                line.color = Color.parseColor("#E4574F")
                line.strokeWidth = 5f
                for (i in 0 until 4) {
                    line.alpha = (alpha * 235).toInt()
                    c.save()
                    c.rotate((i * 90).toFloat())
                    val p = Path().apply {
                        moveTo(-24f, -5f)
                        cubicTo(-10f, -7f, -7f, -10f, -5f, -24f)
                    }
                    c.drawPath(p, line)
                    c.restore()
                }
            }
            SceneEffectKind.LIGHTBULB -> {
                fill.color = Color.parseColor("#FFF4C8")
                fill.alpha = (alpha * 255).toInt()
                val bulb = Path().apply {
                    moveTo(-7f, 12f)
                    cubicTo(-7f, 3f, -19f, 1f, -19f, -13f)
                    cubicTo(-19f, -37f, 19f, -37f, 19f, -13f)
                    cubicTo(19f, 1f, 7f, 3f, 7f, 12f)
                    close()
                }
                c.drawPath(bulb, fill)
                line.color = Color.parseColor("#B59730")
                line.strokeWidth = 1.6f
                line.alpha = (alpha * 220).toInt()
                c.drawPath(bulb, line)
                for (a in 5..9) {
                    val ang = a / 4.0 * PI
                    c.drawLine(
                        (cos(ang) * 27).toFloat(), (-12 + sin(ang) * 27).toFloat(),
                        (cos(ang) * 33).toFloat(), (-12 + sin(ang) * 33).toFloat(), line
                    )
                }
            }
            SceneEffectKind.QUESTION, SceneEffectKind.ZZZ, SceneEffectKind.COMIC -> {
                val text = when (cue.kind) {
                    SceneEffectKind.QUESTION -> "?"
                    SceneEffectKind.ZZZ -> "Z z z"
                    else -> cue.text ?: "砰！"
                }
                val size = if (cue.kind == SceneEffectKind.COMIC) 34f else 42f
                typo.textSize = size
                typoStroke.textSize = size
                typoStroke.strokeWidth = 5f
                typo.color = if (cue.kind == SceneEffectKind.COMIC)
                    Color.parseColor("#FFFFFF") else Color.parseColor("#F5F0D8")
                typo.alpha = (alpha * 255).toInt()
                typoStroke.color = Color.parseColor("#2A2A38")
                typoStroke.alpha = (alpha * 255).toInt()
                c.drawText(text, 0f, 0f, typoStroke)
                c.drawText(text, 0f, 0f, typo)
            }
            else -> Unit
        }
        c.restore()
    }

    companion object {
        /** 与原 View 一致的点状粒子放大倍率 */
        const val SPARK = 2.6
    }
}
