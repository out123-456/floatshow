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

/**
 * 可拖动的内容面板窗口. 关闭/移动的最终结果通过回调交给 Service 写回设置.
 */
class PanelWindow(
    private val service: Service,
    private val lifecycleOwner: OverlayLifecycleOwner,
    private val onMoved: (x: Int, y: Int) -> Unit,
    private val onClosed: () -> Unit
) {

    private val windowManager =
        service.getSystemService(Service.WINDOW_SERVICE) as WindowManager

    private var params: WindowManager.LayoutParams? = null
    private var view: ComposeView? = null

    /** 拖动时的亚像素累积`,避免小步拖动被取整吃掉 */
    private var accX = 0f
    private var accY = 0f
    /** 本帧是否已经有待执行的移动`,保证一帧最多一次 updateViewLayout */
    private var framePending = false

    val isShowing: Boolean get() = view != null

    fun show(initialX: Int, initialY: Int) {
        if (view != null) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            // 不抢焦点(不弹输入法)`,但可以接收触摸;允许超出屏幕边缘
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val maxWidthDp = (service.resources.configuration.screenWidthDp - 48).coerceIn(200, 360)

        val composeView = ComposeView(service).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                FloatshowTheme {
                    OverlayPanelContent(
                        maxWidthDp = maxWidthDp,
                        onDragDelta = ::moveBy,
                        onDragEnd = {
                            val (x, y) = flush()
                            onMoved(x, y)
                        },
                        onClose = onClosed
                    )
                }
            }
        }

        windowManager.addView(composeView, layoutParams)
        params = layoutParams
        view = composeView
    }

    /**
     * 拖动回调可能一帧来好几次, 这里只累积位移, 每帧合并成一次 updateViewLayout.
     * WindowManager 是跨进程调用, 一帧调多次就是拖动卡顿的来源.
     */
    fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        accX += dx
        accY += dy
        if (framePending) return
        framePending = true
        v.postOnAnimation(moveRunnable)
    }

    private val moveRunnable = Runnable {
        framePending = false
        applyMove()
    }

    /** 把累积位移真正写进窗口参数`,返回松手时的坐标 */
    fun flush(): Pair<Int, Int> {
        view?.removeCallbacks(moveRunnable)
        framePending = false
        applyMove()
        return (params?.x ?: 0) to (params?.y ?: 0)
    }

    private fun applyMove() {
        val p = params ?: return
        val v = view ?: return
        val stepX = accX.toInt()
        val stepY = accY.toInt()
        if (stepX == 0 && stepY == 0) return
        accX -= stepX
        accY -= stepY
        p.x += stepX
        p.y += stepY
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    fun remove() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view?.removeCallbacks(moveRunnable)
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
