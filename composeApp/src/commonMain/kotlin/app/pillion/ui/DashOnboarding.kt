package app.pillion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pillion.core.DashSetup
import app.pillion.core.DashStage
import app.pillion.core.SetupTraceLine
import app.pillion.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Guided setup for dedicated dash mode.
 *
 * Screens: welcome → enable Wireless debugging → notification-driven pairing/status → ready.
 * PIN entry intentionally exists only in Android's setup notification; there is no duplicate text
 * field in Pillion.
 */
@Composable
internal fun DashOnboarding(
    dash: DashSetup,
    onOptOut: () -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit,
) {
    val state by dash.state.collectAsState()
    var step by rememberSaveable { mutableStateOf(0) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            when (step) {
                0 -> Page(
                    hero = { IconHero(Icons.Filled.TwoWheeler) },
                    title = stringResource(Res.string.dash_nav_title),
                    subtitle = stringResource(Res.string.dash_nav_body),
                    step = 0,
                    primary = stringResource(Res.string.get_started) to { step = 1 },
                    secondary = stringResource(Res.string.maybe_later) to onOptOut,
                )

                1 -> Page(
                    hero = { IconHero(Icons.Filled.Settings) },
                    title = stringResource(Res.string.wireless_debugging_on),
                    subtitle = stringResource(Res.string.wireless_debugging_help),
                    step = 1,
                    primary = stringResource(Res.string.open_settings) to {
                        dash.startPairingAssistant()
                        dash.openWirelessDebuggingSettings()
                        step = 2
                    },
                    secondary = stringResource(Res.string.back) to { step = 0 },
                )

                2 -> {
                    val title = when (state.stage) {
                        DashStage.Pairing -> stringResource(Res.string.pairing)
                        DashStage.Connecting -> stringResource(Res.string.preparing_pillion)
                        DashStage.Connected -> stringResource(Res.string.setup_success)
                        DashStage.Error -> stringResource(Res.string.setup_needs_attention)
                        else -> stringResource(Res.string.pair_from_notification)
                    }
                    val subtitle = state.message ?: stringResource(Res.string.pair_from_notification_help)
                    val primary = when {
                        state.stage == DashStage.Connected ->
                            stringResource(Res.string.next) to { step = 3 }

                        state.stage == DashStage.Error && state.canRetrySetup ->
                            stringResource(Res.string.retry_setup) to { dash.connect() }

                        else -> null
                    }
                    val secondary = if (state.stage == DashStage.Connected) null
                    else stringResource(Res.string.open_settings_again) to {
                        // Refresh/resume the setup notification as Settings is re-opened. This keeps
                        // the existing retry/endpoint state while making a fresh pairing dialog usable.
                        dash.reopenWirelessDebuggingSettings()
                    }
                    val showTrace = state.stage != DashStage.Connected && state.setupTrace.isNotEmpty()

                    Page(
                        hero = { PairingStatusHero(state.stage) },
                        title = title,
                        subtitle = subtitle,
                        step = 2,
                        primary = primary,
                        secondary = secondary,
                    ) {
                        if (showTrace) SetupTracePanel(state.setupTrace)
                    }
                }

                else -> Page(
                    hero = { IconHero(Icons.Filled.CheckCircle) },
                    title = stringResource(Res.string.all_set),
                    subtitle = stringResource(Res.string.all_set_help),
                    step = 3,
                    primary = stringResource(Res.string.done) to onFinish,
                )
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(4.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.close_setup),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- one-screen scaffold ----

@Composable
private fun Page(
    hero: @Composable () -> Unit,
    title: String,
    subtitle: String,
    step: Int,
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 24.dp),
    ) {
        // Content fills the available height and scrolls if needed; actions remain pinned below.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            hero()
            Spacer(Modifier.height(36.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            content()
        }
        Spacer(Modifier.height(20.dp))
        PageDots(step, total = 4)
        Spacer(Modifier.height(20.dp))
        if (primary != null) {
            Button(
                onClick = primary.second,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    primary.first,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (secondary != null) {
            TextButton(onClick = secondary.second, modifier = Modifier.fillMaxWidth()) {
                Text(secondary.first)
            }
        }
    }
}

@Composable
private fun SetupTracePanel(lines: List<SetupTraceLine>) {
    Spacer(Modifier.height(20.dp))
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(Res.string.setup_trace),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        lines.forEachIndexed { index, line ->
            val marker = if (index == lines.lastIndex) ">" else " "
            Text(
                "[${formatTraceTime(line.elapsedMs)}] $marker ${line.text}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatTraceTime(elapsedMs: Long): String {
    val tenths = ((elapsedMs.coerceAtLeast(0L) + 50L) / 100L)
    val seconds = tenths / 10L
    val decimal = tenths % 10L
    return "$seconds.$decimal".padStart(5) + "s"
}

@Composable
private fun IconHero(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        Modifier.size(180.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(88.dp),
        )
    }
}

@Composable
private fun PairingStatusHero(stage: DashStage) {
    Box(
        Modifier.size(180.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        when (stage) {
            DashStage.Pairing, DashStage.Connecting -> CircularProgressIndicator(Modifier.size(72.dp))
            DashStage.Connected -> Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(88.dp),
            )
            DashStage.Error -> Text(
                "!",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold,
            )
            else -> Text(
                "PIN",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PageDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val active = i == current
            Box(
                Modifier.height(8.dp).width(if (active) 24.dp else 8.dp).clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    ),
            )
        }
    }
}
