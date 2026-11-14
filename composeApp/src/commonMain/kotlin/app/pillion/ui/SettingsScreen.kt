package app.pillion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.AppInfo
import app.pillion.core.DashDpi
import app.pillion.core.DashTapSettings
import app.pillion.core.DashAutoDriveSettings
import app.pillion.core.DashMargins
import app.pillion.core.DashMarginColor
import app.pillion.core.DashApp
import app.pillion.core.DashResolution
import app.pillion.core.ThemeMode
import app.pillion.core.SettingsPreset
import app.pillion.core.UpdateInfo
import app.pillion.resources.Res
import app.pillion.resources.app_icon
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    quality: Int,
    onQuality: (Int) -> Unit,
    maxFps: Int,
    onMaxFps: (Int) -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    dashSupported: Boolean = false,
    dashEnabled: Boolean = false,
    dashResolution: DashResolution = DashResolution.DEFAULT,
    onDashResolution: (DashResolution) -> Unit = {},
    dashDpi: Int = DashDpi.DEFAULT,
    onDashDpi: (Int) -> Unit = {},
    dashMargins: DashMargins = DashMargins(),
    onDashMargins: (DashMargins) -> Unit = {},
    dashMarginColor: DashMarginColor = DashMarginColor.Black,
    onDashMarginColor: (DashMarginColor) -> Unit = {},
    dashTap: DashTapSettings = DashTapSettings(),
    onDashTap: (DashTapSettings) -> Unit = {},
    dashAutoDrive: DashAutoDriveSettings = DashAutoDriveSettings(),
    onDashAutoDrive: (DashAutoDriveSettings) -> Unit = {},
    yahooNaviEnabled: Boolean = true,
    yahooAccessibilityEnabled: Boolean = false,
    onYahooNaviEnabled: (Boolean) -> Unit = {},
    dashApps: List<DashApp> = emptyList(),
    selectedDashApp: String? = null,
    onDashApp: (String?) -> Unit = {},
    onSetUpDash: () -> Unit = {},
    onDisableDash: () -> Unit = {},
    bikeName: String = "",
    onChangeBike: () -> Unit = {},
    update: UpdateInfo?,
    onBack: () -> Unit,
    onExportDiagnostics: (() -> Unit)? = null,
    presets: List<SettingsPreset> = emptyList(),
    onApplyPreset: (String) -> Boolean = { false },
    onSavePreset: (String, Boolean) -> Boolean = { _, _ -> false },
    onDeletePreset: (String) -> Boolean = { false },
) {
    val uriHandler = LocalUriHandler.current
    var displayAdjustmentsExpanded by remember { mutableStateOf(false) }
    var tapExpanded by remember { mutableStateOf(false) }
    var yahooExpanded by remember { mutableStateOf(false) }
    val yahooSelected = selectedDashApp == "jp.co.yahoo.android.apps.navi"
    val yahooReady = dashEnabled && yahooSelected && yahooAccessibilityEnabled
    Column(
        modifier = Modifier.fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (update != null) {
            SectionHeader("Updates")
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(update.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "A new version is available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { uriHandler.openUri(update.url) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("Get", fontWeight = FontWeight.SemiBold)
                    }
                }
                if (update.notes.isNotBlank()) {
                    GroupDivider()
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (onExportDiagnostics != null) {
            SectionHeader("Test-base diagnostics")
            SettingsGroup {
                Button(
                    onClick = onExportDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Export diagnostic ZIP") }
                Text(
                    "Saves persistent flight breadcrumbs and best-effort Android logcat to Downloads.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (presets.isNotEmpty()) {
            PresetSettings(presets, onApplyPreset, onSavePreset, onDeletePreset)
            Spacer(Modifier.height(24.dp))
        }

        SectionHeader("Appearance")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val options = listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Light",
                ThemeMode.DARK to "Dark",
            )
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Motorcycle")
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onChangeBike).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(bikeName.ifEmpty { "Not selected" }, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tap to switch head unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Change",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("Mirroring")
        SettingsGroup {
            SettingSlider("Image quality", "$quality", quality.toFloat(), 10f, 80f) { onQuality(it.roundToInt()) }
            GroupDivider()
            SettingSlider("Max frame rate", "$maxFps fps", maxFps.toFloat(), 1f, 15f, steps = 13) { onMaxFps(it.roundToInt()) }
        }
        Text(
            "Higher quality looks sharper but makes the picture less smooth. The FPS cap " +
                "saves battery and reduces heat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
        )

        if (dashSupported) {
            Spacer(Modifier.height(24.dp))
            SectionHeader("Dedicated dash display")
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (dashEnabled) "On" else "Off",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Keep your nav app on the dash with the phone screen off.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dashEnabled) {
                        OutlinedButton(onClick = onDisableDash, shape = RoundedCornerShape(12.dp)) { Text("Disable") }
                    } else {
                        Button(onClick = onSetUpDash, shape = RoundedCornerShape(12.dp)) { Text("Set up") }
                    }
                }
                if (dashEnabled) {
                    GroupDivider()
                    LinkRow("Re-run setup (after a restart)") { onSetUpDash() }
                }
                GroupDivider()
                DashResolutionSelector(dashResolution, onDashResolution)
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { displayAdjustmentsExpanded = !displayAdjustmentsExpanded }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Display adjustments", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(if (displayAdjustmentsExpanded) "▲" else "▼",
                        color = MaterialTheme.colorScheme.primary)
                }
                if (displayAdjustmentsExpanded) {
                    DashDpiSelector(dashDpi, onDashDpi)
                    GroupDivider()
                    PixelMarginSelector("Left margin", dashMargins.left, DashMargins.MAX_LEFT) { onDashMargins(dashMargins.copy(left = it)) }
                    GroupDivider()
                    PixelMarginSelector("Bottom margin", dashMargins.bottom, DashMargins.MAX_BOTTOM) { onDashMargins(dashMargins.copy(bottom = it)) }
                }
                GroupDivider()
                MarginColorSelector(dashMarginColor, onDashMarginColor)
                GroupDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Stick input", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = dashTap.enabled,
                        onCheckedChange = { onDashTap(dashTap.copy(enabled = it)) })
                }
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().clickable { tapExpanded = !tapExpanded }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Stick tap positions", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(if (tapExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                }
                if (tapExpanded) {
                    Text("Relative to top-left (0–100%). Changes apply on next Start.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TapPositionRow("Zoom in (up)", dashTap.upX, dashTap.upY,
                        onX = { onDashTap(dashTap.copy(upX = it)) },
                        onY = { onDashTap(dashTap.copy(upY = it)) })
                    GroupDivider()
                    TapPositionRow("Zoom out (down)", dashTap.downX, dashTap.downY,
                        onX = { onDashTap(dashTap.copy(downX = it)) },
                        onY = { onDashTap(dashTap.copy(downY = it)) })
                }
                GroupDivider()
                DashAppSelector(dashApps, selectedDashApp, onDashApp)
                GroupDivider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Yahoo! Car Navigation features", style = MaterialTheme.typography.bodyLarge)
                        Text("Fresh VD launch, reload mask, live ROAD text and automatic Drive Mode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = yahooNaviEnabled && yahooReady,
                        onCheckedChange = onYahooNaviEnabled, enabled = yahooReady)
                }
                if (dashEnabled && yahooSelected && !yahooAccessibilityEnabled) {
                    Text(
                        "Enable the Pillion Yahoo ROAD accessibility service to use these features.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                if (yahooNaviEnabled && yahooReady) {
                    GroupDivider()
                    Row(
                        Modifier.fillMaxWidth().clickable { yahooExpanded = !yahooExpanded }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Drive Mode & automatic tap positions",
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(if (yahooExpanded) "▲" else "▼",
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (yahooExpanded) {
                        Text("Drive Mode stick positions",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        TapPositionRow("Zoom in (Drive Mode)", dashTap.driveUpX, dashTap.driveUpY,
                            onX = { onDashTap(dashTap.copy(driveUpX = it)) },
                            onY = { onDashTap(dashTap.copy(driveUpY = it)) })
                        GroupDivider()
                        TapPositionRow("Zoom out (Drive Mode)", dashTap.driveDownX, dashTap.driveDownY,
                            onX = { onDashTap(dashTap.copy(driveDownX = it)) },
                            onY = { onDashTap(dashTap.copy(driveDownY = it)) })
                        GroupDivider()
                        Text("Automatic mode switching",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("Wait 10 seconds on initial or arrival screen before tapping. " +
                                "After ending guidance, recheck the initial screen and switch to Drive Mode. " +
                                "Changes apply on next Start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TapPositionRow("Initial screen", dashAutoDrive.initialX, dashAutoDrive.initialY,
                            onX = { onDashAutoDrive(dashAutoDrive.copy(initialX = it)) },
                            onY = { onDashAutoDrive(dashAutoDrive.copy(initialY = it)) })
                        GroupDivider()
                        TapPositionRow("Arrival screen", dashAutoDrive.arrivedX, dashAutoDrive.arrivedY,
                            onX = { onDashAutoDrive(dashAutoDrive.copy(arrivedX = it)) },
                            onY = { onDashAutoDrive(dashAutoDrive.copy(arrivedY = it)) })
                    }
                }
            }
            Text(
                "Casts the real app to the dash in landscape with the screen off. Run setup once " +
                    "while Wi-Fi is connected; after that it can start without Wi-Fi until restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader("About")
        SettingsGroup {
            AppRow()
            GroupDivider()
            LinkRow("Source code") { uriHandler.openUri(REPO_URL) }
            GroupDivider()
            LinkRow("Report an issue") { uriHandler.openUri("$REPO_URL/issues") }
            GroupDivider()
            LinkRow("Changelog") { uriHandler.openUri("$REPO_URL/blob/main/CHANGELOG.md") }
        }

        Spacer(Modifier.height(28.dp))
        MadeByCredit { uriHandler.openUri("https://github.com/alexandrevega") }
        Spacer(Modifier.height(16.dp))
    }
}


@Composable
private fun PresetSettings(
    presets: List<SettingsPreset>,
    onApply: (String) -> Boolean,
    onSave: (String, Boolean) -> Boolean,
    onDelete: (String) -> Boolean,
) {
    var selected by remember { mutableStateOf(presets.firstOrNull()?.name.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val chosen = presets.firstOrNull { it.name == selected }
    SectionHeader("Settings presets")
    SettingsGroup {
        Box(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chosen?.let { "${it.name}   ·   ${it.dpi} dpi / ${it.layoutScale}" } ?: "Select preset",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text("▼", color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text("${preset.name}   ·   ${preset.dpi} dpi / ${preset.layoutScale}") },
                        onClick = { selected = preset.name; expanded = false; error = "" },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { action = "apply" },
                enabled = chosen != null,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp),
            ) { Text("Apply", maxLines = 1, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { newName = ""; error = ""; action = "new" },
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp),
            ) { Text("Save new", maxLines = 1, fontSize = 12.sp) }
            OutlinedButton(
                onClick = { action = "overwrite" },
                enabled = chosen != null && !chosen.isDefault,
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 3.dp, vertical = 8.dp),
            ) { Text("Overwrite", maxLines = 1, fontSize = 12.sp) }
            IconButton(
                onClick = { action = "delete" },
                enabled = chosen != null && !chosen.isDefault,
                modifier = Modifier.size(36.dp),
            ) { Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete preset", modifier = Modifier.size(18.dp)) }
        }
        if (error.isNotEmpty()) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (action != null) {
        val operation = action
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(when (operation) {
                "apply" -> "Apply preset?"
                "overwrite" -> "Overwrite preset?"
                "delete" -> "Delete preset?"
                else -> "Save new preset"
            }) },
            text = {
                if (operation == "new") {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(80) },
                        label = { Text("Preset name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(when (operation) {
                        "apply" -> "Replace current settings with \"${chosen?.name}\"? Changes take effect on next Start."
                        "overwrite" -> "Replace \"${chosen?.name}\" with the current settings?"
                        else -> "Permanently delete \"${chosen?.name}\"?"
                    })
                }
            },
            confirmButton = {
                TextButton(
                    enabled = operation != "new" || newName.trim().isNotEmpty(),
                    onClick = {
                        val ok = when (operation) {
                            "apply" -> chosen?.let { onApply(it.name) } ?: false
                            "overwrite" -> chosen?.let { onSave(it.name, true) } ?: false
                            "delete" -> chosen?.let { onDelete(it.name) } ?: false
                            else -> onSave(newName.trim(), false)
                        }
                        if (ok) {
                            if (operation == "new") selected = newName.trim()
                            if (operation == "delete") selected = presets.firstOrNull()?.name.orEmpty()
                            error = ""
                            action = null
                        } else {
                            error = if (operation == "new") "Cannot save: the name may already exist." else "Operation failed."
                            action = null
                        }
                    },
                ) { Text(if (operation == "new") "Save" else when (operation) {
                    "apply" -> "Apply"
                    "overwrite" -> "Overwrite"
                    else -> "Delete"
                }) }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun SettingSlider(
    label: String,
    value: String,
    current: Float,
    min: Float,
    max: Float,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = current, onValueChange = onChange, valueRange = min..max, steps = steps)
    }
}

@Composable
private fun DashDpiSelector(dpi: Int, onDpi: (Int) -> Unit) {
    val value = DashDpi.snap(dpi)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("DPI", style = MaterialTheme.typography.bodyLarge)
            Text("$value dpi", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDpi(DashDpi.snap(value - DashDpi.STEP)) },
                enabled = value > DashDpi.MIN, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease DPI by 10")
            }
            Slider(value = value.toFloat(),
                onValueChange = { onDpi(DashDpi.snap(it.roundToInt())) },
                valueRange = DashDpi.MIN.toFloat()..DashDpi.MAX.toFloat(),
                steps = (DashDpi.MAX - DashDpi.MIN) / DashDpi.STEP - 1,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { onDpi(DashDpi.snap(value + DashDpi.STEP)) },
                enabled = value < DashDpi.MAX, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Increase DPI by 10")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${DashDpi.MIN} dpi", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${DashDpi.MAX} dpi", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PixelMarginSelector(label: String, px: Int, maximum: Int, onChange: (Int) -> Unit) =
    NumericSelector(label, px, maximum, "px", onChange)

@Composable
private fun TapPositionRow(
    title: String,
    x: Int,
    y: Int,
    onX: (Int) -> Unit,
    onY: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TapPercentAxis("Horizontal", x, onX, Modifier.weight(1f))
            TapPercentAxis("Vertical", y, onY, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TapPercentAxis(
    label: String,
    input: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = input.coerceIn(0, 100)
    var editing by remember { mutableStateOf(false) }
    var editValue by remember { mutableStateOf(value.toString()) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - 1).coerceAtLeast(0)) },
                enabled = value > 0, modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label",
                    modifier = Modifier.size(18.dp))
            }
            Text("$value%", modifier = Modifier.width(54.dp).clickable {
                editValue = value.toString()
                editing = true
            }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center)
            IconButton(
                onClick = { onChange((value + 1).coerceAtMost(100)) },
                enabled = value < 100, modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label",
                    modifier = Modifier.size(18.dp))
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("$label (0–100%)") },
            text = {
                OutlinedTextField(
                    value = editValue,
                    onValueChange = { updated ->
                        if (updated.length <= 3 && updated.all { it.isDigit() }) editValue = updated
                    },
                    label = { Text("Position (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editValue.toIntOrNull()?.let { onChange(it.coerceIn(0, 100)) }
                        editing = false
                    },
                    enabled = editValue.toIntOrNull() != null,
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NumericSelector(label: String, input: Int, maximum: Int, unit: String, onChange: (Int) -> Unit) {
    val value = input.coerceIn(0, maximum)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text("$value $unit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange((value - 1).coerceAtLeast(0)) }, enabled = value > 0, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Remove, contentDescription = "Decrease $label")
            }
            Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(0, maximum)) },
                valueRange = 0f..maximum.toFloat(), modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange((value + 1).coerceAtMost(maximum)) }, enabled = value < maximum, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Increase $label")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$maximum $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun MarginColorSelector(selected: DashMarginColor, onSelect: (DashMarginColor) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Margin color", style = MaterialTheme.typography.bodyLarge)
            Text(selected.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Change", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Margin color") },
        text = { Column { DashMarginColor.values().forEachIndexed { i, option ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(option); choosing = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(option.label, modifier = Modifier.weight(1f))
                if (selected == option) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            if (i < DashMarginColor.values().lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        } } },
        confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } })
}
@Composable
private fun DashResolutionSelector(
    selected: DashResolution,
    onSelect: (DashResolution) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Map layout size", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${selected.label} — The image always fits the dashboard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Change",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Map layout size") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    DashResolution.values().forEachIndexed { index, option ->
                        ResolutionDialogRow(
                            option = option,
                            selected = option == selected,
                            onClick = {
                                onSelect(option)
                                choosing = false
                            },
                        )
                        if (index != DashResolution.values().lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { choosing = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DashAppSelector(
    apps: List<DashApp>,
    selectedPackage: String?,
    onSelect: (String?) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val selected = apps.firstOrNull { it.packageName == selectedPackage }
    Row(
        Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("App to display", style = MaterialTheme.typography.bodyLarge)
            Text(
                selected?.label ?: if (selectedPackage != null) "Unavailable — select another app" else "Not selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("Change", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("App to display") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (apps.isEmpty()) Text("No launchable applications found.")
                    apps.forEach { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onSelect(app.packageName)
                                choosing = false
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (app.packageName == selectedPackage)
                                Icon(Icons.Filled.Check, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ResolutionDialogRow(
    option: DashResolution,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (option) {
                DashResolution.Scale1 -> "${option.label} — Low detail · lower battery"
                DashResolution.Balanced -> "${option.label} — Recommended"
                DashResolution.Scale3 -> "${option.label} — High detail · higher battery"
                else -> option.label
            },
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun AppRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = "Pillion",
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Pillion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Version ${AppInfo.VERSION}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MadeByCredit(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Made with ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color(0xFFFF5C8A),
            modifier = Modifier.size(13.dp),
        )
        Text(" by ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "@alexandrevega",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
