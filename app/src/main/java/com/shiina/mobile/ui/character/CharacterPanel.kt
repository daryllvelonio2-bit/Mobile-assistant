package com.shiina.mobile.ui.character

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CharacterPanel(viewModel: CharacterViewModel) {
    val tone by viewModel.tone.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val renderError by viewModel.error.collectAsState()
    val overlayOk = viewModel.canOverlay()

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Character", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Tone $tone · Mode $mode",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!overlayOk) {
            Text(
                text = "Overlay permission not granted. Use Permissions above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (renderError != null) {
            Text(
                text = renderError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { viewModel.renderLatest() }, enabled = overlayOk) {
                Text("Render latest")
            }
            OutlinedButton(
                onClick = { viewModel.show() },
                enabled = overlayOk,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Show")
            }
            OutlinedButton(
                onClick = { viewModel.hide() },
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text("Hide")
            }
        }
    }
}
