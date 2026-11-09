package io.legado.app.ui.book.read.page

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.BookProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ContentEditDialog
import io.legado.app.ui.book.read.page.api.DataSource
import io.legado.app.ui.book.read.page.delegate.CoverPageDelegate
import io.legado.app.ui.book.read.page.delegate.HorizontalPageDelegate
import io.legado.app.ui.book.read.page.delegate.NoAnimPageDelegate
import io.legado.app.ui.book.read.page.delegate.PageDelegate
import io.legado.app.ui.book.read.page.delegate.ScrollPageDelegate
import io.legado.app.ui.book.read.page.delegate.SimulationPageDelegate
import io.legado.app.ui.book.read.page.delegate.SlidePageDelegate
import io.legado.app.ui.book.read.page.entities.PageDirection
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.TextPos
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.provider.LayoutProgressListener
import io.legado.app.ui.book.read.page.provider.TextPageFactory
import io.legado.app.utils.activity
import io.legado.app.utils.invisible
import io.legado.app.utils.longToastOnUi
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.throttle
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs

/**
 * 阅读视图
 */
class ReadView(context: Context, attrs: AttributeSet) :
    FrameLayout(context, attrs),
    DataSource, LayoutProgressListener {

    val callBack: CallBack get() = activity as CallBack
    var pageFactory: TextPageFactory = TextPageFactory(this)
    var pageDelegate: PageDelegate? = null
        private set(value) {
            field?.onDestroy()
            field = null
            field = value
            upContent()
        }
    override var isScroll = false
    val prevPage by lazy { PageView(context) }
    val curPage by lazy { PageView(context) }
    val nextPage by lazy { PageView(context) }
    val defaultAnimationSpeed = 300
    private var pressDown = false
    private var isMove = false

    //底部上滑进入朗读界面手势
    private var bottomSwipeUp = false
    //顶部下滑停止朗读手势（仅朗读中生效）
    private var topSwipeDown = false
    //朗读界面（半屏）弹出时，穿透到阅读页的触摸：上滑 → 开始/暂停朗读
    private var readAloudPanelSwipeUp = false
    //朗读界面（半屏）弹出时，穿透到阅读页的触摸：下滑 → 关闭朗读界面
    private var readAloudPanelSwipeDown = false
    //注意：此处不再接管横向滑动。上半屏的横向滑动保留为翻页操作，
    //模式切换只在朗读面板自身窗口内处理（见 ReadAloudDialog.installWindowGestures）。
    //触发上下滑手势所需的纵向位移。屏高占比越小越灵敏，
    //但过小会与左右翻页争抢事件，4% 是实测后较平衡的取值。
    private val bottomSwipeSlop by lazy {
        (height * 0.04f).coerceAtLeast(ViewConfiguration.get(context).scaledTouchSlop * 4f)
    }

    /**
     * 朗读界面是否已弹出。
     *
     * 半屏模式下朗读界面窗口只占屏幕下半部分并开启触摸穿透，上半屏仍属阅读页，
     * 触摸会直接落到本 View；此时需把手势接管为朗读专用：
     * 上滑开始朗读、下滑关闭朗读界面、单击关闭朗读界面。
     * 全屏模式窗口铺满整屏，触摸到不了这里，不受影响。
     */
    private val readAloudPanelShowing: Boolean
        get() = kotlin.runCatching { callBack.isReadAloudDialogShowing() }.getOrDefault(false)

    //起始点
    var startX: Float = 0f
    var startY: Float = 0f

    //上一个触碰点
    var lastX: Float = 0f
    var lastY: Float = 0f

    //触碰点
    var touchX: Float = 0f
    var touchY: Float = 0f

    //是否停止动画动作
    var isAbortAnim = false

    //长按
    private var longPressed = false
    private val longPressTimeout = 600L
    private val longPressRunnable = Runnable {
        longPressed = true
        onLongPress()
    }
    var isTextSelected = false
    private var pressOnTextSelected = false
    private val initialTextPos = TextPos(0, 0, 0)

    private val slopSquare by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var pageSlopSquare: Int = slopSquare
    var pageSlopSquare2: Int = pageSlopSquare * pageSlopSquare
    private var pageTouchClick: Int = 0
    private val tlRect = RectF()
    private val tcRect = RectF()
    private val trRect = RectF()
    private val mlRect = RectF()
    private val mcRect = RectF()
    private val mrRect = RectF()
    private val blRect = RectF()
    private val bcRect = RectF()
    private val brRect = RectF()
    private val boundary by lazy { BreakIterator.getWordInstance(Locale.getDefault()) }
    private val upProgressThrottle = throttle(200) { post { upProgress() } }
    val autoPager = AutoPager(this)
    val isAutoPage get() = autoPager.isRunning

    init {
        if (!isInEditMode) {
            upBg()
            setWillNotDraw(false)
            upPageAnim()
            upPageSlopSquare()
        }
        addView(nextPage)
        addView(curPage)
        addView(prevPage)
        prevPage.invisible()
        nextPage.invisible()
        curPage.markAsMainView()
        upPageTouchClick()
    }

    private fun setRect9x() {
        tlRect.set(0f + pageTouchClick, 0f, width * 0.33f, height * 0.33f)
        tcRect.set(width * 0.33f, 0f, width * 0.66f, height * 0.33f)
        trRect.set(width * 0.36f, 0f, width.toFloat() - pageTouchClick, height * 0.33f)
        mlRect.set(0f + pageTouchClick, height * 0.33f, width * 0.33f, height * 0.66f)
        mcRect.set(width * 0.33f, height * 0.33f, width * 0.66f, height * 0.66f)
        mrRect.set(width * 0.66f, height * 0.33f, width.toFloat() - pageTouchClick, height * 0.66f)
        blRect.set(0f + pageTouchClick, height * 0.66f, width * 0.33f, height.toFloat())
        bcRect.set(width * 0.33f, height * 0.66f, width * 0.66f, height.toFloat())
        brRect.set(width * 0.66f, height * 0.66f, width.toFloat() - pageTouchClick, height.toFloat())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setRect9x()
        prevPage.x = -w.toFloat()
        pageDelegate?.setViewSize(w, h)
        if (w > 0 && h > 0) {
            upBg()
            callBack.upSystemUiVisibility()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        pageDelegate?.onDraw(canvas)
        autoPager.onDraw(canvas)
    }

    override fun computeScroll() {
        pageDelegate?.computeScroll()
        autoPager.computeOffset()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }

    /**
     * 触摸事件
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = this.rootWindowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.mandatorySystemGestures()
            )
            val height = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
            if (height != null) {
                if (event.y > height.minus(insets.bottom)
                    && event.action != MotionEvent.ACTION_UP
                    && event.action != MotionEvent.ACTION_CANCEL
                ) {
                    return true
                }
            }
        }

        //在多点触控时，事件不走ACTION_DOWN分支而产生的特殊事件处理
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pageDelegate?.onTouch(event)
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                callBack.screenOffTimerStart()
                if (isTextSelected) {
                    curPage.cancelSelect()
                    isTextSelected = false
                    pressOnTextSelected = true
                } else {
                    pressOnTextSelected = false
                }
                longPressed = false
                postDelayed(longPressRunnable, longPressTimeout)
                pressDown = true
                isMove = false
                bottomSwipeUp = false
                topSwipeDown = false
                readAloudPanelSwipeUp = false
                readAloudPanelSwipeDown = false
                pageDelegate?.onTouch(event)
                pageDelegate?.onDown()
                setStartPoint(event.x, event.y, false)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pressDown) return true
                val absX = abs(startX - event.x)
                val absY = abs(startY - event.y)
                if (!isMove) {
                    isMove = absX > slopSquare || absY > slopSquare
                }
                if (isMove) {
                    longPressed = false
                    removeCallbacks(longPressRunnable)
                    //朗读界面已弹出时，穿透到阅读页的触摸接管为朗读手势。
                    //必须排在原上下滑手势之前，否则上滑会重复弹出朗读界面。
                    //限定 !isScroll：上下滚动模式下纵向滑动即滚动正文，
                    //与上下滑手势同义会互相冲突（与原生 bottomSwipeUp / topSwipeDown 保持一致）。
                    if (!isScroll && !isTextSelected && readAloudPanelShowing) {
                        if (!readAloudPanelSwipeUp && !readAloudPanelSwipeDown
                            && event.y < startY - bottomSwipeSlop
                            && absY >= absX * 0.9f
                        ) {
                            readAloudPanelSwipeUp = true
                            pageDelegate?.abortAnim()
                            return true
                        }
                        if (!readAloudPanelSwipeUp && !readAloudPanelSwipeDown
                            && startY > height * 0.1f
                            && event.y > startY + bottomSwipeSlop
                            && absY >= absX * 0.9f
                        ) {
                            readAloudPanelSwipeDown = true
                            pageDelegate?.abortAnim()
                            return true
                        }
                        if (readAloudPanelSwipeUp || readAloudPanelSwipeDown) return true
                    }
                    //非滚动翻页模式下，从屏幕下半部分（竖直中线以下）任意位置向上滑动进入朗读界面
                    if (!isScroll
                        && !isTextSelected
                        && !bottomSwipeUp
                        && startY > height * 0.333f
                        && event.y < startY - bottomSwipeSlop
                        && absY >= absX * 0.9f
                    ) {
                        bottomSwipeUp = true
                        pageDelegate?.abortAnim()
                        return true
                    }
                    if (bottomSwipeUp) return true
                    //非滚动翻页模式下，从屏幕上半部分（排除顶部10%，避免与下拉状态栏冲突）任意位置向下滑动停止朗读
                    //手势总开关（readAloudImageInReadPage）关闭时，不识别该手势，让触摸正常落到翻页/其它操作
                    if (!isScroll
                        && !isTextSelected
                        && !topSwipeDown
                        && !bottomSwipeUp
                        && AppConfig.readAloudImageInReadPage
                        && startY > height * 0.1f
                        && startY < height * 0.667f
                        && event.y > startY + bottomSwipeSlop
                        && absY >= absX * 0.9f
                    ) {
                        topSwipeDown = true
                        pageDelegate?.abortAnim()
                        return true
                    }
                    if (topSwipeDown) return true
                    if (isTextSelected) {
                        selectText(event.x, event.y)
                    } else {
                        pageDelegate?.onTouch(event)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                callBack.screenOffTimerStart()
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (bottomSwipeUp) {
                    callBack.showReadAloudDialog()
                    return true
                }
                if (topSwipeDown) {
                    // 顶部下滑：进入/显示阅读界面顶部的图片界面
                    //（开关关闭时由 CallBack 内部判断，该手势无效）
                    callBack.onTopSwipeDown()
                    return true
                }
                //朗读界面已弹出：上滑 → 开始/暂停朗读（与面板播放按钮行为一致）
                if (readAloudPanelSwipeUp) {
                    callBack.onClickReadAloud()
                    return true
                }
                //朗读界面已弹出：下滑 → 关闭朗读界面
                if (readAloudPanelSwipeDown) {
                    callBack.dismissReadAloudDialog()
                    return true
                }
                //朗读界面已弹出：单击穿透区 → 关闭朗读界面。
                //限定 !isMove，避免横滑翻页时误关面板；长按与选中态仍走原逻辑
                if (readAloudPanelShowing && !isMove && !longPressed && !pressOnTextSelected) {
                    callBack.dismissReadAloudDialog()
                    return true
                }
                if (!pageDelegate!!.isMoved && !isMove) {
                    if (!longPressed && !pressOnTextSelected) {
                        if (!curPage.onClick(startX, startY)) {
                            onSingleTapUp()
                        }
                        return true
                    }
                }
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (!pressDown) return true
                pressDown = false
                if (isTextSelected) {
                    callBack.showTextActionMenu()
                } else if (pageDelegate!!.isMoved) {
                    pageDelegate?.onTouch(event)
                }
                pressOnTextSelected = false
                autoPager.resume()
            }
        }
        return true
    }

    fun cancelSelect(clearSearchResult: Boolean = false) {
        if (isTextSelected) {
            curPage.cancelSelect(clearSearchResult)
            isTextSelected = false
        }
    }

    /**
     * 更新状态栏
     */
    fun upStatusBar() {
        curPage.upStatusBar()
        prevPage.upStatusBar()
        nextPage.upStatusBar()
    }

    /**
     * 保存开始位置
     */
    fun setStartPoint(x: Float, y: Float, invalidate: Boolean = true) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        touchX = x
        touchY = y

        if (invalidate) {
            invalidate()
        }
    }

    /**
     * 保存当前位置
     */
    fun setTouchPoint(x: Float, y: Float, invalidate: Boolean = true) {
        lastX = touchX
        lastY = touchY
        touchX = x
        touchY = y
        if (invalidate) {
            invalidate()
        }
        pageDelegate?.onScroll()
        val offset = touchY - lastY
        touchY -= offset - offset.toInt()
    }

    /**
     * 长按选择
     */
    private fun onLongPress() {
        kotlin.runCatching {
            curPage.longPress(startX, startY) { textPos: TextPos ->
                isTextSelected = true
                pressOnTextSelected = true
                initialTextPos.upData(textPos)
                val startPos = textPos.copy()
                val endPos = textPos.copy()
                val page = curPage.relativePage(textPos.relativePagePos)
                val stringBuilder = StringBuilder()
                var cIndex = textPos.columnIndex
                var lineStart = textPos.lineIndex
                var lineEnd = textPos.lineIndex
                for (index in textPos.lineIndex - 1 downTo 0) {
                    val textLine = page.getLine(index)
                    if (textLine.isParagraphEnd) {
                        break
                    } else {
                        stringBuilder.insert(0, textLine.text)
                        lineStart -= 1
                        cIndex += textLine.charSize
                    }
                }
                for (index in textPos.lineIndex until page.lineSize) {
                    val textLine = page.getLine(index)
                    stringBuilder.append(textLine.text)
                    lineEnd += 1
                    if (textLine.isParagraphEnd) {
                        break
                    }
                }
                var start: Int
                var end: Int
                boundary.setText(stringBuilder.toString())
                start = boundary.first()
                end = boundary.next()
                while (end != BreakIterator.DONE) {
                    if (cIndex in start until end) {
                        break
                    }
                    start = end
                    end = boundary.next()
                }
                kotlin.run {
                    var ci = 0
                    for (index in lineStart..lineEnd) {
                        val textLine = page.getLine(index)
                        for (j in textLine.columns.indices) {
                            if (ci == start) {
                                startPos.lineIndex = index
                                startPos.columnIndex = j
                            } else if (ci == end - 1) {
                                endPos.lineIndex = index
                                endPos.columnIndex = j
                                return@run
                            }
                            val column = textLine.getColumn(j)
                            if (column is TextBaseColumn) {
                                ci += column.charData.length
                            } else {
                                ci++
                            }
                        }
                    }
                }
                curPage.selectStartMoveIndex(startPos)
                curPage.selectEndMoveIndex(endPos)
            }
        }
    }

    /**
     * 单击
     */
    private fun onSingleTapUp() {
        // 开启音频缓存标记后，禁用左右两侧点击翻页（翻页改用滑动），
        // 避免点击删除图标/小喇叭时手指偏移被误判为翻页。
        // 中央区域与顶部中间仍执行原点击动作；图标点击由 ContentTextView 优先处理。
        val clickPageByTap = !AppConfig.showAudioCacheIndicator
        when {
            isTextSelected -> Unit
            mcRect.contains(startX, startY) -> if (!isAbortAnim) {
                click(AppConfig.clickActionMC)
            }

            bcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionBC)
            }

            blRect.contains(startX, startY) -> if (clickPageByTap) {
                click(AppConfig.clickActionBL)
            }

            brRect.contains(startX, startY) -> if (clickPageByTap) {
                click(AppConfig.clickActionBR)
            }

            mlRect.contains(startX, startY) -> if (clickPageByTap) {
                click(AppConfig.clickActionML)
            }

            mrRect.contains(startX, startY) -> if (clickPageByTap) {
                click(AppConfig.clickActionMR)
            }

            tlRect.contains(startX, startY) -> if (clickPageByTap) {
                click(AppConfig.clickActionTL)
            }

            tcRect.contains(startX, startY) -> {
                click(AppConfig.clickActionTC)
            }

            trRect.contains(startX, startY) -> if (clickPageByTap) {
                click(AppConfig.clickActionTR)
            }
        }
    }

    /**
     * 点击
     */
    private fun click(action: Int) {
        when (action) {
            0 -> {
                pageDelegate?.dismissSnackBar()
                callBack.showActionMenu()
            }

            1 -> pageDelegate?.nextPageByAnim(defaultAnimationSpeed)
            2 -> pageDelegate?.prevPageByAnim(defaultAnimationSpeed)
            3 -> ReadBook.moveToNextChapter(true)
            4 -> ReadBook.moveToPrevChapter(upContent = true, toLast = false)
            5 -> ReadAloud.prevParagraph(context)
            6 -> ReadAloud.nextParagraph(context)
            7 -> callBack.addBookmark()
            8 -> activity?.showDialogFragment(ContentEditDialog())
            9 -> callBack.changeReplaceRuleState()
            10 -> callBack.openChapterList()
            11 -> callBack.openSearchActivity(null)
            12 -> ReadBook.syncProgress(
                { progress -> callBack.sureNewProgress(progress) },
                { context.longToastOnUi(context.getString(R.string.upload_book_success)) },
                { context.longToastOnUi(context.getString(R.string.sync_book_progress_success)) })

            13 -> {
                if (BaseReadAloudService.isPlay()) {
                    ReadAloud.pause(context)
                } else {
                    ReadAloud.resume(context)
                }
            }
        }
    }

    /**
     * 选择文本
     */
    private fun selectText(x: Float, y: Float) {
        curPage.selectText(x, y) { textPos ->
            val compare = initialTextPos.compare(textPos)
            when {
                compare > 0 -> {
                    curPage.selectStartMoveIndex(textPos)
                    curPage.selectEndMoveIndex(
                        initialTextPos.relativePagePos,
                        initialTextPos.lineIndex,
                        initialTextPos.columnIndex - 1
                    )
                }

                else -> {
                    curPage.selectStartMoveIndex(initialTextPos)
                    curPage.selectEndMoveIndex(textPos)
                }
            }
        }
    }

    /**
     * 销毁事件
     */
    fun onDestroy() {
        pageDelegate?.onDestroy()
        curPage.cancelSelect()
        invalidateTextPage()
    }

    /**
     * 翻页动画完成后事件
     * @param direction 翻页方向
     */
    fun fillPage(direction: PageDirection): Boolean {
        return when (direction) {
            PageDirection.PREV -> {
                pageFactory.moveToPrev(true)
            }

            PageDirection.NEXT -> {
                pageFactory.moveToNext(true)
            }

            else -> false
        }
    }

    /**
     * 更新翻页动画
     */
    fun upPageAnim(upRecorder: Boolean = false) {
        isScroll = ReadBook.pageAnim() == 3
        ChapterProvider.upLayout()
        when (ReadBook.pageAnim()) {
            PageAnim.coverPageAnim -> if (pageDelegate !is CoverPageDelegate) {
                pageDelegate = CoverPageDelegate(this)
            }

            PageAnim.slidePageAnim -> if (pageDelegate !is SlidePageDelegate) {
                pageDelegate = SlidePageDelegate(this)
            }

            PageAnim.simulationPageAnim -> if (pageDelegate !is SimulationPageDelegate) {
                pageDelegate = SimulationPageDelegate(this)
            }

            PageAnim.scrollPageAnim -> if (pageDelegate !is ScrollPageDelegate) {
                pageDelegate = ScrollPageDelegate(this)
            }

            else -> if (pageDelegate !is NoAnimPageDelegate) {
                pageDelegate = NoAnimPageDelegate(this)
            }
        }
        (pageDelegate as? ScrollPageDelegate)?.noAnim = AppConfig.noAnimScrollPage
        if (upRecorder) {
            (pageDelegate as? HorizontalPageDelegate)?.upRecorder()
            autoPager.upRecorder()
        }
        pageDelegate?.setViewSize(width, height)
        if (isScroll) {
            curPage.setAutoPager(autoPager)
        } else {
            curPage.setAutoPager(null)
        }
        curPage.setIsScroll(isScroll)
    }

    /**
     * 更新阅读内容
     * @param relativePosition 相对位置 -1 上一页 0 当前页 1 下一页
     * @param resetPageOffset 滚动阅读是是否重置位置
     */
    override fun upContent(relativePosition: Int, resetPageOffset: Boolean) {
        post {
            curPage.setContentDescription(pageFactory.curPage.text)
        }
        if (isScroll && !isAutoPage) {
            if (relativePosition == 0) {
                curPage.setContent(pageFactory.curPage, resetPageOffset)
            } else {
                curPage.invalidateContentView()
            }
        } else {
            when (relativePosition) {
                -1 -> prevPage.setContent(pageFactory.prevPage)
                1 -> nextPage.setContent(pageFactory.nextPage)
                else -> {
                    curPage.setContent(pageFactory.curPage, resetPageOffset)
                    nextPage.setContent(pageFactory.nextPage)
                    prevPage.setContent(pageFactory.prevPage)
                }
            }
        }
        callBack.screenOffTimerStart()
    }

    private fun upProgress() {
        curPage.setProgress(pageFactory.curPage)
    }

    /**
     * 更新滑动距离
     */
    fun upPageSlopSquare() {
        val pageTouchSlop = AppConfig.pageTouchSlop
        this.pageSlopSquare = if (pageTouchSlop == 0) slopSquare else pageTouchSlop
        pageSlopSquare2 = this.pageSlopSquare * this.pageSlopSquare
    }

    /**
     * 更新边缘点击阈值
     */
    fun upPageTouchClick() {
        this.pageTouchClick = AppConfig.pageTouchClick
        setRect9x()
    }

    /**
     * 更新样式
     */
    fun upStyle() {
        ChapterProvider.upStyle()
        curPage.upStyle()
        prevPage.upStyle()
        nextPage.upStyle()
        if (ReadBookConfig.isNineBgImg) {
            upBg()
        }
    }

    /**
     * 更新背景
     */
    fun upBg() {
        ReadBookConfig.upBg(width, height)
        curPage.upBg()
        prevPage.upBg()
        nextPage.upBg()
    }

    /**
     * 更新背景透明度
     */
    fun upBgAlpha() {
        curPage.upBgAlpha()
        prevPage.upBgAlpha()
        nextPage.upBgAlpha()
    }

    /**
     * 更新时间信息
     */
    fun upTime() {
        curPage.upTime()
        prevPage.upTime()
        nextPage.upTime()
    }

    /**
     * 更新电量信息
     */
    fun upBattery(battery: Int) {
        curPage.upBattery(battery)
        prevPage.upBattery(battery)
        nextPage.upBattery(battery)
    }

    /**
     * 从选择位置开始朗读
     */
    suspend fun aloudStartSelect() {
        val selectStartPos = curPage.selectStartPos
        var pagePos = selectStartPos.relativePagePos
        val line = selectStartPos.lineIndex
        val column = selectStartPos.columnIndex
        while (pagePos > 0) {
            if (!ReadBook.moveToNextPage()) {
                ReadBook.moveToNextChapterAwait(false)
            }
            pagePos--
        }
        val startPos = curPage.textPage.getPosByLineColumn(line, column)
        ReadBook.readAloud(startPos = startPos)
    }

    /**
     * @return 选择的文本
     */
    fun getSelectText(): String {
        return curPage.selectedText
    }

    /**
     * 选区覆盖到的完整段落（段落号 + 段落文本）
     */
    fun getSelectedParagraphs(): List<Pair<Int, String>> {
        return curPage.selectedParagraphs
    }

    fun getCurVisiblePage(): TextPage {
        return curPage.getCurVisiblePage()
    }

    fun getReadAloudPos(): Pair<Int, TextLine>? {
        return curPage.getReadAloudPos()
    }

    fun invalidateTextPage() {
        if (!AppConfig.optimizeRender) {
            return
        }
        pageFactory.run {
            prevPage.invalidateAll()
            curPage.invalidateAll()
            nextPage.invalidateAll()
            nextPlusPage.invalidateAll()
        }
    }

    fun onScrollAnimStart() {
        autoPager.pause()
    }

    fun onScrollAnimStop() {
        autoPager.resume()
    }

    fun onPageChange() {
        autoPager.reset()
        submitRenderTask()
    }

    fun submitRenderTask() {
        if (!AppConfig.optimizeRender) {
            return
        }
        curPage.submitRenderTask()
    }

    fun isLongScreenShot(): Boolean {
        return curPage.isLongScreenShot()
    }

    override fun onLayoutPageCompleted(index: Int, page: TextPage) {
        upProgressThrottle.invoke()
    }

    override val currentChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(0) else null
        }

    override val nextChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(1) else null
        }

    override val prevChapter: TextChapter?
        get() {
            return if (callBack.isInitFinish) ReadBook.textChapter(-1) else null
        }

    override fun hasNextChapter(): Boolean {
        return ReadBook.durChapterIndex < ReadBook.simulatedChapterSize - 1
    }

    override fun hasPrevChapter(): Boolean {
        return ReadBook.durChapterIndex > 0
    }

    interface CallBack {
        val isInitFinish: Boolean
        fun showActionMenu()
        fun showReadAloudDialog()
        /** 朗读界面是否已弹出（半屏时上半屏触摸会穿透到本 View） */
        fun isReadAloudDialogShowing(): Boolean
        /** 关闭朗读界面 */
        fun dismissReadAloudDialog()
        /** 开始/暂停朗读，与朗读界面播放按钮行为一致 */
        fun onClickReadAloud()
        /**
         * 阅读页顶部向下滑动：显示阅读界面顶部的图片界面。
         * 开关关闭时该手势无效（默认空实现，不影响其它实现者）。
         */
        fun onTopSwipeDown() {}
        fun screenOffTimerStart()
        fun showTextActionMenu()
        fun autoPageStop()
        fun openChapterList()
        fun addBookmark()
        fun changeReplaceRuleState()
        fun openSearchActivity(searchWord: String?)
        fun upSystemUiVisibility()
        fun sureNewProgress(progress: BookProgress)
    }
}
