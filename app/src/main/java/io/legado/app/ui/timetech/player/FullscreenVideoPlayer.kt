package io.legado.app.ui.timetech.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.bumptech.glide.request.RequestOptions
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.help.video.VideoCache
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs

/** 循环模式：列表循环 → 随机播放 → 单个循环 → 回到列表循环 */
enum class LoopMode(val label: String) {
    LIST_LOOP("列表循环"),
    SHUFFLE("随机播放"),
    SINGLE_LOOP("单个循环"),
}

fun LoopMode.next(): LoopMode = when (this) {
    LoopMode.LIST_LOOP -> LoopMode.SHUFFLE
    LoopMode.SHUFFLE -> LoopMode.SINGLE_LOOP
    LoopMode.SINGLE_LOOP -> LoopMode.LIST_LOOP
}

val LoopMode.icon: ImageVector
    get() = when (this) {
        LoopMode.LIST_LOOP -> Icons.Filled.Repeat
        LoopMode.SHUFFLE -> Icons.Filled.Shuffle
        LoopMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
    }

/** 画面缩放模式：自适应 → 裁剪 → 拉伸 → 回到自适应 */
enum class ScaleMode(val label: String) {
    FIT("自适应"),
    CROP("裁剪"),
    STRETCH("拉伸"),
}

fun ScaleMode.next(): ScaleMode = when (this) {
    ScaleMode.FIT -> ScaleMode.CROP
    ScaleMode.CROP -> ScaleMode.STRETCH
    ScaleMode.STRETCH -> ScaleMode.FIT
}

val ScaleMode.icon: ImageVector
    get() = when (this) {
        ScaleMode.FIT -> Icons.Filled.FitScreen
        ScaleMode.CROP -> Icons.Filled.Crop
        ScaleMode.STRETCH -> Icons.Filled.AspectRatio
    }

/** 映射到 media3 的 AspectRatioFrameLayout 缩放常量 */
val ScaleMode.resizeMode: Int
    get() = when (this) {
        ScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        // 等比放大铺满屏幕，超出部分裁掉
        ScaleMode.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        // 直接拉伸铺满，不保持比例
        ScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }

/**
 * 全屏视频播放页（时光播放器）。
 *
 * 外层 [VerticalPager] 负责上下滑动切换视频，每页持有一个独立 ExoPlayer 且只有当前页播放；
 * 控制层用 Compose 自绘，避免系统控制器与滑动手势互相抢事件。
 *
 * 手势：单击显隐控制条、双击左右半屏快退/快进 10 秒、长按 2 倍速、横向拖动逐帧快进。
 */
/**
 * 顶部安全区：状态栏 + 刘海。播放时会隐藏系统栏并允许内容延伸到刘海区，
 * 单纯用 statusBarsPadding 在系统栏隐藏后会归零，控制栏就会钻到挖孔下面，
 * 所以统一走 safeDrawing，只取上边。
 */
@Composable
private fun Modifier.safeTop(): Modifier =
    this.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))

/** 底部安全区：导航栏 + 刘海，只取下边 */
@Composable
private fun Modifier.safeBottom(): Modifier =
    this.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))

@Composable
fun FullscreenVideoPlayer(
    videos: List<LocalVideo>,
    initialIndex: Int,
    onExit: () -> Unit,
    // 在线剧集模式：给每个请求带上书源的 UA/Referer/Cookie。
    // 视频站的 m3u8 普遍校验 UA 与 Referer，不带会被直接拒绝。
    headers: Map<String, String> = emptyMap(),
    // 剧名。非空时标题栏显示「剧名 · 第N集」，且上下滑提示改成「下一集」
    seriesTitle: String? = null,
    // 某集地址尚未就绪（uri 为空）时回调，调用方补齐后重建列表即可自动起播。
    // 需要执行书源规则才能拿到地址的源（如蜜桃）不能一次性解析全部剧集，
    // 否则 N 集就是 N 次串行网络请求，只能滚到哪一集解析哪一集。
    // prefetch=true 表示"预取下一集"，调用方据此只解析、不弹内置播放器，真正滑到才播。
    onResolveEpisode: ((index: Int, prefetch: Boolean) -> Unit)? = null,
    // 列表切换模式（发现页/搜索竖滑）取用「下一个视频」首集预取结果的回调。
    // 当前集起播 2 秒后即调用（不必等播到最后一集）。
    // **非挂起**：只返回已就绪的结果，没就绪返回 null 并自行在后台继续解析/嗅探——
    // 加密源的嗅探要开 WebView 跑 20 秒以上，绝不能阻塞在这里（会被重组取消）。
    onPrefetchNextVideo: (() -> NextVideoPreload?)? = null,
    // 起始集内进度（毫秒），用于续播。会话内的 [positions] 优先于它，
    // 因为用户在本页内来回切集时应该回到自己刚才的位置，而不是进页时的位置。
    initialPositionMs: Long = 0L,
    // 播放进度上报（集序号 + 集内毫秒）。每 5 秒与切集/退出时各上报一次，
    // 调用方据此落库，保证「播到第 N 集」在下次进入时仍然有效。
    onProgressChange: ((index: Int, positionMs: Long) -> Unit)? = null,
    // 跳转经典播放页（首页形态：上方视频画面 + 下方选集方块）
    onOpenHome: () -> Unit = {},
    // 搜索/发现入口提供的视频列表：右侧 20% 区域竖滑切换下一个/上一个（null 表示无列表）
    // onFeedDrag：拖动过程中实时上报位移（跟手）；onFeedCancel：没过阈值时回弹
    onFeedSwitch: ((Int) -> Unit)? = null,
    onFeedDrag: ((Float) -> Unit)? = null,
    onFeedCancel: (() -> Unit)? = null,
    // 书籍封面：加载/解析时铺底显示，快速切换时可辨认视频
    coverUrl: String = "",
    // 封面所属书源 origin：与列表一致的图片缓存 key，避免封面重新联网拉取黑屏
    sourceOrigin: String = "",
    // 当前书籍地址：无缝切换交接池的键之一
    bookUrl: String = "",
    // 预加载好的封面 drawable：同步铺到播放器内部封面，避免解析↔缓冲交界那 1 帧黑屏
    coverDrawable: Drawable? = null,
    // 首页压缩模式：视频区固定高度、强制竖屏、显示系统栏（同一播放器实例，不销毁）
    compactMode: Boolean = false,
    // 加入书架（null 则不显示按钮）
    onAddShelf: (() -> Unit)? = null,
    // 播放顺序（显示顺序）：由外层 Activity 持有并传给「选集面板」与「主页下方目录」共用，
    // 点「倒序」/切循环模式时整条顺序一起变，主页目录也跟着反。分页第 page 页播 videos[playOrder[page]]。
    playOrderState: MutableState<List<Int>>,
    // 播放顺序是否为「倒序」（持久化标记）：true 时整条剧集列表反转，
    // 与小说「反转目录」同效，下次打开该视频自动沿用。
    reverseOrder: Boolean = false,
    // 点「倒序」后回调，把新的倒序状态持久化（true=已反转 / false=已还原）
    onReversePersist: (Boolean) -> Unit = {},
    // 当前正在播放的播放器实例（由 VideoPlayerPage 在成为当前页时上报），
    // 供 Activity 层的 MediaSession / 通知栏控制真正的播放器。
    activePlayer: MutableState<Player?> = mutableStateOf<Player?>(null),
    // 播放阶段出错（ExoPlayer 报错：无法播放/播放出错/网址问题等）回调。
    // 上层据此在可切换列表的场景自动跳到下一个视频。
    onPlaybackError: ((PlaybackException) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, videos.lastIndex)) {
        videos.size
    }
    // 本次会话内记录每个视频的播放进度，切回来时续播
    val positions = remember { HashMap<String, Long>() }

    // 循环与画面模式放在分页外层，切换视频后保持用户的选择
    var loopMode by remember { mutableStateOf(LoopMode.LIST_LOOP) }
    var scaleMode by remember { mutableStateOf(ScaleMode.FIT) }
    // 锁定竖屏：横版视频也竖屏居中播放（上下留黑边），类似抖音的横版内容。
    // 状态持久化，重进播放页保持用户的选择
    var lockedPortrait by remember {
        mutableStateOf(
            context.getSharedPreferences("timetech_video_player", Context.MODE_PRIVATE)
                .getBoolean("lockedPortrait", false)
        )
    }
    var lastIsLandscape by remember { mutableStateOf(false) }
    var showSwitchHint by remember { mutableStateOf(true) }
    /** 选集面板 */
    var showEpisodes by remember { mutableStateOf(false) }

    // 播放顺序：**一组原始视频下标**，分页里第 page 页播的是 videos[playOrder[page]]。
    //
    // 随机播放必须重排整条序列，而不是只在「自动跳下一个」时掷一次骰子——那样
    // 手动上滑翻页依旧是列表里的下一个，一半的行为还是顺序的。重排之后，
    // 自动（播完）与手动（上滑）都只是「翻到下一页」，天然同一条路径。
    // 该状态由外层 Activity 持有（见 playOrderState 参数）并与「主页下方目录」共享，
    // 因此点「倒序」时选集面板与主页目录会一起反转。
    var playOrder by playOrderState

    // 切换循环模式 / 倒序标记时重建顺序，并把当前正在看的那一集钉在原页码上，
    // 保证「切模式」「开/关倒序」这些动作本身不会让画面跳到别的视频。
    LaunchedEffect(loopMode, videos.size, reverseOrder) {
        val page = pagerState.settledPage
        val anchor = playOrder.getOrNull(page) ?: page
        if (loopMode == LoopMode.SHUFFLE && videos.size > 1) {
            playOrder = shuffledKeepingPosition(videos.size, anchor = anchor, atPage = page)
        } else {
            // 倒序：把整条播放顺序反转（与小说「反转目录」同效，已持久化，下次打开沿用）
            val base = if (reverseOrder) videos.indices.reversed().toList()
            else videos.indices.toList()
            playOrder = base
            // 把当前正在看的那一集（anchor）钉在它新顺序里的页码上，画面不跳
            val targetPage = base.indexOf(anchor)
            if (targetPage >= 0 && pagerState.settledPage != targetPage) {
                pagerState.scrollToPage(targetPage)
            }
        }
    }

    // 停在某一集时补齐它的地址；预取下一集，让上滑后能立刻起播
    val currentPage = pagerState.settledPage
    LaunchedEffect(currentPage, videos.size) {
        if (onResolveEpisode == null) return@LaunchedEffect
        if (videos.getOrNull(playOrder[currentPage])?.uri == Uri.EMPTY &&
            !SharedPlayer.has(bookUrl, playOrder[currentPage])
        ) {
            onResolveEpisode.invoke(playOrder[currentPage], false)
        }
        // 预取的是**下一页**要播的那集，不是原始列表里的 current+1
        val next = playOrder.getOrNull(currentPage + 1) ?: return@LaunchedEffect
        if (videos.getOrNull(next)?.uri == Uri.EMPTY &&
            !SharedPlayer.has(bookUrl, next)
        ) {
            onResolveEpisode.invoke(next, true)
        }
    }

    // 选集方块/外部跳集：startIndex 变化时把分页滚到对应集。
    // 注意：不要以 playOrder 作为依赖——否则每次点「倒序」都会重新触发本效应、
    // 把分页滚回 initialIndex 所在页，造成画面乱跳。
    LaunchedEffect(initialIndex, videos.size) {
        val target = initialIndex.coerceIn(0, videos.lastIndex)
        val page = playOrder.indexOf(target).coerceAtLeast(0)
        if (pagerState.currentPage != page) pagerState.scrollToPage(page)
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // 系统栏：全屏模式隐藏并边到边；首页压缩模式显示系统栏、正常布局
    LaunchedEffect(compactMode) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (!compactMode) {
            // 播放时临时改成边到边，再把系统栏颜色置为透明，画面才能真正铺满
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attrs
            }
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(Unit) {
        delay(2600)
        showSwitchHint = false
    }

    BackHandler {
        onExit()
    }

    /** 根据视频宽高决定横竖屏，跟随传感器在横/竖屏内部自由翻转 */
    val applyAutoOrientation: (Boolean) -> Unit = { isLandscape ->
        lastIsLandscape = isLandscape
        activity?.requestedOrientation = if (compactMode || lockedPortrait) {
            // 首页压缩模式/锁定竖屏：保持竖屏，画面交给缩放模式居中留黑边
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /** 切换锁定竖屏：落盘并立即按新方向生效 */
    val setLockedPortrait: (Boolean) -> Unit = { on ->
        lockedPortrait = on
        context.getSharedPreferences("timetech_video_player", Context.MODE_PRIVATE)
            .edit().putBoolean("lockedPortrait", on).apply()
        applyAutoOrientation(lastIsLandscape)
    }

    // 预缓冲下一集：当前集正常播放约 2 秒后，为下一集建立不超过 ~10 秒的缓冲窗口，
    // 上下滑/选集切换时直接接管已缓冲的播放器实例，不再出现"缓冲一会儿"。
    // 列表切换模式（发现页/搜索竖滑，onPrefetchNextVideo != null）下同时预缓冲**下一个视频**
    // 的首集：用户在任意一集都可能竖滑切到下一部，等到最后一集才预取根本来不及——
    // 下一集留在 PreloadCache、下一个视频登记进 SharedPlayer，两者互不挤占。
    LaunchedEffect(pagerState.currentPage, videos, playOrder) {
        // 先让当前这一集自己站稳，再动预缓冲：它还在转圈时去抢带宽，两头都卡。
        // 原来是死等 2 秒——当前集卡顿时照样开跑，反而拖慢起播。
        awaitSelfBuffered(activePlayer)
        if (videos.isEmpty()) return@LaunchedEffect
        // 取用「下一个视频」的预取结果。
        // 注意这里**不能 await 联网预取**：加密源要走 WebView 嗅探，最长 20 秒以上，
        // 而本 effect 的 key 含 videos，剧集列表一更新就重启，
        // 挂在里面的协程会被取消（且是取消异常、不写日志，静默失败）。
        // 所以 onPrefetchNextVideo 只做"有结果就给我，没有就自己后台去取"，
        // 真正耗时的解析/嗅探由会话层独立承担，不受这里重组影响。
        if (onPrefetchNextVideo != null) {
            val next = onPrefetchNextVideo?.invoke()
            if (next != null && next.uri.isNotBlank()) {
                PreloadCache.prepareNextVideo(
                    context, next.bookUrl, next.index, Uri.parse(next.uri),
                    // 预取结果自带的请求头（含嗅探出的 Referer）优先
                    next.headers.takeIf { it.isNotEmpty() } ?: headers,
                    next.startPositionMs,
                    bufferMs = preloadBufferMs(context),
                )
            }
        }
        val nextIndex = playOrder.getOrNull(pagerState.currentPage + 1) ?: return@LaunchedEffect
        val nextVideo = videos.getOrNull(nextIndex) ?: return@LaunchedEffect
        // 地址还没解析出来的集（懒解析），等解析完成重新组合后这里会再触发
        if (nextVideo.uri == Uri.EMPTY) return@LaunchedEffect
        PreloadCache.prepare(context, nextVideo.uri, headers)
    }
    DisposableEffect(Unit) {
        onDispose { PreloadCache.releaseAll() }
    }

    /**
     * 一个视频播完后的去向：单个循环由 ExoPlayer 自己重播，其余一律「翻到下一页」。
     *
     * 随机性已经体现在 [playOrder] 里（随机模式下下一页就是随机的那一集），
     * 所以这里不再单独处理 SHUFFLE——自动与手动因此走完全相同的一条路径。
     */
    val handlePlayEnded: () -> Unit = {
        if (loopMode != LoopMode.SINGLE_LOOP && videos.size > 1) {
            val current = pagerState.currentPage
            val next = if (current + 1 < videos.size) current + 1 else 0
            scope.launch { pagerState.animateScrollToPage(next) }
            // 播完即从头开始，下次切回来不要停在片尾
            positions[positionKey(playOrder[current], videos.getOrNull(playOrder[current]))] = 0L
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 必须为 0：PlayerView 用的是 surface_view（独立 SurfaceFlinger 合成层），
            // 它会在 View 层级上「打洞」穿透，Z 序与位置都不受 Compose 控制。
            // 只要相邻页也组合出第二个 SurfaceView，就会把邻页画面错误渲染到左上角。
            // 保持 0 可确保同一时刻只存在一个 PlayerView，缩略图占位负责过渡时的黑帧。
            beyondViewportPageCount = 0,
        ) { page ->
            // page 是**播放位置**，i 才是它在原始剧集列表里的序号：
            // 随机播放时两者不同，编号与标题要显示原始集号。
            val i = playOrder[page]
            VideoPage(
                video = videos[i],
                index = i,
                total = videos.size,
                active = page == pagerState.currentPage,
                startPosition = positions[positionKey(i, videos[i])]
                    ?: if (i == initialIndex) initialPositionMs else 0L,
                onPositionSave = { positions[positionKey(i, videos[i])] = it },
                onProgressChange = onProgressChange,
                headers = headers,
                seriesTitle = seriesTitle,
                loopMode = loopMode,
                // 锁定竖屏时强制自适应（FIT），横版视频居中显示、上下留黑边
                scaleMode = if (lockedPortrait) ScaleMode.FIT else scaleMode,
                onLoopModeChange = { loopMode = it },
                onScaleModeChange = { scaleMode = it },
                onPlayEnded = handlePlayEnded,
                onAutoOrientation = applyAutoOrientation,
                onExit = onExit,
                onOpenEpisodes = { if (videos.size > 1) showEpisodes = true },
                lockedPortrait = lockedPortrait,
                onLockPortraitChange = setLockedPortrait,
                onOpenHome = onOpenHome,
                onFeedSwitch = onFeedSwitch,
                onFeedDrag = onFeedDrag,
                onFeedCancel = onFeedCancel,
                coverUrl = coverUrl,
                sourceOrigin = sourceOrigin,
                bookUrl = bookUrl,
                coverDrawable = coverDrawable,
                onAddShelf = onAddShelf,
                activePlayer = activePlayer,
                onPlaybackError = onPlaybackError,
                // 首/末集：该方向没有集可切，竖滑改为切列表里的上一个/下一个视频
                noPrevEpisode = page == 0 || videos.size <= 1,
                noNextEpisode = page == videos.lastIndex || videos.size <= 1,
            )
        }

        AnimatedVisibility(
            visible = showSwitchHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeTop()
                .padding(top = 56.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    if (seriesTitle.isNullOrBlank()) "上滑切换下一个视频" else "上滑切换到下一集",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }

    if (showEpisodes) {
        EpisodePickerSheet(
            videos = videos,
            // 选集面板按当前播放顺序（playOrder）展示，点击「倒序」后列表会整体反转
            order = playOrder,
            currentIndex = playOrder[pagerState.currentPage],
            onDismiss = { showEpisodes = false },
            // 倒序：整体反转本视频所有集的播放顺序（再次点击可还原）。
            // 反转后把分页直接滚到「当前正在看的这集」在新顺序里的位置，画面不跳，
            // 但上下滑/选集列表都已是干净的逆向顺序。
            onReverse = {
                val page = pagerState.currentPage
                val anchor = playOrder.getOrNull(page) ?: page
                val newOrder = playOrder.reversed()
                playOrder = newOrder
                val newPage = newOrder.indexOf(anchor)
                if (newPage >= 0 && newPage != page) {
                    scope.launch { pagerState.scrollToPage(newPage) }
                }
                // 把新的倒序状态持久化：true=已反转 / false=已还原，下次打开该视频沿用
                onReversePersist(newOrder == videos.indices.reversed().toList())
            },
            onPick = { index ->
                showEpisodes = false
                val page = playOrder.indexOf(index)
                if (page >= 0) scope.launch { pagerState.animateScrollToPage(page) }
            }
        )
    }
}

/**
 * 进度记忆的键。
 *
 * 懒解析期间多集的 uri 都是空的（都是同一个 Uri.EMPTY），直接用 uri 字符串会让它们
 * 共用一份进度，因此未解析的集退化成按序号索引。
 */
private fun positionKey(index: Int, video: LocalVideo?): String =
    if (video == null || video.uri == Uri.EMPTY) "#$index" else video.uri.toString()

/**
 * 打乱 [0, size) 作为新的播放顺序，并把 [anchor] 固定在第 [atPage] 页、其余随机填充。
 *
 * 固定页码是为了切换随机模式时画面不跳：切换前第 atPage 页正在播 anchor，
 * 切换后这一页还是 anchor，用户只会感觉到「下一集变成随机的了」。
 */
private fun shuffledKeepingPosition(size: Int, anchor: Int, atPage: Int): List<Int> {
    if (size <= 1) return listOf(anchor)
    val others = (0 until size).filter { it != anchor }.shuffled()
    return List(size) { i ->
        if (i == atPage) anchor else others[if (i < atPage) i else i - 1]
    }
}

@Composable
private fun VideoPage(
    video: LocalVideo,
    index: Int,
    total: Int,
    active: Boolean,
    startPosition: Long,
    onPositionSave: (Long) -> Unit,
    onProgressChange: ((index: Int, positionMs: Long) -> Unit)?,
    headers: Map<String, String>,
    seriesTitle: String?,
    loopMode: LoopMode,
    scaleMode: ScaleMode,
    onLoopModeChange: (LoopMode) -> Unit,
    onScaleModeChange: (ScaleMode) -> Unit,
    onPlayEnded: () -> Unit,
    onAutoOrientation: (Boolean) -> Unit,
    onExit: () -> Unit,
    onOpenEpisodes: () -> Unit,
    lockedPortrait: Boolean,
    onLockPortraitChange: (Boolean) -> Unit,
    onOpenHome: () -> Unit = {},
    onFeedSwitch: ((Int) -> Unit)? = null,
    onFeedDrag: ((Float) -> Unit)? = null,
    onFeedCancel: (() -> Unit)? = null,
    coverUrl: String = "",
    sourceOrigin: String = "",
    bookUrl: String = "",
    // 预加载好的封面 drawable：同步铺到播放器内部封面，避免解析↔缓冲交界那 1 帧黑屏
    coverDrawable: Drawable? = null,
    onAddShelf: (() -> Unit)? = null,
    // 当前正在播放的播放器实例，透传给 VideoPlayerPage 上报给 Activity
    activePlayer: MutableState<Player?> = mutableStateOf<Player?>(null),
    // 播放阶段出错回调，透传给 VideoPlayerPage
    onPlaybackError: ((PlaybackException) -> Unit)? = null,
    // 播放顺序上前一集/后一集是否存在。为 false 时该方向的竖滑改为切换列表视频
    noPrevEpisode: Boolean = false,
    noNextEpisode: Boolean = false,
) {
    // 地址尚未解析出来（需要跑书源规则的源）：先占位，等外层补齐 uri 后这里会重新组合；
    // 但若交接池里有该集的播放器实例（大屏↔首页无缝切换），直接接管，不显示占位
    if (video.uri == Uri.EMPTY && !SharedPlayer.has(bookUrl, index)) {
        PendingVideoPage(video = video, index = index, total = total, seriesTitle = seriesTitle, coverUrl = coverUrl, sourceOrigin = sourceOrigin)
        return
    }
    VideoPlayerPage(
        video = video,
        index = index,
        total = total,
        active = active,
        startPosition = startPosition,
        onPositionSave = onPositionSave,
        onProgressChange = onProgressChange,
        headers = headers,
        seriesTitle = seriesTitle,
        loopMode = loopMode,
        scaleMode = scaleMode,
        onLoopModeChange = onLoopModeChange,
        onScaleModeChange = onScaleModeChange,
        onPlayEnded = onPlayEnded,
        onAutoOrientation = onAutoOrientation,
        onExit = onExit,
        onOpenEpisodes = onOpenEpisodes,
        lockedPortrait = lockedPortrait,
        onLockPortraitChange = onLockPortraitChange,
        onOpenHome = onOpenHome,
        onFeedSwitch = onFeedSwitch,
        onFeedDrag = onFeedDrag,
        onFeedCancel = onFeedCancel,
        coverUrl = coverUrl,
        bookUrl = bookUrl,
        coverDrawable = coverDrawable,
        onAddShelf = onAddShelf,
        activePlayer = activePlayer,
        onPlaybackError = onPlaybackError,
        noPrevEpisode = noPrevEpisode,
        noNextEpisode = noNextEpisode,
    )
}

/**
 * 按整个 URL 推断流类型并显式声明。
 *
 * ExoPlayer 默认只看 URI 的 path 后缀来挑 MediaSource，而视频书源给的地址普遍把真正的
 * 文件名藏在 query 里（如 `/api/v1/m3u8/proxy?path=xxx.m3u8`、`/api/m3u8/decode/authPath?...`），
 * path 后缀是 proxy / authPath，会被判成普通文件交给 ProgressiveMediaSource，
 * 拿 m3u8 文本当媒体解析，结果必然是 Source error。
 *
 * 返回 null 表示交给 ExoPlayer 自己推断（本地文件、常规直链走这条）。
 */
private fun inferMimeType(url: String): String? {
    // 本地文件交给 Extractor 嗅探：拼接产物可能是 ts/m4s 内容，不能被 .mp4 后缀误导
    if (url.startsWith("file:")) return null
    val lower = url.lowercase()
    val path = lower.substringBefore('?').substringBefore('#')
    // 1) path 后缀：最可靠，直接采信
    when {
        path.endsWith(".m3u8") -> return MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") -> return MimeTypes.APPLICATION_MPD
        path.endsWith(".mp4") || path.endsWith(".m4v") -> return MimeTypes.VIDEO_MP4
        path.endsWith(".webm") -> return MimeTypes.VIDEO_WEBM
    }
    // 2) 真实文件名藏在 query 里（书源常见 /api/m3u8/proxy?path=xxx.m3u8）：
    //    只看 path 后缀会漏判，这里按整个 URL 再匹配一次。m3u8 优先——
    //    形如 index.m3u8?url=xxx.mp4 的请求，主体是 m3u8 而不是 mp4
    when {
        lower.contains(".m3u8") -> return MimeTypes.APPLICATION_M3U8
        lower.contains(".mpd") -> return MimeTypes.APPLICATION_MPD
        lower.contains(".mp4") || lower.contains(".m4v") -> return MimeTypes.VIDEO_MP4
        lower.contains(".webm") -> return MimeTypes.VIDEO_WEBM
    }
    // 3) 路径段里带 m3u8（如 /api/m3u8/decode/...）
    if (lower.contains("/m3u8/")) return MimeTypes.APPLICATION_M3U8
    // 4) authPath：书源的 HLS 解码/代理端点（如 /api/v1/play/authPath?...）。
    //    必须与 VideoSourcePlayer.isPlayableUrl 同一口径，否则这类地址会被判成普通文件
    //    交给 ProgressiveMediaSource，拿 m3u8 文本当媒体解析，必然 Source error
    if (lower.contains("authpath")) return MimeTypes.APPLICATION_M3U8
    return null
}

private fun buildMediaItem(uri: Uri): MediaItem {
    val builder = MediaItem.Builder().setUri(uri)
    inferMimeType(uri.toString())?.let { builder.setMimeType(it) }
    return builder.build()
}

/**
 * 构造带默认请求头的 MediaSourceFactory。
 *
 * 视频站的 m3u8 与分片普遍校验 UA / Referer / Cookie，ExoPlayer 默认不带任何请求头，
 * 直接播会被 403。这里把书源配置的 header 注入到 HttpDataSource，
 * 同时保留对 HLS / DASH 等流式封装的支持（DefaultMediaSourceFactory 会自动分派）。
 *
 * 注意：不能把只认 http/https 的 httpFactory 直接塞给 DefaultMediaSourceFactory，
 * 否则本地视频（file://、content://，含 SAF 文档树）也会被当成 HTTP 请求读取而失败，
 * 表现为「无法播放该视频 / Source error」。必须用 [DefaultDataSource.Factory] 包装一层，
 * 它会按 URI scheme 自动路由到 FileDataSource / ContentDataSource / HttpDataSource。
 *
 * 缓存同样只包在 http 分支外（[VideoCache.wrap]），因此本地文件不会被复制进缓存，
 * 而在线播放产生的缓冲会落进 [VideoCache]，与 [VideoDownloader] 共用同一份——
 * 边看边存时已看过的分片直接命中，不再重复下载。
 */
private fun buildMediaSourceFactory(
    context: android.content.Context,
    headers: Map<String, String>,
    // 传了就跟踪分片传输状态，供预缓冲补齐到分片边界用；播放侧不需要
    shardTracker: ShardTracker? = null,
): DefaultMediaSourceFactory {
    val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
    if (headers.isNotEmpty()) {
        httpFactory.setDefaultRequestProperties(headers)
    }
    val dataSourceFactory = DefaultDataSource.Factory(context, VideoCache.wrap(httpFactory))
    if (shardTracker != null) {
        dataSourceFactory.setTransferListener(shardTracker)
    }
    return DefaultMediaSourceFactory(dataSourceFactory)
}

/** 标题栏文案：剧集模式显示「剧名 · 第N集」，普通视频直接显示文件名 */
private fun episodeTitle(seriesTitle: String?, name: String, index: Int, total: Int): String {
    if (seriesTitle.isNullOrBlank()) return name
    return if (total > 1) "$seriesTitle · 第${index + 1}集" else seriesTitle
}

/** 等待解析出播放地址的占位页 */
@Composable
private fun PendingVideoPage(
    video: LocalVideo,
    index: Int,
    total: Int,
    seriesTitle: String?,
    coverUrl: String = "",
    sourceOrigin: String = "",
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // 封面铺底：快速切换时一眼认出这是哪部视频
        CoverBackdrop(coverUrl = coverUrl, sourceOrigin = sourceOrigin, modifier = Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(
                text = episodeTitle(seriesTitle, video.name, index, total),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
internal fun VideoPlayerPage(
    video: LocalVideo,
    index: Int,
    total: Int,
    active: Boolean,
    noPrevEpisode: Boolean = false,
    noNextEpisode: Boolean = false,
    startPosition: Long,
    onPositionSave: (Long) -> Unit,
    onProgressChange: ((index: Int, positionMs: Long) -> Unit)?,
    headers: Map<String, String>,
    seriesTitle: String?,
    loopMode: LoopMode,
    scaleMode: ScaleMode,
    onLoopModeChange: (LoopMode) -> Unit,
    onScaleModeChange: (ScaleMode) -> Unit,
    onPlayEnded: () -> Unit,
    onAutoOrientation: (Boolean) -> Unit,
    onExit: () -> Unit,
    onOpenEpisodes: () -> Unit,
    lockedPortrait: Boolean,
    onLockPortraitChange: (Boolean) -> Unit,
    onOpenHome: () -> Unit = {},
    onFeedSwitch: ((Int) -> Unit)? = null,
    onFeedDrag: ((Float) -> Unit)? = null,
    onFeedCancel: (() -> Unit)? = null,
    coverUrl: String = "",
    sourceOrigin: String = "",
    bookUrl: String = "",
    // 预加载好的封面 drawable：同步铺到内部封面，避免解析↔缓冲交界那 1 帧黑屏
    coverDrawable: Drawable? = null,
    onAddShelf: (() -> Unit)? = null,
    // 当前正在播放的播放器实例（由本页在成为当前页 active 时上报给 Activity）
    activePlayer: MutableState<Player?> = mutableStateOf<Player?>(null),
    // 播放阶段出错（ExoPlayer 报错：无法播放/播放出错/网址问题等）回调
    onPlaybackError: ((PlaybackException) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 播放器的监听在整个页面存活期间只注册一次，用 updatedState 保证它读到的是最新的回调，
    // 否则用户中途改了循环模式后，回调仍停留在首次组合时的版本
    val currentAutoOrientation = rememberUpdatedState(onAutoOrientation)
    val currentPlayEnded = rememberUpdatedState(onPlayEnded)
    val currentProgressChange = rememberUpdatedState(onProgressChange)
    val player = remember(video.uri, index, headers) {
        // 1) 无缝切换：接管大屏↔首页保留的实例（进度原样保留，不重新加载、不重新解析）
        // 2) 预缓冲：接管下一集/下一部已缓冲的实例（从头缓冲的，需回到起始位置）
        // 3) 都没有：新建
        SharedPlayer.take(bookUrl, index, headers)
            ?: PreloadCache.take(video.uri, headers)?.also {
                if (startPosition > 0) it.seekTo(startPosition)
            }
            ?: ExoPlayer.Builder(context)
                .setMediaSourceFactory(buildMediaSourceFactory(context, headers))
                .build()
                .apply {
                    setMediaItem(buildMediaItem(video.uri))
                    if (startPosition > 0) seekTo(startPosition)
                    prepare()
                    playWhenReady = true
                }
    }

    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    // 是否已经开播出过画面：出画后不再显示封面铺底
    val started = remember(video.uri) { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 切到新视频 / 正在缓冲准备播放时，顶部标题显示 6 秒，方便确认当前看的是哪个视频
    var showTitleHint by remember { mutableStateOf(false) }
    LaunchedEffect(video.uri) {
        showTitleHint = true
        delay(6000)
        showTitleHint = false
    }

    // 进入播放后默认隐藏控制条，点一下屏幕才显示
    var showControls by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var longPressBoost by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    /** 切换循环/画面模式时的短暂文字提示 */
    var modeHint by remember { mutableStateOf<String?>(null) }
    /** 每个视频只自动旋转一次，避免反复回调把用户手动设置冲掉 */
    val autoOrientApplied = remember(video.uri) { mutableStateOf(false) }

    // 拖动进度（手势拖动 / 进度条拖动共用）
    var dragging by remember { mutableStateOf(false) }
    var dragBase by remember { mutableLongStateOf(0L) }
    var dragTarget by remember { mutableLongStateOf(0L) }
    var accumDx by remember { mutableFloatStateOf(0f) }
    var sliderValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // 直播落后窗口：网络/码率跟不上时播放位置掉出直播滑动窗口（旧切片已被回收），
                // ExoPlayer 抛 BehindLiveWindowException，表现为"播一小段就报错"。
                // 拉回直播边缘并重新 prepare 即可续上，点播不会出现该错误码，无需担心误触发。
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    // 直播下 seekToDefaultPosition 即定位到直播边缘
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                errorText = error.message ?: "播放失败"
                AppLog.put("视频播放失败：${video.name}", error)
                // 上报播放错误，交由上层在有列表可切时自动跳到下一个视频
                onPlaybackError?.invoke(error)
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (autoOrientApplied.value) return
                if (size.width <= 0 || size.height <= 0) return
                autoOrientApplied.value = true
                // 部分视频带旋转标记，宽高需要按旋转角度修正后再判断
                val swapped = size.unappliedRotationDegrees % 180 != 0
                val isLandscape = if (swapped) {
                    size.height > size.width
                } else {
                    size.width > size.height
                }
                currentAutoOrientation.value(isLandscape)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 单个循环由 ExoPlayer 的 REPEAT_MODE_ONE 内部重播，不会走到 STATE_ENDED
                if (playbackState == Player.STATE_ENDED) {
                    currentPlayEnded.value()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
        }
    }

    DisposableEffect(video.uri) {
        onDispose {
            // 已经播完的必须记成 0，否则这里是片尾位置：下次切回来会 seekTo(片尾)，
            // 立刻 STATE_ENDED → 触发「播完跳下一个」，表现为回来放不了还自动翻页。
            val ended = player.playbackState == Player.STATE_ENDED
            val last = if (ended) 0L else player.currentPosition.coerceAtLeast(0)
            onPositionSave(last)
            currentProgressChange.value?.invoke(index, last)
            // 不直接销毁：交给 SharedPlayer 保留（LRU 上限 2，自动暂停），
            // 供大屏↔首页无缝切换时接管，避免重新加载视频
            player.pause()
            SharedPlayer.keep(player, bookUrl, index, headers)
        }
    }

    // 定期把「看到第几集 + 集内进度」上报给调用方落库。
    // 只在退出时上报不够：Activity finish 后 ViewModel 作用域随之取消，
    // 落库的协程很可能来不及跑完，进度就丢了。
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            val pos = player.currentPosition
            if (pos > 0) currentProgressChange.value?.invoke(index, pos)
        }
    }

    // 上报「当前正在播放的播放器」给 Activity 层（MediaSession/通知栏）。
    // 仅当前页(active)才注册；切走或销毁时若仍是本页实例才清掉，避免把新页播放器误清。
    DisposableEffect(active, player) {
        if (active) activePlayer.value = player
        onDispose {
            if (activePlayer.value === player) activePlayer.value = null
        }
    }

    LaunchedEffect(active) {
        if (active) {
            // 播完之后再手动切回来：播放器可能还是同一个实例并停在 STATE_ENDED，
            // 此时光置 playWhenReady 不会重播，必须先回到片头，否则看起来是「放不了」。
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.playWhenReady = true
        } else {
            player.playWhenReady = false
            player.pause()
        }
    }

    LaunchedEffect(speed, longPressBoost) {
        player.setPlaybackSpeed(if (longPressBoost) 2f else speed)
    }

    // 单个循环交给播放器自己重播，其余模式播完后由外层翻页
    LaunchedEffect(loopMode) {
        player.repeatMode = if (loopMode == LoopMode.SINGLE_LOOP) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    // 固定频率把播放器状态同步到 Compose：进度条与播放中状态
    LaunchedEffect(player) {
        while (true) {
            duration = player.duration.takeIf { it > 0 } ?: 0L
            position = player.currentPosition.coerceAtLeast(0)
            playing = player.isPlaying
            if (playing) started.value = true
            buffering = player.playbackState == Player.STATE_BUFFERING
            delay(200)
        }
    }

    LaunchedEffect(modeHint) {
        if (modeHint != null) {
            delay(1200)
            modeHint = null
        }
    }

    LaunchedEffect(showControls, playing, locked, dragging) {
        if (showControls && playing && !locked && !dragging) {
            delay(3500)
            showControls = false
        }
    }

    val seekTo: (Long) -> Unit = { target ->
        val fixed = target.coerceIn(0, duration.coerceAtLeast(0))
        player.seekTo(fixed)
        position = fixed
        // 手势拖动与进度条拖动共用同一个值，避免拖动中进度条跳回旧位置
        if (duration > 0) sliderValue = fixed.toFloat() / duration
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // 视频首帧缩略图：垫在 PlayerView 之下。
        // 非当前页没有 PlayerView，用它做占位；当前页则覆盖 SurfaceView 就绪前的黑帧。
        // 在线视频没有本地文件可读，MediaMetadataRetriever 只会白等一场，直接跳过。
        if (video.uri.scheme == "file" || video.uri.scheme == "content") {
            ThumbnailLayer(video)
        }

        // 只有当前页组合 PlayerView：surface_view 是独立合成层，会穿透 View 层级，
        // 同时存在多个会让邻页画面串到当前页上（表现为左上角出现别的视频封面）。
        // 缩略图垫底 + 单 SurfaceView，既避免串画面，也省掉一个解码器。
        if (active) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        this.player = player
                    }
                },
                onRelease = { view -> view.player = null },
                update = { view ->
                    view.player = player
                    view.resizeMode = scaleMode.resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 手势层：不消费按下事件，纵向滑动交还给外层 VerticalPager
        if (!locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(
                        video.uri, onFeedSwitch, onFeedDrag, onFeedCancel,
                        noPrevEpisode, noNextEpisode,
                    ) {
                        detectPlayerGestures(
                            onTap = { showControls = !showControls },
                            onDoubleTap = { x ->
                                val step = if (x < size.width / 2f) -10_000L else 10_000L
                                seekTo(player.currentPosition + step)
                            },
                            onLongPressStart = { longPressBoost = true },
                            onLongPressEnd = { longPressBoost = false },
                            onSeekStart = {
                                dragging = true
                                accumDx = 0f
                                dragBase = player.currentPosition
                                dragTarget = dragBase
                            },
                            onSeek = { dx ->
                                accumDx += dx
                                // 整屏宽度约等于视频总时长的一半（0.6 = 原灵敏度 1.2 的一半），
                                // 短距离拖动过于灵敏，降为原先的一半跨度
                                val span = (accumDx / size.width) * duration.coerceAtLeast(0) * 0.6f
                                dragTarget = (dragBase + span).toLong()
                                    .coerceIn(0, duration.coerceAtLeast(0))
                                seekTo(dragTarget)
                            },
                            onSeekEnd = { dragging = false },
                            onFeedSwitch = { offset ->
                                // 切走前用播放器手头的精确位置先落库（此时全局状态还是当前视频，写入正确；
                                // 否则 feedSwitch→load 换掉全局 book 后，上一视频的销毁上报会被丢/串台）。
                                // 播完的记成 0，避免回来 seekTo 片尾立刻 STATE_ENDED 触发自动翻页。
                                currentProgressChange.value?.invoke(
                                    index,
                                    if (player.playbackState == Player.STATE_ENDED) 0L
                                    else player.currentPosition.coerceAtLeast(0)
                                )
                                onFeedSwitch?.invoke(offset)
                            },
                            onFeedDrag = onFeedDrag,
                            onFeedCancel = onFeedCancel,
                            // 整部只有一集时两个方向都没有集可切
                            noPrevEpisode = total <= 1 || noPrevEpisode,
                            noNextEpisode = total <= 1 || noNextEpisode,
                        )
                    }
            )
        }

        // 加载/缓冲时用书籍封面铺底（已开播出画面后不再显示）
        if (!started.value && errorText == null) {
            CoverBackdrop(
                coverUrl = coverUrl,
                sourceOrigin = sourceOrigin,
                initialDrawable = coverDrawable,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 切视频 / 缓冲时顶部标题提示 6 秒，方便确认当前看的是哪个视频
        AnimatedVisibility(
            visible = showTitleHint,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeTop()
                .padding(top = 8.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    episodeTitle(seriesTitle, video.name, index, total),
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        if (buffering) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center).size(46.dp)
            )
        }

        if (!playing && !buffering && errorText == null && !dragging) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(64.dp)
            )
        }

        if (dragging && duration > 0) {
            SeekPreview(
                target = dragTarget,
                base = dragBase,
                duration = duration,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (longPressBoost) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeTop()
                    .padding(top = 48.dp)
            ) {
                Text(
                    "2.0× 倍速播放中",
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // 循环/画面模式切换提示，短暂显示后自动消失
        modeHint?.let { hint ->
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeBottom()
                    .padding(bottom = 96.dp)
            ) {
                Text(
                    hint,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        errorText?.let {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("无法播放该视频", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        AnimatedVisibility(
            visible = showControls && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            PlayerControls(
                title = episodeTitle(seriesTitle, video.name, index, total),
                playing = playing,
                position = position,
                duration = duration,
                speed = speed,
                dragging = dragging,
                showEpisodeButton = total > 1,
                onOpenEpisodes = onOpenEpisodes,
                episodeNo = index + 1,
                episodeTotal = total,
                onAddShelf = onAddShelf,
                sliderValue = sliderValue,
                onSliderValueChange = {
                    dragging = true
                    sliderValue = it
                    seekTo((it * duration).toLong())
                },
                onSliderFinished = { dragging = false },
                onTogglePlay = {
                    if (player.isPlaying) player.pause() else player.play()
                },
                onSeekBy = { ms -> seekTo(player.currentPosition + ms) },
                loopMode = loopMode,
                scaleMode = scaleMode,
                onLoopModeChange = {
                    onLoopModeChange(it)
                    modeHint = it.label
                },
                onScaleModeChange = {
                    onScaleModeChange(it)
                    modeHint = it.label
                },
                onSpeedChange = { speed = it },
                showSpeedMenu = showSpeedMenu,
                onSpeedMenuToggle = { showSpeedMenu = it },
                lockedPortrait = lockedPortrait,
                onLockPortraitChange = onLockPortraitChange,
                onOpenHome = onOpenHome,
                onLock = { locked = true },
                onExit = onExit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 中间正方形播放/暂停区：显示控制条或暂停时可见，点一下即切换
        if (!locked && (showControls || !playing)) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable {
                        if (player.isPlaying) player.pause() else player.play()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        if (locked) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeTop()
                    .padding(start = 4.dp, top = 4.dp)
            ) {
                Icon(Icons.Filled.Lock, contentDescription = "解锁", tint = Color.White)
            }
        }
    }
}

/**
 * 控制栏右上角显示的**系统当前时间**（HH:mm）。
 *
 * 注意它只在控制条可见时才会被组合（外层 AnimatedVisibility 之内），
 * 收起控制条后这个刷新循环随之结束，不会在后台一直空转。
 * 显示只精确到分钟，所以对齐到下一个整分钟再刷新，不做无意义的每秒重组。
 */
@Composable
private fun rememberClockText(): String {
    var text by remember { mutableStateOf(clockText()) }
    LaunchedEffect(Unit) {
        while (true) {
            text = clockText()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 1_000L)
        }
    }
    return text
}

private fun clockText(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Composable
private fun PlayerControls(
    title: String,
    playing: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    dragging: Boolean,
    sliderValue: Float,
    showEpisodeButton: Boolean = false,
    onOpenEpisodes: () -> Unit = {},
    // 当前播放的集号与总集数（顶栏"第N集"按钮）
    episodeNo: Int = 0,
    episodeTotal: Int = 0,
    // 加入书架（null 则不显示按钮）
    onAddShelf: (() -> Unit)? = null,
    onSliderValueChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    loopMode: LoopMode,
    scaleMode: ScaleMode,
    onLoopModeChange: (LoopMode) -> Unit,
    onScaleModeChange: (ScaleMode) -> Unit,
    onSpeedChange: (Float) -> Unit,
    showSpeedMenu: Boolean,
    onSpeedMenuToggle: (Boolean) -> Unit,
    // 锁定竖屏：横版视频也竖屏居中播放（上下留黑边），状态持久化
    lockedPortrait: Boolean = false,
    onLockPortraitChange: (Boolean) -> Unit = {},
    // 跳转经典播放页（首页形态：上方视频 + 下方选集方块）
    onOpenHome: () -> Unit = {},
    onLock: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderPosition = if (dragging) {
        sliderValue
    } else if (duration > 0) {
        (position.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .safeTop()
                .padding(vertical = 4.dp)
        ) {
            // 视频名称独占一行置于控制条最上方，避免原先与按钮挤在同一行导致名字被截断显示不全。
            // 同一行右端显示系统当前时间（与下方进度条的"播放进度"是两回事）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    rememberClockText(),
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onExit) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White
                    )
                }
                if (showEpisodeButton) {
                    TextButton(onClick = onOpenEpisodes) {
                        // 显示当前播放到第几集；点击查看所有集
                        Text(
                            "第${episodeNo}集",
                            color = Color.White,
                            fontSize = 13.sp
                        )
                    }
                }
                Box {
                    TextButton(onClick = { onSpeedMenuToggle(true) }) {
                        Text(formatSpeed(speed), color = Color.White, fontSize = 13.sp)
                    }
                    DropdownMenu(
                        expanded = showSpeedMenu,
                        onDismissRequest = { onSpeedMenuToggle(false) }
                    ) {
                        SPEEDS.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        formatSpeed(item),
                                        color = if (item == speed) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                },
                                onClick = {
                                    onSpeedChange(item)
                                    onSpeedMenuToggle(false)
                                }
                            )
                        }
                    }
                }
                // 右上角：循环模式（从底部左下角移到这里）
                IconButton(onClick = { onLoopModeChange(loopMode.next()) }) {
                    Icon(
                        loopMode.icon,
                        contentDescription = loopMode.label,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 锁定竖屏：点击切换，激活时高亮
                IconButton(onClick = { onLockPortraitChange(!lockedPortrait) }) {
                    Icon(
                        Icons.Filled.ScreenLockPortrait,
                        contentDescription = if (lockedPortrait) "解锁竖屏" else "锁定竖屏",
                        tint = if (lockedPortrait) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.White
                        }
                    )
                }
                // 锁定：与加入书架横向排列，和其余按钮同处一行（标题上移后已腾出空间）
                IconButton(onClick = onLock) {
                    Icon(
                        Icons.Filled.LockOpen,
                        contentDescription = "锁定",
                        tint = Color.White
                    )
                }
                if (onAddShelf != null) {
                    IconButton(
                        onClick = { onAddShelf?.invoke() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Filled.BookmarkAdd,
                            contentDescription = "加入书架",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.5f))
                .safeBottom()
                .padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatDuration(position),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.width(48.dp)
                )
                if (duration > 0) {
                    Slider(
                        value = sliderPosition,
                        onValueChange = onSliderValueChange,
                        onValueChangeFinished = onSliderFinished,
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .height(20.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .padding(horizontal = 8.dp)
                            .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    )
                }
                Text(
                    formatDuration(duration),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.width(48.dp),
                    textAlign = TextAlign.End
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左下角：首页（经典播放页，上方视频 + 下方选集方块）
                IconButton(onClick = onOpenHome) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = "首页",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                // 中：快退 / 播放 / 快进，整体居中
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onSeekBy(-10_000L) }) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "快退10秒",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    IconButton(onClick = onTogglePlay) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = { onSeekBy(10_000L) }) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "快进10秒",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                // 右：画面缩放
                IconButton(onClick = { onScaleModeChange(scaleMode.next()) }) {
                    Icon(
                        scaleMode.icon,
                        contentDescription = scaleMode.label,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * 视频首帧缩略图。列表页已解码过并写入 [VideoMetaCache]，这里基本是缓存命中，几乎无开销。
 * 用 Fit 而非 Crop，避免非当前页占位时画面被裁切得和真实播放画面不一致。
 */
@Composable
private fun ThumbnailLayer(video: LocalVideo) {
    val context = LocalContext.current
    var thumbnail by remember(video.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(video.uri) {
        thumbnail = VideoMetaCache.get(context, video).thumbnail
    }
    thumbnail?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun SeekPreview(
    target: Long,
    base: Long,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    val delta = target - base
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                (if (delta >= 0) "+" else "-") + formatDuration(abs(delta)),
                color = Color.White,
                fontSize = 18.sp
            )
            Text(
                "${formatDuration(target)} / ${formatDuration(duration)}",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp
            )
        }
    }
}

/**
 * 选集面板。
 *
 * 每一格只在集名很短时才显示集名，否则退回「第N集」——书源给的章节名常常是整段剧集标题，
 * 塞进格子里只会被截断成一团乱码。完整标题在播放器顶栏与占位页上是全的。
 *
 * 尚未解析出地址的集照常可点：跳转后外层会在落到该页时补解析。
 */
@Composable
private fun EpisodePickerSheet(
    videos: List<LocalVideo>,
    order: List<Int>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onReverse: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(order, currentIndex) {
        val pos = order.indexOf(currentIndex)
        if (pos >= 0) gridState.scrollToItem(pos)
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.6f),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "选集（共 ${videos.size} 集）",
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f)
                    )
                    // 倒序：一键反转本视频全部集的播放顺序，便于修正顺序错乱的剧集
                    TextButton(onClick = onReverse) {
                        Text("倒序", color = Color.White, fontSize = 14.sp)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 86.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(order) { position, origIndex ->
                        val video = videos[origIndex]
                        EpisodeItem(
                            label = if (video.name.length <= 7) {
                                video.name
                            } else {
                                "第${position + 1}集"
                            },
                            selected = origIndex == currentIndex,
                            ready = video.uri != Uri.EMPTY,
                            onClick = { onPick(origIndex) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EpisodeItem(
    label: String,
    selected: Boolean,
    ready: Boolean,
    onClick: () -> Unit,
) {
    val background = when {
        selected -> MaterialTheme.colorScheme.primary
        ready -> Color.White.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.05f)
    }
    val foreground = if (selected) {
        Color.Black
    } else {
        Color.White.copy(alpha = if (ready) 0.92f else 0.38f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = foreground,
            fontSize = 13.sp,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 播放器手势识别。刻意不消费任何按下事件，保证纵向滑动仍能被外层 [VerticalPager] 接走。
 */
private suspend fun PointerInputScope.detectPlayerGestures(
    onTap: () -> Unit,
    onDoubleTap: (Float) -> Unit,
    onLongPressStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSeekStart: () -> Unit,
    onSeek: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    onFeedSwitch: ((Int) -> Unit)? = null,
    onFeedDrag: ((Float) -> Unit)? = null,
    onFeedCancel: (() -> Unit)? = null,
    /** 播放顺序上前面没有集可切（第一集，或整部只有一集）：下滑改为切换列表里的上一个视频 */
    noPrevEpisode: Boolean = false,
    /** 播放顺序上后面没有集可切（最后一集，或整部只有一集）：上滑改为切换列表里的下一个视频 */
    noNextEpisode: Boolean = false,
) {
    val slop = viewConfiguration.touchSlop
    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    var lastTapUpTime = 0L

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 顶部/底部 10%「禁拖不禁点」：落点在这里本就不拦截按下，避免误触发应用内
        // 上/下滑切集、左/右滑快进，也不让外层 VerticalPager 切页；系统手势
        // （下拉通知、上滑多任务）由系统基于原始输入流独立识别，与应用层无关。
        // 关键修正：手指一旦移动超过 slop 才判定为拖拽并整段吃掉；
        // 纯「点一下」要放行给 onTap，否则进度条/播放暂停等控制条正好压在这块
        // 区域，隐藏时永远点不出来（这正是之前的问题）。
        if (down.position.y < size.height * 0.1f || down.position.y > size.height * 0.9f) {
            val startX = down.position.x
            val startY = down.position.y
            down.consume()
            var dragConsumed = false
            var ev = awaitPointerEvent(PointerEventPass.Main)
            while (true) {
                if (!dragConsumed && ev.changes.any {
                        abs(it.position.x - startX) + abs(it.position.y - startY) > slop
                    }
                ) {
                    dragConsumed = true
                }
                ev.changes.forEach { it.consume() }
                if (!ev.changes.any { it.pressed }) break
                ev = awaitPointerEvent(PointerEventPass.Main)
            }
            // 纯点按（没拖动）放行给 onTap，让隐藏的控制条能弹出来
            if (!dragConsumed) onTap()
            return@awaitEachGesture
        }
        val downTime = System.currentTimeMillis()
        var seeking = false
        var verticalTaken = false
        var longPressFired = false
        var feedTaken = false
        /** 跟手起点：越过 slop 那一刻的位移，避免刚起步画面就跳一下 */
        var feedDragBase = 0f
        var accumDx = 0f
        var accumDy = 0f
        // 右侧 20% 区域且存在列表：竖滑切换列表里的视频（不是切集），手势由本层消费。
        // 收窄到 20% 是为了减少误触：30% 时正常握持/调音量的落点常常落在区内。
        val inFeedZone = down.position.x >= size.width * 0.8f

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            accumDx += change.position.x - change.previousPosition.x
            accumDy += change.position.y - change.previousPosition.y

            if (!seeking && !verticalTaken) {
                if (abs(accumDx) > slop && abs(accumDx) > abs(accumDy)) {
                    seeking = true
                    onSeekStart()
                } else if (abs(accumDy) > slop) {
                    // 纵向滑动：默认交给外层分页器切集；
                    // 但该方向已经没有集可切时（最后一集再上滑、第一集再下滑），
                    // 分页器本来也不会动，这时整屏都改为切列表里的下一个/上一个视频。
                    verticalTaken = true
                    val up = accumDy < 0
                    val feedMode = onFeedSwitch != null &&
                        (inFeedZone || (up && noNextEpisode) || (!up && noPrevEpisode))
                    if (feedMode) {
                        feedTaken = true
                        feedDragBase = accumDy
                    }
                }
            }
            if (feedTaken) {
                // 消费纵向位移，阻止外层分页器切集，改为切换列表视频
                change.consume()
                // 跟手：手指移动多少画面就跟着移动多少（与上下滑切集时分页器跟手一致）
                onFeedDrag?.invoke(accumDy - feedDragBase)
            }
            if (seeking) {
                onSeek(change.position.x - change.previousPosition.x)
            }
            if (!seeking && !verticalTaken && !longPressFired &&
                System.currentTimeMillis() - downTime >= longPressTimeout
            ) {
                longPressFired = true
                onLongPressStart()
            }
        }

        if (feedTaken) {
            if (abs(accumDy) > slop * 6) {
                onFeedSwitch?.invoke(if (accumDy < 0) 1 else -1)
            } else {
                // 没过阈值：回弹到原位
                onFeedCancel?.invoke()
            }
        }
        if (longPressFired) onLongPressEnd()
        if (seeking) onSeekEnd()

        if (!seeking && !verticalTaken && !longPressFired) {
            val upTime = System.currentTimeMillis()
            if (upTime - downTime < longPressTimeout) {
                if (upTime - lastTapUpTime <= doubleTapTimeout) {
                    lastTapUpTime = 0L
                    onDoubleTap(down.position.x)
                } else {
                    lastTapUpTime = upTime
                    onTap()
                }
            }
        }
    }
}

private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** 倍速文案：去掉多余的小数尾零（0.50× → 0.5×），同时保留整数档的一位小数（1.0×） */
private fun formatSpeed(speed: Float): String {
    val text = "%.2f".format(Locale.getDefault(), speed).trimEnd('0')
    return (if (text.endsWith('.')) "${text}0" else text) + "×"
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "未知大小"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "$bytes B"
    } else {
        "%.1f %s".format(Locale.getDefault(), value, units[index])
    }
}

/**
 * 封面铺底：加载/解析/缓冲时显示书籍封面，快速切换视频时一眼可辨认。
 * 图片完整显示（FIT_CENTER）：占不满就留黑边，不做裁切。
 * 用 Glide 加载（与项目图片体系一致），URL 为空时退回纯黑。
 */
@Composable
internal fun CoverBackdrop(
    coverUrl: String,
    sourceOrigin: String? = null,
    modifier: Modifier = Modifier,
    /** 已预加载好的封面：传入则同步铺上，避免 SurfaceView「打洞」在异步刷上之前那 1 帧露出黑底 */
    initialDrawable: Drawable? = null,
) {
    if (coverUrl.isBlank()) {
        Box(modifier = modifier.background(Color.Black))
        return
    }
    AndroidView(
        factory = { ctx ->
            object : android.widget.ImageView(ctx) {
                override fun onDraw(canvas: android.graphics.Canvas) {
                    val d = drawable
                    // 兜底：封面 bitmap 已被图片框架回收（如切视频时旧封面释放），
                    // 若仍被绘制会抛「Canvas: trying to use a recycled bitmap」。这里跳过绘制，
                    // 露出下方黑底，等封面重新加载即可，避免崩溃。
                    if (d is android.graphics.drawable.BitmapDrawable
                        && d.bitmap?.isRecycled == true
                    ) {
                        super.setImageDrawable(null)
                        return
                    }
                    super.onDraw(canvas)
                }
            }.apply {
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                if (initialDrawable != null) setImageDrawable(initialDrawable)
            }
        },
        update = { view ->
            if (initialDrawable != null) {
                // 已用预加载的同步底图铺好：跳过异步加载，否则 Glide 会先清空再重_load，
                // 反而又制造一帧空封面 → 黑底闪动
                if (view.drawable != initialDrawable) view.setImageDrawable(initialDrawable)
            } else {
                // 与搜索/发现列表完全相同的图片加载链路（同一 ImageLoader + 书源 origin 头 + 共享缓存），
                // 列表已加载过的封面直接命中缓存、瞬间显示，不再重新联网拉取而露出黑底
                var options = RequestOptions()
                    .set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
                if (!sourceOrigin.isNullOrBlank()) {
                    options = options.set(OkHttpModelLoader.sourceOriginOption, sourceOrigin)
                }
                ImageLoader.load(view.context, coverUrl)
                    .apply(options)
                    .fitCenter()
                    .into(view)
            }
        },
        modifier = modifier
    )
}

/**
 * 无缝切换播放器交接池。
 *
 * 大屏播放页与首页（视频+选集方块）共用同一套播放组件，切换时不再销毁重建：
 * 播放页退出时把播放器实例（连同进度与已缓冲数据）存进池子，另一侧按
 * 「书籍 + 集序号」直接接管——不能用解析出的 URL 做键：签名型源的 URL
 * 每次解析都会变，按 URL 匹配永远落空，导致两边各自重建、各自记进度。
 *
 * LRU 上限 2 个：存入时自动暂停（避免无界面时继续出声），超过上限释放最旧的。
 * 全部仅在主线程访问，无需加锁。
 */
internal object SharedPlayer {
    private class Entry(
        val player: ExoPlayer,
        val key: String,
        val headers: Map<String, String>,
    )

    private val entries = ArrayList<Entry>()
    /** 显式交接槽：点「首页/放大」按钮的瞬间同步放入，另一侧组合时取走，无时序竞争 */
    private var pendingEntry: Entry? = null
    /**
     * 「下一个视频」的预缓冲实例：**独占槽位，不参与 [entries] 的 LRU 淘汰**。
     *
     * 之前它跟大屏↔首页交接池共用 entries（上限 2、淘汰最早），而它总是最早入队的那个：
     * 每切一集就有一次页面销毁入库，两三次就把预缓冲挤掉并 release。
     * 且它自带请求头（源 header + 嗅探出的 Referer），与书源 header 必然不同，
     * 若按 [take] 的 headers 相等去匹配，会永远取不到、却因为 [has] 命中而不再重建
     * ——变成一个"永久占位但取不出来"的僵尸槽位。
     * 所以单独放这里：按 key 取用，带自己的请求头，且不会被别的实例挤掉。
     */
    private var nextVideo: Entry? = null

    /** 交接键：书籍 + 集序号（与解析出的 URL 无关） */
    fun key(bookUrl: String, chapterIndex: Int) = "$bookUrl#$chapterIndex"

    fun has(bookUrl: String, chapterIndex: Int): Boolean {
        val k = key(bookUrl, chapterIndex)
        return entries.any { it.key == k } ||
            pendingEntry?.key == k ||
            nextVideo?.key == k
    }

    fun take(bookUrl: String, chapterIndex: Int, headers: Map<String, String>): ExoPlayer? {
        val k = key(bookUrl, chapterIndex)
        // 下一个视频的预缓冲优先接管：它自带请求头（含嗅探出的 Referer），
        // 不与书源 header 比较——比了必然不等，等于白预缓冲一场。
        nextVideo?.let { nv ->
            if (nv.key == k) {
                nextVideo = null
                return nv.player
            }
        }
        // 先取显式交接的实例
        pendingEntry?.let { pe ->
            pendingEntry = null
            if (pe.key == k && pe.headers == headers) return pe.player
            pe.player.release()
        }
        val i = entries.indexOfFirst { it.key == k && it.headers == headers }
        if (i < 0) {
            // 键命中但书源头不一致的残留：清掉时要 release，否则每切一次视频漏一个 ExoPlayer
            entries.filter { it.key == k }.forEach { it.player.release() }
            entries.removeAll { it.key == k }
            return null
        }
        return entries.removeAt(i).player
    }

    /** 下一部预缓冲是否已就位且请求头一致（避免同一目标被反复重建） */
    fun hasNextVideo(
        bookUrl: String,
        chapterIndex: Int,
        headers: Map<String, String>,
    ): Boolean {
        val nv = nextVideo ?: return false
        return nv.key == key(bookUrl, chapterIndex) && nv.headers == headers
    }

    /** 显式交接：点「首页/放大」按钮时同步调用，播放器带着进度与缓冲原样移交 */
    fun handOver(player: ExoPlayer, bookUrl: String, chapterIndex: Int, headers: Map<String, String>) {
        if (entries.any { it.player === player } || pendingEntry?.player === player) return
        val k = key(bookUrl, chapterIndex)
        entries.filter { it.key == k }.forEach { it.player.release() }
        entries.removeAll { it.key == k }
        pendingEntry?.player?.release()
        player.pause()
        player.playWhenReady = false
        pendingEntry = Entry(player, k, headers)
    }

    /** 播放页退出时存入（自动暂停）；已显式交接的实例不会重复存入 */
    fun keep(player: ExoPlayer, bookUrl: String, chapterIndex: Int, headers: Map<String, String>) {
        if (entries.any { it.player === player } || pendingEntry?.player === player
            || nextVideo?.player === player
        ) return
        val k = key(bookUrl, chapterIndex)
        // 同键的旧实例要先 release 再移除：直接 removeAll 会让它再也无人引用却没被释放，
        // 每切一次集就漏一个 ExoPlayer（内存与解码资源都回不来）
        entries.filter { it.key == k }.forEach { it.player.release() }
        entries.removeAll { it.key == k }
        player.pause()
        player.playWhenReady = false
        entries.add(Entry(player, k, headers))
        while (entries.size > 2) {
            entries.removeAt(0).player.release()
        }
    }

    /**
     * 登记「下一个视频」的预缓冲实例（独占槽位，不参与上面的 LRU）。
     * 换目标就释放旧的：用户可能先往下预缓冲了 B，又往上切回 A，B 的那份不该一直占着解码资源。
     */
    fun keepNextVideo(
        player: ExoPlayer,
        bookUrl: String,
        chapterIndex: Int,
        headers: Map<String, String>,
    ) {
        nextVideo?.let { old ->
            if (old.player !== player) old.player.release()
        }
        player.pause()
        player.playWhenReady = false
        nextVideo = Entry(player, key(bookUrl, chapterIndex), headers)
    }

    /** 放弃这份预缓冲（退出播放器 / 切换列表时调用），避免留下无主的 ExoPlayer */
    fun releaseNextVideo() {
        nextVideo?.player?.release()
        nextVideo = null
    }
}

/** 预取到的「下一个视频」首集：书籍地址 + 起始集序号 + 流地址 + 该集上次看到的位置 */
data class NextVideoPreload(
    val bookUrl: String,
    val index: Int,
    val uri: String,
    val startPositionMs: Long = 0L,
    /**
     * 这个地址实际要带的请求头：书源 header + 嗅探到的 Referer。
     *
     * 加密/防盗链源的直链是嗅探出来的，站点往往校验 Referer；而书源配置的 header 里
     * 并没有它（它只存在于嗅探到的那一次请求里）。预缓冲若不带上，缓冲能建起来、
     * 切过去却 403。所以这里一并带出，取用时直接用它，而不是再拿书源 header 去比对。
     */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 下一集预缓冲。
 *
 * 当前集开始正常播放后，为下一集提前建立播放器并 prepare（不播放），
 * 缓冲窗口上下限都压在 10 秒——对一集几十分钟的正片来说远低于 10% 的流量，
 * 却足够切集时秒开。播放页切到该集时通过 [take] 直接接管这个已缓冲的实例。
 *
 * 只保留一集：预缓冲新的会释放旧的。全部仅在主线程访问，无需加锁。
 */
/** 移动网络下「下一个视频」的预缓冲时长（毫秒）：移动流量贵，但仍给到 20 秒缓解卡顿 */
private const val PRELOAD_BUFFER_MS_MOBILE = 20_000
/** WiFi 下「下一个视频」的预缓冲时长（毫秒）：不按流量计费，多缓冲到 40 秒换切过去更稳 */
private const val PRELOAD_BUFFER_MS_WIFI = 40_000
/**
 * 开跑预缓冲前，要求当前这一集自己已经攒下的缓冲量（毫秒）。
 *
 * 取 3 秒而不是更长：这点缓冲足够起播，起播后播放器自己会继续往后缓存，
 * 不需要等当前集攒满一大段才轮到预缓冲下一部——否则"刚打开就划走"的场景
 * 永远等不到下一部的缓冲，而那种场景恰恰最需要它。
 */
private const val PRELOAD_SELF_AHEAD_MS = 3_000L

/**
 * 预缓冲前先等当前这一路站稳。三条轨任一满足即放行：
 *
 * - **已经起播**（[Player.isPlaying]）：最硬的一条证据，说明带宽够用、画面已出来，
 *   起播后它自己会继续往后缓存，不必再替它攒。这条**不看时长也不看码率**，
 *   所以可变码率、未知时长/直播这类"算不准 3 秒到底是多少数据"的流全靠它兜住。
 * - **前方已攒够 [PRELOAD_SELF_AHEAD_MS]**：起播前用这条，避免刚开播就去抢带宽。
 * - **超时兜底**：当前集真卡死（一直在 BUFFERING）时不能连预缓冲一起拖住。
 *
 * 预缓冲的意义是「切过去秒开」，代价是和正在播的这一路抢带宽，
 * 所以条件是"站稳"而不是"越多越好"——攒够了就该让位给下一部。
 */
private suspend fun awaitSelfBuffered(
    activePlayer: MutableState<Player?>,
    timeoutMs: Long = 20_000L,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val p = activePlayer.value
        if (p != null
            && p.playbackState == Player.STATE_READY
            && (p.isPlaying
                || p.bufferedPosition - p.currentPosition >= PRELOAD_SELF_AHEAD_MS)
        ) return
        delay(500)
    }
}

/** 预缓冲时长按网络类型区分：WiFi 下多缓冲一段，移动网络省着花 */
private fun preloadBufferMs(context: Context): Int =
    if (context.isWifiConnect) PRELOAD_BUFFER_MS_WIFI else PRELOAD_BUFFER_MS_MOBILE

/**
 * 记录**当前是否有分片正在传输**，供预缓冲补齐到分片边界用。
 *
 * 注意是"在途数"而不是累计数：累计数只增不减，一旦拿去当闸门，接管开始播放后
 * 就再也打不开，播放器不再继续加载（表现为每次都转圈）。这里只关心"手上还有没有
 * 没下完的分片"。
 */
private class ShardTracker : TransferListener {
    @Volatile
    var inFlight = 0
        private set

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork && isMediaShard(dataSpec.uri.toString())) inFlight++
    }

    override fun onBytesTransferred(
        source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int,
    ) = Unit

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (isNetwork && isMediaShard(dataSpec.uri.toString()) && inFlight > 0) inFlight--
    }
}

/**
 * 这个请求下载的是**分片流的数据块**，而不是播放列表、也不是直链单文件。
 *
 * 两类都要排除：
 * - m3u8/mpd 是索引不是数据，且不少源的列表地址本身就带 /m3u8/ 路径段
 *   （如 https://x/m3u8/index.m3u8），不排掉会被误判成数据块；
 * - .mp4/.m4v/.webm 直链整段就是一个请求，"补齐"等于把整个文件下完，
 *   那种流只该按时长截断（与不加分片时的行为一致）。
 */
private fun isMediaShard(url: String): Boolean {
    val path = url.substringBefore('?').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".mpd")) return false
    if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".webm")) return false
    return path.endsWith(".ts") || path.endsWith(".m4s") || path.contains("/m3u8/")
}

/**
 * 预缓冲专用：**按时长叫停，但不在分片中间截断**。
 *
 * HLS 的最小下载单位是分片（.ts）。只看时长的话，10 秒一到就把传输掐断，很容易停在
 * 分片中间——那半个分片既播不了（要重下）又白花了流量。所以时长达标后不立刻收，
 * 允许把手上已经开始传输的那个分片下完，凑到分片边界再停；那个分片收尾后
 * inFlight 归零，下一次询问才真正返回 false（最多多补一个分片）。
 *
 * 分片在这里**只用于补齐，不做上限**：分片计数只增不减，拿它当闸门会在接管
 * 开始播放后永久拦截加载，切过去反而每次都转圈。
 *
 * 两个实现上的坑：
 * 1. 必须**继承**而不能写成 `LoadControl by delegate`——Kotlin 的接口委托不转发
 *    Java 接口的 default 方法，getBackBufferDurationUs() 会落到空默认实现上直接抛
 *    IllegalStateException；
 * 2. override 的必须是 `shouldContinueLoading(Parameters)` 这一版，media3 1.8 已把
 *    LoadControl 换成 Parameters 版，老的 (long, long, float) 不再被调用。
 */
private class ShardAwareLoadControl(
    private val bufferMs: Int,
    private val shards: ShardTracker,
) : DefaultLoadControl() {

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // 时长达标、且手上没有还在传输的分片 → 收；否则让它把当前分片下完
        if (parameters.bufferedDurationUs >= bufferMs * 1_000L && shards.inFlight == 0) return false
        // 未达标时走 DefaultLoadControl 自己的策略；它默认要 50 秒，
        // 上面这条会在 10/30 秒先行拦住，所以真正生效的是传入的 bufferMs
        return super.shouldContinueLoading(parameters)
    }
}

internal object PreloadCache {
    private var cachedUri: Uri? = null
    private var cachedHeaders: Map<String, String>? = null
    private var player: ExoPlayer? = null

    fun prepare(context: Context, uri: Uri, headers: Map<String, String>) {
        if (uri == cachedUri && player != null) return
        release()
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(buildMediaSourceFactory(context, headers))
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 10_000, 2_000, 5_000)
                    .build()
            )
            .build()
            .apply {
                setMediaItem(buildMediaItem(uri))
                prepare()
                playWhenReady = false
            }
        cachedUri = uri
        cachedHeaders = headers
    }

    /**
     * 预缓冲**下一个视频**的首集。
     *
     * 与预缓冲下一集不同：切换视频会重建整组播放组件（[PreloadCache] 的槽位随之释放），
     * 所以这里不留在 [PreloadCache]，而是登记进 [SharedPlayer] 的**独占槽位**
     * （[SharedPlayer.keepNextVideo]）——它由「书籍 + 集序号」索引，能跨过这次重建存活，
     * 且不会被大屏↔首页交接池的 LRU 挤掉，新的一页组合时按同一个键接管。
     *
     * [headers] 用预取结果自带的请求头（书源 header + 嗅探出的 Referer），
     * 而不是当前视频的书源 header：加密源钓出来的直链常校验 Referer，少了必然 403。
     */
    fun prepareNextVideo(
        context: Context,
        bookUrl: String,
        index: Int,
        uri: Uri,
        headers: Map<String, String>,
        startPositionMs: Long = 0L,
        // 缓冲多长：由调用方按网络类型给（WiFi 30 秒 / 移动网络 10 秒）
        bufferMs: Int = PRELOAD_BUFFER_MS_MOBILE,
    ) {
        if (bookUrl.isBlank() || uri == Uri.EMPTY) return
        if (SharedPlayer.hasNextVideo(bookUrl, index, headers)) return
        // 跟踪分片传输，只为"补齐到分片边界"服务，不做数量上限
        val shards = ShardTracker()
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(buildMediaSourceFactory(context, headers, shards))
            .setLoadControl(ShardAwareLoadControl(bufferMs, shards))
            .build()
            .apply {
                setMediaItem(buildMediaItem(uri))
                if (startPositionMs > 0) seekTo(startPositionMs)
                prepare()
                playWhenReady = false
            }
        SharedPlayer.keepNextVideo(player, bookUrl, index, headers)
    }

    /** 播放页接管预缓冲的实例；书源头不匹配（极少见）时弃用重建 */
    fun take(uri: Uri, headers: Map<String, String>): ExoPlayer? {
        if (uri != cachedUri || player == null) return null
        if (cachedHeaders != headers) {
            release()
            return null
        }
        val taken = player
        player = null
        cachedUri = null
        cachedHeaders = null
        return taken
    }

    fun releaseAll() {
        release()
    }

    private fun release() {
        player?.release()
        player = null
        cachedUri = null
        cachedHeaders = null
    }
}

/**
 * 播放/解析失败时的提示条（取代各处原来的模态错误弹窗）。
 *
 * 旧实现是一个 Dialog：一出现就吃掉所有触摸，点「关闭」还会直接 finish()，
 * 于是"这一部看不了就只能退出播放页"，连竖滑切下一个视频都做不到。
 * 现在这条提示贴在底部、只吃掉自己几个按钮的点击，不拦其它区域，页面也不退出：
 * 可以点「重试」，有列表时（canSwitch）右侧 20% 照旧能竖滑切下一个。
 *
 * 播放器页（SourceVideoPlayerActivity）与书源启动页（VideoLaunchActivity）共用。
 */
@Composable
internal fun BoxScope.ErrorNotice(
    message: String,
    canSwitch: Boolean,
    onRetry: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(start = 16.dp, end = 16.dp, bottom = 40.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xDD1C1C1E),
        contentColor = Color.White,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = stringResource(R.string.video_get_url_fail),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(top = 4.dp),
            )
            if (canSwitch) {
                Text(
                    text = "可滑动屏幕右侧切换下一个视频",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onRetry) {
                    Text(text = "重试", color = Color.White)
                }
                TextButton(onClick = onExit) {
                    // 文案用「退出」而不是「关闭」：这个按钮是真的离开播放页，
                    // 标成「关闭」会让人以为只是关掉提示（正是旧弹窗最别扭的地方）
                    Text(text = "退出", color = Color.White)
                }
            }
        }
    }
}
