package io.legado.app.help.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.legado.app.utils.getBoolean
import io.legado.app.utils.putBoolean
import io.legado.app.utils.putLong
import io.legado.app.utils.putString
import io.legado.app.utils.remove
import splitties.init.appCtx

@Suppress("ConstPropertyName")
object LocalConfig : SharedPreferences
by appCtx.getSharedPreferences("local", Context.MODE_PRIVATE) {

    private const val versionCodeKey = "appVersionCode"

    /**
     * 本地密码,用来对需要备份的敏感信息加密,如 webdav 配置等
     */
    var password: String?
        get() = getString("password", null)
        set(value) {
            if (value != null) {
                putString("password", value)
            } else {
                remove("password")
            }
        }

    var lastBackup: Long
        get() = getLong("lastBackup", 0)
        set(value) {
            // 同步落盘: 备份刚写完时常被清理工具杀掉进程, apply() 的异步写入可能
            // 来不及落盘, 时间戳回退到旧值会让下次启动误判成"云端备份领先当前"
            edit(commit = true) {
                putLong("lastBackup", value)
            }
        }

    var privacyPolicyOk: Boolean
        get() = getBoolean("privacyPolicyOk")
        set(value) {
            putBoolean("privacyPolicyOk", value)
        }

    val readHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readHelpVersion", "firstRead")

    val backupHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "backupHelpVersion", "firstBackup")

    val readMenuHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "readMenuHelpVersion", "firstReadMenu")

    val bookSourcesHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "bookSourceHelpVersion", "firstOpenBookSources")

    val webDavBookHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "webDavBookHelpVersion", "firstOpenWebDavBook")

    val ruleHelpVersionIsLast: Boolean
        get() = isLastVersion(1, "ruleHelpVersion")

    val needUpHttpTTS: Boolean
        // 6 → 7：让老用户（httpTtsVersion 早已置成 6）也重新走一次默认引擎导入。
        // 起因：「内置TTS插件」的 id 始终是正数 1780805418993（HttpTTS.BUILTIN_TTS_PLUGIN_ID），
        // 不在 deleteDefault(id < 0) 的覆盖范围内，而 insert 会主键冲突使整批导入回滚，
        // 内置引擎因此永远无法随 APP 升级更新。现改为按固定 id 先删后插，
        // 见 DefaultData.importDefaultHttpTTS；版本号升到 7 才会触发这次重新导入。
        get() = !isLastVersion(7, "httpTtsVersion")

    val needUpTxtTocRule: Boolean
        get() = !isLastVersion(3, "txtTocRuleVersion")

    val needUpRssSources: Boolean
        get() = !isLastVersion(6, "rssSourceVersion")

    val needUpDictRule: Boolean
        get() = !isLastVersion(2, "needUpDictRule")

    var versionCode
        get() = getLong(versionCodeKey, 0)
        set(value) {
            edit { putLong(versionCodeKey, value) }
        }

    /**
     * 升级置位：下次取用 WebView 时清一次磁盘缓存与旧 cookie。
     * 旧版（修复前）会把源站偶发 4xx / 过期会话写进 WebView 磁盘缓存与
     * android.webkit.CookieManager，清空应用数据前一直复现；升级后清掉即可。
     */
    var needClearWebViewCache: Boolean
        get() = getBoolean("needClearWebViewCache", false)
        set(value) {
            edit { putBoolean("needClearWebViewCache", value) }
        }

    val isFirstOpenApp: Boolean
        get() {
            val value = getBoolean("firstOpen", true)
            if (value) {
                edit { putBoolean("firstOpen", false) }
            }
            return value
        }

    @Suppress("SameParameterValue")
    private fun isLastVersion(
        lastVersion: Int,
        versionKey: String,
        firstOpenKey: String? = null
    ): Boolean {
        var version = getInt(versionKey, 0)
        if (version == 0 && firstOpenKey != null) {
            if (!getBoolean(firstOpenKey, true)) {
                version = 1
            }
        }
        if (version < lastVersion) {
            edit { putInt(versionKey, lastVersion) }
            return false
        }
        return true
    }

    var bookInfoDeleteAlert: Boolean
        get() = getBoolean("bookInfoDeleteAlert", true)
        set(value) {
            putBoolean("bookInfoDeleteAlert", value)
        }

    var deleteBookOriginal: Boolean
        get() = getBoolean("deleteBookOriginal")
        set(value) {
            putBoolean("deleteBookOriginal", value)
        }

    var appCrash: Boolean
        get() = getBoolean("appCrash")
        set(value) {
            putBoolean("appCrash", value)
        }

}
