package app.pillion.core

/**
 * Produces 480x234 JPEG frames of the screen. Pull-based: the engine asks for the most recent
 * frame at its own send rate, so the platform only spends CPU compressing frames that get sent.
 * The engine depends on this abstraction (DIP); platforms provide it (Android MediaProjection,
 * iOS ReplayKit).
 */
interface ScreenSource {
    fun start()
    /** The most recent screen as a 480x234 JPEG, or null if no frame is available yet. */
    fun latestFrame(): ByteArray?

    /**
     * Diagnostic hook for XMAX UP/DOWN. The Android dash source keeps the proven helper-side
     * `input -d <dashDisplayId> tap ...` path and only changes the point. [markerFrames] is a
     * send-copy-only marker lifetime; the helper JPEG cache is never modified.
     */
    fun tapDashPoint(up: Boolean, markerFrames: Int): Boolean = false

    /** Called before each Bluetooth/NaviLite transport attempt, including reconnect attempts. */
    fun onTransportConnecting() {}

    /** Called only after a normal IMAGE_ACK has been received for a sent image. */
    fun onImageAck() {}

    /** One pending OCR text update for NaviLite ROAD, or null when no update is ready. */
    fun pollRoadText(): String? = null

    fun stop()
}
