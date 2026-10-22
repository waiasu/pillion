package app.pillion.android

import android.content.Context
import android.os.SystemClock
import app.pillion.core.DashResolution
import app.pillion.core.MirrorSettings
import java.util.UUID

/**
 * Temporary v40 room test. The Activity receives a one-use token stored in private app data.
 * A public shell-launchable Activity must never accept an unauthenticated auto-Start extra.
 */
object CcuAutoTrial {
    const val EXTRA_TOKEN = "app.pillion.ccu.extra.TRIAL_TOKEN"
    const val EXTRA_SESSION_ID = "app.pillion.ccu.extra.TRIAL_ID"
    const val EXTRA_REASON = "app.pillion.ccu.extra.REASON"
    const val ACTION_CAPTURE_READY = "app.pillion.ccu.TRIAL_CAPTURE_READY"
    const val ACTION_START_FAILED = "app.pillion.ccu.TRIAL_START_FAILED"

    private const val PREF_FILE = "ccu_auto_trial"
    private const val KEY_TOKEN = "token"
    private const val KEY_EXPIRES = "expiry"

    fun issue(context: Context): String {
        val token = UUID.randomUUID().toString()
        check(context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRES, SystemClock.elapsedRealtime() + 90_000L)
            .commit()) { "Could not store one-use trial token" }
        return token
    }

    fun consume(context: Context, presented: String?): Boolean {
        if (presented.isNullOrBlank()) return false
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val valid = presented == prefs.getString(KEY_TOKEN, null) &&
            SystemClock.elapsedRealtime() <= prefs.getLong(KEY_EXPIRES, 0L)
        prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).commit()
        return valid
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_TOKEN).remove(KEY_EXPIRES).commit()
    }

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
