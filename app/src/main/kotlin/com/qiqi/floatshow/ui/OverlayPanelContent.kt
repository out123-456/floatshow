package com.qiqi.floatshow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import com.qiqi.floatshow.overlay.PanelGeometry
import com.qiqi.floatshow.overlay.PanelLayout
import com.qiqi.floatshow.overlay.ResizeCorner
import kotlin.math.min

/**
 * 悬浮面板的全部交互. 窗口尺寸是精确像素, 所以这里也用像素思考: 组件按 px 摆, 只有文字用 dp 排版.
 *
 * 几何由本函数持有唯一真相(geo). 手势里连续读写的都是它, 不会读到上一次重组的旧值,
 * 也不依赖重组节流; 每帧的最终结果再交给窗口([onGeometry]).
 *
 * - 收缩态是圆球, 内嵌应用图标, 点按展开
 * - 工具栏与最小化/关闭同高, 工具栏整行与面板四条边都能拖动
 * - 左上/左下/右下按住缩放, 关闭按钮所在角不提供缩放
 * - 顶部进入屏幕顶部 10% 时工具栏翻到底部; 翻回顶部要工具栏进入屏幕底部 10%
 */
@Composable
fun OverlayPanelContent(
    geometry: PanelGeometry,
    expandedInitially: Boolean,
    toolbarAtBottomInitially: Boolean,
    screenWidth: Int,
    screenHeight: Int,
    ballSizePx: Int,
    controlSizePx: Int,
    minSizePx: Int,
    restoreWidthPx: Int,
    restoreHeightPx: Int,
    onGeometry: (PanelGeometry) -> Unit,
    onCommit: (PanelGeometry, Boolean, Boolean) -> Unit,
    onClose: () -> Unit
) {
    var geo by remember { mutableStateOf(geometry) }
    var expanded by remember { mutableStateOf(expandedInitially) }
    var toolbarAtBottom by remember { mutableStateOf(toolbarAtBottomInitially) }

    // 展开态最后一次的尺寸, 从球展开时按它还原
    var restoreWidth by remember { mutableStateOf(restoreWidthPx) }
    var restoreHeight by remember { mutableStateOf(restoreHeightPx) }

    /**
     * 写几何: 先按"对边至少留 5%"约束, 再判断工具栏翻转, 最后交给窗口.
     * 翻转判断用约束后的值, 所以拖到边上时也会正确翻转.
     */
    fun push(next: PanelGeometry) {
        val clamped = PanelLayout.clamp(next, screenWidth, screenHeight)
        geo = clamped
        toolbarAtBottom = PanelLayout.nextToolbarAtBottom(toolbarAtBottom, clamped, screenHeight)
        onGeometry(clamped)
    }

    fun commit() {
        if (expanded) {
            restoreWidth = geo.width
            restoreHeight = geo.height
        }
        onCommit(geo, expanded, toolbarAtBottom)
    }

    if (!expanded) {
        BallContent(
            ballSizePx = ballSizePx,
            onMove = { push(geo.copy(x = geo.x + it.first, y = geo.y + it.second)) },
            onCommit = ::commit,
            onExpand = {
                val centerX = geo.x + geo.width / 2
                val centerY = geo.y + geo.height / 2
                expanded = true
                push(
                    PanelGeometry(
                        x = centerX - restoreWidth / 2,
                        y = centerY - restoreHeight / 2,
                        width = restoreWidth,
                        height = restoreHeight
                    )
                )
                commit()
            }
        )
        return
    }

    ExpandedContent(
        toolbarAtBottom = toolbarAtBottom,
        controlSizePx = controlSizePx,
        minSizePx = minSizePx,
        onMove = { push(geo.copy(x = geo.x + it.first, y = geo.y + it.second)) },
        onResize = { corner, dx, dy ->
            push(
                PanelLayout.resize(
                    corner = corner,
                    geometry = geo,
                    dx = dx,
                    dy = dy,
                    minSize = minSizePx,
                    maxSize = min(screenWidth, screenHeight)
                )
            )
        },
        onCommit = ::commit,
        onMinimize = {
            // 球放在最小化按钮原来的屏幕位置
            val (controlX, controlY) = controlRect(geo, toolbarAtBottom, controlSizePx, isMinimize = true)
            expanded = false
            push(
                PanelGeometry(
                    x = controlX + (controlSizePx - ballSizePx) / 2,
                    y = controlY + (controlSizePx - ballSizePx) / 2,
                    width = ballSizePx,
                    height = ballSizePx
                )
            )
            commit()
        },
        onClose = onClose
    )
}

/** 最小化/关闭按钮的屏幕位置(左上角像素). 关闭在最右, 最小化紧挨着它左侧, 两者等大 */
private fun controlRect(
    geometry: PanelGeometry,
    toolbarAtBottom: Boolean,
    controlSizePx: Int,
    isMinimize: Boolean
): Pair<Int, Int> {
    val right = geometry.x + geometry.width - controlSizePx
    val x = if (isMinimize) right - controlSizePx else right
    val y = if (toolbarAtBottom) geometry.y + geometry.height - controlSizePx else geometry.y
    return x to y
}

/** 收缩态圆球: 点按展开, 拖动移动 */
@Composable
private fun BallContent(
    ballSizePx: Int,
    onMove: (Pair<Int, Int>) -> Unit,
    onCommit: () -> Unit,
    onExpand: () -> Unit
) {
    // 拖动累积的小数部分, 保证慢速拖动不被取整吃掉
    var accX by remember { mutableStateOf(0f) }
    var accY by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onExpand() })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { accX = 0f; accY = 0f },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                    onDrag = { change, amount ->
                        change.consume()
                        accX += amount.x
                        accY += amount.y
                        // 累积到整数像素才写一次, 亚像素留在 acc 里
                        if (kotlin.math.abs(accX) < 1f && kotlin.math.abs(accY) < 1f) return@detectDragGestures
                        val stepX = accX.toInt()
                        val stepY = accY.toInt()
                        accX -= stepX
                        accY -= stepY
                        onMove(stepX to stepY)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.size(ballSizePx.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = stringResource(R.string.panel_ball_desc),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.size((ballSizePx / 2).dp)
                )
            }
        }
    }
}

/** 展开态: 工具栏 + 内容槽 + 三个角手柄; 面板本身无圆角 */
@Composable
private fun ExpandedContent(
    toolbarAtBottom: Boolean,
    controlSizePx: Int,
    minSizePx: Int,
    onMove: (Pair<Int, Int>) -> Unit,
    onResize: (ResizeCorner, Float, Float) -> Unit,
    onCommit: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!toolbarAtBottom) {
                    Toolbar(
                        controlSizePx = controlSizePx,
                        onMove = onMove,
                        onCommit = onCommit,
                        onMinimize = onMinimize,
                        onClose = onClose
                    )
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                ContentsSlot(modifier = Modifier.weight(1f))
                if (toolbarAtBottom) {
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Toolbar(
                        controlSizePx = controlSizePx,
                        onMove = onMove,
                        onCommit = onCommit,
                        onMinimize = onMinimize,
                        onClose = onClose
                    )
                }
            }

            // 三个角的缩放手柄; 关闭按钮所在角永远没有手柄
            PanelLayout.resizableCorners(toolbarAtBottom).forEach { corner ->
                ResizeHandle(
                    corner = corner,
                    controlSizePx = controlSizePx,
                    onResize = onResize,
                    onCommit = onCommit
                )
            }
        }
    }
}

/** 工具栏: 整行可拖动, 右端依次是最小化与关闭 */
@Composable
private fun Toolbar(
    controlSizePx: Int,
    onMove: (Pair<Int, Int>) -> Unit,
    onCommit: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    var accX by remember { mutableStateOf(0f) }
    var accY by remember { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(controlSizePx.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { accX = 0f; accY = 0f },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                    onDrag = { change, amount ->
                        change.consume()
                        accX += amount.x
                        accY += amount.y
                        val stepX = accX.toInt()
                        val stepY = accY.toInt()
                        accX -= stepX
                        accY -= stepY
                        if (stepX != 0 || stepY != 0) onMove(stepX to stepY)
                    }
                )
            }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.panel_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        SquareIconButton(
            sizePx = controlSizePx,
            icon = Icons.Rounded.OpenInFull,
            label = stringResource(R.string.panel_minimize),
            onClick = onMinimize
        )
        SquareIconButton(
            sizePx = controlSizePx,
            icon = Icons.Rounded.Close,
            label = stringResource(R.string.panel_close),
            onClick = onClose
        )
    }
}

/** 方形触发区的图标按钮: 点按区就是整块方形, 图标在中间 */
@Composable
private fun SquareIconButton(
    sizePx: Int,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(sizePx.dp)
            .pointerInput(Unit) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** 内容槽, 后续接图片 / Markdown */
@Composable
private fun ContentsSlot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.panel_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** 角上的方形手柄, 按住移动即可缩放对应两条边 */
@Composable
private fun BoxScope.ResizeHandle(
    corner: ResizeCorner,
    controlSizePx: Int,
    onResize: (ResizeCorner, Float, Float) -> Unit,
    onCommit: () -> Unit
) {
    val alignment = when (corner) {
        ResizeCorner.LEFT_TOP -> Alignment.TopStart
        ResizeCorner.LEFT_BOTTOM -> Alignment.BottomStart
        ResizeCorner.RIGHT_TOP -> Alignment.TopEnd
        ResizeCorner.RIGHT_BOTTOM -> Alignment.BottomEnd
    }

    Box(
        modifier = Modifier
            .align(alignment)
            .size(controlSizePx.dp)
            .background(Color.Transparent)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                    onDrag = { change, amount ->
                        change.consume()
                        onResize(corner, amount.x, amount.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 视觉标记: 明确告诉用户这里能按住缩放
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}
