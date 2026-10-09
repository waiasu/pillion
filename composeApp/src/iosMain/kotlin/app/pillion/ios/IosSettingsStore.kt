package app.pillion.ios

import app.pillion.core.DashMarginColor
import app.pillion.core.DashMargins
import app.pillion.core.DashResolution
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode
import platform.Foundation.NSUserDefaults

/** [SettingsStore] backed by NSUserDefaults. */
class IosSettingsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(defaults.stringForKey(THEME_KEY) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        defaults.setObject(mode.name, forKey = THEME_KEY)
    }

    override fun imageQuality(): Int = defaults.stringForKey(QUALITY_KEY)?.toIntOrNull()?.takeIf { it in 10..80 } ?: 80

    override fun setImageQuality(value: Int) {
        defaults.setObject(value.coerceIn(10, 80).toString(), forKey = QUALITY_KEY)
    }

    override fun dashDpi(): Int =
        defaults.stringForKey(DASH_DPI_KEY)?.toIntOrNull()?.takeIf { it in 160..480 && it % 10 == 0 } ?: 220

    override fun setDashDpi(value: Int) {
        val snapped = ((value.coerceIn(160, 480) - 160) / 10) * 10 + 160
        defaults.setObject(snapped.toString(), forKey = DASH_DPI_KEY)
    }

    override fun maxFps(): Double = normalizeMaxFps(
        defaults.stringForKey(MAX_FPS_KEY)?.toDoubleOrNull() ?: 3.0,
    )

    override fun setMaxFps(value: Double) {
        defaults.setObject(normalizeMaxFps(value).toString(), forKey = MAX_FPS_KEY)
    }

    // Dedicated dash mode is Android-only. These remain here for interface completeness.
    override fun dashEnabled(): Boolean = defaults.boolForKey(DASH_ENABLED_KEY)

    override fun setDashEnabled(enabled: Boolean) {
        defaults.setBool(enabled, forKey = DASH_ENABLED_KEY)
    }

    override fun dashScaleTenths(): Int =
        defaults.stringForKey(DASH_SCALE_KEY)?.toIntOrNull()?.takeIf { it in 10..30 }
            ?: DashResolution.DEFAULT_SCALE_TENTHS

    override fun setDashScaleTenths(value: Int) {
        defaults.setObject(value.coerceIn(10, 30).toString(), forKey = DASH_SCALE_KEY)
    }

    override fun dashLeftMargin(): Int = DashMargins.clampLeft(defaults.stringForKey(DASH_LEFT_KEY)?.toIntOrNull() ?: 50)

    override fun dashBottomMargin(): Int = DashMargins.clampBottom(defaults.stringForKey(DASH_BOTTOM_KEY)?.toIntOrNull() ?: 20)

    override fun dashMarginColor(): DashMarginColor =
        DashMarginColor.fromName(defaults.stringForKey(DASH_COLOR_KEY))

    override fun setDashLayout(leftMargin: Int, bottomMargin: Int, color: DashMarginColor) {
        defaults.setObject(DashMargins.clampLeft(leftMargin).toString(), forKey = DASH_LEFT_KEY)
        defaults.setObject(DashMargins.clampBottom(bottomMargin).toString(), forKey = DASH_BOTTOM_KEY)
        defaults.setObject(color.name, forKey = DASH_COLOR_KEY)
    }

    override fun stickZoomInXPercent(): Int = defaults.stringForKey(STICK_ZOOM_IN_X_KEY)?.toIntOrNull()?.coerceIn(0, 100) ?: 94
    override fun stickZoomInYPercent(): Int = defaults.stringForKey(STICK_ZOOM_IN_Y_KEY)?.toIntOrNull()?.coerceIn(0, 100) ?: 48
    override fun stickZoomOutXPercent(): Int = defaults.stringForKey(STICK_ZOOM_OUT_X_KEY)?.toIntOrNull()?.coerceIn(0, 100) ?: 94
    override fun stickZoomOutYPercent(): Int = defaults.stringForKey(STICK_ZOOM_OUT_Y_KEY)?.toIntOrNull()?.coerceIn(0, 100) ?: 77

    override fun setStickTapPositions(zoomInX: Int, zoomInY: Int, zoomOutX: Int, zoomOutY: Int) {
        defaults.setObject(zoomInX.coerceIn(0, 100).toString(), forKey = STICK_ZOOM_IN_X_KEY)
        defaults.setObject(zoomInY.coerceIn(0, 100).toString(), forKey = STICK_ZOOM_IN_Y_KEY)
        defaults.setObject(zoomOutX.coerceIn(0, 100).toString(), forKey = STICK_ZOOM_OUT_X_KEY)
        defaults.setObject(zoomOutY.coerceIn(0, 100).toString(), forKey = STICK_ZOOM_OUT_Y_KEY)
    }

    override fun ocrEnabled(): Boolean = defaults.boolForKey(OCR_ENABLED_KEY)
    override fun ocrShowArea(): Boolean = defaults.boolForKey(OCR_SHOW_AREA_KEY)
    override fun ocrLeftPercent(): Int = defaults.stringForKey(OCR_LEFT_KEY)?.toIntOrNull()?.coerceIn(20, 80) ?: 30
    override fun ocrTopPercent(): Int = defaults.stringForKey(OCR_TOP_KEY)?.toIntOrNull()?.coerceIn(80, 100) ?: 89
    override fun ocrRightPercent(): Int = defaults.stringForKey(OCR_RIGHT_KEY)?.toIntOrNull()?.coerceIn(20, 80) ?: 70
    override fun ocrBottomPercent(): Int = defaults.stringForKey(OCR_BOTTOM_KEY)?.toIntOrNull()?.coerceIn(80, 100) ?: 100
    override fun ocr2LeftPercent(): Int = 88
    override fun ocr2TopPercent(): Int = 56
    override fun ocr2RightPercent(): Int = 98
    override fun ocr2BottomPercent(): Int = 64

    override fun setOcrSettings(enabled: Boolean, showArea: Boolean, left: Int, top: Int, right: Int, bottom: Int, area2Left: Int, area2Top: Int, area2Right: Int, area2Bottom: Int) {
        defaults.setBool(enabled, forKey = OCR_ENABLED_KEY)
        defaults.setBool(showArea, forKey = OCR_SHOW_AREA_KEY)
        defaults.setObject(left.coerceIn(20, 80).toString(), forKey = OCR_LEFT_KEY)
        defaults.setObject(top.coerceIn(80, 100).toString(), forKey = OCR_TOP_KEY)
        defaults.setObject(right.coerceIn(20, 80).toString(), forKey = OCR_RIGHT_KEY)
        defaults.setObject(bottom.coerceIn(80, 100).toString(), forKey = OCR_BOTTOM_KEY)
    }

    override fun restartAppOnVd(): Boolean = false
    override fun setRestartAppOnVd(enabled: Boolean) = Unit

    override fun fixedDashAppEnabled(): Boolean = false
    override fun fixedDashAppPackage(): String? = null
    override fun setFixedDashApp(enabled: Boolean, packageName: String?) = Unit

    override fun selectedBikeId(): String? = defaults.stringForKey(BIKE_KEY)

    override fun setSelectedBikeId(id: String) {
        defaults.setObject(id, forKey = BIKE_KEY)
    }

    private fun normalizeMaxFps(value: Double): Double = when {
        value <= 0.5 -> 0.5
        value >= 15.0 -> 15.0
        else -> value.toInt().coerceIn(1, 15).toDouble()
    }

    private companion object {
        const val THEME_KEY = "theme_mode"
        const val QUALITY_KEY = "image_quality"
        const val DASH_DPI_KEY = "dash_dpi"
        const val MAX_FPS_KEY = "max_fps"
        const val DASH_ENABLED_KEY = "dash_enabled"
        const val DASH_SCALE_KEY = "dash_scale_tenths"
        const val DASH_LEFT_KEY = "dash_left_margin"
        const val DASH_BOTTOM_KEY = "dash_bottom_margin"
        const val DASH_COLOR_KEY = "dash_margin_color"
        const val STICK_ZOOM_IN_X_KEY = "stick_zoom_in_x_percent"
        const val STICK_ZOOM_IN_Y_KEY = "stick_zoom_in_y_percent"
        const val STICK_ZOOM_OUT_X_KEY = "stick_zoom_out_x_percent"
        const val STICK_ZOOM_OUT_Y_KEY = "stick_zoom_out_y_percent"
        const val OCR_ENABLED_KEY = "ocr_enabled"
        const val OCR_SHOW_AREA_KEY = "ocr_show_area"
        const val OCR_LEFT_KEY = "ocr_left_percent"
        const val OCR_TOP_KEY = "ocr_top_percent"
        const val OCR_RIGHT_KEY = "ocr_right_percent"
        const val OCR_BOTTOM_KEY = "ocr_bottom_percent"
        const val BIKE_KEY = "selected_bike_id"
    }
}
