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
 * - 顶部旁白字幕：旁白段落显示在图片最上面（旁白态不显示立绘，仅留占位高度）
 * - 底部两行：第一行说话人立绘（左侧、带边框、顶部裁剪保头部，仅对话角色显示），第二行台词面板（打字机逐字）
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

        // 点立绘 → 打开该角色的角色档案（可补全信息/编辑提示词/重新生成立绘）。
        // 仅对话角色有立绘可点；旁白态立绘为 INVISIBLE 占位、不可点击。
        binding.ivVnPortrait.setOnClickListener {
            val speaker = currentSpeaker.ifBlank { "旁白" }
            CharacterProfileDialog.newInstance(bookName, speaker)
                .show(parentFragmentManager, "characterProfileDialog")
        }
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
            // 用统一路由定位当前片段（与 onSubtitlePos 同款判据：朗读位置）：
            // 朗读位置在对话内 → 触发对话；在旁白内 → 旁白框整段
            val current = paraSegments.lastOrNull { it.start < readChars }
            if (current != null && current.type == SegType.DIALOG) {
                triggerDialog(current)
                val visible = (readChars - current.start).coerceIn(0, current.text.trim().length)
                binding.tvVnDialogue.text = current.text.trim().take(visible)
            } else if (current != null) {
                if (currentSpeaker != "旁白") switchPortrait("旁白")
                currentSpeaker = "旁白"
                binding.tvVnNarration.visibility = View.VISIBLE
                binding.tvVnNarration.text = current.text.trim()
                binding.tvVnDialogue.visibility = View.GONE
                binding.tvVnRoleName.text = "旁白"
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
        val raw: List<Segment> = splitSegments(p.text)
        // 解析每个对话段的说话人。解析不出的**保持对话类型**（不降级成旁白）：
        // 对话永远按对话显示（台词区+打字机），角色名显示"未知"，立绘保持现状。
        // 之前"降级为旁白"导致说话人匹配失败时整段全按旁白念、对话感全丢。
        paraSegments = raw.map { s ->
            if (s.type == SegType.DIALOG) {
                val sp = lookupSpeakerText(s.text)
                if (sp.isBlank()) s else s.copy(speaker = sp)
            } else s
        }
        // 诊断日志：分割结果（类型/区间/说话人），用于验证真实路径
        io.legado.app.constant.AppLog.putDebug(
            "舞台诊断: 分割结果 " + paraSegments.joinToString(" | ") {
                "${if (it.type == SegType.DIALOG) "对话" else "旁白"}" +
                        "(${it.start}-${it.end})" +
                        (if (it.type == SegType.DIALOG) "说话人=${it.speaker.ifBlank { "未知" }}" else "") +
                        " 文本=${it.text.trim().take(12)}"
            }
        )
        val dialogSegs = paraSegments.filter { it.type == SegType.DIALOG }

        // ---- 初始态（段落刚开始，朗读位置≈0）----
        // 只显示「段首片段」：是旁白则旁白框整段顶上；是对话则等待触发。
        // 后续片段由 onSubtitlePos 统一路由（读到哪个显示哪个），
        // 不再用 takeWhile——中间/段尾旁白不会被丢弃。
        val first = paraSegments.firstOrNull()
        if (first != null && first.type == SegType.NARRATION && first.text.isNotBlank()) {
            currentSpeaker = "旁白"
            binding.tvVnNarration.visibility = View.VISIBLE
            binding.tvVnNarration.text = first.text.trim()
            binding.tvVnDialogue.visibility = View.GONE
            binding.tvVnRoleName.text = "旁白"
            switchPortrait("旁白")
        } else if (first != null && first.type == SegType.DIALOG) {
            binding.tvVnNarration.visibility = View.GONE
            binding.tvVnNarration.text = ""
            binding.tvVnDialogue.visibility = View.VISIBLE
            binding.tvVnRoleName.text = ""
            // 段首就是对话：等触发（onSubtitlePos），立绘保持占位
        } else {
            // 空片段兜底：按旁白
            currentSpeaker = "旁白"
            binding.tvVnRoleName.text = "旁白"
            binding.tvVnDialogue.visibility = View.GONE
            switchPortrait("旁白")
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
        // 触发与显示统一用同一判据：朗读位置 pos.end。
        // 之前触发用 pos.end、显示用 pos.start——边界碎片上两者不一致：
        // 对话刚触发（立绘切到说话人），显示路由又按碎片起点判"当前在旁白"，
        // 立刻覆盖回旁白立绘/旁白框——表现为立绘闪一下又回旁白、文字按旁白显示。
        // 统一判据后不可能自己和自己竞争。
        paraSegments.filter {
            it.type == SegType.DIALOG && !it.triggered && it.start < pos.end
        }.forEach { triggerDialog(it) }
        // 显示定位：朗读位置所在的片段（最后一个 start < pos.end 的片段）
        val current = paraSegments.lastOrNull { it.start < pos.end } ?: return
        // 诊断日志：路由决策（读到哪个片段、显示到哪个框）
        io.legado.app.constant.AppLog.putDebug(
            "舞台诊断: 路由 pos=(${pos.start}-${pos.end}) 当前片段=" +
                    "${if (current.type == SegType.DIALOG) "对话" else "旁白"}" +
                    "(${current.start}-${current.end}) 字幕=${pos.text.take(12)}"
        )
        if (current.type == SegType.DIALOG) {
            // ---- 朗读位置在对话内：台词区打字机（已触发），旁白框 GONE ----
            if (!current.triggered) triggerDialog(current)
        } else {
            // ---- 朗读位置在旁白内：旁白框显示，台词区 GONE ----
            // 立绘：之前不是旁白态才切换（先判断后更新，顺序不能反）
            if (currentSpeaker != "旁白") switchPortrait("旁白")
            // 同步更新 currentSpeaker（点击立绘打开档案要用）：
            // 之前只在 triggerDialog 更新，旁白期间残留上一个说话人，
            // 点旁白立绘会打开上一个角色的档案
            currentSpeaker = "旁白"
            binding.tvVnNarration.visibility = View.VISIBLE
            // 碎片可能横跨「对话尾→旁白头」：截掉碎片里属于对话的部分，
            // 只显示旁白头的文字（避免对话尾巴以旁白形式重复显示）
            val narrationText = if (pos.start < current.start) {
                pos.text.substring((current.start - pos.start).coerceAtLeast(0))
            } else pos.text
            binding.tvVnNarration.text = narrationText
            binding.tvVnDialogue.visibility = View.GONE
            binding.tvVnRoleName.text = "旁白"
        }
    }

    /** 触发一个对话段：立绘切到说话人 + 启动打字机（28ms/字，节奏均匀） */
    private fun triggerDialog(seg: Segment) {
        seg.triggered = true
        currentSpeaker = seg.speaker
        // 说话人解析失败时显示"未知"（对话仍按对话显示，不降级）
        binding.tvVnRoleName.text = seg.speaker.ifBlank { "未知" }
        // 互斥（对话态）：旁白框 GONE、台词区 VISIBLE
        binding.tvVnNarration.visibility = View.GONE
        binding.tvVnNarration.text = ""
        binding.tvVnDialogue.visibility = View.VISIBLE
        // 说话人未知：不切立绘（保持现状），只显示对话
        if (seg.speaker.isNotBlank()) {
            switchPortrait(seg.speaker)
        }
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
            // 只认中文全角双引号：开“ 闭”是两个不同字符，开闭明确无二义性
            val isOpen = c == '“'
            val isClose = c == '”'
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
        if (title.isBlank()) {
            io.legado.app.constant.AppLog.putDebug(
                "舞台诊断: 章节标题为空，无法匹配说话人（对话前20字=${dialogText.take(20)}）"
            )
            return ""
        }
        val role = ScriptCharacterRepository.matchChapterRoleByText(
            bookName, title, dialogText.trim()
        )
        val result = if (role != null && !role.isNarration) role.name else ""
        if (result.isBlank()) {
            io.legado.app.constant.AppLog.putDebug(
                "舞台诊断: 说话人匹配失败 书=$bookName 章=$title 对话前20字=${dialogText.take(20)}" +
                        "（缓存命中=${role != null}，isNarration=${role?.isNarration}）"
            )
        }
        return result
    }

    // ------------------------------------------------------------------
    // 立绘联动
    // ------------------------------------------------------------------

    private fun switchPortrait(speaker: String) {
        portraitPollJob?.cancel()
        portraitPollJob = null
        if (speaker == "旁白") {
            // 旁白不显示立绘：仅预留 110dp 占位（保持演出区高度恒定、不跳变），
            // 不再生成"说书人"形象
            portraitAvailable = false
            binding.ivVnPortrait.visibility = View.INVISIBLE
            return
        }
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
        AiProfileService.ensureProfileAndAvatar(
            bookName, speaker,
            ReadBook.durChapterIndex,
            ReadBook.curTextChapter?.chapter?.title.orEmpty()
        )
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
        root.setOnTouchListener { v, event ->
            // 事件落在立绘区域内：放行给立绘的 click（打开角色档案），
            // 不进入舞台手势（否则 setClickListener 永远收不到事件）
            val portrait = binding.ivVnPortrait
            if (portrait.visibility == View.VISIBLE && isInsideView(portrait, event)) {
                return@setOnTouchListener false
            }
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

    /** 事件坐标是否落在视图 bounds 内（窗口坐标系） */
    private fun isInsideView(view: View, event: android.view.MotionEvent): Boolean {
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        val x = event.rawX - loc[0]
        val y = event.rawY - loc[1]
        return x >= 0 && x <= view.width && y >= 0 && y <= view.height
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
