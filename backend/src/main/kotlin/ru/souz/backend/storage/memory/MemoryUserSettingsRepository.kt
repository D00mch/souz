package ru.souz.backend.storage.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository

class MemoryUserSettingsRepository : UserSettingsRepository {
    private val mutex = Mutex()
    private val settings = LinkedHashMap<String, UserSettings>()

    override suspend fun get(userId: String): UserSettings? = mutex.withLock {
        settings[userId]
    }

    override suspend fun save(settings: UserSettings): UserSettings = mutex.withLock {
        this.settings[settings.userId] = settings
        settings
    }
}
