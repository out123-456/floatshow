package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    private val bitmaps = LruCache<String, Bitmap>(8)
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

        val bitmap = bitmapFor(layer, uri, w, h)
        if (bitmap != null && !bitmap.isRecycled) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(bitmap, null, dst, imagePaint)
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

    /**
     * 取这一层要画的那块像素. 面板视窗有限, 所以永远只读"需要的那一块".
     * 有 raw 走 mmap 读区; 没有的话, 小图整张解出来, 大图只能用降采样图顶着.
     */
    private fun bitmapFor(layer: PanelLayer, uri: Uri, w: Float, h: Float): Bitmap? {
        val cacheKey = layer.content
        bitmaps.get(cacheKey)?.let { if (!it.isRecycled) return it }
        if (pendingImage.add(cacheKey)) {
            val current = token
            executor.execute {
                val loaded = PanelContentSource.load(context, uri, w.toInt(), h.toInt())
                post {
                    pendingImage.remove(cacheKey)
                    if (current != token) {
                        loaded?.recycle()
                        return@post
                    }
                    if (loaded != null) {
                        bitmaps.put(cacheKey, loaded)
                        invalidate()
                    }
                }
            }
        }
        return null
    }

    private val pendingImage = HashSet<String>()

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
        bitmaps.evictAll()
        textLayouts.clear()
        // 不再被任何层引用的 raw 就不该继续开着
        val keep = after.map { it.content }.toSet()
        val stale = readers.keys.filter { it !in keep }
        stale.forEach { key -> readers.remove(key)?.close() }
        pendingImage.clear()
    }

    fun release() {
        token++
        bitmaps.evictAll()
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
 * 层内容的像素来源. 面板只需要"这一块", 所以永远按目标尺寸取, 不整图进内存.
 */
object PanelContentSource {

    fun isSvg(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (mime == "image/svg+xml") return true
        return uri.lastPathSegment?.endsWith(".svg", ignoreCase = true) == true
    }

    /**
     * 加载一层的位图. 有 raw 就按目标尺寸读一块并缩放; 没有就按目标尺寸降采样解码.
     * 目标超过 [maxSide] 时按 [maxSide] 截断, 免得面板里出现几万像素宽的位图.
     */
    fun load(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val w = targetW.coerceAtLeast(1).coerceAtMost(MAX_SIDE)
        val h = targetH.coerceAtLeast(1).coerceAtMost(MAX_SIDE)
        return runCatching {
            val raw = RawImage.fileFor(context, uri)
            if (raw.isFile && raw.length() > RawImage.HEADER) {
                RawReader(raw).use { reader ->
                    val rw = min(reader.width, w)
                    val rh = min(reader.height, h)
                    val pixels = IntArray(rw * rh)
                    reader.readRows(0, 0, rw, rh, pixels)
                    Bitmap.createBitmap(pixels, rw, rh, Bitmap.Config.ARGB_8888)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }
        }.getOrNull()
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
