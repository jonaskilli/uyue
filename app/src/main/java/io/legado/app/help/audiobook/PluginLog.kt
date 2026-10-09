package io.legado.app.help.audiobook

/**
 * TTS 插件脚本输出的内存日志（console.log / logger.i 等）。
 *
 * 插件 JS 里的 console.* 与 logger.* 最终都走 JReadVoicePluginRuntime.LoggerFacade，
 * 原先只写到 logcat，在 App 界面里看不到，排查插件问题只能连电脑。
 * 这里在内存里再存一份，供「TTS 引擎插件 → 日志」页显示。
 *
 * 与 [io.legado.app.constant.AppLog] 的区别：
 *  - AppLog = 应用自身的正常日志；
 *  - PluginLog = 插件脚本打印的日志（量大、且只在调试插件时才关心），
 *    所以日志页默认只显示 AppLog，用户打开「插件日志」开关后才合并显示这里的内容。
 *
 * 只存内存，进程重启即清空；超出上限丢最旧的。
 */
object PluginLog {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val time: Long,
        val level: Level,
        val message: String,
    )

    /** 内存日志上限。插件脚本可能循环打印，不宜无限增长。 */
    private const val MAX_LOGS = 250

    private val logs = ArrayList<Entry>()

    /** 变更计数：界面据此判断是否需要重新装载，避免无意义的整表重建 */
    @Volatile
    var version: Int = 0
        private set

    @Synchronized
    fun put(level: Level, message: String) {
        if (message.isBlank()) return
        while (logs.size >= MAX_LOGS) {
            logs.removeLastOrNull()
        }
        logs.add(0, Entry(System.currentTimeMillis(), level, message))
        version++
    }

    /** 最新在前 */
    @Synchronized
    fun list(): List<Entry> = logs.toList()

    @Synchronized
    fun clear() {
        logs.clear()
        version++
    }
}
