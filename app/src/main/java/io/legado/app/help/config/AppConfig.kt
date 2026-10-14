package io.legado.app.help.config

import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import io.legado.app.BuildConfig
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.book.AiImageBookPrefs
import io.legado.app.utils.GSON
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefFloat
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isNightMode
import io.legado.app.utils.parseIpsFromString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefFloat
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.sysConfiguration
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.io.File
import io.legado.app.utils.FileUtils
import java.net.InetAddress

@Suppress("MemberVisibilityCanBePrivate", "ConstPropertyName")
object AppConfig : SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * 代码编辑器默认主题：素影清晖（themeFileNames 里的 l_quiet）。
     * 索引与 CodeEditViewModel.themeFileNames 一一对应。
     */
    const val EDIT_THEME_QUIET_LIGHT = 7

    // 迁移：部分 key 在旧版本中以 Int 形式存储，但对应 Preference（EditTextPreference）
    // 初始化时调用 getString() 会触发 ClassCastException 导致打开设置界面崩溃。
    // 在对象初始化时将这些残留 Int 值转为 String，确保后续读取安全。
    init {
        val sp = appCtx.defaultSharedPreferences
        val keysToMigrate = listOf(
            PreferKey.readAloudImageInterval,
            PreferKey.readAloudCoverWidth,
            PreferKey.readAloudCoverTopMargin,
            PreferKey.readAloudImageInReadPageWidth,
            PreferKey.readAloudImageInReadPageTextSizeReduce
        )
        for (key in keysToMigrate) {
            val v = sp.all[key]
            // 数值型一律转成字符串存回。只处理 Int 时，曾以 Float/Long 落盘的机器仍会崩溃。
            if (v != null && v !is String) {
                sp.edit().remove(key).apply()
                sp.edit().putString(key, v.toString()).apply()
            }
        }
        // 旧版 readAloudImageInReadPage 既是「显示开关」也是「手势落点」。
        // 改版后它只作为「手势开关」，图片是否显示由 readAloudImageShowing 单独记录。
        // 升级时若旧值为真，则默认让图片继续显示（并开启手势），避免老用户图片凭空消失。
        if (sp.all[PreferKey.readAloudImageShowing] == null
            && sp.getBoolean(PreferKey.readAloudImageInReadPage, false)
        ) {
            sp.edit().putBoolean(PreferKey.readAloudImageShowing, true).apply()
        }
    }

    val isCronet = appCtx.getPrefBoolean(PreferKey.cronet)
    var useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)
    var userAgent: String = getPrefUserAgent()
    var customHosts = appCtx.getPrefString(PreferKey.customHosts)
    // 代码编辑器主题索引，与 CodeEditViewModel.themeFileNames 一一对应：
    // 0=d_monokai_dimmed 1=d_monokai 2=d_modern 3=l_modern 4=d_solarized 5=l_solarized 6=d_abyss 7=l_quiet
    // 默认用 7（素影清晖）：原先默认 0（暗色）在浅色界面下显得发黑
    var editTheme = appCtx.getPrefInt(PreferKey.editTheme, EDIT_THEME_QUIET_LIGHT)
    var editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)
    var editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)
    var isEInkMode = appCtx.getPrefString(PreferKey.themeMode) == "3"
    var clickActionTL = appCtx.getPrefInt(PreferKey.clickActionTL, 2)
    var clickActionTC = appCtx.getPrefInt(PreferKey.clickActionTC, 2)
    var clickActionTR = appCtx.getPrefInt(PreferKey.clickActionTR, 1)
    var clickActionML = appCtx.getPrefInt(PreferKey.clickActionML, 2)
    var clickActionMC = appCtx.getPrefInt(PreferKey.clickActionMC, 0)
    var clickActionMR = appCtx.getPrefInt(PreferKey.clickActionMR, 1)
    var clickActionBL = appCtx.getPrefInt(PreferKey.clickActionBL, 2)
    var clickActionBC = appCtx.getPrefInt(PreferKey.clickActionBC, 1)
    var clickActionBR = appCtx.getPrefInt(PreferKey.clickActionBR, 1)
    var themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
    var useDefaultCover = appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)
    var optimizeRender = CanvasRecorderFactory.isSupport
            && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)
    var recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
    /**
     * 书源规则里的 `java.toast` / `java.longToast` 是否真的弹提示。
     *
     * 用 getter 而不是初始化时求值：object 的属性只会算一次，写成 `var x = getPrefBoolean()`
     * 在设置页改完开关后这里仍是旧值。默认 false——不少视频源在正文规则里 toast
     * "正在加载第N集"，播放/切集时反复弹窗很碍眼，关掉后只写日志。
     */
    var sourceToast: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.sourceToast, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.sourceToast, value)
        }

    /**
     * 禁止书源弹出任何验证：图片验证码对话框（`java.getVerificationCode`）与验证网页
     * （`java.startBrowserAwait`、带验证标记的 startBrowser）一律不弹。
     *
     * 开了之后需要验证码才能过的源会直接失败（规则里抛异常），不会再打断操作。
     * 注意：视频播放走的是 `startBrowser(videoMode=true)`，**不属于验证**，不受这个开关影响。
     */
    var disableSourceVerification: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableSourceVerification, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableSourceVerification, value)
        }
    var editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
    var editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
    var editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
    var editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
    var showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
    var adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.editFontScale -> editFontScale = appCtx.getPrefInt(PreferKey.editFontScale, 16)
            PreferKey.editNonPrintable -> editNonPrintable = appCtx.getPrefInt(PreferKey.editNonPrintable, 0)
            PreferKey.editAutoWrap -> editAutoWrap = appCtx.getPrefBoolean(PreferKey.editAutoWrap, true)
            PreferKey.editAutoComplete -> editAutoComplete = appCtx.getPrefBoolean(PreferKey.editAutoComplete, true)
            PreferKey.showBoardLine -> showBoardLine = appCtx.getPrefInt(PreferKey.showBoardLine, 1)
            PreferKey.adaptSpecialStyle -> adaptSpecialStyle = appCtx.getPrefBoolean(PreferKey.adaptSpecialStyle, true)

            PreferKey.themeMode -> {
                themeMode = appCtx.getPrefString(PreferKey.themeMode, "0")
                isEInkMode = themeMode == "3"
            }

            PreferKey.clickActionTL -> clickActionTL =
                appCtx.getPrefInt(PreferKey.clickActionTL, 2)

            PreferKey.clickActionTC -> clickActionTC =
                appCtx.getPrefInt(PreferKey.clickActionTC, 2)

            PreferKey.clickActionTR -> clickActionTR =
                appCtx.getPrefInt(PreferKey.clickActionTR, 1)

            PreferKey.clickActionML -> clickActionML =
                appCtx.getPrefInt(PreferKey.clickActionML, 2)

            PreferKey.clickActionMC -> clickActionMC =
                appCtx.getPrefInt(PreferKey.clickActionMC, 0)

            PreferKey.clickActionMR -> clickActionMR =
                appCtx.getPrefInt(PreferKey.clickActionMR, 1)

            PreferKey.clickActionBL -> clickActionBL =
                appCtx.getPrefInt(PreferKey.clickActionBL, 2)

            PreferKey.clickActionBC -> clickActionBC =
                appCtx.getPrefInt(PreferKey.clickActionBC, 1)

            PreferKey.clickActionBR -> clickActionBR =
                appCtx.getPrefInt(PreferKey.clickActionBR, 1)

            PreferKey.readBodyToLh -> ReadBookConfig.readBodyToLh =
                appCtx.getPrefBoolean(PreferKey.readBodyToLh, true)

            PreferKey.useZhLayout -> ReadBookConfig.useZhLayout =
                appCtx.getPrefBoolean(PreferKey.useZhLayout)

            PreferKey.userAgent -> userAgent = getPrefUserAgent()

            PreferKey.customHosts -> {
                customHosts = appCtx.getPrefString(PreferKey.customHosts)
                _hostMap = null
                _addressCache = null
            }

            PreferKey.editTheme -> editTheme = appCtx.getPrefInt(PreferKey.editTheme, EDIT_THEME_QUIET_LIGHT)

            PreferKey.editThemeDark -> editThemeDark = appCtx.getPrefInt(PreferKey.editThemeDark, 0)

            PreferKey.editTemeAuto -> editTemeAuto = appCtx.getPrefBoolean(PreferKey.editTemeAuto)

            PreferKey.antiAlias -> useAntiAlias = appCtx.getPrefBoolean(PreferKey.antiAlias)

            PreferKey.useDefaultCover -> useDefaultCover =
                appCtx.getPrefBoolean(PreferKey.useDefaultCover, false)

            PreferKey.optimizeRender -> optimizeRender = CanvasRecorderFactory.isSupport
                    && appCtx.getPrefBoolean(PreferKey.optimizeRender, false)

            PreferKey.recordLog -> recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)

        }
    }

    //dns配置
    private var _hostMap: Map<String, Any?>? = null
    val hostMap: Map<String, Any?>
        get() = _hostMap ?: run {
            val cache = GSON.fromJsonObject<Map<String, Any?>>(customHosts).getOrNull() ?: emptyMap()
            _hostMap = cache
            cache
        }
    private var _addressCache: Map<String, List<InetAddress>>? = null
    val addressCache: Map<String, List<InetAddress>>
        get() = _addressCache ?: run {
            val cache = hostMap.mapNotNull { (host, ipValue) ->
                val addresses = when (ipValue) {
                    is String -> ipValue.parseIpsFromString()
                    is List<*> -> ipValue.parseIpsFromList()
                    else -> null
                }
                addresses?.let { host to it }
            }.toMap()
            _addressCache = cache
            cache
        }
    private fun List<*>.parseIpsFromList(): List<InetAddress> =
        mapNotNull { element ->
            (element as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?.runCatching { InetAddress.getByName(this) }
                ?.getOrNull()
        }

    var isNightTheme: Boolean
        get() = when (themeMode) {
            "1" -> false
            "2" -> true
            "3" -> false
            else -> sysConfiguration.isNightMode
        }
        set(value) {
            if (isNightTheme != value) {
                if (value) {
                    appCtx.putPrefString(PreferKey.themeMode, "2")
                } else {
                    appCtx.putPrefString(PreferKey.themeMode, "1")
                }
            }
        }
    var showBookname: Int
        get() = appCtx.getPrefInt(PreferKey.showBooknameLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.showBooknameLayout, value)
        }
    var bookshelfMargin: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfMargin, 12)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfMargin, value)
        }

    var showUnread: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showUnread, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showUnread, value)
        }

    var showLastUpdateTime: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showLastUpdateTime, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showLastUpdateTime, value)
        }

    var showWaitUpCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showWaitUpCount, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showWaitUpCount, value)
        }

    var readBrightness: Int
        get() = if (isNightTheme) {
            appCtx.getPrefInt(PreferKey.nightBrightness, 100)
        } else {
            appCtx.getPrefInt(PreferKey.brightness, 100)
        }
        set(value) {
            if (isNightTheme) {
                appCtx.putPrefInt(PreferKey.nightBrightness, value)
            } else {
                appCtx.putPrefInt(PreferKey.brightness, value)
            }
        }

    val textSelectAble: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.textSelectAble, true)

    val isTransparentStatusBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.transparentStatusBar, true)

    val immNavigationBar: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.immNavigationBar, true)

    val screenOrientation: String?
        get() = appCtx.getPrefString(PreferKey.screenOrientation)

    var bookGroupStyle: Int
        get() = appCtx.getPrefInt(PreferKey.bookGroupStyle, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookGroupStyle, value)
        }

    var bookshelfLayout: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfLayout, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfLayout, value)
        }

    var saveTabPosition: Int
        get() = appCtx.getPrefInt(PreferKey.saveTabPosition, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.saveTabPosition, value)
        }

    var bookExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookExportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookExportFileName, value)
        }

    var episodeExportFileName: String?
        get() = appCtx.getPrefString(PreferKey.episodeExportFileName, "")
        set(value) {
            appCtx.putPrefString(PreferKey.episodeExportFileName, value)
        }

    var bookImportFileName: String?
        get() = appCtx.getPrefString(PreferKey.bookImportFileName)
        set(value) {
            appCtx.putPrefString(PreferKey.bookImportFileName, value)
        }

    var backupPath: String?
        get() = appCtx.getPrefString(PreferKey.backupPath)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.backupPath)
            } else {
                appCtx.putPrefString(PreferKey.backupPath, value)
            }
        }

    var defaultBookTreeUri: String?
        get() = appCtx.getPrefString(PreferKey.defaultBookTreeUri)
        set(value) {
            if (value.isNullOrEmpty()) {
                appCtx.removePref(PreferKey.defaultBookTreeUri)
            } else {
                appCtx.putPrefString(PreferKey.defaultBookTreeUri, value)
            }
        }

    val showDiscovery: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showDiscovery, true)

    val showVideo: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showVideo, true)

    val showRSS: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showRss, true)

    val autoRefreshBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoRefresh)

    val onlyUpdateRead: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.onlyUpdateRead)

    var enableReview: Boolean
        get() = BuildConfig.DEBUG && appCtx.getPrefBoolean(PreferKey.enableReview, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReview, value)
        }

    var threadCount: Int
        get() = appCtx.getPrefInt(PreferKey.threadCount, 16)
        set(value) {
            appCtx.putPrefInt(PreferKey.threadCount, value)
        }

    var remoteServerId: Long
        get() = appCtx.getPrefLong(PreferKey.remoteServerId)
        set(value) {
            appCtx.putPrefLong(PreferKey.remoteServerId, value)
        }

    var importBookPath: String?
        get() = appCtx.getPrefString("importBookPath")
        set(value) {
            if (value == null) {
                appCtx.removePref("importBookPath")
            } else {
                appCtx.putPrefString("importBookPath", value)
            }
        }

    var ttsFlowSys: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsFollowSys, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsFollowSys, value)
        }

    /**
     * TTS 音频缓存开关
     * 启用后会在朗读时保存音频文件到缓存目录
     */
    var ttsCacheEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsCacheEnabled, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.ttsCacheEnabled, value)
        }

    val noAnimScrollPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.noAnimScrollPage, false)

    const val defaultSpeechRate = 5

    /** 精细刻度默认进度：50 → 显示 1.00 */
    const val defaultSpeechRateFine = defaultSpeechRate * 10

    /**
     * 朗读界面语速滑块原始进度（精细刻度 0~450，步进 0.01）：
     * 显示系数 = (ttsSpeechRateFine + 50) / 100f，默认 50 → 1.00。
     * 首次读取时自动从旧版 ttsSpeechRate（0~45）迁移（×10），老用户取值不变。
     */
    var ttsSpeechRateFine: Int
        get() {
            val stored = appCtx.getPrefInt(PreferKey.ttsSpeechRateFine, Int.MIN_VALUE)
            if (stored != Int.MIN_VALUE) return stored.coerceIn(0, 450)
            val migrated = appCtx.getPrefInt(PreferKey.ttsSpeechRate, defaultSpeechRate) * 10
            appCtx.putPrefInt(PreferKey.ttsSpeechRateFine, migrated)
            return migrated
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsSpeechRateFine, value.coerceIn(0, 450))
        }

    /** 旧版粗刻度（0~45，步进 0.1）：供引擎参数/缓存 key 等整数刻度消费方使用，由精细值派生 */
    var ttsSpeechRate: Int
        get() = Math.round(ttsSpeechRateFine / 10f)
        set(value) {
            ttsSpeechRateFine = value * 10
        }

    var ttsTimer: Int
        get() = appCtx.getPrefInt(PreferKey.ttsTimer, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.ttsTimer, value)
        }

    val speechRatePlayFine: Int get() = if (ttsFlowSys) defaultSpeechRateFine else ttsSpeechRateFine
    val speechRatePlay: Int get() = if (ttsFlowSys) defaultSpeechRate else ttsSpeechRate

    var chineseConverterType: Int
        get() = appCtx.getPrefInt(PreferKey.chineseConverterType)
        set(value) {
            appCtx.putPrefInt(PreferKey.chineseConverterType, value)
        }

    var systemTypefaces: Int
        get() = appCtx.getPrefInt(PreferKey.systemTypefaces)
        set(value) {
            appCtx.putPrefInt(PreferKey.systemTypefaces, value)
        }

    var elevation: Int
        get() = if (isEInkMode) 0 else appCtx.getPrefInt(
            PreferKey.barElevation,
            AppConst.sysElevation
        )
        set(value) {
            appCtx.putPrefInt(PreferKey.barElevation, value)
        }

    var readUrlInBrowser: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readUrlOpenInBrowser)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readUrlOpenInBrowser, value)
        }

    var exportCharset: String
        get() {
            val c = appCtx.getPrefString(PreferKey.exportCharset)
            if (c.isNullOrBlank()) {
                return "UTF-8"
            }
            return c
        }
        set(value) {
            appCtx.putPrefString(PreferKey.exportCharset, value)
        }

    var exportUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportUseReplace, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportUseReplace, value)
        }

    var exportToWebDav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportToWebDav)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportToWebDav, value)
        }

    var exportNoChapterName: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportNoChapterName)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportNoChapterName, value)
        }

    var enableCustomExport: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableCustomExport, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableCustomExport, value)
        }

    var exportType: Int
        get() = appCtx.getPrefInt(PreferKey.exportType)
        set(value) {
            appCtx.putPrefInt(PreferKey.exportType, value)
        }
    var exportPictureFile: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportPictureFile, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportPictureFile, value)
        }

    var parallelExportBook: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.parallelExportBook, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.parallelExportBook, value)
        }

    var exportAddAnnotation: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.exportAddAnnotation, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.exportAddAnnotation, value)
        }

    var changeSourceCheckAuthor: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceCheckAuthor)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceCheckAuthor, value)
        }

    var ttsEngine: String?
        get() = appCtx.getPrefString(PreferKey.ttsEngine)
        set(value) {
            appCtx.putPrefString(PreferKey.ttsEngine, value)
        }

    /**
     * 系统TTS引擎包名
     * 用于系统TTS模式下的定时激活和前台保活
     */
    var sysTtsPackageName: String?
        get() = appCtx.getPrefString(PreferKey.sysTtsPackageName)
        set(value) {
            appCtx.putPrefString(PreferKey.sysTtsPackageName, value)
        }

    var webPort: Int
        get() = appCtx.getPrefInt(PreferKey.webPort, 1122)
        set(value) {
            appCtx.putPrefInt(PreferKey.webPort, value)
        }

    var tocUiUseReplace: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocUiUseReplace)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocUiUseReplace, value)
        }

    var tocCountWords: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.tocCountWords, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.tocCountWords, value)
        }

    var enableReadRecord: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.enableReadRecord, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableReadRecord, value)
        }

    val autoChangeSource: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoChangeSource, true)

    var changeSourceLoadInfo: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadInfo)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadInfo, value)
        }

    var changeSourceLoadToc: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadToc)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadToc, value)
        }

    var changeSourceLoadWordCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceLoadWordCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceLoadWordCount, value)
        }

    var changeSourceShowCacheCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceShowCacheCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceShowCacheCount, value)
        }

    var changeSourceSortByChapterCount: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.changeSourceSortByChapterCount)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.changeSourceSortByChapterCount, value)
        }

    var openBookInfoByClickTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.openBookInfoByClickTitle, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.openBookInfoByClickTitle, value)
        }

    var showBookshelfFastScroller: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showBookshelfFastScroller, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showBookshelfFastScroller, value)
        }

    var contentSelectSpeakMod: Int
        get() = appCtx.getPrefInt(PreferKey.contentSelectSpeakMod)
        set(value) {
            appCtx.putPrefInt(PreferKey.contentSelectSpeakMod, value)
        }

    var batchChangeSourceDelay: Int
        get() = appCtx.getPrefInt(PreferKey.batchChangeSourceDelay)
        set(value) {
            appCtx.putPrefInt(PreferKey.batchChangeSourceDelay, value)
        }

    val importKeepName get() = appCtx.getPrefBoolean(PreferKey.importKeepName)
    val importKeepGroup get() = appCtx.getPrefBoolean(PreferKey.importKeepGroup)
    var importKeepEnable: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importKeepEnable, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importKeepEnable, value)
        }
    var importShowComment: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.importShowComment, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.importShowComment, value)
        }

    var previewImageByClick: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.previewImageByClick, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.previewImageByClick, value)
        }

    val clickImgWay: String?
        get() = appCtx.getPrefString(PreferKey.clickImgWay)

    var preDownloadNum
        get() = appCtx.getPrefInt(PreferKey.preDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.preDownloadNum, value)
        }

    val syncBookProgress get() = appCtx.getPrefBoolean(PreferKey.syncBookProgress, true)

    val syncBookProgressPlus get() = appCtx.getPrefBoolean(PreferKey.syncBookProgressPlus, false)

    val mediaButtonOnExit get() = appCtx.getPrefBoolean("mediaButtonOnExit", true)

    val readAloudByMediaButton
        get() = appCtx.getPrefBoolean(PreferKey.readAloudByMediaButton, false)

    val replaceEnableDefault get() = appCtx.getPrefBoolean(PreferKey.replaceEnableDefault, true)

    val webDavDir get() = appCtx.getPrefString(PreferKey.webDavDir, "legado")

    val webDavDeviceName get() = appCtx.getPrefString(PreferKey.webDavDeviceName, Build.MODEL)

    val recordHeapDump get() = appCtx.getPrefBoolean(PreferKey.recordHeapDump, false)

    val loadCoverOnlyWifi get() = appCtx.getPrefBoolean(PreferKey.loadCoverOnlyWifi, false)

    val showAddToShelfAlert get() = appCtx.getPrefBoolean(PreferKey.showAddToShelfAlert, true)

    val ignoreAudioFocus get() = appCtx.getPrefBoolean(PreferKey.ignoreAudioFocus, false)

    var pauseReadAloudWhilePhoneCalls
        get() = appCtx.getPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.pauseReadAloudWhilePhoneCalls, value)

    val onlyLatestBackup get() = appCtx.getPrefBoolean(PreferKey.onlyLatestBackup, true)

    /**
     * webDav备份保留数量,小于等于0为不限制
     */
    var webDavBackupKeepCount: Int
        get() = appCtx.getPrefInt(PreferKey.webDavBackupKeepCount, 30)
        set(value) = appCtx.putPrefInt(PreferKey.webDavBackupKeepCount, value)

    val autoCheckNewBackup get() = appCtx.getPrefBoolean(PreferKey.autoCheckNewBackup, true)

    val defaultHomePage get() = appCtx.getPrefString(PreferKey.defaultHomePage, "bookshelf")

    val updateToVariant get() = appCtx.getPrefString(PreferKey.updateToVariant, "default_version")

    val streamReadAloudAudio get() = appCtx.getPrefBoolean(PreferKey.streamReadAloudAudio, false)

    val doublePageHorizontal: String?
        get() = appCtx.getPrefString(PreferKey.doublePageHorizontal)

    val progressBarBehavior: String?
        get() = appCtx.getPrefString(PreferKey.progressBarBehavior, "page")

    val keyPageOnLongPress
        get() = appCtx.getPrefBoolean(PreferKey.keyPageOnLongPress, false)

    val volumeKeyPage
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPage, false)

    val volumeKeyPageOnPlay
        get() = appCtx.getPrefBoolean(PreferKey.volumeKeyPageOnPlay, true)

    val mouseWheelPage
        get() = appCtx.getPrefBoolean(PreferKey.mouseWheelPage, true)

    val paddingDisplayCutouts
        get() = appCtx.getPrefBoolean(PreferKey.paddingDisplayCutouts, false)

    var searchScope: String
        get() = appCtx.getPrefString("searchScope") ?: ""
        set(value) {
            appCtx.putPrefString("searchScope", value)
        }

    var searchGroup: String
        get() = appCtx.getPrefString("searchGroup") ?: ""
        set(value) {
            appCtx.putPrefString("searchGroup", value)
        }

    var pageTouchSlop: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchSlop, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchSlop, value)
        }

    var pageTouchClick: Int
        get() = appCtx.getPrefInt(PreferKey.pageTouchClick, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.pageTouchClick, value)
        }

    var bookshelfSort: Int
        get() = appCtx.getPrefInt(PreferKey.bookshelfSort, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.bookshelfSort, value)
        }

    fun getBookSortByGroupId(groupId: Long): Int {
        return appDb.bookGroupDao.getByID(groupId)?.getRealBookSort()
            ?: bookshelfSort
    }

    private fun getPrefUserAgent(): String {
        val ua = appCtx.getPrefString(PreferKey.userAgent)
        if (ua.isNullOrBlank()) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + BuildConfig.Cronet_Main_Version + " Safari/537.36"
        }
        return ua
    }

    var bitmapCacheSize: Int
        get() = appCtx.getPrefInt(PreferKey.bitmapCacheSize, 50)
        set(value) {
            appCtx.putPrefInt(PreferKey.bitmapCacheSize, value)
        }

    var imageRetainNum: Int
        get() = appCtx.getPrefInt(PreferKey.imageRetainNum, 0)
        set(value) {
            appCtx.putPrefInt(PreferKey.imageRetainNum, value)
        }

    var showReadTitleBarAddition: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadTitleAddition, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.showReadTitleAddition, value)
        }
    var readBarStyleFollowPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readBarStyleFollowPage, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.readBarStyleFollowPage, value)
        }

    var sourceEditMaxLine: Int
        get() {
            val maxLine = appCtx.getPrefInt(PreferKey.sourceEditMaxLine, Int.MAX_VALUE)
            if (maxLine < 10) {
                return Int.MAX_VALUE
            }
            return maxLine
        }
        set(value) {
            appCtx.putPrefInt(PreferKey.sourceEditMaxLine, value)
        }

    var audioPlayUseWakeLock: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.audioPlayWakeLock)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.audioPlayWakeLock, value)
        }

    var brightnessVwPos: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.brightnessVwPos)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.brightnessVwPos, value)
        }

    fun detectClickArea() {
        if (clickActionTL * clickActionTC * clickActionTR
            * clickActionML * clickActionMC * clickActionMR
            * clickActionBL * clickActionBC * clickActionBR != 0
        ) {
            appCtx.putPrefInt(PreferKey.clickActionMC, 0)
            appCtx.toastOnUi("当前没有配置菜单区域,自动恢复中间区域为菜单.")
        }
    }

    val showMangaUi: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showMangaUi, true)

    var disableMangaScale: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaScale, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaScale, value)
        }

    var disableMangaPageAnim: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.disableMangaPageAnim, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableMangaPageAnim, value)
        }

    var mangaPreDownloadNum
        get() = appCtx.getPrefInt(PreferKey.mangaPreDownloadNum, 10)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaPreDownloadNum, value)
        }

    var disableClickScroll
        get() = appCtx.getPrefBoolean(PreferKey.disableClickScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableClickScroll, value)
        }

    var mangaAutoPageSpeed
        get() = appCtx.getPrefInt(PreferKey.mangaAutoPageSpeed, 3)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaAutoPageSpeed, value)
        }

    var mangaFooterConfig
        get() = appCtx.getPrefString(PreferKey.mangaFooterConfig, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaFooterConfig, value)
        }

    var enableMangaHorizontalScroll
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaHorizontalScroll, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaHorizontalScroll, value)
        }

    var mangaColorFilter
        get() = appCtx.getPrefString(PreferKey.mangaColorFilter, "")
        set(value) {
            appCtx.putPrefString(PreferKey.mangaColorFilter, value)
        }

    var hideMangaTitle
        get() = appCtx.getPrefBoolean(PreferKey.hideMangaTitle, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.hideMangaTitle, value)
        }

    var enableMangaEInk
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaEInk, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaEInk, value)
        }

    var mangaEInkThreshold
        get() = appCtx.getPrefInt(PreferKey.mangaEInkThreshold, 150)
        set(value) {
            appCtx.putPrefInt(PreferKey.mangaEInkThreshold, value)
        }

    var disableHorizontalPageSnap
        get() = appCtx.getPrefBoolean(PreferKey.disableHorizontalPageSnap, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.disableHorizontalPageSnap, value)
        }

    var enableMangaGray
        get() = appCtx.getPrefBoolean(PreferKey.enableMangaGray, false)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.enableMangaGray, value)
        }

    var welcomeImage
        get() = appCtx.getPrefString(PreferKey.welcomeImage)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImage, value)
        }

    var welcomeShowText
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowText, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowText, value)
        }

    var welcomeShowIcon
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIcon, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIcon, value)
        }

    var welcomeImageDark
        get() = appCtx.getPrefString(PreferKey.welcomeImageDark)
        set(value) {
            appCtx.putPrefString(PreferKey.welcomeImageDark, value)
        }

    var welcomeShowTextDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowTextDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowTextDark, value)
        }

    var welcomeShowIconDark
        get() = appCtx.getPrefBoolean(PreferKey.welcomeShowIconDark, true)
        set(value) {
            appCtx.putPrefBoolean(PreferKey.welcomeShowIconDark, value)
        }

    // ================= 自定义功能区域 Start =================

    // 1. 听书预加载数量
    val audioPreDownloadNum: Int
        get() {
            val str = appCtx.getPrefString("audioPreDownloadNum")
            return str?.toIntOrNull() ?: 1
        }

    // 2. 音频缓存保留时间 (返回毫秒)
    val audioCacheCleanTime: Long
        get() {
            val str = appCtx.getPrefString("audioCacheCleanTime")
            val minutes = str?.toLongOrNull() ?: 10L
            return minutes * 60 * 1000L
        }

    // 3. 朗读标题开关 (修复预缓存 bug 用)
    var readAloudTitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudTitle, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudTitle, value)

    // 4. 朗读完章节自动合并音频
    var autoMergeAudioOnChapterEnd: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoMergeAudioOnChapterEnd, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.autoMergeAudioOnChapterEnd, value)

    // 5. 合并后转换为MP3 (64kbps)
    var convertToMp3AfterMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.convertToMp3AfterMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.convertToMp3AfterMerge, value)

    // 6. 合并时保存文本
    var saveTextWithMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.saveTextWithMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.saveTextWithMerge, value)

    // 6.1 合并时保存为视频（复用已保存的AI图片，静态帧+烧入字幕，只保留 MP4）
    var saveVideoWithMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.saveVideoWithMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.saveVideoWithMerge, value)

    // 7. 朗读缓存音频统一转换为 WAV (24000Hz, 16bit, 单声道)
    var convertCacheToWav: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.convertCacheToWav, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.convertCacheToWav, value)

    // 7.1 转换为 WAV 时的音量增益倍数（默认 1.0，最大 3.0，超过 3.0 自动截断为 3.0）
    // 注意：EditTextPreference 存储的是 String，不能用 getFloat/putFloat
    var convertCacheToWavGain: Float
        get() = appCtx.getPrefString(PreferKey.convertCacheToWavGain, "1.0")
            ?.toFloatOrNull()?.coerceIn(0f, 3f) ?: 1.0f
        set(value) = appCtx.putPrefString(PreferKey.convertCacheToWavGain, value.toString())

    // TTS 后处理音频参数（全局级别，默认 1.0，范围 0.1~3.0）
    var ttsPostSpeed: Float
        get() = appCtx.getPrefString(PreferKey.ttsPostSpeed, "1.0")
            ?.toFloatOrNull()?.coerceIn(0.1f, 3f) ?: 1.0f
        set(value) = appCtx.putPrefString(PreferKey.ttsPostSpeed, value.toString())

    var ttsPostVolume: Float
        get() = appCtx.getPrefString(PreferKey.ttsPostVolume, "1.0")
            ?.toFloatOrNull()?.coerceIn(0.1f, 3f) ?: 1.0f
        set(value) = appCtx.putPrefString(PreferKey.ttsPostVolume, value.toString())

    var ttsPostPitch: Float
        get() = appCtx.getPrefString(PreferKey.ttsPostPitch, "1.0")
            ?.toFloatOrNull()?.coerceIn(0.1f, 3f) ?: 1.0f
        set(value) = appCtx.putPrefString(PreferKey.ttsPostPitch, value.toString())

    // 快节奏播放（去除静音）：合成阶段 PCM 裁剪，默认关闭，最小静音时长默认 200ms
    var ttsSilenceSkipEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsSilenceSkipEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.ttsSilenceSkipEnabled, value)

    var ttsSilenceSkipMinMs: Int
        get() = appCtx.getPrefInt(PreferKey.ttsSilenceSkipMinMs, 200)
            .coerceIn(150, 2000)
        set(value) = appCtx.putPrefInt(PreferKey.ttsSilenceSkipMinMs, value.coerceIn(150, 2000))

    // 朗读播放时的音量增益倍数（默认 1.0，范围 0.5~5.0，超过自动截断为 5.0）
    var readAloudVolumeGain: Float
        get() = appCtx.getPrefString(PreferKey.readAloudVolumeGain, "1.0")
            ?.toFloatOrNull()?.coerceIn(0.5f, 5.0f) ?: 1.0f
        set(value) = appCtx.putPrefString(
            PreferKey.readAloudVolumeGain,
            value.coerceIn(0.5f, 5.0f).toString()
        )

    // 6.1 超短音频判定阈值（秒），设为0表示不限制
    // 注意：EditTextPreference 存储的是 String，不能用 getFloat/putFloat
    var shortAudioMinDuration: Float
        get() = appCtx.getPrefString(PreferKey.shortAudioMinDuration, "0.1")?.toFloatOrNull() ?: 0.1f
        set(value) = appCtx.putPrefString(PreferKey.shortAudioMinDuration, value.toString())

    // 7. 字幕每行最大中文字符数
    var srtSubtitleMaxChars: Int
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.srtSubtitleMaxChars, "15")?.toIntOrNull() ?: 15
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.srtSubtitleMaxChars, value.toString()).apply()

    // 8. 字幕时间偏移量（秒，支持负数）
    var srtSubtitleTimeOffset: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.srtSubtitleTimeOffset, "0")?.toFloatOrNull() ?: 0f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.srtSubtitleTimeOffset, value.toString()).apply()

    // 9. 静音匹配范围（秒）- 字幕时间对齐到静音位置的范围
    var srtSilenceMatchRange: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.srtSilenceMatchRange, "0.5")?.toFloatOrNull() ?: 0.5f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.srtSilenceMatchRange, value.toString()).apply()

    // 9.1 保存为视频时的字幕样式
    // 字幕字号相对默认大小的比例，1.3 = 当前默认大小
    var videoSubtitleFontSizeScale: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleFontSizeScale, "1.3")?.toFloatOrNull() ?: 1.3f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleFontSizeScale, value.toString()).apply()

    // 字幕垂直偏移（画面高度比例），正数上移，负数下移，默认 0
    var videoSubtitleVOffset: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleVOffset, "0")?.toFloatOrNull() ?: 0f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleVOffset, value.toString()).apply()

    // 字幕是否按句（标点）切割，默认 false（整段作为一条字幕）
    var videoSubtitleSplit: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.videoSubtitleSplit, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.videoSubtitleSplit, value)

    // 字幕填充色（十六进制，如 #FF000000），默认金黄 #FFFFDD00
    var videoSubtitleFontColor: String
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleFontColor, "#FFFFDD00") ?: "#FFFFDD00"
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleFontColor, value).apply()

    // 描边粗细（相对字号的比例），默认 0.12（描边宽 = 字号 × 0.12）
    var videoSubtitleStrokeWidth: Float
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleStrokeWidth, "0.12")?.toFloatOrNull() ?: 0.12f
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleStrokeWidth, value.toString()).apply()

    // 描边颜色（十六进制，如 #FFFFFFFF），默认黑色 #FF000000
    var videoSubtitleStrokeColor: String
        get() = appCtx.defaultSharedPreferences.getString(PreferKey.videoSubtitleStrokeColor, "#FF000000") ?: "#FF000000"
        set(value) = appCtx.defaultSharedPreferences.edit().putString(PreferKey.videoSubtitleStrokeColor, value).apply()

    // 10. 朗读对话框显示封面/字幕/进度条
    var showReadAloudCoverSubtitle: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showReadAloudCoverSubtitle, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.showReadAloudCoverSubtitle, value)

    // 朗读界面是否隐藏当前章节进度条，默认 true 隐藏（开启即去掉进度条）
    var readAloudHideChapterProgress: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudHideChapterProgress, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudHideChapterProgress, value)

    // 朗读界面小图（封面/本地图）与顶部的距离，单位 dp，默认 16
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var readAloudCoverTopMargin: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudCoverTopMargin, "16")
                ?.toIntOrNull() ?: 16
        } catch (e: ClassCastException) {
            // 兼容旧版本误存为 Int 的情况
            appCtx.getPrefInt(PreferKey.readAloudCoverTopMargin, 16)
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudCoverTopMargin, value.toString()).apply()

    // 11. 朗读对话框封面宽度（dp），默认240，高度按340/240比例自动匹配
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var readAloudCoverWidth: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudCoverWidth, "240")
                ?.toIntOrNull() ?: 240
        } catch (e: ClassCastException) {
            // 兼容旧版存的是 int 的情况
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.readAloudCoverWidth, 240)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readAloudCoverWidth, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudCoverWidth, value.toString()).apply()

    /**
     * 手动清理 TTS 缓存
     * 同时清理转发器缓存和系统TTS缓存
     */
    fun clearTtsCache() {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        FileUtils.delete(baseDir.absolutePath + File.separator + "httpTTS")
        FileUtils.delete(baseDir.absolutePath + File.separator + "httpTTS_cache")
        // 清理系统TTS缓存（与 TTSReadAloudService 的 ttsFolderPath 保持一致）
        val sysTtsCacheDir = File(appCtx.externalCacheDir, "systemTTS")
        if (sysTtsCacheDir.exists()) {
            FileUtils.listDirsAndFiles(sysTtsCacheDir.absolutePath)?.forEach {
                FileUtils.delete(it.absolutePath)
            }
        }
    }

    /**
     * 删除当前正在阅读/朗读章节的缓存音频（HttpTTS + 系统TTS）。
     * @return 删除的文件数量；若无法定位当前章节则返回 -1
     */
    fun deleteCurrentChapterCache(): Int {
        val book = io.legado.app.model.ReadBook.book ?: return -1
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, io.legado.app.model.ReadBook.durChapterIndex)
            ?: return -1
        return io.legado.app.service.HttpTtsAudioCache.deleteBookChapterCache(book, chapter)
    }

    /**
     * 删除当前正在阅读/朗读章节的已识别角色缓存（AI 章节 json）。
     * 与目录界面章节右侧删除图标使用同一套路径与特殊字符规则（AiCacheFileUtil）。
     * @return true 表示成功删除（或本就没有该文件），false 表示无法定位当前章节
     */
    fun deleteCurrentChapterRoleCache(): Boolean {
        val book = io.legado.app.model.ReadBook.book ?: return false
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, io.legado.app.model.ReadBook.durChapterIndex)
            ?: return false
        io.legado.app.ui.book.toc.AiCacheFileUtil.deleteChapterCache(book, chapter)
        return true
    }

    /**
     * 删除当前正在阅读/朗读章节中，所有名为 [roleName] 的角色所在段落的缓存音频（HttpTTS + 系统TTS）。
     * @return 删除的文件数量（0 表示未定位到当前章节或无匹配）
     */
    fun deleteCurrentChapterRoleAudioCache(roleName: String): Int {
        val book = io.legado.app.model.ReadBook.book ?: return 0
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, io.legado.app.model.ReadBook.durChapterIndex)
            ?: return 0
        return io.legado.app.service.HttpTtsAudioCache.deleteChapterRoleAudioCache(book, chapter, roleName)
    }

    // 系统TTS单句合成超时（秒），默认120秒
    var sysTtsSynthesizeTimeout: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.sysTtsSynthesizeTimeout, "120")
                ?.toIntOrNull()?.coerceAtLeast(5)?.coerceAtMost(300) ?: 120
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.sysTtsSynthesizeTimeout, 120)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.sysTtsSynthesizeTimeout, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.sysTtsSynthesizeTimeout, value.toString()).apply()

    // TTS合成失败重试次数，默认3次
    var ttsRetryCount: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.ttsRetryCount, "3")
                ?.toIntOrNull()?.coerceAtLeast(1)?.coerceAtMost(10) ?: 3
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.ttsRetryCount, 3)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.ttsRetryCount, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.ttsRetryCount, value.toString()).apply()

    // TTS合成失败超过最大次数后是否跳过（静音代替），默认开启
    var ttsRetrySkipOnFail: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.ttsRetrySkipOnFail, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.ttsRetrySkipOnFail, value)

    /**
     * 实时更新音频开关。
     * 开启后：朗读中检测到 gengxin.json 信号时，停止朗读 → 删除所有缓存 → 重新开始朗读。
     * 默认关闭。
     */
    var gengxinRealtimeUpdate: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.gengxinRealtimeUpdate, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.gengxinRealtimeUpdate, value)

    // --- 背景音乐 (BGM) 配置 ---
    
    var isBgmEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bgmEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.bgmEnabled, value)

    var soundEffectMode: String
        get() = appCtx.getPrefString(PreferKey.soundEffectMode, "off") ?: "off"
        set(value) = appCtx.putPrefString(PreferKey.soundEffectMode, value)

    /**
     * 当前书籍音效模式。值为 follow / off / normal / all。
     * - follow：跟随全局 [soundEffectMode]
     * - 其它：仅对当前书生效，覆盖全局设置
     */
    fun getBookSoundEffectMode(bookUrl: String): String {
        if (bookUrl.isBlank()) return "follow"
        return appCtx.getPrefString(PreferKey.bookSoundEffectModePrefix + bookUrl, "follow") ?: "follow"
    }

    fun setBookSoundEffectMode(bookUrl: String, mode: String) {
        if (bookUrl.isBlank()) return
        appCtx.putPrefString(PreferKey.bookSoundEffectModePrefix + bookUrl, mode)
    }

    /**
     * 获取当前书籍实际生效的音效模式：
     * 若书籍配置为 follow 则用全局 [soundEffectMode]，否则用书籍专属配置。
     */
    fun getEffectiveSoundEffectMode(bookUrl: String): String {
        val bookMode = getBookSoundEffectMode(bookUrl)
        return if (bookMode == "follow") soundEffectMode else bookMode
    }

    var soundEffectOffsetChars: Int
        get() = appCtx.getPrefInt(PreferKey.soundEffectOffsetChars, 5)
        set(value) = appCtx.putPrefInt(PreferKey.soundEffectOffsetChars, value)

    var soundEffectVolume: Int
        get() = appCtx.getPrefInt(PreferKey.soundEffectVolume, 80)
        set(value) = appCtx.putPrefInt(PreferKey.soundEffectVolume, value)

    /** 合并章节音频时是否将音效混音进完整 WAV */
    var mixSoundEffectOnMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mixSoundEffectOnMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.mixSoundEffectOnMerge, value)

    // --- AI 音效（广播剧音效库）---

    private const val DEFAULT_AI_EFFECT_DIR =
        "/storage/emulated/0/Download/chajian/广播剧音效库"

    /** AI 音效总开关 */
    var aiEffectEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiEffectEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiEffectEnabled, value)

    /** 音效库本地根目录，结构与远端仓库一致 */
    var aiEffectLocalDir: String
        get() = appCtx.getPrefString(PreferKey.aiEffectLocalDir, DEFAULT_AI_EFFECT_DIR)
            ?: DEFAULT_AI_EFFECT_DIR
        set(value) = appCtx.putPrefString(PreferKey.aiEffectLocalDir, value)

    /** 单次最多处理的章节数量，防止误填把整本书都跑一遍 */
    private const val MAX_AI_CHAPTER_FIX_COUNT = 50

    /** AI 章节处理的默认接口（本地网关），格式：接口地址@@模型名@@密钥 */
    private const val DEFAULT_AI_CHAPTER_FIX_API = "http://localhost:8801/v1@@g3@@00"

    /**
     * AI 章节修复（去广告/错别字）的接口配置，
     * 格式与「AI 切歌」一致：接口地址@@模型名@@密钥
     */
    var aiChapterFixApi: String
        get() = appCtx.getPrefString(PreferKey.aiChapterFixApi)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AI_CHAPTER_FIX_API
        set(value) = appCtx.putPrefString(PreferKey.aiChapterFixApi, value)

    /** AI 章节修复提示词，为空表示用内置默认 */
    var aiChapterFixPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiChapterFixPrompt) ?: ""
        set(value) = appCtx.putPrefString(PreferKey.aiChapterFixPrompt, value)

    /** AI 章节处理：自动处理开关。开启后每次切换章节，自动处理后续章节 */
    var aiChapterFixAuto: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiChapterFixAuto, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiChapterFixAuto, value)

    /** AI 章节处理：每次处理的章节数量（自动处理与批量处理共用） */
    var aiChapterFixCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiChapterFixCount, 1)
        set(value) = appCtx.putPrefInt(PreferKey.aiChapterFixCount, value.coerceIn(1, MAX_AI_CHAPTER_FIX_COUNT))

    /**
     * AI 章节处理：超长章节拆分阈值（字数），0 表示不拆分。
     * 章节总字数超过该值时，按阈值均分成多块、逐块送 AI 处理（刀口落在段落边界，不切断段落）。
     */
    var aiChapterFixMaxLength: Int
        get() = appCtx.getPrefInt(PreferKey.aiChapterFixMaxLength, 0)
        set(value) = appCtx.putPrefInt(PreferKey.aiChapterFixMaxLength, value.coerceAtLeast(0))

    /** AI 章节处理：请求失败或返回无法解析时的重试次数，0 表示不重试 */
    var aiChapterFixRetryCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiChapterFixRetryCount, 0)
        set(value) = appCtx.putPrefInt(PreferKey.aiChapterFixRetryCount, value.coerceAtLeast(0))

    /**
     * AI 章节处理：发给 AI 时附带的上下文参考字数，0 表示不附带。
     * 处理某章时在其正文上、下分别附上上一章结尾 / 下一章开头该字数的内容，
     * 供 AI 理解断章处的情节，上下文本身不会被修改。
     */
    var aiChapterFixContextLength: Int
        get() = appCtx.getPrefInt(PreferKey.aiChapterFixContextLength, 0)
        set(value) = appCtx.putPrefInt(PreferKey.aiChapterFixContextLength, value.coerceAtLeast(0))

    /**
     * AI 章节处理：单次请求的超时时间（秒，从发起到响应结束的总时限），默认 180。
     * 长章节的 AI 分析生成较慢时可调大，下限 10 秒，上限不限制。
     */
    var aiChapterFixTimeoutSeconds: Int
        get() = appCtx.getPrefInt(PreferKey.aiChapterFixTimeoutSeconds, 180)
        set(value) = appCtx.putPrefInt(PreferKey.aiChapterFixTimeoutSeconds, value.coerceAtLeast(10))

    /**
     * 阶段一每段提取关键字的**上限**，实际数量按段落字数递减（每 100 字约 12 个）。
     * 关键字限制在 1~2 字（见 [AiEffectAnalyzer.KEYWORD_PROMPT]），检索命中率高。
     */
    var aiEffectKeywordCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiEffectKeywordCount, 12)
        set(value) = appCtx.putPrefInt(PreferKey.aiEffectKeywordCount, value)

    /** 阶段二最多选用几个音效 */
    var aiEffectMaxPick: Int
        get() = appCtx.getPrefInt(PreferKey.aiEffectMaxPick, 3)
        set(value) = appCtx.putPrefInt(PreferKey.aiEffectMaxPick, value)

    /** 每个关键字最多取几条候选 */
    var aiEffectCandidatesPerKeyword: Int
        get() = appCtx.getPrefInt(PreferKey.aiEffectCandidatesPerKeyword, 40)
        set(value) = appCtx.putPrefInt(PreferKey.aiEffectCandidatesPerKeyword, value)

    /**
     * 候选总量上限：**所有关键字的检索结果合并去重后**的最终条数。
     * 也就是发给 AI 挑选的候选列表长度，防止泛词（如"声"）撑爆 prompt。
     */
    var aiEffectMaxCandidates: Int
        get() = appCtx.getPrefInt(PreferKey.aiEffectMaxCandidates, 300)
        set(value) = appCtx.putPrefInt(PreferKey.aiEffectMaxCandidates, value)

    /**
     * AI 音效的攒批字数。**直接复用 BGM 的字数滑块** [bgmAICharInterval]
     * （范围 100~1000，默认 350），不再单独存一份，避免两处设置不一致。
     * 攒够这么多字就把期间所有未分析段落打包成一批，一次 AI 调用处理完。
     */
    val aiEffectCharInterval: Int
        get() = bgmAICharInterval

    /** 导出章节音频时是否混入 AI 选中的音效 */
    var aiEffectMixOnMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.aiEffectMixOnMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.aiEffectMixOnMerge, value)

    var bgmPath: String?
        get() = appCtx.getPrefString(PreferKey.bgmPath)
        set(value) = appCtx.putPrefString(PreferKey.bgmPath, value)

    var bgmVolume: Int
        get() = appCtx.getPrefInt(PreferKey.bgmVolume, 30)
        set(value) = appCtx.putPrefInt(PreferKey.bgmVolume, value)

    /**
     * 合并章节音频 / 合成视频时是否混入背景音乐。
     * 混入时使用的音量与朗读播放一致，取自 [bgmVolume]。
     */
    var mixBgmOnMerge: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.mixBgmOnMerge, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.mixBgmOnMerge, value)

    /**
     * 混入背景音乐时，单首最少持续时长（毫秒，用字符串存储以便 EditTextPreference 兼容）。
     * 当前这首铺够该时长后，遇到下一个候选切换点才切换，避免音乐过于零碎。
     */
    var bgmMinSegmentMs: Long
        get() = appCtx.getPrefString(PreferKey.bgmMinSegmentMs, "60000")
            ?.toLongOrNull()?.coerceAtLeast(0L) ?: 60000L
        set(value) = appCtx.putPrefString(PreferKey.bgmMinSegmentMs, value.toString())

    var bgmAIEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.bgmAIEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.bgmAIEnabled, value)

    var bgmAICharInterval: Int
        get() = appCtx.getPrefInt(PreferKey.bgmAICharInterval, 350)
        set(value) = appCtx.putPrefInt(PreferKey.bgmAICharInterval, value)

    /**
     * 下一段上一段朗读：开启后开始朗读时从第一段开始
     */
    var readAloudStartFromFirst: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudStartFromFirst, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudStartFromFirst, value)

    /**
     * 系统TTS当前使用的voice名称
     * 用于生成/匹配系统TTS缓存文件名
     */
    var sysTtsVoiceName: String
        get() = appCtx.getPrefString(PreferKey.sysTtsVoiceName, "default") ?: "default"
        set(value) = appCtx.putPrefString(PreferKey.sysTtsVoiceName, value)

    // 12. 朗读封面跑马灯边框开关
    var readAloudCoverMarqueeEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudCoverMarqueeEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudCoverMarqueeEnabled, value)

    // 13. 朗读封面跑马灯边框速度（毫秒），默认3000，值越小越快
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var readAloudCoverMarqueeSpeed: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudCoverMarqueeSpeed, "3000")
                ?.toIntOrNull() ?: 3000
        } catch (e: ClassCastException) {
            // 兼容旧版存的是 int 的情况
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.readAloudCoverMarqueeSpeed, 3000)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readAloudCoverMarqueeSpeed, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudCoverMarqueeSpeed, value.toString()).apply()

    // 14. 跑马灯与BGM联动开关
    var marqueeBgmLinkEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.marqueeBgmLinkEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.marqueeBgmLinkEnabled, value)

    // 15. 悬浮窗贴边距离偏移（dp），默认0贴边，正值往内缩，负数超出屏幕
    var readAloudFloatEdgeOffset: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudFloatEdgeOffset, "0")
                ?.toIntOrNull() ?: 0
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.readAloudFloatEdgeOffset, 0)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.readAloudFloatEdgeOffset, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudFloatEdgeOffset, value.toString()).apply()

    // 16. 角色标注显示开关
    var showRoleAnnotation: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showRoleAnnotation, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.showRoleAnnotation, value)

    // 17. 角色标注上下偏移量（dp），默认0。正数向上偏移，负数向下偏移
    // 注意：必须用 String 存储，因为 EditTextPreference 底层调用 getString()
    var roleAnnotationOffset: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.roleAnnotationOffset, "0")
                ?.toIntOrNull() ?: 0
        } catch (e: ClassCastException) {
            val old = appCtx.defaultSharedPreferences.getInt(PreferKey.roleAnnotationOffset, 0)
            appCtx.defaultSharedPreferences.edit()
                .putString(PreferKey.roleAnnotationOffset, old.toString()).apply()
            old
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.roleAnnotationOffset, value.toString()).apply()

    // 17b. 切章自动补全角色档案：阅读切章时用上一章正文 + 已标注姓名补全档案并自动生成立绘
    var autoProfileOnChapterSwitch: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.autoProfileOnChapterSwitch, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.autoProfileOnChapterSwitch, value)

    // 18. 音频缓存标记显示开关
    var showAudioCacheIndicator: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.showAudioCacheIndicator, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.showAudioCacheIndicator, value)

    // 19.5 视觉小说舞台开关（朗读大图界面替换为 galgame 演出界面，全局开关，默认关闭）
    var vnStageEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.vnStageEnabled, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.vnStageEnabled, value)

    // 19. AI 生图开关（每本书单独设置，默认关闭；无打开书籍时读为 false、写入无效）
    // 绑定维度为【书名】，换书源后 bookUrl 变化也不失效；同时兼容旧版按 bookUrl 存储的数据。
    var readAloudAiImage: Boolean
        get() {
            val book = io.legado.app.model.ReadBook.book ?: return false
            return AiImageBookPrefs.getBoolean(
                PreferKey.aiImageEnableNamePrefix,
                PreferKey.aiImageEnablePrefix,
                book.name,
                book.bookUrl,
                false
            )
        }
        set(value) {
            val book = io.legado.app.model.ReadBook.book ?: return
            AiImageBookPrefs.putBoolean(
                PreferKey.aiImageEnableNamePrefix,
                PreferKey.aiImageEnablePrefix,
                book.name,
                book.bookUrl,
                value
            )
        }
    // 阅读界面顶部 AI 图片「手势开关」：开启后才允许用顶部下滑/图片上滑开关图片（默认关闭）
    var readAloudImageInReadPage: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudImageInReadPage, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudImageInReadPage, value)

    // 阅读界面顶部 AI 图片「当前是否正在显示」：由手势打开/关闭，与手势开关解耦（默认关闭）
    var readAloudImageShowing: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.readAloudImageShowing, false)
        set(value) = appCtx.putPrefBoolean(PreferKey.readAloudImageShowing, value)

    /**
     * 阅读界面顶部 AI 图片宽度（dp，默认 240，范围 80-600），独立于朗读界面图片宽度。
     *
     * **必须以字符串存取**：该 key 在 `pref_config_aloud.xml` 里是 EditTextPreference，
     * 其初始化会调用 `SharedPreferences.getString()`。若这里用 putPrefInt 写成 Int，
     * 打开朗读设置时就会抛 `ClassCastException: Integer cannot be cast to String`，
     * 且崩溃发生在构建偏好树期间，界面直接打不开。
     * 读取用 getPrefString（内部对非字符串值做 toString 兼容），双保险。
     */
    var readAloudImageInReadPageWidth: Int
        get() = appCtx.getPrefString(PreferKey.readAloudImageInReadPageWidth)
            ?.toIntOrNull()?.coerceIn(80, 600) ?: 240
        set(value) = appCtx.putPrefString(
            PreferKey.readAloudImageInReadPageWidth,
            value.coerceIn(80, 600).toString()
        )

    /**
     * 显示图片界面时正文字号减小量（sp，默认 2，范围 0-10，0 表示不减小）。
     * 同为数值型偏好，与宽度一样以字符串存取，避免将来接入设置界面时踩同样的坑。
     */
    var readAloudImageInReadPageTextSizeReduce: Int
        get() = appCtx.getPrefString(PreferKey.readAloudImageInReadPageTextSizeReduce)
            ?.toIntOrNull()?.coerceIn(0, 10) ?: 2
        set(value) = appCtx.putPrefString(
            PreferKey.readAloudImageInReadPageTextSizeReduce,
            value.coerceIn(0, 10).toString()
        )

    // AI 生图临时缓存保留数量上限（默认 50，至少为 1）
    var aiImageCacheCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageCacheCount, 50)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageCacheCount, value.coerceAtLeast(1))

    /**
     * 书籍信息界面「生成封面」用的提示词，**与朗读生图的模板提示词分开**。
     * 占位符：{name} 书名，{intro} 简介。
     * 长按「生成封面」按钮可编辑。
     */
    var aiCoverPrompt: String
        get() = appCtx.getPrefString(PreferKey.aiCoverPrompt)
            ?: io.legado.app.model.AiImageGenerator.DEFAULT_COVER_PROMPT
        set(value) = appCtx.putPrefString(PreferKey.aiCoverPrompt, value)

    /**
     * 书籍信息界面里**手动选中的**封面模板 id（-1 表示没选过）。
     * 选过就一直记住，重启 App 也保持手动选的那个，不回退到默认模板。
     * 自动补封面也优先用它。
     */
    var aiCoverTemplateId: Long
        get() = appCtx.getPrefLong(PreferKey.aiCoverTemplateId, -1L)
        set(value) = appCtx.putPrefLong(PreferKey.aiCoverTemplateId, value)

    // AI 生图分析字数（每读多少字触发一次生图，默认200）
    var aiImageCharCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageCharCount, 200)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageCharCount, value)

    // AI 生图上下文字数（正文前/后的上文与后续字数，默认50，范围0~2000）
    var aiImageContextCharCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageContextCharCount, 50).coerceIn(0, 2000)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageContextCharCount, value.coerceIn(0, 2000))

    // AI 生图重试次数（生图失败后重试次数，每次重试重新收集正文，默认1）
    var aiImageRetryCount: Int
        get() = appCtx.getPrefInt(PreferKey.aiImageRetryCount, 1)
        set(value) = appCtx.putPrefInt(PreferKey.aiImageRetryCount, value)

    // AI 生图请求间隔时间（秒，每次请求与重试前等待，避免触发 API 频率限制，默认1.0）
    var aiImageRequestInterval: Float
        get() = appCtx.getPrefFloat(PreferKey.aiImageRequestInterval, 1.0f)
        set(value) = appCtx.putPrefFloat(PreferKey.aiImageRequestInterval, value)

    /**
     * 是否保存AI生图到持久目录（每本书单独设置，默认关闭）。
     * 仅当开关开启时，生图成功后才会落盘到 AI生图/{书名}/{章节}/。
     * 绑定维度为【书名】，换书源不失效；同时兼容旧版按 bookUrl 存储的数据。
     */
    fun isAiImageSaveEnabled(bookName: String?, bookUrl: String?): Boolean {
        if (bookName.isNullOrBlank() && bookUrl.isNullOrBlank()) return false
        return AiImageBookPrefs.getBoolean(
            PreferKey.aiImageSaveNamePrefix,
            PreferKey.aiImageSavePrefix,
            bookName,
            bookUrl,
            false
        )
    }

    fun setAiImageSaveEnabled(bookName: String?, bookUrl: String?, enabled: Boolean) {
        if (bookName.isNullOrBlank() && bookUrl.isNullOrBlank()) return
        AiImageBookPrefs.putBoolean(
            PreferKey.aiImageSaveNamePrefix,
            PreferKey.aiImageSavePrefix,
            bookName,
            bookUrl,
            enabled
        )
    }

    // 20. 朗读/大图图片轮播间隔（秒，默认5，0=不自动轮播，仅手动切换）
    // 注意：必须用 String 存储，因为 pref_config_aloud.xml 中是 EditTextPreference，
    // 其初始化会调用 SharedPreferences.getString()，若旧版本以 Int 存储则会崩溃。
    var readAloudImageInterval: Int
        get() = try {
            appCtx.defaultSharedPreferences.getString(PreferKey.readAloudImageInterval, "5")
                ?.toIntOrNull() ?: 5
        } catch (e: ClassCastException) {
            appCtx.getPrefInt(PreferKey.readAloudImageInterval, 5)
        }
        set(value) = appCtx.defaultSharedPreferences.edit()
            .putString(PreferKey.readAloudImageInterval, value.toString()).apply()

    // ================= 自定义功能区域 End =================

    // --- 与角色对话（RoleChat）---

    /** 与角色对话：OpenAI 兼容接口配置，格式同 AI 处理章节：接口@@模型@@密钥（可多组轮换）。
     *  未配置（或被清空）时默认走内置混元太极本地网关，与其他 AI 功能同一条路线 */
    var roleChatApiConfig: String
        get() = appCtx.getPrefString(PreferKey.roleChatApiConfig)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AI_CHAPTER_FIX_API
        set(value) = appCtx.putPrefString(PreferKey.roleChatApiConfig, value)

    /** 与角色对话：回复完成后是否用角色绑定发音人朗读 */
    var roleChatVoiceEnabled: Boolean
        get() = appCtx.getPrefBoolean(PreferKey.roleChatVoiceEnabled, true)
        set(value) = appCtx.putPrefBoolean(PreferKey.roleChatVoiceEnabled, value)

    /** 与角色对话：发给 AI 的历史上下文条数上限 */
    var roleChatContextLimit: Int
        get() = appCtx.getPrefInt(PreferKey.roleChatContextLimit, 20)
        set(value) = appCtx.putPrefInt(PreferKey.roleChatContextLimit, value.coerceIn(2, 200))

    /** 角色立绘使用的生图模板 id（0 = 跟随默认模板）。选择后持久化，重启不丢 */
    var aiPortraitTemplateId: Long
        get() = appCtx.getPrefString(PreferKey.aiPortraitTemplateId)?.toLongOrNull() ?: 0L
        set(value) = appCtx.putPrefString(PreferKey.aiPortraitTemplateId, value.toString())

    // 视觉小说舞台背景呈现方式：0=左右裁切铺满(默认) 1=不裁左右·上下补模糊 2=贴顶·仅下方补模糊
    var vnBackgroundMode: Int
        get() = appCtx.getPrefInt(PreferKey.vnBackgroundMode, 0).coerceIn(0, 2)
        set(value) = appCtx.putPrefInt(PreferKey.vnBackgroundMode, value.coerceIn(0, 2))

}
