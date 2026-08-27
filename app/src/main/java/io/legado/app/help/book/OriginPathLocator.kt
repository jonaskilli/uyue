package io.legado.app.help.book

import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File

/**
 * 本地书"原文件物理位置"定位器。
 *
 * 背景：以 content:// 令牌登记的本地书（SAF 目录授权导入 / 外部"用阅读打开"），
 * 其授权随卸载清空——重装备份恢复后令牌失效无法读文件。
 * 本类在导入时把原文件的物理路径候选（含各处同名同大小副本）记入 Book.originPaths，
 * 读取失败时无需任何 SAF 授权（仅依赖全部文件访问权限）即可按候选自动找回。
 *
 * 候选来源：
 * 1) SAF 令牌明文直译（com.android.externalstorage.documents 的 document id 即 存储根:相对路径）
 * 2) MediaStore 全盘索引按 文件名(+大小) 查询，毫秒级、零遍历
 *
 * 候选上限 [MAX_CANDIDATES]，超出时淘汰最旧。
 */
object OriginPathLocator {

    private const val MAX_CANDIDATES = 20

    /** 系统本地存储提供器的 authority——document id 为明文 "存储根:相对路径"，可直译物理路径 */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /** 内部实现前缀 → 物理根目录 primary=内置存储; 其他key为SD卡卷UUID(启动时探测) */
    private val storageRoots: Map<String, String> by lazy {
        buildMap {
            put("primary", "/storage/emulated/0")
            // 探测所有挂载的二级存储(SD卡/USB)的 UUID→路径映射
            runCatching {
                appCtx.getExternalFilesDirs(null).forEach { dir ->
                    // /storage/XXXX-XXXX/Android/data/<pkg>/files → 提取 /storage/XXXX-XXXX
                    val m = Regex("^(/storage/[0-9A-Fa-f-]{4,})/").find(dir.absolutePath) ?: return@forEach
                    val uuid = m.groupValues[1].substringAfterLast('/')
                    if (uuid != "primary" && !containsKey(uuid)) {
                        put(uuid, m.groupValues[1])
                    }
                }
            }
        }
    }

    /**
     * 候选条目: p=物理路径 s=采集时的文件字节数(0未知) m=修改时间毫秒(0未知)
     */
    data class OriginCandidate(val p: String, val s: Long, val m: Long)

    /* ---------------- 采集 ---------------- */

    /**
     * 导入/更新本地书时调用：采集该文件的物理路径候选并写入 book.originPaths。
     * file:// 书本路径即 bookUrl 本身(重装不失效)，仍统一记录保持口径一致。
     */
    fun capture(book: Book, importUri: Uri, fileName: String, size: Long, lastModified: Long) {
        runCatching {
            val candidates = LinkedHashSet<String>()
            if (importUri.isContentScheme()) {
                tokenToPhysicalPath(importUri)?.let { candidates.add(it) }
                candidates.addAll(findCandidates(fileName, size))
            } else {
                importUri.path?.let { candidates.add(it) }
            }
            if (candidates.isEmpty()) return
            val list = candidates.take(MAX_CANDIDATES).map { path ->
                OriginCandidate(
                    p = path,
                    // 无法读到属性时填 0，验证层会降级为仅存在性检查
                    s = size,
                    m = lastModified
                )
            }
            book.originPaths = GSON.toJson(list)
        }
    }

    /**
     * 补录既有书籍的候选(老备份/未采集过的书)。搜索成功则写回 originPaths 并入库。
     */
    fun lazyCaptureIfMissing(book: Book): List<String> {
        val existing = parseCandidates(book)
        if (existing.isNotEmpty()) return existing.map { it.p }
        return runCatching {
            val found = findCandidates(book.originName.ifBlank { "${book.name}.txt" }, null)
            if (found.isEmpty()) {
                emptyList()
            } else {
                val list = found.take(MAX_CANDIDATES).map {
                    OriginCandidate(p = it, s = 0L, m = 0L)
                }
                book.originPaths = GSON.toJson(list)
                appDb.bookDao.update(book)
                found
            }
        }.getOrDefault(emptyList())
    }

    /* ---------------- 自愈恢复 ---------------- */

    /**
     * 书籍文件读取失败时调用。依次尝试找回：
     * 第0步 candidate 列表逐一存在性校验；
     * 第1步 老书兜底: 若 bookUrl 是可明文直译的系统令牌→换算物理路径尝试;
     * 第2步 老书兜底: 仅凭 originName 全盘索引搜索。
     * 任一命中则:
     *  - 验证可用后把 book.bookUrl 升级为物理路径(file 形式, 重装永不失效)并入库
     *  - 返回对应 FileDoc; 全部失败返回 null(交由上层原有手动流程)
     */
    fun recover(book: Book): FileDoc? {
        val tried = LinkedHashSet<String>()
        val candidates = parseCandidates(book)
        candidates.forEach { tried.add(it.p) }

        // 第1步: 失效令牌本身若为系统本地存储文档, 直接明文直译成物理路径
        runCatching {
            if (book.bookUrl.isContentScheme()) {
                tokenToPhysicalPath(book.bookUrl.toUri())?.let { tried.add(it) }
            }
        }.onFailure {
            AppLog.put("OriginPathLocator.recover第1层令牌直译异常: ${it.message}\n")
        }

        // 第2步: 全盘索引兜底——无条件执行且带权限引导(不能因为直译出了候选就跳过:
        // 直译的路径可能已失效, 而文件可能被移动到了别处)。
        // 新书候选含精确大小→按 名字+大小 精确查;
        // 老书(旧版导入,无size数据)仅按名字(originName=原始文件名含扩展名)查。
        runCatching {
            val knownSize = candidates.firstOrNull()?.s?.takeIf { it > 0L }
            val searchName = book.originName.ifBlank { "${book.name}.txt" }
            val before = tried.size
            findCandidates(searchName, knownSize, silent = false).forEach { found ->
                tried.add(found)
            }
            // 搜索结果持久化: 即使本次全部验证失败也为下次留存线索
            if (tried.size > before) {
                persistFound(book, tried)
            }
        }.onFailure {
            AppLog.put("OriginPathLocator.recover第2层全盘搜索异常: ${it.message}\n")
        }

        val expectedSize = candidates.firstOrNull()?.s ?: 0L
        for (path in tried) {
            val file = File(path)
            if (!file.exists() || !file.canRead() || file.length() <= 0L) continue
            // 记录过精确大小的候选做一致性校验, 不匹配的副本放后面不优先采用
            if (expectedSize > 0 && file.length() != expectedSize) continue
            return applyRecoveredPath(book, file)
        }
        // 大小不一致但存在的候选举最后考虑(文件可能被重新生成过)
        for (path in tried) {
            val file = File(path)
            if (file.exists() && file.canRead() && file.length() > 0L) {
                return applyRecoveredPath(book, file)
            }
        }
        return null
    }

    /**
     * 把自愈过程中发现的新候选写回 book.originPaths(截断到上限),
     * 让"本次没救成"的书在下次重试时拥有更多线索。
     */
    private fun persistFound(book: Book, paths: LinkedHashSet<String>) {
        if (paths.isEmpty()) return
        runCatching {
            val list = paths.toList().take(MAX_CANDIDATES).map { p ->
                val f = File(p)
                OriginCandidate(
                    p = p,
                    s = if (f.exists()) f.length() else 0L,
                    m = if (f.exists()) f.lastModified() else 0L
                )
            }
            book.originPaths = GSON.toJson(list)
            appDb.bookDao.update(book)
        }
    }

    /**
     * 命中后的落地动作(刻意不修改 book.bookUrl 主键!):
     * - 仅把命中路径置顶到 originPaths 持久化, 下次打开时该候选首个被验证, 热路径毫秒级;
     * - 不变更 bookUrl 是因为 books/chapters/bookmark 等多张表以 bookUrl 为主键/外键,
     *   在目录刷新中途换键会触发外键约束失败与孤儿数据(踩过的坑);
     * - 进程内即时加速由调用方 getLocalUri 写 localUriCache 完成。
     */
    private fun applyRecoveredPath(book: Book, file: File): FileDoc {
        val fileDoc = FileDoc.fromFile(file)
        AppLog.put("本地书《${book.name}》已自动重新定位到 ${file.absolutePath}\n")
        runCatching {
            val candidates = parseCandidates(book)
            val hit = file.absolutePath
            val reordered = buildList {
                add(OriginCandidate(p = hit, s = file.length(), m = file.lastModified()))
                candidates.forEach { if (it.p != hit) add(it) }
            }.take(MAX_CANDIDATES)
            book.originPaths = GSON.toJson(reordered)
            appDb.bookDao.update(book)
        }
        return fileDoc
    }

    /* ---------------- 基础能力 ---------------- */

    /**
     * MediaStore 全盘索引查询: DISPLAY_NAME 精确匹配(+可选 SIZE),
     * 返回真实物理路径列表(_data), 按最近修改排序, 已过滤应用私有目录与无效项。
     * @param silent true=无"所有文件访问"权限时静默返回空; false=弹Toast引导开启
     */
    fun findCandidates(displayName: String, size: Long?, silent: Boolean = true): List<String> {
        if (displayName.isBlank()) return emptyList()
        // _data 列的可读性依赖全部文件访问权限(MANAGE_EXTERNAL_STORAGE);
        // 无权限时列表大多为空。注意: 该权限卸载重装后必然回到关闭状态!
        if (!Environment.isExternalStorageManager()) {
            if (!silent) {
                AppLog.put("OriginPathLocator: 未授予[所有文件访问]权限, 全盘索引搜索跳过\n")
                appCtx.toastOnUi("请先在系统设置中给阅读开启[所有文件访问]权限, 否则无法自动定位本地书")
            }
            return emptyList()
        }
        val result = runCatching {
            val collection = MediaStore.Files.getContentUri("external")
            val selection: String
            val args: Array<String>
            if (size != null && size > 0L) {
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.SIZE}=?"
                args = arrayOf(displayName, size.toString())
            } else {
                selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
                args = arrayOf(displayName)
            }
            appCtx.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DATA),
                selection,
                args,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                buildList {
                    while (cursor.moveToNext()) {
                        val path = cursor.getString(dataIdx) ?: continue
                        if (isPrivateDir(path)) continue
                        add(path)
                    }
                }.take(MAX_CANDIDATES)
            } ?: emptyList()
        }.onFailure {
            AppLog.put("OriginPathLocator.findCandidates失败: ${it.message}\n")
        }.getOrDefault(emptyList())
        val sizeInfo = size?.let { "+${it}字节" } ?: ""
        AppLog.put("OriginPathLocator: 按[$displayName$sizeInfo]搜到${result.size}个候选\n")
        return result
    }

    /**
     * SAF 令牌明文直译: com.android.externalstorage.documents 的 documentId
     * 形如 "primary:Books/A/x.epub", 即 "存储根别名:相对路径"。
     * 可直译的返回绝对物理路径, 其他 provider 返回 null。
     */
    fun tokenToPhysicalPath(uri: Uri): String? {
        return runCatching {
            if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return@runCatching null
            var docId: String? = runCatching {
                android.provider.DocumentsContract.getDocumentId(uri)
            }.getOrNull()
            // tree-only uri: 用树id做目录直译对单文件无意义, 但可能就是文件本身(部分管理器误用)
            if (docId == null) {
                docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            }
            val id = docId ?: return@runCatching null
            val sep = id.indexOf(':')
            if (sep < 1) return@runCatching null
            val rootKey = id.substring(0, sep).lowercase()
            val relPath = id.substring(sep + 1)
            if (relPath.isBlank()) return@runCatching null
            val root = storageRoots[rootKey] ?: return@runCatching null
            "$root/${relPath.replace(':', '/')}"
        }.getOrNull()
    }

    private fun isPrivateDir(path: String): Boolean {
        return path.contains("/Android/data/io.legado.app/")
            || path.contains("/Android/data/${appCtx.packageName}/")
    }

    private fun parseCandidates(book: Book): List<OriginCandidate> {
        return runCatching {
            GSON.fromJsonArray<OriginCandidate>(book.originPaths)
                .getOrDefault(emptyList())
                .filter { it.p.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}
