package app.pillion.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.util.Log
import app.pillion.MainActivity
import app.pillion.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Passive CCU ACL watcher. Real connection events start VD; sustained disconnection stops it.
 * CaptureService and DashServer remain unchanged.
 * Resident Stop delegates to CaptureService's established explicit Stop without changing
 * the RFCOMM, PROMOTE/DEMOTE or VD implementation.
 *
 * Android owns the service singleton. Each Start is additionally guarded here, preventing
 * duplicate receivers or foreground notifications on rapid taps.
 */
class CcuWatcherService : Service() {
    private val adapter by lazy { runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() }
    private val timerHandler = Handler(Looper.getMainLooper())
    @Volatile private var started = false
    private var registered = false
    private var trialReceiverRegistered = false
    private var status = R.string.watcher_searching
    @Volatile private var trialActive = false
    private var autoSessionOwned = false
    private var autoStopInFlight = false
    private var sessionCcuAddress: String? = null
    private var disconnectedAtMs = 0L
    private var disconnectReason = ""
    @Volatile private var trialId: String? = null
    @Volatile private var trialOutcome: String? = null
    @Volatile private var trialWaiter: CountDownLatch? = null

    private val disconnectTask = object : Runnable {
        override fun run() {
            if (!started || !autoSessionOwned || disconnectedAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - disconnectedAtMs
            if (elapsed < AUTO_STOP_GRACE_MS) {
                timerHandler.postDelayed(this, AUTO_STOP_GRACE_MS - elapsed)
                return
            }
            record("AUTO_STOP_CONFIRMED elapsedMs=$elapsed reason=$disconnectReason")
            beginAutoStop(disconnectReason)
        }
    }

    private fun scheduleDisconnect(reason: String) {
        if (!autoSessionOwned || autoStopInFlight) return
        disconnectedAtMs = SystemClock.elapsedRealtime()
        disconnectReason = reason
        timerHandler.removeCallbacks(disconnectTask)
        timerHandler.postDelayed(disconnectTask, AUTO_STOP_GRACE_MS)
        record("AUTO_STOP_PENDING reason=$reason delayMs=$AUTO_STOP_GRACE_MS")
    }

    private fun cancelDisconnect(reason: String) {
        if (disconnectedAtMs == 0L) return
        timerHandler.removeCallbacks(disconnectTask)
        disconnectedAtMs = 0L
        record("AUTO_STOP_CANCELLED reason=$reason")
    }

    private fun beginAutoStart(address: String?) {
        if (!started || autoSessionOwned || autoStopInFlight) {
            record("AUTO_START_SUPPRESSED owned=$autoSessionOwned stopping=$autoStopInFlight")
            return
        }
        autoSessionOwned = true
        sessionCcuAddress = address
        trialActive = true
        record("AUTO_START_TRIGGER source=acl_flag_ready")
        Thread({ performRoomTrial() }, "pillion-ccu-auto-start").apply {
            isDaemon = true
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun captureServiceRunning(): Boolean =
        runCatching {
            getSystemService(ActivityManager::class.java)
                .getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == CaptureService::class.java.name }
        }.getOrDefault(false)

    private fun requestCaptureStop() {
        startService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_STOP)
                .putExtra(CaptureService.EXTRA_KEEP_DASH_HELPER, true)
        )
    }

    private fun beginAutoStop(reason: String) {
        if (!started || !autoSessionOwned || autoStopInFlight) return
        autoSessionOwned = false
        sessionCcuAddress = null
        autoStopInFlight = true
        cancelDisconnect("confirmed_stop")
        trialActive = false
        trialWaiter?.countDown()
        val interactiveBefore = getSystemService(PowerManager::class.java)?.isInteractive
        val lockedBefore = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val captureBefore = captureServiceRunning()
        val restoreDark = captureBefore && (interactiveBefore == false || lockedBefore)
        record("AUTO_STOP_BEGIN reason=$reason capture=$captureBefore interactive=$interactiveBefore locked=$lockedBefore restoreDark=$restoreDark")
        Thread({
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.pillion:ccu-auto-stop")
                .apply { acquire(STOP_WAKELOCK_MS) }
            try {
                requestCaptureStop()
                record("AUTO_STOP_CAPTURE_REQUESTED")
                val deadline = SystemClock.elapsedRealtime() + STOP_SERVICE_WAIT_MS
                while (started && captureServiceRunning() && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(150L)
                }
                val stopped = !captureServiceRunning()
                record("AUTO_STOP_CAPTURE_EXIT stopped=$stopped")
                if (restoreDark && stopped && started) {
                    // DEMOTE injects WAKEUP; allow a short settle after service exit.
                    Thread.sleep(1_200L)
                    val stillLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
                    if (started && stillLocked) {
                        val adb = PillionAdb.getInstance(applicationContext)
                        val connected = (adb.isConnected() && adb.getHostAddress() == PillionAdb.LOOPBACK_HOST) ||
                            adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT)
                        if (connected) {
                            record("AUTO_STOP_SLEEP_REQUEST keyevent=223")
                            val output = adb.runShell("input keyevent 223")
                            record("AUTO_STOP_SLEEP_RESULT output=${output.trim().take(160)}")
                        } else record("AUTO_STOP_SLEEP_SKIPPED reason=adb_unavailable")
                    } else record("AUTO_STOP_SLEEP_SKIPPED reason=phone_unlocked_or_watcher_stopped")
                } else record("AUTO_STOP_SLEEP_SKIPPED reason=not_dark_or_capture_not_stopped")
            } catch (t: Throwable) {
                record("AUTO_STOP_FAILED reason=${t.javaClass.simpleName}: ${t.message}".take(230))
            } finally {
                runCatching { if (wakeLock.isHeld) wakeLock.release() }
                timerHandler.post {
                    autoStopInFlight = false
                    record("AUTO_STOP_END")
                }
            }
        }, "pillion-ccu-auto-stop").apply { isDaemon = true; start() }
    }

    private val trialEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!trialActive || intent == null) return
            val token = intent.getStringExtra(CcuAutoTrial.EXTRA_SESSION_ID)
            if (token == null || token != trialId) return
            val outcome = when (intent.action) {
                CcuAutoTrial.ACTION_CAPTURE_READY -> "CAPTURE_READY"
                CcuAutoTrial.ACTION_START_FAILED ->
                    "START_FAILED " + intent.getStringExtra(CcuAutoTrial.EXTRA_REASON).orEmpty()
                else -> return
            }
            trialOutcome = outcome
            record("TRIAL_SIGNAL $outcome")
            trialWaiter?.countDown()
        }
    }

    private fun performRoomTrial() {
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "app.pillion:ccu-auto-start")
            .apply { acquire(START_WAKELOCK_MS) }
        var wakeSent = false
        try {
            check(trialActive) { "cancelled" }
            record("TRIAL_BEGIN")
            val adb = PillionAdb.getInstance(applicationContext)
            val connected = (adb.isConnected() && adb.getHostAddress() == PillionAdb.LOOPBACK_HOST) ||
                adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT)
            check(connected) { "loopback_adb_unavailable" }
            record("TRIAL_ADB_READY loopback=true")
            // Sample immediately before WAKEUP, not before the ADB connection.
            // An already-interactive phone belongs to its user: auto-Start must not
            // force it to sleep afterward. Null is treated conservatively as ON.
            val interactiveBeforeWake = getSystemService(PowerManager::class.java)?.isInteractive
            record("TRIAL_DISPLAY_BEFORE_WAKE interactive=$interactiveBeforeWake")
            // Always issue the idempotent WAKEUP key, even if already interactive:
            // a physical power press may race the narrow sample-to-command window.
            check(trialActive) { "cancelled" }
            record("TRIAL_WAKE_REQUEST keyevent=224")
            val wakeOutput = adb.runShell("input keyevent 224")
            record("TRIAL_WAKE_RESULT output=${wakeOutput.trim().take(160)}")
            wakeSent = true
            val wakeDeadline = SystemClock.elapsedRealtime() + 5_000L
            while (trialActive && getSystemService(PowerManager::class.java)?.isInteractive != true &&
                SystemClock.elapsedRealtime() < wakeDeadline) {
                Thread.sleep(250L)
            }
            check(getSystemService(PowerManager::class.java)?.isInteractive == true) {
                "wake_not_interactive"
            }
            check(trialActive) { "cancelled" }
            val token = CcuAutoTrial.issue(applicationContext)
            trialId = token
            trialWaiter = CountDownLatch(1)
            trialOutcome = null
            // When MainActivity already exists (especially after Reset & Restart),
            // plain 'am start' can merely foreground the task without delivering
            // the token to onCreate/onNewIntent. Reuse the existing root Activity
            // and dispatch its new Intent instead of creating another instance.
            val flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            record("TRIAL_AM_START_REQUEST reuseExistingActivity=true")
            val output = adb.runShell(
                "am start -f 0x${flags.toString(16)} " +
                    "-n app.pillion/app.pillion.MainActivity --es " +
                    "${CcuAutoTrial.EXTRA_TOKEN} $token"
            )
            record("TRIAL_AM_START_RESULT output=${output.trim().take(240)}")
            check(!output.contains("Error:", ignoreCase = true)) { "am_start_error" }
            check(trialWaiter?.await(20L, TimeUnit.SECONDS) == true) {
                "capture_ready_timeout"
            }
            val outcome = trialOutcome.orEmpty()
            check(outcome == "CAPTURE_READY") { outcome }
            check(trialActive) { "cancelled" }
            if (interactiveBeforeWake == false) {
                // Restore only the screen-OFF state that the auto-run temporarily woke.
                record("TRIAL_SLEEP_REQUEST keyevent=223 reason=restore_initial_off")
                val sleepOutput = adb.runShell("input keyevent 223")
                record("TRIAL_SLEEP_RESULT output=${sleepOutput.trim().take(160)}")
            } else {
                val reason = if (interactiveBeforeWake == true) "initial_screen_on" else "initial_state_unknown"
                record("TRIAL_SLEEP_SKIPPED reason=$reason")
            }
            record("TRIAL_COMMAND_SEQUENCE_COMPLETE")
            timerHandler.post { updateNotification() }
        } catch (t: Throwable) {
            val reason = "${t.javaClass.simpleName}: ${t.message}".take(200)
            record("TRIAL_FAILED reason=$reason wakeSent=$wakeSent")
            timerHandler.post { updateNotification() }
        } finally {
            trialWaiter = null
            CcuAutoTrial.clear(applicationContext)
            trialActive = false
            record("TRIAL_END")
            runCatching { if (wakeLock.isHeld) wakeLock.release() }
        }
    }

    // Six-second flag is for START only; its expiration is not a Stop signal.
    // Monotonic elapsed time prevents wall-clock changes from affecting the six-second window.
    private var mayConnect = false
    private var candidateAddress: String? = null
    private var candidateAtMs = 0L
    private var lastConnectedAtMs = 0L

    private val expiryTask = object : Runnable {
        override fun run() {
            if (!started || lastConnectedAtMs == 0L) return
            val elapsed = SystemClock.elapsedRealtime() - lastConnectedAtMs
            if (elapsed < TRIGGER_WINDOW_MS) {
                timerHandler.postDelayed(this, (TRIGGER_WINDOW_MS - elapsed).coerceAtLeast(1L))
                return
            }
            record("FLAG_WINDOW_EXPIRED elapsedMs=$elapsed wasReady=$mayConnect")
            resetTrigger("six_seconds_without_acl_connected")
        }
    }

    private fun resetTrigger(reason: String) {
        timerHandler.removeCallbacks(expiryTask)
        val wasReady = mayConnect
        mayConnect = false
        candidateAddress = null
        candidateAtMs = 0L
        lastConnectedAtMs = 0L
        if (wasReady) record("FLAG_NOT_READY reason=$reason")
        else record("FLAG_RESET reason=$reason")
        updateNotification()
    }

    private fun onCcuConnected(device: BluetoothDevice, name: String) {
        val now = SystemClock.elapsedRealtime()
        val address = runCatching { device.address }.getOrNull()
        val elapsed = if (lastConnectedAtMs > 0L) now - lastConnectedAtMs else -1L
        record("ACL_CONNECTED name=$name intervalMs=$elapsed")

        // A stale callback after a deep sleep must not count as the second event.
        val sameDevice = address != null && address == candidateAddress
        val withinWindow = elapsed >= 0L && elapsed <= TRIGGER_WINDOW_MS
        if (!sameDevice || !withinWindow) {
            if (mayConnect) record("FLAG_NOT_READY reason=connection_gap intervalMs=$elapsed")
            mayConnect = false
            candidateAddress = address
            candidateAtMs = now
            record("FLAG_CANDIDATE first=1/2 windowMs=$TRIGGER_WINDOW_MS name=$name")
        } else if (!mayConnect) {
            mayConnect = true
            record("FLAG_READY intervalMs=$elapsed name=$name")
            beginAutoStart(address)
        }
        if (sessionCcuAddress == null || sessionCcuAddress == address) cancelDisconnect("acl_connected")
        lastConnectedAtMs = now
        timerHandler.removeCallbacks(expiryTask)
        timerHandler.postDelayed(expiryTask, TRIGGER_WINDOW_MS)
        changeStatus(R.string.watcher_found, "CCU_FOUND via=ACL_CONNECTED name=$name")
        // The second ACL event often does not change the status resource. Refresh for the flag.
        updateNotification()
    }

    private fun updateNotification() {
        if (!started) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> {
                        resetTrigger("bluetooth_off")
                        scheduleDisconnect("bluetooth_off")
                        changeStatus(R.string.watcher_bt_off, "BT_OFF")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        resetTrigger("bluetooth_on")
                        changeStatus(R.string.watcher_searching, "BT_ON")
                    }
                }
                return
            }
            val device = readDevice(intent) ?: return
            if (!isTarget(device)) return
            val name = runCatching { device.name }.getOrNull().orEmpty()
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    onCcuConnected(device, name)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Debounce auto-Stop separately from the six-second READY flag.
                    val address = runCatching { device.address }.getOrNull()
                    if (sessionCcuAddress == null || sessionCcuAddress == address) {
                        scheduleDisconnect("acl_disconnected")
                    }
                    changeStatus(R.string.watcher_disconnected,
                        "CCU_DISCONNECTED name=$name flagReady=$mayConnect")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            record("WATCH_STOP requested")
            trialActive = false
            trialWaiter?.countDown()
            // Stop the current cast through its existing explicit Stop path. This also
            // DEMOTEs the VD target and preserves the idle Helper for a later Start.
            // The watcher is a separate process: its static CaptureService state is
            // not shared with the main process, so dispatch Stop directly.
            try {
                requestCaptureStop()
                record("WATCH_CAPTURE_STOP requested keepHelper=true")
            } catch (t: Exception) {
                record("WATCH_CAPTURE_STOP fallback reason=${t.javaClass.simpleName}")
                // Last resort if Android refuses the explicit service command.
                runCatching { stopService(Intent(this, CaptureService::class.java)) }
            } finally {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (started) {
            record("WATCH_START duplicate_ignored")
            publishStatus(true)
            return START_STICKY
        }
        started = true
        createNotificationChannel()
        startForegroundTyped(buildNotification())
        record("WATCH_START pid=${android.os.Process.myPid()} restored=${intent == null}")
        registerEvents()
        val filter = IntentFilter().apply {
            addAction(CcuAutoTrial.ACTION_CAPTURE_READY)
            addAction(CcuAutoTrial.ACTION_START_FAILED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trialEvents, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(trialEvents, filter)
        }
        trialReceiverRegistered = true
        record("AUTO_READY passive_acl=true start_windowMs=$TRIGGER_WINDOW_MS stop_graceMs=$AUTO_STOP_GRACE_MS")
        if (adapter?.isEnabled != true) {
            changeStatus(R.string.watcher_bt_off, "BT_OFF initial")
        } else {
            record("ACL_EVENT_WAITING passive=true")
        }
        publishStatus(true)
        return START_STICKY
    }

    private fun registerEvents() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(events, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(events, filter)
        }
        registered = true
        val names = runCatching { adapter?.bondedDevices.orEmpty().mapNotNull { it.name } }
            .getOrDefault(emptyList())
            .filter { matchesCcu(it) }
        record("BONDED_TARGETS count=${names.size} names=${names.joinToString(",")}")
    }

    @Suppress("DEPRECATION")
    private fun readDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun isTarget(device: BluetoothDevice): Boolean {
        if (!hasBluetoothPermission()) return false
        return runCatching {
            device.bondState == BluetoothDevice.BOND_BONDED && matchesCcu(device.name.orEmpty())
        }.getOrDefault(false)
    }

    private fun matchesCcu(name: String): Boolean =
        name.startsWith("YCCU", ignoreCase = true) || name.contains("CCU", ignoreCase = true)

    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun changeStatus(next: Int, event: String) {
        if (!started) return
        if (status != next || event.startsWith("CCU_")) record(event)
        if (status == next) return
        status = next
        updateNotification()
        publishStatus(true)
    }

    private fun publishStatus(running: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_RUNNING, running)
            .putExtra(EXTRA_STATUS, if (running) getString(status) else "stopped"))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.watcher_notification_title),
                NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 21,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 22,
            Intent(this, CcuWatcherService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle(getString(R.string.watcher_notification_title))
            .setContentText(getString(status))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.watcher_stop), stop).build())
            .build()
    }

    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun record(event: String) {
        Log.i(TAG, event)
        // Independent, bounded log. Never write from this process into the main flight recorder.
        runCatching {
            val file = File(filesDir, "diagnostics/ccu-watcher.log")
            file.parentFile?.mkdirs()
            if (file.length() >= MAX_LOG_BYTES) file.writeText("[watcher log rotated]\n")
            file.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} $event\n")
        }
    }

    override fun onDestroy() {
        trialActive = false
        started = false
        timerHandler.removeCallbacks(expiryTask)
        timerHandler.removeCallbacks(disconnectTask)
        trialWaiter?.countDown()
        CcuAutoTrial.clear(applicationContext)
        if (trialReceiverRegistered) {
            runCatching { unregisterReceiver(trialEvents) }
            trialReceiverRegistered = false
        }
        if (registered) {
            unregisterReceiver(events)
            registered = false
        }
        record("WATCH_STOP completed")
        publishStatus(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "app.pillion.ccu.ACTION_START"
        const val ACTION_STOP = "app.pillion.ccu.ACTION_STOP"
        const val ACTION_STATUS = "app.pillion.ccu.ACTION_STATUS"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_STATUS = "status"
        private const val TAG = "Pillion-CCU-Watcher"
        private const val CHANNEL_ID = "pillion-ccu-watcher"
        private const val NOTIFICATION_ID = 40
        private const val TRIGGER_WINDOW_MS = 6_000L
        private const val AUTO_STOP_GRACE_MS = 10_000L
        private const val STOP_SERVICE_WAIT_MS = 7_000L
        private const val START_WAKELOCK_MS = 45_000L
        private const val STOP_WAKELOCK_MS = 20_000L
        private const val MAX_LOG_BYTES = 256 * 1024L
    }
}
