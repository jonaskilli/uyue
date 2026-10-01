package io.legado.app.ui.hunyuan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import io.legado.app.help.hunyuan.App
import io.legado.app.help.hunyuan.ProxyViewModel
import io.legado.app.service.ReadAloudFloatService

class HunyuanTaijiActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ProxyViewModel(application)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                App(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 本页不继承 BaseActivity，须自行通知朗读悬浮球服务：
        // 混元太极在前台期间隐藏悬浮球，否则 66dp 悬浮球浮在本页上会拦截
        // 其覆盖范围内的所有触摸（服务商选择框/按钮点不动的元凶）
        ReadAloudFloatService.onActivityResumed(this)
    }

    override fun onPause() {
        super.onPause()
        ReadAloudFloatService.onActivityPaused(this)
    }
}
