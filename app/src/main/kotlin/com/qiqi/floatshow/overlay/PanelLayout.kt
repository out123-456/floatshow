package com.qiqi.floatshow.overlay

import kotlin.math.roundToInt

/**
 * 面板几何的纯计算, 单位全部是像素.
 *
 * 位置: 面板允许几乎整个出屏, 唯一的底线是"最小化/关闭那颗按钮"必须完整留在安全区,
 *       否则面板就再也点不到、关不掉了. 用户拖到边界时手势不被打断, 只是超出方向不再跟手.
 * 翻转: 顶边进入屏幕顶部 10% 时工具栏翻到底部; 翻回顶部要底边进入屏幕底部 10% 且顶边已离开顶部 10%.
 * 缩放: 只有角能缩放(边只用来整体移动). 被拖的边跟手, 宽/高不足最小尺寸时面板整体跟着手指平移,
 *       手柄交给对面那一侧; 纵向翻转同时把工具栏换到另一边, 避免手柄压在关闭按钮上.
 */
object PanelLayout {

    const val FLIP_RATIO = 0.1f

    /** 关闭按钮所在的角: 工具栏在上是右上, 在下是右下 */
    fun closeCorner(toolbarAtBottom: Boolean): PanelGrip =
        if (toolbarAtBottom) PanelGrip.RIGHT_BOTTOM else PanelGrip.RIGHT_TOP

    /** 可缩放的手柄 = 三个角, 去掉关闭角 */
    fun cornerGrips(toolbarAtBottom: Boolean): List<PanelGrip> {
        val close = closeCorner(toolbarAtBottom)
        return PanelGrip.entries.filter { it.isCorner && it != close }
    }

    /**
     * 可以整体移动的边缘. 工具栏所在的那条边由工具栏自己承担拖动, 不再单独放手柄,
     * 否则手柄会压在最小化/关闭按钮上.
     */
    fun edgeGrips(toolbarAtBottom: Boolean): List<PanelGrip> {
        val edges = listOf(
            PanelGrip.TOP_EDGE,
            PanelGrip.BOTTOM_EDGE,
            PanelGrip.LEFT_EDGE,
            PanelGrip.RIGHT_EDGE
        )
        return if (toolbarAtBottom) edges.filter { it != PanelGrip.BOTTOM_EDGE }
        else edges.filter { it != PanelGrip.TOP_EDGE }
    }

    /**
     * 面板位置约束. 唯一的硬要求: 最小化/关闭那颗按钮(工具栏右端 controlSize×controlSize)
     * 完整落在安全区内. 面板其余部分可以整块出屏, 用户拖回来就能关.
     *
     * 一维各算一个"可行区间", 超出的那一侧就夹回来; 另一侧不受影响,
     * 所以手指继续往超出的方向走时面板停住, 反手回来立刻跟上, 焦点不丢.
     *
     * @param safe 挖孔四条边, 顺序 左/上/右/下
     */
    fun clamp(
        geometry: PanelGeometry,
        screenWidth: Int,
        screenHeight: Int,
        controlSize: Int,
        toolbarAtBottom: Boolean,
        safe: IntArray
    ): PanelGeometry {
        val usableLeft = safe[0]
        val usableTop = safe[1]
        val usableRight = screenWidth - safe[2]
        val usableBottom = screenHeight - safe[3]

        // 按钮在 X 方向的可行区间: 按钮左边缘 >= 可用区左, 右边缘 <= 可用区右
        val loX = usableLeft + controlSize - geometry.width
        val hiX = usableRight - controlSize
        // 按钮在 Y 方向: 工具栏在上时按钮占面板顶端, 在下时占底端
        val loY = if (toolbarAtBottom) {
            usableTop + controlSize - geometry.height
        } else {
            usableTop
        }
        val hiY = if (toolbarAtBottom) {
            usableBottom - controlSize
        } else {
            usableBottom - geometry.height
        }

        val x = axis(geometry.x, loX, hiX)
        val y = axis(geometry.y, loY, hiY)
        return if (x == geometry.x && y == geometry.y) geometry else geometry.copy(x = x, y = y)
    }

    /** 区间可能因面板比可用区还大而颠倒, 这时取靠前的那一端, 保证方向一致 */
    private fun axis(value: Int, lo: Int, hi: Int): Int =
        if (lo <= hi) value.coerceIn(lo, hi) else hi

    /**
     * 工具栏是否该翻到底部(入参是约束后的几何).
     * 判断依据是"工具栏那一行"而不是面板外框: 面板可以拖到几乎整块出屏,
     * 只有工具栏真的贴近屏幕边缘时才需要换边.
     */
    fun nextToolbarAtBottom(
        current: Boolean,
        geometry: PanelGeometry,
        screenHeight: Int,
        controlSize: Int
    ): Boolean {
        val band = screenHeight * FLIP_RATIO
        val barTop = if (current) {
            geometry.y + geometry.height - controlSize
        } else {
            geometry.y
        }
        return if (!current) {
            // 工具栏进入屏幕顶部 10% 带: 翻到底部
            barTop < band
        } else {
            // 已经在底部: 只有工具栏离开底部 10% 带才翻回顶部.
            // 上一版把两个条件组合着判, 顶部带内两种状态互相翻转造成闪烁.
            barTop + controlSize < screenHeight - band
        }
    }

    /**
     * 从某个角缩放. dx/dy 是本次手势相对起点的位移.
     * 全程用整数边: 只有被拖的那条边按手指四舍五入, 不动的边保持原值.
     * 上一版用浮点边再各自 toInt() 截断, 宽高与位置的取整方向不一致, 不动的边会在 R 和 R-1 之间跳.
     */
    fun resize(
        grip: PanelGrip,
        start: PanelGeometry,
        dx: Float,
        dy: Float,
        minSize: Int,
        maxSize: Int
    ): PanelGeometry {
        var left = start.x
        var top = start.y
        var right = start.x + start.width
        var bottom = start.y + start.height

        if (grip.affectsRight) {
            right = (right + dx).roundToInt().coerceIn(left + minSize, left + maxSize)
        } else if (grip.affectsLeft) {
            left = (left + dx).roundToInt().coerceIn(right - maxSize, right - minSize)
        }

        if (grip.affectsBottom) {
            bottom = (bottom + dy).roundToInt().coerceIn(top + minSize, top + maxSize)
        } else if (grip.affectsTop) {
            top = (top + dy).roundToInt().coerceIn(bottom - maxSize, bottom - minSize)
        }

        return PanelGeometry(left, top, right - left, bottom - top)
    }
}
