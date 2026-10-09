package app.pillion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.pillion.core.DashMarginColor
import app.pillion.core.DashResolution
import app.pillion.core.DashScale
import app.pillion.core.DashSetup
import app.pillion.core.MirrorController
import app.pillion.core.LaunchableApp
import app.pillion.core.MirrorSettings
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.HeadUnitRegistry
import app.pillion.resources.*
import org.jetbrains.compose.resources.stringResource

/** The public GitHub repository — shown in-app so anyone can read the source. */
const val REPO_URL = "https://github.com/alexandrevega/pillion"

@Composable
fun App(
    controllerFor: (HeadUnitProfile) -> MirrorController,
    settingsStore: SettingsStore? = null,
    dashSetup: DashSetup? = null,
    launchableApps: List<LaunchableApp> = emptyList(),
    onRestartApp: () -> Unit = {},
    onResetAndRestartApp: () -> Unit = {},
    onExportDiagnostics: () -> Unit = {},
    startRequestInFlight: Boolean? = null,
) {
    var themeMode by remember { mutableStateOf(settingsStore?.themeMode() ?: ThemeMode.SYSTEM) }
    PillionTheme(themeMode) {
        var selectedBikeId by remember { mutableStateOf(settingsStore?.selectedBikeId()) }
        var changingBike by remember { mutableStateOf(false) }
        val profile = selectedBikeId?.let { HeadUnitRegistry.byId(it) }

        if (profile == null) {
            OnboardingScreen(
                profiles = HeadUnitRegistry.all(),
                onSelect = { p -> settingsStore?.setSelectedBikeId(p.id); selectedBikeId = p.id; changingBike = false },
                skipIntro = changingBike,
            )
            return@PillionTheme
        }

        val controller = remember(profile.id) { controllerFor(profile) }
        val state by controller.state.collectAsState()

        // These mirroring/dash-layout/tap values are restart-applied settings. Keep the active
        // (saved) values separate from UI drafts so backing out of Settings discards changes.
        val savedQuality = remember { settingsStore?.imageQuality() ?: 80 }
        val savedDashDpi = remember { settingsStore?.dashDpi() ?: 220 }
        val savedMaxFps = remember { settingsStore?.maxFps() ?: 3.0 }
        val savedDashScale = remember {
            DashScale.fromTenths(settingsStore?.dashScaleTenths() ?: DashScale.DEFAULT.tenths)
        }
        val savedLeftMargin = remember { settingsStore?.dashLeftMargin() ?: 50 }
        val savedBottomMargin = remember { settingsStore?.dashBottomMargin() ?: 20 }
        val savedMarginColor = remember { settingsStore?.dashMarginColor() ?: DashMarginColor.Black }
        val savedStickZoomInX = remember { settingsStore?.stickZoomInXPercent() ?: 94 }
        val savedStickZoomInY = remember { settingsStore?.stickZoomInYPercent() ?: 48 }
        val savedStickZoomOutX = remember { settingsStore?.stickZoomOutXPercent() ?: 94 }
        val savedStickZoomOutY = remember { settingsStore?.stickZoomOutYPercent() ?: 77 }
        val savedOcrEnabled = remember { settingsStore?.ocrEnabled() ?: false }
        val savedOcrShowArea = remember { settingsStore?.ocrShowArea() ?: false }
        val savedOcrLeft = remember { settingsStore?.ocrLeftPercent() ?: 30 }
        val savedOcrTop = remember { settingsStore?.ocrTopPercent() ?: 89 }
        val savedOcrRight = remember { settingsStore?.ocrRightPercent() ?: 70 }
        val savedOcrBottom = remember { settingsStore?.ocrBottomPercent() ?: 100 }
        val savedOcr2Left = remember { settingsStore?.ocr2LeftPercent() ?: 88 }
        val savedOcr2Top = remember { settingsStore?.ocr2TopPercent() ?: 56 }
        val savedOcr2Right = remember { settingsStore?.ocr2RightPercent() ?: 98 }
        val savedOcr2Bottom = remember { settingsStore?.ocr2BottomPercent() ?: 64 }
        val savedRestartAppOnVd = remember { settingsStore?.restartAppOnVd() ?: false }
        val savedFixedDashAppEnabled = remember { settingsStore?.fixedDashAppEnabled() ?: false }
        val savedFixedDashAppPackage = remember { settingsStore?.fixedDashAppPackage() }

        var draftQuality by rememberSaveable { mutableStateOf(savedQuality) }
        var draftDashDpi by rememberSaveable { mutableStateOf(savedDashDpi) }
        var draftMaxFps by rememberSaveable { mutableStateOf(savedMaxFps) }
        var draftDashScale by remember { mutableStateOf(savedDashScale) }
        var draftLeftMargin by rememberSaveable { mutableStateOf(savedLeftMargin) }
        var draftBottomMargin by rememberSaveable { mutableStateOf(savedBottomMargin) }
        var draftMarginColor by remember { mutableStateOf(savedMarginColor) }
        var draftStickZoomInX by rememberSaveable { mutableStateOf(savedStickZoomInX) }
        var draftStickZoomInY by rememberSaveable { mutableStateOf(savedStickZoomInY) }
        var draftStickZoomOutX by rememberSaveable { mutableStateOf(savedStickZoomOutX) }
        var draftStickZoomOutY by rememberSaveable { mutableStateOf(savedStickZoomOutY) }
        var draftOcrEnabled by rememberSaveable { mutableStateOf(savedOcrEnabled) }
        var draftOcrShowArea by rememberSaveable { mutableStateOf(savedOcrShowArea) }
        var draftOcrLeft by rememberSaveable { mutableStateOf(savedOcrLeft) }
        var draftOcrTop by rememberSaveable { mutableStateOf(savedOcrTop) }
        var draftOcrRight by rememberSaveable { mutableStateOf(savedOcrRight) }
        var draftOcrBottom by rememberSaveable { mutableStateOf(savedOcrBottom) }
        var draftOcr2Left by rememberSaveable { mutableStateOf(savedOcr2Left) }
        var draftOcr2Top by rememberSaveable { mutableStateOf(savedOcr2Top) }
        var draftOcr2Right by rememberSaveable { mutableStateOf(savedOcr2Right) }
        var draftOcr2Bottom by rememberSaveable { mutableStateOf(savedOcr2Bottom) }
        var draftRestartAppOnVd by rememberSaveable { mutableStateOf(savedRestartAppOnVd) }
        var draftFixedDashAppEnabled by rememberSaveable { mutableStateOf(savedFixedDashAppEnabled) }
        var draftFixedDashAppPackage by rememberSaveable { mutableStateOf(savedFixedDashAppPackage) }

        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showDashOnboarding by rememberSaveable { mutableStateOf(false) }
        var restartAction by remember { mutableStateOf<RestartAction?>(null) }
        var dashEnabled by remember { mutableStateOf(settingsStore?.dashEnabled() ?: false) }

        val activeDashResolution = DashResolution.forLayout(
            savedLeftMargin,
            savedBottomMargin,
            savedDashScale.tenths,
        )
        val restartPending = draftQuality != savedQuality ||
            draftDashDpi != savedDashDpi ||
            draftMaxFps != savedMaxFps ||
            draftDashScale != savedDashScale ||
            draftLeftMargin != savedLeftMargin ||
            draftBottomMargin != savedBottomMargin ||
            draftMarginColor != savedMarginColor ||
            draftStickZoomInX != savedStickZoomInX ||
            draftStickZoomInY != savedStickZoomInY ||
            draftStickZoomOutX != savedStickZoomOutX ||
            draftStickZoomOutY != savedStickZoomOutY ||
            draftOcrEnabled != savedOcrEnabled ||
            draftOcrShowArea != savedOcrShowArea ||
            draftOcrLeft != savedOcrLeft ||
            draftOcrTop != savedOcrTop ||
            draftOcrRight != savedOcrRight ||
            draftOcrBottom != savedOcrBottom ||
            draftOcr2Left != savedOcr2Left ||
            draftOcr2Top != savedOcr2Top ||
            draftOcr2Right != savedOcr2Right ||
            draftOcr2Bottom != savedOcr2Bottom ||
            draftRestartAppOnVd != savedRestartAppOnVd ||
            draftFixedDashAppEnabled != savedFixedDashAppEnabled ||
            draftFixedDashAppPackage != savedFixedDashAppPackage

        BackHandler(enabled = restartAction != null) { }
        BackHandler(enabled = restartAction == null && (showSettings || showDashOnboarding)) {
            if (showDashOnboarding) {
                dashSetup?.cancelPairingAssistant()
                showDashOnboarding = false
            } else {
                showSettings = false
            }
        }

        if (restartAction != null) {
            RestartScreen(restartAction!!)
        } else if (showDashOnboarding && dashSetup != null) {
            DashOnboarding(
                dash = dashSetup,
                onOptOut = {
                    dashSetup?.cancelPairingAssistant()
                    dashEnabled = false; settingsStore?.setDashEnabled(false); showDashOnboarding = false
                },
                onFinish = {
                    dashSetup?.cancelPairingAssistant()
                    dashEnabled = true; settingsStore?.setDashEnabled(true); showDashOnboarding = false
                },
                onClose = {
                    dashSetup?.cancelPairingAssistant()
                    showDashOnboarding = false
                },
            )
        } else if (showSettings) {
            SettingsScreen(
                quality = draftQuality,
                onQuality = { draftQuality = it },
                dashDpi = draftDashDpi,
                onDashDpi = { draftDashDpi = it },
                maxFps = draftMaxFps,
                onMaxFps = { draftMaxFps = it },
                themeMode = themeMode,
                onThemeMode = { themeMode = it; settingsStore?.setThemeMode(it) },
                dashSupported = dashSetup != null,
                dashEnabled = dashEnabled,
                dashScale = draftDashScale,
                onDashScale = { draftDashScale = it },
                draftLeftMargin = draftLeftMargin,
                onDraftLeftMargin = { draftLeftMargin = it },
                draftBottomMargin = draftBottomMargin,
                onDraftBottomMargin = { draftBottomMargin = it },
                draftMarginColor = draftMarginColor,
                onDraftMarginColor = { draftMarginColor = it },
                draftStickZoomInX = draftStickZoomInX,
                onDraftStickZoomInX = { draftStickZoomInX = it },
                draftStickZoomInY = draftStickZoomInY,
                onDraftStickZoomInY = { draftStickZoomInY = it },
                draftStickZoomOutX = draftStickZoomOutX,
                onDraftStickZoomOutX = { draftStickZoomOutX = it },
                draftStickZoomOutY = draftStickZoomOutY,
                onDraftStickZoomOutY = { draftStickZoomOutY = it },
                draftOcrEnabled = draftOcrEnabled,
                onDraftOcrEnabled = { draftOcrEnabled = it },
                draftOcrShowArea = draftOcrShowArea,
                onDraftOcrShowArea = { draftOcrShowArea = it },
                draftOcrLeft = draftOcrLeft,
                onDraftOcrLeft = { draftOcrLeft = it },
                draftOcrTop = draftOcrTop,
                onDraftOcrTop = { draftOcrTop = it },
                draftOcrRight = draftOcrRight,
                onDraftOcrRight = { draftOcrRight = it },
                draftOcrBottom = draftOcrBottom,
                onDraftOcrBottom = { draftOcrBottom = it },
                draftOcr2Left = draftOcr2Left,
                onDraftOcr2Left = { draftOcr2Left = it },
                draftOcr2Top = draftOcr2Top,
                onDraftOcr2Top = { draftOcr2Top = it },
                draftOcr2Right = draftOcr2Right,
                onDraftOcr2Right = { draftOcr2Right = it },
                draftOcr2Bottom = draftOcr2Bottom,
                onDraftOcr2Bottom = { draftOcr2Bottom = it },
                draftRestartAppOnVd = draftRestartAppOnVd,
                onDraftRestartAppOnVd = { draftRestartAppOnVd = it },
                draftFixedDashAppEnabled = draftFixedDashAppEnabled,
                onDraftFixedDashAppEnabled = { draftFixedDashAppEnabled = it },
                draftFixedDashAppPackage = draftFixedDashAppPackage,
                onDraftFixedDashAppPackage = { draftFixedDashAppPackage = it },
                launchableApps = launchableApps,
                restartPending = restartPending,
                onSaveAndRestart = {
                    if (restartPending) {
                        // commit()-backed setters are intentional: the process may be restarted immediately.
                        settingsStore?.setImageQuality(draftQuality)
                        settingsStore?.setDashDpi(draftDashDpi)
                        settingsStore?.setMaxFps(draftMaxFps)
                        settingsStore?.setDashScaleTenths(draftDashScale.tenths)
                        settingsStore?.setDashLayout(draftLeftMargin, draftBottomMargin, draftMarginColor)
                        settingsStore?.setStickTapPositions(
                            draftStickZoomInX,
                            draftStickZoomInY,
                            draftStickZoomOutX,
                            draftStickZoomOutY,
                        )
                        settingsStore?.setOcrSettings(
                            draftOcrEnabled,
                            draftOcrShowArea,
                            draftOcrLeft,
                            draftOcrTop,
                            draftOcrRight,
                            draftOcrBottom,
                            draftOcr2Left,
                            draftOcr2Top,
                            draftOcr2Right,
                            draftOcr2Bottom,
                        )
                        settingsStore?.setRestartAppOnVd(draftRestartAppOnVd)
                        settingsStore?.setFixedDashApp(draftFixedDashAppEnabled, draftFixedDashAppPackage)
                        // Cover Settings immediately; the helper performs the final force-stop and
                        // fresh launch after the existing cleanup/settle sequence completes.
                        restartAction = RestartAction.Save
                        onRestartApp()
                    }
                },
                onExportDiagnostics = onExportDiagnostics,
                onSetUpDash = { showDashOnboarding = true },
                onDisableDash = { dashEnabled = false; settingsStore?.setDashEnabled(false) },
                bikeName = profile.displayName,
                onChangeBike = { showSettings = false; changingBike = true; selectedBikeId = null },
                onBack = { showSettings = false },
            )
        } else {
            HomeScreen(
                state = state,
                startRequestInFlight = startRequestInFlight,
                onOpenSettings = { showSettings = true },
                onStart = {
                    controller.start(
                        MirrorSettings(
                            quality = savedQuality,
                            dashDpi = savedDashDpi,
                            maxFps = savedMaxFps,
                            dashResolution = activeDashResolution,
                            dashLeftMargin = savedLeftMargin,
                            dashBottomMargin = savedBottomMargin,
                            dashMarginColor = savedMarginColor,
                            stickZoomInXPercent = savedStickZoomInX,
                            stickZoomInYPercent = savedStickZoomInY,
                            stickZoomOutXPercent = savedStickZoomOutX,
                            stickZoomOutYPercent = savedStickZoomOutY,
                            ocrEnabled = savedOcrEnabled,
                            ocrShowArea = savedOcrShowArea,
                            ocrLeftPercent = savedOcrLeft,
                            ocrTopPercent = savedOcrTop,
                            ocrRightPercent = savedOcrRight,
                            ocrBottomPercent = savedOcrBottom,
                            ocr2LeftPercent = savedOcr2Left,
                            ocr2TopPercent = savedOcr2Top,
                            ocr2RightPercent = savedOcr2Right,
                            ocr2BottomPercent = savedOcr2Bottom,
                            restartAppOnVd = savedRestartAppOnVd,
                            fixedDashAppEnabled = savedFixedDashAppEnabled,
                            fixedDashAppPackage = savedFixedDashAppPackage,
                        ),
                    )
                },
                onStop = controller::stop,
                onResetAndRestart = {
                    restartAction = RestartAction.Reset
                    onResetAndRestartApp()
                },
            )
        }

    }
}

private enum class RestartAction { Save, Reset }

@Composable
private fun RestartScreen(action: RestartAction) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (action == RestartAction.Save) Res.string.saving_restarting
                    else Res.string.resetting_restarting,
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
