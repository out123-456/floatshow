package com.qiqi.floatshow.ui

import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qiqi.floatshow.R
import com.qiqi.floatshow.data.SettingsRepository
import com.qiqi.floatshow.service.OverlayService
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenLab: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var crosshairEnabled by remember { mutableStateOf(settings.crosshairEnabled) }
    var panelEnabled by remember { mutableStateOf(settings.panelEnabled) }
    var hasImage by remember { mutableStateOf(settings.crosshairImageUri != null) }
    var notificationsAllowed by remember { mutableStateOf(notificationsEnabled(context)) }
    var serviceRunning by remember { mutableStateOf(settings.serviceRunning) }

    // 服务会写 serviceRunning, 面板也会自己写 panelEnabled(在面板上关掉时),
    // 这里跟着刷新, 否则主界面开关会一直停在旧状态
    DisposableEffect(settings) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsRepository.KEY_SERVICE_RUNNING) {
                serviceRunning = settings.serviceRunning
            }
            if (key == SettingsRepository.KEY_PANEL_ENABLED) {
                panelEnabled = settings.panelEnabled
            }
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // 用可调试标记代替 BuildConfig: 不生成 Java 源, 也就不触发 JdkImageTransform
    val debuggable = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    // 跟着配置走, 旋转后偏移范围自动更新
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = remember(configuration, density) {
        (configuration.screenWidthDp * density.density).roundToInt()
    }
    val screenHeight = remember(configuration, density) {
        (configuration.screenHeightDp * density.density).roundToInt()
    }

    // 有权限就请求服务, 与开关无关; 服务常驻, 直到用户点右上角关闭或系统回收
    LaunchedEffect(hasOverlayPermission) {
        if (hasOverlayPermission) OverlayService.start(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = Settings.canDrawOverlays(context)
                notificationsAllowed = notificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            settings.crosshairImageUri = uri.toString()
            hasImage = true
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationsAllowed = notificationsEnabled(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // 设计预览页只在 debug 里留入口, 之后会删
                    if (debuggable) {
                        IconButton(onClick = onOpenLab) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = stringResource(R.string.lab_open)
                            )
                        }
                    }
                    // 服务在跑就是红色"关停", 停止时是强调色"启动"
                    IconButton(onClick = {
                        if (serviceRunning) OverlayService.stop(context) else OverlayService.start(context)
                    }) {
                        Icon(
                            imageVector = if (serviceRunning) Icons.Rounded.Close else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(
                                if (serviceRunning) R.string.action_close_service
                                else R.string.action_start_service
                            ),
                            tint = if (serviceRunning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        val focusManager = LocalFocusManager.current
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // 点空白处收起键盘/取消焦点; 子控件已消费的点击不会走到这里
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasOverlayPermission) {
                PermissionCard(
                    title = stringResource(R.string.permission_overlay_title),
                    description = stringResource(R.string.permission_overlay_desc),
                    action = stringResource(R.string.permission_overlay_action),
                    onAction = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed) {
                PermissionCard(
                    title = stringResource(R.string.permission_notification_title),
                    description = stringResource(R.string.permission_notification_desc),
                    action = stringResource(R.string.permission_notification_action),
                    onAction = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }

            CrosshairSettingsCard(
                settings = settings,
                enabled = crosshairEnabled,
                onEnabledChange = {
                    crosshairEnabled = it
                    settings.crosshairEnabled = it
                },
                hasImage = hasImage,
                onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                onResetImage = {
                    settings.crosshairImageUri = null
                    hasImage = false
                },
                screenWidth = screenWidth,
                screenHeight = screenHeight
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.section_panel),
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.section_panel_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = panelEnabled,
                            onCheckedChange = {
                                panelEnabled = it
                                settings.panelEnabled = it
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            Text(
                text = stringResource(R.string.settings_footer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Android 13 起看权限, 更早的版本看通知开关 */
private fun notificationsEnabled(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

@Composable
private fun PermissionCard(
    title: String,
    description: String,
    action: String,
    onAction: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onAction) { Text(text = action) }
        }
    }
}
