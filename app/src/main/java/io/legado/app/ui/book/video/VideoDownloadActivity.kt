package io.legado.app.ui.book.video

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

/**
 * 下载管理页（独立入口）。
 *
 * 从视频发现页右上角进入：不用先打开某一部视频，就能看到所有下载过/正在下载的视频，
 * 点进去继续、暂停、删除，也可以在这里批量清掉不再需要的缓存。
 *
 * 界面与播放页里的下载弹窗是同一份实现（[DownloadDialog] 的 standalone 模式），
 * 只是外壳换成整页。
 */
class VideoDownloadActivity : BaseComposeActivity(imageBg = false) {

    @Composable
    override fun Content() {
        DownloadDialog(
            episodes = emptyList(),
            bookName = "",
            showManageFirst = false,
            standalone = true,
            onDismiss = { finish() },
        )
    }
}
