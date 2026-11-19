package com.example.crosshair

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.os.Build

class CrosshairTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = CrosshairPrefs(this)
        if (prefs.serviceRunning) {
            stopService(Intent(this, CrosshairService::class.java))
        } else {
            val intent = Intent(this, CrosshairService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val prefs = CrosshairPrefs(this)
        qsTile?.apply {
            state = if (prefs.serviceRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
