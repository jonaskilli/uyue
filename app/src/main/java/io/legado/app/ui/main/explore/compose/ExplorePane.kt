package io.legado.app.ui.main.explore.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.explore.ExplorePaneViewModel
import io.legado.app.ui.widget.image.CoverImageView

/**
 * 字号在初版基础上共放大五号（+2 后用户仍嫌小，再 +3），容器与封面同步放大。
 * 参考阅读 Max 版「新版发现」的分区结构：源标题 + 分类条 + 带封面的内容区 + 翻页条。
 * 切源由右侧 25% 书源列表承担，所以这里不再画下拉选源。
 */
private object PaneD {
    val text = 14.sp
    val textTiny = 13.sp
    val textTitle = 15.sp
    val pad = 2.dp
    val gap = 2.dp
    val chipH = 32.dp
    val btnH = 32.dp
    /** 内容区顶部留白：分类条与第一行封面之间不该贴在一起 */
    val contentTop = 10.dp
    /** 封面放大：列表 34x46 -> 52x72，网格高度 78 -> 120 */
    val listCoverW = 72.dp
    val listCoverH = 100.dp
    /** 展开面板分类项比正文小一号，配合加宽后能多显示几个字 */
    val expandText = 13.sp
    /** 网格：封面下方独立的文字区，两行标题也能完整显示 */
    val gridNameBandH = 72.dp
    val gridFadeH = 28.dp
    /** 网格行与行之间的上下间距（用户要的是这个，卡片高，给足才看得出来） */
    val gridVGap = 14.dp
    /** 两列之间的左右间距：保持接近原状，不跟着一起加大 */
    val gridHGap = 2.dp
    val corner = 2.dp
    val hairline = 0.5.dp
}

/**
 * 起点落在顶条/分类条内、向下滑超过阈值就展开全部分类。
 * 只累计向下位移，横向滑动会由子 LazyRow 优先消费，按钮的点击也不受影响；
 * 抬手才触发，避免滑动途中弹窗打断手势。
 */
@Composable
private fun Modifier.expandOnDownDrag(onExpand: () -> Unit): Modifier {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    val currentOnExpand by rememberUpdatedState(onExpand)
    return pointerInput(Unit) {
        var down = 0f
        detectVerticalDragGestures(
            onDragStart = { down = 0f },
            onDragEnd = {
                if (down >= threshold) currentOnExpand()
                down = 0f
            },
            onDragCancel = { down = 0f }
        ) { _, dragAmount ->
            if (dragAmount > 0f) down += dragAmount
        }
    }
}

/** 不额外引 runtime-livedata，手动把 LiveData 桥到 Compose state */
@Composable
private fun <T> liveState(data: LiveData<T>, initial: T): State<T> =
    produceState(initialValue = initial, key1 = data) {
        val observer = Observer<T> { value = it }
        data.observeForever(observer)
        awaitDispose { data.removeObserver(observer) }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExplorePane(
    viewModel: ExplorePaneViewModel,
    onBookClick: (SearchBook) -> Unit,
    onBookLongClick: (SearchBook) -> Unit,
    modifier: Modifier = Modifier
) {
    val name by liveState(viewModel.sourceName, "")
    val kinds by liveState(viewModel.kinds, emptyList())
    val books by liveState(viewModel.books, emptyList())
    val loading by liveState(viewModel.loading, false)
    val message by liveState(viewModel.message, null)
    val page by liveState(viewModel.page, 1)
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf(viewModel.gridMode) }
    var showAllKinds by remember { mutableStateOf(false) }
    val tagRowState = rememberLazyListState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val paneScope = rememberCoroutineScope()

    // 滚到末尾自动加载下一页，取代原来的「更多」按钮；
    // 防重复与「到底」判断做在 ViewModel，避免重组时连发好几页
    LaunchedEffect(grid, books.size, loading) {
        snapshotFlow {
            if (grid) {
                val info = gridState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
            } else {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
            }
        }.collect { (last, total) ->
            if (total > 0 && last >= 0 && last >= total - 3) viewModel.loadMore()
        }
    }

    // 横向条只显示可加载的 url 类；展开面板显示全部类型
    val urlKinds = kinds.filter {
        it.type == ExploreKind.Type.url && !it.url.isNullOrBlank()
    }

    // 切源时 ViewModel 会自动加载第一个分类，分类条高亮要跟着同步
    LaunchedEffect(urlKinds) {
        if (urlKinds.none { it.url == selectedUrl }) {
            selectedUrl = urlKinds.firstOrNull()?.url
        }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        // 顶条：源名靠左自适应，按钮组靠右；展开按钮放最右且加大点击区
        Row(
            modifier = Modifier.fillMaxWidth().height(PaneD.btnH)
                .padding(horizontal = PaneD.pad)
                .expandOnDownDrag { showAllKinds = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                fontSize = PaneD.textTitle,
                maxLines = 1,
                // 不换行排成一行，超出的部分按像素裁到 weight 给的边界
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false).clipToBounds()
            )
            Spacer(Modifier.weight(1f))
            TinyButton(if (grid) "列表" else "网格") {
                grid = !grid
                viewModel.gridMode = grid
            }
            Spacer(Modifier.width(PaneD.gap))
            TinyButton("刷新") { viewModel.refresh() }
            Spacer(Modifier.width(PaneD.gap))
            // 展开分类：放在按钮组最右，宽度至少 56dp，避免小按钮难点
            Text(
                text = "展开 ▾",
                fontSize = PaneD.text,
                maxLines = 1,
                modifier = Modifier
                    .height(PaneD.btnH - PaneD.pad)
                    .widthIn(min = 56.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(PaneD.corner)
                    )
                    .border(
                        PaneD.hairline,
                        Color.Gray.copy(alpha = 0.3f),
                        RoundedCornerShape(PaneD.corner)
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .clickable { showAllKinds = true }
            )
            Spacer(Modifier.width(PaneD.gap))
            Text("P$page", fontSize = PaneD.textTiny)
        }

        // 分类条：只放可加载的 url 类分类，整行都留给横向滚动
        if (urlKinds.isNotEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(PaneD.chipH)
                    .zIndex(1f)
                    .expandOnDownDrag { showAllKinds = true }
            ) {
                LazyRow(
                    state = tagRowState,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = PaneD.pad)
                ) {
                items(count = urlKinds.size) { index ->
                    val kind = urlKinds[index]
                    val active = kind.url == selectedUrl
                    Text(
                        text = kind.title,
                        fontSize = PaneD.text,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(end = PaneD.gap)
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(PaneD.corner)
                            )
                            .border(
                                PaneD.hairline,
                                Color.Gray.copy(alpha = 0.3f),
                                RoundedCornerShape(PaneD.corner)
                            )
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                            .clickable {
                                selectedUrl = kind.url
                                viewModel.loadKind(kind)
                            }
                    )
                }
                }
            }
        }

        // 展开：按书源原本的 Flexbox 排布（圆角胶囊 + flexBasisPercent 宽度 +
        // 整行项占满 + 自动换行），不是等宽格子。含 url / button / text / select 全部类型。
        if (showAllKinds) {
            Dialog(
                onDismissRequest = { showAllKinds = false },
                // 关掉平台默认宽度限制，否则弹窗本身就被留白压窄
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(0.96f).heightIn(max = 460.dp)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(
                            "全部分类",
                            fontSize = PaneD.textTitle,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            kinds.forEachIndexed { index, kind ->
                                val style = kind.style()
                                val percent = style.layout_flexBasisPercent
                                val widthMod = when {
                                    style.layout_wrapBefore || percent >= 0.95f ->
                                        Modifier.fillMaxWidth()
                                    percent > 0f ->
                                        Modifier.fillMaxWidth(percent.coerceIn(0.25f, 0.9f))
                                    else -> Modifier.wrapContentWidth()
                                }
                                val loadable = kind.type == ExploreKind.Type.url &&
                                    !kind.url.isNullOrBlank()
                                val active = loadable && kind.url == selectedUrl
                                // 非 url 类是书源的筛选/输入项，沿用原 Flexbox 的样式但不可点
                                val label = when (kind.type) {
                                    ExploreKind.Type.select -> "${kind.title} ▾"
                                    ExploreKind.Type.text -> "${kind.title} ✎"
                                    else -> kind.title
                                }
                                Text(
                                    text = label,
                                    fontSize = PaneD.expandText,
                                    maxLines = 1,
                                    softWrap = false,
                                    color = if (loadable) Color.Unspecified
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = widthMod
                                        .clipToBounds()
                                        .padding(3.dp)
                                        .background(
                                            if (active) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            // 对齐 item_fillet_text 的圆角胶囊
                                            RoundedCornerShape(16.dp)
                                        )
                                        .border(
                                            PaneD.hairline,
                                            Color.Gray.copy(alpha = 0.3f),
                                            RoundedCornerShape(16.dp)
                                        )
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .then(
                                            if (loadable) Modifier.clickable {
                                                showAllKinds = false
                                                selectedUrl = kind.url
                                                viewModel.loadKind(kind)
                                                val rowIndex = urlKinds.indexOfFirst {
                                                    it.url == kind.url
                                                }
                                                if (rowIndex >= 0) {
                                                    paneScope.launch {
                                                        tagRowState.animateScrollToItem(rowIndex)
                                                    }
                                                }
                                            } else Modifier
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }

        // 内容区：占满剩余空间
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                // 换源 / 换分类 / 刷新期间旧列表已清空，显示加载态而不是上一个分类的内容
                loading && books.isEmpty() -> LoadingPane()
                !message.isNullOrBlank() -> CenteredText(
                    "${message!!}\n点此重试",
                    onClick = { viewModel.refresh() }
                )
                books.isEmpty() ->
                    CenteredText(if (name.isEmpty()) "点右侧书源查看内容" else "暂无内容")

                grid -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(PaneD.gridVGap),
                    horizontalArrangement = Arrangement.spacedBy(PaneD.gridHGap),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PaneD.pad, top = PaneD.contentTop,
                        end = PaneD.pad, bottom = PaneD.pad
                    )
                ) {
                    items(count = books.size) { index ->
                        GridCard(books[index], onBookClick, onBookLongClick)
                    }
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PaneD.pad, top = PaneD.contentTop,
                        end = PaneD.pad, bottom = PaneD.pad
                    )
                ) {
                    items(count = books.size) { index ->
                        ListCard(books[index], onBookClick, onBookLongClick)
                    }
                }
            }
        }

    }
}

@Composable
private fun TinyButton(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = PaneD.textTiny,
        maxLines = 1,
        modifier = Modifier
            .height(PaneD.btnH - PaneD.pad)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(PaneD.corner)
            )
            .border(
                PaneD.hairline,
                Color.Gray.copy(alpha = 0.3f),
                RoundedCornerShape(PaneD.corner)
            )
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun LoadingPane() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Spacer(Modifier.height(6.dp))
        Text("加载中…", fontSize = PaneD.text)
    }
}

@Composable
private fun CenteredText(text: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxSize().then(
            onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier
        ),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = PaneD.text)
    }
}

/** 复用项目自带的封面控件：它处理书源头、默认封面与 WiFi 限制 */
@Composable
private fun Cover(book: SearchBook, modifier: Modifier) {
    val onlyWifi = AppConfig.loadCoverOnlyWifi
    AndroidView(
        factory = { ctx -> CoverImageView(ctx) },
        modifier = modifier,
        update = { view -> view.load(book, onlyWifi) }
    )
}

/**
 * 按书架 item_bookshelf_grid2 的结构：封面铺满，书名叠在封面底部渐变条上（白字两行）。
 * 渐变用 Compose 原生 Brush 复刻书架的 bg_gradient_cover，尺寸仍走紧凑表。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCard(
    book: SearchBook,
    onClick: (SearchBook) -> Unit,
    onLongClick: (SearchBook) -> Unit
) {
    Column(
        Modifier.padding(PaneD.pad).combinedClickable(
            onClick = { onClick(book) },
            onLongClick = { onLongClick(book) }
        )
    ) {
        // 左栏变窄后按列宽等比缩放，避免固定高度把封面拉成瘦高
        Box(Modifier.fillMaxWidth().aspectRatio(1.2f)) {
            Cover(
                book,
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
            // 封面底部留一点渐变，视觉上延续到下面的文字条
            Box(
                Modifier.fillMaxWidth().height(PaneD.gridFadeH)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
            )
        }
        // 文字在封面下方的独立区域：固定高度 + 垂直居中，
        // 两行标题也不会被默认 Clip 裁掉第二行的底部
        Box(
            Modifier.fillMaxWidth().height(PaneD.gridNameBandH)
                .clipToBounds()
                .background(Color.Black.copy(alpha = 0.78f)),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = book.name,
                color = Color.White,
                fontSize = PaneD.text,
                lineHeight = 19.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 3.dp)
            )
        }
    }
}

/**
 * 按书架 item_bookshelf_list 的结构：左封面，右侧书名 / 作者 / 最新章节 / 简介多行。
 * 书架里的阅读进度、更新时间、未读角标这类"已在书架"数据，发现结果里没有，故不显示。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListCard(
    book: SearchBook,
    onClick: (SearchBook) -> Unit,
    onLongClick: (SearchBook) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp).combinedClickable(
            onClick = { onClick(book) },
            onLongClick = { onLongClick(book) }
        )
    ) {
        Cover(
            book,
            Modifier.size(PaneD.listCoverW, PaneD.listCoverH)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(PaneD.gap))
        // 整列按像素裁剪：三行单行文字都允许超出边界后显示半个字
        Column(Modifier.weight(1f).clipToBounds()) {
            Text(
                book.name, fontSize = PaneD.textTitle, maxLines = 1,
                softWrap = false
            )
            Text(
                book.author, fontSize = PaneD.text, maxLines = 1,
                softWrap = false
            )
            Text(
                book.latestChapterTitle.orEmpty(), fontSize = PaneD.textTiny,
                maxLines = 1, softWrap = false
            )
            Text(
                book.intro.orEmpty(), fontSize = PaneD.textTiny, maxLines = 2,
            )
        }
    }
}
