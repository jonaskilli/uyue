package io.legado.app.ui.soundeffect

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 音效替换规则。
 *
 * 字段与朗读服务 `BaseReadAloudService.compileSoundEffectRules()` 所解析的
 * `ttsrv-replaces3.json` / `ttsrv-replaces5.json` 保持一致，
 * 保证本界面保存的文件能被朗读服务原样读取。
 */
@Parcelize
data class SoundEffectRule(
    var id: Long = 0L,
    var name: String = "",
    var isEnabled: Boolean = true,
    var isRegex: Boolean = false,
    var pattern: String = "",
    var replacement: String = "",
    var order: Int = 0,
    var sampleText: String = ""
) : Parcelable {

    /** 复制一份，避免编辑过程中的对象与列表中的对象互相影响 */
    fun copyRule(): SoundEffectRule = copy()

    fun isValid(): Boolean {
        if (pattern.isEmpty()) return false
        if (isRegex) {
            return try {
                SoundEffectRuleManager.compilePattern(pattern)
                true
            } catch (e: Exception) {
                false
            }
        }
        return true
    }
}

/**
 * 音效规则分组。
 * 分组在列表中的先后即规则的执行先后。
 */
data class SoundEffectRuleGroup(
    var id: Long = 0L,
    var name: String = "",
    var order: Int = 0,
    var onExecution: Int = 0
)

/**
 * 一个分组及其下的规则。组内按 [SoundEffectRule.order] 升序执行。
 */
data class SoundEffectGroupWithRules(
    var group: SoundEffectRuleGroup,
    val list: MutableList<SoundEffectRule>
)
