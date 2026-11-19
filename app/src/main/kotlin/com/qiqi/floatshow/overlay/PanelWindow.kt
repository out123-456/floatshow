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

        val composeView = ComposeView(service).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                FloatshowTheme {
                    OverlayPanelContent(
                        onDragDelta = ::moveBy,
                        onDragEnd = { onMoved(params?.x ?: initialX, params?.y ?: initialY) },
                        onClose = onClosed
                    )
                }
            }
        }

        windowManager.addView(composeView, layoutParams)
        params = layoutParams
        view = composeView
    }

    fun moveBy(dx: Float, dy: Float) {
        val p = params ?: return
        accX += dx
        accY += dy
        val stepX = accX.toInt()
        val stepY = accY.toInt()
        if (stepX == 0 && stepY == 0) return
        accX -= stepX
        accY -= stepY
        p.x += stepX
        p.y += stepY
        windowManager.updateViewLayout(view, p)
    }

    fun remove() {
        view?.let { runCatching { windowManager.removeView(it) } }
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
