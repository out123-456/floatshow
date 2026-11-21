package com.qiqi.floatshow.overlay

/** 一次缩放的结算结果: 新几何 + 生效中的手柄(可能因为越界被换到对面) */
data class ResizeResult(val geometry: PanelGeometry, val grip: PanelGrip)

/**
 * 面板几何的纯计算, 单位全部是像素.
 *
 * 位置: 允许单边超出屏幕, 但对边至少留屏幕尺寸的 5%.
 * 翻转: 顶边进入屏幕顶部 10% 时工具栏翻到底部; 翻回顶部要底边进入屏幕底部 10% 且顶边已离开顶部 10%.
 * 缩放: 只有角能缩放(边只用来整体移动). 被拖的边跟手, 宽/高不足最小尺寸时面板整体跟着手指平移,
 *       手柄交给对面那一侧; 纵向翻转同时把工具栏换到另一边, 避免手柄压在关闭按钮上.
 */
object PanelLayout {

    const val MIN_VISIBLE_RATIO = 0.05f
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

    /** 把位置约束进"对边至少留 5%"的范围; 面板比屏幕还大时居中 */
    fun clamp(geometry: PanelGeometry, screenWidth: Int, screenHeight: Int): PanelGeometry {
        val visibleX = (screenWidth * MIN_VISIBLE_RATIO).toInt()
        val visibleY = (screenHeight * MIN_VISIBLE_RATIO).toInt()
        val loX = visibleX - geometry.width
        val hiX = screenWidth - visibleX
        val loY = visibleY - geometry.height
        val hiY = screenHeight - visibleY
        val x = if (loX > hiX) (screenWidth - geometry.width) / 2 else geometry.x.coerceIn(loX, hiX)
        val y = if (loY > hiY) (screenHeight - geometry.height) / 2 else geometry.y.coerceIn(loY, hiY)
        return if (x == geometry.x && y == geometry.y) geometry else geometry.copy(x = x, y = y)
    }

    /** 工具栏是否该翻到底部(入参是约束后的几何) */
    fun nextToolbarAtBottom(current: Boolean, geometry: PanelGeometry, screenHeight: Int): Boolean {
        val band = screenHeight * FLIP_RATIO
        return if (!current) {
            geometry.y < band
        } else {
            geometry.y >= band && geometry.y + geometry.height >= screenHeight - band
        }
    }

    /**
     * 从某个角缩放. dx/dy 是本次手势相对起点的位移.
     * 被拖的边越过最小尺寸时, 面板整体朝手指方向平移, 该方向的手柄交给对面.
     */
    fun resize(
        grip: PanelGrip,
        start: PanelGeometry,
        dx: Float,
        dy: Float,
        minSize: Int,
        maxSize: Int
    ): ResizeResult {
        var left = start.x.toFloat()
        var top = start.y.toFloat()
        var right = (start.x + start.width).toFloat()
        var bottom = (start.y + start.height).toFloat()
        var g = grip
        val min = minSize.toFloat()
        val max = maxSize.toFloat()

        if (grip.affectsRight) {
            val finger = right + dx
            if (finger - left < min) {
                left = finger
                right = left + min
                g = g.flipHorizontal()
            } else {
                right = finger
                if (right - left > max) right = left + max
            }
        } else if (grip.affectsLeft) {
            val finger = left + dx
            if (right - finger < min) {
                right = finger
                left = right - min
                g = g.flipHorizontal()
            } else {
                left = finger
                if (right - left > max) left = right - max
            }
        }

        if (grip.affectsBottom) {
            val finger = bottom + dy
            if (finger - top < min) {
                top = finger
                bottom = top + min
                g = g.flipVertical()
            } else {
                bottom = finger
                if (bottom - top > max) bottom = top + max
            }
        } else if (grip.affectsTop) {
            val finger = top + dy
            if (bottom - finger < min) {
                bottom = finger
                top = bottom - min
                g = g.flipVertical()
            } else {
                top = finger
                if (bottom - top > max) top = bottom - max
            }
        }

        return ResizeResult(
            PanelGeometry(left.toInt(), top.toInt(), (right - left).toInt(), (bottom - top).toInt()),
            g
        )
    }
}
