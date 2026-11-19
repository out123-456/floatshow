package com.example.crosshair

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat

class CrosshairService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var crosshairView: CrosshairView
    private lateinit var prefs: CrosshairPrefs

    override fun onCreate() {
        super.onCreate()
        prefs = CrosshairPrefs(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        prefs.serviceRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeOverlay()
        addOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        prefs.serviceRunning = false
    }

    private fun addOverlay() {
        crosshairView = CrosshairView(this)
        val size = prefs.size
        crosshairView.setDisplaySize(size)
        crosshairView.setTransparency(prefs.transparency)

        val imageUriStr = prefs.imageUri
        if (imageUriStr != null) {
            try {
                val uri = Uri.parse(imageUriStr)
                crosshairView.updateImage(uri, size)
            } catch (_: Exception) { }
        }

        val metrics = windowManager.currentWindowMetrics
        val screenWidth = metrics.bounds.width()
        val screenHeight = metrics.bounds.height()

        val statusBarHeight = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            metrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        } else {
            0
        }

        val centerY = if (prefs.notchMode) {
            screenHeight / 2
        } else {
            (screenHeight - statusBarHeight) / 2
        }
        val centerX = screenWidth / 2

        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centerX + prefs.offsetX - size / 2
            y = centerY + prefs.offsetY - size / 2
        }

        windowManager.addView(crosshairView, params)
    }

    private fun removeOverlay() {
        try {
            windowManager.removeView(crosshairView)
        } catch (_: Exception) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "crosshair_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
