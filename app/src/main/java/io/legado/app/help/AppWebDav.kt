package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.removePref
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import splitties.init.appCtx
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    var authorization: Authorization? = null
        private set

    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    init {
        runBlocking {
            upConfig()
        }
    }

    private val rootWebDavUrl: String
        get() {
            val configUrl = appCtx.getPrefString(PreferKey.webDavUrl)?.trim()
            var url = if (configUrl.isNullOrEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            AppConfig.webDavDir?.trim()?.let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig() {
        kotlin.runCatching {
            authorization = null
            defaultBookWebDav = null
            val account = appCtx.getPrefString(PreferKey.webDavAccount)
            val password = appCtx.getPrefString(PreferKey.webDavPassword)
            if (!account.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val mAuthorization = Authorization(account, password)
                checkAuthorization(mAuthorization)
                WebDav(rootWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bookProgressUrl, mAuthorization).makeAsDir()
                WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()
                WebDav(bgWebDavUrl, mAuthorization).makeAsDir()
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
            }
        }
    }

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    @Throws(WebDavException::class)
    suspend fun restoreWebDav(name: String) {
        authorization?.let {
            val webDav = WebDav(rootWebDavUrl + name, it)
            webDav.downloadTo(Backup.zipFilePath, true)
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
            Restore.restoreLocked(Backup.backupPath)
            // 恢复完刷新本地备份时间戳, 否则下次启动 backupSync 会再次认为
            // "云端领先"并重复弹出恢复提示(从备份与恢复页手动恢复时尤其明显)
            LocalConfig.lastBackup = System.currentTimeMillis()
        }
    }

    /**
     * 列出云端所有 TTS 备份文件名
     *
     * TTS 备份是独立于主备份的 zip(命名 ttsBackup*.zip), 不能被 [lastBackUp] 和
     * [clearOldBackups] 当成主备份处理, 所以这里单独按 "ttsBackup" 前缀过滤。
     */
    @Throws(Exception::class)
    suspend fun getTtsBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            val files = WebDav(rootWebDavUrl, it).listFiles()
                .sortedWith { o1, o2 ->
                    AlphanumComparator.compare(o1.displayName, o2.displayName)
                }.reversed()
            files.forEach { webDav ->
                if (webDav.displayName.startsWith(TTS_BACKUP_PREFIX)) {
                    names.add(webDav.displayName)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

    /**
     * 上传 TTS 备份到 webDav
     */
    suspend fun upTtsBackup(fileName: String, file: File): Boolean {
        if (!NetworkUtils.isAvailable()) return false
        val authorization = authorization ?: return false
        if (!file.exists()) return false
        return kotlin.runCatching {
            WebDav("$rootWebDavUrl$fileName", authorization).upload(file)
            true
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传TTS备份失败\n${it.localizedMessage}", it)
        }.getOrDefault(false)
    }

    /**
     * 下载云端 TTS 备份到本地文件
     */
    @Throws(WebDavException::class)
    suspend fun downTtsBackup(name: String, destFile: File) {
        val authorization = authorization ?: throw WebDavException("webDav没有配置")
        FileUtils.delete(destFile.absolutePath)
        WebDav(rootWebDavUrl + name, authorization).downloadTo(destFile.absolutePath, true)
    }

    /** TTS 备份文件名的统一前缀 */
    const val TTS_BACKUP_PREFIX = "ttsBackup"

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().forEach { webDavFile ->
                    // size 为 0 的一定是上传中断残留的半成品(正常备份不可能为空),
                    // 不能当成最新备份, 否则恢复时会拿到一个空 zip
                    if (webDavFile.displayName.startsWith("backup")
                        && webDavFile.size > 0
                    ) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    /**
     * 上传备份文件到 webDav
     *
     * 实测部分服务端(如 Koofr)在收到 PUT 请求、刚开始接收数据时就会创建文件并写入
     * "传输开始"的时间戳, 只有完整传完才会把时间戳刷新为"传输完成"。因此上传失败时
     * 云端很可能残留一个 0 字节、时间戳为"传输开始"的半成品文件。
     * 所以这里在上传结束后统一回查一次云端文件信息并返回, 由调用方用云端真实时间戳
     * 作为本地基准(消除设备时钟与服务器时钟的偏差、以及打包耗时带来的误判),
     * 上传失败且云端文件不完整时顺手清理半成品。
     *
     * @param fileName 备份文件名
     */
    suspend fun backUpWebDav(fileName: String): BackUpResult {
        val localSize = File(Backup.zipFilePath).length()
        if (!NetworkUtils.isAvailable()) return BackUpResult()
        val authorization = authorization ?: return BackUpResult()
        val putUrl = "$rootWebDavUrl$fileName"
        var uploaded = false
        try {
            WebDav(putUrl, authorization).upload(Backup.zipFilePath)
            uploaded = true
            clearOldBackups()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传备份至webdav失败\n${e.localizedMessage}", e)
        }
        val remote = kotlin.runCatching {
            WebDav(putUrl, authorization).getWebDavFile()
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("查询webDav备份信息失败\n${it.localizedMessage}", it)
        }.getOrNull()
        if (remote == null) {
            // 云端没有这个文件, 调用方回退到本地时间
            return BackUpResult(uploaded = uploaded)
        }
        if (!uploaded && localSize > 0 && remote.size != localSize) {
            // 上传中断残留的半成品, 删掉以免被当成最新备份、也避免恢复到空 zip
            if (remote.delete()) {
                return BackUpResult(uploaded = false)
            }
        }
        return BackUpResult(
            uploaded = uploaded,
            remoteLastModify = remote.lastModify,
            remoteSize = remote.size
        )
    }

    /**
     * 清理webDav上的旧备份,只保留最新的[keepCount]个备份
     * 备份文件名按时间倒序排列,超出数量的旧备份会被删除
     * [keepCount]小于等于0时不限制数量,不删除任何备份
     * @return 删除的备份数量
     */
    suspend fun clearOldBackups(keepCount: Int = AppConfig.webDavBackupKeepCount): Int {
        if (keepCount <= 0) return 0
        val authorization = authorization ?: return 0
        if (!NetworkUtils.isAvailable()) return 0
        return kotlin.runCatching {
            val files = WebDav(rootWebDavUrl, authorization).listFiles()
                .filter { !it.isDir && it.displayName.startsWith("backup") }
                .sortedWith { o1, o2 ->
                    AlphanumComparator.compare(o2.displayName, o1.displayName)
                }
            var count = 0
            files.forEachIndexed { index, file ->
                if (index >= keepCount && file.delete()) {
                    count++
                }
            }
            count
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("清理webDav旧备份失败\n${it.localizedMessage}", it)
        }.getOrDefault(0)
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
    }

    /**
     * 下载背景图片
     */
    suspend fun downBgs() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(byteArray, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String) {
        if (!NetworkUtils.isAvailable()) return
        try {
            authorization?.let {
                // 如果导出的本地文件存在,开始上传
                val putUrl = exportsWebDavUrl + fileName
                WebDav(putUrl, it).upload(uri, "text/plain")
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!AppConfig.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(bookProgress: BookProgress, onSuccess: (() -> Unit)? = null) {
        try {
            val authorization = authorization ?: return
            if (!AppConfig.syncBookProgress) return
            if (!NetworkUtils.isAvailable()) return
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        val url = getProgressUrl(book.name, book.author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()
                }
            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName]
            webDavFile ?: return
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return
            }
            getBookProgress(book)?.let { bookProgress ->
                // 同设备：这份云端进度是本机自己之前传的，用户主动退回重看时
                // 不该在启动时被云端旧位置覆盖回去
                if (bookProgress.isFromSameDevice()) return@let
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

}

/**
 * webDav 备份上传结果
 *
 * @property uploaded 是否完整上传成功(无网络/未配置/上传抛异常均为 false)
 * @property remoteLastModify 云端备份文件的真实修改时间, 0 表示云端没有该文件
 *           (未配置、上传失败且半成品已清理、或回查失败), 调用方应回退到本机时间
 * @property remoteSize 云端备份文件大小
 */
data class BackUpResult(
    val uploaded: Boolean = false,
    val remoteLastModify: Long = 0,
    val remoteSize: Long = 0
)