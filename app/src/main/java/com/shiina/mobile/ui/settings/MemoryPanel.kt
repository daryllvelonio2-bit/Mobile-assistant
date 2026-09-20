package com.shiina.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shiina.mobile.data.db.MemoryFact

/** Phase 6 memory viewer: status, facts list (edit/delete), weekly digests, forget everything. */
@Composable
fun MemoryPanel(viewModel: SettingsViewModel) {
    val facts by viewModel.facts.collectAsState()
    val status by viewModel.memoryStatus.collectAsState()
    val digests by viewModel.digests.collectAsState()
    val fullContext by viewModel.fullContext.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var armed by remember { mutableStateOf(false) }
    // No scroll here: MainActivity's root column already scrolls — a nested
    // scrollable gets infinite constraints and crashes the app on launch.
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Text("Memory", style = MaterialTheme.typography.titleLarge)
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            facts.forEach { fact ->
                FactRow(fact, viewModel)
            }
        }
        if (digests.isNotEmpty()) {
            Text(
                text = "Weekly digests",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            digests.forEach { d ->
                Text(
                    text = d.digest,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        OutlinedButton(
            onClick = { viewModel.refreshContext() },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text("Refresh total context")
        }
        Text(
            text = "Total context — exactly what she carries into every reply (${fullContext.length} chars):",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = fullContext,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = {
                if (!armed) {
                    armed = true
                } else {
                    armed = false
                    viewModel.forgetAll()
                    runCatching {
                        context.startService(
                            android.content.Intent(
                                context,
                                com.shiina.mobile.debug.DebugTalkService::class.java,
                            ).setAction(
                                com.shiina.mobile.debug.DebugTalkService.ACTION_RESET_SESSION,
                            ),
                        )
                    }
                }
            },
            modifier = Modifier.padding(top = 12.dp),
        ) {
            Text(
                if (armed) "Tap again to confirm wipe" else "Forget everything",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun FactRow(fact: MemoryFact, viewModel: SettingsViewModel) {
    var draft by remember(fact.key, fact.value) { mutableStateOf(fact.value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(fact.key, style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "%.1f confidence · ${fact.source}".format(fact.confidence),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            if (draft != fact.value) {
                OutlinedButton(onClick = { viewModel.updateFact(fact.key, draft) }) {
                    Text("Save")
                }
            }
            OutlinedButton(onClick = { viewModel.deleteFact(fact.key) }) {
                Text("Delete")
            }
        }
    }
}