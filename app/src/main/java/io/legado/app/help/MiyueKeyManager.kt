package io.legado.app.help

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.preference.PreferenceManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import io.legado.app.R
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

    /** 恢复密钥：列出已保存密钥，点行即恢复；另提供修改 / 删除 / 查看当前密钥 */
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

        val listView = dialog.findViewById<ListView>(R.id.lv_keys)
        val btnDelete = dialog.findViewById<Button>(R.id.btn_delete_key)
        val btnShowCurrent = dialog.findViewById<Button>(R.id.btn_show_current)
        val btnCancel = dialog.findViewById<Button>(R.id.btn_cancel)

        val adapter = object : ArrayAdapter<String>(
            context,
            R.layout.dialog_key_item,
            R.id.tv_key_name,
            names
        ) {
            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup
            ): View {
                val view = convertView
                    ?: LayoutInflater.from(context).inflate(R.layout.dialog_key_item, parent, false)
                val name = names[position]
                val tv = view.findViewById<TextView>(R.id.tv_key_name)
                tv.text = name
                tv.setTextColor(ContextCompat.getColor(context, R.color.primaryText))

                // 点击整行（除“修改”按钮）即恢复该密钥到 miyue.txt 并关闭弹窗
                view.setOnClickListener {
                    val key = keyMap.optString(name, "")
                    writeTxtFile(KEY_FILE, key)
                    saveKeyToPrefs(context, key)
                    toast(context, "已恢复密钥: $name")
                    dialog.dismiss()
                }

                view.findViewById<ImageButton>(R.id.btn_edit_key).setOnClickListener {
                    dialog.dismiss()
                    modifyKey(context, name)
                }
                return view
            }
        }
        listView.adapter = adapter

        btnDelete.setOnClickListener {
            dialog.dismiss()
            selectKeyToDelete(context)
        }
        btnShowCurrent.setOnClickListener {
            dialog.dismiss()
            showCurrentKeyDialog(context)
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /** 当前生效的密钥（Kotlin 侧） */
    fun getCurrentKey(context: Context): String {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_KEY, "") ?: ""
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

    private fun selectKeyToDelete(context: Context) {
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
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun deleteKey(context: Context, name: String) {
        AlertDialog.Builder(context)
            .setTitle("确认删除")
            .setMessage("确定要删除密钥 '$name' 吗？")
            .setPositiveButton("删除") { _, _ ->
                val keyMap = getKeyMap()
                keyMap.remove(name)
                saveKeyMap(keyMap)
                // 注意：删除密钥不修改 miyue.txt，只修改 keys.json
                toast(context, "已删除密钥: $name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun modifyKey(context: Context, name: String) {
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

        // 保存并使用：更新 keys.json 并设为当前生效密钥（占原「保存」的位置，最右边）
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "保存并使用") { _, _ ->
            applyModifyKey(context, name, nameInput, keyInput, true)
        }
        dialog.show()
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
