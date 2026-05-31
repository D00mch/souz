package ru.souz.ambient

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

data class AmbientAnalysisServiceConfig(
    val recentBlockLimit: Int = 5,
    val recentAnalysisLimit: Int = 20,
)

class DefaultAmbientAnalysisService(
    private val analyzer: AmbientBlockAnalyzer,
    private val capabilityProvider: AmbientCapabilityProvider,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val config: AmbientAnalysisServiceConfig = AmbientAnalysisServiceConfig(),
) : AmbientAnalysisService {
    private val mutex = Mutex()
    private val recentBlocks = ArrayDeque<AmbientSemanticBlock>()
    private val recentAnalyses = ArrayDeque<AmbientAnalysisResult>()
    private val activeJobs = mutableSetOf<Job>()
    private val analysisLock = Mutex()
    private val _analyses = MutableSharedFlow<AmbientAnalysisResult>(replay = 16, extraBufferCapacity = 16)
    private val _taskCandidates = MutableSharedFlow<AmbientTaskCandidate>(replay = 16, extraBufferCapacity = 16)

    override val analyses: Flow<AmbientAnalysisResult> = _analyses.asSharedFlow()
    override val taskCandidates: Flow<AmbientTaskCandidate> = _taskCandidates.asSharedFlow()

    override suspend fun analyzeBlock(block: AmbientSemanticBlock): AmbientAnalysisResult {
        val job = currentCoroutineContext()[Job]
        if (job != null) {
            mutex.withLock { activeJobs += job }
        }
        return try {
            analysisLock.withLock {
                val context = mutex.withLock { recentBlocks.toList() }
                    .takeLast(config.recentBlockLimit)
                val capabilities = capabilityProvider.capabilities()
                val result = analyzer.analyze(block, context, capabilities)
                val filtered = result.copy(
                    taskCandidates = result.taskCandidates.filter { it.shouldEmit() },
                )
                record(block, filtered)
                _analyses.emit(filtered)
                filtered.taskCandidates.forEach { _taskCandidates.emit(it) }
                filtered
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val result = AmbientAnalysisResult(
                blockId = block.id,
                blockSummary = "ambient analysis unavailable",
                extractedStatements = emptyList(),
                taskCandidates = emptyList(),
                rawModelOutputPreview = error.message?.take(240),
            )
            record(block, result)
            _analyses.emit(result)
            result
        } finally {
            if (job != null) {
                mutex.withLock { activeJobs -= job }
            }
        }
    }

    override suspend fun stop() {
        val jobs = mutex.withLock { activeJobs.toList() }
        jobs.forEach { it.cancel() }
    }

    override fun recentAnalyses(): List<AmbientAnalysisResult> = recentAnalyses.toList()

    override fun clear() {
        recentBlocks.clear()
        recentAnalyses.clear()
    }

    private suspend fun record(block: AmbientSemanticBlock, result: AmbientAnalysisResult) {
        mutex.withLock {
            recentBlocks.addLast(block)
            while (recentBlocks.size > config.recentBlockLimit) {
                recentBlocks.removeFirst()
            }
            recentAnalyses.addLast(result)
            while (recentAnalyses.size > config.recentAnalysisLimit) {
                recentAnalyses.removeFirst()
            }
        }
    }

    private fun AmbientTaskCandidate.shouldEmit(): Boolean {
        if (addressedness == AmbientAddressedness.BACKGROUND_OR_QUOTED) return false
        val threshold = when (addressedness) {
            AmbientAddressedness.DIRECT_TO_SOUZ -> 0.65
            AmbientAddressedness.IMPLICIT_USER_INTENT -> 0.75
            AmbientAddressedness.AMBIENT_CONVERSATION,
            AmbientAddressedness.UNKNOWN -> 0.85
            AmbientAddressedness.BACKGROUND_OR_QUOTED -> 1.01
        }
        return confidence >= threshold
    }
}
