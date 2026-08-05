package ru.souz.llms.openai

import ru.souz.db.SettingsProvider

internal object OpenAIEndpointConfig {
    const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    fun endpoint(settingsProvider: SettingsProvider, path: String): String =
        "${baseUrl(settingsProvider)}/${path.trimStart('/')}"

    fun chatModelOverride(settingsProvider: SettingsProvider): String? =
        settingsProvider.openaiModel.nonBlank()

    private fun baseUrl(settingsProvider: SettingsProvider): String =
        settingsProvider.openaiBaseUrl.normalizedBaseUrl()
            ?: DEFAULT_BASE_URL

    private fun String?.nonBlank(): String? =
        this?.trim()?.takeIf { it.isNotBlank() }

    private fun String?.normalizedBaseUrl(): String? =
        this?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
}
