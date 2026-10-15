package app.pillion

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.app.LocaleManager
import java.util.Locale
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pillion.android.AndroidDashSetup
import app.pillion.android.AndroidMirrorController
import app.pillion.android.AndroidSettingsStore
import app.pillion.android.AdbPairingCoordinator
import app.pillion.android.CaptureService
import app.pillion.android.DashHelper
import app.pillion.android.PillionAdb
import app.pillion.android.DiagnosticExporter
import app.pillion.android.DiagnosticFlightRecorder
import app.pillion.android.SdlMirrorController
import app.pillion.android.sdl.ProjectionHolder
import app.pillion.android.sdl.SdlService
import app.pillion.android.sdl.SdlRouterService
import app.pillion.android.sdl.SdlSessionState
import app.pillion.core.LaunchableApp
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.MirrorState
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.registerBuiltInHeadUnits
import app.pillion.ui.App
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the Android framework plumbing for a session: runtime permissions, the MediaProjection consent
 * dialog, and starting/stopping the right foreground service for the selected head unit — the NaviLite
 * [CaptureService] (Bluetooth) or the [SdlService] (USB). The Compose UI only sees [MirrorController].
 */
class MainActivity : ComponentActivity() {

    private var pendingSettings = MirrorSettings()
    private var pendingSdl = false // whether the in-flight projection grant is for the SDL/USB path
    @Volatile private var diagnosticExportRunning = false
    private var startRequestInFlight by mutableStateOf(false)
    private var dashSettingsRevision by mutableStateOf(0)
    private val settingsStore by lazy { AndroidSettingsStore(applicationContext) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            // Consent was cancelled/denied. The session never left Idle, so explicitly unlock START.
            startRequestInFlight = false
            return@registerForActivityResult
        }

        try {
            if (pendingSdl) {
                // SDL path: hand the capture grant to the SDL stack; the session connects on USB attach
                // (or now, if the bike is already plugged in) and mirrors once the dash activates us.
                ProjectionHolder.resultCode = result.resultCode
                ProjectionHolder.resultData = data
                ProjectionHolder.projection = null
                startSdlService()
            } else {
                startCaptureService(pendingSettings, result.resultCode, data)
            }
            releaseStartLockWhenSessionBegins()
        } catch (t: Throwable) {
            startRequestInFlight = false
            throw t
        }
    }

    /** One service intent for both normal mirroring and the consent-free helper VD. */
    private fun startCaptureService(
        settings: MirrorSettings,
        projectionCode: Int? = null,
        projectionData: Intent? = null,
    ) {
        CaptureService.resultCode = projectionCode ?: 0
        CaptureService.resultData = projectionData
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_QUALITY, settings.quality)
            .putExtra(CaptureService.EXTRA_DASH_DPI, settings.dashDpi)
            .putExtra(CaptureService.EXTRA_MAX_FPS, settings.maxFps)
            .putExtra(CaptureService.EXTRA_DASH_ENABLED, settingsStore.dashEnabled())
            .putExtra(CaptureService.EXTRA_DASH_WIDTH, settings.dashResolution.width)
            .putExtra(CaptureService.EXTRA_DASH_HEIGHT, settings.dashResolution.height)
            .putExtra(CaptureService.EXTRA_DASH_LEFT_MARGIN, settings.dashLeftMargin)
            .putExtra(CaptureService.EXTRA_DASH_BOTTOM_MARGIN, settings.dashBottomMargin)
            .putExtra(CaptureService.EXTRA_DASH_MARGIN_COLOR, settings.dashMarginColor.rgb)
            .putExtra(CaptureService.EXTRA_STICK_TAP_ENABLED, settings.stickTapEnabled)
            .putExtra(CaptureService.EXTRA_STICK_ZOOM_IN_X, settings.stickZoomInXPercent)
            .putExtra(CaptureService.EXTRA_STICK_ZOOM_IN_Y, settings.stickZoomInYPercent)
            .putExtra(CaptureService.EXTRA_STICK_ZOOM_OUT_X, settings.stickZoomOutXPercent)
            .putExtra(CaptureService.EXTRA_STICK_ZOOM_OUT_Y, settings.stickZoomOutYPercent)
            .putExtra(CaptureService.EXTRA_OCR_ENABLED, settings.ocrEnabled)
            .putExtra(CaptureService.EXTRA_OCR_SHOW_AREA, settings.ocrShowArea)
            .putExtra(CaptureService.EXTRA_OCR_LEFT, settings.ocrLeftPercent)
            .putExtra(CaptureService.EXTRA_OCR_TOP, settings.ocrTopPercent)
            .putExtra(CaptureService.EXTRA_OCR_RIGHT, settings.ocrRightPercent)
            .putExtra(CaptureService.EXTRA_OCR_BOTTOM, settings.ocrBottomPercent)
            .putExtra(CaptureService.EXTRA_OCR2_LEFT, settings.ocr2LeftPercent)
            .putExtra(CaptureService.EXTRA_OCR2_TOP, settings.ocr2TopPercent)
            .putExtra(CaptureService.EXTRA_OCR2_RIGHT, settings.ocr2RightPercent)
            .putExtra(CaptureService.EXTRA_OCR2_BOTTOM, settings.ocr2BottomPercent)
            .putExtra(CaptureService.EXTRA_RESTART_APP_ON_VD, settings.restartAppOnVd)
            .putExtra(CaptureService.EXTRA_AUTO_DRIVE_MODE, settings.autoDriveModeEnabled)
            .putExtra(CaptureService.EXTRA_DRIVE_TAP_X, settings.driveTapXPercent)
            .putExtra(CaptureService.EXTRA_DRIVE_TAP_Y, settings.driveTapYPercent)
            .putExtra(CaptureService.EXTRA_DRIVE_STICK_IN_X, settings.driveStickZoomInXPercent)
            .putExtra(CaptureService.EXTRA_DRIVE_STICK_IN_Y, settings.driveStickZoomInYPercent)
            .putExtra(CaptureService.EXTRA_DRIVE_STICK_OUT_X, settings.driveStickZoomOutXPercent)
            .putExtra(CaptureService.EXTRA_DRIVE_STICK_OUT_Y, settings.driveStickZoomOutYPercent)
            .putExtra(CaptureService.EXTRA_DRIVE_IMAGE_SHIFT, settings.driveImageShiftPx)
            .putExtra(CaptureService.EXTRA_FIXED_DASH_APP_ENABLED, settings.fixedDashAppEnabled)
            .putExtra(CaptureService.EXTRA_FIXED_DASH_APP_PACKAGE, settings.fixedDashAppPackage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) {
            try { startSelectedMode() } catch (t: Throwable) {
                startRequestInFlight = false
                throw t
            }
        } else {
            startRequestInFlight = false
        }
    }

    // First-launch permission request is deliberately separate from the START flow above. Granting
    // Nearby devices / Notifications here must never start MediaProjection or a mirror session.
    private val startupPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Permission result is intentionally passive. START handles any still-missing permission. */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) AdbPairingCoordinator.onNotificationPermissionGranted(applicationContext)
    }

    private fun loadLaunchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        }

        return resolved
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(packageManager).toString() }
                    .getOrDefault(packageName)
                    .ifBlank { packageName }
                LaunchableApp(packageName = packageName, label = label)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySelectedLanguage(settingsStore.languageTag(), recreateActivity = false)
        registerBuiltInHeadUnits()
        val dashSetup = AndroidDashSetup(
            context = applicationContext,
            requestNotificationPermission = ::requestNotificationPermission,
        )
        val launchableApps = loadLaunchableApps()
        settingsStore.initializeFirstRun(
            yahooNaviInstalled = launchableApps.any { it.packageName == "jp.co.yahoo.android.apps.navi" },
        )
        setContent {
            App(
                controllerFor = ::controllerFor,
                settingsStore = settingsStore,
                dashSetup = dashSetup,
                launchableApps = launchableApps,
                onRestartApp = { restartPillion(clearCache = false) },
                onResetAndRestartApp = { restartPillion(clearCache = true) },
                onExportDiagnostics = ::exportDiagnostics,
                onLanguageChange = { applySelectedLanguage(it, recreateActivity = true) },
                startRequestInFlight = startRequestInFlight,
                dashSettingsRevision = dashSettingsRevision,
                onCheckDashAvailability = ::checkDashAvailability,
            )
        }
        if (savedInstanceState == null) {
            // Ask once on a clean install, after the first Activity content has been attached.
            window.decorView.post { requestInitialRuntimePermissionsOnce() }
        }
    }


    /** Resource-only locale switch. Recreating the Activity does not restart an active capture service. */
    @Suppress("DEPRECATION")
    private fun applySelectedLanguage(tag: String, recreateActivity: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = getSystemService(LocaleManager::class.java)
            val desired = LocaleList.forLanguageTags(tag)
            if (localeManager.applicationLocales.toLanguageTags() != desired.toLanguageTags()) {
                localeManager.applicationLocales = desired
            }
        } else {
            val config = resources.configuration
            val selected = if (tag.isBlank()) Locale.getDefault() else Locale.forLanguageTag(tag)
            config.setLocale(selected)
            resources.updateConfiguration(config, resources.displayMetrics)
            applicationContext.resources.updateConfiguration(config, applicationContext.resources.displayMetrics)
        }
        if (recreateActivity) recreate()
    }

    private fun exportDiagnostics() {
        if (diagnosticExportRunning) {
            Toast.makeText(this, getString(app.pillion.R.string.diagnostic_export_running), Toast.LENGTH_SHORT).show()
            return
        }
        diagnosticExportRunning = true
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                DiagnosticExporter.export(applicationContext)
            } finally {
                diagnosticExportRunning = false
            }
            withContext(Dispatchers.Main) {
                val message = result.fold(
                    onSuccess = { getString(app.pillion.R.string.diagnostic_saved, it) },
                    onFailure = { getString(app.pillion.R.string.diagnostic_export_failed, it.message ?: it.javaClass.simpleName) },
                )
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restartPillion(clearCache: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            DiagnosticFlightRecorder.record(
                "APP",
                if (clearCache) "RESET_RESTART_BEGIN" else "SAVE_RESTART_BEGIN",
            )
            // Save & Restart and Reset & Restart deliberately share this one cleanup path. Settings
            // are committed by the UI before the save path enters here. Reset additionally removes
            // only transient cache files; preferences, permissions, ADB identity and diagnostic
            // history live elsewhere and are intentionally preserved.
            DashHelper.stopWatchdog()
            AdbPairingCoordinator.stop(applicationContext)

            // Ask the foreground services to release their own MediaProjection / SDL state first.
            runCatching {
                startService(
                    Intent(this@MainActivity, CaptureService::class.java)
                        .setAction(CaptureService.ACTION_STOP)
                        .putExtra(CaptureService.EXTRA_KEEP_DASH_HELPER, true),
                )
            }
            runCatching {
                startService(Intent(this@MainActivity, SdlService::class.java).setAction(SdlService.ACTION_STOP))
            }

            delay(250)
            runCatching { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
            runCatching { stopService(Intent(this@MainActivity, SdlService::class.java)) }
            runCatching { stopService(Intent(this@MainActivity, SdlRouterService::class.java)) }

            // SdlRouterService runs in its own process, so wait until Android no longer reports any
            // Pillion service before handing the final process restart to the shell-side helper.
            val activityManager = getSystemService(ActivityManager::class.java)
            val serviceNames = setOf(
                CaptureService::class.java.name,
                SdlService::class.java.name,
                SdlRouterService::class.java.name,
            )
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                val servicesStopped = runCatching {
                    activityManager.getRunningServices(Int.MAX_VALUE)
                        .none { it.service.className in serviceNames }
                }.getOrDefault(false)
                if (servicesStopped) break

                // A transport can briefly race the first stop request; repeat only idempotent stops.
                runCatching { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
                runCatching { stopService(Intent(this@MainActivity, SdlService::class.java)) }
                runCatching { stopService(Intent(this@MainActivity, SdlRouterService::class.java)) }
                delay(100)
            }

            if (clearCache) {
                clearTransientCaches()
                DiagnosticFlightRecorder.record("APP", "RESET_CACHE_CLEARED")
            }

            // Keep the same conservative settle window used by v33 Save & Shutdown. This lets worker
            // threads, sockets, Binder/SDL teardown and router process cleanup finish before restart.
            delay(3_000L)

            val restartScheduled = DashHelper.schedulePillionRestart(applicationContext)
            if (!restartScheduled) {
                DiagnosticFlightRecorder.record("APP", "RESTART_FALLBACK_MANUAL_REOPEN")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Automatic restart unavailable. Open Pillion again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    finishAndRemoveTask()
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private fun clearTransientCaches() {
        runCatching {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
        runCatching {
            externalCacheDir?.let { dir ->
                dir.deleteRecursively()
                dir.mkdirs()
            }
        }
    }

    /** Resolve the [MirrorController] for the selected head unit (DIP — the UI doesn't know which). */
    private fun controllerFor(profile: HeadUnitProfile): MirrorController =
        if (profile.requiresUsb) {
            SdlMirrorController(
                onStart = { settings -> pendingSdl = true; startMirroring(settings) },
                onStop = ::stopSdl,
            )
        } else {
            AndroidMirrorController(
                onStart = { settings -> pendingSdl = false; startMirroring(settings) },
                onStop = ::stopMirroring,
            )
        }

    private fun releaseStartLockWhenSessionBegins() {
        val sdlRequest = pendingSdl
        lifecycleScope.launch {
            val stateFlow = if (sdlRequest) SdlSessionState.state else CaptureService.state
            stateFlow.first {
                it is MirrorState.Connecting ||
                    it is MirrorState.Streaming ||
                    it is MirrorState.Broadcasting
            }
            startRequestInFlight = false
        }
    }

    /**
     * Entry-only check. Do not discover Wi-Fi ADB or spawn a Helper in this preflight:
     * existing DashHelper.ensureRunning() retains responsibility for normal startup.
     */
    private suspend fun checkDashAvailability(): Boolean {
        if (!settingsStore.dashEnabled()) return false
        val available = withContext(Dispatchers.IO) {
            if (DashHelper.isRunning(applicationContext)) {
                DiagnosticFlightRecorder.record("VD", "PREFLIGHT helper ready")
                true
            } else {
                val adbReady = runCatching {
                    val adb = PillionAdb.getInstance(applicationContext)
                    // connect() returns false when this libadb manager is *already* connected.
                    // Only reuse a live loopback session, not an unrelated Wi-Fi ADB session.
                    (adb.isConnected() && adb.getHostAddress() == PillionAdb.LOOPBACK_HOST) ||
                        adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT)
                }.getOrDefault(false)
                DiagnosticFlightRecorder.record("VD", if (adbReady) "PREFLIGHT local ADB ready" else "PREFLIGHT unavailable")
                adbReady
            }
        }
        if (!available && settingsStore.dashEnabled()) {
            settingsStore.setDashEnabled(false)
            dashSettingsRevision++
            DiagnosticFlightRecorder.record("VD", "DISABLED after failed preflight")
        }
        return available && settingsStore.dashEnabled()
    }

    /** Dedicated NaviLite VD bypasses MediaProjection only while local prerequisites are usable. */
    private fun startSelectedMode() {
        if (pendingSdl || !settingsStore.dashEnabled()) {
            requestProjection()
            return
        }
        // The existing START in-flight lock stays active until this asynchronous check completes.
        lifecycleScope.launch {
            try {
                val dashReady = checkDashAvailability()
                if (!startRequestInFlight) return@launch
                if (dashReady) {
                    startCaptureService(pendingSettings)
                    releaseStartLockWhenSessionBegins()
                } else {
                    // Same Start press, normal Android consent flow. Never change CaptureService.
                    requestProjection()
                }
            } catch (t: Throwable) {
                startRequestInFlight = false
                throw t
            }
        }
    }

    private fun startMirroring(settings: MirrorSettings) {
        // One START request at a time. Keep the UI and framework guard locked until the request
        // either fails/cancels or the selected mirror session actually leaves Idle.
        if (startRequestInFlight) return
        startRequestInFlight = true
        pendingSettings = settings
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        try {
            if (missing.isEmpty()) startSelectedMode() else permissionLauncher.launch(missing.toTypedArray())
        } catch (t: Throwable) {
            startRequestInFlight = false
            throw t
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        try {
            val intent = if (
                !pendingSdl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ) {
                // Normal mirroring is always whole-screen. Android 14+ therefore skips the
                // app-vs-screen picker and asks consent only for the default physical display.
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                // Preserve the existing SDL/USB consent flow and pre-Android-14 behaviour.
                mpm.createScreenCaptureIntent()
            }
            projectionLauncher.launch(intent)
        } catch (t: Throwable) {
            startRequestInFlight = false
            throw t
        }
    }

    private fun stopMirroring() {
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_STOP)
                .putExtra(CaptureService.EXTRA_KEEP_DASH_HELPER, true),
        )
    }

    private fun startSdlService() {
        val intent = Intent(this, SdlService::class.java)
            .putExtra(SdlService.EXTRA_APP_ID, SdlService.APP_ID_GARMIN)
            .putExtra(SdlService.EXTRA_MIRROR, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopSdl() {
        // ACTION_STOP (not stopService) so the service latches "user stopped" and the receiver won't
        // auto-revive it while the USB cable is still connected.
        startService(Intent(this, SdlService::class.java).setAction(SdlService.ACTION_STOP))
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestInitialRuntimePermissionsOnce() {
        val prefs = getSharedPreferences(INITIAL_PERMISSION_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INITIAL_PERMISSION_REQUESTED, false)) return

        // Mark before launching so rotation/recreation cannot issue a second system prompt.
        prefs.edit().putBoolean(KEY_INITIAL_PERMISSION_REQUESTED, true).apply()
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) startupPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private companion object {
        const val INITIAL_PERMISSION_PREFS = "pillion.initial_permissions"
        const val KEY_INITIAL_PERMISSION_REQUESTED = "requested_once"
    }
}
