package ru.souz.ambient

import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

data class AmbientAnalysisServiceConfig(
    val recentBlockLimit: Int = 5,
    val recentAnalysisLimit: Int = 20,
    val analysisTimeoutMs: Long = 10_000L,
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
            val context = mutex.withLock { recentBlocks.toList() }
                .takeLast(config.recentBlockLimit)
            val capabilities = capabilityProvider.capabilities()

            analysisLock.withLock {
                val result = try {
                    runAnalyzer(block, context, capabilities)
                } catch (error: TimeoutCancellationException) {
                    logger.warn("Ambient analysis timed out for block={} after {} ms", block.id, config.analysisTimeoutMs)
                    AmbientAnalysisResult(
                        blockId = block.id,
                        blockSummary = "ambient analysis timed out",
                        extractedStatements = emptyList(),
                        taskCandidates = emptyList(),
                        rawModelOutputPreview = error.message?.take(240),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    AmbientAnalysisResult(
                        blockId = block.id,
                        blockSummary = "ambient analysis unavailable",
                        extractedStatements = emptyList(),
                        taskCandidates = emptyList(),
                        rawModelOutputPreview = error.message?.take(240),
                    )
                }
                val filtered = result.withFilteredCandidates()
                val rejectionSummary = result.taskCandidates.rejectionSummary()
                record(block, filtered)
                _analyses.emit(filtered)
                filtered.taskCandidates
                    .forEach { _taskCandidates.emit(it) }
                logger.debug(
                    "Ambient analysis completed for block={} addressedness={} chars={} modelCandidates={} emittedCandidates={} rejected={} rawPreviewChars={}",
                    block.id,
                    block.addressedness,
                    block.text.length,
                    result.taskCandidates.size,
                    filtered.taskCandidates.size,
                    rejectionSummary,
                    result.rawModelOutputPreview?.length ?: 0,
                )
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

    private suspend fun runAnalyzer(
        block: AmbientSemanticBlock,
        context: List<AmbientSemanticBlock>,
        capabilities: List<AmbientCapability>,
    ): AmbientAnalysisResult {
        if (config.analysisTimeoutMs <= 0L) {
            return analyzer.analyze(block, context, capabilities)
        }
        return withTimeout(config.analysisTimeoutMs) {
            analyzer.analyze(block, context, capabilities)
        }
    }

    private fun AmbientAnalysisResult.withFilteredCandidates(): AmbientAnalysisResult =
        copy(taskCandidates = taskCandidates.filter { it.shouldEmit() }.distinctBy { it.id })

    private fun List<AmbientTaskCandidate>.rejectionSummary(): String {
        val rejected = mapNotNull { it.rejectionReason() }
        if (rejected.isEmpty()) return "none"
        return rejected
            .groupingBy { it }
            .eachCount()
            .entries
            .joinToString(",") { (reason, count) -> "$reason:$count" }
    }

    private fun AmbientTaskCandidate.rejectionReason(): String? {
        if (addressedness == AmbientAddressedness.BACKGROUND_OR_QUOTED) return "background"
        val threshold = when (addressedness) {
            AmbientAddressedness.DIRECT_TO_SOUZ -> 0.65
            AmbientAddressedness.IMPLICIT_USER_INTENT -> 0.75
            AmbientAddressedness.AMBIENT_CONVERSATION,
            AmbientAddressedness.UNKNOWN -> 0.85
            AmbientAddressedness.BACKGROUND_OR_QUOTED -> 1.01
        }
        return if (confidence >= threshold) null else "low_confidence"
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

    private companion object {
        val logger = LoggerFactory.getLogger(DefaultAmbientAnalysisService::class.java)
    }
}
