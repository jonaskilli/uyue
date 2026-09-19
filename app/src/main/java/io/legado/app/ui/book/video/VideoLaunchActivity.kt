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
            onDismiss = { finish() }
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
    onDismiss: () -> Unit
) {
    if (state.error != null) {
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.video_get_url_fail),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.close))
                }
            }
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
