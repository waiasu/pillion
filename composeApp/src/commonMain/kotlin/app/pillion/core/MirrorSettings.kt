package app.pillion.core

/** User-tunable session settings. */
data class MirrorSettings(
    val quality: Int = 80,
    val dashDpi: Int = 220,
    val maxFps: Double = 3.0,
    val dashResolution: DashResolution = DashResolution.DEFAULT,
    val dashLeftMargin: Int = 50,
    val dashBottomMargin: Int = 20,
    val dashMarginColor: DashMarginColor = DashMarginColor.Black,
    val stickZoomInXPercent: Int = 94,
    val stickZoomInYPercent: Int = 48,
    val stickZoomOutXPercent: Int = 94,
    val stickZoomOutYPercent: Int = 77,
    val ocrEnabled: Boolean = false,
    val ocrShowArea: Boolean = false,
    val ocrLeftPercent: Int = 30,
    val ocrTopPercent: Int = 89,
    val ocrRightPercent: Int = 70,
    val ocrBottomPercent: Int = 100,
    val ocr2LeftPercent: Int = 88,
    val ocr2TopPercent: Int = 56,
    val ocr2RightPercent: Int = 98,
    val ocr2BottomPercent: Int = 64,
    val restartAppOnVd: Boolean = false,
    val fixedDashAppEnabled: Boolean = false,
    val fixedDashAppPackage: String? = null,
)
