package app.pillion.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import app.pillion.core.ScreenSource
import java.io.ByteArrayOutputStream

/**
 * Lightweight generated frame used after returning from dedicated-dash mode.
 *
 * MediaProjection cannot be relied on after the phone has been locked, so once the first dash
 * promotion has happened the unlocked side intentionally shows this status frame instead of trying
 * to reuse a stale phone-mirror frame. The normal MirrorEngine/NaviLite loop keeps sending the
 * returned JPEG at its configured FPS; only the dot count changes once per second.
 */
class StatusScreenSource(
    private val message: String = STATUS_MESSAGE,
) : ScreenSource {

    @Volatile private var startedAtMs = SystemClock.elapsedRealtime()
    private var cachedDotCount = -1
    private var cachedFrame: ByteArray? = null

    override fun start() = Unit

    /** Restart the dot animation whenever STATUS becomes active. */
    fun reset() {
        startedAtMs = SystemClock.elapsedRealtime()
        synchronized(this) {
            cachedDotCount = -1
            cachedFrame = null
        }
    }

    override fun latestFrame(): ByteArray {
        val elapsed = SystemClock.elapsedRealtime() - startedAtMs
        val dotCount = ((elapsed / STATUS_DOT_INTERVAL_MS) % (STATUS_DOT_MAX + 1)).toInt()

        synchronized(this) {
            if (cachedDotCount != dotCount || cachedFrame == null) {
                cachedDotCount = dotCount
                cachedFrame = render(dotCount)
            }
            return cachedFrame!!
        }
    }

    override fun stop() {
        synchronized(this) {
            cachedDotCount = -1
            cachedFrame = null
        }
    }

    private fun render(dotCount: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(STATUS_BG_COLOR)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = STATUS_TEXT_COLOR
            textSize = STATUS_TEXT_SIZE_PX
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        // Keep the message itself fixed at the exact screen centre. Only the dots grow to its right,
        // so "Waiting for the image" never shifts as the animation advances.
        val centerX = WIDTH / 2f
        val centerY = HEIGHT / 2f
        val baseline = centerY - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(message, centerX, baseline, paint)

        if (dotCount > 0) {
            val messageWidth = paint.measureText(message)
            val dots = STATUS_DOT.repeat(dotCount)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(dots, centerX + messageWidth / 2f + STATUS_DOT_GAP_PX, baseline, paint)
        }

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, STATUS_JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    companion object {
        // Intentionally kept together so message/animation/size can be changed easily in a later build.
        const val STATUS_MESSAGE = "Please lock the phone"
        const val STATUS_DOT = "・"
        const val STATUS_DOT_MAX = 3
        const val STATUS_DOT_INTERVAL_MS = 1_000L
        const val STATUS_TEXT_SIZE_PX = 20f
        const val STATUS_DOT_GAP_PX = 4f

        private const val WIDTH = 480
        private const val HEIGHT = 234
        private const val STATUS_JPEG_QUALITY = 60
        private val STATUS_BG_COLOR = Color.BLACK
        private val STATUS_TEXT_COLOR = Color.WHITE
    }
}
