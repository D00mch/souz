package ru.souz.ui.common

import org.jetbrains.compose.resources.StringResource
import souz.composeapp.generated.resources.Res
import souz.composeapp.generated.resources.*

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
