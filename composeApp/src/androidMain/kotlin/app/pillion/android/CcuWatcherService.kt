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
import android.os.IBinder
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
    private var started = false
    private var registered = false
    private var connected = false
    private var status = R.string.watcher_searching
    private var lastDeviceName: String? = null

    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> {
                        connected = false
                        changeStatus(R.string.watcher_bt_off, "BT_OFF")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        connected = false
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
                    connected = true
                    lastDeviceName = name
                    changeStatus(R.string.watcher_found, "CCU_FOUND via=ACL_CONNECTED name=$name")
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connected = false
                    changeStatus(R.string.watcher_disconnected, "CCU_DISCONNECTED name=$name")
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
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
        started = false
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
        private const val MAX_LOG_BYTES = 256 * 1024L
    }
}
