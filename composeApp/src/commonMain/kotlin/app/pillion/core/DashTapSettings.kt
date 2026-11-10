package app.pillion.core

/** NAV and DRIVE stick tap positions, percentages from the top-left of the VD. */
data class DashTapSettings(
    val upX: Int = 93,
    val upY: Int = 47,
    val downX: Int = 93,
    val downY: Int = 74,
    val driveUpX: Int = 93,
    val driveUpY: Int = 13,
    val driveDownX: Int = 93,
    val driveDownY: Int = 30,
    /** Controls only handlebar-stick taps; automatic Drive Mode taps remain enabled. */
    val enabled: Boolean = true,
) {
    companion object {
        /** Marker duration provides at least one frame at low FPS. */
        fun markerDurationMs(fps: Int): Int = when (fps) {
            1 -> 1_100
            2 -> 600
            3 -> 766
            else -> 600
        }

        fun clamped(
            upX: Int, upY: Int, downX: Int, downY: Int,
            driveUpX: Int = 93, driveUpY: Int = 13,
            driveDownX: Int = 93, driveDownY: Int = 30,
            enabled: Boolean = true,
        ) = DashTapSettings(
            upX.coerceIn(0, 100), upY.coerceIn(0, 100),
            downX.coerceIn(0, 100), downY.coerceIn(0, 100),
            driveUpX.coerceIn(0, 100), driveUpY.coerceIn(0, 100),
            driveDownX.coerceIn(0, 100), driveDownY.coerceIn(0, 100),
            enabled = enabled,
        )
    }
}
