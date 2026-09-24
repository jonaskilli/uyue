package io.legado.app.ui.book.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import io.legado.app.ui.timetech.player.EpisodeItem
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        // 播放顺序（显示顺序）：videos 的下标序列，分页第 page 页播 videos[playOrder[page]]。
        // 提升到 Activity 层，让「选集面板」与「主页下方目录」共用同一份顺序，
        // 点「倒序」/切循环模式时两者一起反转（随机播放同理）。按 bookUrl + 集数重置，
        // 切到另一个视频时回到自然序。
        val playOrderState = remember(state.bookUrl, state.episodes.size) {
            mutableStateOf(state.episodes.indices.toList())
        }
        var playOrder by playOrderState

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
        // 播放阶段出错（ExoPlayer 报错：无法播放/播放出错/网址问题等）：只要处在有列表可切的
        // 场景（无限刷、发现页/搜索竖滑等），就自动切到下一个视频，而不是卡在坏链上。
        LaunchedEffect(state.playbackError, state.feedEnabled, infiniteSwipe) {
            val msg = state.playbackError ?: return@LaunchedEffect
            if (!state.feedEnabled) {
                // 单视频没有下一个可切，清掉状态避免反复触发
                SourceVideoPlayerSession.clearError()
                return@LaunchedEffect
            }
            val failedUrl = state.bookUrl
            // 只有无限刷才把坏视频永久拉黑：发现页/搜索页竖滑只是临时看看，
            // 一次偶发 403/超时就把人家永久屏蔽，以后正常浏览再也刷不到（且换网络也回不来）
            if (infiniteSwipe && failedUrl.isNotBlank()) InfiniteSwipeFeed.markFailed(failedUrl)
            consecutiveFails++
            if (consecutiveFails > 10) {
                SourceVideoPlayerSession.clearError()
                SourceVideoPlayerSession.skipFallback()
                toastOnUi(this@SourceVideoPlayerActivity.getString(R.string.infinite_swipe_too_many_failed))
                finish()
            } else {
                // 冷却：避免零点几秒内狂跳
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
                    (state.error != null || state.fallbackFailed != null) && !infiniteSwipe -> ErrorDialog(
                        message = state.error ?: state.fallbackFailed.orEmpty(),
                        onDismiss = {
                            // 关闭错误框时同步清掉会话里的错误，防止残留到下一个视频
                            SourceVideoPlayerSession.clearError()
                            finish()
                        }
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
                            onFeedSwitch = if (state.feedEnabled) {
                                { offset ->
                                    // 先把当前这部沿手指方向整屏滑出（此间露出下一个视频封面，不黑屏），
                                    // 滑出完成后再开始加载下一个视频；新视频就绪后封面已铺满，
                                    // 直接原地接播、不再做第二次滑入动画（避免与滑出动画重复割裂）；
                                    // 若已是列表首尾（无下一个），则滑回原位并复位。
                                    feedSwitchDir = offset
                                    // 立刻用第二个（目标）视频的封面铺底，让滑动全程露出的都是即将播放的视频封面
                                    SourceVideoPlayerSession.previewFeed(offset)
                                    feedScope.launch {
                                        animate(
                                            initialValue = feedOffsetDp,
                                            targetValue = -offset * screenHeightDp.toFloat(),
                                            animationSpec = tween(
                                                durationMillis = 200,
                                                easing = FastOutSlowInEasing,
                                            ),
                                        ) { value, _ -> feedOffsetDp = value }
                                        if (SourceVideoPlayerSession.feedSwitch(offset)) {
                                            feedLoading = true
                                        } else {
                                            feedLoading = false
                                            feedSwitchDir = 0
                                            animate(
                                                initialValue = feedOffsetDp,
                                                targetValue = 0f,
                                                animationSpec = tween(
                                                    durationMillis = 220,
                                                    easing = FastOutSlowInEasing,
                                                ),
                                            ) { value, _ -> feedOffsetDp = value }
                                        }
                                    }
                                }
                            } else {
                                null
                            },
                            onFeedDrag = if (state.feedEnabled) onFeedDrag else null,
                            onFeedCancel = if (state.feedEnabled) onFeedCancel else null,
                            coverUrl = state.coverUrl,
                            sourceOrigin = state.coverOrigin,
                            bookUrl = state.bookUrl,
                            coverDrawable = coverDrawable,
                            compactMode = homeMode,
                            playOrderState = playOrderState,
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

                    // 白名单书源：没有剧集可渲染，正在等书源自己拉起播放器（根封面 + 下方转圈层已覆盖）
                }
            }

            if (homeMode && state.episodes.isNotEmpty()) {
                HomePanel(
                    state = state,
                    playOrder = playOrder,
                    onExpand = { homeMode = false },
                )
            }
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
    onExpand: () -> Unit,
) {
    val episodes = state.episodes
    val context = LocalContext.current
    var showDownload by remember { mutableStateOf(false) }
    // 打开弹窗时：已有下载任务则直接进下载进度页，否则进选集勾选页
    var downloadShowManage by remember { mutableStateOf(false) }
    var watchSave by remember { mutableStateOf(VideoDownloader.isWatchSaveEnabled()) }
    val currentIndex = state.currentIndex.coerceIn(0, (episodes.lastIndex).coerceAtLeast(0))

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
                    SourceVideoPlayerSession.onEpisodeActivated(currentIndex)
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

        // 选集方块网格：按 playOrder（显示顺序，倒序/随机后整体反转）排列，
        // 但标签「第 N 集」、高亮、跳转都用原始下标，因此顺序变了编号与播放进度不变。
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 86.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(playOrder) { _, origIndex ->
                val ep = episodes.getOrNull(origIndex) ?: return@itemsIndexed
                EpisodeItem(
                    label = if (ep.name.length <= 7) ep.name else "第${origIndex + 1}集",
                    selected = origIndex == currentIndex,
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
    standalone: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dlStates by VideoDownloader.states.collectAsStateWithLifecycle()
    val groups by VideoDownloader.videoGroups.collectAsStateWithLifecycle()
    // null = 当前正在播的这一部；非空 = 切换过去的那部视频的保存目录名
    var selectedDir by remember { mutableStateOf<String?>(null) }
    var selectorOpen by remember { mutableStateOf(standalone) }
    var tab by remember { mutableStateOf(if (showManageFirst || standalone) 1 else 0) }
    var menuOpen by remember { mutableStateOf(false) }
    // 必须用快照状态列表：普通 BooleanArray 的修改不会触发重组，勾选框不刷新
    val checked = remember(episodes) {
        mutableStateListOf(*Array(episodes.size) { false })
    }
    // 进度条目的勾选 / 视频列表的勾选
    val itemChecked = remember { mutableStateListOf<String>() }
    val videoChecked = remember { mutableStateListOf<String>() }

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
    // 只显示当前所选视频的条目
    // 永远按目录里的集数顺序展示：先下第 3 集再下第 1 集时，第 1 集也要排在前面，
    // 而不是按下单先后堆在后面（statesMap 是插入序，这里显式按集序号排一次）
    val shownStates = dlStates
        .filter { it.dirName == currentDir }
        .sortedBy { it.task.index }

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

    // 弹窗与独立页面共用同一份内容，只是外壳不同
    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(16.dp)) {
                // 顶部：视频名 + 切换指示 + 三点菜单
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { selectorOpen = !selectorOpen }) {
                        Text(
                            if (currentName.length > 14) currentName.take(14) + "…" else currentName,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 1
                        )
                        Text(
                            if (selectorOpen) " ▲" else " ▼",
                            color = Color.White.copy(alpha = 0.6f),
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
                                tint = Color.White
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
                        color = Color.White.copy(alpha = 0.6f),
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
                            Text("取消", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (videoChecked.isNotEmpty()) {
                            TextButton(onClick = {
                                val n = VideoDownloader.deleteVideos(videoChecked.toSet())
                                videoChecked.clear()
                                context.toastOnUi("已删除 $n 部视频及其缓存文件")
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
                                color = Color.White.copy(alpha = 0.5f),
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
                                            color = Color.White.copy(alpha = 0.9f),
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            "${g.completedCount}/${g.episodeCount} 集 · ${formatSize(g.sizeBytes)}",
                                            color = Color.White.copy(alpha = 0.5f),
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
                            Text("收起", color = Color.White)
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
                                        Color.White.copy(alpha = 0.6f)
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
                                    Color.White.copy(alpha = 0.6f)
                                }
                            )
                        }
                    }
                when (tab) {
                    0 -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 8.dp)
                        ) {
                            listItemsIndexed(episodes) { i, ep ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = checked[i],
                                        onCheckedChange = { checked[i] = it }
                                    )
                                    Text(
                                        "第${i + 1}集 ${ep.name}",
                                        color = Color.White.copy(alpha = 0.9f),
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
                                Text("取消", color = Color.White)
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
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    TextButton(onClick = {
                                        val n = VideoDownloader.deleteTasks(itemChecked.toSet())
                                        itemChecked.clear()
                                        context.toastOnUi("已删除 $n 条（含缓存文件）")
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
                                    color = Color.White.copy(alpha = 0.5f),
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
                                    )
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
            color = Color(0xFF101010)
        ) { body() }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f),
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1C1C1E)
            ) { body() }
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
                color = Color.White.copy(alpha = 0.9f),
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
                    .background(Color.White.copy(alpha = 0.15f))
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
                else -> Color.White.copy(alpha = 0.6f)
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
                tint = if (completed) MaterialTheme.colorScheme.primary else Color.White
            )
        }
        // 三点菜单
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多操作",
                    tint = Color.White
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

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.video_get_url_fail),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.close))
                }
            }
        }
    }
}
