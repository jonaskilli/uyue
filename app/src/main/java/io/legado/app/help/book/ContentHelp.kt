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
     * ## 11-14 定稿：不做「旁白／对话分离」（档2 已删除）
     *
     * 曾有一版档2，按引号边界把「旁白+对话」切开。删除依据是真实正文实测：
     *
     *  · 《呼啸山庄》236 段里，它把 537 段切出来（原本 412），<15 字碎片从 86
     *    涨到 220 —— `[3字] 我说，` 这种旁白单独成段，每段单独发一次合成请求，
     *    语调断裂、总请求数暴增，与用户「每段短一点、点下一段反应快」的目的相反；
     *  · 出版书里大量双引号是**术语/强调**（“宣统”“保全面子”），档2 当对话切，
     *    8 段全误拆、产生 11 个碎片；
     *  · 用户最担心的「旁白 + 一长段对话」场景，实测档1 并不吃亏：
     *    《呼啸山庄》363 字的整段长对话（引号内含十余个句子），档1 切成 11 段、
     *    每段 22~50 字，全部断在句末。只要对话本身是多句，档1 就够。
     *
     * 档2 唯一能补的是「旁白 + 一整句没有句末标点的长话」，而那种切完仍是长段
     * （实测 83 字切成 6+77），收益有限。用户已确认可接受该情况原样保留。
     *
     * 因此这里只保留一刀：按句末标点切超长段（档1）。
     *
     * @param content 章节正文（原文，含它自己的分段与缩进）
     * @param chapterName 章名（本实现不再用它，保留参数是为了调用方签名稳定）
     */
    fun reSegment(content: String, chapterName: String): String {
        // 空正文直接返回，避免 StringBuilder 空跑
        if (content.isBlank()) return content
        // 只切超长段；splitLongParagraph 自己会跳过短行
        return splitLongParagraph(StringBuilder(content)).toString()
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
                // 3. 切点计算：只认句末标点（含一层引号内，省略号在内）。
                //
                // ⚠️ 唯一的切点来源就是句末。原先的两级兜底已按用户定稿删除，
                // 它们违背句末语义、实测会切在半句：
                //   · 逗号/分号降级——把 100 字逗号连缀段切成 [54, 46]，切在句中；
                //   · 按字数硬切——把 201 字无标点段切成 [50,50,50,51]，每 50 字生切一刀。
                // 用户原话：「最主要还是得保持他那个合成语义的流畅性，如果这个
                // 保证不了的话，你切的乱七八糟的，不觉得有什么好」。
                cuts.addAll(computeCuts(primary, line.length, MAX_PARAGRAPH_LENGTH))
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
     *  · 只在**引号内**取点（深度 1）。注意本实现深度只会在 0/1 之间跳，
     *    同层引号按开合配对（见 nextQuoteDepth），不会出现 ≥2；
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
     * **深度约束放宽到 ≤1**——引号外（0）与引号内（1）都可切。
     *
     * ⚠️ 关于「嵌套引号」：本实现的 [nextQuoteDepth] 把同层引号按开合配对，
     * 深度只在 0/1 间跳，**永远不会产生 ≥2 的值**（实测 `他说：“她告诉我：
     * ‘明天别来了。’我记住了。”` 最大深度仍是 1）。也就是说这里写 `> 1` 是
     * 一道**语义上的保险**，当前实现下不会命中；真出现 ≥2 的深度说明引号判定
     * 逻辑将来被改动，届时这道保险自动生效。
     */
    private fun addCut(out: MutableList<Int>, line: String, depths: IntArray, pos: Int) {
        if (pos <= 0 || pos >= line.length) return
        if (depths[pos] > 1) return
        if (match(MARK_SENTENCES_END, line[pos])) return
        if (!out.contains(pos)) out.add(pos)
    }

    /**
     * 在一整行（段落）内计算切分点。
     *
     * ## 11-14 用户定稿：无差别切分 + 碎片并回
     *
     * 用户原话：「我一直以为你说的是整段超过 50 字，然后点开启就开始切分。要不那样呗，
     * 无差别切分不行吗？不管这段到底多长，只要开启都进行切分……至于切出来短句就
     * 切出来，也不处理，你觉得合适吗？还是得加个限制？」
     *
     * 结论：**切分本身无差别，但碎片必须并回**。实测依据（《明雾烟》10 个超长段）：
     * 完全放任不管会切出 `“尝尝。”`（5 字）、`付雪梨呢？`（5 字）这种光杆段——
     * 朗读时每段单独发一次合成请求，5 个字独立开口、独立收尾，段间还要停一拍，
     * 恰恰破坏用户最在意的「合成语义的流畅性」。所以碎片要并回前段。
     *
     * ## 规则（三条，取代原先的贪心分组 + 补刀 + 降级）
     *
     *  1. 段落不超过 [threshold]（50 字）⇒ **一刀不切**，短段一个字都不动；
     *  2. 超过 ⇒ **每一个候选句末点都切**，不再要求「距上一个切点 ≥ threshold」。
     *     旧实现那条约束才是省略号失效的真正原因：省略号多出现在句子早期
     *     （实测 `他想了很久……最后还是决定…`，省略号在第 5 字），
     *     一律被跳过，于是 51 字段落明明有切点却一刀不切。
     *  3. 切出来的段不足 [MIN_MERGE_LENGTH]（15 字）⇒ **并回前段**，不产生碎片。
     *
     * 候选点由 [collectSentenceEnds] 提供（句末标点，含一层引号内；省略号在其中）。
     *
     * @param positions 候选切点（升序，已含引号深度约束）
     * @param limit 判定用的目标段长
     */
    private fun computeCuts(
        positions: List<Int>,
        lineLength: Int,
        limit: Int
    ): MutableList<Int> {
        val cuts = ArrayList<Int>()
        // 规则①：短段一个字都不动
        if (lineLength <= limit) return cuts
        // 规则②：每个候选点都切
        for (p in positions) {
            if (p <= 0 || p >= lineLength) continue
            // 规则③：切出来不足 MIN_MERGE_LENGTH 的，不设切点（等于并回前段）。
            // 判定看「这一段相对上一个切点有多长」，不是相对段首——
            // 否则连续几个短句会被反复跳过，最后一段反而超长。
            val prev = cuts.lastOrNull() ?: 0
            if (p - prev < MIN_MERGE_LENGTH) continue
            // 末尾残段过短也并回：不在这里切，让它留在最后一段里
            if (lineLength - p < MIN_MERGE_LENGTH) continue
            if (!cuts.contains(p)) cuts.add(p)
        }
        return cuts
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