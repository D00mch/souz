package ru.gigadesk.ui.common

import org.jetbrains.compose.resources.StringResource
import ru.gigadesk.edition.BuildEdition
import ru.gigadesk.edition.BuildEditionConfig
import gigadesk.composeapp.generated.resources.Res
import gigadesk.composeapp.generated.resources.*

enum class ApiKeyProvider(
    val title: StringResource,
    val url: String,
    val description: StringResource,
    val details: StringResource,
) {
    AI_TUNNEL(
        title = Res.string.provider_aitunnel_title,
        url = "https://aitunnel.ru/",
        description = Res.string.provider_aitunnel_desc,
        details = Res.string.provider_aitunnel_details,
    ),
    QWEN(
        title = Res.string.provider_qwen_title,
        url = "https://modelstudio.console.alibabacloud.com/",
        description = Res.string.provider_qwen_desc,
        details = Res.string.provider_qwen_details,
    ),
    ANTHROPIC(
        url = "https://console.anthropic.com/settings/keys",
        title = Res.string.provider_anthropic_title,
        description = Res.string.provider_anthropic_desc,
        details = Res.string.provider_anthropic_details,
    ),
    OPENAI(
        url = "https://platform.openai.com/api-keys",
        title = Res.string.provider_openai_title,
        description = Res.string.provider_openai_desc,
        details = Res.string.provider_openai_details,
    ),
    SBER(
        title = Res.string.provider_sber_title,
        url = "https://developers.sber.ru/studio/workspaces",
        description = Res.string.provider_sber_desc,
        details = Res.string.provider_sber_details,
    )
}

enum class ApiKeyField {
    GIGA_CHAT,
    QWEN_CHAT,
    AI_TUNNEL,
    ANTHROPIC,
    OPENAI,
    SALUTE_SPEECH,
}

object ApiKeysBuildProfile {
    val availableFields: Set<ApiKeyField> = when (BuildEditionConfig.current) {
        BuildEdition.RU -> setOf(
            ApiKeyField.GIGA_CHAT,
            ApiKeyField.QWEN_CHAT,
            ApiKeyField.AI_TUNNEL,
            ApiKeyField.SALUTE_SPEECH,
        )

        BuildEdition.EN -> setOf(
            ApiKeyField.QWEN_CHAT,
            ApiKeyField.ANTHROPIC,
            ApiKeyField.OPENAI,
        )
    }

    val providers: List<ApiKeyProvider> = when (BuildEditionConfig.current) {
        BuildEdition.RU -> listOf(
            ApiKeyProvider.SBER,
            ApiKeyProvider.QWEN,
            ApiKeyProvider.AI_TUNNEL,
        )

        BuildEdition.EN -> listOf(
            ApiKeyProvider.OPENAI,
            ApiKeyProvider.QWEN,
            ApiKeyProvider.ANTHROPIC,
        )
    }

    fun hasField(field: ApiKeyField): Boolean = field in availableFields
}

fun configuredApiKeysCount(
    gigaChatKey: String,
    qwenChatKey: String,
    aiTunnelKey: String,
    anthropicKey: String,
    openaiKey: String,
    saluteSpeechKey: String,
): Int = mapOf(
    ApiKeyField.GIGA_CHAT to gigaChatKey,
    ApiKeyField.QWEN_CHAT to qwenChatKey,
    ApiKeyField.AI_TUNNEL to aiTunnelKey,
    ApiKeyField.ANTHROPIC to anthropicKey,
    ApiKeyField.OPENAI to openaiKey,
    ApiKeyField.SALUTE_SPEECH to saluteSpeechKey,
).count { (field, key) ->
    ApiKeysBuildProfile.hasField(field) && key.isNotBlank()
}

fun hasAnyConfiguredApiKey(
    gigaChatKey: String,
    qwenChatKey: String,
    aiTunnelKey: String,
    anthropicKey: String,
    openaiKey: String,
    saluteSpeechKey: String,
): Boolean = configuredApiKeysCount(gigaChatKey, qwenChatKey, aiTunnelKey, anthropicKey, openaiKey, saluteSpeechKey) > 0
