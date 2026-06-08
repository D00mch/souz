package ru.souz.ambient

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class AmbientAnalysisPipelineTest {

    @Test
    fun `active analysis continues while pending block is replaced by latest`() = runTest {
        val blockService = TestAmbientSemanticBlockService()
        val analysisService = BlockingAmbientAnalysisService()
        val pipeline = DefaultAmbientAnalysisPipeline(
            blockService = blockService,
            analysisService = analysisService,
            scope = backgroundScope,
        )

        pipeline.start()
        blockService.awaitSubscriber()
        try {
            blockService.emit(block("b1"))
            assertEquals("b1", analysisService.awaitStarted())

            blockService.emit(block("b2"))
            blockService.emit(block("b3"))

            analysisService.completeOne()

            assertEquals("b3", analysisService.awaitStarted())
            assertEquals(listOf("b1", "b3"), analysisService.startedBlocks)
        } finally {
            pipeline.stop()
        }
    }

    private class TestAmbientSemanticBlockService : AmbientSemanticBlockService {
        private val mutableBlocks = MutableSharedFlow<AmbientSemanticBlock>(extraBufferCapacity = 8)
        override val blocks: Flow<AmbientSemanticBlock> = mutableBlocks

        override fun snapshot(): List<AmbientSemanticBlock> = emptyList()

        override suspend fun start() = Unit

        override suspend fun stop() = Unit

        override fun clear() = Unit

        suspend fun awaitSubscriber() {
            withTimeout(1_000L) {
                mutableBlocks.subscriptionCount.first { count -> count > 0 }
            }
        }

        suspend fun emit(block: AmbientSemanticBlock) {
            mutableBlocks.emit(block)
        }
    }

    private class BlockingAmbientAnalysisService : AmbientAnalysisService {
        private val started = Channel<String>(Channel.UNLIMITED)
        private val completions = Channel<Unit>(Channel.UNLIMITED)
        val startedBlocks = mutableListOf<String>()
        override val analyses = MutableSharedFlow<AmbientAnalysisResult>()
        override val taskCandidates = MutableSharedFlow<AmbientTaskCandidate>()

        override suspend fun analyzeBlock(block: AmbientSemanticBlock): AmbientAnalysisResult {
            startedBlocks += block.id
            started.send(block.id)
            completions.receive()
            return AmbientAnalysisResult(
                blockId = block.id,
                blockSummary = null,
                extractedStatements = emptyList(),
                taskCandidates = emptyList(),
            )
        }

        suspend fun awaitStarted(): String = withTimeout(1_000L) {
            started.receive()
        }

        fun completeOne() {
            completions.trySend(Unit)
        }

        override suspend fun stop() = Unit

        override fun recentAnalyses(): List<AmbientAnalysisResult> = emptyList()

        override fun clear() = Unit
    }

    private fun block(id: String): AmbientSemanticBlock = AmbientSemanticBlock(
        id = id,
        text = "speech $id",
        eventIds = listOf("e-$id"),
        startedAtMs = 100,
        endedAtMs = 200,
        closedAtMs = 300,
        closeReason = AmbientBlockCloseReason.PAUSE,
        speakerRole = AmbientSpeakerRole.PROBABLY_USER,
        addressedness = AmbientAddressedness.IMPLICIT_USER_INTENT,
    )
}
