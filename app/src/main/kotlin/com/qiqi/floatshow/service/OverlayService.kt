package com.qiqi.floatshow.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
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
import android.widget.Toast
import android.provider.Settings
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.qiqi.floatshow.MainActivity
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.SettingsRepository
import com.qiqi.floatshow.overlay.CrosshairView
import com.qiqi.floatshow.overlay.PanelGeometry
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

    /**
     * Service 不会像 Activity 那样自动收到配置变化, 必须自己注册.
     * 旋转后准星要重新居中, 面板位置也要重新约束.
     */
    private val configCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) = sync()
        override fun onLowMemory() = Unit
    }

    override fun onCreate() {
        super.onCreate()
        // 先声明前台: 系统只给 5 秒, 其它初始化都排在它后面, 慢了会直接 RemoteServiceException 崩掉
        createNotificationChannel()
        startForegroundCompat()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = SettingsRepository(this)
        settings.serviceRunning = true
        lifecycleOwner.start()
        registerComponentCallbacks(configCallbacks)
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
            // 由服务自己收尾: 此时 startForeground 已经调过, 不会踩到 5 秒规则
            stopSelf()
            return START_NOT_STICKY
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // 兜底: 被系统重启或重复 start 时也保证处于前台状态
        startForegroundCompat()
        sync()
        return START_STICKY
    }

    override fun onDestroy() {
        settings.serviceRunning = false
        handler.removeCallbacks(resyncRunnable)
        unregisterComponentCallbacks(configCallbacks)
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
        // 服务常驻: 前两种层全部收起也不自停, 只有被系统回收才结束, 下次开关不用重建
    }

    private fun applyCrosshair() {
        if (!settings.crosshairEnabled || !Settings.canDrawOverlays(this)) {
            removeCrosshair()
            return
        }

        val view = crosshairView ?: CrosshairView(this).also {
            crosshairView = it
            it.onLoadFailed = { notifyImageFailed() }
            it.onLoaded = { scheduleSync() }
        }
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
        val center = crosshairCenter(screenWidth, screenHeight)
        val left = center.first - pxW / 2
        val top = center.second - pxH / 2

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
                // 准星允许画进挖孔区(已有 NO_LIMITS 让它能超出屏幕范围)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
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
        val density = resources.displayMetrics.density
        val controlPx = dpToPx(SettingsRepository.PANEL_CONTROL_SIZE_DP, density)
        val ballPx = dpToPx(SettingsRepository.PANEL_BALL_SIZE_DP, density)
        val minPx = dpToPx(SettingsRepository.MIN_PANEL_SIZE_DP, density)
        val restoreWidth = settings.panelWidthPx.takeIf { it > 0 }
            ?.coerceAtLeast(minPx)
            ?: dpToPx(SettingsRepository.DEFAULT_PANEL_WIDTH_DP, density)
        val restoreHeight = settings.panelHeightPx.takeIf { it > 0 }
            ?.coerceAtLeast(minPx)
            ?: dpToPx(SettingsRepository.DEFAULT_PANEL_HEIGHT_DP, density)

        val expanded = settings.panelExpanded
        // 两种几何分开存: 收成球不会覆盖面板自己的位置与尺寸
        val panelGeometry = PanelGeometry(
            x = settings.panelX.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: ((screenWidth - restoreWidth) / 2),
            y = settings.panelY.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: ((screenHeight - restoreHeight) / 2),
            width = restoreWidth,
            height = restoreHeight
        )
        val ballGeometry = PanelGeometry(
            x = settings.panelBallX.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: (screenWidth - ballPx - dpToPx(24, density)),
            y = settings.panelBallY.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: dpToPx(24, density),
            width = ballPx,
            height = ballPx
        )

        panel = PanelWindow(
            service = this,
            initialPanel = panelGeometry,
            initialBall = ballGeometry,
            expandedInitially = expanded,
            toolbarAtBottomInitially = settings.panelToolbarAtBottom,
            ballSizePx = ballPx,
            controlSizePx = controlPx,
            minSizePx = minPx,
            onState = { state ->
                settings.editing {
                    putBoolean(SettingsRepository.KEY_PANEL_EXPANDED, state.expanded)
                    putBoolean(SettingsRepository.KEY_PANEL_TOOLBAR_BOTTOM, state.toolbarAtBottom)
                    putInt(SettingsRepository.KEY_PANEL_X, state.panel.x)
                    putInt(SettingsRepository.KEY_PANEL_Y, state.panel.y)
                    putInt(SettingsRepository.KEY_PANEL_WIDTH, state.panel.width)
                    putInt(SettingsRepository.KEY_PANEL_HEIGHT, state.panel.height)
                    putInt(SettingsRepository.KEY_PANEL_BALL_X, state.ball.x)
                    putInt(SettingsRepository.KEY_PANEL_BALL_Y, state.ball.y)
                }
            },
            onClose = { settings.panelEnabled = false }
        ).also {
            runCatching { it.show() }.onFailure { stopSelf() }
        }
    }

    private fun dpToPx(dp: Int, density: Float): Int = (dp * density).toInt()

    /** 图片读不出来时给个提示, 否则用户只会看到什么都没显示 */
    private fun notifyImageFailed() {
        Toast.makeText(this, getString(R.string.crosshair_image_failed), Toast.LENGTH_SHORT).show()
    }

    private fun removeCrosshair() {
        val view = crosshairView ?: return
        crosshairParams?.let { runCatching { windowManager.removeView(view) } }
        crosshairView = null
        crosshairParams = null
    }

    // ── 屏幕计算 ────────────────────────────────────────────

    /**
     * 整屏尺寸(物理像素). 用 display 的 real size 而不是 displayMetrics:
     * 后者会随状态栏显隐变化, 导致准星在唤出状态栏后突然位移.
     */
    private fun screenSize(): Pair<Int, Int> {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        if (display != null) {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            if (point.x > 0 && point.y > 0) return point.x to point.y
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * 准星中心.
     * 忽视刘海打开 = 相对整屏居中; 关闭 = 相对"系统栏与挖孔以外"的可用区居中(导航栏不算可用区损失).
     * 避让轴随屏幕朝向走: 竖屏避让上下, 横屏避让左右; 两个偏移量随之换轴.
     */
    private fun crosshairCenter(screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val landscape = screenWidth > screenHeight
        val insets = usableInsets()
        val baseX: Int
        val baseY: Int
        if (landscape) {
            baseX = if (settings.crosshairNotchMode) {
                screenWidth / 2
            } else {
                insets.left + (screenWidth - insets.left - insets.right) / 2
            }
            baseY = screenHeight / 2
        } else {
            baseX = screenWidth / 2
            baseY = if (settings.crosshairNotchMode) {
                screenHeight / 2
            } else {
                insets.top + (screenHeight - insets.top) / 2
            }
        }
        val offsetX = if (landscape) settings.crosshairOffsetY else settings.crosshairOffsetX
        val offsetY = if (landscape) settings.crosshairOffsetX else settings.crosshairOffsetY
        return (baseX + offsetX) to (baseY + offsetY)
    }

    /**
     * 可用区里要避让的部分: 只算挖孔, 状态栏一律当作不存在(导航栏本来也不算).
     * 状态栏能随时唤出又消失, 让它参与计算会让准星跳来跳去.
     */
    private fun usableInsets(): android.graphics.Insets {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return android.graphics.Insets.NONE
        return windowManager.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.displayCutout())
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
                // 最低档: 无声无震动无角标, 收进折叠区, 只作为系统对前台服务的硬性要求
                NotificationManager.IMPORTANCE_MIN
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
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
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

        /** 关闭服务: 用 startService 递一个停止指令, 由服务内部 stopSelf */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, OverlayService::class.java).setAction(ACTION_STOP)
                )
            }
        }

    }
}
