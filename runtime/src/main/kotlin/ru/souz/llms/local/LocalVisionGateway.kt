package ru.souz.llms.local

import ru.souz.db.SettingsProvider
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requestVisionText

class LocalVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val localApi: LocalChatAPI,
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String =
        requestVisionText(
            settingsProvider = settingsProvider,
            input = input,
            attachment = input.imagePath.toString(),
            failurePrefix = "Local image understanding failed",
            chatRequest = localApi::message,
        )
}
