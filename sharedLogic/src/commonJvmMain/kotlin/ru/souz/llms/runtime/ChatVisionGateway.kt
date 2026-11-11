package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI

/** Uses the ordinary chat adapter and its host-owned transport for remote vision. */
class ChatVisionGateway(
    private val settings: SettingsProvider,
    private val chatApi: LLMChatAPI,
) : VisionGateway {
    override suspend fun analyze(input: VisionInput): String {
        check(settings.gigaModel.provider.supportsImageInput()) {
            "Image understanding is unavailable for ${settings.gigaModel.provider}"
        }
        return requestVisionText(settings, input, input.images.map { it.forVision() }, chatApi::message)
    }
}
