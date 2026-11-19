package io.legado.app.ui.book.read.page

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

    private val memoryCache = hashMapOf<String, Map<Int, RoleInfo>>()

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
        val cache = loadChapterCache(bookName, textChapter.title)
        if (cache.isEmpty()) return

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
