package io.legado.app.model.readify

import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import org.json.JSONObject
import splitties.init.appCtx

/**
 * 悦读（Readify）网页账号会话。
 *
 * 会话来源与 TTS 插件「本悦读 v12」完全一致：网页登录成功后取 localStorage 的 `user` 键
 * （插件 defVars.readifyAccountSession.binding = "locals.user"），内容是一整串账号 JSON，
 * 内含 refreshToken / userId / nickname 等；后续接口只用 refreshToken 去换短期 userToken。
 *
 * 两个必须遵守的服务端约定：
 * 1) 每次调用 /api/users/tokens 都会**轮换** refreshToken，续期成功后必须回填落盘，
 *    否则下一次冷启动就只能用旧令牌，直接 200001。
 * 2) 并发续期会互相作废（同一个旧令牌被用两次），所以续期必须串行——见 ReadifyApi.renewMutex。
 */
@Suppress("unused")
object ReadifySession {

    /** 与插件里的 loginUrl 一致：桌面 UA 下走这个路径才能进登录页（/login 不存在） */
    const val LOGIN_URL = "https://app.readifyai.com/library/voices"

    /** PluginLoginActivity 的 binding：locals.xxx → localStorage.getItem('xxx') */
    const val LOGIN_BINDING = "locals.user"

    /** 强制电脑模式 UA：悦读按 UA 分流，手机 UA 会被甩到 App 下载页 */
    const val LOGIN_UA = "pc"

    /** 网页提取出来的原始会话 JSON 整串 */
    var raw: String?
        get() = appCtx.getPrefString(PreferKey.readifySession)?.takeIf { it.isNotBlank() }
        private set(value) = appCtx.putPrefString(PreferKey.readifySession, value)

    val isLogin: Boolean get() = refreshToken != null

    val refreshToken: String?
        get() = json()?.optString("refreshToken")?.takeIf { it.isNotBlank() }

    val userId: String?
        get() = json()?.optString("userId")?.takeIf { it.isNotBlank() }

    /** 用于菜单上显示"已登录：xxx" */
    val displayName: String
        get() {
            val json = json() ?: return ""
            return json.optString("nickname").takeIf { it.isNotBlank() }
                ?: json.optString("email").takeIf { it.isNotBlank() }
                ?: json.optString("readerId")
        }

    private fun json(): JSONObject? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** 登录页保存后写入；返回值是否为合法账号 JSON */
    fun save(rawValue: String): Boolean {
        val json = runCatching { JSONObject(rawValue) }.getOrNull() ?: return false
        if (json.optString("refreshToken").isBlank()) return false
        raw = rawValue
        return true
    }

    /** 续期时服务端轮换了 refreshToken → 回填到会话 JSON 里 */
    fun updateRefreshToken(newToken: String) {
        val json = json() ?: return
        json.put("refreshToken", newToken)
        raw = json.toString()
    }

    fun clear() {
        appCtx.putPrefString(PreferKey.readifySession, null)
    }
}
