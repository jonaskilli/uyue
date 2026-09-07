package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

/**
 * AI 选定的一个音效。
 * @param name 音效名，如 "炒菜"
 * @param path 相对路径，如 "01_通用母库/拟音/炒菜.mp3"
 * @param at 触发位置：start / mid / end
 */
data class AiEffectPick(
    val name: String = "",
    val path: String = "",
    val at: String = "start"
)

/**
 * 一个段落的 AI 识别结果。
 *
 * **只有真正配了音效的段落才会存成记录**：[effects] 为空的段不写入（并会删掉旧记录），
 * 于是「无音效」等价于「没有这条记录」，下次朗读会重新识别，
 * 不会因为一次判断失误就永久没有音效。
 */
data class AiEffectRecord(
    val paraIndex: Int = 0,
    val effects: List<AiEffectPick> = emptyList(),
    val wallTime: Long = System.currentTimeMillis()
)

/**
 * AI 音效识别记录存储。
 *
 * 目录：`filesDir/ai_effect/{md5(bookUrl)}/{chapterIndex}.json`，每章一个 JSON 文件，
 * 与 [BgmTimelineStore] 同一套范式：文件存储不进 Room，避免数据库迁移。
 *
 * 记录的意义不只是导出复用：**已分析过的段落不再重复调用 AI**，
 * 重听、回跳都不会重复产生费用。
 *
 * 全部方法为 suspend，内部切 Dispatchers.IO；写操作经 [lock] 串行化。
 */
object AiEffectStore {

    private const val TAG = "AiEffectStore"
    private const val DIR_NAME = "ai_effect"

    private val lock = Mutex()

    private fun bookDir(bookUrl: String): File {
        val hash = try {
            MD5Utils.md5Encode16(bookUrl)
        } catch (_: Exception) {
            bookUrl.hashCode().toString()
        }
        return File(File(appCtx.filesDir, DIR_NAME), hash)
    }

    /** 指定章节的记录文件（可能不存在） */
    fun chapterFile(bookUrl: String, chapterIndex: Int): File =
        File(bookDir(bookUrl), "$chapterIndex.json")

    fun hasChapterData(bookUrl: String, chapterIndex: Int): Boolean {
        val f = chapterFile(bookUrl, chapterIndex)
        return f.exists() && f.length() > 0L
    }

    /** 读取整章记录，按段落升序；无记录或解析失败返回空列表 */
    suspend fun load(bookUrl: String, chapterIndex: Int): List<AiEffectRecord> =
        withContext(Dispatchers.IO) {
            val f = chapterFile(bookUrl, chapterIndex)
            if (!f.exists() || f.length() == 0L) return@withContext emptyList()
            try {
                val arr = JSONArray(f.readText())
                val list = ArrayList<AiEffectRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val effects = ArrayList<AiEffectPick>()
                    val effArr = o.optJSONArray("effects")
                    if (effArr != null) {
                        for (j in 0 until effArr.length()) {
                            val e = effArr.optJSONObject(j) ?: continue
                            val name = e.optString("name", "")
                            if (name.isBlank()) continue
                            effects.add(
                                AiEffectPick(
                                    name = name,
                                    path = e.optString("path", ""),
                                    at = e.optString("at", "start")
                                )
                            )
                        }
                    }
                    list.add(
                        AiEffectRecord(
                            paraIndex = o.optInt("paraIndex", 0),
                            effects = effects,
                            wallTime = o.optLong("wallTime", 0L)
                        )
                    )
                }
                list.sortedBy { it.paraIndex }
            } catch (e: Exception) {
                AppLog.put("$TAG: 读取记录失败 ${f.name}, ${e.localizedMessage}")
                emptyList()
            }
        }

    /**
     * 读取单个段落的结果。
     *
     * **只记录真正配了音效的段落**，因此：
     * @return null 表示该段落没有记录（从未分析过，或上次分析结果是「无音效」），
     *         需要重新调用 AI 分析；非 null 表示已配音效，直接复用，不再重复调用 AI。
     */
    suspend fun loadParagraph(
        bookUrl: String,
        chapterIndex: Int,
        paraIndex: Int
    ): List<AiEffectPick>? = load(bookUrl, chapterIndex)
        .firstOrNull { it.paraIndex == paraIndex }
        ?.effects
        ?.takeIf { it.isNotEmpty() }

    private suspend fun save(
        bookUrl: String,
        chapterIndex: Int,
        records: List<AiEffectRecord>
    ) = withContext(Dispatchers.IO) {
        try {
            val dir = bookDir(bookUrl)
            dir.mkdirs()
            val arr = JSONArray()
            records.sortedBy { it.paraIndex }.forEach { r ->
                val effArr = JSONArray()
                r.effects.forEach { e ->
                    effArr.put(
                        JSONObject().apply {
                            put("name", e.name)
                            put("path", e.path)
                            put("at", e.at)
                        }
                    )
                }
                arr.put(
                    JSONObject().apply {
                        put("paraIndex", r.paraIndex)
                        put("effects", effArr)
                        put("wallTime", r.wallTime)
                    }
                )
            }
            File(dir, "$chapterIndex.json").writeText(arr.toString())
        } catch (e: Exception) {
            AppLog.put("$TAG: 写入记录失败 $chapterIndex, ${e.localizedMessage}")
        }
    }

    /** 写入/覆盖一个段落的结果 */
    suspend fun mark(
        bookUrl: String,
        chapterIndex: Int,
        record: AiEffectRecord
    ) = lock.withLock {
        val list = load(bookUrl, chapterIndex).toMutableList()
        val idx = list.indexOfFirst { it.paraIndex == record.paraIndex }
        if (idx >= 0) {
            list[idx] = record
        } else {
            list.add(record)
        }
        save(bookUrl, chapterIndex, list)
    }

    /**
     * 删除单个段落的记录。
     *
     * 用途：某段分析结果为空（AI 判断不需要音效）时清掉可能存在的旧记录，
     * 使该段落回到「未分析」状态，下次朗读仍有机会重新识别。
     *
     * @return 是否确实删掉了记录（false 表示本来就没有）
     */
    suspend fun deleteParagraph(
        bookUrl: String,
        chapterIndex: Int,
        paraIndex: Int
    ): Boolean = lock.withLock {
        val list = load(bookUrl, chapterIndex).toMutableList()
        if (list.none { it.paraIndex == paraIndex }) return@withLock false
        save(bookUrl, chapterIndex, list.filter { it.paraIndex != paraIndex })
        AppLog.put("$TAG: 删除段落记录 $chapterIndex/$paraIndex")
        true
    }

    /** 删除指定章节的记录文件，返回是否确实删掉了 */
    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val f = chapterFile(bookUrl, chapterIndex)
                if (!f.exists()) return@withContext false
                val ok = f.delete()
                bookDir(bookUrl)
                    .takeIf { it.exists() && (it.list()?.isEmpty() == true) }
                    ?.delete()
                AppLog.put("$TAG: 删除章节记录 $chapterIndex -> $ok")
                ok
            } catch (e: Exception) {
                AppLog.put("$TAG: 删除章节记录失败 ${e.localizedMessage}")
                false
            }
        }

    /** 删除整本书的全部章节记录 */
    suspend fun deleteBook(bookUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = bookDir(bookUrl)
            if (!dir.exists()) return@withContext false
            val ok = dir.deleteRecursively()
            AppLog.put("$TAG: 删除本书全部AI音效记录 -> $ok")
            ok
        } catch (e: Exception) {
            AppLog.put("$TAG: 删除本书AI音效记录失败 ${e.localizedMessage}")
            false
        }
    }

    /** 清空所有书的记录 */
    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(appCtx.filesDir, DIR_NAME)
            if (!dir.exists()) return@withContext false
            val ok = dir.deleteRecursively()
            AppLog.put("$TAG: 清空全部AI音效记录 -> $ok")
            ok
        } catch (e: Exception) {
            AppLog.put("$TAG: 清空全部AI音效记录失败 ${e.localizedMessage}")
            false
        }
    }
}
