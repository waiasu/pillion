package app.pillion.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.core.MirrorState
import app.pillion.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(
    state: MirrorState,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onResetAndRestart: () -> Unit,
    startRequestInFlight: Boolean? = null,
) {
    var startTapLocked by remember { mutableStateOf(false) }

    // Android supplies startRequestInFlight and owns the complete request lifetime. Keep the local
    // latch only for immediate click suppression; release it on cancel/failure or once the session
    // actually leaves Idle. Platforms that do not supply the external flag retain the previous
    // short 1.2 s double-tap guard unchanged.
    LaunchedEffect(startRequestInFlight, state) {
        if (startRequestInFlight != null && startTapLocked) {
            if (!startRequestInFlight || state !is MirrorState.Idle) {
                startTapLocked = false
            }
        }
    }
    LaunchedEffect(startTapLocked, state) {
        if (startRequestInFlight != null || !startTapLocked) return@LaunchedEffect
        if (state !is MirrorState.Idle) {
            startTapLocked = false
        } else {
            delay(1_200L)
            startTapLocked = false
        }
    }

    val startLocked = startTapLocked || startRequestInFlight == true

    Column(
        modifier = Modifier.fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.align(Alignment.TopCenter)) { Wordmark() }
            IconButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(Res.string.settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state is MirrorState.Idle) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ConnectGuide()
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    StatusDisplay(state)
                }
            }
        }
        PrimaryButton(
            state = state,
            startEnabled = !startLocked,
            onStart = {
                if (!startLocked) {
                    startTapLocked = true
                    onStart()
                }
            },
            onStop = onStop,
        )
        Spacer(Modifier.height(28.dp))
        ResetRestartSlider(onResetAndRestart = onResetAndRestart)
        Text(
            stringResource(Res.string.reset_restart_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 6.dp, end = 6.dp),
        )
    }
}


@Composable
private fun ResetRestartSlider(onResetAndRestart: () -> Unit) {
    var trackWidthPx by remember { mutableStateOf(0f) }
    var thumbOffsetPx by remember { mutableStateOf(0f) }
    val thumbSize = 44.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.slide_reset_restart),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat() },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbSizePx = with(androidx.compose.ui.platform.LocalDensity.current) { thumbSize.toPx() }
            val maxOffsetPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)
            val dragState = rememberDraggableState { delta ->
                thumbOffsetPx = (thumbOffsetPx + delta).coerceIn(0f, maxOffsetPx)
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbOffsetPx.roundToInt(), 0) }
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStopped = {
                            if (maxOffsetPx > 0f && thumbOffsetPx >= maxOffsetPx * 0.9f) {
                                animate(thumbOffsetPx, maxOffsetPx) { value, _ ->
                                    thumbOffsetPx = value
                                }
                                onResetAndRestart()
                                thumbOffsetPx = 0f
                            } else {
                                animate(thumbOffsetPx, 0f) { value, _ ->
                                    thumbOffsetPx = value
                                }
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "›",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun Wordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Pillion",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
        )
        Text(
            stringResource(Res.string.tagline),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDisplay(state: MirrorState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            MirrorState.Idle -> Unit
            MirrorState.Connecting -> {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(Res.string.connecting_to_dash), style = MaterialTheme.typography.titleMedium)
            }
            is MirrorState.Streaming -> {
                Text(
                    formatFps(state.fps),
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(Res.string.fps),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(Res.string.mirroring_frame_size, state.kbPerFrame),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MirrorState.Broadcasting -> {
                StatusDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.broadcasting), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(Res.string.open_maps_on_dash),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is MirrorState.Error -> {
                StatusDot(MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(Res.string.disconnected), style = MaterialTheme.typography.titleMedium)
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun ConnectGuide() {
    val steps = listOf(
        stringResource(Res.string.guide_pair_bluetooth),
        stringResource(Res.string.guide_landscape),
        stringResource(Res.string.guide_navigation_mode),
        stringResource(Res.string.guide_start_mirroring),
        stringResource(Res.string.guide_open_maps),
    )
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.before_you_ride),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(12.dp))
        steps.forEachIndexed { index, step -> StepRow(index + 1, step) }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PrimaryButton(
    state: MirrorState,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val active = state is MirrorState.Streaming || state is MirrorState.Connecting ||
        state is MirrorState.Broadcasting
    Button(
        onClick = if (active) onStop else onStart,
        enabled = active || startEnabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Text(
            if (active) stringResource(Res.string.stop_mirroring) else stringResource(Res.string.start_mirroring),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatFps(fps: Double): String {
    val rounded = (fps * 10).toInt()
    return "${rounded / 10}.${rounded % 10}"
}
