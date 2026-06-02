package ru.souz.ambient

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

data class AmbientSuggestion(
    val id: String,
    val candidate: AmbientTaskCandidate,
    val status: AmbientSuggestionStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val expiresAtMs: Long,
    val failureReason: String? = null,
)

enum class AmbientSuggestionStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    EXPIRED,
    EXECUTING,
    COMPLETED,
    FAILED,
}

data class AmbientSuggestionStoreConfig(
    val maxPendingSuggestions: Int = 3,
    val ttlMs: Long = 10_000L,
    val dedupeCooldownMs: Long = 2 * 60 * 1_000L,
)

interface AmbientSuggestionStore {
    val suggestions: StateFlow<List<AmbientSuggestion>>

    fun addCandidate(candidate: AmbientTaskCandidate)
    fun accept(id: String): AmbientSuggestion?
    fun reject(id: String)
    fun markExecuting(id: String)
    fun markCompleted(id: String)
    fun markFailed(id: String, reason: String?)
    fun expireOld()
    fun clear()
}

class InMemoryAmbientSuggestionStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: AmbientSuggestionStoreConfig = AmbientSuggestionStoreConfig(),
) : AmbientSuggestionStore {
    private val _suggestions = MutableStateFlow<List<AmbientSuggestion>>(emptyList())
    override val suggestions: StateFlow<List<AmbientSuggestion>> = _suggestions.asStateFlow()

    override fun addCandidate(candidate: AmbientTaskCandidate) {
        val now = clock()
        val normalizedTask = candidate.taskText.normalizedForDedupe()
        if (normalizedTask.isBlank()) return

        _suggestions.update { current ->
            val expired = current.expirePending(now)
            if (expired.any { it.id == candidate.id && it.status in ACTIVE_STATUSES }) return@update expired
            if (expired.any { it.isRecentDuplicateOf(normalizedTask, candidate.evidenceEventIds, now) }) {
                return@update expired
            }

            val suggestion = AmbientSuggestion(
                id = candidate.id,
                candidate = candidate,
                status = AmbientSuggestionStatus.PENDING,
                createdAtMs = now,
                updatedAtMs = now,
                expiresAtMs = now + config.ttlMs,
            )

            (expired + suggestion).trimPendingLimit()
        }
    }

    override fun accept(id: String): AmbientSuggestion? {
        var accepted: AmbientSuggestion? = null
        val now = clock()
        _suggestions.update { current ->
            current.map { suggestion ->
                if (suggestion.id == id && suggestion.status == AmbientSuggestionStatus.PENDING) {
                    suggestion.copy(status = AmbientSuggestionStatus.ACCEPTED, updatedAtMs = now).also {
                        accepted = it
                    }
                } else {
                    suggestion
                }
            }
        }
        return accepted
    }

    override fun reject(id: String) {
        updateStatus(id, AmbientSuggestionStatus.REJECTED)
    }

    override fun markExecuting(id: String) {
        updateStatus(id, AmbientSuggestionStatus.EXECUTING)
    }

    override fun markCompleted(id: String) {
        updateStatus(id, AmbientSuggestionStatus.COMPLETED)
    }

    override fun markFailed(id: String, reason: String?) {
        val now = clock()
        _suggestions.update { current ->
            current.map { suggestion ->
                if (suggestion.id == id) {
                    suggestion.copy(
                        status = AmbientSuggestionStatus.FAILED,
                        updatedAtMs = now,
                        failureReason = reason?.take(240),
                    )
                } else {
                    suggestion
                }
            }
        }
    }

    override fun expireOld() {
        val now = clock()
        _suggestions.update { current -> current.expirePending(now) }
    }

    override fun clear() {
        _suggestions.value = emptyList()
    }

    private fun updateStatus(id: String, status: AmbientSuggestionStatus) {
        val now = clock()
        _suggestions.update { current ->
            current.map { suggestion ->
                if (suggestion.id == id) {
                    suggestion.copy(status = status, updatedAtMs = now, failureReason = null)
                } else {
                    suggestion
                }
            }
        }
    }

    private fun List<AmbientSuggestion>.expirePending(now: Long): List<AmbientSuggestion> =
        map { suggestion ->
            if (suggestion.status == AmbientSuggestionStatus.PENDING && now >= suggestion.expiresAtMs) {
                suggestion.copy(status = AmbientSuggestionStatus.EXPIRED, updatedAtMs = now)
            } else {
                suggestion
            }
        }

    private fun List<AmbientSuggestion>.trimPendingLimit(): List<AmbientSuggestion> {
        val pending = filter { it.status == AmbientSuggestionStatus.PENDING }
        val overflow = pending.size - config.maxPendingSuggestions
        if (overflow <= 0) return this

        val dropIds = pending
            .sortedBy { it.createdAtMs }
            .take(overflow)
            .mapTo(mutableSetOf()) { it.id }
        return filterNot { it.id in dropIds && it.status == AmbientSuggestionStatus.PENDING }
    }

    private fun AmbientSuggestion.isRecentDuplicateOf(
        normalizedTask: String,
        evidenceEventIds: List<String>,
        now: Long,
    ): Boolean =
        now - createdAtMs <= config.dedupeCooldownMs &&
            candidate.taskText.normalizedForDedupe() == normalizedTask &&
            candidate.evidenceEventIds == evidenceEventIds

    private fun String.normalizedForDedupe(): String =
        lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")

    private companion object {
        val ACTIVE_STATUSES = setOf(
            AmbientSuggestionStatus.PENDING,
            AmbientSuggestionStatus.ACCEPTED,
            AmbientSuggestionStatus.EXECUTING,
        )
    }
}

class AmbientSuggestionController(
    private val store: AmbientSuggestionStore,
) {
    fun handleCandidate(candidate: AmbientTaskCandidate): Boolean {
        val normalizedTask = candidate.taskText.trim()
        if (normalizedTask.isBlank()) return false
        if (candidate.addressedness == AmbientAddressedness.BACKGROUND_OR_QUOTED) return false
        if (candidate.confidence < candidate.addressedness.threshold()) return false
        if (candidate.risk == AmbientTaskRisk.HIGH && !candidate.isDirectOrHighConfidence()) return false

        store.addCandidate(
            candidate.copy(
                title = candidate.title.trim().ifBlank { normalizedTask },
                taskText = normalizedTask,
                suggestionText = candidate.suggestionText.trim().ifBlank {
                    "Похоже, я могу помочь: ${candidate.title.trim().ifBlank { normalizedTask }}"
                },
                requiresConfirmation = true,
            )
        )
        return true
    }

    private fun AmbientAddressedness.threshold(): Double =
        when (this) {
            AmbientAddressedness.DIRECT_TO_SOUZ -> 0.65
            AmbientAddressedness.IMPLICIT_USER_INTENT -> 0.75
            AmbientAddressedness.AMBIENT_CONVERSATION,
            AmbientAddressedness.UNKNOWN -> 0.85
            AmbientAddressedness.BACKGROUND_OR_QUOTED -> 1.01
        }

    private fun AmbientTaskCandidate.isDirectOrHighConfidence(): Boolean =
        addressedness == AmbientAddressedness.DIRECT_TO_SOUZ || confidence >= 0.85
}

interface AmbientSuggestionPipeline {
    suspend fun start()
    suspend fun stop()
}

class DefaultAmbientSuggestionPipeline(
    private val candidateFlow: Flow<AmbientTaskCandidate>,
    private val controller: AmbientSuggestionController,
    private val scope: CoroutineScope,
) : AmbientSuggestionPipeline {
    private val mutex = Mutex()
    private var job: Job? = null

    override suspend fun start() {
        mutex.withLock {
            if (job?.isActive == true) return
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                candidateFlow.collect { candidate ->
                    controller.handleCandidate(candidate)
                }
            }
        }
    }

    override suspend fun stop() {
        val currentJob = mutex.withLock { job.also { job = null } }
        currentJob?.cancelAndJoin()
    }
}

fun AmbientSuggestionPipeline(
    candidateFlow: Flow<AmbientTaskCandidate>,
    controller: AmbientSuggestionController,
    scope: CoroutineScope,
): AmbientSuggestionPipeline = DefaultAmbientSuggestionPipeline(
    candidateFlow = candidateFlow,
    controller = controller,
    scope = scope,
)
