package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

internal suspend fun requestVisionText(
    settingsProvider: SettingsProvider,
    input: VisionInput,
    attachments: List<String>,
    chatRequest: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
): String {
    val model = settingsProvider.gigaModel
    return chatRequest(
        LLMRequest.Chat(
            model = settingsProvider.executionModelId(model),
            provider = model.provider,
            contextSize = settingsProvider.contextSize,
            messages = listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.user,
                    content = input.question,
                    attachments = attachments,
                )
            ),
        ),
    ).requireAssistantText("Image understanding failed")
}
