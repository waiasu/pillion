package app.pillion.android

import android.content.Context
import org.json.JSONObject
import app.pillion.core.SettingsPreset
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


    // Only projection/app preferences belong to a preset. Device transport, bike and appearance stay untouched.
    private val presetKeys = listOf(
        "image_quality", "max_frame_rate", KEY_DASH_ENABLED, KEY_DASH_RESOLUTION, KEY_DASH_DPI,
        "dash_left_margin", "dash_bottom_margin", "dash_margin_color", KEY_DASH_APP,
        "stick_input_enabled", "tap_up_x", "tap_up_y", "tap_down_x", "tap_down_y",
        "tap_drive_up_x", "tap_drive_up_y", "tap_drive_down_x", "tap_drive_down_y",
        "auto_drive_initial_x", "auto_drive_initial_y", "auto_drive_arrived_x",
        "auto_drive_arrived_y", "yahoo_navi_enabled",
    )
    private val defaultValues: Map<String, Any> by lazy {
        val tap = DashTapSettings()
        val drive = DashAutoDriveSettings()
        val margin = DashMargins()
        mapOf(
            "image_quality" to 80, "max_frame_rate" to 3,
            KEY_DASH_ENABLED to false, KEY_DASH_RESOLUTION to DashResolution.DEFAULT.name,
            KEY_DASH_DPI to DashDpi.DEFAULT,
            "dash_left_margin" to margin.left, "dash_bottom_margin" to margin.bottom,
            "dash_margin_color" to DashMarginColor.Black.name,
            KEY_DASH_APP to (YAHOO_PACKAGE.takeIf { packageManager.getLaunchIntentForPackage(it) != null } ?: ""),
            "stick_input_enabled" to tap.enabled,
            "tap_up_x" to tap.upX, "tap_up_y" to tap.upY,
            "tap_down_x" to tap.downX, "tap_down_y" to tap.downY,
            "tap_drive_up_x" to tap.driveUpX, "tap_drive_up_y" to tap.driveUpY,
            "tap_drive_down_x" to tap.driveDownX, "tap_drive_down_y" to tap.driveDownY,
            "auto_drive_initial_x" to drive.initialX, "auto_drive_initial_y" to drive.initialY,
            "auto_drive_arrived_x" to drive.arrivedX, "auto_drive_arrived_y" to drive.arrivedY,
            "yahoo_navi_enabled" to true,
        )
    }

    private fun presetObject(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_PRESETS, "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun currentSnapshot(): JSONObject {
        val data = JSONObject()
        for (key in presetKeys) {
            val value = when (key) {
                KEY_DASH_APP -> dashAppPackage() ?: ""
                else -> prefs.all[key] ?: defaultValues.getValue(key)
            }
            data.put(key, value)
        }
        return data
    }

    override fun listPresets(): List<SettingsPreset> {
        val saved = presetObject()
        val entries = mutableListOf(
            SettingsPreset(DEFAULT_PRESET, DashDpi.DEFAULT, DashResolution.DEFAULT.label, isDefault = true),
        )
        val names = saved.keys().asSequence().toList().sortedWith(String.CASE_INSENSITIVE_ORDER)
        for (name in names) {
            val data = saved.optJSONObject(name) ?: continue
            val dpi = DashDpi.snap(data.optInt(KEY_DASH_DPI, DashDpi.DEFAULT))
            val scale = DashResolution.fromName(data.optString(KEY_DASH_RESOLUTION, null)).label
            entries.add(SettingsPreset(name, dpi, scale))
        }
        return entries
    }

    override fun savePreset(name: String, overwrite: Boolean): Boolean {
        val normalized = name.trim()
        if (normalized.isEmpty() || normalized.length > 80 || normalized == DEFAULT_PRESET) return false
        val saved = presetObject()
        if (saved.has(normalized) && !overwrite) return false
        if (!saved.has(normalized) && overwrite) return false
        saved.put(normalized, currentSnapshot())
        prefs.edit().putString(KEY_PRESETS, saved.toString()).apply()
        return true
    }

    override fun applyPreset(name: String): Boolean {
        val data = if (name == DEFAULT_PRESET) {
            JSONObject().apply { defaultValues.forEach { (key, value) -> put(key, value) } }
        } else presetObject().optJSONObject(name) ?: return false
        // Snapshot is full and typed; reject incomplete or malformed records before changing preferences.
        if (presetKeys.any { !data.has(it) }) return false
        val editor = prefs.edit()
        for (key in presetKeys) {
            val expected = defaultValues.getValue(key)
            when (expected) {
                is Boolean -> {
                    if (data.opt(key) !is Boolean) return false
                    editor.putBoolean(key, data.getBoolean(key))
                }
                is Int -> {
                    if (data.opt(key) !is Number) return false
                    editor.putInt(key, data.getInt(key))
                }
                is String -> {
                    if (data.opt(key) !is String) return false
                    editor.putString(key, data.getString(key))
                }
            }
        }
        editor.apply()
        return true
    }

    override fun deletePreset(name: String): Boolean {
        if (name == DEFAULT_PRESET) return false
        val saved = presetObject()
        if (!saved.has(name)) return false
        saved.remove(name)
        prefs.edit().putString(KEY_PRESETS, saved.toString()).apply()
        return true
    }

    private companion object {
        const val KEY_PRESETS = "settings_presets_v1"
        const val DEFAULT_PRESET = "標準設定 (デフォルト)"
        const val KEY_THEME = "theme_mode"
        const val KEY_DASH_ENABLED = "dash_enabled"
        const val KEY_DASH_RESOLUTION = "dash_resolution"
        const val KEY_BIKE = "selected_bike_id"
        const val KEY_DASH_APP = "dash_app_package"
        const val KEY_DASH_DPI = "dash_dpi"
        const val YAHOO_PACKAGE = "jp.co.yahoo.android.apps.navi"
    }
}
