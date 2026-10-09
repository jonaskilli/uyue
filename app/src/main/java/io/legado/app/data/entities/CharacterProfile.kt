package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index

/**
 * AI 角色档案。
 *
 * 与朗读脚本的 `characterRecords.json`（只有 姓名/别名/性别/年龄）不同，
 * 这里保存 App 侧独立建档产生的**完整人设**：身份、性格、外形特征、说话风格、
 * 人物关系、背景履历，以及随朗读推进不断追加的[事件时间线][timeline]。
 *
 * 主键为 `bookUrl + name`：同一角色在不同书里互不影响。
 * 其中 `bookUrl` 由 [io.legado.app.model.CharacterProfileManager.bookKey] 生成。
 *
 * 数据来源与更新策略见 [io.legado.app.model.CharacterProfileManager]：
 * 1. 首次出现 → 建档（一次 AI 请求）
 * 2. 之后每积累足够证据 → 增量更新（追加 timeline、修订字段）
 */
@Entity(
    tableName = "characterProfiles",
    primaryKeys = ["bookUrl", "name"],
    indices = [Index(value = ["bookUrl"])]
)
data class CharacterProfile(
    /** 书籍标识（见 [io.legado.app.model.CharacterProfileManager.bookKey]） */
    val bookUrl: String,
    /** 角色主名 */
    val name: String,
    /** 别名，多个用 | 分隔 */
    val aliases: String = "",
    /** 性别：男 / 女 */
    val gender: String = "",
    /** 年龄段：童 / 少年 / 青年 / 中年 / 老年 */
    val age: String = "",
    /** 身份，如「青云门长老」 */
    val identity: String = "",
    /** 性格，短句 */
    val personality: String = "",
    /** 外形/习惯特征，JSON 数组字符串 */
    val traits: String = "",
    /** 说话风格 */
    val speakingStyle: String = "",
    /** 人物关系，JSON 数组：[{"target":"李四","relation":"师徒"}] */
    val relations: String = "",
    /** 背景履历 */
    val background: String = "",
    /** 事件时间线，JSON 数组：[{"chapter":"第十章","event":"接任长老"}] */
    val timeline: String = "",
    /** 首次出场章节标题 */
    val firstChapter: String = "",
    /** 最近一次更新时的章节标题 */
    val lastChapter: String = "",
    /** 最近一次更新时的章节下标，用于计算增量间隔 */
    val lastChapterIndex: Int = -1,
    /** 台词累计条数（出场频度，用于排序与增量触发） */
    val mentionCount: Int = 0,
    /** 上次建档/增量时的 [mentionCount]，用于计算新增台词量 */
    val lastUpdateMention: Int = 0,
    /** 信息充分度 0~1，越低越可能是推测 */
    val confidence: Float = 0f,
    /** 最近更新时间戳 */
    val updatedAt: Long = 0
) {
    /** 档案信息是否足够完整（用于判断是否需要尽快增量补全） */
    val isComplete: Boolean
        get() = identity.isNotBlank() && personality.isNotBlank() &&
                background.isNotBlank() && confidence >= 0.6f

    /** 档案摘要（供「与角色对话」的人设 prompt 使用） */
    fun summary(): String = buildString {
        if (gender.isNotBlank() || age.isNotBlank()) {
            append("性别年龄：").append(listOf(gender, age).filter { it.isNotBlank() }.joinToString("")).append('\n')
        }
        if (identity.isNotBlank()) append("身份：$identity\n")
        if (personality.isNotBlank()) append("性格：$personality\n")
        if (speakingStyle.isNotBlank()) append("说话风格：$speakingStyle\n")
        if (traits.isNotBlank() && traits != "[]") append("外形特征：$traits\n")
        if (relations.isNotBlank() && relations != "[]") append("人物关系：$relations\n")
        if (background.isNotBlank()) append("背景履历：$background\n")
    }.trim()
}
