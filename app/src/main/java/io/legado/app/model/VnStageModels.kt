package io.legado.app.model

/**
 * 视觉小说舞台（VN Stage）公共数据模型。
 *
 * 朗读服务在每段开始朗读时通过 EventBus.VN_PARAGRAPH 广播 [VnParagraph]，
 * 舞台界面据此反查说话人、驱动立绘切换与打字机台词。
 */

/** 一段朗读的归属信息（事件 EventBus.VN_PARAGRAPH 的载荷） */
data class VnParagraph(
    /** 段落全文（原始文本，未净化） */
    val text: String,
    /** 段落在 contentList 中的下标 */
    val index: Int
)

/**
 * 当前字幕片段及其在段落内的字符区间（事件 EventBus.VN_SUBTITLE_POS 的载荷）。
 * 区间坐标与段落净化文本一致（≈原始文本）；消费侧幂等使用 [end]，
 * 服务周期性重发同一片段时不会造成进度膨胀。
 */
data class VnSubtitlePos(
    /** 片段文本 */
    val text: String,
    /** 片段在段落内的起始字符（含） */
    val start: Int,
    /** 片段在段落内的结束字符（不含） */
    val end: Int
)
