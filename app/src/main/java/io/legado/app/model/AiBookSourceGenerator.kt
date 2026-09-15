package io.legado.app.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * AI 书源生成器（页面质检 + 四链路实测）。
 *
 * 流程：
 * ```
 * 阶段一 采集（每个页面都让 AI 质检，不达标自动换方案重试）
 *   首页    → AI 判定是否小说站 + 找出真实搜索入口
 *   搜索页  → AI 判定是否真的返回了结果 + 给出第一本书的详情页网址
 *   详情页  → AI 判定是否含书籍信息 + 目录页网址 + 是否详情/目录合一
 *   目录页  → AI 判定是否有章节列表 + 第一章网址 + 正倒序
 *   正文页  → AI 判定是否真的是正文
 * 阶段二 生成（基于已质检确认的页面与元信息，让 AI 写完整书源）
 * 阶段三 验证（用 App 自带的 WebBook 引擎按真实使用路径跑一遍）
 *   搜索 → 详情 → 目录 → 正文
 *   任一环节失败 → 把「失败环节的规则 + 对应页面真实 HTML」交给 AI 修正 → 重测
 *   最多修正 [MAX_FIX_ROUNDS] 轮，仍失败则带病返回（报告里注明哪环没通过）
 * ```
 */
object AiBookSourceGenerator {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val postClient = client.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 单页送给 AI 的最大字符数 */
    private const val MAX_PAGE_CHARS = 8000

    /** 验证失败后允许 AI 修正的轮数 */
    private const val MAX_FIX_ROUNDS = 2

    private const val MIN_CONTENT_CHARS = 50

    /** 一个抓到并质检过的页面 */
    private data class Page(val role: String, val url: String, val html: String)

    /** 验证报告条目 */
    data class VerifyItem(val step: String, val ok: Boolean, val message: String)

    /** 生成结果：书源 + 四链路验证报告 + 修正轮数 */
    data class GenResult(
        val source: BookSource,
        val verify: List<VerifyItem>,
        val fixRounds: Int
    )

    // ==================== 对外入口 ====================

    suspend fun generate(
        seedUrl: String,
        keyword: String,
        onProgress: (String) -> Unit
    ): Result<GenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val seed = normalize(seedUrl)
            val pages = ArrayList<Page>(6)
            val meta = HashMap<String, String>()

            // ---------- 1) 首页 ----------
            onProgress("抓取首页…")
            var homeHtml = fetch(seed) ?: error("无法访问该地址：$seed")
            pages.add(Page("首页", seed, homeHtml))
            var homeCheck = aiCheckPage(CHECK_HOME, homeHtml, seed)
            var searchTemplate: String? = homeCheck?.str("searchUrl")
            val testKeyword = homeCheck?.str("suggestKeyword").takeUnless { it.isNullOrBlank() }
                ?: keyword
            if (homeCheck?.bool("isNovelSite") == false) {
                onProgress("首页疑似不是小说站，用电脑版 UA 重试…")
                homeHtml = fetch(seed, pcUa = true) ?: homeHtml
                homeCheck = aiCheckPage(CHECK_HOME, homeHtml, seed)
                searchTemplate = homeCheck?.str("searchUrl") ?: searchTemplate
            }
            meta["首页质检"] = homeCheck?.str("problem")?.takeIf { it.isNotBlank() } ?: "通过"

            // ---------- 2) 搜索页 ----------
            var listHtml: String? = null
            var listUrl = ""
            var searchCheck: JsonObject? = null
            var searchDisabled = false
            if (!searchTemplate.isNullOrBlank()) {
                onProgress("搜索关键词「$testKeyword」…")
                listUrl = fillKey(searchTemplate, testKeyword)
                listHtml = fetchPage(searchTemplate, testKeyword)
                if (listHtml != null && pageSimilar(listHtml, homeHtml)) {
                    // 软失败：站点把失效的搜索请求 301 回了首页
                    onProgress("搜索地址被站点重定向回了首页…")
                    listHtml = null
                    searchDisabled = true
                }
                if (listHtml != null) {
                    pages.add(Page("搜索结果页", listUrl, listHtml))
                    searchCheck = aiCheckPage(CHECK_SEARCH, listHtml, listUrl, testKeyword)
                    if (searchCheck?.bool("isSearchResult") == false) {
                        onProgress("搜索结果异常，用电脑版 UA 重试…")
                        val retry = fetch(listUrl, pcUa = true)
                        if (retry != null && !pageSimilar(retry, homeHtml)) {
                            pages.add(Page("搜索结果页(重试)", listUrl, retry))
                            searchCheck = aiCheckPage(CHECK_SEARCH, retry, listUrl, testKeyword)
                            listHtml = retry
                        } else {
                            searchDisabled = true
                        }
                    }
                } else {
                    searchDisabled = true
                }
            } else {
                onProgress("首页未识别出搜索入口…")
                searchDisabled = true
            }
            var detailUrl = searchCheck?.str("bookUrl")?.takeIf { it.startsWith("http") }
            if (detailUrl == null && listHtml != null) {
                detailUrl = firstBookLink(listHtml, listUrl.ifBlank { seed })
            }

            // 搜索不可用时的降级：用分类页当搜索入口（书源仍可搜索浏览，只是结果不按关键词过滤）
            if (searchDisabled) {
                onProgress("站点搜索不可用，尝试用分类页作为书源入口…")
                firstClassLink(homeHtml, seed)?.let { classUrl ->
                    val classHtml = fetch(classUrl)
                    if (classHtml != null && !pageSimilar(classHtml, homeHtml)) {
                        val catCheck = aiCheckPage(CHECK_CATEGORY, classHtml, classUrl)
                        if (catCheck?.bool("isListPage") == true) {
                            searchTemplate = classUrl
                            listUrl = classUrl
                            listHtml = classHtml
                            searchCheck = catCheck
                            detailUrl = catCheck.str("bookUrl")?.takeIf { it.startsWith("http") }
                                ?: firstBookLink(classHtml, classUrl)
                            pages.add(Page("分类列表页", classUrl, classHtml))
                            meta["搜索降级"] =
                                "站点搜索接口对程序不可用（请求被重定向回首页），" +
                                    "searchUrl 已降级指向分类页 $classUrl，" +
                                    "搜索结果为分类推荐而非按关键词过滤"
                        }
                    }
                }
            }
            meta["搜索质检"] = when {
                searchTemplate == null -> "失败：站点搜索对程序不可用"
                searchCheck?.str("problem")?.isNotBlank() == true ->
                    "疑似问题：" + searchCheck!!.str("problem")
                else -> "通过"
            }

            // ---------- 3) 详情页 ----------
            var detailHtml: String? = null
            var infoCheck: JsonObject? = null
            var detailPageIsToc = false
            val detailUrlFinal = detailUrl
            if (detailUrlFinal != null) {
                onProgress("抓取书籍详情页…")
                detailHtml = fetch(detailUrlFinal)
                if (detailHtml != null) {
                    pages.add(Page("书籍详情页", detailUrlFinal, detailHtml))
                    infoCheck = aiCheckPage(CHECK_INFO, detailHtml, detailUrlFinal)
                    detailPageIsToc = infoCheck?.bool("isAlsoToc") == true
                    meta["书名"] = infoCheck?.str("name").orEmpty()
                    meta["作者"] = infoCheck?.str("author").orEmpty()
                }
            } else {
                meta["详情质检"] = "未获得详情页"
            }
            meta["详情质检"] = infoCheck?.str("problem")?.takeIf { it.isNotBlank() }
                ?: if (detailHtml != null) "通过" else "未抓到"

            // ---------- 4) 目录页（详情/目录合一则直接用详情页） ----------
            var tocHtml: String? = null
            var tocUrl = ""
            var tocCheck: JsonObject? = null
            val declaredTocUrl = infoCheck?.str("tocUrl")?.takeIf {
                it.startsWith("http") && it != detailUrlFinal
            }
            when {
                detailPageIsToc && detailHtml != null -> {
                    onProgress("详情页即为目录页")
                    tocUrl = detailUrlFinal ?: error("详情页地址缺失")
                    tocHtml = detailHtml
                    tocCheck = aiCheckPage(CHECK_TOC, tocHtml, tocUrl)
                }
                declaredTocUrl != null -> {
                    onProgress("抓取目录页…")
                    tocUrl = declaredTocUrl
                    tocHtml = fetch(tocUrl)
                    if (tocHtml != null) {
                        pages.add(Page("目录页", tocUrl, tocHtml))
                        tocCheck = aiCheckPage(CHECK_TOC, tocHtml, tocUrl)
                    }
                }
                else -> onProgress("未找到明确目录页，交给 AI 从已有页面推断")
            }
            meta["目录质检"] = tocCheck?.str("problem")?.takeIf { it.isNotBlank() }
                ?: if (tocHtml != null) "通过" else "未抓到"
            val chapterOrder = tocCheck?.str("order").orEmpty()

            // ---------- 5) 正文页 ----------
            var contentUrl = tocCheck?.str("firstChapterUrl")?.takeIf { it.startsWith("http") }
            if (contentUrl == null && tocHtml != null) {
                contentUrl = firstChapterLink(
                    tocHtml, tocUrl.ifBlank { seed }, desc = chapterOrder == "desc"
                )
            }
            if (contentUrl != null) {
                onProgress("抓取正文页…")
                var contentHtml = fetch(contentUrl)
                // 死链兜底：详情页上的「点击阅读」常是 301 回详情页的死链，
                // 抓回来的页面若与详情页相同，改用目录页里的章节链接
                if (contentHtml != null && detailHtml != null && pageSimilar(contentHtml, detailHtml)) {
                    onProgress("该章节地址无效（返回了详情页），改用目录页中的章节链接…")
                    tocHtml?.let { toc ->
                        val alt = firstChapterLink(
                            toc, tocUrl.ifBlank { seed }, desc = chapterOrder == "desc"
                        )
                        if (alt != null && alt != contentUrl) {
                            fetch(alt)?.let { altHtml ->
                                if (detailHtml == null || !pageSimilar(altHtml, detailHtml)) {
                                    contentUrl = alt
                                    contentHtml = altHtml
                                }
                            }
                        }
                    }
                }
                contentHtml?.let { html ->
                    val url = contentUrl ?: return@let
                    pages.add(Page("正文页", url, html))
                    val c = aiCheckPage(CHECK_CONTENT, html, url)
                    meta["正文质检"] = c?.str("problem")?.takeIf { p -> p.isNotBlank() }
                        ?: if (c?.bool("hasContent") == true) "通过" else "疑似无正文"
                    if (c?.bool("hasContent") != true) {
                        onProgress("正文页异常，用电脑版 UA 重试…")
                        fetch(url, pcUa = true)?.let {
                            pages.add(Page("正文页(重试)", url, it))
                        }
                    }
                }
            }

            // ---------- 阶段二：生成书源 ----------
            onProgress("已采集 ${pages.size} 个页面，正在交给 AI 生成书源规则…")
            var source = askGenerate(pages, seed, searchTemplate, meta)
                ?: error("AI 未返回书源。请确认「角色档案设置」里已配置接口（接口@@模型@@密钥）")
            ensureMobileUa(source)

            // ---------- 阶段三：四链路实测 + 失败修正 ----------
            var fixRounds = 0
            var report = verifySource(source, testKeyword, pages, onProgress)
            while (report.any { !it.ok } && fixRounds < MAX_FIX_ROUNDS) {
                fixRounds++
                onProgress(
                    "验证未通过（${report.filter { !it.ok }.joinToString("、") { it.step }}），" +
                        "正在让 AI 修正（第 $fixRounds 轮）…"
                )
                val fixed = fixSource(source, report, pages) ?: break
                ensureMobileUa(fixed)
                source = fixed
                report = verifySource(source, testKeyword, pages, onProgress)
            }

            if (source.bookSourceName.isBlank()) source.bookSourceName = "AI生成书源"
            if (source.bookSourceGroup.isNullOrBlank()) source.bookSourceGroup = "AI生成"

            GenResult(source, report, fixRounds)
        }
    }

    // ==================== 阶段一：页面抓取 ====================

    private fun normalize(raw: String): String {
        val s = raw.trim()
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            else -> "https://$s"
        }
    }

    private fun fetch(url: String, pcUa: Boolean = false): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (pcUa) PC_UA else MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.put("[AI书源] 抓取失败 HTTP ${response.code}：$url")
                return@use null
            }
            response.body.string().takeIf { it.isNotBlank() }
        }
    }.onFailure { AppLog.put("[AI书源] 抓取异常：${it.message}", it) }.getOrNull()

    /**
     * 按 legado searchUrl 格式抓页面：
     * 纯地址替换 {{key}} 后 GET；`地址,{"method":"POST","body":"..."}` 按 POST 抓。
     */
    private fun fetchPage(template: String, keyword: String): String? {
        val comma = template.indexOf(",{\"")
        if (comma < 0) return fetch(fillKey(template, keyword))
        val url = template.substring(0, comma).trim()
        val option = runCatching {
            JsonParser.parseString(template.substring(comma + 1)).asJsonObject
        }.getOrNull() ?: return fetch(fillKey(url, keyword))
        if (!option.str("method").equals("post", true)) return fetch(fillKey(url, keyword))
        val body = fillKey(option.str("body") ?: "", keyword)
        val charset = option.str("charset")?.takeIf { it.isNotBlank() } ?: "UTF-8"
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", MOBILE_UA)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=$charset")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            postClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.put("[AI书源] 搜索 POST 失败 HTTP ${response.code}：$url")
                    return@use null
                }
                response.body.string().takeIf { it.isNotBlank() }
            }
        }.onFailure { AppLog.put("[AI书源] 搜索 POST 异常：${it.message}", it) }.getOrNull()
    }

    private fun fillKey(template: String, keyword: String): String {
        val key = runCatching { URLEncoder.encode(keyword, "UTF-8") }.getOrDefault(keyword)
        return template.replace("{{key}}", key)
    }

    /** 搜索结果页里最像「书籍详情页」的链接（AI 不可用时的兜底） */
    private fun firstBookLink(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val host = hostOf(baseUrl)
        val bad = listOf(
            "首页", "登录", "注册", "排行", "排行榜", "书架", "下一页", "上一页", "尾页",
            "全部", "更多", "搜索", "返回", "顶部", "底部", "客户端", "APP", "下载",
            "收藏", "书签", "投诉", "帮助", "关于", "联系"
        )
        return doc.select("a[href]").firstOrNull { a ->
            val text = a.text().trim()
            val href = a.attr("abs:href")
            text.length in 2..30 &&
                href.startsWith("http") &&
                (host == null || hostOf(href) == host) &&
                bad.none { text == it } &&
                !href.contains("javascript:", true)
        }?.attr("abs:href")
    }

    /** 目录页里的第一章链接（AI 不可用时的兜底，感知正倒序） */
    private fun firstChapterLink(html: String, baseUrl: String, desc: Boolean): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val chapterPattern = Regex("第\\s*[0-9零一二三四五六七八九十百千万]+\\s*[章节回]")
        val links = doc.select("a[href]")
            .filter { it.attr("abs:href").startsWith("http") }
            .filter { a ->
                val t = a.text().trim()
                t.length in 1..40 && chapterPattern.containsMatchIn(t)
            }
        val target = if (desc) links.lastOrNull() else links.firstOrNull()
        return target?.attr("abs:href")
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()

    // ==================== AI 质检 / 生成 / 修正 ====================

    private fun aiCheckPage(
        prompt: String,
        html: String,
        url: String,
        keyword: String? = null
    ): JsonObject? {
        val user = buildString {
            append(prompt.replace("{url}", url).replace("{keyword}", keyword.orEmpty()))
            append("\n页面 URL：").append(url).append('\n')
            append("```html\n").append(cleanHtml(html)).append("\n```")
        }
        val raw = chat(user, 600) ?: return null
        return parseJsonObject(raw)
    }

    private fun askGenerate(
        pages: List<Page>,
        seedUrl: String,
        searchTemplate: String?,
        meta: Map<String, String>
    ): BookSource? {
        val sb = StringBuilder()
        pages.forEachIndexed { index, page ->
            sb.append("【页面").append(index + 1).append("｜角色：").append(page.role)
                .append("】URL: ").append(page.url).append('\n')
            sb.append("```html\n").append(cleanHtml(page.html)).append("\n```\n\n")
        }
        val metaText = meta.entries.joinToString("\n") { "- ${it.key}：${it.value}" }
        val searchHint = searchTemplate?.takeIf { it.isNotBlank() }
            ?: "（未识别出搜索入口，searchUrl 依据页面推断，不确定留空）"
        val user = USER_PROMPT
            .replace("{seed}", seedUrl)
            .replace("{searchHint}", searchHint)
            .replace("{meta}", metaText)
            .replace("{pages}", sb.toString()) + PATTERN_LIBRARY
        val raw = chat(user, 4000) ?: return null
        val json = extractJson(raw) ?: return null
        return GSON.fromJsonObject<BookSource>(json).getOrNull()
    }

    /** 让 AI 修正验证失败的环节（附失败原因与对应页面的真实 HTML） */
    private fun fixSource(
        source: BookSource,
        report: List<VerifyItem>,
        pages: List<Page>
    ): BookSource? {
        val failed = report.filter { !it.ok }
        val current = runCatching { GSON.toJson(source) }.getOrDefault("")
        val sb = StringBuilder()
        failed.forEach { f ->
            sb.append("【").append(f.step).append("】").append(f.message).append('\n')
            val page = when (f.step) {
                "搜索" -> pages.firstOrNull { it.role.startsWith("搜索") }
                "详情" -> pages.firstOrNull { it.role.startsWith("详情") }
                "目录" -> pages.firstOrNull { it.role.startsWith("目录") }
                    ?: pages.firstOrNull { it.role.startsWith("详情") }
                "正文" -> pages.firstOrNull { it.role.startsWith("正文") }
                    ?: pages.firstOrNull { it.role.startsWith("目录") }
                else -> null
            }
            if (page != null) {
                sb.append("相关页面：").append(page.role).append("  URL: ").append(page.url).append('\n')
                sb.append("```html\n").append(cleanHtml(page.html)).append("\n```\n")
            }
        }
        val user = FIX_PROMPT
            .replace("{fails}", sb.toString())
            .replace("{source}", current) + PATTERN_LIBRARY
        val raw = chat(user, 4000) ?: return null
        val json = extractJson(raw) ?: return null
        return GSON.fromJsonObject<BookSource>(json).getOrNull()
    }

    /**
     * 确保书源带手机浏览器 UA。
     * 书源未配 UA 时 legado 会自动注入系统默认的【PC 版 Chrome UA】，
     * 而很多手机站对 PC UA 的处理是 301 → /404.html，且 /404.html 又 301 → 自己，
     * 造成 OkHttp「Too many follow-up requests: 21」重定向死循环（qianjinge.com 实测）。
     */
    private fun ensureMobileUa(source: BookSource) {
        val map = source.header?.takeIf { it.isNotBlank() }?.let {
            runCatching { GSON.fromJsonObject<Map<String, String>>(it).getOrNull() }.getOrNull()
        }?.toMutableMap() ?: mutableMapOf()
        val hasUa = map.keys.any { it.equals(AppConst.UA_NAME, ignoreCase = true) }
        if (!hasUa) {
            map[AppConst.UA_NAME] = MOBILE_UA
            source.header = GSON.toJson(map)
        }
    }

    // ==================== 阶段三：四链路实测 ====================

    /** 用 App 自带的 WebBook 引擎按真实使用路径验证：验证通过 = 实际可用 */
    private suspend fun verifySource(
        source: BookSource,
        keyword: String,
        pages: List<Page>,
        onProgress: (String) -> Unit
    ): List<VerifyItem> {
        val report = ArrayList<VerifyItem>(4)
        var book: Book? = null

        onProgress("验证 ①/④：搜索…")
        runCatching {
            if (source.searchUrl.isNullOrBlank()) error("searchUrl 为空")
            val books = WebBook.searchBookAwait(source, keyword, 1)
            if (books.isEmpty()) error("搜索无结果")
            val first = books.first()
            book = first.toBook().also { it.origin = source.bookSourceUrl }
            report.add(VerifyItem("搜索", true, "搜到 ${books.size} 本，第一本《${first.name}》"))
        }.onFailure {
            report.add(VerifyItem("搜索", false, it.message ?: "执行异常"))
        }

        onProgress("验证 ②/④：书籍详情…")
        runCatching {
            val probe = book ?: Book(
                name = "验证测试",
                bookUrl = pages.firstOrNull { it.role.startsWith("详情") }?.url
                    ?: pages.firstOrNull { it.role.startsWith("搜索") }?.url
                    ?: source.bookSourceUrl,
                origin = source.bookSourceUrl
            )
            val info = WebBook.getBookInfoAwait(source, probe, canReName = true)
            if (info.name.isBlank()) error("未能解析出书名")
            book = info
            report.add(
                VerifyItem(
                    "详情", true,
                    "书名《${info.name}》" + info.author.takeIf { a -> a.isNotBlank() }
                        ?.let { " 作者：$it" }.orEmpty()
                )
            )
        }.onFailure {
            report.add(VerifyItem("详情", false, it.message ?: "执行异常"))
        }

        onProgress("验证 ③/④：目录…")
        var firstChapter: io.legado.app.data.entities.BookChapter? = null
        runCatching {
            val b = book ?: error("详情验证未通过，无法继续")
            val chapters = WebBook.getChapterListAwait(source, b).getOrNull().orEmpty()
            if (chapters.isEmpty()) error("解析出 0 个章节")
            firstChapter = chapters.firstOrNull { !it.isVolume } ?: chapters.first()
            report.add(VerifyItem("目录", true, "共 ${chapters.size} 章，首章《${firstChapter?.title}》"))
        }.onFailure {
            report.add(VerifyItem("目录", false, it.message ?: "执行异常"))
        }

        onProgress("验证 ④/④：正文…")
        runCatching {
            val b = book ?: error("详情验证未通过，无法继续")
            val chapter = firstChapter ?: error("目录验证未通过，无法继续")
            val content = WebBook.getContentAwait(source, b, chapter, null, needSave = false)
            val plain = Jsoup.parse(content).text().trim()
            if (plain.length < MIN_CONTENT_CHARS) error("正文过短（${plain.length} 字）")
            report.add(VerifyItem("正文", true, "取到正文 ${plain.length} 字"))
        }.onFailure {
            report.add(VerifyItem("正文", false, it.message ?: "执行异常"))
        }
        return report
    }

    // ==================== 通用工具 ====================

    private fun chat(user: String, maxTokens: Int): String? = try {
        CharacterProfileManager.chatForExternal(SYSTEM_PROMPT, user, maxTokens)
    } catch (e: Exception) {
        AppLog.put("[AI书源] 调用 AI 失败：${e.message}", e)
        null
    }

    private fun parseJsonObject(raw: String): JsonObject? {
        val json = extractJson(raw) ?: return null
        return runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
    }

    /** 从 AI 回复里抠出 JSON（模型常带 ```json 包裹或前后解释） */
    private fun extractJson(raw: String): String? {
        val s = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return s.substring(start, end + 1)
    }

    /**
     * 清理 HTML：删除样式/统计脚本，但**保留含跳转/搜索逻辑的脚本**——
     * 很多手机站（如 m.qianjinge.com）的搜索地址只存在于 JS 里
     * （`location.href='/Search/'+关键词`），全部删除会让 AI 推断不出搜索入口。
     */
    private fun cleanHtml(html: String): String {
        var s = html
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
            .replace(Regex("(?is)<!--.*?-->"), "")
        // script 只保留「跳转/搜索/请求」类逻辑，剔除统计与广告
        s = Regex("(?is)<script[^>]*>(.*?)</script>").replace(s) { m ->
            val js = m.groupValues[1].replace(Regex("\\s+"), " ").trim()
            val useful = js.length in 20..1200 &&
                listOf(
                    "location.href", "location.replace", "window.open",
                    "xmlhttp", "ajax", "fetch(", "action="
                ).any { js.contains(it, true) } &&
                listOf("hm.baidu", "google-analytics", "cnzz", "adsbygoogle", "statistic")
                    .none { js.contains(it, true) }
            if (useful) "<script>" + js.take(500) + "</script>" else ""
        }
        val doc = runCatching { Jsoup.parse(s) }.getOrNull() ?: return s.take(MAX_PAGE_CHARS)
        doc.select("noscript,svg,iframe,link,meta,canvas,input[type=hidden]").remove()
        doc.select("*").forEach {
            it.removeAttr("style")
            it.removeAttr("onclick")
            it.removeAttr("onmouseover")
            it.removeAttr("data-track")
        }
        val one = doc.html()
            .replace(Regex(">\\s+<"), "><")
            .replace(Regex("[ \\t]{2,}"), " ")
        return if (one.length > MAX_PAGE_CHARS) {
            one.take(MAX_PAGE_CHARS) + "\n<!-- 内容过长已截断 -->"
        } else {
            one
        }
    }

    /** 两页内容是否基本相同：识别「搜索被 301 回首页」「章节链接是死链指回详情页」这类软失败 */
    private fun pageSimilar(a: String, b: String): Boolean {
        val ka = a.replace(Regex("\\s"), "")
        val kb = b.replace(Regex("\\s"), "")
        if (ka.length < 100 || kb.length < 100) return false
        return ka.take(600) == kb.take(600)
    }

    /** 首页里最像「分类列表页」的链接（站点搜索不可用时的降级入口） */
    private fun firstClassLink(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)
        val host = hostOf(baseUrl)
        val pattern = Regex(
            "/?(?:class|list|sort|category|fenlei|type)[^\"']*",
            RegexOption.IGNORE_CASE
        )
        return doc.select("a[href]").firstOrNull { a ->
            val href = a.attr("abs:href")
            href.startsWith("http") &&
                (host == null || hostOf(href) == host) &&
                pattern.containsMatchIn(href) &&
                a.text().trim().length in 2..12
        }?.attr("abs:href")
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { !it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asBoolean

    // ==================== 提示词 ====================

    private const val SYSTEM_PROMPT =
        "你是「阅读」(Legado) App 的书源规则编写专家，精通其 CSS/XPath/JSONPath/正则/JS 规则语法。" +
            "你严格按要求的格式输出 JSON，不输出任何解释、注释或 Markdown 代码块。"

    private val CHECK_HOME = """
        下面是一个网页的 HTML。请判断它是否是小说网站的有效页面，并找出搜索入口。
        注意：很多手机站的真实搜索地址写在页面的 JavaScript 里（如 location.href='/Search/'+关键词，
        或 /search.php?keyword=），请检查页面中保留的 <script> 内容；表单没有 action 属性时尤其如此。
        常见搜索路径形态（**仅当与页面表单/脚本实际吻合时才采用，页面实际结构优先于模板**）：
        /search.php?keyword={{key}}、/Search/{{key}}、/search?q={{key}}、/search?kw={{key}}、
        /modules/article/search.php?searchkey={{key}}（杰奇CMS）、/e/search/index.php（帝国CMS，POST）、
        /s.php?ie=gbk&q={{key}}、/api/search?q={{key}}。
        不要因为模板存在就套用页面里不存在的路径；页面表单/JS 明确给出的搜索地址永远优先。
        只输出 JSON（不要解释）：
        {"isNovelSite": true或false,
         "searchUrl": "可直接使用的搜索请求地址，关键词位置用 {{key}} 占位，例如 https://www.x.com/Search/{{key}}；若必须 POST 则写成 {\"method\":\"POST\",\"body\":\"参数名={{key}}&其他隐藏参数=值\"}；找不到填 null",
         "suggestKeyword": "从页面热门搜索词或推荐书名里挑一个 2~3 字的中文关键词用于测试，找不到填 null",
         "problem": "不是小说站或缺搜索入口时简述原因，正常则留空"}
    """.trimIndent()

    private val CHECK_SEARCH = """
        这是用关键词「{keyword}」请求后的网页 HTML。请判断是否真的返回了搜索结果。
        只输出 JSON（不要解释）：
        {"isSearchResult": true或false,
         "bookUrl": "搜索结果里第一本小说的详情页完整网址；没有结果填 null",
         "problem": "无效时简述原因（如：无搜索结果/是验证码页/被重定向到首页/是登录页），正常则留空"}
    """.trimIndent()

    private val CHECK_INFO = """
        这是疑似书籍详情页的网页 HTML。请判断它是否包含书籍信息。
        注意：详情页上的「最新章节」区块不是目录页；「点击阅读/开始阅读」链接可能指向第一章甚至失效，
        目录页地址优先选「查看全部章节/章节目录/完整目录」这类链接。
        只输出 JSON（不要解释）：
        {"isBookInfo": true或false,
         "name": "书名，没有填 null",
         "author": "作者，没有填 null",
         "isAlsoToc": true或false,
         "tocUrl": "目录页的完整网址；如果完整章节列表就在本页，填 null 并把 isAlsoToc 设为 true",
         "problem": "异常时简述原因，正常则留空"}
    """.trimIndent()

    private val CHECK_TOC = """
        这是疑似目录页的网页 HTML。请判断它是否包含章节列表。
        注意：要区分详情页的「最新章节」推荐区块——真正的目录页有大量连续章节链接且常带分页。
        只输出 JSON（不要解释）：
        {"hasChapters": true或false,
         "firstChapterUrl": "第一章的完整网址；注意：如果章节列表按新到旧排列，第一章在列表末尾",
         "order": "asc（旧到新）或 desc（新到旧）",
         "nextTocUrl": "目录分页下一页的完整网址，没有填 null",
         "problem": "异常时简述原因（如：页面是详情页/是正文页/需要登录），正常则留空"}
    """.trimIndent()

    private val CHECK_CONTENT = """
        这是疑似正文页的网页 HTML。请判断它是否是小说章节正文页。
        注意：如果页面更像书籍详情页（含书籍简介、加入书架按钮、章节链接列表）而不是正文，
        hasContent 要填 false 并在 problem 里说明。
        正文容器命名因站而异，常见候选（按出现频率）：
        content、htmlContent、nr1、chaptercontent、booktxt、novelcontent、read-content、
        TextContent、article、con、nr、txt、BookText、acontent、readcontent。
        只要页面存在上述之一（或其他明显正文容器）且其中是章节正文，hasContent 就填 true。
        只输出 JSON（不要解释）：
        {"isChapter": true或false,
         "hasContent": true或false,
         "nextContentUrl": "正文分页或下一章的完整网址，没有填 null",
         "problem": "异常时简述原因（如：其实是详情页/是目录页/正文为空/是验证码页），正常则留空"}
    """.trimIndent()

    /**
     * 成熟书源模式库：从 4770 个社区验证书源（aoaostar 全量 3907 + 1629 精品等）统计提炼。
     * 拼接在生成/修正 prompt 之后，让 AI 优先套用已被大量站点验证过的规则形态。
     */
    private val PATTERN_LIBRARY = """


        【成熟书源模式库】（来自 4770 个社区验证书源的统计，规则形态优先按此套用，再按页面实际结构微调）

        一、搜索入口（按站点程序套用；{{key}} 为关键词，{{page}} 为页码）
        · 杰奇CMS（页面含 /modules/article/ 或 /books/数字/ 结构）：
          GET /modules/article/search.php?searchkey={{key}}&searchtype=articlename&page={{page}}
        · 帝国CMS：POST /e/search/index.php，body: show=title&keyboard={{key}}
        · 手机站常见：GET /Search/{{key}}、/search.php?keyword={{key}}、/search?q={{key}}、
          /s?q={{key}}、/search?kw={{key}}、/search?keyword={{key}}、/api/search?q={{key}}、/ar.php?keyWord={{key}}
        · 表单参数名常见：searchkey、keyword、q、s、kw、keyWord、searchname
        · 搜索地址写在 <script> 里（location.href='/Search/'+关键词）时直接采用该地址
        · 结果分页：URL 加 &page={{page}}；需要首页判断时用 {{page==1?首页URL:后续URL}}

        二、搜索结果 bookList 常见写法
        class.bookbox（笔趣阁系）｜id.sitembox@tag.dl｜class.grid@tag.tr!0（!0 排除表头）｜.item｜id.content@ul@tag.li

        三、详情页常见写法
        · 书名：id.info@h1@text##\\（.*|免费阅读|全文.*阅读；或 meta 属性 [property=og:novel:book_name]@content
        · 作者：[property=og:novel:author]@content；简介：id.intro@text 或 [property=og:description]@content
        · 约 7 成站点目录就在详情页（tocUrl 留空）；有独立目录页时优先「查看全部章节/目录」链接
        · 详情页的「最新章节」区块不是目录

        四、目录 chapterList 常见写法
        id.list@tag.dd（杰奇CMS）｜class.listmain@dd｜class.zjlist@tag.dd｜dl@dd!0
        · 列表前几项常是「最新章节」推荐，用 !0:1:2:3 排除，或从完整列表容器取 dd/li
        · 目录分页 nextTocUrl：text.下一页@href 或 class.page@tag.a.-1@href

        五、正文容器候选（按出现频率，取页面实际存在的那个）
        id.content ＞ id.htmlContent ＞ id.nr1 ＞ id.chaptercontent ＞ id.booktxt ＞ id.novelcontent ＞
        id.read-content ＞ id.TextContent ＞ article ＞ id.con ＞ id.nr ＞ id.txt ＞ id.BookText ＞
        id.acontent ＞ id.readcontent ＞ id.cont-body ＞ id.page-content ＞ id.articlecontent ＞
        class.article-content ＞ id.content1 ＞ class.panel-body ＞ class.read-content ＞ class.txt
        · 规则用 @html 保留段落；正文由 <p> 分段时可用 @textNodes
        · 正文分页/翻章 nextContentUrl：text.下一页@href 或 text.下一章@href

        六、正文净化（## 后接竖线分隔的正则；页面有广告注入文本时附加）
        常见广告句式：天才一秒记住.*｜一秒记住.*｜www\\..*com｜请记住本书首发域名.*｜最新章节.*｜笔趣阁
        写法示例：id.content@html##天才一秒记住.*|请记住本书首发域名.*
        · 章节名带卷前缀/求收藏时净化：tag.a@text##正文卷.|正文.|VIP卷.|默认卷.|【求收藏】|（.*）

        七、meta 属性系列（正规站优先用，稳定可靠）
        · 书名 [property=og:novel:book_name]@content　作者 [property=og:novel:author]@content
        · 封面 [property=og:image]@content　最新章 [property=og:novel:latest_chapter_name]@content
        · 简介 [property=og:description]@content　封面也常用 id.fmimg@img@src

        八、searchUrl 选项与杂项
        · GBK 编码站点：URL 加 ,{"charset":"gbk"}（1111 个书源在用）；charset:"auto" 可自动检测
        · 需要执行页面 JS 才有数据时加 {"webView":true}
        · API 型站点（页面是 JSON）用 $ 规则：bookList 用 $..data[*] 等，字段用 $.name
        · 站点拒绝请求时给书源配 header（手机 UA 与 Referer）
        · 不要给 chapterList 主动加反转/排序处理，按页面章节链接的自然顺序写规则即可
    """.trimIndent()

    private val CHECK_CATEGORY = """
        这是一个小说网站分类列表页的 HTML。请确认它是有效的书籍列表页。
        只输出 JSON（不要解释）：
        {"isListPage": true或false,
         "bookUrl": "列表里第一本小说的详情页完整网址；没有填 null",
         "problem": "异常时简述原因，正常则留空"}
    """.trimIndent()

    private val USER_PROMPT = """
        请为下面这个小说网站编写一份完整、可直接导入「阅读」(Legado) App 使用的书源 JSON。
        这些页面均已核验可用，采集时确认了以下信息，规则必须与其保持一致：

        起始网址：{seed}
        搜索信息：{searchHint}
        采集确认：{meta}

        {pages}

        【输出要求】
        只输出**一个 JSON 对象**（不要数组、不要代码块、不要解释），字段如下：
        {"bookSourceUrl": "站点根地址",
         "bookSourceName": "站点名称",
         "bookSourceGroup": "AI生成",
         "bookSourceType": 0,
         "bookUrlPattern": "详情页 URL 正则，不确定留空",
         "searchUrl": "搜索地址，关键词用 {{key}}，翻页用 {{page}}",
         "ruleSearch": {"bookList": "搜索结果列表选择器", "name": "书名规则", "author": "作者规则",
           "kind": "分类规则，不确定留空", "lastChapter": "", "intro": "", "coverUrl": "",
           "bookUrl": "详情页链接规则"},
         "ruleBookInfo": {"name": "书名规则", "author": "作者规则", "kind": "", "lastChapter": "",
           "intro": "", "coverUrl": "", "tocUrl": "目录页链接规则", "wordCount": ""},
         "ruleToc": {"chapterList": "章节列表选择器", "chapterName": "章节名规则",
           "chapterUrl": "章节链接规则", "nextTocUrl": "目录翻页规则，没有留空"},
         "ruleContent": {"content": "正文规则", "nextContentUrl": "正文翻页规则，没有留空",
           "replaceRegex": "广告替换，形式 正则##替换##，只写页面里见过的广告，没有留空"}}

        【规则语法要点】
        1. 默认 CSS 选择器（Jsoup），如 `class.book-list@tag.li`、`id.content`；
        2. 取属性用 `@href` / `@src` / `@title` / `@data-src`，取文本用 `@text` / `@textNodes`；
        3. 列表规则写成「容器选择器@条目选择器」；
        4. 不确定的字段**留空字符串**，不要编造。

        【硬性规则】
        1. 只依据给到的页面内容推断；
        2. 正文容器的命名因站而异（可能是 id.nr、id.content、class.read-content、article 等），以页面实际结构为准，不要套用固定命名；
        3. 章节列表排除「最新章节」推荐区，取完整章节列表容器；
        4. 采集确认里的书名/作者/第一章网址必须与规则解析结果吻合。
    """.trimIndent()

    private val FIX_PROMPT = """
        下面这份书源在实际验证中有环节失败。请根据失败原因与对应页面的真实 HTML，
        找出规则问题并修正，输出**完整**的修正后书源 JSON（不要解释、不要代码块）。

        【提示】若失败原因是「Too many follow-up requests」或 30x 重定向循环，
        多半不是规则问题，而是请求被站点反爬——保留书源现有的 header（User-Agent）配置即可，不要删除。

        【验证失败环节】
        {fails}

        【当前书源 JSON】
        {source}
    """.trimIndent()
}
