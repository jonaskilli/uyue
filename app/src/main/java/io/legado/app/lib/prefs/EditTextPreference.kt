package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.TextView
import androidx.preference.EditTextPreference.OnBindEditTextListener
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.applyTint

class EditTextPreference(context: Context, attrs: AttributeSet) :
    androidx.preference.EditTextPreference(context, attrs) {

    companion object {
        private const val TAG = "EditTextPreference"
    }

    private var mOnBindEditTextListener: OnBindEditTextListener? = null
    private val onBindEditTextListener = OnBindEditTextListener { editText ->
        editText.applyTint(context.accentColor)
        mOnBindEditTextListener?.onBindEditText(editText)
    }

    init {
        // isPersistent = true
        layoutResource = R.layout.view_preference
        super.setOnBindEditTextListener(onBindEditTextListener)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        Preference.bindView<TextView>(context, holder, icon, title, summary, null, null)
        super.onBindViewHolder(holder)
    }

    override fun setOnBindEditTextListener(onBindEditTextListener: OnBindEditTextListener?) {
        mOnBindEditTextListener = onBindEditTextListener
    }

    /**
     * 初始值读取——**类型安全**版本。
     *
     * 父类实现为：
     * `setText(getPersistedString((String) defaultValue))`
     * 内部直接调用 `SharedPreferences.getString(key, def)`。
     * 一旦该 key 在 SP 中实际存储的是 Float / Int / Long 等其它类型，
     * Android 的 SharedPreferencesImpl.getString 会直接抛
     * `ClassCastException: java.lang.Float cannot be cast to java.lang.String`。
     *
     * 这类脏数据来自外部：恢复其它 fork / 第三方工具（钛备份、换机助手等）产生的备份时，
     * 备份中的数值会以 Float 形式写入（见 Restore 对 Float 分支的处理）。
     * 本仓库自身的偏好写入始终用字符串，不会内部产生该问题。
     *
     * 由于崩溃发生在 Fragment 创建偏好树期间，界面根本打不开，
     * 用户无法自行改回，属于「一次污染、永久崩溃」。
     *
     * 这里绕过 getPersistedString，改用 all[key] 判类型后取值，
     * 对 Float / Int / Long / Boolean 等一律安全转换为字符串。
     *
     * 注意：签名必须与父类 `protected void onSetInitialValue(Object)` 完全一致，
     * 否则 Kotlin 只会生成一个重载新方法，父类仍调用原实现，修复会静默失效。
     */
    override fun onSetInitialValue(defaultValue: Any?) {
        // 若设置了自定义 PreferenceDataStore（本项目当前未使用），
        // 值并不在 SharedPreferences 中，交由父类按其自身方式读取，
        // 避免此处误判为「未持久化」而退回默认值。
        if (preferenceDataStore != null) {
            super.onSetInitialValue(defaultValue)
            return
        }
        setText(readPersistedValueSafely(defaultValue as? String))
    }

    /**
     * 所有持久化读取的**必经点**（父类 onSetInitialValue、对话框恢复等内部路径最终都会调到这里）。
     *
     * 父类实现直接 `SharedPreferences.getString(key, def)`，一旦该 key 在 SP 中实际存储的是
     * Float / Int / Long 等其它类型（典型来源：恢复第三方工具产生的备份、或旧版本曾以
     * 数值类型写入），Android 的 SharedPreferencesImpl.getString 会抛
     * `ClassCastException: java.lang.Float cannot be cast to java.lang.String`。
     * 该异常发生在 Fragment 构建偏好树期间，设置界面完全打不开，属于「一次污染、永久崩溃」。
     *
     * 这里重写为类型安全读取：非 String 一律安全转换为字符串，并在
     * [onSetInitialValue] 的重写之外再兜一层，确保任何内部调用路径都不会绕过。
     */
    override fun getPersistedString(defaultReturnValue: String?): String? {
        // 使用自定义 PreferenceDataStore（本项目未使用）或不持久化时，保持父类语义
        if (preferenceDataStore != null || !shouldPersist()) {
            return super.getPersistedString(defaultReturnValue)
        }
        return readPersistedValueSafely(defaultReturnValue)
    }

    /**
     * 安全读取已持久化的值。
     *
     * @param def 默认值，当未持久化或读取失败时使用
     */
    private fun readPersistedValueSafely(def: String?): String? {
        // 未开启持久化：父类同样直接返回默认值
        if (!shouldPersist()) return def
        return try {
            val stored = preferenceManager?.sharedPreferences?.all?.get(key)
            when (stored) {
                null -> def
                is String -> stored
                else -> stored.toString()
            }
        } catch (e: Throwable) {
            // 兜底：这里若抛异常会导致 Fragment 构建偏好树时崩溃，
            // 设置界面将完全无法打开，因此任何异常都只记日志并回退默认值。
            Log.e(TAG, "读取偏好初始值失败，使用默认值。key=$key", e)
            def
        }
    }

}
