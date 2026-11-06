package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LlmProvider
import ru.souz.llms.findLLMModel

fun LlmProvider?.supportsImageInput(): Boolean = when (this) {
    LlmProvider.OPENAI, LlmProvider.CODEX, LlmProvider.ANTHROPIC -> true
    else -> false
}

/** Uses the normal model route, credentials and accounting; adapters serialize the image blocks. */
class ChatVisionGateway(
    private val settings: SettingsProvider,
    private val api: LLMChatAPI,
    private val modelSelection: String? = null,
) : VisionGateway {
    override suspend fun analyze(input: VisionInput): String {
        val model = if (modelSelection == null) settings.gigaModel else findLLMModel(modelSelection)
        val provider = model?.provider ?: LlmProvider.OPENAI
        check(provider.supportsImageInput()) { "Image understanding is unavailable for $provider" }
        return api.message(
            LLMRequest.Chat(
                model = if (modelSelection == null) settings.executionModelId(requireNotNull(model)) else model?.alias ?: modelSelection,
                provider = provider,
                messages = listOf(
                    LLMRequest.Message(
                        LLMMessageRole.user,
                        input.question,
                        attachments = input.images.map { it.forVision() },
                    ),
                ),
            ),
        ).requireAssistantText("Image understanding failed")
    }
}
