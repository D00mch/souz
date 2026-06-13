package ru.souz.ambient

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
import org.slf4j.LoggerFactory
import ru.souz.service.speech.LiveSpeechTranscriptEvent
import ru.souz.service.speech.LiveSpeechTranscriptionProvider
import ru.souz.service.speech.LiveSpeechTranscriptionSession
import ru.souz.service.speech.LocalMacOsLiveSpeechModelUnavailableException
import ru.souz.service.speech.LocalMacOsLiveSpeechPermissionDeniedException
import ru.souz.service.speech.LocalMacOsLiveSpeechUnavailableException
import ru.souz.service.speech.LocalMacOsLiveSpeechUnsupportedException
import ru.souz.service.speech.LocalMacOsSpeechPermissionDeniedException
import ru.souz.service.speech.LocalMacOsSpeechUnavailableException
import ru.souz.service.speech.SpeechRecognitionProvider
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

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

class DefaultAmbientTranscriptionService(
    private val liveSpeechProvider: LiveSpeechTranscriptionProvider,
    private val batchSpeechRecognitionProvider: SpeechRecognitionProvider? = null,
    private val audioSource: PcmAudioFrameSource,
    private val scope: CoroutineScope,
    private val batchWindowMillis: Long = 3_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : AmbientTranscriptionService {
    private val logger = LoggerFactory.getLogger(DefaultAmbientTranscriptionService::class.java)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<AmbientTranscriptionState>(AmbientTranscriptionState.Stopped)
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

        if (!isExpectedPcmFormat()) {
            markStoppedFromStarting()
            return AmbientSpeechAvailability.MicrophoneUnavailable
        }

        val session = try {
            liveSpeechProvider.start(locale)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                markStoppedFromStarting()
                throw error
            }
            logger.warn(
                "Ambient live transcription start failed: locale={} error={}",
                locale,
                error.message ?: error::class.simpleName,
            )
            when (error) {
                is LocalMacOsLiveSpeechPermissionDeniedException -> {
                    markStoppedFromStarting()
                    return AmbientSpeechAvailability.SpeechRecognitionPermissionDenied
                }

                is LocalMacOsLiveSpeechUnsupportedException,
                is LocalMacOsLiveSpeechModelUnavailableException,
                is LocalMacOsLiveSpeechUnavailableException ->
                    return startBatchFallback(locale)

                else -> return startBatchFallback(locale)
            }
        }

        val microphoneStarted = try {
            audioSource.start()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        }
        if (!microphoneStarted) {
            runCatching { session.cancel() }
            markStoppedFromStarting()
            return AmbientSpeechAvailability.MicrophoneUnavailable
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

    private suspend fun startBatchFallback(locale: String): AmbientSpeechAvailability {
        val batchProvider = batchSpeechRecognitionProvider
        if (batchProvider == null || !batchProvider.enabled || !batchProvider.hasRequiredKey) {
            markStoppedFromStarting()
            return AmbientSpeechAvailability.LiveBackendUnavailable
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

        logger.info("Starting ambient batch speech recognition fallback: locale={}", locale)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runBatchFallbackLoop(batchProvider, locale)
        }
        mutex.withLock {
            activeSession = null
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

    override suspend fun clearTranscript() = Unit

    private suspend fun runListeningLoop(session: LiveSpeechTranscriptionSession) {
        val currentJob = currentCoroutineContext()[Job]
        var failure: Throwable? = null
        var finalized = false
        try {
            audioSource.frames().collect { chunk ->
                if (chunk.isEmpty()) return@collect
                session.acceptPcm(chunk)
                publish(session.pollEvents(), source = AmbientTranscriptSource.LIVE)
            }
            publish(session.finalizeAndFinish(), source = AmbientTranscriptSource.LIVE)
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

    private suspend fun runBatchFallbackLoop(
        batchProvider: SpeechRecognitionProvider,
        locale: String,
    ) {
        val currentJob = currentCoroutineContext()[Job]
        var failure: Throwable? = null
        val pendingAudio = ByteArrayOutputStream()
        var pendingAudioStartedAtMs: Long? = null
        var nextAudioStartedAtMs = clock()
        try {
            audioSource.frames().collect { chunk ->
                if (chunk.isEmpty()) return@collect
                if (pendingAudio.size() == 0) {
                    pendingAudioStartedAtMs = nextAudioStartedAtMs
                }
                pendingAudio.write(chunk)
                if (pendingAudio.size() >= batchWindowSizeBytes()) {
                    nextAudioStartedAtMs = recognizeAndPublishBatch(
                        batchProvider = batchProvider,
                        locale = locale,
                        pendingAudio = pendingAudio,
                        startedAtMs = pendingAudioStartedAtMs ?: nextAudioStartedAtMs,
                    )
                    pendingAudioStartedAtMs = null
                }
            }
            if (pendingAudio.size() > 0) {
                recognizeAndPublishBatch(
                    batchProvider = batchProvider,
                    locale = locale,
                    pendingAudio = pendingAudio,
                    startedAtMs = pendingAudioStartedAtMs ?: nextAudioStartedAtMs,
                )
            }
        } catch (error: CancellationException) {
            // Normal stop path. Cleanup happens in finally.
        } catch (error: Throwable) {
            failure = when (error) {
                is LocalMacOsSpeechPermissionDeniedException ->
                    LocalMacOsLiveSpeechPermissionDeniedException()

                else -> error
            }
        } finally {
            runCatching { audioSource.stop() }
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

    private suspend fun recognizeAndPublishBatch(
        batchProvider: SpeechRecognitionProvider,
        locale: String,
        pendingAudio: ByteArrayOutputStream,
        startedAtMs: Long,
    ): Long {
        if (pendingAudio.size() <= 0) return startedAtMs

        val audio = pendingAudio.toByteArray()
        pendingAudio.reset()
        val endedAtMs = startedAtMs + audioDurationMillis(audio.size)
        val recognized = try {
            batchProvider.recognize(audio).trim()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error.isNoSpeechDetected()) {
                logger.debug("Ambient batch speech fallback skipped no-speech window: locale={}", locale)
                return endedAtMs
            }
            throw error
        }
        if (recognized.isBlank()) return endedAtMs

        logger.debug(
            "Ambient batch speech fallback recognized: locale={} startedAtMs={} endedAtMs={} chars={}",
            locale,
            startedAtMs,
            endedAtMs,
            recognized.length,
        )
        publish(
            events = listOf(
                LiveSpeechTranscriptEvent(
                    text = recognized,
                    isFinal = true,
                    startedAtMs = startedAtMs,
                    endedAtMs = endedAtMs,
                )
            ),
            source = AmbientTranscriptSource.BATCH_FALLBACK,
        )
        return endedAtMs
    }

    private fun Throwable.isNoSpeechDetected(): Boolean =
        this is LocalMacOsSpeechUnavailableException &&
            message?.contains("No speech detected", ignoreCase = true) == true

    private suspend fun publish(events: List<LiveSpeechTranscriptEvent>, source: AmbientTranscriptSource) {
        if (events.isEmpty()) return
        val ambientEvents = mutex.withLock {
            events.map { event -> event.toAmbientEvent(source) }
        }
        for (event in ambientEvents) {
            logger.debug(
                "Ambient transcript event emitted: id={} source={} final={} chars={}",
                event.id,
                event.source,
                event.isFinal,
                event.text.length,
            )
            if (!_transcriptEvents.tryEmit(event)) {
                logger.warn(
                    "Ambient transcript event dropped: id={} source={} final={} chars={}",
                    event.id,
                    event.source,
                    event.isFinal,
                    event.text.length,
                )
            }
        }
    }

    private fun LiveSpeechTranscriptEvent.toAmbientEvent(source: AmbientTranscriptSource): AmbientTranscriptEvent {
        nextEventOrdinal += 1
        return AmbientTranscriptEvent(
            id = "ambient-$nextEventOrdinal",
            text = text,
            isFinal = isFinal,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            receivedAtMs = clock(),
            source = source,
        )
    }

    private suspend fun isRunning(): Boolean = mutex.withLock {
        listeningJob?.isActive == true || _state.value == AmbientTranscriptionState.Starting
    }

    private suspend fun isLiveBackendSupported(locale: String): Boolean = try {
        liveSpeechProvider.isSupported(locale) ||
            batchSpeechRecognitionProvider?.let { it.enabled && it.hasRequiredKey } == true
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        batchSpeechRecognitionProvider?.let { it.enabled && it.hasRequiredKey } == true
    }

    private fun isExpectedPcmFormat(): Boolean =
        audioSource.sampleRateHz == EXPECTED_SAMPLE_RATE_HZ &&
            audioSource.channels == EXPECTED_CHANNELS &&
            audioSource.bitsPerSample == EXPECTED_BITS_PER_SAMPLE

    private fun batchWindowSizeBytes(): Int {
        val bytesPerSample = audioSource.bitsPerSample / 8
        val bytesPerSecond = audioSource.sampleRateHz * audioSource.channels * bytesPerSample
        val rawSize = bytesPerSecond * batchWindowMillis / 1_000
        val blockAlign = audioSource.channels * bytesPerSample
        return (rawSize - (rawSize % blockAlign)).coerceAtLeast(blockAlign.toLong()).toInt()
    }

    private fun audioDurationMillis(byteSize: Int): Long {
        val bytesPerSample = audioSource.bitsPerSample / 8
        val bytesPerSecond = audioSource.sampleRateHz * audioSource.channels * bytesPerSample
        if (bytesPerSecond <= 0) return 0L
        return byteSize * 1_000L / bytesPerSecond
    }

    private suspend fun markStoppedFromStarting() {
        mutex.withLock {
            if (_state.value == AmbientTranscriptionState.Starting) {
                _state.value = AmbientTranscriptionState.Stopped
            }
        }
    }

    private companion object {
        const val EXPECTED_SAMPLE_RATE_HZ = 16_000
        const val EXPECTED_CHANNELS = 1
        const val EXPECTED_BITS_PER_SAMPLE = 16
    }
}
