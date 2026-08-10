package ru.souz.skilloauth.impl

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class InMemorySkillOAuthCredentialRepository : SkillOAuthCredentialRepository {
    private val credentials = ConcurrentHashMap<Pair<String, String>, SkillOAuthCredential>()
    private val lock = ReentrantLock()

    override suspend fun find(userId: String, provider: String): SkillOAuthCredential? =
        credentials[userId to provider]

    override suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential? = lock.withLock {
        val key = credential.userId to credential.provider
        val existing = credentials[key]
        if (existing != null && credential.generation < existing.generation) {
            return@withLock null
        }
        credentials[key] = credential
        credential
    }

    override suspend fun delete(userId: String, provider: String) {
        credentials.remove(userId to provider)
    }
}

internal class InMemorySkillOAuthPendingStateRepository : SkillOAuthPendingStateRepository {
    private val pending = ConcurrentHashMap<String, SkillOAuthPendingState>()
    private val lock = ReentrantLock()

    override suspend fun upsertSupersedingByUserAndProvider(
        pending: SkillOAuthPendingState,
    ): SkillOAuthPendingState = lock.withLock {
        val existing = this.pending.values.firstOrNull {
            it.userId == pending.userId && it.provider == pending.provider
        }
        if (existing != null) this.pending.remove(existing.state)
        this.pending[pending.state] = pending
        pending
    }

    override suspend fun consume(state: String, now: Instant): SkillOAuthPendingState? {
        val found = pending.remove(state) ?: return null
        return if (found.expiresAt.isBefore(now)) null else found
    }
}

internal class InMemorySkillOAuthRequestedScopesRepository : SkillOAuthRequestedScopesRepository {
    private data class Entry(val state: SkillOAuthRequestedScopesState, val updatedAt: Instant)

    private val entries = ConcurrentHashMap<Pair<String, String>, Entry>()
    private val lock = ReentrantLock()

    override suspend fun mergeAndBump(
        userId: String,
        provider: String,
        scopes: List<String>,
        now: Instant,
        activeSince: Instant,
    ): SkillOAuthRequestedScopesState = lock.withLock {
        val key = userId to provider
        val existing = entries[key]
        val baseScopes = if (existing == null || existing.updatedAt.isBefore(activeSince)) {
            emptyList()
        } else {
            existing.state.requestedScopes
        }
        val merged = SkillOAuthRequestedScopesState(
            requestedScopes = (baseScopes + scopes).distinct(),
            generation = (existing?.state?.generation ?: 0L) + 1,
        )
        entries[key] = Entry(merged, now)
        merged
    }
}
