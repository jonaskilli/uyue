package io.legado.app.ui.tts.script

import android.annotation.SuppressLint
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.TtsScript
import io.legado.app.databinding.ActivityTtsScriptBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.lib.dialogs.alert
import io.legado.app.help.MiyueKeyManager
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.utils.GSON
import io.legado.app.utils.StringUtils
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TtsScriptActivity : VMBaseActivity<ActivityTtsScriptBinding, TtsScriptViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    TtsScriptAdapter.CallBack {

    override val binding by viewBinding(ActivityTtsScriptBinding::inflate)
    override val viewModel by viewModels<TtsScriptViewModel>()
    private val adapter by lazy { TtsScriptAdapter(this, this) }
    private var scriptFlowJob: Job? = null
    private var dataInit = false

    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
        }

    private val importDoc = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(this)?.let { source ->
                kotlin.runCatching {
                    GSON.fromJson(source, Array<TtsScript>::class.java)?.let { scripts ->
                        lifecycleScope.launch {
                            appDb.ttsScriptDao.insert(*scripts)
                        }
                        setResult(RESULT_OK)
                    }
                }.onFailure { e ->
                    toastOnUi("import error: ${e.localizedMessage}")
                }
            }
        }.onFailure { e ->
            toastOnUi("readTextError:${e.localizedMessage}")
        }
    }

    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton { sendToClip(uri.toString()) }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initKeyManageView()
        initSelectActionView()
        observeScriptData()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tts_script, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        dragSelectTouchHelper.activeSlideSelect()
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    /**
     * 顶部密钥管理入口：与「角色管理」页的保存密钥 / 恢复密钥完全同一套实现。
     */
    private fun initKeyManageView() {
        binding.btnSaveKey.setOnClickListener {
            MiyueKeyManager.saveKey(this)
        }
        binding.btnRestoreKey.setOnClickListener {
            MiyueKeyManager.restoreKey(this)
        }
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) adapter.selectAll() else adapter.revertSelection()
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.delSelection(adapter.selection) }
            noButton()
        }
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.tts_script_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeScriptData(searchKey: String? = null) {
        dataInit = false
        scriptFlowJob?.cancel()
        scriptFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.ttsScriptDao.flowAll()
                searchKey == getString(R.string.enabled) -> appDb.ttsScriptDao.flowEnabled()
                else -> appDb.ttsScriptDao.flowAll()
            }.catch {
                AppLog.put("朗读脚本管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                if (dataInit) setResult(RESULT_OK)
                adapter.setItems(it, adapter.diffItemCallBack)
                dataInit = true
                delay(100)
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_tts_script ->
                editActivity.launch(TtsScriptEditActivity.startIntent(this))
            R.id.menu_del_selection -> viewModel.delSelection(adapter.selection)
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_help -> showHelp("ttsScriptHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(adapter.selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(adapter.selection)
            R.id.menu_top_sel -> viewModel.topSelect(adapter.selection)
            R.id.menu_bottom_sel -> viewModel.bottomSelect(adapter.selection)
            R.id.menu_export_selection -> exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "exportTtsScript.json",
                    GSON.toJson(adapter.selection).toByteArray(),
                    "application/json"
                )
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            adapter.selection.size,
            adapter.itemCount
        )
    }

    override fun update(vararg script: TtsScript) {
        setResult(RESULT_OK)
        viewModel.update(*script)
    }

    override fun delete(script: TtsScript) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + script.name)
            noButton()
            yesButton {
                setResult(RESULT_OK)
                viewModel.delete(script)
            }
        }
    }

    override fun edit(script: TtsScript) {
        setResult(RESULT_OK)
        editActivity.launch(TtsScriptEditActivity.startIntent(this, script.id))
    }

    override fun toTop(script: TtsScript) {
        setResult(RESULT_OK)
        viewModel.toTop(script)
    }

    override fun toBottom(script: TtsScript) {
        setResult(RESULT_OK)
        viewModel.toBottom(script)
    }

    private data class EngineItem(val label: String, val value: String)

    override fun bindTtsEngines(script: TtsScript) {
        setResult(RESULT_OK)
        lifecycleScope.launch(IO) {
            val httpTtsList = appDb.httpTTSDao.all
            val sysEngines = try {
                val tts = TextToSpeech(this@TtsScriptActivity, null)
                val engines = tts.engines
                tts.shutdown()
                engines
            } catch (e: Exception) {
                emptyList<TextToSpeech.EngineInfo>()
            }
            val engineItems = mutableListOf<EngineItem>()
            sysEngines.forEach { engine ->
                engineItems.add(EngineItem(engine.label, "sys:${engine.name}"))
            }
            httpTtsList.forEach { h ->
                engineItems.add(EngineItem(h.name, "http:${h.id}"))
            }
            val checkedArray = BooleanArray(engineItems.size) { index ->
                if (script.bindTtsEngines.isEmpty()) false
                else script.bindTtsEngines.split(",").map { it.trim() }
                    .contains(engineItems[index].value)
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                alert(R.string.bind_tts_engines) {
                    multiChoiceItems(
                        engineItems.map { it.label }.toTypedArray(),
                        checkedArray
                    ) { _, which, isChecked ->
                        checkedArray[which] = isChecked
                    }
                    positiveButton(R.string.save) {
                        val selected = engineItems.filterIndexed { index, _ ->
                            checkedArray[index]
                        }.map { it.value }
                        script.bindTtsEngines = selected.joinToString(",")
                        viewModel.update(script)
                        if (selected.isEmpty()) {
                            toastOnUi("已清除绑定，该脚本对所有朗读引擎生效")
                        } else {
                            toastOnUi("已绑定 ${selected.size} 个朗读引擎")
                        }
                    }
                    negativeButton(R.string.cancel)
                }
            }
        }
    }

    /**
     * 设定参数：弹出 4 个输入框（默认值 = 脚本当前值，超时按秒），
     * 保存后按变量名原位改写脚本代码并写库，下一段朗读即生效。
     * 仅对含参数的脚本调用（菜单项已控制显隐）。
     */
    override fun setParams(script: TtsScript) {
        val values = TtsScriptParams.parse(script.code) ?: run {
            toastOnUi("该脚本没有可配置的参数")
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
        }

        fun addInput(
            param: TtsScriptParams.Param,
            value: String,
            decimal: Boolean
        ): TextInputEditText {
            val til = TextInputLayout(this).apply { hint = param.label }
            val editText = TextInputEditText(this).apply {
                setText(value)
                inputType = if (decimal) {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    InputType.TYPE_CLASS_NUMBER
                }
                setSelectAllOnFocus(true)
            }
            til.addView(editText)
            container.addView(til)
            return editText
        }

        val pChar = TtsScriptParams.PARAMS[0]
        val pLen = TtsScriptParams.PARAMS[1]
        val pRetry = TtsScriptParams.PARAMS[2]
        val pTimeout = TtsScriptParams.PARAMS[3]
        val etChar = addInput(pChar, values.intOf(pChar.varName)?.toString().orEmpty(), false)
        val etLen = addInput(pLen, values.intOf(pLen.varName)?.toString().orEmpty(), false)
        val etRetry = addInput(pRetry, values.intOf(pRetry.varName)?.toString().orEmpty(), false)
        // 超时框按秒编辑，保存时换算回毫秒
        val etTimeout = addInput(
            pTimeout,
            TtsScriptParams.toSecondsText(values.intOf(pTimeout.varName) ?: 0),
            true
        )

        alert(R.string.set_params) {
            customView { container }
            okButton {
                val vChar = readParam(etChar, pChar)
                val vLen = readParam(etLen, pLen)
                val vRetry = readParam(etRetry, pRetry)
                val vTimeout = readParam(etTimeout, pTimeout)
                if (vChar == null || vLen == null || vRetry == null || vTimeout == null) {
                    toastOnUi(R.string.tts_script_params_invalid)
                    return@okButton
                }
                // 注意：必须用 copy 生成新对象去更新库，不能就地改 script.code。
                // 否则适配器里当前条目已被改成新 code，flowAll 重发后 diff 认为内容未变，
                // 不触发重绑，条目上的参数摘要就不会刷新（需退出重进才刷新）。
                viewModel.update(
                    script.copy(
                        code = TtsScriptParams.applyFromInputs(
                            script.code,
                            vChar.toInt(), vLen.toInt(), vRetry.toInt(), vTimeout
                        )
                    )
                )
                toastOnUi(R.string.tts_script_params_saved)
            }
            noButton()
        }
    }

    /** 读取并校验一个参数输入，非法时在输入框上提示取值范围 */
    private fun readParam(editText: TextInputEditText, param: TtsScriptParams.Param): Double? {
        val value = editText.text?.toString()?.trim()?.toDoubleOrNull()
        if (value == null || value < param.min || value > param.max) {
            editText.error = param.rangeHint()
            editText.requestFocus()
            return null
        }
        return value
    }

    override fun upOrder() {
        setResult(RESULT_OK)
        viewModel.upOrder()
    }
}
