package io.legado.app.ui.soundeffect

import android.app.Application
import android.content.Intent
import io.legado.app.base.BaseViewModel
import io.legado.app.utils.toastOnUi

class SoundEffectRuleEditViewModel(application: Application) : BaseViewModel(application) {

    var rule: SoundEffectRule? = null
    var groupId: Long = -1L
    var groupIds: LongArray = longArrayOf()
    var groupNames: Array<String> = emptyArray()

    fun initData(intent: Intent, finally: () -> Unit) {
        execute {
            @Suppress("DEPRECATION")
            rule = intent.getParcelableExtra(EXTRA_RULE) as? SoundEffectRule
            groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1L)
            groupIds = intent.getLongArrayExtra(EXTRA_GROUP_IDS) ?: longArrayOf()
            groupNames = intent.getStringArrayExtra(EXTRA_GROUP_NAMES) ?: emptyArray()
            if (rule == null) {
                rule = SoundEffectRule(
                    id = System.currentTimeMillis(),
                    isEnabled = true
                )
            }
        }.onFinally {
            finally()
        }
    }

    fun save(rule: SoundEffectRule, success: () -> Unit) {
        execute {
            if (rule.pattern.isEmpty()) {
                throw IllegalArgumentException("匹配内容不能为空")
            }
            if (rule.isRegex) {
                SoundEffectRuleManager.compilePattern(rule.pattern)
            }
        }.onSuccess {
            success()
        }.onError {
            context.toastOnUi(it.localizedMessage ?: "保存失败")
        }
    }

    companion object {
        const val EXTRA_RULE = "rule"
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_IDS = "groupIds"
        const val EXTRA_GROUP_NAMES = "groupNames"
    }
}
