package ru.souz.backend.events.bus

import kotlinx.coroutines.channels.ReceiveChannel
import ru.souz.backend.events.model.AgentEvent

data class AgentEventStream(
    val replay: List<AgentEvent>,
    val liveEvents: ReceiveChannel<AgentEvent>,
    val close: suspend () -> Unit,
)
