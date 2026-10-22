package app.pillion.android

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import app.pillion.core.DashResolution
import app.pillion.core.MirrorSettings
import java.util.UUID

/**
 * Temporary v40 room test. A private AtomicFile bridges the separate watcher and app
 * processes; SharedPreferences is not safe for fresh cross-process challenge handover.
 * A public shell-launchable Activity must never accept an unauthenticated auto-Start extra.
 */
object CcuAutoTrial {
    const val EXTRA_TOKEN = "app.pillion.ccu.extra.TRIAL_TOKEN"
    const val EXTRA_SESSION_ID = "app.pillion.ccu.extra.TRIAL_ID"
    const val EXTRA_REASON = "app.pillion.ccu.extra.REASON"
    const val ACTION_CAPTURE_READY = "app.pillion.ccu.TRIAL_CAPTURE_READY"
    const val ACTION_START_FAILED = "app.pillion.ccu.TRIAL_START_FAILED"

    private const val TOKEN_FILE = "ccu_auto_trial.token"
    private const val TOKEN_VALID_MS = 90_000L

    private fun tokenFile(context: Context): AtomicFile =
        AtomicFile(File(context.filesDir, TOKEN_FILE))

    /** Atomic private-file write: the main process can read the newest challenge immediately. */
    fun issue(context: Context): String {
        val token = UUID.randomUUID().toString()
        val file = tokenFile(context)
        val stream = file.startWrite()
        try {
            stream.write("$token\n${SystemClock.elapsedRealtime() + TOKEN_VALID_MS}\n".toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (t: Throwable) {
            file.failWrite(stream)
            throw t
        }
        return token
    }

    /** Consume once. Failed guesses must not invalidate a legitimate pending challenge. */
    fun consume(context: Context, presented: String?): Boolean {
        if (presented.isNullOrBlank()) return false
        val file = tokenFile(context)
        val stored = runCatching {
            file.openRead().bufferedReader(Charsets.UTF_8).use { it.readLines() }
        }.getOrNull() ?: return false
        val savedToken = stored.getOrNull(0)
        val expiration = stored.getOrNull(1)?.toLongOrNull() ?: 0L
        val expired = SystemClock.elapsedRealtime() > expiration
        val valid = !expired && savedToken == presented
        if (valid || expired) file.delete()
        return valid
    }

    fun clear(context: Context) = tokenFile(context).delete()

    /** Match the Android Home screen's persisted (not draft) settings exactly. */
    fun settings(context: Context): MirrorSettings {
        val s = AndroidSettingsStore(context)
        return MirrorSettings(
            quality = s.imageQuality(),
            dashDpi = s.dashDpi(),
            maxFps = s.maxFps(),
            dashResolution = DashResolution.forLayout(
                s.dashLeftMargin(), s.dashBottomMargin(), s.dashScaleTenths(),
            ),
            dashLeftMargin = s.dashLeftMargin(),
            dashBottomMargin = s.dashBottomMargin(),
            dashMarginColor = s.dashMarginColor(),
            stickTapEnabled = s.stickTapEnabled(),
            stickZoomInXPercent = s.stickZoomInXPercent(),
            stickZoomInYPercent = s.stickZoomInYPercent(),
            stickZoomOutXPercent = s.stickZoomOutXPercent(),
            stickZoomOutYPercent = s.stickZoomOutYPercent(),
            ocrEnabled = s.ocrEnabled(),
            ocrShowArea = s.ocrShowArea(),
            ocrLeftPercent = s.ocrLeftPercent(),
            ocrTopPercent = s.ocrTopPercent(),
            ocrRightPercent = s.ocrRightPercent(),
            ocrBottomPercent = s.ocrBottomPercent(),
            ocr2LeftPercent = s.ocr2LeftPercent(),
            ocr2TopPercent = s.ocr2TopPercent(),
            ocr2RightPercent = s.ocr2RightPercent(),
            ocr2BottomPercent = s.ocr2BottomPercent(),
            restartAppOnVd = s.restartAppOnVd(),
            autoDriveModeEnabled = s.autoDriveModeEnabled(),
            driveTapXPercent = s.driveTapXPercent(),
            driveTapYPercent = s.driveTapYPercent(),
            driveStickZoomInXPercent = s.driveStickZoomInXPercent(),
            driveStickZoomInYPercent = s.driveStickZoomInYPercent(),
            driveStickZoomOutXPercent = s.driveStickZoomOutXPercent(),
            driveStickZoomOutYPercent = s.driveStickZoomOutYPercent(),
            driveImageShiftPx = s.driveImageShiftPx(),
            fixedDashAppEnabled = s.fixedDashAppEnabled(),
            fixedDashAppPackage = s.fixedDashAppPackage(),
        )
    }
}
