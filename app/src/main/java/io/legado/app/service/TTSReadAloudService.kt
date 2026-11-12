package io.legado.app.service

import android.app.PendingIntent
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.help.audio.GainAudioProcessor
import io.legado.app.constant.AppLog
import io.legado.app.model.VnParagraph
import io.legado.app.model.VnSubtitlePos
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SubTitleUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地朗读（系统TTS合成 + 转发器播放/缓存架构）
 *
 * 系统TTS仅负责把文本 synthesizeToFile 合成到本地缓存文件，
 * 播放调度、ExoPlayer 管理、字级进度、缓存清理全部复用 HttpReadAloudService 模式。
 * 音频缓存目录：externalCacheDir/systemTTS/
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener, Player.Listener {

    companion object {
        private const val TAG = "TTSReadAloudService"
        private const val PRELOAD_TRIGGER_REMAIN = 5
    }

    // ====== TTS 核心 ======
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    /** 标记主线程已经请求播放但 TTS 尚未初始化完成，onInit 回调中据此补救调用 play() */
    private var pendingPlay = false

    // ====== ExoPlayer & 播放（完全复制转发器模式）======
    // 朗读播放音量增益处理器：注入 ExoPlayer 音频管线，解码后 PCM 上做乘性增益 + tanh 软限幅
    private val gainAudioProcessor: GainAudioProcessor by lazy {
        GainAudioProcessor(AppConfig.readAloudVolumeGain)
    }

    private val exoPlayer: ExoPlayer by lazy {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return DefaultAudioSink.Builder(this@TTSReadAloudService)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainAudioProcessor))
                    .build()
            }
        }
        ExoPlayer.Builder(this, renderersFactory).build()
    }
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private var itemRetryPending = false

    // ====== 缓存目录 ======
    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        val dir = File(baseDir, "systemTTS")
        if (!dir.exists()) dir.mkdirs()
        dir.absolutePath + File.separator
    }

    // ====== 合成核心 ======
    /**
     * 合成专用服务级协程作用域（10-12 在途去重配套）。
     * 与调用方 job 解耦：某一方（翻页时的老 play 任务）被 cancel 时，
     * 已提交给引擎的那笔合成仍要跑完并落缓存，等在它上面的人也要拿到结果。
     * 生命周期与 service 同步，onDestroy 里取消。
     */
    private val synthScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            // 兜底：任何漏网的未捕获异常只记日志，不走默认 handler 崩应用
            AppLog.put("TTS合成作用域未捕获异常\n${e.localizedMessage}", e)
        }
    )
    /** 把 TTS onDone/onError 回调转成挂起函数的 CompletableDeferred */
    private val synthesisCompleters = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    /** utteranceId -> 合成目标临时文件 */
    private val synthesisTargetFiles = ConcurrentHashMap<String, File>()
    /**
     * 在途合成（10-12 修「同一段文字被合成两次」）：key = 目标缓存文件绝对路径。
     *
     * 为什么必须加：`synthesizeText` 开头只查「缓存文件是否已存在且有效」，
     * **在途那一笔查不出来**——上一笔还没写盘（合成中/等待回调），新的调用就会
     * 判定「没缓存」再提交一次，同一段文字被送进引擎两遍、各合成一次。
     * 翻页/跳段时命中率最高：`seekToParagraphByProgress` 改完 `nowSpeak` 直接 `play()`，
     * 而 `play()` 的 `synthesisTask?.cancel()` 只能撤协程，撤不掉**已提交给 TTS 引擎**的请求，
     * 于是老任务在途的那一笔与新任务重头那一笔撞成两笔（真机日志：同文本间隔 74ms 两笔，
     * 两笔都成功、都没有 `_retry` 后缀 ⇒ 不是超时重试，是两次独立提交）。
     * 预加载走自己的 utteranceId 也会撞上同一段，按「缓存文件」而非 utteranceId 做键才能一起盖住。
     */
    private val inFlightSyntheses = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    /** 主合成协程 */
    private var synthesisTask: Job? = null
    /** 跨章预加载协程 */
    private var preloadJob: Job? = null

    // ====== 字幕同步 ======
    private var subtitleSyncJob: Job? = null
    /** 音效规则计算任务。与字幕同步循环解耦，避免长段落计算阻塞字幕显示 */
    private var effectCalcJob: Job? = null
    private var currentSubtitles: List<String> = emptyList()
    private var currentSubtitleCharRanges: List<Pair<Int, Int>> = emptyList()

    // ====== 静音超时检测 ======
    private var silentPlayCheckJob: Job? = null

    // ====== 生命周期 ======

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
        BgmManager.init(this)
        // loadBgmFiles 移到 play() / playByIndex() 的协程中后台执行，
        // 避免 onCreate 主线程同步遍历文件阻塞朗读启动
        initTts()
        // ⚠️ 这里**不能**调 upSpeechRate()：它末行无条件 play()。
        // onCreate 期 TextToSpeech 尚未初始化完成，那次 play() 只会置 pendingPlay 等
        // onInit 回调补播；而 onStartCommand(play) → newReadAloud() 随后还会再 play() 一次。
        // 两次都从 nowSpeak=0 起跑，play() 开头的 synthesisTask?.cancel() 只能撤协程、
        // 撤不掉已提交给 TTS 引擎的合成请求 ⇒ 第 0 段（章首）被合成两遍。
        // 真机日志实证：同一 utteranceId:Legado0 连续两轮 onStart/onDone（无 _retry 后缀
        // ⇒ 非重试，是两次独立提交），且「TtsEngineActivator 尝试激活」连打两次
        // （该调用在 play() 内仅一处）。仅新起服务时命中（服务已在跑则 onCreate 不再执行）。
        // 语速改到 onInit 里应用（见下），播放一律交给 onStartCommand 链路。
        observeEvent<Int>(EventBus.READ_ALOUD_SEEK_PARAGRAPH) { progress ->
            seekToParagraphByProgress(progress)
        }
    }

    /** 只把当前语速应用到 TTS 引擎，不触发 [play]（须在引擎初始化完成后调用） */
    private fun applySpeechRateToEngine() {
        if (!AppConfig.ttsFlowSys) {
            val rate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(rate)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
        clearTTS()
        BgmManager.release()
        synthesisTask?.cancel()
        playIndexJob?.cancel()
        preloadJob?.cancel()
        silentPlayCheckJob?.cancel()
        // 合成专用作用域：与 service 同生命周期（10-12 在途去重配套）
        synthScope.cancel()
        stopSubtitleSync()
        // 停止朗读后在后台清理过期音频缓存：进程级协程，界面/服务销毁都不受影响；
        // 单次删除有数量与时间上限，不会和朗读 / AI 章节分析抢磁盘
        Coroutine.async { removeCacheFile() }
    }

    // ====== TTS 初始化 ======

    @Synchronized
    private fun initTts() {
        ttsInitFinish = false
        val ttsEngineStr = ReadAloud.ttsEngine
        val engine = when {
            ttsEngineStr.isNullOrBlank() -> null
            ttsEngineStr.startsWith("{") -> {
                GSON.fromJsonObject<SelectItem<String>>(ttsEngineStr).getOrNull()?.value
            }
            else -> ttsEngineStr
        }
        val finalEngine = engine?.takeIf { it.isNotBlank() } ?: AppConfig.sysTtsPackageName
        LogUtils.d(TAG, "initTts engine:$engine finalEngine:$finalEngine")
        textToSpeech = if (finalEngine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            AppConfig.sysTtsPackageName = finalEngine
            TextToSpeech(this, this, finalEngine)
        }
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        pendingPlay = false
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                if (AppConfig.sysTtsPackageName.isNullOrBlank()) {
                    val actualEngine = it.defaultEngine
                    if (!actualEngine.isNullOrBlank()) {
                        AppConfig.sysTtsPackageName = actualEngine
                        LogUtils.d(TAG, "系统TTS引擎包名已保存: $actualEngine")
                    }
                }
                val voiceName = it.voice?.name
                if (!voiceName.isNullOrBlank()) {
                    AppConfig.sysTtsVoiceName = voiceName
                    LogUtils.d(TAG, "系统TTS voice 已保存: $voiceName")
                }
                ttsInitFinish = true
                // 引擎就绪后再应用语速（原在 onCreate 经 upSpeechRate 应用，但那里的
                // play() 副作用会导致章首重复合成，故语速挪到这里，只设语速不起播）
                applySpeechRateToEngine()
                if (pendingPlay) {
                    pendingPlay = false
                    play()
                }
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    // ====== 合成回调监听 ======

    private val ttsUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(s: String?) {
            LogUtils.d(TAG, "onStart synthesis utteranceId:$s")
        }

        override fun onDone(s: String?) {
            LogUtils.d(TAG, "onDone synthesis utteranceId:$s")
            // 【诊断日志】回调到达（10-12 加，只记录不改逻辑）
            AppLog.putDebug("TTS回调 onDone id=$s")
            s ?: return
            val targetFile = synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            if (completer != null) {
                if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                    completer.complete(targetFile)
                } else {
                    completer.complete(null)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            LogUtils.d(TAG, "onError utteranceId:$utteranceId errorCode:$errorCode")
            // 【诊断日志】回调到达（10-12 加，只记录不改逻辑）
            AppLog.putDebug("TTS回调 onError id=$utteranceId code=$errorCode")
            synthesisTargetFiles.remove(utteranceId)
            val completer = synthesisCompleters.remove(utteranceId)
            completer?.complete(null)
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String?) {
            LogUtils.d(TAG, "onError utteranceId:$s")
            synthesisTargetFiles.remove(s)
            val completer = synthesisCompleters.remove(s)
            completer?.complete(null)
        }
    }

    // ====== 核心合成方法 ======

    /**
     * 合成入口（10-12 加「在途去重」）：同一段文字若已有一笔在合成，本次**不再提交第二笔**，
     * 直接等那一笔的结果——主播放、跨章预加载、翻页重发三条路都走这里，天然共用。
     *
     * 去重键 = 目标缓存文件绝对路径，与 `doSynthesizeText` 内部的缓存键完全同源，
     * 只按「段落文本 + 下标 + 章节标题」判同一段，不受 utteranceId（各调用方自己生成）影响。
     */
    private suspend fun synthesizeText(
        text: String,
        utteranceId: String,
        index: Int = -1,
        chapterTitle: String = "",
        rawText: String = text
    ): File? {
        val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
        if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
            return cacheFile
        }
        val cacheKey = cacheFile.absolutePath

        // ① 已有在途登记：本次不提交第二笔，只等那一笔的结果。
        // 用 NonCancellable 包住**等待**（只包等待，不包合成）：翻页时老任务被 cancel
        // 不该把「这一笔的结果」丢掉；而真正的合成仍归提交它的那个调用方管，可被 cancel。
        inFlightSyntheses[cacheKey]?.let { pending ->
            return withContext(NonCancellable) { pending.await() }
        }

        // ② 自己成为这一笔的持有者
        val mine = CompletableDeferred<File?>()
        val winner = inFlightSyntheses.putIfAbsent(cacheKey, mine)
        if (winner != null) {
            // 输给并发调用（与 ① 之间只隔一瞬）：改为等对方那笔
            return withContext(NonCancellable) { winner.await() }
        }
        // 【诊断日志】真正提交引擎的一笔（10-12 加，只记录不改逻辑）
        AppLog.putDebug("TTS提交引擎 index=$index id=$utteranceId text=${text.take(20)}")

        // 登记后跑的**局部协程**：用服务级 scope（不挂在任何调用方的 job 下），
        // 这样某一方被 cancel 不会掐断这一笔——否则 mine 永不 complete，等它的人会永久挂住。
        // 外层 NonCancellable 只为「把子协程启起来」：调用方在 launch 这一瞬被取消，
        // 也要保证这笔合成有归属、mine 一定被 complete。服务销毁时在 onDestroy 取消它。
        val job = withContext(NonCancellable) {
            synthScope.launch {
                // 必须**无条件** complete：doSynthesizeText 若抛异常（而非返回 null），
                // 一旦跳过 complete，持有者与所有等待方就会永久挂在 mine.await() 上；
                // 且裸 SupervisorJob 子协程的未捕获异常会走默认 handler 直接崩应用。
                val result = runCatching {
                    doSynthesizeText(text, utteranceId, index, chapterTitle, rawText)
                }.onFailure {
                    AppLog.put("TTS合成异常\n${it.localizedMessage}", it)
                }.getOrNull()
                inFlightSyntheses.remove(cacheKey)
                mine.complete(result)
            }
        }
        // 持有者（即提交这一笔的人）可被取消，发给引擎的那笔请求照旧跑完并落缓存
        return try {
            job.join()
            mine.await()
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * 通用 TTS 合成方法。
     * 先写入 .tmp 临时文件，合成成功且验证为有效音频后，再重命名为正式缓存文件。
     * 若验证失败，会自动重试最多2次。
     */
    private suspend fun doSynthesizeText(
        text: String,
        utteranceId: String,
        index: Int = -1,
        chapterTitle: String = "",
        rawText: String = text
    ): File? {
        val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
        if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
            return cacheFile
        }

        suspend fun tryOnce(attempt: Int): File? {
            val tts = textToSpeech ?: return null
            val currentUtteranceId = if (attempt == 0) utteranceId else "${utteranceId}_retry$attempt"
            val tempFile = File(ttsFolderPath, "${cacheFile.name}.${currentUtteranceId}.tmp")

            if (cacheFile.exists() && !isValidAudioFile(cacheFile)) {
                cacheFile.delete()
            }
            FileUtils.listDirsAndFiles(ttsFolderPath)?.filter {
                it.isFile && it.name.startsWith(cacheFile.name) && it.name.endsWith(".tmp")
            }?.forEach { it.delete() }

            val completer = CompletableDeferred<File?>()
            synthesisCompleters[currentUtteranceId] = completer
            synthesisTargetFiles[currentUtteranceId] = tempFile

            val params = Bundle().apply {
                putInt("stream", AudioManager.STREAM_MUSIC)
            }

            val result = tts.runCatching {
                synthesizeToFile(text, params, tempFile, currentUtteranceId)
            }.getOrElse {
                AppLog.put("tts合成提交失败(attempt=$attempt)\n${it.localizedMessage}", it, true)
                TextToSpeech.ERROR
            }
            // 【诊断日志】提交结果（10-12 加，只记录不改逻辑）：
            // 用来回答「超时那一刻引擎到底收到请求没有」——SUCCESS 表示已交给引擎，
            // 之后若既无 onDone/onError 又超时，就是引擎侧没回调；ERROR 则是提交就失败。
            AppLog.putDebug(
                "TTS提交结果 id=$currentUtteranceId attempt=$attempt result=" +
                    (if (result == TextToSpeech.SUCCESS) "SUCCESS" else "ERROR($result)") +
                    " text=${text.take(20)}"
            )

            val synthesizedFile = if (result == TextToSpeech.SUCCESS) {
                try {
                    withTimeout(AppConfig.sysTtsSynthesizeTimeout * 1000L) { completer.await() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    // 超时：引擎可能已死，清除实例以便重试时重新初始化
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    if (tempFile.exists() && isValidAudioFile(tempFile)) {
                        tempFile
                    } else {
                        clearTTS()
                        AppLog.put(
                            "TTS合成超时，引擎无响应，已清除实例待重试: $currentUtteranceId" +
                                "（提交时 result 见上一条「TTS提交结果」；到此刻仍无 onDone/onError）"
                        )
                        null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // 真正的协程取消（用户暂停/停止）
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    throw e
                } catch (_: Exception) {
                    synthesisCompleters.remove(currentUtteranceId)
                    synthesisTargetFiles.remove(currentUtteranceId)
                    null
                }
            } else {
                synthesisCompleters.remove(currentUtteranceId)
                synthesisTargetFiles.remove(currentUtteranceId)
                null
            }

            return if (synthesizedFile != null && isValidAudioFile(synthesizedFile)) {
                if (synthesizedFile.renameTo(cacheFile)) {
                    cacheFile
                } else {
                    synthesizedFile.delete()
                    null
                }
            } else {
                synthesizedFile?.delete()
                null
            }
        }

        repeat(AppConfig.ttsRetryCount) { attempt ->
            // 如果引擎实例已丢失或未就绪，在主线程重新初始化并等待完成
            if (textToSpeech == null || !ttsInitFinish) {
                withContext(Dispatchers.Main) {
                    clearTTS()
                    initTts()
                }
                var waitCount = 0
                while (!ttsInitFinish && waitCount < 100) {
                    delay(50)
                    waitCount++
                }
            }
            val result = tryOnce(attempt)
            if (result != null) return result

            // 合成失败（非最后一次重试）：清除实例，让下一次重试时强制重建
            if (attempt < AppConfig.ttsRetryCount - 1) {
                clearTTS()
                delay(500)
            }
        }
        return null
    }

    private fun getRawText(index: Int): String {
        var text = contentList.getOrNull(index) ?: return ""
        if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
            text = text.substring(paragraphStartPos)
        }
        return text
    }

    private suspend fun synthesizeSingle(index: Int): File? {
        val rawText = getRawText(index)
        val text = applyTtsScripts(rawText, paragraphIndex = index)
        if (text.matches(AppPattern.notReadAloudRegex)) {
            val fileName = getFileNameHelper(textChapter?.chapter?.title ?: "", rawText, index)
            if (!hasSpeakFile(fileName)) createSilentSound(fileName)
            return getSpeakFileAsMd5(fileName)
        }
        val utteranceId = "${AppConst.APP_TAG}$index"
        val chapterTitle = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        return synthesizeText(text, utteranceId, index, chapterTitle, rawText) ?: run {
            if (!AppConfig.ttsRetrySkipOnFail) {
                throw NoStackTraceException(
                    "TTS合成失败，超过最大重试次数${AppConfig.ttsRetryCount}次，文本：$text"
                )
            }
            val fileName = getFileNameHelper(chapterTitle, rawText, index)
            if (!hasSpeakFile(fileName)) createSilentSound(fileName)
            getSpeakFileAsMd5(fileName)
        }
    }

    // ====== 播放调度（复制/适配转发器）======

    /** 本次服务生命周期内，开始朗读时只补清一次，避免切章 / 恢复播放反复触发 */
    private var startCleanDone = false

    @Synchronized
    override fun play(affectBgm: Boolean) {
        // 兜底上次直接杀后台（没走 onDestroy）没清成的缓存；延后几秒，让朗读先启动
        if (!startCleanDone) {
            startCleanDone = true
            Coroutine.async {
                delay(3000)
                removeCacheFile()
            }
        }
        if (!ttsInitFinish) {
            pendingPlay = true
            return
        }
        pendingPlay = false
        pageChanged = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play(affectBgm)
        val sysTtsPkg = AppConfig.sysTtsPackageName
        if (!sysTtsPkg.isNullOrBlank()) {
            TtsEngineActivator.activateTtsEngine(this, sysTtsPkg)
        }
        // BGM移到onPlaybackStateChanged(STATE_READY)中触发，避免和朗读启动争抢资源

        synthesisTask?.cancel()
        playIndexJob?.cancel()
        // 取消预加载协程，不再继续提交新请求。
        // 已提交到TTS引擎的请求继续完成并写入缓存，
        // 新章节合成时检查缓存命中则直接复用。
        preloadJob?.cancel()
        playErrorNo = 0
        itemRetryPending = false

        val chapterTitle = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        val endIndex = if (singleParagraphMode) (nowSpeak + 1).coerceAtMost(contentList.size) else contentList.size
        synthesisTask = lifecycleScope.launch(Dispatchers.IO) {
            var needPreload = false
            for (i in nowSpeak until endIndex) {
                ensureActive()

                val rawText = getRawText(i)
                val speakText = applyTtsScripts(rawText, paragraphIndex = i)
                val isEndMarker = i == contentList.lastIndex
                val file = if (isEndMarker || speakText.matches(AppPattern.notReadAloudRegex)) {
                    if (isEndMarker) {
                        AppLog.putDebug("章节末尾静音占位符，使用静音文件")
                    }
                    val fileName = getFileNameHelper(chapterTitle, rawText, i)
                    if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                    getSpeakFileAsMd5(fileName)
                } else {
                    val cacheFile = getCacheFileForText(rawText, i, chapterTitle)
                    if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
                        AppLog.putDebug("TTS缓存命中: ${cacheFile.name}")
                        cacheFile
                    } else {
                        try {
                            synthesizeSingle(i) ?: run {
                                AppLog.put("TTS合成失败，使用静音文件占位: index=$i")
                                val fileName = getFileNameHelper(chapterTitle, rawText, i)
                                if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                                getSpeakFileAsMd5(fileName)
                            }
                        } catch (e: Exception) {
                            when (e) {
                                is CancellationException -> throw e
                                else -> {
                                    AppLog.put("TTS合成异常，使用静音文件占位: index=$i, ${e.message}")
                                    val fileName = getFileNameHelper(chapterTitle, rawText, i)
                                    if (!hasSpeakFile(fileName)) createSilentSound(fileName)
                                    getSpeakFileAsMd5(fileName)
                                }
                            }
                        }
                    }
                }

                if (file != null && file.exists()) {
                    withContext(Dispatchers.Main) {
                        val mediaItem = MediaItem.Builder()
                            .setMediaId("$i")
                            .setUri(Uri.fromFile(file))
                            .build()
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }

                if (!singleParagraphMode && contentList.size - i <= PRELOAD_TRIGGER_REMAIN) {
                    needPreload = true
                }
            }
            // 当前章节全部合成并加入播放列表后，再启动预加载，
            // 避免与当前章节末尾争夺同一个 TTS 实例的 FIFO 队列。
            if (needPreload) {
                preloadNextChapter()
            }
        }
    }

    // 朗读播放音量增益实时生效：由 BaseReadAloudService 事件监听触发
    override fun updateVolumeGain(gain: Float) {
        gainAudioProcessor.setGain(gain)
    }

    override fun playStop(affectBgm: Boolean) {
        exoPlayer.stop()
        synthesisTask?.cancel()
        playIndexJob?.cancel()
        preloadJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        textToSpeech?.runCatching { stop() }
        if (affectBgm) {
            BgmManager.pause()
        }
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        // 预加载需要 TTS 引擎继续工作，放弃音频焦点会导致某些引擎清空合成队列，
        // 所以暂停朗读时保留焦点，避免跨章节预加载被中断。
        super.pauseReadAloud(false)
        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        exoPlayer.pause()
        BgmManager.pause()
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
            startBgm()
        }
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                if (exoPlayer.playbackState == Player.STATE_READY) {
                    exoPlayer.play()
                    upPlayPos()
                    startSubtitleSync()
                } else {
                    play()
                }
            }
        }
    }

    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val rate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(rate)
        }
        play()
    }

    // ====== ExoPlayer 监听器（完全复制转发器）======

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_READY -> {
                if (pause) return
                exoPlayer.play()
                upPlayPos()
                startSubtitleSync()
                postEvent(EventBus.READ_ALOUD_AUDIO_CACHE_REFRESH, true)
                // 朗读开始播放后再启动BGM匹配，避免和朗读启动争抢资源
                if (AppConfig.isBgmEnabled && !BgmManager.isPlaying()) {
                    startBgm()
                }
            }
            Player.STATE_ENDED -> {
                playIndexJob?.cancel()
                silentPlayCheckJob?.cancel()
                playErrorNo = 0
                if (singleParagraphMode) {
                    singleParagraphMode = false
                    pauseReadAloud()
                    return
                }
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            else -> {}
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }
            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
            itemRetryPending = false
        }
        if (mediaItem != null) {
            val mediaIndex = mediaItem.mediaId.toIntOrNull() ?: -1
            if (mediaIndex >= 0 && mediaIndex < contentList.size) {
                // 记录旧段落索引，用于计算跳过的字数触发 AI 生图切换
                val oldIndex = nowSpeak
                syncToParagraph(mediaIndex)
                // syncToParagraph 路径也需要触发 AI 生图检查（正常播放走此路径）
                if (mediaIndex > oldIndex) {
                    var advancedLen = 0
                    for (i in oldIndex until mediaIndex) {
                        if (i < contentList.size) advancedLen += contentList[i].length + 1
                    }
                    checkAiImageTrigger(advancedLen)
                }
            } else {
                updateNextPos()
            }
            checkSilentPlay()
            val currentFileName = mediaItem.mediaId
            if (currentFileName != null && isSilentFile(currentFileName)) {
                postEvent(EventBus.READ_ALOUD_FADE_OUT, true)
            }
        }
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("TTS播放错误", error)
        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        playErrorNo++
        // 同一段出错只重建重试一次：从实际报错位置重建当前项并续播，不丢内容
        val currentItem = exoPlayer.currentMediaItem
        if (playErrorNo < 5 && !itemRetryPending && currentItem != null) {
            val errorPosition = exoPlayer.currentPosition
            val index = exoPlayer.currentMediaItemIndex
            AppLog.putDebug("TTS朗读出错，从实际报错位置(${errorPosition}ms)重建续播当前段")
            exoPlayer.removeMediaItem(index)
            exoPlayer.addMediaItem(index, currentItem)
            exoPlayer.seekTo(index, errorPosition)
            exoPlayer.play()
            itemRetryPending = true
            return
        }
        if (playErrorNo >= 5) {
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    // ====== 状态推进 ======

    private fun updateNextPos() {
        val advancedLen = contentList[nowSpeak].length + 1 - paragraphStartPos
        readAloudNumber += advancedLen
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
            // 段落推进后作废被跨过的BGM记录（保留为导出时的候选切换点）
            onBgmParagraphAdvanced()
            // 段落推进后检查是否触发AI生图切换
            checkAiImageTrigger(advancedLen)
        } else {
            nextChapter()
            return
        }
    }

    override fun nextChapter() {
        val currentBook = ReadBook.book
        val currentChapter = textChapter?.chapter
        val currentChapterIndex = ReadBook.durChapterIndex

        // 切换章节前重置AI生图状态，新章节 sceneIndex 从0开始，避免跨章 sceneIndex 错位导致缓存/保存错乱
        resetAiImageState()
        // 先切换章节，再触发朗读，避免 resumeReadAloud 时 contentList 还是旧章节内容
        ReadBook.upReadTime()
        AppLog.putDebug("${currentChapter?.title} 朗读结束跳转下一章并朗读")
        isAutoSwitchingChapter = true
        val hasNext = ReadBook.moveToNextChapter(true)

        postEvent(EventBus.READ_ALOUD_CHAPTER_CHANGED, ReadBook.curTextChapter?.title ?: "")

        if (AppConfig.autoMergeAudioOnChapterEnd && currentBook != null && currentChapter != null) {
            Coroutine.async {
                HttpTtsAudioCache.mergeChapterAudioAuto(currentBook, currentChapter, currentChapterIndex, this@TTSReadAloudService)
            }
        }

        if (hasNext) {
            // 切章时的「取消旧分析 + 按新章号重新排期」已由 ReadBook 的统一钩子完成。
            // 这里不能再取消：moveToNextChapter 内部已经同步启动新一轮分析，
            // 在此取消会把刚排出来的新任务杀掉，朗读自动换章就永远处理不了后续章节。
            // 自动切章：下一章通常已排版好，不会走 newReadAloud / contentLoadFinish，
            // 必须在恢复合成前同步重落盘 data.json，保证朗读脚本读到的是新窗口内容
            ReadBook.saveTtsTextJson(force = true)
            // 跨章接续：上一章放到一半的歌，本章接着放后半段
            recordBgmChapterStart(ReadBook.durChapterIndex)
            resumeReadAloud()
        } else {
            stopSelf()
        }
    }

    // ====== 字级进度同步（完全复制转发器）======

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    // ====== 字幕同步 ======

    private fun startSubtitleSync() {
        if (subtitleSyncJob?.isActive == true) return
        subtitleSyncJob = lifecycleScope.launch {
            while (isActive) {
                if (!pause && exoPlayer.isPlaying) {
                    if (nowSpeak != lastNowSpeak) {
                        prepareSubtitles()
                        lastNowSpeak = nowSpeak
                    }
                    updateSubtitle()
                    // 检查并触发音效
                    checkAndPlayEffects(exoPlayer.duration, exoPlayer.currentPosition)
                }
                delay(200)
            }
        }
    }

    private fun stopSubtitleSync() {
        subtitleSyncJob?.cancel()
        subtitleSyncJob = null
        effectCalcJob?.cancel()
        effectCalcJob = null
        lastNowSpeak = -1
        currentSubtitles = emptyList()
        currentSubtitleCharRanges = emptyList()
        currentParagraphTotalChars = 0
    }

    private fun prepareSubtitles() {
        if (nowSpeak >= contentList.size) return
        val raw = contentList[nowSpeak]
        var text = raw
        // 短段落要靠上一段衔接才能匹配上正则；从段落中间起播时，上文就是本段被截掉的那部分
        val prevText: String
        if (paragraphStartPos > 0 && paragraphStartPos < raw.length) {
            prevText = raw.substring(0, paragraphStartPos)
            text = raw.substring(paragraphStartPos)
        } else {
            prevText = contentList.getOrNull(nowSpeak - 1).orEmpty()
        }
        // 字幕用原始文本立即构建并交给本轮 updateSubtitle 发送。
        // 注意本函数不能挂起：调用方 while 循环里 updateSubtitle 排在它之后，
        // 一旦挂起等待音效计算，字幕就会被推迟到计算结束才显示。
        val baseText = purifySpeakText(text)
        buildSubtitles(baseText)

        // 视觉小说舞台：段落开始朗读即推送（说话人归属由 UI 侧反查章节角色缓存）
        postEvent(EventBus.VN_PARAGRAPH, VnParagraph(text, nowSpeak))

        val effectiveMode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
            AppConfig.getEffectiveSoundEffectMode(it)
        } ?: AppConfig.soundEffectMode
        if (effectiveMode != "off") {
            scheduleSoundEffects(text, baseText, nowSpeak, prevText)
        }
        // AI 音效：按字数区间调度，永远多备一个区间，
        // 保证读到某一段时它的音效早已分析完
        scheduleAiEffects()
        // AI 弹幕：同一套区间调度（按字数切区间、领先预排、到位触发）
        scheduleDanmaku()
    }

    /**
     * 异步计算音效规则，不阻塞字幕同步循环。
     * 替换结果只用于 MediaPlayer 音效播放，不影响合成文本。
     */
    private fun scheduleSoundEffects(
        text: String,
        baseText: String,
        index: Int,
        prevText: String = ""
    ) {
        effectCalcJob?.cancel()
        effectCalcJob = lifecycleScope.launch(Dispatchers.IO) {
            val (cleanText, effects) = parseSoundEffects(applySoundEffectRules(text, prevText))
            val effectText = purifySpeakText(cleanText)
            withContext(Dispatchers.Main) {
                // 计算期间段落已切换，丢弃结果，交由下一轮重新计算
                if (index != nowSpeak) return@withContext
                paragraphEffects[index] = effects
                // 规则若改写了正文（不只是插入音效标记），按替换后文本刷新一次字幕
                if (effectText != baseText) {
                    buildSubtitles(effectText)
                }
            }
        }
    }

    /** 按净化后的文本切片字幕并统计字数区间 */
    private fun buildSubtitles(purifiedText: String) {
        currentSubtitles = SubTitleUtils.splitToSubtitles(purifiedText, 15)
        var acc = 0
        currentSubtitleCharRanges = currentSubtitles.map { subtitle ->
            val chars = SubTitleUtils.countChinese(subtitle)
            val range = acc to (acc + chars)
            acc += chars
            range
        }
        currentParagraphTotalChars = acc
    }

    private fun updateSubtitle() {
        if (currentSubtitles.isEmpty()) return
        // 兜底：若 ExoPlayer 读不出时长，按字数估算总时长，避免字幕完全不更新
        val totalDuration = if (exoPlayer.duration > 0) {
            exoPlayer.duration
        } else {
            val rate = (AppConfig.ttsSpeechRate + 5).coerceAtLeast(1)
            val msPerChar = (1250f / rate).toLong()
            (currentParagraphTotalChars * msPerChar).coerceAtLeast(1)
        }
        if (totalDuration <= 0) return
        val ratio = exoPlayer.currentPosition.toFloat() / totalDuration
        val currentCharCount = (ratio * currentParagraphTotalChars).toInt()

        var currentSubtitle = currentSubtitles.lastOrNull() ?: ""
        var currentRange = currentSubtitleCharRanges.lastOrNull() ?: (0 to 0)
        for (i in currentSubtitleCharRanges.indices) {
            val (startChars, endChars) = currentSubtitleCharRanges[i]
            if (currentCharCount in startChars until endChars) {
                currentSubtitle = currentSubtitles[i]
                currentRange = startChars to endChars
                break
            }
        }
        postEvent(EventBus.READ_ALOUD_SUBTITLE, currentSubtitle)
        // 视觉小说舞台：片段位置（段内字符区间），驱动对话文字跟随朗读进度
        postEvent(
            EventBus.VN_SUBTITLE_POS,
            VnSubtitlePos(currentSubtitle, currentRange.first, currentRange.second)
        )

        val textChapter = textChapter ?: return
        val totalChapterChars = textChapter.getContent().length.coerceAtLeast(1)
        val paragraphReadChars = (ratio * currentParagraphTotalChars).toInt()
        val currentTotalRead = readAloudNumber + paragraphReadChars
        val progress = (currentTotalRead.toFloat() / totalChapterChars * 1000)
            .toInt()
            .coerceIn(0, 1000)
        postEvent(EventBus.READ_ALOUD_CHAPTER_PROGRESS, progress)
    }

    // ====== 静音超时检测 ======

    private fun checkSilentPlay() {
        silentPlayCheckJob?.cancel()
        val currentFileName = exoPlayer.currentMediaItem?.mediaId
        if (currentFileName != null && isSilentFile(currentFileName)) {
            silentPlayCheckJob = lifecycleScope.launch {
                delay(200L)
                try {
                    if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.playWhenReady
                        && exoPlayer.currentMediaItem?.mediaId == currentFileName
                        && isSilentFile(currentFileName)
                    ) {
                        AppLog.putDebug("静音播放超时，强制跳过: $currentFileName")
                        playErrorNo = 0
                        if (exoPlayer.hasNextMediaItem()) {
                            exoPlayer.seekToNextMediaItem()
                        } else {
                            updateNextPos()
                            exoPlayer.stop()
                            exoPlayer.clearMediaItems()
                        }
                    }
                } catch (_: Exception) {}
            }
        } else {
            silentPlayCheckJob?.cancel()
        }
    }

    // ====== 跨章节预加载 ======

    private fun preloadNextChapter() {
        if (preloadJob?.isActive == true) return
        preloadJob = lifecycleScope.launch(Dispatchers.IO) {
            val book = ReadBook.book ?: return@launch
            val currentIdx = ReadBook.durChapterIndex
            val limitChapter = AppConfig.audioPreDownloadNum

            for (offset in 1..limitChapter) {
                ensureActive()
                val nextIdx = currentIdx + offset
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, nextIdx)
                    ?: continue

                // 优先使用 ReadBook 已排版的 TextChapter 生成段列表，和主播放来源一致
                val textChapter = ReadBook.textChapter(offset)
                val segments: List<String>
                val segmentTitle: String
                if (textChapter != null && textChapter.pages.isNotEmpty()) {
                    segments = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
                        .split("\n")
                        .filter { it.isNotEmpty() }
                    segmentTitle = textChapter.title
                } else {
                    val rawContent = BookHelp.getContent(book, chapter) ?: continue
                    val contentProcessor = ContentProcessor.get(book)
                    // includeTitle=true 与 TextChapter.getNeedReadAloud() 输出保持一致：
                    // TextChapterLayout 排版时会把标题也写入 TextPage.text，
                    // 所以 getNeedReadAloud().split("\n") 的第一个元素就是标题。
                    val bookContent = contentProcessor.getContent(
                        book = book,
                        chapter = chapter,
                        content = rawContent,
                        includeTitle = true,
                        useReplace = AppConfig.replaceEnableDefault && book.getUseReplaceRule(),
                        chineseConvert = AppConfig.chineseConverterType != 0,
                        fixQuote = book.getFixQuote(),
                        reSegment = book.getReSegment()
                    )
                    // 同步 TextChapter.getNeedReadAloud() 中对 [袮꧁] 的替换，确保缓存命中
                    segments = bookContent.toString()
                        .replace(Regex("[袮꧁]"), " ")
                        .split("\n")
                        .filter { it.isNotEmpty() }
                    segmentTitle = chapter.title
                }

                if (segments.isEmpty()) continue

                for (i in segments.indices) {
                    ensureActive()
                    val rawText = segments[i]
                    val text = applyTtsScripts(
                        rawText,
                        paragraphIndex = i,
                        chapterTitle = segmentTitle,
                        chapterParagraphs = segments
                    )
                    if (text.matches(AppPattern.notReadAloudRegex)) continue

                    val cacheFile = getCacheFileForText(rawText, i, segmentTitle)
                    if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) continue

                    val utteranceId = "PRELOAD_${AppConst.APP_TAG}_${System.currentTimeMillis()}_${offset}_$i"
                    synthesizeText(text, utteranceId, i, segmentTitle, rawText)
                }
            }
        }
    }

    // ====== 缓存管理 ======

    /**
     * 开始 / 停止朗读时在后台清理过期音频缓存。
     *
     * 两条约束叠加：
     *  1) 保留水位：音频文件不超过 AUDIO_CACHE_KEEP_FLOOR 个就完全不清，
     *     超出也只删多出来的那部分，始终留够水位；
     *  2) 单次限额：一次最多删 AUDIO_CACHE_MAX_PER_RUN 个、最多跑 60 秒，
     *     每 50 个歇 1 秒，避免历史缓存堆积时磁盘 IO 峰值拖慢朗读和 AI 分析。
     * 候选按文件修改时间升序（最旧先删），仍只删超过保留时间、
     * 且不在当前章 / 上一章 / 后续预下载章保护前缀里的文件。
     * 系统 TTS 的静音占位文件沿用原有规则：无论是否过期都删。
     */
    private suspend fun removeCacheFile() {
        val startedAt = System.currentTimeMillis()
        val keepTime = AppConfig.audioCacheCleanTime
        val entries = FileUtils.listDirsAndFiles(ttsFolderPath) ?: return
        // 水位只按真正的音频文件算：.tmp / 子目录都不计入
        val audioFiles = entries.filter {
            it.isFile && (it.name.endsWith(".mp3") || it.name.endsWith(".wav"))
        }
        val total = audioFiles.size
        if (total <= BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR) {
            AppLog.put(
                "音频缓存清理: 当前 $total 个，未超过保留水位 " +
                        "${BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR}，跳过"
            )
            return
        }
        val excess = total - BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR
        val quota = minOf(excess, BaseReadAloudService.AUDIO_CACHE_MAX_PER_RUN)
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        val limit = AppConfig.audioPreDownloadNum
        val protectedPrefixes = mutableSetOf<String>()
        val currentTitle = this.textChapter?.chapter?.title ?: ""
        if (currentTitle.isNotEmpty()) {
            protectedPrefixes.add(MD5Utils.md5Encode16(currentTitle.trim()))
        }
        // 开始朗读时 textChapter 可能还没就绪，再按章号补一次当前章
        appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx)?.let {
            protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
        }
        // 保护上一章，避免回退时被清理
        appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx - 1)?.let {
            protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
        }
        for (i in 1..limit) {
            appDb.bookChapterDao.getChapter(book.bookUrl, currentIdx + i)?.let {
                protectedPrefixes.add(MD5Utils.md5Encode16(it.title.trim()))
            }
        }
        var deleted = 0
        var failed = 0
        var stoppedByLimit = false
        // 最旧优先：只删多出来的那部分时，必须保证先淘汰最早的缓存
        val candidates = audioFiles.sortedBy { it.lastModified() }
        for (index in candidates.indices) {
            if (deleted >= quota ||
                System.currentTimeMillis() - startedAt >= BaseReadAloudService.AUDIO_CACHE_MAX_DURATION_MS
            ) {
                stoppedByLimit = true
                break
            }
            val fileItem = candidates[index]
            val fName = fileItem.name
            // 文件名固定以 16 位章标题 MD5 开头，直接查集合即可
            val isProtected = fName.take(16) in protectedPrefixes
            val isSilentSound = fileItem.length() == BaseReadAloudService.SILENT_SOUND_SIZE
            val shouldDelete = isSilentSound ||
                    (!isProtected && System.currentTimeMillis() - fileItem.lastModified() > keepTime)
            if (!shouldDelete) continue
            val ok = runCatching { FileUtils.delete(fileItem.absolutePath) }.getOrDefault(false)
            if (!ok) {
                failed++
                continue
            }
            deleted++
            // 每批歇 1 秒；最后一项和已达配额时不再白等
            if (deleted % BaseReadAloudService.AUDIO_CACHE_BATCH_SIZE == 0 &&
                deleted < quota && index < candidates.lastIndex
            ) {
                delay(BaseReadAloudService.AUDIO_CACHE_BATCH_DELAY_MS)
            }
        }
        val tail = if (stoppedByLimit) "，已达单次限额或时间预算，剩余下次继续" else "，本轮扫描完毕"
        AppLog.put(
            "音频缓存清理: 共 $total 个、超出水位 $excess 个，" +
                    "本次删除 $deleted 个、失败 $failed 个，" +
                    "耗时 ${System.currentTimeMillis() - startedAt}ms$tail"
        )
    }

    private fun deleteCurrentSpeakFile() {
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    // ====== 工具方法 ======

    private fun getSpeakText(index: Int): String {
        val rawText = getRawText(index)
        // 快速路径：后续段落的缓存已准备好时，跳过脚本执行，避免阻塞播放流程
        if (index > nowSpeak) {
            val chapterTitle = textChapter?.chapter?.title ?: ""
            val cacheFile = getCacheFileForText(rawText, index, chapterTitle)
            if (cacheFile.exists() && cacheFile.length() > 0 && isValidAudioFile(cacheFile)) {
                return rawText
            }
        }
        return applyTtsScripts(rawText, paragraphIndex = index)
    }

    private fun getFileNameHelper(title: String, content: String, index: Int = -1): String {
        val t = title.trim()
        val c = content.trim()
        val engine = ReadAloud.ttsEngine ?: "default"
        val voice = textToSpeech?.voice?.name ?: AppConfig.sysTtsVoiceName ?: "default"
        val rate = (AppConfig.ttsSpeechRate + 5) / 10f
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$engine|$voice|$rate$indexPart|$c")
        val titlePart = if (t.isNotEmpty()) MD5Utils.md5Encode16(t) + "_" else ""
        return "$titlePart$key"
    }

    /**
     * gengxin 触发处理：删除所有系统TTS缓存（.wav）。
     */
    /**
     * 删除指定段之后的系统TTS缓存（.wav）。
     * @param fromIndex 从此 index 之后（含 fromIndex+1）开始删除；-1 删除全部。
     */
    override fun deleteGengxinCachesAfter(fromIndex: Int) {
        if (fromIndex < 0) {
            // 未朗读：删除全部
            var deleted = 0
            FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
                if (fileItem.isFile) {
                    FileUtils.delete(fileItem.absolutePath)
                    deleted++
                }
            }
            AppLog.put("gengxin: 已删除所有 系统TTS 缓存，共 $deleted 个文件")
            return
        }
        // 正在朗读：以当前朗读音频的生成时间为基准，删除所有生成时间晚于它的缓存（跨章节）
        val title = textChapter?.chapter?.title ?: textChapter?.title ?: return
        if (fromIndex > contentList.lastIndex) {
            AppLog.put("gengxin: fromIndex 越界 (fromIndex=$fromIndex)")
            return
        }
        val currentFn = getFileNameHelper(title, contentList[fromIndex], fromIndex)
        val currentWav = File("${ttsFolderPath}$currentFn.wav")
        val baseTime = if (currentWav.exists()) currentWav.lastModified() else 0L
        if (baseTime == 0L) {
            // 兜底：取不到基准时间时，退回按 index 删本章后续
            val deleteFrom = fromIndex + 1
            if (deleteFrom > contentList.lastIndex) {
                AppLog.put("gengxin: 无后续段落需删除 (fromIndex=$fromIndex)")
                return
            }
            var deleted = 0
            for (i in deleteFrom..contentList.lastIndex) {
                val fn = getFileNameHelper(title, contentList[i], i)
                val wav = File("${ttsFolderPath}$fn.wav")
                if (wav.exists()) { wav.delete(); deleted++ }
            }
            AppLog.put("gengxin: 基准时间缺失，已删除本章段落 $deleteFrom..${contentList.lastIndex} 的 系统TTS 缓存，共 $deleted 个文件")
            return
        }
        // 按生成时间删除：遍历整个缓存目录，删除所有 lastModified() 晚于基准时间的文件
        // 当前正在朗读的音频通过文件名排除，确保不被误删
        var deleted = 0
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach { fileItem ->
            if (!fileItem.isFile) return@forEach
            if (fileItem.name == "$currentFn.wav") return@forEach
            if (fileItem.lastModified() > baseTime) {
                FileUtils.delete(fileItem.absolutePath)
                deleted++
            }
        }
        AppLog.put("gengxin: 已按时间删除 baseTime=$baseTime 之后的 系统TTS 缓存，共 $deleted 个文件")
    }

    private fun getCacheFileForText(text: String, index: Int = -1, chapterTitle: String = ""): File {
        val engine = ReadAloud.ttsEngine ?: "default"
        val rate = (AppConfig.ttsSpeechRate + 5) / 10f
        val voice = textToSpeech?.voice?.name ?: AppConfig.sysTtsVoiceName ?: "default"
        val indexPart = if (index >= 0) "|$index" else ""
        val key = MD5Utils.md5Encode16("$engine|$voice|$rate$indexPart|$text")
        val titlePart = if (chapterTitle.isNotEmpty()) MD5Utils.md5Encode16(chapterTitle.trim()) + "_" else ""
        return File(ttsFolderPath, "$titlePart$key.wav")
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.wav")
    }

    private fun isSilentFile(fileName: String): Boolean {
        val file = File("${ttsFolderPath}$fileName.wav")
        if (!file.exists()) return false
        return file.length() == BaseReadAloudService.SILENT_SOUND_SIZE.toLong()
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.wav")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.wav")
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(BaseReadAloudService.generateSilentWavBytes(50))
    }

    private fun purifySpeakText(text: String): String {
        return text
            .replace(Regex("<img[^>]*>"), "")
            .replace(Regex("<[a-zA-Z][^>]*>|</[a-zA-Z][^>]*>"), "")
            .replace(Regex("&[a-zA-Z#0-9]+;"), "")
            .replace(Regex("[袮꧁]"), "")
    }

    // ====== 音频验证 ======

    private fun isValidAudioFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 44) return false
        return try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull()
            retriever.release()
            // 宽松校验：只要 MediaMetadataRetriever 不抛异常即视为有效音频。
            // 很多 TTS 引擎合成的音频 ExoPlayer 能正常播放，但 Retriever 读不出时长，
            // 或不是标准 WAV 头，之前这种音频会被误判为无效而重新合成。
            true
        } catch (e: Exception) {
            // Retriever 抛异常也不直接判无效，只要文件大小合理就放行
            true
        }
    }

    private fun parseWavDurationMs(file: File): Long? {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(44)
                if (stream.read(header) < 44) return null
                if (!(header[0] == 'R'.toByte() && header[1] == 'I'.toByte() &&
                            header[2] == 'F'.toByte() && header[3] == 'F'.toByte() &&
                            header[8] == 'W'.toByte() && header[9] == 'A'.toByte() &&
                            header[10] == 'V'.toByte() && header[11] == 'E'.toByte())) {
                    return null
                }
                val sampleRate = java.nio.ByteBuffer.wrap(header, 24, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                val channels = java.nio.ByteBuffer.wrap(header, 22, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                val bitsPerSample = java.nio.ByteBuffer.wrap(header, 34, 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
                if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return null
                val byteRate = sampleRate * channels * bitsPerSample / 8
                if (byteRate <= 0) return null
                val dataSize = file.length() - 44
                (dataSize * 1000L / byteRate)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ====== 段落跳转 ======

    private fun seekToParagraphByProgress(progress: Int) {
        val tc = textChapter ?: return
        if (contentList.isEmpty()) return
        val totalChars = tc.getContent().length.coerceAtLeast(1)
        val targetPos = (totalChars * progress / 1000f).toInt()

        var acc = 0
        var targetParagraph = 0
        for (i in contentList.indices) {
            val len = contentList[i].length + 1
            if (acc + len > targetPos) {
                targetParagraph = i
                break
            }
            acc += len
        }

        playIndexJob?.cancel()
        silentPlayCheckJob?.cancel()
        stopSubtitleSync()
        playErrorNo = 0

        nowSpeak = targetParagraph
        paragraphStartPos = 0

        readAloudNumber = 0
        for (i in 0 until targetParagraph) {
            readAloudNumber += contentList[i].length + 1
        }

        pageIndex = tc.getPageIndexByCharIndex(readAloudNumber)

        exoPlayer.stop()
        play()
    }

    // ====== 段落时长估算 ======

    override fun estimateParagraphDuration(index: Int): Long {
        if (index < 0 || index >= contentList.size) return 0L
        var text = contentList[index]
        if (text.isEmpty()) return 0L

        if (paragraphStartPos > 0 && paragraphStartPos < text.length && index == nowSpeak) {
            text = text.substring(paragraphStartPos)
        }
        if (text.isEmpty()) return 0L

        val fileName = getFileNameHelper(textChapter?.chapter?.title ?: "", text, index)
        val file = getSpeakFileAsMd5(fileName)
        if (file.exists() && file.length() > 0) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull()
                if (duration != null && duration > 0) return duration
            } catch (_: Exception) {
            } finally {
                retriever.release()
            }
        }

        val rate = AppConfig.speechRatePlay + 5
        val msPerChar = (1250f / rate).toLong()
        return text.length * msPerChar
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }
}
