package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 收缩态悬浮球: 应用图标铺满整颗球, 并按球的圆形裁剪,
 * 图标的圆与球的外圆重合, 不再留白边.
 */
class BallView(context: Context) : View(context) {

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density
    }
    private val clipPath = Path()

    private val icon: Drawable? = runCatching {
        context.packageManager.getApplicationIcon(context.packageName)
    }.getOrNull()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val radius = minOf(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f

        // 图标可能有透明区域, 底下先垫一层白
        canvas.drawCircle(cx, cy, radius, whitePaint)

        val iconDrawable = icon ?: return
        // 图标按整块正方形绘制, 再裁成圆: 图标的最大内接圆就是球的外圆
        clipPath.reset()
        clipPath.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        iconDrawable.setBounds(0, 0, w.toInt(), h.toInt())
        iconDrawable.draw(canvas)
        canvas.restore()

        canvas.drawCircle(cx, cy, radius - borderPaint.strokeWidth / 2f, borderPaint)
    }
}
