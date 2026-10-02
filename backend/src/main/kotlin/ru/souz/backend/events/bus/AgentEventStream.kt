package ru.souz.backend.events.bus

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.select
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventEnvelope

data class AgentEventStream(
    val replay: List<AgentEvent>,
    val liveEvents: ReceiveChannel<AgentEventEnvelope>,
    val commands: ReceiveChannel<AgentEventEnvelope>,
    val close: suspend () -> Unit,
    val replayAfter: suspend (afterSeq: Long) -> List<AgentEvent>,
    val initialSeq: Long,
) {
    suspend fun receiveLive(): AgentEventEnvelope? = select {
        commands.onReceiveCatching { it.getOrNull() }
        liveEvents.onReceiveCatching { it.getOrNull() }
    }
}
