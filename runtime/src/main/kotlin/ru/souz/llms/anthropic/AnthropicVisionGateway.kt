package ru.souz.llms.anthropic

import java.io.File
import ru.souz.db.SettingsProvider
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requestVisionText

class AnthropicVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val anthropicApi: AnthropicChatAPI,
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String {
        val uploaded = anthropicApi.uploadFile(File(input.imagePath.toString()))
        return requestVisionText(
            settingsProvider = settingsProvider,
            input = input,
            attachment = uploaded.id,
            failurePrefix = "Anthropic image understanding failed",
            chatRequest = anthropicApi::message,
        )
    }
}
