package io.legado.app.ui.main.bookshelf

import android.annotation.SuppressLint
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.indices
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.DialogBookshelfConfigBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogReadifyLoginBinding
import io.legado.app.databinding.DialogReadifySessionEditBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.model.readify.ReadifyApi
import io.legado.app.model.readify.ReadifySession
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.cache.CacheActivity
import io.legado.app.ui.book.group.GroupManageDialog
import io.legado.app.ui.book.import.local.ImportBookActivity
import io.legado.app.ui.book.import.remote.RemoteBookActivity
import io.legado.app.ui.book.manage.BookshelfManageActivity
import io.legado.app.ui.book.read.config.CharacterProfileManageActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.main.MainViewModel
import io.legado.app.ui.tts.plugin.PluginLoginActivity
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.checkByIndex
import io.legado.app.utils.getCheckedIndex
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

abstract class BaseBookshelfFragment(layoutId: Int) : VMBaseFragment<BookshelfViewModel>(layoutId),
    MainFragmentInterface {

    override val position: Int? get() = arguments?.getInt("position")

    val activityViewModel by activityViewModels<MainViewModel>()
    override val viewModel by viewModels<BookshelfViewModel>()

    private val importBookshelf = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(requireContext())?.let { text ->
                viewModel.importBookshelf(text, groupId)
            }
        }.onFailure {
            toastOnUi(it.localizedMessage ?: "ERROR")
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    requireContext().sendToClip(uri.toString())
                }
            }
        }
    }
    abstract val groupId: Long
    abstract val books: List<Book>
    abstract var onlyUpdateRead: Boolean

    /**
     * 悦读网页登录：完全复用 TTS 插件的 PluginLoginActivity
     * （电脑模式 UA + 桌面视口 + localStorage/sessionStorage/cookie 提取）。
     * binding 用 locals.user，与插件「本悦读 v12」的 defVars 一致。
     */
    private val readifyLoginResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == PluginLoginActivity.OK) {
            val value = result.data?.getStringExtra(PluginLoginActivity.RESULT).orEmpty()
            if (ReadifySession.save(value)) {
                toastOnUi("已登录悦读账号：${ReadifySession.displayName}")
                activity?.invalidateOptionsMenu()
            } else {
                toastOnUi("未取到完整登录信息，请完成网页登录后再点保存")
            }
        }
    }

    private var groupsLiveData: LiveData<List<BookGroup>>? = null
    private val waitDialog by lazy {
        WaitDialog(requireContext()).apply {
            setOnCancelListener {
                viewModel.addBookJob?.cancel()
            }
        }
    }

    abstract fun gotoTop()

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_bookshelf, menu)
        menu.findItem(R.id.menu_readify_login)?.title =
            if (ReadifySession.isLogin) {
                // 只贴了 refreshToken 的会话没有昵称，别显示成"悦读已登录："
                val name = ReadifySession.displayName
                if (name.isBlank()) "悦读已登录" else "悦读已登录：$name"
            } else {
                "登录悦读账号"
            }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        when (item.itemId) {
            R.id.menu_remote -> startActivity<RemoteBookActivity>()
            R.id.menu_upload_webdav -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
                putExtra("uploadWebDav", true)
            }
            R.id.menu_search -> startActivity<SearchActivity>()
            R.id.menu_update_toc -> activityViewModel.upToc(books, onlyUpdateRead)
            R.id.menu_bookshelf_layout -> configBookshelf()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_add_local -> startActivity<ImportBookActivity>()
            R.id.menu_add_url -> showAddBookByUrlAlert()
            R.id.menu_bookshelf_manage -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_download -> startActivity<CacheActivity> {
                putExtra("groupId", groupId)
            }

            R.id.menu_export_bookshelf -> viewModel.exportBookshelf(books) { file ->
                exportResult.launch {
                    mode = HandleFileContract.EXPORT
                    fileData =
                        HandleFileContract.FileData("bookshelf.json", file, "application/json")
                }
            }

            R.id.menu_import_bookshelf -> importBookshelfAlert(groupId)
            // 角色档案总揽: 按书分组列出全部 AI 角色档案。放书架而不是书籍详情页,
            // 这样书删掉后也能直接进来, 不用先找一本书打开
            R.id.menu_readify_login -> startReadifyLogin()
            R.id.menu_readify_upload -> startActivity<BookshelfManageActivity> {
                putExtra("groupId", groupId)
                putExtra("uploadReadify", true)
            }
            R.id.menu_readify_download -> startActivity<RemoteBookActivity> {
                putExtra("readify", true)
            }
            R.id.menu_character_profile -> startActivity<CharacterProfileManageActivity>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
    }

    // ---------------------------------------------------------- 悦读云同步

    /**
     * 登录入口：优先「账号密码直登」（调登录接口，不加载网页，秒级完成）；
     * 网页登录留给手机号/微信扫码兜底；也可手工编辑登录认证，或退出登录。
     *
     * 注意：AlertController 的 setMessage 与 setItems 互斥——同屏设置 message 时
     * items 列表根本不会挂到视图树上（appcompat AlertController#setupContent），
     * 所以这里把状态信息并进标题，列表单独用。
     */
    private fun startReadifyLogin() {
        val options = mutableListOf("账号密码登录（快）", "网页登录", "编辑登录认证")
        if (ReadifySession.isLogin) options.add("退出登录")
        val title = if (ReadifySession.isLogin) {
            val name = ReadifySession.displayName
            if (name.isBlank()) "悦读已登录" else "悦读已登录：$name"
        } else {
            "登录悦读账号"
        }
        alert(title = title) {
            items(options) { _, index ->
                when (options[index]) {
                    "账号密码登录（快）" -> showReadifyPasswordLogin()
                    "网页登录" -> launchReadifyWebLogin()
                    "编辑登录认证" -> showReadifySessionEdit()
                    "退出登录" -> confirmReadifyLogout()
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    /** 网页登录：复用 TTS 插件的 PluginLoginActivity（电脑模式 UA + localStorage 提取） */
    private fun launchReadifyWebLogin() {
        readifyLoginResult.launch(
            Intent(requireContext(), PluginLoginActivity::class.java).apply {
                putExtra(PluginLoginActivity.ARG_LOGIN_URL, ReadifySession.LOGIN_URL)
                putExtra(PluginLoginActivity.ARG_BINDING, ReadifySession.LOGIN_BINDING)
                putExtra(PluginLoginActivity.ARG_UA, ReadifySession.LOGIN_UA)
                putExtra(
                    PluginLoginActivity.ARG_DESC,
                    "登录悦读后点右上角「保存」带回会话"
                )
            }
        )
    }

    /**
     * 账号密码直登：调 /api/users/email/login 直接换 refreshToken。
     * 凭据只按用户勾选存本机 SharedPreferences，不写死进代码/仓库。
     */
    @SuppressLint("InflateParams")
    private fun showReadifyPasswordLogin() {
        val binding = DialogReadifyLoginBinding.inflate(layoutInflater)
        val savedAccount = requireContext().getPrefString(PreferKey.readifyLoginAccount)
        val savedPassword = requireContext().getPrefString(PreferKey.readifyLoginPassword)
        binding.etAccount.setText(savedAccount.orEmpty())
        binding.etPassword.setText(savedPassword.orEmpty())
        binding.cbRemember.isChecked =
            !savedAccount.isNullOrBlank() && !savedPassword.isNullOrBlank()
        alert(title = "悦读账号登录") {
            customView { binding.root }
            positiveButton("登录") {
                val account = binding.etAccount.text?.toString()?.trim().orEmpty()
                val password = binding.etPassword.text?.toString().orEmpty()
                if (account.isBlank() || password.isBlank()) {
                    toastOnUi("请输入账号和密码")
                    return@positiveButton
                }
                if (binding.cbRemember.isChecked) {
                    requireContext().putPrefString(PreferKey.readifyLoginAccount, account)
                    requireContext().putPrefString(PreferKey.readifyLoginPassword, password)
                } else {
                    requireContext().putPrefString(PreferKey.readifyLoginAccount, null)
                    requireContext().putPrefString(PreferKey.readifyLoginPassword, null)
                }
                waitDialog.setTitle("正在登录悦读")
                waitDialog.show()
                lifecycleScope.launch {
                    try {
                        val name = ReadifyApi.loginWithPassword(account, password)
                        waitDialog.dismiss()
                        toastOnUi("已登录悦读账号：$name")
                        activity?.invalidateOptionsMenu()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        waitDialog.dismiss()
                        toastOnUi(e.localizedMessage ?: "悦读登录失败")
                    }
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    /**
     * 手工编辑登录认证：粘贴网页提取的整串会话 JSON（localStorage.user），
     * 或只贴单独的 refreshToken；保存后与网页登录完全等价。
     */
    @SuppressLint("InflateParams")
    private fun showReadifySessionEdit() {
        val binding = DialogReadifySessionEditBinding.inflate(layoutInflater)
        binding.etSession.setText(ReadifySession.raw.orEmpty())
        alert(title = "编辑登录认证") {
            customView { binding.root }
            positiveButton("保存") {
                val input = binding.etSession.text?.toString().orEmpty()
                if (input.isBlank()) {
                    toastOnUi("认证内容不能为空")
                    return@positiveButton
                }
                if (ReadifySession.saveFromInput(input)) {
                    val name = ReadifySession.displayName
                    toastOnUi(
                        if (name.isBlank()) "已保存悦读登录认证" else "已登录悦读账号：$name"
                    )
                    activity?.invalidateOptionsMenu()
                } else {
                    toastOnUi("认证格式不正确：需要含 refreshToken 的会话 JSON，或单独的 refreshToken")
                }
            }
            negativeButton(android.R.string.cancel)
        }
    }

    /** 退出登录：二次确认后清掉本机会话（云端书架数据不受影响） */
    private fun confirmReadifyLogout() {
        alert(title = "退出登录") {
            setMessage("退出后将清除本机保存的悦读登录认证，云端书架数据不受影响。")
            positiveButton("退出登录") {
                ReadifyApi.logout()
                toastOnUi("已退出悦读账号")
                activity?.invalidateOptionsMenu()
            }
            negativeButton(android.R.string.cancel)
        }
    }

    protected fun initBookGroupData() {
        groupsLiveData?.removeObservers(viewLifecycleOwner)
        groupsLiveData = appDb.bookGroupDao.show.apply {
            observe(viewLifecycleOwner) {
                upGroup(it)
            }
        }
    }

    abstract fun upGroup(data: List<BookGroup>)

    abstract fun upSort()

    override fun observeLiveBus() {
        viewModel.addBookProgressLiveData.observe(this) { count ->
            if (count < 0) {
                waitDialog.dismiss()
            } else {
                waitDialog.setText("添加中... ($count)")
            }
        }
    }

    @SuppressLint("InflateParams")
    fun showAddBookByUrlAlert() {
        alert(titleResource = R.string.add_book_url) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    waitDialog.setText("添加中...")
                    waitDialog.show()
                    viewModel.addBookByUrl(it)
                }
            }
            cancelButton()
        }
    }

    @SuppressLint("InflateParams")
    fun configBookshelf() {
        alert(titleResource = R.string.bookshelf_layout) {
            var bookshelfLayout = AppConfig.bookshelfLayout
            var bookshelfSort = AppConfig.bookshelfSort
            var showBookname = AppConfig.showBookname
            val alertBinding =
                DialogBookshelfConfigBinding.inflate(layoutInflater)
                    .apply {
                        if (AppConfig.bookGroupStyle !in 0..<spGroupStyle.count) {
                            AppConfig.bookGroupStyle = 0
                        }
                        if (bookshelfLayout !in rgLayout.indices) {
                            bookshelfLayout = 0
                            AppConfig.bookshelfLayout = 0
                        }
                        if (bookshelfSort !in rgSort.indices) {
                            bookshelfSort = 0
                            AppConfig.bookshelfSort = 0
                        }
                        if (showBookname !in rgbLayout.indices) {
                            showBookname = 0
                            AppConfig.showBookname = 0
                        }
                        spGroupStyle.setSelection(AppConfig.bookGroupStyle)
                        swShowUnread.isChecked = AppConfig.showUnread
                        swShowLastUpdateTime.isChecked = AppConfig.showLastUpdateTime
                        swShowWaitUpBooks.isChecked = AppConfig.showWaitUpCount
                        swShowBookshelfFastScroller.isChecked = AppConfig.showBookshelfFastScroller
                        rgLayout.checkByIndex(bookshelfLayout)
                        rgbLayout.checkByIndex(showBookname)
                        if (bookshelfLayout < 2) {
                            bookNameChoice.visibility = View.GONE
                        }
                        rgLayout.setOnCheckedChangeListener { group, checkedId ->
                            val index = group.getCheckedIndex()
                            bookNameChoice.visibility = if (index > 1) View.VISIBLE else View.GONE
                        }
                        rgSort.checkByIndex(bookshelfSort)
                        margin.progress = AppConfig.bookshelfMargin
                    }
            customView { alertBinding.root }
            okButton {
                alertBinding.apply {
                    var notifyMain = false
                    var recreate = false
                    if (AppConfig.bookGroupStyle != spGroupStyle.selectedItemPosition) {
                        AppConfig.bookGroupStyle = spGroupStyle.selectedItemPosition
                        notifyMain = true
                    }
                    if (showBookname != rgbLayout.getCheckedIndex()) {
                        AppConfig.showBookname = rgbLayout.getCheckedIndex()
                        recreate = true
                    }
                    if (AppConfig.bookshelfMargin != margin.progress) {
                        AppConfig.bookshelfMargin = margin.progress
                        recreate = true
                    }
                    if (AppConfig.showUnread != swShowUnread.isChecked) {
                        AppConfig.showUnread = swShowUnread.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showLastUpdateTime != swShowLastUpdateTime.isChecked) {
                        AppConfig.showLastUpdateTime = swShowLastUpdateTime.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (AppConfig.showWaitUpCount != swShowWaitUpBooks.isChecked) {
                        AppConfig.showWaitUpCount = swShowWaitUpBooks.isChecked
                        activityViewModel.postUpBooksLiveData(true)
                    }
                    if (AppConfig.showBookshelfFastScroller != swShowBookshelfFastScroller.isChecked) {
                        AppConfig.showBookshelfFastScroller = swShowBookshelfFastScroller.isChecked
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    }
                    if (bookshelfSort != rgSort.getCheckedIndex()) {
                        AppConfig.bookshelfSort = rgSort.getCheckedIndex()
                        upSort()
                    }
                    if (bookshelfLayout != rgLayout.getCheckedIndex()) {
                        AppConfig.bookshelfLayout = rgLayout.getCheckedIndex()
                        if (AppConfig.bookshelfLayout < 2) {
                            activityViewModel.booksGridRecycledViewPool.clear()
                        } else {
                            activityViewModel.booksListRecycledViewPool.clear()
                        }
                        recreate = true
                    }
                    if (recreate) {
                        postEvent(EventBus.RECREATE, "")
                    } else if (notifyMain) {
                        postEvent(EventBus.NOTIFY_MAIN, false)
                    }
                }
            }
            cancelButton()
        }
    }


    private fun importBookshelfAlert(groupId: Long) {
        alert(titleResource = R.string.import_bookshelf) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url/json"
            }
            customView { alertBinding.root }
            okButton {
                alertBinding.editView.text?.toString()?.let {
                    viewModel.importBookshelf(it, groupId)
                }
            }
            cancelButton()
            neutralButton(R.string.select_file) {
                importBookshelf.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            }
        }
    }

}