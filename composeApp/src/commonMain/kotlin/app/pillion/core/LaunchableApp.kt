package app.pillion.core

/** Minimal metadata for an installed app that exposes a launcher activity. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
)
