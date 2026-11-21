package com.qiqi.floatshow.data

import org.json.JSONArray
import org.json.JSONObject

/** 层的内容类型. 只有这两种, 不再区分"背景层"与"叠加层"本身 */
enum class PanelLayerKind { IMAGE, TEXT }

/**
 * 优先级标记.
 * TOP 与 BOTTOM 全局互斥, 且各自最多一个: 已经有一个 TOP 时, 别的层不能标 TOP 也不能标 BOTTOM.
 * 固定只是一个标记, 目前不影响任何行为, 可以与 TOP/BOTTOM 叠加, 数量不限.
 */
enum class PanelLayerPin { NONE, TOP, BOTTOM }

/**
 * 面板里的一层. 列表第 0 项就是背景层, 之后依次是叠加层.
 * [content] 对图片是 content:// uri, 对文本是正文本身.
 */
data class PanelLayer(
    val id: String,
    val kind: PanelLayerKind,
    val content: String,
    val pin: PanelLayerPin = PanelLayerPin.NONE,
    val fixed: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind.name)
        put("content", content)
        put("pin", pin.name)
        put("fixed", fixed)
    }

    companion object {
        fun fromJson(json: JSONObject): PanelLayer = PanelLayer(
            id = json.optString("id"),
            kind = runCatching { PanelLayerKind.valueOf(json.optString("kind")) }
                .getOrDefault(PanelLayerKind.TEXT),
            content = json.optString("content"),
            pin = runCatching { PanelLayerPin.valueOf(json.optString("pin")) }
                .getOrDefault(PanelLayerPin.NONE),
            fixed = json.optBoolean("fixed")
        )

        fun listToJson(layers: List<PanelLayer>): String =
            JSONArray().also { array -> layers.forEach { array.put(it.toJson()) } }.toString()

        fun listFromJson(raw: String?): List<PanelLayer> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val layer = fromJson(item)
                        if (layer.id.isNotEmpty()) add(layer)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
