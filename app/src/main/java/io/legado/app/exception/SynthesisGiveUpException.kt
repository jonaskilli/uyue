package io.legado.app.exception

/**
 * 合成彻底失败（慢重试预算耗尽）。
 *
 * 11-17：用户关掉「失败后跳过继续朗读」时，合成失败会先原地慢重试（每 10 秒一轮、
 * 总预算 1 分钟）给它恢复机会；预算耗尽仍失败 → 抛本异常，由播放循环接住并
 * **暂停朗读**（与那个开关键文案「关闭后将暂停朗读」一致）。
 *
 * 为什么不写静音占位：占位会让这段永久哑掉且听不出异常；暂停则停在原地、
 * 用户手动继续后还会重新合成，内容不丢。
 */
class SynthesisGiveUpException(
    val paragraphIndex: Int,
    val previewText: String,
) : NoStackTraceException("TTS合成失败，已重试超预算，暂停朗读: index=$paragraphIndex")
