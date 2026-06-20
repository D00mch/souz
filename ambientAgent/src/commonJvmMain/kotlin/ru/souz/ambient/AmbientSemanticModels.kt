package ru.souz.ambient

data class AmbientSemanticBlock(
    val id: String,
    val text: String,
    val eventIds: List<String>,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val closedAtMs: Long,
    val closeReason: AmbientBlockCloseReason,
    val speakerRole: AmbientSpeakerRole,
    val addressedness: AmbientAddressedness,
)

enum class AmbientBlockCloseReason {
    PAUSE,
    MAX_DURATION,
    MAX_CHARS,
    MANUAL_FLUSH,
    STOPPED,
}

enum class AmbientSpeakerRole {
    PROBABLY_USER,
    UNKNOWN,
    PROBABLY_OTHER,
    BACKGROUND_MEDIA,
}

enum class AmbientAddressedness {
    DIRECT_TO_SOUZ,
    IMPLICIT_USER_INTENT,
    AMBIENT_CONVERSATION,
    BACKGROUND_OR_QUOTED,
    UNKNOWN,
}
