package io.legado.app.ui.book.read.config

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.app.AlertDialog
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogVnStageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.loadGif
import io.legado.app.help.glide.VnCompositeTransform
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.AiProfileService
import io.legado.app.model.ReadBook
import io.legado.app.model.VnParagraph
import io.legado.app.tts.voice.ScriptCharacterRepository
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.sysScreenOffTime
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

    /**
     * 触发提前量（字符数）：立绘需要解码（IO）甚至首次 AI 生成，
     * 等朗读位置真正越过对话起点才切换会来不及，表现为立绘晚半拍才出现。
     * 统一把「触发 / 显示」判据前移这么多字符，让立绘与台词面板提前就位。
     */
    private val triggerLookaheadChars = 4

    /** 单条对话的长度上限（字符）：与脚本同款，防止引号缺失时把整段吞成一句对话 */
    private val maxDialogLen = 1000

    /**
     * 拟声/强调的引号内容上限（字符）：纯汉字、无标点且不超过这个长度时视为
     * 拟声词或强调（如「只听“砰”的一声」），降级为旁白，不查说话人、不生成立绘。
     * 与内置脚本终局过滤 `/“(<<[^<>]+>>)?([\u4E00-\u9FFF]{1,15})”/g` 同款。
     */
    private val shortInterjectionMax = 15

    /**
     * 黑括号 `【】` 片段的说话人。
     * 与内置朗读脚本 SPECIAL_ROLES 里的「【】括号发音人 → 系统」一致。
     */
    private val systemSpeaker = "系统"

    /** 上一段是否以未闭合的 `“` 结尾（作者把一句对话拆成了多个段落） */
    private var pendingDialog = false

    /** 未闭合对话的说话人，供下一个续段继承 */
    private var pendingSpeaker = ""

    /** 上次处理段落所属章节标题：切章后不延续上一段的未闭合对话 */
    private var lastChapterTitle: String? = null

    /**
     * 背景来源是否为「AI 生图」（与大图/小图界面同源）。
     * 由「启用AI生图」开关（[AppConfig.readAloudAiImage]）决定：
     * - 开启 → 背景用 [AiImageGenerator] 的图，随 [EventBus.AI_IMAGE_CHANGED] 切换，
     *          与大图/小图界面完全同一套来源与切换方式；
     * - 关闭 → 背景用 [VnBackgroundManager] 的场景背景（原逻辑，不动）。
     * 进入界面时判定一次；中途改开关需重进本界面生效。
     */
    private var useAiImageBackground = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 屏幕超时设置（毫秒），跟随阅读页"屏幕超时"设置；< 0 表示常亮 */
    private var screenTimeOut: Long = 0
    private val screenOffHandler by lazy { Handler(Looper.getMainLooper()) }
    private val screenOffRunnable by lazy { Runnable { keepDialogScreenOn(false) } }

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
        // 跟随阅读页「屏幕超时」设置（含常亮），应用到本视觉小说界面的 window
        upScreenTimeOut()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.rootView.setBackgroundColor(Color.BLACK)
        binding.tvVnDialogue.movementMethod = ScrollingMovementMethod()
        // 背景来源：AI 生图开关开启 → 与大图/小图界面同源（AiImageGenerator + AI_IMAGE_CHANGED）；
        // 关闭 → 场景背景管理器（原逻辑，不动）
        useAiImageBackground = AppConfig.readAloudAiImage
        if (useAiImageBackground) {
            showAiGeneratedBackground()
        } else {
            // 背景管理器（object 单例）：绑定显示目标；渲染交给本界面的两层背景加载，
            // 以统一支持「背景呈现方式」（裁切 / 上下补模糊 / 贴顶补模糊）。
            VnBackgroundManager.onSceneFile = { loadBackgroundImage(file = it) }
            VnBackgroundManager.onBuiltin = { loadBackgroundImage(resId = R.drawable.vn_default_background) }
            VnBackgroundManager.attach(binding.ivVnBackground)
        }

        // 中途进入：立刻渲染当前朗读段落（整段按已读完状态显示），
        // 随后段落切换事件自然接管。否则要等下一段开始才有文字。
        renderCurrentParagraphOnEnter()

        // 手势（与大图界面一致）：单击左/右 1/3 上一段/下一段，
        // 中 1/3 暂停/继续朗读；从上向下滑动退出本界面
        setupGestures()

        // 点立绘 → 打开该角色的角色档案（可补全信息/编辑提示词/重新生成立绘）。
        // 仅对话/系统角色有立绘可点；旁白态立绘为 INVISIBLE 占位，点击不生效。
        binding.ivVnPortrait.setOnClickListener {
            val speaker = currentSpeaker.ifBlank { "旁白" }
            CharacterProfileDialog.newInstance(bookName, speaker)
                .show(parentFragmentManager, "characterProfileDialog")
        }

        // 长按立绘区 → 固定打开「系统」档案。
        // 这是正文里没有【】时编辑系统立绘的唯一入口：立绘 VISIBLE 时由 ImageView
        // 消费长按，INVISIBLE 时（旁白态）落到外层 FrameLayout，两处都挂保证任何时候
        // 都进得去。档案弹窗支持无档案时直接出图，也可编辑人设后重新生成。
        val openSystemProfile: () -> Boolean = {
            CharacterProfileDialog.newInstance(bookName, systemSpeaker)
                .show(parentFragmentManager, "characterProfileDialog")
            true
        }
        binding.ivVnPortrait.setOnLongClickListener { openSystemProfile() }
        binding.flVnPortrait.setOnLongClickListener { openSystemProfile() }
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
        // AI 生图变化：与大图/小图界面同一套事件源驱动背景切换。
        // 只有 AI 生图开关开启时消费；关闭时背景归 VnBackgroundManager 管，这里不插手。
        observeEvent<String>(EventBus.AI_IMAGE_CHANGED) { imagePath ->
            if (!useAiImageBackground) return@observeEvent
            if (imagePath.isBlank()) return@observeEvent
            val f = File(imagePath)
            if (f.exists()) loadBackgroundImage(file = f)
        }
    }

    // ------------------------------------------------------------------
    // 背景：AI 生图（与大图/小图界面同源）
    // ------------------------------------------------------------------

    /**
     * 取最新一张 AI 生图作为背景（复刻 FullscreenAiImageDialog 的 AI 分支）。
     * 暂无图时用内置通用背景兜底，不黑屏。
     */
    private fun showAiGeneratedBackground() {
        val f = AiImageGenerator.getLastGeneratedImage()
        if (f != null && f.exists()) {
            loadBackgroundImage(file = f)
        } else {
            loadBackgroundImage(resId = R.drawable.vn_default_background)
        }
    }

    /**
     * 加载背景图并按 [AppConfig.vnBackgroundMode] 切换呈现方式。
     * - 0 左右裁切铺满全屏（centerCrop，单次加载）
     * - 1 不裁左右（fitCenter），上下镜像带：接缝清晰、向边缘渐隐透出模糊 → 越来越模糊
     * - 2 贴顶（fitStart），仅下方镜像带
     * 模式 1/2 用 [VnCompositeTransform] 把「全屏模糊底 + 清晰主图 + 镜像带」合成到
     * 一张 Bitmap（一个 drawable），切换时主图与上下拼接同帧出现，不再先出主图再补拼接。
     * GIF 模糊成本高且镜像意义不大，模式 1/2 退化为仅清晰层按模式缩放。
     */
    private fun loadBackgroundImage(file: File? = null, resId: Int = 0) {
        if (!isAdded || activity?.isDestroyed == true) return
        val mode = AppConfig.vnBackgroundMode
        val isGif = file != null && isGifFile(file)
        val sharp = binding.ivVnBackground

        // 0 / GIF：左右裁切铺满（单次加载，立即显示）
        if (isGif || mode == 0) {
            sharp.scaleType = ImageView.ScaleType.CENTER_CROP
            if (isGif) {
                sharp.loadGif(file!!, sharp.scaleType)
            } else {
                val req = if (resId != 0) Glide.with(sharp.context).load(resId)
                else Glide.with(sharp.context).load(file)
                req.placeholder(sharp.drawable).into(sharp)
            }
            return
        }

        // 模式 1/2：单张合成图（主图 + 全屏模糊底 + 镜像带），一次加载同帧呈现
        sharp.scaleType = ImageView.ScaleType.FIT_XY
        val fitMode = if (mode == 2) 1 else 0
        val req = if (resId != 0) Glide.with(sharp.context).load(resId)
        else Glide.with(sharp.context).load(file)
        req.transform(VnCompositeTransform(top = mode == 1, bottom = true, fitMode = fitMode))
            .placeholder(sharp.drawable)
            .into(sharp)
    }

    /** 判断文件是否为 GIF 动图（按扩展名，不区分大小写） */
    private fun isGifFile(file: File): Boolean =
        file.extension.equals("gif", ignoreCase = true)

    // ------------------------------------------------------------------
    // 段落处理
    // ------------------------------------------------------------------

    /** 片段类型：引号外旁白 / 引号内对话 / 黑括号【】内系统消息 */
    private enum class SegType { NARRATION, DIALOG, SYSTEM }

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
            // 用统一路由定位当前片段（与 onSubtitlePos 同款判据：朗读位置 + 提前量）：
            // 朗读位置在对话内 → 触发对话；在旁白内 → 旁白框整段
            val current = currentSegment(readChars)
            if (current != null && current.type != SegType.NARRATION) {
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

        // 切章：上一段属于上一章，未闭合的对话不能延续到本章
        val chapterTitle = ReadBook.curTextChapter?.chapter?.title.orEmpty()
        if (chapterTitle != lastChapterTitle) {
            lastChapterTitle = chapterTitle
            pendingDialog = false
            pendingSpeaker = ""
        }

        // ---- 跨段延续：作者把一句对话拆成多个段落时，中间的段落可能没有引号 ----
        // 上一段留下未闭合的 “ → 本段是它的续段
        val carried = pendingDialog
        val carriedSpeaker = pendingSpeaker
        // 本段是否以未闭合的 “ 结尾：最后一个 “ 在最后一个 ” 之后。
        // （脚本没有这一步，靠缓存按行匹配；舞台是连续处理段落的，
        //   直接看引号状态更可靠，也不依赖缓存是否已生成）
        pendingDialog = p.text.lastIndexOf('“') > p.text.lastIndexOf('”')
        pendingSpeaker = ""

        // 完全无引号：可能是被拆开的对话的续段（脚本 handleNoQuoteText 同款）。
        // 先拿整段文本去章节缓存反查归属，命中即说明这句确实是某人说的。
        val noQuote = !p.text.contains('“') && !p.text.contains('”')
        val noQuoteSpeaker = if (noQuote && p.text.isNotBlank()) {
            lookupSpeakerText(p.text).takeIf { it.isNotBlank() }.orEmpty()
        } else ""

        paraSegments = if (
            noQuote && (noQuoteSpeaker.isNotBlank() || (carried && carriedSpeaker.isNotBlank()))
        ) {
            // 整段按对话显示：优先用缓存查到的说话人，其次继承上一段未闭合的对话
            listOf(
                Segment(
                    0, p.text.length, SegType.DIALOG, p.text,
                    noQuoteSpeaker.ifBlank { carriedSpeaker }
                )
            )
        } else {
            // 解析每个对话段的说话人。解析不出的**保持对话类型**（不降级成旁白）：
            // 对话永远按对话显示（台词区+打字机），角色名显示"未知"，立绘保持现状。
            // 之前"降级为旁白"导致说话人匹配失败时整段全按旁白念、对话感全丢。
            raw.map { s ->
                when (s.type) {
                    // 系统消息：说话人固定，不需要按文本反查归属
                    SegType.SYSTEM -> s.copy(speaker = systemSpeaker)
                    SegType.DIALOG -> {
                        val sp = lookupSpeakerText(s.text)
                        when {
                            sp.isNotBlank() -> s.copy(speaker = sp)
                            // 续段且查不到归属：继承上一段未闭合对话的说话人
                            carried && carriedSpeaker.isNotBlank() -> s.copy(speaker = carriedSpeaker)
                            else -> s
                        }
                    }
                    else -> s
                }
            }
        }
        // 记下本段最后一位说话人，供下一段继承
        pendingSpeaker = paraSegments.lastOrNull {
            it.type == SegType.DIALOG && it.speaker.isNotBlank()
        }?.speaker.orEmpty()
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
        } else if (first != null && first.type != SegType.NARRATION) {
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
        // 背景预分析调度 + 查记录切场景（带段落索引）。
        // AI 生图开关开启时背景由 AI_IMAGE_CHANGED 驱动，不再走场景背景调度，
        // 避免两套体系同时生成、浪费请求
        if (!useAiImageBackground) {
            VnBackgroundManager.onParagraphIndex(p.index, p.text)
        }
    }

    /**
     * 按朗读位置定位「当前片段」，带 [triggerLookaheadChars] 提前量。
     *
     * 只**提前进入对话**，不提前离开对话：
     * 立绘/台词面板提前 [triggerLookaheadChars] 个字符就位（补偿解码与生成耗时），
     * 但对话末尾的最后几个字符仍归该对话——否则台词会被提前掐掉、立绘提前消失，
     * 出现「话还没念完立绘先没了」。
     */
    private fun currentSegment(readPos: Int): Segment? {
        val strict = paraSegments.lastOrNull { it.start < readPos }
        val ahead = paraSegments.lastOrNull { it.start < readPos + triggerLookaheadChars }
        if (strict == null) return ahead
        if (ahead == null || ahead.start <= strict.start) return strict
        // 对话与系统消息都提前进入（都要先切立绘）；旁白不提前
        return if (ahead.type != SegType.NARRATION) ahead else strict
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
        // 触发与显示统一用同一判据：朗读位置 pos.end（+ 提前量）。
        // 之前触发用 pos.end、显示用 pos.start——边界碎片上两者不一致：
        // 对话刚触发（立绘切到说话人），显示路由又按碎片起点判"当前在旁白"，
        // 立刻覆盖回旁白立绘/旁白框——表现为立绘闪一下又回旁白、文字按旁白显示。
        // 统一判据后不可能自己和自己竞争。
        // 提前量：立绘解码/生成耗时，前移若干字符触发，避免立绘晚半拍才出现。
        val triggerPos = pos.end + triggerLookaheadChars
        paraSegments.filter {
            it.type != SegType.NARRATION && !it.triggered && it.start < triggerPos
        }.forEach { triggerDialog(it) }
        // 显示定位：朗读位置所在的片段（带提前量，见 currentSegment）
        val current = currentSegment(pos.end) ?: return
        // 诊断日志：路由决策（读到哪个片段、显示到哪个框）
        io.legado.app.constant.AppLog.putDebug(
            "舞台诊断: 路由 pos=(${pos.start}-${pos.end}) 当前片段=" +
                    "${if (current.type == SegType.DIALOG) "对话" else "旁白"}" +
                    "(${current.start}-${current.end}) 字幕=${pos.text.take(12)}"
        )
        if (current.type != SegType.NARRATION) {
            // ---- 朗读位置在对话/系统消息内：台词区打字机（已触发），旁白框 GONE ----
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
        // 不预设高度：让框随打字机逐字显示自然增高。
        // 演出区是 layout_gravity="bottom"，内容变高时底部不动、顶部向上推，
        // 就是"一行一行向上生长"的效果；下限 4 行、上限 8 行由 XML 约束。
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
     * 把段落按**中文全角双引号**切成交替的旁白/对话片段序列（带 raw 段内字符区间）。
     *
     * 规则与内置朗读脚本「(脚本)猫箱-VV(混元3+Z)」保持一致——
     * [ScriptCharacterRepository.matchChapterRoleByText] 是按**对话文本**反查归属的，
     * 只有切出来的文本与脚本写入章节缓存的 dialogText 逐字一致才能命中说话人。
     *
     * 1. 只认 `“`(U+201C) / `”`(U+201D)：开闭是两个不同字符，无二义性；
     * 2. 一个 `“` 就是一段对话：再遇到 `“` 直接关闭上一段（等价脚本预处理
     *    `replace(/(“[^“”\n]*)(“)/g, "$1”$2")`），因此**对话内容里不会包含引号**，
     *    嵌套引号不再把对话截断成半截；
     * 3. 整段**没有 `“` 只有 `”`**（对话被作者拆成多个段落的续段）：
     *    段首 → `”` 判为对话，`”` 之后判为旁白（脚本 handleSpecialQuoteCases 同款）。
     *    本段若存在 `“`，多余的 `”` 仅作边界忽略，不倒推成对话；
     * 4. **正常闭合**的引号对里，纯汉字、无标点且 ≤ [shortInterjectionMax] 字的内容
     *    降级为旁白（拟声/强调，如「只听“砰”的一声」）；被新 `“` 打断或段尾未闭合的
     *    不参与该判定；
     * 5. 单条对话最长 [maxDialogLen] 字；
     * 6. 黑括号 `【…】` 内的内容单独切成 [SegType.SYSTEM]，说话人为 [systemSpeaker]
     *    （与脚本 SPECIAL_ROLES 的「【】括号发音人 → 系统」一致）；
     *    `【` 优先于引号——遇到它就先收尾当前旁白/对话。形如 `【01】` 的纯数字
     *    黑括号是脚本的对话序号标记，按边界吞掉，不当系统消息。
     */
    private fun splitSegments(paragraph: String): List<Segment> {
        val out = ArrayList<Segment>(4)
        val hasOpenQuote = paragraph.indexOf('“') >= 0
        var narrationStart = 0
        var dialogStart = -1
        var systemStart = -1
        var i = 0
        fun addNarration(from: Int, to: Int) {
            if (to > from) out.add(
                Segment(from, to, SegType.NARRATION, paragraph.substring(from, to))
            )
        }
        /** 黑括号【】内的系统消息（说话人固定为 [systemSpeaker]） */
        fun addSystem(from: Int, to: Int) {
            if (to > from) out.add(
                Segment(from, to, SegType.SYSTEM, paragraph.substring(from, to))
            )
        }
        /**
         * @param closed 是否被 `”` 正常闭合。只有正常闭合的引号对才做拟声降级——
         *               被新 `“` 打断或段尾未闭合的都是真正的对话，不能因为短就降级
         *               （脚本终局过滤正则同样要求 `“`…`”` 完整配对才命中）。
         */
        fun addDialog(from: Int, to: Int, closed: Boolean) {
            val end = minOf(to, from + maxDialogLen)
            if (end <= from) return
            val text = paragraph.substring(from, end)
            // 拟声/强调（如「只听“砰”的一声」）不当对话：不查说话人、不生成立绘
            val type =
                if (closed && isShortInterjection(text)) SegType.NARRATION else SegType.DIALOG
            out.add(Segment(from, end, type, text))
        }
        while (i < paragraph.length) {
            when (paragraph[i]) {
                '【' -> {
                    if (isSeqMark(paragraph, i)) {
                        // 脚本的对话序号【01】【02】：当边界吞掉，不进正文显示
                        val closeIdx = paragraph.indexOf('】', i)
                        if (closeIdx > 0) {
                            addNarration(narrationStart, i)
                            i = closeIdx
                            narrationStart = closeIdx + 1
                        }
                    } else {
                        // 黑括号优先：先收尾当前旁白/对话，再开系统段
                        if (dialogStart >= 0) {
                            addDialog(dialogStart, i, closed = false)
                            dialogStart = -1
                        } else {
                            addNarration(narrationStart, i)
                        }
                        systemStart = i + 1
                    }
                }
                '】' -> {
                    if (systemStart >= 0) {
                        addSystem(systemStart, i)
                        systemStart = -1
                        narrationStart = i + 1
                    }
                }
                '“' -> {
                    if (dialogStart >= 0) {
                        // 嵌套/连续开引号：直接关闭上一段，再开新段
                        addDialog(dialogStart, i, closed = false)
                    } else {
                        addNarration(narrationStart, i)
                    }
                    dialogStart = i + 1
                }
                '”' -> {
                    if (dialogStart >= 0) {
                        addDialog(dialogStart, i, closed = true)
                        dialogStart = -1
                    } else if (!hasOpenQuote) {
                        // 整段无左引号：承接上一段的对话，段首到此处算对话
                        addDialog(narrationStart, i, closed = true)
                    }
                    narrationStart = i + 1
                }
            }
            i++
        }
        if (systemStart >= 0) {
            // 未闭合的【：其后全部视为系统消息
            addSystem(systemStart, paragraph.length)
        } else if (dialogStart >= 0) {
            // 只有开引号：其后全部视为对话
            addDialog(dialogStart, paragraph.length, closed = false)
        } else if (narrationStart < paragraph.length) {
            addNarration(narrationStart, paragraph.length)
        }
        // 合并相邻旁白段：拟声降级与引号间隙会把一句旁白切成好几块，
        // 不合并的话旁白框会跟着朗读位置一跳一跳地换内容
        val merged = ArrayList<Segment>(out.size)
        for (s in out) {
            val last = merged.lastOrNull()
            if (s.type == SegType.NARRATION && last != null && last.type == SegType.NARRATION) {
                merged[merged.size - 1] =
                    last.copy(end = s.end, text = paragraph.substring(last.start, s.end))
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** 拟声/强调判定：纯汉字、无标点、且不超过 [shortInterjectionMax] 字 */
    private fun isShortInterjection(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > shortInterjectionMax) return false
        return t.all { it in '\u4E00'..'\u9FFF' }
    }

    /**
     * `【` 开头的是否为朗读脚本的对话序号标记（形如 `【01】`）。
     * 脚本 `generateBatchSeqContent` 会给每条对话打序号发给 AI，`cleanDialogText`
     * 里也有 `replace(/【\d+】/g, "")`——这类不是系统消息，必须排除。
     */
    private fun isSeqMark(paragraph: String, openIndex: Int): Boolean {
        var j = openIndex + 1
        if (j >= paragraph.length) return false
        while (j < paragraph.length && paragraph[j].isDigit()) j++
        return j > openIndex + 1 && j < paragraph.length && paragraph[j] == '】'
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
        if (speaker == systemSpeaker) {
            // 系统：只出图、不建档（见 showSystemPortrait 注释）
            showSystemPortrait()
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

    /**
     * 系统立绘：只生成/复用一张图，**不建角色档案**。
     *
     * "系统"不是正文人物——它在 CharacterProfileManager 的 SKIP_NAMES 里，
     * 且没有台词上下文可供 AI 分析，走 [AiProfileService.ensureProfileAndAvatar]
     * 会因建档失败而始终拿不到图。这里直接按角色名出图。
     *
     * 人设编辑与「重新生成立绘」由用户在角色档案弹窗里手工操作：
     * 点舞台上的系统立绘即可进入（[CharacterProfileDialog] 支持无档案时也能出图）。
     */
    private fun showSystemPortrait() {
        val cached = AiImageGenerator.getCachedAvatar(bookName, systemSpeaker)
        if (cached != null) {
            showPortrait(cached)
            return
        }
        // 用 INVISIBLE 而非 GONE：保持 110dp 占位，生成完成前面板位置不跳变
        portraitAvailable = false
        binding.ivVnPortrait.visibility = View.INVISIBLE
        AiImageGenerator.generateCharacterAvatar(
            characterName = systemSpeaker,
            genderAge = "",
            bookName = bookName,
            identity = "系统提示",
            regenerate = false,
            templateId = io.legado.app.help.config.AppConfig.aiPortraitTemplateId,
            onSuccess = { if (currentSpeaker == systemSpeaker) showPortrait(it) },
            onFailure = { }
        )
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

                override fun onLongPress(e: android.view.MotionEvent) {
                    // 长按背景 → 选择呈现方式；滑动已触发退出时不弹
                    if (moved) return
                    showBackgroundModeMenu()
                }

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

    /**
     * 长按背景 → 选择背景呈现方式（持久化，重启不丢）。
     * 三项对应 [AppConfig.vnBackgroundMode]：
     * 0 左右裁切铺满 / 1 不裁左右·上下镜像渐模糊 / 2 贴顶·仅下方镜像渐模糊。
     */
    private fun showBackgroundModeMenu() {
        if (!isAdded) return
        val items = arrayOf(
            "左右裁切（铺满全屏）",
            "不裁左右 · 上下镜像渐模糊",
            "贴顶 · 下方镜像渐模糊"
        )
        val cur = AppConfig.vnBackgroundMode
        AlertDialog.Builder(requireContext())
            .setTitle("背景呈现方式")
            .setSingleChoiceItems(items, cur) { d, which ->
                AppConfig.vnBackgroundMode = which
                d.dismiss()
                reloadBackgroundWithMode()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 切换呈现方式后，按当前背景来源重刷两层背景 */
    private fun reloadBackgroundWithMode() {
        if (useAiImageBackground) {
            showAiGeneratedBackground()
        } else {
            VnBackgroundManager.reshow()
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
        // 清理屏幕超时计时器，避免泄漏；退出后由朗读界面/阅读界面自行管理屏幕状态
        screenOffHandler.removeCallbacks(screenOffRunnable)
    }

    /**
     * 读取「屏幕超时」设置并应用到本视觉小说界面的 window。
     * 与朗读大图界面（FullscreenAiImageDialog）保持一致：常亮保持常亮；
     * 设定时间大于系统息屏时间则在该时间后允许息屏；默认使用系统设置。
     */
    private fun upScreenTimeOut() {
        val keepLightPrefer = getPrefString(PreferKey.keepLight)?.toIntOrNull() ?: 0
        screenTimeOut = keepLightPrefer * 1000L
        screenOffTimerStart()
    }

    private fun screenOffTimerStart() {
        screenOffHandler.post {
            if (screenTimeOut < 0) {
                keepDialogScreenOn(true)
                return@post
            }
            val t = screenTimeOut - requireContext().sysScreenOffTime
            if (t > 0) {
                keepDialogScreenOn(true)
                screenOffHandler.removeCallbacks(screenOffRunnable)
                screenOffHandler.postDelayed(screenOffRunnable, screenTimeOut)
            } else {
                keepDialogScreenOn(false)
            }
        }
    }

    /** 给本 Dialog 的 window 添加/清除保持屏幕常亮标志 */
    private fun keepDialogScreenOn(on: Boolean) {
        val window = dialog?.window ?: return
        val isScreenOn =
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (on == isScreenOn) return
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
