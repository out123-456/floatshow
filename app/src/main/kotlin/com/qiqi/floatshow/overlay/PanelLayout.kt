package com.qiqi.floatshow.overlay

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
            // 顶边进入屏幕顶部 10%: 翻到底部
            geometry.y < band
        } else {
            // 已经在底部: 只有"离开顶部区且底边进入底部 10%"才翻回顶部, 否则保持不动.
            // 这里返回的是"是否仍在底部", 上一版把翻回条件直接当结果返回, 顶部区内两种状态互相翻转造成闪烁
            !(geometry.y >= band && geometry.y + geometry.height >= screenHeight - band)
        }
    }

    /**
     * 从某个角缩放. dx/dy 是本次手势相对起点的位移.
     * 被拖的边到最小/最大尺寸就停住, 面板位置不跟着手指挥动.
     */
    fun resize(
        grip: PanelGrip,
        start: PanelGeometry,
        dx: Float,
        dy: Float,
        minSize: Int,
        maxSize: Int
    ): PanelGeometry {
        var left = start.x.toFloat()
        var top = start.y.toFloat()
        var right = (start.x + start.width).toFloat()
        var bottom = (start.y + start.height).toFloat()
        val min = minSize.toFloat()
        val max = maxSize.toFloat()

        if (grip.affectsRight) {
            right = (right + dx).coerceIn(left + min, left + max)
        } else if (grip.affectsLeft) {
            left = (left + dx).coerceIn(right - max, right - min)
        }

        if (grip.affectsBottom) {
            bottom = (bottom + dy).coerceIn(top + min, top + max)
        } else if (grip.affectsTop) {
            top = (top + dy).coerceIn(bottom - max, bottom - min)
        }

        return PanelGeometry(
            left.toInt(),
            top.toInt(),
            (right - left).toInt(),
            (bottom - top).toInt()
        )
    }
}
