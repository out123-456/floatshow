package com.qiqi.floatshow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.qiqi.floatshow.MainActivity
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.SettingsRepository
import com.qiqi.floatshow.overlay.CrosshairView
import com.qiqi.floatshow.overlay.PanelWindow

/**
 * 前台服务. 唯一的职责: 按 [SettingsRepository] 的内容维护悬浮层(准星 + 面板).
 * 设置变化时通过 SharedPreferences 监听即时同步`,所以主界面改完立刻生效.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: SettingsRepository
    private val lifecycleOwner = OverlayLifecycleOwner()

    private var crosshairView: CrosshairView? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var panel: PanelWindow? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSync = false

    /** 拖动滑条时 SP 一帧变好几次`,合并成一次处理, 避免重复解码与窗口刷新 */
    private val resyncRunnable = Runnable {
        pendingSync = false
        sync()
    }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleSync() }

    override fun onCreate() {
        super.onCreate()
        // 先声明前台: 系统只给 5 秒, 其它初始化都排在它后面, 慢了会直接 RemoteServiceException 崩掉
        createNotificationChannel()
        startForegroundCompat()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = SettingsRepository(this)
        lifecycleOwner.start()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType())
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // 兜底: 被系统重启或重复 start 时也保证处于前台状态
        startForegroundCompat()
        sync()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 屏幕旋转后准星要重新居中
        removeCrosshair()
        sync()
    }

    override fun onDestroy() {
        handler.removeCallbacks(resyncRunnable)
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        removeCrosshair()
        panel?.remove()
        panel = null
        lifecycleOwner.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 同步 ────────────────────────────────────────────────

    private fun scheduleSync() {
        if (pendingSync) return
        pendingSync = true
        handler.postDelayed(resyncRunnable, SYNC_INTERVAL_MS)
    }

    private fun sync() {
        applyCrosshair()
        applyPanel()
        if (!settings.anyOverlayEnabled) stopSelf()
    }

    private fun applyCrosshair() {
        if (!settings.crosshairEnabled || !Settings.canDrawOverlays(this)) {
            removeCrosshair()
            return
        }

        val view = crosshairView ?: CrosshairView(this).also { crosshairView = it }
        val size = settings.crosshairSize.coerceIn(SettingsRepository.MIN_SIZE, SettingsRepository.MAX_SIZE)
        view.setSize(size)
        view.setImage(settings.crosshairImageUri)
        view.alpha = settings.crosshairAlpha
        view.setTransform(
            settings.crosshairRotation,
            settings.crosshairFlipH,
            settings.crosshairFlipV
        )

        // 窗口只能整数像素, 亚像素尺寸靠 scale 补偿, 视觉尺寸与设置值一致
        val pxW = view.pixelWidth
        val pxH = view.pixelHeight
        view.scaleX = view.boxWidth / pxW
        view.scaleY = view.boxHeight / pxH

        val (screenWidth, screenHeight) = screenSize()
        val centerY = centeredY(screenHeight)
        val left = screenWidth / 2 + settings.crosshairOffsetX - pxW / 2
        val top = centerY + settings.crosshairOffsetY - pxH / 2

        val params = crosshairParams
        if (params == null) {
            val newParams = WindowManager.LayoutParams(
                pxW,
                pxH,
                overlayWindowType(),
                // 准星永远点击穿透`,不拦截任何操作
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left
                y = top
            }
            runCatching { windowManager.addView(view, newParams) }
                .onSuccess { crosshairParams = newParams }
                .onFailure { stopSelf() }
        } else {
            // 窗口没变化就不要调 WMS`,这是拖动滑条时最主要的额外开销
            if (params.width == pxW && params.height == pxH &&
                params.x == left && params.y == top
            ) {
                return
            }
            params.width = pxW
            params.height = pxH
            params.x = left
            params.y = top
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun applyPanel() {
        if (!settings.panelEnabled || !Settings.canDrawOverlays(this)) {
            panel?.remove()
            panel = null
            return
        }
        if (panel?.isShowing == true) return

        val (screenWidth, screenHeight) = screenSize()
        val initialX = settings.panelX.takeUnless { it == SettingsRepository.UNSET_POSITION }
            ?: (screenWidth / 4)
        val initialY = settings.panelY.takeUnless { it == SettingsRepository.UNSET_POSITION }
            ?: (screenHeight / 3)

        panel = PanelWindow(
            service = this,
            lifecycleOwner = lifecycleOwner,
            onMoved = { x, y ->
                settings.panelX = x
                settings.panelY = y
            },
            onClosed = { settings.panelEnabled = false }
        ).also {
            runCatching { it.show(initialX, initialY) }
                .onFailure { stopSelf() }
        }
    }

    private fun removeCrosshair() {
        val view = crosshairView ?: return
        crosshairParams?.let { runCatching { windowManager.removeView(view) } }
        crosshairView = null
        crosshairParams = null
    }

    // ── 屏幕计算 ────────────────────────────────────────────

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    /** 刘海模式: 用整块屏幕的中心; 否则扣掉状态栏后居中 */
    private fun centeredY(screenHeight: Int): Int {
        if (settings.crosshairNotchMode || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return screenHeight / 2
        }
        val statusBar =
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.statusBars()).top
        return (screenHeight - statusBar) / 2
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // ── 通知 ────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.channel_description) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        const val ACTION_START = "com.qiqi.floatshow.action.START"
        const val ACTION_STOP = "com.qiqi.floatshow.action.STOP"

        private const val CHANNEL_ID = "floatshow_overlay"
        private const val NOTIFICATION_ID = 1001

        /** 合并设置变化的间隔(约一帧) */
        private const val SYNC_INTERVAL_MS = 16L

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
