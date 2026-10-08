package io.legado.app.help.video

import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import io.legado.app.constant.AppLog
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 广告分片过滤器（内容指纹库）。
 *
 * ## 它解决什么问题
 * 视频源（SSAI 服务端插码）把广告切成 .ts 分片混进正片的播放清单里。
 * 这类分片**没法从 URL 认出来**——同一个网站每一部视频的分片文件名都不一样
 * （实测：第 1 集 637 片与第 2 集 659 片，文件名零重合）。
 *
 * 但反过来，**同一条广告在任何视频里都是同一份字节**：实测第 1 集与第 2 集的同一条广告，
 * 三个分片各自 md5 逐字节相同（1780924B / 1109576B / 2029084B）。
 * 所以判据只能落在「内容」上，且库的大小只取决于**广告有几条**，与看过多少视频无关。
 *
 * ## 判据（两级，先看 cheap 的）
 * 1. **size 初筛**：由 ExoPlayer 的 bytesLoaded 直接给出，零成本；
 * 2. **采样哈希确认**：只取分片**前 [SAMPLE_BYTES]** 字节算 SHA-256，从 [VideoCache]
 *    读本地已缓存的数据，不联网、不读整片（一个 2MB 分片只读 192KB）。
 *
 * 只认 size 会误杀——固定码率源里大量正片分片字节数接近，所以**必须**走到哈希确认才判广告。
 *
 * ## 样本 folder
 * `Download/保存视频/AI识别广告/`，每个广告分片存成一个独立文件，**原始字节不做任何加工**，
 * 后缀统一改成 `.mp4`（仅改名，方便在文件管理器里按视频缩略图查看/整理）。
 *
 * **删除 folder 里的文件即撤销对应规则**——这是刻意设计的可纠误方式，
 * 比改数据库/设置项直观得多。新增文件也会被自动纳入。
 * 索引文件 [INDEX_FILE] 是隐藏的侧车文件，由本对象全权维护，不要手改。
 *
 * ## 关于加密流
 * `#EXT-X-KEY` 且**每段 IV 不同**的分片，密文每次都不一样，采样哈希会失配
 * （size 仍稳定）。这类源只能靠 [AdFilterConfidence.SIZE_ONLY] 放宽，默认关闭以避免误杀。
 */
object AdFilter {

    /** 单条指纹：对应 folder 里的一个样本文件 */
    data class Fingerprint(
        /** 样本文件名 */
        val file: String,
        /** 分片字节数（一级判据） */
        val size: Long,
        /** 前 [SAMPLE_BYTES] 字节的 SHA-256（二级判据） */
        val hash: String,
        /** 归属站点；null 表示全局生效 */
        val host: String?,
    )

    /** 判定力度：size 命中后是否还需要哈希确认 */
    enum class AdFilterConfidence {
        /** 严格（默认）：size + 采样哈希都命中才算广告 */
        STRICT,

        /** 宽松：size 命中即算广告。仅供"广告字节数独一份"的源手动开启，有误杀风险 */
        SIZE_ONLY,
    }

    const val ROOT_NAME = "保存视频"
    const val DIR_NAME = "AI识别广告"
    private const val INDEX_FILE = ".ad-index.json"

    /**
     * 采样字节数：从头读这么多。
     *
     * 192KB 覆盖 TS 的 PAT/PMT 与开头若干帧的画面/音频数据，同一条广告必然一致，
     * 正片撞上的概率可以忽略。取太大就得真的读整片，浪费 IO。
     */
    private const val SAMPLE_BYTES = 192 * 1024

    private const val READ_BUF = 32 * 1024
    private const val RESCAN_DELAY_MS = 600L

    /** size -> 该字节数下的所有指纹（按 host 隔离，见 [matchAllows]） */
    private val bySize = HashMap<Long, MutableList<Fingerprint>>()

    /** 已知文件名 -> size，用于快速判断"这个文件有没有变化"，避免每次重算哈希 */
    private val knownFiles = HashMap<String, Pair<Long, Long>>() // file -> (size, lastModified)

    @Volatile
    private var loaded = false

    private var observer: FileObserver? = null
    private val observersStarted = AtomicBoolean(false)
    /** 去重用的延时重建任务：连续删/拷多个文件时只跑最后一次 */
    private var rescanJob: Thread? = null

    /**
     * 判定力度。默认 [AdFilterConfidence.STRICT]。
     * 注意：改成 SIZE_ONLY 会显著放大误杀正片的风险，只对确认过的源开启。
     */
    @Volatile
    var confidence: AdFilterConfidence = AdFilterConfidence.STRICT

    /** 总开关。关掉后一切判定直接放行，用于排查"是不是误杀了正片" */
    @Volatile
    var enabled = true

    /**
     * 是否允许"跨站点"共享指纹。
     *
     * 默认开启：实测同一条广告在两部不同视频里 md5 逐字节相同，而判据是
     * 「size + 前 192KB 的 SHA-256」，正片撞上的概率可以忽略。
     * 开着才能让共处一套模板的站点（ffzy 系等）一次标记、处处生效；
     * 若真遇到跨站误杀，把它设成 false 就退化成"按站点隔离"。
     */
    @Volatile
    var globalMode = true

    // ---------------------------------------------------------------- 目录

    /** `Download/保存视频/AI识别广告`。与 [VideoDownloader] 共用一个根，权限机制一致 */
    fun dir(): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "$ROOT_NAME/$DIR_NAME"
    )

    /** 索引侧车文件 */
    private fun indexFile(): File = File(dir(), INDEX_FILE)

    // ---------------------------------------------------------------- 加载索引

    /**
     * 扫描 folder 并重建索引。
     *
     * 每个样本文件按 (size, lastModified) 决定要不要重算哈希——只有新增或改动的文件才读，
     * 所以几十个样本文件也只需几百毫秒，且只在启动/目录变化各跑一次。
     */
    @Synchronized
    fun refresh(force: Boolean = false) {
        val d = dir()
        if (!d.isDirectory) {
            d.mkdirs()
        }
        ensureObserver()
        val files = d.listFiles().orEmpty()
            .filter { it.isFile && it.name != INDEX_FILE }
            .sortedBy { it.name }
        if (!force && loaded && files.size == knownFiles.size &&
            files.all { knownFiles[it.name] == (it.length() to it.lastModified()) }
        ) {
            // 磁盘状态与索引完全一致，跳过重算（避免重复 IO）
            return
        }

        val next = HashMap<Long, MutableList<Fingerprint>>()
        val nextKnown = HashMap<String, Pair<Long, Long>>()

        // 先读索引，尽量复用已算过的哈希
        val cached = HashMap<String, JSONObject>()
        runCatching {
            val j = JSONObject(indexFile().readText())
            j.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    cached[o.optString("file")] = o
                }
            }
        }

        for (f in files) {
            val size = f.length()
            nextKnown[f.name] = size to f.lastModified()
            val old = cached[f.name]
            val hash = if (
                old != null && old.optLong("size", -1L) == size &&
                !old.optString("hash").isNullOrEmpty()
            ) {
                old.optString("hash") // 文件名与大小都没变，直接复用
            } else {
                sha256Of(f)
            }
            if (hash.isNullOrEmpty()) continue
            val host = old?.optString("host")?.takeIf { !it.isNullOrEmpty() }
            next.getOrPut(size) { ArrayList() }
                .add(Fingerprint(f.name, size, hash, host))
        }

        bySize.clear()
        bySize.putAll(next)
        knownFiles.clear()
        knownFiles.putAll(nextKnown)
        loaded = true
        persist()
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            bySize.values.flatten().forEach { fp ->
                arr.put(JSONObject().apply {
                    put("file", fp.file)
                    put("size", fp.size)
                    put("hash", fp.hash)
                    if (fp.host != null) put("host", fp.host)
                })
            }
            indexFile().writeText(JSONObject().apply { put("items", arr) }.toString())
        }.onFailure { AppLog.put("广告指纹索引写入失败", it) }
    }

    /**
     * 目录变化后延迟重建：用户可能连续删/拷好几个文件，
     * 每次都立刻重建会重复读盘。
     */
    private fun ensureObserver() {
        if (!observersStarted.compareAndSet(false, true)) return
        val d = dir()
        if (!d.isDirectory && !d.mkdirs()) return
        // 事件掩码：常量必须用 FileObserver. 限定（它们是类里的静态字段），
        // 位或用 Kotlin 的 infix Int.or（Kotlin 没有 Java 的 | 运算符）
        val mask = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.CLOSE_WRITE or FileObserver.MODIFY
        // FileObserver 走系统回调，在主线程创建最稳妥。
        // warmUp 常在 IO 线程被调用，所以这里按当前线程决定直接建还是 post 过去。
        // 末尾那个 Unit 是必需的：lambda 若以 runCatching 收尾会被推断成 () -> Result<Unit>，
        // 而 Handler.post 要的是 () -> Unit。
        val create: () -> Unit = {
            runCatching {
                observer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    object : FileObserver(d, mask) {
                        override fun onEvent(event: Int, path: String?) = scheduleRefresh(path)
                    }
                } else {
                    // FileObserver(File,..) 是 API 29 才加的构造函数，minSdk 23 下直接调用会
                    // NoSuchMethodError 崩在旧设备上；旧签名接收的是路径字符串。
                    @Suppress("DEPRECATION")
                    object : FileObserver(d.path, mask) {
                        override fun onEvent(event: Int, path: String?) = scheduleRefresh(path)
                    }
                }.apply { startWatching() }
            }.onFailure {
                // 监听失败不影响使用：判定时走的 refresh 会兜住，
                // 只是"删文件后要下次启动/刷新才生效"。
                AppLog.put("广告目录监听启动失败，改为按需刷新", it)
                observersStarted.set(false)
            }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            create()
        } else {
            // post 本身也可能抛（极端情况下取不到主 Looper），不能让异常冒到调用方：
            // 监听只是锦上添花，失败了就退回"按需刷新"。
            runCatching { Handler(Looper.getMainLooper()).post(create) }
                .onFailure { observersStarted.set(false) }
        }
    }

    /** 目录变化后延迟重建：用户可能连续删/拷好几个文件，每次都立刻重建会重复读盘 */
    private fun scheduleRefresh(path: String?) {
        if (path == INDEX_FILE) return // 自己写的，忽略
        rescanJob?.interrupt()
        rescanJob = Thread {
            try {
                Thread.sleep(RESCAN_DELAY_MS)
                refresh(force = true)
            } catch (_: InterruptedException) {
            }
        }.apply { start() }
    }

    // ---------------------------------------------------------------- 清单层序列指纹

    /**
     * `#EXTINF` 序列 → 站点。
     *
     * 这是让"第一集标记完，第二集直接无广告"的关键：size 与内容都要下载才拿得到，
     * URL 又每集都变（实测两集 659 片文件名零重合），只有 EXTINF 时长序列
     * **只读清单就能比对**，而且实测跨集完全一致：
     * `第1集 [4.867, 3.333, 5.6]` == `第2集 [4.867, 3.333, 5.6]`。
     *
     * 用**整段序列**而非单个时长做键：单个时长（如 4.0 秒）满地都是，必然误伤；
     * 连续 3 片同时对上的概率可以忽略。
     */
    private val extInfSeq = HashMap<String, String>() // seqKey -> host

    /** 登记一条 EXTINF 序列指纹 */
    @Synchronized
    fun registerExtInfSequence(seq: List<Long>, host: String?) {
        // 至少 2 片才有区分度，单片时长毫无意义
        if (seq.size < 2 || seq.any { it <= 0 }) return
        extInfSeq[AdPlaylistFilter.seqKey(seq)] = host ?: ""
        saveSeqIndex()
    }

    /**
     * 匹配 EXTINF 序列。
     *
     * 命中即判广告——**这一步没有任何文件读取，也没有网络**，是真正意义上的预防：
     * 第二次播放新的一集，清单刚拿到就能把广告行摘掉，它根本不会被下载。
     */
    @Synchronized
    fun matchExtInfSequence(seq: List<Long>?, host: String?): Boolean {
        if (!enabled || seq.isNullOrEmpty()) return false
        if (extInfSeq.isEmpty()) loadSeqIndex()
        val owner = extInfSeq[AdPlaylistFilter.seqKey(seq)] ?: return false
        if (globalMode || owner.isEmpty()) return true
        return owner == (host ?: "")
    }

    private fun seqIndexFile(): File = File(dir(), ".ad-seq.json")

    private fun loadSeqIndex() {
        runCatching {
            if (extInfSeq.isNotEmpty()) return
            val f = seqIndexFile()
            if (!f.exists()) return
            val obj = JSONObject(f.readText())
            val arr = obj.optJSONArray("seqs") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("key")
                if (k.isNotEmpty()) extInfSeq[k] = o.optString("host")
            }
        }
    }

    private fun saveSeqIndex() {
        runCatching {
            val arr = JSONArray()
            extInfSeq.forEach { (k, h) ->
                arr.put(JSONObject().apply {
                    put("key", k)
                    put("host", h)
                })
            }
            seqIndexFile().writeText(JSONObject().apply { put("seqs", arr) }.toString())
        }.onFailure { AppLog.put("广告序列索引写入失败", it) }
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 该 host 是否可以套用 [fp]。
     * [globalMode] 打开或指纹本就没有 host 归属时，一律生效。
     */
    private fun matchAllows(fp: Fingerprint, host: String?): Boolean =
        globalMode || fp.host == null || host == null || fp.host == host

    /**
     * 判定一个分片是不是广告。
     *
     * **必须在后台线程调用**：命中 size 后要从缓存读数并哈希。
     * 未命中 size 时零 IO，可以随便调用（这也是把它作为第一级的原因）。
     *
     * @param url 分片完整地址
     * @param bytesLoaded ExoPlayer 报告的字节数；<=0 时会自动改用 Content-Length
     * @param headers 书源请求头（m3u8/分片普遍校验 UA、Referer）
     */
    fun isAd(url: String, bytesLoaded: Long = -1L, headers: Map<String, String> = emptyMap()): Boolean {
        if (!enabled) return false
        if (!loaded) refresh()
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val pending = bySize[bytesLoaded]?.filter { matchAllows(it, host) }
        // 初筛：size 都没撞上，直接放行，连缓存都不用读
        if (pending.isNullOrEmpty()) return false
        val actual = if (bytesLoaded > 0) bytesLoaded else contentLengthOf(url, headers)
        val candidates = (bySize[actual] ?: pending).filter { matchAllows(it, host) }
        if (candidates.isEmpty()) return false
        if (confidence == AdFilterConfidence.SIZE_ONLY) return true
        val hash = sampleHashFromCache(url, headers) ?: return false
        return candidates.any { it.hash == hash }
    }

    /** 已经拿到完整内容时的判定（下载侧用，无需二次读容器） */
    fun isAdBySample(size: Long, sample: ByteArray, host: String?): Boolean {
        if (!loaded) refresh()
        val candidates = bySize[size]?.filter { matchAllows(it, host) }
        if (candidates.isNullOrEmpty()) return false
        if (confidence == AdFilterConfidence.SIZE_ONLY) return true
        val hash = sha256(sample)
        return candidates.any { it.hash == hash }
    }

    /**
     * 判定一个**已经落盘的本地文件**是不是广告，并给出命中的那条指纹。
     *
     * 下载侧除了"删不删"还需要"记下依据"——把指纹写进 skipped-ads.json，
     * 以后用户撤销标记（删掉样本文件）时能自动让这条剔除失效。
     */
    fun matchedFingerprint(file: File, host: String?): Fingerprint? {
        if (!enabled) return null
        if (!loaded) refresh()
        val size = file.length()
        if (size <= 0) return null
        val candidates = bySize[size]?.filter { matchAllows(it, host) }
        if (candidates.isNullOrEmpty()) return null
        if (confidence == AdFilterConfidence.SIZE_ONLY) return candidates.first()
        val hash = sha256Of(file) ?: return null
        return candidates.firstOrNull { it.hash == hash }
    }

    /** 判定一个已经落盘的本地文件是不是广告（下载侧用）。 */
    fun isAdFile(file: File, host: String?): Boolean = matchedFingerprint(file, host) != null

    /**
     * [isAd] 的孪生：判定之外还给出命中的那条指纹。
     * 播放本地缓存时手动标记 http 回填分片用——写 skipped-ads.json 需要指纹依据。
     * **必须在后台线程调用**（命中 size 后要从缓存读数并哈希）。
     */
    fun matchedFingerprintFor(
        url: String,
        bytesLoaded: Long,
        headers: Map<String, String> = emptyMap(),
    ): Fingerprint? {
        if (!enabled) return null
        if (!loaded) refresh()
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val actual = if (bytesLoaded > 0) bytesLoaded else contentLengthOf(url, headers)
        val candidates = (bySize[actual] ?: return null).filter { matchAllows(it, host) }
        if (candidates.isEmpty()) return null
        if (confidence == AdFilterConfidence.SIZE_ONLY) return candidates.first()
        val hash = sampleHashFromCache(url, headers) ?: return null
        return candidates.firstOrNull { it.hash == hash }
    }

    /**
     * 这条指纹是否**仍在库**里。
     *
     * 用户在样本文件夹里删掉某个 mp4 = 撤销这条规则；此前据此做的下载剔除
     * 必须跟着作废，否则会出现"标记已删，下载却仍然跳过广告"的矛盾。
     */
    fun isFingerprintAlive(size: Long, hash: String): Boolean {
        if (size <= 0L || hash.isEmpty()) return false
        if (!loaded) refresh()
        return bySize[size]?.any { it.hash == hash } == true
    }

    /** 只按 size 判有没有"可能"命中，用于决定要不要付费去算哈希 */
    fun mayMatch(size: Long, host: String?): Boolean {
        if (!enabled) return false
        if (!loaded) refresh()
        return bySize[size]?.any { matchAllows(it, host) } == true
    }

    // ---------------------------------------------------------------- 注册（标记当期分片）

    /**
     * 把当前分片登记为广告：把分片原始字节原样保存到样本 folder（后缀 .mp4），并把指纹入库。
     *
     * 返回保存出的文件；失败返回 null。
     * **必须在后台线程调用**（要读整个分片）。
     */
    fun register(url: String, headers: Map<String, String>, size: Long): File? {
        if (!loaded) refresh()
        if (size <= 0) return null
        // 已经登记过的不重复写文件
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        val existing = bySize[size]?.firstOrNull { matchAllows(it, host) }
        if (existing != null) {
            return File(dir(), existing.file)
        }
        val bytes = readCachedOrNetwork(url, headers) ?: return null
        // 以实际读到的字节为准：Content-Length 与实体不符时（部分源会）以实体为准，
        // 否则指纹基于错误的 size 建起来，以后永远匹配不上。
        val actualSize = bytes.size.toLong()
        val hash = sha256(bytes.copyOf(min(SAMPLE_BYTES, bytes.size)))
        val file = newSampleFile(host, actualSize)
        runCatching {
            FileOutputStream(file).use { it.write(bytes) }
        }.onFailure {
            AppLog.put("广告样本保存失败", it)
            return null
        }
        // 立刻入库，不用等 FileObserver 的延迟
        bySize.getOrPut(actualSize) { ArrayList() }
            .add(Fingerprint(file.name, actualSize, hash, host))
        knownFiles[file.name] = file.length() to file.lastModified()
        persist()
        return file
    }

    /**
     * 把一个**本地分片文件**登记为广告（播放本地缓存时标记用）。
     *
     * 与 [register] 的 http 路径不同：字节直接来自本地文件，不走 [VideoCache]
     * （CacheDataSource 只认 http/https，对 file:// 只会抛异常）。
     * 若该文件已命中现有指纹（size+哈希），说明库里已有这条，直接复用样本文件不重复存。
     */
    fun registerFile(file: File, host: String?): File? {
        if (!loaded) refresh()
        if (!file.exists() || file.length() <= 0L) return null
        matchedFingerprint(file, host)?.let { fp ->
            if (fp.file.isNotEmpty()) return File(dir(), fp.file)
        }
        val actualSize = file.length()
        val hash = sha256Of(file) ?: return null
        val f = newSampleFile(host, actualSize)
        runCatching { file.copyTo(f, overwrite = true) }.onFailure {
            AppLog.put("广告样本保存失败", it)
            return null
        }
        bySize.getOrPut(actualSize) { ArrayList() }
            .add(Fingerprint(f.name, actualSize, hash, host))
        knownFiles[f.name] = f.length() to f.lastModified()
        persist()
        return f
    }

    private fun newSampleFile(host: String?, size: Long): File {
        val d = dir().apply { if (!exists()) mkdirs() }
        val stamp = java.text.SimpleDateFormat(
            "yyMMdd-HHmmss", java.util.Locale.getDefault()
        ).format(java.util.Date())
        val tag = host?.replace(Regex("[^A-Za-z0-9]"), "")?.take(12)
            ?.takeIf { it.isNotBlank() } ?: "unknown"
        var f = File(d, "${tag}__${stamp}__${size}$SAMPLE_EXT")
        var n = 1
        while (f.exists()) {
            f = File(d, "${tag}__${stamp}_${n}__${size}$SAMPLE_EXT")
            n++
        }
        return f
    }

    /** 样本文件后缀：内容完全不加工，只是改个名方便在文件管理器里查看 */
    const val SAMPLE_EXT = ".mp4"

    /**
     * 该地址在缓存里已占的字节数（未缓存返回 0）。
     *
     * 纯内存查询（SimpleCache 的 span 索引常驻内存），不下任何网络请求，
     * 所以可以放在播放链路里零代价调用。分片没下完时得到的是部分长度，
     * 与指纹库自然匹配不上，不会误判。
     */
    fun cachedLength(url: String): Long = runCatching {
        VideoCache.cachedLength(url)
    }.getOrDefault(0L)

    /**
     * 读取**已缓存分片**的前 [SAMPLE_BYTES] 字节（清单改写时用）。
     *
     * 只走本地 [VideoCache] 且不写入，因此：
     * - 不产生任何网络请求（未缓存时返回空）
     * - 与 [isAdFile] 判的是同一段字节，口径与播放侧完全一致
     */
    fun readSampleFor(url: String): ByteArray = runCatching {
        if (!VideoCache.isCached(url)) return@runCatching ByteArray(0)
        val s = VideoCache.open(url, emptyMap(), write = false)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(READ_BUF)
            var remain = SAMPLE_BYTES
            while (remain > 0) {
                val r = s.source.read(buf, 0, min(READ_BUF, remain))
                if (r == C.RESULT_END_OF_INPUT) break
                if (r <= 0) break
                out.write(buf, 0, r)
                remain -= r
            }
            out.toByteArray()
        } finally {
            runCatching { s.source.close() }
        }
    }.getOrDefault(ByteArray(0))

    /**
     * 读取**已缓存分片**的完整字节（供缩略图抽帧用）。
     *
     * 与 [readSampleFor] 同口径：只走本地 [VideoCache] 且 write=false，未缓存返回空，
     * 因此缩略图抽取零流量——已播放过的分片（暂停位置前后的那一批）本地都有。
     */
    fun readFullCached(url: String): ByteArray? = runCatching {
        if (!VideoCache.isCached(url)) return@runCatching null
        val s = VideoCache.open(url, emptyMap(), write = false)
        try {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(READ_BUF)
            while (true) {
                val r = s.source.read(buf, 0, buf.size)
                if (r == C.RESULT_END_OF_INPUT) break
                if (r <= 0) break
                out.write(buf, 0, r)
            }
            out.toByteArray()
        } finally {
            runCatching { s.source.close() }
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 读取工具

    /** 已从 ExoPlayer 缓存落盘的分片：走 [VideoCache]，写=false 只读不写，零流量 */
    private fun readCachedOrNetwork(url: String, headers: Map<String, String>): ByteArray? =
        runCatching {
            val out = java.io.ByteArrayOutputStream()
            VideoCache.open(url, headers, write = false).let { s ->
                try {
                    val buf = ByteArray(READ_BUF)
                    while (true) {
                        val r = s.source.read(buf, 0, buf.size)
                        if (r == C.RESULT_END_OF_INPUT) break
                        out.write(buf, 0, r)
                    }
                } finally {
                    runCatching { s.source.close() }
                }
            }
            out.toByteArray()
        }.onFailure { AppLog.putDebug("广告样本读取失败：$url") }.getOrNull()

    /** 只读前 [SAMPLE_BYTES] 字节做哈希 */
    private fun sampleHashFromCache(url: String, headers: Map<String, String>): String? =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            VideoCache.open(url, headers, write = false).let { s ->
                try {
                    val buf = ByteArray(READ_BUF)
                    var remain = SAMPLE_BYTES
                    while (remain > 0) {
                        val r = s.source.read(buf, 0, min(READ_BUF, remain))
                        if (r == C.RESULT_END_OF_INPUT) break
                        md.update(buf, 0, r)
                        remain -= r
                    }
                } finally {
                    runCatching { s.source.close() }
                }
            }
            md.digest().toHex()
        }.onFailure { AppLog.putDebug("广告采样失败：$url") }.getOrNull()

    private fun contentLengthOf(url: String, headers: Map<String, String>): Long =
        runCatching {
            VideoCache.open(url, headers, write = false).let { s ->
                val len = s.length
                runCatching { s.source.close() }
                len
            }
        }.getOrDefault(-1L)

    // ---------------------------------------------------------------- 哈希

    private fun sha256(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun sha256Of(f: File): String? =
        runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { ins ->
                val buf = ByteArray(READ_BUF)
                var remain = SAMPLE_BYTES
                while (remain > 0) {
                    val r = ins.read(buf, 0, min(READ_BUF, remain))
                    if (r <= 0) break
                    md.update(buf, 0, r)
                    remain -= r
                }
            }
            md.digest().toHex()
        }.onFailure { AppLog.put("广告样本哈希计算失败：${f.name}", it) }.getOrNull()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- 状态查询

    /** 当前生效的指纹条数（用于设置页展示） */
    fun count(): Int = bySize.values.sumOf { it.size }

    /** 初始化：建议在播放器起来前调一次，避免首次判定时同步扫描 */
    fun warmUp() {
        if (!loaded) refresh()
    }

    /**
     * 指纹库是否还是空的（一条都没标记过）。
     *
     * [VideoCache] 用它来决定要不要去改写播放清单：**没有指纹就别碰播放链路**，
     * 让清单走原来的流式读取，行为与未启用广告过滤完全一致，零风险。
     */
    fun isLibraryEmpty(): Boolean {
        if (!loaded) refresh()
        if (extInfSeq.isNotEmpty()) return false
        if (extInfSeq.isEmpty()) loadSeqIndex()
        if (extInfSeq.isNotEmpty()) return false
        return bySize.isEmpty()
    }
}
