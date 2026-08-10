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
    private data class RequestedScopesEntry(
        val requestedScopes: List<String>,
        val generation: Long,
        val updatedAt: Instant,
    )

    private val pending = ConcurrentHashMap<String, SkillOAuthPendingState>()
    private val requestedScopes = ConcurrentHashMap<Pair<String, String>, RequestedScopesEntry>()
    private val lock = ReentrantLock()

    override suspend fun beginAuthorization(
        state: String,
        userId: String,
        skillId: String,
        provider: String,
        scopes: List<String>,
        now: Instant,
        activeSince: Instant,
        expiresAt: Instant,
    ): SkillOAuthPendingState = lock.withLock {
        val key = userId to provider
        val existing = requestedScopes[key]
        val baseScopes = if (existing == null || existing.updatedAt.isBefore(activeSince)) {
            emptyList()
        } else {
            existing.requestedScopes
        }
        val mergedScopes = (baseScopes + scopes).distinct()
        val nextGeneration = (existing?.generation ?: 0L) + 1
        requestedScopes[key] = RequestedScopesEntry(mergedScopes, nextGeneration, now)

        val existingPending = pending.values.firstOrNull { it.userId == userId && it.provider == provider }
        if (existingPending != null) pending.remove(existingPending.state)
        val created = SkillOAuthPendingState(
            state = state,
            userId = userId,
            skillId = skillId,
            provider = provider,
            requestedScopes = mergedScopes,
            generation = nextGeneration,
            expiresAt = expiresAt,
        )
        pending[state] = created
        created
    }

    override suspend fun consume(state: String, now: Instant): SkillOAuthPendingState? {
        val found = pending.remove(state) ?: return null
        return if (found.expiresAt.isBefore(now)) null else found
    }
}
