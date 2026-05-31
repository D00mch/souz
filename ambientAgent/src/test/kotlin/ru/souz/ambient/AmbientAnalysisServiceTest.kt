package ru.souz.ambient

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.souz.service.speech.ambient.AmbientTranscriptEvent
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
    fun `semantic block service ignores volatile transcript events`() = runTest {
        val transcriptEvents = MutableSharedFlow<AmbientTranscriptEvent>()
        val blockService = DefaultAmbientSemanticBlockService(
            transcriptEvents = transcriptEvents,
            builder = SemanticBlockBuilder(config = SemanticBlockBuilderConfig(minUsefulChars = 1)),
            scope = this,
        )
        blockService.start()

        transcriptEvents.emit(transcriptEvent("v1", "draft", isFinal = false))
        blockService.stop()
        advanceUntilIdle()

        assertEquals(emptyList(), blockService.snapshot())
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

    private fun block(
        id: String,
        addressedness: AmbientAddressedness,
    ): AmbientSemanticBlock = AmbientSemanticBlock(
        id = id,
        text = "Союз, создай задачу",
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

    private fun transcriptEvent(id: String, text: String, isFinal: Boolean): AmbientTranscriptEvent =
        AmbientTranscriptEvent(
            id = id,
            text = text,
            isFinal = isFinal,
            startedAtMs = null,
            endedAtMs = null,
            receivedAtMs = 1_000,
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

    private class StaticCapabilityProvider : AmbientCapabilityProvider {
        override suspend fun capabilities(): List<AmbientCapability> = emptyList()
    }
}
