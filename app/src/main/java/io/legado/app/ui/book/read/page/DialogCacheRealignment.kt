package io.legado.app.ui.book.read.page

import org.json.JSONObject

/**
 * 标注缓存重排（2026-11-21）：替换规则改正文后，把角色缓存搬到新序号上。
 *
 * 背景：全书分析/朗读写缓存按「本章第几个 “” 对话」编号；阅读替换净化改正文
 * （用户边读边加规则）后，引号个数会变（如「非对话双引号改单引号」），旧序号
 * 全体错位，页面标注挂错人。台词文字本身没变或只变了符号，无需重新花 AI 分析，
 * 本地把旧条目对齐到新位置即可。
 *
 * 设计原则（11-21 用户令「最坏不能比现状更糟」）：
 * · 检测不一致才动手；一致时 [realignIfNeeded] 返回 UNCHANGED，零写盘零改动；
 * · 对不上的条目进 [Realigned.orphans] 单独返回，不删不硬塞；
 * · 调用方写盘前先备份 .bak；本模块自身不做 IO（纯函数，见 buildRealignedResults）。
 *
 * 归一化（[normalize]）：去空白与常见标点/符号，只留汉字、字母、数字。
 * 两端（缓存 dialogText 与正文台词）同一函数处理，符号被替换规则改动后仍可对上。
 */
object DialogCacheRealignment {

    /** 重排结果 */
    data class Realigned(
        /** 新 results JSON：新序号 -> {name,gender,age,dialogText,...}，供调用方写盘 */
        val results: JSONObject,
        /** 重排后仍在缓存里的条目数 */
        val mappedCount: Int,
        /** 对不上的旧条目（seq=旧序号，text=归一化台词），只报告不写入 */
        val orphans: List<Int>,
        /** 对齐方式：exact=逐条精确命中 / order=顺序对齐兜底 */
        val mode: String
    )

    enum class Status { UNCHANGED, REALIGNED, FAILED }

    /** 旧 results 里「完整有效」条目数（有名字也有台词，参与对齐的口径） */
    private fun countValidEntries(oldResults: JSONObject): Int {
        var n = 0
        val keys = oldResults.keys()
        while (keys.hasNext()) {
            val obj = oldResults.optJSONObject(keys.next()) ?: continue
            if (obj.optString("name", "").trim().isNotEmpty()
                && obj.optString("dialogText", obj.optString("dialogContent", "")).isNotEmpty()) n++
        }
        return n
    }

    /**
     * 归一化：只留汉字/字母/数字。汉字区含扩展A/兼容区，够覆盖简繁书源文本。
     */
    fun normalize(text: String): String {
        if (text.isNullOrEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in 'a'..'z', in 'A'..'Z', in '0'..'9' -> sb.append(ch)
                in '\u4E00'..'\u9FFF' -> sb.append(ch)
                in '\u3400'..'\u4DBF' -> sb.append(ch)
                in '\uF900'..'\uFAFF' -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 从正文中按排版同源规则枚举引号台词：
     * 序号口径与 attachAnnotations / buildTtsMetaJson 完全一致——每个 “ 字符 globalSeq++，
     * 台词取该 “ 到下一个 “ 之间的内容（右引号 ” 只是截断标记，不占序号）。
     * 返回 (新序号1起, 台词原文) 列表。
     */
    fun extractQuotes(content: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        if (content.isNullOrEmpty()) return out
        var seq = 0
        var inQuote = false
        val sb = StringBuilder()
        for (ch in content) {
            if (ch == '\u201C') { // “
                if (inQuote && sb.isNotEmpty()) out.add(++seq to sb.toString())
                inQuote = true
                sb.setLength(0)
            } else if (inQuote) {
                if (ch == '\u201D') { // ” 只截断，不占号
                    if (sb.isNotEmpty()) out.add(++seq to sb.toString())
                    inQuote = false
                    sb.setLength(0)
                } else {
                    sb.append(ch)
                }
            }
        }
        if (inQuote && sb.isNotEmpty()) out.add(++seq to sb.toString())
        return out
    }

    /**
     * 核心对齐：把旧缓存 results 对齐到当前正文的新序号上。
     * 纯函数：不读不写盘；调用方拿 [Realigned.results] 自行备份+写盘。
     *
     * @param currentContent 当前章正文（已过替换净化的口径，与挂标注同一文本源）
     * @param oldResults 旧缓存 results JSONObject（seq -> {name,dialogText,...}）
     */
    fun buildRealignedResults(currentContent: String, oldResults: JSONObject): Realigned? {
        val quotes = extractQuotes(currentContent)
        if (quotes.isEmpty()) return null

        // 旧条目按序号升序；无 name 或无台词的条目不参与（空壳防御，同 matchChapterCacheExact 口径）
        val oldSeqs = ArrayList<Int>()
        val oldTexts = HashMap<Int, String>()
        val oldRoles = HashMap<Int, JSONObject>()
        val keys = oldResults.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val seq = key.toIntOrNull() ?: continue
            val obj = oldResults.optJSONObject(key) ?: continue
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) continue
            val dialogText = obj.optString("dialogText", obj.optString("dialogContent", ""))
            if (dialogText.isEmpty()) continue
            oldSeqs.add(seq)
            oldTexts[seq] = normalize(dialogText)
            oldRoles[seq] = obj
        }
        if (oldSeqs.isEmpty()) return null
        oldSeqs.sort()

        val newResults = JSONObject()
        val orphans = ArrayList<Int>()
        val newNorm = quotes.map { normalize(it.second) }

        // 第一遍：逐条按归一化台词精确匹配（一条旧条目只占一个新位置，防重复台词抢位）
        val claimed = BooleanArray(quotes.size)
        val oldToNew = HashMap<Int, Int>()
        for (oldSeq in oldSeqs) {
            val t = oldTexts[oldSeq] ?: continue
            if (t.isEmpty()) continue
            var hit = -1
            var bestDist = Int.MAX_VALUE
            for (i in quotes.indices) {
                if (claimed[i]) continue
                if (newNorm[i] == t) {
                    val dist = kotlin.math.abs(quotes[i].first - oldSeq)
                    if (dist < bestDist) { bestDist = dist; hit = i }
                }
            }
            if (hit >= 0) { claimed[hit] = true; oldToNew[oldSeq] = quotes[hit].first }
        }

        // 第二遍：仍未安放的旧条目按「相对顺序」对齐兜底——
        // 台词被替换规则改了字（归一化也对不上）时，旧条目之间的先后顺序通常不变，
        // 取「已精确安放条目」夹出的区间做线性内插；夹不住的（两端缺锚/区间没空位）成孤儿。
        // 只有单侧锚点时区间开放、不猜位置——宁旧勿错。
        val anchored = oldSeqs.filter { oldToNew.containsKey(it) }
        for (oldSeq in oldSeqs) {
            if (oldToNew.containsKey(oldSeq)) continue
            val prev = anchored.lastOrNull { it < oldSeq }
            val next = anchored.firstOrNull { it > oldSeq }
            if (prev == null || next == null) { orphans.add(oldSeq); continue }
            val pn = oldToNew[prev]!!   // 前锚的新序号
            val nx = oldToNew[next]!!   // 后锚的新序号
            val lo = pn + 1
            val hi = nx - 1
            if (hi < lo) { orphans.add(oldSeq); continue }  // 两锚点相邻，中间没空位
            // 按旧序号比例内插候选位置，限制在两锚点之间
            val off = Math.round((oldSeq - prev).toDouble() / (next - prev) * (nx - pn)).toInt()
            val cand = (pn + off).coerceIn(lo, hi)
            // 候选位被占就先向后找空位，再向前找
            var placedIdx = -1
            var i = cand
            while (i <= hi) { if (!claimed[i - 1]) { placedIdx = i; break }; i++ }
            if (placedIdx < 0) {
                i = cand - 1
                while (i >= lo) { if (!claimed[i - 1]) { placedIdx = i; break }; i-- }
            }
            if (placedIdx > 0) {
                claimed[placedIdx - 1] = true
                oldToNew[oldSeq] = placedIdx
            } else {
                orphans.add(oldSeq)
            }
        }

        // 组装新 results：按新序号升序写入；同时保留旧 results 里与引号无关的
        // 整体字段由调用方负责（本函数只产出 results）。
        // 11-21 15:5x 修复（孤儿回填）：对不上新正文的旧条目**原样保留**，绝不剔除——
        // 「宁旧勿错」= 旧数据最多没用，不能没了。当初承诺「孤儿不删」，首版实现却
        // 把孤儿从 results 里丢掉了（0077 实测：9 条→1 条，用户看到「缓存全消失」，
        // 且唯一真相只在 .bak 里，重开规则后无法再恢复）。回填口径：孤儿挂回原序号；
        // 若原序号已被精确命中的条目占用，则**宁要命中的新映射、孤儿顺延挂到
        // 未占用的更大序号**（保证每个旧条目都有位置，且不覆盖任何已命中结果）。
        val occupiedByMapped = HashSet<Int>(oldToNew.values)
        for (oldSeq in orphans) {
            val obj = oldRoles[oldSeq] ?: continue
            val slot = if (!occupiedByMapped.contains(oldSeq) && oldSeq <= quotes.size)
                oldSeq else {
                var s = maxOf(oldSeq, 1)
                while (occupiedByMapped.contains(s) || newResults.has(s.toString())) s++
                s
            }
            occupiedByMapped.add(slot)
            val copy = JSONObject(obj.toString())
            // 台词保持缓存原文（正文对不上才成了孤儿，正文那句不是它的）
            newResults.put(slot.toString(), copy)
        }
        for ((oldSeq, newSeq) in oldToNew) {
            val obj = oldRoles[oldSeq] ?: continue
            val copy = JSONObject(obj.toString())
            copy.put("dialogText", quotes.first { it.first == newSeq }.second)
            newResults.put(newSeq.toString(), copy)
        }

        // 无变化守卫：映射后的键集、名字、台词与旧 results 完全一致 → 返回 null，
        // 调用方零写盘（「没坏就不修」）。正文没被规则改过时每次挂标注都走这里。
        // 孤儿回填场景不算「无变化」（回填要么改了键集/序号，要么留待调用方
        // changed 复查兜底——那里全同会跳过写盘，自循环由它堵死）。
        if (orphans.isEmpty() && newResults.length() > 0 && newResults.length() == countValidEntries(oldResults)) {
            var identical = true
            val it = newResults.keys()
            while (it.hasNext()) {
                val k = it.next()
                val n = newResults.optJSONObject(k)
                val o = oldResults.optJSONObject(k)
                if (n == null || o == null
                    || n.optString("name") != o.optString("name")
                    || n.optString("dialogText") != o.optString("dialogText")) {
                    identical = false
                    break
                }
            }
            if (identical) return null
        }

        val mode = if (orphans.isEmpty() && oldSeqs.size == quotes.size) "exact"
                   else if (orphans.isEmpty()) "exact+order"
                   else "order"
        // 11-21 15:5x：孤儿已回填进 results，Realigned.orphans 仍报告（日志口径用），
        // 但语义从「丢弃」变为「保留在缓存、位置未确认」。
        return Realigned(newResults, oldToNew.size, orphans, mode)
    }
}
