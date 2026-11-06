package io.legado.app.ui.book.read.config

import android.widget.ImageView
import io.legado.app.constant.AppLog
import io.legado.app.model.AiImageGenerator
import io.legado.app.model.CharacterProfileManager
import io.legado.app.model.SceneEffectCue
import io.legado.app.model.SceneEffectKind
import io.legado.app.model.ReadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 视觉小说舞台：场景背景管理器。
 *
 * 架构照抄 AI 音效（BaseReadAloudService.scheduleAiEffects）的**预分析**模式：
 *
 * 1. **区间划分**：区间 = 从某段起累计约 [RANGE_CHARS]（400 字）覆盖的段落，
 *    边界永远落在段落结尾（字数只是目标值，实际略多）。
 * 2. **永远领先一个区间**：朗读开始就分析「当前区间 + 下一区间」；
 *    读完当前区间时，下一区间的结果早已算好——背景切换永远不迟到。
 * 3. **AI 按序号输出**：prompt 给 AI 带序号的段落列表，要求输出 JSON 数组
 *    [{"para":序号,"scene":"场景名"}]，只对**发生场景变化**的段落给条目。
 * 4. **记录缓存**（bookUrl/chapterIndex 维度）：段落索引 → 场景名；
 *    已有记录直接复用，不再问 AI。
 * 5. **切换规则**：段落推进时查记录，记录场景 ≠ 当前显示场景才换图。
 *    几段一切/长段落一切由 AI 判断自然决定（同场景段落不产生条目）。
 *
 * 背景图全局共用：缓存命中（cacheDir/vn_backgrounds/<类别>.jpg）直接显示；
 * 未命中 AI 生成一次并缓存（生成期间保持当前背景，不闪黑）。
 */
object VnBackgroundManager {

    /** 场景背景缓存目录（全局共用，不按书区分） */
    private const val DIR_NAME = "vn_backgrounds"

    /** 记录文件名（bookUrl/chapterIndex 维度，存 cacheDir/vn_backgrounds/records/） */
    private const val RECORDS_DIR = "vn_scene_records"

    /**
     * 预置场景类别（4 字以上，按「风格 × 场地」细分，AI 从这些名字里选）。
     * 细分原则：同一场所在不同时代/世界观下是不同类别（如古代厨房 ≠ 现代厨房），
     * 避免古风小说里出现现代物件、科幻小说里出现古典庭院的违和感。
     */
    val SCENE_CATEGORIES: List<String> = listOf(
        // ---- 古代/古风 ----
        "古代宫殿大殿", "古代皇家园林", "古代庭院深深", "古代客栈大堂", "古代市集街道",
        "古代书房雅室", "古代闺房卧室", "古代厨房灶房", "古代府衙公堂", "古代军营帐内",
        "古代城楼城墙", "古代酒楼茶馆", "古代书院学堂", "古代寺庙禅房", "古代地牢牢房",
        "古代马车车内", "古代小桥流水", "古代田园村庄", "古代青山密林", "古代悬崖峭壁",
        "古代瀑布深潭", "古代山洞洞府", "古代雪原荒野", "古代沙漠戈壁", "古代海边疆岸",
        // ---- 修仙/玄幻 ----
        "修仙宗门广场", "修仙洞府内室", "修仙藏经阁楼", "修仙炼丹房室", "修仙秘境深处",
        "修仙祭坛法阵", "修仙山顶云海", "玄幻异空间内", "玄幻魔域深处",
        // ---- 武侠 ----
        "武侠竹林小径", "武侠客栈大堂", "武侠比武擂台", "武侠悬崖绝顶", "武侠丐帮破庙",
        // ---- 现代都市 ----
        "现代客厅公寓", "现代厨房餐厅", "现代卧室套房", "现代书房办公室", "现代公司会议室",
        "现代教室校园", "现代操场校园", "现代图书馆内", "现代医院病房", "现代地铁车厢",
        "现代街道夜景", "现代天台楼顶", "现代商场内部", "现代咖啡馆内", "现代餐厅饭馆",
        "现代工厂车间", "现代实验室研究", "现代电梯轿厢", "现代地下车库", "现代公园长椅",
        // ---- 校园青春 ----
        "校园教室课堂", "校园操场黄昏", "校园图书馆内", "校园天台黄昏", "校园林荫小道",
        // ---- 科幻 ----
        "科幻飞船舱内", "科幻太空站内", "科幻星球地表", "科幻未来都市", "科幻实验室基地",
        "科幻废墟世界", "科幻虚拟空间",
        // ---- 悬疑/恐怖 ----
        "悬疑废弃医院", "悬疑旧宅古屋", "悬疑地下室暗室", "悬疑浓雾街道", "悬疑深夜小巷",
        "悬疑灵堂殡仪", "悬疑深山迷路",
        // ---- 西幻 ----
        "西幻城堡大厅", "西幻酒馆旅店", "西幻村庄农田", "西幻魔法塔内", "西幻森林深处",
        "西幻地下城内", "西幻龙巢深处", "西幻王座宫殿",
        // ---- 通用自然（不区分时代）----
        "自然山林小径", "自然河边溪畔", "自然雪地冰原", "自然雨夜街巷", "自然山谷深处",
        "自然海边沙滩", "自然草原旷野", "自然星空夜空"
    )

    /** 默认场景（无 AI 接口/分析失败时兜底；从类别清单里取一个通用起点） */
    private const val DEFAULT_SCENE = "现代客厅公寓"

    /** 每个 VN 预设场景一个低干扰常驻循环特效；不用雾烟、光束、星芒、萤火等遮挡/闪烁类 */
    private val IDLE_EFFECT_BY_SCENE: Map<String, SceneEffectKind> = mapOf(
        "古代宫殿大殿" to SceneEffectKind.BOKEH,
        "古代皇家园林" to SceneEffectKind.BUTTERFLIES,
        "古代庭院深深" to SceneEffectKind.PETALS,
        "古代客栈大堂" to SceneEffectKind.BOKEH,
        "古代市集街道" to SceneEffectKind.DUST,
        "古代书房雅室" to SceneEffectKind.DUST,
        "古代闺房卧室" to SceneEffectKind.DUST,
        "古代厨房灶房" to SceneEffectKind.BOKEH,
        "古代府衙公堂" to SceneEffectKind.DUST,
        "古代军营帐内" to SceneEffectKind.DUST,
        "古代城楼城墙" to SceneEffectKind.BIRD_FLOCK,
        "古代酒楼茶馆" to SceneEffectKind.BOKEH,
        "古代书院学堂" to SceneEffectKind.DUST,
        "古代寺庙禅房" to SceneEffectKind.BOKEH,
        "古代地牢牢房" to SceneEffectKind.DUST,
        "古代马车车内" to SceneEffectKind.DUST,
        "古代小桥流水" to SceneEffectKind.BUBBLES,
        "古代田园村庄" to SceneEffectKind.DANDELION,
        "古代青山密林" to SceneEffectKind.LEAVES,
        "古代悬崖峭壁" to SceneEffectKind.WIND,
        "古代瀑布深潭" to SceneEffectKind.BUBBLES,
        "古代山洞洞府" to SceneEffectKind.DUST,
        "古代雪原荒野" to SceneEffectKind.SNOW,
        "古代沙漠戈壁" to SceneEffectKind.WIND,
        "古代海边疆岸" to SceneEffectKind.BIRDS_GULL,
        "修仙宗门广场" to SceneEffectKind.BOKEH,
        "修仙洞府内室" to SceneEffectKind.DUST,
        "修仙藏经阁楼" to SceneEffectKind.DUST,
        "修仙炼丹房室" to SceneEffectKind.WIND,
        "修仙秘境深处" to SceneEffectKind.DUST,
        "修仙祭坛法阵" to SceneEffectKind.DUST,
        "修仙山顶云海" to SceneEffectKind.DUST,
        "玄幻异空间内" to SceneEffectKind.DUST,
        "玄幻魔域深处" to SceneEffectKind.WIND,
        "武侠竹林小径" to SceneEffectKind.LEAVES,
        "武侠客栈大堂" to SceneEffectKind.BOKEH,
        "武侠比武擂台" to SceneEffectKind.DUST,
        "武侠悬崖绝顶" to SceneEffectKind.WIND,
        "武侠丐帮破庙" to SceneEffectKind.BIRDS_CROW,
        "现代客厅公寓" to SceneEffectKind.BOKEH,
        "现代厨房餐厅" to SceneEffectKind.BOKEH,
        "现代卧室套房" to SceneEffectKind.DUST,
        "现代书房办公室" to SceneEffectKind.DUST,
        "现代公司会议室" to SceneEffectKind.DUST,
        "现代教室校园" to SceneEffectKind.DUST,
        "现代操场校园" to SceneEffectKind.WIND,
        "现代图书馆内" to SceneEffectKind.DUST,
        "现代医院病房" to SceneEffectKind.DUST,
        "现代地铁车厢" to SceneEffectKind.DUST,
        "现代街道夜景" to SceneEffectKind.BOKEH,
        "现代天台楼顶" to SceneEffectKind.BOKEH,
        "现代商场内部" to SceneEffectKind.BOKEH,
        "现代咖啡馆内" to SceneEffectKind.BOKEH,
        "现代餐厅饭馆" to SceneEffectKind.BOKEH,
        "现代工厂车间" to SceneEffectKind.BOKEH,
        "现代实验室研究" to SceneEffectKind.BUBBLES,
        "现代电梯轿厢" to SceneEffectKind.DUST,
        "现代地下车库" to SceneEffectKind.DUST,
        "现代公园长椅" to SceneEffectKind.LEAVES,
        "校园教室课堂" to SceneEffectKind.DUST,
        "校园操场黄昏" to SceneEffectKind.WIND,
        "校园图书馆内" to SceneEffectKind.DUST,
        "校园天台黄昏" to SceneEffectKind.BOKEH,
        "校园林荫小道" to SceneEffectKind.LEAVES,
        "科幻飞船舱内" to SceneEffectKind.DUST,
        "科幻太空站内" to SceneEffectKind.BOKEH,
        "科幻星球地表" to SceneEffectKind.WIND,
        "科幻未来都市" to SceneEffectKind.BOKEH,
        "科幻实验室基地" to SceneEffectKind.DUST,
        "科幻废墟世界" to SceneEffectKind.DUST,
        "科幻虚拟空间" to SceneEffectKind.DUST,
        "悬疑废弃医院" to SceneEffectKind.DUST,
        "悬疑旧宅古屋" to SceneEffectKind.DUST,
        "悬疑地下室暗室" to SceneEffectKind.DUST,
        "悬疑浓雾街道" to SceneEffectKind.DUST,
        "悬疑深夜小巷" to SceneEffectKind.DUST,
        "悬疑灵堂殡仪" to SceneEffectKind.BIRDS_CROW,
        "悬疑深山迷路" to SceneEffectKind.DUST,
        "西幻城堡大厅" to SceneEffectKind.BOKEH,
        "西幻酒馆旅店" to SceneEffectKind.BOKEH,
        "西幻村庄农田" to SceneEffectKind.DANDELION,
        "西幻魔法塔内" to SceneEffectKind.DUST,
        "西幻森林深处" to SceneEffectKind.BUTTERFLIES,
        "西幻地下城内" to SceneEffectKind.DUST,
        "西幻龙巢深处" to SceneEffectKind.WIND,
        "西幻王座宫殿" to SceneEffectKind.BOKEH,
        "自然山林小径" to SceneEffectKind.LEAVES,
        "自然河边溪畔" to SceneEffectKind.BUBBLES,
        "自然雪地冰原" to SceneEffectKind.SNOW,
        "自然雨夜街巷" to SceneEffectKind.RAIN,
        "自然山谷深处" to SceneEffectKind.DUST,
        "自然海边沙滩" to SceneEffectKind.BIRDS_GULL,
        "自然草原旷野" to SceneEffectKind.WIND,
        "自然星空夜空" to SceneEffectKind.BOKEH
    )

    /** 区间目标字数（照 AI 音效：先按字数确定覆盖哪几段，边界永远落在段落结尾） */
    private const val RANGE_CHARS = 300

    /** 类别 → 生成中标记（并发去重） */
    private val generating = ConcurrentHashMap<String, Boolean>()

    /** 当前显示的类别 */
    @Volatile
    private var currentScene: String = ""

    /**
     * 背景图就绪回调：交给舞台界面统一做两层渲染（清晰层 + 模糊蒙板层 + 呈现方式）。
     * 由 VnStageDialog 在 attach 前设置，release 时清空，避免持有界面引用。
     */
    var onSceneFile: ((File) -> Unit)? = null

    /** 内置通用背景回调（同样交给舞台界面渲染，以统一呈现方式） */
    var onBuiltin: (() -> Unit)? = null

    /** 当前 VN 预设场景对应的常驻循环特效；主线程回调给 SceneEffectView */
    var onIdleEffect: ((SceneEffectCue) -> Unit)? = null

    @Volatile
    private var currentIdleScene: String = ""

    /** 代次号：界面销毁/重新绑定后让所有排队中的主线程回调失效 */
    @Volatile
    private var idleGeneration: Int = 0

    /** 背景图回调代次：只随 attach/release 变化，不因场景切换误伤背景刷新 */
    @Volatile
    private var viewGeneration: Int = 0

    /** 当前 ImageView 引用（界面销毁即释放） */
    @Volatile
    private var targetView: ImageView? = null

    /** 分析/生成任务 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 预分析调度状态（照抄 AI 音效）----
    /** 待分析区间队列 (起始段, 结束段)，串行处理 */
    private val pendingRanges = ArrayDeque<Pair<Int, Int>>()

    /** 区间覆盖的段落文本（由调用方在 onParagraph 时按段落索引提供） */
    private val paragraphTexts = HashMap<Int, String>()

    /** 当前书/章节（记录缓存维度；跨章节后重置） */
    @Volatile
    private var recordKey: String = ""

    /** 记录缓存：段落索引 → 场景名（本章节内存缓存） */
    private val sceneRecords = HashMap<Int, String>()

    /** 已安排过分析的区间终点（含）。下一个区间从这里 +1 开始 */
    private var scheduledMaxPara = -1

    /** 当前朗读区间 [start, end] */
    private var currentStartPara = -1
    private var currentEndPara = -1

    /** 首次调度是否已完成 */
    private var started = false

    /**
     * 绑定显示目标。每次舞台界面创建时调用。
     * 立即显示内置通用背景兜底（避免进入界面/生成中一片黑）。
     */
    fun attach(view: ImageView) {
        idleGeneration++
        viewGeneration++
        targetView = view
        // 先上内置通用背景，绝不让用户看到黑屏
        showBuiltinBackground()
        // 恢复上次类别（若有缓存图）
        if (currentScene.isNotBlank()) {
            showScene(currentScene)
        }
        emitIdle(if (currentScene.isNotBlank()) currentScene else DEFAULT_SCENE)
    }

    /**
     * 段落朗读回调（每段开始时由 VnStageDialog 调用）。
     * 驱动预分析调度 + 查记录切场景。
     * @param index 段落索引（contentList 下标）
     */
    fun onParagraphIndex(index: Int, text: String) {
        if (index < 0) return
        synchronized(paragraphTexts) { paragraphTexts[index] = text }
        schedule(index)
        applySceneForParagraph(index)
    }

    /** 界面销毁时释放引用（不删缓存与记录） */
    fun release() {
        targetView = null
        onSceneFile = null
        onBuiltin = null
        onIdleEffect = null
        currentIdleScene = ""
        idleGeneration++
        viewGeneration++
    }

    /** 呈现方式切换后，按当前场景/内置背景重绘（回调交给舞台界面做两层渲染） */
    fun reshow() {
        if (currentScene.isNotBlank()) showScene(currentScene)
        else onBuiltin?.invoke()
        emitIdle(if (currentScene.isNotBlank()) currentScene else DEFAULT_SCENE)
    }

    /** 非 VN 界面/开关刚打开时用的默认常驻层 */
    fun defaultIdleCue(): SceneEffectCue = idleCueFor(DEFAULT_SCENE)

    /** 配置变化后强制重发一次当前场景特效，避免 idle 因开关切换丢失 */
    fun refreshIdleEffect() {
        currentIdleScene = ""
        emitIdle(if (currentScene.isNotBlank()) currentScene else DEFAULT_SCENE)
    }

    /** 常驻兜底特效已整个移除：不再按场景下发任何 idle cue */
    @Suppress("UNUSED_PARAMETER")
    private fun emitIdle(scene: String) = Unit

    private fun idleCueFor(scene: String): SceneEffectCue {
        val kind = IDLE_EFFECT_BY_SCENE[scene] ?: SceneEffectKind.BOKEH
        val wind = when (kind) {
            SceneEffectKind.WIND, SceneEffectKind.RAIN, SceneEffectKind.SNOW,
            SceneEffectKind.LEAVES, SceneEffectKind.PETALS, SceneEffectKind.DANDELION,
            SceneEffectKind.BIRD_FLOCK, SceneEffectKind.BIRDS_GULL,
            SceneEffectKind.BIRDS_CROW -> true
            else -> false
        }
        return SceneEffectCue(
            para = -1,
            kind = kind,
            level = 1,
            durationMs = Long.MAX_VALUE,
            wind = wind,
            source = "vn-scene-idle",
            sizeScale = 1.6f
        )
    }

    // ------------------------------------------------------------------
    // 预分析调度（照抄 AI 音效：区间 + 永远领先一个）
    // ------------------------------------------------------------------

    private fun schedule(nowIndex: Int) {
        val book = ReadBook.book ?: return
        val key = "${book.bookUrl}/${ReadBook.durChapterIndex}"
        if (key != recordKey) {
            // 跨章节：重置调度与记录缓存（记录文件按章节落盘，重进还会复用）
            persistRecords()
            recordKey = key
            sceneRecords.clear()
            synchronized(paragraphTexts) { paragraphTexts.clear() }
            started = false
            scheduledMaxPara = -1
            currentStartPara = -1
            currentEndPara = -1
            loadRecords(key)
        }
        when {
            !started -> {
                started = true
                // 首次：当前区间 + 下一区间，一开始就领先一个
                startRangeAt(nowIndex)
                enqueueNextRange()
            }
            nowIndex !in currentStartPara..currentEndPara -> {
                // 离开了当前区间（跳段/回退/正常推进）：从当前位置重开
                startRangeAt(nowIndex)
                enqueueNextRange()
            }
        }
        ensureWorker()
    }

    /** 以 from 为起点开一个新区间并入队（累计 RANGE_CHARS 字，边界落段落结尾） */
    private fun startRangeAt(from: Int) {
        val end = endParagraphByChars(from)
        if (end < from) return
        currentStartPara = from
        currentEndPara = end
        enqueueRange(from, end)
    }

    /** 安排「下一个还没安排过的区间」，使队列始终比朗读领先一个 */
    private fun enqueueNextRange() {
        val start = scheduledMaxPara + 1
        val end = endParagraphByChars(start)
        if (end < start) return
        enqueueRange(start, end)
    }

    private fun enqueueRange(start: Int, end: Int) {
        if (start < 0 || end < start) return
        synchronized(pendingRanges) { pendingRanges.addLast(start to end) }
        if (end > scheduledMaxPara) scheduledMaxPara = end
    }

    /** 从 from 起累计够 RANGE_CHARS 字的段落终点（段落文本由 paragraphTexts 提供） */
    /**
     * 从 from 起累计够 RANGE_CHARS 字的段落终点（照 AI 音效的
     * calculateEndParagraphByCharCount：边界永远落在段落结尾）。
     * 段落文本从 curTextChapter 全章段落取（contentList 同源），
     * 服务推送的 paragraphTexts 优先。
     */
    private fun endParagraphByChars(from: Int): Int {
        if (from < 0) return -1
        val chapter = ReadBook.curTextChapter ?: return -1
        val paragraphs = chapter.paragraphs
        if (paragraphs.isEmpty() || from >= paragraphs.size) return -1
        var acc = 0
        for (i in from until paragraphs.size) {
            val pushed = synchronized(paragraphTexts) { paragraphTexts[i] }
            val len = pushed?.length ?: paragraphs[i].text.length
            if (len <= 0) continue
            acc += len + 1
            if (acc >= RANGE_CHARS) return i
        }
        return paragraphs.lastIndex
    }

    /** 串行 worker：逐个区间调 AI，写记录 */
    private fun ensureWorker() {
        if (workerActive) return
        workerActive = true
        scope.launch {
            try {
                while (true) {
                    val range = synchronized(pendingRanges) {
                        if (pendingRanges.isEmpty()) null else pendingRanges.removeFirst()
                    } ?: break
                    runCatching { analyzeRange(range.first, range.second) }
                }
            } finally {
                workerActive = false
            }
        }
    }

    @Volatile
    private var workerActive = false

    /**
     * 分析一个区间：prompt 给 AI 带序号的段落列表，
     * 要求输出 [{"para":序号,"scene":"场景名"}]——只对发生场景变化的段落给条目。
     * 已有记录的段落跳过（复用，不再问 AI）。
     */
    private fun analyzeRange(start: Int, end: Int) {
        // 区间段落文本：curTextChapter 全章段落优先（contentList 同源，
        // 未读段落也有文本——这是预分析能提前的关键），服务推送的兜底
        val chapter = ReadBook.curTextChapter
        val chapterParas = chapter?.paragraphs
        fun textOf(i: Int): String? {
            val pushed = synchronized(paragraphTexts) { paragraphTexts[i] }
            if (pushed != null) return pushed
            return chapterParas?.getOrNull(i)?.text
        }
        val todo = ArrayList<Pair<Int, String>>()
        for (i in start..end) {
            if (sceneRecords.containsKey(i)) continue
            val t = textOf(i) ?: continue
            if (t.isNotBlank()) todo.add(i to t)
        }
        if (todo.isEmpty()) return
        val system = "你是小说场景分类器。给定带序号的小说段落列表，判断每段的场景。"
        val user = buildString {
            append("场景列表（只能从中选择）：").append(SCENE_CATEGORIES.joinToString("、"))
            append("\n\n段落列表：")
            todo.forEach { (i, t) ->
                append("\n【").append(i).append("】").append(t.take(200))
            }
            append("\n\n请输出 JSON 数组，只包含**发生场景变化**的段落（与上一段场景不同才有条目），")
            append("格式：[{\"para\":段落序号,\"scene\":\"场景名\"}]。")
            append("第一个段落总是给出场景（作为起点）。不要输出任何其他文字。")
        }
        val raw = runCatching {
            CharacterProfileManager.chatForExternal(system, user, 512)
        }.getOrNull() ?: return
        // 解析 JSON 数组
        runCatching {
            val arr = com.google.gson.JsonParser.parseString(
                raw.substringAfter('[').let { "[$it" }.substringBeforeLast(']') + "]"
            ).asJsonArray
            for (e in arr) {
                val o = e.asJsonObject ?: continue
                val para = o.get("para")?.asInt ?: continue
                val scene = o.get("scene")?.asString?.trim().orEmpty()
                if (scene in SCENE_CATEGORIES) {
                    sceneRecords[para] = scene
                }
            }
        }.onFailure {
            AppLog.put("视觉小说舞台: 场景记录解析失败: ${it.localizedMessage}")
        }
        // 落盘（增量保存）
        persistRecords()
        // 预生成：本区间识别出的所有场景，图没生的立即生成——
        // 朗读推进到那一段时图早已就绪，不再"识别出来了但图迟到"
        sceneRecords.values.distinct().forEach { ensureSceneImage(it) }
    }

    /** 段落推进时查记录：记录场景 ≠ 当前显示场景才换图 */
    private fun applySceneForParagraph(index: Int) {
        val scene = sceneRecords[index] ?: return
        trySwitchScene(scene, index)
    }

    /**
     * 切换场景（带频率限制）：
     * 1. 冷却：距上次切换不足 [MIN_STAY_PARAGRAPHS] 个段落不切（防 AI 连续
     *    给不同场景条目时背景抖动）；
     * 2. 图未就绪不切：目标场景的图还没生成完时**保持当前显示**（不闪黑、
     *    不切到一半）；图生成完成回调里再补切。
     */
    private fun trySwitchScene(scene: String, index: Int) {
        if (scene == currentScene) return
        // 冷却：至少停留 2 个段落
        if (lastSwitchIndex in 0 until index && index - lastSwitchIndex < MIN_STAY_PARAGRAPHS) {
            return
        }
        // 场景一确定就换常驻特效，不等背景图生成完
        emitIdle(scene)
        // 图未就绪：不切显示（保持当前图），等生成完成回调补切
        val f = sceneFile(scene)
        if (!f.isFile || f.length() == 0L) {
            ensureSceneImage(scene)
            pendingScene = scene
            return
        }
        currentScene = scene
        lastSwitchIndex = index
        pendingScene = ""
        AppLog.putDebug("视觉小说舞台: 场景 → $scene（第 $index 段）")
        showScene(scene)
    }

    /** 切换冷却：最短停留段落数 */
    private const val MIN_STAY_PARAGRAPHS = 2

    /** 上次切换发生的段落索引 */
    private var lastSwitchIndex = -1

    /** 图生成中等待切换的目标场景（生成完成回调补切） */
    @Volatile
    private var pendingScene: String = ""

    // ------------------------------------------------------------------
    // 记录持久化（bookUrl/chapterIndex 维度）
    // ------------------------------------------------------------------

    private fun recordFile(key: String): File =
        File(File(splitties.init.appCtx.cacheDir, RECORDS_DIR), "$key.json")

    private fun loadRecords(key: String) {
        runCatching {
            val f = recordFile(key)
            if (!f.isFile) return
            val arr = com.google.gson.JsonParser.parseString(f.readText()).asJsonArray
            for (e in arr) {
                val o = e.asJsonObject ?: continue
                val para = o.get("para")?.asInt ?: continue
                val scene = o.get("scene")?.asString?.trim().orEmpty()
                if (scene in SCENE_CATEGORIES) sceneRecords[para] = scene
            }
        }
    }

    private fun persistRecords() {
        if (recordKey.isBlank()) return
        runCatching {
            val f = recordFile(recordKey)
            f.parentFile?.mkdirs()
            val arr = com.google.gson.JsonArray()
            sceneRecords.forEach { (para, scene) ->
                val o = com.google.gson.JsonObject()
                o.addProperty("para", para)
                o.addProperty("scene", scene)
                arr.add(o)
            }
            f.writeText(arr.toString())
        }
    }

    // ------------------------------------------------------------------
    // 图片获取与显示
    // ------------------------------------------------------------------

    private fun sceneFile(scene: String): File =
        File(File(splitties.init.appCtx.cacheDir, DIR_NAME), "$scene.jpg")

    /** 确保该类别的背景图存在：缓存命中直接返回；未命中 AI 生成一次 */
    private fun ensureSceneImage(scene: String) {
        val f = sceneFile(scene)
        if (f.isFile && f.length() > 0) return
        if (generating.putIfAbsent(scene, true) != null) return
        scope.launch {
            try {
                val prompt = "小说场景背景图，$scene，无人物，横屏构图，" +
                        "氛围自然，适合作为小说朗读的背景画面"
                val book = ReadBook.book
                val template = AiImageGenerator
                    .getActiveTemplates(book?.name.orEmpty(), book?.bookUrl.orEmpty())
                    .firstOrNull() ?: return@launch
                val result = AiImageGenerator.generateImageDirect(template, prompt)
                result.onSuccess { file ->
                    runCatching {
                        f.parentFile?.mkdirs()
                        file.copyTo(f, overwrite = true)
                        AppLog.putDebug("视觉小说舞台: 已生成背景「$scene」")
                    }
                    if (scene == currentScene) {
                        showScene(scene)
                    } else if (scene == pendingScene) {
                        // 图生成完成：补切（此前因图未就绪保持了旧显示）
                        pendingScene = ""
                        currentScene = scene
                        showScene(scene)
                    }
                }
            } finally {
                generating.remove(scene)
            }
        }
    }

    /** 主线程显示该类别背景；渲染交给舞台界面的 [onSceneFile] 回调（两层 + 呈现方式） */
    private fun showScene(scene: String) {
        emitIdle(scene)
        val f = sceneFile(scene)
        if (!f.isFile || f.length() == 0L) return
        if (targetView == null) return
        val generation = viewGeneration
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (generation != viewGeneration || targetView == null) return@post
            onSceneFile?.invoke(f)
        }
    }

    /** 显示打包进 APK 的内置通用背景（渲染交给舞台界面的 [onBuiltin] 回调） */
    private fun showBuiltinBackground() {
        emitIdle(DEFAULT_SCENE)
        if (targetView == null) return
        val generation = viewGeneration
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (generation != viewGeneration || targetView == null) return@post
            onBuiltin?.invoke()
        }
    }
}
