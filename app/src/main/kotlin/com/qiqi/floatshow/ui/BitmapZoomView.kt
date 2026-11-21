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
    /** overview 是不是自己解出来的; 借来的那张不能 recycle */
    private var overviewOwned = false
    private var full: Bitmap? = null
    private var reader: RawReader? = null

    // 视口窗口: 窗口位图对应原图上 (windowX, windowY) 起的一块像素
    private var window: Bitmap? = null
    private var windowX = 0
    private var windowY = 0
    private var windowW = 0
    private var windowH = 0
    private var wantFill: IntArray? = null
    private var fillRunning = false
    /** 窗口里的像素是否与当前 windowX/windowY 对应; 重填期间为 false */
    private var windowReady = false

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
            val opened = if (rawFile.isFile && rawFile.length() > RawImage.HEADER) {
                runCatching { RawReader(rawFile) }.getOrNull()
            } else {
                null
            }
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
            }

            // 3. 降采样整图. 这一步任何来源都要做: 缩到全图时只画它.
            //    (raw 也一样 —— raw 只有按视口读小块的能力, 没有整图缩略)
            //    抽屉预览已经解过同规格的一张就直接借用, 省掉一次全图 inflate
            val cached = OverviewCache.peek(uri.toString())
            val small = cached?.first ?: decodeSampled(uri, w, h)
            post {
                if (current != token) {
                    if (cached == null) small?.recycle()
                    return@post
                }
                overviewOwned = cached == null
                overview = small
                invalidate()
            }

            // 4. 只有小图且没有 raw 时才整图进内存; 大图没转 raw 就停在降采样图, 不冒险
            if (opened == null && w.toLong() * h <= FULL_PIXEL_LIMIT) {
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

    private fun decodeSampled(uri: Uri, w: Int, h: Int): Bitmap? =
        runCatching {
            // 与抽屉预览同规格: 长边 1024. 全图缩放在这个清晰度下够用
            var sample = 1
            while (max(w, h) / (sample * 2) >= OVERVIEW_SIDE) sample *= 2
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
        // 窗口 = 视口 + 每边 PAD. 用固定余量而不是倍数: 1:1 时视口就有整个屏幕那么大,
        // 再按倍数放大窗口等于每次重填多读一倍多的数据, 拖动会明显拖沓.
        val needW = min(imageWidth, ceil(viewW).toInt() + PAD * 2)
        val needH = min(imageHeight, ceil(viewH).toInt() + PAD * 2)
        if (needW > MAX_WINDOW || needH > MAX_WINDOW) {
            // 极端的细长图: 一次搬不下, 先用降采样整图顶着
            drawWhole(canvas, overview ?: full)
            return
        }
        val coverW = needW
        val coverH = needH

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
            windowX = (viewX - PAD).toInt().coerceIn(0, max(0, imageWidth - coverW))
            windowY = (viewY - PAD).toInt().coerceIn(0, max(0, imageHeight - coverH))
            windowReady = false
            requestFill(windowX, windowY, coverW, coverH)
        }

        val bmp = window
        if (bmp == null || bmp.isRecycled || !windowReady) {
            // 窗口还没解码出来时先用降采样整图顶着, 免得白屏
            drawWhole(canvas, overview ?: full)
            return
        }
        dst.set(
            offsetX + windowX * scale,
            offsetY + windowY * scale,
            offsetX + (windowX + windowW) * scale,
            offsetY + (windowY + windowH) * scale
        )
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    /**
     * 请求填充窗口. 只保留最新一次请求: 拖动时视口连续变化, 排队等旧请求会让画面停在错位的内容上.
     */
    private fun requestFill(x: Int, y: Int, w: Int, h: Int) {
        wantFill = intArrayOf(x, y, w, h)
        if (fillRunning) return
        fillRunning = true
        pumpFill()
    }

    private fun pumpFill() {
        val want = wantFill
        if (want == null) {
            fillRunning = false
            return
        }
        wantFill = null
        val source = reader
        if (source == null) {
            fillRunning = false
            return
        }
        val current = token
        val (fx, fy, fw, fh) = want
        executor.execute {
            val pixels = runCatching {
                IntArray(fw * fh).also { source.readRows(fy, fh, it) }
            }.getOrNull()
            post {
                if (current != token) {
                    fillRunning = false
                    return@post
                }
                val target = window
                // 只有窗口还是同一块(尺寸与起点都没变)才写回去, 否则这批像素已经过期
                if (pixels != null && target != null && !target.isRecycled &&
                    target.width == fw && target.height == fh &&
                    windowX == fx && windowY == fy
                ) {
                    target.setPixels(pixels, 0, fw, 0, 0, fw, fh)
                    windowReady = true
                    invalidate()
                }
                pumpFill()
            }
        }
    }

    private fun releaseData() {
        if (overviewOwned) overview?.recycle()
        overview = null
        overviewOwned = false
        full?.recycle()
        full = null
        reader?.close()
        reader = null
        window?.recycle()
        window = null
        windowW = 0
        windowH = 0
        wantFill = null
        windowReady = false
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

        /** 视口覆盖超过这么多像素(即缩得很小)时才用降采样整图, 约 32MB 一帧 */
        private const val WINDOW_PIXEL_LIMIT = 8L * 1024L * 1024L

        /** 降采样整图的长边, 与抽屉预览一致 */
        private const val OVERVIEW_SIDE = 1024

        /** 窗口比视口每边多出的像素, 拖动在这个范围内不重填 */
        private const val PAD = 128

        /** 窗口单个边长上限, 超过就退回降采样整图 */
        private const val MAX_WINDOW = 4096
    }
}
