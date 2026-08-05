package ru.souz.skilloauth.impl

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class InMemorySkillOAuthCredentialRepository : SkillOAuthCredentialRepository {
    private val credentials = ConcurrentHashMap<Pair<String, String>, SkillOAuthCredential>()

    override suspend fun find(userId: String, provider: String): SkillOAuthCredential? =
        credentials[userId to provider]

    override suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential {
        credentials[credential.userId to credential.provider] = credential
        return credential
    }

    override suspend fun delete(userId: String, provider: String) {
        credentials.remove(userId to provider)
    }
}

internal class InMemorySkillOAuthPendingStateRepository : SkillOAuthPendingStateRepository {
    private val pending = ConcurrentHashMap<String, SkillOAuthPendingState>()

    override suspend fun create(pending: SkillOAuthPendingState) {
        this.pending[pending.state] = pending
    }

    override suspend fun consume(state: String, now: Instant): SkillOAuthPendingState? {
        val found = pending.remove(state) ?: return null
        return if (found.expiresAt.isBefore(now)) null else found
    }

    override suspend fun consumeActiveForUserAndProvider(
        userId: String,
        provider: String,
        now: Instant,
    ): List<SkillOAuthPendingState> {
        val matches = pending.values.filter {
            it.userId == userId && it.provider == provider && !it.expiresAt.isBefore(now)
        }
        matches.forEach { pending.remove(it.state) }
        return matches
    }
}
