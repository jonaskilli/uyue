package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.databinding.DialogVnStageBinding
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.AiProfileService
import io.legado.app.model.ReadBook
import io.legado.app.model.VnParagraph
import io.legado.app.tts.voice.ScriptCharacterRepository
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视觉小说舞台（朗读大图界面的替换形态，开关见 ReadAloudConfigDialog「视觉小说舞台」）。
 *
 * 结构（自上而下）：
 * - 全屏 AI 场景背景（[VnBackgroundManager]：AI 选场景类别，缓存命中直接用，未命中生成）
 * - 顶部旁白字幕：旁白段落显示在图片最上面
 * - 底部两行：第一行说话人立绘（左侧、带边框、顶部裁剪保头部），第二行台词面板（打字机逐字）
 *
 * 段落归属：朗读服务推 EventBus.VN_PARAGRAPH（段落全文），本界面用
 * ScriptCharacterRepository.getChapterRoles 的 dialogText 反查说话人；
 * 反查不到按旁白处理（旁白不换立绘、顶部整段显示）。
 *
 * 交互：点击空白/背景返回朗读界面。
 */
class VnStageDialog : BaseDialogFragment(R.layout.dialog_vn_stage) {

    private val binding by viewBinding(DialogVnStageBinding::bind)

    /** 当前书名（空则功能降级为纯字幕） */
    private val bookName get() = ReadBook.book?.name.orEmpty()

    /** 当前说话人（空 = 旁白） */
    private var currentSpeaker: String = ""

    /** 立绘轮询协程 */
    private var portraitPollJob: Job? = null

    /** 当前是否已有立绘可显示（旁白时保持，避免闪烁） */
    private var portraitAvailable = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, R.style.dialog_style_material)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.black)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.FILL
            attr.width = ViewGroup.LayoutParams.MATCH_PARENT
            attr.height = ViewGroup.LayoutParams.MATCH_PARENT
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            clearFlags(
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION
            )
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            WindowInsetsControllerCompat(this, decorView).apply {
                if (ReadBookConfig.hideStatusBar) {
                    hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                } else {
                    show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                }
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rootView.setBackgroundColor(Color.BLACK)
        binding.tvVnDialogue.movementMethod = ScrollingMovementMethod()
        // 背景管理器（object 单例）：绑定显示目标
        VnBackgroundManager.attach(binding.ivVnBackground)

        // 中途进入：立刻渲染当前朗读段落（整段按已读完状态显示），
        // 随后段落切换事件自然接管。否则要等下一段开始才有文字。
        renderCurrentParagraphOnEnter()

        // 手势（与大图界面一致）：单击左/右 1/3 上一段/下一段，
        // 中 1/3 暂停/继续朗读；从上向下滑动退出本界面
        setupGestures()
    }

    override fun observeLiveBus() {
        // 旁白切句 + 对话触发时机：由 VN_SUBTITLE_POS 片段（带段内区间）驱动。
        // 注意：不能用 READ_ALOUD_SUBTITLE 累计长度——同步任务会周期性重发
        // 同一片段，累计长度膨胀 3~5 倍，导致段落刚开始对话就"读到了"。
        // VN_SUBTITLE_POS 带区间，readChars = pos.end 幂等，重发不膨胀。
        observeEvent<io.legado.app.model.VnSubtitlePos>(EventBus.VN_SUBTITLE_POS) { pos ->
            onSubtitlePos(pos)
        }
        // 段落开始：切分片段、逐段解析说话人、重置面板
        observeEvent<VnParagraph>(EventBus.VN_PARAGRAPH) { p ->
            handleParagraph(p)
        }
    }

    // ------------------------------------------------------------------
    // 段落处理
    // ------------------------------------------------------------------

    /** 片段类型：引号外旁白 / 引号内对话 */
    private enum class SegType { NARRATION, DIALOG }

    /** 段落片段：raw 段内字符区间 + 归属（对话段解析不出说话人时降级为旁白） */
    private data class Segment(
        val start: Int,
        val end: Int,
        val type: SegType,
        val text: String,
        val speaker: String = "",
        var triggered: Boolean = false
    )

    /** 当前段落的片段序列（VN_PARAGRAPH 时重建） */
    private var paraSegments: List<Segment> = emptyList()

    /** 已读字符数（字幕片段累计长度，≈朗读位置） */
    private var readChars = 0

    /**
     * 中途进入界面：立刻渲染朗读中的段落（整段按已读完状态显示）。
     * 用 ReadBook.durChapterPos（当前阅读位置=朗读位置）在章节段落里定位当前段；
     * 取不到时静默（等下一段事件接管）。
     */
    private fun renderCurrentParagraphOnEnter() {
        runCatching {
            val chapter = ReadBook.curTextChapter ?: return
            val pos = ReadBook.durChapterPos
            // 段落列表：找包含当前位置的段落（TextParagraph.text 即段落全文）
            var target: String? = null
            var targetPara: io.legado.app.ui.book.read.page.entities.TextParagraph? = null
            var last: String? = null
            var lastPara: io.legado.app.ui.book.read.page.entities.TextParagraph? = null
            for (para in chapter.paragraphs) {
                if (para.chapterPosition > pos) break
                last = para.text
                lastPara = para
                if (pos <= para.chapterPosition + para.length + 1) {
                    target = para.text
                    targetPara = para
                    break
                }
            }
            val para = targetPara ?: lastPara ?: return
            val text = (target ?: last)?.trim().orEmpty()
            if (text.isBlank()) return
            // 静态渲染：切分 + 旁白框全量 + 对话面板就位。
            // 不标记 triggered、不写满格对话文字——对话文字仍由字幕片段驱动
            // （按当前段内已读位置初始化 readChars，后续片段自然续上）。
            handleParagraph(VnParagraph(text, -1))
            // 段内已读位置 ≈ durChapterPos - 段起点
            readChars = (pos - para.chapterPosition).coerceIn(0, text.length)
            // 旁白框：首个对话前的整段旁白
            val narration = paraSegments
                .takeWhile { it.type != SegType.DIALOG }
                .map { it.text.trim() }
                .filter { it.isNotBlank() }
                .joinToString("  ")
            if (narration.isNotBlank()) {
                binding.tvVnNarration.visibility = View.VISIBLE
                binding.tvVnNarration.text = narration
            }
            // 对话面板：处于对话区间内则立即触发该对话（文字按 readChars 截取）
            val inDialog = paraSegments.firstOrNull {
                it.type == SegType.DIALOG && readChars >= it.start && readChars < it.end
            }
            if (inDialog != null) {
                triggerDialog(inDialog)
                val visible = (readChars - inDialog.start).coerceIn(0, inDialog.text.trim().length)
                binding.tvVnDialogue.text = inDialog.text.trim().take(visible)
            }
        }
    }

    /**
     * 段落开始朗读：切分片段 + 逐个对话段解析说话人。
     *
     * - 对话段：**等朗读进度到达时才触发**（[triggerDialog]），由字幕片段驱动；
     *   段首就是对话的立即触发，其余等位置到达（旁白读完/临近读完）。
     * - 旁白段：片段进旁白框（跟读节奏切句）；对话段中间的片段不进旁白框。
     * - 解析不出说话人的对话段降级为旁白段（文本不丢，走旁白框）。
     */
    private fun handleParagraph(p: VnParagraph) {
        if (!isAdded) return
        readChars = 0
        stopTypewriter()
        val raw = splitSegments(p.text)
        // 解析每个对话段的说话人；解析不出的降级为旁白（其文本走旁白框）
        paraSegments = raw.map { s ->
            if (s.type == SegType.DIALOG) {
                val sp = lookupSpeakerText(s.text)
                if (sp.isBlank()) s.copy(type = SegType.NARRATION) else s.copy(speaker = sp)
            } else s
        }
        val dialogSegs = paraSegments.filter { it.type == SegType.DIALOG }

        // ---- 旁白文字（与对话同框，在台词面板内）：先整段顶上
        // 「第一个对话之前」的旁白（随后片段接管 15 字/片切句）----
        val narrationBeforeDialog = paraSegments
            .takeWhile { it.type != SegType.DIALOG }
            .map { it.text.trim() }
            .filter { it.isNotBlank() }
            .joinToString("  ")
        if (narrationBeforeDialog.isNotBlank()) {
            binding.tvVnNarration.visibility = View.VISIBLE
            binding.tvVnNarration.text = narrationBeforeDialog
        } else {
            binding.tvVnNarration.visibility = View.GONE
            binding.tvVnNarration.text = ""
        }

        // ---- 台词面板：重置，等待朗读进度触发 ----
        binding.tvVnDialogue.text = ""
        if (narrationBeforeDialog.isNotBlank()) {
            // 旁白文字正在显示（纯旁白段或混合段的旁白部分）：
            // 立绘统一显示旁白形象，面板标"旁白"
            binding.tvVnRoleName.text = "旁白"
            binding.tvVnDialogue.visibility = View.GONE
            switchPortrait("旁白")
        } else if (dialogSegs.isEmpty()) {
            // 无旁白文字也无对话（异常兜底）：按旁白处理
            binding.tvVnRoleName.text = "旁白"
            binding.tvVnDialogue.visibility = View.GONE
            switchPortrait("旁白")
        } else {
            binding.tvVnRoleName.text = ""
            // 段首就是对话：立绘等触发时再显示（triggerDialog 里 switchPortrait）；
            // 这里不 GONE 立绘——保持 110dp 占位，避免演出区高度跳变
        }
        // 背景预分析调度 + 查记录切场景（带段落索引）
        VnBackgroundManager.onParagraphIndex(p.index, p.text)
    }

    /**
     * 字幕片段到达（VN_SUBTITLE_POS，带段内字符区间）：
     * 只负责**触发时机**——朗读位置越过某对话起点时启动该对话的打字机；
     * 文字进度由打字机协程（28ms/字）驱动，节奏均匀。
     * readChars = pos.end 幂等赋值——服务周期性重发同一片段不会膨胀进度。
     */
    private fun onSubtitlePos(pos: io.legado.app.model.VnSubtitlePos) {
        if (!isAdded || paraSegments.isEmpty()) return
        readChars = pos.end
        // 触发所有已被朗读越过的对话段（start < 片段末尾）
        paraSegments.filter {
            it.type == SegType.DIALOG && !it.triggered && it.start < pos.end
        }.forEach { triggerDialog(it) }
        // 当前片段与对话区间有交集时：对话显示优先，不进旁白框
        val inDialog = paraSegments.any {
            it.type == SegType.DIALOG && it.triggered && pos.start < it.end
        }
        if (inDialog) return
        // 纯旁白片段：进旁白框
        binding.tvVnNarration.visibility = View.VISIBLE
        binding.tvVnNarration.text = pos.text
    }

    /** 触发一个对话段：立绘切到说话人 + 启动打字机（28ms/字，节奏均匀） */
    private fun triggerDialog(seg: Segment) {
        seg.triggered = true
        currentSpeaker = seg.speaker
        binding.tvVnRoleName.text = seg.speaker
        // 双向互斥：对话显示时旁白区 GONE、台词区 VISIBLE——
        // 面板恒为「角色名 + 3 行正文」，任何状态高度不变
        binding.tvVnNarration.visibility = View.GONE
        binding.tvVnNarration.text = ""
        binding.tvVnDialogue.visibility = View.VISIBLE
        switchPortrait(seg.speaker)
        startTypewriter(seg.text.trim())
    }

    // ------------------------------------------------------------------
    // 打字机（28ms/字，独立协程逐字推进，节奏均匀）
    // ------------------------------------------------------------------

    private var typewriterJob: Job? = null

    private fun stopTypewriter() {
        typewriterJob?.cancel()
        typewriterJob = null
    }

    private fun startTypewriter(fullText: String) {
        stopTypewriter()
        if (fullText.isEmpty()) {
            binding.tvVnDialogue.text = ""
            return
        }
        typewriterJob = lifecycleScope.launch(Dispatchers.Main) {
            val sb = StringBuilder()
            for (ch in fullText) {
                sb.append(ch)
                binding.tvVnDialogue.text = sb.toString()
                delay(28)
            }
        }
    }

    /**
     * 把段落按引号切成交替的旁白/对话片段序列（带 raw 段内字符区间）。
     * 引号识别：中文全角“”『』「」与英文双引号。
     * 不配对：只有开引号→其后视为对话；只有闭引号→闭引号仅作边界，其前为旁白。
     */
    private fun splitSegments(paragraph: String): List<Segment> {
        val out = ArrayList<Segment>(4)
        val chars = paragraph.toCharArray()
        var narrationStart = 0
        var dialogStart = -1
        var i = 0
        fun addNarration(from: Int, to: Int) {
            if (to > from) out.add(
                Segment(from, to, SegType.NARRATION, paragraph.substring(from, to))
            )
        }
        fun addDialog(from: Int, to: Int) {
            if (to > from) out.add(
                Segment(from, to, SegType.DIALOG, paragraph.substring(from, to))
            )
        }
        while (i < chars.size) {
            val c = chars[i]
            val isOpen = c == '“' || c == '「' || c == '『' || c == '"'
            val isClose = c == '”' || c == '」' || c == '』' || c == '"'
            if (isOpen && dialogStart < 0) {
                addNarration(narrationStart, i)
                dialogStart = i + 1
            } else if (isClose && dialogStart >= 0) {
                addDialog(dialogStart, i)
                dialogStart = -1
                narrationStart = i + 1
            }
            i++
        }
        if (dialogStart >= 0) {
            // 只有开引号：其后全部视为对话
            addDialog(dialogStart, chars.size)
        } else if (narrationStart < chars.size) {
            // 正常闭引号后的引号外旁白
            addNarration(narrationStart, chars.size)
        }
        return out
    }

    /** 反查单个对话文本的说话人（matchChapterRoleByText 前缀索引） */
    private fun lookupSpeakerText(dialogText: String): String {
        if (bookName.isBlank()) return ""
        val title = ReadBook.curTextChapter?.chapter?.title.orEmpty()
        if (title.isBlank()) return ""
        val role = ScriptCharacterRepository.matchChapterRoleByText(
            bookName, title, dialogText.trim()
        )
        return if (role != null && !role.isNarration) role.name else ""
    }

    // ------------------------------------------------------------------
    // 立绘联动
    // ------------------------------------------------------------------

    private fun switchPortrait(speaker: String) {
        portraitPollJob?.cancel()
        portraitPollJob = null
        val cached = AiImageGenerator.getCachedAvatar(bookName, speaker)
        if (cached != null) {
            showPortrait(cached)
            return
        }
        // 无立绘：后台生成，5 秒轮询刷新。
        // 用 INVISIBLE 而非 GONE：保持 110dp 占位，演出区高度恒定，
        // 生成完成前面板位置不跳变
        portraitAvailable = false
        binding.ivVnPortrait.visibility = View.INVISIBLE
        if (speaker == "旁白") {
            // 旁白立绘：不建角色档案（避免"旁白"污染档案列表），
            // 直接按"说书人"形象生成，全局缓存一张
            AiImageGenerator.generateCharacterAvatar(
                characterName = "旁白",
                genderAge = "",
                bookName = bookName,
                identity = "说书人，故事的讲述者",
                personality = "沉稳",
                background = "",
                regenerate = false,
                templateId = io.legado.app.help.config.AppConfig.aiPortraitTemplateId,
                onSuccess = { },
                onFailure = { }
            )
        } else {
            AiProfileService.ensureProfileAndAvatar(
                bookName, speaker,
                ReadBook.durChapterIndex,
                ReadBook.curTextChapter?.chapter?.title.orEmpty()
            )
        }
        portraitPollJob = lifecycleScope.launch(Dispatchers.Main) {
            repeat(60) {
                delay(5000)
                val f = AiImageGenerator.getCachedAvatar(bookName, speaker)
                if (f != null) {
                    if (currentSpeaker == speaker) showPortrait(f)
                    return@launch
                }
            }
        }
    }

    private fun showPortrait(file: File) {
        portraitAvailable = true
        binding.ivVnPortrait.visibility = View.VISIBLE
        // 顶部正方形裁剪（保头部），边框由 drawable + padding 提供
        execute {
            AiImageGenerator.decodeAvatarTopSquare(file, 220)
        }.onSuccess {
            if (it != null && isAdded) {
                binding.ivVnPortrait.setImageBitmap(it)
            }
        }
    }

    // ------------------------------------------------------------------
    // 手势（与大图界面 FullscreenAiImageDialog 一致）
    // ------------------------------------------------------------------

    /**
     * 手势：
     * - 单击左 1/3：朗读上一段；单击右 1/3：朗读下一段
     * - 单击中 1/3：暂停/继续朗读
     * - 从上向下滑动：退出本界面
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val root = binding.rootView
        var moved = false
        var downY = 0f
        val gesture = android.view.GestureDetector(
            requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    if (moved) return false
                    val w = root.width.takeIf { it > 0 } ?: return false
                    when {
                        e.x < w / 3f -> seekParagraph(-1)
                        e.x > w * 2f / 3f -> seekParagraph(1)
                        else -> toggleReadAloud()
                    }
                    return true
                }

                override fun onScroll(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (moved) return false
                    val startY = e1?.y ?: downY
                    val dx = e2.x - (e1?.x ?: 0f)
                    val dy = e2.y - startY
                    // 从上向下滑动（纵向位移为主且超过阈值）：退出本界面
                    if (dy > 60 && Math.abs(dy) > Math.abs(dx)) {
                        dismissAllowingStateLoss()
                        moved = true
                    }
                    return false
                }
            }
        )
        root.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downY = event.y
                    moved = false
                }
            }
            gesture.onTouchEvent(event)
            true
        }
    }

    /** 暂停/继续朗读（与大图界面同款回调链） */
    private fun toggleReadAloud() {
        (activity as? ReadAloudDialog.CallBack)?.onClickReadAloud()
    }

    /**
     * 跳转朗读段落：±1 段。把目标段落位置换算成章节千分比，
     * 发 READ_ALOUD_SEEK_PARAGRAPH 事件（服务按千分比定位段落重播）。
     */
    /**
     * 跳转朗读段落：直接调用朗读界面同款 API（服务按 nowSpeak±1 推进）。
     * 之前自造的 durChapterPos→千分比→SEEK 事件方案与服务的定位方式不兼容
     * （千分比反算落回当前段内部），导致点右边永远重复同一段。
     */
    private fun seekParagraph(direction: Int) {
        val ctx = context ?: return
        if (direction < 0) {
            io.legado.app.model.ReadAloud.prevParagraph(ctx)
        } else {
            io.legado.app.model.ReadAloud.nextParagraph(ctx)
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        stopTypewriter()
        portraitPollJob?.cancel()
        portraitPollJob = null
        VnBackgroundManager.release()
    }
}
