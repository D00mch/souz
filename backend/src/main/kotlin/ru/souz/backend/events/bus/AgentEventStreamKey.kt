package ru.souz.backend.events.bus

import java.util.UUID

internal data class AgentEventStreamKey(
    val userId: String,
    val chatId: UUID,
)
