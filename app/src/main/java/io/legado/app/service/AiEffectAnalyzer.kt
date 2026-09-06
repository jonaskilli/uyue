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
     * 旧版内置提示词的特征串，用于 App 启动时判断是否需要升级。
     * 命中说明这条提示词仍是内置旧版、用户没改过，可以安全覆盖。
     * 旧版都是单段格式（含【本段】或"输出 4 个关键字"），新版改为批量格式。
     */
    val outdatedMarkers: List<String> = listOf(
        "【本段】",
        "输出 4 个关键字",
        "某段确实没有可识别声音就输出空数组",
        "每 100 字给 4 个",
        "每 100 字给 8 个"
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
     *         AI 漏掉的段补空列表（表示「看过，不需要音效」，会落盘避免重复分析）。
     *         整批失败（AI 不可用/异常）时返回 null，不落盘，允许下次重试。
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
        // 候选池随段数放宽，但设上限避免 prompt 过长
        val poolLimit = (AppConfig.aiEffectMaxCandidates * paras.size).coerceAtMost(200)
        val candidates = AiEffectIndex.searchByKeywords(
            allKeywords,
            AppConfig.aiEffectCandidatesPerKeyword,
            poolLimit
        )
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
            append("\n\n【正文段落（编号即顺序，相邻编号互为上下文）】\n")
            if (prevOfFirst.isNotBlank()) {
                append("（上文提示：这些段落之前的内容是）").append(clip(prevOfFirst)).append("\n")
            }
            // 必须带编号：解析端用 p 对应段落，没有编号 AI 无从判断归属
            paras.forEachIndexed { i, (_, text) ->
                append("第${i + 1}段（${text.length}字）：").append(clip(text)).append("\n")
            }
            // 上限是配置项，动态告知，改配置立即生效
            append("\n本次每段最多输出 ${keywordLimit()} 个关键字。\n")
            append("请按段落顺序，为每一段各输出一组关键字，输出 JSON：")
        }
        val raw = try {
            BgmAIService.callOpenAIApi(provider, prompt)
        } catch (e: CancellationException) {
            // 切章/停止朗读导致的取消必须向上传播，否则会被当成「无关键字」落盘，
            // 把未分析的段落永久标记为无音效
            throw e
        } catch (e: Exception) {
            AppLog.put("$TAG: 批量关键字请求失败 ${e.localizedMessage}")
            return null
        }
        return parseKeywordsBatch(raw, paras)
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
            append("\n\n【候选音效列表】\n")
            candidates.forEachIndexed { i, e ->
                append("${i + 1}. ${e.name}（${e.category}/${e.sub}）\n")
            }
            append("\n【正文段落（编号即顺序）】\n")
            if (prevOfFirst.isNotBlank()) {
                append("（上文提示：这些段落之前的内容是）").append(clip(prevOfFirst)).append("\n")
            }
            paras.forEachIndexed { i, (_, text) ->
                append("第${i + 1}段（${text.length}字）：").append(clip(text)).append("\n")
            }
            append("\n请为每一段各选 0~${pickLimit()} 个音效，用第几段标记归属，输出 JSON：")
        }
        val raw = try {
            BgmAIService.callOpenAIApi(provider, prompt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.put("$TAG: 批量精选请求失败 ${e.localizedMessage}")
            return null
        }
        return parsePicksBatch(raw, paras, candidates)
    }

    // ---------------- 解析 ----------------

    /**
     * 解析批量关键字。
     * 期望 `{"paras":[{"p":1,"k":["雷","雨"]}, ...]}`，p 是 1 起的段序号。
     * AI 常把 p 写成字符串或漏掉某些段，这里都做容错；无法定位的段落按无关键字处理。
     */
    private fun parseKeywordsBatch(
        raw: String,
        paras: List<Pair<Int, String>>
    ): Map<Int, List<String>> {
        val obj = extractJsonObject(raw) ?: return emptyMap()
        val arr = obj.optJSONArray("paras") ?: return emptyMap()
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
            // 按该段字数截断：每 100 字 4 个，上限 aiEffectKeywordCount
            out[paraIndex] = list.take(keywordQuota(paras[order - 1].second.length))
        }
        return out
    }

    /**
     * 解析批量精选结果。
     * 期望 `{"picks":[{"p":1,"name":"雷","at":"start"}, ...]}`，p 是 1 起的段序号。
     * 同一段可能被重复提及，按 [AppConfig.aiEffectMaxPick] 截断并去重。
     */
    private fun parsePicksBatch(
        raw: String,
        paras: List<Pair<Int, String>>,
        candidates: List<AiEffectIndex.Entry>
    ): Map<Int, List<AiEffectPick>> {
        val obj = extractJsonObject(raw) ?: return emptyMap()
        val arr = obj.optJSONArray("picks") ?: return emptyMap()
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
            val entry = byName[name] ?: continue
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

    private fun clip(text: String): String =
        if (text.length <= MAX_CHARS_PER_PARA) text else text.take(MAX_CHARS_PER_PARA)

    /** 内置默认关键字提示词，App 启动时写入 bgmAIPrompt 表供用户修改 */
    const val KEYWORD_PROMPT = """你是广播剧音效设计师。下面有若干段小说正文，请**为每一段分别**提炼用于检索音效库的关键字。

## 要求
- 每一段都要输出一组关键字，段落数量必须与输入一致。
- **不要轻易输出空数组**。只有纯粹的心理描写、抽象叙述、抒情议论才给空数组；只要段落里有任何动作、物体、环境、人声或可联想的声响，就给出关键字。理由是：给了关键字还有机会在后续筛选中剔除，给了空数组则完全失去检索机会。声音不明显时，给出最接近的 2 个即可。
- 每段的关键字数量已在该段标题的"第N段（X字）"里给出字数，**按字数增减：每 100 字给 12 个**（100 字给 12 个、150 字给 18 个、50 字给 6 个），极短段落至少给 6 个。**总数量不得超过我给出的上限**。
- **每个关键字严格只能 1~2 个字**，必须是具体名词或具体动作。
- 1 个字：雷、雨、风、剑、门、马、火、水、钟、铃、枪、猫、狗、鸟、哭、笑、喘、咳。
- 2 个字：脚步、开门、炒菜、倒水、玻璃、心跳、呼吸、雷声、马蹄、鸟鸣、水声、风声、关门、上楼、点火、写字、翻书、碗筷、车声、人群。
- **不要输出 3 个字及以上的词**。"炒菜声"要写成"炒菜"，"脚步声"要写成"脚步"，"雷声隆隆"要写成"雷"。
- 禁止输出泛词："声音""音效""背景""气氛""环境"这类词没有检索价值。
- 只关注该段自己真实发生的声音，不要凭相邻段落臆测。
- 同一段内关键字尽量覆盖不同的声音事件，不要重复表达同一个声音，也不要凑数。

## 输出格式
严格只输出如下 JSON，不要解释、不要 markdown 代码块。
p 是段落序号，从 1 开始；k 是该段的关键字数组：
{"paras":[{"p":1,"k":["炒菜","油锅","脚步","雷","开门","雨"]},{"p":2,"k":[]}]}"""

    /** 内置默认精选提示词，App 启动时写入 bgmAIPrompt 表供用户修改 */
    const val PICK_PROMPT = """你是广播剧音效设计师。请为下面**每一段**正文分别从候选音效列表中挑选合适的音效。

## 要求
- 每一段最多选 3 个，最少选 0 个；某段没有合适的声音就不要为它输出任何条目。
- **p 是段落序号，从 1 开始**，必须与输入段落标题"第N段"的编号对应。
- 只能从【候选音效列表】中选择，名字必须原样照抄，不要改写、不要加扩展名。
- 优先选择该段明确出现的声音，不要为了凑数选择勉强相关的。
- 同一段内不要选择含义重复的音效（例如已有"脚步"就不要再选"走路"）。
- 音效要在该段内触发，不要为某段选择实际发生在别的段落里的声音。
- at 表示触发时机：start=段落开头，mid=段落中间，end=段落结尾。默认 start。

## 输出格式
严格只输出如下 JSON，不要解释、不要 markdown 代码块：
{"picks":[{"p":1,"name":"炒菜","at":"start"},{"p":1,"name":"雷","at":"mid"},{"p":2,"name":"脚步","at":"start"}]}"""
}
