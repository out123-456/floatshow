package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 准星层. 只负责把图片(或默认圆点)画出来`,窗口参数由 [com.qiqi.floatshow.service.OverlayService] 管理.
 *
 * 尺寸语义: [setSize] 指的是图片的**水平边**(像素), 高度按图片自身比例算, 与屏幕横竖无关.
 * 旋转时不裁角: 窗口按旋转后的包围盒开, 内容始终绕包围盒中心旋转.
 */
class CrosshairView(context: Context) : View(context) {

    private var size: Float = 4f
    private var bitmap: Bitmap? = null
    /** 已经解码的 uri`,用于避免每次设置变化都重新解码图片(这是后台性能的最大开销) */
    private var uriKey: String? = null
    private var rotation = 0f
    private var flipH = false
    private var flipV = false

    private val fallbackPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 旋转前的内容尺寸`,宽度就是设置项里的"大小" */
    val contentWidth: Float get() = size
    val contentHeight: Float get() = size * aspect()

    private fun aspect(): Float {
        val bmp = bitmap ?: return 1f
        return if (bmp.width <= 0) 1f else bmp.height.toFloat() / bmp.width.toFloat()
    }

    /** 旋转后的包围盒尺寸(浮点)`,窗口要按它开才能不裁角 */
    private fun rotatedBox(): Pair<Float, Float> {
        val rad = Math.toRadians(rotation.toDouble())
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val w = contentWidth
        val h = contentHeight
        return (w * c + h * s) to (w * s + h * c)
    }

    val boxWidth: Float get() = rotatedBox().first
    val boxHeight: Float get() = rotatedBox().second

    /** 窗口只能整数像素`,亚像素部分由 scale 补偿 */
    val pixelWidth: Int get() = ceil(boxWidth).toInt().coerceAtLeast(1)
    val pixelHeight: Int get() = ceil(boxHeight).toInt().coerceAtLeast(1)

    fun setSize(value: Float) {
        if (size == value) return
        size = value
        requestLayout()
        invalidate()
    }

    /** uri 为 null 时回退到默认圆点; 同一个 uri 重复设置不会重新解码 */
    fun setImage(uriString: String?) {
        if (uriKey == uriString) return
        uriKey = uriString
        bitmap = uriString?.let { decode(Uri.parse(it)) }
        requestLayout()
        invalidate()
    }

    fun setTransform(rotationDeg: Float, flipHorizontal: Boolean, flipVertical: Boolean) {
        if (rotation == rotationDeg && flipH == flipHorizontal && flipV == flipVertical) return
        rotation = rotationDeg
        flipH = flipHorizontal
        flipV = flipVertical
        requestLayout()
        invalidate()
    }

    /** 按屏幕宽度做采样解码`,避免一张手机照片解出几十 MB 的位图 */
    private fun decode(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val target = resources.displayMetrics.widthPixels.coerceAtLeast(512)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    } catch (_: Exception) {
        null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(pixelWidth, pixelHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        val cx = pixelWidth / 2f
        val cy = pixelHeight / 2f
        val w = contentWidth
        val h = contentHeight
        val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.scale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, cx, cy)
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, rect, null)
        } else {
            canvas.drawCircle(cx, cy, w / 2f, fallbackPaint)
        }
        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 不主动 recycle: 窗口移除后 View 可能被重建复用, 让 GC 处理更安全
        bitmap = null
        uriKey = null
    }
}
