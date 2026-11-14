package io.legado.app.ui.book.read.config

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AppConfig
import io.legado.app.model.DanmakuItem
import io.legado.app.model.DanmakuReady
import io.legado.app.model.DanmakuStore
import io.legado.app.model.ReadBook
import io.legado.app.model.VnParagraph
import io.legado.app.service.DanmakuRuntime
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.observeEvent

/**
 * 朗读面板 / 视觉小说舞台 / 全屏 AI 大图共用的弹幕宿主。
 *
 * 三个界面用**同一个 DanmakuView 实例**：界面切换时只把这个 View 换挂到当前界面窗口，
 * 不清空在屏弹幕、不重建订阅。当前章数据、已发射锚点、发射时机也只有一份，
 * 因此互相切换时弹幕接着飘，不会消失、不会把飘过的整批重新发一遍。
 */
class DanmakuHost(private val activity: ReadBookActivity) {

    private data class Owner(val decor: ViewGroup?, var visible: Boolean)

    /** 按显示顺序登记；最后一个即当前最上层界面 */
    private val owners = LinkedHashMap<String, Owner>()
    private var view: DanmakuView? = null
    private var observed = false

    private var currentDanmaku: List<DanmakuItem> = emptyList()
    private var danmakuKey: String? = null
    private val firedAnchors = mutableSetOf<Int>()

    /** 当前是否有任一弹幕界面在前台（后续接「仅在界面可见才请求 AI」时可直接用） */
    val isAnyUiVisible: Boolean get() = owners.isNotEmpty()

    /** 界面 onStart：登记活跃界面，把共享 View 挂到它的窗口最上层 */
    fun enter(owner: String, decor: ViewGroup?, visible: Boolean = true) {
        owners[owner] = Owner(decor, visible)
        DanmakuRuntime.setUiVisible(true)
        observe()
        attachTo(decor, visible)
    }

    /** 界面 onStop：撤销登记；仍有界面时把 View 挂回最上层的那个 */
    fun exit(owner: String) {
        owners.remove(owner)
        // 三个界面之间互切时集合始终非空，不会误停请求；全离开才关闸门
        DanmakuRuntime.setUiVisible(owners.isNotEmpty())
        if (owners.isEmpty()) {
            view?.visibility = View.GONE
            view?.pause()
        } else {
            val top = owners.entries.last().value
            attachTo(top.decor, top.visible)
        }
    }

    /** 朗读面板半屏模式不显示弹幕，全屏恢复；状态记住，切界面回来不会错位 */
    fun setVisible(visible: Boolean, owner: String = "readAloud") {
        owners[owner]?.visible = visible
        val v = view ?: return
        if (owners.keys.lastOrNull() == owner) applyVisibility(v, visible)
    }

    /** Activity 销毁：真正移除 View 并清空全部状态 */
    fun destroy() {
        DanmakuRuntime.setUiVisible(false)
        val v = view ?: return
        v.keepStateOnDetach = false
        (v.parent as? ViewGroup)?.removeView(v)
        v.clearDanmaku()
        view = null
        owners.clear()
        currentDanmaku = emptyList()
        firedAnchors.clear()
        danmakuKey = null
        observed = false
    }

    private fun attachTo(decor: ViewGroup?, visible: Boolean) {
        val target = decor ?: return
        val v = view ?: DanmakuView(activity).also {
            it.keepStateOnDetach = true
            view = it
        }
        if (v.parent !== target) {
            (v.parent as? ViewGroup)?.removeView(v)
            target.addView(
                v,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        applyVisibility(v, visible)
    }

    private fun applyVisibility(v: DanmakuView, visible: Boolean) {
        val show = visible && owners.isNotEmpty() && AppConfig.danmakuEnabled
        v.visibility = if (show) View.VISIBLE else View.GONE
        if (show) v.resume() else v.pause()
    }

    /** 事件只在 Activity 生命周期上订阅一次，不随界面切换重复注册 */
    private fun observe() {
        if (observed) return
        observed = true
        runCatching {
            val book = ReadBook.book
            val title = ReadBook.curTextChapter?.title
            if (book != null && !title.isNullOrEmpty()) {
                danmakuKey = DanmakuStore.key
                currentDanmaku = DanmakuStore.get("${book.name}|$title")
            }
        }
        activity.observeEvent<DanmakuReady>(EventBus.DANMAKU_READY) { ready ->
            if (ready.key != danmakuKey) {
                danmakuKey = ready.key
                firedAnchors.clear()
                view?.clearDanmaku()
            }
            currentDanmaku = ready.items
        }
        activity.observeEvent<VnParagraph>(EventBus.VN_PARAGRAPH) { p ->
            val target = view ?: return@observeEvent
            if (owners.isEmpty()) return@observeEvent
            if (!AppConfig.danmakuEnabled || currentDanmaku.isEmpty()) return@observeEvent
            currentDanmaku.filter { it.para <= p.index && firedAnchors.add(it.para) }
                .forEach { item ->
                    val keyAtSchedule = danmakuKey
                    target.postDanmakuDelayed(DANMAKU_LAUNCH_DELAY_MS) { v ->
                        // 延迟期间切章或关闭开关：丢弃，避免旧弹幕在新章开头冒出来
                        if (danmakuKey == keyAtSchedule) v.show(item.text)
                    }
                }
        }
        activity.observeEvent<String>(EventBus.READ_ALOUD_CHAPTER_CHANGED) {
            currentDanmaku = emptyList()
            firedAnchors.clear()
            view?.clearDanmaku()
        }
    }

    private companion object {
        /** 发射延后 1 秒，让弹幕出现时朗读刚好讲到这里，不提前剧透 */
        const val DANMAKU_LAUNCH_DELAY_MS = 1000L
    }
}
