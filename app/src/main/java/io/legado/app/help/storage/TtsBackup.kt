package io.legado.app.help.storage

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.data.entities.AiProfileTemplate
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.data.entities.BgmAIPrompt
import io.legado.app.data.entities.CharacterProfile
import io.legado.app.data.entities.TtsScript
import io.legado.app.help.EngineDataDir
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TTS / AI 相关配置的独立备份与恢复。
 *
 * 这部分数据(朗读脚本、TTS 插件、混元太极、AI 生图/人设/章节修复模板、BGM AI 配置、
 * 角色档案)是本项目后来新增的, 原版阅读的备份格式里没有对应字段, 混进主备份 zip
 * 会导致与原版阅读互不兼容。所以这里单独打一个 zip, 由「备份与恢复」页的专用入口
 * 导出/导入, 与主备份完全隔离。
 *
 * 后来又新增了场景特效开关、插件登录自动填充、朗读/AI 偏好设置、AI 音效索引、
 * 角色立绘等数据, 分散在独立 SharedPreferences、默认 SharedPreferences
 * 和新的外部目录里, 旧实现没有覆盖, 这里一并纳入。
 * 背景音乐关键词/名称清单与 BGM 文件夹里的音乐是一一对应的，不单独备份。
 *
 * 兼容策略：
 * - 原有 10 个模块的文件名、内容格式一律不变, 老版本照常识别；
 * - 新增模块一律用新的文件名/目录名, 老版本不认识会直接忽略, 不会报错；
 * - 新备份额外写 [FILE_MANIFEST], 记录真正被打包的模块, 新版本据此过滤；
 * - 若用户只勾选了新增模块, 会补一个内容为 `{}` 的 [FILE_SP_HUNYUAN] 占位,
 *   保证最老的 TTS 备份版本解压后也能列出至少一项, 不会报「备份里没有可恢复的内容」；
 *   老版本读到的是空配置，不会写脏数据。
 *
 * 恢复分两步: 先 [unpackFromUri] / [unpackFromFile] 解压并列出可恢复项,
 * 用户在多选弹窗里勾选后调用 [apply] 只恢复选中项, 最后 [finish] 清理临时目录。
 */
object TtsBackup {

    private val backupPath: String by lazy {
        appCtx.filesDir.getFile("ttsBackup").createFolderIfNotExist().absolutePath
    }

    private val zipFilePath: String by lazy {
        "${appCtx.externalFiles.absolutePath}${File.separator}tmp_tts_backup.zip"
    }

    /** 混元太极配置所在的 SharedPreferences 名 */
    private const val SP_HUNYUAN = "ds_proxy"
    /** TTS 插件/音色配置所在的 SharedPreferences 名 */
    private const val SP_JREAD = "jread_voice_engine"
    /** 场景特效「禁用/常驻」开关 */
    private const val SP_SCENE_EFFECT = "scene_effect_settings"
    /** TTS 插件登录页记住的账号密码 */
    private const val SP_PLUGIN_LOGIN = "plugin_login_autofill"

    private const val FILE_AI_IMAGE = "aiImageTemplate.json"
    private const val FILE_AI_CHAPTER_FIX = "aiChapterFixTemplate.json"
    private const val FILE_AI_PROFILE = "aiProfileTemplate.json"
    private const val FILE_BGM_PROMPT = "bgmAIPrompt.json"
    private const val FILE_BGM_PROVIDER = "bgmAIProvider.json"
    private const val FILE_TTS_SCRIPT = "ttsScript.json"
    private const val FILE_CHARACTER = "characterProfile.json"
    private const val FILE_SP_HUNYUAN = "dsProxy.json"
    private const val FILE_SP_JREAD = "jreadVoiceEngine.json"
    private const val FILE_SP_AI_IMAGE_PRESET = "aiImagePromptPresets.json"
    private const val FILE_SP_SCENE_EFFECT = "sceneEffectSettings.json"
    private const val FILE_SP_PLUGIN_LOGIN = "pluginLoginAutofill.json"
    private const val FILE_TTS_PREFS = "ttsPreferences.json"

    /**
     * AI 生图「题材/画风」自定义预设的存储。
     * 名字必须与 [io.legado.app.model.AiImagePromptPresetStore] 里用的完全一致，
     * 否则备份出来是空的、恢复也读不到。
     */
    private const val SP_AI_IMAGE_PRESET = "aiImagePromptPresets"

    /** 外部引擎数据目录在 zip 内的父目录 */
    private const val DIR_CHAJIAN = "chajian"
    /** 新增的外部配置文件(不在两个引擎目录里)统一放在这个新目录下 */
    private const val DIR_EXTRA_CONFIG = "extraConfig"
    /** 角色立绘是二进制图片, 单独放一个老版本完全不认识的目录 */
    private const val DIR_AVATARS = "avatars"

    private const val SLOT_AI_EFFECT = "aiEffect"
    private const val FILE_AI_EFFECT_INDEX = "ai_effect_index_kw.json"
    private const val FILE_OLD_AI_EFFECT_INDEX = "ai_effect_index.json"

    /** 角色立绘根目录, 与 [io.legado.app.model.AiImageGenerator] 保持一致 */
    private const val AVATAR_ROOT_DIR = "/storage/emulated/0/Download/AI头像"

    /** 新备份的清单文件, 老版本不认识会忽略 */
    private const val FILE_MANIFEST = "ttsBackupManifest.json"
    private const val MANIFEST_VERSION = 2

    /**
     * 外部目录单个文件的打包上限。
     * 加密时内存里会同时存在明文和密文两份字符串, 峰值约为文件本身的 3 倍,
     * 卡在 10MB 是为了避免低端机 OOM。
     */
    private const val MAX_EXTERNAL_FILE_SIZE = 10 * 1024 * 1024L
    private const val MAX_AVATAR_FILE_SIZE = 5 * 1024 * 1024L
    private const val MAX_AVATARS_TOTAL_SIZE = 30 * 1024 * 1024L

    /**
     * 明确不打包的外部文件名。
     * data.json 是朗读过程中实时生成的中间数据, 体积可能很大且没必要还原,
     * 打进备份只会让 zip 变大、上传变慢。
     */
    private val EXCLUDE_FILE_NAMES = setOf("data.json")

    // ------------------------------------------------------ 可单独恢复的模块 key

    const val K_AI_IMAGE = "aiImage"
    const val K_AI_CHAPTER_FIX = "aiChapterFix"
    const val K_AI_PROFILE = "aiProfile"
    const val K_BGM_PROMPT = "bgmPrompt"
    const val K_BGM_PROVIDER = "bgmProvider"
    const val K_TTS_SCRIPT = "ttsScript"
    const val K_CHARACTER = "characterProfile"
    const val K_HUNYUAN = "hunyuan"
    const val K_JREAD = "jreadVoice"
    /**
     * AI 生图「题材/画风」自定义预设。
     * 与 [K_AI_IMAGE] 分开：K_AI_IMAGE 备的是 Room 里的模板（模板里已选中的题材/画风内容），
     * 这里备的是用户自己新增/修改/置顶的那份预设列表，存在 SharedPreferences 里。
     */
    const val K_AI_IMAGE_PRESET = "aiImagePromptPreset"
    const val K_EXTERNAL = "externalFiles"

    /** 默认 SharedPreferences 里与朗读/AI 相关的设置项 */
    const val K_TTS_PREFS = "ttsPreferences"
    /** 场景特效开关、常驻特效选择等 */
    const val K_SCENE_EFFECT = "sceneEffectSettings"
    /** TTS 插件登录页保存的账号密码 */
    const val K_PLUGIN_LOGIN = "pluginLoginAutofill"
    /** AI 音效索引(广播剧音效库目录) */
    const val K_EXTRA_CONFIG = "extraConfig"
    /** 角色立绘与立绘历史图片；体积较大，备份选择列表里默认不勾选 */
    const val K_AVATARS = "avatars"

    /** 备份里一项可勾选恢复的内容 */
    data class TtsBackupItem(
        val key: String,
        val title: String,
        val detail: String
    )

    private data class Manifest(
        val version: Int = 0,
        val modules: List<String> = emptyList()
    )

    private data class ModuleSize(
        val bytes: Long,
        val count: Int
    )

    private data class ExternalSource(
        val dirName: String,
        val file: File
    )

    private data class ExtraConfigSource(
        val slot: String,
        val name: String,
        val text: String
    )

    private val mutex = Mutex()

    /** 老版本 TTS 备份认识的模块；新备份至少要留一个这类文件，老版本才不会报空 */
    private val LEGACY_MODULE_KEYS = setOf(
        K_AI_IMAGE, K_AI_CHAPTER_FIX, K_AI_PROFILE, K_BGM_PROMPT, K_BGM_PROVIDER,
        K_TTS_SCRIPT, K_CHARACTER, K_HUNYUAN, K_JREAD, K_EXTERNAL
    )

    /** 以目录形式打包、详情里显示文件数的模块 */
    private val DIR_MODULE_KEYS = setOf(K_EXTERNAL, K_EXTRA_CONFIG, K_AVATARS)

    /** 数据库表模块，详情里显示条数 */
    private val TABLE_MODULE_KEYS = setOf(
        K_AI_IMAGE, K_AI_CHAPTER_FIX, K_AI_PROFILE, K_BGM_PROMPT,
        K_BGM_PROVIDER, K_TTS_SCRIPT, K_CHARACTER
    )

    /**
     * 默认 SharedPreferences 里需要单独备份的朗读/AI 设置。
     * 主备份的 config.xml 会整份备份这些键；这里单独再备一份，是为了让
     * 「TTS/AI 备份」不依赖主备份也能还原这些新增功能的开关与参数。
     * 里面含 AI 接口密钥，所以落盘前会加密。
     */
    private val TTS_PREF_KEYS = setOf(
        PreferKey.ttsEngine, PreferKey.ttsFollowSys, PreferKey.ttsSpeechRate,
        PreferKey.ttsSpeechRateFine, PreferKey.ttsCacheEnabled,
        PreferKey.sysTtsPackageName, PreferKey.sysTtsSynthesizeTimeout,
        PreferKey.ttsRetryCount, PreferKey.ttsRetrySkipOnFail,
        PreferKey.sysTtsVoiceName, PreferKey.ttsTimer,
        PreferKey.streamReadAloudAudio, PreferKey.readAloudByPage,
        PreferKey.contentSelectSpeakMod, PreferKey.defaultToReadAloud,
        PreferKey.pauseReadAloudWhilePhoneCalls, PreferKey.readAloudByMediaButton,
        PreferKey.ignoreAudioFocus,
        PreferKey.ttsTailCheckEnabled, PreferKey.ttsTailCheckMaxRetry,
        PreferKey.ttsTailCheckHardCutDb, PreferKey.ttsTailCheckAudibleDb,
        PreferKey.ttsTailCheckMinDurationMs,
        PreferKey.convertCacheToWav, PreferKey.convertCacheToWavGain,
        PreferKey.readAloudVolumeGain,
        PreferKey.ttsPostSpeed, PreferKey.ttsPostVolume, PreferKey.ttsPostPitch,
        PreferKey.ttsSilenceSkipEnabled, PreferKey.ttsSilenceSkipMinMs,
        PreferKey.bgmEnabled, PreferKey.bgmPath, PreferKey.bgmVolume,
        PreferKey.bgmAIEnabled, PreferKey.bgmThinkingEnabled,
        PreferKey.bgmAICharInterval, PreferKey.mixBgmOnMerge, PreferKey.bgmMinSegmentMs,
        PreferKey.soundEffectMode, PreferKey.soundEffectOffsetChars,
        PreferKey.soundEffectVolume, PreferKey.mixSoundEffectOnMerge,
        PreferKey.aiEffectEnabled, PreferKey.aiEffectLocalDir,
        PreferKey.aiEffectKeywordCount, PreferKey.aiEffectMaxPick,
        PreferKey.aiEffectCandidatesPerKeyword, PreferKey.aiEffectMaxCandidates,
        PreferKey.aiEffectMixOnMerge,
        PreferKey.danmakuEnabled, PreferKey.danmakuTrackCount,
        PreferKey.sceneEffectEnabled,
        PreferKey.showRoleAnnotation, PreferKey.roleAnnotationOffset,
        PreferKey.autoProfileOnChapterSwitch,
        PreferKey.readAloudAiImage, PreferKey.vnStageEnabled, PreferKey.vnBackgroundMode,
        PreferKey.aiImageCacheCount, PreferKey.aiImageSize, PreferKey.readAloudImageInterval,
        PreferKey.aiImageLastEditTemplateId, PreferKey.aiCoverPrompt,
        PreferKey.aiCoverTemplateId, PreferKey.aiImageCharCount,
        PreferKey.aiImageContextCharCount, PreferKey.aiImageRetryCount,
        PreferKey.aiImageRequestInterval,
        PreferKey.aiChapterFixApi, PreferKey.aiChapterFixPrompt,
        PreferKey.aiChapterFixCount, PreferKey.aiChapterFixMaxLength,
        PreferKey.aiChapterFixRetryCount, PreferKey.aiChapterFixContextLength,
        PreferKey.aiChapterFixTimeoutSeconds, PreferKey.aiChapterFixLastEditTemplateId,
        PreferKey.roleChatApiConfig, PreferKey.roleChatVoiceEnabled,
        PreferKey.roleChatContextLimit, PreferKey.aiPortraitTemplateId,
        PreferKey.aiSourceRuleHistory,
        PreferKey.readAloudTitle, PreferKey.readAloudStartFromFirst,
        PreferKey.autoMergeAudioOnChapterEnd, PreferKey.convertToMp3AfterMerge,
        PreferKey.saveTextWithMerge, PreferKey.saveVideoWithMerge,
        PreferKey.shortAudioMinDuration, PreferKey.srtSubtitleMaxChars,
        PreferKey.srtSubtitleTimeOffset, PreferKey.srtSilenceMatchRange,
        PreferKey.showReadAloudCoverSubtitle, PreferKey.readAloudHideChapterProgress,
        PreferKey.readAloudCoverTopMargin, PreferKey.readAloudCoverAutoFit,
        PreferKey.readAloudCoverWidth, PreferKey.readAloudBackToBackground,
        PreferKey.readAloudFloatWindow, PreferKey.readAloudFloatClickSwap,
        PreferKey.readAloudFloatX, PreferKey.readAloudFloatY,
        PreferKey.readAloudFloatEdgeOffset,
        PreferKey.bookAnalysisScript,
        PreferKey.readAloudCoverMarqueeEnabled, PreferKey.readAloudCoverMarqueeSpeed,
        PreferKey.marqueeBgmLinkEnabled, PreferKey.showAudioCacheIndicator,
        PreferKey.readAloudImageInReadPage, PreferKey.readAloudImageShowing,
        PreferKey.readAloudImageInReadPageWidth,
        PreferKey.readAloudImageInReadPageTextSizeReduce,
        "miyue", "characterProfileSeededBooks", "characterProfileDeletedProfiles"
    )

    /** 按书/按模板保存的动态键前缀 */
    private val TTS_PREF_PREFIXES = listOf(
        PreferKey.bookSoundEffectModePrefix,
        PreferKey.aiImageEnablePrefix, PreferKey.aiImageTemplateBindPrefix,
        PreferKey.aiImageSavePrefix, PreferKey.aiImageEnableNamePrefix,
        PreferKey.aiImageTemplateBindNamePrefix, PreferKey.aiImageSaveNamePrefix,
        PreferKey.aiChapterFixTemplateBindPrefix, PreferKey.aiChapterFixAutoBookPrefix
    )

    private fun isTtsPrefKey(key: String): Boolean =
        key in TTS_PREF_KEYS || TTS_PREF_PREFIXES.any { key.startsWith(it) }

    /** 所有可单独备份/恢复的模块 */
    private fun allModuleKeys() = listOf(
        K_AI_IMAGE, K_AI_IMAGE_PRESET, K_AI_CHAPTER_FIX, K_AI_PROFILE, K_BGM_PROMPT,
        K_BGM_PROVIDER, K_TTS_SCRIPT, K_CHARACTER, K_HUNYUAN, K_JREAD,
        K_TTS_PREFS, K_SCENE_EFFECT, K_PLUGIN_LOGIN,
        K_EXTERNAL, K_EXTRA_CONFIG, K_AVATARS
    )

    private fun moduleTitle(key: String): String = when (key) {
        K_AI_IMAGE -> "AI 生图模板"
        K_AI_IMAGE_PRESET -> "AI 生图题材/画风预设"
        K_AI_CHAPTER_FIX -> "AI 章节修复模板"
        K_AI_PROFILE -> "AI 人设模板"
        K_BGM_PROMPT -> "背景音乐 AI 提示词"
        K_BGM_PROVIDER -> "背景音乐 AI 服务商"
        K_TTS_SCRIPT -> "朗读脚本"
        K_CHARACTER -> "角色档案"
        K_HUNYUAN -> "混元太极配置"
        K_JREAD -> "TTS 引擎插件"
        K_TTS_PREFS -> "朗读/AI 偏好设置"
        K_SCENE_EFFECT -> "场景特效开关"
        K_PLUGIN_LOGIN -> "TTS 插件登录信息"
        K_EXTERNAL -> "外部配置文件(含 miyue.txt)"
        K_EXTRA_CONFIG -> "AI 音效索引"
        K_AVATARS -> "角色立绘/头像"
        else -> "其它配置"
    }

    /**
     * 备份时供勾选的模块清单。
     * 与恢复用的 availableItems() 不同: 这里不检查是否已有内容。空模块勾了也
     * 只是不写文件, 不该在选择阶段就消失, 否则用户每次都得重新判断该勾什么。
     * detail 里显示按实际落盘格式估算的大小/条数，与恢复列表保持同一套展示口径。
     */
    suspend fun packableItems(): List<TtsBackupItem> = withContext(IO) {
        val ctx = currentCoroutineContext()
        allModuleKeys().map { key ->
            ctx.ensureActive()
            val size = measureModule(key)
            TtsBackupItem(key, moduleTitle(key), detailText(size, key))
        }
    }

    /** 默认导出文件名 */
    fun defaultFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        return "ttsBackup$date.zip"
    }

    // -------------------------------------------------------------------- 备份

    /**
     * 打包并写入到用户选择的 [uri], 只包含 [keys] 里勾选的模块
     */
    suspend fun backup(context: Context, uri: Uri, keys: Collection<String>) {
        val zipFile = File(zipFilePath)
        packTo(zipFile, keys)
        withContext(IO) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(zipFile).use { it.copyTo(out) }
            } ?: throw IllegalStateException("打开导出文件失败")
        }
        FileUtils.delete(zipFilePath)
    }

    /**
     * 打包到指定文件, 供上传到 WebDav 使用
     */
    suspend fun backupToFile(target: File, keys: Collection<String>) {
        packTo(target, keys)
    }

    private suspend fun packTo(zipFile: File, keys: Collection<String>) = withContext(IO) {
        mutex.withLock {
            FileUtils.delete(backupPath)
            File(backupPath).mkdirs()
            writeTables(keys, backupPath)
            currentCoroutineContext().ensureActive()
            writeSharedPrefs(keys, backupPath)
            currentCoroutineContext().ensureActive()
            if (K_TTS_PREFS in keys) writeTtsPrefs(backupPath)
            if (K_EXTERNAL in keys) writeExternalFiles(backupPath)
            if (K_EXTRA_CONFIG in keys) writeExtraConfig(backupPath)
            if (K_AVATARS in keys) writeAvatars(backupPath)
            currentCoroutineContext().ensureActive()

            // 清单必须在兼容占位之前生成：占位用的 dsProxy.json 不能算作
            // 用户真正勾选并导出的「混元太极配置」模块。
            val included = keys.filter { moduleHasData(backupPath, it) }.distinct()
            writeManifest(backupPath, included)
            ensureLegacyPlaceholder(backupPath)

            val files = collectBackupFiles(backupPath, included)
            FileUtils.delete(zipFile.absolutePath)
            if (!ZipUtils.zipFiles(files, zipFile)) {
                throw IllegalStateException("打包TTS备份失败")
            }
            currentCoroutineContext().ensureActive()
            FileUtils.delete(backupPath)
        }
    }

    private suspend fun writeTables(keys: Collection<String>, path: String) {
        if (K_AI_IMAGE in keys) {
            writeListToJson(appDb.aiImageTemplateDao.getAllList(), FILE_AI_IMAGE, path)
        }
        if (K_AI_CHAPTER_FIX in keys) {
            writeListToJson(appDb.aiChapterFixTemplateDao.getAllList(), FILE_AI_CHAPTER_FIX, path)
        }
        if (K_AI_PROFILE in keys) {
            writeListToJson(appDb.aiProfileTemplateDao.getAllList(), FILE_AI_PROFILE, path)
        }
        if (K_BGM_PROMPT in keys) {
            writeListToJson(appDb.bgmAIPromptDao.getAllList(), FILE_BGM_PROMPT, path)
        }
        if (K_BGM_PROVIDER in keys) {
            writeListToJson(appDb.bgmAIProviderDao.getAllList(), FILE_BGM_PROVIDER, path)
        }
        if (K_TTS_SCRIPT in keys) {
            writeListToJson(appDb.ttsScriptDao.all, FILE_TTS_SCRIPT, path)
        }
        if (K_CHARACTER in keys) {
            writeListToJson(appDb.characterProfileDao.getAllList(), FILE_CHARACTER, path)
        }
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        if (list.isEmpty()) return
        FileUtils.createFileIfNotExist(path + File.separator + fileName)
            .outputStream().buffered().use {
                GSON.writeToOutputStream(it, list)
            }
    }

    private fun writeSharedPrefs(keys: Collection<String>, path: String) {
        if (K_HUNYUAN in keys) writeSpEncrypted(path, FILE_SP_HUNYUAN, SP_HUNYUAN)
        if (K_JREAD in keys) writeSpEncrypted(path, FILE_SP_JREAD, SP_JREAD)
        if (K_AI_IMAGE_PRESET in keys) {
            writeSpEncrypted(path, FILE_SP_AI_IMAGE_PRESET, SP_AI_IMAGE_PRESET)
        }
        if (K_SCENE_EFFECT in keys) {
            writeSpEncryptedIfNotEmpty(path, FILE_SP_SCENE_EFFECT, SP_SCENE_EFFECT)
        }
        if (K_PLUGIN_LOGIN in keys) {
            writeSpEncryptedIfNotEmpty(path, FILE_SP_PLUGIN_LOGIN, SP_PLUGIN_LOGIN)
        }
    }

    /**
     * 把 SharedPreferences 落成加密 json。
     *
     * 这里不能直接用 GSON 序列化 sp.all 再反序列化: Gson 会把所有数字解析成 Double,
     * 还原时 Int/Long/Float 类型就丢了, 而 ds_proxy 里 proxy_port 是按 Int 读的,
     * 写成 Long 会让 getInt 抛 ClassCastException。所以额外记一个类型标记。
     */
    private fun writeSpEncrypted(path: String, fileName: String, prefName: String) {
        val sp = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        writeEncryptedMap(path, fileName, buildTypedMap(sp))
    }

    private fun writeSpEncryptedIfNotEmpty(path: String, fileName: String, prefName: String) {
        val sp = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val map = buildTypedMap(sp)
        if (map.isEmpty()) return
        writeEncryptedMap(path, fileName, map)
    }

    private fun writeTtsPrefs(path: String) {
        val map = buildTypedMap(appCtx.defaultSharedPreferences, ::isTtsPrefKey)
        if (map.isEmpty()) return
        writeEncryptedMap(path, FILE_TTS_PREFS, map)
    }

    private fun writeEncryptedMap(
        path: String,
        fileName: String,
        map: Map<String, Map<String, Any?>>
    ) {
        val out = encryptJson(GSON.toJson(map))
        FileUtils.createFileIfNotExist(path + File.separator + fileName).writeText(out)
    }

    private fun buildTypedMap(
        sp: SharedPreferences,
        filter: (String) -> Boolean = { true }
    ): LinkedHashMap<String, Map<String, Any?>> {
        val map = LinkedHashMap<String, Map<String, Any?>>()
        sp.all.forEach { (key, value) ->
            if (!filter(key)) return@forEach
            val typed = when (value) {
                is Boolean -> mapOf("t" to "b", "v" to value)
                is Int -> mapOf("t" to "i", "v" to value)
                is Long -> mapOf("t" to "l", "v" to value)
                is Float -> mapOf("t" to "f", "v" to value)
                is String -> mapOf("t" to "s", "v" to value)
                is Set<*> -> mapOf("t" to "e", "v" to value.toList())
                else -> null
            }
            if (typed != null) map[key] = typed
        }
        return map
    }

    /**
     * 把外部引擎目录下的配置收进备份。
     *
     * 收两类: *.json(角色记录、音效规则、keys.json 等) 和 *.txt(miyue.txt 密钥、
     * cunfang.txt 等); [EXCLUDE_FILE_NAMES] 里的和超过 [MAX_EXTERNAL_FILE_SIZE]
     * 的跳过。权限不足时跳过, 不影响主流程。
     *
     * 这部分含第三方密钥明文, 而备份会上传到 webDav, 所以逐个加密后再落盘。
     * 不做"合成一个大 json 再整体加密"是因为加密时明文和密文字符串会各占一份内存,
     * 合并后是整体膨胀, 目录稍大就容易 OOM。
     */
    private fun writeExternalFiles(path: String) {
        val aes = BackupAES()
        externalSources().forEach { src ->
            val destDir = File(path, "$DIR_CHAJIAN${File.separator}${src.dirName}")
            destDir.mkdirs()
            val text = runCatching { src.file.readText() }.getOrNull() ?: return@forEach
            val out = encryptText(text, aes)
            runCatching {
                File(destDir, src.file.name).writeText(out)
            }.onFailure {
                AppLog.put("TTS备份: 写入 ${src.file.name} 失败\n${it.localizedMessage}")
            }
        }
    }

    private fun externalSources(): List<ExternalSource> {
        val sources = arrayListOf<ExternalSource>()
        EngineDataDir.allDirs().forEach { dir ->
            val src = runCatching { File(dir) }.getOrNull() ?: return@forEach
            val files = runCatching {
                src.listFiles()?.filter {
                    it.isFile
                            && it.name !in EXCLUDE_FILE_NAMES
                            && (it.extension == "json" || it.extension == "txt")
                            && it.length() <= MAX_EXTERNAL_FILE_SIZE
                }
            }.getOrNull() ?: run {
                AppLog.put("TTS备份: 读取目录失败或无权限 $dir")
                return@forEach
            }
            files.forEach { sources.add(ExternalSource(src.name, it)) }
        }
        return sources
    }

    /** 新增的外部配置：只备 AI 音效索引；BGM 关键词/名称清单与音乐文件夹绑定，不单独备份 */
    private fun writeExtraConfig(path: String) {
        val sources = extraConfigSources()
        if (sources.isEmpty()) return
        val aes = BackupAES()
        sources.forEach { src ->
            val destDir = File(path, "$DIR_EXTRA_CONFIG${File.separator}${src.slot}")
            destDir.mkdirs()
            val out = encryptText(src.text, aes)
            runCatching {
                File(destDir, src.name).writeText(out)
            }.onFailure {
                AppLog.put("TTS备份: 写入 ${src.name} 失败\n${it.localizedMessage}")
            }
        }
    }

    private fun extraConfigSources(): List<ExtraConfigSource> {
        val sources = arrayListOf<ExtraConfigSource>()
        listOf(FILE_AI_EFFECT_INDEX, FILE_OLD_AI_EFFECT_INDEX).forEach { name ->
            aiEffectConfigText(name)?.takeIf { it.isNotBlank() }?.let {
                sources.add(ExtraConfigSource(SLOT_AI_EFFECT, name, it))
            }
        }
        return sources
    }

    private fun aiEffectConfigText(name: String): String? {
        val file = File(AppConfig.aiEffectLocalDir, name)
        if (!file.isFile || file.length() > MAX_EXTERNAL_FILE_SIZE) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** 角色立绘是二进制图片，原样打包；只收 .jpg 且限制单文件与总大小 */
    private fun writeAvatars(path: String) {
        val sources = avatarSources()
        if (sources.isEmpty()) return
        val root = File(path, DIR_AVATARS)
        sources.forEach { (relative, file) ->
            val dest = File(root, relative)
            runCatching {
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }.onFailure {
                AppLog.put("TTS备份: 写入立绘 $relative 失败\n${it.localizedMessage}")
            }
        }
    }

    private fun avatarSources(): List<Pair<String, File>> {
        val root = File(AVATAR_ROOT_DIR)
        if (!root.isDirectory) return emptyList()
        val sources = arrayListOf<Pair<String, File>>()
        var total = 0L
        root.walkTopDown().forEach { file ->
            if (!file.isFile || !file.extension.equals("jpg", true)) return@forEach
            val length = file.length()
            if (length <= 0 || length > MAX_AVATAR_FILE_SIZE) return@forEach
            if (total + length > MAX_AVATARS_TOTAL_SIZE) return@forEach
            val relative = runCatching {
                file.relativeTo(root).path.replace(File.separatorChar, '/')
            }.getOrNull() ?: return@forEach
            if (!isSafeRelativePath(relative)) return@forEach
            sources.add(relative to file)
            total += length
        }
        return sources.sortedBy { it.first }
    }

    private fun writeManifest(path: String, modules: List<String>) {
        val map = linkedMapOf(
            "version" to MANIFEST_VERSION,
            "modules" to modules
        )
        File(path, FILE_MANIFEST).writeText(GSON.toJson(map))
    }

    private fun readManifest(path: String): Manifest? {
        val file = File(path, FILE_MANIFEST)
        if (!file.exists()) return null
        return runCatching {
            val map = GSON.fromJsonObject<Map<String, Any>>(file.readText()).getOrNull()
                ?: return null
            val version = (map["version"] as? Number)?.toInt() ?: 0
            val modules = (map["modules"] as? List<*>)?.mapNotNull { it?.toString() }
                ?: emptyList()
            Manifest(version, modules)
        }.getOrNull()
    }

    /**
     * 老版本只按固定文件名列可恢复项。若用户只勾了新增模块，zip 里会没有任何
     * 老版本认识的文件，老版本会直接报「备份里没有可恢复的内容」。补一个空的
     * 混元太极配置文件即可：老版本解析 `{}` 只会写入空配置，不碰数据库，也不会报错。
     */
    private fun ensureLegacyPlaceholder(path: String) {
        if (LEGACY_MODULE_KEYS.any { moduleHasData(path, it) }) return
        val file = File(path, FILE_SP_HUNYUAN)
        if (!file.exists()) file.writeText("{}")
    }

    private fun collectBackupFiles(path: String, keys: Collection<String>): List<File> {
        val files = arrayListOf<File>()
        keys.forEach { files.addAll(moduleFiles(path, it)) }
        File(path, FILE_MANIFEST).takeIf { it.exists() }?.let { files.add(it) }
        // 兼容占位可能不在 keys 里，显式补上
        File(path, FILE_SP_HUNYUAN).takeIf { it.exists() }?.let { files.add(it) }
        return files.distinctBy { it.absolutePath }
    }

    // ------------------------------------------------------------ 恢复(两步)

    /**
     * 第一步: 把 [uri] 指向的备份解压到内部目录, 返回备份里实际包含的模块。
     * 只解压不写数据库, 交给调用方拿去弹多选。
     */
    suspend fun unpackFromUri(context: Context, uri: Uri): List<TtsBackupItem> {
        val zipFile = File(zipFilePath)
        withContext(IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(zipFile).use { input.copyTo(it) }
            } ?: throw IllegalStateException("读取备份文件失败")
        }
        return unpackFromFile(zipFile)
    }

    /**
     * 第一步: 从本地 zip(如 WebDav 下载的)解压, 返回备份里实际包含的模块
     */
    suspend fun unpackFromFile(zipFile: File): List<TtsBackupItem> = withContext(IO) {
        mutex.withLock {
            FileUtils.delete(backupPath)
            File(backupPath).mkdirs()
            ZipUtils.unZipToPath(zipFile, backupPath)
        }
        availableItems()
    }

    /**
     * 备份里实际存在的模块。
     * 新备份带清单，按清单过滤，避免把兼容占位当成用户真正备份的模块；
     * 老备份没有清单时退回「按文件是否存在判断」，行为与旧版本一致。
     */
    private fun availableItems(): List<TtsBackupItem> {
        val manifest = readManifest(backupPath)
        val included = if (manifest != null && manifest.version >= MANIFEST_VERSION) {
            manifest.modules.toHashSet()
        } else {
            null
        }
        val items = arrayListOf<TtsBackupItem>()
        allModuleKeys().forEach { key ->
            if (included != null && key !in included) return@forEach
            val files = moduleFileList(backupPath, key)
            if (files.isEmpty()) return@forEach
            items.add(TtsBackupItem(key, moduleTitle(key), moduleDetail(files, key)))
        }
        return items
    }

    /**
     * 第二步: 只恢复 [keys] 里选中的模块
     */
    suspend fun apply(keys: Collection<String>) = withContext(IO) {
        val path = backupPath
        if (K_AI_IMAGE in keys) {
            readList<AiImageTemplate>(path, FILE_AI_IMAGE)?.let {
                appDb.aiImageTemplateDao.insert(*it.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        if (K_AI_CHAPTER_FIX in keys) {
            readList<AiChapterFixTemplate>(path, FILE_AI_CHAPTER_FIX)?.let {
                appDb.aiChapterFixTemplateDao.insert(*it.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        if (K_AI_PROFILE in keys) {
            readList<AiProfileTemplate>(path, FILE_AI_PROFILE)?.let {
                appDb.aiProfileTemplateDao.insert(*it.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        if (K_BGM_PROMPT in keys) {
            readList<BgmAIPrompt>(path, FILE_BGM_PROMPT)?.let { incoming ->
                val dao = appDb.bgmAIPromptDao
                // 先删同名旧记录再插入：插入是按 id REPLACE，而本机的提示词
                // （如启动时自动种下的"默认提示词"）与备份里的 id 几乎必然不同，
                // 直接插入会越恢复越多（同名提示词翻倍）。提示词以 name 为业务键。
                val names = incoming.mapTo(hashSetOf()) { it.name }
                dao.getAllList().filter { it.name in names }.forEach { dao.delete(it) }
                dao.insert(*incoming.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        if (K_BGM_PROVIDER in keys) {
            readList<BgmAIProvider>(path, FILE_BGM_PROVIDER)?.let {
                appDb.bgmAIProviderDao.insert(*it.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        if (K_TTS_SCRIPT in keys) {
            readList<TtsScript>(path, FILE_TTS_SCRIPT)?.let {
                appDb.ttsScriptDao.insert(*it.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        if (K_CHARACTER in keys) {
            readList<CharacterProfile>(path, FILE_CHARACTER)?.let {
                appDb.characterProfileDao.insert(*it.toTypedArray())
            }
        }
        currentCoroutineContext().ensureActive()
        val hunyuan = K_HUNYUAN in keys
        val jread = K_JREAD in keys
        if (hunyuan) readSpEncrypted(path, FILE_SP_HUNYUAN, SP_HUNYUAN)
        if (jread) readSpEncrypted(path, FILE_SP_JREAD, SP_JREAD)
        // 旧备份(本功能上线前导出的)里没有这个文件, readSpEncrypted 内部有 exists 判断会直接返回,
        // 不会影响其余模块恢复, 所以这里可以无条件按 key 调用
        if (K_AI_IMAGE_PRESET in keys) {
            readSpEncrypted(path, FILE_SP_AI_IMAGE_PRESET, SP_AI_IMAGE_PRESET)
        }
        if (K_SCENE_EFFECT in keys) {
            readSpEncrypted(path, FILE_SP_SCENE_EFFECT, SP_SCENE_EFFECT)
        }
        if (K_PLUGIN_LOGIN in keys) {
            readSpEncrypted(path, FILE_SP_PLUGIN_LOGIN, SP_PLUGIN_LOGIN)
        }
        if (K_TTS_PREFS in keys) readTtsPrefs(path)
        if (hunyuan || jread || K_TTS_PREFS in keys) {
            // 这些配置有内存缓存, 恢复完必须失效, 否则界面还显示旧数据
            runCatching {
                io.legado.app.help.hunyuan.HunyuanLocalGateway.invalidateConfig()
            }
            io.legado.app.help.audiobook.JReadVoiceEngine.invalidateAllCaches()
        }
        currentCoroutineContext().ensureActive()
        if (K_EXTERNAL in keys) restoreExternalFiles()
        if (K_EXTRA_CONFIG in keys) restoreExtraConfig()
        if (K_AVATARS in keys) restoreAvatars()
    }

    /**
     * 第三步: 清理临时目录。取消恢复时也要调用, 否则临时文件会一直留着。
     */
    suspend fun finish() = withContext(IO) {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }

    /**
     * 目录由调用方传入而不是直接取 [backupPath]: inline 函数体内访问 private 成员
     * 在部分 Kotlin 版本会报 "inline function cannot access non-public-API"。
     */
    private inline fun <reified T> readList(path: String, fileName: String): List<T>? {
        val file = File(path, fileName)
        if (!file.exists()) return null
        return try {
            FileInputStream(file).use {
                GSON.fromJsonArray<T>(it).getOrNull()
            }
        } catch (e: Exception) {
            AppLog.put("TTS恢复: $fileName 解析失败\n${e.localizedMessage}", e)
            null
        }
    }

    private fun readSpEncrypted(path: String, fileName: String, prefName: String) {
        val map = readEncryptedMap(path, fileName) ?: return
        val edit = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
        applyTypedMap(map, edit)
        if (prefName == SP_SCENE_EFFECT) {
            // SceneEffectSettings 首次初始化时会按这个标记做一次「复位迁移」，
            // 老备份里可能没有它，补上以免刚恢复的开关被立刻清掉。
            edit.putBoolean("reset_migration_v1", true)
        }
        edit.apply()
    }

    private fun readTtsPrefs(path: String) {
        val map = readEncryptedMap(path, FILE_TTS_PREFS) ?: return
        val edit = appCtx.defaultSharedPreferences.edit()
        applyTypedMap(map, edit, ::isTtsPrefKey)
        edit.apply()
    }

    private fun readEncryptedMap(path: String, fileName: String): Map<String, Any>? {
        val file = File(path, fileName)
        if (!file.exists()) return null
        val json = decryptJson(file.readText())
        return runCatching {
            GSON.fromJsonObject<HashMap<String, Any>>(json).getOrNull()
        }.getOrNull()
    }

    private fun applyTypedMap(
        map: Map<String, Any>,
        edit: SharedPreferences.Editor,
        keyFilter: (String) -> Boolean = { true }
    ) {
        map.forEach { (key, raw) ->
            if (!keyFilter(key)) return@forEach
            val entry = raw as? Map<*, *> ?: return@forEach
            val value = entry["v"] ?: return@forEach
            when (entry["t"] as? String) {
                "b" -> edit.putBoolean(key, value == true || value == "true")
                "i" -> (value as? Number)?.let { edit.putInt(key, it.toInt()) }
                "l" -> (value as? Number)?.let { edit.putLong(key, it.toLong()) }
                "f" -> (value as? Number)?.let { edit.putFloat(key, it.toFloat()) }
                "s" -> edit.putString(key, value.toString())
                "e" -> (value as? List<*>)?.let {
                    edit.putStringSet(key, it.mapNotNull { item -> item?.toString() }.toSet())
                }
            }
        }
    }

    private fun restoreExternalFiles() {
        val root = File(backupPath, DIR_CHAJIAN)
        val dirs = runCatching { root.listFiles() }.getOrNull() ?: return
        val parent = runCatching {
            File(EngineDataDir.allDirs().first()).parentFile
        }.getOrNull() ?: return
        val aes = BackupAES()
        dirs.forEach { dir ->
            val target = runCatching { File(parent, dir.name) }.getOrNull() ?: return@forEach
            // 防路径穿越
            val inRange = runCatching {
                target.canonicalPath.startsWith(parent.canonicalPath)
            }.getOrDefault(false)
            if (!inRange) return@forEach
            if (!target.exists() && !target.mkdirs()) {
                AppLog.put("TTS恢复: 无法创建目录 ${target.absolutePath}")
                return@forEach
            }
            dir.listFiles()?.forEach { file ->
                if (!safeFileName(file.name)) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                // 解密失败就按原文写, 兼容未加密的旧备份
                val plain = decryptText(text, aes)
                runCatching {
                    File(target, file.name).writeText(plain)
                }.onFailure {
                    AppLog.put("TTS恢复: 写入 ${file.name} 失败\n${it.localizedMessage}")
                }
            }
        }
    }

    private fun restoreExtraConfig() {
        val root = File(backupPath, DIR_EXTRA_CONFIG)
        val slots = runCatching { root.listFiles() }.getOrNull() ?: return
        val aes = BackupAES()
        slots.forEach { slotDir ->
            if (!slotDir.isDirectory) return@forEach
            slotDir.listFiles()?.forEach { file ->
                if (!safeFileName(file.name)) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val plain = decryptText(text, aes)
                // 旧的开发版备份可能还有 bgm 槽位，这里直接忽略，避免覆盖用户自己的 BGM 配置
                if (slotDir.name == SLOT_AI_EFFECT) {
                    writeAiEffectConfig(file.name, plain)
                }
            }
        }
    }

    private fun safeFileName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".."
                && !name.contains('/') && !name.contains('\\')

    private fun isSafeRelativePath(relative: String): Boolean =
        relative.isNotBlank() && relative.split('/').none {
            it.isEmpty() || it == "." || it == ".." || it.contains('\\')
        }

    private fun writeAiEffectConfig(name: String, text: String) {
        val dir = File(AppConfig.aiEffectLocalDir)
        if (!dir.exists() && !dir.mkdirs()) {
            AppLog.put("TTS恢复: 无法创建目录 ${dir.absolutePath}")
            return
        }
        val target = File(dir, name)
        val inRange = runCatching {
            target.canonicalPath.startsWith(dir.canonicalPath)
        }.getOrDefault(false)
        if (!inRange) return
        runCatching { target.writeText(text) }.onFailure {
            AppLog.put("TTS恢复: 写入 $name 失败\n${it.localizedMessage}")
        }
    }

    private fun restoreAvatars() {
        val root = File(backupPath, DIR_AVATARS)
        if (!root.isDirectory) return
        val targetRoot = File(AVATAR_ROOT_DIR)
        if (!targetRoot.exists() && !targetRoot.mkdirs()) {
            AppLog.put("TTS恢复: 无法创建目录 ${targetRoot.absolutePath}")
            return
        }
        var total = 0L
        root.walkTopDown().filter { it.isFile }.toList().sortedBy {
            runCatching { it.relativeTo(root).path }.getOrDefault(it.name)
        }.forEach { file ->
            val relative = runCatching {
                file.relativeTo(root).path.replace(File.separatorChar, '/')
            }.getOrNull() ?: return@forEach
            if (!isSafeRelativePath(relative)) return@forEach
            val length = file.length()
            if (length <= 0 || length > MAX_AVATAR_FILE_SIZE) return@forEach
            if (total + length > MAX_AVATARS_TOTAL_SIZE) return@forEach
            val target = File(targetRoot, relative)
            val inRange = runCatching {
                target.canonicalPath.startsWith(targetRoot.canonicalPath)
            }.getOrDefault(false)
            if (!inRange) return@forEach
            runCatching {
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }.onFailure {
                AppLog.put("TTS恢复: 写入立绘 $relative 失败\n${it.localizedMessage}")
            }
            total += length
        }
    }

    // ------------------------------------------------------------ 大小统计

    private fun measureModule(key: String): ModuleSize = when (key) {
        K_AI_IMAGE -> measureTable(appDb.aiImageTemplateDao.getAllList())
        K_AI_CHAPTER_FIX -> measureTable(appDb.aiChapterFixTemplateDao.getAllList())
        K_AI_PROFILE -> measureTable(appDb.aiProfileTemplateDao.getAllList())
        K_BGM_PROMPT -> measureTable(appDb.bgmAIPromptDao.getAllList())
        K_BGM_PROVIDER -> measureTable(appDb.bgmAIProviderDao.getAllList())
        K_TTS_SCRIPT -> measureTable(appDb.ttsScriptDao.all)
        K_CHARACTER -> measureTable(appDb.characterProfileDao.getAllList())
        K_HUNYUAN -> measureSp(SP_HUNYUAN, skipEmpty = false)
        K_JREAD -> measureSp(SP_JREAD, skipEmpty = false)
        K_AI_IMAGE_PRESET -> measureSp(SP_AI_IMAGE_PRESET, skipEmpty = false)
        K_SCENE_EFFECT -> measureSp(SP_SCENE_EFFECT, skipEmpty = true)
        K_PLUGIN_LOGIN -> measureSp(SP_PLUGIN_LOGIN, skipEmpty = true)
        K_TTS_PREFS -> measureDefaultPrefs()
        K_EXTERNAL -> measureExternal()
        K_EXTRA_CONFIG -> measureExtraConfig()
        K_AVATARS -> measureAvatars()
        else -> ModuleSize(0, 0)
    }

    private fun measureTable(list: List<Any>): ModuleSize {
        if (list.isEmpty()) return ModuleSize(0, 0)
        return ModuleSize(GSON.toJson(list).toByteArray().size.toLong(), list.size)
    }

    private fun measureSp(
        prefName: String,
        filter: (String) -> Boolean = { true },
        skipEmpty: Boolean
    ): ModuleSize {
        val sp = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val map = buildTypedMap(sp, filter)
        if (skipEmpty && map.isEmpty()) return ModuleSize(0, 0)
        val bytes = encryptJson(GSON.toJson(map)).toByteArray().size.toLong()
        return ModuleSize(bytes, map.size)
    }

    private fun measureDefaultPrefs(): ModuleSize {
        val map = buildTypedMap(appCtx.defaultSharedPreferences, ::isTtsPrefKey)
        if (map.isEmpty()) return ModuleSize(0, 0)
        val bytes = encryptJson(GSON.toJson(map)).toByteArray().size.toLong()
        return ModuleSize(bytes, map.size)
    }

    private fun measureExternal(): ModuleSize {
        val sources = externalSources()
        if (sources.isEmpty()) return ModuleSize(0, 0)
        val aes = BackupAES()
        var bytes = 0L
        var count = 0
        sources.forEach { src ->
            val text = runCatching { src.file.readText() }.getOrNull() ?: return@forEach
            bytes += encryptText(text, aes).toByteArray().size
            count++
        }
        return ModuleSize(bytes, count)
    }

    private fun measureExtraConfig(): ModuleSize {
        val sources = extraConfigSources()
        if (sources.isEmpty()) return ModuleSize(0, 0)
        val aes = BackupAES()
        var bytes = 0L
        sources.forEach { src ->
            bytes += encryptText(src.text, aes).toByteArray().size
        }
        return ModuleSize(bytes, sources.size)
    }

    private fun measureAvatars(): ModuleSize {
        val sources = avatarSources()
        return ModuleSize(sources.sumOf { it.second.length() }, sources.size)
    }

    private fun detailText(size: ModuleSize, key: String): String {
        if (size.bytes <= 0 && size.count == 0) return "无数据"
        val unit = when (key) {
            in DIR_MODULE_KEYS -> "个文件"
            in TABLE_MODULE_KEYS -> "条"
            else -> "项"
        }
        return if (size.count > 0) {
            "${size.count} $unit · ${formatSize(size.bytes)}"
        } else {
            formatSize(size.bytes)
        }
    }

    private fun moduleDetail(files: List<File>, key: String): String {
        val bytes = files.sumOf { it.length() }
        return if (key in DIR_MODULE_KEYS) {
            "${files.size} 个文件 · ${formatSize(bytes)}"
        } else {
            formatSize(bytes)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1fMB", bytes / (1024.0 * 1024.0))
    }

    // ------------------------------------------------------------ 文件映射

    private fun moduleFiles(path: String, key: String): List<File> = when (key) {
        K_AI_IMAGE -> listOf(File(path, FILE_AI_IMAGE))
        K_AI_IMAGE_PRESET -> listOf(File(path, FILE_SP_AI_IMAGE_PRESET))
        K_AI_CHAPTER_FIX -> listOf(File(path, FILE_AI_CHAPTER_FIX))
        K_AI_PROFILE -> listOf(File(path, FILE_AI_PROFILE))
        K_BGM_PROMPT -> listOf(File(path, FILE_BGM_PROMPT))
        K_BGM_PROVIDER -> listOf(File(path, FILE_BGM_PROVIDER))
        K_TTS_SCRIPT -> listOf(File(path, FILE_TTS_SCRIPT))
        K_CHARACTER -> listOf(File(path, FILE_CHARACTER))
        K_HUNYUAN -> listOf(File(path, FILE_SP_HUNYUAN))
        K_JREAD -> listOf(File(path, FILE_SP_JREAD))
        K_TTS_PREFS -> listOf(File(path, FILE_TTS_PREFS))
        K_SCENE_EFFECT -> listOf(File(path, FILE_SP_SCENE_EFFECT))
        K_PLUGIN_LOGIN -> listOf(File(path, FILE_SP_PLUGIN_LOGIN))
        K_EXTERNAL -> listOf(File(path, DIR_CHAJIAN))
        K_EXTRA_CONFIG -> listOf(File(path, DIR_EXTRA_CONFIG))
        K_AVATARS -> listOf(File(path, DIR_AVATARS))
        else -> emptyList()
    }.filter { it.exists() }

    private fun moduleFileList(path: String, key: String): List<File> {
        val files = moduleFiles(path, key).flatMap { file ->
            if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.toList()
            } else {
                listOf(file)
            }
        }
        // 旧的开发版备份可能带 bgm 槽位，恢复时会被忽略，统计里也不该把它算进来
        return if (key == K_EXTRA_CONFIG) {
            files.filter { it.parentFile?.name == SLOT_AI_EFFECT }
        } else {
            files
        }
    }

    private fun moduleHasData(path: String, key: String): Boolean =
        moduleFileList(path, key).isNotEmpty()

    // ------------------------------------------------------------ 加解密

    private fun encryptJson(json: String): String =
        BackupAES().runCatching { encryptBase64(json) }.getOrDefault(json)

    private fun decryptJson(json: String): String {
        if (json.trimStart().startsWith("{")) return json
        return BackupAES().runCatching { decryptStr(json) }.getOrDefault(json)
    }

    private fun encryptText(text: String, aes: BackupAES = BackupAES()): String =
        aes.runCatching { encryptBase64(text) }.getOrDefault(text)

    private fun decryptText(text: String, aes: BackupAES = BackupAES()): String =
        aes.runCatching { decryptStr(text) }.getOrDefault(text)

}
