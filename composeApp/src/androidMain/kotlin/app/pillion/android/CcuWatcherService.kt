package app.pillion.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.os.SystemClock
import android.util.Log
import app.pillion.MainActivity
import app.pillion.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Experimental, separate-process CCU observer. It never calls CaptureService, Helper,
 * MediaProjection, PROMOTE/DEMOTE, or RFCOMM. A discovered device is NOT a VD start.
 *
 * Android owns the service singleton. Each Start is additionally guarded here, preventing
 * duplicate receivers or foreground notifications on rapid taps.
 */
class CcuWatcherService : Service() {
    private val adapter by lazy { runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() }
    private val timerHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var registered = false
    private var status = R.string.watcher_searching

    // Experimental trigger flag only. No real projection start/stop is performed here.
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
        }
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
                    // A short disconnect is expected during the observed CCU cycle.
                    // Keep the current flag until the last ACL_CONNECTED is 6s old.
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
            stopSelf()
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
        return builder.setContentTitle(
                getString(if (mayConnect) R.string.watcher_flag_ready else R.string.watcher_flag_blocked)
            )
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
        started = false
        timerHandler.removeCallbacks(expiryTask)
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
        private const val MAX_LOG_BYTES = 256 * 1024L
    }
}
