package ru.souz.backend

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.souz.llms.DEFAULT_MAX_TOKENS
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.TokenLogging

data class BackendChatSettings(
    val model: String = LLMModel.Max.alias,
    val provider: LlmProvider = LlmProvider.GIGA,
    val temperature: Float = 0.7f,
    val contextSize: Int = DEFAULT_MAX_TOKENS,
)

data class ChatHistoryMessage(
    val role: String,
    val content: String,
)

data class ChatHistoryResponse(
    val history: List<ChatHistoryMessage>,
)

class BackendRequestException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)

class ChatService(
    private val chatApi: LLMChatAPI,
    private val settings: () -> BackendChatSettings,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val tokenLogging: TokenLogging = NoopTokenLogging,
) {
    private val mutex = Mutex()
    private val conversation = ArrayList<LLMRequest.Message>()

    suspend fun sendMessage(message: String): ChatHistoryResponse = mutex.withLock {
        val userText = message.trim()
        if (userText.isEmpty()) {
            throw BackendRequestException(400, "Message must not be empty.")
        }

        val currentSettings = settings()
        val requestHistory = currentHistoryWithSystemPrompt() + LLMRequest.Message(
            role = LLMMessageRole.user,
            content = userText,
        )
        val request = LLMRequest.Chat(
            model = currentSettings.model,
            messages = requestHistory,
            functions = emptyList(),
            temperature = currentSettings.temperature,
            maxTokens = currentSettings.contextSize,
        )

        val requestId = UUID.randomUUID().toString()
        val response = executeChatRequest(requestId, request, errorStatusCode = 502)
        val assistantText = response.contentOrThrow(statusCode = 502)

        conversation.clear()
        conversation.addAll(
            requestHistory + LLMRequest.Message(
                role = LLMMessageRole.assistant,
                content = assistantText,
            )
        )
        snapshotHistory()
    }

    suspend fun history(): ChatHistoryResponse = mutex.withLock {
        snapshotHistory()
    }

    suspend fun clearHistory(): ChatHistoryResponse = mutex.withLock {
        conversation.clear()
        snapshotHistory()
    }

    private fun currentHistoryWithSystemPrompt(): List<LLMRequest.Message> =
        conversation.takeIf { it.isNotEmpty() }
            ?: listOf(
                LLMRequest.Message(
                    role = LLMMessageRole.system,
                    content = systemPrompt,
                )
            )

    private suspend fun executeChatRequest(
        requestId: String,
        request: LLMRequest.Chat,
        errorStatusCode: Int,
    ): LLMResponse.Chat.Ok {
        tokenLogging.startRequest(requestId)
        val response = try {
            withContext(tokenLogging.requestContextElement(requestId)) {
                chatApi.message(request)
            }
        } finally {
            tokenLogging.finishRequest(requestId)
        }

        return when (response) {
            is LLMResponse.Chat.Error -> throw BackendRequestException(
                statusCode = errorStatusCode,
                message = "LLM request failed (${response.status}): ${response.message}",
            )

            is LLMResponse.Chat.Ok -> response
        }
    }

    private fun LLMResponse.Chat.Ok.contentOrThrow(statusCode: Int): String =
        choices.asReversed()
            .firstNotNullOfOrNull { choice -> choice.message.content.trim().takeIf { it.isNotEmpty() } }
            ?: throw BackendRequestException(statusCode, "LLM returned an empty response.")

    private fun LLMRequest.Message.toHistoryMessage(): ChatHistoryMessage =
        ChatHistoryMessage(
            role = role.name,
            content = content,
        )

    private fun snapshotHistory(): ChatHistoryResponse =
        ChatHistoryResponse(conversation.filterNot { it.role == LLMMessageRole.system }.map { it.toHistoryMessage() })

    private companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Souz AI backend assistant. Answer directly and concisely in the user's language."
    }
}

private object NoopTokenLogging : TokenLogging {
    override fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat) = Unit
}
