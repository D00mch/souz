package ru.souz.llms.openai

import java.nio.file.Files
import java.util.Base64
import ru.souz.db.SettingsProvider
import ru.souz.llms.runtime.DEFAULT_MAX_VISION_IMAGE_BYTES
import ru.souz.llms.runtime.VisionGateway
import ru.souz.llms.runtime.VisionInput
import ru.souz.llms.runtime.requestVisionText

class OpenAIVisionGateway(
    private val settingsProvider: SettingsProvider,
    private val openAiApi: OpenAIChatAPI,
    private val maxImageBytes: Long = DEFAULT_MAX_VISION_IMAGE_BYTES,
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String {
        requireSizeWithinLimit(input)
        val dataUrl = "data:${input.mimeType};base64,${
            Base64.getEncoder().encodeToString(Files.readAllBytes(input.imagePath))
        }"
        return requestVisionText(
            settingsProvider = settingsProvider,
            input = input,
            attachment = dataUrl,
            failurePrefix = "OpenAI image understanding failed",
            chatRequest = openAiApi::message,
        )
    }

    private fun requireSizeWithinLimit(input: VisionInput) {
        if (input.sizeBytes > maxImageBytes) {
            throw IllegalArgumentException(
                "OpenAI image input is too large: ${input.sizeBytes} bytes exceeds limit of $maxImageBytes bytes",
            )
        }
    }
}
