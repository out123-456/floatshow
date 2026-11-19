package com.qiqi.floatshow.service

import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.qiqi.floatshow.data.SettingsRepository

/** 快捷设置磁贴: 一键开关准星 */
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

        if (settings.anyOverlayEnabled && Settings.canDrawOverlays(this)) {
            OverlayService.start(this)
        } else if (!settings.anyOverlayEnabled) {
            OverlayService.stop(this)
        }

        qsTile?.apply {
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
