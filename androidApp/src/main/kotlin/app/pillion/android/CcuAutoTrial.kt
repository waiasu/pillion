package app.pillion.android

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import java.io.File
import app.pillion.core.DashResolution
import app.pillion.core.MirrorSettings
import java.util.UUID

/**
 * v40 CCU-linked auto-start challenge. A private AtomicFile bridges the watcher and app
 * processes; SharedPreferences is not safe for fresh cross-process challenge handover.
 * A public shell-launchable Activity must never accept an unauthenticated auto-Start extra.
 */
object CcuAutoTrial {
    const val EXTRA_TOKEN = "app.pillion.ccu.extra.TRIAL_TOKEN"
    const val EXTRA_SESSION_ID = "app.pillion.ccu.extra.TRIAL_ID"
    const val EXTRA_INITIAL_SCREEN_OFF = "app.pillion.ccu.extra.INITIAL_SCREEN_OFF"
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

    /** Use current test-base's saved VD settings rather than v40's obsolete preferences. */
    fun settings(context: Context): MirrorSettings {
        val s = AndroidSettingsStore(context)
        return MirrorSettings(
            quality = s.imageQuality(), maxFps = s.maxFrameRate(),
            dashResolution = s.dashResolution(), dashDpi = s.dashDpi(),
            dashMargins = s.dashMargins(), dashMarginColor = s.dashMarginColor(),
            dashTap = s.dashTapSettings(), dashAutoDrive = s.dashAutoDriveSettings(),
            yahooNaviEnabled = s.yahooNaviEnabled(),
        )
    }
}
