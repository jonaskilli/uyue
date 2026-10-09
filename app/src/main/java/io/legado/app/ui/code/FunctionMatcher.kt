package io.legado.app.ui.code

import io.github.rosemoe.sora.widget.CodeEditor

/**
 * 函数匹配器：根据函数签名（第一行）在代码中查找同名函数块，支持替换
 *
 * 移植自 TTS_Server_Android 的 FunctionMatcher（纯 Kotlin，仅依赖 sora-editor API，可直接使用）。
 *
 * 用于编辑器搜索栏的"函数匹配模式"：当搜索框输入多行代码（完整函数）时，
 * 只匹配第一行（函数签名），找到同名函数后可整体替换。
 */
class FunctionMatcher(private val editor: CodeEditor) {

    /**
     * 函数块：起始行和结束行（0-based，含）
     */
    data class FunctionBlock(val startLine: Int, val endLine: Int)

    /**
     * 从粘贴的代码中提取函数签名行（跳过注释行和空行）
     * @return 函数签名行（trim 后），找不到返回空字符串
     */
    fun extractFunctionSignature(code: String): String {
        for (line in code.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("//")) continue
            if (trimmed.startsWith("/*")) continue
            if (trimmed.startsWith("*")) continue
            if (trimmed.endsWith("*/")) continue
            return trimmed
        }
        return ""
    }

    /**
     * 查找所有函数签名匹配的函数块（只匹配最外层，跳过嵌套在已匹配块内部的同名函数）
     */
    fun findFunctions(signature: String): List<FunctionBlock> {
        val text = editor.text
        val target = signature.trim()
        if (target.isEmpty()) return emptyList()

        val results = mutableListOf<FunctionBlock>()
        var lineIndex = 0
        while (lineIndex < text.lineCount) {
            val line = text.getLine(lineIndex).toString().trim()
            if (line == target) {
                val endLine = findBlockEnd(lineIndex)
                if (endLine > lineIndex) {
                    results.add(FunctionBlock(lineIndex, endLine))
                    lineIndex = endLine + 1
                    continue
                }
            }
            lineIndex++
        }
        return results
    }

    /**
     * 从指定行开始，基于括号匹配找到代码块结束行
     * 支持 {} 函数块 和 [] 数组块，自动检测首个开括号类型进行配对
     */
    private fun findBlockEnd(startLine: Int): Int {
        val text = editor.text
        val stack = ArrayDeque<Char>()
        var openType: Char? = null

        for (lineIndex in startLine until text.lineCount) {
            val line = text.getLine(lineIndex).toString()
            var inString = false
            var stringChar: Char = ' '

            for (c in line) {
                when {
                    inString -> {
                        if (c == stringChar) inString = false
                    }
                    c == '"' || c == '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    c == '/' -> { /* 简易处理，不识别块注释 */ }
                    c == '{' || c == '[' -> {
                        stack.addLast(c)
                        if (openType == null) openType = c
                    }
                    c == '}' || c == ']' -> {
                        val openChar = if (c == '}') '{' else '['
                        while (stack.isNotEmpty() && stack.last() != openChar) {
                            stack.removeLast()
                        }
                        if (stack.isNotEmpty()) {
                            stack.removeLast()
                            if (stack.isEmpty() && openType != null) {
                                val expectedClose = if (openType == '{') '}' else ']'
                                if (c == expectedClose) {
                                    return lineIndex
                                }
                            }
                        }
                    }
                }
            }
        }
        return -1
    }

    /**
     * 替换指定函数块为新代码，自动调整缩进以匹配原代码
     */
    fun replaceFunction(startLine: Int, endLine: Int, newCode: String) {
        val text = editor.text
        val originalFirstLine = text.getLine(startLine).toString()
        val originalIndent = originalFirstLine.takeWhile { it == ' ' || it == '\t' }

        val newFirstLine = newCode.substringBefore("\n")
        val newIndent = newFirstLine.takeWhile { it == ' ' || it == '\t' }

        val adjustedCode = newCode.lineSequence().mapIndexed { index, line ->
            if (line.isBlank()) {
                line
            } else {
                val stripped = if (index == 0) {
                    line.removePrefix(newIndent)
                } else {
                    if (line.startsWith(newIndent)) line.removePrefix(newIndent) else line.trimStart()
                }
                originalIndent + stripped
            }
        }.joinToString("\n")

        val start = text.getCharIndex(startLine, 0)
        val end = text.getCharIndex(endLine, text.getColumnCount(endLine))
        text.replace(start, end, adjustedCode)
    }
}
