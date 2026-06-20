package ru.souz.ambient

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class AmbientTranscriptEvent(
    val id: String,
    val text: String,
    val isFinal: Boolean,
    val startedAtMs: Long?,
    val endedAtMs: Long?,
    val receivedAtMs: Long,
    val source: AmbientTranscriptSource = AmbientTranscriptSource.LIVE,
)

enum class AmbientTranscriptSource {
    LIVE,
    BATCH_FALLBACK,
}

sealed interface AmbientSpeechAvailability {
    data object Available : AmbientSpeechAvailability
    data object LiveBackendUnavailable : AmbientSpeechAvailability
    data object MicrophoneUnavailable : AmbientSpeechAvailability
    data object SpeechRecognitionPermissionDenied : AmbientSpeechAvailability
    data object AlreadyRunning : AmbientSpeechAvailability
}

sealed interface AmbientTranscriptionState {
    data object Stopped : AmbientTranscriptionState
    data object Starting : AmbientTranscriptionState
    data class Listening(val locale: String) : AmbientTranscriptionState
    data class Error(val message: String) : AmbientTranscriptionState
}

interface PcmAudioFrameSource {
    val sampleRateHz: Int
    val channels: Int
    val bitsPerSample: Int

    fun frames(): Flow<ByteArray>
    suspend fun start(): Boolean
    suspend fun stop()
}

interface AmbientTranscriptionService {
    val state: StateFlow<AmbientTranscriptionState>
    val transcriptEvents: Flow<AmbientTranscriptEvent>

    suspend fun availability(locale: String): AmbientSpeechAvailability
    suspend fun start(locale: String): AmbientSpeechAvailability
    suspend fun stop()
    suspend fun clearTranscript()
}
