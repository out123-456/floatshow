package com.qiqi.floatshow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
    /** 缩小到全图时用的整图降采样; 只在需要时存在 */
    private var overview: Bitmap? = null
    private var imageWidth = 0
    private var imageHeight = 0
    private var offsetX = 0f
    private var offsetY = 0f

    // 容量按条目数计: 32 块 512×512 ARGB = 约 32MB.
    // 被淘汰的块立刻回收: 33MB 级别的位图等 GC 等不到, 容易先 OOM
    private val tileCache = object : LruCache<TileKey, Bitmap>(MAX_TILES) {
        override fun entryRemoved(
            evicted: Boolean,
            key: TileKey,
            oldValue: Bitmap,
            newValue: Bitmap?
        ) {
            if (evicted && !oldValue.isRecycled) oldValue.recycle()
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val tileRectCache = HashMap<TileKey, Rect>()

    private val executor = Executors.newSingleThreadExecutor()
    private val pending = HashSet<TileKey>()
    private var token = 0
    private var uri: Uri? = null

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
        tileCache.evictAll()
        tileRectCache.clear()
        overview?.recycle()
        overview = null
        overviewRequested = false
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
        if (imageWidth <= 0 || scale <= 0f) return

        // 缩小成整个画面时, 逐块解码几百块瓦片毫无意义:
        // 此时每个屏幕像素只需要 1 个源像素的采样结果, 整图降采样一次就够.
        if (scale < OVERVIEW_THRESHOLD) {
            drawOverview(canvas)
            return
        }

        val decoder = decoder ?: return

        // 视口对应到原图上的区域
        val viewLeft = -offsetX / scale
        val viewTop = -offsetY / scale
        val viewRight = (width - offsetX) / scale
        val viewBottom = (height - offsetY) / scale

        val firstCol = (viewLeft / TILE).toInt().coerceAtLeast(0)
        val firstRow = (viewTop / TILE).toInt().coerceAtLeast(0)
        val lastCol = (viewRight / TILE).toInt().coerceAtMost((imageWidth - 1) / TILE)
        val lastRow = (viewBottom / TILE).toInt().coerceAtMost((imageHeight - 1) / TILE)

        // 缩小才插值; 1:1 与放大用最近邻, 边界像素完整, 不会因采样混出半透明缝
        paint.isFilterBitmap = scale < 1f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        // 只遍历视口附近的瓦片, 缺的提交后台解码.
        // 顺序从视口中心向外: 打开时用户先看中心, 中心先解出来观感最好.
        val centerCol = (firstCol + lastCol) / 2
        val centerRow = (firstRow + lastRow) / 2
        val order = buildList {
            for (row in firstRow..lastRow) for (col in firstCol..lastCol) add(col to row)
        }.sortedBy { (col, row) ->
            val dc = col - centerCol
            val dr = row - centerRow
            dc * dc + dr * dr
        }
        for ((col, row) in order) {
            val key = TileKey(col, row)
            val tile = tileCache.get(key)
            if (tile != null) {
                // 按解码时的扩边矩形绘制, 相邻瓦片互相覆盖 OVERLAP 像素, 边界上没有缝
                val bounds = tileRectCache.getOrPut(key) { tileBounds(key.col, key.row) }
                dst.set(bounds)
                canvas.drawBitmap(tile, null, dst, paint)
            } else {
                tileRectCache.remove(key)
                requestTile(decoder, key)
            }
        }
        canvas.restore()
    }

    /**
     * 整图降采样绘制. 目标边长按"整图刚好铺满屏幕"算, 再留一点余量给后续放大.
     * 只在后台解码一次, 之后这段比例范围内都复用它.
     */
    private fun drawOverview(canvas: Canvas) {
        val bmp = overview
        if (bmp == null || bmp.isRecycled) {
            if (!overviewRequested) requestOverview()
            return
        }
        dst.set(
            offsetX, offsetY,
            offsetX + imageWidth * scale,
            offsetY + imageHeight * scale
        )
        paint.isFilterBitmap = true
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    private var overviewRequested = false

    private fun requestOverview() {
        if (overviewRequested) return
        val source = uri ?: return
        overviewRequested = true
        val current = token
        // 目标是屏幕上能看到的像素数, 不必更大
        val targetW = width.coerceAtLeast(1)
        val targetH = height.coerceAtLeast(1)
        executor.execute {
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(source)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetW &&
                    bounds.outHeight / (sample * 2) >= targetH
                ) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(source)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
            post {
                if (current != token) {
                    bitmap?.recycle()
                    return@post
                }
                overview = bitmap
                invalidate()
            }
        }
    }

    private fun requestTile(decoder: BitmapRegionDecoder, key: TileKey) {
        if (!pending.add(key)) return
        val current = token
        val rect = tileBounds(key.col, key.row)
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

    /**
     * 一块瓦片实际解码/绘制的矩形: 向四周多取 OVERLAP 像素.
     * 绘制时用同一矩形, 相邻块的连接处就有一段重叠, 缝隙被覆盖.
     */
    private fun tileBounds(col: Int, row: Int): Rect {
        val left = (col * TILE - OVERLAP).coerceAtLeast(0)
        val top = (row * TILE - OVERLAP).coerceAtLeast(0)
        val right = ((col + 1) * TILE + OVERLAP).coerceAtMost(imageWidth)
        val bottom = ((row + 1) * TILE + OVERLAP).coerceAtMost(imageHeight)
        return Rect(left, top, right, bottom)
    }

    fun release() {
        token++
        tileRectCache.clear()
        tileCache.evictAll()
        overview?.recycle()
        overview = null
        decoder?.recycle()
        decoder = null
        executor.shutdownNow()
    }

    private data class TileKey(val col: Int, val row: Int)

    companion object {
        private const val TILE = 512
        private const val MAX_TILES = 32

        /** 瓦片之间的重叠像素, 用来彻底消除拼接缝 */
        private const val OVERLAP = 2

        /** 低于这个比例就整图降采样; 高于它才需要逐块瓦片 */
        private const val OVERVIEW_THRESHOLD = 0.5f
    }
}
