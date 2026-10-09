package io.legado.app.service

import android.media.MediaMetadataRetriever
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.BgmChapterMixer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 导出用背景音乐规划器：为「章节合并 / 视频合成」挑选并排布背景音乐。
 *
 * 两条路径：
 * 1. **播放记录优先**（[planTracks]）——朗读时已把「哪首歌从哪一段开始播」记进
 *    [BgmTimelineStore]，导出直接照这份记录铺，成品与实际听到的完全一致。
 * 2. **离线选曲兜底**（[planByContent]）——没有记录时（未朗读过 / 记录已清理），
 *    按章节文本临时选曲。
 *
 * 记录转轨道时应用「1 分钟规则」：
 * - 本次真实切歌的记录（stale=false）直接采用，忠实还原；
 * - 被跨过作废的记录（stale=true）作为**候选切换点**，
 *   只有当前这首已铺够 [AppConfig.bgmMinSegmentMs] 才切过去，否则跳过由前一首延长覆盖，
 *   避免一首歌循环太久导致音乐单调。
 */
object BgmExportPlanner {

    private const val TAG = "BgmExportPlanner"

    /** 单次喂给 AI / 关键词匹配的文本量 */
    private const val ANALYZE_CHARS = 1500

    /** 音效标记，如 (打雷音效) */
    private val effectPattern = Regex("[（(][\\u4e00-\\u9fa5]*音效[)）]")

    /**
     * 规划本章背景音乐轨道。必须在 IO 线程调用。
     *
     * 优先使用播放记录；无记录时才回退离线选曲。
     *
     * @param bookUrl 书籍 URL，用于定位记录文件
     * @param chapterIndex 章节索引
     * @param segments 每段朗读文本（含音效标记），离线选曲时用
     * @param audioFiles 每段对应音频文件（与 segments 一一对应），用于换算段落时间轴
     * @return 轨道列表；开关关闭 / 无可用 BGM / 参数不匹配时返回空
     */
    suspend fun planTracks(
        bookUrl: String,
        chapterIndex: Int,
        segments: List<String>,
        audioFiles: List<File>
    ): List<BgmChapterMixer.Track> = withContext(Dispatchers.IO) {
        if (!AppConfig.mixBgmOnMerge) return@withContext emptyList<BgmChapterMixer.Track>()
        if (segments.isEmpty() || segments.size != audioFiles.size) {
            AppLog.put("$TAG: 段落数与音频数不匹配(${segments.size}/${audioFiles.size})，跳过BGM")
            return@withContext emptyList<BgmChapterMixer.Track>()
        }

        val names = BgmManager.getAudioFileList().ifEmpty {
            BgmManager.loadBgmFiles()
            BgmManager.getAudioFileList()
        }
        if (names.isEmpty()) {
            AppLog.put("$TAG: 未设置背景音乐目录或目录内无音频，跳过BGM")
            return@withContext emptyList<BgmChapterMixer.Track>()
        }

        // 1. 累加每段时长，得到每段在章节内容时间轴上的起点
        val startMs = LongArray(audioFiles.size)
        val retriever = MediaMetadataRetriever()
        var acc = 0L
        try {
            audioFiles.forEachIndexed { i, f ->
                startMs[i] = acc
                acc += durationOf(retriever, f)
            }
        } finally {
            retriever.release()
        }
        if (acc <= 0L) {
            AppLog.put("$TAG: 章节总时长为0，跳过BGM")
            return@withContext emptyList<BgmChapterMixer.Track>()
        }

        val volume = AppConfig.bgmVolume / 100f

        // 2. 优先按播放记录铺：导出的就是朗读时真实听到的那几首
        val marks = if (bookUrl.isNotBlank()) {
            BgmTimelineStore.load(bookUrl, chapterIndex)
        } else {
            emptyList()
        }
        if (marks.isNotEmpty()) {
            val tracks = buildTracksFromMarks(marks, startMs, volume, names)
            if (tracks.isNotEmpty()) {
                AppLog.put(
                    "$TAG: 按播放记录铺BGM, 记录${marks.size}条 -> 采用${tracks.size}首, " +
                        "章节${acc}ms, 音量${(volume * 100).toInt()}%"
                )
                return@withContext tracks
            }
            AppLog.put("$TAG: 记录均不可用，回退离线选曲")
        }

        // 3. 无记录：按文本临时选曲
        planByContent(segments, startMs, acc, volume, names)
    }

    /**
     * 把播放记录转成混音轨道。
     *
     * @param startMs 每段在章节时间轴上的起点，用于把段落索引换算成毫秒
     * @param names 当前可用的 BGM 名，用于过滤掉已失效（被删/改名）的曲目
     */
    private fun buildTracksFromMarks(
        marks: List<BgmMark>,
        startMs: LongArray,
        volume: Float,
        names: List<String>
    ): List<BgmChapterMixer.Track> {
        val minSegMs = AppConfig.bgmMinSegmentMs
        val tracks = mutableListOf<BgmChapterMixer.Track>()
        var lastStartMs = 0L
        for (m in marks) {
            if (m.paraIndex < 0 || m.paraIndex >= startMs.size) continue
            // 记录里的曲目已从 BGM 目录移除时跳过，改用下一首，避免整章无音乐
            val actual = names.firstOrNull { it.equals(m.bgmName, ignoreCase = true) }
                ?: continue
            val tMs = startMs[m.paraIndex] + m.posInParaMs.coerceAtLeast(0)
            if (tracks.isEmpty()) {
                // 第一首总是采用，保证开头有音乐
                tracks.add(trackOf(actual, tMs, m, volume))
                lastStartMs = tMs
                continue
            }
            if (!m.stale) {
                // 本次真实切歌，忠实还原，不受最少时长限制
                tracks.add(trackOf(actual, tMs, m, volume))
                lastStartMs = tMs
                continue
            }
            // stale：候选切换点。当前这首铺够最少时长才切，否则跳过由前一首延长覆盖
            if (tMs - lastStartMs >= minSegMs) {
                tracks.add(trackOf(actual, tMs, m, volume))
                lastStartMs = tMs
            }
        }
        return tracks
    }

    private fun trackOf(
        name: String,
        startMs: Long,
        m: BgmMark,
        volume: Float
    ) = BgmChapterMixer.Track(
        bgmName = name,
        startMs = startMs.coerceAtLeast(0),
        songOffsetMs = m.songOffsetMs.coerceAtLeast(0),
        volume = volume
    )

    /**
     * 离线选曲：没有播放记录时，按章节文本分段调用 AI / 关键词匹配选曲。
     *
     * 规则：
     * - 第一段必放一首，保证开头有音乐；
     * - 之后每积累 [AppConfig.bgmAICharInterval] 字产生一个候选切换点；
     * - 候选点距上一首起点不足 [AppConfig.bgmMinSegmentMs] 时丢弃，避免音乐过碎；
     * - 与上一首同名则丢弃，避免连续重复；
     * - 都选不出来时随机取一首铺满整章，保证开关打开必有声音。
     */
    private suspend fun planByContent(
        segments: List<String>,
        startMs: LongArray,
        chapterMs: Long,
        volume: Float,
        names: List<String>
    ): List<BgmChapterMixer.Track> {
        val minSegMs = AppConfig.bgmMinSegmentMs
        val charInterval = AppConfig.bgmAICharInterval.coerceAtLeast(50)
        val tracks = mutableListOf<BgmChapterMixer.Track>()
        val recent = ArrayDeque<String>(4)
        var lastPick: String? = null
        var lastTrackStartMs = 0L
        var charAcc = 0

        // 注意：pickBgm 是 suspend，这里必须用普通 for 循环，
        // forEachIndexed 的 lambda 不是 suspend，无法调用挂起函数。
        for (i in segments.indices) {
            val tMs = startMs[i]
            charAcc += segments[i].length
            val isFirst = i == 0
            val enoughChars = charAcc >= charInterval
            val enoughTime = tracks.isEmpty() || (tMs - lastTrackStartMs) >= minSegMs
            if (!isFirst && !enoughChars) continue
            if (!enoughTime) continue
            charAcc = 0

            val content = collectFrom(i, segments)
            if (content.isBlank()) continue
            val picked = pickBgm(content, lastPick, recent, names) ?: continue
            if (picked == lastPick) continue

            tracks.add(
                BgmChapterMixer.Track(
                    bgmName = picked,
                    startMs = tMs,
                    songOffsetMs = 0L,
                    volume = volume,
                    fadeIn = true
                )
            )
            lastPick = picked
            lastTrackStartMs = tMs
            remember(recent, picked)
        }

        // 一个都没选出来（未配 AI / 未配关键词）时，随机一首铺满整章
        if (tracks.isEmpty()) {
            val fallback = names.random()
            AppLog.put("$TAG: 选曲无结果，回退随机整章单曲: $fallback")
            tracks.add(
                BgmChapterMixer.Track(
                    bgmName = fallback,
                    startMs = 0L,
                    songOffsetMs = 0L,
                    volume = volume
                )
            )
        }

        AppLog.put(
            "$TAG: 离线选曲完成，${tracks.size}首, 章节${chapterMs}ms, " +
                "音量${(volume * 100).toInt()}%"
        )
        return tracks
    }

    /** 从第 index 段开始收集一定量文本用于选曲（去掉音效标记） */
    private fun collectFrom(index: Int, segments: List<String>): String {
        val sb = StringBuilder()
        for (i in index until segments.size) {
            sb.append(effectPattern.replace(segments[i], ""))
            if (sb.length >= ANALYZE_CHARS) break
        }
        return sb.toString().take(ANALYZE_CHARS * 2)
    }

    /**
     * 选曲：优先 AI（开关打开且有可用 provider），否则关键词匹配，最后随机兜底。
     * @param current 当前曲目，避免选到同一首
     * @param recent 最近已用曲目，避免短期重复
     */
    private suspend fun pickBgm(
        content: String,
        current: String?,
        recent: ArrayDeque<String>,
        allNames: List<String>
    ): String? {
        if (AppConfig.bgmAIEnabled) {
            // analyzeContent 是 suspend，runCatching 的 lambda 非挂起，这里只能用 try/catch
            val ai = try {
                BgmAIService.analyzeContent(content, current)
            } catch (e: Exception) {
                AppLog.putDebug("$TAG: AI选曲异常 ${e.localizedMessage}")
                null
            }
            if (!ai.isNullOrBlank() && allNames.any { it.equals(ai, ignoreCase = true) }) {
                return ai
            }
            AppLog.putDebug("$TAG: AI未返回可用曲名，回退关键词匹配")
        }
        // 关键词匹配依赖 BGM 目录下的「背景音乐的关键词.json」
        runCatching { BgmKeywordMatcher.loadConfig(AppConfig.bgmPath) }
        val kw = runCatching { BgmKeywordMatcher.analyze(content, current) }.getOrNull()
        if (!kw.isNullOrBlank()) return kw

        // 兜底：排除当前 + 最近用过的，随机挑一首
        val candidates = allNames.filter {
            !it.equals(current, ignoreCase = true) && !recent.contains(it)
        }.ifEmpty { allNames.filter { !it.equals(current, ignoreCase = true) } }
        return candidates.randomOrNull()
    }

    private fun remember(recent: ArrayDeque<String>, name: String) {
        recent.remove(name)
        recent.addFirst(name)
        while (recent.size > 3) recent.removeLast()
    }

    private fun durationOf(retriever: MediaMetadataRetriever, file: File): Long {
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
