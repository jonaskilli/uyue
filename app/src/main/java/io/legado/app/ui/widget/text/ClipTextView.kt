package io.legado.app.ui.widget.text

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 单行文字按「像素」裁剪，而不是按字素簇边界丢弃。
 *
 * TextView 默认的 BoringLayout / StaticLayout 在测量一行能放多少字时，会把
 * emoji + 变体选择符、ZWJ 组合等视为不可分的 grapheme cluster：整簇放不下就
 * 整簇不绘制，于是名字后半段（图标 + 英文）会留下一大片空白，而不是显示到边界。
 *
 * 这里复用 TextView 自己的 Paint（字号、颜色、字体回退、emoji 支持全部保留），
 * 直接 drawText 画一整行不换行；Canvas 由 View 系统自动裁剪到本 View 边界，
 * 所以超出部分会按像素切掉，最后一个字哪怕只画一半也会画出来。
 */
class ClipTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    init {
        maxLines = 1
        ellipsize = null
    }

    override fun onDraw(canvas: Canvas) {
        val content = text?.toString().orEmpty()
        if (content.isEmpty()) return
        val p = paint
        // TextView 只在自己的 onDraw() 里执行 mTextPaint.setColor(mCurTextColor)，
        // 而本类重写 onDraw 且不调用 super，paint 会永远停在构造时的默认纯黑：
        // 于是 XML 的 android:textColor 与代码里的 setTextColor() 都形同虚设，
        // 夜间模式下书源列表就是黑字压深底。这里每次绘制前手动同步当前文字色。
        p.color = currentTextColor
        val fm = p.fontMetrics
        // 垂直居中；从 paddingLeft 起画，右侧超出 View 边界的部分由 Canvas 裁切
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(content, paddingLeft.toFloat(), baseline, p)
    }
}
