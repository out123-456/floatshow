package com.example.crosshair

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.crosshair.ui.theme.CrosshairTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sticky immersive mode
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (!checkPermissions()) return

        setContent {
            CrosshairTheme {
                CrosshairSettingsScreen()
            }
        }
    }

    private fun checkPermissions(): Boolean {
        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                finish()
                return false
            }
        }
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
                return false
            }
        }
        // Check battery optimization
        val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            finish()
            return false
        }
        return true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrosshairSettingsScreen() {
    val context = LocalContext.current
    val prefs = remember { CrosshairPrefs(context) }
    val focusManager = LocalFocusManager.current

    var imageUriStr by remember { mutableStateOf(prefs.imageUri) }
    var sizeText by remember { mutableStateOf(prefs.size.toString()) }
    var offsetX by remember { mutableFloatStateOf(prefs.offsetX.toFloat()) }
    var offsetY by remember { mutableFloatStateOf(prefs.offsetY.toFloat()) }
    var transparency by remember { mutableFloatStateOf(prefs.transparency) }
    var notchMode by remember { mutableStateOf(prefs.notchMode) }
    var serviceRunning by remember { mutableStateOf(prefs.serviceRunning) }
    var sizeEditFocused by remember { mutableStateOf(false) }

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: SecurityException) { }
            imageUriStr = uri.toString()
            prefs.imageUri = uri.toString()
        }
    }

    fun toggleService() {
        val intent = Intent(context, CrosshairService::class.java)
        if (serviceRunning) {
            context.stopService(intent)
            serviceRunning = false
            prefs.serviceRunning = false
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            serviceRunning = true
            prefs.serviceRunning = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crosshair Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Image selection
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Crosshair Image", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Text(if (imageUriStr != null) "Change Image" else "Select Image")
                    }
                    if (imageUriStr != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Image selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Size control
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Size (px)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val newSize = (sizeText.toIntOrNull() ?: 40) - 1
                                if (newSize >= 8) {
                                    sizeText = newSize.toString()
                                    prefs.size = newSize
                                }
                            }
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease size")
                        }
                        OutlinedTextField(
                            value = sizeText,
                            onValueChange = { value ->
                                val filtered = value.filter { it.isDigit() }
                                sizeText = filtered
                                val intVal = filtered.toIntOrNull()
                                if (intVal != null && intVal >= 8) {
                                    prefs.size = intVal
                                }
                            },
                            modifier = Modifier
                                .width(100.dp)
                                .onFocusChanged { focusState ->
                                    sizeEditFocused = focusState.isFocused
                                    if (!focusState.isFocused) {
                                        val intVal = sizeText.toIntOrNull()
                                        if (intVal == null || intVal < 8) {
                                            sizeText = "40"
                                            prefs.size = 40
                                        }
                                    }
                                },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        IconButton(
                            onClick = {
                                val newSize = (sizeText.toIntOrNull() ?: 40) + 1
                                sizeText = newSize.toString()
                                prefs.size = newSize
                            }
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase size")
                        }
                    }
                }
            }

            // X Offset
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("X Offset", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            offsetX = (offsetX - 1).coerceIn(-screenWidth.toFloat(), screenWidth.toFloat())
                            prefs.offsetX = offsetX.toInt()
                        }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease X")
                        }
                        Slider(
                            value = offsetX,
                            onValueChange = {
                                offsetX = it
                                prefs.offsetX = it.toInt()
                            },
                            valueRange = -screenWidth.toFloat()..screenWidth.toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            offsetX = (offsetX + 1).coerceIn(-screenWidth.toFloat(), screenWidth.toFloat())
                            prefs.offsetX = offsetX.toInt()
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase X")
                        }
                    }
                    Text("${offsetX.toInt()} px", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Y Offset
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Y Offset", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            offsetY = (offsetY - 1).coerceIn(-screenHeight.toFloat(), screenHeight.toFloat())
                            prefs.offsetY = offsetY.toInt()
                        }) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease Y")
                        }
                        Slider(
                            value = offsetY,
                            onValueChange = {
                                offsetY = it
                                prefs.offsetY = it.toInt()
                            },
                            valueRange = -screenHeight.toFloat()..screenHeight.toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            offsetY = (offsetY + 1).coerceIn(-screenHeight.toFloat(), screenHeight.toFloat())
                            prefs.offsetY = offsetY.toInt()
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Increase Y")
                        }
                    }
                    Text("${offsetY.toInt()} px", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Transparency
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Transparency", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = transparency,
                        onValueChange = {
                            transparency = it
                            prefs.transparency = it
                        },
                        valueRange = 0f..1f
                    )
                    Text(
                        "${(transparency * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Notch mode
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Notch Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Include status bar area in center calculation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Checkbox(
                        checked = notchMode,
                        onCheckedChange = {
                            notchMode = it
                            prefs.notchMode = it
                        }
                    )
                }
            }

            // Toggle service
            Button(
                onClick = { toggleService() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serviceRunning)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (serviceRunning) "Stop Crosshair" else "Start Crosshair",
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
