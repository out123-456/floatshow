package com.qiqi.floatshow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.PanelLayer
import com.qiqi.floatshow.overlay.PanelContentSource
import com.qiqi.floatshow.data.PanelTextMode
import android.net.Uri
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Slider
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.rounded.MoreVert
import com.qiqi.floatshow.data.PanelLayerKind
import com.qiqi.floatshow.data.PanelLayerPin
import com.qiqi.floatshow.data.PanelLayers
import com.qiqi.floatshow.overlay.PanelVisibility

/**
 * 悬浮面板的内容区设置: 层列表 + 字体.
 * 只在面板开关打开时显示.
 */
@Composable
fun PanelContentSection(
    layers: List<PanelLayer>,
    onImportLayer: () -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTop: (String) -> Unit,
    onToggleBottom: (String) -> Unit,
    onToggleFixed: (String) -> Unit,
    onSetAnchor: (String, String?) -> Unit,
    onDelete: (String) -> Unit,
    onOpenLayer: (String) -> Unit,
    onRenameLayer: (String) -> Unit,
    onRotateLeft: (String) -> Unit,
    onRotateRight: (String) -> Unit,
    onToggleMirror: (String) -> Unit,
    onTextMode: (String, String) -> Unit,
    onSvgScale: (String, Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        layers.forEach { layer ->
            LayerRow(
                layer = layer,
                layers = layers,
                onMoveUp = { onMoveUp(layer.id) },
                onMoveDown = { onMoveDown(layer.id) },
                onToggleTop = { onToggleTop(layer.id) },
                onToggleBottom = { onToggleBottom(layer.id) },
                onToggleFixed = { onToggleFixed(layer.id) },
                onSetAnchor = { target -> onSetAnchor(layer.id, target) },
                onDelete = { onDelete(layer.id) },
                onOpen = { onOpenLayer(layer.id) },
                onRename = { onRenameLayer(layer.id) },
                onRotateLeft = { onRotateLeft(layer.id) },
                onRotateRight = { onRotateRight(layer.id) },
                onToggleMirror = { onToggleMirror(layer.id) },
                onTextMode = { mode -> onTextMode(layer.id, mode) },
                onSvgScale = { factor -> onSvgScale(layer.id, factor) }
            )
        }

        TextButton(
            onClick = onImportLayer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
            Text(
                text = stringResource(
                    if (layers.isEmpty()) R.string.panel_import_background
                    else R.string.panel_import_overlay
                )
            )
        }

    }
}

/** 一行一层: 类型图标 | 名称 ─ 上移 下移 置顶 置底 固定 删除 */
@Composable
private fun LayerRow(
    layer: PanelLayer,
    layers: List<PanelLayer>,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggleTop: () -> Unit,
    onToggleBottom: () -> Unit,
    onToggleFixed: () -> Unit,
    onSetAnchor: (String?) -> Unit,
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onToggleMirror: () -> Unit,
    onTextMode: (String) -> Unit,
    onSvgScale: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 点类型图标才展开抽屉, 否则点名字是改名.
        // 内容被拖到视窗之外时图标变错误色: 那行是用户唯一能摸到的入口, 点它进去就有"重置位置"
        val hidden = layer.id in PanelVisibility.invisibleLayerIds.value
        IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = layer.kind.icon(),
                contentDescription = stringResource(R.string.panel_layer_open),
                tint = if (hidden) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = layer.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                // 点名字改名, 点左边类型图标才展开抽屉
                .clickable(onClick = onRename)
                .padding(vertical = 8.dp, horizontal = 2.dp)
        )

        SmallAction(
            icon = Icons.Rounded.ArrowUpward,
            description = R.string.panel_layer_move_up,
            enabled = PanelLayers.canMoveUp(layers, layer.id),
            highlighted = false,
            onClick = onMoveUp
        )
        SmallAction(
            icon = Icons.Rounded.ArrowDownward,
            description = R.string.panel_layer_move_down,
            enabled = PanelLayers.canMoveDown(layers, layer.id),
            highlighted = false,
            onClick = onMoveDown
        )
        SmallAction(
            icon = Icons.Rounded.VerticalAlignTop,
            description = R.string.panel_layer_pin_top,
            enabled = PanelLayers.canPinTop(layers, layer.id),
            highlighted = layer.pin == PanelLayerPin.TOP,
            onClick = onToggleTop
        )
        SmallAction(
            icon = Icons.Rounded.VerticalAlignBottom,
            description = R.string.panel_layer_pin_bottom,
            enabled = PanelLayers.canPinBottom(layers, layer.id),
            highlighted = layer.pin == PanelLayerPin.BOTTOM,
            onClick = onToggleBottom
        )
        FixedAction(
            layer = layer,
            layers = layers,
            onToggleFixed = onToggleFixed,
            onSetAnchor = onSetAnchor
        )
        MoreAction(
            layer = layer,
            onRotateLeft = onRotateLeft,
            onRotateRight = onRotateRight,
            onToggleMirror = onToggleMirror,
            onTextMode = onTextMode,
            onSvgScale = onSvgScale,
            onDelete = onDelete
        )
    }
}

/**
 * 更多: 点开一个浮动面板, 里面是这一层的预处理与删除.
 * 删除放在最下面且是错误色, 需要二次确认.
 * 固定(锚点为面板)的层不能预处理, 对应的控件直接不出现.
 */
@Composable
private fun MoreAction(
    layer: PanelLayer,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onToggleMirror: () -> Unit,
    onTextMode: (String) -> Unit,
    onSvgScale: (Float) -> Unit,
    onDelete: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    val canPre = !layer.fixed
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(R.string.panel_layer_more),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (!confirming) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.panel_more_title),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (layer.kind == PanelLayerKind.TEXT) {
                        TextModeRow(layer.textMode, enabled = canPre, onChange = onTextMode)
                    } else if (canPre) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            OrientButton(
                                label = stringResource(R.string.panel_pre_mirror),
                                active = layer.mirrored,
                                onClick = onToggleMirror
                            )
                            OrientButton(
                                label = stringResource(R.string.panel_pre_rotate_left),
                                active = false,
                                onClick = onRotateLeft
                            )
                            OrientButton(
                                label = stringResource(R.string.panel_pre_rotate_right),
                                active = false,
                                onClick = onRotateRight
                            )
                        }
                        if (PanelContentSource.isSvg(context = LocalContext.current,
                                uri = Uri.parse(layer.content)) ) {
                            SvgScaleRow(layer, onSvgScale)
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.panel_pre_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { confirming = true }) {
                            Text(
                                text = stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        text = stringResource(R.string.panel_delete_confirm),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.panel_delete_confirm_desc, layer.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Row(
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { confirming = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(onClick = {
                            confirming = false
                            open = false
                            onDelete()
                        }) {
                            Text(
                                text = stringResource(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrientButton(label: String, active: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TextModeRow(
    current: String,
    enabled: Boolean,
    onChange: (String) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = current == PanelTextMode.PLAIN,
            onClick = { if (enabled) onChange(PanelTextMode.PLAIN) },
            enabled = enabled,
            label = { Text(stringResource(R.string.panel_pre_plain)) }
        )
        FilterChip(
            selected = current == PanelTextMode.MARKDOWN,
            onClick = { if (enabled) onChange(PanelTextMode.MARKDOWN) },
            enabled = enabled,
            label = { Text(stringResource(R.string.panel_pre_markdown)) }
        )
    }
}

/** SVG 的显示倍数: 1..16 档, 改的是这一层在面板里的宽度 */
@Composable
private fun SvgScaleRow(layer: PanelLayer, onScale: (Float) -> Unit) {
    val density = LocalDensity.current.density
    val side = layer.width.toFloat().takeIf { it > 0 } ?: (100f * density)
    val base = 100f * density
    val current = (side / base).coerceIn(1f, 16f)
    Column {
        Text(
            text = stringResource(R.string.panel_pre_scale) + " " + current.toInt() + "x",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = current,
            onValueChange = { onScale(it) },
            valueRange = 1f..16f
        )
    }
}

@Composable
private fun SmallAction(
    icon: ImageVector,
    description: Int,
    enabled: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            modifier = Modifier.size(20.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                highlighted -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * 字体卡片: 与准星、悬浮面板同级, 独占一张 Card.
 * 版式照抄未展开的悬浮面板卡片: 左边标题"字体", 中间字体名, 右边两个操作.
 * 字体加载失败时字体名标错误色, 而不是弹提示.
 */
@Composable
fun FontCard(
    fontName: String?,
    failed: Boolean,
    onPickFont: () -> Unit,
    onResetFont: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.panel_font)) },
            supportingContent = {
                Text(
                    text = fontName ?: stringResource(R.string.panel_font_system),
                    // 失败时用错误色标出字体名
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPickFont) {
                        Text(stringResource(R.string.panel_font_change))
                    }
                    if (fontName != null) {
                        TextButton(onClick = onResetFont) {
                            Text(stringResource(R.string.panel_font_reset))
                        }
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
fun PanelLayerKind.icon(): ImageVector = when (this) {
    PanelLayerKind.IMAGE -> Icons.Rounded.Image
    PanelLayerKind.TEXT -> Icons.Rounded.Notes
}
