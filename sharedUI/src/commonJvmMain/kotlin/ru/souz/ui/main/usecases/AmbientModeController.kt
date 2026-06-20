package ru.souz.ui.main.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import ru.souz.ambient.AmbientAddressedness
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientBlockCloseReason
import ru.souz.ambient.AmbientModeState
import ru.souz.ambient.AmbientSemanticBlock
import ru.souz.ambient.AmbientSpeechAvailability
import ru.souz.ambient.AmbientSuggestion
import ru.souz.ambient.AmbientSuggestionConfig
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.AmbientTranscriptEvent
import ru.souz.ambient.AmbientTranscriptSource
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.AmbientTranscriptionState
import ru.souz.ambient.SemanticBlockBuilder
import java.util.Locale

class AmbientModeController(
    private val scope: CoroutineScope,
    private val transcription: AmbientTranscriptionService,
    private val blockBuilder: SemanticBlockBuilder = SemanticBlockBuilder(),
    private val analyzer: AmbientBlockAnalyzer,
    private val executeCandidate: suspend (AmbientTaskCandidate) -> Unit,
    private val localeProvider: () -> String,
    private val failureMessageProvider: suspend (AmbientSpeechAvailability) -> String,
    private val suggestionConfig: AmbientSuggestionConfig = AmbientSuggestionConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStartRequested: () -> Unit = {},
    private val inactivityFlushMs: Long = DEFAULT_INACTIVITY_FLUSH_MS,
    private val batchFallbackInactivityFlushMs: Long = DEFAULT_BATCH_FALLBACK_INACTIVITY_FLUSH_MS,
    private val postAcceptSuppressionMs: Long = DEFAULT_POST_ACCEPT_SUPPRESSION_MS,
) {
    private val logger = LoggerFactory.getLogger(AmbientModeController::class.java)
    private val mutex = Mutex()
    private val _modeState = MutableStateFlow(AmbientModeState())
    private val _suggestions = MutableStateFlow<List<AmbientSuggestion>>(emptyList())
    private val recentFingerprints = ArrayDeque<RecentFingerprint>()
    private var analysisJob: Job? = null
    private var expiryJob: Job? = null
    private var transcriptionStateJob: Job? = null
    private var inactivityJob: Job? = null
    private var runGeneration: Long = 0
    private var acceptedTaskExecutions: Int = 0
    private var postAcceptSuppressUntilMs: Long = 0L

    val modeState: StateFlow<AmbientModeState> = _modeState.asStateFlow()
    val suggestions: StateFlow<List<AmbientSuggestion>> = _suggestions.asStateFlow()

    val isMicrophoneBusyForVoiceInput: Boolean
        get() = modeState.value.enabled || modeState.value.starting || modeState.value.listening

    suspend fun start() {
        val currentRunGeneration = mutex.withLock {
            if (_modeState.value.enabled || _modeState.value.starting) {
                null
            } else {
                runGeneration += 1
                clearSuggestionsLocked()
                blockBuilder.clear()
                _modeState.value = AmbientModeState(enabled = true, starting = true, analyzing = true)
                onStartRequested()
                startJobsLocked(runGeneration)
                runGeneration
            }
        } ?: return

        val locale = localeProvider()
        logger.info("Ambient mode start requested: locale={}", locale)
        when (val availability = transcription.start(locale)) {
            AmbientSpeechAvailability.Available,
            AmbientSpeechAvailability.AlreadyRunning -> {
                val listening = transcription.state.value is AmbientTranscriptionState.Listening ||
                    availability == AmbientSpeechAvailability.AlreadyRunning
                mutex.withLock {
                    if (isCurrentRunLocked(currentRunGeneration)) {
                        _modeState.value = AmbientModeState(
                            enabled = true,
                            starting = false,
                            listening = listening,
                            analyzing = true,
                        )
                    }
                }
            }

            AmbientSpeechAvailability.LiveBackendUnavailable,
            AmbientSpeechAvailability.MicrophoneUnavailable,
            AmbientSpeechAvailability.SpeechRecognitionPermissionDenied ->
                failStart(currentRunGeneration, failureMessageProvider(availability))
        }
    }

    suspend fun stop(clearSuggestions: Boolean = true) {
        val currentJob = currentCoroutineContext()[Job]
        val jobs = mutex.withLock {
            runGeneration += 1
            acceptedTaskExecutions = 0
            postAcceptSuppressUntilMs = 0L
            val jobs = listOfNotNull(analysisJob, expiryJob, transcriptionStateJob, inactivityJob)
                .filter { it != currentJob }
            analysisJob = null
            expiryJob = null
            transcriptionStateJob = null
            inactivityJob = null
            blockBuilder.clear()
            if (clearSuggestions) {
                clearSuggestionsLocked()
            }
            jobs
        }
        jobs.forEach { it.cancelAndJoin() }
        runCatching { transcription.stop() }
        runCatching { transcription.clearTranscript() }
        _modeState.value = AmbientModeState()
    }

    fun stopAsync(
        cleanupScope: CoroutineScope = scope,
        clearSuggestions: Boolean = true,
    ) {
        cleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                stop(clearSuggestions)
            }
        }
    }

    suspend fun toggle() {
        if (isMicrophoneBusyForVoiceInput) {
            stop(clearSuggestions = true)
        } else {
            start()
        }
    }

    suspend fun acceptSuggestion(id: String): Boolean {
        val suggestion = mutex.withLock {
            consumeSuggestionLocked(id)?.also {
                acceptedTaskExecutions += 1
                clearPendingSuggestionsLocked()
                blockBuilder.discardOpenAsLiveBaseline()
            }
        } ?: return false
        return try {
            executeCandidate(suggestion.candidate)
            true
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            false
        } finally {
            mutex.withLock {
                acceptedTaskExecutions = (acceptedTaskExecutions - 1).coerceAtLeast(0)
                postAcceptSuppressUntilMs = maxOf(postAcceptSuppressUntilMs, clock() + postAcceptSuppressionMs)
                blockBuilder.discardOpenAsLiveBaseline()
            }
        }
    }

    suspend fun rejectSuggestion(id: String) {
        removeSuggestion(id)
    }

    suspend fun dismissSuggestion(id: String) {
        removeSuggestion(id)
    }

    suspend fun expireOld() {
        mutex.withLock {
            expireOldLocked(clock())
        }
    }

    private fun startJobsLocked(currentRunGeneration: Long) {
        analysisJob?.cancel()
        expiryJob?.cancel()
        transcriptionStateJob?.cancel()
        inactivityJob?.cancel()

        analysisJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transcription.transcriptEvents
                .buffer(capacity = MAX_PENDING_TRANSCRIPT_EVENTS, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                .collect { event -> processTranscriptEvent(currentRunGeneration, event) }
        }
        expiryJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            suggestions.collectLatest { pending ->
                val nextExpiry = pending.minOfOrNull { it.expiresAtMs } ?: return@collectLatest
                delay((nextExpiry - clock()).coerceAtLeast(0L) + EXPIRY_GRACE_MS)
                expireOld()
            }
        }
        transcriptionStateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transcription.state.collect { state ->
                when (state) {
                    AmbientTranscriptionState.Stopped -> Unit
                    AmbientTranscriptionState.Starting ->
                        updateModeIfCurrent(
                            currentRunGeneration,
                            AmbientModeState(enabled = true, starting = true, analyzing = true),
                        )

                    is AmbientTranscriptionState.Listening ->
                        updateModeIfCurrent(
                            currentRunGeneration,
                            AmbientModeState(enabled = true, listening = true, analyzing = true),
                        )

                    is AmbientTranscriptionState.Error -> {
                        logger.warn("Ambient transcription error: {}", state.message)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            stop(clearSuggestions = true)
                            _modeState.value = AmbientModeState(errorMessage = state.message)
                        }
                    }
                }
            }
        }
    }

    private suspend fun processTranscriptEvent(
        currentRunGeneration: Long,
        event: AmbientTranscriptEvent,
    ) {
        val closedBlocks = mutex.withLock {
            if (!isCurrentRunLocked(currentRunGeneration)) {
                null
            } else {
                blockBuilder.accept(event).also {
                    if (isSuggestionsSuppressedLocked(clock())) {
                        blockBuilder.discardOpenAsLiveBaseline()
                        return@withLock null
                    }
                    if (event.text.isNotBlank() && (event.isFinal || event.source == AmbientTranscriptSource.LIVE)) {
                        scheduleInactivityFlushLocked(currentRunGeneration, event.source)
                    }
                }
            }
        } ?: return

        closedBlocks.forEach { block -> analyzeAndOffer(block) }
    }

    private fun scheduleInactivityFlushLocked(
        currentRunGeneration: Long,
        source: AmbientTranscriptSource,
    ) {
        inactivityJob?.cancel()
        val flushDelayMs = when (source) {
            AmbientTranscriptSource.LIVE -> inactivityFlushMs
            AmbientTranscriptSource.BATCH_FALLBACK -> batchFallbackInactivityFlushMs
        }
        if (flushDelayMs <= 0L) return

        inactivityJob = scope.launch {
            delay(flushDelayMs)
            val inactiveBlock = mutex.withLock {
                if (!isCurrentRunLocked(currentRunGeneration)) {
                    null
                } else {
                    inactivityJob = null
                    blockBuilder.flush(AmbientBlockCloseReason.PAUSE)
                }
            }
            inactiveBlock?.let { analyzeAndOffer(it) }
        }
    }

    private suspend fun analyzeAndOffer(block: AmbientSemanticBlock) {
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
            addSuggestion(candidate)
        }
    }

    private suspend fun addSuggestion(candidate: AmbientTaskCandidate) {
        mutex.withLock {
            val now = clock()
            if (isSuggestionsSuppressedLocked(now)) return@withLock
            val fingerprint = candidate.taskText.normalizedForDedupe()
            if (fingerprint.isBlank()) return@withLock
            pruneFingerprintsLocked(now)
            if (recentFingerprints.any { it.value == fingerprint }) return@withLock

            val suggestion = AmbientSuggestion(
                id = candidate.id,
                candidate = candidate.copy(taskText = candidate.taskText.trim()),
                createdAtMs = now,
                expiresAtMs = now + suggestionConfig.ttlMs,
            )
            recentFingerprints.addLast(RecentFingerprint(fingerprint, now))
            _suggestions.value = (_suggestions.value.removeExpired(now) + suggestion)
                .takeLast(suggestionConfig.maxPendingSuggestions)
        }
    }

    private suspend fun consumeSuggestion(id: String): AmbientSuggestion? = mutex.withLock {
        consumeSuggestionLocked(id)
    }

    private fun consumeSuggestionLocked(id: String): AmbientSuggestion? {
        val now = clock()
        var consumed: AmbientSuggestion? = null
        _suggestions.value = _suggestions.value.removeExpired(now).filterNot { suggestion ->
            val match = suggestion.id == id
            if (match) consumed = suggestion
            match
        }
        return consumed
    }

    private suspend fun removeSuggestion(id: String) {
        mutex.withLock {
            _suggestions.value = _suggestions.value.filterNot { it.id == id }
        }
    }

    private suspend fun updateModeIfCurrent(
        currentRunGeneration: Long,
        state: AmbientModeState,
    ) {
        mutex.withLock {
            if (isCurrentRunLocked(currentRunGeneration)) {
                _modeState.value = state
            }
        }
    }

    private suspend fun failStart(
        currentRunGeneration: Long,
        message: String,
    ) {
        val shouldFail = mutex.withLock { isCurrentRunLocked(currentRunGeneration) }
        if (!shouldFail) return
        logger.warn("Ambient mode start failed: {}", message)
        stop(clearSuggestions = true)
        _modeState.value = AmbientModeState(errorMessage = message)
    }

    private fun clearSuggestionsLocked() {
        clearPendingSuggestionsLocked()
        recentFingerprints.clear()
    }

    private fun clearPendingSuggestionsLocked() {
        _suggestions.value = emptyList()
    }

    private fun expireOldLocked(now: Long) {
        pruneFingerprintsLocked(now)
        _suggestions.value = _suggestions.value.removeExpired(now)
    }

    private fun isSuggestionsSuppressedLocked(now: Long): Boolean =
        acceptedTaskExecutions > 0 || now < postAcceptSuppressUntilMs

    private fun pruneFingerprintsLocked(now: Long) {
        val cutoff = now - suggestionConfig.dedupeCooldownMs
        while (recentFingerprints.isNotEmpty() && recentFingerprints.first().createdAtMs <= cutoff) {
            recentFingerprints.removeFirst()
        }
        while (recentFingerprints.size > MAX_RECENT_FINGERPRINTS) {
            recentFingerprints.removeFirst()
        }
    }

    private fun isCurrentRunLocked(currentRunGeneration: Long): Boolean =
        runGeneration == currentRunGeneration && _modeState.value.enabled

    private fun List<AmbientSuggestion>.removeExpired(now: Long): List<AmbientSuggestion> =
        filter { it.expiresAtMs > now }

    private fun AmbientTaskCandidate.shouldOffer(): Boolean =
        taskText.isNotBlank() &&
            addressedness != AmbientAddressedness.BACKGROUND_OR_QUOTED

    private fun String.normalizedForDedupe(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class RecentFingerprint(
        val value: String,
        val createdAtMs: Long,
    )

    private companion object {
        const val MAX_PENDING_TRANSCRIPT_EVENTS = 8
        const val MAX_RECENT_FINGERPRINTS = 32
        const val EXPIRY_GRACE_MS = 25L
        const val DEFAULT_INACTIVITY_FLUSH_MS = 1_000L
        const val DEFAULT_BATCH_FALLBACK_INACTIVITY_FLUSH_MS = 3_500L
        const val DEFAULT_POST_ACCEPT_SUPPRESSION_MS = 5_000L
    }
}
