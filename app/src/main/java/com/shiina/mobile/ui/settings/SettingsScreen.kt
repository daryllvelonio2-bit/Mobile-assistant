package com.shiina.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import com.shiina.mobile.data.settings.SettingsRepository
import com.shiina.mobile.ui.permissions.PermissionSection

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState

/**
 * Modern, clean Settings screen following Google Pixel / Material 3 flush list style.
 * No nested cards or heavy background containers.
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
            title = "Set Bedtime Start",
            initialHour = bedtimeStartHour,
            initialMinute = bedtimeStartMinute,
            onConfirm = { h, m -> viewModel.setBedtimeStart(h, m) },
            onDismiss = { showStartTimePicker = false },
        )
    }

    if (showEndTimePicker) {
        BedtimeTimePickerDialog(
            title = "Set Bedtime End",
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
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Integrated Permissions Section
        PermissionSection()

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Intelligence / API Configuration
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Intelligence & Decision",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (geminiKeys.isNotEmpty()) {
                Text(
                    text = "${geminiKeys.size} key${if (geminiKeys.size > 1) "s" else ""} active",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Round-robin pool: requests automatically rotate across all keys to distribute load and prevent 429 rate limits.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Existing keys list
        if (geminiKeys.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                geminiKeys.forEachIndexed { index, k ->
                    val masked = if (k.length > 8) "${k.take(6)}...${k.takeLast(4)}" else "••••••••"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Key #${index + 1}: $masked",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Active in rotation",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(
                            onClick = { viewModel.removeGeminiKey(k) },
                            modifier = Modifier.size(36.dp),
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
            }
            Spacer(modifier = Modifier.height(12.dp))
        } else {
            Text(
                text = "No API keys added yet. Add at least one Gemini key to enable autonomous reasoning, memory, and chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Add New API Key Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newKeyText,
                onValueChange = { newKeyText = it },
                label = { Text("Add Gemini API Key") },
                placeholder = { Text("Paste new API key") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                visualTransformation = if (showNewKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showNewKey = !showNewKey }) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = if (showNewKey) "Hide key" else "Show key",
                            tint = if (showNewKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = {
                    if (newKeyText.isNotBlank()) {
                        viewModel.addGeminiKey(newKeyText.trim())
                        newKeyText = ""
                    }
                },
                enabled = newKeyText.isNotBlank(),
            ) {
                Text("Add")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Model Target Selection
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Model Target",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (selectedModel == SettingsRepository.MODEL_31_FLASH_LITE) "3.1 Flash-Lite" else "3.5 Flash-Lite",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Gemini 3.5 and 3.1 maintain distinct free-tier quotas on Google AI Studio. Switch between them or let the agent fall back automatically if rate-limited.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val models = listOf(
                SettingsRepository.MODEL_35_FLASH_LITE to Pair("Gemini 3.5 Flash-Lite", "Recommended · Balanced reasoning, multimodal vision, & tool calling"),
                SettingsRepository.MODEL_31_FLASH_LITE to Pair("Gemini 3.1 Flash-Lite", "Lightweight · Separate free-tier quota & ultra-low latency"),
            )
            models.forEach { (modelId, info) ->
                val (title, subtitle) = info
                val isSelected = selectedModel == modelId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setGeminiModel(modelId) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { viewModel.setGeminiModel(modelId) },
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Observation Settings
        Text(
            text = "Observation & Senses",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Screenshot capture",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Periodic screen analysis for contextual assistance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = screenshot,
                onCheckedChange = viewModel::toggleScreenshot,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Voice TTS (Backlog B5)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Voice speech output (TTS)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Speaks responses aloud with emotional voice inflection and late-night calm pitch",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = voiceTts,
                onCheckedChange = viewModel::toggleVoiceTts,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Daily Rhythm
        Text(
            text = "Daily Rhythm",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Daily reflection alarm",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Scheduled at %02d:%02d daily".format(hour, minute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledTonalButton(
                onClick = { viewModel.setAlarm((hour + 1) % 24, minute) },
            ) {
                Text("+1h")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bedtime start picker
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showStartTimePicker = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bedtime start",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Night guardian & binge-block active from %02d:%02d".format(bedtimeStartHour, bedtimeStartMinute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledTonalButton(
                onClick = { showStartTimePicker = true },
            ) {
                Text("%02d:%02d".format(bedtimeStartHour, bedtimeStartMinute))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Bedtime end picker
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showEndTimePicker = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bedtime end",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Morning wake time at %02d:%02d".format(bedtimeEndHour, bedtimeEndMinute),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            FilledTonalButton(
                onClick = { showEndTimePicker = true },
            ) {
                Text("%02d:%02d".format(bedtimeEndHour, bedtimeEndMinute))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // About & Version
        Text(
            text = "About",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Shiina Mobile Assistant",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Version ${com.shiina.mobile.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onReplayOnboarding,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        ) {
            Text("Replay Guided Setup Tour")
        }

        Spacer(modifier = Modifier.height(16.dp))
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
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
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
    )
}
