package com.qiqi.floatshow.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.PanelLayer
import com.qiqi.floatshow.data.PanelLayerKind
import com.qiqi.floatshow.data.PanelLayerPin
import com.qiqi.floatshow.data.PanelLayers

/**
 * 悬浮面板的内容区设置: 层列表 + 字体.
 * 只在面板开关打开时显示.
 */
@Composable
fun PanelContentSection(
    layers: List<PanelLayer>,
    fontName: String?,
    onImportLayer: () -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onToggleTop: (String) -> Unit,
    onToggleBottom: (String) -> Unit,
    onToggleFixed: (String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenLayer: (String) -> Unit,
    onRenameLayer: (String) -> Unit,
    onPickFont: () -> Unit,
    onResetFont: () -> Unit,
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
                onDelete = { onDelete(layer.id) },
                onOpen = { onOpenLayer(layer.id) },
                onRename = { onRenameLayer(layer.id) }
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

        HorizontalDivider()

        FontRow(fontName = fontName, onPickFont = onPickFont, onResetFont = onResetFont)
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
    onDelete: () -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 点类型图标才展开抽屉, 否则点名字是改名
        IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = layer.kind.icon(),
                contentDescription = stringResource(R.string.panel_layer_open),
                tint = MaterialTheme.colorScheme.onSurface
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
        SmallAction(
            icon = Icons.Rounded.PushPin,
            description = R.string.panel_layer_fixed,
            enabled = true,
            highlighted = layer.fixed,
            onClick = onToggleFixed
        )
        SmallAction(
            icon = Icons.Rounded.Delete,
            description = R.string.panel_layer_delete,
            enabled = true,
            highlighted = false,
            onClick = onDelete
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

@Composable
private fun FontRow(
    fontName: String?,
    onPickFont: () -> Unit,
    onResetFont: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.panel_font),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = fontName ?: stringResource(R.string.panel_font_system),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 4.dp)
        )
        TextButton(onClick = onPickFont) { Text(stringResource(R.string.panel_font_change)) }
        TextButton(onClick = onResetFont, enabled = fontName != null) {
            Text(stringResource(R.string.panel_font_reset))
        }
    }
}

@Composable
fun PanelLayerKind.icon(): ImageVector = when (this) {
    PanelLayerKind.IMAGE -> Icons.Rounded.Image
    PanelLayerKind.TEXT -> Icons.Rounded.Notes
}
