package io.legado.app.model

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiProfileTemplate
import io.legado.app.data.entities.BgmAIProvider
import io.legado.app.data.entities.CharacterProfile
import io.legado.app.help.book.BookHelp
import io.legado.app.help.hunyuan.HunyuanLocalGateway
import io.legado.app.service.AiChapterFixService
import io.legado.app.tts.voice.ScriptCharacterRepository
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import splitties.init.appCtx
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * AI 角色档案管理器。
 *
 * 朗读脚本只维护 `姓名/别名/性别/年龄` 四字段（且只在角色首次出现时写一次），
 * 本管理器在 App 侧独立建立**完整人设档案**：身份、性格、外形特征、说话风格、
 * 人物关系、背景履历，并在朗读推进过程中不断追加事件时间线。
 *
 * 模型接入与「AI处理章节」同构：接口配置存 aiProfileTemplate 表
 * （接口@@模型@@密钥，3 段一组可多组轮换），UI 输入框直填、输入即保存。
 *
 * 设计要点：
 * 1. **与朗读解耦**：由 [onCharactersDetected] 接收脚本上报的角色名，
 *    全部在后台单线程串行执行，失败静默，绝不阻塞朗读。
 * 2. **每角色只建档一次**：之后再出现只累加 [CharacterProfile.mentionCount]，
 *    直到「跨过足够章节」或「新增足够台词」才做一次增量更新（见 [needIncremental]）。
 * 3. **覆盖全章正文**：建档上下文取当前章节正文（优先该角色相关段落），信息更充分。
 * 4. **老档案作为初始值**：[seedLegacy] 把脚本 `characterRecords.json` 里的
 *    角色导入成"仅基础字段"的档案，后续由增量补全人设。
 */
object CharacterProfileManager {

    /** 建档上下文最大字符数 */
    private const val MAX_CONTEXT_CHARS = 6000
    /** 单个角色失败后的冷却时间，避免反复打 API */
    private const val FAIL_COOLDOWN_MS = 5 * 60_000L
    /** 兜底台词缓存有效期：避免每次分析日志都全量扫章节目录 */
    private const val FALLBACK_TTL_MS = 10 * 60_000L

    /** 兜底台词缓存条数上限（key = 书 × 角色），超限按缓存时间淘汰最旧的 */
    private const val MAX_DIALOG_FALLBACK = 64

    /** 失败冷却表条数上限（超限清空即可，只影响失败重试节流） */
    private const val MAX_FAILED_AT = 256

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val serial = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** 正在处理的角色，避免重复入队 */
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    /** 角色 → 上次失败时间 */
    private val failedAt = ConcurrentHashMap<String, Long>()
    /** (书名\u0000角色名) → (缓存时间, 全书台词)，章节缓存取不到时的兜底 */
    private val dialogFallback = ConcurrentHashMap<String, Pair<Long, List<String>>>()
    /** 非角色名（脚本的兜底占位） */
    private val SKIP_NAMES = setOf("旁白", "系统", "未知", "群众", "众人", "其他", "不明")

    /** 已导入过老档案的书（持久化，避免重装/重启后反复导入） */
    private const val KEY_SEEDED_BOOKS = "characterProfileSeededBooks"
    /** 用户已删除的档案：bookUrl|name（持久化，删掉后不再自动重建） */
    private const val KEY_DELETED_PROFILES = "characterProfileDeletedProfiles"

    /** 明显不是人物的名字（脚本 AI 误判产物，如「江语嫣的手机」） */
    private val NAME_NOISE_WORDS = listOf(
        "手机", "电话", "电脑", "声音", "屏幕", "消息", "短信", "视频", "图片",
        "东西", "提示", "界面", "系统", "按钮", "文字", "内容"
    )

    /** 书籍标识：与脚本体系一致，按书名区分 */
    fun bookKey(bookName: String): String = "script:" + bookName.trim()

    /** 群演/泛称类名字：脚本降级时常产出「群众女青年」「路人甲」这类，不建档 */
    private val CROWD_WORDS = listOf(
        "群众", "众人", "路人", "围观", "侍卫", "士兵", "家丁", "丫鬟", "随从",
        "弟子们", "师兄弟", "甲乙丙", "甲乙", "某某", "旁人", "大家", "其他人"
    )

    private fun isSkipName(n: String): Boolean {
        val name = n.trim()
        if (name.isBlank() || name in SKIP_NAMES) return true
        // 「群众女青年」「路人甲」这类泛称一律跳过
        return CROWD_WORDS.any { name.contains(it) }
    }

    /** 名字是否明显不是人物（脚本 AI 误判产物，如「江语嫣的手机」） */
    private fun isNoiseName(n: String): Boolean {
        val name = n.trim()
        if (name.length > 12) return true
        return NAME_NOISE_WORDS.any { name.contains(it) }
    }

    // ==================== 模板与模型调用（对齐 AI 处理章节范式） ====================

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 独立客户端（挂混元太极本地网关拦截器；非流式调用用 callTimeout 兜底） */
    private val client = OkHttpClient.Builder()
        .addInterceptor(HunyuanLocalGateway)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    /** 多组 API 轮换计数器 */
    private val apiCounter = AtomicLong(0)

    @Volatile
    private var templateCache: AiProfileTemplate? = null
    @Volatile
    private var templateCacheAt = 0L

    /** 当前生效的档案模板（默认模板；带 30s 缓存，避免每次分析都查库） */
    fun getTemplate(): AiProfileTemplate? {
        val cached = templateCache
        val now = System.currentTimeMillis()
        if (cached != null && now - templateCacheAt < 30_000L) return cached
        val fresh = runCatching {
            appDb.aiProfileTemplateDao.getDefault()
                ?: builtinDefaultTemplate()
        }.getOrNull()
        if (fresh != null) {
            templateCache = fresh
            templateCacheAt = now
        }
        return fresh
    }

    /** 使模板缓存失效（模板保存后调用，让新配置立即生效） */
    fun invalidateTemplateCache() {
        templateCache = null
        templateCacheAt = 0L
    }

    /** 内置默认模板：默认走内置混元太极本地网关（与其他 AI 功能同一条路线） */
    fun builtinDefaultTemplate(): AiProfileTemplate = AiProfileTemplate(
        id = DEFAULT_TEMPLATE_ID,
        name = "默认档案模板",
        apiConfig = DEFAULT_PROFILE_API,
        prompt = "",
        chapterGap = 5,
        retryCount = 1,
        timeoutSeconds = 240,
        autoEnable = true,
        isDefault = true
    )

    /** 默认档案接口：混元太极本地网关（进程内直连，无需启动代理端口） */
    private const val DEFAULT_PROFILE_API = "http://localhost:8801/v1@@g3@@00"

    const val DEFAULT_TEMPLATE_ID = -920260913L

    /** 是否启用了自动建档（模板开关） */
    private fun autoEnable(): Boolean = getTemplate()?.autoEnable == true

    /**
     * 单轮 OpenAI 兼容对话（非流式），messages = system + user。
     * 失败抛异常，由调用方按模板 retryCount 与多组 API 轮换重试。
     */
    private fun chatOnce(
        provider: BgmAIProvider,
        system: String,
        user: String,
        maxTokens: Int
    ): String {
        val body = org.json.JSONObject().apply {
            put("model", provider.modelId)
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().put("role", "system").put("content", system))
                put(org.json.JSONObject().put("role", "user").put("content", user))
            })
        }
        val request = Request.Builder()
            .url("${provider.url}/chat/completions")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = runCatching { resp.body?.string().orEmpty() }.getOrDefault("").take(300)
                throw RuntimeException("HTTP ${resp.code} $err")
            }
            val text = runCatching {
                val json = org.json.JSONObject(resp.body?.string().orEmpty())
                json.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
            }.getOrDefault("")
            if (text.isBlank()) throw RuntimeException("AI 未返回内容")
            return text
        }
    }

    /** 带多组接口轮换 + 模板重试次数的对话调用；全部失败返回 null */
    private fun chatWithRetry(
        system: String,
        user: String,
        maxTokens: Int,
        onFailMsg: ((String) -> Unit)? = null
    ): String? {
        val template = getTemplate()
        val providers = AiChapterFixService.parseApiConfigs(template?.apiConfig.orEmpty())
        if (providers.isEmpty()) {
            onFailMsg?.invoke("未配置接口，请在角色档案设置里填写（接口@@模型@@密钥）")
            return null
        }
        val retries = (template?.retryCount ?: 1).coerceAtLeast(0)
        val timeout = (template?.timeoutSeconds ?: 240).coerceAtLeast(10)
        var lastError = ""
        repeat(retries + 1) { attempt ->
            // 每次尝试（含重试）按顺序轮换一组 API，分散限流压力
            val provider = providers[apiCounter.getAndIncrement().mod(providers.size)]
            try {
                // 沿用全局客户端超时上限；模板超时只用于日志提示
                val raw = chatOnce(provider, system, user, maxTokens)
                AppLog.putDebug("角色档案: AI返回 ${raw.length} 字符")
                return raw
            } catch (e: Exception) {
                lastError = "第${attempt + 1}/${retries + 1}次尝试失败（API第${apiCounter.get().mod(providers.size) + 1}组）：${e.localizedMessage}"
                AppLog.put("角色档案: $lastError")
            }
        }
        onFailMsg?.invoke(lastError)
        return null
    }

    // ====== 持久化集合：已导入的书 / 用户已删除的档案 ======

    private fun readStringSet(key: String): MutableSet<String> {
        val raw = appCtx.getPrefString(key)
        if (raw.isNullOrBlank()) return mutableSetOf()
        return runCatching {
            val arr = JsonParser.parseString(raw).asJsonArray
            val set = mutableSetOf<String>()
            arr.forEach { if (it.isJsonPrimitive) set.add(it.asString) }
            set
        }.getOrDefault(mutableSetOf())
    }

    private fun writeStringSet(key: String, set: Set<String>) {
        val arr = JsonArray()
        set.take(2000).forEach { arr.add(it) }
        appCtx.putPrefString(key, arr.toString())
    }

    /** 该档案是否被用户删过；删过就不再自动重建，避免「删了又冒出来」 */
    private fun isDeleted(bookUrl: String, name: String): Boolean =
        readStringSet(KEY_DELETED_PROFILES).contains("$bookUrl|$name")

    private fun markDeleted(bookUrl: String, name: String) {
        val set = readStringSet(KEY_DELETED_PROFILES)
        set.add("$bookUrl|$name")
        writeStringSet(KEY_DELETED_PROFILES, set)
    }

    private fun unmarkDeleted(bookUrl: String, name: String) {
        val set = readStringSet(KEY_DELETED_PROFILES)
        if (set.remove("$bookUrl|$name")) {
            writeStringSet(KEY_DELETED_PROFILES, set)
        }
    }

    /**
     * 朗读过程中发现角色（由脚本章节缓存更新时触发）。
     *
     * @param bookName 书名
     * @param chapterTitle 当前章节标题
     * @param chapterIndex 当前章节下标，用于计算增量间隔
     * @param names 本批识别出的角色名
     * @param force true=跳过模板 autoEnable 检查（切章补全有自己的独立开关）
     * @param onComplete 本批全部处理完毕后的回调（在串行队列线程执行，可能滞后），
     *        参数为过滤后的有效角色名；切章补全流程据此为缺立绘的角色补图
     */
    fun onCharactersDetected(
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        names: Collection<String>,
        force: Boolean = false,
        onComplete: ((List<String>) -> Unit)? = null
    ) {
        if (!force && !autoEnable()) {
            onComplete?.invoke(emptyList())
            return
        }
        if (bookName.isBlank()) {
            onComplete?.invoke(emptyList())
            return
        }
        val valid = names.map { it.trim() }.filter { !isSkipName(it) }.distinct()
        if (valid.isEmpty()) {
            onComplete?.invoke(emptyList())
            return
        }
        scope.launch(serial) {
            runCatching { handle(bookName, chapterTitle, chapterIndex, valid) }
                .onFailure { AppLog.put("角色档案处理异常: ${it.message}", it) }
            onComplete?.invoke(valid)
        }
    }

    /**
     * 手动重建某个角色的档案（UI 用）。
     *
     * @param chapterIndex 当前章节下标；传 -1 时自动回退到全书台词兜底
     * @param chapterTitle 当前章节标题，用于取该章对话归属
     * @param onDone 在**主线程**回调，参数为(更新后的档案, 失败原因)；成功时原因为 null
     */
    fun regenerate(
        bookName: String,
        name: String,
        chapterIndex: Int = -1,
        chapterTitle: String = "",
        onDone: ((CharacterProfile?, String?) -> Unit)? = null
    ) {
        scope.launch(serial) {
            var error: String? = null
            val result = runCatching {
                failedAt.remove(bookKey(bookName) + "\u0000" + name)
                // 用户主动重建 → 解除删除标记
                unmarkDeleted(bookKey(bookName), name)
                rebuild(bookName, name, chapterIndex, chapterTitle)
            }.onFailure { error = it.message ?: "建档失败（未知原因）" }.getOrNull()
            if (onDone != null) {
                val msg = error
                Handler(Looper.getMainLooper()).post { onDone(result, msg) }
            }
        }
    }

    /**
     * 角色合并时同步合并档案：把 [mergedNames] 的档案并入 [mainName]，
     * 并触发一次 AI 请求，基于合并前的多份档案重新整理出一份完整档案。
     *
     * @param onDone 在**主线程**回调，返回合并后的档案；无档案可合并时返回 null
     */
    fun mergeProfiles(
        bookName: String,
        mainName: String,
        mergedNames: List<String>,
        onDone: ((CharacterProfile?) -> Unit)? = null
    ) {
        scope.launch(serial) {
            val result = runCatching { doMergeProfiles(bookName, mainName, mergedNames) }.getOrNull()
            if (onDone != null) {
                Handler(Looper.getMainLooper()).post { onDone(result) }
            }
        }
    }

    private suspend fun doMergeProfiles(
        bookName: String,
        mainName: String,
        mergedNames: List<String>
    ): CharacterProfile? {
        val bookUrl = bookKey(bookName)
        val dao = appDb.characterProfileDao
        val main = dao.get(bookUrl, mainName)
            ?: CharacterProfile(bookUrl = bookUrl, name = mainName)
        val others = mergedNames
            .filter { it != mainName }
            .mapNotNull { n -> dao.get(bookUrl, n) ?: findByAlias(bookUrl, n) }
        if (others.isEmpty() && main.identity.isBlank() && main.personality.isBlank()) {
            return null
        }
        // 1) 基础合并
        var merged = baseMerge(main, others)
        // 2) 删除被合并方的档案（只保留主名）
        mergedNames.filter { it != mainName }.forEach { dao.delete(bookUrl, it) }
        dao.insert(merged.copy(updatedAt = System.currentTimeMillis()))
        // 3) 让 AI 基于合并前的多份档案重新整理
        val refined = requestMerge(bookName, main, others)
        if (refined != null) {
            merged = applyMergeRefine(merged, refined)
            dao.insert(merged.copy(updatedAt = System.currentTimeMillis()))
            AppLog.put("角色档案已合并并经 AI 整理：$mainName（并入 ${others.size} 份）")
        } else {
            AppLog.put("角色档案已合并（AI 整理未执行）：$mainName（并入 ${others.size} 份）")
        }
        return merged
    }

    /** 不做 AI 调用的结构化合并：别名/时间线/出场数累加，空字段由被合并方补全 */
    private fun baseMerge(main: CharacterProfile, others: List<CharacterProfile>): CharacterProfile {
        val aliasSet = LinkedHashSet<String>()
        main.aliases.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEach { aliasSet.add(it) }
        var timeline = main.timeline
        var mention = main.mentionCount
        var confidence = main.confidence
        var lastIndex = main.lastChapterIndex
        var lastChapter = main.lastChapter
        for (o in others) {
            aliasSet.add(o.name.trim())
            o.aliases.split("|").map { it.trim() }.filter { it.isNotBlank() }.forEach { aliasSet.add(it) }
            timeline = mergeTimeline(timeline, o.timeline)
            mention += o.mentionCount
            confidence = maxOf(confidence, o.confidence)
            if (o.lastChapterIndex > lastIndex) {
                lastIndex = o.lastChapterIndex
                lastChapter = o.lastChapter
            }
        }
        fun pick(cur: String, get: (CharacterProfile) -> String): String =
            cur.ifBlank { others.firstNotNullOfOrNull { get(it).takeIf { v -> v.isNotBlank() } } ?: "" }
        val (gender, age) = splitGenderAge(pick(main.gender) { it.gender }, pick(main.age) { it.age })
        return main.copy(
            aliases = aliasSet.joinToString("|"),
            gender = gender,
            age = age,
            identity = pick(main.identity) { it.identity },
            personality = pick(main.personality) { it.personality },
            speakingStyle = pick(main.speakingStyle) { it.speakingStyle },
            background = pick(main.background) { it.background },
            traits = if (main.traits.isBlank() || main.traits == "[]") {
                others.firstOrNull { it.traits.isNotBlank() && it.traits != "[]" }?.traits ?: main.traits
            } else main.traits,
            relations = if (main.relations.isBlank() || main.relations == "[]") {
                others.firstOrNull { it.relations.isNotBlank() && it.relations != "[]" }?.relations ?: main.relations
            } else main.relations,
            timeline = timeline,
            firstChapter = main.firstChapter.ifBlank {
                others.firstOrNull { it.firstChapter.isNotBlank() }?.firstChapter ?: ""
            },
            lastChapter = lastChapter,
            lastChapterIndex = lastIndex,
            mentionCount = mention,
            lastUpdateMention = mention,
            confidence = confidence
        )
    }

    private fun requestMerge(
        bookName: String,
        main: CharacterProfile,
        others: List<CharacterProfile>
    ): CharacterProfile? {
        if (others.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("【主名】").append(main.name).append('\n')
        sb.append("【档案 1】\n").append(describe(main)).append("\n\n")
        others.forEachIndexed { i, o ->
            sb.append("【档案 ").append(i + 2).append("｜原角色名：").append(o.name).append("】\n")
                .append(describe(o)).append("\n\n")
        }
        val user = MERGE_PROMPT
            .replace("{book}", bookName)
            .replace("{main}", main.name)
            .replace("{profiles}", sb.toString())
        val raw = chatWithRetry(SYSTEM_PROMPT, user, 1200)
        return raw?.let { parseProfile(it) }
    }

    /** 用 AI 整理结果覆盖合并档案；时间线取并集避免丢失 */
    private fun applyMergeRefine(
        merged: CharacterProfile,
        refined: CharacterProfile
    ): CharacterProfile = merged.copy(
        gender = refined.gender.takeIf { it.isNotBlank() } ?: merged.gender,
        age = refined.age.takeIf { it.isNotBlank() } ?: merged.age,
        identity = refined.identity.takeIf { it.isNotBlank() } ?: merged.identity,
        personality = refined.personality.takeIf { it.isNotBlank() } ?: merged.personality,
        speakingStyle = refined.speakingStyle.takeIf { it.isNotBlank() } ?: merged.speakingStyle,
        background = refined.background.takeIf { it.isNotBlank() } ?: merged.background,
        traits = refined.traits.takeIf { it.isNotBlank() && it != "[]" } ?: merged.traits,
        relations = refined.relations.takeIf { it.isNotBlank() && it != "[]" } ?: merged.relations,
        timeline = mergeTimeline(merged.timeline, refined.timeline),
        confidence = maxOf(merged.confidence, refined.confidence)
    )

    // ==================== 查询 / 维护（供 UI 调用） ====================

    fun getProfiles(bookName: String): List<CharacterProfile> = runCatching {
        appDb.characterProfileDao.getByBook(bookKey(bookName))
    }.getOrDefault(emptyList())
        // 信息充分度（confidence）高的排前面；同分时按出场频度、名字稳定排序
        .sortedWith(
            compareByDescending<CharacterProfile> { it.confidence }
                .thenByDescending { it.mentionCount }
                .thenBy { it.name }
        )

    fun getProfile(bookName: String, name: String): CharacterProfile? = runCatching {
        appDb.characterProfileDao.get(bookKey(bookName), name)
            ?: findByAlias(bookKey(bookName), name)
    }.getOrNull()

    fun save(profile: CharacterProfile) {
        runCatching {
            appDb.characterProfileDao.insert(profile.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * 发音人推断出的性别/年龄写回档案。
     *
     * @param force true=用户**主动分配**发音人，发音人即权威来源，直接覆盖档案的性别/年龄；
     *              false=**被动回补**，仅在档案对应字段为空时补入。
     */
    fun applyVoiceGenderAge(
        bookName: String,
        name: String,
        gender: String,
        age: String,
        force: Boolean = false
    ) {
        if (gender.isBlank() && age.isBlank()) return
        scope.launch(serial) {
            runCatching {
                val bookUrl = bookKey(bookName)
                val dao = appDb.characterProfileDao
                val existing = dao.get(bookUrl, name) ?: findByAlias(bookUrl, name) ?: return@launch
                val ng = when {
                    gender.isBlank() -> existing.gender
                    force -> gender
                    existing.gender.isBlank() -> gender
                    else -> existing.gender
                }
                val na = when {
                    age.isBlank() -> existing.age
                    force -> age
                    existing.age.isBlank() -> age
                    else -> existing.age
                }
                if (ng == existing.gender && na == existing.age) return@launch
                dao.update(existing.copy(gender = ng, age = na, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun delete(bookName: String, name: String) {
        val bookUrl = bookKey(bookName)
        runCatching { appDb.characterProfileDao.delete(bookUrl, name) }
        // 记入删除名单：之后朗读不再为它自动建档（用户主动「重新生成」可解除）
        markDeleted(bookUrl, name)
    }

    fun clearBook(bookName: String) {
        val bookUrl = bookKey(bookName)
        // 清空前先把现有档案记入删除名单，避免清空后被老档案导入或重新建档带回来
        runCatching {
            appDb.characterProfileDao.getNames(bookUrl).forEach { markDeleted(bookUrl, it) }
        }
        runCatching { appDb.characterProfileDao.deleteByBook(bookUrl) }
    }

    /**
     * 存量数据清洗：剔除各档案中与其他角色**主名**冲突的别名，
     * 顺带拆分混写的性别年龄并补全漏填的关系。后台执行。
     */
    fun sanitizeAllAliases(bookName: String) {
        scope.launch(serial) {
            runCatching {
                val bookUrl = bookKey(bookName)
                val dao = appDb.characterProfileDao
                val all = dao.getByBook(bookUrl)
                if (all.isEmpty()) return@runCatching
                val mainNames = all.map { it.name.trim() }.toHashSet()
                var changed = 0
                for (p in all) {
                    val cleaned = p.aliases.split("|")
                        .map { it.trim() }
                        .filter {
                            it.isNotBlank() && it != p.name &&
                                it.length in 2..8 && it !in mainNames
                        }
                        .distinct()
                        .take(5)
                        .joinToString("|")
                    val (g, a) = splitGenderAge(p.gender, p.age)
                    val rel = if (p.relations.isBlank() || p.relations == "[]") {
                        extractRelations("${p.identity} ${p.background} ${p.personality}")
                    } else {
                        p.relations
                    }
                    if (cleaned != p.aliases || g != p.gender || a != p.age || rel != p.relations) {
                        dao.update(p.copy(aliases = cleaned, gender = g, age = a, relations = rel))
                        changed++
                    }
                }
                if (changed > 0) {
                    AppLog.put("已规范化 $changed 个角色档案（别名冲突/性别年龄拆分/关系补全）")
                }
            }
        }
    }

    // ==================== 内部处理 ====================

    private suspend fun handle(
        bookName: String,
        chapterTitle: String,
        chapterIndex: Int,
        names: List<String>
    ) {
        val bookUrl = bookKey(bookName)
        seedLegacy(bookUrl, bookName)
        // 允许读整章：取当前章节正文作为建档上下文
        val chapterText = readChapterText(bookName, chapterIndex)
        val dialogLines = collectDialogLines(bookName, chapterTitle)
        for (name in names) {
            // 误判名（如「XX的手机」）与用户已删除的角色，一律不再建档
            if (isNoiseName(name) || isDeleted(bookUrl, name)) continue
            val key = "$bookUrl\u0000$name"
            if (!inFlight.add(key)) continue
            try {
                val lastFail = failedAt[key] ?: 0L
                if (System.currentTimeMillis() - lastFail < FAIL_COOLDOWN_MS) continue
                // 本章没有该角色的任何专属材料 → 不请求 AI（避免拿别人的段落生成导致串档），
                // 但仍登记一次出场
                val dialogs = dialogLines[name].takeIf { !it.isNullOrEmpty() }
                    ?: if (dialogLines.isEmpty()) fallbackDialogs(bookName, name) else null
                val context = buildContext(name, chapterText, dialogs)
                if (context.isBlank()) {
                    touchMention(bookUrl, bookName, name, chapterTitle)
                    AppLog.put("角色「$name」本章无专属上下文，暂只记录出场")
                    continue
                }
                updateOrCreate(
                    bookUrl, bookName, name, chapterTitle, chapterIndex, context
                )
            } catch (e: Exception) {
                failedAt[key] = System.currentTimeMillis()
                trimFailedAt()
                AppLog.put("角色「$name」档案处理失败: ${e.message}", e)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun updateOrCreate(
        bookUrl: String,
        bookName: String,
        name: String,
        chapterTitle: String,
        chapterIndex: Int,
        context: String
    ) {
        val dao = appDb.characterProfileDao
        val existing = dao.get(bookUrl, name) ?: findByAlias(bookUrl, name)
        if (existing == null) {
            val legacy = legacyInfo(bookName, name)
            val created = requestCreate(
                bookName, name, legacy.first, legacy.second, legacy.third, context
            )
            // 建档失败也要写一条占位档案：保证「主名」这个锚点存在，
            // 否则下次该角色出现时主名查不到，会按别名误命中别人的档案
            val base = created ?: CharacterProfile(
                bookUrl = bookUrl,
                name = name,
                gender = legacy.first,
                age = legacy.second,
                confidence = 0.1f
            )
            dao.insert(
                base.copy(
                    bookUrl = bookUrl,
                    name = name,
                    aliases = sanitizeAliases(base.aliases, bookUrl, name),
                    firstChapter = chapterTitle,
                    lastChapter = chapterTitle,
                    lastChapterIndex = chapterIndex,
                    mentionCount = 1,
                    lastUpdateMention = 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (created != null) {
                AppLog.put("角色档案已建立：$name｜${created.identity.ifBlank { "身份未知" }}")
            } else {
                AppLog.put("角色「$name」建档未成功，已写入占位档案（后续会自动补全）")
            }
            return
        }
        val mention = existing.mentionCount + 1
        if (!needIncremental(existing, chapterIndex, mention)) {
            dao.update(existing.copy(mentionCount = mention, lastChapter = chapterTitle))
            return
        }
        val patch = requestPatch(bookName, existing, context)
        val merged = applyPatch(existing, patch)
        dao.update(
            merged.copy(
                mentionCount = mention,
                lastUpdateMention = mention,
                lastChapter = chapterTitle,
                lastChapterIndex = chapterIndex,
                updatedAt = System.currentTimeMillis()
            )
        )
        AppLog.put("角色档案已更新：$name（第 ${chapterIndex + 1} 章）")
    }

    private fun needIncremental(p: CharacterProfile, chapterIndex: Int, mention: Int): Boolean {
        // 本章已经更新过一次 → 不再重复请求（避免同一章被反复打 API）
        if (chapterIndex >= 0 && p.lastChapterIndex == chapterIndex) return false
        // 档案还不完整（缺身份/性格/背景，或置信度偏低）→ 每章都补一次，尽早成型
        val incomplete = p.identity.isBlank() || p.personality.isBlank() ||
            p.background.isBlank() || p.confidence < 0.6f
        if (incomplete) return true
        if (chapterIndex >= 0 && p.lastChapterIndex >= 0 &&
            chapterIndex - p.lastChapterIndex >= chapterGap()
        ) {
            return true
        }
        return mention - p.lastUpdateMention >= mentionGap()
    }

    /** 更新章节间隔（模板里配置） */
    private fun chapterGap(): Int = (getTemplate()?.chapterGap ?: 5).coerceIn(1, 10)

    /** 新增台词阈值：随章节间隔联动，间隔越小阈值越低 */
    private fun mentionGap(): Int = (chapterGap() * 5).coerceIn(5, 40)

    private suspend fun rebuild(
        bookName: String,
        name: String,
        chapterIndex: Int,
        chapterTitle: String = ""
    ): CharacterProfile? {
        val bookUrl = bookKey(bookName)
        val dao = appDb.characterProfileDao
        val old = dao.get(bookUrl, name)
        // 没有可用接口时直接给出明确原因
        val providers = AiChapterFixService.parseApiConfigs(getTemplate()?.apiConfig.orEmpty())
        if (providers.isEmpty()) {
            throw IllegalStateException("未配置档案接口，请在「角色档案设置」里填写（接口@@模型@@密钥）")
        }
        val chapterText = if (chapterIndex >= 0) readChapterText(bookName, chapterIndex) else null
        // dialogs 不能固定传 null：那样在 chapterIndex<0（UI 直接调用）时上下文恒为空，
        // 手动建档会 100% 失败并误报成「接口未配置」
        var dialogs: List<String>? = if (chapterTitle.isNotBlank()) {
            collectDialogLines(bookName, chapterTitle)[name]
        } else null
        if (dialogs.isNullOrEmpty()) {
            // 兜底：全书范围找该角色的台词
            ScriptCharacterRepository.collectDialogsByName(bookName, name)
                .takeIf { it.isNotEmpty() }?.let { dialogs = it }
        }
        val context = buildContext(name, chapterText, dialogs)
        if (context.isBlank()) {
            if (old != null) {
                AppLog.put("角色「$name」未找到专属上下文，跳过重新生成")
                return old
            }
            throw IllegalStateException("未找到《$name》的台词或相关段落，无法建档（可先朗读一段该角色的内容再试）")
        }
        val legacy = legacyInfo(bookName, name)
        val created = requestCreate(
            bookName, name,
            old?.gender ?: legacy.first,
            old?.age ?: legacy.second,
            old?.aliases ?: legacy.third,
            context
        ) ?: throw IllegalStateException("AI 未返回有效档案，请稍后重试（详情见日志）")
        val merged = created.copy(
            bookUrl = bookUrl,
            name = name,
            aliases = old?.aliases ?: sanitizeAliases(created.aliases, bookUrl, name),
            firstChapter = old?.firstChapter ?: "",
            lastChapter = old?.lastChapter ?: "",
            lastChapterIndex = old?.lastChapterIndex ?: -1,
            mentionCount = old?.mentionCount ?: 1,
            lastUpdateMention = old?.mentionCount ?: 1,
            timeline = old?.timeline ?: created.timeline
        )
        dao.insert(merged.copy(updatedAt = System.currentTimeMillis()))
        return merged
    }

    /**
     * 老档案导入：把脚本 `characterRecords.json` 的角色作为初始值写入。
     * 每本书只做一次；已存在的角色不覆盖。
     */
    private fun seedLegacy(bookUrl: String, bookName: String) {
        // 持久化「已导入」标记：整本书只导入一次，
        // 否则每次冷启动/重装都会把脚本 characterRecords.json 的旧角色重新灌进来
        val seeded = readStringSet(KEY_SEEDED_BOOKS)
        if (seeded.contains(bookUrl)) return
        seeded.add(bookUrl)
        writeStringSet(KEY_SEEDED_BOOKS, seeded)
        runCatching {
            val records = ScriptCharacterRepository.getCharacterRecords(bookName)
            if (records.isEmpty()) return
            val existed = appDb.characterProfileDao.getNames(bookUrl).toHashSet()
            val now = System.currentTimeMillis()
            var count = 0
            for (rec in records.values) {
                val name = rec.name.trim()
                if (name.isBlank() || name in existed || isSkipName(name)) continue
                // 过滤误判产物与用户已删除的角色
                if (isNoiseName(name) || isDeleted(bookUrl, name)) continue
                val (gender, age) = splitGenderAge(rec.gender, rec.age)
                appDb.characterProfileDao.insert(
                    CharacterProfile(
                        bookUrl = bookUrl,
                        name = name,
                        aliases = rec.aliases,
                        gender = gender,
                        age = age,
                        confidence = 0.3f,
                        updatedAt = now
                    )
                )
                count++
            }
            if (count > 0) {
                AppLog.put("已从旧角色档案导入 $count 个角色（《$bookName》）")
            }
        }.onFailure {
            AppLog.put("导入旧角色档案失败: ${it.message}", it)
        }
    }

    /** 从脚本老档案里取性别/年龄/别名 */
    private fun legacyInfo(bookName: String, name: String): Triple<String, String, String> {
        val rec = runCatching { ScriptCharacterRepository.findRecord(name, bookName) }.getOrNull()
            ?: return Triple("", "", "")
        return Triple(rec.gender, rec.age, rec.aliases)
    }

    /**
     * 按别名反查档案。调用方已先按主名查过，这里只做**严格别名**匹配：
     * - 名字长度 < 2 不参与（单字称呼极易误命中）
     * - 排除主名相同的条目（那种情况由主名查询负责）
     */
    private fun findByAlias(bookUrl: String, name: String): CharacterProfile? {
        if (name.trim().length < 2) return null
        return runCatching {
            appDb.characterProfileDao.getByBook(bookUrl).firstOrNull { p ->
                p.name != name && p.aliases.split("|").any { it.trim() == name }
            }
        }.getOrNull()
    }

    /**
     * 别名清洗 —— 串档的另一个主要来源。
     * AI 建档时常常把主角的称呼写进配角 aliases，之后主角出场时按别名命中的
     * 就是配角档案。这里强制剔除任何与**其他角色主名**相同的别名。
     */
    private fun sanitizeAliases(raw: String, bookUrl: String, selfName: String): String {
        val others = runCatching { appDb.characterProfileDao.getNames(bookUrl) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() && it != selfName }
            .toHashSet()
        return raw.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() && it != selfName }
            .filter { it.length in 2..8 }
            .filter { it !in others }
            .distinct()
            .take(5)
            .joinToString("|")
    }

    /** 读整章正文（用户允许读整章） */
    private fun readChapterText(bookName: String, chapterIndex: Int): String? = runCatching {
        if (chapterIndex < 0) return null
        val book: io.legado.app.data.entities.Book = appDb.bookDao.findByName(bookName).firstOrNull() ?: return null
        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return null
        BookHelp.getContent(book, chapter)
    }.getOrNull()

    /** 从脚本章节缓存聚合「角色 → 台词」作为兜底上下文 */
    private fun collectDialogLines(bookName: String, chapterTitle: String): Map<String, List<String>> {
        val roles = runCatching {
            ScriptCharacterRepository.getChapterRoles(bookName, chapterTitle)
        }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, MutableList<String>>()
        for (role in roles.values) {
            val text = role.dialogText
            if (role.isNarration || text.isBlank()) continue
            map.getOrPut(role.name) { ArrayList() }.add(text)
        }
        return map
    }

    /** 章节缓存整体取不到时的兜底：全书范围找该角色的台词（带 TTL 缓存） */
    private fun fallbackDialogs(bookName: String, name: String): List<String> {
        val key = "$bookName\u0000$name"
        val now = System.currentTimeMillis()
        dialogFallback[key]?.let { (at, lines) ->
            if (now - at < FALLBACK_TTL_MS) return lines
        }
        val lines = runCatching {
            ScriptCharacterRepository.collectDialogsByName(bookName, name)
        }.getOrDefault(emptyList())
        dialogFallback[key] = now to lines
        trimDialogFallback()
        return lines
    }

    /** 淘汰过期的兜底台词，超限时按缓存时间淘汰最旧的 */
    private fun trimDialogFallback() {
        if (dialogFallback.size <= MAX_DIALOG_FALLBACK) return
        val expireBefore = System.currentTimeMillis() - FALLBACK_TTL_MS
        dialogFallback.entries.removeIf { it.value.first < expireBefore }
        while (dialogFallback.size > MAX_DIALOG_FALLBACK) {
            val oldestKey = dialogFallback.minByOrNull { it.value.first }?.key ?: break
            dialogFallback.remove(oldestKey)
        }
    }

    /** 失败冷却表超限时清理（先清过期，仍超限则整体清空） */
    private fun trimFailedAt() {
        if (failedAt.size <= MAX_FAILED_AT) return
        val expireBefore = System.currentTimeMillis() - FAIL_COOLDOWN_MS
        failedAt.entries.removeIf { it.value < expireBefore }
        if (failedAt.size > MAX_FAILED_AT) {
            failedAt.clear()
        }
    }

    /**
     * 记一次出场：还没有档案就写一条占位（保留主名锚点），已有则累加 mentionCount。
     */
    private fun touchMention(
        bookUrl: String,
        bookName: String,
        name: String,
        chapterTitle: String
    ) {
        runCatching {
            val dao = appDb.characterProfileDao
            val existing = dao.get(bookUrl, name) ?: findByAlias(bookUrl, name)
            if (existing == null) {
                val legacy = legacyInfo(bookName, name)
                val (gender, age) = splitGenderAge(legacy.first, legacy.second)
                dao.insert(
                    CharacterProfile(
                        bookUrl = bookUrl,
                        name = name,
                        gender = gender,
                        age = age,
                        confidence = 0f,
                        firstChapter = chapterTitle,
                        lastChapter = chapterTitle,
                        mentionCount = 1,
                        lastUpdateMention = 0,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                dao.update(
                    existing.copy(
                        mentionCount = existing.mentionCount + 1,
                        lastChapter = chapterTitle
                    )
                )
            }
        }
    }

    /**
     * 构造**该角色专属**的上下文。
     *
     * 关键点：**绝不回退到整章正文**——只喂该角色的台词与正文中提到该角色
     * 姓名的段落，两者都没有 → 返回空串，宁缺勿错（避免把主角履历写进配角档案）。
     */
    private fun buildContext(name: String, chapterText: String?, dialogs: List<String>?): String {
        val sb = StringBuilder()
        val lines = dialogs?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        if (lines.isNotEmpty()) {
            sb.append("【该角色在本章的台词】\n")
            lines.take(12).forEachIndexed { i, d ->
                sb.append(i + 1).append(". ").append(d.take(120)).append('\n')
            }
        }
        if (!chapterText.isNullOrBlank()) {
            val paras = chapterText.split('\n')
                .map { it.trim() }
                .filter { mentionsRole(it, name) }
            if (paras.isNotEmpty()) {
                sb.append("【正文中提到「").append(name).append("」的段落】\n")
                var budget = MAX_CONTEXT_CHARS
                for (p in paras.take(15)) {
                    if (budget <= 0) break
                    val s = p.take(budget)
                    sb.append(s).append('\n')
                    budget -= s.length
                }
            }
        }
        return sb.toString()
    }

    /** 段落是否确实在说这个角色：长度合理且出现该角色全名 */
    private fun mentionsRole(paragraph: String, name: String): Boolean {
        if (paragraph.length < 4 || paragraph.length > 1200) return false
        return paragraph.contains(name)
    }

    // ==================== AI 请求 ====================

    private fun requestCreate(
        bookName: String,
        name: String,
        gender: String,
        age: String,
        aliases: String,
        context: String
    ): CharacterProfile? {
        val user = CREATE_PROMPT
            .replace("{book}", bookName)
            .replace("{name}", name)
            .replace(
                "{known}",
                "性别：${gender.ifBlank { "未知" }}；年龄段：${age.ifBlank { "未知" }}；别名：${aliases.ifBlank { "无" }}"
            )
            .replace("{context}", context)
        val raw = chatWithRetry(SYSTEM_PROMPT, user, 1200)
        return raw?.let { parseProfile(it) }
    }

    private fun requestPatch(
        bookName: String,
        existing: CharacterProfile,
        context: String
    ): JsonObject? {
        val user = PATCH_PROMPT
            .replace("{book}", bookName)
            .replace("{name}", existing.name)
            .replace("{profile}", describe(existing))
            .replace("{context}", context)
        val raw = chatWithRetry(SYSTEM_PROMPT, user, 1000)
        return raw?.let { runCatching { extractJsonObject(it) }.getOrNull() }
    }

    private fun parseProfile(raw: String): CharacterProfile? {
        val obj = runCatching { extractJsonObject(raw) }.getOrNull() ?: return null
        fun str(k: String): String =
            obj.get(k)?.takeIf { !it.isJsonNull }?.asString?.trim() ?: ""
        fun arr(k: String): String = runCatching {
            obj.get(k)?.takeIf { it.isJsonArray }?.asJsonArray?.toString()
        }.getOrNull() ?: "[]"
        val conf = runCatching { obj.get("confidence")?.asFloat }.getOrNull() ?: 0.5f
        val identity = str("identity")
        val background = str("background")
        // AI 常把「某某的徒弟」写进身份却漏填 relations，这里兜底抽一次
        val relationsRaw = arr("relations")
        val relations = if (relationsRaw.isBlank() || relationsRaw == "[]") {
            extractRelations("$identity $background ${str("personality")}")
        } else {
            relationsRaw
        }
        // 性别 / 年龄必须拆开存，避免存成「男少年」这种混写
        val (gender, age) = splitGenderAge(str("gender"), str("age"))
        return CharacterProfile(
            bookUrl = "",
            name = str("name"),
            aliases = arrToListString(obj, "aliases"),
            gender = gender,
            age = age,
            identity = identity,
            personality = str("personality"),
            traits = arr("traits"),
            speakingStyle = str("speakingStyle"),
            relations = relations,
            background = background,
            timeline = arr("timeline"),
            confidence = conf.coerceIn(0f, 1f)
        )
    }

    /**
     * 拆分性别与年龄段：统一规整成 性别 ∈ {男, 女}、年龄 ∈ {童, 少年, 青年, 中年, 老年}。
     */
    fun splitGenderAge(gender: String, age: String): Pair<String, String> {
        var g = gender.trim()
        var a = age.trim()
        // 规整脚本侧常见的重复前缀
        a = a.replace(Regex("^男少年"), "少年")
            .replace(Regex("^女少女"), "少女")
            .replace(Regex("^男男"), "")
            .replace(Regex("^女女"), "")
        // age 自带性别前缀 → 仅当性别未知时拆出性别，避免「女童」被拆回「童」
        if (a.startsWith("男") || a.startsWith("女")) {
            if (g.isBlank()) {
                g = a.substring(0, 1)
                a = a.substring(1)
            }
        }
        // gender 里混了年龄段 → 拆出来
        if (g.length > 1 && a.isBlank()) {
            val rest = g.substring(1)
            if (rest.contains("童") || rest.contains("少") || rest.contains("青") ||
                rest.contains("中") || rest.contains("老")
            ) {
                a = rest
                g = g.substring(0, 1)
            }
        }
        if (g.isBlank()) {
            if (a.contains("女")) g = "女" else if (a.contains("男")) g = "男"
        }
        if (g.length > 1) g = g.substring(0, 1)
        return g to a
    }

    /** 关系词表 */
    private const val RELATION_WORDS =
        "父亲|母亲|师父|师傅|老师|徒弟|弟子|师兄|师弟|师姐|师妹|师叔|师伯|师祖|同门|" +
            "哥哥|弟弟|姐姐|妹妹|兄长|儿子|女儿|妻子|丈夫|夫人|未婚妻|未婚夫|恋人|" +
            "朋友|好友|兄弟|对手|敌人|仇人|主人|下属|手下|伙伴|搭档|爷爷|奶奶|外公|外婆|舅舅|" +
            "义兄|义弟|义父|义母|师尊|恩师|师娘"

    /** 形式一：人名在前 —— 「张三的徒弟」「张三徒弟」 */
    private val RELATION_PATTERN =
        Regex("([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})的?($RELATION_WORDS)")

    /** 形式二： —— 「张三之子」「张三之徒」 */
    private val RELATION_PATTERN_ZHI =
        Regex("([\\u4e00-\\u9fa5A-Za-z0-9]{1,8})之(子|女|徒|师|父|母|兄|弟|姐|妹|妻|夫)")

    /** 形式三：关系词在前 —— 「师父是张三」「师父：张三」「师承张三」 */
    private val RELATION_PATTERN_REV =
        Regex("(?:师承|拜|认)?($RELATION_WORDS)\\s*(?:是|为|叫|名叫|乃)?\\s*[:：]?\\s*([\\u4e00-\\u9fa5]{2,4})")

    private val ZHI_MAP = mapOf(
        "子" to "儿子", "女" to "女儿", "徒" to "徒弟", "师" to "师父",
        "父" to "父亲", "母" to "母亲", "兄" to "哥哥", "弟" to "弟弟",
        "姐" to "姐姐", "妹" to "妹妹", "妻" to "妻子", "夫" to "丈夫"
    )

    /**
     * 从身份/背景/性格文本里兜底抽取人物关系（AI 漏填 relations 时的正则回抽）。
     */
    private fun extractRelations(text: String): String {
        if (text.isBlank()) return "[]"
        val arr = JsonArray()
        val seen = HashSet<String>()
        val invalid = setOf("他", "她", "其", "它", "自己", "对方", "此人", "别人", "有人", "某人")
        fun add(rawTarget: String, rel: String) {
            if (arr.size() >= 8) return
            val target = rawTarget.trim()
            if (target.isBlank() || target.length !in 1..8) return
            if (target in invalid) return
            if (target.startsWith("一个") || target.startsWith("这位") || target.startsWith("那位")) return
            if (!seen.add("$target|$rel")) return
            val o = JsonObject()
            o.addProperty("target", target)
            o.addProperty("relation", rel)
            arr.add(o)
        }
        for (m in RELATION_PATTERN.findAll(text)) add(m.groupValues[1], m.groupValues[2])
        for (m in RELATION_PATTERN_ZHI.findAll(text)) {
            val rel = ZHI_MAP[m.groupValues[2]] ?: continue
            add(m.groupValues[1], rel)
        }
        for (m in RELATION_PATTERN_REV.findAll(text)) add(m.groupValues[2], m.groupValues[1])
        return if (arr.size() == 0) "[]" else arr.toString()
    }

    private fun arrToListString(obj: JsonObject, key: String): String =
        runCatching {
            obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { e -> !e.isJsonNull }?.asString }
                ?.joinToString("|") ?: ""
        }.getOrDefault("")

    /** 把增量结果合并进已有档案 */
    private fun applyPatch(existing: CharacterProfile, patch: JsonObject?): CharacterProfile {
        if (patch == null) return existing
        var result = existing
        // 1) 追加事件时间线（按 章节+事件 去重）
        runCatching {
            val arr = patch.get("newTimeline")?.takeIf { it.isJsonArray }?.asJsonArray
            if (arr != null && arr.size() > 0) {
                result = result.copy(timeline = mergeTimeline(existing.timeline, arr.toString()))
            }
        }
        // 2) 修订字段
        runCatching {
            val rev = patch.get("revised")?.takeIf { it.isJsonObject }?.asJsonObject
            if (rev != null) {
                fun s(k: String): String? =
                    rev.get(k)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotBlank() }
                val identity = s("identity") ?: result.identity
                val background = s("background") ?: result.background
                // 性别/年龄拆开存，避免增量把「男少年」这类混写带进来
                val (g, a) = splitGenderAge(s("gender") ?: result.gender, s("age") ?: result.age)
                result = result.copy(
                    identity = identity,
                    personality = s("personality") ?: result.personality,
                    background = background,
                    speakingStyle = s("speakingStyle") ?: result.speakingStyle,
                    gender = g,
                    age = a,
                    relations = result.relations.takeIf { it.isNotBlank() && it != "[]" }
                        ?: extractRelations("$identity $background")
                )
            }
        }
        // 3) 置信度取更高者
        runCatching {
            val c = patch.get("confidence")?.asFloat
            if (c != null && c > result.confidence) {
                result = result.copy(confidence = c.coerceIn(0f, 1f))
            }
        }
        return result
    }

    /** 合并两条事件时间线（JSON 数组字符串），按 章节+事件 去重 */
    private fun mergeTimeline(a: String, b: String): String {
        val arr = runCatching {
            JsonParser.parseString(a.ifBlank { "[]" }).asJsonArray
        }.getOrElse { JsonArray() }
        val add = runCatching {
            JsonParser.parseString(b.ifBlank { "[]" }).asJsonArray
        }.getOrNull() ?: return arr.toString()
        val seen = HashSet<String>()
        arr.forEach { e -> if (e.isJsonObject) seen.add(timelineKey(e.asJsonObject)) }
        add.forEach { e ->
            if (e.isJsonObject) {
                val o = e.asJsonObject
                val k = timelineKey(o)
                if (k.isNotBlank() && seen.add(k)) arr.add(o)
            }
        }
        return arr.toString()
    }

    private fun timelineKey(o: JsonObject): String {
        val chapter = o.get("chapter")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        val event = o.get("event")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return "$chapter|$event"
    }

    /** 档案 → 给 AI 看的简要描述 */
    private fun describe(p: CharacterProfile): String {
        fun pretty(json: String, label: String): String {
            if (json.isBlank() || json == "[]") return ""
            return runCatching {
                val arr = JsonParser.parseString(json).asJsonArray
                if (arr.size() == 0) return ""
                val items = arr.mapNotNull { e ->
                    if (!e.isJsonObject) {
                        // 特征数组元素是纯字符串
                        if (e.isJsonPrimitive) e.asString else null
                    } else {
                        val o = e.asJsonObject
                        val a = o.get("chapter")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        val b = o.get("event")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        val c = o.get("target")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        val d = o.get("relation")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                        when {
                            b.isNotBlank() -> "$a：$b"
                            d.isNotBlank() -> "$c（$d）"
                            else -> o.toString()
                        }
                    }
                }
                if (items.isEmpty()) "" else "$label：" + items.joinToString("；")
            }.getOrDefault("")
        }
        val parts = ArrayList<String>()
        if (p.identity.isNotBlank()) parts.add("身份：${p.identity}")
        if (p.personality.isNotBlank()) parts.add("性格：${p.personality}")
        if (p.speakingStyle.isNotBlank()) parts.add("说话风格：${p.speakingStyle}")
        if (p.background.isNotBlank()) parts.add("背景：${p.background}")
        pretty(p.traits, "特征").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        pretty(p.relations, "关系").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        pretty(p.timeline, "已知经历").takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return if (parts.isEmpty()) "（暂无）" else parts.joinToString("\n")
    }

    private fun extractJsonObject(raw: String): JsonObject {
        val s = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalArgumentException("AI 未返回 JSON 对象")
        return JsonParser.parseString(s.substring(start, end + 1)).asJsonObject
    }

    private const val SYSTEM_PROMPT =
        "你是专业的小说人物档案编撰助手。只输出合法 JSON，不要任何解释、不要 Markdown 代码块。"

    private val CREATE_PROMPT = """
        请为小说《{book}》中的角色「{name}」建立档案。

【已知信息】
{known}

【可参考内容】（以下材料均与该角色直接相关，其中的其他人物仅作背景）
{context}

【输出要求】
严格输出如下 JSON 对象（不要数组，不要代码块）：
{
  "name": "角色主名",
  "aliases": ["别名1", "别名2"],
  "gender": "男 或 女",
  "age": "童 / 少年 / 青年 / 中年 / 老年 之一",
  "identity": "身份，如：青云门长老；看不出就空字符串",
  "personality": "性格，40 字以内",
  "traits": ["外形或习惯特征，最多 3 条"],
  "speakingStyle": "说话风格，25 字以内",
  "relations": [{"target": "相关角色名", "relation": "关系，如：师徒"}],
  "background": "出身与背景履历，100 字以内",
  "timeline": [{"chapter": "章节或时间点", "event": "事件，30 字以内"}],
  "confidence": 0.6
}

【规则】
1. 只依据与「{name}」直接相关的信息推断，不要编造；不确定就留空并把 confidence 调低
2. 【严禁】把内容中其他人物的身份、经历、履历、人际关系写成「{name}」的内容
3. 若「{name}」只是被提到名字、没有实际戏份，请留空所有字段并把 confidence 设到 0.2 以下
4. aliases 只能是该人物本人的其他称呼（字号/绰号/昵称），且必须出现在与该角色相关的内容中；禁止填写其他角色的名字
5. confidence 为 0~1 小数，表示信息充分程度
6. gender 只填「男」或「女」；age 只填 童/少年/青年/中年/老年 之一，不要写「男少年」这种混写
7. 若身份或背景里出现「某人的儿子/徒弟/师兄/朋友」等关系，必须在 relations 中逐条列出
8. 只输出 JSON 本身
    """.trimIndent()

    private val PATCH_PROMPT = """
        角色「{name}」在小说《{book}》中的现有档案：
{profile}

以下是后续章节中与该角色有关的内容：
{context}

请输出**增量更新**，严格 JSON：
{
  "newTimeline": [{"chapter": "章节", "event": "事件"}],
  "revised": {"identity": "", "personality": "", "background": "", "speakingStyle": ""},
  "confidence": 0.8
}

【规则】
1. newTimeline 只写本次内容里「{name}」**本人新出现**的事件；没有就给空数组
2. 【严禁】把本章其他人物的经历写成「{name}」的经历，只写与该角色直接相关的新事件
3. 若「{name}」在本段内容中没有实质戏份，newTimeline 给空数组、revised 全留空
4. revised 只填需要修正的字段，未变化或不确定就留空字符串
5. confidence 为整合全部信息后的总体充分度
6. 只输出 JSON 本身
    """.trimIndent()

    private val MERGE_PROMPT = """
        小说《{book}》中的下列档案，经确认实为**同一个人物**的不同称呼/别名。
请以「{main}」为主名，把所有档案整合为一份完整、无矛盾的档案。

{profiles}

【输出要求】
严格输出如下 JSON 对象（不要数组，不要代码块）：
{
  "name": "{main}",
  "aliases": ["所有出现过的称呼"],
  "gender": "男 或 女",
  "age": "童 / 少年 / 青年 / 中年 / 老年 之一",
  "identity": "整合后的身份",
  "personality": "整合后的性格，40 字以内",
  "traits": ["外形或习惯特征，最多 3 条"],
  "speakingStyle": "说话风格，25 字以内",
  "relations": [{"target": "相关角色名", "relation": "关系"}],
  "background": "整合后的出身与背景履历，120 字以内",
  "timeline": [{"chapter": "章节或时间点", "event": "事件"}],
  "confidence": 0.8
}

【规则】
1. 时间线合并去重、按先后排序；各档案冲突时以信息更具体者为准
2. 不要把不同人物的经历混进来；不确定就留空并调低 confidence
3. 只输出 JSON 本身
    """.trimIndent()
}
