package app.pillion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Keeps platform system bars in sync with the resolved app theme. */
@Composable
internal expect fun SystemBars(dark: Boolean, background: Color)
