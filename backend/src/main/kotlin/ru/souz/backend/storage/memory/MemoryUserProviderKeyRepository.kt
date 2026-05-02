package ru.souz.backend.storage.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.keys.model.UserProviderKey
import ru.souz.backend.keys.repository.UserProviderKeyRepository
import ru.souz.llms.LlmProvider

class MemoryUserProviderKeyRepository(
    maxEntries: Int,
) : UserProviderKeyRepository {
    private val mutex = Mutex()
    private val keys = boundedLruMap<Pair<String, LlmProvider>, UserProviderKey>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun get(
        userId: String,
        provider: LlmProvider,
    ): UserProviderKey? = mutex.withLock {
        keys[userId to provider]
    }

    override suspend fun list(userId: String): List<UserProviderKey> = mutex.withLock {
        keys.values.filter { it.userId == userId }.sortedBy { it.provider.name }
    }

    override suspend fun save(key: UserProviderKey): UserProviderKey = mutex.withLock {
        keys[key.userId to key.provider] = key
        key
    }

    override suspend fun delete(
        userId: String,
        provider: LlmProvider,
    ): Boolean = mutex.withLock {
        keys.remove(userId to provider) != null
    }
}
