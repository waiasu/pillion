package app.pillion.core

/** Stick tap positions in percent of the virtual display, with the top-left as origin. */
data class DashTapSettings(
    val upX: Int = 93,
    val upY: Int = 47,
    val downX: Int = 93,
    val downY: Int = 74,
) {
    companion object {
        /** Target one capture at 1-2 FPS, two at 3 FPS, and ~500ms at higher FPS,
         * with 100ms timing margin. ACK delays can still exceed these windows.
         */
        fun markerDurationMs(fps: Int): Int = when (fps) {
            1 -> 1_100
            2 -> 600
            3 -> 766
            else -> 600
        }

        fun clamped(upX: Int, upY: Int, downX: Int, downY: Int) = DashTapSettings(
            upX.coerceIn(0, 100), upY.coerceIn(0, 100),
            downX.coerceIn(0, 100), downY.coerceIn(0, 100),
        )
    }
}
