package com.shiina.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shiina.mobile.CompanionApp
import com.shiina.mobile.data.db.ChatTurn
import com.shiina.mobile.debug.ChatBus
import com.shiina.mobile.debug.ChatSend
import com.shiina.mobile.theme.ShiinaMotion
import com.shiina.mobile.theme.ShiinaShapes
import com.shiina.mobile.ui.components.MoodOrb
import com.shiina.mobile.ui.components.ShiinaChip
import com.shiina.mobile.ui.components.TypingDots
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * In-app chat with Shiina (BACKLOG B1). Turns stream live from Room
 * (chat_turns); sending forwards into DebugTalkService's agent loop via ChatSend.
 * Echo + busy/progress state come from ChatBus so the UI reacts instantly even
 * before the DB turn lands.
 */
@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as CompanionApp).container

    val turns by container.chatTurnDao.observeRecent(200)
        .collectAsState(initial = emptyList())
    val busy by ChatBus.busy.collectAsState()
    val progress by ChatBus.progress.collectAsState()
    val lastEcho by ChatBus.lastUserText.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Dedup: once the echoed user turn arrives from the DB, drop the local echo.
    val echoText = lastEcho?.takeIf { pending ->
        turns.firstOrNull { it.role.equals("user", ignoreCase = true) }?.text != pending &&
            turns.none { it.role.equals("user", ignoreCase = true) && it.text == pending }
    }

    val chronological = remember(turns, echoText) {
        turns.reversed() + if (echoText != null) {
            listOf(
                ChatTurn(
                    id = -1L,
                    role = "user",
                    text = echoText,
                    timestampMillis = System.currentTimeMillis(),
                ),
            )
        } else emptyList()
    }

    LaunchedEffect(chronological.size, busy) {
        if (chronological.isNotEmpty()) listState.animateScrollToItem(chronological.size)
    }

    // Show a scroll-to-bottom affordance when the user has scrolled away.
    val showJumpToBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last < listState.layoutInfo.totalItemsCount - 2
        }
    }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (chronological.isEmpty() && !busy) {
                ChatEmptyState(
                    onSuggestion = { text ->
                        ChatSend.text(context, text)
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 12.dp,
                    ),
                ) {
                    var lastDay: Int = -1
                    var lastRole: String? = null
                    chronological.forEachIndexed { index, turn ->
                        val dayBucket = dayOf(turn.timestampMillis)
                        if (dayBucket != lastDay) {
                            item(key = "day-$dayBucket") {
                                DaySeparator(turn.timestampMillis)
                            }
                            lastDay = dayBucket
                            lastRole = null
                        }
                        val sameAuthor =
                            lastRole != null && lastRole.equals(turn.role, ignoreCase = true)
                        item(key = "turn-${turn.id}-${turn.timestampMillis}") {
                            ChatBubble(turn = turn, grouped = sameAuthor)
                        }
                        lastRole = turn.role
                    }

                    if (busy) {
                        item(key = "progress") {
                            ThinkingRow(
                                progress = progress,
                                onStop = { ChatSend.stop(context) },
                            )
                        }
                    }

                    item(key = "tail-spacer") {
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            ChatInputBar(
                value = input,
                onValueChange = { input = it.take(500) },
                busy = busy,
                onSend = {
                    if (input.isNotBlank()) {
                        ChatSend.text(context, input)
                        input = ""
                    }
                },
                onStop = { ChatSend.stop(context) },
            )
        }

        // Jump-to-bottom FAB
        AnimatedVisibility(
            visible = showJumpToBottom && chronological.isNotEmpty(),
            enter = fadeIn(ShiinaMotion.TweenMedium) + slideInVertically { it / 2 },
            exit = fadeOut(ShiinaMotion.TweenFast) + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 96.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable {
                        scope.launch {
                            val target = listState.layoutInfo.totalItemsCount
                            if (target > 0) listState.animateScrollToItem(target - 1)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Jump to latest",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun ChatEmptyState(
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suggestions = listOf(
        "What can you do on my phone?" to "See her full capabilities",
        "Set an alarm for 7:30 AM" to "Try a device action",
        "What do you remember about me?" to "Inspect her memory",
        "Play some music" to "Start a media task",
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MoodOrb(mood = "calm", size = 132.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Start a conversation",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ask a question, hand off a task, or just say hi. Shiina sees your screen and can act on your phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            suggestions.forEach { (prompt, hint) ->
                SuggestionCard(prompt = prompt, hint = hint, onClick = { onSuggestion(prompt) })
            }
        }
    }
}

@Composable
private fun SuggestionCard(prompt: String, hint: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = ShiinaMotion.Components.CardPress,
        label = "sugg",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(ShiinaShapes.Medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ShiinaShapes.Medium,
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(ShiinaShapes.Small)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = prompt,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DaySeparator(timestamp: Long) {
    val label = remember(timestamp) {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = timestamp }
        when {
            sameDay(now, then) -> "Today"
            isYesterday(now, then) -> "Yesterday"
            else -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(ShiinaShapes.Full)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn, grouped: Boolean) {
    val isUser = turn.role.equals("user", ignoreCase = true)
    val time = remember(turn.timestampMillis) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(turn.timestampMillis))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (grouped) 2.dp else 8.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            if (!isUser) {
                if (!grouped) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.tertiary,
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Shiina",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                } else {
                    Spacer(Modifier.width(36.dp))
                }
            }

            Column(
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                if (!isUser && !grouped) {
                    Text(
                        text = "Shiina",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 10.dp, bottom = 3.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .widthIn(max = 292.dp)
                        .clip(
                            if (isUser) ShiinaShapes.BubbleUser else ShiinaShapes.BubbleAssistant,
                        )
                        .background(
                            if (isUser) {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.88f),
                                    ),
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                                    ),
                                )
                            },
                        )
                        .padding(horizontal = 15.dp, vertical = 11.dp),
                ) {
                    Text(
                        text = turn.text,
                        style = com.shiina.mobile.theme.ShiinaTextStyles.ChatBubbleAssistant,
                        color = if (isUser) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (!grouped) {
                    Text(
                        text = time,
                        style = com.shiina.mobile.theme.ShiinaTextStyles.ChatBubbleTime,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow(progress: String, onStop: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(
                    androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 20.dp,
                        bottomStart = 20.dp,
                        bottomEnd = 20.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypingDots()
                if (progress.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = progress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        ShiinaChip(
            text = "Stop",
            icon = Icons.Default.Stop,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onStop,
        )
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val canSend = value.isNotBlank()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sendScale by animateFloatAsState(
        targetValue = if (pressed && canSend) 0.9f else 1f,
        animationSpec = ShiinaMotion.Components.ButtonPress,
        label = "send",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(ShiinaShapes.ExtraLarge)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    ShiinaShapes.ExtraLarge,
                ),
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        text = if (busy) "Shiina is working…" else "Message Shiina…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp, max = 150.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                .clip(CircleShape)
                .background(
                    when {
                        busy -> MaterialTheme.colorScheme.errorContainer
                        canSend -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = { if (busy) onStop() else if (canSend) onSend() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (busy) Icons.Default.Stop else Icons.Filled.ArrowUpward,
                contentDescription = if (busy) "Stop" else "Send",
                tint = when {
                    busy -> MaterialTheme.colorScheme.onErrorContainer
                    canSend -> MaterialTheme.colorScheme.onPrimary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun dayOf(millis: Long): Int {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun isYesterday(now: Calendar, then: Calendar): Boolean {
    val clone = now.clone() as Calendar
    clone.add(Calendar.DAY_OF_YEAR, -1)
    return sameDay(clone, then)
}
