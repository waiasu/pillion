package app.pillion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import app.pillion.core.DashMarginColor
import app.pillion.core.DashResolution
import app.pillion.core.MirrorEngine
import app.pillion.core.ScreenSource
import app.pillion.core.MirrorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * Foreground service that hosts a mirroring session. MediaProjection requires a running
 * foreground service of type mediaProjection, so the engine lives here for its lifetime.
 * Single responsibility: own the Android service lifecycle and surface the engine's state.
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: MirrorEngine? = null
    private var engineStateJob: Job? = null
    private var reconnectJob: Job? = null
    private var activeScreenSource: ScreenSource? = null
    private var activeMaxFps: Double = 3.0
    @Volatile private var reconnectWait = false
    @Volatile private var dashAclConnected = false
    @Volatile private var reconnectAttempt = 0
    @Volatile private var lastNotificationText: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dashEnabled = false
    private var dashSwitch: SwitchableScreenSource? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var keyguardManager: KeyguardManager? = null
    private var keyguardListener: Any? = null
    @Volatile private var dashPromotedAtMs = 0L
    @Volatile private var dashSawLockedKeyguard = false
    @Volatile private var lastDashTargetComponent: String? = null
    @Volatile private var fixedDashAppEnabled = false
    @Volatile private var fixedDashAppPackage: String? = null
    @Volatile private var keepDashHelperOnDestroy = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            keepDashHelperOnDestroy = intent.getBooleanExtra(EXTRA_KEEP_DASH_HELPER, false)
            Log.i(TAG, "notification: explicit Stop keepHelper=$keepDashHelperOnDestroy")
            DiagnosticFlightRecorder.record(
                "SERVICE",
                "explicit STOP requested keepHelper=$keepDashHelperOnDestroy",
            )
            if (keepDashHelperOnDestroy && dashEnabled) {
                // Move the promoted app back to display 0 before tearing the service down. Prefer a
                // fresh command connection so this still works when the frame-stream socket is the
                // thing that failed; fall back to that existing stream only if direct control fails.
                if (!DashHelper.demoteExisting()) {
                    runCatching { dashSwitch?.demote() }
                }
                DiagnosticFlightRecorder.record("HELPER", "PRESERVE requested on stop")
            }
            removeForegroundNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()
        _state.value = MirrorState.Connecting

        val quality = intent?.getIntExtra(EXTRA_QUALITY, 80) ?: 80
        val dashDpi = intent?.getIntExtra(EXTRA_DASH_DPI, 240)?.coerceIn(160, 480) ?: 240
        val maxFps = intent?.getDoubleExtra(EXTRA_MAX_FPS, 3.0) ?: 3.0
        val dashResolution = dashResolutionFrom(intent)
        val dashLeftMargin = intent?.getIntExtra(EXTRA_DASH_LEFT_MARGIN, 50) ?: 50
        val dashBottomMargin = intent?.getIntExtra(EXTRA_DASH_BOTTOM_MARGIN, 16) ?: 16
        val dashMarginColor = DashMarginColor.entries.firstOrNull {
            it.rgb == (intent?.getIntExtra(EXTRA_DASH_MARGIN_COLOR, DashMarginColor.Black.rgb)
                ?: DashMarginColor.Black.rgb)
        } ?: DashMarginColor.Black
        val stickZoomInX = intent?.getIntExtra(EXTRA_STICK_ZOOM_IN_X, 93)?.coerceIn(0, 100) ?: 93
        val stickZoomInY = intent?.getIntExtra(EXTRA_STICK_ZOOM_IN_Y, 47)?.coerceIn(0, 100) ?: 47
        val stickZoomOutX = intent?.getIntExtra(EXTRA_STICK_ZOOM_OUT_X, 93)?.coerceIn(0, 100) ?: 93
        val stickZoomOutY = intent?.getIntExtra(EXTRA_STICK_ZOOM_OUT_Y, 74)?.coerceIn(0, 100) ?: 74
        val ocrEnabled = intent?.getBooleanExtra(EXTRA_OCR_ENABLED, false) ?: false
        val ocrShowArea = intent?.getBooleanExtra(EXTRA_OCR_SHOW_AREA, false) ?: false
        val ocrLeft = intent?.getIntExtra(EXTRA_OCR_LEFT, 30)?.coerceIn(20, 80) ?: 30
        val ocrTop = intent?.getIntExtra(EXTRA_OCR_TOP, 89)?.coerceIn(80, 100) ?: 89
        val ocrRight = intent?.getIntExtra(EXTRA_OCR_RIGHT, 70)?.coerceIn(20, 80) ?: 70
        val ocrBottom = intent?.getIntExtra(EXTRA_OCR_BOTTOM, 100)?.coerceIn(80, 100) ?: 100
        val ocr2Left = intent?.getIntExtra(EXTRA_OCR2_LEFT, 88)?.coerceIn(0, 100) ?: 88
        val ocr2Top = intent?.getIntExtra(EXTRA_OCR2_TOP, 56)?.coerceIn(0, 100) ?: 56
        val ocr2Right = intent?.getIntExtra(EXTRA_OCR2_RIGHT, 98)?.coerceIn(0, 100) ?: 98
        val ocr2Bottom = intent?.getIntExtra(EXTRA_OCR2_BOTTOM, 64)?.coerceIn(0, 100) ?: 64
        val restartAppOnVd = intent?.getBooleanExtra(EXTRA_RESTART_APP_ON_VD, false) ?: false
        fixedDashAppEnabled = intent?.getBooleanExtra(EXTRA_FIXED_DASH_APP_ENABLED, false) ?: false
        fixedDashAppPackage = intent?.getStringExtra(EXTRA_FIXED_DASH_APP_PACKAGE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        dashEnabled = intent?.getBooleanExtra(EXTRA_DASH_ENABLED, false) ?: false
        val btState = runCatching { BluetoothAdapter.getDefaultAdapter()?.state }.getOrNull()
        DiagnosticFlightRecorder.record(
            "SERVICE",
            "session start dashEnabled=$dashEnabled fps=$maxFps dpi=$dashDpi btState=$btState",
        )
        startSession(
            quality,
            dashDpi,
            maxFps,
            dashResolution,
            dashLeftMargin,
            dashBottomMargin,
            dashMarginColor,
            stickZoomInX,
            stickZoomInY,
            stickZoomOutX,
            stickZoomOutY,
            ocrEnabled,
            ocrShowArea,
            ocrLeft,
            ocrTop,
            ocrRight,
            ocrBottom,
            ocr2Left,
            ocr2Top,
            ocr2Right,
            ocr2Bottom,
            restartAppOnVd,
        )
        return START_NOT_STICKY
    }

    /**
     * One Bluetooth/NaviLite session. It always mirrors the phone via MediaProjection; when dash mode
     * is enabled it also spawns the privileged helper and switches the engine's source to the dash
     * (foreground app on a trusted display) whenever the phone is locked — **mirror while unlocked,
     * dash while locked**. The helper only encodes while locked, so it costs no extra battery idle.
     */
    private fun startSession(
        quality: Int,
        dashDpi: Int,
        maxFps: Double,
        dashResolution: DashResolution,
        dashLeftMargin: Int,
        dashBottomMargin: Int,
        dashMarginColor: DashMarginColor,
        stickZoomInX: Int,
        stickZoomInY: Int,
        stickZoomOutX: Int,
        stickZoomOutY: Int,
        ocrEnabled: Boolean,
        ocrShowArea: Boolean,
        ocrLeft: Int,
        ocrTop: Int,
        ocrRight: Int,
        ocrBottom: Int,
        ocr2Left: Int,
        ocr2Top: Int,
        ocr2Right: Int,
        ocr2Bottom: Int,
        restartAppOnVd: Boolean,
    ) {
        // A screen-capture grant is single-use. If it's missing or stale (e.g. cleared when the app
        // crashed + restarted), starting a mediaProjection foreground service throws SecurityException
        // — which would HARD-CRASH the app. So validate first, and on a stale grant fall back to a
        // non-projection foreground type + a clean "re-grant" message instead of crashing.
        val data = resultData
        if (resultCode == 0 || data == null) {
            runCatching { startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            fail("Screen capture not granted — tap Start again"); return
        }
        // Must be foreground (mediaProjection) before acquiring the projection (Android 10+).
        try {
            startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } catch (e: SecurityException) {
            Log.e(TAG, "media projection FGS rejected — stale capture grant", e)
            resultCode = 0; resultData = null
            runCatching { startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
            fail("Screen capture expired — tap Start again"); return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = mpm.getMediaProjection(resultCode, data) ?: run {
            fail("screen capture denied"); return
        }
        // The grant is single-use; clear it so a later restart can't reuse a dead token (→ crash).
        resultCode = 0; resultData = null
        // Create the mirror display NOW, while the projection token is fresh.
        val mirror = MediaProjectionScreenSource(this, projection, quality)
        runCatching { mirror.start() }

        val source: ScreenSource = if (dashEnabled) {
            val switch = SwitchableScreenSource(
                mirror,
                DashStreamScreenSource(
                    leftMargin = dashLeftMargin,
                    bottomMargin = dashBottomMargin,
                    zoomInXPercent = stickZoomInX,
                    zoomInYPercent = stickZoomInY,
                    zoomOutXPercent = stickZoomOutX,
                    zoomOutYPercent = stickZoomOutY,
                    ocrEnabled = ocrEnabled,
                    restartAppOnVd = restartAppOnVd,
                    appContext = applicationContext,
                ),
                restartAppOnVd = restartAppOnVd,
            )
            dashSwitch = switch
            // Spawn or reuse the helper. Starting it needs Wireless Debugging, but an already-running
            // helper serves over loopback and survives Wi-Fi loss.
            scope.launch(Dispatchers.IO) {
                runCatching {
                    DashHelper.ensureRunning(
                        this@CaptureService,
                        quality,
                        dashDpi,
                        dashResolution,
                        dashLeftMargin,
                        dashBottomMargin,
                        dashMarginColor,
                        ocrEnabled,
                        ocrShowArea,
                        ocrLeft,
                        ocrTop,
                        ocrRight,
                        ocrBottom,
                        ocr2Left,
                        ocr2Top,
                        ocr2Right,
                        ocr2Bottom,
                        preferExisting = true,
                    )
                }
                    .onFailure {
                        Log.e(TAG, "dash: helper unavailable", it)
                        fail(it.message ?: "Dash helper unavailable")
                    }
            }
            registerScreenReceiver()
            registerBluetoothReceiver()
            registerKeyguardUnlockListener()
            // Self-heal: if the helper is killed (adbd restart on Wi-Fi/debug loss), respawn it over
            // the loopback channel so the dash recovers instead of freezing.
            DashHelper.startWatchdog(
                this, quality, dashDpi, dashResolution, dashLeftMargin, dashBottomMargin, dashMarginColor,
                ocrEnabled, ocrShowArea, ocrLeft, ocrTop, ocrRight, ocrBottom,
                ocr2Left, ocr2Top, ocr2Right, ocr2Bottom,
            )
            switch
        } else {
            mirror
        }
        runEngine(source, maxFps)
    }

    /** Mirror while unlocked, dash while locked: promote the foreground app on lock, demote on unlock. */
    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val action = intent.action
                // onReceive runs on the main thread; promote/demote write to the helper socket, which
                // is network I/O (NetworkOnMainThreadException otherwise), so do it off the main thread.
                scope.launch(Dispatchers.IO) {
                    when (action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            val promotedAt = dashPromotedAtMs
                            if (promotedAt != 0L) {
                                val ageMs = System.currentTimeMillis() - promotedAt
                                if (ageMs < RETURN_TO_PHONE_GRACE_MS) {
                                    // Blanking display 0 during PROMOTE can itself produce a short-lived
                                    // screen-state edge. Do not mistake that synthetic edge for the user's
                                    // next POWER press. Once the transition has settled, a SCREEN_OFF while
                                    // already promoted is the useful signal: Android is still awake behind
                                    // the dark panel, so the physical POWER press is trying to put that awake
                                    // device to sleep. Demote immediately; the shell helper then restores the
                                    // phone through KEYCODE_WAKEUP, giving a single-press return to keyguard.
                                    Log.d(TAG, "dash: ignoring promoted screen-off during settle (${ageMs}ms)")
                                } else {
                                    returnToPhone("power press while promoted (screen off)")
                                }
                                return@launch
                            }
                            val component = dashPromotionComponent()
                            Log.d(TAG, "dash: screen off; target=$component fixed=$fixedDashAppEnabled")
                            if (component == null) {
                                Log.w(TAG, "dash: no promotion target available")
                            } else {
                                // Remember the actual target we chose. SystemUI/keyguard must not replace
                                // it on a later lock-only round trip, even if the lock screen stays up
                                // longer than the UsageStats query window.
                                lastDashTargetComponent = component
                                dashSwitch?.promote(component)
                                dashPromotedAtMs = System.currentTimeMillis()
                                dashSawLockedKeyguard = isKeyguardLockedNow()
                                scheduleLockStateSample(dashPromotedAtMs)
                            }
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            val promotedAt = dashPromotedAtMs
                            if (promotedAt != 0L) {
                                val ageMs = System.currentTimeMillis() - promotedAt
                                if (ageMs >= RETURN_TO_PHONE_GRACE_MS) {
                                    returnToPhone("screen on")
                                } else {
                                    // The helper briefly wakes the device during PROMOTE so the virtual display
                                    // can render. Ignore that synthetic wake; panel-off retries will blank display 0.
                                    Log.d(TAG, "dash: ignoring promotion wake (${ageMs}ms)")
                                    schedulePromotionWakeSettleCheck(promotedAt, ageMs)
                                }
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            returnToPhone("user present", force = true)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        screenReceiver = receiver
    }

    /**
     * Some devices do not send a useful SCREEN_ON transition after we blank display 0 directly.
     * Keyguard unlock is the higher-signal event, but the listener is API 33+ and permission-gated,
     * so it is registered defensively and the normal broadcasts remain as fallbacks.
     */

    /** Observe Android's base Bluetooth link state separately from RFCOMM/session failures. */
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    DiagnosticFlightRecorder.record("BT", "ADAPTER_STATE state=$state")
                    return
                }
                @Suppress("DEPRECATION")
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val name = runCatching { device?.name }.getOrNull() ?: "unknown"
                if (!isDashBluetoothDevice(name)) {
                    Log.d(TAG, "reconnect-v5: ignoring ACL event from non-dash device=$name action=$action")
                    return
                }
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        dashAclConnected = true
                        Log.i(TAG, "reconnect-v5: BT ACL_CONNECTED device=$name wait=$reconnectWait")
                        DiagnosticFlightRecorder.record("BT", "ACL_CONNECTED device=$name wait=$reconnectWait")
                        scheduleReconnect("ACL_CONNECTED")
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        dashAclConnected = false
                        Log.w(TAG, "reconnect-v5: BT ACL_DISCONNECTED device=$name")
                        DiagnosticFlightRecorder.record("BT", "ACL_DISCONNECTED device=$name")

                        // ACL is only a useful hint. Do not cancel the reconnect loop here:
                        // Android/CCU can flap ACL while the bike is booting, and waiting for a later
                        // ACL_CONNECTED broadcast proved unreliable on the real XMAX.
                        enterReconnectWait("ACL disconnected: $name")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        bluetoothReceiver = receiver
    }

    private fun registerKeyguardUnlockListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(PERMISSION_SUBSCRIBE_KEYGUARD) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "dash: keyguard unlock listener unavailable; permission not granted")
            return
        }
        runCatching {
            val manager = getSystemService(KeyguardManager::class.java)
                ?: error("KeyguardManager unavailable")
            val listenerClass = Class.forName("android.app.KeyguardManager\$KeyguardLockedStateListener")
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onKeyguardLockedStateChanged") {
                    val locked = args?.firstOrNull() as? Boolean ?: return@newProxyInstance null
                    scope.launch(Dispatchers.IO) {
                        if (locked) {
                            if (dashPromotedAtMs != 0L) dashSawLockedKeyguard = true
                            Log.d(TAG, "dash: keyguard locked")
                        } else {
                            onKeyguardUnlocked()
                        }
                    }
                }
                null
            }
            val executor = Executor { runnable -> scope.launch(Dispatchers.IO) { runnable.run() } }
            manager.javaClass
                .getMethod("addKeyguardLockedStateListener", Executor::class.java, listenerClass)
                .invoke(manager, executor, listener)
            keyguardManager = manager
            keyguardListener = listener
            Log.d(TAG, "dash: keyguard unlock listener registered")
        }.onFailure {
            Log.d(TAG, "dash: keyguard unlock listener unavailable: ${it.javaClass.simpleName}")
            keyguardManager = null
            keyguardListener = null
        }
    }

    private fun unregisterKeyguardUnlockListener() {
        val manager = keyguardManager ?: return
        val listener = keyguardListener ?: return
        runCatching {
            val listenerClass = Class.forName("android.app.KeyguardManager\$KeyguardLockedStateListener")
            manager.javaClass
                .getMethod("removeKeyguardLockedStateListener", listenerClass)
                .invoke(manager, listener)
        }
        keyguardManager = null
        keyguardListener = null
    }

    private fun onKeyguardUnlocked() {
        val promotedAt = dashPromotedAtMs
        if (promotedAt == 0L) return
        val ageMs = System.currentTimeMillis() - promotedAt
        if (dashSawLockedKeyguard || ageMs >= RETURN_TO_PHONE_GRACE_MS) {
            returnToPhone("keyguard unlocked")
        } else {
            Log.d(TAG, "dash: ignoring keyguard-unlocked callback before lock settled (${ageMs}ms)")
        }
    }

    private fun scheduleLockStateSample(promotedAt: Long) {
        scope.launch(Dispatchers.IO) {
            Thread.sleep(LOCK_STATE_SAMPLE_DELAY_MS)
            if (dashPromotedAtMs == promotedAt && isKeyguardLockedNow()) {
                dashSawLockedKeyguard = true
                Log.d(TAG, "dash: keyguard locked after promotion")
            }
        }
    }

    private fun schedulePromotionWakeSettleCheck(promotedAt: Long, ageMs: Long) {
        val delayMs = (RETURN_TO_PHONE_GRACE_MS - ageMs).coerceAtLeast(0L) + 150L
        scope.launch(Dispatchers.IO) {
            Thread.sleep(delayMs)
            if (dashPromotedAtMs != promotedAt) return@launch
            if (dashSawLockedKeyguard && isKeyguardUnlockedNow()) {
                returnToPhone("unlock after promotion wake")
            } else {
                Log.d(TAG, "dash: promotion wake settled; keeping dash active")
            }
        }
    }

    private fun returnToPhone(reason: String, force: Boolean = false) {
        if (!force && dashPromotedAtMs == 0L) return
        Log.d(TAG, "dash: $reason; returning to phone")
        dashPromotedAtMs = 0L
        dashSawLockedKeyguard = false
        dashSwitch?.demote()
    }

    private fun isKeyguardLockedNow(): Boolean {
        val manager = getSystemService(KeyguardManager::class.java) ?: return false
        return manager.isDeviceLocked || manager.isKeyguardLocked
    }

    private fun isKeyguardUnlockedNow(): Boolean {
        val manager = getSystemService(KeyguardManager::class.java) ?: return false
        return !manager.isDeviceLocked && !manager.isKeyguardLocked
    }

    /** Resolve the app to promote. Fixed-app mode never falls through to the foreground app. */
    private fun dashPromotionComponent(): String? {
        if (!fixedDashAppEnabled) return foregroundComponent()

        val packageName = fixedDashAppPackage
        if (packageName == null) {
            Log.w(TAG, "dash: fixed app enabled with no selection; falling back to Pillion")
            return pillionComponent()
        }
        if (isDashPromotionProhibited(packageName)) {
            Log.w(TAG, "dash: fixed app is prohibited ($packageName); falling back to Pillion")
            return pillionComponent()
        }

        val component = packageManager.getLaunchIntentForPackage(packageName)
            ?.component
            ?.flattenToString()
        if (component == null) {
            Log.w(TAG, "dash: fixed app unavailable ($packageName); falling back to Pillion")
            return pillionComponent()
        }
        Log.d(TAG, "dash: fixed app target=$component")
        return component
    }

    /**
     * The foreground app's launcher component to promote at lock.
     *
     * A keyguard-only round trip must not replace the user's app with SystemUI: after returning
     * from DASH to the lock screen, a timeout/POWER lock should promote the same app again.
     * Other prohibited foregrounds (Settings/launcher/permission UI) keep the existing safety
     * behavior and fall back to Pillion.
     */
    private fun foregroundComponent(): String? {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return lastDashTargetComponent
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 60_000, now) }
            .onFailure { Log.w(TAG, "dash: usage query failed", it) }
            .getOrNull() ?: return lastDashTargetComponent
        val event = UsageEvents.Event()
        var component: String? = lastDashTargetComponent
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val pkg = event.packageName ?: continue
                val candidate = when {
                    isKeyguardSystemUi(pkg) -> {
                        // Lock screen is only a transient system surface. Preserve the last real
                        // user app so a re-lock returns to the same DASH content.
                        Log.d(TAG, "dash: keyguard/SystemUI foreground; keeping target=$component")
                        component
                    }
                    isDashPromotionProhibited(pkg) -> pillionComponent()
                    else -> packageManager.getLaunchIntentForPackage(pkg)
                        ?.component
                        ?.flattenToString()
                }
                if (candidate == null) {
                    Log.d(TAG, "dash: ignoring foreground package without launcher: $pkg")
                } else {
                    component = candidate
                    Log.d(TAG, "dash: foreground candidate=$component")
                }
            }
        }
        if (component == null) Log.w(TAG, "dash: UsageStats returned no launchable foreground app")
        return component
    }

    private fun isKeyguardSystemUi(packageName: String): Boolean =
        packageName == "com.android.systemui" || packageName.startsWith("com.android.systemui.")



    private fun isDashPromotionProhibited(packageName: String): Boolean {
        if (packageName == ownPackageName()) return false
        if (packageName == defaultHomePackageName()) return true
        return packageName == "com.android.settings" ||
            packageName == "com.android.systemui" ||
            packageName == "com.google.android.permissioncontroller" ||
            packageName == "com.android.permissioncontroller" ||
            packageName == "com.google.android.apps.nexuslauncher" ||
            packageName == "com.android.launcher3" ||
            packageName.startsWith("com.android.systemui.")
    }

    private fun ownPackageName(): String = applicationContext.packageName

    private fun defaultHomePackageName(): String? {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    }

    private fun pillionComponent(): String? =
        packageManager.getLaunchIntentForPackage(ownPackageName())
            ?.component
            ?.flattenToString()

    private fun runEngine(screen: ScreenSource, maxFps: Double, reuseScreen: Boolean = false) {
        activeScreenSource = screen
        activeMaxFps = maxFps
        val channel = RfcommByteChannel()
        val mirror = MirrorEngine(
            channel = channel,
            screen = screen,
            maxFps = maxFps,
            keepScreenAliveOnFailure = true,
            startScreenOnStart = !reuseScreen,
        )
        engine = mirror
        engineStateJob?.cancel()
        engineStateJob = scope.launch {
            var streamingRecorded = false
            mirror.state.collect { state ->
                when (state) {
                    is MirrorState.Streaming -> {
                        _state.value = state
                        if (!streamingRecorded) {
                            streamingRecorded = true
                            DiagnosticFlightRecorder.record(
                                "SESSION",
                                "STREAMING stable reconnectWait=$reconnectWait attempt=$reconnectAttempt",
                            )
                        }
                        if (reconnectWait) {
                            Log.i(TAG, "reconnect-v5: session stable; leaving reconnect wait after attempt=$reconnectAttempt")
                        }
                        reconnectWait = false
                        reconnectAttempt = 0
                        updateNotification(getString(app.pillion.R.string.notification_streaming))
                    }
                    is MirrorState.Error -> {
                        // A dead RFCOMM engine reports Error as it unwinds. Do NOT publish that
                        // Error to the app UI: HomeScreen treats Error as an inactive session and
                        // changes the main button back to Start/Connect even though this Service,
                        // MediaProjection/helper and reconnect wait are deliberately still alive.
                        if (reconnectWait) {
                            _state.value = MirrorState.Connecting
                            onReconnectAttemptFailed(state.message)
                        } else {
                            enterReconnectWait(state.message)
                        }
                    }
                    MirrorState.Idle -> {
                        // An old/replaced engine can become Idle while the Service remains in
                        // reconnect wait. Keep the public state active until explicit Stop.
                        if (reconnectWait) {
                            _state.value = MirrorState.Connecting
                            Log.d(TAG, "reconnect-v5: suppressed engine Idle during reconnect wait")
                        } else {
                            _state.value = state
                        }
                    }
                    else -> _state.value = state
                }
            }
        }
        mirror.start(scope)
    }

    private fun isDashBluetoothDevice(name: String): Boolean =
        name.startsWith("YCCU", ignoreCase = true) || name.contains("CCU", ignoreCase = true)

    /**
     * Rebuild only the dead RFCOMM/NaviLite side.
     *
     * v5 deliberately does NOT require ACL_CONNECTED. On the real XMAX Android can establish,
     * drop and re-establish the BR/EDR ACL link during CCU boot without our receiver seeing the
     * useful edge at the right time. While reconnectWait is true we therefore probe RFCOMM every
     * few seconds. RfcommByteChannel creates a brand-new BluetoothSocket each time and selects the
     * bonded YCCU/CCU. The surviving ScreenSource/helper/VD side is reused unchanged.
     */
    private fun scheduleReconnect(trigger: String, delayMs: Long = RECONNECT_SETTLE_MS) {
        if (!reconnectWait) {
            Log.d(TAG, "reconnect-v5: reconnect not needed ($trigger); session is not waiting")
            return
        }
        if (reconnectJob?.isActive == true) {
            Log.d(TAG, "reconnect-v5: reconnect already scheduled/running; trigger=$trigger")
            return
        }
        reconnectJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "reconnect-v5: scheduling RFCOMM probe in ${delayMs}ms trigger=$trigger acl=$dashAclConnected")
            delay(delayMs)
            if (!reconnectWait) {
                Log.d(TAG, "reconnect-v5: rebuild cancelled before start; session recovered/stopped")
                reconnectJob = null
                return@launch
            }

            reconnectAttempt += 1
            val attempt = reconnectAttempt
            Log.i(TAG, "reconnect-v5: attempt=$attempt rebuilding RFCOMM + NaviLite session acl=$dashAclConnected")
            DiagnosticFlightRecorder.record("RECONNECT", "ATTEMPT $attempt acl=$dashAclConnected trigger=$trigger")
            updateNotification(getString(app.pillion.R.string.notification_reconnecting, attempt))

            val screen = activeScreenSource
            if (screen == null) {
                Log.e(TAG, "reconnect-v5: no surviving screen source; cannot reconnect")
                updateNotification(getString(app.pillion.R.string.notification_waiting))
                reconnectJob = null
                scheduleReconnect("no-screen", RECONNECT_RETRY_MS)
                return@launch
            }

            // Replace only transport/protocol engine. reuseScreen=true is the important boundary:
            // MirrorEngine must NOT call screen.start(), so reconnect cannot request MediaProjection,
            // recreate the helper, recreate VD, or otherwise disturb the proven power/display path.
            runEngine(screen, activeMaxFps, reuseScreen = true)
            reconnectJob = null
        }
    }

    private fun onReconnectAttemptFailed(reason: String) {
        Log.w(TAG, "reconnect-v5: reconnect attempt=$reconnectAttempt failed: $reason")
        DiagnosticFlightRecorder.record("RECONNECT", "FAILED attempt=$reconnectAttempt reason=$reason")
        updateNotification(getString(app.pillion.R.string.notification_waiting))

        // Retry regardless of our cached ACL flag. A fresh RFCOMM connect is the real readiness
        // test; ACL broadcasts are only hints and can be missed/flap while the CCU is booting.
        scheduleReconnect("retry", RECONNECT_RETRY_MS)
    }

    /**
     * Stable reconnect behavior for an unexpected RFCOMM/NaviLite loss.
     * Deliberately do NOT stop the service, MediaProjection, screen sources or helper, and do not
     * change the current phone/VD placement. Power/keyguard events remain solely responsible for
     * promote/demote while transport reconnects in the background.
     */
    private fun enterReconnectWait(reason: String) {
        if (reconnectWait) return
        reconnectWait = true
        reconnectAttempt = 0
        // Keep the public controller state active. HomeScreen maps Error/Idle to the Start/Connect
        // button, which falsely looks like the mirroring Service ended even though we are waiting.
        _state.value = MirrorState.Connecting
        Log.w(TAG, "reconnect-v5: transport/session lost: $reason")
        DiagnosticFlightRecorder.record("RECONNECT", "WAIT entered reason=$reason acl=$dashAclConnected")
        Log.w(TAG, "reconnect-v5: skipping normal service shutdown; preserving current display state")
        updateNotification(getString(app.pillion.R.string.notification_waiting))
        // Keep probing even when ACL is currently false. The CCU may come back without a useful
        // ACTION_ACL_CONNECTED edge reaching this receiver; RFCOMM success is our authoritative
        // signal that the dash is ready again.
        scheduleReconnect("transport-loss", RECONNECT_RETRY_MS)
    }

    private fun dashResolutionFrom(intent: Intent?): DashResolution {
        val width = intent?.getIntExtra(EXTRA_DASH_WIDTH, DashResolution.DEFAULT.width)
            ?: DashResolution.DEFAULT.width
        val height = intent?.getIntExtra(EXTRA_DASH_HEIGHT, DashResolution.DEFAULT.height)
            ?: DashResolution.DEFAULT.height
        return DashResolution(width, height)
    }

    private fun killHelper() {
        runCatching { PillionAdb.getInstance(this).runShell("pkill -f app.pillion.server.DashServer") }
    }

    private fun fail(message: String) {
        _state.value = MirrorState.Error(message)
        if (reconnectWait) {
            // During reconnect wait, transport/helper failures must not end the surviving session.
            // The notification Stop action is the only path that is allowed to end the service.
            Log.w(TAG, "reconnect-v5: fail suppressed during reconnect wait: $message")
            DiagnosticFlightRecorder.record("SERVICE", "FAIL suppressed during reconnect: $message")
            updateNotification(getString(app.pillion.R.string.notification_waiting))
            return
        }
        Log.e(TAG, "reconnect-v5: fatal startup failure; stopping service: $message")
        DiagnosticFlightRecorder.record("SERVICE", "FATAL startup failure: $message")
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "CaptureService.onDestroy()")
        DiagnosticFlightRecorder.record("SERVICE", "CaptureService destroyed reconnectWait=$reconnectWait")
        reconnectJob?.cancel()
        reconnectJob = null
        engineStateJob?.cancel()
        engineStateJob = null
        engine?.stop()
        DashHelper.stopWatchdog() // stop first, so it doesn't respawn the helper we're tearing down
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        bluetoothReceiver?.let { runCatching { unregisterReceiver(it) } }
        unregisterKeyguardUnlockListener()
        // A normal user Stop and Save/Reset restart deliberately preserve an already-running
        // shell helper. It is demoted above, stops encoding/heartbeat, and waits on loopback so a
        // later START can reuse it even with Wi-Fi unavailable. Fatal/unexpected teardown keeps the
        // old full-cleanup behavior and releases the trusted display by terminating the helper.
        val switch = dashSwitch
        if (dashEnabled && !keepDashHelperOnDestroy) {
            Thread {
                runCatching { switch?.quit() }
                killHelper()
            }.start()
        } else if (dashEnabled) {
            Log.i(TAG, "dash: preserving idle helper after service stop")
            DiagnosticFlightRecorder.record("HELPER", "PRESERVED after service stop")
        }
        scope.cancel()
        releaseWakeLock()
        _state.value = MirrorState.Idle
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keep the CPU running so the Bluetooth stream survives screen dimming / Doze during a ride. */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillion:mirror").apply {
            setReferenceCounted(false)
            acquire(MAX_SESSION_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Foreground with the right service type: mediaProjection for mirror, connectedDevice for dash. */
    private fun startForegroundTyped(type: Int) {
        val text = getString(app.pillion.R.string.notification_connecting)
        lastNotificationText = text
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        lastNotificationText = null
    }

    /**
     * One quiet, ongoing service notification. Android 14+ may still let the user swipe an ongoing
     * foreground-service notification away, but these flags make our intent explicit and prevent
     * reconnect/status updates from repeatedly alerting.
     */
    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CaptureService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_KEEP_DASH_HELPER, true)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            getString(app.pillion.R.string.notification_stop),
            stopPendingIntent,
        ).build()

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(app.pillion.R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(app.pillion.R.string.notification_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Pillion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(stopAction)
            .build()
    }

    /** Avoid notify() churn when Streaming publishes updated fps/KB values with the same UI state. */
    private fun updateNotification(text: String) {
        if (lastNotificationText == text) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "pillion"
        private const val TAG = "Pillion"
        private const val MAX_SESSION_MS = 3L * 60 * 60 * 1000 // 3h safety cap
        private const val RETURN_TO_PHONE_GRACE_MS = 3_000L
        private const val LOCK_STATE_SAMPLE_DELAY_MS = 250L
        private const val RECONNECT_SETTLE_MS = 1_500L
        private const val RECONNECT_RETRY_MS = 1_500L
        private const val PERMISSION_SUBSCRIBE_KEYGUARD =
            "android.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE"
        const val ACTION_STOP = "app.pillion.action.STOP"
        const val EXTRA_KEEP_DASH_HELPER = "app.pillion.extra.KEEP_DASH_HELPER"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_DASH_DPI = "dash_dpi"
        const val EXTRA_MAX_FPS = "maxFps"
        /** When true, the session switches to the dedicated dash display whenever the phone is locked. */
        const val EXTRA_DASH_ENABLED = "dashEnabled"
        const val EXTRA_DASH_WIDTH = "dashWidth"
        const val EXTRA_DASH_HEIGHT = "dashHeight"
        const val EXTRA_DASH_LEFT_MARGIN = "dashLeftMargin"
        const val EXTRA_DASH_BOTTOM_MARGIN = "dashBottomMargin"
        const val EXTRA_DASH_MARGIN_COLOR = "dashMarginColor"
        const val EXTRA_STICK_ZOOM_IN_X = "stickZoomInXPercent"
        const val EXTRA_STICK_ZOOM_IN_Y = "stickZoomInYPercent"
        const val EXTRA_STICK_ZOOM_OUT_X = "stickZoomOutXPercent"
        const val EXTRA_STICK_ZOOM_OUT_Y = "stickZoomOutYPercent"
        const val EXTRA_OCR_ENABLED = "ocrEnabled"
        const val EXTRA_OCR_SHOW_AREA = "ocrShowArea"
        const val EXTRA_OCR_LEFT = "ocrLeftPercent"
        const val EXTRA_OCR_TOP = "ocrTopPercent"
        const val EXTRA_OCR_RIGHT = "ocrRightPercent"
        const val EXTRA_OCR_BOTTOM = "ocrBottomPercent"
        const val EXTRA_OCR2_LEFT = "ocr2LeftPercent"
        const val EXTRA_OCR2_TOP = "ocr2TopPercent"
        const val EXTRA_OCR2_RIGHT = "ocr2RightPercent"
        const val EXTRA_OCR2_BOTTOM = "ocr2BottomPercent"
        const val EXTRA_RESTART_APP_ON_VD = "restartAppOnVd"
        const val EXTRA_FIXED_DASH_APP_ENABLED = "fixedDashAppEnabled"
        const val EXTRA_FIXED_DASH_APP_PACKAGE = "fixedDashAppPackage"

        // Handed over by the Activity after the user grants screen capture.
        @Volatile var resultCode: Int = 0
        @Volatile var resultData: Intent? = null

        private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
        val state: StateFlow<MirrorState> = _state.asStateFlow()
    }
}
