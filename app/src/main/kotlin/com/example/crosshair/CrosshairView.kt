package com.example.crosshair

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.View

class CrosshairView(context: Context) : View(context) {

    private var displaySize: Int = 40
    private var bitmap: Bitmap? = null
    private var hasCustomImage: Boolean = false

    private val fallbackPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setDisplaySize(size: Int) {
        displaySize = size
        requestLayout()
        invalidate()
    }

    fun updateImage(uri: Uri?, size: Int) {
        displaySize = size
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (inputStream != null) {
                    BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream.close()
                }

                val inputStream2 = context.contentResolver.openInputStream(uri)
                val loadedBitmap = BitmapFactory.decodeStream(inputStream2)
                inputStream2?.close()
                hasCustomImage = loadedBitmap != null
                bitmap = loadedBitmap
            } catch (e: Exception) {
                hasCustomImage = false
                bitmap = null
            }
        } else {
            hasCustomImage = false
            bitmap = null
        }
        requestLayout()
        invalidate()
    }

    fun setTransparency(alpha: Float) {
        this.alpha = alpha
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(displaySize, displaySize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap
        if (hasCustomImage && bmp != null) {
            val scaledBitmap = Bitmap.createScaledBitmap(bmp, displaySize, displaySize, true)
            canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
            if (scaledBitmap != bmp) scaledBitmap.recycle()
        } else {
            // Draw white filled circle as default
            val cx = displaySize / 2f
            val cy = displaySize / 2f
            val radius = displaySize / 2f
            canvas.drawCircle(cx, cy, radius, fallbackPaint)
        }
    }
}
