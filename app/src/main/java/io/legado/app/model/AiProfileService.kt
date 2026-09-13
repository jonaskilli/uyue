package io.legado.app.model

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.tts.voice.ScriptCharacterRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * AI 角色档案的自动触发编排。
 *
 * 两条触发链：
 * 1. 朗读跨章时（[BaseReadAloudService.nextChapter]）调用 [onChapterStarted]：
 *    从脚本写入的章节缓存 json（xiaoshuo/<书>/<章>.json）提取本章出现的角色名，
 *    交给 [CharacterProfileManager] 按阈值自动建档/增量更新。
 * 2. 阅读切章时（ReadBook.moveToNextChapter / moveToPrevChapter）调用
 *    [onChapterSwitched]：需在「角色档案设置」里打开「切章自动补全」开关。
 *    直接复用离开那一章**已识别好的姓名**（即双引号上方标注的那份缓存，
 *    无需再走一轮姓名识别），配合该章原文建档/增量更新，
 *    并为尚无立绘的角色自动生成（立绘缓存约定与「与角色对话」一致）。
 *
 * 全程只读缓存文件、全容错、后台异步，不阻塞朗读与阅读。
 */
object AiProfileService {

    private val switchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 切章防抖：同一章 30 秒内只处理一次（翻页越章/连续切章会产生重复调用） */
    @Volatile
    private var lastSwitchKey: String? = null
    @Volatile
    private var lastSwitchAt = 0L

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

    /**
     * 阅读界面切换章节时触发（异步执行，不阻塞 UI）。
     *
     * @param bookName 书名
     * @param prevChapterIndex 「离开的那一章」的下标（切下一章时为原章，切上一章时为原章）
     */
    fun onChapterSwitched(bookName: String, prevChapterIndex: Int) {
        if (bookName.isBlank() || prevChapterIndex < 0) return
        if (!AppConfig.autoProfileOnChapterSwitch) return
        val key = "$bookName#$prevChapterIndex"
        val now = System.currentTimeMillis()
        if (lastSwitchKey == key && now - lastSwitchAt < 30_000L) return
        lastSwitchKey = key
        lastSwitchAt = now
        switchScope.launch {
            runCatching {
                val book = appDb.bookDao.findByName(bookName).firstOrNull()
                    ?: return@launch
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, prevChapterIndex)
                    ?: return@launch
                val title = chapter.title
                if (title.isBlank()) return@launch
                // 姓名直接读标注缓存（双引号上方标注同一数据源），已识别好，去旁白去重即可
                val roles = ScriptCharacterRepository.getChapterRoles(bookName, title)
                    ?: return@launch
                val names = roles.values
                    .filter { !it.isNarration }
                    .map { it.name.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (names.isEmpty()) return@launch
                AppLog.putDebug(
                    "角色档案: 切章补全（第${prevChapterIndex + 1}章「$title」，角色 ${names.size} 个）"
                )
                CharacterProfileManager.onCharactersDetected(
                    bookName, title, prevChapterIndex, names,
                    force = true,
                    onComplete = { valid -> generateMissingAvatars(bookName, valid) }
                )
            }.onFailure {
                // 静默：档案功能绝不影响阅读
            }
        }
    }

    /** 为尚无立绘的角色补生成（已有立绘或正在生成中的自动跳过） */
    private fun generateMissingAvatars(bookName: String, names: List<String>) {
        for (name in names) {
            if (AiImageGenerator.getCachedAvatar(bookName, name) != null) continue
            val profile = CharacterProfileManager.getProfile(bookName, name)
            AiImageGenerator.generateCharacterAvatar(
                characterName = name,
                genderAge = listOf(profile?.gender.orEmpty(), profile?.age.orEmpty())
                    .filter { it.isNotBlank() }.joinToString(""),
                bookName = bookName,
                identity = profile?.identity.orEmpty(),
                personality = profile?.personality.orEmpty(),
                background = profile?.background.orEmpty(),
                regenerate = false,
                templateId = AppConfig.aiPortraitTemplateId,
                onSuccess = { AppLog.put("角色档案: 已自动生成「$name」立绘") },
                onFailure = { e ->
                    AppLog.put("角色档案: 「$name」立绘自动生成失败: ${e.message}")
                }
            )
        }
    }
}
