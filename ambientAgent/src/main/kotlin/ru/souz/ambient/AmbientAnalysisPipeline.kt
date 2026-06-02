package ru.souz.ambient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AmbientAnalysisPipeline {
    suspend fun start()
    suspend fun stop()
}

class DefaultAmbientAnalysisPipeline(
    private val blockService: AmbientSemanticBlockService,
    private val analysisService: AmbientAnalysisService,
    private val scope: CoroutineScope,
) : AmbientAnalysisPipeline {
    private val mutex = Mutex()
    private var job: Job? = null

    override suspend fun start() {
        mutex.withLock {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                blockService.blocks
                    .buffer(capacity = MAX_PENDING_BLOCKS, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    .collect { block ->
                        analysisService.analyzeBlock(block)
                    }
            }
            blockService.start()
        }
    }

    override suspend fun stop() {
        val currentJob = mutex.withLock { job.also { job = null } }
        currentJob?.cancelAndJoin()
        blockService.stop()
        analysisService.stop()
    }

    private companion object {
        const val MAX_PENDING_BLOCKS = 3
    }
}
