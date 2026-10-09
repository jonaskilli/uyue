package io.legado.app.base

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.core.view.WindowCompat
import io.legado.app.constant.Theme
import io.legado.app.utils.toggleSystemBar

/**
 * Compose 页面基类（精简版）。
 *
 * 构造参数与使用方式与上游一致，供时光播放器链路（SourceVideoPlayerActivity /
 * VideoLaunchActivity）使用：全屏黑底播放页不依赖阅读器的主题体系，
 * 直接使用 Material3 默认配色即可。
 *
 * [setupSystemBar] 为 open：FullscreenVideoPlayer 退出沉浸式时会调它还原系统栏。
 */
abstract class BaseComposeActivity(
    val fullScreen: Boolean = true,
    private val toolBarTheme: Theme = Theme.Auto,
    private val transparent: Boolean = false,
    private val imageBg: Boolean = true
) : AppCompatActivity() {

    @Composable
    protected abstract fun Content()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSystemBar()
        setContent {
            MaterialTheme {
                Content()
            }
        }
    }

    open fun setupSystemBar() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (fullScreen) {
            toggleSystemBar(false)
        }
    }
}
