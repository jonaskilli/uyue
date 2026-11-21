package io.legado.app.service

import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadAloud
import io.legado.app.utils.MD5Utils

/**
 * 系统 TTS 缓存文件名——**唯一权威算法**（11-21 抽取）。
 *
 * ## 为什么必须有这个 object
 *
 * 此前缓存文件名算法有两份独立实现：
 *   · 写入侧 = [TTSReadAloudService.getCacheFileForText] / getFileNameHelper（含 play 路径）
 *   · 扫描侧 = AudioCacheIndicatorManager.scanChapter / getCacheFile（阅读页缓存标记自算）
 * 两份代码早已漂移，真机 11-21 实锤「预缓存过了、阅读页标记全灰」：
 *   ① voice：写入侧 `textToSpeech?.voice?.name` 优先（引擎实际 voice.name），
 *      扫描侧只读 AppConfig.sysTtsVoiceName——引擎 voice.name ≠ 设置项时**全部 miss**；
 *   ② 章名 titlePart：写入侧按请求时的 chapterTitle（预加载笔可能属于别的章），
 *      扫描侧永远用当前章 title——串章段落写到别的 titlePart 下，标记永远扫不到。
 * 修法 = 写入与扫描调同一个函数，算法只此一份，漂移不再可能。
 *
 * ## voice 的取值口径
 *
 * 写入发生在朗读服务里，能拿到引擎实例的 voice.name；扫描发生在阅读界面，
 * 拿不到。统一口径：**能拿到引擎实例就用 voice.name，否则用设置项**——
 * 与写入侧 `textToSpeech?.voice?.name ?: AppConfig.sysTtsVoiceName ?: "default"`
 * 逐字一致。扫描侧传 voice=null 即自动落设置项；只要引擎初始化时写入的
 * voice.name 与设置项一致（正常情况），两侧就命中同一文件名。
 */
object TtsCacheFileName {

    /**
     * 系统 TTS 缓存文件名（不含扩展名，.wav 由调用方加）。
     * 与 TTSReadAloudService.getFileNameHelper 完全同源。
     *
     * @param voice 引擎 voice.name；null = 扫描侧拿不到引擎实例，回落设置项
     */
    fun sysTts(
        title: String,
        content: String,
        index: Int = -1,
        engine: String? = null,
        voice: String? = null,
        ttsSpeechRate: Int = AppConfig.ttsSpeechRate,
    ): String {
        val t = title.trim()
        val c = content.trim()
        val eng = engine ?: ReadAloud.ttsEngine ?: "default"
        val v = voice ?: AppConfig.sysTtsVoiceName ?: "default"
        val rate = (ttsSpeechRate + 5) / 10f
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$eng|$v|$rate$indexPart|$c")
        val titlePart = if (t.isNotEmpty()) MD5Utils.md5Encode16(t) + "_" else ""
        return "$titlePart$key"
    }

    /**
     * HttpTTS 缓存文件名（不含扩展名）。与 HttpReadAloudService.getFileNameHelper 同源。
     *
     * @param speechRate粗 AppConfig.speechRatePlay + 5 由调用方算好传入
     *（写入侧该字段是服务级成员，扫描侧取实时设置，同口径）
     */
    fun httpTts(
        title: String,
        content: String,
        index: Int = -1,
        speechRate: Int,
        fineRate: Int = AppConfig.speechRatePlayFine,
    ): String {
        val t = title.trim()
        val c = content.trim()
        val indexPart = if (index >= 0) "|$index" else ""
        return MD5Utils.md5Encode16(t) + "_" +
            MD5Utils.md5Encode16("$speechRate-$fineRate-$indexPart-|$c")
    }
}
