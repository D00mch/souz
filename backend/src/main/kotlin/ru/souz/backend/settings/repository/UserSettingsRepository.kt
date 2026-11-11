package ru.souz.backend.settings.repository

import ru.souz.backend.settings.model.PersonalitySnapshot
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.model.PersonalityParameters

interface UserSettingsRepository {
    suspend fun get(userId: String): UserSettings?
    suspend fun save(settings: UserSettings): UserSettings

    suspend fun personality(userId: String): PersonalitySnapshot

    /** Returns null if another update won. Validation and delivery stay outside the transaction. */
    suspend fun compareAndSetPersonality(userId: String, previous: PersonalitySnapshot, parameters: PersonalityParameters): PersonalitySnapshot?
}
