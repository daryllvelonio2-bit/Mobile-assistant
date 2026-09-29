package com.shiina.mobile.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.theme.ShiinaMotion
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.ui.components.ShiinaCard
import com.shiina.mobile.ui.components.ShiinaChip
import com.shiina.mobile.ui.components.ShiinaDivider
import com.shiina.mobile.ui.components.ShiinaIconBadge
import com.shiina.mobile.ui.components.ShiinaSectionHeader
import com.shiina.mobile.ui.components.ShiinaToggleRow
import com.shiina.mobile.ui.components.ShiinaValueRow
import com.shiina.mobile.ui.permissions.PermissionSection

/**
 * Settings — organised, scannable, and grouped by intent.
 * Sections: Access, Intelligence, Senses, Rhythm, About.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
    onReplayOnboarding: () -> Unit = {},
) {
    val screenshot by viewModel.screenshotEnabled.collectAsState()
    val voiceTts by viewModel.voiceTtsEnabled.collectAsState()
    val hour by viewModel.alarmHour.collectAsState()
    val minute by viewModel.alarmMinute.collectAsState()
    val geminiKeys by viewModel.geminiKeys.collectAsState()
    val selectedModel by viewModel.geminiModel.collectAsState()
    val bedtimeStartHour by viewModel.bedtimeStartHour.collectAsState()
    val bedtimeStartMinute by viewModel.bedtimeStartMinute.collectAsState()
    val bedtimeEndHour by viewModel.bedtimeEndHour.collectAsState()
    val bedtimeEndMinute by viewModel.bedtimeEndMinute.collectAsState()

    var newKeyText by remember { mutableStateOf("") }
    var showNewKey by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    if (showStartTimePicker) {
        BedtimeTimePickerDialog(
            title = "Bedtime start",
            initialHour = bedtimeStartHour,
            initialMinute = bedtimeStartMinute,
            onConfirm = { h, m -> viewModel.setBedtimeStart(h, m) },
            onDismiss = { showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        BedtimeTimePickerDialog(
            title = "Bedtime end",
            initialHour = bedtimeEndHour,
            initialMinute = bedtimeEndMinute,
            onConfirm = { h, m -> viewModel.setBedtimeEnd(h, m) },
            onDismiss = { showEndTimePicker = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---------- ACCESS ----------
        ShiinaSectionHeader(
            title = "Access & permissions",
            eyebrow = "Setup",
            subtitle = "What Shiina can sense and do on your device",
            icon = Icons.Default.Lock,
        )
        Spacer(Modifier.height(8.dp))
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            PermissionSection()
        }

        Spacer(Modifier.height(28.dp))

        // ---------- INTELLIGENCE ----------
        ShiinaSectionHeader(
            title = "Intelligence & decision",
            eyebrow = "Brain",
            subtitle = "Gemini keys rotate round-robin to dodge rate limits",
            icon = Icons.Default.AutoAwesome,
            action = {
                if (geminiKeys.isNotEmpty()) {
                    ShiinaChip(
                        text = "${geminiKeys.size} active",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            },
        )
        Spacer(Modifier.height(12.dp))

        if (geminiKeys.isEmpty()) {
            ShiinaCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
            ) {
                Text(
                    text = "No API keys yet",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Add at least one Gemini key to unlock reasoning, memory, and chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else {
            ShiinaCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 4.dp,
                ),
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                geminiKeys.forEachIndexed { index, k ->
                    ApiKeyRow(
                        index = index + 1,
                        masked = if (k.length > 8) "${k.take(6)}…${k.takeLast(4)}" else "••••••••",
                        onRemove = { viewModel.removeGeminiKey(k) },
                    )
                    if (index != geminiKeys.lastIndex) ShiinaDivider()
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Add key card
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ) {
            Text(
                text = "Add a key",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newKeyText,
                    onValueChange = { newKeyText = it },
                    placeholder = { Text("AIzaSy…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = ShiinaShapes.Medium,
                    visualTransformation = if (showNewKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    trailingIcon = {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { showNewKey = !showNewKey },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (showNewKey) Icons.Default.Visibility
                                else Icons.Default.VisibilityOff,
                                contentDescription = if (showNewKey) "Hide key" else "Show key",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = {
                        if (newKeyText.isNotBlank()) {
                            viewModel.addGeminiKey(newKeyText.trim())
                            newKeyText = ""
                        }
                    },
                    enabled = newKeyText.isNotBlank(),
                    shape = ShiinaShapes.Medium,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Model selection
        TextureSectionHeader("Model target", "Pick her reasoning engine")
        Spacer(Modifier.height(10.dp))
        val models = listOf(
            SettingsRepository.MODEL_35_FLASH_LITE to Pair(
                "Gemini 3.5 Flash-Lite",
                "Balanced reasoning · vision · tool calling",
            ),
            SettingsRepository.MODEL_31_FLASH_LITE to Pair(
                "Gemini 3.1 Flash-Lite",
                "Lightweight · separate quota · ultra-low latency",
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            models.forEach { (modelId, info) ->
                val (title, subtitle) = info
                ModelOption(
                    title = title,
                    subtitle = subtitle,
                    selected = selectedModel == modelId,
                    onClick = { viewModel.setGeminiModel(modelId) },
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---------- SENSES ----------
        ShiinaSectionHeader(
            title = "Senses & output",
            eyebrow = "Perception",
            subtitle = "How she perceives and speaks",
            icon = Icons.Default.CameraAlt,
        )
        Spacer(Modifier.height(4.dp))
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 4.dp,
            ),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            ShiinaToggleRow(
                title = "Screen capture",
                description = "Periodic screen analysis for contextual help",
                checked = screenshot,
                onCheckedChange = viewModel::toggleScreenshot,
                icon = Icons.Default.CameraAlt,
            )
            ShiinaDivider()
            ShiinaToggleRow(
                title = "Voice output",
                description = "Speaks replies with mood-matched inflection",
                checked = voiceTts,
                onCheckedChange = viewModel::toggleVoiceTts,
                icon = Icons.Default.RecordVoiceOver,
                accent = MaterialTheme.colorScheme.tertiary,
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---------- RHYTHM ----------
        ShiinaSectionHeader(
            title = "Daily rhythm",
            eyebrow = "Schedule",
            subtitle = "Alarms, reflections, and rest windows",
            icon = Icons.Default.NotificationsActive,
        )
        Spacer(Modifier.height(4.dp))
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            ShiinaValueRow(
                title = "Daily reflection",
                value = "Runs at %02d:%02d".format(hour, minute),
                icon = Icons.Default.NotificationsActive,
                trailing = {
                    FilledTonalButton(
                        onClick = { viewModel.setAlarm((hour + 1) % 24, minute) },
                        shape = ShiinaShapes.Medium,
                    ) { Text("+1h") }
                },
            )
            ShiinaDivider()
            ShiinaValueRow(
                title = "Bedtime start",
                value = "Guardian active from %02d:%02d".format(bedtimeStartHour, bedtimeStartMinute),
                icon = Icons.Default.WbTwilight,
                accent = MaterialTheme.colorScheme.secondary,
                onClick = { showStartTimePicker = true },
                trailing = {
                    FilledTonalButton(
                        onClick = { showStartTimePicker = true },
                        shape = ShiinaShapes.Medium,
                    ) { Text("%02d:%02d".format(bedtimeStartHour, bedtimeStartMinute)) }
                },
            )
            ShiinaDivider()
            ShiinaValueRow(
                title = "Bedtime end",
                value = "Morning wake at %02d:%02d".format(bedtimeEndHour, bedtimeEndMinute),
                icon = Icons.Default.Bedtime,
                accent = MaterialTheme.colorScheme.tertiary,
                onClick = { showEndTimePicker = true },
                trailing = {
                    FilledTonalButton(
                        onClick = { showEndTimePicker = true },
                        shape = ShiinaShapes.Medium,
                    ) { Text("%02d:%02d".format(bedtimeEndHour, bedtimeEndMinute)) }
                },
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---------- ABOUT ----------
        ShiinaSectionHeader(
            title = "About",
            eyebrow = "App",
            icon = Icons.Default.AutoAwesome,
        )
        Spacer(Modifier.height(10.dp))
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShiinaIconBadge(
                    icon = Icons.Default.AutoAwesome,
                    size = 44.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Shiina Mobile Assistant",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Version ${com.shiina.mobile.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onReplayOnboarding,
                modifier = Modifier.fillMaxWidth(),
                shape = ShiinaShapes.Medium,
            ) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Replay guided setup")
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun TextureSectionHeader(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApiKeyRow(index: Int, masked: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShiinaIconBadge(icon = Icons.Default.Lock, size = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Key #$index",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = masked,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove key",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun ModelOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = ShiinaMotion.Components.CardPress,
        label = "modelOpt",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = ShiinaMotion.TweenMediumColor,
        label = "modelBorder",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        animationSpec = ShiinaMotion.TweenMediumColor,
        label = "modelBg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(ShiinaShapes.Large)
            .background(bgColor)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, ShiinaShapes.Large)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(
                    if (selected) 6.dp else 2.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BedtimeTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    onConfirm(timePickerState.hour, timePickerState.minute)
                    onDismiss()
                },
            ) { Text("Confirm") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = timePickerState)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = ShiinaShapes.ExtraLarge,
    )
}
