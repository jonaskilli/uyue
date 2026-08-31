package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
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
 * 一条背景音乐播放记录。
 *
 * 语义：**从 [paraIndex] 这一段开始播放 [bgmName]，一直放到下一条记录为止**。
 * 所以记录管的是一段区间而非一个点，中间没有记录的段落自动归前一首管辖。
 *
 * @param bgmName 曲目名（不含扩展名）
 * @param paraIndex 起始段落索引
 * @param posInParaMs 段落内已播放位置（毫秒），段内跳转时用于精确定位
 * @param songOffsetMs 这首歌从自己的第几毫秒开始放。
 *                     跨章节接续（上一章放了一半、本章接后半段）靠它还原。
 * @param volume 记录当时的 BGM 音量，仅作排查参考；导出统一取 AppConfig.bgmVolume
 * @param stale 是否已被跨过作废。**不物理删除**，保留作为导出时候选切换点：
 *              当前这首铺够 AppConfig.bgmMinSegmentMs 后才允许切换到候选点，
 *              否则跳过，由前一首延长覆盖。
 * @param wallTime 产生时间，仅用于排查
 */
data class BgmMark(
    val bgmName: String,
    val paraIndex: Int,
    val posInParaMs: Long = 0L,
    val songOffsetMs: Long = 0L,
    val volume: Int = AppConfig.bgmVolume,
    val stale: Boolean = false,
    val wallTime: Long = System.currentTimeMillis()
)

/**
 * 背景音乐播放记录（"小本本"）存储。
 *
 * 目录：`filesDir/bgm_timeline/{md5(bookUrl)}/{chapterIndex}.json`，每章一个 JSON 文件。
 *
 * 用文件而非 Room：BgmTimeline 是易失的播放轨迹，随章节合并完成即可清理；
 * 且 AppDatabase 当前 version=100，新增表要写 migration，风险高于收益。
 *
 * 全部方法为 suspend，内部切 Dispatchers.IO，可直接在主线程调用。
 * 写操作通过 [lock] 串行化，避免段落快速推进时并发写互相覆盖。
 */
object BgmTimelineStore {

    private const val TAG = "BgmTimelineStore"
    private const val DIR_NAME = "bgm_timeline"

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

    /** 该章节是否已有记录 */
    fun hasChapterData(bookUrl: String, chapterIndex: Int): Boolean {
        val f = chapterFile(bookUrl, chapterIndex)
        return f.exists() && f.length() > 0L
    }

    /** 读取指定章节记录，按段落升序；无记录或解析失败返回空列表 */
    suspend fun load(bookUrl: String, chapterIndex: Int): List<BgmMark> =
        withContext(Dispatchers.IO) {
            val f = chapterFile(bookUrl, chapterIndex)
            if (!f.exists() || f.length() == 0L) return@withContext emptyList()
            try {
                val arr = JSONArray(f.readText())
                val list = ArrayList<BgmMark>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("bgmName", "")
                    if (name.isBlank()) continue
                    list.add(
                        BgmMark(
                            bgmName = name,
                            paraIndex = o.optInt("paraIndex", 0),
                            posInParaMs = o.optLong("posInParaMs", 0L),
                            songOffsetMs = o.optLong("songOffsetMs", 0L),
                            volume = o.optInt("volume", AppConfig.bgmVolume),
                            stale = o.optBoolean("stale", false),
                            wallTime = o.optLong("wallTime", 0L)
                        )
                    )
                }
                list.sortedWith(compareBy<BgmMark> { it.paraIndex }.thenBy { it.posInParaMs })
            } catch (e: Exception) {
                AppLog.put("$TAG: 读取记录失败 ${f.name}, ${e.localizedMessage}")
                emptyList()
            }
        }

    private suspend fun save(
        bookUrl: String,
        chapterIndex: Int,
        marks: List<BgmMark>
    ) = withContext(Dispatchers.IO) {
        try {
            val dir = bookDir(bookUrl)
            dir.mkdirs()
            val arr = JSONArray()
            marks.sortedWith(compareBy<BgmMark> { it.paraIndex }.thenBy { it.posInParaMs })
                .forEach { m ->
                    arr.put(
                        JSONObject().apply {
                            put("bgmName", m.bgmName)
                            put("paraIndex", m.paraIndex)
                            put("posInParaMs", m.posInParaMs)
                            put("songOffsetMs", m.songOffsetMs)
                            put("volume", m.volume)
                            put("stale", m.stale)
                            put("wallTime", m.wallTime)
                        }
                    )
                }
            File(dir, "$chapterIndex.json").writeText(arr.toString())
        } catch (e: Exception) {
            AppLog.put("$TAG: 写入记录失败 $chapterIndex, ${e.localizedMessage}")
        }
    }

    /**
     * 写入一条记录。
     *
     * 「回跳不批量删」规则：
     * - 该段落已有记录 → 覆盖它（视为本次收听在此处切歌），并清除 stale 标记
     * - 该段落没有记录 → 插入，其余记录保持不动（后面的先留着）
     */
    suspend fun mark(bookUrl: String, chapterIndex: Int, mark: BgmMark) = lock.withLock {
        val list = load(bookUrl, chapterIndex).toMutableList()
        val idx = list.indexOfFirst { it.paraIndex == mark.paraIndex }
        if (idx >= 0) {
            list[idx] = mark.copy(stale = false)
        } else {
            list.add(mark)
        }
        save(bookUrl, chapterIndex, list)
    }

    /**
     * 作废被跨过的记录：把起始段落落在 (fromPara, toPara] 内的记录标为 stale。
     *
     * **只标记不删除**——保留它们是为了导出时作为候选切换点：
     * 当前曲目铺够 AppConfig.bgmMinSegmentMs 后遇到候选点才切换，否则跳过由前一首延长。
     * 真正的清除发生在章节合并完成时。
     */
    suspend fun invalidateRange(
        bookUrl: String,
        chapterIndex: Int,
        fromPara: Int,
        toPara: Int
    ) = lock.withLock {
        if (toPara <= fromPara) return@withLock
        val list = load(bookUrl, chapterIndex)
        if (list.isEmpty()) return@withLock
        var changed = false
        val updated = list.map { m ->
            if (!m.stale && m.paraIndex > fromPara && m.paraIndex <= toPara) {
                changed = true
                m.copy(stale = true)
            } else {
                m
            }
        }
        if (changed) {
            save(bookUrl, chapterIndex, updated)
            AppLog.put("$TAG: 标记作废 段落($fromPara, $toPara]")
        }
    }

    /** 清除章节内所有 stale 记录（章节合并完成后调用） */
    suspend fun clearStale(bookUrl: String, chapterIndex: Int) = lock.withLock {
        val list = load(bookUrl, chapterIndex)
        if (list.isEmpty()) return@withLock
        val updated = list.filterNot { it.stale }
        if (updated.size != list.size) {
            if (updated.isEmpty()) {
                deleteChapter(bookUrl, chapterIndex)
            } else {
                save(bookUrl, chapterIndex, updated)
            }
        }
    }

    /**
     * 删除指定章节的记录文件。
     * @return true 确实删掉了文件；false 表示原本就没有记录
     */
    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val f = chapterFile(bookUrl, chapterIndex)
                if (!f.exists()) return@withContext false
                val ok = f.delete()
                // 目录空了顺手清掉，避免残留空目录
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
            AppLog.put("$TAG: 删除本书全部BGM记录 -> $ok")
            ok
        } catch (e: Exception) {
            AppLog.put("$TAG: 删除本书BGM记录失败 ${e.localizedMessage}")
            false
        }
    }

    /** 清空所有书的记录 */
    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(appCtx.filesDir, DIR_NAME)
            if (!dir.exists()) return@withContext false
            val ok = dir.deleteRecursively()
            AppLog.put("$TAG: 清空全部BGM记录 -> $ok")
            ok
        } catch (e: Exception) {
            AppLog.put("$TAG: 清空BGM记录失败 ${e.localizedMessage}")
            false
        }
    }
}
