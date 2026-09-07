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
        "每一段最多选 3 个"
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
            if (prevOfFirst.isNotBlank()) {
                append("（上文提示：这些段落之前的内容是）").append(clip(prevOfFirst)).append("\n")
            }
            paras.forEachIndexed { i, (_, text) ->
                append("第${i + 1}段（${clip(text).length}字）：").append(clip(text)).append("\n")
            }
            append("\n请为每一段各选 0~${pickLimit()} 个音效，用 p 标记归属，输出 JSON：")
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
        return parsePicksBatch(raw, paras, candidates).also {
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
     * 期望 `{"picks":[{"p":1,"name":"雷","at":"start"}, ...]}`，p 是 1 起的段序号。
     * 同一段可能被重复提及，按 [AppConfig.aiEffectMaxPick] 截断并去重。
     *
     * @return null 表示**返回内容不是合法 JSON**（多半是输出被截断），按失败处理不落盘。
     */
    private fun parsePicksBatch(
        raw: String,
        paras: List<Pair<Int, String>>,
        candidates: List<AiEffectIndex.Entry>
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
            list.add(AiEffectPick(entry.name, entry.path, normalizeAt(item.optString("at"))))
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
- 同一段内不要挑含义重复的（已有"脚步"就不要再挑"走路"）。
- 【候选音效列表】是所有段落共用的，混有其它段落的声音。**只能挑本段真实发生的**，不要把别段的声音安到本段头上。
- 相邻段落一并提供是为了判断边界：上一段已经响过、本段只是余波的，不要重复挑。
- **只能从列表里挑，不能自己编名字**。就算你觉得有更贴切的音效，只要不在列表里就不能写。

## 怎么写（照抄，不要改写）
- name 只写纯音效名：照抄"N. "后面的那一个词，**不要带序号、不要加 .mp3**。
- 不要在列表的名字上做任何修改：列表里是"炒菜"就写"炒菜"，不要写成"炒菜声"。
- at 表示触发时机：start=段落开头，mid=段落中间，end=段落结尾。默认 start。

## 输出格式
严格只输出如下 JSON，不要解释、不要 markdown 代码块：
{"picks":[{"p":1,"name":"炒菜","at":"start"},{"p":1,"name":"雷","at":"mid"},{"p":2,"name":"脚步","at":"start"}]}"""
}
