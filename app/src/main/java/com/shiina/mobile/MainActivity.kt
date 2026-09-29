package com.shiina.mobile

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.shiina.mobile.observation.CaptureConsent
import com.shiina.mobile.observation.ObservationService
import com.shiina.mobile.theme.CompanionTheme
import com.shiina.mobile.theme.ShiinaMotion
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.ui.chat.ChatScreen
import com.shiina.mobile.ui.character.CharacterPanel
import com.shiina.mobile.ui.character.CharacterViewModel
import com.shiina.mobile.ui.onboarding.OnboardingScreen
import com.shiina.mobile.ui.permissions.hasAllPermissions
import com.shiina.mobile.ui.settings.MemoryPanel
import com.shiina.mobile.ui.settings.SettingsScreen
import com.shiina.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Set by the overlay bubble tap (same process) to open the Chat tab. */
        @Volatile var pendingTab: Int? = null
    }

    private fun applyPendingTab() {
        pendingTab?.let {
            requestedTab.intValue = it
            pendingTab = null
        }
    }

    /** Observed by MainAppScreen to switch tabs from outside the composition. */
    private val requestedTab = androidx.compose.runtime.mutableIntStateOf(-1)

    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val captureConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK && res.data != null) {
                CaptureConsent.resultCode = res.resultCode
                CaptureConsent.data = res.data
                val i = Intent(this, ObservationService::class.java).apply {
                    action = ObservationService.ACTION_START_PROJECTION
                }
                runCatching { startForegroundService(i) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching { com.shiina.mobile.debug.DebugTalkService.start(this) }
        val container = (application as CompanionApp).container
        val settingsVm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(container.settingsRepository, container.keyStore, container.memoryStore, container.memoryContext, container.learnedMemoryManager, container.proceduralMemoryStore) as T
            },
        )[SettingsViewModel::class.java]
        val characterVm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CharacterViewModel(
                        applicationContext,
                        container.providerRegistry,
                        container.usageReader,
                        container.baselineUpdater,
                        container.memoryEpisodeDao,
                        container.database.goalDao(),
                        container.sleepReader,
                    ) as T
            },
        )[CharacterViewModel::class.java]

        setContent {
            applyPendingTab()
            CompanionTheme {
                val onboardingCompleted by settingsVm.onboardingCompleted.collectAsState()
                var replayOnboarding by remember { mutableStateOf(false) }

                if (!onboardingCompleted || replayOnboarding) {
                    OnboardingScreen(
                        viewModel = settingsVm,
                        onComplete = {
                            replayOnboarding = false
                            settingsVm.completeOnboarding()
                        },
                    )
                } else {
                    MainAppScreen(
                        characterVm = characterVm,
                        settingsVm = settingsVm,
                        requestedTab = requestedTab,
                        onReplayOnboarding = { replayOnboarding = true },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Overlay bubble tap: bring the existing activity forward on the Chat tab.
        applyPendingTab()
    }

    override fun onResume() {
        super.onResume()
        val container = (application as CompanionApp).container
        runCatching { container.userActivityTracker.recordActivity() }
        // Screenshot toggle on but no projection yet -> ask for capture consent once.
        lifecycleScope.launch {
            val enabled = runCatching {
                container.settingsRepository.screenshotEnabled.first()
            }.getOrDefault(false)
            if (enabled && !container.screenshotTaker.ready) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                runCatching { captureConsent.launch(mpm.createScreenCaptureIntent()) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val container = (application as CompanionApp).container
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { container.memoryConsolidator.consolidate() }
        }
    }
}

/**
 * Navigation destinations. Order defines the bottom-bar arrangement.
 */
private enum class ShiinaTab(
    val label: String,
    val subtitle: String,
    val filledIcon: ImageVector,
    val outlinedIcon: ImageVector,
) {
    Companion(
        label = "Companion",
        subtitle = "Your presence on screen",
        filledIcon = Icons.Filled.Face,
        outlinedIcon = Icons.Outlined.Face,
    ),
    Chat(
        label = "Chat",
        subtitle = "Talk with Shiina",
        filledIcon = Icons.Filled.Psychology,
        outlinedIcon = Icons.Outlined.Psychology,
    ),
    Memory(
        label = "Memory",
        subtitle = "What she remembers",
        filledIcon = Icons.Filled.Lock,
        outlinedIcon = Icons.Outlined.Lock,
    ),
    Settings(
        label = "Settings",
        subtitle = "Preferences & access",
        filledIcon = Icons.Filled.Settings,
        outlinedIcon = Icons.Outlined.Settings,
    ),
}

@Composable
private fun MainAppScreen(
    characterVm: CharacterViewModel,
    settingsVm: SettingsViewModel,
    requestedTab: androidx.compose.runtime.IntState,
    onReplayOnboarding: () -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val reqTab by requestedTab
    LaunchedEffect(reqTab) {
        if (reqTab in 0..3) selectedTab = reqTab
    }
    val context = LocalContext.current
    val allPermissionsGranted = hasAllPermissions(context)
    val tabs = ShiinaTab.entries
    val activeTab = tabs[selectedTab.coerceIn(0, tabs.size - 1)]

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- Adaptive header ---
            ShiinaHeader(
                title = activeTab.label,
                subtitle = activeTab.subtitle,
                showPermissionWarning = !allPermissionsGranted && activeTab != ShiinaTab.Settings,
                onPermissionClick = { selectedTab = ShiinaTab.Settings.ordinal },
            )

            // --- Content with crossfade + subtle scale ---
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        (fadeIn(ShiinaMotion.TweenMediumSlow) + scaleIn(
                            initialScale = 0.985f,
                            animationSpec = ShiinaMotion.TweenMediumSlow,
                        )).togetherWith(
                            fadeOut(ShiinaMotion.TweenFast) + scaleOut(
                                targetScale = 1.01f,
                                animationSpec = ShiinaMotion.TweenFast,
                            ),
                        )
                    },
                    label = "tab_content",
                ) { tab ->
                    when (tab) {
                        0 -> CharacterPanel(viewModel = characterVm)
                        1 -> ChatScreen()
                        2 -> MemoryPanel(viewModel = settingsVm)
                        else -> SettingsScreen(
                            viewModel = settingsVm,
                            onReplayOnboarding = onReplayOnboarding,
                        )
                    }
                }
            }

            // --- Floating pill bottom navigation ---
            ShiinaBottomBar(
                selectedIndex = selectedTab,
                tabs = tabs,
                permissionBadge = !allPermissionsGranted,
                onSelect = { selectedTab = it },
            )
        }
    }
}

@Composable
private fun ShiinaHeader(
    title: String,
    subtitle: String,
    showPermissionWarning: Boolean,
    onPermissionClick: () -> Unit,
) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.0f),
                    ),
                ),
            )
            .padding(
                top = statusBar + 12.dp,
                start = 20.dp,
                end = 20.dp,
                bottom = 8.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showPermissionWarning) {
            Box(
                modifier = Modifier
                    .clip(ShiinaShapes.Full)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable(onClick = onPermissionClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Permissions required",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "Setup",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShiinaBottomBar(
    selectedIndex: Int,
    tabs: List<ShiinaTab>,
    permissionBadge: Boolean,
    onSelect: (Int) -> Unit,
) {
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                bottom = navBarPadding + 12.dp,
                top = 6.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = ShiinaShapes.Full,
                    ambientColor = Color.Black.copy(alpha = 0.35f),
                    spotColor = Color.Black.copy(alpha = 0.35f),
                )
                .clip(ShiinaShapes.Full)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                NavPillItem(
                    tab = tab,
                    selected = selectedIndex == index,
                    showBadge = permissionBadge && tab == ShiinaTab.Settings,
                    onClick = { onSelect(index) },
                )
            }
        }
    }
}

@Composable
private fun NavPillItem(
    tab: ShiinaTab,
    selected: Boolean,
    showBadge: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = ShiinaMotion.Components.NavItemSelect,
        label = "navScale",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(ShiinaShapes.Full)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = if (selected) 14.dp else 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Icon(
                imageVector = if (selected) tab.filledIcon else tab.outlinedIcon,
                contentDescription = tab.label,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            if (showBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = fadeIn(ShiinaMotion.TweenMediumSlow) + scaleIn(
                initialScale = 0.7f,
                animationSpec = ShiinaMotion.TweenMediumSlow,
            ),
            exit = fadeOut(ShiinaMotion.TweenFast) + scaleOut(
                targetScale = 0.7f,
                animationSpec = ShiinaMotion.TweenFast,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            }
        }
    }
}
