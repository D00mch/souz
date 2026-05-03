package ru.souz.backend.events.bus

object AgentEventLimits {
    const val DEFAULT_REPLAY_LIMIT = 100
    const val MAX_REPLAY_LIMIT = 1_000
    const val LIVE_BUFFER_SIZE = 256
}
