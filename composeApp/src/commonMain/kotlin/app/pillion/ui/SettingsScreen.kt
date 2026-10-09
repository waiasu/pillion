package app.pillion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.AppInfo
import app.pillion.core.DashMarginColor
import app.pillion.core.DashMargins
import app.pillion.core.DashResolution
import app.pillion.core.DashScale
import app.pillion.core.LaunchableApp
import app.pillion.core.ThemeMode
import app.pillion.resources.Res
import app.pillion.resources.app_icon
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.painterResource
import app.pillion.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    quality: Int,
    onQuality: (Int) -> Unit,
    dashDpi: Int = 220,
    onDashDpi: (Int) -> Unit = {},
    maxFps: Double,
    onMaxFps: (Double) -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    dashSupported: Boolean = false,
    dashEnabled: Boolean = false,
    dashScale: DashScale = DashScale.DEFAULT,
    onDashScale: (DashScale) -> Unit = {},
    draftLeftMargin: Int = 0,
    onDraftLeftMargin: (Int) -> Unit = {},
    draftBottomMargin: Int = 0,
    onDraftBottomMargin: (Int) -> Unit = {},
    draftMarginColor: DashMarginColor = DashMarginColor.Black,
    onDraftMarginColor: (DashMarginColor) -> Unit = {},
    draftStickZoomInX: Int = 94,
    onDraftStickZoomInX: (Int) -> Unit = {},
    draftStickZoomInY: Int = 48,
    onDraftStickZoomInY: (Int) -> Unit = {},
    draftStickZoomOutX: Int = 94,
    onDraftStickZoomOutX: (Int) -> Unit = {},
    draftStickZoomOutY: Int = 77,
    onDraftStickZoomOutY: (Int) -> Unit = {},
    draftOcrEnabled: Boolean = false,
    onDraftOcrEnabled: (Boolean) -> Unit = {},
    draftOcrShowArea: Boolean = false,
    onDraftOcrShowArea: (Boolean) -> Unit = {},
    draftOcrLeft: Int = 30,
    onDraftOcrLeft: (Int) -> Unit = {},
    draftOcrTop: Int = 89,
    onDraftOcrTop: (Int) -> Unit = {},
    draftOcrRight: Int = 70,
    onDraftOcrRight: (Int) -> Unit = {},
    draftOcrBottom: Int = 100,
    onDraftOcrBottom: (Int) -> Unit = {},
    draftOcr2Left: Int = 86,
    onDraftOcr2Left: (Int) -> Unit = {},
    draftOcr2Top: Int = 52,
    onDraftOcr2Top: (Int) -> Unit = {},
    draftOcr2Right: Int = 98,
    onDraftOcr2Right: (Int) -> Unit = {},
    draftOcr2Bottom: Int = 64,
    onDraftOcr2Bottom: (Int) -> Unit = {},
    draftRestartAppOnVd: Boolean = false,
    onDraftRestartAppOnVd: (Boolean) -> Unit = {},
    draftFixedDashAppEnabled: Boolean = false,
    onDraftFixedDashAppEnabled: (Boolean) -> Unit = {},
    draftFixedDashAppPackage: String? = null,
    onDraftFixedDashAppPackage: (String?) -> Unit = {},
    launchableApps: List<LaunchableApp> = emptyList(),
    restartPending: Boolean = false,
    onSaveAndRestart: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    onSetUpDash: () -> Unit = {},
    onDisableDash: () -> Unit = {},
    bikeName: String = "",
    onChangeBike: () -> Unit = {},
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var showFixedAppPicker by remember { mutableStateOf(false) }
    val selectedFixedApp = launchableApps.firstOrNull { it.packageName == draftFixedDashAppPackage }

    if (showFixedAppPicker) {
        AlertDialog(
            onDismissRequest = { showFixedAppPicker = false },
            title = { Text("Select app for VD") },
            text = {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                onDraftFixedDashAppPackage(null)
                                showFixedAppPicker = false
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.not_selected))
                            Text(
                                "Falls back to Pillion when fixed-app mode is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (draftFixedDashAppPackage == null) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    }
                    launchableApps.forEach { app ->
                        GroupDivider()
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    onDraftFixedDashAppPackage(app.packageName)
                                    showFixedAppPicker = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (draftFixedDashAppPackage == app.packageName) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFixedAppPicker = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

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
                    contentDescription = stringResource(Res.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(stringResource(Res.string.settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        SectionHeader(stringResource(Res.string.appearance))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            val options = listOf(
                ThemeMode.SYSTEM to stringResource(Res.string.system),
                ThemeMode.LIGHT to stringResource(Res.string.light),
                ThemeMode.DARK to stringResource(Res.string.dark),
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
        SectionHeader(stringResource(Res.string.motorcycle))
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onChangeBike).padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (bikeName.isEmpty()) stringResource(Res.string.not_selected) else bikeName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(Res.string.tap_switch_head_unit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(Res.string.change),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(Res.string.mirroring))
        SettingsGroup {
            SettingSlider(stringResource(Res.string.image_quality), "$quality", quality.toFloat(), 10f, 80f) { onQuality(it.roundToInt()) }
            if (dashSupported) {
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.dpi),
                    "$dashDpi dpi",
                    dashDpi.toFloat(),
                    160f,
                    480f,
                    step = 10f,
                ) { onDashDpi((it.roundToInt() / 10) * 10) }
            }
            GroupDivider()
            val fpsChoices = (1..15).map { it.toDouble() }
            val fpsIndex = fpsChoices.indexOf(maxFps).takeIf { it >= 0 } ?: 2
            SettingSlider(
                stringResource(Res.string.max_frame_rate),
                "${formatMaxFps(maxFps)} fps",
                fpsIndex.toFloat(),
                0f,
                fpsChoices.lastIndex.toFloat(),
                step = 1f,
            ) { rawIndex ->
                onMaxFps(fpsChoices[rawIndex.roundToInt().coerceIn(0, fpsChoices.lastIndex)])
            }
            if (dashSupported) {
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.left_margin),
                    "$draftLeftMargin px",
                    draftLeftMargin.toFloat(),
                    0f,
                    DashMargins.maxLeft().toFloat(),
                    step = DashMargins.STEP_PX.toFloat(),
                ) { onDraftLeftMargin(DashMargins.clampLeft(it.roundToInt())) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.bottom_margin),
                    "$draftBottomMargin px",
                    draftBottomMargin.toFloat(),
                    0f,
                    DashMargins.maxBottom().toFloat(),
                    step = DashMargins.STEP_PX.toFloat(),
                ) { onDraftBottomMargin(DashMargins.clampBottom(it.roundToInt())) }
                GroupDivider()
                MarginColorSelector(draftMarginColor, onDraftMarginColor)
            }
        }
        if (!dashSupported) {
            Text(
                stringResource(Res.string.image_quality_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            )
        }

        if (dashSupported) {
            Spacer(Modifier.height(24.dp))
            SectionHeader(stringResource(Res.string.dedicated_dash_display))
            SettingsGroup {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (dashEnabled) stringResource(Res.string.on) else stringResource(Res.string.off),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stringResource(Res.string.dash_keep_nav),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (dashEnabled) {
                        OutlinedButton(onClick = onDisableDash, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.disable)) }
                    } else {
                        Button(onClick = onSetUpDash, shape = RoundedCornerShape(12.dp)) { Text(stringResource(Res.string.set_up)) }
                    }
                }
                if (dashEnabled) {
                    GroupDivider()
                    LinkRow(stringResource(Res.string.rerun_setup)) { onSetUpDash() }
                }
                GroupDivider()
                DashResolutionSelector(dashScale, draftLeftMargin, draftBottomMargin, onDashScale)
                GroupDivider()
                Text(
                    stringResource(Res.string.stick_tap_position),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                Text(
                    stringResource(Res.string.stick_tap_position_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                SettingSlider(
                    stringResource(Res.string.zoom_in_horizontal),
                    "$draftStickZoomInX%",
                    draftStickZoomInX.toFloat(),
                    0f,
                    100f,
                    step = 1f,
                ) { onDraftStickZoomInX(it.roundToInt().coerceIn(0, 100)) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.zoom_in_vertical),
                    "$draftStickZoomInY%",
                    draftStickZoomInY.toFloat(),
                    0f,
                    100f,
                    step = 1f,
                ) { onDraftStickZoomInY(it.roundToInt().coerceIn(0, 100)) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.zoom_out_horizontal),
                    "$draftStickZoomOutX%",
                    draftStickZoomOutX.toFloat(),
                    0f,
                    100f,
                    step = 1f,
                ) { onDraftStickZoomOutX(it.roundToInt().coerceIn(0, 100)) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.zoom_out_vertical),
                    "$draftStickZoomOutY%",
                    draftStickZoomOutY.toFloat(),
                    0f,
                    100f,
                    step = 1f,
                ) { onDraftStickZoomOutY(it.roundToInt().coerceIn(0, 100)) }
                GroupDivider()
                Text(
                    stringResource(Res.string.ocr_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
                Text(
                    stringResource(Res.string.ocr_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.ocr_enable), modifier = Modifier.weight(1f))
                    Switch(checked = draftOcrEnabled, onCheckedChange = onDraftOcrEnabled)
                }
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(Res.string.ocr_show_area), modifier = Modifier.weight(1f))
                    Switch(
                        checked = draftOcrShowArea,
                        onCheckedChange = onDraftOcrShowArea,
                        enabled = draftOcrEnabled,
                    )
                }
                GroupDivider()
                Text(
                    stringResource(Res.string.ocr_area_1),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                SettingSlider(
                    stringResource(Res.string.ocr_left),
                    "$draftOcrLeft%",
                    draftOcrLeft.toFloat(),
                    20f,
                    80f,
                    step = 1f,
                ) { onDraftOcrLeft(it.roundToInt().coerceIn(20, 80)) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.ocr_top),
                    "$draftOcrTop%",
                    draftOcrTop.toFloat(),
                    80f,
                    100f,
                    step = 1f,
                ) { onDraftOcrTop(it.roundToInt().coerceIn(80, 100)) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.ocr_right),
                    "$draftOcrRight%",
                    draftOcrRight.toFloat(),
                    20f,
                    80f,
                    step = 1f,
                ) { onDraftOcrRight(it.roundToInt().coerceIn(20, 80)) }
                GroupDivider()
                SettingSlider(
                    stringResource(Res.string.ocr_bottom),
                    "$draftOcrBottom%",
                    draftOcrBottom.toFloat(),
                    80f,
                    100f,
                    step = 1f,
                ) { onDraftOcrBottom(it.roundToInt().coerceIn(80, 100)) }
                GroupDivider()
                Text(
                    stringResource(Res.string.ocr_area_2),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                SettingSlider(stringResource(Res.string.ocr_left), "$draftOcr2Left%", draftOcr2Left.toFloat(), 0f, 100f, step = 1f) {
                    onDraftOcr2Left(it.roundToInt().coerceIn(0, 100))
                }
                GroupDivider()
                SettingSlider(stringResource(Res.string.ocr_top), "$draftOcr2Top%", draftOcr2Top.toFloat(), 0f, 100f, step = 1f) {
                    onDraftOcr2Top(it.roundToInt().coerceIn(0, 100))
                }
                GroupDivider()
                SettingSlider(stringResource(Res.string.ocr_right), "$draftOcr2Right%", draftOcr2Right.toFloat(), 0f, 100f, step = 1f) {
                    onDraftOcr2Right(it.roundToInt().coerceIn(0, 100))
                }
                GroupDivider()
                SettingSlider(stringResource(Res.string.ocr_bottom), "$draftOcr2Bottom%", draftOcr2Bottom.toFloat(), 0f, 100f, step = 1f) {
                    onDraftOcr2Bottom(it.roundToInt().coerceIn(0, 100))
                }
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Restart app on VD")
                        Text(
                            "Restart the app after moving it to the virtual display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = draftRestartAppOnVd, onCheckedChange = onDraftRestartAppOnVd)
                }
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Always use selected app on VD")
                        Text(
                            "Promote the selected app instead of the current foreground app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = draftFixedDashAppEnabled,
                        onCheckedChange = onDraftFixedDashAppEnabled,
                    )
                }
                GroupDivider()
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { showFixedAppPicker = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Selected app")
                        Text(
                            selectedFixedApp?.label
                                ?: draftFixedDashAppPackage
                                ?: stringResource(Res.string.not_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        stringResource(Res.string.change),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                stringResource(Res.string.dash_feature_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            )
        }

        if (dashSupported) {
            Spacer(Modifier.height(24.dp))
            SettingsGroup {
                RestartRow(restartPending, onSaveAndRestart)
            }
            Text(
                stringResource(Res.string.save_restart_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            )

            Spacer(Modifier.height(12.dp))
            SettingsGroup {
                DiagnosticExportRow(onExportDiagnostics)
            }
            Text(
                "Saves recent Pillion connection history plus a one-time Android/Bluetooth snapshot to Downloads. " +
                    "Full-device logcat is collected only when you tap Export.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 8.dp, end = 6.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(stringResource(Res.string.about))
        SettingsGroup {
            AppRow()
            GroupDivider()
            LinkRow(stringResource(Res.string.source_code)) { uriHandler.openUri(REPO_URL) }
            GroupDivider()
            LinkRow(stringResource(Res.string.report_issue)) { uriHandler.openUri("$REPO_URL/issues") }
            GroupDivider()
            LinkRow(stringResource(Res.string.changelog)) { uriHandler.openUri("$REPO_URL/blob/main/CHANGELOG.md") }
        }

        Spacer(Modifier.height(28.dp))
        MadeByCredit { uriHandler.openUri("https://github.com/alexandrevega") }
        Spacer(Modifier.height(16.dp))
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
    step: Float? = null,
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
        Slider(
            value = current,
            onValueChange = onChange,
            valueRange = min..max,
            steps = step?.let { (((max - min) / it).roundToInt() - 1).coerceAtLeast(0) } ?: 0,
        )
    }
}

@Composable
private fun MarginColorSelector(
    selected: DashMarginColor,
    onSelect: (DashMarginColor) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.margin_color), style = MaterialTheme.typography.bodyLarge)
            Text(
                selected.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(Res.string.change),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(Res.string.margin_color)) },
            text = {
                Column {
                    DashMarginColor.entries.forEachIndexed { index, option ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onSelect(option)
                                choosing = false
                            }.padding(vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                option.label,
                                modifier = Modifier.weight(1f),
                                color = if (selected == option) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            if (selected == option) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (index != DashMarginColor.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun RestartRow(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.save_settings_restart), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            stringResource(Res.string.save_restart),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun DiagnosticExportRow(onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onExport).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Export diagnostic log", style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            "Export",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DashResolutionSelector(
    selected: DashScale,
    leftMargin: Int,
    bottomMargin: Int,
    onSelect: (DashScale) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    val selectedResolution = DashResolution.forLayout(leftMargin, bottomMargin, selected.tenths)

    Row(
        Modifier.fillMaxWidth().clickable { choosing = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.map_layout_size), style = MaterialTheme.typography.bodyLarge)
            Text(
                "${selectedResolution.label} - ${resolutionDetail(selected)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(Res.string.change),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(Res.string.map_layout_size)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(Res.string.virtual_display_size_help, DashResolution.OUTPUT_WIDTH, DashResolution.OUTPUT_HEIGHT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    DashScale.entries.forEachIndexed { index, option ->
                        val resolution = DashResolution.forLayout(leftMargin, bottomMargin, option.tenths)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onSelect(option)
                                choosing = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    resolution.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (selected == option) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected == option) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    resolutionDetail(option),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selected == option) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        if (index != DashScale.entries.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}

@Composable
private fun resolutionDetail(option: DashScale): String = when (option) {
    DashScale.X10 -> stringResource(Res.string.matches_usable_area)
    DashScale.X20 -> "${option.label} · ${stringResource(Res.string.recommended)}"
    DashScale.X30 -> "${option.label} · ${stringResource(Res.string.most_detail_heavy)}"
    else -> "${option.label} · ${stringResource(Res.string.more_detail)}"
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
                stringResource(Res.string.version_format, AppInfo.VERSION),
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

private fun formatMaxFps(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
