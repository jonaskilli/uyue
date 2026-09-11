package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * AI 章节修复记录。
 *
 * 目录：`filesDir/ai_chapter_fix/{md5(bookUrl)}/{chapterIndex}.json`，每章一个文件，
 * 与 [AiEffectStore] 同一套范式（文件存储，不进 Room，避免数据库迁移）。
 *
 * 状态含义：
 *  - [STATUS_FIXED]：已按 AI 返回的内容改写过正文，之后不再重复处理；
 *  - [STATUS_CLEAN]：AI 检查后认为无需修改，同属正常结果，之后也不再处理；
 *  - [STATUS_ERROR]：请求失败 / 返回无法解析 / AI 拒绝，属于异常，下次仍要重试。
 *
 * 所有方法均为 suspend，内部切 [Dispatchers.IO]；写操作经 [lock] 串行化。
 */
object AiChapterFixStore {

    private const val TAG = "AiChapterFixStore"
    private const val DIR_NAME = "ai_chapter_fix"

    const val STATUS_FIXED = "fixed"
    const val STATUS_CLEAN = "clean"
    const val STATUS_ERROR = "error"

    private val lock = Mutex()

    private fun bookDir(bookUrl: String): File {
        val hash = try {
            MD5Utils.md5Encode16(bookUrl)
        } catch (_: Exception) {
            bookUrl.hashCode().toString()
        }
        return File(File(appCtx.filesDir, DIR_NAME), hash)
    }

    fun chapterFile(bookUrl: String, chapterIndex: Int): File =
        File(bookDir(bookUrl), "$chapterIndex.json")

    /** 读取状态；无记录返回 null */
    suspend fun status(bookUrl: String, chapterIndex: Int): String? = lock.withLock {
        readStatus(bookUrl, chapterIndex)
    }

    private suspend fun readStatus(bookUrl: String, chapterIndex: Int): String? =
        withContext(Dispatchers.IO) {
            val f = chapterFile(bookUrl, chapterIndex)
            if (!f.exists() || f.length() == 0L) return@withContext null
            try {
                JSONObject(f.readText()).optString("status").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                AppLog.put("$TAG: 读取记录失败 $chapterIndex, ${e.localizedMessage}")
                null
            }
        }

    /**
     * 该章是否已处理过。
     * 只有「已修复」和「确认无需修改」算处理过；失败/拒绝要重试，返回 false。
     */
    suspend fun isDone(bookUrl: String, chapterIndex: Int): Boolean {
        return when (status(bookUrl, chapterIndex)) {
            STATUS_FIXED, STATUS_CLEAN -> true
            else -> false
        }
    }

    /** 写入状态，[changedParagraphs] 记录本次改动的段落数（便于排查） */
    suspend fun mark(
        bookUrl: String,
        chapterIndex: Int,
        status: String,
        changedParagraphs: Int = 0
    ) = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                val dir = bookDir(bookUrl)
                dir.mkdirs()
                val json = JSONObject().apply {
                    put("status", status)
                    put("changed", changedParagraphs)
                    put("time", System.currentTimeMillis())
                }
                // 先写临时文件再改名，避免写一半被读成「没有记录」而导致重复处理
                val file = File(dir, "$chapterIndex.json")
                val tmp = File(dir, "$chapterIndex.json.tmp")
                tmp.writeText(json.toString())
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, true)
                    tmp.delete()
                }
            } catch (e: Exception) {
                AppLog.put("$TAG: 写入记录失败 $chapterIndex, ${e.localizedMessage}")
            }
        }
    }

    /** 删除整本书的记录 */
    suspend fun deleteBook(bookUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = bookDir(bookUrl)
            if (!dir.exists()) return@withContext false
            dir.deleteRecursively()
        } catch (e: Exception) {
            AppLog.put("$TAG: 删除本书记录失败 ${e.localizedMessage}")
            false
        }
    }
}
