package ru.souz.backend.events.bus

import kotlinx.coroutines.channels.ReceiveChannel
import ru.souz.backend.events.model.AgentEventEnvelope

data class AgentEventSubscription(
    val events: ReceiveChannel<AgentEventEnvelope>,
    val close: suspend () -> Unit,
)
