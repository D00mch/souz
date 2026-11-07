package ru.souz.llms.runtime

import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMResponse
import ru.souz.llms.exceedsContextBudget
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
    private val modelSelection: String? = null,
    private val chat: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
) : VisionGateway {
    override suspend fun analyze(input: VisionInput): String {
        val model = if (modelSelection == null) settings.gigaModel else findLLMModel(modelSelection)
        val provider = model?.provider ?: LlmProvider.OPENAI
        check(provider.supportsImageInput()) { "Image understanding is unavailable for $provider" }
        val request = LLMRequest.Chat(
            model = if (modelSelection == null) settings.executionModelId(requireNotNull(model)) else model?.alias ?: modelSelection,
            provider = provider,
            contextSize = settings.contextSize,
            messages = listOf(LLMRequest.Message(
                LLMMessageRole.user,
                input.question,
                attachments = input.images.map { it.forVision(provider) },
            )),
        )
        require(!request.exceedsContextBudget(settings.contextSize)) { "image_context_budget_exceeded" }
        return chat(request).requireAssistantText("Image understanding failed")
    }
}
