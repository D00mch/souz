package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicVisionGateway
import ru.souz.llms.openai.OpenAIVisionGateway

class LLMCapabilityResolver(
    private val settingsProvider: SettingsProvider,
    private val openAiGateway: OpenAIVisionGateway,
    private val anthropicGateway: AnthropicVisionGateway,
    private val additionalGateways: Map<LlmProvider, VisionGateway> = emptyMap(),
) : VisionGateway {

    override suspend fun analyze(input: VisionInput): String = when (settingsProvider.gigaModel.provider) {
        LlmProvider.OPENAI -> openAiGateway.analyze(input)
        LlmProvider.ANTHROPIC -> anthropicGateway.analyze(input)
        in additionalGateways.keys -> additionalGateways.getValue(settingsProvider.gigaModel.provider).analyze(input)
        else -> throw UnsupportedOperationException(
            "Image understanding is not supported by the current provider: ${settingsProvider.gigaModel.provider}",
        )
    }
}
