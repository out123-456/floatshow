package com.qiqi.floatshow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import kotlin.math.abs

/**
 * 一行设置: 左标签 + 右侧当前值(胶囊点击区, 点开 Dialog 精确输入), 下面一条滑块, 两端标出上下限.
 *
 * @param valueRange 输入框允许的完整范围
 * @param sliderRange 滑块覆盖的范围; 上限可以随当前值扩展, 撤销输入后自然回落
 * @param splitAt 分段点: 上限超过它的两倍时, 从下限到它的这一段固定占滑条的一半
 * @param snapTarget 只吸附这一个值(滑到附近时直接对上), null 表示不吸附
 * @param snapTolerance 吸附的容差
 */
@Composable
fun SettingSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String,
    dialogTitle: String = label,
    allowDecimal: Boolean = false,
    sliderRange: ClosedFloatingPointRange<Float> = valueRange,
    splitAt: Float? = null,
    snapTarget: Float? = null,
    snapTolerance: Float = 0f
) {
    var showDialog by remember { mutableStateOf(false) }

    val low = sliderRange.start
    // 上限由"松开时的值"决定: 拖动过程中锁死, 否则映射会跟着手指自激抖动
    val high = maxOf(sliderRange.endInclusive, value).coerceAtMost(valueRange.endInclusive)
    var dragging by remember { mutableStateOf(false) }
    var dragHigh by remember { mutableFloatStateOf(high) }
    var dragPosition by remember { mutableFloatStateOf(toPosition(value, low, high, splitAt)) }
    var lastValue by remember { mutableFloatStateOf(value) }

    val idlePosition = toPosition(value, low, high, splitAt)
    val position by animateFloatAsState(
        targetValue = if (dragging) dragPosition else idlePosition,
        animationSpec = tween(durationMillis = 220),
        label = "slider-position"
    )

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showDialog = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Slider(
            value = position,
            onValueChange = { raw ->
                if (!dragging) {
                    dragging = true
                    dragHigh = high
                }
                dragPosition = raw
                val dragged = fromPosition(raw, low, dragHigh, splitAt)
                val clamped = dragged.coerceIn(low, dragHigh)
                lastValue = if (allowDecimal) clamped else clamped.toInt().toFloat()
                onChange(lastValue)
            },
            onValueChangeFinished = {
                dragging = false
                // 松手才吸附, 松开后动画回弹到吸附位置
                if (snapTarget != null && abs(lastValue - snapTarget) <= snapTolerance) {
                    lastValue = snapTarget
                    onChange(snapTarget)
                }
            },
            valueRange = 0f..1f
        )
        RangeLabels(start = format(low), end = format(high))
    }

    if (showDialog) {
        NumberDialog(
            title = dialogTitle,
            initial = value,
            valueRange = valueRange,
            allowDecimal = allowDecimal,
            onConfirm = {
                onChange(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

/** 滑块两端的最小/最大标志 */
@Composable
private fun RangeLabels(start: String, end: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = start,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = end,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** 数值 -> 滑块位置(0..1) */
internal fun toPosition(value: Float, low: Float, high: Float, splitAt: Float?): Float {
    if (high <= low) return 0f
    val v = value.coerceIn(low, high)
    if (splitAt != null && splitAt > low && high > splitAt * 2f) {
        return if (v <= splitAt) {
            (v - low) / (splitAt - low) * 0.5f
        } else {
            0.5f + 0.5f * (v - splitAt) / (high - splitAt)
        }.coerceIn(0f, 1f)
    }
    return (v - low) / (high - low)
}

/** 滑块位置(0..1) -> 数值 */
internal fun fromPosition(position: Float, low: Float, high: Float, splitAt: Float?): Float {
    if (high <= low) return low
    val t = position.coerceIn(0f, 1f)
    if (splitAt != null && splitAt > low && high > splitAt * 2f) {
        return if (t <= 0.5f) {
            low + (splitAt - low) * (t * 2f)
        } else {
            splitAt + (high - splitAt) * ((t - 0.5f) * 2f)
        }
    }
    return low + (high - low) * t
}

@Composable
private fun NumberDialog(
    title: String,
    initial: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    allowDecimal: Boolean,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(trimNumber(initial, allowDecimal)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { raw -> text = sanitize(raw, allowDecimal) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
                ),
                supportingText = {
                    Text(
                        text = stringResource(
                            R.string.dialog_range_format,
                            trimNumber(valueRange.start, allowDecimal),
                            trimNumber(valueRange.endInclusive, allowDecimal)
                        )
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = text.toFloatOrNull()
                if (parsed != null) {
                    onConfirm(parsed.coerceIn(valueRange.start, valueRange.endInclusive))
                } else {
                    onDismiss()
                }
            }) { Text(text = stringResource(R.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    )
}

fun sanitize(raw: String, allowDecimal: Boolean): String {
    val allowed = if (allowDecimal) setOf('.') else emptySet()
    return raw.filter { it.isDigit() || it in allowed }
}

fun trimNumber(value: Float, allowDecimal: Boolean): String =
    if (allowDecimal) {
        String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    } else {
        value.toInt().toString()
    }
