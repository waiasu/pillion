package app.pillion.android

import android.os.SystemClock
import android.util.Log
import app.pillion.core.DashAutoDriveSettings
import app.pillion.core.ScreenSource

/**
 * STATUS -> PROMOTING -> DASH, with isolated Yahoo text/Drive Mode experiment.
 * No change to reconnect, helper state, or the parent branch's STATUS diagnostics.
 */
class DashModeScreenSource(
    private val dash: DashStreamScreenSource,
    private val autoDrive: DashAutoDriveSettings = DashAutoDriveSettings(),
    private val yahooFeatures: Boolean = false,
) : ScreenSource {
    private enum class Mode { STATUS, PROMOTING, DASH }
    private enum class DrivePhase { DETECTING, NAV, INITIAL, ARRIVED, WAIT_RETURN, ARRIVAL_DONE, DRIVE }
    private val status = StatusScreenSource()
    private val reloadStatus = StatusScreenSource(StatusScreenSource.RELOADING_APP)
    @Volatile private var reloadMaskUntilMs = 0L
    private val roadLock = Any()
    private var phase = DrivePhase.DETECTING
    private var lastRoadSent: String? = null
    private var clearRoadPending = true
    private var promoteAtMs = 0L
    private var arrivedAtMs = 0L
    private var returnUnknownSinceMs = 0L
    private var driveStickReadyAtMs = Long.MAX_VALUE

    @Volatile private var mode = Mode.STATUS
    @Volatile private var promoteStartFrameSeq = 0L
    @Volatile private var dashReadyAtMs = Long.MAX_VALUE
    @Volatile private var promotingStartedAtMs = 0L
    @Volatile private var promotionWaitLevel = 0
    @Volatile private var lastStatusReportAtMs = 0L

    override fun start() {
        status.start()
        reloadStatus.start()
        dash.start()
        YahooRoadText.observe(false)
        synchronized(roadLock) {
            phase = DrivePhase.DETECTING
            lastRoadSent = null
            driveStickReadyAtMs = Long.MAX_VALUE
            clearRoadPending = true
        }
        lastStatusReportAtMs = SystemClock.elapsedRealtime()
        DiagnosticFlightRecorder.record("VD_MODE", "STATUS initialized")
    }

    override fun resizeOutput(width: Int, height: Int) {
        status.resizeOutput(width, height)
        reloadStatus.resizeOutput(width, height)
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
                if (yahooFeatures && now < reloadMaskUntilMs) reloadStatus.latestFrame()
                else status.latestFrame()
            } else {
                reloadMaskUntilMs = 0L
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

    override fun onRoadTransportReady() {
        synchronized(roadLock) {
            lastRoadSent = null
            clearRoadPending = mode == Mode.STATUS
        }
    }

    override fun pollRoadText(): String? = synchronized(roadLock) {
        if (mode == Mode.STATUS) {
            if (!clearRoadPending) return@synchronized null
            clearRoadPending = false
            lastRoadSent = ""
            DiagnosticFlightRecorder.record("ROAD", "queued clear on STATUS")
            return@synchronized ""
        }
        // PROMOTING/VD frame loss must neither touch Yahoo nor advance a timer.
        if (!yahooFeatures || mode != Mode.DASH) return@synchronized null

        val now = SystemClock.elapsedRealtime()
        val observed = YahooRoadText.current()
        when (phase) {
            DrivePhase.DETECTING -> {
                when (observed.page) {
                    YahooPage.NAV -> {
                        phase = DrivePhase.NAV
                        DiagnosticFlightRecorder.record("DRIVE", "PROMOTE classified NAV")
                    }
                    YahooPage.INITIAL -> {
                        phase = DrivePhase.INITIAL
                        DiagnosticFlightRecorder.record("DRIVE", "PROMOTE classified INITIAL")
                    }
                    YahooPage.ARRIVED -> {
                        phase = DrivePhase.ARRIVED
                        arrivedAtMs = now
                        DiagnosticFlightRecorder.record("DRIVE", "PROMOTE classified ARRIVED")
                    }
                    else -> Unit
                }
            }
            DrivePhase.NAV -> {
                if (observed.page == YahooPage.ARRIVED) {
                    phase = DrivePhase.ARRIVED
                    arrivedAtMs = now
                    DiagnosticFlightRecorder.record("DRIVE", "NAV -> ARRIVED")
                }
            }
            DrivePhase.WAIT_RETURN -> {
                // After Finish, Yahoo can move straight to a screen with no recognisable
                // INITIAL controls (and no usable zoom buttons). Treat sustained UNKNOWN as
                // a terminal arrival state rather than reclassifying or injecting taps.
                when (observed.page) {
                    YahooPage.INITIAL -> {
                        returnUnknownSinceMs = 0L
                        phase = DrivePhase.INITIAL
                        DiagnosticFlightRecorder.record("DRIVE", "ARRIVED exit -> INITIAL; start second 10s check")
                    }
                    YahooPage.NAV -> {
                        returnUnknownSinceMs = 0L
                        phase = DrivePhase.NAV
                        DiagnosticFlightRecorder.record("DRIVE", "ARRIVED exit -> NAV")
                    }
                    YahooPage.UNKNOWN -> {
                        if (returnUnknownSinceMs == 0L) returnUnknownSinceMs = now
                        if (now - returnUnknownSinceMs >= ARRIVAL_UNKNOWN_SETTLE_MS) {
                            phase = DrivePhase.ARRIVAL_DONE
                            DiagnosticFlightRecorder.record(
                                "DRIVE", "WAIT_RETURN -> ARRIVAL_DONE (UNKNOWN stable)"
                            )
                        }
                    }
                    else -> returnUnknownSinceMs = 0L
                }
            }
            else -> Unit
        }

        when (phase) {
            DrivePhase.INITIAL -> {
                if (observed.page == YahooPage.INITIAL &&
                    now - promoteAtMs >= DRIVE_WAIT_MS &&
                    now >= dashReadyAtMs &&
                    dash.tapAutoDrive(autoDrive.initialX, autoDrive.initialY)) {
                    phase = DrivePhase.DRIVE
                    driveStickReadyAtMs = now + DRIVE_STICK_SETTLE_MS
                    DiagnosticFlightRecorder.record("DRIVE", "INITIAL -> DRIVE one-shot tap")
                }
            }
            DrivePhase.ARRIVED -> {
                if (observed.page == YahooPage.ARRIVED &&
                    now - arrivedAtMs >= DRIVE_WAIT_MS &&
                    now >= dashReadyAtMs &&
                    dash.tapAutoDrive(autoDrive.arrivedX, autoDrive.arrivedY)) {
                    // The arrival button only closes guidance; it returns to the initial
                    // screen. Reclassify there and perform its separate Drive tap.
                    phase = DrivePhase.WAIT_RETURN
                    promoteAtMs = now
                    returnUnknownSinceMs = 0L
                    DiagnosticFlightRecorder.record("DRIVE", "ARRIVED exit tapped -> WAIT_RETURN; re-arm initial screen decision")
                }
            }
            else -> Unit
        }

        val road = when (phase) {
            DrivePhase.NAV -> if (observed.page == YahooPage.NAV) {
                val arrival = observed.arrival.replace(Regex("""\s*・\s*"""), "・").trim()
                listOfNotNull(
                    observed.scale.takeIf { it.isNotBlank() },
                    arrival.takeIf { it.isNotBlank() },
                ).joinToString("　　")
            } else null
            DrivePhase.ARRIVED, DrivePhase.WAIT_RETURN, DrivePhase.ARRIVAL_DONE -> "運転お疲れ様でした"
            DrivePhase.DRIVE -> "DRIVE MODE"
            DrivePhase.INITIAL, DrivePhase.DETECTING -> null
        } ?: return@synchronized null
        val safe = road.takeRoadBytes(64)
        if (safe == lastRoadSent) return@synchronized null
        lastRoadSent = safe
        DiagnosticFlightRecorder.record("ROAD", "queued bytes=${safe.toByteArray(Charsets.UTF_8).size} phase=$phase")
        safe
    }

    private fun String.takeRoadBytes(limit: Int): String {
        val output = StringBuilder()
        var remaining = limit
        for (ch in this) {
            if (ch.code < 32 || ch.code == 127) continue
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (size > remaining) break
            output.append(ch)
            remaining -= size
        }
        return output.toString()
    }

    override fun tapDashPoint(up: Boolean) {
        synchronized(roadLock) {
            if (mode != Mode.DASH || SystemClock.elapsedRealtime() < dashReadyAtMs) return
            if (!yahooFeatures) {
                dash.tapStickPoint(up, false)
                return
            }
            // Before classification, during arrival, and during the two UI transitions,
            // the stick must never tap controls meant for the other page.
            when (phase) {
                DrivePhase.NAV -> dash.tapStickPoint(up, false)
                DrivePhase.DRIVE -> if (SystemClock.elapsedRealtime() >= driveStickReadyAtMs)
                    dash.tapStickPoint(up, true)
                else -> Unit
            }
        }
    }

    fun promote(component: String) {
        dashReadyAtMs = Long.MAX_VALUE
        promoteStartFrameSeq = dash.receivedFrameSequence()
        promotingStartedAtMs = SystemClock.elapsedRealtime()
        promotionWaitLevel = 0
        status.reset()
        reloadStatus.reset()
        reloadMaskUntilMs = if (yahooFeatures && component.startsWith("jp.co.yahoo.android.apps.navi/"))
            promotingStartedAtMs + 4_000L else 0L
        mode = Mode.PROMOTING
        // A new PROMOTE is the only way to re-arm classification after DRIVE.
        synchronized(roadLock) {
            phase = DrivePhase.DETECTING
            lastRoadSent = null
            driveStickReadyAtMs = Long.MAX_VALUE
            clearRoadPending = false
            promoteAtMs = promotingStartedAtMs
            arrivedAtMs = 0L
            returnUnknownSinceMs = 0L
        }
        YahooRoadText.observe(yahooFeatures && component.startsWith("jp.co.yahoo.android.apps.navi/"))
        DiagnosticFlightRecorder.record("VD_MODE", "STATUS -> PROMOTING startSeq=$promoteStartFrameSeq")
        dash.promote(component)
        Log.i(TAG, "STATUS -> PROMOTING $component")
    }

    fun demote() {
        val previousMode = mode
        dashReadyAtMs = Long.MAX_VALUE
        reloadMaskUntilMs = 0L
        mode = Mode.STATUS
        YahooRoadText.observe(false)
        synchronized(roadLock) {
            phase = DrivePhase.DETECTING
            clearRoadPending = true
            driveStickReadyAtMs = Long.MAX_VALUE
            lastRoadSent = null
        }
        lastStatusReportAtMs = SystemClock.elapsedRealtime()
        status.reset()
        DiagnosticFlightRecorder.record("VD_MODE", "$previousMode -> STATUS demote")
        dash.demote()
        Log.i(TAG, "DASH -> STATUS")
    }

    fun quit() = dash.quit()

    override fun stop() {
        dashReadyAtMs = Long.MAX_VALUE
        YahooRoadText.observe(false)
        dash.stop()
        status.stop()
        reloadStatus.stop()
    }

    private companion object {
        const val TAG = "Pillion"
        const val DRIVE_WAIT_MS = 10_000L
        const val ARRIVAL_UNKNOWN_SETTLE_MS = 5_000L
        const val DRIVE_STICK_SETTLE_MS = 1_000L
    }
}
