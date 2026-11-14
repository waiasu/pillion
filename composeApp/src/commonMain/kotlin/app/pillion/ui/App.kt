package app.pillion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import app.pillion.core.AppInfo
import app.pillion.core.DashSetup
import app.pillion.core.DashDpi
import app.pillion.core.DashMargins
import app.pillion.core.DashTapSettings
import app.pillion.core.DashAutoDriveSettings
import app.pillion.core.DashMarginColor
import app.pillion.core.DashAppCatalog
import app.pillion.core.DashResolution
import app.pillion.core.MirrorController
import app.pillion.core.CastMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import app.pillion.core.MirrorSettings
import app.pillion.core.SettingsStore
import app.pillion.core.SettingsPreset
import app.pillion.core.ThemeMode
import app.pillion.core.UpdateChecker
import app.pillion.core.UpdateInfo
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.HeadUnitRegistry

/** The public GitHub repository — shown in-app so anyone can read the source. */
const val REPO_URL = "https://github.com/alexandrevega/pillion"

/**
 * App entry point: owns the top-level UI state and routes between bike-selection (first run), Home and
 * Settings. The controller is resolved per selected [HeadUnitProfile] via [controllerFor] (DIP), so the
 * shared UI never knows whether it's driving NaviLite (Bluetooth) or SDL (USB).
 */
@Composable
fun App(
    controllerFor: (HeadUnitProfile) -> MirrorController,
    updateChecker: UpdateChecker? = null,
    settingsStore: SettingsStore? = null,
    dashSetup: DashSetup? = null,
    dashAppCatalog: DashAppCatalog? = null,
    onExportDiagnostics: (() -> Unit)? = null,
    onStartDash: ((MirrorSettings) -> Unit)? = null,
    onStopDash: (() -> Unit)? = null,
    castMode: StateFlow<CastMode> = MutableStateFlow(CastMode.NONE),
    watcherRunning: Boolean = false,
    watcherBusy: Boolean = false,
    onWatcherToggle: () -> Unit = {},
    yahooAccessibilityEnabled: Boolean = false,
    onRestoreBackup: () -> Unit = {},
    restoreStatus: String? = null,
) {
    var themeMode by remember { mutableStateOf(settingsStore?.themeMode() ?: ThemeMode.SYSTEM) }
    PillionTheme(themeMode) {
        var selectedBikeId by remember { mutableStateOf(settingsStore?.selectedBikeId()) }
        var changingBike by remember { mutableStateOf(false) }
        val profile = selectedBikeId?.let { HeadUnitRegistry.byId(it) }

        // First run shows the full onboarding; "Change bike" (from Settings) jumps to the picker.
        if (profile == null) {
            OnboardingScreen(
                profiles = HeadUnitRegistry.all(),
                onSelect = { p -> settingsStore?.setSelectedBikeId(p.id); selectedBikeId = p.id; changingBike = false },
                skipIntro = changingBike,
                onRestoreBackup = onRestoreBackup,
                restoreStatus = restoreStatus,
            )
            return@PillionTheme
        }

        val controller = remember(profile.id) { controllerFor(profile) }
        val state by controller.state.collectAsState()
        val activeMode by castMode.collectAsState()
        var quality by remember { mutableStateOf(settingsStore?.imageQuality() ?: 80) }
        var maxFps by remember { mutableStateOf(settingsStore?.maxFrameRate() ?: 3) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var showDashOnboarding by rememberSaveable { mutableStateOf(false) }
        var dashEnabled by remember { mutableStateOf(settingsStore?.dashEnabled() ?: false) }
        var dashResolution by remember { mutableStateOf(settingsStore?.dashResolution() ?: DashResolution.DEFAULT) }
        var dashDpi by remember { mutableStateOf(settingsStore?.dashDpi() ?: DashDpi.DEFAULT) }
        var dashMargins by remember { mutableStateOf(settingsStore?.dashMargins() ?: DashMargins()) }
        var dashMarginColor by remember { mutableStateOf(settingsStore?.dashMarginColor() ?: DashMarginColor.Black) }
        var dashTap by remember { mutableStateOf(settingsStore?.dashTapSettings() ?: DashTapSettings()) }
        var dashAutoDrive by remember { mutableStateOf(settingsStore?.dashAutoDriveSettings() ?: DashAutoDriveSettings()) }
        var yahooNaviEnabled by remember { mutableStateOf(settingsStore?.yahooNaviEnabled() ?: true) }
        var dashAppPackage by remember { mutableStateOf(settingsStore?.dashAppPackage()) }
        var presets by remember { mutableStateOf(settingsStore?.listPresets().orEmpty()) }
        val dashApps = remember(dashAppCatalog) { dashAppCatalog?.availableApps().orEmpty() }
        var dashStartError by remember { mutableStateOf<String?>(null) }
        var update by remember { mutableStateOf<UpdateInfo?>(null) }
        var updateDismissed by rememberSaveable { mutableStateOf(false) }
        val uriHandler = LocalUriHandler.current

        LaunchedEffect(updateChecker) { update = updateChecker?.newerThan(AppInfo.VERSION) }
        BackHandler(enabled = showSettings || showDashOnboarding) {
            if (showDashOnboarding) showDashOnboarding = false else showSettings = false
        }

        if (showDashOnboarding && dashSetup != null) {
            DashOnboarding(
                dash = dashSetup,
                onOptOut = {
                    dashEnabled = false; settingsStore?.setDashEnabled(false); showDashOnboarding = false
                },
                onFinish = {
                    dashEnabled = true; settingsStore?.setDashEnabled(true); showDashOnboarding = false
                },
                onClose = { showDashOnboarding = false },
            )
        } else if (showSettings) {
            SettingsScreen(
                quality = quality,
                onQuality = { quality = it.coerceIn(10, 80); settingsStore?.setImageQuality(quality) },
                maxFps = maxFps,
                onMaxFps = { maxFps = it.coerceIn(1, 15); settingsStore?.setMaxFrameRate(maxFps) },
                themeMode = themeMode,
                onThemeMode = { themeMode = it; settingsStore?.setThemeMode(it) },
                dashSupported = dashSetup != null,
                dashEnabled = dashEnabled,
                dashResolution = dashResolution,
                onDashResolution = {
                    dashResolution = it
                    settingsStore?.setDashResolution(it)
                },
                dashDpi = dashDpi,
                onDashDpi = { dashDpi = DashDpi.snap(it); settingsStore?.setDashDpi(dashDpi) },
                dashMargins = dashMargins,
                onDashMargins = { dashMargins = DashMargins.clamped(it.left, it.bottom); settingsStore?.setDashMargins(dashMargins) },
                dashMarginColor = dashMarginColor,
                onDashMarginColor = { dashMarginColor = it; settingsStore?.setDashMarginColor(it) },
                dashTap = dashTap,
                onDashTap = { dashTap = DashTapSettings.clamped(it.upX, it.upY, it.downX, it.downY,
                    it.driveUpX, it.driveUpY, it.driveDownX, it.driveDownY, it.enabled); settingsStore?.setDashTapSettings(dashTap) },
                dashAutoDrive = dashAutoDrive,
                onDashAutoDrive = { dashAutoDrive = DashAutoDriveSettings.clamped(it.initialX, it.initialY, it.arrivedX, it.arrivedY); settingsStore?.setDashAutoDriveSettings(dashAutoDrive) },
                yahooNaviEnabled = yahooNaviEnabled,
                yahooAccessibilityEnabled = yahooAccessibilityEnabled,
                onYahooNaviEnabled = { yahooNaviEnabled = it; settingsStore?.setYahooNaviEnabled(it) },
                dashApps = dashApps,
                selectedDashApp = dashAppPackage,
                onDashApp = { dashAppPackage = it; settingsStore?.setDashAppPackage(it); dashStartError = null },
                onSetUpDash = { showDashOnboarding = true },
                onDisableDash = { dashEnabled = false; settingsStore?.setDashEnabled(false) },
                bikeName = profile.displayName,
                onChangeBike = { showSettings = false; changingBike = true; selectedBikeId = null },
                update = update,
                onBack = { showSettings = false },
                onExportDiagnostics = onExportDiagnostics,
                presets = presets,
                onSavePreset = { name, overwrite ->
                    val saved = settingsStore?.savePreset(name, overwrite) ?: false
                    if (saved) presets = settingsStore?.listPresets().orEmpty()
                    saved
                },
                onDeletePreset = { name ->
                    val removed = settingsStore?.deletePreset(name) ?: false
                    if (removed) presets = settingsStore?.listPresets().orEmpty()
                    removed
                },
                onApplyPreset = { name ->
                    val applied = settingsStore?.applyPreset(name) ?: false
                    if (applied) {
                        quality = settingsStore?.imageQuality() ?: quality
                        maxFps = settingsStore?.maxFrameRate() ?: maxFps
                        dashEnabled = settingsStore?.dashEnabled() ?: dashEnabled
                        dashResolution = settingsStore?.dashResolution() ?: dashResolution
                        dashDpi = settingsStore?.dashDpi() ?: dashDpi
                        dashMargins = settingsStore?.dashMargins() ?: dashMargins
                        dashMarginColor = settingsStore?.dashMarginColor() ?: dashMarginColor
                        dashTap = settingsStore?.dashTapSettings() ?: dashTap
                        dashAutoDrive = settingsStore?.dashAutoDriveSettings() ?: dashAutoDrive
                        yahooNaviEnabled = settingsStore?.yahooNaviEnabled() ?: yahooNaviEnabled
                        dashAppPackage = settingsStore?.dashAppPackage()
                        dashStartError = null
                    }
                    applied
                },
            )
        } else {
            HomeScreen(
                state = if (dashStartError != null && activeMode == CastMode.NONE) app.pillion.core.MirrorState.Error(dashStartError!!) else state,
                update = update,
                onOpenSettings = { showSettings = true },
                onStart = {
                    if (!watcherRunning && !watcherBusy)
                        controller.start(MirrorSettings(quality, maxFps, dashResolution))
                },
                onStop = controller::stop,
                castMode = activeMode,
                showDash = onStartDash != null && !profile.requiresUsb,
                dashReady = dashEnabled,
                onStartDash = {
                    if (watcherRunning || watcherBusy) {
                        dashStartError = "Stop CCU linked mode before manual Start."
                    } else if (dashAppPackage == null) {
                        dashStartError = "Select an app to display in Settings before starting Virtual Display."
                    } else if (dashApps.none { it.packageName == dashAppPackage }) {
                        dashStartError = "The selected app is unavailable. Choose another app in Settings."
                    } else {
                        dashStartError = null
                        onStartDash?.invoke(MirrorSettings(quality, maxFps, dashResolution, dashDpi, dashMargins, dashMarginColor, dashTap, dashAutoDrive,
                            yahooNaviEnabled && dashEnabled && yahooAccessibilityEnabled &&
                                dashAppPackage == "jp.co.yahoo.android.apps.navi"))
                    }
                },
                onStopDash = { onStopDash?.invoke() },
                watcherRunning = watcherRunning, watcherBusy = watcherBusy,
                onWatcherToggle = onWatcherToggle,
            )
        }

        if (update != null && !updateDismissed) {
            UpdateDialog(
                update = update!!,
                onUpdate = { uriHandler.openUri(update!!.url); updateDismissed = true },
                onDismiss = { updateDismissed = true },
            )
        }
    }
}
