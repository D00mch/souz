package ru.souz.backend.settings.repository

import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.model.PersonalitySnapshot

interface UserSettingsRepository {
    suspend fun get(userId: String): UserSettings?
    suspend fun save(settings: UserSettings): UserSettings
    suspend fun personality(userId: String): PersonalitySnapshot = get(userId)?.let {
        PersonalitySnapshot(it.personalityPrompt, it.personalityRevision)
    } ?: PersonalitySnapshot()
    suspend fun compareAndSetPersonality(userId: String, expectedRevision: Long, prompt: String?): PersonalitySnapshot?
}
