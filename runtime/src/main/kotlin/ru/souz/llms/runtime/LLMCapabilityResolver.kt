package ru.souz.llms.runtime

import java.io.File
import java.util.Base64
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.anthropic.AnthropicChatAPI
import ru.souz.llms.local.LocalChatAPI
import ru.souz.llms.openai.OpenAIChatAPI
import ru.souz.llms.openai.OpenAIImageGenerationGateway

class LLMCapabilityResolver(
    private val settingsProvider: SettingsProvider,
    private val anthropicApi: AnthropicChatAPI,
    private val openAiApi: OpenAIChatAPI,
    private val localApi: LocalChatAPI,
    private val openAiImageGenerationGateway: OpenAIImageGenerationGateway,
) : VisionGateway, ImageGenerationGateway {

    override suspend fun analyze(input: VisionInput): String = when (settingsProvider.gigaModel.provider) {
        LlmProvider.LOCAL -> localApi.requireAssistantText(
            LLMRequest.Chat(
                model = settingsProvider.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = input.question,
                        attachments = listOf(input.imagePath),
                    ),
                ),
            )
        )

        LlmProvider.OPENAI -> openAiApi.requireAssistantText(
            LLMRequest.Chat(
                model = settingsProvider.gigaModel.alias,
                messages = listOf(
                    LLMRequest.Message(
                        role = LLMMessageRole.user,
                        content = input.question,
                        attachments = listOf(input.toDataUrl()),
                    ),
                ),
            )
        )

        LlmProvider.ANTHROPIC -> {
            val uploaded = anthropicApi.uploadFile(File(input.imagePath))
            anthropicApi.requireAssistantText(
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
            )
        }

        else -> throw UnsupportedOperationException(
            "Image understanding is not supported by the current provider: ${settingsProvider.gigaModel.provider}",
        )
    }

    override suspend fun generate(input: ImageGenerationInput): GeneratedImage = when (settingsProvider.gigaModel.provider) {
        LlmProvider.OPENAI -> openAiImageGenerationGateway.generate(input)
        else -> throw UnsupportedOperationException(
            "Image generation is not supported by the current provider: ${settingsProvider.gigaModel.provider}",
        )
    }

    private fun VisionInput.toDataUrl(): String =
        "data:$mimeType;base64,${Base64.getEncoder().encodeToString(imageBytes)}"
}

private suspend fun OpenAIChatAPI.requireAssistantText(body: LLMRequest.Chat): String =
    message(body).requireAssistantText("OpenAI image understanding failed")

private suspend fun AnthropicChatAPI.requireAssistantText(body: LLMRequest.Chat): String =
    message(body).requireAssistantText("Anthropic image understanding failed")

private suspend fun LocalChatAPI.requireAssistantText(body: LLMRequest.Chat): String =
    message(body).requireAssistantText("Local image understanding failed")

private fun LLMResponse.Chat.requireAssistantText(prefix: String): String = when (this) {
    is LLMResponse.Chat.Ok -> choices.firstOrNull()?.message?.content
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("$prefix: empty response")

    is LLMResponse.Chat.Error -> throw IllegalStateException("$prefix: $message")
}
