package app.pillion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.pillion.core.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF34D8C8),
    onPrimary = Color(0xFF00201C),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1E242C),
    onSurfaceVariant = Color(0xFF8B95A3),
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF24171A),
    onErrorContainer = Color(0xFFFFB4AB),
    outline = Color(0xFF2A313B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00897B),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDF0F3),
    onSurfaceVariant = Color(0xFF5A636E),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFD3D8DE),
)

/** Applies the Pillion color scheme for the chosen [ThemeMode] and a base surface. */
@Composable
internal fun PillionTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    SystemBars(dark, colors.background)
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
