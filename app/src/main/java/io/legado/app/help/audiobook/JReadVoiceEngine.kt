package io.legado.app.help.audiobook

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipInputStream

object JReadVoiceEngine {
    private const val TAG = "JReadVoiceEngine"
    private const val SYNTHESIS_TIMEOUT_MS = 300_000L
    private const val SYNTHESIS_RETRY_COUNT = 10
    private const val PREF_NAME = "jread_voice_engine"
    private const val KEY_CONFIGS = "voice_configs"
    private const val KEY_PLUGINS = "voice_plugins"
    private const val KEY_GROUPS = "voice_groups"
    private const val KEY_BUILTIN_SYSTEM_CONFIG_SEEDED = "builtin_jread_voice_system_configs_seeded_v3"

    /** 试听文本兜底文案：配置未单独设置试听文本时，编辑页与配置列表的试听都用它 */
    const val DEFAULT_PREVIEW_TEXT = "你好呀，你吃饭了吗？"

    // ===== 内存缓存：避免每次合成音频都全量解析 SharedPreferences JSON =====
    @Volatile
    private var cachedConfigs: List<VoiceConfig>? = null
    @Volatile
    private var cachedPlugins: List<VoicePlugin>? = null
    /** voiceTag(已normalize) → 启用的 VoiceConfig，用于 O(1) 查找 */
    @Volatile
    private var cachedConfigIndex: Map<String, VoiceConfig>? = null
    /** pluginId → 启用的 VoicePlugin，用于 O(1) 查找 */
    @Volatile
    private var cachedPluginIndex: Map<String, VoicePlugin>? = null

    /**
     * 清空配置缓存（在 saveConfigs 写盘后调用）。
     * @param resetMigration 仅在外部数据入口（导入/播种）置 true；
     * 自身写盘的数据已规范化，不再复位 migrationDone，
     * 否则每次写后的首次读取都要对全部配置重跑一遍迁移扫描，配置量大时明显卡顿。
     */
    private fun invalidateConfigCache(resetMigration: Boolean = false) {
        cachedConfigs = null
        cachedConfigIndex = null
        if (resetMigration) {
            migrationDone = false
        }
    }

    /** 清空插件缓存（在 savePlugins 写盘后调用） */
    private fun invalidatePluginCache() {
        cachedPlugins = null
        cachedPluginIndex = null
    }

    /** 清空分组缓存（在 saveGroups 写盘后调用） */
    @Volatile
    private var cachedGroups: List<VoiceGroup>? = null
    private fun invalidateGroupCache() {
        cachedGroups = null
    }

    /**
     * 清空全部内存缓存。
     * 供外部批量导入（TTS 备份恢复）后调用：直接改写了 SharedPreferences 不会走
     * saveXxx 系列，缓存还是旧的，不清会导致界面继续显示恢复前的插件/音色列表。
     */
    fun invalidateAllCaches() {
        invalidateConfigCache(resetMigration = true)
        invalidatePluginCache()
        invalidateGroupCache()
    }

    /** 迁移幂等标志：首次执行后设 true，本次进程内不再重复迁移 */
    @Volatile
    private var migrationDone = false
    private const val KEY_BUILTIN_PLUGIN_BUNDLE_12_SEEDED = "builtin_jread_voice_plugins_12_seeded"
    private const val BUILTIN_SYSTEM_CONFIG_ASSET = "defaultData/jreadVoice/jread_voice_system_configs.json"
    private const val BUILTIN_PLUGIN_BUNDLE_12_ASSET = "defaultData/jreadVoice/jread_voice_plugins_12.zip"
    private const val BUILTIN_VIVI_GROUP_KEY = "性格分组演员池"
    private const val BUILTIN_VIVI_LEGACY_GROUP_KEY = "Vivi的独舞"
    private const val BUILTIN_PLUGIN_BUNDLE_12_KEY = "maoxiang.tts.gj_v35"
    private const val KEY_BUILTIN_VIVI_DEDUPED = "builtin_vivi_voice_pool_deduped_v1"
    @Volatile
    private var seedingBuiltInConfigs = false
    @Volatile
    private var seedingBuiltInPlugins = false
    @Volatile
    private var migratingTimbreVoiceGroups = false
    private const val KEY_SELECTED_GROUP = "selected_config_group"
    private const val KEY_SELECTED_SUB_GROUP = "selected_config_sub_group"

    data class VoicePlugin(
        val id: String = UUID.randomUUID().toString(),
        val name: String = "",
        val pluginId: String = "",
        val pluginGroupId: String = "",
        val pluginGroupName: String = "",
        val author: String = "",
        val version: String = "",
        val iconUrl: String = "",
        val code: String = "",
        val defVarsJson: String = "{}",
        val userVarsJson: String = "{}",
        val method: String = "GET",
        val urlTemplate: String = "",
        val headersText: String = "",
        val bodyTemplate: String = "",
        val responseAudioPath: String = "",
        val enabled: Boolean = true,
        /** 插件级合成参数：与每个配置的同名参数相乘，1.0 = 不调整 */
        val speed: Float = 1f,
        val volume: Float = 1f,
        val pitch: Float = 1f,
        /** 插件级后处理参数（合成后生效）：与分组/全局后处理链路结果相乘，FOLLOW(0) = 不干预 */
        val postSpeed: Float = PostAudioParams.FOLLOW,
        val postVolume: Float = PostAudioParams.FOLLOW,
        val postPitch: Float = PostAudioParams.FOLLOW,
    )

    data class VoiceGroup(
        val id: String = UUID.randomUUID().toString(),
        val groupName: String,
        val subGroupName: String = "",
        val thirdGroupName: String = "",
        val displayName: String = "",
        val postSpeed: Float = PostAudioParams.FOLLOW,
        val postVolume: Float = PostAudioParams.FOLLOW,
        val postPitch: Float = PostAudioParams.FOLLOW,
        val sortOrder: Int = 0,
    )

    data class VoiceTagPool(
        val key: String,
        val label: String,
    )

    private data class TimbreConfigGroupTarget(
        val groupName: String,
        val subGroupName: String,
        val thirdGroupName: String,
    )

    data class VoiceConfig(
        val id: String = UUID.randomUUID().toString(),
        val voiceTag: String,
        val groupName: String = "",
        val subGroupName: String = "",
        val thirdGroupName: String = "",
        val displayName: String = "",
        val pluginId: String = "",
        val locale: String = "",
        val voice: String = "",
        val previewText: String = "",
        val dataJson: String = "{}",
        val speed: Float = 1f,
        val volume: Float = 1f,
        val pitch: Float = 1f,
        val method: String = "GET",
        val urlTemplate: String = "",
        val headersText: String = "",
        val bodyTemplate: String = "",
        val responseAudioPath: String = "",
        val enabled: Boolean = true,
        val postSpeed: Float = PostAudioParams.FOLLOW,
        val postVolume: Float = PostAudioParams.FOLLOW,
        val postPitch: Float = PostAudioParams.FOLLOW,
        val sortOrder: Int = 0,
    )

    fun listConfigs(
        context: Context,
        ensureBuiltIns: Boolean = true,
        /** 损坏恢复的重入保护：true 时不允许再次走恢复分支，防止备份也损坏时无限递归 */
        recovering: Boolean = false,
    ): List<VoiceConfig> {
        cachedConfigs?.let { return it }
        val appContext = context.applicationContext
        if (ensureBuiltIns && !seedingBuiltInConfigs) {
            ensureBuiltInVoiceConfigPresets(appContext)
        }
        migrateExistingTimbreVoicePoolGroups(appContext)
        val raw = appContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONFIGS, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull()
        // 主数据损坏（不合法 JSON，典型成因：数 MB 的大 JSON 落盘中途进程被杀、磁盘满）。
        // 这里绝不能静默当空列表——空列表一旦被任何写操作（改开关、拖排序）全量写回
        // 磁盘，用户导入的全部配置就永久清零、只剩内置预置重新种入，表现正是
        // 「用着用着所有配置和插件都恢复成内置」。先从备份把主文件恢复，再重新读取。
        if (array == null && raw.isNotBlank() && raw != "[]" && !recovering) {
            val backup = readBackup(context, KEY_CONFIGS)
            if (backup != null) {
                AppLog.put("[J阅读声音引擎] 配置数据损坏（JSON 解析失败），已从备份恢复")
                appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_CONFIGS, backup).commit()
                invalidateConfigCache()
                cachedConfigs = null
                return listConfigs(context, ensureBuiltIns = false, recovering = true)
            }
            AppLog.put("[J阅读声音引擎] 配置数据损坏且无可用备份，只能返回空列表")
        }
        val result = buildList {
            for (index in 0 until (array?.length() ?: 0)) {
                val obj = array?.optJSONObject(index) ?: continue
                parseVoiceConfig(obj)?.let { add(it) }
            }
        }
        cachedConfigs = result
        return result
    }

    fun voiceConfigSnapshotVersion(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val configs = prefs.getString(KEY_CONFIGS, "[]").orEmpty()
        val plugins = prefs.getString(KEY_PLUGINS, "[]").orEmpty()
        return "${configs.length}:${configs.hashCode()}:${plugins.length}:${plugins.hashCode()}"
    }

    fun ensureBuiltInVoicePresets(context: Context) {
        ensureBuiltInVoiceConfigPresets(context.applicationContext)
        ensureBuiltInVoicePluginPresets(context.applicationContext)
    }

    private fun ensureBuiltInVoiceConfigPresets(context: Context) {
        val appContext = context.applicationContext
        ensureBuiltInSystemVoiceConfigPresets(appContext)
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        deduplicateBuiltInVoicePool(appContext, prefs)
    }

    private fun ensureBuiltInSystemVoiceConfigPresets(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BUILTIN_SYSTEM_CONFIG_SEEDED, false)) return
        val text = runCatching {
            appContext.assets.open(BUILTIN_SYSTEM_CONFIG_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrNull() ?: return
        seedingBuiltInConfigs = true
        try {
            val count = runCatching { importConfigsFromJson(appContext, text, useStableId = true) }.getOrDefault(0)
            if (count > 0) {
                prefs.edit().putBoolean(KEY_BUILTIN_SYSTEM_CONFIG_SEEDED, true).apply()
                AppLog.putDebug("[J阅读声音引擎] 已预置基础发声配置：$count 项")
            }
        } finally {
            seedingBuiltInConfigs = false
        }
    }

    /**
     * 一次性去重：历史版本由于导入时用随机 UUID 作为 id，导致同一角色被反复追加产生重复。
     * 这里按稳定 id（内容哈希）分组，每组只保留一条，彻底清除重复数据。
     */
    private fun deduplicateBuiltInVoicePool(context: Context, prefs: SharedPreferences) {
        if (prefs.getBoolean(KEY_BUILTIN_VIVI_DEDUPED, false)) return
        val configs = listConfigs(context, ensureBuiltIns = false)
        if (configs.isEmpty()) {
            prefs.edit().putBoolean(KEY_BUILTIN_VIVI_DEDUPED, true).apply()
            return
        }
        val seen = linkedMapOf<String, VoiceConfig>()
        val kept = mutableListOf<VoiceConfig>()
        configs.forEach { config ->
            val stableId = stableConfigId(config)
            val existing = seen[stableId]
            if (existing == null) {
                seen[stableId] = config
                kept += config
            } else {
                // 保留 enabled 的那条；都未启用则保留原顺序第一条
                if (config.enabled && !existing.enabled) {
                    seen[stableId] = config
                    kept[kept.indexOf(existing)] = config
                }
            }
        }
        if (kept.size != configs.size) {
            saveConfigs(context, kept)
        }
        prefs.edit().putBoolean(KEY_BUILTIN_VIVI_DEDUPED, true).apply()
    }

    fun isConfigPlayable(context: Context, config: VoiceConfig): Boolean {
        return config.isPlayable(listPlugins(context.applicationContext))
    }

    fun fallbackVoiceTagAfterInvalidAudio(context: Context, voiceTag: String): String? {
        val plugins = listPlugins(context.applicationContext)
        val configs = listConfigs(context.applicationContext).filter { it.enabled && it.isPlayable(plugins) }
        if (configs.isEmpty()) return null
        val normalizedTag = normalizeVoiceTag(voiceTag)
        val exact = findConfigIn(configs, normalizedTag)
        val wantedPool = voiceTagPool(normalizedTag)?.key
        if (wantedPool != null) {
            configs.firstOrNull { config ->
                config.id != exact?.id && voiceTagPool(config.voiceTag)?.key == wantedPool
            }?.let { return it.voiceTag }
            if (wantedPool == "narration") return null
        }
        return configs.firstOrNull { it.id != exact?.id && it.voiceTag == "旁白" }?.voiceTag
            ?: configs.firstOrNull { it.id != exact?.id }?.voiceTag
    }

    fun listGroups(context: Context): List<VoiceGroup> {
        cachedGroups?.let { return it }
        val appContext = context.applicationContext
        migrateExistingTimbreVoicePoolGroups(appContext)
        val raw = appContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GROUPS, "[]")
            .orEmpty()
        val result = parseVoiceGroups(raw)
        cachedGroups = result
        return result
    }

    /**
     * 朗读界面语速滑块系数：显示值 = (speechRatePlayFine + 50) / 100f。
     * 滑块精细进度 0~450（步进 0.01）→ 系数 0.50~5.00；默认 50 → 系数 1.00（不调整）；
     * 「跟随系统」开关打开时系数恒 1.00。
     * 该系数作用于合成完毕后的音频（Sonic 变速后处理）。
     */
    fun readAloudUiSpeedFactor(): Float =
        (io.legado.app.help.config.AppConfig.speechRatePlayFine + 50) / 100f

    /**
     * 解析某个 voiceTag 的最终后处理音频参数。
     *
     * 【关键】选配置的口径必须与合成完全一致（复用 [findConfig]：仅启用 + 可用 + 标签别名匹配 + 兜底），
     * 绝不能直接 `firstOrNull { it.voiceTag == voiceTag }`：配置表里内置音色池有 400+ 条同名标签
     * （如「旁白」「少女01」），那样取到的大概率是内置那条 post*=0 的配置，
     * 于是条目级/分组级/插件级后处理参数全部读不到（表现为"配了没效果"），
     * 而全局参数与配置无关所以照常生效。
     */
    /** 全局级后处理音频参数（朗读配置列表右上角菜单里设置的那套） */
    private fun globalPostParams(): PostAudioParams = PostAudioParams(
        io.legado.app.help.config.AppConfig.ttsPostSpeed,
        io.legado.app.help.config.AppConfig.ttsPostVolume,
        io.legado.app.help.config.AppConfig.ttsPostPitch,
    )

    fun resolvePostAudioParams(context: Context, voiceTag: String): PostAudioParams {
        val uiSpeedFactor = readAloudUiSpeedFactor()
        val config = findConfig(context, voiceTag)
            ?: return globalPostParams().let {
                PostAudioParams(
                    speed = (it.speed * uiSpeedFactor)
                        .coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
                    volume = it.volume.coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
                    pitch = it.pitch.coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
                )
            }
        return resolvePostAudioParams(context, config, uiSpeedFactor)
    }

    /**
     * 按配置 id 解析最终后处理音频参数。
     * 供脚本用 `synthesizeByConfigId` 合成时使用：配置已确定，无同名歧义。
     */
    fun resolvePostAudioParamsById(context: Context, configId: String): PostAudioParams? {
        if (configId.isBlank()) return null
        val config = findConfigById(context, configId) ?: return null
        return resolvePostAudioParams(context, config, readAloudUiSpeedFactor())
    }

    /**
     * 以「已经确定下来的那条配置」解析最终后处理音频参数。
     * 合成与后处理必须用同一条配置，否则条目/分组/插件级参数会作用到别的配置上。
     */
    fun resolvePostAudioParams(context: Context, config: VoiceConfig): PostAudioParams =
        resolvePostAudioParams(context, config, readAloudUiSpeedFactor())

    private fun resolvePostAudioParams(
        context: Context,
        config: VoiceConfig,
        uiSpeedFactor: Float,
    ): PostAudioParams {
        val groups = listGroups(context)
        val globalParams = globalPostParams()

        // 大分组：与全局相乘（分组某项为 FOLLOW 则视为 1.0，只保留全局）
        val groupParams = groups
            .firstOrNull { it.groupName == config.groupName && it.subGroupName.isBlank() && it.thirdGroupName.isBlank() }
            ?.let { PostAudioParams(it.postSpeed, it.postVolume, it.postPitch).timesWithParent(globalParams) }
            ?: globalParams

        // 中分组 (subGroup)
        val subGroupParams = groups
            .firstOrNull { it.groupName == config.groupName && it.subGroupName == config.subGroupName && it.thirdGroupName.isBlank() }
            ?.let { PostAudioParams(it.postSpeed, it.postVolume, it.postPitch).timesWithParent(groupParams) }
            ?: groupParams

        // 小分组 (thirdGroup)
        val thirdGroupParams = groups
            .firstOrNull { it.groupName == config.groupName && it.subGroupName == config.subGroupName && it.thirdGroupName == config.thirdGroupName }
            ?.let { PostAudioParams(it.postSpeed, it.postVolume, it.postPitch).timesWithParent(subGroupParams) }
            ?: subGroupParams

        // 配置级别：与上面逐级累乘的结果再相乘
        val base = PostAudioParams(config.postSpeed, config.postVolume, config.postPitch)
            .timesWithParent(thirdGroupParams)
            .resolved()
        // 插件级后处理参数：与链路结果相乘（FOLLOW/0 视为 1.0 = 不干预）；
        // 语速再乘朗读界面滑块系数；总体钳制到有效范围
        val plugin = findPlugin(context, config.pluginId) ?: return PostAudioParams(
            speed = (base.speed * uiSpeedFactor).coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
            volume = base.volume.coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
            pitch = base.pitch.coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
        )
        return PostAudioParams(
            speed = (base.speed * pluginSynthFactor(plugin.postSpeed) * uiSpeedFactor)
                .coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
            volume = (base.volume * pluginSynthFactor(plugin.postVolume))
                .coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
            pitch = (base.pitch * pluginSynthFactor(plugin.postPitch))
                .coerceIn(PostAudioParams.MIN, PostAudioParams.MAX),
        )
    }

    fun saveGroup(context: Context, group: VoiceGroup) {
        val groupName = group.groupName.trim()
        if (groupName.isBlank()) return
        val normalized = group.copy(
            groupName = groupName,
            subGroupName = group.subGroupName.trim(),
            thirdGroupName = group.thirdGroupName.trim(),
        )
        val groups = listGroups(context).toMutableList()
        val index = groups.indexOfFirst {
            it.groupName == normalized.groupName &&
                it.subGroupName == normalized.subGroupName &&
                it.thirdGroupName == normalized.thirdGroupName
        }.takeIf { it >= 0 } ?: groups.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            val existing = groups[index]
            groups[index] = normalized.copy(
                id = existing.id,
                displayName = normalized.displayName.ifBlank { existing.displayName },
            )
        } else {
            groups += normalized
        }
        saveGroups(context, groups)
    }

    /**
     * 持久化配置列表的排序（批量更新 sortOrder）
     */
    fun saveConfigsSortOrder(context: Context, orderedConfigIds: List<String>) {
        val configs = listConfigs(context, ensureBuiltIns = false).toMutableList()
        val idToOrder = orderedConfigIds.mapIndexed { index, id -> id to index }.toMap()
        val updated = configs.map { config ->
            config.copy(sortOrder = idToOrder[config.id] ?: config.sortOrder)
        }
        saveConfigs(context, updated)
    }

    /**
     * 持久化分组列表的排序（批量更新 sortOrder）
     */
    fun saveGroupsSortOrder(context: Context, orderedGroupIds: List<String>) {
        val groups = listGroups(context).toMutableList()
        val idToOrder = orderedGroupIds.mapIndexed { index, id -> id to index }.toMap()
        val updated = groups.map { group ->
            group.copy(sortOrder = idToOrder[group.id] ?: group.sortOrder)
        }
        saveGroups(context, updated)
    }

    fun deleteGroup(context: Context, groupName: String, subGroupName: String? = null) {
        val cleanGroup = groupName.trim().ifBlank { "默认分组" }
        val cleanSub = subGroupName?.trim()
        val groups = listGroups(context).filterNot {
            it.groupName.ifBlank { "默认分组" } == cleanGroup &&
            (cleanSub == null || it.subGroupName.ifBlank { "默认" } == cleanSub)
        }
        saveGroups(context, groups)
    }

    fun renameConfigGroupDisplayName(
        context: Context,
        groupName: String,
        subGroupName: String?,
        displayName: String,
    ) {
        val cleanGroupName = groupName.trim().ifBlank { "默认分组" }
        val cleanSubGroupName = subGroupName?.trim().orEmpty().let { if (it == "默认") "" else it }
        val cleanDisplayName = displayName.trim()
        if (cleanDisplayName.isBlank()) return
        val currentGroups = listGroups(context)
        val existing = currentGroups.firstOrNull {
            it.groupName.ifBlank { "默认分组" } == cleanGroupName &&
                it.subGroupName.ifBlank { "默认" } == cleanSubGroupName.ifBlank { "默认" }
        }
        saveGroup(
            context,
            (existing ?: VoiceGroup(groupName = cleanGroupName, subGroupName = cleanSubGroupName))
                .copy(displayName = cleanDisplayName)
        )
    }

    /**
     * 快速启停单条配置（供列表开关使用）——事务化"读-改-写"：
     * 全程持有配置写锁，快速连点时各次修改串行且基于最新状态累加，
     * 彻底消除并发整表快照互相覆盖导致的"点4个只成功2个"。
     * 不做内容规范化、不 upsert 分组（分组信息未变）；
     * 启用时在单次遍历内完成同 voiceTag 互斥；仅一次写盘，写后直接预热缓存。
     * @return 写盘后的最新配置全量列表（可直接用于刷新 UI 内存态）
     */
    fun setConfigEnabled(context: Context, id: String, enabled: Boolean): List<VoiceConfig> {
        synchronized(configWriteLock) {
            val configs = listConfigs(context, ensureBuiltIns = false)
            val target = configs.firstOrNull { it.id == id }
                ?: return configs
            if (target.enabled == enabled) return configs
            val changed = booleanArrayOf(false)
            val updated: List<VoiceConfig> = if (enabled) {
                configs.map {
                    when {
                        it.id == id -> {
                            changed[0] = true
                            it.copy(enabled = true)
                        }
                        // 同标签互斥：同一遍完成，避免二次全扫
                        it.enabled && sameVoiceTag(it.voiceTag, target.voiceTag) -> {
                            changed[0] = true
                            it.copy(enabled = false)
                        }
                        else -> it
                    }
                }
            } else {
                configs.map {
                    if (it.id == id && it.enabled) {
                        changed[0] = true
                        it.copy(enabled = false)
                    } else it
                }
            }
            if (changed[0]) {
                saveConfigs(context, updated)
                return updated
            }
            return configs
        }
    }

    fun saveConfig(context: Context, config: VoiceConfig) {
        val normalizedConfig = normalizeVoiceConfigForStorage(config)
        saveGroup(
            context,
            VoiceGroup(
                groupName = normalizedConfig.groupName.ifBlank { "默认分组" },
                subGroupName = normalizedConfig.subGroupName,
                thirdGroupName = normalizedConfig.thirdGroupName,
            )
        )
        val configs = listConfigs(context).toMutableList()
        val index = configs.indexOfFirst {
            it.id == normalizedConfig.id
        }
        if (index >= 0) {
            configs[index] = normalizedConfig
        } else {
            configs += normalizedConfig
        }
        val savedConfigs = if (normalizedConfig.enabled) {
            configs.map {
                if (it.id != normalizedConfig.id && sameVoiceTag(it.voiceTag, normalizedConfig.voiceTag)) {
                    it.copy(enabled = false)
                } else {
                    it
                }
            }
        } else {
            configs
        }
        saveConfigs(context, savedConfigs)
    }

    fun saveConfigsBatch(context: Context, configsToSave: Collection<VoiceConfig>) {
        if (configsToSave.isEmpty()) return
        val appContext = context.applicationContext
        val normalizedConfigs = configsToSave
            .map { normalizeVoiceConfigForStorage(it) }
            .distinctBy { it.id }
        if (normalizedConfigs.isEmpty()) return

        val groups = listGroups(appContext).toMutableList()
        fun upsertGroup(group: VoiceGroup) {
            val normalized = group.copy(
                groupName = group.groupName.trim().ifBlank { "默认分组" },
                subGroupName = group.subGroupName.trim(),
                thirdGroupName = group.thirdGroupName.trim(),
            )
            val index = groups.indexOfFirst {
                it.groupName == normalized.groupName &&
                    it.subGroupName == normalized.subGroupName &&
                    it.thirdGroupName == normalized.thirdGroupName
            }.takeIf { it >= 0 } ?: groups.indexOfFirst { it.id == normalized.id }
            if (index >= 0) {
                val existing = groups[index]
                groups[index] = normalized.copy(
                    id = existing.id,
                    displayName = normalized.displayName.ifBlank { existing.displayName },
                )
            } else {
                groups += normalized
            }
        }

        normalizedConfigs.forEach { config ->
            upsertGroup(
                VoiceGroup(
                    groupName = config.groupName.ifBlank { "默认分组" },
                    subGroupName = config.subGroupName,
                    thirdGroupName = config.thirdGroupName,
                )
            )
        }
        saveGroups(appContext, groups)

        val enabledIdByVoiceTag = linkedMapOf<String, String>()
        normalizedConfigs
            .filter { it.enabled }
            .forEach { config ->
                val key = normalizedVoiceTagForConfig(config)
                if (key.isNotBlank()) enabledIdByVoiceTag[key] = config.id
            }
        val chosenEnabledIds = enabledIdByVoiceTag.values.toSet()
        val normalizedForStorage = normalizedConfigs.map { config ->
            if (config.enabled && config.id !in chosenEnabledIds) {
                config.copy(enabled = false)
            } else {
                config
            }
        }
        val byId = normalizedForStorage.associateBy { it.id }
        val existingConfigs = listConfigs(appContext).toMutableList()
        existingConfigs.replaceAll { byId[it.id] ?: it }
        val existingIds = existingConfigs.map { it.id }.toSet()
        normalizedForStorage
            .filterNot { it.id in existingIds }
            .forEach { existingConfigs += it }

        val batchIds = normalizedForStorage.map { it.id }.toSet()
        val savedConfigs = normalizedForStorage
            .filter { it.enabled }
            .fold(existingConfigs.toList()) { current, normalized ->
                current.map {
                    if (it.id !in batchIds && sameVoiceTag(it.voiceTag, normalized.voiceTag)) {
                        it.copy(enabled = false)
                    } else {
                        it
                    }
                }
            }
        saveConfigs(appContext, savedConfigs)
    }

    /** 一键禁用所有配置 */
    fun disableAllConfigs(context: Context) {
        val configs = listConfigs(context)
        val updated = configs.map { it.copy(enabled = false) }
        saveConfigs(context, updated)
    }

    /**
     * 分组/批量启停（事务化）：
     * 全程持有配置写锁——读取、互斥计算、写盘、缓存预热为一次原子"读-改-写"，
     * 并发调用串行执行且各自基于最新状态，不再发生整表快照互相覆盖。
     * 禁用路径跳过 voiceTag 规范化扫描（互斥仅在启用时有意义），大表下显著提速。
     * @return 写盘后的最新配置全量列表（可直接用于刷新 UI 内存态）
     */
    fun setConfigsEnabled(
        context: Context,
        configIds: Collection<String>,
        enabled: Boolean,
    ): List<VoiceConfig> {
        synchronized(configWriteLock) {
            val idSet = configIds.filter { it.isNotBlank() }.toSet()
            if (idSet.isEmpty()) return listConfigs(context, ensureBuiltIns = false)
            val configs = listConfigs(context, ensureBuiltIns = false)
            val enabledIdByVoiceTag = linkedMapOf<String, String>()
            if (enabled) {
                configs.forEach { config ->
                    if (config.id in idSet) {
                        val key = normalizedVoiceTagForConfig(config)
                        if (key.isNotBlank()) {
                            enabledIdByVoiceTag[key] = config.id
                        }
                    }
                }
            }
            val chosenEnabledIds = enabledIdByVoiceTag.values.toSet()
            val targetTags = enabledIdByVoiceTag.keys
            val changed = booleanArrayOf(false)
            val updated: List<VoiceConfig>
            if (enabled) {
                updated = configs.map { config ->
                    val key = normalizedVoiceTagForConfig(config)
                    val targetEnabled = when {
                        config.id in idSet -> config.id in chosenEnabledIds
                        key in targetTags -> false
                        else -> config.enabled
                    }
                    if (config.enabled == targetEnabled) config
                    else {
                        changed[0] = true
                        config.copy(enabled = targetEnabled)
                    }
                }
            } else {
                // 禁用：无互斥语义，仅翻转目标项
                updated = configs.map { config ->
                    if (config.id in idSet && config.enabled) {
                        changed[0] = true
                        config.copy(enabled = false)
                    } else config
                }
            }
            if (changed[0]) {
                saveConfigs(context, updated)
                return updated
            }
            return configs
        }
    }

    /** 插件级音频参数因子：<=0 视为 1.0（不调整） */
    fun pluginSynthFactor(value: Float): Float =
        if (value <= 0f) PostAudioParams.DEFAULT else value

    /**
     * 更新插件级音频参数（保存在插件自身上，不覆盖各配置的参数）：
     * - speed/volume/pitch：合成参数，与每个配置的同名参数相乘，1.0 = 不调整
     * - postSpeed/postVolume/postPitch：合成后后处理参数，与分组/全局链路结果相乘，FOLLOW(0) = 不干预
     */
    fun updatePluginAudioParams(
        context: Context,
        plugin: VoicePlugin,
        speed: Float,
        volume: Float,
        pitch: Float,
        postSpeed: Float = PostAudioParams.FOLLOW,
        postVolume: Float = PostAudioParams.FOLLOW,
        postPitch: Float = PostAudioParams.FOLLOW,
    ): Boolean {
        val plugins = listPlugins(context).toMutableList()
        val index = plugins.indexOfFirst {
            it.id == plugin.id || (plugin.pluginId.isNotBlank() && it.pluginId == plugin.pluginId)
        }
        if (index < 0) return false
        plugins[index] = plugins[index].copy(
            speed = speed,
            volume = volume,
            pitch = pitch,
            postSpeed = postSpeed,
            postVolume = postVolume,
            postPitch = postPitch,
        )
        savePlugins(context, plugins)
        return true
    }

    fun deleteConfig(context: Context, id: String) {
        saveConfigs(context, listConfigs(context).filterNot { it.id == id })
    }

    fun requiredVoicePools(rawTags: List<String>): List<VoiceTagPool> {
        return rawTags
            .mapNotNull { voiceTagPool(it) }
            .distinctBy { it.key }
    }

    fun missingEnabledVoicePools(
        configs: List<VoiceConfig>,
        rawTags: List<String>,
    ): List<String> {
        val requiredPools = requiredVoicePools(rawTags)
        if (requiredPools.isEmpty()) return emptyList()
        val enabledPools = configs
            .filter { it.enabled }
            .mapNotNull { voiceTagPool(it.voiceTag) }
            .map { it.key }
            .toSet()
        return requiredPools
            .filterNot { it.key in enabledPools }
            .map { it.label }
    }

    fun deleteConfigGroup(context: Context, groupName: String, subGroupName: String? = null): Int {
        val normalizedGroup = groupName.ifBlank { "默认分组" }
        val normalizedSub = subGroupName?.ifBlank { "默认" }
        fun matchesGroup(configGroup: String): Boolean {
            return configGroup.ifBlank { "默认分组" } == normalizedGroup
        }
        fun matchesSub(configSub: String): Boolean {
            return normalizedSub == null || configSub.ifBlank { "默认" } == normalizedSub
        }

        val oldConfigs = listConfigs(context)
        val newConfigs = oldConfigs.filterNot { matchesGroup(it.groupName) && matchesSub(it.subGroupName) }
        saveConfigs(context, newConfigs)
        saveGroups(
            context,
            listGroups(context).filterNot {
                it.groupName == normalizedGroup &&
                    (normalizedSub == null || it.subGroupName.ifBlank { "默认" } == normalizedSub)
            }
        )
        selectedConfigGroup(context)?.let { (selectedGroup, selectedSub) ->
            if (selectedGroup == normalizedGroup && (normalizedSub == null || selectedSub.ifBlank { "默认" } == normalizedSub)) {
                clearSelectedConfigGroup(context)
            }
        }
        return oldConfigs.size - newConfigs.size
    }

    fun moveConfigGroup(
        context: Context,
        sourceGroupName: String,
        sourceSubGroupName: String?,
        targetGroupName: String,
        targetSubGroupName: String,
    ): Int {
        val normalizedSourceGroup = sourceGroupName.ifBlank { "默认分组" }
        val normalizedSourceSub = sourceSubGroupName?.ifBlank { "默认" }
        val cleanTargetGroup = targetGroupName.trim().ifBlank { "默认分组" }
        val cleanTargetSub = targetSubGroupName.trim().let { if (it == "默认") "" else it }
        fun matchesGroup(configGroup: String): Boolean {
            return configGroup.ifBlank { "默认分组" } == normalizedSourceGroup
        }
        fun matchesSub(configSub: String): Boolean {
            return normalizedSourceSub == null || configSub.ifBlank { "默认" } == normalizedSourceSub
        }

        var movedCount = 0
        val movedConfigs = listConfigs(context).map { config ->
            if (matchesGroup(config.groupName) && matchesSub(config.subGroupName)) {
                movedCount++
                config.copy(groupName = cleanTargetGroup, subGroupName = cleanTargetSub)
            } else {
                config
            }
        }
        if (movedCount <= 0) return 0

        saveConfigs(context, movedConfigs)
        saveGroups(
            context,
            listGroups(context).filterNot {
                it.groupName.ifBlank { "默认分组" } == normalizedSourceGroup &&
                    (normalizedSourceSub == null || it.subGroupName.ifBlank { "默认" } == normalizedSourceSub)
            }
        )
        saveGroup(context, VoiceGroup(groupName = cleanTargetGroup, subGroupName = cleanTargetSub))
        return movedCount
    }

    fun moveConfigsToGroup(
        context: Context,
        configIds: Set<String>,
        targetGroupName: String,
        targetSubGroupName: String,
    ): Int {
        if (configIds.isEmpty()) return 0
        val cleanTargetGroup = targetGroupName.trim().ifBlank { "默认分组" }
        val cleanTargetSub = targetSubGroupName.trim().let { if (it == "默认") "" else it }
        var movedCount = 0
        val movedConfigs = listConfigs(context).map { config ->
            if (config.id in configIds) {
                movedCount++
                config.copy(groupName = cleanTargetGroup, subGroupName = cleanTargetSub)
            } else {
                config
            }
        }
        if (movedCount <= 0) return 0

        saveConfigs(context, movedConfigs)
        saveGroup(context, VoiceGroup(groupName = cleanTargetGroup, subGroupName = cleanTargetSub))
        return movedCount
    }

    fun switchConfigGroupPlugin(
        context: Context,
        groupName: String,
        subGroupName: String?,
        targetPlugin: VoicePlugin,
        targetLocale: String,
        targetVoices: List<JReadVoicePluginRuntime.VoiceOption>,
    ): Int {
        val normalizedGroup = groupName.ifBlank { "默认分组" }
        val normalizedSub = subGroupName?.ifBlank { "默认" }
        val cleanLocale = targetLocale.trim().ifBlank { "zh-CN" }
        var changedCount = 0
        val updatedConfigs = listConfigs(context).map { config ->
            val configGroup = config.groupName.ifBlank { "默认分组" }
            val configSub = config.subGroupName.ifBlank { "默认" }
            val matchesGroup = configGroup == normalizedGroup
            val matchesSub = normalizedSub == null || configSub == normalizedSub
            if (!matchesGroup || !matchesSub) return@map config
            val next = config.copy(
                pluginId = targetPlugin.pluginId.ifBlank { targetPlugin.id },
                locale = cleanLocale,
                voice = resolveTargetPluginVoice(config, targetVoices),
                method = "GET",
                urlTemplate = "",
                headersText = "",
                bodyTemplate = "",
                responseAudioPath = "",
            )
            if (next != config) changedCount++
            next
        }
        if (changedCount > 0) {
            saveConfigs(context, updatedConfigs)
        }
        return changedCount
    }

    private fun resolveTargetPluginVoice(
        config: VoiceConfig,
        targetVoices: List<JReadVoicePluginRuntime.VoiceOption>,
    ): String {
        if (targetVoices.isEmpty()) return config.voice
        val targetIds = targetVoices.map { it.id }.filter { it.isNotBlank() }
        if (config.voice.isNotBlank() && config.voice in targetIds) return config.voice

        val candidates = listOf(config.displayName, config.voice, config.voiceTag)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val normalizedCandidates = candidates.map { normalizeVoiceIdForPluginMatch(it) }.filter { it.isNotBlank() }

        targetVoices.firstOrNull { option ->
            val optionValues = listOf(option.id, option.name)
                .map { normalizeVoiceIdForPluginMatch(it) }
                .filter { it.isNotBlank() }
            optionValues.any { it in normalizedCandidates }
        }?.let { return it.id }

        candidates.mapNotNull { extractVoiceNumberForPluginMatch(it) }.firstOrNull { number ->
            targetVoices.any {
                extractVoiceNumberForPluginMatch(it.id) == number ||
                    extractVoiceNumberForPluginMatch(it.name) == number
            }
        }?.let { number ->
            targetVoices.firstOrNull {
                extractVoiceNumberForPluginMatch(it.id) == number ||
                    extractVoiceNumberForPluginMatch(it.name) == number
            }?.let { return it.id }
        }

        targetVoices.firstOrNull { option ->
            val optionValues = listOf(option.id, option.name)
                .map { normalizeVoiceIdForPluginMatch(it) }
                .filter { it.isNotBlank() }
            optionValues.any { optionValue ->
                normalizedCandidates.any { candidate ->
                    candidate.contains(optionValue) || optionValue.contains(candidate)
                }
            }
        }?.let { return it.id }

        return config.voice
    }

    private fun normalizeVoiceIdForPluginMatch(raw: String): String {
        return raw
            .trim()
            .removePrefix("clone_")
            .replace(Regex("^ggref_\\d+_"), "")
            .replace(Regex("[()（）\\s/\\\\.:：，,;；#?&=\\[\\]{}]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .lowercase()
    }

    private fun extractVoiceNumberForPluginMatch(raw: String): String? {
        return Regex("\\d{2,6}")
            .find(normalizeVoiceIdForPluginMatch(raw))
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    fun reorderConfigPrimaryGroups(context: Context, orderedGroupNames: List<String>) {
        val cleanOrder = orderedGroupNames
            .map { it.trim().ifBlank { "默认分组" } }
            .distinct()
        if (cleanOrder.isEmpty()) return
        val currentGroups = listGroups(context)
        val configGroups = listConfigs(context)
            .map { config ->
                val groupName = config.groupName.ifBlank { "默认分组" }
                val subGroupName = config.subGroupName
                val existing = currentGroups.firstOrNull {
                    it.groupName.ifBlank { "默认分组" } == groupName &&
                        it.subGroupName.ifBlank { "默认" } == subGroupName.ifBlank { "默认" }
                }
                existing ?: VoiceGroup(groupName = groupName, subGroupName = subGroupName)
            }
        val mergedGroups = (currentGroups + configGroups)
            .distinctBy { it.groupName.ifBlank { "默认分组" } to it.subGroupName.ifBlank { "默认" } }
        val orderIndex = cleanOrder.withIndex().associate { it.value to it.index }
        val reordered = mergedGroups.sortedWith(
            compareBy<VoiceGroup> { orderIndex[it.groupName.ifBlank { "默认分组" }] ?: Int.MAX_VALUE }
                .thenBy { it.subGroupName.ifBlank { "默认" } }
        )
        saveGroups(context, reordered)
    }

    fun reorderConfigSubGroups(context: Context, groupName: String, orderedSubGroupNames: List<String>) {
        val cleanGroup = groupName.trim().ifBlank { "默认分组" }
        val cleanOrder = orderedSubGroupNames
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "默认" }
            .distinct()
        if (cleanOrder.isEmpty()) return
        val currentGroups = listGroups(context)
        val configGroups = listConfigs(context)
            .filter { it.groupName.ifBlank { "默认分组" } == cleanGroup }
            .mapNotNull { config ->
                val subGroupName = config.subGroupName.trim()
                if (subGroupName.isBlank() || subGroupName == "默认") return@mapNotNull null
                val existing = currentGroups.firstOrNull {
                    it.groupName.ifBlank { "默认分组" } == cleanGroup &&
                        it.subGroupName.trim() == subGroupName
                }
                existing ?: VoiceGroup(groupName = cleanGroup, subGroupName = subGroupName)
            }
        val mergedGroups = (currentGroups + configGroups)
            .distinctBy { it.groupName.ifBlank { "默认分组" } to it.subGroupName.ifBlank { "默认" } }
        val orderIndex = cleanOrder.withIndex().associate { it.value to it.index }
        val reordered = mergedGroups.sortedWith(
            compareBy<VoiceGroup> { if (it.groupName.ifBlank { "默认分组" } == cleanGroup) 0 else 1 }
                .thenBy { if (it.groupName.ifBlank { "默认分组" } == cleanGroup) orderIndex[it.subGroupName.trim()] ?: Int.MAX_VALUE else Int.MAX_VALUE }
                .thenBy { it.groupName.ifBlank { "默认分组" } }
                .thenBy { it.subGroupName.ifBlank { "默认" } }
        )
        saveGroups(context, reordered)
    }

    fun selectedConfigGroup(context: Context): Pair<String, String>? {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val groupName = prefs.getString(KEY_SELECTED_GROUP, "").orEmpty()
        if (groupName.isBlank()) return null
        return groupName to ""
    }

    fun selectConfigGroup(context: Context, groupName: String, subGroupName: String) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_GROUP, groupName)
            .putString(KEY_SELECTED_SUB_GROUP, "")
            .apply()
    }

    fun clearSelectedConfigGroup(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SELECTED_GROUP)
            .remove(KEY_SELECTED_SUB_GROUP)
            .apply()
    }

    fun listPlugins(context: Context, recovering: Boolean = false): List<VoicePlugin> {
        cachedPlugins?.let { return it }
        val appContext = context.applicationContext
        if (!seedingBuiltInPlugins) {
            ensureBuiltInVoicePluginPresets(appContext)
        }
        val raw = appContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLUGINS, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrNull()
        // 主数据损坏：与 listConfigs 同一套恢复策略，先从备份恢复主文件再重读。
        // 不恢复的话，后续任何一次写操作会把空列表写回磁盘，全部插件永久清零。
        if (array == null && raw.isNotBlank() && raw != "[]" && !recovering) {
            val backup = readBackup(context, KEY_PLUGINS)
            if (backup != null) {
                AppLog.put("[J阅读声音引擎] 插件数据损坏（JSON 解析失败），已从备份恢复")
                appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PLUGINS, backup).commit()
                invalidatePluginCache()
                cachedPlugins = null
                return listPlugins(context, recovering = true)
            }
            AppLog.put("[J阅读声音引擎] 插件数据损坏且无可用备份，只能返回空列表")
        }
        val result = buildList {
            for (index in 0 until (array?.length() ?: 0)) {
                val obj = array?.optJSONObject(index) ?: continue
                val pluginBody = importedPluginBody(obj)
                val urlTemplate = pluginBody.first
                val code = pluginBody.second
                val name = firstText(obj, "name", "displayName", "pluginName").trim()
                val pluginId = importedPluginId(obj, name)
                if (urlTemplate.isBlank() && code.isBlank()) continue
                if (name.isBlank() && pluginId.isBlank()) continue
                add(
                    VoicePlugin(
                        id = obj.optString("id").ifBlank { pluginId.ifBlank { UUID.randomUUID().toString() } },
                        name = name.ifBlank { pluginId.ifBlank { "未命名插件" } },
                        pluginId = pluginId.ifBlank { obj.optString("id") },
                        pluginGroupId = firstText(obj, "pluginGroupId", "groupId").trim(),
                        pluginGroupName = firstText(obj, "pluginGroupName", "groupName").trim(),
                        author = firstText(obj, "author", "creator").trim(),
                        version = firstText(obj, "version", "versionName").trim(),
                        iconUrl = firstText(obj, "iconUrl", "icon").trim(),
                        code = code,
                        defVarsJson = jsonText(obj, "defVars", "vars").ifBlank { "{}" },
                        userVarsJson = jsonText(obj, "userVars").ifBlank { "{}" },
                        method = obj.optString("method", "GET").ifBlank { "GET" }.uppercase(),
                        urlTemplate = urlTemplate,
                        headersText = firstText(obj, "headersText", "headers"),
                        bodyTemplate = firstText(obj, "bodyTemplate", "body", "requestBody"),
                        responseAudioPath = firstText(obj, "responseAudioPath", "audioPath", "audioUrlPath"),
                        enabled = obj.optBoolean("enabled", obj.optBoolean("isEnabled", true)),
                        speed = obj.optDouble("speed", 1.0).toFloat(),
                        volume = obj.optDouble("volume", 1.0).toFloat(),
                        pitch = obj.optDouble("pitch", 1.0).toFloat(),
                        postSpeed = obj.optDouble("postSpeed", PostAudioParams.FOLLOW.toDouble()).toFloat(),
                        postVolume = obj.optDouble("postVolume", PostAudioParams.FOLLOW.toDouble()).toFloat(),
                        postPitch = obj.optDouble("postPitch", PostAudioParams.FOLLOW.toDouble()).toFloat(),
                    )
                )
            }
        }
        cachedPlugins = result
        return result
    }

    private fun ensureBuiltInVoicePluginPresets(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_BUILTIN_PLUGIN_BUNDLE_12_SEEDED, false)) {
            val bytes = runCatching {
                appContext.assets.open(BUILTIN_PLUGIN_BUNDLE_12_ASSET).use { it.readBytes() }
            }.getOrNull()
            if (bytes != null) {
                seedingBuiltInPlugins = true
                try {
                    val count = runCatching { importPluginsFromPackageBytes(appContext, bytes) }.getOrDefault(0)
                    if (count > 0 || hasPlugin(prefs.getString(KEY_PLUGINS, "[]").orEmpty(), BUILTIN_PLUGIN_BUNDLE_12_KEY)) {
                        prefs.edit().putBoolean(KEY_BUILTIN_PLUGIN_BUNDLE_12_SEEDED, true).apply()
                        AppLog.putDebug("[J阅读声音引擎] 已预置全套声音插件：$count 个")
                    }
                } finally {
                    seedingBuiltInPlugins = false
                }
            }
        }

        cleanupBundledLegacyPlugins(appContext)
    }

    private fun cleanupBundledLegacyPlugins(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        var removedPluginCount = 0
        val plugins = runCatching { JSONArray(prefs.getString(KEY_PLUGINS, "[]").orEmpty()) }.getOrNull()
        if (plugins != null) {
            val keptPlugins = JSONArray()
            for (index in 0 until plugins.length()) {
                val item = plugins.optJSONObject(index) ?: continue
                val pluginId = firstText(item, "pluginId", "id", "sourcePluginId").trim()
                val name = firstText(item, "name", "displayName", "pluginName").trim()
                val isLegacyLocalSound = pluginId.equals("bendiyinxiao", ignoreCase = true) &&
                    name.contains("本地音效", ignoreCase = true)
                val isLegacyMaoxiangVv = pluginId.equals("maoxiang.tts.v3", ignoreCase = true) &&
                    (
                        name.contains("猫箱", ignoreCase = true) ||
                            name.contains("VV大军", ignoreCase = true)
                    )
                if (isLegacyLocalSound || isLegacyMaoxiangVv) {
                    removedPluginCount++
                } else {
                    keptPlugins.put(item)
                }
            }
            if (removedPluginCount > 0) {
                prefs.edit().putString(KEY_PLUGINS, keptPlugins.toString()).apply()
            }
        }

        var migratedConfigCount = 0
        val configs = runCatching { JSONArray(prefs.getString(KEY_CONFIGS, "[]").orEmpty()) }.getOrNull()
        if (configs != null) {
            val migratedConfigs = JSONArray()
            for (index in 0 until configs.length()) {
                val item = configs.optJSONObject(index) ?: continue
                val pluginId = firstText(item, "pluginId", "sourcePluginId").trim()
                when {
                    pluginId.equals("bendiyinxiao", ignoreCase = true) -> {
                        item.put("pluginId", "jread.audios.1064")
                        if (item.optString("voice").isBlank()) {
                            item.put("voice", "all_voices")
                        }
                        migratedConfigCount++
                    }
                    pluginId.equals("maoxiang.tts.v3", ignoreCase = true) -> {
                        item.put("pluginId", BUILTIN_PLUGIN_BUNDLE_12_KEY)
                        migratedConfigCount++
                    }
                }
                migratedConfigs.put(item)
            }
            if (migratedConfigCount > 0) {
                prefs.edit().putString(KEY_CONFIGS, migratedConfigs.toString()).apply()
            }
        }

        if (removedPluginCount > 0 || migratedConfigCount > 0) {
            AppLog.putDebug(
                "[J阅读声音引擎] 已清理旧内置插件：plugins=$removedPluginCount, migratedConfigs=$migratedConfigCount"
            )
        }
    }

    private fun hasPlugin(raw: String, keyword: String): Boolean {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (firstText(item, "pluginId", "id", "sourcePluginId") == keyword) return true
            if (firstText(item, "name", "displayName", "pluginName").contains(keyword, ignoreCase = true)) return true
        }
        return false
    }

    fun savePlugin(context: Context, plugin: VoicePlugin) {
        val plugins = listPlugins(context).toMutableList()
        val index = plugins.indexOfFirst {
            it.id == plugin.id || (plugin.pluginId.isNotBlank() && it.pluginId == plugin.pluginId)
        }
        if (index >= 0) {
            plugins[index] = plugin
        } else {
            plugins += plugin
        }
        savePlugins(context, plugins)
    }

    fun deletePlugin(context: Context, id: String) {
        // 只删除插件本身，保留各配置的 pluginId 绑定：
        // 重新导入同 ID 插件后配置自动恢复可用（findPlugin 按 id/pluginId 双匹配），
        // 无需逐个手动重绑。插件缺失期间合成会报"缺少插件"并走音色兜底，不影响其他配置。
        savePlugins(context, listPlugins(context).filterNot { it.id == id })
    }

    fun importPluginsFromJson(context: Context, raw: String): Int {
        val text = raw.trim()
        if (text.isBlank()) return 0
        val array = when {
            text.startsWith("[") -> JSONArray(text)
            else -> {
                val obj = JSONObject(text)
                obj.optJSONArray("plugins")
                    ?: obj.optJSONArray("items")
                    ?: JSONArray().put(obj)
            }
        }
        val plugins = listPlugins(context).toMutableList()
        var count = 0
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val pluginBody = importedPluginBody(obj)
            val urlTemplate = pluginBody.first
            val code = pluginBody.second
            if (urlTemplate.isBlank() && code.isBlank()) continue
            val name = firstText(obj, "name", "displayName", "pluginName").trim().ifBlank { "未命名插件" }
            val plugin = VoicePlugin(
                id = obj.optString("id").trim().ifBlank { UUID.randomUUID().toString() },
                name = name,
                pluginId = importedPluginId(obj, name),
                pluginGroupId = firstText(obj, "pluginGroupId", "groupId").trim(),
                pluginGroupName = firstText(obj, "pluginGroupName", "groupName").trim(),
                author = firstText(obj, "author", "creator").trim(),
                version = firstText(obj, "version", "versionName").trim(),
                iconUrl = firstText(obj, "iconUrl", "icon").trim(),
                code = code,
                defVarsJson = jsonText(obj, "defVars", "vars").ifBlank { "{}" },
                userVarsJson = jsonText(obj, "userVars").ifBlank { "{}" },
                method = obj.optString("method", "GET").ifBlank { "GET" }.uppercase(),
                urlTemplate = urlTemplate,
                headersText = firstText(obj, "headersText", "headers"),
                bodyTemplate = firstText(obj, "bodyTemplate", "body", "requestBody"),
                responseAudioPath = firstText(obj, "responseAudioPath", "audioPath", "audioUrlPath"),
                enabled = obj.optBoolean("enabled", obj.optBoolean("isEnabled", true)),
            )
            val existingIndex = plugins.indexOfFirst {
                it.id == plugin.id || (plugin.pluginId.isNotBlank() && it.pluginId == plugin.pluginId)
            }
            if (existingIndex >= 0) {
                // 升级导入（同 id/pluginId 已存在）：继承旧版已设置的用户变量。
                // 导出的分享文件默认不含 userVars，整体覆盖会把用户设置的变量清空；
                // 以旧版变量为底，文件里显式携带的键（includeUserVars 导出的）才覆盖。
                val existing = plugins[existingIndex]
                plugins[existingIndex] = plugin.copy(
                    userVarsJson = mergeUserVars(existing.userVarsJson, plugin.userVarsJson)
                )
            } else {
                plugins += plugin
            }
            count++
        }
        if (count > 0) savePlugins(context, plugins)
        return count
    }

    private fun importedPluginBody(obj: JSONObject): Pair<String, String> {
        val rawUrl = firstText(obj, "urlTemplate", "url", "requestUrl", "audioUrl").trim()
        val rawCode = firstText(obj, "code", "pluginCode").trim()
        return if (rawUrl.startsWith("@js:", ignoreCase = true)) {
            "" to rawUrl.substringAfter("@js:").trimStart()
        } else {
            rawUrl to rawCode
        }
    }

    /**
     * 合并导入插件的用户变量：以旧版已设置的变量为底，导入文件里显式携带的键覆盖。
     * 分享导出默认不含 userVars（避免泄露会话令牌等），因此升级导入时若不合并，
     * 用户原来设置的变量会被空对象整体覆盖而丢失。
     */
    private fun mergeUserVars(oldJson: String, incomingJson: String): String {
        val incoming = runCatching { JSONObject(incomingJson) }.getOrNull()
        if (incoming == null || incoming.length() == 0) return oldJson.ifBlank { "{}" }
        val merged = runCatching { JSONObject(oldJson) }.getOrElse { JSONObject() }
        for (key in incoming.keys()) {
            merged.put(key, incoming.opt(key))
        }
        return merged.toString()
    }

    private fun importedPluginId(obj: JSONObject, name: String): String {
        val explicit = firstText(obj, "pluginId", "id", "sourcePluginId").trim()
        val contentType = firstText(obj, "contentType", "type").trim()
        val cleanName = name.trim()
        if (
            explicit.isBlank() &&
            (
                contentType.contains("maoxiang", ignoreCase = true) ||
                    cleanName.contains("猫箱-VV") ||
                    cleanName.contains("VV大军")
            )
        ) {
            return "maoxiang.tts.v3"
        }
        // 插件未声明 id/pluginId 时，返回一个全新的随机 UUID 作为 id。
        // 之前这里返回空字符串 ""，导致两次导入同名插件时 id 都为 ""，
        // importPluginsFromJson / importPluginsFromPackageBytes 的去重比较
        // (it.id == plugin.id) 命中，第二份会静默覆盖第一份，最终在插件列表里
        // 只显示一个。改为每次生成独立 UUID，可保留多次导入的同名插件。
        if (explicit.isBlank()) {
            return UUID.randomUUID().toString()
        }
        return explicit
    }

    fun exportPluginsJson(context: Context, includeUserVars: Boolean = false): String {
        val plugins = listPlugins(context)
        return JSONObject()
            .put("format", "jread_voice_plugin_bundle")
            .put("version", 1)
            .put("plugins", JSONArray().apply {
                plugins.forEach { put(pluginToJson(it, includeUserVars)) }
            })
            .toString(2)
    }

    /**
     * 导出**单个**插件，格式与整包导出完全一致（plugins 数组里只有这一个），
     * 因此导出结果可以直接走既有的导入流程，不需要额外的解析分支。
     *
     * @param pluginId 插件 id
     * @param includeUserVars 是否带上用户变量（默认 false，与整包导出一致，避免把会话令牌等写进分享文件）
     * @return 插件不存在时返回 null
     */
    fun exportPluginJson(
        context: Context,
        pluginId: String,
        includeUserVars: Boolean = false,
    ): String? {
        val plugin = listPlugins(context).firstOrNull { it.id == pluginId } ?: return null
        return JSONObject()
            .put("format", "jread_voice_plugin_bundle")
            .put("version", 1)
            .put("plugins", JSONArray().put(pluginToJson(plugin, includeUserVars)))
            .toString(2)
    }

    fun exportConfigsJson(context: Context): String {
        val appContext = context.applicationContext
        return JSONObject()
            .put("format", "jread_voice_config_bundle")
            .put("version", 1)
            .put("groups", JSONArray().apply {
                listGroups(appContext).forEach { put(voiceGroupToJson(it)) }
            })
            .put("configs", JSONArray().apply {
                listConfigs(appContext).forEach { put(voiceConfigToJson(it)) }
            })
            .toString(2)
    }

    fun importPluginsFromPackageBytes(context: Context, bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        val rawText = bytes.toString(Charsets.UTF_8).trimStart()
        if (rawText.startsWith("{") || rawText.startsWith("[")) {
            return importPluginsFromJson(context, rawText)
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var fallbackJson: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/').lowercase()
                if (!name.endsWith(".json")) continue
                val text = zip.readBytes().toString(Charsets.UTF_8)
                if (name == "jread_voice_plugins.json" || name == "plugins.json") {
                    return importPluginsFromJson(context, text)
                }
                fallbackJson = fallbackJson ?: text
            }
            fallbackJson?.let { return importPluginsFromJson(context, it) }
        }
        return 0
    }

    fun importConfigsFromJson(context: Context, raw: String, useStableId: Boolean = false): Int {
        val text = raw.trim()
        if (text.isBlank()) return 0
        val array = when {
            text.startsWith("[") -> JSONArray(text)
            else -> {
                val obj = JSONObject(text)
                obj.optJSONArray("configs")
                    ?: obj.optJSONArray("groups")
                    ?: obj.optJSONArray("voices")
                    ?: obj.optJSONArray("items")
                    ?: JSONArray().put(obj)
            }
        }
        importGroupConfigsFromJson(context, array, useStableId)?.let { return it }
        val imported = mutableListOf<VoiceConfig>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val config = parseVoiceConfig(obj) ?: continue
            val id = if (useStableId) stableConfigId(config) else UUID.randomUUID().toString()
            // 导入配置列表时彻底不关联/校验插件：原样保留 config.pluginId，不再调用
            // resolveImportedConfigPluginId（该函数每次都要解析全部插件 JSON，配置数量 × 插件数量
            // 会造成 O(N²) 级耗时，文件越大、插件越多越慢）。插件关联由用户导入后自行处理。
            imported += config.copy(id = id)
        }
        if (imported.isEmpty()) return 0
        // 外部数据可能含未规范化 voiceTag，标记迁移需重查
        migrationDone = false
        if (useStableId) {
            // 用稳定 id 去重：删除现有列表中与导入项稳定 id 相同的旧条目，再整体写入（覆盖而非追加）。
            // 注意：单次导入的 JSON 内若包含多条稳定 id 相同的配置（例如导出时同一角色被重复写入），
            // 这里也要对 imported 自身按 id 去重（保留最后一条），否则 existing += imported 会把这些
            // 重复 id 的配置一并追加，导致存储里出现多条相同 id 的配置。后续按 id 删除/替换时会误伤
            // 同 id 的其它配置（如删除禁用项时把启用项一并删掉）。
            val stableIds = imported.map { it.id }.toSet()
            val deduped = imported.distinctBy { it.id }
            val existing = listConfigs(context, ensureBuiltIns = false)
                .filterNot { it.id in stableIds }
                .toMutableList()
            existing += deduped
            saveConfigs(context, existing)
        } else {
            // 批量一次性写入：imported 放在 existing 之后，由 saveConfigs 的 last-wins 去重保证
            // 相同 id 时新配置覆盖旧配置（useStableId=false 时 imported 为随机 id，等同原逐条追加语义）。
            // 原逐条 saveConfig 每次都触发一次「读全量 + 写全量」，配置数量大时退化为 O(N²)；
            // 改为单次 saveConfigs 后写盘次数从 N 次降为 1 次。
            val existing = listConfigs(context, ensureBuiltIns = false)
            saveConfigs(context, existing + imported.distinctBy { it.id })
        }
        return imported.size
    }

    private fun importGroupConfigsFromJson(context: Context, array: JSONArray, useStableId: Boolean = false): Int? {
        val entries = buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val group = obj.optJSONObject("group") ?: continue
                add(group to (obj.optJSONArray("list") ?: JSONArray()))
            }
        }
        if (entries.isEmpty()) return null

        val groups = entries.map { it.first }
        val groupById = groups.associateBy { it.optLong("groupId", it.optLong("id", 0L)) }

        fun groupPath(group: JSONObject): List<String> {
            val names = mutableListOf<String>()
            var current: JSONObject? = group
            val visited = mutableSetOf<Long>()
            while (current != null) {
                val id = current.optLong("groupId", current.optLong("id", 0L))
                if (id != 0L && !visited.add(id)) break
                current.optString("name").trim().takeIf { it.isNotBlank() }?.let {
                    names += it
                }
                val parentId = current.optLong("parentGroupId", 0L)
                current = groupById[parentId]
            }
            return names.asReversed()
        }

        val importedConfigs = listConfigs(context).toMutableList()
        val importedGroups = listGroups(context).toMutableList()
        fun upsertGroup(group: VoiceGroup) {
            val normalized = group.copy(
                groupName = group.groupName.trim().ifBlank { "默认分组" },
                subGroupName = group.subGroupName.trim(),
                thirdGroupName = group.thirdGroupName.trim(),
            )
            val existingIndex = importedGroups.indexOfFirst {
                it.groupName.ifBlank { "默认分组" } == normalized.groupName &&
                    it.subGroupName.ifBlank { "默认" } == normalized.subGroupName.ifBlank { "默认" } &&
                    it.thirdGroupName.ifBlank { "默认" } == normalized.thirdGroupName.ifBlank { "默认" }
            }
            if (existingIndex >= 0) {
                val existing = importedGroups[existingIndex]
                importedGroups[existingIndex] = normalized.copy(
                    id = existing.id,
                    displayName = normalized.displayName.ifBlank { existing.displayName },
                )
            } else {
                importedGroups += normalized
            }
        }
        fun upsertConfig(config: VoiceConfig) {
            val normalized = normalizeVoiceConfigForStorage(
                config.copy(groupName = config.groupName.trim().ifBlank { "默认分组" })
            )
            val stableId = if (useStableId) stableConfigId(normalized) else normalized.id
            val normalizedWithId = normalized.copy(id = stableId)
            upsertGroup(
                VoiceGroup(
                    groupName = normalizedWithId.groupName,
                    subGroupName = normalizedWithId.subGroupName,
                    thirdGroupName = normalizedWithId.thirdGroupName,
                )
            )
            val existingIndex = importedConfigs.indexOfFirst {
                it.id == stableId
            }
            if (existingIndex >= 0) {
                importedConfigs[existingIndex] = normalizedWithId
            } else {
                importedConfigs += normalizedWithId
            }
            if (normalizedWithId.enabled) {
                importedConfigs.replaceAll {
                    if (it.id != stableId && sameVoiceTag(it.voiceTag, normalizedWithId.voiceTag)) {
                        it.copy(enabled = false)
                    } else {
                        it
                    }
                }
            }
        }

        var count = 0
        entries.forEach { (group, list) ->
            val path = groupPath(group)
            val groupName = path.firstOrNull().orEmpty()
            val subGroupName = path.drop(1).joinToString(" / ")
            for (index in 0 until list.length()) {
                val item = list.optJSONObject(index) ?: continue
                val groupedNames = resolveImportedConfigGroupNames(groupName, subGroupName, item)
                if (groupedNames.first.isNotBlank()) {
                    upsertGroup(
                        VoiceGroup(
                            groupName = groupedNames.first,
                            subGroupName = groupedNames.second,
                            thirdGroupName = groupedNames.third,
                        )
                    )
                }
                val withGroup = JSONObject(item.toString())
                    .put("groupName", groupedNames.first)
                    .put("subGroupName", groupedNames.second)
                    .put("thirdGroupName", groupedNames.third)
                val config = parseVoiceConfig(withGroup) ?: continue
                upsertConfig(
                    config.copy(
                        pluginId = resolveImportedConfigPluginId(context, config, withGroup),
                    ),
                )
                count++
            }
        }
        if (count > 0) {
            saveGroups(context, importedGroups)
            saveConfigs(context, importedConfigs)
            // 外部分组导入同样标记迁移需重查
            migrationDone = false
        }
        return count
    }

    private fun resolveImportedConfigGroupNames(
        groupName: String,
        subGroupName: String,
        item: JSONObject,
    ): Triple<String, String, String> {
        val cleanGroupName = groupName.trim()
        val cleanSubGroupName = subGroupName.trim()
        val cleanThirdGroupName = ""
        val isTimbrePool = cleanGroupName.contains(BUILTIN_VIVI_GROUP_KEY, ignoreCase = true) ||
            cleanGroupName.contains(BUILTIN_VIVI_LEGACY_GROUP_KEY, ignoreCase = true)
        if (!isTimbrePool) {
            return Triple(cleanGroupName, cleanSubGroupName, cleanThirdGroupName)
        }
        val nestedConfig = item.optJSONObject("config") ?: item
        val speechRule = nestedConfig.optJSONObject("speechRule") ?: item.optJSONObject("speechRule")
        val displayName = firstText(item, "displayName", "label", "name")
        val voiceTag = normalizeVoiceTag(
            rawTag = firstText(item, "voiceTag", "tag", "tagName")
                .ifBlank { firstText(speechRule, "voiceTag", "tag") }
                .ifBlank { displayName },
            rawTagName = firstText(item, "tagName")
                .ifBlank { firstText(speechRule, "tagName", "displayName", "name") }
                .ifBlank { displayName }
        )
        val source = voiceTag.ifBlank { displayName }
            .trim()
            .trimVoiceTagBrackets()
            .replace(Regex("\\s+"), "")
        val match = Regex("^(男性|女性)(儿童|少年|青年|中年|老年)/(.+?)(\\d{1,3})?$").find(source)
            ?: Regex("^(男|女)(儿童|少年|青年|中年|老年)/(.+?)(\\d{1,3})?$").find(source)
        if (match == null) return Triple(BUILTIN_VIVI_GROUP_KEY, cleanSubGroupName, cleanThirdGroupName)
        val gender = when (match.groupValues[1]) {
            "男", "男性" -> "男性"
            "女", "女性" -> "女性"
            else -> return Triple(BUILTIN_VIVI_GROUP_KEY, cleanSubGroupName, cleanThirdGroupName)
        }
        val age = match.groupValues[2]
        val feature = match.groupValues.getOrNull(3).orEmpty().trim().ifBlank { "通用" }
        return Triple(BUILTIN_VIVI_GROUP_KEY, "$gender$age", "$gender$age/$feature")
    }

    private fun resolveImportedConfigPluginId(
        context: Context,
        config: VoiceConfig,
        sourceObj: JSONObject,
    ): String {
        if (config.pluginId.isBlank()) return ""
        val plugins = listPlugins(context)
        plugins.firstOrNull { it.id == config.pluginId || it.pluginId == config.pluginId }?.let {
            return config.pluginId
        }
        val nestedConfig = sourceObj.optJSONObject("config") ?: sourceObj
        val source = nestedConfig.optJSONObject("source") ?: sourceObj.optJSONObject("source")
        val sourceData = source?.optJSONObject("data")
        val candidates = linkedSetOf<String>().apply {
            add(config.pluginId)
            add(firstText(sourceObj, "pluginName", "sourcePluginName", "ttsName"))
            add(firstText(nestedConfig, "pluginName", "sourcePluginName", "ttsName"))
            add(firstText(source, "pluginName", "sourcePluginName", "ttsName", "name", "displayName"))
            add(firstText(sourceData, "pluginName", "sourcePluginName", "ttsName"))
        }.map { it.trim() }.filter { it.isNotBlank() }
        plugins.firstOrNull { plugin ->
            candidates.any { candidate ->
                candidate == plugin.id ||
                    candidate == plugin.pluginId ||
                    candidate.equals(plugin.name, ignoreCase = true)
            }
        }?.let { plugin ->
            return plugin.id
        }
        return config.pluginId
    }

    fun synthesizeLineAudio(
        context: Context,
        text: String,
        pointerJson: String,
        requestId: String,
    ): ByteArray? {
        val pointer = runCatching { JSONObject(pointerJson) }.getOrNull() ?: JSONObject()
        val voiceTag = pointer.optString("voiceTag")
            .ifBlank { pointer.optString("jreadVoiceTag") }
            .ifBlank { pointer.optString("scriptVoiceTag") }
            .ifBlank { "旁白" }
            .trim()
        // 用 findPlayableConfig 而不是 findConfig：前者对旁白标签会优先取「本书专属旁白」，
        // 且不做启用过滤（旁白标签默认只启用一个，其余禁用态但仍可能被本书指定）。
        // 之前这里用 findConfig，会把用户指定的禁用旁白过滤掉，导致选了却始终用默认旁白。
        val config = findPlayableConfig(context, voiceTag) ?: run {
            AppLog.putDebug("[J阅读声音引擎] 未找到可用配置: voiceTag=$voiceTag")
            TtsSynthLog.put(
                TtsSynthLog.Level.ERROR,
                "合成失败 [$voiceTag] 未找到可用配置｜文本：${text.takeTextForLog()}"
            )
            return null
        }
        val resolvedVoiceTag = config.voiceTag.ifBlank { voiceTag }
        if (!sameVoiceTag(voiceTag, resolvedVoiceTag)) {
            AppLog.putDebug(
                "[J阅读声音引擎] voiceTag 配置不可用，已兜底: requested=$voiceTag actual=$resolvedVoiceTag"
            )
            Log.w(TAG, "Fallback voice config: requested=$voiceTag actual=$resolvedVoiceTag")
        }
        // 直接返回合成字节，不再落盘到 line_audio 缓存（该缓存 key 含随机 requestId，
        // 永不命中，且清理函数从未被调用，纯属移植残留、只会无限占空间）
        return requestAudioToFile(context, config, text, resolvedVoiceTag, pointer, requestId)
    }

    /**
     * 用**已经选好的配置**合成，不再按标签重新挑选。
     *
     * 供 JS 桥使用：它在 synthesize 入口已按 voiceTag + 本书专属旁白选好配置，
     * 若再走 [synthesizeLineAudio] 内部重选，预设旁白（通常是禁用态）会被
     * `filter { it.enabled }` 过滤掉，导致「选了旁白却始终用默认的」。
     */
    fun synthesizeLineAudioWithConfig(
        context: Context,
        config: VoiceConfig,
        text: String,
        pointerJson: String,
        requestId: String,
    ): ByteArray? {
        val pointer = runCatching { JSONObject(pointerJson) }.getOrNull() ?: JSONObject()
        val voiceTag = pointer.optString("voiceTag")
            .ifBlank { pointer.optString("jreadVoiceTag") }
            .ifBlank { pointer.optString("scriptVoiceTag") }
            .ifBlank { "旁白" }
            .trim()
        val resolvedVoiceTag = config.voiceTag.ifBlank { voiceTag }
        if (!sameVoiceTag(voiceTag, resolvedVoiceTag)) {
            AppLog.putDebug(
                "[J阅读声音引擎] 使用指定配置合成: requested=$voiceTag actual=$resolvedVoiceTag"
            )
        }
        return requestAudioToFile(context, config, text, resolvedVoiceTag, pointer, requestId)
    }

    fun synthesizeConfigLineAudio(
        context: Context,
        config: VoiceConfig,
        text: String,
        pointerJson: String,
        requestId: String,
    ): ByteArray {
        val pointer = runCatching { JSONObject(pointerJson) }.getOrNull() ?: JSONObject()
        val voiceTag = config.voiceTag.ifBlank {
            pointer.optString("voiceTag")
                .ifBlank { pointer.optString("jreadVoiceTag") }
                .ifBlank { "旁白" }
        }.trim()
        // 直接返回合成字节，不再落盘到 line_audio 缓存（移植残留、永不命中且从未清理）
        return requestAudioToFile(context, config, text, voiceTag, pointer, requestId)
    }

    /** 按 id 取配置（不做启用/可用性过滤，配置 id 唯一） */
    fun findConfigById(context: Context, configId: String): VoiceConfig? {
        if (configId.isBlank()) return null
        return listConfigs(context, ensureBuiltIns = false).firstOrNull { it.id == configId }
    }

    /**
     * 与合成完全一致地按标签选出实际生效的配置。
     * 供 JS 桥在合成后对「同一条配置」施加条目/分组/插件级后处理参数。
     *
     * 旁白标签额外优先用「本书专属旁白」：每本书可在朗读界面指定自己的旁白音色，
     * 指定后合成旁白段时直接用那条，不再走下面的默认挑选。
     */
    fun findPlayableConfig(context: Context, voiceTag: String): VoiceConfig? {
        if (isNarrationTag(voiceTag)) {
            val presetId = io.legado.app.model.ReadBook.book?.config?.ttsNarrationConfigId
            if (!presetId.isNullOrBlank()) {
                // findConfigById 不做启用/可用性过滤：旁白标签默认只启用一个，
                // 其余是禁用态但仍可被选为本书旁白；只有配置真的被删除才返回 null，
                // 此时自然回退到下面的默认逻辑。
                findConfigById(context, presetId)?.let { return it }
            }
        }
        return findConfig(context, voiceTag)
    }

    /**
     * 是否「旁白」标签。口径与 [normalizeVoiceTag] / [voiceTagPool] 保持一致，
     * 保证配置列表、脚本标签、这里的判定三者相同。
     */
    fun isNarrationTag(voiceTag: String): Boolean =
        voiceTagPool(normalizeVoiceTag(voiceTag).ifBlank { voiceTag })?.key == "narration"

    /**
     * 列出所有旁白标签的配置（含禁用态），供「本书旁白」选择弹窗使用。
     * 旁白默认只启用一个，其余都是禁用态，所以这里不能按启用状态过滤。
     */
    fun listNarrationConfigs(context: Context): List<VoiceConfig> =
        listConfigs(context).filter { isNarrationTag(it.voiceTag) }
            .sortedWith(
                compareByDescending<VoiceConfig> { it.enabled }
                    .thenBy { it.displayName.ifBlank { it.voiceTag } }
            )

    private fun findConfig(context: Context, voiceTag: String): VoiceConfig? {
        val configs = listConfigs(context).filter { it.enabled }        // 预取插件列表一次，传给 isPlayable/resolveRequest，避免每个候选配置都重新 listPlugins
        val plugins = listPlugins(context)
        val normalizedTag = normalizeVoiceTag(voiceTag)
        val exact = findConfigIn(configs, normalizedTag)
        if (exact != null && exact.isPlayable(plugins)) return exact
        if (exact != null) {
            AppLog.putDebug(
                "[J阅读声音引擎] 跳过不可用配置: voiceTag=${exact.voiceTag} " +
                    "pluginId=${exact.pluginId} voice=${exact.voice}"
            )
        }
        val wantedPool = voiceTagPool(normalizedTag)?.key
        if (wantedPool != null) {
            configs.firstOrNull { config ->
                config.isPlayable(plugins) && voiceTagPool(config.voiceTag)?.key == wantedPool
            }?.let { return it }
            if (wantedPool == "narration") {
                AppLog.putDebug("[J阅读声音引擎] 旁白配置不可用，拒绝兜底到角色音色")
                return null
            }
        }
        return configs.firstOrNull { it.voiceTag == "旁白" && it.isPlayable(plugins) }
            ?: configs.firstOrNull { it.isPlayable(plugins) }
    }

    private fun findConfigIn(configs: List<VoiceConfig>, voiceTag: String): VoiceConfig? {
        if (voiceTag.isBlank()) return configs.firstOrNull { it.voiceTag == "旁白" }
        val wantedAliases = voiceTagAliases(voiceTag)
        return configs.firstOrNull { config ->
            voiceTagAliases(config.voiceTag).any { it in wantedAliases }
        }
    }

    private fun VoiceConfig.isPlayable(plugins: List<VoicePlugin>): Boolean {
        val plugin = plugins.firstOrNull { it.enabled && (it.id == pluginId || it.pluginId == pluginId) }
        if (plugin != null && plugin.code.isNotBlank() && urlTemplate.isBlank()) {
            return voice.isNotBlank()
        }
        return resolveRequest(plugin).urlTemplate.isNotBlank()
    }

    private fun requestAudioToFile(
        context: Context,
        config: VoiceConfig,
        text: String,
        voiceTag: String,
        pointer: JSONObject,
        requestId: String,
    ): ByteArray {
        var lastError: Exception? = null
        val startedAt = android.os.SystemClock.elapsedRealtime()
        AppLog.putDebug(
            "[声音合成][J阅读引擎] 音频合成开始 requestId=$requestId " +
                "tag=$voiceTag text=${text.take(36)}"
        )
        // 同时写进「TTS 引擎插件 → 日志」页：只记合成相关的关键节点，
        // 不把 AppLog 里那些与应用无关的日志混进来
        TtsSynthLog.put(
            TtsSynthLog.Level.DEBUG,
            "合成开始 [$voiceTag] ${text.takeTextForLog()}"
        )
        repeat(SYNTHESIS_RETRY_COUNT) { attempt ->
            try {
                val attemptStartedAt = android.os.SystemClock.elapsedRealtime()
                AppLog.putDebug(
                    "[声音合成][J阅读引擎] 音频合成尝试 ${attempt + 1}/$SYNTHESIS_RETRY_COUNT " +
                        "requestId=$requestId tag=$voiceTag text=${text.take(36)}"
                )
                val bytes = requestAudioToFileOnce(context, config, text, voiceTag, pointer, requestId)
                if (bytes.isNotEmpty()) {
                    val cost = android.os.SystemClock.elapsedRealtime() - startedAt
                    AppLog.putDebug(
                        "[声音合成][J阅读引擎] 音频合成成功 requestId=$requestId " +
                            "attempt=${attempt + 1}/$SYNTHESIS_RETRY_COUNT " +
                            "cost=${cost}ms " +
                            "thisCost=${android.os.SystemClock.elapsedRealtime() - attemptStartedAt}ms " +
                            "size=${bytes.size} tag=$voiceTag"
                    )
                    TtsSynthLog.put(
                        TtsSynthLog.Level.SUCCESS,
                        "合成成功 [$voiceTag] ${TtsSynthLog.sizeToReadable(bytes.size)} / ${cost}ms"
                    )
                    return bytes
                }
                throw IllegalStateException("J阅读声音合成后音频为空")
            } catch (e: Exception) {
                lastError = e
                val reason = e.localizedMessage ?: e.javaClass.simpleName
                if (attempt >= SYNTHESIS_RETRY_COUNT - 1) {
                    AppLog.putDebug(
                        "[声音合成][J阅读引擎] 音频合成最终失败，已达到 $SYNTHESIS_RETRY_COUNT 次 " +
                            "requestId=$requestId cost=${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                            "tag=$voiceTag error=${reason} " +
                            "text=${text.take(36)}"
                    )
                    TtsSynthLog.put(
                        TtsSynthLog.Level.ERROR,
                        "合成失败 [$voiceTag] $reason｜文本：${text.takeTextForLog()}"
                    )
                    throw e
                }
                val nextAttempt = attempt + 2
                Log.w(
                    TAG,
                    "Request audio failed, retry=$nextAttempt/$SYNTHESIS_RETRY_COUNT " +
                        "tag=$voiceTag requestId=$requestId textLen=${text.length}",
                    e
                )
                AppLog.putDebug(
                    "[声音合成][J阅读引擎] 合成失败，准备重试 $nextAttempt/$SYNTHESIS_RETRY_COUNT " +
                        "tag=$voiceTag error=${reason} " +
                        "text=${text.take(36)}"
                )
                TtsSynthLog.put(
                    TtsSynthLog.Level.WARN,
                    "合成失败，重试 $nextAttempt/$SYNTHESIS_RETRY_COUNT [$voiceTag] $reason"
                )
                Thread.sleep(800L * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("J阅读声音合成失败")
    }

    /** 日志里展示的文本片段：过长截断，换行压成空格，避免一条日志占好几行 */
    private fun String.takeTextForLog(): String {
        val singleLine = replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length > 40) "${singleLine.take(40)}…" else singleLine
    }

    private fun requestAudioToFileOnce(
        context: Context,
        config: VoiceConfig,
        text: String,
        voiceTag: String,
        pointer: JSONObject,
        requestId: String,
    ): ByteArray {
        val plugin = findPlugin(context, config.pluginId)
        Log.i(
            TAG,
            "Request audio: tag=$voiceTag, plugin=${plugin?.name.orEmpty()}, voice=${config.voice}, textLen=${text.length}"
        )
        if (plugin != null && plugin.code.isNotBlank() && config.urlTemplate.isBlank()) {
            require(config.voice.isNotBlank()) {
                "J阅读声音配置未选择插件音色: voiceTag=${config.voiceTag.ifBlank { voiceTag }}，请在单项 TTS 里读取插件音色列表并选择 voice"
            }
            val bytes = JReadVoicePluginRuntime.synthesize(
                context = context.applicationContext,
                plugin = plugin,
                config = config,
                text = text,
                voiceTag = voiceTag,
                pointer = pointer,
            )
            if (bytes.isEmpty()) {
                throw IllegalStateException(
                    "J阅读插件返回空音频: voiceTag=${config.voiceTag.ifBlank { voiceTag }} " +
                        "plugin=${plugin.name.ifBlank { plugin.pluginId.ifBlank { plugin.id } }} voice=${config.voice}"
                )
            }
            if (isClearlyInvalidPluginAudio(text, bytes)) {
                throw IllegalStateException(
                    "J阅读插件返回异常小音频: bytes=${bytes.size} textLen=${text.length} " +
                        "voiceTag=${config.voiceTag.ifBlank { voiceTag }} " +
                        "plugin=${plugin.name.ifBlank { plugin.pluginId.ifBlank { plugin.id } }} voice=${config.voice}"
                )
            }
            // 注意：不在这里做「快节奏（去静音）」裁剪。
            // 此时音频还没经过后处理（Sonic 语速/音调/音量），静音段的时长是「未变速」的
            // 原始时长；若先裁再变速，语速调快后停顿被成倍压缩，衔接处会因过度压缩而失真。
            // 去静音统一放到朗读落盘前（HttpReadAloudService 的转码封装处）执行，
            // 那时转 WAV + 参数调节都已完成，裁剪基于成品时长，听感速度才统一。
            return bytes
        }

        val request = config.resolveRequest(plugin)
        require(request.urlTemplate.isNotBlank()) { "J阅读声音配置缺少插件或 URL" }
        val url = render(request.urlTemplate, text, voiceTag, config, plugin, pointer, requestId)
        val headers = parseHeaders(render(request.headersText, text, voiceTag, config, plugin, pointer, requestId))
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        if (request.method.equals("POST", ignoreCase = true)) {
            val body = render(request.bodyTemplate, text, voiceTag, config, plugin, pointer, requestId)
            val mediaType = headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.toMediaTypeOrNull()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()
            builder.post(body.toRequestBody(mediaType))
        } else {
            builder.get()
        }

        val result = synthesisHttpClient().newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("J阅读声音请求失败: HTTP ${response.code} ${response.message}")
            }
            val bytes = response.body.bytes()
            val audioBytes = extractAudioBytes(bytes, request.responseAudioPath)
            if (audioBytes.isEmpty()) {
                throw IllegalStateException("J阅读声音请求返回空音频")
            }
            if (isClearlyInvalidPluginAudio(text, audioBytes)) {
                throw IllegalStateException(
                    "J阅读声音请求返回异常小音频: bytes=${audioBytes.size} textLen=${text.length}"
                )
            }
            // 同上：去静音不在合成阶段做，交给朗读落盘前的统一封装处
            audioBytes
        }
        return result
    }

    private fun isClearlyInvalidPluginAudio(text: String, bytes: ByteArray): Boolean {
        val cleanText = text.replace(
            Regex("[\\s“”‘’「」『』（）()【】\\[\\]{}<>《》、，。！？!?；：…—\\-.,;:'\"]"),
            ""
        )
        return cleanText.length >= 6 && bytes.size <= 1024
    }

    private fun extractAudioBytes(bytes: ByteArray, audioPath: String): ByteArray {
        if (audioPath.isBlank()) return bytes
        val text = bytes.toString(Charsets.UTF_8)
        val root = JSONObject(text)
        val value = readJsonPath(root, audioPath.trim()).trim()
        if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
            val request = Request.Builder().url(value).get().build()
            return synthesisHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("J阅读声音二段音频下载失败: HTTP ${response.code}")
                }
                response.body.bytes()
            }
        }
        return Base64.decode(value.substringAfter("base64,", value), Base64.DEFAULT)
    }

    private fun synthesisHttpClient() = okHttpClient.newBuilder()
        .readTimeout(SYNTHESIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(SYNTHESIS_TIMEOUT_MS + 10_000L, TimeUnit.MILLISECONDS)
        .build()

    private fun render(
        template: String,
        text: String,
        voiceTag: String,
        config: VoiceConfig,
        plugin: VoicePlugin?,
        pointer: JSONObject,
        requestId: String,
    ): String {
        fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        val roleName = pointer.optString("roleName").ifBlank { pointer.optString("jreadRoleName") }
        val emotion = pointer.optString("emotion").ifBlank { pointer.optString("jreadEmotion") }
        val effectText = pointer.optString("effectText").ifBlank { pointer.optString("jreadEffectText") }
        val voice = config.voice.ifBlank { voiceTag }
        return template
            .replace("{text}", enc(text))
            .replace("{rawText}", text)
            .replace("{voiceTag}", enc(voiceTag))
            .replace("{rawVoiceTag}", voiceTag)
            .replace("{voice}", enc(voice))
            .replace("{rawVoice}", voice)
            .replace("{roleName}", enc(roleName))
            .replace("{rawRoleName}", roleName)
            .replace("{emotion}", enc(emotion))
            .replace("{rawEmotion}", emotion)
            .replace("{effectText}", enc(effectText))
            .replace("{rawEffectText}", effectText)
            .replace("{speed}", (config.speed * pluginSynthFactor(plugin?.speed ?: 1f)).toString())
            .replace("{volume}", (config.volume * pluginSynthFactor(plugin?.volume ?: 1f)).toString())
            .replace("{pitch}", (config.pitch * pluginSynthFactor(plugin?.pitch ?: 1f)).toString())
            .replace("{requestId}", enc(requestId))
    }

    private data class RequestTemplate(
        val method: String,
        val urlTemplate: String,
        val headersText: String,
        val bodyTemplate: String,
        val responseAudioPath: String,
    )

    private fun VoiceConfig.resolveRequest(plugin: VoicePlugin?): RequestTemplate {
        return RequestTemplate(
            method = method.takeIf { urlTemplate.isNotBlank() } ?: plugin?.method ?: method,
            urlTemplate = urlTemplate.ifBlank { plugin?.urlTemplate.orEmpty() },
            headersText = headersText.ifBlank { plugin?.headersText.orEmpty() },
            bodyTemplate = bodyTemplate.ifBlank { plugin?.bodyTemplate.orEmpty() },
            responseAudioPath = responseAudioPath.ifBlank { plugin?.responseAudioPath.orEmpty() },
        )
    }

    private fun findPlugin(context: Context, pluginId: String): VoicePlugin? {
        if (pluginId.isBlank()) return null
        // 优先用缓存索引 O(1) 查找，避免每次都 listPlugins 全量解析
        cachedPluginIndex?.let { index ->
            index[pluginId]?.let { return it }
        }
        val plugins = listPlugins(context)
        // 构建索引供后续复用
        val index = plugins.filter { it.enabled }
            .associateBy { it.id }
            .toMutableMap()
        plugins.filter { it.enabled }.forEach { p ->
            if (p.pluginId.isNotBlank()) index.putIfAbsent(p.pluginId, p)
        }
        cachedPluginIndex = index
        return index[pluginId]
    }

    private fun readJsonPath(root: JSONObject, path: String): String {
        var current: Any? = root
        path.split('.')
            .filter { it.isNotBlank() }
            .forEach { part ->
                current = when (val node = current) {
                    is JSONObject -> node.opt(part)
                    is JSONArray -> part.toIntOrNull()?.let { node.opt(it) }
                    else -> null
                }
            }
        return current?.toString().orEmpty()
    }

    fun saveGroups(context: Context, groups: List<VoiceGroup>) {
        val json = JSONArray().apply {
            groups.distinctBy { Triple(it.groupName, it.subGroupName, it.thirdGroupName) }.forEach {
                if (it.groupName.isBlank()) return@forEach
                put(voiceGroupToJson(it))
            }
        }.toString()
        // commit 而非 apply，理由同 saveConfigs
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_GROUPS, json)
            .commit()
        writeBackup(context, KEY_GROUPS, json)
        invalidateGroupCache()
    }

    private fun voiceGroupToJson(group: VoiceGroup): JSONObject {
        return JSONObject()
            .put("id", group.id)
            .put("groupName", group.groupName)
            .put("subGroupName", group.subGroupName)
            .put("thirdGroupName", group.thirdGroupName)
            .put("displayName", group.displayName)
            .put("postSpeed", group.postSpeed)
            .put("postVolume", group.postVolume)
            .put("postPitch", group.postPitch)
            .put("sortOrder", group.sortOrder)
    }

    private fun parseVoiceGroups(raw: String): List<VoiceGroup> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val groupName = obj.optString("groupName").trim()
                if (groupName.isBlank()) continue
                add(
                    VoiceGroup(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        groupName = groupName,
                        subGroupName = obj.optString("subGroupName").trim(),
                        thirdGroupName = obj.optString("thirdGroupName").trim(),
                        displayName = obj.optString("displayName").trim(),
                        postSpeed = obj.optDouble("postSpeed", PostAudioParams.FOLLOW.toDouble()).toFloat(),
                        postVolume = obj.optDouble("postVolume", PostAudioParams.FOLLOW.toDouble()).toFloat(),
                        postPitch = obj.optDouble("postPitch", PostAudioParams.FOLLOW.toDouble()).toFloat(),
                        sortOrder = obj.optInt("sortOrder", 0),
                    )
                )
            }
        }
    }

    private fun migrateExistingTimbreVoicePoolGroups(context: Context) {
        if (migratingTimbreVoiceGroups || seedingBuiltInConfigs || migrationDone) return
        val appContext = context.applicationContext
        migratingTimbreVoiceGroups = true
        try {
            val prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val rawConfigs = prefs.getString(KEY_CONFIGS, "[]").orEmpty()
            val configArray = runCatching { JSONArray(rawConfigs) }.getOrNull() ?: return
            val migratedArray = JSONArray()
            var configChanged = false

            for (index in 0 until configArray.length()) {
                val item = configArray.optJSONObject(index)
                if (item == null) {
                    migratedArray.put(configArray.opt(index))
                    continue
                }
                val config = parseVoiceConfig(item)
                if (config == null) {
                    migratedArray.put(item)
                    continue
                }
                // 仅规范化 voiceTag，不再用标签强制覆盖分组
                val normalizedTag = normalizedVoiceTagForConfig(config)
                val rawVoiceTag = firstText(item, "voiceTag", "tag", "tagName")
                if (rawVoiceTag != normalizedTag) {
                    configChanged = true
                    migratedArray.put(
                        JSONObject(item.toString()).put("voiceTag", normalizedTag)
                    )
                } else {
                    migratedArray.put(item)
                }
            }

            if (configChanged) {
                prefs.edit().putString(KEY_CONFIGS, migratedArray.toString()).apply()
                AppLog.putDebug("[J阅读声音引擎] 已规范化标签格式")
            }
            migrationDone = true
        } finally {
            migratingTimbreVoiceGroups = false
        }
    }

    private fun VoiceGroup.isTimbreVoicePoolGroup(): Boolean {
        return groupName.contains(BUILTIN_VIVI_GROUP_KEY, ignoreCase = true) ||
            groupName.contains(BUILTIN_VIVI_LEGACY_GROUP_KEY, ignoreCase = true)
    }

    /**
     * 基于内容生成稳定 id：相同角色（plugin + voiceTag + 分组路径）无论导入多少次都得到同一 id，
     * 从而在保存时按 id 覆盖而非追加，从根本上去除重复。
     */
    private fun stableConfigId(config: VoiceConfig): String {
        val raw = buildString {
            append(config.pluginId.trim())
            append('\u0000')
            append(config.voiceTag.trim())
            append('\u0000')
            append(config.groupName.trim())
            append('\u0000')
            append(config.subGroupName.trim())
            append('\u0000')
            append(config.thirdGroupName.trim())
        }
        return "vc_" + raw.hashCode().let { if (it == Int.MIN_VALUE) 0 else it }.toString(36)
    }

    private fun normalizeVoiceConfigForStorage(config: VoiceConfig): VoiceConfig {
        // 完全按用户手动设置的分组保存，不再用标签强制覆盖分组
        val normalizedVoiceTag = normalizedVoiceTagForConfig(config)
        return config.copy(
            voiceTag = normalizedVoiceTag,
            groupName = config.groupName.trim(),
            subGroupName = config.subGroupName.trim(),
            thirdGroupName = config.thirdGroupName.trim(),
        )
    }

    private fun normalizedVoiceTagForConfig(config: VoiceConfig): String {
        return normalizeVoiceTag(config.voiceTag)
            .ifBlank { config.voiceTag.trim().trimVoiceTagBrackets() }
            .ifBlank { normalizeVoiceTag(config.displayName) }
            .ifBlank { config.displayName.trim().trimVoiceTagBrackets() }
    }

    private fun timbreConfigGroupForVoiceTag(rawTag: String): TimbreConfigGroupTarget? {
        val normalized = normalizeVoiceTag(rawTag)
            .ifBlank { rawTag.trim().trimVoiceTagBrackets() }
            .replace(Regex("\\s+"), "")
        // 旧格式: 男青年01、少女01、男童01 等
        val oldMatch = Regex("^(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)(\\d{1,3})?$")
            .matchEntire(normalized)
        if (oldMatch != null) {
            val shortAge = oldMatch.groupValues[1]
            return TimbreConfigGroupTarget(
                groupName = "发音人",
                subGroupName = shortAge,
                thirdGroupName = shortAge,
            )
        }
        // 带斜杠的旧格式: 男/男青年01
        val slashMatch = Regex("^(男|女)/(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊)(\\d{1,3})?$")
            .matchEntire(normalized)
        if (slashMatch != null) {
            val age = slashMatch.groupValues[2]
            return TimbreConfigGroupTarget(
                groupName = "发音人",
                subGroupName = age,
                thirdGroupName = age,
            )
        }
        // 主角
        val leadMatch = Regex("^主角(男主|女主)(\\d{1,3})?$").matchEntire(normalized)
        if (leadMatch != null) {
            val role = leadMatch.groupValues[1]
            return TimbreConfigGroupTarget(
                groupName = "主角",
                subGroupName = role,
                thirdGroupName = role,
            )
        }
        // 女主01/男主01 简写
        val simpleLeadMatch = Regex("^(男主|女主)(\\d{1,3})?$").matchEntire(normalized)
        if (simpleLeadMatch != null) {
            val role = simpleLeadMatch.groupValues[1]
            return TimbreConfigGroupTarget(
                groupName = "主角",
                subGroupName = role,
                thirdGroupName = role,
            )
        }
        return null
    }

    /**
     * 配置表进程内写锁：所有"读-改-写"事务的串行化点。
     * 配置以整串 JSON 存储，任何一条修改都要全量读+全量写；
     * 无锁时并发修改会用各自的旧快照互相覆盖（后写胜出），造成丢改动。
     * 持锁后并发请求排队且各自基于最新状态累加——语义等价于数据库的单行 UPDATE。
     * synchronized 可重入：事务函数内部调用 saveConfigs 不会死锁。
     */
    private val configWriteLock = Any()

    // ===== 数据备份 =====
    // 配置/插件以单条大 JSON 存在 SharedPreferences 里。这个文件一旦损坏
    // （大 JSON 落盘中途被杀、磁盘满等），读取会拿到不合法的 JSON——如果不做处理，
    // 后续任何一次写操作都会把「空列表」全量写回磁盘，用户导入的全部插件与配置
    // 就此永久清零，只剩内置预置重新种入，表现正是「用着用着全部恢复成内置」。
    // 因此每次成功落盘后同步写一份备份（应用私有目录、临时文件+rename 原子写），
    // 读取发现损坏时自动从备份恢复并写回主文件。

    private fun backupFile(context: Context, key: String): File =
        File(File(context.applicationContext.filesDir, "jread_voice_backup"), "$key.json")

    private fun writeBackup(context: Context, key: String, json: String) {
        runCatching {
            val dst = backupFile(context, key)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, "${dst.name}.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(dst)) {
                dst.writeText(json)
                tmp.delete()
            }
        }.onFailure {
            AppLog.putDebug("[J阅读声音引擎] 备份写入失败：$key ${it.message}")
        }
    }

    private fun readBackup(context: Context, key: String): String? =
        runCatching {
            backupFile(context, key).takeIf { it.isFile && it.length() > 0 }?.readText()
        }.getOrNull()

    private fun saveConfigs(context: Context, configs: List<VoiceConfig>) {
        synchronized(configWriteLock) {
            // 防御：不允许存储里出现多条相同 id 的配置（例如历史导入产生的重复副本、
            // 或导出再导入时同 id 被多次写入）。相同 id 视为同一配置，保留最后一条，
            // 确保 id 可作为唯一主键使用，避免按 id 删除/替换时误伤同 id 的其它配置。
            val deduped = configs.withIndex().groupBy(
                keySelector = { if (it.value.id.isBlank()) "blank_${it.index}" else it.value.id },
                valueTransform = { it.value }
            ).map { (_, list) -> list.last() }
            val json = JSONArray().apply { deduped.forEach { put(voiceConfigToJson(it)) } }.toString()
            // commit 而非 apply：本文件所有调用方都在 Dispatchers.IO，同步落盘的代价
            // 可接受；而 apply 是异步的，配置列表越大（大插件 + 数千项音色 = 数 MB 的
            // JSON，每次小改动都全量重写）落盘越慢，「操作完成 → 真正落盘」之间的
            // 丢失窗口就越大——进程在这个窗口被杀，本次改动静默丢失，数据回滚到
            // 上一次成功落盘的状态，用户看到的正是「用着用着恢复成内置」。
            val ok = context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIGS, json)
                .commit()
            if (!ok) AppLog.put("[J阅读声音引擎] 配置写盘失败（commit 返回 false）")
            writeBackup(context, KEY_CONFIGS, json)
            invalidateConfigCache()
            // 预热：磁盘内容与 deduped 一致，后续读取直接命中缓存，跳过全量重解析
            cachedConfigs = deduped
        }
    }

    /**
     * 按 id 集合一次性删除多条配置，仅全量写盘一次（避免逐条 deleteConfig 的 N 次全量读写）。
     * 返回被删除后剩余的配置列表。
     */
    fun deleteConfigs(context: Context, ids: Set<String>): List<VoiceConfig> {
        val remaining = listConfigs(context).filterNot { ids.contains(it.id) }
        saveConfigs(context, remaining)
        return remaining
    }

    private fun voiceConfigToJson(config: VoiceConfig): JSONObject {
        return JSONObject()
            .put("id", config.id)
            .put("voiceTag", config.voiceTag)
            .put("groupName", config.groupName)
            .put("subGroupName", config.subGroupName)
            .put("thirdGroupName", config.thirdGroupName)
            .put("displayName", config.displayName)
            .put("pluginId", config.pluginId)
            .put("locale", config.locale)
            .put("voice", config.voice)
            .put("previewText", config.previewText)
            .put("data", safeJsonObject(config.dataJson))
            .put("speed", config.speed)
            .put("volume", config.volume)
            .put("pitch", config.pitch)
            .put("method", config.method)
            .put("urlTemplate", config.urlTemplate)
            .put("headersText", config.headersText)
            .put("bodyTemplate", config.bodyTemplate)
            .put("responseAudioPath", config.responseAudioPath)
            .put("enabled", config.enabled)
            .put("postSpeed", config.postSpeed)
            .put("postVolume", config.postVolume)
            .put("postPitch", config.postPitch)
            .put("sortOrder", config.sortOrder)
    }

    private fun savePlugins(context: Context, plugins: List<VoicePlugin>) {
        val json = JSONArray().apply {
            plugins.forEach { put(pluginToJson(it, includeUserVars = true)) }
        }.toString()
        // commit 而非 apply，理由同 saveConfigs：消除大 JSON 异步落盘的丢失窗口
        val ok = context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PLUGINS, json)
            .commit()
        if (!ok) AppLog.put("[J阅读声音引擎] 插件写盘失败（commit 返回 false）")
        writeBackup(context, KEY_PLUGINS, json)
        invalidatePluginCache()
    }

    private fun pluginToJson(plugin: VoicePlugin, includeUserVars: Boolean): JSONObject {
        return JSONObject()
            .put("id", plugin.id)
            .put("name", plugin.name)
            .put("pluginId", plugin.pluginId)
            .put("pluginGroupId", plugin.pluginGroupId)
            .put("pluginGroupName", plugin.pluginGroupName)
            .put("author", plugin.author)
            .put("version", plugin.version)
            .put("iconUrl", plugin.iconUrl)
            .put("code", plugin.code)
            .put("defVars", safeJsonObject(plugin.defVarsJson))
            .put("userVars", if (includeUserVars) safeJsonObject(plugin.userVarsJson) else JSONObject())
            .put("method", plugin.method)
            .put("urlTemplate", plugin.urlTemplate)
            .put("headersText", plugin.headersText)
            .put("bodyTemplate", plugin.bodyTemplate)
            .put("responseAudioPath", plugin.responseAudioPath)
            .put("enabled", plugin.enabled)
            .put("speed", plugin.speed)
            .put("volume", plugin.volume)
            .put("pitch", plugin.pitch)
            .put("postSpeed", plugin.postSpeed)
            .put("postVolume", plugin.postVolume)
            .put("postPitch", plugin.postPitch)
    }

    private fun parseVoiceConfig(obj: JSONObject): VoiceConfig? {
        val nestedConfig = obj.optJSONObject("config") ?: obj
        val speechRule = nestedConfig.optJSONObject("speechRule") ?: obj.optJSONObject("speechRule")
        val source = nestedConfig.optJSONObject("source") ?: obj.optJSONObject("source")
        val sourceData = source?.optJSONObject("data")
        val voiceTag = normalizeVoiceTag(
            rawTag = firstText(obj, "voiceTag", "tag", "tagName")
                .ifBlank { firstText(speechRule, "voiceTag", "tag") }
                .ifBlank { firstText(obj, "displayName", "label", "name") },
            rawTagName = firstText(obj, "tagName")
                .ifBlank { firstText(speechRule, "tagName", "displayName", "name") }
                .ifBlank { firstText(obj, "displayName", "label", "name") }
        )
        val urlTemplate = firstText(obj, "urlTemplate", "url", "requestUrl", "audioUrl")
            .ifBlank { firstText(nestedConfig, "urlTemplate", "url", "requestUrl", "audioUrl") }
            .ifBlank { firstText(source, "urlTemplate", "url", "requestUrl", "audioUrl") }
            .trim()
        val pluginId = firstText(obj, "pluginId", "plugin", "sourcePluginId")
            .ifBlank { firstText(nestedConfig, "pluginId", "plugin", "sourcePluginId") }
            .ifBlank { firstText(source, "pluginId", "plugin", "sourcePluginId") }
            .trim()
        if (voiceTag.isBlank() || (urlTemplate.isBlank() && pluginId.isBlank())) return null
        return VoiceConfig(
            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
            voiceTag = voiceTag,
            groupName = firstText(obj, "groupName", "group", "configGroupName")
                .ifBlank { firstText(nestedConfig, "groupName", "group", "configGroupName") }
                .ifBlank { firstText(source, "groupName", "group", "configGroupName") }
                .trim(),
            subGroupName = firstText(obj, "subGroupName", "subGroup", "childGroupName", "voiceGroupName", "category")
                .ifBlank { firstText(nestedConfig, "subGroupName", "subGroup", "childGroupName", "voiceGroupName", "category") }
                .ifBlank { firstText(source, "subGroupName", "subGroup", "childGroupName", "voiceGroupName", "category") }
                .trim(),
            thirdGroupName = firstText(obj, "thirdGroupName", "thirdGroup", "thirdGroupName", "ageGroupName")
                .ifBlank { firstText(nestedConfig, "thirdGroupName", "thirdGroup", "thirdGroupName", "ageGroupName") }
                .ifBlank { firstText(source, "thirdGroupName", "thirdGroup", "thirdGroupName", "ageGroupName") }
                .trim(),
            displayName = firstText(obj, "displayName", "label", "name")
                .ifBlank { firstText(sourceData, "voiceName", "name", "label") }
                .ifBlank { firstText(source, "voiceName", "name", "label") }
                .trim(),
            pluginId = pluginId,
            locale = firstText(obj, "locale", "language", "lang")
                .ifBlank { firstText(source, "locale", "language", "lang") }
                .trim(),
            voice = firstText(obj, "voice", "voiceName", "speaker", "speakerId")
                .ifBlank { firstText(source, "voice", "voiceName", "speaker", "speakerId") }
                .ifBlank { firstText(sourceData, "voice", "voiceName", "speaker", "speakerId") }
                .trim(),
            previewText = firstText(obj, "previewText", "testText", "sampleText")
                .ifBlank { firstText(source, "previewText", "testText", "sampleText") }
                .trim(),
            dataJson = jsonText(obj, "data")
                .ifBlank { jsonText(source, "data") }
                .ifBlank { "{}" },
            speed = firstNumber(obj, source, "speed") ?: 1f,
            volume = firstNumber(obj, source, "volume") ?: 1f,
            pitch = firstNumber(obj, source, "pitch") ?: 1f,
            method = firstText(obj, "method")
                .ifBlank { firstText(nestedConfig, "method") }
                .ifBlank { firstText(source, "method") }
                .ifBlank { "GET" }
                .uppercase(),
            urlTemplate = urlTemplate,
            headersText = firstText(obj, "headersText", "headers")
                .ifBlank { firstText(nestedConfig, "headersText", "headers") }
                .ifBlank { firstText(source, "headersText", "headers") },
            bodyTemplate = firstText(obj, "bodyTemplate", "body", "requestBody")
                .ifBlank { firstText(nestedConfig, "bodyTemplate", "body", "requestBody") }
                .ifBlank { firstText(source, "bodyTemplate", "body", "requestBody") },
            responseAudioPath = firstText(obj, "responseAudioPath", "audioPath", "audioUrlPath")
                .ifBlank { firstText(nestedConfig, "responseAudioPath", "audioPath", "audioUrlPath") }
                .ifBlank { firstText(source, "responseAudioPath", "audioPath", "audioUrlPath") },
            enabled = obj.optBoolean("enabled", obj.optBoolean("isEnabled", true)),
            postSpeed = firstNumber(obj, source, "postSpeed") ?: PostAudioParams.FOLLOW,
            postVolume = firstNumber(obj, source, "postVolume") ?: PostAudioParams.FOLLOW,
            postPitch = firstNumber(obj, source, "postPitch") ?: PostAudioParams.FOLLOW,
            sortOrder = firstNumber(obj, source, "sortOrder")?.toInt() ?: 0,
        )
    }

    fun normalizeVoiceTag(rawTag: String, rawTagName: String = ""): String {
        val tag = rawTag.trim().trimVoiceTagBrackets()
        val tagName = rawTagName.trim().trimVoiceTagBrackets()
        listOf(tagName, tag).forEach { value ->
            normalizeScriptStyleVoiceTag(value)?.let { return it }
        }
        return when {
            tagName == "旁白" -> "旁白"
            tag == "旁白" -> "旁白"
            tag.equals("narration", ignoreCase = true) -> "旁白"
            tag.equals("旁白", ignoreCase = true) -> "旁白"
            tag.isNotBlank() && !tag.equals("dialogue", ignoreCase = true) -> tag
            tagName.isNotBlank() && !tagName.equals("dialogue", ignoreCase = true) -> tagName
            else -> ""
        }
    }

    private fun voiceTagAliases(raw: String): Set<String> {
        val normalized = normalizeVoiceTag(raw).ifBlank { raw.trim().trimVoiceTagBrackets() }
        if (normalized.isBlank()) return emptySet()
        val aliases = linkedSetOf(normalized)
        if (normalized == "旁白") {
            aliases += "narration"
            return aliases
        }
        val slashMatch = Regex("^(男|女)/(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊)(\\d{1,3})?$")
            .matchEntire(normalized)
        if (slashMatch != null) {
            val gender = slashMatch.groupValues[1]
            val age = slashMatch.groupValues[2]
            val index = slashMatch.groupValues[3]
            aliases += "$age$index"
            if (age == "特殊") aliases += "${age}$gender$index"
            return aliases
        }
        normalizeScriptStyleVoiceTag(normalized)?.let { aliases += it }
        return aliases
    }

    private fun sameVoiceTag(left: String, right: String): Boolean {
        val leftAliases = voiceTagAliases(left)
        val rightAliases = voiceTagAliases(right)
        return leftAliases.isNotEmpty() && rightAliases.isNotEmpty() && leftAliases.any { it in rightAliases }
    }

    private fun voiceTagPool(raw: String): VoiceTagPool? {
        val normalized = normalizeVoiceTag(raw).ifBlank { raw.trim().trimVoiceTagBrackets() }
        val compact = normalized.replace(Regex("\\s+"), "")
        if (compact.isBlank() || compact == "待分配") return null
        if (compact == "旁白" || compact.equals("narration", ignoreCase = true)) {
            return VoiceTagPool("narration", "旁白")
        }
        Regex("^括号([1-4])$").matchEntire(compact)?.let {
            return VoiceTagPool("bracket-${it.groupValues[1]}", "括号${it.groupValues[1]}")
        }
        if (compact.startsWith("localSound", ignoreCase = true)) {
            return VoiceTagPool("local-sound", "本地音效")
        }
        // 旧格式: 男青年01、少女01、男童01 等
        Regex("^(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)(\\d{1,3})?$")
            .matchEntire(compact)?.let {
                return VoiceTagPool(it.groupValues[1], it.groupValues[1])
            }
        // 主角: 男主01、女主01
        Regex("^(男主|女主)(\\d{1,3})?$").matchEntire(compact)?.let {
            return VoiceTagPool("lead-${it.groupValues[1]}", "主角 ${it.groupValues[1]}")
        }
        Regex("^主角(男主|女主)\\d{1,3}$").matchEntire(compact)?.let {
            val role = it.groupValues[1]
            return VoiceTagPool("lead-$role", "主角 $role")
        }
        // 带斜杠的旧格式
        Regex("^(男|女)/(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊)\\d{0,3}$")
            .matchEntire(compact)?.let {
                val age = it.groupValues[2]
                return VoiceTagPool(age, age)
            }
        return null
    }

    private fun timbreStyleVoiceTagPool(raw: String): VoiceTagPool? {
        val normalized = normalizeTimbreStyleVoiceTag(raw.replace(Regex("\\s+"), ""))
            ?: return null
        val match = Regex("^(男性|女性)(儿童|少年|青年|中年|老年)/(.+?)(\\d{2,3})$")
            .matchEntire(normalized)
            ?: return null
        val key = "${match.groupValues[1]}${match.groupValues[2]}/${match.groupValues[3]}"
        return VoiceTagPool(key, key)
    }

    private fun normalizeScriptStyleVoiceTag(value: String): String? {
        if (value.isBlank()) return null
        val clean = value.trim().trimVoiceTagBrackets()
        val compact = clean.replace(Regex("\\s+"), "")
        if (compact.startsWith("localSound", ignoreCase = true)) return compact
        // 旧格式直接保留: 男/男青年01 → 男青年01
        Regex("^(男|女)/(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊)(\\d{1,3})?$")
            .matchEntire(compact)
            ?.let { match ->
                val age = match.groupValues[2]
                val index = match.groupValues[3].padStart(2, '0')
                if (age == "特殊") return "特殊${match.groupValues[1]}$index"
                return "$age$index"
            }
        // 旧格式: 男青年01、少女01 等，补齐编号为2位
        Regex("^(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女|男主|女主)(\\d{1,3})?$")
            .matchEntire(compact)
            ?.let { match ->
                val shortAge = match.groupValues[1]
                val index = match.groupValues[2].padStart(2, '0')
                return "$shortAge$index"
            }
        // Timbre 格式迁移为旧格式: 男性青年/通用01 → 男青年01
        normalizeTimbreStyleVoiceTag(compact)?.let { timbre ->
            val timbreMatch = Regex("^(男性|女性)(儿童|少年|青年|中年|老年)/(.+?)(\\d{2,3})$").matchEntire(timbre)
            if (timbreMatch != null) {
                val gender = timbreMatch.groupValues[1]
                val age = timbreMatch.groupValues[2]
                val index = timbreMatch.groupValues[4]
                val oldAge = when (gender) {
                    "男性" -> when (age) { "儿童" -> "男童"; "少年" -> "少年"; "青年" -> "男青年"; "中年" -> "男中年"; "老年" -> "男老年"; else -> null }
                    "女性" -> when (age) { "儿童" -> "女童"; "少年" -> "少女"; "青年" -> "女青年"; "中年" -> "女中年"; "老年" -> "女老年"; else -> null }
                    else -> null
                } ?: return null
                return "$oldAge$index"
            }
        }
        return null
    }

    private fun old286VoiceTagToTimbreStyleVoiceTag(value: String): String? {
        val compact = value.trim().trimVoiceTagBrackets().replace(Regex("\\s+"), "")
        val match = Regex("^(?:([男女])/)?(女童|男童|少女|少年|女青年|男青年|女中年|男中年|女老年|男老年)(\\d{1,3})$")
            .matchEntire(compact)
            ?: return null
        val target = when (match.groupValues[2]) {
            "女童" -> "女性儿童"
            "男童" -> "男性儿童"
            "少女" -> "女性少年"
            "少年" -> "男性少年"
            "女青年" -> "女性青年"
            "男青年" -> "男性青年"
            "女中年" -> "女性中年"
            "男中年" -> "男性中年"
            "女老年" -> "女性老年"
            "男老年" -> "男性老年"
            else -> return null
        }
        val index = match.groupValues[3].toIntOrNull()?.coerceAtLeast(1) ?: return null
        return "$target/通用${index.toString().padStart(2, '0')}"
    }

    private fun normalizeTimbreStyleVoiceTag(value: String): String? {
        Regex("^(男性|女性)(儿童|少年|青年|中年|老年)/(.+?)[（(]风格([一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾\\d]+)[）)]$")
            .matchEntire(value)
            ?.let { return it.toTimbreStyleVoiceTag() }
        Regex("^(男|女)(儿童|少年|青年|中年|老年)/(.+?)[（(]风格([一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾\\d]+)[）)]$")
            .matchEntire(value)
            ?.let { return it.toTimbreStyleVoiceTag() }
        val withoutStyle = value
            .replace(Regex("[（(]风格[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾\\d]+[）)]$"), "")
        val match = Regex("^(男性|女性)(儿童|少年|青年|中年|老年)/(.+?)(\\d{1,3}|[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾])?$")
            .matchEntire(withoutStyle)
            ?: Regex("^(男|女)(儿童|少年|青年|中年|老年)/(.+?)(\\d{1,3}|[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾])?$")
                .matchEntire(withoutStyle)
            ?: Regex("^(男|女)(儿童|少年|青年|中年|老年)/(.+?)(\\d{1,3}|[一二三四五六七八九十壹贰叁肆伍陆柒捌玖拾])?$")
                .matchEntire(value)
            ?: return null
        return match.toTimbreStyleVoiceTag()
    }

    private fun MatchResult.toTimbreStyleVoiceTag(): String? {
        val gender = when (groupValues[1]) {
            "男", "男性" -> "男性"
            "女", "女性" -> "女性"
            else -> return null
        }
        val age = groupValues[2]
        val feature = groupValues[3].trim('（', '(', '）', ')')
            .substringBefore("风格")
            .trim()
        if (feature.isBlank()) return null
        val suffix = normalizeTimbreStyleIndex(groupValues.getOrNull(4).orEmpty())
        return "$gender$age/$feature$suffix"
    }

    private fun normalizeTimbreStyleIndex(value: String): String {
        val clean = value.trim()
        if (clean.isBlank()) return "01"
        clean.toIntOrNull()?.let { return it.coerceAtLeast(1).toString().padStart(2, '0') }
        return when (clean) {
            "一", "壹" -> "01"
            "二", "贰" -> "02"
            "三", "叁" -> "03"
            "四", "肆" -> "04"
            "五", "伍" -> "05"
            "六", "陆" -> "06"
            "七", "柒" -> "07"
            "八", "捌" -> "08"
            "九", "玖" -> "09"
            "十", "拾" -> "10"
            else -> "01"
        }
    }

    private fun String.trimVoiceTagBrackets(): String {
        return removePrefix("【")
            .removeSuffix("】")
            .removePrefix("[")
            .removeSuffix("]")
            .trim()
    }

    private fun firstNumber(primary: JSONObject?, secondary: JSONObject?, name: String): Float? {
        listOf(primary, secondary).forEach { obj ->
            if (obj == null || !obj.has(name)) return@forEach
            val raw = obj.opt(name)
            when (raw) {
                is Number -> return raw.toFloat()
                is String -> raw.toFloatOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun parseHeaders(headersText: String): Map<String, String> {
        return headersText.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) return@mapNotNull null
                val name = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                if (name.isBlank() || value.isBlank()) null else name to value
            }
            .toMap()
    }

    private fun firstText(obj: JSONObject?, vararg names: String): String {
        if (obj == null) return ""
        for (name in names) {
            val raw = obj.opt(name) ?: continue
            val value = when (raw) {
                is JSONObject, is JSONArray -> raw.toString()
                else -> raw.toString()
            }.trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun jsonText(obj: JSONObject?, vararg names: String): String {
        if (obj == null) return ""
        for (name in names) {
            val raw = obj.opt(name) ?: continue
            val value = when (raw) {
                is JSONObject, is JSONArray -> raw.toString()
                else -> raw.toString()
            }.trim()
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun safeJsonObject(raw: String): JSONObject {
        return runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse { JSONObject() }
    }
}
