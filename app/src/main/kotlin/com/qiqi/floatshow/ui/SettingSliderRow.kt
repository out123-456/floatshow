package com.qiqi.floatshow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一行设置: 上排是标签 + 可直接输入的下划线数值框, 下排是 `| 最小 | 滑块 | 最大 |`.
 *
 * @param valueRange 允许的完整范围
 * @param sliderRange 滑块本身覆盖的范围; 上限可以随当前值扩展, 撤销输入后自然回落
 * @param splitAt 分段点: 上限超过它的两倍时, 从下限到它的这一段固定占滑条的一半
 * @param snapTarget 松手时要吸附的那一个值, null 表示不吸附
 * @param snapTolerance 吸附容差
 */
@Composable
fun SettingSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    format: (Float) -> String,
    allowDecimal: Boolean = false,
    sliderRange: ClosedFloatingPointRange<Float> = valueRange,
    splitAt: Float? = null,
    snapTarget: Float? = null,
    snapTolerance: Float = 0f
) {
    val low = sliderRange.start
    // 上限由"松开时的值"决定: 拖动过程中锁死, 否则映射跟着手指自激
    val high = maxOf(sliderRange.endInclusive, value).coerceAtMost(valueRange.endInclusive)
    var dragging by remember { mutableStateOf(false) }
    var dragHigh by remember { mutableFloatStateOf(high) }
    var position by remember { mutableFloatStateOf(toPosition(value, low, high, splitAt)) }

    // 外部值变了(输入框、重置)且不在拖动, 就把滑块挪过去
    LaunchedEffect(value, low, high, splitAt, dragging) {
        if (!dragging) position = toPosition(value, low, high, splitAt)
    }

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            InlineNumberField(
                value = value,
                valueRange = valueRange,
                allowDecimal = allowDecimal,
                onCommit = onChange
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = format(low),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = position,
                onValueChange = { raw ->
                    if (!dragging) {
                        dragging = true
                        dragHigh = high
                    }
                    position = raw
                    val dragged = fromPosition(raw, low, dragHigh, splitAt)
                    val clamped = dragged.coerceIn(low, dragHigh)
                    onChange(if (allowDecimal) clamped else clamped.roundToInt().toFloat())
                },
                onValueChangeFinished = {
                    dragging = false
                    if (snapTarget != null && abs(value - snapTarget) <= snapTolerance) {
                        onChange(snapTarget)
                    }
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = format(high),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 下划线数值输入框: 边输边生效, 不需要再开对话框 */
@Composable
private fun InlineNumberField(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    allowDecimal: Boolean,
    onCommit: (Float) -> Unit
) {
    var text by remember { mutableStateOf(trimNumber(value, allowDecimal)) }

    // 只有外部值变化(滑条、重置)才回写文字, 输入过程中的每次回调不再重置光标
    LaunchedEffect(value) {
        if (text.toFloatOrNull() != value) text = trimNumber(value, allowDecimal)
    }

    Column(horizontalAlignment = Alignment.End) {
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val cleaned = sanitize(raw, allowDecimal)
                text = cleaned
                cleaned.toFloatOrNull()?.let {
                    onCommit(it.coerceIn(valueRange.start, valueRange.endInclusive))
                }
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (allowDecimal) KeyboardType.Decimal else KeyboardType.Number
            ),
            modifier = Modifier.width(72.dp)
        )
        Divider(
            color = MaterialTheme.colorScheme.outline,
            thickness = 1.dp
        )
    }
}

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
    return ((v - low) / (high - low)).coerceIn(0f, 1f)
}

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

fun sanitize(raw: String, allowDecimal: Boolean): String {
    val allowed = if (allowDecimal) setOf('.', '-') else setOf('-')
    return raw.filter { it.isDigit() || it in allowed }
}

fun trimNumber(value: Float, allowDecimal: Boolean): String =
    if (allowDecimal) {
        String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    } else {
        value.toInt().toString()
    }
