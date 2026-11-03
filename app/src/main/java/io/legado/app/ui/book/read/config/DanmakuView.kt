package io.legado.app.ui.book.read.config

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import io.legado.app.help.config.AppConfig
import kotlin.math.max
import kotlin.random.Random

/**
 * AI 弹幕浮层：全屏透明 View，只使用顶部一段区域，
 * 弹幕从右往左飘过，多条轨道错开（随机轨道 + 轨道内随机纵向偏移 + 随机速度）。
 *
 * - 零依赖自绘（Canvas + 时间驱动位移），不为每章几条弹幕引入弹幕库；
 * - 不设置 clickable/focusable，触摸事件全部穿透给下层的控制面板；
 * - 同轨道防重叠：上一条弹幕尾部完全进入屏幕且留出间隙后才放行下一条；
 * - 描边用「黑描边 + 白填充」两遍绘制实现，不依赖 setShadowLayer（避开软件层限制）。
 */
class DanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private class FlyingDanmaku(
        val text: String,
        val lane: Int,
        val y: Float,
        var x: Float,
        val speed: Float,
        val width: Float,
        val fillPaint: TextPaint,
        val strokePaint: TextPaint
    )

    /** 每条轨道上一条弹幕（用于防重叠判断） */
    private val laneLast = HashMap<Int, FlyingDanmaku>()
    private val active = ArrayList<FlyingDanmaku>()
    private var lastFrameTime = 0L

    private val basePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
    }
    private val baseStrokePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 6f
        textAlign = Paint.Align.LEFT
    }

    /** 弹幕底色：淡黑半透明，背景明暗跨度大时保证文字可读 */
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
    }

    /** 底色圆角与内边距（px） */
    private val bgRadius = 8f * resources.displayMetrics.density
    private val bgPadX = 8f * resources.displayMetrics.density
    private val bgPadY = 3f * resources.displayMetrics.density

    private val danmakuTextSize: Float
        get() = 15f * resources.displayMetrics.scaledDensity

    private val laneHeight: Float
        get() = danmakuTextSize * 1.6f

    /** 弹幕区顶部起始位置：避开状态栏附近，从屏幕高度约 6% 开始 */
    private val topOffsetRatio = 0.06f

    /** 弹幕基础穿越时长（毫秒），实际速度在此基础上随机浮动 */
    private val baseCrossDurationMs = 6500f

    /** 同轨道放行间隙（px） */
    private val laneGapPx = 24f * resources.displayMetrics.density

    /** 批量发射时的排队计数：同一批弹幕按 1.1s 间隔错开，避免同时起飞挤满轨道 */
    private val pendingRunnables = ArrayList<Runnable>()
    private var queueIndex = 0

    /**
     * 显式帧循环：Choreographer 每帧回调一次 invalidate。
     * 不用 onDraw 末尾 postInvalidateOnAnimation 自驱动——部分机型的
     * 弹窗窗口里它不会持续产生后续帧，导致弹幕只画第一帧（右边缘露个头），
     * 直到下一次外部触发重绘才带着巨大的 dt 一次性跳到屏幕左侧。
     */
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (active.isEmpty()) return
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameLoop() {
        if (active.isEmpty()) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopFrameLoop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    /**
     * 发射一条弹幕。若当前已有排队中的弹幕（如段落追发一批），
     * 自动追加 1.1s/条 的错开延迟，而不是同时起飞。
     */
    fun show(text: String) {
        if (text.isBlank() || width <= 0) return
        val delayMs = queueIndex * 1100L
        queueIndex++
        val runnable = object : Runnable {
            override fun run() {
                queueIndex = (queueIndex - 1).coerceAtLeast(0)
                pendingRunnables.remove(this)
                launchNow(text)
            }
        }
        pendingRunnables.add(runnable)
        postDelayed(runnable, delayMs)
    }

    private fun launchNow(text: String) {
        if (text.isBlank() || width <= 0) return
        val trackCount = AppConfig.danmakuTrackCount
        val viewWidth = width.toFloat()

        // 选轨道：优先选「上一条已完全进场且留出间隙」的轨道；没有则选推进最远的
        var bestLane = -1
        var bestProgress = -1f
        for (lane in 0 until trackCount) {
            val last = laneLast[lane]
            if (last == null || last.x + last.width + laneGapPx + viewWidth * 0.2f < viewWidth) {
                bestLane = lane
                break
            }
            val progress = viewWidth - last.x
            if (progress > bestProgress) {
                bestProgress = progress
                bestLane = lane
            }
        }
        if (bestLane < 0) return

        val scale = 0.9f + Random.nextFloat() * 0.25f
        val fillPaint = TextPaint(basePaint).apply {
            textSize = danmakuTextSize * scale
            alpha = 215 + Random.nextInt(40)
        }
        val strokePaint = TextPaint(baseStrokePaint).apply {
            textSize = fillPaint.textSize
            alpha = fillPaint.alpha
        }
        val textWidth = measureWidth(text, scale)
        val textHeight = textWidthHeight(fillPaint)
        // 轨道内随机纵向偏移，配合随机轨道实现「错开飘」
        val laneTop = height * topOffsetRatio + bestLane * laneHeight
        val jitter = if (laneHeight > textHeight) Random.nextFloat() * (laneHeight - textHeight) else 0f
        // 速度单位是「像素/秒」：穿越时长 baseCrossDurationMs 是毫秒，需先换算成秒，
        // 与 onDraw 里的 dt（秒）匹配。此前误用毫秒值导致实际速度慢 1000 倍，
        // 表现为弹幕只露出右边缘一个头、几乎不移动
        val speed = (viewWidth + textWidth) / (baseCrossDurationMs / 1000f) *
            (0.85f + Random.nextFloat() * 0.4f)

        val danmaku = FlyingDanmaku(
            text = text,
            lane = bestLane,
            y = laneTop + textHeight * 0.9f + jitter,
            x = viewWidth,
            speed = speed,
            width = textWidth,
            fillPaint = fillPaint,
            strokePaint = strokePaint
        )
        laneLast[bestLane] = danmaku
        active.add(danmaku)
        if (lastFrameTime == 0L) {
            lastFrameTime = SystemClock.uptimeMillis()
        }
        startFrameLoop()
    }

    /** 清空正在飘的弹幕（切章/关闭弹幕时调用） */
    fun clearDanmaku() {
        pendingRunnables.forEach { removeCallbacks(it) }
        pendingRunnables.clear()
        queueIndex = 0
        stopFrameLoop()
        active.clear()
        laneLast.clear()
        lastFrameTime = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (active.isEmpty()) {
            lastFrameTime = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        // dt 上限 0.1s：任何原因导致的绘制间隙（切后台、丢帧）都不允许弹幕瞬移
        val dt = if (lastFrameTime == 0L) 0f
        else ((now - lastFrameTime) / 1000f).coerceIn(0f, 0.1f)
        lastFrameTime = now

        val iter = active.iterator()
        while (iter.hasNext()) {
            val d = iter.next()
            d.x -= d.speed * dt
            if (d.x + d.width < 0) {
                if (laneLast[d.lane] === d) {
                    laneLast.remove(d.lane)
                }
                iter.remove()
                continue
            }
            // 淡底色圆角矩形打底，再黑描边 + 白填充，明暗跨度大的背景上也清晰
            val fm = d.fillPaint.fontMetrics
            canvas.drawRoundRect(
                d.x - bgPadX,
                d.y + fm.top - bgPadY,
                d.x + d.width + bgPadX,
                d.y + fm.bottom + bgPadY,
                bgRadius, bgRadius, bgPaint
            )
            canvas.drawText(d.text, d.x, d.y, d.strokePaint)
            canvas.drawText(d.text, d.x, d.y, d.fillPaint)
        }
        // 帧循环由 Choreographer 驱动（见 frameCallback），onDraw 不再自续
    }

    /** View 不可见时暂停帧循环，回到可见且有在屏弹幕时恢复，避免无谓耗电 */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            stopFrameLoop()
            lastFrameTime = 0L
        } else if (active.isNotEmpty()) {
            startFrameLoop()
        }
    }

    private fun measureWidth(text: String, scale: Float): Float {
        basePaint.textSize = danmakuTextSize * scale
        return max(basePaint.measureText(text), 1f)
    }

    private fun textWidthHeight(paint: TextPaint): Float =
        paint.fontMetrics.bottom - paint.fontMetrics.top
}
