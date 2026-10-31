package io.legado.app.constant

@Suppress("ConstPropertyName")
object PreferKey {
    const val language = "language"
    const val fontScale = "fontScale"
    const val themeMode = "themeMode"
    const val userAgent = "userAgent"
    const val customHosts = "customHosts"
    const val videoSetting = "videoSetting"
    const val editTheme = "editTheme"
    const val editThemeDark = "editThemeDark"
    const val editTemeAuto = "editTemeAuto"
    const val showUnread = "showUnread"
    const val showBooknameLayout = "showBooknameLayout"
    const val bookshelfMargin = "bookshelfMargin"
    const val bookGroupStyle = "bookGroupStyle"
    const val useDefaultCover = "useDefaultCover"
    const val loadCoverOnlyWifi = "loadCoverOnlyWifi"
    const val coverShowName = "coverShowName"
    const val coverShowAuthor = "coverShowAuthor"
    const val coverShowNameN = "coverShowNameN"
    const val coverShowAuthorN = "coverShowAuthorN"
    const val remoteServerId = "remoteServerId"
    const val hideStatusBar = "hideStatusBar"
    const val clickActionTL = "clickActionTopLeft"
    const val clickActionTC = "clickActionTopCenter"
    const val clickActionTR = "clickActionTopRight"
    const val clickActionML = "clickActionMiddleLeft"
    const val clickActionMC = "clickActionMiddleCenter"
    const val clickActionMR = "clickActionMiddleRight"
    const val clickActionBL = "clickActionBottomLeft"
    const val clickActionBC = "clickActionBottomCenter"
    const val clickActionBR = "clickActionBottomRight"
    const val hideNavigationBar = "hideNavigationBar"
    const val precisionSearch = "precisionSearch"
    const val readAloudByPage = "readAloudByPage"
    const val ttsEngine = "appTtsEngine"
    const val ttsFollowSys = "ttsFollowSys"
    const val ttsSpeechRate = "ttsSpeechRate"
    /** 语速滑块精细进度（0~450，步进 0.01），ttsSpeechRate 为其粗刻度派生值 */
    const val ttsSpeechRateFine = "ttsSpeechRateFine"
    const val ttsCacheEnabled = "ttsCacheEnabled"
    const val sysTtsPackageName = "sysTtsPackageName"
    const val sysTtsSynthesizeTimeout = "sysTtsSynthesizeTimeout"
    const val ttsRetryCount = "ttsRetryCount"
    const val ttsRetrySkipOnFail = "ttsRetrySkipOnFail"
    /** 实时更新音频开关：开启后检测到 gengxin.json 信号时停止朗读、删除所有缓存、重新开始 */
    const val gengxinRealtimeUpdate = "gengxinRealtimeUpdate"
    const val prevKeys = "prevKeyCodes"
    const val nextKeys = "nextKeyCodes"
    const val showDiscovery = "showDiscovery"
    const val showVideo = "showVideo"
    const val enableReview = "enableReview"
    const val showRss = "showRss"
    const val bookshelfLayout = "bookshelfLayout"
    const val bookshelfSort = "bookshelfSort"
    const val bookExportFileName = "bookExportFileName"
    const val bookImportFileName = "bookImportFileName"
    const val episodeExportFileName = "episodeExportFileName"
    const val recordLog = "recordLog"
    const val processText = "process_text"
    const val cleanCache = "cleanCache"
    const val autoClearCacheOnStart = "autoClearCacheOnStart"
    const val saveTabPosition = "saveTabPosition"
    const val fontFolder = "fontFolder"
    const val backupPath = "backupUri"
    const val restoreIgnore = "restoreIgnore"
    const val threadCount = "threadCount"
    const val webPort = "webPort"
    const val keepLight = "keep_light"
    const val webService = "webService"
    const val webDavUrl = "web_dav_url"
    const val webDavAccount = "web_dav_account"
    const val webDavPassword = "web_dav_password"
    const val webDavDir = "webDavDir"
    const val enableCustomExport = "enableCustomExport"
    const val exportToWebDav = "webDavCacheBackup"
    // 悦读(Readify) 云同步: 网页登录提取的会话 JSON + 同步开关
    const val readifySession = "readifySession"
    // 悦读账号密码直登: 记住的账号/密码（仅存本机，避免每次重输）
    const val readifyLoginAccount = "readifyLoginAccount"
    const val readifyLoginPassword = "readifyLoginPassword"
    const val exportNoChapterName = "exportNoChapterName"
    const val exportType = "exportType"
    const val exportPictureFile = "exportPictureFile"
    const val changeSourceCheckAuthor = "changeSourceCheckAuthor"
    const val changeSourceLoadToc = "changeSourceLoadToc"
    const val changeSourceLoadInfo = "changeSourceLoadInfo"
    const val changeSourceLoadWordCount = "changeSourceLoadWordCount"
    const val changeSourceShowCacheCount = "changeSourceShowCacheCount"
    const val changeSourceSortByChapterCount = "changeSourceSortByChapterCount"
    const val chineseConverterType = "chineseConverterType"
    const val launcherIcon = "launcherIcon"
    const val textSelectAble = "selectText"
    const val shareLayout = "shareLayout"
    const val comicStyleSelect = "comicStyleSelect"
    const val readStyleSelect = "readStyleSelect"
    const val systemTypefaces = "system_typefaces"
    const val readBodyToLh = "readBodyToLh"
    const val textFullJustify = "textFullJustify"
    const val textBottomJustify = "textBottomJustify"
    const val adaptSpecialStyle = "adaptSpecialStyle"
    const val autoReadSpeed = "autoReadSpeed"
    const val barElevation = "barElevation"
    const val transparentStatusBar = "transparentStatusBar"
    const val immNavigationBar = "immNavigationBar"
    const val defaultCover = "defaultCover"
    const val defaultCoverDark = "defaultCoverDark"
    const val replaceEnableDefault = "replaceEnableDefault"
    const val showBrightnessView = "showBrightnessView"
    const val autoClearExpired = "autoClearExpired"
    const val autoChangeSource = "autoChangeSource"
    const val importKeepName = "importKeepName"
    const val importKeepGroup = "importKeepGroup"
    const val screenOrientation = "screenOrientation"
    const val syncBookProgress = "syncBookProgress"
    const val syncBookProgressPlus = "syncBookProgressPlus"
    const val cronet = "Cronet"
    const val antiAlias = "antiAlias"
    const val bitmapCacheSize = "bitmapCacheSize"
    const val imageRetainNum = "imageRetainNum"
    const val preDownloadNum = "preDownloadNum"
    const val mangaPreDownloadNum = "mangaPreDownloadNum"
    const val mangaAutoPageSpeed = "mangaAutoPageSpeed"
    const val mangaFooterConfig = "mangaFooterConfig"
    const val disableClickScroll = "disableClickScroll"
    const val enableMangaHorizontalScroll = "enableMangaHorizontalScroll"
    const val hideMangaTitle = "hideMangaTitle"
    const val mangaColorFilter = "mangaColorFilter"
    const val enableMangaEInk = "enableMangaEInk"
    const val mangaEInkThreshold = "mangaEInkThreshold"
    const val disableHorizontalPageSnap = "disableHorizontalPageSnap"
    const val enableMangaGray = "enableMangaGray"
    const val autoRefresh = "auto_refresh"
    const val onlyUpdateRead = "onlyUpdateRead"
    const val defaultToRead = "defaultToRead"
    const val defaultToReadAloud = "defaultToReadAloud"
    const val exportCharset = "exportCharset"
    const val exportUseReplace = "exportUseReplace"
    const val useZhLayout = "useZhLayout"
    const val brightness = "brightness"
    const val nightBrightness = "nightBrightness"
    const val expandTextMenu = "expandTextMenu"
    const val doublePageHorizontal = "doubleHorizontalPage"
    const val readUrlOpenInBrowser = "readUrlInBrowser"
    const val defaultBookTreeUri = "defaultBookTreeUri"
    const val checkSource = "checkSource"
    const val uploadRule = "uploadRule"
    const val tocUiUseReplace = "tocUiUseReplace"
    const val tocCountWords = "tocCountWords"
    const val enableReadRecord = "enableReadRecord"
    const val localBookImportSort = "localBookImportSort"
    const val customWelcome = "customWelcome"
    const val welcomeShowTime = "welcomeShowTime"
    const val welcomeImage = "welcomeImagePath"
    const val welcomeImageDark = "welcomeImagePathDark"
    const val welcomeShowText = "welcomeShowText"
    const val welcomeShowTextDark = "welcomeShowTextDark"
    const val welcomeShowIcon = "welcomeShowIcon"
    const val welcomeShowIconDark = "welcomeShowIconDark"
    const val pageTouchSlop = "pageTouchSlop"
    const val pageTouchClick = "pageTouchClick"
    const val showAddToShelfAlert = "showAddToShelfAlert"
    const val ignoreAudioFocus = "ignoreAudioFocus"
    const val parallelExportBook = "parallelExportBook"
    const val exportAddAnnotation = "exportAddAnnotation"
    const val progressBarBehavior = "progressBarBehavior"
    const val sourceEditMaxLine = "sourceEditMaxLine"
    const val ttsTimer = "ttsTimer"
    const val noAnimScrollPage = "noAnimScrollPage"
    const val webDavDeviceName = "webDavDeviceName"
    const val webServiceWakeLock = "webServiceWakeLock"
    const val audioPlayWakeLock = "audioPlayWakeLock"
    const val readAloudWakeLock = "readAloudWakeLock"
    const val showLastUpdateTime = "showLastUpdateTime"
    const val showWaitUpCount = "showWaitUpCount"
    const val clearWebViewData = "clearWebViewData"
    const val onlyLatestBackup = "onlyLatestBackup"
    const val brightnessVwPos = "brightnessVwPos"
    const val shrinkDatabase = "shrinkDatabase"
    const val batchChangeSourceDelay = "batchChangeSourceDelay"
    const val openBookInfoByClickTitle = "openBookInfoByClickTitle"
    const val defaultHomePage = "defaultHomePage"
    const val showBookshelfFastScroller = "showBookshelfFastScroller"
    const val importKeepEnable = "importKeepEnable"
    const val importShowComment = "importShowComment"
    const val previewImageByClick = "previewImageByClick"
    const val clickImgWay = "clickImgWay"
    const val keyPageOnLongPress = "keyPageOnLongPress"
    const val volumeKeyPage = "volumeKeyPage"
    const val volumeKeyPageOnPlay = "volumeKeyPageOnPlay"
    const val mouseWheelPage = "mouseWheelPage"
    const val recordHeapDump = "recordHeapDump"
    const val optimizeRender = "optimizeRender"
    const val updateToVariant = "updateToVariant"
    const val streamReadAloudAudio = "streamReadAloudAudio"
    const val pauseReadAloudWhilePhoneCalls = "pauseReadAloudWhilePhoneCalls"
    const val readAloudByMediaButton = "readAloudByMediaButton"
    const val showMangaUi = "showMangaUi"
    const val disableMangaScale = "disableMangaScale"
    const val disableMangaPageAnim = "disableMangaPageAnim"
    const val paddingDisplayCutouts = "paddingDisplayCutouts"
    const val autoCheckNewBackup = "autoCheckNewBackup"
    const val webDavBackupKeepCount = "webDavBackupKeepCount"

    const val dThemeName = "durThemeName"
    const val dNThemeName = "durThemeNameNight"

    /**
     * 内置主题已补齐到的版本。内置配色有增删时版本号 +1，
     * 用于给已安装（已存过主题配置）的用户补发新增配色，且不覆盖他自己调过的同名主题。
     */
    const val defaultThemeVersion = "defaultThemeVersion"

    const val cPrimary = "colorPrimary"
    const val cAccent = "colorAccent"
    const val cBackground = "colorBackground"
    const val cBBackground = "colorBottomBackground"
    const val bgImage = "backgroundImage"
    const val bgImageBlurring = "backgroundImageBlurring"
    const val tNavBar = "transparentNavBar"

    const val cNPrimary = "colorPrimaryNight"
    const val cNAccent = "colorAccentNight"
    const val cNBackground = "colorBackgroundNight"
    const val cNBBackground = "colorBottomBackgroundNight"
    const val bgImageN = "backgroundImageNight"
    const val bgImageNBlurring = "backgroundImageNightBlurring"
    const val tNavBarN = "transparentNavBarNight"

    const val showReadTitleAddition = "showReadTitleAddition"
    const val readBarStyleFollowPage = "readBarStyleFollowPage"
    const val contentSelectSpeakMod = "contentReadAloudMod"
    const val editFontScale = "editFontScale"
    const val editNonPrintable = "editNonPrintable"
    const val editAutoWrap = "editAutoWrap"
    const val editAutoComplete = "editAutoComplete"
    const val showBoardLine = "showBoardLine"

    // --- 背景音乐 (BGM) 与 标题朗读 相关新增 ---
    const val bgmEnabled = "bgmEnabled"
    const val bgmPath = "bgmPath"
    const val bgmVolume = "bgmVolume"
    const val bgmAIEnabled = "bgmAIEnabled"
    const val bgmThinkingEnabled = "bgmThinkingEnabled"

    // --- 朗读合成「检测尾音」相关 ---
    const val ttsTailCheckEnabled = "ttsTailCheckEnabled"
    const val ttsTailCheckMaxRetry = "ttsTailCheckMaxRetry"
    const val ttsTailCheckHardCutDb = "ttsTailCheckHardCutDb"
    const val ttsTailCheckAudibleDb = "ttsTailCheckAudibleDb"
    const val ttsTailCheckMinDurationMs = "ttsTailCheckMinDurationMs"
    const val bgmAICharInterval = "bgmAICharInterval"
    const val soundEffectMode = "soundEffectMode"
    // 当前书籍音效模式：key = bookSoundEffectMode_${bookUrl}，value = follow/off/normal/all
    const val bookSoundEffectModePrefix = "bookSoundEffectMode_"
    const val soundEffectOffsetChars = "soundEffectOffsetChars"
    const val soundEffectVolume = "soundEffectVolume"
    const val mixSoundEffectOnMerge = "mixSoundEffectOnMerge"
    // --- AI 音效（广播剧音效库）---
    const val aiEffectEnabled = "aiEffectEnabled"
    const val aiEffectLocalDir = "aiEffectLocalDir"
    const val aiEffectKeywordCount = "aiEffectKeywordCount"
    const val aiEffectMaxPick = "aiEffectMaxPick"
    const val aiEffectCandidatesPerKeyword = "aiEffectCandidatesPerKeyword"
    const val aiEffectMaxCandidates = "aiEffectMaxCandidates"

    const val aiEffectMixOnMerge = "aiEffectMixOnMerge"
    // 阅读界面顶部AI图片「手势开关」：开启后才允许用顶部下滑/图片上滑来开关图片
    const val readAloudImageInReadPage = "readAloudImageInReadPage"
    // 阅读界面顶部AI图片「当前是否正在显示」：由手势打开/关闭，与上面的手势开关解耦
    const val readAloudImageShowing = "readAloudImageShowing"
    // 阅读界面顶部AI图片宽度（dp），独立于朗读界面的图片宽度
    const val readAloudImageInReadPageWidth = "readAloudImageInReadPageWidth"
    // 显示图片界面时正文字号减小量（sp），0 表示不减小，默认 2
    const val readAloudImageInReadPageTextSizeReduce = "readAloudImageInReadPageTextSizeReduce"
    // 合并章节音频/合成视频时是否混入背景音乐
    const val mixBgmOnMerge = "mixBgmOnMerge"
    // 混入背景音乐时单首最少持续时长(毫秒)，超过后才允许切换到下一首
    const val bgmMinSegmentMs = "bgmMinSegmentMs"
    const val readAloudTitle = "readAloudTitle"
    const val readAloudStartFromFirst = "readAloudStartFromFirst"
    const val autoMergeAudioOnChapterEnd = "autoMergeAudioOnChapterEnd"
    const val convertToMp3AfterMerge = "convertToMp3AfterMerge"
    const val saveTextWithMerge = "saveTextWithMerge"
    const val saveVideoWithMerge = "saveVideoWithMerge"
    const val shortAudioMinDuration = "shortAudioMinDuration"
    const val srtSubtitleMaxChars = "srtSubtitleMaxChars"
    const val srtSubtitleTimeOffset = "srtSubtitleTimeOffset"
    const val srtSilenceMatchRange = "srtSilenceMatchRange"
    const val showReadAloudCoverSubtitle = "showReadAloudCoverSubtitle"
    // 朗读界面是否隐藏当前章节进度条，默认 true 隐藏（开启即去掉进度条）
    const val readAloudHideChapterProgress = "readAloudHideChapterProgress"
    // 朗读界面小图（封面/本地图）与顶部的距离，单位 dp，默认 16
    const val readAloudCoverTopMargin = "readAloudCoverTopMargin"

    // 保存为视频时的字幕样式设置
    const val videoSubtitleFontSizeScale = "videoSubtitleFontSizeScale" // 字号相对默认比例，1.0=默认
    const val videoSubtitleVOffset = "videoSubtitleVOffset"             // 字幕垂直偏移(画面高度比例)，正=上移，负=下移
    const val videoSubtitleSplit = "videoSubtitleSplit"               // 是否按句(标点)切割字幕，默认 false
    const val videoSubtitleFontColor = "videoSubtitleFontColor"        // 字幕填充色(十六进制，如 #FF000000)
    const val videoSubtitleStrokeWidth = "videoSubtitleStrokeWidth"    // 描边粗细(相对字号比例，默认 0.12)
    const val videoSubtitleStrokeColor = "videoSubtitleStrokeColor"    // 描边颜色(十六进制，如 #FFFFFFFF)
    const val videoSubtitleStyle = "videoSubtitleStyle"            // 视频字幕样式（字号/字色/描边粗细/描边色）汇总设置
    const val readAloudCoverWidth = "readAloudCoverWidth"
    const val readAloudBackToBackground = "readAloudBackToBackground"
    const val readAloudFloatWindow = "readAloudFloatWindow"
    const val readAloudFloatClickSwap = "readAloudFloatClickSwap"
    const val readAloudFloatX = "readAloudFloatX"
    const val readAloudFloatY = "readAloudFloatY"
    const val readAloudFloatEdgeOffset = "readAloudFloatEdgeOffset"
    const val videoFloatX = "videoFloatX"
    const val videoFloatY = "videoFloatY"

    // 系统TTS当前使用的voice名称（用于缓存文件名匹配）
    const val sysTtsVoiceName = "sysTtsVoiceName"

    // 朗读封面跑马灯边框
    const val readAloudCoverMarqueeEnabled = "readAloudCoverMarqueeEnabled"
    const val readAloudCoverMarqueeSpeed = "readAloudCoverMarqueeSpeed"
    const val marqueeBgmLinkEnabled = "marqueeBgmLinkEnabled"

    // 角色标注显示开关与偏移量
    const val showRoleAnnotation = "showRoleAnnotation"
    const val roleAnnotationOffset = "roleAnnotationOffset"

    // 切章自动补全角色档案（用上一章正文与已标注姓名，无立绘的角色自动生成）
    const val autoProfileOnChapterSwitch = "autoProfileOnChapterSwitch"

    // 音频缓存标记显示开关
    const val showAudioCacheIndicator = "showAudioCacheIndicator"

    // 朗读缓存音频统一转换为 WAV (24000Hz, 16bit, 单声道)
    const val convertCacheToWav = "convertCacheToWav"

    // 转换为 WAV 时的音量增益倍数（默认 1.0，最大 3.0，超过自动截断为 3.0）
    const val convertCacheToWavGain = "convertCacheToWavGain"

    // 朗读播放时的音量增益倍数（默认 1.0，范围 0.5~5.0，超过自动截断为 5.0）
    const val readAloudVolumeGain = "readAloudVolumeGain"

    // TTS 后处理音频参数（全局，默认 1.0）
    const val ttsPostSpeed = "ttsPostSpeed"
    const val ttsPostVolume = "ttsPostVolume"
    const val ttsPostPitch = "ttsPostPitch"

    // TTS 快节奏播放（去除静音，合成阶段 PCM 裁剪）
    const val ttsSilenceSkipEnabled = "ttsSilenceSkipEnabled"
    const val ttsSilenceSkipMinMs = "ttsSilenceSkipMinMs"

    // --- AI 生图配置 ---
    const val readAloudAiImage = "readAloudAiImage"
    // 视觉小说舞台（朗读大图界面替换为 galgame 演出界面）
    const val vnStageEnabled = "vnStageEnabled"
    // 视觉小说舞台背景呈现方式：0=左右裁切铺满(默认) 1=不裁左右·上下补模糊 2=贴顶·仅下方补模糊
    const val vnBackgroundMode = "vnBackgroundMode"
    // 启用AI生图（每本书单独设置），key = aiImageEnablePrefix_${bookUrl}
    const val aiImageEnablePrefix = "aiImageEnable_"
    // AI 生图临时缓存保留数量上限（默认 50）
    const val aiImageCacheCount = "aiImageCacheCount"
    const val aiImageSize = "aiImageSize"
    // 朗读/大图图片轮播间隔（秒），0=不自动轮播，仅手动切换
    const val readAloudImageInterval = "readAloudImageInterval"
    // 书籍与模板的绑定关系：key = aiImageTemplateBind_${bookUrl}，value = templateId
    const val aiImageTemplateBindPrefix = "aiImageTemplateBind_"
    // 上次编辑的模板 id（记住用户上次编辑哪个模板，下次打开设置时恢复）
    const val aiImageLastEditTemplateId = "aiImageLastEditTemplateId"
    // 书籍信息界面「生成封面」用的提示词（与朗读生图的模板提示词分开）
    const val aiCoverPrompt = "aiCoverPrompt"
    // 书籍信息界面手动选中的封面模板 id，记住它，重启后仍是这个
    const val aiCoverTemplateId = "aiCoverTemplateId"
    // AI 生图分析字数（每读多少字触发一次，默认200）
    const val aiImageCharCount = "aiImageCharCount"
    // AI 生图上下文字数（正文前/后的上文与后续字数，默认50）
    const val aiImageContextCharCount = "aiImageContextCharCount"
    // AI 生图重试次数（生图失败后重试次数，默认1）
    const val aiImageRetryCount = "aiImageRetryCount"
    // AI 生图请求间隔时间（秒，每次请求与重试前等待，避免触发 API 频率限制，默认1.0）
    const val aiImageRequestInterval = "aiImageRequestInterval"
    // 是否保存AI生图到持久目录（每本书单独设置），key = aiImageSavePrefix_${bookUrl}
    const val aiImageSavePrefix = "aiImageSave_"

    // --- AI 章节修复（去广告/错别字）---
    // AI 接口配置，格式与 AI 切歌一致：接口地址@@模型名@@密钥
    const val aiChapterFixApi = "aiChapterFixApi"
    // AI 章节修复提示词，为空时用内置默认
    const val aiChapterFixPrompt = "aiChapterFixPrompt"
    // 自动处理开关：切换章节时自动处理后续章节
    const val aiChapterFixAuto = "aiChapterFixAuto"
    // 每次处理的章节数量（自动处理与批量处理共用）
    const val aiChapterFixCount = "aiChapterFixCount"
    // 超长章节拆分阈值（字数），0 表示不拆分
    const val aiChapterFixMaxLength = "aiChapterFixMaxLength"
    // AI 请求失败/解析失败时的重试次数，0 表示不重试
    const val aiChapterFixRetryCount = "aiChapterFixRetryCount"
    // 发给 AI 时附带的上下文参考字数（上一章结尾+下一章开头），0 表示不附带
    const val aiChapterFixContextLength = "aiChapterFixContextLength"
    // AI 请求超时时间（秒，从发起到响应结束的总时限），默认 180
    const val aiChapterFixTimeoutSeconds = "aiChapterFixTimeoutSeconds"
    // AI 章节处理模板的书籍绑定（一本书一个模板），key = 前缀 + 书名
    const val aiChapterFixTemplateBindPrefix = "aiChapterFixTemplateBind_"
    // AI 章节处理自动处理开关（按书生效），key = 前缀 + 书名
    const val aiChapterFixAutoBookPrefix = "aiChapterFixAutoBook_"

    // --- AI 生图「按书名」绑定（新版）---
    // 旧版按 bookUrl 存储，换书源后 bookUrl 变化会导致绑定失效；新版改按书名存储。
    // 读取时会回退旧版 key 并迁移，写入时新旧 key 双写以兼容降级。
    // 启用AI生图（按书名），key = aiImageEnableNamePrefix_${bookName}
    const val aiImageEnableNamePrefix = "aiImageEnableName_"
    // 书籍与模板绑定关系（按书名），key = aiImageTemplateBindNamePrefix_${bookName}，value = 逗号分隔的模板 id
    const val aiImageTemplateBindNamePrefix = "aiImageTemplateBindName_"
    // 是否保存AI生图到持久目录（按书名），key = aiImageSaveNamePrefix_${bookName}
    const val aiImageSaveNamePrefix = "aiImageSaveName_"

    // --- 与角色对话（RoleChat）---
    // OpenAI 兼容接口配置，格式与「AI处理章节」一致：接口@@模型@@密钥（3 段一组，可多组轮换）
    const val roleChatApiConfig = "roleChatApiConfig"
    // 回复完成后是否用角色绑定的发音人朗读
    const val roleChatVoiceEnabled = "roleChatVoiceEnabled"
    // 发给 AI 的历史上下文条数上限（不含本轮输入与 system 人设）
    const val roleChatContextLimit = "roleChatContextLimit"
    // 角色立绘使用的生图模板 id（0 = 跟随默认模板）
    const val aiPortraitTemplateId = "aiPortraitTemplateId"
    // AI 生成书源：按网址记录的历史成功规则（整份 JSON）
    const val aiSourceRuleHistory = "aiSourceRuleHistory"
    // 内置浏览器/内置播放器是否全屏（勾选后一直生效，切换视频重建页面也能保持）
    const val browserFullScreen = "browserFullScreen"
    // 书源规则里的 java.toast / java.longToast 是否真的弹出提示
    // 默认关：很多视频源在正文规则里用 toast 报"正在加载第N集"，播放/切集时反复弹很碍眼
    const val sourceToast = "sourceToast"
    // 禁止书源弹出任何验证（图片验证码对话框 / 验证网页）。开关在「我的 → 书源管理」右上角菜单
    const val disableSourceVerification = "disableSourceVerification"
    // 书架搜索「不搜索视频源」：勾选后搜索只走非视频源（视频源有独立的搜索入口）
    const val searchExcludeVideo = "searchExcludeVideo"
}
