package app.pillion.core

/**
 * Persists user preferences across launches. The UI depends on this abstraction (DIP); platforms
 * provide it (Android: SharedPreferences). A null store simply means "use defaults" (e.g. previews).
 */
interface SettingsStore {
    fun themeMode(): ThemeMode
    fun setThemeMode(mode: ThemeMode)

    fun imageQuality(): Int
    fun setImageQuality(value: Int)

    /** Density used only by the dedicated/locked virtual display. */
    fun dashDpi(): Int
    fun setDashDpi(value: Int)

    fun maxFps(): Double
    fun setMaxFps(value: Double)

    /** Whether the user has opted into "dedicated dash display" mode (completed onboarding). */
    fun dashEnabled(): Boolean
    fun setDashEnabled(enabled: Boolean)

    /** Virtual-display scale (10 = 1.0x, 20 = 2.0x, 30 = 3.0x). */
    fun dashScaleTenths(): Int
    fun setDashScaleTenths(value: Int)

    fun dashLeftMargin(): Int
    fun dashBottomMargin(): Int
    fun dashMarginColor(): DashMarginColor

    /** Persist all restart-required dash-layout settings as one operation. */
    fun setDashLayout(leftMargin: Int, bottomMargin: Int, color: DashMarginColor)

    /** X/Y positions within the usable dash image, expressed as percentages (0..100). */
    fun stickZoomInXPercent(): Int
    fun stickZoomInYPercent(): Int
    fun stickZoomOutXPercent(): Int
    fun stickZoomOutYPercent(): Int
    fun setStickTapPositions(zoomInX: Int, zoomInY: Int, zoomOutX: Int, zoomOutY: Int)

    /** OCR of a configurable rectangle on the original dedicated virtual display. */
    fun ocrEnabled(): Boolean
    fun ocrShowArea(): Boolean
    fun ocrLeftPercent(): Int
    fun ocrTopPercent(): Int
    fun ocrRightPercent(): Int
    fun ocrBottomPercent(): Int
    fun ocr2LeftPercent(): Int
    fun ocr2TopPercent(): Int
    fun ocr2RightPercent(): Int
    fun ocr2BottomPercent(): Int
    fun setOcrSettings(
        enabled: Boolean,
        showArea: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        area2Left: Int,
        area2Top: Int,
        area2Right: Int,
        area2Bottom: Int,
    )

    /** Restart the promoted app UI once on the virtual display after the normal move completes. */
    fun restartAppOnVd(): Boolean
    fun setRestartAppOnVd(enabled: Boolean)

    /** Always promote one selected launcher app instead of whichever app is currently foreground. */
    fun fixedDashAppEnabled(): Boolean
    fun fixedDashAppPackage(): String?
    fun setFixedDashApp(enabled: Boolean, packageName: String?)

    /** The head-unit the user picked at onboarding ([app.pillion.core.headunit.HeadUnitProfile.id]),
     *  or null if they haven't chosen yet (→ show the bike-selection screen). */
    fun selectedBikeId(): String?
    fun setSelectedBikeId(id: String)
}
