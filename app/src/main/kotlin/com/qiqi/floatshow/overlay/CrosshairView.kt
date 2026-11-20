package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import com.caverock.androidsvg.SVG
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 准星层. 只负责把图片(或默认圆点)画出来`,窗口参数由 [com.qiqi.floatshow.service.OverlayService] 管理.
 *
 * 尺寸语义: [setSize] 指的是图片的**水平边**(像素), 高度按图片自身比例算, 与屏幕横竖无关.
 * 旋转时不裁角: 窗口按旋转后的包围盒开, 内容始终绕包围盒中心旋转.
 *
 * 支持位图与 SVG 两种来源: 位图按屏幕宽度采样解码, SVG 直接矢量绘制, 放大不会糊.
 */
class CrosshairView(context: Context) : View(context) {

    private var size: Float = 4f
    private var bitmap: Bitmap? = null
    /** SVG 预渲染成的矢量 Picture`,绘制时按目标矩形缩放, 尺寸完全由 rect 决定 */
    private var vector: Picture? = null
    private var vectorAspect = 1f
    /** 已经加载的 uri`,用于避免每次设置变化都重新解码(这是后台性能的最大开销) */
    @Volatile
    private var uriKey: String? = null
    /** 图片/SVG 读取失败时通知外部(主线程回调) */
    var onLoadFailed: ((String) -> Unit)? = null
    private var rotation = 0f
    private var flipH = false
    private var flipV = false

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 单线程即可: 同一时刻只会有一张图在解, 连点切图不会并发解出好几张大图 */
    private val decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "crosshair-decode").apply { isDaemon = true }
    }

    private val fallbackPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 旋转前的内容尺寸`,宽度就是设置项里的"大小" */
    val contentWidth: Float get() = size
    val contentHeight: Float get() = size * aspect()

    private fun aspect(): Float {
        val bmp = bitmap
        if (bmp != null) {
            return if (bmp.width <= 0) 1f else bmp.height.toFloat() / bmp.width.toFloat()
        }
        if (vector != null) return vectorAspect
        return 1f
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

    /**
     * uri 为 null 时回退到默认圆点; 同一个 uri 重复设置不会重新加载.
     * 加载放后台线程, 大图不至于把主线程 (和服务启动) 卡住.
     */
    fun setImage(uriString: String?) {
        if (uriKey == uriString) return
        uriKey = uriString
        if (uriString == null) {
            bitmap = null
            vector = null
            requestLayout()
            invalidate()
            return
        }
        decodeExecutor.execute {
            // 排队期间已经换成别的图了, 直接丢弃, 别白解一张
            if (uriKey != uriString) return@execute
            val uri = Uri.parse(uriString)
            var loadedBitmap: Bitmap? = null
            var loadedSvg: Picture? = null
            var loadedAspect = 1f
            if (isSvg(uri)) {
                val doc = decodeSvg(uri)
                if (doc != null) {
                    loadedAspect = svgAspect(doc)
                    loadedSvg = doc.renderToPicture(
                        pictureWidth(loadedAspect),
                        pictureHeight(loadedAspect)
                    )
                }
            } else {
                loadedBitmap = decodeBitmap(uri)
            }
            mainHandler.post {
                // 加载期间 uri 又被换掉了, 这批结果作废
                if (uriKey != uriString) return@post
                bitmap = loadedBitmap
                vector = loadedSvg
                vectorAspect = loadedAspect
                requestLayout()
                invalidate()
                if (loadedBitmap == null && loadedSvg == null) {
                    onLoadFailed?.invoke(uriString)
                }
            }
        }
    }

    fun setTransform(rotationDeg: Float, flipHorizontal: Boolean, flipVertical: Boolean) {
        if (rotation == rotationDeg && flipH == flipHorizontal && flipV == flipVertical) return
        rotation = rotationDeg
        flipH = flipHorizontal
        flipV = flipVertical
        requestLayout()
        invalidate()
    }

    /** 系统不给 SVG 的 MIME 时靠扩展名补一刀 */
    private fun isSvg(uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime == MIME_SVG) return true
        return uri.lastPathSegment?.endsWith(".svg", ignoreCase = true) == true
    }

    /** 按屏幕宽度做采样解码`,避免一张手机照片解出几十 MB 的位图 */
    private fun decodeBitmap(uri: Uri): Bitmap? = try {
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

    private fun decodeSvg(uri: Uri): SVG? = try {
        context.contentResolver.openInputStream(uri)?.use { SVG.getFromInputStream(it) }
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
        when {
            bmp != null -> canvas.drawBitmap(bmp, null, rect, null)
            vector != null -> canvas.drawPicture(vector, rect)
            else -> canvas.drawCircle(cx, cy, w / 2f, fallbackPaint)
        }
        canvas.restore()
    }

    /** viewBox 优先: 只给 viewBox 没有 width/height 的文件也能算比例 */
    private fun svgAspect(doc: SVG): Float {
        val box = runCatching { doc.documentViewBox }.getOrNull()
        if (box != null && box.width() > 0f && box.height() > 0f) return box.height() / box.width()
        val w = doc.documentWidth
        val h = doc.documentHeight
        if (w > 0f && h > 0f) return h / w
        val ratio = runCatching { doc.documentAspectRatio }.getOrDefault(-1f)
        if (ratio > 0f) return 1f / ratio
        return 1f
    }

    /** Picture 的比例要和内容一致, 否则拉伸到目标矩形会留边 */
    private fun pictureWidth(aspect: Float): Int =
        if (aspect >= 1f) (SVG_PICTURE_SIZE / aspect).toInt().coerceAtLeast(1) else SVG_PICTURE_SIZE

    private fun pictureHeight(aspect: Float): Int =
        if (aspect >= 1f) SVG_PICTURE_SIZE else (SVG_PICTURE_SIZE * aspect).toInt().coerceAtLeast(1)

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 不主动 recycle: 窗口移除后 View 可能被重建复用, 让 GC 处理更安全
        bitmap = null
        vector = null
        uriKey = null
    }

    private companion object {
        const val MIME_SVG = "image/svg+xml"

        /** SVG 先渲染到这个尺寸的 Picture, 缩放绘制时仍是矢量 */
        const val SVG_PICTURE_SIZE = 1024
    }
}
