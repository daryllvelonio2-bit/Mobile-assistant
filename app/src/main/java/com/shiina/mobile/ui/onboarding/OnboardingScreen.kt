package com.shiina.mobile.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shiina.mobile.theme.ShiinaMotion
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.theme.ShiinaTextStyles
import com.shiina.mobile.ui.components.AnimatedCheck
import com.shiina.mobile.ui.components.MoodOrb
import com.shiina.mobile.ui.components.ShiinaCard
import com.shiina.mobile.ui.components.ShiinaDivider
import com.shiina.mobile.ui.components.ShiinaIconBadge
import com.shiina.mobile.ui.permissions.canScheduleExactAlarms
import com.shiina.mobile.ui.permissions.hasAccessibilityAccess
import com.shiina.mobile.ui.permissions.hasUsageAccess
import com.shiina.mobile.ui.permissions.isIgnoringBatteryOptimizations
import com.shiina.mobile.ui.permissions.openAccessibilitySettings
import com.shiina.mobile.ui.permissions.openBatterySettings
import com.shiina.mobile.ui.permissions.openExactAlarmSettings
import com.shiina.mobile.ui.permissions.openOverlaySettings
import com.shiina.mobile.ui.permissions.openUsageSettings
import com.shiina.mobile.ui.settings.SettingsViewModel

/**
 * First-run guided onboarding flow (Backlog B8).
 * A step-based wizard: intro, brain setup, access, senses, and launch.
 */
@Composable
fun OnboardingScreen(
    viewModel: SettingsViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }
    val totalSteps = 5
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Top progress ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBar + 12.dp, start = 20.dp, end = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (step > 0) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { step-- },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Spacer(Modifier.width(40.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${step + 1} of $totalSteps",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        viewModel.completeOnboarding()
                        onComplete()
                    }) {
                        Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Segmented progress bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (i in 0 until totalSteps) {
                        val filled by animateFloatAsState(
                            targetValue = if (i <= step) 1f else 0.25f,
                            animationSpec = ShiinaMotion.Components.OnboardingStepIndicator,
                            label = "seg$i",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = filled),
                                ),
                        )
                    }
                }
            }

            // ---- Step content ----
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it / 3 } + fadeIn(ShiinaMotion.TweenMediumSlow))
                                .togetherWith(
                                    slideOutHorizontally { -it / 3 } + fadeOut(ShiinaMotion.TweenFast),
                                )
                        } else {
                            (slideInHorizontally { -it / 3 } + fadeIn(ShiinaMotion.TweenMediumSlow))
                                .togetherWith(
                                    slideOutHorizontally { it / 3 } + fadeOut(ShiinaMotion.TweenFast),
                                )
                        }
                    },
                    label = "onboarding_step",
                ) { current ->
                    when (current) {
                        0 -> StepWelcome()
                        1 -> StepApiKey(viewModel)
                        2 -> StepPermissions()
                        3 -> StepPreferences(viewModel)
                        else -> StepReady()
                    }
                }
            }

            // ---- Bottom action ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        top = 8.dp,
                        bottom = navBar + 16.dp,
                    ),
            ) {
                Button(
                    onClick = {
                        if (step < totalSteps - 1) {
                            step++
                        } else {
                            viewModel.completeOnboarding()
                            onComplete()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = ShiinaShapes.Large,
                ) {
                    Text(
                        text = if (step == totalSteps - 1) "Enter companion" else "Continue",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = if (step == totalSteps - 1) Icons.Default.Check
                        else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ============================================================
// Step 1 — Welcome
// ============================================================
@Composable
private fun StepWelcome() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        MoodOrb(mood = "calm", size = 150.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Meet Shiina",
            style = ShiinaTextStyles.OnboardingTitle,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "An emotionally-aware phone companion with real senses and the ability to act on your behalf.",
            style = ShiinaTextStyles.OnboardingBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        FeatureCard(
            icon = Icons.Default.AutoAwesome,
            title = "Genuine personality",
            body = "No robotic clichés. She gets pouty when you stay up at 3 AM and actually cares about your wellbeing.",
            accent = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        FeatureCard(
            icon = Icons.Default.Layers,
            title = "Acts on your phone",
            body = "Opens apps, sets alarms, navigates UI, and learns multi-step workflows to do them faster next time.",
            accent = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(10.dp))
        FeatureCard(
            icon = Icons.Default.Lock,
            title = "Grounded, never guessing",
            body = "She works from live screen receipts and real sensor data instead of inventing outcomes.",
            accent = MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    title: String,
    body: String,
    accent: Color,
) {
    ShiinaCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    ) {
        Row {
            ShiinaIconBadge(icon = icon, tint = accent, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ============================================================
// Step 2 — API key
// ============================================================
@Composable
private fun StepApiKey(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val geminiKeys by viewModel.geminiKeys.collectAsState()
    var inputKey by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        ShiinaIconBadge(
            icon = Icons.Default.Key,
            size = 52.dp,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Connect her brain",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Shiina reasons with Google Gemini. Paste an API key to switch on her thinking engine.",
            style = ShiinaTextStyles.OnboardingBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        if (geminiKeys.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShiinaShapes.Large)
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f),
                        ShiinaShapes.Large,
                    )
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Brain connected",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${geminiKeys.size} key(s) rotating in the pool",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedTextField(
            value = inputKey,
            onValueChange = { inputKey = it },
            label = { Text("Gemini API key") },
            placeholder = { Text("AIzaSy…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = ShiinaShapes.Medium,
            visualTransformation = if (keyVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { keyVisible = !keyVisible },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (keyVisible) Icons.Default.Visibility
                        else Icons.Default.VisibilityOff,
                        contentDescription = "Toggle visibility",
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val trimmed = inputKey.trim()
                    if (trimmed.isNotEmpty()) {
                        viewModel.addGeminiKey(trimmed)
                        inputKey = ""
                    }
                },
                enabled = inputKey.trim().isNotEmpty(),
                shape = ShiinaShapes.Medium,
                modifier = Modifier.weight(1f),
            ) { Text("Save key") }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://aistudio.google.com/app/apikey"),
                            ),
                        )
                    }
                },
                shape = ShiinaShapes.Medium,
            ) { Text("Get a free key") }
        }

        Spacer(Modifier.height(20.dp))
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            Row {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Tip: add several keys. Shiina rotates them automatically to avoid rate limits and keep her available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ============================================================
// Step 3 — Permissions
// ============================================================
@Composable
private fun StepPermissions() {
    val context = LocalContext.current
    var refreshTick by remember { mutableIntStateOf(0) }
    remember(refreshTick) { }

    val overlay = Settings.canDrawOverlays(context)
    val a11y = hasAccessibilityAccess(context)
    val usage = hasUsageAccess(context)
    val alarm = canScheduleExactAlarms(context)
    val battery = isIgnoringBatteryOptimizations(context)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Give her senses",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "These let Shiina navigate, observe, and float over your screen.",
                    style = ShiinaTextStyles.OnboardingBody,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable { refreshTick++ },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        PermissionCard(
            index = 1,
            title = "Draw over other apps",
            purpose = "Lets her floating avatar appear on top of any app.",
            icon = Icons.Default.Layers,
            granted = overlay,
            onGrant = { openOverlaySettings(context) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            index = 2,
            title = "Accessibility service",
            purpose = "Required to inspect the UI, tap, type, and automate tasks.",
            icon = Icons.Default.TouchApp,
            granted = a11y,
            onGrant = { openAccessibilitySettings(context) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            index = 3,
            title = "Usage access",
            purpose = "Sees which app is open, tracks screen time, senses breaks.",
            icon = Icons.Default.Visibility,
            granted = usage,
            onGrant = { openUsageSettings(context) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            index = 4,
            title = "Exact alarms",
            purpose = "Fires reminders and check-ins to the second.",
            icon = Icons.Default.Schedule,
            granted = alarm,
            onGrant = { openExactAlarmSettings(context) },
        )
        Spacer(Modifier.height(10.dp))
        PermissionCard(
            index = 5,
            title = "Unrestricted battery",
            purpose = "Stops aggressive OEM savers from killing her in the background.",
            icon = Icons.Default.BatteryChargingFull,
            granted = battery,
            onGrant = { openBatterySettings(context) },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionCard(
    index: Int,
    title: String,
    purpose: String,
    icon: ImageVector,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    val borderColor = if (granted) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val bg = if (granted) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShiinaShapes.Large)
            .background(bg)
            .border(1.dp, borderColor, ShiinaShapes.Large)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShiinaIconBadge(
            icon = icon,
            tint = if (granted) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            size = 42.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$index. $title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = purpose,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Granted",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            FilledTonalButton(
                onClick = onGrant,
                shape = ShiinaShapes.Medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Grant", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ============================================================
// Step 4 — Preferences
// ============================================================
@Composable
private fun StepPreferences(viewModel: SettingsViewModel) {
    val voiceEnabled by viewModel.voiceTtsEnabled.collectAsState()
    val screenshotEnabled by viewModel.screenshotEnabled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        ShiinaIconBadge(
            icon = Icons.Default.RecordVoiceOver,
            size = 52.dp,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Voice & vision",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Choose how Shiina speaks and whether she can see your screen.",
            style = ShiinaTextStyles.OnboardingBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        PreferenceCard(
            icon = Icons.Default.RecordVoiceOver,
            title = "Voice output",
            body = "Speaks replies aloud with pitch and pacing matched to her mood.",
            checked = voiceEnabled,
            accent = MaterialTheme.colorScheme.tertiary,
            onChange = { viewModel.toggleVoiceTts(it) },
        )
        Spacer(Modifier.height(12.dp))
        PreferenceCard(
            icon = Icons.Default.CameraAlt,
            title = "Screen vision",
            body = "Captures small, optimised frames so she can inspect app state before acting.",
            checked = screenshotEnabled,
            accent = MaterialTheme.colorScheme.primary,
            onChange = { viewModel.toggleScreenshot(it) },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PreferenceCard(
    icon: ImageVector,
    title: String,
    body: String,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShiinaShapes.Large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                ShiinaShapes.Large,
            )
            .clickable { onChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShiinaIconBadge(icon = icon, tint = accent, size = 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ============================================================
// Step 5 — Ready
// ============================================================
@Composable
private fun StepReady() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        AnimatedCheck(size = 96.dp, color = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "You're all set",
            style = ShiinaTextStyles.OnboardingTitle,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Shiina is ready to help you navigate, check in, and keep your rhythm balanced.",
            style = ShiinaTextStyles.OnboardingBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ) {
            Text(
                text = "Quick tips",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            TipRow("Tap the floating avatar to open chat anytime.")
            Spacer(Modifier.height(8.dp))
            TipRow("Long-press the avatar to hide it temporarily.")
            Spacer(Modifier.height(8.dp))
            TipRow("Try: \u201cSet an alarm for 7:30 AM\u201d or \u201cOpen YouTube and search piano music\u201d.")
            Spacer(Modifier.height(8.dp))
            TipRow("She learns new procedures as you explore together.")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TipRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
