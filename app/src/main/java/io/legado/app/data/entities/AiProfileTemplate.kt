package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 角色档案模板配置。
 *
 * 字段与「AI处理章节」模板同构：输入框直填 API（多组轮换），输入即保存。
 * apiConfig 格式（3 段一组，可无限追加）：
 * 接口地址@@模型名字@@密钥@@接口地址@@模型名字@@密钥@@...
 */
@Entity(tableName = "aiProfileTemplate")
data class AiProfileTemplate(
    @PrimaryKey
    val id: Long = System.currentTimeMillis(),
    var name: String = "",
    var apiConfig: String = "",
    /** 建档/增量分析用的附加提示词（附加在内置分析指令之后，可为空） */
    var prompt: String = "",
    /** 生图提示词（立绘），非空时直接覆盖内置立绘提示词，可为空 */
    @ColumnInfo(defaultValue = "")
    var imagePrompt: String = "",
    /** 增量更新频率：每 N 章至少更新一次（档案不完整时每章都补全） */
    @ColumnInfo(defaultValue = "5")
    var chapterGap: Int = 5,
    /** 请求失败或返回无法解析时的重试次数 */
    @ColumnInfo(defaultValue = "1")
    var retryCount: Int = 1,
    /** 单次请求的整体超时（秒） */
    @ColumnInfo(defaultValue = "240")
    var timeoutSeconds: Int = 240,
    /** 是否自动建档（新角色首次出现时自动建档） */
    @ColumnInfo(defaultValue = "1")
    var autoEnable: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    var isDefault: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var lastUpdateTime: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    var sortOrder: Int = 0
)
