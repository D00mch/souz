package ru.souz.backend.onboarding

import ru.souz.backend.http.BackendV1SettingsDto

data class OnboardingStateResponse(
    val required: Boolean,
    val completed: Boolean,
    val currentStep: String,
    val reasons: List<String>,
    val hasUsableModelAccess: Boolean,
    val availableServerManagedProviders: List<OnboardingServerManagedProvider>,
    val availableUserManagedProviders: List<OnboardingUserManagedProvider>,
    val currentSettings: BackendV1SettingsDto,
    val recommendedDefaultModel: String,
)

data class OnboardingServerManagedProvider(
    val provider: String,
    val models: List<String>,
    val recommended: Boolean,
)

data class OnboardingUserManagedProvider(
    val provider: String,
    val models: List<String>,
    val configured: Boolean,
    val recommended: Boolean,
)

data class OnboardingCompleteResponse(
    val completed: Boolean,
)
