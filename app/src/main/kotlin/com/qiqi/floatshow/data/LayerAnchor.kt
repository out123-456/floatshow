package com.qiqi.floatshow.data

/**
 * 锚点: 一个被若干层共享的坐标系原点.
 *
 * 每个层都持有一个锚点与一份偏移, 层的绝对位置 = 锚点原点 + 自己这份偏移.
 * 这样"绑定"只是换共享对象, 不引入树: a 位移时 a 的锚点原点是唯一变化的东西,
 * 那些共享同一锚点的层各算一次加法即可, 互相之间不需要递归.
 *
 * 性能约定:
 *  · 只有创建者可以改锚点(改动它会牵动所有共享者)
 *  · 广播事件由锚点自己遍历订阅者发出; 创建者只发一次, 不必去找子层
 *  · 锚点是面板的层(terminated)不订阅任何事件, 位置直接等于自己的偏移
 */
data class Anchor(
    /** 创建者(拥有这个锚点原点的层). 共享同一个锚点的层彼此不再有父子关系 */
    val ownerId: String?,
    /** 锚点原点在面板坐标系里的位置 */
    val x: Int,
    val y: Int
) {
    /** 面板是终止锚点: 不移动, 所以挂在它上面的层完全不需要更新 */
    val terminated: Boolean get() = ownerId == null

    companion object {
        /** 面板锚点: 原点恒为 (0,0), 且永不发出事件 */
        val PANEL = Anchor(ownerId = null, x = 0, y = 0)
    }
}

/**
 * 锚点广播出来的"位置更新". 订阅者拿自己的偏移做一次加法就能得到新位置.
 * 只有创建者会发这个事件, 锚点负责转给所有订阅者.
 */
data class AnchorShift(val anchorId: String, val dx: Int, val dy: Int)
