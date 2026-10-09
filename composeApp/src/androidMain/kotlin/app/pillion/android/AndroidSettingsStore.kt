package app.pillion.android

import android.content.Context
import app.pillion.core.DashMarginColor
import app.pillion.core.DashMargins
import app.pillion.core.DashResolution
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode

/** [SettingsStore] backed by SharedPreferences. Single responsibility: persist preferences. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("pillion.settings", Context.MODE_PRIVATE)

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    override fun imageQuality(): Int = prefs.getInt(KEY_QUALITY, 80).coerceIn(10, 80)

    override fun setImageQuality(value: Int) {
        prefs.edit().putInt(KEY_QUALITY, value.coerceIn(10, 80)).commit()
    }

    override fun dashDpi(): Int = prefs.getInt(KEY_DASH_DPI, 240).coerceIn(160, 480)

    override fun setDashDpi(value: Int) {
        val snapped = ((value.coerceIn(160, 480) - 160) / 10) * 10 + 160
        prefs.edit().putInt(KEY_DASH_DPI, snapped).commit()
    }

    override fun maxFps(): Double {
        // Older builds stored an Int. Read either type so upgrades preserve the saved value.
        val raw = runCatching { prefs.getFloat(KEY_MAX_FPS, 3f).toDouble() }
            .recoverCatching { prefs.getInt(KEY_MAX_FPS, 3).toDouble() }
            .getOrDefault(3.0)
        return normalizeMaxFps(raw)
    }

    override fun setMaxFps(value: Double) {
        prefs.edit().putFloat(KEY_MAX_FPS, normalizeMaxFps(value).toFloat()).commit()
    }

    override fun dashEnabled(): Boolean = prefs.getBoolean(KEY_DASH_ENABLED, false)

    override fun setDashEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DASH_ENABLED, enabled).apply()
    }

    override fun dashScaleTenths(): Int = prefs.getInt(KEY_DASH_SCALE, DashResolution.DEFAULT_SCALE_TENTHS)
        .coerceIn(10, 30)

    override fun setDashScaleTenths(value: Int) {
        prefs.edit().putInt(KEY_DASH_SCALE, value.coerceIn(10, 30)).commit()
    }

    override fun dashLeftMargin(): Int = DashMargins.clampLeft(prefs.getInt(KEY_DASH_LEFT_MARGIN, 50))

    override fun dashBottomMargin(): Int = DashMargins.clampBottom(prefs.getInt(KEY_DASH_BOTTOM_MARGIN, 16))

    override fun dashMarginColor(): DashMarginColor =
        DashMarginColor.fromName(prefs.getString(KEY_DASH_MARGIN_COLOR, null))

    override fun setDashLayout(leftMargin: Int, bottomMargin: Int, color: DashMarginColor) {
        // commit(), not apply(): Restart app may terminate the process immediately after this call.
        prefs.edit()
            .putInt(KEY_DASH_LEFT_MARGIN, DashMargins.clampLeft(leftMargin))
            .putInt(KEY_DASH_BOTTOM_MARGIN, DashMargins.clampBottom(bottomMargin))
            .putString(KEY_DASH_MARGIN_COLOR, color.name)
            .commit()
    }

    override fun stickZoomInXPercent(): Int = prefs.getInt(KEY_STICK_ZOOM_IN_X, 93).coerceIn(0, 100)
    override fun stickZoomInYPercent(): Int = prefs.getInt(KEY_STICK_ZOOM_IN_Y, 47).coerceIn(0, 100)
    override fun stickZoomOutXPercent(): Int = prefs.getInt(KEY_STICK_ZOOM_OUT_X, 93).coerceIn(0, 100)
    override fun stickZoomOutYPercent(): Int = prefs.getInt(KEY_STICK_ZOOM_OUT_Y, 74).coerceIn(0, 100)

    override fun setStickTapPositions(zoomInX: Int, zoomInY: Int, zoomOutX: Int, zoomOutY: Int) {
        // Keep this synchronous for the same reason as the other restart-applied settings.
        prefs.edit()
            .putInt(KEY_STICK_ZOOM_IN_X, zoomInX.coerceIn(0, 100))
            .putInt(KEY_STICK_ZOOM_IN_Y, zoomInY.coerceIn(0, 100))
            .putInt(KEY_STICK_ZOOM_OUT_X, zoomOutX.coerceIn(0, 100))
            .putInt(KEY_STICK_ZOOM_OUT_Y, zoomOutY.coerceIn(0, 100))
            .commit()
    }

    override fun ocrEnabled(): Boolean = prefs.getBoolean(KEY_OCR_ENABLED, false)
    override fun ocrShowArea(): Boolean = prefs.getBoolean(KEY_OCR_SHOW_AREA, false)
    override fun ocrLeftPercent(): Int = prefs.getInt(KEY_OCR_LEFT, 30).coerceIn(20, 80)
    override fun ocrTopPercent(): Int = prefs.getInt(KEY_OCR_TOP, 89).coerceIn(80, 100)
    override fun ocrRightPercent(): Int = prefs.getInt(KEY_OCR_RIGHT, 70).coerceIn(20, 80)
    override fun ocrBottomPercent(): Int = prefs.getInt(KEY_OCR_BOTTOM, 100).coerceIn(80, 100)
    override fun ocr2LeftPercent(): Int = prefs.getInt(KEY_OCR2_LEFT, 88).coerceIn(0, 100)
    override fun ocr2TopPercent(): Int = prefs.getInt(KEY_OCR2_TOP, 56).coerceIn(0, 100)
    override fun ocr2RightPercent(): Int = prefs.getInt(KEY_OCR2_RIGHT, 98).coerceIn(0, 100)
    override fun ocr2BottomPercent(): Int = prefs.getInt(KEY_OCR2_BOTTOM, 64).coerceIn(0, 100)

    override fun setOcrSettings(enabled: Boolean, showArea: Boolean, left: Int, top: Int, right: Int, bottom: Int, area2Left: Int, area2Top: Int, area2Right: Int, area2Bottom: Int) {
        prefs.edit()
            .putBoolean(KEY_OCR_ENABLED, enabled)
            .putBoolean(KEY_OCR_SHOW_AREA, showArea)
            .putInt(KEY_OCR_LEFT, left.coerceIn(20, 80))
            .putInt(KEY_OCR_TOP, top.coerceIn(80, 100))
            .putInt(KEY_OCR_RIGHT, right.coerceIn(20, 80))
            .putInt(KEY_OCR_BOTTOM, bottom.coerceIn(80, 100))
            .putInt(KEY_OCR2_LEFT, area2Left.coerceIn(0, 100))
            .putInt(KEY_OCR2_TOP, area2Top.coerceIn(0, 100))
            .putInt(KEY_OCR2_RIGHT, area2Right.coerceIn(0, 100))
            .putInt(KEY_OCR2_BOTTOM, area2Bottom.coerceIn(0, 100))
            .commit()
    }

    override fun restartAppOnVd(): Boolean = prefs.getBoolean(KEY_RESTART_APP_ON_VD, false)

    override fun setRestartAppOnVd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESTART_APP_ON_VD, enabled).commit()
    }

    override fun fixedDashAppEnabled(): Boolean = prefs.getBoolean(KEY_FIXED_DASH_APP_ENABLED, false)

    override fun fixedDashAppPackage(): String? =
        prefs.getString(KEY_FIXED_DASH_APP_PACKAGE, null)?.takeIf { it.isNotBlank() }

    override fun setFixedDashApp(enabled: Boolean, packageName: String?) {
        // Keep this synchronous: Settings may immediately restart the app after saving.
        val editor = prefs.edit().putBoolean(KEY_FIXED_DASH_APP_ENABLED, enabled)
        val normalized = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) editor.remove(KEY_FIXED_DASH_APP_PACKAGE)
        else editor.putString(KEY_FIXED_DASH_APP_PACKAGE, normalized)
        editor.commit()
    }

    override fun selectedBikeId(): String? = prefs.getString(KEY_BIKE, null)

    override fun setSelectedBikeId(id: String) {
        prefs.edit().putString(KEY_BIKE, id).apply()
    }


    private fun normalizeMaxFps(value: Double): Double =
        value.toInt().coerceIn(1, 15).toDouble()

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_QUALITY = "image_quality"
        const val KEY_DASH_DPI = "dash_dpi"
        const val KEY_MAX_FPS = "max_fps"
        const val KEY_DASH_ENABLED = "dash_enabled"
        const val KEY_DASH_SCALE = "dash_scale_tenths"
        const val KEY_DASH_LEFT_MARGIN = "dash_left_margin"
        const val KEY_DASH_BOTTOM_MARGIN = "dash_bottom_margin"
        const val KEY_DASH_MARGIN_COLOR = "dash_margin_color"
        const val KEY_STICK_ZOOM_IN_X = "stick_zoom_in_x_percent"
        const val KEY_STICK_ZOOM_IN_Y = "stick_zoom_in_y_percent"
        const val KEY_STICK_ZOOM_OUT_X = "stick_zoom_out_x_percent"
        const val KEY_STICK_ZOOM_OUT_Y = "stick_zoom_out_y_percent"
        const val KEY_OCR_ENABLED = "ocr_enabled"
        const val KEY_OCR_SHOW_AREA = "ocr_show_area"
        const val KEY_OCR_LEFT = "ocr_left_percent"
        const val KEY_OCR_TOP = "ocr_top_percent"
        const val KEY_OCR_RIGHT = "ocr_right_percent"
        const val KEY_OCR_BOTTOM = "ocr_bottom_percent"
        const val KEY_OCR2_LEFT = "ocr2_left_percent"
        const val KEY_OCR2_TOP = "ocr2_top_percent"
        const val KEY_OCR2_RIGHT = "ocr2_right_percent"
        const val KEY_OCR2_BOTTOM = "ocr2_bottom_percent"
        const val KEY_RESTART_APP_ON_VD = "restart_app_on_vd"
        const val KEY_FIXED_DASH_APP_ENABLED = "fixed_dash_app_enabled"
        const val KEY_FIXED_DASH_APP_PACKAGE = "fixed_dash_app_package"
        const val KEY_BIKE = "selected_bike_id"
    }
}
