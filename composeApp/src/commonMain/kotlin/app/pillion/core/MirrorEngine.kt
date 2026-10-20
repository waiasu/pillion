package app.pillion.core

import app.pillion.protocol.FRAME_TYPE_PHONE
import app.pillion.protocol.NaviLiteCodec
import app.pillion.protocol.PDT_POINTER
import app.pillion.protocol.ServiceType
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates a mirroring session: connect -> handshake -> stream screen frames -> report state.
 * Depends only on [ByteChannel] and [ScreenSource] (DIP) — knows nothing about RFCOMM, EASession,
 * MediaProjection or ReplayKit, so the same engine drives both platforms.
 */
class MirrorEngine(
    private val channel: ByteChannel,
    private val screen: ScreenSource,
    private val maxFps: Double = 3.0,
    private val imageType: Int = 3, // NAVIGATION_EXPANDED
    private val keepScreenAliveOnFailure: Boolean = false,
    private val startScreenOnStart: Boolean = true,
) {
    private val _state = MutableStateFlow<MirrorState>(MirrorState.Idle)
    val state: StateFlow<MirrorState> = _state.asStateFlow()

    private val minIntervalMs: Long = if (maxFps > 0.0 && maxFps < 60.0) (1000.0 / maxFps).toLong().coerceAtLeast(1L) else 0L
    private var job: Job? = null
    @Volatile private var running = false
    @Volatile private var lastFrameKb = 0
    private var seq = 1

    fun start(scope: CoroutineScope) {
        if (job != null) return
        running = true
        _state.value = MirrorState.Connecting
        job = scope.launch(Dispatchers.Default) {
            var unexpectedFailure = false
            try {
                // Start capture FIRST: a MediaProjection token goes stale if the virtual display
                // isn't created promptly, so we must not defer it behind the Bluetooth handshake.
                if (startScreenOnStart) {
                    Logger.d("session: starting screen capture")
                    screen.start()
                } else {
                    Logger.d("session: reusing existing screen capture")
                }
                Logger.d("session: connecting transport")
                Logger.trail("TRANSPORT CONNECT_START")
                // Keep optional features (OCR etc.) outside the established connect/handshake path.
                // A surviving ScreenSource can use this hook to reset any post-connect work before
                // every initial connection or reconnect attempt without changing RFCOMM itself.
                screen.onTransportConnecting()
                channel.open()
                screen.onTransportConnected()
                Logger.trail("TRANSPORT CONNECTED")
                val reader = FrameReader(channel)
                Logger.d("session: handshake")
                Logger.trail("HANDSHAKE START")
                Handshake(channel, reader).perform()
                Logger.trail("HANDSHAKE OK")
                Logger.d("session: streaming")
                streamLoop(reader)
            } catch (t: Throwable) {
                unexpectedFailure = running
                Logger.e("session failed", t)
                Logger.trail("SESSION FAILED ${t::class.simpleName ?: "Throwable"}: ${t.message ?: "-"}")
                if (running) _state.value = MirrorState.Error(t.message ?: "connection lost")
            } finally {
                // Keep the surviving VD/Helper, but stop producing unsent JPEGs.
                runCatching { screen.onTransportDisconnected() }
                running = false
                if (!unexpectedFailure || !keepScreenAliveOnFailure) runCatching { screen.stop() }
                runCatching { channel.close() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { channel.close() } // unblocks the blocking reader
        job = null
        _state.value = MirrorState.Idle
    }

    private suspend fun streamLoop(reader: FrameReader) = coroutineScope {
        // Keep the blocking transport reader on a dedicated coroutine. The sender can abandon an
        // ACK wait after 2 seconds without closing/reconnecting RFCOMM, while late ACKs and
        // stick input continue to be received by the same FrameReader.
        val incomingFrames = Channel<NaviFrameView>(Channel.BUFFERED)
        val receiver = launch(Dispatchers.IO) {
            try {
                while (running && isActive) incomingFrames.send(reader.next())
            } catch (t: Throwable) {
                if (running) incomingFrames.close(t)
            } finally {
                incomingFrames.close()
            }
        }
        try {
        // Stop-and-wait during healthy operation: exactly one JPEG awaits an ACK. On a 2-second
        // timeout, skip that ACK and send the freshest next frame, just as StreetCross does.
        // Sequence matching prevents a late ACK from acknowledging the wrong image.
        var lastSend = 0L
        var waitedForFrame = false
        var acks = 0
        var windowStart = nowMs()
        var ackMsTotal = 0L
        var ackMsMax = 0L
        var firstAckRecorded = false
        while (running) {
            val jpeg = screen.latestFrame()
            if (jpeg == null) {
                if (!waitedForFrame) {
                    Logger.d("session: waiting for first screen frame")
                    waitedForFrame = true
                }
                sleepMs(15)
                continue
            }
            if (minIntervalMs > 0L) {
                val wait = minIntervalMs - (nowMs() - lastSend)
                if (wait > 0L) sleepMs(wait)
            }
            val sentAt = nowMs()
            lastSend = sentAt
            val sentSeq = seq and 0xffff
            sendImage(jpeg)
            lastFrameKb = jpeg.size / 1024
            if (seq == 2) {
                Logger.d("session: first image sent (${jpeg.size} bytes)")
                Logger.trail("IMAGE FIRST_SENT bytes=${jpeg.size}")
            }
            // Deadline is relative to the completed write. Unrelated messages and late ACKs
            // must not reset the timer; a missing ACK must not freeze the image stream forever.
            val acknowledged = withTimeoutOrNull(2_000L) {
                var matched = false
                while (running && !matched) {
                    val incoming = incomingFrames.receiveCatching().getOrThrow()
                    if (incoming.serviceType == ServiceType.IMAGE_ACK) {
                        val ackSeq = imageAckSequence(incoming.payload)
                        if (ackSeq == sentSeq) {
                            matched = true
                        } else {
                            // Ignore delayed or malformed ACK without extending the deadline.
                        }
                    } else {
                        handleStickInput(incoming)
                    }
                }
                matched
            } == true
            if (!running) break
            if (!acknowledged) {
                Logger.e("session: IMAGE_ACK_TIMEOUT seq=$sentSeq after 2000ms")
                Logger.trail("IMAGE ACK_TIMEOUT seq=$sentSeq timeoutMs=2000")
                continue
            }
            // This is the only signal OCR uses to start its post-connect grace period. Until this
            // point the connect/handshake/image path is identical to the v17 path.
            if (!firstAckRecorded) {
                firstAckRecorded = true
                Logger.trail("IMAGE FIRST_ACK")
            }
            screen.onImageAck()
            // OCR ROAD writes stay serialized on the existing stop-and-wait loop, immediately after
            // a valid IMAGE_ACK, so no second coroutine can interleave Bluetooth writes.
            screen.pollRoadText()?.let { roadText ->
                channel.write(
                    NaviLiteCodec.build(
                        FRAME_TYPE_PHONE,
                        ServiceType.ROAD,
                        PDT_POINTER,
                        roadText.encodeToByteArray(),
                    )
                )
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
            // A coroutine cancel alone cannot interrupt InputStream.read(). Closing the transport
            // also unblocks the reader if Stop or another failure ends this streaming session.
            runCatching { channel.close() }
            receiver.cancel()
            incomingFrames.cancel()
        }
    }


    /**
     * XMAX stick integration: only UP/DOWN zoom requests are consumed by Pillion.
     * Center press and every other dash-side request are deliberately ignored so the
     * vehicle's own menu behavior remains untouched.
     */
    private fun handleStickInput(frame: NaviFrameView) {
        if (frame.serviceType != 51 && frame.serviceType != 52) return

        // Reply with the zoom-level update already accepted by the dash, then inject the configured
        // UP/DOWN tap into the dedicated display. No other stick service is handled here.
        val zoomPayload = byteArrayOf(
            0x07, 0x19, 0x06, 0x00,
            0x30, 0x2e, 0x32, 0x20, 0x6d, 0x69,
        )
        channel.write(
            NaviLiteCodec.build(
                FRAME_TYPE_PHONE,
                ServiceType.ZOOM,
                PDT_POINTER,
                zoomPayload,
            )
        )

        val markerFrames = kotlin.math.ceil(maxFps.coerceAtLeast(0.1) * 0.5).toInt().coerceAtLeast(1)
        screen.tapDashPoint(frame.serviceType == 51, markerFrames)
    }

    private fun sendImage(jpeg: ByteArray) {
        val payload = ByteArray(3 + jpeg.size)
        payload[0] = imageType.toByte()
        payload[1] = (seq and 0xff).toByte()
        payload[2] = ((seq ushr 8) and 0xff).toByte()
        jpeg.copyInto(payload, 3)
        seq++
        channel.write(NaviLiteCodec.build(FRAME_TYPE_PHONE, ServiceType.IMAGE, PDT_POINTER, payload))
    }
}

/** IMAGE_ACK carries the 16-bit little-endian sequence number of the acknowledged JPEG. */
internal fun imageAckSequence(payload: ByteArray): Int? =
    if (payload.size >= 2) (payload[0].toInt() and 0xff) or ((payload[1].toInt() and 0xff) shl 8)
    else null
