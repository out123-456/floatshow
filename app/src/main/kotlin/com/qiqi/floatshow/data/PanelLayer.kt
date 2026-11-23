package com.qiqi.floatshow.data

import org.json.JSONArray
import org.json.JSONObject

/** 层的内容类型. 只有这两种, 不再区分"背景层"与"叠加层"本身 */
enum class PanelLayerKind { IMAGE, TEXT }

/** 文本层的渲染方式 */
object PanelTextMode {
    const val PLAIN = "plain"
    const val MARKDOWN = "markdown"
}

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
    /** 锚点标签: true 表示这层被钉在某个坐标系里, 不参与拖动与缩放 */
    val fixed: Boolean = false,
    /** 锚点层 id; null 表示锚点就是面板本身 */
    val anchorId: String? = null,
    /**
     * 面板内的位置与尺寸(像素, 相对面板左上角).
     * [width] 或 [height] <= 0 表示"还没定过" — 由渲染端按内容自身的天然尺寸填一次.
     */
    val x: Int = 0,
    val y: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** 旋转档位 0..3, 每档 90 度; 与 [mirrored] 一起构成 8 种朝向 */
    val rotation: Int = 0,
    /** 左右镜像; 与旋转互不排斥 */
    val mirrored: Boolean = false,
    /** 文本渲染方式, 见 [PanelTextMode] */
    val textMode: String = PanelTextMode.PLAIN
) {
    /** 归一化后的朝向索引 0..7, 用于"8 状态空间" */
    val orientation: Int get() = (rotation and 3) * 2 + if (mirrored) 1 else 0

    fun withOrientation(index: Int): PanelLayer = copy(
        rotation = (index / 2) and 3,
        mirrored = index % 2 == 1
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("kind", kind.name)
        put("content", content)
        put("pin", pin.name)
        put("fixed", fixed)
        put("anchor", anchorId ?: "")
        put("x", x)
        put("y", y)
        put("w", width)
        put("h", height)
        put("rot", rotation)
        put("mirror", mirrored)
        put("textMode", textMode)
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
            fixed = json.optBoolean("fixed"),
            anchorId = json.optString("anchor").ifBlank { null },
            x = json.optInt("x"),
            y = json.optInt("y"),
            width = json.optInt("w"),
            height = json.optInt("h"),
            rotation = json.optInt("rot") and 3,
            mirrored = json.optBoolean("mirror"),
            textMode = json.optString("textMode").ifBlank { PanelTextMode.PLAIN }
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

    /** 左旋一档(每次 90 度, 4 档循环) */
    fun rotateLeft(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(rotation = (it.rotation + 3) and 3) else it }

    fun rotateRight(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(rotation = (it.rotation + 1) and 3) else it }

    fun toggleMirror(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(mirrored = !it.mirrored) else it }

    fun setTextMode(layers: List<PanelLayer>, id: String, mode: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(textMode = mode) else it }

    /** 改了尺寸(面板里的像素) */
    fun resize(layers: List<PanelLayer>, id: String, w: Int, h: Int): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(width = w, height = h) else it }

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

    // ── 锚点(相对固定) ─────────────────────────────────────

    /**
     * 能不能让 [id] 锚到 [targetId]: 不能锚自己, 也不能锚自己的后代(会成环).
     * 锚点为面板(=null)不需要检测.
     */
    fun canAnchor(layers: List<PanelLayer>, id: String, targetId: String?): Boolean {
        if (targetId == null) return true
        if (targetId == id) return false
        return !isAncestor(layers, ancestor = id, of = targetId)
    }

    /** [ancestor] 是不是 [of] 的祖先(逐级向上追) */
    fun isAncestor(layers: List<PanelLayer>, ancestor: String, of: String): Boolean {
        var cursor = layers.firstOrNull { it.id == of }?.anchorId
        var guard = 0
        while (cursor != null && guard++ < 64) {
            if (cursor == ancestor) return true
            cursor = layers.firstOrNull { it.id == cursor }?.anchorId
        }
        return false
    }

    /** 某个层的直接子层 */
    fun childrenOf(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.filter { it.anchorId == id }

    /**
     * 移动一个锚点是面板的层. 它创建的锚点(如果有子层挂在上面)原点跟着走,
     * 锚点自己负责把位移广播给订阅者; 创建者只发这一次, 不去遍历子层.
     *
     * 返回新的列表; 锚点是面板的层(没有创建锚点)直接跳过, 一个事件都不发.
     */
    fun move(layers: List<PanelLayer>, id: String, x: Int, y: Int): List<PanelLayer> {
        val target = layers.firstOrNull { it.id == id } ?: return layers
        if (target.anchorId != null) {
            // 有父层: 偏移是相对锚点的, 移动它没有意义
            return layers
        }
        val dx = x - target.x
        val dy = y - target.y
        moveAnchor(layers, id, dx, dy)
        return layers.map { if (it.id == id) it.copy(x = x, y = y) else it }
    }

    /**
     * 锚点 [anchorId] 的创建者移动了 (dx, dy), 把位移广播给所有订阅者.
     * 一次性重算, 没有递归: 订阅者之间互不影响.
     */
    fun moveAnchor(layers: List<PanelLayer>, anchorId: String, dx: Int, dy: Int): List<PanelLayer> {
        if (dx == 0 && dy == 0) return layers
        val subscribers = layers.filter { it.anchorId == anchorId }
        if (subscribers.isEmpty()) return layers
        return layers.map { layer ->
            if (layer.anchorId != anchorId) layer
            else layer.copy(x = layer.x + dx, y = layer.y + dy)
        }
    }

    /**
     * 重置一个层自己的偏移: 回到它锚点的原点.
     * 有父层的层不允许重置位置(它的偏移是相对锚点的, 一重置就会跳)。
     */
    fun resetPosition(layers: List<PanelLayer>, id: String): List<PanelLayer> {
        val target = layers.firstOrNull { it.id == id } ?: return layers
        if (target.anchorId != null) return layers
        val dx = -target.x
        val dy = -target.y
        moveAnchor(layers, id, dx, dy)
        return layers.map {
            if (it.id == id) it.copy(x = 0, y = 0) else it
        }
    }

    /** 锚点层: anchorId 为 null 的那些(它们的锚点是面板) */
    fun roots(layers: List<PanelLayer>): List<PanelLayer> = layers.filter { it.anchorId == null }

    fun setAnchor(layers: List<PanelLayer>, id: String, targetId: String?): List<PanelLayer> {
        if (!canAnchor(layers, id, targetId)) return layers
        return layers.map {
            if (it.id != id) it
            else it.copy(anchorId = targetId, fixed = true)
        }
    }

    fun clearAnchor(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.map { if (it.id == id) it.copy(anchorId = null, fixed = false) else it }

    /** 解除这一层的全部子层(它们回到锚点=面板) */
    fun detachChildren(layers: List<PanelLayer>, id: String): List<PanelLayer> =
        layers.map { if (it.anchorId == id) it.copy(anchorId = null, fixed = false) else it }

    /** 有子层的层不能删(先解除); 锚点为面板的层本身可以删 */
    fun canDelete(layers: List<PanelLayer>, id: String): Boolean =
        layers.none { it.anchorId == id }

    /**
     * 改尺寸时所有后代按同一比例缩放: 相对距离与大小同步改变.
     * 锚点是"层对层"的转接点, 所以比例要一路传下去.
     */
    fun resizeWithChildren(layers: List<PanelLayer>, id: String, w: Int, h: Int): List<PanelLayer> {
        val target = layers.firstOrNull { it.id == id } ?: return layers
        val oldW = target.width
        val oldH = target.height
        if (oldW <= 0 || oldH <= 0 || w <= 0 || h <= 0) {
            return layers.map { if (it.id == id) it.copy(width = w, height = h) else it }
        }
        val kx = w.toFloat() / oldW
        val ky = h.toFloat() / oldH
        val out = layers.toMutableList()
        val index = out.indexOfFirst { it.id == id }
        if (index >= 0) out[index] = out[index].copy(width = w, height = h)
        scaleChildren(out, id, kx, ky)
        return out.toList()
    }

    /** 递归把 [parentId] 的整棵子树按比例缩放 */
    private fun scaleChildren(holder: MutableList<PanelLayer>, parentId: String, kx: Float, ky: Float) {
        for (i in holder.indices) {
            val layer = holder[i]
            if (layer.anchorId != parentId) continue
            holder[i] = layer.copy(
                x = (layer.x * kx).toInt(),
                y = (layer.y * ky).toInt(),
                width = if (layer.width > 0) (layer.width * kx).toInt() else layer.width,
                height = if (layer.height > 0) (layer.height * ky).toInt() else layer.height
            )
            scaleChildren(holder, layer.id, kx, ky)
        }
    }

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

    fun remove(layers: List<PanelLayer>, id: String): List<PanelLayer> {
        // 删掉一个层时, 它的子层回到"锚点=面板", 避免悬空引用
        val next = layers.map { if (it.anchorId == id) it.copy(anchorId = null, fixed = true) else it }
        return next.filterNot { it.id == id }
    }

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
