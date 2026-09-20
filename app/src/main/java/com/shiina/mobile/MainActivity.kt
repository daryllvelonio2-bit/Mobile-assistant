package com.shiina.mobile

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.shiina.mobile.observation.CaptureConsent
import com.shiina.mobile.observation.ObservationService
import com.shiina.mobile.theme.CompanionTheme
import com.shiina.mobile.ui.character.CharacterPanel
import com.shiina.mobile.ui.character.CharacterViewModel
import com.shiina.mobile.ui.permissions.PermissionScreen
import com.shiina.mobile.ui.settings.MemoryPanel
import com.shiina.mobile.ui.settings.SettingsScreen
import com.shiina.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

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
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as CompanionApp).container
        val settingsVm = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SettingsViewModel(container.settingsRepository, container.keyStore, container.memoryStore, container.memoryContext) as T
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
                    ) as T
            },
        )[CharacterViewModel::class.java]
        setContent {
            CompanionTheme {
                Column(modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    PermissionScreen()
                    SettingsScreen(settingsVm)
                    CharacterPanel(characterVm)
                    MemoryPanel(settingsVm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Screenshot toggle on but no projection yet -> ask for capture consent once.
        CoroutineScope(Dispatchers.Main).launch {
            val container = (application as CompanionApp).container
            val enabled = runCatching {
                container.settingsRepository.screenshotEnabled.first()
            }.getOrDefault(false)
            if (enabled && !container.screenshotTaker.ready) {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                runCatching { captureConsent.launch(mpm.createScreenCaptureIntent()) }
            }
        }
    }
}
