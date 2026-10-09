package io.legado.app.help.webView

import android.content.Context
import android.content.MutableContextWrapper
import io.legado.app.ui.rss.read.VisibleWebView

class PooledWebView(
    val realWebView: VisibleWebView, // 真正的WebView实例
    val id: String // 唯一标识
) {
    var isInUse: Boolean = false // 是否正在被使用
    var lastUseTime: Long = 0 // 最后一次被使用的时间戳

    /**
     * 是否已被销毁。销毁过的 WebView 绝不能再回到池里被取用——
     * 拿到死实例的页面会永远空白、进度条卡住，而且只能重启 App 才恢复。
     */
    var destroyed: Boolean = false

    /** 是否正躺在闲置池里：重复 release 时用它判断能不能销毁 */
    var inIdlePool: Boolean = false

    fun upContext(context: Context): PooledWebView {
        val contextWrapper = realWebView.context as MutableContextWrapper
        contextWrapper.baseContext = context
        return this
    }
}