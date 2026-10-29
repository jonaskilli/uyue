package io.legado.app.help.source

import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.explore.ExploreAdapter.Companion.exploreInfoMapList
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 采用md5作为key可以在分类修改后自动重新计算,不需要手动刷新
 */

private val mutexMap by lazy { hashMapOf<String, Mutex>() }
private val exploreKindsMap by lazy { ConcurrentHashMap<String, List<ExploreKind>>() }
private val aCache by lazy { ACache.get("explore") }

private fun BookSource.getExploreKindsKey(): String {
    return MD5Utils.md5Encode(bookSourceUrl + exploreUrl)
}

private fun BookSourcePart.getExploreKindsKey(): String {
    return getBookSource()!!.getExploreKindsKey()
}

suspend fun BookSourcePart.exploreKinds(): List<ExploreKind> {
    return getBookSource()!!.exploreKinds()
}

suspend fun BookSource.exploreKinds(): List<ExploreKind> {
    val exploreKindsKey = getExploreKindsKey()
    exploreKindsMap[exploreKindsKey]?.let { return it }
    val exploreUrl = exploreUrl
    if (exploreUrl.isNullOrBlank()) {
        return emptyList()
    }
    val mutex = mutexMap[bookSourceUrl] ?: Mutex().apply { mutexMap[bookSourceUrl] = this }
    mutex.withLock {
        exploreKindsMap[exploreKindsKey]?.let { return it }
        val kinds = arrayListOf<ExploreKind>()
        withContext(Dispatchers.IO) {
            kotlin.runCatching {
                val ruleStr = when {
                    exploreUrl.startsWith("@js:", true) -> {
                        aCache.getAsString(exploreKindsKey)?.takeIf { it.isNotBlank() } ?: run {
                            val exploreInfoMap = exploreInfoMapList[bookSourceUrl] ?: InfoMap(bookSourceUrl).also {
                                exploreInfoMapList.put(bookSourceUrl, it)
                            }
                            runScriptWithContext {
                                evalJS(exploreUrl.substring(4)) {
                                    put("infoMap", exploreInfoMap)
                                }.toString().trim()
                            }.also {
                                aCache.put(exploreKindsKey, it)
                            }
                        }
                    }
                    exploreUrl.startsWith("<js>", true) -> {
                        aCache.getAsString(exploreKindsKey)?.takeIf { it.isNotBlank() } ?: run {
                            val exploreInfoMap = exploreInfoMapList[bookSourceUrl] ?: InfoMap(bookSourceUrl).also {
                                exploreInfoMapList.put(bookSourceUrl, it)
                            }
                            runScriptWithContext {
                                evalJS(exploreUrl.substring(4, exploreUrl.lastIndexOf("<"))) {
                                    put("infoMap", exploreInfoMap)
                                }.toString().trim()
                            }.also {
                                aCache.put(exploreKindsKey, it)
                            }
                        }
                    }
                    else -> exploreUrl
                }
                if (ruleStr.isJsonArray()) {
                    GSON.fromJsonArray<ExploreKind>(ruleStr).getOrThrow().let {
                        kinds.addAll(it)
                    }
                } else {
                    ruleStr.split("(&&|\n)+".toRegex()).forEach { kindStr ->
                        val kindCfg = kindStr.split("::")
                        kinds.add(ExploreKind(kindCfg.first(), kindCfg.getOrNull(1)))
                    }
                }
            }.onFailure {
                kinds.add(ExploreKind("ERROR:${it.localizedMessage}", it.stackTraceToString()))
                it.printOnDebug()
            }
        }
        exploreKindsMap[exploreKindsKey] = kinds
        return kinds
    }
}

suspend fun BookSourcePart.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

suspend fun BookSource.clearExploreKindsCache() {
    withContext(Dispatchers.IO) {
        val exploreKindsKey = getExploreKindsKey()
        aCache.remove(exploreKindsKey)
        exploreKindsMap.remove(exploreKindsKey)
    }
}

fun BookSource.exploreKindsJson(): String {
    val exploreKindsKey = getExploreKindsKey()
    return aCache.getAsString(exploreKindsKey)?.takeIf { it.isJsonArray() }
        ?: exploreUrl.takeIf { it.isJsonArray() }
        ?: ""
}

fun BookSource.getBookType(): Int {
    return when (bookSourceType) {
        BookSourceType.file -> BookType.text or BookType.webFile
        BookSourceType.image -> BookType.image
        BookSourceType.audio -> BookType.audio
        BookSourceType.video -> BookType.video
        else -> BookType.text
    }
}

/**
 * 分组或书源名里出现这些关键词即视为视频源。
 *
 * **必须与 BookSourceDao.flowVideoSources() 系列的 like 条件逐条保持一致**：
 * 视频主页按它筛源，书籍路由按它判定，两处一旦失配就会出现
 * "视频主页能播、继续阅读却开成小说阅读页"的错位。改这里就要改那里。
 *
 * 分组是用户最能直接控制的口径：某个源因为规则写法特殊没被自动认出来时，
 * 在书源管理里把分组改成含「视频」即可强制收纳进视频源。
 */
val VIDEO_GROUP_KEYWORDS = arrayOf(
    "视频", "視頻", "影视", "电影", "劇集", "剧集", "电视", "动漫", "短剧"
)

/**
 * 规则特征：只写关键字、不写点号。
 *
 * 早先匹配的是 `.m3u8` / `.mp4`，可不少源的规则里是 `m3u8?xxx`、`.m3u8` 被拆在
 * 字符串拼接里、或写的是 `contains("m3u8")` 这类判断，带点匹配就漏了。
 * 规则里出现 m3u8/mp4 基本只可能是视频，放宽不会误伤小说源。
 */
private val VIDEO_RULE_HINTS = arrayOf("m3u8", "mp4")

/**
 * 是否为视频源。
 *
 * 生态里的视频书源大多没把 bookSourceType 标成 4，因此不能只看 [bookSourceType]，
 * 要依次叠加：分组 → 书源名 → 规则特征，与视频主页的选源口径对齐。
 */
fun BookSource.isVideoSource(): Boolean {
    // 视频源特性关闭：一律不判视频源，聚合源（小说+视频+漫画）按普通书源走
    if (!AppConfig.videoFeatureEnabled) return false
    if (bookSourceType == BookSourceType.video) return true
    // 分组优先（用户在书源管理里改一下就能强制收纳），分组为空时再看书源名兜底
    val group = bookSourceGroup ?: ""
    if (group.isVideoGroupName()) return true
    if (bookSourceName.isVideoGroupName()) return true
    // ruleContent 是 ContentRule 对象，库里是 JSON 文本（DAO 的 like 就打在它上面），
    // 这里同样序列化后再匹配，保证与 SQL 口径一致
    val rule = ruleContent?.let { GSON.toJson(it) } ?: ""
    return rule.hasVideoRuleHint()
}

/**
 * 规则文本（库里 ruleContent 的 JSON 原文）是否含视频特征。
 *
 * 视频主页用的是 [io.legado.app.data.dao.VideoSourceRow]，它直接拿到 JSON 原文，
 * 不必先把 ContentRule 序列化一遍——省掉每个源一次 GSON 往返。
 */
fun String.hasVideoRuleHint(): Boolean =
    VIDEO_RULE_HINTS.any { contains(it, ignoreCase = true) }

/** 分组或书源名是否命中视频关键词（书源管理里批量标记时复用同一口径） */
fun String.isVideoGroupName(): Boolean =
    VIDEO_GROUP_KEYWORDS.any { contains(it) } ||
        contains("video", ignoreCase = true) ||
        contains("movie", ignoreCase = true)
