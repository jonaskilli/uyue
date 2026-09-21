package io.legado.app.help.video

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.C
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 视频选集下载器。
 *
 * - mp4 等单文件直链：流式保存（走 [VideoCache]，与播放共用同一份缓存）
 * - m3u8：逐段下载（支持 AES-128 加密与 EXT-X-MAP 初始化段），按顺序拼接成完整文件
 * - 保存位置：/storage/emulated/0/Download/保存视频/<视频名>/，后缀统一 .mp4
 *
 * 网络读取一律经由 [VideoCache]：播放缓冲过的分片直接命中，不再重复下载，
 * 没看过的部分才真正联网，联网结果同样进缓存供下次播放复用。
 *
 * 断点续传：m3u8 下载时用 .idx 文件记录播放清单指纹与每段字节数，
 * 中断后重新下载会先校验指纹、截掉写了一半的尾巴，从缺失的片段继续，补齐后合并为 .mp4。
 *
 * 两种入口共用核心 [downloadCore]：
 * - [enqueue]：手动多选下载，队列串行
 * - [watchStart]：边看边存——正在播的选集进队列，播完/切集后由 worker
 *   检测缺失片段补齐再合并，保证最终保存的是完整视频
 */
object VideoDownloader {

    data class Task(
        val book: Book,
        val source: BookSource,
        val index: Int,
        val chapter: BookChapter,
    )

    /**
     * 下载条目状态。
     * [COLLECTING]：跟随播放收集中——只搬缓存里已有的分片，进度会随播放推进，
     * 剩余缺口要等切集/退出后由收尾补齐。它不是"卡住"，也不需要用户干预。
     */
    enum class DlStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, COLLECTING }

    /**
     * 单条下载的实时状态（供界面实时展示进度条/暂停续传）。
     * key 与 [watchKey] 一致：手动下载与边看边存同一选集共用一条状态。
     */
    data class TaskState(
        val key: String,
        val task: Task,
        val title: String,
        val progress: Int, // 0-99 下载中，100 已完成
        val status: DlStatus,
        /** 保存目录名（Download/保存视频/<dirName>）：界面按它把条目归到各视频名下 */
        val dirName: String = "",
    )

    /** 暂停用异常：从下载循环安全退出，已写入部分保留供续传 */
    private class PausedException : Exception()

    /** 同一集正在被别的任务写入（收尾补齐中）：这一轮收集直接跳过，不算失败 */
    private class BusyException : Exception()

    /**
     * @param collectOnly true = **只收集缓存里已有的分片**（播放缓冲留下的），
     * 不产生任何网络流量；缺的部分留给收尾时全速补齐。边播边存阶段用它，
     * 播放与下载因此不再抢带宽——这是"开着边播边存会卡"的根治办法。
     */
    private data class WatchItem(
        val task: Task,
        val knownUrl: String?,
        val collectOnly: Boolean,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchChannel = Channel<WatchItem>(Channel.UNLIMITED)
    /**
     * 收尾队列：切集/播完时对上一集的补齐请求走这里，**优先于** [watchChannel]。
     * 保证"播完的那集先补齐落盘"，而不是被新入队的当前集压在后面。
     */
    private val finalizeChannel = Channel<WatchItem>(Channel.UNLIMITED)
    /** 手动下载队列：串行执行，暂停的任务跳过、留在队列里 */
    private val manualQueue = ArrayDeque<Task>()
    private var workerBusy = false
    /** 暂停中的下载 key（watchKey） */
    private val pausedKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 播放页是否还在播。
     * 播放期间的后台下载一律让路（压低并发并节流），否则它会和正在播的这一路抢带宽。
     */
    @Volatile
    private var playbackActive = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
    }

    /** 播放中后台下载的分片间隔：给播放留出带宽余量 */
    private const val THROTTLE_SLEEP_MS = 200L

    private val statesMap = LinkedHashMap<String, TaskState>()
    private val _states = MutableStateFlow<List<TaskState>>(emptyList())

    /** 实时下载状态列表（界面收集展示，退出界面后仍在后台更新） */
    val states: StateFlow<List<TaskState>> get() = _states

    private fun publishStates() {
        synchronized(statesMap) { _states.value = statesMap.values.toList() }
        scheduleSave()
    }

    /**
     * 下载完成监听：mp4 落盘后回调。
     *
     * 播放会话据此把该集的播放地址换成**本地文件**——否则会话里还是那条 http 直链，
     * 明明已经下载完了，下次播放仍要联网重新缓冲。
     */
    private val completionListeners = java.util.concurrent.CopyOnWriteArrayList<(Task) -> Unit>()

    fun addCompletionListener(listener: (Task) -> Unit) {
        completionListeners.add(listener)
    }

    private fun notifyCompleted(task: Task) {
        completionListeners.forEach { runCatching { it(task) } }
    }

    private fun ensureState(task: Task) {
        val key = watchKey(task)
        synchronized(statesMap) {
            if (!statesMap.containsKey(key)) {
                statesMap[key] = TaskState(
                    key, task, titleOf(task), 0, DlStatus.QUEUED, dirOf(task).name
                )
            }
        }
        publishStates()
    }

    private fun updateState(key: String, transform: (TaskState) -> TaskState) {
        synchronized(statesMap) {
            statesMap[key]?.let { statesMap[key] = transform(it) }
        }
        publishStates()
    }

    private fun removeState(key: String) {
        synchronized(statesMap) { statesMap.remove(key) }
        publishStates()
    }

    // ---------- 下载记录持久化 ----------
    // 任务队列原本只在内存里：退出 APP 后进程一死，已下载的与下了一半的记录全没了，
    // 而磁盘上的 .mp4 / .part 还躺在那里没人认领。这里落一份索引，
    // 下次启动按索引 + 磁盘实际文件恢复，中断的还能接着下。
    private val indexFile: File by lazy { File(appCtx.filesDir, "video_download/tasks.json") }
    private val saveLock = Any()
    private var saveScheduled = false

    @Volatile
    private var restored = false

    private val restoreLock = Any()

    /** 恢复上次的下载记录（幂等，只跑一次）。App 启动与进入播放页都会调用。 */
    fun restoreIfNeeded() {
        synchronized(restoreLock) {
            if (restored) return
            restored = true
        }
        scope.launch { runCatching { restore() } }
    }

    private fun restore() {
        val text = runCatching { indexFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val book = appDb.bookDao.getBook(o.optString("bookUrl")) ?: continue
            val source = appDb.bookSourceDao.getBookSource(o.optString("sourceUrl")) ?: continue
            val chapterUrl = o.optString("chapterUrl")
            val index = o.optInt("index", 0)
            // 章节优先从库里取（信息最全），库里没有就按索引里的信息还原一个
            val chapter = appDb.bookChapterDao.getChapterList(book.bookUrl)
                .firstOrNull { it.url == chapterUrl }
                ?: appDb.bookChapterDao.getChapter(book.bookUrl, index)
                ?: BookChapter(
                    url = chapterUrl,
                    title = o.optString("chapterTitle"),
                    bookUrl = book.bookUrl,
                    index = index,
                )
            val task = Task(book, source, index, chapter)
            val key = watchKey(task)
            val title = titleOf(task)
            val dir = dirOf(task)
            val savedProgress = o.optInt("progress", 0).coerceIn(0, 99)
            // 以磁盘实际文件为准：mp4 成品在 = 已完成；分片文件夹有分片也算已缓存（可播）；
            // 只有 .part = 中断，可续传
            val state = when {
                File(dir, "$title.mp4").exists() ->
                    DlStatus.COMPLETED to 100
                File(dir, title).isDirectory &&
                    File(dir, title).listFiles().orEmpty()
                        .any { it.name.startsWith("seg_") } ->
                    DlStatus.COMPLETED to 100
                File(dir, "$title.mp4.part").exists() ->
                    DlStatus.PAUSED to savedProgress
                else -> DlStatus.FAILED to savedProgress
            }
            synchronized(statesMap) {
                statesMap[key] = TaskState(key, task, title, state.second, state.first, dir.name)
            }
        }
        publishStates()
    }

    /** 状态变化后延迟落盘（进度更新很频繁，节流一下） */
    private fun scheduleSave() {
        synchronized(saveLock) {
            if (saveScheduled) return
            saveScheduled = true
        }
        scope.launch {
            delay(800)
            synchronized(saveLock) { saveScheduled = false }
            runCatching { saveNow() }
        }
    }

    private fun saveNow() {
        val snapshot = synchronized(statesMap) { statesMap.values.toList() }
        val arr = org.json.JSONArray()
        snapshot.forEach { s ->
            arr.put(
                org.json.JSONObject().apply {
                    put("bookUrl", s.task.book.bookUrl)
                    put("sourceUrl", s.task.source.bookSourceUrl)
                    put("index", s.task.index)
                    put("chapterUrl", s.task.chapter.url)
                    put("chapterTitle", s.task.chapter.title)
                    put("progress", s.progress)
                    put("status", s.status.name)
                }
            )
        }
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(arr.toString())
    }
    /** 同一来源的请求头，避免每次分段请求都重新构建 */
    private var cachedHeaders: Pair<String, Map<String, String>>? = null
    /** 正在写入的临时文件路径（下载队列与边看边存互斥同一选集） */
    private val activePaths = HashSet<String>()
    /** 已入队待补齐的边看边存任务（去重） */
    private val watchQueued = HashSet<String>()
    private val prefs by lazy {
        appCtx.getSharedPreferences("timetech_video_player", Context.MODE_PRIVATE)
    }

    init {
        // 边看边存 worker：串行补齐缺失片段，完成后合并为 .mp4
        // 每一轮先取收尾队列（上一集），再取普通队列（当前集）：播完的那集先落盘
        scope.launch {
            while (true) {
                val w = finalizeChannel.tryReceive().getOrNull() ?: watchChannel.receive()
                runCatching { runWatch(w) }.onFailure {
                    when (it) {
                        is kotlinx.coroutines.CancellationException -> {}
                        is PausedException -> Unit // 状态已在 runWatch 里置为 PAUSED
                        else -> AppLog.put("边看边存补齐失败：${w.task.chapter.title}", it)
                    }
                }
                synchronized(watchQueued) { watchQueued.remove(watchKey(w.task)) }
            }
        }
    }

    /** 执行一条边看边存任务（收集/补齐缺失片段 + 合并落盘），带实时进度与状态回报 */
    private suspend fun runWatch(w: WatchItem) {
        val key = watchKey(w.task)
        updateState(key) { it.copy(status = DlStatus.RUNNING) }
        try {
            val file = downloadCore(
                w.task, dirOf(w.task), titleOf(w.task), w.knownUrl, key,
                collectOnly = w.collectOnly,
            ) { p ->
                updateState(key) { it.copy(progress = p) }
            }
            if (file != null) {
                updateState(key) { it.copy(progress = 100, status = DlStatus.COMPLETED) }
            } else if (w.collectOnly) {
                // 跟随播放收集了一部分，剩下的等收尾补齐：显示为"跟随播放 x%"，
                // 不能回到"等待中"——那会让人以为它卡住没干活
                updateState(key) { it.copy(status = DlStatus.COLLECTING) }
            } else {
                updateState(key) { it.copy(status = DlStatus.QUEUED) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: BusyException) {
            // 同一集正在补齐：静默跳过这一轮收集
            return
        } catch (e: PausedException) {
            updateState(key) { it.copy(status = DlStatus.PAUSED) }
            throw e
        } catch (e: Exception) {
            updateState(key) { it.copy(status = DlStatus.FAILED) }
            throw e
        }
    }

    /**
     * 手动下载：登记状态后入队，串行执行。
     * 已有完整 mp4 的直接标记完成，不再重复下载。
     */
    fun enqueue(tasks: List<Task>) {
        tasks.forEach { task ->
            val key = watchKey(task)
            if (File(dirOf(task), "${titleOf(task)}.mp4").exists()) {
                synchronized(statesMap) {
                    statesMap[key] = TaskState(
                        key, task, titleOf(task), 100, DlStatus.COMPLETED, dirOf(task).name
                    )
                }
                notifyCompleted(task)
                return@forEach
            }
            ensureState(task)
            pausedKeys.remove(key)
            synchronized(manualQueue) { manualQueue.addLast(task) }
        }
        publishStates()
        pump()
    }

    /** 串行 worker：取下一条未暂停的任务执行 */
    private fun pump() {
        val next: Task
        synchronized(manualQueue) {
            if (workerBusy) return
            val idx = manualQueue.indexOfFirst { watchKey(it) !in pausedKeys }
            if (idx < 0) return
            next = manualQueue.removeAt(idx)
            workerBusy = true
        }
        updateState(watchKey(next)) { it.copy(status = DlStatus.RUNNING) }
        scope.launch {
            runCatching { download(next) }
            synchronized(manualQueue) { workerBusy = false }
            pump()
        }
    }

    /** 暂停：循环在下一个检查点安全中止，已写入部分保留用于续传 */
    fun pause(key: String) {
        pausedKeys.add(key)
        updateState(key) { s ->
            if (s.status == DlStatus.QUEUED || s.status == DlStatus.RUNNING) {
                s.copy(status = DlStatus.PAUSED)
            } else s
        }
    }

    /** 继续：清除暂停标记，未完成的重新入队续传 */
    fun resume(key: String) {
        val task = synchronized(statesMap) { statesMap[key]?.task } ?: return
        pausedKeys.remove(key)
        updateState(key) { s ->
            if (s.status == DlStatus.PAUSED) s.copy(status = DlStatus.QUEUED) else s
        }
        synchronized(manualQueue) {
            if (manualQueue.none { watchKey(it) == key }) manualQueue.addLast(task)
        }
        pump()
    }

    /**
     * 全部开始：把所有未完成的条目（暂停/失败/跟随播放中/等待中）清掉暂停标记并重新入队。
     * 已完成的跳过；正在下载的保持不动（重复入队会撞同一份 .part）。
     *
     * @return 新入队的条数
     */
    fun resumeAll(dirName: String? = null): Int {
        val tasks = synchronized(statesMap) {
            statesMap.values
                .filter { dirName == null || it.dirName == dirName }
                .filter { it.status != DlStatus.COMPLETED && it.status != DlStatus.RUNNING }
                .map { it.task }
        }
        tasks.forEach { task ->
            val key = watchKey(task)
            pausedKeys.remove(key)
            updateState(key) { s ->
                if (s.status == DlStatus.COMPLETED || s.status == DlStatus.RUNNING) s
                else s.copy(status = DlStatus.QUEUED)
            }
            synchronized(manualQueue) {
                if (manualQueue.none { watchKey(it) == key }) manualQueue.addLast(task)
            }
        }
        if (tasks.isNotEmpty()) pump()
        return tasks.size
    }

    /**
     * 全部停止：暂停所有进行中的条目（等待中/下载中/跟随播放中）。
     * 已写进 .part 的部分保留，之后可以续传；已完成的记录不受影响。
     *
     * @return 被暂停的条数
     */
    fun pauseAll(dirName: String? = null): Int {
        val keys = synchronized(statesMap) {
            statesMap.values.filter { dirName == null || it.dirName == dirName }
                .filter {
                    it.status == DlStatus.QUEUED ||
                        it.status == DlStatus.RUNNING ||
                        it.status == DlStatus.COLLECTING
                }.map { it.key }
        }
        keys.forEach { pause(it) }
        return keys.size
    }

    /** 删除已缓存（分片文件夹 + mp4 成品 + .part/.idx 临时文件），条目保留，再次下载时从头开始 */
    fun deleteCache(state: TaskState) {
        val dir = dirOf(state.task)
        File(dir, state.title).deleteRecursively() // 分片缓存文件夹
        File(dir, "${state.title}.mp4").delete()
        File(dir, "${state.title}.mp4.part").delete()
        File(dir, "${state.title}.mp4.idx").delete()
        updateState(state.key) { it.copy(progress = 0) }
    }

    /** 删除已生成的 mp4 成品，并从下载列表移除该条目（分片文件夹缓存保留） */
    fun deleteMp4(state: TaskState) {
        File(dirOf(state.task), "${state.title}.mp4").delete()
        removeState(state.key)
    }

    // ---------- 已缓存视频（按视频分组管理） ----------

    /** 一部视频的下载汇总 */
    data class VideoGroup(
        /** 有下载记录时是 bookUrl，只有目录没有记录时是 "dir:<目录名>" */
        val key: String,
        val name: String,
        /** 保存目录名（Download/保存视频/<dirName>） */
        val dirName: String,
        val episodeCount: Int,
        val completedCount: Int,
        val sizeBytes: Long,
    )

    private val _videoGroups = MutableStateFlow<List<VideoGroup>>(emptyList())
    val videoGroups: StateFlow<List<VideoGroup>> get() = _videoGroups

    /** 保存目录名：与 [dirOf] 同一套清洗规则 */
    fun dirNameOf(book: Book): String =
        book.name.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }

    private fun rootDir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "保存视频"
    )

    /**
     * 已下载视频的 bookUrl 列表，顺序与"已缓存视频"列表一致。
     * 供播放器在这些已下载视频之间切换（右侧 30% 竖滑）时用；
     * 只有目录、没有下载记录的（key 为 "dir:" 前缀）无法定位书籍，直接跳过。
     */
    fun downloadedBookUrls(): List<String> =
        _videoGroups.value.mapNotNull { g -> g.key.takeUnless { it.startsWith("dir:") } }

    /** 某部视频第一集已下载的集序号；一集都没下完则返回 null */
    fun firstCompletedIndex(bookUrl: String): Int? =
        synchronized(statesMap) {
            statesMap.values
                .filter { it.task.book.bookUrl == bookUrl && it.status == DlStatus.COMPLETED }
                .minByOrNull { it.task.index }
                ?.task?.index
        }

    /** 某部视频的下载条目（按保存目录名过滤） */
    fun tasksOfDir(dirName: String): List<TaskState> =
        synchronized(statesMap) {
            statesMap.values.filter { dirOf(it.task).name == dirName }
        }

    /**
     * 扫描"已缓存视频"：保存目录下的子目录 + 下载记录里出现过、但目录已不存在的视频
     * （后者按 0 文件显示，方便清掉残留记录）。体积统计放在后台线程。
     */
    fun refreshVideoGroups() {
        scope.launch { _videoGroups.value = buildVideoGroups() }
    }

    private fun buildVideoGroups(): List<VideoGroup> {
        val root = rootDir()
        val snapshot = synchronized(statesMap) { statesMap.values.toList() }
        val map = LinkedHashMap<String, VideoGroup>()
        snapshot.forEach { s ->
            val dirName = dirOf(s.task).name
            val old = map[dirName]
            map[dirName] = VideoGroup(
                key = s.task.book.bookUrl,
                name = s.task.book.name,
                dirName = dirName,
                episodeCount = (old?.episodeCount ?: 0) + 1,
                completedCount = (old?.completedCount ?: 0) +
                    if (s.status == DlStatus.COMPLETED) 1 else 0,
                sizeBytes = 0L,
            )
        }
        // 目录还在、但下载记录已经没了的（记录被清过 / 手动放进去的）
        root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            if (!map.containsKey(d.name)) {
                val mp4 = d.listFiles()?.count { it.isFile && it.name.endsWith(".mp4") } ?: 0
                map[d.name] = VideoGroup("dir:${d.name}", d.name, d.name, mp4, mp4, 0L)
            }
        }
        return map.values.map { g ->
            val dir = File(root, g.dirName)
            val size = if (dir.exists()) {
                runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
                    .getOrDefault(0L)
            } else 0L
            g.copy(sizeBytes = size)
        }.sortedWith(compareByDescending<VideoGroup> { it.sizeBytes }.thenBy { it.name })
    }

    /**
     * 删除若干条目——**删干净**：成品 mp4 + 没下完的 .part/.idx 一起删，并从列表移除。
     * @return 实际删除的条数
     */
    fun deleteTasks(keys: Set<String>): Int {
        var n = 0
        keys.forEach { key ->
            val st = synchronized(statesMap) { statesMap[key] } ?: return@forEach
            val dir = dirOf(st.task)
            File(dir, "${st.title}.mp4").delete()
            File(dir, "${st.title}.mp4.part").delete()
            File(dir, "${st.title}.mp4.idx").delete()
            File(dir, st.title).deleteRecursively() // 分片缓存文件夹
            pausedKeys.remove(key)
            removeState(key)
            n++
        }
        if (n > 0) refreshVideoGroups()
        return n
    }

    /**
     * 删除整部视频——**删干净**：整个保存目录连根删，并移除它的全部下载记录。
     * @return 删除的视频数
     */
    fun deleteVideos(keys: Set<String>): Int {
        if (keys.isEmpty()) return 0
        val groups = _videoGroups.value
        keys.forEach { key ->
            val dirName = groups.firstOrNull { it.key == key }?.dirName
                ?: key.removePrefix("dir:")
            runCatching { File(rootDir(), dirName).deleteRecursively() }
            val toRemove = synchronized(statesMap) {
                statesMap.values.filter { dirOf(it.task).name == dirName }.map { it.key }
            }
            toRemove.forEach {
                pausedKeys.remove(it)
                removeState(it)
            }
        }
        refreshVideoGroups()
        return keys.size
    }

    fun hasStoragePermission(): Boolean =
        Environment.isExternalStorageManager()

    fun openStoragePermissionSetting() {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${appCtx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            appCtx.startActivity(intent)
        }.onFailure {
            AppLog.put("打开存储权限设置失败", it)
        }
    }

    /**
     * 边看边存：把正在播放的选集加入**收集**队列。
     *
     * 这一阶段只把播放缓冲已经写进 [VideoCache] 的分片搬到 .part（纯本地 IO，零流量），
     * 缺的分片不动——否则后台全速下载会和正在播的这一路抢带宽，表现出来就是卡。
     * 缺口留给 [watchFinalize] 在切集/退出后补齐。
     *
     * 已完整保存的跳过（并通知会话改用本地文件），同一集去重。
     */
    fun watchStart(task: Task, knownUrl: String? = null) {
        if (!isWatchSaveEnabled()) return
        if (File(dirOf(task), "${titleOf(task)}.mp4").exists()) {
            // 已经下完了：不再重复下载，让会话直接播本地文件
            notifyCompleted(task)
            return
        }
        val key = watchKey(task)
        synchronized(watchQueued) {
            if (!watchQueued.add(key)) return
        }
        ensureState(task)
        watchChannel.trySend(WatchItem(task, knownUrl, collectOnly = true))
    }

    /**
     * 收尾某一集：切集/播完时调用，把这一集缺失的片段补齐后合并保存。
     *
     * 走**优先队列**（先于 [watchStart] 入队的当前集），因此"播完第 N 集切到第 N+1 集"
     * 时一定是第 N 集先落盘，第 N+1 集排在其后，不会出现切走的那集被丢下、
     * 反而去下新集的情况。
     *
     * 已在下载中的（还在 [watchQueued] 里）不重复入队，让它自然跑完；
     * 已下载完的跳过；之前失败过的则借这次机会重试。
     */
    fun watchFinalize(task: Task, knownUrl: String? = null) {
        if (!isWatchSaveEnabled()) return
        if (File(dirOf(task), "${titleOf(task)}.mp4").exists()) {
            notifyCompleted(task)
            return
        }
        val key = watchKey(task)
        synchronized(watchQueued) {
            if (key in watchQueued) return
        }
        ensureState(task)
        // 收尾 = 真正把缺口补齐，需要联网（播放中会自动让路，退出后全速）
        finalizeChannel.trySend(WatchItem(task, knownUrl, collectOnly = false))
    }

    /**
     * 完整保存策略下无需取消：任务在后台把缺失片段补齐后再合并，保证视频完整。
     * （保留空实现以兼容旧调用点）
     */
    fun watchStop() = Unit

    fun isWatchSaveEnabled(): Boolean =
        prefs.getBoolean("watchSave", false)

    fun setWatchSaveEnabled(on: Boolean) {
        prefs.edit().putBoolean("watchSave", on).apply()
    }

    /**
     * 查找本地可播文件：合并好的 <标题>.mp4 优先；否则只要分片文件夹里有一个分片，
     * 就当成本地缓存，返回（按需生成的）local.m3u8——已下的走本地、没下的实时补。
     */
    fun findLocalFile(bookName: String, index: Int, title: String): File? {
        val name = bookName.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "保存视频/$name"
        )
        val t = title.replace(AppPattern.fileNameRegex, "").ifBlank { "第${index + 1}集" }
        // 合并成品优先
        val mp4 = File(dir, "$t.mp4")
        if (mp4.exists() && mp4.length() > 0) return mp4
        // 分片缓存文件夹：有分片即视为已缓存
        val epFolder = epFolderOf(dir, t)
        if (epFolder.isDirectory &&
            epFolder.listFiles().orEmpty().any { it.name.startsWith("seg_") && it.name.endsWith(".ts") }
        ) {
            return buildLocalPlaylist(epFolder)
        }
        return null
    }

    private fun dirOf(task: Task): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "保存视频/${task.book.name.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }}"
    )

    private fun titleOf(task: Task): String =
        task.chapter.title.replace(AppPattern.fileNameRegex, "").ifBlank { "第${task.index + 1}集" }

    private fun watchKey(task: Task): String =
        "${dirOf(task).absolutePath}/${titleOf(task)}"

    private suspend fun download(task: Task) {
        val key = watchKey(task)
        val title = titleOf(task)
        ensureState(task)
        appCtx.toastOnUi("开始下载：${task.chapter.title}")
        runCatching {
            downloadCore(
                task, dirOf(task), title, knownUrl = null, key = key, collectOnly = false,
            ) { p ->
                updateState(key) { it.copy(progress = p) }
            }
        }
            .onSuccess { file ->
                if (file == null) {
                    updateState(key) { it.copy(status = DlStatus.QUEUED) }
                    return@onSuccess
                }
                AppLog.put("下载完成：${file.absolutePath}")
                updateState(key) { it.copy(progress = 100, status = DlStatus.COMPLETED) }
                appCtx.toastOnUi("下载完成：$title")
            }
            .onFailure { e ->
                when (e) {
                    is PausedException ->
                        updateState(key) { it.copy(status = DlStatus.PAUSED) }
                    else -> {
                        AppLog.put("下载失败：${task.chapter.title}", e)
                        updateState(key) { it.copy(status = DlStatus.FAILED) }
                        appCtx.toastOnUi("下载失败：${task.chapter.title}\n${e.localizedMessage}")
                    }
                }
            }
    }

    /**
     * 下载核心：解析直链 → （m3u8 分段拼接 / 直链流式）→ 合并为 <标题>.mp4。
     *
     * @param collectOnly true 时只取缓存里已有的分片（边播边存阶段），
     *   没取全就不合并，保留 .part/.idx 供后续补齐。
     * @return 合并好的 mp4；**null 表示这一轮没取全**，尚未合并成 mp4。
     */
    private suspend fun downloadCore(
        task: Task,
        dir: File,
        title: String,
        knownUrl: String?,
        key: String,
        collectOnly: Boolean = false,
        onProgress: (Int) -> Unit = {},
    ): File? {
        val mp4 = File(dir, "$title.mp4")
        if (mp4.exists() && mp4.length() > 0) {
            notifyCompleted(task) // 已有合并成品：通知会话改用本地播放，不再联网
            return mp4
        }

        // 1. 直链：优先用已知的（播放中已在播的地址），否则现场解析
        val url = knownUrl?.takeIf { it.startsWith("http") }
            ?: VideoSourcePlayer.resolveChapterUrl(task.book, task.source, task.chapter)
        if (url.isNullOrBlank()) {
            throw IllegalStateException("未获取到直链（网页播放器型源无法下载）")
        }
        val headers = getHeaders(task.source)
        dir.mkdirs()
        val epFolder = epFolderOf(dir, title)
        val isHls = url.contains(".m3u8", true) || url.contains("/m3u8/", true)
        // 同一集正在被别的任务写入（收尾补齐中）：这一轮收集直接跳过，不算失败
        synchronized(activePaths) {
            if (!activePaths.add(epFolder.path)) {
                if (collectOnly) throw BusyException()
                throw IllegalStateException("该选集正在下载中")
            }
        }
        try {
            if (isHls) {
                // m3u8：分片原样落进 <标题>/ 文件夹（raw，加密则保持加密），不在此合并
                val (total, written) = downloadHls(
                    url, headers, epFolder, resume = true, key = key, collectOnly, onProgress
                )
                // 没取全（播放中只收集了缓存里的部分）就不算完成，缺口留给收尾补齐
                if (total <= 0 || written < total) {
                    AppLog.putDebug("已收集 $written/$total，剩余待补齐：${titleOf(task)}")
                    return null
                }
                notifyCompleted(task) // 分片已全部落盘：播放走本地分片，无需合并
                return epFolder
            } else {
                // 单文件直链：直接存成 <标题>.mp4
                val (total, written) = downloadDirect(url, headers, mp4, key = key, collectOnly, onProgress)
                if (total <= 0 || written < total) return null
                notifyCompleted(task)
                return mp4
            }
        } finally {
            synchronized(activePaths) { activePaths.remove(epFolder.path) }
        }
    }

    private fun getHeaders(source: BookSource): Map<String, String> {
        if (cachedHeaders?.first == source.bookSourceUrl) return cachedHeaders!!.second
        val headers = runCatching { source.getHeaderMap(true) }.getOrDefault(emptyMap())
        cachedHeaders = source.bookSourceUrl to headers
        return headers
    }

    /** 暂停检查点：已暂停则抛 [PausedException] 退出下载循环 */
    private fun checkPaused(key: String) {
        if (key in pausedKeys) throw PausedException()
    }

    /**
     * 单文件直链下载，走与播放共用的 [VideoCache]。
     *
     * 不再做 Range 续传：部分服务器不支持 Range 时会返回 200 全量，续传会把整段数据
     * 当成"从断点开始"写进去，拼出打不开的坏文件。改为从头写——
     * 已播放过的部分会命中缓存（零流量），效果等同续传，且不会出现错位的字节。
     *
     * [collectOnly] 时直接跳过：直链是一个整体，没法"只取已缓存的那部分"，
     * 一读就会回源跟播放抢带宽，整集留到收尾时再下。
     */
    private fun downloadDirect(
        url: String,
        headers: Map<String, String>,
        out: File,
        key: String,
        collectOnly: Boolean,
        onProgress: (Int) -> Unit,
    ): Pair<Int, Int> {
        if (collectOnly) return 1 to 0
        out.delete()
        val source = VideoCache.open(url, headers, write = false)
        try {
            val total = source.length
            FileOutputStream(out).use { output ->
                var downloaded = 0L
                var lastPct = -1
                val buf = ByteArray(64 * 1024)
                while (true) {
                    checkPaused(key)
                    val read = source.source.read(buf, 0, buf.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt().coerceIn(0, 99)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            return 1 to 1
        } finally {
            runCatching { source.source.close() }
        }
    }

    /**
     * m3u8 分段下载到 <标题>/ 文件夹：每个分片原样落盘（加密则保持加密），
     * 同时落一份原始 playlist.m3u8（绝对地址）+ key.bin/init.bin/meta.json，
     * 供播放时拼 local.m3u8（已下的走本地、没下的走网络实时补齐）与手动合并成 MP4。
     *
     * 不在此合并：HLS 每个分片自带关键帧，分片文件夹本身就是其他播放器那类"可断点续看的缓存"，
     * 播放器按原生 HLS 逐分片 seek，不再有"裸拼 TS 定位错关键帧定格 3 秒"的问题。
     *
     * @param collectOnly 只收缓存里已有的分片，缺口留给收尾补齐。
     * @return (分片总数, 已落盘的分片数)
     */
    private fun downloadHls(
        playlistUrl: String,
        headers: Map<String, String>,
        epFolder: File,
        resume: Boolean,
        key: String,
        collectOnly: Boolean,
        onProgress: (Int) -> Unit,
    ): Pair<Int, Int> {
        epFolder.mkdirs()
        var playlist = fetchText(playlistUrl, headers)
        // 主清单(#EXT-X-STREAM-INF)：取码率最高的二级媒体清单，递归解析
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            val lines = playlist.lines()
            var bestUrl: String? = null
            var bestBw = -1L
            var i = 0
            while (i < lines.size) {
                val l = lines[i].trim()
                if (l.startsWith("#EXT-X-STREAM-INF")) {
                    val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val next = lines.getOrNull(i + 1)?.trim()
                    if (!next.isNullOrBlank() && !next.startsWith("#") && bw >= bestBw) {
                        bestBw = bw
                        bestUrl = next
                    }
                }
                i++
            }
            checkNotNull(bestUrl) { "主清单中没有媒体清单" }
            val mediaUrl = NetworkUtils.getAbsoluteURL(playlistUrl, bestUrl)
            return downloadHls(mediaUrl, headers, epFolder, resume, key, collectOnly, onProgress)
        }
        var keyUri: String? = null
        var ivHex: String? = null
        var mediaSequence = 0L
        var initSegment: String? = null
        val segUrls = ArrayList<String>()

        playlist.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = line.substringAfter(':').toLongOrNull() ?: 0L

                line.startsWith("#EXT-X-MAP") ->
                    initSegment = parseAttribute(line, "URI")

                line.startsWith("#EXT-X-KEY") -> {
                    val method = parseAttribute(line, "METHOD")
                    if (method.equals("NONE", true)) {
                        keyUri = null
                        ivHex = null
                    } else {
                        keyUri = parseAttribute(line, "URI")
                        ivHex = parseAttribute(line, "IV")
                    }
                }

                !line.startsWith("#") && line.isNotBlank() ->
                    segUrls.add(NetworkUtils.getAbsoluteURL(playlistUrl, line))
            }
        }
        check(segUrls.isNotEmpty()) { "m3u8 中没有分片" }
        val total = segUrls.size
        // 落一份"原始播放列表"（分片/密钥/初始化段都换成绝对地址），供播放时拼 local.m3u8
        File(epFolder, "playlist.m3u8").writeText(
            playlist.lines().joinToString("\n") { l ->
                val t = l.trim()
                when {
                    t.startsWith("#EXT-X-KEY") && keyUri != null ->
                        replaceUriAttr(t, NetworkUtils.getAbsoluteURL(playlistUrl, keyUri))
                    t.startsWith("#EXT-X-MAP") && initSegment != null ->
                        replaceUriAttr(t, NetworkUtils.getAbsoluteURL(playlistUrl, initSegment))
                    !t.startsWith("#") && t.isNotBlank() ->
                        NetworkUtils.getAbsoluteURL(playlistUrl, t)
                    else -> l
                }
            }
        )
        // 密钥 / 初始化段：存原始字节，离线播放与合并都要用
        if (keyUri != null) {
            val keyUrl = NetworkUtils.getAbsoluteURL(playlistUrl, keyUri)
            runCatching { File(epFolder, "key.bin").writeBytes(fetchBytes(keyUrl, headers, write = false)) }
        }
        if (initSegment != null) {
            val initUrl = NetworkUtils.getAbsoluteURL(playlistUrl, initSegment)
            runCatching { File(epFolder, "init.bin").writeBytes(fetchBytes(initUrl, headers, write = false)) }
        }
        org.json.JSONObject().apply {
            put("seq", mediaSequence)
            if (ivHex != null) put("iv", ivHex) else put("iv", org.json.JSONObject.NULL)
            put("encrypted", keyUri != null)
        }.let { File(epFolder, "meta.json").writeText(it.toString()) }

        // 断点续传：已存在的非空分片直接跳过（best-effort，避免重下几十 MB 的分片）
        val need = (0 until total).filter {
            !(segFile(epFolder, it).exists() && segFile(epFolder, it).length() > 0)
        }
        if (need.isEmpty()) return total to total
        // 边播边存收集阶段：只搬缓存里已有的分片，缺口留给收尾
        val todo = if (collectOnly) need.filter { VideoCache.isCached(segUrls[it]) }
        else need
        if (todo.isEmpty()) return total to (total - need.size)

        // 播放中：压低并发并分片节流，给正在播的那一路让出带宽
        val throttle = playbackActive && !collectOnly
        val workerCount = if (collectOnly || throttle) 2 else 6
        val keyCache = HashMap<String, ByteArray>()
        val nextIndex = AtomicInteger(0)
        val writtenCount = AtomicInteger(0)
        val lastPct = AtomicInteger(-1) // 进度按百分比变化节流，避免频繁刷新状态
        val errorRef = AtomicReference<Exception?>(null)
        val inFlight = Semaphore(workerCount)
        val workers = (0 until workerCount).map {
            Thread {
                try {
                    while (errorRef.get() == null) {
                        val idx = nextIndex.getAndIncrement()
                        if (idx >= todo.size) return@Thread
                        val k = todo[idx]
                        inFlight.acquire()
                        try {
                            checkPaused(key)
                            fetchSegmentToFile(
                                Triple(segUrls[k], ivHex, keyUri),
                                playlistUrl, headers, mediaSequence + k,
                                keyCache, segFile(epFolder, k), raw = true
                            )
                            val done = writtenCount.incrementAndGet()
                            // 每落盘一个分片就回报一次进度（含断点续传已存在的），按百分比变化节流
                            val pct = ((done + total - need.size) * 100 / total).coerceIn(0, 99)
                            if (pct != lastPct.getAndSet(pct)) onProgress(pct)
                            if (throttle) Thread.sleep(THROTTLE_SLEEP_MS)
                        } finally {
                            inFlight.release()
                        }
                    }
                } catch (e: Exception) {
                    errorRef.compareAndSet(null, e)
                }
            }.apply { start() }
        }
        try {
            // 必须等所有 worker 真正跑完：之前用 join(10_000) 超时，网络慢/分片多时会提前返回，
            // 但 worker 线程还在后台写盘——表现就是"分片其实下完了，状态却停在等待中"。
            workers.forEach { it.join() }
        } finally {
            // 中断/异常时清掉没下完的半截分片，别在文件夹里留坏文件
            for (k in todo) {
                val f = segFile(epFolder, k)
                if (f.exists() && f.length() == 0L) f.delete()
            }
        }
        errorRef.get()?.let { throw it }
        onProgress(100)
        // 全部补齐：临时索引清掉（playlist/meta 保留供播放与合并）
        if ((0 until total).all { segFile(epFolder, it).exists() && segFile(epFolder, it).length() > 0 }) {
            File(epFolder, "index.idx").delete()
        }
        // 完成数以磁盘实际落盘为准（不依赖 writtenCount 计数），避免并发/超时造成的漏算
        val written = (0 until total).count {
            segFile(epFolder, it).exists() && segFile(epFolder, it).length() > 0
        }
        return total to written
    }

    /**
     * 把一个分片**流式**落到临时文件：命中缓存读缓存，否则联网（不写缓存）。
     * 全程只用固定大小的缓冲区，不在内存里攒整个分片——大分片 OOM 就是这么来的。
     * 加密分片用 [CipherOutputStream] 边读边解密，同样不占内存。
     */
    private fun fetchSegmentToFile(
        segment: Triple<String, String?, String?>,
        playlistUrl: String,
        headers: Map<String, String>,
        sequence: Long,
        keyCache: HashMap<String, ByteArray>,
        segFile: File,
        raw: Boolean = true,
    ) {
        val (segUrl, segIv, segKeyUri) = segment
        // write=false：播放器已经在写缓存了，下载侧只读不写，免得两边抢 SimpleCache 的锁
        val source = VideoCache.open(segUrl, headers, write = false)
        // raw=true：原样落盘（加密分片保持加密，离线播放/合并时再由播放器或合并器解密）
        val cipher = if (!raw && segKeyUri != null) {
            val keyUrl = NetworkUtils.getAbsoluteURL(playlistUrl, segKeyUri)
            val k = synchronized(keyCache) {
                keyCache.getOrPut(keyUrl) { fetchBytes(keyUrl, headers, write = false) }
            }
            aesCipher(k, segIv, sequence)
        } else {
            null
        }
        try {
            FileOutputStream(segFile).use { fos ->
                val sink: OutputStream = cipher?.let { CipherOutputStream(fos, it) } ?: fos
                sink.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.source.read(buf, 0, buf.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buf, 0, read)
                    }
                }
            }
        } finally {
            runCatching { source.source.close() }
        }
    }

    /** 某一集的分片缓存目录：Download/保存视频/<dir>/<标题>/ */
    private fun epFolderOf(dir: File, title: String): File = File(dir, title)

    /** 第 k 个分片文件（按播放列表顺序，0 基），原始字节（加密则保持加密） */
    private fun segFile(epFolder: File, index: Int): File =
        File(epFolder, "seg_%06d.ts".format(index))

    /**
     * 把已缓存的分片文件夹合并成单个 MP4（多选后由 UI 触发）。
     *
     * 走和在线播放一致的"逐分片解密 + 顺序拼接"：读本地原始分片，用 [meta.json] 里的
     * 序列号/IV 还原 AES 密钥，[CipherInputStream] 边读边解密，顺序写入 <标题>.mp4。
     * 不重封装、不转码——这是验证过稳定的老办法，专门用来离线保存。
     *
     * 合并与播放互不干扰：合并在后台协程跑，原分片文件夹始终保留（合并失败也只是没产出 mp4）。
     */
    fun mergeToMp4(task: Task) {
        scope.launch {
            runCatching { mergeEpisode(task) }
                .onFailure { AppLog.put("合并为 MP4 失败：${titleOf(task)}", it) }
        }
    }

    private fun mergeEpisode(task: Task): File? {
        val dir = dirOf(task)
        val title = titleOf(task)
        val epFolder = epFolderOf(dir, title)
        if (!epFolder.isDirectory) return null
        val mp4 = File(dir, "$title.mp4")
        val segs = epFolder.listFiles().orEmpty()
            .filter { it.name.startsWith("seg_") && it.name.endsWith(".ts") }
            .sortedBy { it.name }
        if (segs.isEmpty()) return null
        val meta = runCatching { org.json.JSONObject(File(epFolder, "meta.json").readText()) }.getOrNull()
        val seq = meta?.optLong("seq", 0L) ?: 0L
        val iv = if (meta != null && meta.has("iv") && !meta.isNull("iv")) meta.getString("iv") else null
        val keyFile = File(epFolder, "key.bin").takeIf { it.exists() }
        val keyBytes = keyFile?.readBytes()
        val initFile = File(epFolder, "init.bin").takeIf { it.exists() }
        FileOutputStream(mp4).use { out ->
            initFile?.inputStream()?.use { ins -> ins.copyTo(out) }
            segs.forEachIndexed { k, seg ->
                val cipher = if (keyBytes != null) aesCipher(keyBytes, iv, seq + k) else null
                seg.inputStream().use { ins ->
                    val decoded = cipher?.let { CipherInputStream(ins, it) } ?: ins
                    decoded.use { din ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = din.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                        }
                    }
                }
            }
        }
        File(epFolder, "index.idx").delete()
        notifyCompleted(task) // mp4 已生成：会话可切到本地 mp4 播放
        return mp4
    }

    /**
     * 生成播放用的 local.m3u8：已下载的分片指向本地文件（零网络），
     * 未下载的保持原始 http(s) 地址由播放器实时补齐——这就是"分片文件夹里有一个分片就算缓存"。
     * 加密流的密钥/初始化段也优先用本地副本。
     */
    private fun buildLocalPlaylist(epFolder: File): File {
        val out = File(epFolder, "local.m3u8")
        val playlist = runCatching { File(epFolder, "playlist.m3u8").readText() }.getOrNull()
            ?: return File(epFolder, "playlist.m3u8")
        val sb = StringBuilder()
        var k = 0
        playlist.lines().forEach { line ->
            val l = line
            when {
                l.startsWith("#EXT-X-KEY") -> {
                    val localKey = File(epFolder, "key.bin")
                    if (localKey.exists()) sb.append(replaceUriAttr(l, Uri.fromFile(localKey).toString()))
                        .append('\n')
                    else sb.append(l).append('\n')
                }
                l.startsWith("#EXT-X-MAP") -> {
                    val localInit = File(epFolder, "init.bin")
                    if (localInit.exists()) sb.append(replaceUriAttr(l, Uri.fromFile(localInit).toString()))
                        .append('\n')
                    else sb.append(l).append('\n')
                }
                l.startsWith("#") -> sb.append(l).append('\n')
                l.isBlank() -> Unit
                else -> {
                    val localSeg = segFile(epFolder, k)
                    if (localSeg.exists()) sb.append(Uri.fromFile(localSeg).toString()).append('\n')
                    else sb.append(l).append('\n')
                    k++
                }
            }
        }
        out.writeText(sb.toString())
        return out
    }

    /** 把 #EXT-X-KEY / #EXT-X-MAP 行的 URI="..." 替换成给定地址 */
    private fun replaceUriAttr(line: String, uri: String): String =
        line.replace(Regex("""URI="[^"]*""""), """URI="$uri"""")

    /** AES-128-CBC 解密器；未指定 IV 时按 HLS 规范用分片序号（大端 16 字节） */
    private fun aesCipher(key: ByteArray, ivHex: String?, sequence: Long): Cipher {
        val iv = if (ivHex != null) {
            val hex = ivHex.removePrefix("0x").removePrefix("0X")
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            ByteBuffer.allocate(16).putLong(sequence).array()
        }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    private fun parseAttribute(line: String, name: String): String? {
        val regex = Regex("""$name=("([^"]*)"|[^,]*)""")
        return regex.find(line)?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }
            ?: regex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    }

    /**
     * 取文本（m3u8 清单）**直连、不进缓存**：
     * 站点常在清单地址里带时效 token，缓存住一份旧清单会让后续播放/下载都用过期分片。
     * 清单体积很小，重复取的代价可以忽略；真正占流量的分片走 [VideoCache]。
     */
    private fun fetchText(url: String, headers: Map<String, String>): String {
        val request = okhttp3.Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        okHttpClient.newCall(request).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            return resp.body?.string() ?: ""
        }
    }

    /**
     * 取字节（m3u8 分片 / AES 密钥 / 初始化段）——**走 [VideoCache]**。
     * 播放缓冲过的分片会直接命中，不再重复下载；未命中的联网取回后同样写进缓存，
     * 供之后再播放时复用。
     */
    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        write: Boolean = false,
        retry: Int = 2,
    ): ByteArray {
        repeat(retry) { attempt ->
            try {
                return VideoCache.readBytes(url, headers, write)
            } catch (e: Exception) {
                if (attempt == retry - 1) throw e
            }
        }
        throw IllegalStateException("unreachable")
    }
}
