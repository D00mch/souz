package ru.souz.backend.storage.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository

class MemoryUserSettingsRepository(
    maxEntries: Int,
) : UserSettingsRepository {
    private val mutex = Mutex()
    private val settings = boundedLruMap<String, UserSettings>(maxEntries)

    constructor() : this(DEFAULT_MEMORY_REPOSITORY_MAX_ENTRIES)

    override suspend fun get(userId: String): UserSettings? = mutex.withLock {
        settings[userId]
    }

    override suspend fun save(settings: UserSettings): UserSettings = mutex.withLock {
        this.settings[settings.userId] = settings
        settings
    }
}
