package com.qiqi.floatshow.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.caverock.androidsvg.SVG
import java.util.concurrent.Executors

/**
 * SVG 查看器. 矢量本身没有"分辨率"问题, 放大只是在画布上换了个变换矩阵,
 * 只需要在放大时把画布裁剪到视口范围, 免得整份文档被反复往外画.
 */
class SvgView(context: Context) : View(context) {

    private var svg: SVG? = null
    private var docWidth = 0f
    private var docHeight = 0f

    /** 相对标称尺寸的倍数 */
    var zoom = 1f
        private set
    var maxZoom = 64f
        private set

    private var offsetX = 0f
    private var offsetY = 0f

    private val executor = Executors.newSingleThreadExecutor()
    private var token = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dest = RectF()

    var onLoaded: ((Int, Int) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (zoom * detector.scaleFactor).coerceIn(1f, maxZoom)
                if (target == zoom) return true
                val ratio = target / zoom
                offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
                offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
                zoom = target
                constrainOffset()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float
            ): Boolean {
                offsetX -= dx
                offsetY -= dy
                constrainOffset()
                invalidate()
                return true
            }
        }
    )

    /** 由外层按钮设定倍数, 保持视图中心不动 */
    fun setZoom(value: Float) {
        val target = value.coerceIn(1f, maxZoom)
        if (target == zoom) return
        val cx = width / 2f
        val cy = height / 2f
        val ratio = target / zoom
        offsetX = cx - (cx - offsetX) * ratio
        offsetY = cy - (cy - offsetY) * ratio
        zoom = target
        constrainOffset()
        invalidate()
    }

    fun load(uri: Uri) {
        token++
        val current = token
        svg = null
        invalidate()
        executor.execute {
            val parsed = runCatching {
                context.contentResolver.openInputStream(uri)?.use { SVG.getFromInputStream(it) }
            }.getOrNull()
            post {
                if (current != token) return@post
                if (parsed == null) {
                    onLoaded?.invoke(0, 0)
                    return@post
                }
                svg = parsed
                docWidth = parsed.documentWidth.takeIf { it > 0f } ?: 512f
                docHeight = parsed.documentHeight.takeIf { it > 0f } ?: 512f
                fitToView()
                invalidate()
                onLoaded?.invoke(docWidth.toInt(), docHeight.toInt())
            }
        }
    }

    fun fitToView() {
        if (docWidth <= 0f || width == 0) return
        // 标称尺寸先铺满视图, 之后再按倍数放大
        val fit = minOf(width * 0.8f / docWidth, height * 0.8f / docHeight)
        zoom = fit.coerceIn(1f, maxZoom)
        offsetX = (width - docWidth * zoom) / 2f
        offsetY = (height - docHeight * zoom) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) fitToView()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun constrainOffset() {
        val w = docWidth * zoom
        val h = docHeight * zoom
        offsetX = if (w <= width) (width - w) / 2f else offsetX.coerceIn(width - w, 0f)
        offsetY = if (h <= height) (height - h) / 2f else offsetY.coerceIn(height - h, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val vector = svg ?: return
        canvas.save()
        // 裁剪到视口: 放大 64 倍时只让可见部分参与绘制
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(offsetX, offsetY)
        canvas.scale(zoom, zoom)
        dest.set(0f, 0f, docWidth, docHeight)
        runCatching { vector.renderToCanvas(canvas, dest) }
        canvas.restore()
    }

    fun release() {
        token++
        svg = null
        executor.shutdownNow()
    }
}
