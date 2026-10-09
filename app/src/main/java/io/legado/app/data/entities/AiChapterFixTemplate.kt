package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 章节处理模板配置
 * 每个模板包含一套完整的处理参数（接口、提示词、拆分/并发/重试/超时/上下文）。
 * 默认模板（isDefault=true）对未绑定其它模板的书籍生效，全局唯一且不可删除。
 *
 * apiConfig 支持多组接口轮换，格式（3 段一组，可无限追加）：
 * 接口地址@@模型名字@@密钥@@接口地址@@模型名字@@密钥@@...
 * 每次请求按顺序轮换使用一组；失败重试时自动切换到下一组。
 */
@Entity(tableName = "aiChapterFixTemplate")
data class AiChapterFixTemplate(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var apiConfig: String = "",
    var prompt: String = "",
    /** 批量处理章节数（自动处理与批量处理共用） */
    @ColumnInfo(defaultValue = "1")
    var chapterCount: Int = 1,
    /** 超长章节拆分阈值（字数），0 表示不拆分 */
    @ColumnInfo(defaultValue = "0")
    var maxLength: Int = 0,
    /** 同一章拆成多块时的并发请求数，1 表示顺序处理 */
    @ColumnInfo(defaultValue = "1")
    var concurrency: Int = 1,
    /** 请求失败或返回无法解析时的重试次数 */
    @ColumnInfo(defaultValue = "0")
    var retryCount: Int = 0,
    /** 单次请求的整体超时（秒） */
    @ColumnInfo(defaultValue = "180")
    var timeoutSeconds: Int = 180,
    /** 上下文参考字数（上一章结尾+下一章开头），0 表示不附带 */
    @ColumnInfo(defaultValue = "0")
    var contextLength: Int = 0,
    @ColumnInfo(defaultValue = "0")
    var isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    var sortOrder: Int = 0
)
