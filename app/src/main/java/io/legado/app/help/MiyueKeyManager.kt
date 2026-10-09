package io.legado.app.help

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.preference.PreferenceManager
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.BaseExpandableListAdapter
import android.widget.Button
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

    /** 测试 / 取模型都是网络操作，统一挂在这个 scope 上，界面关闭时取消 */
    private val keyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ==================== 对外入口 ====================

    /** 保存密钥：两步弹窗（先输密钥内容，再输密钥名称） */
    fun saveKey(context: Context) {
        val keyInput = TextInputEditText(context).apply {
            hint = "输入密钥内容"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(context)
            .setTitle("保存密钥 - 第1步")
            .setView(keyInput)
            .setPositiveButton("下一步") { _, _ ->
                val keyContent = keyInput.text?.toString()?.trim() ?: ""
                if (keyContent.isNotEmpty()) {
                    val nameInput = TextInputEditText(context).apply {
                        hint = "输入密钥名称"
                        setPadding(50, 30, 50, 30)
                    }

                    AlertDialog.Builder(context)
                        .setTitle("保存密钥 - 第2步")
                        .setView(nameInput)
                        .setPositiveButton("保存") { _, _ ->
                            val name = nameInput.text?.toString()?.trim() ?: ""
                            if (name.isNotEmpty()) {
                                saveKeyToJson(name, keyContent)
                                toast(context, "密钥已保存: $name")
                            } else {
                                toast(context, "名称不能为空")
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                } else {
                    toast(context, "密钥内容不能为空")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 恢复密钥：可折叠列表。
     *
     * 组 = 一个已保存的密钥（整行点击展开/折叠，右侧可 恢复 / 测试整份 / 修改）；
     * 子 = 该密钥里解析出来的每一个 API（只显示模型名，右侧可单独 测试 / 取该接口的全部模型）。
     * 底部「测试全部」一次性把所有密钥里的所有 API 都测一遍。
     */
    fun restoreKey(context: Context) {
        val keyMap = getKeyMap()
        val names = getKeyNames(keyMap)

        if (names.isEmpty()) {
            toast(context, "没有已保存的密钥")
            return
        }

        val dialog = Dialog(context)
        dialog.setContentView(R.layout.dialog_key_list)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val listView = dialog.findViewById<ExpandableListView>(R.id.lv_keys)
        val btnTestAll = dialog.findViewById<Button>(R.id.btn_test_all)
        val btnDelete = dialog.findViewById<Button>(R.id.btn_delete_key)
        val btnShowCurrent = dialog.findViewById<Button>(R.id.btn_show_current)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        val tvHint = dialog.findViewById<TextView>(R.id.tv_key_hint)
        val adapter = KeyExpandableAdapter(
            context = context,
            names = names,
            keyMap = keyMap,
            // 当前生效的是哪一份：组行标「使用中」，删除 API 时才知道要不要同步刷新
            currentKey = readTxtFile(KEY_FILE),
            onRestore = { name ->
                val key = keyMap.optString(name, "")
                writeTxtFile(KEY_FILE, key)
                saveKeyToPrefs(context, key)
                toast(context, "已恢复密钥: $name")
                dialog.dismiss()
            },
            onToggle = { gp ->
                if (listView.isGroupExpanded(gp)) {
                    listView.collapseGroup(gp)
                } else {
                    listView.expandGroup(gp)
                }
            },
        )
        // 分隔线必须显式给成 Drawable：
        // ExpandableListView 画子项分隔线用的是 mChildDivider，而它默认不跟随 divider 属性，
        // 部分 ROM 又不把 @color/divider 解析成 Drawable —— 两者任一为 null，
        // 一展开就在 drawDivider() 里 NPE（vivo/Android 15 实测崩溃）。
        val dividerDrawable = ColorDrawable(ContextCompat.getColor(context, R.color.divider))
        listView.divider = dividerDrawable
        listView.dividerHeight = 1
        listView.setChildDivider(dividerDrawable)

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
        // 长按子项 = 删掉这一个 API。整份（组）的删除走组行「修改」里的删除按钮，
        // 那里删完会 refresh() 刷新整个列表。
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val packed = listView.getExpandableListPosition(position)
            if (ExpandableListView.getPackedPositionType(packed)
                == ExpandableListView.PACKED_POSITION_TYPE_CHILD
            ) {
                adapter.confirmRemoveAt(
                    ExpandableListView.getPackedPositionGroup(packed),
                    ExpandableListView.getPackedPositionChild(packed)
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
        // 下面两个都只是「在上面叠一层」，不再先关掉密钥列表——
        // 关掉的话做完一步就得重新点「恢复密钥」进来，来回很烦
        btnDelete.setOnClickListener {
            selectKeyToDelete(context) { adapter.refresh() }
        }
        btnShowCurrent.setOnClickListener {
            showCurrentKeyDialog(context)
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener { adapter.cancel() }

        dialog.show()
    }

    /**
     * 某个接口上的可用模型：带搜索框（模型多的时候靠它筛）。
     *
     * 点条目 = 把这份密钥里该 API 的模型直接换成它，同时复制一份到剪贴板；
     * [onPick] 传 null 时只复制、不改动密钥。
     */
    fun showModelsDialog(
        context: Context,
        entry: ApiEntry,
        onPick: ((model: String) -> Unit)? = null,
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

        tvTip.text = "加载中…（${entry.host()}）"

        var allModels: List<String> = emptyList()
        val adapter = object : ArrayAdapter<String>(
            context, R.layout.dialog_models_item, R.id.tv_model_name, mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(R.layout.dialog_models_item, parent, false)
                val model = getItem(position) ?: ""
                view.findViewById<TextView>(R.id.tv_model_name).text = model
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
        // 点整行 = 切换为该模型。复制不再顺带发生，交给行内的「复制」按钮
        listView.setOnItemClickListener { _, _, position, _ ->
            val model = adapter.getItem(position) ?: return@setOnItemClickListener
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

        keyScope.launch {
            val result = MiyueKeyTester.fetchModels(entry)
            if (result.error != null) {
                tvTip.text = "获取失败：${result.error}"
                return@launch
            }
            allModels = result.models
            tvTip.text = "共 ${allModels.size} 个模型 · 点整行切换，点右侧「复制」复制"
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

    private fun showCurrentKeyDialog(context: Context) {
        val miyueContent = readTxtFile(KEY_FILE)
        if (miyueContent.isEmpty()) {
            toast(context, "当前密钥为空")
            return
        }

        AlertDialog.Builder(context)
            .setTitle("当前密钥内容")
            .setMessage(miyueContent)
            .setPositiveButton("复制") { _, _ ->
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("密钥", miyueContent))
                toast(context, "已复制到剪贴板")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

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
private class KeyExpandableAdapter(
    private val context: Context,
    /** 可变：改了密钥名字或删掉一份之后要原地更新这个列表 */
    private val names: MutableList<String>,
    private var keyMap: JSONObject,
    /** 打开界面时当前生效的密钥内容，用于标「使用中」与删除后同步 */
    private val currentKey: String,
    private val onRestore: (String) -> Unit,
    /** 展开/折叠某个组，由外部驱动 ExpandableListView */
    private val onToggle: (groupPosition: Int) -> Unit,
) : BaseExpandableListAdapter() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 每份密钥只解析一次 */
    private val entriesCache = HashMap<String, List<ApiEntry>>()
    /** 测试结果，key = "密钥名#下标" */
    private val results = HashMap<String, ApiTestResult>()
    private val testing = HashSet<String>()

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

        view.findViewById<Button>(R.id.btn_group_restore).setOnClickListener { onRestore(name) }
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
            // 列出该接口的全部模型，点哪个就把这条 API 的模型换成哪个
            MiyueKeyManager.showModelsDialog(context, entry) { picked ->
                applyModel(name, entry, picked)
            }
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
        Toast.makeText(context, "${entry.model} → $newModel（已复制）", Toast.LENGTH_SHORT).show()
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
