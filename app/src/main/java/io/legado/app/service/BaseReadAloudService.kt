@file:Suppress("DEPRECATION")

package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.constant.PageAnim
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Status
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.utils.StringUtils
import io.legado.app.help.MediaHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.http.CookieStore
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.AiImagePersistentCache
import io.legado.app.model.DanmakuReady
import io.legado.app.model.DanmakuStore
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.help.audio.GainAudioProcessor
import io.legado.app.data.appDb
import io.legado.app.utils.LogUtils
import io.legado.app.utils.activityPendingIntent
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.observeEvent
import io.legado.app.utils.observeSharedPreferences
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import splitties.systemservices.audioManager
import splitties.systemservices.notificationManager
import splitties.systemservices.powerManager
import splitties.systemservices.telephonyManager
import splitties.systemservices.wifiManager

/**
 * 朗读服务
 */
abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        /** 每章弹幕条数（固定值，不再提供设置）：配合按字数占比分摊，12 在中长章节里密度适中 */
        const val DANMAKU_COUNT_PER_CHAPTER = 12

        /** 音效文件（JSON）存放目录，音效规则管理界面也往这里写入 */
        const val SOUND_EFFECT_DIR = "/storage/emulated/0/Download/chajian/bendiyinxiao2"

        @JvmStatic
        var isRun = false
            private set

        /** 朗读运行状态流，供书架等界面观察以实时刷新耳机/停止按钮状态 */
        @JvmStatic
        val isRunFlow = MutableStateFlow(false)

        @JvmStatic
        var pause = true
            private set

        /** 当前运行中的朗读服务实例，用于从外部（如半屏朗读 Dialog）调用实例方法 */
        @JvmStatic
        var instance: BaseReadAloudService? = null

        /**
         * 朗读运行中，阅读页被手动翻页后调用：将朗读进度对齐到当前阅读页起点并继续朗读，
         * 不重读已读过的内容，从翻到的新页开始继续播报。
         */
        @JvmStatic
        fun syncToCurrentPage() {
            if (!isRun) return
            instance?.newReadAloud(true, ReadBook.durPageIndex, 0)
        }

        @JvmStatic
        var timeMinute: Int = 0
            private set

        fun isPlay(): Boolean {
            return isRun && !pause
        }

        /**
         * gengxin 触发重启后要恢复到的段落索引。
         * 服务重启后 newReadAloud 会重置 nowSpeak，在 play() 前用此值覆盖。
         * -1 表示无待恢复。
         */
        @JvmStatic
        var pendingGengxinResumeIndex: Int = -1

        private const val TAG = "BaseReadAloudService"

        /** 0.05 秒静音 WAV 文件大小（8000Hz 单声道 16bit） */
        const val SILENT_SOUND_SIZE = 844L

        /**
         * 生成指定时长的静音 WAV 文件字节数组
         * 默认 50ms，8000Hz 单声道 16bit PCM
         */
        fun generateSilentWavBytes(durationMs: Int = 50): ByteArray {
            val sampleRate = 8000
            val numChannels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val numSamples = sampleRate * durationMs / 1000
            val dataSize = numSamples * blockAlign
            val totalSize = 36 + dataSize

            val buffer = java.nio.ByteBuffer.allocate(44 + dataSize)
            buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)

            // RIFF chunk descriptor
            buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            buffer.putInt(totalSize)
            buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

            // fmt sub-chunk
            buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            buffer.putInt(16)           // Subchunk1Size
            buffer.putShort(1)          // AudioFormat = PCM
            buffer.putShort(numChannels.toShort())
            buffer.putInt(sampleRate)
            buffer.putInt(byteRate)
            buffer.putShort(blockAlign.toShort())
            buffer.putShort(bitsPerSample.toShort())

            // data sub-chunk
            buffer.put("data".toByteArray(Charsets.US_ASCII))
            buffer.putInt(dataSize)

            // PCM silence data (all zeros)
            repeat(dataSize) { buffer.put(0) }

            return buffer.array()
        }

        /**
         * 音效标记正则：匹配 (打雷音效) 等形式
         */
        private val soundEffectPattern = Regex("\\(([\\u4e00-\\u9fa5]*音效)\\)")

        /**
         * 少于这个字数的段落，正则容易匹配不上（很多规则要看完整的一句话），
         * 此时用 [prevText] 衔接上来再匹配。
         */
        private const val MIN_MATCH_CHARS = 50

        /** 补上下文时，上一段最多取末尾这么多字 */
        private const val CONTEXT_CHARS = 50

        /**
         * 上一段与当前段的衔接符。
         * 规则文件（ttsrv-replaces*.json）里有对应规则，会把衔接符连同前面的内容一并删掉，
         * 所以替换完成后剩下的就是当前段落加音效标记；这里再兜底截一次，防止规则没生效。
         */
        private const val PARA_JOINER = "〉〉"

        /**
         * 应用内置音效替换规则，在文本中插入 (音效) 标记。
         * 若文本中已存在音效标记，则跳过，避免重复。
         * 根据当前书籍生效的音效模式选择不同的规则文件。
         *
         * @param text 当前段落
         * @param prevText 上一段落。当前段落不足 [MIN_MATCH_CHARS] 字时，
         *                 取它的末尾 [CONTEXT_CHARS] 字用 [PARA_JOINER] 衔接到前面一起匹配，
         *                 让跨句的规则也能命中；替换完成后该部分会被去掉，不影响本段正文。
         */
        @JvmStatic
        protected fun applySoundEffectRules(text: String, prevText: String = ""): String {
            if (text.isEmpty()) return text
            val mode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
                AppConfig.getEffectiveSoundEffectMode(it)
            } ?: AppConfig.soundEffectMode
            if (mode == "off") return text

            val jsonFileName = if (mode == "normal") "ttsrv-replaces5.json" else "ttsrv-replaces3.json"
            val file = java.io.File(io.legado.app.help.EngineDataDir.baseDir() + jsonFileName)
            val downloadUrl = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/$jsonFileName?download=true"
            val rules = getSoundEffectRules(file, downloadUrl) ?: return text

            // 短段落补上文，原文两段之间是换行，这里换成衔接符，便于规则识别与删除
            val withContext = prevText.isNotBlank() && text.length < MIN_MATCH_CHARS
            val source = if (withContext) {
                val head =
                    if (prevText.length > CONTEXT_CHARS) prevText.takeLast(CONTEXT_CHARS) else prevText
                head + PARA_JOINER + text
            } else {
                text
            }

            return try {
                val hasEffects = soundEffectPattern.containsMatchIn(source)
                var result = source
                for (rule in rules) {
                    if (rule.isRegex && rule.regex == null) {
                        // 正则编译失败：保持原有行为，跳过该条
                        continue
                    }
                    try {
                        result = if (rule.regex != null) {
                            rule.regex.replace(result, rule.replacement)
                        } else {
                            result.replace(rule.pattern, rule.replacement)
                        }
                    } catch (e: Exception) {
                        AppLog.put("[ttsrv] 规则失败 [order=${rule.order}]: ${e.message} pattern=${rule.pattern}")
                    }
                }
                // 只有"替换后确实配出了音效"才值得记一条：
                // 规则跑完但没配上音效是常态，每段落打一条汇总会把有用的日志全淹掉
                val nowHasEffects = soundEffectPattern.containsMatchIn(result)
                if (!hasEffects && nowHasEffects) {
                    AppLog.put("[ttsrv] 替换后生成了音效标记")
                }
                // 衔接进来的上文只是给正则看的，不属于本段：
                // 规则文件里有对应规则删掉它，这里再兜底截一次，避免规则没命中时混进正文
                if (withContext) result.substringAfterLast(PARA_JOINER) else result
            } catch (e: Exception) {
                AppLog.put("[ttsrv] 整体异常: ${e.message}")
                text
            }
        }

        /**
         * 外部（音效规则管理界面）改写规则文件后调用，强制丢弃已编译的规则缓存，
         * 使下一次朗读重新读取并编译规则文件。
         *
         * 仅依赖文件的修改时间与大小判定缓存并不可靠：改动可能恰好不改变大小，
         * 或文件系统时间戳精度不足，因此由改写方显式通知。
         */
        @JvmStatic
        fun invalidateSoundEffectRuleCache() {
            synchronized(ruleCacheLock) {
                ruleCache = null
            }
            AppLog.put("[ttsrv] 规则缓存已失效，下次朗读将重新加载")
        }

        /**
         * 已完成编译的音效规则。
         * @param regex 正则规则编译后的 Regex；字面规则或编译失败时为 null
         * @param isRegex true 表示这是一条正则规则（区别于字面替换）
         * @param order 规则序号，用于日志定位
         */
        private data class CompiledRule(
            val pattern: String,
            val replacement: String,
            val regex: Regex?,
            val isRegex: Boolean,
            val order: Int
        )

        /**
         * 规则集缓存。
         * 判定是否失效的三项：文件完整路径、最后修改时间、文件大小。
         * 切换音效模式（3/5 文件不同）、切换朗读引擎（mingwuyan/mingwuyanZ 目录不同）、
         * 手动修改或更新规则文件（修改时间与大小变化）都会命中其中一项，从而自动重新加载。
         */
        private data class RuleCache(
            val path: String,
            val lastModified: Long,
            val size: Long,
            val rules: List<CompiledRule>
        )

        private val ruleCacheLock = Any()
        private var ruleCache: RuleCache? = null

        /**
         * 获取当前生效的音效规则集（已按 order 排序、已完成正则编译）。
         * 命中缓存时只比对文件元信息，不读取也不解析 JSON。
         * @return 规则列表；加载或下载失败返回 null
         */
        private fun getSoundEffectRules(
            file: java.io.File,
            downloadUrl: String
        ): List<CompiledRule>? {
            val path = file.absolutePath
            // 1. 命中缓存：只需文件元信息，避免每次都读取并解析整个 JSON
            if (file.exists()) {
                val lastModified = file.lastModified()
                val size = file.length()
                synchronized(ruleCacheLock) {
                    val cached = ruleCache
                    if (cached != null && cached.path == path
                        && cached.lastModified == lastModified && cached.size == size
                    ) {
                        return cached.rules
                    }
                }
            }
            // 2. 缓存不可用：读取本地 JSON，缺失或无效则重新下载
            val raw = readOrDownloadSoundEffectRules(file, downloadUrl) ?: return null
            // 3. 解析 + 编译正则（只在缓存失效时执行一次）
            val rules = try {
                compileSoundEffectRules(raw)
            } catch (e: Exception) {
                AppLog.put("[ttsrv] 规则解析失败: ${e.message}")
                return null
            }
            val lastModified = if (file.exists()) file.lastModified() else 0L
            val size = if (file.exists()) file.length() else raw.length.toLong()
            synchronized(ruleCacheLock) {
                ruleCache = RuleCache(path, lastModified, size, rules)
            }
            AppLog.put("[ttsrv] 规则已编译缓存: ${rules.size}条, 文件=${file.name}")
            return rules
        }

        /**
         * 读取本地规则 JSON；不存在或内容无效时删除并重新下载保存。
         * @return 原始 JSON 文本，失败返回 null
         */
        private fun readOrDownloadSoundEffectRules(
            file: java.io.File,
            downloadUrl: String
        ): String? {
            var raw: String? = null
            // 1. 优先读取本地 JSON
            if (file.exists()) {
                try {
                    raw = file.readText()
                    if (raw.isNullOrEmpty() || raw == "null" || raw == "undefined"
                        || !raw.trimStart().startsWith("[")
                    ) {
                        raw = null
                        file.delete()
                        AppLog.put("[ttsrv] 本地文件内容无效，已删除，将重新下载")
                    }
                } catch (e: Exception) {
                    AppLog.put("[ttsrv] 本地文件读取失败: ${e.message}")
                    raw = null
                }
            }
            // 2. 本地没有或无效，下载
            if (raw == null) {
                try {
                    val response = io.legado.app.help.http.okHttpClient.newCall(
                        okhttp3.Request.Builder().url(downloadUrl).build()
                    ).execute()
                    raw = response.body?.string()
                    if (!raw.isNullOrEmpty() && raw != "null" && raw != "undefined"
                        && raw.trimStart().startsWith("[")
                    ) {
                        file.parentFile?.mkdirs()
                        file.writeText(raw)
                        AppLog.put("[ttsrv] 下载成功并保存")
                    } else {
                        AppLog.put("[ttsrv] 下载返回无效内容: ${raw?.take(100)}")
                        return null
                    }
                } catch (e: Exception) {
                    AppLog.put("[ttsrv] 下载失败: ${e.message}")
                    return null
                }
            }
            return raw
        }

        /**
         * 把规则 JSON 解析为有序、已编译的规则列表。
         * 保持原有执行顺序：按 JSON 中的分组顺序，组内按 order 升序。
         */
        private fun compileSoundEffectRules(raw: String): List<CompiledRule> {
            val data = org.json.JSONArray(raw)
            val rules = mutableListOf<CompiledRule>()
            for (i in 0 until data.length()) {
                val groupObj = data.getJSONObject(i)
                val list = groupObj.optJSONArray("list") ?: continue
                val groupRules = mutableListOf<CompiledRule>()
                for (j in 0 until list.length()) {
                    val item = list.getJSONObject(j)
                    if (item.optBoolean("isEnabled", true).not()) continue
                    val pattern = item.optString("pattern", "") ?: continue
                    if (pattern.isEmpty()) continue
                    val order = item.optInt("order", 0)
                    val isRegex = item.optBoolean("isRegex", false)
                    val regex = if (isRegex) {
                        try {
                            val (cleanPattern, flags) = parsePatternAndFlags(pattern)
                            val effectivePattern = convertEs6RegexToJava(cleanPattern)
                            if (flags.isNotEmpty()) {
                                val flagSet = mutableSetOf<RegexOption>()
                                if (flags.contains('i')) flagSet.add(RegexOption.IGNORE_CASE)
                                if (flags.contains('m')) flagSet.add(RegexOption.MULTILINE)
                                if (flags.contains('s')) flagSet.add(RegexOption.DOT_MATCHES_ALL)
                                Regex(effectivePattern, flagSet)
                            } else {
                                Regex(effectivePattern)
                            }
                        } catch (e: Exception) {
                            // 编译失败留作 null，执行阶段按原有逻辑跳过该条
                            AppLog.put("[ttsrv] 正则编译失败 [order=$order]: ${e.message} pattern=$pattern")
                            null
                        }
                    } else {
                        null
                    }
                    groupRules.add(
                        CompiledRule(
                            pattern = pattern,
                            replacement = item.optString("replacement", ""),
                            regex = regex,
                            isRegex = isRegex,
                            order = order
                        )
                    )
                }
                groupRules.sortBy { it.order }
                rules.addAll(groupRules)
            }
            return rules
        }

        private fun parsePatternAndFlags(pattern: String): Pair<String, String> {
            if (pattern.length >= 2 && pattern.startsWith("/") && !pattern.substring(1).contains("/")) {
                return pattern to ""
            }
            if (pattern.length >= 2 && pattern.startsWith("/")) {
                val lastSlash = pattern.lastIndexOf("/")
                if (lastSlash > 0) {
                    val regex = pattern.substring(1, lastSlash)
                    val flags = pattern.substring(lastSlash + 1)
                    return regex to flags
                }
            }
            return pattern to ""
        }

        private fun convertEs6RegexToJava(pattern: String): String {
            return pattern.replace(Regex("""\\u\{([0-9a-fA-F]+)\}""")) { match ->
                "\\x{${match.groupValues[1]}}"
            }
        }

        /**
         * 从已完成规则替换的文本中提取 (音效) 标记（纯计算，不触碰播放状态，可在后台线程调用）。
         * @return (去掉标记后的净文本, 音效列表)
         */
        @JvmStatic
        protected fun parseSoundEffects(processed: String): Pair<String, MutableList<SoundEffect>> {
            val effects = mutableListOf<SoundEffect>()
            val sb = StringBuilder()
            var lastEnd = 0
            soundEffectPattern.findAll(processed).forEach { match ->
                sb.append(processed.substring(lastEnd, match.range.first))
                val offset = sb.length
                val name = match.groupValues[1]
                effects.add(SoundEffect(offset, name, "$name.json"))
                lastEnd = match.range.last + 1
            }
            sb.append(processed.substring(lastEnd))
            return sb.toString() to effects
        }

        /**
         * 供合并流程使用：对文本应用音效替换规则并提取音效标记。
         * 返回 (去掉标记后的净文本, 音效列表)，不依赖播放时的 paragraphEffects 状态。
         * 可在无 service 实例时静态调用（如目录界面手动保存）。
         */
        fun extractSoundEffectsForMerge(
            text: String,
            prevText: String = ""
        ): Pair<String, List<SoundEffect>> {
            return parseSoundEffects(applySoundEffectRules(text, prevText))
        }

        /**
         * 供合并流程使用：按音效 JSON 文件名获取解码后的音效音频文件（复用播放流程的下载/解码逻辑）。
         * 可在无 service 实例时静态调用。
         */
        fun getSoundEffectAudioFile(fileName: String): java.io.File? {
            return downloadAndDecodeEffect(fileName, true)?.first
        }

        /**
         * 下载音效 JSON 并解码为音频文件。
         * 支持轮换：从 JSON 的 audios 数组中按 currentIndex 取出 base64，
         * 解码后保存为索引文件。若 consumeIndex 为 true，则更新 currentIndex 并写回 JSON。
         * 返回音频文件和当前索引。
         */
        private fun downloadAndDecodeEffect(fileName: String, consumeIndex: Boolean = false): Pair<java.io.File, Int>? {
            val sfxDir = java.io.File(SOUND_EFFECT_DIR)
            if (!sfxDir.exists()) sfxDir.mkdirs()
            val jsonFile = java.io.File(sfxDir, fileName)

            var jsonStr: String? = null
            // 1. 优先读取本地 JSON
            if (jsonFile.exists()) {
                try {
                    jsonStr = jsonFile.readText()
                    if (jsonStr.isNullOrEmpty() || !jsonStr.trimStart().startsWith("{")) jsonStr = null
                    else AppLog.putDebug("[音效] 读取本地 JSON: $fileName")
                } catch (e: Exception) {
                    AppLog.put("[音效] 本地 JSON 读取失败: $fileName, ${e.message}")
                    jsonStr = null
                }
            } else {
                AppLog.putDebug("[音效] 本地 JSON 不存在: $fileName")
            }

            // 2. 本地没有或无效，下载
            if (jsonStr == null) {
                val url = "https://cnb.cool/mingwuyan/yinpin/-/git/raw/main/bdyinxiao3/$fileName"
                AppLog.put("[音效] 开始下载: $url")
                try {
                    val response = io.legado.app.help.http.okHttpClient.newCall(
                        okhttp3.Request.Builder().url(url).build()
                    ).execute()
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty() && body.trimStart().startsWith("{")) {
                        jsonStr = body
                        jsonFile.writeText(jsonStr)
                        AppLog.put("[音效] 下载成功并保存: $fileName")
                    } else {
                        AppLog.put("[音效] 下载返回无效内容: $fileName")
                    }
                } catch (e: Exception) {
                    AppLog.put("[音效] 下载失败: $fileName, ${e.message}")
                }
            }

            if (jsonStr == null) {
                AppLog.put("[音效] 无法获取 JSON: $fileName")
                return null
            }

            // 3. 解析
            val json = try { org.json.JSONObject(jsonStr) } catch (e: Exception) {
                AppLog.put("[音效] JSON 解析失败: $fileName, ${e.message}")
                return null
            }
            val audios = json.optJSONArray("audios") ?: run {
                AppLog.put("[音效] 缺少 audios 数组: $fileName")
                return null
            }
            if (audios.length() == 0) {
                AppLog.put("[音效] audios 为空: $fileName")
                return null
            }

            // 4. 获取当前索引
            val savedIndex = json.optInt("currentIndex", 0)
            val index = if (savedIndex >= audios.length()) 0 else savedIndex
            val b64 = audios.optString(index) ?: run {
                AppLog.put("[音效] 索引 $index 无 base64: $fileName")
                return null
            }
            if (b64.length < 100) {
                AppLog.put("[音效] base64 太短(${b64.length}): $fileName idx=$index")
                return null
            }

            // 5. 若 consumeIndex 为 true，更新索引并写回 JSON
            if (consumeIndex && audios.length() > 1) {
                val nextIndex = (index + 1) % audios.length()
                try {
                    json.put("currentIndex", nextIndex)
                    jsonFile.writeText(json.toString())
                    AppLog.putDebug("[音效] 轮换索引: $fileName $index -> $nextIndex")
                } catch (e: Exception) {
                    AppLog.put("[音效] 索引更新失败: $fileName, ${e.message}")
                }
            }

            // 6. 解码并保存为索引文件
            val bytes = try {
                android.util.Base64.decode(b64.trim(), android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                AppLog.put("[音效] base64 解码失败: $fileName idx=$index, ${e.message}")
                return null
            }

            val audioFile = java.io.File(sfxDir, fileName.replace(".json", "_$index.mp3"))
            audioFile.writeBytes(bytes)
            AppLog.putDebug("[音效] 解码成功: $fileName idx=$index size=${bytes.size}")
            return audioFile to index
        }
    }

    private val useWakeLock = appCtx.getPrefBoolean(PreferKey.readAloudWakeLock, false)
    private val mainHandler by lazy { io.legado.app.utils.buildMainHandler() }
    private val wakeLock by lazy {
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "legado:ReadAloudService")
            .apply {
                this.setReferenceCounted(false)
            }
    }
    private val wifiLock by lazy {
        @Suppress("DEPRECATION")
        wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "legado:AudioPlayService")
            ?.apply {
                setReferenceCounted(false)
            }
    }
    private val mFocusRequest: AudioFocusRequestCompat by lazy {
        MediaHelp.buildAudioFocusRequestCompat(this)
    }
    private val mediaSessionCompat: MediaSessionCompat by lazy {
        MediaSessionCompat(this, "readAloud")
    }
    private val phoneStateListener by lazy {
        ReadAloudPhoneStateListener()
    }
    internal var contentList = emptyList<String>()
    internal var nowSpeak: Int = 0
    internal var readAloudNumber: Int = 0
    internal var textChapter: TextChapter? = null
    internal var pageIndex = 0
    /** 单段播放模式：只播放当前段落，播放完后自动暂停 */
    var singleParagraphMode = false
    /**
     * 从当前段落开头连续朗读模式（长按段落小喇叭触发）。
     * 与 [singleParagraphMode] 的区别：播完当前段不暂停，继续朗读后续段落并可跨章节。
     * 仅影响启动时的定位逻辑，开始播放后即复位。
     */
    var continueFromParagraph = false
    private var needResumeOnAudioFocusGain = false
    private var needResumeOnCallStateIdle = false
    private var registeredPhoneStateListener = false
    private var dsJob: Job? = null
    private var upNotificationJob: Coroutine<*>? = null
    private var cover: Bitmap =
        BitmapFactory.decodeResource(appCtx.resources, R.drawable.icon_read_book)
    var pageChanged = false
    private var toLast = false
    var paragraphStartPos = 0
    var readAloudByPage = false
        private set
    protected var isAutoSwitchingChapter = false

    // AI预分析下一首BGM
    internal var pendingNextBgmIndex: Int = -1
    private var lastBgmAnalysisTime: Long = 0L
    private var bgmAnalysisJob: Job? = null

    // AI 生图：字数驱动 + 预生成
    /** 当前 AI 图片场景序号（0,1,2...），解绑BGM后独立递增 */
    private var aiImageSceneIndex: Int = 0
    /** 自上次触发以来累计的已读字数 */
    private var aiImageAccumulatedChars: Int = 0
    /** 是否已执行过首次生成（避免重复触发） */
    private var aiImageFirstGenerated: Boolean = false
    /** 实时生成防重入标志：displayCurrentAiImage 启动生成后置 true，完成后置 false，防止重复调用 */
    private var aiImageRealtimeGenerating: Boolean = false
    /**
     * 首次触发生图时的起始段落。用于识别「同一章内从中间重新开始朗读」：
     * 起点变了就必须重新触发，否则 aiImageFirstGenerated 为 true 会直接跳过，
     * 画面一直停在上次位置的那张图上（即“总是显示前面的图片”）。
     */
    private var aiImageLastStartPara: Int = -1

    /**
     * 估算某段朗读的音频时长（毫秒）
     * 子类根据实际朗读方式实现（缓存音频时长或字数估算）
     */
    abstract fun estimateParagraphDuration(index: Int): Long

    private val ttsJsExtensions by lazy {
        object : JsExtensions {
            override fun getSource() = ReadAloud.httpTTS
            override fun getTag() = "TTS"
        }
    }

    /** gengxin 信号文件所在目录（随当前朗读引擎切换） */
    private val gengxinDir get() = io.legado.app.help.EngineDataDir.baseDir()
    /** cunfang.txt 存放书名，shuming.{书名}.json 是要检测修改时间的信号文件 */
    private val gengxinCunfangPath get() = gengxinDir + "cunfang.txt"
    private fun gengxinShumingPath(bookName: String) = gengxinDir + "shuming.$bookName.json"

    /** 后台轮询协程 */
    private var gengxinPollJob: Job? = null
    /** 上次检测到的信号文件修改时间，0 表示尚未记录 */
    private var gengxinLastMtime: Long = 0L
    /** 上次检测到的书名（cunfang.txt 内容），用于判断是否切换书籍 */
    private var gengxinLastBookName: String? = null
    /** 标记是否正在执行触发处理（防止重启过程中重复触发） */
    private var gengxinHandling: Boolean = false
    /** 标记本次朗读是 gengxin 恢复，跳过 newReadAloud 里的"跳过当前段"逻辑 */
    private var gengxinResuming: Boolean = false

    /**
     * 启动后台定时轮询：每秒检测 cunfang.txt → 提取书名 → 查 shuming.{书名}.json 的修改时间。
     * 时间变化则停止朗读、删除所有缓存、重新开始朗读。
     * 仅当开关开启时生效。
     */
    private fun startGengxinPolling() {
        gengxinPollJob?.cancel()
        gengxinPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    if (AppConfig.gengxinRealtimeUpdate && !gengxinHandling) {
                        checkGengxinSignalFile()
                    }
                } catch (e: Exception) {
                    AppLog.put("gengxin 轮询异常: ${e.localizedMessage}", e)
                }
                delay(1000)
            }
        }
    }

    /** 停止后台轮询 */
    private fun stopGengxinPolling() {
        gengxinPollJob?.cancel()
        gengxinPollJob = null
    }

    /**
     * 检测信号文件：读 cunfang.txt 内容(书名) → 查 shuming.{书名}.json 的 lastModified。
     * 若与上次记录不同则触发处理。
     */
    private fun checkGengxinSignalFile() {
        val cunfang = java.io.File(gengxinCunfangPath)
        if (!cunfang.exists()) return
        val bookName = cunfang.readText().trim()
        if (bookName.isEmpty()) return
        val shumingFile = java.io.File(gengxinShumingPath(bookName))
        if (!shumingFile.exists()) return
        val mtime = shumingFile.lastModified()
        // 首次记录 或 切换书籍（书名不同）：只更新记录，不触发处理
        if (gengxinLastMtime == 0L || bookName != gengxinLastBookName) {
            gengxinLastMtime = mtime
            gengxinLastBookName = bookName
            AppLog.put("gengxin 轮询: 记录 $bookName mtime=$mtime (首次或切换书籍，不触发)")
            return
        }
        if (mtime == gengxinLastMtime) return   // 同一本书时间未变，不触发
        // 同一本书且修改时间变化，触发处理
        AppLog.put("gengxin 轮询: 检测到 $bookName 修改时间变化 $gengxinLastMtime -> $mtime")
        gengxinLastMtime = mtime
        handleGengxinTrigger()
    }

    /**
     * gengxin 触发处理：
     * - 正在朗读：删除当前段之后的缓存 → 停止朗读 → 从当前段重新开始朗读
     * - 未朗读：删除所有缓存
     * 由子类实现 deleteGengxinCachesAfter() 删除指定段之后的缓存。
     */
    private fun handleGengxinTrigger() {
        gengxinHandling = true
        val speaking = isPlay()
        val curIndex = nowSpeak
        AppLog.put("gengxin 触发处理: speaking=$speaking, curIndex=$curIndex")
        if (speaking) {
            // 正在朗读：只删除当前段之后的缓存，当前段及之前保留
            deleteGengxinCachesAfter(curIndex)
            // 记录恢复段索引，重启后从此段继续
            pendingGengxinResumeIndex = curIndex
            // 停止当前朗读
            stopSelf()
            // 延迟让服务完全停止后重新开始朗读
            // 直接调 ReadAloud.play 而非 ReadBook.readAloud，避免循环重启
            mainHandler.postDelayed({
                gengxinHandling = false
                ReadAloud.play(appCtx, true, startPos = 0)
            }, 800)
        } else {
            // 未朗读：删除所有缓存
            deleteGengxinCachesAfter(-1)
            gengxinHandling = false
        }
    }

    /**
     * 删除指定段之后的缓存（不含当前段）。
     * @param fromIndex 从此 index 之后（含 fromIndex+1）开始删除；
     *                  传 -1 表示删除全部（未朗读场景）。
     * 子类按各自缓存后缀实现。
     */
    protected abstract fun deleteGengxinCachesAfter(fromIndex: Int)

    /**
     * 应用朗读脚本，按顺序执行启用的JS脚本，修改待朗读文本。
     * 脚本通过变量 text 获取当前文本，通过 java 调用 ajax、readFile、writeTxtFile 等扩展方法，
     * 返回值为修改后的文本。
     */
    protected fun applyTtsScripts(text: String): String {
        if (text.isEmpty() || text == " ") return text
        val scripts = try {
            appDb.ttsScriptDao.all.filter { it.isEnabled }.sortedBy { it.order }
        } catch (e: Exception) {
            return text
        }
        if (scripts.isEmpty()) return text
        val currentEngineName = getCurrentTtsEngineName()
        var result = text
        scripts.forEach { script ->
            if (script.code.isBlank()) return@forEach
            // 如果绑定了朗读引擎，检查当前引擎是否在绑定列表中
            if (script.bindTtsEngines.isNotBlank()) {
                val boundEngines = script.bindTtsEngines.split(",").map { it.trim() }
                if (currentEngineName != null && !boundEngines.contains(currentEngineName)) {
                    return@forEach
                }
            }
            try {
                val jsResult = RhinoScriptEngine.run {
                    val bindings = buildScriptBindings { bindings ->
                        bindings["java"] = ttsJsExtensions
                        bindings["cache"] = CacheManager
                        bindings["cookie"] = CookieStore
                        bindings["text"] = result
                        bindings["source"] = ReadAloud.httpTTS
                        bindings["speakText"] = text
                    }
                    eval(script.code, bindings)
                }
                if (jsResult != null) {
                    result = jsResult.toString()
                }
            } catch (e: Exception) {
                AppLog.put("朗读脚本[${script.name}]执行错误", e)
            }
        }
        return result
    }

    // ========== 音效处理 ==========

    /**
     * 段落音效映射：段落索引 -> 该段落的音效列表
     */
    val paragraphEffects = mutableMapOf<Int, MutableList<SoundEffect>>()

    /**
     * 音效信息
     * @param charOffset 在净化后文本中的字符偏移位置
     * @param name 音效名称，如 "打雷"
     * @param fileName 对应的 JSON 文件名，如 "打雷音效.json"；
     *                 AI 音效则为相对路径，如 "01_通用母库/拟音/炒菜.mp3"
     * @param triggered 是否已触发
     * @param offsetRatio 触发位置占段落净字数的比例（0~1）。非空时用比例换算偏移，
     *                    避免依赖段落实际字数，AI 音效的 start/mid/end 走这条路。
     * @param isAiEffect true 表示来自广播剧音效库，按相对路径从 [AiEffectDownloader] 取文件
     */
    data class SoundEffect(
        val charOffset: Int,
        val name: String,
        val fileName: String,
        var triggered: Boolean = false,
        val offsetRatio: Float? = null,
        val isAiEffect: Boolean = false
    )

    /**
     * 提取文本中的 (音效) 标记，记录其在净化后文本中的字符偏移位置。
     * 返回已移除标记的纯文本。
     */
    fun extractSoundEffects(text: String, index: Int): String {
        val (cleanText, effects) = parseSoundEffects(text)
        paragraphEffects[index] = effects
        return cleanText
    }

    /**
     * 重置所有音效触发状态，并清空播放队列。
     * AI 音效的触发状态一并重置，但识别记录保存在文件中，不会重新调用 AI。
     */
    fun resetSoundEffects() {
        paragraphEffects.values.forEach { list ->
            list.forEach { it.triggered = false }
        }
        aiEffects.values.forEach { list ->
            list.forEach { it.triggered = false }
        }
        effectPlayer?.run {
            stop()
            clearMediaItems()
        }
    }

    protected var currentParagraphTotalChars: Int = 0

    // ========== 音效播放（ExoPlayer 播放列表 + PCM 增益）==========

    /**
     * 音效增益处理器。作用在解码后的 PCM 上，支持 >100% 增益。
     *
     * 原来是 MediaPlayer.setVolume()，其上限被系统强制限制为 1.0f，
     * 超过 100% 的音量设置完全无效。改用 ExoPlayer 音频管线后，
     * 增益直接乘在 PCM 采样上，无此限制，且带 tanh 软限幅不会爆音。
     */
    private val effectGainProcessor: GainAudioProcessor by lazy {
        GainAudioProcessor(AppConfig.soundEffectVolume / 100f)
    }

    /** 音效播放器。构造失败时为 null，此时音效静默降级为不播放 */
    private var effectPlayer: ExoPlayer? = null

    private fun getOrCreateEffectPlayer(): ExoPlayer? {
        effectPlayer?.let { return it }
        return try {
            val renderersFactory = object : DefaultRenderersFactory(this) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink? {
                    return DefaultAudioSink.Builder(this@BaseReadAloudService)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf(effectGainProcessor))
                        .build()
                }
            }
            val player = ExoPlayer.Builder(this, renderersFactory).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // 列表播完后清空，回到闲置状态，下次 addMediaItem 重新 prepare
                        if (playbackState == Player.STATE_ENDED) {
                            lastEffectPlayTime = System.currentTimeMillis()
                            clearMediaItems()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        AppLog.put("[音效] 播放错误: ${error.message}")
                        // 出错的 item 无法自动跳过，移除后继续播剩下的
                        removeMediaItem(currentMediaItemIndex)
                        if (mediaItemCount > 0) {
                            prepare()
                            play()
                        } else {
                            lastEffectPlayTime = System.currentTimeMillis()
                        }
                    }
                })
            }
            effectPlayer = player
            player
        } catch (e: Exception) {
            AppLog.put("[音效] 播放器创建失败: ${e.message}")
            null
        }
    }

    protected var lastEffectPlayTime = 0L

    /**
     * 触发时下载/解码音效，然后加入播放队列。
     * ExoPlayer 播放列表会自动顺序播放，无需手动维护队列。
     *
     * AI 音效跳过 JSON 解码流程，直接取本地文件或下载远端 mp3。
     */
    protected fun loadAndPlayEffect(effect: SoundEffect) {
        lifecycleScope.launch(Dispatchers.IO) {
            val audioFile = if (effect.isAiEffect) {
                AiEffectDownloader.obtain(effect.fileName)
            } else {
                downloadAndDecodeEffect(effect.fileName, true)?.first
            }
            if (audioFile == null) {
                AppLog.put("[音效] 加载失败，跳过: ${effect.name}")
                return@launch
            }
            withContext(Dispatchers.Main) {
                enqueueOrPlayEffect(audioFile, effect.name)
            }
        }
    }

    /**
     * 将音效加入播放列表并立即开始播放。
     */
    private fun enqueueOrPlayEffect(audioFile: java.io.File, effectName: String) {
        try {
            val player = getOrCreateEffectPlayer() ?: return
            // 每次播放前刷新增益，使音量滑块调节实时生效
            effectGainProcessor.setGain(AppConfig.soundEffectVolume / 100f)
            val mediaItem = MediaItem.fromUri(android.net.Uri.fromFile(audioFile))
            // 列表已播完但 STATE_ENDED 的清空回调尚未执行时，mediaItemCount 仍 > 0，
            // 此时只 addMediaItem + play() 不会重新起播，该条音效会被静默丢弃。
            val ended = player.playbackState == Player.STATE_ENDED
            val needPrepare = player.mediaItemCount == 0 || ended
            player.addMediaItem(mediaItem)
            if (needPrepare) {
                if (ended) {
                    // 定位到刚加入的音效，避免重播列表中已播完的旧 item
                    player.seekTo(player.mediaItemCount - 1, 0)
                }
                player.prepare()
            }
            player.play()
            lastEffectPlayTime = System.currentTimeMillis()
            AppLog.put("[音效] 播放: $effectName")
        } catch (e: Exception) {
            AppLog.put("[音效] 播放失败: $effectName, ${e.message}")
        }
    }

    /**
     * 暂停当前音效播放（用于朗读暂停时）。
     */
    protected fun pauseSoundEffects() {
        effectPlayer?.pause()
    }

    /** 释放音效播放器资源 */
    private fun releaseEffectPlayer() {
        effectPlayer?.release()
        effectPlayer = null
    }

    /**
     * 根据当前播放进度检查并触发音效。
     * 规则音效与 AI 音效各有一套开关，互不影响。
     */
    protected fun checkAndPlayEffects(totalDuration: Long, currentPosition: Long) {
        if (totalDuration <= 0L) return
        // 段落切走了：上一段里没赶上的音效不能就这么丢掉，收进兜底队列补播
        flushMissedAiEffects()
        checkRuleEffects(totalDuration, currentPosition)
        checkAiEffects(totalDuration, currentPosition)
    }

    /**
     * 段落切换时，把上一段**尚未触发**的 AI 音效按落点顺序收进兜底队列并补播。
     *
     * 不这么做的话，只要 AI 结果晚于段落读完，这些音效就再也没有人会去检查，
     * 变成"识别出来了但没响"。
     */
    private fun flushMissedAiEffects() {
        val current = nowSpeak
        if (lastAiFlushedPara == current) return
        lastAiFlushedPara = current
        // 取所有已经读过的段落，而不是只取上一段——跳段/快进时会一次跨过好几段
        val overdue = aiEffects.keys.filter { it < current }.sorted()
        if (overdue.isEmpty()) return
        var count = 0
        for (para in overdue) {
            val missed = aiEffects.remove(para)
                ?.filter { !it.triggered }
                ?.sortedBy { it.offsetRatio ?: 0f }
            if (missed.isNullOrEmpty()) continue
            missed.forEach { offerMissedAiEffect(it) }
            count += missed.size
        }
        if (count == 0) return
        AppLog.put(
            "[AI音效] 段落 ${overdue.joinToString(",")} 已读完，" +
                "$count 条未触发，转入补播队列"
        )
        drainMissedAiEffects()
    }

    /** 收进兜底队列。超过上限就丢弃，避免队列越积越长、音效永远追不上朗读 */
    private fun offerMissedAiEffect(effect: SoundEffect) {
        if (aiMissedQueue.size >= aiMissedMax) return
        if (aiMissedQueue.any { it === effect }) return
        effect.triggered = true
        aiMissedQueue.addLast(effect)
    }

    /** 把兜底队列交给播放器。ExoPlayer 的 playlist 会按加入顺序依次播完 */
    private fun drainMissedAiEffects() {
        while (aiMissedQueue.isNotEmpty()) {
            loadAndPlayEffect(aiMissedQueue.removeFirst())
        }
    }

    /** 规则音效：由 (xxx音效) 标记 + ttsrv-replaces 规则生成 */
    private fun checkRuleEffects(totalDuration: Long, currentPosition: Long) {
        val effectiveMode = io.legado.app.model.ReadBook.book?.bookUrl?.let {
            AppConfig.getEffectiveSoundEffectMode(it)
        } ?: AppConfig.soundEffectMode
        if (effectiveMode == "off") return
        val effects = paragraphEffects[nowSpeak]
        if (effects.isNullOrEmpty()) return

        val duration = totalDuration.coerceAtLeast(1)
        val totalChars = currentParagraphTotalChars.coerceAtLeast(1)
        val ratio = currentPosition.toFloat() / duration
        val charPos = (ratio * totalChars).toInt()

        effects.forEach { effect ->
            if (!effect.triggered && charPos >= (effect.charOffset - AppConfig.soundEffectOffsetChars)) {
                effect.triggered = true
                loadAndPlayEffect(effect)
            }
        }
    }

    /** AI 音效：位置按段落净字数的比例换算，不依赖实际字数 */
    private fun checkAiEffects(totalDuration: Long, currentPosition: Long) {
        if (!AppConfig.aiEffectEnabled) return
        val effects = aiEffects[nowSpeak]
        if (effects.isNullOrEmpty()) return

        val duration = totalDuration.coerceAtLeast(1)
        val ratio = (currentPosition.toFloat() / duration).coerceIn(0f, 1f)
        // 与规则音效对齐：同样提前 soundEffectOffsetChars 个字起播，
        // 让音效先起来一点再被声音盖住，不至于读完那句才响
        val totalChars = currentParagraphTotalChars.coerceAtLeast(1)
        val leadChars = AppConfig.soundEffectOffsetChars.coerceAtLeast(0)
        val lead = (leadChars.toFloat() / totalChars).coerceAtMost(1f)

        effects.forEach { effect ->
            val target = ((effect.offsetRatio ?: 0f) - lead).coerceIn(0f, 1f)
            if (!effect.triggered && ratio >= target) {
                effect.triggered = true
                loadAndPlayEffect(effect)
            }
        }
    }

    // ========== AI 音效：预分析调度 ==========

    /** 段落索引 -> 该段落 AI 选中的音效 */
    private val aiEffects = mutableMapOf<Int, MutableList<SoundEffect>>()

    /**
     * 错过触发窗口的 AI 音效，按原顺序补播。
     *
     * 由来：AI 分析是异步的，结果常常在段落读到一半甚至读完之后才落地，
     * 而 [checkAiEffects] 只认 `aiEffects[nowSpeak]`、且要求进度已过落点，
     * 于是落点靠后的音效会被静默丢弃——"明明识别出来了却只响一个"就是这么来的。
     *
     * 这里把它们收进队列按原顺序补播：**宁可晚一点响，也不要整个丢掉**。
     * ExoPlayer 的 playlist 本身就是队列，顺序 addMediaItem 即可顺序播放。
     */
    private val aiMissedQueue = ArrayDeque<SoundEffect>()

    /** 兜底队列上限：分析一直落后时防止无限堆积，否则音效永远追不上朗读 */
    private val aiMissedMax = 12

    /** 上一次做过"过期清理"的段落号，用于检测段落切换 */
    private var lastAiFlushedPara = -1

    /**
     * 字幕同步用：已经生成过字幕的段落号，-1 表示还没有。
     *
     * **必须在 [clearAiEffectRuntime] 里一起复位**：停止后重读同一个段落时段落号没变，
     * 不复位的话字幕循环认为「这段字幕已经生成过了」而不再调 [prepareSubtitles]，
     * [scheduleAiEffects] 就永远不会被调用，记录文件里已有的音效也没人去装载
     * ——表现为「第一次有音效，停止后重读这一段反而没了」。
     */
    @Volatile
    protected var lastNowSpeak: Int = -1

    /**
     * 本章 AI 音效记录缓存：段落索引 -> 音效。
     * 开始朗读时整章只读一次文件，之后每段直接从内存取，
     * 避免每段都读一次文件（写入是全量覆盖，读写交错会读到空文件）。
     */
    private val aiEffectCache = HashMap<Int, List<AiEffectPick>>()

    /** 缓存对应的 "bookUrl/chapterIndex"，用来判断是否需要重新载入 */
    private var aiEffectCacheKey: String = ""

    /**
     * 待分析的**区间队列**，元素为 (起始段落, 结束段落)，串行处理。
     * 队列里放的是区间而不是单段，一次 AI 调用处理整个区间。
     */
    private val aiEffectPending = ArrayDeque<Pair<Int, Int>>()

    private var aiEffectWorker: Job? = null

    /** 首次进入是否已完成，用于保证开头第一段一定被分析 */
    private var aiEffectStarted = false

    /** 已安排过分析的区间终点（含）。下一个区间从这里 +1 开始，保证区间不重叠 */
    private var aiEffectScheduledMaxPara = -1

    /** 当前正在朗读的区间 [start, end]，读完就切到下一个已备好的区间 */
    private var aiEffectCurrentStartPara = -1
    private var aiEffectCurrentEndPara = -1

    /** worker 用的当前书与章节。跨章节后调度会刷新，避免记录写错章节 */
    @Volatile
    private var aiEffectBookUrl: String = ""

    @Volatile
    private var aiEffectChapterIndex: Int = 0

    /**
     * 按**字数区间**安排 AI 音效分析。子类在段落切换时调用。
     *
     * 一个区间 = 从某一段起往后累计够 [AppConfig.aiEffectCharInterval] 字所覆盖的段落。
     * 边界永远落在段落结尾（复用 [calculateEndParagraphByCharCount]，与 AI 生图
     * 切场景是同一套算法），所以设定字数只是目标值，实际会略多——最多多一整段。
     *
     * 策略照抄 AI 生图的「预生成」：**永远多备一个区间**。
     * - 开始朗读：安排「当前区间」+「下一个区间」
     * - 每读完一个区间：已备好的下一个转为当前，同时再补一个
     *
     * 于是从第二个区间起，结果在读到之前就已经算好了，不会再出现
     * 「结果落地时段落已读完」。第一个区间的结果可能晚一点，出来就播（走补播队列）。
     *
     * 已有记录的段落直接复用、不再问 AI，见 [analyzeAiEffectBatch]。
     */
    protected fun scheduleAiEffects() {
        if (!AppConfig.aiEffectEnabled) return
        val bookUrl = ReadBook.book?.bookUrl?.takeIf { it.isNotBlank() } ?: return
        aiEffectBookUrl = bookUrl
        aiEffectChapterIndex = ReadBook.durChapterIndex

        when {
            !aiEffectStarted -> {
                aiEffectStarted = true
                // 首次：当前区间 + 下一个区间，一开始就领先一个
                startRangeAt(nowSpeak)
                enqueueNextRange()
            }

            aiEffectCurrentStartPara in 0..aiEffectCurrentEndPara &&
                nowSpeak !in aiEffectCurrentStartPara..aiEffectCurrentEndPara -> {
                // 离开了当前区间
                if (nowSpeak == aiEffectCurrentEndPara + 1 &&
                    aiEffectScheduledMaxPara > aiEffectCurrentEndPara
                ) {
                    // 正常往下读：把已备好的下一个区间转为当前
                    aiEffectCurrentStartPara = aiEffectCurrentEndPara + 1
                    aiEffectCurrentEndPara = aiEffectScheduledMaxPara
                } else {
                    // 跳段 / 回退：已安排的区间对不上，从当前位置重开。
                    // 重复涉及的段落会命中记录缓存，不会重复问 AI
                    startRangeAt(nowSpeak)
                }
                enqueueNextRange()
            }
        }
        ensureAiEffectWorker()
    }

    /** 以 from 为起点开一个新区间并入队 */
    private fun startRangeAt(from: Int) {
        if (from < 0 || from >= contentList.size) return
        val end = calculateEndParagraphByCharCount(AppConfig.aiEffectCharInterval, from)
        if (end < from) return
        aiEffectCurrentStartPara = from
        aiEffectCurrentEndPara = end
        enqueueRange(from, end)
    }

    /** 安排「下一个还没安排过的区间」，使队列始终比朗读领先一个区间 */
    private fun enqueueNextRange() {
        val start = aiEffectScheduledMaxPara + 1
        if (start >= contentList.size) return
        val end = calculateEndParagraphByCharCount(AppConfig.aiEffectCharInterval, start)
        if (end < start) return
        enqueueRange(start, end)
    }

    private fun enqueueRange(start: Int, end: Int) {
        if (start < 0 || end < start) return
        synchronized(aiEffectPending) { aiEffectPending.addLast(start to end) }
        if (end > aiEffectScheduledMaxPara) aiEffectScheduledMaxPara = end
    }

    private fun ensureAiEffectWorker() {
        if (aiEffectWorker?.isActive == true) return
        aiEffectWorker = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val range = synchronized(aiEffectPending) {
                    if (aiEffectPending.isEmpty()) {
                        null
                    } else {
                        val r = aiEffectPending.first()
                        aiEffectPending.removeFirst()
                        r
                    }
                } ?: break
                try {
                    analyzeAiEffectBatch(
                        (range.first..range.second).toList(),
                        aiEffectBookUrl,
                        aiEffectChapterIndex
                    )
                } catch (e: CancellationException) {
                    // 切章/停止导致的取消：放行，让协程正常结束。
                    // 若在这里吞掉，取消会被当成普通异常继续跑后面的批次
                    throw e
                } catch (e: Exception) {
                    AppLog.put("[AI音效] 区间分析异常: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * 分析一批段落。
     * 已落盘（且确实有音效）的段落直接复用记录，其余段落进入批次调 AI。
     *
     * 记录里只保留真正配了音效的段落，所以「没音效」== 「没有记录」，
     * 这些段落每次朗读都会重新识别一次，不会因为一次判断失误就永久没有音效。
     */
    private suspend fun analyzeAiEffectBatch(
        indices: List<Int>,
        bookUrl: String,
        chapterIndex: Int
    ) {
        // 先把整章记录读进内存：有记录的段落直接复用，不再问 AI
        ensureAiEffectCache(bookUrl, chapterIndex)
        val todo = ArrayList<Pair<Int, String>>()
        for (i in indices) {
            val text = contentList.getOrNull(i) ?: continue
            val cached = aiEffectCache[i]
            if (cached != null) {
                applyAiPicks(i, cached)
                prefetchAiEffects(cached)
            } else {
                todo.add(i to text)
            }
        }
        if (todo.isEmpty()) return
        // 音效索引六千多条，全部命中记录时不该为它多等——
        // 加载慢的话段落可能已经念完，音效就永远排不上了。只在真要问 AI 时加载。
        AiEffectIndex.ensureLoaded()
        val result = analyzeByAiBatch(todo, bookUrl, chapterIndex) ?: return
        result.forEach { (index, picks) ->
            if (picks.isEmpty()) {
                // 没有音效的段不留记录：清掉可能存在的旧记录，下次可重新识别
                clearAiPicks(index)
            } else {
                applyAiPicks(index, picks)
                prefetchAiEffects(picks)
            }
        }
    }

    /**
     * 批量调 AI 并落盘。
     *
     * **只记录真正配了音效的段落**：picks 为空的段不写记录（并清掉旧记录），
     * 于是「无音效」等价于「无记录」，下次朗读会重新识别。
     * 若把空结果也落盘，该段落会被永久标记为无音效——重听、重启都不会再分析。
     *
     * @return 段落索引 -> 音效，**保证包含 [paras] 的每个索引**（无音效的段为空列表）；
     *         null 表示整批失败（不落盘，允许下次重试）。
     */
    private suspend fun analyzeByAiBatch(
        paras: List<Pair<Int, String>>,
        bookUrl: String,
        chapterIndex: Int
    ): Map<Int, List<AiEffectPick>>? {
        val prev = contentList.getOrNull((paras.firstOrNull()?.first ?: 0) - 1).orEmpty()
        val result = AiEffectAnalyzer.analyzeBatch(paras, prev) ?: return null
        result.forEach { (index, picks) ->
            if (picks.isEmpty()) {
                // 空结果不落盘；顺带清掉历史遗留的空/旧记录，让该段回到未分析状态
                aiEffectCache.remove(index)
                AiEffectStore.deleteParagraph(bookUrl, chapterIndex, index)
            } else {
                aiEffectCache[index] = picks
                AiEffectStore.mark(
                    bookUrl,
                    chapterIndex,
                    AiEffectRecord(paraIndex = index, effects = picks)
                )
            }
        }
        return result
    }

    /** 预下载音效，播到时直接用本地文件 */
    private suspend fun prefetchAiEffects(picks: List<AiEffectPick>) {
        picks.forEach { pick ->
            if (pick.path.isNotBlank()) AiEffectDownloader.obtain(pick.path)
        }
    }

    /** 把 AI 选中的音效转为播放任务（主线程） */
    private suspend fun applyAiPicks(index: Int, picks: List<AiEffectPick>) {
        // 位置序号按段落原文换算，和发给 AI 时插入的 [n] 是同一套锚点
        val text = contentList.getOrNull(index).orEmpty()
        val effects = picks.mapNotNull { pick ->
            if (pick.name.isBlank()) return@mapNotNull null
            SoundEffect(
                charOffset = 0,
                name = pick.name,
                fileName = pick.path,
                offsetRatio = AiEffectAnalyzer.ratioOfPos(text, pick.pos, pick.at),
                isAiEffect = true
            )
        }
        if (effects.isEmpty()) {
            clearAiPicks(index)
            return
        }
        withContext(Dispatchers.Main) {
            if (index < nowSpeak) {
                // 结果落地时这一段已经读完了：再排期也没人去检查，
                // 直接按落点顺序进补播队列，保证识别出来的音效都会响
                effects.sortedBy { it.offsetRatio ?: 0f }.forEach { offerMissedAiEffect(it) }
                aiEffects.remove(index)
                AppLog.put("[AI音效] 段落$index 落地时已读完，${effects.size}条直接补播")
                drainMissedAiEffects()
            } else {
                aiEffects[index] = effects.toMutableList()
            }
        }
    }

    /**
     * 清掉某段已排期的 AI 音效。
     * 用于段落重新分析后结果为空的场景，避免上一轮残留的音效继续播。
     */
    private suspend fun clearAiPicks(index: Int) = withContext(Dispatchers.Main) {
        aiEffects.remove(index)
    }

    /** 章节切换 / 停止时清空本轮状态，识别记录保留在文件中 */
    protected fun clearAiEffectRuntime() {
        aiEffectWorker?.cancel()
        aiEffectWorker = null
        synchronized(aiEffectPending) { aiEffectPending.clear() }
        aiEffects.clear()
        aiMissedQueue.clear()
        lastAiFlushedPara = -1
        aiEffectScheduledMaxPara = -1
        aiEffectCurrentStartPara = -1
        aiEffectCurrentEndPara = -1
        aiEffectStarted = false
        // 见 [lastNowSpeak] 的注释：不复位会导致重读同一段时不再调度 AI 音效
        lastNowSpeak = -1
        // 记录文件可能已被外部改动（如设置里清除本章记录），下次重新载入
        aiEffectCacheKey = ""
        // AI 弹幕：同步清空调度状态（记录保留在缓存文件，重读复用）
        clearDanmakuRuntime()
    }

    /**
     * 把整章记录读入内存缓存。
     * 只在切换书/章节时真正读文件，同一章内多次调用直接命中缓存。
     */
    private suspend fun ensureAiEffectCache(bookUrl: String, chapterIndex: Int) {
        val key = "$bookUrl/$chapterIndex"
        if (aiEffectCacheKey == key) return
        aiEffectCache.clear()
        aiEffectCacheKey = key
        val records = AiEffectStore.load(bookUrl, chapterIndex)
        records.forEach { r ->
            if (r.effects.isNotEmpty()) aiEffectCache[r.paraIndex] = r.effects
        }
        if (records.isNotEmpty()) {
            AppLog.put("[AI音效] 载入本章记录 ${records.size} 段")
        }
    }

    /**
     * 获取当前使用的朗读引擎标识，用于脚本绑定匹配。
     * HttpTTS 返回 http:ID，系统 TTS 返回 sys:包名。
     */
    private fun getCurrentTtsEngineName(): String? {
        val ttsEngine = ReadAloud.ttsEngine
        return if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            "http:$ttsEngine"
        } else {
            val pkg = AppConfig.sysTtsPackageName
            if (!pkg.isNullOrBlank()) "sys:$pkg" else null
        }
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                pauseReadAloud()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate() {
        super.onCreate()
        instance = this
        isRun = true
        isRunFlow.value = true
        pause = false
        observeLiveBus()
        initMediaSession()
        initBroadcastReceiver()
        initPhoneStateListener()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        // 优先套用「朗读未开始时」暂存的定时，其次才是「保存设定时间」里的默认值
        val startTimer = ReadAloud.pendingTimer ?: AppConfig.ttsTimer
        ReadAloud.pendingTimer = null
        setTimer(startTimer)
        if (startTimer > 0) {
            toastOnUi("朗读定时 ${startTimer} 分钟")
        }
        // 启动 gengxin 信号后台轮询（每秒检测一次）
        startGengxinPolling()
        execute {
            ImageLoader
                .loadBitmap(this@BaseReadAloudService, ReadBook.book?.getDisplayCover())
                .submit()
                .get()
        }.onSuccess {
            if (it.width > 16 && it.height > 16) {
                cover = it
                upReadAloudNotification()
            }
        }
        // 注册BGM回调：提供下一首索引，BGM播放/切换后触发预分析
        BgmManager.getNextBgmIndex = { pendingNextBgmIndex }
        BgmManager.onBgmStarted = {
            // 注意：这里【不能】把 service 侧 pendingNextBgmIndex 写回 BgmManager——
            // 旧实现每次切歌都写回，预分析一旦没跑成（防抖/收集失败/AI失败），
            // 旧推荐就永久残留，BGM 每次播完都切同一首（死循环）。
            // 预分析成功时 triggerBgmAnalysis 会主动写入 BgmManager.pendingNextBgmIndex，
            // 失败时 handlePlaybackEnded 已用完即弃走随机，无需在此同步。
            // 记录本次切歌：新歌从当前段落开始放
            recordBgmAtCurrentPara()
            // BGM预分析（仅推荐下一首BGM，AI生图已改为字数独立驱动）
            triggerBgmAnalysis()
        }
    }

    fun observeLiveBus() {
        observeEvent<Bundle>(EventBus.READ_ALOUD_PLAY) {
            val play = it.getBoolean("play")
            val pageIndex = it.getInt("pageIndex")
            val startPos = it.getInt("startPos")
            newReadAloud(play, pageIndex, startPos)
        }
        // 朗读播放音量增益实时生效：设置项修改后由设置页 postEvent 触发
        observeEvent<Float>(EventBus.READ_ALOUD_VOLUME_GAIN) { gain ->
            updateVolumeGain(gain)
        }
        observeEvent<String>(EventBus.AI_IMAGE_REQUEST) { _ ->
            // 朗读对话框请求显示当前 AI 图片
            // 若首次生成已触发，只需通知UI显示已有图片，不重复启动生成
            if (aiImageFirstGenerated) {
                val book = ReadBook.book
                if (book != null) {
                    // 仅当当前场景已有图片时才通知 UI。
                    // 生图失败或尚未就绪时保持当前显示，不再回退到"最近生成的图片"，
                    // 避免失败场景被其它旧图覆盖（即"失败保持状态"）。
                    AiImageGenerator.notifyImageIfReady(book.bookUrl, ReadBook.durChapterIndex, aiImageSceneIndex)
                }
            } else {
                displayCurrentAiImage()
            }
        }
        observeSharedPreferences { _, key ->
            when (key) {
                PreferKey.ignoreAudioFocus,
                PreferKey.pauseReadAloudWhilePhoneCalls -> {
                    initPhoneStateListener()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 释放音效播放器
        releaseEffectPlayer()
        // 停止 gengxin 信号后台轮询
        stopGengxinPolling()
        if (useWakeLock) {
            wakeLock.release()
            wifiLock?.release()
        }
        isRun = false
        isRunFlow.value = false
        pause = true
        // 服务结束即停掉倒计时并清掉定时残留，
        // 避免下次开面板还显示上一次的剩余时间、或残留值影响下一次朗读
        dsJob?.cancel()
        dsJob = null
        timeMinute = 0
        instance = null
        abandonFocus()
        unregisterReceiver(broadcastReceiver)
        postEvent(EventBus.ALOUD_STATE, Status.STOP)
        notificationManager.cancel(NotificationId.ReadAloudService)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSessionCompat.release()
        ReadBook.uploadProgress()
        unregisterPhoneStateListener(phoneStateListener)
        upNotificationJob?.invokeOnCompletion {
            notificationManager.cancel(NotificationId.ReadAloudService)
        }
        ReadAloudFloatService.stop(this)
        AiImageGenerator.clearCache()
        clearAiEffectRuntime()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.play -> {
                singleParagraphMode = intent.getBooleanExtra("singleParagraph", false)
                continueFromParagraph = intent.getBooleanExtra("continueFromParagraph", false)
                newReadAloud(
                    intent.getBooleanExtra("play", true),
                    intent.getIntExtra("pageIndex", ReadBook.durPageIndex),
                    intent.getIntExtra("startPos", 0)
                )
            }

            IntentAction.pause -> pauseReadAloud()
            IntentAction.resume -> resumeReadAloud()
            IntentAction.upTtsSpeechRate -> upSpeechRate(true)
            IntentAction.prevParagraph -> prevP()
            IntentAction.nextParagraph -> nextP()
            IntentAction.prev -> prevChapter()
            IntentAction.next -> nextChapter()
            IntentAction.addTimer -> addTimer()
            IntentAction.setTimer -> setTimer(intent.getIntExtra("minute", 0))
            IntentAction.stop -> stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun newReadAloud(play: Boolean, pageIndex: Int, startPos: Int) {
        execute(executeContext = Dispatchers.IO) {
            val textChapter = ReadBook.curTextChapter ?: return@execute
            if (!textChapter.isCompleted) {
                if (textChapter.pages.isEmpty()) {
                    AppLog.putDebug("newReadAloud: TextChapter 排版未完成且 pages 为空，等待排版完成")
                    return@execute
                }
                AppLog.putDebug("newReadAloud: TextChapter 排版未完成，使用已排版部分继续朗读")
            }
            this@BaseReadAloudService.pageIndex = pageIndex
            this@BaseReadAloudService.textChapter = textChapter
            readAloudNumber = textChapter.getReadLength(pageIndex) + startPos
            // 强制刷新：章节刚加载时后续章节常未下载，写入内容不完整，
            // 若沿用节流则开始朗读时拿不到完整的预加载章节
            ReadBook.saveTtsTextJson(force = true)
            readAloudByPage = getPrefBoolean(PreferKey.readAloudByPage)
            contentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
                .toMutableList()
                .apply { add(" ") }
            // 换了内容就作废上一轮的分析任务：段落索引已指向新内容，
            // 继续跑会把旧章节的结果写进新章节的记录文件
            clearAiEffectRuntime()
            var pos = startPos
            val page = textChapter.getPage(pageIndex)!!
            if (pos > 0) {
                for (paragraph in page.paragraphs) {
                    val tmp = pos - paragraph.length - 1
                    if (tmp < 0) break
                    pos = tmp
                }
            }
            nowSpeak = textChapter.getParagraphNum(readAloudNumber + 1, readAloudByPage) - 1
            if (singleParagraphMode || continueFromParagraph) {
                // 两者都要求从段落开头朗读，而非从页面/章节的中间位置切入
                paragraphStartPos = 0
            } else {
                if (!readAloudByPage && startPos == 0 && !toLast) {
                    pos = page.chapterPosition -
                            textChapter.paragraphs[nowSpeak].chapterPosition
                }
                if (toLast) {
                    toLast = false
                    readAloudNumber = textChapter.getLastParagraphPosition()
                    nowSpeak = (contentList.lastIndex - 1).coerceAtLeast(0)
                    if (page.paragraphs.size == 1) {
                        pos = page.chapterPosition -
                                textChapter.paragraphs[nowSpeak].chapterPosition
                    }
                }
                paragraphStartPos = pos
            }
            // gengxin 重启恢复：若有待恢复的段落索引，覆盖 nowSpeak 从该段继续
            if (pendingGengxinResumeIndex >= 0 && pendingGengxinResumeIndex < contentList.size) {
                AppLog.put("gengxin 恢复: nowSpeak $nowSpeak -> ${pendingGengxinResumeIndex}")
                nowSpeak = pendingGengxinResumeIndex
                paragraphStartPos = 0
                pendingGengxinResumeIndex = -1
                gengxinResuming = true   // 标记恢复，避免下方跳过当前段
            }
            launch(Dispatchers.Main) {
                if (play) {
                    LogUtils.d(TAG, "nowSpeak=$nowSpeak")
                    // 简化逻辑：nowSpeak==0直接朗读，nowSpeak!=0跳过当前段
                    // gengxin 恢复时不跳过（要从恢复段重新朗读）
                    // continueFromParagraph 同样不跳过：长按小喇叭要求从当前段落开头开始连续朗读，
                    // 若在此跳过，实际会从下一段开始，与预期不符。
                    //
                    // 「禁止跳下一段」开关（readAloudStartFromFirst）：
                    // - 翻页模式：跟随开关，关闭时跳至下一段开头
                    // - 滚动模式：强制等同于开关开启，即始终从当前段落开始
                    //   因为该模式下起点由屏幕可见首行精确定位（见 ReadBookActivity 的
                    //   scrollPageAnim 分支用 getReadAloudPos 取 pagePosition），
                    //   再跳到下一段会漏读用户正在看的那一段。
                    val isScrollMode = ReadBook.pageAnim() == PageAnim.scrollPageAnim
                    val shouldSkipCurrentParagraph =
                        !AppConfig.readAloudStartFromFirst && !isScrollMode

                    if (!gengxinResuming && !singleParagraphMode && !continueFromParagraph
                        && shouldSkipCurrentParagraph && !toLast && nowSpeak < contentList.size
                    ) {
                        if (nowSpeak != 0) {
                            LogUtils.d(TAG, "nowSpeak!=0，跳过当前段")
                            moveToNextParagraph()
                        } else {
                            LogUtils.d(TAG, "nowSpeak==0，直接朗读")
                        }
                    }
                    gengxinResuming = false   // 重置标志
                    // 开启状态：直接朗读，不做位置调整
                    isAutoSwitchingChapter = false
                    upTtsProgress(readAloudNumber + 1)
                    play()
                    // 仅用于启动定位，播完当前段要继续后续段落，故在此复位，
                    // 避免影响之后恢复播放、翻章等逻辑
                    continueFromParagraph = false
                    // 首次开始朗读时立即生成第一张AI图片，并预生成下一张
                    triggerFirstAiImageIfNeeded()
                } else {
                    pageChanged = true
                }
            }
        }.onError {
            AppLog.put("启动朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    @SuppressLint("WakelockTimeout")
    open fun play(affectBgm: Boolean = true) {
        if (useWakeLock) {
            wakeLock.acquire()
            wifiLock?.acquire()
        }
        isRun = true
        isRunFlow.value = true
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
        upFloatWindow()
    }

    abstract fun playStop(affectBgm: Boolean = true)

    /**
     * 朗读播放音量增益实时更新。子类（Http/TTS 朗读服务）应 override 此方法，
     * 将增益应用到各自 ExoPlayer 的 [io.legado.app.help.audio.GainAudioProcessor]。
     * 默认空实现，避免基类被实例化时出错。
     */
    open fun updateVolumeGain(gain: Float) {
        // 由子类实现
    }

    @CallSuper
    open fun pauseReadAloud(abandonFocus: Boolean = true) {
        wifiLock?.release()
        pause = true
        if (abandonFocus) {
            abandonFocus()
        }
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
        postEvent(EventBus.ALOUD_STATE, Status.PAUSE)
        ReadBook.uploadProgress()
        doDs()
    }

    @SuppressLint("WakelockTimeout")
    @CallSuper
    open fun resumeReadAloud() {
        resumeReadAloudInternal()
    }

    private fun resumeReadAloudInternal() {
        pause = false
        needResumeOnAudioFocusGain = false
        needResumeOnCallStateIdle = false
        upReadAloudNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(EventBus.ALOUD_STATE, Status.PLAY)
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    fun upTtsProgress(progress: Int) {
        postEvent(EventBus.TTS_PROGRESS, progress)
    }

    /**
     * 同步朗读位置到指定段落索引
     * 根据 contentList 重新计算 readAloudNumber，避免 updateNextPos 累积误差
     * 用于音频播放切换时直接定位，解决队列跳过段落导致的高亮不同步
     */
    protected fun syncToParagraph(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= contentList.size) return
        nowSpeak = targetIndex
        paragraphStartPos = 0
        readAloudNumber = 0
        for (i in 0 until targetIndex) {
            readAloudNumber += contentList[i].length + 1
        }
        // 跳转到新段落：回跳则记落点、前进则作废跨过的记录
        onBgmParagraphJumped(targetIndex)
    }

    private fun prevP() {
        if (nowSpeak > 0) {
            // 手动切换上一段时，不影响背景音乐
            stopReadAloudInternal(false)
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                    ReadBook.moveToPrevPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            playReadAloudInternal(false)
        } else {
            toLast = true
            ReadBook.moveToPrevChapter(true)
        }
    }

    private fun nextP() {
        if (nowSpeak < contentList.size - 1) {
            // 手动切换下一段时，不影响背景音乐
            stopReadAloudInternal(false)
            readAloudNumber += contentList[nowSpeak].length.plus(1) - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage && nowSpeak < it.getParagraphs(true).size) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
            }
            upTtsProgress(readAloudNumber + 1)
            playReadAloudInternal(false)
        } else {
            nextChapter()
        }
    }

    /**
     * 下一段（不播放，用于朗读开始前的位置调整）
     */
    private fun moveToNextParagraph() {
        if (nowSpeak < contentList.size - 1) {
            readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
            paragraphStartPos = 0
            nowSpeak++
            textChapter?.let {
                if (readAloudByPage && nowSpeak < it.getParagraphs(true).size) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber--
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber >= it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                }
            }
        }
    }

    /**
     * 上一段（不播放，用于朗读开始前的位置调整）
     */
    private fun moveToPrevParagraph() {
        if (nowSpeak > 0) {
            do {
                nowSpeak--
                readAloudNumber -= contentList[nowSpeak].length + 1 + paragraphStartPos
                paragraphStartPos = 0
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            textChapter?.let {
                if (readAloudByPage) {
                    val paragraphs = it.getParagraphs(true)
                    if (!paragraphs[nowSpeak].isParagraphEnd) readAloudNumber++
                }
                if (readAloudNumber < it.getReadLength(pageIndex)) {
                    pageIndex--
                }
            }
        }
    }

    /**
     * 内部停止朗读（不受背景音乐影响）
     * @param affectBgm 是否影响背景音乐播放状态
     */
    protected fun stopReadAloudInternal(affectBgm: Boolean = true) {
        playStop(affectBgm)
    }

    /**
     * 内部播放朗读（不受背景音乐影响）
     * @param affectBgm 是否影响背景音乐播放状态
     */
    protected fun playReadAloudInternal(affectBgm: Boolean = true) {
        play(affectBgm)
    }

    /**
     * 启动BGM：首次播放时先AI分析当前文本再播放，后续直接继续播放
     */
    protected fun startBgm() {
        if (!AppConfig.isBgmEnabled) return
        if (AppConfig.bgmAIEnabled) {
            if (BgmManager.hasPlayedOnce) {
                BgmManager.play()
                return
            }
            lifecycleScope.launch {
                try {
                    val currentBgm = BgmManager.getCurrentBgmName()
                    val contentToAnalyze = withContext(Dispatchers.IO) {
                        collectContentForAI()
                    }
                    if (!contentToAnalyze.isNullOrBlank()) {
                        val recommendedFileName = withContext(Dispatchers.IO) {
                            BgmAIService.analyzeContent(contentToAnalyze, currentBgm)
                        }
                        if (!recommendedFileName.isNullOrEmpty()) {
                            val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                            val targetIndex = if (index >= 0) index else BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                            if (targetIndex >= 0) {
                                AppLog.put("AI背景音乐: 第一首分析结果=$recommendedFileName, index=$targetIndex")
                                BgmManager.playByIndex(targetIndex)
                                return@launch
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLog.put("AI背景音乐首首分析失败: ${e.localizedMessage}")
                }
                BgmManager.play()
            }
            return
        }
        // AI智能切歌关闭：后台匹配BGM，匹配成功后再播放，不阻塞朗读流程
        // 没有关键词配置文件（背景音乐的关键词.json）或匹配不到时，兜底随机播放一首
        lifecycleScope.launch {
            try {
                // 先加载BGM文件列表和关键词配置（确保 allAudioFiles、playlist 和 categoryKeywords 可用）
                BgmManager.loadBgmFiles()

                // 如果已经播放过，直接继续播放当前音乐，不再重新匹配
                if (BgmManager.hasPlayedOnce) {
                    BgmManager.play()
                    return@launch
                }

                val currentBgm = BgmManager.getCurrentBgmName()
                val contentToAnalyze = withContext(Dispatchers.IO) {
                    collectContentForAI()
                }
                if (!contentToAnalyze.isNullOrBlank()) {
                    val recommendedFileName = withContext(Dispatchers.IO) {
                        BgmKeywordMatcher.analyze(contentToAnalyze, currentBgm)
                    }
                    if (!recommendedFileName.isNullOrEmpty()) {
                        val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                        val targetIndex = if (index >= 0) index else BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                        if (targetIndex >= 0) {
                            AppLog.put("关键词BGM: 匹配成功=$recommendedFileName, index=$targetIndex")
                            BgmManager.playByIndex(targetIndex)
                            return@launch
                        }
                    }
                }
                // 没配置关键词、或没匹配到推荐曲目：随机播放（BgmManager 内歌单已 shuffle）
                AppLog.put("关键词BGM: 无匹配结果，随机播放")
                BgmManager.play()
            } catch (e: Exception) {
                AppLog.put("关键词BGM匹配失败: ${e.localizedMessage}")
                BgmManager.play()
            }
        }
    }

    // ==================== BGM 播放记录 ====================
    // 朗读时把「哪首歌从哪一段开始播」写进每章一个的 JSON 文件，
    // 章节合并时直接照这份记录铺背景音乐，而不是重新选曲。

    /** 上次记录 BGM 时所在的段落，用于判定「跨过」区间 */
    private var lastBgmRecordPara: Int = -1

    /**
     * 当前段内已播放位置（毫秒）。
     * Http 朗读能精确取到播放器的 currentPosition；系统 TTS 取不到，返回 0（按段落开头算）。
     */
    protected open fun getCurrentParaPosMs(): Long = 0L

    private fun bgmBookUrl(): String? = ReadBook.book?.bookUrl?.takeIf { it.isNotBlank() }

    /** 记录「当前这首 BGM 从当前段落开始播放」。切歌时调用，新歌从头放，故 songOffsetMs = 0。 */
    private fun recordBgmAtCurrentPara() {
        if (!AppConfig.isBgmEnabled) return
        val bookUrl = bgmBookUrl() ?: return
        val name = BgmManager.getCurrentBgmName() ?: return
        val mark = BgmMark(
            bgmName = name,
            paraIndex = nowSpeak,
            posInParaMs = getCurrentParaPosMs(),
            songOffsetMs = 0L
        )
        val chapterIndex = ReadBook.durChapterIndex
        lifecycleScope.launch {
            BgmTimelineStore.mark(bookUrl, chapterIndex, mark)
        }
        lastBgmRecordPara = nowSpeak
    }

    /**
     * 跨章节接续：上一章放到一半的歌，本章接着放后半段。
     * 必须在新章节索引生效后调用，songOffsetMs 取当前歌已播放位置。
     */
    fun recordBgmChapterStart(newChapterIndex: Int) {
        if (!AppConfig.isBgmEnabled) return
        val bookUrl = bgmBookUrl() ?: return
        val name = BgmManager.getCurrentBgmName() ?: return
        val mark = BgmMark(
            bgmName = name,
            paraIndex = 0,
            posInParaMs = 0L,
            songOffsetMs = BgmManager.getCurrentBgmPosition()
        )
        lifecycleScope.launch {
            BgmTimelineStore.mark(bookUrl, newChapterIndex, mark)
        }
        lastBgmRecordPara = 0
    }

    /**
     * 段落推进时调用（子类 nowSpeak++ 之后）。
     * 把被跨过的旧记录标记为 stale——不删除，留作导出时的候选切换点：
     * 当前这首铺够 AppConfig.bgmMinSegmentMs 后才允许切过去，否则由前一首延长覆盖。
     */
    protected fun onBgmParagraphAdvanced() {
        if (!AppConfig.isBgmEnabled) return
        val from = lastBgmRecordPara
        if (from >= 0 && nowSpeak > from) {
            val bookUrl = bgmBookUrl() ?: return
            val chapterIndex = ReadBook.durChapterIndex
            lifecycleScope.launch {
                BgmTimelineStore.invalidateRange(bookUrl, chapterIndex, from, nowSpeak)
            }
        }
        lastBgmRecordPara = nowSpeak
    }

    /**
     * 跳转段落（拖动进度条 / 回跳）时调用。
     * - 回跳：**不批量删**后面的记录，只在落点记一条（同段落则覆盖）
     * - 前进：跨过的区间标记 stale
     */
    private fun onBgmParagraphJumped(newIndex: Int) {
        if (!AppConfig.isBgmEnabled) return
        val bookUrl = bgmBookUrl() ?: return
        val chapterIndex = ReadBook.durChapterIndex
        if (newIndex < lastBgmRecordPara || lastBgmRecordPara < 0) {
            // 回跳（或首次定位）：落点记一条当前正在放的歌，带上它已播放位置以便接续
            val name = BgmManager.getCurrentBgmName() ?: return
            val mark = BgmMark(
                bgmName = name,
                paraIndex = newIndex,
                posInParaMs = 0L,
                songOffsetMs = BgmManager.getCurrentBgmPosition()
            )
            lifecycleScope.launch {
                BgmTimelineStore.mark(bookUrl, chapterIndex, mark)
            }
        } else if (newIndex > lastBgmRecordPara) {
            val from = lastBgmRecordPara
            lifecycleScope.launch {
                BgmTimelineStore.invalidateRange(bookUrl, chapterIndex, from, newIndex)
            }
        }
        lastBgmRecordPara = newIndex
    }

    /** 开始/切换朗读时重置段落追踪，避免跨章后误判「跨过」区间 */
    fun resetBgmRecordPara(paraIndex: Int) {
        lastBgmRecordPara = paraIndex
    }

    // ==================== BGM 播放记录 结束 ====================

    /**
     * BGM播放时预分析下一首：根据BGM剩余时长定位朗读结束段落，
     * 从那收集文本让AI分析，实现BGM切换与朗读场景对齐
     */
    fun triggerBgmAnalysis() {
        if (AppConfig.bgmAIEnabled) {
            val now = System.currentTimeMillis()
            if (now - lastBgmAnalysisTime < 5000L) {
                AppLog.put("AI背景音乐: 预分析被5秒防抖跳过")
                return
            }
            lastBgmAnalysisTime = now
            bgmAnalysisJob?.cancel()
            AppLog.put("AI背景音乐: ==== 触发预分析 ====")
            bgmAnalysisJob = lifecycleScope.launch {
                try {
                    val remainingMs = BgmManager.getRemainingDuration()
                    val currentBgm = BgmManager.getCurrentBgmName()
                    AppLog.put("AI背景音乐: 当前BGM=$currentBgm, 剩余时长=${remainingMs}ms")
                    val contentToAnalyze = if (remainingMs > 0) {
                        val endParagraph = calculateEndParagraph(remainingMs)
                        AppLog.put("AI背景音乐: BGM剩余${remainingMs}ms, 预计结束在段落$endParagraph")
                        withContext(Dispatchers.IO) {
                            collectContentFromParagraph(endParagraph)
                        }
                    } else {
                        AppLog.put("AI背景音乐: remainingMs<=0，从当前段落收集")
                        withContext(Dispatchers.IO) {
                            collectContentForAI()
                        }
                    }
                    if (contentToAnalyze.isNullOrBlank()) {
                        LogUtils.d(TAG, "AI背景音乐: 未收集到有效文本")
                        AppLog.put("AI背景音乐: 未收集到有效文本")
                        return@launch
                    }
                    LogUtils.d(TAG, "AI背景音乐: 预分析文本长度=${contentToAnalyze.length}")
                    AppLog.put("AI背景音乐: 预分析文本长度=${contentToAnalyze.length}")
                    val recommendedFileName = withContext(Dispatchers.IO) {
                        BgmAIService.analyzeContent(contentToAnalyze, currentBgm)
                    }
                    if (!recommendedFileName.isNullOrEmpty()) {
                        val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                        var resolved = if (index >= 0) {
                            index
                        } else {
                            BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                        }
                        // 推荐结果不得是当前正在播放的这首（AI 返回被排除歌名时的最后防线）
                        if (resolved == BgmManager.getCurrentPlayingIndex()) {
                            AppLog.put("AI背景音乐: 推荐与当前播放相同，弃用本轮推荐")
                            resolved = -1
                        }
                        pendingNextBgmIndex = resolved
                        BgmManager.pendingNextBgmIndex = pendingNextBgmIndex
                        AppLog.put("AI背景音乐: 预分析下一首=$recommendedFileName, index=$pendingNextBgmIndex")
                        if (pendingNextBgmIndex >= 0 && !BgmManager.isPlaying()) {
                            AppLog.put("AI背景音乐: 分析完成，BGM暂停中，主动播放 index=$pendingNextBgmIndex")
                            BgmManager.playByIndex(pendingNextBgmIndex)
                        }
                        // AI生图已改为字数独立驱动，不再由BGM预分析触发
                    } else {
                        AppLog.put("AI背景音乐: AI未返回推荐文件名 (result=$recommendedFileName)")
                    }
                } catch (e: Exception) {
                    LogUtils.e(TAG, "AI背景音乐预分析失败：${e.message}")
                    AppLog.put("AI背景音乐预分析失败：${e.localizedMessage}")
                }
            }
            return
        }
        // AI智能切歌关闭：使用关键词匹配进行预分析
        val now = System.currentTimeMillis()
        if (now - lastBgmAnalysisTime < 5000L) {
            AppLog.put("关键词BGM: 预分析被5秒防抖跳过")
            return
        }
        lastBgmAnalysisTime = now
        bgmAnalysisJob?.cancel()
        AppLog.put("关键词BGM: ==== 触发预分析 ====")
        bgmAnalysisJob = lifecycleScope.launch {
            try {
                val remainingMs = BgmManager.getRemainingDuration()
                val currentBgm = BgmManager.getCurrentBgmName()
                AppLog.put("关键词BGM: 当前BGM=$currentBgm, 剩余时长=${remainingMs}ms")
                val contentToAnalyze = if (remainingMs > 0) {
                    val endParagraph = calculateEndParagraph(remainingMs)
                    AppLog.put("关键词BGM: BGM剩余${remainingMs}ms, 预计结束在段落$endParagraph")
                    withContext(Dispatchers.IO) {
                        collectContentFromParagraph(endParagraph)
                    }
                } else {
                    AppLog.put("关键词BGM: remainingMs<=0，从当前段落收集")
                    withContext(Dispatchers.IO) {
                        collectContentForAI()
                    }
                }
                if (contentToAnalyze.isNullOrBlank()) {
                    AppLog.put("关键词BGM: 未收集到有效文本")
                    return@launch
                }
                AppLog.put("关键词BGM: 预分析文本长度=${contentToAnalyze.length}")
                val recommendedFileName = withContext(Dispatchers.IO) {
                    BgmKeywordMatcher.analyze(contentToAnalyze, currentBgm)
                }
                if (!recommendedFileName.isNullOrEmpty()) {
                    val index = BgmManager.findMediaItemIndexByName(recommendedFileName)
                    var resolved = if (index >= 0) {
                        index
                    } else {
                        BgmManager.findMediaItemIndexFuzzy(recommendedFileName)
                    }
                    // 与 AI 路径同款防线：推荐不得与当前播放相同
                    if (resolved == BgmManager.getCurrentPlayingIndex()) {
                        AppLog.put("关键词BGM: 推荐与当前播放相同，弃用本轮推荐")
                        resolved = -1
                    }
                    pendingNextBgmIndex = resolved
                    BgmManager.pendingNextBgmIndex = pendingNextBgmIndex
                    AppLog.put("关键词BGM: 预分析下一首=$recommendedFileName, index=$pendingNextBgmIndex")
                    if (pendingNextBgmIndex >= 0 && !BgmManager.isPlaying()) {
                        AppLog.put("关键词BGM: 分析完成，BGM暂停中，主动播放 index=$pendingNextBgmIndex")
                        BgmManager.playByIndex(pendingNextBgmIndex)
                    }
                } else {
                    AppLog.put("关键词BGM: 未匹配到推荐文件名")
                }
            } catch (e: Exception) {
                AppLog.put("关键词BGM预分析失败：${e.localizedMessage}")
            }
        }
    }

    // ==================== AI 弹幕（与 AI 音效同款区间调度） ====================

    /**
     * 待分析的区间队列，元素为 (起始段落, 结束段落)，串行处理。
     * 区间按 [AppConfig.aiEffectCharInterval]（沿用 AI 音效/BGM 的分析字数）切分，
     * 队列始终比朗读领先一个区间，实现「提前生成、到位触发」。
     */
    private val danmakuPending = ArrayDeque<Pair<Int, Int>>()
    private var danmakuWorker: Job? = null

    /** 首次进入是否已启动 */
    private var danmakuStarted = false

    /** 已安排过分析的区间终点（含） */
    private var danmakuScheduledMaxPara = -1

    /** 当前正在朗读的区间 [start, end] */
    private var danmakuCurrentStartPara = -1
    private var danmakuCurrentEndPara = -1

    /** 缓存对应的 "bookUrl/chapterIndex"，章节切换时重置调度 */
    private var danmakuChapterKey = ""

    /** 章节 key（书名|章节标题）：Store/事件/缓存文件共用的章节标识 */
    private var danmakuNameKey = ""
    private var danmakuBookName = ""
    private var danmakuChapterTitle = ""

    /** 整章内容 hash（写入弹幕缓存，章节更新后缓存自动失效） */
    private var danmakuContentHash = ""
    private var danmakuChapterTotalChars = 0

    /** 弹幕条数按区间字数占比分摊时的小数余额（累积到后续区间，保证全章总条数≈设置值） */
    private var danmakuCountRemainder = 0.0

    /**
     * 每段朗读开始时调用（prepareSubtitles），驱动弹幕区间的提前调度：
     * - 首段：从当前位置开区间并入队，再预排下一个区间；
     * - 正常往下读：把已备好的下一个区间转为当前区间，继续预排；
     * - 跳段/回退：从当前位置重开区间（重复段落命中记录缓存，不重复问 AI）。
     */
    protected fun scheduleDanmaku() {
        if (!AppConfig.danmakuEnabled) return
        val book = ReadBook.book ?: return
        val title = textChapter?.title ?: ReadBook.curTextChapter?.title ?: return
        val chapterKey = "${book.bookUrl}/${ReadBook.durChapterIndex}"
        val nameKey = "${book.name}|$title"
        if (chapterKey != danmakuChapterKey) {
            danmakuChapterKey = chapterKey
            danmakuNameKey = nameKey
            danmakuBookName = book.name
            danmakuChapterTitle = title
            danmakuContentHash = contentList.joinToString("\n").hashCode().toString()
            danmakuChapterTotalChars = contentList.sumOf { it.length + 1 }
            danmakuCountRemainder = 0.0
            danmakuStarted = false
            danmakuScheduledMaxPara = -1
            danmakuCurrentStartPara = -1
            danmakuCurrentEndPara = -1
            synchronized(danmakuPending) { danmakuPending.clear() }
            // 切章后旧锚点全部作废（同章 seek 重入时 key 不变，保留已生成结果）
            if (DanmakuStore.key != null && DanmakuStore.key != nameKey) {
                DanmakuStore.clear()
            }
        }
        when {
            !danmakuStarted -> {
                danmakuStarted = true
                startDanmakuRangeAt(nowSpeak)
                enqueueNextDanmakuRange()
            }

            danmakuCurrentStartPara in 0..danmakuCurrentEndPara &&
                nowSpeak !in danmakuCurrentStartPara..danmakuCurrentEndPara -> {
                if (nowSpeak == danmakuCurrentEndPara + 1 &&
                    danmakuScheduledMaxPara > danmakuCurrentEndPara
                ) {
                    danmakuCurrentStartPara = danmakuCurrentEndPara + 1
                    danmakuCurrentEndPara = danmakuScheduledMaxPara
                } else {
                    startDanmakuRangeAt(nowSpeak)
                }
                enqueueNextDanmakuRange()
            }
        }
        ensureDanmakuWorker()
    }

    /** 以 from 为起点开一个新区间并入队 */
    private fun startDanmakuRangeAt(from: Int) {
        if (from < 0 || from >= contentList.size) return
        val end = calculateEndParagraphByCharCount(AppConfig.aiEffectCharInterval, from)
        if (end < from) return
        danmakuCurrentStartPara = from
        danmakuCurrentEndPara = end
        enqueueDanmakuRange(from, end)
    }

    /** 安排「下一个还没安排过的区间」，使队列始终比朗读领先一个区间 */
    private fun enqueueNextDanmakuRange() {
        val start = danmakuScheduledMaxPara + 1
        if (start >= contentList.size) return
        val end = calculateEndParagraphByCharCount(AppConfig.aiEffectCharInterval, start)
        if (end < start) return
        enqueueDanmakuRange(start, end)
    }

    private fun enqueueDanmakuRange(start: Int, end: Int) {
        if (start < 0 || end < start) return
        synchronized(danmakuPending) { danmakuPending.addLast(start to end) }
        if (end > danmakuScheduledMaxPara) danmakuScheduledMaxPara = end
    }

    /**
     * 串行 worker：逐区间生成弹幕。
     * 每区间条数按「总条数 × 区间字数占比」分摊；区间结果合并进
     * [DanmakuStore] 并全量广播 DANMAKU_READY（弹窗据此增量刷新）。
     */
    private fun ensureDanmakuWorker() {
        if (danmakuWorker?.isActive == true) return
        danmakuWorker = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val range = synchronized(danmakuPending) {
                    danmakuPending.removeFirstOrNull()
                } ?: break
                val bookName = danmakuBookName
                val title = danmakuChapterTitle
                if (bookName.isEmpty() || title.isEmpty()) continue
                // 快照区间段落（与章节切换并发改写 contentList 的窗口极小，且可重入）
                val paras = ArrayList<Pair<Int, String>>()
                for (i in range.first..range.second) {
                    val text = contentList.getOrNull(i)
                    if (!text.isNullOrBlank() && !text.matches(AppPattern.notReadAloudRegex)) {
                        paras.add(i to text)
                    }
                }
                if (paras.isEmpty()) continue
                // 每区间条数 = 总条数 × 区间实际字数占比，小数余额累积到后续区间。
                // 条数固定为 DANMAKU_COUNT_PER_CHAPTER（原输入框已移除：旧取整逻辑
                // 让设置在中长章节里密度毫无差别，没有存在的意义）。
                val count = if (danmakuChapterTotalChars > 0) {
                    val rangeChars = paras.sumOf { it.second.length + 1 }
                    val exact = DANMAKU_COUNT_PER_CHAPTER.toDouble() *
                        rangeChars / danmakuChapterTotalChars
                    val acc = danmakuCountRemainder + exact
                    val n = kotlin.math.floor(acc + 1e-9).toInt().coerceAtLeast(0)
                    danmakuCountRemainder = acc - n
                    n
                } else 1
                val items = DanmakuGenerator.generateRange(
                    bookName, title, danmakuContentHash, paras, count
                )
                if (items.isNotEmpty()) {
                    val all = DanmakuStore.merge(danmakuNameKey, items)
                    postEvent(EventBus.DANMAKU_READY, DanmakuReady(danmakuNameKey, all))
                }
            }
        }
    }

    /** 章节切换/停止时清空调度状态；生成记录保留在缓存文件中，重读直接复用 */
    private fun clearDanmakuRuntime() {
        danmakuWorker?.cancel()
        danmakuWorker = null
        synchronized(danmakuPending) { danmakuPending.clear() }
        danmakuStarted = false
        danmakuScheduledMaxPara = -1
        danmakuCurrentStartPara = -1
        danmakuCurrentEndPara = -1
        danmakuChapterKey = ""
        danmakuCountRemainder = 0.0
    }

    /**
     * 供设置界面在朗读运行中手动触发弹幕生成：
     * 重置调度状态后立即从当前位置开始排区间（命中缓存不重复请求）。
     */
    fun triggerDanmakuGenerationManual() {
        clearDanmakuRuntime()
        scheduleDanmaku()
    }

    /**
     * 计算BGM剩余时长内朗读会走到哪个段落
     * 从当前段落(nowSpeak)开始累加各段时长，累加>=remainingMs时停止
     */
    private fun calculateEndParagraph(remainingMs: Long): Int {
        var accumulated = 0L
        val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
        for (i in nowSpeak until contentList.size) {
            val duration = estimateParagraphDuration(i)
            accumulated += duration
            if (accumulated >= remainingMs) {
                return i.coerceAtMost(realLastIndex)
            }
        }
        return realLastIndex
    }

    /**
     * 从指定段落开始收集AI分析文本
     * 用于BGM切换前的预分析，收集BGM结束时朗读所在场景
     */
    private fun collectContentFromParagraph(startIndex: Int): String? {
        val maxCharCount = 5000
        val targetChars = AppConfig.bgmAICharInterval.coerceIn(100, 1000)
        val sb = StringBuilder()
        var currentCharCount = 0

        val book = ReadBook.book
        if (book != null) {
            sb.append("【书籍信息】\n")
            sb.append("书名：${book.name}\n")
            if (book.author.isNotEmpty()) sb.append("作者：${book.author}\n")
            if (!book.kind.isNullOrEmpty()) sb.append("分类：${book.kind}\n")
            if (!book.customTag.isNullOrEmpty()) sb.append("自定义分类：${book.customTag}\n")
            sb.append("【正文内容】\n\n")
            currentCharCount = sb.length
        }

        for (i in startIndex until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) {
                continue
            }

            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) {
                    sb.append(paragraph.substring(0, remainingChars))
                }
                break
            }

            sb.append(paragraph)
            sb.append("\n")
            currentCharCount += paragraph.length + 1

            if (currentCharCount >= targetChars) break
        }

        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 段落计数切换已废弃，由BGM播放状态驱动切换
     */
    protected fun checkBgmSwitch() {
        // 不再使用段落计数触发BGM切换
    }

    /**
     * 收集用于AI分析的文本内容
     * 基于段落位置，收集当前段落及后续段落的文本
     * 包含书籍信息（书名、作者、分类）
     * 收集总字数由 bgmAICharInterval 决定（默认350字）
     */
    internal fun collectContentForAI(): String? {
        val maxCharCount = 5000 // 8000 tokens约等于5000字符（考虑中英混合）
        val targetChars = AppConfig.bgmAICharInterval.coerceIn(100, 1000)
        val sb = StringBuilder()
        var currentCharCount = 0

        // 添加书籍信息
        val book = ReadBook.book
        if (book != null) {
            sb.append("【书籍信息】\n")
            sb.append("书名：${book.name}\n")
            if (book.author.isNotEmpty()) {
                sb.append("作者：${book.author}\n")
            }
            if (!book.kind.isNullOrEmpty()) {
                sb.append("分类：${book.kind}\n")
            }
            if (!book.customTag.isNullOrEmpty()) {
                sb.append("自定义分类：${book.customTag}\n")
            }
            sb.append("【正文内容】\n\n")
            currentCharCount = sb.length
        }

        // 从当前段落开始，收集文本直到达到targetChars字数
        for (i in nowSpeak until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(io.legado.app.constant.AppPattern.notReadAloudRegex)) {
                continue
            }

            // 如果添加这个段落后会超过字符限制，则截断
            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) {
                    sb.append(paragraph.substring(0, remainingChars))
                }
                break
            }

            sb.append(paragraph)
            sb.append("\n")
            currentCharCount += paragraph.length + 1

            // 正文部分达到目标字数就停止
            if (currentCharCount >= targetChars) {
                break
            }
        }

        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 显示当前场景对应的AI图片（字数驱动，不再依赖BGM索引）
     * 优先显示预生成的图片，若不存在则实时生成
     * 含防重入保护：避免 triggerFirst 和 AI_IMAGE_REQUEST 同时触发导致重复生成
     */
    /**
     * @param skipLocateDisplay 为 true 时跳过「按朗读位置定位并推送图片」这一步，
     *        只做缓存判定与预生成。用于朗读起点变化（手动翻页/跳转）的场景：
     *        阅读页已经按「当前屏幕第二个段落号」定位并推送过了，
     *        这里再按 nowSpeak 定位一次会因锚点不同而推出另一张图，表现为"闪一下再切回"。
     */
    fun displayCurrentAiImage(skipLocateDisplay: Boolean = false) {
        if (!AppConfig.readAloudAiImage) {
            AppLog.put("AI生图[DEBUG] displayCurrentAiImage: readAloudAiImage=false, 跳过")
            return
        }
        val book = ReadBook.book ?: run {
            AppLog.put("AI生图[DEBUG] displayCurrentAiImage: ReadBook.book=null, 跳过")
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        AppLog.put("AI生图[DEBUG] displayCurrentAiImage: sceneIndex=$aiImageSceneIndex, chapterIndex=$chapterIndex")
        // 当前朗读位置对应的段落区间，用于按段落匹配内部缓存。
        // 从章节中间开始朗读时 sceneIndex 语义与上次从头朗读不同，
        // 必须靠这个区间才能匹配到正确的图片；匹配不上则触发生成。
        val cacheCtx = buildPersistentCacheContext()
        val startPara = cacheCtx?.startPara ?: -1
        val endPara = cacheCtx?.endPara ?: -1
        // 1. 先尝试显示已缓存的图片（内部缓存按段落区间匹配）
        if (!skipLocateDisplay) {
            val notified = AiImageGenerator.notifyImageIfReady(
                book.bookUrl, chapterIndex, aiImageSceneIndex, startPara, endPara
            )
            // 1.1 精确匹配不到时：按当前段落在本章已缓存图片里就近（p±10）找一张，
            //     暂时显示并定位图库锚点。只负责显示/定位，不触发生成；
            //     后续真正生成或命中当前场景的图时会自动切过去。
            if (!notified && startPara >= 0) {
                AiImageGenerator.showNearestChapterImage(startPara)
            }
        }
        // 2. 如果缓存不存在，实时生成（首次或预生成失败时）
        if (AiImageGenerator.getCachedImage(book.bookUrl, chapterIndex, aiImageSceneIndex, startPara, endPara) == null) {
            // 防重入：如果已有生成任务在跑或本方法已启动过实时生成，跳过重复启动
            if (aiImageRealtimeGenerating || AiImageGenerator.isGenerating(book.bookUrl, chapterIndex, aiImageSceneIndex)) {
                AppLog.put("AI生图[DEBUG] displayCurrentAiImage: 已有生成任务在跑, 跳过重复启动 sceneIndex=$aiImageSceneIndex")
            } else {
                AppLog.put("AI生图[DEBUG] displayCurrentAiImage: 缓存为空, 启动实时生成 sceneIndex=$aiImageSceneIndex")
                // 同步设置标志（在 launch 之前），确保第二个调用能检测到
                aiImageRealtimeGenerating = true
                lifecycleScope.launch {
                    try {
                        val content = withContext(Dispatchers.IO) { collectContentForAiImage() }
                        if (!content.isNullOrBlank()) {
                            AiImageGenerator.generateForScene(
                                bookUrl = book.bookUrl,
                                chapterIndex = chapterIndex,
                                sceneIndex = aiImageSceneIndex,
                                mood = "",
                                sourceText = content,
                                bookName = book.name,
                                retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                                contentProvider = { collectContentForAiImage() },
                                cacheCtx = buildPersistentCacheContext(),
                                // 重试时按当前朗读位置重新算段落范围并复查缓存，段落递增后可命中旧图
                                cacheCtxProvider = { buildPersistentCacheContext() }
                            )
                        }
                    } catch (e: Exception) {
                        AppLog.put("AI 生图实时生成失败: ${e.localizedMessage}")
                    } finally {
                        aiImageRealtimeGenerating = false
                    }
                }
            }
        }
        // 3. 预生成下一张（即使跳过实时生成也执行，保证下一张提前准备）
        preGenerateNextAiImage()
    }

    /**
     * 首次开始朗读时触发：立即生成第一张AI图片并预生成下一张
     * 仅在首次调用时执行，后续不再重复
     */
    fun triggerFirstAiImageIfNeeded() {
        if (!AppConfig.readAloudAiImage) {
            AppLog.put("AI生图[DEBUG] triggerFirst: readAloudAiImage=false, 跳过")
            return
        }
        val startPara = nowSpeak
        // 朗读起点变化（手动翻页/跳转）导致的重启。此时阅读页已按「当前屏幕第二个段落号」
        // 定位并推送过图片，本流程不能再按 nowSpeak 定位一次，否则两次锚点不同
        // 会推出另一张图，表现为"闪一下再切回"。
        var restartedByStartParaChange = false
        // 同一章内从中间重新开始朗读：起点段落变了就必须重新触发。
        // 否则 aiImageFirstGenerated 为 true 会直接 return，
        // 画面一直停在上次位置那张图上（即“总是显示前面的图片”）。
        if (aiImageFirstGenerated && aiImageLastStartPara != startPara) {
            AppLog.put("AI生图: 检测到朗读起点变化 $aiImageLastStartPara -> $startPara，重置生图状态重新触发")
            resetAiImageState()
            restartedByStartParaChange = true
        }
        if (aiImageFirstGenerated) {
            AppLog.put("AI生图[DEBUG] triggerFirst: 已首次生成过, 跳过 (sceneIndex=$aiImageSceneIndex, accumulated=$aiImageAccumulatedChars)")
            return
        }
        aiImageFirstGenerated = true
        aiImageSceneIndex = 0
        aiImageAccumulatedChars = 0
        aiImageLastStartPara = startPara
        AppLog.put("AI生图: 首次触发，开始生成第一张图片(sceneIndex=0, startPara=$startPara)")
        displayCurrentAiImage(skipLocateDisplay = restartedByStartParaChange)
    }

    /**
     * 段落推进后检查是否达到字数阈值，触发AI图片切换
     * 在子类 updateNextPos() 的 nowSpeak++ 之后调用
     * @param advancedParagraphLength 刚朗读完的段落字数（用于累计）
     */
    protected fun checkAiImageTrigger(advancedParagraphLength: Int) {
        if (!AppConfig.readAloudAiImage) return
        if (!aiImageFirstGenerated) {
            AppLog.put("AI生图[DEBUG] checkAiImageTrigger: aiImageFirstGenerated=false, 跳过(段落推进+${advancedParagraphLength}字)")
            return
        }
        val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
        aiImageAccumulatedChars += advancedParagraphLength
        if (aiImageAccumulatedChars >= charCount) {
            AppLog.put("AI生图: 累计字数${aiImageAccumulatedChars}≥${charCount}，触发切换")
            aiImageAccumulatedChars = 0
            switchToNextAiImage()
        } else {
            AppLog.put("AI生图[DEBUG] checkAiImageTrigger: 累计${aiImageAccumulatedChars}/${charCount}字 (本次+${advancedParagraphLength})")
        }
    }

    /**
     * 切换到下一张AI图片：显示已预生成的图片 + 预生成再下一张
     */
    private fun switchToNextAiImage() {
        aiImageSceneIndex++
        val book = ReadBook.book ?: run {
            AppLog.put("AI生图[DEBUG] switchToNextAiImage: ReadBook.book=null, 无法切换")
            return
        }
        val chapterIndex = ReadBook.durChapterIndex
        AppLog.put("AI生图: 切换到 sceneIndex=$aiImageSceneIndex, chapterIndex=$chapterIndex")
        // 1. 立即显示已预生成的图片（秒切，无等待）
        AiImageGenerator.notifyImageIfReady(book.bookUrl, chapterIndex, aiImageSceneIndex)
        // 若预生成不存在（上次预生成失败），则实时生成当前场景
        if (AiImageGenerator.getCachedImage(book.bookUrl, chapterIndex, aiImageSceneIndex) == null) {
            AppLog.put("AI生图[DEBUG] switchToNextAiImage: sceneIndex=$aiImageSceneIndex 缓存为空, 实时生成")
            realtimeGenerateForCurrentScene(book, chapterIndex, aiImageSceneIndex)
        } else {
            AppLog.put("AI生图[DEBUG] switchToNextAiImage: sceneIndex=$aiImageSceneIndex 预生成缓存命中, 秒切")
        }
        // 2. 预生成下一张图片
        preGenerateNextAiImage()
    }

    /**
     * 实时生成当前场景图片（预生成缓存未命中时的兜底）。
     * 当检测到当前段落接近章末且字数不足时，进行跨章节处理（生成本章末尾图 + 下章开头图）。
     */
    private fun realtimeGenerateForCurrentScene(
        book: io.legado.app.data.entities.Book,
        chapterIndex: Int,
        sceneIndex: Int,
    ) {
        lifecycleScope.launch {
            try {
                val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
                val endParagraph = calculateEndParagraphByCharCount(charCount)
                val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
                // 跨章节判定：结束段落被钳制到本章最后段，且当前段落不在最后（仍有本章末尾内容可生成）
                if (endParagraph >= realLastIndex && nowSpeak < realLastIndex) {
                    AppLog.put("AI生图: 实时生成检测到跨章节（章末字数不足），分两章生成")
                    preGenerateCrossChapter(book, chapterIndex, sceneIndex, charCount, realLastIndex)
                    return@launch
                }
                val content = withContext(Dispatchers.IO) { collectContentForAiImage() }
                if (!content.isNullOrBlank()) {
                    AiImageGenerator.generateForScene(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapterIndex,
                        sceneIndex = sceneIndex,
                        mood = "",
                        sourceText = content,
                        bookName = book.name,
                        retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                        contentProvider = { collectContentForAiImage() },
                        cacheCtx = buildPersistentCacheContext(),
                        // 重试时按当前朗读位置重新算段落范围并复查缓存，段落递增后可命中旧图
                        cacheCtxProvider = { buildPersistentCacheContext() }
                    )
                }
            } catch (e: Exception) {
                AppLog.put("AI 生图切换时实时生成失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * 预生成下一张AI图片
     * 从当前朗读位置往后推算「分析字数」对应的结束段落，从该段落提取正文预生成
     * 当推算到达本章末尾（字数不足）时，进行跨章节处理：
     *   - 第一张：本章末尾剩余段落（序号沿用本章，保存到本章目录）
     *   - 第二张：下一章开头段落补足剩余字数（序号从下一章0开始，保存到下一章目录）
     */
    private fun preGenerateNextAiImage() {
        if (!AppConfig.readAloudAiImage) return
        val book = ReadBook.book ?: return
        val chapterIndex = ReadBook.durChapterIndex
        val nextSceneIndex = aiImageSceneIndex + 1
        // 已缓存或正在生成则跳过
        if (AiImageGenerator.getCachedImage(book.bookUrl, chapterIndex, nextSceneIndex) != null) return
        lifecycleScope.launch {
            try {
                val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
                val endParagraph = calculateEndParagraphByCharCount(charCount)
                val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
                // 跨章节判定：结束段落被钳制到本章最后段，说明字数不足到达章末
                if (endParagraph >= realLastIndex && nowSpeak < realLastIndex) {
                    AppLog.put("AI生图: 预生成检测到跨章节（章末字数不足），分两章生成")
                    preGenerateCrossChapter(book, chapterIndex, nextSceneIndex, charCount, realLastIndex)
                    return@launch
                }
                AppLog.put("AI生图: 预生成 sceneIndex=$nextSceneIndex, 从段落${endParagraph}提取${charCount}字")
                val content = withContext(Dispatchers.IO) {
                    collectContentFromParagraphWithCharCount(endParagraph, charCount)
                }
                if (!content.isNullOrBlank()) {
                    AiImageGenerator.preGenerateForScene(
                        bookUrl = book.bookUrl,
                        chapterIndex = chapterIndex,
                        sceneIndex = nextSceneIndex,
                        mood = "",
                        sourceText = content,
                        bookName = book.name,
                        retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                        contentProvider = { collectContentForAiImage() },
                        cacheCtx = buildPersistentCacheContext(startPara = endParagraph),
                        // 预生成重试：按「当前图片段落的下一个段」重算范围并复查缓存，段落递增可命中旧图
                        cacheCtxProvider = { buildPersistentCacheContext(startPara = calculateEndParagraphByCharCount(charCount)) }
                    )
                }
            } catch (e: Exception) {
                AppLog.put("AI生图预生成失败: ${e.localizedMessage}")
            }
        }
    }

    /**
     * 跨章节预生成两张图片：
     * - 第一张：本章末尾剩余段落（startPara=当前段落, endPara=本章最后段），保存到本章目录
     * - 第二张：下一章开头段落补足剩余字数（startPara=0, endPara=补足位置），保存到下一章目录，序号从0开始
     */
    private suspend fun preGenerateCrossChapter(
        book: io.legado.app.data.entities.Book,
        chapterIndex: Int,
        sceneIndex: Int,
        charCount: Int,
        realLastIndex: Int,
    ) {
        // 第一张：本章末尾剩余段落
        val curEndPara = realLastIndex
        val curContent = withContext(Dispatchers.IO) {
            collectContentFromParagraphWithCharCount(nowSpeak, charCount)
        }
        if (!curContent.isNullOrBlank()) {
            AppLog.put("AI生图: 跨章节-本章末尾图 sceneIndex=$sceneIndex, 段落[$nowSpeak,$curEndPara]")
            AiImageGenerator.preGenerateForScene(
                bookUrl = book.bookUrl,
                chapterIndex = chapterIndex,
                sceneIndex = sceneIndex,
                mood = "",
                sourceText = curContent,
                bookName = book.name,
                retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                contentProvider = { collectContentForAiImage() },
                cacheCtx = buildPersistentCacheContext(startPara = nowSpeak),
                // 跨章节-本章末尾图：重试时按当前 nowSpeak 重算范围并复查缓存
                cacheCtxProvider = { buildPersistentCacheContext(startPara = nowSpeak) }
            )
        }
        // 第二张：下一章开头段落补足剩余字数
        val nextChapter = ReadBook.textChapter(1)
        if (nextChapter != null) {
            val nextChapterIndex = chapterIndex + 1
            val nextParaList = nextChapter.getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n").filter { it.isNotEmpty() }.toMutableList()
            if (nextParaList.isNotEmpty()) {
                val nextCharCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
                val nextEndPara = calculateEndParagraphInList(nextParaList, nextCharCount, 0)
                val nextContent = withContext(Dispatchers.IO) {
                    collectContentFromList(nextParaList, 0, nextCharCount)
                }
                if (!nextContent.isNullOrBlank()) {
                    AppLog.put("AI生图: 跨章节-下章开头图 sceneIndex=0(下章), 段落[0,$nextEndPara]")
                    val nextChapterName = nextChapter.title
                    AiImageGenerator.preGenerateForScene(
                        bookUrl = book.bookUrl,
                        chapterIndex = nextChapterIndex,
                        sceneIndex = 0,
                        mood = "",
                        sourceText = nextContent,
                        bookName = book.name,
                        retryCount = AppConfig.aiImageRetryCount.coerceAtLeast(0),
                        contentProvider = { collectContentForAiImage() },
                        cacheCtx = AiImageGenerator.PersistentCacheContext(
                            bookUrl = book.bookUrl,
                            bookName = book.name,
                            chapterIndex = nextChapterIndex,
                            chapterName = nextChapterName,
                            startPara = 0,
                            endPara = nextEndPara,
                            charCount = nextCharCount,
                        ),
                        // 跨章节-下章开头图：重试时复查下一章开头区间（startPara=0 固定，按当前章状态重算）
                        cacheCtxProvider = {
                            AiImageGenerator.PersistentCacheContext(
                                bookUrl = book.bookUrl,
                                bookName = book.name,
                                chapterIndex = nextChapterIndex,
                                chapterName = nextChapterName,
                                startPara = 0,
                                endPara = calculateEndParagraphInList(nextParaList, nextCharCount, 0),
                                charCount = nextCharCount,
                            )
                        }
                    )
                }
            }
        }
    }

    /**
     * 根据字数计算从指定段落起，累计charCount字后会到达的结束段落
     * @param fromIndex 起始段落，默认为当前朗读位置 nowSpeak
     */
    private fun calculateEndParagraphByCharCount(charCount: Int, fromIndex: Int = nowSpeak): Int {
        var accumulated = 0
        val realLastIndex = (contentList.lastIndex - 1).coerceAtLeast(0)
        for (i in fromIndex until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            accumulated += paragraph.length + 1
            if (accumulated >= charCount) {
                return i.coerceAtMost(realLastIndex)
            }
        }
        return realLastIndex
    }

    /**
     * 从当前段落开始收集AI生图用的正文内容
     * 提取字数由 aiImageCharCount 决定（默认200字）
     */
    /**
     * 收集发给 AI 生图的小说内容，组织为「上文 / 正文 / 后续」三段。
     * - 正文：从当前朗读段落 [nowSpeak] 起累计 [aiImageCharCount] 字；
     * - 上文：正文之前累计 [aiImageContextCharCount] 字（当前章内向前取）；
     * - 后续：正文结束段之后累计 [aiImageContextCharCount] 字。
     * 三段均为纯正文（书籍信息由提示词模板 {book} 提供），便于角色标注与上下文衔接。
     */
    internal fun collectContentForAiImage(): String? {
        val centerChars = AppConfig.aiImageCharCount.coerceIn(50, 5000)
        val contextChars = AppConfig.aiImageContextCharCount
        val start = nowSpeak.coerceAtLeast(0)

        // 正文：从 start 起累计 centerChars 字
        val bodyResult = collectPlainText(start, centerChars)
        val bodyText = bodyResult.text
        val bodyEndIndex = bodyResult.endIndex

        // 上文：从 start 之前向前累计 contextChars 字
        val preText = if (contextChars > 0) collectBackwardText(start - 1, contextChars) else ""

        // 后续：从正文结束段之后继续累计 contextChars 字
        val postText = if (contextChars > 0) {
            collectPlainText(bodyEndIndex + 1, contextChars).text
        } else ""

        val sb = StringBuilder()
        sb.append("【上文】\n")
        sb.append(preText.ifBlank { "（无）" })
        sb.append("\n\n【正文内容】\n")
        sb.append(bodyText.ifBlank { "（无）" })
        sb.append("\n\n【后续】\n")
        sb.append(postText.ifBlank { "（无）" })

        return sb.toString().takeIf { it.isNotBlank() }
    }

    /**
     * 从指定段落向前（索引递减）累计 targetChars 字，返回拼接的正文。
     * 仅取当前章 [contentList] 中 startBeforeIndex 之前的段落。
     */
    private fun collectBackwardText(startBeforeIndex: Int, targetChars: Int): String {
        if (startBeforeIndex < 0) return ""
        val sb = StringBuilder()
        var count = 0
        for (i in startBeforeIndex downTo 0) {
            val paragraph = contentList.getOrNull(i) ?: break
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            sb.insert(0, paragraph + "\n")
            count += paragraph.length + 1
            if (count >= targetChars) break
        }
        return sb.toString().trim()
    }

    /**
     * 从 startIndex 起收集纯正文（不含书籍信息头），累计 targetChars 字。
     * 返回正文文本与结束段落索引（用于推算后续起点）。
     */
    private fun collectPlainText(startIndex: Int, targetChars: Int): PlainTextResult {
        val sb = StringBuilder()
        var count = 0
        var endIndex = startIndex.coerceAtLeast(0)
        for (i in startIndex until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            sb.append(paragraph).append("\n")
            count += paragraph.length + 1
            endIndex = i
            if (count >= targetChars) break
        }
        return PlainTextResult(sb.toString().trim(), endIndex)
    }

    private data class PlainTextResult(val text: String, val endIndex: Int)


    /**
     * 从指定段落开始收集正文，提取指定字数
     */
    private fun collectContentFromParagraphWithCharCount(startIndex: Int, targetChars: Int): String? {
        val maxCharCount = 5000
        val sb = StringBuilder()
        var currentCharCount = 0

        val book = ReadBook.book
        if (book != null) {
            sb.append("【书籍信息】\n")
            sb.append("书名：${book.name}\n")
            if (book.author.isNotEmpty()) sb.append("作者：${book.author}\n")
            if (!book.kind.isNullOrEmpty()) sb.append("分类：${book.kind}\n")
            if (!book.customTag.isNullOrEmpty()) sb.append("自定义分类：${book.customTag}\n")
            sb.append("【正文内容】\n\n")
            currentCharCount = sb.length
        }

        for (i in startIndex until contentList.size) {
            val paragraph = contentList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) sb.append(paragraph.substring(0, remainingChars))
                break
            }
            sb.append(paragraph)
            sb.append("\n")
            currentCharCount += paragraph.length + 1
            if (currentCharCount >= targetChars) break
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 在指定段落列表上，从 fromIndex 起累计 charCount 字后到达的结束段落索引。
     * 用于跨章节时在下一章的段列表上推算结束段落。
     */
    private fun calculateEndParagraphInList(
        paraList: List<String>,
        charCount: Int,
        fromIndex: Int = 0,
    ): Int {
        var accumulated = 0
        val realLastIndex = (paraList.lastIndex).coerceAtLeast(0)
        for (i in fromIndex until paraList.size) {
            val paragraph = paraList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            accumulated += paragraph.length + 1
            if (accumulated >= charCount) {
                return i.coerceAtMost(realLastIndex)
            }
        }
        return realLastIndex
    }

    /**
     * 在指定段落列表上收集正文，从 startIndex 起提取 targetChars 字。
     * 用于跨章节时从下一章段列表收集开头正文。
     */
    private fun collectContentFromList(
        paraList: List<String>,
        startIndex: Int,
        targetChars: Int,
    ): String? {
        val maxCharCount = 5000
        val sb = StringBuilder()
        var currentCharCount = 0
        val book = ReadBook.book
        if (book != null) {
            sb.append("【书籍信息】\n")
            sb.append("书名：${book.name}\n")
            if (book.author.isNotEmpty()) sb.append("作者：${book.author}\n")
            if (!book.kind.isNullOrEmpty()) sb.append("分类：${book.kind}\n")
            if (!book.customTag.isNullOrEmpty()) sb.append("自定义分类：${book.customTag}\n")
            sb.append("【正文内容】\n\n")
            currentCharCount = sb.length
        }
        for (i in startIndex until paraList.size) {
            val paragraph = paraList[i]
            if (paragraph.isNullOrBlank() || paragraph.matches(AppPattern.notReadAloudRegex)) continue
            if (currentCharCount + paragraph.length > maxCharCount) {
                val remainingChars = maxCharCount - currentCharCount
                if (remainingChars > 0) sb.append(paragraph.substring(0, remainingChars))
                break
            }
            sb.append(paragraph)
            sb.append("\n")
            currentCharCount += paragraph.length + 1
            if (currentCharCount >= targetChars) break
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 构建持久缓存上下文：携带当前生图覆盖的段落区间与章节信息，供持久缓存命中查询与落盘。
     * @param startPara 本次生图起始段落，默认为当前朗读位置 nowSpeak
     */
    private fun buildPersistentCacheContext(startPara: Int = nowSpeak): AiImageGenerator.PersistentCacheContext? {
        val book = ReadBook.book ?: return null
        val charCount = AppConfig.aiImageCharCount.coerceIn(50, 5000)
        val chapterIndex = ReadBook.durChapterIndex
        val chapterName = textChapter?.title ?: textChapter?.chapter?.title ?: ""
        // 始终以传入的 startPara（当前朗读位置 / 下一段目标段）计算区间，
        // 每次生图前都先按当前段落查命中，命中复用旧图，未命中才调 API。
        val start = startPara
        val endPara = calculateEndParagraphByCharCount(charCount, fromIndex = start)
        return AiImageGenerator.PersistentCacheContext(
            bookUrl = book.bookUrl,
            bookName = book.name,
            chapterIndex = chapterIndex,
            chapterName = chapterName,
            startPara = start,
            endPara = endPara,
            charCount = charCount,
        )
    }

    /**
     * 切换章节时重置AI生图状态
     */
    fun resetAiImageState() {
        aiImageSceneIndex = 0
        aiImageAccumulatedChars = 0
        aiImageFirstGenerated = false
        aiImageRealtimeGenerating = false
        aiImageLastStartPara = -1
        // 场景缓存以 sceneIndex 为键，而 sceneIndex 是相对朗读起点的序号。
        // 起点变化（同章手动翻页）后旧缓存的语义已经错位，必须整体失效，
        // 否则自动切换会取到按旧起点生成的图（翻页后却切到开始朗读那张的下一张）。
        ReadBook.book?.let { book ->
            val chapterIndex = ReadBook.durChapterIndex
            AiImageGenerator.clearChapterSceneCache(book.bookUrl, chapterIndex)
            // 清理持久缓存游标，使新章节从头按序轮换复用旧图
            AiImagePersistentCache.resetChapterCursor(book.bookUrl, chapterIndex)
        }
    }

    private fun setTimer(minute: Int) {
        timeMinute = minute
        doDs()
    }

    private fun addTimer() {
        if (timeMinute >= 180 || timeMinute < 0) {
            timeMinute = 0
        } else {
            timeMinute += 10
            if (timeMinute > 180) timeMinute = 180
        }
        doDs()
    }

    /**
     * 定时
     * 只在 timeMinute > 0（真的设了定时）时才启动倒计时；
     * 归零即停止朗读，不再让 timeMinute 掉到负数卡死。
     */
    @Synchronized
    private fun doDs() {
        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
        upReadAloudNotification()
        dsJob?.cancel()
        dsJob = null
        if (timeMinute <= 0) {
            // 未设置定时（或已归零）：不跑倒计时
            return
        }
        dsJob = lifecycleScope.launch {
            while (isActive) {
                delay(60000)
                if (!pause) {
                    timeMinute--
                    if (timeMinute <= 0) {
                        timeMinute = 0
                        ReadAloud.stop(this@BaseReadAloudService)
                        postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                        break
                    }
                }
                postEvent(EventBus.READ_ALOUD_DS, timeMinute)
                upReadAloudNotification()
            }
        }
    }

    /**
     * 请求音频焦点
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        if (AppConfig.ignoreAudioFocus) {
            return true
        }
        val requestFocus = MediaHelp.requestFocus(mFocusRequest)
        if (!requestFocus) {
            pauseReadAloud(false)
            toastOnUi("未获取到音频焦点")
        }
        return requestFocus
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, mFocusRequest)
    }

    /**
     * 更新媒体状态
     */
    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                // 为系统媒体控件添加定时按钮
//                .addCustomAction(
//                    PlaybackStateCompat.CustomAction.Builder(
//                        "ACTION_ADD_TIMER",
//                        getString(R.string.set_timer),
//                        R.drawable.ic_time_add_24dp
//                    ).build()
//                )
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    @SuppressLint("UnspecifiedImmutableFlag")
    private fun initMediaSession() {
        if (getPrefBoolean("systemMediaControlCompatibilityChange")) {
            mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumeReadAloud()
                }

                override fun onPause() {
                    pauseReadAloud()
                }

                override fun onSkipToNext() {
                    if (getPrefBoolean("mediaButtonPerNext", false)) {
                        nextChapter()
                    } else {
                        nextP()
                    }
                }

                override fun onSkipToPrevious() {
                    if (getPrefBoolean("mediaButtonPerNext", false)) {
                        prevChapter()
                    } else {
                        prevP()
                    }
                }

                override fun onStop() {
                    stopSelf()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    if (action == "ACTION_ADD_TIMER") addTimer()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return MediaButtonReceiver.handleIntent(
                        this@BaseReadAloudService, mediaButtonEvent
                    )
                }
            })
        } else {
            mediaSessionCompat.setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                    return MediaButtonReceiver.handleIntent(
                        this@BaseReadAloudService, mediaButtonEvent
                    )
                }
            })
        }
    }

    /**
     * 注册多媒体按钮监听
     */
    private fun initBroadcastReceiver() {
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 音频焦点变化
     */
    override fun onAudioFocusChange(focusChange: Int) {
        if (AppConfig.ignoreAudioFocus) {
            AppLog.put("忽略音频焦点处理(TTS)")
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (needResumeOnAudioFocusGain) {
                    AppLog.put("音频焦点获得,继续朗读")
                    resumeReadAloud()
                } else {
                    AppLog.put("音频焦点获得")
                }
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                AppLog.put("音频焦点丢失,暂停朗读")
                pauseReadAloud()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                AppLog.put("音频焦点暂时丢失并会很快再次获得,暂停朗读")
                if (!pause) {
                    needResumeOnAudioFocusGain = true
                    pauseReadAloud(false)
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
                AppLog.put("音频焦点短暂丢失,不做处理")
            }
        }
    }

    private fun upReadAloudNotification() {
        upNotificationJob = execute {
            try {
                val notification = createNotification()
                notificationManager.notify(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
            }
        }
    }

    private fun upFloatWindow() {
        if (getPrefBoolean(PreferKey.readAloudFloatWindow, true) && isPlay()) {
            ReadAloudFloatService.updateVisibility(this)
        }
    }

    private fun choiceMediaStyle(): androidx.media.app.NotificationCompat.MediaStyle {
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(1, 2, 4)
        if (getPrefBoolean("systemMediaControlCompatibilityChange")) {
            //fix #4090 android 14 can not show play control in lock screen
            mediaStyle.setMediaSession(mediaSessionCompat.sessionToken)
        }
        return mediaStyle
    }

    private fun createNotification(): NotificationCompat.Builder {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            timeMinute > 0 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )

            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": ${ReadBook.book?.name}"
        var nSubtitle = ReadBook.curTextChapter?.title
        if (nSubtitle.isNullOrBlank())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat
            .Builder(this, AppConst.channelIdReadAloud)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setSubText(getString(R.string.read_aloud))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(
                activityPendingIntent<ReadBookActivity>("activity")
            )
            .setVibrate(null)
            .setSound(null)
            .setLights(0, 0, 0)
        builder.setLargeIcon(cover)
        // 按钮定义：上一章、播放、停止、下一章、定时
        builder.addAction(
            R.drawable.ic_skip_previous,
            getString(R.string.previous_chapter),
            aloudServicePendingIntent(IntentAction.prev)
        )
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                aloudServicePendingIntent(IntentAction.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                aloudServicePendingIntent(IntentAction.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            aloudServicePendingIntent(IntentAction.stop)
        )
        builder.addAction(
            R.drawable.ic_skip_next,
            getString(R.string.next_chapter),
            aloudServicePendingIntent(IntentAction.next)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            aloudServicePendingIntent(IntentAction.addTimer)
        )
        builder.setStyle(choiceMediaStyle())
        return builder
    }

    /**
     * 更新通知
     */
    override fun startForegroundNotification() {
        execute {
            try {
                val notification = createNotification()
                startForeground(NotificationId.ReadAloudService, notification.build())
            } catch (e: Exception) {
                AppLog.put("创建朗读通知出错,${e.localizedMessage}", e, true)
                //创建通知出错不结束服务就会崩溃,服务必须绑定通知
                stopSelf()
            }
        }
    }

    abstract fun aloudServicePendingIntent(actionStr: String): PendingIntent?

    open fun prevChapter() {
        toLast = false
        // 切换章节时重置AI生图状态
        resetAiImageState()
        resumeReadAloudInternal()
        ReadBook.moveToPrevChapter(true, toLast = false)
    }

    open fun nextChapter() {
        ReadBook.upReadTime()
        AppLog.putDebug("${ReadBook.curTextChapter?.chapter?.title} 朗读结束跳转下一章并朗读")
        isAutoSwitchingChapter = true
        // 切换章节时重置AI生图状态，新章节从头开始计数
        resetAiImageState()
        if (!ReadBook.moveToNextChapter(true)) {
            stopSelf()
            return
        }
        // 进入新章节：从脚本章节缓存提取角色，触发 AI 角色档案的建档/增量更新（全容错）
        runCatching {
            ReadBook.book?.let { book ->
                io.legado.app.model.AiProfileService.onChapterStarted(
                    book.name, ReadBook.durChapterIndex,
                    ReadBook.curTextChapter?.chapter?.title.orEmpty()
                )
            }
        }
        resumeReadAloudInternal()
    }

    private fun initPhoneStateListener() {
        val needRegister = AppConfig.ignoreAudioFocus && AppConfig.pauseReadAloudWhilePhoneCalls
        if (needRegister && registeredPhoneStateListener) {
            return
        }
        if (needRegister) {
            registerPhoneStateListener(phoneStateListener)
        } else {
            unregisterPhoneStateListener(phoneStateListener)
        }
    }

    private fun unregisterPhoneStateListener(l: PhoneStateListener) {
        if (registeredPhoneStateListener) {
            withReadPhoneStatePermission {
                telephonyManager.listen(l, PhoneStateListener.LISTEN_NONE)
                registeredPhoneStateListener = false
            }
        }
    }

    private fun registerPhoneStateListener(l: PhoneStateListener) {
        withReadPhoneStatePermission {
            telephonyManager.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            registeredPhoneStateListener = true
        }
    }

    private fun withReadPhoneStatePermission(block: () -> Unit) {
        try {
            block.invoke()
        } catch (_: SecurityException) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.READ_PHONE_STATE)
                .rationale(R.string.read_aloud_read_phone_state_permission_rationale)
                .onGranted {
                    try {
                        block.invoke()
                    } catch (_: SecurityException) {
                        LogUtils.d(TAG, "Grant read phone state permission fail.")
                    }
                }
                .request()
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    inner class ReadAloudPhoneStateListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            super.onCallStateChanged(state, phoneNumber)
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    if (needResumeOnCallStateIdle) {
                        AppLog.put("来电结束,继续朗读")
                        resumeReadAloud()
                    } else {
                        AppLog.put("来电结束")
                    }
                }

                TelephonyManager.CALL_STATE_RINGING -> {
                    if (!pause) {
                        AppLog.put("来电响铃,暂停朗读")
                        needResumeOnCallStateIdle = true
                        pauseReadAloud()
                    } else {
                        AppLog.put("来电响铃")
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    AppLog.put("来电接听,不做处理")
                }
            }
        }
    }

}
