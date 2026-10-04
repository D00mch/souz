package ru.souz.skilloauth.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InMemorySkillOAuthCredentialRepository : SkillOAuthCredentialRepository {
    private val credentials = mutableMapOf<Pair<String, String>, SkillOAuthCredential>()
    private val mutex = Mutex()

    override suspend fun find(userId: String, provider: String): SkillOAuthCredential? = mutex.withLock {
        credentials[userId to provider]
    }

    override suspend fun upsert(credential: SkillOAuthCredential): SkillOAuthCredential? = mutex.withLock {
        val key = credential.userId to credential.provider
        val existing = credentials[key]
        val accepted = existing == null ||
            credential.generation > existing.generation ||
            (credential.generation == existing.generation && credential.revision == existing.revision)
        if (!accepted) {
            return@withLock null
        }
        val stored = credential.copy(revision = (existing?.revision ?: -1) + 1)
        credentials[key] = stored
        stored
    }

    override suspend fun delete(userId: String, provider: String): Unit = mutex.withLock {
        credentials.remove(userId to provider)
    }
}
