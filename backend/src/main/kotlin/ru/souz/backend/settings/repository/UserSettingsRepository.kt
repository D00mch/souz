package ru.souz.backend.settings.repository

import ru.souz.backend.settings.model.UserSettings

interface UserSettingsRepository {
    suspend fun get(userId: String): UserSettings?
    suspend fun save(settings: UserSettings): UserSettings
}
