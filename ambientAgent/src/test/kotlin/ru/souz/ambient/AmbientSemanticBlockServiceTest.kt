package ru.souz.ambient

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientSemanticBlockServiceTest {

    @Test
    fun `service emits closed live block through flow`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val service = service(transcriptEvents, this)
        val emitted = mutableListOf<AmbientSemanticBlock>()
        val collectJob = launch { service.blocks.take(1).toList(emitted) }

        service.start()
        advanceUntilIdle()
        transcriptEvents.emit(event(id = "e1", text = "Союз, нужна помощь", startedAtMs = 100L, endedAtMs = 200L))
        advanceTimeBy(999L)
        assertEquals(emptyList(), emitted)

        advanceTimeBy(1L)
        advanceUntilIdle()

        assertEquals("Союз, нужна помощь", emitted.single().text)
        assertEquals(AmbientBlockCloseReason.PAUSE, emitted.single().closeReason)
        collectJob.cancel()
        service.stop()
    }

    @Test
    fun `service ignores batch volatile events`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val service = service(transcriptEvents, this)

        service.start()
        advanceUntilIdle()
        transcriptEvents.emit(
            event(
                id = "v1",
                text = "draft",
                isFinal = false,
                source = AmbientTranscriptSource.BATCH_FALLBACK,
            )
        )
        service.stop()

        val emitted = mutableListOf<AmbientSemanticBlock>()
        val collectJob = launch { service.blocks.take(1).toList(emitted) }
        advanceUntilIdle()
        assertEquals(emptyList(), emitted)
        collectJob.cancel()
    }

    @Test
    fun `service keeps contiguous batch fallback windows open beyond live inactivity`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val service = service(transcriptEvents, this)
        val emitted = mutableListOf<AmbientSemanticBlock>()
        val collectJob = launch { service.blocks.take(1).toList(emitted) }

        service.start()
        advanceUntilIdle()
        transcriptEvents.emit(
            event(
                id = "batch-1",
                text = "проверь календарь",
                startedAtMs = 1_000L,
                endedAtMs = 4_000L,
                source = AmbientTranscriptSource.BATCH_FALLBACK,
            )
        )
        advanceTimeBy(2_900L)
        runCurrent()
        assertEquals(emptyList(), emitted)

        transcriptEvents.emit(
            event(
                id = "batch-2",
                text = "на завтра",
                startedAtMs = 4_000L,
                endedAtMs = 7_000L,
                source = AmbientTranscriptSource.BATCH_FALLBACK,
            )
        )
        advanceTimeBy(3_500L)
        advanceUntilIdle()

        assertEquals("проверь календарь на завтра", emitted.single().text)
        assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, emitted.single().addressedness)
        collectJob.cancel()
        service.stop()
    }

    private fun service(
        transcriptEvents: MutableSharedFlow<AmbientTranscriptEvent>,
        scope: TestScope,
    ): DefaultAmbientSemanticBlockService = DefaultAmbientSemanticBlockService(
        transcriptEvents = transcriptEvents,
        builder = SemanticBlockBuilder(
            clock = { scope.testScheduler.currentTime },
            config = SemanticBlockBuilderConfig(minUsefulChars = 1),
        ),
        scope = scope,
    )

    private fun event(
        id: String,
        text: String,
        isFinal: Boolean = true,
        startedAtMs: Long? = null,
        endedAtMs: Long? = null,
        receivedAtMs: Long = endedAtMs ?: startedAtMs ?: 1_000L,
        source: AmbientTranscriptSource = AmbientTranscriptSource.LIVE,
    ): AmbientTranscriptEvent = AmbientTranscriptEvent(
        id = id,
        text = text,
        isFinal = isFinal,
        startedAtMs = startedAtMs,
        endedAtMs = endedAtMs,
        receivedAtMs = receivedAtMs,
        source = source,
    )
}
