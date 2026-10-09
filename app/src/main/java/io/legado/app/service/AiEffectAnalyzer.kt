package io.legado.app.service

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI 音效分析：两段式，先把段落提炼成关键字，再从候选列表里挑选。
 *
 * 之所以分两步：音效库有六千多条，一次性塞进 prompt 不现实，
 * 先用 AI 提取 4 个关键字缩小范围，再让 AI 在几十条候选里做最终选择。
 *
 * 输入一律带上「上一段 + 本段 + 下一段」，让 AI 能判断声音事件的边界
 * （例如上段已打雷、本段是余波，就不该再配一次雷声）。
 *
 * AI 供应商复用 BGM 的配置（[appDb.bgmAIProviderDao]），不新增一套。
 */
object AiEffectAnalyzer {

    private const val TAG = "AiEffectAnalyzer"

    /** 提示词在 bgmAIPrompt 表中的 name；表里没有时回退到内置默认 */
    const val PROMPT_KEYWORD = "音效关键字提取"
    const val PROMPT_PICK = "音效选择"

    /** 每段喂给 AI 的最大字数，超出截断，避免 prompt 过长 */
    private const val MAX_CHARS_PER_PARA = 500

    /** 关键字最大长度。音效库名字以 1~2 字为主，超过 2 字检索不到 */
    private const val MAX_KEYWORD_LEN = 2

    /**
     * 位置序号的锚点标点。
     * 段首固定为 1 号，之后每出现一个这里的标点，就在**该标点之后**再插一个序号。
     * 序号即「一句话的边界」，AI 挑中音效时回传序号，播放时就能落在对应的话上，
     * 而不是像 start/mid/end 那样只能落在三等分的位置。
     */
    private val ANCHOR_PUNCT = setOf('，', '。', '！', '？', '；', '：', '、', '…')

    /** 精选阶段模糊兜底的最小长度。1 个字太容易误配（"声"会命中"水声""风声"） */
    private const val MIN_FUZZY_LEN = 2

    /**
     * 候选少于这个数就启用高频单字兜底。
     * AI 常给"目光""书册"这类整词检索不到、但含可用单字的关键字，
     * 候选过少会让精选阶段没得选。
     */
    private const val FALLBACK_MIN_CANDIDATES = 35

    /** 兜底时取出现次数最多的几个单字 */
    private const val FALLBACK_TOP_CHARS = 10

    /** 兜底检索的候选上限 */
    private const val FALLBACK_POOL_LIMIT = 100

    /**
     * 每多少字配 1 个关键字。即「每 100 字 12 个」（约每 8.3 字 1 个）。
     * 用整数分母避免浮点，取整为 8，略多于 12 个/100 字。
     */
    private const val CHARS_PER_KEYWORD = 8

    /** 每段关键字的下限，避免极短段落拿到 0 个导致无法检索 */
    private const val MIN_KEYWORD_PER_PARA = 6

    /** 单段关键字的默认上限，当配置值过小时兜底 */
    private const val DEFAULT_MAX_KEYWORD_PER_PARA = 12

    /**
     * 单段关键字上限。
     * [AppConfig.aiEffectKeywordCount] 是**最大值**，实际数量按段落字数递减：
     * 每 100 字约 12 个，短段落相应减少，但不低于 [MIN_KEYWORD_PER_PARA]。
     *
     * 这样段落越碎，总关键字数不会随段数暴涨，候选池才够分。
     */
    private fun keywordLimit(): Int =
        AppConfig.aiEffectKeywordCount.coerceAtLeast(DEFAULT_MAX_KEYWORD_PER_PARA)

    /** 按段落字数算出该段实际要几个关键字 */
    private fun keywordQuota(textLen: Int): Int {
        val byChars = textLen / CHARS_PER_KEYWORD
        return byChars.coerceIn(MIN_KEYWORD_PER_PARA, keywordLimit())
    }

    /** 每段最多选几个音效 */
    private fun pickLimit(): Int = AppConfig.aiEffectMaxPick.coerceAtLeast(1)

    /**
     * 统计关键字里出现次数最多的若干单字。
     * 候选不足时用它兜底：整词无命中（如"书册"）时，单字（"书"）往往能命中
     * "翻开一本书""书店翻书"等。只统计汉字。
     */
    private fun topFrequentChars(keywords: Collection<String>, topN: Int): List<String> {
        val counter = LinkedHashMap<Char, Int>()
        for (kw in keywords) {
            for (ch in kw) {
                if (ch in '\u4e00'..'\u9fa5') {
                    counter[ch] = (counter[ch] ?: 0) + 1
                }
            }
        }
        return counter.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { it.key.toString() }
    }

    /**
     * 旧版内置提示词的特征串，用于 App 启动时判断是否需要升级。
     * 命中说明这条提示词仍是内置旧版、用户没改过，可以安全覆盖。
     *
     * **每次改动内置提示词，都要把上一版的特征串加进来**，
     * 否则老设备上的旧提示词匹配不上，会一直停在旧版（曾漏过"每 100 字给 12 个"这一版）。
     * 特征串取该版独有、用户自写提示词里不太可能出现的话。
     */
    val outdatedMarkers: List<String> = listOf(
        // 单段格式时期
        "【本段】",
        "输出 4 个关键字",
        "某段确实没有可识别声音就输出空数组",
        "每 100 字给 4 个",
        "每 100 字给 8 个",
        // 批量格式：让 AI 自己按字数算数量（与实际配额算法不一致）
        "每 100 字给 12 个",
        // 精选提示词：数量写死 3，未跟随 aiEffectMaxPick
        "每一段最多选 3 个",
        // 精选提示词：只用 start/mid/end 三档，无法定位到具体句子
        "at 表示触发时机",
        // 精选提示词：pos 只说"声音应该响起时正读到几号"，没强调"与描写句同时开始"，
        // AI 常把序号填在描写文字之后，声音慢半拍。本版改为"声音先起头、描写紧随"
        "这个声音应该响起时，朗读正读到几号",
        // 精选提示词：去重只有一句"同一段内不要挑含义重复的"，
        // 没说候选里存在大量同语义变体（材质/步数/题材不同、名字完全不像），
        // 关键词扩召回后 AI 常把"脚步"的多个版本一起挑上。本版单列"去重"一节
        "同一段内不要挑含义重复的"
    )

    /**
     * 批量分析若干段落。
     *
     * 一次调用里把整批段落都交给 AI，AI 回标段落编号，解析后按段分发。
     * 无论多少段，一批只花 2 次 AI 调用（关键字 + 精选）。
     *
     * @param paras 待分析段落，(段落索引, 段落文本)。索引需在整章内唯一。
     * @param prevOfFirst 批次第一段的上一段，作为开头段落的上文
     * @return 段落索引 -> 选中的音效。**保证包含 [paras] 里的每一个索引**，
     *         AI 漏掉的段补空列表。空列表由调用方决定如何处理
     *         （当前策略：不落盘，下次可重新识别）。
     *         整批失败（AI 不可用/异常/返回非法 JSON）时返回 null，不落盘，允许下次重试。
     */
    suspend fun analyzeBatch(
        paras: List<Pair<Int, String>>,
        prevOfFirst: String
    ): Map<Int, List<AiEffectPick>>? = withContext(Dispatchers.IO) {
        if (paras.isEmpty()) return@withContext emptyMap()
        AiEffectIndex.ensureLoaded()
        if (AiEffectIndex.size() == 0) {
            AppLog.put("$TAG: 音效索引为空，跳过分析")
            return@withContext null
        }
        val provider = appDb.bgmAIProviderDao.getEnabled()
        if (provider == null) {
            AppLog.put("$TAG: 未启用AI提供商")
            return@withContext null
        }

        // 1. 批量关键字：每段一组。null = 请求失败，整批放弃（不落盘，下次重试）
        val keywordsByPara = requestKeywordsBatch(provider, paras, prevOfFirst)
            ?: return@withContext null

        // 2. 所有段的关键字合并检索，共享一个候选池
        val allKeywords = keywordsByPara.values.flatten().distinct()
        if (allKeywords.isEmpty()) {
            // 请求成功但没有任何关键字，属于 AI 的有效判断，落盘避免重复分析
            AppLog.put("$TAG: 整批未提取到关键字")
            return@withContext paras.associate { it.first to emptyList() }
        }
        // 候选池：所有段的关键字合并检索，共享同一个池子，去重后即为发给 AI 的候选列表。
        // 总量固定为 aiEffectMaxCandidates（默认 300），不再随段数放大——
        // 候选是整批共用的，段数多不代表需要更多候选，放大只会撑爆 prompt。
        val poolLimit = AppConfig.aiEffectMaxCandidates.coerceAtLeast(1)
        var candidates = AiEffectIndex.searchByKeywords(
            allKeywords,
            AppConfig.aiEffectCandidatesPerKeyword,
            poolLimit
        )
        // 候选过少时启用高频单字兜底：
        // AI 常给"书册"这类整词无命中、但单字"书"能命中"翻开一本书""书店翻书"的关键字。
        if (candidates.size < FALLBACK_MIN_CANDIDATES) {
            val topChars = topFrequentChars(allKeywords, FALLBACK_TOP_CHARS)
            if (topChars.isNotEmpty()) {
                val before = candidates.size
                val extra = AiEffectIndex.searchByKeywords(
                    topChars,
                    AppConfig.aiEffectCandidatesPerKeyword,
                    FALLBACK_POOL_LIMIT
                )
                // 先保留原候选（更精确），再补充兜底命中
                val merged = LinkedHashMap<String, AiEffectIndex.Entry>()
                candidates.forEach { merged[it.name] = it }
                extra.forEach { merged.putIfAbsent(it.name, it) }
                // 合并后仍受总量约束：兜底单字命中面很广，不截断会远超 poolLimit
                val mergedSize = merged.size
                candidates = merged.values.take(poolLimit)
                AppLog.put(
                    "$TAG: 候选不足($before<${FALLBACK_MIN_CANDIDATES})，" +
                        "高频单字兜底 $topChars -> ${candidates.size}条" +
                        if (mergedSize > poolLimit) "（合并${mergedSize}条已截断）" else ""
                )
            }
        }
        if (candidates.isEmpty()) {
            AppLog.put("$TAG: 关键字无候选命中: $allKeywords")
            return@withContext paras.associate { it.first to emptyList() }
        }

        // 3. 批量精选：每段 0~N 个。失败则整批放弃，不落盘
        val picks = requestPicksBatch(provider, paras, prevOfFirst, candidates)
            ?: return@withContext null
        AppLog.put(
            "$TAG: 批量分析完成, ${paras.size}段, 关键字${allKeywords.size}个, " +
                "候选${candidates.size}条, 命中${picks.values.count { it.isNotEmpty() }}段"
        )
        // 补齐全段，AI 漏掉的段视为无音效
        paras.associate { (idx, _) -> idx to (picks[idx] ?: emptyList()) }
    }

    // ---------------- 阶段一：批量关键字 ----------------

    /**
     * 一次调用为整批段落各提取一组关键字。
     * 段与段本身就是彼此的上下文（相邻编号即相邻文本），无需再单独传上下文。
     */
    /**
     * @return 段落索引 -> 关键字。**null 表示请求失败**（网络错误等），
     *         与「成功但某段无关键字」区分开：失败时调用方不落盘，允许下次重试。
     */
    private suspend fun requestKeywordsBatch(
        provider: io.legado.app.data.entities.BgmAIProvider,
        paras: List<Pair<Int, String>>,
        prevOfFirst: String
    ): Map<Int, List<String>>? {
        val prompt = buildString {
            append(
                appDb.bgmAIPromptDao.getByName(PROMPT_KEYWORD)?.prompt
                    ?.takeIf { it.isNotBlank() } ?: KEYWORD_PROMPT
            )
            append("\n\n【正文段落（共${paras.size}段，编号即顺序，相邻编号互为上下文）】\n")
            if (prevOfFirst.isNotBlank()) {
                append("（上文提示：这些段落之前的内容是）").append(clip(prevOfFirst)).append("\n")
            }
            // 必须带编号：解析端用 p 对应段落，没有编号 AI 无从判断归属。
            // 字数用截断后的长度，数量由 keywordQuota 算好直接写明——
            // 让 AI 自己按字数换算既容易算错，又会生成一截被 take 丢掉的关键字。
            paras.forEachIndexed { i, (_, text) ->
                val clipped = clip(text)
                append("第${i + 1}段（${clipped.length}字，请给${keywordQuota(clipped.length)}个）：")
                    .append(clipped).append("\n")
            }
            append("\n请按段落顺序，为每一段各输出一组关键字，输出 JSON：")
        }
        val raw = try {
            // 输出整批段落的 JSON，长度不可预估，必须不限制 token
            BgmAIService.callOpenAIApi(provider, prompt, null)
        } catch (e: CancellationException) {
            // 切章/停止朗读导致的取消必须向上传播，否则会被当成「无关键字」落盘，
            // 把未分析的段落永久标记为无音效
            throw e
        } catch (e: Exception) {
            AppLog.put("$TAG: 批量关键字请求失败 ${e.localizedMessage}")
            return null
        }
        // null = 返回内容不是合法 JSON（多半被截断），按失败处理，不落盘
        return parseKeywordsBatch(raw, paras).also {
            if (it == null) {
                AppLog.put("$TAG: 批量关键字解析失败，原始返回: ${raw.take(300)}")
            }
        }
    }

    // ---------------- 阶段二：批量精选 ----------------

    /** @return 段落索引 -> 选中的音效，null 表示请求失败（不落盘） */
    private suspend fun requestPicksBatch(
        provider: io.legado.app.data.entities.BgmAIProvider,
        paras: List<Pair<Int, String>>,
        prevOfFirst: String,
        candidates: List<AiEffectIndex.Entry>
    ): Map<Int, List<AiEffectPick>>? {
        val prompt = buildString {
            append(
                appDb.bgmAIPromptDao.getByName(PROMPT_PICK)?.prompt
                    ?.takeIf { it.isNotBlank() } ?: PICK_PROMPT
            )
            // 候选是全批次共享的，必须提前说清，否则 AI 会把别段的声音安到本段。
            // 只发纯音效名：名字本身已能表达意思，分类（如"01_通用母库/拟音"）是音效库的
            // 管理维度而非声音语义，带上只会增加识别负担、还可能干扰判断。
            append("\n\n【候选音效列表（共${candidates.size}条，为所有段落共用）】\n")
            candidates.forEachIndexed { i, e ->
                append("${i + 1}. ${e.name}\n")
            }
            append("\n【正文段落（共${paras.size}段，编号即顺序）】\n")
            append("正文里 [n] 是位置序号：段首为 [1]，之后每个标点（，。！？；：、…）后面依次 +1。\n")
            if (prevOfFirst.isNotBlank()) {
                append("（上文提示：这些段落之前的内容是）").append(clip(prevOfFirst)).append("\n")
            }
            // 发带序号的正文：AI 直接看得到落点，回传的 pos 就是这个序号
            paras.forEachIndexed { i, (_, text) ->
                val marked = markText(clip(text))
                append("第${i + 1}段（${clip(text).length}字）：").append(marked.first).append("\n")
            }
            append("\n请为每一段各选 0~${pickLimit()} 个音效，用 p 标记归属、用 pos 标出位置序号，输出 JSON：")
        }
        val raw = try {
            // 输出整批段落的 JSON，长度不可预估，必须不限制 token
            BgmAIService.callOpenAIApi(provider, prompt, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("$TAG: 批量精选请求失败 ${e.localizedMessage}")
            return null
        }
        // 序号是按 clip 后的正文生成的，解析时用它校验 AI 回的 pos 是否越界
        val offsetsByPara = paras.associate { (idx, text) ->
            idx to markOffsets(clip(text))
        }
        return parsePicksBatch(raw, paras, candidates, offsetsByPara).also {
            if (it == null) {
                AppLog.put("$TAG: 批量精选解析失败，原始返回: ${raw.take(300)}")
            }
        }
    }

    // ---------------- 解析 ----------------

    /**
     * 解析批量关键字。
     * 期望 `{"paras":[{"p":1,"k":["雷","雨"]}, ...]}`，p 是 1 起的段序号。
     * AI 常把 p 写成字符串或漏掉某些段，这里都做容错；无法定位的段落按无关键字处理。
     *
     * @return null 表示**返回内容不是合法 JSON**（多半是输出被截断）。
     *         这与「JSON 合法但某段 k 为空」是两回事：后者是 AI 的有效判断，
     *         前者必须按失败处理，否则会把未分析的段落当作「无音效」落盘，永远不再重试。
     */
    private fun parseKeywordsBatch(
        raw: String,
        paras: List<Pair<Int, String>>
    ): Map<Int, List<String>>? {
        val obj = extractJsonObject(raw) ?: return null
        val arr = obj.optJSONArray("paras") ?: return null
        val out = HashMap<Int, List<String>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val order = item.optInt("p", -1)
            if (order < 1 || order > paras.size) continue
            val paraIndex = paras[order - 1].first
            val list = ArrayList<String>()
            item.optJSONArray("k")?.let { ka ->
                for (j in 0 until ka.length()) {
                    var s = ka.optString(j).trim().removeSuffix(".mp3").trim()
                    if (s.isEmpty()) continue
                    // 关键字限制 1~2 字：音效库名字以 1~2 字为主（雷、雨、脚步、开门），
                    // 超过 2 字检索不到。AI 偶尔给"炒菜声"，截断成"炒菜"即可命中。
                    if (s.length > MAX_KEYWORD_LEN) s = s.take(MAX_KEYWORD_LEN)
                    if (s !in list) list.add(s)
                }
            }
            // 按该段配额截断。用截断后的长度，与提示词里告知 AI 的数字保持一致
            out[paraIndex] = list.take(keywordQuota(clip(paras[order - 1].second).length))
        }
        return out
    }

    /**
     * 解析批量精选结果。
     * 期望 `{"picks":[{"p":1,"name":"雷","pos":3}, ...]}`，p 是 1 起的段序号，
     * pos 是正文里插入的位置序号（见 [markText]），决定音效落在哪句话上。
     * 同一段可能被重复提及，按 [AppConfig.aiEffectMaxPick] 截断并去重。
     *
     * pos 缺失或越界（AI 编的号）时置 0，由 [ratioOfPos] 回退到 at 的三档位置。
     *
     * @return null 表示**返回内容不是合法 JSON**（多半是输出被截断），按失败处理不落盘。
     */
    private fun parsePicksBatch(
        raw: String,
        paras: List<Pair<Int, String>>,
        candidates: List<AiEffectIndex.Entry>,
        offsetsByPara: Map<Int, List<Int>>
    ): Map<Int, List<AiEffectPick>>? {
        val obj = extractJsonObject(raw) ?: return null
        val arr = obj.optJSONArray("picks") ?: return null
        val byName = HashMap<String, AiEffectIndex.Entry>()
        candidates.forEach { byName[it.name] = it }
        val maxPick = AppConfig.aiEffectMaxPick.coerceAtLeast(1)
        val out = HashMap<Int, MutableList<AiEffectPick>>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val order = item.optInt("p", -1)
            if (order < 1 || order > paras.size) continue
            val paraIndex = paras[order - 1].first
            val name = item.optString("name").trim().removeSuffix(".mp3").trim()
            val entry = matchCandidate(byName, candidates, name) ?: continue
            val list = out.getOrPut(paraIndex) { ArrayList() }
            if (list.size >= maxPick) continue
            if (list.any { it.name == entry.name }) continue
            // pos 只认本段真实存在的序号，越界视为没给，回退到 at
            val posRaw = item.optInt("pos", 0)
            val maxPos = offsetsByPara[paraIndex]?.size ?: 0
            val pos = if (posRaw in 1..maxPos) posRaw else 0
            list.add(
                AiEffectPick(
                    entry.name,
                    entry.path,
                    normalizeAt(item.optString("at")),
                    pos
                )
            )
        }
        return out
    }

    /** 去掉 ```json 包裹，截取第一个 { 到最后一个 } */
    private fun extractJsonObject(raw: String): JSONObject? {
        var text = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            JSONObject(text.substring(s, e + 1))
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeAt(at: String): String = when (at.trim().lowercase()) {
        "mid", "middle", "中" -> "mid"
        "end", "tail", "尾" -> "end"
        else -> "start"
    }

    /**
     * 在候选里按名字查找 AI 回传的音效，三级递进：
     *
     * 1. **精确匹配**：AI 原样照抄候选名。
     * 2. **清洗后再匹配**：候选行是 `12. 炒菜`，
     *    AI 常把序号一起抄回来，去掉后重试。
     * 3. **改写兜底**：AI 自己做了措辞调整，如库里叫"炒菜"它写"炒菜声"、
     *    库里叫"雷声"它写"雷"。见 [matchByPrefix]。
     *
     * 兜底**只在候选范围内**查找，所以不会产生凭空的音效：
     * 匹配不上的说明是 AI 幻觉出的名字，照旧返回 null 由调用方丢弃。
     */
    private fun matchCandidate(
        byName: Map<String, AiEffectIndex.Entry>,
        candidates: List<AiEffectIndex.Entry>,
        rawName: String
    ): AiEffectIndex.Entry? {
        byName[rawName]?.let { return it }
        val cleaned = cleanName(rawName)
        if (cleaned.isEmpty()) return null
        byName[cleaned]?.let { return it }
        return matchByPrefix(candidates, cleaned)
    }

    /**
     * 前缀方向匹配，处理 AI 的措辞改写。
     *
     * 只认**前缀**、不用 `contains`：后者会让泛词误中——AI 写"声"会命中"水声""风声"，
     * 而"声"根本不是有效音效名。两个方向覆盖绝大多数改写：
     * - AI 写简称（"雷"），库里是全称（"雷声"）→ 取**最短**的候选，最贴近原意
     * - AI 写全称（"炒菜声"），库里是简称（"炒菜"）→ 取**最长**的候选，信息最全
     */
    private fun matchByPrefix(
        candidates: List<AiEffectIndex.Entry>,
        cleaned: String
    ): AiEffectIndex.Entry? {
        if (cleaned.length < MIN_FUZZY_LEN) return null
        candidates.asSequence()
            .filter { it.name.startsWith(cleaned) }
            .minByOrNull { it.name.length }?.let { return it }
        return candidates.asSequence()
            .filter { cleaned.startsWith(it.name) && it.name.length >= MIN_FUZZY_LEN }
            .maxByOrNull { it.name.length }
    }

    /**
     * 清洗 AI 回传的音效名。
     * 候选行是 `12. 炒菜`，AI 常把序号一起抄回来，这里去掉后只留纯音效名。
     */
    private fun cleanName(raw: String): String {
        val noIndex = raw.replace(Regex("^\\d+\\s*[.、:：)\\]}]\\s*"), "")
        return noIndex.substringBefore("（").substringBefore("(").trim()
    }

    private fun clip(text: String): String =
        if (text.length <= MAX_CHARS_PER_PARA) text else text.take(MAX_CHARS_PER_PARA)

    // ---------------- 位置序号 ----------------

    /**
     * 段落内所有位置序号在**原文**中的字符偏移（序号从 1 起，故下标 0 对应 1 号）。
     * 段首为 1 号（偏移 0），之后每个 [ANCHOR_PUNCT] 标点之后依次 +1。
     */
    fun markOffsets(text: String): List<Int> {
        val offsets = ArrayList<Int>()
        offsets.add(0)
        for (i in text.indices) {
            if (text[i] !in ANCHOR_PUNCT) continue
            // 连续标点（"……"、"？？"、"。，"、"！！!")只认最后一个：
            // 连着插两三个序号没有意义，反而让 AI 多出一堆同一位置的候选号
            if (i + 1 < text.length && text[i + 1] in ANCHOR_PUNCT) continue
            offsets.add(i + 1)
        }
        return offsets
    }

    /**
     * 给段落插入位置序号，供第二次请求（精选）发给 AI。
     *
     * 例：`他推开门，外面在下雨。` → `[1]他推开门，[2]外面在下雨。[3]`
     *
     * @return (带序号的文本, 序号1起 -> 该序号在**原文本**中的字符偏移)。
     *         偏移量以原文为准，序号本身不计入，方便换算播放位置。
     */
    fun markText(text: String): Pair<String, List<Int>> {
        val offsets = markOffsets(text)
        val sb = StringBuilder(text.length + offsets.size * 4)
        var cursor = 0
        offsets.forEachIndexed { i, off ->
            if (off > cursor) {
                sb.append(text, cursor, off)
                cursor = off
            }
            sb.append('[').append(i + 1).append(']')
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString() to offsets
    }

    /**
     * 位置序号 -> 段落内的触发比例（0~1）。
     *
     * 序号合法时按原文偏移换算，**与 [markText] 用的是同一套锚点**，因此
     * AI 回哪个序号，音效就落在那句话上。
     * 序号缺失或越界（AI 幻觉/旧记录）时回退到 [at] 的三档位置。
     */
    fun ratioOfPos(text: String, pos: Int, at: String): Float {
        if (pos > 0) {
            val clipped = clip(text)
            if (clipped.isNotEmpty()) {
                val offsets = markOffsets(clipped)
                if (pos <= offsets.size) {
                    return (offsets[pos - 1].toFloat() / clipped.length).coerceIn(0f, 1f)
                }
            }
        }
        return when (at) {
            "mid" -> 0.5f
            "end" -> 0.98f
            else -> 0f
        }
    }

    /** 内置默认关键字提示词，App 启动时写入 bgmAIPrompt 表供用户修改 */
    const val KEYWORD_PROMPT = """你是广播剧音效设计师。下面有若干段小说正文，请**为每一段分别**提炼用于检索音效库的关键字。

## 数量
- 每段要几个，已直接写在该段标题里（"第N段（X字，请给Y个）"）。**严格按 Y 给，不多不少。**
- 凑不满 Y 个时，给最接近的几个即可，不要为了凑数把同一个声音换个说法重复写。

## 用词
- **每个关键字严格只能 1~2 个字**，必须是具体名词或具体动作。
- 1 个字：雷、雨、风、剑、门、马、火、水、钟、铃、枪、猫、狗、鸟、哭、笑、喘、咳。
- 2 个字：脚步、开门、炒菜、倒水、玻璃、心跳、呼吸、雷声、马蹄、鸟鸣、关门、上楼、点火、写字、翻书、碗筷、车声、人群。
- **不要输出 3 个字及以上的词**。"炒菜声"要写成"炒菜"，"脚步声"要写成"脚步"，"雷声隆隆"要写成"雷"。
- 禁止泛词："声音""音效""背景""气氛""环境""场景"这类词检索不到任何音效。

## 取材
- 只提炼**该段真实发生**的声音。
- 相邻段落一并提供，是为了让你判断声音事件的**边界与是否重复**：上一段已经打雷、本段只是余波，本段就不要再给"雷"。不要把别段的声音算到本段头上。
- 特别长的段落只取前 500 字，只按你看到的内容提炼，不要脑补被截掉的部分。
- **不要轻易给空数组**。只有纯粹的心理描写、抽象叙述、抒情议论才给空数组；只要段落里有任何动作、物体、环境、人声或可联想的声响，就给关键字——给了关键字还有机会在后续筛选中剔除，给了空数组就完全失去检索机会。

## 输出格式
严格只输出如下 JSON，不要解释、不要 markdown 代码块。
p 是段落序号，从 1 开始；k 是该段的关键字数组。每一段都要有一条：
{"paras":[{"p":1,"k":["炒菜","油锅","脚步","雷","开门","雨"]},{"p":2,"k":[]}]}"""

    /** 内置默认精选提示词，App 启动时写入 bgmAIPrompt 表供用户修改 */
    const val PICK_PROMPT = """你是广播剧音效设计师。请为下面**每一段**正文分别从候选音效列表中挑选合适的音效。

## 数量与归属
- 每段最多选的个数已在末尾写明，最少选 0 个；某段没有合适的声音就不要为它输出任何条目。
- **p 是段落序号，从 1 开始**，必须与输入段落标题"第N段"的编号对应。
- 输入有几段就要判断几段，不要只处理前几段。

## 怎么挑（按意思挑）
- 候选列表是**按关键词检索**出来的，只保证大致相关，里面必然混着对不上的噪声。
  你的任务是：理解本段**实际发生了什么声音**，再从列表里挑意思最对得上的那个。
- **按意思挑，不要求正文里出现候选名的字面**：
  段落写"厨房里传来滋啦一声"，候选里有"炒菜"，就该挑"炒菜"；
  段落写"他推门出去了"，候选里有"开门"或"关门"，按实际动作挑。
- 反过来，**字面像但意思不对的不要挑**：段落只是"谈起去年那场雨"、当下并没下雨，就不要挑"雨"。
- 只挑**该段实际发生**的声音。不要挑"氛围合适"的铺垫式音效（那是背景音乐的活，不是音效）。
- 【候选音效列表】是所有段落共用的，混有其它段落的声音。**只能挑本段真实发生的**，不要把别段的声音安到本段头上。
- **只能从列表里挑，不能自己编名字**。就算你觉得有更贴切的音效，只要不在列表里就不能写。

## 去重（最容易犯的错）
- **候选里同一个声音往往有几十个版本**：只是材质、远近、步数、题材不同，名字却完全不像。
  例如下面这些**全是"脚步"**——
  `草地脚步一步`、`大理石皮鞋一步`、`木地板赤脚缓慢一步`、`瓷砖运动鞋正常一步`、`楼上脚步传导`、`行军脚步小队`
  **它们算同一个声音，每段最多挑 1 个**，挑材质与场景最贴切当下的那个。
  挑两个等于把同一个声音放了两遍，听感上就是明显的重复音效。
- **同义不同名也算重复**：脚步/走路/步行、开门/推门、打雷/雷声/炸雷/闷雷、鸟鸣/鸟叫、
  关门/合门、上楼/登楼、跑步/奔跑。挑了其中一个，就不要再挑另一个。
- **相邻段落之间同样要去重**：一批段落里同一个声音**只配在它第一次出现的那一段**。
  上一段已经响过、本段只是延续或余波的，不要重复挑。
- 每段的数量是**上限，不是指标**：一段里真的只有一件事发生，就只挑 1 个。
  宁可少挑，也不要把同一个声音换个名字再挑一次来凑数。

## 怎么写（照抄，不要改写）
- name 只写纯音效名：照抄"N. "后面的那一个词，**不要带序号、不要加 .mp3**。
- 不要在列表的名字上做任何修改：列表里是"炒菜"就写"炒菜"，不要写成"炒菜声"。

## 位置：pos 填正文里的序号
- 正文每个段落都插了位置序号：段首是 [1]，之后每个标点（，。！？；：、…）后面依次是 [2]、[3]……
  例：[1]他推开门，[2]外面下起了雨。[3]
  序号站在**它后面那句话的开头**：[2] 就是"外面下起了雨"这句话的起点。

### 核心：声音要和描写它的那句话**同时开始**，不能等那句话读完才响
音效播放要占一段时间。正确顺序是——
**声音先起头，紧接着朗读才读到描写它的文字**，
这样声音还在响的时候正好听到"他推开门""下起了雨"，听感才对得上。
反过来，等描写文字都读完了声音才响，就变成慢半拍的画外音，明显突兀。

### 填法：找到**描写这个声音的那句话**，填这句话**开头**的序号
- ✅ `他推开门，外面下起了雨。` 打成 `[1]他推开门，[2]外面下起了雨。[3]`
  - 开门声填 **1**——声音和"他推开门"同时开始
  - 雨声填 **2**——声音和"外面下起了雨"同时开始
- ❌ **最常见的错**：开门声填 2。那样声音要等"他推开门"整句读完才响，
  此时已经在读"外面下起了雨"，开门声整整晚了一句话。
- ❌ **不要因为"前面已经描写过这个声音了"就把序号往后挪一位**。
  序号表示的是**声音何时开始响**，不是"描写文字读到哪了"。
  描写在前面，声音就该在描写**之前**起头，而不是之后补一个标记。
- 一句话里有多个声音时，都填这句话开头的序号（它们本来就同时发生）。

### 其它
- pos 必须是**该段正文里真实出现过的序号**，不要自己编号、不要填别段的序号。
- 拿不准就填该段声音所在那一句的开头序号，不要留空。

## 输出格式
严格只输出如下 JSON，不要解释、不要 markdown 代码块：
{"picks":[{"p":1,"name":"炒菜","pos":3},{"p":1,"name":"雷","pos":7},{"p":2,"name":"脚步","pos":1}]}"""
}
