package ru.souz.ui.main.usecases

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import ru.souz.ambient.AmbientAddressedness
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientSuggestionConfig
import ru.souz.ambient.AmbientModeState
import ru.souz.ambient.AmbientSemanticBlock
import ru.souz.ambient.SemanticBlockBuilder
import ru.souz.ambient.SemanticBlockBuilderConfig
import ru.souz.ambient.AmbientSpeechAvailability
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.AmbientTranscriptEvent
import ru.souz.ambient.AmbientTranscriptSource
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.AmbientTranscriptionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientModeControllerTest {

    @Test
    fun `start builds semantic blocks from transcript events and analyzes pending suggestions`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        val analyzer = FakeAnalyzer { block -> candidate("c1", taskText = block.text) }
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = analyzer,
        )

        controller.start()
        runCurrent()
        transcriptEvents.emit(event(id = "e1", text = "Союз, проверь календарь"))
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("ru-RU"), transcription.startedLocales)
        assertEquals(listOf("Союз, проверь календарь"), analyzer.blocks.map { it.text })
        assertEquals(listOf("c1"), controller.suggestions.value.map { it.id })
        assertEquals(AmbientModeState(enabled = true, listening = true, analyzing = true), controller.modeState.value)
        controller.stop()
    }

    @Test
    fun `suggestions are deduped bounded accepted and rejected inside controller`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        val analyzer = QueueAnalyzer(
            candidate("c1", taskText = "Создай, задачу!"),
            candidate("c2", taskText = "  создай   задачу "),
            candidate("c3", taskText = "Проверь календарь"),
        )
        val dispatched = mutableListOf<String>()
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = analyzer,
            suggestionConfig = AmbientSuggestionConfig(maxPendingSuggestions = 2),
            executeCandidate = { dispatched += it.taskText },
        )

        controller.start()
        runCurrent()
        emitClosedLiveBlock(transcriptEvents, "Союз, создай задачу")
        emitClosedLiveBlock(transcriptEvents, "Союз, создай задачу пожалуйста")
        emitClosedLiveBlock(transcriptEvents, "Союз, проверь календарь")

        assertEquals(listOf("c1", "c3"), controller.suggestions.value.map { it.id })

        assertTrue(controller.acceptSuggestion("c1"))
        assertFalse(controller.acceptSuggestion("c1"))
        controller.rejectSuggestion("c3")

        assertEquals(listOf("Создай, задачу!"), dispatched)
        assertEquals(emptyList(), controller.suggestions.value)
        controller.stop()
    }

    @Test
    fun `stop clears volatile state pending suggestions and cancels inactivity analysis`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val analyzer = FakeAnalyzer { candidate("c1") }
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = analyzer,
        )

        controller.start()
        runCurrent()
        transcriptEvents.emit(event(id = "e1", text = "Союз, проверь календарь"))
        controller.stop(clearSuggestions = true)
        advanceTimeBy(1_000L)
        advanceUntilIdle()

        assertEquals(1, transcription.stopCalls)
        assertEquals(1, transcription.clearCalls)
        assertEquals(emptyList(), analyzer.blocks)
        assertEquals(emptyList(), controller.suggestions.value)
        assertEquals(AmbientModeState(), controller.modeState.value)
    }

    @Test
    fun `stopAsync returns without waiting for cleanup`() = runTest {
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        val transcription = FakeTranscriptionService(
            onStop = {
                stopEntered.complete(Unit)
                releaseStop.await()
            }
        )
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = FakeAnalyzer { null },
        )

        controller.stopAsync(clearSuggestions = true)
        runCurrent()

        assertTrue(stopEntered.isCompleted)
        assertFalse(releaseStop.isCompleted)

        releaseStop.complete(Unit)
        advanceUntilIdle()
        assertEquals(AmbientModeState(), controller.modeState.value)
    }

    @Test
    fun `stopAsync can cleanup from external scope after controller scope is cancelled`() = runTest {
        val controllerScope = TestScope(StandardTestDispatcher(testScheduler))
        val cleanupScope = TestScope(StandardTestDispatcher(testScheduler))
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        val analyzer = FakeAnalyzer { candidate("c1") }
        val controller = controller(
            scope = controllerScope,
            transcription = transcription,
            analyzer = analyzer,
        )

        controller.start()
        assertTrue(controller.modeState.value.enabled)

        controllerScope.cancel()
        controller.stopAsync(cleanupScope = cleanupScope, clearSuggestions = true)
        advanceUntilIdle()

        assertEquals(1, transcription.stopCalls)
        assertEquals(1, transcription.clearCalls)
        assertEquals(emptyList(), controller.suggestions.value)
        assertEquals(AmbientModeState(), controller.modeState.value)
        cleanupScope.cancel()
    }

    @Test
    fun `accept consumes suggestion and dispatches task`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        val analyzer = FakeAnalyzer { candidate("c1", taskText = "Проверь календарь") }
        val dispatched = mutableListOf<String>()
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = analyzer,
            executeCandidate = { dispatched += it.taskText },
        )

        controller.start()
        runCurrent()
        emitClosedLiveBlock(transcriptEvents, "Союз, проверь календарь")

        assertTrue(controller.acceptSuggestion("c1"))

        assertEquals(listOf("Проверь календарь"), dispatched)
        assertEquals(emptyList(), controller.suggestions.value)
        controller.stop()
    }

    @Test
    fun `accept suppresses cumulative live transcript while confirmed task is executing`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        var nextCandidateOrdinal = 0
        val analyzer = FakeAnalyzer { block ->
            nextCandidateOrdinal += 1
            candidate("c$nextCandidateOrdinal", taskText = block.text)
        }
        val releaseExecution = CompletableDeferred<Unit>()
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = analyzer,
            executeCandidate = { releaseExecution.await() },
            postAcceptSuppressionMs = 10L,
        )

        controller.start()
        runCurrent()
        emitClosedLiveBlock(transcriptEvents, "посмотри что в моем тг")
        assertEquals(listOf("посмотри что в моем тг"), controller.suggestions.value.map { it.candidate.taskText })

        val acceptJob = launch { controller.acceptSuggestion(controller.suggestions.value.single().id) }
        runCurrent()
        transcriptEvents.emit(
            event(
                id = "accepted-cumulative",
                text = "посмотри что в моем тг и расскажи что там",
                isFinal = false,
            )
        )
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(emptyList(), controller.suggestions.value)
        assertEquals(listOf("посмотри что в моем тг"), analyzer.blocks.map { it.text })

        releaseExecution.complete(Unit)
        acceptJob.join()
        advanceTimeBy(11L)
        transcriptEvents.emit(
            event(
                id = "new-cumulative",
                text = "посмотри что в моем тг и расскажи что там открой календарь",
                isFinal = false,
            )
        )
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("открой календарь"), analyzer.blocks.drop(1).map { it.text })
        controller.stop()
    }

    @Test
    fun `accept drops late analysis results produced during confirmed task execution`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val transcription = FakeTranscriptionService(transcriptEvents = transcriptEvents)
        val lateAnalysisEntered = CompletableDeferred<Unit>()
        val releaseLateAnalysis = CompletableDeferred<Unit>()
        val releaseExecution = CompletableDeferred<Unit>()
        var analyzeCalls = 0
        val analyzer = object : AmbientBlockAnalyzer {
            override suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate? {
                analyzeCalls += 1
                return if (analyzeCalls == 1) {
                    candidate("c1", taskText = block.text)
                } else {
                    lateAnalysisEntered.complete(Unit)
                    releaseLateAnalysis.await()
                    candidate("late", taskText = block.text)
                }
            }
        }
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = analyzer,
            executeCandidate = { releaseExecution.await() },
        )

        controller.start()
        runCurrent()
        emitClosedLiveBlock(transcriptEvents, "посмотри что в моем тг")
        assertEquals(listOf("c1"), controller.suggestions.value.map { it.id })

        emitClosedLiveBlock(transcriptEvents, "открой календарь")
        lateAnalysisEntered.await()

        val acceptJob = launch { controller.acceptSuggestion("c1") }
        runCurrent()
        releaseLateAnalysis.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), controller.suggestions.value)

        releaseExecution.complete(Unit)
        acceptJob.join()
        controller.stop()
    }

    @Test
    fun `microphone is busy while ambient is starting or listening`() = runTest {
        val transcription = FakeTranscriptionService(startResult = AmbientSpeechAvailability.Available)
        val controller = controller(
            scope = this,
            transcription = transcription,
            analyzer = FakeAnalyzer { null },
        )

        controller.start()
        assertTrue(controller.isMicrophoneBusyForVoiceInput)

        controller.stop()
        assertFalse(controller.isMicrophoneBusyForVoiceInput)
    }

    private fun controller(
        scope: TestScope,
        transcription: AmbientTranscriptionService = FakeTranscriptionService(),
        analyzer: AmbientBlockAnalyzer,
        suggestionConfig: AmbientSuggestionConfig = AmbientSuggestionConfig(),
        executeCandidate: suspend (AmbientTaskCandidate) -> Unit = {},
        postAcceptSuppressionMs: Long = 5_000L,
    ): AmbientModeController = AmbientModeController(
        scope = scope,
        transcription = transcription,
        blockBuilder = SemanticBlockBuilder(
            clock = { scope.testScheduler.currentTime },
            config = SemanticBlockBuilderConfig(minUsefulChars = 1),
        ),
        analyzer = analyzer,
        executeCandidate = executeCandidate,
        localeProvider = { "ru-RU" },
        failureMessageProvider = { "failure:$it" },
        suggestionConfig = suggestionConfig,
        clock = { scope.testScheduler.currentTime },
        postAcceptSuppressionMs = postAcceptSuppressionMs,
    )

    private fun candidate(
        id: String,
        taskText: String = "Task $id",
    ): AmbientTaskCandidate = AmbientTaskCandidate(
        id = id,
        taskText = taskText,
        confidence = 1.0,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
        evidenceEventIds = listOf("e1"),
    )

    private suspend fun TestScope.emitClosedLiveBlock(
        transcriptEvents: MutableSharedFlow<AmbientTranscriptEvent>,
        text: String,
    ) {
        transcriptEvents.emit(event(id = "e${testScheduler.currentTime}", text = text))
        advanceTimeBy(1_000L)
        runCurrent()
    }

    private fun event(
        id: String,
        text: String,
        isFinal: Boolean = true,
        source: AmbientTranscriptSource = AmbientTranscriptSource.LIVE,
    ): AmbientTranscriptEvent = AmbientTranscriptEvent(
        id = id,
        text = text,
        isFinal = isFinal,
        startedAtMs = 100L,
        endedAtMs = 200L,
        receivedAtMs = 200L,
        source = source,
    )

    private class FakeAnalyzer(
        private val candidateProvider: (AmbientSemanticBlock) -> AmbientTaskCandidate?,
    ) : AmbientBlockAnalyzer {
        val blocks = mutableListOf<AmbientSemanticBlock>()

        override suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate? {
            blocks += block
            return candidateProvider(block)
        }
    }

    private class QueueAnalyzer(
        vararg candidates: AmbientTaskCandidate,
    ) : AmbientBlockAnalyzer {
        private val pending = ArrayDeque(candidates.toList())

        override suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate? =
            pending.removeFirstOrNull()
    }

    private class FakeTranscriptionService(
        private val startResult: AmbientSpeechAvailability = AmbientSpeechAvailability.Available,
        override val transcriptEvents: MutableSharedFlow<AmbientTranscriptEvent> = MutableSharedFlow(),
        private val onStop: suspend () -> Unit = {},
    ) : AmbientTranscriptionService {
        private val mutableState = MutableStateFlow<AmbientTranscriptionState>(AmbientTranscriptionState.Stopped)
        override val state = mutableState
        val startedLocales = mutableListOf<String>()
        var stopCalls = 0
        var clearCalls = 0

        override suspend fun availability(locale: String): AmbientSpeechAvailability = startResult

        override suspend fun start(locale: String): AmbientSpeechAvailability {
            startedLocales += locale
            if (startResult == AmbientSpeechAvailability.Available) {
                mutableState.value = AmbientTranscriptionState.Listening(locale)
            }
            return startResult
        }

        override suspend fun stop() {
            stopCalls += 1
            onStop()
            mutableState.value = AmbientTranscriptionState.Stopped
        }

        override suspend fun clearTranscript() {
            clearCalls += 1
        }
    }
}
