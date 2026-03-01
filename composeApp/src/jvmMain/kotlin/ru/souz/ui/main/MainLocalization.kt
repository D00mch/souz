package ru.souz.ui.main

import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.onboarding_display_text
import souz.composeapp.generated.resources.onboarding_input_permission_request
import souz.composeapp.generated.resources.onboarding_input_permission_restart_failed
import souz.composeapp.generated.resources.onboarding_speech_text
import souz.composeapp.generated.resources.start_tips

internal object MainLocalization {
    private const val FALLBACK_ONBOARDING_DISPLAY_TEXT =
        "Привет! Меня зовут Souz. Я могу помогать с задачами на вашем компьютере."
    private const val FALLBACK_ONBOARDING_SPEECH_TEXT =
        "Привет! Меня зовут Союз. Я могу помогать с задачами на вашем компьютере."
    private const val FALLBACK_ONBOARDING_PERMISSION_REQUEST =
        "Пожалуйста, разрешите доступ к Input Monitoring и перезапустите приложение."
    private const val FALLBACK_ONBOARDING_PERMISSION_RESTART_FAILED =
        "Доступ к Input Monitoring получен. Пожалуйста, перезапустите приложение вручную."

    suspend fun startTips(): List<String> {
        if (!canReadComposeResources()) return emptyList()
        return runCatching { getStringArray(Res.array.start_tips) }.getOrElse { emptyList() }
    }

    suspend fun onboardingDisplayText(): String = getStringWithFallback(
        fallback = FALLBACK_ONBOARDING_DISPLAY_TEXT,
    ) {
        getString(Res.string.onboarding_display_text)
    }

    suspend fun onboardingSpeechText(): String = getStringWithFallback(
        fallback = FALLBACK_ONBOARDING_SPEECH_TEXT,
    ) {
        getString(Res.string.onboarding_speech_text)
    }

    suspend fun onboardingPermissionRequest(): String = getStringWithFallback(
        fallback = FALLBACK_ONBOARDING_PERMISSION_REQUEST,
    ) {
        getString(Res.string.onboarding_input_permission_request)
    }

    suspend fun onboardingPermissionRestartFailed(): String = getStringWithFallback(
        fallback = FALLBACK_ONBOARDING_PERMISSION_RESTART_FAILED,
    ) {
        getString(Res.string.onboarding_input_permission_restart_failed)
    }

    private suspend fun getStringWithFallback(fallback: String, loader: suspend () -> String): String {
        if (!canReadComposeResources()) return fallback
        return runCatching { loader() }.getOrElse { fallback }
    }

    private fun canReadComposeResources(): Boolean =
        runCatching { !java.awt.GraphicsEnvironment.isHeadless() }.getOrDefault(false)
}

