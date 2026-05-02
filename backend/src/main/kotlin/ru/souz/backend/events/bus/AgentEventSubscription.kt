package ru.souz.backend.events.bus

import kotlinx.coroutines.channels.ReceiveChannel
import ru.souz.backend.events.model.AgentEvent

data class AgentEventSubscription(
    val events: ReceiveChannel<AgentEvent>,
    val close: suspend () -> Unit,
)
