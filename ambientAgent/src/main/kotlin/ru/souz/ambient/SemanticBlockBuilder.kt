package ru.souz.ambient

import ru.souz.service.speech.ambient.AmbientTranscriptEvent
import java.util.Locale

data class SemanticBlockBuilderConfig(
    val pauseToCloseMs: Long = 1_500L,
    val maxBlockDurationMs: Long = 25_000L,
    val maxBlockChars: Int = 900,
    val minUsefulChars: Int = 8,
)

class SemanticBlockBuilder(
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: SemanticBlockBuilderConfig = SemanticBlockBuilderConfig(),
) {
    private var current: MutableList<AmbientTranscriptEvent> = mutableListOf()
    private var nextBlockOrdinal: Long = 0

    fun accept(event: AmbientTranscriptEvent): List<AmbientSemanticBlock> {
        if (!event.isFinal || event.text.isBlank()) return emptyList()

        val closed = mutableListOf<AmbientSemanticBlock>()
        val closeReason = closeReasonBeforeAdding(event)
        if (closeReason != null) {
            closeCurrent(closeReason)?.let { closed += it }
        }
        current += event
        return closed
    }

    fun flush(
        closeReason: AmbientBlockCloseReason = AmbientBlockCloseReason.MANUAL_FLUSH,
    ): AmbientSemanticBlock? = closeCurrent(closeReason)

    private fun closeReasonBeforeAdding(next: AmbientTranscriptEvent): AmbientBlockCloseReason? {
        if (current.isEmpty()) return null

        val previousEnd = current.last().eventEndMs()
        val nextStart = next.eventStartMs()
        if (nextStart - previousEnd > config.pauseToCloseMs) {
            return AmbientBlockCloseReason.PAUSE
        }

        val duration = maxOf(current.first().eventStartMs(), next.eventEndMs()) -
            minOf(current.first().eventStartMs(), next.eventEndMs())
        if (duration > config.maxBlockDurationMs) {
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

        val text = events.joinToString(" ") { it.text.trim() }.trim()
        val addressedness = classifyAddressedness(text)
        if (
            text.length < config.minUsefulChars &&
            addressedness != AmbientAddressedness.DIRECT_TO_SOUZ &&
            events.size == 1
        ) {
            return null
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

    private companion object {
        val DIRECT_MARKERS = listOf("souz", "souз", "союз", "ассистент")
        val IMPLICIT_INTENT_MARKERS = listOf(
            "напомни мне",
            "создай",
            "найди",
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
