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
         * 本次朗读的音频是否已在 synthesize 返回前按各角色自身的后处理参数调节完毕。
         * 为 true 时合并阶段不再做统一调速（各角色参数不同，不能用一套参数调）。
         */
        @Volatile
        @JvmStatic
        var synthesisPostProcessed: Boolean = false
            private set

        @JvmStatic
        fun resetSynthesizeFlag() {
            synthesizeCalled = false
            lastVoiceTag = ""
            synthesisPostProcessed = false
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
            return silentPcm02s()
        }
        val requestId = UUID.randomUUID().toString()
        val pointerJson = JSONObject().put("voiceTag", voiceTag).toString()
        return try {
            val bytes = JReadVoiceEngine.synthesizeLineAudio(context, text, pointerJson, requestId)
            applyPostAudioImmediately(bytes, voiceTag)
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
        // 纯符号切片前置拦截：非中文/英文/数字直接返回 0.2 秒静音，不进引擎
        if (isSymbolOnly(text)) {
            AppLog.putDebug("[TtsPluginJsBridge] 纯符号切片，返回0.2秒静音: configId=$configId textLen=${text.length}")
            synthesisPostProcessed = true
            return silentPcm02s()
        }
        val requestId = UUID.randomUUID().toString()
        val pointerJson = JSONObject().put("voiceTag", config.voiceTag).toString()
        return try {
            val bytes = JReadVoiceEngine.synthesizeConfigLineAudio(context, config, text, pointerJson, requestId)
            applyPostAudioImmediately(bytes, config.voiceTag)
        } catch (e: Exception) {
            AppLog.put("[TtsPluginJsBridge] synthesizeByConfigId 失败: configId=$configId error=${e.message}", e)
            Log.e(TAG, "synthesizeByConfigId failed: configId=$configId", e)
            null
        }
    }

    /**
     * 合成完毕后立即按该角色（voiceTag）自身的后处理参数调节音频：
     * 分组/全局链路 × 插件后参数 × 朗读界面语速滑块，一次乘齐，源头上调完，
     * 让引擎/脚本后续拿到的就是成品，合并阶段只做纯拼接。
     * 参数全部为默认值时字节原样返回（零转码开销），但同样置位标志以免合并阶段重复调速。
     */
    private fun applyPostAudioImmediately(bytes: ByteArray?, voiceTag: String): ByteArray? {
        if (bytes == null || bytes.isEmpty()) return bytes
        synthesisPostProcessed = true
        val params = JReadVoiceEngine.resolvePostAudioParams(context, voiceTag)
        if (params.resolved().isDefault()) return bytes
        // 输入可能是插件原始格式（mp3 等）或标准 WAV，decodeToStandardWav 统一处理：
        // 解码 → 重采样 24k → Sonic(语速/音量/音高) → 封装 WAV
        return io.legado.app.utils.AudioDecodeUtil.decodeToStandardWav(bytes, postParams = params) ?: bytes
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
