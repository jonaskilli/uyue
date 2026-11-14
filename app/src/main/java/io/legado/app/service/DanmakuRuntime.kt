package io.legado.app.service

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import io.legado.app.constant.AppLog
import okhttp3.Call
import splitties.init.appCtx

/**
 * AI 弹幕请求的全局闸门。
 *
 * 只有「三个弹幕界面之一可见 + 屏幕亮着 + 未锁屏」才允许发 AI 请求。
 * 失活时立即软暂停：不再排新区间、不再发新请求，但队列、缓存、已发射锚点全部保留；
 * 连续失活满 60 秒才硬停止（退出 worker、掐断在途请求）。
 * 因此误锁屏、快速切界面再回来都是零割裂，不会重新生成、不会重复计费。
 *
 * 这里只放纯数据和一个在途 [Call]，不持有任何 View / Fragment / LifecycleOwner / 回调，
 * 界面销毁后不存在「回调找不到界面」的问题。
 */
object DanmakuRuntime {

    /** 连续失活多久才真正收工（毫秒） */
    const val HARD_STOP_DELAY_MS = 60_000L

    @Volatile
    private var uiVisible = false

    @Volatile
    private var inactiveSince = 0L

    @Volatile
    private var hardStopped = false

    @Volatile
    private var inFlightCall: Call? = null

    /** 由共用弹幕宿主在界面 onStart / onStop / Activity 销毁时更新 */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
    }

    /** 实时查系统状态，不依赖广播；关屏、锁屏、亮屏解锁都不会漏判 */
    fun isActive(): Boolean {
        if (!uiVisible) return false
        val pm = appCtx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive != true) return false
        val km = appCtx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) return false
        return true
    }

    /** 软暂停：记录失活起点；返回 true 表示已连续失活满 60 秒，需要硬停止 */
    fun noteInactive(): Boolean {
        val now = System.currentTimeMillis()
        if (inactiveSince == 0L) {
            inactiveSince = now
            hardStopped = false
            AppLog.put("AI弹幕: 软暂停（无弹幕界面/关屏/锁屏），停止新增请求")
        }
        return !hardStopped && now - inactiveSince >= HARD_STOP_DELAY_MS
    }

    /** 重新可见：清掉失活计时，队列与缓存原样续用 */
    fun noteActive() {
        if (inactiveSince != 0L) {
            inactiveSince = 0L
            hardStopped = false
            AppLog.put("AI弹幕: 界面恢复，队列与缓存续用")
        }
    }

    /** 硬停止：掐断还在途的那一次请求，之后由 worker 自行退出 */
    fun hardStop() {
        hardStopped = true
        inFlightCall?.let { runCatching { it.cancel() } }
        inFlightCall = null
        AppLog.put("AI弹幕: 硬停止（连续失活60秒），取消在途请求")
    }

    /** 登记本次弹幕请求，供硬停止时取消 */
    fun registerCall(call: Call) {
        inFlightCall = call
    }

    /** 请求结束（成功 / 失败 / 被取消）后清掉引用；worker 串行，同一时刻只有一个 */
    fun clearInFlight() {
        inFlightCall = null
    }
}
