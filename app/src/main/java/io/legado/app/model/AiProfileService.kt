package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.tts.voice.ScriptCharacterRepository

/**
 * AI 角色档案的自动触发编排。
 *
 * 朗读跨章时（[BaseReadAloudService.nextChapter]）调用 [onChapterStarted]：
 * 从脚本写入的章节缓存 json（xiaoshuo/<书>/<章>.json）提取本章出现的角色名，
 * 交给 [CharacterProfileManager] 按阈值自动建档/增量更新。
 *
 * 全程只读缓存文件、全容错，不阻塞朗读。
 */
object AiProfileService {

    /** 朗读进入新章节时触发一次角色发现 */
    fun onChapterStarted(bookName: String, chapterIndex: Int, chapterTitle: String) {
        if (bookName.isBlank() || chapterTitle.isBlank()) return
        try {
            val roles = ScriptCharacterRepository.getChapterRoles(bookName, chapterTitle) ?: return
            val names = roles.values
                .filter { !it.isNarration }
                .map { it.name.trim() }
                .filter { it.isNotBlank() }
                .distinct()
            if (names.isEmpty()) return
            AppLog.putDebug("角色档案: 第${chapterIndex + 1}章识别到角色 ${names.size} 个")
            CharacterProfileManager.onCharactersDetected(bookName, chapterTitle, chapterIndex, names)
        } catch (e: Exception) {
            // 静默：档案功能绝不影响朗读
        }
    }
}
