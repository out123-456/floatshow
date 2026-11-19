package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View

/**
 * 准星层. 只负责把图片(或默认圆点)画出来`,窗口参数由 [com.qiqi.floatshow.service.OverlayService] 管理.
 */
class CrosshairView(context: Context) : View(context) {

    private var displaySize: Int = 40
    private var bitmap: Bitmap? = null

    private val fallbackPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setDisplaySize(size: Int) {
        if (displaySize == size) return
        displaySize = size
        requestLayout()
        invalidate()
    }

    /** uri 为 null 时回退到默认圆点 */
    fun setImage(uri: Uri?) {
        bitmap = uri?.let(::decode)
        invalidate()
    }

    private fun decode(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
    } catch (_: Exception) {
        null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(displaySize, displaySize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, displaySize, displaySize), null)
        } else {
            val half = displaySize / 2f
            canvas.drawCircle(half, half, half, fallbackPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 不主动 recycle: 窗口移除后 View 可能被重建复用, 让 GC 处理更安全
        bitmap = null
    }
}
