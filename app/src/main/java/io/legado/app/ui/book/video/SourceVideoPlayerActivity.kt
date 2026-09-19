package io.legado.app.ui.book.video

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.help.video.VideoDownloader
import io.legado.app.ui.timetech.player.CoverBackdrop
import io.legado.app.ui.timetech.player.EpisodeItem
import io.legado.app.ui.timetech.player.FullscreenVideoPlayer
import io.legado.app.ui.timetech.player.LocalVideo
import io.legado.app.utils.toastOnUi

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
        val state by SourceVideoPlayerSession.uiState.collectAsStateWithLifecycle()
        var homeMode by remember { mutableStateOf(false) }

        LaunchedEffect(intent.getStringExtra("bookUrl")) {
            SourceVideoPlayerSession.load(
                bookUrl = intent.getStringExtra("bookUrl").orEmpty(),
                startIndex = intent.getIntExtra("startIndex", 0),
            )
        }

        // 这一集拿不到直链：交给书源自己的播放器
        LaunchedEffect(state.fallbackIndex) {
            val index = state.fallbackIndex ?: return@LaunchedEffect
            SourceVideoPlayerSession.playWithSourcePlayer(index)
        }
        // 必须等浏览器真的拉起来再关页面，否则还在转场就被销毁，播放器起不来
        LaunchedEffect(state.fallbackLaunched) {
            if (state.fallbackLaunched) finish()
        }

        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                    .background(Color.Black)
            ) {
                when {
                    state.loading -> LoadingContent()

                    state.error != null || state.fallbackFailed != null -> ErrorDialog(
                        message = state.error ?: state.fallbackFailed.orEmpty(),
                        onDismiss = { finish() }
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
                                    // 退出播放器即清空切换列表，
                                    // 避免下次从其它分类打开同一视频时沿用旧分类的列表
                                    SourceVideoPlayerSession.clearFeed()
                                    finish()
                                }
                            },
                            headers = state.headers,
                            seriesTitle = state.bookName,
                            onResolveEpisode = SourceVideoPlayerSession::resolveEpisode,
                            onProgressChange = SourceVideoPlayerSession::saveProgress,
                            onOpenHome = { homeMode = true },
                            onFeedSwitch = if (state.feedEnabled) {
                                { offset -> SourceVideoPlayerSession.feedSwitch(offset) }
                            } else {
                                null
                            },
                            coverUrl = state.coverUrl,
                            bookUrl = state.bookUrl,
                            compactMode = homeMode,
                            onAddShelf = { SourceVideoPlayerSession.addToShelf() },
                        )
                    }

                    // 白名单书源：没有剧集可渲染，正在等书源自己拉起播放器
                    state.fallbackIndex != null -> LoadingContent()
                }
            }

            if (homeMode && state.episodes.isNotEmpty()) {
                HomePanel(
                    state = state,
                    onExpand = { homeMode = false },
                )
            }
        }
    }

    companion object {

        fun createIntent(
            context: Context,
            bookUrl: String,
            startIndex: Int = 0,
        ): Intent = Intent(context, SourceVideoPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("bookUrl", bookUrl)
            putExtra("startIndex", startIndex)
        }
    }
}

/** 首页模式下方区域：标题行（剧名/边看边存/下载/放大）+ 选集方块网格 */
@Composable
private fun HomePanel(
    state: SourceVideoPlayerUiState,
    onExpand: () -> Unit,
) {
    val episodes = state.episodes
    val context = LocalContext.current
    var showDownload by remember { mutableStateOf(false) }
    // 打开弹窗时：已有下载任务则直接进下载进度页，否则进选集勾选页
    var downloadShowManage by remember { mutableStateOf(false) }
    var watchSave by remember { mutableStateOf(VideoDownloader.isWatchSaveEnabled()) }
    val currentIndex = state.currentIndex.coerceIn(0, (episodes.lastIndex).coerceAtLeast(0))

    // 边看边存：当前集进入补齐队列；切集/播完后 worker 检测缺失片段补齐再合并
    LaunchedEffect(currentIndex, episodes.getOrNull(currentIndex)?.uri) {
        val chapter = SourceVideoPlayerSession.getChapters().getOrNull(currentIndex)
        val book = SourceVideoPlayerSession.getBook()
        val source = SourceVideoPlayerSession.getSource()
        val knownUrl = episodes.getOrNull(currentIndex)?.uri?.toString()?.takeIf { it.startsWith("http") }
        if (chapter != null && book != null && source != null) {
            VideoDownloader.watchStart(
                VideoDownloader.Task(book, source, currentIndex, chapter),
                knownUrl
            )
        }
    }

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

        // 选集方块网格
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 86.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(episodes) { i, ep ->
                EpisodeItem(
                    label = if (ep.name.length <= 7) ep.name else "第${i + 1}集",
                    selected = i == currentIndex,
                    ready = ep.uri != Uri.EMPTY,
                    onClick = { SourceVideoPlayerSession.selectEpisode(i) }
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

/** 下载弹窗：顶部双页签——选集勾选下载 / 实时下载进度管理 */
@Composable
private fun DownloadDialog(
    episodes: List<LocalVideo>,
    bookName: String,
    showManageFirst: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dlStates by VideoDownloader.states.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(if (showManageFirst) 1 else 0) }
    // 必须用快照状态列表：普通 BooleanArray 的修改不会触发重组，勾选框不刷新
    val checked = remember(episodes) {
        mutableStateListOf(*Array(episodes.size) { false })
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.7f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1C1C1E)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 页签行
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    TextButton(onClick = { tab = 1 }) {
                        Text(
                            "下载进度（${dlStates.size}）",
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
                        if (dlStates.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "暂无下载任务",
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
                                items(dlStates.size) { i ->
                                    DownloadStateItem(dlStates[i])
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 下载进度条目：浅色轨道 + 深色进度条 + 百分比 + 暂停/继续 + 三点菜单（删除已缓存/已生成mp4） */
@Composable
private fun DownloadStateItem(st: VideoDownloader.TaskState) {
    var menuOpen by remember { mutableStateOf(false) }
    val running = st.status == VideoDownloader.DlStatus.QUEUED ||
        st.status == VideoDownloader.DlStatus.RUNNING
    Row(verticalAlignment = Alignment.CenterVertically) {
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
            },
            color = when (st.status) {
                VideoDownloader.DlStatus.FAILED -> Color(0xFFFF6B6B)
                VideoDownloader.DlStatus.COMPLETED -> Color(0xFF34C759)
                else -> Color.White.copy(alpha = 0.6f)
            },
            fontSize = 11.sp
        )
        // 暂停 / 继续
        IconButton(onClick = {
            if (running) VideoDownloader.pause(st.key) else VideoDownloader.resume(st.key)
        }) {
            Icon(
                if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (running) "暂停" else "继续",
                tint = Color.White
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
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 3.dp,
            modifier = Modifier.size(40.dp)
        )
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
