package ru.souz.ambient

import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMChatAPI

class LocalChatAmbientLocalLlm(
    private val localChatAPI: LLMChatAPI,
    private val modelProvider: () -> LLMModel = { LLMModel.LocalGemma4_E4B_It },
) : AmbientLocalLlm {
    override suspend fun completeJson(systemPrompt: String, userPrompt: String): String {
        val model = modelProvider()
        val response = localChatAPI.message(
            LLMRequest.Chat(
                model = model.alias,
                messages = listOf(
                    LLMRequest.Message(role = LLMMessageRole.system, content = systemPrompt),
                    LLMRequest.Message(role = LLMMessageRole.user, content = userPrompt),
                ),
                functionCall = "none",
                functions = emptyList(),
                temperature = 0.1f,
                stream = false,
                maxTokens = AMBIENT_JSON_MAX_TOKENS,
            )
        )
        return when (response) {
            is LLMResponse.Chat.Ok -> response.choices.firstOrNull()?.message?.content.orEmpty()
            is LLMResponse.Chat.Error -> throw IllegalStateException(response.message)
        }
    }

    private companion object {
        const val AMBIENT_JSON_MAX_TOKENS = 256
    }
}
