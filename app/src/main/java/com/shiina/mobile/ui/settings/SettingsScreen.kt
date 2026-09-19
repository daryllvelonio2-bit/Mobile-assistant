package com.shiina.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val screenshot by viewModel.screenshotEnabled.collectAsState()
    val hour by viewModel.alarmHour.collectAsState()
    val minute by viewModel.alarmMinute.collectAsState()
    val geminiKey by viewModel.geminiKey.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Screenshot capture", modifier = Modifier.weight(1f))
            Switch(checked = screenshot, onCheckedChange = viewModel::toggleScreenshot)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Alarm %02d:%02d".format(hour, minute), modifier = Modifier.weight(1f))
            Button(onClick = { viewModel.setAlarm((hour + 1) % 24, minute) }) { Text("+1h") }
        }
        OutlinedTextField(
            value = geminiKey,
            onValueChange = viewModel::updateGeminiKey,
            label = { Text("Gemini API Key") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
}
