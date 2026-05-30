package ru.souz.backend.chat.service

import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.execution.model.AgentExecution

data class MessageListPage(
    val items: List<ChatMessage>,
    val nextBeforeSeq: Long?,
)

data class SendMessageResult(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage?,
    val execution: AgentExecution,
)
