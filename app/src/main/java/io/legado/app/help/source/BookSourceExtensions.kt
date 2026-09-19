package io.legado.app.help.source

import com.script.rhino.runScriptWithContext
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.help.CacheManager
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
 * 书源分组里出现这些关键词即视为视频源。
 *
 * 必须与 BookSourceDao.flowVideoSources() 的 like 条件保持一致：
 * 视频主页按它筛源，书籍路由按它判定，两处一旦失配就会出现
 * "视频主页能播、继续阅读却开成小说阅读页"的错位。
 */
private val VIDEO_GROUP_KEYWORDS = arrayOf(
    "视频", "影视", "电影", "剧集", "电视", "动漫", "短剧"
)
private val VIDEO_RULE_HINTS = arrayOf(".m3u8", ".mp4")

/**
 * 是否为视频源。
 *
 * 生态里的视频书源大多没把 bookSourceType 标成 4（DAO 里 flowVideoSources 的注释也这么说），
 * 因此不能只看 [bookSourceType]，要叠加分组名与规则特征的模糊匹配，
 * 与视频主页的选源口径对齐。
 */
fun BookSource.isVideoSource(): Boolean {
    if (bookSourceType == BookSourceType.video) return true
    val group = bookSourceGroup ?: ""
    if (VIDEO_GROUP_KEYWORDS.any { group.contains(it) }) return true
    if (group.contains("video", ignoreCase = true)) return true
    if (group.contains("movie", ignoreCase = true)) return true
    // ruleContent 是 ContentRule 对象，库里是 JSON 文本（DAO 的 like 就打在它上面），
    // 这里同样序列化后再匹配，保证与 SQL 口径一致
    val rule = ruleContent?.let { GSON.toJson(it) } ?: ""
    return VIDEO_RULE_HINTS.any { rule.contains(it, ignoreCase = true) }
}
