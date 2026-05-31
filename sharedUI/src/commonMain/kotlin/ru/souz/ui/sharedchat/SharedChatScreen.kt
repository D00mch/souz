package ru.souz.ui.sharedchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.souz.ui.sharedsettings.SharedSettingsEvent
import ru.souz.ui.sharedsettings.SharedSettingsPanel
import ru.souz.ui.sharedsettings.SharedSettingsUiState

data class SharedChatUiState(
    val input: String,
    val messages: List<SharedChatMessageUi>,
    val status: String? = null,
    val isSending: Boolean = false,
    val isOnline: Boolean = true,
    val title: String = "Chat",
    val emptyText: String = "Send a message to start.",
    val inputPlaceholder: String = "Message",
    val sendLabel: String = "Send",
    val sendingLabel: String = "Sending",
    val stopLabel: String = "Stop",
    val clearLabel: String = "Clear",
    val thinkingLabel: String = "Thinking",
    val offlineLabel: String = "Offline",
    val canClear: Boolean = messages.isNotEmpty() && !isSending,
    val inputEnabled: Boolean = !isSending,
    val canAttach: Boolean = false,
    val canUseVoice: Boolean = false,
    val isListening: Boolean = false,
    val attachments: List<SharedChatAttachmentUi> = emptyList(),
    val settings: SharedSettingsUiState? = null,
)

data class SharedChatMessageUi(
    val id: String,
    val role: SharedChatRole,
    val content: String,
    val timestampText: String? = null,
    val actionLines: List<String> = emptyList(),
    val attachments: List<SharedChatAttachmentUi> = emptyList(),
)

enum class SharedChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class SharedChatAttachmentUi(
    val id: String,
    val name: String,
    val detail: String? = null,
    val kind: SharedChatAttachmentKind = SharedChatAttachmentKind.OTHER,
)

enum class SharedChatAttachmentKind {
    DOCUMENT,
    IMAGE,
    PDF,
    SPREADSHEET,
    VIDEO,
    AUDIO,
    ARCHIVE,
    OTHER,
}

sealed interface SharedChatEvent {
    data class InputChanged(val value: String) : SharedChatEvent
    data object SendMessage : SharedChatEvent
    data object CancelProcessing : SharedChatEvent
    data object ClearConversation : SharedChatEvent
    data object PickAttachments : SharedChatEvent
    data class RemoveAttachment(val id: String) : SharedChatEvent
    data object StartListening : SharedChatEvent
    data object StopListening : SharedChatEvent
    data class Settings(val event: SharedSettingsEvent) : SharedChatEvent
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
                val isWide = maxWidth >= 840.dp
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        state.settings?.let { settings ->
                            SharedSettingsPanel(
                                state = settings,
                                onEvent = { onEvent(SharedChatEvent.Settings(it)) },
                                modifier = Modifier
                                    .width(360.dp)
                                    .fillMaxSize(),
                            )
                        }
                        SharedChatSurface(
                            state = state,
                            onEvent = onEvent,
                            listState = listState,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        state.settings?.let { settings ->
                            SharedSettingsPanel(
                                state = settings,
                                onEvent = { onEvent(SharedChatEvent.Settings(it)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        SharedChatSurface(
                            state = state,
                            onEvent = onEvent,
                            listState = listState,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(state.messages.size, state.isSending) {
        if (state.messages.isNotEmpty() || state.isSending) {
            val targetIndex = if (state.isSending) state.messages.size else state.messages.lastIndex
            listState.animateScrollToItem(targetIndex)
        }
    }
}
