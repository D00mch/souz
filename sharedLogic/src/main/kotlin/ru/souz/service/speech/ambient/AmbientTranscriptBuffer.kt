package ru.souz.service.speech.ambient

data class AmbientTranscriptEvent(
    val id: String,
    val text: String,
    val isFinal: Boolean,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val receivedAtMs: Long,
)

data class AmbientTranscriptSnapshot(
    val finalEvents: List<AmbientTranscriptEvent>,
    val currentVolatile: AmbientTranscriptEvent?,
)

class AmbientTranscriptBuffer(
    private val maxFinalEvents: Int = DEFAULT_MAX_FINAL_EVENTS,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val finalEvents = ArrayDeque<AmbientTranscriptEvent>()
    private var currentVolatile: AmbientTranscriptEvent? = null

    fun append(event: AmbientTranscriptEvent) {
        prune(nowMs = clock())
        if (event.isFinal) {
            currentVolatile = null
            finalEvents.addLast(event)
            trimToMaxFinalEvents()
            prune(nowMs = clock())
        } else {
            currentVolatile = event
        }
    }

    fun prune(nowMs: Long = clock()) {
        val cutoffMs = nowMs - ttlMs
        while (finalEvents.isNotEmpty() && finalEvents.first().receivedAtMs < cutoffMs) {
            finalEvents.removeFirst()
        }
    }

    fun clear() {
        finalEvents.clear()
        currentVolatile = null
    }

    fun snapshot(): AmbientTranscriptSnapshot = AmbientTranscriptSnapshot(
        finalEvents = finalEvents.toList(),
        currentVolatile = currentVolatile,
    )

    private fun trimToMaxFinalEvents() {
        while (finalEvents.size > maxFinalEvents) {
            finalEvents.removeFirst()
        }
    }

    private companion object {
        const val DEFAULT_MAX_FINAL_EVENTS = 300
        const val DEFAULT_TTL_MS = 10 * 60 * 1_000L
    }
}
