package io.legado.app.data.entities

import android.content.Context
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.R
import io.legado.app.constant.BookType
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "searchBooks",
    indices = [(Index(value = ["bookUrl"], unique = true)),
        (Index(value = ["origin"], unique = false))],
    foreignKeys = [(ForeignKey(
        entity = BookSource::class,
        parentColumns = ["bookSourceUrl"],
        childColumns = ["origin"],
        onDelete = ForeignKey.CASCADE
    ))]
)
data class SearchBook(
    @PrimaryKey
    override var bookUrl: String = "",
    /** 书源 */
    var origin: String = "",
    var originName: String = "",
    /** BookType */
    var type: Int = BookType.text,
    override var name: String = "",
    override var author: String = "",
    override var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    override var wordCount: String? = null,
    var latestChapterTitle: String? = null,
    /** 目录页Url (toc=table of Contents) */
    var tocUrl: String = "",
    var time: Long = System.currentTimeMillis(),
    override var variable: String? = null,
    var originOrder: Int = 0,
    var chapterWordCountText: String? = null,
    @ColumnInfo(defaultValue = "-1")
    var chapterWordCount: Int = -1,
    @ColumnInfo(defaultValue = "-1")
    var respondTime: Int = -1
) : Parcelable, BaseBook, Comparable<SearchBook> {

    @Ignore
    @IgnoredOnParcel
    override var infoHtml: String? = null

    @Ignore
    @IgnoredOnParcel
    override var tocHtml: String? = null

    override fun equals(other: Any?) = other is SearchBook && other.bookUrl == bookUrl

    override fun hashCode() = bookUrl.hashCode()

    override fun compareTo(other: SearchBook): Int {
        return other.originOrder - this.originOrder
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: HashMap()
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val origins: LinkedHashSet<String> by lazy { linkedSetOf(origin) }

    /**
     * 同名结果被合并时，各源自己那条结果的 bookUrl。
     * 见 [addOrigin]；没有它，切到某个源播放时只能拿到「最先返回的源」的链接。
     */
    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    val originUrls: LinkedHashMap<String, String> by lazy {
        linkedMapOf(origin to bookUrl)
    }

    fun addOrigin(origin: String, bookUrl: String) {
        origins.add(origin)
        originUrls[origin] = bookUrl
    }

    /**
     * 取 [origin] 这个源返回的结果链接；未指定源或该源链接缺失时退回自身。
     * 合并记录的 bookUrl 只是最先返回那个源的，切源播放必须用它。
     */
    fun getBookUrlByOrigin(origin: String?): String {
        if (origin.isNullOrBlank()) return bookUrl
        return originUrls[origin]?.takeIf { it.isNotBlank() } ?: bookUrl
    }

    fun getDisplayLastChapterTitle(): String {
        latestChapterTitle?.let {
            if (it.isNotEmpty()) {
                return it
            }
        }
        return "无最新章节"
    }

    fun trimIntro(context: Context): String {
        val trimIntro = intro?.trim()
        return if (trimIntro.isNullOrEmpty()) {
            context.getString(R.string.intro_show_null)
        } else {
            context.getString(R.string.intro_show, trimIntro)
        }
    }

    fun releaseHtmlData() {
        infoHtml = null
        tocHtml = null
    }

    fun primaryStr(): String {
        return origin + bookUrl
    }

    fun sameBookTypeLocal(bookType: Int): Boolean {
        return type and BookType.allBookTypeLocal == bookType and BookType.allBookTypeLocal
    }

    fun toBook() = Book(
        name = name,
        author = author,
        kind = kind,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        type = type,
        wordCount = wordCount,
        latestChapterTitle = latestChapterTitle,
        coverUrl = coverUrl,
        intro = intro,
        tocUrl = tocUrl,
        originOrder = originOrder,
        variable = variable
    ).apply {
        this.infoHtml = this@SearchBook.infoHtml
        this.tocHtml = this@SearchBook.tocHtml
    }
}