package io.legado.app.help.video

import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 视频书源播放白名单。
 *
 * App 拦截书源播放器是为了接管进度与选集，但有的书源自己的播放页本来就带选集、
 * 清晰度、倍速（如番茄），拦截下来反而让这些功能失效，而且它的播放页是个网页、
 * 拿不到可直连的流地址。
 *
 * 加入白名单的书源一律跳过拦截，直接走 [VideoSourcePlayer.play]，
 * 由书源自己拉起它的播放器，行为与改动前完全一致。
 */
object VideoConfig {

    /**
     * 以换行分隔存储。bookSourceUrl 是单行文本，不会与分隔符冲突，
     * 也就不需要引入 JSON 解析与随之而来的类型/混淆问题。
     */
    private const val keyWhitelist = "videoSourceWhitelist"

    private fun readWhitelistRaw(): String =
        appCtx.getPrefString(keyWhitelist, "") ?: ""

    private fun writeWhitelistRaw(raw: String) {
        appCtx.putPrefString(keyWhitelist, raw)
    }

    fun whitelist(): Set<String> =
        readWhitelistRaw().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun isWhitelisted(bookSourceUrl: String): Boolean =
        bookSourceUrl.isNotBlank() && whitelist().contains(bookSourceUrl)

    fun setWhitelisted(bookSourceUrl: String, whitelisted: Boolean) {
        if (bookSourceUrl.isBlank()) return
        val set = whitelist().toMutableSet()
        if (whitelisted) {
            set.add(bookSourceUrl)
        } else {
            set.remove(bookSourceUrl)
        }
        writeWhitelistRaw(set.joinToString("\n"))
    }

    /** @return 切换后的状态 */
    fun toggle(bookSourceUrl: String): Boolean {
        val next = !isWhitelisted(bookSourceUrl)
        setWhitelisted(bookSourceUrl, next)
        return next
    }
}
