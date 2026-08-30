package io.legado.app.help

import io.legado.app.ui.book.read.config.CharacterVoiceHelper

/**
 * 朗读数据目录。
 *
 * 规则（与「角色管理」「fayinren.json」的判定完全一致，复用同一个函数）：
 *  - 朗读引擎 = 内置TTS插件 → 使用 /storage/emulated/0/Download/chajian/mingwuyanZ/
 *  - 其他引擎（系统TTS / 普通HTTP源）→ 使用 /storage/emulated/0/Download/chajian/mingwuyan/
 *
 * 之所以要两个目录：内置TTS插件走的是 APP 内部音色池，发音人标签与外置脚本体系不同，
 * 混用同一个目录会让两边的角色记录、发音人列表互相覆盖。
 *
 * 用法：
 *  - JS 侧：readExternalFile / writeExternalFile / deleteExternalFile 内部自动调用 [rewrite]
 *  - Kotlin 侧：直接用 [baseDir] 拼文件名，不要再把路径写死
 */
object EngineDataDir {

    /** 原目录（不带尾斜杠，便于精确匹配目录段） */
    private const val ORIGINAL = "/storage/emulated/0/Download/chajian/mingwuyan"
    /** 内置TTS插件专用目录（不带尾斜杠） */
    private const val BUILTIN = "/storage/emulated/0/Download/chajian/mingwuyanZ"

    /** 供 Kotlin 侧拼接文件名使用，带尾斜杠。 */
    fun baseDir(): String =
        if (CharacterVoiceHelper.isUsingBuiltinTtsPlugin()) "$BUILTIN/" else "$ORIGINAL/"

    /**
     * 把 mingwuyan 下的路径重写为当前引擎对应的目录。
     * 未命中 mingwuyan 或当前不是内置TTS插件时，原样返回。
     */
    fun rewrite(path: String): String {
        if (!CharacterVoiceHelper.isUsingBuiltinTtsPlugin()) return path
        if (!path.startsWith(ORIGINAL)) return path
        // 已经在 Z 目录里，避免重复替换（幂等）
        if (path.startsWith(BUILTIN)) return path
        val rest = path.substring(ORIGINAL.length) // 形如 "/data.json" 或 ""
        return BUILTIN + rest
    }
}
