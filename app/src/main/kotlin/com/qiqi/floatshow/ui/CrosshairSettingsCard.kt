package com.qiqi.floatshow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.SettingsRepository
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 准星段.
 * 标题项直接放在外层 Card 里(不套卡片), 其余每项各自一张 ElevatedCard.
 * 图片相关的选择/旋转/镜像合成一张卡片, 只有选过图才展开完整内容.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrosshairSettingsCard(
    settings: SettingsRepository,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    hasImage: Boolean,
    onPickImage: () -> Unit,
    onResetImage: () -> Unit,
    screenWidth: Int,
    screenHeight: Int
) {
    var size by remember { mutableFloatStateOf(settings.crosshairSize) }
    var offsetX by remember { mutableFloatStateOf(settings.crosshairOffsetX.toFloat()) }
    var offsetY by remember { mutableFloatStateOf(settings.crosshairOffsetY.toFloat()) }
    var alphaPercent by remember { mutableFloatStateOf(settings.crosshairAlpha * 100f) }
    var notch by remember { mutableStateOf(settings.crosshairNotchMode) }
    var rotation by remember { mutableFloatStateOf(settings.crosshairRotation) }
    var flipH by remember { mutableStateOf(settings.crosshairFlipH) }
    var flipV by remember { mutableStateOf(settings.crosshairFlipV) }

    val pxFormat = stringResource(R.string.unit_px_format)
    val percentFormat = stringResource(R.string.unit_percent_format)
    val degreeFormat = stringResource(R.string.unit_degree_format)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            // 标题项不套卡片
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.section_crosshair),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                supportingContent = {
                    // 描述为空时整行不渲染, 否则会留一段空白
                    val desc = stringResource(R.string.section_crosshair_desc)
                    if (desc.isNotEmpty()) {
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 图片组: 选择 / 旋转 / 镜像 同在一张卡片
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.crosshair_image),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                },
                                trailingContent = {
                                    ImageActions(
                                        hasImage = hasImage,
                                        onResetImage = onResetImage,
                                        onPickImage = onPickImage
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )

                            AnimatedVisibility(
                                visible = hasImage,
                                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
                            ) {
                              Column {
                                Divider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                    thickness = 1.dp
                                )
                                // 旋转放在列表下方
                                SettingSliderRow(
                                    label = stringResource(R.string.crosshair_rotate),
                                    value = rotation,
                                    valueRange = 0f..SettingsRepository.MAX_ROTATION,
                                    onChange = {
                                        rotation = it
                                        settings.crosshairRotation = it
                                    },
                                    format = {
                                        String.format(
                                            Locale.US,
                                            degreeFormat,
                                            it.roundToInt().toString()
                                        )
                                    }
                                )
                                MirrorRow(
                                    flipH = flipH,
                                    onFlipHChange = {
                                        flipH = it
                                        settings.crosshairFlipH = it
                                    },
                                    flipV = flipV,
                                    onFlipVChange = {
                                        flipV = it
                                        settings.crosshairFlipV = it
                                    }
                                )
                              }
                            }
                        }
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        SettingSliderRow(
                            label = stringResource(R.string.crosshair_size),
                            value = size,
                            valueRange = SettingsRepository.MIN_SIZE..SettingsRepository.MAX_SIZE,
                            sliderRange = SettingsRepository.MIN_SIZE..SettingsRepository.SIZE_SOFT_MAX,
                            splitAt = SettingsRepository.SIZE_SOFT_MAX,
                            snapTarget = SettingsRepository.SIZE_SOFT_MAX,
                            snapTolerance = SettingsRepository.SIZE_SNAP_TOLERANCE,
                            onChange = {
                                size = it.roundToInt().toFloat()
                                settings.crosshairSize = size
                            },
                            format = {
                                String.format(Locale.US, pxFormat, it.roundToInt().toString())
                            }
                        )
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        SettingSliderRow(
                            label = stringResource(R.string.crosshair_offset_x),
                            value = offsetX,
                            valueRange = -screenWidth.toFloat()..screenWidth.toFloat(),
                            onChange = {
                                offsetX = it
                                settings.crosshairOffsetX = it.roundToInt()
                            },
                            format = { String.format(Locale.US, pxFormat, pixels(it, false)) }
                        )
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        SettingSliderRow(
                            label = stringResource(R.string.crosshair_offset_y),
                            value = offsetY,
                            valueRange = -screenHeight.toFloat()..screenHeight.toFloat(),
                            onChange = {
                                offsetY = it
                                settings.crosshairOffsetY = it.roundToInt()
                            },
                            format = { String.format(Locale.US, pxFormat, pixels(it, false)) }
                        )
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        SettingSliderRow(
                            label = stringResource(R.string.crosshair_alpha),
                            value = alphaPercent,
                            valueRange = 0f..100f,
                            onChange = {
                                alphaPercent = it
                                settings.crosshairAlpha = it / 100f
                            },
                            format = {
                                String.format(
                                    Locale.US,
                                    percentFormat,
                                    it.roundToInt().toString()
                                )
                            }
                        )
                    }

                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.crosshair_notch),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = stringResource(R.string.crosshair_notch_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingContent = {
                                Switch(checked = notch, onCheckedChange = {
                                    notch = it
                                    settings.crosshairNotchMode = it
                                })
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}

/** 镜像: 水平/垂直两个复选框同一行, 复选框紧贴文字, 中间用竖线隔开 */
@Composable
private fun MirrorRow(
    flipH: Boolean,
    onFlipHChange: (Boolean) -> Unit,
    flipV: Boolean,
    onFlipVChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MirrorOption(
            label = stringResource(R.string.crosshair_flip_h),
            checked = flipH,
            onCheckedChange = onFlipHChange,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        MirrorOption(
            label = stringResource(R.string.crosshair_flip_v),
            checked = flipV,
            onCheckedChange = onFlipVChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MirrorOption(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Checkbox,
            onValueChange = onCheckedChange
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ImageActions(
    hasImage: Boolean,
    onResetImage: () -> Unit,
    onPickImage: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (hasImage) {
            TextButton(onClick = onResetImage) {
                Text(stringResource(R.string.crosshair_reset_image))
            }
        }
        Button(onClick = onPickImage) {
            Text(stringResource(R.string.crosshair_select_image))
        }
    }
}

private fun pixels(value: Float, allowDecimal: Boolean): String =
    if (allowDecimal) {
        val text = String.format(Locale.US, "%.2f", value)
        text.trimEnd('0').trimEnd('.')
    } else {
        value.roundToInt().toString()
    }
