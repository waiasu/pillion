package app.pillion.core

/**
 * Persists user preferences across launches. The UI depends on this abstraction (DIP); platforms
 * provide it (Android: SharedPreferences). A null store simply means "use defaults" (e.g. previews).
 */
data class SettingsPreset(val name: String, val dpi: Int, val layoutScale: String, val isDefault: Boolean = false)

interface SettingsStore {
    fun listPresets(): List<SettingsPreset> = emptyList()
    fun savePreset(name: String, overwrite: Boolean = false): Boolean = false
    fun applyPreset(name: String): Boolean = false
    fun deletePreset(name: String): Boolean = false

    fun imageQuality(): Int = 80
    fun setImageQuality(value: Int) {}
    fun maxFrameRate(): Int = 3
    fun setMaxFrameRate(value: Int) {}
    fun dashTapSettings(): DashTapSettings = DashTapSettings()
    fun setDashTapSettings(settings: DashTapSettings) {}
    fun dashAutoDriveSettings(): DashAutoDriveSettings = DashAutoDriveSettings()
    fun setDashAutoDriveSettings(settings: DashAutoDriveSettings) {}
    fun yahooNaviEnabled(): Boolean = true
    fun setYahooNaviEnabled(enabled: Boolean) {}

    fun themeMode(): ThemeMode
    fun setThemeMode(mode: ThemeMode)

    /** Whether the user has opted into "dedicated dash display" mode (completed onboarding). */
    fun dashEnabled(): Boolean
    fun setDashEnabled(enabled: Boolean)

    /** Virtual display resolution for the dedicated dash helper. */
    fun dashResolution(): DashResolution
    fun setDashResolution(resolution: DashResolution)

    fun dashDpi(): Int = DashDpi.DEFAULT
    fun setDashDpi(dpi: Int) {}
    fun dashMargins(): DashMargins = DashMargins()
    fun setDashMargins(margins: DashMargins) {}
    fun dashMarginColor(): DashMarginColor = DashMarginColor.Black
    fun setDashMarginColor(color: DashMarginColor) {}

    /** Package selected for dedicated VD. Null means no choice yet. */
    fun dashAppPackage(): String? = null
    fun setDashAppPackage(packageName: String?) {}

    /** The head-unit the user picked at onboarding ([app.pillion.core.headunit.HeadUnitProfile.id]),
     *  or null if they haven't chosen yet (→ show the bike-selection screen). */
    fun selectedBikeId(): String?
    fun setSelectedBikeId(id: String)
}
