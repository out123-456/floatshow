package com.qiqi.floatshow.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.SettingsRepository

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
    var size by remember { mutableIntStateOf(settings.crosshairSize) }
    var offsetX by remember { mutableFloatStateOf(settings.crosshairOffsetX.toFloat()) }
    var offsetY by remember { mutableFloatStateOf(settings.crosshairOffsetY.toFloat()) }
    var alpha by remember { mutableFloatStateOf(settings.crosshairAlpha) }
    var notch by remember { mutableStateOf(settings.crosshairNotchMode) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.section_crosshair),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.section_crosshair_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (!enabled) return@Column

            Divider()

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.crosshair_image),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (hasImage) {
                    TextButton(onClick = onResetImage) {
                        Text(stringResource(R.string.crosshair_reset_image))
                    }
                }
                OutlinedButton(onClick = onPickImage) {
                    Text(stringResource(R.string.crosshair_select_image))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.crosshair_size),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val next = (size - 2).coerceAtLeast(8)
                    size = next
                    settings.crosshairSize = next
                }) {
                    Icon(Icons.Rounded.Remove, contentDescription = null)
                }
                Text(text = "$size px", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = {
                    val next = (size + 2).coerceAtMost(256)
                    size = next
                    settings.crosshairSize = next
                }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                }
            }

            SliderRow(
                labelRes = R.string.crosshair_offset_x,
                value = offsetX,
                valueRange = -screenWidth.toFloat()..screenWidth.toFloat(),
                onChange = {
                    offsetX = it
                    settings.crosshairOffsetX = it.toInt()
                }
            )

            SliderRow(
                labelRes = R.string.crosshair_offset_y,
                value = offsetY,
                valueRange = -screenHeight.toFloat()..screenHeight.toFloat(),
                onChange = {
                    offsetY = it
                    settings.crosshairOffsetY = it.toInt()
                }
            )

            SliderRow(
                labelRes = R.string.crosshair_alpha,
                value = alpha,
                valueRange = 0f..1f,
                onChange = {
                    alpha = it
                    settings.crosshairAlpha = it
                },
                valueText = "${(alpha * 100).toInt()}%"
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.crosshair_notch),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.crosshair_notch_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = notch, onCheckedChange = {
                    notch = it
                    settings.crosshairNotchMode = it
                })
            }
        }
    }
}

@Composable
private fun SliderRow(
    @StringRes labelRes: Int,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    valueText: String = value.toInt().toString()
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(text = valueText, style = MaterialTheme.typography.bodySmall)
        }
        Slider(value = value, onValueChange = onChange, valueRange = valueRange)
    }
}
