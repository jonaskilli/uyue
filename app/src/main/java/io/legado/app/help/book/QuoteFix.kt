package io.legado.app.help.book

import io.legado.app.constant.AppLog
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * 双引号修复
 *
 * 与「重新分段」同级的按书开关（菜单中位于其上方），开启后在重新分段之前执行，
 * 用于修复正文中错乱/缺失的双引号：」/「 归一、去无用引号、
 * 奇数个引号配对、补左/右引号等。
 *
 * 规则共 [RULES_SIZE] 条，来自用户提供的替换净化规则组「双引号修复」，
 * **必须严格按顺序逐条执行**（前面的规则会改变后面规则的匹配前提）。
 *
 * 语义与「阅读→替换净化」引擎完全一致：java.util.regex.Pattern 编译，
 * Matcher.replaceAll 应用（替换串中 $1 为分组引用，$ 需转义为 \$）。
 */
object QuoteFix {

    private const val RULES_SIZE = 15

    private class FixRule(
        val name: String,
        val pattern: Pattern,
        val replacement: String
    )

    private val rules: List<FixRule> by lazy {
        buildList {
            // 1. 」 归一为反引号
            add(plain("」", "”"))
            // 2. 「 归一为起引号
            add(plain("「", "“"))
            // 3. 去无用双引号：汉字间的短引用（1-15 字）去引号
            add(
                FixRule(
                    "去无用双引号",
                    Pattern.compile("([\\u4e00-\\u9fa5])“([\\u4e00-\\u9fa5]{1,15})”([\\u4e00-\\u9fa5])"),
                    "\$1\$2\$3"
                )
            )
            // 5. 3个双引号修改1（4引号行多一个起引号）
            add(
                FixRule(
                    "3个双引号修改1",
                    Pattern.compile("(\\n|^)([^“”\\n]*“[^“”\\n]*”[^“”\\n]*“[^“”\\n]*”[^“”\\n]*)“([^“”\\n]*)(\\n|\$)"),
                    "\$1\$2‘‘\$3\$4"
                )
            )
            // 6. 3个双引号修改2（2引号行多一个起引号）
            add(
                FixRule(
                    "3个双引号修改2",
                    Pattern.compile("(\\n|^)([^“”\\n]*“[^“”\\n]*”[^“”\\n]*)“([^“”\\n]*)(\\n|\$)"),
                    "\$1\$2‘‘\$3\$4"
                )
            )
            // 7. 3个双引号修改3（4引号行多一个反引号）
            add(
                FixRule(
                    "3个双引号修改3",
                    Pattern.compile("(\\n|^)([^“”\\n]*)”([^“”\\n]*“[^“”\\n]*”[^“”\\n]*”[^“”\\n]*”[^“”\\n]*)(\\n|\$)"),
                    "\$1\$2’’\$3\$4"
                )
            )
            // 8. 3个双引号修改4（2引号行多一个反引号）
            add(
                FixRule(
                    "3个双引号修改4",
                    Pattern.compile("(\\n|^)([^“”\\n]*)”([^“”\\n]*“[^“”\\n]*”[^“”\\n]*)(\\n|\$)"),
                    "\$1\$2’’\$3\$4"
                )
            )
            // 9. 双引号修正（行首起）
            add(
                FixRule(
                    "双引号修正",
                    Pattern.compile("(\\n|^)([^\\n“”]*)[“”]([^\\n“”]*)[“”]([^\\n“”]*)"),
                    "\$1\$2“\$3”\$4"
                )
            )
            // 10. 双引号修正2（行尾止）
            add(
                FixRule(
                    "双引号修正2",
                    Pattern.compile("([^\\n“”]*)[“”]([^\\n“”]*)[“”]([^\\n“”]*)(\\n|\$)"),
                    "\$1“\$2”\$3\$4"
                )
            )
            // 11. 双引号恢复1：临时标记 ‘‘ 还原为起引号
            add(plain("‘‘", "“"))
            // 12. 双引号恢复2：临时标记 ’’ 还原为反引号
            add(plain("’’", "”"))
            // 13. 补右双引号
            add(
                FixRule(
                    "补右双引号",
                    Pattern.compile("(“[^“”\\n]*)(\\n[^“”]*“|\\n[^“”]*\$|\$)"),
                    "\$1”\$2"
                )
            )
            // 14. 补左双引号
            add(
                FixRule(
                    "补左双引号",
                    Pattern.compile("(^|”[^“”]*\\n|^[^“”]*\\n)([^“”\\n]*”)"),
                    "\$1“\$2"
                )
            )
            // 15. 双引号修复11：去掉引号内多余的引号
            add(
                FixRule(
                    "双引号修复11",
                    Pattern.compile("(“[^\\n“”]*)[“”]([^\\n“”]*”)"),
                    "\$1\$2"
                )
            )
            // 16. 修复12：跨行补左引号
            add(
                FixRule(
                    "修复12",
                    Pattern.compile("(^|”)([^\\n“”]*\\n)([^\\n“”]*”)"),
                    "\$1\$2“\$3"
                )
            )
        }.also {
            check(it.size == RULES_SIZE) { "双引号修复规则数量应为 $RULES_SIZE，实际 ${it.size}" }
        }
    }

    /** 非正则规则：字面匹配、字面替换 */
    private fun plain(pattern: String, replacement: String) = FixRule(
        pattern,
        Pattern.compile(Pattern.quote(pattern)),
        Matcher.quoteReplacement(replacement)
    )

    /**
     * 执行双引号修复。按 [rules] 顺序逐条替换，与替换净化引擎语义一致。
     * 单条规则出错只记录日志并跳过，不影响其他规则和后续重新分段。
     */
    fun fix(content: String): String {
        var result = content
        for (rule in rules) {
            if (result.isEmpty()) break
            try {
                val replaced = rule.pattern.matcher(result).replaceAll(rule.replacement)
                if (replaced != result) {
                    result = replaced
                }
            } catch (e: Exception) {
                AppLog.put("双引号修复: 规则 ${rule.name}执行出错\n$result", e)
            }
        }
        return result
    }

}
