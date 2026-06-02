package ru.souz.ambient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
    private val inactivityFlushMs: Long = DEFAULT_INACTIVITY_FLUSH_MS,
) : AmbientSemanticBlockService {
    private val mutex = Mutex()
    private val closedBlocks = ArrayDeque<AmbientSemanticBlock>()
    private val _blocks = MutableSharedFlow<AmbientSemanticBlock>(extraBufferCapacity = 16)
    private var job: Job? = null
    private var inactivityJob: Job? = null

    override val blocks: Flow<AmbientSemanticBlock> = _blocks.asSharedFlow()

    override fun snapshot(): List<AmbientSemanticBlock> = closedBlocks.toList()

    override suspend fun start() {
        mutex.withLock {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                transcriptEvents.collect { event ->
                    val closed = mutex.withLock {
                        builder.accept(event).also {
                            if (event.isFinal && event.text.isNotBlank()) {
                                scheduleInactivityFlushLocked()
                            }
                        }
                    }
                    for (block in closed) {
                        publish(block)
                    }
                }
            }
        }
    }

    override suspend fun stop() {
        val (currentJob, currentInactivityJob) = mutex.withLock {
            val currentJob = job
            val currentInactivityJob = inactivityJob
            job = null
            inactivityJob = null
            currentJob to currentInactivityJob
        }
        currentJob?.cancelAndJoin()
        currentInactivityJob?.cancelAndJoin()
        val stoppedBlock = mutex.withLock {
            builder.flush(AmbientBlockCloseReason.STOPPED)
        }
        stoppedBlock?.let { publish(it) }
    }

    override fun clear() {
        closedBlocks.clear()
    }

    private fun scheduleInactivityFlushLocked() {
        inactivityJob?.cancel()
        if (inactivityFlushMs <= 0L) return

        inactivityJob = scope.launch {
            delay(inactivityFlushMs)
            val inactiveBlock = mutex.withLock {
                inactivityJob = null
                builder.flush(AmbientBlockCloseReason.PAUSE)
            }
            inactiveBlock?.let { publish(it) }
        }
    }

    private suspend fun publish(block: AmbientSemanticBlock) {
        mutex.withLock {
            closedBlocks.addLast(block)
            while (closedBlocks.size > MAX_SNAPSHOT_BLOCKS) {
                closedBlocks.removeFirst()
            }
            _blocks.tryEmit(block)
        }
    }

    private companion object {
        const val MAX_SNAPSHOT_BLOCKS = 100
        const val DEFAULT_INACTIVITY_FLUSH_MS = 3_000L
    }
}
