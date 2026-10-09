package app.pillion.android

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.pillion.core.DashMarginColor
import app.pillion.core.DashResolution
import app.pillion.server.DashServer
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Owns the shell-side dash helper lifecycle. Starting the helper needs Wireless Debugging because
 * it runs as shell, but once started it serves frames over loopback and survives Wi-Fi loss.
 */
object DashHelper {
    const val DEFAULT_QUALITY = 40

    private const val TAG = "Pillion"
    private const val DASH_PROTOCOL_WIDTH = 480
    private const val DASH_PROTOCOL_HEIGHT = 234
    private const val CONNECT_TIMEOUT_MS = 250
    private const val START_TIMEOUT_MS = 5_000L
    private const val WATCHDOG_INTERVAL_MS = 3_000L
    private const val LOOPBACK_CONNECT_TRIES = 10
    private const val LOOPBACK_RETRY_MS = 300L
    // A freshly-spawned helper takes ~1-2s to create the display + bind its socket; don't respawn
    // (which pkills it) until it's had time to come up, or the watchdog thrashes in a respawn loop.
    private const val SPAWN_GRACE_MS = 8_000L

    @Volatile private var lastSpawnAt = 0L

    @Synchronized
    fun ensureRunning(
        context: Context,
        quality: Int,
        dashDpi: Int,
        dashResolution: DashResolution,
        leftMargin: Int = 0,
        bottomMargin: Int = 0,
        marginColor: DashMarginColor = DashMarginColor.Black,
        ocrEnabled: Boolean = false,
        ocrShowArea: Boolean = false,
        ocrLeft: Int = 30,
        ocrTop: Int = 89,
        ocrRight: Int = 70,
        ocrBottom: Int = 100,
        ocr2Left: Int = 88,
        ocr2Top: Int = 56,
        ocr2Right: Int = 98,
        ocr2Bottom: Int = 64,
        preferExisting: Boolean = false,
        onProgress: (String) -> Unit = {},
    ) {
        if (preferExisting && isRunning()) {
            Log.d(TAG, "dash: using existing helper")
            DiagnosticFlightRecorder.record("HELPER", "REUSE existing")
            progress(onProgress, "Using existing Pillion Helper")
            return
        }

        val appContext = context.applicationContext
        val adb = PillionAdb.getInstance(appContext)
        if (!ensureConnected(adb, appContext, onProgress)) {
            if (isRunning()) {
                Log.d(TAG, "dash: using existing helper; ADB unavailable")
                DiagnosticFlightRecorder.record("HELPER", "REUSE existing; ADB unavailable")
                progress(onProgress, "Using existing Pillion Helper; ADB unavailable")
                return
            }
            throw IllegalStateException(
                "Wireless debugging is not connected. Connect to Wi-Fi once and re-run dash setup " +
                    "before starting without Wi-Fi.",
            )
        }

        progress(onProgress, "Preparing shell privileges...")
        runCatching { adb.prepareDashPrivileges(appContext) }
            .onSuccess {
                Log.d(TAG, "dash: granted usage-stats appop through shell")
                progress(onProgress, "Shell privileges ready")
            }
            .onFailure {
                Log.w(TAG, "dash: could not grant usage-stats appop", it)
                progress(onProgress, "Privilege grant warning; continuing")
            }

        // Clear any stale helper so resolution/quality changes apply whenever ADB is reachable.
        progress(onProgress, "Stopping previous Pillion Helper...")
        runCatching { adb.runShell("pkill -f app.pillion.server.DashServer") }
        waitUntilStopped()
        progress(onProgress, "Starting Pillion Helper...")
        DiagnosticFlightRecorder.record("HELPER", "SPAWN_REQUEST ${dashResolution.width}x${dashResolution.height} dpi=$dashDpi")
        spawn(adb, appContext, quality, dashDpi, dashResolution, leftMargin, bottomMargin, marginColor, ocrEnabled, ocrShowArea, ocrLeft, ocrTop, ocrRight, ocrBottom, ocr2Left, ocr2Top, ocr2Right, ocr2Bottom)
        progress(onProgress, "Helper launch command sent")
        progress(onProgress, "Waiting for Pillion Helper...")
        check(waitUntilRunning()) { "Dash helper did not start" }
        DiagnosticFlightRecorder.record("HELPER", "READY")
        progress(onProgress, "Pillion Helper ready")
    }

    /**
     * Get a privileged ADB connection, **preferring loopback** — which works with no Wi-Fi once
     * [PillionAdb.enableTcpip] has run. First use bootstraps over Wireless debugging (needs Wi-Fi) and
     * upgrades to loopback so later reconnects, including offline respawns by the watchdog, succeed.
     */
    private fun ensureConnected(
        adb: PillionAdb,
        context: Context,
        onProgress: (String) -> Unit,
    ): Boolean {
        progress(onProgress, "Checking loopback ADB :5555...")
        if (runCatching { adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT) }.getOrDefault(false)) {
            Log.d(TAG, "dash: connected over loopback (offline-capable)")
            DiagnosticFlightRecorder.record("ADB", "LOOPBACK_CONNECTED")
            progress(onProgress, "Loopback ADB ready")
            return true
        }
        progress(onProgress, "Loopback ADB not available")
        progress(onProgress, "Connecting via Wireless ADB...")
        val wireless = runCatching { adb.autoConnectDevice(context, timeoutMs = 15_000) }
            .onFailure { Log.w(TAG, "dash: wireless auto-connect failed", it) }
            .getOrDefault(false)
        if (!wireless) {
            DiagnosticFlightRecorder.record("ADB", "WIRELESS_UNAVAILABLE")
            progress(onProgress, "Wireless ADB unavailable")
            return false
        }
        progress(onProgress, "Wireless ADB connected")
        DiagnosticFlightRecorder.record("ADB", "WIRELESS_CONNECTED")
        Log.d(TAG, "dash: connected over Wireless debugging; upgrading to loopback tcpip")
        progress(onProgress, "Enabling loopback ADB :5555...")
        runCatching { adb.enableTcpip() }.onFailure { Log.w(TAG, "dash: enableTcpip failed", it) }
        progress(onProgress, "Waiting for adbd restart...")
        // adbd is restarting on the new port; retry the loopback connect until it's back up.
        repeat(LOOPBACK_CONNECT_TRIES) {
            if (runCatching { adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT) }.getOrDefault(false)) {
                Log.i(TAG, "dash: upgraded to loopback adb — survives Wi-Fi loss")
                DiagnosticFlightRecorder.record("ADB", "LOOPBACK_UPGRADED")
                progress(onProgress, "Loopback ADB ready")
                return true
            }
            Thread.sleep(LOOPBACK_RETRY_MS)
        }
        // Upgrade failed (e.g. device blocks tcpip); fall back to wireless so we can still spawn now.
        Log.w(TAG, "dash: loopback upgrade failed; staying on Wireless debugging")
        progress(onProgress, "Loopback upgrade unavailable; retrying Wireless ADB...")
        val fallback = runCatching { adb.autoConnectDevice(context, timeoutMs = 5_000) }.getOrDefault(false)
        DiagnosticFlightRecorder.record("ADB", if (fallback) "WIRELESS_RECONNECTED" else "WIRELESS_RECONNECT_FAILED")
        progress(onProgress, if (fallback) "Wireless ADB reconnected" else "Wireless ADB reconnect failed")
        return fallback
    }

    private fun progress(onProgress: (String) -> Unit, message: String) {
        // Diagnostics must never be able to alter helper behavior.
        runCatching { onProgress(message) }
    }

    @Volatile private var watchdog: Thread? = null

    /**
     * While dashing, respawn the helper if it dies — e.g. adbd restarts when Wi-Fi/wireless-debugging
     * drops, killing the helper via adbd's cgroup. Recovery uses the loopback channel, so it works
     * offline as long as [PillionAdb.enableTcpip] succeeded earlier.
     */
    @Synchronized
    fun startWatchdog(
        context: Context,
        quality: Int,
        dashDpi: Int,
        dashResolution: DashResolution,
        leftMargin: Int,
        bottomMargin: Int,
        marginColor: DashMarginColor,
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
    ) {
        if (watchdog?.isAlive == true) return // exactly one watchdog, even across session restarts
        val appContext = context.applicationContext
        // Each thread checks `watchdog === this`: if it's no longer the designated watchdog (a new one
        // started, or stopWatchdog nulled it), it exits — so we never end up with two fighting.
        val thread = object : Thread("dash-watchdog") {
            override fun run() {
                while (watchdog === this && !isInterrupted) {
                    try {
                        sleep(WATCHDOG_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (watchdog !== this) break
                    if (DashHelper.isRunning()) continue
                    // A helper we just spawned is still coming up; don't pkill+respawn it mid-startup.
                    if (SystemClock.elapsedRealtime() - lastSpawnAt < SPAWN_GRACE_MS) continue
                    Log.w(TAG, "dash: helper down — attempting respawn over loopback")
                    DiagnosticFlightRecorder.record("HELPER", "DOWN watchdog detected")
                    runCatching { ensureRunning(appContext, quality, dashDpi, dashResolution, leftMargin, bottomMargin, marginColor, ocrEnabled, ocrShowArea, ocrLeft, ocrTop, ocrRight, ocrBottom, ocr2Left, ocr2Top, ocr2Right, ocr2Bottom) }
                        .onSuccess {
                            Log.i(TAG, "dash: helper respawned")
                            DiagnosticFlightRecorder.record("HELPER", "RESPAWNED")
                        }
                        .onFailure {
                            Log.w(TAG, "dash: respawn failed (offline / adb gone): ${it.message}")
                            DiagnosticFlightRecorder.record("HELPER", "RESPAWN_FAILED ${it.javaClass.simpleName}: ${it.message ?: "-"}")
                        }
                }
            }
        }.apply { isDaemon = true }
        watchdog = thread
        thread.start()
        Log.d(TAG, "dash: watchdog started")
    }

    @Synchronized
    fun stopWatchdog() {
        watchdog?.interrupt()
        watchdog = null
    }

    fun isRunning(): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DashServer.PORT), CONNECT_TIMEOUT_MS)
            }
        }.isSuccess

    /** Demote an already-running helper without requiring ADB or the frame-stream socket. */
    fun demoteExisting(): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DashServer.PORT), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().write("DEMOTE\n".encodeToByteArray())
                socket.getOutputStream().flush()
            }
            DiagnosticFlightRecorder.record("HELPER", "DEMOTE direct")
            true
        }.getOrElse {
            Log.d(TAG, "dash: no helper available for direct demote")
            false
        }

    private const val RESTART_HELPER_PORT = 28118
    private const val RESTART_HELPER_READY_TIMEOUT_MS = 3_000L
    private const val RESTART_HELPER_SOCKET_TIMEOUT_MS = 1_000

    /**
     * Hand the final Pillion process restart to a shell-UID helper so it survives the app's own
     * force-stop. Prefer the already-running DashServer helper. If it is absent, use ADB only to
     * launch the tiny RestartHelper, wait for READY, then send RESTART_PILLION to that helper.
     */
    fun schedulePillionRestart(context: Context): Boolean {
        DiagnosticFlightRecorder.record("APP", "RESTART_REQUEST")

        // Normal case: the existing DashServer helper already has the proven restart implementation.
        if (scheduleRestartThroughHelper()) {
            DiagnosticFlightRecorder.record("APP", "RESTART_SCHEDULED helper")
            return true
        }

        // If a restart-only helper is already alive from a previous attempt, reuse it rather than
        // opening another ADB shell.
        if (sendRestartHelperCommand("RESTART_PILLION", "OK")) {
            DiagnosticFlightRecorder.record("APP", "RESTART_SCHEDULED restart-helper-reuse")
            return true
        }

        // A stale DashServer from an older APK may not understand RESTART_PILLION. QUIT has existed
        // for a long time, so retire it before starting the dedicated restart helper.
        if (isRunning()) {
            shutdownExisting()
            val stopDeadline = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < stopDeadline && isRunning()) {
                Thread.sleep(50L)
            }
        }

        val appContext = context.applicationContext
        val adb = PillionAdb.getInstance(appContext)
        val connected = runCatching {
            adb.connectDevice(PillionAdb.LOOPBACK_HOST, PillionAdb.TCPIP_PORT) ||
                adb.autoConnectDevice(appContext, timeoutMs = 5_000)
        }.getOrDefault(false)
        if (!connected) {
            Log.w(TAG, "app restart: helper absent and ADB unavailable")
            DiagnosticFlightRecorder.record("APP", "RESTART_FAILED no helper/adb")
            return false
        }

        DiagnosticFlightRecorder.record("APP", "RESTART_HELPER_START")
        if (!launchRestartHelper(adb, appContext.packageName)) {
            Log.w(TAG, "app restart: restart helper launch failed")
            DiagnosticFlightRecorder.record("APP", "RESTART_FAILED restart-helper launch")
            return false
        }

        val readyDeadline = SystemClock.elapsedRealtime() + RESTART_HELPER_READY_TIMEOUT_MS
        var ready = false
        while (SystemClock.elapsedRealtime() < readyDeadline) {
            if (sendRestartHelperCommand("PING", "READY")) {
                ready = true
                break
            }
            Thread.sleep(50L)
        }
        if (!ready) {
            Log.w(TAG, "app restart: restart helper did not become ready")
            DiagnosticFlightRecorder.record("APP", "RESTART_FAILED restart-helper ready timeout")
            return false
        }
        DiagnosticFlightRecorder.record("APP", "RESTART_HELPER_READY")

        if (!sendRestartHelperCommand("RESTART_PILLION", "OK")) {
            Log.w(TAG, "app restart: restart helper rejected/lost restart command")
            DiagnosticFlightRecorder.record("APP", "RESTART_FAILED restart-helper command")
            return false
        }

        Log.i(TAG, "app restart: scheduled through restart helper")
        DiagnosticFlightRecorder.record("APP", "RESTART_SCHEDULED restart-helper")
        return true
    }

    private fun scheduleRestartThroughHelper(): Boolean {
        if (!isRunning()) return false
        val sent = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DashServer.PORT), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().write("RESTART_PILLION\n".encodeToByteArray())
                socket.getOutputStream().flush()
            }
            true
        }.getOrElse {
            Log.w(TAG, "app restart: could not send restart command to helper", it)
            false
        }
        if (!sent) return false

        // The helper exits immediately after spawning the detached restart shell. Seeing its loopback
        // port disappear confirms the command was accepted before we let the app wait for force-stop.
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isRunning()) return true
            Thread.sleep(50L)
        }
        return !isRunning()
    }

    private fun launchRestartHelper(adb: PillionAdb, packageName: String): Boolean {
        return runCatching {
            // This is intentionally the same proven detach shape used to launch DashServer. The
            // long-lived process is app_process itself; unlike the old fallback, no detached shell
            // has to survive while sleeping before it executes am force-stop.
            val command =
                "setsid sh -c 'CLASSPATH=\$(pm path $packageName | grep base.apk | cut -d: -f2):\$SYSTEMSERVERCLASSPATH " +
                    "app_process / app.pillion.server.RestartHelper </dev/null >/dev/null 2>&1 &'"
            val stream = adb.openShellStream(command)
            runCatching { stream.openInputStream().readBytes() }
                .onFailure { t ->
                    if (t.message?.contains("Stream closed", ignoreCase = true) != true) throw t
                }
            runCatching { stream.close() }
            true
        }.getOrElse { t ->
            Log.w(TAG, "app restart: restart helper ADB launch failed", t)
            false
        }
    }

    private fun sendRestartHelperCommand(command: String, expectedReply: String): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", RESTART_HELPER_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = RESTART_HELPER_SOCKET_TIMEOUT_MS
                socket.getOutputStream().write("$command\n".encodeToByteArray())
                socket.getOutputStream().flush()
                socket.getInputStream().bufferedReader().readLine() == expectedReply
            }
        }.getOrDefault(false)
    }

    /** Stop a detached helper over loopback so restart-required layout settings cannot reuse it. */
    fun shutdownExisting() {
        DiagnosticFlightRecorder.record("HELPER", "SHUTDOWN_REQUEST")
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DashServer.PORT), CONNECT_TIMEOUT_MS)
                socket.getOutputStream().write("QUIT\n".encodeToByteArray())
                socket.getOutputStream().flush()
            }
        }.onFailure { Log.d(TAG, "dash: no helper to stop before app restart") }
    }

    private fun spawn(
        adb: PillionAdb,
        context: Context,
        quality: Int,
        dashDpi: Int,
        dashResolution: DashResolution,
        leftMargin: Int,
        bottomMargin: Int,
        marginColor: DashMarginColor,
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
    ) {
        Log.d(
            TAG,
            "dash: spawning helper virtual=${dashResolution.width}x${dashResolution.height} dpi=$dashDpi " +
                "output=${DASH_PROTOCOL_WIDTH}x$DASH_PROTOCOL_HEIGHT " +
                "margins=${leftMargin}px/${bottomMargin}px color=${marginColor.label} ocr=$ocrEnabled area1=$ocrLeft,$ocrTop-$ocrRight,$ocrBottom area2=$ocr2Left,$ocr2Top-$ocr2Right,$ocr2Bottom",
        )
        // Detach the helper so it survives ADB disconnect AND Wi-Fi loss:
        // 1. setsid gives it a new session/process group, outside adbd's teardown group.
        // 2. the shell backgrounds app_process and exits, so the helper reparents to init.
        //
        // Include SYSTEMSERVERCLASSPATH so the helper can load com.android.server.display.DisplayControl
        // on its main classloader for physical-panel power control.
        val inner = "CLASSPATH=\$(pm path ${context.packageName} | grep base.apk | cut -d: -f2):\$SYSTEMSERVERCLASSPATH " +
            "app_process / app.pillion.server.DashServer " +
            "${dashResolution.width} ${dashResolution.height} $dashDpi $quality " +
            "$DASH_PROTOCOL_WIDTH $DASH_PROTOCOL_HEIGHT " +
            "$leftMargin $bottomMargin ${marginColor.rgb} " +
            "OCR2 ${if (ocrEnabled) 1 else 0} ${if (ocrShowArea) 1 else 0} " +
            "${ocrLeft.coerceIn(20, 80)} ${ocrTop.coerceIn(80, 100)} " +
            "${ocrRight.coerceIn(20, 80)} ${ocrBottom.coerceIn(80, 100)} " +
            "${ocr2Left.coerceIn(0, 100)} ${ocr2Top.coerceIn(0, 100)} " +
            "${ocr2Right.coerceIn(0, 100)} ${ocr2Bottom.coerceIn(0, 100)} " +
            "</dev/null >/dev/null 2>&1 &"
        val stream = adb.openShellStream("setsid sh -c '$inner'")
        runCatching { stream.openInputStream().readBytes() }
            .onFailure { t ->
                if (t.message?.contains("Stream closed", ignoreCase = true) != true) throw t
            }
        runCatching { stream.close() }
        lastSpawnAt = SystemClock.elapsedRealtime()
        Log.d(TAG, "dash: helper spawned (detached to init)")
    }

    private fun waitUntilRunning(): Boolean {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) return true
            Thread.sleep(100)
        }
        return isRunning()
    }

    private fun waitUntilStopped() {
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && isRunning()) {
            Thread.sleep(100)
        }
    }
}
