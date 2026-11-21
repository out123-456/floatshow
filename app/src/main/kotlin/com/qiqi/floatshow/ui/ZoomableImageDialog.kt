package com.qiqi.floatshow.ui

import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import com.qiqi.floatshow.R

/**
 * 全屏查看器. 位图走 [TileImageView](瓦片按需解码, 支持 1:1 与放大),
 * SVG 走 [SvgView](矢量放大), 两者都由触摸完成缩放与拖动; SVG 额外提供倍数按钮.
 */
private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT

@Composable
fun ZoomableImageDialog(uri: Uri, isSvg: Boolean, onDismiss: () -> Unit) {
    var svgView by remember { mutableStateOf<SvgView?>(null) }
    var zoomPercent by remember { mutableStateOf(1f) }
    var info by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // 对话框窗口也按"状态栏不存在"处理: 否则唤出状态栏时整页会被挤下去
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        val window = (hostView.parent as? DialogWindowProvider)?.window
        if (window != null) {
            window.setLayout(MATCH, MATCH)
            window.decorView.fitsSystemWindows = false
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        onDispose { }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    if (isSvg) {
                        SvgView(context).also { view ->
                            view.onLoaded = { w, h -> info = w to h }
                            view.load(uri)
                            svgView = view
                        }
                    } else {
                        TileImageView(context).also { view ->
                            view.onLoaded = { w, h -> info = w to h }
                            view.load(uri)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // SVG 倍数按钮: 触摸缩放之外的另一条路
            if (isSvg) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(top = 32.dp, start = 12.dp, end = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(1f, 2f, 4f, 8f, 16f).forEach { factor ->
                        FilterChip(
                            selected = zoomPercent == factor,
                            onClick = {
                                svgView?.setZoom(factor)
                                zoomPercent = factor
                            },
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

            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Text(stringResource(R.string.action_confirm))
            }

            val size = info
            if (size != null && size.first > 0) {
                Text(
                    text = "${size.first} × ${size.second}",
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                )
            }
        }
    }

    DisposableEffect(uri) {
        onDispose { svgView?.release() }
    }
}
