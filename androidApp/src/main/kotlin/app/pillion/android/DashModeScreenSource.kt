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
    @Volatile private var promotingStartedAtMs = 0L
    @Volatile private var promotionWaitLevel = 0
    @Volatile private var lastStatusReportAtMs = 0L

    override fun start() {
        status.start()
        dash.start()
        lastStatusReportAtMs = SystemClock.elapsedRealtime()
        DiagnosticFlightRecorder.record("VD_MODE", "STATUS initialized")
    }

    override fun resizeOutput(width: Int, height: Int) {
        status.resizeOutput(width, height)
        dash.resizeOutput(width, height)
    }

    override fun latestFrame(): ByteArray? = when (mode) {
        Mode.STATUS -> {
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatusReportAtMs >= 30_000L) {
                lastStatusReportAtMs = now
                DiagnosticFlightRecorder.record("VD_MODE", "STATUS waiting: ${dash.debugSnapshot()}")
            }
            status.latestFrame()
        }
        Mode.PROMOTING -> {
            val fresh = dash.receivedFrameSequence() > promoteStartFrameSeq
            val frame = if (fresh) dash.latestFrame() else null
            val now = SystemClock.elapsedRealtime()
            if (frame == null) {
                val waitedMs = now - promotingStartedAtMs
                val level = if (waitedMs >= 15_000L) 2 else if (waitedMs >= 3_000L) 1 else 0
                if (level > promotionWaitLevel) {
                    promotionWaitLevel = level
                    DiagnosticFlightRecorder.record(
                        "VD_MODE",
                        "PROMOTING waiting ${waitedMs}ms fresh=$fresh startSeq=$promoteStartFrameSeq " +
                            dash.debugSnapshot()
                    )
                }
                status.latestFrame()
            } else {
                mode = Mode.DASH
                promotionWaitLevel = 0
                dashReadyAtMs = now + 1_000L
                DiagnosticFlightRecorder.record(
                    "VD_MODE",
                    "PROMOTING -> DASH waitedMs=${now - promotingStartedAtMs} seq=${dash.receivedFrameSequence()}"
                )
                Log.i(TAG, "VD first fresh frame; STATUS -> DASH")
                frame
            }
        }
        Mode.DASH -> dash.latestFrame() ?: run {
            // Helper transport/size gate was lost. Re-arm the fresh-frame and settle gate.
            promoteStartFrameSeq = dash.receivedFrameSequence()
            dashReadyAtMs = Long.MAX_VALUE
            mode = Mode.PROMOTING
            promotingStartedAtMs = SystemClock.elapsedRealtime()
            promotionWaitLevel = 0
            status.reset()
            DiagnosticFlightRecorder.record("VD_MODE", "DASH -> PROMOTING frame lost: ${dash.debugSnapshot()}")
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
        promotingStartedAtMs = SystemClock.elapsedRealtime()
        promotionWaitLevel = 0
        status.reset()
        mode = Mode.PROMOTING
        DiagnosticFlightRecorder.record("VD_MODE", "STATUS -> PROMOTING startSeq=$promoteStartFrameSeq")
        dash.promote(component)
        Log.i(TAG, "STATUS -> PROMOTING $component")
    }

    fun demote() {
        val previousMode = mode
        dashReadyAtMs = Long.MAX_VALUE
        mode = Mode.STATUS
        lastStatusReportAtMs = SystemClock.elapsedRealtime()
        status.reset()
        DiagnosticFlightRecorder.record("VD_MODE", "$previousMode -> STATUS demote")
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
