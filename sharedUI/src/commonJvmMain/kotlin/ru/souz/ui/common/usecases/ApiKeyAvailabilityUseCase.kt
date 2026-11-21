package ru.souz.ui.common.usecases

import ru.souz.db.DesktopModelCatalog
import ru.souz.db.REGION_EN
import ru.souz.db.SettingsProvider
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
    private val modelCatalog: DesktopModelCatalog,
    private val settingsProvider: SettingsProvider,
) {
    fun availability(): ApiKeyAvailability {
        val availableFields = availableFields()
        return ApiKeyAvailability(
            fields = availableFields,
            providers = availableProviders(availableFields),
            supportsVoiceRecognitionApiKeys = supportsVoiceRecognitionApiKeys(availableFields),
            supportsLocalInference = modelCatalog.supportsLocalInference,
        )
    }

    fun configuredKeysCount(values: ApiKeyValues): Int {
        val availableFields = availableFields()
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
            field in availableFields && key.isNotBlank()
        }
    }

    fun hasAnyConfiguredKey(values: ApiKeyValues): Boolean = configuredKeysCount(values) > 0

    private fun availableFields(): Set<ApiKeyField> = buildSet {
        add(ApiKeyField.GIGA_CHAT)
        add(ApiKeyField.QWEN_CHAT)
        add(ApiKeyField.AI_TUNNEL)
        add(ApiKeyField.ANTHROPIC)
        add(ApiKeyField.OPENAI)
        add(ApiKeyField.CODEX)
        if (settingsProvider.regionProfile != REGION_EN) add(ApiKeyField.POLZA)
    }

    private fun supportsVoiceRecognitionApiKeys(availableFields: Set<ApiKeyField>): Boolean =
        ApiKeyField.POLZA in availableFields || ApiKeyField.OPENAI in availableFields

    private fun availableProviders(availableFields: Set<ApiKeyField>): List<ApiKeyProvider> = buildList {
        if (ApiKeyField.GIGA_CHAT in availableFields) add(ApiKeyProvider.SBER)
        if (ApiKeyField.POLZA in availableFields) add(ApiKeyProvider.POLZA)
        if (ApiKeyField.OPENAI in availableFields) add(ApiKeyProvider.OPENAI)
        if (ApiKeyField.QWEN_CHAT in availableFields) add(ApiKeyProvider.QWEN)
        if (ApiKeyField.AI_TUNNEL in availableFields) add(ApiKeyProvider.AI_TUNNEL)
        if (ApiKeyField.ANTHROPIC in availableFields) add(ApiKeyProvider.ANTHROPIC)
        if (ApiKeyField.CODEX in availableFields) add(ApiKeyProvider.CODEX)
    }
}
