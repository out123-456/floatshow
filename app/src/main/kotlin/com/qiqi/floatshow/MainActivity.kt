package com.qiqi.floatshow

import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.text.font.FontFamily
import com.qiqi.floatshow.data.SettingsRepository
import com.qiqi.floatshow.text.FontLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.qiqi.floatshow.service.OverlayService
import com.qiqi.floatshow.service.OverlayTileService
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
    /**
     * 快捷方式直接开关准星. 写完设置就要把服务拉起来, 否则改了也不生效.
     */
    private fun toggleCrosshairFromShortcut() {
        val settings = SettingsRepository(this)
        val next = !settings.crosshairEnabled
        settings.crosshairEnabled = next
        // 磁贴在别的进程里不知道这里改了, 主动让它刷新
        OverlayTileService.refresh(this)
        if (settings.anyOverlayEnabled && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
    }

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
        // 桌面长按图标的快捷方式: 只翻转准星, 不打开界面
        if (intent?.action == ACTION_TOGGLE_CROSSHAIR) {
            toggleCrosshairFromShortcut()
            finish()
            return
        }
        enableEdgeToEdge()
        // 主界面全屏: 隐藏状态栏, 并允许内容画进挖孔区.
        // 下滑可临时唤出状态栏, 它是系统提供的行为, 不是本应用自绘.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        hideStatusBar()
        setContent {
            // 字体是全局的: 主界面与悬浮面板用同一份 Typeface.
            // uri 存在 SharedPreferences 里, 跟着 pref 变化更新, 换字体后立刻生效.
            val settings = remember { SettingsRepository(this) }
            var fontUri by remember { mutableStateOf(settings.panelFontUri) }
            DisposableEffect(settings) {
                val listener =
                    android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == SettingsRepository.KEY_PANEL_FONT_URI) {
                            fontUri = settings.panelFontUri
                        }
                    }
                settings.prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            // 解析字体是 IO, 放到后台; 期间先沿用系统字体, 完成后自动换掉
            val fontFamily by produceState<FontFamily?>(null, fontUri) {
                value = if (fontUri == null) null else withContext(Dispatchers.IO) {
                    FontLoader.load(this@MainActivity, fontUri)?.let(::FontFamily)
                }
            }
            FloatshowTheme(fontFamily = fontFamily) {
                SettingsScreen()
            }
        }
    }

    companion object {
        /** 桌面快捷方式用: 只翻转准星, 不进界面 */
        const val ACTION_TOGGLE_CROSSHAIR = "com.qiqi.floatshow.action.TOGGLE_CROSSHAIR"
    }
}
