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
import com.qiqi.floatshow.overlay.PanelLayout
import kotlin.math.max
import kotlin.math.min
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
    /** 面板当前用的字体 uri, 用来判断换字体后要不要重建窗口 */
    private var lastFontUri: String? = null
    /** 上一轮同步时面板是否开着, 用来识别"关→开"这个沿 */
    private var lastPanelEnabled = false
    /** 本次新建窗口是不是开关从关变开触发的; 只有这种才算全新打开 */
    private var freshOpen = false

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
        // 面板开关从"关"变"开"算一次全新打开: 从球开始, 并让球离屏幕边至少 5%
        val enabled = settings.panelEnabled && Settings.canDrawOverlays(this)
        if (enabled && !lastPanelEnabled) {
            settings.panelExpanded = false
            freshOpen = true
        }
        lastPanelEnabled = enabled

        // 字体在 PanelWindow 构造时读到字段里, 换字体必须重建悬浮窗才会生效
        if (panel != null && lastFontUri != settings.panelFontUri) {
            panel?.remove()
            panel = null
        }
        lastFontUri = settings.panelFontUri
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
                // 准星永远点击穿透`,不拦截任何操作.
                // LAYOUT_IN_SCREEN 必须有: 没有它, y 的原点是状态栏下方,
                // 状态栏一出现准星就整体下移, 与 crosshairCenter() 算出的坐标对不上.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
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

        // 全新打开一律从球开始; 服务重建(如被系统回收)则恢复上次的展开状态
        val expanded = settings.panelExpanded && !freshOpen
        freshOpen = false
        // 两种几何分开存: 收成球不会覆盖面板自己的位置与尺寸
        val panelGeometry = PanelGeometry(
            x = settings.panelX.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: ((screenWidth - restoreWidth) / 2),
            y = settings.panelY.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: ((screenHeight - restoreHeight) / 2),
            width = restoreWidth,
            height = restoreHeight
        )
        val marginPx = (min(screenWidth, screenHeight) * EDGE_MARGIN_RATIO).toInt()
        val ballGeometry = PanelGeometry(
            x = settings.panelBallX.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: (screenWidth - ballPx - dpToPx(24, density)),
            y = settings.panelBallY.takeUnless { it == SettingsRepository.UNSET_POSITION }
                ?: dpToPx(24, density),
            width = ballPx,
            height = ballPx
        ).let { geometry ->
            // 贴边就推离: 贴得太近时球可能被屏幕圆角/手势区吃掉, 点不到就只剩开关能救
            PanelLayout.pushAwayFromEdges(geometry, screenWidth, screenHeight, marginPx)
        }

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
     * 准星中心点.
     *
     * 坐标原点固定在物理屏幕左上角(窗口带 FLAG_LAYOUT_IN_SCREEN), 所以这里只按屏幕尺寸算,
     * 与状态栏是否显示无关:
     *  · 刘海模式: 相对整屏居中
     *  · 非刘海模式: 相对"扣掉挖孔后的区域"居中(挖孔 inset 四边都可能非零)
     * 横屏时用户看到的"水平/垂直"与设备自然方向互换, 所以偏移量也要跟着换.
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
     * 屏幕尺寸. 必须尊重 `wm size` 的覆盖值, 所以 getRealSize() 优先:
     * 它给的是逻辑 display 的当前尺寸, 覆盖后就是覆盖值.
     * maximumWindowMetrics 不能用: 它给的是物理溢出范围, 覆盖成 900x2000 后仍报原始分辨率.
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
            if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /** 挖孔占掉的边距. 挖孔是硬件属性, 首次取到非零值后固定下来, 不再随窗口状态反复问系统 */
    private var cachedCutout: android.graphics.Insets? = null

    private fun usableInsets(): android.graphics.Insets {
        val cached = cachedCutout
        if (cached != null) return cached
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return android.graphics.Insets.NONE
        val insets = runCatching {
            windowManager.currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.displayCutout())
        }.getOrNull() ?: return android.graphics.Insets.NONE
        // 还没探到挖孔就先不缓存, 下次继续探
        if (insets.left > 0 || insets.top > 0 || insets.right > 0 || insets.bottom > 0) {
            cachedCutout = insets
        }
        return insets
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

        /** 球离屏幕边的最小距离, 相对屏幕短边的比例 */
        private const val EDGE_MARGIN_RATIO = 0.05f

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
