package io.legado.app.ui.book.toc

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
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
     * 获取章节的 AI 缓存文件
     */
    fun getChapterCacheFile(book: Book, chapter: BookChapter): File =
        getChapterCacheFileByName(book.name, chapter.title)

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

