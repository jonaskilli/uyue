package io.legado.app.ui.book.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.help.video.VideoDownloader
import io.legado.app.help.video.VideoFeedStore
import io.legado.app.help.video.InfiniteSwipeFeed
import io.legado.app.ui.timetech.player.CoverBackdrop
import io.legado.app.ui.timetech.player.EpisodeItem
import io.legado.app.ui.timetech.player.ErrorNotice
import io.legado.app.ui.timetech.player.FullscreenVideoPlayer
import io.legado.app.ui.timetech.player.LocalVideo
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.utils.toastOnUi
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.widget.RemoteViews
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import io.legado.app.constant.AppConst
import io.legado.app.constant.NotificationId
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在线剧集播放页（时光播放器）。
 *
 * 与 [VideoLaunchActivity] 的分工：那边的书源页面自带播放器与选集，App 只负责把它拉起来；
 * 这边由 App 接管播放，因此自带选集面板、进度记忆、倍速等能力。
 *
 * 「首页模式」：同一个 Activity 内把播放器**原地压缩**到顶部 240dp，
 * 下方显示选集方块网格——播放器实例不销毁不重建，切换零缓冲、进度天然一份。
 * 解析不出直链的剧集会自动退回 [VideoLaunchActivity] 的路径。
 */
class SourceVideoPlayerActivity : BaseComposeActivity(imageBg = false) {

    @Composable
    override fun Content() {
        // 打开时光播放器时清掉可能残留后台的内置浏览器视频实例，避免旧的叠在前面显示
        LaunchedEffect(Unit) { WebViewActivity.finishVideoInstance() }
        val state by SourceVideoPlayerSession.uiState.collectAsStateWithLifecycle()
        // ===== 通知栏 / 锁屏媒体控制（轻量，不做后台播放）=====
        // 单个 MediaSession 代表「当前正在播的那一集」，由 VideoPlayerPage 在成为当前页时
        // 上报它的 Player 实例；通知的播放/暂停按钮经一个动态广播转发到 MediaSession 的
        // transportControls，从而控制真正的播放器。Activity 销毁即取消通知、释放会话。
        val activePlayer = remember { mutableStateOf<Player?>(null) }
        val mediaSession = remember {
            MediaSessionCompat(this, "sourceVideoPlayer").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        activePlayer.value?.play()
                    }

                    override fun onPause() {
                        activePlayer.value?.pause()
                    }

                    override fun onSeekTo(pos: Long) {
                        activePlayer.value?.seekTo(pos)
                    }

                    override fun onStop() {
                        activePlayer.value?.pause()
                    }
                })
                isActive = true
            }
        }
        // 通知栏播放/暂停按钮：动态广播转发给 MediaSession（不复用朗读的 MediaButtonReceiver）
        val toggleReceiver = remember {
            object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val ctrl = mediaSession.controller.transportControls
                    if (activePlayer.value?.isPlaying == true) ctrl.pause() else ctrl.play()
                }
            }
        }
        DisposableEffect(Unit) {
            ContextCompat.registerReceiver(
                this@SourceVideoPlayerActivity,
                toggleReceiver,
                IntentFilter(ACTION_TOGGLE_VIDEO_PLAYBACK),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            onDispose {
                this@SourceVideoPlayerActivity.unregisterReceiver(toggleReceiver)
                mediaSession.isActive = false
                mediaSession.release()
                NotificationManagerCompat.from(this@SourceVideoPlayerActivity)
                    .cancel(NotificationId.SourceVideoPlayer)
            }
        }
        LaunchedEffect(Unit) {
            var lastPlaying = false
            var lastTitle = ""
            while (true) {
                delay(1000)
                val player = activePlayer.value
                val ui = SourceVideoPlayerSession.uiState.value
                val episode = ui.episodes.getOrNull(ui.currentIndex)?.name ?: ""
                val title = if (ui.bookName.isBlank()) episode else ui.bookName
                val playing = player?.isPlaying == true
                val pos = player?.currentPosition?.coerceAtLeast(0) ?: 0L
                val dur = player?.duration?.takeIf { it > 0 } ?: 0L
                // 锁屏 / 媒体会话状态（驱动锁屏进度与播放/暂停按钮）
                mediaSession.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(
                            PlaybackStateCompat.ACTION_PLAY or
                                    PlaybackStateCompat.ACTION_PAUSE or
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                    PlaybackStateCompat.ACTION_SEEK_TO or
                                    PlaybackStateCompat.ACTION_STOP
                        )
                        .setState(
                            if (playing) PlaybackStateCompat.STATE_PLAYING
                            else PlaybackStateCompat.STATE_PAUSED,
                            pos, 1f
                        )
                        .build()
                )
                mediaSession.setMetadata(
                    MediaMetadataCompat.Builder()
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_TITLE,
                            title.ifBlank { getString(R.string.video) }
                        )
                        .putString(
                            MediaMetadataCompat.METADATA_KEY_ARTIST,
                            episode.ifBlank { getString(R.string.video) }
                        )
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                        .build()
                )
                // 仅播放状态或标题变化时重发通知，避免无谓刷新
                if (playing != lastPlaying || title != lastTitle) {
                    lastPlaying = playing
                    lastTitle = title
                    NotificationManagerCompat.from(this@SourceVideoPlayerActivity).notify(
                        NotificationId.SourceVideoPlayer,
                        buildVideoNotification(
                            playing = playing,
                            title = title,
                            subtitle = episode,
                            sessionToken = mediaSession.sessionToken,
                            bookUrl = ui.bookUrl,
                            homeMode = this@SourceVideoPlayerActivity.intent.getBooleanExtra("homeMode", false),
                            startIndex = ui.currentIndex,
                        ).build()
                    )
                }
            }
        }
        // homeMode：搜索结果进来时直接落在"播放器在上 + 选集在下"的首页形态，方便先挑集
        var homeMode by remember {
            mutableStateOf(intent.getBooleanExtra("homeMode", false))
        }
        // infiniteSwipe：无限刷模式（来自视频发现页的「无限刷」按钮）。
        // 该模式下所有视频都用时光播放器播放；解析/播放失败或需书源自带播放器时，
        // 不弹错误退出，而是自动切到下一个视频。
        var infiniteSwipe by remember {
            mutableStateOf(intent.getBooleanExtra("infiniteSwipe", false))
        }
        // 连续失败计数：超过阈值（10）仍全是失效视频则退出，避免死循环
        var consecutiveFails by remember { mutableIntStateOf(0) }
        // 失败切换冷却：避免解析/播放失败时瞬间狂跳，把一整批视频在零点几秒内全跳过
        var lastSkipMs by remember { mutableStateOf(0L) }
        /** 弹窗与自动跳过共用：两者的切换动作都带冷却与计数，需要跑在协程里 */
        val errorScope = rememberCoroutineScope()
        /** 非空 = 正在询问"这个出错的视频要不要跳过" */
        var errorChoice by remember { mutableStateOf<ErrorChoice?>(null) }
        // 用户已经手动切走了（竖滑/返回）就没必要再问了
        LaunchedEffect(state.playbackError) {
            if (state.playbackError == null) errorChoice = null
        }

        // 播放顺序（显示顺序）：videos 的下标序列，分页第 page 页播 videos[playOrder[page]]。
        // 提升到 Activity 层，让「选集面板」与「主页下方目录」共用同一份顺序，
        // 点「倒序」/切循环模式时两者一起反转（随机播放同理）。按 bookUrl + 集数重置，
        // 切到另一个视频时回到自然序。
        val playOrderState = remember(state.bookUrl, state.episodes.size) {
            // 集数变化（切到另一部、或后台刷新出更新后的新集）都会重算这里，
            // 按该视频自己的 reverseOrder 复原，否则用户设的倒序会被悄悄打回自然序
            mutableStateOf(
                if (state.reverseOrder) state.episodes.indices.toList().reversed()
                else state.episodes.indices.toList()
            )
        }
        var playOrder by playOrderState
        /**
         * 分页器当前页码。主页那排选集方块的高亮直接用它，
         * 与选集面板同源——点一下就变色，不需要和进度上报抢状态。
         */
        val currentPageState = remember(state.bookUrl, state.episodes.size) {
            mutableStateOf(state.startIndex.coerceAtLeast(0))
        }

        LaunchedEffect(intent.getStringExtra("bookUrl")) {
            SourceVideoPlayerSession.load(
                bookUrl = intent.getStringExtra("bookUrl").orEmpty(),
                startIndex = intent.getIntExtra("startIndex", 0),
            )
        }

        // 这一集拿不到直链：交给书源自己的播放器（无限刷模式下不拉起，改由下面的失败自动切换处理）
        LaunchedEffect(state.fallbackIndex) {
            val index = state.fallbackIndex ?: return@LaunchedEffect
            if (infiniteSwipe) return@LaunchedEffect
            SourceVideoPlayerSession.playWithSourcePlayer(index)
        }
        // 无限刷模式：解析/播放失败、或需要书源自带播放器时，不弹错误、不退出，
        // 自动切到下一个视频；连续过多失效才真正退出（防死循环）。
        LaunchedEffect(
            state.error,
            state.fallbackFailed,
            state.fallbackIndex,
            infiniteSwipe,
        ) {
            if (!infiniteSwipe) return@LaunchedEffect
            val failed = state.error != null
                    || state.fallbackFailed != null
                    || state.fallbackIndex != null
            if (!failed) return@LaunchedEffect
            // 解析/回退类失败（详情页出错、目录列表为空、拉不起书源播放器等）：
            // 多半是书源规则对该页面不适应或临时网络问题，不当成"视频失效"永久屏蔽，
            // 否则能播的视频会被误删、列表越刷越少（markFailed 会持久化屏蔽并移出缓存列表）。
            consecutiveFails++
            if (consecutiveFails > 10) {
                SourceVideoPlayerSession.clearError()
                SourceVideoPlayerSession.skipFallback()
                toastOnUi(this@SourceVideoPlayerActivity.getString(R.string.infinite_swipe_too_many_failed))
                finish()
            } else {
                // 冷却：避免零点几秒内狂跳，把一批视频在瞬间全跳过
                val wait = (800L - (System.currentTimeMillis() - lastSkipMs)).coerceAtLeast(0)
                if (wait > 0) delay(wait)
                lastSkipMs = System.currentTimeMillis()
                SourceVideoPlayerSession.clearError()
                SourceVideoPlayerSession.skipFallback()
                toastOnUi(this@SourceVideoPlayerActivity.getString(R.string.infinite_swipe_skip_failed))
                val ok = SourceVideoPlayerSession.feedSwitch(1)
                if (!ok && !SourceVideoPlayerSession.feedSwitch(-1)) {
                    finish()
                }
            }
        }
        /**
         * 播放出错后"切到下一个视频"的统一出口。
         *
         * 抽出来是因为它现在有两个触发点：弹窗里的「自动跳过」「跳过」，以及
         * 已经选过自动跳过时的静默切换。两处行为必须完全一致。
         *
         * `markFailed` 也挪进来了：它是"永久拉黑"，只有**确定要跳过**时才该执行。
         * 放在弹窗之前的话，用户点「取消」这个视频也已经被拉黑了。
         */
        fun switchToNextVideo(failedUrl: String) {
            errorScope.launch {
                // 只有无限刷才把坏视频永久拉黑：发现页/搜索页竖滑只是临时看看，
                // 一次偶发 403/超时就把人家永久屏蔽，以后正常浏览再也刷不到（且换网络也回不来）
                if (infiniteSwipe && failedUrl.isNotBlank()) InfiniteSwipeFeed.markFailed(failedUrl)
                consecutiveFails++
                if (consecutiveFails > 10) {
                    SourceVideoPlayerSession.clearError()
                    SourceVideoPlayerSession.skipFallback()
                    toastOnUi(getString(R.string.infinite_swipe_too_many_failed))
                    finish()
                    return@launch
                }
                // 冷却：避免零点几秒内狂跳
                val wait = (800L - (System.currentTimeMillis() - lastSkipMs)).coerceAtLeast(0)
                if (wait > 0) delay(wait)
                lastSkipMs = System.currentTimeMillis()
                SourceVideoPlayerSession.clearError()
                SourceVideoPlayerSession.skipFallback()
                toastOnUi(getString(R.string.infinite_swipe_skip_failed))
                val ok = SourceVideoPlayerSession.feedSwitch(1)
                if (!ok && !SourceVideoPlayerSession.feedSwitch(-1)) {
                    finish()
                }
            }
        }

        // 播放阶段出错（ExoPlayer 报错：无法播放/播放出错/网址问题等）：处在有列表可切的
        // 场景（无限刷、发现页/搜索竖滑等）时，先问一句再决定要不要切下一个——
        // 之前是无条件直接切，用户连选择的机会都没有。
        LaunchedEffect(state.playbackError, state.feedEnabled, infiniteSwipe) {
            val msg = state.playbackError ?: return@LaunchedEffect
            if (!state.feedEnabled) {
                // 单视频没有下一个可切，清掉状态避免反复触发
                SourceVideoPlayerSession.clearError()
                return@LaunchedEffect
            }
            if (isAutoSkipFailedVideo()) {
                // 本次运行内已经选过"自动跳过"，不再打扰
                switchToNextVideo(state.bookUrl)
            } else {
                errorChoice = ErrorChoice(msg, state.bookUrl)
            }
        }

        // 出错视频的选择弹窗：自动跳过 / 跳过 / 取消
        errorChoice?.let { choice ->
            PlaybackErrorChoiceDialog(
                videoName = state.bookName,
                message = choice.message,
                onAutoSkip = {
                    setAutoSkipFailedVideo(true)
                    errorChoice = null
                    switchToNextVideo(choice.bookUrl)
                },
                onSkip = {
                    errorChoice = null
                    switchToNextVideo(choice.bookUrl)
                },
                onCancel = {
                    // 停在当前的错误界面，什么都不动：
                    // state.playbackError 没被清掉，而 LaunchedEffect 的 key 没变就不会重跑，
                    // 所以不会又弹一次
                    errorChoice = null
                },
            )
        }
        // 成功加载出可播的视频（无错误、有剧集）时，重置连续失败计数
        LaunchedEffect(state.error, state.episodes.size, state.fallbackIndex) {
            if (infiniteSwipe
                && state.error == null
                && state.fallbackIndex == null
                && state.episodes.isNotEmpty()
            ) {
                consecutiveFails = 0
            }
        }
        // 解析/回退失败只弹一次 toast 告知，不再用模态错误框打断操作：
        // 页面留在播放页，可点提示条上的「重试」，有列表时右侧 20% 竖滑即可切下一个视频。
        LaunchedEffect(state.error, state.fallbackFailed) {
            val msg = state.error ?: state.fallbackFailed ?: return@LaunchedEffect
            if (infiniteSwipe) return@LaunchedEffect
            toastOnUi(msg)
        }
        // 必须等浏览器真的拉起来再关页面，否则还在转场就被销毁，播放器起不来
        LaunchedEffect(state.fallbackLaunched) {
            if (state.fallbackLaunched) finish()
        }

        // 预加载封面 drawable：解析期间根底图已把它载进共享缓存，这里取出交给播放器，
        // 让播放器内部封面在第 0 帧就铺好，避免 SurfaceView「打洞」在异步刷上之前露黑底
        var coverDrawable by remember { mutableStateOf<Drawable?>(null) }
        var coverTarget by remember { mutableStateOf<CustomTarget<Drawable>?>(null) }
        DisposableEffect(Unit) {
            onDispose {
                coverTarget?.let { Glide.with(this@SourceVideoPlayerActivity).clear(it) }
            }
        }
        LaunchedEffect(state.coverUrl, state.coverOrigin) {
            val prev = coverTarget
            coverTarget = null
            prev?.let { Glide.with(this@SourceVideoPlayerActivity).clear(it) }
            // 旧封面已被 Glide 释放（底层 bitmap 可能被回收入池），立即摘除显示，
            // 否则 CoverBackdrop 的 ImageView 仍持有它并在下一帧绘制 →
            // 「Canvas: trying to use a recycled bitmap」崩溃（切视频时必现）
            coverDrawable = null
            if (state.coverUrl.isBlank()) {
                return@LaunchedEffect
            }
            val target = object : CustomTarget<Drawable>(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL) {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    coverDrawable = resource
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // 不清空：该 drawable 可能正被播放器显示，引用由我们自行管理
                }
            }
            coverTarget = target
            var options = RequestOptions()
                .set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
            if (state.coverOrigin.isNotBlank()) {
                options = options.set(OkHttpModelLoader.sourceOriginOption, state.coverOrigin)
            }
            ImageLoader.load(this@SourceVideoPlayerActivity, state.coverUrl)
                .apply(options)
                .fitCenter()
                .into(target)
        }

        // 边看边存：进入/切集时统一触发（全屏与首页模式共用同一入口）。
        // 切集时先收尾上一集、再入队当前集；已下载完的集会切成本地文件播放。
        // 之前挂在首页面板里，全屏播放时完全不触发，播完一集什么都没存下。
        LaunchedEffect(
            state.bookUrl,
            state.currentIndex,
            state.episodes.getOrNull(state.currentIndex)?.uri,
        ) {
            if (state.episodes.isNotEmpty()) {
                SourceVideoPlayerSession.onEpisodeActivated(state.currentIndex)
            }
        }

        // 边播边存的"边播"部分：播放期间每 20 秒收一次已缓冲的分片（纯本地搬运，零流量）。
        // 只靠切集/退出各触发一次的话，播放过程中进度条会一直停在"等待中"。
        LaunchedEffect(state.bookUrl, state.currentIndex) {
            if (state.episodes.isEmpty()) return@LaunchedEffect
            while (true) {
                SourceVideoPlayerSession.collectCurrentEpisode()
                delay(20_000)
            }
        }

        // 页面不在了就解除"让路"：否则后台下载会一直被限速
        DisposableEffect(Unit) {
            onDispose { VideoDownloader.setPlaybackActive(false) }
        }

        // ===== 右侧 20% 竖滑切换视频：跟手位移 + 翻页 =====
        // 位移单位统一用 dp，0 = 就位；负值=画面上移，正值=画面下移
        var feedOffsetDp by remember { mutableFloatStateOf(0f) }
        // 切换方向：+1 下一个 / -1 上一个，0 = 本次不是切视频。
        // 放在 key(...) 之外，才能在播放器整组重建后仍记得该往哪个方向翻。
        var feedSwitchDir by remember { mutableIntStateOf(0) }
        // 切视频进行中（正在加载下一个视频）：期间用封面+转圈覆盖，不显示黑色 Loading
        var feedLoading by remember { mutableStateOf(false) }
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        val density = LocalDensity.current
        val feedScope = rememberCoroutineScope()

        /** 跟手：手指移动多少，画面就跟着移动多少（与上下滑切集的分页器观感一致） */
        val onFeedDrag: (Float) -> Unit = { dyPx ->
            feedOffsetDp = with(density) { dyPx.toDp().value }
        }
        /** 没到阈值就松手：回弹到原位 */
        val onFeedCancel: () -> Unit = {
            feedScope.launch {
                animate(
                    initialValue = feedOffsetDp,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                ) { value, _ -> feedOffsetDp = value }
            }
        }
        /**
         * 切到列表里的上一个/下一个视频（offset：+1 下一个，-1 上一个）。
         *
         * 抽成变量而不是内联在播放器参数里，是为了让「解析失败」这种没有播放器可渲染的状态
         * 也能复用同一套切换动作（见下方 FeedSwipeZone）。
         */
        val switchFeed: (Int) -> Unit = { offset ->
            feedSwitchDir = offset
            // 立刻用第二个（目标）视频的封面铺底，让滑动全程露出的都是即将播放的视频封面
            SourceVideoPlayerSession.previewFeed(offset)
            feedScope.launch {
                animate(
                    initialValue = feedOffsetDp,
                    targetValue = -offset * screenHeightDp.toFloat(),
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                ) { value, _ -> feedOffsetDp = value }
                if (SourceVideoPlayerSession.feedSwitch(offset)) {
                    feedLoading = true
                } else {
                    feedLoading = false
                    feedSwitchDir = 0
                    animate(
                        initialValue = feedOffsetDp,
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                    ) { value, _ -> feedOffsetDp = value }
                }
            }
        }
        // 新视频加载完成后 bookUrl 才更新；封面早已铺满整屏，原地接播即可，
        // 不再做第二次「从底部滑入」动画，避免与滑出动画重复显得割裂
        LaunchedEffect(state.bookUrl) {
            val dir = feedSwitchDir
            if (dir != 0) {
                feedSwitchDir = 0
                feedLoading = false
                feedOffsetDp = 0f
            }
        }
        // 切换失败（加载出错）时把画面滑回来，避免整块停在屏幕外看不见错误提示
        LaunchedEffect(state.error, state.fallbackFailed) {
            if ((state.error != null || state.fallbackFailed != null) && feedOffsetDp != 0f) {
                feedLoading = false
                feedSwitchDir = 0
                animate(
                    initialValue = feedOffsetDp,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                ) { value, _ -> feedOffsetDp = value }
            }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // 播放器区域
            Column(modifier = Modifier.fillMaxSize()) {
            // 播放器：全屏 = 占满；首页模式 = 压缩到顶部 240dp（实例不变）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (homeMode) {
                            Modifier.height(240.dp)
                        } else {
                            Modifier.weight(1f)
                        }
                    )
                    // 纯黑底（根 Box 已铺黑），加载/衔接无黑底闪动由播放器内部封面接管
                    .offset(y = feedOffsetDp.dp)
            ) {
                when {
                    // 解析/回退失败：不再弹模态错误框。
                    // 早先它一出现就吃掉全部触摸，点「关闭」还会直接 finish()，
                    // 于是"这一部看不了就只能退出"，连竖滑切下一个视频都做不到。
                    // 现在这里铺封面（竖滑露出的是封面而不是黑屏），
                    // 错误由外层的 ErrorNotice 以提示条呈现，页面不退出，
                    // 有列表时右侧 20% 照旧能竖滑切下一个。
                    (state.error != null || state.fallbackFailed != null) && !infiniteSwipe -> CoverBackdrop(
                        coverUrl = state.coverUrl,
                        sourceOrigin = state.coverOrigin,
                        initialDrawable = coverDrawable,
                        modifier = Modifier.fillMaxSize(),
                    )

                    state.episodes.isNotEmpty() -> key(state.bookUrl) {
                        // key：切换列表视频（右侧竖滑）时整组重建，剧集与进度按新视频重置
                        FullscreenVideoPlayer(
                            videos = state.episodes,
                            initialIndex = state.startIndex,
                            initialPositionMs = state.startPositionMs,
                            onExit = {
                                if (homeMode) {
                                    homeMode = false
                                } else {
                                    // 退出前把正在播的这集收尾落盘（不切集直接退出也存下）
                                    SourceVideoPlayerSession.finalizeCurrentEpisode()
                                    // 退出前先把当前视频的播放进度落库（趁全局状态还是当前视频）
                                    SourceVideoPlayerSession.flushCurrentProgress()
                                    // 退出播放器即清空切换列表，
                                    // 避免下次从其它分类打开同一视频时沿用旧分类的列表
                                    SourceVideoPlayerSession.clearFeed()
                                    finish()
                                }
                            },
                            headers = state.headers,
                            seriesTitle = state.bookName,
                            onResolveEpisode = { idx, prefetch ->
                                SourceVideoPlayerSession.resolveEpisode(idx, prefetch)
                            },
                            // 取用「下一个视频」的预取结果（含加密源的提前嗅探）。
                            // 非挂起：没就绪就返回 null，由会话层在后台继续解析/嗅探，下一轮再来取。
                            onPrefetchNextVideo = SourceVideoPlayerSession::peekNextVideo,
                            onProgressChange = SourceVideoPlayerSession::saveProgress,
                            onOpenHome = { homeMode = true },
                            onFeedSwitch = if (state.feedEnabled) switchFeed else null,
                            onFeedDrag = if (state.feedEnabled) onFeedDrag else null,
                            onFeedCancel = if (state.feedEnabled) onFeedCancel else null,
                            coverUrl = state.coverUrl,
                            sourceOrigin = state.coverOrigin,
                            bookUrl = state.bookUrl,
                            coverDrawable = coverDrawable,
                            compactMode = homeMode,
                            playOrderState = playOrderState,
                            currentPageState = currentPageState,
                            reverseOrder = state.reverseOrder,
                            onReversePersist = { reversed ->
                                SourceVideoPlayerSession.setReverseOrder(state.bookUrl, reversed)
                            },
                            onAddShelf = { SourceVideoPlayerSession.addToShelf() },
                            onPlaybackError = { err ->
                                SourceVideoPlayerSession.setPlaybackError(err.localizedMessage ?: "播放出错")
                            },
                            activePlayer = activePlayer,
                        )
                    }

                    // 白名单书源：没有剧集可渲染，正在等书源自己拉起播放器；
                    // 解析中（剧集还没就绪）也走这里——铺上封面，
                    // 这样解析途中竖滑切走时露出的是封面，而不是一片黑
                    else -> CoverBackdrop(
                        coverUrl = state.coverUrl,
                        sourceOrigin = state.coverOrigin,
                        initialDrawable = coverDrawable,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (homeMode && state.episodes.isNotEmpty()) {
                HomePanel(
                    state = state,
                    playOrder = playOrder,
                    currentPage = currentPageState.value,
                    // 倒序是用户对「网站集数排反了」的修正，下载弹窗里的「第N集」
                    // 也要按修正后的顺序编号，与播放器顶栏/目录面板一致
                    episodeOrder = if (state.reverseOrder) playOrder else null,
                    onExpand = { homeMode = false },
                )
            }
            }

            // 播放器没渲染出来时（解析中、或解析失败），播放器内部那套右侧竖滑手势
            // 根本不存在，这里补一层，于是：
            //   · 解析要几秒也能中途滑走，不必等它解析完；
            //   · 解析失败也不会卡在这一部，照样能滑到下一个视频。
            // 手势区在下、提示条在上：提示条只吃掉自己几个按钮的点击，不拦其它区域。
            val playerRendered = state.episodes.isNotEmpty()
                    && state.error == null
                    && state.fallbackFailed == null
            if (!playerRendered && !infiniteSwipe) {
                FeedSwipeZone(
                    enabled = state.feedEnabled,
                    onDrag = onFeedDrag,
                    onCancel = onFeedCancel,
                    onSwitch = switchFeed,
                )
            }

            // 解析/回退失败：只给提示，不弹窗、不退出
            if ((state.error != null || state.fallbackFailed != null) && !infiniteSwipe) {
                ErrorNotice(
                    message = state.error ?: state.fallbackFailed.orEmpty(),
                    canSwitch = state.feedEnabled,
                    onRetry = {
                        SourceVideoPlayerSession.clearError()
                        SourceVideoPlayerSession.load(state.bookUrl, state.currentIndex)
                    },
                    onExit = { finish() },
                )
            }

            // 加载/解析/切换中转圈：根 Box 已铺黑底，无黑屏闪动
            if (state.loading || feedLoading || state.fallbackIndex != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }

    /**
     * 构建视频播放的媒体通知。
     *
     * 用自定义 RemoteViews 把布局收成两行：左侧剧名+当前集名两行文字，右侧一个与
     * 这两行等高的方形播放/暂停按钮（点按经动态广播转发到 MediaSession 控制真正的播放器），
     * 避免默认 MediaStyle 把按钮单独排成第三行、以及之前 setSubText 多出来的一行。
     * 仍保留 MediaStyle 以继续支持锁屏媒体控制。
     */
    private fun buildVideoNotification(
        playing: Boolean,
        title: String,
        subtitle: String,
        sessionToken: MediaSessionCompat.Token,
        bookUrl: String = "",
        homeMode: Boolean = false,
        startIndex: Int = 0,
    ): NotificationCompat.Builder {
        val toggleIntent = Intent(ACTION_TOGGLE_VIDEO_PLAYBACK).setPackage(packageName)
        val togglePi = PendingIntent.getBroadcast(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 点击通知栏：回到正在播放的视频，复用已有会话（load 内部命中"同一本书且剧集已就绪"
        // 分支，不会重新拉起/解析视频）。SINGLE_TOP 让 Activity 已在前台时直接复用、不重建。
        val contentIntent = createIntent(this, bookUrl, startIndex, homeMode).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPi = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val views = RemoteViews(packageName, R.layout.notification_video).apply {
            setTextViewText(R.id.tv_title, title.ifBlank { getString(R.string.video) })
            setTextViewText(R.id.tv_subtitle, subtitle)
            setImageViewResource(
                R.id.btn_toggle,
                if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp
            )
            setOnClickPendingIntent(R.id.btn_toggle, togglePi)
        }

        return NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setContentTitle(title.ifBlank { getString(R.string.video) })
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPi)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .addAction(
                if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_24dp,
                getString(if (playing) R.string.pause else R.string.resume),
                togglePi
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(0)
            )
    }

    companion object {

        /** 通知栏播放/暂停按钮广播 action（仅本应用内部动态接收） */
        const val ACTION_TOGGLE_VIDEO_PLAYBACK = "io.legado.app.action.TOGGLE_VIDEO_PLAYBACK"

        /**
         * 播放出错时是否"自动跳过"：用户在弹窗里点过「自动跳过」后为 true，
         * 之后本次运行内再遇到出错的视频就不再打扰，直接切下一个。
         *
         * **刻意只放在内存里**（static 字段）：重启 APP 即恢复成每次都询问。
         * 视频出错常常是临时的（CDN 抽风、限流、token 过期），要是把这个选择
         * 持久化下来，用户很可能忘了自己点过什么，以后视频悄悄被跳过还不知道原因。
         */
        @Volatile
        private var autoSkipFailedVideo = false

        fun isAutoSkipFailedVideo(): Boolean = autoSkipFailedVideo

        fun setAutoSkipFailedVideo(on: Boolean) {
            autoSkipFailedVideo = on
        }

        fun createIntent(
            context: Context,
            bookUrl: String,
            startIndex: Int = 0,
            homeMode: Boolean = false,
            infiniteSwipe: Boolean = false,
        ): Intent = Intent(context, SourceVideoPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("bookUrl", bookUrl)
            putExtra("startIndex", startIndex)
            putExtra("homeMode", homeMode)
            putExtra("infiniteSwipe", infiniteSwipe)
        }
    }
}

/** 首页模式下方区域：标题行（剧名/边看边存/下载/放大）+ 选集方块网格 */
@Composable
private fun HomePanel(
    state: SourceVideoPlayerUiState,
    playOrder: List<Int>,
    /** 分页器当前页码——高亮的唯一数据源，与选集面板同源 */
    currentPage: Int,
    /** 倒序修正后的显示顺序；null = 未倒序，按原始编号显示 */
    episodeOrder: List<Int>?,
    onExpand: () -> Unit,
) {
    val episodes = state.episodes
    val context = LocalContext.current
    var showDownload by remember { mutableStateOf(false) }
    // 打开弹窗时：已有下载任务则直接进下载进度页，否则进选集勾选页
    var downloadShowManage by remember { mutableStateOf(false) }
    var watchSave by remember { mutableStateOf(VideoDownloader.isWatchSaveEnabled()) }
    // 当前正在播的那一集的**原始下标**：由分页页码经 playOrder 映射而来。
    // 不用 state.currentIndex —— 那个字段同时被「用户选中」和 saveProgress 的
    // 「正在播放」两拨人写，切集过程中会被抢回旧集，高亮要等好几秒才变。
    val currentOrigIndex = playOrder.getOrNull(currentPage)
        ?: state.currentIndex.coerceIn(0, episodes.lastIndex.coerceAtLeast(0))

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {
        // 标题行：剧名 + 集数 + 边看边存 + 下载 + 放大
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                state.bookName,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "共 ${episodes.size} 集",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.size(8.dp))
            TextButton(onClick = {
                watchSave = !watchSave
                VideoDownloader.setWatchSaveEnabled(watchSave)
                // 刚打开：把正在播的这一集也存下来，不必等切到下一集才生效
                if (watchSave) {
                                        // 用高亮那一集（与方块显示一致），而不是 state.currentIndex
                                        SourceVideoPlayerSession.onEpisodeActivated(currentOrigIndex)
                                    }
            }) {
                Text(
                    if (watchSave) "边看边存·开" else "边看边存·关",
                    color = if (watchSave) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.White.copy(alpha = 0.7f)
                    },
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = {
                downloadShowManage = VideoDownloader.states.value.isNotEmpty()
                showDownload = true
            }) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = "下载选集",
                    tint = Color.White
                )
            }
            // 放大：恢复全屏播放
            IconButton(onClick = onExpand) {
                Icon(
                    Icons.Filled.Fullscreen,
                    contentDescription = "放大播放",
                    tint = Color.White
                )
            }
        }

        // 选集方块网格：按 playOrder（显示顺序，倒序/随机后整体反转）排列。
        // 高亮、跳转仍用原始下标（进度记录基于它）；标签「第 N 集」在倒序时
        // 改按修正后的顺序编号，与播放器顶栏/标题栏/目录面板一致——
        // 否则顶栏说「第1集」、这里同一个视频却标着「第n集」，用户会当又错了。
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 86.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(playOrder) { pos, origIndex ->
                val ep = episodes.getOrNull(origIndex) ?: return@itemsIndexed
                val no = episodeOrder?.indexOf(origIndex)
                    ?.takeIf { it >= 0 }?.plus(1) ?: (origIndex + 1)
                EpisodeItem(
                    label = if (ep.name.length <= 7) ep.name else "第${no}集",
                    selected = origIndex == currentOrigIndex,
                    ready = ep.uri != Uri.EMPTY,
                    onClick = { SourceVideoPlayerSession.selectEpisode(origIndex) }
                )
            }
        }
    }

    if (showDownload) {
        DownloadDialog(
            episodes = episodes,
            bookName = state.bookName,
            showManageFirst = downloadShowManage,
            episodeOrder = if (state.reverseOrder) playOrder else null,
            onDismiss = { showDownload = false }
        )
    }
}

/**
 * 下载管理界面。
 *
 * - 顶部是**当前视频名**，点开可切换到「已缓存视频列表」：每部视频都能勾选、可全选、
 *   可批量删除（连目录带记录一起删），选中某部就把下面的列表切到它；
 * - 下面是双页签：选集勾选下载（仅当前视频，别的视频要新增下载得先进它的播放页）
 *   / 下载进度（每条带复选框，可全选后批量删除）。
 *
 * @param standalone true = 作为独立页面铺满整屏（视频发现页右上角入口进来的那种），
 *   没有"当前视频"这一概念，默认停在视频列表上；false = 播放页里的弹窗。
 */
@Composable
internal fun DownloadDialog(
    episodes: List<LocalVideo>,
    bookName: String,
    showManageFirst: Boolean,
    /** 倒序修正后的显示顺序（原始下标 → 显示集号的映射来源）；null = 未倒序，按原始编号 */
    episodeOrder: List<Int>? = null,
    standalone: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dlStates by VideoDownloader.states.collectAsStateWithLifecycle()
    val groups by VideoDownloader.videoGroups.collectAsStateWithLifecycle()
    // 倒序标记存在 SharedPreferences 里，不是 Compose 状态：播放页点了倒序再返回
    // 本界面时，没有信号触发重组，进度列表就会停在旧顺序。收集这个版本号，
    // 倒序一变（或从后台带新值回来）就重组，viewReversed 重新读取，列表立刻跟着翻。
    val reverseVersion by SourceVideoPlayerSession.reverseVersion.collectAsStateWithLifecycle()
    // null = 当前正在播的这一部；非空 = 切换过去的那部视频的保存目录名
    var selectedDir by remember { mutableStateOf<String?>(null) }
    var selectorOpen by remember { mutableStateOf(standalone) }
    var tab by remember { mutableStateOf(if (showManageFirst || standalone) 1 else 0) }
    var menuOpen by remember { mutableStateOf(false) }
    // 必须用快照状态列表：普通 BooleanArray 的修改不会触发重组，勾选框不刷新
    val checked = remember(episodes) {
        mutableStateListOf(*Array(episodes.size) { false })
    }
    // 进度条目的勾选 / 视频列表的勾选 / 其他缓存的勾选
    val itemChecked = remember { mutableStateListOf<String>() }
    val videoChecked = remember { mutableStateListOf<String>() }
    val orphanChecked = remember { mutableStateListOf<String>() }

    // 批量删除的二次确认：first = 确认文案，second = 确认后真正执行的删除动作。
    // 只拦多选批量删除（误触损失大）；单条删除保持直接执行。
    var batchDeleteConfirm by remember {
        mutableStateOf<Pair<String, (() -> Unit)>?>(null)
    }

    // 「其他缓存」：无下载记录的孤儿缓存。两种来源：
    // - 记录丢失（tasks.json 损坏/被清），分片与密钥都还在；
    // - 换源重下每集命名不同（"1、2" vs "第一集"），旧源下的目录对不上任何记录。
    // 按钮默认灰色（没有孤儿可看），点一下列出这些缓存，再点一下切回下载记录。
    var showOrphans by remember { mutableStateOf(false) }
    var orphans by remember { mutableStateOf<List<VideoDownloader.OrphanCache>>(emptyList()) }
    // 合并/删除孤儿后手动触发一次重扫（这些操作不改下载记录，dlStates 不会变）
    var orphanTick by remember { mutableIntStateOf(0) }
    // 非空时正在全屏播放某个孤儿缓存
    // 「其他缓存」的播放会话：整份列表都交给时光播放器（上下滑可切换全集），
    // 与正常下载的视频一致。未解析的集 uri 为空，滑到时由 onResolveEpisode 现场解析。
    var playingOrphanVideos by remember { mutableStateOf<List<LocalVideo>?>(null) }
    var playingOrphanCaches by remember { mutableStateOf<List<VideoDownloader.OrphanCache>?>(null) }
    var playingOrphanStart by remember { mutableStateOf(0) }
    val orphanScope = rememberCoroutineScope()

    // —— 其他缓存手动排序 ——
    // 长按条目上下拖动，或用条目菜单里的 置顶 / 上移一位 / 下移一位；
    // 改完把新顺序写进视频文件夹里的 .order.json（随文件夹走，重装/换设备都在）。
    // 只作用于「其他缓存」列表；有记录的分集按目录集序号排，两套互不相干。
    val orphanItemHeights = remember { mutableStateMapOf<Int, Int>() }
    var orphanDragIndex by remember { mutableStateOf<Int?>(null) }
    var orphanDragOffset by remember { mutableFloatStateOf(0f) }
    val orphanSpacingPx = with(LocalDensity.current) { 10.dp.toPx() }

    // 主题色：独立页面（视频发现页右上角进来的下载管理）跟随应用主题
    // （应用主题设为"自动"时即跟随系统深浅色 + 应用主题色）；
    // 播放页里的弹窗叠在黑底播放器上，保持黑底白字不变。
    val dark = if (standalone) ThemeConfig.isDarkTheme() else true
    val contentColor = if (dark) Color.White else MaterialTheme.colorScheme.onBackground
    // 半透明元素（进度轨道等）也用同一个色：深色主题下是白，浅色主题下是深色
    val pageBg = if (dark) Color(0xFF101010) else MaterialTheme.colorScheme.background
    val dialogBg = if (dark) Color(0xFF1C1C1E) else MaterialTheme.colorScheme.surface

    // 下载管理页（standalone）：已从「全部视频」进入某一部视频的详情时，
    // 系统返回键先退回视频列表，而不是直接退出整个页面——
    // 用户点进一部视频看完分集，按返回的预期是回去挑下一部，
    // 原先这里没拦，返回键直接把页面关了，只能重新进来。
    // 正在看视频列表时（selectorOpen=true）不拦，返回键照常退出页面。
    if (standalone && !selectorOpen) {
        BackHandler {
            // 正在全屏播孤儿缓存时，返回键先退出播放，再走原有的退回视频列表
            if (playingOrphanVideos != null) {
                playingOrphanVideos = null
                playingOrphanCaches = null
                return@BackHandler
            }
            selectedDir = null
            selectorOpen = true
            tab = 1
            itemChecked.clear()
        }
    }

    LaunchedEffect(Unit) { VideoDownloader.refreshVideoGroups() }

    val currentBook = if (standalone) null else SourceVideoPlayerSession.getBook()
    val isCurrentVideo = !standalone && selectedDir == null
    // 独立页面且还没选视频时没有"当前目录"，列表为空，靠视频列表去挑一部
    val currentDir = selectedDir
        ?: currentBook?.let { VideoDownloader.dirNameOf(it) }
        ?: if (standalone) "" else bookName.replace(Regex("[\\\\/:*?\"<>|]"), "").ifBlank { "未命名" }
    val currentName = when {
        standalone && selectedDir == null -> "全部视频"
        isCurrentVideo -> bookName.ifBlank { currentDir }
        else -> groups.firstOrNull { it.dirName == selectedDir }?.name ?: selectedDir.orEmpty()
    }
    // 当前视图对应的视频 bookUrl（用来查它自己的倒序标记）：
    // - 播放页弹窗的「当前视频」→ 会话的 book；
    // - 选中了别的视频（下载管理页 / 播放页里切换）→ 视频列表 group 的 key（bookUrl）。
    // 不能套用播放会话的 playOrder：独立页面没有会话，而且查看别的视频时
    // 那是另一部剧的顺序，套上来就是错的。
    val viewBookUrl = when {
        !standalone && selectedDir == null -> SourceVideoPlayerSession.getBook()?.bookUrl
        else -> groups.firstOrNull { it.dirName == selectedDir }?.key
            ?.takeUnless { it.startsWith("dir:") }
    }
    // 该视频是否设过倒序修正。是的话分集按修正后的顺序（原始集号降序）展示——
    // 「倒序」的本意就是原始第 n 集其实是第 1 集，所以降序即修正序。
    // 以 reverseVersion 为 key：标记变化时强制重读，列表即时刷新
    val viewReversed = remember(viewBookUrl, reverseVersion) {
        viewBookUrl?.let { SourceVideoPlayerSession.isReverseOrder(it) } == true
    }

    // 只显示当前所选视频的条目
    // 永远按目录里的集数顺序展示：先下第 3 集再下第 1 集时，第 1 集也要排在前面，
    // 而不是按下单先后堆在后面（statesMap 是插入序，这里显式按集序号排一次）。
    // 该视频设了倒序则反过来——修正后的第 1 集（原始最后一集）排在最上面。
    val shownStates = dlStates
        .filter { it.dirName == currentDir }
        .sortedWith(
            if (viewReversed) compareByDescending<VideoDownloader.TaskState> { it.task.index }
            else compareBy { it.task.index }
        )

    // 已下载完的一集：点播放按钮直接播这一集。
    // - 播放页里（当前这部剧）：切集并关掉弹窗，播放器瞬间换成本地文件；
    // - 别的视频 / 独立页面：拉起播放页直接播这一集。
    val onPlayEpisode: (VideoDownloader.TaskState) -> Unit = { st ->
        val task = st.task
        val curBook = SourceVideoPlayerSession.getBook()
        if (!standalone && curBook != null && curBook.bookUrl == task.book.bookUrl) {
            onDismiss()
            SourceVideoPlayerSession.selectEpisode(task.index)
        } else {
            // 把「已下载的视频」列表交给播放器：进去后右侧 20% 竖滑即可在
            // 这些已下载视频之间切换（与从发现页/搜索页进入时的列表切换同一套手势）。
            // 起始集取该视频第一集已下载的集，避免切过去后从没下载的集开始播。
            VideoFeedStore.put(
                provider = { VideoDownloader.downloadedBookUrls() },
                currentBookUrl = task.book.bookUrl,
                startIndexOf = { url -> VideoDownloader.firstCompletedIndex(url) ?: 0 },
            )
            context.startActivity(
                SourceVideoPlayerActivity.createIntent(context, task.book.bookUrl, task.index)
            )
            if (!standalone) onDismiss()
        }
    }

    // 切到别的视频后没有"选集下载"可勾（目录要联网拉），强制留在进度页
    LaunchedEffect(selectedDir) {
        if (selectedDir != null) tab = 1
    }

    // 孤儿缓存探测：进入某部视频 / 记录变化 / 合并删除后都重扫一遍。
    // 下载记录的恢复（restoreIfNeeded）是异步的，恢复完 publishStates 会更新 dlStates，
    // 这里的 LaunchedEffect 随之重算，误报（把有记录的当成孤儿）只是启动后的一瞬。
    LaunchedEffect(currentDir, dlStates, tab, selectorOpen, orphanTick) {
        if (tab == 1 && !selectorOpen && currentDir.isNotBlank()) {
            VideoDownloader.restoreIfNeeded()
            orphans = withContext(Dispatchers.IO) {
                VideoDownloader.orphanCaches(currentDir)
            }
        }
    }

    // 播放无记录的缓存：把整份「其他缓存」列表都交给时光播放器（与正常下载的视频一致，
    // 上下滑切换全集）；点击的这一集先解析出本地文件直接起播，其余集滑到时再现场解析
    val onPlayOrphan: (VideoDownloader.OrphanCache) -> Unit = { o ->
        orphanScope.launch {
            val snapshot = orphans.toList()
            val startIdx = snapshot.indexOfFirst { it.title == o.title }.coerceAtLeast(0)
            val f = withContext(Dispatchers.IO) {
                VideoDownloader.findOrphanFile(o.dirName, o.title)
            }
            if (f == null || f.length() <= 0L) {
                context.toastOnUi("缓存文件不存在或已损坏")
                return@launch
            }
            playingOrphanCaches = snapshot
            playingOrphanStart = startIdx
            playingOrphanVideos = snapshot.map { c ->
                if (c.title == o.title) {
                    LocalVideo(Uri.fromFile(f), c.title, f.absolutePath, f.length(), 0L)
                } else {
                    LocalVideo(Uri.EMPTY, c.title, "", 0L, 0L)
                }
            }
        }
    }

    /** 播放器滑到未解析的集（或预取下一集）时现场解析本地文件；回填列表后自动起播 */
    fun resolveOrphanAt(index: Int) {
        val caches = playingOrphanCaches ?: return
        val videos = playingOrphanVideos ?: return
        val c = caches.getOrNull(index) ?: return
        if (videos.getOrNull(index)?.uri != Uri.EMPTY) return
        orphanScope.launch {
            val f = withContext(Dispatchers.IO) {
                VideoDownloader.findOrphanFile(c.dirName, c.title)
            }
            if (f == null || f.length() <= 0L) return@launch
            if (playingOrphanCaches !== caches) return@launch // 播放会话已换，丢弃结果
            playingOrphanVideos = (playingOrphanVideos ?: videos).toMutableList().also {
                it[index] = LocalVideo(Uri.fromFile(f), c.title, f.absolutePath, f.length(), 0L)
            }
        }
    }

    /** 手动调整「其他缓存」顺序：界面立即生效，并把新顺序写进视频文件夹的 .order.json */
    fun moveOrphan(from: Int, to: Int) {
        if (from == to || from !in orphans.indices) return
        val list = orphans.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        orphans = list
        VideoDownloader.saveOrphanOrderAsync(currentDir, list.map { it.title })
    }

    // 弹窗与独立页面共用同一份内容，只是外壳不同
    val body: @Composable () -> Unit = {
        // 批量删除二次确认框
        val confirmReq = batchDeleteConfirm
        if (confirmReq != null) {
            AlertDialog(
                onDismissRequest = { batchDeleteConfirm = null },
                title = { Text("确认删除") },
                text = { Text(confirmReq.first) },
                confirmButton = {
                    TextButton(onClick = {
                        batchDeleteConfirm = null
                        confirmReq.second()
                    }) {
                        Text("删除", color = Color(0xFFFF6B6B))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { batchDeleteConfirm = null }) {
                        Text("取消")
                    }
                }
            )
        }
        val orphanVideos = playingOrphanVideos
        if (orphanVideos != null) {
            // 全屏播「其他缓存」：与正片同一个时光播放器（本地清单 / 进度 / 手势全都在），
            // 上下滑可在全集之间切换；未解析的集滑到时由 onResolveEpisode 现场补齐后自动起播。
            // 顺序/页码状态以进入播放时的列表快照为 key：解析回填不会重置分页位置
            val orphanOrder = remember(playingOrphanCaches) {
                mutableStateOf(orphanVideos.indices.toList())
            }
            val orphanPage = remember(playingOrphanCaches) {
                mutableStateOf(playingOrphanStart)
            }
            FullscreenVideoPlayer(
                videos = orphanVideos,
                initialIndex = playingOrphanStart,
                seriesTitle = currentName,
                onExit = {
                    playingOrphanVideos = null
                    playingOrphanCaches = null
                },
                playOrderState = orphanOrder,
                currentPageState = orphanPage,
                onResolveEpisode = { index, _ -> resolveOrphanAt(index) },
            )
        } else {
        Column(modifier = Modifier.padding(16.dp)) {
                // 顶部：视频名 + 切换指示 + 三点菜单
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selectorOpen = !selectorOpen }) {
                        Text(
                            if (currentName.length > 14) currentName.take(14) + "…" else currentName,
                            color = contentColor,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                        Text(
                            if (selectorOpen) " ▲" else " ▼",
                            color = contentColor.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        )
                    }
                    if (!isCurrentVideo) {
                        TextButton(onClick = {
                            selectedDir = null
                            // 独立页面里没有"当前视频"，回到未选中状态即视频列表
                            if (standalone) selectorOpen = true
                            tab = 1
                        }) {
                            Text(
                                if (standalone) "全部视频" else "回到当前",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    // 三点：作用于**当前所选视频**的任务。
                    // 重启 APP 后一堆"已暂停/失败"的记录，不用一条条点，这里一把梭
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "批量操作",
                                tint = contentColor
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部开始") },
                                onClick = {
                                    menuOpen = false
                                    val n = VideoDownloader.resumeAll(currentDir)
                                    context.toastOnUi(
                                        if (n > 0) "已开始 $n 个任务" else "没有待继续的任务"
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("全部停止") },
                                onClick = {
                                    menuOpen = false
                                    val n = VideoDownloader.pauseAll(currentDir)
                                    context.toastOnUi(
                                        if (n > 0) "已暂停 $n 个任务" else "没有进行中的任务"
                                    )
                                }
                            )
                            if (!selectorOpen && tab == 1) {
                                DropdownMenuItem(
                                    text = { Text("全选") },
                                    onClick = {
                                        menuOpen = false
                                        itemChecked.clear()
                                        itemChecked.addAll(shownStates.map { it.key })
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("取消全选") },
                                    onClick = {
                                        menuOpen = false
                                        itemChecked.clear()
                                    }
                                )
                                if (itemChecked.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("合并为MP4") },
                                        onClick = {
                                            menuOpen = false
                                            val tasks = shownStates
                                                .filter { it.key in itemChecked }
                                                .map { it.task }
                                            tasks.forEach { VideoDownloader.mergeToMp4(it) }
                                            context.toastOnUi("已加入合并队列：${tasks.size} 个")
                                        }
                                    )
                                }
                            }
                            if (!selectorOpen && tab == 1 && showOrphans) {
                                DropdownMenuItem(
                                    text = { Text("恢复默认排序") },
                                    onClick = {
                                        menuOpen = false
                                        VideoDownloader.clearOrphanOrderAsync(currentDir)
                                        orphanTick++
                                    }
                                )
                            }
                            if (!selectorOpen && tab == 0) {
                                DropdownMenuItem(
                                    text = { Text("全选") },
                                    onClick = {
                                        menuOpen = false
                                        checked.indices.forEach { checked[it] = true }
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("取消全选") },
                                    onClick = {
                                        menuOpen = false
                                        checked.indices.forEach { checked[it] = false }
                                    }
                                )
                            }
                        }
                    }
                }

                if (selectorOpen) {
                    // 已缓存视频列表：每部视频可勾选、可全选批量删，点名字切过去管理
                    Text(
                        "已缓存视频（${groups.size}）",
                        color = contentColor.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            videoChecked.clear()
                            videoChecked.addAll(groups.map { it.key })
                        }) {
                            Text("全选", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                        }
                        TextButton(onClick = { videoChecked.clear() }) {
                            Text("取消", color = contentColor.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (videoChecked.isNotEmpty()) {
                            TextButton(onClick = {
                                val keys = videoChecked.toSet()
                                val names = groups.filter { it.key in keys }.joinToString("、") { it.name }
                                batchDeleteConfirm =
                                    "将删除 ${keys.size} 部视频（$names）的全部下载记录与缓存文件，确定？" to {
                                        val n = VideoDownloader.deleteVideos(keys)
                                        videoChecked.clear()
                                        context.toastOnUi("已删除 $n 部视频及其缓存文件")
                                    }
                            }) {
                                Text(
                                    "删除选中(${videoChecked.size})",
                                    color = Color(0xFFFF6B6B),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                    if (groups.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "还没有缓存任何视频",
                                color = contentColor.copy(alpha = 0.5f),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(groups.size) { i ->
                                val g = groups[i]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedDir = g.dirName
                                            selectorOpen = false
                                            tab = 1
                                        }
                                ) {
                                    Checkbox(
                                        checked = videoChecked.contains(g.key),
                                        onCheckedChange = { on ->
                                            if (on) videoChecked.add(g.key)
                                            else videoChecked.remove(g.key)
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            g.name,
                                            color = contentColor.copy(alpha = 0.9f),
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${g.completedCount}/${g.episodeCount} 集 · ${formatSize(g.sizeBytes)}",
                                            color = contentColor.copy(alpha = 0.5f),
                                            fontSize = 11.sp
                                        )
                                    }
                                    TextButton(onClick = {
                                        VideoDownloader.deleteVideos(setOf(g.key))
                                        context.toastOnUi("已删除《${g.name}》及其缓存文件")
                                    }) {
                                        Text("删除", color = Color(0xFFFF6B6B), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectorOpen = false }) {
                            Text("收起", color = contentColor)
                        }
                    }
                } else {
                    // 页签行：别的视频没有"选集下载"（新增要进它自己的播放页）
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCurrentVideo) {
                            TextButton(onClick = { tab = 0 }) {
                                Text(
                                    "选集下载",
                                    color = if (tab == 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        contentColor.copy(alpha = 0.6f)
                                    }
                                )
                            }
                        }
                        TextButton(onClick = { tab = 1 }) {
                            Text(
                                "下载进度（${shownStates.size}）",
                                color = if (tab == 1) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    contentColor.copy(alpha = 0.6f)
                                }
                            )
                        }
                        // 「其他缓存」：默认灰色（当前视频目录下没有无记录的缓存）；
                        // 有点击后列出孤儿缓存，再点一下切回下载记录
                        if (tab == 1 && currentDir.isNotBlank()) {
                            TextButton(
                                onClick = { showOrphans = !showOrphans },
                                enabled = orphans.isNotEmpty() || showOrphans,
                            ) {
                                Text(
                                    when {
                                        showOrphans -> "返回下载记录"
                                        orphans.isEmpty() -> "其他缓存"
                                        else -> "其他缓存(${orphans.size})"
                                    },
                                    color = when {
                                        showOrphans -> MaterialTheme.colorScheme.primary
                                        orphans.isEmpty() -> contentColor.copy(alpha = 0.3f)
                                        else -> contentColor.copy(alpha = 0.75f)
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                when (tab) {
                    0 -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 8.dp)
                        ) {
                            // 勾选列表的排列也跟随倒序：修正后的第 1 集排在最上面，
                            // 与播放顺序/目录面板一致；未倒序按原始顺序。
                            // 勾选与下载任务本身仍用原始下标 i——保存目录与进度记录都基于它
                            val displayOrder = episodeOrder ?: episodes.indices.toList()
                            items(displayOrder.size) { pos ->
                                val i = displayOrder[pos]
                                val ep = episodes.getOrNull(i) ?: return@items
                                // 显示集号按倒序修正后的顺序走（与播放器顶栏/目录面板一致）
                                val no = if (episodeOrder != null) pos + 1 else i + 1
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = checked[i],
                                        onCheckedChange = { checked[i] = it }
                                    )
                                    Text(
                                        "第${no}集 ${ep.name}",
                                        color = contentColor.copy(alpha = 0.9f),
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("取消", color = contentColor)
                            }
                            TextButton(onClick = {
                                val selected = checked.indices.filter { checked[it] }
                                if (selected.isEmpty()) {
                                    context.toastOnUi("未选择任何选集")
                                    return@TextButton
                                }
                                val chapters = SourceVideoPlayerSession.getChapters()
                                val book = SourceVideoPlayerSession.getBook()
                                val source = SourceVideoPlayerSession.getSource()
                                if (chapters.size != episodes.size || book == null || source == null) {
                                    context.toastOnUi("剧集信息尚未就绪，请稍后再试")
                                    return@TextButton
                                }
                                val tasks = selected.mapNotNull { i ->
                                    chapters.getOrNull(i)?.let {
                                        VideoDownloader.Task(book, source, i, it)
                                    }
                                }
                                VideoDownloader.enqueue(tasks)
                                context.toastOnUi(
                                    "已加入下载队列 ${tasks.size} 集\n保存到 Download/保存视频/$bookName"
                                )
                                // 下载在后台进行，切到进度页可实时查看；关闭弹窗也不中断
                                tab = 1
                            }) {
                                Text("开始下载", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    else -> {
                        if (showOrphans) {
                            // 「其他缓存」：只显示没有下载记录的孤儿缓存（可播放 / 可合并 / 可删除）
                            if (orphans.isEmpty()) {
                                Box(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "没有发现其他缓存",
                                        color = contentColor.copy(alpha = 0.5f),
                                        fontSize = 13.sp
                                    )
                                }
                            } else {
                                // 多选删除操作条（与下载进度页同款）：全选 / 取消 / 删除选中（二次确认）
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        orphanChecked.clear()
                                        orphanChecked.addAll(orphans.map { it.title })
                                    }) {
                                        Text(
                                            "全选",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 12.sp
                                        )
                                    }
                                    if (orphanChecked.isNotEmpty()) {
                                        TextButton(onClick = { orphanChecked.clear() }) {
                                            Text(
                                                "取消(${orphanChecked.size})",
                                                color = contentColor.copy(alpha = 0.6f),
                                                fontSize = 12.sp
                                            )
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                        TextButton(onClick = {
                                            val targets = orphans.filter { it.title in orphanChecked }
                                            batchDeleteConfirm =
                                                "将删除选中的 ${targets.size} 个其他缓存（缓存文件夹/成品文件一并删除），确定？" to {
                                                    targets.forEach {
                                                        VideoDownloader.deleteOrphanCache(
                                                            it.dirName, it.title, it.isHls
                                                        )
                                                    }
                                                    orphanChecked.clear()
                                                    orphanTick++
                                                    context.toastOnUi("已删除 ${targets.size} 个其他缓存")
                                                }
                                        }) {
                                            Text(
                                                "删除选中(${orphanChecked.size})",
                                                color = Color(0xFFFF6B6B),
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(top = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(orphans.size) { i ->
                                        val o = orphans[i]
                                        val dragging = orphanDragIndex == i
                                        Box(
                                            modifier = Modifier
                                                .zIndex(if (dragging) 1f else 0f)
                                                .graphicsLayer {
                                                    translationY =
                                                        if (dragging) orphanDragOffset else 0f
                                                }
                                                .onSizeChanged {
                                                    orphanItemHeights[i] = it.height
                                                }
                                                .pointerInput(orphanTick, orphans.size) {
                                                    // 长按拖动排序：越过相邻条目即交换，
                                                    // 松手后把新顺序写进文件夹
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            orphanDragIndex = i
                                                            orphanDragOffset = 0f
                                                        },
                                                        onDragEnd = {
                                                            orphanDragIndex = null
                                                            VideoDownloader.saveOrphanOrderAsync(
                                                                currentDir,
                                                                orphans.map { it.title }
                                                            )
                                                        },
                                                        onDragCancel = { orphanDragIndex = null },
                                                        onDrag = { change, amt ->
                                                            change.consume()
                                                            var idx = orphanDragIndex
                                                                ?: return@detectDragGesturesAfterLongPress
                                                            orphanDragOffset += amt.y
                                                            while (idx < orphans.lastIndex &&
                                                                orphanDragOffset >
                                                                (orphanItemHeights[idx + 1] ?: 60) +
                                                                orphanSpacingPx
                                                            ) {
                                                                val step =
                                                                    (orphanItemHeights[idx + 1] ?: 60) +
                                                                        orphanSpacingPx
                                                                orphans = orphans
                                                                    .toMutableList()
                                                                    .apply {
                                                                        val t = set(idx, get(idx + 1))
                                                                        set(idx + 1, t)
                                                                    }
                                                                orphanDragOffset -= step
                                                                idx++
                                                                orphanDragIndex = idx
                                                            }
                                                            while (idx > 0 &&
                                                                orphanDragOffset <
                                                                -((orphanItemHeights[idx - 1] ?: 60) +
                                                                    orphanSpacingPx)
                                                            ) {
                                                                val step =
                                                                    (orphanItemHeights[idx - 1] ?: 60) +
                                                                        orphanSpacingPx
                                                                orphans = orphans
                                                                    .toMutableList()
                                                                    .apply {
                                                                        val t = set(idx - 1, get(idx))
                                                                        set(idx, t)
                                                                    }
                                                                orphanDragOffset += step
                                                                idx--
                                                                orphanDragIndex = idx
                                                            }
                                                        },
                                                    )
                                                }
                                        ) {
                                            OrphanCacheItem(
                                                o = o,
                                                checked = orphanChecked.contains(o.title),
                                                onCheckedChange = { on ->
                                                    if (on) orphanChecked.add(o.title)
                                                    else orphanChecked.remove(o.title)
                                                },
                                                contentColor = contentColor,
                                                onPlay = { onPlayOrphan(o) },
                                                onPinTop = { moveOrphan(i, 0) },
                                                onMoveUp = { if (i > 0) moveOrphan(i, i - 1) },
                                                onMoveDown = {
                                                    if (i < orphans.lastIndex) moveOrphan(i, i + 1)
                                                },
                                                onChanged = { orphanTick++ },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                        // 勾选操作条：有选中时出现批量删除
                        if (shownStates.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    itemChecked.clear()
                                    itemChecked.addAll(shownStates.map { it.key })
                                }) {
                                    Text(
                                        "全选",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 12.sp
                                    )
                                }
                                if (itemChecked.isNotEmpty()) {
                                    TextButton(onClick = { itemChecked.clear() }) {
                                        Text(
                                            "取消(${itemChecked.size})",
                                            color = contentColor.copy(alpha = 0.6f),
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        val keys = itemChecked.toSet()
                                        batchDeleteConfirm =
                                            "将删除选中的 ${keys.size} 条下载记录及其缓存文件（含成品 MP4），确定？" to {
                                                val n = VideoDownloader.deleteTasks(keys)
                                                itemChecked.clear()
                                                context.toastOnUi("已删除 $n 条（含缓存文件）")
                                            }
                                    }) {
                                        Text(
                                            "删除选中",
                                            color = Color(0xFFFF6B6B),
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                        if (shownStates.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (isCurrentVideo) "暂无下载任务" else "这部视频没有下载记录",
                                    color = contentColor.copy(alpha = 0.5f),
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(shownStates.size) { i ->
                                    val st = shownStates[i]
                                    DownloadStateItem(
                                        st = st,
                                        checked = itemChecked.contains(st.key),
                                        onCheckedChange = { on ->
                                            if (on) itemChecked.add(st.key)
                                            else itemChecked.remove(st.key)
                                        },
                                        onPlay = onPlayEpisode,
                                        contentColor = contentColor,
                                    )
                                }
                            }
                        }
                        }
                    }
                }
                }
            }
        }
    }
    if (standalone) {
        // 独立页面：铺满整屏
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = pageBg
        ) { body() }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
                shape = RoundedCornerShape(16.dp),
                color = dialogBg
            ) { body() }
        }
    }
}

/** 出错视频的待决选择：错误消息 + 出错视频的 bookUrl（确定要跳过后才拿它拉黑） */
private data class ErrorChoice(
    val message: String,
    val bookUrl: String,
)

/**
 * 播放出错后的三选一弹窗。
 *
 * 之前出错就无条件切下一个：用户既不知道为什么被切走，也没法留下来看一眼——
 * 而很多出错只是某个分片临时抽风，重进一次就好了，却被直接跳过去了。
 *
 * - **自动跳过**：本次运行内后续出错的视频都不再询问，直接切（重启 APP 恢复询问）；
 * - **跳过**：只跳过这一个，之后再遇到出错的视频照样弹窗；
 * - **取消**：什么都不做，停在当前的错误界面。
 */
@Composable
private fun PlaybackErrorChoiceDialog(
    videoName: String,
    message: String,
    onAutoSkip: () -> Unit,
    onSkip: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("播放出错", color = Color.White, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                if (videoName.isNotBlank()) {
                    Text(
                        videoName,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    message,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp,
                    maxLines = 4
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "自动跳过＝本次之后不再询问；跳过＝只跳过这一个；取消＝停在当前视频",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onAutoSkip) {
                        Text("自动跳过", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onSkip) {
                        Text("跳过", color = Color.White)
                    }
                    TextButton(onClick = onCancel) {
                        Text("取消", color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

/** 体积格式化：B / KB / MB / GB */
private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.lastIndex) {
        value /= 1024
        i++
    }
    return if (i == 0) "${bytes}B" else "%.1f${units[i]}".format(value)
}

/** 下载进度条目：浅色轨道 + 深色进度条 + 百分比 + 暂停/继续 + 三点菜单（删除已缓存/已生成mp4） */
@Composable
private fun DownloadStateItem(
    st: VideoDownloader.TaskState,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onPlay: (VideoDownloader.TaskState) -> Unit,
    /** 正文色：深色主题（播放页弹窗）为白，浅色主题（跟随系统的下载管理页）为深色 */
    contentColor: Color,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val running = st.status == VideoDownloader.DlStatus.QUEUED ||
        st.status == VideoDownloader.DlStatus.RUNNING
    val completed = st.status == VideoDownloader.DlStatus.COMPLETED
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 勾选后配合上方的"删除选中"做批量删除
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                st.title,
                color = contentColor.copy(alpha = 0.9f),
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            // 浅色轨道 + 深色进度条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(contentColor.copy(alpha = 0.15f))
            ) {
                if (st.progress > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(st.progress / 100f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (st.status == VideoDownloader.DlStatus.COMPLETED) {
                                    Color(0xFF34C759)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(6.dp))
        // 状态/百分比
        Text(
            when (st.status) {
                VideoDownloader.DlStatus.QUEUED -> "等待中"
                VideoDownloader.DlStatus.RUNNING -> "${st.progress.coerceAtLeast(0)}%"
                VideoDownloader.DlStatus.PAUSED -> "已暂停 ${st.progress}%"
                VideoDownloader.DlStatus.COMPLETED -> "完成"
                VideoDownloader.DlStatus.FAILED -> "失败"
                VideoDownloader.DlStatus.COLLECTING ->
                    if (st.progress > 0) "跟随播放 ${st.progress}%" else "跟随播放"
            },
            color = when (st.status) {
                VideoDownloader.DlStatus.FAILED -> Color(0xFFFF6B6B)
                VideoDownloader.DlStatus.COMPLETED -> Color(0xFF34C759)
                else -> contentColor.copy(alpha = 0.6f)
            },
            fontSize = 11.sp
        )
        // 暂停 / 继续 / 播放：已完成的一集该按钮变成"播放"，点一下直接播这一集
        IconButton(onClick = {
            when {
                completed -> onPlay(st)
                running -> VideoDownloader.pause(st.key)
                else -> VideoDownloader.resume(st.key)
            }
        }) {
            Icon(
                if (completed) Icons.Filled.PlayArrow
                else if (running) Icons.Filled.Pause
                else Icons.Filled.PlayArrow,
                contentDescription = if (completed) "播放" else if (running) "暂停" else "继续",
                tint = if (completed) MaterialTheme.colorScheme.primary else contentColor
            )
        }
        // 三点菜单
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = contentColor
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("删除已缓存") },
                    onClick = {
                        VideoDownloader.deleteCache(st)
                        menuOpen = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("删除已生成mp4") },
                    onClick = {
                        VideoDownloader.deleteMp4(st)
                        menuOpen = false
                    }
                )
            }
        }
    }
}

/**
 * 「其他缓存」条目：无下载记录的孤儿缓存。功能与下载进度条目对齐——
 * 右侧播放按钮直接播（mp4 或按分片重建的 local.m3u8），三点菜单里合并为 MP4 / 删除。
 */
@Composable
private fun OrphanCacheItem(
    o: VideoDownloader.OrphanCache,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentColor: Color,
    onPlay: () -> Unit,
    onPinTop: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    /** 合并/删除等改动磁盘的操作完成后回调（界面据此重扫孤儿列表） */
    onChanged: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 勾选后配合上方的"删除选中"做批量删除（带二次确认）
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                o.title,
                color = contentColor.copy(alpha = 0.9f),
                fontSize = 13.sp,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (o.isHls) {
                    "分片缓存 ${o.segDone}/${o.segTotal} 片 · ${formatSize(o.sizeBytes)}"
                } else {
                    "本地视频 · ${formatSize(o.sizeBytes)}"
                },
                color = contentColor.copy(alpha = 0.6f),
                fontSize = 11.sp
            )
        }
        IconButton(onClick = onPlay) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = "播放",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = contentColor
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("置顶") },
                    onClick = { menuOpen = false; onPinTop() }
                )
                DropdownMenuItem(
                    text = { Text("上移一位") },
                    onClick = { menuOpen = false; onMoveUp() }
                )
                DropdownMenuItem(
                    text = { Text("下移一位") },
                    onClick = { menuOpen = false; onMoveDown() }
                )
                if (o.isHls) {
                    DropdownMenuItem(
                        text = { Text("合并为MP4") },
                        onClick = {
                            menuOpen = false
                            VideoDownloader.mergeOrphanToMp4(o.dirName, o.title, onChanged)
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = {
                        menuOpen = false
                        VideoDownloader.deleteOrphanCache(o.dirName, o.title, o.isHls, onChanged)
                    }
                )
            }
        }
    }
}

/**
 * 右侧 20% 竖滑切换区：给「没有播放器可渲染」的状态补上这套手势。
 *
 * 正常播放时该手势由播放器内部提供；但解析失败时 episodes 为空、播放器压根不渲染，
 * 手势也就跟着没了——这正是"失败即只能退出"的根因。
 * 这里按与播放器内部一致的口径（右侧 20%、越过 slop*6 触发、跟手位移）补一层，
 * 让失败状态下照样能滑到列表里的下一个视频。
 */
@Composable
private fun BoxScope.FeedSwipeZone(
    enabled: Boolean,
    onDrag: (Float) -> Unit,
    onCancel: () -> Unit,
    onSwitch: (Int) -> Unit,
) {
    if (!enabled) return
    val viewConfiguration = LocalViewConfiguration.current
    Box(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .fillMaxWidth(0.2f)
            .pointerInput(onSwitch) {
                // 与播放器内部同一阈值：touchSlop * 6
                val thresholdPx = viewConfiguration.touchSlop * 6f
                var total = 0f
                var fired = false
                detectVerticalDragGestures(
                    onDragStart = { total = 0f; fired = false },
                    onDragEnd = { if (!fired && total != 0f) onCancel() },
                    onDragCancel = { if (!fired) onCancel() },
                    onVerticalDrag = { change, dragAmount ->
                        if (fired) return@detectVerticalDragGestures
                        change.consume()
                        total += dragAmount
                        onDrag(total)
                        if (abs(total) > thresholdPx) {
                            fired = true
                            // 与播放器内部一致：上滑（位移为负）看下一个
                            onSwitch(if (total < 0) 1 else -1)
                        }
                    },
                )
            }
    )
}
