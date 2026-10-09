package io.legado.app.help.book

import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import splitties.init.appCtx

/**
 * AI 生图「按书绑定」配置的读写封装。
 *
 * ### 绑定维度：书名
 * 旧版以 `bookUrl` 为 key 后缀存储，更换书源后 bookUrl 会变化，导致原先绑定的
 * 开关/模板失效。这里改为以【书名】为维度，换书源后仍然有效。
 *
 * ### 兼容与降级
 * - 读：优先读「书名 key」；不存在时回退「旧版 bookUrl key」的值，并顺手迁移到书名 key，
 *   保证升级后旧绑定不丢。
 * - 写：同时写「书名 key」和「旧版 bookUrl key」两份。这样用户把 App 降级回旧版本后，
 *   旧版本读到的仍是同一份值，不会出现新旧数据不一致、开关状态错乱的问题。
 * - 若书名为空（异常兜底），自动退化为只按 bookUrl 读写的旧行为。
 */
object AiImageBookPrefs {

    private val prefs
        get() = appCtx.defaultSharedPreferences

    /** 书名 key 不存在时返回 null */
    private fun nameKey(prefix: String, bookName: String?): String? =
        if (bookName.isNullOrBlank()) null else prefix + bookName

    /** bookUrl key 不存在时返回 null */
    private fun urlKey(prefix: String, bookUrl: String?): String? =
        if (bookUrl.isNullOrBlank()) null else prefix + bookUrl

    /**
     * 读取布尔型按书配置：书名优先，回退旧版 bookUrl 并迁移。
     */
    fun getBoolean(
        namePrefix: String,
        urlPrefix: String,
        bookName: String?,
        bookUrl: String?,
        default: Boolean,
    ): Boolean {
        val nKey = nameKey(namePrefix, bookName)
        if (nKey != null && prefs.contains(nKey)) {
            return appCtx.getPrefBoolean(nKey, default)
        }
        val uKey = urlKey(urlPrefix, bookUrl)
        if (uKey != null && prefs.contains(uKey)) {
            val value = appCtx.getPrefBoolean(uKey, default)
            // 迁移旧值到书名 key，之后即以书名为准
            if (nKey != null) appCtx.putPrefBoolean(nKey, value)
            return value
        }
        return default
    }

    /**
     * 写入布尔型按书配置：书名 key 与旧版 bookUrl key 同时写，便于降级回旧版本。
     */
    fun putBoolean(
        namePrefix: String,
        urlPrefix: String,
        bookName: String?,
        bookUrl: String?,
        value: Boolean,
    ) {
        nameKey(namePrefix, bookName)?.let { appCtx.putPrefBoolean(it, value) }
        urlKey(urlPrefix, bookUrl)?.let { appCtx.putPrefBoolean(it, value) }
    }

    /**
     * 读取字符串型按书配置：书名优先，回退旧版 bookUrl 并迁移。
     */
    fun getString(
        namePrefix: String,
        urlPrefix: String,
        bookName: String?,
        bookUrl: String?,
    ): String? {
        val nKey = nameKey(namePrefix, bookName)
        if (nKey != null && prefs.contains(nKey)) {
            return appCtx.getPrefString(nKey)
        }
        val uKey = urlKey(urlPrefix, bookUrl)
        if (uKey != null && prefs.contains(uKey)) {
            val value = appCtx.getPrefString(uKey)
            if (value != null && nKey != null) appCtx.putPrefString(nKey, value)
            return value
        }
        return null
    }

    /**
     * 写入字符串型按书配置（value 为 null 表示清除）：书名 key 与旧版 bookUrl key 同时处理。
     */
    fun putString(
        namePrefix: String,
        urlPrefix: String,
        bookName: String?,
        bookUrl: String?,
        value: String?,
    ) {
        val nKey = nameKey(namePrefix, bookName)
        val uKey = urlKey(urlPrefix, bookUrl)
        if (value == null) {
            nKey?.let { appCtx.removePref(it) }
            uKey?.let { appCtx.removePref(it) }
        } else {
            nKey?.let { appCtx.putPrefString(it, value) }
            uKey?.let { appCtx.putPrefString(it, value) }
        }
    }
}
