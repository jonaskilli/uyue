package io.legado.app.model.localBook

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.R
import io.legado.app.exception.EmptyFileException
import io.legado.app.help.DefaultData
import io.legado.app.help.book.isLocalModified
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.EncodingDetect
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.Utf8BomUtils
import splitties.init.appCtx
import java.io.FileNotFoundException
import java.nio.charset.Charset
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.math.min

class TextFile(private var book: Book) {

    @Suppress("ConstPropertyName")
    companion object {
        private val padRegex = "^[\\n\\s]+".toRegex()
        const val spaceChars = "🫅🈳🏻"
        private const val txtBufferSize = 8 * 1024 * 1024
        private var textFile: TextFile? = null

        /**
         * 内置「阅读导出书籍」目录规则。
         *
         * 导出 txt 时（ContentProcessor.getContent）章节标题不带缩进、正文段落带缩进，
         * 且每章之间有一个空行，据此可精确匹配标题行：
         * - `(?:(?<=\n\n)|^)` 标题前是空行，或位于文件开头。
         *   末尾的 `|^` 用于文件被手动删掉书名/作者/简介、直接从第一章开始的情况，
         *   此时首个标题前没有空行，仅靠 lookbehind 会漏掉第一章。
         *   lookbehind 为固定长度，在 Java 中合法且不消耗字符，matcher.start() 仍指向标题首字符。
         * - `(?![\s　])` 排除带缩进的正文段落。
         * - `(.*\S.*)` 排除空行。
         * - `(?=\n[ \t　]|\s*\z)` 标题后必须紧跟带缩进的正文行，或已是文件结尾。
         *   这一项抵消 `|^` 的副作用：文件开头的书名/作者/简介同样位于行首且无缩进，
         *   但它们后面跟的是另一行无缩进元信息或空行，唯有真正的标题后面才跟缩进正文。
         *
         * 不入库：它是自动识别专用规则，不应被用户编辑或删除，
         * 也不应影响 [getTocRules] 按序遍历的选优逻辑。
         */
        const val LEGADO_EXPORT_RULE_ID = -9999L

        /** 是否为内置的「阅读导出书籍」规则。它不入库，编辑/删除/启停等操作对它无效。 */
        fun isBuiltInRule(tocRule: TxtTocRule?): Boolean =
            tocRule != null && tocRule.id == LEGADO_EXPORT_RULE_ID

        val legadoExportTocRule: TxtTocRule by lazy {
            TxtTocRule(
                id = LEGADO_EXPORT_RULE_ID,
                name = "阅读导出书籍",
                rule = "(?:(?<=\\n\\n)|^)(?![\\s　])(.*\\S.*)(?=\\n[ \\t　]|\\s*\\z)",
                example = "第一章 标题（自身无缩进，后一行是带缩进的正文）",
                enable = true
            )
        }

        // 以下为导出格式识别的阈值，只用于自动识别，不影响用户手动选择的规则
        /** 最多检查的行数，避免大文件全量扫描 */
        private const val MAX_FORMAT_CHECK_LINES = 3000
        /** 少于此行数不判定为导出格式（内容太少，统计不可靠） */
        private const val MIN_FORMAT_CHECK_LINES = 10
        /** 缩进行占比需超过此值 */
        private const val INDENT_RATIO_THRESHOLD = 0.5f
        /** 至少要有这么多个候选标题 */
        private const val MIN_CANDIDATE_TITLES = 3

        @Synchronized
        private fun getTextFile(book: Book): TextFile {
            if (textFile == null || textFile?.book?.bookUrl != book.bookUrl || book.isLocalModified()) {
                textFile = TextFile(book)
                return textFile!!
            }
            textFile?.book = book
            return textFile!!
        }

        @Throws(FileNotFoundException::class)
        fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getTextFile(book).getChapterList()
        }

        @Synchronized
        @Throws(FileNotFoundException::class)
        fun getContent(book: Book, bookChapter: BookChapter): String {
            return getTextFile(book).getContent(bookChapter)
        }

        fun clear() {
            textFile = null
        }

    }

    private val blank: Byte = 0x0a

    //默认从文件中获取数据的长度
    private val bufferSize = 512000

    //没有标题的时候，每个章节的最大长度
    private val maxLengthWithNoToc = 10 * 1024

    //使用正则划分目录，每个章节的最大允许长度
    private val maxLengthWithToc = 102400

    private var charset: Charset = book.fileCharset()

    private var txtBuffer: ByteArray? = null
    private var bufferStart = -1L
    private var bufferEnd = -1L

    //选中更好的目录规则判断阈值
    private val overRuleCount = 2
    private val toSearchBook = book.toSearchBook()
    class MutableRef<String>(var value: String)
    private val lastVolumeTitle = MutableRef("")

    /**
     * 获取目录
     */
    @Throws(FileNotFoundException::class, SecurityException::class, EmptyFileException::class)
    fun getChapterList(): ArrayList<BookChapter> {
        val modified = book.isLocalModified()
        if (book.charset == null || book.tocUrl.isBlank() || modified) {
            LocalBook.getBookInputStream(book).use { bis ->
                val buffer = ByteArray(bufferSize)
                val length = bis.read(buffer)
                if (length == -1) throw EmptyFileException("Unexpected Empty Txt File")
                if (book.charset.isNullOrBlank() || modified) {
                    book.charset = EncodingDetect.getEncode(buffer.copyOf(length))
                }
                charset = book.fileCharset()
                if (book.tocUrl.isBlank() || modified) {
                    val blockContent = String(buffer, 0, length, charset)
                    val tocRule = getTocRule(blockContent)
                    book.tocUrl = tocRule?.let { it.rule + spaceChars + it.replacement } ?: ""
                }
            }
        }
        val (toc, wordCount) = analyze(book.tocUrl.split(spaceChars, limit = 2))
        book.wordCount = StringUtils.wordCountFormat(wordCount)
        toc.forEachIndexed { index, bookChapter ->
            bookChapter.index = index
            bookChapter.bookUrl = book.bookUrl
            bookChapter.url = MD5Utils.md5Encode16(book.originName + index + bookChapter.title)
        }
        getWordCount(toc, book)
        return toc
    }

    fun getContent(chapter: BookChapter): String {
        val start = chapter.start!!
        val end = chapter.end!!
        if (txtBuffer == null || start > bufferEnd || end < bufferStart) {
            LocalBook.getBookInputStream(book).use { bis ->
                bufferStart = txtBufferSize * (start / txtBufferSize)
                txtBuffer = ByteArray(min(txtBufferSize, bis.available() - bufferStart.toInt()))
                bufferEnd = bufferStart + txtBuffer!!.size
                bis.skip(bufferStart)
                bis.read(txtBuffer)
            }
        }

        val count = (end - start).toInt()
        val buffer = ByteArray(count)

        @Suppress("ConvertTwoComparisonsToRangeCheck")
        if (start < bufferEnd && end > bufferEnd || start < bufferStart && end > bufferStart) {
            /** 章节内容在缓冲区交界处 */
            LocalBook.getBookInputStream(book).use { bis ->
                bis.skip(start)
                bis.read(buffer)
            }
        } else {
            /** 章节内容在缓冲区内 */
            txtBuffer!!.copyInto(
                buffer,
                0,
                (start - bufferStart).toInt(),
                (end - bufferStart).toInt()
            )
        }

        return String(buffer, charset)
            //.substringAfter(chapter.title) //之前裁切没有间隙，所以正文保留了标题，需要在这儿去掉
            .replace(padRegex, "　　")
    }

    /**
     * 按规则解析目录
     */
    private fun analyze(rr: List<String>): Pair<ArrayList<BookChapter>, Int> {
        val pattern = rr[0].toPattern(Pattern.MULTILINE)
        val jsStr = rr.getOrNull(1)
        if (rr[0].isEmpty()) {
            return analyze()
        }
        // 使用内置规则时，文件开头的书名/作者/简介属于书籍信息，不再生成「前言」章节
        val useLegadoExportRule = rr[0] == legadoExportTocRule.rule
        lastVolumeTitle.value = ""
        val toc = arrayListOf<BookChapter>()
        var bookWordCount = 0
        LocalBook.getBookInputStream(book).use { bis ->
            var blockContent: String
            //加载章节
            var curOffset: Long = 0
            //读取的长度
            var length: Int
            var lastChapterWordCount = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            bis.read(buffer, 0, 3)
            if (Utf8BomUtils.hasBom(buffer)) {
                bufferStart = 0
                curOffset = 3
            }
            //获取文件中的数据到buffer，直到没有数据为止
            while (bis.read(
                    buffer, bufferStart, bufferSize - bufferStart
                ).also { length = it } > 0
            ) {
                var end = bufferStart + length
                if (end == bufferSize) {
                    for (i in bufferStart + length - 1 downTo 0) {
                        if (buffer[i] == blank) {
                            end = i
                            break
                        }
                    }
                }
                //将数据转换成String, 不能超过length
                blockContent = String(buffer, 0, end, charset)
                buffer.copyInto(buffer, 0, end, bufferStart + length)
                bufferStart = bufferStart + length - end
                length = end
                //当前Block下使过的String的指针
                var seekPos = 0
                //进行正则匹配
                val matcher: Matcher = pattern.matcher(blockContent)
                //如果存在相应章节
                while (matcher.find()) { //获取匹配到的字符在字符串中的起始位置
                    val chapterStart = matcher.start()
                    //获取章节内容
                    val chapterContent = blockContent.substring(seekPos, chapterStart)
                    val chapterContentLength = chapterContent.length
                    val chapterLength = chapterContent.toByteArray(charset).size.toLong()
                    val titleLength = matcher.group().toByteArray(charset).size.toLong()
                    val lastStart = toc.lastOrNull()?.start ?: curOffset
                    if (book.getSplitLongChapter() && curOffset + chapterLength - lastStart > maxLengthWithToc) {
                        //章节字数太多进行拆分
                        val lastTitle = toc.lastOrNull()?.let {
                            it.end = it.start
                            it.isVolume = true
                            lastVolumeTitle.value = it.title
                            it.tag = null
                            it.title
                        }
                        val title = replacement(
                            matcher.group(),
                            jsStr,
                            toc,
                            lastTitle,
                            chapterContentLength
                        ).takeIf { it.isNotEmpty() } ?: continue
                        val (chapters, wordCount) = analyze(lastStart, curOffset + chapterLength)
                        lastTitle?.let {
                            chapters.forEachIndexed { index, bookChapter ->
                                bookChapter.title = "$lastTitle(${index + 1})"
                            }
                        }
                        toc.addAll(chapters)
                        bookWordCount += wordCount
                        //创建当前章节
                        val curChapter = BookChapter()
                        curChapter.title = title
                        curChapter.start = curOffset + chapterLength
                        curChapter.end = curChapter.start
                        toc.add(curChapter)
                        lastChapterWordCount = 0
                    } else if (seekPos == 0 && chapterStart != 0) {
                        /**
                         * 如果 seekPos == 0 && chapterStart != 0 表示当前block处前面有一段内容
                         * 第一种情况一定是序章 第二种情况是上一个章节的内容
                         */
                        if (toc.isEmpty()) { //如果当前没有章节，那么就是序章
                            //加入简介
                            if (chapterContent.isNotBlank()) {
                                if (useLegadoExportRule) {
                                    // 阅读导出的书籍：开头是书名/作者/简介，属于书籍信息。
                                    // 与书源书籍保持一致，目录直接从第一章开始，不生成「前言」章节。
                                    val (author, intro) = parseBookMetaInfo(chapterContent)
                                    if (author.isNotBlank()) {
                                        book.author = author
                                    }
                                    if (intro.isNotBlank()) {
                                        book.intro = intro.take(600)
                                    }
                                } else {
                                    val title = replacement("前言", jsStr, toc)
                                    if (title.isNotEmpty()) {
                                        //如果js把"前言"处理成空了，那么就不要前言,并且前言内容会全部放到简介里面去
                                        val qyChapter = BookChapter()
                                        qyChapter.title = title
                                        qyChapter.start = curOffset
                                        qyChapter.end = curOffset + chapterLength
                                        qyChapter.wordCount =
                                            StringUtils.wordCountFormat(chapterContentLength)
                                        toc.add(qyChapter)
                                    }
                                    book.intro =
                                        if (chapterContentLength > 600 && title.isNotEmpty()) {
                                            chapterContent.take(600)
                                        } else {
                                            chapterContent
                                        }
                                }
                            }
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc
                            ).takeIf { it.isNotEmpty() } ?: continue
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = curOffset + chapterLength + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        } else { //否则就block分割之后，上一个章节的剩余内容
                            //获取上一章节
                            val lastChapter = toc.last()
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc,
                                lastChapter.title,
                                chapterContentLength
                            ).takeIf { it.isNotEmpty() } ?: continue
                            if (chapterContent.isBlank()) {
                                lastChapter.isVolume = true
                                lastVolumeTitle.value = lastChapter.title
                            } else {
                                lastChapter.isVolume = false
                            }
                            //将当前段落添加上一章去
                            lastChapter.end = lastChapter.end!! + chapterLength
                            lastChapterWordCount += chapterContentLength
                            lastChapter.wordCount =
                                StringUtils.wordCountFormat(lastChapterWordCount)
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = lastChapter.end!! + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        }
                        bookWordCount += chapterContentLength
                        lastChapterWordCount = 0
                    } else {
                        if (toc.isNotEmpty()) { //获取章节内容
                            //获取上一章节
                            val lastChapter = toc.last()
                            val title = replacement(
                                matcher.group(),
                                jsStr,
                                toc,
                                lastChapter.title,
                                chapterContentLength
                            ).takeIf { it.isNotEmpty() }
                                ?: continue
                            if (chapterContent.isBlank()) {
                                lastChapter.isVolume = true
                                lastVolumeTitle.value = lastChapter.title
                            } else {
                                lastChapter.isVolume = false
                            }
                            lastChapter.end =
                                lastChapter.start!! + chapterLength
                            lastChapter.wordCount =
                                StringUtils.wordCountFormat(chapterContentLength)
                            //创建当前章节
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = lastChapter.end!! + titleLength
                            curChapter.end = curChapter.start
                            toc.add(curChapter)
                        } else { //如果章节不存在则创建章节
                            val title = replacement(matcher.group(), jsStr, toc).takeIf { it.isNotEmpty() }
                                ?: continue
                            val curChapter = BookChapter()
                            curChapter.title = title
                            curChapter.start = curOffset + titleLength
                            curChapter.end = curChapter.start
                            curChapter.wordCount =
                                StringUtils.wordCountFormat(chapterContentLength)
                            toc.add(curChapter)
                        }
                        bookWordCount += chapterContentLength
                        lastChapterWordCount = 0
                    }
                    //设置指针偏移
                    seekPos += chapterContentLength + matcher.group().length
                }
                val wordCount = blockContent.length - seekPos
                bookWordCount += wordCount
                lastChapterWordCount += wordCount
                //block的偏移点
                curOffset += length.toLong()
                //设置上一章的结尾
                toc.lastOrNull()?.let {
                    it.end = curOffset
                    it.wordCount = StringUtils.wordCountFormat(lastChapterWordCount)
                }
            }
            toc.lastOrNull()?.let { chapter ->
                //章节字数太多进行拆分
                if (book.getSplitLongChapter() && chapter.end!! - chapter.start!! > maxLengthWithToc) {
                    val end = chapter.end!!
                    chapter.end = chapter.start
                    chapter.isVolume = true
                    chapter.tag = null
                    val lastTitle = chapter.title
                    lastVolumeTitle.value = lastTitle
                    val (chapters, _) = analyze(
                        chapter.start!!, end
                    )
                    chapters.forEachIndexed { index, bookChapter ->
                        bookChapter.title = "$lastTitle(${index + 1})"
                    }
                    toc.addAll(chapters)
                }
            }
        }
        System.gc()
        System.runFinalization()
        return toc to bookWordCount
    }

    /**
     * 无规则拆分目录
     */
    private fun analyze(
        fileStart: Long = 0L, fileEnd: Long = Long.MAX_VALUE
    ): Pair<ArrayList<BookChapter>, Int> {
        val toc = arrayListOf<BookChapter>()
        var bookWordCount = 0
        LocalBook.getBookInputStream(book).use { bis ->
            //block的个数
            var blockPos = 0
            //加载章节
            var curOffset: Long = 0
            var chapterPos = 0
            //读取的长度
            var length = 0
            var lastChapterWordCount = 0
            val buffer = ByteArray(bufferSize)
            var bufferStart = 3
            if (fileStart == 0L) {
                bis.read(buffer, 0, 3)
                if (Utf8BomUtils.hasBom(buffer)) {
                    bufferStart = 0
                    curOffset = 3
                }
            } else {
                bis.skip(fileStart)
                curOffset = fileStart
                bufferStart = 0
            }
            //获取文件中的数据到buffer，直到没有数据为止
            while (fileEnd - curOffset - bufferStart > 0 && bis.read(
                    buffer, bufferStart, min(
                        (bufferSize - bufferStart).toLong(), fileEnd - curOffset - bufferStart
                    ).toInt()
                ).also { length = it } > 0
            ) {
                blockPos++
                //章节在buffer的偏移量
                var chapterOffset = 0
                //当前剩余可分配的长度
                length += bufferStart
                var strLength = length
                //分章的位置
                chapterPos = 0
                while (strLength > 0) {
                    chapterPos++
                    //是否长度超过一章
                    if (strLength > maxLengthWithNoToc) { //在buffer中一章的终止点
                        var end = length
                        //寻找换行符作为终止点
                        for (i in chapterOffset + maxLengthWithNoToc until length) {
                            if (buffer[i] == blank) {
                                end = i
                                break
                            }
                        }
                        val content = String(buffer, chapterOffset, end - chapterOffset, charset)
                        bookWordCount += content.length
                        lastChapterWordCount = content.length
                        val chapter = BookChapter()
                        chapter.title = "第${blockPos}章($chapterPos)"
                        chapter.start = toc.lastOrNull()?.end ?: curOffset
                        chapter.end = chapter.start!! + end - chapterOffset
                        chapter.wordCount = StringUtils.wordCountFormat(content.length)
                        toc.add(chapter)
                        //减去已经被分配的长度
                        strLength -= (end - chapterOffset)
                        //设置偏移的位置
                        chapterOffset = end
                    } else {
                        buffer.copyInto(buffer, 0, length - strLength, length)
                        length -= strLength
                        bufferStart = strLength
                        strLength = 0
                    }
                }
                //block的偏移点
                curOffset += length.toLong()
            }
            //设置结尾章节
            val content = String(buffer, 0, bufferStart, charset)
            bookWordCount += content.length
            if (bufferStart > 100 || toc.isEmpty()) {
                val chapter = BookChapter()
                chapter.title = "第${blockPos}章(${chapterPos})"
                chapter.start = toc.lastOrNull()?.end ?: curOffset
                chapter.end = chapter.start!! + bufferStart
                chapter.wordCount = StringUtils.wordCountFormat(content.length)
                toc.add(chapter)
            } else {
                val wordCount = lastChapterWordCount + content.length
                toc.lastOrNull()?.let {
                    it.end = it.end!! + bufferStart
                    it.wordCount = StringUtils.wordCountFormat(wordCount)
                }
            }
        }
        return toc to bookWordCount
    }

    /**
     * 判断文本是否**确定**为本应用导出的 txt。
     *
     * 用于自动识别，要求「百分百确认」，任一项不满足都返回 false 并回退到原有规则选优，
     * 用户仍可在目录规则列表顶端手动选择内置规则。
     *
     * 导出文件的结构极其规整（见 [legadoExportTocRule]）：
     * ```
     * 书名 / 作者 / 简介（连续若干无缩进行）
     * 空行
     * 标题行（无缩进）
     * 　　正文段（带缩进）
     * 　　正文段（带缩进）
     * 空行
     * 标题行
     * ...
     * ```
     * 据此逐行校验：一旦进入正文区，除「前面是空行的标题行」外，
     * 任何无缩进行都说明不是导出格式。这能排除章节内夹杂小标题、
     * 或仅部分段落缩进的普通 txt——它们缩进率可能同样很高，但结构不符。
     */
    private fun isStrictLegadoExportFormat(content: String): Boolean {
        var totalLines = 0
        var indentedLines = 0
        // 文件开头连续的若干无缩进行视为头部元信息（书名/作者/简介），不受结构约束
        var inHeader = true
        var prevBlank = false

        for (line in content.lineSequence()) {
            if (line.isBlank()) {
                prevBlank = true
                inHeader = false
                continue
            }
            if (++totalLines > MAX_FORMAT_CHECK_LINES) break
            // 与导出时的缩进判定保持一致（ContentProcessor 的 trim 规则）
            if (line[0].code <= 0x20 || line[0] == '　') {
                indentedLines++
                inHeader = false
            } else {
                // 正文区出现无缩进行且前面不是空行 → 不是导出格式
                if (!inHeader && !prevBlank) return false
            }
            prevBlank = false
        }

        if (totalLines < MIN_FORMAT_CHECK_LINES) return false
        if (indentedLines.toFloat() / totalLines <= INDENT_RATIO_THRESHOLD) return false
        // 用内置规则实际试匹配，确认确实能分出足够多的章节
        return countTitlesByLegadoRule(content) >= MIN_CANDIDATE_TITLES
    }

    /**
     * 解析导出文件开头的书籍信息，返回 Pair(作者, 简介)。
     *
     * 对应 [ExportBookService.getAllContents] 写出的结构：
     * ```
     * 书名
     * 作者：xxx
     * 简介：
     * <简介内容>
     * ```
     * 第一行是书名，此处不处理（书名由文件名决定）。
     * 解析不到某一项时返回空串，调用方据此决定是否覆盖。
     */
    private fun parseBookMetaInfo(header: String): Pair<String, String> {
        val authorPrefix = metaPrefix(R.string.author_show)
        val introPrefix = metaPrefix(R.string.intro_show)
        var author = ""
        val introLines = arrayListOf<String>()
        var inIntro = false

        header.lineSequence().forEachIndexed { index, line ->
            val trimmed = line.trim()
            when {
                // 第一行是书名
                index == 0 -> return@forEachIndexed
                // 进入简介后，其后所有行都归简介（简介支持多行）
                inIntro -> introLines.add(line)
                trimmed.startsWith(introPrefix) -> {
                    inIntro = true
                    val firstLine = trimmed.removePrefix(introPrefix)
                        .trimStart('：', ':', ' ', '\t')
                    if (firstLine.isNotEmpty()) {
                        introLines.add(firstLine)
                    }
                }
                author.isEmpty() && trimmed.startsWith(authorPrefix) -> {
                    author = trimmed.removePrefix(authorPrefix)
                        .trimStart('：', ':', ' ', '\t')
                }
            }
        }
        return author to introLines.joinToString("\n").trim()
    }

    /**
     * 从形如「作者：%s」的资源串取出前缀（即「作者」）。
     * 用资源串而非硬编码，以兼容当前语言下的导出格式（简体/繁体/英文等）。
     * 同时去掉全角与半角冒号，匹配时再统一允许两种冒号。
     */
    private fun metaPrefix(resId: Int): String {
        return appCtx.getString(resId, "")
            .substringBefore('：')
            .substringBefore(':')
            .trim()
    }

    /**
     * 用内置规则试匹配，统计能分出多少章节。
     * 直接复用规则本身而非另行推导，避免「文件开头被删导致首个标题漏统计」。
     */
    private fun countTitlesByLegadoRule(content: String): Int {
        val pattern = try {
            legadoExportTocRule.rule.toPattern(Pattern.MULTILINE)
        } catch (e: Exception) {
            return 0
        }
        val matcher = pattern.matcher(content)
        var count = 0
        while (matcher.find()) {
            if (++count >= MIN_CANDIDATE_TITLES) return count
        }
        return count
    }

    /**
     * 获取合适的目录规则
     */
    private fun getTocRule(content: String): TxtTocRule? {
        // 仅当结构完全符合导出特征时才自动使用内置规则，优先级高于其后所有规则。
        // 判定不通过一律回退到原有选优逻辑，用户可随时在规则列表顶端手动选择内置规则。
        // 手动选定的规则保存在 book.tocUrl，只有其为空或文件被修改时才会走到这里，
        // 因此手动选择的结果不会被这里的自动识别覆盖。
        if (isStrictLegadoExportFormat(content)) {
            AppLog.put("识别为阅读导出的TXT，使用内置目录规则")
            return legadoExportTocRule
        }
        val rules = getTocRules() //.reversed() 改动num >= maxNum条件，不需要再反转
        var maxNum = 1
        var mTocRule: TxtTocRule? = null
        for (tocRule in rules) {
            val pattern = try {
                tocRule.rule.toPattern(Pattern.MULTILINE)
            } catch (e: PatternSyntaxException) {
                AppLog.put("TXT目录规则正则语法错误:${tocRule.name}\n$e", e)
                continue
            }
            val matcher = pattern.matcher(content)
            var start = 0
            var csNum = 0
            var numE = 0
            var lastTitle: String? = null
            while (matcher.find()) {
                val contentLength = matcher.start() - start
                if (start == 0 || contentLength > 1000) {
                    val title = replacement(matcher.group(), tocRule.replacement, csNum, lastTitle, contentLength)
                    if (title.isNotEmpty()) {
                        lastTitle = title
                        csNum++
                    }
                    start = matcher.end()
                } else if (contentLength < 100) {
                    numE++ //这种不住100字的一般被识别为卷，即错误识别，正常章节数不大于卷3倍的不选
                }
            }
            if (csNum > numE * 3 && (csNum > maxNum + overRuleCount)) { //后面的规则匹配数量没超过最大值2个，那么依旧用前面那个
                maxNum = csNum
                mTocRule = tocRule
                if (maxNum > 70) {
                    break
                } //能获取60个章节，说明这个规则能基本匹配，并且排在前面，所以不考虑后面的规则
            }
        }
        return mTocRule
    }

    class JsExtensions(val lastVolumeTitle: MutableRef<String>, val toc: ArrayList<BookChapter>? = null) {
        fun putVolume(title: String) {
            lastVolumeTitle.value = title
            if (toc != null) {
                val start = toc.lastOrNull()?.end ?: 0
                toc.add(BookChapter(title = title, isVolume = true, start = start, end = start))
            }
        }
    }
    /**
     * 净化标题
     */
    private fun replacement(
        content: String,
        jsStr: String?,
        toc: ArrayList<BookChapter>,
        prevTitle: String? = null,
        prevLength: Int = -1
    ): String {
        if (jsStr.isNullOrBlank()) {
            return content
        }
        return RhinoScriptEngine.run {
            val bindings = ScriptBindings()
            bindings["result"] = content
            bindings["book"] = toSearchBook
            bindings["index"] = toc.size + 1
            bindings["prevTitle"] = prevTitle
            bindings["prevLength"] = prevLength
            bindings["lastVolumeTitle"] = lastVolumeTitle.value
            bindings["java"] = JsExtensions(lastVolumeTitle, toc)
            eval(jsStr, bindings)
        }.toString()
    }

    private fun replacement(
        content: String,
        jsStr: String?,
        index: Int,
        prevTitle: String? = null,
        prevLength: Int = -1
    ): String {
        if (jsStr.isNullOrBlank()) {
            return content
        }
        return RhinoScriptEngine.run {
            val bindings = ScriptBindings()
            bindings["result"] = content
            bindings["book"] = toSearchBook
            bindings["index"] = index + 1
            bindings["prevTitle"] = prevTitle
            bindings["prevLength"] = prevLength
            bindings["lastVolumeTitle"] = lastVolumeTitle.value
            bindings["java"] = JsExtensions(lastVolumeTitle)
            eval(jsStr, bindings)
        }.toString()
    }


    /**
     * 获取启用的目录规则
     */
    private fun getTocRules(): List<TxtTocRule> {
        var rules = appDb.txtTocRuleDao.enabled
        if (appDb.txtTocRuleDao.count == 0) {
            rules = DefaultData.txtTocRules.apply {
                appDb.txtTocRuleDao.insert(*this.toTypedArray())
            }.filter {
                it.enable
            }
        }
        return rules
    }

    private fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

}