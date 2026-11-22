package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.qiqi.floatshow.data.PanelLayer
import com.qiqi.floatshow.data.PanelLayerKind
import com.qiqi.floatshow.data.PanelLayers
import com.qiqi.floatshow.data.PanelTextMode
import com.qiqi.floatshow.data.RawImage
import com.qiqi.floatshow.data.RawReader
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * 面板内容区: 把层按顺序画出来.
 *
 * 坐标系: 所有层的 [PanelLayer.x]/[PanelLayer.y] 都以面板左上角为原点, 单位是屏幕像素.
 * 内容整体还有一层偏移与缩放(长按拖动移动、双指缩放), 相当于"画布"的相机.
 *
 * 位图的像素来源优先 raw: 面板只有屏幕那么大, 需要的像素量很有限, 从 mmap 里读一块就够,
 * 不必为了显示局部而把 8192 的 PNG 整张解压.
 */
class PanelContentView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    private var layers: List<PanelLayer> = emptyList()

    /** 内容画布的相机: 偏移(像素)与缩放 */
    private var contentOffsetX = 0f
    private var contentOffsetY = 0f
    private var contentScale = 1f

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
    }
    private val dst = RectF()

    /** 已打开的 raw 读取器, 按 uri 复用; 面板关掉时统一关 */
    private val readers = HashMap<String, RawReader>()
    private val textLayouts = HashMap<String, Pair<StaticLayout, Int>>()

    private val executor = Executors.newSingleThreadExecutor()
    private var token = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (contentScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                if (target == contentScale) return true
                // 以双指焦点为中心缩放, 内容在指下不动
                val fx = detector.focusX
                val fy = detector.focusY
                val ratio = target / contentScale
                contentOffsetX = fx - (fx - contentOffsetX) * ratio
                contentOffsetY = fy - (fy - contentOffsetY) * ratio
                contentScale = target
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
                contentOffsetX -= dx
                contentOffsetY -= dy
                invalidate()
                return true
            }
        }
    )

    /** 把内容相机恢复到左上角 1:1 */
    fun resetCamera() {
        contentOffsetX = 0f
        contentOffsetY = 0f
        contentScale = 1f
        invalidate()
    }

    fun setLayers(next: List<PanelLayer>) {
        val before = layers
        if (before == next) return
        layers = next
        // 内容变了, 丢掉旧的解码结果(位图与文本排版都按内容区分)
        dropCaches(before, next)
        invalidate()
    }

    /** 手指落在固定层上时整段手势都不响应 */
    private var ignoreGesture = false

    /** 面板尺寸变化时不需要做任何事: 内容坐标是绝对的, 不随视窗拉伸 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            ignoreGesture = hitFixedLayer(event.x, event.y)
        }
        if (ignoreGesture) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) ignoreGesture = false
            return true
        }
        // 双指缩放优先级高于拖动
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            scaleDetector.onTouchEvent(event)
        }
        return true
    }

    /** 屏幕点是否落在某个固定/锚定层的矩形里(按当前相机反变换) */
    private fun hitFixedLayer(screenX: Float, screenY: Float): Boolean {
        val fixedLayers = layers.filter { it.fixed }
        if (fixedLayers.isEmpty()) return false
        val inverse = Matrix()
        val forward = Matrix()
        forward.postTranslate(contentOffsetX, contentOffsetY)
        forward.postScale(contentScale, contentScale)
        if (!forward.invert(inverse)) return false
        val p = floatArrayOf(screenX, screenY)
        inverse.mapPoints(p)
        val x = p[0]
        val y = p[1]
        return fixedLayers.any { layer ->
            val w = layerBoxWidth(layer)
            val h = layerBoxHeight(layer)
            x >= layer.x && y >= layer.y && x <= layer.x + w && y <= layer.y + h
        }
    }

    /** 层在面板里的宽高(取不到天然尺寸就按 0, 当作不可点) */
    private fun layerSize(layer: PanelLayer): Pair<Float, Float> {
        if (layer.width > 0 && layer.height > 0) {
            return layer.width.toFloat() to layer.height.toFloat()
        }
        return naturalSizes[layer.content] ?: (0f to 0f)
    }

    /** 旋转 90/270 后包围盒的宽高互换 */
    private fun layerBoxWidth(layer: PanelLayer): Float {
        val (w, h) = layerSize(layer)
        return if (layer.rotation % 2 == 1) h else w
    }

    private fun layerBoxHeight(layer: PanelLayer): Float {
        val (w, h) = layerSize(layer)
        return if (layer.rotation % 2 == 1) w else h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val order = PanelLayers.renderOrder(layers)
        canvas.save()
        canvas.translate(contentOffsetX, contentOffsetY)
        canvas.scale(contentScale, contentScale)
        for (layer in order) {
            when (layer.kind) {
                PanelLayerKind.IMAGE -> drawImage(canvas, layer)
                PanelLayerKind.TEXT -> drawText(canvas, layer)
            }
        }
        canvas.restore()
        reportVisibility(canvas)
    }

    /**
     * 层在屏幕上的包围盒完全落在视窗之外时, 记进 [PanelVisibility], 让主界面把种类图标标红.
     * 这是"用户自己把内容拖丢了"的唯一提示.
     */
    private fun reportVisibility(canvas: Canvas) {
        val visible = HashSet<String>()
        val m = Matrix()
        canvas.getMatrix(m)
        val pts = FloatArray(8)
        for (layer in layers) {
            val w = layerBoxWidth(layer)
            val h = layerBoxHeight(layer)
            if (w <= 0f || h <= 0f) continue
            val lx = layer.x.toFloat()
            val ly = layer.y.toFloat()
            pts[0] = lx; pts[1] = ly
            pts[2] = lx + w; pts[3] = ly
            pts[4] = lx; pts[5] = ly + h
            pts[6] = lx + w; pts[7] = ly + h
            m.mapPoints(pts)
            val minX = minOf(pts[0], pts[2], pts[4], pts[6])
            val maxX = maxOf(pts[0], pts[2], pts[4], pts[6])
            val minY = minOf(pts[1], pts[3], pts[5], pts[7])
            val maxY = maxOf(pts[1], pts[3], pts[5], pts[7])
            if (maxX > 0f && minX < getWidth() && maxY > 0f && minY < getHeight()) {
                visible += layer.id
            }
        }
        val invisible = layers.map { it.id }.toSet() - visible
        PanelVisibility.set(invisible)
    }

    // ── 位图 ────────────────────────────────────────────────

    private fun drawImage(canvas: Canvas, layer: PanelLayer) {
        val uri = runCatching { Uri.parse(layer.content) }.getOrNull() ?: return
        val natural = naturalSize(layer, uri) ?: return
        val w = if (layer.width > 0) layer.width.toFloat() else natural.first
        val h = if (layer.height > 0) layer.height.toFloat() else natural.second
        if (w <= 0f || h <= 0f) return

        canvas.save()
        // 以层矩形的中心为变换原点: 旋转与镜像都不会把内容甩到别处
        canvas.translate(layer.x + w / 2f, layer.y + h / 2f)
        canvas.rotate(layer.rotation.toFloat() * 90f)
        if (layer.mirrored) canvas.scale(-1f, 1f)
        canvas.translate(-w / 2f, -h / 2f)

        if (PanelContentSource.isSvg(context, uri)) {
            val picture = svgPicture(layer, uri)
            if (picture != null) canvas.drawPicture(picture, RectF(0f, 0f, w, h))
            canvas.restore()
            return
        }

        // 把屏幕视口反变换回"层的本地坐标", 得到这一帧真正要画的那一块
        val screenRect = RectF(0f, 0f, getWidth().toFloat(), getHeight().toFloat())
        val inverse = Matrix()
        if (!canvas.matrix.invert(inverse)) {
            canvas.restore()
            return
        }
        val local = RectF()
        inverse.mapRect(local, screenRect)
        val clipped = RectF(0f, 0f, w, h)
        if (!clipped.intersect(local) || clipped.isEmpty) {
            canvas.restore()
            return
        }

        // 落到原图坐标
        val scaleX = natural.first / w
        val scaleY = natural.second / h
        val srcRect = Rect(
            (clipped.left * scaleX).toInt().coerceIn(0, natural.first.toInt() - 1),
            (clipped.top * scaleY).toInt().coerceIn(0, natural.second.toInt() - 1),
            (clipped.right * scaleX).toInt().coerceIn(1, natural.first.toInt()),
            (clipped.bottom * scaleY).toInt().coerceIn(1, natural.second.toInt())
        )
        if (srcRect.width() <= 0 || srcRect.height() <= 0) {
            canvas.restore()
            return
        }

        // 采样步长按"这块在屏幕上占多少像素"决定
        val pixels = canvas.matrix.mapRadius(1f)
        val targetW = (srcRect.width() * pixels).toInt().coerceAtLeast(1)
        val step = (srcRect.width() / targetW).coerceAtLeast(1)

        // 取一块比 srcRect 更大的窗口(并按 WINDOW 对齐), 平移时就不会每帧重新解码.
        // 之前直接按 srcRect 做缓存键, 内容一碰到视窗边界, 键每帧都在变, 就"刷新个没完"
        val win = windowFor(srcRect, step, natural)
        val entry = imageWindow(layer, uri, win, step)
        val fallback = lastEntry[layer.id]
        val use = entry ?: fallback
        if (use == null || use.bitmap.isRecycled) {
            canvas.restore()
            return
        }
        // 把 srcRect 在窗口里的位置换算成位图里的取样矩形
        val inBmp = Rect(
            (srcRect.left - win.left) / step,
            (srcRect.top - win.top) / step,
            ((srcRect.right - win.left) / step).coerceAtLeast((srcRect.left - win.left) / step + 1),
            ((srcRect.bottom - win.top) / step).coerceAtLeast((srcRect.top - win.top) / step + 1)
        )
        imagePaint.isFilterBitmap = true
        canvas.drawBitmap(use.bitmap, inBmp, clipped, imagePaint)
        canvas.restore()
    }

    /** 缓存里按层记一块已解好的像素, 换窗口前先用它顶着, 不闪 */
    private val lastEntry = HashMap<String, ImageEntry>()

    /**
     * 把 srcRect 向外扩一点, 并把左上角对齐到 [WINDOW] 的倍数.
     * 这样内容在窗口内平移时键不变, 只有移出半个窗口才重新解码.
     */
    private fun windowFor(src: Rect, step: Int, natural: Pair<Float, Float>): Rect {
        val maxW = natural.first.toInt()
        val maxH = natural.second.toInt()
        val pad = WINDOW / 2
        val left = ((src.left - pad).coerceAtLeast(0) / WINDOW) * WINDOW
        val top = ((src.top - pad).coerceAtLeast(0) / WINDOW) * WINDOW
        val right = ((src.right + pad).coerceAtMost(maxW) / WINDOW + 1) * WINDOW
        val bottom = ((src.bottom + pad).coerceAtMost(maxH) / WINDOW + 1) * WINDOW
        return Rect(
            left,
            top,
            right.coerceIn(left + WINDOW, maxW),
            bottom.coerceIn(top + WINDOW, maxH)
        )
    }

    /** 层的天然尺寸: 位图按原始像素, SVG 按 100dp 的一边 */
    private fun naturalSize(layer: PanelLayer, uri: Uri): Pair<Float, Float>? {
        val known = naturalSizes[layer.content]
        if (known != null) return known
        val t = when (layer.kind) {
            PanelLayerKind.IMAGE -> {
                if (PanelContentSource.isSvg(context, uri)) {
                    val side = 100 * density
                    side to side
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, bounds)
                        }
                    }
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        bounds.outWidth.toFloat() to bounds.outHeight.toFloat()
                    } else {
                        null
                    }
                }
            }
            else -> null
        }
        if (t != null) naturalSizes[layer.content] = t
        return t
    }

    private val naturalSizes = HashMap<String, Pair<Float, Float>>()

    /** SVG 预渲染成 Picture, 矢量放缩不糊 */
    private fun svgPicture(layer: PanelLayer, uri: Uri): Picture? {
        svgPictures[layer.content]?.let { return it }
        val doc = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                com.caverock.androidsvg.SVG.getFromInputStream(it)
            }
        }.getOrNull() ?: return null
        val side = 1024
        val pic = runCatching {
            doc.setDocumentWidth(side.toFloat())
            doc.setDocumentHeight(side.toFloat())
            doc.renderToPicture(side, side)
        }.getOrNull() ?: return null
        svgPictures[layer.content] = pic
        return pic
    }

    private val svgPictures = HashMap<String, Picture>()

    /** 一块已经解好的像素, 以及在位图里的取样矩形 */
    private class ImageEntry(val bitmap: Bitmap, val src: RectF)

    /**
     * 取这一层要画的那块像素. 面板视窗有限, 所以永远只读"需要的那一块".
     * 有 raw 走 mmap 按步长采样读区; 没有的话, 按目标尺寸降采样解码.
     */
    private fun imageWindow(
        layer: PanelLayer,
        uri: Uri,
        srcRect: Rect,
        step: Int
    ): ImageEntry? {
        val key = layer.content + "|" + srcRect.left + "," + srcRect.top + "," +
            srcRect.width() + "," + srcRect.height() + "|" + step
        imageCache.get(key)?.let {
            if (!it.bitmap.isRecycled) {
                lastEntry[layer.id] = it
                return it
            }
        }
        if (pendingImage.add(key)) {
            val current = token
            executor.execute {
                val (bmp, outW, outH) = PanelContentSource.loadRegion(
                    context, uri, srcRect, step
                )
                post {
                    pendingImage.remove(key)
                    if (current != token) {
                        bmp?.recycle()
                        return@post
                    }
                    if (bmp != null && outW > 0 && outH > 0) {
                        val entry = ImageEntry(bmp, RectF(0f, 0f, outW.toFloat(), outH.toFloat()))
                        imageCache.put(key, entry)
                        lastEntry[layer.id] = entry
                        invalidate()
                    }
                }
            }
        }
        return null
    }

    private val pendingImage = HashSet<String>()
    private val imageCache = object : LruCache<String, ImageEntry>(8) {
        override fun entryRemoved(
            evicted: Boolean, key: String, oldValue: ImageEntry, newValue: ImageEntry?
        ) {
            if (!oldValue.bitmap.isRecycled) oldValue.bitmap.recycle()
        }
    }

    // ── 文本 ────────────────────────────────────────────────

    private fun drawText(canvas: Canvas, layer: PanelLayer) {
        val layout = layoutFor(layer) ?: return
        canvas.save()
        canvas.translate(layer.x.toFloat(), layer.y.toFloat())
        canvas.rotate(layer.rotation.toFloat() * 90f)
        if (layer.mirrored) canvas.scale(-1f, 1f, layout.width / 2f, layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun layoutFor(layer: PanelLayer): StaticLayout? {
        val key = layer.id + ":" + layer.textMode + ":" + (layer.width) + ":" + layer.content.hashCode()
        textLayouts[key]?.let { return it.first }
        val width = if (layer.width > 0) layer.width else (this.width.takeIf { it > 0 } ?: 300)
        val text: CharSequence = if (layer.textMode == PanelTextMode.MARKDOWN) {
            MarkdownLite.render(layer.content, textPaint.textSize)
        } else {
            layer.content
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .build()
        textLayouts[key] = layout to width
        return layout
    }

    private fun dropCaches(before: List<PanelLayer>, after: List<PanelLayer>) {
        if (before == after) return
        imageCache.evictAll()
        textLayouts.clear()
        // 不再被任何层引用的 raw 就不该继续开着
        val keep = after.map { it.content }.toSet()
        val stale = readers.keys.filter { it !in keep }
        stale.forEach { key -> readers.remove(key)?.close() }
        pendingImage.clear()
    }

    fun release() {
        token++
        imageCache.evictAll()
        textLayouts.clear()
        readers.values.forEach { it.close() }
        readers.clear()
        executor.shutdownNow()
    }

    companion object {
        private const val MIN_SCALE = 0.1f
        private const val MAX_SCALE = 32f

        /** 位图窗口粒度(原图像素). 越大重解码越少, 每次读的量也越大 */
        private const val WINDOW = 256
    }
}

/**
 * 层内容的像素来源. 面板只需要"这一块", 所以永远按目标矩形取, 不整图进内存.
 */
object PanelContentSource {

    fun isSvg(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime == "image/svg+xml") return true
        return uri.lastPathSegment?.endsWith(".svg", ignoreCase = true) == true
    }

    /**
     * 读 [src] 这块像素, 按 [step] 跳采样.
     * 有 raw 走 mmap; 没有则按需要的那块解码(小图直接整张, 大图只能降采样).
     * 返回 (位图, 宽, 高). 位图可能比 src 小(采样了), 画的时候拉伸回层矩形.
     */
    fun loadRegion(
        context: Context,
        uri: Uri,
        src: Rect,
        step: Int
    ): Triple<Bitmap?, Int, Int> {
        val raw = RawImage.fileFor(context, uri)
        if (raw.isFile && raw.length() > RawImage.HEADER) {
            val reader = runCatching { RawReader(raw) }.getOrNull()
                ?: return Triple(null, 0, 0)
            return try {
                val w = min(src.width(), reader.width - src.left).coerceAtLeast(1)
                val h = min(src.height(), reader.height - src.top).coerceAtLeast(1)
                val outW = (w + step - 1) / step
                val outH = (h + step - 1) / step
                val pixels = IntArray(outW * outH)
                reader.readRegionScaled(src.left, src.top, w, h, step, pixels)
                Triple(
                    Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888),
                    outW,
                    outH
                )
            } catch (e: Throwable) {
                Triple(null, 0, 0)
            } finally {
                reader.close()
            }
        }

        // 没有 raw: 只能整图解码一次再裁. 图越大越吃亏, 这正是要转 raw 的理由
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Triple(null, 0, 0)
        val options = BitmapFactory.Options()
        // 只解需要的分辨率: 目标就是 src 采样后的尺寸
        var sample = 1
        val outW = (src.width() + step - 1) / step
        while (bounds.outWidth / (sample * 2) >= outW && sample * 2 < bounds.outWidth) sample *= 2
        options.inSampleSize = sample
        val full = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull() ?: return Triple(null, 0, 0)
        val ratioX = full.width.toFloat() / bounds.outWidth
        val ratioY = full.height.toFloat() / bounds.outHeight
        val crop = Rect(
            (src.left * ratioX).toInt().coerceIn(0, full.width - 1),
            (src.top * ratioY).toInt().coerceIn(0, full.height - 1),
            (src.right * ratioX).toInt().coerceIn(1, full.width),
            (src.bottom * ratioY).toInt().coerceIn(1, full.height)
        )
        if (crop.width() <= 0 || crop.height() <= 0) {
            full.recycle()
            return Triple(null, 0, 0)
        }
        val cropped = Bitmap.createBitmap(full, crop.left, crop.top, crop.width(), crop.height())
        if (cropped !== full) full.recycle()
        return Triple(cropped, cropped.width, cropped.height)
    }

    private const val MAX_SIDE = 2048
}

private fun RawReader.use(block: (RawReader) -> Bitmap?): Bitmap? = try {
    block(this)
} finally {
    close()
}

/** 极简 markdown: 标题、粗体、斜体、行内代码、无序列表. 不支持 HTML/LaTeX/表格. */
object MarkdownLite {

    fun render(source: String, baseSize: Float): CharSequence {
        val out = SpannableStringBuilder()
        for (line in source.lines()) {
            val trimmed = line.trimEnd()
            if (trimmed.isBlank()) {
                out.append('\n')
                continue
            }
            val heading = headingLevel(trimmed)
            if (heading > 0) {
                val body = trimmed.substring(heading).trimStart()
                out.append(inline(body))
                out.setSpan(
                    StyleSpan(Typeface.BOLD),
                    out.length - body.length,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else if (isBullet(trimmed)) {
                out.append("• ")
                out.append(inline(trimmed.substring(1).trimStart()))
            } else {
                out.append(inline(trimmed))
            }
            out.append('\n')
        }
        return out
    }

    private fun headingLevel(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == '#') n++
        return if (n in 1..6 && n < line.length && line[n] == ' ') n else 0
    }

    private fun isBullet(line: String): Boolean =
        line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ")

    private fun inline(text: String): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        val start = out.length
                        out.append(text, i + 2, end)
                        out.setSpan(
                            StyleSpan(Typeface.BOLD), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 2
                    } else {
                        out.append(text[i++])
                    }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > 0) {
                        val start = out.length
                        out.append(text, i + 1, end)
                        out.setSpan(
                            StyleSpan(Typeface.ITALIC), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 1
                    } else {
                        out.append(text[i++])
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        out.append(text, i + 1, end)
                        i = end + 1
                    } else {
                        out.append(text[i++])
                    }
                }
                else -> out.append(text[i++])
            }
        }
        return out
    }
}
