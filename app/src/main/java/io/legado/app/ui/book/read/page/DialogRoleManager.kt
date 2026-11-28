package io.legado.app.ui.book.read.page

import android.os.FileObserver
import android.os.SystemClock
import android.text.TextPaint
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.toc.AiCacheFileUtil
import io.legado.app.utils.dpToPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import io.legado.app.constant.AppLog

object DialogRoleManager {

    private const val CACHE_ROOT = "/storage/emulated/0/Download/chajian/xiaoshuo/"

    data class RoleInfo(
        val seq: Int,
        var name: String,
        var gender: String,
        var age: String,
        val dialogText: String?
    )

    data class RoleAnnotation(
        val seq: Int,
        val columnIndex: Int,
        val name: String,
        val gender: String,
        val age: String,
        val fullName: String,
        val labelStart: Float,
        val labelEnd: Float
    )

    // 11-28：改 ConcurrentHashMap。清空缓存（invalidateBookMemoryCache）会遍历
    // keys，而写盘/挂载在 IO 协程里并发读写；普通 HashMap 遍历时并发写会抛
    // ConcurrentModificationException（一旦抛在这里，标注刷新就整体失效）。
    private val memoryCache = java.util.concurrent.ConcurrentHashMap<String, Map<Int, RoleInfo>>()

    fun getCacheRoot(): String = CACHE_ROOT

    // ===== 11-20（角色标注写完即显）：脚本写缓存 → 防抖重挂 =====
    //
    // 背景：标注只在「排版完成/切章/翻页」这几个时刻挂载，而角色缓存是朗读
    // 过程中由脚本逐段写盘的。首次朗读一章时缓存是空的，挂载落空；脚本写完
    // 盘后没有通知机制，只能等下次翻页碰运气，或重启（强制重排版）。
    // 信号点在 JsExtensions.writeExternalFile（脚本写文件的唯一 Kotlin 出口，
    // 命中角色缓存路径时调 [notifyCacheWritten]）。
    //
    // 防抖 300ms：合成是逐段提交的，同一章几秒内会连写多次；每次都挂会
    // 重复重绘。防抖把一批写收敛成「最后写完那次」挂载，体验是「这段的
    // AI 结果一到，标注整批出现」。挂载失败（排版未完成/无回调）静默：
    // 下一次写或翻页仍会触发，不会丢。
    private var pendingReattachJob: Job? = null
    private val reattachScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun notifyCacheWritten() {
        if (pendingReattachJob?.isActive == true) return
        pendingReattachJob = reattachScope.launch {
            delay(300)
            val bookName = ReadBook.book?.name ?: return@launch
            // 只重挂当前正在阅读的那一章；其他章的缓存写入等切章时自然挂上
            val textChapter = ReadBook.curTextChapter ?: return@launch
            clearChapterCache(bookName, textChapter.title)
            attachAnnotations(textChapter, bookName)
            withContext(Dispatchers.Main) {
                ReadBook.callBack?.upContent()
            }
        }
    }

    // ===== 11-21 20:3x（用户令「实时朗读分析那个标注也要立马显示」）=====
    //
    // 朗读链的缓存写盘跑在 **UTTS 进程**（规则 JS 经 lib-script 的
    // ttsrv.writeTxtFile 落 /storage/emulated/0/Download/chajian/xiaoshuo/），
    // U阅 进程内没有任何函数调用可以挂钩——11-20 接在 writeExternalFile 的
    // 信号覆盖不到它（那条路径是 U阅 自己的写盘入口，朗读链不走）。
    //
    // 正解 = **FileObserver 盯当前书的缓存目录**：不管写盘方是 UTTS 的朗读
    // 脚本、U阅 的全书分析服务还是手动改文件，CLOSE_WRITE/MOVED_TO/CREATE
    // 一律触发，防抖 300ms 走现成的 [notifyCacheWritten]。与 UTTS 日志页的
    // ReadProgressReader 同款「盯目录不盯文件」口径（防 inode 更换静默失效）。
    //
    // 生命周期：阅读页活跃期间常驻（startWatching 开销≈0，inotify 回调仅
    // 文件变化时一次）；书名变化（切书）由 [watchBook] 重新挂目标目录。
    @Volatile
    private var cacheWatcher: FileObserver? = null
    @Volatile
    private var watchedBook: String? = null
    private val watcherLock = Any()

    /** 事件线程防抖节流：目录高频写入时只让一个 300ms 窗口活着 */
    @Volatile
    private var watcherDebounceAt = 0L

    /** 切书/进阅读页时调用：把观察器挪到当前书的缓存目录。 */
    fun watchBook(bookName: String?) {
        synchronized(watcherLock) {
            val target = bookName?.takeIf { it.isNotBlank() } ?: run {
                cacheWatcher?.stopWatching()
                cacheWatcher = null
                watchedBook = null
                return
            }
            if (watchedBook == target) return
            cacheWatcher?.stopWatching()
            val dir = getBookFolder(target)
            if (!dir.isDirectory) {
                // 目录还没建（本书从没分析过）：不挂观察器，等首次写盘/翻页
                // 的挂载路径自然走到（attach 的书名与目录随后由翻页链补上）
                watchedBook = null
                return
            }
            val mask = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
                FileObserver.CREATE or FileObserver.DELETE
            cacheWatcher = object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    if (!path.endsWith(".json")) return
                    // 粗节流：300ms 内的连发只透传第一发（细防抖在 notifyCacheWritten）
                    val now = SystemClock.elapsedRealtime()
                    if (now - watcherDebounceAt < 300) return
                    watcherDebounceAt = now
                    notifyCacheWritten()
                }
            }.also { it.startWatching() }
            watchedBook = target
        }
    }

    /**
     * 11-21 20:3x（用户令「打开角色标注开关立马显示」）：开关切换的即时刷新。
     * 与 notifyCacheWritten 同一套防抖重挂，但**清空后强制挂载**：
     *   · 开 → 若本章已有分析缓存立即上屏（attachAnnotations 内部按开关门控，
     *     关闭态下它直接 return，所以这里不用再判）；
     *   · 关 → 需要把已画的标注清掉。走 ReadBook.loadRoleAnnotations 的关闭
     *     清理口径（它对关态清缓存+upContent），关开关立即无痕。
     * 防抖复用 pendingReattachJob：开关连拨/分析写入并发时只挂一次。
     */
    fun notifyToggleChanged() {
        if (!AppConfig.showRoleAnnotation) {
            // 关：清空各行已挂的标注并重绘（roleAnnotations 是 val 列表，用
            // setRoleAnnotations(emptyList) 同款清法，保持 synchronized 口径）。
            // 清内存缓存在 IO 走协程，主线程回调复用文件内既有的 withContext 模式。
            val textChapter = ReadBook.curTextChapter ?: return
            reattachScope.launch {
                textChapter.pages.forEach { page ->
                    page.lines.forEach { line -> line.setRoleAnnotations(emptyList()) }
                }
                withContext(Dispatchers.Main) {
                    ReadBook.callBack?.upContent()
                }
            }
            return
        }
        if (pendingReattachJob?.isActive == true) return
        pendingReattachJob = reattachScope.launch {
            delay(300)
            val bookName = ReadBook.book?.name ?: return@launch
            val textChapter = ReadBook.curTextChapter ?: return@launch
            clearChapterCache(bookName, textChapter.title)
            attachAnnotations(textChapter, bookName)
            withContext(Dispatchers.Main) {
                ReadBook.callBack?.upContent()
            }
        }
    }

    /**
     * 章节名清洗规则。
     *
     * 实际实现在 [AiCacheFileUtil.sanitizeChapterFileName]，与内置朗读脚本一致。
     * 注意：**不要用它处理书名**，书名规则不同（见 [getBookFolder]）。
     */
    fun sanitizeFileName(name: String): String =
        AiCacheFileUtil.sanitizeChapterFileName(name)

    /**
     * 书名目录。
     *
     * 必须用书名的规则（半角下划线 + 截断 50），
     * 与写入方 [AiCacheFileUtil.getBookDirByName] 及朗读脚本保持一致。
     */
    private fun getBookFolder(bookName: String): File =
        AiCacheFileUtil.getBookDirByName(bookName)

    /**
     * 扫描小说文件夹中的图片文件（关闭 AI 生图时，朗读界面小图与全屏大图都靠它轮播本地图片）。
     * 目录：[getBookFolder]，即 CACHE_ROOT + 书名清洗规则。
     * 支持 jpg/jpeg/png/gif/webp/bmp，按文件名排序返回。
     */
    fun scanBookImages(bookName: String): List<File> {
        val bookFolder = getBookFolder(bookName)
        if (!bookFolder.exists() || !bookFolder.isDirectory) return emptyList()
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        return bookFolder.listFiles { file ->
            file.isFile && file.extension.lowercase() in imageExtensions
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun getCacheFile(bookName: String, chapterTitle: String): File {
        val file = AiCacheFileUtil.getChapterCacheFileByName(bookName, chapterTitle)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        return file
    }

    fun loadChapterCache(bookName: String, chapterTitle: String): Map<Int, RoleInfo> {
        val cacheKey = "$bookName|$chapterTitle"
        memoryCache[cacheKey]?.let { return it }

        val file = getCacheFile(bookName, chapterTitle)
        if (!file.exists()) return emptyMap()

        return try {
            val json = JSONObject(file.readText())
            val results = json.optJSONObject("results") ?: return emptyMap()
            val map = mutableMapOf<Int, RoleInfo>()
            results.keys().forEach { key ->
                val seq = key.toIntOrNull() ?: return@forEach
                val obj = results.getJSONObject(key)
                map[seq] = RoleInfo(
                    seq = seq,
                    name = obj.optString("name", ""),
                    gender = obj.optString("gender", ""),
                    age = obj.optString("age", ""),
                    dialogText = obj.optString("dialogText", null)
                )
            }
            memoryCache[cacheKey] = map
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveRole(
        bookName: String,
        chapterTitle: String,
        seq: Int,
        name: String,
        gender: String,
        age: String
    ) {
        val file = getCacheFile(bookName, chapterTitle)
        val json = if (file.exists()) {
            try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                JSONObject()
            }
        } else {
            JSONObject()
        }

        val results = json.optJSONObject("results")
            ?: JSONObject().also { json.put("results", it) }
        val existing = results.optJSONObject(seq.toString()) ?: JSONObject()
        existing.put("name", name)
        existing.put("gender", gender)
        existing.put("age", age)
        if (!existing.has("dialogText")) existing.put("dialogText", "")
        results.put(seq.toString(), existing)

        if (!json.has("title")) json.put("title", chapterTitle)

        file.writeText(json.toString(2))

        val cacheKey = "$bookName|$chapterTitle"
        val map = memoryCache[cacheKey]?.toMutableMap() ?: mutableMapOf()
        val old = map[seq]
        map[seq] = RoleInfo(seq, name, gender, age, old?.dialogText)
        memoryCache[cacheKey] = map
    }

    fun clearChapterCache(bookName: String, chapterTitle: String) {
        memoryCache.remove("$bookName|$chapterTitle")
    }

    /**
     * 作废整本书的内存缓存。
     *
     * 11-28 用户实报「清空阅读小说目录的 AI 缓存后，正文标注不会立马消失」：
     * 目录页的删除只删磁盘 json（[AiCacheFileUtil.deleteAllChapterCaches] /
     * [AiCacheFileUtil.deleteChapterCache]），**没人清 [memoryCache]**。
     * 于是即便重新挂载，[loadChapterCache] 仍从内存里拿到旧条目 → 标注照旧。
     * 删除侧必须显式调用本函数。
     */
    fun invalidateBookMemoryCache(bookName: String) {
        synchronized(memoryCache) {
            val prefix = "$bookName|"
            memoryCache.keys.filter { it.startsWith(prefix) }.forEach { memoryCache.remove(it) }
        }
    }

    /**
     * 缓存被清空后的强制刷新：作废内存缓存 + 清掉已画的标注 + 重绘。
     *
     * 与 [notifyCacheWritten] 同一套重挂，但语义相反——那边是「缓存变多了」，
     * 这边是「缓存变没了」，必须保证空缓存也能把旧标注抹掉。
     *
     * 删除动作多发生在**目录页（另一个 Activity）**，此时本页可能排不上重绘；
     * 所以同时记一个脏标记，由阅读页 onResume 调 [flushPendingClear] 兜底。
     */
    fun notifyCacheCleared(bookName: String) {
        invalidateBookMemoryCache(bookName)
        // 「删除全部AI缓存」在目录为空时会把书目录本身也删掉（AiCacheFileUtil），
        // 观察器盯的就是这个目录 —— 目录没了 inotify 就静默失效，且 watchBook 里
        // `watchedBook == target` 会让它以后都不再重挂。这里主动作废观察器，
        // 下一次 watchBook（翻页/切章都会调）在目录重建后重新挂上。
        synchronized(watcherLock) {
            pendingClearedBook = bookName
            if (watchedBook == bookName) {
                cacheWatcher?.stopWatching()
                cacheWatcher = null
                watchedBook = null
            }
        }
        reattachCleared(bookName)
    }

    /** 有清空动作待阅读页确认（跨 Activity 兜底用） */
    @Volatile
    private var pendingClearedBook: String? = null

    /**
     * 阅读页 onResume 调用：把上次没排上的清空刷新补做一次。
     *
     * 正常路径下 [notifyCacheCleared] 已经重挂过了，这里只是幂等补做，不增加
     * 任何额外绘制（无脏标记直接返回）。存在的理由：目录页删缓存时阅读页处于
     * paused，重绘可能被推迟，用户回来看到的就是「标注还在」。
     */
    fun flushPendingClear() {
        val bookName = synchronized(watcherLock) {
            val b = pendingClearedBook
            pendingClearedBook = null
            b
        } ?: return
        invalidateBookMemoryCache(bookName)
        reattachCleared(bookName)
    }

    /** 真正执行「作废并重挂」。清的不是当前书时只作废内存缓存，不碰屏上内容 */
    private fun reattachCleared(bookName: String) {
        // 目录页是独立 Activity，任何书都可能在这里被清，不能盲目抹当前页标注
        val curBookName = ReadBook.book?.name
        if (curBookName != null && curBookName != bookName) return
        if (ReadBook.curTextChapter == null) return
        // 走阅读页的标准重挂链：watchBook（观察器刚才被我主动停掉，目录重建后
        // 要重新挂上，否则以后朗读写盘不再即时上屏）+ clearChapterCache +
        // attachAnnotations + upContent。attachAnnotations 在缓存空时会清空
        // 各行已画的标注，故这里不需要再单独抹一遍。
        ReadBook.reloadRoleAnnotations()
    }

    // ===== 11-21（缓存重排）：替换规则改正文后，旧分析结果搬到新序号上 =====
    //
    // 用户边读边加「阅读替换净化」规则（如「非对话双引号改单引号」），正文变了，
    // 缓存还按旧引号个数编号 → 页面标注挂错人（序号整体平移）。台词文字没变或
    // 只变了符号，不必重花 AI 分析：本地把旧条目对齐到新序号即可。
    //
    // 挂载点选在 attachAnnotations 入口（排版完成后、IO 线程）：
    // · 只在「当前正文引号台词与缓存对不上」时才写盘（DialogCacheRealignment 先检测）；
    // · 写盘前先备份 .bak（防呆），对齐失败的条目进 orphan 不删；
    // · RealignmentStatus.realignFailed / 开关关闭：静默跳过，行为与旧版完全一致；
    // · 全程在本函数已有的 IO 线程里执行，毫秒级，不碰朗读链路。
    private fun realignCacheIfNeeded(textChapter: TextChapter, bookName: String) {
        if (!AppConfig.roleAnnotationRealignment) return
        val chapterTitle = textChapter.title
        if (chapterTitle.isBlank()) return
        val cacheKey = "$bookName|$chapterTitle"
        val cache = loadChapterCache(bookName, chapterTitle)
        if (cache.isEmpty()) return

        val content = textChapter.getContent()
        if (content.isBlank()) return

        // 快检：台词文字与条数都一致就返回（绝大多数情况），零写盘
        val quotes = DialogCacheRealignment.extractQuotes(content)
        if (quotes.isEmpty()) return
        val cacheNorm = cache.toSortedMap().values.mapNotNull {
            DialogCacheRealignment.normalize(it.dialogText ?: "").takeIf { n -> n.isNotEmpty() }
        }
        val contentNorm = quotes.map { DialogCacheRealignment.normalize(it.second) }
        if (cacheNorm == contentNorm) return

        // 对不上 → 重排：读原始 results，纯函数对齐，备份后写盘
        val file = getCacheFile(bookName, chapterTitle)
        val json = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            return
        }
        val oldResults = json.optJSONObject("results") ?: return
        val t0 = System.currentTimeMillis()
        val realigned = DialogCacheRealignment.buildRealignedResults(content, oldResults)
            ?: return
        val elapsedMs = System.currentTimeMillis() - t0
        // 重排产出的映射必须真的改变了什么才写盘（完全相同则不动）。
        // 11-21 15:5x 自循环守卫：孤儿回填后缓存台词序列≠正文台词序列是**常态**，
        // 快检必然不通过、每次挂标注都会重进这里；若这次重排对键集与内容毫无改变
        // （上一轮已回填到位），写盘纯属重复——跳过。
        var changed = false
        val oldKeys = sortedSetOf<Int>()
        oldResults.keys().forEach { it.toIntOrNull()?.let { s -> oldKeys.add(s) } }
        val newKeys = sortedSetOf<Int>()
        realigned.results.keys().forEach { it.toIntOrNull()?.let { s -> newKeys.add(s) } }
        if (oldKeys != newKeys) changed = true
        else {
            for (k in newKeys) {
                val oldObj = oldResults.optJSONObject(k.toString())
                val newObj = realigned.results.optJSONObject(k.toString())
                if (oldObj?.optString("name") != newObj?.optString("name")
                    || oldObj?.optString("dialogText") != newObj?.optString("dialogText")
                ) { changed = true; break }
            }
        }
        if (!changed) return

        try {
            val bak = File(file.parentFile, file.name + ".bak")
            if (!bak.exists()) runCatching { file.copyTo(bak, overwrite = false) }
            json.put("results", realigned.results)
            file.writeText(json.toString(2))
            AppLog.put("缓存重排: 《${bookName}》${chapterTitle.take(12)} → 新${realigned.mappedCount}条"
                + (if (realigned.orphans.isNotEmpty()) "（孤儿${realigned.orphans.size}条:${realigned.orphans.take(5)}）" else "")
                + "，耗时${elapsedMs}ms")
        } catch (e: Exception) {
            AppLog.put("缓存重排失败（保留原缓存）: ${e.localizedMessage}", e)
            return
        }
        // 内存缓存作废，本函数返回后 attachAnnotations 会重新 load 拿到新序号
        memoryCache.remove(cacheKey)
    }


    /**
     * 将当前章节 JSON 中所有名为 [oldName] 的角色（姓名/性别/年龄）替换为新值。
     * 按旧名字全文搜索匹配，不依赖 seq。只作用于当前章节。
     */
    fun replaceChapterRole(
        bookName: String,
        chapterTitle: String,
        oldName: String,
        name: String,
        gender: String,
        age: String
    ) {
        val file = getCacheFile(bookName, chapterTitle)
        if (!file.exists()) return
        val json = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            return
        }
        val results = json.optJSONObject("results") ?: return
        var changed = false
        results.keys().forEach { key ->
            val obj = results.optJSONObject(key) ?: return@forEach
            if (obj.optString("name", "") == oldName) {
                obj.put("name", name)
                obj.put("gender", gender)
                obj.put("age", age)
                changed = true
            }
        }
        if (!changed) return
        file.writeText(json.toString(2))

        // 同步刷新内存缓存
        val cacheKey = "$bookName|$chapterTitle"
        val map = memoryCache[cacheKey]?.toMutableMap() ?: return
        map.values.forEach { role ->
            if (role.name == oldName) {
                role.name = name
                role.gender = gender
                role.age = age
            }
        }
        memoryCache[cacheKey] = map
    }

    /**
     * 将全本书所有章节 JSON 中名为 [oldName] 的角色（姓名/性别/年龄）替换为新值。
     * 按旧名字全文搜索匹配，遍历本书目录下所有 .json 文件的所有角色。
     * 书名目录沿用 [AiCacheFileUtil.getBookDirByName] 的规则，确保与生成 json 时一致。
     */
    fun replaceBookRole(
        bookName: String,
        oldName: String,
        name: String,
        gender: String,
        age: String
    ) {
        val bookFolder = getBookFolder(bookName)
        if (!bookFolder.exists() || !bookFolder.isDirectory) return
        val jsonFiles = bookFolder.listFiles { f ->
            f.isFile && f.extension.lowercase() == "json"
        } ?: return

        jsonFiles.forEach { file ->
            val json = try {
                JSONObject(file.readText())
            } catch (e: Exception) {
                return@forEach
            }
            val results = json.optJSONObject("results") ?: return@forEach
            var changed = false
            results.keys().forEach { key ->
                val obj = results.optJSONObject(key) ?: return@forEach
                if (obj.optString("name", "") == oldName) {
                    obj.put("name", name)
                    obj.put("gender", gender)
                    obj.put("age", age)
                    changed = true
                }
            }
            if (!changed) return@forEach
            file.writeText(json.toString(2))

            // 同步刷新内存缓存（文件名即章节名去扩展名）
            val chapterTitle = file.nameWithoutExtension
            val cacheKey = "$bookName|$chapterTitle"
            val map = memoryCache[cacheKey]?.toMutableMap() ?: return@forEach
            map.values.forEach { role ->
                if (role.name == oldName) {
                    role.name = name
                    role.gender = gender
                    role.age = age
                }
            }
            memoryCache[cacheKey] = map
        }
    }

    fun collectNeighborRoles(
        bookName: String,
        chapterTitle: String,
        currentSeq: Int,
        currentName: String,
        chapterTitles: List<String>,
        currentChapterIndex: Int,
        /** 最多跨几个章节找相邻角色：1 = 只看当前章 + 前后各一章（读文件少，出结果快） */
        maxChapterSpan: Int = Int.MAX_VALUE
    ): List<RoleInfo> {
        val seen = mutableSetOf<String>()
        if (currentName.isNotBlank()) seen.add(currentName)

        val prevRoles = mutableListOf<RoleInfo>()
        val nextRoles = mutableListOf<RoleInfo>()

        val currentCache = loadChapterCache(bookName, chapterTitle)

        // 当前章节：前面4个（seq 递减，越近越前）
        var i = currentSeq - 1
        while (i >= 1 && prevRoles.size < 4) {
            currentCache[i]?.let { role ->
                if (role.name.isNotBlank() && !seen.contains(role.name)) {
                    seen.add(role.name)
                    prevRoles.add(role)
                }
            }
            i--
        }

        // 当前章节：后面5个（seq 递增）
        i = currentSeq + 1
        val maxSeq = currentCache.keys.maxOrNull() ?: Int.MAX_VALUE
        while (i <= maxSeq && nextRoles.size < 5) {
            currentCache[i]?.let { role ->
                if (role.name.isNotBlank() && !seen.contains(role.name)) {
                    seen.add(role.name)
                    nextRoles.add(role)
                }
            }
            i++
        }

        // 向前跨章节补充（从上一章末尾往前取）
        var chapterIdx = currentChapterIndex - 1
        while (chapterIdx >= 0
            && chapterIdx >= currentChapterIndex - maxChapterSpan
            && prevRoles.size < 4
        ) {
            val prevCache = loadChapterCache(bookName, chapterTitles[chapterIdx])
            if (prevCache.isNotEmpty()) {
                val prevMaxSeq = prevCache.keys.maxOrNull() ?: 0
                var seq = prevMaxSeq
                while (seq >= 1 && prevRoles.size < 4) {
                    prevCache[seq]?.let { role ->
                        if (role.name.isNotBlank() && !seen.contains(role.name)) {
                            seen.add(role.name)
                            prevRoles.add(role)
                        }
                    }
                    seq--
                }
            }
            chapterIdx--
        }

        // 向后跨章节补充（从下一章开头往后取）
        chapterIdx = currentChapterIndex + 1
        while (chapterIdx < chapterTitles.size
            && chapterIdx <= currentChapterIndex + maxChapterSpan
            && nextRoles.size < 5
        ) {
            val nextCache = loadChapterCache(bookName, chapterTitles[chapterIdx])
            if (nextCache.isNotEmpty()) {
                val nextMaxSeq = nextCache.keys.maxOrNull() ?: 0
                var seq = 1
                while (seq <= nextMaxSeq && nextRoles.size < 5) {
                    nextCache[seq]?.let { role ->
                        if (role.name.isNotBlank() && !seen.contains(role.name)) {
                            seen.add(role.name)
                            nextRoles.add(role)
                        }
                    }
                    seq++
                }
            }
            chapterIdx++
        }

        return prevRoles.reversed() + nextRoles
    }

    fun attachAnnotations(textChapter: TextChapter, bookName: String) {
        if (!AppConfig.showRoleAnnotation) return
        if (bookName.isBlank()) return
        realignCacheIfNeeded(textChapter, bookName)
        val cache = loadChapterCache(bookName, textChapter.title)
        if (cache.isEmpty()) {
            // 11-28：缓存为空必须**清掉已画的标注**，不能直接 return。
            // 用户「清空 AI 缓存后正文标注不消失」的最后一环就在这里：清空 →
            // 重挂 → 缓存空 → 早退 → 上一次挂上的 roleAnnotations 原样留在行上，
            // 要等重排版（切章/重启）才消失。这里补 setRoleAnnotations(emptyList())，
            // 与关开关那条清理路径同一口径。
            textChapter.pages.forEach { page ->
                page.lines.forEach { line -> line.setRoleAnnotations(emptyList()) }
            }
            return
        }

        var globalSeq = 0
        textChapter.pages.forEach { page ->
            page.lines.forEach { line ->
                val tempAnnotations = mutableListOf<RoleAnnotation>()
                line.columns.forEachIndexed { index, column ->
                    if (column is io.legado.app.ui.book.read.page.entities.column.TextColumn
                        && column.charData == "“"
                    ) {
                        globalSeq++
                        val role = cache[globalSeq] ?: return@forEachIndexed
                        if (role.name.isBlank()) return@forEachIndexed

                        // 不限字数：名字多长就显示多长（11-20 用户令，原 take(5) 会把
                        // 「洛克乌德先生」截成「洛克乌德先」）。宽度实时测量，点击区域随之变宽。
                        val label = role.name
                        val paint = annotationPaint
                        paint.textSize = ChapterProvider.contentPaint.textSize * 0.55f
                        val labelWidth = paint.measureText(label)
                        val x = column.start

                        tempAnnotations.add(
                            RoleAnnotation(
                                seq = globalSeq,
                                columnIndex = index,
                                name = label,
                                gender = role.gender,
                                age = role.age,
                                fullName = role.name,
                                labelStart = x,
                                labelEnd = x + labelWidth
                            )
                        )
                    }
                }
                line.setRoleAnnotations(tempAnnotations)
            }
        }
    }

    val annotationPaint: TextPaint by lazy {
        TextPaint(ChapterProvider.contentPaint).apply {
            isAntiAlias = true
        }
    }
}
