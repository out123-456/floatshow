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
import com.qiqi.floatshow.data.RawImage
import com.qiqi.floatshow.data.RawReader
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 位图查看器. 三种数据来源, 按代价从低到高:
 *  · overview: 降采样整图, 缩放到全图时用它, 一张小位图, 开销恒定
 *  · full:     整图位图, 只在图不大(RawImage 之外的小图)时用
 *  · raw:      磁盘上的裸像素, 通过 mmap 只读当前视口附近的一块, 内存不随原图增长
 *
 * 大图推荐先转 raw, 否则打开时要整图 inflate, 8192 的 PNG 会占 200MB 以上.
 */
class BitmapZoomView(context: Context) : View(context) {

    /** 显示比例: 1 表示 1 原图像素 = 1 屏幕像素 */
    var scale = 1f
        private set
    var minScale = 0.05f
        private set
    var maxScale = 8f
        private set

    private var imageWidth = 0
    private var imageHeight = 0
    private var offsetX = 0f
    private var offsetY = 0f
    private var uri: Uri? = null
    private var token = 0

    private var overview: Bitmap? = null
    private var full: Bitmap? = null
    private var reader: RawReader? = null

    // 视口窗口: 窗口位图对应原图上 (windowX, windowY) 起的一块像素
    private var window: Bitmap? = null
    private var windowX = 0
    private var windowY = 0
    private var windowW = 0
    private var windowH = 0
    private var fillPending = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val executor = Executors.newSingleThreadExecutor()

    /** 加载完成回调: 原图宽高 */
    var onLoaded: ((Int, Int) -> Unit)? = null
    /** 当前是否在用 raw(界面据此决定提示) */
    var usingRaw = false
        private set

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
                if (target == scale) return true
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
        releaseData()
        imageWidth = 0
        imageHeight = 0
        invalidate()

        val targetW = if (width > 0) width else resources.displayMetrics.widthPixels
        val targetH = if (height > 0) height else resources.displayMetrics.heightPixels

        executor.execute {
            // 1. 读文件头拿尺寸, 立刻能把图摆正
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

            // 2. 有 raw 就直接用 raw, 完全不碰原图的压缩流
            val rawFile = RawImage.fileFor(context, uri)
            if (rawFile.isFile && rawFile.length() > RawImage.HEADER) {
                val opened = runCatching { RawReader(rawFile) }.getOrNull()
                if (opened != null) {
                    post {
                        if (current != token) {
                            opened.close()
                            return@post
                        }
                        reader = opened
                        usingRaw = true
                        invalidate()
                    }
                    return@execute
                }
            }

            // 3. 先降采样出整图轮廓, 让用户秒见全图
            val small = decodeSampled(uri, w, h, targetW, targetH)
            post {
                if (current != token) {
                    small?.recycle()
                    return@post
                }
                overview = small
                invalidate()
            }

            // 4. 小图才补原始像素; 大图没有 raw 就停在降采样图, 不冒险整图进内存
            if (w * h <= FULL_PIXEL_LIMIT) {
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
    }

    /** 转好 raw 后由外部调用, 立刻切过去, 不必重新打开界面 */
    fun reloadWithRaw() {
        val current = uri ?: return
        load(current)
    }

    private fun decodeSampled(uri: Uri, w: Int, h: Int, targetW: Int, targetH: Int): Bitmap? =
        runCatching {
            var sample = 1
            while (w / (sample * 2) >= targetW && h / (sample * 2) >= targetH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()

    private fun decodeFull(uri: Uri, w: Int, h: Int): Bitmap? {
        for (config in listOf(Bitmap.Config.ARGB_8888, Bitmap.Config.RGB_565)) {
            val options = BitmapFactory.Options().apply { inPreferredConfig = config }
            val bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
            if (bitmap != null) return bitmap
        }
        return null
    }

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

        // 视口对应的原图区域(浮点)
        val viewW = width / scale
        val viewH = height / scale
        val viewX = -offsetX / scale
        val viewY = -offsetY / scale

        // 视口覆盖面积太大时(缩放到全图)没必要搬原始像素, 直接用降采样整图
        if (viewW.toLong() * viewH.toLong() > WINDOW_PIXEL_LIMIT) {
            drawWhole(canvas, overview ?: full)
            return
        }

        if (reader != null) {
            drawFromRaw(canvas, viewX, viewY, viewW, viewH)
            return
        }
        drawWhole(canvas, full ?: overview)
    }

    /** 整张位图铺到当前变换上 */
    private fun drawWhole(canvas: Canvas, bitmap: Bitmap?) {
        val bmp = bitmap ?: return
        if (bmp.isRecycled) return
        dst.set(
            offsetX, offsetY,
            offsetX + imageWidth * scale,
            offsetY + imageHeight * scale
        )
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    /**
     * 从 raw 里搬视口附近的一块到窗口位图, 再画上去.
     * 窗口比视口大一圈, 所以拖动时多数帧都不需要重填; 只有视口移出窗口才读一次磁盘.
     */
    private fun drawFromRaw(canvas: Canvas, viewX: Float, viewY: Float, viewW: Float, viewH: Float) {
        val coverW = min(imageWidth, max(1, ceil(viewW * WINDOW_GROW).toInt()))
        val coverH = min(imageHeight, max(1, ceil(viewH * WINDOW_GROW).toInt()))

        val covered = window != null && !window!!.isRecycled &&
            viewX >= windowX && viewY >= windowY &&
            viewX + viewW <= windowX + windowW &&
            viewY + viewH <= windowY + windowH

        if (!covered) {
            if (window == null || window!!.width != coverW || window!!.height != coverH) {
                window?.recycle()
                window = Bitmap.createBitmap(coverW, coverH, Bitmap.Config.ARGB_8888)
                windowW = coverW
                windowH = coverH
            }
            // 窗口居中在视口上, 再夹进图内
            windowX = (viewX - (coverW - viewW) / 2f).toInt()
                .coerceIn(0, max(0, imageWidth - coverW))
            windowY = (viewY - (coverH - viewH) / 2f).toInt()
                .coerceIn(0, max(0, imageHeight - coverH))
            requestFill(windowX, windowY, coverW, coverH)
        }

        val bmp = window ?: return
        if (bmp.isRecycled) return
        dst.set(
            offsetX + windowX * scale,
            offsetY + windowY * scale,
            offsetX + (windowX + windowW) * scale,
            offsetY + (windowY + windowH) * scale
        )
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private fun requestFill(x: Int, y: Int, w: Int, h: Int) {
        if (fillPending) return
        val source = reader ?: return
        fillPending = true
        val current = token
        executor.execute {
            val pixels = runCatching {
                val buffer = IntArray(w * h)
                source.readRows(y, h, buffer)
                buffer
            }.getOrNull()
            post {
                fillPending = false
                if (current != token) return@post
                val target = window
                if (pixels != null && target != null && !target.isRecycled &&
                    target.width == w && target.height == h
                ) {
                    target.setPixels(pixels, 0, w, 0, 0, w, h)
                    invalidate()
                }
            }
        }
    }

    private fun releaseData() {
        overview?.recycle()
        overview = null
        full?.recycle()
        full = null
        reader?.close()
        reader = null
        window?.recycle()
        window = null
        windowW = 0
        windowH = 0
        usingRaw = false
    }

    fun release() {
        token++
        releaseData()
        executor.shutdownNow()
    }

    companion object {
        /** 超过这么多像素就不再整图进内存(约 64MB) */
        private const val FULL_PIXEL_LIMIT = 4096 * 4096

        /** 视口覆盖超过这么多像素时改用降采样整图 */
        private const val WINDOW_PIXEL_LIMIT = 4096L * 4096L

        /** 窗口比视口大多少, 越大重填越少, 内存也越大 */
        private const val WINDOW_GROW = 1.5f
    }
}
