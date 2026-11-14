package app.pillion

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import app.pillion.android.AndroidDashAppCatalog
import app.pillion.android.AndroidDashSetup
import app.pillion.android.AndroidMirrorController
import app.pillion.android.AndroidSettingsStore
import app.pillion.android.AdbPairingCoordinator
import app.pillion.android.CaptureService
import app.pillion.android.CcuAutoTrial
import app.pillion.android.CcuWatcherService
import app.pillion.android.DiagnosticExporter
import app.pillion.android.DiagnosticFlightRecorder
import app.pillion.android.SdlMirrorController
import app.pillion.android.YahooRoadAccessibilityService
import app.pillion.android.sdl.ProjectionHolder
import app.pillion.android.sdl.SdlService
import app.pillion.core.MirrorController
import app.pillion.core.MirrorSettings
import app.pillion.core.CastMode
import app.pillion.core.headunit.HeadUnitProfile
import app.pillion.core.headunit.registerBuiltInHeadUnits
import app.pillion.ui.App

/**
 * Owns the Android framework plumbing for a session: runtime permissions, the MediaProjection consent
 * dialog, and starting/stopping the right foreground service for the selected head unit — the NaviLite
 * [CaptureService] (Bluetooth) or the [SdlService] (USB). The Compose UI only sees [MirrorController].
 */
class MainActivity : ComponentActivity() {

    private var pendingSettings = MirrorSettings()
    private var pendingSdl = false // whether the in-flight projection grant is for the SDL/USB path
    private var pendingTrialId: String? = null
    private var watcherRunning by mutableStateOf(false)
    private var watcherBusy by mutableStateOf(false)
    private var yahooAccessibilityEnabled by mutableStateOf(false)
    private var restoreStatus by mutableStateOf<String?>(null)
    private val restorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restoreStatus = runCatching {
                val json = contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(stream).use { zip ->
                        var backup: String? = null
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (entry.name == "settings-backup.json") {
                                val buffer = ByteArrayOutputStream()
                                val chunk = ByteArray(8192)
                                while (true) {
                                    val count = zip.read(chunk)
                                    if (count < 0) break
                                    if (buffer.size() + count > 1_048_576) error("Settings backup too large")
                                    buffer.write(chunk, 0, count)
                                }
                                backup = buffer.toString(Charsets.UTF_8.name())
                                break
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                        backup ?: error("No settings-backup.json in ZIP")
                    }
                } ?: error("Cannot open ZIP")
                settingsStore.importBackupJson(json)
                "Settings and presets restored. Continue to choose your motorcycle."
            }.getOrElse { "Restore failed: ${it.message?.take(140) ?: it.javaClass.simpleName}" }
        }
    }
    // Keep the manual Start buttons disabled until CCU watcher startup is confirmed.
    private var watcherStartPending = false
    private var watcherStartGeneration = 0
    private var watcherReceiverRegistered = false
    private val watcherStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CcuWatcherService.ACTION_STATUS) return
            val running = intent.getBooleanExtra(CcuWatcherService.EXTRA_RUNNING, false)
            watcherRunning = running
            // An unrelated/stale "stopped" status is not a start confirmation.
            if (watcherStartPending && !running) return
            watcherStartPending = false
            watcherBusy = false
        }
    }
    private val watcherPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) sendWatcherStart()
        else {
            watcherStartPending = false
            watcherBusy = false
            Toast.makeText(this, "Bluetooth and notification permissions required", Toast.LENGTH_LONG).show()
        }
    }
    private val settingsStore by lazy { AndroidSettingsStore(applicationContext) }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            if (pendingSdl) {
                // SDL path: hand the capture grant to the SDL stack; the session connects on USB attach
                // (or now, if the bike is already plugged in) and mirrors once the dash activates us.
                ProjectionHolder.resultCode = result.resultCode
                ProjectionHolder.resultData = data
                ProjectionHolder.projection = null
                startSdlService()
            } else {
                CaptureService.resultCode = result.resultCode
                CaptureService.resultData = data
                val intent = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_QUALITY, pendingSettings.quality)
                    .putExtra(CaptureService.EXTRA_MAX_FPS, pendingSettings.maxFps)
                    .putExtra(CaptureService.EXTRA_DASH_ENABLED, false)
                    .putExtra(CaptureService.EXTRA_DASH_WIDTH, pendingSettings.dashResolution.width)
                    .putExtra(CaptureService.EXTRA_DASH_HEIGHT, pendingSettings.dashResolution.height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) requestProjection()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) AdbPairingCoordinator.start(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerBuiltInHeadUnits()
        // Custom test APK: do not check upstream releases.
        val dashSetup = AndroidDashSetup(
            context = applicationContext,
            requestNotificationPermission = ::requestNotificationPermission,
        )
        setContent { App(::controllerFor, null, settingsStore, dashSetup, AndroidDashAppCatalog(applicationContext), onExportDiagnostics = ::exportDiagnostics, onStartDash = ::startDash, onStopDash = ::stopMirroring, castMode = CaptureService.mode,
            watcherRunning = watcherRunning, watcherBusy = watcherBusy, onWatcherToggle = ::toggleWatcher,
            yahooAccessibilityEnabled = yahooAccessibilityEnabled,
            onRestoreBackup = { restoreStatus = null; restorePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            restoreStatus = restoreStatus) }
        acceptAutoTrialIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptAutoTrialIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Also refresh after returning from Android's Accessibility settings.
        yahooAccessibilityEnabled = isYahooAccessibilityEnabled()
    }

    private fun isYahooAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val service = info.resolveInfo.serviceInfo
                service.packageName == packageName &&
                    service.name == YahooRoadAccessibilityService::class.java.name
            }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CcuWatcherService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(watcherStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(watcherStatusReceiver, filter)
        }
        watcherReceiverRegistered = true
        refreshWatcherStatus()
    }

    override fun onStop() {
        if (watcherReceiverRegistered) {
            unregisterReceiver(watcherStatusReceiver)
            watcherReceiverRegistered = false
        }
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun refreshWatcherStatus() {
        val running = runCatching {
            getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == CcuWatcherService::class.java.name }
        }.getOrDefault(false)
        watcherRunning = running
        // A negative poll during first service startup must not enable the other buttons.
        if (!watcherStartPending || running) {
            watcherStartPending = false
            watcherBusy = false
        }
    }

    private fun sendTrialFailure(reason: String) {
        val id = pendingTrialId ?: return
        DiagnosticFlightRecorder.record("AUTO", "START_FAILED reason=${reason}")
        sendBroadcast(Intent(CcuAutoTrial.ACTION_START_FAILED).setPackage(packageName)
            .putExtra(CcuAutoTrial.EXTRA_SESSION_ID, id)
            .putExtra(CcuAutoTrial.EXTRA_REASON, reason))
        pendingTrialId = null
    }

    private fun acceptAutoTrialIntent(source: Intent?) {
        val token = source?.getStringExtra(CcuAutoTrial.EXTRA_TOKEN) ?: return
        source.removeExtra(CcuAutoTrial.EXTRA_TOKEN)
        if (!CcuAutoTrial.consume(applicationContext, token)) {
            DiagnosticFlightRecorder.record("AUTO", "token rejected")
            return
        }
        val initiallyOff = source.getBooleanExtra(CcuAutoTrial.EXTRA_INITIAL_SCREEN_OFF, false)
        source.removeExtra(CcuAutoTrial.EXTRA_INITIAL_SCREEN_OFF)
        window.decorView.post {
            pendingTrialId = token
            if (CaptureService.mode.value != CastMode.NONE || watcherBusy) {
                sendTrialFailure("capture_busy")
                return@post
            }
            if (!settingsStore.dashEnabled()) {
                sendTrialFailure("VD_disabled")
                return@post
            }
            if (settingsStore.dashAppPackage() == null) {
                sendTrialFailure("no_target_app")
                return@post
            }
            if (requiredPermissions().any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                sendTrialFailure("permissions_missing")
                return@post
            }
            try {
                val settings = CcuAutoTrial.settings(applicationContext)
                DiagnosticFlightRecorder.record("AUTO", "START saved_VD_settings")
                launchDashService(settings, initiallyOff)
                pendingTrialId = null
            } catch (t: Throwable) {
                sendTrialFailure(t.javaClass.simpleName)
            }
        }
    }

    private fun toggleWatcher() {
        if (watcherBusy) return
        if (watcherRunning) {
            watcherBusy = true
            watcherStartPending = false
            runCatching {
                startService(Intent(this, CcuWatcherService::class.java).setAction(CcuWatcherService.ACTION_STOP))
            }.onFailure { watcherBusy = false }
        } else {
            if (!settingsStore.dashEnabled() || settingsStore.dashAppPackage() == null) {
                Toast.makeText(this, "Set up Virtual Display first", Toast.LENGTH_SHORT).show()
                return
            }
            if (CaptureService.mode.value != CastMode.NONE) {
                Toast.makeText(this, "Stop mirroring before CCU linked Start", Toast.LENGTH_SHORT).show()
                return
            }
            watcherBusy = true
            watcherStartPending = true
            val needed = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) sendWatcherStart()
            else watcherPermissions.launch(needed.toTypedArray())
        }
        window.decorView.postDelayed({ if (watcherBusy) refreshWatcherStatus() }, 3000L)
    }

    private fun sendWatcherStart() {
        val attempt = ++watcherStartGeneration
        try {
            val intent = Intent(this, CcuWatcherService::class.java).setAction(CcuWatcherService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (t: Throwable) {
            watcherStartPending = false
            watcherBusy = false
            Toast.makeText(this, "CCU linked Start failed: ${t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        // ACTION_STATUS normally confirms startup. If it is lost, a running-service poll
        // can confirm it, but an early negative poll must never clear the Start guard.
        // Recover a genuinely failed launch after a bounded timeout.
        window.decorView.postDelayed({
            if (watcherStartPending && watcherStartGeneration == attempt) {
                refreshWatcherStatus()
                if (watcherStartPending) {
                    watcherStartPending = false
                    watcherBusy = false
                    Toast.makeText(this, "CCU linked Start timed out", Toast.LENGTH_LONG).show()
                }
            }
        }, 10_000L)
    }

    private fun exportDiagnostics() {
        DiagnosticExporter.exportAsync(applicationContext) { result ->
            runOnUiThread {
                Toast.makeText(this, result.fold(
                    onSuccess = { "Diagnostic saved to Downloads: $it" },
                    onFailure = { "Diagnostic export failed: ${it.javaClass.simpleName}" },
                ), Toast.LENGTH_LONG).show()
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

    private fun startMirroring(settings: MirrorSettings) {
        if (CaptureService.mode.value != CastMode.NONE || watcherRunning || watcherBusy) return
        pendingSettings = settings
        DiagnosticFlightRecorder.record("UI", "Start requested")
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) requestProjection() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private val dashPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) launchDashService(pendingSettings)
    }

    private fun startDash(settings: MirrorSettings) {
        if (CaptureService.mode.value != CastMode.NONE || watcherRunning || watcherBusy) return
        pendingSettings = settings
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) launchDashService(settings)
        else dashPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun launchDashService(settings: MirrorSettings, ccuInitiallyOff: Boolean = false) {
        val packageName = settingsStore.dashAppPackage()
            ?: run { sendTrialFailure("no_target_app"); return }
        val component = packageManager.getLaunchIntentForPackage(packageName)?.component
            ?: run { sendTrialFailure("app_not_launchable"); return }
        val intent = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_DASH_ENABLED, true)
            .apply {
                pendingTrialId?.let {
                    putExtra(CcuAutoTrial.EXTRA_SESSION_ID, it)
                    putExtra(CaptureService.EXTRA_CCU_INITIALLY_OFF, ccuInitiallyOff)
                }
            }
            .putExtra(CaptureService.EXTRA_DASH_COMPONENT, component.flattenToString())
            .putExtra(CaptureService.EXTRA_DASH_DPI, settings.dashDpi)
            .putExtra(CaptureService.EXTRA_MARGIN_LEFT, settings.dashMargins.left)
            .putExtra(CaptureService.EXTRA_MARGIN_BOTTOM, settings.dashMargins.bottom)
            .putExtra(CaptureService.EXTRA_MARGIN_COLOR, settings.dashMarginColor.name)
            .putExtra(CaptureService.EXTRA_STICK_INPUT_ENABLED, settings.dashTap.enabled)
            .putExtra(CaptureService.EXTRA_TAP_UP_X, settings.dashTap.upX)
            .putExtra(CaptureService.EXTRA_TAP_UP_Y, settings.dashTap.upY)
            .putExtra(CaptureService.EXTRA_TAP_DOWN_X, settings.dashTap.downX)
            .putExtra(CaptureService.EXTRA_TAP_DOWN_Y, settings.dashTap.downY)
            .putExtra(CaptureService.EXTRA_TAP_DRIVE_UP_X, settings.dashTap.driveUpX)
            .putExtra(CaptureService.EXTRA_TAP_DRIVE_UP_Y, settings.dashTap.driveUpY)
            .putExtra(CaptureService.EXTRA_TAP_DRIVE_DOWN_X, settings.dashTap.driveDownX)
            .putExtra(CaptureService.EXTRA_TAP_DRIVE_DOWN_Y, settings.dashTap.driveDownY)
            .putExtra(CaptureService.EXTRA_AUTO_INITIAL_X, settings.dashAutoDrive.initialX)
            .putExtra(CaptureService.EXTRA_AUTO_INITIAL_Y, settings.dashAutoDrive.initialY)
            .putExtra(CaptureService.EXTRA_AUTO_ARRIVED_X, settings.dashAutoDrive.arrivedX)
            .putExtra(CaptureService.EXTRA_AUTO_ARRIVED_Y, settings.dashAutoDrive.arrivedY)
            .putExtra(CaptureService.EXTRA_YAHOO_NAVI_ENABLED, settings.yahooNaviEnabled &&
                packageName == "jp.co.yahoo.android.apps.navi" && isYahooAccessibilityEnabled())
            .putExtra(CaptureService.EXTRA_QUALITY, settings.quality)
            .putExtra(CaptureService.EXTRA_MAX_FPS, settings.maxFps)
            .putExtra(CaptureService.EXTRA_DASH_WIDTH, settings.dashResolution.width)
            .putExtra(CaptureService.EXTRA_DASH_HEIGHT, settings.dashResolution.height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopMirroring() {
        DiagnosticFlightRecorder.record("UI", "Stop requested")
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
