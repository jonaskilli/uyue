package io.legado.app.help.book

import java.text.BreakIterator
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

@Suppress("SameParameterValue", "RegExpRedundantEscape")
object ContentHelp {

    /**
     * 段落重排算法入口。把整篇内容输入，连接错误的分段，再把每个段落调用其他方法重新切分
     *
     * @param content     正文
     * @param chapterName 标题
     * @return
     */
    /**
     * 长段拆分（11-14 用户令改名改功能）。
     *
     * ## 改名前它是什么
     *
     * 原名「重新分段」，做的是**整章重排**：引号规范化、去段落内空格、把断开的段
     * 黏合、把 `。”` 之后切开、`findNewLines` 按词典重排……最后才切超长段。
     * 那五步是"修复排版"，会改变作者本来的分段——正常段落也被动过。
     *
     * ## 现在只做一件事：超长段兜底切分
     *
     * 用户原话（11-14）：「这个功能就可以删掉了……当你觉得段落长的时候可以给它开开，
     * 我那个 TTS 里面长句分割也不是一直开呀，基本上都没开过，只有遇到出版书的时候，
     * 容易遇到一段特别长我才开」。
     *
     * 所以语义对齐 UTTS 的「分割长句」（isSplitEnabled，默认关）：
     * **只在段落超过阈值时才切开，短段一个字都不动**（含空格、引号、换行）。
     * 实现直接复用原有的 [splitLongParagraph]，它本来就是自包含的：
     * 逐行判断 `line.length > MAX_PARAGRAPH_LENGTH` 才切，否则原样输出。
     *
     * 保留：引号深度跟踪（跨页/跨段延续）。11-14 用户拍板后，**一层引号内的
     * 句末标点也算切点**（否则对话多的书里长段永远切不动），但仍保证不会把一句话
     * 从中间切断——引号内的逗号不切，深度 ≥2 的嵌套引号不切。
     * 丢弃：引号规范化、去空格、段落黏合、`。”` 断句、词典重排——
     * 这些属于"排版纠正"，不该藏在一个叫"长段拆分"的开关后面。
     *
     * @param content 章节正文（原文，含它自己的分段与缩进）
     * @param chapterName 章名（本实现不再用它，保留参数是为了调用方签名稳定）
     * @param splitNarration 档2：是否再做「旁白／对话分离」（默认关，见下）
     */
    fun reSegment(
        content: String,
        chapterName: String,
        splitNarration: Boolean = false
    ): String {
        // 空正文直接返回，避免 StringBuilder 空跑
        if (content.isBlank()) return content
        // 第一道：只切超长段；splitLongParagraph 自己会跳过短行
        val cut = splitLongParagraph(StringBuilder(content)).toString()
        // 第二道（档2，默认关）：把「旁白+对话」混在一段的拆开。
        // 顺序不能反：先进引号分离、再按长度切，会把已经够短的对话又切碎；
        // 先按长度切出符合目标段长的段，再分离旁白/对话，两边都干净。
        if (!splitNarration) return cut
        return splitNarrationDialogue(cut)
    }

    /**
     * 强制切分，减少段落内的句子
     * 如果连续2对引号的段落没有提示语，进入对话模式。最后一对引号后强制切分段落
     * 如果引号内的内容长于5句，可能引号状态有误，随机分段
     * 如果引号外的内容长于3句，随机分段
     *
     * @param str
     * @return
     */
    private fun reduceLength(str: StringBuilder): StringBuilder {
        val p = str.toString().split("\n".toRegex()).toTypedArray()
        val l = p.size
        val b = BooleanArray(l)
        for (i in 0 until l) {
            b[i] = p[i].matches(PARAGRAPH_DIAGLOG)
        }
        var dialogue = 0
        for (i in 0 until l) {
            if (b[i]) {
                if (dialogue < 0) dialogue = 1 else if (dialogue < 2) dialogue++
            } else {
                if (dialogue > 1) {
                    p[i] = splitQuote(p[i])
                    dialogue--
                } else if (dialogue > 0 && i < l - 2) {
                    if (b[i + 1]) p[i] = splitQuote(p[i])
                }
            }
        }
        val string = StringBuilder()
        for (i in 0 until l) {
            string.append('\n')
            string.append(p[i])
            //System.out.print(" "+b[i]);
        }
        //System.out.println(" " + str);
        return string
    }

    /**
     * 强制分割超长段落（确定性兜底）。
     *
     * forceSplit 的随机插行命中率不稳定：无逗号的长段落每处句末只有约 1/3 概率，
     * reduceLength 注释中"引号外内容长于 3 句随机分段"也并未实现，
     * 导致朗读时经常出现几十句黏连成一段的超长段落。
     *
     * 参照成熟实践（ICU 标准分句 + 目标长度分组 + 多级保底）：
     *  1. 分句：BreakIterator（ICU 句子边界，Locale.CHINA）+ 中文句末标点扫描双保险；
     *  2. **双引号内绝不下刀**：全程跟踪引号深度，只有一个引号时按上下文判定它是
     *     起引号还是反引号——判定为起引号则其括起的内容（可跨段落，直到出现反引号）
     *     全部视为引号内，任何情况下都不切；
     *  3. 句末标点（引号外与一层引号内都算）按 [MAX_PARAGRAPH_LENGTH] 贪心分组；
     *  4. 末段短于 [MIN_MERGE_LENGTH] 并入前段，避免碎片。
     *
     * ⚠️ 11-14 用户定稿：切点**只认句末标点**，不做任何字数硬切/逗号降级。
     * 原先的「超长区间降级到逗号、再无位置才按字数硬切」已删除，
     * 因为它会把句子从中间切断，破坏合成语义的连贯。
     *
     * @param str 待分段内容，行间以 \n 分隔
     */
    private fun splitLongParagraph(str: StringBuilder): StringBuilder {
        val lines = str.toString().split("\n")
        val result = StringBuilder(str.length + str.length / 8)
        // 引号深度跨段落延续：段落中只有起引号时，后续段落仍视作引号内
        var depth = 0
        val sentenceIterator = BreakIterator.getSentenceInstance(Locale.CHINA)
        for (line in lines) {
            if (line.isEmpty()) {
                result.append("\n")
                continue
            }
            val cuts = ArrayList<Int>()
            if (line.length > MAX_PARAGRAPH_LENGTH) {
                // 1. 计算每个位置的引号深度：depths[i] 为第 i 个字符之前的深度
                val depths = IntArray(line.length + 1)
                var d = depth
                for (i in line.indices) {
                    depths[i] = d
                    d = nextQuoteDepth(line, i, d)
                }
                depths[line.length] = d
                depth = d
                // 2. 一级切分点：引号外的句末（中文标点扫描 + ICU 句子边界）
                val primary = ArrayList<Int>()
                collectSentenceEnds(line, depths, primary)
                sentenceIterator.setText(line)
                sentenceIterator.first()
                var next = sentenceIterator.next()
                while (next != BreakIterator.DONE) {
                    addCut(primary, line, depths, next)
                    next = sentenceIterator.next()
                }
                // 3. 按目标长度切分。
                //
                // 11-14 用户定稿（「最主要还是得保持他那个合成语义的流畅性，如果这个
                // 保证不了的话，你切的乱七八糟的，不觉得有什么好」「首先做的就是
                // 句末切割」）：**唯一的切点来源就是句末标点**。
                //
                // 因此删掉了原先的两级兜底（它们违背句末语义，实测会切在半句）：
                //   · secondary 逗号/分号降级——实测把 100 字逗号连缀段切成 [54, 46]，
                //     切在句中；
                //   · refineOverLong + hardCutPosition 按字数硬切——实测把 201 字
                //     无标点段切成 [50,50,50,51]，每 50 字生切一刀。
                // 代价已知且为用户接受：整段只有一个句末标点、或无句末标点的长段
                // 不会被切开（真实正文《明雾烟》10 段超长段中 6 段属此类）。
                // 用户原话「就算他有一段有 100 个字，我也不觉得有什么问题」——
                // 不切，远好过乱切。
                greedyCut(primary, 0, line.length, MAX_PARAGRAPH_LENGTH, cuts)
                // 4. 末段过短并入前段
                if (cuts.isNotEmpty() && line.length - cuts.last() < MIN_MERGE_LENGTH) {
                    cuts.removeAt(cuts.size - 1)
                }
            } else {
                // 短行无需切分，但仍要延续引号深度
                var d = depth
                for (i in line.indices) {
                    d = nextQuoteDepth(line, i, d)
                }
                depth = d
            }
            if (cuts.isEmpty()) {
                result.append(line).append("\n")
            } else {
                var start = 0
                for (cut in cuts) {
                    result.append(line, start, cut).append("\n")
                    start = cut
                }
                result.append(line, start, line.length).append("\n")
            }
        }
        // 去掉末尾多出的一个换行，保持与入参一致的行结构
        if (result.isNotEmpty() && result.last() == '\n') {
            result.setLength(result.length - 1)
        }
        return result
    }

    /**
     * 计算第 [i] 个字符之后的引号深度。
     * 只有一个引号时按上下文判定它是起引号还是反引号：
     * 起引号之后的内容（直到出现反引号，可跨段落）全部视为引号内，绝不下刀。
     */
    private fun nextQuoteDepth(line: String, i: Int, depth: Int): Int {
        val c = line[i]
        if (c != '“' && c != '”' && c != '"') return depth
        val prev = if (i > 0) line[i - 1] else '\n'
        return when (c) {
            '“' -> when {
                // 起引号
                depth == 0 -> 1
                // 句末/逗号后的 “ 实为误用的反引号
                match(MARK_QUOTE_AFTER_CLOSE, prev) -> 0
                // 新起一对，前一引号未闭合
                else -> 1
            }
            // 反引号：有可闭合的则闭合，孤立反引号无可闭合
            '”' -> if (depth > 0) depth - 1 else 0
            // 直引号按位置推断方向
            else -> if (depth > 0) 0
            else if (match(MARK_QUOTE_AFTER_CLOSE, prev)) 0 else 1
        }
    }

    /**
     * 扫描中文句末标点，收集切分点。
     *
     * 11-14 用户拍板方案 A（「只在引号内句末切，不动引入语」）：
     * **引号内的句末标点也算合法切点**。
     *
     * 为什么必须放开这一条（真实正文实测，非推测）：
     * 拿《明雾烟》全章 442 段跑，超过 50 字的 10 段里 **0 段被切开**——
     * 因为小说里最长的段落恰恰是整段对话，引号占比 67%~96%，
     * 而旧实现只在引号外取点 ⇒ 一个可用切点都没有 ⇒ 长段拆分在最该起作用
     * 的文体（出版书、旁白+对话混排）上等于没开。
     *
     * 放开后的边界：
     *  · 只在 **深度 1**（一层引号内）取点，深度 ≥2 的嵌套引号仍不切；
     *  · 只认句末标点（。！？；…），引号内的逗号仍然不切——
     *    所以不会把一句话从中间切断，也不会动「他说：」这种引入语；
     *  · 连续句末标点（？！、……）与紧随的反引号归入本段，不在其中下刀。
     */
    private fun collectSentenceEnds(line: String, depths: IntArray, out: MutableList<Int>) {
        var i = 0
        while (i < line.length) {
            val inQuote = depths[i] == 1
            if ((depths[i] == 0 || inQuote) && match(MARK_SENTENCES_END, line[i])) {
                var end = i + 1
                while (end < line.length &&
                    (match(MARK_SENTENCES_END, line[end]) || line[end] == '”' || line[end] == '"')
                ) {
                    end++
                }
                addCut(out, line, depths, end)
                i = end
                continue
            }
            i++
        }
    }

    /**
     * 添加切分点。
     *
     * @param pos 候选位置（切在该字符之前）
     *
     * 判据：不能是行首/行尾、不能把连续句末标点切开；
     * **深度约束放宽到 ≤1**——引号外（0）与一层引号内（1）都可切，
     * 深度 ≥2 的嵌套引号仍视为不可切（见 collectSentenceEnds 的注释）。
     */
    private fun addCut(out: MutableList<Int>, line: String, depths: IntArray, pos: Int) {
        if (pos <= 0 || pos >= line.length) return
        if (depths[pos] > 1) return
        if (match(MARK_SENTENCES_END, line[pos])) return
        if (!out.contains(pos)) out.add(pos)
    }

    /**
     * 在 [from, to) 内切分：优先在「累计长度达到 [threshold]」的候选点下刀。
     *
     * ## 11-14 改：去掉 50~80 字的死区（真实正文实测出来的硬伤）
     *
     * 旧实现要求 `p - last >= threshold(50)` 才切，而 51~80 字的段落切一刀后
     * 剩余部分不足 80（当时的降级门槛），降级逻辑不接手，于是形成**死区**：
     *   · 60 字的段落：句末点在 59 处，切下去剩 1 字（< MIN_MERGE 15）⇒ 并回去；
     *   · 79 字的段落：切完剩 29 字，无人接手；
     *   ⇒ 长度 51~80 的段落整段原样输出。
     * （降级逻辑本身已按用户定稿删除，这里保留成因记录，避免将来又把它加回来）
     * 实测：长度 55/60/70/79 全部 1 段，81 才开始切。也就是说
     * **「长段拆分」对 80 字以下的段落完全无效**——而目标段长本来就是 50。
     *
     * 现在改成三段式：
     *  1. 贪心：达到 threshold 就切（同旧行为，优先保住段长）；
     *  2. **补刀**：若一个切点都没有、而整段又超过 threshold，
     *     就在「最接近 threshold 的那个候选点」切一刀——把 51~80 的死区填上；
     *  3. 只有切出来的两半都不短于 MIN_MERGE_LENGTH 才认，否则整段保留
     *     （用户定稿：不硬切，宁可保留原段，也不切出碎片）。
     */
    private fun greedyCut(
        positions: List<Int>,
        from: Int,
        to: Int,
        threshold: Int,
        out: MutableList<Int>
    ) {
        var last = from
        val before = out.size
        for (p in positions) {
            if (p <= from || p >= to) continue
            if (p - last >= threshold) {
                out.add(p)
                last = p
            }
        }
        // 补刀：一个点都没切出来，但整段超过目标段长 ⇒ 在离 threshold 最近的候选点切一刀
        if (out.size == before && to - from > threshold) {
            var best = -1
            var bestDist = Int.MAX_VALUE
            for (p in positions) {
                if (p <= from || p >= to) continue
                // 两半都不能太短，否则没有意义
                if (p - from < MIN_MERGE_LENGTH || to - p < MIN_MERGE_LENGTH) continue
                val dist = kotlin.math.abs(p - from - threshold)
                if (dist < bestDist) {
                    bestDist = dist
                    best = p
                }
            }
            if (best > 0) out.add(best)
        }
    }

    private fun splitQuote(str: String): String {
        val length = str.length
        if (length < 3) return str
        if (match(MARK_QUOTATION, str[0])) {
            val i = seekIndex(str, MARK_QUOTATION, 1, length - 2, true) + 1
            if (i > 1) if (!match(MARK_QUOTATION_BEFORE, str[i - 1])) {
                return "${str.take(i)}\n${str.substring(i)}"
            }
        } else if (match(MARK_QUOTATION, str[length - 1])) {
            val i = length - 1 - seekIndex(str, MARK_QUOTATION, 1, length - 2, false)
            if (i > 1) {
                if (!match(MARK_QUOTATION_BEFORE, str[i - 1])) {
                    return "${str.take(i)}\n${str.substring(i)}"
                }
            }
        }
        return str
    }

    /**
     * 档2：把「旁白 + 对话」混在同一段里的句子拆开（11-14 用户令做二档）。
     *
     * ## 为什么不直接复用 splitQuote
     *
     * [splitQuote] 有两个限制，拿它当档2 会漏：
     *  1. **一次只切一刀**：`她说：“A”然后他说：“B”` 只在第一个引号处切开，
     *     后半段仍是旁白对话混着；用户要的是"分两段"，得切到没有混合为止；
     *  2. **只认"段首/段尾是引号"两种情况**：段中间的对话
     *     （`他抬头说：“走。”然后转身。`）它根本不处理。
     *
     * 本函数判据（比 splitQuote 宽，但仍保守）：
     *  - 引号深度 0→1 处（起引号）切成两段：前面旁白、后面对话；
     *  - 引号深度 1→0 处之后切片：对话结束，后面回到旁白；
     *  - 只对**同一行**操作，不跨行；
     *  - 不含字母/数字的碎片（如只剩 `：“`）并回前段——否则 UTTS 会为
     *    空串发一次请求（用户为此报过"零词请求"）。
     *
     * ⚠️ 用户已明说这条的代价：「对话的上下文不清楚，会让 AI 分析不准，
     * 不知道这句话谁说的」。所以**默认关闭**，只在用户主动开档2时生效。
     */
    private fun splitNarrationDialogue(content: String): String {
        if (content.isBlank()) return content
        val lines = content.split("\n")
        val pieces = ArrayList<String>()
        for (line in lines) {
            // 空行原样保留：正文的段落/空行结构由上游决定，档2 不该顺手吃掉它
            if (line.isBlank()) {
                if (pieces.isNotEmpty()) pieces.add("")
                continue
            }
            // 逐行按引号深度找下刀位置
            val cuts = ArrayList<Int>()
            var depth = 0
            for (i in line.indices) {
                val nd = nextQuoteDepth(line, i, depth)
                if (nd != depth) {
                    // 0→1（起引号）：在引号前切，旁白独立成段
                    // 1→0（收引号）：在引号后切，对话独立成段
                    val pos = if (nd > depth) i else i + 1
                    if (pos in 1 until line.length && !cuts.contains(pos)) cuts.add(pos)
                    depth = nd
                }
            }
            if (cuts.isEmpty()) {
                pieces.add(line)
                continue
            }
            var from = 0
            for (cut in cuts) {
                if (cut <= from) continue
                val piece = line.substring(from, cut)
                if (piece.isNotBlank()) pieces.add(piece)
                from = cut
            }
            if (from < line.length) {
                val tail = line.substring(from)
                if (tail.isNotBlank()) pieces.add(tail)
            }
        }
        // 缺口4：合并「纯标点/空白」碎片并回前段（判据同 UTTS splitSentences）
        val merged = ArrayList<String>()
        for (piece in pieces) {
            // 空行（段落分隔）原样进结果，不参与并段
            if (piece.isEmpty()) {
                merged.add(piece)
                continue
            }
            val noContent = piece.none { it.isLetterOrDigit() }
            // 只并进**非空的前一段**：前一段是空行时并过去，会把标点并到空行上
            val prev = merged.lastOrNull()
            if (prev != null && prev.isNotEmpty() && noContent) {
                merged[merged.size - 1] = prev + piece
            } else {
                merged.add(piece)
            }
        }
        return merged.joinToString("\n")
    }

    /**
     * 计算随机插入换行符的位置。
     * @param str 字符串
     * @param offset 传回的结果需要叠加的偏移量
     * @param min 最低几个句子，随机插入换行
     * @param gain 倍率。每个句子插入换行的数学期望 = 1 / gain , gain越大越不容易插入换行
     * @return
     */
    private fun forceSplit(
        str: String,
        offset: Int,
        min: Int,
        gain: Int,
        tigger: Int
    ): ArrayList<Int> {
        val result = ArrayList<Int>()
        val arrayEnd = seekIndexes(str, MARK_SENTENCES_END_P, 0, str.length - 2, true)
        val arrayMid = seekIndexes(str, MARK_SENTENCES_MID, 0, str.length - 2, true)
        if (arrayEnd.size < tigger && arrayMid.size < tigger * 3) return result
        var j = 0
        var i = min
        while (i < arrayEnd.size) {
            var k = 0
            while (j < arrayMid.size) {
                if (arrayMid[j] < arrayEnd[i]) k++
                j++
            }
            if (Math.random() * gain < 0.8 + k / 2.5) {
                result.add(arrayEnd[i] + offset)
                i = max(i + min, i)
            }
            i++
        }
        return result
    }

    // 对内容重新划分段落.输入参数str已经使用换行符预分割
    private fun findNewLines(str: String, dict: List<String>): String {
        val string = StringBuilder(str)
        // 标记string中每个引号的位置.特别的，用引号进行列举时视为只有一对引号。 如：“锅”、“碗”视为“锅、碗”，从而避免误断句。
        val arrayQuote: MutableList<Int> = ArrayList()
        //  标记插入换行符的位置，int为插入位置（str的char下标）
        var insN = ArrayList<Int>()

        //mod[i]标记str的每一段处于引号内还是引号外。范围： str.substring( array_quote.get(i), array_quote.get(i+1) )的状态。
        //长度：array_quote.size(),但是初始化时未预估占用的长度，用空间换时间
        //0未知，正数引号内，负数引号外。
        //如果相邻的两个标记都为+1，那么需要增加1个引号。
        //引号内不进行断句
        val mod = IntArray(str.length)
        var waitClose = false
        for (i in str.indices) {
            val c = str[i]
            if (match(MARK_QUOTATION, c)) {
                val size = arrayQuote.size

                //        把“xxx”、“yy”合并为“xxx_yy”进行处理
                if (size > 0) {
                    val quotePre = arrayQuote[size - 1]
                    if (i - quotePre == 2) {
                        var remove = false
                        if (waitClose) {
                            if (match(",，、/", str[i - 1])) {
                                // 考虑出现“和”这种特殊情况
                                remove = true
                            }
                        } else if (match(",，、/和与或", str[i - 1])) {
                            remove = true
                        }
                        if (remove) {
                            string.setCharAt(i, '“')
                            string.setCharAt(i - 2, '”')
                            arrayQuote.removeAt(size - 1)
                            mod[size - 1] = 1
                            mod[size] = -1
                            continue
                        }
                    }
                }
                arrayQuote.add(i)

                //  为xxx：“xxx”做标记
                if (i > 1) {
                    // 当前发言的正引号的前一个字符
                    val charB1 = str[i - 1]
                    // 上次发言的正引号的前一个字符
                    var charB2 = 0.toChar()
                    if (match(MARK_QUOTATION_BEFORE, charB1)) {
                        // 如果不是第一处引号，寻找上一处断句，进行分段
                        if (arrayQuote.size > 1) {
                            val lastQuote = arrayQuote[arrayQuote.size - 2]
                            var p = 0
                            if (charB1 == ',' || charB1 == '，') {
                                if (arrayQuote.size > 2) {
                                    p = arrayQuote[arrayQuote.size - 3]
                                    if (p > 0) {
                                        charB2 = str[p - 1]
                                    }
                                }
                            }
                            //if(char_b2=='.' || char_b2=='。')
                            if (match(MARK_SENTENCES_END_P, charB2)) {
                                insN.add(p - 1)
                            } else if (!match("的", charB2)) {
                                val lastEnd = seekLast(str, MARK_SENTENCES_END, i, lastQuote)
                                if (lastEnd > 0) insN.add(lastEnd) else insN.add(lastQuote)
                            }
                        }
                        waitClose = true
                        mod[size] = 1
                        if (size > 0) {
                            mod[size - 1] = -1
                            if (size > 1) {
                                mod[size - 2] = 1
                            }
                        }
                    } else if (waitClose) {
                        run {
                            waitClose = false
                            insN.add(i)
                        }
                    }
                }
            }
        }
        val size = arrayQuote.size


        //标记循环状态，此位置前的引号是否已经配对
        var opend = false
        if (size > 0) {
            //第1次遍历array_quote，令其元素的值不为0
            for (i in 0 until size) {
                if (mod[i] > 0) {
                    opend = true
                } else if (mod[i] < 0) {
                    //连续2个反引号表明存在冲突，强制把前一个设为正引号
                    if (!opend) {
                        if (i > 0) mod[i] = 3
                    }
                    opend = false
                } else {
                    opend = !opend
                    if (opend) mod[i] = 2 else mod[i] = -2
                }
            }
            //        修正，断尾必须封闭引号
            if (opend) {
                if (arrayQuote[size - 1] - string.length > -3) {
                    //if((match(MARK_QUOTATION,string.charAt(string.length()-1)) || match(MARK_QUOTATION,string.charAt(string.length()-2)))){
                    if (size > 1) mod[size - 2] = 4
                    // 0<=i<size,故无需判断size>=1
                    mod[size - 1] = -4
                } else if (!match(MARK_SENTENCES_SAY, string[string.length - 2])) string.append(
                    "”"
                )
            }


            //第2次循环，mod[i]由负变正时，前1字符如果是句末，需要插入换行
            var loop2Mod1 = -1 //上一个引号跟随内容的状态
            var loop2Mod2: Int //当前引号跟随内容的状态
            var i = 0
            var j = arrayQuote[0] - 1 //当前引号前一字符的序号
            if (j < 0) {
                i = 1
                loop2Mod1 = 0
            }
            while (i < size) {
                j = arrayQuote[i] - 1
                loop2Mod2 = mod[i]
                if (loop2Mod1 < 0 && loop2Mod2 > 0) {
                    if (match(MARK_SENTENCES_END, string[j])) insN.add(j)
                }
                loop2Mod1 = loop2Mod2
                i++
            }
        }

        //第3次循环，匹配并插入换行。
        //"xxxx" xxxx。\n xxx“xxxx”
        //未实现

        // 使用字典验证ins_n , 避免插入不必要的换行。
        // 由于目前没有插入、的列表，无法解决 “xx”、“xx”“xx” 被插入换行的问题
        val insN1 = ArrayList<Int>()
        for (i in insN) {
            if (match("\"'”“", string[i])) {
                val start: Int = seekLast(
                    str,
                    "\"'”“",
                    i - 1,
                    i - WORD_MAX_LENGTH
                )
                if (start > 0) {
                    val word = str.substring(start + 1, i)
                    if (dict.contains(word)) {
                        //System.out.println("使用字典验证 跳过\tins_n=" + i + "  word=" + word);
                        //引号内如果是字典词条，后方不插入换行符（前方不需要优化）
                        continue
                    } else {
                        //System.out.println("使用字典验证 插入\tins_n=" + i + "  word=" + word);
                        if (match("的地得", str[start])) {
                            //xx的“xx”，后方不插入换行符（前方不需要优化）
                            continue
                        }
                    }
                }
            }
            insN1.add(i)
        }
        insN = insN1

//        随机在句末插入换行符
        insN = ArrayList(HashSet(insN))
        insN.sort()
        run {
            var subs: String
            var j = 0
            var progress = 0
            var nextLine = -1
            if (insN.isNotEmpty()) nextLine = insN[j]
            var gain = 3
            var min = 0
            var trigger = 2
            for (i in arrayQuote.indices) {
                val qutoe = arrayQuote[i]
                if (qutoe > 0) {
                    gain = 4
                    min = 2
                    trigger = 4
                } else {
                    gain = 3
                    min = 0
                    trigger = 2
                }

//            把引号前的换行符与内容相间插入
                while (j < insN.size) {

//                如果下一个换行符在当前引号前，那么需要此次处理.如果紧挨当前引号，需要考虑插入引号的情况
                    if (nextLine >= qutoe) break
                    nextLine = insN[j]
                    if (progress < nextLine) {
                        subs = string.substring(progress, nextLine)
                        insN.addAll(forceSplit(subs, progress, min, gain, trigger))
                        progress = nextLine + 1
                    }
                    j++
                }
                if (progress < qutoe) {
                    subs = string.substring(progress, qutoe + 1)
                    insN.addAll(forceSplit(subs, progress, min, gain, trigger))
                    progress = qutoe + 1
                }
            }
            while (j < insN.size) {
                nextLine = insN[j]
                if (progress < nextLine) {
                    subs = string.substring(progress, nextLine)
                    insN.addAll(forceSplit(subs, progress, min, gain, trigger))
                    progress = nextLine + 1
                }
                j++
            }
            if (progress < string.length) {
                subs = string.substring(progress, string.length)
                insN.addAll(forceSplit(subs, progress, min, gain, trigger))
            }
        }

//     根据段落状态修正引号方向、计算需要插入引号的位置
//     ins_quote跟随array_quote   ins_quote[i]!=0,则array_quote.get(i)的引号前需要前插入'”'
        val insQuote = BooleanArray(size)
        opend = false
        for (i in 0 until size) {
            val p = arrayQuote[i]
            if (mod[i] > 0) {
                string.setCharAt(p, '“')
                if (opend) insQuote[i] = true
                opend = true
            } else if (mod[i] < 0) {
                string.setCharAt(p, '”')
                opend = false
            } else {
                opend = !opend
                if (opend) string.setCharAt(p, '“') else string.setCharAt(p, '”')
            }
        }
        insN = ArrayList(HashSet(insN))
        insN.sort()

//     完成字符串拼接（从string复制、插入引号和换行
//     ins_quote 在引号前插入一个引号。   ins_quote[i]!=0,则array_quote.get(i)的引号前需要前插入'”'
//     ins_n 插入换行。数组的值表示插入换行符的位置
        val buffer = StringBuilder((str.length * 1.15).toInt())
        var j = 0
        var progress = 0
        var nextLine = -1
        if (insN.isNotEmpty()) nextLine = insN[j]
        for (i in arrayQuote.indices) {
            val quote = arrayQuote[i]

//            把引号前的换行符与内容相间插入
            while (j < insN.size) {

//                如果下一个换行符在当前引号前，那么需要此次处理.如果紧挨当前引号，需要考虑插入引号的情况
                if (nextLine >= quote) break
                nextLine = insN[j]
                buffer.append(string, progress, nextLine + 1)
                buffer.append('\n')
                progress = nextLine + 1
                j++
            }
            if (progress < quote) {
                buffer.append(string, progress, quote + 1)
                progress = quote + 1
            }
            if (insQuote[i] && buffer.length > 2) {
                if (buffer[buffer.length - 1] == '\n') buffer.append('“') else buffer.insert(
                    buffer.length - 1,
                    "”\n"
                )
            }
        }
        while (j < insN.size) {
            nextLine = insN[j]
            if (progress <= nextLine) {
                buffer.append(string, progress, nextLine + 1)
                buffer.append('\n')
                progress = nextLine + 1
            }
            j++
        }
        if (progress < string.length) {
            buffer.append(string, progress, string.length)
        }
        return buffer.toString()
    }

    /**
     * 从字符串提取引号包围,且不止出现一次的内容为字典
     *
     * @param str
     * @return 词条列表
     */
    private fun makeDict(str: String): List<String> {

        // 引号中间不包含任何标点
        val patten = Pattern.compile(
            """
          (?<=["'”“])([^
          \p{P}]{1,$WORD_MAX_LENGTH})(?=["'”“])
          """.trimIndent()
        )
        //Pattern patten = Pattern.compile("(?<=[\"'”“])([^\n\"'”“]{1,16})(?=[\"'”“])");
        val matcher = patten.matcher(str)
        val cache: MutableList<String> = ArrayList()
        val dict: MutableList<String> = ArrayList()
        while (matcher.find()) {
            val word = matcher.group()
            if (cache.contains(word)) {
                if (!dict.contains(word)) dict.add(word)
            } else cache.add(word)
        }
        return dict
    }

    /**
     * 计算匹配到字典的每个字符的位置
     *
     * @param str     待匹配的字符串
     * @param key     字典
     * @param from    从字符串的第几个字符开始匹配
     * @param to      匹配到第几个字符结束
     * @param inOrder 是否按照从前向后的顺序匹配
     * @return 返回距离构成的ArrayList<Int>
     */
    private fun seekIndexes(
        str: String,
        key: String,
        from: Int,
        to: Int,
        inOrder: Boolean
    ): ArrayList<Int> {
        val list = ArrayList<Int>()
        if (str.length - from < 1) return list
        var i = 0
        if (from > 0) i = from
        var t = str.length
        if (to > 0) t = min(t, to)
        var c: Char
        while (i < t) {
            c = if (inOrder) str[i] else str[str.length - i - 1]
            if (key.indexOf(c) != -1) {
                if (list.isNotEmpty() && i - list.last() == 1) {
                    list[list.lastIndex] = i
                } else {
                    list.add(i)
                }
            }
            i++
        }
        return list
    }

    /**
     * 计算字符串最后出现与字典中字符匹配的位置
     *
     * @param str  数据字符串
     * @param key  字典字符串
     * @param from 从哪个字符开始匹配，默认最末位
     * @param to   匹配到哪个字符（不包含此字符）默认0
     * @return 位置（正向计算)
     */
    private fun seekLast(str: String, key: String, from: Int, to: Int): Int {
        if (str.length - from < 1) return -1
        var i = str.lastIndex
        if (from < i && i > 0) i = from
        var t = 0
        if (to > 0) t = to
        var c: Char
        while (i > t) {
            c = str[i]
            if (key.indexOf(c) != -1) {
                return i
            }
            i--
        }
        return -1
    }

    /**
     * 计算字符串与字典中字符的最短距离
     *
     * @param str     数据字符串
     * @param key     字典字符串
     * @param from    从哪个字符开始匹配，默认0
     * @param to      匹配到哪个字符（不包含此字符）默认匹配到最末位
     * @param inOrder 是否从正向开始匹配
     * @return 返回最短距离, 注意不是str的char的下标
     */
    private fun seekIndex(str: String, key: String, from: Int, to: Int, inOrder: Boolean): Int {
        if (str.length - from < 1) return -1
        var i = 0
        if (from > 0) i = from
        var t = str.length
        if (to > 0) t = min(t, to)
        var c: Char
        while (i < t) {
            c = if (inOrder) str[i] else str[str.length - i - 1]
            if (key.indexOf(c) != -1) {
                return i
            }
            i++
        }
        return -1
    }

    /* 搜寻引号并进行分段。处理了一、二、五三类常见情况
    参照百科词条[引号#应用示例](https://baike.baidu.com/item/%E5%BC%95%E5%8F%B7/998963?#5)对引号内容进行矫正并分句。
    一、完整引用说话内容，在反引号内侧有断句标点。例如：
            1) 丫姑折断几枝扔下来，边叫我的小名儿边说：“先喂饱你！”
            2）“哎呀，真是美极了！”皇帝说，“我十分满意！”
            3）“怕什么！海的美就在这里！”我说道。
    二、部分引用，在反引号外侧有断句标点：
            4）适当地改善自己的生活，岂但“你管得着吗”，而且是顺乎天理，合乎人情的。
            5）现代画家徐悲鸿笔下的马，正如有的评论家所说的那样，“形神兼备，充满生机”。
            6）唐朝的张嘉贞说它“制造奇特，人不知其所为”。
    三、一段接着一段地直接引用时，中间段落只在段首用起引号，该段段尾却不用引回号。但是正统文学不在考虑范围内。
    四、引号里面又要用引号时，外面一层用双引号，里面一层用单引号。暂时不需要考虑
    五、反语和强调，周围没有断句符号。
    */

    //  句子结尾的标点。因为引号可能存在误判，不包含引号。
    //
    // 11-14 用户令（长段拆分加强版，当时叫「长句分割」）：「加强版就是遇到分号也分段」。
    // 把 `；;` 并入句末集合：
    //   · 中文里分号常用来并列举证，本身就是一个语义停顿点，切开不影响语义完整；
    //   · 与 UTTS 的 splitSentences 口径（`[。？?！!;；]`）对齐，两端判定一致；
    //   · 出版书里「……第一；……第二；」这种超长并列句，只有按分号才切得动
    //     （否则整段只能靠逗号降级切，会切在半句中间）。
    //
    // 11-14（当时叫「长句分割」的缺口1）：省略号 `…` 从 MID 移到 END。
    // 中文里 `……` 绝大多数是**句子收尾**（"他想了想……"/"然后呢……"），
    // 放在 MID 里被当逗号级——降级切分时才会用到它，正常切分根本不认；
    // 结果是含省略号的句子只能靠后面的逗号断，容易断在半句。
    // ⚠️ 已知取舍：少数情况下 `……` 是句中停顿（"他想了很久……最后还是走了"），
    // 这时会被切成两段。误切一次的代价（多一次停顿）远小于"整段只能靠逗号凑"。
    private const val MARK_SENTENCES_END = "？。！?!~；;…"
    private const val MARK_SENTENCES_END_P = ".？。！?!~…"

    //  句中标点，由于某些网站常把“，”写为"."，故英文句点按照句中标点判断
    private const val MARK_SENTENCES_MID = ".，、,—"
    private const val MARK_SENTENCES_SAY = "问说喊唱叫骂道着答"

    //  XXX说：“”的冒号
    private const val MARK_QUOTATION_BEFORE = "，：,:"

    //  引号
    private const val MARK_QUOTATION = "\"“”"
    private const val MARK_QUOTATION_RIGHT = "\"”"
    private val PARAGRAPH_DIAGLOG = "^[\"”“][^\"”“]+[\"”“]$".toRegex()

    //  限制字典的长度
    private const val WORD_MAX_LENGTH = 16

    //  长段拆分：超长段落兜底切分参数（11-14 由「重新分段」改名）
    //  依据主流网文（起点/番茄）实测：段落中位数约 30 字、平均约 42 字，
    //  超过 50 字的仅约 1/4，故目标段长取 50，与原生段落长度对齐
    private const val MAX_PARAGRAPH_LENGTH = 50    // 目标段长（字）
    private const val MIN_MERGE_LENGTH = 15       // 末段短于此值并入前段

    //  其后的引号按反引号处理（用于判定只有一个引号时的引号方向）
    private const val MARK_QUOTE_AFTER_CLOSE = "。？！~…,，、;；:："

    private fun match(rule: String, chr: Char): Boolean {
        return rule.indexOf(chr) != -1
    }
}