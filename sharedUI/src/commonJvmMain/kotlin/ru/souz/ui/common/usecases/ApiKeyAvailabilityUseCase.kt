package ru.souz.ui.common.usecases

import ru.souz.db.REGION_EN
import ru.souz.db.SettingsProvider
import ru.souz.llms.LocalModelAvailability
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider

data class ApiKeyValues(
    val gigaChatKey: String,
    val qwenChatKey: String,
    val aiTunnelKey: String,
    val anthropicKey: String,
    val openaiKey: String,
    val polzaApiKey: String,
    val codexConnected: Boolean = false,
)

data class ApiKeyAvailability(
    val fields: Set<ApiKeyField>,
    val providers: List<ApiKeyProvider>,
    val supportsVoiceRecognitionApiKeys: Boolean,
    val supportsLocalInference: Boolean,
)

class ApiKeyAvailabilityUseCase(
    private val localModels: LocalModelAvailability,
    private val settingsProvider: SettingsProvider,
) {
    fun availability(): ApiKeyAvailability {
        val keys = availableKeys()
        return ApiKeyAvailability(
            fields = keys.keys,
            providers = keys.values.toList(),
            supportsVoiceRecognitionApiKeys = ApiKeyField.POLZA in keys || ApiKeyField.OPENAI in keys,
            supportsLocalInference = localModels.isProviderAvailable(),
        )
    }

    fun configuredKeysCount(values: ApiKeyValues, fields: Set<ApiKeyField>): Int {
        val codexValue = if (values.codexConnected) "connected" else ""
        return mapOf(
            ApiKeyField.GIGA_CHAT to values.gigaChatKey,
            ApiKeyField.QWEN_CHAT to values.qwenChatKey,
            ApiKeyField.AI_TUNNEL to values.aiTunnelKey,
            ApiKeyField.ANTHROPIC to values.anthropicKey,
            ApiKeyField.OPENAI to values.openaiKey,
            ApiKeyField.POLZA to values.polzaApiKey,
            ApiKeyField.CODEX to codexValue,
        ).count { (field, key) ->
            field in fields && key.isNotBlank()
        }
    }

    private fun availableKeys(): Map<ApiKeyField, ApiKeyProvider> = buildMap {
        put(ApiKeyField.GIGA_CHAT, ApiKeyProvider.SBER)
        if (settingsProvider.regionProfile != REGION_EN) put(ApiKeyField.POLZA, ApiKeyProvider.POLZA)
        put(ApiKeyField.OPENAI, ApiKeyProvider.OPENAI)
        put(ApiKeyField.QWEN_CHAT, ApiKeyProvider.QWEN)
        put(ApiKeyField.AI_TUNNEL, ApiKeyProvider.AI_TUNNEL)
        put(ApiKeyField.ANTHROPIC, ApiKeyProvider.ANTHROPIC)
        put(ApiKeyField.CODEX, ApiKeyProvider.CODEX)
    }
}
