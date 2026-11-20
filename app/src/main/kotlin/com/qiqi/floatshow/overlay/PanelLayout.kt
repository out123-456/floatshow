package com.qiqi.floatshow.overlay

/**
 * 面板几何的纯计算, 与 Compose 无关, 单位全部是像素.
 *
 * 三条规则:
 * 1. 允许单边超出屏幕, 但对边至少留屏幕尺寸的 5%
 * 2. 面板顶部进入屏幕顶部 10% 时工具栏翻到底部; 工具栏在底部且面板底部进入屏幕底部 10%、同时顶部已离开顶部 10% 时翻回顶部
 * 3. 缩放时被拖动的那条边在离对边 [minSize] 处停住
 */
object PanelLayout {

    const val MIN_VISIBLE_RATIO = 0.05f
    const val FLIP_RATIO = 0.1f

    /** 把位置约束进"对边至少留 5%"的范围. 尺寸超过 90% 屏幕时居中, 否则没有合法位置 */
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

    /** 工具栏是否该翻到底部 */
    fun nextToolbarAtBottom(current: Boolean, geometry: PanelGeometry, screenHeight: Int): Boolean {
        val band = screenHeight * FLIP_RATIO
        return if (!current) {
            // 顶部进入屏幕顶部 10% -> 翻到底部
            geometry.y < band
        } else {
            // 翻回顶部要同时满足: 工具栏(底边)进入屏幕底部 10%, 且顶部已离开顶部 10%(避免来回跳)
            geometry.y >= band && geometry.y + geometry.height >= screenHeight - band
        }
    }

    /** 关闭按钮所在角, 这个角不提供缩放 */
    fun closeCorner(toolbarAtBottom: Boolean): ResizeCorner =
        if (toolbarAtBottom) ResizeCorner.RIGHT_BOTTOM else ResizeCorner.RIGHT_TOP

    fun resizableCorners(toolbarAtBottom: Boolean): List<ResizeCorner> {
        val close = closeCorner(toolbarAtBottom)
        return ResizeCorner.entries.filter { it != close }
    }

    /**
     * 从某个角缩放. dx/dy 是本次手势位移, 用的是左上/右下两条边的浮点值,
     * 被拖的边越过最小尺寸时停住, 另一条边不动.
     */
    fun resize(
        corner: ResizeCorner,
        geometry: PanelGeometry,
        dx: Float,
        dy: Float,
        minSize: Int,
        maxSize: Int
    ): PanelGeometry {
        var left = geometry.x.toFloat()
        var top = geometry.y.toFloat()
        var right = (geometry.x + geometry.width).toFloat()
        var bottom = (geometry.y + geometry.height).toFloat()

        when (corner) {
            ResizeCorner.LEFT_TOP -> { left += dx; top += dy }
            ResizeCorner.LEFT_BOTTOM -> { left += dx; bottom += dy }
            ResizeCorner.RIGHT_TOP -> { right += dx; top += dy }
            ResizeCorner.RIGHT_BOTTOM -> { right += dx; bottom += dy }
        }

        val movesLeft = corner == ResizeCorner.LEFT_TOP || corner == ResizeCorner.LEFT_BOTTOM
        val movesTop = corner == ResizeCorner.LEFT_TOP || corner == ResizeCorner.RIGHT_TOP

        // 宽: 小于最小尺寸时把被拖的边顶回去; 大于上限时同理
        var width = right - left
        if (width < minSize) {
            val fixed = minSize.toFloat()
            if (movesLeft) left = right - fixed else right = left + fixed
            width = fixed
        } else if (width > maxSize) {
            if (movesLeft) left = right - maxSize.toFloat() else right = left + maxSize.toFloat()
            width = maxSize.toFloat()
        }

        var height = bottom - top
        if (height < minSize) {
            val fixed = minSize.toFloat()
            if (movesTop) top = bottom - fixed else bottom = top + fixed
            height = fixed
        } else if (height > maxSize) {
            if (movesTop) top = bottom - maxSize.toFloat() else bottom = top + maxSize.toFloat()
            height = maxSize.toFloat()
        }

        return PanelGeometry(
            x = left.toInt(),
            y = top.toInt(),
            width = width.toInt(),
            height = height.toInt()
        )
    }
}
