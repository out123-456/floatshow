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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import com.qiqi.floatshow.overlay.PanelGeometry
import com.qiqi.floatshow.overlay.PanelLayout
import com.qiqi.floatshow.overlay.ResizeCorner

/**
 * 悬浮面板的全部交互. 窗口尺寸是精确像素, 所以这里也用像素思考:
 * 组件按 px 摆, 只有文字用 dp 排版.
 *
 * - 收缩态是一个圆球, 内嵌本应用图标, 点按展开
 * - 展开态工具栏与最小化/关闭按钮同高, 工具栏所在整行和面板四条边都可以拖动
 * - 左上/左下/右下按住缩放, 关闭按钮所在角不提供缩放
 * - 面板顶部进入屏幕顶部 10% 时工具栏翻到底部; 翻回顶部要工具栏进入屏幕底部 10%
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
    var expanded by remember { mutableStateOf(expandedInitially) }
    var toolbarAtBottom by remember { mutableStateOf(toolbarAtBottomInitially) }

    // 缩放前的大小, 用于从球展开时恢复
    var restoreWidth by remember { mutableStateOf(restoreWidthPx) }
    var restoreHeight by remember { mutableStateOf(restoreHeightPx) }

    /** 交给窗口的几何: 约束 + 翻转判断都在这里统一做 */
    fun push(next: PanelGeometry) {
        val clamped = PanelLayout.clamp(next, screenWidth, screenHeight)
        toolbarAtBottom = PanelLayout.nextToolbarAtBottom(toolbarAtBottom, clamped, screenHeight)
        onGeometry(clamped)
    }

    fun commit() {
        onCommit(geometry, expanded, toolbarAtBottom)
    }

    if (!expanded) {
        BallContent(
            geometry = geometry,
            ballSizePx = ballSizePx,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            onGeometry = ::push,
            onCommit = commit,
            onExpand = {
                val centerX = geometry.x + geometry.width / 2
                val centerY = geometry.y + geometry.height / 2
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
        geometry = geometry,
        toolbarAtBottom = toolbarAtBottom,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        controlSizePx = controlSizePx,
        minSizePx = minSizePx,
        closeCorner = PanelLayout.closeCorner(toolbarAtBottom),
        onGeometry = ::push,
        onCommit = commit,
        onMinimize = {
            // 球放在最小化按钮原来的屏幕位置
            val control = controlRect(geometry, toolbarAtBottom, controlSizePx, isMinimize = true)
            val ballX = control.first + (controlSizePx - ballSizePx) / 2
            val ballY = control.second + (controlSizePx - ballSizePx) / 2
            restoreWidth = geometry.width
            restoreHeight = geometry.height
            expanded = false
            push(PanelGeometry(ballX, ballY, ballSizePx, ballSizePx))
            commit()
        },
        onClose = onClose
    )
}

/**
 * 最小化/关闭按钮的屏幕位置. 关闭在最右, 最小化紧挨着它的左侧, 两者同尺寸.
 * 返回 (left, top).
 */
private fun controlRect(
    geometry: PanelGeometry,
    toolbarAtBottom: Boolean,
    controlSizePx: Int,
    isMinimize: Boolean
): Pair<Int, Int> {
    val right = geometry.x + geometry.width - controlSizePx
    val x = if (isMinimize) right - controlSizePx else right
    val y = if (toolbarAtBottom) {
        geometry.y + geometry.height - controlSizePx
    } else {
        geometry.y
    }
    return x to y
}

/** 收缩态圆球 */
@Composable
private fun BallContent(
    geometry: PanelGeometry,
    ballSizePx: Int,
    screenWidth: Int,
    screenHeight: Int,
    onGeometry: (PanelGeometry) -> Unit,
    onCommit: () -> Unit,
    onExpand: () -> Unit
) {
    var dragAcc by remember { mutableStateOf(0f to 0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onExpand() },
                    // 长按拖动: 与点按区分, 避免拖动手势被当成点击
                    onLongPress = {}
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragAcc = 0f to 0f },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragAcc = (dragAcc.first + amount.x) to (dragAcc.second + amount.y)
                        val stepX = dragAcc.first.toInt()
                        val stepY = dragAcc.second.toInt()
                        if (stepX == 0 && stepY == 0) return@detectDragGestures
                        dragAcc = (dragAcc.first - stepX) to (dragAcc.second - stepY)
                        onGeometry(geometry.copy(x = geometry.x + stepX, y = geometry.y + stepY))
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
    // screenWidth / screenHeight 由调用方约束保证, 这里不重复使用
}

/** 展开态 */
@Composable
private fun ExpandedContent(
    geometry: PanelGeometry,
    toolbarAtBottom: Boolean,
    screenWidth: Int,
    screenHeight: Int,
    controlSizePx: Int,
    minSizePx: Int,
    closeCorner: ResizeCorner,
    onGeometry: (PanelGeometry) -> Unit,
    onCommit: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = androidx.compose.ui.graphics.RectangleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!toolbarAtBottom) {
                    Toolbar(
                        geometry = geometry,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        controlSizePx = controlSizePx,
                        onGeometry = onGeometry,
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
                        geometry = geometry,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        controlSizePx = controlSizePx,
                        onGeometry = onGeometry,
                        onCommit = onCommit,
                        onMinimize = onMinimize,
                        onClose = onClose
                    )
                }
            }

            // 三个角的缩放手柄. 关闭按钮所在角始终没有手柄
            PanelLayout.resizableCorners(toolbarAtBottom).forEach { corner ->
                ResizeHandle(
                    corner = corner,
                    geometry = geometry,
                    controlSizePx = controlSizePx,
                    minSizePx = minSizePx,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    onGeometry = onGeometry,
                    onCommit = onCommit
                )
            }
        }
    }
}

/** 工具栏: 可拖动整行, 右端依次是最小化与关闭 */
@Composable
private fun Toolbar(
    geometry: PanelGeometry,
    screenWidth: Int,
    screenHeight: Int,
    controlSizePx: Int,
    onGeometry: (PanelGeometry) -> Unit,
    onCommit: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    var dragAcc by remember { mutableStateOf(0f to 0f) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(controlSizePx.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragAcc = 0f to 0f },
                    onDragEnd = { onCommit() },
                    onDragCancel = { onCommit() },
                    onDrag = { change, amount ->
                        change.consume()
                        dragAcc = (dragAcc.first + amount.x) to (dragAcc.second + amount.y)
                        val stepX = dragAcc.first.toInt()
                        val stepY = dragAcc.second.toInt()
                        if (stepX == 0 && stepY == 0) return@detectDragGestures
                        dragAcc = (dragAcc.first - stepX) to (dragAcc.second - stepY)
                        onGeometry(geometry.copy(x = geometry.x + stepX, y = geometry.y + stepY))
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

/** 方形触发区的图标按钮, 点按区就是整块方形 */
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
            .pointerInput(Unit) {
                detectTapGestures { onClick() }
            },
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
private fun BoxScope.ContentsSlot(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.panel_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** 角上的方形手柄, 按住沿屏幕移动即可缩放 */
@Composable
private fun BoxScope.ResizeHandle(
    corner: ResizeCorner,
    geometry: PanelGeometry,
    controlSizePx: Int,
    minSizePx: Int,
    screenWidth: Int,
    screenHeight: Int,
    onGeometry: (PanelGeometry) -> Unit,
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
                        onGeometry(
                            PanelLayout.resize(
                                corner = corner,
                                geometry = geometry,
                                dx = amount.x,
                                dy = amount.y,
                                minSize = minSizePx,
                                maxSize = min(screenWidth, screenHeight)
                            )
                        )
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 视觉标记: 半透明小方块, 明确告诉用户这里可以按住缩放
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}
