package io.legado.app.ui.book.video

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.Theme
import io.legado.app.ui.timetech.player.ErrorNotice

/**
 * 视频启动页。
 *
 * 本身不播放任何内容：准备好书籍与目录后执行正文规则，
 * 由书源自己拉起它的播放器（内置浏览器里的网页播放器）。
 * 拉起后本页立即结束，不残留中间页。
 *
 * 窗口背景透明，只在等待期间显示一个加载提示。
 */
class VideoLaunchActivity : BaseComposeActivity(
    fullScreen = false,
    toolBarTheme = Theme.Dark,
    imageBg = false
) {

    private val viewModel by viewModels<VideoLaunchViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
    }

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(viewModel) {
            viewModel.launch(intent)
        }

        // 书源播放器已拉起，或用户关闭错误提示后，结束本页
        LaunchedEffect(state.launched) {
            if (state.launched) finish()
        }

        VideoLaunchContent(
            state = state,
            onRetry = { viewModel.launch(intent) },
            onExit = { finish() }
        )
    }

    companion object {

        fun createIntent(
            context: Context,
            bookUrl: String,
            inBookshelf: Boolean = true
        ): Intent = Intent(context, VideoLaunchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("bookUrl", bookUrl)
            putExtra("inBookshelf", inBookshelf)
        }
    }
}

@Composable
private fun VideoLaunchContent(
    state: VideoLaunchUiState,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    // 失败不再用模态弹窗（它一出现就吃掉所有触摸，点「关闭」还会直接 finish()，
    // 于是"打开失败 = 只剩退出"）。改成底部提示条：不拦其它区域、不自动退出，
    // 可以原地重试，也可以主动退出。
    if (state.error != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            ErrorNotice(
                message = state.error,
                // 本页是单视频入口，没有可切换的列表
                canSwitch = false,
                onRetry = onRetry,
                onExit = onExit,
            )
        }
        return
    }

    if (state.loading) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
        }
    }
    // 其余情况（已拉起播放器）保持透明，等待 Activity 结束
}
