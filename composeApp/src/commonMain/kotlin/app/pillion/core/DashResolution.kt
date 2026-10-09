package app.pillion.core

import kotlin.math.roundToInt

/**
 * Off-screen display size used by the dedicated dash helper. The final frame sent to the XMAX stays
 * fixed at [OUTPUT_WIDTH] x [OUTPUT_HEIGHT]; only the Android virtual display changes size.
 */
data class DashResolution(
    val width: Int,
    val height: Int,
) {
    val label: String get() = "$width x $height"

    companion object {
        const val OUTPUT_WIDTH = 480
        const val OUTPUT_HEIGHT = 234
        const val DEFAULT_SCALE_TENTHS = 20

        val DEFAULT: DashResolution = forLayout(0, 0, DEFAULT_SCALE_TENTHS)

        fun forLayout(leftMargin: Int, bottomMargin: Int, scaleTenths: Int): DashResolution {
            val usableWidth = (OUTPUT_WIDTH - leftMargin).coerceAtLeast(1)
            val usableHeight = (OUTPUT_HEIGHT - bottomMargin).coerceAtLeast(1)
            val scale = scaleTenths / 10f
            return DashResolution(
                width = (usableWidth * scale).roundToInt().coerceAtLeast(1),
                height = (usableHeight * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }
}
