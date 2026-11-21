package com.qiqi.floatshow

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.qiqi.floatshow.ui.DesignLabScreen
import com.qiqi.floatshow.ui.SettingsScreen
import com.qiqi.floatshow.ui.theme.FloatshowTheme

/**
 * 唯一的活动. 只做两件事: 检查权限 / 调整悬浮层参数`,真正的显示都在 [com.qiqi.floatshow.service.OverlayService] 里.
 */
class MainActivity : ComponentActivity() {

    /**
     * 全屏隐藏状态栏. 用 BEHAVIOR_DEFAULT 而不是"下滑临时显示":
     * 后者会让状态栏在摸到屏幕顶部时闪回来, 与"全程全屏"的意图冲突.
     */
    private fun hideStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        }
    }

    /** 系统在某些时刻会自己把状态栏放回来, 重新获得焦点时补一次 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 主界面全屏: 隐藏状态栏, 并允许内容画进挖孔区.
        // 下滑可临时唤出状态栏, 它是系统提供的行为, 不是本应用自绘.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        hideStatusBar()
        setContent {
            FloatshowTheme {
                var showLab by remember { mutableStateOf(false) }
                if (showLab) {
                    DesignLabScreen(onBack = { showLab = false })
                } else {
                    SettingsScreen(onOpenLab = { showLab = true })
                }
            }
        }
    }
}
