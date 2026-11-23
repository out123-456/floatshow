package com.qiqi.floatshow.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qiqi.floatshow.data.SettingsRepository

/**
 * 快捷设置磁贴: 一键开关准星.
 *
 * 磁贴状态不是主动推送的: 系统只在"用户拉下通知栏"等时机调用 [onStartListening].
 * 所以在别处(主界面开关、桌面快捷方式)改了准星之后, 必须显式请求一次刷新,
 * 否则拉下通知栏看到的还是旧状态 —— 也就是"两个开关互不联动"的那种观感.
 */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val settings = SettingsRepository(this)
        settings.crosshairEnabled = !settings.crosshairEnabled
        // 打开准星时把服务拉起来; 关闭交给服务的自停逻辑, 这里不去 stopService,
        // 那会掐断 startForeground 的时序
        if (settings.anyOverlayEnabled && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }
        syncTile()
    }

    private fun syncTile() {
        qsTile?.apply {
            state = if (SettingsRepository(this@OverlayTileService).crosshairEnabled) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    companion object {
        /**
         * 让系统重新拉一次磁贴状态. 设置被本应用在别处改动后调用.
         * 只声明过磁贴、用户没把它加到快捷面板时, 这个调用是空的, 不会报错.
         */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, OverlayTileService::class.java)
                )
            }
        }
    }
}
