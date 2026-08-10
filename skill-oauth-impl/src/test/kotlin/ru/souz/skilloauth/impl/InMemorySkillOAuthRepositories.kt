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

    override suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential = lock.withLock {
        val existing = credentials[credential.userId to credential.provider]
        val merged = credential.copy(
            grantedScopes = (existing?.grantedScopes.orEmpty() + credential.grantedScopes).distinct()
        )
        credentials[merged.userId to merged.provider] = merged
        merged
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
        val merged = pending.copy(
            requestedScopes = (existing?.requestedScopes.orEmpty() + pending.requestedScopes).distinct()
        )
        if (existing != null) this.pending.remove(existing.state)
        this.pending[merged.state] = merged
        merged
    }

    override suspend fun consume(state: String, now: Instant): SkillOAuthPendingState? {
        val found = pending.remove(state) ?: return null
        return if (found.expiresAt.isBefore(now)) null else found
    }
}
