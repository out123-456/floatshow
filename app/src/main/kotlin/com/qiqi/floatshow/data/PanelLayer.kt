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
    /** 显示名. 同一时刻列表内不允许重名, 改名要经过 [PanelLayers.isNameTaken] 检查 */
    val name: String,
    val kind: PanelLayerKind,
    val content: String,
    val pin: PanelLayerPin = PanelLayerPin.NONE,
    val fixed: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("content", content)
        put("pin", pin.name)
        put("fixed", fixed)
    }

    companion object {
        fun fromJson(json: JSONObject): PanelLayer = PanelLayer(
            id = json.optString("id"),
            name = json.optString("name"),
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
                        if (layer.id.isEmpty()) continue
                        add(if (layer.name.isBlank()) layer.copy(name = PanelLayers.fallbackName(layer)) else layer)
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * 层列表的纯逻辑: 移动、标记、替换、删除.
 * 列表顺序 = 原堆内位置, 索引 0 是最底层.
 * 置顶/置底只打标记, 行本身不动; 实际渲染层级由 [renderOrder] 决定.
 */
object PanelLayers {

    /** 渲染顺序: BOTTOM 堆在最底, 无标记居中, TOP 堆在最上; 各堆内保持列表顺序 */
    fun renderOrder(layers: List<PanelLayer>): List<PanelLayer> = buildList {
        addAll(layers.filter { it.pin == PanelLayerPin.BOTTOM })
        addAll(layers.filter { it.pin == PanelLayerPin.NONE })
        addAll(layers.filter { it.pin == PanelLayerPin.TOP })
    }

    /** 该层现在能不能置顶: 已置底的层不能, 已经有别的层置顶时也不能; 已置顶时允许再次点击取消 */
    fun canPinTop(layers: List<PanelLayer>, id: String): Boolean {
        val layer = layers.firstOrNull { it.id == id } ?: return false
        return when (layer.pin) {
            PanelLayerPin.TOP -> true
            PanelLayerPin.BOTTOM -> false
            PanelLayerPin.NONE -> layers.none { it.pin == PanelLayerPin.TOP }
        }
    }

    fun canPinBottom(layers: List<PanelLayer>, id: String): Boolean {
        val layer = layers.firstOrNull { it.id == id } ?: return false
        return when (layer.pin) {
            PanelLayerPin.BOTTOM -> true
            PanelLayerPin.TOP -> false
            PanelLayerPin.NONE -> layers.none { it.pin == PanelLayerPin.BOTTOM }
        }
    }

    fun togglePinTop(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        if (!canPinTop(layers, id)) layers
        else layers.map { if (it.id == id) it.copy(pin = it.pin.toggle(PanelLayerPin.TOP)) else it }

    fun togglePinBottom(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        if (!canPinBottom(layers, id)) layers
        else layers.map { if (it.id == id) it.copy(pin = it.pin.toggle(PanelLayerPin.BOTTOM)) else it }

    /** 固定只是标记, 不影响层级 */
    fun toggleFixed(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(fixed = !it.fixed) else it }

    /** 上移 = 往层级高的方向(列表靠后)挪一格; 索引 0 不能上移 */
    fun moveUp(layers: List<PanelLayer>, id: String): List<PanelLayer> {
        val index = layers.indexOfFirst { it.id == id }
        if (index <= 0) return layers
        return layers.swap(index, index - 1)
    }

    /** 下移 = 往层级低的方向挪一格; 末项不能下移 */
    fun moveDown(layers: List<PanelLayer>, id: String): List<PanelLayer> {
        val index = layers.indexOfFirst { it.id == id }
        if (index < 0 || index >= layers.lastIndex) return layers
        return layers.swap(index, index + 1)
    }

    fun canMoveUp(layers: List<PanelLayer>, id: String): Boolean =
        layers.indexOfFirst { it.id == id } > 0

    fun canMoveDown(layers: List<PanelLayer>, id: String): Boolean {
        val index = layers.indexOfFirst { it.id == id }
        return index >= 0 && index < layers.lastIndex
    }

    /** 整层替换: 内容与类型都换, 但层级、标记、固定、名字保持原样 */
    fun replaceWith(layers: List<PanelLayer>, id: String, replacement: PanelLayer): List<PanelLayer> =
        layers.map {
            if (it.id == id) replacement.copy(
                id = it.id,
                name = it.name,
                pin = it.pin,
                fixed = it.fixed
            ) else it
        }

    /** 替换只换内容, 层级、标记、固定一律不动 */
    fun replaceContent(layers: List<PanelLayer>, id: String, content: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(content = content) else it }

    fun remove(layers: List<PanelLayer>, id: String): List<PanelLayer> = layers.filterNot { it.id == id }

    /** 名称是否已被别的层占用([excludeId] 是正在改名的那个层) */
    fun isNameTaken(layers: List<PanelLayer>, name: String, excludeId: String? = null): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return true
        return layers.any { it.id != excludeId && it.name == trimmed }
    }

    /** 默认名: 图片取文件名, 文本取前几个字; 重名时自动加序号 */
    fun fallbackName(layer: PanelLayer): String {
        val base = when (layer.kind) {
            PanelLayerKind.IMAGE -> {
                val raw = layer.content.substringAfterLast('/').substringAfterLast('%')
                raw.substringBeforeLast('.').trim().ifEmpty { "图片层" }
            }
            PanelLayerKind.TEXT -> layer.content.trim().take(12).ifEmpty { "文本层" }
        }
        return base
    }

    /** 加入列表并保证不重名 */
    fun add(layers: List<PanelLayer>, layer: PanelLayer): List<PanelLayer> {
        val desired = layer.name.trim().ifEmpty { fallbackName(layer) }
        var name = desired
        var index = 2
        while (isNameTaken(layers, name)) {
            name = desired + " " + index
            index++
        }
        return layers + layer.copy(name = name)
    }

    /** 改名. 重名或空名返回原列表, 由调用方提示 */
    fun rename(layers: List<PanelLayer>, id: String, newName: String): List<PanelLayer> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || isNameTaken(layers, trimmed, id)) return layers
        return layers.map { if (it.id == id) it.copy(name = trimmed) else it }
    }

    /** 只有最底层(索引 0)才是背景层 */
    fun hasBackground(layers: List<PanelLayer>): Boolean = layers.isNotEmpty()

    private fun List<PanelLayer>.swap(a: Int, b: Int): List<PanelLayer> {
        val list = toMutableList()
        val tmp = list[a]
        list[a] = list[b]
        list[b] = tmp
        return list
    }

    private fun PanelLayerPin.toggle(target: PanelLayerPin): PanelLayerPin =
        if (this == target) PanelLayerPin.NONE else target
}
