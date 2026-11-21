package com.qiqi.floatshow.data

import org.json.JSONArray
import org.json.JSONObject

/** 层的内容类型. 只有这两种, 不再区分"背景层"与"叠加层"本身 */
enum class PanelLayerKind { IMAGE, TEXT }

/**
 * 优先级标记. 规则:
 *  · TOP 全局最多一个, BOTTOM 全局最多一个, 两者彼此不互斥.
 *  · 同一个层不能同时带 TOP 与 BOTTOM.
 *  · 因此当某层是 TOP 时: 该层不能再置底; 别的层不能置顶, 但仍可以置底.
 *  · 固定只是标记, 目前不影响任何行为, 可与 TOP/BOTTOM 叠加, 数量不限.
 */
enum class PanelLayerPin { NONE, TOP, BOTTOM }

/**
 * 面板里的一层.
 * 列表顺序即层级顺序: 下标 0 是最底层(背景层), 下标越大越靠上, 后导入的压在先导入的上面.
 * 但置顶/置底的标记会改写实际层级: 带 BOTTOM 的层落到最底(它就成了背景层),
 * 带 TOP 的层升到最上; 标记取消后层回到它原来的位置.
 * [content] 对图片是 content:// uri, 对文本是正文本身. 替换只换内容, 不改层级.
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
