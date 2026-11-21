package com.qiqi.floatshow.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 收缩态悬浮球: 白底圆 + 居中的应用图标.
 * 图标由系统给的应用图标缩放到圆内, 不做任何自绘.
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

    private val icon: Drawable? = runCatching {
        context.packageManager.getApplicationIcon(context.packageName)
    }.getOrNull()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val radius = minOf(w, h) / 2f
        val cx = w / 2f
        val cy = h / 2f
        canvas.drawCircle(cx, cy, radius, whitePaint)
        canvas.drawCircle(cx, cy, radius - borderPaint.strokeWidth / 2f, borderPaint)

        val iconDrawable = icon ?: return
        val inset = radius * 0.32f
        val box = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)
        iconDrawable.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
        iconDrawable.draw(canvas)
    }
}
