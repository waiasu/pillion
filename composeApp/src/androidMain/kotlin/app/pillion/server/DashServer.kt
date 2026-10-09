package app.pillion.server

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.Paint
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Privileged helper for the dedicated-dash feature, run as the **shell uid** via `app_process`
 * (spawned through Pillion's in-app ADB bootstrap). Because it runs as shell it can create a
 * **trusted** virtual display — the one thing a normal app uid cannot do — launch a real app onto
 * it, capture it, and stream it back to the app, so the dash keeps rendering with the phone screen
 * off.
 *
 * Pipeline: trusted VirtualDisplay -> ImageReader -> JPEG -> the original v17 loopback frame stream
 * (`[4-byte frame length][frame]`) on [PORT]. Optional OCR is deliberately isolated on [OCR_PORT]
 * and remains inactive until the app explicitly sends OCR_START after Bluetooth IMAGE_ACKs have been
 * stable for a grace period. We use loopback TCP rather than:
 *   - a unix LocalSocket: SELinux forbids untrusted_app -> shell (`avc: denied { connectto } ...
 *     tclass=unix_stream_socket`); TCP isn't subject to that domain rule.
 *   - the ADB exec stdout stream: that dies with the ADB/Wi-Fi connection, but there is no Wi-Fi on
 *     a moving bike. Loopback is always up, so once spawned (detached, via `nohup`) the helper keeps
 *     serving frames with no network at all.
 *
 * Status/diagnostics go to **logcat** (tag [TAG]).
 *
 * Launch detached so it outlives the spawning ADB connection:
 *   CLASSPATH=<base.apk> nohup app_process / app.pillion.server.DashServer \
 *     <virtual-w> <virtual-h> <dpi> <quality> <output-w> <output-h> <component> &
 */
object DashServer {

    private const val TAG = "PillionDash"
    const val PORT = 28117 // v17 main frame protocol: [length][JPEG], intentionally unchanged
    const val OCR_PORT = 28119 // isolated raw-VD OCR crop side-channel

    // Public flags exist on DisplayManager; the trusted/own-group/always-unlocked ones are @hide,
    // so use their raw bit values (matched exactly to scrcpy's NewDisplayCapture on Android 14+).
    private const val FLAG_PUBLIC = 1 shl 0
    private const val FLAG_PRESENTATION = 1 shl 1
    private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_TRUSTED = 1 shl 10
    private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val FLAG_OWN_FOCUS = 1 shl 14
    // The key flag (Android 14+): puts the display in a power group that survives the phone screen
    // turning off — without it the virtual display lands in display group 0 and dies with the panel.
    private const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    private const val FLAGS = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or
        FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or
        FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or
        FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED or
        FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP

    private const val MIN_INTERVAL_MS = 50L // cap capture/encode to ~20fps; the app paces sends
    private const val OCR_AREA1_INTERVAL_MS = 10_000L
    private const val OCR_AREA2_INTERVAL_MS = 1_000L
    private const val OCR2_PAYLOAD_MAGIC = 0x4F435232 // "OCR2"
    private const val OCR_JPEG_QUALITY = 95

    private var virtualWidth = 480
    private var virtualHeight = 240
    private var outputWidth = 480
    private var outputHeight = 234
    private var quality = 40
    private var leftMargin = 0
    private var bottomMargin = 0
    private var marginRgb = 0x000000
    private var ocrEnabled = false
    private var ocrShowArea = false
    private var ocrLeftPercent = 30
    private var ocrTopPercent = 89
    private var ocrRightPercent = 70
    private var ocrBottomPercent = 100
    private var ocr2LeftPercent = 88
    private var ocr2TopPercent = 56
    private var ocr2RightPercent = 98
    private var ocr2BottomPercent = 64
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ocrAreaOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val ocrAreaInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private var lastEncodeMs = 0L
    private var lastOcrArea1CropMs = 0L
    private var lastOcrArea2CropMs = 0L
    @Volatile private var latestOcrArea1Jpeg: ByteArray? = null

    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var latestSeq = 0L
    @Volatile private var latestOcrJpeg: ByteArray? = null
    @Volatile private var latestOcrSeq = 0L
    @Volatile private var ocrActive = false
    @Volatile private var ocrServerStarted = false

    // Battery: encode only while a foreground app is promoted (phone locked). Idle otherwise.
    @Volatile private var capturing = false
    @Volatile private var restartPerformedForCurrentPromotion = false
    @Volatile private var restartFrameGate = false
    @Volatile private var displayId = -1
    @Volatile private var dashDisplay: VirtualDisplay? = null
    @Volatile private var lastComponent: String? = null
    @Volatile private var keepAlive: Thread? = null
    @Volatile private var panelOffRetry: Thread? = null
    @Volatile private var secureCameraWatcher: Thread? = null
    @Volatile private var secureCameraPanelRestore: Thread? = null


    private const val KEEP_ALIVE_MS = 3000L // poke the dash display group well under its ~10s idle timeout
    private val PANEL_OFF_RETRY_DELAYS_MS = longArrayOf(700L, 1500L)
    private const val SECURE_CAMERA_POLL_MS = 250L
    private const val SECURE_CAMERA_SETTLE_MS = 500L
    private const val GOOGLE_CAMERA_PACKAGE = "com.google.android.GoogleCamera"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()

        virtualWidth = args.getOrNull(0)?.toIntOrNull() ?: 480
        virtualHeight = args.getOrNull(1)?.toIntOrNull() ?: 240
        val dpi = args.getOrNull(2)?.toIntOrNull() ?: 160
        quality = args.getOrNull(3)?.toIntOrNull() ?: 40
        val outputArg = args.getOrNull(4)?.toIntOrNull()
        outputWidth = outputArg ?: 480
        outputHeight = args.getOrNull(5)?.toIntOrNull()?.takeIf { outputArg != null } ?: 234

        // New format after output size: <left-margin> <bottom-margin> <margin-rgb> [component].
        // Keep accepting the older format where the component immediately followed output height.
        val layoutArgsPresent = outputArg != null && args.getOrNull(6)?.toIntOrNull() != null
        if (layoutArgsPresent) {
            leftMargin = args.getOrNull(6)?.toIntOrNull()?.coerceIn(0, outputWidth - 1) ?: 0
            bottomMargin = args.getOrNull(7)?.toIntOrNull()?.coerceIn(0, outputHeight - 1) ?: 0
            marginRgb = args.getOrNull(8)?.toIntOrNull()?.and(0x00FFFFFF) ?: 0x000000
        }
        val ocrArgsPresent = layoutArgsPresent && (args.getOrNull(9) == "OCR1" || args.getOrNull(9) == "OCR2")
        if (ocrArgsPresent) {
            ocrEnabled = args.getOrNull(10)?.toIntOrNull() == 1
            ocrShowArea = args.getOrNull(11)?.toIntOrNull() == 1
            ocrLeftPercent = args.getOrNull(12)?.toIntOrNull()?.coerceIn(20, 80) ?: 30
            ocrTopPercent = args.getOrNull(13)?.toIntOrNull()?.coerceIn(80, 100) ?: 89
            ocrRightPercent = args.getOrNull(14)?.toIntOrNull()?.coerceIn(20, 80) ?: 70
            ocrBottomPercent = args.getOrNull(15)?.toIntOrNull()?.coerceIn(80, 100) ?: 100
            if (args.getOrNull(9) == "OCR2") {
                ocr2LeftPercent = args.getOrNull(16)?.toIntOrNull()?.coerceIn(0, 100) ?: 88
                ocr2TopPercent = args.getOrNull(17)?.toIntOrNull()?.coerceIn(0, 100) ?: 56
                ocr2RightPercent = args.getOrNull(18)?.toIntOrNull()?.coerceIn(0, 100) ?: 98
                ocr2BottomPercent = args.getOrNull(19)?.toIntOrNull()?.coerceIn(0, 100) ?: 64
            }
        }
        val launchComponent = when {
            outputArg == null -> args.getOrNull(4)
            args.getOrNull(9) == "OCR2" -> args.getOrNull(20)
            ocrArgsPresent -> args.getOrNull(16)
            layoutArgsPresent -> args.getOrNull(9)
            else -> args.getOrNull(6)
        }
        // launchComponent example: com.waze/com.waze.FreeMapAppActivity

        try {
            val context = ShellContext(systemContext())
            val captureThread = HandlerThread("pillion-capture").apply { start() }
            val handler = Handler(captureThread.looper)

            val reader = ImageReader.newInstance(virtualWidth, virtualHeight, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ ir -> onImage(ir) }, handler)

            val display = createTrustedVirtualDisplay(
                context,
                "pillion-dash",
                virtualWidth,
                virtualHeight,
                dpi,
                reader.surface,
            )
            dashDisplay = display
            displayId = display.display.displayId
            startSecureCameraWatcher()
            Log.i(
                TAG,
                "trusted display created id=$displayId virtual=${virtualWidth}x$virtualHeight " +
                    "output=${outputWidth}x$outputHeight dpi=$dpi " +
                    "margins=${leftMargin}px/${bottomMargin}px rgb=$marginRgb " +
                    "ocr=$ocrEnabled show=$ocrShowArea area1=$ocrLeftPercent,$ocrTopPercent-$ocrRightPercent,$ocrBottomPercent area2=$ocr2LeftPercent,$ocr2TopPercent-$ocr2RightPercent,$ocr2BottomPercent",
            )

            // The display starts empty (idle, no encoding). The app sends PROMOTE on screen-off and
            // DEMOTE on unlock over the socket. An optional arg promotes immediately (dev/testing).
            if (launchComponent != null) promoteApp(launchComponent)
            startTcpServer()
            Log.i(TAG, "ready, serving v17 frames on 127.0.0.1:$PORT" +
                if (ocrEnabled) "; OCR available but not started" else "")
        } catch (t: Throwable) {
            Log.e(TAG, "fatal", t)
            return
        }
        Looper.loop()
    }

    /**
     * Encode the newest frame to JPEG (throttled) while promoted. When idle ([capturing]=false) we
     * drain frames without encoding, so an unlocked phone (mirroring instead) costs no extra battery.
     */
    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            if (!capturing || restartFrameGate) return
            val now = System.currentTimeMillis()
            if (now - lastEncodeMs < MIN_INTERVAL_MS) return
            lastEncodeMs = now
            val result = toJpegTimed(image, now)
            latestJpeg = result.bytes
            latestSeq++
            result.ocrBytes?.let { crop ->
                latestOcrJpeg = crop
                latestOcrSeq++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "encode drop: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            image.close()
        }
    }

    private fun startTcpServer() {
        val server = ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"))
        Thread {
            // Keep accepting forever, one thread per client. The phone (esp. MIUI) can tear down the
            // app's loopback socket on screen-off; the app then reconnects, so we must stay ready to
            // accept the new connection rather than blocking inside a single client's serve loop.
            while (true) {
                val client = runCatching { server.accept() }.getOrNull()
                if (client == null) { Thread.sleep(50); continue }
                Thread { serveClient(client) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = false; start() }
    }

    private fun serveClient(client: Socket) {
        val clientId = "${client.inetAddress.hostAddress}:${client.port}"
        Log.i(TAG, "client connected: $clientId")
        // Reverse channel: PROMOTE/DEMOTE/TAP plus OCR_START/STOP commands. The writer remains the
        // exact v17 byte stream: [4-byte length][JPEG], with no OCR framing added here.
        Thread { readCommands(client) }.apply { isDaemon = true; start() }
        var sentSeq = -1L
        try {
            client.tcpNoDelay = true
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            while (!client.isClosed) {
                val seq = latestSeq
                val frame = latestJpeg
                if (frame != null && seq != sentSeq) {
                    out.writeInt(frame.size)
                    out.write(frame)
                    out.flush()
                    sentSeq = seq
                } else {
                    Thread.sleep(10)
                }
            }
        } catch (e: Throwable) {
            Log.i(TAG, "client disconnected: $clientId ${e.javaClass.simpleName}: ${e.message} sentSeq=$sentSeq latestSeq=$latestSeq")
        } finally {
            runCatching { client.close() }
        }
    }

    @Synchronized
    private fun ensureOcrTcpServerStarted(): Boolean {
        if (ocrServerStarted) return true
        return runCatching {
            val server = ServerSocket(OCR_PORT, 2, InetAddress.getByName("127.0.0.1"))
            Thread {
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull()
                    if (client == null) { Thread.sleep(50); continue }
                    Thread { serveOcrClient(client) }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
            ocrServerStarted = true
            Log.i(TAG, "OCR side-channel started on 127.0.0.1:$OCR_PORT")
            true
        }.getOrElse { t ->
            Log.w(TAG, "OCR side-channel start failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** OCR is output-only and completely separate from the proven main frame socket. */
    private fun serveOcrClient(client: Socket) {
        val clientId = "${client.inetAddress.hostAddress}:${client.port}"
        Log.i(TAG, "OCR client connected: $clientId")
        var sentSeq = -1L
        var lastSentMs = 0L
        try {
            client.tcpNoDelay = true
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            while (!client.isClosed) {
                val active = ocrEnabled && ocrActive
                val frame = latestOcrJpeg
                val seq = latestOcrSeq
                val now = System.currentTimeMillis()
                // New crops send immediately. Re-send the cached two-area sample once per second so
                // Area 2 keeps its normal one-second cadence even when Android stops queueing new
                // buffers for visually static content. Area 1 recognition is cached in the app side.
                val due = active && frame != null && (seq != sentSeq || now - lastSentMs >= OCR_AREA2_INTERVAL_MS)
                if (due) {
                    val payload = frame ?: continue
                    out.writeInt(payload.size)
                    out.write(payload)
                    out.flush()
                    sentSeq = seq
                    lastSentMs = now
                } else {
                    Thread.sleep(20)
                }
            }
        } catch (e: Throwable) {
            Log.i(TAG, "OCR client disconnected: $clientId ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readCommands(client: Socket) {
        try {
            val input = client.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine()?.trim() ?: break
                when {
                    line.startsWith("PROMOTE ") -> {
                        val fields = line.removePrefix("PROMOTE ").trim().split(' ')
                        promoteApp(fields.firstOrNull().orEmpty(), fields.getOrNull(1) == "1")
                    }
                    line == "DEMOTE" -> demoteApp()
                    line == "OCR_START" -> setOcrActive(true)
                    line == "OCR_STOP" -> setOcrActive(false)
                    line.startsWith("TAP_PERCENT ") -> {
                        val parts = line.split(' ')
                        val xPercent = parts.getOrNull(1)?.toIntOrNull()
                        val yPercent = parts.getOrNull(2)?.toIntOrNull()
                        val label = parts.getOrNull(3) ?: "POINT"
                        if (xPercent == null || yPercent == null) {
                            Log.w(TAG, "bad TAP_PERCENT command: $line")
                        } else {
                            tapDashPercent(xPercent, yPercent, label)
                        }
                    }
                    line == "RESTART_PILLION" -> schedulePillionRestart()
                    line == "QUIT" -> shutdown()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "command reader failed: ${t.javaClass.simpleName}: ${t.message}")
            // fall through to close
        } finally {
            // Closing wakes the writer loop (it checks isClosed) so a broken client's thread ends
            // instead of sleeping forever.
            runCatching { client.close() }
        }
    }

    private fun setOcrActive(active: Boolean) {
        if (!ocrEnabled) return
        val requested = active && capturing
        if (ocrActive == requested) return
        if (requested && !ensureOcrTcpServerStarted()) {
            Log.w(TAG, "OCR enable ignored because side-channel could not start")
            return
        }
        ocrActive = requested
        lastOcrArea1CropMs = 0L
        lastOcrArea2CropMs = 0L
        latestOcrJpeg = null
        latestOcrArea1Jpeg = null
        latestOcrSeq++
        Log.i(TAG, "OCR ${if (requested) "enabled" else "disabled"} in helper")
    }

    /**
     * Tap the current virtual display at a percentage-based position. Percentages are relative to
     * the VD content itself, so changing the VD scale/DPI does not change the logical target.
     * 0% maps to the first pixel and 100% to the last pixel on each axis.
     */
    private fun tapDashPercent(xPercent: Int, yPercent: Int, label: String) {
        val targetDisplay = displayId
        if (targetDisplay < 0) {
            Log.w(TAG, "dash tap $label: no dash display")
            return
        }

        val x = percentToIndex(virtualWidth, xPercent)
        val y = percentToIndex(virtualHeight, yPercent)

        // Known-good path: keep this call shape identical to the successful center-tap build.
        exec(
            "input", "-d", targetDisplay.toString(),
            "tap", x.toString(), y.toString(),
        )
    }

    private fun percentToIndex(size: Int, percent: Int): Int {
        if (size <= 1) return 0
        return (((size - 1).toLong() * percent.coerceIn(0, 100) + 50L) / 100L).toInt()
    }

    /** Move the foreground app onto the dash display and start encoding (phone just locked). */
    private fun promoteApp(component: String, restartOnVd: Boolean = false) {
        if (displayId < 0 || component.isEmpty()) return
        // Every DASH promotion starts by resetting only the optional OCR side. OCR can only be
        // re-enabled later by OCR_START after the app sees a real IMAGE_ACK and its five-second grace.
        ocrActive = false
        latestOcrJpeg = null
        latestOcrArea1Jpeg = null
        lastOcrArea1CropMs = 0L
        lastOcrArea2CropMs = 0L

        if (!restartOnVd || component.substringBefore('/') == "app.pillion") {
            // IMPORTANT: Restart OFF stays on the exact proven v24 promotion path. Do not fold this
            // into the restart path: the normal move-stack behaviour is intentionally preserved.
            relocateApp(component, displayId)
            lastComponent = component
            capturing = true
            startHeartbeat()
            setMainDisplayPower(false)
            startPanelOffRetries()
            Log.i(TAG, "promoted $component to display $displayId")
            return
        }

        // Restart ON is deliberately a separate path. Do NOT first move the live task to the VD.
        // Remove it while it is still on the phone, let Activity/native renderer cleanup finish,
        // then create the replacement Activity natively on the dash display.
        lastComponent = component
        capturing = true
        startHeartbeat()
        setMainDisplayPower(false)
        startPanelOffRetries()
        restartPerformedForCurrentPromotion = true
        // Do not publish VD frames from the empty/restarting display. The first frame released after
        // the new task is confirmed is therefore a fresh post-launch DASH frame.
        restartFrameGate = true
        latestJpeg = null
        latestSeq++
        Log.i(TAG, "restart-on-vd: preparing $component for fresh launch on display $displayId")
        restartAppBeforeVdLaunch(component)
    }

    /** Restart-ON path: remove the existing phone-side task first, then launch a fresh task on VD.
     * This avoids the old relocate -> remove -> relaunch sequence entirely. Restart OFF never enters
     * this function and therefore keeps the original v24 behaviour byte-for-byte at the call level. */
    private fun restartAppBeforeVdLaunch(component: String) {
        val target = displayId
        if (target < 0) return
        val pkg = component.substringBefore('/')

        Thread {
            if (!capturing || displayId != target || lastComponent != component) return@Thread

            val task = runCatching { taskAndDisplayForPackage(pkg) }
                .onFailure { Log.w(TAG, "restart-on-vd: task lookup failed for $pkg: ${reason(it)}") }
                .getOrNull()

            var oldTaskId: Int? = null
            if (task != null) {
                oldTaskId = task.first
                val fromDisplay = task.second
                val removed = runCatching { removeTaskFromRecents(oldTaskId) }
                    .onFailure {
                        Log.w(TAG, "restart-on-vd: removeTask($oldTaskId) failed for $pkg: ${reason(it)}")
                    }
                    .getOrElse { false }
                Log.i(
                    TAG,
                    "restart-on-vd: pre-VD removeTask($oldTaskId) for $pkg " +
                        "from display $fromDisplay -> $removed",
                )
                if (!removed) {
                    // Removal failed: do not leave the meter blank. Fall back to the proven normal
                    // relocation path, but only inside Restart ON's failure handling.
                    if (capturing && displayId == target && lastComponent == component) {
                        relocateApp(component, target)
                        Log.w(TAG, "restart-on-vd: removal failed; used normal relocation fallback")
                    }
                    return@Thread
                }

                // Wait until ActivityTaskManager has actually discarded the old task. This prevents
                // singleTask from routing the later `am start` back into the Activity being destroyed.
                val goneStart = android.os.SystemClock.uptimeMillis()
                var oldTaskGone = false
                while (capturing && displayId == target && lastComponent == component) {
                    val current = runCatching { taskAndDisplayForPackage(pkg) }.getOrNull()
                    if (current?.first != oldTaskId) {
                        oldTaskGone = true
                        break
                    }
                    if (android.os.SystemClock.uptimeMillis() - goneStart >= 1_500L) break
                    try { Thread.sleep(50L) } catch (_: InterruptedException) { return@Thread }
                }
                if (!oldTaskGone) {
                    Log.w(TAG, "restart-on-vd: old task $oldTaskId still present after 1500ms; abort launch")
                    return@Thread
                }
                val goneMs = android.os.SystemClock.uptimeMillis() - goneStart
                Log.i(TAG, "restart-on-vd: phone task $oldTaskId gone after ${goneMs}ms")
            } else {
                Log.i(TAG, "restart-on-vd: $pkg has no existing task; fresh VD launch only")
            }

            // Same cleanup window that proved sufficient for Yahoo's native/audio teardown, but now
            // it happens before the app has ever crossed onto the virtual display.
            try { Thread.sleep(500L) } catch (_: InterruptedException) { return@Thread }
            if (!capturing || displayId != target || lastComponent != component) return@Thread

            val rcStart = exec(
                "am", "start", "--display", target.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                "-n", component,
            )

            val launchStart = android.os.SystemClock.uptimeMillis()
            var launchedTask: Pair<Int, Int>? = null
            while (capturing && displayId == target && lastComponent == component) {
                launchedTask = runCatching { taskAndDisplayForPackage(pkg) }.getOrNull()
                if (launchedTask != null) break
                if (android.os.SystemClock.uptimeMillis() - launchStart >= 1_500L) break
                try { Thread.sleep(50L) } catch (_: InterruptedException) { return@Thread }
            }
            val newTaskId = launchedTask?.first
            val landed = launchedTask?.second
            Log.i(
                TAG,
                "restart-on-vd: fresh launch $component on display $target " +
                    "(rc=$rcStart, oldTask=$oldTaskId, newTask=$newTaskId, landed=$landed)",
            )

            // Keep the existing conservative fallback only if Android ignored --display.
            if (landed != target && capturing && displayId == target && lastComponent == component) {
                relocateApp(component, target)
            }

            // A task now exists. Release encoding only after that confirmation; the next ImageReader
            // update becomes the first fresh DASH frame visible to the app/reload-mask logic.
            if (launchedTask != null && capturing && displayId == target && lastComponent == component) {
                restartFrameGate = false
                Log.i(TAG, "restart-on-vd: new task confirmed; waiting for first fresh DASH frame")
            }
        }.apply { name = "pillion-restart-on-vd"; isDaemon = true; start() }
    }

    /** ActivityTaskManagerService.removeTask(): same task/Recents removal primitive used by SystemUI.
     * The helper is an app_process running as uid=shell, and Android's Shell package is granted
     * android.permission.REMOVE_TASKS. */
    private fun removeTaskFromRecents(taskId: Int): Boolean {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val method = atm.javaClass.methods.firstOrNull {
            it.name == "removeTask" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: error("IActivityTaskManager.removeTask(int) unavailable")
        val result = method.invoke(atm, taskId)
        return (result as? Boolean) ?: true
    }

    /**
     * Put [component] on [target], **moving its existing task** rather than re-launching it.
     * `am start --display` only relocates apps that allow a fresh instance; a singleTask/singleInstance
     * app already running on display 0 (e.g. Google Maps mid-navigation) just gets refocused on display
     * 0, so the dash display stays empty (black) while the app flashes on the phone on unlock — exactly
     * the reported bug. Moving the root task (the `am display move-stack` primitive) relocates it
     * regardless of launch mode. Fall back to a fresh launch only when the app isn't running yet.
     */
    private fun relocateApp(component: String, target: Int) {
        val pkg = component.substringBefore('/')
        val before = runCatching { taskAndDisplayForPackage(pkg) }
            .onFailure { Log.w(TAG, "relocate: task lookup failed for $pkg: ${reason(it)}") }
            .getOrNull()
        if (before != null) {
            val (taskId, fromDisplay) = before
            val rc = exec("am", "display", "move-stack", taskId.toString(), target.toString())
            // Re-query where the task actually ended up: move-stack returns rc=0 even when a
            // singleInstance task refuses to move, so the landed display is the source of truth.
            val landed = runCatching { taskAndDisplayForPackage(pkg)?.second }.getOrNull()
            Log.i(TAG, "relocate: task $taskId ($pkg) display $fromDisplay -> $target (rc=$rc, landed=$landed)")
        } else {
            exec(
                "am", "start", "--display", target.toString(),
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", component,
            )
            Log.i(TAG, "relocate: launched $component on display $target (was not running)")
        }
    }

    /**
     * v33 camera assist, intentionally isolated from v30's proven power / PROMOTE / DEMOTE flow.
     *
     * Pixel's double-POWER shortcut can launch SecureCamera on display 0 while v30 is still finishing
     * its normal relocation + panel-blank sequence. Do not suppress or reorder any of that existing
     * logic. Instead, when SecureCamera first becomes the actual top activity, wait briefly for v30's
     * own transitions to settle, verify that SecureCamera is still top, then cancel only the queued
     * panel-OFF retries and reopen the physical panel. Camera exit remains entirely v30 behaviour.
     */
    private fun startSecureCameraWatcher() {
        if (secureCameraWatcher != null) return
        val watcher = Thread {
            var cameraWasTop = false
            var queryFailureLogged = false
            Log.i(TAG, "camera-panel: top-activity watcher started")
            while (!Thread.currentThread().isInterrupted) {
                val topResult = runCatching { topActivityOnDisplay(0) }
                if (topResult.isFailure) {
                    if (!queryFailureLogged) {
                        Log.w(TAG, "camera-panel: topActivity query failed: ${reason(topResult.exceptionOrNull()!!)}")
                        queryFailureLogged = true
                    }
                } else {
                    queryFailureLogged = false
                }

                val cameraIsTop = isPixelSecureCamera(topResult.getOrNull())
                if (cameraIsTop && !cameraWasTop) {
                    scheduleSecureCameraPanelRestore()
                } else if (!cameraIsTop && cameraWasTop) {
                    // If Camera disappeared during the settle window, do not wake a stale screen.
                    secureCameraPanelRestore?.interrupt()
                    secureCameraPanelRestore = null
                }
                cameraWasTop = cameraIsTop

                try {
                    Thread.sleep(SECURE_CAMERA_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            secureCameraPanelRestore?.interrupt()
            secureCameraPanelRestore = null
            if (secureCameraWatcher === Thread.currentThread()) secureCameraWatcher = null
            Log.i(TAG, "camera-panel: top-activity watcher stopped")
        }.apply {
            name = "pillion-secure-camera-watch"
            isDaemon = true
        }
        secureCameraWatcher = watcher
        watcher.start()
    }

    private fun scheduleSecureCameraPanelRestore() {
        secureCameraPanelRestore?.interrupt()
        val restore = Thread {
            try {
                Thread.sleep(SECURE_CAMERA_SETTLE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }

            val top = runCatching { topActivityOnDisplay(0) }
                .onFailure { Log.w(TAG, "camera-panel: recheck failed: ${reason(it)}") }
                .getOrNull()
            if (!isPixelSecureCamera(top)) {
                Log.i(TAG, "camera-panel: restore skipped; SecureCamera no longer top ($top)")
                if (secureCameraPanelRestore === Thread.currentThread()) secureCameraPanelRestore = null
                return@Thread
            }

            // Order matters: stop every queued panel-OFF retry first, then reopen the panel once.
            // PROMOTE/DEMOTE, heartbeat, task placement, and Camera exit are deliberately untouched.
            stopPanelOffRetries()
            setMainDisplayPower(true)
            Log.i(TAG, "camera-panel: SecureCamera stable for ${SECURE_CAMERA_SETTLE_MS}ms; cancelled panel-OFF retries and restored main panel ($top)")
            if (secureCameraPanelRestore === Thread.currentThread()) secureCameraPanelRestore = null
        }.apply {
            name = "pillion-secure-camera-panel-restore"
            isDaemon = true
        }
        secureCameraPanelRestore = restore
        restore.start()
    }

    /** Current top activity for one display. Prefer the focused root task; fall back to z-order. */
    private fun topActivityOnDisplay(targetDisplayId: Int): ComponentName? {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val onDisplayMethod = atm.javaClass.methods.firstOrNull {
            it.name == "getAllRootTaskInfosOnDisplay" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        val onDisplayInfos = runCatching { onDisplayMethod?.invoke(atm, targetDisplayId) as? List<*> }.getOrNull()
        val infos = onDisplayInfos
            ?: (atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>).filter { info ->
                info != null && runCatching {
                    info.javaClass.getField("displayId").getInt(info) == targetDisplayId
                }.getOrDefault(false)
            }

        var fallback: ComponentName? = null
        for (info in infos) {
            info ?: continue
            val cls = info.javaClass
            val top = runCatching { cls.getField("topActivity").get(info) as? ComponentName }.getOrNull()
                ?: continue
            if (fallback == null) fallback = top
            val focused = runCatching { cls.getField("isFocused").getBoolean(info) }.getOrDefault(false)
            if (focused) return top
        }
        return fallback
    }

    private fun isPixelSecureCamera(component: ComponentName?): Boolean {
        if (component == null || component.packageName != GOOGLE_CAMERA_PACKAGE) return false
        return component.className.substringAfterLast('.').contains("SecureCameraActivity")
    }

    /** (rootTaskId, displayId) of [pkg]'s current task, or null if it isn't running. */
    private fun taskAndDisplayForPackage(pkg: String): Pair<Int, Int>? {
        val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        val infos = atm.javaClass.getMethod("getAllRootTaskInfos").invoke(atm) as List<*>
        for (info in infos) {
            info ?: continue
            val cls = info.javaClass
            val top = runCatching { cls.getField("topActivity").get(info) as? ComponentName }.getOrNull()
            val base = runCatching { cls.getField("baseActivity").get(info) as? ComponentName }.getOrNull()
            if (top?.packageName == pkg || base?.packageName == pkg) {
                return cls.getField("taskId").getInt(info) to cls.getField("displayId").getInt(info)
            }
        }
        return null
    }

    /**
     * Keep the dash display's own display group from going idle while promoted. An
     * OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED virtual display still powers OFF ~10s after the phone screen
     * turns off unless something keeps poking it — exactly scrcpy's `--keep-active`. We call
     * IPowerManager.userActivity(displayId, …) every few seconds, which resets *that group's* sleep
     * timer without waking the main screen, so the dash keeps rendering with the phone locked.
     */
    private fun startHeartbeat() {
        if (keepAlive != null) return
        val pm = powerService
        val m = userActivity
        if (pm == null || m == null) {
            Log.w(TAG, "keep-alive: userActivity unavailable — dash will black out ~10s after lock")
            return
        }
        wakeDisplay(displayId) // power-press may have dozed this display group; wake it so it can render
        keepAlive = Thread {
            Log.i(TAG, "keep-alive: started for display $displayId")
            while (capturing && displayId >= 0) {
                runCatching { m.invoke(pm, displayId, android.os.SystemClock.uptimeMillis(), 0, 0) }
                    .onFailure { Log.w(TAG, "keep-alive: userActivity failed: ${it.message}") }
                try { Thread.sleep(KEEP_ALIVE_MS) } catch (_: InterruptedException) { break }
            }
            Log.i(TAG, "keep-alive: stopped")
        }.apply { isDaemon = true; start() }
    }

    private fun stopHeartbeat() {
        keepAlive?.interrupt()
        keepAlive = null
    }

    /**
     * On Android 16, wakeUpWithDisplayId() may still be finishing its normal display-on transition
     * when the first panel-off request runs, so PowerManager can turn display 0 back on a few hundred
     * milliseconds later. Retry after the wake settles; the virtual display keeps rendering.
     */
    private fun startPanelOffRetries() {
        panelOffRetry?.interrupt()
        val retry = Thread {
            var elapsed = 0L
            for (delay in PANEL_OFF_RETRY_DELAYS_MS) {
                try {
                    val sleepMs = delay - elapsed
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
                elapsed = delay
                if (!capturing) break
                Log.i(TAG, "panel: retrying main display OFF after ${delay}ms")
                setMainDisplayPower(false)
            }
            if (panelOffRetry === Thread.currentThread()) panelOffRetry = null
        }.apply { isDaemon = true }
        panelOffRetry = retry
        retry.start()
    }

    private fun stopPanelOffRetries() {
        panelOffRetry?.interrupt()
        panelOffRetry = null
    }

    /** Wake the dash display group from sleep/doze. userActivity only resets the idle timer; it
     *  cannot wake a sleeping group, so Android 14+ needs wakeUpWithDisplayId() here. */
    private fun wakeDisplay(displayId: Int) {
        val pm = powerService ?: return
        val m = pm.javaClass.methods.firstOrNull {
            it.name == "wakeUpWithDisplayId" && it.parameterTypes.any { t -> t == Int::class.javaPrimitiveType }
        } ?: pm.javaClass.methods.firstOrNull { it.name == "wakeUp" } ?: run {
            Log.w(TAG, "wake: no wakeUp method"); return
        }
        runCatching {
            m.invoke(pm, *wakeArgs(m, displayId))
            Log.i(TAG, "wake: requested ${m.name} for display $displayId")
        }.onFailure { Log.w(TAG, "wake: wakeUp failed: ${reason(it)}") }
    }

    private fun wakeArgs(method: java.lang.reflect.Method, displayId: Int): Array<Any?> {
        val strings = mutableListOf("pillion-dash", SHELL_PACKAGE)
        val last = method.parameterTypes.lastIndex
        return method.parameterTypes.mapIndexed { i, t ->
            when {
                t == Long::class.javaPrimitiveType -> android.os.SystemClock.uptimeMillis()
                t == Int::class.javaPrimitiveType ->
                    if (method.name == "wakeUpWithDisplayId" && i == last) displayId else 0
                t == String::class.java -> strings.removeFirstOrNull() ?: SHELL_PACKAGE
                else -> null
            }
        }.toTypedArray()
    }

    /** IPowerManager binder, for the per-display userActivity heartbeat (shell uid holds DEVICE_POWER). */
    private val powerService: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val stub = Class.forName("android.os.IPowerManager\$Stub")
            stub.methods.first { it.name == "asInterface" }.invoke(null, binder)
        }.onFailure { Log.w(TAG, "keep-alive: no IPowerManager: ${it.message}") }.getOrNull()
    }

    /** API 31+ overload: userActivity(int displayId, long time, int event, int flags). */
    private val userActivity: java.lang.reflect.Method? by lazy {
        powerService?.javaClass?.methods?.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                it.parameterTypes[1] == Long::class.javaPrimitiveType
        }
    }

    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2
    private const val DISPLAY_STATE_OFF = 1
    private const val DISPLAY_STATE_ON = 2

    /**
     * Blank (or restore) the PHONE's main panel without sleeping the device — scrcpy's `--turn-screen-off`.
     * The dash needs the device to stay interactive (so its display group keeps rendering, kept alive by
     * [startHeartbeat]); pressing POWER would truly sleep the device and kill the dash. So instead we
     * turn just display 0's panel off at the SurfaceFlinger level: the phone looks off, the system stays
     * awake, and the dash keeps rendering. Restored on demote/shutdown.
     */
    private fun setMainDisplayPower(on: Boolean) {
        // Try the real panel-off APIs only. Brightness-0 dimming was removed because it doesn't truly
        // turn the screen off. On some builds these hidden APIs are unavailable or rejected; when that
        // happens there is no app-only way to force a real panel-off state.
        // Primary: SurfaceControl.setDisplayPowerMode(token, mode) — actually blanks the panel at the
        // SurfaceFlinger level while the system stays awake (scrcpy's --turn-screen-off).
        val token = mainDisplayToken()
        if (token != null) {
            val ok = runCatching {
                val sc = Class.forName("android.view.SurfaceControl")
                sc.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(null, token, if (on) POWER_MODE_NORMAL else POWER_MODE_OFF)
            }.onFailure { Log.w(TAG, "panel: setDisplayPowerMode: ${reason(it)}") }.isSuccess
            if (ok) { Log.i(TAG, "panel: main display ${if (on) "ON" else "OFF"} (setDisplayPowerMode)"); return }
        }
        // Fallback: IDisplayManager.requestDisplayPower (Android 15+) — may be a no-op on some builds.
        val dm = displayManagerService
        val req = dm?.javaClass?.methods?.firstOrNull {
            it.name == "requestDisplayPower" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (dm != null && req != null) {
            runCatching {
                val arg = if (req.parameterTypes[1] == Boolean::class.javaPrimitiveType) {
                    on
                } else if (on) {
                    DISPLAY_STATE_ON
                } else {
                    DISPLAY_STATE_OFF
                }
                req.invoke(dm, 0, arg) as? Boolean
            }.onSuccess { accepted ->
                if (accepted == false) {
                    Log.w(TAG, "panel: requestDisplayPower returned false for ${if (on) "ON" else "OFF"}")
                } else {
                    Log.i(TAG, "panel: main display ${if (on) "ON" else "OFF"} (requestDisplayPower)")
                }
            }
                .onFailure { Log.w(TAG, "panel: requestDisplayPower: ${reason(it)}") }
        } else {
            Log.w(TAG, "panel: no way to set main display power")
        }
    }

    /**
     * com.android.server.display.DisplayControl, loaded + linked so its **native** getPhysicalDisplay*
     * methods resolve from a bare app_process. Android 14+ moved those methods here from SurfaceControl.
     *
     * The naive approach — `Class.forName(...)` on the boot/app classloader + `System.loadLibrary` —
     * fails with UnsatisfiedLinkError: RegisterNatives binds the JNI methods to whichever classloader
     * loaded the *library*, which isn't the one that loaded DisplayControl. scrcpy's fix, replicated
     * here: load DisplayControl through a classloader built from $SYSTEMSERVERCLASSPATH, then load the
     * library via the private `Runtime.loadLibrary0(Class, String)` passing that same class — so the
     * natives register against it. Computed once, then reused for every panel-power call.
     */
    private val displayControlClass: Class<*>? by lazy {
        runCatching {
            val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createClassLoader = factory.getDeclaredMethod(
                "createClassLoader",
                String::class.java, String::class.java, String::class.java, ClassLoader::class.java,
                Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java,
            )
            val systemServerClasspath = System.getenv("SYSTEMSERVERCLASSPATH")
                ?: error("SYSTEMSERVERCLASSPATH unset")
            val loader = createClassLoader.invoke(
                null, systemServerClasspath, null, null, ClassLoader.getSystemClassLoader(), 0, true, null,
            ) as ClassLoader
            val dc = loader.loadClass("com.android.server.display.DisplayControl")
            linkAndroidServers(dc)
            Log.i(TAG, "panel: DisplayControl linked via SYSTEMSERVERCLASSPATH classloader")
            dc
        }.onFailure { Log.w(TAG, "panel: DisplayControl init failed: ${reason(it)}") }.getOrNull()
    }

    /**
     * Load libandroid_servers and register its natives against [dc]'s classloader. lint flags
     * `loadLibrary0` as a blocked non-SDK API for normal apps, but the helper runs as the **shell uid**
     * (app_process), which is exempt from the hidden-API blacklist — same as scrcpy — so the reflection
     * succeeds at runtime. Hence the suppression.
     */
    @SuppressLint("BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "PrivateApi")
    private fun linkAndroidServers(dc: Class<*>) {
        val rt = Runtime.getRuntime()
        // loadLibrary0(Class, String) on API 34+; fall back to (ClassLoader, String) on older shapes.
        val linked = runCatching {
            Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(rt, dc, "android_servers")
        }.recoverCatching {
            Runtime::class.java.getDeclaredMethod("loadLibrary0", ClassLoader::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(rt, dc.classLoader, "android_servers")
        }
        check(linked.isSuccess) { "loadLibrary0 failed: ${reason(linked.exceptionOrNull()!!)}" }
    }

    /** IDisplayManager binder, for requestDisplayPower (Android 15+). */
    private val displayManagerService: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "display")
            val stub = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            stub.methods.first { it.name == "asInterface" }.invoke(null, binder)
        }.onFailure { Log.w(TAG, "panel: no IDisplayManager: ${reason(it)}") }.getOrNull()
    }

    /**
     * The main physical display's SurfaceControl token. On Android 14+ the getPhysicalDisplay* methods
     * moved off SurfaceControl into com.android.server.display.DisplayControl (in SYSTEMSERVERCLASSPATH,
     * not our boot classpath), so load that with a dedicated classloader; fall back to SurfaceControl on
     * older builds.
     */
    private fun mainDisplayToken(): android.os.IBinder? {
        val sc = Class.forName("android.view.SurfaceControl")
        // 1) SurfaceControl.getInternalDisplayToken() — simplest, API 29+.
        runCatching {
            return sc.getMethod("getInternalDisplayToken").invoke(null) as android.os.IBinder
        }.onFailure { Log.w(TAG, "panel: getInternalDisplayToken: ${reason(it)}") }
        // 2) SurfaceControl.getPhysicalDisplayIds/Token — pre-14 and some 14+.
        runCatching {
            val ids = sc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
            val getToken = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
            return getToken.invoke(null, ids.first()) as android.os.IBinder
        }.onFailure { Log.w(TAG, "panel: SurfaceControl physical token: ${reason(it)}") }
        // 3) Android 14+: methods moved to com.android.server.display.DisplayControl, whose native
        // getPhysicalDisplay* methods only resolve when the class + libandroid_servers are loaded
        // together via the SYSTEMSERVERCLASSPATH classloader (see [displayControlClass]).
        runCatching {
            val dc = displayControlClass ?: error("DisplayControl unavailable")
            val ids = dc.getMethod("getPhysicalDisplayIds").apply { isAccessible = true }
                .invoke(null) as LongArray
            val getToken = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
            return getToken.invoke(null, ids.first()) as android.os.IBinder
        }.onFailure { Log.w(TAG, "panel: DisplayControl token: ${reason(it)}") }
        return null
    }

    /** Unwrap reflection wrappers so the real cause shows in logcat. */
    private fun reason(t: Throwable): String {
        val c = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t.cause ?: t
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    /** Schedule a fresh Pillion process from outside the app uid, then let this helper exit. */
    private fun schedulePillionRestart() {
        Thread {
            // Finish the helper-owned display/task cleanup before arming the delayed force-stop.
            runCatching { demoteApp() }

            val packageName = "app.pillion"
            val component = "$packageName/app.pillion.MainActivity"
            val inner = "sleep 1; am force-stop $packageName; sleep 1; am start -n $component"
            val command = "setsid sh -c '$inner' </dev/null >/dev/null 2>&1 &"
            val scheduled = runCatching {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).waitFor() == 0
            }.getOrElse { t ->
                Log.w(TAG, "Pillion restart scheduling failed: ${reason(t)}")
                false
            }
            if (scheduled) {
                Log.i(TAG, "Pillion restart scheduled; helper exiting")
                System.exit(0)
            } else {
                Log.w(TAG, "Pillion restart was not scheduled; helper remains available for fallback")
            }
        }.apply { name = "pillion-app-restart"; isDaemon = false; start() }
    }

    /** Release the trusted display and exit. Process death drops the display token, so System.exit
     *  is enough; the app sends QUIT over loopback when the session ends (the helper is detached and
     *  would otherwise outlive the app). */
    private fun shutdown() {
        Log.i(TAG, "shutdown requested; releasing display and exiting")
        runCatching { demoteApp() }
        System.exit(0)
    }

    /**
     * Move the app back to the phone and stop encoding.
     *
     * Do not restore display 0 through the low-level SurfaceControl/display-manager ON path here.
     * That path is device-dependent and can leave some displays black. The physical POWER press
     * that asks us to demote has just driven Android toward sleep while the panel was manually
     * blanked; after moving the task back, use Android's normal wake input path instead.
     */
    private fun demoteApp() {
        capturing = false
        restartPerformedForCurrentPromotion = false
        restartFrameGate = false
        ocrActive = false
        lastOcrArea1CropMs = 0L
        lastOcrArea2CropMs = 0L
        stopPanelOffRetries()
        stopHeartbeat()
        latestJpeg = null
        latestOcrJpeg = null
        latestOcrArea1Jpeg = null
        latestOcrSeq++
        lastComponent?.let { relocateApp(it, 0) } // move task while the phone panel is still dark
        wakePhoneViaKeyEvent()
        Log.i(TAG, "demoted to phone; requested system wake")
    }

    /** KEYCODE_WAKEUP (224): normal Android wake path; harmless when Android is already awake. */
    private fun wakePhoneViaKeyEvent() {
        val rc = exec("input", "keyevent", "224")
        if (rc == 0) {
            Log.i(TAG, "wake: KEYCODE_WAKEUP injected")
        } else {
            // Keep the old direct restore only as an emergency fallback when input injection itself
            // is unavailable. This avoids stranding the phone because of a missing shell command
            // while still making the normal path the safer system wake route.
            Log.w(TAG, "wake: KEYCODE_WAKEUP failed rc=$rc; falling back to direct display restore")
            wakeDisplay(0)
            setMainDisplayPower(true)
        }
    }

    private data class JpegResult(
        val bytes: ByteArray,
        val ocrBytes: ByteArray?,
        val copyMs: Long,
        val scaleMs: Long,
        val jpegMs: Long,
    )

    /**
     * Produce the normal 480x234 frame exactly as v17. After OCR_START, Area 1 is sampled every ten
     * seconds while the tiny Area 2 scale crop is sampled every second, both from the original VD
     * before scaling/margins.
     */
    private fun toJpegTimed(image: Image, nowMs: Long): JpegResult {
        val copyStart = System.currentTimeMillis()
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * virtualWidth
        val padded = Bitmap.createBitmap(
            virtualWidth + if (pixelStride > 0) rowPadding / pixelStride else 0,
            virtualHeight,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val bitmap = if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, virtualWidth, virtualHeight)
        }
        val copyMs = System.currentTimeMillis() - copyStart

        // OCR source is the unscaled VD. Do not draw the diagnostic rectangle into this crop.
        // Area 1 stays on its proven ten-second cadence. Area 2 is independently refreshed every
        // second so Yahoo's automatic intersection zoom is reflected without a stick-trigger path.
        val ocrBytes = if (ocrEnabled && ocrActive) {
            runCatching { encodeScheduledOcrCrops(bitmap, nowMs) }
                .onFailure { Log.w(TAG, "OCR crop failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
        } else {
            null
        }

        val scaleStart = System.currentTimeMillis()
        val usableWidth = (outputWidth - leftMargin).coerceAtLeast(1)
        val usableHeight = (outputHeight - bottomMargin).coerceAtLeast(1)
        val noMargins = leftMargin == 0 && bottomMargin == 0
        val needsOcrOverlay = ocrEnabled && ocrActive && ocrShowArea
        val output = if (noMargins && bitmap.width == outputWidth && bitmap.height == outputHeight && !needsOcrOverlay) {
            bitmap
        } else {
            Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                canvas.drawColor(0xFF000000.toInt() or marginRgb)
                canvas.drawBitmap(
                    bitmap,
                    null,
                    Rect(leftMargin, 0, leftMargin + usableWidth, usableHeight),
                    scalePaint,
                )
                if (needsOcrOverlay) drawOcrArea(canvas, usableWidth, usableHeight)
            }
        }
        val scaleMs = System.currentTimeMillis() - scaleStart

        val jpegStart = System.currentTimeMillis()
        val out = ByteArrayOutputStream()
        output.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        val jpegMs = System.currentTimeMillis() - jpegStart
        if (output !== bitmap) output.recycle()
        if (bitmap !== padded) bitmap.recycle()
        padded.recycle()
        return JpegResult(bytes, ocrBytes, copyMs, scaleMs, jpegMs)
    }

    private fun encodeScheduledOcrCrops(bitmap: Bitmap, nowMs: Long): ByteArray? {
        val area1Due = latestOcrArea1Jpeg == null || nowMs - lastOcrArea1CropMs >= OCR_AREA1_INTERVAL_MS
        val area2Due = nowMs - lastOcrArea2CropMs >= OCR_AREA2_INTERVAL_MS
        if (!area1Due && !area2Due) return null

        if (area1Due) {
            val freshArea1 = encodeOcrArea(
                bitmap, ocrLeftPercent, ocrTopPercent, ocrRightPercent, ocrBottomPercent,
            )
            latestOcrArea1Jpeg = freshArea1
            lastOcrArea1CropMs = nowMs
        }

        // Whenever Area 1 is refreshed, take Area 2 from the same source frame even if its own
        // one-second deadline is a few milliseconds away. This keeps the paired payload coherent.
        if (!area2Due && !area1Due) return null
        val area2 = encodeOcrArea(
            bitmap, ocr2LeftPercent, ocr2TopPercent, ocr2RightPercent, ocr2BottomPercent,
        )
        lastOcrArea2CropMs = nowMs
        val area1 = latestOcrArea1Jpeg ?: return null
        return buildOcrPayload(area1, area2)
    }

    private fun buildOcrPayload(area1: ByteArray, area2: ByteArray): ByteArray {
        return ByteArrayOutputStream(area1.size + area2.size + 12).use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(OCR2_PAYLOAD_MAGIC)
                out.writeInt(area1.size)
                out.write(area1)
                out.writeInt(area2.size)
                out.write(area2)
            }
            bytes.toByteArray()
        }
    }

    private fun encodeOcrArea(bitmap: Bitmap, leftP: Int, topP: Int, rightP: Int, bottomP: Int): ByteArray {
        val x1 = percentEdge(bitmap.width, minOf(leftP, rightP), false)
        val x2 = percentEdge(bitmap.width, maxOf(leftP, rightP), true).coerceAtLeast(x1 + 1)
        val y1 = percentEdge(bitmap.height, minOf(topP, bottomP), false)
        val y2 = percentEdge(bitmap.height, maxOf(topP, bottomP), true).coerceAtLeast(y1 + 1)
        val crop = Bitmap.createBitmap(bitmap, x1, y1, (x2 - x1).coerceAtLeast(1), (y2 - y1).coerceAtLeast(1))
        return try {
            ByteArrayOutputStream().use { out ->
                crop.compress(Bitmap.CompressFormat.JPEG, OCR_JPEG_QUALITY, out)
                out.toByteArray()
            }
        } finally {
            crop.recycle()
        }
    }

    private fun percentEdge(size: Int, percent: Int, upper: Boolean): Int {
        val raw = (size.toLong() * percent.coerceIn(0, 100) / 100L).toInt()
        return if (upper) raw.coerceIn(1, size) else raw.coerceIn(0, size - 1)
    }

    private fun drawOcrArea(canvas: Canvas, usableWidth: Int, usableHeight: Int) {
        drawOcrRect(canvas, usableWidth, usableHeight, ocrLeftPercent, ocrTopPercent, ocrRightPercent, ocrBottomPercent)
        drawOcrRect(canvas, usableWidth, usableHeight, ocr2LeftPercent, ocr2TopPercent, ocr2RightPercent, ocr2BottomPercent)
    }

    private fun drawOcrRect(canvas: Canvas, usableWidth: Int, usableHeight: Int, leftValue: Int, topValue: Int, rightValue: Int, bottomValue: Int) {
        val leftP = minOf(leftValue, rightValue)
        val rightP = maxOf(leftValue, rightValue)
        val topP = minOf(topValue, bottomValue)
        val bottomP = maxOf(topValue, bottomValue)
        val left = leftMargin + usableWidth * leftP / 100f
        val right = leftMargin + usableWidth * rightP / 100f
        val top = usableHeight * topP / 100f
        val bottom = usableHeight * bottomP / 100f
        canvas.drawRect(left, top, right, bottom, ocrAreaOuterPaint)
        canvas.drawRect(left, top, right, bottom, ocrAreaInnerPaint)
    }



    /** A system Context with no Application — the only way to get one in a bare app_process. */
    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val systemMain = activityThread.getMethod("systemMain").invoke(null)
        return activityThread.getMethod("getSystemContext").invoke(systemMain) as Context
    }

    /**
     * Create a trusted virtual display. The public [createVirtualDisplay] only accepts a flags int on
     * a [DisplayManager] instance built with a Context (its constructor is @hide), so we build one via
     * reflection — exactly what scrcpy does. The system grants the trusted/public flags because our
     * process runs as the shell uid (presented by [ShellContext]).
     */
    private fun createTrustedVirtualDisplay(
        context: Context, name: String, width: Int, height: Int, dpi: Int, surface: Surface,
    ): VirtualDisplay {
        val dmClass = android.hardware.display.DisplayManager::class.java
        val ctor = dmClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val dm = ctor.newInstance(context)
        return dm.createVirtualDisplay(name, width, height, dpi, surface, FLAGS)
    }

    private fun exec(vararg command: String): Int {
        val process = Runtime.getRuntime().exec(command)
        process.inputStream.bufferedReader().readText()
        process.errorStream.bufferedReader().readText()
        return process.waitFor()
    }

    private const val SHELL_UID = 2000
    private const val SHELL_PACKAGE = "com.android.shell"

    /** Reports the shell identity so framework ownership checks pass. Mirrors scrcpy's FakeContext. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE

        @TargetApi(31)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PACKAGE).build()
    }
}
