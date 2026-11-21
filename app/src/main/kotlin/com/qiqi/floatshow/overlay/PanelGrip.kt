package com.qiqi.floatshow.overlay

/**
 * 面板上可以按住拖动的位置: 四条边 + 四个角.
 * 关闭按钮所在的角不放任何手柄, 那个角只属于关闭.
 */
enum class PanelGrip(
    val affectsLeft: Boolean,
    val affectsTop: Boolean,
    val affectsRight: Boolean,
    val affectsBottom: Boolean,
    val isCorner: Boolean
) {
    LEFT_TOP(true, true, false, false, true),
    RIGHT_TOP(false, true, true, false, true),
    LEFT_BOTTOM(true, false, false, true, true),
    RIGHT_BOTTOM(false, false, true, true, true),
    TOP_EDGE(false, true, false, false, false),
    BOTTOM_EDGE(false, false, false, true, false),
    LEFT_EDGE(true, false, false, false, false),
    RIGHT_EDGE(false, false, true, false, false);

    /** 左右互换, 用于越过边界后把手柄交给另一侧 */
    fun flipHorizontal(): PanelGrip = when (this) {
        LEFT_TOP -> RIGHT_TOP
        RIGHT_TOP -> LEFT_TOP
        LEFT_BOTTOM -> RIGHT_BOTTOM
        RIGHT_BOTTOM -> LEFT_BOTTOM
        LEFT_EDGE -> RIGHT_EDGE
        RIGHT_EDGE -> LEFT_EDGE
        else -> this
    }

    /** 上下互换 */
    fun flipVertical(): PanelGrip = when (this) {
        LEFT_TOP -> LEFT_BOTTOM
        LEFT_BOTTOM -> LEFT_TOP
        RIGHT_TOP -> RIGHT_BOTTOM
        RIGHT_BOTTOM -> RIGHT_TOP
        TOP_EDGE -> BOTTOM_EDGE
        BOTTOM_EDGE -> TOP_EDGE
        else -> this
    }
}
