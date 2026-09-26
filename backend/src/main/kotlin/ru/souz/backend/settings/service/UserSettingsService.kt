package ru.souz.backend.settings.service

import java.time.Instant
import ru.souz.backend.common.BackendLlmSupport
import ru.souz.backend.http.invalidV1Request
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.llms.LlmProvider

class UserSettingsService(
    private val userSettingsRepository: UserSettingsRepository,
    private val effectiveSettingsResolver: EffectiveSettingsResolver,
) {
    suspend fun get(userId: String): EffectiveUserSettings =
        effectiveSettingsResolver.resolve(userId)

    suspend fun patch(
        userId: String,
        overrides: UserSettingsOverrides,
    ): EffectiveUserSettings {
        if (overrides.defaultModel?.provider == LlmProvider.GIGA) {
            throw invalidV1Request(BackendLlmSupport.GIGA_UNSUPPORTED_MESSAGE)
        }
        if (
            overrides.defaultModel != null &&
            !effectiveSettingsResolver.isSelectableDefaultModel(userId, overrides.defaultModel)
        ) {
            throw invalidV1Request("defaultModel must be available to the current user.")
        }
        val now = Instant.now()
        val existing = userSettingsRepository.get(userId) ?: UserSettings(userId = userId, createdAt = now)
        userSettingsRepository.save(
            existing.copy(
                defaultModel = overrides.defaultModel ?: existing.defaultModel,
                contextSize = overrides.contextSize ?: existing.contextSize,
                temperature = overrides.temperature ?: existing.temperature,
                locale = overrides.locale ?: existing.locale,
                timeZone = overrides.timeZone ?: existing.timeZone,
                systemPrompt = overrides.systemPrompt ?: existing.systemPrompt,
                enabledTools = overrides.enabledTools ?: existing.enabledTools,
                showToolEvents = overrides.showToolEvents ?: existing.showToolEvents,
                streamingMessages = overrides.streamingMessages ?: existing.streamingMessages,
                interfaceLanguage = overrides.interfaceLanguage ?: existing.interfaceLanguage,
                requestTimeoutMillis = overrides.requestTimeoutMillis ?: existing.requestTimeoutMillis,
                useFewShotExamples = overrides.useFewShotExamples ?: existing.useFewShotExamples,
                updatedAt = now,
            )
        )
        return effectiveSettingsResolver.resolve(userId)
    }
}
