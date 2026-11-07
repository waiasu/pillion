package app.pillion.core

import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.ServiceType
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates a mirroring session: connect -> handshake -> stream screen frames -> report state.
 * Depends only on [ByteChannel] and [ScreenSource] (DIP) — knows nothing about RFCOMM, EASession,
 * MediaProjection or ReplayKit, so the same engine drives both platforms.
 */
class MirrorEngine(
    private val channel: ByteChannel,
    private val screen: ScreenSource,
    private val maxFps: Int = 15,
    private val imageType: Int = 3, // NAVIGATION_EXPANDED
    // VD can start before the bike is powered on; MediaProjection keeps fail-fast semantics.
    private val retryInitialConnection: Boolean = false,
) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val minIntervalMs: Long = if (maxFps in 1..59) 1000L / maxFps else 0L
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var lastFrameKb = 0
    private var seq = 1
    private val writeLock = Any()
    private val stopGate = Any()
    @Volatile private var stopRequested = false
    @Volatile private var pendingImageAckSeq = -1
    @Volatile private var imageWriteInProgress = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        running = true
        stopRequested = false
        _state.value = MirrorState.Connecting
        job = scope.launch(Dispatchers.Default) {
            try {
                // Start capture FIRST: a MediaProjection token goes stale if the virtual display
                // isn't created promptly, so we must not defer it behind the Bluetooth handshake.
                Logger.d("session: starting screen capture")
                screen.start()
                // Reconnect loop: the dash can drop the Bluetooth link on its own (e.g. some dashes
                // terminate it when the phone locks — HCI peer-terminated), which breaks the RFCOMM
                // write mid-stream. Rather than failing the whole session, re-open + re-handshake and
                // resume, so a drop is a brief reconnect instead of a dead session. Only stop() ends it.
                var connectedOnce = false
                var initialFailures = 0
                while (running && !stopRequested) {
                    try {
                        _state.value = MirrorState.Connecting
                        if (!running || stopRequested) break
                        Logger.d("session: connecting transport")
                        channel.open()
                        // Stop may arrive while a blocking RFCOMM connect is in progress.
                        // Never start a handshake on a transport opened after shutdown.
                        if (!running || stopRequested) break
                        val reader = FrameReader(channel)
                        Logger.d("session: handshake")
                        val dashSize = performHandshakeWithTimeout(reader)
                        if (!running || stopRequested) break
                        Logger.d("session: dash size ${dashSize.width}x${dashSize.height}")
                        screen.resizeOutput(dashSize.width, dashSize.height)
                        if (!connectedOnce && initialFailures > 0) {
                            Logger.d("session: CCU connected after $initialFailures initial failure(s)")
                        }
                        connectedOnce = true
                        seq = 1 // fresh sequence per (re)connect, matching a new session
                        Logger.d("session: streaming")
                        streamLoop(reader)
                    } catch (t: Throwable) {
                        if (!running || stopRequested) break
                        if (!connectedOnce && !retryInitialConnection) {
                            throw t // normal mirror: first failure remains fatal
                        }
                        if (!connectedOnce) {
                            initialFailures++
                            // An unpowered bike is expected in VD mode. Log the transition once,
                            // then only occasional milestones rather than every failed dial.
                            if (initialFailures == 1 || initialFailures % 10 == 0) {
                                Logger.d("session: waiting for CCU (initial attempt $initialFailures failed: ${t.javaClass.simpleName})")
                            }
                        } else {
                            Logger.e("session dropped; reconnecting in ${RECONNECT_DELAY_MS}ms", t)
                        }
                        runCatching { channel.close() }
                        // Stop must end a pending initial-connect wait without a long join.
                        var remaining = RECONNECT_DELAY_MS
                        while (running && !stopRequested && remaining > 0) {
                            val step = minOf(100L, remaining)
                            sleepMs(step)
                            remaining -= step
                        }
                    }
                }
            } catch (t: Throwable) {
                Logger.e("session failed", t)
                if (running) _state.value = MirrorState.Error(t.message ?: "connection lost")
            } finally {
                running = false
                runCatching { screen.stop() }
                runCatching { channel.close() }
            }
        }
    }

    /**
     * FrameReader uses blocking socket reads, so a coroutine timeout cannot interrupt
     * a missing ESN_UPDATE / SEC_DATA response. Close RFCOMM from an independent timer:
     * this unblocks read(), allowing the existing reconnect loop to retry authentication.
     */
    private suspend fun performHandshakeWithTimeout(reader: FrameReader): DashSize = coroutineScope {
        val watchdog = launch(Dispatchers.Default) {
            delay(HANDSHAKE_TIMEOUT_MS)
            if (running) {
                Logger.e("session: HANDSHAKE_TIMEOUT after ${HANDSHAKE_TIMEOUT_MS}ms; reconnecting")
                runCatching { channel.close() }
            }
        }
        try {
            Handshake(channel, reader).perform()
        } finally {
            watchdog.cancel()
        }
    }

    fun stop() {
        // Prevent a new IMAGE while keeping the receiver alive just long enough to
        // collect the last IMAGE_ACK. Closing RFCOMM immediately after IMAGE write
        // was correlated with a CCU that reconnects but never sends ESN_UPDATE.
        val firstStop = synchronized(stopGate) {
            if (stopRequested) false else {
                stopRequested = true
                true
            }
        }
        if (!firstStop) return
        val began = nowMs()
        if (pendingImageAckSeq != -1 || imageWriteInProgress) {
            while ((pendingImageAckSeq != -1 || imageWriteInProgress) &&
                nowMs() - began < STOP_ACK_GRACE_MS) {
                sleepMs(5)
            }
            Logger.d("session: Stop final IMAGE_ACK wait=${nowMs() - began}ms " +
                "completed=${pendingImageAckSeq == -1 && !imageWriteInProgress}")
        }
        running = false
        runCatching { channel.close() } // unblocks a missing ACK or handshake read
        job = null
        _state.value = MirrorState.Idle
    }

    private suspend fun streamLoop(reader: FrameReader) = coroutineScope {
        // FrameReader.next() is blocking I/O: a coroutine timeout around it would not interrupt
        // the read. Keep a single dedicated reader and let the sender time out on its channel.
        val incomingFrames = Channel<NaviFrameView>(Channel.BUFFERED)
        // Event-driven serial worker. Preserve press order without delaying Bluetooth responses.
        val tapEvents = Channel<Boolean>(Channel.UNLIMITED)
        val tapWorker = launch(Dispatchers.IO) {
            for (up in tapEvents) {
                if (running) screen.tapDashPoint(up)
            }
        }
        val receiver = launch(Dispatchers.IO) {
            try {
                while (running && isActive) {
                    val next = reader.next()
                    if (next.serviceType == ServiceType.IMAGE_ACK &&
                        imageAckSequence(next.payload) == pendingImageAckSeq) {
                        pendingImageAckSeq = -1
                    }
                    if (next.serviceType == 51 || next.serviceType == 52) {
                        // Acknowledge even during STATUS/PROMOTING; never wedge the CCU pressed effect.
                        synchronized(writeLock) {
                            channel.write(NaviLiteCodec.build(FRAME_TYPE_PHONE, ServiceType.ZOOM, PDT_POINTER, ZOOM_PAYLOAD))
                        }
                        // Never wait for shell input on the RFCOMM reader.
                        tapEvents.trySend(next.serviceType == 51)
                    } else incomingFrames.send(next)
                }
            } catch (t: Throwable) {
                if (running) incomingFrames.close(t)
            } finally {
                incomingFrames.close()
            }
        }
        try {
        // Stop-and-wait: send the freshest frame, then block on its IMAGE_ACK before sending the
        // next, so exactly one frame is ever on the link. A sliding window (sending N+1 before N's
        // ACK returns) buffered a frame ahead and added a whole round-trip of latency on slower
        // dashes — visible as constant lag regardless of fps — which isn't worth the peak throughput.
        var lastSend = 0L
        var waitedForFrame = false
        var acks = 0
        var windowStart = nowMs()
        var ackMsTotal = 0L
        var ackMsMax = 0L
        var consecutiveTimeouts = 0
        while (running && !stopRequested) {
            // Pace first, then sample the latest frame. Sampling before the FPS wait
            // held an older JPEG even when the helper had already supplied a newer one.
            if (minIntervalMs > 0L) {
                val wait = minIntervalMs - (nowMs() - lastSend)
                if (wait > 0L) sleepMs(wait)
            }
            if (!running || stopRequested) break
            val jpeg = screen.latestFrame()
            if (jpeg == null) {
                if (!waitedForFrame) {
                    Logger.d("session: waiting for first screen frame")
                    waitedForFrame = true
                }
                sleepMs(15)
                continue
            }
            val sentAt = nowMs()
            lastSend = sentAt
            val sentSeq = seq and 0xffff
            // This gate makes Stop and "begin IMAGE write" mutually exclusive.
            // Stop can never miss a write that starts just as it checks the ACK.
            val maySend = synchronized(stopGate) {
                if (stopRequested) false else {
                    pendingImageAckSeq = sentSeq
                    imageWriteInProgress = true
                    true
                }
            }
            if (!maySend) break
            try {
                sendImage(jpeg)
            } finally {
                imageWriteInProgress = false
            }
            lastFrameKb = jpeg.size / 1024
            if (seq == 2) Logger.d("session: first image sent (${jpeg.size} bytes)")
            // Match only this image's ACK. A late or malformed ACK must not unblock a later image,
            // and unrelated messages must not restart the 2-second deadline.
            val acknowledged = awaitImageAck(incomingFrames, sentSeq, ACK_TIMEOUT_MS) { ignored ->
                Logger.d("session: ignoring IMAGE_ACK expected=$sentSeq actual=${ignored ?: "malformed"}")
            }
            if (!running) break
            if (!acknowledged) {
                consecutiveTimeouts++
                if (consecutiveTimeouts == 1 || consecutiveTimeouts % 10 == 0) {
                    Logger.e("session: IMAGE_ACK_TIMEOUT seq=$sentSeq after ${ACK_TIMEOUT_MS}ms (consecutive=$consecutiveTimeouts)")
                }
                continue // Send the freshest image without reopening a healthy RFCOMM link.
            }
            if (consecutiveTimeouts > 0) {
                Logger.d("session: IMAGE_ACK recovered seq=$sentSeq after $consecutiveTimeouts timeout(s)")
                consecutiveTimeouts = 0
            }
            val ackMs = nowMs() - sentAt
            ackMsTotal += ackMs
            if (ackMs > ackMsMax) ackMsMax = ackMs
            acks++
            val elapsed = nowMs() - windowStart
            if (elapsed >= 1000) {
                val avgAckMs = if (acks > 0) ackMsTotal / acks else 0L
                Logger.d("session: $acks fps, $lastFrameKb KB/frame, ack ${avgAckMs}ms avg/${ackMsMax}ms max")
                _state.value = MirrorState.Streaming(acks * 1000.0 / elapsed, lastFrameKb)
                acks = 0
                ackMsTotal = 0L
                ackMsMax = 0L
                windowStart = nowMs()
            }
        }
        } finally {
            // Closing the transport is essential: cancelling a coroutine alone cannot unblock
            // a socket InputStream.read() on all Android Bluetooth implementations.
            runCatching { channel.close() }
            receiver.cancel()
            incomingFrames.cancel()
            tapEvents.cancel()
            tapWorker.cancel()
        }
    }

    private fun sendImage(jpeg: ByteArray) {
        val payload = ByteArray(3 + jpeg.size)
        payload[0] = imageType.toByte()
        payload[1] = (seq and 0xff).toByte()
        payload[2] = ((seq ushr 8) and 0xff).toByte()
        jpeg.copyInto(payload, 3)
        seq++
        synchronized(writeLock) {
            channel.write(NaviLiteCodec.build(FRAME_TYPE_PHONE, ServiceType.IMAGE, PDT_POINTER, payload))
        }
    }

    private companion object {
        /** Wait between a dropped link and a reconnect attempt (the dash needs a moment to settle). */
        const val RECONNECT_DELAY_MS = 1500L
        const val HANDSHAKE_TIMEOUT_MS = 5000L
        const val ACK_TIMEOUT_MS = 2000L
        const val STOP_ACK_GRACE_MS = 500L
        val ZOOM_PAYLOAD = byteArrayOf(0x07, 0x19, 0x06, 0x00, 0x30, 0x2e, 0x32, 0x20, 0x6d, 0x69)
    }
}

/** Returns only for the expected IMAGE_ACK or its deadline; closed transports propagate to reconnect. */
internal suspend fun awaitImageAck(
    incomingFrames: ReceiveChannel<NaviFrameView>,
    expectedSeq: Int,
    timeoutMs: Long,
    onIgnoredAck: (Int?) -> Unit = {},
): Boolean = withTimeoutOrNull(timeoutMs) {
    var matched = false
    while (!matched) {
        val incoming = incomingFrames.receiveCatching().getOrThrow()
        if (incoming.serviceType == ServiceType.IMAGE_ACK) {
            val actual = imageAckSequence(incoming.payload)
            if (actual == expectedSeq) matched = true else onIgnoredAck(actual)
        }
    }
    matched
} == true

/** IMAGE_ACK echoes the JPEG's 16-bit little-endian sequence number. */
internal fun imageAckSequence(payload: ByteArray): Int? =
    if (payload.size >= 2) (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)
    else null
