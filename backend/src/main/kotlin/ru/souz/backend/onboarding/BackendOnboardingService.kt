package ru.souz.backend.onboarding

import java.time.Instant
import ru.souz.backend.bootstrap.BackendBootstrapService
import ru.souz.backend.bootstrap.BootstrapModelCapability
import ru.souz.backend.security.RequestIdentity
import ru.souz.backend.settings.model.UserSettings
import ru.souz.backend.settings.repository.UserSettingsRepository
import ru.souz.backend.settings.service.UserSettingsOverrides
import ru.souz.backend.settings.service.UserSettingsService
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

class BackendOnboardingService(
    private val bootstrapService: BackendBootstrapService,
    private val userSettingsRepository: UserSettingsRepository,
    private val userSettingsService: UserSettingsService,
) {
    suspend fun state(identity: RequestIdentity): OnboardingStateResponse {
        val bootstrap = bootstrapService.response(identity)
        val storedSettings = userSettingsRepository.get(identity.userId)
        val completed = storedSettings?.onboardingCompletedAt != null
        val recommendedDefaultModel = bootstrap.settings.defaultModel
        val recommendedProvider = recommendedDefaultModel.toProviderOrNull()?.name?.lowercase()
        val hasUsableModelAccess = bootstrap.capabilities.models.any { it.serverManagedKey || it.userManagedKey }
        val serverManagedProviders = bootstrap.capabilities.models
            .filter { it.serverManagedKey }
            .groupByProvider()
            .map { (provider, models) ->
                OnboardingServerManagedProvider(
                    provider = provider,
                    models = models,
                    recommended = provider == recommendedProvider,
                )
            }
        val userManagedProviders = LLMModel.entries
            .filter { it.provider != LlmProvider.LOCAL }
            .groupBy { it.provider.name.lowercase() }
            .toSortedMap()
            .map { (provider, models) ->
                val configured = bootstrap.capabilities.models.any {
                    it.provider == provider && it.userManagedKey
                }
                OnboardingUserManagedProvider(
                    provider = provider,
                    models = models.map { it.alias }.sorted(),
                    configured = configured,
                    recommended = provider == recommendedProvider && serverManagedProviders.none { it.recommended },
                )
            }

        return OnboardingStateResponse(
            required = !completed || !hasUsableModelAccess,
            completed = completed,
            currentStep = when {
                !hasUsableModelAccess -> STEP_PROVIDER
                !completed -> STEP_PREFERENCES
                else -> STEP_DONE
            },
            reasons = buildList {
                if (!hasUsableModelAccess) {
                    add(REASON_MISSING_MODEL_ACCESS)
                } else if (!completed) {
                    add(REASON_PREFERENCES_INCOMPLETE)
                }
            },
            hasUsableModelAccess = hasUsableModelAccess,
            availableServerManagedProviders = serverManagedProviders,
            availableUserManagedProviders = userManagedProviders,
            currentSettings = bootstrap.settings,
            recommendedDefaultModel = recommendedDefaultModel,
        )
    }

    suspend fun complete(
        identity: RequestIdentity,
        overrides: UserSettingsOverrides,
    ): OnboardingCompleteResponse {
        userSettingsService.patch(identity.userId, overrides)
        val current = userSettingsRepository.get(identity.userId) ?: error("Persisted user settings are unavailable.")
        val now = Instant.now()
        userSettingsRepository.save(
            current.copy(
                schemaVersion = UserSettings.CURRENT_SCHEMA_VERSION,
                onboardingCompletedAt = current.onboardingCompletedAt ?: now,
                updatedAt = now,
            )
        )
        return OnboardingCompleteResponse(completed = true)
    }

    private fun List<BootstrapModelCapability>.groupByProvider(): Map<String, List<String>> =
        groupBy { it.provider }
            .toSortedMap()
            .mapValues { (_, models) -> models.map { it.model }.sorted() }

    private fun String.toProviderOrNull(): LlmProvider? =
        LLMModel.entries.firstOrNull { it.alias == this }?.provider

    private companion object {
        const val STEP_PROVIDER = "provider"
        const val STEP_PREFERENCES = "preferences"
        const val STEP_DONE = "done"
        const val REASON_MISSING_MODEL_ACCESS = "missing_model_access"
        const val REASON_PREFERENCES_INCOMPLETE = "preferences_incomplete"
    }
}
