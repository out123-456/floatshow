package com.qiqi.floatshow.overlay

/**
 * 可按住缩放的角. 只有三个: 关闭按钮所在角永远不提供缩放,
 * 否则热区会盖住关闭按钮.
 */
enum class ResizeCorner {
    LEFT_TOP,
    LEFT_BOTTOM,
    RIGHT_TOP,
    RIGHT_BOTTOM
}
