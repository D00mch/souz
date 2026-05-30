package ru.souz.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import ru.souz.android.agent.AndroidAgentRuntime
import ru.souz.android.settings.AndroidSettingsProvider
import ru.souz.android.storage.AndroidChatDatabase
import ru.souz.android.storage.AndroidChatMessage
import ru.souz.llms.ToolInvocationMeta
import ru.souz.ui.sharedchat.SharedChatEvent
import ru.souz.ui.sharedchat.SharedChatMessageUi
import ru.souz.ui.sharedchat.SharedChatScreen
import ru.souz.ui.sharedchat.SharedChatUiState

@Composable
fun SouzAndroidApp(
    settings: AndroidSettingsProvider,
    chatDatabase: AndroidChatDatabase,
    agentRuntime: AndroidAgentRuntime,
) {
    var apiKey by remember { mutableStateOf(settings.openaiKey.orEmpty()) }
    var model by remember { mutableStateOf(settings.chatModelAlias) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var isSending by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(chatDatabase.listMessages()) }
    val scope = rememberCoroutineScope()

    fun saveSettings() {
        settings.openaiKey = apiKey
        settings.chatModelAlias = model
        status = "Settings saved"
    }

    fun clearConversation() {
        chatDatabase.clear()
        agentRuntime.agentFacade.clearContext()
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
            try {
                val userMessage = chatDatabase.appendMessage("user", text)
                messages = messages + userMessage
                val assistantMessage = chatDatabase.appendMessage("assistant", "")
                messages = messages + assistantMessage

                val response = runCatching {
                    agentRuntime.agentFacade.setModel(settings.gigaModel)
                    agentRuntime.agentFacade.setContextSize(settings.contextSize)
                    agentRuntime.agentFacade.execute(
                        input = text,
                        toolInvocationMetaOverride = ToolInvocationMeta.localDefault(
                            conversationId = "android-local-chat",
                            requestId = userMessage.id,
                            attributes = mapOf(
                                "userMessageId" to userMessage.id,
                                "assistantMessageId" to assistantMessage.id,
                            ),
                        ),
                    )
                }
                val assistantText = response.getOrElse { error ->
                    "Error: ${error.message ?: error.toString()}"
                }
                chatDatabase.updateMessageContent(assistantMessage.id, assistantText)
                messages = chatDatabase.listMessages()
                status = response.exceptionOrNull()?.message
            } finally {
                isSending = false
            }
        }
    }

    SharedChatScreen(
        state = SharedChatUiState(
            apiKey = apiKey,
            model = model,
            input = input,
            messages = messages.map(AndroidChatMessage::toSharedUi),
            status = status,
            isSending = isSending,
        ),
        onEvent = { event ->
            when (event) {
                is SharedChatEvent.ApiKeyChanged -> apiKey = event.value
                is SharedChatEvent.ModelChanged -> model = event.value
                is SharedChatEvent.InputChanged -> input = event.value
                SharedChatEvent.SaveSettings -> saveSettings()
                SharedChatEvent.ClearConversation -> clearConversation()
                SharedChatEvent.SendMessage -> sendMessage()
            }
        },
    )
}

private fun AndroidChatMessage.toSharedUi(): SharedChatMessageUi =
    SharedChatMessageUi(
        id = id,
        role = role,
        content = content,
    )
