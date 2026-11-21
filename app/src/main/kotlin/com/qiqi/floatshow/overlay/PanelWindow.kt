package com.qiqi.floatshow.overlay

import android.app.Service
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.qiqi.floatshow.service.OverlayLifecycleOwner
import com.qiqi.floatshow.ui.OverlayPanelContent
import com.qiqi.floatshow.ui.theme.FloatshowTheme

/** 窗口几何, 单位 px */
data class PanelGeometry(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 面板窗口. 窗口尺寸为精确像素值, 球态与展开态只是两个不同的几何.
 *
 * 几何的唯一真相在 Compose 侧; 这里只负责把它套到窗口上:
 * 同一帧内多次修改合并成一次 updateViewLayout, 避免跨进程调用堆积.
 */
class PanelWindow(
    private val service: Service,
    private val lifecycleOwner: OverlayLifecycleOwner,
    private val initial: PanelGeometry,
    private val expandedInitially: Boolean,
    private val toolbarAtBottomInitially: Boolean,
    private val ballSizePx: Int,
    private val controlSizePx: Int,
    private val minSizePx: Int,
    private val restoreWidthPx: Int,
    private val restoreHeightPx: Int,
    private val screenSize: () -> Pair<Int, Int>,
    private val onCommitted: (PanelGeometry, Boolean, Boolean) -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = service.getSystemService(Service.WINDOW_SERVICE) as WindowManager

    private var params: WindowManager.LayoutParams? = null
    private var view: ComposeView? = null

    /** 窗口当前已经套用的几何(不参与 Compose 重组) */
    private var applied = initial
    /** 待套用的最新几何 */
    private var target = initial
    private var pendingApply = false

    private val applyRunnable = Runnable {
        pendingApply = false
        applyGeometry()
    }

    val isShowing: Boolean get() = view != null

    fun show() {
        if (view != null) return

        val layoutParams = WindowManager.LayoutParams(
            initial.width,
            initial.height,
            overlayWindowType(),
            // 不抢焦点, 所以不会弹输入法也不会打断别处的光标
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initial.x
            y = initial.y
        }

        val composeView = ComposeView(service).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                FloatshowTheme {
                    val (screenWidth, screenHeight) = screenSize()
                    OverlayPanelContent(
                        geometry = initial,
                        expandedInitially = expandedInitially,
                        toolbarAtBottomInitially = toolbarAtBottomInitially,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        ballSizePx = ballSizePx,
                        controlSizePx = controlSizePx,
                        minSizePx = minSizePx,
                        restoreWidthPx = restoreWidthPx,
                        restoreHeightPx = restoreHeightPx,
                        onGeometry = ::applyGeometry,
                        onCommit = onCommitted,
                        onClose = onClose
                    )
                }
            }
        }

        windowManager.addView(composeView, layoutParams)
        params = layoutParams
        view = composeView
    }

    /** Compose 算出的新几何, 合并在下一帧套用 */
    fun applyGeometry(new: PanelGeometry) {
        if (target == new) return
        target = new
        if (pendingApply) return
        pendingApply = true
        view?.postOnAnimation(applyRunnable)
    }

    private fun applyGeometry() {
        val p = params ?: return
        val v = view ?: return
        val g = target
        if (applied == g) return
        applied = g
        p.x = g.x
        p.y = g.y
        p.width = g.width
        p.height = g.height
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    fun remove() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view?.removeCallbacks(applyRunnable)
        view = null
        params = null
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
