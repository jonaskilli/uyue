package io.legado.app.ui.main.explore.compose

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.explore.ExplorePaneViewModel
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.getCompatColor

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
    /** 顶条与分类条之间的间距：原来两块贴得太近 */
    val headerGap = 8.dp
    /** 内容区顶部留白：加大后第一行封面不会被上方的分类条压住 */
    val contentTop = 14.dp
    /** 分类按钮最小宽度：按两个汉字计，字数再少也不缩成一条 */
    val chipMinW = 48.dp
    /** 分类按钮之间的间距 */
    val chipGap = 8.dp
    /** 顶条按钮之间的间距 */
    val btnGap = 10.dp
    /** 顶条小按钮最小宽度，方便点击 */
    val btnMinW = 44.dp
    /** 封面放大：列表 34x46 -> 52x72，网格高度 78 -> 120 */
    val listCoverW = 72.dp
    val listCoverH = 100.dp
    /** 展开面板分类项比正文小一号，配合加宽后能多显示几个字 */
    val expandText = 13.sp
    /** 网格：文字区收紧，标题贴着封面底部，两行也能完整显示 */
    val gridNameBandH = 40.dp
    /** 网格行与行之间的上下间距：保留分隔但不再把名字推得太远 */
    val gridVGap = 12.dp
    /** 每张卡片上下各留一段，与行间距叠加成上下间隔 */
    val gridItemPadV = 2.dp
    /** 两列之间的左右间距：保持接近原状，不跟着一起加大 */
    val gridHGap = 2.dp
    val corner = 2.dp
    val hairline = 0.5.dp
}

/**
 * 发现页与视频页共用：颜色取自项目自身的日/夜间资源（values / values-night），
 * 与 App 其它界面保持一致。Compose 的 MaterialTheme 默认永远停在亮色配色，
 * 且不会给 Text 提供主题文字色，所以这里显式构造 ColorScheme 并注入
 * LocalContentColor；isSystemInDarkTheme() 会在日夜间切换时触发重组。
 */
@Composable
private fun paneColorScheme(): ColorScheme {
    val context = LocalContext.current
    val night = isSystemInDarkTheme()
    fun color(resId: Int) = Color(context.getCompatColor(resId))
    val surface = color(R.color.background)
    val surfaceVariant = color(R.color.background_card)
    val onSurface = color(R.color.primaryText)
    val onSurfaceVariant = color(R.color.secondaryText)
    val outline = color(R.color.divider)
    val primary = Color(ThemeStore.primaryColor(context))
    val primaryContainer = primary
        .copy(alpha = if (night) 0.38f else 0.20f)
        .compositeOver(surface)
    val base = if (night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        background = surface,
        onBackground = onSurface,
        outline = outline
    )
}

/**
 * 发现页 / 视频页的 Compose 主题入口：两个页面的 setContent 都从这里进入，
 * 保证日夜间切换时整块界面（背景、文字、卡片、边框）都跟着项目主题走。
 */
@Composable
fun ExplorePaneTheme(content: @Composable () -> Unit) {
    val scheme = paneColorScheme()
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            content()
        }
    }
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
    val contentEpoch by liveState(viewModel.contentEpoch, 0)
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf(viewModel.gridMode) }
    var showAllKinds by remember { mutableStateOf(false) }
    val tagRowState = rememberLazyListState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val paneScope = rememberCoroutineScope()
    // 分类 JS 的 java 对象：与改版前 ExploreAdapter 一致，让按钮/下拉的 action
    // 里 java.refreshExplore() / upUiData() 仍能重绘分类、回写筛选值
    val activity = LocalContext.current as? AppCompatActivity
    val kindJs = remember(name) {
        SourceLoginJsExtensions(
            activity,
            viewModel.getSource(),
            object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, String?>?) {
                    data?.forEach { (k, v) -> if (v != null) viewModel.putKindValue(k, v) }
                }

                override fun reUiView() {
                    viewModel.refreshKinds()
                }
            }
        )
    }

    // 换源 / 换分类 / 刷新都从顶部开始看。gridState、listState 是长期 remember 的，
    // 不重置的话新分类数据更短，原滚动位置会被钳到列表末尾，看起来像"一下滑到底"。
    // 只滚当前模式的 state，避免动到未附加的列表；切网格/列表时也归位。
    LaunchedEffect(contentEpoch, grid) {
        if (grid) gridState.scrollToItem(0) else listState.scrollToItem(0)
    }

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
                // 独占按钮组之外的全部剩余宽度。原先这里又放了一个 weight(1f) 的
                // Spacer，剩余空间被两者平分：名字只拿到一半就被裁掉，另一半变成
                // 名字与「网格/列表」之间的空白。
                modifier = Modifier.weight(1f).clipToBounds()
            )
            Spacer(Modifier.width(PaneD.btnGap))
            TinyButton(if (grid) "列表" else "网格") {
                grid = !grid
                viewModel.gridMode = grid
            }
            Spacer(Modifier.width(PaneD.btnGap))
            TinyButton("刷新") { viewModel.refresh() }
            Spacer(Modifier.width(PaneD.btnGap))
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
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(PaneD.corner)
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .clickable { showAllKinds = true }
            )
            Spacer(Modifier.width(PaneD.btnGap))
            Text("P$page", fontSize = PaneD.textTiny)
        }

        // 顶条与分类条之间留出间距，别贴在一起
        Spacer(Modifier.height(PaneD.headerGap))

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
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(end = PaneD.chipGap)
                            .widthIn(min = PaneD.chipMinW)
                            .background(
                                if (active) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(PaneD.corner)
                            )
                            .border(
                                PaneD.hairline,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                RoundedCornerShape(PaneD.corner)
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .clickable {
                                selectedUrl = kind.url
                                viewModel.loadKind(kind)
                            }
                    )
                }
                }
            }
        } else {
            // 没有分类时也占住同样的高度：右侧书源列表的顶部偏移是固定值，
            // 头部高度必须恒定，否则会对不齐
            Spacer(Modifier.fillMaxWidth().height(PaneD.chipH))
        }

        // 展开：按书源原本的 Flexbox 排布（圆角胶囊 + flexBasisPercent 宽度 +
        // 整行项占满 + 自动换行），不是等宽格子。含 url / button / text / select 全部类型。
        if (showAllKinds) {
            Dialog(
                onDismissRequest = { showAllKinds = false },
                // 关掉平台默认宽度限制，否则弹窗本身就被留白压窄
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                // 高度按屏幕比例给：分类多的书源一次能看到更多项（仍可上下滑动）；
                // 分类少时仍是 wrapContent，不会撑出一个很高的空弹窗
                val screenH = LocalConfiguration.current.screenHeightDp.dp
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(0.96f).heightIn(max = screenH * 0.88f)
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
                                .heightIn(max = screenH * 0.76f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            kinds.forEach { kind ->
                                val style = kind.style()
                                val percent = style.layout_flexBasisPercent
                                val widthMod = when {
                                    style.layout_wrapBefore || percent >= 0.95f ->
                                        Modifier.fillMaxWidth()
                                    percent > 0f ->
                                        Modifier.fillMaxWidth(percent.coerceIn(0.25f, 0.9f))
                                    else -> Modifier.wrapContentWidth()
                                }
                                // 与改版前 Flexbox 一样按 kind.type 分别渲染：
                                // url 胶囊 / 下拉选择 / 输入框 / 切换 / 动作按钮全部可交互
                                ExploreKindItem(
                                    kind = kind,
                                    modifier = widthMod,
                                    active = kind.type == ExploreKind.Type.url &&
                                        !kind.url.isNullOrBlank() && kind.url == selectedUrl,
                                    viewModel = viewModel,
                                    jsExt = kindJs,
                                    onValueChanged = { viewModel.reloadForKindChange() },
                                    onLoadUrl = { picked ->
                                        showAllKinds = false
                                        selectedUrl = picked.url
                                        viewModel.loadKind(picked)
                                        val rowIndex = urlKinds.indexOfFirst {
                                            it.url == picked.url
                                        }
                                        if (rowIndex >= 0) {
                                            paneScope.launch {
                                                tagRowState.animateScrollToItem(rowIndex)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 内容区：顶部全宽区域已让给顶条 + 分类条，这里只占左侧 70%，
        // 右侧 30% 空出来给 XML 里的书源列表（rv_find 在 ComposeView 上层）
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(Modifier.fillMaxWidth(0.7f).fillMaxHeight()) {
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
}

/** 展开分类里的圆角胶囊：对齐旧版 item_fillet_text，颜色跟随主题 */
@Composable
private fun KindChip(
    label: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Text(
        text = label,
        fontSize = PaneD.expandText,
        maxLines = 1,
        softWrap = false,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clipToBounds()
            .padding(3.dp)
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .border(
                PaneD.hairline,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    )
}

/** viewName：'字面量' 或书源 JS 求出的动态标题（旧版 evalUiJs） */
@Composable
private fun rememberKindLabel(
    kind: ExploreKind,
    viewModel: ExplorePaneViewModel
): State<String> = produceState(initialValue = kind.title, key1 = kind) {
    val n = viewModel.evalViewName(kind)
    value = when {
        n == null -> kind.title
        n.isEmpty() -> "null"
        else -> n
    }
}

/** 展开面板按 kind.type 分派：url / select / text / toggle / button 全部可交互 */
@Composable
private fun ExploreKindItem(
    kind: ExploreKind,
    modifier: Modifier,
    active: Boolean,
    viewModel: ExplorePaneViewModel,
    jsExt: SourceLoginJsExtensions,
    onValueChanged: () -> Unit,
    onLoadUrl: (ExploreKind) -> Unit
) {
    val label by rememberKindLabel(kind, viewModel)
    val context = LocalContext.current
    when (kind.type) {
        ExploreKind.Type.select -> KindSelectItem(
            kind, label, modifier, viewModel, jsExt, onValueChanged
        )
        ExploreKind.Type.text -> KindTextItem(kind, label, modifier, viewModel, jsExt)
        ExploreKind.Type.toggle -> KindToggleItem(
            kind, label, modifier, viewModel, jsExt, onValueChanged
        )
        ExploreKind.Type.button -> {
            var lastClick by remember(kind) { mutableStateOf(0L) }
            KindChip(label = label, active = false, modifier = modifier) {
                val now = System.currentTimeMillis()
                if (now - lastClick >= 200) {
                    lastClick = now
                    viewModel.evalKindAction(kind, jsExt)
                    onValueChanged()
                }
            }
        }
        else -> KindChip(label = label, active = active, modifier = modifier) {
            if (kind.title.startsWith("ERROR:")) {
                Toast.makeText(context, kind.url.orEmpty(), Toast.LENGTH_LONG).show()
            } else {
                onLoadUrl(kind)
            }
        }
    }
}

/** select：下拉列表，选项来自 kind.chars，选中值写回 infoMap 并触发 action */
@Composable
private fun KindSelectItem(
    kind: ExploreKind,
    label: String,
    modifier: Modifier,
    viewModel: ExplorePaneViewModel,
    jsExt: SourceLoginJsExtensions,
    onValueChanged: () -> Unit
) {
    val options = remember(kind) {
        kind.chars?.filterNotNull().orEmpty().ifEmpty { listOf("chars", "is null") }
    }
    var selected by remember(kind) {
        mutableStateOf(
            viewModel.kindValue(kind.title) ?: kind.default ?: options.firstOrNull().orEmpty()
        )
    }
    // 旧版初始化就把默认值写进 infoMap，后续 URL 规则才取得到
    LaunchedEffect(kind) { viewModel.putKindValue(kind.title, selected) }
    var expanded by remember(kind) { mutableStateOf(false) }
    Box(modifier) {
        KindChip(
            label = "$label: $selected ▾",
            active = false,
            modifier = Modifier.wrapContentWidth()
        ) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = PaneD.expandText, maxLines = 1) },
                    onClick = {
                        expanded = false
                        selected = option
                        viewModel.putKindValue(kind.title, option)
                        viewModel.evalKindAction(kind, jsExt)
                        onValueChanged()
                    }
                )
            }
        }
    }
}

/** text：输入即写 infoMap，action 带 500ms 防抖（对齐旧版 TextWatcher） */
@Composable
private fun KindTextItem(
    kind: ExploreKind,
    label: String,
    modifier: Modifier,
    viewModel: ExplorePaneViewModel,
    jsExt: SourceLoginJsExtensions
) {
    var value by remember(kind) {
        mutableStateOf(viewModel.kindValue(kind.title).orEmpty())
    }
    var lastActionValue by remember(kind) { mutableStateOf(value) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            viewModel.putKindValue(kind.title, it)
        },
        singleLine = true,
        placeholder = { Text(label, fontSize = PaneD.expandText, maxLines = 1) },
        textStyle = TextStyle(
            fontSize = PaneD.expandText,
            color = MaterialTheme.colorScheme.onSurface
        ),
        modifier = modifier.padding(3.dp).widthIn(min = 120.dp)
    )
    LaunchedEffect(value) {
        if (value == lastActionValue) return@LaunchedEffect
        delay(500)
        lastActionValue = value
        viewModel.evalKindAction(kind, jsExt)
    }
}

/** toggle：点击在 kind.chars 间循环，写 infoMap 并触发 action */
@Composable
private fun KindToggleItem(
    kind: ExploreKind,
    label: String,
    modifier: Modifier,
    viewModel: ExplorePaneViewModel,
    jsExt: SourceLoginJsExtensions,
    onValueChanged: () -> Unit
) {
    val chars = remember(kind) {
        kind.chars?.filterNotNull().orEmpty().ifEmpty { listOf("chars", "is null") }
    }
    val valueFirst = kind.style().layout_justifySelf != "right"
    var value by remember(kind) {
        mutableStateOf(
            viewModel.kindValue(kind.title) ?: kind.default ?: chars.firstOrNull().orEmpty()
        )
    }
    LaunchedEffect(kind) { viewModel.putKindValue(kind.title, value) }
    var lastClick by remember(kind) { mutableStateOf(0L) }
    KindChip(
        label = if (valueFirst) "$value$label" else "$label$value",
        active = false,
        modifier = modifier
    ) {
        val now = System.currentTimeMillis()
        if (now - lastClick >= 200) {
            lastClick = now
            val next = chars.getOrNull((chars.indexOf(value) + 1) % chars.size) ?: ""
            value = next
            viewModel.putKindValue(kind.title, next)
            viewModel.evalKindAction(kind, jsExt)
            onValueChanged()
        }
    }
}

@Composable
private fun TinyButton(text: String, onClick: () -> Unit) {
    // 用 Box 包一层，让文字在固定高度的按钮里真正上下居中；
    // 原先直接把 Text 放在 background 上，只有 1dp 纵向 padding，字会贴着上沿
    Box(
        Modifier
            .height(PaneD.btnH - PaneD.pad)
            .widthIn(min = PaneD.btnMinW)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(PaneD.corner)
            )
            .border(
                PaneD.hairline,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(PaneD.corner)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = PaneD.textTiny,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
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
 * 网格卡片：封面按列宽等比缩放，标题放在封面下方的独立文字区。
 * 文字区底色与文字色都取自日夜间主题，不再用固定的半透明黑 + 白字；
 * 卡片上下 padding 与 gridVGap 叠加，形成清晰的上下间隔。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridCard(
    book: SearchBook,
    onClick: (SearchBook) -> Unit,
    onLongClick: (SearchBook) -> Unit
) {
    Column(
        Modifier.padding(horizontal = PaneD.pad, vertical = PaneD.gridItemPadV)
            .combinedClickable(
                onClick = { onClick(book) },
                onLongClick = { onLongClick(book) }
            )
    ) {
        // 与 CoverImageView.onMeasure() 的 4:3 严格对齐：
        // 之前容器是 1.2:1 的矮胖框，控件却按 4:3 测量，CENTER_CROP 会把
        // 图片上下裁掉，第一排连顶部圆角都看不到
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f)) {
            Cover(
                book,
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        // 文字区收紧 + 顶部对齐：标题紧贴封面底部，到下一排封面也更近
        Box(
            Modifier.fillMaxWidth().height(PaneD.gridNameBandH)
                .clipToBounds()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.TopStart
        ) {
            Text(
                text = book.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = PaneD.text,
                lineHeight = 18.sp,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
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
