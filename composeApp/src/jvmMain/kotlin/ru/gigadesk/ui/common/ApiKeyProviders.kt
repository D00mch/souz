package ru.gigadesk.ui.common

import org.jetbrains.compose.resources.StringResource
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
    SBER(
        title = Res.string.provider_sber_title,
        url = "https://developers.sber.ru/studio/workspaces",
        description = Res.string.provider_sber_desc,
        details = Res.string.provider_sber_details,
    )
}

fun configuredApiKeysCount(
    gigaChatKey: String,
    qwenChatKey: String,
    aiTunnelKey: String,
    saluteSpeechKey: String,
): Int = listOf(gigaChatKey, qwenChatKey, aiTunnelKey, saluteSpeechKey).count { it.isNotBlank() }

fun hasAnyConfiguredApiKey(
    gigaChatKey: String,
    qwenChatKey: String,
    aiTunnelKey: String,
    saluteSpeechKey: String,
): Boolean = configuredApiKeysCount(gigaChatKey, qwenChatKey, aiTunnelKey, saluteSpeechKey) > 0
