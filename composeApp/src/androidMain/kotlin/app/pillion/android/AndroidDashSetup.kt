package app.pillion.android

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import app.pillion.core.DashResolution
import app.pillion.core.DashScale
import app.pillion.core.DashSetup
import app.pillion.core.DashStage
import app.pillion.core.DashState
import app.pillion.core.SetupTraceLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android [DashSetup]: the one-time pair/connect bootstrap only. Normal casting/reconnect behavior
 * lives in CaptureService/DashHelper and is intentionally not changed by the onboarding flow.
 */
class AndroidDashSetup(
    context: Context,
    private val requestNotificationPermission: () -> Unit = {},
) : DashSetup {
    private val context = context.applicationContext
    private val settings = AndroidSettingsStore(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DashState())
    override val state: StateFlow<DashState> = _state.asStateFlow()
    @Volatile private var helperPrepared = false

    // Setup trace is deliberately ephemeral. It exists only in RAM for the current onboarding
    // session, never contains the PIN, and is surfaced directly in the step-2 UI for screenshots.
    private val traceLock = Any()
    private val traceLines = ArrayList<SetupTraceLine>()
    private var traceStartedAtMs: Long? = null
    private var lastPairingState = AdbPairingState()

    companion object {
        // One-time setup only. Normal DashHelper watchdog/reconnect behavior is unchanged.
        private const val HELPER_PREPARE_TIMEOUT_MS = 20_000L
        private const val MAX_TRACE_LINES = 80
    }

    init {
        scope.launch {
            AdbPairingCoordinator.state.collect { pairingState ->
                recordCoordinatorProgress(lastPairingState, pairingState)
                lastPairingState = pairingState

                _state.value = DashState(
                    stage = pairingState.stage,
                    message = pairingState.message,
                    canRetrySetup = pairingState.canRetrySetup,
                    setupTrace = traceSnapshot(),
                )

                // Raw ADB success is not setup success. Prepare the helper first, and expose Connected
                // to Compose only after the helper is actually reachable.
                if (pairingState.stage == DashStage.Connecting && pairingState.adbConnected && !helperPrepared) {
                    helperPrepared = true
                    prepareHelper()
                }
            }
        }
    }

    override fun startPairingAssistant() {
        helperPrepared = false
        resetTrace()
        requestNotificationPermission()
        AdbPairingCoordinator.start(context)
    }

    override fun cancelPairingAssistant() {
        AdbPairingCoordinator.stop(context)
    }

    override fun openWirelessDebuggingSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun reopenWirelessDebuggingSettings() {
        requestNotificationPermission()
        AdbPairingCoordinator.resumeForSettings(context)
        openWirelessDebuggingSettings()
    }

    // Retained as a programmatic/dev fallback. The normal onboarding accepts PIN only from the
    // notification RemoteInput so there is one user-facing input path.
    override fun pair(code: String) {
        AdbPairingCoordinator.pairWithDiscoveredEndpoint(context, code)
    }

    override fun pair(host: String, pairingPort: Int, code: String) {
        AdbPairingCoordinator.pairExplicit(context, AdbPairingEndpoint(host, pairingPort), code)
    }

    /** Retry after pairing succeeded; this never asks for a new PIN or restarts pairing discovery. */
    override fun connect() {
        helperPrepared = false
        ensureTraceStarted()
        appendTrace("----- Retry setup -----")
        AdbPairingCoordinator.connect(context)
    }

    private suspend fun prepareHelper() {
        val preparing = context.getString(app.pillion.R.string.adb_preparing_pillion)
        _state.value = DashState(
            stage = DashStage.Connecting,
            message = preparing,
            canRetrySetup = true,
            setupTrace = traceSnapshot(),
        )
        AdbPairingCoordinator.markPreparing(context)

        runCatching {
            val scale = DashScale.fromTenths(settings.dashScaleTenths())
            val left = settings.dashLeftMargin()
            val bottom = settings.dashBottomMargin()
            val resolution = DashResolution.forLayout(left, bottom, scale.tenths)
            val completed = withTimeoutOrNull(HELPER_PREPARE_TIMEOUT_MS) {
                runInterruptible {
                    DashHelper.ensureRunning(
                        context = context,
                        quality = settings.imageQuality(),
                        dashDpi = settings.dashDpi(),
                        dashResolution = resolution,
                        leftMargin = left,
                        bottomMargin = bottom,
                        marginColor = settings.dashMarginColor(),
                        ocrEnabled = settings.ocrEnabled(),
                        ocrShowArea = settings.ocrShowArea(),
                        ocrLeft = settings.ocrLeftPercent(),
                        ocrTop = settings.ocrTopPercent(),
                        ocrRight = settings.ocrRightPercent(),
                        ocrBottom = settings.ocrBottomPercent(),
                        ocr2Left = settings.ocr2LeftPercent(),
                        ocr2Top = settings.ocr2TopPercent(),
                        ocr2Right = settings.ocr2RightPercent(),
                        ocr2Bottom = settings.ocr2BottomPercent(),
                        onProgress = ::appendTrace,
                    )
                }
                true
            }
            check(completed == true) { context.getString(app.pillion.R.string.adb_preparing_timed_out) }
        }.onSuccess {
            val ready = context.getString(app.pillion.R.string.adb_setup_complete)
            _state.value = DashState(
                stage = DashStage.Connected,
                message = ready,
                setupTrace = traceSnapshot(),
            )
            AdbPairingCoordinator.markSetupComplete(context)
        }.onFailure { t ->
            helperPrepared = false
            val message = t.message ?: context.getString(app.pillion.R.string.adb_setup_failed)
            appendTrace("ERROR: $message")
            _state.value = DashState(
                stage = DashStage.Error,
                message = message,
                canRetrySetup = true,
                setupTrace = traceSnapshot(),
            )
            AdbPairingCoordinator.markSetupFailed(context, message)
        }
    }

    private fun recordCoordinatorProgress(previous: AdbPairingState, current: AdbPairingState) {
        if (current.stage == DashStage.Pairing && previous.stage != DashStage.Pairing) {
            if (traceSnapshot().isNotEmpty()) {
                ensureTraceStarted()
                appendTrace("----- New pairing attempt -----")
            } else {
                ensureTraceStarted()
            }
            val port = current.endpoint?.port
            appendTrace(if (port != null) "Pairing with Android on port $port..." else "Pairing with Android...")
        }

        if (previous.stage == DashStage.Pairing && current.stage == DashStage.Connecting) {
            appendTrace("Pairing OK")
            appendTrace("Connecting to ADB...")
        } else if (
            current.stage == DashStage.Connecting &&
            !current.adbConnected &&
            previous.stage != DashStage.Connecting
        ) {
            ensureTraceStarted()
            appendTrace("Connecting to ADB...")
        }

        if (current.stage == DashStage.Connecting && current.adbConnected && !previous.adbConnected) {
            ensureTraceStarted()
            appendTrace("ADB connected")
        }

        if (current.stage == DashStage.Error && previous.stage != DashStage.Error) {
            when {
                previous.stage == DashStage.Pairing -> {
                    ensureTraceStarted()
                    appendTrace("Pairing failed: ${current.message ?: "unknown error"}")
                }

                previous.stage == DashStage.Connecting && !current.adbConnected -> {
                    ensureTraceStarted()
                    appendTrace("ADB connection failed: ${current.message ?: "unknown error"}")
                }
            }
        }
    }

    private fun resetTrace() {
        synchronized(traceLock) {
            traceStartedAtMs = null
            traceLines.clear()
        }
        _state.value = _state.value.copy(setupTrace = emptyList())
    }

    private fun ensureTraceStarted() {
        synchronized(traceLock) {
            if (traceStartedAtMs == null) traceStartedAtMs = SystemClock.elapsedRealtime()
        }
    }

    private fun appendTrace(text: String) {
        val snapshot = synchronized(traceLock) {
            val start = traceStartedAtMs ?: SystemClock.elapsedRealtime().also { traceStartedAtMs = it }
            if (traceLines.lastOrNull()?.text == text) return@synchronized traceLines.toList()
            traceLines += SetupTraceLine(
                elapsedMs = (SystemClock.elapsedRealtime() - start).coerceAtLeast(0L),
                text = text,
            )
            while (traceLines.size > MAX_TRACE_LINES) traceLines.removeAt(0)
            traceLines.toList()
        }
        _state.value = _state.value.copy(setupTrace = snapshot)
    }

    private fun traceSnapshot(): List<SetupTraceLine> = synchronized(traceLock) { traceLines.toList() }
}
