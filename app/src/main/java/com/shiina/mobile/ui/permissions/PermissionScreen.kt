package com.shiina.mobile.ui.permissions

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.ui.components.ShiinaDivider

/**
 * Permission rows — flush list style, meant to be embedded inside a
 * Settings card or rendered standalone. Headerless so callers own the title.
 */
@Composable
fun PermissionSection(
    modifier: Modifier = Modifier,
) {
    val context = LocalContextCompat()
    var refreshTick by remember { mutableIntStateOf(0) }
    remember(refreshTick) { }

    val usage = hasUsageAccess(context)
    val overlay = Settings.canDrawOverlays(context)
    val alarm = canScheduleExactAlarms(context)
    val notification = hasNotificationAccess(context)
    val a11y = hasAccessibilityAccess(context)
    val battery = isIgnoringBatteryOptimizations(context)

    Column(modifier = modifier.fillMaxWidth()) {
        PermissionItemRow(
            title = "Usage access",
            description = "App usage and context awareness",
            icon = Icons.Default.Insights,
            granted = usage,
            onOpen = { openUsageSettings(context) },
        )
        ShiinaDivider()

        PermissionItemRow(
            title = "Draw over other apps",
            description = "Lets her floating avatar appear over apps",
            icon = Icons.Default.Layers,
            granted = overlay,
            onOpen = { openOverlaySettings(context) },
        )
        ShiinaDivider()

        PermissionItemRow(
            title = "Accessibility service",
            description = "Tap, scroll, and type to complete tasks",
            icon = Icons.Default.TouchApp,
            granted = a11y,
            onOpen = { openAccessibilitySettings(context) },
        )
        ShiinaDivider()

        PermissionItemRow(
            title = "Exact alarms" + if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) " (auto)" else "",
            description = "Timely reflections and reminders",
            icon = Icons.Default.Schedule,
            granted = alarm,
            onOpen = { openExactAlarmSettings(context) },
        )
        ShiinaDivider()

        PermissionItemRow(
            title = "Notification listener",
            description = "Reads the exact track and artist from players",
            icon = Icons.Default.Notifications,
            granted = notification,
            onOpen = { openNotificationListenerSettings(context) },
        )
        ShiinaDivider()

        PermissionItemRow(
            title = "Unrestricted battery",
            description = "Stops the OS killing her in the background",
            icon = Icons.Default.BatteryChargingFull,
            granted = battery,
            onOpen = { openBatterySettings(context) },
        )
    }
}

/** Local wrapper to keep the androidx import list tidy. */
@Composable
private fun LocalContextCompat(): android.content.Context =
    androidx.compose.ui.platform.LocalContext.current

/**
 * Standalone Permissions screen (kept for compatibility).
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
        Text(
            text = "Permissions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Grant access so Shiina can operate at full strength.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PermissionSection()
    }
}

@Composable
private fun PermissionItemRow(
    title: String,
    description: String,
    icon: ImageVector,
    granted: Boolean,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShiinaShapes.Medium)
            .clickable(enabled = !granted, onClick = onOpen)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(ShiinaShapes.Medium)
                .background(
                    if (granted) {
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (granted) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Granted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            FilledTonalButton(
                onClick = onOpen,
                shape = ShiinaShapes.Medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("Grant", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * Compact banner shown when any permission is missing.
 */
@Composable
fun PermissionNoticeBanner(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShiinaShapes.Medium)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Permissions required for full features · Tap to configure",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
    }
}
