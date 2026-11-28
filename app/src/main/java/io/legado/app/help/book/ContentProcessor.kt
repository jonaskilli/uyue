package io.legado.app.help.book

import android.os.Build
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.AppPattern.spaceRegex
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.exception.RegexTimeoutException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.escapeRegex
import io.legado.app.utils.replace
import io.legado.app.utils.stackTraceStr
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern

class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    companion object {
        private val processors = hashMapOf<String, WeakReference<ContentProcessor>>()
        private val isAndroid8 = Build.VERSION.SDK_INT in 26..27

        fun get(book: Book) = get(book.name, book.origin)

        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val processorWr = processors[bookName + bookOrigin]
            var processor: ContentProcessor? = processorWr?.get()
            if (processor == null) {
                processor = ContentProcessor(bookName, bookOrigin)
                processors[bookName + bookOrigin] = WeakReference(processor)
            }
            return processor
        }

        fun upReplaceRules() {
            processors.forEach {
                it.value.get()?.upReplaceRules()
            }
        }

    }

    private val titleReplaceRules = CopyOnWriteArrayList<ReplaceRule>()
    private val contentReplaceRules = CopyOnWriteArrayList<ReplaceRule>()
    val removeSameTitleCache = hashSetOf<String>()

    init {
        upReplaceRules()
        upRemoveSameTitle()
    }

    fun upReplaceRules() {
        titleReplaceRules.run {
            clear()
            addAll(appDb.replaceRuleDao.findEnabledByTitleScope(bookName, bookOrigin))
        }
        contentReplaceRules.run {
            clear()
            addAll(appDb.replaceRuleDao.findEnabledByContentScope(bookName, bookOrigin))
        }
    }

    private fun upRemoveSameTitle() {
        val book = appDb.bookDao.getBookByOrigin(bookName, bookOrigin) ?: return
        removeSameTitleCache.clear()
        val files = BookHelp.getChapterFiles(book).filter {
            it.endsWith("nr")
        }
        removeSameTitleCache.addAll(files)
    }

    fun getTitleReplaceRules(): List<ReplaceRule> {
        return titleReplaceRules
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun getContentReplaceRules(): List<ReplaceRule> {
        return contentReplaceRules
    }

    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        fixQuote: Boolean = true,
        reSegment: Boolean = true
    ): BookContent {
        var mContent = content
        var sameTitleRemoved = false
        var effectiveReplaceRules: ArrayList<ReplaceRule>? = null
        if (content != "null") {
            //去除重复标题
            val fileName = chapter.getFileName("nr")
            if (!removeSameTitleCache.contains(fileName)) try {
                val name = Pattern.quote(book.name)
                var title = chapter.title.escapeRegex().replace(spaceRegex, "\\\\s*")
                var matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                    .matcher(mContent)
                if (matcher.find()) {
                    mContent = mContent.substring(matcher.end())
                    sameTitleRemoved = true
                } else if (useReplace && book.getUseReplaceRule()) {
                    title = Pattern.quote(
                        chapter.getDisplayTitle(
                            contentReplaceRules,
                            chineseConvert = false
                        )
                    )
                    matcher = Pattern.compile("^(\\s|\\p{P}|${name})*${title}(\\s)*")
                        .matcher(mContent)
                    if (matcher.find()) {
                        mContent = mContent.substring(matcher.end())
                        sameTitleRemoved = true
                    }
                }
            } catch (e: Exception) {
                AppLog.put("去除重复标题出错\n${e.localizedMessage}", e)
            }
            if (fixQuote && book.getFixQuote()) {
                //双引号修复（必须先于重新分段，修复错乱/缺失的引号）
                mContent = QuoteFix.fix(mContent)
            }
            // ⚠️ 11-14 结构性调整（用户令）：替换净化必须跑在「长段拆分」**之前**。
            //
            // 原因（实测）：档2（旁白/对话分离）以「双引号边界」判断哪里是对话。
            // 但出版书里大量双引号是用来**强调/术语**的（“宣统”“皇帝陛下”“无用”
            // “保全面子”……），不是对话。用户为此配了一条替换规则把这些转成单引号
            // （正是他贴的那条“非对话改引号为单引号”）。
            // 旧顺序是「先拆分、后替换」：拆分时双引号还没被规范化，档2 把“宣统”
            // 当成对话切了出来，替换规则再来改已经来不及 —— 规则对档2 完全无效。
            //
            // 实测《我在溥仪身边十三年》前 5 章（28 段）：
            //   先拆分后替换：8 段被误拆、产生 11 个短碎片（“宣统”“皇上”各成一段）
            //   先替换后拆分：3 段被拆、产生 0 个短碎片
            //
            // ⚠️ 只影响档2（引号判据）；档1 只看句末标点，顺序对结果无影响。
            // ⚠️ 替换块内含 `lines().joinToString { it.trim() }`（按行去首尾空白），
            //    挪动后对空白行的影响与原先一致，因为它仍在「分段成 contents」之前。
            if (useReplace && book.getUseReplaceRule()) {
                val replaceBook = book.toSearchBook()
                //替换
                effectiveReplaceRules = arrayListOf()
                mContent = mContent.lines().joinToString("\n") { it.trim() }
                getContentReplaceRules().forEach { item ->
                    if (item.pattern.isEmpty()) {
                        return@forEach
                    }
                    try {
                        val tmp = if (item.isRegex) {
                            mContent.replace(
                                item.regex,
                                item.replacement,
                                item.getValidTimeoutMillisecond(),
                                chapter,
                                replaceBook
                            )
                        } else {
                            mContent.replace(item.pattern, item.replacement)
                        }
                        if (mContent != tmp) {
                            effectiveReplaceRules.add(item)
                            mContent = tmp
                        }
                    } catch (e: RegexTimeoutException) {
                        item.isEnabled = false
                        appDb.replaceRuleDao.update(item)
                        mContent = item.name + e.stackTraceStr
                    } catch (_: CancellationException) {
                    } catch (e: Exception) {
                        AppLog.put("替换净化: 规则 ${item.name}替换出错.\n${mContent}", e)
                        appCtx.toastOnUi("替换净化: 规则 ${item.name}替换出错")
                    }
                }
            }

            if (reSegment && book.getReSegment()) {
                //长段拆分（只按句末标点切超长段；档2 旁白/对话分离已按用户令删除）
                mContent = ContentHelp.reSegment(mContent, chapter.title)
            }
            if (chineseConvert) {
                //简繁转换
                try {
                    when (AppConfig.chineseConverterType) {
                        1 -> mContent = ChineseUtils.t2s(mContent)
                        2 -> mContent = ChineseUtils.s2t(mContent)
                    }
                } catch (_: Exception) {
                    appCtx.toastOnUi("简繁转换出错")
                }
            }
            val useHtmlMap = mutableMapOf<String, String>()
            if (AppConfig.adaptSpecialStyle) { //html处理
                mContent = AppPattern.useHtmlRegex.replace(mContent) { matchResult ->
                    val placeholder = "特殊格式的占位不应该被看见${useHtmlMap.size}。"
                    useHtmlMap[placeholder] = "\n${matchResult.value.replace("\n","")}\n"
                    placeholder
                }
            }
            useHtmlMap.forEach { (placeholder, originalContent) ->
                mContent = mContent.replace(placeholder, originalContent)
            }
        }
        if (includeTitle) {
            // 重新添加「替换后的标题」，并在开双引号修复时去掉标题里的全角双引号：
            // 朗读脚本按 data.json 第一行标题定位章节并给角色缓存 json 命名，
            // 阅读端也按同一标题找缓存，三处必须是同一个名字。
            var displayTitle = chapter.getDisplayTitle(
                getTitleReplaceRules(),
                useReplace = useReplace && book.getUseReplaceRule()
            )
            if (fixQuote && book.getFixQuote()) {
                displayTitle = QuoteFix.fixTitle(displayTitle)
            }
            mContent = displayTitle + "\n" + mContent
        }
        if (isAndroid8) {
            mContent = mContent.replace('\u00A0', ' ')
        }
        val contents = arrayListOf<String>()
        mContent.split("\n").forEach { str ->
            val paragraph = str.trim {
                it.code <= 0x20 || it == '　'
            }
            if (paragraph.isNotEmpty()) {
                if (contents.isEmpty() && includeTitle) {
                    contents.add(paragraph)
                } else {
                    contents.add("${ReadBookConfig.paragraphIndent}$paragraph")
                }
            }
        }
        return BookContent(sameTitleRemoved, contents, effectiveReplaceRules)
    }

}
