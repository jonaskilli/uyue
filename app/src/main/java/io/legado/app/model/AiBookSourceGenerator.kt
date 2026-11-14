package io.legado.app.model

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.video.VideoSourcePlayer
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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

    /** 用户没填「测试关键词」、且 AI 也没给出建议词时的兜底词 */
    private const val DEFAULT_KEYWORD = "剑"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        // 强制 HTTP/1.1：部分站点服务器 h2 流实现有 Bug（崩流 INTERNAL_ERROR），浏览器会自动降级而 OkHttp 不会
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    private val postClient = client.newBuilder()
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    /** 全局栈降级客户端：带 Cronet 拦截器（与浏览器同源），同样强制 HTTP/1.1 */
    private val globalClient = okHttpClient.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /**
     * DoH（加密 DNS）客户端：浏览器有"安全 DNS"可绕过运营商 DNS 污染，
     * 普通 OkHttp 走系统 DNS 被污染时会出现"浏览器能开、App 无法访问"（Cloudflare 站常见）。
     * 用阿里 DoH 为主、腾讯 DoH 为备，JSON API（RFC8484）零额外依赖；
     * 两个 DoH 服务域名本身在国内不污染，bootstrap 走系统 DNS 即可。
     */
    private val dohClients: List<OkHttpClient> by lazy {
        listOf(
            "https://dns.alidns.cn/resolve",
            "https://doh.pub/dns-query"
        ).map { base ->
            client.newBuilder().dns(Dns { host ->
                val ips = queryDoh("$base?name=$host&type=A")
                    ?: throw UnknownHostException("DoH 解析失败: $host")
                ips.map { InetAddress.getByName(it) }
            }).build()
        }
    }

    /** DoH JSON 查询，返回 A 记录 IP 列表；失败返回 null 触发下一个 DoH 服务 */
    private fun queryDoh(url: String): List<String>? = runCatching {
        val req = Request.Builder().url(url).header("Accept", "application/dns-json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val obj = JsonParser.parseString(r.body.string()).asJsonObject
            if (obj.get("Status")?.asInt != 0) return@use null
            obj.getAsJsonArray("Answer")
                ?.mapNotNull { runCatching { it.asJsonObject.get("data").asString }.getOrNull() }
                ?.filter { it.matches(Regex("([0-9]{1,3}\\.){3}[0-9]{1,3}")) }
                ?.takeIf { it.isNotEmpty() }
        }
    }.onFailure { AppLog.put("[AI书源] DoH 查询异常：${it.message}") }.getOrNull()

    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 单页送给 AI 的最大字符数 */
    private const val MAX_PAGE_CHARS = 12000

    /** 验证失败后允许 AI 修正的轮数 */
    private const val MAX_FIX_ROUNDS = 2

    private const val MIN_CONTENT_CHARS = 50

    /** 一个抓到并质检过的页面 */
    private data class Page(val role: String, val url: String, val html: String)

    /** 目录页候选：来源决定抓取优先级，html 已抓到时复用 */
    private data class TocCandidate(val url: String, val html: String?, val source: String)

    /**
     * 验证报告条目。
     * blocking=false 的项（如封面）只把结果展示给用户，不参与 AI 修正、兜底与整体失败判定，
     * 避免「站点没有封面」这种可接受的缺失把整份书源判成生成失败。
     */
    data class VerifyItem(
        val step: String,
        val ok: Boolean,
        val message: String,
        val blocking: Boolean = true
    )

    /**
     * 跨多次实跑复用的「当前书源解析出的书 / 首章」。
     * 增量复验时只重验失败环节，依赖的 book / firstChapter 由上轮成功的实跑结果提供，
     * 避免为了复验一个失败项而把已通过的搜索 / 详情环节重新抓一遍。
     */
    class VerifyHolder {
        var book: Book? = null
        var chapter: io.legado.app.data.entities.BookChapter? = null
    }

    /** 生成结果：书源 + 四链路验证报告 + 修正轮数 + 由历史记录补齐并通过的环节 */
    data class GenResult(
        val source: BookSource,
        val verify: List<VerifyItem>,
        val fixRounds: Int,
        val patchedSteps: List<String> = emptyList()
    )

    // ==================== 对外入口 ====================

    suspend fun generate(
        seedUrl: String,
        keyword: String? = null,
        loginUrl: String? = null,
        includeExplore: Boolean = false,
        fixRounds: Int = 2,
        manualBookInfo: String? = null,
        manualTocUrl: String? = null,
        manualContentUrl: String? = null,
        singleBook: Boolean = false,
        codeAssist: Boolean = false,
        videoMode: Boolean = false,
        onProgress: (String) -> Unit
    ): Result<GenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val seed = normalize(seedUrl)
            AppLog.put(
                "[AI书源] generate 开始：manualBookInfo=${manualBookInfo?.take(120)}, " +
                "manualTocUrl=${manualTocUrl?.take(120)}, " +
                "manualContentUrl=${manualContentUrl?.take(120)}, keyword=$keyword"
            )
            // 手动指定的页面：用户填了就优先使用，不再靠程序猜
            val manualInfo = manualBookInfo?.trim()?.takeIf { it.isNotBlank() }?.let { normalize(it) }
            // 目录框里可以填网址，也可以直接填目录入口的链接文字（如「查看全部章节」「......」）
            val manualTocRaw = manualTocUrl?.trim()?.takeIf { it.isNotBlank() }
            val manualToc = manualTocRaw?.takeIf { looksLikeUrl(it) }?.let { normalize(it) }
            val manualTocText = manualTocRaw?.takeUnless { looksLikeUrl(it) }
            // 正文框只收网址：填了就直接用这一章的页面，不再从目录里猜第一章
            val manualContent = manualContentUrl?.trim()
                ?.takeIf { it.isNotBlank() && looksLikeUrl(it) }
                ?.let { normalize(it) }
            val meta = HashMap<String, String>()
            if (manualContent != null) {
                meta["正文页(手动指定)"] = "用户手动指定了章节正文页：$manualContent；" +
                    "正文规则请以该页面源码为准，不要再猜测章节地址。"
            }
            // 用户同时填了「书籍详情页」与「目录页」两个网址：调一次 AI 推导二者的地址规律，
            // 得到一个能把详情页地址换成目录页地址的正则兜底规则（作用于 baseUrl）。
            // 仅当两个网址都给了且不同才调用，避免无谓的请求；推导不出则返回 null。
            AppLog.put("[AI书源] 目录正则推导判断：manualInfo=$manualInfo | manualToc=$manualToc")
            val aiTocRegex = if (manualInfo != null && manualToc != null) {
                askTocUrlRegex(manualInfo, manualToc, onProgress)?.also { rx ->
                    meta["目录地址正则推导(AI)"] = buildString {
                        append("用户同时提供了书籍详情页与目录页网址，已用 AI 推导出二者的地址规律：")
                        append("\n  详情页：").append(manualInfo)
                        append("\n  目录页：").append(manualToc)
                        append("\n  可直接套在书籍详情页自身地址(baseUrl)上的正则兜底规则")
                        append("（ruleBookInfo.tocUrl 取不到目录入口链接时使用）：")
                        append("\n  ").append(rx)
                        append("\n  建议 ruleBookInfo.tocUrl 优先从页面提取目录入口链接；")
                        append("若页面没有可靠入口链接，直接采用上面的 baseUrl 正则兜底写法")
                        append("（不要写死某一本书的地址，否则所有书会打开同一份目录）。")
                    }
                }
            } else {
                AppLog.put(
                    "[AI书源] 跳过目录地址正则 AI 推导：需同时在「书籍详情页网址」与「目录页网址」两框填真实网址" +
                    "（当前 manualInfo=$manualInfo, manualToc=$manualToc）"
                )
                null
            }
            val pages = ArrayList<Page>(6)
            if (!loginUrl.isNullOrBlank()) {
                val cookie = CookieStore.getCookie(seed)
                meta["登录"] = if (cookie.isNotBlank()) {
                    "用户已登录该站点，采集页面为登录后状态（书源将写入登录地址）"
                } else {
                    "站点需要登录但未检测到登录状态，建议先点「去登录」完成登录再生成"
                }
            }

            // ---------- 1) 首页 ----------
            // 单本模式不需要首页：没有搜索也没有发现，只抓这一本书自己的页面
            var homeHtml = ""
            var homeCheck: JsonObject? = null
            var searchTemplate: String? = null
            // 用户填的关键词优先级最高：填了就一定用它去搜，AI 建议词不再反过来覆盖用户输入；
            // 只有用户留空时，才采用 AI 从页面热门词里挑的那个。
            val userKeyword = keyword?.trim()?.takeIf { it.isNotBlank() }
            var aiKeyword: String? = null
            var testKeyword = userKeyword ?: DEFAULT_KEYWORD
            if (!singleBook) {
                onProgress("抓取首页…")
                homeHtml = fetch(seed) ?: error("无法访问该地址：$seed")
                pages.add(Page("首页", seed, homeHtml))
                homeCheck = aiCheckPage(CHECK_HOME, homeHtml, seed)
                searchTemplate = homeCheck?.str("searchUrl")
                aiKeyword = homeCheck?.str("suggestKeyword")?.trim()?.takeIf { it.isNotBlank() }
                if (userKeyword == null && aiKeyword != null) {
                    testKeyword = aiKeyword
                    onProgress("未填测试关键词，自动采用站点热词「$aiKeyword」…")
                }
                AppLog.put(
                    "[AI书源] 测试关键词定为「$testKeyword」" +
                        "（用户输入=${userKeyword ?: "空"}，AI建议=${aiKeyword ?: "空"}）"
                )
                if (homeCheck?.bool("isNovelSite") == false) {
                    onProgress("首页疑似不是小说站，用电脑版 UA 重试…")
                    homeHtml = fetch(seed, pcUa = true) ?: homeHtml
                    homeCheck = aiCheckPage(CHECK_HOME, homeHtml, seed)
                    searchTemplate = homeCheck?.str("searchUrl") ?: searchTemplate
                }
                meta["首页质检"] = homeCheck?.str("problem")?.takeIf { it.isNotBlank() } ?: "通过"
            }

            // ---------- 2) 搜索页 ----------
            var listHtml: String? = null
            var listUrl = ""
            var searchCheck: JsonObject? = null
            var searchDisabled = false
            if (singleBook) {
                onProgress("单本模式：跳过搜索与发现，只抓这本书的信息页、目录页、正文页…")
            } else if (!searchTemplate.isNullOrBlank()) {
                onProgress("搜索关键词「$testKeyword」…")
                listUrl = absUrl(fillKey(searchTemplate, testKeyword), seed)
                listHtml = fetchPage(searchTemplate, testKeyword, seed)
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
                val listHtml0 = listHtml
                if (listHtml0 != null && countBookLinks(listHtml0, listUrl) < 2) {
                    // AI 给的搜索地址数不出书籍链接：主动用首页表单推导的候选（POST 优先）再竞速一次。
                    // 社区书源里 30% 的搜索是 POST（1476 站），AI 常误写成 GET，这一步是必须的。
                    var baseCount = countBookLinks(listHtml0, listUrl)
                    bestSearchCandidate(homeHtml, seed, testKeyword)?.let { (tpl, html) ->
                        val u = absUrl(fillKey(tpl.substringBefore(",{\""), testKeyword), seed)
                        val n = countBookLinks(html, u)
                        if (n > baseCount) {
                            baseCount = n
                            searchTemplate = tpl
                            listUrl = u
                            listHtml = html
                            pages.add(Page("搜索结果页(竞速替换)", u, html))
                            // detailUrl 在下面几行统一从 searchCheck 里取，这里只更新 searchCheck
                            searchCheck = aiCheckPage(CHECK_SEARCH, html, u, testKeyword)
                        }
                    }
                    if (baseCount < 2) {
                        meta["搜索结果复核"] = "搜索页里没能数出指向书籍详情页的链接，" +
                            "请核对 searchUrl 是否真能搜到书（{{key}} 位置、参数名、隐藏字段、" +
                            "以及该站搜索是否需要 POST）。"
                    }
                }
            } else {
                onProgress("首页未识别出搜索入口…")
                searchDisabled = true
            }
            // ===== 原搜索逻辑判定不可用后的兜底：改按站内搜索表单推导地址 =====
            if (searchDisabled) {
                onProgress("搜索入口不可用，改从站内搜索表单推导…")
                bestSearchCandidate(homeHtml, seed, testKeyword)?.let { (tpl, html) ->
                    val url = absUrl(fillKey(tpl.substringBefore(",{\""), testKeyword), seed)
                    pages.add(Page("搜索结果页(表单推导)", url, html))
                    searchTemplate = tpl
                    listUrl = url
                    listHtml = html
                    searchCheck = aiCheckPage(CHECK_SEARCH, html, url, testKeyword)
                    searchDisabled = false
                }
            }
            // ===== 用户自定义关键词确实搜不到书时的兜底 =====
            // 用户填的词是首选；只有当它一个书籍链接都数不出来时，才换 AI 建议的站点热词重试一次，
            // 并且把换词这件事明确提示出来，不再静默替换用户输入。
            if (!singleBook && userKeyword != null) {
                val tpl = searchTemplate
                val altKeyword = aiKeyword
                if (!tpl.isNullOrBlank() && altKeyword != null && altKeyword != userKeyword) {
                    val curCount = listHtml?.let { countBookLinks(it, listUrl) } ?: 0
                    if (curCount < 2) {
                        onProgress("关键词「$userKeyword」未搜到书，改用站点热词「$altKeyword」重试…")
                        val url2 = absUrl(fillKey(tpl.substringBefore(",{\""), altKeyword), seed)
                        val html2 = fetchPage(tpl, altKeyword, seed)
                        if (html2 != null && countBookLinks(html2, url2) > curCount) {
                            listUrl = url2
                            listHtml = html2
                            testKeyword = altKeyword
                            pages.add(Page("搜索结果页(热词重试)", url2, html2))
                            searchCheck = aiCheckPage(CHECK_SEARCH, html2, url2, altKeyword)
                            searchDisabled = false
                        }
                    }
                }
            }
            var detailUrl = searchCheck?.str("bookUrl")?.takeIf { it.startsWith("http") }
            val listHtml1 = listHtml
            if (detailUrl == null && listHtml1 != null) {
                detailUrl = firstBookLink(listHtml1, listUrl.ifBlank { seed })
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
            // JSON 搜索接口（API 型站点，458 站）：把纯代码算出的 JSONPath 直接告诉 AI
            listHtml?.let { h ->
                jsonBookCandidates(h)?.let { c ->
                    meta["搜索结果(JSON)"] = "搜索接口返回的是 JSON，bookList 可直接用 ${c.list}" +
                        "（也可写 @JSon:${c.list}）；name=${c.name}；author=${c.author}；bookUrl=${c.url}"
                }
            }
            // 搜索结果分页（22% 的书源带 {{page}}）：提醒 AI 在 searchUrl 里留分页位
            listHtml?.let { h ->
                detectTocPagers(h, listUrl).firstOrNull()?.let { c ->
                    meta["搜索分页"] = "搜索结果有分页（${c.desc}），searchUrl 请用 {{page}} 占位，" +
                        "否则只能拿到第一页的搜索结果。"
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
            // 手动指定的书籍信息页优先，不再依赖搜索结果里猜出来的详情页
            val detailUrlFinal = manualInfo ?: detailUrl
            if (detailUrlFinal != null) {
                onProgress(if (manualInfo != null) "抓取书籍信息页（手动指定）…" else "抓取书籍详情页…")
                detailHtml = fetch(detailUrlFinal)
                if (detailHtml != null) {
                    pages.add(Page("书籍详情页", detailUrlFinal, detailHtml))
                    if (singleBook) {
                        // 单本模式：页面是用户手动指定的，可信，跳过 AI 质检省时间；
                        // 目录是否就在信息页里用纯代码判断（章节链接够多即是）
                        detailPageIsToc = countChapterLinks(detailHtml) >= 8
                    } else {
                        infoCheck = aiCheckPage(CHECK_INFO, detailHtml, detailUrlFinal)
                        detailPageIsToc = infoCheck?.bool("isAlsoToc") == true
                        meta["书名"] = infoCheck?.str("name").orEmpty()
                        meta["作者"] = infoCheck?.str("author").orEmpty()
                    }
                }
            } else {
                meta["详情质检"] = "未获得详情页"
            }
            meta["详情质检"] = if (singleBook) {
                if (detailHtml != null) "单本模式：页面为手动指定，已跳过 AI 质检" else "未抓到"
            } else {
                infoCheck?.str("problem")?.takeIf { it.isNotBlank() }
                    ?: if (detailHtml != null) "通过" else "未抓到"
            }


            // ---------- 4) 目录页 ----------
            // 手动指定优先：用户填了目录页网址就直接用，并在书籍信息页源码里把这个入口挖出来；
            // 没填才走「多候选竞速，取章节链接最多的当目录」。
            var tocHtml: String? = null
            var tocUrl = ""
            var tocCheck: JsonObject? = null
            var tocPagerCands: List<PagerCand> = emptyList()
            if (manualToc != null) {
                onProgress("抓取目录页（手动指定）…")
                val html = fetch(manualToc)
                // 校验：手动填的地址可能其实是正文页/错误页（页面里没有章节链接），
                // 这时不采信，回退到自动定位目录——单本模式尤其需要这个容错
                val manualCount = html?.let { countChapterLinks(it) } ?: 0
                val detailCount = detailHtml?.let { countChapterLinks(it) } ?: 0
                if (html != null && manualCount == 0 && detailCount > 0) {
                    onProgress("你填的目录页里没有章节链接（可能填成了正文页），改为自动定位目录…")
                } else if (html != null) {
                    tocUrl = manualToc
                    tocHtml = html
                    if (pages.none { it.url == manualToc }) {
                        pages.add(Page("目录页", manualToc, html))
                    }
                    meta["目录选择"] = "用户手动指定：$manualToc"
                    // 单本模式：手动指定的目录页可信，跳过 AI 质检
                    tocCheck = if (!singleBook) aiCheckPage(CHECK_TOC, html, manualToc) else null
                    // 目录规则的难点是「书籍信息页里怎么拿到这个地址」，直接把入口位置交给 AI
                    val where = detailHtml?.let {
                        locateTocAnchor(it, detailUrlFinal ?: seed, manualToc)
                    } ?: "书籍信息页未抓取到，无法定位目录入口"
                    meta["目录入口定位"] = where
                    onProgress(where.substringBefore('\n').take(100))
                } else {
                    // 目录页与书籍信息页是同一地址（只多个页内锚点）时，请求到的本来就是同一份 HTML，
                    // 直接复用已抓到的信息页内容，不必回退重找
                    if (detailHtml != null && urlKey(manualToc) == urlKey(detailUrlFinal ?: seed)) {
                        tocUrl = manualToc
                        tocHtml = detailHtml
                        meta["目录选择"] = "用户手动指定：$manualToc（与书籍信息页同一地址，复用已抓内容）"
                        tocCheck = if (!singleBook) aiCheckPage(CHECK_TOC, detailHtml, manualToc) else null
                        meta["目录入口定位"] = "目录页与书籍信息页是同一个地址（仅页内锚点不同），" +
                            "页面上未必有指向它的入口链接，ruleBookInfo.tocUrl 请用「目录地址推导」的拼接写法"
                        onProgress("手动目录页与书籍信息页同址，复用已抓内容…")
                    } else {
                        onProgress("手动指定的目录页抓取失败，回退自动查找…")
                    }
                }
            }
            if (tocHtml == null) {
                if (manualTocText != null) {
                    // 用户填的是入口文字而不是网址：按这段文字在书籍信息页里找对应链接
                    onProgress("按你指定的入口「$manualTocText」在书籍信息页里找目录链接…")
                    meta["目录入口指定"] =
                        "用户指定：书籍信息页里文字为「$manualTocText」的链接就是目录入口，" +
                            "ruleBookInfo.tocUrl 必须能取到这个链接"
                }
                // 多候选竞速，取章节链接最多的当目录
                // 很多站详情页只挂一部分章节（最新章节区块/前几章），另有「全部目录」独立页。
                // 把详情页本身、AI 给的目录地址、详情页里的目录文字链接全部作为候选，
                // 逐个抓取并统计章节链接数，数量最多的才是真正目录（纯代码计数，不耗 AI）。
                val tocCandidates = ArrayList<TocCandidate>()
                if (detailPageIsToc && detailHtml != null && detailUrlFinal != null) {
                    tocCandidates.add(TocCandidate(detailUrlFinal, detailHtml, "详情页"))
                }
                infoCheck?.str("tocUrl")?.takeIf {
                    it.startsWith("http") && it != detailUrlFinal
                }?.let { tocCandidates.add(TocCandidate(it, null, "AI目录链接")) }
                // heuristicTocLinks 的排序保证：用户指定的入口文字命中的链接排在最前
                var preferredUrl: String? = null
                detailHtml?.let { dh ->
                    val du = detailUrlFinal ?: seed
                    val extra = manualTocText?.let { listOf(it) } ?: emptyList()
                    val links = heuristicTocLinks(dh, du, extra)
                    if (manualTocText != null) preferredUrl = links.firstOrNull()
                    links.forEach { u ->
                        if (u != detailUrlFinal && tocCandidates.none { it.url == u }) {
                            tocCandidates.add(TocCandidate(u, null, "页面目录链接"))
                        }
                    }
                }
                if (manualTocText != null && preferredUrl == null) {
                    onProgress("书籍信息页里没找到文字为「$manualTocText」的链接，改为自动查找…")
                }
                if (tocCandidates.isNotEmpty()) {
                    var best: TocCandidate? = null
                    var bestCount = -1
                    // 用户指定了入口文字：这个链接抓得到且里面有章节链接就直接采用，不再参与竞速
                    val pref = preferredUrl
                    if (pref != null) {
                        fetch(pref)?.let { html ->
                            val count = countChapterLinks(html)
                            onProgress("你指定的入口「$manualTocText」→ $pref（章节链接 $count 个）")
                            if (count > 0) {
                                bestCount = count
                                best = TocCandidate(pref, html, "用户指定入口")
                            }
                        }
                    }
                    if (best == null) {
                        // 上限放宽到 12 个：详情页上的目录文字链接、省略号入口、用户自定义入口
                        // 都可能排在后面，名额太少会把真正的目录页截断掉
                        for (cand in tocCandidates.take(12)) {
                            val html = cand.html ?: fetch(cand.url) ?: continue
                            val count = countChapterLinks(html)
                            onProgress("目录候选「${cand.source}」章节链接 $count 个")
                            if (count > bestCount) {
                                bestCount = count
                                best = TocCandidate(cand.url, html, cand.source)
                            }
                        }
                    }
                    best?.let { b ->
                        tocUrl = b.url
                        tocHtml = b.html
                        if (b.source != "详情页" && pages.none { it.url == b.url }) {
                            pages.add(Page("目录页", b.url, b.html ?: ""))
                        }
                        meta["目录选择"] =
                            "候选 ${tocCandidates.size} 个，选中「${b.source}」（${bestCount} 个章节链接）"
                        tocCheck = if (!singleBook) aiCheckPage(CHECK_TOC, b.html ?: "", b.url) else null
                    }
                } else {
                    onProgress("未找到明确目录页，交给 AI 从已有页面推断")
                }
            }
            // ===== 以下为原逻辑（手动指定 / 多候选竞速）都没找出目录页时的兜底 =====
            val infoHtml = detailHtml
            if (tocHtml == null && infoHtml != null && countChapterLinks(infoHtml) >= 8) {
                val du = detailUrlFinal ?: seed
                tocUrl = du
                tocHtml = infoHtml
                tocCheck = aiCheckPage(CHECK_TOC, infoHtml, du)
                meta["目录选择"] =
                    "目录候选均未命中，改用书籍信息页本身当目录页" +
                        "（页内 ${countChapterLinks(infoHtml)} 个章节链接）"
                onProgress("目录候选均未命中，改用书籍信息页当目录页…")
            }
            // 目录页确定后再补提示：目录就是信息页 → tocUrl 留空；并给出章节区块结构
            val tocHtml0 = tocHtml
            if (tocHtml0 != null) {
                // 翻页全形态探测：文本 / 下拉选页 / 数字页码 / JS 拼页，并给出 || 组合规则
                val pagerCands = detectTocPagers(tocHtml0, tocUrl.ifBlank { seed })
                if (pagerCands.isNotEmpty()) {
                    tocPagerCands = pagerCands
                    meta["目录分页"] = buildString {
                        append("【目录分页】这个目录是分页的：当前页只列出一部分章节，")
                        append("ruleToc.nextTocUrl 必须填写，否则书源只能拿到第一页的章节。\n")
                        append("检测到的翻页形态（可直接采用，首选第一条）：\n")
                        pagerCands.take(3).forEach {
                            append("  · ").append(it.desc).append(" → ")
                                .append(it.rule.take(140)).append('\n')
                        }
                        append("推荐写法：").append(bestPagerRule(pagerCands).orEmpty().take(160))
                    }
                }
                // JSON 目录页：直接给出可用的 JSONPath 章节列表
                jsonChapterCandidates(tocHtml0).take(2).let { list ->
                    if (list.isNotEmpty()) {
                        meta["章节列表候选(JSON)"] =
                            "目录页返回的是 JSON，chapterList 可直接用：" + list.joinToString(" 或 ")
                    }
                }
                // 目录入口全形态候选：写 ruleBookInfo.tocUrl 时照这里选
                val dh = detailHtml
                if (dh != null) {
                    detectTocEntries(dh, detailUrlFinal ?: seed).take(4).let { entries ->
                        if (entries.isNotEmpty()) {
                            meta["目录入口候选"] = entries.joinToString("；") { "${it.rule}（${it.desc}）" }
                        }
                    }
                }
                if (detailUrlFinal != null && cleanUrl(tocUrl) == cleanUrl(detailUrlFinal)) {
                    meta["书籍信息页即目录"] =
                        "本次采用的目录页就是书籍信息页本身（本书没有独立目录页）：" +
                            "ruleBookInfo.tocUrl 建议留空（留空 = 直接用书籍信息页当目录页），" +
                            "不要把「开始阅读」那种指向第一章正文的链接填进 tocUrl" +
                            "（那会让目录页变成第一章正文，章节列表直接为空）。"
                }
                describeChapterBlocks(tocHtml0, tocUrl.ifBlank { seed })
                    ?.let { meta["章节区块"] = it }
            }
            meta["目录质检"] = if (singleBook) {
                if (tocHtml != null) "单本模式：目录页为手动指定，已跳过 AI 质检" else "未抓到"
            } else {
                tocCheck?.str("problem")?.takeIf { it.isNotBlank() }
                    ?: if (tocHtml != null) "通过" else "未抓到"
            }
            // AI 在目录质检里给出的下一页地址：原先只取了 problem，翻页信息被丢掉了，
            // 于是目录一分页，生成的书源就只有第一页的章节
            tocCheck?.str("nextTocUrl")?.takeIf { it.startsWith("http") }?.let { next ->
                if (meta["目录分页"] == null) {
                    meta["目录分页"] =
                        "该目录是分页的，下一页为 $next；ruleToc.nextTocUrl 必须填写" +
                            "（如 text.下一页@href），否则书源只能拿到第一页的章节。"
                }
            }
            // 目录页地址能不能按书籍编号从详情页地址推导出来——能推导就给 AI 一条可直接抄的规则，
            // 这比「详情页里那个入口链接的选择器」可靠得多（那个链接常常没有 class/id）
            if (tocUrl.isNotBlank() && detailUrlFinal != null) {
                deriveTocUrlRule(detailUrlFinal, tocUrl)?.let { meta["目录地址推导"] = it }
            }
            // 在书籍信息页里找不到指向目录页的入口链接时，明确让 AI 走拼接兜底（不要编造选择器）
            val locate = meta["目录入口定位"]
            if (locate != null && locate.contains("没有直接指向") && meta["目录地址推导"] != null) {
                meta["目录入口定位"] = locate +
                    "\n已在书籍信息页里确认找不到指向该目录页的链接，" +
                    "请直接采用「目录地址推导」里的拼接写法，不要编造选择器。"
            }
            val chapterOrder = tocCheck?.str("order").orEmpty()

            // ---------- 5) 正文页 ----------
            // 手动指定了正文页就直接用它，跳过「AI 质检猜第一章 / 目录页取第一章链接」
            var contentUrl = manualContent
                ?: tocCheck?.str("firstChapterUrl")?.takeIf { it.startsWith("http") }
            val tocHtmlFinal = tocHtml
            if (contentUrl == null && tocHtmlFinal != null) {
                contentUrl = firstChapterLink(
                    tocHtmlFinal, tocUrl.ifBlank { seed }, desc = chapterOrder == "desc"
                )
            }
            if (contentUrl != null) {
                onProgress(
                    if (manualContent != null) "抓取正文页（手动指定）…"
                    else if (videoMode) "抓取播放页…" else "抓取正文页…"
                )
                var contentHtml = fetch(contentUrl)
                // 死链兜底：详情页上的「点击阅读」常是 301 回详情页的死链，
                // 抓回来的页面若与详情页相同，改用目录页里的章节链接。
                // 手动指定的正文页不参与该兜底，始终以用户给的页面为准。
                if (manualContent == null && contentHtml != null && detailHtml != null &&
                    pageSimilar(contentHtml, detailHtml)
                ) {
                    onProgress("该章节地址无效（返回了详情页），改用目录页中的章节链接…")
                    tocHtmlFinal?.let { toc ->
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
                    val contentRole = if (videoMode) "播放页" else "正文页"
                    pages.add(Page(contentRole, url, html))
                    if (singleBook) {
                        // 单本模式：跳过 AI 质检，正文规则是否可用由后面的实测验证
                        meta[if (videoMode) "播放质检" else "正文质检"] =
                            "单本模式：已跳过 AI 质检"
                    } else {
                        val c = if (videoMode) aiCheckPage(CHECK_PLAY_PAGE, html, url)
                        else aiCheckPage(CHECK_CONTENT, html, url)
                        val pageOk = if (videoMode) c?.bool("hasPlayUrl") == true
                        else c?.bool("hasContent") == true
                        meta[if (videoMode) "播放质检" else "正文质检"] = c?.str("problem")
                            ?.takeIf { p -> p.isNotBlank() }
                            ?: if (pageOk) "通过"
                            else if (videoMode) "页面未发现可播直链" else "疑似无正文"
                        if (!pageOk) {
                            onProgress(if (videoMode) "播放页异常，用电脑版 UA 重试…" else "正文页异常，用电脑版 UA 重试…")
                            fetch(url, pcUa = true)?.let {
                                pages.add(Page("${contentRole}(重试)", url, it))
                            }
                        }
                    }
                }
            }

            // ---------- 6) 分类列表页（发现页用，勾选了才采集） ----------
            if (!singleBook && includeExplore && pages.none { it.role.startsWith("分类列表页") }) {
                val classUrl = homeCheck?.str("firstCategoryUrl")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { fillKey(it, testKeyword).replace("{{page}}", "1") }
                    ?.takeIf { it.startsWith("http") }
                    ?: firstClassLink(homeHtml, seed)
                if (classUrl != null) {
                    onProgress("抓取分类列表页…")
                    fetch(classUrl)?.let { clsHtml ->
                        if (!pageSimilar(clsHtml, homeHtml)) {
                            pages.add(Page("分类列表页", classUrl, clsHtml))
                            val catCheck = aiCheckPage(CHECK_CATEGORY, clsHtml, classUrl)
                            meta["发现页"] = if (catCheck?.bool("isListPage") == true) {
                                "分类列表页可用：$classUrl"
                            } else {
                                catCheck?.str("problem")?.takeIf { it.isNotBlank() } ?: "分类列表页疑似异常"
                            }
                        }
                    }
                } else {
                    meta["发现页"] = "未识别出分类导航"
                }
            }

            // ---------- 阶段二：生成书源 ----------
            onProgress("已采集 ${pages.size} 个页面，正在交给 AI 生成书源规则…")
            var source = askGenerate(pages, seed, searchTemplate, meta, includeExplore, singleBook, videoMode)
                ?: error("AI 未返回书源。请确认「角色档案设置」里已配置接口（接口@@模型@@密钥）")
            ensureMobileUa(source)
            // 目录地址正则兜底：把「详情页→目录页」的 AI 正则写进书源，
            // 运行时若书籍详情页取不到目录入口链接，就靠它把详情页地址换成目录页地址
            aiTocRegex?.let { source.getBookInfoRule().tocUrlRegex = it }
            // 规则语法规范化：AI 常写 :eq()/逗号多选择器/伪类等 legado 不认的写法，先纠正再竞速
            normalizeSourceRules(source)
            // 目录全候选竞速：AI 规则与纯代码推导的所有容器在真实页面上比拼，取章节最完整的
            if (videoMode) refineTocRulesVideo(source, pages, onProgress) else refineTocRules(source, pages, onProgress)
            if (videoMode) markAsVideoSource(source)
            // 搜索结果容器竞速：搜索侧此前没有任何纯代码兜底，这里补上与目录侧同款的机制
            refineSearchRules(source, pages, onProgress)
            // 写入登录地址：Cookie 失效时可在「书源管理→登录」重新登录，
            // 登录后的 Cookie 由 legado 按域名自动注入书源请求
            if (!loginUrl.isNullOrBlank()) {
                source.loginUrl = loginUrl
            }

            // ---------- 阶段三：五链路实测 + 失败修正 ----------
            val maxRounds = fixRounds.coerceIn(0, 8)
            var fixRound = 0
            val holder = VerifyHolder()
            var report = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode, holder = holder)

            // ===== 代码深度辅助：纯代码解析版 vs AI 识别版 双轨实测，择优采用 =====
            // 仅在勾选「代码辅助」时启用：用纯代码解析另生成一份对照书源，
            // 与 AI 版一起实跑五链路，通过更多者胜；平手优先代码版（更可控、不易幻觉）。
            // 这样 AI 偶发幻觉（尤其 tocUrl、章节/正文容器）时，由代码解析版取胜。
            if (codeAssist) {
                onProgress("代码深度辅助：正在用「纯代码解析」生成对照书源…")
                val codeSource = if (videoMode) buildCodeVideoSource(
                    pages, seed, searchTemplate, meta, includeExplore, singleBook,
                    loginUrl, homeHtml, detailUrlFinal, tocUrl
                ) else buildCodeBookSource(
                    pages, seed, searchTemplate, meta, includeExplore, singleBook,
                    loginUrl, detailUrlFinal, tocUrl
                )
                ensureMobileUa(codeSource)
                normalizeSourceRules(codeSource)
                if (videoMode) refineTocRulesVideo(codeSource, pages, onProgress) else refineTocRules(codeSource, pages, onProgress)
                if (videoMode) markAsVideoSource(codeSource)
                refineSearchRules(codeSource, pages, onProgress)
                val codeHolder = VerifyHolder()
                val codeReport = verifySource(codeSource, testKeyword, pages, onProgress, singleBook, videoMode, holder = codeHolder)
                val aiOk = report.count { it.ok }
                val codeOk = codeReport.count { it.ok }
                onProgress("双轨实测：AI 版通过 $aiOk/${report.size}，代码解析版通过 $codeOk/${codeReport.size}")
                if (codeOk >= aiOk) {
                    meta["双轨择优"] = "AI 版 $aiOk/${report.size}、代码解析版 $codeOk/${codeReport.size}，采用代码解析版"
                    onProgress("采用「纯代码解析版」（通过更多或持平且更可控）")
                    source = codeSource
                    report = codeReport
                    holder.book = codeHolder.book
                    holder.chapter = codeHolder.chapter
                } else {
                    meta["双轨择优"] = "AI 版 $aiOk/${report.size}、代码解析版 $codeOk/${codeReport.size}，采用 AI 识别版"
                    onProgress("采用「AI 识别版」")
                }
            }

            // ===== 增量修复：只修失败项、只复验失败项；已通过项永久保留，不再重复校验 =====
            // 解决「AI 整份重写把已通过的规则改坏」的问题：每轮只把失败环节的规则交给 AI 出补丁，
            // 深合并进现有书源（已通过规则原样不动），随后只重跑失败环节；哪环现在通过了就并入成功集，
            // 没通过继续留在失败集——成功环节永远不会被复验、也不会被改坏。
            // 非阻塞项（封面）无论成败都留在报告里，但不进 AI 修正轮
            val passed = report.filter { it.ok || !it.blocking }.associateBy { it.step }.toMutableMap()
            val failedItems = report.filter { !it.ok && it.blocking }.associateBy { it.step }.toMutableMap()
            val failedSteps = failedItems.keys.toMutableSet()
            while (failedSteps.isNotEmpty() && fixRound < maxRounds) {
                fixRound++
                onProgress(
                    "验证未通过（${failedSteps.joinToString("、")}），" +
                        "正在让 AI 只修正这些环节（第 $fixRound/$maxRounds 轮）…"
                )
                val fixed = fixSource(source, failedItems.values.toList(), pages, meta, videoMode) ?: break
                ensureMobileUa(fixed)
                normalizeSourceRules(fixed)
                if (videoMode) refineTocRulesVideo(fixed, pages, onProgress) else refineTocRules(fixed, pages, onProgress)
                if (videoMode) markAsVideoSource(fixed)
                refineSearchRules(fixed, pages, onProgress)
                source = fixed
                // 只复验本轮失败项；依赖的 book / 首章来自 holder（上轮成功实跑结果），不重抓已通过的环节
                val re = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode, steps = failedSteps, holder = holder)
                re.forEach { it2 ->
                    if (it2.ok) {
                        passed[it2.step] = it2
                        failedItems.remove(it2.step)
                        failedSteps.remove(it2.step)
                    } else {
                        failedItems[it2.step] = it2
                    }
                }
            }
            // 汇总：已通过项 + 仍失败项，供后续兜底 / 报告使用
            report = (passed.values + failedItems.values).toMutableList()

            // ---------- 阶段四：AI 修不动时的兜底 ----------
            // 只在上面 maxRounds 轮 AI 修正全部用尽后触发；触发后重新验证，
            // 并再给同样 maxRounds 轮的 AI 修正机会——兜底与原流程享有同等修复次数
            if (report.any { !it.ok && it.blocking }) {
                val beforeFail = report.count { !it.ok && it.blocking }
                val backupTocUrl = source.getBookInfoRule().tocUrl
                val backupSearchUrl = source.searchUrl
                val changed = applyFallbacks(
                    source, report, homeHtml, seed, testKeyword, tocUrl, detailUrlFinal, contentUrl
                )
                if (changed) {
                    onProgress("AI 修正 $maxRounds 轮仍未通过，改用兜底方案重试…")
                    var after = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode)
                    // 兜底必须让情况变好，否则原样撤回——不能因为兜底把原本能用的一环弄坏
                    if (after.count { !it.ok && it.blocking } >= beforeFail) {
                        onProgress("兜底未改善，已撤回原有规则…")
                        source.getBookInfoRule().tocUrl = backupTocUrl
                        source.searchUrl = backupSearchUrl
                        after = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode)
                    }
                    report = after
                    var round2 = 0
                    while (report.any { !it.ok && it.blocking } && round2 < maxRounds) {
                        round2++
                        onProgress(
                            "兜底方案仍未通过（${
                                report.filter { !it.ok && it.blocking }.joinToString("、") { it.step }
                            }），正在让 AI 修正（第 $round2/$maxRounds 轮）…"
                        )
                        val fixed = fixSource(source, report, pages, meta, videoMode) ?: break
                        ensureMobileUa(fixed)
                        if (videoMode) refineTocRulesVideo(fixed, pages, onProgress) else refineTocRules(fixed, pages, onProgress)
                        if (videoMode) markAsVideoSource(fixed)
                refineSearchRules(fixed, pages, onProgress)
                        source = fixed
                        report = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode)
                    }
                    fixRound += round2
                }
            }

            // ---------- 阶段四补：目录仍然拿不到章节时的最后手段 ----------
            // refineTocRules 出于「不能改坏」的考虑，对 JS/JSON 这类无法粗测的规则一律不动；
            // 但如果实测就是 0 章，保留原规则毫无意义——这里用纯代码候选强行顶一次，
            // 验证通过才留下，否则原样撤回
            if (report.firstOrNull { it.step == "目录" }?.ok != true) {
                val backupChapterList = source.getTocRule().chapterList
                if (lastResortToc(source, pages)) {
                    val recheck = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode)
                    if (recheck.firstOrNull { it.step == "目录" }?.ok == true) {
                        report = recheck
                        onProgress("目录最后手段：改用纯代码候选 ${source.getTocRule().chapterList}")
                    } else {
                        source.getTocRule().chapterList = backupChapterList
                    }
                }
            }

            // ---------- 阶段五：目录分页的翻页规则补全 ----------
            // 目录验证能过、但规则里没有 nextTocUrl 时，书源只能拿到第一页章节。
            // 这里把探测到的翻页形态（文本 / 下拉选页 / 页码容器 / JS 拼页）逐个补上重验，
            // 取章节数最多的那个；都没变好就撤回——不因为兜底把原本可用的书源改坏
            val tocOk = report.firstOrNull { it.step == "目录" }?.ok == true
            if (tocOk && tocPagerCands.isNotEmpty() &&
                source.getTocRule().nextTocUrl.isNullOrBlank()
            ) {
                val baseCount = chapterCountOf(report)
                var bestRule: String? = null
                var bestCount = baseCount
                for (c in tocPagerCands.take(3)) {
                    source.getTocRule().nextTocUrl = c.rule
                    val recheck = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode)
                    val n = chapterCountOf(recheck)
                    if (recheck.firstOrNull { it.step == "目录" }?.ok == true && n > bestCount) {
                        bestCount = n
                        bestRule = c.rule
                        if (n >= baseCount * 2) break // 已经翻倍，不必再试其它形态
                    }
                }
                if (bestRule != null) {
                    source.getTocRule().nextTocUrl = bestRule
                    report = verifySource(source, testKeyword, pages, onProgress, singleBook, videoMode)
                    onProgress("目录翻页补全：$bestRule（章节 $baseCount → $bestCount）")
                } else {
                    source.getTocRule().nextTocUrl = null
                }
            }

            if (singleBook) {
                // 单本书源：只服务于这一本书，搜索与发现都留空；
                // 名字与地址带上书名，避免与同站的其它书源撞车。
                // 跳过了详情页 AI 质检，书名从验证报告的「详情」项里取
                val name = meta["书名"].orEmpty().trim().ifBlank {
                    Regex("《(.+?)》")
                        .find(report.firstOrNull { it.step == "详情" }?.message.orEmpty())
                        ?.groupValues?.get(1).orEmpty()
                }.ifBlank { source.bookSourceName }
                source.searchUrl = null
                source.exploreUrl = null
                source.bookSourceGroup = "单本"
                if (name.isNotBlank()) {
                    source.bookSourceName = "单本·$name"
                    source.bookSourceUrl = "$seed#$name"
                }
            }
            if (source.bookSourceName.isBlank()) source.bookSourceName = "AI生成书源"
            if (source.bookSourceGroup.isNullOrBlank()) source.bookSourceGroup = "AI生成"

            // ===== 历史成功规则补齐 =====
            // 同一网址以前生成过、当时某些环节是通过的：这次这些环节又没过时，
            // 直接把历史里对应环节的规则搬过来，补完只对补齐的环节重新实跑一次，
            // 确保不是"看着补上了、实际仍不可用"。
            val patchedSteps = ArrayList<String>()
            if (!singleBook) {
                val failedSteps = report.filter { !it.ok && it.blocking }.map { it.step }.toSet()
                val record = AiSourceHistory.pickBest(seed, failedSteps)
                val old = record?.let { AiSourceHistory.parse(it) }
                if (record != null && old != null) {
                    val patchable = failedSteps.filter { it in record.okSteps }.toSet()
                    if (patchable.isNotEmpty()) {
                        onProgress("发现该网址的历史记录，正在补齐：${patchable.joinToString("、")}…")
                        patchable.forEach { copyStepRules(old, source, it) }
                        val re = verifySource(
                            source, testKeyword, pages, onProgress,
                            singleBook, videoMode, patchable, holder
                        )
                        val merged = report.associateBy { it.step }.toMutableMap()
                        re.forEach { merged[it.step] = it }
                        report = merged.values.toList()
                        patchedSteps.addAll(patchable.filter { s -> re.any { it.step == s && it.ok } })
                    }
                }
                // 记录本次结果（只记通过的阻塞性环节），供下次同一网址生成时补齐
                AiSourceHistory.save(seed, source, report.filter { it.ok && it.blocking }.map { it.step })
            }

            // ===== 封面收尾 =====
            // 无论走 AI 版 / 代码版 / 修正 / 历史补齐哪条分支，最后都保证三处 coverUrl 有规则；
            // 若封面验证仍失败，用完整候选链强制覆盖并复验一次，让报告反映真实结果。
            ensureCoverRules(source, pages)
            if (report.firstOrNull { it.step == "封面" }?.ok != true && forceCoverRules(source, pages)) {
                onProgress("封面规则未命中，正在用完整候选链重试…")
                val re = verifySource(
                    source, testKeyword, pages, onProgress,
                    skipSearch = singleBook, videoMode = videoMode,
                    steps = setOf("详情"), holder = holder
                )
                val merged = report.associateBy { it.step }.toMutableMap()
                re.forEach { v ->
                    if (v.step == "封面" || (v.step == "详情" && v.ok)) merged[v.step] = v
                }
                report = merged.values.toList()
            }

            GenResult(source, report, fixRound, patchedSteps)
        }
    }

    // ==================== 已有书源：AI 校验并修正 ====================

    /**
     * 对一份已保存的书源做「实测校验 → 内置规则修复 → AI 辅助修正」。
     * 与 generate 不同：不让 AI 重写整份书源，只针对验证失败的环节出补丁；
     * 每一步改动后都复验，没变好就撤回，绝不把原本能用的环节改坏。
     */
    suspend fun verifyAndFix(
        source: BookSource,
        keyword: String? = null,
        fixRounds: Int = 2,
        includeExplore: Boolean = true,
        codeAssist: Boolean = true,
        videoMode: Boolean = false,
        manualBookInfo: String? = null,
        manualTocUrl: String? = null,
        manualContentUrl: String? = null,        onProgress: (String) -> Unit
    ): Result<GenResult> = withContext(Dispatchers.IO) {
        runCatching {
            // 深拷贝：不直接改动 Room 里正在使用的对象，撤回时也能整份还原
            var current: BookSource = GSON.fromJson(GSON.toJson(source), BookSource::class.java)
                ?: error("书源解析失败")
            val kw = keyword?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_KEYWORD
            val seed = current.bookSourceUrl
            ensureMobileUa(current)
            normalizeSourceRules(current)
            // 校验修正视频源时同样按文档补齐类型 / 请求头并清洗违规写法
            if (videoMode) markAsVideoSource(current)

            // 与生成模式一致：目录框可填网址或入口文字，详情/正文框只收网址
            val manualInfo = manualBookInfo?.trim()
                ?.takeIf { it.isNotBlank() && looksLikeUrl(it) }?.let { normalize(it) }
            val manualTocRaw = manualTocUrl?.trim()?.takeIf { it.isNotBlank() }
            val manualToc = manualTocRaw?.takeIf { looksLikeUrl(it) }?.let { normalize(it) }
            val manualTocText = manualTocRaw?.takeUnless { looksLikeUrl(it) }
            val manualContent = manualContentUrl?.trim()
                ?.takeIf { it.isNotBlank() && looksLikeUrl(it) }?.let { normalize(it) }

            val pages = collectPagesForVerify(
                source = current,
                keyword = kw,
                includeExplore = includeExplore,
                videoMode = videoMode,
                manualInfo = manualInfo,
                manualToc = manualToc,
                manualTocText = manualTocText,
                manualContent = manualContent,
                onProgress = onProgress
            )
            val holder = VerifyHolder()
            val meta = HashMap<String, String>()
            meta["校验模式"] = "这是用户已保存的书源，只修正验证失败的环节，不要重写其它规则"
            if (manualInfo != null || manualToc != null || manualTocText != null || manualContent != null) {
                meta["用户指定的参考页面"] = buildString {
                    append("用户手动指定了以下页面，请优先以这些页面的源码为参考修规则：")
                    manualInfo?.let { append("\n  书籍详情页：").append(it) }
                    manualToc?.let { append("\n  目录页：").append(it) }
                    manualTocText?.let {
                        append("\n  目录入口文字：").append(it)
                            .append("（需在书籍详情页里按该文字找到对应链接）")
                    }
                    manualContent?.let { append("\n  章节正文页：").append(it) }
                }
            }
            onProgress("开始逐项实测校验…")
            var report = verifySource(
                current, kw, pages, onProgress,
                videoMode = videoMode, holder = holder
            )
            val maxRounds = fixRounds.coerceIn(0, 8)
            var rounds = 0
            while (report.any { !it.ok && it.blocking } && rounds < maxRounds) {
                rounds++
                val beforeOk = report.count { it.ok && it.blocking }

                // ① 内置规则：搜索/目录容器竞速、目录最后手段、通用兜底、封面补齐
                if (codeAssist) {
                    onProgress("第 $rounds/$maxRounds 轮：正在用内置规则修复失败环节…")
                    val backup = GSON.toJson(current)
                    applyCodeFixes(current, report, pages, seed, kw, videoMode, onProgress)
                    var after = verifySource(
                        current, kw, pages, onProgress,
                        videoMode = videoMode, holder = holder
                    )
                    if (after.count { it.ok && it.blocking } <= beforeOk) {
                        GSON.fromJson(backup, BookSource::class.java)?.let { current = it }
                        after = verifySource(
                            current, kw, pages, onProgress,
                            videoMode = videoMode, holder = holder
                        )
                    }
                    report = after
                }
                if (report.all { it.ok || !it.blocking }) break

                // ② AI 辅助：只返回失败环节的字段补丁，复验没变差才留下
                onProgress("第 $rounds/$maxRounds 轮：内置规则未修好，交给 AI 出补丁…")
                val failed = report.filter { !it.ok && it.blocking }
                val fixed = fixSource(current, failed, pages, meta, videoMode)
                if (fixed == null) {
                    onProgress("AI 未返回可用补丁，停止修正")
                    break
                }
                ensureMobileUa(fixed)
                normalizeSourceRules(fixed)
                val after = verifySource(
                    fixed, kw, pages, onProgress,
                    videoMode = videoMode, holder = holder
                )
                if (after.count { it.ok && it.blocking } >= report.count { it.ok && it.blocking }) {
                    current = fixed
                    report = after
                } else {
                    onProgress("AI 补丁未改善，已撤回")
                }
            }

            // 封面收尾：规则补齐，仍失败时用完整候选链强制覆盖并复验
            ensureCoverRules(current, pages)
            if (report.firstOrNull { it.step == "封面" }?.ok != true && forceCoverRules(current, pages)) {
                val re = verifySource(
                    current, kw, pages, onProgress,
                    videoMode = videoMode, steps = setOf("详情"), holder = holder
                )
                val merged = report.associateBy { it.step }.toMutableMap()
                re.forEach { v ->
                    if (v.step == "封面" || (v.step == "详情" && v.ok)) merged[v.step] = v
                }
                report = merged.values.toList()
            }
            // ===== 历史成功规则补齐 =====
            // 生成时存下「当时通过的环节」，校验时也要拿来补当前失败环节，不能只校验不利用
            val patchedSteps = ArrayList<String>()
            val failedSteps = report.filter { !it.ok && it.blocking }.map { it.step }.toSet()
            val record = AiSourceHistory.pickBest(seed, failedSteps)
            val old = record?.let { AiSourceHistory.parse(it) }
            if (record != null && old != null) {
                val patchable = failedSteps.filter { it in record.okSteps }.toSet()
                if (patchable.isNotEmpty()) {
                    onProgress("发现该网址的历史记录，正在补齐：${patchable.joinToString("、")}…")
                    patchable.forEach { copyStepRules(old, current, it) }
                    val re = verifySource(
                        current, kw, pages, onProgress,
                        videoMode = videoMode, steps = patchable, holder = holder
                    )
                    val merged = report.associateBy { it.step }.toMutableMap()
                    re.forEach { merged[it.step] = it }
                    report = merged.values.toList()
                    patchedSteps.addAll(patchable.filter { s -> re.any { it.step == s && it.ok } })
                }
            }
            onProgress("校验完成：${report.count { it.ok }}/${report.size} 项通过")
            GenResult(current, report, rounds, patchedSteps)        }
    }

    /**
     * 采集页面源码：用户在界面上手动指定的页面优先，其余按书源自己的 URL 规则抓。
     * 内置规则竞速和 AI 修正提示词都要用真实 HTML；尽力而为——某个页面取不到就跳过，
     * verifySource 仍会用 WebBook 引擎做真实校验。     */
    private suspend fun collectPagesForVerify(
        source: BookSource,
        keyword: String,
        includeExplore: Boolean,
        videoMode: Boolean,
        manualInfo: String? = null,
        manualToc: String? = null,
        manualTocText: String? = null,
        manualContent: String? = null,        onProgress: (String) -> Unit
    ): List<Page> {
        val pages = ArrayList<Page>(6)
        val seed = source.bookSourceUrl
        onProgress("校验准备：抓取首页…")
        val homeHtml = fetch(seed).orEmpty()
        if (homeHtml.isNotBlank()) pages.add(Page("首页", seed, homeHtml))

        val searchTpl = source.searchUrl?.trim().orEmpty()
        var searchHtml = ""
        if (searchTpl.isNotBlank() && !searchTpl.contains("<js>") && !searchTpl.startsWith("@js")) {
            onProgress("校验准备：抓取搜索结果页…")
            searchHtml = fetchPage(searchTpl.replace("{{page}}", "1"), keyword, seed).orEmpty()
        }
        if (searchHtml.isBlank() && homeHtml.isNotBlank()) {
            // 书源自带的 searchUrl 已经失效：从首页表单推导候选搜索地址再试
            onProgress("原搜索地址取不到页面，正在从首页推导搜索入口…")
            for (tpl in deriveSearchUrls(homeHtml, seed).take(3)) {
                searchHtml = fetchPage(tpl, keyword, seed).orEmpty()
                if (searchHtml.isNotBlank()) break
            }
        }
        if (searchHtml.isNotBlank()) pages.add(Page("搜索结果页", seed, searchHtml))

        onProgress("校验准备：抓取详情页 / 目录 / 正文…")
        // 手动指定的详情页优先，填了就不再靠搜索结果猜
        var detailUrl: String? = manualInfo
        val firstBook = if (detailUrl == null) runCatching {
            WebBook.searchBookAwait(source, keyword, 1).firstOrNull()?.toBook()
                ?.also { it.origin = seed }
        }.getOrNull() else null
        if (detailUrl == null) {
            detailUrl = firstBook?.bookUrl?.takeIf { it.isNotBlank() }?.let { absUrl(it, seed) }
            if (detailUrl.isNullOrBlank() && searchHtml.isNotBlank()) {
                detailUrl = runCatching { firstBookLink(searchHtml, seed) }.getOrNull()
            }
            if (detailUrl.isNullOrBlank() && homeHtml.isNotBlank()) {
                // 搜索规则全坏时，首页推荐位通常也有书籍链接，用它兜底
                detailUrl = runCatching { firstBookLink(homeHtml, seed) }.getOrNull()
            }        }
        val detailHtml = detailUrl?.let { fetch(it) }.orEmpty()
        if (detailUrl != null && detailHtml.isNotBlank()) {
            pages.add(Page("书籍详情页", detailUrl, detailHtml))
        }

        val infoBook = firstBook?.let {
            runCatching { WebBook.getBookInfoAwait(source, it, canReName = true) }.getOrNull()
        }
        // 目录页同样以手动指定优先；填的是入口文字时，按文字在详情页里找对应链接
        var tocUrl: String? = manualToc
        if (tocUrl == null && manualTocText != null && detailHtml.isNotBlank()) {
            tocUrl = runCatching {
                heuristicTocLinks(detailHtml, detailUrl ?: seed, listOf(manualTocText)).firstOrNull()
            }.getOrNull()
            if (tocUrl != null) {
                onProgress("按你指定的入口「$manualTocText」找到目录页：$tocUrl")
            }
        }
        if (tocUrl == null) {
            tocUrl = infoBook?.tocUrl?.takeIf { it.isNotBlank() }
                ?.let { absUrl(it, detailUrl ?: seed) } ?: detailUrl
        }
        val tocHtml = tocUrl?.let { fetch(it) }.orEmpty()
        if (tocUrl != null && tocHtml.isNotBlank()) {
            pages.add(Page("目录页", tocUrl, tocHtml))
        }
        // 手动指定的章节正文页优先：填了就直接用，不再从目录里猜第一章
        var chapterUrl: String? = manualContent
        if (chapterUrl == null) {
            chapterUrl = infoBook?.let { b ->
                runCatching {
                    WebBook.getChapterListAwait(source, b).getOrNull()?.firstOrNull()?.url
                }.getOrNull()
            }?.takeIf { it.isNotBlank() }?.let { absUrl(it, tocUrl ?: seed) }
            if (chapterUrl.isNullOrBlank() && tocHtml.isNotBlank()) {
                // 目录规则坏到 0 章时，直接从目录页里找第一章链接，保证 AI 能看到正文页
                chapterUrl = runCatching {
                    val chapterName = Regex("第\\s*[0-9零〇一二两三四五六七八九十百千万]+\\s*[章节回集]")
                    Jsoup.parse(tocHtml, tocUrl ?: seed).select("a[href]")
                        .firstOrNull { chapterName.containsMatchIn(it.text()) }
                        ?.attr("abs:href")
                }.getOrNull()?.takeIf { it.startsWith("http") }
            }        }
        val contentHtml = chapterUrl?.let { fetch(it) }.orEmpty()
        if (chapterUrl != null && contentHtml.isNotBlank()) {
            pages.add(Page(if (videoMode) "播放页" else "正文页", chapterUrl, contentHtml))
        }

        if (includeExplore) {
            val explore = source.exploreUrl?.trim().orEmpty()
            if (explore.isNotBlank()) {
                onProgress("校验准备：抓取分类列表页…")
                parseExploreFirstUrl(explore, seed)?.let { u ->
                    fetch(u)?.takeIf { it.isNotBlank() }
                        ?.let { pages.add(Page("分类列表页", u, it)) }
                }
            }
        }
        return pages
    }

    /** 内置规则修复：只针对失败环节跑纯代码候选竞速、通用兜底与封面补齐 */
    private fun applyCodeFixes(
        source: BookSource,
        report: List<VerifyItem>,
        pages: List<Page>,
        seed: String,
        keyword: String,
        videoMode: Boolean,
        onProgress: (String) -> Unit
    ) {
        val failed = report.filter { !it.ok && it.blocking }.map { it.step }.toSet()
        if ("搜索" in failed) {
            refineSearchRules(source, pages, onProgress)
        }
        if ("目录" in failed || "正文" in failed || "播放" in failed) {
            if (videoMode) {
                refineTocRulesVideo(source, pages, onProgress)
            } else {
                refineTocRules(source, pages, onProgress)
            }
            if ("目录" in failed) lastResortToc(source, pages)
        }
        ensureCoverRules(source, pages)
        if (report.firstOrNull { it.step == "封面" }?.ok != true) {
            forceCoverRules(source, pages)
        }
        val homeHtml = pages.firstOrNull { it.role == "首页" }?.html.orEmpty()
        val infoUrl = pages.firstOrNull { it.role == "书籍详情页" }?.url
        val tocUrl = pages.firstOrNull { it.role == "目录页" }?.url.orEmpty()
        val contentUrl = pages.firstOrNull {
            it.role.startsWith("正文") || it.role.startsWith("播放")
        }?.url
        applyFallbacks(source, report, homeHtml, seed, keyword, tocUrl, infoUrl, contentUrl)
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

    /**
     * 抓取页面：先用内置客户端，失败自动降级到 legado 全局网络栈。
     * 降级原因（实测案例）：
     * · 部分站点服务器有 HTTP/2 流 Bug（m.qbmfxs.com 动态路径 INTERNAL_ERROR 崩流），
     *   浏览器会自动降级 HTTP/1.1 而 OkHttp 不会 → 内置客户端已强制 HTTP/1.1；
     * · 部分 WAF 对请求头不全的请求直接重置连接（需 Accept/Referer 等完整头）；
     * · 设备上浏览器有安全 DNS 可绕过 DNS 污染，直连 OkHttp 不能——
     *   全局栈带 Cronet 拦截器（与 Chrome 同源网络栈），行为与浏览器一致。
     */
    private fun fetch(
        url: String,
        pcUa: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ): String? {
        fetchBy(client, url, pcUa, headers)?.let { return it }
        // 系统 DNS 失败 → 依次尝试 DoH（防 DNS 污染）
        for (c in dohClients) {
            fetchBy(c, url, pcUa, headers)?.let {
                AppLog.put("[AI书源] 系统 DNS 抓取失败，DoH 解析后成功：$url")
                return it
            }
        }
        fetchBy(globalClient, url, pcUa, headers)?.let {
            AppLog.put("[AI书源] 已通过全局网络栈降级抓取成功：$url")
            return it
        }
        // 最后兜底：WebView 真浏览器内核抓取——与用户浏览器完全同源
        // （系统网络栈、QUIC、ECH 等，浏览器能开的它就能开），解决 SNI 干扰等顽固问题
        return fetchByWebView(url, headers)?.also {
            AppLog.put("[AI书源] 所有网络栈均失败，WebView 内核抓取成功：$url")
        }
    }

    /** 用后台 WebView（真浏览器内核）抓取页面，必须在后台线程调用 */
    private fun fetchByWebView(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String? = runCatching {
        runBlocking {
            BackstageWebView(
                url = url,
                headerMap = mapOf("User-Agent" to MOBILE_UA) + headers,
                tag = "AiBookSource"
            ).getStrResponse().body?.takeIf { it.isNotBlank() }
        }
    }.onFailure {
        AppLog.put("[AI书源] WebView 抓取失败：${it.message}", it)
    }.getOrNull()

    private fun fetchBy(
        c: OkHttpClient,
        url: String,
        pcUa: Boolean,
        headers: Map<String, String> = emptyMap()
    ): String? = runCatching {
        val referer = runCatching {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}/"
        }.getOrDefault("")
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", if (pcUa) PC_UA else MOBILE_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", referer)
        // 书源自带的自定义请求头（社区书源里 113 站配了 headers 选项）
        headers.forEach { (k, v) -> builder.header(k, v) }
        // 登录态：按域名取用户在此站点登录保存的 Cookie（需要登录才能看正文的站点）
        CookieStore.getCookie(url).takeIf { it.isNotBlank() }?.let {
            builder.header("Cookie", it)
        }
        val request = builder.build()
        c.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.put("[AI书源] 抓取失败 HTTP ${response.code}：$url")
                return@use null
            }
            val raw = response.body?.bytes() ?: return@use null
            decodeHtml(raw, response).takeIf { it.isNotBlank() }
        }
    }.onFailure { AppLog.put("[AI书源] 抓取异常：${it.message}", it) }.getOrNull()

    /**
     * 按站点真实编码解码响应体：国内小说站大量使用 GBK（社区书源里 19% 的 searchUrl 带
     * charset=gbk 选项），而 OkHttp 的 body.string() 默认按 UTF-8 解，GBK 页面会变成乱码，
     * AI 拿着乱码页面写规则必然写错。这里按「响应头 → HTML meta → 默认 UTF-8」依次判定。
     */
    private fun decodeHtml(raw: ByteArray, response: Response): String {
        val declared = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(response.header("Content-Type").orEmpty())?.groupValues?.get(1)
        val enc = if (declared.isNullOrBlank()) {
            detectCharset(raw)
        } else if (declared.equals("gb2312", true) || declared.equals("gbk", true)) {
            "GB18030"
        } else {
            declared
        }
        return runCatching { String(raw, charset(enc)) }.getOrDefault(String(raw, Charsets.UTF_8))
    }

    /** 从 HTML 头部的 <meta charset=..> / http-equiv 里猜编码，猜不到按 UTF-8 */
    private fun detectCharset(raw: ByteArray): String {
        val head = runCatching { String(raw, Charsets.ISO_8859_1) }.getOrDefault("").take(2048)
        val v = Regex("""charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)?.lowercase().orEmpty()
        return when {
            v.startsWith("gb") -> "GB18030"
            v == "big5" -> "Big5"
            v.isNotBlank() -> v
            else -> "UTF-8"
        }
    }

    /**
     * 按 legado searchUrl 格式抓页面：
     * 纯地址替换 {{key}} 后 GET；`地址,{"method":"POST","body":"..."}` 按 POST 抓。
     */
    /** 相对地址 → 绝对地址（书源里 41% 的 searchUrl 是相对路径，采集阶段必须补全才能请求） */
    private fun absUrl(u: String, base: String): String {
        val s = u.trim()
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        if (s.startsWith("//")) return "https:$s"
        if (base.isBlank()) return s
        val b = base.trimEnd('/')
        return if (s.startsWith("/")) {
            (Regex("^(https?://[^/]+)").find(b)?.groupValues?.get(1) ?: b) + s
        } else {
            "$b/$s"
        }
    }

    private fun fetchPage(template: String, keyword: String, baseUrl: String = ""): String? {
        val comma = template.indexOf(",{\"")
        if (comma < 0) return fetch(absUrl(fillKey(template, keyword), baseUrl))
        val url = absUrl(template.substring(0, comma).trim(), baseUrl)
        val option = runCatching {
            JsonParser.parseString(template.substring(comma + 1)).asJsonObject
        }.getOrNull() ?: return fetch(fillKey(url, keyword))
        // 书源自带的自定义请求头（社区书源里 113 站配了 headers 选项）
        val headers = option.getAsJsonObject("headers")?.entrySet()
            ?.mapNotNull { (k, v) -> runCatching { k to v.asString }.getOrNull() }
            ?.toMap().orEmpty()
        val finalUrl = fillKey(url, keyword)
        // webView：搜索结果由 JS 渲染时必须用浏览器内核取（社区书源里 145 站配了这个选项）
        if (option.bool("webView") == true) {
            return fetchByWebView(finalUrl, headers) ?: fetch(finalUrl, headers = headers)
        }
        if (!option.str("method").equals("post", true)) return fetch(finalUrl, headers = headers)
        val body = fillKey(option.str("body") ?: "", keyword)
        val charset = option.str("charset")?.takeIf { it.isNotBlank() } ?: "UTF-8"
        return postBy(postClient, url, body, charset, headers)
            ?: postBy(globalClient, url, body, charset, headers)?.also {
                AppLog.put("[AI书源] 搜索 POST 直连失败，已通过全局网络栈降级成功：$url")
            }
    }

    private fun postBy(
        c: OkHttpClient,
        url: String,
        body: String,
        charset: String,
        headers: Map<String, String> = emptyMap()
    ): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", runCatching {
                val u = java.net.URI(url)
                "${u.scheme}://${u.host}/"
            }.getOrDefault(""))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=$charset")
        headers.forEach { (k, v) -> builder.header(k, v) }
        CookieStore.getCookie(url).takeIf { it.isNotBlank() }?.let {
            builder.header("Cookie", it)
        }
        val request = builder
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        c.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                AppLog.put("[AI书源] 搜索 POST 失败 HTTP ${response.code}：$url")
                return@use null
            }
            val raw = response.body?.bytes() ?: return@use null
            decodeHtml(raw, response).takeIf { it.isNotBlank() }
        }
    }.onFailure { AppLog.put("[AI书源] 搜索 POST 异常：${it.message}", it) }.getOrNull()

    private fun fillKey(template: String, keyword: String): String {
        val key = runCatching { URLEncoder.encode(keyword, "UTF-8") }.getOrDefault(keyword)
        return template.replace("{{key}}", key)
    }

    /**
     * 从首页的搜索表单推导搜索地址——AI 猜错搜索入口（编造不存在的路径、漏掉隐藏字段、
     * 该用 POST 却写成 GET）时的纯代码兜底。POST 形式排在前面，因为它最贴近表单真实行为。
     */
    private fun deriveSearchUrls(homeHtml: String, homeUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(homeHtml, homeUrl) }.getOrNull() ?: return emptyList()
        val out = LinkedHashSet<String>()
        val keyNames = listOf(
            "keyword", "keywords", "key", "wd", "q", "searchkey", "searchword", "kw", "s"
        )
        for (form in doc.select("form")) {
            val action = form.attr("abs:action").takeIf { it.startsWith("http") } ?: continue
            val isPost = form.attr("method").trim().equals("post", true)
            val inputs = form.select("input[name]")
            val keyInput = inputs.firstOrNull { i ->
                val n = i.attr("name").lowercase()
                val type = i.attr("type").lowercase()
                keyNames.any { it == n } && (type.isEmpty() || type == "text" || type == "search")
            } ?: continue
            val name = keyInput.attr("name")
            val hidden = inputs.filter { it !== keyInput && it.attr("name").isNotBlank() }
                .filter { it.attr("type").lowercase() == "hidden" }
                .joinToString("&") { "${it.attr("name")}=${it.attr("value")}" }
            val pairs = if (hidden.isBlank()) "$name={{key}}" else "$name={{key}}&$hidden"
            if (isPost) out.add("$action,{\"method\":\"POST\",\"body\":\"$pairs\"}")
            out.add("$action?$pairs")
        }
        return out.toList()
    }

    /** 搜索结果页里指向书籍详情页的链接数量（纯代码复核：搜索地址是否真的能搜到书） */
    private fun countBookLinks(html: String, baseUrl: String): Int {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return 0
        val chapter = Regex("第\\s*[0-9零〇一二两三四五六七八九十百千万]+\\s*[章节回]")
        val bad = listOf(
            "首页", "登录", "注册", "排行", "书架", "下一页", "上一页", "尾页", "更多", "搜索",
            "返回", "下载", "收藏", "帮助", "关于", "联系", "分类", "全本", "阅读记录", "顶部"
        )
        val host = hostOf(baseUrl)
        val urls = HashSet<String>()
        for (a in doc.select("a[href]")) {
            val t = a.text().trim()
            val href = a.attr("abs:href")
            if (!href.startsWith("http") || t.isBlank() || t.length > 40) continue
            if (bad.any { t.contains(it) } || chapter.containsMatchIn(t)) continue
            if (host != null && hostOf(href) != host) continue
            val path = runCatching { java.net.URI(href).path }.getOrNull() ?: continue
            if (Regex("\\.(css|js|jpg|jpeg|png|gif|txt|zip|html?)$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(path)
            ) continue
            // 书籍详情页：路径里通常带这本书的数字 id
            if (!Regex("/[a-zA-Z_]*\\d{2,}").containsMatchIn(path)) continue
            urls.add(href)
        }
        return urls.size
    }

    /** 逐个试候选搜索地址，取能数出书籍链接最多的那个（返回 模板 与 抓到的页面） */
    private fun bestSearchCandidate(
        homeHtml: String,
        homeUrl: String,
        keyword: String
    ): Pair<String, String>? {
        var best: Pair<String, String>? = null
        var bestCount = 1
        for (tpl in deriveSearchUrls(homeHtml, homeUrl).take(4)) {
            val html = fetchPage(tpl, keyword, homeUrl) ?: continue
            val n = countBookLinks(html, absUrl(fillKey(tpl.substringBefore(",{\""), keyword), homeUrl))
            if (n > bestCount) {
                bestCount = n
                best = tpl to html
            }
        }
        return best
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

    /**
     * 详情页里的目录入口链接：文字含「目录/全部章节/章节列表」等，
     * 或文字就是一串省略号（......、…、···）——有些站点只给一个点，点进去才是完整目录。
     * 用于目录多候选竞速——很多站详情页只挂部分章节，真正的完整目录在独立页。
     */
    private fun heuristicTocLinks(
        detailHtml: String,
        baseUrl: String,
        extraKeywords: List<String> = emptyList()
    ): List<String> {
        val doc = Jsoup.parse(detailHtml, baseUrl)
        val keywords = listOf(
            "目录", "全部章节", "章节列表", "查看全部", "完整目录", "所有章节",
            "查看全部章节", "章节目录", "全部章节列表", "查看目录", "进入目录"
        )
        // 纯省略号入口：有些站的目录入口不给任何文字，就是 ...... / … / ··· / ••• 一串点，
        // 点进去才是完整目录页——必须认，否则这类站点永远找不到目录
        val ellipsis = Regex("^[.．。·•…・,、\\s]{2,}$")
        // 用户在目录框里指定的入口文字优先级最高（可能就是「......」这类站点自定义写法）
        val extra = extraKeywords.map { it.trim() }.filter { it.isNotBlank() }
        val top = LinkedHashSet<String>()
        val strong = LinkedHashSet<String>()
        val weak = LinkedHashSet<String>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("abs:href")
            if (!href.startsWith("http")) return@forEach
            val t = a.text().trim()
            when {
                extra.any { t.contains(it) } -> top.add(href)
                t.length <= 16 && keywords.any { t.contains(it) } -> strong.add(href)
                ellipsis.matches(t) -> weak.add(href)
            }
        }
        // 用户指定 > 强特征（明确的目录文字）> 弱特征（纯省略号）：
        // 候选抓取有数量上限，排在前面才不会被截断丢掉
        return (top + strong + weak).distinct()
    }

    /**
     * 统计页面里「第X章/节/回」式章节链接的数量。
     * 用于目录候选竞速：数量最多的候选才是完整目录。
     * （同一标准下比较，有区分度即可；个别站章节名无编号会使计数偏低，但不影响相对比较）
     */
    private fun countChapterLinks(html: String): Int {
        val doc = Jsoup.parse(html)
        val chapterPattern = Regex("第\\s*[0-9零〇一二两三四五六七八九十百千万]+\\s*[章节回]")
        return doc.select("a[href]").count { chapterPattern.containsMatchIn(it.text()) }
    }

    /**
     * 书籍信息页里常常同时存在两个章节区块：「最新章节」（少量、倒序）与「全部章节列表」（完整、正序），
     * 二者还常常挤在同一个容器里（div 未闭合导致）。AI 分不清就会把最新章节那几章当成完整目录，
     * 或把两段一起取——章节重复、首章也不是第 1 章。这里用纯代码把区块结构与分界点算出来。
     */
    private fun describeChapterBlocks(html: String, baseUrl: String): String? {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return null
        val pattern = Regex("第\\s*[0-9零〇一二两三四五六七八九十百千万]+\\s*[章节回]")
        val blocks = ArrayList<Pair<Element, List<Element>>>()
        for (el in doc.select("div,ul,ol")) {
            val links = el.select("a[href]").filter { pattern.containsMatchIn(it.text()) }
            if (links.size >= 3) blocks.add(el to links)
        }
        if (blocks.isEmpty()) return null
        // 只描述最内层容器：链接数相同的外层容器不必重复
        val inner = blocks.filter { b ->
            blocks.none { o ->
                o !== b && o.second.size == b.second.size && isAncestorOf(o.first, b.first)
            }
        }
        return buildString {
            append("页面里的章节区块（务必区分「最新章节」与「全部章节列表」）：\n")
            inner.sortedByDescending { it.second.size }.take(3).forEachIndexed { i, (el, links) ->
                append("  ${i + 1}) <").append(el.tagName())
                if (el.id().isNotBlank()) append(" id=").append(el.id())
                if (el.className().isNotBlank()) append(" class=").append(el.className())
                append("> 共 ").append(links.size).append(" 条章节链接；")
                append("开头三条：").append(links.take(3).joinToString(" / ") { it.text().trim() })
                append("；末尾三条：").append(links.takeLast(3).joinToString(" / ") { it.text().trim() })
                append("\n")
                // 直接给出可照抄的 chapterList 写法，减少 AI 自己拼选择器带来的随机性
                append("     推荐写法：ruleToc.chapterList = ")
                append(suggestChapterList(el, links)).append("\n")
                val split = fullListStartIndex(links)
                if (split > 0) {
                    append("     ⚠ 前 ").append(split).append(" 条是倒序的「最新章节」，")
                    append("完整目录从第 ").append(split + 1).append(" 条（「")
                    append(links[split].text().trim()).append("」）开始。")
                    append("优先用能精确定位到「全部章节列表」那个容器的选择器；")
                    append("实在区分不开时，宁可把前面这几条最新章节一起带上（多几条不影响阅读），")
                    append("也不要写没把握的 JS 截取规则——规则写错会导致整个目录解析失败。\n")
                }
            }
        }
    }

    /**
     * 目录分页：目录一多站点就会分页（本站每页只列 50 章），规则里没有 nextTocUrl 时
     * 书源只能拿到第一页的章节。这里用纯代码找出「下一页」并给出可直接用的规则。
     * 返回 Pair(给 AI 的说明, 可直接写入 ruleToc.nextTocUrl 的规则)。
     */
    /**
     * 按容器与链接结构直接拼出 chapterList 选择器，作为给 AI 的确定性建议。
     * 链接外层还有一层 p/li 时用两级写法（legado 常见形态）。
     */
    private fun suggestChapterList(container: Element, links: List<Element>): String {
        val head = when {
            container.className().isNotBlank() ->
                "class." + container.className().trim().split(Regex("\\s+")).first()
            container.id().isNotBlank() -> "id." + container.id()
            else -> "tag." + container.tagName()
        }
        val wrapper = links.firstOrNull()?.parent()
        return if (wrapper != null && wrapper !== container &&
            wrapper.tagName() in listOf("p", "li", "dd", "span")
        ) {
            "$head@tag.${wrapper.tagName()}@tag.a"
        } else {
            "$head@tag.a"
        }
    }

    /** 完整目录的起点：章节序号最小的那条（它前面若还有倒序的最新章节，起点就 > 0） */
    private fun fullListStartIndex(links: List<Element>): Int {
        var minIdx = -1
        var minVal = Long.MAX_VALUE
        links.forEachIndexed { i, a ->
            val n = Regex("第\\s*([0-9]+)\\s*[章节回]").find(a.text().trim())
                ?.groupValues?.get(1)?.toLongOrNull() ?: return@forEachIndexed
            if (n < minVal) {
                minVal = n
                minIdx = i
            }
        }
        // 最小值确实接近第 1 章、且不在开头，才认为前面混了「最新章节」
        return if (minIdx > 0 && minVal <= 3) minIdx else 0
    }

    private fun isAncestorOf(parent: Element, child: Element): Boolean {
        var p = child.parent()
        while (p != null) {
            if (p === parent) return true
            p = p.parent()
        }
        return false
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

    /**
     * 在书籍信息页源码里定位指向指定目录页的那个 <a>，把它的文字、href、所属容器结构与
     * 上下文 HTML 直接挖出来——写 ruleBookInfo.tocUrl 时照这个位置写就不会错。
     *
     * 找不到的兜底顺序：① 目录页就是信息页加锚点 → 按「信息页地址+#锚点」拼；
     * ② 看「目录地址推导」里按编号/固定后缀拼出的规则；③ 都没有且目录就在信息页里才留空。
     */
    private fun locateTocAnchor(detailHtml: String, baseUrl: String, tocUrl: String): String {
        val doc = runCatching { Jsoup.parse(detailHtml, baseUrl) }.getOrNull()
            ?: return "书籍信息页解析失败，无法定位目录入口"
        // 原逻辑：先精确匹配（去 # 锚点、去尾斜杠、忽略 http/https 差异），
        // 再退一级按上级路径匹配——命中就按原样把入口位置交给 AI
        val target = urlKey(tocUrl)
        val anchors = doc.select("a[href]")
        val hit = anchors.firstOrNull { urlKey(it.attr("abs:href")) == target }
            ?: anchors.firstOrNull { looseUrlKey(it.attr("abs:href")) == looseUrlKey(tocUrl) }
        if (hit != null) return describeAnchor(hit)
        // —— 以下都是原逻辑找不到入口后的兜底，不改变原本能命中的情况 ——
        // ① 目录页就是书籍信息页加个页内锚点（.../content/221532 → .../content/221532#chapters）
        val anchor = tocUrl.substringAfter('#', "").trim()
        if (anchor.isNotBlank() && urlKey(baseUrl) == urlKey(tocUrl)) {
            val a = doc.select("a[href]").firstOrNull {
                it.attr("href").substringAfter('#') == anchor
            }
            if (a != null) return describeAnchor(a)
            return buildString {
                append("书籍信息页里没有指向该目录页的链接，也没有 href=\"#").append(anchor)
                append("\" 这样的页内锚点链接——这本书的目录章节就渲染在书籍信息页里，")
                append("目录页地址 = 书籍信息页地址 + #").append(anchor).append("。")
                append("ruleBookInfo.tocUrl 可改用「目录地址推导」里的拼接写法（不要编造不存在的选择器），")
                append("ruleToc 按书籍信息页里的完整章节列表容器写，不要只取「最新章节」区块。")
            }
        }
        return buildString {
            append("书籍信息页里没有直接指向该目录页的 <a> 链接")
            append("（可能是 JS 动态拼接，或目录页与书籍信息页不同域）。")
            append("此时 ruleBookInfo.tocUrl 不要用不存在的选择器编造：")
            append("先看「目录地址推导」里有没有可用的拼接写法，有就采用；")
            append("都没有、且目录章节确实就在书籍信息页里时才留空。")
        }
    }

    /** 把找到的那个目录入口 <a> 的位置信息描述清楚，供 AI 抄成选择器 */
    private fun describeAnchor(hit: Element): String {
        val parent = hit.parent()
        val grand = parent?.parent()
        val siblingLinks = parent?.children()
            ?.count { it.tagName() == "a" && it.hasAttr("href") } ?: 1
        val index = parent?.children()?.indexOf(hit)?.plus(1) ?: 0
        val ctx = (parent?.html() ?: hit.outerHtml())
            .replace(Regex("\\s+"), " ").trim().take(500)
        return buildString {
            append("目录入口链接：文字「").append(hit.text().trim()).append("」，")
            append("href=\"").append(hit.attr("href")).append("\"")
            append("（绝对地址 ").append(hit.attr("abs:href")).append("）")
            if (hit.className().isNotBlank()) append("，a.class=").append(hit.className())
            if (hit.id().isNotBlank()) append("，a.id=").append(hit.id())
            append("；所在容器 ")
            if (parent != null) {
                append("<").append(parent.tagName()).append(">")
                if (parent.id().isNotBlank()) append(" id=").append(parent.id())
                if (parent.className().isNotBlank()) append(" class=").append(parent.className())
            } else {
                append("（未知）")
            }
            append("，是容器内第 ").append(index).append(" 个子元素（该容器共 ")
            append(siblingLinks).append(" 个链接）")
            if (grand != null) {
                append("，上层 <").append(grand.tagName()).append(">")
                if (grand.id().isNotBlank()) append(" id=").append(grand.id())
                if (grand.className().isNotBlank()) append(" class=").append(grand.className())
            }
            append("。\n容器 HTML 片段：").append(ctx)
        }
    }

    /**
     * 目录页与书籍详情页之间常有固定地址规律（如 /novel/113846.html → /novel/list/113846/1.html），
     * 但详情页里那个入口链接往往没有任何 class/id 可用（甚至压根没有入口链接）。
     * 这里按「书籍编号」把可直接在 legado 里用的 tocUrl JS 规则算出来交给 AI，
     * 避免 AI 把 tocUrl 留空（目录退化成详情页几章）或写死某一本书的地址（所有书共用一份目录）。
     *
     * 候选顺序：① 同页锚点 ② 信息页地址+固定后缀 ③ 同目录换末段文件名 ④ 按书籍标识派生。
     * 具体命中哪一种完全由这两个网址的实际差分决定，不预设任何固定形态。
     */
    /**
     * 用户同时填了书籍详情页与目录页网址时，调一次 AI 推导「详情页地址 → 目录页地址」的正则，
     * 返回可直接写入书源、作用在 baseUrl（书籍自身地址）上的 legado <js> 候选规则：
     *   <js>baseUrl.replace(new RegExp("PATTERN","g"),"REPLACEMENT")</js>
     * 大部分小说站地址里那段数字/字母就是书籍 ID，详情页与目录页共用，AI 很容易识别。
     * 校验：必须用 infoUrl 套这个正则严格等于 tocUrl 才采用；推导不出或两网址相同则返回 null。
     */
    private fun askTocUrlRegex(
        infoUrl: String,
        tocUrl: String,
        onProgress: (String) -> Unit
    ): String? {
        if (infoUrl.isBlank() || tocUrl.isBlank() || cleanUrl(infoUrl) == cleanUrl(tocUrl)) {
            AppLog.put("[AI书源] 目录地址正则推导跳过：两网址为空或相同（infoUrl=$infoUrl, tocUrl=$tocUrl）")
            return null
        }
        val prompt = ASK_TOC_REGEX.format(infoUrl, tocUrl)
        val raw = chat(prompt, 600) ?: run {
            AppLog.put("[AI书源] 目录地址正则推导失败：AI 接口无返回（检查网络/API 密钥是否可用）")
            return null
        }
        val obj = parseJsonObject(raw) ?: run {
            AppLog.put("[AI书源] 目录地址正则推导失败：AI 返回无法解析为 JSON：${raw.take(200)}")
            return null
        }
        val pattern = obj.str("pattern")?.takeIf { it.isNotBlank() } ?: run {
            AppLog.put("[AI书源] 目录地址正则推导失败：AI 未给出可用的 pattern")
            return null
        }
        val replacement = obj.str("replacement").orEmpty()
        // 本地校验：正则必须能把详情页地址严格变成目录页地址，才敢写进书源兜底
        val out = runCatching { infoUrl.replace(Regex(pattern), replacement) }.getOrNull()
        if (out != tocUrl) {
            AppLog.put("[AI书源] 目录地址正则推导失败：正则校验不通过，详情页 $infoUrl 套用后=$out 期望目录页=$tocUrl")
            return null
        }
        // 把 pattern/replacement 序列化成合法的 JS 字符串字面量（GSON 负责转义），
        // 拼成 baseUrl.replace(new RegExp("...","g"),"...")，让 legado 在书籍自身地址上套用
        val js = "<js>baseUrl.replace(new RegExp(${GSON.toJson(pattern)}, \"g\"), " +
            "${GSON.toJson(replacement)})</js>"
        AppLog.put("[AI书源] 目录地址正则候选推导成功：$infoUrl → $tocUrl  ($js)")
        // 把「书籍详情页网址用正则转换」的结果直接提示出来，方便人工核对转换是否正确
        onProgress(
            "【AI 目录地址正则推导】\n" +
            "  书籍详情页：$infoUrl\n" +
            "  转换后目录页：$out\n" +
            "  规则：$js"
        )
        return js
    }

    private fun deriveTocUrlRule(infoUrl: String, tocUrl: String): String? {
        if (infoUrl.isBlank() || tocUrl.isBlank()) return null
        // ===== 原逻辑（一字不动地保留）：同域 + 按数字书籍编号派生 =====
        if (hostOf(infoUrl) != null && hostOf(infoUrl) == hostOf(tocUrl)) {
            // 书籍编号：详情页地址末段的数字（.../novel/113846.html → 113846）
            val bookId = Regex("/(\\d+)\\.html").find(infoUrl)?.groupValues?.get(1)
                ?: Regex("/(\\d+)/?$").find(infoUrl)?.groupValues?.get(1)
            if (bookId != null) {
                // 编号必须在目录页地址里出现且只出现一次，才是「按编号派生」的关系
                val start = tocUrl.indexOf(bookId)
                if (start >= 0 && tocUrl.indexOf(bookId, start + 1) < 0) {
                    val prefix = tocUrl.substring(0, start)
                    val suffix = tocUrl.substring(start + bookId.length)
                    if (prefix.isNotBlank()) {
                        // 用 JS 字符串切分取编号，不用正则——避免 \d 这类写法在书源 JSON 里多层转义出错
                        val rule = "<js>var u=baseUrl.split(\"?\")[0];" +
                            "var n=u.substring(u.lastIndexOf(\"/\")+1).split(\".\")[0];" +
                            "\"$prefix\"+n+\"$suffix\"</js>"
                        return buildString {
                            append("【目录地址推导】这两个网址存在固定规律：")
                            append("书籍详情页 ").append(infoUrl).append(" → 目录页 ").append(tocUrl)
                            append("，其中的书籍编号是 ").append(bookId).append("。\n")
                            append("书籍信息页里那个入口链接往往没有可定位的 class/id（甚至根本没有入口链接），")
                            append("所以 ruleBookInfo.tocUrl 不要留空、也不要写死某一本书的地址")
                            append("（写死会让所有书都打开同一本书的目录），")
                            append("应当按上面的规律用 JS 取出书籍编号再拼装。本站可直接使用的写法：\n")
                            append(rule)
                            append("\n（legado JS 规则：baseUrl 是书籍详情页地址；")
                            append("上式先去掉可能带的 ? 参数，再取末段编号 ").append(bookId)
                            append("，拼成 ").append(prefix).append("编号").append(suffix).append("）")
                        }
                    }
                }
            }
        }
        // ===== 原逻辑推不出来时，才依次尝试下面这些兜底写法 =====
        deriveAnchorRule(infoUrl, tocUrl)?.let { return it }
        deriveSuffixRule(infoUrl, tocUrl)?.let { return it }
        deriveSiblingRule(infoUrl, tocUrl)?.let { return it }
        deriveIdRule(infoUrl, tocUrl)?.let { return it }
        // 目录页与书籍信息页完全是同一个地址：没有独立目录页，tocUrl 应留空
        if (cleanUrl(infoUrl) == cleanUrl(tocUrl)) {
            return buildString {
                append("【目录地址推导】目录页地址与书籍信息页地址完全相同（").append(infoUrl).append("），")
                append("本书没有独立目录页，目录章节就渲染在书籍信息页里。\n")
                append("ruleBookInfo.tocUrl 建议留空（留空 = 直接用书籍信息页当目录页），")
                append("不要填任何地址，尤其不要把「开始阅读」那种指向第一章正文的链接填进去")
                append("（那会让目录页变成第一章正文，章节列表直接为空）。")
                append("ruleToc 按「章节区块」里指出的完整目录部分写。")
            }
        }
        return null
    }

    /** 目录页与书籍信息页在同一目录，只是末段文件名不同（.../1/index.html → .../1/catalog.html） */
    private fun deriveSiblingRule(infoUrl: String, tocUrl: String): String? {
        val i = cleanUrl(infoUrl)
        val t = cleanUrl(tocUrl)
        if (i.isBlank() || t.isBlank() || i == t) return null
        val iDir = i.substringBeforeLast('/', "")
        val tDir = t.substringBeforeLast('/', "")
        if (iDir.isBlank() || iDir != tDir) return null
        val file = t.substringAfterLast('/')
        if (file.isBlank() || file.contains('"')) return null
        val rule = "<js>${BASE_URL_JS}u.substring(0,u.lastIndexOf(\"/\")+1)+\"$file\"</js>"
        return buildString {
            append("【目录地址推导】目录页与书籍信息页在同一个目录，只换了末段文件名：")
            append("书籍信息页 ").append(infoUrl).append(" → 目录页 ").append(tocUrl).append("。\n")
            append("所以 ruleBookInfo.tocUrl 不要留空、也不要写死某一本书的地址")
            append("（写死会让所有书都打开同一本书的目录），")
            append("按「取书籍详情页所在目录 + 固定文件名」拼装。本站可直接使用的写法：\n")
            append(rule)
            append("\n（legado JS 规则：baseUrl 是书籍详情页地址；去掉 ? 参数、# 锚点与末尾斜杠后")
            append("取到目录部分，再拼上 ").append(file).append("）")
        }
    }

    /**
     * 目录页地址里含有书籍信息页的「书籍标识」：
     * /novel/113846.html → /novel/list/113846/1.html、/book/abc-123 → /toc/abc-123.html。
     * 标识不限于纯数字——用字母数字 slug 当书号的站点同样适用。
     */
    private fun deriveIdRule(infoUrl: String, tocUrl: String): String? {
        val id = bookIdOf(infoUrl) ?: return null
        // 标识必须在目录页地址里出现且只出现一次，才是「按标识派生」的关系
        val start = tocUrl.indexOf(id)
        if (start < 0 || tocUrl.indexOf(id, start + 1) >= 0) return null
        val prefix = tocUrl.substring(0, start)
        val suffix = tocUrl.substring(start + id.length)
        if (prefix.isBlank() || prefix.contains('"') || suffix.contains('"')) return null
        // 用 JS 字符串切分取标识，不用正则——避免 \d 这类写法在书源 JSON 里多层转义出错
        val rule = "<js>${BASE_URL_JS}var n=u.substring(u.lastIndexOf(\"/\")+1).split(\".\")[0];" +
            "\"$prefix\"+n+\"$suffix\"</js>"
        return buildString {
            append("【目录地址推导】这两个网址存在固定规律：")
            append("书籍信息页 ").append(infoUrl).append(" → 目录页 ").append(tocUrl)
            append("，其中随书变化的标识是 ").append(id).append("。\n")
            append("书籍信息页里那个入口链接往往没有可定位的 class/id（甚至根本没有入口链接），")
            append("所以 ruleBookInfo.tocUrl 不要留空、也不要写死某一本书的地址")
            append("（写死会让所有书都打开同一本书的目录），")
            append("应当按上面的规律用 JS 取出书籍标识再拼装。本站可直接使用的写法：\n")
            append(rule)
            append("\n（legado JS 规则：baseUrl 是书籍详情页地址；")
            append("先去掉 ? 参数、# 锚点与末尾斜杠，再取末段标识 ").append(id)
            append("，拼成 ").append(prefix).append("标识").append(suffix).append("）")
        }
    }

    /** 书籍标识：书籍信息页地址末段去掉扩展名（数字编号、字母数字 slug 都认） */
    private fun bookIdOf(infoUrl: String): String? {
        val last = cleanUrl(infoUrl).substringAfterLast('/')
        val id = last.substringBefore('.')
        if (id.length < 2 || id.contains('"')) return null
        // index/book/info 这类通用文件名不是书籍标识
        if (id.equals("index", true) || id.equals("book", true) || id.equals("info", true)) return null
        if (!Regex("[A-Za-z0-9_-]+").matches(id)) return null
        return id
    }

    /** 去掉 ? 参数、# 锚点与末尾斜杠，便于比较两个地址的路径关系 */
    private fun cleanUrl(u: String): String =
        u.substringBefore('#').substringBefore('?').trimEnd('/')

    /** legado JS 片段：取到干净的书籍详情页地址（去掉 ? 参数、# 锚点与末尾斜杠） */
    private val BASE_URL_JS =
        "var u=baseUrl.split(\"#\")[0].split(\"?\")[0];while(u.slice(-1)==\"/\")u=u.slice(0,-1);"

    /**
     * 目录页与书籍信息页是同一个地址，只多了页内锚点（.../content/221532 → .../content/221532#chapters）。
     * 这类站点的目录章节就渲染在信息页里，页面上常常根本没有指向它的入口链接，
     * 于是最后用「书籍信息页地址 + #锚点」拼出目录页地址，这是最可靠的兜底。
     */
    private fun deriveAnchorRule(infoUrl: String, tocUrl: String): String? {
        val anchor = tocUrl.substringAfter('#', "").trim()
        if (anchor.isBlank() || anchor.contains('"')) return null
        if (urlKey(infoUrl) != urlKey(tocUrl)) return null
        val rule = "<js>${BASE_URL_JS}u+\"#$anchor\"</js>"
        return buildString {
            append("【目录地址推导】目录页与书籍信息页是同一个地址，只多了页内锚点 #").append(anchor)
            append("：\n  书籍信息页 ").append(infoUrl).append("\n  目录页     ").append(tocUrl).append("\n")
            append("说明这本书的目录章节就渲染在书籍信息页里（锚点只是页内定位，请求到的是同一份 HTML）。")
            append("所以 ruleBookInfo.tocUrl 不要留空，也不要去找页面上不存在的入口链接，")
            append("直接由书籍信息页地址拼上这个锚点即可。本站可直接使用的写法：\n")
            append(rule)
            append("\n（legado JS 规则：baseUrl 是书籍详情页地址；先去掉可能带的 ? 参数和 # 部分，")
            append("再拼上 #").append(anchor).append("；")
            append("ruleToc 按这一页里的完整章节列表容器写，不要只取「最新章节」区块）")
        }
    }

    /** 目录页 = 书籍信息页地址 + 固定后缀（如 /book/1 → /book/1/catalog） */
    private fun deriveSuffixRule(infoUrl: String, tocUrl: String): String? {
        val base = infoUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        if (base.isBlank() || !tocUrl.startsWith(base, true)) return null
        val suffix = tocUrl.substring(base.length)
        if (suffix.isBlank() || suffix == "/" || suffix.contains('"')) return null
        val rule = "<js>${BASE_URL_JS}u+\"$suffix\"</js>"
        return buildString {
            append("【目录地址推导】目录页地址就是书籍信息页地址加上固定后缀：")
            append("书籍信息页 ").append(infoUrl).append(" → 目录页 ").append(tocUrl).append("。\n")
            append("书籍信息页里那个入口链接往往没有可定位的 class/id（甚至根本没有入口链接），")
            append("所以 ruleBookInfo.tocUrl 不要留空、也不要写死某一本书的地址")
            append("（写死会让所有书都打开同一本书的目录）。本站可直接使用的写法：\n")
            append(rule)
            append("\n（legado JS 规则：baseUrl 是书籍详情页地址；先去掉 ? 参数、# 锚点与末尾斜杠，")
            append("再拼上 ").append(suffix).append("）")
        }
    }

    /** URL 归一化：去锚点、去尾斜杠、https 视同 http，用于判断两个地址是否同一页 */
    private fun urlKey(u: String): String {
        var s = u.trim()
        val hash = s.indexOf('#')
        if (hash >= 0) s = s.substring(0, hash)
        s = s.trimEnd('/')
        if (s.startsWith("https://")) s = "http://" + s.removePrefix("https://")
        return s
    }

    /** 宽松归一化：再去掉最后一级路径，用于「/book/1/」与「/book/1/index.html」这类差异 */
    private fun looseUrlKey(u: String): String {
        val k = urlKey(u)
        val i = k.lastIndexOf('/')
        return if (i > 8) k.substring(0, i) else k
    }

    private fun hostOf(url: String): String? =
        runCatching { java.net.URI(url).host }.getOrNull()

    /** 形如域名/网址的输入：www.x.com、m.x.com/mulu/1.html */
    private val URL_LIKE = Regex("^(?:[\\w-]+\\.)+[a-zA-Z]{2,}(?:[:/?#].*)?$")

    /**
     * 判断目录输入框里填的是「网址」还是「目录入口的文字」。
     * 网址直接抓取；不是网址的（如「查看全部章节」「......」「更多章节」）
     * 就当作入口文字，去书籍信息页里匹配对应链接。
     */
    private fun looksLikeUrl(s: String): Boolean {
        if (s.contains(" ") || s.contains("\n")) return false
        return s.startsWith("http://", true) || s.startsWith("https://", true) ||
            s.startsWith("//") || URL_LIKE.matches(s)
    }

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
        val raw = chat(user, 1200) ?: return null
        return parseJsonObject(raw)
    }

    private fun askGenerate(
        pages: List<Page>,
        seedUrl: String,
        searchTemplate: String?,
        meta: Map<String, String>,
        includeExplore: Boolean,
        singleBook: Boolean = false,
        videoMode: Boolean = false
    ): BookSource? {
        // 发现页字段按需拼接：不勾选时完全不出现，JSON 更紧凑、输出截断风险更低
        val exploreFields = if (includeExplore) {
            "         \"exploreUrl\": \"发现页分类列表，「分类名::完整网址」每行一个；网址用采集确认里的真实分类地址（带页码的写 {{page}}）；没有分类导航留空\","
        } else {
            "         \"exploreUrl\": \"\","
        }
        val exploreRule = if (includeExplore) {
            "         \"ruleExplore\": {\"bookList\": \"分类列表页的书籍列表选择器\", \"name\": \"书名规则\", \"author\": \"作者规则\",\n" +
                "           \"kind\": \"\", \"lastChapter\": \"\", \"intro\": \"\", \"coverUrl\": \"\", \"bookUrl\": \"详情页链接规则；结构与搜索结果页类似，可复用其写法\"},"
        } else {
            "         \"ruleExplore\": {},"
        }
        val exploreHardRule = if (includeExplore) {
            "发现页：exploreUrl 必须用采集确认里出现的真实分类地址，ruleExplore 依据「分类列表页」的实际 HTML 写；没有分类页则 exploreUrl 与 ruleExplore 留空；"
        } else {
            "本次不生成发现页：exploreUrl 与 ruleExplore 必须留空；"
        }
        val sb = StringBuilder()
        pages.forEachIndexed { index, page ->
            sb.append("【页面").append(index + 1).append("｜角色：").append(page.role)
                .append("】URL: ").append(page.url).append('\n')
            sb.append("```html\n").append(cleanHtml(page.html)).append("\n```\n\n")
        }
        val metaText = meta.entries.joinToString("\n") { "- ${it.key}：${it.value}" }
        val searchHint = if (singleBook) {
            "（单本模式：这个书源只服务于当前这一本书，不要求搜索与发现。" +
                "searchUrl 与 exploreUrl 必须留空字符串，只把 ruleBookInfo / ruleToc / ruleContent 写对即可）"
        } else {
            searchTemplate?.takeIf { it.isNotBlank() }
                ?: "（未识别出搜索入口，searchUrl 依据页面推断，不确定留空）"
        }
        val base = (if (videoMode) VIDEO_USER_PROMPT else USER_PROMPT)
            .replace("{seed}", seedUrl)
            .replace("{searchHint}", searchHint)
            .replace("{meta}", metaText)
            .replace("{pages}", sb.toString())
            .replace("{exploreFields}", exploreFields)
            .replace("{exploreRule}", exploreRule)
            .replace("{exploreHardRule}", exploreHardRule) + PATTERN_LIBRARY +
            (if (videoMode) VIDEO_HARD_RULES else "")
        // 单本模式：模板里的 searchUrl 字段说明一并置空，避免 AI 困惑着去编搜索地址
        val user = if (singleBook) {
            base.replace(Regex("\"searchUrl\":\\s*\"[^\"]*\""), "\"searchUrl\": \"\"")
        } else {
            base
        }
        val raw = chat(user, 8000) ?: return null
        val json = extractJson(raw) ?: return null
        return GSON.fromJsonObject<BookSource>(json).getOrNull()
    }

    /**
     * AI 修不动时的纯代码兜底——只在 fixRounds 轮 AI 修正全部失败后才被调用：
     * · 目录环节失败，且目录页就是书籍信息页（两者同址）→ 清空 ruleBookInfo.tocUrl，
     *   留空即等于「直接用书籍详情页当目录页」，正是目录与信息页同页的站点要的写法；
     * · 搜索环节失败 → 换成从站内搜索表单推导出的地址（带表单真实的 method 与隐藏字段）。
     * 改完由调用方重新验证，并再给同样轮数的 AI 修正机会，不会直接判失败。
     */
    private fun applyFallbacks(
        source: BookSource,
        report: List<VerifyItem>,
        homeHtml: String,
        seed: String,
        keyword: String,
        tocUrl: String,
        infoUrl: String?,
        contentUrl: String?
    ): Boolean {
        var changed = false
        val failed = report.filter { !it.ok && it.blocking }.map { it.step }.toSet()
        if ("目录" in failed && tocUrl.isNotBlank()) {
            // 目录就是书籍信息页，或目录页地址被填成了第一章正文页
            // （很多站的「开始阅读」按钮 href 就是第一章，填进去目录必然为空）
            val sameAsInfo = !infoUrl.isNullOrBlank() && cleanUrl(tocUrl) == cleanUrl(infoUrl!!)
            val sameAsContent =
                !contentUrl.isNullOrBlank() && cleanUrl(tocUrl) == cleanUrl(contentUrl!!)
            if (sameAsInfo || sameAsContent) {
                val info = source.getBookInfoRule()
                val cur = info.tocUrl
                // 只清「把网址写死」这种必然出错的写法（写死会让所有书打开同一页）；
                // 选择器 / JS 规则可能是 AI 写对的结果，不能一并清掉
                if (!cur.isNullOrBlank() && cur.trim().startsWith("http")) {
                    info.tocUrl = null
                    changed = true
                }
            }
        }
        if ("搜索" in failed) {
            // 优先用「多地址 && 组合」：legado 会依次尝试，容错比单一地址高
            // （社区书源里 26 站这么写）；拿不到多个候选才退回单一最优地址
            val multi = deriveSearchUrls(homeHtml, seed).take(2)
                .joinToString("&&").takeIf { it.isNotBlank() }
            val fallback = multi ?: bestSearchCandidate(homeHtml, seed, keyword)?.first
            fallback?.let { tpl ->
                if (source.searchUrl != tpl) {
                    source.searchUrl = tpl
                    changed = true
                }
            }
        }
        return changed
    }

    /** 让 AI 修正验证失败的环节（附失败原因与对应页面的真实 HTML） */
    private fun fixSource(
        source: BookSource,
        report: List<VerifyItem>,
        pages: List<Page>,
        meta: Map<String, String>,
        videoMode: Boolean = false
    ): BookSource? {
        val failed = report.filter { !it.ok && it.blocking }
        if (failed.isEmpty()) return null
        val current = runCatching { GSON.toJson(source) }.getOrDefault("")
        val sb = StringBuilder()
        failed.forEach { f ->
            sb.append("【").append(f.step).append("】").append(f.message).append('\n')
            val page = when (f.step) {
                "搜索" -> pages.firstOrNull { it.role.startsWith("搜索") }
                "详情" -> pages.firstOrNull { it.role.contains("详情") }
                "目录" -> pages.firstOrNull { it.role.startsWith("目录") }
                    ?: pages.firstOrNull { it.role.contains("详情") }
                "播放" -> pages.firstOrNull { it.role.startsWith("播放") }
                "正文" -> pages.firstOrNull { it.role.startsWith("正文") }
                    ?: pages.firstOrNull { it.role.startsWith("目录") }
                "发现" -> pages.firstOrNull { it.role.startsWith("分类列表页") }
                else -> null
            }
            if (page != null) {
                sb.append("相关页面：").append(page.role).append("  URL: ").append(page.url).append('\n')
                sb.append("```html\n").append(cleanHtml(page.html)).append("\n```\n")
            }
        }
        val hintsText = if (videoMode) {
            val searchPage = pages.firstOrNull { it.role.startsWith("搜索") }
            val episodePage = pages.firstOrNull { it.role == "目录页" }
            val playPage = pages.firstOrNull { it.role == "播放页" }
            buildVideoCodeHints(
                searchPage?.html.orEmpty(),
                searchPage?.url.orEmpty(),
                "",
                episodePage?.html,
                episodePage?.url.orEmpty(),
                playPage?.html
            ).entries.joinToString("\n") { "- ${it.key}：${it.value}" }
        } else {
            buildHints(meta)
        }
        val user = FIX_PROMPT
            .replace("{fails}", sb.toString())
            .replace("{source}", current)
            .replace("{hints}", hintsText) + PATTERN_LIBRARY +
            (if (videoMode) VIDEO_HARD_RULES else "")
        val raw = chat(user, 8000) ?: return null
        val json = extractJson(raw) ?: return null
        val aiJson = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        // 只挑出「失败环节」对应的字段做补丁，深合并进现有书源——
        // 已通过的环节规则原样保留，AI 即便多写也不会把好的改坏
        val patch = patchForSteps(aiJson, failed.map { it.step }.toSet())
        if (patch.size() == 0) return null
        return mergePatch(source, patch)
    }

    /**
     * 把 AI 返回的书源里，只取出与「失败环节」对应的顶层字段，做成补丁。
     * 即便 AI 返回了整份 JSON，我们也只采纳失败环节相关的字段，杜绝它顺手改坏已通过的规则。
     */
    private fun patchForSteps(aiJson: JsonObject, failedSteps: Set<String>): JsonObject {
        // 环节名 → 该环节在书源 JSON 里的顶层字段
        val keyMap = mapOf(
            "搜索" to listOf("searchUrl", "ruleSearch"),
            "详情" to listOf("ruleBookInfo"),
            "目录" to listOf("ruleToc"),
            "播放" to listOf("ruleContent"),
            "正文" to listOf("ruleContent"),
            "发现" to listOf("exploreUrl")
        )
        val patch = JsonObject()
        for (step in failedSteps) {
            keyMap[step]?.forEach { key ->
                if (aiJson.has(key)) patch.add(key, aiJson[key].deepCopy())
            }
        }
        return patch
    }

    /** 深合并补丁进现有书源：只覆盖补丁里的字段，其余（已通过）字段原样保留 */
    private fun mergePatch(base: BookSource, patch: JsonObject): BookSource {
        val baseJson = GSON.toJsonTree(base).asJsonObject.deepCopy()
        fun merge(into: JsonObject, from: JsonObject) {
            for ((k, v) in from.entrySet()) {
                // 封面规则完全由内置预设生成（见 ensureCoverRules / buildListCoverChain）；
                // AI 补丁里写空值或编造选择器都不采纳，避免把列表封面规则冲掉
                if (k == "coverUrl") continue
                if (v.isJsonObject && into.has(k) && into[k].isJsonObject) {
                    merge(into[k].asJsonObject, v.asJsonObject)
                } else {
                    into.add(k, v)
                }
            }
        }
        merge(baseJson, patch)
        return GSON.fromJson(baseJson, BookSource::class.java) ?: base
    }

    /**
     * 把纯代码已经算出的结论（目录入口候选 / 翻页形态 / JSON 章节列表 / 章节区块）汇总给 AI。
     * 修正阶段同样要给——否则第一次生成没听提示、验证失败后，AI 还是看不到这些白算出来的结果。
     */
    private fun buildHints(meta: Map<String, String>): String {
        val wanted = listOf(
            "目录入口", "目录分页", "章节列表候选", "章节区块", "目录选择",
            "目录地址推导", "目录地址正则推导"
        )
        val lines = ArrayList<String>()
        for ((k, v) in meta) {
            if (v.isBlank()) continue
            if (wanted.any { k.contains(it) }) lines.add("- $k：$v")
        }
        return lines.joinToString("\n").ifBlank { "（本次没有额外的纯代码结论）" }
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

    // ==================== 目录全候选竞速（每次生成都执行，源自 5541 书源统计特征） ====================

    /** 「第X章/节/回」式章节标题——数字部分必须带捕获组，chapterNo 要取它 */
    private val CHAPTER_TITLE_RE = Regex("第\\s*([0-9零〇一二两三四五六七八九十百千万]+)\\s*[章节回]")

    /** 数字开头的章节名（有些站章节名就是「1大器晚成系统开启！」这种，没有「第X章」） */
    private val NUM_CHAPTER_RE = Regex("^\\s*\\d{1,5}\\s*[\\u4e00-\\u9fa5A-Za-z（(]")

    /** 是否像章节标题（两种形态都认） */
    private fun isChapterText(t: String): Boolean =
        t.isNotBlank() && (CHAPTER_TITLE_RE.containsMatchIn(t) || NUM_CHAPTER_RE.containsMatchIn(t))

    /** 取章节序号：优先「第N章」，其次「数字开头」；取不到返回 null */
    private fun chapterNo(text: String): Int? {
        CHAPTER_TITLE_RE.find(text)?.let { m ->
            val raw = m.groupValues[1]
            raw.toIntOrNull()?.let { return it }
            return cnToInt(raw)
        }
        return Regex("^\\s*(\\d{1,5})").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 简单中文数字转阿拉伯数字（一~万，够目录排序用） */
    private fun cnToInt(s: String): Int? {
        if (s.isBlank()) return null
        val map = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (s == "十") return 10
        var total = 0
        var cur = 0
        for (ch in s) {
            when {
                ch in map -> cur = map.getValue(ch)
                ch == '十' -> { total += (if (cur == 0) 1 else cur) * 10; cur = 0 }
                ch == '百' -> { total += (if (cur == 0) 1 else cur) * 100; cur = 0 }
                ch == '千' -> { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                ch == '万' -> { total = (total + cur) * 10000; cur = 0 }
                else -> return null
            }
        }
        return total + cur
    }

    /** 最长「连续自增」章节段：返回 (长度, 起始章号)，用于识别完整目录、避开最新章/推荐位 */
    private fun longestRun(nums: List<Int>): Pair<Int, Int?> {
        if (nums.isEmpty()) return 0 to null
        var best = 1
        var cur = 1
        var bestStart = nums[0]
        var curStart = nums[0]
        for (i in 1 until nums.size) {
            if (nums[i] == nums[i - 1] + 1) {
                cur++
                if (cur > best) {
                    best = cur
                    bestStart = curStart
                }
            } else {
                cur = 1
                curStart = nums[i]
            }
        }
        return best to bestStart
    }

    /**
     * legado 选择器 → CSS（仅供 Jsoup 粗测用）。
     * 支持 class./id./tag./裸 css 与多级 @ 串联；JS/JSON/XPath/属性步骤一律返回 null（不参与粗测）
     */
    private fun legadoToCss(sel: String): String? {
        val first = sel.trim().split("||").firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return null
        if (first.contains("<js>") || first.contains("@js:") || first.startsWith("$") ||
            first.startsWith("//") || first.startsWith("@")
        ) return null
        val parts = first.split("@").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        fun clsOf(prefix: String) = prefix.removePrefix("class.").trim()
            .split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(".")
        val sb = StringBuilder()
        val head = parts[0]
        when {
            head.startsWith("class.") -> sb.append(".").append(clsOf(head))
            head.startsWith("id.") -> sb.append("#").append(head.removePrefix("id."))
            head.startsWith("tag.") -> sb.append(head.removePrefix("tag."))
            head.startsWith(".") || head.startsWith("#") -> sb.append(head)
            head.matches(Regex("[A-Za-z][\\w-]*")) -> sb.append(head)
            else -> return null
        }
        for (p in parts.drop(1)) {
            when {
                p.startsWith("tag.") -> sb.append(" ").append(p.removePrefix("tag."))
                p.startsWith("class.") -> sb.append(".").append(clsOf(p))
                p.startsWith(".") -> sb.append(" ").append(p)
                p.matches(Regex("[A-Za-z][\\w-]*")) -> sb.append(" ").append(p)
                else -> break
            }
        }
        return sb.toString()
    }

    /** 收集一批元素里的章节标题文字（元素本身是 <a> 时也算） */
    private fun chapterTextsOf(els: Collection<Element>): List<String> {
        val out = ArrayList<String>()
        for (e in els) {
            if (e.tagName() == "a" && isChapterText(e.text())) {
                out.add(e.text())
            } else {
                e.select("a[href]").forEach { a -> if (isChapterText(a.text())) out.add(a.text()) }
            }
        }
        return out
    }

    /** 目录候选：选择器 / 连续段长度 / 段起点 / 章节条数 */
    private data class TocCand(val sel: String, val run: Int, val start: Int, val count: Int)

    /** 用 Jsoup 粗测一个 chapterList 选择器在页面上的表现 */
    private fun scoreChapterSel(html: String, sel: String?): TocCand? {
        if (sel.isNullOrBlank()) return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        // 用 selectForScore：支持 !排除 / .N索引 / XPath，否则这些形态无法参与竞速
        val els = selectForScore(doc, sel) ?: return null
        val texts = chapterTextsOf(els)
        if (texts.isEmpty()) return null
        val nums = texts.mapNotNull { chapterNo(it) }
        val (run, start) = longestRun(nums)
        return TocCand(sel, run, start ?: Int.MAX_VALUE, texts.size)
    }

    /** 更优判定：连续段更长 > 段起点更接近第1章 > 章节更多 */
    private fun betterCand(a: TocCand, b: TocCand): Boolean = when {
        a.run != b.run -> a.run > b.run
        a.start != b.start -> a.start < b.start
        else -> a.count > b.count
    }

    /** 候选竞速：全部候选一起比，取「章节最完整」的那个 */
    private fun pickBestChapterList(html: String, cands: List<String>): TocCand? {
        var best: TocCand? = null
        for (c in cands) {
            val s = scoreChapterSel(html, c) ?: continue
            if (s.count == 0) continue
            val b = best
            if (b == null || betterCand(s, b)) best = s
        }
        return best
    }

    /** 给容器找一个「全局章节数不膨胀」的 legado 选择器（避免 class.box 命中一堆无关元素） */
    private fun candidateCss(el: Element, doc: org.jsoup.nodes.Document): String? {
        val local = chapterTextsOf(listOf(el)).size
        if (local <= 0) return null
        val variants = ArrayList<String>()
        if (el.id().isNotBlank()) variants.add("id." + el.id())
        val cls = el.classNames().filter { it.matches(Regex("[A-Za-z_][\\w-]*")) }
        if (cls.isNotEmpty()) {
            variants.add("class." + cls.joinToString("."))
            variants.add("class." + cls.first())
            variants.add("tag.${el.tagName()}.${cls.first()}")
        }
        variants.add("tag." + el.tagName())
        var tight: Pair<String, Int>? = null
        for (v in variants) {
            val css = legadoToCss(v) ?: continue
            val got = runCatching { doc.select(css) }.getOrNull() ?: continue
            val n = chapterTextsOf(got).size
            if (n < local) continue
            if (n == local) return v
            val t = tight
            if (t == null || n < t.second) tight = v to n
        }
        return tight?.first
    }

    /**
     * 「最新章节 / 排行榜 / 推荐位 / 最近更新」这类区块不是完整目录，
     * 即使章号连续也不能当目录用——否则会把书源从「完整目录」换成「十几条最新章」。
     */
    private fun isNotTocBlock(el: Element): Boolean {
        val words = listOf(
            "最新", "最近更新", "排行", "推荐", "热门", "猜你喜欢",
            "zuixin", "paihang", "tuijian", "remen", "rank", "hot"
        )
        var p: Element? = el
        var d = 0
        while (p != null && d < 3) {
            val id = p.id().lowercase()
            val cls = p.className().lowercase()
            if (words.any { id.contains(it) || cls.contains(it) }) return true
            p = p.parent()
            d++
        }
        // 区块上方常放标题（如 <h3>最新章节</h3>、<h2>热门推荐</h2>）
        val headText = el.previousElementSibling()?.text().orEmpty() +
            el.parent()?.children()?.firstOrNull()?.takeIf { it.tagName().startsWith("h") }?.text().orEmpty()
        return words.any { headText.contains(it) }
    }

    /** 纯代码推导章节列表候选（镜像 5541 书源里稳定的容器写法） */
    private fun autoChapterCandidates(html: String, baseUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val links = doc.select("a[href]").filter { isChapterText(it.text()) }
        if (links.size < 3) return emptyList()
        val els = LinkedHashSet<Element>()
        for (a in links) {
            var p: Element? = a.parent()
            var d = 0
            while (p != null && d < 5) {
                els.add(p)
                p = p.parent()
                d++
            }
        }
        val cands = ArrayList<TocCand>()
        for (el in els) {
            val texts = chapterTextsOf(listOf(el))
            if (texts.size < 3) continue
            // 最新章节/排行/推荐区块一律不参选
            if (isNotTocBlock(el)) continue
            val css = candidateCss(el, doc) ?: continue
            val nums = texts.mapNotNull { chapterNo(it) }
            val (run, start) = longestRun(nums)
            cands.add(TocCand("$css@tag.a", run, start ?: Int.MAX_VALUE, texts.size))
        }
        return cands.sortedWith(
            compareByDescending<TocCand> { it.run }.thenBy { it.start }.thenByDescending { it.count }
        ).map { it.sel }.distinct().take(6)
    }

    /**
     * 目录全候选竞速：把 AI 给的 chapterList 与纯代码推导的所有候选容器，
     * 一起放到真实目录页 HTML 上比拼，取「章节最完整」的那个。
     *
     * 安全门槛（关键）——不能把原本可用的书源改坏：
     * · 只在「目录页」上比（页面就是书源真正要用的那个目录页，不跨页乱比）；
     * · AI 规则能粗测时，候选必须「条数不少于 AI 且连续段严格更长」才允许替换；
     * · AI 规则取不到时才允许候选顶上，且候选自身要像个完整目录（≥8 条）；
     * · JS/JSON 等无法粗测的规则一律不动。
     */
    private fun refineTocRules(source: BookSource, pages: List<Page>, onProgress: (String) -> Unit) {
        val toc = source.getTocRule()
        val cur = toc.chapterList
        val page = pages.firstOrNull { it.role == "目录页" && it.html.isNotBlank() } ?: return
        val cands = ArrayList<String>()
        if (!cur.isNullOrBlank()) cands.add(cur)
        cands.addAll(autoChapterCandidates(page.html, page.url))
        if (cands.size <= 1) return
        val curScore = scoreChapterSel(page.html, cur)
        // 无法粗测的非空规则（JS/JSON/XPath）原样保留，避免误伤
        if (!cur.isNullOrBlank() && curScore == null) return
        val best = pickBestChapterList(page.html, cands) ?: return

        // 决定采用哪条：候选必须明显更优才替换，否则沿用 AI 原规则（不能把原本能用的改坏）
        val chosen: String
        val chosenScore: TocCand
        when {
            cur.isNullOrBlank() -> {
                if (best.count < 8 || best.run < 3) return
                chosen = best.sel
                chosenScore = best
            }
            best.sel == cur -> {
                chosen = cur
                chosenScore = curScore!!
            }
            best.count >= curScore!!.count && best.run > curScore.run -> {
                chosen = best.sel
                chosenScore = best
            }
            else -> {
                chosen = cur
                chosenScore = curScore
            }
        }

        // 「排除前 N 条」优化：完整目录前常混着「最新章节」（连续段起点 > 0），
        // 这是社区书源里跳过最新章节的标准手段，比整体换容器更精准。
        // 关键：无论最终用的是 AI 规则还是候选都要试——否则绝大多数场景根本轮不到它。
        var finalSel = chosen
        if (chosenScore.start > 0 && chosenScore.start <= 12) {
            withExcludePrefix(chosen, chosenScore.start)?.let { trimmed ->
                val t = scoreChapterSel(page.html, trimmed)
                if (t != null && t.count > 0 &&
                    (t.run > chosenScore.run ||
                        (t.run == chosenScore.run && t.start < chosenScore.start))
                ) {
                    finalSel = trimmed
                    onProgress("目录优选：排除前 ${chosenScore.start} 条 → $trimmed（连续 ${t.run} 章）")
                }
            }
        }
        if (finalSel != cur) {
            toc.chapterList = finalSel
            if (finalSel == chosen) {
                onProgress("目录优选：$chosen 胜出（连续 ${chosenScore.run} 章 / 共 ${chosenScore.count} 条）")
            }
        }
    }

    // ==================== 搜索结果全形态兜底 ====================
    // 目录侧有一整套纯代码候选竞速，搜索侧此前完全没有——AI 的 bookList 写错就只能等 AI 修正。
    // 这里补上同一套机制：容器候选 + 竞速 + 「排除前 N 条」，同样遵守「不能改坏」的门槛。

    /** 搜索候选：选择器 / 条目数 / 其中能取到详情页链接的条数 */
    private data class SearchCand(val sel: String, val count: Int, val withUrl: Int)

    /**
     * 像「书籍详情页」的链接判定。
     * 比 countBookLinks 宽松：原判定要求路径含 2 位以上数字，会把 /book/abc123/、
     * /novel/xiyouji/ 这类非数字 ID 的站点误杀成「搜索不可用」。
     */
    private fun looksLikeBookUrl(u: String): Boolean {
        if (!u.startsWith("http")) return false
        if (Regex(
                "\\.(css|js|json|jpg|jpeg|png|gif|webp|svg|ico|txt|zip|apk|mp3|mp4)(\\?|$)",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(u)
        ) return false
        val path = runCatching { java.net.URI(u).path }.getOrNull() ?: return false
        val seg = path.trim('/').split('/').filter { it.isNotBlank() }
        if (seg.isEmpty()) return false // 首页本身不算书籍页
        val bad = listOf(
            "login", "register", "search", "rank", "top", "bookcase", "user",
            "about", "contact", "help", "index", "default", "tag", "author"
        )
        if (seg.any { it.lowercase() in bad } && !Regex("\\d{2,}").containsMatchIn(path)) return false
        return true
    }

    /** 元素里指向书籍详情页的链接数 */
    private fun bookLinksOf(el: Element): Int {
        var n = 0
        if (el.tagName() == "a" && looksLikeBookUrl(el.attr("abs:href"))) n++
        for (a in el.select("a[href]")) {
            if (looksLikeBookUrl(a.attr("abs:href"))) n++
        }
        return n
    }

    /** 给容器找一个「全局条目数不膨胀」的 legado 选择器（按书籍链接计数，与目录侧同理） */
    private fun candidateCssByLinks(el: Element, doc: org.jsoup.nodes.Document): String? {
        val local = bookLinksOf(el)
        if (local <= 0) return null
        val variants = ArrayList<String>()
        if (el.id().isNotBlank()) variants.add("id." + el.id())
        val cls = el.classNames().filter { it.matches(Regex("[A-Za-z_][\\w-]*")) }
        if (cls.isNotEmpty()) {
            variants.add("class." + cls.joinToString("."))
            variants.add("class." + cls.first())
            variants.add("tag.${el.tagName()}.${cls.first()}")
        }
        variants.add("tag." + el.tagName())
        var tight: Pair<String, Int>? = null
        for (v in variants) {
            val css = legadoToCss(v) ?: continue
            val got = runCatching { doc.select(css) }.getOrNull() ?: continue
            var n = 0
            for (g in got) n += bookLinksOf(g)
            if (n < local) continue
            if (n == local) return v
            val t = tight
            if (t == null || n < t.second) tight = v to n
        }
        return tight?.first
    }

    /** JSON 搜索结果候选：书籍数组的 JSONPath 及各字段 */
    private data class JsonBookCand(
        val list: String,
        val name: String?,
        val author: String?,
        val url: String?,
        val cover: String?
    )

    /**
     * JSON 搜索接口（API 型站点，社区书源里 458 站在用）：找出「对象数组且同时含书名与链接字段」
     * 的路径，直接给出 bookList / name / author / bookUrl / coverUrl 的 JSONPath。
     */
    private fun jsonBookCandidates(html: String): JsonBookCand? {
        val t = html.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return null
        val root = runCatching { JsonParser.parseString(t) }.getOrNull() ?: return null
        val nameKeys = listOf("name", "title", "bookname", "book_name", "novelname")
        val authorKeys = listOf("author", "authorname", "author_name", "writer")
        val urlKeys = listOf("url", "link", "id", "bookid", "book_id", "novelid")
        val coverKeys = listOf("cover", "coverurl", "cover_url", "pic", "img", "image")
        var found: JsonBookCand? = null
        fun walk(el: JsonElement, path: String, depth: Int) {
            if (depth > 6 || found != null) return
            when {
                el.isJsonObject -> el.asJsonObject.entrySet().forEach { (k, v) ->
                    walk(v, if (path.isEmpty()) k else "$path.$k", depth + 1)
                }
                el.isJsonArray -> {
                    val arr = el.asJsonArray
                    if (arr.size() >= 2 && arr.all { it.isJsonObject }) {
                        val keys = arr[0].asJsonObject.keySet()
                        fun pick(cands: List<String>) =
                            keys.firstOrNull { it.lowercase() in cands }
                        val nk = pick(nameKeys)
                        val uk = pick(urlKeys)
                        if (nk != null && uk != null) {
                            val base = if (path.isEmpty()) "\$" else "\$.$path"
                            found = JsonBookCand(
                                "$base[*]",
                                "$base[*].$nk",
                                pick(authorKeys)?.let { "$base[*].$it" },
                                "$base[*].$uk",
                                pick(coverKeys)?.let { "$base[*].$it" }
                            )
                            return
                        }
                    }
                    if (arr.size() > 0) walk(arr[0], path, depth + 1)
                }
            }
        }
        walk(root, "", 0)
        return found
    }

    /** 纯代码推导搜索结果列表候选：找「含最多书籍详情链接」的容器 */
    private fun autoSearchCandidates(html: String, baseUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val links = doc.select("a[href]").filter { looksLikeBookUrl(it.attr("abs:href")) }
        if (links.size < 2) return emptyList()
        val els = LinkedHashSet<Element>()
        for (a in links) {
            var p: Element? = a.parent()
            var d = 0
            while (p != null && d < 4) {
                els.add(p)
                p = p.parent()
                d++
            }
        }
        val cands = ArrayList<SearchCand>()
        for (el in els) {
            val n = bookLinksOf(el)
            if (n < 2) continue
            if (isNotTocBlock(el)) continue // 排行榜/推荐位同样不是搜索结果
            val css = candidateCssByLinks(el, doc) ?: continue
            cands.add(SearchCand("$css@tag.a", n, n))
        }
        return cands.sortedWith(
            compareByDescending<SearchCand> { it.withUrl }.thenByDescending { it.count }
        ).map { it.sel }.distinct().take(6)
    }

    private fun scoreSearchSel(html: String, baseUrl: String, sel: String?): SearchCand? {
        if (sel.isNullOrBlank()) return null
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return null
        val els = selectForScore(doc, sel) ?: return null
        if (els.isEmpty()) return null
        var withUrl = 0
        for (e in els) if (bookLinksOf(e) > 0) withUrl++
        return SearchCand(sel, els.size, withUrl)
    }

    private fun pickBestSearchList(html: String, baseUrl: String, cands: List<String>): SearchCand? {
        var best: SearchCand? = null
        for (c in cands) {
            val s = scoreSearchSel(html, baseUrl, c) ?: continue
            val b = best
            if (b == null || s.withUrl > b.withUrl || (s.withUrl == b.withUrl && s.count > b.count)) {
                best = s
            }
        }
        return best
    }

    /**
     * 搜索结果容器竞速：AI 给的 bookList 与纯代码候选一起在真实搜索页上比，
     * 取「能取到最多书籍详情链接」的那个。门槛同目录侧：候选必须严格更优才替换。
     */
    private fun refineSearchRules(source: BookSource, pages: List<Page>, onProgress: (String) -> Unit) {
        val search = source.getSearchRule()
        val cur = search.bookList
        val page = pages.firstOrNull { it.role.startsWith("搜索") && it.html.isNotBlank() } ?: return
        // JSON 搜索接口：AI 没写 bookList 时，直接用纯代码算出的 JSONPath（API 型站点常见）
        if (cur.isNullOrBlank()) {
            jsonBookCandidates(page.html)?.let { c ->
                search.bookList = c.list
                search.name = c.name
                search.author = c.author
                search.bookUrl = c.url
                search.coverUrl = c.cover
                onProgress("搜索优选：JSON 接口，采用 ${c.list}")
                return
            }
        }
        val cands = ArrayList<String>()
        if (!cur.isNullOrBlank()) cands.add(cur)
        cands.addAll(autoSearchCandidates(page.html, page.url))
        if (cands.size <= 1) return
        val curScore = scoreSearchSel(page.html, page.url, cur)
        // 无法粗测的非空规则（JS/JSON）原样保留，避免误伤
        if (!cur.isNullOrBlank() && curScore == null) return
        val best = pickBestSearchList(page.html, page.url, cands) ?: return
        when {
            cur.isNullOrBlank() -> {
                if (best.withUrl < 2) return
                search.bookList = best.sel
                onProgress("搜索优选：${best.sel}（${best.withUrl} 条书链接）")
            }
            best.sel == cur -> Unit
            best.withUrl > (curScore?.withUrl ?: 0) -> {
                search.bookList = best.sel
                onProgress("搜索优选：${best.sel} 胜出（${best.withUrl} 条书链接 / 共 ${best.count} 条）")
            }
        }
        // 「排除前 N 条」优化：搜索结果常把表头/广告行混进列表
        // （社区书源里 14% 的 bookList 带 ! 排除，但 AI 几乎不会主动写）
        val adopted = search.bookList
        if (!adopted.isNullOrBlank() && !adopted.contains('!')) {
            val doc = runCatching { Jsoup.parse(page.html, page.url) }.getOrNull() ?: return
            val els = selectForScore(doc, adopted) ?: return
            var lead = 0
            for (e in els) {
                if (bookLinksOf(e) > 0) break
                lead++
            }
            if (lead > 0 && lead <= 5) {
                val trimmed = adopted + "!" + (0 until lead).joinToString(":")
                val t = scoreSearchSel(page.html, page.url, trimmed)
                val now = scoreSearchSel(page.html, page.url, adopted)
                if (t != null && (now == null || t.withUrl > now.withUrl)) {
                    search.bookList = trimmed
                    onProgress("搜索优选：排除前 $lead 条 → $trimmed")
                }
            }
        }
    }

    // ==================== 目录解析全形态兜底 ====================
    // 形态清单来自 5641 个社区书源的统计（含长尾）：只要书源里出现过的写法，这里都能生成对应兜底。
    // 原则：原逻辑优先——只在 AI 规则取不到或明显不完整时才用兜底覆盖，且覆盖后必须能重验。

    /** 从验证报告里取出目录环节的章节数（用于比较兜底前后谁取到的章节更多） */
    private fun chapterCountOf(report: List<VerifyItem>): Int {
        val msg = report.firstOrNull { it.step == "目录" }?.message.orEmpty()
        return Regex("共\\s*(\\d+)\\s*章").find(msg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * 最后手段：目录实测 0 章时，不再顾忌 AI 原规则，直接用纯代码候选顶上。
     * 目录页找不到就用详情页（很多站目录与详情同页），取「连续段 ≥3」的候选。
     */
    private fun lastResortToc(source: BookSource, pages: List<Page>): Boolean {
        val page = pages.firstOrNull { it.role == "目录页" && it.html.isNotBlank() }
            ?: pages.firstOrNull { it.role.contains("详情") && it.html.isNotBlank() }
            ?: return false
        val cands = autoChapterCandidates(page.html, page.url)
        if (cands.isEmpty()) return false
        val best = pickBestChapterList(page.html, cands) ?: return false
        if (best.count < 3 || best.run < 3) return false
        source.getTocRule().chapterList = best.sel
        return true
    }

    /** 翻页候选：规则 + 说明 + 优先级（大者优先） */
    private data class PagerCand(val rule: String, val desc: String, val score: Int)

    /** 目录入口候选：规则 + 说明 */
    private data class EntryCand(val rule: String, val desc: String)

    private val PAGER_WORDS = listOf("下一页", "下页", "后页", "下一頁", "後頁", "末页", "尾页")

    private val TOC_ENTRY_WORDS = listOf(
        "章节目录", "查看完整目录", "查看全部章节", "全部章节列表", "查看目录",
        "完整目录", "章节列表", "进入目录", "全部章节", "更多目录", "查看全部", "目录"
    )

    /** 简易 XPath → CSS（仅供粗测评分用，复杂 XPath 直接放弃） */
    private fun xpathToCss(xp: String): String? {
        var s = xp.trim()
        for (suf in listOf("/@href", "/@src", "/@title", "/text()")) {
            if (s.endsWith(suf)) s = s.removeSuffix(suf)
        }
        if (!s.startsWith("//")) return null
        if (s.contains("::") || s.contains("contains(") || s.contains("following-sibling")) return null
        val parts = s.removePrefix("//").split("/").filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty()) return null
        val css = ArrayList<String>()
        for (p in parts) {
            val m = Regex("^(\\*|[A-Za-z][\\w-]*)?((?:\\[[^\\]]+\\])*)$").find(p) ?: return null
            val tag = m.groupValues[1]
            val preds = m.groupValues[2]
            if (preds.contains("[")) {
                val fixed = Regex("\\[@([\\w-]+)=(['\"]?)([^'\"\\]]*)\\2\\]").replace(preds) { r ->
                    "[${r.groupValues[1]}=\"${r.groupValues[3]}\"]"
                }
                if (fixed.contains("@")) return null
                css.add((if (tag.isEmpty() || tag == "*") "" else tag) + fixed)
            } else {
                css.add(if (tag.isEmpty() || tag == "*") "" else tag)
            }
        }
        val joined = css.filter { it.isNotBlank() }.joinToString(" ")
        return joined.ifBlank { null }
    }

    /**
     * 把含 legado 修饰（!排除 / .N索引 / XPath）的选择器落到 Jsoup 上：先转 CSS 再按索引过滤。
     * legadoToCss 不认这些修饰，直接拼接会选不到元素，导致含 `!` 的规则无法参与竞速评分。
     */
    private fun selectForScore(doc: org.jsoup.nodes.Document, sel: String): List<Element>? {
        val first = sel.split("||").firstOrNull()?.trim().orEmpty()
        if (first.isBlank()) return null
        val parts = first.split("@").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val plain = parts.joinToString("@") {
            it.replace(Regex("![\\d:-]+$"), "").replace(Regex("\\.-?\\d+$"), "")
        }
        val css = (if (first.startsWith("//")) xpathToCss(plain) else legadoToCss(plain))
            ?: return null
        var els: List<Element> = runCatching { doc.select(css) }.getOrNull() ?: return null
        val last = parts.last()
        // .N 索引：legado 里表示取第 N 个同源容器（负数为倒数）
        Regex("\\.(-?\\d+)$").find(last)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val i = if (n < 0) els.size + n else n
            els = if (i in els.indices) listOf(els[i]) else emptyList()
        }
        // ! 排除：!0:1:2 去掉前几条，!-1 去掉最后一条
        Regex("!([\\d:-]+)$").find(last)?.let { m ->
            val excl = m.groupValues[1].split(":").mapNotNull { it.trim().toIntOrNull() }
            if (excl.isNotEmpty()) {
                val size = els.size
                els = els.filterIndexed { i, _ ->
                    excl.none { e -> (if (e < 0) size + e else e) == i }
                }
            }
        }
        return els
    }

    /** 生成「排除前 N 条」写法（跳过最新章节区块）：容器@条目!0:1:…:N-1 */
    private fun withExcludePrefix(sel: String, n: Int): String? {
        if (n <= 0 || n > 20) return null
        if (sel.contains('!')) return null
        return sel + "!" + (0 until n).joinToString(":")
    }

    /**
     * 目录翻页全形态探测（覆盖书源里出现过的所有翻页写法）：
     * ① 文本翻页 text.下一页@href（含翻页容器 class.X@tag.a@href）
     * ② 下拉选页 option@value / class.X@option!0@value
     * ③ 数字页码容器 class.X@tag.a@href（含末页负索引 .-1）
     * ④ JS 按总页数拼页（从页码链接推断地址模式与总页数）
     */
    private fun detectTocPagers(html: String, baseUrl: String): List<PagerCand> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, PagerCand>()
        fun add(rule: String, desc: String, score: Int) {
            val r = rule.trim()
            if (r.isNotBlank() && !out.containsKey(r)) out[r] = PagerCand(r, desc, score)
        }

        // ① 文本翻页
        val hit = doc.select("a[href]").firstOrNull { a -> PAGER_WORDS.any { a.text().trim() == it } }
            ?: doc.select("a[href]").firstOrNull { a -> PAGER_WORDS.any { a.text().trim().contains(it) } }
        if (hit != null) {
            val w = PAGER_WORDS.first { hit.text().trim().contains(it) }
            add("text.$w@href", "文本翻页「${hit.text().trim()}」", 100)
            val pcls = hit.parent()?.className()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
            if (!pcls.isNullOrBlank()) add("class.$pcls@tag.a@href", "翻页容器 class.$pcls", 92)
        }

        // ② 下拉选页（select + option，社区书源里 189 站在用）
        val selBox = doc.select("select").firstOrNull { it.select("option[value]").size >= 3 }
        if (selBox != null) {
            val firstText = selBox.select("option").firstOrNull()?.text()?.trim().orEmpty()
            // 第一项是「请选择/第 1 页」这类占位时用 !0 排除
            val excl = if (firstText.isBlank() || firstText.contains("选择") || firstText.contains("请")) "!0" else ""
            val head = when {
                selBox.id().isNotBlank() -> "id.${selBox.id()}"
                selBox.className().isNotBlank() ->
                    "class." + selBox.className().trim().split(Regex("\\s+")).first()
                else -> ""
            }
            add(
                if (head.isBlank()) "option$excl@value" else "$head@option$excl@value",
                "下拉选页（${selBox.select("option").size} 项）", 96
            )
        }

        // ③ 数字页码容器（class/id 含 page|paginate|pagelist）
        val box = doc.select("div,ul,ol,span,td,p,center").firstOrNull { el ->
            val key = (el.className() + " " + el.id()).lowercase()
            (key.contains("page") || key.contains("paginate") || key.contains("pagelist")) &&
                el.select("a[href]").size >= 3
        }
        if (box != null) {
            val head = when {
                box.id().isNotBlank() -> "id.${box.id()}"
                box.className().isNotBlank() ->
                    "class." + box.className().trim().split(Regex("\\s+")).first()
                else -> "tag.${box.tagName()}"
            }
            add("$head@tag.a@href", "数字页码容器 $head", 88)
            val hasLast = box.select("a[href]").any { a ->
                val t = a.text().trim()
                t == "末页" || t == "尾页" || t == "最后一页"
            }
            if (hasLast) add("$head@tag.a.-1@href", "末页负索引 $head", 84)
        }

        // ④ JS 按总页数拼页
        buildPagerJs(doc)?.let { (rule, desc) -> add(rule, desc, 60) }

        return out.values.sortedByDescending { it.score }
    }

    /**
     * JS 按总页数拼页：从数字页码链接推断「公共前缀 + 变化数字 + 公共后缀」与总页数，
     * 生成 legado 可执行的 <js>（直接列出后续各页地址，不依赖当前页码）。
     */
    private fun buildPagerJs(doc: org.jsoup.nodes.Document): Pair<String, String>? {
        val numLinks = doc.select("a[href]").filter { a ->
            val t = a.text().trim()
            t.isNotEmpty() && t.length <= 4 && t.all { it.isDigit() } &&
                a.attr("abs:href").startsWith("http")
        }
        if (numLinks.size < 2) return null
        val a = numLinks[0].attr("abs:href")
        val b = numLinks[1].attr("abs:href")
        var p = 0
        while (p < a.length && p < b.length && a[p] == b[p]) p++
        var s = 0
        while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
        val midA = a.substring(p, a.length - s)
        val midB = b.substring(p, b.length - s)
        if (midA.isEmpty() || !midA.all { it.isDigit() } || !midB.all { it.isDigit() }) return null
        val n1 = midA.toIntOrNull() ?: return null
        val n2 = midB.toIntOrNull() ?: return null
        val step = n2 - n1
        if (step <= 0) return null
        val total = numLinks.mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: return null
        if (total <= n1) return null
        val pre = a.substring(0, p).replace("\"", "")
        val suf = a.substring(a.length - s).replace("\"", "")
        val js = "<js>\nvar pre=\"$pre\";var suf=\"$suf\";var step=$step;var total=$total;\n" +
            "var list=[];\nfor(var i=1+step;i<=total;i+=step){list.push({k:String(i),v:pre+i+suf});}\n" +
            "list\n</js>"
        return js to "JS 按总页数拼页（共 $total 页，步长 $step）"
    }

    /** 取最优翻页规则：主形态 + 一条互补形态用 || 并列（社区书源常见写法）；JS 拼页只作最后兜底 */
    private fun bestPagerRule(cands: List<PagerCand>): String? {
        if (cands.isEmpty()) return null
        val js = cands.firstOrNull { it.rule.contains("<js>") }
        val plain = cands.filter { !it.rule.contains("<js>") }
        if (plain.isEmpty()) return js?.rule
        val top = plain.first()
        val alt = plain.drop(1).firstOrNull {
            it.rule.substringBefore("@").substringBefore("!") !=
                top.rule.substringBefore("@").substringBefore("!")
        }
        val main = if (alt != null && alt.score >= 80) top.rule + "||" + alt.rule else top.rule
        return if (js != null) main + "||" + js.rule else main
    }

    /**
     * 详情页 → 目录页 的入口全形态探测：
     * ① 链接文字 ② meta 属性 og:novel:read_url ③ 属性选择器 ④ XPath 按文字定位
     */
    private fun detectTocEntries(detailHtml: String, baseUrl: String): List<EntryCand> {
        val out = ArrayList<EntryCand>()
        val doc = runCatching { Jsoup.parse(detailHtml, baseUrl) }.getOrNull() ?: return out
        // ① 链接文字（按命中顺序给，越具体的词越靠前）
        for (w in TOC_ENTRY_WORDS) {
            val a = doc.select("a[href]").firstOrNull { it.text().trim().contains(w) } ?: continue
            out.add(EntryCand("text.$w@href", "入口文字「${a.text().trim()}」"))
        }
        // ② meta 直接给出目录地址（正规站常用）
        val metaUrl = doc.select("meta[property=\"og:novel:read_url\"]").attr("content")
            .ifBlank { doc.select("meta[name=\"og:novel:read_url\"]").attr("content") }
        if (metaUrl.isNotBlank()) {
            out.add(EntryCand("[property=og:novel:read_url]@content", "meta 里直接给了目录地址 $metaUrl"))
        }
        // ③ 入口链接没有 class/id 但有固定属性时，用属性选择器
        doc.select("a[href]").firstOrNull { a ->
            TOC_ENTRY_WORDS.any { a.text().trim().contains(it) } &&
                a.className().isBlank() && a.id().isBlank()
        }?.let { a ->
            val st = a.attr("style")
            if (st.isNotBlank()) out.add(EntryCand("[style=\"$st\"]@href", "入口链接的 style 属性"))
        }
        // ④ XPath 按文字定位（无 class/id 可用时的最后手段）
        out.add(EntryCand("//a[contains(text(),'目录')]/@href", "XPath 按文字定位目录入口"))
        return out
    }

    /**
     * 把 AI 常写错的非法写法纠正成 legado 语法（社区书源里没有这些错误写法）：
     * :eq(n)→.n、:has()/:contains()/… 伪类剔除、逗号分隔多选择器→||
     */
    private fun normalizeRule(raw: String?): String? {
        val s0 = raw?.trim() ?: return null
        if (s0.isBlank()) return s0
        var s = s0
        s = Regex(":eq\\((\\d+)\\)").replace(s) { "." + it.groupValues[1] }
        s = Regex(
            ":(has|contains|containsOwn|nth-child|nth-of-type|not|first-child|last-child|gt|lt)\\([^()]*\\)"
        ).replace(s, "")
        // 逗号多选择器 → ||（跳过 ,{"webView":true} 这类 JSON 选项里的逗号）
        s = Regex("\\s*,\\s*(?=[.#a-zA-Z\\[])").replace(s, "||")
        return s.trim()
    }

    /** 对目录与搜索相关的所有规则做一次语法规范化（只纠语法，不改语义） */
    private fun normalizeSourceRules(source: BookSource) {
        val info = source.getBookInfoRule()
        val toc = source.getTocRule()
        val search = source.getSearchRule()
        info.tocUrl = normalizeRule(info.tocUrl)
        toc.chapterList = normalizeRule(toc.chapterList)
        toc.chapterName = normalizeRule(toc.chapterName)
        toc.chapterUrl = normalizeRule(toc.chapterUrl)
        toc.nextTocUrl = normalizeRule(toc.nextTocUrl)
        // 搜索侧的 bookList 同样会被 AI 写成逗号多选择器 / :eq() / 伪类，一并纠正
        search.bookList = normalizeRule(search.bookList)
        search.name = normalizeRule(search.name)
        search.author = normalizeRule(search.author)
        search.bookUrl = normalizeRule(search.bookUrl)
        search.coverUrl = normalizeRule(search.coverUrl)
        search.intro = normalizeRule(search.intro)
        search.kind = normalizeRule(search.kind)
        search.lastChapter = normalizeRule(search.lastChapter)
    }

    /**
     * JSON 目录页的章节列表候选（API 型站点）：找出「对象数组且同时含名称与链接字段」的路径。
     * 生成 $..key[*] 这类 JSONPath 章节列表规则。
     */
    private fun jsonChapterCandidates(html: String): List<String> {
        val t = html.trim()
        if (!t.startsWith("{") && !t.startsWith("[")) return emptyList()
        val root = runCatching { JsonParser.parseString(t) }.getOrNull() ?: return emptyList()
        val found = LinkedHashSet<String>()
        val nameKeys = listOf("name", "title", "chaptername", "chaptertitle", "chapter_name")
        val urlKeys = listOf("url", "link", "href", "id", "chapterid", "chapter_id")
        fun walk(el: JsonElement, path: String, depth: Int) {
            if (depth > 6 || found.size >= 3) return
            when {
                el.isJsonObject -> el.asJsonObject.entrySet().forEach { (k, v) ->
                    walk(v, if (path.isEmpty()) k else "$path.$k", depth + 1)
                }
                el.isJsonArray -> {
                    val arr = el.asJsonArray
                    if (arr.size() >= 3 && arr.all { it.isJsonObject }) {
                        val keys = arr[0].asJsonObject.keySet().map { it.lowercase() }
                        if (keys.any { it in nameKeys } && keys.any { it in urlKeys }) {
                            found.add(if (path.isEmpty()) "\$.[*]" else "\$.$path[*]")
                        }
                    }
                    if (arr.size() > 0) walk(arr[0], path, depth + 1)
                }
            }
        }
        walk(root, "", 0)
        return found.toList()
    }

    // ==================== 封面规则预设（由 5541 个社区书源的写法统计得来） ====================

    /**
     * 一条封面候选：写进书源的规则字符串 + 与之严格对应的 Jsoup 实测方式。
     * 只有在采集到的页面 HTML 上实测能取到封面地址的规则才会被写入，
     * 避免把 AI 编造的、页面里根本不存在的选择器塞进 coverUrl。
     */
    private data class CoverProbe(val rule: String, val selector: String, val attr: String)

    /** 详情页封面：og:image / id.fmimg / class.pic 等社区高频写法，按出现频次排序 */
    private val DETAIL_COVER_PROBES = listOf(
        CoverProbe("[property=og:image]@content", "meta[property=og:image]", "content"),
        CoverProbe("id.fmimg@img@src", "#fmimg img", "src"),
        CoverProbe("class.pic@img@src", ".pic img", "src"),
        CoverProbe("class.cover@img@src", ".cover img", "src"),
        CoverProbe("class.bookimg@img@src", ".bookimg img", "src"),
        CoverProbe("class.imgbox@tag.img@src", ".imgbox img", "src"),
        CoverProbe("img@data-original", "img", "data-original"),
        CoverProbe("img@data-src", "img", "data-src"),
        CoverProbe("tag.img@src", "img", "src"),
        CoverProbe("[property$=image]@content", "meta[property$=image]", "content")
    )

    /** 列表页封面：规则作用在「单个书籍条目」上，先通用 img，再常见卡片容器 */
    private val LIST_COVER_PROBES = listOf(
        CoverProbe("img@src", "img", "src"),
        CoverProbe("img@data-original", "img", "data-original"),
        CoverProbe("img@data-src", "img", "data-src"),
        CoverProbe("class.bookimg@img@src", ".bookimg img", "src"),
        CoverProbe("class.pic@img@src", ".pic img", "src"),
        CoverProbe("class.cover@img@src", ".cover img", "src"),
        CoverProbe("class.imgbox@tag.img@src", ".imgbox img", "src"),
        CoverProbe("tag.dt@tag.a@img@src", "dt a img", "src"),
        CoverProbe("[property=og:image]@content", "meta[property=og:image]", "content")
    )

    /**
     * JSON 接口型列表的封面写法。这类规则没法用 Jsoup 在 HTML 上实测，
     * 但 legado 对 || 链里取不到值的规则会自动跳过，挂在链尾对 HTML 站点无害、对 API 站点有效。
     */
    private val JSON_COVER_RULES = listOf(
        "$.cover", "$.coverUrl", "$.Img", "$.pic", "$.image", "$.thumb"
    )

    /** 明显不是封面的图片：站点 logo、占位图、二维码等 */
    private val NOT_COVER_WORDS = listOf(
        "logo", "icon", "blank", "loading", "placeholder", "1x1",
        "spacer", "qrcode", "avatar", "advert", "banner"
    )

    private fun looksLikeCover(url: String): Boolean {
        if (!url.startsWith("http", true)) return false
        val low = url.lowercase()
        return NOT_COVER_WORDS.none { low.contains(it) }
    }

    /** 相对 / 协议相对地址转绝对地址，与 legado 的 NetworkUtils.getAbsoluteURL 行为一致 */
    private fun coverAbsUrl(base: String, raw: String): String {
        val r = raw.trim()
        return when {
            r.startsWith("http", true) -> r
            r.startsWith("//") ->
                (runCatching { java.net.URI(base).scheme }.getOrNull() ?: "https") + ":" + r
            else -> runCatching { java.net.URI(base).resolve(r).toString() }.getOrDefault(r)
        }
    }

    /**
     * 在页面 HTML 上逐条实测候选规则，返回用 || 串起的兜底链（只保留命中的规则）。
     * legado 对 || 会按顺序取第一个非空结果，链里有几条就够应付同站不同模板。
     */
    private fun probeCoverRules(
        html: String,
        baseUrl: String,
        probes: List<CoverProbe>,
        limit: Int = 4
    ): String? {
        if (html.isBlank()) return null
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return null
        val hit = LinkedHashSet<String>()
        for (p in probes) {
            val url = doc.select(p.selector).asSequence().mapNotNull { el ->
                val raw = el.attr(p.attr).trim()
                if (raw.isEmpty()) null else coverAbsUrl(baseUrl, raw).takeIf { looksLikeCover(it) }
            }.firstOrNull()
            if (url != null) hit.add(p.rule)
            if (hit.size >= limit) break
        }
        return hit.takeIf { it.isNotEmpty() }?.joinToString("||")
    }

    /**
     * 列表侧封面规则链：HTML 页面用实测命中的选择器 + 通用 img 兜底，
     * JSON 接口页面用真实字段路径 + 常见 JSONPath 兜底，两者不混写。
     *
     * 关键：列表规则作用在「单个书籍条目」上，整页第一张图未必是封面；只按整页实测结果填写，
     * 实测不到就留空，会让搜索列表与发现页列表永远拿不到封面（详情页有 og:image 所以不受影响）。
     * 因此这里即便一个候选都没命中，也必须返回一条兜底链。
     */
    private fun buildListCoverChain(page: Page?, fallback: String? = null): String {
        val html = page?.html?.trim().orEmpty()
        // JSON 接口型列表：CSS 选择器必然取不到值，改用纯 JSONPath 链；
        // 先从响应里算出真实封面字段路径（如 $.data.list[*].cover），比写死的 $.cover 准
        if (html.startsWith("{") || html.startsWith("[")) {
            val chain = LinkedHashSet<String>()
            runCatching { jsonBookCandidates(html)?.cover }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { chain.add(it) }
            JSON_COVER_RULES.forEach { chain.add(it) }
            return chain.joinToString("||")
        }
        val chain = LinkedHashSet<String>()
        page?.let { probeCoverRules(it.html, it.url, LIST_COVER_PROBES, 6) }
            ?.split("||")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { chain.add(it) }
        // 页面实测不到也必须给一条链：列表规则作用在单个书籍条目上，
        // 条目里的封面通常就在 img 的 src / data-original / data-src 里
        listOf("img@src", "img@data-original", "img@data-src", "tag.img@src")
            .forEach { chain.add(it) }
        fallback?.takeIf { it.isNotBlank() }
            ?.split("||")?.map { it.trim() }?.filter { it.isNotEmpty() }
            // 只复用同为 CSS 形态的规则，避免把 JSONPath 混进 Jsoup 选择器链
            ?.filter { !it.startsWith('$') && !it.startsWith("{{") }
            ?.forEach { chain.add(it) }
        return chain.joinToString("||")
    }

    /** 按角色优先级挑一个采集页面（找不到就退回首页面） */
    private fun coverPage(pages: List<Page>, vararg roles: String): Page? {
        for (r in roles) {
            val p = pages.firstOrNull { it.role.startsWith(r) }
            if (p != null) return p
        }
        return pages.firstOrNull()
    }

    /**
     * 三处 coverUrl 任一为空时都补上规则链（详情 / 搜索 / 发现），AI 已写的非空规则不覆盖。
     * 放在 verifySource 开头调用，可覆盖 AI 版 / 代码辅助版 / 修正版 / 兜底版等所有分支。
     */
    private fun ensureCoverRules(source: BookSource, pages: List<Page>) {
        val detail = coverPage(pages, "书籍详情页", "搜索结果页", "首页")
        val search = coverPage(pages, "搜索结果页", "分类列表页", "首页")
        val category = coverPage(pages, "分类列表页", "搜索结果页", "首页")

        val info = source.getBookInfoRule()
        if (info.coverUrl.isNullOrBlank()) {
            val chain = LinkedHashSet<String>()
            detail?.let { probeCoverRules(it.html, it.url, DETAIL_COVER_PROBES, Int.MAX_VALUE) }
                ?.split("||")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.forEach { chain.add(it) }
            listOf("[property=og:image]@content", "img@data-original", "img@src")
                .forEach { chain.add(it) }
            info.coverUrl = chain.joinToString("||")
        }
        val sr = source.getSearchRule()
        if (sr.coverUrl.isNullOrBlank()) {
            // 搜索列表：不再因为「整页实测不到封面」就留空
            sr.coverUrl = buildListCoverChain(search)
        }
        val ex = source.getExploreRule()
        if (ex.coverUrl.isNullOrBlank()) {
            // 发现页列表：有分类页就按分类页再实测一次，否则复用搜索侧的链
            ex.coverUrl = if (!source.exploreUrl.isNullOrBlank()) {
                buildListCoverChain(category, sr.coverUrl)
            } else {
                sr.coverUrl
            }
        }
    }

    /** 封面验证失败时的最后手段：用完整候选链覆盖；只有 CSS 背景图站点才单独改用 JS 写法 */
    private fun forceCoverRules(source: BookSource, pages: List<Page>): Boolean {
        val detail = coverPage(pages, "书籍详情页", "搜索结果页", "首页")
        val search = coverPage(pages, "搜索结果页", "分类列表页", "首页")
        var changed = false
        detail?.let { p ->
            // JS 规则不能与 CSS 规则用 || 拼接：legado 会把规则串行执行，
            // 后面的 JS 拿前面的结果当输入再算一次，反而把已取到的封面清空
            val chain = probeCoverRules(p.html, p.url, DETAIL_COVER_PROBES, Int.MAX_VALUE)
                ?.takeIf { it.isNotBlank() }
                ?: CSS_COVER_RULE.takeIf { isCssBackgroundCover(p.html) }
            if (!chain.isNullOrBlank() && chain != source.getBookInfoRule().coverUrl) {
                source.getBookInfoRule().coverUrl = chain
                changed = true
            }
        }
        search?.let { p ->
            val chain = buildListCoverChain(p)
            if (chain != source.getSearchRule().coverUrl) {
                source.getSearchRule().coverUrl = chain
                // 发现页已有自己的实测链时不覆盖
                if (source.getExploreRule().coverUrl.isNullOrBlank()) {
                    source.getExploreRule().coverUrl = chain
                }
                changed = true
            }
        }
        return changed
    }

    // ==================== 阶段三：四链路实测 ====================

    /** 用 App 自带的 WebBook 引擎按真实使用路径验证：验证通过 = 实际可用 */
    /**
     * 把 [from] 里某个环节涉及的规则搬到 [to]，用于历史记录补齐。
     * 只搬这一环节用到的字段，其余环节保持 [to] 自己的规则，避免把已经通过的环节整体回退。
     */
    private fun copyStepRules(from: BookSource, to: BookSource, step: String) {
        when (step) {
            "搜索" -> {
                // 历史里那份书源可能没有封面规则，整体替换后要把本次算出的封面规则补回去
                val cover = to.getSearchRule().coverUrl
                to.searchUrl = from.searchUrl
                to.ruleSearch = from.ruleSearch
                if (to.getSearchRule().coverUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                    to.getSearchRule().coverUrl = cover
                }
            }
            "详情" -> {
                to.ruleBookInfo = from.ruleBookInfo
            }
            "目录" -> {
                to.ruleToc = from.ruleToc
                // 目录入口地址写在详情规则里，一并搬过来，否则光换目录规则仍取不到目录页
                val oldTocUrl = from.ruleBookInfo?.tocUrl
                if (!oldTocUrl.isNullOrBlank()) to.getBookInfoRule().tocUrl = oldTocUrl
            }
            "正文", "播放" -> {
                to.ruleContent = from.ruleContent
            }
            "发现" -> {
                // 同上：发现页列表封面规则不能被历史书源里的空值覆盖
                val cover = to.getExploreRule().coverUrl
                to.exploreUrl = from.exploreUrl
                to.ruleExplore = from.ruleExplore
                to.enabledExplore = from.enabledExplore
                if (to.getExploreRule().coverUrl.isNullOrBlank() && !cover.isNullOrBlank()) {
                    to.getExploreRule().coverUrl = cover
                }
            }
        }
    }

    /**
     * 五链路实测 + 封面校验（封面在详情环节顺带验证，报告共 6 项，封面项非阻塞）。
     * @param steps 为 null 时跑全部五环（首轮 / 兜底用）；
     *             非 null 时只跑指定环节（增量修复用），其余环节直接复用 [holder] 里上轮解析出的 book / 首章，
     *             不再重新抓网、也不重写它们的通过状态。
     * @param holder 跨多次实跑复用的书 / 首章；本轮若重跑了搜索 / 详情 / 目录会刷新它。
     */
    private suspend fun verifySource(
        source: BookSource,
        keyword: String,
        pages: List<Page>,
        onProgress: (String) -> Unit,
        skipSearch: Boolean = false,
        videoMode: Boolean = false,
        steps: Set<String>? = null,
        holder: VerifyHolder? = null
    ): List<VerifyItem> {
        // 封面：任何一次实测前先保证三处 coverUrl 有「页面实测过」的规则，
        // 覆盖 AI 版 / 代码辅助版 / 修正版 / 兜底版等所有分支
        ensureCoverRules(source, pages)
        val report = ArrayList<VerifyItem>(6)
        var book: Book? = holder?.book
        var firstChapter: io.legado.app.data.entities.BookChapter? = holder?.chapter
        // 列表侧真实解析出的封面地址：搜索结果 / 分类列表各一个
        var searchCover: String? = null
        var exploreCover: String? = null

        if (steps == null || steps.contains("搜索")) {
            if (skipSearch) {
                // 单本模式：这个书源本来就没有搜索，只验详情/目录/正文
                report.add(VerifyItem("搜索", true, "单本模式，已跳过（本书源不含搜索）"))
            } else {
                onProgress("验证 ①/⑤：搜索…")
                runCatching {
                    if (source.searchUrl.isNullOrBlank()) error("searchUrl 为空")
                    if (source.searchUrl!!.contains("::")) {
                        error("searchUrl 写成了「分类名::网址」的分类列表格式——那是 exploreUrl 的格式，searchUrl 必须是单个搜索地址且含 {{key}}")
                    }
                    val books = WebBook.searchBookAwait(source, keyword, 1)
                    if (books.isEmpty()) error("搜索无结果")
                    val first = books.first()
                    book = first.toBook().also { it.origin = source.bookSourceUrl }
                    searchCover = first.coverUrl?.trim()?.takeIf { it.isNotBlank() }
                    report.add(VerifyItem("搜索", true, "搜到 ${books.size} 本，第一本《${first.name}》"))
                }.onFailure {
                    report.add(VerifyItem("搜索", false, it.message ?: "执行异常"))
                }
            }
        }

        if (steps == null || steps.contains("详情")) {
            onProgress("验证 ②/⑤：书籍详情 / 封面…")
            runCatching {
                val probe = book ?: Book(
                    name = "验证测试",
                    bookUrl = pages.firstOrNull { it.role.contains("详情") }?.url
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
        }

        if (steps == null || steps.contains("目录")) {
            onProgress("验证 ③/⑤：目录…")
            runCatching {
                val b = book ?: error("详情验证未通过，无法继续")
                val chapters = WebBook.getChapterListAwait(source, b).getOrNull().orEmpty()
                if (chapters.isEmpty()) error("解析出 0 个章节")
                firstChapter = chapters.firstOrNull { !it.isVolume } ?: chapters.first()
                report.add(VerifyItem("目录", true, "共 ${chapters.size} 章，首章《${firstChapter?.title}》"))
            }.onFailure {
                report.add(VerifyItem("目录", false, it.message ?: "执行异常"))
            }
        }

        if (steps == null || steps.contains("播放") || steps.contains("正文")) {
            onProgress(if (videoMode) "验证 ④/⑤：播放地址…" else "验证 ④/⑤：正文…")
            runCatching {
                val b = book ?: error("详情验证未通过，无法继续")
                val chapter = firstChapter ?: error("目录验证未通过，无法继续")
                val content = WebBook.getContentAwait(source, b, chapter, null, needSave = false)
                if (videoMode) {
                    // 与时光播放器 VideoSourcePlayer.isPlayableUrl 同一口径：
                    // 正文必须是「单个可播直链」，HTML / 提示语 / 网页地址都不能算通过
                    val text = content.trim()
                    if (text.isEmpty()) error("正文规则返回空，未能解析出播放地址")
                    if (!VideoSourcePlayer.isPlayableUrl(text)) {
                        val hit = VideoSourcePlayer.extractPlayableUrl(text)
                        error(
                            if (hit != null) "播放地址夹杂了其它内容，应只返回一个直链：$hit"
                            else "返回的不是可播直链（需 .mp4/.m3u8/.m4v/.webm，或含 /m3u8/、authPath）"
                        )
                    }
                    report.add(VerifyItem("播放", true, "解析到播放地址：${text.take(60)}"))
                } else {
                    val plain = Jsoup.parse(content).text().trim()
                    if (plain.length < MIN_CONTENT_CHARS) error("正文过短（${plain.length} 字）")
                    report.add(VerifyItem("正文", true, "取到正文 ${plain.length} 字"))
                }
            }.onFailure {
                report.add(VerifyItem(if (videoMode) "播放" else "正文", false, it.message ?: "执行异常"))
            }
        }

        if (steps == null || steps.contains("发现")) {
            onProgress("验证 ⑤/⑤：发现页…")
            val explore = source.exploreUrl?.trim().orEmpty()
            if (explore.isBlank()) {
                report.add(VerifyItem("发现", true, "未配置（站点无分类导航，已跳过）"))
            } else {
                runCatching {
                    val url = parseExploreFirstUrl(explore, source.bookSourceUrl)
                        ?: error("exploreUrl 无法解析出分类地址")
                    val books = WebBook.exploreBookAwait(source, url, 1)
                    if (books.isEmpty()) error("发现页无结果（规则或分类地址有误）")
                    exploreCover = books.first().coverUrl?.trim()?.takeIf { it.isNotBlank() }
                    report.add(
                        VerifyItem("发现", true, "分类页取到《${books.first().name}》等 ${books.size} 本")
                    )
                }.onFailure {
                    report.add(VerifyItem("发现", false, it.message ?: "执行异常"))
                }
            }
        }
        // 封面验证：详情 + 搜索列表 + 发现页列表三处都要真解析出图片地址。
        // 只验详情会漏掉「点进详情页才有封面、列表里全空」这种情况。
        if (steps == null || steps.contains("详情")) {
            val detailOk = report.any { it.step == "详情" && it.ok } &&
                looksLikeCover(book?.coverUrl?.trim().orEmpty())
            // 增量复验只跑指定环节，没跑搜索/发现时不把缺失误判成封面失败
            val searchOk = if (steps != null || skipSearch) true
            else looksLikeCover(searchCover.orEmpty())
            val hasExplore = !source.exploreUrl.isNullOrBlank()
            val exploreOk = if (steps != null || skipSearch || !hasExplore) true
            else looksLikeCover(exploreCover.orEmpty())
            val rulesOk = !source.getSearchRule().coverUrl.isNullOrBlank() &&
                !source.getExploreRule().coverUrl.isNullOrBlank()
            val coverOk = detailOk && searchOk && exploreOk && rulesOk
            report.add(
                VerifyItem(
                    "封面", coverOk,
                    buildString {
                        append("详情 ").append(if (detailOk) "✓" else "✗")
                        append("｜搜索列表 ").append(if (searchOk) "✓" else "✗")
                        if (hasExplore) append("｜发现列表 ").append(if (exploreOk) "✓" else "✗")
                        if (!rulesOk) append("｜列表规则缺失")
                        if (steps == null && !skipSearch && searchCover.isNullOrBlank()) {
                            append("\n搜索结果未解析出封面地址，请检查 ruleSearch.coverUrl")
                        }
                        if (steps == null && !skipSearch && hasExplore && exploreCover.isNullOrBlank()) {
                            append("\n分类列表未解析出封面地址，请检查 ruleExplore.coverUrl")
                        }
                    },
                    blocking = false
                )
            )
        }
        holder?.book = book
        holder?.chapter = firstChapter
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
        if (one.length <= MAX_PAGE_CHARS) return one
        // 截断会丢掉页面尾部的分页导航（「下一页」），AI 看不到就不会写目录翻页规则，
        // 这里单独把分页区补回截断内容后面
        val pager = pagerHtml(doc)
        val head = one.take(MAX_PAGE_CHARS)
        return if (pager.isNotBlank() && !head.contains(pager)) {
            head + "\n<!-- 内容过长已截断，以下是页面尾部的分页导航 -->" + pager
        } else {
            head + "\n<!-- 内容过长已截断 -->"
        }
    }

    /** 页面尾部的分页导航区 HTML（含「下一页/下页/后页」的链接），供截断时补回 */
    private fun pagerHtml(doc: org.jsoup.nodes.Document): String {
        val words = listOf("下一页", "下页", "后页", "尾页", "末页")
        val hit = doc.select("a[href]").firstOrNull { a ->
            val t = a.text().trim()
            words.any { t == it || t.contains(it) }
        } ?: return ""
        val box = hit.parent()?.takeIf { it.html().length in 1..800 } ?: hit
        return box.outerHtml().replace(Regex("\\s+"), " ").trim().take(600)
    }

    /** 两页内容是否基本相同：识别「搜索被 301 回首页」「章节链接是死链指回详情页」这类软失败 */
    private fun pageSimilar(a: String, b: String): Boolean {
        val ka = a.replace(Regex("\\s"), "")
        val kb = b.replace(Regex("\\s"), "")
        if (ka.length < 100 || kb.length < 100) return false
        return ka.take(600) == kb.take(600)
    }

    /**
     * 解析 exploreUrl 的第一个分类地址（用于抓取分类列表页与发现页验证）。
     * 支持「分类名::URL」行式（含 {{page}}）与 JSON 数组两种主流写法；
     * @js 动态生成无法静态解析，返回 null 由调用方兜底。
     */
    private fun parseExploreFirstUrl(exploreUrl: String, seed: String): String? {
        val raw = exploreUrl.trim()
        if (raw.isBlank() || raw.startsWith("@js") || raw.contains("<js>")) return null
        val rawUrl = when {
            raw.startsWith("[") -> runCatching {
                JsonParser.parseString(raw).asJsonArray.firstOrNull()
                    ?.asJsonObject?.get("url")?.asString
            }.getOrNull()
            else -> raw.lineSequence().firstOrNull { it.contains("::") }
                ?.substringAfter("::")?.trim()
        } ?: return null
        val u = rawUrl.replace("{{page}}", "1").trim()
        return when {
            u.startsWith("http") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> runCatching {
                val home = java.net.URI(seed)
                "${home.scheme}://${home.host}$u"
            }.getOrNull()
            else -> null
        }
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
         "firstCategoryUrl": "页面导航/分类区里第一个书籍分类的完整网址（只填一个地址，用于抓取分类列表页；地址带页码时把页码写成 {{page}}，如 https://www.x.com/class/1/{{page}}.html）；页面没有分类导航填 null",
         "problem": "不是小说站或缺搜索入口时简述原因，正常则留空"}
    """.trimIndent()

    private val CHECK_SEARCH = """
        这是用关键词「{keyword}」请求后的网页 HTML。请判断是否真的返回了搜索结果。
        注意：如果页面 <title> 正常但列表区域空无一物、结构是空壳（script 加载的 SPA），
        说明搜索结果由 JS 渲染，要在 problem 里写明「搜索结果由JS渲染」。
        只输出 JSON（不要解释）：
        {"isSearchResult": true或false,
         "bookUrl": "搜索结果里第一本小说的详情页完整网址；没有结果填 null",
         "problem": "无效时简述原因（如：无搜索结果/是验证码页/被重定向到首页/是登录页/搜索结果由JS渲染），正常则留空"}
    """.trimIndent()

    private val CHECK_INFO = """
        这是疑似书籍详情页的网页 HTML。请判断它是否包含书籍信息。
        注意：详情页上的「最新章节」区块不是目录页；「点击阅读/开始阅读」链接可能指向第一章甚至失效，
        目录页地址优先选「查看全部章节/章节目录/完整目录」这类链接；
        有些站点的目录入口不给文字提示，就是一串省略号（......、…、···）或「查看全部章节>」，
        这类链接同样要当作目录页地址填进 tocUrl。
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

    /** 视频播放页专用质检：判断是否取得到可播直链，不用小说正文页的口径 */
    private val CHECK_PLAY_PAGE = """
        这是一个视频 / 影视 / 短剧站的播放页或单集页 HTML。请判断能否从页面里取到可直接播放的流地址
        （路径以 .mp4/.m3u8/.m4v/.webm 结尾，或含 /m3u8/、authPath、.m3u8? 的代理型直链）。
        注意：这里不是小说正文页，不要用「有没有小说正文容器」来判断；
        年龄确认页、验证码页、只有网页播放器地址的页面都算异常。
        只输出 JSON（不要解释）：
        {"hasPlayUrl": true或false,
         "playUrl": "页面里第一个可播直链的完整网址；没有填 null",
         "problem": "异常时简述原因（如：其实是详情页/年龄确认页/只有网页播放器地址），正常则留空"}
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
     * 目录地址正则推导：用户同时提供了书籍详情页网址与目录页网址时，让 AI 找出二者地址规律，
     * 给出一个能把「详情页地址」换成「目录页地址」的正则（作用于书籍自身地址 baseUrl）。
     * 大部分小说站地址里那串数字就是书籍 ID，详情页与目录页共用，AI 很容易识别出来。
     * 只输出 JSON（不要解释）：
     * {"pattern": "匹配详情页地址的正则（Java/Kotlin 正则语法，可用 (\\d+) 之类捕获组）",
     *  "replacement": "替换串，捕获组用 $1 $2 引用；整体需满足 infoUrl.replace(pattern, replacement) == tocUrl"}
     */
    private val ASK_TOC_REGEX = """
        书籍详情页完整网址：%1${"$"}s
        目录页完整网址：     %2${"$"}s
        请找出这两个网址之间的固定规律（通常是其中一段数字/字母是同一本书的 ID），
        写一个正则表达式：把详情页网址替换成目录页网址。
        要求：
        1）pattern 用 Java/Kotlin 正则语法（例如 /book/(\d+)\.html 写成 book/(\d+)\.html）；
        2）replacement 用 $1 $2 引用捕获组，必须保证 infoUrl.replace(pattern, replacement) 严格等于 tocUrl；
        3）如果两网址仅在末尾路径/文件名/某段数字不同，就抓那段差异做捕获组，不要写死整本书的地址；
        4）若两个网址完全相同（详情页即目录页），pattern 填 ""、replacement 填 ""。

        【输出格式，必须严格遵守】
        你只能且必须输出一行 JSON，不要任何解释、不要 Markdown 代码块包裹、不要换行以外的多余字符。
        JSON 必须同时包含 pattern 与 replacement 两个字段，格式如下（这是示例，不是你的答案）：
        {"pattern":"novel/(\\d+)\\.html","replacement":"novel/list/$1/1.html"}
        再次强调：只输出上面这种一行 JSON，其它什么都不要写。
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

        九、发现页（exploreUrl + ruleExplore，5541 个书源里 3264 个都配了）
        · exploreUrl 行式写法（最常用 1838 个），分类名::完整网址，每行一个，支持 {{page}} 翻页：
          玄幻::https://x.com/class/1/{{page}}.html
          都市::https://x.com/class/2/{{page}}.html
        · 也支持 JSON 数组写法：[{"title":"玄幻","url":"..."},{"title":"都市","url":"..."}]
        · ruleExplore 与 ruleSearch 同类字段：bookList/name/author/bookUrl/coverUrl/intro/kind/lastChapter
        · 分类列表页 bookList 常见写法：
          class.l@li｜class.novelslist2@tag.dl｜class.list-item｜class.hot_sale｜class.bookbox｜class.titlelist@li
        · 分类地址必须是采集到的真实分类页，不要编造；站点没有分类导航就不要写 exploreUrl

        十、知名大站的已验证方案（HTML 搜索页多为 JS 渲染，直接套用其 JSON 接口）
        · 纵横中文网（zongheng.com 系，含 comic.zongheng.com）：
          searchUrl: https://search.zongheng.com/search/book?keyword={{key}}&sort=null&pageNo=1&pageNum=20&isFromHuayu=0
          bookList: $.data.datas.list[*]　name: $.name　author: $.authorName
          bookUrl: https://www.zongheng.com/book/{{$.bookId}}
          coverUrl: https://static.zongheng.com{{$.coverUrl}}
        · 遇到搜索结果页 <title> 正常但列表区域空无一物时，优先怀疑 JS 渲染：
          在页面 script 里找 api/接口线索，或套用站点的 JSON 搜索接口，或改用 {"webView":true}

        十一、目录页地址与详情页存在规律时（ruleBookInfo.tocUrl 的写法）
        · 举例（仅为说明形态，实际形态以本站「目录地址推导」为准）：
          详情页 /novel/113846.html → 目录页 /novel/list/113846/1.html（书籍编号相同）
        · 这类站点详情页里常常没有「目录」链接；有的也只是一串省略号
          （<li><a href="/novel/list/113846/1.html">......</a></li>），li/ul 上没有任何 class/id 可选
        · 所以 tocUrl 不要留空（会退化成只拿到详情页那几个最新章节），
          更不要写死某一本书的目录地址（会导致所有书都跑去打开同一本书的目录）
        · 正确写法：用 JS 从书籍详情页地址里取出随书变化的那一段再拼装（刻意不用正则，
          免得 \\d 这类写法在书源 JSON 里被多层转义写错），形如
          <js>var u=baseUrl.split("#")[0].split("?")[0];...;u 或 n 再拼上固定部分</js>
        · 按编号拼不出规律时，「目录地址推导」还可能给出其它形态（信息页地址加页内锚点或固定后缀、
          同一目录换末段文件名、按书籍标识拼等）；没有推导结果时仍按「目录入口定位」写选择器
        · 顺序是：入口链接能写出可靠选择器就写选择器；只有选择器不可靠
          （入口链接没有 class/id、或压根找不到入口链接）时才改用「目录地址推导」里的拼接写法。
          绝不允许用页面上不存在的选择器编造 tocUrl，也不允许写死某一本书的地址
        · 采集确认里的「目录地址推导」已按本站实际网址算好这条写法，直接采用

        十二、视频站（短剧 / 影视 / 动漫）抓包解析套路——先判断页面属于哪一种形态再写规则
        · 形态 A：整部剧的 m3u8 清单直接写在页面 <script> 里（多为逗号分隔的一串地址），
          页面上压根没有真正的选集列表（选集区块常是 javascript:history.back() 这类假链接）。
          这时**不要**写 chapterList 选择器，直接用 JS 把清单解析成 N 集直链：
            chapterList: @js:(function(){var m=String(result).match(/https?:\/\/[^\"'\s,]+?\.m3u8/g);
              if(!m)return [];var seen={},out=[],n=0;for(var i=0;i<m.length;i++){var u=m[i];
              if(seen[u])continue;seen[u]=1;n++;out.push('第'+n+'集::'+u);}return out;})()
            chapterName: @js:String(result).split('::')[0]
            chapterUrl:  @js:String(result).split('::')[1]
          好处：每集都是 m3u8 直链，目录一拉下来就能播，还能下载 / 边看边存 / 记忆进度。
        · 形态 B：抖音式瀑布流（首页是 swiper，swiper-wrapper 为空，数据靠 JS 拉）。
          ① 首页 HTML 里找 <script data-main="xxx.js">，下载这个 JS，在里面找接口
             （形如 /api.php/api/video/videolist，参数一般只有 page）；
          ② 若 JS 里出现两段 CryptoJS enc.Utf8.parse('...')，那就是 AES 的 key 与 iv（CBC/PKCS7），
             响应通常是 {"data":"<base64 密文>"}，解密后才是真正的列表 JSON；
          ③ exploreUrl 写法（POST 翻页，body 里带 {{page}}）：
             首页瀑布流::https://x.com/api.php/api/video/videolist,
             {"method":"POST","body":"page={{page}}","headers":{"Content-Type":"application/x-www-form-urlencoded"}}
          ④ ruleExplore.bookList 用 @js 解密并输出 JSON 串（每条一个 JSON.stringify），
             配套写 name:$.name、bookUrl:$.url、coverUrl:$.cover；
             同一条 JS 里用「首字符是否为 {」判断，可以同时兼容 JSON 接口与 HTML 分类页。
        · 形态 C：分类导航。分类名必须取导航链接里的文字（如 /list/?1.html 旁的「短剧」），绝不臆造；
          站点没有 URL 分页（试过 ?page=2 是 404/301）就不要写 {{page}}，否则第二页是空的；
          空分类（站点自己返回 0 条）也要照实保留，不要拿别的地址顶替。
        · 形态 D：播放页。嵌入播放器至少有两种模板，**必须两种都取**，否则会有一半分类播不了：
          ① xgplayer：<div id="mse" data-url="/media/m3u8?url=...">
          ② 原生 video：<source src="/media/m3u8?url=..." type="application/x-mpegURL">
          典型流程：详情页 JSON-LD 的 embedUrl → /embed/{token} 页面 → 取上面的地址 → 拼上站点域名
          （embedUrl 的域名常与站点不同，要换成站点域名再请求）。
          代理型地址形如 /media/m3u8?url=...%3Fv%3D3&exp=...&token=...、或 ?url=xxx&f=1.m3u8，
          它**确实是 m3u8 流**（Content-Type 为 application/vnd.apple.mpegurl），可以直接交给播放器。
        · 形态 E：封面不在 <img src>，而在 div 的 style 里：background-image: url('https://...jpg')。
          这时 tag.img@src / @data-src 都取不到，必须用 JS 抠：
            coverUrl: @js:(function(){var m=String(result).match(/url\(['"]?(https?:\/\/[^'"),]+)/);return m?m[1]:''})()
        · 其它经验：
          详情页封面取不到时，退回 og:image 或 JSON-LD 的 thumbnailUrl；
          分类页与搜索页常常是同一套卡片（class.drama-card、class.video-item 等），规则可复用；
          封面常被 AES 加密（不是 JPEG 头），用 coverDecodeJs 解密（先判断 JPEG 魔数再决定解不解密）。
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
         "searchUrl": "搜索地址，关键词用 {{key}}，翻页用 {{page}}；必须是单个搜索请求地址，绝不能写成分类列表",
{exploreFields}
         "ruleSearch": {"bookList": "搜索结果列表选择器", "name": "书名规则", "author": "作者规则",
           "kind": "分类规则，不确定留空", "lastChapter": "", "intro": "", "coverUrl": "",
           "bookUrl": "详情页链接规则"},
{exploreRule}
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
        4. 采集确认里的书名/作者/第一章网址必须与规则解析结果吻合；
        5. {exploreHardRule}
        6. searchUrl 与 exploreUrl 是两个完全不同的东西，绝不能混写：searchUrl 是**单个**搜索请求地址且必须含 {{key}}；exploreUrl 是**多行**「分类名::网址」列表且不含 {{key}}；
        7. ruleBookInfo.tocUrl 的写法按优先级：① 采集确认里有「目录地址推导」且其中是明确的地址规律时，采用给出的 JS 规则（通常是按书籍编号拼，也可能是信息页地址加固定后缀/页内锚点等）；② 「目录入口定位」里那个链接的容器有明确 class/id 时，写成选择器规则（如 class.xxx@tag.a@href、text.目录@href）；③ 目录就是书籍信息页本身时留空；④ 都没有才留空。绝不允许把某一本书的目录地址写死进 tocUrl，也不允许用页面上不存在的选择器编造。
    """.trimIndent()

    private val FIX_PROMPT = """
        下面这份书源在实际验证中有环节失败。请根据失败原因与对应页面的真实 HTML，
        只修正失败的环节，输出**仅含你改动字段的部分 JSON 补丁**（不要解释、不要代码块）。

        【重要】未失败的环节规则完全正确，请**不要改动、也不要重写**它们——
        只返回失败环节对应的字段（如 目录失败就只返回 ruleToc，播放失败就只返回 ruleContent）。
        这样做是为了避免把已经能通过验证的规则不小心改坏。

        【提示】若失败原因是「Too many follow-up requests」或 30x 重定向循环，
        多半不是规则问题，而是请求被站点反爬——保留书源现有的 header（User-Agent）配置即可，不要删除。

        【验证失败环节】
        {fails}

        【纯代码已经算出的可用写法（修正时优先直接采用，不要另造选择器）】
        {hints}

        【当前书源 JSON（仅供参考，你只需返回失败环节要改动的字段）】
        {source}
    """.trimIndent()

    // ==================== 代码深度辅助：纯代码解析对照书源 ====================
    /** 详情/信息页自动选封面：优先 data-src / data-original，兜底 src */
    private fun coverRuleFrom(html: String, baseUrl: String): String {
        val doc = Jsoup.parse(html, baseUrl)
        val img = doc.select("img").firstOrNull {
            it.attr("abs:src").startsWith("http") ||
                it.attr("abs:data-src").startsWith("http") ||
                it.attr("abs:data-original").startsWith("http")
        } ?: return ""
        return when {
            img.hasAttr("data-src") -> "tag.img@data-src"
            img.hasAttr("data-original") -> "tag.img@data-original"
            else -> "tag.img@src"
        }
    }

    /**
     * 纯代码解析版书源：能用代码解析的字段（搜索JSON / 封面 / 书名 / 目录 / 正文）直接填，
     * 解析不出的留空——然后与 AI 版一起实测，哪个通过用哪个。
     */
    private fun buildCodeBookSource(
        pages: List<Page>,
        seed: String,
        searchTemplate: String?,
        meta: Map<String, String>,
        includeExplore: Boolean,
        singleBook: Boolean,
        loginUrl: String?,
        detailUrlFinal: String?,
        tocUrl: String
    ): BookSource {
        val s = BookSource()
        s.bookSourceUrl = seed
        s.bookSourceName = meta["书名"]?.takeIf { it.isNotBlank() } ?: "代码解析书源"
        s.bookSourceGroup = "AI生成"
        s.loginUrl = loginUrl
        // 搜索：JSON 接口直接给路径，否则用「含最多书籍链接」的容器
        if (!singleBook) {
            searchTemplate?.takeIf { it.isNotBlank() }?.let { s.searchUrl = it }
            pages.firstOrNull { it.role.startsWith("搜索") }?.let { p ->
                jsonBookCandidates(p.html)?.let { c ->
                    s.getSearchRule().apply {
                        bookList = c.list
                        name = c.name
                        author = c.author
                        bookUrl = c.url
                        coverUrl = c.cover
                    }
                } ?: autoSearchCandidates(p.html, p.url).firstOrNull()?.let { sel ->
                    s.getSearchRule().apply {
                        bookList = sel
                        bookUrl = "$sel@href"
                        name = "$sel@text"
                    }
                }
            }
        }
        // 详情：封面 + 书名（作者不强求，详情验证只检查书名非空）
        pages.firstOrNull { it.role.contains("详情") }?.let { p ->
            val info = s.getBookInfoRule()
            info.coverUrl = coverRuleFrom(p.html, p.url)
            autoNameRule(p.html, p.url).takeIf { it.isNotBlank() }?.let { info.name = it }
            // 目录地址：能用代码从详情页地址拼出就填，拼不出留空（交给后续实测/兜底）
            if (detailUrlFinal != null && tocUrl.isNotBlank()) {
                deriveTocUrlRuleStr(detailUrlFinal, tocUrl)?.let { info.tocUrl = it }
            }
        }
        // 目录：JSON 目录给数组路径；否则用「含最多章节链接」的容器
        pages.firstOrNull { it.role == "目录页" }?.let { p ->
            jsonChapterCandidates(p.html).firstOrNull()?.let { jp ->
                s.getTocRule().chapterList = jp
            } ?: autoChapterCandidates(p.html, p.url).firstOrNull()?.let { sel ->
                s.getTocRule().apply {
                    chapterList = sel
                    chapterName = "$sel@text"
                    chapterUrl = "$sel@href"
                }
            }
        }
        // 正文：用纯代码选出正文文本最长的块
        pages.firstOrNull { it.role == "正文页" }?.let { p ->
            autoContentRule(p.html, p.url).takeIf { it.isNotBlank() }?.let {
                s.getContentRule().content = it
            }
        }
        return s
    }

    /** 详情页自动选书名：优先 h1，兜底 h2/h3（详情验证只要求书名非空） */
    private fun autoNameRule(html: String, baseUrl: String): String {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return ""
        doc.selectFirst("h1")?.let { if (it.text().isNotBlank()) return "tag.h1@text" }
        doc.select("h2,h3").firstOrNull {
            it.text().isNotBlank() && it.text().length in 2..40
        }?.let { return "tag.${it.tagName().lowercase()}@text" }
        return ""
    }

    /** 正文页自动选内容容器：取「含 <p>/<br> 且纯文本最长」的块，再用收紧选择器定位 */
    private fun autoContentRule(html: String, baseUrl: String): String {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return ""
        var best: Element? = null
        var bestLen = 0
        for (el in doc.select("article, section, div, main")) {
            if (el.select("p").isEmpty() && el.select("br").isEmpty()) continue
            val len = el.text().replace(Regex("\\s"), "").length
            if (len > bestLen) {
                bestLen = len
                best = el
            }
        }
        if (best == null || bestLen < 150) return ""
        val sel = contentCss(best, doc) ?: return ""
        return "$sel@text"
    }

    /** 与 candidateCss 同构，但用「纯文本长度」代替「章节文本数」来判定选择器精确匹配 */
    private fun contentCss(el: Element, doc: org.jsoup.nodes.Document): String? {
        val local = el.text().replace(Regex("\\s"), "").length
        val variants = ArrayList<String>()
        if (el.id().isNotBlank()) variants.add("id." + el.id())
        val cls = el.classNames().filter { it.matches(Regex("[A-Za-z_][\\w-]*")) }
        if (cls.isNotEmpty()) {
            variants.add("class." + cls.joinToString("."))
            variants.add("class." + cls.first())
            variants.add("tag.${el.tagName().lowercase()}.${cls.first()}")
        }
        variants.add("tag." + el.tagName())
        var tight: Pair<String, Int>? = null
        for (v in variants) {
            val css = legadoToCss(v) ?: continue
            val got = runCatching { doc.select(css) }.getOrNull() ?: continue
            val n = got.sumOf { it.text().replace(Regex("\\s"), "").length }
            if (n < local) continue
            if (n == local) return v
            val t = tight
            if (t == null || n < t.second) tight = v to n
        }
        return tight?.first
    }

    /**
     * 返回「详情页地址 → 目录页地址」的纯规则串（与 deriveTocUrlRule 同逻辑，
     * 但只输出可直接写入 ruleBookInfo.tocUrl 的规则，不输出说明文本）。
     * 返回 "" 表示目录即详情页本身（tocUrl 应留空），返回 null 表示无法推导。
     */
    private fun deriveTocUrlRuleStr(infoUrl: String, tocUrl: String): String? {
        if (infoUrl.isBlank() || tocUrl.isBlank()) return null
        // 目录页与详情页同址：没有独立目录页，tocUrl 留空
        if (cleanUrl(infoUrl) == cleanUrl(tocUrl)) return ""
        // 同域 + 按数字编号派生
        if (hostOf(infoUrl) != null && hostOf(infoUrl) == hostOf(tocUrl)) {
            val bookId = Regex("/(\\d+)\\.html").find(infoUrl)?.groupValues?.get(1)
                ?: Regex("/(\\d+)/?$").find(infoUrl)?.groupValues?.get(1)
            if (bookId != null) {
                val start = tocUrl.indexOf(bookId)
                if (start >= 0 && tocUrl.indexOf(bookId, start + 1) < 0) {
                    val prefix = tocUrl.substring(0, start)
                    val suffix = tocUrl.substring(start + bookId.length)
                    if (prefix.isNotBlank()) {
                        return "<js>var u=baseUrl.split(\"?\")[0];" +
                            "var n=u.substring(u.lastIndexOf(\"/\")+1).split(\".\")[0];" +
                            "\"$prefix\"+n+\"$suffix\"</js>"
                    }
                }
            }
        }
        // 同目录换末段文件名
        val i = cleanUrl(infoUrl)
        val t = cleanUrl(tocUrl)
        if (i.isNotBlank() && t.isNotBlank() && i != t) {
            val iDir = i.substringBeforeLast('/', "")
            val tDir = t.substringBeforeLast('/', "")
            if (iDir.isNotBlank() && iDir == tDir) {
                val file = t.substringAfterLast('/')
                if (file.isNotBlank() && !file.contains('"')) {
                    return "<js>${BASE_URL_JS}u.substring(0,u.lastIndexOf(\"/\")+1)+\"$file\"</js>"
                }
            }
        }
        // 仅页内锚点不同
        val anchor = tocUrl.substringAfter('#', "").trim()
        if (anchor.isNotBlank() && !anchor.contains('"') && urlKey(infoUrl) == urlKey(tocUrl)) {
            return "<js>${BASE_URL_JS}u+\"#$anchor\"</js>"
        }
        // 详情页地址 + 固定后缀
        val base = infoUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        if (base.isNotBlank() && t.startsWith(base, true)) {
            val suffix = t.substring(base.length)
            if (suffix.isNotBlank() && suffix != "/" && !suffix.contains('"')) {
                return "<js>${BASE_URL_JS}u+\"$suffix\"</js>"
            }
        }
        // 按书籍标识（数字/字母数字 slug）派生
        val id = bookIdOf(infoUrl)
        if (id != null) {
            val start = tocUrl.indexOf(id)
            if (start >= 0 && tocUrl.indexOf(id, start + 1) < 0) {
                val prefix = tocUrl.substring(0, start)
                val suffix = tocUrl.substring(start + id.length)
                if (prefix.isNotBlank() && !prefix.contains('"') && !suffix.contains('"')) {
                    return "<js>${BASE_URL_JS}var n=u.substring(u.lastIndexOf(\"/\")+1).split(\".\")[0];" +
                        "\"$prefix\"+n+\"$suffix\"</js>"
                }
            }
        }
        return null
    }

    // ==================== AI 生成视频源（视频 / 影视 / 动漫 / 短剧） ====================

    /**
     * 视频源模式提示词：把「小说 / 书籍 / 章节 / 正文」的语义映射到「影片 / 剧集 / 播放地址」。
     * 与小说模式共用 {seed}/{searchHint}/{meta}/{pages}/{exploreFields}/{exploreRule}/{exploreHardRule} 占位。
     */
    private val VIDEO_USER_PROMPT = """
        请为下面这个视频 / 影视 / 动漫 / 短剧站编写一份完整、可直接导入「阅读」(Legado) App 使用的**视频源 JSON**。

        【先理解视频源与小说书源的对应关系】
         「正文」= **播放地址**（m3u8 / mp4 直链），不是文字内容。

        起始网址：{seed}
        搜索信息：{searchHint}
        采集确认：{meta}

        {pages}

        【输出要求】
        只输出**一个 JSON 对象**（不要数组、不要代码块、不要解释），字段如下：
        {"bookSourceUrl": "站点根地址",
         "bookSourceName": "站点名称",
         "bookSourceGroup": "视频",
         "bookSourceType": 4,
         "header": "{\"User-Agent\":\"桌面 Chrome UA\",\"Referer\":\"站点根地址/\"}",
         "enabledCookieJar": true,
         "searchUrl": "搜索地址，关键词用 {{key}}，翻页用 {{page}}",
        {exploreFields}
         "ruleSearch": {"bookList": "搜索结果列表规则", "name": "片名规则", "author": "",
           "kind": "标签/类型，不确定留空", "lastChapter": "更新状态，不确定留空", "intro": "",
           "coverUrl": "封面规则", "bookUrl": "详情页链接规则"},
        {exploreRule}
         "ruleBookInfo": {"name": "片名规则", "author": "", "kind": "", "lastChapter": "",
           "intro": "简介规则", "coverUrl": "封面规则", "tocUrl": "选集页链接规则，不确定留空"},
         "ruleToc": {"chapterList": "剧集列表规则", "chapterName": "集数名规则",
           "chapterUrl": "单集地址规则", "nextTocUrl": "选集翻页规则，没有留空"},
         "ruleContent": {"content": "播放地址规则", "sourceRegex": ".*\\.(m3u8|mp4|m4v|webm).*"}}

        【播放地址（ruleContent.content）写法，按优先级选】
        1. 单集地址本身就是 m3u8 / mp4 / m4v / webm 直链，或含 /m3u8/、authPath 的代理型直链
        2. 页面或接口里能拿到直链时，用 @js: + ES5 从 result 中提取，结果只返回一个 URL，
           以 `result = 直链; result;` 收尾；需要二次请求用 java.ajax / java.post
        3. 只返回一个 URL 字符串，前后不要有任何其它字符；绝不能返回 HTML、JSON 对象、
           MPD、提示语或网页地址，也绝对不要调用 java.startBrowser / java.startBrowserAwait

        【规则语法要点】
        1. CSS 选择器（Jsoup）：如 class.video-item、tag.a@href；
        2. JSON 接口用 JSONPath：如 $.list[*]、$.data.items；两种模式不能混写在同一条规则里；
        3. 取属性用 @href / @src / @data-src（封面常常是懒加载的 data-src）；
        4. 不确定的字段**留空字符串**，不要编造。

        【硬性规则】
        1. 只依据给到的页面内容推断；
        2. 剧集列表要排除「推荐 / 猜你喜欢」区块，只取真正的选集列表；
        3. {exploreHardRule}
        4. searchUrl 与 exploreUrl 是两个完全不同的东西，绝不能混写；
        5. bookSourceType 必须是 4，bookSourceGroup 必须含「视频 / 影视 / 动漫 / 短剧」字样。
        """.trimIndent()

    /**
     * 视频源硬规则，对应 docs/视频源开发指南.md 的 §1 硬规则速查表、§2 时光播放器链路、§3 Rhino 语法。
     * 生成、AI 修正、已有书源校验修正三条链路都会拼上，避免写出「验证通过但时光播放器播不了」的规则。
     */
    private val VIDEO_HARD_RULES = """

        【视频源硬规则（时光播放器 · 唯一链路，必须全部遵守）】
        目标：每集在首次解析就拿到能直接交给 ExoPlayer 的流地址，本项目不接受网页播放器兜底。
        1. bookSourceType 必须是 4，bookSourceGroup 必须含「视频 / 影视 / 短剧」字样；不要写 videoSourceWhitelist。
        2. chapterUrl 必须是普通 http(s) 地址，禁止 data: URI 与 javascript: 链接。
        3. ruleContent.content 必须只返回【一个】URL 字符串，前后不能有任何其它字符，
           不能是 HTML、JSON 对象、MPD 文本、提示语或网页地址。
           可播判定：路径以 .mp4/.m3u8/.m4v/.webm 结尾，或含 /m3u8/、authPath、.m3u8? 之一。
        4. 正文、登录检查里绝对不要调用 java.startBrowser() / java.startBrowserAwait()；
           也不要依赖 jsLib 自定义函数、java.readBookConfig、Map("cookie") 等私有扩展。
        5. Rhino 是 ES5：不要写 new URL()、fetch、XMLHttpRequest、document、window、箭头函数、模板字符串；
           相对地址用字符串拼接补全。
        6. header 必须是静态 JSON 字符串，同时带桌面 User-Agent 与 Referer
           （播放器只按默认请求属性注入，移动 UA 常被 CDN 拒绝）；需要会话 Cookie 时写 enabledCookieJar: true。
        7. chapterList / bookList 用 <js> 返回时必须是字符串数组，
           每条是 JSON.stringify 后的 JSON 字符串，配套写 chapterName: ${'$'}.name、chapterUrl: ${'$'}.url。
        8. @js: 规则统一用 `result = 结果; result;` 收尾，不要用 IIFE；
           规则字段插值用 {${'$'}.field} 形式（如 bookUrl 里拼 JSON 字段），
           URL 里只认 {{key}}、{{page}}，两者绝不能混用，否则 URL 里会出现字面量导致 404。
        9. 多字段兜底：分集页与电影页字段可能不同，播放地址要把 durl / hls_url / url 等候选都取到，
           只依赖单一字段会让一类内容全挂。
        10. 优先用站点 JSON API 生成目录与取流地址，别依赖页面 DOM；
            bookUrl 与每个 chapterUrl 必须保持同一视图参数与请求头（如 ?__view=mobile）。
        11. ruleContent.sourceRegex 建议写 .*\.(m3u8|mp4|m4v|webm).*；
            不要返回 #EXT-X-STREAM-INF 主清单，时光播放器不支持 MPD。
        12. HTTP 200 不等于能解析：用书源真正拼出的 URL（含全部参数与 header）验证，
            对比响应体积与特征字段，排除年龄确认页 / 验证页 / 桌面版页面。
        """.trimIndent()

    /** 播放页没有裸直链时的纯代码兜底：ES5 从响应里抠出第一个可播地址，只返回单个 URL */
    private val VIDEO_CONTENT_EXTRACT_JS = """
        @js:
        var s = String(result || '');
        var m = s.match(/https?:\/\/[^\s"'<>\\]+/g) || [];
        var out = '';
        for (var i = 0; i < m.length; i++) {
            var u = m[i].replace(/&amp;/g, '&');
            if (/\.(m3u8|mp4|m4v|webm)/i.test(u) || /\/m3u8\//i.test(u) || /authPath/i.test(u)) {
                out = u;
                break;
            }
        }
        result = out;
        result;
    """.trimIndent()

    // ---------- 视频源纯代码解析辅助（与章节侧同构，按「集数」语义改写） ----------

    private val EPISODE_TITLE_RE =
        Regex("第\\s*([0-9零〇一二两三四五六七八九十百千万]+)\\s*[集]")
    private val NUM_EPISODE_RE = Regex("^\\s*\\d{1,5}\\s*[\\u4e00-\\u9fa5A-Za-z（(]")

    private fun isEpisodeText(t: String): Boolean =
        t.isNotBlank() && (EPISODE_TITLE_RE.containsMatchIn(t) || NUM_EPISODE_RE.containsMatchIn(t))

    private fun episodeNo(text: String): Int? {
        EPISODE_TITLE_RE.find(text)?.let { m ->
            val raw = m.groupValues[1]
            raw.toIntOrNull()?.let { return it }
            return cnToInt(raw)
        }
        return Regex("^\\s*(\\d{1,5})").find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun episodeTextsOf(els: List<Element>): List<String> =
        els.flatMap { it.select("a[href]") }.map { it.text() }.filter { isEpisodeText(it) }

    private fun isNotEpisodeBlock(el: Element): Boolean = isNotTocBlock(el)

    /** 纯代码推导剧集列表候选（镜像 autoChapterCandidates） */
    private fun autoEpisodeCandidates(html: String, baseUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val links = doc.select("a[href]").filter { isEpisodeText(it.text()) }
        if (links.size < 3) return emptyList()
        val els = LinkedHashSet<Element>()
        for (a in links) {
            var p: Element? = a.parent()
            var d = 0
            while (p != null && d < 5) {
                els.add(p)
                p = p.parent()
                d++
            }
        }
        val cands = ArrayList<TocCand>()
        for (el in els) {
            val texts = episodeTextsOf(listOf(el))
            if (texts.size < 3) continue
            if (isNotEpisodeBlock(el)) continue
            val css = candidateCss(el, doc) ?: continue
            val nums = texts.mapNotNull { episodeNo(it) }
            val (run, start) = longestRun(nums)
            cands.add(TocCand("$css@tag.a", run, start ?: Int.MAX_VALUE, texts.size))
        }
        return cands.sortedWith(
            compareByDescending<TocCand> { it.run }.thenBy { it.start }.thenByDescending { it.count }
        ).map { it.sel }.distinct().take(6)
    }

    private fun scoreEpisodeSel(html: String, sel: String?): TocCand? {
        if (sel.isNullOrBlank()) return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val els = selectForScore(doc, sel) ?: return null
        val texts = episodeTextsOf(els)
        if (texts.isEmpty()) return null
        val nums = texts.mapNotNull { episodeNo(it) }
        val (run, start) = longestRun(nums)
        return TocCand(sel, run, start ?: Int.MAX_VALUE, texts.size)
    }

    private fun pickBestEpisodeList(html: String, cands: List<String>): TocCand? {
        var best: TocCand? = null
        for (c in cands) {
            val s = scoreEpisodeSel(html, c) ?: continue
            if (s.count == 0) continue
            val b = best
            if (b == null || betterCand(s, b)) best = s
        }
        return best
    }

    /** 从播放页 HTML 里直接抠出可播放直链（与 VideoSourcePlayer.isPlayableUrl 同口径） */
    private fun extractPlayableUrl(html: String): String? {
        val re = Regex(
            "https?://[^\\s\"'<>]+\\.(?:m3u8|mp4|m4v|webm)[^\\s\"'<>]*",
            RegexOption.IGNORE_CASE
        )
        re.find(html)?.value?.let { return it }
        // 代理型直链：/media/m3u8?url=...、/m3u8/...、authPath=...（PATTERN_LIBRARY 形态 D）
        return Regex("https?://[^\\s\"'<>]+").findAll(html)
            .map { it.value }
            .firstOrNull { VideoSourcePlayer.isPlayableUrl(it) }
    }

    /**
     * 播放地址规则：能直接抠出直链就留空（由 sourceRegex 命中）；
     * 否则用 ES5 从响应里提取单个直链，绝不退回 java.startBrowserAwait
     * （文档 §1#5：拉起网页播放器属于失败设计）。
     */
    private fun playContentRule(html: String): String {
        val u = extractPlayableUrl(html)
        return if (u != null) "" else VIDEO_CONTENT_EXTRACT_JS
    }

    /** 从首页纯代码解析出分类导航，拼成「分类名::网址」多行 exploreUrl */
    private fun videoExploreLinks(homeHtml: String, seed: String): String? {
        val doc = runCatching { Jsoup.parse(homeHtml, seed) }.getOrNull() ?: return null
        val re = Regex("/?(?:class|list|sort|category|fenlei|type|vod|video|drama)[^\"']*")
        val seen = LinkedHashSet<String>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href").takeIf { it.startsWith("http") } ?: continue
            val path = runCatching { java.net.URL(href).path }.getOrNull().orEmpty()
            if (!re.containsMatchIn(path)) continue
            val name = a.text().trim()
            if (name.isBlank()) continue
            seen.add("$name::$href")
        }
        return seen.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    // ---------- 视频站形态识别（抓包页面 → 直接算出规则） ----------

    private val CSS_BG_URL_RE =
        Regex("background-image\\s*:\\s*url\\((['\"]?)([^'\")]+)")
    private val M3U8_URL_RE = Regex("https?://[^\"'\\s,]+?\\.m3u8")
    private val DATA_MAIN_JS_RE = Regex("data-main=\"([^\"]+\\.js)")
    private val SOURCE_SRC_RE = Regex("<source[^>]+src=")
    private val EMBED_URL_RE = Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"")

    /** 封面是 CSS 背景图（background-image: url(...)）：img 的 src/data-src 都取不到 */
    private fun isCssBackgroundCover(html: String): Boolean =
        html.contains("background-image") && CSS_BG_URL_RE.containsMatchIn(html)

    private val CSS_COVER_RULE =
        "@js:(function(){var m=String(result).match(/url\\(['\"]?(https?:\\/\\/[^'\"),]+)/);" +
            "return m?m[1]:''})()"

    /** 整部剧的 m3u8 清单藏在页面 script 里（逗号分隔），直接解析成 N 集直链 */
    private fun m3u8ListInScript(html: String): List<String> =
        M3U8_URL_RE.findAll(html).map { it.value }.distinct().toList()

    /** @return (chapterList, chapterName, chapterUrl) */
    private fun m3u8ListTocRule(): Triple<String, String, String> = Triple(
        "@js:\n(function(){var m=String(result).match(/https?:\\/\\/[^\\\"\\\\s,]+?\\.m3u8/g);" +
            "if(!m)return [];var seen={},out=[],n=0;" +
            "for(var i=0;i<m.length;i++){var u=m[i];if(seen[u])continue;seen[u]=1;n++;" +
            "out.push('第'+n+'集::'+u);}return out;})()",
        "@js:String(result).split('::')[0]",
        "@js:String(result).split('::')[1]"
    )

    /**
     * 播放页规则：详情页 JSON-LD 的 embedUrl → 嵌入页 → 取流地址。
     * 站点至少有两种嵌入模板（xgplayer 的 data-url、原生 video 的 <source src>），两种都要认，
     * 否则只会有一半分类能播。
     */
    private val EMBED_PLAY_RULE =
        "@js:\n(function(){\n" +
            "  var d=String(result);\n" +
            "  var m=d.match(/\\\"embedUrl\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"/);\n" +
            "  var h0=String(baseUrl).match(/^https?:\\/\\/[^\\/]+/);\n" +
            "  var src=m?m[1]:'';\n" +
            "  var h;\n" +
            "  if(src){ if(h0) src=src.replace(/^https?:\\/\\/[^\\/]+/,h0[0]); h=java.ajax(src); } else { h=d; }\n" +
            "  var u=h.match(/data-url=\\\"([^\\\"]+)\\\"/) || h.match(/<source[^>]+src=\\\"([^\\\"]+)\\\"/);\n" +
            "  if(!u)return '';\n" +
            "  var url=u[1];\n" +
            "  return /^https?:/.test(url)?url:((h0?h0[0]:'')+url);\n" +
            "})()"

    /** 播放页是否属于「嵌入页取流」形态（embedUrl / data-url / source src 任一命中） */
    private fun isEmbedPlayPage(html: String): Boolean =
        EMBED_URL_RE.containsMatchIn(html) ||
            html.contains("data-url=") ||
            SOURCE_SRC_RE.containsMatchIn(html)

    /** 首页是 JS 瀑布流时，给出「该去哪个 JS 里找接口」的线索 */
    private fun waterfallMainJs(homeHtml: String): String? =
        DATA_MAIN_JS_RE.find(homeHtml)?.groupValues?.getOrNull(1)

    // ---------- 视频源生成主流程 ----------

    private fun buildCodeVideoSource(
        pages: List<Page>,
        seed: String,
        searchTemplate: String?,
        meta: Map<String, String>,
        includeExplore: Boolean,
        singleBook: Boolean,
        loginUrl: String?,
        homeHtml: String,
        detailUrlFinal: String?,
        tocUrl: String
    ): BookSource {
        val s = BookSource()
        s.bookSourceUrl = seed
        s.bookSourceName = meta["片名"]?.takeIf { it.isNotBlank() }
            ?: meta["书名"]?.takeIf { it.isNotBlank() } ?: "代码解析视频源"
        s.bookSourceGroup = "视频"
        s.bookSourceType = 4
        s.loginUrl = loginUrl
        // 搜索：JSON 接口直接给路径，否则用「含最多剧集链接」的容器
        if (!singleBook) {
            searchTemplate?.takeIf { it.isNotBlank() }?.let { s.searchUrl = it }
            pages.firstOrNull { it.role.startsWith("搜索") }?.let { p ->
                jsonBookCandidates(p.html)?.let { c ->
                    s.getSearchRule().apply {
                        bookList = c.list
                        name = c.name
                        author = c.author
                        bookUrl = c.url
                        coverUrl = c.cover
                    }
                } ?: autoSearchCandidates(p.html, p.url).firstOrNull()?.let { sel ->
                    s.getSearchRule().apply {
                        bookList = sel
                        bookUrl = "$sel@href"
                        name = "$sel@text"
                    }
                }
            }
        }
        // 发现：首页纯代码解析出的分类导航
        if (includeExplore) {
            videoExploreLinks(homeHtml, seed)?.let { s.exploreUrl = it }
        }
        // 详情：封面 + 片名
        pages.firstOrNull { it.role.contains("详情") }?.let { p ->
            val info = s.getBookInfoRule()
            info.coverUrl = coverRuleFrom(p.html, p.url)
            autoNameRule(p.html, p.url).takeIf { it.isNotBlank() }?.let { info.name = it }
            if (detailUrlFinal != null && tocUrl.isNotBlank()) {
                deriveTocUrlRuleStr(detailUrlFinal, tocUrl)?.let { info.tocUrl = it }
            }
        }
        // 选集（目录页）：整部剧的 m3u8 清单写在页面里时优先用它生成 N 集直链，
        // 否则纯代码选「剧集最完整」的容器
        val tocPage = pages.firstOrNull { it.role == "目录页" }
            ?: pages.firstOrNull { it.role.contains("详情") }
        val m3u8List = tocPage?.let { m3u8ListInScript(it.html) }.orEmpty()
        if (m3u8List.size >= 3) {
            val (list, name, url) = m3u8ListTocRule()
            s.getTocRule().apply {
                chapterList = list
                chapterName = name
                chapterUrl = url
            }
        } else {
            tocPage?.let { p ->
                autoEpisodeCandidates(p.html, p.url).firstOrNull()?.let { sel ->
                    s.getTocRule().apply {
                        chapterList = sel
                        chapterName = "$sel@text"
                        chapterUrl = "$sel@href"
                    }
                }
            }
        }
        // 播放页：嵌入页取流优先，其次能直接抠直链就留空（sourceRegex 命中），最后网页播放器拉起
        pages.firstOrNull { it.role == "播放页" }?.let { p ->
            s.getContentRule().content = when {
                isEmbedPlayPage(p.html) -> EMBED_PLAY_RULE
                else -> playContentRule(p.html)
            }
        }
        // 封面是 CSS 背景图时，img@src / @data-src 取不到，改用 JS 抠 url(...)
        if (pages.any { isCssBackgroundCover(it.html) } || isCssBackgroundCover(homeHtml)) {
            if (s.getSearchRule().coverUrl.isNullOrBlank()) s.getSearchRule().coverUrl = CSS_COVER_RULE
            if (s.getExploreRule().coverUrl.isNullOrBlank()) s.getExploreRule().coverUrl = CSS_COVER_RULE
        }
        return s
    }

    /** 视频源模式：把纯代码已经算出的结论汇总给 AI（镜像 buildHints 的视频版） */
    private fun buildVideoCodeHints(
        searchHtml: String,
        searchUrl: String,
        homeHtml: String,
        episodeHtml: String?,
        episodeBaseUrl: String,
        playHtml: String?
    ): Map<String, String> {
        val hints = LinkedHashMap<String, String>()
        if (searchHtml.isNotBlank()) {
            jsonBookCandidates(searchHtml)?.let { c ->
                hints["搜索结果(JSON)"] = "搜索接口为 JSON，纯代码已算出 bookList=${c.list}，" +
                    "name=${c.name}，bookUrl=${c.url}（ruleSearch 可直接采用）"
            } ?: autoSearchCandidates(searchHtml, searchUrl).firstOrNull()?.let { sel ->
                if (!hints.containsKey("搜索结果(JSON)"))
                    hints["搜索结果(HTML)"] = "搜索结果页纯代码解析出列表容器：$sel" +
                        "（bookList 写 $sel，条目链接 $sel@href）"
            }
        }
        if (homeHtml.isNotBlank()) {
            videoExploreLinks(homeHtml, searchUrl)?.let { v ->
                hints["发现页(代码解析)"] = "首页纯代码解析出分类导航，exploreUrl 可写：\n$v"
            }
        }
        if (!episodeHtml.isNullOrBlank()) {
            autoEpisodeCandidates(episodeHtml, episodeBaseUrl).firstOrNull()?.let { sel ->
                hints["剧集列表(代码解析)"] = "选集页纯代码解析出剧集列表容器：$sel" +
                    "（chapterList 写 $sel，chapterName=$sel@text，chapterUrl=$sel@href）"
            }
        }
        waterfallMainJs(homeHtml)?.let { js ->
            hints["首页瀑布流(代码解析)"] = "首页是 JS 瀑布流（swiper 为空壳），主脚本：$js。" +
                "请下载该 JS 找接口（形如 /api.php/api/video/videolist，参数 page）与加密常量" +
                "（CryptoJS enc.Utf8.parse('...') 两段即 AES 的 key/iv），" +
                "exploreUrl 写成 POST+body 翻页，ruleExplore.bookList 用 @js 解密后输出 JSON 串"
        }
        if (isCssBackgroundCover(homeHtml) || isCssBackgroundCover(searchHtml)) {
            hints["封面(代码解析)"] = "列表封面在 div 的 style:background-image 里（不是 img 的 src/data-src），" +
                "coverUrl 必须写：$CSS_COVER_RULE"
        }
        if (!episodeHtml.isNullOrBlank()) {
            val list = m3u8ListInScript(episodeHtml)
            if (list.size >= 3) {
                hints["剧集直链(代码解析)"] = "页面 script 里直接带着 ${list.size} 条 m3u8 清单" +
                    "（首条 ${list.first().take(60)}），没有真正的选集列表，" +
                    "ruleToc 应改用 JS 把清单解析成 N 集直链，不要写选择器"
            }
        }
        if (!playHtml.isNullOrBlank()) {
            if (isEmbedPlayPage(playHtml)) {
                hints["播放地址(代码解析)"] = "播放页是「详情页 embedUrl → 嵌入页 → 流地址」形态，" +
                    "且嵌入页至少有两种模板（xgplayer 的 data-url、原生 video 的 <source src>），" +
                    "content 必须两种都取：$EMBED_PLAY_RULE"
            }
            extractPlayableUrl(playHtml)?.let { u ->
                hints["播放地址(代码提取)"] = "播放页纯代码已提取到可播放直链：" +
                    "${u.take(80)}，ruleContent.content 可留空（由 sourceRegex 命中）"
            } ?: run {
                hints["播放地址(代码提取)"] = "播放页未直接出现可播直链，" +
                    "需 java.ajax / java.post 二次请求或用 @js: 从 result 里提取；" +
                    "content 必须只返回一个直链，禁止 java.startBrowser* 与网页地址"
            }
        }
        return hints
    }

    /** 把已生成的书源标记为视频源：类型 / 组名 / sourceRegex / 请求头，并按文档做禁词自检 */
    private fun markAsVideoSource(source: BookSource) {
        source.bookSourceType = 4
        val g = source.bookSourceGroup ?: ""
        val keywords = listOf("动漫", "短剧", "视频", "影视", "电影", "剧集", "电视")
        val hit = keywords.any { g.contains(it) }
        source.bookSourceGroup = if (g.isBlank()) "视频" else if (hit) "$g·视频" else g

        val sr = source.getContentRule().sourceRegex
        if (sr.isNullOrBlank()) {
            source.getContentRule().sourceRegex = ".*\\.(m3u8|mp4|m4v|webm).*"
        }
        // 文档 §1#7 / §2.5：静态 JSON header 必须同时带桌面 UA 与 Referer，
        // 否则直链常被 CDN 判 403（移动 UA 尤其明显）
        val headers = source.header?.takeIf { it.isNotBlank() }?.let {
            runCatching { GSON.fromJsonObject<Map<String, String>>(it).getOrNull() }.getOrNull()
        }?.toMutableMap() ?: mutableMapOf()
        val uaKey = headers.keys.firstOrNull { it.equals(AppConst.UA_NAME, ignoreCase = true) }
            ?: AppConst.UA_NAME
        headers[uaKey] = PC_UA
        val refererKey = headers.keys.firstOrNull { it.equals("Referer", ignoreCase = true) } ?: "Referer"
        if (headers[refererKey].isNullOrBlank()) {
            val referer = runCatching {
                val u = java.net.URI(source.bookSourceUrl)
                val host = u.host
                val scheme = u.scheme ?: "https"
                if (!host.isNullOrBlank()) "$scheme://$host/" else ""
            }.getOrDefault("")
            if (referer.isNotBlank()) headers[refererKey] = referer
        }
        source.header = GSON.toJson(headers)
        source.enabledCookieJar = true

        // 文档 §8 自查清单：生成结果里不允许出现这些失败写法
        val contentRule = source.getContentRule().content.orEmpty()
        val forbidden = listOf("startBrowser", "readBookConfig", "data:", "MPD")
        if (forbidden.any { contentRule.contains(it, true) }) {
            AppLog.put("[AI书源] 视频源正文规则含禁用写法，改用直链提取 JS：$contentRule")
            source.getContentRule().content = VIDEO_CONTENT_EXTRACT_JS
        }
    }

    /** 视频源目录全候选竞速：与 refineTocRules 同构，按「集数」语义择优 */
    private fun refineTocRulesVideo(
        source: BookSource,
        pages: List<Page>,
        onProgress: (String) -> Unit
    ) {
        val toc = source.getTocRule()
        val existing = toc.chapterList
        val page = pages.firstOrNull { it.role == "目录页" }?.takeIf { it.html.isNotBlank() } ?: return
        val cands = ArrayList<String>()
        if (!existing.isNullOrBlank()) cands.add(existing)
        cands.addAll(autoEpisodeCandidates(page.html, page.url))
        if (cands.size <= 1) return
        val best = pickBestEpisodeList(page.html, cands) ?: return
        val scored = scoreEpisodeSel(page.html, existing)
        val chosen = if (existing.isNullOrBlank()) {
            if (best.count >= 2) best.sel else existing
        } else if (scored != null && scored.count > best.count) {
            scored.sel
        } else {
            best.sel
        }
        if (chosen == existing) return
        toc.chapterList = chosen
        toc.chapterName = "$chosen@text"
        toc.chapterUrl = "$chosen@href"
        onProgress("剧集优选：$chosen（${best.count} 条剧集）")
    }
}
