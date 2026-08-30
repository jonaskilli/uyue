package io.legado.app.help

import io.legado.app.help.audiobook.JReadVoiceEngine
import io.legado.app.model.ReadAloud
import io.legado.app.ui.book.read.config.CharacterVoiceHelper
import splitties.init.appCtx

/**
 * 内置文件。
 *
 * 作用：JS 调用 java.readExternalFile(".../fayinren.json") 时，
 * **且当前朗读引擎是内置TTS插件**，不再去外部存储找这个文件，
 * 直接把 APP 内部已启用发音人的 voiceTag 数组返回给 JS，例如 ["少女01","少女02","男主1", …]。
 *
 * 判定逻辑与「角色管理」完全一致，复用 [CharacterVoiceHelper.isUsingBuiltinTtsPlugin]：
 *  - 内置TTS插件引擎 → 返回 APP 内部配置列表里的已启用标签（实时、不落盘）
 *  - 其他引擎（系统TTS / 普通HTTP源）→ 返回 null，照旧读取本地 fayinren.json
 *
 * 好处：不依赖存储权限、不怕文件被删、界面上改完启用状态立刻生效（无写入时序问题）。
 */
object VirtualExternalFiles {

    /** 要接管的文件名。只看路径最后一段，不区分大小写。 */
    private const val FAYINREN = "fayinren.json"

    /**
     * 缓存有效期（毫秒）。
     * 朗读脚本每读一段就会重新执行一次，这里必须缓存，否则每段都要全量解析一遍发音人配置。
     */
    private const val CACHE_TTL_MS = 3000L

    /** 缓存内容 = (生成时的引擎 id, JSON, 时间戳)。引擎切换后缓存自动不命中。 */
    @Volatile
    private var cache: Triple<String?, String, Long>? = null

    /**
     * 命中内置文件则返回内容，未命中返回 null（调用方照常去读磁盘）。
     */
    @Synchronized
    fun read(path: String): String? {
        if (!path.substringAfterLast('/').equals(FAYINREN, ignoreCase = true)) return null
        // 只有选了内置TTS插件才接管；其他引擎维持原状读本地 fayinren.json
        if (!CharacterVoiceHelper.isUsingBuiltinTtsPlugin()) return null

        val engineId = currentEngineId()
        val now = System.currentTimeMillis()
        cache?.let { (cachedEngine, json, time) ->
            if (cachedEngine == engineId && now - time < CACHE_TTL_MS) return json
        }

        val json = buildFayinrenJson() ?: return null
        cache = Triple(engineId, json, now)
        return json
    }

    /** 当前朗读引擎 id，仅用于缓存校验，取不到就当无 id 处理。 */
    private fun currentEngineId(): String? = ReadAloud.ttsEngine

    /**
     * 发音人启用状态被修改后调用，让下一次读取立刻拿到新内容，不用等缓存过期。
     */
    fun invalidate() {
        cache = null
    }

    /** 生成 JSON 数组：APP 内所有已启用发音人的 voiceTag。 */
    private fun buildFayinrenJson(): String? {
        val tags = runCatching {
            // 与 CharacterVoiceHelper.getAvailableVoices() 保持一致：
            // ensureBuiltIns=false 跳过预设播种检查，优先命中 JReadVoiceEngine 内存缓存，读取最轻量
            JReadVoiceEngine.listConfigs(appCtx, ensureBuiltIns = false)
                .asSequence()
                .filter { it.enabled }
                .map { it.voiceTag.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .toList()
        }.getOrNull()
        if (tags.isNullOrEmpty()) return null
        val array = org.json.JSONArray()
        tags.forEach { array.put(it) }
        return array.toString(2)
    }
}
