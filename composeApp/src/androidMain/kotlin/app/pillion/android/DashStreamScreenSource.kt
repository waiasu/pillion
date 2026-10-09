package app.pillion.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.Log
import app.pillion.core.DashResolution
import app.pillion.core.ScreenSource
import app.pillion.server.DashServer
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main dash-frame transport deliberately remains the v17 loopback protocol:
 * `[4-byte length][JPEG bytes]` on [DashServer.PORT]. OCR is isolated on a second loopback port and
 * is not connected or started until a real Bluetooth IMAGE_ACK has been flowing for five seconds.
 * This keeps OCR, Tesseract and ROAD traffic out of initial connection/reconnection handling.
 */
class DashStreamScreenSource(
    private val leftMargin: Int = 50,
    private val bottomMargin: Int = 16,
    private val zoomInXPercent: Int = 93,
    private val zoomInYPercent: Int = 47,
    private val zoomOutXPercent: Int = 93,
    private val zoomOutYPercent: Int = 74,
    private val ocrEnabled: Boolean = false,
    private val restartAppOnVd: Boolean = false,
    private val appContext: Context? = null,
) : ScreenSource {
    private val thread = Thread(::readLoop).apply { isDaemon = true }
    @Volatile private var ocrThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var ocrSocket: Socket? = null
    @Volatile private var latest: ByteArray? = null
    @Volatile private var lastFrameAt: Long = 0
    @Volatile private var staticFrameLogged = false
    @Volatile private var socketDownLogged = false
    @Volatile private var helperConnectFailureLogged = false
    @Volatile private var receiveSeq = 0L
    @Volatile private var lastReceiveGapMs = 0L
    @Volatile private var desiredComponent: String? = null
    @Volatile private var tapMarkerX = 0
    @Volatile private var tapMarkerY = 0
    @Volatile private var tapMarkerFramesRemaining = 0

    // OCR gate. No OCR socket, crop, recognizer request or ROAD message exists before this gate opens.
    @Volatile private var ocrAckSeen = false
    @Volatile private var ocrEnableAtMs = 0L
    @Volatile private var ocrGateActive = false
    private val ocrInFlight = AtomicBoolean(false)
    private val recognizerLock = Any()
    @Volatile private var tessBaseApi: TessBaseAPI? = null
    private val ocrWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pillion-ocr-engine").apply { isDaemon = true }
    }
    private val roadTextLock = Any()
    private var pendingRoadText: String? = null
    private var hasPublishedRoadText = false
    @Volatile private var cachedOcrArea1Jpeg: ByteArray? = null
    @Volatile private var cachedOcrArea1Text = ""

    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun start() {
        if (running) return
        running = true
        thread.start()
        // OCR thread is intentionally not created here. It is started lazily only after the first
        // valid DASH IMAGE_ACK plus the five-second grace period.
    }

    private fun readLoop() {
        while (running) {
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress("127.0.0.1", DashServer.PORT), CONNECT_TIMEOUT_MS)
                }
                socket = s
                helperConnectFailureLogged = false
                Log.d(TAG, "dash stream: connected to 127.0.0.1:${DashServer.PORT} local=${s.localPort}")
                DiagnosticFlightRecorder.record("DASH", "HELPER_STREAM CONNECTED")
                syncDesiredState(s)
                val data = DataInputStream(s.getInputStream().buffered())
                while (running) {
                    // Intentionally identical to v17: main frame stream has no OCR length/payload.
                    val len = data.readInt()
                    if (len <= 0 || len > MAX_FRAME) throw IllegalStateException("bad frame length $len")
                    val frame = ByteArray(len)
                    data.readFully(frame)
                    val now = SystemClock.elapsedRealtime()
                    val gap = if (lastFrameAt == 0L) 0L else now - lastFrameAt
                    latest = frame
                    lastFrameAt = now
                    receiveSeq++
                    lastReceiveGapMs = gap
                }
            } catch (t: Throwable) {
                if (running) {
                    val hadConnectedSocket = socket != null
                    Log.w(TAG, "dash stream: read loop ${t.javaClass.simpleName}: ${t.message}; lastRxGap=${lastReceiveGapMs}ms seq=$receiveSeq; retrying")
                    if (hadConnectedSocket) {
                        DiagnosticFlightRecorder.record(
                            "DASH",
                            "HELPER_STREAM LOST ${t.javaClass.simpleName}: ${t.message ?: "-"} seq=$receiveSeq",
                        )
                    } else if (!helperConnectFailureLogged) {
                        helperConnectFailureLogged = true
                        DiagnosticFlightRecorder.record(
                            "DASH",
                            "HELPER_STREAM CONNECT_FAIL ${t.javaClass.simpleName}: ${t.message ?: "-"}",
                        )
                    }
                }
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (running) Thread.sleep(RETRY_MS)
        }
    }

    @Synchronized
    private fun ensureOcrThreadStarted() {
        if (ocrThread?.isAlive == true || !running || !ocrEnabled) return
        ocrThread = Thread(::ocrReadLoop, "pillion-ocr").apply {
            isDaemon = true
            start()
        }
    }

    /** OCR has its own loopback stream so it cannot change or stall the proven v17 frame reader. */
    private fun ocrReadLoop() {
        while (running) {
            if (!ocrGateActive) {
                Thread.sleep(OCR_IDLE_POLL_MS)
                continue
            }
            try {
                val s = Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress("127.0.0.1", DashServer.OCR_PORT), CONNECT_TIMEOUT_MS)
                }
                ocrSocket = s
                Log.i(TAG, "OCR: side-channel connected to 127.0.0.1:${DashServer.OCR_PORT}")
                val data = DataInputStream(s.getInputStream().buffered())
                while (running && ocrGateActive) {
                    val len = data.readInt()
                    if (len <= 0 || len > MAX_OCR_FRAME) throw IllegalStateException("bad OCR frame length $len")
                    val frame = ByteArray(len)
                    data.readFully(frame)
                    if (ocrGateActive) processOcr(frame)
                }
            } catch (t: Throwable) {
                if (running && ocrGateActive) {
                    Log.w(TAG, "OCR: side-channel ${t.javaClass.simpleName}: ${t.message}; retrying")
                }
            } finally {
                runCatching { ocrSocket?.close() }
                ocrSocket = null
            }
            if (running && ocrGateActive) Thread.sleep(RETRY_MS)
        }
    }

    /**
     * Return the last frame while the helper connection is healthy, even when it has not changed for
     * a while. A SurfaceView/Map renderer is allowed to be event-driven: when the visual contents are
     * static Android may simply stop queueing new buffers.
     */
    override fun latestFrame(): ByteArray? {
        maybeEnableOcr()

        val frame = latest ?: return null
        val age = SystemClock.elapsedRealtime() - lastFrameAt
        val connected = socket != null

        if (connected) {
            socketDownLogged = false
            if (age >= STATIC_FRAME_LOG_MS) {
                if (!staticFrameLogged) {
                    Log.d(TAG, "dash stream: frame unchanged for ${age}ms; keeping last dash frame (socket=connected)")
                    staticFrameLogged = true
                }
            } else {
                staticFrameLogged = false
            }
            return withTapMarker(frame)
        }

        staticFrameLogged = false
        if (age <= SOCKET_DOWN_GRACE_MS) {
            if (!socketDownLogged) {
                Log.w(TAG, "dash stream: socket down; holding last dash frame during reconnect grace (${age}ms)")
                socketDownLogged = true
            }
            return withTapMarker(frame)
        }

        if (socketDownLogged) {
            Log.w(TAG, "dash stream: socket still down after ${age}ms; allowing mirror fallback")
        }
        socketDownLogged = false
        return null
    }

    /** Monotonic count of complete DASH frames received from the helper. */
    fun receivedFrameSequence(): Long = receiveSeq

    /** Every RFCOMM/NaviLite attempt starts with OCR completely disabled. */
    override fun onTransportConnecting() {
        resetOcrGate("transport connecting", notifyHelperIfActive = true)
    }

    /** Start the five-second grace only after a real IMAGE_ACK while DASH mode is active. */
    override fun onImageAck() {
        if (!ocrEnabled || ocrAckSeen || !running) return
        ocrAckSeen = true
        ocrEnableAtMs = SystemClock.elapsedRealtime() + OCR_START_DELAY_MS
        Log.i(TAG, "OCR: first IMAGE_ACK; keeping v17-only path for ${OCR_START_DELAY_MS}ms")
        DiagnosticFlightRecorder.record("OCR", "GATE grace started ${OCR_START_DELAY_MS}ms")
    }

    private fun maybeEnableOcr() {
        if (!ocrEnabled || !ocrAckSeen || ocrGateActive) return
        val enableAt = ocrEnableAtMs
        if (enableAt == 0L || SystemClock.elapsedRealtime() < enableAt) return
        // Clear Area 1 again at the gate boundary. A recognition already in flight when an older
        // session was reset can finish shortly afterward; the five-second grace guarantees it is
        // gone before this new session starts.
        cachedOcrArea1Jpeg = null
        cachedOcrArea1Text = ""
        ocrGateActive = true
        // The helper begins crop generation only now. The OCR thread itself is also created only here,
        // so Tesseract, the side-channel socket and OCR polling are absent from initial connection.
        send("OCR_START\n")
        ensureOcrThreadStarted()
        Log.i(TAG, "OCR: enabled after stable IMAGE_ACK grace")
        DiagnosticFlightRecorder.record("OCR", "GATE OPEN")
    }

    private fun resetOcrGate(reason: String, notifyHelperIfActive: Boolean = false) {
        if (!ocrEnabled) return
        val helperCouldBeActive = ocrGateActive
        val hadGateState = helperCouldBeActive || ocrAckSeen
        ocrAckSeen = false
        ocrEnableAtMs = 0L
        ocrGateActive = false
        cachedOcrArea1Jpeg = null
        cachedOcrArea1Text = ""
        synchronized(roadTextLock) {
            pendingRoadText = null
            hasPublishedRoadText = false
        }
        runCatching { ocrSocket?.close() }
        ocrSocket = null
        // Normal PROMOTE and DEMOTE commands hard-reset OCR inside the helper, so do not add an
        // extra command to those proven v17 transitions. Only an in-place transport reconnect may
        // need an explicit STOP if OCR had actually become active.
        if (notifyHelperIfActive && helperCouldBeActive) send("OCR_STOP\n")
        if (hadGateState) {
            Log.i(TAG, "OCR: reset ($reason)")
            DiagnosticFlightRecorder.record("OCR", "GATE RESET reason=$reason")
        }
    }

    private fun tesseract(): TessBaseAPI {
        val existing = tessBaseApi
        if (existing != null) return existing
        return synchronized(recognizerLock) {
            tessBaseApi ?: run {
                val context = appContext ?: error("OCR: Android context unavailable for Tesseract")
                val dataRoot = ensureTessData(context)
                TessBaseAPI().also { tess ->
                    val config = mapOf(
                        "load_system_dawg" to "0",
                        "load_freq_dawg" to "0",
                        TessBaseAPI.VAR_CHAR_WHITELIST to OCR_TESS_WHITELIST,
                    )
                    check(tess.init(dataRoot.absolutePath, TESS_LANGUAGE, TessBaseAPI.OEM_LSTM_ONLY, config)) {
                        "Tesseract init failed"
                    }
                    tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_RAW_LINE)
                    tessBaseApi = tess
                    Log.i(TAG, "OCR: Tesseract initialized after gate (eng fast, PSM_RAW_LINE)")
                }
            }
        }
    }

    /** Copy the bundled fast English model once into app-private storage, as required by Tesseract. */
    private fun ensureTessData(context: Context): File {
        val root = File(context.filesDir, TESS_DATA_ROOT_DIR)
        val tessdataDir = File(root, "tessdata")
        val target = File(tessdataDir, TESS_DATA_FILE)
        if (target.isFile && target.length() == TESS_DATA_FILE_BYTES) return root

        check(tessdataDir.exists() || tessdataDir.mkdirs()) { "Cannot create ${tessdataDir.absolutePath}" }
        val temp = File(tessdataDir, ".${TESS_DATA_FILE}.tmp")
        runCatching { temp.delete() }
        context.assets.open("tessdata/$TESS_DATA_FILE").use { input ->
            temp.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        check(temp.length() == TESS_DATA_FILE_BYTES) {
            "Bundled Tesseract model size mismatch: ${temp.length()}"
        }
        if (target.exists()) check(target.delete()) { "Cannot replace ${target.absolutePath}" }
        check(temp.renameTo(target)) { "Cannot install ${target.absolutePath}" }
        Log.i(TAG, "OCR: installed bundled Tesseract model (${target.length()} bytes)")
        return root
    }

    /** At most one two-area OCR cycle is in flight; excess crops are dropped, never queued. */
    private fun processOcr(payload: ByteArray) {
        if (!ocrGateActive) return
        if (!ocrInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "OCR: previous recognition still running; crop skipped")
            return
        }
        val areas = decodeOcrPayload(payload)
        if (areas == null) {
            ocrInFlight.set(false)
            Log.w(TAG, "OCR: invalid two-area payload")
            return
        }

        // The helper samples Area 1 only every ten seconds and reuses that exact JPEG in the
        // one-second Area 2 payloads. Compare the bytes before decoding so Tesseract only sees
        // Area 1 when that ten-second sample actually changed.
        val area1Changed = cachedOcrArea1Jpeg?.contentEquals(areas.first) != true
        val bitmap1 = if (area1Changed) {
            BitmapFactory.decodeByteArray(areas.first, 0, areas.first.size)
        } else {
            null
        }
        if (area1Changed && bitmap1 == null) {
            Log.w(TAG, "OCR: Area 1 crop decode failed; keeping cached Area 1")
        }
        val bitmap2 = BitmapFactory.decodeByteArray(areas.second, 0, areas.second.size)
        if (bitmap2 == null) {
            bitmap1?.recycle()
            ocrInFlight.set(false)
            Log.w(TAG, "OCR: Area 2 crop decode failed")
            return
        }

        // Tesseract is synchronous. Keep it off the OCR socket reader so incoming crops continue to
        // be consumed and dropped by ocrInFlight exactly like the previous async ML Kit path.
        runCatching {
            ocrWorker.execute {
                val ocrCycleStartedAt = SystemClock.elapsedRealtime()
                var area1Ms = 0L
                var area1 = cachedOcrArea1Text
                var area1Cached = true
                try {
                    if (bitmap1 != null) {
                        val area1StartedAt = SystemClock.elapsedRealtime()
                        val raw1 = recognizeBitmap(bitmap1)
                        Log.i(TAG, "OCR raw area1='${raw1.replace("\r", "\\r").replace("\n", "\\n").replace("'", "’")}'")
                        area1Ms = SystemClock.elapsedRealtime() - area1StartedAt
                        if (!ocrGateActive) {
                            bitmap2.recycle()
                            return@execute
                        }
                        area1 = formatArea1RoadText(raw1)
                        cachedOcrArea1Text = area1
                        cachedOcrArea1Jpeg = areas.first
                        area1Cached = false
                    }

                    if (!ocrGateActive) {
                        bitmap2.recycle()
                        return@execute
                    }
                    val area2StartedAt = SystemClock.elapsedRealtime()
                    val raw2 = recognizeBitmap(bitmap2)
                    Log.i(TAG, "OCR raw area2='${raw2.replace("\r", "\\r").replace("\n", "\\n").replace("'", "’")}'")
                    val area2Ms = SystemClock.elapsedRealtime() - area2StartedAt
                    val totalMs = SystemClock.elapsedRealtime() - ocrCycleStartedAt
                    Log.i(
                        TAG,
                        "OCR timing: area1=${area1Ms}ms area2=${area2Ms}ms total=${totalMs}ms area1Cached=$area1Cached",
                    )
                    if (!ocrGateActive) return@execute

                    val area2 = extractDistance(raw2) ?: ""
                    val combined = area2 + OCR_AREA_SEPARATOR + area1
                    val safe = sanitizeRoadText(combined)
                    var queued = false
                    synchronized(roadTextLock) {
                        when {
                            safe.isNotEmpty() -> {
                                pendingRoadText = safe
                                hasPublishedRoadText = true
                                queued = true
                            }
                            hasPublishedRoadText -> {
                                pendingRoadText = ""
                                queued = true
                            }
                        }
                    }
                    if (queued) Log.d(TAG, "OCR: '${safe.replace("'", "’")}'")
                    else Log.d(TAG, "OCR: initial empty result ignored")
                } catch (t: Throwable) {
                    Log.w(TAG, "OCR failed: ${t.javaClass.simpleName}: ${t.message}")
                    if (bitmap1 != null && !bitmap1.isRecycled) bitmap1.recycle()
                    if (!bitmap2.isRecycled) bitmap2.recycle()
                } finally {
                    ocrInFlight.set(false)
                }
            }
        }.onFailure { t ->
            bitmap1?.recycle()
            bitmap2.recycle()
            ocrInFlight.set(false)
            Log.w(TAG, "OCR: worker rejected task: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun recognizeBitmap(bitmap: Bitmap): String {
        val tess = try {
            tesseract()
        } catch (t: Throwable) {
            bitmap.recycle()
            Log.w(TAG, "OCR: Tesseract init failed: ${t.javaClass.simpleName}: ${t.message}")
            return ""
        }

        return try {
            tess.setImage(bitmap)
            tess.getUTF8Text().orEmpty()
        } catch (t: Throwable) {
            Log.w(TAG, "OCR: Tesseract recognition failed: ${t.javaClass.simpleName}: ${t.message}")
            ""
        } finally {
            runCatching { tess.clear() }
            bitmap.recycle()
        }
    }

    private fun decodeOcrPayload(payload: ByteArray): Pair<ByteArray, ByteArray>? = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            check(input.readInt() == OCR2_PAYLOAD_MAGIC) { "bad OCR2 magic" }
            val len1 = input.readInt()
            check(len1 in 1..MAX_OCR_AREA_FRAME)
            val area1 = ByteArray(len1).also { input.readFully(it) }
            val len2 = input.readInt()
            check(len2 in 1..MAX_OCR_AREA_FRAME)
            val area2 = ByteArray(len2).also { input.readFully(it) }
            area1 to area2
        }
    }.getOrNull()

    private fun formatArea1RoadText(raw: String): String {
        // Yahoo! NAVI's Japanese "着・" is not recognized by the English Tesseract model,
        // but with the current crop/whitelist it is emitted as one OCR character between the
        // time and remaining distance. Ignore exactly that one character regardless of what
        // it was recognized as, then parse only the distance at the end of the remaining text.
        val timeMatch = OCR_AREA1_TIME_REGEX.find(raw) ?: return ""
        val time = timeMatch.groupValues[1]
        val afterTime = raw.substring(timeMatch.range.last + 1).trimStart()
        if (afterTime.isEmpty()) return ""
        val distanceSource = afterTime.substring(1)
        val distance = extractArea1Distance(distanceSource) ?: return ""
        return "${time}着・$distance"
    }

    private fun extractArea1Distance(raw: String): String? {
        val match = OCR_AREA1_DISTANCE_REGEX.find(raw) ?: return null
        val value = match.groupValues[1]
        val unit = match.groupValues[2].lowercase()
        val numeric = value.toDoubleOrNull() ?: return null

        val valid = when (unit) {
            "m" -> !value.contains('.') && value.length <= 3 && numeric in 1.0..999.0
            "km" -> {
                val integerDigits = value.substringBefore('.').length
                val decimalDigits = value.substringAfter('.', "").length
                integerDigits <= 4 && decimalDigits <= 1 && numeric in 1.0..9999.9
            }
            else -> false
        }
        if (!valid) return null
        return value + unit
    }

    private fun extractDistance(raw: String): String? {
        val match = OCR_DISTANCE_REGEX.find(raw) ?: return null
        val value = match.groupValues[1]
        val unit = match.groupValues[2].lowercase()
        return value + unit
    }

    /** Protocol safety only: preserve OCR meaning, remove controls, and cap at 64 UTF-8 bytes. */
    private fun sanitizeRoadText(raw: String): String {
        val clean = buildString(raw.length) {
            raw.forEach { ch ->
                when (ch) {
                    '\n', '\r', '\t' -> append(' ')
                    else -> if (ch.code !in 0x00..0x1F && ch.code != 0x7F) append(ch)
                }
            }
        }
        val out = StringBuilder(clean.length)
        var byteCount = 0
        var i = 0
        while (i < clean.length) {
            val codePoint = clean.codePointAt(i)
            val piece = String(Character.toChars(codePoint))
            val pieceBytes = piece.toByteArray(StandardCharsets.UTF_8).size
            if (byteCount + pieceBytes > ROAD_MAX_UTF8_BYTES) break
            out.append(piece)
            byteCount += pieceBytes
            i += Character.charCount(codePoint)
        }
        return out.toString().trim()
    }

    override fun pollRoadText(): String? {
        if (!ocrGateActive) return null
        return synchronized(roadTextLock) {
            val value = pendingRoadText
            pendingRoadText = null
            value
        }
    }

    /** Tell the helper to move the foreground app onto the dash display and start encoding. */
    fun promote(component: String) {
        DiagnosticFlightRecorder.record("VD", "PROMOTE component=$component restart=$restartAppOnVd")
        resetOcrGate("promote")
        desiredComponent = component
        send("PROMOTE $component ${if (restartAppOnVd) 1 else 0}\n")
    }

    /**
     * Use the same loopback -> helper -> `input -d dashDisplayId tap` route as the proven center
     * test. UP/DOWN only select a different final-output point.
     */
    override fun tapDashPoint(up: Boolean, markerFrames: Int): Boolean {
        if (socket == null) {
            Log.w(TAG, "dash tap: helper socket is not connected")
            return false
        }

        val xPercent = (if (up) zoomInXPercent else zoomOutXPercent).coerceIn(0, 100)
        val yPercent = (if (up) zoomInYPercent else zoomOutYPercent).coerceIn(0, 100)
        val label = if (up) "UP" else "DOWN"

        val usableWidth = (DashResolution.OUTPUT_WIDTH - leftMargin).coerceAtLeast(1)
        val usableHeight = (DashResolution.OUTPUT_HEIGHT - bottomMargin).coerceAtLeast(1)
        tapMarkerX = leftMargin.coerceAtLeast(0) + percentToIndex(usableWidth, xPercent)
        tapMarkerY = percentToIndex(usableHeight, yPercent)
        tapMarkerFramesRemaining = markerFrames.coerceAtLeast(1)

        send("TAP_PERCENT $xPercent $yPercent $label\n")
        return true
    }

    private fun percentToIndex(size: Int, percent: Int): Int {
        if (size <= 1) return 0
        return (((size - 1).toLong() * percent.coerceIn(0, 100) + 50L) / 100L).toInt()
    }

    /** Draw a small marker on a throw-away decode/re-encode only; never mutate [latest]. */
    private fun withTapMarker(frame: ByteArray): ByteArray {
        val remaining = tapMarkerFramesRemaining
        if (remaining <= 0) return frame
        tapMarkerFramesRemaining = remaining - 1
        return runCatching {
            val decoded = BitmapFactory.decodeByteArray(frame, 0, frame.size) ?: return frame
            val copy = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(copy)
            canvas.drawBitmap(decoded, 0f, 0f, null)
            decoded.recycle()
            val x = tapMarkerX.coerceIn(0, copy.width - 1).toFloat()
            val y = tapMarkerY.coerceIn(0, copy.height - 1).toFloat()
            canvas.drawCircle(x, y, TAP_MARKER_RADIUS_PX, markerFillPaint)
            canvas.drawCircle(x, y, TAP_MARKER_RADIUS_PX, markerStrokePaint)
            val out = ByteArrayOutputStream(frame.size + 1024)
            copy.compress(Bitmap.CompressFormat.JPEG, TAP_MARKER_JPEG_QUALITY, out)
            copy.recycle()
            out.toByteArray()
        }.getOrElse {
            Log.w(TAG, "tap marker render failed: ${it.javaClass.simpleName}: ${it.message}")
            frame
        }
    }

    /** Tell the helper to move the app back to the phone and stop encoding. */
    fun demote() {
        DiagnosticFlightRecorder.record("VD", "DEMOTE")
        resetOcrGate("demote")
        desiredComponent = null
        latest = null
        staticFrameLogged = false
        socketDownLogged = false
        tapMarkerFramesRemaining = 0
        send("DEMOTE\n")
    }

    /** End the session and release the helper. */
    fun quit() {
        DiagnosticFlightRecorder.record("VD", "QUIT helper/display")
        resetOcrGate("quit")
        send("QUIT\n")
        stop()
    }

    private fun send(line: String) {
        val s = socket ?: run {
            Log.d(TAG, "dash stream: queued ${line.trim()} until helper connects")
            return
        }
        send(s, line)
    }

    private fun syncDesiredState(s: Socket) {
        val component = desiredComponent
        if (component == null) {
            send(s, "DEMOTE\n")
        } else {
            Log.d(TAG, "dash stream: syncing pending PROMOTE $component")
            send(s, "PROMOTE $component ${if (restartAppOnVd) 1 else 0}\n")
        }
        if (ocrEnabled && ocrGateActive) {
            // PROMOTE/DEMOTE above always reset helper OCR. Replay START only when this same DASH
            // session had already passed its grace period before the loopback socket reconnected.
            send(s, "OCR_START\n")
        }
    }

    private fun send(s: Socket, line: String) {
        synchronized(writeLock) {
            runCatching { s.getOutputStream().apply { write(line.toByteArray()); flush() } }
                .onFailure {
                    Log.d(TAG, "dash stream: send failed for ${line.trim()}: ${it.javaClass.simpleName}; reconnecting")
                    runCatching { s.close() }
                }
        }
    }

    private val writeLock = Any()

    override fun stop() {
        running = false
        ocrGateActive = false
        runCatching { socket?.close() }
        runCatching { ocrSocket?.close() }
        socket = null
        ocrSocket = null
        desiredComponent = null
        latest = null
        staticFrameLogged = false
        socketDownLogged = false
        tapMarkerFramesRemaining = 0
        synchronized(roadTextLock) {
            pendingRoadText = null
            hasPublishedRoadText = false
        }
        cachedOcrArea1Jpeg = null
        cachedOcrArea1Text = ""
        // Do not recycle Tesseract while a native recognition call is in progress. Queue cleanup
        // behind the single OCR worker, then stop accepting new work.
        runCatching {
            ocrWorker.execute {
                synchronized(recognizerLock) {
                    runCatching { tessBaseApi?.recycle() }
                    tessBaseApi = null
                }
            }
        }
        ocrWorker.shutdown()
    }

    private companion object {
        const val TAG = "Pillion"
        const val MAX_FRAME = 4 * 1024 * 1024
        const val MAX_OCR_FRAME = 2 * 1024 * 1024
        const val ROAD_MAX_UTF8_BYTES = 64
        const val OCR2_PAYLOAD_MAGIC = 0x4F435232
        const val OCR_AREA_SEPARATOR = "　　"
        val OCR_AREA1_TIME_REGEX = Regex("""^\s*(\d{1,2}:\d{2})""")
        val OCR_AREA1_DISTANCE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(km|m)\s*$""", RegexOption.IGNORE_CASE)
        val OCR_DISTANCE_REGEX = Regex("""\b(\d+(?:\.\d+)?)\s*(km|m)\b""", RegexOption.IGNORE_CASE)
        const val OCR_TESS_WHITELIST = "0123456789:.kmKM"
        const val TESS_LANGUAGE = "eng"
        const val TESS_DATA_ROOT_DIR = "tesseract-fast-4.1.0"
        const val TESS_DATA_FILE = "eng.traineddata"
        const val TESS_DATA_FILE_BYTES = 4_113_088L
        const val MAX_OCR_AREA_FRAME = 512 * 1024
        const val CONNECT_TIMEOUT_MS = 2000
        const val RETRY_MS = 300L
        const val OCR_IDLE_POLL_MS = 250L
        const val OCR_START_DELAY_MS = 5_000L

        const val TAP_MARKER_RADIUS_PX = 12f
        const val TAP_MARKER_JPEG_QUALITY = 90

        /** Log only; a connected helper may legitimately keep a static frame for any duration. */
        const val STATIC_FRAME_LOG_MS = 1500L

        /** Real connection loss gets a short reconnect window before the legacy mirror fallback. */
        const val SOCKET_DOWN_GRACE_MS = 5000L
    }
}
