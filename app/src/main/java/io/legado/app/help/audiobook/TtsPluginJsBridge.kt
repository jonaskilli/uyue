package io.legado.app.help.audiobook

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import io.legado.app.constant.AppLog
import org.json.JSONObject
import splitties.init.appCtx
import java.util.UUID

/**
 * JS 桥接对象，注入到网络 TTS 的 JS 执行环境中。
 *
 * 在网络 TTS 的 JS 代码中可通过 `tts.synthesize(tag, text)` 调用 JReadVoiceEngine
 * 按标签查找配置并合成音频，返回 ByteArray。
 *
 * 示例 JS 用法：
 * ```js
 * // 用 "旁白" 标签对应的配置合成音频
 * var audio = tts.synthesize("旁白", speakText);
 * // 用 "男性青年/通用01" 标签合成
 * var audio2 = tts.synthesize("男性青年/通用01", speakText);
 * // 列出所有可用标签
 * var tags = tts.listTags();
 * ```
 */
@Keep
class TtsPluginJsBridge {

    companion object {
        private const val TAG = "TtsPluginJsBridge"

        @Volatile
        @JvmStatic
        var synthesizeCalled: Boolean = false
            private set

        @Volatile
        @JvmStatic
        var lastVoiceTag: String = ""
            private set

        /**
         * 本次合成实际使用到的配置 id。
         * 后处理参数必须按这条配置解析：配置表里存在大量同名标签（内置音色池 400+ 条），
         * 只按 voiceTag 反查会取到别的配置，导致条目/分组/插件级后处理参数失效。
         */
        @Volatile
        @JvmStatic
        var lastConfigId: String = ""
            private set

        /**
         * 本次朗读的音频是否已在 synthesize 返回前按各角色自身的后处理参数调节完毕。
         * 为 true 时合并阶段不再做统一调速（各角色参数不同，不能用一套参数调）。
         *
         * 注意这是**整段朗读级别的粗粒度开关**，只用于无法分段的场景
         * （如 Single/InputStream）。能拿到分段的场景请用 [buildPerSegmentParams]，
         * 它逐段判断、按角色对齐，不会出现「旁白成功把开关置位、对话失败却拿不到参数」。
         */
        @Volatile
        @JvmStatic
        var synthesisPostProcessed: Boolean = false
            private set

        /**
         * 一次 synthesize 调用的记录。
         * 用于合并阶段把后处理参数对齐回正确的分段：脚本是「合成一次 → write 一次」，
         * 所以分段顺序与这里的记录顺序一致，再用字节数二次校验即可放心对应。
         */
        class SynthesisRecord(
            /** 本次合成使用的标签（旁白 / 男性青年…）；音效段为「音效:<文件名>」 */
            val voiceTag: String,
            /** 本次合成实际使用的配置 id */
            val configId: String,
            /** 该角色解析出来的后处理参数 */
            val params: PostAudioParams,
            /** 是否已成功转成标准 WAV 并应用了 [params]；false 表示合并阶段需补做 */
            val converted: Boolean,
            /** 返回给脚本的字节数，用于与分段长度比对 */
            val byteSize: Int,
            /**
             * 是否为音效段（由 [markSoundEffect] 标记，不走 TTS 合成）。
             * 音效没有角色参数，音量单独跟随背景音乐界面的「音效音量」滑块。
             */
            val isSoundEffect: Boolean = false,
        )

        /**
         * 合并阶段某一集的音频处理配置。
         */
        class SegmentAudioConfig(
            /** 后处理参数；null 表示该段已处理过或不需要（如音效段） */
            val params: PostAudioParams?,
            /** 附加音量增益；音效段取「音效音量」滑块，其余为 1.0f */
            val gain: Float,
        )

        /** 音效音量倍率（背景音乐界面滑块，0~300%） */
        private fun soundEffectGain(): Float =
            (io.legado.app.help.config.AppConfig.soundEffectVolume / 100f)
                .coerceIn(0f, PostAudioParams.MAX)

        private val synthesisRecords = mutableListOf<SynthesisRecord>()

        /** 本次朗读至今的合成记录（按调用顺序） */
        @JvmStatic
        val records: List<SynthesisRecord>
            get() = synchronized(synthesisRecords) { synthesisRecords.toList() }

        private fun addRecord(record: SynthesisRecord) {
            synchronized(synthesisRecords) { synthesisRecords.add(record) }
        }

        /**
         * 脚本在把音效字节 write 进分段缓冲区**之后**调用，声明这一段是音效。
         *
         * 音效不经过 TTS 合成、没有角色参数，若不做标记，合并阶段既拿不到它的
         * 后处理参数、也不会应用「音效音量」滑块——表现为音效忽大忽小、
         * 调了音量没反应。传入同一份字节以便与分段长度校验对齐。
         *
         * @param fileName 音效 JSON 文件名，仅用于日志排查
         * @param bytes    刚刚 write 进分段缓冲区的音效字节（必须是同一份）
         */
        @JvmStatic
        fun markSoundEffect(fileName: String, bytes: ByteArray) {
            if (bytes.isEmpty()) return
            addRecord(
                SynthesisRecord(
                    voiceTag = "音效:$fileName",
                    configId = "",
                    // 音效不用角色后处理参数，音量由 gain 单独给
                    params = PostAudioParams(),
                    converted = false,
                    byteSize = bytes.size,
                    isSoundEffect = true,
                )
            )
        }

        /**
         * 按「索引 + 字节长度」双重校验，把每次合成的音频参数对齐到对应分段。
         *
         * @param segments 分段缓冲区切出来的音频段
         * @param ranges   每段的 (起始偏移, 长度)
         * @return 与 [segments] 等长的参数列表，元素为 null 表示该段已处理过、无需补；
         *         数量或长度对不上（脚本改过音频、或不是分段缓冲区路径）时返回 null，
         *         调用方应退回整体参数，**绝不猜测对应关系**
         */
        @JvmStatic
        fun buildPerSegmentParams(
            segments: List<ByteArray>,
            ranges: List<Pair<Int, Int>>,
        ): List<PostAudioParams?>? =
            buildPerSegmentAudioConfig(segments, ranges)?.map { it.params }

        /**
         * 同 [buildPerSegmentParams]，但同时给出每段的附加音量增益。
         * 用于音效段：它不该套角色的语速/音调，但要按「音效音量」滑块调整音量。
         */
        @JvmStatic
        fun buildPerSegmentAudioConfig(
            segments: List<ByteArray>,
            ranges: List<Pair<Int, Int>>,
        ): List<SegmentAudioConfig>? {
            val recs = records
            if (recs.isEmpty() || segments.size <= 1) return null
            if (recs.size != segments.size || ranges.size != segments.size) return null
            recs.forEachIndexed { i, r ->
                // 长度必须完全一致：脚本若对音频做过裁剪/再编码，对应关系即不可信
                if (r.byteSize != segments[i].size) return null
                if (ranges[i].second != segments[i].size) return null
            }
            val sfxGain = soundEffectGain()
            return recs.map { r ->
                if (r.isSoundEffect) {
                    // 音效：不套角色参数，只按音效音量调增益
                    SegmentAudioConfig(params = null, gain = sfxGain)
                } else {
                    // 已转码成功的段：参数在合成时已应用，只补增益（默认 1.0）
                    SegmentAudioConfig(
                        params = if (r.converted) null else r.params,
                        gain = 1.0f,
                    )
                }
            }
        }

        /**
         * 没有任何一段需要补参数时返回 true（用于 Single 这类无法分段的场景）。
         */
        @JvmStatic
        fun allSegmentsConverted(): Boolean =
            records.isNotEmpty() && records.all { it.converted }

        @JvmStatic
        fun resetSynthesizeFlag() {
            synthesizeCalled = false
            lastVoiceTag = ""
            lastConfigId = ""
            synthesisPostProcessed = false
            synchronized(synthesisRecords) { synthesisRecords.clear() }
        }

        /** 可朗读字符：任意语言文字 + 十进制数字。不含任何此类字符的切片送进引擎必然报"不支持文本" */
        private val SPEAKABLE_REGEX = Regex("[\\p{L}\\p{Nd}]")

        /** 剥离 HTML 标签/实体，避免 "<img>" 之类标签里的字母被误判为可朗读内容 */
        private val STRIP_REGEX = Regex("<[a-zA-Z][^>]*>|&[a-zA-Z#0-9]+;")

        /**
         * 纯符号检测：不含中文/英文/数字（\p{L} 或 \p{Nd}）即为纯符号切片。
         * 用于在脚本角色切分后的最后关口拦截，返回 0.2 秒静音替代合成，
         * 避免引擎报"参数错误，不支持的发音人 / 文本"后触发 10 次重试（累计卡顿约 36 秒）。
         */
        fun isSymbolOnly(text: String): Boolean =
            !SPEAKABLE_REGEX.containsMatchIn(STRIP_REGEX.replace(text, ""))

        /**
         * 0.2 秒静音，WAV 格式（带 RIFF 头）。
         * 必须是合法音频文件而非裸 PCM：脚本会把静音与其他角色片段（MP3/WAV）拼接，
         * 裸 PCM 无格式头，splitMixedAudioBytes 无法识别、解码器无法处理 → ExoPlayer 播放错误。
         */
        fun silentPcm02s(): ByteArray =
            io.legado.app.service.BaseReadAloudService.generateSilentWavBytes(200)
    }

    private val context: Context = appCtx

    /**
     * 按标签合成音频。
     *
     * @param voiceTag 语音标签，如 "旁白"、"括号1"、"男性青年/通用01"
     * @param text 要合成的文本
     * @return 音频字节数组 (PCM 24000Hz)，失败返回 null
     */
    fun synthesize(voiceTag: String, text: String): ByteArray? {
        synthesizeCalled = true
        lastVoiceTag = voiceTag
        if (voiceTag.isBlank() || text.isBlank()) {
            AppLog.putDebug("[TtsPluginJsBridge] synthesize 参数为空: tag=$voiceTag textLen=${text.length}")
            return null
        }
        // 纯符号切片前置拦截：非中文/英文/数字直接返回 0.2 秒静音，不进引擎
        // （引擎对纯符号报"参数错误，不支持的文本"并触发 10 次重试，累计卡顿 36 秒+）
        if (isSymbolOnly(text)) {
            AppLog.putDebug("[TtsPluginJsBridge] 纯符号切片，返回0.2秒静音: tag=$voiceTag textLen=${text.length}")
            synthesisPostProcessed = true
            // 静音段脚本同样会 write，必须记一条，否则 records 比 segments 少，
            // 逐段对齐会整体失效（静音已是标准 WAV、无需参数，converted=true）
            addRecord(
                SynthesisRecord(
                    voiceTag = voiceTag,
                    configId = lastConfigId,
                    params = PostAudioParams(),
                    converted = true,
                    byteSize = silentPcm02s().size,
                )
            )
            return silentPcm02s()
        }
        val requestId = UUID.randomUUID().toString()
        val pointerJson = JSONObject().put("voiceTag", voiceTag).toString()
        // 预先按合成口径选定配置，后处理用同一条，避免按标签反查命中同名配置
        val usedConfig = JReadVoiceEngine.findPlayableConfig(context, voiceTag)
        lastConfigId = usedConfig?.id.orEmpty()
        return try {
            val bytes = JReadVoiceEngine.synthesizeLineAudio(context, text, pointerJson, requestId)
            val outcome = applyPostAudioImmediately(bytes, usedConfig, voiceTag)
            // 只有真的返回了字节才记录：脚本是 `if (audio && audio.length > 0)` 才 write，
            // 返回空时若也记一条，records 就会比 segments 多，导致逐段对齐整体失效。
            if (outcome.bytes != null && outcome.bytes.isNotEmpty()) {
                addRecord(
                    SynthesisRecord(
                        voiceTag = voiceTag,
                        configId = lastConfigId,
                        params = outcome.params,
                        converted = outcome.converted,
                        byteSize = outcome.bytes.size,
                    )
                )
            }
            outcome.bytes
        } catch (e: Exception) {
            AppLog.put("[TtsPluginJsBridge] synthesize 失败: tag=$voiceTag error=${e.message}", e)
            Log.e(TAG, "synthesize failed: tag=$voiceTag", e)
            null
        }
    }

    /**
     * 使用指定配置 ID 合成音频。
     *
     * @param configId 配置 ID
     * @param text 要合成的文本
     * @return 音频字节数组，失败返回 null
     */
    fun synthesizeByConfigId(configId: String, text: String): ByteArray? {
        synthesizeCalled = true
        if (configId.isBlank() || text.isBlank()) return null
        val config = JReadVoiceEngine.listConfigs(context).firstOrNull { it.id == configId }
            ?: run {
                AppLog.putDebug("[TtsPluginJsBridge] 未找到配置: configId=$configId")
                return null
            }
        lastVoiceTag = config.voiceTag
        lastConfigId = config.id
        // 纯符号切片前置拦截：非中文/英文/数字直接返回 0.2 秒静音，不进引擎
        if (isSymbolOnly(text)) {
            AppLog.putDebug("[TtsPluginJsBridge] 纯符号切片，返回0.2秒静音: configId=$configId textLen=${text.length}")
            synthesisPostProcessed = true
            // 同 synthesize：静音段也要记录，保持 records 与 segments 一一对应
            addRecord(
                SynthesisRecord(
                    voiceTag = config.voiceTag,
                    configId = config.id,
                    params = PostAudioParams(),
                    converted = true,
                    byteSize = silentPcm02s().size,
                )
            )
            return silentPcm02s()
        }
        val requestId = UUID.randomUUID().toString()
        val pointerJson = JSONObject().put("voiceTag", config.voiceTag).toString()
        return try {
            val bytes = JReadVoiceEngine.synthesizeConfigLineAudio(context, config, text, pointerJson, requestId)
            val outcome = applyPostAudioImmediately(bytes, config, config.voiceTag)
            // 只有真的返回了字节才记录（脚本按 length > 0 决定是否 write）
            if (outcome.bytes != null && outcome.bytes.isNotEmpty()) {
                addRecord(
                    SynthesisRecord(
                        voiceTag = config.voiceTag,
                        configId = config.id,
                        params = outcome.params,
                        converted = outcome.converted,
                        byteSize = outcome.bytes.size,
                    )
                )
            }
            outcome.bytes
        } catch (e: Exception) {
            AppLog.put("[TtsPluginJsBridge] synthesizeByConfigId 失败: configId=$configId error=${e.message}", e)
            Log.e(TAG, "synthesizeByConfigId failed: configId=$configId", e)
            null
        }
    }

    /** 合成后处理的产物：[bytes] 交给脚本，[converted] 表示是否已转码并应用了 [params] */
    private class PostAudioOutcome(
        val bytes: ByteArray?,
        val converted: Boolean,
        val params: PostAudioParams,
    )

    /**
     * 合成完毕后立即做两件事（转码优先，是硬要求）：
     * 1. **统一转码**：不管插件返回什么格式（MP3/AAC/参数不一的 WAV），一律
     *    解码 → 重采样 24000Hz/16bit/单声道 → 封装标准 WAV。
     *    原因：引擎/脚本会把各角色片段直接字节拼接成一个块返回，格式不一致时
     *    （如旁白 WAV + 对话 MP3），按内容嗅探拆分与 WAV 头声明的 data 长度
     *    都会把后续段截断，表现为「只读旁白不读对话」；源头统一成同参数 WAV 后，
     *    拼接块是纯 WAV 序列，每段自带头且声明长度精确，拆分/落盘/播放再无格式问题。
     * 2. **后处理调速**：按该角色自身参数（分组/全局链路 × 插件后参数 × 朗读界面
     *    语速滑块）一次乘齐，与转码同趟完成（Sonic 只能在 PCM 域工作），
     *    让引擎/脚本后续拿到的就是成品，合并阶段只做纯拼接。
     */
    private fun applyPostAudioImmediately(
        bytes: ByteArray?,
        config: JReadVoiceEngine.VoiceConfig?,
        voiceTag: String,
    ): PostAudioOutcome {
        if (bytes == null || bytes.isEmpty()) {
            return PostAudioOutcome(bytes, false, PostAudioParams())
        }
        val params = config?.let { JReadVoiceEngine.resolvePostAudioParams(context, it) }
            ?: JReadVoiceEngine.resolvePostAudioParams(context, voiceTag)
        // 输入可能是插件原始格式（mp3 等）或标准 WAV，decodeToStandardWav 统一处理：
        // 解码（Exo → 净化后 Exo → 平台 MediaExtractor → MediaCodec 裸流直解）
        // → 重采样 24k → Sonic(语速/音量/音高，参数为默认时跳过) → 封装 WAV。
        // gain 传 1.0f：本趟是中间处理，音量增益由朗读落盘前的最终封装统一应用一次，
        // 这里再乘会导致同一段被放大两次。
        val out = io.legado.app.utils.AudioDecodeUtil.decodeToStandardWav(
            bytes,
            gain = 1.0f,
            postParams = params,
        )
        // 四级解码全失败才回退原始字节，保证合成不中断。
        //
        // 关键：**只有转码成功才置位** synthesisPostProcessed、才标记 converted。
        // 若失败也置位，朗读落盘前那趟会认为「参数已经调过」而把 postParams 置 null，
        // 于是这句音频从头到尾没经过 Sonic——插件返回的是 MP3、拿不到 PCM，
        // 音量/语速调节全部失效，表现为「音量调得很小，偶尔有一句突然很大」。
        // 同时把 converted=false 记进 [SynthesisRecord]，合并阶段就能按**这个角色**
        // 自己的参数补做后处理（旁白成功与否不影响对话那段）。
        if (out == null) {
            AppLog.put(
                "[TtsPluginJsBridge] 合成音频转 WAV 失败，回退原始字节: " +
                    "tag=$voiceTag bytes=${bytes.size}"
            )
            return PostAudioOutcome(bytes, converted = false, params = params)
        }
        synthesisPostProcessed = true
        return PostAudioOutcome(out, converted = true, params = params)
    }

    /**
     * 列出所有可用的语音标签。
     *
     * @return 标签字符串数组
     */
    fun listTags(): Array<String> {
        val configs = JReadVoiceEngine.listConfigs(context)
        val tags = configs.filter { it.enabled }.map { it.voiceTag }.distinct()
        AppLog.putDebug("[TtsPluginJsBridge] listTags: count=${tags.size}")
        return tags.toTypedArray()
    }

    /**
     * 列出所有配置信息（标签 + 显示名 + 插件名）。
     *
     * @return JSON 字符串数组，每项包含 voiceTag, displayName, pluginName, voice
     */
    fun listConfigs(): String {
        val configs = JReadVoiceEngine.listConfigs(context).filter { it.enabled }
        val plugins = JReadVoiceEngine.listPlugins(context)
        val array = org.json.JSONArray()
        configs.forEach { config ->
            val plugin = plugins.firstOrNull {
                it.enabled && (it.id == config.pluginId || it.pluginId == config.pluginId)
            }
            array.put(JSONObject()
                .put("id", config.id)
                .put("voiceTag", config.voiceTag)
                .put("displayName", config.displayName)
                .put("pluginName", plugin?.name.orEmpty())
                .put("voice", config.voice)
                .put("groupName", config.groupName)
                .put("subGroupName", config.subGroupName)
            )
        }
        return array.toString()
    }

    /**
     * 获取音频采样率（固定 24000Hz）。
     */
    fun getSampleRate(): Int = 24_000
}
