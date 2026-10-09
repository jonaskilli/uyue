package io.legado.app.ui.code

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.math.max
import kotlin.math.min

/**
 * 代码折叠指示器（行号区折叠箭头）
 *
 * 移植自 TTS_Server_Android 的 CodeFoldingOverlay（Compose Canvas 版），
 * 本项目是 View 体系，这里改写为自定义 View：
 *  - 只覆盖编辑器行号区（gutter，左侧固定宽度），不挡代码区域
 *  - ▶ 右指三角：已折叠，点击展开
 *  - ▼ 下指三角：可折叠但未折叠，点击折叠
 *
 * 触摸处理要点：本 View 会消费事件，但会把每个事件原样转发给 [editor]，
 * 这样行号区依然可以正常拖动滚动/定位光标，不会挡住编辑器自身的手势。
 *
 * 通过 sora-editor 的 layout.getCharLayoutOffset / offsetY / rowHeight 计算 Y 坐标，
 * 自动兼容 wordwrap 与非 wordwrap 模式。
 */
class FoldIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    lateinit var editor: CodeEditor
    lateinit var foldingManager: CodeFoldingManager

    /** 折叠状态变化后的回调，用于同步持久化 */
    var onFoldChanged: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // 可折叠按钮：橙色醒目；已折叠按钮：蓝色；折叠行背景：半透明蓝
    private val colorFoldable = 0xFFFF6B35.toInt()
    private val colorFolded = 0xFF4A90D9.toInt()
    private val colorFoldedBg = 0x404A90D9.toInt()

    private var lastOffsetY = -1
    private var lastLineCount = -1

    private val pollRunnable = object : Runnable {
        override fun run() {
            // 编辑器可能还没注入（View 先于 Activity 的赋值被 attach），这里不能 return，
            // 否则轮询链会断掉，之后永远不再刷新
            if (::editor.isInitialized) {
                val offsetY = editor.offsetY
                val lineCount = editor.text.lineCount
                if (offsetY != lastOffsetY || lineCount != lastLineCount) {
                    lastOffsetY = offsetY
                    lastLineCount = lineCount
                    invalidate()
                }
            }
            postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.y)
            return true
        }
    })

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(pollRunnable)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(pollRunnable)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!::editor.isInitialized || !::foldingManager.isInitialized) return
        val text = editor.text
        val rowHeight = editor.rowHeight.toFloat()
        val offsetY = editor.offsetY.toFloat()
        val firstVisibleLine = max(0, editor.firstVisibleLine)
        val lastVisibleLine = min(text.lineCount - 1, editor.lastVisibleLine)
        if (lastVisibleLine < firstVisibleLine) return

        val gutterWidth = width.toFloat()
        val btnHalfSize = rowHeight * 0.32f

        // 已折叠区域：折叠行背景 + ▶（点击展开）
        for (region in foldingManager.getRegions()) {
            val line = region.currentStart
            if (line < firstVisibleLine - 1 || line > lastVisibleLine + 1) continue
            val y = getLineY(line) - offsetY
            if (y < -rowHeight || y > height) continue
            paint.color = colorFoldedBg
            paint.style = Paint.Style.FILL
            canvas.drawRect(0f, y, gutterWidth, y + rowHeight * 3, paint)
            drawTriangle(canvas, gutterWidth / 2f, y + rowHeight / 2f, btnHalfSize, colorFolded, false)
        }

        // 可折叠未折叠区域：▼（点击折叠）
        for (block in foldingManager.findFoldableBlocks()) {
            val line = block.first
            if (line < firstVisibleLine - 1 || line > lastVisibleLine + 1) continue
            if (foldingManager.isFolded(line)) continue
            val y = getLineY(line) - offsetY
            if (y < -rowHeight || y > height) continue
            drawTriangle(canvas, gutterWidth / 2f, y + rowHeight / 2f, btnHalfSize, colorFoldable, true)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!::editor.isInitialized) return false
        // 原样转发给编辑器，保证行号区仍可拖动滚动与定位光标
        val copy = MotionEvent.obtain(event)
        runCatching { editor.dispatchTouchEvent(copy) }
        copy.recycle()
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** 处理点击：判断是否点在某个折叠箭头上 */
    private fun handleTap(yInView: Float) {
        if (!::foldingManager.isInitialized) return
        val rowHeight = editor.rowHeight.toFloat()
        val offsetY = editor.offsetY.toFloat()
        val clickContentY = yInView + offsetY
        val firstVisibleLine = max(0, editor.firstVisibleLine)
        val lastVisibleLine = min(editor.text.lineCount - 1, editor.lastVisibleLine)

        // 已折叠 → 展开
        for (region in foldingManager.getRegions()) {
            val line = region.currentStart
            if (line < firstVisibleLine - 1 || line > lastVisibleLine + 1) continue
            val y = getLineY(line)
            if (clickContentY in y..(y + rowHeight)) {
                // 展开前记住滚动偏移：text.replace 会把滚动位置带到内容末尾，需要立即还原
                val savedOffsetY = editor.offsetY
                editor.setSelection(line, 0, true)
                foldingManager.unfoldAt(line)
                runCatching {
                    val scroller = editor.scroller
                    scroller.startScroll(0, savedOffsetY, 0, 0, 0)
                    scroller.forceFinished(true)
                }
                editor.setSelection(line, 0, true)
                editor.invalidate()
                onFoldChanged?.invoke()
                return
            }
        }

        // 未折叠 → 折叠
        for (block in foldingManager.findFoldableBlocks()) {
            val line = block.first
            if (line < firstVisibleLine - 1 || line > lastVisibleLine + 1) continue
            if (foldingManager.isFolded(line)) continue
            val y = getLineY(line)
            if (clickContentY in y..(y + rowHeight)) {
                editor.setSelection(line, 0, true)
                foldingManager.fold(block.first, block.last)
                editor.invalidate()
                onFoldChanged?.invoke()
                return
            }
        }
    }

    /**
     * 获取指定逻辑行在内容坐标中的 Y（顶部）
     * 优先用 layout.getCharLayoutOffset（[0] 是字符底部，减去行高得到行顶部），
     * 失败则退化为 行高×行号（自动换行开启时该退化的位置会有偏差，仅作兜底）
     */
    private fun getLineY(line: Int): Float {
        return try {
            val offsets = editor.layout.getCharLayoutOffset(line, 0, offsetBuffer)
            offsets[0] - editor.rowHeight.toFloat()
        } catch (_: Exception) {
            editor.rowHeight.toFloat() * line
        }
    }

    /** getCharLayoutOffset 的输出缓冲区（(II[F)[F，第三参为复用缓冲） */
    private val offsetBuffer = FloatArray(4)

    private fun drawTriangle(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        halfSize: Float,
        color: Int,
        pointingDown: Boolean,
    ) {
        path.reset()
        if (pointingDown) {
            path.moveTo(centerX - halfSize, centerY - halfSize)
            path.lineTo(centerX + halfSize, centerY - halfSize)
            path.lineTo(centerX, centerY + halfSize)
        } else {
            path.moveTo(centerX - halfSize, centerY - halfSize)
            path.lineTo(centerX + halfSize, centerY)
            path.lineTo(centerX - halfSize, centerY + halfSize)
        }
        path.close()
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }

    private companion object {
        const val POLL_INTERVAL_MS = 50L
    }
}
