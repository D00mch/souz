package ru.souz.ui.main

import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.onboarding_display_text
import souz.composeapp.generated.resources.onboarding_input_permission_request
import souz.composeapp.generated.resources.onboarding_input_permission_restart_failed
import souz.composeapp.generated.resources.onboarding_speech_text
import souz.composeapp.generated.resources.start_tips

interface MainLocalization {
    suspend fun startTips(): List<String>
    suspend fun onboardingDisplayText(): String
    suspend fun onboardingSpeechText(): String
    suspend fun onboardingPermissionRequest(): String
    suspend fun onboardingPermissionRestartFailed(): String
}

object ComposeMainLocalization : MainLocalization {
    override suspend fun startTips(): List<String> = getStringArray(Res.array.start_tips)

    override suspend fun onboardingDisplayText(): String = getString(Res.string.onboarding_display_text)

    override suspend fun onboardingSpeechText(): String = getString(Res.string.onboarding_speech_text)

    override suspend fun onboardingPermissionRequest(): String =
        getString(Res.string.onboarding_input_permission_request)

    override suspend fun onboardingPermissionRestartFailed(): String =
        getString(Res.string.onboarding_input_permission_restart_failed)
}
