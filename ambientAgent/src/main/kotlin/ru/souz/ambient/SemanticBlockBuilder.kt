package ru.souz.ambient

import java.util.Locale

data class SemanticBlockBuilderConfig(
    val pauseToCloseMs: Long = 3_000L,
    val maxBlockDurationMs: Long = 3_000L,
    val batchFallbackMaxBlockDurationMs: Long = 10_000L,
    val maxBlockChars: Int = 1_800,
    val minUsefulChars: Int = 8,
)

class SemanticBlockBuilder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: SemanticBlockBuilderConfig = SemanticBlockBuilderConfig(),
) {
    private var current: MutableList<AmbientTranscriptEvent> = mutableListOf()
    private var currentLiveFullText: String? = null
    private var lastClosedLiveFullText: String? = null
    private var lastClosedLiveEndMs: Long? = null
    private var nextBlockOrdinal: Long = 0

    fun accept(event: AmbientTranscriptEvent): List<AmbientSemanticBlock> {
        val acceptedEvent = acceptedEvent(event) ?: return emptyList()
        val liveFullText = event.text.trim().takeIf { acceptedEvent.source == AmbientTranscriptSource.LIVE }

        if (
            acceptedEvent.source == AmbientTranscriptSource.LIVE &&
            current.size == 1 &&
            current.single().source == AmbientTranscriptSource.LIVE &&
            !current.single().isFinal
        ) {
            current[0] = acceptedEvent
            currentLiveFullText = liveFullText
            return emptyList()
        }

        val closed = mutableListOf<AmbientSemanticBlock>()
        val closeReason = closeReasonBeforeAdding(acceptedEvent)
        if (closeReason != null) {
            closeCurrent(closeReason)?.let { closed += it }
        }
        current += acceptedEvent
        currentLiveFullText = liveFullText
        return closed
    }

    fun flush(
        closeReason: AmbientBlockCloseReason = AmbientBlockCloseReason.MANUAL_FLUSH,
    ): AmbientSemanticBlock? = closeCurrent(closeReason)

    fun clear() {
        current = mutableListOf()
        currentLiveFullText = null
    }

    private fun closeReasonBeforeAdding(next: AmbientTranscriptEvent): AmbientBlockCloseReason? {
        if (current.isEmpty()) return null

        val previousEnd = current.last().eventEndMs()
        val nextStart = next.eventStartMs()
        if (nextStart - previousEnd > config.pauseToCloseMs) {
            return AmbientBlockCloseReason.PAUSE
        }

        val duration = maxOf(current.first().eventStartMs(), next.eventEndMs()) -
            minOf(current.first().eventStartMs(), next.eventEndMs())
        val maxBlockDurationMs = if (isBatchFallbackWindow(next)) {
            config.batchFallbackMaxBlockDurationMs
        } else {
            config.maxBlockDurationMs
        }
        if (duration > maxBlockDurationMs) {
            return AmbientBlockCloseReason.MAX_DURATION
        }

        val joinedLength = (current.joinToString(" ") { it.text.trim() } + " " + next.text.trim()).trim().length
        if (joinedLength > config.maxBlockChars) {
            return AmbientBlockCloseReason.MAX_CHARS
        }

        return null
    }

    private fun closeCurrent(closeReason: AmbientBlockCloseReason): AmbientSemanticBlock? {
        if (current.isEmpty()) return null
        val events = current.toList()
        current = mutableListOf()
        val liveFullText = currentLiveFullText
        currentLiveFullText = null

        val text = events.joinToString(" ") { it.text.trim() }.trim()
        val addressedness = classifyAddressedness(text)
        if (
            text.length < config.minUsefulChars &&
            addressedness != AmbientAddressedness.DIRECT_TO_SOUZ &&
            events.size == 1
        ) {
            return null
        }

        if (liveFullText != null && events.all { it.source == AmbientTranscriptSource.LIVE }) {
            lastClosedLiveFullText = liveFullText
            lastClosedLiveEndMs = events.maxOfOrNull { it.eventEndMs() }
        }
        nextBlockOrdinal += 1
        return AmbientSemanticBlock(
            id = "semantic-$nextBlockOrdinal",
            text = text,
            eventIds = events.map { it.id },
            startedAtMs = events.minOfOrNull { it.eventStartMs() },
            endedAtMs = events.maxOfOrNull { it.eventEndMs() },
            closedAtMs = clock(),
            closeReason = closeReason,
            speakerRole = classifySpeakerRole(addressedness),
            addressedness = addressedness,
        )
    }

    private fun classifyAddressedness(text: String): AmbientAddressedness {
        val normalized = text.lowercase(Locale.ROOT)
        return when {
            BACKGROUND_MARKERS.any { normalized.contains(it) } -> AmbientAddressedness.BACKGROUND_OR_QUOTED
            DIRECT_MARKERS.any { marker -> normalized.contains(marker) } -> AmbientAddressedness.DIRECT_TO_SOUZ
            IMPLICIT_INTENT_MARKERS.any { normalized.contains(it) } -> AmbientAddressedness.IMPLICIT_USER_INTENT
            CONVERSATION_MARKERS.any { normalized.contains(it) } -> AmbientAddressedness.AMBIENT_CONVERSATION
            else -> AmbientAddressedness.UNKNOWN
        }
    }

    private fun classifySpeakerRole(addressedness: AmbientAddressedness): AmbientSpeakerRole = when (addressedness) {
        AmbientAddressedness.DIRECT_TO_SOUZ,
        AmbientAddressedness.IMPLICIT_USER_INTENT -> AmbientSpeakerRole.PROBABLY_USER

        AmbientAddressedness.BACKGROUND_OR_QUOTED -> AmbientSpeakerRole.BACKGROUND_MEDIA
        AmbientAddressedness.AMBIENT_CONVERSATION,
        AmbientAddressedness.UNKNOWN -> AmbientSpeakerRole.UNKNOWN
    }

    private fun AmbientTranscriptEvent.eventStartMs(): Long = startedAtMs ?: receivedAtMs
    private fun AmbientTranscriptEvent.eventEndMs(): Long = endedAtMs ?: receivedAtMs
    private fun isBatchFallbackWindow(next: AmbientTranscriptEvent): Boolean =
        next.source == AmbientTranscriptSource.BATCH_FALLBACK ||
            current.any { it.source == AmbientTranscriptSource.BATCH_FALLBACK }

    private fun acceptedEvent(event: AmbientTranscriptEvent): AmbientTranscriptEvent? {
        if (event.text.isBlank()) return null
        if (event.isFinal) {
            return if (event.source == AmbientTranscriptSource.LIVE) liveDeltaEvent(event) else event
        }
        if (event.source != AmbientTranscriptSource.LIVE) return null
        return liveDeltaEvent(event)
    }

    private fun liveDeltaEvent(event: AmbientTranscriptEvent): AmbientTranscriptEvent? {
        val fullText = event.text.trim()
        val (deltaText, trimmedPrevious) = liveDeltaText(
            previous = lastClosedLiveFullText,
            current = fullText,
        )
        if (deltaText.isBlank()) return null

        val adjustedStartMs = if (trimmedPrevious) {
            lastClosedLiveEndMs ?: event.startedAtMs
        } else {
            event.startedAtMs
        }
        return event.copy(
            text = deltaText,
            startedAtMs = adjustedStartMs,
        )
    }

    private fun liveDeltaText(previous: String?, current: String): Pair<String, Boolean> {
        val previousText = previous?.trim().orEmpty()
        if (previousText.isBlank()) return current to false
        if (current == previousText) return "" to true
        if (current.startsWith(previousText)) {
            return current.removePrefix(previousText).trimStart() to true
        }

        val previousTokens = previousText.split(WHITESPACE_REGEX).filter(String::isNotBlank)
        val currentTokens = current.split(WHITESPACE_REGEX).filter(String::isNotBlank)
        val maxOverlap = minOf(previousTokens.size, currentTokens.size)
        for (overlap in maxOverlap downTo 1) {
            if (previousTokens.takeLast(overlap) == currentTokens.take(overlap)) {
                return currentTokens.drop(overlap).joinToString(" ") to true
            }
        }
        return current to false
    }

    private companion object {
        val WHITESPACE_REGEX = Regex("\\s+")
        val DIRECT_MARKERS = listOf("souz", "souз", "союз", "ассистент")
        val IMPLICIT_INTENT_MARKERS = listOf(
            "напомни мне",
            "создай",
            "найди",
            "поищи",
            "проверь",
            "узнай",
            "посмотри",
            "посмотрел",
            "хочу",
            "хотел бы",
            "хотела бы",
            "какая погода",
            "какой погода",
            "погода в",
            "отправь",
            "запланируй",
            "надо",
            "нужно",
            "я забыл",
            "не забыть",
            "потом надо",
        )
        val BACKGROUND_MARKERS = listOf(
            "в видео сказали",
            "он сказал",
            "она сказала",
            "в фильме",
            "на подкасте",
        )
        val CONVERSATION_MARKERS = listOf("как думаешь", "что скажешь", "обсудим")
    }
}
