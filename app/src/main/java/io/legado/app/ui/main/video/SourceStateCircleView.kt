package io.legado.app.ui.main.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 视频源总开关的三态指示圆（无限刷分类筛选面板用）。
 *
 * 三态：
 * - [State.OFF]    关闭：灰色空心圆（只有描边）
 * - [State.ON]     开启：红色实心圆
 * - [State.PARTIAL] 部分开启：红色实心圆，中间叠一个白色空心圆（半径 = 外圆半径的一半）
 *
 * 之所以要"部分开启"：一个视频源下面有多个发现分类，只勾了一部分时，
 * 用纯开关没法表达（既不是全开也不是全关）。
 */
class SourceStateCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class State { OFF, ON, PARTIAL }

    var state: State = State.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
                contentDescription = when (value) {
                    State.OFF -> "关闭"
                    State.ON -> "开启"
                    State.PARTIAL -> "部分开启"
                }
            }
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = COLOR_ON
    }
    private val offPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = COLOR_OFF
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    init {
        // 行整体有展开/折叠的点击，这里必须可点才能优先消费掉点击
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (SIZE_DP * resources.displayMetrics.density).toInt() +
                paddingLeft + paddingRight
        val sizeH = (SIZE_DP * resources.displayMetrics.density).toInt() +
                paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(sizeH, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        val r = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom) / 2f
        if (r <= 0f) return
        when (state) {
            State.OFF -> {
                // 空心灰圆：描边贴在圆内侧，避免被裁掉；粗一点，免得在浅色背景上看不清
                offPaint.strokeWidth = (r / 3f).coerceAtLeast(1.5f)
                canvas.drawCircle(cx, cy, r - offPaint.strokeWidth / 2f, offPaint)
            }

            State.ON -> {
                canvas.drawCircle(cx, cy, r, fillPaint)
            }

            State.PARTIAL -> {
                canvas.drawCircle(cx, cy, r, fillPaint)
                // 白色空心圆：半径 = 外圆半径的一半
                innerPaint.strokeWidth = (r / 5f).coerceAtLeast(1f)
                canvas.drawCircle(cx, cy, r / 2f, innerPaint)
            }
        }
    }

    companion object {
        private const val SIZE_DP = 26
        private val COLOR_ON = Color.parseColor("#E53935")   // 开启/部分开启：红色
        private val COLOR_OFF = Color.parseColor("#757575")  // 关闭：深灰描边（浅色背景上也看得清）
    }
}
