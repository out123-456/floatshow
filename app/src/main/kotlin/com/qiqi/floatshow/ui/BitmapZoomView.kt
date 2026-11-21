package com.qiqi.floatshow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors

/**
 * 位图查看器: 整图只解压一次, 之后所有缩放拖动都是画一张位图.
 *
 * 为什么不再用瓦片: PNG 的扫描行是顺序 zlib 压缩的, 每行 filter 还依赖上一行,
 * 所以"只解一块区域"在 PNG 上做不到 —— 每次 decodeRegion 都要从文件头一直 inflate 到那行.
 * 8192 图拖到下半部分时, 这个代价是整图的几倍, 这才是"慢"的真正原因.
 *
 * 唯一能避免重复 inflate 的办法, 就是把解压结果留在某处复用: 内存、或磁盘上的 raw.
 * 这里选内存: 打开时解一次, 之后每一帧都是单张位图绘制, 恒定开销.
 * 打开过程分两步 —— 先降采样出整图轮廓, 再补上原始像素, 所以不会白屏等几秒.
 */
class BitmapZoomView(context: Context) : View(context) {

    /** 显示比例: 1 表示 1 原图像素 = 1 屏幕像素 */
    var scale = 1f
        private set
    var minScale = 0.05f
        private set
    var maxScale = 16f
        private set

    private var imageWidth = 0
    private var imageHeight = 0
    private var offsetX = 0f
    private var offsetY = 0f

    /** 低精度的整图, 解码完成前和整图失败时都用它兜底 */
    private var overview: Bitmap? = null
    /** 原始像素的整图; PNG 已在后台 inflate 完, 之后直接 blit */
    private var full: Bitmap? = null

    private var uri: Uri? = null
    private var token = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val executor = Executors.newSingleThreadExecutor()

    /** 加载完成回调: 原图宽高 */
    var onLoaded: ((Int, Int) -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                if (target == scale) return true
                // 以双指焦点为中心缩放, 视觉上不动
                val fx = detector.focusX
                val fy = detector.focusY
                val ratio = target / scale
                offsetX = fx - (fx - offsetX) * ratio
                offsetY = fy - (fy - offsetY) * ratio
                scale = target
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

    fun load(uri: Uri) {
        this.uri = uri
        token++
        val current = token
        recycleBitmaps()
        imageWidth = 0
        imageHeight = 0
        invalidate()

        // 视图可能还没测量, 用屏幕尺寸兜底
        val targetW = if (width > 0) width else resources.displayMetrics.widthPixels
        val targetH = if (height > 0) height else resources.displayMetrics.heightPixels

        executor.execute {
            // 1. 只读文件头拿尺寸, 立刻能摆好位置
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                post { if (current == token) onLoaded?.invoke(0, 0) }
                return@execute
            }
            post {
                if (current != token) return@post
                imageWidth = w
                imageHeight = h
                fitToView()
                invalidate()
                onLoaded?.invoke(w, h)
            }

            // 2. 降采样出整图轮廓: 几百毫秒内先让用户看见整张图
            val small = decodeSampled(uri, w, h, targetW, targetH)
            post {
                if (current != token) {
                    small?.recycle()
                    return@post
                }
                overview = small
                invalidate()
            }

            // 3. 整图原始像素: PNG 的一次性 inflate, 之后不再解压
            val big = decodeFull(uri, w, h)
            post {
                if (current != token) {
                    big?.recycle()
                    return@post
                }
                full = big
                invalidate()
            }
        }
    }

    /** 目标尺寸的降采样整图; 只做一次 */
    private fun decodeSampled(uri: Uri, w: Int, h: Int, targetW: Int, targetH: Int): Bitmap? =
        runCatching {
            var sample = 1
            while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()

    /**
     * 整图原始像素. 先按 ARGB_8888 要最高质量, 内存不够时退到 RGB_565(体积只有一半).
     * 两个都失败就只剩降采样图, 缩放会糊, 但不会崩.
     */
    private fun decodeFull(uri: Uri, w: Int, h: Int): Bitmap? {
        for (config in CONFIGS) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = config
                inMutable = false
            }
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

    /** 让整张图刚好铺满视图, 作为初始比例 */
    fun fitToView() {
        if (imageWidth <= 0 || imageHeight <= 0 || width == 0 || height == 0) return
        scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        minScale = scale * 0.5f
        offsetX = (width - imageWidth * scale) / 2f
        offsetY = (height - imageHeight * scale) / 2f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (imageWidth > 0 && oldw == 0 && oldh == 0) fitToView()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) constrainOffset()
        return true
    }

    /** 图比视图小时居中, 比视图大时不许拖出空白 */
    private fun constrainOffset() {
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        offsetX = if (scaledW <= width) {
            if (scaledW <= 0f) 0f else (width - scaledW) / 2f
        } else {
            offsetX.coerceIn(width - scaledW, 0f)
        }
        offsetY = if (scaledH <= height) {
            if (scaledH <= 0f) 0f else (height - scaledH) / 2f
        } else {
            offsetY.coerceIn(height - scaledH, 0f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (imageWidth <= 0 || imageHeight <= 0 || scale <= 0f) return
        // 整图优先; 还没解出来就用降采样顶替, 放大时会糊, 但位置与缩放都是对的
        val bitmap = full ?: overview ?: return
        if (bitmap.isRecycled) return
        dst.set(
            offsetX,
            offsetY,
            offsetX + imageWidth * scale,
            offsetY + imageHeight * scale
        )
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    private fun recycleBitmaps() {
        full?.recycle()
        full = null
        overview?.recycle()
        overview = null
    }

    fun release() {
        token++
        recycleBitmaps()
        executor.shutdownNow()
    }

    companion object {
        private val CONFIGS = listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565)
    }
}
