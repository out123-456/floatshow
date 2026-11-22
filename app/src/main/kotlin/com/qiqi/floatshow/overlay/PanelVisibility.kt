package com.qiqi.floatshow.overlay

import androidx.compose.runtime.mutableStateOf

/**
 * 面板内容区把"完全看不见的层"报上来, 主界面据此把种类图标标红.
 * 在同一个进程里, 所以用 Compose 的 [mutableStateOf] 直接当跨组件通道:
 * 写的一方是面板的 View, 读的一方是主界面.
 */
object PanelVisibility {
    val invisibleLayerIds = mutableStateOf<Set<String>>(emptySet())

    fun set(ids: Set<String>) {
        if (invisibleLayerIds.value != ids) invisibleLayerIds.value = ids
    }

    fun clear() {
        if (invisibleLayerIds.value.isNotEmpty()) invisibleLayerIds.value = emptySet()
    }
}
