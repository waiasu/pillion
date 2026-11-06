package app.pillion.android

import android.util.Log
import android.os.SystemClock
import app.pillion.core.ScreenSource

/**
 * v40's STATUS -> PROMOTE -> DASH -> DEMOTE behaviour, without replacing test-base's
 * loopback helper, CCU size negotiation, or RFCOMM/ACK loop.
 */
class DashModeScreenSource(
    private val dash: DashStreamScreenSource,
) : ScreenSource {
    private enum class Mode { STATUS, PROMOTING, DASH }
    private val status = StatusScreenSource()
    @Volatile private var mode = Mode.STATUS
    @Volatile private var promoteStartFrameSeq = 0L
    @Volatile private var dashReadyAtMs = Long.MAX_VALUE

    override fun start() {
        status.start()
        dash.start()
    }

    override fun resizeOutput(width: Int, height: Int) {
        status.resizeOutput(width, height)
        dash.resizeOutput(width, height)
    }

    override fun latestFrame(): ByteArray? = when (mode) {
        Mode.STATUS -> status.latestFrame()
        Mode.PROMOTING -> {
            val fresh = dash.receivedFrameSequence() > promoteStartFrameSeq
            val frame = if (fresh) dash.latestFrame() else null
            if (frame == null) status.latestFrame()
            else {
                mode = Mode.DASH
                dashReadyAtMs = SystemClock.elapsedRealtime() + 1_000L
                Log.i(TAG, "VD first fresh frame; STATUS -> DASH")
                frame
            }
        }
        Mode.DASH -> dash.latestFrame() ?: run {
            // Helper transport/size gate was lost. Re-arm the fresh-frame and settle gate.
            promoteStartFrameSeq = dash.receivedFrameSequence()
            dashReadyAtMs = Long.MAX_VALUE
            mode = Mode.PROMOTING
            status.reset()
            status.latestFrame()
        }
    }

    override fun tapDashPoint(up: Boolean) {
        if (mode != Mode.DASH || SystemClock.elapsedRealtime() < dashReadyAtMs) return
        dash.tapDashPoint(up)
    }

    fun promote(component: String) {
        dashReadyAtMs = Long.MAX_VALUE
        // Arm the cover *before* sending PROMOTE. Do not reveal the old task's
        // JPEG during relocation or a delayed VD restart.
        promoteStartFrameSeq = dash.receivedFrameSequence()
        status.reset()
        mode = Mode.PROMOTING
        dash.promote(component)
        Log.i(TAG, "STATUS -> PROMOTING $component")
    }

    fun demote() {
        dashReadyAtMs = Long.MAX_VALUE
        mode = Mode.STATUS
        status.reset()
        dash.demote()
        Log.i(TAG, "DASH -> STATUS")
    }

    fun quit() = dash.quit()

    override fun stop() {
        dashReadyAtMs = Long.MAX_VALUE
        dash.stop()
        status.stop()
    }

    private companion object { const val TAG = "Pillion" }
}
