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
     * 2. 分组名含视频类关键词（视频/影视/电影/剧集/电视/短剧/video 等）
     * 3. 正文规则里直接写了 m3u8/mp4（强特征）
     *
     * 现有生态里的视频书源几乎都是 type = 0 的普通书源，
     * 只按类型过滤会导致用户导入后视频主页一直是空的，
     * 因此这里加分组与规则特征兜底，让"导入即可见"尽量成立。
     * 规则里只调 startBrowser 打开网页播放器的源无法自动识别，
     * 仍需在书源管理里批量「标记为视频源」。
     */
    @Query(
        """select * from book_sources
        where enabled = 1
        and (bookSourceType = 4
            or bookSourceGroup like '%视频%'
            or bookSourceGroup like '%影视%'
            or bookSourceGroup like '%电影%'
            or bookSourceGroup like '%剧集%'
            or bookSourceGroup like '%电视%'
            or bookSourceGroup like '%动漫%'
            or bookSourceGroup like '%短剧%'
            or bookSourceGroup like '%video%'
            or bookSourceGroup like '%movie%'
            or ruleContent like '%.m3u8%'
            or ruleContent like '%.mp4%')
        order by customOrder asc"""
    )
    fun flowVideoSources(): Flow<List<BookSource>>

    /**
     * 视频书源（part 投影，供发现页列表使用），判定条件与 [flowVideoSources] 完全一致。
     */
    @Query(
        """select bp.* from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl
        where b.enabled = 1
        and (b.bookSourceType = 4
            or b.bookSourceGroup like '%视频%'
            or b.bookSourceGroup like '%影视%'
            or b.bookSourceGroup like '%电影%'
            or b.bookSourceGroup like '%剧集%'
            or b.bookSourceGroup like '%电视%'
            or b.bookSourceGroup like '%动漫%'
            or b.bookSourceGroup like '%短剧%'
            or b.bookSourceGroup like '%video%'
            or b.bookSourceGroup like '%movie%'
            or b.ruleContent like '%.m3u8%'
            or b.ruleContent like '%.mp4%')
        order by bp.customOrder asc"""
    )
    fun flowVideoSourcesPart(): Flow<List<BookSourcePart>>

    @Query(
        """select bp.* from book_sources b join book_sources_part bp on b.bookSourceUrl = bp.bookSourceUrl
        where b.enabled = 1
        and (b.bookSourceType = 4
            or b.bookSourceGroup like '%视频%'
            or b.bookSourceGroup like '%影视%'
            or b.bookSourceGroup like '%电影%'
            or b.bookSourceGroup like '%剧集%'
            or b.bookSourceGroup like '%电视%'
            or b.bookSourceGroup like '%动漫%'
            or b.bookSourceGroup like '%短剧%'
            or b.bookSourceGroup like '%video%'
            or b.bookSourceGroup like '%movie%'
            or b.ruleContent like '%.m3u8%'
            or b.ruleContent like '%.mp4%')
        and (bp.bookSourceGroup like '%' || :key || '%'
            or bp.bookSourceName like '%' || :key || '%')
        order by bp.customOrder asc"""
    )
    fun flowVideoSourcesPart(key: String): Flow<List<BookSourcePart>>

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