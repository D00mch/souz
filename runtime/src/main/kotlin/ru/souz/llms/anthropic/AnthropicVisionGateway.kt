package ru.souz.llms.anthropic

import java.io.File
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requireAssistantText

class AnthropicVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val anthropicApi: AnthropicChatAPI,
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String {
        val uploaded = anthropicApi.uploadFile(File(input.imagePath.toString()))
        return anthropicApi.message(
            LLMRequest.Chat(
                model = settingsProvider.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = input.question,
                        attachments = listOf(uploaded.id),
                    ),
                ),
            )
        ).requireAssistantText("Anthropic image understanding failed")
    }
}
