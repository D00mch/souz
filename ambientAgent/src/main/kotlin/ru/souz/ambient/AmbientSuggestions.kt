package ru.souz.ambient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

data class AmbientSuggestion(
    val id: String,
    val candidate: AmbientTaskCandidate,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

data class AmbientSuggestionStoreConfig(
    val maxPendingSuggestions: Int = 3,
    val ttlMs: Long = 10_000L,
    val dedupeCooldownMs: Long = 2 * 60 * 1_000L,
)

interface AmbientSuggestionStore {
    val pending: StateFlow<List<AmbientSuggestion>>

    fun add(candidate: AmbientTaskCandidate)
    fun consume(id: String): AmbientSuggestion?
    fun dismiss(id: String)
    fun expireOld()
    fun clear()
}

class InMemoryAmbientSuggestionStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: AmbientSuggestionStoreConfig = AmbientSuggestionStoreConfig(),
) : AmbientSuggestionStore {
    private val _pending = MutableStateFlow<List<AmbientSuggestion>>(emptyList())
    override val pending: StateFlow<List<AmbientSuggestion>> = _pending.asStateFlow()
    private val recentFingerprints = ArrayDeque<RecentFingerprint>()

    override fun add(candidate: AmbientTaskCandidate) {
        val now = clock()
        val fingerprint = candidate.taskText.normalizedForDedupe()
        if (fingerprint.isBlank()) return
        pruneFingerprints(now)
        if (recentFingerprints.any { it.value == fingerprint }) return

        val suggestion = AmbientSuggestion(
            id = candidate.id,
            candidate = candidate.copy(taskText = candidate.taskText.trim()),
            createdAtMs = now,
            expiresAtMs = now + config.ttlMs,
        )
        recentFingerprints.addLast(RecentFingerprint(fingerprint, now))
        _pending.update { current ->
            (current.removeExpired(now) + suggestion)
                .takeLast(config.maxPendingSuggestions)
        }
    }

    override fun consume(id: String): AmbientSuggestion? {
        val now = clock()
        var consumed: AmbientSuggestion? = null
        _pending.update { current ->
            current.removeExpired(now).filterNot { suggestion ->
                val match = suggestion.id == id
                if (match) consumed = suggestion
                match
            }
        }
        return consumed
    }

    override fun dismiss(id: String) {
        _pending.update { current -> current.filterNot { it.id == id } }
    }

    override fun expireOld() {
        val now = clock()
        pruneFingerprints(now)
        _pending.update { current -> current.removeExpired(now) }
    }

    override fun clear() {
        _pending.value = emptyList()
        recentFingerprints.clear()
    }

    private fun List<AmbientSuggestion>.removeExpired(now: Long): List<AmbientSuggestion> =
        filter { it.expiresAtMs > now }

    private fun pruneFingerprints(now: Long) {
        val cutoff = now - config.dedupeCooldownMs
        while (recentFingerprints.isNotEmpty() && recentFingerprints.first().createdAtMs <= cutoff) {
            recentFingerprints.removeFirst()
        }
        while (recentFingerprints.size > MAX_RECENT_FINGERPRINTS) {
            recentFingerprints.removeFirst()
        }
    }

    private fun String.normalizedForDedupe(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class RecentFingerprint(
        val value: String,
        val createdAtMs: Long,
    )

    private companion object {
        const val MAX_RECENT_FINGERPRINTS = 32
    }
}
