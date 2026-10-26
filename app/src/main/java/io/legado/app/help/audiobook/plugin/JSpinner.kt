package io.legado.app.help.audiobook.plugin

import io.legado.app.R
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.max

/**
 * 插件 UI 下拉选择控件。
 * 继承 FrameLayout，内部包含一个标签 TextView 和一个 Spinner。
 * 插件 JS 代码通过 new JSpinner(context, "hint") 创建，直接作为 View 使用。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@SuppressLint("ViewConstructor")
class JSpinner @JvmOverloads constructor(
    context: Any,
    val hint: String = ""
) : FrameLayout(context as Context) {

    private val ctx = context as Context

    companion object {
        const val TAG = "JSpinner"

        /**
         * 选项数超过该值时，点选择框改弹「带搜索框的列表」而不是原生下拉。
         * 少量选项（如音效模式 8 项、采样率 3 项）走原生下拉更快，不必加搜索。
         */
        const val SEARCH_THRESHOLD = 10

        /** 搜索弹窗里列表的最大高度（dp） */
        private const val SEARCH_LIST_MAX_HEIGHT_DP = 420
    }

    private val mLabelView: TextView
    private val mSpinner: Spinner

    private val mItems = mutableListOf<Item>()

    var items: List<Item>
        get() = mItems
        set(value) {
            mFireListener = false
            mItems.clear()
            mItems.addAll(value)
            refreshAdapter()
            mFireListener = true
        }

    /**
     * 重载 setItems，接受任意类型（Rhino JS 数组或 Java List）。
     * Rhino 传 JS 数组时可能不会自动转换为 List<Item>，这里手动处理。
     */
    fun setItems(items: Any?) {
        mFireListener = false
        mItems.clear()
        when (items) {
            is List<*> -> {
                items.forEach { item ->
                    if (item is Item) mItems.add(item)
                }
            }
            is Array<*> -> {
                items.forEach { item ->
                    if (item is Item) mItems.add(item)
                }
            }
            else -> {
                // 尝试作为可迭代对象处理（Rhino NativeArray 实现了 List 接口）
                try {
                    val iter = (items as? Iterable<*>)?.iterator()
                    while (iter?.hasNext() == true) {
                        val item = iter.next()
                        if (item is Item) mItems.add(item)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("JSpinner", "setItems: unsupported type ${items?.javaClass?.name}", e)
                }
            }
        }
        refreshAdapter()
        mFireListener = true
    }

    var selectedPosition: Int
        get() = mSpinner.selectedItemPosition
        set(value) {
            mFireListener = false
            mSpinner.setSelection(max(0, value))
            mFireListener = true
        }

    var value: Any
        get() = mItems.getOrElse(selectedPosition) { mItems.getOrNull(0) ?: Item("", "") }.value
        set(value) {
            val idx = mItems.indexOfFirst { it.value == value }
            selectedPosition = max(0, idx)
        }

    private var mListener: OnItemSelectedListener? = null
    private var mFireListener = true  // 控制 onItemSelected 是否触发回调

    interface OnItemSelectedListener {
        fun onItemSelected(spinner: JSpinner, position: Int, item: Item)
    }

    fun setOnItemSelected(listener: OnItemSelectedListener?) {
        mListener = listener
    }

    fun select(position: Int) {
        mFireListener = false
        selectedPosition = position
        mFireListener = true
    }

    init {
        val padding = (8 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        mLabelView = TextView(ctx).apply {
            text = hint
            textSize = 13f
            setPadding(0, 0, 0, (4 * resources.displayMetrics.density).toInt())
        }
        mSpinner = Spinner(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            // 默认带边框（描边跟随字体色，深浅模式自动切换），插件未自定义背景时生效
            background = ctx.getDrawable(R.drawable.spinner_border)
            // 给边框内文字留上下左右间距，避免文字紧贴边框（与界面原生 Spinner 观感一致）
            val h = (12 * ctx.resources.displayMetrics.density).toInt()
            val v = (8 * ctx.resources.displayMetrics.density).toInt()
            setPadding(h, v, h, v)
        }
        container.addView(mLabelView)
        container.addView(mSpinner)
        addView(container)

        mSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!mFireListener) return
                val item = mItems.getOrNull(position) ?: return
                val listener = mListener ?: return
                val cx = org.mozilla.javascript.Context.enter() as? com.script.rhino.RhinoContext
                try {
                    cx?.allowScriptRun = true
                    listener.onItemSelected(this@JSpinner, position, item)
                } catch (e: Exception) {
                    // 插件 JS 回调中可能引用了尚未创建的变量（onLoadUI 还没执行完），
                    // 这是 Spinner 异步触发 onItemSelected 导致的，忽略即可。
                    android.util.Log.w("JSpinner", "onItemSelected callback error (likely during init, safe to ignore): ${e.message}")
                } finally {
                    cx?.allowScriptRun = false
                    org.mozilla.javascript.Context.exit()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ★ 选项数超过 SEARCH_THRESHOLD 时，点选择框改弹「带搜索框的列表」。
        //   豆包 374/745 音色塞进原生下拉根本没法挑。只拦 ACTION_UP，其余让原生处理；
        //   未超阈值返回 false，维持原生下拉，观感不变。
        mSpinner.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && shouldUseSearch()) {
                openSearchDialog()
                true
            } else {
                false
            }
        }
    }

    private fun shouldUseSearch(): Boolean = mItems.size > SEARCH_THRESHOLD

    /**
     * 带搜索框的选项列表弹窗。
     *
     * 只做「过滤 + 选中」，不动 mSpinner 的 adapter —— mSpinner 始终挂完整列表，
     * 因此 selectedPosition / value / select() 这些对外语义完全不变，
     * 插件拿到的还是原始 Item（而不是过滤后的子集序号）。
     */
    private fun openSearchDialog() {
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val input = EditText(ctx).apply {
            hint = "搜索（输入关键词过滤）"
            setSingleLine(true)
        }
        val emptyView = TextView(ctx).apply {
            text = "无匹配结果"
            textSize = 13f
            setPadding(0, pad / 2, 0, pad / 2)
            visibility = android.view.View.GONE
        }
        val listView = ListView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (SEARCH_LIST_MAX_HEIGHT_DP * density).toInt()
            )
        }
        box.addView(input)
        box.addView(listView)
        box.addView(emptyView)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(hint.ifBlank { "选择" })
            .setView(box)
            .create()

        fun refresh(query: String) {
            val q = query.trim().lowercase()
            val originalIndexes: List<Int> =
                if (q.isEmpty()) mItems.indices.toList()
                else mItems.mapIndexedNotNull { i, it ->
                    if (it.name.toString().lowercase().contains(q)) i else null
                }
            emptyView.visibility = if (originalIndexes.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            listView.visibility = if (originalIndexes.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            listView.adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_list_item_1,
                originalIndexes.map { mItems[it].name.toString() }
            )
            listView.setOnItemClickListener { _, _, pos, _ ->
                val origin = originalIndexes.getOrNull(pos) ?: return@setOnItemClickListener
                dialog.dismiss()
                // mSpinner 挂的是完整列表，直接按原始索引选中；
                // 随之异步触发的 onItemSelected 会把原始 Item 回调给插件
                selectedPosition = origin
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString().orEmpty()) }
        })
        refresh("")
        dialog.show()
    }

    private fun refreshAdapter() {
        val names = mItems.map { it.name.toString() }
        val adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        mSpinner.adapter = adapter
    }
}
