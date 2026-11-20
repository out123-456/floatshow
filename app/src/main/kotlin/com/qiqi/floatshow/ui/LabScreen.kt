package com.qiqi.floatshow.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.qiqi.floatshow.R
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 设计预览页. 只在 debug 构建出现, 用来快速对比 MD3 的颜色/文字/组件, 不参与正式界面.
 * 每一项都标注中英双语名字`,方便照着名字挑组件.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignLabScreen(onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val sheetState = rememberModalBottomSheetState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSheet by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = stringResource(R.string.lab_name_drawer),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                Text(
                    text = stringResource(R.string.lab_drawer_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.lab_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_cancel)
                            )
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LabSection(R.string.lab_section_colors) {
                    ColorRow(R.string.lab_color_primary, scheme.primary)
                    ColorRow(R.string.lab_color_on_primary, scheme.onPrimary)
                    ColorRow(R.string.lab_color_primary_container, scheme.primaryContainer)
                    ColorRow(R.string.lab_color_on_primary_container, scheme.onPrimaryContainer)
                    ColorRow(R.string.lab_color_secondary, scheme.secondary)
                    ColorRow(R.string.lab_color_on_secondary, scheme.onSecondary)
                    ColorRow(R.string.lab_color_tertiary, scheme.tertiary)
                    ColorRow(R.string.lab_color_on_tertiary, scheme.onTertiary)
                    ColorRow(R.string.lab_color_error, scheme.error)
                    ColorRow(R.string.lab_color_on_error, scheme.onError)
                    ColorRow(R.string.lab_color_error_container, scheme.errorContainer)
                    ColorRow(R.string.lab_color_surface, scheme.surface)
                    ColorRow(R.string.lab_color_on_surface, scheme.onSurface)
                    ColorRow(R.string.lab_color_surface_variant, scheme.surfaceVariant)
                    ColorRow(R.string.lab_color_on_surface_variant, scheme.onSurfaceVariant)
                    ColorRow(R.string.lab_color_outline, scheme.outline)
                    ColorRow(R.string.lab_color_outline_variant, scheme.outlineVariant)
                    ColorRow(R.string.lab_color_inverse_surface, scheme.inverseSurface)
                }

                LabSection(R.string.lab_section_type) {
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.displaySmall, "displaySmall")
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.headlineSmall, "headlineSmall")
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.titleLarge, "titleLarge")
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.titleMedium, "titleMedium")
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.bodyLarge, "bodyLarge")
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.bodyMedium, "bodyMedium")
                    TypeRow(R.string.lab_sample_text, MaterialTheme.typography.labelSmall, "labelSmall")
                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = scheme.outlineVariant)
                    Text(
                        text = stringResource(R.string.lab_name_divider),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }

                LabSection(R.string.lab_section_buttons) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = {}) { Text(stringResource(R.string.lab_name_button_filled)) }
                        FilledTonalButton(onClick = {}) {
                            Text(stringResource(R.string.lab_name_button_tonal))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = {}) {
                            Text(stringResource(R.string.lab_name_button_outlined))
                        }
                        ElevatedButton(onClick = {}) {
                            Text(stringResource(R.string.lab_name_button_elevated))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {}) { Text(stringResource(R.string.lab_name_button_text)) }
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.lab_name_button_icon)
                            )
                        }
                    }
                }

                LabSection(R.string.lab_section_inputs) {
                    var text by remember { mutableStateOf("") }
                    var checked by remember { mutableStateOf(true) }
                    var radio by remember { mutableStateOf(true) }
                    var slider by remember { mutableFloatStateOf(0.4f) }

                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(stringResource(R.string.lab_name_textfield)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = checked, onCheckedChange = { checked = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lab_name_switch))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = { checked = it })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lab_name_checkbox))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = radio, onClick = { radio = !radio })
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.lab_name_radio))
                    }
                    Text(stringResource(R.string.lab_name_slider))
                    Slider(value = slider, onValueChange = { slider = it })
                }

                LabSection(R.string.lab_section_surfaces) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        LabCardBody(R.string.lab_name_card)
                    }
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        LabCardBody(R.string.lab_name_card_elevated)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        LabCardBody(R.string.lab_name_card_outlined)
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.lab_name_list_item)) },
                        supportingContent = { Text(stringResource(R.string.lab_sample_text)) }
                    )
                }

                LabSection(R.string.lab_section_nav) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = {
                            Text(stringResource(R.string.lab_name_tab_row))
                        })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = {
                            Text(stringResource(R.string.lab_name_nav_bar))
                        })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FloatingActionButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.lab_name_fab)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        SmallFloatingActionButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.lab_name_fab_small)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        ExtendedFloatingActionButton(
                            onClick = {},
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            text = { Text(stringResource(R.string.lab_name_fab_extended)) }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    BadgedBox(
                        badge = {
                            Badge { Text("9") }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = stringResource(R.string.lab_name_badge)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    NavigationBar {
                        NavigationBarItem(
                            selected = true,
                            onClick = {},
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            label = { Text(stringResource(R.string.lab_name_nav_bar)) }
                        )
                        NavigationBarItem(
                            selected = false,
                            onClick = {},
                            icon = { Icon(Icons.Filled.Favorite, contentDescription = null) },
                            label = { Text(stringResource(R.string.lab_sample_text)) }
                        )
                    }
                }

                LabSection(R.string.lab_section_dialogs) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showDialog = true }) {
                            Text(stringResource(R.string.lab_name_dialog))
                        }
                        OutlinedButton(onClick = { showSheet = true }) {
                            Text(stringResource(R.string.lab_name_sheet))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text(stringResource(R.string.lab_name_drawer))
                        }
                        Box {
                            OutlinedButton(onClick = { showMenu = true }) {
                                Text(stringResource(R.string.lab_name_menu))
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lab_sample_text)) },
                                    onClick = { showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.lab_sample_text)) },
                                    onClick = { showMenu = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.lab_snackbar_text))
                            }
                        }) {
                            Text(stringResource(R.string.lab_name_snackbar))
                        }
                    }
                }

                LabSection(R.string.lab_section_progress) {
                    Text(stringResource(R.string.lab_name_linear_progress))
                    LinearProgressIndicator(progress = 0.4f, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator()
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.lab_name_circular_progress))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.lab_name_chip_assist)) }
                        )
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = { Text(stringResource(R.string.lab_name_chip_filter)) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text(stringResource(R.string.lab_name_chip_suggestion)) }
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.lab_name_dialog)) },
            text = { Text(stringResource(R.string.lab_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.lab_name_sheet),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.lab_sheet_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LabSection(@StringRes titleRes: Int, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(PaddingValues(16.dp)),
                content = content
            )
        }
    }
}

@Composable
private fun ColorRow(@StringRes nameRes: Int, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Text(text = stringResource(nameRes), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(
            text = hexOf(color),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypeRow(@StringRes textRes: Int, style: TextStyle, name: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = stringResource(textRes),
            style = style,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LabCardBody(@StringRes nameRes: Int) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(text = stringResource(nameRes), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.lab_sample_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun hexOf(color: Color): String =
    String.format(Locale.US, "#%06X", color.toArgb() and 0xFFFFFF)
