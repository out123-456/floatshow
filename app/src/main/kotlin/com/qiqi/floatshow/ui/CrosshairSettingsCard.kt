package com.qiqi.floatshow.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
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
            ListItem(
                headlineContent = {
                    Text(
                        text = stringResource(R.string.section_crosshair),
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                supportingContent = {
                    Text(
                        text = stringResource(R.string.section_crosshair_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked ->
                            // 重新打开时把过小的值拉回默认, 避免恢复成看不见的小点
                            if (checked && size < SettingsRepository.SIZE_SOFT_MAX) {
                                size = SettingsRepository.SIZE_SOFT_MAX
                                settings.crosshairSize = SettingsRepository.SIZE_SOFT_MAX
                            }
                            onEnabledChange(checked)
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            // 直接 if 展开, 不用 AnimatedVisibility: 展开动画要每帧重排整列, 是开关卡顿的主因
            if (enabled) {
                SectionDivider()

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

                // 图片相关的变换跟在图片后面, 属于图片的子项
                if (hasImage) {
                    SectionDivider()
                    SettingSliderRow(
                        label = stringResource(R.string.crosshair_rotate),
                        value = rotation,
                        valueRange = 0f..SettingsRepository.MAX_ROTATION,
                        onChange = {
                            rotation = it
                            settings.crosshairRotation = it
                        },
                        format = { String.format(Locale.US, degreeFormat, it.roundToInt().toString()) }
                    )
                    SectionDivider()
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.crosshair_flip_h),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        supportingContent = {
                            Text(
                                text = stringResource(R.string.crosshair_transform_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            Switch(checked = flipH, onCheckedChange = {
                                flipH = it
                                settings.crosshairFlipH = it
                            })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                    SectionDivider()
                    ListItem(
                        headlineContent = {
                            Text(
                                text = stringResource(R.string.crosshair_flip_v),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        trailingContent = {
                            Switch(checked = flipV, onCheckedChange = {
                                flipV = it
                                settings.crosshairFlipV = it
                            })
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }

                SectionDivider()
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
                    format = { String.format(Locale.US, pxFormat, it.roundToInt().toString()) }
                )

                SectionDivider()
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

                SectionDivider()
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

                SectionDivider()
                SettingSliderRow(
                    label = stringResource(R.string.crosshair_alpha),
                    value = alphaPercent,
                    valueRange = 0f..100f,
                    onChange = {
                        alphaPercent = it
                        settings.crosshairAlpha = it / 100f
                    },
                    format = { String.format(Locale.US, percentFormat, it.roundToInt().toString()) }
                )

                SectionDivider()
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

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 分隔线: 用 MD3 里比背景略亮的描边色, 不抢内容 */
@Composable
private fun SectionDivider() {
    Divider(
        modifier = Modifier.padding(horizontal = 16.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
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
