package ru.souz.backend.storage.memory

import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.user.model.UserRecord
import ru.souz.backend.user.model.refreshLastSeenAt
import ru.souz.backend.user.repository.UserRepository

class MemoryUserRepository(
    maxEntries: Int,
) : UserRepository {
    private val mutex = Mutex()
    private val users = boundedLruMap<String, UserRecord>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun ensureUser(userId: String): UserRecord = mutex.withLock {
        val now = Instant.now()
        val existing = users[userId]
        val ensured = when (existing) {
            null -> UserRecord(
                id = userId,
                createdAt = now,
                lastSeenAt = now,
            )
            else -> existing.refreshLastSeenAt(now)
        }
        users[userId] = ensured
        ensured
    }

    suspend fun get(userId: String): UserRecord? = mutex.withLock {
        users[userId]
    }

    suspend fun count(): Int = mutex.withLock {
        users.size
    }
}
