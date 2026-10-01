package io.legado.app.ui.book.import.remote

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.widget.EditText
import android.view.MenuItem
import android.view.SubMenu
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isGone
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.book.import.BaseImportBookActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.utils.ArchiveUtils
import io.legado.app.utils.FileDoc
import io.legado.app.utils.dpToPx
import io.legado.app.utils.find
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.io.File

/**
 * 展示远程书籍
 */
class RemoteBookActivity : BaseImportBookActivity<RemoteBookViewModel>(),
    RemoteBookAdapter.CallBack,
    SelectActionBar.CallBack,
    PopupMenu.OnMenuItemClickListener,
    ServersDialog.Callback {

    override val viewModel by viewModels<RemoteBookViewModel>()
    private val adapter by lazy { RemoteBookAdapter(this, this) }
    private var groupMenu: SubMenu? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        searchView.queryHint = getString(R.string.screen) + " • " + getString(R.string.remote_book)
        onBackPressedDispatcher.addCallback(this) {
            if (!goBackDir()) {
                finish()
            }
        }
        lifecycleScope.launch {
            if (!setBookStorage()) {
                finish()
                return@launch
            }
            initView()
            initEvent()
            launch {
                viewModel.dataFlow.conflate().collect { sortedRemoteBooks ->
                    binding.refreshProgressBar.isAutoLoading = false
                    binding.tvEmptyMsg.isGone = sortedRemoteBooks.isNotEmpty()
                    adapter.setItems(sortedRemoteBooks)
                    delay(500)
                }
            }
            viewModel.initData {
                // 直接回到上次浏览的目录(即书籍所在文件夹);
                // 无记录或目录已被删除时回退到书籍根目录
                if (viewModel.restoreDirList()) {
                    upPath(onError = {
                        viewModel.clearDirList()
                        upPath()
                    })
                } else {
                    upPath()
                }
            }
        }
    }

    override fun observeLiveBus() {
        viewModel.permissionDenialLiveData.observe(this) {
            localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
            }
        }
    }

    private fun initView() {
        binding.layTop.setBackgroundColor(backgroundColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.selectActionBar.setMainActionText(R.string.add_to_bookshelf)
        binding.selectActionBar.setCallBack(this)
        // 选择栏弹出菜单: 批量删除选中项
        binding.selectActionBar.inflateMenu(R.menu.remote_book_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        if (!LocalConfig.webDavBookHelpVersionIsLast) {
            showHelp("webDavBookHelp")
        }
    }

    private fun sortCheck(sortKey: RemoteBookSort) {
        if (viewModel.sortKey == sortKey) {
            viewModel.sortAscending = !viewModel.sortAscending
        } else {
            viewModel.sortAscending = true
            viewModel.sortKey = sortKey
        }
    }

    private fun initEvent() {
        binding.tvGoBack.setOnClickListener {
            goBackDir()
        }
    }


    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_remote, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_refresh -> upPath()
            R.id.menu_server_config -> showDialogFragment<ServersDialog>()
            R.id.menu_log -> showDialogFragment<AppLogDialog>()
            R.id.menu_help -> showHelp("webDavBookHelp")
            R.id.menu_sort_name -> {
                item.isChecked = true
                sortCheck(RemoteBookSort.Name)
                upPath()
            }
            R.id.menu_sort_time -> {
                item.isChecked = true
                sortCheck(RemoteBookSort.Default)
                upPath()
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_sort)?.subMenu
        groupMenu?.setGroupCheckable(R.id.menu_group_sort, true, true)
        groupMenu?.findItem(R.id.menu_sort_name)?.isChecked =
            viewModel.sortKey == RemoteBookSort.Name
        groupMenu?.findItem(R.id.menu_sort_time)?.isChecked =
            viewModel.sortKey == RemoteBookSort.Default
        return super.onPrepareOptionsMenu(menu)
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun selectAll(selectAll: Boolean) {
        adapter.selectAll(selectAll)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onClickSelectBarMainAction() {
        binding.refreshProgressBar.isAutoLoading = true
        viewModel.addToBookshelf(adapter.selected) {
            adapter.selected.clear()
            adapter.notifyDataSetChanged()
            binding.refreshProgressBar.isAutoLoading = false
        }
    }

    private fun goBackDir(): Boolean {
        if (viewModel.dirList.isEmpty()) {
            return false
        }
        viewModel.dirList.removeLastOrNull()
        upPath()
        return true
    }

    private fun upPath(onError: (() -> Unit)? = null) {
        binding.tvGoBack.isEnabled = viewModel.dirList.isNotEmpty()
        var path = if (viewModel.isDefaultWebdav) {
            "books" + File.separator
        } else {
            File.separator
        }
        viewModel.dirList.forEach {
            path = path + it.filename + File.separator
        }
        binding.tvPath.text = path
        viewModel.dataCallback?.clear()
        adapter.selected.clear()
        // 记住浏览位置, 下次打开直接回到此目录
        viewModel.saveDirList()
        viewModel.loadRemoteBookList(
            viewModel.dirList.lastOrNull()?.path,
            {
                binding.refreshProgressBar.isAutoLoading = it
            },
            onError
        )
    }

    override fun openDir(remoteBook: RemoteBook) {
        viewModel.dirList.add(remoteBook)
        upPath()
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(adapter.selected.size, adapter.checkableCount)
        // 选中含已上架的书时, 主按钮语义是"重新下载恢复", 而非首次加入
        binding.selectActionBar.setMainActionText(
            if (adapter.selected.any { it.isOnBookShelf }) "恢复到书架"
            else getString(R.string.add_to_bookshelf)
        )
    }

    override fun onDialogDismiss(tag: String) {
        viewModel.initData {
            upPath()
        }
    }

    /** 选择栏弹出菜单: 批量删除选中项(含确认) */
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_del_selection -> deleteRemoteBooks(adapter.selected)
        }
        return false
    }

    override fun onSearchTextChange(newText: String?) {
        viewModel.updateCallBackFlow(newText)
    }

    private fun showRemoteBookDownloadAlert(
        remoteBook: RemoteBook,
        onDownloadFinish: (() -> Unit)? = null
    ) {
        alert(
            R.string.draw,
            R.string.archive_not_found
        ) {
            okButton {
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    onDownloadFinish?.invoke()
                }
            }
            noButton()
        }
    }

    override fun startRead(remoteBook: RemoteBook) {
        val downloadFileName = remoteBook.filename
        // 兼容文件名含作者/zip 包装等差异, 按书名级匹配查找书架中的对应书籍
        LocalBook.findShelfBook(downloadFileName)?.let {
            startReadBook(it)
            return
        }
        if (!ArchiveUtils.isArchive(downloadFileName)) {
            // 书架里没有对应书籍, 保持静默(由用户勾选后加入书架)
            return
        } else {
            AppConfig.defaultBookTreeUri ?: return
            val downloadArchiveFileDoc = FileDoc.fromUri(Uri.parse(AppConfig.defaultBookTreeUri), true)
                .find(downloadFileName)
            if (downloadArchiveFileDoc == null) {
                showRemoteBookDownloadAlert(remoteBook) {
                    startRead(remoteBook)
                }
            } else {
                onArchiveFileClick(downloadArchiveFileDoc)
            }
        }
    }

    override fun addToBookShelfAgain(remoteBook: RemoteBook) {
        alert(getString(R.string.sure), "是否重新加入书架？") {
            yesButton {
                binding.refreshProgressBar.isAutoLoading = true
                viewModel.addToBookshelf(hashSetOf(remoteBook)) {
                    binding.refreshProgressBar.isAutoLoading = false
                }
            }
            noButton()
        }
    }

    override fun deleteRemoteBooks(remoteBooks: Collection<RemoteBook>) {
        if (remoteBooks.isEmpty()) return
        val names = remoteBooks.joinToString("、") { it.filename }
        alert(getString(R.string.draw), "确定删除远程书籍？\n$names\n\n删除后无法恢复！") {
            yesButton {
                binding.refreshProgressBar.isAutoLoading = true
                viewModel.deleteRemoteBooks(remoteBooks) {
                    // 重新拉取列表; 删除的是当前目录内容时也应刷新
                    upPath()
                }
            }
            noButton()
        }
    }

    override fun renameRemoteBook(remoteBook: RemoteBook) {
        val input = EditText(this).apply {
            setText(remoteBook.filename)
            setSelection(text?.length ?: 0)
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 0)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(input)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty() || newName == remoteBook.filename) {
                    return@setPositiveButton
                }
                binding.refreshProgressBar.isAutoLoading = true
                viewModel.renameRemoteBook(remoteBook, newName) { success ->
                    if (success) {
                        upPath()
                    } else {
                        binding.refreshProgressBar.isAutoLoading = false
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

}
