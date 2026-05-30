package ru.souz.android.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.souz.android.llms.AndroidOpenAiClient
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.android.storage.AndroidChatDatabase
import ru.souz.android.storage.AndroidChatMessage

@Composable
fun SouzAndroidApp(
    settings: AndroidSettingsProvider,
    chatDatabase: AndroidChatDatabase,
    chatClient: AndroidOpenAiClient,
) {
    var apiKey by remember { mutableStateOf(settings.openaiKey.orEmpty()) }
    var model by remember { mutableStateOf(settings.chatModelAlias) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(chatDatabase.listMessages()) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    fun saveSettings() {
        settings.openaiKey = apiKey
        settings.chatModelAlias = model
        status = "Settings saved"
    }

    fun clearConversation() {
        chatDatabase.clear()
        messages = emptyList()
        status = "Conversation cleared"
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || isSending) return
        input = ""
        isSending = true
        status = null
        scope.launch {
            val userMessage = chatDatabase.appendMessage("user", text)
            messages = messages + userMessage
            val assistantMessage = chatDatabase.appendMessage("assistant", "")
            messages = messages + assistantMessage
            val response = chatClient.respond(messages.filter { it.content.isNotBlank() })
            val assistantText = response.getOrElse { error ->
                "Error: ${error.message ?: error.toString()}"
            }
            chatDatabase.updateMessageContent(assistantMessage.id, assistantText)
            messages = chatDatabase.listMessages()
            status = response.exceptionOrNull()?.message
            isSending = false
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
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
                            apiKey = apiKey,
                            model = model,
                            status = status,
                            onApiKeyChange = { apiKey = it },
                            onModelChange = { model = it },
                            onSave = ::saveSettings,
                            modifier = Modifier.width(320.dp),
                        )
                        ChatPanel(
                            messages = messages,
                            input = input,
                            isSending = isSending,
                            listStateModifier = Modifier.weight(1f),
                            onInputChange = { input = it },
                            onClear = ::clearConversation,
                            onSend = ::sendMessage,
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
                            apiKey = apiKey,
                            model = model,
                            status = status,
                            onApiKeyChange = { apiKey = it },
                            onModelChange = { model = it },
                            onSave = ::saveSettings,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ChatPanel(
                            messages = messages,
                            input = input,
                            isSending = isSending,
                            listStateModifier = Modifier.weight(1f),
                            onInputChange = { input = it },
                            onClear = ::clearConversation,
                            onSend = ::sendMessage,
                            listState = listState,
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
}

@Composable
private fun SettingsPanel(
    apiKey: String,
    model: String,
    status: String?,
    onApiKeyChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onSave: () -> Unit,
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
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = onModelChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                singleLine = true,
            )
            Button(onClick = onSave, modifier = Modifier.align(Alignment.End)) {
                Text("Save")
            }
            status?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChatPanel(
    messages: List<AndroidChatMessage>,
    input: String,
    isSending: Boolean,
    listStateModifier: Modifier,
    onInputChange: (String) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
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
                TextButton(onClick = onClear, enabled = messages.isNotEmpty() && !isSending) {
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
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "Enter an API key, save settings, and send a message.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
                if (isSending) {
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
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Message") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !isSending,
                )
                Button(
                    onClick = onSend,
                    enabled = input.isNotBlank() && !isSending,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(if (isSending) "Sending" else "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: AndroidChatMessage) {
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
