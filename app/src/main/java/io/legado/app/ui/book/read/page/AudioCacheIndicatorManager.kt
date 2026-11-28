package io.legado.app.ui.book.read.page

import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.service.TtsCacheFileName
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.StringUtils
import splitties.init.appCtx
import java.io.File

/**
 * 音频缓存标记管理器
 * 负责扫描当前章节各段落的音频缓存状态，并提供单段删除功能。
 * 文件名计算严格复用 HttpReadAloudService / TTSReadAloudService 的算法。
 * 同时支持 HttpTTS 和系统 TTS 缓存的检测、播放与删除。
 * 系统 TTS 与 HttpTTS 均使用朗读脚本前的原始文本计算 MD5，仅文件夹不同。
 */
object AudioCacheIndicatorManager {

    // paragraphNum -> 缓存文件
    private val cachedParagraphMap = mutableMapOf<Int, File>()


    private val httpTtsFolderPath: String by lazy {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val sysTtsFolderPath: String by lazy {
        // 11-28：收敛到唯一权威解析器，与写入侧/清空侧/拼接侧同源。
        // 此前自己写死 externalCacheDir/systemTTS——四处各写一份，11-23 归拢时
        // 只改了一半（漏了音频缓存），正是这类重复实现的典型翻车。
        io.legado.app.help.TtsAudioCacheDir.path()
    }

    /** 判断当前是否使用 HttpTTS */
    private fun isHttpTts(): Boolean {
        val ttsEngine = ReadAloud.ttsEngine
        return !ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)
    }

    /**
     * 对段落文本做和朗读服务 [TextChapter.getNeedReadAloud] 相同的预处理，
     * 保证后续过滤、MD5 计算与实际缓存文件名一致。
     */
    private fun prepareParagraphText(raw: String): String {
        return raw.replace(Regex("[袮꧁]"), " ")
    }

    /** 扫描当前章节各段落的缓存状态 */
    fun scanChapter(textChapter: TextChapter) {
        cachedParagraphMap.clear()
        if (textChapter.pages.isEmpty()) return

        val paragraphs = textChapter.getParagraphs(false)
        if (paragraphs.isEmpty()) return

        // 预扫描两个缓存文件夹，建立文件名 -> 文件的映射
        val httpCacheFiles = mutableSetOf<String>()
        File(httpTtsFolderPath).listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") }
            ?.forEach { httpCacheFiles.add(it.name.removeSuffix(".mp3")) }
        val sysCacheFiles = mutableSetOf<String>()
        File(sysTtsFolderPath).listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }
            ?.forEach { sysCacheFiles.add(it.name.removeSuffix(".wav")) }

        // 音频缓存文件名由朗读服务按**原始章节名**（chapter.title）计算哈希：
        // 见 HttpReadAloudService/TTSReadAloudService 的 getFileNameHelper 调用点。
        // 不能用 textChapter.title——那是「替换规则 + 去引号」后的显示标题，
        // 本书一旦配了改标题的替换规则或标题带引号，两者不同，标记会全部失效。
        val title = textChapter.chapter.title
        val speechRateHttp = AppConfig.speechRatePlay + 5

        // 朗读服务 contentList 会过滤空段落，索引只计非空段落，
        // 因此这里也过滤后按顺序匹配，避免空行导致后续索引偏移。
        val nonEmptyParagraphs = paragraphs.filter {
            prepareParagraphText(it.text).trim().isNotEmpty()
        }

        nonEmptyParagraphs.forEachIndexed { index, paragraph ->
            val rawText = prepareParagraphText(paragraph.text)
            val indexPart = index

            // 1) 尝试 HttpTTS 缓存（文件名统一走 TtsCacheFileName——11-21 起
            //    写入/扫描共用唯一算法，此前两份手写实现漂移导致标记全灰）
            val httpFileName = TtsCacheFileName.httpTts(
                title, rawText.trim(), indexPart, speechRate = speechRateHttp,
            )
            if (httpCacheFiles.contains(httpFileName)) {
                cachedParagraphMap[paragraph.num] = File("${httpTtsFolderPath}$httpFileName.mp3")
                return@forEachIndexed
            }

            // 2) 尝试系统 TTS 缓存。voice 传 null=回落设置项——与写入侧
            //    「引擎实例优先、否则设置项」在引擎 voice.name==设置项时同键。
            //    ⚠️ 11-21 修「标记全灰」的关键之一：旧扫描代码自算 MD5 时
            //    voice 只读设置项、写入侧却用引擎 voice.name，两侧键不一致。
            val sysFileName = TtsCacheFileName.sysTts(
                title = title,
                content = rawText,
                index = indexPart,
            )
            if (sysCacheFiles.contains(sysFileName)) {
                cachedParagraphMap[paragraph.num] = File("${sysTtsFolderPath}$sysFileName.wav")
            }
        }
    }

    fun hasCache(paragraphNum: Int): Boolean = cachedParagraphMap.containsKey(paragraphNum)

    /**
     * 删除指定段落的音频缓存，不用确认框，直接删除。
     * @return 是否成功删除
     */
    fun deleteCache(textChapter: TextChapter, paragraphNum: Int): Boolean {
        val file = cachedParagraphMap[paragraphNum] ?: getCacheFile(textChapter, paragraphNum)
        ?: return false
        val deleted = file.delete()
        // HttpTTS 可能还有对应的 .seginfo
        if (deleted && file.name.endsWith(".mp3")) {
            File("${file.absolutePath}.seginfo").delete()
        }
        if (deleted) {
            cachedParagraphMap.remove(paragraphNum)
        }
        return deleted
    }

    /**
     * 获取指定段落的缓存文件（如果存在）。
     * @return 缓存文件，不存在则返回 null
     */
    fun getCacheFile(textChapter: TextChapter, paragraphNum: Int): File? {
        // 优先返回扫描时记录的文件
        cachedParagraphMap[paragraphNum]?.let { return it }

        val paragraphs = textChapter.getParagraphs(false)
        val paragraph = paragraphs.find { it.num == paragraphNum } ?: return null
        val rawText = prepareParagraphText(paragraph.text)
        if (rawText.trim().isEmpty()) return null

        val nonEmptyParagraphs = paragraphs.filter { prepareParagraphText(it.text).trim().isNotEmpty() }
        val index = nonEmptyParagraphs.indexOfFirst { it.num == paragraphNum }
        if (index < 0) return null
        // 音频缓存文件名由朗读服务按**原始章节名**（chapter.title）计算哈希：
        // 见 HttpReadAloudService/TTSReadAloudService 的 getFileNameHelper 调用点。
        // 不能用 textChapter.title——那是「替换规则 + 去引号」后的显示标题，
        // 本书一旦配了改标题的替换规则或标题带引号，两者不同，标记会全部失效。
        val title = textChapter.chapter.title

        // 1) 尝试 HttpTTS（算法统一走 TtsCacheFileName，11-21）
        val httpFileName = TtsCacheFileName.httpTts(
            title, rawText.trim(), index,
            speechRate = AppConfig.speechRatePlay + 5,
        )
        File("${httpTtsFolderPath}$httpFileName.mp3").takeIf { it.exists() }?.let { return it }

        // 2) 尝试系统 TTS（voice 传 null 回落设置项，与 scanChapter 同口径）
        val sysFileName = TtsCacheFileName.sysTts(
            title = title,
            content = rawText,
            index = index,
        )
        val file = File("${sysTtsFolderPath}$sysFileName.wav")
        if (file.exists()) return file
        return null
    }

    fun clearChapterState() {
        cachedParagraphMap.clear()
    }
}
