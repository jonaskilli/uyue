package io.legado.app.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiImageTemplate
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.toc.AiCacheFileUtil
import io.legado.app.help.book.AiImageBookPrefs
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.TtsEngineActivator
import io.legado.app.utils.GSON
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 图片生成器
 * 根据朗读场景（背景音乐切换）生成 AI 图片，与 BGM 同步切换，使用 BGM 收集的文本。
 *
 * 配置以「模板」为单位存储在数据库（AiImageTemplate）。
 * 每本书可绑定一个模板；未显式绑定时使用默认模板。
 * 生图失败时用原提示词重试一次，再失败则放弃本次生图，等待下次触发。
 */
object AiImageGenerator {

    /**
     * 生图专用客户端：继承全局拦截器（含混元太极本地网关），
     * 但放宽超时——全局 okHttpClient 只有 60s，豆包/智谱生图常常更慢。
     */
    private val imageClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(240, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    const val CACHE_DIR_NAME = "ai_images"
    const val DEFAULT_STYLE_SUFFIX = "，超高清8K，有故事感，国风3DCG，皮肤质感真实细腻；柔和柔光打光，电影级细腻光影、皮肤次表面散射，细腻材质质感，氛围感清冷温柔，仙侠唯美，写实渲染，PBR材质，全局光照，极致画质，景深特写，高级冷调色调"
    const val DEFAULT_PROMPT_TEMPLATE = "请根据以下小说片段，从中选取一个最有画面感、最精彩动人的场景，生成一张完整的场景插图：{text}{book}要求：1.必须是一个完整的场景画面（包含环境背景、空间氛围、人物位置关系、互动动作），不要只画人物特写或正面肖像；2.选取主角参与度最高、互动最丰富的瞬间；3.如果片段中有女性角色，优先选取主角与美女角色互动的场景，女性角色娇媚动人、美丽迷人；4.男性角色英姿飒爽、气宇轩昂。{style}"
    const val DEFAULT_NEGATIVE_PROMPT = "凝重的眼神，愁眉苦脸，丑陋，畸形，低质量"
    const val DEFAULT_MODEL_URL = "https://api.siliconflow.cn/v1"
    const val DEFAULT_MODEL_NAME = "Kwai-Kolors/Kolors"

    /** 魔搭 Qwen-Image 2.0 Pro Gradio 兜底接口（无需密钥），当模板三字段全空时使用 */
    const val GRADIO_API_BASE = "https://qwen-qwen-image-2-0-pro.ms.show"
    const val GRADIO_API_SUBMIT = "https://qwen-qwen-image-2-0-pro.ms.show/gradio_api/call/generate_image"
    /** Gradio 兜底默认竖版尺寸（宽×高） */
    const val GRADIO_DEFAULT_WIDTH = 1024
    const val GRADIO_DEFAULT_HEIGHT = 1536

    /** 默认模板的固定 id，首次初始化时使用 */
    const val DEFAULT_TEMPLATE_ID = 1L

    /** 每本书最多绑定的模板数量 */
    const val MAX_BOUND_TEMPLATES = 20

    /** 模板总数上限（新建模板时检查） */
    const val MAX_TEMPLATES = 20

    /** 密钥分隔符，多个密钥用 @@ 分割实现轮换 */
    const val KEY_SEPARATOR = "@@"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 场景缓存：key = bookUrl_chapterIndex_sceneIndex，避免重复生成 */
    private val sceneCache = ConcurrentHashMap<String, File>()

    /**
     * 持久缓存上下文：携带生图时的段落范围与章节信息，用于持久缓存命中查询与落盘。
     * - startPara/endPara: 本次生图覆盖的段落区间
     * - charCount: 当前设定的字数阈值（用于落盘记录，供后续命中推断旧图粗细）
     */
    data class PersistentCacheContext(
        val bookUrl: String,
        val bookName: String,
        val chapterIndex: Int,
        val chapterName: String,
        val startPara: Int,
        val endPara: Int,
        val charCount: Int,
    )

    /** 每个 cacheKey 正在生成的 Job，替代单一 generatingKey，避免并发生成任务互相覆盖导致等待逻辑失效 */
    private val generatingJobs = ConcurrentHashMap<String, Job>()

    private var lastGeneratedImagePath: String? = null

    /**
     * 最近一次生图的章节上下文。用于按「当前章节」汇总已生成的图片，
     * 供手动左右切换图库使用（只读取已存在的图片，不触发生成）。
     */
    private var lastCacheCtx: PersistentCacheContext? = null

    /** 缓存文件名格式: 001_p012_p018_c200.png */
    private val CHAPTER_FILE_RE =
        Regex("(\\d+)_p(\\d+)_p(\\d+)_c(\\d+)\\.png", RegexOption.IGNORE_CASE)

    /**
     * 显示顺序记录（含命中复用的旧图），最新在前，用于左右切换图库。
     * 与 getHistoryImages()（仅新生成图）不同，这里还包含持久缓存命中复用的旧图，
     * 使命中图也能进入左右切换的图库。
     */
    private val displaySequence = mutableListOf<File>()

    var enabled: Boolean
        get() = AppConfig.readAloudAiImage
        set(value) {
            AppConfig.readAloudAiImage = value
            if (!value) {
                generatingJobs.values.forEach { it.cancel() }
                generatingJobs.clear()
                postEvent(EventBus.AI_IMAGE_CHANGED, "")
            }
        }

    // ========== 模板与书籍绑定（支持多模板轮换） ==========

    /**
     * 获取书籍当前生效的模板列表。
     * 若该书籍绑定了多个模板则返回这些模板；未绑定时返回默认模板。
     * 生图时按场景索引轮换使用列表中的模板。
     * 绑定维度为【书名】（换书源不失效），bookUrl 仅用于兼容旧版数据。
     */
    fun getActiveTemplates(bookName: String, bookUrl: String): List<AiImageTemplate> {
        val boundIds = getBoundTemplateIds(bookName, bookUrl)
        if (boundIds.isNotEmpty()) {
            val templates = boundIds.mapNotNull { appDb.aiImageTemplateDao.getById(it) }
                .filter { t ->
                    val allBlank = t.modelUrl.isBlank() && t.modelName.isBlank() && t.modelKey.isBlank()
                    val hasUrlAndName = t.modelUrl.isNotBlank() && t.modelName.isNotBlank()
                    hasUrlAndName || allBlank
                }
            if (templates.isNotEmpty()) return templates
        }
        val default = appDb.aiImageTemplateDao.getDefault() ?: builtinDefaultTemplate()
        return listOf(default)
    }

    /** 获取默认模板 id（数据库中 isDefault=1 的模板），不存在时返回内置默认 id */
    fun getDefaultTemplateId(): Long {
        return appDb.aiImageTemplateDao.getDefault()?.id ?: DEFAULT_TEMPLATE_ID
    }

    /** 读取书籍绑定的模板 id 列表，未绑定返回空列表（表示使用默认模板）。按书名绑定，兼容旧版 bookUrl 数据 */
    fun getBoundTemplateIds(bookName: String, bookUrl: String): List<Long> {
        if (bookName.isBlank() && bookUrl.isBlank()) return emptyList()
        return AiImageBookPrefs.getString(
            PreferKey.aiImageTemplateBindNamePrefix,
            PreferKey.aiImageTemplateBindPrefix,
            bookName,
            bookUrl
        )
            ?.split(",")
            ?.mapNotNull { it.trim().toLongOrNull() }
            ?.filter { it > 0 }
            ?: emptyList()
    }

    /** 设置书籍绑定的模板 id 列表（按书名存储，同时同步旧版 bookUrl key） */
    fun setBoundTemplateIds(bookName: String, bookUrl: String, templateIds: List<Long>) {
        if (bookName.isBlank() && bookUrl.isBlank()) return
        AiImageBookPrefs.putString(
            PreferKey.aiImageTemplateBindNamePrefix,
            PreferKey.aiImageTemplateBindPrefix,
            bookName,
            bookUrl,
            if (templateIds.isEmpty()) null else templateIds.joinToString(",")
        )
    }

    /**
     * 添加单个模板绑定（支持多模板轮换）。
     * @return true 绑定成功；false 已达上限 [MAX_BOUND_TEMPLATES]。
     */
    fun addBoundTemplateId(bookName: String, bookUrl: String, templateId: Long): Boolean {
        val ids = getBoundTemplateIds(bookName, bookUrl).toMutableList()
        if (templateId in ids) return true
        if (ids.size >= MAX_BOUND_TEMPLATES) return false
        ids.add(templateId)
        setBoundTemplateIds(bookName, bookUrl, ids)
        return true
    }

    /** 移除单个模板绑定 */
    fun removeBoundTemplateId(bookName: String, bookUrl: String, templateId: Long) {
        val ids = getBoundTemplateIds(bookName, bookUrl).toMutableList()
        if (ids.remove(templateId)) {
            setBoundTemplateIds(bookName, bookUrl, ids)
        }
    }

    /** 解除书籍所有绑定（回到默认模板），同时清理新旧两套 key */
    fun unbindBook(bookName: String, bookUrl: String) {
        if (bookName.isBlank() && bookUrl.isBlank()) return
        setBoundTemplateIds(bookName, bookUrl, emptyList())
    }

    /**
     * 删除模板时清理所有书籍对该模板的绑定。
     * 绑定 id 存在 SharedPreferences 里，只删数据库会残留失效 id，
     * 导致生图轮换时选中一个已不存在的模板。
     * 需同时清理「按书名」与旧版「按 bookUrl」两套 key。
     * @return 受影响的 key 数量
     */
    fun unbindTemplateFromAllBooks(templateId: Long): Int {
        val all: Map<String, Any?> = appCtx.defaultSharedPreferences.all
        val prefixes = listOf(
            PreferKey.aiImageTemplateBindNamePrefix,
            PreferKey.aiImageTemplateBindPrefix
        )
        val keys = all.keys.filter { key -> prefixes.any { key.startsWith(it) } }
        if (keys.isEmpty()) return 0
        val editor = appCtx.defaultSharedPreferences.edit()
        var affected = 0
        for (key in keys) {
            val raw = all[key] as? String ?: continue
            val ids = raw.split(",").mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }
            val kept = ids.filter { it != templateId }
            if (kept.size == ids.size) continue
            if (kept.isEmpty()) {
                editor.remove(key)
            } else {
                editor.putString(key, kept.joinToString(","))
            }
            affected++
        }
        if (affected > 0) editor.apply()
        return affected
    }

    /**
     * 判断书籍是否绑定了指定模板。按书名判断，兼容旧版 bookUrl 数据。
     * 未显式绑定时视为绑定到默认模板。
     */
    fun isBookBoundToTemplate(bookName: String, bookUrl: String, templateId: Long, isDefault: Boolean): Boolean {
        val ids = getBoundTemplateIds(bookName, bookUrl)
        return if (ids.isEmpty()) {
            isDefault
        } else {
            templateId in ids
        }
    }

    private fun builtinDefaultTemplate(): AiImageTemplate {
        return AiImageTemplate(
            id = DEFAULT_TEMPLATE_ID,
            name = "默认模板",
            modelUrl = DEFAULT_MODEL_URL,
            modelName = DEFAULT_MODEL_NAME,
            imageSize = "784x1168",
            imageStyle = DEFAULT_STYLE_SUFFIX,
            promptTemplate = DEFAULT_PROMPT_TEMPLATE,
            negativePrompt = DEFAULT_NEGATIVE_PROMPT,
            isDefault = true
        )
    }

    // ========== 图片生成 ==========

    /**
     * 解析模板密钥字段，多个密钥用 [KEY_SEPARATOR]（@@）分隔。
     * @return 密钥列表（已去空、去空串），无密钥返回空列表
     */
    fun parseKeys(modelKey: String): List<String> {
        if (modelKey.isBlank()) return emptyList()
        return modelKey.split(KEY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 判断模板是否为魔搭 Gradio 兜底模板（接口地址、模型名、密钥三字段全空）。
     * 此类模板无需密钥，走内置免费魔搭接口，不参与密钥轮换。
     * 统一用此方法判断，避免多处判断不一致。
     */
    fun isGradioFallback(template: AiImageTemplate): Boolean {
        return template.modelUrl.isBlank() && template.modelName.isBlank() && template.modelKey.isBlank()
    }

    /** 密钥槽位：模板 + 具体密钥 + 该密钥在模板中的序号（从0开始） */
    private data class KeySlot(
        val template: AiImageTemplate,
        val key: String,
        val keyIndex: Int,
    )

    /**
     * 构建密钥槽位列表。
     * - 魔搭兜底模板（三字段全空，无需密钥）：占一个空密钥槽位，直接走魔搭接口
     * - 普通模板：每个密钥各占一个槽位，生图时按槽位顺序轮换
     * 生图时按槽位顺序轮换，实现「同一模板多密钥依次用完，再切换到下一个模板」。
     */
    private fun buildKeySlots(templates: List<AiImageTemplate>): List<KeySlot> {
        val slots = mutableListOf<KeySlot>()
        for (template in templates) {
            if (isGradioFallback(template)) {
                // 魔搭兜底模板：无需密钥，占一个空槽位，调用时走 callGradioApi
                slots.add(KeySlot(template, "", 0))
                continue
            }
            val keys = parseKeys(template.modelKey)
            if (keys.isEmpty()) {
                // 有接口地址/模型名但无密钥的模板（部分接口无需鉴权），占一个空槽位
                slots.add(KeySlot(template, "", 0))
            } else {
                keys.forEachIndexed { idx, key ->
                    slots.add(KeySlot(template, key, idx))
                }
            }
        }
        return slots
    }

    /**
     * 获取并递增全局密钥槽位索引（跨场景、跨重试持续轮换）。
     * 每次调用返回当前索引并将索引 +1（对槽位总数取模），保证每次生图请求使用下一个密钥。
     */
    @Synchronized
    private fun nextSlotIndex(totalSlots: Int): Int {
        if (totalSlots <= 0) return 0
        val prefs = appCtx.getSharedPreferences("aiImageKeyRotation", 0)
        val current = prefs.getInt("slotIndex", 0) % totalSlots
        prefs.edit().putInt("slotIndex", (current + 1) % totalSlots).apply()
        return current
    }

    /**
     * 根据场景信息生成 AI 图片
     * @param bookUrl 书籍唯一标识
     * @param chapterIndex 章节索引
     * @param sceneIndex 场景索引（字数驱动的触发序号）
     * @param mood 场景氛围（可空）
     * @param sourceText 场景文本片段
     * @param bookName 书名
     * @param retryCount 重试次数（默认1），每次重试通过 contentProvider 重新收集正文
     * @param contentProvider 重试时重新收集正文的回调（传 null 则用原文本重试）
     */
    fun generateForScene(
        bookUrl: String,
        chapterIndex: Int,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        retryCount: Int = 1,
        contentProvider: (suspend () -> String?)? = null,
        cacheCtx: PersistentCacheContext? = null,
        // 重试时重新计算段落范围并复查持久缓存的回调（基于当前朗读位置），
        // 用于生图失败后段落递增时复用之前的旧图。传 null 则不复查（预生成场景）。
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ) {
        AppLog.put("AI生图[DEBUG] generateForScene入口: sceneIndex=$sceneIndex, enabled=$enabled, sourceTextLen=${sourceText.length}")
        // 记录当前章节上下文，供手动切换图库按本章汇总已生成的图片
        if (cacheCtx != null) lastCacheCtx = cacheCtx
        if (!enabled) {
            AppLog.put("AI生图[DEBUG] generateForScene: enabled=false, 直接返回")
            return
        }
        val templates = getActiveTemplates(bookName, bookUrl)
        AppLog.put("AI生图[DEBUG] generateForScene: templates数量=${templates.size}")
        if (templates.isEmpty()) {
            AppLog.put("AI生图[DEBUG] generateForScene: templates为空, 直接返回")
            return
        }
        // 按 sceneIndex 轮换选择模板，实现多模板依次轮换生图
        val template = templates[sceneIndex % templates.size]
        val useGradioFallback = template.modelUrl.isBlank() && template.modelName.isBlank() && template.modelKey.isBlank()
        if (!useGradioFallback && (template.modelUrl.isBlank() || template.modelName.isBlank())) {
            AppLog.put("AI生图[DEBUG] generateForScene: 模板url或name为空, 直接返回")
            return
        }

        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        // 已缓存则直接通知。
        // 注意：朗读中手动翻页会把 sceneIndex 重置为 0，此时 sceneCache 里可能仍是开始朗读时那张图。
        // 不校验就复用会把刚按段落定位好的图覆盖回旧图，而且这里 return 会跳过后面的
        // 持久缓存命中与真正生成，导致该场景永远停在旧图上。故与 notifyImageIfReady 一样做区间校验。
        val cachedScene = sceneCache[cacheKey]
        if (cachedScene != null && isSceneCacheValid(
                cachedScene,
                cacheCtx?.startPara ?: -1,
                cacheCtx?.endPara ?: -1
            )
        ) {
            AppLog.put("AI生图[DEBUG] generateForScene: 命中内存缓存, 直接通知")
            val cachedPath = cachedScene.absolutePath
            // 同步更新 lastGeneratedImagePath，确保退出重进朗读界面时 getLastGeneratedImage 能取到最新图片
            if (cachedPath.isNotBlank()) {
                lastGeneratedImagePath = cachedPath
            }
            recordDisplay(cachedPath)
            postEvent(EventBus.AI_IMAGE_CHANGED, cachedPath)
            return
        }

        // 持久缓存命中查询：二次朗读同一处时直接复用已保存图片，不再调 API
        if (cacheCtx != null) {
            val hit = AiImagePersistentCache.findHit(
                bookUrl = cacheCtx.bookUrl,
                bookName = cacheCtx.bookName,
                chapterIndex = chapterIndex,
                chapterName = cacheCtx.chapterName,
                startPara = cacheCtx.startPara,
                endPara = cacheCtx.endPara,
                charCount = cacheCtx.charCount,
            )
            if (hit != null) {
                AppLog.put("AI生图[DEBUG] generateForScene: 命中持久缓存, 回填并通知")
                sceneCache[cacheKey] = hit
                lastGeneratedImagePath = hit.absolutePath
                recordDisplay(hit.absolutePath)
                postEvent(EventBus.AI_IMAGE_CHANGED, hit.absolutePath)
                return
            }
        }

        // 已有同名任务在生成（可能是预生成），可靠地 join 等待其完成。
        // 若等待结束后缓存仍为空（预生成失败），由本调用兜底重新生成，避免该场景永久卡死、后续切换全部失效。
        val existing = generatingJobs[cacheKey]
        if (existing != null && existing.isActive) {
            AppLog.put("AI生图[DEBUG] generateForScene: sceneIndex=$sceneIndex 已有任务在跑, join等待")
            scope.launch {
                try {
                    existing.join()
                } catch (_: Exception) {}
                val cached = sceneCache[cacheKey]
                if (cached != null) {
                    // 避免重复通知：若 startSceneGenerate(notifyUi=true) 已通知过同一路径，跳过
                    // 这样当 displayCurrentAiImage 被调用两次时，第二次 join 完成不会重复推送图片
                    if (lastGeneratedImagePath != cached.absolutePath) {
                        AppLog.put("AI生图[DEBUG] generateForScene: join完成, 缓存命中, 通知UI")
                        lastGeneratedImagePath = cached.absolutePath
                        recordDisplay(cached.absolutePath)
                        postEvent(EventBus.AI_IMAGE_CHANGED, cached.absolutePath)
                    } else {
                        AppLog.put("AI生图[DEBUG] generateForScene: join完成, 缓存命中, 但已通知过, 跳过")
                    }
                } else {
                    // 预生成失败或并发请求时旧 job 未生成成功，兜底重新生成当前场景并通知 UI
                    AppLog.put("AI生图[DEBUG] generateForScene: join完成但缓存为空, 兜底重新生成 sceneIndex=$sceneIndex")
                    startSceneGenerate(
                        templates, sceneIndex, mood, sourceText, bookName,
                        cacheKey, retryCount, contentProvider, notifyUi = true, cacheCtx = cacheCtx,
                        cacheCtxProvider = cacheCtxProvider
                    )
                }
            }
            return
        }

        AppLog.put("AI生图[DEBUG] generateForScene: sceneIndex=$sceneIndex 无同名任务, 启动新生成")
        startSceneGenerate(
            templates, sceneIndex, mood, sourceText, bookName,
            cacheKey, retryCount, contentProvider, notifyUi = true, cacheCtx = cacheCtx,
            cacheCtxProvider = cacheCtxProvider
        )
    }

    /**
     * 启动一个场景的生成任务（实时生成与预生成共用）。
     * 用 generatingJobs[cacheKey] 跟踪每个场景的生成 Job，不同场景间互不取消。
     * 注意：本方法不做判重，由调用方保证不重复启动同一场景。
     * @param notifyUi true=生成成功后通知 UI 显示；false=预生成静默不通知
     */
    private fun startSceneGenerate(
        templates: List<AiImageTemplate>,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        cacheKey: String,
        retryCount: Int,
        contentProvider: (suspend () -> String?)?,
        notifyUi: Boolean,
        cacheCtx: PersistentCacheContext? = null,
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ) {
        val job = scope.launch {
            try {
                val genResult = generateWithRetry(
                    templates, sceneIndex, mood, sourceText, bookName, cacheKey, retryCount, contentProvider,
                    cacheCtxProvider = cacheCtxProvider
                )
                if (genResult != null) {
                    var imageFile = genResult.file
                    // 持久化到 AI生图/{书名}/{章节}/ 目录，供二次朗读命中复用
                    // 仅当"本次是真正新生成的图"且当前书开启"保存AI图片"开关时才落盘。
                    // 命中已有持久缓存时（genResult.isHit=true），imageFile 就是章节文件夹里
                    // 已落盘的旧图，若再 saveImage 会按当前偏移区间把同一张图存成新文件名，
                    // 造成章节文件夹里出现两张一模一样的图——这是之前重复的根因。
                    if (!genResult.isHit && cacheCtx != null && AppConfig.isAiImageSaveEnabled(cacheCtx.bookName, cacheCtx.bookUrl)) {
                        AiImagePersistentCache.saveImage(
                            bookName = cacheCtx.bookName,
                            chapterIndex = cacheCtx.chapterIndex,
                            chapterName = cacheCtx.chapterName,
                            startPara = cacheCtx.startPara,
                            endPara = cacheCtx.endPara,
                            charCount = cacheCtx.charCount,
                            sourceText = sourceText,
                            imageFile = imageFile,
                        )
                    }
                    // 写入内部缓存：文件名带段落区间，供后续按段落匹配复用。
                    // 命中持久缓存时不必再存（届时内部缓存会在查找时回落到持久缓存）。
                    if (!genResult.isHit && cacheCtx != null) {
                        AiImagePersistentCache.saveInternalImage(
                            bookUrl = cacheCtx.bookUrl,
                            chapterIndex = cacheCtx.chapterIndex,
                            startPara = cacheCtx.startPara,
                            endPara = cacheCtx.endPara,
                            charCount = cacheCtx.charCount,
                            imageFile = imageFile,
                        )?.let { saved ->
                            // 改用带段落名的文件，删掉临时的 hash 命名文件，
                            // 避免同一张图占两份磁盘配额（trimHistoryIfNeeded 按文件数限长）。
                            // 命中持久缓存时 imageFile 就是持久目录的旧图，绝不能删。
                            val internalRoot = File(appCtx.cacheDir, CACHE_DIR_NAME).absolutePath
                            if (imageFile.absolutePath.startsWith(internalRoot) && imageFile.absolutePath != saved.absolutePath) {
                                runCatching { imageFile.delete() }
                            }
                            imageFile = saved
                        }
                    }
                    sceneCache[cacheKey] = imageFile
                    if (notifyUi) {
                        lastGeneratedImagePath = imageFile.absolutePath
                        recordDisplay(imageFile.absolutePath)
                        postEvent(EventBus.AI_IMAGE_CHANGED, imageFile.absolutePath)
                    } else {
                        AppLog.put("AI生图: 预生成完成 sceneIndex=$sceneIndex")
                    }
                    trimHistoryIfNeeded()
                } else {
                    AppLog.put(
                        "AI生图${if (notifyUi) "" else "预生成"}重试${retryCount}次仍失败，放弃本次生图，等待下次触发"
                    )
                }
            } catch (e: Exception) {
                AppLog.put("AI生图${if (notifyUi) "" else "预生成"}失败: ${e.message}", e)
            } finally {
                generatingJobs.remove(cacheKey)
            }
        }
        generatingJobs[cacheKey] = job
    }

    /**
     * 生图结果包装：file 为最终图片文件；isHit 表示是否命中已有持久缓存。
     * 命中时 file 就是章节文件夹里已落盘的旧图，调用方【禁止】再调用 saveImage，
     * 否则会按当前偏移区间把同一张图又存成新文件名，造成章节文件夹里出现两张。
     */
    private data class GenerateResult(val file: File, val isHit: Boolean)

    /**
     * 支持自定义重试次数的生图核心。
     * 每次重试通过 contentProvider 重新收集正文内容并重建提示词，同时轮换模板。
     * @param templates 书籍绑定的模板列表（多模板时重试轮换使用）
     * @param sceneIndex 场景序号，用于模板轮换基准
     * @param retryCount 重试次数（不含首次），默认1
     * @param contentProvider 重试时重新收集正文的回调，返回null或空则沿用上次文本
     */
    private suspend fun generateWithRetry(
        templates: List<AiImageTemplate>,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        cacheKey: String,
        retryCount: Int = 1,
        contentProvider: (suspend () -> String?)? = null,
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ): GenerateResult? {
        // 生图发送请求前，先激活当前 TTS 引擎
        // 后台 TTS 常被系统回收/关闭，提前激活以保证朗读在生图期间不中断
        // 激活逻辑与朗读开始时完全一致（转发器 TTS 或系统 TTS 按配置选择）
        TtsEngineActivator.activateCurrentTtsEngine()

        var currentText = sourceText
        val maxAttempts = 1 + retryCount.coerceAtLeast(0)

        // 构建密钥槽位列表：同一模板的多个密钥依次排列，用完后切换到下一个模板。
        // 每次生图请求（含重试）从全局轮换索引取下一个槽位，实现多密钥轮换。
        val slots = buildKeySlots(templates)
        if (slots.isEmpty()) return null

        for (attempt in 1..maxAttempts) {
            // 每次重试前，根据当前朗读位置重新计算段落范围并复查持久缓存：
            // 朗读在生图期间持续前移，段落递增后可能正好对齐到之前的旧图，
            // 此时直接复用旧图而非再调 API（正文也通过 contentProvider 紧跟剧情）。
            if (cacheCtxProvider != null) {
                val freshCtx = cacheCtxProvider()
                if (freshCtx != null) {
                    val retryHit = AiImagePersistentCache.findHit(
                        bookUrl = freshCtx.bookUrl,
                        bookName = freshCtx.bookName,
                        chapterIndex = freshCtx.chapterIndex,
                        chapterName = freshCtx.chapterName,
                        startPara = freshCtx.startPara,
                        endPara = freshCtx.endPara,
                        charCount = freshCtx.charCount,
                    )
                    if (retryHit != null) {
                        AppLog.put("AI生图第${attempt}次尝试前复查命中持久缓存, 直接复用 区间[${freshCtx.startPara},${freshCtx.endPara}]")
                        return GenerateResult(retryHit, isHit = true)
                    }
                }
            }
            // 请求间隔：每次请求（含首次与重试）前等待设定的间隔时间，避免触发 API 频率限制
            val intervalMs = (AppConfig.aiImageRequestInterval * 1000).toLong().coerceAtLeast(0)
            if (intervalMs > 0) {
                AppLog.put("AI生图第${attempt}次请求前等待 ${intervalMs}ms 间隔")
                delay(intervalMs)
            }
            // 每次尝试（含重试）轮换密钥槽位：同一模板多密钥依次用完，再切换下一个模板
            val slotIndex = nextSlotIndex(slots.size)
            val slot = slots[slotIndex]
            val template = slot.template
            val prompt = buildPrompt(template, mood, currentText, bookName)
            // 魔搭兜底模板无需密钥，单独显示日志避免误导
            if (isGradioFallback(template)) {
                AppLog.put("AI生图第${attempt}次尝试，模板=${template.name}，魔搭兜底(无需密钥)")
            } else {
                val keyCount = parseKeys(template.modelKey).size.coerceAtLeast(1)
                AppLog.put("AI生图第${attempt}次尝试，模板=${template.name}，密钥${slot.keyIndex + 1}/$keyCount")
            }
            val result = try {
                callImageApi(template, slot.key, prompt, cacheKey)
            } catch (e: Exception) {
                AppLog.put("AI生图第${attempt}次请求异常: ${e.message}")
                null
            }
            if (result != null) {
                AppLog.put("AI生图第${attempt}次成功")
                return GenerateResult(result, isHit = false)
            }
            if (attempt < maxAttempts) {
                // 重试前重新收集正文（朗读在继续，段落位置已变化）
                if (contentProvider != null) {
                    val newText = withContext(Dispatchers.IO) { contentProvider() }
                    if (!newText.isNullOrBlank()) {
                        currentText = newText
                        AppLog.put("AI生图第${attempt}次失败，重试已重新收集文本(${currentText.length}字)")
                    } else {
                        AppLog.put("AI生图第${attempt}次失败，重新收集文本为空，沿用上次文本重试")
                    }
                } else {
                    AppLog.put("AI生图第${attempt}次失败，使用原文本重试")
                }
            }
        }
        return null
    }

    /**
     * 获取已缓存的场景图片（内存+文件系统）
     */
    /**
     * 获取指定场景的缓存图片。
     *
     * 先按 sceneIndex 精确查内存（保证预生成->秒切）；未命中时按当前段落区间
     * 在内部缓存中匹配（内部缓存文件名带段落区间，见
     * [AiImagePersistentCache.saveInternalImage]）。
     *
     * 从章节中间开始朗读时，sceneIndex 的语义与上次从头朗读时不同，
     * 精确 key 不可用，正是靠段落区间匹配来找到对应图片；
     * 匹配不上则返回 null 触发生成。
     */
    fun getCachedImage(
        bookUrl: String,
        chapterIndex: Int,
        sceneIndex: Int,
        startPara: Int = -1,
        endPara: Int = -1,
    ): File? {
        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        sceneCache[cacheKey]?.let { if (isSceneCacheValid(it, startPara, endPara)) return it }
        if (startPara >= 0 && endPara >= startPara) {
            val file = AiImagePersistentCache.findInternalHit(bookUrl, chapterIndex, startPara, endPara)
            if (file != null) {
                // 回填内存缓存，保证同一场景后续秒切
                sceneCache[cacheKey] = file
                return file
            }
        }
        return null
    }

    /**
     * 检查指定场景是否已有生成任务在跑
     */
    fun isGenerating(bookUrl: String, chapterIndex: Int, sceneIndex: Int): Boolean {
        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        val job = generatingJobs[cacheKey]
        return job != null && job.isActive
    }

    /**
     * 获取当前应显示的图片，依次尝试：
     * 1. [lastGeneratedImagePath] —— 内存记录的最新图
     * 2. [displaySequence] —— 真正显示过的图片（最新显示在前）
     * 3. 文件系统按修改时间倒序第一张
     *
     * 后两级兜底很关键：Service 被系统回收后重建会清空内存变量，
     * 若无兜底，切回朗读界面会闪回书籍封面。
     * 有了兜底，新图尚未生成的阶段会一直保持"上一张图片"。
     */
    fun getLastGeneratedImage(): File? {
        lastGeneratedImagePath?.let { path ->
            val f = File(path)
            if (f.exists()) return f
        }
        // 内存变量失效时，优先按"显示顺序"恢复最后真正显示过的那张
        displaySequence.firstOrNull()?.let { if (it.exists()) return it }
        return getHistoryImages().firstOrNull()
    }

    private fun getCacheFile(cacheKey: String): File {
        val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "${cacheKey.hashCode()}.png")
    }

    /** 历史图片保留数量上限（可在设置中调整，默认 50） */
    private val maxHistoryImages: Int
        get() = AppConfig.aiImageCacheCount

    /**
     * 获取按最后修改时间倒序排列的历史图片列表（最新在前）。
     * 仅统计生图缓存文件（排除测试图片 test_image_*）。
     * 同时清理超过 [maxHistoryImages] 张的旧图片。
     */
    fun getHistoryImages(): List<File> {
        val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
        if (!cacheDir.exists()) return emptyList()
        // 内部缓存已改为按 书/章节 分目录存放，需递归扫描
        val files = cacheDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".png") && !it.name.startsWith("test_image_") }
            .toList()
            .sortedByDescending { it.lastModified() }
        // 清理超量旧图片
        if (files.size > maxHistoryImages) {
            files.drop(maxHistoryImages).forEach { it.delete() }
            return files.take(maxHistoryImages)
        }
        return files
    }

    /**
     * 记录一次"真正显示"的图片（含命中复用的旧图），供左右切换图库使用。
     * 去重、置顶、限长，保证显示过的命中旧图也出现在历史切换列表里。
     */
    private fun recordDisplay(path: String) {
        if (path.isBlank()) return
        val f = File(path)
        if (!f.exists()) return
        displaySequence.removeAll { it.absolutePath == f.absolutePath }
        displaySequence.add(0, f)
        while (displaySequence.size > maxHistoryImages) {
            displaySequence.removeAt(displaySequence.lastIndex)
        }
    }

    /**
     * 左右切换用的图库：显示过的命中旧图（displaySequence，最新显示在前）+
     * 新生成图（getHistoryImages），合并去重后限长。
     *
     * 注意：命中旧图位于持久缓存目录（/Download/AI生图/...），修改时间很老，
     * 若按 lastModified 倒序再 take(50)，会被大量新生成图挤掉、永远进不了切换列表。
     * 因此这里以"显示顺序优先"排列，保证命中图一定保留在列表内、且排在最前（与当前显示一致）。
     */
    /**
     * 左右切换用的图库，整体按「朗读顺序」排列：
     * 1. 当前章节已生成的全部图片（按起始段落升序：先场景1、再场景2…）
     *    —— 这样当前图处在它真实的位置，它后面的就是尚未读到的后续场景，
     *       即使这些图从未自动显示过也能手动翻到。
     * 2. 显示过的图（displaySequence，最新在前）兜底，保证命中旧图不被时间排序挤掉
     * 3. 其他历史图
     *
     * 调用方【必须】用 [getGalleryIndexOfCurrent] 定位当前图，不能再假设下标 0 是当前图。
     */
    fun getDisplayGallery(): List<File> {
        // 按「场景」去重：同一张图在持久缓存与内部缓存各存一份，路径不同但内容相同，
        // 只按路径去重会让同一张图出现两次，表现为"切一次还是原图、点两次才换一张"。
        val result = LinkedHashMap<String, File>()
        fun addAll(list: List<File>) {
            list.forEach { f -> if (f.exists()) result.putIfAbsent(sceneKeyOf(f), f) }
        }
        // 本章按朗读顺序优先
        addAll(getChapterGallery())
        // 再补充显示过的图（命中旧图位于持久缓存目录，mtime 很老，靠这里兜底保留）
        addAll(displaySequence)
        // 再补充其他历史图（getHistoryImages 已自行限长并清理超量旧图）
        addAll(getHistoryImages())
        return result.values.take(maxHistoryImages)
    }

    /**
     * 场景去重键：文件名里的 起始段_结束段_字数。
     * 持久缓存与内部缓存的命名规则完全一致，据此可判定是同一张图。
     */
    private fun sceneKeyOf(file: File): String {
        val m = CHAPTER_FILE_RE.find(file.name)
        return if (m != null) "p${m.groupValues[2]}_${m.groupValues[3]}_c${m.groupValues[4]}"
        else file.absolutePath
    }

    /**
     * 从缓存文件名解析段落区间。
     * 文件名不符合命名规则（如临时 hash 命名）时返回 null，表示无法校验。
     */
    private fun paragraphRangeOf(file: File): Pair<Int, Int>? {
        val m = CHAPTER_FILE_RE.find(file.name) ?: return null
        val start = m.groupValues[2].toIntOrNull() ?: return null
        val end = m.groupValues[3].toIntOrNull() ?: return null
        return start to end
    }

    /**
     * 场景缓存里的图是否仍属于当前朗读区间。
     *
     * 同一章内手动翻页后，朗读会重置 sceneIndex=0，但 sceneCache 里存的仍是开始朗读时那张图，
     * 直接返回会把画面切回旧图（翻页定位好的图被覆盖）。
     * 这里用文件名里的段落区间做重叠校验，判定规则与内部缓存命中一致
     * （[AiImagePersistentCache.rangeOverlapHit]）。
     *
     * 无法校验（段落号缺失或文件名不规则）时按命中处理，保持原有行为。
     */
    private fun isSceneCacheValid(cached: File, startPara: Int, endPara: Int): Boolean {
        if (startPara < 0 || endPara < startPara) return true
        val range = paragraphRangeOf(cached) ?: return true
        return AiImagePersistentCache.rangeOverlapHit(range.first, range.second, startPara, endPara)
    }

    /**
     * 当前正在显示的图片在图库中的真实下标（按朗读顺序定位）。
     * 找不到时返回 0。
     */
    fun getGalleryIndexOfCurrent(gallery: List<File>): Int {
        val path = lastGeneratedImagePath
        if (!path.isNullOrBlank()) {
            // 1) 路径完全一致
            val idx = gallery.indexOfFirst { it.absolutePath == path }
            if (idx >= 0) return idx
            // 2) 同一场景：图库去重后只保留一份（持久缓存优先），
            //    而当前图路径可能是被去重掉的另一份（内部缓存），必须按场景键兜底匹配，
            //    否则会返回 0，导致手动切换从第 0 张开始、所有图都被当成"后续"。
            val key = sceneKeyOf(File(path))
            val idx2 = gallery.indexOfFirst { sceneKeyOf(it) == key }
            if (idx2 >= 0) return idx2
        }
        return 0
    }

    /**
     * 当前章节已生成的全部图片（持久缓存 /Download/AI生图 + 内部缓存 cacheDir/ai_images），
     * 按朗读顺序（起始段落升序）返回。
     *
     * 说明：只做只读汇总，不触发生成、不做任何提示；
     * 正在合成中的图片仍按原有流程合成，朗读到对应位置时会自动切回该图。
     */
    fun getChapterGallery(): List<File> {
        return chapterImagesWithRange().map { it.file }
    }

    /**
     * 当前章节上下文：以「当前正在阅读的章节」为准。
     * lastCacheCtx 只在生成时才更新，刚切章节还没生图时它会停留在上一章，
     * 导致"本章图库"实际列出上一章的图，因此这里以 ReadBook 当前进度为准。
     */
    private fun currentChapterCtx(): PersistentCacheContext? {
        val book = io.legado.app.model.ReadBook.book ?: return lastCacheCtx
        val chapterIndex = io.legado.app.model.ReadBook.durChapterIndex
        val last = lastCacheCtx
        if (last != null && last.bookUrl == book.bookUrl && last.chapterIndex == chapterIndex) {
            return last
        }
        return PersistentCacheContext(
            bookUrl = book.bookUrl,
            bookName = book.name,
            chapterIndex = chapterIndex,
            chapterName = io.legado.app.model.ReadBook.curTextChapter?.title ?: "",
            startPara = 0,
            endPara = 0,
            charCount = AppConfig.aiImageCharCount,
        )
    }

    /**
     * 当前章节已缓存的图片及其段落区间，按段落升序。
     * 以本地持久缓存（/Download/AI生图/）为主：同一场景两边都有时取持久缓存那份；
     * 只有本地没有的场景，才用内部缓存（cacheDir/ai_images）补齐。
     */
    private fun chapterImagesWithRange(): List<AiImagePersistentCache.SavedImageTrack> {
        val ctx = currentChapterCtx() ?: return emptyList()
        return try {
            val persisted = AiImagePersistentCache.getChapterImageTracks(
                ctx.bookName, ctx.chapterIndex, ctx.chapterName
            )
            val internal = AiImagePersistentCache.getInternalChapterImageTracks(
                ctx.bookUrl, ctx.chapterIndex
            )
            // persisted 在前，去重时保留的就是本地持久缓存那份
            (persisted + internal)
                .filter { it.file.exists() }
                .distinctBy { sceneKeyOf(it.file) }
                .sortedBy { it.startPara }
        } catch (e: Exception) {
            AppLog.put("AI生图: 汇总本章图片失败 ${e.message}")
            emptyList()
        }
    }

    /**
     * 按当前朗读段落在本章已缓存图片里定位：
     * 先精确匹配包含该段落的图，再按距离向外扩散 p±1 … p±10（同距离先往后、再往前）。
     * 只在当前章节内查找，不触发生成。
     */
    fun findChapterImageByParagraph(paragraph: Int): File? {
        if (paragraph < 0) return null
        val images = chapterImagesWithRange()
        if (images.isEmpty()) return null
        for (d in 0..10) {
            val candidates = if (d == 0) listOf(paragraph) else listOf(paragraph + d, paragraph - d)
            for (p in candidates) {
                if (p < 0) continue
                val hit = images.firstOrNull { p >= it.startPara && p <= it.endPara }
                if (hit != null) return hit.file
            }
        }
        return null
    }

    /**
     * 开始朗读/切章节时，按当前段落就近找一张已缓存的图**暂时显示并定位**。
     * 只负责显示与图库锚点，不触发生成、不写缓存；
     * 后续真正生成/命中当前场景的图时，AI_IMAGE_CHANGED 会把它切过去。
     * @return 是否定位成功
     */
    fun showNearestChapterImage(paragraph: Int): Boolean {
        val file = findChapterImageByParagraph(paragraph) ?: return false
        lastGeneratedImagePath = file.absolutePath
        recordDisplay(file.absolutePath)
        AppLog.put("AI生图: 按段落 $paragraph 就近定位到 ${file.name}")
        postEvent(EventBus.AI_IMAGE_CHANGED, file.absolutePath)
        return true
    }

    /**
     * 当前屏幕显示的正文里用于定位的段落号。
     *
     * 优先取当前页第二个段落的段落号：第一段的开头往往被截在上一页，
     * 语义不完整，第二段更能代表本页内容。当前页只有一个段落时退回第一个段落号。
     *
     * 段落号取自 TextLine.paragraphNum（章节内编号，从 1 开始，标题等未参与编号的行为 0）。
     * @return 段落号，取不到返回 null
     */
    fun currentDisplayParagraph(): Int? {
        val page = io.legado.app.model.ReadBook.curTextChapter
            ?.getPage(io.legado.app.model.ReadBook.durPageIndex) ?: return null
        val numList = page.lines
            .map { it.paragraphNum }
            .filter { it > 0 }
            .distinct()
            .take(2)
        return when {
            numList.isEmpty() -> null
            numList.size >= 2 -> numList[1]
            else -> numList[0]
        }
    }

    /**
     * 按当前屏幕显示的段落重新定位图片，翻页时调用（朗读中不依赖朗读流程，立刻生效）。
     * 只负责显示与图库锚点，不触发生成、不写缓存；后续真正生成/命中当前场景的图时会自动切过去。
     *
     * 未命中任何已缓存图片时不发事件，保持当前显示不变。
     * @return 是否定位成功
     */
    fun locateImageByCurrentPage(): Boolean {
        if (!AppConfig.readAloudAiImage) return false
        val paragraph = currentDisplayParagraph() ?: return false
        return showNearestChapterImage(paragraph)
    }



    /**
     * 清理超量历史图片，保证磁盘缓存不超过 [maxHistoryImages] 张。
     * 在每张新图生成后调用，避免图片无限增长。
     */
    private fun trimHistoryIfNeeded() {
        try {
            val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) return
            // 内部缓存已改为按 书/章节 分目录存放，需递归扫描
            val files = cacheDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".png") && !it.name.startsWith("test_image_") }
                .toList()
                .sortedByDescending { it.lastModified() }
            if (files.size > maxHistoryImages) {
                files.drop(maxHistoryImages).forEach { it.delete() }
            }
            // 顺便清理超过1小时的测试图片
            val now = System.currentTimeMillis()
            cacheDir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("test_image_") }
                .forEach { if (now - it.lastModified() > 3600000L) it.delete() }
        } catch (_: Exception) {}
    }

    /**
     * 清空 AI 生图临时缓存：删除缓存目录下的所有生图文件，
     * 并清除内存中的相关引用（场景缓存、显示序列、最近图片路径）。
     * 仅删除临时缓存（cacheDir/CACHE_DIR_NAME），不会删除
     * 「保存AI图片」落盘的 /Download/AI生图/ 永久图片。
     * @return 删除的文件数量
     */
    fun clearTempCache(): Int {
        var deleted = 0
        try {
            val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (cacheDir.exists()) {
                // 内部缓存已改为按 书/章节 分目录存放，需递归扫描
                cacheDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".png") && !it.name.startsWith("test_image_") }
                    .forEach {
                        if (it.delete()) deleted++
                    }
            }
        } catch (_: Exception) {}
        // 同步清理内存引用，避免画廊继续引用已删除的文件
        sceneCache.clear()
        displaySequence.clear()
        lastGeneratedImagePath = null
        return deleted
    }

    /**
     * 清除指定章节的场景缓存（内存），使后续场景按新的朗读起点重新匹配/生成。
     *
     * 场景缓存以 `bookUrl_chapterIndex_sceneIndex` 为键，而 sceneIndex 是**相对于朗读起点**的序号。
     * 同一章内手动翻页后朗读会重置 sceneIndex=0，但缓存里仍是按旧起点生成的图：
     * scene 0 还能靠段落区间校验纠正，scene 1、2… 却会被自动切换原样取出，
     * 表现为「翻了好几页，自动切换却切到开始朗读那张的下一张」。
     * 因此朗读起点变化时必须让本章场景缓存整体失效。
     *
     * 磁盘上的内部缓存与持久缓存不受影响，重新匹配时仍会按段落区间命中，不会重复调 API。
     */
    fun clearChapterSceneCache(bookUrl: String, chapterIndex: Int) {
        val prefix = "${bookUrl}_${chapterIndex}_"
        try {
            sceneCache.keys.filter { it.startsWith(prefix) }.forEach { sceneCache.remove(it) }
        } catch (_: Exception) {
            sceneCache.clear()
        }
    }

    /**
     * 根据当前缓存数量上限，立即清理超量的旧图（用户在设置中调小上限时调用）。
     */
    fun applyCacheLimit() {
        trimHistoryIfNeeded()
    }

    /**
     * 获取当前正在显示的图片文件（优先 lastGeneratedImagePath，其次历史第一张）
     */
    fun getCurrentDisplayImage(): File? {
        lastGeneratedImagePath?.let { path ->
            val f = File(path)
            if (f.exists()) return f
        }
        displaySequence.firstOrNull()?.let { if (it.exists()) return it }
        return getHistoryImages().firstOrNull()
    }

    /**
     * 清除内存缓存（切换书籍时调用）
     */
    fun clearCache() {
        sceneCache.clear()
        generatingJobs.values.forEach { it.cancel() }
        generatingJobs.clear()
        lastGeneratedImagePath = null
        displaySequence.clear()
        AiImagePersistentCache.clearAllCursors()
    }

    // ========== 预生成机制 ==========

    /** 预生成图片：静默生成，不通知UI，用于提前准备下一张图片 */
    fun preGenerateForScene(
        bookUrl: String,
        chapterIndex: Int,
        sceneIndex: Int,
        mood: String,
        sourceText: String,
        bookName: String,
        retryCount: Int = 1,
        contentProvider: (suspend () -> String?)? = null,
        cacheCtx: PersistentCacheContext? = null,
        // 重试时重新计算段落范围并复查持久缓存的回调（基于当前朗读位置），
        // 用于生图失败后段落递增时复用之前的旧图。传 null 则不复查（预生成场景）。
        cacheCtxProvider: (() -> PersistentCacheContext?)? = null,
    ) {
        if (!enabled) return
        val templates = getActiveTemplates(bookName, bookUrl)
        if (templates.isEmpty()) return
        // 按 sceneIndex 轮换选择模板
        val template = templates[sceneIndex % templates.size]
        val useGradioFallback = template.modelUrl.isBlank() && template.modelName.isBlank() && template.modelKey.isBlank()
        if (!useGradioFallback && (template.modelUrl.isBlank() || template.modelName.isBlank())) return

        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        // 已缓存则跳过
        if (sceneCache.containsKey(cacheKey)) return
        // 内部缓存按段落区间匹配（文件名带段落信息），与持久缓存同逻辑
        if (cacheCtx != null) {
            val internalHit = AiImagePersistentCache.findInternalHit(
                bookUrl, chapterIndex, cacheCtx.startPara, cacheCtx.endPara
            )
            if (internalHit != null) {
                AppLog.put("AI生图[DEBUG] preGenerateForScene: 命中内部缓存, 静默回填 sceneIndex=$sceneIndex")
                sceneCache[cacheKey] = internalHit
                return
            }
        }
        // 持久缓存命中：静默回填到内存，不再调 API
        if (cacheCtx != null) {
            val hit = AiImagePersistentCache.findHit(
                bookUrl = cacheCtx.bookUrl,
                bookName = cacheCtx.bookName,
                chapterIndex = chapterIndex,
                chapterName = cacheCtx.chapterName,
                startPara = cacheCtx.startPara,
                endPara = cacheCtx.endPara,
                charCount = cacheCtx.charCount,
            )
            if (hit != null) {
                AppLog.put("AI生图[DEBUG] preGenerateForScene: 命中持久缓存, 静默回填 sceneIndex=$sceneIndex")
                sceneCache[cacheKey] = hit
                return
            }
        }
        // 已有同名任务在生成（实时生成或预生成），直接跳过避免重复
        val existing = generatingJobs[cacheKey]
        if (existing != null && existing.isActive) return

        startSceneGenerate(
            templates, sceneIndex, mood, sourceText, bookName,
            cacheKey, retryCount, contentProvider, notifyUi = false, cacheCtx = cacheCtx,
            cacheCtxProvider = cacheCtxProvider
        )
    }

    /** 通知UI显示已缓存的图片（支持按段落区间从内部缓存恢复）。返回 true 表示找到并通知了图片 */
    fun notifyImageIfReady(
        bookUrl: String,
        chapterIndex: Int,
        sceneIndex: Int,
        startPara: Int = -1,
        endPara: Int = -1,
    ): Boolean {
        val cacheKey = "${bookUrl}_${chapterIndex}_${sceneIndex}"
        // 翻页后 sceneIndex 会重置，缓存里的图可能属于其它段落，必须校验区间再复用
        val cached = sceneCache[cacheKey]
        val imageFile = if (cached != null && isSceneCacheValid(cached, startPara, endPara)) {
            cached
        } else {
            if (startPara >= 0 && endPara >= startPara) {
                AiImagePersistentCache.findInternalHit(bookUrl, chapterIndex, startPara, endPara)
                    ?.also { sceneCache[cacheKey] = it }
            } else {
                null
            }
        }
        if (imageFile != null) {
            // 同步更新 lastGeneratedImagePath，确保退出重进朗读界面时能取到最新图片
            lastGeneratedImagePath = imageFile.absolutePath
            recordDisplay(imageFile.absolutePath)
            postEvent(EventBus.AI_IMAGE_CHANGED, imageFile.absolutePath)
            return true
        }
        return false
    }

    private fun buildPrompt(
        template: AiImageTemplate,
        mood: String,
        sourceText: String,
        bookName: String,
    ): String {
        // 角色标注：开启后，对发给 API 的正文做角色标注（与导出小说“添加标注”同源）
        val annotatedSourceText = if (template.annotateRoles) {
            annotateSourceText(sourceText)
        } else {
            sourceText
        }
        // 正文过滤：删除正文中包含的过滤词语（多个用竖线分隔）
        val filteredText = filterSourceText(annotatedSourceText, template.filterWords)
        val textDesc = if (filteredText.isNotBlank()) {
            val truncated = if (filteredText.length > 350) filteredText.take(350) + "..." else filteredText
            "内容片段：$truncated。"
        } else ""
        val bookDesc = if (bookName.isNotBlank()) "出自小说《$bookName》。" else ""

        return template.promptTemplate
            .replace("{text}", textDesc)
            .replace("{book}", bookDesc)
            .replace("{style}", template.imageStyle)
    }

    /**
     * 对正文做角色标注（在每个左双引号“右侧插入 <<姓名（性别/年龄）>>）。
     * 角色信息来自 AI 章节缓存文件，需要当前书籍与章节信息。
     * 无当前书籍/章节或标注失败时，返回原文本。
     */
    private fun annotateSourceText(sourceText: String): String {
        val book = ReadBook.book ?: return sourceText
        val chapter = ReadBook.curTextChapter?.chapter ?: return sourceText
        return try {
            AiCacheFileUtil.annotateTextWithRoles(sourceText, book, chapter)
        } catch (e: Exception) {
            sourceText
        }
    }

    /**
     * 过滤正文内容：将 [sourceText] 中包含的过滤词语删除（替换为空字符串）。
     * 过滤词语多个用竖线 | 分隔，例如：词语1|词语2|词语3
     * 空过滤词会被跳过，避免误删。
     */
    private fun filterSourceText(sourceText: String, filterWords: String): String {
        if (filterWords.isBlank() || sourceText.isBlank()) return sourceText
        var result = sourceText
        filterWords.split("|").forEach { word ->
            val trimmed = word.trim()
            if (trimmed.isNotEmpty()) {
                result = result.replace(trimmed, "")
            }
        }
        return result
    }

    /**
     * 构造生图请求体。
     *
     * @param strictFormat 是否严格格式。
     *  - false（默认兼容模式）：同时发送 image_size、size、negative_prompt 等多个字段，
     *    兼容 SiliconFlow、OpenAI 等"忽略未知字段"的宽松接口。
     *  - true（严格格式）：只发送对应接口白名单字段，避免 tokenrhythm.studio 等
     *    严格校验"未知字段"的服务返回 HTTP 400。此时：
     *      * 含 siliconflow 的地址使用 image_size，其余使用 size；
     *      * 不发送 negative_prompt（即使填写）；
     *      * n 仍发送（主流接口均支持），base64 开关按需发送。
     */
    private fun buildImageRequestBody(
        modelName: String,
        imageSize: String,
        prompt: String,
        negativePrompt: String,
        useBase64Response: Boolean,
        strictFormat: Boolean,
        modelUrl: String,
    ): MutableMap<String, Any> {
        val body = mutableMapOf<String, Any>(
            "model" to modelName,
            "prompt" to prompt,
            "n" to 1,
        )
        if (strictFormat) {
            // 严格格式：按接口类型只发一个尺寸字段
            if (modelUrl.contains("siliconflow", ignoreCase = true)) {
                body["image_size"] = imageSize
            } else {
                body["size"] = imageSize
            }
        } else {
            // 兼容格式：同时发送两种尺寸字段 + 负面提示词
            body["image_size"] = imageSize
            body["size"] = imageSize
            if (negativePrompt.isNotBlank()) {
                body["negative_prompt"] = negativePrompt
            }
        }
        // 开启 base64 模式：要求 API 直接返回 base64 图片数据
        if (useBase64Response) {
            body["response_format"] = "b64_json"
        }
        return body
    }

    private suspend fun callImageApi(
        template: AiImageTemplate,
        modelKey: String,
        prompt: String,
        cacheKey: String,
    ): File? {
        // 模板三字段全空 → 走魔搭 Gradio 兜底（免费、无需密钥）
        // 注意：此判断基于模板原始字段，不受多密钥轮换传入的 modelKey 影响，魔搭兜底不会被误判
        if (isGradioFallback(template)) {
            AppLog.put("AI生图: 模板「${template.name}」三字段全空，走魔搭Gradio兜底（无需密钥）")
            return callGradioApi(prompt, template.imageSize, cacheKey)
        }
        val url = normalizeImageApiUrl(template.modelUrl.trim())
        val authHeader = if (modelKey.isNotBlank()) {
            "Bearer ${modelKey.trim()}"
        } else null

        // 构造请求体：根据模板配置决定字段集
        // 严格格式：只发对应接口白名单字段，避免 tokenrhythm.studio 等严格校验"未知字段"报错
        // 兼容格式：同时包含各主流格式的字段（image_size / size / negative_prompt），兼容所有生图服务
        val body = buildImageRequestBody(
            modelName = template.modelName.trim(),
            imageSize = template.imageSize,
            prompt = prompt,
            negativePrompt = template.negativePrompt,
            useBase64Response = template.useBase64Response,
            strictFormat = template.strictFormat,
            modelUrl = url,
        )
        val bodyJson = GSON.toJson(body)
        val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")

        if (authHeader != null) {
            requestBuilder.header("Authorization", authHeader)
        }

        val response = imageClient.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            val errorBody = response.body?.string()?.take(500) ?: ""
            AppLog.put("AI生图 API 请求失败 HTTP ${response.code}: $errorBody")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        val json = JsonParser.parseString(responseBody).asJsonObject

        // 尝试解析图片数据（支持 base64 / URL 多种格式）
        val imageBytes = extractImageBytes(json) ?: return null

        val imageFile = getCacheFile(cacheKey)
        imageFile.writeBytes(imageBytes)
        return imageFile
    }

    /**
     * 从 API 响应中提取图片字节数据
     */
    private fun extractImageBytes(json: com.google.gson.JsonObject): ByteArray? {
        // 1. OpenAI DALL-E b64_json 格式: data[0].b64_json
        try {
            val dataArray = json.getAsJsonArray("data")
            if (dataArray != null && dataArray.size() > 0) {
                val first = dataArray[0].asJsonObject
                val b64 = first.get("b64_json")?.asString
                if (!b64.isNullOrBlank()) {
                    return android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                }
            }
        } catch (_: Exception) {}

        // 2. 通用格式: images[0] 直接是 base64 字符串
        try {
            val images = json.getAsJsonArray("images")
            if (images != null && images.size() > 0) {
                val first = images[0]
                if (first.isJsonPrimitive) {
                    return android.util.Base64.decode(first.asString, android.util.Base64.DEFAULT)
                }
            }
        } catch (_: Exception) {}

        // 3. 直接 image 字段
        try {
            val image = json.get("image")?.asString
            if (!image.isNullOrBlank()) {
                return android.util.Base64.decode(image, android.util.Base64.DEFAULT)
            }
        } catch (_: Exception) {}

        // 4. 硅基流动格式: images[0].url（下载 URL 图片）
        try {
            val images = json.getAsJsonArray("images")
            if (images != null && images.size() > 0) {
                val first = images[0].asJsonObject
                val imageUrl = first.get("url")?.asString
                if (!imageUrl.isNullOrBlank()) {
                    return downloadImageBytes(imageUrl)
                }
            }
        } catch (_: Exception) {}

        // 5. OpenAI URL 格式: data[0].url
        try {
            val dataArray = json.getAsJsonArray("data")
            if (dataArray != null && dataArray.size() > 0) {
                val first = dataArray[0].asJsonObject
                val imageUrl = first.get("url")?.asString
                if (!imageUrl.isNullOrBlank()) {
                    return downloadImageBytes(imageUrl)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun downloadImageBytes(url: String): ByteArray? {
        return try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 魔搭 Qwen-Image 2.0 Pro Gradio 兜底生图。
     * 严格参照 qwen_image_generator.html 的两步流程：
     *  1) POST 提交任务，拿到 event_id
     *  2) 每 3 秒轮询 …/generate_image/{event_id}，解析 SSE，拿到图片 URL 后下载
     * @param prompt 已构造好的提示词
     * @param imageSize 模板的图片尺寸（格式 "宽x高"），无法识别时用默认竖版 1024×1536
     */
    private suspend fun callGradioApi(
        prompt: String,
        imageSize: String,
        cacheKey: String,
    ): File? {
        val (width, height) = parseImageSizeForGradio(imageSize)
        // 注意：魔搭 Gradio API 的 data 数组中，第7个位置实际是"高度"、第8个位置实际是"宽度"
        // （与 qwen_image_generator.html 中 label 标注相反，HTML 误标，但行为是竖版）。
        // 为复刻 HTML 的竖版效果，这里把真实 height 放第7位、真实 width 放第8位。
        // 步骤1：提交任务
        val submitBodyJson = GSON.toJson(
            mapOf(
                "data" to listOf(
                    emptyList<Any>(),   // [] 参考图（空）
                    prompt,             // 提示词
                    false,              // 提示词扩展（关闭）
                    false,
                    0,                  // seed
                    true,               // randomize（seed===0）
                    height,             // 第7位：魔搭实际当作高度
                    width,              // 第8位：魔搭实际当作宽度
                    " "
                )
            )
        )
        val submitRequest = Request.Builder()
            .url(GRADIO_API_SUBMIT)
            .post(submitBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val submitResp = try {
            okHttpClient.newCall(submitRequest).execute()
        } catch (e: Exception) {
            AppLog.put("AI生图(魔搭) 提交异常: ${e.message}")
            return null
        }
        if (!submitResp.isSuccessful) {
            AppLog.put("AI生图(魔搭) 提交失败 HTTP ${submitResp.code}")
            return null
        }
        val submitBodyStr = submitResp.body?.string() ?: run {
            AppLog.put("AI生图(魔搭) 提交响应为空")
            return null
        }
        val eventId = try {
            JsonParser.parseString(submitBodyStr).asJsonObject.get("event_id")?.asString
        } catch (_: Exception) {
            null
        }
        if (eventId.isNullOrBlank()) {
            AppLog.put("AI生图(魔搭) 未获取到 event_id: ${submitBodyStr.take(300)}")
            return null
        }

        // 步骤2：轮询（最多 20 次，每次间隔 3 秒）
        val maxAttempts = 20
        var lastImageUrl: String? = null
        var foundComplete = false
        for (attempt in 1..maxAttempts) {
            delay(3000)
            val pollRequest = Request.Builder()
                .url("$GRADIO_API_SUBMIT/$eventId")
                .build()
            val text = try {
                okHttpClient.newCall(pollRequest).execute().body?.string()
            } catch (_: Exception) {
                null
            } ?: continue

            // 解析 SSE
            val lines = text.split("\n")
            for (line in lines) {
                if (line.startsWith("event: complete")) {
                    foundComplete = true
                }
                if (line.startsWith("data: ")) {
                    val dataStr = line.substring(6)
                    if (dataStr == "[, null, null]") continue
                    try {
                        val data = JsonParser.parseString(dataStr)
                        if (data.isJsonArray && data.asJsonArray.size() > 0) {
                            val first = data.asJsonArray[0]
                            if (first.isJsonObject) {
                                val url = first.asJsonObject.get("url")?.asString
                                if (!url.isNullOrBlank()) {
                                    lastImageUrl = url
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (foundComplete) break
        }

        if (lastImageUrl.isNullOrBlank()) {
            AppLog.put("AI生图(魔搭) 轮询结束未获取到图片URL")
            return null
        }
        val bytes = downloadImageBytes(lastImageUrl) ?: run {
            AppLog.put("AI生图(魔搭) 下载图片失败: $lastImageUrl")
            return null
        }
        val imageFile = getCacheFile(cacheKey)
        imageFile.writeBytes(bytes)
        return imageFile
    }

    /**
     * 解析图片尺寸为魔搭 Gradio 支持的宽高。
     * 支持 "宽x高" / "宽×高" / "宽X高"。
     * 魔搭 width∈{768,1024,1344,1536,2688}，height∈{768,1024,1344,1536,2688}。
     * 无法识别或不支持时返回默认竖版 1024×1536。
     */
    private fun parseImageSizeForGradio(imageSize: String): Pair<Int, Int> {
        val supportedW = setOf(768, 1024, 1344, 1536, 2688)
        val supportedH = setOf(768, 1024, 1344, 1536, 2688)
        val parts = imageSize.trim().split("x", "×", "X")
        if (parts.size == 2) {
            val w = parts[0].trim().toIntOrNull()
            val h = parts[1].trim().toIntOrNull()
            if (w != null && h != null && w in supportedW && h in supportedH) {
                return Pair(w, h)
            }
        }
        return Pair(GRADIO_DEFAULT_WIDTH, GRADIO_DEFAULT_HEIGHT)
    }

    /**
     * 测试模型是否可用（使用当前编辑中的参数，不依赖已保存模板）
     */
    suspend fun testModel(
        modelUrl: String,
        modelName: String,
        modelKey: String,
        imageSize: String,
        imageStyle: String,
        promptTemplate: String,
        negativePrompt: String,
        useBase64Response: Boolean = false,
        strictFormat: Boolean = false,
    ): Result<File> {
        // 测试连接发送请求前，先激活当前 TTS 引擎
        // 后台 TTS 常被系统回收/关闭，提前激活以保证朗读在测试期间不中断
        // 激活逻辑与朗读开始、正常生图完全一致（转发器 TTS 或系统 TTS 按配置选择）
        TtsEngineActivator.activateCurrentTtsEngine()

        // 三字段全空 → 测试魔搭 Gradio 兜底
        if (modelUrl.isBlank() && modelName.isBlank() && modelKey.isBlank()) {            return try {
                val testPrompt = promptTemplate
                    .replace("{mood}", "")
                    .replace("{text}", "A beautiful landscape painting")
                    .replace("{book}", "")
                    .replace("{style}", imageStyle)
                val file = callGradioApi(testPrompt, imageSize, "test_${System.currentTimeMillis()}")
                if (file != null) Result.success(file)
                else Result.failure(Exception("魔搭生图失败，请稍后重试"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        return try {
            val testPrompt = promptTemplate
                .replace("{mood}", "")
                .replace("{text}", "A beautiful landscape painting")
                .replace("{book}", "")
                .replace("{style}", imageStyle)
            val url = normalizeImageApiUrl(modelUrl.trim())
            // 多密钥场景：测试连接时使用第一个密钥
            val testKey = parseKeys(modelKey).firstOrNull() ?: ""
            val authHeader = if (testKey.isNotBlank()) "Bearer ${testKey.trim()}" else null

            val body = buildImageRequestBody(
                modelName = modelName.trim(),
                imageSize = imageSize,
                prompt = testPrompt,
                negativePrompt = negativePrompt,
                useBase64Response = useBase64Response,
                strictFormat = strictFormat,
                modelUrl = url,
            )
            val bodyJson = GSON.toJson(body)
            val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())

            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")

            if (authHeader != null) {
                requestBuilder.header("Authorization", authHeader)
            }

            val response = imageClient.newCall(requestBuilder.build()).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()?.take(500) ?: "未知错误"
                return Result.failure(Exception("HTTP ${response.code}: $errorBody"))
            }

            val responseBody = response.body?.string() ?: return Result.failure(Exception("响应体为空"))
            val json = JsonParser.parseString(responseBody).asJsonObject
            val imageBytes = extractImageBytes(json)
                ?: return Result.failure(Exception("无法解析图片数据，请确认 API 和模型名称正确"))

            val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val imageFile = File(cacheDir, "test_image_${System.currentTimeMillis()}.png")
            imageFile.writeBytes(imageBytes)
            Result.success(imageFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 用**指定模板** + **自定义提示词**生成一张图片，返回本地文件。
     *
     * 与朗读生图完全解耦：不写场景缓存、不参与模板轮换、不管段落区间，
     * 只负责"拿这个模板、用这段提示词、出一张图"。
     * 书籍信息界面生成封面走的就是这条路。
     *
     * @param prompt 已经替换好占位符的最终提示词，直接发给模型
     */
    suspend fun generateImageDirect(
        template: AiImageTemplate,
        prompt: String
    ): Result<File> {
        val modelUrl = template.modelUrl
        val modelName = template.modelName
        val modelKey = template.modelKey
        val useGradioFallback =
            modelUrl.isBlank() && modelName.isBlank() && modelKey.isBlank()
        return try {
            if (useGradioFallback) {
                val file = callGradioApi(
                    prompt,
                    template.imageSize,
                    "cover_${System.currentTimeMillis()}"
                )
                if (file != null) Result.success(file)
                else Result.failure(Exception("魔搭生图失败，请稍后重试"))
            } else {
                if (modelUrl.isBlank() || modelName.isBlank()) {
                    return Result.failure(Exception("模板的接口地址或模型名为空"))
                }
                val url = normalizeImageApiUrl(modelUrl.trim())
                val key = parseKeys(modelKey).firstOrNull() ?: ""
                val authHeader = if (key.isNotBlank()) "Bearer ${key.trim()}" else null
                val body = buildImageRequestBody(
                    modelName = modelName.trim(),
                    imageSize = template.imageSize,
                    prompt = prompt,
                    negativePrompt = template.negativePrompt,
                    useBase64Response = template.useBase64Response,
                    strictFormat = template.strictFormat,
                    modelUrl = url,
                )
                val requestBody = GSON.toJson(body)
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                if (authHeader != null) {
                    requestBuilder.header("Authorization", authHeader)
                }
                val response = imageClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()?.take(500) ?: "未知错误"
                    return Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }
                val responseBody =
                    response.body?.string() ?: return Result.failure(Exception("响应体为空"))
                val json = JsonParser.parseString(responseBody).asJsonObject
                val imageBytes = extractImageBytes(json)
                    ?: return Result.failure(Exception("无法解析图片数据，请确认 API 和模型名称正确"))
                val cacheDir = File(appCtx.cacheDir, CACHE_DIR_NAME)
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val imageFile = File(cacheDir, "cover_${System.currentTimeMillis()}.png")
                imageFile.writeBytes(imageBytes)
                Result.success(imageFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * **没选模板时的内置默认**：混元太极本地网关的智谱生图接口。
     *
     * 走的是进程内直连（`HunyuanLocalGateway` 已在 [imageClient] 的拦截器链里），
     * 不需要真有个服务监听在这个端口上。
     */
    const val BUILTIN_MODEL_URL = "http://localhost:8801/v1"
    const val BUILTIN_MODEL_NAME = "glm-image-hd"
    const val BUILTIN_MODEL_KEY = "123"

    /** 生成封面用的图片尺寸（竖版，接近书封比例） */
    const val COVER_IMAGE_SIZE = "1024x1536"

    /**
     * 没选模板（或一个模板都没有）时用它兜底，
     * 免得用户还得先去建模板才能生成封面。
     */
    fun builtinTemplate(): io.legado.app.data.entities.AiImageTemplate =
        io.legado.app.data.entities.AiImageTemplate(
            id = 0L,
            name = "内置默认",
            modelUrl = BUILTIN_MODEL_URL,
            modelName = BUILTIN_MODEL_NAME,
            modelKey = BUILTIN_MODEL_KEY,
            imageSize = COVER_IMAGE_SIZE,
            imageStyle = DEFAULT_STYLE_SUFFIX,
            negativePrompt = DEFAULT_NEGATIVE_PROMPT
        )

    /**
     * 生成封面用的默认提示词。{name} 书名，{intro} 简介。
     *
     * **封面必须带书名**——真实的小说封面（起点/番茄/晋江那种）都是
     * 「题材插画 + 醒目的书名大字 + 小号作者名」，
     * 只有插画没有书名的那叫插图，不叫封面。
     */
    const val DEFAULT_COVER_PROMPT =
        "请生成一张中文网络小说的竖版书籍封面。\n" +
            "书名：{name}\n" +
            "小说简介：{intro}\n" +
            "【内容】\n" +
            "1. 根据书名与简介判断题材（玄幻/都市/言情/悬疑/历史等），" +
            "画一幅贴合该题材的精美插画作为封面主体，氛围感强、细节丰富；\n" +
            "2. 主体人物占画面中下部，上方留出干净的背景区域用于放书名。\n" +
            "【文字】\n" +
            "3. 必须在画面显著位置用醒目、工整的字体写出书名「{name}」，" +
            "字要清晰可辨、笔画正确、与画面风格协调；\n" +
            "4. 书名是这段文字中唯一需要出现的标题文字，不要另外编造别的标题、副标题或宣传语；\n" +
            "5. 除书名外不要出现其它文字、水印、Logo、签名、二维码。\n" +
            "【画面色调】\n" +
            "6. 整体画面**明亮通透**，光线充足、色彩鲜明饱和，不要灰暗、阴沉、过曝或黑压压；" +
            "即使题材本身偏暗（悬疑 / 恐怖 / 末世），也要做成明亮清透的商业封面质感。\n" +
            "【版式】\n" +
            "7. 竖版 3:4，标准书籍封面比例，四周留白合理；\n" +
            "8. **书名的位置不要太靠上**：放在画面中部偏上（从上往下约 30%~45% 处）、横向居中，" +
            "与顶部边缘至少留出一整个书名的距离——封面装帧时上下会裁掉一部分，" +
            "书名贴顶会被裁掉；底部同理也不要贴边。"

    private fun normalizeImageApiUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        // 已是完整生图接口，直接用
        if (trimmed.endsWith("/images/generations")) return trimmed
        // 误填了聊天接口，替换为生图接口
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed.removeSuffix("/chat/completions") + "/images/generations"
        }
        // 其他情况最多只补 /images/generations，不强制补 /v1，避免非标准路径出错
        return "$trimmed/images/generations"
    }

    // ===================== 角色立绘（气泡头像 / 与角色对话） =====================

    /** 角色立绘缓存目录名（固定 cacheDir/ai_images/ai_avatars/） */
    private const val AVATAR_DIR_NAME = "ai_avatars"

    /** 立绘专用负面提示词：与场景插图不同，立绘要的是人物半身/全身像 */
    private const val AVATAR_NEGATIVE_PROMPT =
        "文字, 水印, 边框, 多人, 群像, 残缺肢体, 手部特写, 模糊, 低质量"

    /** 用户没配置画风时的中性兜底（避免默认国风把都市小说的立绘带成仙侠） */
    private const val AVATAR_NEUTRAL_STYLE =
        "高质量数字绘画, 细腻光影, 写实与插画结合的人物立绘风格"

    /** 同一角色的立绘只生成一次 */
    private val avatarInFlight = ConcurrentHashMap<String, Boolean>()

    /**
     * 角色立绘缓存目录（应用私有缓存，懒加载）。
     * 固定 cacheDir 而不跟随 savePath：savePath 可能是 SAF content:// 路径不可写。
     */
    private val avatarDirCache: File by lazy {
        File(File(appCtx.cacheDir, CACHE_DIR_NAME), AVATAR_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun avatarCacheDir(): File = avatarCacheDirLazy()

    private fun avatarCacheDirLazy(): File = avatarDirCache

    /** 角色立绘的稳定标识：书名 + 角色名（同书同名视为同一角色） */
    fun avatarKey(bookName: String, characterName: String): String =
        "avatar_${sanitizeFilePart(bookName)}_${sanitizeFilePart(characterName)}"

    private fun sanitizeFilePart(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_").take(40)

    /** 取已缓存的角色立绘；未生成过返回 null */
    fun getCachedAvatar(bookName: String, characterName: String): File? {
        val f = File(avatarCacheDir(), avatarKey(bookName, characterName) + ".jpg")
        return if (f.isFile && f.length() > 0) f else null
    }

    /**
     * 解码角色立绘并**从顶部裁成正方形**（优先保留头部），按目标边长降采样。
     *
     * 立绘通常是竖版全身像：直接等比显示头部占比太小，centerCrop 居中裁剪
     * 又会把头裁掉。这里统一裁成「以宽度为边长、从最顶端开始」的正方形，
     * 保证头部完整可见，显示区域也可以更小。
     *
     * @param targetPx 目标显示边长（px），用于计算降采样倍数；解码失败返回 null
     */
    fun decodeAvatarTopSquare(file: File, targetPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        val bmp = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return@runCatching null
        val side = minOf(bmp.width, bmp.height)
        if (side <= 0) {
            bmp.recycle()
            return@runCatching null
        }
        if (bmp.width == side && bmp.height == side) {
            bmp
        } else {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, side, side)
            bmp.recycle()
            cropped
        }
    }.getOrNull()

    /**
     * 生成角色立绘。
     *
     * 与段落生图的区别：
     * - 缓存键为「书名 + 角色名」，同一角色全局只生成一次；
     * - 提示词由角色名 + 性别年龄段 + 档案人设构成，而非段落正文，保证形象稳定；
     * - 已在生成中的请求直接丢弃（并发去重）。
     *
     * @return 是否已受理并**保证回调**；false 表示被并发去重丢弃、不会有回调
     */
    fun generateCharacterAvatar(
        characterName: String,
        genderAge: String,
        bookName: String,
        identity: String = "",
        personality: String = "",
        background: String = "",
        regenerate: Boolean = false,
        templateId: Long = 0L,
        onSuccess: (File) -> Unit,
        onFailure: (Throwable) -> Unit
    ): Boolean {
        if (characterName.isBlank()) {
            onFailure(IllegalArgumentException("角色名为空"))
            return true
        }
        val key = avatarKey(bookName, characterName)
        if (regenerate) {
            runCatching { File(avatarCacheDir(), "$key.jpg").delete() }
        } else {
            getCachedAvatar(bookName, characterName)?.let {
                AppLog.put("AI立绘: 命中缓存 $characterName")
                onSuccess(it)
                return true
            }
        }
        // 同一角色并发去重
        if (avatarInFlight.putIfAbsent(key, true) != null) {
            if (regenerate) {
                runCatching { File(avatarCacheDir(), "$key.jpg").delete() }
                onFailure(IllegalStateException("旧立绘生成中，稍后重试"))
                return true
            }
            AppLog.put("AI立绘: 已在处理中，跳过 $characterName")
            return false
        }
        AppLog.put("AI立绘: 开始生成 $characterName（重新生成=$regenerate）")
        scope.launch {
            try {
                // 模板选择：优先用界面选定的模板（持久化），无效或未选时回落默认模板
                val template = runCatching {
                    if (templateId > 0L) appDb.aiImageTemplateDao.getById(templateId) else null
                }.getOrNull()
                    ?: runCatching { appDb.aiImageTemplateDao.getDefault() }.getOrNull()
                    ?: appDb.aiImageTemplateDao.getAllList().firstOrNull()
                if (template == null) {
                    throw IllegalStateException("未找到生图模板，请先在 AI 生图设置里配置模板")
                }
                val slots = buildKeySlots(listOf(template))
                if (slots.isEmpty()) {
                    throw IllegalStateException("生图模板「${template.name}」未配置接口与密钥")
                }
                val prompt = buildAvatarPrompt(
                    characterName, genderAge, bookName,
                    template.imageStyle, identity, personality, background
                )
                AppLog.put("AI立绘: 提示词 $prompt")
                val slot = slots[nextSlotIndex(slots.size)]
                // 多密钥/多模板轮换 + 重试一次
                var file: File? = null
                for (attempt in 1..2) {
                    val s = if (attempt == 1) slot else slots[nextSlotIndex(slots.size)]
                    file = try {
                        callImageApi(s.template, s.key, prompt, key)
                    } catch (e: Exception) {
                        AppLog.put("AI立绘第${attempt}次请求异常: ${e.message}")
                        null
                    }
                    if (file != null) break
                }
                file ?: throw IllegalStateException("生图失败，请稍后重试")
                // 落到角色专属缓存路径
                val dir = avatarCacheDir()
                if (!dir.exists() && !dir.mkdirs()) {
                    throw IllegalStateException("立绘缓存目录不可写: ${dir.absolutePath}")
                }
                val target = File(dir, "$key.jpg")
                if (file.absolutePath != target.absolutePath) {
                    file.copyTo(target, overwrite = true)
                }
                if (target.length() <= 0) {
                    throw IllegalStateException("立绘写入为空: ${target.absolutePath}")
                }
                AppLog.put("AI立绘: 生成成功 $characterName -> ${target.name}")
                withContext(Dispatchers.Main) { onSuccess(target) }
            } catch (e: Exception) {
                AppLog.put("AI立绘: 生成失败 $characterName ${e.message}")
                withContext(Dispatchers.Main) { onFailure(e) }
            } finally {
                avatarInFlight.remove(key)
            }
        }
        return true
    }

    /**
     * 角色立绘提示词：强调「人物立绘」与纯背景，避免肢体残缺、多人物或带文字。
     *
     * 画风来源：生图模板的 [AiImageTemplate.imageStyle]；未配置时用中性兜底，
     * 避免默认国风把任何题材都带成古装仙侠。
     * 人设依据（身份/性格/背景）来自 AI 角色档案，没有则仅用姓名+性别年龄。
     */
    private fun buildAvatarPrompt(
        characterName: String,
        genderAge: String,
        bookName: String,
        imageStyle: String,
        identity: String = "",
        personality: String = "",
        background: String = ""
    ): String {
        val who = if (genderAge.isBlank()) characterName else "${genderAge}角色「${characterName}」"
        val style = imageStyle.trim().takeIf { it.isNotBlank() } ?: AVATAR_NEUTRAL_STYLE
        val personaCues = buildList {
            if (identity.isNotBlank()) add("身份：${identity.take(40)}")
            if (personality.isNotBlank()) add("性格：${personality.take(40)}")
            if (background.isNotBlank()) add("背景履历：${background.take(80)}")
        }
        return buildString {
            if (bookName.isNotBlank()) append("小说《${bookName}》")
            append("中的${who}的人物立绘。")
            if (personaCues.isNotEmpty()) {
                append("人物设定：").append(personaCues.joinToString("；")).append("。")
                append("请据此表现其气质、神态与服饰。")
            }
            append("要求：单人全身或大半身立绘，居中构图，面部清晰完整，")
            append("表情自然，姿态放松。")
            append("画面风格：${style}。")
            append("纯色简洁背景，不要文字，不要水印，不要边框，")
            append("不要多余人物，不要残缺肢体，不要手部特写。")
        }
    }
}
