package io.legado.app.ui.book.import.remote

import android.app.Application
import androidx.lifecycle.MutableLiveData
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.analyzeRule.CustomUrl
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.remote.RemoteBook
import io.legado.app.model.remote.RemoteBookManager
import io.legado.app.model.remote.RemoteBookReadify
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.Collections

class RemoteBookViewModel(application: Application) : BaseViewModel(application) {
    var sortKey = RemoteBookSort.Default
    var sortAscending = false
    val dirList = arrayListOf<RemoteBook>()
    val permissionDenialLiveData = MutableLiveData<Int>()

    var dataCallback: DataCallback? = null

    val dataFlow = callbackFlow<List<RemoteBook>> {

        val list = Collections.synchronizedList(ArrayList<RemoteBook>())

        dataCallback = object : DataCallback {

            override fun setItems(remoteFiles: List<RemoteBook>) {
                list.clear()
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun addItems(remoteFiles: List<RemoteBook>) {
                list.addAll(remoteFiles)
                trySend(list)
            }

            override fun clear() {
                list.clear()
                trySend(emptyList())
            }

            override fun screen(key: String?) {
                if (key.isNullOrBlank()) {
                    trySend(list)
                } else {
                    trySend(
                        list.filter { it.filename.contains(key) }
                    )
                }
            }
        }

        awaitClose {
            dataCallback = null
        }
    }.map { list ->
        if (sortAscending) when (sortKey) {
            RemoteBookSort.Name -> list.sortedWith(compareBy<RemoteBook> { !it.isDir }
                    then compareBy(AlphanumComparator) { it.filename })

            else -> list.sortedWith(compareBy({ !it.isDir }, { it.lastModify }))
        } else when (sortKey) {
            RemoteBookSort.Name -> list.sortedWith { o1, o2 ->
                val compare = -compareValues(o1.isDir, o2.isDir)
                if (compare == 0) {
                    return@sortedWith -AlphanumComparator.compare(o1.filename, o2.filename)
                }
                return@sortedWith compare
            }

            else -> list.sortedWith { o1, o2 ->
                val compare = -compareValues(o1.isDir, o2.isDir)
                if (compare == 0) {
                    return@sortedWith -compareValues(o1.lastModify, o2.lastModify)
                }
                return@sortedWith compare
            }
        }
    }.flowOn(Dispatchers.IO)

    private var remoteBookManager: RemoteBookManager? = null
    var isDefaultWebdav = false
    /** 悦读云书库模式：扁平列表，部分网盘操作不可用 */
    var isReadify = false

    /** 记忆浏览位置的存储键(每个服务器独立) */
    private var serverKey = "default"
    private val dirStoreKey: String
        get() = "remoteBookDirPaths_$serverKey"

    fun initData(readify: Boolean = false, onSuccess: () -> Unit) {
        execute {
            isReadify = readify
            if (readify) {
                serverKey = "readify"
                remoteBookManager = RemoteBookReadify.createOrNull()
                    ?: throw NoStackTraceException("未登录悦读书架, 请先在书架菜单登录")
                return@execute
            }
            isDefaultWebdav = false
            appDb.serverDao.get(AppConfig.remoteServerId)?.getWebDavConfig()?.let {
                val authorization = Authorization(it)
                // 规范化根路径以 / 结尾(上传/浏览均依赖 rootBookUrl 直接拼文件名的约定)
                var baseUrl = it.url.trimEnd('/') + "/"
                // 与默认webDav的约定对齐: 服务器上已存在 books/ 子目录时以其为书籍目录;
                // 不存在则保持根目录, 兼容把书传在根目录的旧数据
                runCatching {
                    if (WebDav(baseUrl + "books/", authorization).exists()) {
                        baseUrl += "books/"
                    }
                }
                remoteBookManager = RemoteBookWebDav(baseUrl, authorization, AppConfig.remoteServerId)
                serverKey = AppConfig.remoteServerId.toString()
                return@execute
            }
            isDefaultWebdav = true
            serverKey = "default"
            remoteBookManager = AppWebDav.defaultBookWebDav
                ?: throw NoStackTraceException("webDav没有配置")
        }.onError {
            val name = if (readify) "悦读书架" else "webDav"
            context.toastOnUi("初始化${name}出错:${it.localizedMessage}")
        }.onSuccess {
            onSuccess.invoke()
        }
    }

    /**
     * 恢复上次浏览的目录(打开界面直接回到书籍所在文件夹)
     * @return 是否有可恢复的目录
     */
    fun restoreDirList(): Boolean {
        return runCatching {
            val paths: List<String>? = GSON.fromJsonArray<String>(
                LocalConfig.getString(dirStoreKey, null)
            ).getOrNull()
            if (paths.isNullOrEmpty()) return false
            dirList.clear()
            paths.forEach { p ->
                val clean = p.removeSuffix("/")
                dirList.add(
                    RemoteBook(
                        filename = clean.substringAfterLast('/'),
                        path = p,
                        size = 0,
                        lastModify = 0,
                        contentType = "folder"
                    )
                )
            }
            dirList.isNotEmpty()
        }.getOrDefault(false)
    }

    /** 持久化当前浏览位置 */
    fun saveDirList() {
        runCatching {
            LocalConfig.putString(dirStoreKey, GSON.toJson(dirList.map { it.path }))
        }
    }

    /** 清空记忆(浏览目录已不存在时回退用) */
    fun clearDirList() {
        dirList.clear()
        LocalConfig.remove(dirStoreKey)
    }

    /** 当前所在目录的远程路径(dirList 为空即根目录) */
    fun currentDirPath(): String? = dirList.lastOrNull()?.path

    fun loadRemoteBookList(
        path: String?,
        loadCallback: (loading: Boolean) -> Unit,
        onError: (() -> Unit)? = null
    ) {
        executeLazy {
            val manager = remoteBookManager
                ?: throw NoStackTraceException("没有配置远程书库")
            dataCallback?.clear()
            val rootUrl = (manager as? RemoteBookWebDav)?.rootBookUrl.orEmpty()
            val bookList = manager.getRemoteBookList(path ?: rootUrl)
            dataCallback?.setItems(bookList)
        }.onError {
            val name = if (isReadify) "悦读书架" else "webDav"
            AppLog.put("获取${name}书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi("获取${name}书籍出错\n${it.localizedMessage}")
            onError?.invoke()
        }.onStart {
            loadCallback.invoke(true)
        }.onFinally {
            loadCallback.invoke(false)
        }.start()
    }

    /**
     * 加入/恢复到书架: 下载远程文件并导入。
     * 对已上架的书(本地文件丢失/换机场景), 重新下载后新文件的 bookUrl 可能变化
     * (SAF 重新 createFile 得到新 documentId), 此时清理失效的旧记录避免书架出现重复。
     */
    fun addToBookshelf(remoteBooks: HashSet<RemoteBook>, finally: () -> Unit) {
        execute {
            val manager = remoteBookManager
                ?: throw NoStackTraceException("没有配置远程书库")
            val bookWebDav = manager as? RemoteBookWebDav
            remoteBooks.forEach { remoteBook ->
                val downloadBookUri = manager.downloadRemoteBook(remoteBook)
                LocalBook.importFiles(downloadBookUri).forEach { book ->
                    // 悦读下载下来就是一份完整的本地文件，保持本地书的 origin 即可
                    if (bookWebDav != null) {
                        book.origin = BookType.webDavTag + CustomUrl(remoteBook.path)
                            .putAttribute("serverID", bookWebDav.serverID)
                            .toString()
                    }
                    book.save()
                    runCatching {
                        appDb.bookDao.getBookByFileName(book.originName)?.let { old ->
                            if (old.bookUrl != book.bookUrl) {
                                LocalBook.deleteBook(old, false)
                            }
                        }
                    }
                }
                remoteBook.isOnBookShelf = true
            }
        }.onError {
            AppLog.put("导入出错\n${it.localizedMessage}", it)
            context.toastOnUi("导入出错\n${it.localizedMessage}")
            if (it is SecurityException) {
                permissionDenialLiveData.postValue(1)
            }
        }.onFinally {
            finally.invoke()
        }
    }

    fun updateCallBackFlow(filterKey: String?) {
        dataCallback?.screen(filterKey)
    }

    /**
     * 批量删除远程书籍/目录
     */
    fun deleteRemoteBooks(remoteBooks: Collection<RemoteBook>, finally: () -> Unit) {
        execute {
            val bookWebDav = remoteBookManager
                ?: throw NoStackTraceException("没有配置远程书库")
            val failed = arrayListOf<String>()
            remoteBooks.forEach { remoteBook ->
                try {
                    bookWebDav.delete(remoteBook.path)
                } catch (e: Exception) {
                    failed.add(remoteBook.filename)
                    AppLog.put("删除远程书《${remoteBook.filename}》失败\n${e.localizedMessage}", e)
                }
            }
            if (failed.isNotEmpty()) {
                throw NoStackTraceException("删除失败:${failed.joinToString("、")}")
            }
        }.onError {
            AppLog.put("删除远程书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi(it.localizedMessage)
        }.onFinally {
            finally.invoke()
        }
    }

    /**
     * 重命名远程书籍/目录（保留原扩展名）
     * @param newName 用户输入的新名字, 可不含扩展名
     * @return 是否成功
     */
    fun renameRemoteBook(
        remoteBook: RemoteBook,
        newName: String,
        onFinally: (success: Boolean) -> Unit
    ) {
        execute {
            val bookWebDav = remoteBookManager as? RemoteBookWebDav
                ?: throw NoStackTraceException("当前书库不支持重命名")
            var name = newName.trim()
            if (name.isEmpty()) throw NoStackTraceException("名字不能为空")
            if (name.contains('/') || name.contains('\\')) {
                throw NoStackTraceException("名字不能包含 / 或 \\ ")
            }
            // 未输入扩展名时自动带上原扩展名
            val oldSuffix = remoteBook.filename.substringAfterLast('.', "")
            if (!remoteBook.isDir && oldSuffix.isNotEmpty()
                && !name.endsWith(".$oldSuffix", ignoreCase = true)
            ) {
                name = "$name.$oldSuffix"
            }
            // 目标 = 父目录 + 新名字; 目录的 path 以 / 结尾, 去掉后取父目录
            val parent = remoteBook.path.removeSuffix("/")
                .substringBeforeLast('/')
            bookWebDav.move(remoteBook.path, "$parent/$name", overwrite = false)
            // 原始文件名是书源判定/本地索引的一部分, 重命名后本地书架的对应关系由用户重新加入书架即可
        }.onSuccess {
            onFinally.invoke(true)
        }.onError {
            AppLog.put("重命名远程书籍出错\n${it.localizedMessage}", it)
            context.toastOnUi(it.localizedMessage)
            onFinally.invoke(false)
        }
    }

    interface DataCallback {

        fun setItems(remoteFiles: List<RemoteBook>)

        fun addItems(remoteFiles: List<RemoteBook>)

        fun clear()

        fun screen(key: String?)

    }
}