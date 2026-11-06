package io.legado.app.ui.book.read.config

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 63 种精确形态：object 与 P 绘制类都要用，定义在文件顶层 */
private enum class Shape {
    ORB, BEAM, RING, PORTAL, BOLT, SPARK, BURST, IMPLODE, FIREWORK, FIRE_RING,
    FUNNEL, FLAME, METEOR, CRYSTAL, SPIKE, SLASH, IMPACT, SHIELD, QUAKE,
    PROJECTILE, RIPPLE, BUBBLE, FOUNTAIN, WHIRL, DRIP, SMOKE,
    RAIN, SNOW, PETAL, LEAF, FIREFLY, CLOUD, FOG, AURORA, GODRAYS, GRID,
    BANG, HEALTHBAR, ZZZ, STARS_SPIN, SPIRAL,
    ROCK, VINE, BLOOM, PUDDLE,
    BAT, BIRD, BUTTERFLY, JELLY, WISP,
    CRT, DIAMOND, IRIS, STAR_IRIS, PIXEL, SHATTER,
    GEM, KEY, POTION, EGG, TENTACLE, BLACKHOLE, EMP
}

/** 源码 melee/_lib.js 的 swing：倾斜压扁椭圆 + 锥面抬升，近半宽远半窄 */
private data class Swing(
    val cx: Double, val cy: Double, val r: Double,
    val sy: Double, val rot: Double, val cone: Double,
    val a0: Double, val dir: Double
)

/**
 * 特效的参数化矢量绘制。
 *
 * 按每个特效的**名字**（而不是它所属的大类）分派到 63 种精确形态，
 * 形态依据 pixelfx 源码里每个 fx 的 meta.desc 描述确定：
 * 极光是绿紫光幕、惊叹号是弹出的「!」、滚石是巨石砸地碎裂、
 * 水母是钟形伞加触须、显像管关机是画面压成亮线再收成点……
 * 同一形态内再用 id 派生的 seed 调整配色、粒子数、方向，避免全都长一样。
 *
 * 布局以竖屏为基准：竖屏放大居中元素 16%，横屏保持短边尺度。
 */
object FxVectorRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    /**
     * 语义色系：暗 → 亮共 5 档，第 4 档一律近白（hot core）。
     * VFX 通用规则是「白热核心 + 有色外晕」，闪光/能量/高光绝不能用低亮度棕灰；
     * 棕灰只保留给土石（EARTH），灰白只给不发光烟尘（SMOKE_PAL）。
     */
    /** 刀光专用冷色系：深蓝 → 蓝 → 冰蓝 → 蓝白 → 白热核心 */
    private val BLADE = intArrayOf(0x102A5E, 0x3A78D8, 0x8FC8FF, 0xD8F0FF, 0xFFFFFF)
    private val FIRE = intArrayOf(0x5A1405, 0xB8330C, 0xF07A18, 0xFFC84A, 0xFFF6D8)
    private val ELECTRIC = intArrayOf(0x0A1A5A, 0x2050D8, 0x55B0FF, 0xB8E8FF, 0xFFFFFF)
    private val ICE = intArrayOf(0x0B2A5B, 0x1E6FB8, 0x59B6E8, 0xC8EEFF, 0xFFFFFF)
    private val HOLY = intArrayOf(0x8A5A10, 0xD89A20, 0xFFCE4C, 0xFFEBA8, 0xFFFDF0)
    private val ARCANE = intArrayOf(0x2A1150, 0x6330A8, 0xA46BE0, 0xDCC0FF, 0xFFFFFF)
    private val NATURE = intArrayOf(0x123A22, 0x2E8B57, 0x6FCF97, 0xC8F0D2, 0xFFFFFF)
    private val EARTH = intArrayOf(0x3A2E22, 0x7A6A55, 0xB9A07A, 0xE8D9BC, 0xFFF8E8)
    private val SMOKE_PAL = intArrayOf(0x1A1A24, 0x3C3C4C, 0x707080, 0xB0B0C0, 0xEEEEF6)
    private val BLOOD = intArrayOf(0x400808, 0xA01820, 0xE03840, 0xFF9AA0, 0xFFE8E8)
    private val DARK = intArrayOf(0x0A0512, 0x2A1150, 0x6330A8, 0xA46BE0, 0xE8D9FF)

    private fun rgb(hex: Int): Int =
        Color.rgb(hex shr 16 and 0xFF, hex shr 8 and 0xFF, hex and 0xFF)

    /** 配色按「形态」而不是按大类：流星、闪光、宝石绝不会分到土石棕色 */
    private fun scheme(shape: Shape): IntArray = when (shape) {
        Shape.BURST, Shape.IMPLODE, Shape.FIREWORK, Shape.FIRE_RING,
        Shape.FLAME, Shape.METEOR, Shape.PROJECTILE, Shape.IMPACT,
        Shape.SPARK -> FIRE
        Shape.SLASH -> BLADE
        Shape.BOLT, Shape.ORB, Shape.EMP, Shape.GRID, Shape.GODRAYS -> ELECTRIC
        Shape.CRYSTAL, Shape.RIPPLE, Shape.BUBBLE, Shape.FOUNTAIN,
        Shape.WHIRL, Shape.RAIN, Shape.SNOW, Shape.PETAL, Shape.LEAF,
        Shape.FIREFLY, Shape.AURORA -> ICE
        Shape.BEAM, Shape.GEM, Shape.KEY, Shape.POTION, Shape.EGG,
        Shape.BANG, Shape.HEALTHBAR, Shape.ZZZ, Shape.STARS_SPIN,
        Shape.SPIRAL, Shape.IRIS, Shape.STAR_IRIS, Shape.CRT,
        Shape.DIAMOND, Shape.PIXEL, Shape.SHATTER, Shape.SHIELD -> HOLY
        Shape.RING, Shape.PORTAL, Shape.TENTACLE, Shape.BLACKHOLE -> ARCANE
        Shape.VINE, Shape.BLOOM, Shape.PUDDLE, Shape.SPIKE -> NATURE
        Shape.ROCK, Shape.QUAKE -> EARTH
        Shape.SMOKE, Shape.FOG, Shape.CLOUD, Shape.FUNNEL -> SMOKE_PAL
        Shape.DRIP -> BLOOD
        Shape.BAT, Shape.BIRD, Shape.BUTTERFLY, Shape.JELLY, Shape.WISP -> DARK
    }.map { rgb(it) }.toIntArray()

    private fun baseOf(w: Double, h: Double): Double {
        val short = min(w, h)
        return if (h > w) short * 1.16 else short
    }

    private fun wrap(v: Double, m: Double): Double = ((v % m) + m) % m

    /** 名字 → 形态：逐条按 pixelfx 的 meta.desc 语义指定 */
    private fun shapeOf(name: String): Shape = when (name) {
        "aurora" -> Shape.AURORA
        "exclamation" -> Shape.BANG
        "bat" -> Shape.BAT
        "arcane-beam", "holy-pillar", "loot-beam", "respawn-beam",
        "sunburst", "tractor-beam" -> Shape.BEAM
        "bird" -> Shape.BIRD
        "dark-explosion" -> Shape.BLACKHOLE
        "healing-bloom" -> Shape.BLOOM
        "chain-lightning", "lightning-call", "lightning-strike" -> Shape.BOLT
        "bubbles" -> Shape.BUBBLE
        "barrel-explosion", "big-explosion", "dirt-explosion", "grenade",
        "mana-burst", "nuke", "plasma-explosion", "small-explosion" -> Shape.BURST
        "butterfly" -> Shape.BUTTERFLY
        "clouds" -> Shape.CLOUD
        "crt-off" -> Shape.CRT
        "frost-nova", "snowflake-burst" -> Shape.CRYSTAL
        "diamond-wipe" -> Shape.DIAMOND
        "bleeding", "rain-splash" -> Shape.DRIP
        "egg-hatch" -> Shape.EGG
        "emp-blast", "thunderclap" -> Shape.EMP
        "fireflies" -> Shape.FIREFLY
        "firework-burst", "firework-rocket", "flare" -> Shape.FIREWORK
        "fire-ring" -> Shape.FIRE_RING
        "flame-pillar", "flamethrower", "shadow-flames" -> Shape.FLAME
        "fog" -> Shape.FOG
        "acid-splash", "fountain", "geyser", "splash", "water-jet" -> Shape.FOUNTAIN
        "fire-tornado", "tornado" -> Shape.FUNNEL
        "collect-burst", "crystal-shard", "gem-spin", "power-star" -> Shape.GEM
        "god-rays" -> Shape.GODRAYS
        "laser-grid" -> Shape.GRID
        "health-bar-hit" -> Shape.HEALTHBAR
        "blunt-hit", "bullet-impact", "crit-hit", "hammer-smash",
        "hit-flash", "impact-frame", "uppercut" -> Shape.IMPACT
        "implosion" -> Shape.IMPLODE
        "iris" -> Shape.IRIS
        "jellyfish" -> Shape.JELLY
        "key-glint" -> Shape.KEY
        "falling-leaves" -> Shape.LEAF
        "meteor", "meteor-call", "shooting-stars", "star-fall" -> Shape.METEOR
        "arcane-orb", "ball-lightning", "energy-core", "spell-charge",
        "void-orb", "water-orb" -> Shape.ORB
        "cherry-blossoms", "feather-burst" -> Shape.PETAL
        "pixel-dissolve" -> Shape.PIXEL
        "portal", "spawn-portal" -> Shape.PORTAL
        "potion" -> Shape.POTION
        "arcane-missiles", "arrow", "arrow-shot", "bullet-tracer",
        "cannonball", "energy-disc", "homing-missile", "laser-bolt",
        "magic-missile", "pistol-shots", "plasma-shot", "ricochet",
        "rocket", "rocket-launcher" -> Shape.PROJECTILE
        "toxic-puddle" -> Shape.PUDDLE
        "earth-stomp", "ground-pound", "ground-slam", "heavy-landing",
        "quake" -> Shape.QUAKE
        "blizzard", "rain" -> Shape.RAIN
        "hex-sigil", "magic-circle-cast", "rune-circle", "time-stop" -> Shape.RING
        "ripples" -> Shape.RIPPLE
        "boulder" -> Shape.ROCK
        "shatter-death", "shatter-wipe" -> Shape.SHATTER
        "parry", "shield-block" -> Shape.SHIELD
        "claw-swipe", "dagger-flurry", "dash-strike", "double-slash",
        "heavy-cleave", "scythe-reap", "slash-wipe", "spear-sweep",
        "spin-slash", "sword-combo", "sword-slash", "thrust",
        "whip-crack" -> Shape.SLASH
        "poison-cloud", "poof", "smoke-bomb", "spore-burst" -> Shape.SMOKE
        "snow" -> Shape.SNOW
        "charge-up", "hit-spark", "zap-hit" -> Shape.SPARK
        "earth-spike", "ice-spikes", "thorns" -> Shape.SPIKE
        "confused" -> Shape.SPIRAL
        "stunned" -> Shape.STARS_SPIN
        "star-iris" -> Shape.STAR_IRIS
        "shadow-tendrils", "soul-drain" -> Shape.TENTACLE
        "root-snare", "vine-whip" -> Shape.VINE
        "blizzard-vortex", "leaf-storm", "whirlpool" -> Shape.WHIRL
        "wisp" -> Shape.WISP
        "sleep" -> Shape.ZZZ
        else -> Shape.BURST
    }

    /** 每个特效按 pixelfx 原作 meta.duration 播放，不再全体共用 1.35 秒 */
    private fun durationOf(name: String): Double = when (name) {
        "impact-frame" -> 0.3
        "energy-disc", "hit-flash", "hit-spark", "laser-bolt" -> 0.5
        "arrow", "bullet-tracer" -> 0.6
        "sword-slash", "thrust" -> 0.75
        "double-slash", "plasma-shot", "spin-slash" -> 0.8
        "uppercut", "whip-crack" -> 0.85
        "blunt-hit", "claw-swipe", "crit-hit", "zap-hit" -> 0.9
        "dagger-flurry" -> 0.95
        "water-jet" -> 1.0
        "ricochet" -> 1.1
        "rocket" -> 1.2
        "collect-burst" -> 1.35
        "bullet-impact", "exclamation", "vine-whip" -> 1.4
        "spear-sweep" -> 1.5
        "poof", "power-star" -> 1.6
        "implosion" -> 1.65
        "plasma-explosion" -> 1.7
        "arcane-missiles" -> 1.75
        "chain-lightning", "mana-burst" -> 1.8
        "arrow-shot", "heavy-cleave", "magic-missile", "thunderclap" -> 1.9
        "arcane-beam", "arcane-orb", "blizzard-vortex", "fountain", "hammer-smash", "key-glint",
        "magic-circle-cast", "meteor-call", "pixel-dissolve", "portal", "rocket-launcher",
        "rune-circle", "slash-wipe", "spell-charge", "star-iris", "stunned", "sword-combo",
        "tornado" -> 2.0
        "scythe-reap" -> 2.1
        "boulder", "dash-strike" -> 2.3
        "bat", "bird", "bleeding", "confused", "energy-core", "gem-spin", "leaf-storm",
        "loot-beam", "potion", "rain-splash", "sleep", "thorns" -> 2.4
        "respawn-beam", "splash", "star-fall" -> 2.5
        "pistol-shots" -> 2.6
        "charge-up", "root-snare" -> 2.7
        "acid-splash" -> 2.8
        "feather-burst", "laser-grid", "lightning-call", "lightning-strike", "tractor-beam",
        "water-orb" -> 3.0
        "egg-hatch" -> 3.1
        "butterfly", "crystal-shard", "ground-slam", "jellyfish", "shadow-tendrils",
        "snowflake-burst", "soul-drain", "spore-burst", "void-orb", "wisp" -> 3.2
        "healing-bloom" -> 3.3
        "earth-spike" -> 3.4
        "holy-pillar" -> 3.5
        "dirt-explosion", "firework-burst", "time-stop" -> 3.6
        "cannonball", "dark-explosion", "firework-rocket", "homing-missile", "spawn-portal" -> 3.8
        "fire-ring" -> 3.9
        "ball-lightning", "bubbles", "poison-cloud", "sunburst", "toxic-puddle" -> 4.0
        "grenade", "hex-sigil" -> 4.2
        "flare", "geyser", "meteor", "smoke-bomb" -> 5.0
        "barrel-explosion" -> 5.2
        "small-explosion" -> 5.4
        "big-explosion" -> 7.2
        "blizzard", "rain" -> 8.0
        "nuke" -> 9.0
        "cherry-blossoms", "falling-leaves", "god-rays", "shooting-stars" -> 10.0
        "aurora", "clouds", "fireflies", "fog", "snow" -> 12.0
        else -> 2.0
    }

    /** loop=false 的一次性效果：每轮尾部收干净，不像常驻氛围那样连续 */
    private val ONCE = setOf(
        "acid-splash", "arcane-missiles", "arrow-shot", "barrel-explosion", "big-explosion", "blunt-hit",
        "boulder", "bullet-impact", "cannonball", "chain-lightning", "charge-up", "claw-swipe",
        "collect-burst", "crit-hit", "dagger-flurry", "dark-explosion", "dash-strike", "dirt-explosion",
        "double-slash", "earth-spike", "egg-hatch", "exclamation", "feather-burst", "fire-ring",
        "firework-burst", "firework-rocket", "flare", "geyser", "grenade", "ground-slam",
        "hammer-smash", "healing-bloom", "heavy-cleave", "hex-sigil", "hit-flash", "hit-spark",
        "holy-pillar", "homing-missile", "impact-frame", "implosion", "lightning-call", "lightning-strike",
        "magic-circle-cast", "magic-missile", "mana-burst", "meteor", "meteor-call", "nuke",
        "pistol-shots", "plasma-explosion", "poof", "respawn-beam", "ricochet", "rocket-launcher",
        "root-snare", "scythe-reap", "shadow-tendrils", "small-explosion", "smoke-bomb", "snowflake-burst",
        "spawn-portal", "spear-sweep", "spin-slash", "splash", "spore-burst", "star-fall",
        "sword-combo", "sword-slash", "thorns", "thrust", "thunderclap", "time-stop",
        "uppercut", "vine-whip", "whip-crack", "zap-hit"
    )

    fun draw(
        c: Canvas, fxId: String, t: Double, dur: Double,
        w: Double, h: Double, k: Double, level: Int
    ): Boolean {
        if (fxId.isBlank()) return false
        val name = fxId.substringAfter("__")
        val shape = shapeOf(name)
        val seed = fxId.hashCode() and 0x7fffffff
        val base = baseOf(w, h) * (0.42 + level * 0.04)
        // 原作时长压缩到 0.7~2.8 秒一轮：瞬时类快、氛围类慢，节奏不再千篇一律
        val period = durationOf(name).coerceIn(0.7, 2.8)
        val p = wrap(t / period + (seed % 97) / 97.0, 1.0)
        // 一次性效果在每轮最后 28% 收干净，避免停在末帧或反复重播同一动作
        val onceFade = if (name in ONCE) {
            1.0 - ((p - 0.72) / 0.28).coerceIn(0.0, 1.0)
        } else 1.0
        val fade = minOf(1.0, t / 0.18, (dur - t) / 0.18)
            .coerceAtLeast(0.0) * onceFade
        P(
            c, w * 0.5, h * 0.46, base, w, h, k, p, fade, seed,
            scheme(shape), name, shape, fill, line, glow, path
        ).render()
        return true
    }

    private class P(
        val c: Canvas,
        val cx: Double, val cy: Double, val base: Double,
        val w: Double, val h: Double, val k: Double,
        val p: Double, val fade: Double, val seed: Int,
        val colors: IntArray, val name: String, val shape: Shape,
        val fill: Paint, val line: Paint, val glow: Paint, val path: Path
    ) {
        fun rnd(i: Double, s: Double): Double {
            val v = sin(i * 127.1 + (s + seed % 89) * 311.7) * 43758.5453
            return ((v % 1.0) + 1.0) % 1.0
        }

        fun wrap(v: Double, m: Double): Double = ((v % m) + m) % m

        /** 源码 fire/_lib.js pnoise：平滑一维值噪声，用于火舌摆动与宽度抖动 */
        fun pnoise(x: Double, tag: Double): Double {
            val i = kotlin.math.floor(x)
            val fr = x - i
            val u = fr * fr * (3.0 - 2.0 * fr)
            val a = rnd(i, tag)
            val b = rnd(i + 1.0, tag)
            return (a + (b - a) * u) * 2.0 - 1.0
        }

        /** 源码 lightning/_lib.js zigzag：中点位移分形折线，逐代衰减 0.55 */
        fun zigzag(
            x0: Double, y0: Double, x1: Double, y1: Double,
            jag: Double, tag: Double
        ): List<Pair<Double, Double>> {
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            var pts = mutableListOf(Pair(x0, y0), Pair(x1, y1))
            var off = len * jag
            for (gen in 0 until 4) {
                val next = ArrayList<Pair<Double, Double>>(pts.size * 2)
                next.add(pts[0])
                for (i in 0 until pts.size - 1) {
                    val (ax, ay) = pts[i]
                    val (bx, by) = pts[i + 1]
                    val l = kotlin.math.hypot(bx - ax, by - ay).coerceAtLeast(0.001)
                    val o = (rnd(i.toDouble() + gen * 17, tag) - 0.5) * 2.0 * off
                    next.add(
                        Pair(
                            (ax + bx) / 2.0 - (by - ay) / l * o,
                            (ay + by) / 2.0 + (bx - ax) / l * o
                        )
                    )
                    next.add(Pair(bx, by))
                }
                pts = next
                off *= 0.55
            }
            return pts
        }

        /** 源码 melee/_lib.js onSwing：锥面椭圆上的一点，planeR 为平面半径 */
        fun onSwing(s: Swing, u: Double, planeR: Double): Pair<Double, Double> {
            val a = s.a0 + s.dir * u
            val lx = cos(a) * planeR
            val ly = sin(a) * planeR * s.sy - s.cone * (s.r - planeR)
            val cr = cos(s.rot)
            val sr = sin(s.rot)
            return Pair(s.cx + lx * cr - ly * sr, s.cy + lx * sr + ly * cr)
        }
        /** 按亮度档取色：0 最暗、4 白热核心。seed 只决定粒子分布，不再随机跳亮度档 */
        fun col(i: Int): Int = colors[i.coerceIn(0, colors.lastIndex)]
        fun hot(): Int = colors[colors.lastIndex]
        fun a(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255).toInt()
        fun size(v: Double): Double = v * base
        fun f(v: Double): Float = v.toFloat()

        fun dot(x: Double, y: Double, r: Double, color: Int, alpha: Double, layers: Int = 3) {
            if (alpha <= 0.002) return
            for (q in layers downTo 1) {
                glow.color = color
                glow.alpha = a(alpha * 0.3 / q)
                c.drawCircle(f(x), f(y), f(r * (1.0 + q * 0.8)), glow)
            }
            fill.color = color
            fill.alpha = a(alpha)
            c.drawCircle(f(x), f(y), f(r).coerceAtLeast(0.7f), fill)
            // 大核心闪光补十字/斜十字星芒：白热核心 + 放射线，才是 flash/spark 的画法
            if (layers >= 4 && r > size(0.02)) {
                val s = r * 2.4
                line.color = hot()
                line.strokeWidth = f(size(0.006) + k)
                line.alpha = a(alpha * 0.65)
                c.drawLine(f(x - s), f(y), f(x + s), f(y), line)
                c.drawLine(f(x), f(y - s), f(x), f(y + s), line)
                val d = s * 0.58
                line.alpha = a(alpha * 0.3)
                c.drawLine(f(x - d), f(y - d), f(x + d), f(y + d), line)
                c.drawLine(f(x - d), f(y + d), f(x + d), f(y - d), line)
            }
        }

        fun render() {
            when (shape) {
            Shape.BURST, Shape.IMPLODE, Shape.FIREWORK, Shape.EMP,
            Shape.BLACKHOLE -> burst()
            Shape.FLAME, Shape.FIRE_RING, Shape.FUNNEL -> flame()
            Shape.PROJECTILE, Shape.METEOR -> projectile()
            Shape.SLASH -> slash()
            Shape.IMPACT, Shape.SHIELD, Shape.SPARK, Shape.QUAKE -> impact()
            Shape.BOLT -> bolt()
            Shape.ORB, Shape.BEAM, Shape.RING, Shape.PORTAL,
            Shape.GODRAYS, Shape.GRID -> light()
            Shape.RAIN, Shape.SNOW, Shape.PETAL, Shape.LEAF,
            Shape.FIREFLY, Shape.FOG, Shape.CLOUD, Shape.AURORA -> weather()
            Shape.RIPPLE, Shape.BUBBLE, Shape.DRIP, Shape.FOUNTAIN -> water()
            Shape.CRYSTAL, Shape.SPIKE -> crystal()
            Shape.ROCK, Shape.VINE, Shape.BLOOM, Shape.PUDDLE, Shape.WHIRL -> nature()
            Shape.BAT, Shape.BIRD, Shape.BUTTERFLY, Shape.JELLY, Shape.WISP -> critter()
            Shape.BANG, Shape.HEALTHBAR, Shape.ZZZ,
            Shape.STARS_SPIN, Shape.SPIRAL -> symbol()
            Shape.CRT, Shape.DIAMOND, Shape.IRIS, Shape.STAR_IRIS,
            Shape.PIXEL, Shape.SHATTER -> transition()
            Shape.GEM, Shape.KEY, Shape.POTION, Shape.EGG -> pickup()
            Shape.SMOKE -> smoke()
            Shape.TENTACLE -> tentacle()
            }
        }

        // ===== 爆炸 / 内爆 / 烟花 / 声波 / 黑洞 =====
        private fun burst() {
            val life = (1.0 - p * 0.7) * fade
            when (shape) {
                Shape.FIREWORK -> {
                    val rocket = name.contains("rocket") || name.contains("flare")
                    val cx2 = cx + (rnd(1.0, 2.0) - 0.5) * size(0.4)
                    val cy2 = if (rocket) h * (1.0 - p * 0.75) else cy
                    if (rocket) {
                        for (i in 0 until 12) {
                            val d = rnd(i.toDouble(), 4.0) * size(0.35)
                            dot(cx2 + (rnd(i.toDouble(), 6.0) - 0.5) * size(0.1),
                                cy2 + d, size(0.014), col(3), life * (1 - d / size(0.4)), 2)
                        }
                    } else {
                        for (i in 0 until 44) {
                            val ang = i * PI * 2 / 44
                            val d = size(0.15 + p * 0.85) * (0.6 + rnd(i.toDouble(), 3.0) * 0.4)
                            val x = cx2 + cos(ang) * d
                            val y = cy2 + sin(ang) * d * 0.9 + p * p * size(0.3)
                            line.color = col(i % 4 + 1)
                            line.strokeWidth = f(size(0.012))
                            line.alpha = a(life * (1.0 - p))
                            c.drawLine(f(cx2 + cos(ang) * d * 0.6),
                                f(cy2 + sin(ang) * d * 0.55), f(x), f(y), line)
                            dot(x, y, size(0.016), col(4), life * (1.0 - p), 2)
                        }
                    }
                    // 中心只留针尖大小的白热闪点，别用大实心圆盖住放射状粒子
                    dot(cx2, cy2, size(0.012), hot(), life, 2)
                    return
                }
                Shape.EMP -> {
                    for (ring in 0 until 5) {
                        val rp = wrap(p + ring / 5.0, 1.0)
                        line.color = col(2)
                        line.strokeWidth = f(size(0.02) * (1.0 - rp) + k)
                        line.alpha = a(life * (1.0 - rp) * 0.8)
                        c.drawCircle(f(cx), f(cy), f(size(rp * 1.1)), line)
                    }
                    return
                }
                Shape.BLACKHOLE -> {
                    for (i in 0 until 26) {
                        val ang = i * 0.9 + p * 6
                        val d = size(1.1) * (1.0 - p) * (0.4 + rnd(i.toDouble(), 3.0) * 0.6)
                        dot(cx + cos(ang) * d, cy + sin(ang) * d * 0.6,
                            size(0.012), col(1), life * 0.7, 2)
                    }
                    glow.color = col(0)
                    glow.alpha = a(life * 0.85)
                    c.drawCircle(f(cx), f(cy), f(size(0.2)), glow)
                    line.color = col(3)
                    line.strokeWidth = f(size(0.02))
                    line.alpha = a(life)
                    c.drawCircle(f(cx), f(cy), f(size(0.22)), line)
                    return
                }
                else -> Unit
            }
            val inward = shape == Shape.IMPLODE
            val grow = min(1.0, p / 0.42)
            for (i in 0 until 3) {
                val rp = wrap(p + i / 3.0, 1.0)
                val rr = if (inward) size(1.0 * (1.0 - rp)) else size(0.12 + rp * 0.95)
                line.color = col(i + 2)
                line.strokeWidth = f(size(0.035) * (1.0 - rp) + k)
                line.alpha = a(life * (1.0 - rp) * 0.8)
                c.drawCircle(f(cx), f(cy), f(rr), line)
            }
            val n = 22 + seed % 20
            for (i in 0 until n) {
                val ang = rnd(i.toDouble(), 3.0) * PI * 2
                val d = if (inward) size(1.0 * (1.0 - grow)) * (0.5 + rnd(i.toDouble(), 7.0))
                else size((0.1 + grow * 1.05) * (0.5 + rnd(i.toDouble(), 7.0)))
                val x = cx + cos(ang) * d
                val y = cy + sin(ang) * d * 0.82
                val r = size(0.012 + rnd(i.toDouble(), 11.0) * 0.03)
                line.color = col(i)
                line.strokeWidth = f(r * 0.7)
                line.alpha = a(life * 0.6)
                c.drawLine(f(x - cos(ang) * r * 3), f(y - sin(ang) * r * 3),
                    f(x), f(y), line)
                dot(x, y, r, col(i + 1), life, 2)
            }
            dot(cx, cy, size(0.16), col(4), life * 0.9, 5)
        }

        // ===== 火焰 / 火环 / 火旋风 =====
        private fun flame() {
            if (shape == Shape.FIRE_RING) {
                for (i in 0 until 36) {
                    val ang = i * PI * 2 / 36
                    val r = size(0.55) * (0.9 + 0.1 * sin(p * 8 + i))
                    val x = cx + cos(ang) * r
                    val y = cy + sin(ang) * r * 0.5
                    val flick = sin(p * 12 + i * 1.7) * size(0.08)
                    dot(x, y - flick, size(0.055), col(2 + i % 3), fade * 0.8, 3)
                }
                return
            }
            if (shape == Shape.FUNNEL) {
                for (row in 0 until 16) {
                    val up = row / 16.0
                    val rr = size(0.12 + up * 0.5) * (1.0 + 0.1 * sin(p * 6 + row))
                    val yy = h * 0.92 - up * h * 0.8
                    for (i in 0 until 9) {
                        val ang = i * PI * 2 / 9 + p * (2.0 + up * 3)
                        val x = cx + cos(ang) * rr
                        dot(x, yy + sin(ang) * rr * 0.25, size(0.03),
                            col(1 + row % 4), fade * 0.55, 2)
                    }
                }
                return
            }
            // 源码 fire/_lib.js flameTongue + pnoise：沿高度逐层画火舌条，
            // 宽度按 (1-u)^0.85 递减并叠平滑值噪声，中心线按 u² 侧移 + 噪声摆动。
            val tongues = 7 + seed % 4
            for (j in 0 until tongues) {
                val x0 = cx + (rnd(j.toDouble(), 2.0) - 0.5) * size(0.6)
                val height = size(0.45 + rnd(j.toDouble(), 5.0) * 0.4)
                val baseW = size(0.05 + rnd(j.toDouble(), 8.0) * 0.045)
                val tag = j.toDouble() * 3.0
                val rows = 18
                for (r in 0 until rows) {
                    val u = r.toDouble() / rows
                    val half = baseW * Math.pow(1.0 - u, 0.85) *
                        (0.75 + 0.25 * pnoise(u * 6.0 + p * 5.0, tag))
                    if (half < size(0.002)) continue
                    val drift = pnoise(u * 3.0 + p * 4.0, tag + 11.0) * size(0.07) * u
                    val xx = x0 + drift
                    val yy = cy + size(0.22) - u * height
                    // 三层：外焰暗 → 中焰橙 → 内焰白热
                    for (layer in 0 until 3) {
                        val scale = doubleArrayOf(1.0, 0.62, 0.28)[layer]
                        line.color = col(1 + layer)
                        line.strokeWidth = f(size(0.018) + k)
                        line.alpha = a(fade * (1.0 - u) * (0.35 + layer * 0.25))
                        c.drawLine(f(xx - half * scale), f(yy),
                            f(xx + half * scale), f(yy), line)
                    }
                }
            }
            dot(cx, cy + size(0.2), size(0.16), col(3), fade * 0.8, 4)
            for (i in 0 until 16) {
                val ph = wrap(p * 1.3 + rnd(i.toDouble(), 4.0), 1.0)
                dot(cx + (rnd(i.toDouble(), 6.0) - 0.5) * size(0.7),
                    cy + size(0.2) - ph * size(0.9),
                    size(0.012), col(4), fade * (1.0 - ph), 2)
            }
        }

        // ===== 拖尾弹丸 / 斜向流星 =====
        private fun projectile() {
            if (shape == Shape.METEOR) {
                for (i in 0 until 6) {
                    val q = wrap(p * 1.1 + rnd(i.toDouble(), 3.0), 1.0)
                    val x = -size(0.3) + q * (w + size(0.8))
                    val y = h * (0.05 + rnd(i.toDouble(), 5.0) * 0.35) + q * size(0.5)
                    line.color = col(3)
                    line.strokeWidth = f(size(0.022) * (1.0 - q * 0.5))
                    line.alpha = a(fade * 0.7)
                    c.drawLine(f(x - size(0.55)), f(y - size(0.22)), f(x), f(y), line)
                    dot(x, y, size(0.03), col(4), fade, 4)
                }
                for (i in 0 until 30) {
                    dot(rnd(i.toDouble(), 7.0) * w, rnd(i.toDouble(), 9.0) * h * 0.7,
                        size(0.006), col(4), fade * 0.5, 1)
                }
                return
            }
            val n = 5 + seed % 5
            for (i in 0 until n) {
                val y = h * (0.2 + rnd(i.toDouble(), 3.0) * 0.6)
                val q = wrap(p * (0.8 + rnd(i.toDouble(), 6.0) * 0.5) + rnd(i.toDouble(), 9.0), 1.0)
                val x = -size(0.2) + q * (w + size(0.5))
                line.color = col(3)
                line.strokeWidth = f(size(0.018))
                line.alpha = a(fade * 0.45)
                c.drawLine(f(x - size(0.28)), f(y), f(x), f(y), line)
                dot(x, y, size(0.028), col(4), fade, 3)
            }
        }

        // ===== 刀光 =====
        /**
         * 刀剑斩击。依据 pixelfx 每个招式自己的 meta.desc 绘制：
         * 「倾斜新月两帧挥完、白热转深蓝、根部宽尖端收窄」，
         * 节奏 0.7 秒一轮、起手约 2~3 帧就爆到最大；只有爪击/重劈按描述用暖色。
         */
        private fun slash() {
            val sp = wrap(p * 1.93, 1.0)
            val appear = min(1.0, sp / 0.11)
            val decay = 1.0 - (((sp - 0.36) / 0.64).coerceIn(0.0, 1.0)).let { it * it }
            val life = fade * min(1.0, sp / 0.04) * decay
            if (life <= 0.01) return
            val r = size(0.92)
            when {
                name == "claw-swipe" -> clawMarks(life, appear)
                name == "double-slash" -> crossCut(life, appear)
                name == "dagger-flurry" -> flurry(life, appear, sp)
                name == "spin-slash" -> spinRing(life, appear)
                name == "thrust" -> thrust(life, appear)
                name == "whip-crack" -> whip(life, appear)
                name == "dash-strike" -> dash(life, appear)
                name == "sword-combo" -> combo(life, appear, sp)
                name == "slash-wipe" -> screenWipe(life, appear)
                name == "heavy-cleave" -> cleave(life, appear, r)
                name == "scythe-reap" -> {
                    bladeArc(cx, cy + size(0.12), -1.5, r * 1.25, 2.7,
                        size(0.075), life, appear, 1)
                    bladeEcho(cx, cy + size(0.12), -1.5, r * 1.25, 2.7, life * 0.4)
                }
                name == "spear-sweep" -> {
                    bladeArc(cx, h * 0.78, PI * 0.1, r * 1.5, PI * 0.8,
                        size(0.045), life, appear, 0)
                    dustWake(life)
                }
                else -> {
                    bladeArc(cx, cy, -1.15, r, 2.3, size(0.062), life, appear, 0)
                    bladeEcho(cx, cy, -1.15, r, 2.3, life * 0.45)
                }
            }
        }

        /**
         * 竖屏居中：按弧真实的横向范围把圆心挪到屏幕中线，必要时整体缩放。
         * 原来角度都从负角扫到正角，cos 在中间达到 1，整条弧都落在圆心右侧；
         * 大半径招式（长枪、镰刀、连招）外延会跑到屏幕右外侧。
         * 这里用完整 sweep 采样（不乘 appear），保证动画过程中圆心不抖动。
         */
        private fun slashFit(
            x0: Double, radius: Double, ang: Double, sweep: Double
        ): Pair<Double, Double> {
            val probe = Swing(x0, 0.0, radius, 0.82, -0.15, 0.12,
                ang, if (sweep >= 0.0) 1.0 else -1.0)
            val full = abs(sweep)
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            for (i in 0..16) {
                val px = onSwing(probe, full * i / 16.0, radius).first
                minX = minOf(minX, px)
                maxX = maxOf(maxX, px)
            }
            val spanX = (maxX - minX).coerceAtLeast(1.0)
            // 留 7% 边距，弧中心对准屏幕中线
            val scale = minOf(1.0, w * 0.86 / spanX)
            val midX = (minX + maxX) / 2.0
            val fitCx = w * 0.5 - (midX - x0) * scale
            return Pair(fitCx, scale)
        }

        /** 锥形新月刀光：根部宽、尖端收窄，外晕→主体→白热芯三层，尖端带星芒与火花 */
        private fun bladeArc(
            x0: Double, y0: Double, ang: Double, radius: Double, sweep: Double,
            width: Double, life: Double, appear: Double, tint: Int
        ) {
            val outer = when (tint) {
                2 -> Color.rgb(120, 40, 20)
                1 -> Color.rgb(60, 40, 100)
                else -> col(1)
            }
            val mid = when (tint) {
                2 -> Color.rgb(240, 110, 40)
                1 -> Color.rgb(140, 120, 210)
                else -> col(2)
            }
            val core = when (tint) {
                2 -> Color.rgb(255, 220, 150)
                1 -> Color.rgb(220, 215, 255)
                else -> hot()
            }
            // 源码 melee/_lib.js：swing 是「倾斜压扁椭圆 + 锥面抬升」，近半宽远半窄；
            // smear 的带宽沿弧按 sin(PI*v^2.2)^0.6 从根部收窄到刀尖，并按径向 q 上 7 档色。
            // cone 是源码里的无量纲 k（必须小于 sy=0.82），不是按 radius 缩放的绝对值，
            // 否则锥面抬升会把刀光推出屏幕顶部（竖屏尤其明显）
            val (fitCx, fitScale) = slashFit(x0, radius, ang, sweep)
            val s = Swing(fitCx, y0, radius * fitScale, 0.82, -0.15, 0.12,
                ang, if (sweep >= 0.0) 1.0 else -1.0)
            val r = s.r
            val bandW = width * fitScale
            val span = abs(sweep) * appear
            val steps = 24
            val layers = 5
            for (layer in 0 until layers) {
                val q = layer.toDouble() / (layers - 1) // 0 外缘白热 → 1 内里深蓝
                line.color = when {
                    q < 0.25 -> core
                    q < 0.6 -> mid
                    else -> outer
                }
                line.strokeWidth = f(bandW / layers * 1.7 + k)
                for (i in 0 until steps) {
                    val v0 = i.toDouble() / steps
                    val v1 = (i + 1).toDouble() / steps
                    val w0 = bandW * Math.pow(
                        sin(PI * Math.pow(v0, 2.2)).coerceAtLeast(0.0), 0.6
                    )
                    val w1 = bandW * Math.pow(
                        sin(PI * Math.pow(v1, 2.2)).coerceAtLeast(0.0), 0.6
                    )
                    val (ax, ay) = onSwing(s, span * v0, r - w0 * (0.2 + q * 0.8))
                    val (bx, by) = onSwing(s, span * v1, r - w1 * (0.2 + q * 0.8))
                    // 源码 out.a = min(1, v/0.4)：头部渐入；age/q 让内里与尾部更暗
                    line.alpha = a(life * min(1.0, v0 / 0.4) *
                        (1.0 - p * 0.45) * (1.0 - q * 0.45))
                    c.drawLine(f(ax), f(ay), f(bx), f(by), line)
                }
            }
            // 源码 dotted trace：刀光扫过后沿路径留下的一串点状残迹
            for (i in 0 until 16) {
                val v = i / 15.0
                val (px, py) = onSwing(s, span * v, r - size(0.004))
                dot(px, py, size(0.006), mid, life * (1.0 - v) * 0.55, 1)
            }
            // 刀尖白热闪 + 甩出的火花（源码 glint / spark）
            val (tx, ty) = onSwing(s, span, r - bandW * 0.3)
            dot(tx, ty, bandW * 0.55, core, life, 5)
            for (i in 0 until 10) {
                val sparkAng = ang + span + (rnd(i.toDouble(), 3.0) - 0.5) * 1.6
                val d = bandW * (1.0 + rnd(i.toDouble(), 7.0) * 5) * appear
                dot(tx + cos(sparkAng) * d, ty + sin(sparkAng) * d, bandW * 0.12, mid,
                    life * (1.0 - rnd(i.toDouble(), 9.0)) * 0.8, 2)
            }
        }

        /** 挥出后的残影弧，越靠后越淡越细 */
        private fun bladeEcho(
            x0: Double, y0: Double, ang: Double, radius: Double,
            sweep: Double, alpha: Double
        ) {
            val (fitCx, fitScale) = slashFit(x0, radius, ang, sweep)
            for (e in 1..3) {
                val off = e * 0.09
                val rr = (radius - e * size(0.03)) * fitScale
                path.reset()
                for (i in 0..20) {
                    val echoAng = ang + sweep * i / 20.0 - off
                    val x = fitCx + cos(echoAng) * rr
                    val y = y0 + sin(echoAng) * rr * 0.82
                    if (i == 0) path.moveTo(f(x), f(y)) else path.lineTo(f(x), f(y))
                }
                line.color = col(2)
                line.strokeWidth = f(size(0.012) + k)
                line.alpha = a(alpha / e)
                c.drawPath(path, line)
            }
        }

        private fun dustWake(alpha: Double) {
            for (i in 0 until 20) {
                val x = cx + (rnd(i.toDouble(), 2.0) - 0.5) * size(1.6)
                val q = rnd(i.toDouble(), 5.0)
                glow.color = col(2)
                glow.alpha = a(alpha * 0.16 * (1.0 - q))
                c.drawCircle(f(x), f(h * 0.84 - q * size(0.16)), f(size(0.06)), glow)
            }
        }

        /** 双斩：下斩与上斩交叉成 X，交点爆白 */
        private fun crossCut(life: Double, appear: Double) {
            val r = size(0.9)
            bladeArc(cx, cy, -1.2, r, 2.2, size(0.055), life, appear, 0)
            bladeArc(cx, cy, 1.2, r, -2.2, size(0.055), life * 0.9, appear, 0)
            if (appear > 0.85) {
                dot(cx, cy, size(0.1), hot(), life, 5)
                for (i in 0 until 12) {
                    val burstAng = i * PI / 6
                    dot(cx + cos(burstAng) * size(0.2), cy + sin(burstAng) * size(0.2),
                        size(0.014), col(3), life, 2)
                }
            }
        }

        /** 匕首连击：多角度小新月，最后一记大斩 */
        private fun flurry(life: Double, appear: Double, sp: Double) {
            for (i in 0 until 6) {
                val q = (sp * 3.0 + i * 0.16) % 1.0
                if (q > 0.7) continue
                bladeArc(
                    cx + (rnd(i.toDouble(), 2.0) - 0.5) * size(0.3),
                    cy + (rnd(i.toDouble(), 4.0) - 0.5) * size(0.3),
                    -1.4 + i * 0.55, size(0.5), 1.5, size(0.03),
                    life * 0.8, min(1.0, q * 4.0), 0
                )
            }
            if (sp > 0.45) {
                bladeArc(cx, cy, -1.1, size(1.0), 2.4, size(0.075), life, appear, 0)
                dot(cx + size(0.5), cy - size(0.3), size(0.07), hot(), life, 5)
            }
        }

        /** 回旋斩：腰间一圈倾斜闭合环，环上流转白点 */
        private fun spinRing(life: Double, appear: Double) {
            val rr = size(0.62) * appear
            val widths = doubleArrayOf(0.09, 0.045, 0.012)
            val alphas = doubleArrayOf(0.2, 0.5, 0.95)
            for (layer in 0 until 3) {
                line.color = when (layer) {
                    0 -> col(1)
                    1 -> col(2)
                    else -> hot()
                }
                line.strokeWidth = f(size(widths[layer]) + k)
                line.alpha = a(life * alphas[layer])
                c.drawOval(f(cx - rr), f(cy - rr * 0.42),
                    f(cx + rr), f(cy + rr * 0.42), line)
            }
            for (i in 0 until 22) {
                val dotAng = i * PI * 2 / 22 + p * 6
                dot(cx + cos(dotAng) * rr, cy + sin(dotAng) * rr * 0.42,
                    size(0.012), hot(), life * 0.8, 2)
            }
        }

        /** 突刺：束状直线射向一点 + 四角星 + 穿刺环 + 速度线 */
        private fun thrust(life: Double, appear: Double) {
            // 束状直线从左侧射向屏幕内 84% 处，不再把尖端甩到右边界外
            val startX = w * 0.16
            val tx = startX + w * 0.68 * appear
            for (i in 0 until 7) {
                val off = (i - 3) * size(0.035)
                line.color = if (i % 2 == 0) col(2) else hot()
                line.strokeWidth = f(size(0.012) + k)
                line.alpha = a(life * 0.6)
                c.drawLine(f(startX), f(cy + off),
                    f(tx), f(cy + off * 0.15), line)
            }
            star(tx, cy, size(0.16), hot(), life)
            line.color = col(3)
            line.strokeWidth = f(size(0.02))
            line.alpha = a(life * 0.7)
            c.drawCircle(f(tx), f(cy), f(size(0.22)), line)
            for (i in 0 until 8) {
                val y = cy + (rnd(i.toDouble(), 3.0) - 0.5) * size(0.5)
                line.strokeWidth = f(k)
                line.alpha = a(life * 0.3)
                c.drawLine(0f, f(y), f(tx - size(0.2)), f(y), line)
            }
        }

        /** 鞭击：向右展开的卷曲鞭身 + 尖端啪响白闪与空气环 */
        private fun whip(life: Double, appear: Double) {
            val startX = w * 0.12
            val reach = w * 0.68
            path.reset()
            path.moveTo(f(startX), f(cy + size(0.2)))
            for (i in 1..18) {
                val q = i / 18.0
                path.lineTo(
                    f(startX + reach * q * appear),
                    f(cy + sin(q * PI * 2.2) * size(0.22) * (1.0 - q * 0.5))
                )
            }
            val widths = doubleArrayOf(0.06, 0.028, 0.008)
            val alphas = doubleArrayOf(0.2, 0.5, 0.95)
            for (layer in 0 until 3) {
                line.color = when (layer) {
                    0 -> col(1)
                    1 -> col(2)
                    else -> hot()
                }
                line.strokeWidth = f(size(widths[layer]) + k)
                line.alpha = a(life * alphas[layer])
                c.drawPath(path, line)
            }
            val tx = startX + reach * appear
            dot(tx, cy, size(0.09), hot(), life, 5)
            for (i in 0 until 3) {
                val rr = size(0.1 + i * 0.1)
                line.color = col(3)
                line.strokeWidth = f(k)
                line.alpha = a(life * (1.0 - i * 0.3))
                c.drawOval(f(tx - rr), f(cy - rr), f(tx + rr), f(cy + rr), line)
            }
        }

        /** 爪击：三道平行爪痕，白热→橙→红冷却（唯一按描述用暖色的招式） */
        private fun clawMarks(life: Double, appear: Double) {
            val widths = doubleArrayOf(0.055, 0.026, 0.008)
            val alphas = doubleArrayOf(0.2, 0.55, 0.95)
            for (i in 0 until 3) {
                val off = (i - 1) * size(0.17)
                val q = min(1.0, appear * 1.4 - i * 0.12)
                if (q <= 0.0) continue
                for (layer in 0 until 3) {
                    line.color = when (layer) {
                        0 -> Color.rgb(150, 30, 20)
                        1 -> Color.rgb(255, 130, 50)
                        else -> Color.rgb(255, 245, 220)
                    }
                    line.strokeWidth = f(size(widths[layer]) * (1.0 - q * 0.5) + k)
                    line.alpha = a(life * alphas[layer] * q)
                    c.drawLine(f(cx - size(0.45) + off), f(cy - size(0.45)),
                        f(cx - size(0.45) + off + size(0.35) * q),
                        f(cy - size(0.45) + size(0.95) * q), line)
                }
            }
        }

        /** 重劈：厚余烬弧加速砸地 + 白色爆点 + 发光裂纹 + 尘土 */
        private fun cleave(life: Double, appear: Double, r: Double) {
            bladeArc(cx, cy - size(0.15), -1.9, r * 1.1, 2.9,
                size(0.095), life, appear, 2)
            if (appear > 0.7) {
                dot(cx, h * 0.82, size(0.12), hot(), life, 5)
                for (i in 0 until 6) {
                    val crackAng = -PI + i * PI / 5
                    line.color = Color.rgb(255, 150, 60)
                    line.strokeWidth = f(size(0.012))
                    line.alpha = a(life * 0.8)
                    c.drawLine(f(cx), f(h * 0.82),
                        f(cx + cos(crackAng) * size(0.5)),
                        f(h * 0.82 + sin(crackAng) * size(0.16)), line)
                }
                dustWake(life)
            }
        }

        /** 冲刺突袭：多重残影 + 长速度线 + 暴击白闪 */
        private fun dash(life: Double, appear: Double) {
            for (e in 0 until 3) {
                bladeArc(cx - size(0.7) + e * size(0.35), cy,
                    -1.0, size(0.8), 2.1, size(0.035),
                    life * (1.0 - e * 0.28), appear, 0)
            }
            for (i in 0 until 14) {
                val y = cy + (rnd(i.toDouble(), 4.0) - 0.5) * size(1.1)
                line.color = col(3)
                line.strokeWidth = f(k)
                line.alpha = a(life * 0.35)
                c.drawLine(f(-size(0.2)), f(y),
                    f(cx + size(0.5) * appear), f(y - size(0.1)), line)
            }
            dot(cx + size(0.5), cy - size(0.1), size(0.11), hot(), life, 5)
        }

        /** 剑术连招：三段前进，斩 → 交叉双斩 → 双手劈地 */
        private fun combo(life: Double, appear: Double, sp: Double) {
            val stage = (sp * 3.0).toInt().coerceIn(0, 2)
            val angles = doubleArrayOf(-1.2, 1.1, -1.8)
            val sweeps = doubleArrayOf(2.2, -2.2, 2.7)
            for (s in 0..stage) {
                bladeArc(cx + s * size(0.18), cy, angles[s],
                    size(0.85 + s * 0.1), sweeps[s], size(0.055 + s * 0.012),
                    life * (1.0 - s * 0.2), appear, 0)
            }
        }

        /** 斩击转场：白热刃口横切全屏，两道刀光带火花 */
        private fun screenWipe(life: Double, appear: Double) {
            val widths = doubleArrayOf(0.14, 0.05, 0.012)
            val alphas = doubleArrayOf(0.18, 0.5, 0.95)
            for (cut in 0 until 2) {
                val q = min(1.0, appear * 1.5 - cut * 0.45)
                if (q <= 0.0) continue
                val y = h * (0.32 + cut * 0.34) - q * size(0.25)
                for (layer in 0 until 3) {
                    line.color = when (layer) {
                        0 -> col(1)
                        1 -> col(3)
                        else -> hot()
                    }
                    line.strokeWidth = f(size(widths[layer]) + k)
                    line.alpha = a(life * alphas[layer])
                    c.drawLine(f(-w * 0.1), f(y + size(0.3)),
                        f(w * q), f(y - size(0.3)), line)
                }
                for (i in 0 until 12) {
                    val x = w * q * rnd(i.toDouble(), 3.0)
                    dot(x, y + (rnd(i.toDouble(), 6.0) - 0.5) * size(0.3),
                        size(0.014), col(3), life, 2)
                }
            }
        }

        // ===== 命中 / 格挡 / 电火花 / 地震 =====
        private fun impact() {
            val life = minOf(1.0, p / 0.1, (1.0 - p) / 0.5).coerceAtLeast(0.0) * fade
            when (shape) {
                Shape.SHIELD -> {
                    val rr = size(0.5)
                    for (layer in 0 until 3) {
                        path.reset()
                        path.moveTo(f(cx - rr), f(cy - rr * 0.8))
                        path.quadTo(f(cx), f(cy - rr * 1.3), f(cx + rr), f(cy - rr * 0.8))
                        path.quadTo(f(cx), f(cy + rr * 1.1), f(cx - rr), f(cy - rr * 0.8))
                        path.close()
                        line.strokeWidth = f(size(0.03) + k + layer)
                        line.color = col(3)
                        line.alpha = a(life * (0.3 + layer * 0.25))
                        c.drawPath(path, line)
                    }
                    for (i in 0 until 10) {
                        val ang = rnd(i.toDouble(), 3.0) * PI * 2
                        dot(cx + cos(ang) * rr, cy + sin(ang) * rr,
                            size(0.02), col(4), life, 2)
                    }
                    return
                }
                Shape.SPARK -> {
                    for (i in 0 until 14) {
                        val ang = rnd(i.toDouble(), 2.0) * PI * 2
                        val d = size(0.08 + p * 0.42) * (0.5 + rnd(i.toDouble(), 5.0))
                        val x = cx + cos(ang) * d
                        val y = cy + sin(ang) * d
                        line.color = col(4)
                        line.strokeWidth = f(size(0.008) + k)
                        line.alpha = a(life * (1.0 - p))
                        c.drawLine(f(cx), f(cy), f(x), f(y), line)
                        dot(x, y, size(0.014), col(3), life, 2)
                    }
                    dot(cx, cy, size(0.08), col(4), life, 4)
                    return
                }
                Shape.QUAKE -> {
                    val jitter = sin(p * 40) * size(0.03)
                    for (i in 0 until 4) {
                        val yy = h * 0.78 + i * size(0.04)
                        line.color = col(1 + i % 3)
                        line.strokeWidth = f(size(0.018) + k)
                        line.alpha = a(life * 0.75)
                        var px = 0.0
                        for (s in 1..8) {
                            val nx = w * s / 8.0
                            val ny = yy + (rnd(s.toDouble(), 4.0) - 0.5) * size(0.09) + jitter
                            c.drawLine(f(px), f(yy), f(nx), f(ny), line)
                            px = nx
                        }
                    }
                    for (i in 0 until 18) {
                        val x = rnd(i.toDouble(), 2.0) * w
                        val ph = wrap(p * 1.4 + rnd(i.toDouble(), 6.0), 1.0)
                        dot(x, h * 0.8 - ph * size(0.3), size(0.02), col(2), life * (1 - ph), 2)
                    }
                    return
                }
                else -> Unit
            }
            for (i in 0 until 24) {
                val ang = i * PI * 2 / 24 + p * 0.4
                val r1 = size(0.9)
                val r2 = size(0.38)
                line.color = col(4)
                line.strokeWidth = f(size(0.016))
                line.alpha = a(life * 0.5)
                c.drawLine(f(cx + cos(ang) * r1), f(cy + sin(ang) * r1),
                    f(cx + cos(ang) * r2), f(cy + sin(ang) * r2), line)
            }
            for (i in 0 until 8) {
                val ang = i * PI / 4 + p
                val d = size(0.1 + p * 0.25)
                dot(cx + cos(ang) * d, cy + sin(ang) * d, size(0.03), col(4), life, 3)
            }
            dot(cx, cy, size(0.09), col(4), life, 5)
        }

        // ===== 落雷折线 =====
        /**
         * 闪电：白热细核心 + 多层蓝紫外晕 + 锯齿主脉 + 沿主脉伸出的细分支，
         * 分支各自高频亮灭形成噼啪感，落点有强光闪和白热火花。
         * 关键是「外粗暗、内细亮」，不再画成一条实心粗柱子。
         */
        private fun bolt() {
            val life = fade
            val chain = name.contains("chain")
            val strike = 0.32 + 0.68 * abs(sin(p * 5 * PI))
            // 每 1/8 周期重新放电一次，主脉形状随之变化，形成连续噼啪
            val shapeSeed = seed + (p * 8).toInt() * 31

            fun mainPath(offset: Double, jitterSeed: Double): List<Pair<Double, Double>> {
                val xTop = cx + (rnd(1.0 + jitterSeed, 2.0) - 0.5) * w * 0.5 + offset
                val xBot = cx + (rnd(3.0 + jitterSeed, 4.0) - 0.5) * w * 0.3 + offset
                // 源码 lightning/_lib.js zigzag：沿线段法向做中点位移，逐代衰减 0.55。
                // 比多频正弦更像真实放电的自相似分叉，且横竖屏都按 w/h 归一化。
                return zigzag(xTop, 0.0, xBot, h * 0.88, 0.16,
                    shapeSeed.toDouble() + jitterSeed)
            }

            fun strokePath(pts: List<Pair<Double, Double>>, widthScale: Double, brightness: Double) {
                // 由外到内：宽而暗的蓝紫晕 → 细而亮的白热核心
                val layers = arrayOf(
                    Triple(0.035, col(1), 0.13),
                    Triple(0.020, col(2), 0.25),
                    Triple(0.010, col(3), 0.5),
                    Triple(0.004, hot(), 0.95)
                )
                for ((bw, color, alphaScale) in layers) {
                    line.strokeWidth = f(size(bw) * widthScale + k)
                    line.color = color
                    line.alpha = a(life * strike * brightness * alphaScale)
                    path.reset()
                    path.moveTo(f(pts[0].first), f(pts[0].second))
                    for (i in 1 until pts.size) {
                        path.lineTo(f(pts[i].first), f(pts[i].second))
                    }
                    c.drawPath(path, line)
                }
            }

            val mainPts = mainPath(0.0, 0.0)
            if (chain) {
                // 连锁闪电：第二条更细、更暗的主脉，错开位置同时放电
                strokePath(mainPath(size(0.42), 13.0), 0.55, 0.55)
            }
            strokePath(mainPts, 1.0, 1.0)

            // 主脉上的局部过曝段（噼啪闪点）
            for (i in 1 until mainPts.size step 2) {
                if (rnd(i.toDouble() + shapeSeed, 11.0) > 0.68) {
                    val (ax, ay) = mainPts[i - 1]
                    val (bx, by) = mainPts[i]
                    line.color = hot()
                    line.strokeWidth = f(size(0.008) + k)
                    line.alpha = a(life * strike * (0.4 + 0.6 * abs(sin(p * 46 + i))))
                    c.drawLine(f(ax), f(ay), f(bx), f(by), line)
                    // 源码 crackle：过曝处撒白热亮点，dot 自带十字星芒形成电晕噼啪
                    dot((ax + bx) / 2.0, (ay + by) / 2.0, size(0.014), hot(),
                        life * strike, 2)
                }
            }

            // 细分支：长度约主脉 1/3~1/2，角度多变，末端渐细，各自独立亮灭
            for (b in 0 until 7) {
                val idx = 2 + ((seed + b * 7) % (mainPts.size - 4).coerceAtLeast(1))
                val (bx, by) = mainPts[idx]
                val dir = if (b % 2 == 0) 1.0 else -1.0
                val len = size(0.3 + rnd(b.toDouble(), 3.0) * 0.45)
                // 分叉也用中点位移分形生成（源码 zigzag 的 forks 参数）
                val fork = zigzag(
                    bx, by,
                    bx + dir * len,
                    by + len * (0.35 + rnd(b.toDouble(), 5.0) * 0.45),
                    0.3, shapeSeed.toDouble() + b * 3.0
                )
                // 每条分叉自己闪，形成「噼噼啪啪」
                val on = if (sin(p * 31 + b * 2.4) > -0.35) 1.0 else 0.18
                strokePath(fork, 0.35, 0.7 * on)

                // 二级小分叉：更短更细，末端渐隐
                if (b % 2 == 0) {
                    val (sx, sy) = fork[4]
                    val tip = size(0.16) * (0.7 + rnd(b.toDouble(), 8.0) * 0.6)
                    for (layer in 0 until 2) {
                        line.color = if (layer == 0) col(2) else hot()
                        line.strokeWidth = f(size(if (layer == 0) 0.006 else 0.002) + k)
                        line.alpha = a(life * strike * on * (if (layer == 0) 0.35 else 0.8))
                        c.drawLine(f(sx), f(sy),
                            f(sx - dir * tip * 0.7 + sin(p * 33 + b) * size(0.04)),
                            f(sy + tip), line)
                    }
                    dot(sx - dir * tip * 0.7, sy + tip, size(0.012), hot(),
                        life * strike * on * 0.8, 2)
                }
            }

            // 落点：强光闪 + 四散白热火花
            val (gx, gy) = mainPts.last()
            dot(gx, gy, size(0.1), hot(), life * strike, 5)
            for (i in 0 until 14) {
                val ang = rnd(i.toDouble(), 2.0) * PI * 2
                val d = size(0.08 + p * 0.32) * (0.4 + rnd(i.toDouble(), 6.0))
                dot(gx + cos(ang) * d, gy - abs(sin(ang)) * d * 0.8,
                    size(0.012), col(3), life * strike * (1.0 - p * 0.5), 2)
            }
        }

        // ===== 球 / 光束 / 法环 / 传送门 / 丁达尔 / 激光网格 =====
        private fun light() {
            when (shape) {
                Shape.ORB -> {
                    val ph = p * PI * 2
                    val x = cx + cos(ph) * size(0.28)
                    val y = cy + sin(ph * 1.3) * size(0.18)
                    for (i in 12 downTo 1) {
                        val q = wrap(ph - i * 0.16, PI * 2)
                        dot(cx + cos(q) * size(0.28), cy + sin(q * 1.3) * size(0.18),
                            size(0.02), col(2), fade * (1 - i / 13.0) * 0.5, 2)
                    }
                    val r = size(0.12) * (1 + 0.08 * sin(p * 20))
                    dot(x, y, r, col(3), fade, 5)
                    dot(x, y, r * 0.4, Color.WHITE, fade, 2)
                    for (i in 0 until 10) {
                        val ang = i * PI * 2 / 10 + p * 8
                        line.color = col(4)
                        line.strokeWidth = f(size(0.006) + k)
                        line.alpha = a(fade * 0.7)
                        c.drawLine(f(x + cos(ang) * r), f(y + sin(ang) * r),
                            f(x + cos(ang) * r * 1.5), f(y + sin(ang) * r * 1.5), line)
                    }
                }
                Shape.BEAM -> {
                    val top = w * 0.08
                    val bottom = w * 0.3
                    path.reset()
                    path.moveTo(f(cx - top), 0f)
                    path.lineTo(f(cx + top), 0f)
                    path.lineTo(f(cx + bottom), f(h))
                    path.lineTo(f(cx - bottom), f(h))
                    path.close()
                    glow.color = col(3)
                    glow.alpha = a(fade * 0.2)
                    c.drawPath(path, glow)
                    fill.color = col(4)
                    fill.alpha = a(fade * 0.1)
                    c.drawPath(path, fill)
                    for (i in 0 until 34) {
                        val y = wrap(rnd(i.toDouble(), 2.0) * h - p * h * 0.4, h)
                        val x = cx + (rnd(i.toDouble(), 5.0) - 0.5) * bottom * (y / h) * 2
                        dot(x, y, size(0.012), col(4), fade * 0.7, 2)
                    }
                }
                Shape.GODRAYS -> {
                    for (i in 0 until 5) {
                        val x0 = w * (0.05 + i * 0.2)
                        path.reset()
                        path.moveTo(f(x0), 0f)
                        path.lineTo(f(x0 + w * 0.05), 0f)
                        path.lineTo(f(x0 + w * 0.3), f(h))
                        path.lineTo(f(x0 + w * 0.16), f(h))
                        path.close()
                        glow.color = col(3)
                        glow.alpha = a(fade * 0.12 * (0.7 + 0.3 * sin(p * 4 + i)))
                        c.drawPath(path, glow)
                    }
                    for (i in 0 until 30) {
                        val x = rnd(i.toDouble(), 2.0) * w
                        val y = wrap(rnd(i.toDouble(), 5.0) * h - p * h * 0.2, h)
                        dot(x, y, size(0.008), col(4), fade * 0.5, 1)
                    }
                }
                Shape.PORTAL -> {
                    val rr = size(0.4) * (0.9 + 0.1 * sin(p * 4))
                    for (layer in 0 until 4) {
                        glow.color = col(layer + 1)
                        glow.alpha = a(fade * 0.22)
                        c.drawOval(f(cx - rr * (1 + layer * 0.14)),
                            f(cy - rr * 1.5 * (1 + layer * 0.14)),
                            f(cx + rr * (1 + layer * 0.14)),
                            f(cy + rr * 1.5 * (1 + layer * 0.14)), glow)
                    }
                    fill.color = col(0)
                    fill.alpha = a(fade * 0.85)
                    c.drawOval(f(cx - rr), f(cy - rr * 1.5),
                        f(cx + rr), f(cy + rr * 1.5), fill)
                    for (i in 0 until 14) {
                        val ang = i * PI * 2 / 14 + p * 3
                        dot(cx + cos(ang) * rr, cy + sin(ang) * rr * 1.5,
                            size(0.014), col(4), fade, 2)
                    }
                }
                Shape.GRID -> {
                    for (i in 0 until 8) {
                        val y = h * i / 8.0 + wrap(p, 1.0) * size(0.12)
                        line.color = col(2)
                        line.strokeWidth = f(k * 1.2)
                        line.alpha = a(fade * 0.35)
                        c.drawLine(0f, f(y), f(w), f(y), line)
                    }
                    for (i in 0 until 8) {
                        val x = w * i / 8.0
                        c.drawLine(f(x), 0f, f(x), f(h), line)
                    }
                }
                else -> { // RING：旋转符文环
                    for (ring in 0 until 3) {
                        val rr = size(0.42 + ring * 0.22) * (0.85 + 0.15 * sin(p * 3))
                        line.color = col(ring + 1)
                        line.strokeWidth = f(size(0.01) + k)
                        line.alpha = a(fade * 0.6)
                        c.drawCircle(f(cx), f(cy), f(rr), line)
                        for (i in 0 until 10) {
                            val ang = i * PI / 5 + p * (if (ring % 2 == 0) 1.2 else -0.9)
                            dot(cx + cos(ang) * rr, cy + sin(ang) * rr,
                                size(0.014), col(4), fade * 0.8, 2)
                        }
                    }
                    dot(cx, cy, size(0.13), col(4), fade * 0.75, 4)
                }
            }
        }

        // ===== 天气与天象 =====
        private fun weather() {
            when (shape) {
                Shape.AURORA -> {
                    for (band in 0 until 4) {
                        path.reset()
                        val y0 = h * (0.1 + band * 0.14)
                        path.moveTo(0f, f(y0))
                        for (s in 1..12) {
                            val x = w * s / 12.0
                            val y = y0 + sin(s * 0.8 + p * 4 + band) * size(0.12)
                            path.lineTo(f(x), f(y))
                        }
                        path.lineTo(f(w), f(h * 0.75))
                        path.lineTo(0f, f(h * 0.75))
                        path.close()
                        glow.color = when (band) {
                            0 -> Color.rgb(60, 220, 140)
                            1 -> Color.rgb(90, 200, 220)
                            2 -> Color.rgb(150, 120, 240)
                            else -> Color.rgb(210, 130, 230)
                        }
                        glow.alpha = a(fade * 0.16)
                        c.drawPath(path, glow)
                    }
                    for (i in 0 until 40) {
                        dot(rnd(i.toDouble(), 2.0) * w, rnd(i.toDouble(), 5.0) * h * 0.5,
                            size(0.005), Color.WHITE, fade * 0.6, 1)
                    }
                    return
                }
                Shape.CLOUD -> {
                    val moonX = w * 0.72
                    val moonY = h * 0.2
                    dot(moonX, moonY, size(0.12), Color.rgb(245, 245, 220), fade * 0.75, 5)
                    for (i in 0 until 7) {
                        val q = wrap(p * 0.25 + rnd(i.toDouble(), 3.0), 1.0)
                        val x = -size(0.3) + q * (w + size(0.6))
                        val y = h * (0.15 + rnd(i.toDouble(), 5.0) * 0.5)
                        for (j in 0 until 4) {
                            glow.color = if (j == 0) col(3) else col(1)
                            glow.alpha = a(fade * 0.18)
                            c.drawOval(f(x + j * size(0.12) - size(0.2)),
                                f(y - size(0.06)), f(x + j * size(0.12) + size(0.2)),
                                f(y + size(0.08)), glow)
                        }
                    }
                    return
                }
                else -> Unit
            }
            val n = 46 + seed % 30
            for (i in 0 until n) {
                val x = wrap(rnd(i.toDouble(), 2.0) * w + sin(p * 5 + i) * size(0.06), w)
                val y = wrap(rnd(i.toDouble(), 6.0) * h + p * h * 0.9, h)
                val alpha = fade * (0.35 + rnd(i.toDouble(), 9.0) * 0.45)
                when (shape) {
                    Shape.RAIN -> {
                        line.color = col(3)
                        line.strokeWidth = f(k * 3.2)
                        line.alpha = a(alpha * 0.8)
                        c.drawLine(f(x), f(y), f(x - size(0.03)), f(y + size(0.12)), line)
                    }
                    Shape.SNOW -> {
                        dot(x, y, size(0.018), col(4), alpha, 2)
                        line.color = col(3)
                        line.strokeWidth = f(k * 0.8)
                        line.alpha = a(alpha * 0.4)
                        c.drawLine(f(x - size(0.02)), f(y), f(x + size(0.02)), f(y), line)
                        c.drawLine(f(x), f(y - size(0.02)), f(x), f(y + size(0.02)), line)
                    }
                    Shape.PETAL -> {
                        fill.color = Color.rgb(255, 183, 205)
                        fill.alpha = a(alpha)
                        // 花瓣放大约 1.7 倍（樱花飘落等具体特效用）
                        c.drawOval(f(x - size(0.042)), f(y - size(0.020)),
                            f(x + size(0.042)), f(y + size(0.020)), fill)
                    }
                    Shape.LEAF -> {
                        fill.color = Color.rgb(200, 130, 60)
                        fill.alpha = a(alpha)
                        c.drawOval(f(x - size(0.05)), f(y - size(0.020)),
                            f(x + size(0.05)), f(y + size(0.020)), fill)
                    }
                    Shape.FIREFLY -> {
                        val blink = 0.4 + 0.6 * abs(sin(p * 5 + i))
                        dot(x, y, size(0.02), Color.rgb(210, 255, 120), alpha * blink, 4)
                    }
                    Shape.FOG -> {
                        glow.color = col(2)
                        glow.alpha = a(alpha * 0.12)
                        c.drawOval(f(x - size(0.22)), f(y - size(0.05)),
                            f(x + size(0.22)), f(y + size(0.05)), glow)
                    }
                    else -> dot(x, y, size(0.016), col(4), alpha, 2)
                }
            }
        }

        // ===== 水：涟漪 / 气泡 / 液滴 / 喷涌 =====
        private fun water() {
            when (shape) {
                Shape.RIPPLE -> {
                    for (ring in 0 until 5) {
                        val rp = wrap(p * 0.8 + ring / 5.0, 1.0)
                        val rr = size(0.9) * rp
                        line.color = col(2)
                        line.strokeWidth = f(size(0.014) * (1.0 - rp) + k)
                        line.alpha = a(fade * (1.0 - rp) * 0.7)
                        c.drawOval(f(cx - rr), f(cy - rr * 0.3),
                            f(cx + rr), f(cy + rr * 0.3), line)
                    }
                }
                Shape.BUBBLE -> {
                    for (i in 0 until 20) {
                        val ph = wrap(p * 1.1 + rnd(i.toDouble(), 3.0), 1.0)
                        val x = cx + (rnd(i.toDouble(), 6.0) - 0.5) * size(1.1)
                        val y = h * 0.95 - ph * h * 0.9
                        val r = size(0.03 + rnd(i.toDouble(), 8.0) * 0.05)
                        line.color = col(3)
                        line.strokeWidth = f(size(0.008) + k)
                        line.alpha = a(fade * (1.0 - ph) * 0.8)
                        c.drawCircle(f(x), f(y), f(r), line)
                        dot(x - r * 0.3, y - r * 0.3, r * 0.2, Color.WHITE, fade * 0.8, 1)
                    }
                }
                Shape.DRIP -> {
                    val red = name.contains("bleed")
                    val color = if (red) Color.rgb(190, 30, 40) else col(3)
                    for (i in 0 until 14) {
                        val ph = wrap(p * 1.2 + rnd(i.toDouble(), 3.0), 1.0)
                        val x = cx + (rnd(i.toDouble(), 6.0) - 0.5) * size(0.8)
                        val y = -size(0.1) + ph * h * 0.85
                        fill.color = color
                        fill.alpha = a(fade * (1.0 - ph * 0.4))
                        c.drawOval(f(x - size(0.012)), f(y - size(0.03)),
                            f(x + size(0.012)), f(y + size(0.02)), fill)
                        if (ph > 0.82) {
                            line.color = color
                            line.strokeWidth = f(k)
                            line.alpha = a(fade * (ph - 0.82) * 4)
                            c.drawOval(f(x - size(0.07)), f(h * 0.86 - size(0.02)),
                                f(x + size(0.07)), f(h * 0.86 + size(0.02)), line)
                        }
                    }
                }
                Shape.FOUNTAIN -> {
                    for (i in 0 until 26) {
                        val ph = wrap(p * 1.1 + rnd(i.toDouble(), 4.0), 1.0)
                        val vx = (rnd(i.toDouble(), 7.0) - 0.5) * size(0.8)
                        val x = cx + vx * ph
                        val y = h * 0.88 - sin(ph * PI) * size(0.7)
                        dot(x, y, size(0.02), col(3), fade * (1.0 - ph * 0.6), 2)
                    }
                }
                else -> Unit
            }
        }

        // ===== 冰晶 / 地面尖刺 =====
        private fun crystal() {
            if (shape == Shape.SPIKE) {
                for (i in 0 until 7) {
                    val x = cx + (i - 3) * size(0.16)
                    val hh = size(0.45 + rnd(i.toDouble(), 3.0) * 0.45) *
                        min(1.0, p * 3) * (1.0 - p * 0.3)
                    path.reset()
                    path.moveTo(f(x - size(0.06)), f(h * 0.86))
                    path.lineTo(f(x), f(h * 0.86 - hh))
                    path.lineTo(f(x + size(0.06)), f(h * 0.86))
                    path.close()
                    fill.color = col(2 + i % 3)
                    fill.alpha = a(fade * 0.8)
                    c.drawPath(path, fill)
                }
                return
            }
            // 源码 ice/_lib.js snowflake：严格六向对称枝 + 每枝成对侧枝，
            // 旋转按 10° 量化（像素画逐帧的味道），中心白热核。
            val grow = min(1.0, p / 0.35)
            val quantRot = (p * 9.0).toInt() * (PI / 18.0)
            val rr = size(0.85) * grow
            for (i in 0 until 6) {
                val ang = i * PI / 3.0 + quantRot
                val ex = cx + cos(ang) * rr
                val ey = cy + sin(ang) * rr
                for (layer in 0 until 3) {
                    line.color = if (layer == 2) hot() else col(2 + layer)
                    line.strokeWidth = f(size(0.012 - layer * 0.003) + k)
                    line.alpha = a(fade * (0.4 + layer * 0.3))
                    c.drawLine(f(cx), f(cy), f(ex), f(ey), line)
                }
                // 每枝 3 对侧枝，越靠尖端越短（源码的自相似分叉）
                for (b in 1..3) {
                    val u = b / 4.0
                    val bx = cx + cos(ang) * rr * u
                    val by = cy + sin(ang) * rr * u
                    val bl = rr * 0.22 * (1.0 - u * 0.5)
                    for (side in -1..1 step 2) {
                        val a2 = ang + side * PI / 3.5
                        line.color = col(3)
                        line.strokeWidth = f(size(0.008) + k)
                        line.alpha = a(fade * 0.7)
                        c.drawLine(f(bx), f(by),
                            f(bx + cos(a2) * bl), f(by + sin(a2) * bl), line)
                    }
                }
            }
            dot(cx, cy, size(0.09), hot(), fade, 4)
        }

        // ===== 自然：滚石 / 藤蔓 / 花开 / 毒水洼 / 漩涡 =====
        private fun nature() {
            when (shape) {
                Shape.ROCK -> {
                    val ph = min(1.0, p * 1.6)
                    val x = -size(0.2) + ph * (w + size(0.4))
                    val y = h * 0.35 + sin(ph * PI) * size(0.3)
                    val r = size(0.2)
                    fill.color = col(1)
                    fill.alpha = a(fade)
                    c.drawCircle(f(x), f(y), f(r), fill)
                    for (i in 0 until 6) {
                        val ang = i * PI / 3 + p * 8
                        dot(x + cos(ang) * r * 0.6, y + sin(ang) * r * 0.6,
                            size(0.035), col(0), fade * 0.8, 1)
                    }
                    if (ph >= 1.0) {
                        for (i in 0 until 16) {
                            val ang = rnd(i.toDouble(), 2.0) * PI * 2
                            val d = rnd(i.toDouble(), 5.0) * size(0.7)
                            dot(x + cos(ang) * d, h * 0.8 - abs(sin(ang)) * d,
                                size(0.025), col(1), fade * 0.7, 2)
                        }
                    }
                }
                Shape.VINE -> {
                    for (i in 0 until 5) {
                        val x0 = cx + (i - 2) * size(0.16)
                        path.reset()
                        path.moveTo(f(x0), f(h * 0.9))
                        for (s in 1..10) {
                            val up = s / 10.0
                            path.lineTo(f(x0 + sin(up * 7 + p * 5 + i) * size(0.1)),
                                f(h * 0.9 - up * size(0.9)))
                        }
                        line.color = Color.rgb(70, 150, 80)
                        line.strokeWidth = f(size(0.02) + k)
                        line.alpha = a(fade * 0.8)
                        c.drawPath(path, line)
                    }
                }
                Shape.BLOOM -> {
                    val open = min(1.0, p * 2)
                    for (i in 0 until 8) {
                        val ang = i * PI * 2 / 8
                        val d = size(0.28) * open
                        fill.color = Color.rgb(255, 190, 215)
                        fill.alpha = a(fade * 0.85)
                        c.drawOval(f(cx + cos(ang) * d - size(0.09)),
                            f(cy + sin(ang) * d - size(0.05)),
                            f(cx + cos(ang) * d + size(0.09)),
                            f(cy + sin(ang) * d + size(0.05)), fill)
                    }
                    dot(cx, cy, size(0.1), Color.rgb(255, 230, 140), fade, 5)
                }
                Shape.PUDDLE -> {
                    glow.color = Color.rgb(20, 30, 20)
                    glow.alpha = a(fade * 0.8)
                    c.drawOval(f(cx - size(0.65)), f(h * 0.78 - size(0.12)),
                        f(cx + size(0.65)), f(h * 0.78 + size(0.12)), glow)
                    line.color = Color.rgb(170, 230, 70)
                    line.strokeWidth = f(size(0.012))
                    line.alpha = a(fade * 0.8)
                    c.drawOval(f(cx - size(0.65)), f(h * 0.78 - size(0.12)),
                        f(cx + size(0.65)), f(h * 0.78 + size(0.12)), line)
                    for (i in 0 until 8) {
                        val ph = wrap(p * 1.3 + rnd(i.toDouble(), 3.0), 1.0)
                        dot(cx + (rnd(i.toDouble(), 5.0) - 0.5) * size(1.0),
                            h * 0.78 - ph * size(0.25),
                            size(0.025), Color.rgb(170, 230, 70), fade * (1 - ph), 2)
                    }
                }
                Shape.WHIRL -> {
                    for (row in 0 until 18) {
                        val up = row / 18.0
                        val rr = size(0.16 + up * 0.55) * (1.0 + 0.08 * sin(p * 7 + row))
                        val yy = h * 0.88 - up * h * 0.78
                        for (i in 0 until 10) {
                            val ang = i * PI * 2 / 10 + p * (3.0 + up * 3)
                            dot(cx + cos(ang) * rr, yy + sin(ang) * rr * 0.22,
                                size(0.025), col(1 + row % 4), fade * 0.6, 2)
                        }
                    }
                }
                else -> Unit
            }
        }

        // ===== 生物：蝙蝠 / 鸟 / 蝴蝶 / 水母 / 鬼火 =====
        private fun critter() {
            val x = cx + sin(p * PI * 2) * size(0.3)
            val y = cy + sin(p * PI * 4) * size(0.16)
            when (shape) {
                Shape.JELLY -> {
                    val r = size(0.16) * (1 + 0.12 * sin(p * 8))
                    glow.color = col(3)
                    glow.alpha = a(fade * 0.3)
                    c.drawOval(f(x - r), f(y - r * 0.8), f(x + r), f(y + r * 0.5), glow)
                    fill.color = col(2)
                    fill.alpha = a(fade * 0.65)
                    c.drawOval(f(x - r), f(y - r * 0.8), f(x + r), f(y + r * 0.5), fill)
                    for (i in 0 until 7) {
                        val tx = x + (i - 3) * r * 0.28
                        path.reset()
                        path.moveTo(f(tx), f(y + r * 0.4))
                        path.quadTo(f(tx + sin(p * 6 + i) * r * 0.3), f(y + r),
                            f(tx + sin(p * 6 + i + 1) * r * 0.4), f(y + r * 1.9))
                        line.color = col(3)
                        line.strokeWidth = f(size(0.012) + k)
                        line.alpha = a(fade * 0.7)
                        c.drawPath(path, line)
                    }
                }
                Shape.WISP -> {
                    val flame = size(0.13) * (1 + 0.2 * sin(p * 9))
                    dot(x, y, flame, Color.rgb(120, 230, 255), fade, 5)
                    for (i in 0 until 10) {
                        val ph = i / 10.0
                        dot(x - sin(p * 5 + i) * size(0.12) - ph * size(0.3),
                            y + ph * size(0.4), size(0.02) * (1 - ph),
                            Color.rgb(120, 230, 255), fade * (1 - ph), 2)
                    }
                }
                Shape.BAT -> {
                    val flap = sin(p * 16) * size(0.16)
                    for (side in -1..1 step 2) {
                        path.reset()
                        path.moveTo(f(x), f(y))
                        path.quadTo(f(x + side * size(0.3)), f(y - flap - size(0.2)),
                            f(x + side * size(0.55)), f(y + flap * 0.5))
                        path.quadTo(f(x + side * size(0.32)), f(y + size(0.1)), f(x), f(y))
                        path.close()
                        fill.color = col(1)
                        fill.alpha = a(fade * 0.85)
                        c.drawPath(path, fill)
                    }
                    dot(x, y, size(0.045), col(0), fade, 2)
                }
                Shape.BIRD -> {
                    val flap = sin(p * 12) * size(0.12)
                    for (side in -1..1 step 2) {
                        path.reset()
                        path.moveTo(f(x), f(y))
                        path.quadTo(f(x + side * size(0.22)), f(y - flap - size(0.15)),
                            f(x + side * size(0.45)), f(y - flap * 0.4))
                        path.quadTo(f(x + side * size(0.2)), f(y + size(0.04)), f(x), f(y))
                        path.close()
                        fill.color = col(3)
                        fill.alpha = a(fade * 0.8)
                        c.drawPath(path, fill)
                    }
                }
                Shape.BUTTERFLY -> {
                    val flap = 0.4 + 0.6 * abs(sin(p * 10))
                    for (side in -1..1 step 2) {
                        fill.color = col(2 + (side + 1))
                        fill.alpha = a(fade * 0.75)
                        c.drawOval(f(x + side * size(0.06)), f(y - size(0.14)),
                            f(x + side * size(0.06 + 0.24 * flap)), f(y + size(0.08)), fill)
                        c.drawOval(f(x + side * size(0.05)), f(y),
                            f(x + side * size(0.05 + 0.2 * flap)), f(y + size(0.18)), fill)
                    }
                    dot(x, y, size(0.025), col(4), fade, 2)
                }
                else -> Unit
            }
        }

        // ===== UI / 状态符号 =====
        private fun symbol() {
            when (shape) {
                Shape.BANG -> {
                    val pop = min(1.0, p * 4) * (1.0 - maxOf(0.0, p - 0.8) * 3)
                    val y = cy - size(0.25) - pop * size(0.12)
                    val color = Color.rgb(255, 215, 60)
                    fill.color = color
                    fill.alpha = a(fade * pop)
                    c.drawRect(f(cx - size(0.045)), f(y - size(0.22)),
                        f(cx + size(0.045)), f(y + size(0.06)), fill)
                    c.drawCircle(f(cx), f(y + size(0.14)), f(size(0.05)), fill)
                    dot(cx, y, size(0.3), color, fade * 0.2, 4)
                }
                Shape.HEALTHBAR -> {
                    val bx = cx - size(0.5)
                    val by = cy - size(0.06)
                    val bw = size(1.0)
                    val bh = size(0.12)
                    fill.color = Color.rgb(40, 30, 30)
                    fill.alpha = a(fade)
                    c.drawRect(f(bx), f(by), f(bx + bw), f(by + bh), fill)
                    val lost = 0.25 + 0.35 * min(1.0, p * 2)
                    fill.color = Color.rgb(220, 60, 60)
                    fill.alpha = a(fade)
                    c.drawRect(f(bx + size(0.02)), f(by + size(0.02)),
                        f(bx + bw * (1.0 - lost)), f(by + bh - size(0.02)), fill)
                    if (p < 0.3) {
                        fill.color = Color.WHITE
                        fill.alpha = a(fade * (1 - p / 0.3))
                        c.drawRect(f(bx + bw * (1.0 - lost)), f(by),
                            f(bx + bw), f(by + bh), fill)
                    }
                    line.color = Color.rgb(240, 220, 180)
                    line.strokeWidth = f(k * 1.5)
                    line.alpha = a(fade)
                    c.drawRect(f(bx), f(by), f(bx + bw), f(by + bh), line)
                }
                Shape.ZZZ -> {
                    for (i in 0 until 3) {
                        val ph = wrap(p * 1.2 + i / 3.0, 1.0)
                        val x = cx + size(0.12) + sin(ph * 5 + i) * size(0.06)
                        val y = cy - ph * size(0.65)
                        val s = size(0.07) * (0.6 + ph)
                        line.color = col(3)
                        line.strokeWidth = f(size(0.012) + k)
                        line.alpha = a(fade * (1.0 - ph))
                        c.drawLine(f(x - s), f(y - s), f(x + s), f(y - s), line)
                        c.drawLine(f(x + s), f(y - s), f(x - s), f(y + s), line)
                        c.drawLine(f(x - s), f(y + s), f(x + s), f(y + s), line)
                    }
                }
                Shape.STARS_SPIN -> {
                    for (i in 0 until 3) {
                        val ang = i * PI * 2 / 3 + p * PI * 2
                        val x = cx + cos(ang) * size(0.28)
                        val y = cy - size(0.3) + sin(ang) * size(0.1)
                        star(x, y, size(0.07), Color.rgb(255, 230, 90), fade)
                    }
                    line.color = col(3)
                    line.strokeWidth = f(k)
                    line.alpha = a(fade * 0.4)
                    c.drawOval(f(cx - size(0.32)), f(cy - size(0.4)),
                        f(cx + size(0.32)), f(cy - size(0.2)), line)
                }
                Shape.SPIRAL -> {
                    path.reset()
                    for (i in 0..60) {
                        val q = i / 60.0
                        val ang = q * PI * 6 + p * 5
                        val r = size(0.05 + q * 0.32)
                        val x = cx + cos(ang) * r
                        val y = cy - size(0.2) + sin(ang) * r
                        if (i == 0) path.moveTo(f(x), f(y)) else path.lineTo(f(x), f(y))
                    }
                    line.color = col(3)
                    line.strokeWidth = f(size(0.014) + k)
                    line.alpha = a(fade * 0.8)
                    c.drawPath(path, line)
                    for (i in 0 until 3) {
                        val ang = i * PI * 2 / 3 + p * 3
                        dot(cx + cos(ang) * size(0.45),
                            cy - size(0.2) + sin(ang) * size(0.35),
                            size(0.03), Color.rgb(255, 230, 90), fade, 2)
                    }
                }
                else -> Unit
            }
        }

        private fun star(x: Double, y: Double, r: Double, color: Int, alpha: Double) {
            path.reset()
            for (i in 0 until 10) {
                val ang = -PI / 2 + i * PI / 5
                val rr = if (i % 2 == 0) r else r * 0.45
                val px = x + cos(ang) * rr
                val py = y + sin(ang) * rr
                if (i == 0) path.moveTo(f(px), f(py)) else path.lineTo(f(px), f(py))
            }
            path.close()
            fill.color = color
            fill.alpha = a(alpha)
            c.drawPath(path, fill)
        }

        // ===== 转场 =====
        private fun transition() {
            when (shape) {
                Shape.CRT -> {
                    val q = min(1.0, p * 2)
                    val halfH = h * 0.5 * (1.0 - q)
                    fill.color = Color.WHITE
                    fill.alpha = a(fade * 0.85)
                    c.drawRect(0f, f(cy - halfH - k), f(w), f(cy + halfH + k), fill)
                    if (q > 0.8) {
                        val halfW = w * 0.5 * (1.0 - (q - 0.8) * 5)
                        c.drawRect(f(cx - halfW), f(cy - k * 2),
                            f(cx + halfW), f(cy + k * 2), fill)
                    }
                }
                Shape.DIAMOND -> {
                    val cols = 9
                    val rows = 9
                    val d = min(w / cols, h / rows)
                    for (i in 0 until cols) {
                        for (j in 0 until rows) {
                            val q = (i + j) / (cols + rows - 2.0)
                            if (p * 1.3 < q) continue
                            val x = i * d + d / 2
                            val y = j * d + d / 2
                            path.reset()
                            path.moveTo(f(x), f(y - d * 0.55))
                            path.lineTo(f(x + d * 0.55), f(y))
                            path.lineTo(f(x), f(y + d * 0.55))
                            path.lineTo(f(x - d * 0.55), f(y))
                            path.close()
                            fill.color = col((i + j) % 4 + 1)
                            fill.alpha = a(fade * 0.55)
                            c.drawPath(path, fill)
                        }
                    }
                }
                Shape.IRIS, Shape.STAR_IRIS -> {
                    val rr = size(1.2) * (1.0 - min(1.0, p * 1.4))
                    path.reset()
                    if (shape == Shape.STAR_IRIS) {
                        for (i in 0 until 10) {
                            val ang = -PI / 2 + i * PI / 5 + p * 2
                            val r2 = if (i % 2 == 0) rr else rr * 0.45
                            val px = cx + cos(ang) * r2
                            val py = cy + sin(ang) * r2
                            if (i == 0) path.moveTo(f(px), f(py)) else path.lineTo(f(px), f(py))
                        }
                    } else {
                        path.addCircle(f(cx), f(cy), f(rr), Path.Direction.CW)
                    }
                    path.close()
                    glow.color = col(3)
                    glow.alpha = a(fade * 0.2)
                    c.drawPath(path, glow)
                    line.color = Color.rgb(255, 230, 160)
                    line.strokeWidth = f(size(0.02))
                    line.alpha = a(fade)
                    c.drawPath(path, line)
                }
                Shape.PIXEL -> {
                    val grid = 9
                    val cw = w / grid
                    val ch = h / grid
                    for (i in 0 until grid) {
                        for (j in 0 until grid) {
                            if (rnd(i * 13.0 + j, 4.0) > min(1.0, p * 1.6)) continue
                            fill.color = col((i + j) % colors.size)
                            fill.alpha = a(fade * 0.35)
                            c.drawRect(f(i * cw), f(j * ch),
                                f(i * cw + cw * 0.92), f(j * ch + ch * 0.92), fill)
                        }
                    }
                }
                Shape.SHATTER -> {
                    for (i in 0 until 22) {
                        val ang = rnd(i.toDouble(), 2.0) * PI * 2
                        val d = size(p * 1.1) * (0.4 + rnd(i.toDouble(), 6.0))
                        val x = cx + cos(ang) * d
                        val y = cy + sin(ang) * d * 0.8 + p * p * size(0.4)
                        path.reset()
                        path.moveTo(f(x), f(y - size(0.08)))
                        path.lineTo(f(x + size(0.07)), f(y + size(0.06)))
                        path.lineTo(f(x - size(0.07)), f(y + size(0.05)))
                        path.close()
                        fill.color = col(2 + i % 3)
                        fill.alpha = a(fade * (1.0 - p * 0.5))
                        c.drawPath(path, fill)
                    }
                }
                else -> Unit
            }
        }

        // ===== 拾取物 =====
        private fun pickup() {
            when (shape) {
                Shape.GEM -> {
                    val spin = abs(sin(p * PI * 2))
                    val r = size(0.22)
                    path.reset()
                    path.moveTo(f(cx), f(cy - r))
                    path.lineTo(f(cx + r * spin), f(cy))
                    path.lineTo(f(cx), f(cy + r))
                    path.lineTo(f(cx - r * spin), f(cy))
                    path.close()
                    fill.color = col(3)
                    fill.alpha = a(fade * 0.85)
                    c.drawPath(path, fill)
                    dot(cx, cy, r * 1.4, col(4), fade * 0.25, 4)
                }
                Shape.KEY -> {
                    val r = size(0.1)
                    line.color = Color.rgb(255, 220, 120)
                    line.strokeWidth = f(size(0.025))
                    line.alpha = a(fade)
                    c.drawCircle(f(cx - size(0.18)), f(cy), f(r), line)
                    c.drawLine(f(cx - size(0.08)), f(cy),
                        f(cx + size(0.32)), f(cy), line)
                    c.drawLine(f(cx + size(0.22)), f(cy),
                        f(cx + size(0.22)), f(cy + size(0.1)), line)
                    c.drawLine(f(cx + size(0.32)), f(cy),
                        f(cx + size(0.32)), f(cy + size(0.1)), line)
                    dot(cx, cy, size(0.3), Color.rgb(255, 230, 140), fade * 0.2, 4)
                }
                Shape.POTION -> {
                    val r = size(0.16)
                    fill.color = col(2)
                    fill.alpha = a(fade * 0.8)
                    c.drawRect(f(cx - r * 0.35), f(cy - r * 1.2),
                        f(cx + r * 0.35), f(cy - r * 0.6), fill)
                    c.drawOval(f(cx - r), f(cy - r * 0.7),
                        f(cx + r), f(cy + r), fill)
                    fill.color = Color.rgb(90, 230, 160)
                    fill.alpha = a(fade * 0.9)
                    c.drawOval(f(cx - r * 0.85), f(cy - r * 0.1),
                        f(cx + r * 0.85), f(cy + r * 0.9), fill)
                    dot(cx, cy, r * 1.3, col(3), fade * 0.2, 3)
                }
                Shape.EGG -> {
                    val crack = min(1.0, p * 2)
                    fill.color = Color.rgb(245, 238, 220)
                    fill.alpha = a(fade * 0.9)
                    c.drawOval(f(cx - size(0.18)), f(cy - size(0.26)),
                        f(cx + size(0.18)), f(cy + size(0.22)), fill)
                    if (crack > 0.3) {
                        line.color = col(1)
                        line.strokeWidth = f(size(0.012))
                        line.alpha = a(fade)
                        for (i in 0..6) {
                            val x = cx - size(0.18) + i * size(0.06)
                            val y = cy + sin(i * 2.0) * size(0.04) * crack
                            c.drawLine(f(x), f(y - size(0.03)), f(x + size(0.03)),
                                f(y + size(0.03)), line)
                        }
                        dot(cx, cy - size(0.3) - crack * size(0.2),
                            size(0.05), col(3), fade, 3)
                    }
                }
                else -> Unit
            }
        }

        // ===== 贴地烟雾 =====
        private fun smoke() {
            for (i in 0 until 22) {
                val ph = wrap(p * (0.5 + rnd(i.toDouble(), 4.0) * 0.5) + rnd(i.toDouble(), 7.0), 1.0)
                val x = cx + (rnd(i.toDouble(), 2.0) - 0.5) * size(1.2) * (0.5 + ph)
                val y = h * 0.82 - ph * size(0.55)
                val r = size(0.12 + ph * 0.14)
                glow.color = when {
                    name.contains("poison") || name.contains("spore") ->
                        Color.rgb(150, 210, 70)
                    else -> col(1 + i % 3)
                }
                glow.alpha = a(fade * (1.0 - ph) * 0.22)
                c.drawCircle(f(x), f(y), f(r * 1.6), glow)
                c.drawCircle(f(x), f(y), f(r), glow)
            }
        }

        // ===== 暗影触须 =====
        private fun tentacle() {
            for (i in 0 until 9) {
                val ang = i * PI * 2 / 9 + p * 0.4
                path.reset()
                path.moveTo(f(cx), f(cy))
                var px = cx
                var py = cy
                for (s in 1..8) {
                    val q = s / 8.0
                    val nx = cx + cos(ang + sin(q * 6 + p * 5 + i) * 0.5) * size(0.8) * q
                    val ny = cy + sin(ang + sin(q * 6 + p * 5 + i) * 0.5) * size(0.6) * q
                    path.quadTo(f(px), f(py), f((px + nx) / 2), f((py + ny) / 2))
                    px = nx
                    py = ny
                }
                line.color = col(1 + i % 3)
                line.strokeWidth = f(size(0.03) * (1.0 - p * 0.4) + k)
                line.alpha = a(fade * 0.55)
                c.drawPath(path, line)
            }
            dot(cx, cy, size(0.14), col(0), fade * 0.7, 4)
        }
    }
}
