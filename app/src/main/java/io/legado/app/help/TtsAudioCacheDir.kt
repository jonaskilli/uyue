package io.legado.app.help

import android.os.Environment
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import splitties.init.appCtx
import java.io.File

/**
 * 系统 TTS 音频缓存目录——**唯一权威解析器**（11-28 抽取）。
 *
 * ## 为什么要抽这一个 object
 *
 * 缓存目录此前有三份独立写法，全部写死 `externalCacheDir/systemTTS`：
 *   · [io.legado.app.service.TTSReadAloudService.ttsFolderPath]（写入侧）
 *   · [io.legado.app.ui.book.read.page.AudioCacheIndicatorManager]（阅读页缓存标记扫描侧）
 *   · [io.legado.app.help.config.AppConfig]（清空缓存侧）
 *   · [io.legado.app.service.HttpTtsAudioCache]（音频拼接读取侧）
 *
 * 11-23 那次「日志与 TTS 缓存归拢 chajian/uyue-utts」只搬了日志与 UTTS 的
 * synth-cache，**漏了音频缓存**——于是四类排障数据里三类在共享根、唯独音频
 * 藏在 `Android/data/<包名>/cache/`，文件管理器与数据线都看不到，每次排查都要
 * 用户手动复制。这次把路径收成一个函数，四处同源，不会再漏。
 *
 * ## 落点选择：Download/chajian/uyue-utts/audio-cache/
 *
 * `Download` 是一级共享目录（与日志、synth-cache 同一共享根），文件管理器
 * 直接可见。**代价与保险**：
 *   ① Android 11+ 分区存储下写 `Download` 需要「所有文件访问」权限，
 *      拿不到就**自动回落**到原来的 `externalCacheDir/systemTTS`——绝不因为
 *      没权限就停止缓存（缓存是朗读能不能连听的前提）。
 *   ② 启动时把旧目录里的缓存**一次性迁移**过来，用户已缓存的章节不用重合成。
 *   ③ 保留水位仍是 [io.legado.app.service.BaseReadAloudService.AUDIO_CACHE_KEEP_FLOOR]
 *      个文件，超出照旧自动清理，不会无限涨。
 *
 * ## 为什么不用 getExternalFilesDir()
 *
 * `Android/data/<包名>/files/` 不需要额外权限，但**文件管理器同样看不到**——
 * 那就只是为了不报错而改，排障体验一点没变，等于白改。
 */
object TtsAudioCacheDir {

    /** 共享根下的子目录名（与 logs/、synth-cache/ 同级） */
    private const val SHARED_SUB_DIR = "chajian/uyue-utts/audio-cache"

    /** 旧位置（分区存储时代）：externalCacheDir/systemTTS */
    private const val LEGACY_SUB_DIR = "systemTTS"

    /** 「本进程内解析过一次」——解析含 IO 探测，不宜每笔都跑 */
    @Volatile
    private var resolved: File? = null

    /**
     * 解析当前应使用的缓存目录（带末尾分隔符的绝对路径，可直接字符串拼接文件名）。
     *
     * 优先级：共享目录（可写）> 旧缓存目录（可写）> 私有 cache 兜底。
     * 结果按进程缓存；[refresh] = true 强制重算（权限刚授予后用）。
     */
    fun path(refresh: Boolean = false): String {
        if (!refresh) resolved?.let { return it.absolutePath + File.separator }
        val dir = resolve().also { resolved = it }
        if (!dir.exists()) dir.mkdirs()
        return dir.absolutePath + File.separator
    }

    /** 当前实际使用的目录对象（诊断/清空缓存用） */
    fun dir(): File = resolved?.takeIf { it.exists() } ?: resolve().also { resolved = it }

    private fun resolve(): File {
        sharedDir()?.let { return it }
        legacyDir()?.let { return it }
        // 最后的兜底：私有 cache（一定可写）。正常走不到这里。
        val base = appCtx.externalCacheDir ?: appCtx.cacheDir
        return File(base, LEGACY_SUB_DIR)
    }

    /**
     * 共享目录：Download/chajian/uyue-utts/audio-cache。
     *
     * 判据不能只看「文件能不能建」——分区存储下无权限时 `mkdirs()` 也可能
     * 返回 false 却悄悄建出只读目录。所以用**真实写探针**：写一个小文件再删。
     * 与 `LogUtils.sharedLogFolder()` 同一套路（那边已经验证过在真机上可靠）。
     */
    private fun sharedDir(): File? = runCatching {
        // Android 11+ 需要「所有文件访问」权限才能写 Download
        if (!Environment.isExternalStorageManager()) return@runCatching null
        val base = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (base == null || !base.isDirectory) return@runCatching null
        val folder = File(base, SHARED_SUB_DIR)
        if (!folder.isDirectory && !folder.mkdirs()) return@runCatching null
        val probe = File(folder, ".write_probe")
        try {
            probe.writeText("")
            migrateLegacy(folder)
            folder
        } finally {
            runCatching { probe.delete() }
        }
    }.getOrNull()

    private fun legacyDir(): File? = runCatching {
        val base = appCtx.externalCacheDir ?: appCtx.cacheDir
        val folder = File(base, LEGACY_SUB_DIR)
        if (!folder.isDirectory) folder.mkdirs()
        if (folder.isDirectory) folder else null
    }.getOrNull()

    /**
     * 把旧目录（externalCacheDir/systemTTS）里的音频一次性搬到共享目录。
     *
     * 为什么必须做：用户已经缓存的章节如果留在旧目录，改版后播放会全部 MISS、
     * 白烧一整本书的合成。搬完在旧目录留一个标记，之后不再重复扫描。
     *
     * 只搬 `.wav` / `.mp3`（正式缓存就是这两种），`.tmp` 半截文件直接丢弃——
     * 搬过去也不能用，留着还会让 `isValidAudioFile` 白判。
     */
    private fun migrateLegacy(target: File) {
        val marker = File(target, ".migrated_from_legacy")
        if (marker.exists()) return
        val legacy = runCatching {
            File(appCtx.externalCacheDir ?: appCtx.cacheDir, LEGACY_SUB_DIR)
        }.getOrNull() ?: return
        if (!legacy.isDirectory) {
            runCatching { marker.writeText("") }
            return
        }
        var moved = 0
        var failed = 0
        runCatching {
            legacy.listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name
                if (!name.endsWith(".wav") && !name.endsWith(".mp3")) return@forEach
                val dest = File(target, name)
                if (dest.exists()) return@forEach          // 目标已有：不覆盖
                val ok = runCatching { f.renameTo(dest) }.getOrDefault(false) ||
                    runCatching { f.copyTo(dest, overwrite = false); f.delete(); true }
                        .getOrDefault(false)
                if (ok) moved++ else failed++
            }
        }
        runCatching { marker.writeText("moved=$moved failed=$failed") }
        if (moved > 0 || failed > 0) {
            AppLog.put("音频缓存迁移: 旧目录 → 共享目录，成功 $moved 个、失败 $failed 个")
        }
    }

    /** 权限刚授予/刚撤销时调用，让下次 [path] 重新探测 */
    fun invalidate() {
        resolved = null
    }
}
