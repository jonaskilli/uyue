package io.legado.app.ui.book.toc

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.QuoteFix
import org.json.JSONObject
import java.io.File

/**
 * AI 章节缓存文件工具类
 * 与 朗读脚本缓存版.js 中的文件命名规则保持一致
 */
object AiCacheFileUtil {

    private const val CACHE_ROOT = "/storage/emulated/0/Download/chajian/xiaoshuo/"

    /**
     * 章节名清洗规则。
     *
     * 与内置朗读脚本（assets/defaultData/ttsScripts.json）一致：
     * ```js
     * function sanitizeFileName(name) {
     *   return name.replace(/\?/g, "？").replace(/[\\/:*?"<>|]/g, "＿");
     * }
     * ```
     * 注意：这里用的是全角「？」与全角「＿」，且与书名规则不同。
     */
    fun sanitizeChapterFileName(name: String): String {
        return name.replace("?", "？")
            .replace(Regex("[\\\\/:*?\"<>|]"), "＿")
    }

    /**
     * 书名清洗规则。
     *
     * 与内置朗读脚本一致：
     * ```js
     * function getBookNameSafely() {
     *   return bookName.replace(/[\\/:*?"<>|]/g, "_").substring(0, 50);
     * }
     * ```
     * 脚本随后还会对结果套一层 sanitizeFileName，但此时非法字符已被
     * 替换成半角下划线，那一层是空操作，故最终规则即「半角下划线 + 截断 50」。
     */
    fun sanitizeBookName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(50)
    }

    /**
     * 获取书籍缓存目录
     */
    fun getBookDir(book: Book): File = getBookDirByName(book.name)

    /**
     * 按书名获取缓存目录。
     * 朗读脚本（ttsScripts.json）里的等价实现：
     * `sanitizeFileName(getBookNameSafely())`，
     * 后一步对书名已是空操作，故实际为「半角下划线 + 截断 50」。
     */
    fun getBookDirByName(bookName: String): File =
        File(CACHE_ROOT + sanitizeBookName(bookName) + "/")

    /**
     * 角色缓存统一使用的标题：替换规则 + 双引号修复后的标题。
     *
     * 朗读脚本按 data.json 首行标题定位章节并给角色缓存 json 命名，而阅读端此前
     * 用 `chapter.title`（原始标题）去找文件。一旦本书配了改标题的替换规则，
     * 两边名字对不上 ⇒ 标注全部丢失。三处（data.json 首行、脚本写盘、阅读端读盘）
     * 必须同源，故统一收口到本函数。
     */
    fun chapterCacheTitle(book: Book, chapter: BookChapter): String {
        val title = chapter.getDisplayTitle(
            ContentProcessor.get(book).getTitleReplaceRules(),
            book.getUseReplaceRule()
        )
        // 标题里的全角双引号要去掉：它与正文引号同形，会干扰「按左引号数序号」的
        // 全部逻辑（阅读端标注、脚本给对话编号同理）。
        return if (book.getFixQuote()) QuoteFix.fixTitle(title) else title
    }

    /**
     * 获取章节的 AI 缓存文件
     */
    fun getChapterCacheFile(book: Book, chapter: BookChapter): File =
        getChapterCacheFileByName(book.name, chapterCacheTitle(book, chapter))

    /**
     * 按书名与章节名获取缓存文件。
     * 朗读脚本里的等价实现：`getBookDir() + sanitizeFileName(chapterTitle) + ".json"`。
     */
    fun getChapterCacheFileByName(bookName: String, chapterTitle: String): File =
        File(getBookDirByName(bookName), sanitizeChapterFileName(chapterTitle) + ".json")

    /**
     * 检查章节是否有 AI 缓存文件
     */
    fun hasChapterCache(book: Book, chapter: BookChapter): Boolean {
        return getChapterCacheFile(book, chapter).exists()
    }

    /**
     * 章节改名：把记录姓名的 json 一起改名，并把文件里的 title 字段同步成新章节名。
     *
     * json 结构是 `{title: 章节名, results: {序号: {name...}}}`，姓名标注靠文件名定位，
     * 章节一改名就找不到了，所以改名必须连文件一起改。
     *
     * @return 是否确实改名成功（文件不存在/无需改名返回 false）
     */
    fun renameChapterCache(
        bookName: String,
        oldChapterTitle: String,
        newChapterTitle: String
    ): Boolean {
        if (oldChapterTitle == newChapterTitle) return false
        val oldFile = getChapterCacheFileByName(bookName, oldChapterTitle)
        if (!oldFile.exists()) return false
        val newFile = getChapterCacheFileByName(bookName, newChapterTitle)
        if (!newFile.exists() && !oldFile.renameTo(newFile)) {
            runCatching {
                oldFile.copyTo(newFile, true)
                oldFile.delete()
            }.getOrElse { return false }
        }
        updateCacheTitle(newFile, newChapterTitle)
        return true
    }

    /** 把缓存 json 里的 title 字段改成新章节名，改写失败不影响改名结果 */
    private fun updateCacheTitle(file: File, newTitle: String) {
        runCatching {
            if (!file.exists()) return
            val json = JSONObject(file.readText())
            if (json.optString("title").isNotEmpty()) {
                json.put("title", newTitle)
                file.writeText(json.toString())
            }
        }
    }

    /**
     * 删除章节的 AI 缓存文件
     */
    fun deleteChapterCache(book: Book, chapter: BookChapter): Boolean {
        val file = getChapterCacheFile(book, chapter)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * 获取书籍下所有 AI 缓存文件
     */
    fun getAllChapterCacheFiles(book: Book): List<File> {
        val bookDir = getBookDir(book)
        if (!bookDir.exists() || !bookDir.isDirectory) return emptyList()
        return bookDir.listFiles { _, name -> name.endsWith(".json") }?.toList() ?: emptyList()
    }

    /**
     * 删除整本书的所有 AI 缓存文件
     * 如果目录为空，也删除目录本身
     */
    fun deleteAllChapterCaches(book: Book): Int {
        val bookDir = getBookDir(book)
        if (!bookDir.exists() || !bookDir.isDirectory) return 0
        val files = bookDir.listFiles { _, name -> name.endsWith(".json") } ?: return 0
        var deletedCount = 0
        files.forEach {
            if (it.delete()) deletedCount++
        }
        // 如果目录为空，删除目录本身
        if (bookDir.listFiles().isNullOrEmpty()) {
            bookDir.delete()
        }
        return deletedCount
    }

    /**
     * 为文本添加角色标注：在每个左双引号 “ 后面插入 <<姓名（性别/年龄）>>。
     * 角色信息来自 AI 章节缓存文件（results 字段，键为双引号序号，值为 name/gender/age）。
     * 无缓存文件或解析失败时返回原文本。
     * 导出小说与 AI 生图共用此逻辑。
     */
    fun annotateTextWithRoles(text: String, book: Book, chapter: BookChapter): String {
        val cacheFile = getChapterCacheFile(book, chapter)
        if (!cacheFile.exists()) return text

        return try {
            val json = JSONObject(cacheFile.readText())
            val results = json.optJSONObject("results") ?: return text
            val roleMap = mutableMapOf<Int, Triple<String, String, String>>()
            results.keys().forEach { key ->
                val seq = key.toIntOrNull() ?: return@forEach
                val obj = results.getJSONObject(key)
                val name = obj.optString("name", "")
                if (name.isNotBlank()) {
                    roleMap[seq] = Triple(
                        name,
                        obj.optString("gender", ""),
                        obj.optString("age", "")
                    )
                }
            }
            if (roleMap.isEmpty()) return text

            val sb = StringBuilder()
            var seq = 0
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '“') {
                    seq++
                    sb.append(ch)
                    roleMap[seq]?.let { (name, gender, age) ->
                        val genderAge = buildString {
                            if (gender.isNotBlank()) append(gender)
                            if (gender.isNotBlank() && age.isNotBlank()) append("/")
                            if (age.isNotBlank()) append(age)
                        }
                        if (genderAge.isNotBlank()) {
                            sb.append("<<").append(name).append("（").append(genderAge).append("）>>")
                        } else {
                            sb.append("<<").append(name).append(">>")
                        }
                    }
                } else {
                    sb.append(ch)
                }
                i++
            }
            sb.toString()
        } catch (e: Exception) {
            text
        }
    }
}

