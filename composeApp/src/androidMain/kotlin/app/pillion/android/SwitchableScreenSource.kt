package app.pillion.android

import app.pillion.core.ScreenSource

/**
 * A [ScreenSource] that the engine pulls from.
 *
 * Before the first dedicated-dash promotion it behaves like the original phone mirror. Once dash
 * mode has been used, MediaProjection is no longer treated as reusable after unlock; DEMOTE selects
 * a generated STATUS frame instead. Re-locking uses the existing [promote] path unchanged, so the
 * long-lived Bluetooth/NaviLite session alternates DASH <-> STATUS without trying to recreate a
 * MediaProjection grant.
 */
class SwitchableScreenSource(
    private val mirror: ScreenSource,
    private val dash: DashStreamScreenSource,
    private val restartAppOnVd: Boolean = false,
) : ScreenSource {

    private enum class Mode { MIRROR, DASH, STATUS }

    private val status = StatusScreenSource()
    private val reloadStatus = StatusScreenSource(RELOAD_MESSAGE)
    @Volatile private var reloadMask = false
    @Volatile private var reloadMaskGeneration = 0L
    @Volatile private var reloadMaskStartFrameSeq = 0L
    @Volatile private var mode = Mode.MIRROR
    @Volatile private var dashFallback = Mode.MIRROR

    override fun start() {
        mirror.start()
        dash.start() // connects to the helper's loopback socket; stays idle until promoted
        status.start()
        reloadStatus.start()
    }

    override fun latestFrame(): ByteArray? {
        if (reloadMask) {
            // Restart ON: the helper suppresses restart-time VD frames until the fresh task has
            // actually been confirmed. Therefore the first receive-sequence advance after PROMOTE
            // is the first new DASH frame produced after that task confirmation. Expose it at once.
            if (dash.receivedFrameSequence() > reloadMaskStartFrameSeq) {
                clearReloadMask()
            } else {
                return reloadStatus.latestFrame()
            }
        }
        return when (mode) {
        Mode.MIRROR -> mirror.latestFrame()
        Mode.STATUS -> status.latestFrame()
        Mode.DASH -> {
            val frame = dash.latestFrame()
            if (frame != null) {
                // After DASH has produced a real frame, never fall back to a potentially stale
                // MediaProjection frame later in this session.
                dashFallback = Mode.STATUS
                frame
            } else {
                when (dashFallback) {
                    Mode.MIRROR -> mirror.latestFrame()
                    Mode.STATUS, Mode.DASH -> status.latestFrame()
                }
            }
        }
    }
    }

    override fun tapDashPoint(up: Boolean, markerFrames: Int): Boolean {
        if (mode != Mode.DASH) return false
        return dash.tapDashPoint(up, markerFrames)
    }

    override fun onTransportConnecting() {
        // Reconnects reuse this ScreenSource. Reset only the optional OCR side; the proven v17
        // mirror/dash source and helper lifecycle stay untouched.
        dash.onTransportConnecting()
    }

    override fun onImageAck() {
        // ACKs from MIRROR/STATUS must never start OCR. The grace period begins only after DASH is
        // actually promoted and receiving successful IMAGE_ACKs.
        if (mode == Mode.DASH) dash.onImageAck()
    }

    override fun pollRoadText(): String? = if (mode == Mode.DASH) dash.pollRoadText() else null

    override fun stop() {
        runCatching { mirror.stop() }
        runCatching { dash.stop() }
        runCatching { status.stop() }
        runCatching { reloadStatus.stop() }
    }

    /** Phone locked: promote the foreground app to the dash and stream it. */
    fun promote(component: String) {
        // On the very first transition, preserve the original mirror fallback while the helper is
        // producing its first DASH frame. Every later transition comes from STATUS.
        dashFallback = if (mode == Mode.MIRROR) Mode.MIRROR else Mode.STATUS

        // The optional restart cover is output-only. Arm it before sending PROMOTE so the meter
        // never exposes the transient VD frame while the task-restart path is preparing to run.
        scheduleReloadMaskIfNeeded(component)
        dash.promote(component)
        mode = Mode.DASH
    }

    /**
     * Phone unlocked: move the app back to display 0, but do not return to MediaProjection.
     * A generated status animation is streamed until the existing lock -> promote path runs again.
     */
    fun demote() {
        clearReloadMask()
        status.reset()
        mode = Mode.STATUS
        dashFallback = Mode.STATUS
        dash.demote()
    }

    /** Transport loss: return the promoted task to display 0 and show the generated waiting frame. */
    fun enterReconnectWait() {
        demote()
    }

    /**
     * Output-only cover for the optional task restart. DASH mode, helper connection, VD state and
     * promote/demote state are deliberately left untouched; only latestFrame() is masked.
     */
    private fun scheduleReloadMaskIfNeeded(component: String) {
        if (!restartAppOnVd || component.substringBefore('/') == "app.pillion") {
            clearReloadMask()
            return
        }
        val generation = reloadMaskGeneration + 1L
        reloadMaskGeneration = generation
        reloadStatus.reset()
        reloadMaskStartFrameSeq = dash.receivedFrameSequence()
        reloadMask = true
        Thread {
            try { Thread.sleep(RELOAD_MASK_DURATION_MS) } catch (_: InterruptedException) { return@Thread }
            if (reloadMaskGeneration == generation) reloadMask = false
        }.apply { name = "pillion-reload-mask"; isDaemon = true; start() }
    }

    private fun clearReloadMask() {
        reloadMaskGeneration++
        reloadMask = false
    }

    companion object {
        private const val RELOAD_MESSAGE = "Reloading app "
        // Normal release is event-driven: new task confirmed by the helper, then one fresh DASH
        // frame received. Keep the old 4 s timer only as a fail-safe so the cover cannot stick.
        private const val RELOAD_MASK_DURATION_MS = 4_000L
    }

    fun debugState(): String = "mode=$mode,dashFallback=$dashFallback"

    /** End the session: stop mirroring and tell the (detached) helper to release the display + exit. */
    fun quit() {
        runCatching { mirror.stop() }
        runCatching { status.stop() }
        runCatching { reloadStatus.stop() }
        runCatching { dash.quit() }
    }
}
