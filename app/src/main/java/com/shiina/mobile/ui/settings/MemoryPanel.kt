package com.shiina.mobile.ui.settings

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shiina.mobile.data.db.KnowledgeNote
import com.shiina.mobile.data.db.MemoryFact
import com.shiina.mobile.debug.DebugTalkService
import com.shiina.mobile.theme.ShiinaMotion
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.theme.ShiinaTextStyles
import com.shiina.mobile.ui.components.ShiinaCard
import com.shiina.mobile.ui.components.ShiinaDivider
import com.shiina.mobile.ui.components.ShiinaEmptyState
import com.shiina.mobile.ui.components.ShiinaSectionHeader
import com.shiina.mobile.ui.components.ShiinaStat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory — everything Shiina holds onto, inspectable and editable.
 */
@Composable
fun MemoryPanel(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val facts by viewModel.facts.collectAsState()
    val status by viewModel.memoryStatus.collectAsState()
    val digests by viewModel.digests.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val fullContext by viewModel.fullContext.collectAsState()
    val context = LocalContext.current
    var armed by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- Hero ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShiinaShapes.ExtraLarge)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.06f),
                            Color.Transparent,
                        ),
                    ),
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ShiinaShapes.ExtraLarge,
                )
                .padding(18.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(ShiinaShapes.Medium)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Long-term memory",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ShiinaStat(
                        value = "${facts.size}",
                        label = "Facts",
                        icon = Icons.Default.Insights,
                        modifier = Modifier.weight(1f),
                    )
                    ShiinaStat(
                        value = "${digests.size}",
                        label = "Digests",
                        icon = Icons.Default.Timer,
                        accent = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- Facts ----
        ShiinaSectionHeader(
            title = "Learned facts",
            eyebrow = "Knowledge",
            subtitle = "Preferences and details she has picked up",
            icon = Icons.Default.Insights,
        )
        Spacer(Modifier.height(10.dp))

        if (facts.isEmpty()) {
            ShiinaEmptyState(
                icon = Icons.Default.Psychology,
                title = "Nothing learned yet",
                body = "As you interact, Shiina quietly builds a picture of your preferences, routines, and favourites.",
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                facts.forEach { fact ->
                    FactCard(
                        fact = fact,
                        onSave = { viewModel.updateFact(fact.key, it) },
                        onDelete = { viewModel.deleteFact(fact.key) },
                    )
                }
            }
        }

        // ---- Journal (Phase 3) ----
        Spacer(Modifier.height(28.dp))
        ShiinaSectionHeader(
            title = "Journal",
            eyebrow = "Notes",
            subtitle = if (notes.isEmpty()) "Ideas, plans and decisions you share" else "${notes.size} note${if (notes.size == 1) "" else "s"} captured",
            icon = Icons.Default.Psychology,
        )
        Spacer(Modifier.height(10.dp))
        if (notes.isEmpty()) {
            ShiinaEmptyState(
                icon = Icons.Default.Psychology,
                title = "No notes yet",
                body = "Ideas, plans, tasks and decisions you mention in chat surface here, grouped by topic.",
            )
        } else {
            notes.groupBy { it.topic }.toList().sortedBy { it.first }.forEach { (topic, topicNotes) ->
                Text(
                    text = topic,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    topicNotes.forEach { note ->
                        NoteCard(note = note, onDelete = { viewModel.deleteNote(note.id) })
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ---- Digests ----
        if (digests.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            ShiinaSectionHeader(
                title = "Weekly digests",
                eyebrow = "Summaries",
                subtitle = "Condensed recaps of your shared time",
                icon = Icons.Default.Timer,
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                digests.forEach { digest ->
                    ShiinaCard(
                        modifier = Modifier.fillMaxWidth(),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.25f),
                        borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f),
                    ) {
                        Text(
                            text = digest.digest,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---- Active prompt context ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShiinaShapes.Medium)
                .clickable { showContext = !showContext }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Active prompt context",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${fullContext.length} characters carried into every reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.refreshContext() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh context",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { showContext = !showContext },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (showContext) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showContext) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = showContext,
            enter = fadeIn(ShiinaMotion.TweenMedium) + slideInVertically { -it / 4 },
            exit = fadeOut(ShiinaMotion.TweenFast),
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                ShiinaCard(
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DataObject,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Raw context block",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = fullContext,
                        style = ShiinaTextStyles.CodeBlock,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---- Danger zone ----
        ShiinaCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Erase her memory",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Permanently clears facts, learned memory, procedures, episodes, and conversation context. This cannot be undone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    if (!armed) {
                        armed = true
                    } else {
                        armed = false
                        viewModel.forgetAll()
                        runCatching {
                            context.startService(
                                Intent(context, DebugTalkService::class.java).apply {
                                    action = DebugTalkService.ACTION_RESET_SESSION
                                },
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = ShiinaShapes.Medium,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                ),
            ) {
                Text(
                    text = if (armed) "Tap again to confirm wipe" else "Forget everything",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun NoteCard(
    note: KnowledgeNote,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShiinaShapes.Large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), ShiinaShapes.Large)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KindChip(note.kind)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete note",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = note.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(note.createdMillis))} · ${note.source}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KindChip(kind: String) {
    Box(
        modifier = Modifier
            .clip(ShiinaShapes.Full)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text = kind,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun FactCard(
    fact: MemoryFact,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var draft by remember(fact.key, fact.value) { mutableStateOf(fact.value) }
    var editing by remember { mutableStateOf(false) }
    val isModified = draft != fact.value
    val borderColor by animateColorAsState(
        targetValue = if (isModified) MaterialTheme.colorScheme.secondary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        animationSpec = ShiinaMotion.TweenMediumColor,
        label = "factBorder",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShiinaShapes.Large)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, borderColor, ShiinaShapes.Large)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = fact.key,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete fact",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (editing) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4,
                shape = ShiinaShapes.Medium,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(draft)
                        editing = false
                    },
                    enabled = isModified,
                    shape = ShiinaShapes.Medium,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                OutlinedButton(
                    onClick = {
                        draft = fact.value
                        editing = false
                    },
                    shape = ShiinaShapes.Medium,
                ) { Text("Cancel") }
            }
        } else {
            Text(
                text = fact.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(ShiinaShapes.Full)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "%.0f%% confidence · %s".format(
                            fact.confidence * 100,
                            fact.source,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = { editing = true },
                    shape = ShiinaShapes.Medium,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Edit", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
