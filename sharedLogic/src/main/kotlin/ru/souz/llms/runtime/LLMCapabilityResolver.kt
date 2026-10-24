package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.openai.OpenAIVisionGateway

class LLMCapabilityResolver(
    private val settingsProvider: SettingsProvider,
    private val openAiGateway: OpenAIVisionGateway,
    private val anthropicGateway: AnthropicVisionGateway,
) : VisionGateway {
    override suspend fun analyze(input: VisionInput): String = when (settingsProvider.gigaModel.provider) {
        LlmProvider.OPENAI -> openAiGateway.analyze(input)
        LlmProvider.ANTHROPIC -> anthropicGateway.analyze(input)
        else -> throw UnsupportedOperationException(
            "Image understanding is not supported by the current provider: ${settingsProvider.gigaModel.provider}",
        )
    }
}
