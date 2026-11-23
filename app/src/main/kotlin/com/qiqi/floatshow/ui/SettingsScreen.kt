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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.qiqi.floatshow.data.RawImage
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
import com.qiqi.floatshow.data.PanelContentPicker
import com.qiqi.floatshow.data.PanelLayer
import com.qiqi.floatshow.data.PanelLayerKind
import com.qiqi.floatshow.data.PanelLayers
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
    var layers by remember { mutableStateOf(settings.panelLayers) }
    var fontName by remember { mutableStateOf(settings.panelFontName) }
    var fontFailed by remember { mutableStateOf(false) }
    var showImportKind by remember { mutableStateOf(false) }
    var pendingRenameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }
    var toastText by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    // 进度回调来自后台线程, 需要一个主线程投递入口
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var pendingImportKind by remember { mutableStateOf(PanelLayerKind.IMAGE) }
    var openedLayerId by remember { mutableStateOf<String?>(null) }
    var zoomUri by remember { mutableStateOf<Uri?>(null) }
    var zoomIsSvg by remember { mutableStateOf(false) }
    var replacingLayerId by remember { mutableStateOf<String?>(null) }
    /** 大图导入后待确认是否转 Raw 的 uri */
    var pendingRawUri by remember { mutableStateOf<Uri?>(null) }
    /** 转换进度 0..100, null 表示不在转换 */
    var rawProgress by remember { mutableStateOf<Int?>(null) }
    /** 已确认转过 Raw 的 uri(文件真的存在时才有) */
    var rawReady by remember { mutableStateOf<Set<String>>(emptySet()) }

    // 服务会写 serviceRunning, 面板也会自己写 panelEnabled(在面板上关掉时),
    // 这里跟着刷新, 否则主界面开关会一直停在旧状态
    DisposableEffect(settings) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsRepository.KEY_SERVICE_RUNNING) {
                serviceRunning = settings.serviceRunning
            }
            // 准星也由外部改: 快捷设置磁贴直接写同一个 key. 不跟着刷新,
            // 主页开关就会停在旧状态, 看起来像两个互不相干的开关
            if (key == SettingsRepository.KEY_CROSSHAIR_ENABLED) {
                crosshairEnabled = settings.crosshairEnabled
            }
            if (key == SettingsRepository.KEY_PANEL_ENABLED) {
                panelEnabled = settings.panelEnabled
            }
            if (key == SettingsRepository.KEY_PANEL_LAYERS) {
                layers = settings.panelLayers
            }
            if (key == SettingsRepository.KEY_PANEL_FONT_URI) {
                fontName = settings.panelFontName
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

    /**
     * 层没了(删除、替换内容)之后, 它的 raw 就成了孤儿. 留着只会白占上百 MB, 顺手删掉.
     * 只删"上一版还在、这一版不再被任何层引用"的图片 uri.
     */
    fun collectOrphanRaw(before: List<PanelLayer>, after: List<PanelLayer>) {
        val stillUsed = after.filter { it.kind == PanelLayerKind.IMAGE }
            .map { it.content }.toSet()
        val orphans = before.filter { it.kind == PanelLayerKind.IMAGE }
            .map { it.content }
            .filter { it !in stillUsed }
        if (orphans.isEmpty()) return
        val dropped = mutableSetOf<String>()
        for (content in orphans) {
            val uri = runCatching { Uri.parse(content) }.getOrNull() ?: continue
            if (RawImage.exists(context, uri) && runCatching { RawImage.delete(context, uri) }.getOrDefault(false)) {
                dropped += content
            }
        }
        if (dropped.isNotEmpty()) rawReady = rawReady - dropped
    }

    // 层列表每次改动都整体写回, 只触发一次监听
    fun updateLayers(block: (List<PanelLayer>) -> List<PanelLayer>) {
        val before = layers
        val next = block(before)
        if (next == layers) return
        layers = next
        settings.panelLayers = next
        collectOrphanRaw(before, next)
    }

    /**
     * 超过 4096 边长就提示转 Raw. 4096 以下实测感觉不到解码, 转 Raw 只是白占磁盘.
     * SVG 是矢量, 没有像素可转.
     */
    fun maybeAskRaw(uri: Uri) {
        if (PanelContentPicker.isSvg(context, uri)) return
        val size = PanelContentPicker.imageSize(context, uri) ?: return
        if (maxOf(size.first, size.second) < RAW_ASK_SIDE) return
        if (RawImage.exists(context, uri)) return
        pendingRawUri = uri
    }

    /** 当场转换. pendingRawUri 既是对话框的宿主, 也是转换的目标 */
    fun convertRaw(uri: Uri) {
        pendingRawUri = uri
        rawProgress = 0
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                RawImage.convert(context, uri) { percent ->
                    // 进度回调来自后台线程, 转回主线程更新
                    mainHandler.post { rawProgress = percent }
                }
            }
            rawProgress = null
            pendingRawUri = null
            if (ok) {
                rawReady = rawReady + uri.toString()
            } else {
                toastText = R.string.panel_raw_failed
            }
        }
    }

    /** [replaceId] 非空时是替换这一层的内容(类型可变, 层级与名字不变) */
    fun importLayer(uri: Uri, kind: PanelLayerKind, replaceId: String? = null) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        when (kind) {
            PanelLayerKind.IMAGE -> {
                if (!PanelContentPicker.canReadImage(context, uri)) {
                    toastText = R.string.panel_image_bad
                    return
                }
                val layer = PanelLayer(
                    id = replaceId ?: java.util.UUID.randomUUID().toString(),
                    name = PanelContentPicker.textFileName(context, uri) ?: "",
                    kind = PanelLayerKind.IMAGE,
                    content = uri.toString()
                )
                if (replaceId != null) updateLayers { PanelLayers.replaceWith(it, replaceId, layer) }
                else updateLayers { PanelLayers.add(it, layer) }
                // 大图直接压缩流显示会明显慢, 导入后问一次要不要转 Raw
                maybeAskRaw(uri)
            }
            PanelLayerKind.TEXT -> {
                if (!PanelContentPicker.isTextFile(context, uri)) {
                    toastText = R.string.panel_text_bad_type
                    return
                }
                val text = PanelContentPicker.readText(context, uri)
                if (text.isNullOrBlank()) {
                    toastText = R.string.panel_text_empty
                    return
                }
                val layer = PanelLayer(
                    id = replaceId ?: java.util.UUID.randomUUID().toString(),
                    name = PanelContentPicker.textFileName(context, uri) ?: "",
                    kind = PanelLayerKind.TEXT,
                    content = text
                )
                if (replaceId != null) updateLayers { PanelLayers.replaceWith(it, replaceId, layer) }
                else updateLayers { PanelLayers.add(it, layer) }
            }
        }
    }

    val layerPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val replaceId = replacingLayerId
        replacingLayerId = null
        if (uri != null) importLayer(uri, pendingImportKind, replaceId)
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (!PanelContentPicker.isFontFile(context, uri)) {
            toastText = R.string.panel_font_bad_type
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val name = PanelContentPicker.textFileName(context, uri) ?: "字体"
        // 先试解析一次: 解析不了就不保存, 直接把字体名标红
        val typeface = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                android.graphics.Typeface.Builder(pfd.fileDescriptor).build()
            }
        }.getOrNull()
        if (typeface == null) {
            settings.panelFontName = name
            fontName = name
            settings.panelFontUri = null
            fontFailed = true
            return@rememberLauncherForActivityResult
        }
        settings.panelFontUri = uri.toString()
        settings.panelFontName = name
        fontName = name
        fontFailed = false
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

    // 状态栏是否被唤出都不改变布局: insets 一律不参与测量,
    // 否则下拉一下状态栏整页就往下挤一下
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
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

                if (panelEnabled) {
                    HorizontalDivider()
                    PanelContentSection(
                        layers = layers,
                        onImportLayer = { showImportKind = true },
                        onMoveUp = { id -> updateLayers { PanelLayers.moveUp(it, id) } },
                        onMoveDown = { id -> updateLayers { PanelLayers.moveDown(it, id) } },
                        onToggleTop = { id -> updateLayers { PanelLayers.togglePinTop(it, id) } },
                        onToggleBottom = { id -> updateLayers { PanelLayers.togglePinBottom(it, id) } },
                        onToggleFixed = { id -> updateLayers { PanelLayers.toggleFixed(it, id) } },
                        onSetAnchor = { id, target ->
                            updateLayers { PanelLayers.setAnchor(it, id, target) }
                        },
                        onDelete = { id -> updateLayers { PanelLayers.remove(it, id) } },
                        onOpenLayer = { id -> openedLayerId = id },
                        onRenameLayer = { id ->
                            pendingRenameId = id
                            renameText = layers.firstOrNull { it.id == id }?.name ?: ""
                            renameError = false
                        },
                        onRotateLeft = { id -> updateLayers { PanelLayers.rotateLeft(it, id) } },
                        onRotateRight = { id -> updateLayers { PanelLayers.rotateRight(it, id) } },
                        onToggleMirror = { id -> updateLayers { PanelLayers.toggleMirror(it, id) } },
                        onTextMode = { id, mode ->
                            updateLayers { PanelLayers.setTextMode(it, id, mode) }
                        },
                        onSvgScale = { id, factor ->
                            // SVG 的倍数体现在层的宽度上, 长宽等比
                            val density = context.resources.displayMetrics.density
                            val side = (100f * density * factor).toInt()
                            updateLayers { PanelLayers.resize(it, id, side, side) }
                        }
                    )
                }
            }

            // 字体卡片: 与准星/面板同级
            FontCard(
                fontName = fontName,
                failed = fontFailed,
                onPickFont = { fontPicker.launch(arrayOf("*/*")) },
                onResetFont = {
                    settings.panelFontUri = null
                    settings.panelFontName = null
                    fontName = null
                    fontFailed = false
                }
            )

            Text(
                text = stringResource(R.string.settings_footer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 层抽屉: 内容预览 / 文本编辑 / 替换 / 删除
        openedLayerId?.let { id ->
            val layer = layers.firstOrNull { it.id == id }
            if (layer == null) {
                openedLayerId = null
            } else {
                LayerSheet(
                    layer = layer,
                    onReplace = {
                        replacingLayerId = id
                        showImportKind = true
                    },
                    onDelete = {
                        openedLayerId = null
                        updateLayers { PanelLayers.remove(it, id) }
                    },
                    onTextChanged = { newText ->
                        updateLayers { l -> l.map { if (it.id == id) it.copy(content = newText) else it } }
                    },
                    onOpenZoom = { u, svg ->
                        zoomUri = u
                        zoomIsSvg = svg
                    },
                    onDismiss = { openedLayerId = null },
                    // rawReady 参与判断: 它变了(转换完成/删除)才会重组, 抽屉里的按钮文案跟着换
                    rawState = when {
                        layer.kind != PanelLayerKind.IMAGE -> RawState.NONE
                        PanelContentPicker.isSvg(context, Uri.parse(layer.content)) -> RawState.NONE
                        layer.content in rawReady || RawImage.exists(context, Uri.parse(layer.content)) ->
                            RawState.CONVERTED
                        else -> RawState.AVAILABLE
                    },
                    onResetPosition = if (layer.anchorId != null) null else {
                        { updateLayers { PanelLayers.resetPosition(it, id) } }
                    },
                    onToggleRaw = {
                        val layerUri = Uri.parse(layer.content)
                        if (RawImage.exists(context, layerUri)) {
                            // 删除 Raw 只删缓存文件, 层与内容都还在
                            runCatching { RawImage.delete(context, layerUri) }
                            rawReady = rawReady - layer.content
                            toastText = R.string.panel_raw_deleted
                        } else {
                            convertRaw(layerUri)
                        }
                    }
                )
            }
        }

        zoomUri?.let { uri ->
            ZoomableImageDialog(
                uri = uri,
                isSvg = zoomIsSvg,
                onDismiss = { zoomUri = null }
            )
        }

        if (showImportKind) {
                AlertDialog(
                    onDismissRequest = { showImportKind = false },
                    title = { Text(stringResource(R.string.panel_import_kind_title)) },
                    text = {
                        Column {
                            TextButton(onClick = {
                                pendingImportKind = PanelLayerKind.IMAGE
                                showImportKind = false
                                layerPicker.launch(arrayOf("image/*"))
                            }) { Text(stringResource(R.string.panel_import_image)) }
                            TextButton(onClick = {
                                pendingImportKind = PanelLayerKind.TEXT
                                showImportKind = false
                                layerPicker.launch(arrayOf("text/*", "application/octet-stream"))
                            }) { Text(stringResource(R.string.panel_import_text)) }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showImportKind = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            pendingRenameId?.let { id ->
                AlertDialog(
                    onDismissRequest = { pendingRenameId = null },
                    title = { Text(stringResource(R.string.panel_rename_title)) },
                    text = {
                        OutlinedTextField(
                            value = renameText,
                            onValueChange = {
                                renameText = it
                                renameError = PanelLayers.isNameTaken(layers, it, id)
                            },
                            singleLine = true,
                            isError = renameError,
                            supportingText = {
                                if (renameError) Text(stringResource(R.string.panel_rename_duplicate))
                            }
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                updateLayers { PanelLayers.rename(it, id, renameText) }
                                pendingRenameId = null
                            },
                            enabled = !renameError && renameText.isNotBlank()
                        ) { Text(stringResource(R.string.action_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingRenameId = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }

            // 转 Raw: 同一个对话框从"询问"走到"进度", 不再弹第二个
            val askUri = pendingRawUri
            if (askUri != null) {
                val size = remember(askUri) { PanelContentPicker.imageSize(context, askUri) }
                val progress = rawProgress
                AlertDialog(
                    // 转换进行中不许关: 半途中断会留下半个文件
                    onDismissRequest = { if (progress == null) pendingRawUri = null },
                    title = {
                        Text(
                            stringResource(
                                if (progress == null) R.string.panel_raw_ask_title
                                else R.string.panel_raw_converting
                            )
                        )
                    },
                    // 磁盘占用按 ARGB_8888 估: 每像素 4 字节
                    text = {
                        if (progress == null) {
                            if (size != null) {
                                Text(
                                    stringResource(
                                        R.string.panel_raw_ask_message,
                                        size.first,
                                        size.second,
                                        ((size.first.toLong() * size.second * 4) / (1024 * 1024)).toInt()
                                    )
                                )
                            }
                        } else {
                            Column {
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "$progress%",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    },
                    confirmButton = {
                        if (progress == null) {
                            TextButton(onClick = { convertRaw(askUri) }) {
                                Text(stringResource(R.string.action_convert))
                            }
                        }
                    },
                    dismissButton = {
                        if (progress == null) {
                            TextButton(onClick = { pendingRawUri = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    }
                )
            }

            if (toastText != null) {
                AlertDialog(
                    onDismissRequest = { toastText = null },
                    text = { Text(stringResource(toastText!!)) },
                    confirmButton = {
                        TextButton(onClick = { toastText = null }) {
                            Text(stringResource(R.string.action_confirm))
                        }
                    }
                )
            }
        }
    }
}

/** 超过这个边长的大图才建议转 Raw; 4096 实测感觉不到解码 */
private const val RAW_ASK_SIDE = 4096

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
