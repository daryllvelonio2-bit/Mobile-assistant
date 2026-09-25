package com.shiina.mobile.ui.permissions

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Modern, clean permissions section following Google Pixel / Material 3 flush list style.
 * Zero heavy boxes or tinted cards — typography-led with subtle divider lines.
 */
@Composable
fun PermissionSection(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    remember(refreshTick) { }

    val usage = hasUsageAccess(context)
    val overlay = Settings.canDrawOverlays(context)
    val alarm = canScheduleExactAlarms(context)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = { refreshTick++ }) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh permissions status",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        PermissionItemRow(
            title = "Usage access",
            description = "Enables app usage and context awareness",
            granted = usage,
            onOpen = { openUsageSettings(context) },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )

        PermissionItemRow(
            title = "Draw over other apps",
            description = "Allows Shiina's floating avatar to appear over apps",
            granted = overlay,
            onOpen = { openOverlaySettings(context) },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )

        PermissionItemRow(
            title = "Exact alarms" + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " (auto)" else "",
            description = "Required for timely reflections and reminders",
            granted = alarm,
            onOpen = { openExactAlarmSettings(context) },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )

        PermissionItemRow(
            title = "Notification listener",
            description = "Enables reading exact music track and artist from media players",
            granted = hasNotificationAccess(context),
            onOpen = { openNotificationListenerSettings(context) },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )

        PermissionItemRow(
            title = "Accessibility service",
            description = "Allows Shiina to automatically tap the screen, scroll, and type to complete tasks",
            granted = hasAccessibilityAccess(context),
            onOpen = { openAccessibilitySettings(context) },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )

        PermissionItemRow(
            title = "Unrestricted battery",
            description = "Stops the OS from killing Shiina in the background (triggers, greeting, overlay)",
            granted = isIgnoringBatteryOptimizations(context),
            onOpen = { openBatterySettings(context) },
        )
    }
}

/**
 * Standalone PermissionScreen for compatibility with existing references.
 */
@Composable
fun PermissionScreen(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        PermissionSection()
    }
}

/**
 * Clean, subtle permission item row without unnecessary cards or background boxes.
 */
@Composable
private fun PermissionItemRow(
    title: String,
    description: String,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onOpen)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        if (granted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            FilledTonalButton(
                onClick = onOpen,
                contentPadding = ButtonDefaults.ContentPadding,
            ) {
                Text(
                    text = "Grant",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * Sleek, non-intrusive banner shown when any permission is missing.
 */
@Composable
fun PermissionNoticeBanner(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Permission warning",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Permissions required for full features · Tap to configure",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
    }
}
