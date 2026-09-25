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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.shiina.mobile.observation.CaptureConsent
import com.shiina.mobile.observation.ObservationService
import com.shiina.mobile.theme.CompanionTheme
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Shiina",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (!allPermissionsGranted) {
                        IconButton(onClick = { selectedTab = 3 }) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Permissions needed",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "Companion",
                        )
                    },
                    label = { Text("Companion") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Face,
                            contentDescription = "Chat",
                        )
                    },
                    label = { Text("Chat") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = "Memory",
                        )
                    },
                    label = { Text("Memory") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )

                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        if (!allPermissionsGranted) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.error,
                                    )
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                )
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTab) {
                0 -> CharacterPanel(viewModel = characterVm)
                1 -> ChatScreen()
                2 -> MemoryPanel(viewModel = settingsVm)
                3 -> SettingsScreen(viewModel = settingsVm, onReplayOnboarding = onReplayOnboarding)
            }
        }
    }
}
