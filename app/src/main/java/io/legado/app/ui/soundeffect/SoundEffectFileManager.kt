package io.legado.app.ui.soundeffect

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.legado.app.constant.AppLog
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.EncoderUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 音效文件（JSON）的生成与写入。
 *
 * 音效文件格式为：
 * ```json
 * { "currentIndex": 0, "audios": ["base64...", "base64..."] }
 * ```
 * 播放时按 `currentIndex` 从 `audios` 中取出一个解码播放，
 * 随后索引加一并写回 JSON，因此多个音频会按顺序轮换播放。
 *
 * 存放目录与朗读服务读取的是同一个（[BaseReadAloudService.SOUND_EFFECT_DIR]）。
 */
object SoundEffectFileManager {

    /** 音效文件存放目录 */
    val dir: File get() = File(BaseReadAloudService.SOUND_EFFECT_DIR)

    /** 音效名对应的 JSON 文件，规则标记 (打雷音效) 对应 打雷音效.json */
    fun fileOf(name: String): File = File(dir, "$name.json")

    /**
     * 从 Uri 取出不带扩展名的文件名，用于批量添加时直接作为音效名。
     * 取不到时回退为时间戳。
     */
    fun displayNameOf(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { name ->
                    val dot = name.lastIndexOf('.')
                    val base = if (dot > 0) name.substring(0, dot) else name
                    if (base.isNotBlank()) return base
                }
            }
        }
        return uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: System.currentTimeMillis().toString()
    }

    /** 读取 Uri 的字节内容 */
    fun readBytes(context: Context, uri: Uri): ByteArray? =
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            AppLog.put("[音效文件] 读取失败：$uri, ${e.message}")
            null
        }

    /**
     * 删除某个音效 JSON 此前解码产生的所有 mp3 文件。
     *
     * 朗读服务解码时把音频存为同名前缀的 mp3，
     * 命名规则见 `BaseReadAloudService.downloadAndDecodeEffect()`：
     * `打雷音效.json` → `打雷音效_0.mp3`、`打雷音效_1.mp3`……
     *
     * 虽然解码每次都会覆盖同名 mp3，但新音效的音频数量变少时，
     * 多出来的旧 mp3 会残留下来。覆盖写入前先清理，
     * 顺便避免写入中途失败时残留旧内容被播放。
     */
    fun deleteDerivedMp3(name: String): Int {
        val prefix = name + "_"
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile
                && file.name.startsWith(prefix)
                && file.name.endsWith(".mp3")
                && file.delete()
            ) {
                count++
            }
        }
        if (count > 0) {
            AppLog.put("[音效文件] 清理旧 mp3：$name，共 $count 个")
        }
        return count
    }

    /** 保存结果：[error] 为 null 表示成功 */
    data class SaveResult(val error: String?, val cleanedMp3: Int = 0)

    /**
     * 把多个音频写入一个音效文件。
     *
     * 覆盖同名音效时，会先删除该音效此前解码产生的 mp3，
     * 避免旧音频文件残留。
     *
     * @param name 音效名，对应规则中的标记内容
     * @param audios 音频字节数组，多个会在播放时按顺序轮换
     * @param overwrite 同名文件已存在时是否覆盖
     */
    fun save(
        name: String,
        audios: List<ByteArray>,
        overwrite: Boolean = true
    ): SaveResult {
        if (name.isBlank()) return SaveResult("音效名为空")
        if (audios.isEmpty()) return SaveResult("没有音频内容")
        val file = fileOf(name)
        if (file.exists() && !overwrite) return SaveResult("已存在同名音效：$name")
        // 写入前先清掉该音效此前解码出来的 mp3。
        // 即使 JSON 已被手工删除，残留的 mp3 也一并清掉。
        val cleanedMp3 = deleteDerivedMp3(name)
        val tmp = File(file.absolutePath + ".tmp")
        return try {
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            audios.forEach { arr.put(EncoderUtils.base64Encode(it)) }
            val json = JSONObject().apply {
                put("currentIndex", 0)
                put("audios", arr)
            }
            tmp.writeText(json.toString())
            if (file.exists()) {
                runCatching { file.copyTo(File(file.absolutePath + ".bak"), true) }
            }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, true)
                tmp.delete()
            }
            AppLog.put(
                "[音效文件] 已保存：${file.absolutePath}，${audios.size} 个音频，" +
                    "清理旧 mp3 $cleanedMp3 个"
            )
            SaveResult(null, cleanedMp3)
        } catch (e: Exception) {
            tmp.delete()
            AppLog.put("[音效文件] 保存失败：${e.message}", e)
            SaveResult("保存失败：${e.message}", cleanedMp3)
        }
    }

    /**
     * 把一个音频写入一个独立的音效文件，音效名取自文件名。
     * 用于批量添加。
     */
    fun saveSingle(
        name: String,
        audio: ByteArray,
        overwrite: Boolean = true
    ): SaveResult = save(name, listOf(audio), overwrite)
}
