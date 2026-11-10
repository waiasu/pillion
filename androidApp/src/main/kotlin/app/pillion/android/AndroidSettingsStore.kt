package app.pillion.android

import android.content.Context
import app.pillion.core.DashDpi
import app.pillion.core.DashTapSettings
import app.pillion.core.DashAutoDriveSettings
import app.pillion.core.DashMargins
import app.pillion.core.DashMarginColor
import app.pillion.core.DashResolution
import app.pillion.core.SettingsStore
import app.pillion.core.ThemeMode

/** [SettingsStore] backed by SharedPreferences. Single responsibility: persist preferences. */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("pillion.settings", Context.MODE_PRIVATE)
    private val packageManager = context.applicationContext.packageManager

    override fun imageQuality(): Int = prefs.getInt("image_quality", 80).coerceIn(10, 80)
    override fun setImageQuality(value: Int) { prefs.edit().putInt("image_quality", value.coerceIn(10, 80)).apply() }
    override fun maxFrameRate(): Int = prefs.getInt("max_frame_rate", 3).coerceIn(1, 15)
    override fun setMaxFrameRate(value: Int) { prefs.edit().putInt("max_frame_rate", value.coerceIn(1, 15)).apply() }

    override fun dashTapSettings(): DashTapSettings = DashTapSettings.clamped(
        prefs.getInt("tap_up_x", 93), prefs.getInt("tap_up_y", 47),
        prefs.getInt("tap_down_x", 93), prefs.getInt("tap_down_y", 74),
        prefs.getInt("tap_drive_up_x", 93), prefs.getInt("tap_drive_up_y", 13),
        prefs.getInt("tap_drive_down_x", 93), prefs.getInt("tap_drive_down_y", 30),
        prefs.getBoolean("stick_input_enabled", true),
    )
    override fun setDashTapSettings(settings: DashTapSettings) {
        val v = DashTapSettings.clamped(settings.upX, settings.upY, settings.downX, settings.downY,
            settings.driveUpX, settings.driveUpY, settings.driveDownX, settings.driveDownY,
            settings.enabled)
        prefs.edit().putInt("tap_up_x", v.upX).putInt("tap_up_y", v.upY)
            .putInt("tap_down_x", v.downX).putInt("tap_down_y", v.downY)
            .putInt("tap_drive_up_x", v.driveUpX).putInt("tap_drive_up_y", v.driveUpY)
            .putInt("tap_drive_down_x", v.driveDownX).putInt("tap_drive_down_y", v.driveDownY)
            .putBoolean("stick_input_enabled", v.enabled).apply()
    }

    override fun dashAutoDriveSettings(): DashAutoDriveSettings = DashAutoDriveSettings.clamped(
        prefs.getInt("auto_drive_initial_x", 7), prefs.getInt("auto_drive_initial_y", 53),
        prefs.getInt("auto_drive_arrived_x", 93), prefs.getInt("auto_drive_arrived_y", 90),
    )
    override fun setDashAutoDriveSettings(settings: DashAutoDriveSettings) {
        val v = DashAutoDriveSettings.clamped(
            settings.initialX, settings.initialY, settings.arrivedX, settings.arrivedY,
        )
        prefs.edit().putInt("auto_drive_initial_x", v.initialX)
            .putInt("auto_drive_initial_y", v.initialY)
            .putInt("auto_drive_arrived_x", v.arrivedX)
            .putInt("auto_drive_arrived_y", v.arrivedY).apply()
    }

    override fun yahooNaviEnabled(): Boolean = prefs.getBoolean("yahoo_navi_enabled", true)
    override fun setYahooNaviEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("yahoo_navi_enabled", enabled).apply()
    }

    override fun themeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    override fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    override fun dashEnabled(): Boolean = prefs.getBoolean(KEY_DASH_ENABLED, false)

    override fun setDashEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DASH_ENABLED, enabled).apply()
    }

    override fun dashResolution(): DashResolution =
        DashResolution.fromName(prefs.getString(KEY_DASH_RESOLUTION, null))

    override fun setDashResolution(resolution: DashResolution) {
        prefs.edit().putString(KEY_DASH_RESOLUTION, resolution.name).apply()
    }

    override fun dashDpi(): Int = DashDpi.snap(prefs.getInt(KEY_DASH_DPI, DashDpi.DEFAULT))
    override fun setDashDpi(dpi: Int) { prefs.edit().putInt(KEY_DASH_DPI, DashDpi.snap(dpi)).apply() }

    override fun dashMargins(): DashMargins {
        // Existing saved values (including zero) win over clean-install defaults.
        val defaults = DashMargins()
        return DashMargins.clamped(
            prefs.getInt("dash_left_margin", defaults.left),
            prefs.getInt("dash_bottom_margin", defaults.bottom),
        )
    }
    override fun setDashMargins(margins: DashMargins) {
        val safe = DashMargins.clamped(margins.left, margins.bottom)
        prefs.edit().putInt("dash_left_margin", safe.left).putInt("dash_bottom_margin", safe.bottom).apply()
    }
    override fun dashMarginColor() = DashMarginColor.fromName(prefs.getString("dash_margin_color", null))
    override fun setDashMarginColor(color: DashMarginColor) { prefs.edit().putString("dash_margin_color", color.name).apply() }

    override fun dashAppPackage(): String? {
        // Only an untouched preference gets the Yahoo default. Never overwrite user choice.
        if (prefs.contains(KEY_DASH_APP)) return prefs.getString(KEY_DASH_APP, null)?.takeIf { it.isNotEmpty() }
        return YAHOO_PACKAGE.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
    }

    override fun setDashAppPackage(packageName: String?) {
        // Empty sentinel preserves an explicit deselection instead of re-enabling the default.
        prefs.edit().putString(KEY_DASH_APP, packageName ?: "").apply()
    }

    override fun selectedBikeId(): String? = prefs.getString(KEY_BIKE, null)

    override fun setSelectedBikeId(id: String) {
        prefs.edit().putString(KEY_BIKE, id).apply()
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DASH_ENABLED = "dash_enabled"
        const val KEY_DASH_RESOLUTION = "dash_resolution"
        const val KEY_BIKE = "selected_bike_id"
        const val KEY_DASH_APP = "dash_app_package"
        const val KEY_DASH_DPI = "dash_dpi"
        const val YAHOO_PACKAGE = "jp.co.yahoo.android.apps.navi"
    }
}
