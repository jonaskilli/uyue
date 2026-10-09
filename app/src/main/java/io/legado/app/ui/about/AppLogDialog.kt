package io.legado.app.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.annotation.StringRes
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.databinding.DialogAppLogBinding
import io.legado.app.databinding.ItemAppLogBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import splitties.views.onClick
import java.util.Date

/**
 * 应用日志。
 *
 * 日志只存在内存里（[AppLog]），界面打开时装载一次，之后新产生的日志不会自动出现，
 * 所以用工具栏的刷新按钮重新装载。
 *
 * 筛选条 = 类别下拉 + 关键字输入。日志本身没有分类字段，类别筛选是对消息文本做
 * 不区分大小写的包含匹配（「异常」除外，它按是否带了 Throwable 精确判断）。
 */
class AppLogDialog : BaseDialogFragment(R.layout.dialog_app_log),
    Toolbar.OnMenuItemClickListener {

    private val binding by viewBinding(DialogAppLogBinding::bind)
    private val adapter by lazy {
        LogAdapter(requireContext())
    }

    /** 类别筛选项。keys 为空表示不过滤文本；errorOnly 只看带异常的日志 */
    private data class LogFilter(
        @StringRes val title: Int,
        val keys: List<String> = emptyList(),
        val errorOnly: Boolean = false
    )

    private val filters = listOf(
        LogFilter(R.string.log_filter_all),
        LogFilter(R.string.log_filter_error, errorOnly = true),
        LogFilter(R.string.log_filter_sound_effect, listOf("音效")),
        LogFilter(R.string.log_filter_bgm, listOf("bgm", "背景音乐")),
        LogFilter(R.string.log_filter_ai, listOf("ai")),
        LogFilter(R.string.log_filter_ai_image, listOf("生图")),
        LogFilter(R.string.log_filter_tts, listOf("tts")),
        LogFilter(
            R.string.log_filter_network,
            listOf("http", "网络", "请求", "超时", "下载", "上传")
        ),
        LogFilter(R.string.log_filter_book_source, listOf("书源"))
    )

    /**
     * 上次选择的筛选条件。
     *
     * **只存在内存里**：重新打开日志界面时沿用上次的类别与关键字，
     * 重启 App 后自动回到默认（全部 + 空关键字）。不做持久化，
     * 免得下次打开看到一片被旧条件过滤过的日志而不明所以。
     */
    companion object {
        private var lastFilterIndex = 0
        private var lastKeyword = ""
    }

    /** 当前内存里的全部日志（最新在前） */
    private var allLogs: List<Triple<Long, String, Throwable?>> = emptyList()

    private var filterIndex = 0
    private var keyword = ""

    override fun onStart() {
        super.onStart()
        setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.run {
            toolBar.setBackgroundColor(primaryColor)
            toolBar.setTitle(R.string.log)
            toolBar.inflateMenu(R.menu.app_log)
            toolBar.setOnMenuItemClickListener(this@AppLogDialog)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = adapter

            spinnerFilter.adapter = ArrayAdapter(
                requireContext(),
                android.R.layout.simple_spinner_item,
                filters.map { getString(it.title) }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            // 先还原上次的筛选条件（此时还没挂监听，不会触发多余回调）
            filterIndex = lastFilterIndex.coerceIn(0, filters.lastIndex)
            keyword = lastKeyword
            spinnerFilter.setSelection(filterIndex)
            etKeyword.setText(keyword)
            spinnerFilter.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        filterIndex = position
                        lastFilterIndex = position
                        applyFilter()
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }

            etKeyword.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    keyword = s?.toString()?.trim().orEmpty()
                    lastKeyword = keyword
                    applyFilter()
                }
            })

            ivClearFilter.onClick { resetFilter() }
        }
        refresh()
    }

    /** 重新从 [AppLog] 装载并按当前条件筛选 */
    private fun refresh() {
        allLogs = AppLog.logs
        applyFilter()
    }

    private fun resetFilter() {
        keyword = ""
        lastKeyword = ""
        binding.etKeyword.setText("")
        binding.spinnerFilter.setSelection(0)
        filterIndex = 0
        lastFilterIndex = 0
        applyFilter()
    }

    private fun applyFilter() {
        val filter = filters.getOrNull(filterIndex)
        val keys = filter?.keys.orEmpty()
        val kw = keyword
        val list = allLogs.filter { (_, message, throwable) ->
            if (filter?.errorOnly == true && throwable == null) return@filter false
            if (keys.isNotEmpty() && keys.none { message.contains(it, true) }) return@filter false
            kw.isEmpty() || message.contains(kw, true)
        }
        adapter.setItems(list)
        binding.tvMsg.setText(R.string.log_no_match)
        binding.tvMsg.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.toolBar.subtitle = "${list.size}/${allLogs.size}"
        binding.ivClearFilter.visibility =
            if (filterIndex > 0 || kw.isNotEmpty()) View.VISIBLE else View.GONE
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_refresh -> refresh()

            R.id.menu_clear -> {
                AppLog.clear()
                refresh()
            }

            R.id.menu_copy -> {
                // 只复制当前筛选出来的，跟界面上看到的保持一致
                val text = adapter.getItems().joinToString("\n") {
                    "${LogUtils.logTimeFormat.format(Date(it.first))} ${it.second}"
                }
                if (text.isBlank()) {
                    requireContext().toastOnUi(getString(R.string.log_no_match))
                    return true
                }
                val clipboard =
                    requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("AppLog", text))
                requireContext().toastOnUi(getString(R.string.copy_success))
            }
        }
        return true
    }

    inner class LogAdapter(context: Context) :
        RecyclerAdapter<Triple<Long, String, Throwable?>, ItemAppLogBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemAppLogBinding {
            return ItemAppLogBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAppLogBinding,
            item: Triple<Long, String, Throwable?>,
            payloads: MutableList<Any>
        ) {
            binding.textTime.text = LogUtils.logTimeFormat.format(Date(item.first))
            binding.textMessage.text = item.second
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAppLogBinding) {
            binding.root.onClick {
                getItem(holder.layoutPosition)?.let { item ->
                    item.third?.let {
                        showDialogFragment(TextDialog("Log", it.stackTraceToString()))
                    }
                }
            }
        }

    }

}
