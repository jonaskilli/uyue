package io.legado.app.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.BackstageWebView
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
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
        loginUrl: String? = null,
        includeExplore: Boolean = false,
        fixRounds: Int = 2,
        manualBookInfo: String? = null,
        manualTocUrl: String? = null,
        singleBook: Boolean = false,
        onProgress: (String) -> Unit
    ): Result<GenResult> = withContext(Dispatchers.IO) {
        runCatching {
            val seed = normalize(seedUrl)
            AppLog.put(
                "[AI书源] generate 开始：manualBookInfo=${manualBookInfo?.take(120)}, " +
                "manualTocUrl=${manualTocUrl?.take(120)}, keyword=$keyword"
            )
            // 手动指定的页面：用户填了就优先使用，不再靠程序猜
            val manualInfo = manualBookInfo?.trim()?.takeIf { it.isNotBlank() }?.let { normalize(it) }
            // 目录框里可以填网址，也可以直接填目录入口的链接文字（如「查看全部章节」「......」）
            val manualTocRaw = manualTocUrl?.trim()?.takeIf { it.isNotBlank() }
            val manualToc = manualTocRaw?.takeIf { looksLikeUrl(it) }?.let { normalize(it) }
            val manualTocText = manualTocRaw?.takeUnless { looksLikeUrl(it) }
            val meta = HashMap<String, String>()
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
            var testKeyword = keyword
            if (!singleBook) {
                onProgress("抓取首页…")
                homeHtml = fetch(seed) ?: error("无法访问该地址：$seed")
                pages.add(Page("首页", seed, homeHtml))
                homeCheck = aiCheckPage(CHECK_HOME, homeHtml, seed)
                searchTemplate = homeCheck?.str("searchUrl")
                testKeyword = homeCheck?.str("suggestKeyword").takeUnless { it.isNullOrBlank() }
                    ?: keyword
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
                // 纯代码复核只记录提示，不覆盖 AI 给的结果——原逻辑优先，
                // 真正替换交给下面「搜索被判定不可用」时的兜底
                val listHtml0 = listHtml
                if (listHtml0 != null && countBookLinks(listHtml0, listUrl) < 2) {
                    meta["搜索结果复核"] = "搜索页里没能数出指向书籍详情页的链接，" +
                        "请核对 searchUrl 是否真能搜到书（{{key}} 位置、参数名、隐藏字段是否正确）。"
                }
            } else {
                onProgress("首页未识别出搜索入口…")
                searchDisabled = true
            }
            // ===== 原搜索逻辑判定不可用后的兜底：改按站内搜索表单推导地址 =====
            if (searchDisabled) {
                onProgress("搜索入口不可用，改从站内搜索表单推导…")
                bestSearchCandidate(homeHtml, seed, testKeyword)?.let { (tpl, html) ->
                    val url = fillKey(tpl.substringBefore(",{\""), testKeyword)
                    pages.add(Page("搜索结果页(表单推导)", url, html))
                    searchTemplate = tpl
                    listUrl = url
                    listHtml = html
                    searchCheck = aiCheckPage(CHECK_SEARCH, html, url, testKeyword)
                    searchDisabled = false
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
            var tocPagerRule: String? = null
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
                tocPager(tocHtml0, tocUrl.ifBlank { seed })?.let { (txt, rule) ->
                    meta["目录分页"] = txt
                    tocPagerRule = rule
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
            var contentUrl = tocCheck?.str("firstChapterUrl")?.takeIf { it.startsWith("http") }
            val tocHtmlFinal = tocHtml
            if (contentUrl == null && tocHtmlFinal != null) {
                contentUrl = firstChapterLink(
                    tocHtmlFinal, tocUrl.ifBlank { seed }, desc = chapterOrder == "desc"
                )
            }
            if (contentUrl != null) {
                onProgress("抓取正文页…")
                var contentHtml = fetch(contentUrl)
                // 死链兜底：详情页上的「点击阅读」常是 301 回详情页的死链，
                // 抓回来的页面若与详情页相同，改用目录页里的章节链接
                if (contentHtml != null && detailHtml != null && pageSimilar(contentHtml, detailHtml)) {
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
                    pages.add(Page("正文页", url, html))
                    if (singleBook) {
                        // 单本模式：跳过 AI 质检，正文规则是否可用由后面的实测验证
                        meta["正文质检"] = "单本模式：已跳过 AI 质检"
                    } else {
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
            var source = askGenerate(pages, seed, searchTemplate, meta, includeExplore, singleBook)
                ?: error("AI 未返回书源。请确认「角色档案设置」里已配置接口（接口@@模型@@密钥）")
            ensureMobileUa(source)
            // 目录地址正则兜底：把「详情页→目录页」的 AI 正则写进书源，
            // 运行时若书籍详情页取不到目录入口链接，就靠它把详情页地址换成目录页地址
            aiTocRegex?.let { source.getBookInfoRule().tocUrlRegex = it }
            // 目录全候选竞速：AI 规则与纯代码推导的所有容器在真实页面上比拼，取章节最完整的
            refineTocRules(source, pages, onProgress)
            // 写入登录地址：Cookie 失效时可在「书源管理→登录」重新登录，
            // 登录后的 Cookie 由 legado 按域名自动注入书源请求
            if (!loginUrl.isNullOrBlank()) {
                source.loginUrl = loginUrl
            }

            // ---------- 阶段三：五链路实测 + 失败修正 ----------
            val maxRounds = fixRounds.coerceIn(0, 8)
            var fixRound = 0
            var report = verifySource(source, testKeyword, pages, onProgress, singleBook)
            while (report.any { !it.ok } && fixRound < maxRounds) {
                fixRound++
                onProgress(
                    "验证未通过（${report.filter { !it.ok }.joinToString("、") { it.step }}），" +
                        "正在让 AI 修正（第 $fixRound/$maxRounds 轮）…"
                )
                val fixed = fixSource(source, report, pages) ?: break
                ensureMobileUa(fixed)
                refineTocRules(fixed, pages, onProgress)
                source = fixed
                report = verifySource(source, testKeyword, pages, onProgress, singleBook)
            }

            // ---------- 阶段四：AI 修不动时的兜底 ----------
            // 只在上面 maxRounds 轮 AI 修正全部用尽后触发；触发后重新验证，
            // 并再给同样 maxRounds 轮的 AI 修正机会——兜底与原流程享有同等修复次数
            if (report.any { !it.ok }) {
                val beforeFail = report.count { !it.ok }
                val backupTocUrl = source.getBookInfoRule().tocUrl
                val backupSearchUrl = source.searchUrl
                val changed = applyFallbacks(
                    source, report, homeHtml, seed, testKeyword, tocUrl, detailUrlFinal, contentUrl
                )
                if (changed) {
                    onProgress("AI 修正 $maxRounds 轮仍未通过，改用兜底方案重试…")
                    var after = verifySource(source, testKeyword, pages, onProgress, singleBook)
                    // 兜底必须让情况变好，否则原样撤回——不能因为兜底把原本能用的一环弄坏
                    if (after.count { !it.ok } >= beforeFail) {
                        onProgress("兜底未改善，已撤回原有规则…")
                        source.getBookInfoRule().tocUrl = backupTocUrl
                        source.searchUrl = backupSearchUrl
                        after = verifySource(source, testKeyword, pages, onProgress, singleBook)
                    }
                    report = after
                    var round2 = 0
                    while (report.any { !it.ok } && round2 < maxRounds) {
                        round2++
                        onProgress(
                            "兜底方案仍未通过（${
                                report.filter { !it.ok }.joinToString("、") { it.step }
                            }），正在让 AI 修正（第 $round2/$maxRounds 轮）…"
                        )
                        val fixed = fixSource(source, report, pages) ?: break
                        ensureMobileUa(fixed)
                        refineTocRules(fixed, pages, onProgress)
                        source = fixed
                        report = verifySource(source, testKeyword, pages, onProgress, singleBook)
                    }
                    fixRound += round2
                }
            }

            // ---------- 阶段五：目录分页的翻页规则补全 ----------
            // 目录验证能过、但规则里没有 nextTocUrl 时，书源只能拿到第一页章节。
            // 补上后重新验证：结果没变差就保留，变差就撤回（不改变原本可用的书源）
            val pager = tocPagerRule
            val tocOk = report.firstOrNull { it.step == "目录" }?.ok == true
            if (pager != null && tocOk && source.getTocRule().nextTocUrl.isNullOrBlank()) {
                source.getTocRule().nextTocUrl = pager
                val recheck = verifySource(source, testKeyword, pages, onProgress, singleBook)
                if (recheck.firstOrNull { it.step == "目录" }?.ok == true) {
                    report = recheck
                } else {
                    // 补翻页反而把目录弄坏了，撤回
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

            GenResult(source, report, fixRound)
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

    /**
     * 抓取页面：先用内置客户端，失败自动降级到 legado 全局网络栈。
     * 降级原因（实测案例）：
     * · 部分站点服务器有 HTTP/2 流 Bug（m.qbmfxs.com 动态路径 INTERNAL_ERROR 崩流），
     *   浏览器会自动降级 HTTP/1.1 而 OkHttp 不会 → 内置客户端已强制 HTTP/1.1；
     * · 部分 WAF 对请求头不全的请求直接重置连接（需 Accept/Referer 等完整头）；
     * · 设备上浏览器有安全 DNS 可绕过 DNS 污染，直连 OkHttp 不能——
     *   全局栈带 Cronet 拦截器（与 Chrome 同源网络栈），行为与浏览器一致。
     */
    private fun fetch(url: String, pcUa: Boolean = false): String? {
        fetchBy(client, url, pcUa)?.let { return it }
        // 系统 DNS 失败 → 依次尝试 DoH（防 DNS 污染）
        for (c in dohClients) {
            fetchBy(c, url, pcUa)?.let {
                AppLog.put("[AI书源] 系统 DNS 抓取失败，DoH 解析后成功：$url")
                return it
            }
        }
        fetchBy(globalClient, url, pcUa)?.let {
            AppLog.put("[AI书源] 已通过全局网络栈降级抓取成功：$url")
            return it
        }
        // 最后兜底：WebView 真浏览器内核抓取——与用户浏览器完全同源
        // （系统网络栈、QUIC、ECH 等，浏览器能开的它就能开），解决 SNI 干扰等顽固问题
        return fetchByWebView(url)?.also {
            AppLog.put("[AI书源] 所有网络栈均失败，WebView 内核抓取成功：$url")
        }
    }

    /** 用后台 WebView（真浏览器内核）抓取页面，必须在后台线程调用 */
    private fun fetchByWebView(url: String): String? = runCatching {
        runBlocking {
            BackstageWebView(
                url = url,
                headerMap = mapOf("User-Agent" to MOBILE_UA),
                tag = "AiBookSource"
            ).getStrResponse().body?.takeIf { it.isNotBlank() }
        }
    }.onFailure {
        AppLog.put("[AI书源] WebView 抓取失败：${it.message}", it)
    }.getOrNull()

    private fun fetchBy(c: OkHttpClient, url: String, pcUa: Boolean): String? = runCatching {
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
        return postBy(postClient, url, body, charset)
            ?: postBy(globalClient, url, body, charset)?.also {
                AppLog.put("[AI书源] 搜索 POST 直连失败，已通过全局网络栈降级成功：$url")
            }
    }

    private fun postBy(c: OkHttpClient, url: String, body: String, charset: String): String? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Referer", runCatching {
                val u = java.net.URI(url)
                "${u.scheme}://${u.host}/"
            }.getOrDefault(""))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=$charset")
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
            response.body.string().takeIf { it.isNotBlank() }
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
            val html = fetchPage(tpl, keyword) ?: continue
            val n = countBookLinks(html, fillKey(tpl.substringBefore(",{\""), keyword))
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
    private fun tocPager(html: String, baseUrl: String): Pair<String, String>? {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return null
        val words = listOf("下一页", "下页", "后页")
        val hit = doc.select("a[href]").firstOrNull { a -> words.any { a.text().trim() == it } }
            ?: doc.select("a[href]").firstOrNull { a -> words.any { a.text().trim().contains(it) } }
            ?: return null
        val href = hit.attr("abs:href")
        if (!href.startsWith("http")) return null
        val word = words.first { hit.text().trim().contains(it) }
        val rule = "text.$word@href"
        val parent = hit.parent()
        val boxCls = parent?.className().orEmpty().trim().split(Regex("\\s+")).firstOrNull()
        val text = buildString {
            append("【目录分页】这个目录是分页的：当前页只列出一部分章节，")
            append("「").append(hit.text().trim()).append("」指向下一页 ").append(href).append("。\n")
            append("ruleToc.nextTocUrl 必须填，否则书源只能拿到第一页的章节；可直接写：").append(rule)
            if (!boxCls.isNullOrBlank()) append("（也可写 class.").append(boxCls).append("@tag.a@href）")
            append("。\n分页导航 HTML：")
            append((parent?.outerHtml() ?: hit.outerHtml()).replace(Regex("\\s+"), " ").trim().take(400))
        }
        return text to rule
    }

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
        singleBook: Boolean = false
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
        val base = USER_PROMPT
            .replace("{seed}", seedUrl)
            .replace("{searchHint}", searchHint)
            .replace("{meta}", metaText)
            .replace("{pages}", sb.toString())
            .replace("{exploreFields}", exploreFields)
            .replace("{exploreRule}", exploreRule)
            .replace("{exploreHardRule}", exploreHardRule) + PATTERN_LIBRARY
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
        val failed = report.filter { !it.ok }.map { it.step }.toSet()
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
            bestSearchCandidate(homeHtml, seed, keyword)?.let { (tpl, _) ->
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
                "发现" -> pages.firstOrNull { it.role.startsWith("分类列表页") }
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
        val raw = chat(user, 8000) ?: return null
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
        val css = legadoToCss(sel) ?: return null
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return null
        val els = runCatching { doc.select(css) }.getOrNull() ?: return null
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
        if (best.sel == cur) return
        if (curScore != null) {
            // 原规则可用：候选必须不更少、且连续段严格更长，才有资格替换
            if (best.count < curScore.count || best.run <= curScore.run) return
        } else {
            // 原规则取不到：候选自己要像个完整目录
            if (best.count < 8 || best.run < 3) return
        }
        toc.chapterList = best.sel
        onProgress("目录优选：${best.sel} 胜出（连续 ${best.run} 章 / 共 ${best.count} 条）")
    }

    // ==================== 阶段三：四链路实测 ====================

    /** 用 App 自带的 WebBook 引擎按真实使用路径验证：验证通过 = 实际可用 */
    private suspend fun verifySource(
        source: BookSource,
        keyword: String,
        pages: List<Page>,
        onProgress: (String) -> Unit,
        skipSearch: Boolean = false
    ): List<VerifyItem> {
        val report = ArrayList<VerifyItem>(4)
        var book: Book? = null

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
                report.add(VerifyItem("搜索", true, "搜到 ${books.size} 本，第一本《${first.name}》"))
            }.onFailure {
                report.add(VerifyItem("搜索", false, it.message ?: "执行异常"))
            }
        }

        onProgress("验证 ②/⑤：书籍详情…")
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

        onProgress("验证 ③/⑤：目录…")
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

        onProgress("验证 ④/⑤：正文…")
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
                report.add(
                    VerifyItem("发现", true, "分类页取到《${books.first().name}》等 ${books.size} 本")
                )
            }.onFailure {
                report.add(VerifyItem("发现", false, it.message ?: "执行异常"))
            }
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
        找出规则问题并修正，输出**完整**的修正后书源 JSON（不要解释、不要代码块）。

        【提示】若失败原因是「Too many follow-up requests」或 30x 重定向循环，
        多半不是规则问题，而是请求被站点反爬——保留书源现有的 header（User-Agent）配置即可，不要删除。

        【验证失败环节】
        {fails}

        【当前书源 JSON】
        {source}
    """.trimIndent()
}
