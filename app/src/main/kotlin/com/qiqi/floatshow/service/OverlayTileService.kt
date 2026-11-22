package com.qiqi.floatshow.service

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qiqi.floatshow.data.SettingsRepository

/**
 * 快捷设置磁贴: 一键开关准星.
 * 它和主页那个开关写的是同一个 `crosshair_enabled`, 只是主页原先没监听这个 key,
 * 所以从磁贴改了之后主页仍显示旧状态, 看起来像两个互不相干的开关.
 */
class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (SettingsRepository(this@OverlayTileService).crosshairEnabled) {
                Tile.STATE_ACTIVE
            } else {
                Tile.STATE_INACTIVE
            }
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val settings = SettingsRepository(this)
        val enabled = !settings.crosshairEnabled
        settings.crosshairEnabled = enabled

        // 只负责启动. 关闭交给 Service 自己延时自停, 磁贴发 stopService 同样会掐断 startForeground
        if (settings.anyOverlayEnabled && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        }

        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
