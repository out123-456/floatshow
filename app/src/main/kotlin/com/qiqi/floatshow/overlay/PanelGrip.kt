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
}
