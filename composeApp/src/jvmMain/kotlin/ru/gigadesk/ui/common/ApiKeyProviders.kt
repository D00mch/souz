package ru.gigadesk.ui.common

enum class ApiKeyProvider(
    val title: String,
    val url: String,
    val description: String,
    val details: String,
) {
    AI_TUNNEL(
        title = "AiTunnel",
        url = "https://aitunnel.ru/",
        description = "Единый ключ для популярных зарубежных моделей.",
        details = "Доступны модели OpenAI, Anthropic и Grok.",
    ),
    QWEN(
        title = "Alibaba Model Studio (Qwen)",
        url = "https://modelstudio.console.alibabacloud.com/",
        description = "Ключи и управление для моделей семейства Qwen.",
        details = "Подходит для чата и генерации на моделях Qwen.",
    ),
    SBER(
        title = "Sber Studio (GigaChat + Speech)",
        url = "https://developers.sber.ru/studio/workspaces",
        description = "Кабинет для ключей GigaChat и SaluteSpeech.",
        details = "Если планируете голосовые команды, здесь же получите ключ для Speech API.",
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
