package app.pillion.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
internal actual fun SystemBars(dark: Boolean, background: Color) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        // On edge-to-edge Android versions the full-size themed Surface supplies
        // the background behind transparent bars. Older versions use these colors.
        @Suppress("DEPRECATION")
        window.statusBarColor = background.toArgb()
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && !dark) {
            // Android 7 cannot draw dark navigation icons. Keep their background legible.
            android.graphics.Color.BLACK
        } else background.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
