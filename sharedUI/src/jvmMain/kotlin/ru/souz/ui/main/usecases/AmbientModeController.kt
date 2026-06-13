package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import ru.souz.ambient.AmbientAddressedness
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientModeState
import ru.souz.ambient.AmbientSemanticBlockService
import ru.souz.ambient.AmbientSpeechAvailability
import ru.souz.ambient.AmbientSuggestion
import ru.souz.ambient.AmbientSuggestionStore
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.AmbientTranscriptionState

class AmbientModeController(
    private val appScope: CoroutineScope,
    private val transcription: AmbientTranscriptionService,
    private val semanticBlocks: AmbientSemanticBlockService,
    private val analyzer: AmbientBlockAnalyzer,
    private val suggestionStore: AmbientSuggestionStore,
    private val actionHandler: AmbientSuggestionActionHandler,
    private val localeProvider: () -> String,
    private val onStartRequested: () -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(AmbientModeController::class.java)
    private val mutex = Mutex()
    private val _modeState = MutableStateFlow(AmbientModeState())
    private var analysisJob: Job? = null
    private var expiryJob: Job? = null
    private var transcriptionStateJob: Job? = null

    val modeState: StateFlow<AmbientModeState> = _modeState.asStateFlow()
    val suggestions: StateFlow<List<AmbientSuggestion>> = suggestionStore.pending

    val isMicrophoneBusyForVoiceInput: Boolean
        get() = modeState.value.enabled || modeState.value.starting || modeState.value.listening

    suspend fun start() {
        mutex.withLock {
            if (_modeState.value.enabled || _modeState.value.starting) return
            suggestionStore.clear()
            _modeState.value = AmbientModeState(enabled = true, starting = true, analyzing = true)
            onStartRequested()
            startJobsLocked()
        }

        val locale = localeProvider()
        logger.info("Ambient mode start requested: locale={}", locale)
        when (val availability = transcription.start(locale)) {
            AmbientSpeechAvailability.Available,
            AmbientSpeechAvailability.AlreadyRunning -> {
                val listening = transcription.state.value is AmbientTranscriptionState.Listening ||
                    availability == AmbientSpeechAvailability.AlreadyRunning
                _modeState.value = AmbientModeState(
                    enabled = true,
                    starting = false,
                    listening = listening,
                    analyzing = true,
                )
            }

            AmbientSpeechAvailability.LiveBackendUnavailable ->
                failStart("Ambient STT недоступен")

            AmbientSpeechAvailability.MicrophoneUnavailable ->
                failStart("Микрофон недоступен для ambient mode")

            AmbientSpeechAvailability.SpeechRecognitionPermissionDenied ->
                failStart("Нет разрешения macOS Speech Recognition")
        }
    }

    suspend fun stop(clearSuggestions: Boolean = true) {
        val jobs = mutex.withLock {
            val jobs = listOfNotNull(analysisJob, expiryJob, transcriptionStateJob)
            analysisJob = null
            expiryJob = null
            transcriptionStateJob = null
            jobs
        }
        jobs.forEach { it.cancelAndJoin() }
        runCatching { transcription.stop() }
        runCatching { semanticBlocks.stop() }
        runCatching { transcription.clearTranscript() }
        semanticBlocks.clear()
        if (clearSuggestions) {
            suggestionStore.clear()
        }
        _modeState.value = AmbientModeState()
    }

    fun stopAsync(clearSuggestions: Boolean = true) {
        appScope.launch {
            stop(clearSuggestions)
        }
    }

    suspend fun toggle() {
        if (isMicrophoneBusyForVoiceInput) {
            stop(clearSuggestions = true)
        } else {
            start()
        }
    }

    suspend fun acceptSuggestion(id: String): Boolean =
        actionHandler.accept(id)

    fun rejectSuggestion(id: String) {
        actionHandler.reject(id)
    }

    fun dismissSuggestion(id: String) {
        actionHandler.dismiss(id)
    }

    fun expireOld() {
        suggestionStore.expireOld()
    }

    private fun startJobsLocked() {
        analysisJob?.cancel()
        expiryJob?.cancel()
        transcriptionStateJob?.cancel()

        analysisJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            semanticBlocks.blocks
                .buffer(capacity = MAX_PENDING_BLOCKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .collect { block ->
                    val candidate = try {
                        analyzer.analyze(block)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        logger.warn(
                            "Ambient block analysis failed: blockId={} error={}",
                            block.id,
                            error.message ?: error::class.simpleName,
                        )
                        null
                    }
                    if (candidate != null && candidate.shouldOffer()) {
                        suggestionStore.add(candidate)
                    }
                }
        }
        expiryJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            suggestionStore.pending.collectLatest { pending ->
                val nextExpiry = pending.minOfOrNull { it.expiresAtMs } ?: return@collectLatest
                delay((nextExpiry - System.currentTimeMillis()).coerceAtLeast(0L) + 25L)
                suggestionStore.expireOld()
            }
        }
        transcriptionStateJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            transcription.state.collect { state ->
                when (state) {
                    AmbientTranscriptionState.Stopped -> Unit
                    AmbientTranscriptionState.Starting ->
                        _modeState.value = AmbientModeState(enabled = true, starting = true, analyzing = true)

                    is AmbientTranscriptionState.Listening ->
                        _modeState.value = AmbientModeState(enabled = true, listening = true, analyzing = true)

                    is AmbientTranscriptionState.Error -> {
                        logger.warn("Ambient transcription error: {}", state.message)
                        _modeState.value = AmbientModeState(errorMessage = state.message)
                        stopAsync(clearSuggestions = true)
                    }
                }
            }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            semanticBlocks.start()
        }
    }

    private suspend fun failStart(message: String) {
        logger.warn("Ambient mode start failed: {}", message)
        stop(clearSuggestions = true)
        _modeState.value = AmbientModeState(errorMessage = message)
    }

    private fun AmbientTaskCandidate.shouldOffer(): Boolean =
        taskText.isNotBlank() &&
            addressedness != AmbientAddressedness.BACKGROUND_OR_QUOTED

    private companion object {
        const val MAX_PENDING_BLOCKS = 1
    }
}
