package ru.souz.llms.local

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requireAssistantText

class LocalVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val localApi: LocalChatAPI,
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String =
        localApi.message(
            LLMRequest.Chat(
                model = settingsProvider.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = input.question,
                        attachments = listOf(input.imagePath.toString()),
                    ),
                ),
            )
        ).requireAssistantText("Local image understanding failed")
}
