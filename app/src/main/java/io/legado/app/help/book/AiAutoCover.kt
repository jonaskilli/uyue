package io.legado.app.help.book

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.model.AiImageGenerator
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import splitties.init.appCtx

/**
 * 打开一本书**开始阅读**时，如果它没有真实封面，就自动用 AI 生成一张。
 *
 * 和书籍信息界面那个手动按钮的区别：
 * - 不弹窗、不 Toast、不阻塞阅读，全程后台跑
 * - 每本书每次进程只试一次：成功自然不会再试，失败也不会反复调接口
 *
 * 之所以挂在阅读而不是信息界面：信息界面要专门点进去才触发，
 * 而本地 txt 这类书用户往往是直接点开就读，根本不会去翻信息页。
 */
object AiAutoCover {

    /** 换封面里「默认封面」用的哨兵值（见 ChangeCoverViewModel），只是底图 + 书名文字，不是真实图片 */
    private const val DEFAULT_COVER_FLAG = "use_default_cover"

    /** 自己的作用域，不挂在 Activity 上：退出阅读界面也不会把生成任务取消掉 */
    private val scope = CoroutineScope(SupervisorJob() + IO)

    /** 已经试过的书，避免阅读界面反复重建时重复调接口 */
    private val tried = Collections.synchronizedSet(mutableSetOf<String>())

    /** 是否真的有封面。下面三种都算**没有** */
    fun hasRealCover(cover: String?): Boolean {
        if (cover.isNullOrBlank()) return false
        if (cover.equals(DEFAULT_COVER_FLAG, ignoreCase = true)) return false
        // 网络地址：有地址就认为有封面，不去实际请求
        if (cover.startsWith("http", ignoreCase = true)) return true
        return File(cover).exists()
    }

    /** 供调试/排查：把某本书的"已试过"记录清掉，下次打开会重新生成 */
    fun reset(bookUrl: String) {
        tried.remove(bookUrl)
    }

    fun maybeGenerate(book: Book) {
        val cover = book.getDisplayCover()
        if (hasRealCover(cover)) {
            AppLog.put("自动封面: 《${book.name}》已有封面($cover)，不生成")
            return
        }
        if (!tried.add(book.bookUrl)) {
            AppLog.put("自动封面: 《${book.name}》本次已试过，跳过")
            return
        }
        AppLog.put("自动封面: 《${book.name}》没有真实封面($cover)，开始生成")
        scope.launch { generate(book) }
    }

    private suspend fun generate(book: Book) {
        val template = AiImageGenerator.builtinTemplate()
        val prompt = AppConfig.aiCoverPrompt
            .replace("{name}", book.name)
            .replace("{intro}", book.getDisplayIntro().orEmpty().take(600))
        AppLog.put("自动封面: 请求《${book.name}》接口=${template.modelUrl}")
        val result = runCatching { AiImageGenerator.generateImageDirect(template, prompt) }
            .getOrElse { Result.failure(it) }
        result.onSuccess { file ->
            val path = saveAsBookCover(book, file)
            if (path == null) {
                AppLog.put("自动封面: 《${book.name}》生成成功但保存失败")
                return
            }
            AppLog.put("自动封面: 成功《${book.name}》-> $path")
            // 写回数据库，否则下次进来封面地址还是空的，会再生成一次
            runCatching { appDb.bookDao.update(book) }
            withContext(Main.immediate) {
                postEvent(EventBus.UP_BOOKSHELF, book.bookUrl)
            }
        }.onFailure {
            AppLog.put("自动封面: 失败《${book.name}》${it.localizedMessage}")
        }
    }

    /**
     * 把生成出来的图片存进这本书的封面目录。
     * 与原封面同一个目录（externalFiles/covers），命名带书 URL 的 md5，
     * 这样按前缀就能列出这本书所有生成的封面。
     */
    fun saveAsBookCover(book: Book, file: File): String? {
        return try {
            val dir = File(appCtx.externalFiles, "covers").apply { mkdirs() }
            val md5 = MD5Utils.md5Encode16(book.bookUrl)
            val target = File(dir, "ai_cover_${md5}_${System.currentTimeMillis()}.png")
            file.copyTo(target, overwrite = true)
            // 生成图只是临时缓存，用完后删掉，避免占两遍空间
            runCatching { file.delete() }
            book.customCoverUrl = target.absolutePath
            target.absolutePath
        } catch (e: Exception) {
            AppLog.put("保存AI封面失败: ${e.localizedMessage}")
            null
        }
    }
}
