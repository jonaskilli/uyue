package io.legado.app.help.video

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.media3.common.C
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.model.download.FfmpegMerger
import kotlinx.coroutines.sync.withLock
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
import kotlinx.coroutines.withContext
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
 * - 保存位置：/storage/emulated/0/Download/A保存视频/<视频名>/，后缀统一 .mp4
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
    enum class DlStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, COLLECTING, MERGING }

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
        /** 保存目录名（Download/A保存视频/<dirName>）：界面按它把条目归到各视频名下 */
        val dirName: String = "",
        /** 合并 MP4 进度（0-100）；-1 = 未在合并。界面在播放按钮左侧的圆圈上显示 */
        val mergeProgress: Int = -1,
        /** 直播录制任务：没有固定总量，进度不能用百分比，改用 [recordedMs] 显示 */
        val isLive: Boolean = false,
        /** 已录制的媒体时长（毫秒），仅 [isLive] 任务有意义 */
        val recordedMs: Long = 0L,
    )

    /** 暂停用异常：从下载循环安全退出，已写入部分保留供续传 */
    private class PausedException : Exception()

    /** 同一集正在被别的任务写入（收尾补齐中）：这一轮收集直接跳过，不算失败 */
    private class BusyException : Exception()

    /**
     * 手动下载撞上"这一集正被边看边存占用"。
     *
     * **不是失败**：边看边存每 20 秒就要跑一轮收集（`collectCurrentEpisode`），
     * 用户在缓冲时点下载极易正好撞上，原来直接判 FAILED 并弹
     * 「下载失败：该选集正在下载中」，用户只看到"下载总是中断"。
     * 现在交给 [download] 重排队尾等一会儿再试，收集那一轮跑完就能接上。
     */
    private class SegmentBusyException : Exception()

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
     * 正在录制的直播任务。直播没有"下完"这个概念，必须跑独立协程长期拉流，
     * 不能占着 [manualQueue] 的串行 worker（否则录制期间别的选集一个都下不了）。
     */
    private val liveRecordingKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 用户点了「完成并合成」：录制循环见到就退出并把已录部分合成 MP4 */
    private val liveFinishKeys: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** 直播录制现场文件：暂停/重启后据此接着录 */
    private const val LIVE_META_FILE = "live.json"
    /** 连续拉清单失败多少次才判定直播中断 */
    private const val LIVE_MAX_FETCH_FAIL = 10
    /** 撞上"该集正忙"的重排次数（key = watchKey）：只用来给重排设上限，避免空转 */
    private val busyRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 重排上限：边看边存若长期占着这一集，超过就按失败处理，不再无限重排 */
    private const val MAX_BUSY_REQUEUE = 10
    /** 重排前的等待：边看边存一轮收集通常几秒内就跑完 */
    private const val BUSY_REQUEUE_DELAY_MS = 1500L

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

    /**
     * 外部备份：跟视频文件一起放在 Download/A保存视频/ 下。
     * 内部那份在 appCtx.filesDir，清数据、卸载重装或系统清理就没了；这份随文件走，
     * 用户也能在文件管理器里直接看到。两份由 [saveNow] 同步写、由 [restore] 合并恢复。
     */
    private val backupFile: File by lazy { File(rootDir(), "下载记录.json") }

    private val saveLock = Any()
    private var saveScheduled = false

    @Volatile
    private var restored = false

    private val restoreLock = Any()

    /** 读一份记录文件；文件不存在、为空或损坏时返回空列表，绝不让备份问题影响主流程 */
    private fun readRecords(file: File): List<org.json.JSONObject> {
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val arr = runCatching { org.json.JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** 一条记录的去重键：书 + 章节地址 + 集号（与 [watchKey] 的语义一致） */
    private fun recordKey(o: org.json.JSONObject): String =
        o.optString("bookUrl") + "|" + o.optString("chapterUrl") + "|" + o.optInt("index", 0)

    /** 恢复上次的下载记录（幂等，只跑一次）。App 启动与进入播放页都会调用。 */
    fun restoreIfNeeded() {
        synchronized(restoreLock) {
            if (restored) return
            restored = true
        }
        scope.launch {
            // 装完新版本必须先打开一次 App 才会执行目录迁移；这里主动触发，
            // 不依赖后续是否访问保存目录，也避免「只装不看」时目录一直没变化
            runCatching { saveRootDir }
            runCatching { restore() }
        }
    }

    private fun restore() {
        // 两份记录对比合并去重：内部优先，外部备份独有的补进来。
        // 内部那份被清掉或损坏时，外部备份就是恢复来源。
        val inner = readRecords(indexFile)
        val outer = readRecords(backupFile)
        val merged = LinkedHashMap<String, org.json.JSONObject>()
        inner.forEach { merged[recordKey(it)] = it }
        outer.forEach { merged.putIfAbsent(recordKey(it), it) }
        if (merged.isEmpty()) return
        AppLog.put(
            "下载记录: 内部 ${inner.size} 条、外部备份 ${outer.size} 条，合并去重后 ${merged.size} 条"
        )
        for (o in merged.values) {
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
            // 以磁盘实际文件为准判定状态，但**绝不能只看"有没有文件"**：
            // - mp4 成品在 = 已完成（直链下载先写 .part、写满才改名，成品必是完整的）；
            // - m3u8 分片目录必须**逐片核对**总数与已落盘数：下载到一半被杀进程后，
            //   文件夹里照样躺着一堆 seg_ 文件，只看"有分片"会把它误判成已完成 100%，
            //   于是它再也不会被继续下载——表现就是"列表显示已下载，播放却还在边播边下"；
            //   缺了的分片要按暂停处理，续传时只补缺口。
            // - 只有 .part = 中断，可续传
            // 直播录制现场没有"分片总数"：绝不能拿 hlsSegProgress 的行数判完成，
            // 恢复成暂停，点继续时由录制循环从 live.json 的 lastSeq 接着录。
            val live = readLiveMeta(File(dir, title))
            val hls = hlsSegProgress(File(dir, title))
            val state = when {
                live != null -> DlStatus.PAUSED to 0
                File(dir, "$title.mp4").exists() ->
                    DlStatus.COMPLETED to 100
                hls != null && hls.second >= hls.first ->
                    DlStatus.COMPLETED to 100
                hls != null && hls.second > 0 ->
                    // 下了一半：按已落盘分片数给出真实百分比，方便一眼看出缺多少
                    DlStatus.PAUSED to (hls.second * 100 / hls.first).coerceIn(0, 99)
                File(dir, "$title.mp4.part").exists() ->
                    DlStatus.PAUSED to savedProgress
                // 直播录像已归档成带序号的成品（如 标题001.mp4），没有可续录的现场。
                // 不认这条的话重启后会显示「失败」，让用户以为录像丢了。
                hasLiveArchive(dir, title) ->
                    DlStatus.COMPLETED to 100
                else -> DlStatus.FAILED to savedProgress
            }
            synchronized(statesMap) {
                statesMap[key] = TaskState(
                    key, task, title, state.second, state.first, dir.name,
                    isLive = live != null,
                    recordedMs = live?.durationMs ?: 0L,
                )
            }
            // 恢复历史下载记录时，本地还没有封面的补存一份（幂等），
            // 老视频重启后下载记录里的封面也因此能稳定刷出来
            saveCoverAsync(task)
        }
        publishStates()
        // 合并结果立即回写两份：内部那份被清掉时，这一次就把记录补回来，
        // 也让外部备份与内部保持同一份快照
        runCatching { saveNow() }
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
        val text = arr.toString()
        writeRecord(indexFile, text)
        // 外部备份写失败（没拿到外部存储权限、目录被手动删掉等）不能影响内部记录
        runCatching { writeRecord(backupFile, text) }
            .onFailure { AppLog.putDebug("下载记录: 外部备份写入失败 ${it.localizedMessage}") }
    }

    /** 先写 .tmp 再改名：避免写到一半被杀进程，留下半截 JSON 导致整份记录读不出来 */
    private fun writeRecord(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
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
        // 入队时顺手把封面落到视频目录（幂等，已存过不产生流量）
        tasks.distinctBy { dirOf(it).name }.forEach { saveCoverAsync(it) }
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
                .filter {
                    it.status != DlStatus.COMPLETED && it.status != DlStatus.RUNNING &&
                        it.status != DlStatus.MERGING
                }
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

    // ---------- 直播录制：暂停时由用户决定「真暂停」还是「下载完成」 ----------

    /** 该条目是否正在录制直播（界面据此在暂停前弹选择框） */
    fun isLiveRecording(key: String): Boolean = key in liveRecordingKeys

    /** 指定目录里有没有待收尾的直播录制（录制中与「仅暂停」后都算） */
    fun hasLiveRecording(dirName: String?): Boolean = synchronized(statesMap) {
        statesMap.values.any {
            (dirName == null || it.dirName == dirName) && isUnfinishedLive(it)
        }
    }

    private fun isUnfinishedLive(s: TaskState): Boolean =
        s.isLive && s.status != DlStatus.COMPLETED && s.status != DlStatus.MERGING

    /**
     * 「下载完成」：停止拉流，把已录制的分片合成一个完整 MP4。
     *
     * 正在录制时只置标记，让录制循环自己在下一个检查点收尾（不会硬杀正在写的分片）；
     * 已经处于暂停状态、没有循环在跑时，直接按磁盘现状合成。
     */
    fun finishLiveRecording(key: String) {
        if (key in liveRecordingKeys) {
            liveFinishKeys.add(key)
            updateState(key) { it.copy(status = DlStatus.MERGING, mergeProgress = 0) }
            return
        }
        val state = synchronized(statesMap) { statesMap[key] } ?: return
        val task = state.task
        // 已经在合成 / 已经合成完了：重复点不该再合并一次
        if (state.status == DlStatus.MERGING || state.status == DlStatus.COMPLETED) return
        pausedKeys.remove(key)
        liveFinishKeys.remove(key)
        scope.launch {
            runCatching { finalizeLiveRecording(task, key) }
                .onFailure { AppLog.put("直播录制合成失败：${titleOf(task)}", it) }
        }
    }

    /**
     * 批量收尾：范围内正在录制的直播按「下载完成」合成，其余条目照常暂停。
     *
     * @return 被收尾的直播条数
     */
    fun finishLiveRecordings(dirName: String?): Int {
        val keys = synchronized(statesMap) {
            statesMap.values
                .filter { (dirName == null || it.dirName == dirName) && isUnfinishedLive(it) }
                .map { it.key }
        }
        keys.forEach { finishLiveRecording(it) }
        // 合成中的条目状态已是 MERGING，pauseAll 会自动跳过它们
        pauseAll(dirName)
        return keys.size
    }

    /** 删除已缓存（分片文件夹 + mp4 成品 + .part/.idx 临时文件），条目保留，再次下载时从头开始 */
    fun deleteCache(state: TaskState) {
        // 正在录制时删目录会和拉流写盘打架：先让用户停掉录制
        if (state.key in liveRecordingKeys) {
            appCtx.toastOnUi("请先停止直播录制，再删除缓存")
            return
        }
        val dir = dirOf(state.task)
        // 界面状态先落，瞬时完成
        updateState(state.key) { it.copy(progress = 0) }
        // ===== 文件删除必须放后台 =====
        // deleteRecursively() 是"递归遍历 + 逐个 unlink"，每个文件一次文件系统调用。
        // 一集就是 600+ 个分片、一部剧上万 个，全在 /storage（外部存储，比内部慢一个量级），
        // 跑在主线程上必然 ANR——症状就是"文件小还行、文件一大界面直接崩"。
        // 这里写法本身没问题，纯粹是线程放错了。
        scope.launch {
            runCatching {
                File(dir, state.title).deleteRecursively() // 分片缓存文件夹
                File(dir, "${state.title}.mp4").delete()
                File(dir, "${state.title}.mp4.part").delete()
                File(dir, "${state.title}.mp4.idx").delete()
            }.onFailure { AppLog.put("删除缓存失败：${state.title}", it) }
        }
    }

    /** 删除已生成的 mp4 成品，并从下载列表移除该条目（分片文件夹缓存保留） */
    /** 该集是否已合并出 mp4 成品（界面的合并状态圈据此显示） */
    fun hasMp4(task: Task, title: String): Boolean =
        runCatching { File(dirOf(task), "$title.mp4").exists() && File(dirOf(task), "$title.mp4").length() > 0 }
            .getOrDefault(false)

    fun deleteMp4(state: TaskState) {
        File(dirOf(state.task), "${state.title}.mp4").delete()
        removeState(state.key)
        // 不等 800ms 节流：删除立即同步到内部记录和外部备份，两边一起删
        scope.launch { runCatching { saveNow() } }
    }

    // ---------- 已缓存视频（按视频分组管理） ----------

    /** 一部视频的下载汇总 */
    data class VideoGroup(
        /** 有下载记录时是 bookUrl，只有目录没有记录时是 "dir:<目录名>" */
        val key: String,
        val name: String,
        /** 保存目录名（Download/A保存视频/<dirName>） */
        val dirName: String,
        val episodeCount: Int,
        val completedCount: Int,
        val sizeBytes: Long,
        /** 封面（下载记录里有对应书籍时才有；只有目录、无记录的为 null） */
        val coverUrl: String? = null,
        /** 封面所属书源地址：部分封面要带来源 Referer 才拉得到 */
        val origin: String? = null,
    )

    private val _videoGroups = MutableStateFlow<List<VideoGroup>>(emptyList())
    val videoGroups: StateFlow<List<VideoGroup>> get() = _videoGroups

    /** 保存目录名：与 [dirOf] 同一套清洗规则 */
    fun dirNameOf(book: Book): String =
        book.name.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }

    /**
     * 视频的"添加时间"：用保存目录的 mtime 近似。
     * 新建目录（首次下载）与写入新集都会刷新它——最新添加/最新有动静的视频排最上。
     */
    fun groupAddTime(dirName: String): Long =
        runCatching { File(rootDir(), dirName).lastModified() }.getOrDefault(0L)

    /** 保存目录名：A 开头，在文件管理器里排在中文目录前面，方便找 */
    const val SAVE_ROOT_NAME = "A保存视频"
    private const val OLD_SAVE_ROOT_NAME = "保存视频"

    /**
     * 把旧目录搬到新目录：同一外部存储分区里 renameTo 只是改目录项，瞬时完成、不复制数据，
     * 已下载的视频、分片、封面、下载记录.json 都跟着过去。
     * 公共 Download 分区有时会拒绝「整体改名」，这时退化成逐项搬；全都没搬成才继续用旧目录，
     * 绝不让已有视频凭空消失。每一步都写日志，方便在应用日志里直接核对结果。
     */
    private val saveRootDir: File by lazy {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val newDir = File(downloads, SAVE_ROOT_NAME)
        val oldDir = File(downloads, OLD_SAVE_ROOT_NAME)
        val granted = Environment.isExternalStorageManager()
        if (!oldDir.isDirectory) {
            AppLog.put("保存目录: 旧目录不存在，直接使用 $SAVE_ROOT_NAME")
            return@lazy newDir
        }
        if (!newDir.isDirectory && oldDir.renameTo(newDir)) {
            AppLog.put("保存目录已改名：$OLD_SAVE_ROOT_NAME → $SAVE_ROOT_NAME")
            return@lazy newDir
        }
        val entries = oldDir.listFiles()
        if (entries == null) {
            AppLog.put("保存目录改名失败：读不到旧目录（权限=$granted），继续沿用 $OLD_SAVE_ROOT_NAME")
            return@lazy oldDir
        }
        // 整体改名被拒或新目录已存在：逐项搬，同名条目保留新目录里的
        if (!newDir.isDirectory) newDir.mkdirs()
        var moved = 0
        var failed = 0
        entries.forEach { f ->
            val target = File(newDir, f.name)
            if (target.exists()) {
                moved++
            } else if (f.renameTo(target)) {
                moved++
            } else {
                failed++
            }
        }
        if (failed == 0 && oldDir.listFiles().isNullOrEmpty()) oldDir.delete()
        AppLog.put(
            "保存目录迁移: 权限=$granted，旧目录 ${entries.size} 项，搬入/同名 $moved 项，" +
                "失败 $failed 项，当前使用 ${newDir.name}，" +
                "旧目录${if (oldDir.exists()) "仍存在" else "已移除"}"
        )
        if (moved == 0 && entries.isNotEmpty()) {
            AppLog.put("保存目录改名失败，继续沿用 $OLD_SAVE_ROOT_NAME")
            return@lazy oldDir
        }
        newDir
    }

    /** 供 AdFilter 等共用同一个根目录（迁移只做一次） */
    fun saveRoot(): File = saveRootDir

    private fun rootDir(): File = saveRootDir

    /**
     * 每部视频体积缓存：存到该视频目录下的 [SIZE_CACHE_FILE]（随缓存走，重装/换设备都在）。
     * 这样打开下载管理时不必每次递归遍历整目录算体积（几千个切片文件能卡 1 秒），
     * 直接读记录即可。记录目录 mtime：目录有变动（新增/删除/改名集）时判定过期，
     * 重新算并把结果写回，下次再开又变"直接读"。
     */
    private const val SIZE_CACHE_FILE = ".size_cache.json"

    private data class DirSizeMeta(val sizeBytes: Long, val mtime: Long)

    private fun readDirSize(dirName: String): DirSizeMeta? {
        val f = File(File(rootDir(), dirName), SIZE_CACHE_FILE)
        return runCatching {
            val text = f.readText().takeIf { it.isNotBlank() } ?: return null
            val o = org.json.JSONObject(text)
            DirSizeMeta(o.optLong("size", -1), o.optLong("mtime", -1))
        }.getOrNull()
    }

    private fun writeDirSize(dirName: String, sizeBytes: Long, mtime: Long) {
        runCatching {
            File(File(rootDir(), dirName), SIZE_CACHE_FILE).writeText(
                org.json.JSONObject().apply {
                    put("size", sizeBytes)
                    put("mtime", mtime)
                }.toString()
            )
        }
    }

    /**
     * 已下载视频的 bookUrl 列表，顺序与"已缓存视频"列表一致。
     * 供播放器在这些已下载视频之间切换（右侧 30% 竖滑）时用；
     * 只有目录、没有下载记录的（key 为 "dir:" 前缀）无法定位书籍，直接跳过。
     */
    fun downloadedBookUrls(): List<String> =
        _videoGroups.value.mapNotNull { g -> g.key.takeUnless { it.startsWith("dir:") } }

    /** 离线播放用：下载记录里某一集的信息（目录表缺失时靠它还原播放列表） */
    data class OfflineEpisode(
        val index: Int,
        val title: String,
        val chapterUrl: String,
        val completed: Boolean,
    )

    /**
     * 离线播放用：按 bookUrl 取出下载索引里的集（序号 / 标题 / 章节地址）。
     * 纯本地读取，不联网；目录表被清掉或从未成功拉过目录时，
     * 靠这份记录也能把磁盘上的 MP4、分片缓存还原成可播列表。
     */
    fun offlineEpisodes(bookUrl: String): List<OfflineEpisode> =
        synchronized(statesMap) {
            statesMap.values
                .filter { it.task.book.bookUrl == bookUrl }
                .map {
                    OfflineEpisode(
                        it.task.index,
                        it.task.chapter.title,
                        it.task.chapter.url,
                        it.status == DlStatus.COMPLETED,
                    )
                }
        }.sortedBy { it.index }

    /** 书库里这本书已被删时，从下载记录兜底取回 Book（含剧名，用来定位保存目录） */
    fun offlineBook(bookUrl: String): Book? = synchronized(statesMap) {
        statesMap.values.firstOrNull { it.task.book.bookUrl == bookUrl }?.task?.book
    }

    /** 同上：书源被删时兜底，供后台刷新目录用 */
    fun offlineSource(bookUrl: String): BookSource? = synchronized(statesMap) {
        statesMap.values.firstOrNull { it.task.book.bookUrl == bookUrl }?.task?.source
    }

    /** 某部视频第一集已下载的集序号；一集都没下完则返回 null */
    fun firstCompletedIndex(bookUrl: String): Int? =
        synchronized(statesMap) {
            statesMap.values
                .filter { it.task.book.bookUrl == bookUrl && it.status == DlStatus.COMPLETED }
                .minByOrNull { it.task.index }
                ?.task?.index
        }

    /**
     * 续播集号：上次看到的那一集（book.durChapterIndex，与书架续播同源）。
     * - 那一集已下载完成 → 直接续它（集内进度由播放器从 durChapterPos 恢复）；
     * - 上次看的那集没下载（或从没看过）→ 回落到第一集已下载的集；
     * - 一集都没下完 → null（调用方不显示播放按钮）。
     */
    fun resumeIndexFor(bookUrl: String): Int? {
        val last = appDb.bookDao.getBook(bookUrl)?.durChapterIndex
        val completed = synchronized(statesMap) {
            statesMap.values
                .filter { it.task.book.bookUrl == bookUrl && it.status == DlStatus.COMPLETED }
                .map { it.task.index }
                .toSet()
        }
        if (completed.isEmpty()) return null
        return if (last != null && last in completed) last else completed.min()
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
            // 本地已保存的封面优先：URL 封面有防盗链/时效问题，重启后联网重拉经常失败；
            // 本地文件跟视频走，永远能刷出来
            val localCover = localCoverOf(dirName)
            map[dirName] = VideoGroup(
                key = s.task.book.bookUrl,
                name = s.task.book.name,
                dirName = dirName,
                episodeCount = (old?.episodeCount ?: 0) + 1,
                completedCount = (old?.completedCount ?: 0) +
                    if (s.status == DlStatus.COMPLETED) 1 else 0,
                sizeBytes = 0L,
                coverUrl = localCover ?: s.task.book.getDisplayCover(),
                origin = if (localCover == null) s.task.book.origin else null,
            )
        }
        // 目录还在、但下载记录已经没了的（记录被清过 / 手动放进去的）
        root.listFiles()?.filter { it.isDirectory }?.forEach { d ->
            if (!map.containsKey(d.name)) {
                val mp4 = d.listFiles()?.count { it.isFile && it.name.endsWith(".mp4") } ?: 0
                // 只有目录没有记录的视频：封面也尝试用目录里存过的本地封面
                map[d.name] = VideoGroup(
                    "dir:${d.name}", d.name, d.name, mp4, mp4, 0L,
                    coverUrl = localCoverOf(d.name),
                )
            }
        }
        return map.values.map { g ->
            val dir = File(root, g.dirName)
            val size = if (dir.exists()) {
                val mtime = dir.lastModified()
                // 直接读记录：目录没变动就跳过整目录遍历，打开列表瞬间出数据
                val cached = readDirSize(g.dirName)
                if (cached != null && cached.mtime == mtime) {
                    cached.sizeBytes
                } else {
                    // 记录缺失或目录已变（新增/删除了集）：算一次并写回记录
                    val real = runCatching {
                        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    }.getOrDefault(0L)
                    writeDirSize(g.dirName, real, mtime)
                    real
                }
            } else 0L
            g.copy(sizeBytes = size)
        }.sortedWith(compareByDescending<VideoGroup> { it.sizeBytes }.thenBy { it.name })
    }

    /**
     * 删除若干条目——**删干净**：成品 mp4 + 没下完的 .part/.idx 一起删，并从列表移除。
     * @return 实际删除的条数
     */
    fun deleteTasks(keys: Set<String>): Int {
        if (keys.isEmpty()) return 0
        // 先把条目摘掉：状态操作只在内存里做（removeState 内部 synchronized），瞬时完成，
        // 界面立刻少掉这些项，删除在后台慢慢进行。
        val targets = synchronized(statesMap) {
            keys.mapNotNull { key -> statesMap[key]?.let { key to it } }
        }
        if (targets.isEmpty()) return 0
        targets.forEach { (key, _) ->
            pausedKeys.remove(key)
            removeState(key)
        }
        refreshVideoGroups()
        // 真正的删除放后台，且**串行**：多个目录同时删会互相抢外部存储的 IO，
        // 并发反而更慢，还容易把 ANR 拖得更长。
        scope.launch {
            targets.forEach { (_, st) ->
                val dir = dirOf(st.task)
                runCatching {
                    File(dir, "${st.title}.mp4").delete()
                    File(dir, "${st.title}.mp4.part").delete()
                    File(dir, "${st.title}.mp4.idx").delete()
                    File(dir, st.title).deleteRecursively() // 分片缓存文件夹
                }.onFailure { AppLog.put("删除缓存失败：${st.title}", it) }
            }
            // 文件真的删完了再刷新一次统计，避免把"还没删完"的目录算进已缓存大小
            refreshVideoGroups()
            // 删除立即同步到内部记录和外部备份，不等 800ms 节流窗口
            runCatching { saveNow() }
        }
        return targets.size
    }

    /**
     * 删除整部视频——**删干净**：整个保存目录连根删，并移除它的全部下载记录。
     * @return 删除的视频数
     */
    fun deleteVideos(keys: Set<String>): Int {
        if (keys.isEmpty()) return 0
        val groups = _videoGroups.value
        val dirNames = keys.map { key ->
            groups.firstOrNull { it.key == key }?.dirName ?: key.removePrefix("dir:")
        }.distinct()
        // 先摘记录（内存操作，瞬间完成）
        val toRemove = synchronized(statesMap) {
            statesMap.values.filter { dirOf(it.task).name in dirNames }.map { it.key }
        }
        toRemove.forEach {
            pausedKeys.remove(it)
            removeState(it)
        }
        refreshVideoGroups()
        // 整部视频的目录是最大的（全部集数的分片都在里面），删除同样必须放后台。
        // 原来的 runCatching { } 只吞异常，并不能让主线程不阻塞——这是关键误解点。
        scope.launch {
            dirNames.forEach { dirName ->
                runCatching { File(rootDir(), dirName).deleteRecursively() }
                    .onFailure { AppLog.put("删除视频缓存失败：$dirName", it) }
            }
            refreshVideoGroups()
            // 外部备份在保存目录根部、不在被删的子目录里；这里立即回写两份，
            // 让删除后的记录两边一致（备份不会把已删条目留在里面）
            runCatching { saveNow() }
        }
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
        // 直播由「录制」接管：边看边存那套"按清单分片数补齐再合并"会把滑动窗口
        // 里这几片当成一整集下完，和录制协程抢同一个目录，必须让开。
        if (isLiveTask(task)) return
        // 边看边存同样是"这个视频有下载目录"的信号，封面一并落地
        saveCoverAsync(task)
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
        if (isLiveTask(task)) return
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
        val dir = File(rootDir(), name)
        val t = title.replace(AppPattern.fileNameRegex, "").ifBlank { "第${index + 1}集" }
        // 合并成品优先
        val mp4 = File(dir, "$t.mp4")
        if (mp4.exists() && mp4.length() > 0) return mp4
        // 分片缓存文件夹：有分片即视为已缓存
        val epFolder = epFolderOf(dir, t)
        // 录制中的直播目录清单是逐轮追加的，分片序号与清单行号不一定对齐，
        // 不能按 local.m3u8 播；等用户点「下载完成」合成 mp4 之后再看。
        if (File(epFolder, LIVE_META_FILE).exists()) return null
        if (epFolder.isDirectory &&
            epFolder.listFiles().orEmpty().any { isSegFileName(it.name) }
        ) {
            // 换名前建的老目录没有 .nomedia，里面的 .ts 仍在污染相册，顺手补上
            ensureNoMedia(epFolder)
            return buildLocalPlaylist(epFolder)
        }
        return null
    }

    private fun dirOf(task: Task): File = File(
        rootDir(),
        task.book.name.replace(AppPattern.fileNameRegex, "").ifBlank { "未命名" }
    )

    private fun titleOf(task: Task): String =
        task.chapter.title.replace(AppPattern.fileNameRegex, "").ifBlank { "第${task.index + 1}集" }

    // ---------- 封面本地化 ----------
    // 线上封面 URL 常带防盗链/时效签名，APP 重启后重新联网拉经常失败
    // （表现为下载记录里多集视频刷不出封面）。所以封面一旦拿到就存进
    // 视频自己的下载目录，之后一律优先用本地文件，跟着视频走、永远刷得出来。

    /** 封面在下载目录里的落地文件：Download/A保存视频/<dirName>/cover.jpg */
    private fun coverFileOf(dirName: String): File = File(rootDir(), "$dirName/cover.jpg")

    /**
     * 校验文件头是不是真图片（JPEG / PNG / GIF / WEBP 魔数）。
     * 有些站点的封面地址返回 200 但内容是防盗链 HTML 页或空壳，
     * 不校验就会把垃圾字节存成 cover.jpg——之后列表优先用它、解码失败，
     * 表现为"历史记录有封面、下载记录没有"。所以本地封面采用前必须过这道检查。
     */
    private fun isImageFile(f: File): Boolean = runCatching {
        if (!f.isFile || f.length() < 12L) return false
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(12)
            r.readFully(b)
            val g = { i: Int -> b[i].toInt() and 0xFF }
            when {
                g(0) == 0xFF && g(1) == 0xD8 -> true                                    // JPEG
                g(0) == 0x89 && g(1) == 0x50 && g(2) == 0x4E && g(3) == 0x47 -> true    // PNG
                g(0) == 0x47 && g(1) == 0x49 && g(2) == 0x46 -> true                    // GIF
                g(0) == 0x52 && g(1) == 0x49 && g(8) == 0x57 && g(9) == 0x45 -> true    // WEBP
                else -> false
            }
        }
    }.getOrDefault(false)

    /**
     * 已保存的本地封面（必须是真图片），返回绝对路径供图片加载器直接读；
     * 文件不存在或不是有效图片一律返回 null——回退到线上 URL 方式取封面
     * （与历史记录同一链路，历史里能显示的这里也能显示）。
     */
    private fun localCoverOf(dirName: String): String? = runCatching {
        coverFileOf(dirName).takeIf { isImageFile(it) }?.absolutePath
    }.getOrNull()

    /**
     * 书架封面用：该视频若进过下载（有下载目录且里面已落地 cover.jpg），
     * 返回本地封面路径，否则返回 null——调用方沿用线上 URL 的原逻辑。
     * cover.jpg 只在入队下载/边看边存/恢复记录时写入，存在即代表与下载器有过交集。
     */
    fun bookshelfLocalCover(book: Book): String? = localCoverOf(dirNameOf(book))

    /**
     * 把封面保存到视频的下载目录（幂等：已存过有效图片直接跳过，不产生流量）。
     * 入队下载、边看边存、恢复下载记录时各调一次，尽早把封面落成本地文件。
     * 下载结果必须通过图片魔数校验才落地，防止把防盗链页面存成封面。
     */
    fun saveCoverAsync(task: Task) {
        val coverUrl = task.book.getDisplayCover()?.takeIf { it.isNotBlank() } ?: return
        val target = coverFileOf(dirOf(task).name)
        if (isImageFile(target)) return
        scope.launch {
            runCatching {
                val request = okhttp3.Request.Builder().url(coverUrl).apply {
                    getHeaders(task.source).forEach { (k, v) -> header(k, v) }
                }.build()
                okHttpClient.newCall(request).execute().use { resp ->
                    check(resp.isSuccessful) { "HTTP ${resp.code}" }
                    val body = resp.body ?: return@use
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "cover.jpg.part")
                    FileOutputStream(tmp).use { out -> body.byteStream().copyTo(out) }
                    if (isImageFile(tmp)) {
                        if (!tmp.renameTo(target)) tmp.copyTo(target, overwrite = true)
                    }
                    tmp.delete()
                }
            }.onFailure { AppLog.put("保存视频封面失败：${task.book.name}", it) }
            // 落地成功才刷新：让下载记录列表立刻换用本地封面
            if (isImageFile(target)) refreshVideoGroups()
        }
    }

    private fun watchKey(task: Task): String =
        "${dirOf(task).absolutePath}/${titleOf(task)}"

    // ================================================================== 直播录制
    //
    // 点播清单是"一整集的分片表"，下载器数完分片就该结束；直播清单是**滑动窗口**，
    // 每次拉只有最近几片，而且没有 #EXT-X-ENDLIST。旧逻辑把它当点播：拉一次、下完
    // 窗口里那几片、判定 100% 并合并，于是"只下载了一小段就认为完成"。
    //
    // 这里改成录制式：点下载即从当前窗口开始，按 HLS 的绝对媒体序号持续把新分片
    // 追加落盘，直到用户点暂停并选择「下载完成」；暂停本身只是挂起，可继续录。
    // 停止时把累积的清单补上 #EXT-X-ENDLIST，整段合成一个 MP4。

    /** 录制现场（分片目录里的 live.json）：暂停/重启后据此接着录 */
    private data class LiveMeta(
        val mediaUrl: String,
        val baseSeq: Long,
        val lastSeq: Long,
        val durationMs: Long,
        val keyUri: String?,
        val iv: String?,
        val initUri: String?,
    )

    /** 探测结果：章节地址（点播复用）、媒体清单地址（录制用）、是否直播 */
    private data class LiveProbe(
        val chapterUrl: String,
        val mediaUrl: String,
        val isLive: Boolean,
    )

    private data class LiveSegment(val url: String, val tags: List<String>)

    private data class LivePlaylist(
        val endList: Boolean,
        val mediaSequence: Long,
        val targetSeconds: Int,
        val pollMs: Long,
        val keyUri: String?,
        val iv: String?,
        val initUri: String?,
        val segments: List<LiveSegment>,
    )

    private fun readLiveMeta(epFolder: File): LiveMeta? {
        val f = File(epFolder, LIVE_META_FILE)
        if (!f.exists()) return null
        return runCatching {
            val o = org.json.JSONObject(f.readText())
            LiveMeta(
                mediaUrl = o.optString("mediaUrl"),
                baseSeq = o.optLong("baseSeq"),
                lastSeq = o.optLong("lastSeq", -1L),
                durationMs = o.optLong("durationMs"),
                keyUri = o.optString("keyUri").takeIf { it.isNotEmpty() },
                iv = o.optString("iv").takeIf { it.isNotEmpty() },
                initUri = o.optString("initUri").takeIf { it.isNotEmpty() },
            ).takeIf { it.mediaUrl.isNotEmpty() }
        }.getOrNull()
    }

    private fun writeLiveMeta(epFolder: File, m: LiveMeta) {
        runCatching {
            org.json.JSONObject().apply {
                put("mediaUrl", m.mediaUrl)
                put("baseSeq", m.baseSeq)
                put("lastSeq", m.lastSeq)
                put("durationMs", m.durationMs)
                put("keyUri", m.keyUri ?: "")
                put("iv", m.iv ?: "")
                put("initUri", m.initUri ?: "")
            }.let { File(epFolder, LIVE_META_FILE).writeText(it.toString()) }
        }.onFailure { AppLog.put("保存直播录制现场失败", it) }
    }

    /** 这一集是否由录制链路接管（避免边看边存去写同一份分片目录） */
    private fun isLiveTask(task: Task): Boolean {
        if (watchKey(task) in liveRecordingKeys) return true
        val dir = dirOf(task)
        val title = titleOf(task)
        if (File(epFolderOf(dir, title), LIVE_META_FILE).exists()) return true
        // 已经归档过（标题001.mp4 等）说明这是直播：合成后 epFolder 会被清掉，
        // 若这里返回 false，边看边存就会按点播把它下成 <标题>.mp4 —— 那正是
        // 「重新打开直播被本地缓存挡住」的老问题。再次保存应走录制链路生成 002。
        return hasLiveArchive(dir, title)
    }

    /**
     * 探测是否直播 HLS，返回可直接拉取的媒体清单地址。
     *
     * 只有「m3u8 媒体清单 + 没有 #EXT-X-ENDLIST」才算直播；主清单先取最高码率的子清单。
     * 任何一步失败都返回 null，交回原点播下载链路，不额外制造失败。
     */
    private suspend fun probeLivePlaylist(task: Task): LiveProbe? {
        if (File(dirOf(task), "${titleOf(task)}.mp4").exists()) return null
        // 已有录制现场：即使这次清单带了 ENDLIST（直播刚结束），也要回到录制循环，
        // 由它把最后一段收尾合成；不能在这儿退回点播下载去猜分片数。
        val savedMeta = readLiveMeta(epFolderOf(dirOf(task), titleOf(task)))
        val resumeLive = savedMeta != null
        val url = runCatching {
            VideoSourcePlayer.resolveChapterUrl(task.book, task.source, task.chapter)
        }.getOrNull()?.takeIf { it.startsWith("http", true) }
            // 续录时现场解析失败（书源接口抖动）也能用上次记下的清单地址接着录
            ?: savedMeta?.mediaUrl?.takeIf { it.isNotEmpty() }
            ?: return null
        // 解析结果带回给 downloadCore 复用，点播不必再跑一遍书源正文规则
        if (!(url.contains(".m3u8", true) || url.contains("/m3u8/", true))) {
            return LiveProbe(url, url, false)
        }
        val headers = getHeaders(task.source)
        var mediaUrl = url
        var text = runCatching { fetchText(mediaUrl, headers) }.getOrNull()
            ?: return LiveProbe(url, url, false)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            var best: String? = null
            var bestBw = -1L
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val next = lines.getOrNull(i + 1)?.trim()
                if (!next.isNullOrBlank() && !next.startsWith("#") && bw >= bestBw) {
                    bestBw = bw
                    best = next
                }
            }
            val variant = best ?: return LiveProbe(url, url, false)
            mediaUrl = NetworkUtils.getAbsoluteURL(mediaUrl, variant)
            text = runCatching { fetchText(mediaUrl, headers) }.getOrNull()
                ?: return LiveProbe(url, url, false)
        }
        if (resumeLive) return LiveProbe(url, mediaUrl, true)
        val isLive = !text.contains("#EXT-X-ENDLIST") &&
            !text.contains("#EXT-X-PLAYLIST-TYPE:VOD", true) &&
            text.lines().any { it.isNotBlank() && !it.trim().startsWith("#") }
        return LiveProbe(url, mediaUrl, isLive)
    }

    /** 启动独立录制协程；返回 false 表示这一集已经在录了 */
    private fun startLiveRecording(task: Task, key: String, mediaUrl: String): Boolean {
        if (!liveRecordingKeys.add(key)) return false
        liveFinishKeys.remove(key)
        pausedKeys.remove(key)
        updateState(key) { it.copy(status = DlStatus.RUNNING, isLive = true, progress = 0) }
        scope.launch {
            val epFolder = epFolderOf(dirOf(task), titleOf(task))
            var owned = false
            try {
                // 边看边存那一轮收集可能还在写这个目录，稍等它收工再接管
                var waited = 0
                while (waited < 20 && !owned) {
                    synchronized(activePaths) { owned = activePaths.add(epFolder.path) }
                    if (!owned) {
                        delay(200L)
                        waited++
                    }
                }
                recordLiveHls(task, key, mediaUrl)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("直播录制中断：${titleOf(task)}", e)
                appCtx.toastOnUi("直播录制中断：${e.localizedMessage}")
                updateState(key) { it.copy(status = DlStatus.FAILED) }
            } finally {
                if (owned) synchronized(activePaths) { activePaths.remove(epFolder.path) }
                liveRecordingKeys.remove(key)
                // 极端竞态：循环刚退出时用户才点「完成」，这里补一次收尾
                if (liveFinishKeys.remove(key)) {
                    scope.launch {
                        runCatching { finalizeLiveRecording(task, key) }
                    }
                }
            }
        }
        return true
    }

    /** 持续拉直播清单，把新分片按绝对媒体序号追加落盘 */
    private suspend fun recordLiveHls(task: Task, key: String, mediaUrl: String) {
        val dir = dirOf(task)
        val title = titleOf(task)
        val epFolder = epFolderOf(dir, title)
        epFolder.mkdirs()
        ensureNoMedia(epFolder)
        val headers = getHeaders(task.source)
        val saved = readLiveMeta(epFolder)
        var meta = if (saved == null) {
            // 首次录制：清掉边看边存可能留下的点播式清单/分片，从零开始录
            epFolder.listFiles()?.forEach { if (it.name != NO_MEDIA) it.deleteRecursively() }
            LiveMeta(mediaUrl, 0L, -1L, 0L, null, null, null)
        } else {
            // 地址常带时效 token：续录用本次新解析的地址，序号与已录时长沿用现场
            saved.copy(mediaUrl = mediaUrl)
        }
        writeLiveMeta(epFolder, meta)
        val playlistFile = File(epFolder, "playlist.m3u8")
        val keyCache = HashMap<String, ByteArray>()
        var fetchFails = 0
        var paused = false
        var finished = false
        try {
            while (true) {
                checkPaused(key)
                if (key in liveFinishKeys) {
                    finished = true
                    break
                }
                val text: String? = try {
                    val t = fetchText(meta.mediaUrl, headers)
                    fetchFails = 0
                    t
                } catch (e: Exception) {
                    fetchFails++
                    if (fetchFails >= LIVE_MAX_FETCH_FAIL) {
                        throw IllegalStateException("直播清单连续拉取失败", e)
                    }
                    waitForNextPoll(key, 1000L)
                    null
                }
                if (text == null) continue
                val pl = parseLivePlaylist(text, meta.mediaUrl)
                if (pl == null) {
                    waitForNextPoll(key, 1000L)
                    continue
                }
                // 换了密钥：已录分片是旧密钥加密的，混着继续录会花屏，到此收尾。
                // 只比路径不比 query——key 地址常带一次性 token，不能当成换了密钥。
                val oldKey = meta.keyUri
                val newKey = pl.keyUri
                if (meta.lastSeq >= 0 && oldKey != null && newKey != null &&
                    newKey.substringBefore('?') != oldKey.substringBefore('?')
                ) {
                    finished = true
                    break
                }
                if (meta.lastSeq < 0) {
                    meta = meta.copy(
                        baseSeq = pl.mediaSequence,
                        keyUri = pl.keyUri,
                        iv = pl.iv,
                        initUri = pl.initUri,
                    )
                    val liveKeyUrl = pl.keyUri
                    if (liveKeyUrl != null) {
                        runCatching {
                            File(epFolder, "key.bin").writeBytes(
                                fetchBytes(liveKeyUrl, headers, write = false)
                            )
                        }
                    }
                    val liveInitUrl = pl.initUri
                    if (liveInitUrl != null) {
                        runCatching {
                            File(epFolder, "init.bin").writeBytes(
                                fetchBytes(liveInitUrl, headers, write = false)
                            )
                        }
                    }
                    writePlaylistHeader(playlistFile, meta, pl.targetSeconds)
                    writeMetaJson(epFolder, meta)
                }
                var appended = false
                for ((rel, seg) in pl.segments.withIndex()) {
                    checkPaused(key)
                    if (key in liveFinishKeys) {
                        finished = true
                        break
                    }
                    val absSeq = pl.mediaSequence + rel
                    // 同一个绝对序号只录一次：滑动窗口里重复出现的旧片直接跳过
                    if (absSeq <= meta.lastSeq) continue
                    val localIndex = (absSeq - meta.baseSeq).toInt()
                    if (localIndex < 0) continue
                    val dst = segFile(epFolder, localIndex)
                    if (findSegFile(epFolder, localIndex) == null) {
                        val ok = runCatching {
                            fetchSegmentWithRetry(
                                Triple(seg.url, pl.iv, pl.keyUri),
                                meta.mediaUrl, headers, absSeq, keyCache, dst, key
                            )
                        }.onFailure { e ->
                            if (e is PausedException) throw e
                            AppLog.putDebug("直播分片 #$absSeq 丢失：${e.localizedMessage}")
                            File(epFolder, "${dst.name}.part").delete()
                            dst.delete()
                        }.isSuccess
                        // 单片失败只留空洞，不复用这个序号：aesCipher 的 IV 依赖真实媒体序号
                        if (!ok) continue
                    }
                    // 清单按录制顺序累积；全局标签（KEY/MAP/SEQUENCE）已在头部写过
                    val tags = seg.tags.filter {
                        it.startsWith("#EXTINF") ||
                            it.startsWith("#EXT-X-DISCONTINUITY") ||
                            it.startsWith("#EXT-X-BYTERANGE") ||
                            it.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                    }
                    if (tags.isNotEmpty()) playlistFile.appendText(tags.joinToString("\n", postfix = "\n"))
                    playlistFile.appendText(seg.url + "\n")
                    meta = meta.copy(
                        lastSeq = absSeq,
                        durationMs = meta.durationMs + extInfMs(seg.tags),
                    )
                    appended = true
                }
                writeLiveMeta(epFolder, meta)
                if (finished) break
                updateState(key) {
                    it.copy(status = DlStatus.RUNNING, isLive = true, recordedMs = meta.durationMs)
                }
                // 直播自然结束（清单出现 ENDLIST）：同样把这一整段合成成品
                if (pl.endList) {
                    finished = true
                    break
                }
                waitForNextPoll(key, if (appended) 300L else pl.pollMs)
            }
        } catch (e: PausedException) {
            paused = true
        }
        if (paused) {
            writeLiveMeta(epFolder, meta)
            updateState(key) {
                it.copy(status = DlStatus.PAUSED, isLive = true, recordedMs = meta.durationMs)
            }
            appCtx.toastOnUi("直播录制已暂停，可继续录制或选择「完成并合成」")
            return
        }
        if (finished) finalizeLiveRecording(task, key)
    }

    /** 等待下一轮拉清单，期间随时响应暂停/完成（不能超过 200ms 才生效） */
    private suspend fun waitForNextPoll(key: String, ms: Long) {
        var left = ms.coerceAtLeast(0L)
        while (left > 0) {
            checkPaused(key)
            if (key in liveFinishKeys) return
            val step = 200L.coerceAtMost(left)
            delay(step)
            left -= step
        }
    }

    /** 解析直播媒体清单：分片地址绝对化，保留每片前面的 EXTINF / DISCONTINUITY 等标签 */
    private fun parseLivePlaylist(text: String, baseUrl: String): LivePlaylist? {
        var endList = false
        var mediaSequence = 0L
        var targetSeconds = 6
        var keyUri: String? = null
        var iv: String? = null
        var initUri: String? = null
        val segments = ArrayList<LiveSegment>()
        var pending = ArrayList<String>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.isEmpty() -> Unit
                l.startsWith("#EXT-X-ENDLIST") -> endList = true
                l.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    mediaSequence = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-TARGETDURATION") ->
                    targetSeconds = (l.substringAfter(':').trim().toIntOrNull() ?: 6).coerceAtLeast(1)
                l.startsWith("#EXT-X-KEY") -> {
                    val method = parseAttribute(l, "METHOD")
                    if (method.equals("NONE", true)) {
                        keyUri = null
                        iv = null
                    } else {
                        keyUri = parseAttribute(l, "URI")?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                        iv = parseAttribute(l, "IV")
                    }
                }
                l.startsWith("#EXT-X-MAP") ->
                    initUri = parseAttribute(l, "URI")?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                l.startsWith("#EXTM3U") || l.startsWith("#EXT-X-VERSION") ||
                    l.startsWith("#EXT-X-PLAYLIST-TYPE") ||
                    l.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") ||
                    l.startsWith("#EXT-X-ALLOW-CACHE") -> Unit
                l.startsWith("#") -> pending.add(l)
                else -> {
                    segments.add(LiveSegment(NetworkUtils.getAbsoluteURL(baseUrl, l), pending))
                    pending = ArrayList()
                }
            }
        }
        if (segments.isEmpty()) return null
        val pollMs = (targetSeconds * 1000L / 2).coerceIn(500L, 3000L)
        return LivePlaylist(endList, mediaSequence, targetSeconds, pollMs, keyUri, iv, initUri, segments)
    }

    /** 写录制清单的头部（只在首轮写一次，之后的分片行都 append） */
    private fun writePlaylistHeader(out: File, meta: LiveMeta, targetSeconds: Int) {
        val sb = StringBuilder("#EXTM3U\n#EXT-X-VERSION:3\n")
            .append("#EXT-X-TARGETDURATION:").append(targetSeconds).append('\n')
            .append("#EXT-X-MEDIA-SEQUENCE:").append(meta.baseSeq).append('\n')
        if (meta.keyUri != null) {
            sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"").append(meta.keyUri).append('"')
            meta.iv?.let { sb.append(",IV=").append(it) }
            sb.append('\n')
        }
        if (meta.initUri != null) {
            sb.append("#EXT-X-MAP:URI=\"").append(meta.initUri).append("\"\n")
        }
        out.writeText(sb.toString())
    }

    /** 合并器按 meta.seq + 文件名序号还原 AES IV，seq 必须是首片的绝对媒体序号 */
    private fun writeMetaJson(epFolder: File, meta: LiveMeta) {
        org.json.JSONObject().apply {
            put("seq", meta.baseSeq)
            if (meta.iv != null) put("iv", meta.iv) else put("iv", org.json.JSONObject.NULL)
            put("encrypted", meta.keyUri != null)
        }.let { File(epFolder, "meta.json").writeText(it.toString()) }
    }

    private fun extInfMs(tags: List<String>): Long {
        val line = tags.firstOrNull { it.startsWith("#EXTINF") } ?: return 0L
        val seconds = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
            ?: return 0L
        return (seconds * 1000).toLong()
    }

    /** 正在收尾合成的直播，防止两条路径并发进来各消耗一个归档序号 */
    private val liveFinalizingKeys: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 「下载完成」：把累积清单收成 VOD，再把已录分片整体合成一个 MP4 */
    private suspend fun finalizeLiveRecording(task: Task, key: String) {
        // 录制循环里的 finished 分支、按钮回调、循环 finally 的补刀可能同时到达，
        // 重复合成会白吃掉 001/002 这样的序号，还会把同一批分片合成两份。
        if (!liveFinalizingKeys.add(key)) return
        try {
            finalizeLiveRecordingOnce(task, key)
        } finally {
            liveFinalizingKeys.remove(key)
        }
    }

    private suspend fun finalizeLiveRecordingOnce(task: Task, key: String) {
        val dir = dirOf(task)
        val title = titleOf(task)
        val epFolder = epFolderOf(dir, title)
        if (epFolder.listFiles().orEmpty().none { isSegFileName(it.name) }) {
            updateState(key) {
                it.copy(status = DlStatus.FAILED, isLive = false, mergeProgress = -1)
            }
            appCtx.toastOnUi("没有录到分片，无法合成：$title")
            return
        }
        val pl = File(epFolder, "playlist.m3u8")
        if (pl.exists() && !pl.readText().contains("#EXT-X-ENDLIST")) {
            pl.appendText("\n#EXT-X-ENDLIST\n")
        }
        liveFinishKeys.remove(key)
        updateState(key) {
            it.copy(status = DlStatus.MERGING, isLive = false, mergeProgress = 0)
        }
        val mp4 = mergeMutex.withLock {
            // 序号必须和合成一起串行：两次「完成并合成」并发时会拿到同一个号、互相覆盖。
            // 成品刻意不叫 <标题>.mp4 —— 那一整套「已有缓存」判定只认原名，
            // 用带序号的名字，用户重新打开直播时不会被这份录像挡住，能继续看直播；
            // 下次再录再合成则自动顺延到 002、003…
            val archiveName = nextLiveArchiveFile(dir, title).name
            runCatching {
                mergeSegFolder(dir, title, key, deleteSegments = true, outputName = archiveName)
            }.getOrNull()
        }
        if (mp4 != null) {
            // 合成成功时整个分片目录（含 live.json）已被清掉，这里兜底再删一次
            File(epFolder, LIVE_META_FILE).delete()
            updateState(key) {
                it.copy(
                    status = DlStatus.COMPLETED, progress = 100,
                    mergeProgress = -1, isLive = false
                )
            }
            // 不调 notifyCompleted：这次产物是带序号的归档，不是「这一集已缓存」，
            // 不该让播放会话把当前这一集切成本地文件（用户还要继续看直播）。
            appCtx.toastOnUi("直播录制完成：${mp4.name}", Toast.LENGTH_LONG)
        } else {
            updateState(key) {
                it.copy(status = DlStatus.FAILED, mergeProgress = -1, isLive = true)
            }
            appCtx.toastOnUi("合成失败：$title", Toast.LENGTH_LONG)
        }
    }

    private suspend fun download(task: Task) {
        val key = watchKey(task)
        // 直播：交给独立协程长期录制，立刻腾出串行 worker 给其它选集
        val probe = probeLivePlaylist(task)
        val live = probe?.takeIf { it.isLive }
        if (live != null) {
            if (startLiveRecording(task, key, live.mediaUrl)) {
                appCtx.toastOnUi(
                    "开始录制直播：${task.chapter.title}\n点暂停可选择「仅暂停」或「完成并合成」",
                    Toast.LENGTH_LONG
                )
            }
            return
        }
        val title = titleOf(task)
        // 重排导致的二次进入不算"新开始"，不重复弹「开始下载」
        val isRetry = busyRetries.containsKey(key)
        ensureState(task)
        if (!isRetry) appCtx.toastOnUi("开始下载：${task.chapter.title}")
        runCatching {
            downloadCore(
                task, dirOf(task), title, knownUrl = null, key = key, collectOnly = false,
                resolvedUrl = probe?.chapterUrl,
            ) { p ->
                updateState(key) { it.copy(progress = p) }
            }
        }
            .onSuccess { file ->
                busyRetries.remove(key)
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
                    is PausedException -> {
                        busyRetries.remove(key)
                        updateState(key) { it.copy(status = DlStatus.PAUSED) }
                    }
                    // 撞上边看边存的占用：不是失败，等这一轮收集跑完再来。
                    // 放队首而不是队尾——它已经排过一次队了，不该被后来的任务压到后面。
                    is SegmentBusyException -> {
                        val n = (busyRetries[key] ?: 0) + 1
                        if (n > MAX_BUSY_REQUEUE) {
                            busyRetries.remove(key)
                            AppLog.put("下载失败（该集长期被占用）：${task.chapter.title}", e)
                            updateState(key) { it.copy(status = DlStatus.FAILED) }
                            appCtx.toastOnUi("下载失败：${task.chapter.title}\n该选集正在下载中")
                        } else {
                            busyRetries[key] = n
                            updateState(key) { it.copy(status = DlStatus.QUEUED) }
                            AppLog.putDebug("该集正忙，${BUSY_REQUEUE_DELAY_MS}ms 后重试（第 $n 次）：$title")
                            delay(BUSY_REQUEUE_DELAY_MS)
                            synchronized(manualQueue) { manualQueue.addFirst(task) }
                        }
                    }
                    else -> {
                        busyRetries.remove(key)
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
        /** 探测阶段已解析好的章节地址，避免再跑一次书源正文规则 */
        resolvedUrl: String? = null,
        onProgress: (Int) -> Unit = {},
    ): File? {
        val mp4 = File(dir, "$title.mp4")
        if (mp4.exists() && mp4.length() > 0) {
            notifyCompleted(task) // 已有合并成品：通知会话改用本地播放，不再联网
            return mp4
        }

        // 1. 直链：优先用已知的（播放中已在播的地址），否则现场解析
        val url = knownUrl?.takeIf { it.startsWith("http") }
            ?: resolvedUrl?.takeIf { it.startsWith("http") }
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
                // collectOnly（边播边存收集）撞车：静默跳过，本轮不收集就是了；
                // 手动下载撞车：抛 [SegmentBusyException]，由 download() 重排队尾再试
                // ——它不是失败，只是这一集正被边看边存占用。
                if (collectOnly) throw BusyException()
                throw SegmentBusyException()
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
                // 分片已全部落盘：自动合并为 MP4 并清理分片文件夹
                // （成品 <标题>.mp4 优先于分片播放，缓存体积直接减半以上）
                val merged = mergeSegFolder(dir, title, key, deleteSegments = true)
                notifyCompleted(task)
                return merged ?: epFolder
            } else {
                // 单文件直链：先写 <标题>.mp4.part，写满再改名成 <标题>.mp4。
                //
                // 早先直接往成品名上写，下载到一半被杀进程就会留下一个半截的 .mp4；
                // 而恢复逻辑（以及 downloadCore 开头的快速返回）都以"mp4 存在"当作
                // 已完成，于是这一集被永久判定成下完了——和 m3u8 只下了一半
                // 却被判完成是同一类错误。改名是原子的，成品出现即代表完整。
                val part = File(dir, "$title.mp4.part")
                val (total, written) = downloadDirect(url, headers, part, key = key, collectOnly, onProgress)
                if (total <= 0 || written < total) return null
                if (!part.renameTo(mp4)) {
                    // 跨存储/改名失败时兜底复制，复制成功才删临时文件
                    runCatching { part.copyTo(mp4, overwrite = true); part.delete() }
                        .onFailure { AppLog.put("直链下载改名失败：$title", it) }
                }
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
        // 分片已经不带 .ts 后缀，但换名前的老目录、以及用户手动拷进来的文件
        // 仍会被相册扫到，放一个 .nomedia 双保险（幂等，重复调用无开销）
        ensureNoMedia(epFolder)
        // ===== 完整缓存保护 =====
        // 上次的分片已经**全部落盘**时（哪怕这次拿到的密钥和当时不同），这份缓存
        // 自带旧密钥（key.bin/meta.json），自成一体、可以正常播放——直接按
        // "已完成"返回，一个分片都不动，也不联网。只有分片**缺失**的才继续往下走：
        // 那种情况如果密钥变了，旧分片必须整集清掉重下（新密钥解不开旧密钥
        // 加密的分片，混用整集花屏），见下方 key.bin 比对处。
        // 判定完全用磁盘现状（旧 playlist.m3u8 + 逐片核对），发生在拉取新清单之前，
        // 所以完整缓存连一次网络请求都不发。
        if (resume) {
            val done = hlsSegProgress(epFolder)
            if (done != null && done.first > 0 && done.second >= done.first) {
                AppLog.putDebug("分片已完整，跳过下载：${epFolder.name}（${done.first} 片）")
                return done
            }
        }
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
            val bytes = fetchBytes(keyUrl, headers, write = false)
            val keyFile = File(epFolder, "key.bin")
            // 密钥和上一轮不一样：已下的分片是用旧密钥加密的，留着也解不开，
            // 而且旧密钥会把新的顶掉，让整集都播不了。清空重下——
            // 缓存一半后加密过期、重新解析再来时就会走到这里。
            // （分片**完整**的缓存走不到这里：入口处的"完整缓存保护"已直接按
            //   已完成返回，旧密钥那份缓存原样保留。）
            if (keyFile.exists() && !keyFile.readBytes().contentEquals(bytes)) {
                AppLog.put("密钥已变化，重新下载该选集：${epFolder.name}")
                epFolder.listFiles()
                    ?.filter { it.name.startsWith("seg_") }
                    ?.forEach { it.delete() }
                File(epFolder, "index.idx").delete()
            }
            runCatching { keyFile.writeBytes(bytes) }
        } else {
            // 这一轮没有加密（METHOD=NONE），旧密钥必须删掉：
            // buildLocalPlaylist 见到 key.bin 存在就会把清单里的 KEY 行换成本地文件，
            // 未加密的分片被按加密解，结果是整集花屏
            File(epFolder, "key.bin").delete()
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

        // 已判定为广告的分片序号（上次下载留下来的记录）：既不再下载，
        // 也不写进 local.m3u8——否则缺失会回退成原始 http 地址，广告等于又回来了
        val adSkipped = loadSkippedAds(epFolder).toMutableMap()

        // 断点续传：已存在的非空分片直接跳过（best-effort，避免重下几十 MB 的分片）
        val need = (0 until total).filter {
            it !in adSkipped &&
                !(findSegFile(epFolder, it) != null)
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
                            fetchSegmentWithRetry(
                                Triple(segUrls[k], ivHex, keyUri),
                                playlistUrl, headers, mediaSequence + k,
                                keyCache, segFile(epFolder, k), key = key
                            )
                            // 落盘后立刻比对广告指纹：数据已经在硬盘上，
                            // 只读前 192KB 就能判定，不额外联网也不牵扯 SimpleCache 的锁。
                            // 局部变量刻意不叫 segFile——那会遮蔽同名的 segFile() 方法。
                            val writtenSeg = segFile(epFolder, k)
                            val fp = if (writtenSeg.exists() && writtenSeg.length() > 0) {
                                AdFilter.matchedFingerprint(writtenSeg, hostOfUrl(segUrls[k]))
                            } else {
                                null
                            }
                            if (fp != null) {
                                // 剔除并记下依据：把命中的指纹写进 skipped-ads.json，
                                // 这样用户事后撤销标记（删样本）时，剔除能跟着一起失效
                                writtenSeg.delete()
                                synchronized(adSkipped) { adSkipped[k] = fp }
                                AppLog.putDebug("下载已剔除广告分片：#$k ${segUrls[k]}")
                            }
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
            // 中断/异常时清掉没下完的半截分片，别在文件夹里留坏文件。
            // 正常路径下分片是「写 .part 再改名」，不会留下 0 字节的 seg_；
            // 这里真正要清的是进程被杀时来不及删掉的 .part 临时文件——
            // 它们不影响续传判据（名字带 .part，匹配不上 seg_%06d.<后缀>），
            // 但会白占磁盘，一集几百片时累积可观。
            for (k in todo) {
                val f = segFile(epFolder, k)
                File(epFolder, "${f.name}.part").delete()
                // 老后缀的半截文件也要清（换名前中断的下载会留下 0 字节的 .ts）
                findSegFile(epFolder, k)?.takeIf { it.length() == 0L }?.delete()
            }
        }
        errorRef.get()?.let { throw it }
        onProgress(100)
        saveSkippedAds(epFolder, adSkipped)
        // 全部补齐：临时索引清掉（playlist/meta 保留供播放与合并）
        if ((0 until total).all {
                it in adSkipped ||
                    (segFile(epFolder, it).exists() && segFile(epFolder, it).length() > 0)
            }
        ) {
            File(epFolder, "index.idx").delete()
            // ===== 补齐后必须重写 local.m3u8 =====
            //
            // 下载过程写的是 playlist.m3u8（原始 http 地址），而**播放读的是
            // local.m3u8**（分片指向本地文件），后者只在 findLocalFile() 里生成，
            // 也就是"打开播放页加载剧集列表"时才会刷一次。
            // 边看边存期间它很容易被生成成一份只含部分分片的残缺版本，完成后没人再更新，
            // 于是缓存完立刻点播放会照那份残缺清单走（缺的分片回填成网络地址、
            // 广告片也还在里面），表现为必须重启 APP 才能播——重启后重新 load
            // 才会再生成一份完整的。
            runCatching { buildLocalPlaylist(epFolder) }
                .onFailure { AppLog.putDebug("重建 local.m3u8 失败：${epFolder.name}", it) }
        }
        // 完成数以磁盘实际落盘为准（不依赖 writtenCount 计数），避免并发/超时造成的漏算。
        // 被判广告而剔除的片段算"已完成"——它本来就不该出现在成品里。
        val written = (0 until total).count {
            it in adSkipped || findSegFile(epFolder, it) != null
        }
        return total to written
    }

    // ------------------------------------------------------------------ 广告剔除记录

    /** 被判定为广告、已从本次下载里剔除的分片序号 */
    private fun skippedAdsFile(epFolder: File): File = File(epFolder, "skipped-ads.json")

    /**
     * 播放遇到坏分片时的兜底：返回一份「少了那一片」的清单给播放器，让它从下一片继续。
     *
     * **不落任何记录**：坏片既不写进分片目录、也不进任何名单，每次都是现场剔除。
     * 这是刻意的——分片取不到常常是临时的（CDN 节点故障、限流、token 过期），
     * 记下来反而会让"其实已经好了"的分片被永久跳过。下次播到那儿再试一次，
     * 好了就正常播，还坏就再跳一次。
     *
     * 生成的清单放在 cacheDir（临时目录，24 小时后自动清），不动 `Download/A保存视频/`
     * 里的任何东西。清单内容仍由 [buildLocalPlaylist] 产出：已缓存的分片走本地文件，
     * 其余保留原始 http 地址由播放器实时取，只是少了那一片。
     *
     * @param playlistUri 当前播放的清单地址（file:// 或 http://）
     * @param badSegmentUrl 出错的分片地址
     * @return 跳过版清单的 `file://` 地址；null 表示这条路走不通（调用方照旧报错）
     */
    suspend fun skipSegmentAndRebuildPlaylist(
        playlistUri: String,
        badSegmentUrls: Set<String>,
        headers: Map<String, String>,
    ): String? = withContext(Dispatchers.IO) {
        try {
            runCatching {
                val root = File(appCtx.cacheDir, "video_skipfix")
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                root.listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
            }
            val dir = File(appCtx.cacheDir, "video_skipfix/${playlistUri.hashCode()}")
            // 清单文本从哪来：
            // - 播在线清单 → 用 downloadHls 的 collectOnly 模式把它（连同密钥/初始化段）
            //   落到临时目录。collectOnly 只搬缓存里已有的分片，这步不产生下载流量。
            // - 播本地清单 → 它的分片目录里本来就有下载时落下的 playlist.m3u8，
            //   直接拿来用即可（indexOfSegmentInPlaylist 与本地地址都要靠它）。
            if (!playlistUri.startsWith("file:") && !File(dir, "playlist.m3u8").exists()) {
                if (!playlistUri.startsWith("http", true)) return@withContext null
                // key 传空串：这只是一个临时目录，不存在"暂停"这回事
                downloadHls(playlistUri, headers, dir, resume = true, key = "", collectOnly = true) { }
            }
            if (playlistUri.startsWith("file:")) {
                // 播的是本地清单：把原始清单复制到临时目录，下面的剔除只在副本上做，
                // 绝不能改 Download/A保存视频/ 里的原件
                val src = Uri.parse(playlistUri).path?.let { File(it).parentFile }
                    ?: return@withContext null
                val srcPlaylist = File(src, "playlist.m3u8")
                if (!srcPlaylist.exists()) return@withContext null
                dir.mkdirs()
                srcPlaylist.copyTo(File(dir, "playlist.m3u8"), overwrite = true)
            }
            // 一次把所有已知坏片都摘掉。只摘最新那一片会在两片坏片之间来回反复：
            // 摘掉 A 后播到 B 失败，再生成的清单只摘 B、把 A 放回来了，于是死循环。
            val indices = badSegmentUrls.mapNotNull {
                indexOfSegmentInPlaylist(dir, it).takeIf { i -> i >= 0 }
            }.toSet()
            if (indices.isEmpty()) return@withContext null
            val out = File(dir, "skip.m3u8")
            buildLocalPlaylist(dir, extraSkip = indices, out = out)
            if (!out.exists() || out.length() == 0L) return@withContext null
            Uri.fromFile(out).toString()
        } catch (e: Exception) {
            AppLog.putDebug("坏分片跳过失败：${e.localizedMessage}")
            null
        }
    }

    private fun loadSkippedAds(epFolder: File): Map<Int, AdFilter.Fingerprint?> =
        runCatching<Map<Int, AdFilter.Fingerprint?>> {
        val f = skippedAdsFile(epFolder)
        if (!f.exists()) return@runCatching emptyMap()
        val obj = org.json.JSONObject(f.readText())
        // 兼容早期只记序号的格式：这类没有指纹依据，值给 null
        obj.optJSONArray("indexes")?.let { legacy ->
            return@runCatching (0 until legacy.length())
                .map { legacy.optInt(it, -1) }
                .filter { it >= 0 }
                .associateWith { null as AdFilter.Fingerprint? }
        }
        val arr = obj.optJSONArray("items") ?: return@runCatching emptyMap()
        buildMap {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val index = o.optInt("index", -1)
                if (index < 0) continue
                val size = o.optLong("size", -1L)
                val hash = o.optString("hash")
                // 指纹已被撤销（用户删了样本）→ 这条剔除自动失效
                if (!AdFilter.isFingerprintAlive(size, hash)) continue
                put(
                    index,
                    AdFilter.Fingerprint(
                        file = "",
                        size = size,
                        hash = hash,
                        host = null,
                    )
                )
            }
        }
    }.getOrDefault(emptyMap())

    private fun saveSkippedAds(epFolder: File, skipped: Map<Int, AdFilter.Fingerprint?>) {
        runCatching {
            val arr = org.json.JSONArray()
            skipped.toSortedMap().forEach { (index, fp) ->
                if (fp == null) return@forEach
                arr.put(org.json.JSONObject().apply {
                    put("index", index)
                    put("size", fp.size)
                    put("hash", fp.hash)
                })
            }
            skippedAdsFile(epFolder).writeText(
                org.json.JSONObject().apply { put("items", arr) }.toString()
            )
        }.onFailure { AppLog.putDebug("广告剔除记录写入失败") }
    }

    // ------------------------------------------------------ 播放本地缓存时的广告标记

    /**
     * 求一个分片在**原始 playlist.m3u8** 里的序号（skipped-ads.json 用原始序号）。
     * 本地分片直接从文件名 `seg_%06d.<后缀>` 取；未下载回填的 http 分片按地址行匹配
     * （playlist.m3u8 存的就是绝对地址）。
     */
    fun indexOfSegmentInPlaylist(epFolder: File, url: String): Int {
        if (url.startsWith("file:")) {
            return Regex("""seg_(\d+)\.(seg|ts)$""").find(Uri.parse(url).path ?: "")
                ?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
        val playlist = runCatching { File(epFolder, "playlist.m3u8").readText() }.getOrNull()
            ?: return -1
        var k = 0
        for (line in playlist.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t == url) return k
            k++
        }
        return -1
    }

    /**
     * 播放本地缓存时手动标记广告后的落盘动作。
     *
     * 1) 把新标记的分片序号连同指纹依据并进 skipped-ads.json——
     *    [buildLocalPlaylist] 每次播放都会按它重建 local.m3u8，**不记在这里，
     *    下次播放这些广告分片会原样回来**（播放器不会对本地清单做指纹过滤）；
     * 2) 立刻重生成 local.m3u8，把这次标记的分片连同 #EXTINF 一起摘掉。
     *
     * 注意：**不删除本地分片文件**——当前会话的 ExoPlayer 还握着旧清单，
     * 删了文件 seek 到附近会直接报错；文件留着，反正清单里已经不引用它。
     */
    fun applyLocalAdMarks(epFolder: File, indices: Map<Int, AdFilter.Fingerprint>) {
        if (indices.isEmpty()) return
        runCatching {
            val merged = loadSkippedAds(epFolder).toMutableMap()
            merged.putAll(indices)
            saveSkippedAds(epFolder, merged)
            buildLocalPlaylist(epFolder)
            AppLog.put("本地标记广告：剔除 ${indices.size} 个分片并重建 ${epFolder.name}/local.m3u8")
        }.onFailure { AppLog.putDebug("本地广告标记落盘失败：${epFolder.name}", it) }
    }

    private fun hostOfUrl(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()

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
        // 先写临时文件，写满再**原子改名**成 seg_XXXXXX.<后缀>。
        //
        // 直接往成品名上写的话，进程被杀 / 网络中断会留下一个**半截分片**；
        // 而断点续传的判据只看 `exists() && length > 0`（downloadHls 的 need 计算），
        // 半截分片会被当成"已经下过了"直接跳过，缺口永远补不上，
        // 最后合并出一个花屏 / 后半段卡死的坏视频——而且它无从察觉，
        // 因为文件确实存在、长度也不为 0。
        // 改名在同一目录内是原子的：seg_XXXXXX.<后缀> 一出现就代表这一片是完整的。
        val tmp = File(segFile.parentFile, "${segFile.name}.part")
        var renamed = false
        try {
            FileOutputStream(tmp).use { fos ->
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
            renamed = tmp.renameTo(segFile) || runCatching {
                // 极少数文件系统改名失败：复制兜底
                tmp.copyTo(segFile, overwrite = true)
                true
            }.getOrDefault(false)
        } finally {
            runCatching { source.source.close() }
            // 没成功改名 = 这一片没写完整，绝不能留一个会被误认为已下载的半成品
            if (!renamed) tmp.delete()
        }
    }

    /** 单个分片的最大尝试次数（含首次） */
    private const val SEGMENT_MAX_ATTEMPT = 3

    /** 分片重试的退避基数：第 n 次重试等 500 * n 毫秒（500 / 1000，线性退避） */
    private const val SEGMENT_RETRY_BACKOFF_MS = 500L

    /**
     * 带重试的单分片下载。
     *
     * 原来 worker 里任何一个分片抛异常就写进 `errorRef`，整集立刻判失败——
     * 源站限流一下、网络抖一下，前面几百片全白下，只能整集重来。这正是
     * "下载断断续续、半天也下不完"的直接原因（手动下载尤其明显：失败后停在
     * FAILED，得用户自己点继续才再跑一轮）。
     *
     * 参照 N_m3u8DL-RE 的 `SimpleDownloader`：分片级重试 3 次 + 线性退避，
     * 并且**只对瞬时错误重试**——4xx 鉴权/资源不存在（403/404/410）重试多少次都一样，
     * 在坏链上空耗几秒没有任何意义，直接抛出让这一集快速失败。
     */
    private fun fetchSegmentWithRetry(
        segment: Triple<String, String?, String?>,
        playlistUrl: String,
        headers: Map<String, String>,
        sequence: Long,
        keyCache: HashMap<String, ByteArray>,
        segFile: File,
        key: String,
    ) {
        for (attempt in 1..SEGMENT_MAX_ATTEMPT) {
            try {
                fetchSegmentToFile(segment, playlistUrl, headers, sequence, keyCache, segFile, raw = true)
                return
            } catch (e: Exception) {
                // 暂停必须立刻透传：不能等退避，否则"全部停止"要卡好几秒才生效
                if (e is PausedException) throw e
                if (attempt == SEGMENT_MAX_ATTEMPT || !isTransientSegmentError(e)) throw e
                Thread.sleep(SEGMENT_RETRY_BACKOFF_MS * attempt)
                // 退避期间可能已被暂停，醒来先确认一次
                checkPaused(key)
            }
        }
    }

    /**
     * 分片下载错误是否值得重试。
     *
     * 判据与 N_m3u8DL-RE 的 `RetryUtil.IsTransientNetworkError` 同一套思路：
     * 408 / 425 / 429 / 5xx / 超时 / 套接字错误重试，其余 4xx 与重定向超限直接放弃。
     * 拿不到状态码的一律按瞬时处理（连接失败、读超时都属于这一类）。
     */
    private fun isTransientSegmentError(e: Exception): Boolean {
        val code = (e as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
            ?.responseCode
            ?: (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)
                ?.responseCode
            ?: return true
        return code in RETRYABLE_HTTP_CODES
    }

    /** 值得重试的 HTTP 状态码：请求超时 / 过早关闭 / 限流 / 服务端错误 */
    private val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

    /** 某一集的分片缓存目录：Download/A保存视频/<dir>/<标题>/ */
    private fun epFolderOf(dir: File, title: String): File = File(dir, title)

    /** 直播归档文件名匹配：<标题>001.mp4 / <标题>002.mp4 … */
    private fun liveArchivePattern(title: String) =
        Regex("^${Regex.escape(title)}(\\d{3,})\\.mp4$")

    /**
     * 分配本次直播录制的归档文件名：在原名后追加三位递增序号（标题001.mp4、002…）。
     *
     * 刻意**不叫** `<标题>.mp4`：findLocalFile / enqueue / probeLivePlaylist / watchStart
     * 这一整套「已完成、有缓存」的判定都只认那个名字。用带序号的名字，重新打开同一个
     * 直播时就不会把上次录的内容当成本地缓存播出来，用户可以继续看直播；再次保存时
     * 扫一遍已有序号自动顺延，也不会覆盖之前的录像。
     *
     * 归档文件不带下载记录，会自然出现在「其他缓存」列表里，可单独播放/删除。
     */
    private fun nextLiveArchiveFile(dir: File, title: String): File {
        val pattern = liveArchivePattern(title)
        var max = 0
        dir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val n = pattern.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach
            if (n > max) max = n
        }
        var seq = max + 1
        var out: File
        do {
            out = File(dir, title + seq.toString().padStart(3, '0') + ".mp4")
            seq++
        } while (out.exists())
        return out
    }

    /** 该标题是否已经有直播归档文件（用于恢复下载记录时不误判成失败） */
    private fun hasLiveArchive(dir: File, title: String): Boolean {
        val pattern = liveArchivePattern(title)
        return dir.listFiles().orEmpty().any { it.isFile && pattern.matches(it.name) }
    }

    /**
     * 分片后缀。
     *
     * 早期用 `.ts`：系统媒体库认这个扩展名，会把缓存分片当视频全扫进相册，
     * 存几十部剧之后相册里全是几秒一片的碎片，根本没法看。
     * 改成 `.seg`（媒体库不认）顺带在目录里放 [NO_MEDIA] 双保险。
     */
    private const val SEG_EXT = "seg"

    /** 换名前的老分片后缀，读的时候要能认，否则老缓存会全部失效 */
    private const val SEG_EXT_LEGACY = "ts"

    /** 媒体库跳过标记。放在分片目录里，MediaScanner 就不再扫这个目录 */
    private const val NO_MEDIA = ".nomedia"

    /** 第 k 个分片文件（按播放列表顺序，0 基），原始字节（加密则保持加密） */
    private fun segFile(epFolder: File, index: Int): File =
        File(epFolder, "seg_%06d.$SEG_EXT".format(index))

    /**
     * 取**已落盘**的分片：新后缀 [SEG_EXT] 优先，回退换名前的老后缀。
     * 只认非空文件——0 字节的是没写完的半截。
     */
    private fun findSegFile(epFolder: File, index: Int): File? {
        val seg = File(epFolder, "seg_%06d.$SEG_EXT".format(index))
        if (seg.exists() && seg.length() > 0) return seg
        val legacy = File(epFolder, "seg_%06d.$SEG_EXT_LEGACY".format(index))
        if (legacy.exists() && legacy.length() > 0) return legacy
        return null
    }

    /** 是不是分片文件名（新老后缀都算） */
    private fun isSegFileName(name: String): Boolean =
        name.startsWith("seg_") &&
            (name.endsWith(".$SEG_EXT") || name.endsWith(".$SEG_EXT_LEGACY"))

    /** 从分片文件名里取原始序号；不是分片名返回 null */
    private fun segIndexFromName(name: String): Int? =
        name.removePrefix("seg_")
            .removeSuffix(".$SEG_EXT")
            .removeSuffix(".$SEG_EXT_LEGACY")
            .toIntOrNull()

    /** 确保目录带媒体库跳过标记（幂等；换名前建的老目录也靠它救回来） */
    private fun ensureNoMedia(dir: File) {
        runCatching { File(dir, NO_MEDIA).createNewFile() }
    }

    /**
     * 统计一个 m3u8 分片目录的**真实**完成情况：`(分片总数, 已落盘数)`。
     *
     * 总数取自 playlist.m3u8 里的分片行数（与 [buildLocalPlaylist] 同一套数法），
     * 已落盘数逐片核对磁盘上 `seg_%06d.<后缀>` 是否存在且非空；被判广告剔除的分片
     * （skipped-ads.json）本来就不该出现在成品里，按"已处理"计入。
     *
     * 返回 null = 这不是一个可用的 HLS 分片目录（没有 playlist.m3u8 或里面没有分片），
     * 调用方应退回其它判据。
     *
     * 存在的意义：`downloadHls` 内部（返回值计算）一直是逐片核对的，但**重启恢复时
     * 只看"目录里有没有 seg_ 文件"**，下了一半的目录因此被当成已完成。这里把同一套
     * 判据抽出来，让恢复路径与下载路径用一致的口径。
     */
    private fun hlsSegProgress(epFolder: File): Pair<Int, Int>? {
        if (!epFolder.isDirectory) return null
        val playlist = runCatching { File(epFolder, "playlist.m3u8").readText() }.getOrNull()
            ?: return null
        val total = playlist.lines().count { it.isNotBlank() && !it.trim().startsWith("#") }
        if (total <= 0) return null
        // 广告片算"已处理"：它本来就不该出现在成品里，
        // 否则这一集会永远停在"下了一半"，明明只剩一个被剔除的广告片
        val skipped = loadSkippedAds(epFolder).keys
        var done = 0
        for (k in 0 until total) {
            if (k in skipped) {
                done++
                continue
            }
            val f = findSegFile(epFolder, k)
            if (f != null) done++
        }
        return total to done
    }

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
        val key = watchKey(task)
        scope.launch {
            // FFmpeg 统计回调是全局静态的，多集同时合并会互相污染进度，
            // 这里串行化：逐集合并在后台跑，进度归当前正在合并的那一集
            mergeMutex.withLock {
                val prevState = synchronized(statesMap) { statesMap[key] }
                runCatching { mergeEpisode(task, key) }
                    .onSuccess { mp4 ->
                        // 合并是在后台协程里跑的，界面上原本没有任何反馈：
                        // 成功/无分片都弹一下，否则点完「合并为MP4」完全不知道结果
                        if (mp4 != null) {
                            appCtx.toastOnUi("已合并为 MP4：${mp4.name}", Toast.LENGTH_LONG)
                        } else {
                            appCtx.toastOnUi("合并失败：${titleOf(task)} 没有可合并的分片", Toast.LENGTH_LONG)
                        }
                    }
                    .onFailure {
                        AppLog.put("合并为 MP4 失败：${titleOf(task)}", it)
                        appCtx.toastOnUi("合并失败：${titleOf(task)}", Toast.LENGTH_LONG)
                        // 恢复合并前的状态，界面上的合并圆圈退回原样
                        if (prevState != null) {
                            updateState(key) { prevState.copy(mergeProgress = -1) }
                        }
                    }
            }
        }
    }

    /** 合并互斥：见 [mergeToMp4] 内的说明（FFmpeg 统计回调是全局的） */
    private val mergeMutex = kotlinx.coroutines.sync.Mutex()

    private fun mergeEpisode(task: Task, key: String? = null): File? {
        val mp4 = mergeSegFolder(dirOf(task), titleOf(task), key) ?: return null
        notifyCompleted(task) // mp4 已生成：会话可切到本地 mp4 播放
        if (key != null) {
            updateState(key) { it.copy(status = DlStatus.COMPLETED, progress = 100, mergeProgress = -1) }
        }
        return mp4
    }

    /**
     * 分片文件夹 → mp4 的合并核心：有记录的任务与「其他缓存」孤儿共用同一套。
     *
     * 两步走（shiguangyuedu 同款）：
     * 1. 分片解密按序拼接为原始流临时文件（.ts.part）——进度 0~50%（按字节）；
     * 2. FFmpeg `-c copy` 转封装为标准 MP4（moov 索引，可拖动、秒出首帧）——50~100%
     *    （按 FFmpeg 统计的已处理时长换算，总时长取 playlist.m3u8 的 EXTINF 之和）。
     *    FFmpeg 不可用或转换失败时回退旧行为：拼接流直接改名 .mp4（功能不退化）。
     *
     * @param key 下载记录 key；非空时把 [DlStatus.MERGING] 与 mergeProgress 写进状态
     * @param deleteSegments 合并成功后删除整个分片文件夹（自动合并流程用，省空间）
     */
    private fun mergeSegFolder(
        dir: File,
        title: String,
        key: String? = null,
        deleteSegments: Boolean = false,
        /** 直播录制归档用：指定带序号的成品名；null = 常规 <标题>.mp4 */
        outputName: String? = null,
    ): File? {
        val epFolder = epFolderOf(dir, title)
        if (!epFolder.isDirectory) return null
        val mp4 = File(dir, outputName ?: "$title.mp4")
        val segs = epFolder.listFiles().orEmpty()
            .filter { isSegFileName(it.name) }
            .sortedBy { segIndexFromName(it.name) ?: 0 }
        if (segs.isEmpty()) return null
        val meta = runCatching { org.json.JSONObject(File(epFolder, "meta.json").readText()) }.getOrNull()
        val seq = meta?.optLong("seq", 0L) ?: 0L
        val iv = if (meta != null && meta.has("iv") && !meta.isNull("iv")) meta.getString("iv") else null
        val keyFile = File(epFolder, "key.bin").takeIf { it.exists() }
        val keyBytes = keyFile?.readBytes()
        val initFile = File(epFolder, "init.bin").takeIf { it.exists() }

        fun report(percent: Int) {
            if (key == null) return
            updateState(key) {
                it.copy(status = DlStatus.MERGING, mergeProgress = percent.coerceIn(0, 100))
            }
        }
        report(0)

        // 第一步：解密 + 按序拼接原始流（进度 0~50，按已写字节占比）
        // 临时名跟随成品名：直播归档是 标题001/002…，别让多次合成共用一个 .ts.part
        val raw = File(dir, mp4.name.removeSuffix(".mp4") + ".ts.part")
        val totalBytes = segs.sumOf { it.length() }.coerceAtLeast(1L)
        var written = 0L
        FileOutputStream(raw).use { out ->
            initFile?.inputStream()?.use { ins -> ins.copyTo(out) }
            segs.forEach { seg ->
                // 序号必须取**文件名里记录的原始分片序号**，不能用"第几个存在的文件"。
                // 广告分片被剔除后中间会留下空洞（seg_1 没了、seg_2 还在），
                // 用 forEachIndexed 的下标会让 AES 解密序号整体错位，
                // 而 AES/CBC/PKCS5Padding 的强制 padding 会让错位之后的每一段都解成乱码。
                val k = segIndexFromName(seg.name) ?: 0
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
                written += seg.length()
                report((written * 50 / totalBytes).toInt())
            }
        }
        File(epFolder, "index.idx").delete()

        // 第二步：FFmpeg -c copy 转封装（进度 50~100，按已处理时长占比）；失败回退直写
        val durationMs = playlistDurationMs(epFolder)
        val remuxed = runCatching {
            FfmpegMerger.remux(raw, mp4, durationMs) { p -> report(50 + p / 2) }
        }.getOrDefault(false)
        if (remuxed && mp4.length() > 0) {
            raw.delete()
            AppLog.put("合并MP4: $title 已转封装为标准 MP4")
        } else {
            runCatching { mp4.delete() }
            if (!raw.renameTo(mp4)) {
                runCatching { raw.copyTo(mp4, overwrite = true); raw.delete() }
            }
            AppLog.put("合并MP4: $title 转封装失败，回退直写模式")
        }
        // 成品已生成：按需清理分片文件夹（自动合并流程开启；手动合并不清理）
        if (deleteSegments && mp4.exists() && mp4.length() > 0) {
            runCatching { epFolder.deleteRecursively() }
                .onSuccess { AppLog.put("合并MP4: $title 分片文件夹已清理") }
        }
        return mp4
    }

    /** playlist.m3u8 里 EXTINF 时长之和（毫秒），用作 FFmpeg 转封装进度的分母 */
    private fun playlistDurationMs(epFolder: File): Long = runCatching {
        val f = File(epFolder, "playlist.m3u8")
        if (!f.exists()) return 0L
        var seconds = 0.0
        f.readLines().forEach { line ->
            if (line.startsWith("#EXTINF")) {
                line.substringAfter(':').substringBefore(',').trim()
                    .toDoubleOrNull()?.let { seconds += it }
            }
        }
        (seconds * 1000).toLong()
    }.getOrDefault(0L)

    // ---------- 其他缓存（无下载记录的磁盘缓存） ----------

    /** 一个无下载记录的缓存条目：整份分片文件夹，或一个成品 mp4 */
    data class OrphanCache(
        /** 所属视频的保存目录名（Download/A保存视频/<dirName>） */
        val dirName: String,
        /** 分片文件夹名 / mp4 文件名（不含 .mp4 后缀） */
        val title: String,
        /** true = m3u8 分片文件夹；false = 单文件 mp4 */
        val isHls: Boolean,
        /** HLS 分片总数 / 已落盘数（isHls 时有效） */
        val segTotal: Int,
        val segDone: Int,
        val sizeBytes: Long,
    )

    /**
     * 探测某个视频目录下**没有下载记录**的缓存条目。
     *
     * 两种情况会产生这种条目：
     * - 下载记录丢失（tasks.json 被清 / 数据损坏），分片文件夹、密钥等文件都还在；
     * - 换了视频源重下，每集命名不同（"1、2…" vs "第一集、第二集…"），
     *   旧源下的目录对不上新源的任何一集记录。
     * 有记录的条目一律忽略：界面上平时只显示有记录的，「其他缓存」按钮才补看这些孤儿。
     *
     * 注意：下载记录的恢复（[restoreIfNeeded]）是异步的，调用方应配合记录状态流的
     * 变化重扫一遍，避免恢复完成前把其实有记录的目录误报成孤儿。
     */
    fun orphanCaches(dirName: String): List<OrphanCache> {
        if (dirName.isBlank()) return emptyList()
        val dir = File(rootDir(), dirName)
        if (!dir.isDirectory) return emptyList()
        val recorded = synchronized(statesMap) {
            statesMap.values.filter { it.dirName == dirName }
        }.mapTo(HashSet()) { it.title }
        val result = mutableListOf<OrphanCache>()
        dir.listFiles()?.forEach { f ->
            when {
                f.isDirectory -> {
                    // 只认分片文件夹（有 seg_ 分片），其它杂物目录不动
                    val hasSeg = f.listFiles().orEmpty().any { isSegFileName(it.name) }
                    if (!hasSeg || f.name in recorded) return@forEach
                    val (total, done) = hlsSegProgress(f) ?: (0 to 0)
                    result.add(
                        OrphanCache(
                            dirName = dirName,
                            title = f.name,
                            isHls = true,
                            segTotal = total,
                            segDone = done,
                            sizeBytes = runCatching {
                                f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                            }.getOrDefault(0L),
                        )
                    )
                }
                f.isFile && f.name.endsWith(".mp4") -> {
                    val title = f.name.removeSuffix(".mp4")
                    if (title in recorded) return@forEach
                    result.add(
                        OrphanCache(dirName, title, false, 0, 0, f.length())
                    )
                }
            }
        }
        // 排序三层：手动顺序（存在视频文件夹里的 .order.json，随文件夹走）
        // → 集数自然排序（1/01/第1集/第一集…统一按数字比）→ 标题兜底。
        // 只影响「其他缓存」列表；有记录的分集仍按目录集序号排，两套互不干扰。
        val savedIdx = HashMap<String, Int>()
        loadOrphanOrder(dirName).forEachIndexed { i, t -> if (t !in savedIdx) savedIdx[t] = i }
        return result.sortedWith(
            compareBy<OrphanCache> { savedIdx[it.title] ?: Int.MAX_VALUE }
                .thenBy { parseEpisodeNumber(it.title) ?: Int.MAX_VALUE }
                .thenBy { it.title }
        )
    }

    /**
     * 从集数命名里解析出集号，供自然排序用。
     * 支持：`1` / `01` / `1集` / `第1集` / `第01话` / `EP12`，以及中文数字
     * `第一集` / `第十二集` / `一百零三`（支持 十/百 组合，"两"按 2 处理）。
     * 解析不出的返回 null（排到有集号的后面）。
     */
    fun parseEpisodeNumber(title: String): Int? {
        val t = title.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        Regex("^(\\d+)\\s*集?$").find(t)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("^第\\s*(\\d+)\\s*[集话話期季部卷篇回]?.*$").find(t)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("^EP?\\.?\\s*(\\d+).*$", RegexOption.IGNORE_CASE).find(t)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        val cn = Regex("^第\\s*([零一二两三四五六七八九十百]+)\\s*[集话話期季部卷篇回]?$").find(t)
            ?.groupValues?.get(1)
            ?: t.takeIf { it.matches(Regex("^[零一二两三四五六七八九十百]+$")) }
        return cn?.let { chineseToInt(it) }
    }

    private val CN_DIGITS = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 中文数字 → 整数（支持到百位："十"=10、"二十三"=23、"一百二十三"=123） */
    private fun chineseToInt(s: String): Int? {
        var total = 0
        var current = 0
        var any = false
        for (c in s) {
            when (c) {
                '零' -> { any = true }
                '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0; any = true }
                '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0; any = true }
                else -> {
                    val d = CN_DIGITS[c] ?: return null
                    current = current * 10 + d
                    any = true
                }
            }
        }
        return if (any) total + current else null
    }

    /**
     * 「其他缓存」手动排序的存档文件：直接放在视频文件夹里（隐藏文件），
     * 内容是按顺序排好的标题数组。这样排序跟着文件夹走——
     * APP 数据丢了、换台设备重新读这个文件夹，自定义顺序都还在。
     */
    private const val ORPHAN_ORDER_FILE = ".order.json"

    /** 读取手动排序（无文件/损坏返回空列表，走默认自然排序） */
    fun loadOrphanOrder(dirName: String): List<String> =
        runCatching {
            val f = File(File(rootDir(), dirName), ORPHAN_ORDER_FILE)
            val arr = org.json.JSONArray(f.readText())
            (0 until arr.length()).map { arr.optString(it) }
        }.getOrDefault(emptyList())

    /** 保存手动排序（后台写，避免卡界面） */
    fun saveOrphanOrderAsync(dirName: String, titles: List<String>) {
        scope.launch {
            runCatching {
                val arr = org.json.JSONArray()
                titles.forEach { arr.put(it) }
                File(File(rootDir(), dirName), ORPHAN_ORDER_FILE).writeText(arr.toString())
            }.onFailure { AppLog.put("保存其他缓存排序失败：$dirName", it) }
        }
    }

    /** 清掉手动排序，回到默认自然排序 */
    fun clearOrphanOrderAsync(dirName: String) {
        scope.launch {
            runCatching { File(File(rootDir(), dirName), ORPHAN_ORDER_FILE).delete() }
                .onFailure { AppLog.put("清除其他缓存排序失败：$dirName", it) }
        }
    }

    /**
     * 播放无记录的缓存：成品 mp4 优先；分片文件夹则重建 local.m3u8 后返回。
     * 与有记录的分集走同一套本地播放机制（密钥/初始化段用文件夹里的本地副本）。
     */
    fun findOrphanFile(dirName: String, title: String): File? {
        val dir = File(rootDir(), dirName)
        val mp4 = File(dir, "$title.mp4")
        if (mp4.exists() && mp4.length() > 0) return mp4
        val epFolder = File(dir, title)
        if (epFolder.isDirectory &&
            epFolder.listFiles().orEmpty().any { isSegFileName(it.name) }
        ) {
            ensureNoMedia(epFolder)
            return runCatching { buildLocalPlaylist(epFolder) }.getOrNull()
        }
        return null
    }

    /** 合并无记录的缓存分片为 MP4（合并完成或失败后 onDone 在主线程回调，供界面刷新列表） */
    fun mergeOrphanToMp4(dirName: String, title: String, onDone: (() -> Unit)? = null) {
        scope.launch {
            runCatching { mergeSegFolder(File(rootDir(), dirName), title) }
                .onSuccess { mp4 ->
                    appCtx.toastOnUi(
                        if (mp4 != null) {
                            "已合并为 MP4：$title.mp4"
                        } else {
                            "合并失败：$title 没有可合并的分片"
                        },
                        Toast.LENGTH_LONG,
                    )
                }
                .onFailure {
                    AppLog.put("合并为 MP4 失败：$title", it)
                    appCtx.toastOnUi("合并失败：$title", Toast.LENGTH_LONG)
                }
            withContext(Dispatchers.Main) { onDone?.invoke() }
        }
    }

    /** 删除无记录的缓存条目（删除完成后 onDone 在主线程回调，供界面刷新列表） */
    fun deleteOrphanCache(
        dirName: String,
        title: String,
        isHls: Boolean,
        onDone: (() -> Unit)? = null,
    ) {
        scope.launch {
            runCatching {
                val dir = File(rootDir(), dirName)
                if (isHls) File(dir, title).deleteRecursively()
                else File(dir, "$title.mp4").delete()
            }.onFailure { AppLog.put("删除其他缓存失败：$title", it) }
            withContext(Dispatchers.Main) { onDone?.invoke() }
        }
    }

    /**
     * 生成播放用的 local.m3u8：已下载的分片指向本地文件（零网络），
     * 未下载的保持原始 http(s) 地址由播放器实时补齐——这就是"分片文件夹里有一个分片就算缓存"。
     * 加密流的密钥/初始化段也优先用本地副本。
     */
    private fun buildLocalPlaylist(
        epFolder: File,
        /** 额外要摘掉的分片序号（播放时遇到坏片的现场剔除，不落盘、不进名单） */
        extraSkip: Set<Int> = emptySet(),
        out: File = File(epFolder, "local.m3u8"),
    ): File {
        val playlist = runCatching { File(epFolder, "playlist.m3u8").readText() }.getOrNull()
            ?: return File(epFolder, "playlist.m3u8")

        // 被判广告剔除的分片必须**连同它的 #EXTINF / #EXT-X-BYTERANGE 一起摘掉**，
        // 不能留在清单里：少一步就会走到最后的 else 分支回填成原始 http 地址，
        // 播本地时广告照样联网回来。
        var skipped = loadSkippedAds(epFolder).keys + extraSkip
        val totalSegs = playlist.lines().count { !it.startsWith("#") && it.isNotBlank() }
        // 兜底：万一指纹把整片判成了广告，宁可原样播也不要出一个空清单
        if (skipped.size >= totalSegs) skipped = emptySet()

        val sb = StringBuilder()
        var k = 0
        /** 分片的描述标签：先扣住，等看到分片地址再决定这一整组是留还是丢 */
        var pendingTags: MutableList<String>? = null
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
                // #EXTINF 与 #EXT-X-BYTERANGE 都属于"即将到来的那一片"，必须和地址行同生共死。
                // 早先只扣住 EXTINF、让 BYTERANGE 原样输出，删掉分片后这个 range 就
                // 挂到了下一个分片头上，清单直接错乱。
                l.startsWith("#EXTINF") || l.startsWith("#EXT-X-BYTERANGE") -> {
                    val tags = pendingTags ?: ArrayList<String>(2).also { pendingTags = it }
                    tags.add(l)
                }
                // #EXT-X-DISCONTINUITY **原样透传**。
                // 曾经在这里做过"连续标记压成一个"的去重，那是错的：去重标志只在输出分片时
                // 才复位，而 #EXT-X-KEY / #EXTINF 分支都不复位，于是原本合法的连续标记
                // （加密流换密钥、转码流切换处很常见）会被吞掉一个，清单结构变化导致
                // 播放时解码器配置出错，无广告的视频也一律播不了。
                // 删掉中间分片后留下两个连续 DISCONTINUITY 是合法的，而且语义更正确：
                // 它本就是用来标记广告块边界的。
                l.startsWith("#") -> sb.append(l).append('\n')
                l.isBlank() -> Unit
                else -> {
                    val tags = pendingTags
                    pendingTags = null
                    if (k in skipped) {
                        // 广告分片：描述标签和地址行一起丢掉
                    } else {
                        tags?.forEach { sb.append(it).append('\n') }
                        val localSeg = findSegFile(epFolder, k)
                        if (localSeg != null) {
                            sb.append(Uri.fromFile(localSeg).toString()).append('\n')
                        } else {
                            // 这一片没下到本地 → 回填原始网络地址，播放时去网上取
                            sb.append(l).append('\n')
                        }
                    }
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
