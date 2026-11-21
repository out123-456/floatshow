package com.qiqi.floatshow.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.PanelLayer
import com.qiqi.floatshow.data.PanelLayerKind
import com.qiqi.floatshow.data.PanelContentPicker

/**
 * 一层的抽屉: 顶部"替换内容"按钮, 下面 SVG 倍数那一行, 中间预览, 底部信息.
 * 文本层的预览就是可编辑输入框, 失焦或关闭抽屉时写回.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerSheet(
    layer: PanelLayer,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
    onTextChanged: (String) -> Unit,
    onOpenZoom: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var svgScale by remember(layer.id) { mutableStateOf(1f) }
    var imageSize by remember(layer.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    var loaded by remember(layer.id) { mutableStateOf(false) }
    var text by remember(layer.id) { mutableStateOf(layer.content) }
    var textDirty by remember(layer.id) { mutableStateOf(false) }

    val context = LocalContext.current
    val uri = layer.content.takeIf { layer.kind == PanelLayerKind.IMAGE }?.let(Uri::parse)
    // 用内容提供者的类型判断, 不能只看扩展名: 有些文件选择器给的 uri 不带后缀
    val isSvg = uri != null && PanelContentPicker.isSvg(context, uri)

    // 抽屉任何形式的关闭都要把文本改动落地, 否则输入会白费
    fun flushText() {
        if (textDirty) {
            onTextChanged(text)
            textDirty = false
        }
    }

    DisposableEffect(layer.id) {
        onDispose { flushText() }
    }

    ModalBottomSheet(
        onDismissRequest = {
            flushText()
            onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onReplace) {
                    Text(stringResource(R.string.panel_layer_sheet_replace))
                }
                TextButton(onClick = {
                    flushText()
                    onDelete()
                }) { Text(stringResource(R.string.panel_layer_sheet_delete)) }
            }

            // SVG 倍数: 顶部按钮下面单独一行
            if (isSvg) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.panel_svg_scale, ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    listOf(1f, 2f, 4f, 8f).forEach { factor ->
                        FilterChip(
                            selected = svgScale == factor,
                            onClick = { svgScale = factor },
                            label = {
                                Text(
                                    stringResource(
                                        R.string.panel_svg_scale_x,
                                        factor.toInt().toString()
                                    )
                                )
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            if (layer.kind == PanelLayerKind.TEXT) {
                // 文本编辑框自身可滚动, 抽屉不再套滚动, 免得两层滚动打架
                // 失焦即写回: 抽屉关闭、点别处、切页都会触发
                val focusManager = LocalFocusManager.current
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        textDirty = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onFocusChanged { state ->
                            if (!state.isFocused) flushText()
                        },
                    placeholder = { Text(stringResource(R.string.panel_layer_edit_hint)) },
                    // 不自动换行: 长文本靠横向滚动
                    singleLine = false,
                    maxLines = Int.MAX_VALUE
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    AndroidView(
                        factory = { context ->
                            LayerPreviewView(context).also { view ->
                                view.onLoaded = { w, h ->
                                    imageSize = w to h
                                    loaded = true
                                }
                                view.load(uri, isSvg)
                            }
                        },
                        update = { view ->
                            view.setSvgScale(svgScale)
                            view.load(uri, isSvg)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 4.dp)
                            .clickable(enabled = uri != null) {
                                if (uri != null) onOpenZoom(uri)
                            }
                    )
                }
            }

            val info = when {
                layer.kind == PanelLayerKind.TEXT ->
                    stringResource(R.string.panel_layer_info_text, layer.name, text.length)
                !loaded -> stringResource(R.string.panel_layer_info_loading)
                imageSize == null -> stringResource(R.string.panel_layer_info_failed)
                else -> stringResource(
                    R.string.panel_layer_info_image,
                    layer.name,
                    imageSize!!.first,
                    imageSize!!.second
                )
            }
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}
