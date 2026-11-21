package com.qiqi.floatshow.ui

import android.graphics.Bitmap

/**
 * 降采样小图的一格共享缓存.
 *
 * 抽屉预览和全屏查看都需要"同一张图的低清全图", 规格都是长边 1024.
 * 全屏打开时如果正好在看同一张图, 直接借用抽屉已经解好的那份, 不必再解一遍.
 *
 * 所有权约定: 放进来的 Bitmap 由这里持有, 借用方只读不改不 recycle.
 */
object OverviewCache {

    private var key: String? = null
    private var bitmap: Bitmap? = null
    private var imageWidth = 0
    private var imageHeight = 0

    fun put(uri: String, bmp: Bitmap, width: Int, height: Int) {
        if (bmp.isRecycled) return
        if (key == uri && bitmap === bmp) return
        // 不 recycle 旧那张: 正在全屏查看的视图可能还拿着它, 回收会让它突然白屏.
        // 只换引用, 旧位图交给 GC
        key = uri
        bitmap = bmp
        imageWidth = width
        imageHeight = height
    }

    /** 命中时返回位图与原始宽高; 未命中返回 null */
    fun peek(uri: String): Triple<Bitmap, Int, Int>? {
        val bmp = bitmap ?: return null
        if (key != uri || bmp.isRecycled) return null
        return Triple(bmp, imageWidth, imageHeight)
    }
}
