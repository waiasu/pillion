package app.pillion.core

/** Test-only automatic Yahoo! NAVI Drive Mode tap targets, in VD percentages. */
data class DashAutoDriveSettings(
    val initialX: Int = 7,
    val initialY: Int = 53,
    val arrivedX: Int = 93,
    val arrivedY: Int = 90,
) {
    companion object {
        fun clamped(initialX: Int, initialY: Int, arrivedX: Int, arrivedY: Int) =
            DashAutoDriveSettings(
                initialX.coerceIn(0, 100), initialY.coerceIn(0, 100),
                arrivedX.coerceIn(0, 100), arrivedY.coerceIn(0, 100),
            )
    }
}
