package app.pillion.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import app.pillion.MainActivity
import app.pillion.core.DashStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

data class AdbPairingEndpoint(val host: String, val port: Int)

data class AdbPairingState(
    val stage: DashStage = DashStage.Idle,
    val message: String? = null,
    val endpoint: AdbPairingEndpoint? = null,
    /** ADB itself is authenticated/connected; AndroidDashSetup may now prepare DashHelper. */
    val adbConnected: Boolean = false,
    /** The pairing key is already usable, so a helper/connect retry does not need a new PIN. */
    val canRetrySetup: Boolean = false,
)

private data class PairingSubmission(
    val code: String,
    val endpoint: AdbPairingEndpoint?,
)

private enum class PairingNotificationAction { Reply, Open, None }

private data class PairingNotificationSpec(
    val text: String,
    val ongoing: Boolean,
    val action: PairingNotificationAction,
)

/**
 * Coordinates the no-PC Wireless-debugging bootstrap.
 *
 * This object is deliberately limited to the one-time setup session. Normal dash operation uses
 * DashHelper/PillionAdb directly and does not start this pairing discovery. A setup session owns:
 *
 * 1. discovery of the ephemeral `_adb-tls-pairing._tcp` service while Android's pairing-code dialog
 *    is open;
 * 2. the high-priority setup notification used to enter the PIN; and
 * 3. pairing + the first authenticated ADB connection.
 *
 * Once the pairing key is accepted, discovery is stopped immediately. AndroidDashSetup then prepares
 * the helper and calls [markSetupComplete] or [markSetupFailed].
 */
object AdbPairingCoordinator {
    const val ACTION_PAIR_CODE = "app.pillion.action.PAIR_CODE"
    const val KEY_PAIRING_CODE = "app.pillion.extra.PAIRING_CODE"

    private const val CHANNEL_ID = "pillion_adb_pairing"
    private const val NOTIFICATION_ID = 42
    private const val SERVICE_TYPE = "_adb-tls-pairing._tcp."
    private const val SERVICE_TYPE_NORMALIZED = "_adb-tls-pairing._tcp"
    private const val LOCALHOST = "127.0.0.1"
    private const val TAG = "Pillion"
    // Initial setup only. Normal runtime reconnect timeouts live in DashHelper and are unchanged.
    private const val PAIRING_PORT_WAIT_TIMEOUT_MS = 5_000L
    private const val PAIRING_OPERATION_TIMEOUT_MS = 10_000L
    private const val SETUP_ADB_CONNECT_TIMEOUT_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(AdbPairingState())
    val state: StateFlow<AdbPairingState> = _state.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var resolving = false
    @Volatile private var setupSessionActive = false
    @Volatile private var resolvedServiceName: String? = null

    private val notificationLock = Any()
    private var pendingNotification: PairingNotificationSpec? = null
    private var lastPostedNotificationKey: String? = null

    /** Start (or resume) the one-time pairing assistant. Safe to call twice around permission flow. */
    fun start(context: Context) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        if (!setupSessionActive) {
            setupSessionActive = true
            resolvedServiceName = null
            _state.value = AdbPairingState(
                stage = DashStage.Idle,
                message = appContext.getString(app.pillion.R.string.adb_waiting_pairing_dialog),
            )
            synchronized(notificationLock) {
                pendingNotification = null
                lastPostedNotificationKey = null
            }
        }

        if (_state.value.endpoint != null) {
            val endpoint = _state.value.endpoint!!
            postPairingNotification(
                appContext,
                appContext.getString(app.pillion.R.string.adb_found_port_notification, endpoint.port),
                ongoing = true,
                action = PairingNotificationAction.Reply,
            )
        } else {
            postPairingNotification(
                appContext,
                appContext.getString(app.pillion.R.string.adb_open_pairing_dialog),
                ongoing = true,
                action = PairingNotificationAction.Reply,
            )
        }
        startDiscovery(appContext)
    }

    /**
     * Re-open Developer options from onboarding step 2. Unlike a fresh [start], this preserves the
     * current setup state/endpoint. If setup has already failed, pairing discovery is resumed so a
     * newly-opened Android pairing-code dialog can be detected and the notification becomes useful
     * again instead of remaining stuck on the old failure text.
     */
    fun resumeForSettings(context: Context) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        if (!setupSessionActive) {
            start(appContext)
            return
        }

        val current = _state.value
        when (current.stage) {
            DashStage.Pairing -> {
                postPairingNotification(
                    appContext,
                    current.message ?: appContext.getString(app.pillion.R.string.adb_pairing_failed_retry),
                    ongoing = true,
                    action = PairingNotificationAction.None,
                )
            }

            DashStage.Connecting -> {
                postPairingNotification(
                    appContext,
                    current.message ?: appContext.getString(app.pillion.R.string.adb_preparing_pillion),
                    ongoing = true,
                    action = PairingNotificationAction.Open,
                )
            }

            DashStage.Connected -> {
                postPairingNotification(
                    appContext,
                    appContext.getString(app.pillion.R.string.adb_setup_complete_return),
                    ongoing = false,
                    action = PairingNotificationAction.Open,
                )
            }

            DashStage.Idle, DashStage.Error, DashStage.Casting -> {
                // Pairing/setup errors can leave discovery stopped. The user explicitly chose
                // "Open settings again", so resume discovery and restore a PIN-capable notification
                // without discarding the existing retry state or cached endpoint.
                if (current.endpoint != null) {
                    postPairingNotification(
                        appContext,
                        appContext.getString(app.pillion.R.string.adb_found_port_notification, current.endpoint.port),
                        ongoing = true,
                        action = PairingNotificationAction.Reply,
                    )
                } else {
                    postPairingNotification(
                        appContext,
                        appContext.getString(app.pillion.R.string.adb_open_pairing_dialog),
                        ongoing = true,
                        action = PairingNotificationAction.Reply,
                    )
                }
                startDiscovery(appContext)
            }
        }
    }

    /** Permission callback: post the latest state, but never restart/reset discovery. */
    fun onNotificationPermissionGranted(context: Context) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        val spec = synchronized(notificationLock) {
            lastPostedNotificationKey = null
            pendingNotification
        } ?: return
        postNotificationSpec(appContext, spec)
    }

    /** End only the setup assistant. This never stops DashHelper or normal Pillion operation. */
    fun stop(context: Context) {
        val appContext = context.applicationContext
        setupSessionActive = false
        stopDiscovery(appContext)
        resolvedServiceName = null
        appContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        synchronized(notificationLock) {
            pendingNotification = null
            lastPostedNotificationKey = null
        }
        _state.value = AdbPairingState()
    }

    fun pairWithDiscoveredEndpoint(context: Context, input: String) {
        if (!setupSessionActive) return
        scope.launch { pairWithDiscoveredEndpointBlocking(context.applicationContext, input.trim()) }
    }

    fun pairExplicit(context: Context, endpoint: AdbPairingEndpoint, code: String) {
        if (!setupSessionActive) return
        scope.launch { pair(context.applicationContext, endpoint, code.trim()) }
    }

    /** Retry the authenticated ADB connection only. No new pairing discovery/PIN is started. */
    fun connect(context: Context) {
        val appContext = context.applicationContext
        setupSessionActive = true
        ensureChannel(appContext)
        _state.value = AdbPairingState(
            stage = DashStage.Connecting,
            message = appContext.getString(app.pillion.R.string.adb_connecting_wireless_debugging),
        )
        postPairingNotification(
            appContext,
            appContext.getString(app.pillion.R.string.adb_connecting_wireless_debugging),
            ongoing = true,
            action = PairingNotificationAction.Open,
        )
        scope.launch {
            runCatching { PillionAdb.getInstance(appContext).autoConnectDevice(appContext, timeoutMs = SETUP_ADB_CONNECT_TIMEOUT_MS) }
                .onSuccess { connected ->
                    if (!setupSessionActive) return@onSuccess
                    if (connected) {
                        _state.value = AdbPairingState(
                            stage = DashStage.Connecting,
                            message = appContext.getString(app.pillion.R.string.adb_preparing_pillion),
                            adbConnected = true,
                            canRetrySetup = true,
                        )
                        postPairingNotification(
                            appContext,
                            appContext.getString(app.pillion.R.string.adb_preparing_pillion),
                            ongoing = true,
                            action = PairingNotificationAction.Open,
                        )
                    } else {
                        val message = appContext.getString(app.pillion.R.string.adb_paired_service_not_found)
                        _state.value = AdbPairingState(
                            stage = DashStage.Error,
                            message = message,
                            canRetrySetup = true,
                        )
                        postPairingNotification(
                            appContext,
                            appContext.getString(app.pillion.R.string.adb_setup_retry_failed),
                            ongoing = false,
                            action = PairingNotificationAction.Open,
                        )
                    }
                }
                .onFailure { t ->
                    if (!setupSessionActive) return@onFailure
                    _state.value = AdbPairingState(
                        stage = DashStage.Error,
                        message = t.message ?: appContext.getString(app.pillion.R.string.adb_connect_failed),
                        canRetrySetup = true,
                    )
                    postPairingNotification(
                        appContext,
                        appContext.getString(app.pillion.R.string.adb_setup_retry_failed),
                        ongoing = false,
                        action = PairingNotificationAction.Open,
                    )
                }
        }
    }

    /** Called by AndroidDashSetup once the authenticated ADB connection is ready. */
    fun markPreparing(context: Context) {
        if (!setupSessionActive) return
        val appContext = context.applicationContext
        _state.value = _state.value.copy(
            stage = DashStage.Connecting,
            message = appContext.getString(app.pillion.R.string.adb_preparing_pillion),
            adbConnected = true,
            canRetrySetup = true,
        )
        postPairingNotification(
            appContext,
            appContext.getString(app.pillion.R.string.adb_preparing_pillion),
            ongoing = true,
            action = PairingNotificationAction.Open,
        )
    }

    /** Helper is ready: pairing discovery is definitely gone; success now means Pillion is usable. */
    fun markSetupComplete(context: Context) {
        if (!setupSessionActive) return
        val appContext = context.applicationContext
        stopDiscovery(appContext)
        resolvedServiceName = null
        setupSessionActive = false
        _state.value = AdbPairingState(
            stage = DashStage.Connected,
            message = appContext.getString(app.pillion.R.string.adb_setup_complete),
            adbConnected = true,
        )
        postPairingNotification(
            appContext,
            appContext.getString(app.pillion.R.string.adb_setup_complete_return),
            ongoing = false,
            action = PairingNotificationAction.Open,
        )
    }

    /** Pairing/ADB succeeded but helper preparation did not; retry without asking for another PIN. */
    fun markSetupFailed(context: Context, detail: String?) {
        if (!setupSessionActive) return
        val appContext = context.applicationContext
        stopDiscovery(appContext)
        resolvedServiceName = null
        _state.value = AdbPairingState(
            stage = DashStage.Error,
            message = detail ?: appContext.getString(app.pillion.R.string.adb_setup_failed),
            adbConnected = true,
            canRetrySetup = true,
        )
        postPairingNotification(
            appContext,
            appContext.getString(app.pillion.R.string.adb_setup_failed_return),
            ongoing = false,
            action = PairingNotificationAction.Open,
        )
    }

    private suspend fun pairWithDiscoveredEndpointBlocking(context: Context, input: String) {
        if (!setupSessionActive) return
        val submission = parsePairingSubmission(input)
        if (submission == null) {
            _state.value = _state.value.copy(
                stage = DashStage.Error,
                message = context.getString(app.pillion.R.string.adb_enter_code_or_port),
                canRetrySetup = false,
            )
            postPairingNotification(
                context,
                context.getString(app.pillion.R.string.adb_notification_enter_code_or_port),
                ongoing = true,
                action = PairingNotificationAction.Reply,
            )
            return
        }

        val endpoint = submission.endpoint
            ?: waitForEndpoint()
            ?: run {
                if (!setupSessionActive) return
                Log.d(TAG, "adb pairing: no cached endpoint, restarting discovery for code submit")
                restartDiscovery(context)
                waitForEndpoint()
            }
        if (endpoint == null) {
            _state.value = _state.value.copy(
                stage = DashStage.Error,
                message = context.getString(app.pillion.R.string.adb_no_pairing_port),
                endpoint = null,
                canRetrySetup = false,
            )
            postPairingNotification(
                context,
                context.getString(app.pillion.R.string.adb_notification_port_not_found),
                ongoing = true,
                action = PairingNotificationAction.Reply,
            )
            return
        }

        pair(context, endpoint, submission.code)
    }

    private suspend fun waitForEndpoint(timeoutMs: Long = PAIRING_PORT_WAIT_TIMEOUT_MS): AdbPairingEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (setupSessionActive && System.currentTimeMillis() < deadline) {
            state.value.endpoint?.let { return it }
            delay(150)
        }
        return if (setupSessionActive) state.value.endpoint else null
    }

    private suspend fun pair(context: Context, endpoint: AdbPairingEndpoint, code: String) {
        if (!setupSessionActive) return
        val hosts = listOf(LOCALHOST, endpoint.host).distinct()
        _state.value = AdbPairingState(
            stage = DashStage.Pairing,
            message = context.getString(app.pillion.R.string.adb_pairing_on_port, endpoint.port),
            endpoint = endpoint,
        )
        postPairingNotification(
            context,
            context.getString(app.pillion.R.string.adb_pairing_on_port, endpoint.port),
            ongoing = true,
            action = PairingNotificationAction.None,
        )

        var lastFailure: Throwable? = null
        val paired = withTimeoutOrNull(PAIRING_OPERATION_TIMEOUT_MS) {
            for (host in hosts) {
                val pairingResult = runCatching {
                    // Keep the timeout local to initial pairing. runInterruptible also gives the
                    // blocking libadb call a chance to abort when the setup timeout expires.
                    runInterruptible {
                        PillionAdb.getInstance(context).pairDevice(host, endpoint.port, code)
                    }
                }
                if (pairingResult.isSuccess) return@withTimeoutOrNull true
                lastFailure = pairingResult.exceptionOrNull()
            }
            false
        }

        if (!setupSessionActive) return
        if (paired != true) {
            val message = if (paired == null) {
                context.getString(app.pillion.R.string.adb_pairing_timed_out)
            } else {
                lastFailure?.message ?: context.getString(app.pillion.R.string.adb_pairing_failed_expired)
            }
            _state.value = AdbPairingState(
                stage = DashStage.Error,
                message = message,
                endpoint = endpoint,
            )
            postPairingNotification(
                context,
                if (paired == null) context.getString(app.pillion.R.string.adb_pairing_timed_out)
                else context.getString(app.pillion.R.string.adb_pairing_failed_retry),
                ongoing = true,
                action = PairingNotificationAction.Reply,
            )
            return
        }

        // The pairing key is persisted now. The ephemeral pairing service is no longer needed,
        // so end its NSD/MulticastLock lifetime before moving into normal authenticated ADB.
        stopDiscovery(context)
        resolvedServiceName = null

        _state.value = AdbPairingState(
            stage = DashStage.Connecting,
            message = context.getString(app.pillion.R.string.adb_paired_connecting),
            canRetrySetup = true,
        )
        postPairingNotification(
            context,
            context.getString(app.pillion.R.string.adb_paired_connecting),
            ongoing = true,
            action = PairingNotificationAction.Open,
        )

        val connectResult = runCatching {
            PillionAdb.getInstance(context).autoConnectDevice(context, timeoutMs = SETUP_ADB_CONNECT_TIMEOUT_MS)
        }
        if (!setupSessionActive) return
        val connected = connectResult.getOrDefault(false)
        if (connected) {
            _state.value = AdbPairingState(
                stage = DashStage.Connecting,
                message = context.getString(app.pillion.R.string.adb_preparing_pillion),
                adbConnected = true,
                canRetrySetup = true,
            )
            postPairingNotification(
                context,
                context.getString(app.pillion.R.string.adb_preparing_pillion),
                ongoing = true,
                action = PairingNotificationAction.Open,
            )
        } else {
            val detail = connectResult.exceptionOrNull()?.message
                ?: context.getString(app.pillion.R.string.adb_paired_connect_failed)
            _state.value = AdbPairingState(
                stage = DashStage.Error,
                message = detail,
                canRetrySetup = true,
            )
            postPairingNotification(
                context,
                context.getString(app.pillion.R.string.adb_paired_connect_failed_return),
                ongoing = false,
                action = PairingNotificationAction.Open,
            )
        }
    }

    private fun parsePairingSubmission(input: String): PairingSubmission? {
        if (input.isBlank()) return null
        val hostPort = Regex("""(\d{1,3}(?:\.\d{1,3}){3})\s*:\s*(\d{1,5})""").find(input)
        val numbers = Regex("""\d+""").findAll(input).map { it.value }.toList()
        val compactDigits = input.filter { it.isDigit() }
        val code = numbers.firstOrNull { it.length == 6 }
            ?: compactDigits.takeIf { it.length == 6 }
            ?: return null

        val host = hostPort?.groupValues?.getOrNull(1) ?: LOCALHOST
        val port = hostPort?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: numbers.firstNotNullOfOrNull { number ->
                number.toIntOrNull()?.takeIf { number != code && it in 1..65535 }
            }
        return PairingSubmission(code = code, endpoint = port?.let { AdbPairingEndpoint(host, it) })
    }

    private fun startDiscovery(context: Context) {
        if (!setupSessionActive || discoveryListener != null) return

        multicastLock = runCatching {
            context.getSystemService(WifiManager::class.java)
                .createMulticastLock("pillion-adb-pairing")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()

        val nsd = context.getSystemService(NsdManager::class.java)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "adb pairing: discovery started for $serviceType")
                if (_state.value.stage == DashStage.Idle && _state.value.endpoint == null) {
                    _state.value = _state.value.copy(
                        message = context.getString(app.pillion.R.string.adb_waiting_pairing_dialog),
                    )
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "adb pairing: service found ${serviceInfo.serviceName} ${serviceInfo.serviceType}")
                val stage = _state.value.stage
                if (!setupSessionActive ||
                    serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE_NORMALIZED ||
                    resolving ||
                    stage == DashStage.Pairing ||
                    stage == DashStage.Connecting ||
                    stage == DashStage.Connected
                ) return

                resolving = true
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.w(TAG, "adb pairing: resolve failed $errorCode for ${serviceInfo.serviceName}")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val stage = _state.value.stage
                        if (!setupSessionActive ||
                            serviceInfo.port !in 1..65535 ||
                            stage == DashStage.Pairing ||
                            stage == DashStage.Connecting ||
                            stage == DashStage.Connected
                        ) return
                        val host = serviceInfo.host?.hostAddress ?: LOCALHOST
                        val endpoint = AdbPairingEndpoint(host, serviceInfo.port)
                        resolvedServiceName = serviceInfo.serviceName
                        Log.d(TAG, "adb pairing: resolved ${serviceInfo.serviceName} to $host:${serviceInfo.port}")
                        _state.value = AdbPairingState(
                            stage = DashStage.Idle,
                            message = context.getString(app.pillion.R.string.adb_found_pairing_port, endpoint.port),
                            endpoint = endpoint,
                        )
                        postPairingNotification(
                            context,
                            context.getString(app.pillion.R.string.adb_found_port_notification, endpoint.port),
                            ongoing = true,
                            action = PairingNotificationAction.Reply,
                        )
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "adb pairing: service lost ${serviceInfo.serviceName}")
                if (serviceInfo.serviceName != resolvedServiceName) return
                resolvedServiceName = null
                val current = _state.value
                _state.value = current.copy(endpoint = null)

                // Only return to the waiting prompt while we are still waiting for PIN entry. Once the
                // user has submitted a code, the endpoint is already captured by pair() and service loss
                // is expected as Android closes/replaces the ephemeral pairing service.
                if (setupSessionActive && current.stage == DashStage.Idle) {
                    _state.value = _state.value.copy(
                        stage = DashStage.Idle,
                        message = context.getString(app.pillion.R.string.adb_waiting_pairing_dialog),
                    )
                    postPairingNotification(
                        context,
                        context.getString(app.pillion.R.string.adb_open_pairing_dialog),
                        ongoing = true,
                        action = PairingNotificationAction.Reply,
                    )
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "adb pairing: discovery stopped for $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "adb pairing: discovery failed $errorCode for $serviceType")
                stopDiscovery(context)
                _state.value = AdbPairingState(
                    stage = DashStage.Error,
                    message = context.getString(app.pillion.R.string.adb_discovery_failed, errorCode),
                )
                postPairingNotification(
                    context,
                    context.getString(app.pillion.R.string.adb_discovery_failed_notification),
                    ongoing = true,
                    action = PairingNotificationAction.Reply,
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "adb pairing: stop discovery failed $errorCode for $serviceType")
            }
        }

        discoveryListener = listener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { t ->
                stopDiscovery(context)
                _state.value = AdbPairingState(
                    stage = DashStage.Error,
                    message = t.message ?: context.getString(app.pillion.R.string.adb_discovery_failed_generic),
                )
                postPairingNotification(
                    context,
                    context.getString(app.pillion.R.string.adb_discovery_failed_notification),
                    ongoing = true,
                    action = PairingNotificationAction.Reply,
                )
            }
    }

    private fun restartDiscovery(context: Context) {
        stopDiscovery(context)
        resolvedServiceName = null
        _state.value = _state.value.copy(
            stage = DashStage.Idle,
            endpoint = null,
            message = context.getString(app.pillion.R.string.adb_waiting_pairing_dialog),
            adbConnected = false,
            canRetrySetup = false,
        )
        startDiscovery(context)
        postPairingNotification(
            context,
            context.getString(app.pillion.R.string.adb_open_pairing_dialog),
            ongoing = true,
            action = PairingNotificationAction.Reply,
        )
    }

    private fun stopDiscovery(context: Context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        discoveryListener?.let { listener -> runCatching { nsd.stopServiceDiscovery(listener) } }
        discoveryListener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        resolving = false
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(app.pillion.R.string.adb_setup_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /**
     * Setup notifications are intentionally noisy on meaningful state changes. Exact duplicate
     * updates are suppressed, so repeated NSD callbacks do not buzz the user again.
     */
    private fun postPairingNotification(
        context: Context,
        text: String,
        ongoing: Boolean,
        action: PairingNotificationAction,
    ) {
        val spec = PairingNotificationSpec(text, ongoing, action)
        synchronized(notificationLock) { pendingNotification = spec }
        postNotificationSpec(context, spec)
    }

    private fun postNotificationSpec(context: Context, spec: PairingNotificationSpec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val key = "${spec.text}|${spec.ongoing}|${spec.action}"
        val shouldPost = synchronized(notificationLock) {
            if (lastPostedNotificationKey == key) false
            else {
                lastPostedNotificationKey = key
                true
            }
        }
        if (!shouldPost) return

        val replyIntent = Intent(context, AdbPairingCodeReceiver::class.java).setAction(ACTION_PAIR_CODE)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel(context.getString(app.pillion.R.string.adb_code_or_port_label))
            .build()
        val replyAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_send,
            context.getString(app.pillion.R.string.adb_enter_code_action),
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()

        val contentIntent = openPillionPendingIntent(context)
        val openAction = Notification.Action.Builder(
            android.R.drawable.ic_menu_view,
            context.getString(app.pillion.R.string.adb_open_pillion_action),
            contentIntent,
        ).build()

        val builder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        })
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(context.getString(app.pillion.R.string.adb_setup_title))
            .setContentText(spec.text)
            .setStyle(Notification.BigTextStyle().bigText(spec.text))
            .setOngoing(spec.ongoing)
            .setAutoCancel(!spec.ongoing)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent)

        when (spec.action) {
            PairingNotificationAction.Reply -> builder.addAction(replyAction)
            PairingNotificationAction.Open -> builder.addAction(openAction)
            PairingNotificationAction.None -> Unit
        }

        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, builder.build())
    }

    private fun openPillionPendingIntent(context: Context): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
