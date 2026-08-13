package ru.souz.llms.openai

import java.net.URI
import ru.souz.db.SettingsProvider

internal class OpenAIEndpoint private constructor(
    val baseUrl: String,
    val isOfficial: Boolean,
) {
    fun endpoint(path: String): String = "$baseUrl/${path.trimStart('/')}"

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"

        fun from(rawBaseUrl: String?): OpenAIEndpoint {
            val normalized = rawBaseUrl
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_BASE_URL
            val official = normalized.isOfficialOpenAiBaseUrl()
            return OpenAIEndpoint(
                baseUrl = if (official) DEFAULT_BASE_URL else normalized,
                isOfficial = official,
            )
        }
    }
}

internal fun SettingsProvider.openAIEndpoint(): OpenAIEndpoint =
    OpenAIEndpoint.from(openaiBaseUrl)

private fun String.isOfficialOpenAiBaseUrl(): Boolean {
    val uri = runCatching { URI(this) }.getOrNull() ?: return false
    val normalizedPath = uri.path.orEmpty().trimEnd('/')
    val effectivePort = if (uri.port == -1) DEFAULT_HTTPS_PORT else uri.port
    return uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("api.openai.com", ignoreCase = true) &&
        effectivePort == DEFAULT_HTTPS_PORT &&
        normalizedPath == "/v1" &&
        uri.userInfo == null &&
        uri.query == null &&
        uri.fragment == null
}

private const val DEFAULT_HTTPS_PORT = 443
