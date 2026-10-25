package io.legado.app.ui.book.video

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.legado.app.base.BaseComposeActivity
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.accentColor

/**
 * 下载管理页（独立入口）。
 *
 * 从视频发现页右上角进入：不用先打开某一部视频，就能看到所有下载过/正在下载的视频，
 * 点进去继续、暂停、删除，也可以在这里批量清掉不再需要的缓存。
 *
 * 界面与播放页里的下载弹窗是同一份实现（[DownloadDialog] 的 standalone 模式），
 * 只是外壳换成整页，并且主题跟随应用（应用主题为"自动"时即跟随系统深浅色与主题色），
 * 不再固定黑底白字。
 */
class VideoDownloadActivity : BaseComposeActivity(imageBg = false) {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val dark = ThemeConfig.isDarkTheme()
        val accent = Color(context.accentColor)
        val colorScheme = if (dark) {
            darkColorScheme(primary = accent)
        } else {
            lightColorScheme(primary = accent)
        }
        MaterialTheme(colorScheme = colorScheme) {
            DownloadDialog(
                episodes = emptyList(),
                bookName = "",
                showManageFirst = false,
                standalone = true,
                onDismiss = { finish() },
            )
        }
    }
}
