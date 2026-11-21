package com.qiqi.floatshow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.concurrent.Executors

/**
 * 高精度位图查看器: 原图不整张进内存, 按 512px 瓦片按需解码, 只保留视口附近的块.
 *
 * 这样 8192px 甚至更大的图也能按原始像素 1:1 显示, 放大时每个屏幕像素都有真实源像素,
 * 而不是把一张缩小图硬拉大. 抽屉里的缩略预览仍用降采样, 两者职责不同.
 */
class TileImageView(context: Context) : View(context) {

    /** 显示比例: 1 表示 1 原图像素 = 1 屏幕像素 */
    var scale = 1f
        private set
    var minScale = 0.05f
        private set
    var maxScale = 8f
        private set

    private var decoder: BitmapRegionDecoder? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var offsetX = 0f
    private var offsetY = 0f

    // 容量按条目数计: 32 块 512×512 ARGB = 约 32MB
    private val tileCache = LruCache<TileKey, Bitmap>(MAX_TILES)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = Rect()

    private val executor = Executors.newSingleThreadExecutor()
    private val pending = HashSet<TileKey>()
    private var token = 0

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
        token++
        val current = token
        tileCache.evictAll()
        decoder?.recycle()
        decoder = null
        imageWidth = 0
        imageHeight = 0
        invalidate()

        executor.execute {
            val opened = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapRegionDecoder.newInstance(stream, false)
                }
            }.getOrNull()
            post {
                if (current != token) {
                    opened?.recycle()
                    return@post
                }
                decoder = opened
                if (opened == null) {
                    onLoaded?.invoke(0, 0)
                    invalidate()
                    return@post
                }
                imageWidth = opened.width
                imageHeight = opened.height
                fitToView()
                invalidate()
                onLoaded?.invoke(imageWidth, imageHeight)
            }
        }
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
        val decoder = decoder ?: return
        if (imageWidth <= 0 || scale <= 0f) return

        // 视口对应到原图上的区域
        val viewLeft = -offsetX / scale
        val viewTop = -offsetY / scale
        val viewRight = (width - offsetX) / scale
        val viewBottom = (height - offsetY) / scale

        val firstCol = (viewLeft / TILE).toInt().coerceAtLeast(0)
        val firstRow = (viewTop / TILE).toInt().coerceAtLeast(0)
        val lastCol = (viewRight / TILE).toInt().coerceAtMost((imageWidth - 1) / TILE)
        val lastRow = (viewBottom / TILE).toInt().coerceAtMost((imageHeight - 1) / TILE)

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // 只遍历视口附近的瓦片, 缺的提交后台解码
        for (row in firstRow..lastRow) {
            for (col in firstCol..lastCol) {
                val key = TileKey(col, row)
                val tile = tileCache.get(key)
                if (tile != null) {
                    val tileW = tile.width
                    val tileH = tile.height
                    src.set(0, 0, tileW, tileH)
                    dst.set(col * TILE, row * TILE, col * TILE + tileW, row * TILE + tileH)
                    canvas.drawBitmap(tile, src, dst, paint)
                } else {
                    requestTile(decoder, key)
                }
            }
        }
        canvas.restore()
    }

    private fun requestTile(decoder: BitmapRegionDecoder, key: TileKey) {
        if (!pending.add(key)) return
        val current = token
        val rect = Rect(
            key.col * TILE,
            key.row * TILE,
            minOf((key.col + 1) * TILE, imageWidth),
            minOf((key.row + 1) * TILE, imageHeight)
        )
        executor.execute {
            val bitmap = runCatching { decoder.decodeRegion(rect, null) }.getOrNull()
            post {
                pending.remove(key)
                if (current != token) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap != null) {
                    tileCache.put(key, bitmap)
                    invalidate()
                }
            }
        }
    }

    fun release() {
        token++
        tileCache.evictAll()
        decoder?.recycle()
        decoder = null
        executor.shutdownNow()
    }

    private data class TileKey(val col: Int, val row: Int)

    companion object {
        private const val TILE = 512
        private const val MAX_TILES = 32
    }
}
