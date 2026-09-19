package io.legado.app.ui.timetech.player

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * 本地视频条目。
 *
 * [uri] 是播放用的唯一标识：SAF 目录下为 content:// 文档 URI，普通目录下为 file:// URI，
 * 两种来源统一成对 ExoPlayer 可直接消费的 Uri。
 */
data class LocalVideo(
    val uri: Uri,
    val name: String,
    /** 展示用的路径描述（可能是文档树相对路径，不是可访问的文件绝对路径） */
    val path: String,
    val size: Long,
    val dateModified: Long,
)

/**
 * 视频元信息（首帧缩略图 + 时长）。读取代价较高，按 uri 缓存，避免列表滚动时反复解码。
 */
data class VideoMeta(val duration: Long, val thumbnail: Bitmap?)

/**
 * 本地视频扫描器。
 *
 * 支持两种目录来源：
 * 1. 系统文件选择器授权返回的文档树 URI（content://…/tree/…）——优先用 DocumentsContract
 *    批量查询遍历，provider 不支持时退回 DocumentFile 逐项读取；
 * 2. 普通绝对路径——用 File 遍历（应用持有 MANAGE_EXTERNAL_STORAGE / 旧版存储权限）。
 */
object LocalVideoScanner {

    /** 常见视频后缀（小写） */
    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v",
        "3gp", "3gpp", "ts", "m2ts", "mts", "mpg", "mpeg", "rmvb",
        "rm", "asf", "vob", "f4v", "divx", "ogv"
    )

    /** 递归扫描时的最大目录层级，避免误选根目录后长时间遍历 */
    private const val MAX_DEPTH = 6

    fun isVideoFileName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return VIDEO_EXTENSIONS.contains(name.substring(dot + 1).lowercase(Locale.getDefault()))
    }

    /** 目录来源类型 */
    fun isTreeUri(root: String): Boolean = root.startsWith("content://")

    /** 目录展示名 */
    fun folderDisplayName(context: Context, root: String): String {
        if (root.isBlank()) return "未选择"
        if (isTreeUri(root)) {
            return runCatching {
                DocumentFile.fromTreeUri(context, Uri.parse(root))?.name
            }.getOrNull() ?: "已授权目录"
        }
        return root.substringAfterLast('/').ifBlank { root }
    }

    /**
     * 扫描目录下的视频。返回按名称自然排序的列表（数字按数值比较，如 2.mp4 排在 10.mp4 前）。
     */
    suspend fun scan(
        context: Context,
        root: String,
        includeSubDir: Boolean,
    ): List<LocalVideo> = withContext(Dispatchers.IO) {
        if (root.isBlank()) return@withContext emptyList<LocalVideo>()
        val list = if (isTreeUri(root)) {
            scanTree(context, Uri.parse(root), includeSubDir)
        } else {
            val dir = File(root)
            if (dir.isDirectory) collectFromFile(dir, includeSubDir, 0) else emptyList()
        }
        list.sortedWith(compareBy(NaturalOrderComparator) { it.name })
    }

    /**
     * 遍历 SAF 文档树。
     *
     * 先走 [collectTreeFast] 的批量查询路径，个别第三方 DocumentsProvider 不支持时
     * 再退回 [collectFromDocument] 逐项读取，保证跟改造前的行为一致。
     */
    private suspend fun scanTree(
        context: Context,
        rootUri: Uri,
        includeSubDir: Boolean,
    ): List<LocalVideo> {
        val tree = runCatching { DocumentFile.fromTreeUri(context, rootUri) }.getOrNull()
        val docId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(rootUri) }.getOrNull()
        if (docId != null) {
            val fast = runCatching {
                collectTreeFast(
                    resolver = context.contentResolver,
                    treeUri = rootUri,
                    docId = docId,
                    parentPath = tree?.name.orEmpty(),
                    includeSubDir = includeSubDir,
                    depth = 0,
                )
            }.getOrNull()
            if (fast != null) return fast
        }
        return tree?.let { collectFromDocument(it, it.name.orEmpty(), includeSubDir, 0) }.orEmpty()
    }

    /** 批量查询时一次取回的列，size 与 last_modified 一并带回，省掉后续逐项查询 */
    private val TREE_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    /**
     * 用 [DocumentsContract] 批量查询遍历文档树。
     *
     * 之前靠 DocumentFile 逐项读取：每个文件的 size 和 last_modified 要各发一次跨进程查询，
     * 1000 个视频就是两千次 IPC，中低端机上要等好几秒。批量查询把 display_name、mime_type、
     * size、last_modified 一次带回来，每个目录只发一次查询，IPC 次数降到目录数量级别。
     *
     * 循环内同样响应取消，切换目录后能立刻停手。
     *
     * @return null 表示该 provider 不支持这种查询，调用方应退回 [collectFromDocument]
     */
    private suspend fun collectTreeFast(
        resolver: ContentResolver,
        treeUri: Uri,
        docId: String,
        parentPath: String,
        includeSubDir: Boolean,
        depth: Int,
    ): List<LocalVideo>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val videos = ArrayList<LocalVideo>()
        // 子目录先攒着，等本层遍历完再递归：cursor 开着期间不宜再发新查询
        val subDirs = ArrayList<Pair<String, String>>()
        val ok = runCatching {
            val cursor = resolver.query(childrenUri, TREE_PROJECTION, null, null, null)
                ?: return@runCatching false
            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val timeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                // 少了 id/name/mime 就没法继续，判为不支持，交给上层退回旧路径
                if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) return@runCatching false
                while (c.moveToNext()) {
                    coroutineContext.ensureActive()
                    val childId = c.getString(idIdx) ?: continue
                    val name = c.getString(nameIdx) ?: continue
                    val mime = c.getString(mimeIdx)
                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        if (includeSubDir && depth < MAX_DEPTH) {
                            subDirs.add(childId to "$parentPath/$name")
                        }
                        continue
                    }
                    // 后缀判断保留，兼容 mime 填成 application/octet-stream 的 provider
                    val isVideo = isVideoFileName(name) ||
                        mime?.startsWith("video/", ignoreCase = true) == true
                    if (!isVideo) continue
                    videos.add(
                        LocalVideo(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                            name = name,
                            path = "$parentPath/$name",
                            // 少数 provider 不提供这两列，降级为 0 而不是放弃整次查询
                            size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L,
                            dateModified = if (timeIdx >= 0) c.getLong(timeIdx) else 0L,
                        )
                    )
                }
                true
            }
        }.getOrDefault(false)
        if (!ok) return null

        for ((childId, childPath) in subDirs) {
            coroutineContext.ensureActive()
            // 单个子目录读不出来（权限、provider 报错）不应让整次扫描失败，跳过即可
            val child = collectTreeFast(resolver, treeUri, childId, childPath, true, depth + 1)
                ?: continue
            videos.addAll(child)
        }
        return videos
    }

    /**
     * 遍历文档树目录（逐项读取）。
     *
     * 视频多时这一步会持续很久（每个子项都要跨进程查一次 ContentResolver），
     * 所以循环里定期检查协程是否已取消：切换列表或目录后能立刻停下，
     * 不再把上一次已经没人等的结果跑完带回来。
     *
     * 仅作为 [collectTreeFast] 不可用时的兜底路径。
     */
    private suspend fun collectFromDocument(
        dir: DocumentFile,
        parentPath: String,
        includeSubDir: Boolean,
        depth: Int,
    ): List<LocalVideo> {
        val result = ArrayList<LocalVideo>()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return result
        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                if (includeSubDir && depth < MAX_DEPTH) {
                    val childPath = child.name?.let { "$parentPath/$it" } ?: parentPath
                    result.addAll(collectFromDocument(child, childPath, true, depth + 1))
                }
                continue
            }
            val name = child.name ?: continue
            val isVideo = isVideoFileName(name) ||
                child.type?.startsWith("video/", ignoreCase = true) == true
            if (!isVideo) continue
            result.add(
                LocalVideo(
                    uri = child.uri,
                    name = name,
                    path = "$parentPath/$name",
                    size = runCatching { child.length() }.getOrDefault(0L),
                    dateModified = child.lastModified(),
                )
            )
        }
        return result
    }

    /** 遍历普通目录，同样在循环里响应取消，见 [collectFromDocument] */
    private suspend fun collectFromFile(
        dir: File,
        includeSubDir: Boolean,
        depth: Int,
    ): List<LocalVideo> {
        val result = ArrayList<LocalVideo>()
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return result
        for (child in children) {
            coroutineContext.ensureActive()
            if (child.isDirectory) {
                if (includeSubDir && depth < MAX_DEPTH) {
                    result.addAll(collectFromFile(child, true, depth + 1))
                }
                continue
            }
            if (!isVideoFileName(child.name)) continue
            result.add(
                LocalVideo(
                    uri = Uri.fromFile(child),
                    name = child.name,
                    path = child.absolutePath,
                    size = child.length(),
                    dateModified = child.lastModified(),
                )
            )
        }
        return result
    }

    /** 常用视频目录快捷入口，供未授权时快速定位 */
    fun quickDirs(): List<Pair<String, String>> {
        val root = runCatching {
            @Suppress("DEPRECATION")
            Environment.getExternalStorageDirectory().absolutePath
        }.getOrNull() ?: return emptyList()
        return listOf(
            "录屏" to "$root/DCIM/Screen recordings",
            "微信" to "$root/Android/data/com.tencent.mm/MicroMsg",
        ).filter { File(it.second).isDirectory }
    }
}

/**
 * 视频元信息缓存：MediaMetadataRetriever 解码一帧需要几十毫秒，
 * 列表快速来回滚动时不重复解码，也不会因为 Bitmap 无上限累积把内存吃光。
 */
object VideoMetaCache {

    private const val MAX_ENTRIES = 120

    private val lock = Mutex()
    private val map = object : LinkedHashMap<String, VideoMeta>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoMeta>?): Boolean =
            size > MAX_ENTRIES
    }

    suspend fun get(context: Context, video: LocalVideo): VideoMeta {
        val key = video.uri.toString()
        lock.withLock { map[key] }?.let { return it }
        val meta = withContext(Dispatchers.IO) { readMeta(context, video) }
        lock.withLock { map[key] = meta }
        return meta
    }

    private fun readMeta(context: Context, video: LocalVideo): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, video.uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            VideoMeta(duration, frame)
        } catch (e: Exception) {
            AppLog.put("读取视频信息失败：${video.name}", e)
            VideoMeta(0L, null)
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * 自然排序：把名称中的连续数字按数值比较，避免出现 1、10、2 这种字典序。
 */
internal object NaturalOrderComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var na = 0L
                var nb = 0L
                while (i < a.length && a[i].isDigit()) {
                    na = na * 10 + (a[i] - '0')
                    i++
                }
                while (j < b.length && b[j].isDigit()) {
                    nb = nb * 10 + (b[j] - '0')
                    j++
                }
                if (na != nb) return na.compareTo(nb)
            } else {
                val diff = ca.compareTo(cb)
                if (diff != 0) return diff
                i++
                j++
            }
        }
        return a.length.compareTo(b.length)
    }
}
