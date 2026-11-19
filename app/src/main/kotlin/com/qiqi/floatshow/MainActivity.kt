package com.qiqi.floatshow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.qiqi.floatshow.ui.SettingsScreen
import com.qiqi.floatshow.ui.theme.FloatshowTheme

/**
 * 唯一的活动. 只做两件事: 检查权限 / 调整悬浮层参数`,真正的显示都在 [com.qiqi.floatshow.service.OverlayService] 里.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FloatshowTheme {
                SettingsScreen()
            }
        }
    }
}
