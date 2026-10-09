package app.pillion.core

/** Preset fill colours for the unused left/bottom dash margins. */
enum class DashMarginColor(val label: String, val rgb: Int) {
    Black("Black", 0x000000),
    White("White", 0xFFFFFF),
    Red("Red", 0xFF0000),
    Green("Green", 0x00FF00),
    Blue("Blue", 0x0000FF),
    Yellow("Yellow", 0xFFFF00),
    Cyan("Cyan", 0x00FFFF),
    Magenta("Magenta", 0xFF00FF),
    ;

    companion object {
        fun fromName(name: String?): DashMarginColor =
            entries.firstOrNull { it.name == name } ?: Black
    }
}

/** Scale choices for the dedicated Android virtual display: 1.0x through 3.0x in 0.2x steps. */
enum class DashScale(val tenths: Int) {
    X10(10), X12(12), X14(14), X16(16), X18(18), X20(20),
    X22(22), X24(24), X26(26), X28(28), X30(30),
    ;

    val label: String get() = "${tenths / 10}.${tenths % 10}x"

    companion object {
        val DEFAULT = X20
        fun fromTenths(value: Int): DashScale = entries.firstOrNull { it.tenths == value } ?: DEFAULT
    }
}

object DashMargins {
    const val STEP_PX = 2
    const val MAX_LEFT_PERCENT = 20
    const val MAX_BOTTOM_PERCENT = 30

    fun maxLeft(): Int = evenFloor(DashResolution.OUTPUT_WIDTH * MAX_LEFT_PERCENT / 100)
    fun maxBottom(): Int = evenFloor(DashResolution.OUTPUT_HEIGHT * MAX_BOTTOM_PERCENT / 100)

    fun clampLeft(value: Int): Int = evenFloor(value.coerceIn(0, maxLeft()))
    fun clampBottom(value: Int): Int = evenFloor(value.coerceIn(0, maxBottom()))

    private fun evenFloor(value: Int): Int = value - (value % STEP_PX)
}
