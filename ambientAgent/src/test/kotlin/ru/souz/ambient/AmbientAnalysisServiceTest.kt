package ru.souz.ambient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.service.speech.ambient.AmbientTranscriptEvent
import ru.souz.service.speech.ambient.AmbientTranscriptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AmbientAnalysisServiceTest {

    @Test
    fun `closed semantic block triggers analyzer exactly once`() = runTest {
        val analyzer = FakeAnalyzer(
            AmbientAnalysisResult(
                blockId = "b1",
                blockSummary = null,
                extractedStatements = emptyList(),
                taskCandidates = listOf(candidate("b1", AmbientAddressedness.DIRECT_TO_SOUZ, confidence = 0.7)),
            )
        )
        val service = DefaultAmbientAnalysisService(
            analyzer = analyzer,
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )
        val block = block(id = "b1", addressedness = AmbientAddressedness.DIRECT_TO_SOUZ)

        val result = service.analyzeBlock(block)

        assertEquals("b1", result.blockId)
        assertEquals(listOf("b1"), analyzer.calls.map { it.block.id })
        assertEquals(1, service.recentAnalyses().size)
    }

    @Test
    fun `semantic block service ignores non-live volatile transcript events`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val blockService = DefaultAmbientSemanticBlockService(
            transcriptEvents = transcriptEvents,
            builder = SemanticBlockBuilder(config = SemanticBlockBuilderConfig(minUsefulChars = 1)),
            scope = this,
        )
        blockService.start()

        transcriptEvents.emit(
            transcriptEvent(
                "v1",
                "draft",
                isFinal = false,
                source = AmbientTranscriptSource.BATCH_FALLBACK,
            )
        )
        blockService.stop()
        advanceUntilIdle()

        assertEquals(emptyList(), blockService.snapshot())
    }

    @Test
    fun `semantic block service closes live volatile utterance after one second inactivity`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val blockService = DefaultAmbientSemanticBlockService(
            transcriptEvents = transcriptEvents,
            builder = SemanticBlockBuilder(
                clock = { testScheduler.currentTime },
                config = SemanticBlockBuilderConfig(minUsefulChars = 1),
            ),
            scope = this,
        )
        val emitted = mutableListOf<AmbientSemanticBlock>()
        val collectJob = launch { blockService.blocks.take(1).toList(emitted) }
        try {
            blockService.start()
            advanceUntilIdle()

            transcriptEvents.emit(
                transcriptEvent(
                    id = "live-1",
                    text = "Найди заметку с долгами сколько мне должна Юля",
                    isFinal = false,
                    startedAtMs = 0L,
                    endedAtMs = 12_840L,
                    source = AmbientTranscriptSource.LIVE,
                )
            )
            advanceTimeBy(999L)
            assertEquals(emptyList(), emitted)

            advanceTimeBy(1L)
            advanceUntilIdle()

            assertEquals(1, emitted.size)
            assertEquals("Найди заметку с долгами сколько мне должна Юля", emitted.single().text)
            assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, emitted.single().addressedness)
            assertEquals(AmbientBlockCloseReason.PAUSE, emitted.single().closeReason)
        } finally {
            collectJob.cancel()
            blockService.stop()
        }
    }

    @Test
    fun `semantic block service closes final utterance after default one second inactivity`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val blockService = DefaultAmbientSemanticBlockService(
            transcriptEvents = transcriptEvents,
            builder = SemanticBlockBuilder(
                clock = { testScheduler.currentTime },
                config = SemanticBlockBuilderConfig(minUsefulChars = 1),
            ),
            scope = this,
        )
        val emitted = mutableListOf<AmbientSemanticBlock>()
        val collectJob = launch { blockService.blocks.take(1).toList(emitted) }
        try {
            blockService.start()
            advanceUntilIdle()

            transcriptEvents.emit(
                transcriptEvent(
                    id = "direct-1",
                    text = "Союз, нужна помощь",
                    isFinal = true,
                    startedAtMs = 100L,
                    endedAtMs = 200L,
                )
            )
            advanceTimeBy(999L)
            assertEquals(emptyList(), emitted)

            advanceTimeBy(1L)
            advanceUntilIdle()

            assertEquals(1, emitted.size)
            assertEquals("Союз, нужна помощь", emitted.single().text)
            assertEquals(AmbientBlockCloseReason.PAUSE, emitted.single().closeReason)
        } finally {
            collectJob.cancel()
            blockService.stop()
        }
    }

    @Test
    fun `semantic block service keeps contiguous batch fallback windows open beyond one second inactivity`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val blockService = DefaultAmbientSemanticBlockService(
            transcriptEvents = transcriptEvents,
            builder = SemanticBlockBuilder(
                clock = { testScheduler.currentTime },
                config = SemanticBlockBuilderConfig(minUsefulChars = 1),
            ),
            scope = this,
        )
        val emitted = mutableListOf<AmbientSemanticBlock>()
        val collectJob = launch { blockService.blocks.take(1).toList(emitted) }
        try {
            blockService.start()
            advanceUntilIdle()

            transcriptEvents.emit(
                transcriptEvent(
                    id = "batch-1",
                    text = "проверь календарь",
                    isFinal = true,
                    startedAtMs = 1_000L,
                    endedAtMs = 4_000L,
                    source = AmbientTranscriptSource.BATCH_FALLBACK,
                )
            )
            advanceTimeBy(2_900L)
            runCurrent()
            assertEquals(emptyList(), emitted)

            transcriptEvents.emit(
                transcriptEvent(
                    id = "batch-2",
                    text = "на завтра",
                    isFinal = true,
                    startedAtMs = 4_000L,
                    endedAtMs = 7_000L,
                    source = AmbientTranscriptSource.BATCH_FALLBACK,
                )
            )
            advanceTimeBy(3_500L)
            advanceUntilIdle()

            assertEquals("проверь календарь на завтра", emitted.single().text)
            assertEquals(AmbientAddressedness.IMPLICIT_USER_INTENT, emitted.single().addressedness)
        } finally {
            collectJob.cancel()
            blockService.stop()
        }
    }

    @Test
    fun `default local analysis timeout waits ten seconds`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = DelayedAnalyzer(delayMs = 60_000L),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )

        val analysisJob = launch {
            service.analyzeBlock(block("slow", AmbientAddressedness.IMPLICIT_USER_INTENT))
        }
        runCurrent()

        advanceTimeBy(9_999L)
        assertTrue(analysisJob.isActive)

        advanceTimeBy(1L)
        advanceUntilIdle()

        assertTrue(analysisJob.isCompleted)
        assertEquals("ambient analysis timed out", service.recentAnalyses().single().blockSummary)
    }

    @Test
    fun `analysis service filters low confidence candidates`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = FakeAnalyzer(
                AmbientAnalysisResult(
                    blockId = "b1",
                    blockSummary = null,
                    extractedStatements = emptyList(),
                    taskCandidates = listOf(candidate("b1", AmbientAddressedness.IMPLICIT_USER_INTENT, confidence = 0.7)),
                )
            ),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )
        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }

        service.analyzeBlock(block("b1", AmbientAddressedness.IMPLICIT_USER_INTENT))
        advanceUntilIdle()

        assertTrue(emitted.isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `direct addressed threshold is lower than implicit threshold`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = FakeAnalyzer(
                AmbientAnalysisResult(
                    blockId = "b1",
                    blockSummary = null,
                    extractedStatements = emptyList(),
                    taskCandidates = listOf(candidate("b1", AmbientAddressedness.DIRECT_TO_SOUZ, confidence = 0.7)),
                )
            ),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )
        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }
        runCurrent()

        service.analyzeBlock(block("b1", AmbientAddressedness.DIRECT_TO_SOUZ))
        advanceUntilIdle()

        assertEquals(1, emitted.size)
        collectJob.cancel()
    }

    @Test
    fun `background blocks do not emit candidates`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = FakeAnalyzer(
                AmbientAnalysisResult(
                    blockId = "b1",
                    blockSummary = null,
                    extractedStatements = emptyList(),
                    taskCandidates = listOf(candidate("b1", AmbientAddressedness.BACKGROUND_OR_QUOTED, confidence = 1.0)),
                )
            ),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )
        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }

        service.analyzeBlock(block("b1", AmbientAddressedness.BACKGROUND_OR_QUOTED))
        advanceUntilIdle()

        assertEquals(emptyList(), emitted)
        collectJob.cancel()
    }

    @Test
    fun `recent context is limited`() = runTest {
        val analyzer = FakeAnalyzer(
            AmbientAnalysisResult("last", null, emptyList(), emptyList())
        )
        val service = DefaultAmbientAnalysisService(
            analyzer = analyzer,
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
            config = AmbientAnalysisServiceConfig(recentBlockLimit = 3),
        )

        repeat(5) { index ->
            service.analyzeBlock(block("b$index", AmbientAddressedness.UNKNOWN))
        }

        assertEquals(listOf("b1", "b2", "b3"), analyzer.calls.last().recentContext.map { it.id })
    }

    @Test
    fun `clear removes analyses and candidates`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = FakeAnalyzer(AmbientAnalysisResult("b1", null, emptyList(), emptyList())),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )

        service.analyzeBlock(block("b1", AmbientAddressedness.UNKNOWN))
        service.clear()

        assertEquals(emptyList(), service.recentAnalyses())
    }

    @Test
    fun `cleared task candidate flow does not replay stale candidates to new collectors`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = FakeAnalyzer(
                AmbientAnalysisResult(
                    blockId = "b1",
                    blockSummary = null,
                    extractedStatements = emptyList(),
                    taskCandidates = listOf(candidate("b1", AmbientAddressedness.DIRECT_TO_SOUZ, confidence = 0.7)),
                )
            ),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )

        service.analyzeBlock(block("b1", AmbientAddressedness.DIRECT_TO_SOUZ))
        service.clear()

        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }
        runCurrent()

        assertEquals(emptyList(), emitted)
        collectJob.cancel()
    }

    @Test
    fun `stop cancels analysis jobs`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = SuspendingAnalyzer(),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )
        val job = launch { service.analyzeBlock(block("b1", AmbientAddressedness.DIRECT_TO_SOUZ)) }

        service.stop()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
    }

    @Test
    fun `local model unavailable does not crash service`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = UnavailableAnalyzer(),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )

        val result = service.analyzeBlock(block("b1", AmbientAddressedness.DIRECT_TO_SOUZ))

        assertEquals(emptyList(), result.taskCandidates)
        assertTrue(service.recentAnalyses().isNotEmpty())
    }

    @Test
    fun `obvious weather request does not emit rule based candidate before local analysis`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = DelayedAnalyzer(delayMs = 60_000L),
            capabilityProvider = StaticCapabilityProvider(listOf(webSearchCapability())),
            scope = this,
            config = AmbientAnalysisServiceConfig(analysisTimeoutMs = 1_000L),
        )
        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }

        val analysisJob = launch {
            service.analyzeBlock(
                block(
                    id = "weather",
                    addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
                    text = "я хотел бы чтобы кто-то посмотрел погоду",
                )
            )
        }
        runCurrent()

        assertEquals(emptyList(), emitted)
        assertTrue(analysisJob.isActive)

        advanceTimeBy(1_000L)
        advanceUntilIdle()

        assertTrue(analysisJob.isCompleted)
        assertEquals(1, service.recentAnalyses().size)
        assertEquals(emptyList(), service.recentAnalyses().single().taskCandidates)
        collectJob.cancel()
    }

    @Test
    fun `calendar scheduling hint does not emit without model candidate`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = DelayedAnalyzer(delayMs = 60_000L),
            capabilityProvider = StaticCapabilityProvider(listOf(calendarCreateCapability())),
            scope = this,
            config = AmbientAnalysisServiceConfig(analysisTimeoutMs = 1_000L),
        )
        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }

        val analysisJob = launch {
            service.analyzeBlock(
                block(
                    id = "meeting",
                    addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
                    text = "надо не забыть поставить в шесть вечеру встречу с командой",
                )
            )
        }
        runCurrent()

        assertEquals(emptyList(), emitted)

        analysisJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun `model candidate is emitted after local analysis completes`() = runTest {
        val service = DefaultAmbientAnalysisService(
            analyzer = FakeAnalyzer(
                AmbientAnalysisResult(
                    blockId = "weather",
                    blockSummary = null,
                    extractedStatements = emptyList(),
                    taskCandidates = listOf(
                        candidate(
                            blockId = "weather",
                            addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
                            confidence = 0.9,
                        )
                    ),
                )
            ),
            capabilityProvider = StaticCapabilityProvider(),
            scope = this,
        )
        val emitted = mutableListOf<AmbientTaskCandidate>()
        val collectJob = launch { service.taskCandidates.take(1).toList(emitted) }
        runCurrent()

        service.analyzeBlock(
            block(
                id = "weather",
                addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
                text = "интересно, какая погода в Москве",
            )
        )
        advanceUntilIdle()

        assertEquals(listOf("t-weather"), emitted.map { it.id })
        collectJob.cancel()
    }

    private fun block(
        id: String,
        addressedness: AmbientAddressedness,
        text: String = "Союз, создай задачу",
    ): AmbientSemanticBlock = AmbientSemanticBlock(
        id = id,
        text = text,
        eventIds = listOf("e1"),
        startedAtMs = 100,
        endedAtMs = 200,
        closedAtMs = 300,
        closeReason = AmbientBlockCloseReason.MANUAL_FLUSH,
        speakerRole = AmbientSpeakerRole.PROBABLY_USER,
        addressedness = addressedness,
    )

    private fun candidate(
        blockId: String,
        addressedness: AmbientAddressedness,
        confidence: Double,
    ): AmbientTaskCandidate = AmbientTaskCandidate(
        id = "t-$blockId",
        title = "Task",
        taskText = "Do task",
        suggestionText = "Похоже, я могу помочь: do task",
        confidence = confidence,
        addressedness = addressedness,
        matchedCapabilityIds = emptyList(),
        missingSlots = emptyList(),
        risk = AmbientTaskRisk.LOW,
        requiresConfirmation = true,
        evidenceEventIds = listOf("e1"),
        reason = "test",
    )

    private fun transcriptEvent(
        id: String,
        text: String,
        isFinal: Boolean,
        startedAtMs: Long? = null,
        endedAtMs: Long? = null,
        receivedAtMs: Long = endedAtMs ?: startedAtMs ?: 1_000L,
        source: AmbientTranscriptSource = AmbientTranscriptSource.LIVE,
    ): AmbientTranscriptEvent =
        AmbientTranscriptEvent(
            id = id,
            text = text,
            isFinal = isFinal,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            receivedAtMs = receivedAtMs,
            source = source,
        )

    private fun webSearchCapability(): AmbientCapability = AmbientCapability(
        id = "tool:WEB_SEARCH:InternetSearch",
        kind = AmbientCapabilityKind.TOOL,
        category = "WEB_SEARCH",
        name = "InternetSearch",
        description = "Short factual internet lookup for weather",
        risk = AmbientCapabilityRisk.LOW,
    )

    private fun calendarCreateCapability(): AmbientCapability = AmbientCapability(
        id = "tool:CALENDAR:CalendarCreateEvent",
        kind = AmbientCapabilityKind.TOOL,
        category = "CALENDAR",
        name = "CalendarCreateEvent",
        description = "Create an event in macOS Calendar.",
        risk = AmbientCapabilityRisk.MEDIUM,
    )

    private class FakeAnalyzer(
        private val result: AmbientAnalysisResult,
    ) : AmbientBlockAnalyzer {
        val calls = mutableListOf<Call>()

        override suspend fun analyze(
            block: AmbientSemanticBlock,
            recentContext: List<AmbientSemanticBlock>,
            capabilities: List<AmbientCapability>,
        ): AmbientAnalysisResult {
            calls += Call(block, recentContext, capabilities)
            return result.copy(blockId = block.id)
        }

        data class Call(
            val block: AmbientSemanticBlock,
            val recentContext: List<AmbientSemanticBlock>,
            val capabilities: List<AmbientCapability>,
        )
    }

    private class SuspendingAnalyzer : AmbientBlockAnalyzer {
        override suspend fun analyze(
            block: AmbientSemanticBlock,
            recentContext: List<AmbientSemanticBlock>,
            capabilities: List<AmbientCapability>,
        ): AmbientAnalysisResult {
            throw CancellationException("cancelled")
        }
    }

    private class UnavailableAnalyzer : AmbientBlockAnalyzer {
        override suspend fun analyze(
            block: AmbientSemanticBlock,
            recentContext: List<AmbientSemanticBlock>,
            capabilities: List<AmbientCapability>,
        ): AmbientAnalysisResult = AmbientAnalysisResult(
            blockId = block.id,
            blockSummary = "local model unavailable",
            extractedStatements = emptyList(),
            taskCandidates = emptyList(),
        )
    }

    private class StaticCapabilityProvider(
        private val capabilities: List<AmbientCapability> = emptyList(),
    ) : AmbientCapabilityProvider {
        override suspend fun capabilities(): List<AmbientCapability> = capabilities
    }

    private class DelayedAnalyzer(
        private val delayMs: Long,
    ) : AmbientBlockAnalyzer {
        override suspend fun analyze(
            block: AmbientSemanticBlock,
            recentContext: List<AmbientSemanticBlock>,
            capabilities: List<AmbientCapability>,
        ): AmbientAnalysisResult {
            delay(delayMs)
            return AmbientAnalysisResult(block.id, null, emptyList(), emptyList())
        }
    }
}
