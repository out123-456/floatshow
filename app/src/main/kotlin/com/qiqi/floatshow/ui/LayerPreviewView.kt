package com.qiqi.floatshow.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.view.View
import com.caverock.androidsvg.SVG
import kotlin.math.max
import kotlin.math.min

/**
 * 层内容的预览. 位图与 SVG 都在后台线程加载, 主线程只负责画.
 *
 * 位图一律降采样后再进内存: 8192px 的 PNG 原样解码是 200MB 以上, 必然撑爆进程.
 * 这里按"最大边 2048"取 2 的幂做 inSampleSize, 面板真正渲染时再决定最终像素密度.
 */
class LayerPreviewView(context: Context) : View(context) {

    private var bitmap: Bitmap? = null
    private var svg: SVG? = null
    private var svgWidth = 0f
    private var svgHeight = 0f
    private var svgScale = 1f
    private var token = 0

    // AndroidView 的 update 每次重组都会调用, 相同内容不能重新解码, 否则预览会闪
    private var loadedUri: Uri? = null
    private var loadedIsSvg = false
    private var hasLoaded = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dest = RectF()

    /** 加载完成后回调: (位图宽, 位图高) 或 (SVG 标称宽, 高); 失败时 null */
    var onLoaded: ((Int, Int) -> Unit)? = null

    /** SVG 相对自身标称尺寸的倍数, 只影响预览 */
    fun setSvgScale(scale: Float) {
        if (svgScale == scale) return
        svgScale = scale
        invalidate()
    }

    /** 内容没变就不再解码; 只在 uri 或类型真正改变时重新加载 */
    fun loadIfChanged(uri: Uri?, isSvg: Boolean) {
        if (hasLoaded && loadedUri == uri && loadedIsSvg == isSvg) return
        hasLoaded = true
        loadedUri = uri
        loadedIsSvg = isSvg
        load(uri, isSvg)
    }

    fun load(uri: Uri?, isSvg: Boolean) {
        token++
        val current = token
        bitmap = null
        svg = null
        svgWidth = 0f
        svgHeight = 0f
        invalidate()
        if (uri == null) return

        Thread {
            var loadedBitmap: Bitmap? = null
            var loadedSvg: SVG? = null
            var width = 0f
            var height = 0f
            runCatching {
                if (isSvg) {
                    val parsed = context.contentResolver.openInputStream(uri)?.use {
                        SVG.getFromInputStream(it)
                    }
                    if (parsed != null) {
                        loadedSvg = parsed
                        width = parsed.documentWidth.takeIf { it > 0f } ?: 512f
                        height = parsed.documentHeight.takeIf { it > 0f } ?: 512f
                        // 标称尺寸缺失时按 512 兜底, 避免 0 尺寸导致整块空白
                        if (parsed.documentWidth <= 0f) parsed.setDocumentWidth(width)
                        if (parsed.documentHeight <= 0f) parsed.setDocumentHeight(height)
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bounds)
                    }
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        var sample = 1
                        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) {
                            sample *= 2
                        }
                        val options = BitmapFactory.Options().apply { inSampleSize = sample }
                        loadedBitmap = context.contentResolver.openInputStream(uri)?.use {
                            BitmapFactory.decodeStream(it, null, options)
                        }
                        width = bounds.outWidth.toFloat()
                        height = bounds.outHeight.toFloat()
                    }
                }
            }
            post {
                if (current != token) {
                    loadedBitmap?.recycle()
                    return@post
                }
                bitmap = loadedBitmap
                svg = loadedSvg
                svgWidth = width
                svgHeight = height
                invalidate()
                if (loadedBitmap != null || loadedSvg != null) {
                    onLoaded?.invoke(width.toInt(), height.toInt())
                }
            }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            val fit = min(w / bmp.width, h / bmp.height)
            val dw = bmp.width * fit
            val dh = bmp.height * fit
            dest.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
            canvas.drawBitmap(bmp, null, dest, paint)
            return
        }

        val vector = svg ?: return
        val baseW = (svgWidth * svgScale).coerceAtLeast(1f)
        val baseH = (svgHeight * svgScale).coerceAtLeast(1f)
        // 倍数放大后超出视图就整体缩回来, 保证总是看得全
        val fit = min(1f, min(w / baseW, h / baseH))
        val dw = baseW * fit
        val dh = baseH * fit
        dest.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        runCatching { vector.renderToCanvas(canvas, dest) }
    }

    companion object {
        /** 进内存前的最大边长, 与面板最终显示尺寸脱钩 */
        private const val MAX_SIDE = 2048
    }
}
