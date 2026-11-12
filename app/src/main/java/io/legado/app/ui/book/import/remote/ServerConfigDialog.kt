package io.legado.app.ui.book.import.remote

import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.viewModels
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.rule.RowUi
import io.legado.app.databinding.DialogWebdavServerBinding
import io.legado.app.databinding.ItemSourceEditBinding
import io.legado.app.lib.theme.primaryColor
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import org.json.JSONObject

class ServerConfigDialog() : BaseDialogFragment(R.layout.dialog_webdav_server, true),
    Toolbar.OnMenuItemClickListener {

    constructor(id: Long) : this() {
        arguments = Bundle().apply {
            putLong("id", id)
        }
    }

    private val binding by viewBinding(DialogWebdavServerBinding::bind)
    private val viewModel by viewModels<ServerConfigViewModel>()

    private val webDavServerUi = listOf(
        RowUi("url"),
        RowUi("username"),
        RowUi("password", RowUi.Type.password)
    )

    /** [upWebDavServerUi] 逐行 inflate 出来的输入行，保存时按下标取值（不再靠 view id 反查）。 */
    private val rowBindings = mutableListOf<ItemSourceEditBinding>()

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.setBackgroundColor(primaryColor)
        binding.toolBar.inflateMenu(R.menu.server_config)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener(this)
        viewModel.init(arguments?.getLong("id")) {
            upConfigView(viewModel.mServer)
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> getServer().let {
                viewModel.save(it) {
                    dismissAllowingStateLoss()
                }
            }
        }
        return true
    }

    private fun upConfigView(server: Server?) {
        binding.etName.setText(server?.name)
        binding.spType.setSelection(
            when (server?.type) {
                else -> 0
            }
        )
        when (server?.type) {
            else -> upWebDavServerUi(server?.getConfigJsonObject())
        }
    }

    private fun upWebDavServerUi(config: JSONObject?) {
        // 逐行 inflate 后把 binding 存进列表，保存时按顺序取。
        // 旧实现在这里给根布局改 id（it.root.id = index + 1000），保存时再用
        // findViewById(index + 1000) 捞回来做 ItemSourceEditBinding.bind(rowView)。
        // 这条链天然脆弱：root.id 被覆盖后 ViewBinding 的构造校验（按 id 查 editText）
        // 一旦命中到别的视图就抛
        //   NullPointerException: Missing required view with ID: ...id/editText
        // （真机 3.26.111013 点「保存」必崩）。改为直接持有 binding 引用，不做 id 往返。
        rowBindings.clear()
        binding.flexbox.removeAllViews()
        webDavServerUi.forEachIndexed { index, rowUi ->
            when (rowUi.type) {
                RowUi.Type.text -> ItemSourceEditBinding.inflate(
                    layoutInflater,
                    binding.root,
                    false
                ).let {
                    binding.flexbox.addView(it.root)
                    it.root.id = index + 1000
                    it.textInputLayout.hint = rowUi.name
                    it.editText.setText(config?.getString(rowUi.name))
                    rowBindings.add(it)
                }
                RowUi.Type.password -> ItemSourceEditBinding.inflate(
                    layoutInflater,
                    binding.root,
                    false
                ).let {
                    binding.flexbox.addView(it.root)
                    it.root.id = index + 1000
                    it.textInputLayout.hint = rowUi.name
                    it.editText.inputType =
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                    it.editText.setText(config?.getString(rowUi.name))
                    rowBindings.add(it)
                }
            }
        }
    }

    private fun getServer(): Server {
        val server = viewModel.mServer?.copy() ?: Server()
        server.name = binding.etName.text.toString()
        server.type = when (binding.spType.selectedItemPosition) {
            else -> Server.TYPE.WEBDAV
        }
        server.config = when (server.type) {
            else -> GSON.toJson(getWebDavConfig())
        }
        return server
    }

    private fun getWebDavConfig(): HashMap<String, String> {
        val data = hashMapOf<String, String>()
        // 按 upWebDavServerUi 里的同序绑定取文本，不再 findViewById 反查（见该函数注释）
        webDavServerUi.forEachIndexed { index, rowUi ->
            rowBindings.getOrNull(index)?.editText?.text?.toString()?.let {
                data[rowUi.name] = it
            } ?: run {
                data[rowUi.name] = ""
            }
        }
        return data
    }

}