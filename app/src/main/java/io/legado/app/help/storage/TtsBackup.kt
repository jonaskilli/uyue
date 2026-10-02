package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiChapterFixTemplate
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.data.entities.AiProfileTemplate
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.data.entities.BgmAIPrompt
import io.legado.app.data.entities.CharacterProfile
import io.legado.app.data.entities.TtsScript
import io.legado.app.help.EngineDataDir
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
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
 * 备份内容:
 * - 7 张数据库表 aiImageTemplate / aiChapterFixTemplate / aiProfileTemplate /
 *   bgmAIPrompt / bgmAIProvider / tts_scripts / characterProfiles
 * - 2 个独立 SharedPreferences: ds_proxy(混元太极)、jread_voice_engine(TTS 插件)
 *   两者都含第三方凭证或插件代码, 统一用 [BackupAES] 加密
 * - 外部目录 Download/chajian 下两个引擎目录里的 *.json 与 *.txt(角色记录、音效规则、
 *   miyue.txt 密钥等), 同样加密
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

    private const val FILE_AI_IMAGE = "aiImageTemplate.json"
    private const val FILE_AI_CHAPTER_FIX = "aiChapterFixTemplate.json"
    private const val FILE_AI_PROFILE = "aiProfileTemplate.json"
    private const val FILE_BGM_PROMPT = "bgmAIPrompt.json"
    private const val FILE_BGM_PROVIDER = "bgmAIProvider.json"
    private const val FILE_TTS_SCRIPT = "ttsScript.json"
    private const val FILE_CHARACTER = "characterProfile.json"
    private const val FILE_SP_HUNYUAN = "dsProxy.json"
    private const val FILE_SP_JREAD = "jreadVoiceEngine.json"

    /** 外部引擎数据目录在 zip 内的父目录 */
    private const val DIR_CHAJIAN = "chajian"

    /**
     * 外部目录单个文件的打包上限。
     * 加密时内存里会同时存在明文和密文两份字符串, 峰值约为文件本身的 3 倍,
     * 卡在 10MB 是为了避免低端机 OOM。
     */
    private const val MAX_EXTERNAL_FILE_SIZE = 10 * 1024 * 1024L

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
    const val K_EXTERNAL = "externalFiles"

    /** 备份里一项可勾选恢复的内容 */
    data class TtsBackupItem(
        val key: String,
        val title: String,
        val detail: String
    )

    private val mutex = Mutex()

    /** 默认导出文件名 */
    fun defaultFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        return "ttsBackup$date.zip"
    }

    // -------------------------------------------------------------------- 备份

    /**
     * 打包并写入到用户选择的 [uri]
     */
    suspend fun backup(context: Context, uri: Uri) {
        val zipFile = File(zipFilePath)
        packTo(zipFile)
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
    suspend fun backupToFile(target: File) {
        packTo(target)
    }

    private suspend fun packTo(zipFile: File) = withContext(IO) {
        mutex.withLock {
            FileUtils.delete(backupPath)
            File(backupPath).mkdirs()
            writeTables()
            currentCoroutineContext().ensureActive()
            writeSharedPrefs()
            currentCoroutineContext().ensureActive()
            writeExternalFiles()
            currentCoroutineContext().ensureActive()
            val files = collectBackupFiles()
            FileUtils.delete(zipFile.absolutePath)
            if (!ZipUtils.zipFiles(files, zipFile)) {
                throw IllegalStateException("打包TTS备份失败")
            }
            currentCoroutineContext().ensureActive()
            FileUtils.delete(backupPath)
        }
    }

    private suspend fun writeTables() {
        writeListToJson(appDb.aiImageTemplateDao.getAllList(), FILE_AI_IMAGE)
        writeListToJson(appDb.aiChapterFixTemplateDao.getAllList(), FILE_AI_CHAPTER_FIX)
        writeListToJson(appDb.aiProfileTemplateDao.getAllList(), FILE_AI_PROFILE)
        writeListToJson(appDb.bgmAIPromptDao.getAllList(), FILE_BGM_PROMPT)
        writeListToJson(appDb.bgmAIProviderDao.getAllList(), FILE_BGM_PROVIDER)
        writeListToJson(appDb.ttsScriptDao.all, FILE_TTS_SCRIPT)
        writeListToJson(appDb.characterProfileDao.getAllList(), FILE_CHARACTER)
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String) {
        currentCoroutineContext().ensureActive()
        if (list.isEmpty()) return
        FileUtils.createFileIfNotExist(backupPath + File.separator + fileName)
            .outputStream().buffered().use {
                GSON.writeToOutputStream(it, list)
            }
    }

    private fun writeSharedPrefs() {
        writeSpEncrypted(FILE_SP_HUNYUAN, SP_HUNYUAN)
        writeSpEncrypted(FILE_SP_JREAD, SP_JREAD)
    }

    /**
     * 把 SharedPreferences 落成加密 json。
     *
     * 这里不能直接用 GSON 序列化 sp.all 再反序列化: Gson 会把所有数字解析成 Double,
     * 还原时 Int/Long/Float 类型就丢了, 而 ds_proxy 里 proxy_port 是按 Int 读的,
     * 写成 Long 会让 getInt 抛 ClassCastException。所以额外记一个类型标记。
     */
    private fun writeSpEncrypted(fileName: String, prefName: String) {
        val sp = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val map = LinkedHashMap<String, Map<String, Any?>>()
        sp.all.forEach { (key, value) ->
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
        val json = GSON.toJson(map)
        val aes = BackupAES()
        val out = aes.runCatching { encryptBase64(json) }.getOrDefault(json)
        FileUtils.createFileIfNotExist(backupPath + File.separator + fileName).writeText(out)
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
    private fun writeExternalFiles() {
        val aes = BackupAES()
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
            if (files.isEmpty()) return@forEach
            val destDir = File(backupPath, "$DIR_CHAJIAN${File.separator}${src.name}")
            destDir.mkdirs()
            files.forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val out = aes.runCatching { encryptBase64(text) }.getOrDefault(text)
                runCatching {
                    File(destDir, file.name).writeText(out)
                }.onFailure {
                    AppLog.put("TTS备份: 写入 ${file.name} 失败\n${it.localizedMessage}")
                }
            }
        }
    }

    private fun collectBackupFiles(): List<File> {
        val files = arrayListOf<File>()
        listOf(
            FILE_AI_IMAGE, FILE_AI_CHAPTER_FIX, FILE_AI_PROFILE, FILE_BGM_PROMPT,
            FILE_BGM_PROVIDER, FILE_TTS_SCRIPT, FILE_CHARACTER,
            FILE_SP_HUNYUAN, FILE_SP_JREAD
        ).forEach {
            val file = File(backupPath, it)
            if (file.exists()) files.add(file)
        }
        // 外部配置目录要整个传进去让 ZipUtils 递归: 它用 srcFile.name 逐级拼 entry 名,
        // 如果把文件拍平传进来, 两个引擎目录下的同名文件(如 paragraphHistory.json)
        // 会得到相同的 entry 名, 触发 ZipException: duplicate entry
        val externalDir = File(backupPath, DIR_CHAJIAN)
        if (externalDir.exists()) files.add(externalDir)
        return files
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
     * 备份里实际存在的模块(空表不会写文件, 所以不会出现在列表里)
     */
    private fun availableItems(): List<TtsBackupItem> {
        val items = arrayListOf<TtsBackupItem>()
        fun add(key: String, title: String, fileName: String) {
            val file = File(backupPath, fileName)
            if (file.exists()) {
                items.add(TtsBackupItem(key, title, sizeText(file)))
            }
        }
        add(K_AI_IMAGE, "AI 生图模板", FILE_AI_IMAGE)
        add(K_AI_CHAPTER_FIX, "AI 章节修复模板", FILE_AI_CHAPTER_FIX)
        add(K_AI_PROFILE, "AI 人设模板", FILE_AI_PROFILE)
        add(K_BGM_PROMPT, "背景音乐 AI 提示词", FILE_BGM_PROMPT)
        add(K_BGM_PROVIDER, "背景音乐 AI 服务商", FILE_BGM_PROVIDER)
        add(K_TTS_SCRIPT, "朗读脚本", FILE_TTS_SCRIPT)
        add(K_CHARACTER, "角色档案", FILE_CHARACTER)
        add(K_HUNYUAN, "混元太极配置", FILE_SP_HUNYUAN)
        add(K_JREAD, "TTS 引擎插件", FILE_SP_JREAD)
        val externalCount = File(backupPath, DIR_CHAJIAN).listFiles()
            ?.sumOf { it.listFiles()?.size ?: 0 } ?: 0
        if (externalCount > 0) {
            items.add(TtsBackupItem(K_EXTERNAL, "外部配置文件(含 miyue.txt)", "$externalCount 个文件"))
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
            readList<BgmAIPrompt>(path, FILE_BGM_PROMPT)?.let {
                appDb.bgmAIPromptDao.insert(*it.toTypedArray())
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
        if (hunyuan) readSpEncrypted(FILE_SP_HUNYUAN, SP_HUNYUAN)
        if (jread) readSpEncrypted(FILE_SP_JREAD, SP_JREAD)
        if (hunyuan || jread) {
            // 两者都有内存缓存, 恢复完必须失效, 否则界面还显示旧数据
            runCatching {
                io.legado.app.help.hunyuan.HunyuanLocalGateway.invalidateConfig()
            }
            io.legado.app.help.audiobook.JReadVoiceEngine.invalidateAllCaches()
        }
        currentCoroutineContext().ensureActive()
        if (K_EXTERNAL in keys) restoreExternalFiles()
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

    private fun readSpEncrypted(fileName: String, prefName: String) {
        val file = File(backupPath, fileName)
        if (!file.exists()) return
        var json = file.readText()
        if (!json.trimStart().startsWith("{")) {
            json = runCatching { BackupAES().decryptStr(json) }.getOrNull() ?: return
        }
        val map = runCatching {
            GSON.fromJsonObject<HashMap<String, Any>>(json).getOrNull()
        }.getOrNull() ?: return
        val edit = appCtx.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
        map.forEach { (key, raw) ->
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
        edit.apply()
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
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                // 解密失败就按原文写, 兼容未加密的旧备份
                val plain = aes.runCatching { decryptStr(text) }.getOrDefault(text)
                runCatching {
                    File(target, file.name).writeText(plain)
                }.onFailure {
                    AppLog.put("TTS恢复: 写入 ${file.name} 失败\n${it.localizedMessage}")
                }
            }
        }
    }

    private fun sizeText(file: File): String {
        val kb = file.length() / 1024
        return if (kb < 1024) "${kb}KB" else "${kb / 1024}MB"
    }

}
