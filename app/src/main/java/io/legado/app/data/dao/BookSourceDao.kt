package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.utils.cnCompare
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import io.legado.app.constant.BookSourceType
import io.legado.app.help.source.hasVideoRuleHint
import io.legado.app.help.source.isVideoGroupName
import io.legado.app.help.source.isVideoSource
import kotlinx.coroutines.flow.map

@Dao
interface BookSourceDao {

    @Query("select * from book_sources_part order by customOrder asc")
    fun flowAll(): Flow<List<BookSourcePart>>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.bookSourceName like '%' || :searchKey || '%'
        or b.bookSourceGroup like '%' || :searchKey || '%'
        or b.bookSourceUrl like '%' || :searchKey || '%'
        or b.bookSourceComment like '%' || :searchKey || '%' 
        order by b.customOrder asc"""
    )
    fun flowSearch(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources 
        where bookSourceName like '%' || :searchKey || '%'
        or bookSourceGroup like '%' || :searchKey || '%'
        or bookSourceUrl like '%' || :searchKey || '%'
        or bookSourceComment like '%' || :searchKey || '%' 
        order by customOrder asc"""
    )
    fun search(searchKey: String): List<BookSource>

    @Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 
        and (b.bookSourceName like '%' || :searchKey || '%' 
        or b.bookSourceGroup like '%' || :searchKey || '%' 
        or b.bookSourceUrl like '%' || :searchKey || '%'  
        or b.bookSourceComment like '%' || :searchKey || '%')
        order by b.customOrder asc"""
    )
    fun flowSearchEnabled(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where bookSourceGroup = :searchKey
        or bookSourceGroup like :searchKey || ',%' 
        or bookSourceGroup like  '%,' || :searchKey
        or bookSourceGroup like  '%,' || :searchKey || ',%' 
        order by customOrder asc"""
    )
    fun flowGroupSearch(searchKey: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources 
        where bookSourceGroup = :searchKey
        or bookSourceGroup like :searchKey || ',%' 
        or bookSourceGroup like  '%,' || :searchKey
        or bookSourceGroup like  '%,' || :searchKey || ',%' 
        order by customOrder asc"""
    )
    fun groupSearch(searchKey: String): List<BookSource>

    @Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    fun flowEnabled(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabled = 0 order by customOrder asc")
    fun flowDisabled(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 and hasExploreUrl = 1 order by customOrder asc"""
    )
    fun flowExplore(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where hasLoginUrl = 1 order by customOrder asc")
    fun flowLogin(): Flow<List<BookSourcePart>>

    /**
     * 视频书源，判定条件（满足其一即可，无需手动改类型）：
     * 1. 书源类型被显式设为视频（bookSourceType = 4）
     * 2. **分组名**含视频类关键词（视频/影视/电影/剧集/电视/动漫/短剧/video/movie）
     * 3. **书源名**含同样的关键词（分组为空时靠它兜底，很多源只把类型写在名字里）
     * 4. 正文规则里出现 m3u8/mp4（强特征，不再要求带点号）
     *
     * 现有生态里的视频书源几乎都是 type = 0 的普通书源，只按类型过滤会让视频主页一直空着。
     * 分组是用户最能直接控制的口径：某个源因为规则写法特殊没被自动认出来时，
     * 在书源管理里把分组改成含「视频」两个字即可强制收纳。
     *
     * 条件必须与 [io.legado.app.help.source.BookSourceExtensions.isVideoSource] 逐条一致
     * （关键词见 VIDEO_GROUP_KEYWORDS），否则会出现"主页能播、继续阅读却开成小说页"的错位。
     */
    @Query("select * from book_sources where enabled = 1 order by customOrder asc")
    fun flowEnabledBookSources(): Flow<List<BookSource>>

    @Query("select * from book_sources where enabled = 1 order by customOrder asc")
    fun allEnabledBookSources(): List<BookSource>

    /**
     * 视频主页专用的**轻量行**：展示用的 14 列 + ruleContent 的原始 JSON 文本。
     *
     * 不要换成 `select * from book_sources`——那样会把规则、jsLib、header、发现规则等
     * 大字段全部读进内存，几百个源时仅这一步就要 2~3 秒。
     */
    @Query(
        """select bp.*, b.ruleContent as ruleContentJson
        from book_sources_part bp join book_sources b on b.bookSourceUrl = bp.bookSourceUrl
        where bp.enabled = 1 order by bp.customOrder asc"""
    )
    fun flowEnabledVideoRows(): Flow<List<VideoSourceRow>>

    /** 同上，一次性取（搜索模式用） */
    @Query(
        """select bp.*, b.ruleContent as ruleContentJson
        from book_sources_part bp join book_sources b on b.bookSourceUrl = bp.bookSourceUrl
        where bp.enabled = 1 order by bp.customOrder asc"""
    )
    fun getEnabledVideoRows(): List<VideoSourceRow>

    @Query("update book_sources set bookSourceType = :type where bookSourceUrl = :bookSourceUrl")
    fun upSourceType(bookSourceUrl: String, type: Int)

    @Transaction
    fun upSourceType(type: Int, bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            upSourceType(bs.bookSourceUrl, type)
        }
    }

    @Query(
        """select * from book_sources_part 
        where bookSourceGroup is null or bookSourceGroup = '' or bookSourceGroup like '%未分组%'
        order by customOrder asc"""
    )
    fun flowNoGroup(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabledExplore = 1 order by customOrder asc")
    fun flowEnabledExplore(): Flow<List<BookSourcePart>>

    @Query("select * from book_sources_part where enabledExplore = 0 order by customOrder asc")
    fun flowDisabledExplore(): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 
        and hasExploreUrl = 1 
        and (bookSourceGroup like '%' || :key || '%' 
            or bookSourceName like '%' || :key || '%') 
        order by customOrder asc"""
    )
    fun flowExplore(key: String): Flow<List<BookSourcePart>>

    @Query(
        """select * from book_sources_part 
        where enabledExplore = 1 
        and hasExploreUrl = 1 
        and (bookSourceGroup = :key
            or bookSourceGroup like :key || ',%' 
            or bookSourceGroup like  '%,' || :key
            or bookSourceGroup like  '%,' || :key || ',%') 
        order by customOrder asc"""
    )
    fun flowGroupExplore(key: String): Flow<List<BookSourcePart>>

    @Query("select distinct bookSourceGroup from book_sources where trim(bookSourceGroup) <> ''")
    fun flowGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    fun flowEnabledGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select distinct bookSourceGroup from book_sources 
        where enabledExplore = 1 
        and trim(exploreUrl) <> '' 
        and trim(bookSourceGroup) <> ''
        order by customOrder"""
    )
    fun flowExploreGroupsUnProcessed(): Flow<List<String>>

    @Query(
        """select * from book_sources 
        where bookSourceGroup like '%' || :group || '%' order by customOrder asc"""
    )
    fun getByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources 
        where enabled = 1 
        and (bookSourceGroup = :group
            or bookSourceGroup like :group || ',%' 
            or bookSourceGroup like  '%,' || :group
            or bookSourceGroup like  '%,' || :group || ',%')
        order by customOrder asc"""
    )
    fun getEnabledByGroup(group: String): List<BookSource>

    @Query(
        """select * from book_sources_part 
        where enabled = 1 
        and (bookSourceGroup = :group
            or bookSourceGroup like :group || ',%' 
            or bookSourceGroup like  '%,' || :group
            or bookSourceGroup like  '%,' || :group || ',%')
        order by customOrder asc"""
    )
    fun getEnabledPartByGroup(group: String): List<BookSourcePart>

    @Query(
        """select * from book_sources 
        where bookUrlPattern != 'NONE' and bookSourceType = :type order by customOrder asc"""
    )
    fun getEnabledByType(type: Int): List<BookSource>

    @Query("select * from book_sources where enabled = 1 and bookSourceUrl = :baseUrl")
    fun getBookSourceAddBook(baseUrl: String): BookSource?

    @get:Query(
        """select bp.* 
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl
        where b.enabled = 1 
        and trim(b.bookUrlPattern) <> '' 
        and trim(b.bookUrlPattern) <> 'NONE' 
        order by b.customOrder"""
    )
    val hasBookUrlPattern: List<BookSourcePart>

    @get:Query("select * from book_sources where bookSourceGroup is null or bookSourceGroup = ''")
    val noGroup: List<BookSource>

    @get:Query("select * from book_sources order by customOrder asc")
    val all: List<BookSource>

    @get:Query("select * from book_sources_part order by customOrder asc")
    val allPart: List<BookSourcePart>

    @get:Query("select * from book_sources where enabled = 1 order by customOrder")
    val allEnabled: List<BookSource>

    @get:Query("select * from book_sources_part where enabled = 1 order by customOrder asc")
    val allEnabledPart: List<BookSourcePart>

    @get:Query("select * from book_sources where enabled = 0 order by customOrder")
    val allDisabled: List<BookSource>

    @get:Query(
        """select * from book_sources 
        where bookSourceGroup is null or bookSourceGroup = '' or bookSourceGroup like '%未分组%'"""
    )
    val allNoGroup: List<BookSource>

    @get:Query("select * from book_sources where enabledExplore = 1 order by customOrder")
    val allEnabledExplore: List<BookSource>

    @get:Query("select * from book_sources where enabledExplore = 0 order by customOrder")
    val allDisabledExplore: List<BookSource>

    @get:Query("select * from book_sources where loginUrl is not null and loginUrl != ''")
    val allLogin: List<BookSource>

    @get:Query(
        """select bp.*
        from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl 
        where b.enabled = 1 and b.bookSourceType = 0 order by b.customOrder"""
    )
    val allTextEnabledPart: List<BookSourcePart>

    @get:Query(
        """select distinct bookSourceGroup from book_sources 
        where trim(bookSourceGroup) <> ''"""
    )
    val allGroupsUnProcessed: List<String>

    @get:Query(
        """select distinct bookSourceGroup from book_sources 
        where enabled = 1 and trim(bookSourceGroup) <> ''"""
    )
    val allEnabledGroupsUnProcessed: List<String>

    @Query("select * from book_sources where bookSourceUrl = :key")
    fun getBookSource(key: String): BookSource?

    @Query("select * from book_sources_part where bookSourceUrl = :key")
    fun getBookSourcePart(key: String): BookSourcePart?

    @Query("select count(*) from book_sources")
    fun allCount(): Int

    @Query("SELECT EXISTS(select 1 from book_sources where bookSourceUrl = :key)")
    fun has(key: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookSource: BookSource)

    @Update
    fun update(vararg bookSource: BookSource)

    @Delete
    fun delete(vararg bookSource: BookSource)

    @Query("delete from book_sources where bookSourceUrl = :key")
    fun delete(key: String)

    @Transaction
    fun delete(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            delete(bs.bookSourceUrl)
        }
    }

    @get:Query("select min(customOrder) from book_sources")
    val minOrder: Int

    @get:Query("select max(customOrder) from book_sources")
    val maxOrder: Int

    @get:Query(
        """select exists (select 1 
        from book_sources group by customOrder having count(customOrder) > 1)"""
    )
    val hasDuplicateOrder: Boolean

    @Query("update book_sources set enabled = :enable where bookSourceUrl = :bookSourceUrl")
    fun enable(bookSourceUrl: String, enable: Boolean)

    @Transaction
    fun enable(enable: Boolean, bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            enable(bs.bookSourceUrl, enable)
        }
    }

    @Query("update book_sources set enabledExplore = :enable where bookSourceUrl = :bookSourceUrl")
    fun enableExplore(bookSourceUrl: String, enable: Boolean)

    @Transaction
    fun enableExplore(enable: Boolean, bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            enableExplore(bs.bookSourceUrl, enable)
        }
    }

    @Query(
        """update book_sources 
        set customOrder = :customOrder where bookSourceUrl = :bookSourceUrl"""
    )
    fun upOrder(bookSourceUrl: String, customOrder: Int)

    @Transaction
    fun upOrder(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            upOrder(bs.bookSourceUrl, bs.customOrder)
        }
    }

    fun upOrder(bookSource: BookSourcePart) {
        upOrder(bookSource.bookSourceUrl, bookSource.customOrder)
    }

    @Query(
        """update book_sources 
        set bookSourceGroup = :bookSourceGroup where bookSourceUrl = :bookSourceUrl"""
    )
    fun upGroup(bookSourceUrl: String, bookSourceGroup: String)

    @Transaction
    fun upGroup(bookSources: List<BookSourcePart>) {
        for (bs in bookSources) {
            bs.bookSourceGroup?.let { upGroup(bs.bookSourceUrl, it) }
        }
    }

    private fun dealGroups(list: List<String>): List<String> {
        val groups = linkedSetOf<String>()
        list.forEach {
            it.splitNotBlank(AppPattern.splitGroupRegex).forEach { group ->
                groups.add(group)
            }
        }
        return groups.sortedWith { o1, o2 ->
            o1.cnCompare(o2)
        }
    }

    fun allGroups(): List<String> = dealGroups(allGroupsUnProcessed)

    fun allEnabledGroups(): List<String> = dealGroups(allEnabledGroupsUnProcessed)

    fun flowGroups(): Flow<List<String>> {
        return flowGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }

    fun flowExploreGroups(): Flow<List<String>> {
        return flowExploreGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }

    fun flowEnabledGroups(): Flow<List<String>> {
        return flowEnabledGroupsUnProcessed().map { list ->
            dealGroups(list)
        }.flowOn(IO)
    }
}

/**
 * 视频书源判定（供视频主页/书籍路由），口径见 [io.legado.app.help.source.BookSourceExtensions.isVideoSource]
 * （类型=4 ∥ 分组关键词 ∥ 书源名关键词 ∥ 规则含 m3u8/mp4）。
 *
 * 早期实现把判定写成一条含二十多个 `or ... like` 的 SQL，Room 的 SQLite 语法解析器（ANTLR）
 * 会在此类超长布尔表达式上退化成指数级超慢解析，导致 KSP 编译阶段卡死、打不出 release APK。
 * 现改为「取轻量行 + Kotlin 侧过滤」，只拿展示字段与 ruleContent 的 JSON 原文：
 * 既不碰 ANTLR 的悬崖，也不用加载整条书源、更不用把 ContentRule 再 GSON 序列化一遍，
 * 判定口径与之前完全一致。
 */
fun BookSourceDao.flowVideoSources(): Flow<List<BookSource>> =
    flowEnabledBookSources().map { list -> list.filter { it.isVideoSource() } }

/** 轻量判定：只看类型/分组/书源名 + 规则 JSON 原文里有没有 m3u8/mp4 */
private fun VideoSourceRow.isVideo(): Boolean {
    if (bookSourceType == BookSourceType.video) return true
    val group = bookSourceGroup ?: ""
    if (group.isVideoGroupName()) return true
    if (bookSourceName.isVideoGroupName()) return true
    return (ruleContentJson ?: "").hasVideoRuleHint()
}

fun BookSourceDao.flowVideoSourcesPart(): Flow<List<BookSourcePart>> =
    flowEnabledVideoRows().map { list ->
        list.filter { it.isVideo() }.map { it.toBookSourcePart() }
    }.flowOn(IO)

fun BookSourceDao.getVideoSourceParts(): List<BookSourcePart> =
    getEnabledVideoRows().filter { it.isVideo() }.map { it.toBookSourcePart() }

fun BookSourceDao.flowVideoSourcesPart(key: String): Flow<List<BookSourcePart>> =
    flowEnabledVideoRows().map { list ->
        list.filter { src ->
            src.isVideo() && (
                src.bookSourceGroup?.contains(key, ignoreCase = true) == true ||
                    src.bookSourceName.contains(key, ignoreCase = true)
                )
        }.map { it.toBookSourcePart() }
    }.flowOn(IO)

/**
 * 视频主页筛选用的轻量行：展示字段 + [ruleContentJson]。
 *
 * [ruleContentJson] 直接取 `book_sources.ruleContent` 的**原始 JSON 文本**，不经过
 * Room 的类型转换器，因此不会为每个源解析一次 ContentRule，判定时只需在这段文本上找关键字。
 */
data class VideoSourceRow(
    var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var customOrder: Int = 0,
    var enabled: Boolean = true,
    var enabledExplore: Boolean = true,
    var hasLoginUrl: Boolean = false,
    var lastUpdateTime: Long = 0,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var hasExploreUrl: Boolean = false,
    var eventListener: Boolean = false,
    var bookSourceType: Int = 0,
    var ruleContentJson: String? = null,
)

private fun VideoSourceRow.toBookSourcePart(): BookSourcePart = BookSourcePart(
    bookSourceUrl = bookSourceUrl,
    bookSourceName = bookSourceName,
    bookSourceGroup = bookSourceGroup,
    customOrder = customOrder,
    enabled = enabled,
    enabledExplore = enabledExplore,
    hasLoginUrl = hasLoginUrl,
    lastUpdateTime = lastUpdateTime,
    respondTime = respondTime,
    weight = weight,
    hasExploreUrl = hasExploreUrl,
    eventListener = eventListener,
    bookSourceType = bookSourceType,
)

private fun BookSource.toBookSourcePart(): BookSourcePart = BookSourcePart(
    bookSourceUrl = bookSourceUrl,
    bookSourceName = bookSourceName,
    bookSourceGroup = bookSourceGroup,
    customOrder = customOrder,
    enabled = enabled,
    enabledExplore = enabledExplore,
    hasLoginUrl = !loginUrl.isNullOrBlank(),
    lastUpdateTime = lastUpdateTime,
    respondTime = respondTime,
    weight = weight,
    hasExploreUrl = !exploreUrl.isNullOrBlank(),
    eventListener = eventListener,
    bookSourceType = bookSourceType,
)