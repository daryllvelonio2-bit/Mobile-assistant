package com.shiina.mobile

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
import com.shiina.mobile.theme.CompanionTheme
import com.shiina.mobile.ui.character.CharacterPanel
import com.shiina.mobile.ui.character.CharacterViewModel
import com.shiina.mobile.ui.permissions.PermissionScreen
import com.shiina.mobile.ui.settings.SettingsScreen
import com.shiina.mobile.ui.settings.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val notificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
                    SettingsViewModel(container.settingsRepository, container.keyStore) as T
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
                    ) as T
            },
        )[CharacterViewModel::class.java]
        setContent {
            CompanionTheme {
                Column(modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) {
                    PermissionScreen()
                    SettingsScreen(settingsVm)
                    CharacterPanel(characterVm)
                }
            }
        }
    }
}
