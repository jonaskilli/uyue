package io.legado.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 子项左划露出右侧操作按钮（删除）的布局。
 *
 * 用 dispatchTouchEvent 统一接管：在条目任意位置按下都能发起左划（不再只能从右侧
 * 可点击的按钮起手）；竖向滑动与点击仍交还子 View / ExpandableListView，
 * 因此不影响列表滚动、行内按钮点击和长按拖拽排序。
 */
class SwipeRevealLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** index 0 = 右侧露出的操作区，index 1 = 正常内容 */
    private val reveal: View? get() = if (childCount >= 2) getChildAt(0) else null
    private val content: View? get() = if (childCount >= 2) getChildAt(1) else null

    var onOpened: (() -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    var isOpen: Boolean = false
        private set

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var decided = false
    private var swiping = false
    private var hasDown = false

    /** 最近一次按下的 x（相对本行，-1 表示尚未按下），供「只点左侧区域才弹详情」判断 */
    val lastDownX: Float get() = if (hasDown) downX else -1f

    private fun revealWidth(): Float = reveal?.width?.toFloat() ?: 0f

    fun setOpen(open: Boolean, animate: Boolean = true) {
        val v = content ?: return
        isOpen = open
        val target = if (open) -revealWidth() else 0f
        if (!animate) {
            v.animate().cancel()
            v.translationX = target
        } else {
            v.animate().translationX(target).setDuration(160).start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startTx = content?.translationX ?: 0f
                decided = false
                swiping = false
                hasDown = true
                // 交还子 View：按钮点击、展开、长按拖拽都靠这一手 DOWN 正常分发
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!decided) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        decided = true
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // 判定为横划后，撤掉子 View（按钮）可能已有的按压态，避免误触发点击
                        MotionEvent.obtain(
                            ev.downTime, ev.eventTime, MotionEvent.ACTION_CANCEL,
                            ev.x, ev.y, ev.metaState
                        ).let { cancel ->
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                    } else if (abs(dy) > touchSlop) {
                        decided = true
                        swiping = false
                    }
                }
                if (swiping) {
                    content?.translationX = (startTx + ev.x - downX).coerceIn(-revealWidth(), 0f)
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swiping) {
                    val shouldOpen = (content?.translationX ?: 0f) <= -revealWidth() / 2
                    val wasOpen = isOpen
                    setOpen(shouldOpen)
                    if (shouldOpen && !wasOpen) onOpened?.invoke()
                    if (!shouldOpen && wasOpen) onClosed?.invoke()
                    swiping = false
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
            else -> return super.dispatchTouchEvent(ev)
        }
    }
}
