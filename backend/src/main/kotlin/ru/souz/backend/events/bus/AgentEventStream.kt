package ru.souz.backend.events.bus

import kotlinx.coroutines.channels.ReceiveChannel
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventEnvelope

data class AgentEventStream(
    val replay: List<AgentEvent>,
    val liveEvents: ReceiveChannel<AgentEventEnvelope>,
    val close: suspend () -> Unit,
)
