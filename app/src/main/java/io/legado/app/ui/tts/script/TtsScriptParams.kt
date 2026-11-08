package io.legado.app.ui.tts.script

import java.util.Locale

/**
 * 朗读脚本参数识别与改写。
 *
 * 脚本里的参数都是 `var XXX = 数值;` 形式的顶层变量，且变量在代码中的位置不固定
 * （用户可能在编辑器里增删行、调顺序），因此统一按【变量名】正则定位提取/替换，
 * 与位置无关，只认名字不认位置。
 *
 * 无参数的脚本（如音效替换规则）[parse] 返回 null，界面据此隐藏摘要与菜单项。
 */
object TtsScriptParams {

    /** 参数定义：[varName] 脚本变量名，[label] 界面标签，[isMillis] 毫秒参数（界面按秒编辑） */
    data class Param(
        val varName: String,
        val label: String,
        val min: Double,
        val max: Double,
        val isMillis: Boolean = false
    ) {
        /** 输入校验提示，如 “取值范围 1 ~ 500” / “取值范围 0.1 ~ 300 秒” */
        fun rangeHint(): String {
            val unit = if (isMillis) " 秒" else ""
            return "取值范围 ${fmt(min)} ~ ${fmt(max)}$unit"
        }

        private fun fmt(v: Double): String =
            if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    }

    /** 4 个可配置参数（顺序即对话框与摘要顺序） */
    val PARAMS: List<Param> = listOf(
        Param("MAX_CHARACTER", "最多保存角色数量", min = 1.0, max = 800.0),
        Param("EXTRACT_LENGTH", "每次分析字数", min = 50.0, max = 5000.0),
        Param("MAX_RETRY", "重试次数", min = 0.0, max = 10.0),
        Param("NAME_ANALYZE_TIMEOUT", "最大等待时间", min = 0.1, max = 300.0, isMillis = true)
    )

    /** 超时统一改写：改 NAME_ANALYZE_TIMEOUT 时连带改写 RACE_DELAY */
    private const val TIMEOUT_ALIAS_VAR = "RACE_DELAY"

    /** 提取到的参数值（毫秒参数存毫秒） */
    class Values internal constructor(val map: Map<String, Int>) {
        fun intOf(varName: String): Int? = map[varName]
    }

    /** 按变量名匹配 `var/let/const NAME = 数值;`，位置不固定也能定位 */
    private fun regexOf(varName: String): Regex =
        Regex("""((?:var|let|const)\s+""" + Regex.escape(varName) + """\s*=\s*)(\d+)(\s*;)""")

    /**
     * 从脚本代码提取参数值。任一必需参数缺失返回 null（视为“无参数脚本”）。
     */
    fun parse(code: String): Values? {
        if (code.isBlank()) return null
        val map = LinkedHashMap<String, Int>(PARAMS.size)
        for (p in PARAMS) {
            val m = regexOf(p.varName).find(code) ?: return null
            val v = m.groupValues[2].toIntOrNull() ?: return null
            map[p.varName] = v
        }
        return Values(map)
    }

    /** 毫秒转“秒”显示文本（整除不带小数），如 25000 -> “25” */
    fun toSecondsText(ms: Int): String {
        val sec = ms / 1000.0
        return if (sec % 1.0 == 0.0) {
            sec.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", sec)
        }
    }

    /** 条目摘要，无参数返回 null：角色上限 800 · 分析字数 1000 · 重试 3 次 · 超时 25 秒 */
    fun summary(code: String): String? {
        val v = parse(code) ?: return null
        return buildString {
            append("角色上限 ").append(v.intOf("MAX_CHARACTER"))
            append(" · 分析字数 ").append(v.intOf("EXTRACT_LENGTH"))
            append(" · 重试 ").append(v.intOf("MAX_RETRY")).append("次")
            append(" · 超时 ").append(toSecondsText(v.intOf("NAME_ANALYZE_TIMEOUT") ?: 0)).append("秒")
        }
    }

    /**
     * 按变量名原位替换数值，代码其余部分一律不动。
     * @param values 变量名 -> 新值（毫秒参数传毫秒）；变量不存在则跳过
     * @return 改写后的代码
     */
    fun apply(code: String, values: Map<String, Int>): String {
        var result = code
        values.forEach { (varName, value) ->
            result = regexOf(varName).replace(result) { m ->
                m.groupValues[1] + value + m.groupValues[3]
            }
        }
        return result
    }

    /**
     * 对话框保存入口：4 个输入值（超时为秒）-> 改写后的代码。
     * 超时会同时改写 NAME_ANALYZE_TIMEOUT 与 RACE_DELAY（存在才改）。
     */
    fun applyFromInputs(
        code: String,
        maxCharacter: Int,
        extractLength: Int,
        maxRetry: Int,
        timeoutSeconds: Double
    ): String {
        val timeoutMs = Math.round(timeoutSeconds * 1000.0).toInt()
        val values = linkedMapOf(
            "MAX_CHARACTER" to maxCharacter,
            "EXTRACT_LENGTH" to extractLength,
            "MAX_RETRY" to maxRetry,
            "NAME_ANALYZE_TIMEOUT" to timeoutMs,
            TIMEOUT_ALIAS_VAR to timeoutMs
        )
        return apply(code, values)
    }
}
