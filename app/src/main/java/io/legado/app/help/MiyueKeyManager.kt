package io.legado.app.help

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.DragEvent
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseExpandableListAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.SwipeRevealLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * 密钥（miyue）管理：保存密钥 / 恢复密钥。
 *
 * 逻辑原先只写在 [io.legado.app.ui.book.read.config.CharacterManagerDialog] 里，
 * 现在抽出来供「角色管理」与「朗读脚本」两个界面共用，避免两处实现走偏。
 *
 * 存储：
 *  - keys.json：{密钥名: 密钥内容, ...}（旧格式，保持兼容）
 *  - miyue.txt：当前生效的密钥内容（JS 侧 KEY_FILE 读的就是它）
 *  - SharedPreferences 的 miyue：Kotlin 侧读取的当前密钥
 * 目录统一走 [EngineDataDir.baseDir]，与角色管理同源。
 */
@Suppress("DEPRECATION")
object MiyueKeyManager {

    private const val KEY_FILE = "miyue.txt"
    private const val KEY_LIST_FILE = "keys.json"
    private const val PREF_KEY = "miyue"

    /** 保存密钥界面「免费大模型合集」跳转的网址 */
    private const val FREE_MODELS_URL = "https://sps.yy-gp.com/hho99/i.html"

    /** 测试 / 取模型都是网络操作，统一挂在这个 scope 上，界面关闭时取消 */
    private val keyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 对外入口 ====================

    /**
     * 保存密钥：单界面完成，不再分两步。
     *
     * 顶部一行：右上角「免费大模型合集」调系统浏览器打开白嫖渠道页；
     * 下面是密钥名称；再下面两个切换按钮选录入方式：
     *  - 传统方式：接口地址 / 模型名字 / 密钥 三个输入框，模型名字旁有「获取模型」，
     *    与密钥管理里同一套（拉模型列表，点选即用）；
     *  - @@方式：一个输入框，直接粘贴脚本侧约定的 `地址@@模型@@密钥` 文本。
     * 两种方式最终都存成同一格式（传统方式由三个框拼成一组 @@ 格式）。
     * 校验不过不关弹窗，改完直接再点保存。
     * @param onSaved 保存成功后的回调，用于密钥管理列表原地刷新。
     */
    fun saveKey(context: Context, onSaved: (() -> Unit)? = null) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        fun addInput(hint: String, multiLine: Boolean = false): TextInputEditText =
            TextInputEditText(context).apply {
                this.hint = hint
                // 提示文字调小一档：默认 16sp 在这个弹窗里显得太挤
                textSize = 13f
                if (multiLine) {
                    setSingleLine(false)
                    minLines = 3
                }
                // 不加内边距——底部 padding 会把提示文字压到下划线上，
                // 输入框之间的距离交给外边距（addSpaced）
            }

        /** 统一给输入框/行加上下间距：竖向拉开距离，避免整块挤成一团 */
        fun addSpaced(
            parent: LinearLayout,
            view: View,
            topPx: Int = 22,
        ) {
            parent.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = topPx }
            )
        }

        // ===== 顶部一行：右侧「免费大模型合集」 =====
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val btnFreeModels = Button(context).apply {
            text = "免费大模型合集"
            textSize = 11f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            setPadding(20, 10, 20, 10)
            backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF9800"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(FREE_MODELS_URL)))
                }.onFailure { toast(context, "没有找到可用的浏览器") }
            }
        }
        headerRow.addView(
            btnFreeModels,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(headerRow)

        // ===== 密钥名称 =====
        val etName = addInput("密钥名称")
        addSpaced(container, etName)

        // ===== 方式切换按钮 =====
        val modeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeButton(text: String): Button = Button(context).apply {
            this.text = text
            textSize = 12f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            setPadding(0, 12, 0, 12)
            setTextColor(Color.WHITE)
        }
        val btnModeTrad = modeButton("传统方式")
        val btnModeRaw = modeButton("@@方式")
        modeRow.addView(
            btnModeTrad,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
        )
        modeRow.addView(
            btnModeRaw,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        addSpaced(container, modeRow)

        // ===== 传统方式：接口地址 / 模型名字（+获取模型）/ 密钥 =====
        // etKey 声明放在「获取模型」按钮之前：按钮的点击回调要用它
        val etKey = addInput("密钥")
        val panelTrad = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val etEndpoint = addInput("接口地址（留空用默认智谱地址）")
        panelTrad.addView(etEndpoint)
        val modelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val etModel = addInput("模型名字")
        modelRow.addView(
            etModel,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val btnFetchModels = Button(context).apply {
            text = "获取模型"
            textSize = 11f
            minWidth = 0
            minHeight = 0
            includeFontPadding = false
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                val key = etKey.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    toast(context, "请先填密钥，再获取模型")
                    return@setOnClickListener
                }
                val entry = ApiEntry(
                    endpoint = MiyueKeyTester.normalizeEndpoint(
                        etEndpoint.text?.toString().orEmpty()
                    ),
                    model = "",
                    key = key,
                    scene = "保存密钥",
                )
                showModelsDialog(context, entry, onPick = { picked -> etModel.setText(picked) })
            }
        }
        modelRow.addView(
            btnFetchModels,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8; gravity = Gravity.CENTER_VERTICAL }
        )
        addSpaced(panelTrad, modelRow)
        addSpaced(panelTrad, etKey)
        addSpaced(container, panelTrad)

        // ===== @@方式：一个多行输入框 =====
        val panelRaw = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val etRaw = addInput(
            "地址@@模型@@密钥（多组连着写即可；纯密钥只填密钥）",
            multiLine = true
        )
        addSpaced(panelRaw, etRaw, 10)
        addSpaced(container, panelRaw)

        var traditional = true
        fun paintMode() {
            btnModeTrad.backgroundTintList = ColorStateList.valueOf(
                if (traditional) context.primaryColor else Color.parseColor("#9E9E9E")
            )
            btnModeRaw.backgroundTintList = ColorStateList.valueOf(
                if (traditional) Color.parseColor("#9E9E9E") else context.primaryColor
            )
            panelTrad.visibility = if (traditional) View.VISIBLE else View.GONE
            panelRaw.visibility = if (traditional) View.GONE else View.VISIBLE
        }
        btnModeTrad.setOnClickListener { traditional = true; paintMode() }
        btnModeRaw.setOnClickListener { traditional = false; paintMode() }
        paintMode()

        val dialog = AlertDialog.Builder(context)
            .setTitle("保存密钥")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()

        // 保存按钮接管点击：校验不过就不关弹窗，免得填的东西全丢
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存") { _, _ -> }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                toast(context, "密钥名称不能为空")
                return@setOnClickListener
            }
            val content = if (traditional) {
                val key = etKey.text?.toString()?.trim().orEmpty()
                if (key.isEmpty()) {
                    toast(context, "密钥不能为空")
                    return@setOnClickListener
                }
                val endpoint = MiyueKeyTester.normalizeEndpoint(
                    etEndpoint.text?.toString().orEmpty()
                )
                val model = etModel.text?.toString()?.trim().orEmpty()
                    .ifEmpty { MiyueKeyTester.DEFAULT_MODEL }
                "$endpoint@@$model@@$key"
            } else {
                etRaw.text?.toString()?.trim().orEmpty()
            }
            if (content.isEmpty()) {
                toast(context, "密钥内容不能为空")
                return@setOnClickListener
            }
            saveKeyToJson(name, content)
            onSaved?.invoke()
            toast(context, "密钥已保存: $name")
            dialog.dismiss()
        }
    }

    /**
     * 密钥管理：可折叠列表 + 顶部「正在使用」分区。
     *
     * 顶部 = 当前生效的密钥单独一块（卡片底色 + 粗分隔线与下方隔开，右侧无「使用」按钮）；
     * 下方组 = 一个已保存的密钥（整行点击展开/折叠，右侧可 恢复 / 测试整份 / 修改）；
     * 子 = 该密钥里解析出来的每一个 API（只显示模型名，右侧可单独 测试 / 取该接口的全部模型）。
     * 底部四个按钮：测试全部 / 删除密钥 / 新增 / 取消。「新增」直接复用 [saveKey]，
     * 保存后原地刷新列表，因此没有密钥时也能打开空界面添加第一把。
     * 两个分区共用本适配器，任一区改动后互相刷新，永远显示最新状态。
     */
    fun restoreKey(context: Context) {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_key_list)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val listView = dialog.findViewById<ExpandableListView>(R.id.lv_keys)
        val currentListView = dialog.findViewById<ExpandableListView>(R.id.lv_current_key)
        val tvCurrentMissing = dialog.findViewById<TextView>(R.id.tv_current_missing)
        val btnTestAll = dialog.findViewById<Button>(R.id.btn_test_all)
        val btnDelete = dialog.findViewById<Button>(R.id.btn_delete_key)
        val btnAdd = dialog.findViewById<Button>(R.id.btn_add_key)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        val tvHint = dialog.findViewById<TextView>(R.id.tv_key_hint)
        if (names.isEmpty()) {
            tvHint.text = "暂无已保存的密钥，点下方「新增」添加"
        }

        // 分隔线必须显式给成 Drawable：
        // ExpandableListView 画子项分隔线用的是 mChildDivider，而它默认不跟随 divider 属性，
        // 部分 ROM 又不把 @color/divider 解析成 Drawable —— 两者任一为 null，
        // 一展开就在 drawDivider() 里 NPE（vivo/Android 15 实测崩溃）。
        val dividerDrawable = ColorDrawable(ContextCompat.getColor(context, R.color.divider))
        listView.divider = dividerDrawable
        listView.dividerHeight = 1
        listView.setChildDivider(dividerDrawable)
        // 顶部「正在使用」分区同理，两处列表都可能展开画子项分隔线
        val currentDivider = ColorDrawable(ContextCompat.getColor(context, R.color.divider))
        currentListView.divider = currentDivider
        currentListView.dividerHeight = 1
        currentListView.setChildDivider(currentDivider)

        var bottomAdapter: KeyExpandableAdapter? = null
        var topAdapter: KeyExpandableAdapter? = null

        // 长按抓起子行开始拖拽，位置随 DragEvent 传给落点行
        fun startChildDrag(view: View, adapter: KeyExpandableAdapter, pos: DragChildPos) {
            // 左划正处于打开态时立即归位（无动画），免得快照/影子带着删除按钮
            (view as? SwipeRevealLayout)?.setOpen(false, animate = false)
            // 先在可见状态下截好原样影子并发起拖拽，再把该行标记为空白占位并刷新
            val shadow = OpaqueDragShadow(view)
            view.startDrag(null, shadow, pos, 0)
            adapter.dragPos = pos
            adapter.notifyDataSetChanged()
        }

        /**
         * 重建顶部「正在使用的密钥」分区。
         * 任何一区数据有变（换模型 / 删 API / 改名 / 切换使用中）都从这里走一遍：
         * 重新按 miyue.txt 的内容匹配当前密钥名，整块重建，保证永远显示最新状态。
         */
        fun refreshCurrentSection() {
            val content = readTxtFile(KEY_FILE)
            // 底部组行的「使用中」标记跟着当前密钥走
            bottomAdapter?.updateCurrentKey(content)
            val freshMap = getKeyMap()
            val freshNames = getKeyNames(freshMap)
            val currentName = freshNames.firstOrNull { freshMap.optString(it, "") == content }
            // 旧适配器的测试协程一并取消，避免换块之后旧结果还往回写
            topAdapter?.cancel()
            topAdapter = null
            if (content.isBlank() || currentName == null) {
                // txt 里没内容，或内容对不上任何已保存密钥（如整份被删）
                currentListView.visibility = View.GONE
                tvCurrentMissing.visibility = View.VISIBLE
                return
            }
            tvCurrentMissing.visibility = View.GONE
            currentListView.visibility = View.VISIBLE
            val top = KeyExpandableAdapter(
                context = context,
                names = mutableListOf(currentName),
                keyMap = freshMap,
                currentKey = content,
                // 顶部这份本来就在用，「使用」按钮按分区标志隐藏
                onRestore = { },
                onToggle = { gp ->
                    if (currentListView.isGroupExpanded(gp)) {
                        currentListView.collapseGroup(gp)
                    } else {
                        currentListView.expandGroup(gp)
                    }
                },
                isCurrentSection = true,
                // 顶部改了 → 底部列表同步刷（refresh 内部会再触发底部 onDataChanged
                // 回调 refreshCurrentSection，顶部最终被整块重建，两侧一致）
                onDataChanged = { bottomAdapter?.refresh() },
            )
            currentListView.setAdapter(top)
            currentListView.expandGroup(0)
            topAdapter = top
            // 与下方列表同款的行级交互：点子行看详情、长按删单个 API
            currentListView.setOnChildClickListener { _, _, g, c, _ ->
                top.showDetail(g, c)
                true
            }
            currentListView.setOnItemLongClickListener { _, view, position, _ ->
                val packed = currentListView.getExpandableListPosition(position)
                if (ExpandableListView.getPackedPositionType(packed)
                    == ExpandableListView.PACKED_POSITION_TYPE_CHILD
                ) {
                    startChildDrag(
                        view,
                        top,
                        DragChildPos(
                            ExpandableListView.getPackedPositionGroup(packed),
                            ExpandableListView.getPackedPositionChild(packed)
                        )
                    )
                }
                true
            }
        }

        val adapter = KeyExpandableAdapter(
            context = context,
            names = names,
            keyMap = keyMap,
            // 当前生效的是哪一份：组行标「使用中」，删除 API 时才知道要不要同步刷新
            currentKey = readTxtFile(KEY_FILE),
            onRestore = { name ->
                // 现取现用：列表可能刚被改过，外层 keyMap 引用不保证最新
                val key = getKeyMap().optString(name, "")
                writeTxtFile(KEY_FILE, key)
                saveKeyToPrefs(context, key)
                toast(context, "已恢复密钥: $name")
                // 不关窗：顶部「正在使用」立刻跟着换，列表留在原地
                refreshCurrentSection()
            },
            onToggle = { gp ->
                if (listView.isGroupExpanded(gp)) {
                    listView.collapseGroup(gp)
                } else {
                    listView.expandGroup(gp)
                }
            },
            // 底部列表有任何数据改动，顶部「正在使用」都重建一遍
            onDataChanged = { refreshCurrentSection() },
        )
        bottomAdapter = adapter

        // 必须用 ExpandableListView 自己的 setAdapter：
        // 直接赋 .adapter 会解析成父类 AdapterView 的 ListAdapter，类型对不上
        listView.setAdapter(adapter)

        // 整行点击 = 展开/折叠（展开后才看得到里面的 API）
        listView.setOnGroupClickListener { _, _, groupPosition, _ ->
            if (listView.isGroupExpanded(groupPosition)) {
                listView.collapseGroup(groupPosition)
            } else {
                listView.expandGroup(groupPosition)
            }
            true
        }
        // 点子行 = 打开这个 API 的详情菜单（复制地址 / 复制密钥 / 删除该 API）
        listView.setOnChildClickListener { _, _, g, c, _ ->
            adapter.showDetail(g, c)
            true
        }
        // 长按子项 = 抓起这一条拖动排序（左划才露出删除）。整份（组）的删除走组行
        // 「修改」里的删除按钮，那里删完会 refresh() 刷新整个列表。
        listView.setOnItemLongClickListener { _, view, position, _ ->
            val packed = listView.getExpandableListPosition(position)
            if (ExpandableListView.getPackedPositionType(packed)
                == ExpandableListView.PACKED_POSITION_TYPE_CHILD
            ) {
                startChildDrag(
                    view,
                    adapter,
                    DragChildPos(
                        ExpandableListView.getPackedPositionGroup(packed),
                        ExpandableListView.getPackedPositionChild(packed)
                    )
                )
            }
            // 一律消费掉，避免长按再冒泡出别的默认行为
            true
        }

        btnTestAll.setOnClickListener {
            // 全部展开：不展开的话圆点藏在折叠里，测完也看不见
            for (i in names.indices) listView.expandGroup(i)
            btnTestAll.isEnabled = false
            adapter.testAll(
                onProgress = { done, total ->
                    btnTestAll.text = "$done/$total"
                    tvHint.text = "测试中 $done/$total"
                },
                onDone = { ok, total ->
                    btnTestAll.isEnabled = true
                    btnTestAll.text = "测试全部"
                    tvHint.text = "可用 $ok / 共 $total　·　绿点可用，红点失败"
                    toast(context, "测试完成：可用 $ok / 共 $total")
                }
            )
        }
        // 下面三个都只是「在上面叠一层」，不再先关掉密钥列表——
        // 删除、新增做完都原地刷新，取消才关窗
        btnDelete.setOnClickListener {
            selectKeyToDelete(context) { adapter.refresh() }
        }
        btnAdd.setOnClickListener {
            // 直接复用「保存密钥」窗口；保存成功后 adapter 会重读 keys.json，
            // 并通过 onDataChanged 顺带刷新顶部「正在使用」分区
            saveKey(context) {
                adapter.refresh()
                tvHint.text = "展开密钥可查看并测试其中的 API"
            }
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            adapter.cancel()
            topAdapter?.cancel()
        }

        refreshCurrentSection()
        dialog.show()
    }

    /**
     * 某个接口上的可用模型：带搜索框（模型多的时候靠它筛）。
     *
     * 点条目 = 把这份密钥里该 API 的模型直接换成它，同时复制一份到剪贴板；
     * [onPick] 传 null 时只复制、不改动密钥。
     *
     * [allowMulti]=true 时每行左侧出现勾选框：未有勾选时点整行仍是原来的单选切换；
     * 一旦有勾选，点整行改为切换勾选，并出现「全部添加」按钮，确认后回调 [onBatchAdd]。
     * 勾选状态用模型名（非 position）保存，搜索过滤重建列表后依然正确。
     */
    fun showModelsDialog(
        context: Context,
        entry: ApiEntry,
        onPick: ((model: String) -> Unit)? = null,
        allowMulti: Boolean = false,
        onBatchAdd: ((models: List<String>) -> Unit)? = null,
    ) {
        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_models)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        dialog.findViewById<TextView>(R.id.tv_model_title).text =
            "可用模型（当前：${entry.model}）"
        val tvTip = dialog.findViewById<TextView>(R.id.tv_model_tip)
        val etSearch = dialog.findViewById<EditText>(R.id.et_model_search)
        val listView = dialog.findViewById<ListView>(R.id.lv_models)
        val btnClose = dialog.findViewById<Button>(R.id.btn_model_close)
        val btnAdd = dialog.findViewById<Button>(R.id.btn_model_add)

        tvTip.text = "加载中…（${entry.host()}）"

        var allModels: List<String> = emptyList()
        // 勾选收集：用模型名做 key，搜索过滤重建 adapter 后状态不丢
        val selected = LinkedHashSet<String>()
        fun updateAddButton() {
            btnAdd.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
            btnAdd.text = "全部添加（${selected.size}）"
        }
        val adapter = object : ArrayAdapter<String>(
            context, R.layout.dialog_models_item, R.id.tv_model_name, mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.dialog_models_item, parent, false)
                val model = getItem(position) ?: ""
                view.findViewById<TextView>(R.id.tv_model_name).text = model
                view.findViewById<CheckBox>(R.id.cb_model_select).apply {
                    visibility = if (allowMulti && onBatchAdd != null) View.VISIBLE else View.GONE
                    // 先清监听再设状态，避免复用 view 时 setOnCheckedChangeListener 误触发
                    setOnCheckedChangeListener(null)
                    isChecked = model in selected
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selected.add(model) else selected.remove(model)
                        updateAddButton()
                    }
                }
                // 只有点这个按钮才复制；复制完弹窗保持打开，可以接着复制下一个
                view.findViewById<Button>(R.id.btn_model_copy).setOnClickListener {
                    val cm =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("模型", model))
                    toast(context, "已复制: $model")
                }
                return view
            }
        }
        listView.adapter = adapter
        // 点整行：未勾选时=原来的单选切换；已勾选时=切换这一行的勾选
        listView.setOnItemClickListener { _, _, position, _ ->
            val model = adapter.getItem(position) ?: return@setOnItemClickListener
            if (allowMulti && onBatchAdd != null && selected.isNotEmpty()) {
                if (!selected.add(model)) selected.remove(model)
                adapter.notifyDataSetChanged()
                updateAddButton()
                return@setOnItemClickListener
            }
            if (onPick == null) {
                val cm =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("模型", model))
                toast(context, "已复制: $model")
                return@setOnItemClickListener
            }
            if (model == entry.model) {
                toast(context, "当前就是这个模型")
                return@setOnItemClickListener
            }
            onPick.invoke(model)
            dialog.dismiss()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val kw = s?.toString()?.trim().orEmpty()
                adapter.clear()
                if (kw.isEmpty()) {
                    adapter.addAll(allModels)
                } else {
                    adapter.addAll(allModels.filter { it.contains(kw, ignoreCase = true) })
                }
            }
        })
        btnClose.setOnClickListener { dialog.dismiss() }
        btnAdd.setOnClickListener {
            if (selected.isEmpty()) return@setOnClickListener
            onBatchAdd?.invoke(selected.toList())
            dialog.dismiss()
        }

        keyScope.launch {
            val result = MiyueKeyTester.fetchModels(entry)
            if (result.error != null) {
                tvTip.text = "获取失败：${result.error}"
                return@launch
            }
            allModels = result.models
            tvTip.text = if (allowMulti && onBatchAdd != null) {
                "共 ${allModels.size} 个模型 · 勾选后点「全部添加」批量扩充"
            } else {
                "共 ${allModels.size} 个模型 · 点整行切换，点右侧「复制」复制"
            }
            adapter.clear()
            adapter.addAll(allModels)
        }

        dialog.show()
    }

    /** 当前生效的密钥（Kotlin 侧） */
    fun getCurrentKey(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, "") ?: ""
    }

    /**
     * 把指定内容设为当前生效密钥（写 miyue.txt + 偏好）。
     * 供外部改完某份密钥的内容后同步——例如从正在使用的那份里删掉一个 API。
     */
    fun applyKey(context: Context, content: String) {
        writeTxtFile(KEY_FILE, content)
        saveKeyToPrefs(context, content)
    }

    private fun saveKeyToPrefs(context: Context, key: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(PREF_KEY, key).apply()
    }

    // ==================== 密钥列表读写 ====================

    fun getKeyMap(): JSONObject {
        val content = readTxtFile(KEY_LIST_FILE)
        return try {
            if (content.isNotEmpty()) JSONObject(content) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun saveKeyMap(keyMap: JSONObject) {
        // 只保存到 keys.json，保持旧格式 {name:key, ...} 不变（不写入额外字段）
        writeTxtFile(KEY_LIST_FILE, keyMap.toString())
    }

    fun getKeyNames(keyMap: JSONObject): MutableList<String> {
        val names = mutableListOf<String>()
        val keysIterator = keyMap.keys()
        while (keysIterator.hasNext()) {
            names.add(keysIterator.next())
        }
        return names
    }

    private fun saveKeyToJson(name: String, key: String) {
        val keyMap = getKeyMap()
        putKeyToFront(keyMap, name, key)
        saveKeyMap(keyMap)
        // 同时更新 miyue.txt（保存纯密钥内容）
        writeTxtFile(KEY_FILE, key)
    }

    /**
     * 将指定密钥移到列表最前面（顶部），保持旧格式 {name:key, ...} 不变。
     * 通过重建 keyMap 的插入顺序实现置顶：先放入 name，再按原有顺序放入其余密钥。
     */
    private fun putKeyToFront(keyMap: JSONObject, name: String, value: String) {
        val orderedNames = keyMap.keys().asSequence()
            .filter { it != name }
            .toMutableList()
        val valueMap = mutableMapOf<String, String>()
        orderedNames.forEach { n -> valueMap[n] = keyMap.optString(n, "") }

        keyMap.remove(name)
        orderedNames.forEach { keyMap.remove(it) }
        keyMap.put(name, value)
        orderedNames.forEach { n -> keyMap.put(n, valueMap[n] ?: "") }
    }

    // ==================== 查看 / 删除 / 修改 ====================

    private fun selectKeyToDelete(context: Context, onDone: (() -> Unit)? = null) {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        if (names.isEmpty()) {
            toast(context, "没有已保存的密钥")
            return
        }

        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_key_delete_multi)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val listView = dialog.findViewById<ListView>(R.id.lv_keys_multi)
        val btnConfirm = dialog.findViewById<Button>(R.id.btn_confirm_delete)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel_delete)

        val adapter = ArrayAdapter(
            context, android.R.layout.simple_list_item_checked, names
        )
        listView.adapter = adapter

        btnConfirm.setOnClickListener {
            val selectedIndices = mutableListOf<Int>()
            for (i in 0 until listView.count) {
                if (listView.isItemChecked(i)) {
                    selectedIndices.add(i)
                }
            }
            if (selectedIndices.isEmpty()) {
                toast(context, "请选择要删除的密钥")
                return@setOnClickListener
            }
            dialog.dismiss()
            // 倒序删除避免索引错乱
            selectedIndices.sortedDescending().forEach { index ->
                deleteKey(context, names[index])
            }
            toast(context, "已删除 ${selectedIndices.size} 个密钥")
            onDone?.invoke()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    fun deleteKey(context: Context, name: String, onDone: (() -> Unit)? = null) {
        AlertDialog.Builder(context)
            .setTitle("确认删除整份密钥")
            .setMessage("确定要删除密钥 '$name' 吗？\n里面的所有 API 都会一起删掉。")
            .setPositiveButton("删除") { _, _ ->
                val keyMap = getKeyMap()
                keyMap.remove(name)
                saveKeyMap(keyMap)
                // 注意：删除密钥不修改 miyue.txt，只修改 keys.json
                toast(context, "已删除密钥: $name")
                onDone?.invoke()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 修改某份密钥。
     *
     * @param onDone 改完的回调：调用方据此刷新列表。**不再关闭调用方的界面**——
     *   早先这里会让「恢复密钥」列表整个消失，改完还得重新点进来，很别扭。
     */
    fun modifyKey(context: Context, name: String, onDone: (() -> Unit)? = null) {
        val keyMap = getKeyMap()
        val currentKey = keyMap.optString(name, "")

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }

        val nameLabel = TextView(context).apply {
            text = "密钥名称"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val nameInput = TextInputEditText(context).apply {
            hint = "输入密钥名称"
            setText(name)
            setPadding(0, 0, 0, 20)
        }

        val keyLabel = TextView(context).apply {
            text = "密钥内容"
            textSize = 14f
            setPadding(0, 0, 0, 10)
        }
        val keyInput = TextInputEditText(context).apply {
            hint = "输入密钥内容"
            setText(currentKey)
        }

        container.addView(nameLabel)
        container.addView(nameInput)
        container.addView(keyLabel)
        container.addView(keyInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle("修改密钥")
            .setView(container)
            .setNegativeButton("取消", null)
            .create()

        // 三个按钮：删除整份 | 取消 | 保存并使用
        // 「删除」放最左（NEUTRAL），离常用的「保存」最远，减少误触
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "删除密钥") { _, _ ->
            dialog.dismiss()
            deleteKey(context, name) { onDone?.invoke() }
        }
        // 保存并使用：更新 keys.json 并设为当前生效密钥（占原「保存」的位置，最右边）
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存并使用") { _, _ ->
            applyModifyKey(context, name, nameInput, keyInput, true)
            onDone?.invoke()
        }
        dialog.show()
        // 删除标红，跟「取消 / 保存」区分开
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            ?.setTextColor(Color.parseColor("#F44336"))
    }

    /**
     * 执行密钥修改。
     * @param use true 时除写入 keys.json 外，还将该密钥设为当前生效（写入 miyue.txt 与偏好）
     */
    private fun applyModifyKey(
        context: Context,
        oldName: String,
        nameInput: TextInputEditText,
        keyInput: TextInputEditText,
        use: Boolean
    ) {
        val newName = nameInput.text?.toString()?.trim() ?: ""
        val newKey = keyInput.text?.toString()?.trim() ?: ""
        if (newName.isNotEmpty() && newKey.isNotEmpty()) {
            val keyMap = getKeyMap()
            if (oldName != newName) {
                keyMap.remove(oldName)
            }
            // 修改后将该密钥置顶到恢复列表最上方
            putKeyToFront(keyMap, newName, newKey)
            saveKeyMap(keyMap)
            if (use) {
                writeTxtFile(KEY_FILE, newKey)
                saveKeyToPrefs(context, newKey)
                toast(context, "已修改并启用密钥: $newName")
            } else {
                toast(context, "已修改密钥: $newName")
            }
        } else {
            toast(context, "名称和密钥内容都不能为空")
        }
    }

    // ==================== 文件操作 ====================

    private fun readTxtFile(fileName: String): String {
        return try {
            val file = File(EngineDataDir.baseDir(), fileName)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun writeTxtFile(fileName: String, content: String) {
        try {
            val file = File(EngineDataDir.baseDir(), fileName)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

/**
 * 恢复密钥界面的折叠适配器。
 *
 * 组 = 一个已保存的密钥，子 = 这份密钥里解析出来的每个 API。
 * 除了展示，还负责三种粒度的连通性测试：单个 API、整份密钥、全部密钥。
 */
/**
 * 恢复密钥界面的折叠适配器。
 *
 * 组 = 一份已保存的密钥，子 = 这份密钥里解析出来的每个 API。
 * 除了展示，还负责三种粒度的测试（单个 / 整份 / 全部）以及状态圆点的维护。
 */
/** 拖拽排序时随 DragEvent 传递的状态；c 随实时重排更新，committed 保证松手只同步一次 */
private class DragChildPos(val g: Int, var c: Int, var committed: Boolean = false)

/** 不透明、原样的拖拽影子：把行的快照画出来，跟手移动时和 ItemTouchHelper 观感一致 */
private class OpaqueDragShadow(view: View) : View.DragShadowBuilder(view) {
    private val bitmap: Bitmap
    private val size: Point

    init {
        // 在构造时就把行快照下来（此时还是可见的原样），避免稍后被设为空白占位后截到空图
        val w = view.width.coerceAtLeast(1)
        val h = view.height.coerceAtLeast(1)
        size = Point(w, h)
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(size.x, size.y)
        outShadowTouchPoint.set(size.x / 2, size.y / 2)
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.drawBitmap(bitmap, 0f, 0f, null)
    }
}

private class KeyExpandableAdapter(
    private val context: Context,
    /** 可变：改了密钥名字或删掉一份之后要原地更新这个列表 */
    private val names: MutableList<String>,
    private var keyMap: JSONObject,
    /** 打开界面时当前生效的密钥内容，用于标「使用中」与删除后同步；可用 [updateCurrentKey] 更新 */
    private var currentKey: String,
    private val onRestore: (String) -> Unit,
    /** 展开/折叠某个组，由外部驱动 ExpandableListView */
    private val onToggle: (groupPosition: Int) -> Unit,
    /** 顶部「正在使用」分区专用：隐藏「使用」按钮（这份本来就在用） */
    private val isCurrentSection: Boolean = false,
    /** 数据被改动（换模型/删 API/改名/删除）后回调，调用方用于同步另一个分区 */
    private val onDataChanged: (() -> Unit)? = null,
) : BaseExpandableListAdapter() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 每份密钥只解析一次 */
    private val entriesCache = HashMap<String, List<ApiEntry>>()
    /** 测试结果，key = "密钥名#下标" */
    private val results = HashMap<String, ApiTestResult>()
    private val testing = HashSet<String>()
    /** 当前左划打开的子行，保证同时只有一行露出删除 */
    private var openSwipe: SwipeRevealLayout? = null
    /** 当前左划打开的组行，保证同时只有一个组露出删除整份 */
    private var openGroupSwipe: SwipeRevealLayout? = null
    /** 正在拖动的子项位置：该行在列表里渲染成空白占位（跟手的那份由拖拽影子显示） */
    var dragPos: DragChildPos? = null

    private val dotOk = Color.parseColor("#4CAF50")
    private val dotFail = Color.parseColor("#F44336")
    private val dotBusy = Color.parseColor("#FF9800")
    private val dotNone = Color.parseColor("#9E9E9E")

    private fun entriesOf(name: String): List<ApiEntry> =
        entriesCache.getOrPut(name) { MiyueKeyTester.parse(keyMap.optString(name, "")) }

    private fun rk(name: String, index: Int) = "$name#$index"

    private fun isCurrent(name: String): Boolean =
        currentKey.isNotBlank() && keyMap.optString(name, "") == currentKey

    private fun paintDot(view: View, color: Int) {
        view.background?.mutate()?.setTint(color)
    }

    private fun copy(text: String, label: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
    }

    /** 该密钥下每个 API 的测试结果，还没测的位置是 null */
    private fun resultsOf(name: String): List<ApiTestResult?> =
        entriesOf(name).indices.map { results[rk(name, it)] }

    /** 组行圆点：全过=绿，全败=红，混合或测出过错=橙，完全没测=灰 */
    private fun groupDot(name: String): Int {
        val rs = resultsOf(name)
        if (rs.isEmpty()) return dotNone
        if (rs.all { it == null }) return dotNone
        if (rs.any { it == null }) {
            return if (rs.any { it != null && !it.ok }) dotBusy else dotNone
        }
        val ok = rs.count { it!!.ok }
        return when (ok) {
            rs.size -> dotOk
            0 -> dotFail
            else -> dotBusy
        }
    }

    override fun getGroupCount(): Int = names.size

    override fun getChildrenCount(groupPosition: Int): Int =
        entriesOf(names[groupPosition]).size

    override fun getGroup(groupPosition: Int): Any = names[groupPosition]

    override fun getChild(groupPosition: Int, childPosition: Int): Any =
        entriesOf(names[groupPosition])[childPosition]

    override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

    override fun getChildId(g: Int, c: Int): Long = g * 1000L + c

    override fun hasStableIds(): Boolean = false

    override fun isChildSelectable(g: Int, c: Int): Boolean = true

    override fun getGroupView(
        groupPosition: Int,
        isExpanded: Boolean,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.dialog_key_group, parent, false)
        val name = names[groupPosition]
        val list = entriesOf(name)

        // 左划露出「删除」：删掉整份密钥（走带确认的 deleteKey）
        val swipe = view as SwipeRevealLayout
        // 顶部「正在使用」区保留淡蓝卡片底色，底部用中性色；content 必须不透明才能盖住删除区
        view.findViewById<View>(R.id.group_swipe_content).setBackgroundColor(
            ContextCompat.getColor(
                context,
                if (isCurrentSection) R.color.key_current_bg else R.color.background
            )
        )
        view.findViewById<Button>(R.id.btn_group_delete).setOnClickListener {
            swipe.setOpen(false)
            MiyueKeyManager.deleteKey(context, name) { refresh() }
        }
        swipe.onOpened = { openGroupSwipe?.let { if (it !== swipe) it.setOpen(false) }; openGroupSwipe = swipe }

        view.findViewById<TextView>(R.id.tv_group_arrow).text = if (isExpanded) "▾" else "▸"
        view.findViewById<TextView>(R.id.tv_group_name).text = name
        val count = view.findViewById<TextView>(R.id.tv_group_count)
        count.text = when {
            list.isEmpty() -> "无 API"
            isCurrent(name) -> "使用中 · ${list.size} 个 API"
            else -> "${list.size} 个 API"
        }
        paintDot(view.findViewById(R.id.view_group_dot), groupDot(name))

        // 箭头与密钥名自己接管点击：item 里有 Button 时整行点击常常失效，
        // 只依赖 setOnGroupClickListener 不够稳，这里再挂一层
        val toggle: (View) -> Unit = {
            if (getChildrenCount(groupPosition) == 0) {
                Toast.makeText(context, "这份密钥里没解析出 API", Toast.LENGTH_SHORT).show()
            } else {
                onToggle(groupPosition)
            }
        }
        view.findViewById<View>(R.id.tv_group_arrow).setOnClickListener(toggle)
        view.findViewById<View>(R.id.tv_group_name).setOnClickListener(toggle)
        view.findViewById<View>(R.id.view_group_dot).setOnClickListener(toggle)

        view.findViewById<Button>(R.id.btn_group_restore).apply {
            // 顶部「正在使用」分区：这份本来就在用，不再提供「使用」按钮
            if (isCurrentSection) {
                visibility = View.GONE
            } else {
                setOnClickListener { onRestore(name) }
            }
        }
        view.findViewById<Button>(R.id.btn_group_test).setOnClickListener {
            testGroup(groupPosition)
        }
        view.findViewById<ImageButton>(R.id.btn_group_edit).setOnClickListener {
            // 编辑走的是另一个弹窗，改完只刷新本列表，不关闭「恢复密钥」窗口
            MiyueKeyManager.modifyKey(context, name) { refresh() }
        }
        return view
    }

    override fun getChildView(
        groupPosition: Int,
        childPosition: Int,
        isLastChild: Boolean,
        convertView: View?,
        parent: ViewGroup,
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.dialog_key_child, parent, false)
        val name = names[groupPosition]
        val entry = entriesOf(name)[childPosition]

        // 拖拽排序：本行作为落点，把来源（同组）的子项移到自己的位置
        val here = DragChildPos(groupPosition, childPosition)
        view.setTag(R.id.tv_child_index, here)
        view.setOnDragListener { v, event ->
            val from = event.localState as? DragChildPos
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    // 手指划到哪一行，就把被拖条目实时移到那，其余行当场让位；
                    // 被拖条目所在行渲染为空白占位（见下方 visibility），跟手的由拖拽影子显示
                    if (from != null && from.g == groupPosition && from.c != childPosition) {
                        if (moveApi(groupPosition, from.c, childPosition, notifyOthers = false)) {
                            from.c = childPosition
                            // dragPos 与 from 同一对象，c 更新后再刷一次让空白占位跟到新位置
                            notifyDataSetChanged()
                        }
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    // 位置已在 LOCATION 阶段实时落位，这里收尾：清缓存+同步另一分区
                    if (from != null && from.g == groupPosition && !from.committed) {
                        from.committed = true
                        finishDrag(name)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> true
                DragEvent.ACTION_DRAG_ENDED -> {
                    // 拖出列表外松手也要收尾（DROP 不一定触发）
                    if (from != null && !from.committed) {
                        from.committed = true
                        finishDrag(names[from.g])
                    }
                    true
                }
                else -> true
            }
        }

        // 被拖动的这一行渲染成空白占位；其余行保持原样。convertView 会复用，必须每次都重置
        val isDraggingRow = dragPos?.let { it.g == groupPosition && it.c == childPosition } == true
        view.findViewById<View>(R.id.swipe_content).visibility =
            if (isDraggingRow) View.INVISIBLE else View.VISIBLE

        // 左划露出的「删除」按钮：删掉这一条 API
        val swipe = view as SwipeRevealLayout
        view.findViewById<Button>(R.id.btn_child_delete).setOnClickListener {
            swipe.setOpen(false)
            confirmRemove(name, entry)
        }
        // 同时只允许一行处于左划打开状态
        swipe.onOpened = { openSwipe?.let { if (it !== swipe) it.setOpen(false) }; openSwipe = swipe }
        view.findViewById<TextView>(R.id.tv_child_index).text = "${childPosition + 1}."
        // 主行只显示模型名；来源场景与域名用小字补充，
        // 否则同名模型挂在两个接口上时根本分不清是哪一条
        view.findViewById<TextView>(R.id.tv_child_model).text = entry.model
        view.findViewById<TextView>(R.id.tv_child_host).text =
            "${entry.scene} · ${entry.host()} · ${entry.keyMask()}"

        val key = rk(name, childPosition)
        val r = results[key]
        val dot = view.findViewById<View>(R.id.view_child_dot)
        val status = view.findViewById<TextView>(R.id.tv_child_status)
        when {
            testing.contains(key) -> {
                paintDot(dot, dotBusy)
                status.text = "测试中…"
            }
            r != null -> {
                paintDot(dot, if (r.ok) dotOk else dotFail)
                status.text = if (r.ok) {
                    "✅ ${r.costMs}ms"
                } else {
                    // 错误可能很长（如 HTTP 400 的完整响应体），截断显示，
                    // 避免把右侧「测试 / 模型」按钮挤出去；完整信息点子行详情可见
                    val msg = r.message
                    "❌ ${msg.take(60)}${if (msg.length > 60) "…" else ""}"
                }
            }
            else -> {
                paintDot(dot, dotNone)
                status.text = ""
            }
        }

        view.findViewById<Button>(R.id.btn_child_test).setOnClickListener {
            testOne(groupPosition, childPosition)
        }
        view.findViewById<Button>(R.id.btn_child_models).setOnClickListener {
            // 列出该接口的全部模型：未勾选时点行=单选替换；勾选后「全部添加」=扩充多个模型
            MiyueKeyManager.showModelsDialog(
                context,
                entry,
                onPick = { picked -> applyModel(name, entry, picked) },
                allowMulti = true,
                onBatchAdd = { models -> applyModels(name, entry, models) },
            )
        }
        return view
    }

    /** 点子行：这个 API 的详情，下方四个按钮分别复制 接口 / 模型 / 密钥 / 全部 */
    fun showDetail(g: Int, c: Int) {
        val name = names[g]
        val entry = entriesOf(name).getOrNull(c) ?: return
        val key = rk(name, c)
        val r = results[key]
        val stateText = when {
            testing.contains(key) -> "测试中…"
            r == null -> "未测试"
            r.ok -> "可用（${r.costMs}ms）"
            else -> "不可用：${r.message.take(80)}${if (r.message.length > 80) "…" else ""}"
        }
        val info = "状态：$stateText\n" +
            "场景：${entry.scene}\n" +
            "地址：${entry.endpoint}\n" +
            "密钥：${entry.keyMask()}"

        val dialog = AlertDialog.Builder(context).create()
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_key_child_detail, null)
        view.findViewById<TextView>(R.id.tv_detail_info).text = info
        view.findViewById<Button>(R.id.btn_detail_endpoint).setOnClickListener {
            copy(entry.endpoint, "接口")
        }
        view.findViewById<Button>(R.id.btn_detail_model).setOnClickListener {
            copy(entry.model, "模型")
        }
        view.findViewById<Button>(R.id.btn_detail_key).setOnClickListener {
            copy(entry.key, "密钥")
        }
        view.findViewById<Button>(R.id.btn_detail_all).setOnClickListener {
            // 全部：接口地址@@模型名字@@密钥，单行紧凑格式
            copy("${entry.endpoint}@@${entry.model}@@${entry.key}", "全部")
        }
        dialog.setTitle(entry.model)
        dialog.setView(view)
        dialog.setCancelable(true)
        dialog.show()
    }

    /** 长按子项时用：直接弹删除确认 */
    fun confirmRemoveAt(g: Int, c: Int) {
        val name = names[g]
        val entry = entriesOf(name).getOrNull(c) ?: return
        confirmRemove(name, entry)
    }

    /**
     * 外部改完数据后原地刷新：重新取密钥名（改名/删除都会变），
     * 清掉解析缓存与旧的测试结果。
     */
    /**
     * 外部改完数据后原地刷新：重新从文件加载 keyMap（删除/改名走的都是
     * [MiyueKeyManager.deleteKey] 这类会重新读文件再写入的实现，适配器手里的
     * 旧引用不会自动同步），再取密钥名、清掉解析缓存与旧的测试结果。
     */
    fun refresh() {
        keyMap = MiyueKeyManager.getKeyMap()
        val fresh = MiyueKeyManager.getKeyNames(keyMap)
        names.clear()
        names.addAll(fresh)
        entriesCache.clear()
        results.clear()
        testing.clear()
        notifyDataSetChanged()
        onDataChanged?.invoke()
    }

    /** 当前生效密钥变了（列表里点了「使用」），组行的「使用中」标记跟着变 */
    fun updateCurrentKey(content: String) {
        if (currentKey == content) return
        currentKey = content
        notifyDataSetChanged()
    }

    /** 从这份密钥里剔除一个 API（只影响它所在的那一小段） */
    private fun confirmRemove(name: String, entry: ApiEntry) {
        val wasCurrent = isCurrent(name)
        AlertDialog.Builder(context)
            .setTitle("删除这个 API？")
            .setMessage(
                "将从「$name」中移除：\n${entry.model}（${entry.host()}）" +
                    "\n\n只剔除这一个，这份密钥里的其它 API 不受影响。"
            )
            .setPositiveButton("删除") { _, _ ->
                val old = keyMap.optString(name, "")
                val now = MiyueKeyTester.removeApi(old, entry)
                if (now == old) {
                    Toast.makeText(context, "没能定位到这个 API，未做修改", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }
                keyMap.put(name, now)
                MiyueKeyManager.saveKeyMap(keyMap)
                // 删的正是当前在用的那份：同步刷新 miyue.txt 与偏好
                if (wasCurrent) MiyueKeyManager.applyKey(context, now)
                entriesCache.remove(name)
                results.keys.removeAll(results.keys.filter { it.startsWith("$name#") }.toSet())
                notifyDataSetChanged()
                onDataChanged?.invoke()
                Toast.makeText(context, "已删除：${entry.model}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把这条 API 的模型换成在模型列表里选中的那个 */
    private fun applyModel(name: String, entry: ApiEntry, newModel: String) {
        val wasCurrent = isCurrent(name)
        val old = keyMap.optString(name, "")
        val now = MiyueKeyTester.replaceModel(old, entry, newModel)
        if (now == old) {
            Toast.makeText(context, "没能定位到这个 API，未做修改", Toast.LENGTH_SHORT).show()
            return
        }
        keyMap.put(name, now)
        MiyueKeyManager.saveKeyMap(keyMap)
        // 改的正是当前在用的那份：同步刷新 miyue.txt 与偏好
        if (wasCurrent) MiyueKeyManager.applyKey(context, now)
        entriesCache.remove(name)
        // 模型变了，之前这条的测试结果不再代表现状
        results.keys.removeAll(results.keys.filter { it.startsWith("$name#") }.toSet())
        notifyDataSetChanged()
        onDataChanged?.invoke()
        Toast.makeText(context, "${entry.model} → $newModel（已复制）", Toast.LENGTH_SHORT).show()
    }

    /** 把这条 API 扩充为多个模型（保留原模型，追加勾选的），只有改的是当前密钥才同步生效 */
    private fun applyModels(name: String, entry: ApiEntry, newModels: List<String>) {
        val wasCurrent = isCurrent(name)
        val old = keyMap.optString(name, "")
        val now = MiyueKeyTester.addModels(old, entry, newModels)
        if (now == old) {
            Toast.makeText(context, "没有需要新增的模型（可能已存在）", Toast.LENGTH_SHORT).show()
            return
        }
        keyMap.put(name, now)
        MiyueKeyManager.saveKeyMap(keyMap)
        // 改的正是当前在用的那份：同步刷新 miyue.txt 与偏好；非当前那份只扩充、不改变使用状态
        if (wasCurrent) MiyueKeyManager.applyKey(context, now)
        entriesCache.remove(name)
        results.keys.removeAll(results.keys.filter { it.startsWith("$name#") }.toSet())
        notifyDataSetChanged()
        onDataChanged?.invoke()
        Toast.makeText(context, "已为 ${entry.model} 扩充 ${newModels.size} 个模型", Toast.LENGTH_SHORT)
            .show()
    }

    /**
     * 同一份密钥内把子项从 [from] 拖到 [to] 位置；跨姓名/别名分界即改变归属段。
     * 只有改的是当前正在使用的那份才 applyKey 同步生效，其余只改 keys.json。
     */
    fun moveApi(g: Int, from: Int, to: Int, notifyOthers: Boolean = true): Boolean {
        val name = names[g]
        val entries = entriesOf(name)
        if (from !in entries.indices || to !in entries.indices || from == to) return false
        val old = keyMap.optString(name, "")
        val now = MiyueKeyTester.moveEntry(old, from, to)
        if (now == old) return false
        val wasCurrent = isCurrent(name)
        keyMap.put(name, now)
        MiyueKeyManager.saveKeyMap(keyMap)
        // 拖动中（notifyOthers=false）只更新 keys.json 与界面，miyue.txt 的同步留到松手统一做：
        // 否则第一次移动后 keyMap 内容变了、isCurrent 会翻转，后续步骤就不再同步
        if (notifyOthers && wasCurrent) MiyueKeyManager.applyKey(context, now)
        entriesCache.remove(name)
        if (notifyOthers) {
            results.keys.removeAll(results.keys.filter { it.startsWith("$name#") }.toSet())
            onDataChanged?.invoke()
        }
        notifyDataSetChanged()
        return true
    }

    /** 拖拽结束收尾：当前密钥同步 miyue.txt/偏好，清测试缓存并同步另一分区 */
    private fun finishDrag(name: String) {
        // 松手：清掉空白占位标记，被拖条目在落位正常显示
        dragPos = null
        if (isCurrent(name)) {
            MiyueKeyManager.applyKey(context, keyMap.optString(name, ""))
        }
        entriesCache.remove(name)
        results.keys.removeAll(results.keys.filter { it.startsWith("$name#") }.toSet())
        notifyDataSetChanged()
        onDataChanged?.invoke()
    }

    /** 测某一个 API */
    private fun testOne(g: Int, c: Int) {
        val name = names[g]
        val entry = entriesOf(name).getOrNull(c) ?: return
        val key = rk(name, c)
        testing.add(key)
        notifyDataSetChanged()
        scope.launch {
            val r = MiyueKeyTester.testOne(entry)
            testing.remove(key)
            results[key] = r
            notifyDataSetChanged()
        }
    }

    /** 测某一份密钥里包含的全部 API */
    private fun testGroup(g: Int) {
        val name = names[g]
        val list = entriesOf(name)
        if (list.isEmpty()) {
            Toast.makeText(context, "这份密钥里没解析出 API", Toast.LENGTH_SHORT).show()
            return
        }
        list.indices.forEach { testing.add(rk(name, it)) }
        notifyDataSetChanged()
        scope.launch {
            val rs = MiyueKeyTester.testAll(list) { start, batch ->
                // 每批回来就点亮，不用等全部跑完
                batch.forEachIndexed { k, r ->
                    testing.remove(rk(name, start + k))
                    results[rk(name, start + k)] = r
                }
                notifyDataSetChanged()
            }
            Toast.makeText(
                context,
                "$name：可用 ${rs.count { it.ok }} / 共 ${rs.size}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 测所有密钥里的所有 API */
    fun testAll(
        onProgress: (done: Int, total: Int) -> Unit,
        onDone: (ok: Int, total: Int) -> Unit,
    ) {
        val targets = mutableListOf<Triple<String, Int, ApiEntry>>()
        names.forEach { name ->
            entriesOf(name).forEachIndexed { i, entry -> targets.add(Triple(name, i, entry)) }
        }
        if (targets.isEmpty()) {
            onDone(0, 0)
            return
        }
        targets.forEach { (name, i, _) -> testing.add(rk(name, i)) }
        notifyDataSetChanged()
        scope.launch {
            val rs = MiyueKeyTester.testAll(targets.map { it.third }) { start, batch ->
                batch.forEachIndexed { k, r ->
                    val (name, i, _) = targets[start + k]
                    testing.remove(rk(name, i))
                    results[rk(name, i)] = r
                }
                notifyDataSetChanged()
                onProgress(start + batch.size, targets.size)
            }
            onDone(rs.count { it.ok }, rs.size)
        }
    }

    fun cancel() {
        scope.cancel()
    }
}
