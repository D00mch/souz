package ru.souz.ui.sharedchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

data class SharedChatUiState(
    val apiKey: String,
    val model: String,
    val input: String,
    val messages: List<SharedChatMessageUi>,
    val status: String? = null,
    val isSending: Boolean = false,
)

data class SharedChatMessageUi(
    val id: String,
    val role: String,
    val content: String,
)

sealed interface SharedChatEvent {
    data class ApiKeyChanged(val value: String) : SharedChatEvent
    data class ModelChanged(val value: String) : SharedChatEvent
    data class InputChanged(val value: String) : SharedChatEvent
    data object SaveSettings : SharedChatEvent
    data object ClearConversation : SharedChatEvent
    data object SendMessage : SharedChatEvent
}

@Composable
fun SharedChatScreen(
    state: SharedChatUiState,
    onEvent: (SharedChatEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    MaterialTheme {
        Surface(modifier = modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
            ) {
                val isWide = maxWidth >= 720.dp
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        SettingsPanel(
                            state = state,
                            onEvent = onEvent,
                            modifier = Modifier.width(320.dp),
                        )
                        ChatPanel(
                            state = state,
                            listStateModifier = Modifier.weight(1f),
                            onEvent = onEvent,
                            listState = listState,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SettingsPanel(
                            state = state,
                            onEvent = onEvent,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ChatPanel(
                            state = state,
                            listStateModifier = Modifier.weight(1f),
                            onEvent = onEvent,
                            listState = listState,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }
}

@Composable
private fun SettingsPanel(
    state: SharedChatUiState,
    onEvent: (SharedChatEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Souz Android", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = { onEvent(SharedChatEvent.ApiKeyChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = state.model,
                onValueChange = { onEvent(SharedChatEvent.ModelChanged(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                singleLine = true,
            )
            Button(
                onClick = { onEvent(SharedChatEvent.SaveSettings) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Save")
            }
            state.status?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChatPanel(
    state: SharedChatUiState,
    listStateModifier: Modifier,
    onEvent: (SharedChatEvent) -> Unit,
    listState: LazyListState,
) {
    Surface(
        modifier = listStateModifier,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Chat", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { onEvent(SharedChatEvent.ClearConversation) },
                    enabled = state.messages.isNotEmpty() && !state.isSending,
                ) {
                    Text("Clear")
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            text = "Enter an API key, save settings, and send a message.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (state.isSending) {
                    item {
                        Text("Thinking...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = { onEvent(SharedChatEvent.InputChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !state.isSending,
                )
                Button(
                    onClick = { onEvent(SharedChatEvent.SendMessage) },
                    enabled = state.input.isNotBlank() && !state.isSending,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (state.isSending) "Sending" else "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: SharedChatMessageUi) {
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.content.ifBlank { "..." },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
