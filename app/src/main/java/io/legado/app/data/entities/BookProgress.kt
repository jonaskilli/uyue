package io.legado.app.data.entities

import io.legado.app.help.config.AppConfig

/**
 * 阅读进度（云端 `bookProgress/<书名>_<作者>.json` 的内容）。
 *
 * [deviceName] 是上传该进度时的**设备名称**（`备份与恢复 → 设备名称`，
 * 即 [AppConfig.webDavDeviceName]），用来区分：
 *  - 云端进度是**本机自己之前传的** → 用户主动把章节退回重看，属正常行为，
 *    不该被当成"云端进度更新"弹窗打扰，也不该被云端覆盖；
 *  - 云端进度来自**别的设备** → 这才是真正的跨设备进度同步，才提示用户。
 *
 * 刻意用「设备名称」而不是系统的 ANDROID_ID：设备名称用户可以自己改，
 * 想验证跨设备同步时把名称改一下就能模拟成另一台设备，不必真的拿两台手机。
 *
 * 旧版本上传的进度没有这个字段，反序列化为空串，[isFromSameDevice] 返回 false，
 * 按"未知设备"处理，保持原有的提示行为（宁可多提示一次，也不丢跨设备同步）。
 */
data class BookProgress(
    val name: String,
    val author: String,
    val durChapterIndex: Int,
    val durChapterPos: Int,
    val durChapterTime: Long,
    val durChapterTitle: String?,
    val deviceName: String = ""
) {
    constructor(book: Book) : this(
        name = book.name,
        author = book.author,
        durChapterIndex = book.durChapterIndex,
        durChapterPos = book.durChapterPos,
        durChapterTime = book.durChapterTime,
        durChapterTitle = book.durChapterTitle,
        deviceName = AppConfig.webDavDeviceName.orEmpty()
    )

    /**
     * 这份进度是否由本机（当前设备名）上传。
     * 空 [deviceName]（旧备份数据）返回 false，按未知设备处理。
     */
    fun isFromSameDevice(): Boolean {
        val current = AppConfig.webDavDeviceName.orEmpty()
        return deviceName.isNotBlank() && deviceName == current
    }
}