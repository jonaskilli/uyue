package io.legado.app.ui.book.video

import android.app.Application
import android.content.Intent
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.book.getBookSource
import io.legado.app.help.video.VideoSourcePlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VideoLaunchUiState(
    val loading: Boolean = true,
    val bookName: String = "",
    /** 书源播放器已拉起，Activity 可自行结束 */
    val launched: Boolean = false,
    val error: String? = null
)

/**
 * 视频启动器。
 *
 * 不做任何播放，只负责把书籍与目录准备好、执行正文规则，
 * 由书源自己拉起它的播放器（通常是内置浏览器里的网页播放器）。
 */
class VideoLaunchViewModel(application: Application) : BaseViewModel(application) {

    private val _uiState = MutableStateFlow(VideoLaunchUiState())
    val uiState = _uiState.asStateFlow()

    fun launch(intent: Intent) {
        execute {
            val bookUrl = intent.getStringExtra("bookUrl")
                ?: throw IllegalStateException("缺少 bookUrl")
            val book = appDb.bookDao.getBook(bookUrl)
                ?: throw IllegalStateException("书籍不存在")
            val source = book.getBookSource()
                ?: throw IllegalStateException("书源不存在")
            _uiState.update { it.copy(bookName = book.name) }
            VideoSourcePlayer.openBook(book, source)
        }.onSuccess {
            _uiState.update { it.copy(loading = false, launched = true) }
        }.onError {
            AppLog.put("启动视频播放失败\n${it.localizedMessage}", it, true)
            _uiState.update { s -> s.copy(loading = false, error = it.localizedMessage) }
        }
    }
}
