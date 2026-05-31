package ru.souz.ambient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.service.speech.ambient.AmbientTranscriptEvent

interface AmbientSemanticBlockService {
    val blocks: Flow<AmbientSemanticBlock>
    fun snapshot(): List<AmbientSemanticBlock>
    suspend fun start()
    suspend fun stop()
    fun clear()
}

class DefaultAmbientSemanticBlockService(
    private val transcriptEvents: Flow<AmbientTranscriptEvent>,
    private val builder: SemanticBlockBuilder = SemanticBlockBuilder(),
    private val scope: CoroutineScope,
) : AmbientSemanticBlockService {
    private val mutex = Mutex()
    private val closedBlocks = ArrayDeque<AmbientSemanticBlock>()
    private val _blocks = MutableSharedFlow<AmbientSemanticBlock>(extraBufferCapacity = 16)
    private var job: Job? = null

    override val blocks: Flow<AmbientSemanticBlock> = _blocks.asSharedFlow()

    override fun snapshot(): List<AmbientSemanticBlock> = closedBlocks.toList()

    override suspend fun start() {
        mutex.withLock {
            if (job?.isActive == true) return
            job = scope.launch {
                transcriptEvents.collect { event ->
                    for (block in builder.accept(event)) {
                        publish(block)
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        val currentJob = mutex.withLock { job.also { job = null } }
        currentJob?.cancelAndJoin()
        builder.flush(AmbientBlockCloseReason.STOPPED)?.let { publish(it) }
    }

    override fun clear() {
        closedBlocks.clear()
    }

    private fun publish(block: AmbientSemanticBlock) {
        closedBlocks.addLast(block)
        while (closedBlocks.size > MAX_SNAPSHOT_BLOCKS) {
            closedBlocks.removeFirst()
        }
        _blocks.tryEmit(block)
    }

    private companion object {
        const val MAX_SNAPSHOT_BLOCKS = 100
    }
}
