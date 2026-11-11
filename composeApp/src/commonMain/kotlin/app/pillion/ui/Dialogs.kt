package app.pillion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pillion.core.UpdateInfo

/** Shown on every launch — a safety acknowledgement for experimental, ride-time software. */
@Composable
internal fun ExperimentalDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("Experimental software") },
        text = {
            Text(
                "Pillion is experimental and provided as-is — use it entirely at your own risk. " +
                    "Set your route before you ride and keep your eyes on the road; never interact " +
                    "with your phone while moving. Not affiliated with, or endorsed by, Yamaha, Garmin, or Yahoo! JAPAN.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I understand") }
        },
    )
}

/** Shown once per launch when a newer release is available. */
@Composable
internal fun UpdateDialog(update: UpdateInfo, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Pillion ${update.version} is available.")
                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        update.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onUpdate) { Text("Get update") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}
