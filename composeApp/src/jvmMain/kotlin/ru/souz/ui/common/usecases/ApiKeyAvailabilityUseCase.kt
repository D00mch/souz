package ru.souz.ui.common.usecases

import ru.souz.giga.LlmBuildProfile
import ru.souz.giga.LlmProvider
import ru.souz.ui.common.ApiKeyField
import ru.souz.ui.common.ApiKeyProvider

data class ApiKeyValues(
    val gigaChatKey: String,
    val qwenChatKey: String,
    val aiTunnelKey: String,
    val anthropicKey: String,
    val openaiKey: String,
    val saluteSpeechKey: String,
)

data class ApiKeyAvailability(
    val fields: Set<ApiKeyField>,
    val providers: List<ApiKeyProvider>,
    val supportsVoiceRecognitionApiKeys: Boolean,
    val supportsLocalInference: Boolean,
)

class ApiKeyAvailabilityUseCase(
    private val llmBuildProfile: LlmBuildProfile,
) {
    fun availability(): ApiKeyAvailability {
        val availableFields = availableFields()
        return ApiKeyAvailability(
            fields = availableFields,
            providers = availableProviders(availableFields),
            supportsVoiceRecognitionApiKeys = supportsVoiceRecognitionApiKeys(availableFields),
            supportsLocalInference = LlmProvider.LOCAL in llmBuildProfile.availableProviders,
        )
    }

    fun configuredKeysCount(values: ApiKeyValues): Int {
        val availableFields = availableFields()
        return mapOf(
            ApiKeyField.GIGA_CHAT to values.gigaChatKey,
            ApiKeyField.QWEN_CHAT to values.qwenChatKey,
            ApiKeyField.AI_TUNNEL to values.aiTunnelKey,
            ApiKeyField.ANTHROPIC to values.anthropicKey,
            ApiKeyField.OPENAI to values.openaiKey,
            ApiKeyField.SALUTE_SPEECH to values.saluteSpeechKey,
        ).count { (field, key) ->
            field in availableFields && key.isNotBlank()
        }
    }

    fun hasAnyConfiguredKey(values: ApiKeyValues): Boolean = configuredKeysCount(values) > 0

    private fun availableFields(): Set<ApiKeyField> = buildSet {
        val providers = llmBuildProfile.availableProviders
        if (LlmProvider.GIGA in providers) {
            add(ApiKeyField.GIGA_CHAT)
            add(ApiKeyField.SALUTE_SPEECH)
        }
        if (LlmProvider.QWEN in providers) add(ApiKeyField.QWEN_CHAT)
        if (LlmProvider.AI_TUNNEL in providers) add(ApiKeyField.AI_TUNNEL)
        if (LlmProvider.ANTHROPIC in providers) add(ApiKeyField.ANTHROPIC)
        if (LlmProvider.OPENAI in providers) add(ApiKeyField.OPENAI)
    }

    private fun supportsVoiceRecognitionApiKeys(availableFields: Set<ApiKeyField>): Boolean =
        ApiKeyField.SALUTE_SPEECH in availableFields || ApiKeyField.OPENAI in availableFields

    private fun availableProviders(availableFields: Set<ApiKeyField>): List<ApiKeyProvider> = buildList {
        if (ApiKeyField.GIGA_CHAT in availableFields) add(ApiKeyProvider.SBER)
        if (ApiKeyField.OPENAI in availableFields) add(ApiKeyProvider.OPENAI)
        if (ApiKeyField.QWEN_CHAT in availableFields) add(ApiKeyProvider.QWEN)
        if (ApiKeyField.AI_TUNNEL in availableFields) add(ApiKeyProvider.AI_TUNNEL)
        if (ApiKeyField.ANTHROPIC in availableFields) add(ApiKeyProvider.ANTHROPIC)
    }
}
