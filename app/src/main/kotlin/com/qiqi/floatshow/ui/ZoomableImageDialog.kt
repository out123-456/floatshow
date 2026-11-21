package com.qiqi.floatshow.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qiqi.floatshow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全屏缩放查看器. 双指缩放 + 拖动, 返回退出.
 * 位图同样在后台线程降采样解码, 不把原图整张塞进内存.
 */
@Composable
fun ZoomableImageDialog(uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // LaunchedEffect 里起线程, remember 只做缓存不适合承担副作用
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {

            var sample = 1
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            while (bounds.outWidth / (sample * 2) >= MAX_VIEW_SIDE ||
                bounds.outHeight / (sample * 2) >= MAX_VIEW_SIDE
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            bitmap = runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 8f)
                        offset += pan
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val image = bitmap
            if (image == null) {
                Text(stringResource(R.string.panel_layer_info_loading))
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = stringResource(R.string.panel_zoom_title),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                )
            }
        }
    }
}

private const val MAX_VIEW_SIDE = 2048
