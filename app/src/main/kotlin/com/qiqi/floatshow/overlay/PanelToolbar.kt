package com.qiqi.floatshow.overlay

import android.content.Context
import android.widget.FrameLayout
import kotlin.math.max

/**
 * 工具栏. 三个子 View 按添加顺序依次为 标题 → 最小化 → 关闭, 绘制次序就是覆盖次序:
 * 关闭最上层, 最小化次之, 标题最下.
 *
 * 位置规则: 标题铺满整条; 关闭贴右端; 最小化贴在关闭左侧, 窄到放不下时停在最左端,
 * 由关闭从右往左盖住它的一部分. 谁也不会被移除或缩小, 标题文字也不会被截成省略号.
 * 自己布局而不是靠 LayoutParams, 缩放时窗口每变一帧都不用逐个改子 View 的参数.
 */
class PanelToolbar(context: Context, private val unit: Int) : FrameLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childWidth = if (i == 0) width else unit
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        if (childCount < 3) return
        getChildAt(0).layout(0, 0, width, height)
        val minimizeLeft = max(0, width - unit * 2)
        getChildAt(1).layout(minimizeLeft, 0, minimizeLeft + unit, height)
        val closeLeft = max(0, width - unit)
        getChildAt(2).layout(closeLeft, 0, closeLeft + unit, height)
    }

    // 不让 FrameLayout 自己对子 View 做任何 gravity 处理
    override fun shouldDelayChildPressedState(): Boolean = false
}
