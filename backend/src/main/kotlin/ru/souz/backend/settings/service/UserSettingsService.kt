package ru.souz.backend.settings.service

import java.time.Instant
import ru.souz.backend.settings.model.EffectiveUserSettings
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository

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
        val existing = userSettingsRepository.get(userId)
        val now = Instant.now()
        userSettingsRepository.save(
            UserSettings(
                userId = userId,
                defaultModel = overrides.defaultModel ?: existing?.defaultModel,
                contextSize = overrides.contextSize ?: existing?.contextSize,
                temperature = overrides.temperature ?: existing?.temperature,
                locale = overrides.locale ?: existing?.locale,
                timeZone = overrides.timeZone ?: existing?.timeZone,
                systemPrompt = overrides.systemPrompt ?: existing?.systemPrompt,
                enabledTools = overrides.enabledTools ?: existing?.enabledTools,
                showToolEvents = overrides.showToolEvents ?: existing?.showToolEvents,
                streamingMessages = overrides.streamingMessages ?: existing?.streamingMessages,
                toolPermissions = existing?.toolPermissions ?: emptyMap(),
                mcp = existing?.mcp ?: emptyMap(),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
        return effectiveSettingsResolver.resolve(userId)
    }
}
