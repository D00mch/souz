package ru.souz.service.speech.ambient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.service.speech.LiveSpeechTranscriptEvent
import ru.souz.service.speech.LiveSpeechTranscriptionProvider
import ru.souz.service.speech.LiveSpeechTranscriptionSession
import kotlin.coroutines.cancellation.CancellationException

sealed interface AmbientSpeechAvailability {
    data object Available : AmbientSpeechAvailability
    data object LiveBackendUnavailable : AmbientSpeechAvailability
    data object MicrophoneUnavailable : AmbientSpeechAvailability
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
    fun snapshot(): AmbientTranscriptSnapshot
}

class DefaultAmbientTranscriptionService(
    private val liveSpeechProvider: LiveSpeechTranscriptionProvider,
    private val audioSource: PcmAudioFrameSource,
    private val scope: CoroutineScope,
    private val buffer: AmbientTranscriptBuffer = AmbientTranscriptBuffer(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AmbientTranscriptionService {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AmbientTranscriptionState>(AmbientTranscriptionState.Stopped)
    private val _snapshot = MutableStateFlow(buffer.snapshot())
    private val _transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>(extraBufferCapacity = 64)

    private var listeningJob: Job? = null
    private var activeSession: LiveSpeechTranscriptionSession? = null
    private var nextEventOrdinal: Long = 0

    override val state: StateFlow<AmbientTranscriptionState> = _state.asStateFlow()
    override val transcriptEvents: Flow<AmbientTranscriptEvent> = _transcriptEvents.asSharedFlow()

    override suspend fun availability(locale: String): AmbientSpeechAvailability {
        if (isRunning()) return AmbientSpeechAvailability.AlreadyRunning
        return if (isLiveBackendSupported(locale)) {
            AmbientSpeechAvailability.Available
        } else {
            AmbientSpeechAvailability.LiveBackendUnavailable
        }
    }

    override suspend fun start(locale: String): AmbientSpeechAvailability {
        mutex.withLock {
            if (listeningJob?.isActive == true || _state.value == AmbientTranscriptionState.Starting) {
                return AmbientSpeechAvailability.AlreadyRunning
            }
            _state.value = AmbientTranscriptionState.Starting
        }

        if (!isLiveBackendSupported(locale)) {
            markStoppedFromStarting()
            return AmbientSpeechAvailability.LiveBackendUnavailable
        }

        if (!isExpectedPcmFormat()) {
            markStoppedFromStarting()
            return AmbientSpeechAvailability.MicrophoneUnavailable
        }

        val microphoneStarted = try {
            audioSource.start()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        }
        if (!microphoneStarted) {
            markStoppedFromStarting()
            return AmbientSpeechAvailability.MicrophoneUnavailable
        }

        val session = try {
            liveSpeechProvider.start(locale)
        } catch (error: Throwable) {
            runCatching { audioSource.stop() }
            if (error is CancellationException) {
                markStoppedFromStarting()
                throw error
            }
            markError(error)
            return AmbientSpeechAvailability.LiveBackendUnavailable
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            runListeningLoop(session = session)
        }
        mutex.withLock {
            activeSession = session
            listeningJob = job
            _state.value = AmbientTranscriptionState.Listening(locale)
        }
        job.start()
        return AmbientSpeechAvailability.Available
    }

    override suspend fun stop() {
        val (job, session) = mutex.withLock { listeningJob to activeSession }
        if (job == null) {
            mutex.withLock {
                if (_state.value !is AmbientTranscriptionState.Error) {
                    _state.value = AmbientTranscriptionState.Stopped
                }
            }
            return
        }

        job.cancelAndJoin()
        val cleanupNotRun = mutex.withLock {
            if (listeningJob == job) {
                listeningJob = null
                activeSession = null
                _state.value = AmbientTranscriptionState.Stopped
                true
            } else {
                false
            }
        }
        if (cleanupNotRun) {
            runCatching { audioSource.stop() }
            runCatching { session?.cancel() }
        }
    }

    override suspend fun clearTranscript() {
        mutex.withLock {
            buffer.clear()
            _snapshot.value = buffer.snapshot()
        }
    }

    override fun snapshot(): AmbientTranscriptSnapshot = _snapshot.value

    private suspend fun runListeningLoop(session: LiveSpeechTranscriptionSession) {
        val currentJob = currentCoroutineContext()[Job]
        var failure: Throwable? = null
        var finalized = false
        try {
            audioSource.frames().collect { chunk ->
                if (chunk.isEmpty()) return@collect
                session.acceptPcm(chunk)
                publish(session.pollEvents())
            }
            publish(session.finalizeAndFinish())
            finalized = true
        } catch (error: CancellationException) {
            // Normal stop path. Cleanup happens in finally.
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { audioSource.stop() }
            if (!finalized) {
                runCatching { session.cancel() }
            }
            mutex.withLock {
                if (currentJob == null || listeningJob == currentJob) {
                    listeningJob = null
                    activeSession = null
                    _state.value = failure
                        ?.let { AmbientTranscriptionState.Error(it.message ?: "Ambient transcription failed.") }
                        ?: AmbientTranscriptionState.Stopped
                }
            }
        }
    }

    private suspend fun publish(events: List<LiveSpeechTranscriptEvent>) {
        if (events.isEmpty()) return
        val ambientEvents = mutex.withLock {
            events.map { event ->
                event.toAmbientEvent().also { ambientEvent ->
                    buffer.append(ambientEvent)
                    _snapshot.value = buffer.snapshot()
                }
            }
        }
        for (event in ambientEvents) {
            _transcriptEvents.tryEmit(event)
        }
    }

    private fun LiveSpeechTranscriptEvent.toAmbientEvent(): AmbientTranscriptEvent {
        nextEventOrdinal += 1
        return AmbientTranscriptEvent(
            id = "ambient-$nextEventOrdinal",
            text = text,
            isFinal = isFinal,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            receivedAtMs = clock(),
        )
    }

    private suspend fun isRunning(): Boolean = mutex.withLock {
        listeningJob?.isActive == true || _state.value == AmbientTranscriptionState.Starting
    }

    private suspend fun isLiveBackendSupported(locale: String): Boolean = try {
        liveSpeechProvider.isSupported(locale)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        false
    }

    private fun isExpectedPcmFormat(): Boolean =
        audioSource.sampleRateHz == EXPECTED_SAMPLE_RATE_HZ &&
            audioSource.channels == EXPECTED_CHANNELS &&
            audioSource.bitsPerSample == EXPECTED_BITS_PER_SAMPLE

    private suspend fun markStoppedFromStarting() {
        mutex.withLock {
            if (_state.value == AmbientTranscriptionState.Starting) {
                _state.value = AmbientTranscriptionState.Stopped
            }
        }
    }

    private suspend fun markError(error: Throwable) {
        mutex.withLock {
            _state.value = AmbientTranscriptionState.Error(error.message ?: "Ambient transcription failed.")
        }
    }

    private companion object {
        const val EXPECTED_SAMPLE_RATE_HZ = 16_000
        const val EXPECTED_CHANNELS = 1
        const val EXPECTED_BITS_PER_SAMPLE = 16
    }
}
