package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.openai.OpenAIImageGenerationGateway

class CapabilityBasedImageGenerationGateway(
    private val settingsProvider: SettingsProvider,
    private val openAiGateway: OpenAIImageGenerationGateway,
) : ImageGenerationGateway {
    override suspend fun generate(input: ImageGenerationInput): GeneratedImage {
        if (settingsProvider.openaiKey.isNullOrBlank()) {
            throw UnsupportedOperationException(
                "Image generation is unavailable because OpenAI API key is not configured.",
            )
        }
        return openAiGateway.generate(input)
    }
}
