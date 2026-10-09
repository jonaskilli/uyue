package io.legado.app.help.audiobook

/**
 * TTS 合成日志：只记录「合成的是哪段文本、合成是否成功、失败了报什么错」。
 *
 * 与 [PluginLog] 的分工：
 *  - TtsSynthLog = 合成过程本身的关键节点（开始/成功/重试/失败），
 *    对齐 TTS_Server_Android 默认日志里只看合成结果的习惯；
 *  - PluginLog   = 插件脚本自己 console.log 打印的内容，量大，默认不显示，
 *    需要在日志页打开「插件日志」开关才会合并进来。
 *
 * 只存内存，进程重启即清空；超出上限丢最旧的。
 */
object TtsSynthLog {

    /** 级别对齐 TTS_Server_Android 的配色：DEBUG 蓝、INFO 绿、SUCCESS 跟随主题、WARN 黄、ERROR 红 */
    enum class Level { DEBUG, INFO, SUCCESS, WARN, ERROR }

    data class Entry(
        val time: Long,
        val level: Level,
        val message: String,
    )

    /** 内存日志上限。逐句朗读会产生很多条，不宜无限增长。 */
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

    /** 字节数转成可读大小（用于「合成成功 xx KB」） */
    fun sizeToReadable(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024f)
        else -> "${bytes}B"
    }
}
