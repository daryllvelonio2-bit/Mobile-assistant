package com.shiina.mobile.ui.permissions

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Settings-directed grant flow for the special permissions. Re-checks on Refresh. */
@Composable
fun PermissionScreen() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    remember(refreshTick) { }

    val usage = hasUsageAccess(context)
    val overlay = Settings.canDrawOverlays(context)
    val alarm = canScheduleExactAlarms(context)

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Permissions", style = MaterialTheme.typography.titleLarge)
        PermissionRow(
            label = "Usage access",
            granted = usage,
            onOpen = { openUsageSettings(context) },
        )
        PermissionRow(
            label = "Draw over other apps",
            granted = overlay,
            onOpen = { openOverlaySettings(context) },
        )
        PermissionRow(
            label = "Exact alarms" + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " (auto)" else "",
            granted = alarm,
            onOpen = { openExactAlarmSettings(context) },
        )
        Button(onClick = { refreshTick++ }, modifier = Modifier.padding(top = 8.dp)) {
            Text("Refresh")
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onOpen, enabled = !granted) { Text("Grant") }
    }
}
