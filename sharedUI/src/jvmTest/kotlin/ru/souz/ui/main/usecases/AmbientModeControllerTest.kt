package ru.souz.ui.main.usecases

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import ru.souz.ambient.AmbientAddressedness
import ru.souz.ambient.AmbientBlockAnalyzer
import ru.souz.ambient.AmbientBlockCloseReason
import ru.souz.ambient.AmbientModeState
import ru.souz.ambient.AmbientSemanticBlock
import ru.souz.ambient.AmbientSemanticBlockService
import ru.souz.ambient.AmbientSpeakerRole
import ru.souz.ambient.AmbientSpeechAvailability
import ru.souz.ambient.AmbientSuggestionStore
import ru.souz.ambient.AmbientTaskCandidate
import ru.souz.ambient.AmbientTranscriptEvent
import ru.souz.ambient.AmbientTranscriptionService
import ru.souz.ambient.AmbientTranscriptionState
import ru.souz.ambient.InMemoryAmbientSuggestionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AmbientModeControllerTest {

    @Test
    fun `start launches transcription and analyzes emitted blocks into pending suggestions`() = runTest {
        val blocks = MutableSharedFlow<AmbientSemanticBlock>()
        val transcription = FakeTranscriptionService()
        val semanticBlocks = FakeSemanticBlockService(blocks)
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        val controller = controller(
            scope = this,
            transcription = transcription,
            semanticBlocks = semanticBlocks,
            analyzer = FakeAnalyzer(candidate("c1")),
            store = store,
        )

        controller.start()
        runCurrent()
        blocks.emit(block("b1"))
        advanceUntilIdle()

        assertEquals(1, semanticBlocks.startCalls)
        assertEquals(listOf("ru-RU"), transcription.startedLocales)
        assertEquals(listOf("c1"), store.pending.value.map { it.id })
        assertEquals(AmbientModeState(enabled = true, listening = true, analyzing = true), controller.modeState.value)
        controller.stop()
    }

    @Test
    fun `stop clears volatile state and pending suggestions`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.add(candidate("c1"))
        val transcription = FakeTranscriptionService()
        val semanticBlocks = FakeSemanticBlockService(MutableSharedFlow())
        val controller = controller(
            scope = this,
            transcription = transcription,
            semanticBlocks = semanticBlocks,
            analyzer = FakeAnalyzer(null),
            store = store,
        )

        controller.start()
        controller.stop(clearSuggestions = true)

        assertEquals(1, transcription.stopCalls)
        assertEquals(1, transcription.clearCalls)
        assertEquals(1, semanticBlocks.stopCalls)
        assertEquals(1, semanticBlocks.clearCalls)
        assertEquals(emptyList(), store.pending.value)
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
            semanticBlocks = FakeSemanticBlockService(MutableSharedFlow()),
            analyzer = FakeAnalyzer(null),
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
    fun `accept consumes suggestion and dispatches task`() = runTest {
        val store = InMemoryAmbientSuggestionStore(clock = { 1_000L })
        store.add(candidate("c1", taskText = "Проверь календарь"))
        val dispatched = mutableListOf<String>()
        val controller = controller(
            scope = this,
            semanticBlocks = FakeSemanticBlockService(MutableSharedFlow()),
            analyzer = FakeAnalyzer(null),
            store = store,
            actionHandler = AmbientSuggestionActionHandler(store) { dispatched += it.taskText },
        )

        assertTrue(controller.acceptSuggestion("c1"))

        assertEquals(listOf("Проверь календарь"), dispatched)
        assertEquals(emptyList(), store.pending.value)
    }

    @Test
    fun `microphone is busy while ambient is starting or listening`() = runTest {
        val transcription = FakeTranscriptionService(startResult = AmbientSpeechAvailability.Available)
        val controller = controller(
            scope = this,
            transcription = transcription,
            semanticBlocks = FakeSemanticBlockService(MutableSharedFlow()),
            analyzer = FakeAnalyzer(null),
        )

        controller.start()
        assertTrue(controller.isMicrophoneBusyForVoiceInput)

        controller.stop()
        assertFalse(controller.isMicrophoneBusyForVoiceInput)
    }

    private fun controller(
        scope: TestScope,
        transcription: AmbientTranscriptionService = FakeTranscriptionService(),
        semanticBlocks: AmbientSemanticBlockService,
        analyzer: AmbientBlockAnalyzer,
        store: AmbientSuggestionStore = InMemoryAmbientSuggestionStore(clock = { 1_000L }),
        actionHandler: AmbientSuggestionActionHandler = AmbientSuggestionActionHandler(store) {},
    ): AmbientModeController = AmbientModeController(
        appScope = scope,
        transcription = transcription,
        semanticBlocks = semanticBlocks,
        analyzer = analyzer,
        suggestionStore = store,
        actionHandler = actionHandler,
        localeProvider = { "ru-RU" },
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

    private fun block(id: String): AmbientSemanticBlock = AmbientSemanticBlock(
        id = id,
        text = "Союз, помоги",
        eventIds = listOf("e1"),
        startedAtMs = 100L,
        endedAtMs = 200L,
        closedAtMs = 300L,
        closeReason = AmbientBlockCloseReason.PAUSE,
        speakerRole = AmbientSpeakerRole.PROBABLY_USER,
        addressedness = AmbientAddressedness.DIRECT_TO_SOUZ,
    )

    private class FakeAnalyzer(
        private val candidate: AmbientTaskCandidate?,
    ) : AmbientBlockAnalyzer {
        override suspend fun analyze(block: AmbientSemanticBlock): AmbientTaskCandidate? = candidate
    }

    private class FakeSemanticBlockService(
        override val blocks: Flow<AmbientSemanticBlock>,
    ) : AmbientSemanticBlockService {
        var startCalls = 0
        var stopCalls = 0
        var clearCalls = 0

        override suspend fun start() {
            startCalls += 1
        }

        override suspend fun stop() {
            stopCalls += 1
        }

        override fun clear() {
            clearCalls += 1
        }
    }

    private class FakeTranscriptionService(
        private val startResult: AmbientSpeechAvailability = AmbientSpeechAvailability.Available,
        private val onStop: suspend () -> Unit = {},
    ) : AmbientTranscriptionService {
        private val mutableState = MutableStateFlow<AmbientTranscriptionState>(AmbientTranscriptionState.Stopped)
        override val state = mutableState
        override val transcriptEvents = emptyFlow<AmbientTranscriptEvent>()
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
