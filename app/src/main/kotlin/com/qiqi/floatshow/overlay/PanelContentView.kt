package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
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

    fun setLayers(next: List<PanelLayer>) {
        val before = layers
        if (before == next) return
        layers = next
        // 内容变了, 丢掉旧的解码结果(位图与文本排版都按内容区分)
        dropCaches(before, next)
        invalidate()
    }

    /** 面板尺寸变化时不需要做任何事: 内容坐标是绝对的, 不随视窗拉伸 */
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
    }

    // ── 位图 ────────────────────────────────────────────────

    private fun drawImage(canvas: Canvas, layer: PanelLayer) {
        val uri = runCatching { Uri.parse(layer.content) }.getOrNull() ?: return
        val natural = naturalSize(layer, uri) ?: return
        val w = if (layer.width > 0) layer.width.toFloat() else natural.first
        val h = if (layer.height > 0) layer.height.toFloat() else natural.second

        canvas.save()
        // 以层矩形的中心为变换原点: 旋转与镜像都不会把内容甩到别处
        canvas.translate(layer.x + w / 2f, layer.y + h / 2f)
        canvas.rotate(layer.rotation.toFloat() * 90f)
        if (layer.mirrored) canvas.scale(-1f, 1f)
        canvas.translate(-w / 2f, -h / 2f)

        // 把屏幕视口反变换回"层的本地坐标", 得到这一帧真正要画的那一块.
        // 之前只从 (0,0) 读一块, 所以内容一移出左上角就是空白, 缩小后也只看到一角.
        val screenRect = RectF(0f, 0f, getWidth().toFloat(), getHeight().toFloat())
        val inverse = Matrix()
        if (canvas.matrix.invert(inverse)) {
            val local = RectF()
            inverse.mapRect(local, screenRect)
            val clipped = RectF(0f, 0f, w, h)
            if (!clipped.intersect(local) || clipped.isEmpty) {
                canvas.restore()
                return
            }
            // 这一块在原图上的坐标(层里 1 档 = 原图 1 像素, 缩放后按比例换算)
            val scaleX = if (w > 0f) natural.first / w else 1f
            val scaleY = if (h > 0f) natural.second / h else 1f
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
            // 这块最终落在屏幕上的像素尺寸, 决定采样步长
            val pixels = canvas.matrix.mapRadius(1f)
            val targetW = (srcRect.width() * pixels).toInt().coerceAtLeast(1)
            val step = (srcRect.width() / targetW.coerceAtLeast(1)).coerceAtLeast(1)

            val entry = imageWindow(layer, uri, srcRect, step, natural)
            if (entry == null) {
                canvas.restore()
                return
            }
            canvas.drawBitmap(entry, entry.src, clipped, imagePaint)
        }
        canvas.restore()
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
        step: Int,
        natural: Pair<Float, Float>
    ): ImageEntry? {
        val key = layer.content + "|" + srcRect.left + "," + srcRect.top + "," +
            srcRect.width() + "," + srcRect.height() + "|" + step
        imageCache.get(key)?.let { if (!it.bitmap.isRecycled) return it }
        if (pendingImage.add(key)) {
            val current = token
            executor.execute {
                val (bmp, outW, outH) = PanelContentSource.loadRegion(
                    context, uri, srcRect, step,
                    natural.first.toInt(), natural.second.toInt()
                )
                post {
                    pendingImage.remove(key)
                    if (current != token) {
                        bmp?.recycle()
                        return@post
                    }
                    if (bmp != null && outW > 0 && outH > 0) {
                        imageCache.put(key, ImageEntry(bmp, RectF(0f, 0f, outW.toFloat(), outH.toFloat())))
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
        step: Int,
        naturalW: Int,
        naturalH: Int
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
                null to 0 to 0
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
