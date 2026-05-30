package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse

internal suspend fun requestVisionText(
    settingsProvider: SettingsProvider,
    input: VisionInput,
    attachment: String,
    failurePrefix: String,
    chatRequest: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
): String = chatRequest(
    LLMRequest.Chat(
        model = settingsProvider.gigaModel.alias,
        messages = listOf(
            LLMRequest.Message(
                role = LLMMessageRole.user,
                content = input.question,
                attachments = listOf(attachment),
            )
        ),
    )
).requireAssistantText(failurePrefix)
