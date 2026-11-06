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
import kotlin.math.pow
import kotlin.math.sin

/**
 * 高质量 Canvas 重绘层。
 *
 * 依据 /workspace/tmp/fx-src 里新收集的开源实现重写原来偏简陋的效果：
 * - DOOM 火焰传播 / Konfetti 的旋转彩带 / FireWorks 的升空-爆裂-拖尾三段式；
 * - Particles-Android 与 Quarks 的多景深粒子、Quarks 的确定性发射器；
 * - Aether 的体积光与柔和光晕、OGSlash 的多层缎带、pixelfx 的命中停顿分镜；
 * - 同时实现本轮新增的 14 种效果（无素材时的算法回退）。
 *
 * draw() 返回 false 时由 SceneEffectExtraRenderer 继续用旧实现绘制。
 */
class SceneEffectQualityRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    private fun fract(v: Double): Double = ((v % 1.0) + 1.0) % 1.0
    private fun rnd(i: Double, s: Double): Double = fract(sin(i * 127.1 + s * 311.7) * 43758.5453)
    private fun fade(t: Double, dur: Double): Double =
        minOf(1.0, t / 0.7, (dur - t) / 0.7).coerceAtLeast(0.0)
    private fun wrap(v: Double, m: Double): Double = ((v % m) + m) % m
    private fun alpha(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).toInt()

    /**
     * 竖屏基准尺度。h 是绘制区高度（屏幕高度的 78%）：
     * 竖屏时短边是宽度，居中元素相对又高又窄的屏幕偏小，放大 16% 补足纵向留白；
     * 横屏保持短边尺度，避免元素被拉宽。铺满型粒子仍按 w/h 归一化。
     */
    private fun baseOf(w: Double, h: Double): Double {
        val short = minOf(w, h)
        return if (h > w) short * 1.16 else short
    }

    /** 多层柔光 + 实心核，替代原来的单个实心圆 */
    private fun glowDot(
        c: Canvas, x: Double, y: Double, r: Double,
        color: Int, a: Double, layers: Int = 4
    ) {
        if (a <= 0.002) return
        for (q in layers downTo 1) {
            glow.color = color
            glow.alpha = alpha(a * 0.34 / q)
            val rr = (r * (1.0 + q * 0.85)).toFloat()
            c.drawCircle(x.toFloat(), y.toFloat(), rr, glow)
        }
        fill.color = color
        fill.alpha = alpha(a)
        c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat().coerceAtLeast(0.6f), fill)
    }

    fun draw(
        c: Canvas, cue: SceneEffectCue, t: Double, dur: Double,
        w: Double, h: Double, k: Double
    ): Boolean {
        val f = fade(t, dur)
        val wind = if (cue.wind) 1.0 else 0.0
        when (cue.kind) {
            // FIRE 保留项目最原先的 drawFire（在 ExtraRenderer 里），
            // 这里只接管新增的 DOOM_FIRE「烈焰」，避免旧火焰被重绘层挡住
            SceneEffectKind.DOOM_FIRE -> drawFlame(c, t, w, h, k, f, wind)
            SceneEffectKind.FIREWORKS -> drawFireworks(c, t, w, h, k, f, wind)
            SceneEffectKind.FOG -> drawFog(c, false, t, w, h, k, f, wind)
            SceneEffectKind.SMOKE -> drawFog(c, true, t, w, h, k, f, wind)
            SceneEffectKind.STARS -> drawStars(c, t, w, h, k, f)
            SceneEffectKind.BOKEH -> drawBokeh(c, t, w, h, k, f)
            SceneEffectKind.DUST -> drawDust(c, t, w, h, k, f, wind)
            SceneEffectKind.CONFETTI -> drawConfetti(c, t, w, h, k, f, wind)
            SceneEffectKind.BUBBLES -> drawBubbles(c, t, w, h, k, f, wind)
            SceneEffectKind.FOUNTAIN -> drawFountain(c, t, w, h, k, f)
            SceneEffectKind.RAYS -> drawRays(c, t, w, h, k, f)
            SceneEffectKind.EXPLOSION -> drawExplosion(c, t, dur, w, h, k, f)
            SceneEffectKind.SLASH -> drawSlash(c, t, dur, w, h, k, f)
            SceneEffectKind.IMPACT -> drawImpact(c, t, dur, w, h, k, f)
            SceneEffectKind.SHOCKWAVE -> drawShockwave(c, t, dur, w, h, k, f)
            SceneEffectKind.ARCANE, SceneEffectKind.MAGIC_CIRCLE -> drawArcane(c, t, dur, w, h, k, f)
            SceneEffectKind.HOLY -> drawHoly(c, t, dur, w, h, k, f)
            SceneEffectKind.SHADOW -> drawShadow(c, t, dur, w, h, k, f)
            SceneEffectKind.FROST -> drawFrost(c, t, dur, w, h, k, f)
            SceneEffectKind.PORTAL -> drawPortal(c, t, dur, w, h, k, f)
            SceneEffectKind.PROJECTILE -> drawProjectile(c, t, dur, w, h, k, f)
            SceneEffectKind.SPARK_JET -> drawSparkJet(c, t, dur, w, h, k, f)
            SceneEffectKind.RIPPLE, SceneEffectKind.WAVE -> drawRipple(c, t, dur, w, h, k, f)
            SceneEffectKind.SHIMMER -> drawShimmer(c, t, dur, w, h, k, f)
            SceneEffectKind.RED_PACKET -> drawFallingGifts(c, t, w, h, k, f, wind)
            SceneEffectKind.KOI -> drawKoi(c, t, w, h, k, f)
            else -> return false
        }
        return true
    }

    // ===== 火焰：DOOM 传播算法（TheDoomFIreEffectKMP / doom-fire-algorithm） =====
    private fun drawFlame(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        // 按屏幕尺寸动态分格：横屏列更多、竖屏列更少，每格物理宽度一致，
        // 避免横版把火焰拉成一条宽扁的带子
        val cols = (w / (22 * k)).toInt().coerceIn(24, 72)
        val rows = (h * 0.42 / (16 * k)).toInt().coerceIn(16, 40)
        val cw = w / cols
        val ch = h * 0.42 / rows
        val baseY = h * 0.9
        val palette = intArrayOf(
            Color.argb(0, 0, 0, 0), Color.argb(90, 120, 18, 8),
            Color.argb(150, 190, 38, 10), Color.argb(195, 232, 78, 18),
            Color.argb(225, 255, 138, 32), Color.argb(245, 255, 196, 88),
            Color.argb(255, 255, 244, 198)
        )
        for (col in 0 until cols) {
            // 底部热源：确定性伪随机 + 时间扰动
            val heat = 0.55 + 0.45 * rnd(col * 1.7, 3.0 + t * 0.9)
            for (row in 0 until rows) {
                val up = row.toDouble() / rows
                val flicker = sin(t * 6.0 + col * 0.7 + row * 0.9) * 0.11
                val spread = rnd(col * 2.3 + row * 7.1, 9.0 + t * 0.35) * 0.3
                var v = heat * (1.0 - up) - up * 0.22 + flicker + spread * (1.0 - up)
                v -= abs(col - cols / 2.0) / cols * 0.55
                if (v <= 0.06) continue
                val idx = (v * (palette.size - 1)).toInt().coerceIn(1, palette.size - 1)
                fill.color = palette[idx]
                fill.alpha = alpha(f * (0.35 + v * 0.65))
                val sway = sin(t * 2.1 + row * 0.5 + col * 0.2) * (up * 16 + wind * 12) * k
                val x = col * cw + sway
                val y = baseY - row * ch
                c.drawRect(
                    x.toFloat(), (y - ch).toFloat(),
                    (x + cw + 0.6).toFloat(), (y + ch * 0.25).toFloat(), fill
                )
            }
        }
        // 上升余烬
        for (i in 0 until 26) {
            val p = wrap(t * (0.35 + rnd(i.toDouble(), 5.0) * 0.5) + rnd(i.toDouble(), 1.0), 1.0)
            val x = w * (0.28 + rnd(i.toDouble(), 2.0) * 0.44) +
                sin(p * 9 + i) * 18 * k + wind * p * 40 * k
            val y = baseY - p * h * 0.7
            glowDot(c, x, y, (1.2 + rnd(i.toDouble(), 7.0) * 2.2) * k,
                Color.rgb(255, 176, 72), f * (1.0 - p) * 0.8, 3)
        }
    }

    // ===== 烟花：升空 → 爆裂 → 二次闪烁 → 拖尾消散（FireWorks / Particles-Android） =====
    private fun drawFireworks(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        val shells = 5
        val colors = intArrayOf(
            Color.rgb(255, 214, 92), Color.rgb(120, 206, 255),
            Color.rgb(255, 122, 158), Color.rgb(160, 255, 190),
            Color.rgb(206, 158, 255)
        )
        for (s in 0 until shells) {
            val period = 3.1 + rnd(s.toDouble(), 2.0) * 1.6
            val phase = wrap(t / period + rnd(s.toDouble(), 4.0), 1.0)
            val cx = w * (0.16 + rnd(s.toDouble(), 6.0) * 0.68)
            val cy = h * (0.14 + rnd(s.toDouble(), 8.0) * 0.36)
            val col = colors[s % colors.size]
            if (phase < 0.34) {
                // 升空：光头 + 三段拖尾
                val p = phase / 0.34
                val y = h * 0.92 - (h * 0.92 - cy) * p
                val x = cx - (1.0 - p) * 36 * wind * k
                for (q in 4 downTo 1) {
                    glowDot(c, x, y + q * 7 * k, (2.4 - q * 0.35) * k,
                        Color.rgb(255, 226, 170), f * 0.7 / q, 2)
                }
                glowDot(c, x, y, 3.4 * k, col, f, 4)
            } else {
                // 爆裂：主爆 + 二次闪烁 + 重力下坠
                val p = (phase - 0.34) / 0.66
                val ease = 1.0 - (1.0 - p).pow(2.6)
                val bright = (1.0 - p).pow(1.5) * (0.75 + 0.25 * sin(t * 22 + s))
                val n = 46
                for (i in 0 until n) {
                    val a = i * PI * 2 / n + rnd(s.toDouble(), 11.0) * 0.4
                    val sp = (0.55 + rnd(i + s * 97.0, 3.0) * 0.65)
                    val d = baseOf(w, h) * 0.24 * k * ease * sp
                    val x = cx + cos(a) * d + wind * p * 30 * k
                    val y = cy + sin(a) * d + p * p * h * 0.12
                    val r = (1.1 + rnd(i + s * 41.0, 7.0) * 2.3) * k * (1.0 - p * 0.6)
                    glowDot(c, x, y, r, col, f * bright, 2)
                    // 拖尾
                    line.color = col
                    line.strokeWidth = (r * 0.5).toFloat().coerceAtLeast(0.5f)
                    line.alpha = alpha(f * bright * 0.4)
                    c.drawLine(
                        x.toFloat(), y.toFloat(),
                        (x - cos(a) * r * 4).toFloat(), (y - sin(a) * r * 4).toFloat(), line
                    )
                }
                // 爆裂瞬间只留一个针尖大小的白热闪点。
                // 原先按 baseOf*0.1*k 画 glowDot 会变成一个不透明的暖黄色大实心圆，
                // 还叠 5 层光晕，既不透明又遮挡视线，和烟花散开的粒子完全不搭。
                if (p < 0.12) {
                    val flash = 1.0 - p / 0.12
                    glowDot(c, cx, cy, (4.0 + 8.0 * flash) * k,
                        Color.rgb(255, 250, 224), f * flash * 0.75, 2)
                }
            }
        }
    }

    // ===== 雾/烟：多层流动雾带 + 柔光（Aether 的柔和层叠思路） =====
    private fun drawFog(
        c: Canvas, smoke: Boolean, t: Double, w: Double, h: Double,
        k: Double, f: Double, wind: Double
    ) {
        val layers = 7
        val color = if (smoke) Color.rgb(92, 88, 86) else Color.rgb(206, 220, 232)
        for (l in 0 until layers) {
            val z = l.toDouble() / layers
            val speed = (0.16 + z * 0.4) * (1.0 + wind)
            val baseY = h * (0.18 + z * 0.72)
            for (i in 0 until 9) {
                val seed = l * 31.0 + i
                val x = wrap(
                    rnd(seed, 1.0) * (w + 420 * k) + t * speed * (60 + 90 * z) * k,
                    w + 420 * k
                ) - 210 * k
                val y = baseY + sin(t * 0.32 + seed) * (10 + 26 * z) * k
                val rx = (90 + rnd(seed, 5.0) * 150) * k * (0.8 + z * 0.9)
                val ry = (16 + rnd(seed, 7.0) * 26) * k * (0.8 + z)
                glow.color = color
                glow.alpha = alpha(f * (if (smoke) 0.055 else 0.042) * (1.0 - z * 0.4))
                for (q in 3 downTo 1) {
                    c.drawOval(
                        (x - rx * q * 0.42).toFloat(), (y - ry * q * 0.55).toFloat(),
                        (x + rx * q * 0.42).toFloat(), (y + ry * q * 0.55).toFloat(), glow
                    )
                }
            }
        }
        // 悬浮微粒，增加体积感
        for (i in 0 until 40) {
            val x = wrap(rnd(i.toDouble(), 2.0) * w + t * (8 + wind * 26) * k, w)
            val y = wrap(rnd(i.toDouble(), 9.0) * h - t * 6 * k, h)
            glowDot(c, x, y, 1.2 * k, color, f * 0.12, 2)
        }
    }

    // ===== 星空：银河带 + 闪烁十字星 =====
    private fun drawStars(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        // 斜向银河带
        for (i in 0 until 220) {
            val u = rnd(i.toDouble(), 1.0)
            val spread = (rnd(i.toDouble(), 3.0) - 0.5) * h * 0.24
            val x = u * w
            val y = h * (0.12 + u * 0.42) + spread
            fill.color = Color.rgb(214, 226, 255)
            fill.alpha = alpha(f * (0.06 + rnd(i.toDouble(), 5.0) * 0.16))
            c.drawCircle(
                x.toFloat(), y.toFloat(),
                ((0.5 + rnd(i.toDouble(), 7.0)) * k).toFloat(), fill
            )
        }
        for (i in 0 until 46) {
            val x = rnd(i.toDouble(), 11.0) * w
            val y = rnd(i.toDouble(), 13.0) * h * 0.75
            val tw = 0.35 + 0.65 * sin(t * (1.1 + rnd(i.toDouble(), 17.0)) + i).pow(2.0)
            val r = (1.4 + rnd(i.toDouble(), 19.0) * 2.4) * k
            glowDot(c, x, y, r, Color.rgb(255, 250, 226), f * tw * 0.9, 3)
            if (tw > 0.86) {
                val len = r * (3 + tw * 3)
                line.color = Color.rgb(255, 250, 226)
                line.strokeWidth = (0.7 * k).toFloat()
                line.alpha = alpha(f * (tw - 0.86) * 3)
                c.drawLine((x - len).toFloat(), y.toFloat(), (x + len).toFloat(), y.toFloat(), line)
                c.drawLine(x.toFloat(), (y - len).toFloat(), x.toFloat(), (y + len).toFloat(), line)
            }
        }
    }

    // ===== 光斑：多层柔和 bokeh + 缓慢景深漂移 =====
    private fun drawBokeh(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        val colors = intArrayOf(
            Color.rgb(255, 214, 150), Color.rgb(150, 200, 255),
            Color.rgb(255, 176, 196), Color.rgb(190, 255, 214)
        )
        for (i in 0 until 34) {
            val z = 0.3 + rnd(i.toDouble(), 2.0) * 0.7
            val x = wrap(rnd(i.toDouble(), 4.0) * w + sin(t * 0.21 + i) * 22 * k, w + 120 * k) - 60 * k
            val y = wrap(rnd(i.toDouble(), 6.0) * h - t * (3 + z * 7) * k, h + 120 * k) - 60 * k
            val r = (7 + rnd(i.toDouble(), 8.0) * 26) * k * z
            val col = colors[i % colors.size]
            glow.color = col
            glow.alpha = alpha(f * 0.05)
            for (q in 5 downTo 1) {
                c.drawCircle(
                    x.toFloat(), y.toFloat(),
                    (r * (0.4 + q * 0.28)).toFloat(), glow
                )
            }
            line.color = col
            line.strokeWidth = (1.2 * k).toFloat()
            line.alpha = alpha(f * 0.16)
            c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), line)
            fill.color = Color.WHITE
            fill.alpha = alpha(f * 0.1)
            c.drawCircle((x - r * 0.3).toFloat(), (y - r * 0.3).toFloat(), (r * 0.25).toFloat(), fill)
        }
    }

    // ===== 浮尘：三景深 + 逆光高亮 =====
    private fun drawDust(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        for (i in 0 until 90) {
            val z = 0.3 + rnd(i.toDouble(), 2.0) * 0.9
            val x = wrap(
                rnd(i.toDouble(), 3.0) * w + t * (12 + 34 * z) * wind * k +
                    sin(t * 0.7 + i) * 12 * k, w + 80 * k
            ) - 40 * k
            val y = wrap(rnd(i.toDouble(), 5.0) * h + t * 5 * z * k, h + 80 * k) - 40 * k
            val lum = 0.25 + 0.75 * sin(t * 1.3 + i * 1.7).pow(2.0)
            glowDot(c, x, y, (0.8 + z * 1.6) * k, Color.rgb(255, 232, 184),
                f * lum * 0.42 * z, 2)
        }
    }

    // ===== 彩带：真正旋转的矩形切片（Konfetti 的正反面明暗） =====
    private fun drawConfetti(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        val colors = intArrayOf(
            Color.rgb(255, 96, 120), Color.rgb(255, 206, 72),
            Color.rgb(88, 196, 255), Color.rgb(146, 232, 142),
            Color.rgb(196, 142, 255), Color.rgb(255, 148, 88)
        )
        for (i in 0 until 80) {
            val z = 0.5 + rnd(i.toDouble(), 2.0) * 0.9
            val fall = t * (28 + 34 * z) * k
            val x = wrap(
                rnd(i.toDouble(), 3.0) * w + sin(t * 1.6 + i) * (16 + 22 * wind) * k,
                w + 60 * k
            ) - 30 * k
            val y = wrap(rnd(i.toDouble(), 6.0) * h + fall, h + 60 * k) - 30 * k
            // 彩带切片放大约 1.8 倍，原来只有几像素，飘落时几乎看不清
            val sw = (6 + rnd(i.toDouble(), 8.0) * 9) * k * z
            val sh = (13 + rnd(i.toDouble(), 9.0) * 16) * k * z
            val flip = abs(cos(t * 3.2 + i * 0.9))
            val col = colors[i % colors.size]
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.rotate((t * 120 * (rnd(i.toDouble(), 11.0) - 0.5) * 2 + i * 31).toFloat())
            c.scale((0.25 + flip * 0.75).toFloat(), 1f)
            fill.color = col
            fill.alpha = alpha(f * (0.45 + 0.55 * flip))
            c.drawRect(
                (-sw / 2).toFloat(), (-sh / 2).toFloat(),
                (sw / 2).toFloat(), (sh / 2).toFloat(), fill
            )
            fill.color = Color.WHITE
            fill.alpha = alpha(f * flip * 0.22)
            c.drawRect(
                (-sw / 2).toFloat(), (-sh / 2).toFloat(),
                0f, (sh / 2).toFloat(), fill
            )
            c.restore()
        }
    }

    // ===== 气泡：折射环 + 高光 + 上升摆动 =====
    private fun drawBubbles(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        for (i in 0 until 34) {
            val z = 0.4 + rnd(i.toDouble(), 2.0) * 1.1
            val p = wrap(t * (0.12 + z * 0.1) + rnd(i.toDouble(), 4.0), 1.0)
            val x = w * rnd(i.toDouble(), 6.0) +
                sin(p * 8 + i) * (10 + 18 * wind) * k
            val y = h * (1.05 - p * 1.15)
            val r = (5 + rnd(i.toDouble(), 8.0) * 18) * k * z
            glow.color = Color.rgb(190, 226, 255)
            glow.alpha = alpha(f * 0.06)
            c.drawCircle(x.toFloat(), y.toFloat(), (r * 1.25).toFloat(), glow)
            line.color = Color.rgb(214, 240, 255)
            line.strokeWidth = (1.3 * k).toFloat()
            line.alpha = alpha(f * 0.5)
            c.drawCircle(x.toFloat(), y.toFloat(), r.toFloat(), line)
            fill.color = Color.WHITE
            fill.alpha = alpha(f * 0.5)
            c.drawCircle((x - r * 0.34).toFloat(), (y - r * 0.36).toFloat(),
                (r * 0.2).toFloat(), fill)
            line.alpha = alpha(f * 0.2)
            c.drawCircle((x + r * 0.18).toFloat(), (y + r * 0.2).toFloat(),
                (r * 0.55).toFloat(), line)
        }
    }

    // ===== 喷泉：抛物线水珠 + 落地水花 =====
    private fun drawFountain(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        val cx = w * 0.5
        val base = h * 0.86
        for (i in 0 until 74) {
            val p = wrap(t * (0.55 + rnd(i.toDouble(), 2.0) * 0.5) + rnd(i.toDouble(), 5.0), 1.0)
            val a = -PI / 2 + (rnd(i.toDouble(), 7.0) - 0.5) * 1.15
            val v = baseOf(w, h) * (0.32 + rnd(i.toDouble(), 9.0) * 0.22) * k
            val x = cx + cos(a) * v * p
            val y = base + sin(a) * v * p + 0.5 * v * 1.5 * p * p
            if (y > base + 8 * k) continue
            glowDot(c, x, y, (1.1 + rnd(i.toDouble(), 11.0) * 2.1) * k,
                Color.rgb(186, 226, 255), f * (1.0 - p * 0.6) * 0.8, 2)
        }
        for (q in 0 until 3) {
            val rr = baseOf(w, h) * (0.05 + q * 0.05) * k *
                (0.8 + 0.2 * sin(t * 3 + q))
            line.color = Color.rgb(200, 234, 255)
            line.strokeWidth = (1.6 * k).toFloat()
            line.alpha = alpha(f * 0.22 / (q + 1))
            c.drawOval(
                (cx - rr * 2).toFloat(), (base - rr * 0.22).toFloat(),
                (cx + rr * 2).toFloat(), (base + rr * 0.22).toFloat(), line
            )
        }
    }

    // ===== 体积光：多道梯形光束 + 光尘（Aether / Shady 的分层思路） =====
    private fun drawRays(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 6) {
            val x0 = w * (0.08 + i * 0.17) + sin(t * 0.24 + i) * 14 * k
            val topW = (14 + rnd(i.toDouble(), 3.0) * 22) * k
            val botW = topW * (3.2 + rnd(i.toDouble(), 5.0) * 2.4)
            val pulse = 0.55 + 0.45 * sin(t * 0.7 + i * 1.3).pow(2.0)
            path.reset()
            path.moveTo((x0 - topW).toFloat(), 0f)
            path.lineTo((x0 + topW).toFloat(), 0f)
            path.lineTo((x0 + topW + botW).toFloat(), h.toFloat())
            path.lineTo((x0 - topW - botW * 0.35).toFloat(), h.toFloat())
            path.close()
            fill.color = Color.rgb(255, 244, 208)
            fill.alpha = alpha(f * 0.035 * pulse)
            c.drawPath(path, fill)
            fill.alpha = alpha(f * 0.02 * pulse)
            c.drawPath(path, fill)
        }
        for (i in 0 until 44) {
            val x = wrap(rnd(i.toDouble(), 2.0) * w + sin(t * 0.4 + i) * 18 * k, w)
            val y = wrap(rnd(i.toDouble(), 6.0) * h - t * 7 * k, h)
            glowDot(c, x, y, 1.1 * k, Color.rgb(255, 240, 200), f * 0.2, 2)
        }
    }

    // ===== 爆发类：震屏 + 分层闪光 + 带拖尾碎片（pixelfx act.hits 分镜） =====
    private fun shake(c: Canvas, p: Double, k: Double, power: Double) {
        val amp = (1.0 - p).coerceAtLeast(0.0) * power * k
        c.save()
        c.translate(
            (sin(p * 71.0) * amp).toFloat(),
            (cos(p * 53.0) * amp * 0.7).toFloat()
        )
    }

    private fun flash(c: Canvas, w: Double, h: Double, a: Double, color: Int) {
        if (a <= 0.002) return
        fill.color = color
        fill.alpha = alpha(a)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), fill)
    }

    private fun drawExplosion(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val cy = h * 0.45
        val unit = baseOf(w, h) * 0.26 * k
        val a = (1.0 - p).pow(1.3) * f
        flash(c, w, h, a * 0.32 * (1.0 - p * 4).coerceAtLeast(0.0),
            Color.rgb(255, 238, 200))
        shake(c, p, k, 16.0)
        // 热核（多层）
        val core = unit * (0.42 + p * 1.25)
        glowDot(c, cx, cy, core, Color.rgb(255, 148, 54), a * 0.9, 5)
        glowDot(c, cx, cy, core * 0.5, Color.rgb(255, 246, 214), a, 4)
        // 三层冲击环，粗细随时间收细
        for (ring in 0 until 3) {
            val rp = ((p - ring * 0.09) / (1.0 - ring * 0.09)).coerceIn(0.0, 1.0)
            if (rp <= 0.0) continue
            line.color = Color.rgb(255, (186 - ring * 34).coerceIn(0, 255), 92)
            line.strokeWidth = ((9 - ring * 2) * k * (1.0 - rp) + 1).toFloat()
            line.alpha = alpha(a * (1.0 - rp) * 0.85)
            c.drawCircle(cx.toFloat(), cy.toFloat(),
                (unit * (0.28 + rp * 2.7)).toFloat(), line)
        }
        // 碎片：带方向拖尾、重力下坠
        for (i in 0 until 46) {
            val ang = rnd(i.toDouble(), 7.0) * PI * 2
            val sp = 0.8 + rnd(i + 31.0, 3.0) * 1.4
            val d = unit * (0.3 + p * sp * 2.3)
            val x = cx + cos(ang) * d
            val y = cy + sin(ang) * d + p * p * unit * 0.85
            val r = (1.4 + rnd(i + 5.0, 9.0) * 3.4) * k * (1.0 - p * 0.6)
            line.color = Color.rgb(255, (160 + rnd(i.toDouble(), 13.0) * 90).toInt(), 70)
            line.strokeWidth = (r * 0.8).toFloat().coerceAtLeast(0.6f)
            line.alpha = alpha(a * 0.6)
            c.drawLine(
                (x - cos(ang) * r * 4).toFloat(), (y - sin(ang) * r * 4 - r * 2).toFloat(),
                x.toFloat(), y.toFloat(), line
            )
            glowDot(c, x, y, r, Color.rgb(255, 204, 110), a, 2)
        }
        // 上浮烟团
        for (i in 0 until 9) {
            val sx = cx + (rnd(i.toDouble(), 3.0) - 0.5) * unit * 1.8
            val sy = cy - p * unit * (0.4 + rnd(i + 9.0, 5.0))
            glowDot(c, sx, sy, unit * (0.2 + p * 0.45),
                Color.rgb(104, 98, 94), p * f * 0.12, 3)
        }
        c.restore()
    }

    private fun drawSlash(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val cy = h * 0.45
        val span = baseOf(w, h) * 0.44 * k
        val grow = minOf(1.0, p / 0.24)
        val a = minOf(1.0, p / 0.05, (1.0 - p) / 0.38).coerceAtLeast(0.0) * f
        shake(c, p, k, 7.0)
        // OGSlash：Catmull-Rom 式二次曲线 + 尖端收细，四层描边
        for (layer in 0 until 4) {
            path.reset()
            path.moveTo((cx - span).toFloat(), (cy + span * 0.55).toFloat())
            path.quadTo(
                (cx - span * 0.1).toFloat(), (cy - span * 0.68).toFloat(),
                (cx + span * grow).toFloat(), (cy - span * 0.6 * grow).toFloat()
            )
            val widths = doubleArrayOf(34.0, 16.0, 6.0, 2.0)
            val colors = intArrayOf(
                Color.rgb(96, 168, 255), Color.rgb(150, 215, 255),
                Color.rgb(214, 244, 255), Color.WHITE
            )
            line.strokeWidth = (widths[layer] * k).toFloat()
            line.color = colors[layer]
            line.alpha = alpha(a * (0.22 + layer * 0.26))
            c.drawPath(path, line)
        }
        // 速度线
        for (i in 0 until 26) {
            val yy = cy + (rnd(i.toDouble(), 11.0) - 0.5) * span * 2.3
            val len = span * (0.18 + rnd(i + 3.0, 5.0) * 0.6) * grow
            val sx = cx - span * 1.3 + rnd(i.toDouble(), 5.0) * span
            line.strokeWidth = (1.1 + rnd(i.toDouble(), 9.0) * 1.4).toFloat() * k.toFloat()
            line.color = Color.WHITE
            line.alpha = alpha(a * 0.4)
            c.drawLine(sx.toFloat(), yy.toFloat(),
                (sx + len).toFloat(), (yy - len * 0.34).toFloat(), line)
        }
        // 命中星芒 + 0.14s 命中停顿感（亮度骤升）
        if (p > 0.2) {
            val hx = cx + span * 0.55
            val hy = cy - span * 0.34
            val rr = span * (0.1 + (p - 0.2) * 0.55)
            glowDot(c, hx, hy, rr * 0.36, Color.rgb(255, 250, 224), a, 5)
            for (i in 0 until 12) {
                val ang = i * PI / 6
                line.strokeWidth = (2.4 * k).toFloat()
                line.color = Color.rgb(255, 246, 214)
                line.alpha = alpha(a * 0.85)
                c.drawLine(hx.toFloat(), hy.toFloat(),
                    (hx + cos(ang) * rr).toFloat(), (hy + sin(ang) * rr).toFloat(), line)
            }
        }
        c.restore()
    }

    private fun drawImpact(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val cy = h * 0.45
        val span = baseOf(w, h) * 0.5 * k
        val a = minOf(1.0, p / 0.04, (1.0 - p) / 0.34).coerceAtLeast(0.0) * f
        flash(c, w, h, a * 0.18 * (1.0 - p * 5).coerceAtLeast(0.0), Color.WHITE)
        shake(c, p, k, 12.0)
        // 集中线：内外两端随时间收拢，粗细交替
        for (i in 0 until 34) {
            val ang = i * PI * 2 / 34
            val inner = span * (0.1 + p * 0.16)
            val outer = span * (0.72 - p * 0.2) * (0.7 + rnd(i.toDouble(), 2.0) * 0.55)
            line.strokeWidth = ((1.2 + rnd(i + 2.0, 5.0) * 3.4) * k).toFloat()
            line.color = if (i % 3 == 0) Color.WHITE else Color.rgb(255, 236, 190)
            line.alpha = alpha(a * 0.72)
            c.drawLine(
                (cx + cos(ang) * outer).toFloat(), (cy + sin(ang) * outer).toFloat(),
                (cx + cos(ang) * inner).toFloat(), (cy + sin(ang) * inner).toFloat(), line
            )
        }
        glowDot(c, cx, cy, span * (0.1 + p * 0.14), Color.rgb(255, 244, 208), a, 6)
        // 二重冲击环
        for (q in 0 until 2) {
            val rp = ((p - q * 0.12) / (1.0 - q * 0.12)).coerceIn(0.0, 1.0)
            if (rp <= 0.0) continue
            line.color = Color.rgb(255, 226, 168)
            line.strokeWidth = ((6 - q * 2) * k * (1.0 - rp) + 1).toFloat()
            line.alpha = alpha(a * (1.0 - rp) * 0.7)
            c.drawCircle(cx.toFloat(), cy.toFloat(),
                (span * (0.12 + rp * 0.7)).toFloat(), line)
        }
        c.restore()
    }

    private fun drawShockwave(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val cy = h * 0.45
        val span = baseOf(w, h) * 0.5 * k
        val a = (1.0 - p).pow(1.2) * f
        flash(c, w, h, a * 0.12, Color.rgb(226, 242, 255))
        shake(c, p, k, 9.0)
        for (ring in 0 until 5) {
            val rp = ((p - ring * 0.08) / (1.0 - ring * 0.08)).coerceIn(0.0, 1.0)
            if (rp <= 0.0) continue
            line.color = if (ring % 2 == 0) Color.rgb(206, 236, 255) else Color.WHITE
            line.strokeWidth = ((12 - ring * 1.8) * k * (1.0 - rp) + 1).toFloat()
            line.alpha = alpha(a * (1.0 - rp) * 0.8)
            c.drawCircle(cx.toFloat(), cy.toFloat(),
                (span * (0.12 + rp * 1.65)).toFloat(), line)
        }
        glowDot(c, cx, cy, span * 0.1 * (1.0 - p), Color.WHITE, a * 0.8, 4)
        c.restore()
    }

    // ===== 新增效果：法术 / 圣光 / 暗影 / 冰霜 / 传送门 / 飞矢 / 火花 / 涟漪 / 扫光 / 落物 / 锦鲤 =====
    private fun drawArcane(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val cy = h * 0.45
        val r = baseOf(w, h) * 0.26 * k
        val a = minOf(1.0, p / 0.1, (1.0 - p) / 0.3).coerceAtLeast(0.0) * f
        for (ring in 0 until 3) {
            val rr = r * (0.6 + ring * 0.35) * (0.6 + p * 0.7)
            line.color = Color.rgb(178, 132, 255)
            line.strokeWidth = (2.2 * k).toFloat()
            line.alpha = alpha(a * 0.7)
            c.drawCircle(cx.toFloat(), cy.toFloat(), rr.toFloat(), line)
            for (i in 0 until 12) {
                val ang = i * PI / 6 + t * (if (ring % 2 == 0) 1.1 else -0.8)
                val x = cx + cos(ang) * rr
                val y = cy + sin(ang) * rr
                glowDot(c, x, y, 2.2 * k, Color.rgb(216, 186, 255), a, 2)
            }
        }
        glowDot(c, cx, cy, r * 0.3 * (1.0 + p), Color.rgb(226, 202, 255), a * 0.8, 5)
    }

    private fun drawHoly(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val a = minOf(1.0, p / 0.12, (1.0 - p) / 0.35).coerceAtLeast(0.0) * f
        path.reset()
        // 光柱宽度按屏幕宽度比例，横屏不会变成一根细线
        val top = w * 0.11
        val bottom = w * 0.34
        path.moveTo((cx - top).toFloat(), 0f)
        path.lineTo((cx + top).toFloat(), 0f)
        path.lineTo((cx + bottom).toFloat(), h.toFloat())
        path.lineTo((cx - bottom).toFloat(), h.toFloat())
        path.close()
        fill.color = Color.rgb(255, 246, 206)
        fill.alpha = alpha(a * 0.16)
        c.drawPath(path, fill)
        for (i in 0 until 40) {
            val y = wrap(rnd(i.toDouble(), 2.0) * h - t * 46 * k, h)
            val x = cx + (rnd(i.toDouble(), 5.0) - 0.5) * w * 0.55 * (y / h)
            glowDot(c, x, y, (1.2 + rnd(i.toDouble(), 7.0) * 2) * k,
                Color.rgb(255, 240, 190), a * 0.7, 2)
        }
    }

    private fun drawShadow(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val a = minOf(1.0, p / 0.12, (1.0 - p) / 0.3).coerceAtLeast(0.0) * f
        for (i in 0 until 26) {
            val ang = rnd(i.toDouble(), 3.0) * PI * 2
            val d = baseOf(w, h) * (0.12 + p * 0.42) * k * (0.4 + rnd(i.toDouble(), 7.0))
            val x = w * 0.5 + cos(ang + t * 0.4) * d
            val y = h * 0.45 + sin(ang + t * 0.4) * d * 0.7
            glowDot(c, x, y, (8 + rnd(i.toDouble(), 9.0) * 22) * k,
                Color.rgb(38, 24, 56), a * 0.4, 3)
        }
        flash(c, w, h, a * 0.1, Color.rgb(24, 12, 36))
    }

    private fun drawFrost(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val a = minOf(1.0, p / 0.1, (1.0 - p) / 0.3).coerceAtLeast(0.0) * f
        val cx = w * 0.5
        val cy = h * 0.45
        for (arm in 0 until 12) {
            val ang = arm * PI / 6
            val len = baseOf(w, h) * (0.12 + p * 0.34) * k
            line.color = Color.rgb(198, 236, 255)
            line.strokeWidth = (2.4 * k * (1.0 - p * 0.5)).toFloat()
            line.alpha = alpha(a * 0.8)
            c.drawLine(cx.toFloat(), cy.toFloat(),
                (cx + cos(ang) * len).toFloat(), (cy + sin(ang) * len).toFloat(), line)
            for (b in 1 until 4) {
                val bx = cx + cos(ang) * len * b / 4
                val by = cy + sin(ang) * len * b / 4
                val bl = len * 0.16
                line.alpha = alpha(a * 0.55)
                c.drawLine(bx.toFloat(), by.toFloat(),
                    (bx + cos(ang + 0.7) * bl).toFloat(),
                    (by + sin(ang + 0.7) * bl).toFloat(), line)
                c.drawLine(bx.toFloat(), by.toFloat(),
                    (bx + cos(ang - 0.7) * bl).toFloat(),
                    (by + sin(ang - 0.7) * bl).toFloat(), line)
            }
        }
        glowDot(c, cx, cy, 12 * k, Color.rgb(230, 248, 255), a, 4)
    }

    private fun drawPortal(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val cx = w * 0.5
        val cy = h * 0.45
        val rr = baseOf(w, h) * 0.24 * k * (0.6 + minOf(1.0, p * 3) * 0.4)
        val a = minOf(1.0, p / 0.12, (1.0 - p) / 0.3).coerceAtLeast(0.0) * f
        for (q in 6 downTo 1) {
            line.color = Color.rgb(158, 108, 255)
            line.strokeWidth = (2 + q * 1.2).toFloat() * k.toFloat()
            line.alpha = alpha(a * 0.16)
            c.drawOval(
                (cx - rr - q * 5 * k).toFloat(), (cy - rr * 1.4 - q * 7 * k).toFloat(),
                (cx + rr + q * 5 * k).toFloat(), (cy + rr * 1.4 + q * 7 * k).toFloat(), line
            )
        }
        glowDot(c, cx, cy, rr * 0.8, Color.rgb(76, 40, 140), a * 0.7, 5)
        for (i in 0 until 24) {
            val ang = rnd(i.toDouble(), 2.0) * PI * 2 + t * 1.4
            val d = rr * (0.7 + rnd(i.toDouble(), 6.0) * 0.6)
            glowDot(c, cx + cos(ang) * d, cy + sin(ang) * d * 1.4,
                1.8 * k, Color.rgb(216, 186, 255), a * 0.8, 2)
        }
    }

    private fun drawProjectile(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val a = minOf(1.0, p / 0.06, (1.0 - p) / 0.25).coerceAtLeast(0.0) * f
        for (i in 0 until 7) {
            val y = h * (0.22 + rnd(i.toDouble(), 3.0) * 0.5)
            val x = -w * 0.15 + p * w * 1.3 * (0.8 + rnd(i.toDouble(), 5.0) * 0.4) -
                i * w * 0.07
            glowDot(c, x, y, 3.2 * k, Color.rgb(255, 226, 158), a, 3)
            line.color = Color.rgb(255, 198, 110)
            line.strokeWidth = (2.6 * k).toFloat()
            line.alpha = alpha(a * 0.45)
            c.drawLine((x - 58 * k).toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), line)
        }
    }

    private fun drawSparkJet(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = (t / dur).coerceIn(0.0, 1.0)
        val a = minOf(1.0, p / 0.08, (1.0 - p) / 0.3).coerceAtLeast(0.0) * f
        val ox = w * 0.28
        val oy = h * 0.6
        for (i in 0 until 64) {
            val ang = -0.5 + (rnd(i.toDouble(), 2.0) - 0.5) * 1.3
            val sp = (0.4 + rnd(i.toDouble(), 6.0)) * baseOf(w, h) * 0.55 * k
            val q = wrap(p * (1.0 + rnd(i.toDouble(), 8.0) * 0.6), 1.0)
            val x = ox + cos(ang) * sp * q
            val y = oy + sin(ang) * sp * q + q * q * h * 0.2
            glowDot(c, x, y, (1 + rnd(i.toDouble(), 9.0) * 2) * k,
                Color.rgb(255, 196, 96), a * (1.0 - q), 2)
            line.color = Color.rgb(255, 168, 64)
            line.strokeWidth = (1.1 * k).toFloat()
            line.alpha = alpha(a * (1.0 - q) * 0.5)
            c.drawLine((x - cos(ang) * 9 * k).toFloat(), (y - sin(ang) * 9 * k).toFloat(),
                x.toFloat(), y.toFloat(), line)
        }
    }

    private fun drawRipple(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val tail = minOf(1.0, (dur - t) / 0.7).coerceAtLeast(0.0)
        val cx = w * 0.5
        val cy = h * 0.62
        for (ring in 0 until 5) {
            val rp = wrap(t * 0.55 + ring * 0.2, 1.0)
            val rr = baseOf(w, h) * 0.6 * k * rp
            line.color = Color.rgb(196, 232, 255)
            line.strokeWidth = ((3.5 - ring * 0.5) * k * (1.0 - rp)).toFloat().coerceAtLeast(0.5f)
            line.alpha = alpha(f * tail * (1.0 - rp) * 0.55)
            c.drawOval(
                (cx - rr).toFloat(), (cy - rr * 0.26).toFloat(),
                (cx + rr).toFloat(), (cy + rr * 0.26).toFloat(), line
            )
        }
        // 水面横向波纹
        for (i in 0 until 8) {
            val y = cy + (i - 4) * 9 * k
            val off = sin(t * 1.6 + i * 0.7) * 16 * k
            line.color = Color.rgb(166, 214, 248)
            line.strokeWidth = (1.2 * k).toFloat()
            line.alpha = alpha(f * tail * 0.16)
            c.drawLine((w * 0.12 + off).toFloat(), y.toFloat(),
                (w * 0.88 + off).toFloat(), y.toFloat(), line)
        }
    }

    private fun drawShimmer(
        c: Canvas, t: Double, dur: Double, w: Double, h: Double, k: Double, f: Double
    ) {
        val p = wrap(t / 2.2, 1.0)
        val x = -w * 0.4 + p * w * 1.8
        path.reset()
        path.moveTo(x.toFloat(), 0f)
        val band = w * 0.13
        path.lineTo((x + band).toFloat(), 0f)
        path.lineTo((x + band * 2.7).toFloat(), h.toFloat())
        path.lineTo((x + band * 1.7).toFloat(), h.toFloat())
        path.close()
        fill.color = Color.WHITE
        fill.alpha = alpha(f * 0.2 * sin(p * PI))
        c.drawPath(path, fill)
        fill.alpha = alpha(f * 0.1 * sin(p * PI))
        c.drawPath(path, fill)
    }

    private fun drawFallingGifts(
        c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double, wind: Double
    ) {
        for (i in 0 until 26) {
            val z = 0.5 + rnd(i.toDouble(), 2.0) * 0.7
            val y = wrap(rnd(i.toDouble(), 4.0) * h + t * (46 + 40 * z) * k, h + 80 * k) - 40 * k
            val x = wrap(rnd(i.toDouble(), 6.0) * w + sin(t * 1.2 + i) * 20 * k +
                wind * t * 22 * k, w + 80 * k) - 40 * k
            val sw = 20 * k * z
            val sh = 27 * k * z
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.rotate((sin(t * 1.6 + i) * 14).toFloat())
            fill.color = Color.rgb(206, 42, 52)
            fill.alpha = alpha(f * 0.85)
            c.drawRect((-sw / 2).toFloat(), (-sh / 2).toFloat(),
                (sw / 2).toFloat(), (sh / 2).toFloat(), fill)
            fill.color = Color.rgb(255, 212, 108)
            fill.alpha = alpha(f * 0.9)
            c.drawRect((-sw / 2).toFloat(), (-sh * 0.12).toFloat(),
                (sw / 2).toFloat(), (sh * 0.12).toFloat(), fill)
            c.restore()
        }
    }

    private fun drawKoi(c: Canvas, t: Double, w: Double, h: Double, k: Double, f: Double) {
        for (i in 0 until 5) {
            val z = 0.6 + rnd(i.toDouble(), 2.0) * 0.6
            val phase = t * (0.24 + z * 0.12) + rnd(i.toDouble(), 5.0) * PI * 2
            val x = w * 0.5 + cos(phase) * w * 0.32
            val y = h * (0.55 + rnd(i.toDouble(), 7.0) * 0.3) + sin(phase * 1.7) * h * 0.08
            val ang = (-sin(phase) * 40).toFloat()
            val len = 34 * k * z
            c.save()
            c.translate(x.toFloat(), y.toFloat())
            c.rotate(ang)
            fill.color = if (i % 2 == 0) Color.rgb(244, 132, 76) else Color.rgb(246, 240, 226)
            fill.alpha = alpha(f * 0.75)
            c.drawOval((-len / 2).toFloat(), (-len * 0.2).toFloat(),
                (len / 2).toFloat(), (len * 0.2).toFloat(), fill)
            path.reset()
            path.moveTo((-len / 2).toFloat(), 0f)
            path.lineTo((-len * 0.78).toFloat(), (-len * 0.24).toFloat())
            path.lineTo((-len * 0.78).toFloat(), (len * 0.24).toFloat())
            path.close()
            c.drawPath(path, fill)
            c.restore()
            line.color = Color.rgb(190, 226, 248)
            line.strokeWidth = (1.2 * k).toFloat()
            line.alpha = alpha(f * 0.2)
            c.drawOval(
                (x - len * 1.1).toFloat(), (y - len * 0.24).toFloat(),
                (x + len * 1.1).toFloat(), (y + len * 0.24).toFloat(), line
            )
        }
    }
}
