@file:Suppress("unused")

package io.legado.app.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Environment
import android.webkit.WebSettings
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import io.legado.app.help.globalExecutor
import splitties.init.appCtx
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration.Companion.days

@SuppressLint("SimpleDateFormat")
@Suppress("unused")
object LogUtils {
    const val TIME_PATTERN = "yy-MM-dd HH:mm:ss.SSS"
    val logTimeFormat by lazy { SimpleDateFormat(TIME_PATTERN) }

    fun init(context: Context) {
        fileHandler = createFileHandler(context)?.also {
            logger.addHandler(it)
        }
    }

    @JvmStatic
    fun d(tag: String, msg: String) {
        logger.log(Level.INFO, "$tag $msg")
    }

    inline fun d(tag: String, lazyMsg: () -> String) {
        if (logger.isLoggable(Level.INFO)) {
            logger.log(Level.INFO, "$tag ${lazyMsg()}")
        }
    }

    @JvmStatic
    fun e(tag: String, msg: String) {
        logger.log(Level.WARNING, "$tag $msg")
    }

    val logger: Logger by lazy {
        Logger.getLogger("Legado")
    }

    private var fileHandler: FileHandler? = null

    private fun createFileHandler(context: Context): FileHandler? {
        try {
            // 日志目录改到共享存储 Download/uyue/logs：
            // 原先写在 externalCacheDir（Android/data/<包名>/cache/logs），Android 11+ 分区存储下
            // 其他进程/文件管理器读不到，排查问题时只能靠 App 内的日志对话框。
            // Download 是一级共享目录，PC 数据线与手机文件管理器都能直接看到。
            // 没有「所有文件访问」权限时退回原来的私有缓存目录，保证日志永远写得进去。
            val logFolder = sharedLogFolder() ?: run {
                val root = context.externalCacheDir ?: return null
                FileUtils.createFolderIfNotExist(root, "logs")
            }
            globalExecutor.execute {
                val expiredTime = System.currentTimeMillis() - 7.days.inWholeMilliseconds
                logFolder.listFiles()?.forEach {
                    if (it.lastModified() < expiredTime || it.name.endsWith(".lck")) {
                        it.delete()
                    }
                }
            }
            val date = getCurrentDateStr(TIME_PATTERN).replace(" ", "_").replace(":", "-")
            val logPath = FileUtils.getPath(root = logFolder, "appLog-$date.txt")
            return AsyncFileHandler(logPath).apply {
                formatter = object : java.util.logging.Formatter() {
                    override fun format(record: LogRecord): String {
                        // 设置文件输出格式
                        return getCurrentDateStr(TIME_PATTERN) + ": " + record.message + "\n"
                    }
                }
                level = if (AppConfig.recordLog) {
                    Level.INFO
                } else {
                    Level.OFF
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.putNotSave("创建fileHandler出错\n$e", e)
            return null
        }
    }

    fun upLevel() {
        val level = if (AppConfig.recordLog) {
            Level.INFO
        } else {
            Level.OFF
        }
        fileHandler?.level = level
    }

    /**
     * 获取当前时间
     */
    @SuppressLint("SimpleDateFormat")
    fun getCurrentDateStr(pattern: String): String {
        val date = Date()
        val sdf = SimpleDateFormat(pattern)
        return sdf.format(date)
    }

    /**
     * 共享存储里的日志目录 `Download/uyue/logs`。
     *
     * 返回 null 表示当前没有写入权限（或外部存储未挂载），调用方退回私有缓存目录。
     * 判据用「真实写一次探测」而不是只看 canWrite：分区存储下有的机型 canWrite 报 true
     * 但实际写入抛异常，只有写成功才算数。探测文件用完立刻删掉，不留垃圾。
     */
    private fun sharedLogFolder(): File? = kotlin.runCatching {
        val base = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        if (base == null || !base.isDirectory) return@runCatching null
        val folder = File(base, "uyue/logs")
        if (!folder.isDirectory && !folder.mkdirs()) return@runCatching null
        val probe = File(folder, ".write_probe")
        try {
            probe.writeText("")
            folder
        } finally {
            kotlin.runCatching { probe.delete() }
        }
    }.getOrNull()

    fun logDeviceInfo() {
        d("DeviceInfo") {
            buildString {
                kotlin.runCatching {
                    //获取系统信息
                    append("MANUFACTURER=").append(Build.MANUFACTURER).append("\n")
                    append("BRAND=").append(Build.BRAND).append("\n")
                    append("MODEL=").append(Build.MODEL).append("\n")
                    append("SDK_INT=").append(Build.VERSION.SDK_INT).append("\n")
                    append("RELEASE=").append(Build.VERSION.RELEASE).append("\n")
                    val userAgent = try {
                        WebSettings.getDefaultUserAgent(appCtx)
                    } catch (e: Throwable) {
                        e.toString()
                    }
                    append("WebViewUserAgent=").append(userAgent).append("\n")
                    append("packageName=").append(appCtx.packageName).append("\n")
                    append("heapSize=").append(Runtime.getRuntime().maxMemory()).append("\n")
                    //获取app版本信息
                    AppConst.appInfo.let {
                        append("versionName=").append(it.versionName).append("\n")
                        append("versionCode=").append(it.versionCode).append("\n")
                    }
                }
            }
        }
    }

}

fun Throwable.printOnDebug() {
    if (BuildConfig.DEBUG) {
        printStackTrace()
    }
}
