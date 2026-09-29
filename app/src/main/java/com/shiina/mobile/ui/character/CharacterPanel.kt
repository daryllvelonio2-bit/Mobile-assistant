package com.shiina.mobile.ui.character

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiina.mobile.theme.ShiinaMotion
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.ui.components.MoodOrb
import com.shiina.mobile.ui.components.ShiinaBanner
import com.shiina.mobile.ui.components.ShiinaCard
import com.shiina.mobile.ui.components.ShiinaChip
import com.shiina.mobile.ui.components.ShiinaDivider
import com.shiina.mobile.ui.components.ShiinaSectionHeader
import com.shiina.mobile.ui.components.ShiinaStat
import com.shiina.mobile.ui.components.ShiinaStatusDot
import com.shiina.mobile.ui.components.moodGradientFor
import com.shiina.mobile.ui.permissions.openOverlaySettings

/**
 * Companion dashboard — the home of Shiina's presence.
 * Hero orb, primary actions, and her live state at a glance.
 */
@Composable
fun CharacterPanel(
    viewModel: CharacterViewModel,
    modifier: Modifier = Modifier,
) {
    val tone by viewModel.tone.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val renderError by viewModel.error.collectAsState()
    val rememberedDays by viewModel.rememberedDays.collectAsState()
    val overlayOk = viewModel.canOverlay()
    val context = LocalContext.current

    val moodColors = moodGradientFor(tone)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- Hero presence card ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShiinaShapes.ExtraLarge)
                .background(
                    Brush.linearGradient(
                        listOf(
                            moodColors.first().copy(alpha = 0.18f),
                            moodColors.last().copy(alpha = 0.06f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        ),
                    ),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ShiinaShapes.ExtraLarge,
                )
                .padding(20.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MoodOrb(mood = tone, size = 148.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Shiina",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShiinaChip(
                        text = tone.replaceFirstChar { it.uppercase() },
                        containerColor = moodColors.last().copy(alpha = 0.18f),
                        contentColor = moodColors.last(),
                        icon = Icons.Default.AutoAwesome,
                    )
                    ShiinaChip(
                        text = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Default.Layers,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    ShiinaStatusDot(
                        color = if (overlayOk) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = if (overlayOk) "Overlay ready" else "Overlay permission missing",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ---- Permission banner ----
        AnimatedVisibility(
            visible = !overlayOk,
            enter = fadeIn(ShiinaMotion.TweenMedium),
            exit = fadeOut(ShiinaMotion.TweenFast),
        ) {
            Column {
                ShiinaBanner(
                    text = "Shiina needs overlay access to float on your screen.",
                    icon = Icons.Default.ErrorOutline,
                    onClick = { openOverlaySettings(context) },
                    action = {
                        Text(
                            text = "Grant",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // ---- Error banner ----
        AnimatedVisibility(
            visible = renderError != null,
            enter = fadeIn(ShiinaMotion.TweenMedium),
            exit = fadeOut(ShiinaMotion.TweenFast),
        ) {
            Column {
                ShiinaBanner(
                    text = renderError.orEmpty(),
                    icon = Icons.Default.ErrorOutline,
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { viewModel.clearError() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        // ---- Primary actions ----
        ShiinaSectionHeader(
            title = "Presence",
            eyebrow = "Controls",
            icon = Icons.Default.Visibility,
        )
        Spacer(Modifier.height(12.dp))

        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            ActionRow(
                icon = Icons.Default.Visibility,
                title = "Show overlay",
                subtitle = "Bring her onto your screen",
                onClick = { viewModel.show() },
                accentColor = MaterialTheme.colorScheme.tertiary,
                enabled = overlayOk,
            )
            ShiinaDivider()
            ActionRow(
                icon = Icons.Default.VisibilityOff,
                title = "Hide overlay",
                subtitle = "Dismiss her for now",
                onClick = { viewModel.hide() },
                accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ShiinaDivider()
            ActionRow(
                icon = Icons.Default.PlayArrow,
                title = "Render latest reaction",
                subtitle = "Re-evaluate habits and refresh her mood",
                onClick = { viewModel.renderLatest() },
                accentColor = MaterialTheme.colorScheme.primary,
                enabled = overlayOk,
            )
        }

        Spacer(Modifier.height(20.dp))

        // ---- State at a glance ----
        ShiinaSectionHeader(
            title = "Companion state",
            eyebrow = "Live",
            icon = Icons.Default.Insights,
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShiinaStat(
                value = "$rememberedDays",
                label = "Days remembered",
                icon = Icons.Outlined.Schedule,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            ShiinaStat(
                value = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                label = "Behavior mode",
                icon = Icons.Default.Layers,
                accent = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
            DetailRow(label = "Current tone", value = tone.replaceFirstChar { it.uppercase() })
            ShiinaDivider()
            DetailRow(
                label = "Memory span",
                value = if (rememberedDays > 0) {
                    "$rememberedDays day${if (rememberedDays == 1) "" else "s"} of shared context"
                } else {
                    "No shared history yet"
                },
            )
            ShiinaDivider()
            DetailRow(
                label = "Overlay access",
                value = if (overlayOk) "Granted" else "Missing",
                isAlert = !overlayOk,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = if (rememberedDays > 0) {
                "Reflecting across $rememberedDays day${if (rememberedDays == 1) "" else "s"} of shared memory."
            } else {
                "Ready for your daily activities."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accentColor: Color,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.985f else 1f,
        animationSpec = ShiinaMotion.Components.CardPress,
        label = "actionRow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(ShiinaShapes.Medium)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(ShiinaShapes.Medium)
                .background(
                    (if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant)
                        .copy(alpha = 0.14f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    isAlert: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isAlert) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}
