package ru.souz.backend

import java.time.DateTimeException
import java.time.ZoneId
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

data class AgentRequest(
    val requestId: String = "",
    val userId: String = "",
    val conversationId: String = "",
    val prompt: String = "",
    val model: String = "",
    val contextSize: Int = 0,
    val source: String = "",
    val locale: String = "",
    val timeZone: String = "",
)

data class AgentUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val precachedTokens: Int,
)

data class AgentResponse(
    val requestId: String,
    val conversationId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val content: String,
    val model: String,
    val provider: String,
    val contextSize: Int,
    val usage: AgentUsage,
)

class BackendRequestException(
    val statusCode: Int,
    override val message: String,
) : RuntimeException(message)

class BackendChatService(
    private val chatApi: LLMChatAPI,
    private val settings: () -> BackendChatSettings,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val agentConversationExists: suspend (
        userId: String,
        conversationId: String,
    ) -> Boolean = { _, _ -> true },
    private val tokenLogging: TokenLogging = NoopTokenLogging,
) {
    private val mutex = Mutex()
    private val conversation = ArrayList<LLMRequest.Message>()
    private val agentMutex = Mutex()
    private val agentConversations = LinkedHashMap<AgentConversationKey, List<LLMRequest.Message>>()
    private val activeAgentRequestIds = LinkedHashSet<String>()
    private val activeAgentConversations = LinkedHashSet<AgentConversationKey>()
    private val completedAgentRequestIds = LinkedHashSet<String>()

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

    suspend fun sendAgentRequest(request: AgentRequest): AgentResponse {
        val validated = request.validated()
        if (!agentConversationExists(validated.userId, validated.conversationId)) {
            throw BackendRequestException(404, "User or conversation not found.")
        }
        val conversationKey = AgentConversationKey(validated.userId, validated.conversationId)
        val currentSettings = settings()
        val requestHistory = agentMutex.withLock {
            when {
                validated.requestId in activeAgentRequestIds || validated.requestId in completedAgentRequestIds ->
                    throw BackendRequestException(409, "Duplicate requestId.")
                conversationKey in activeAgentConversations ->
                    throw BackendRequestException(409, "Conversation already has an active request.")
            }

            activeAgentRequestIds += validated.requestId
            activeAgentConversations += conversationKey

            currentAgentHistory(conversationKey) + LLMRequest.Message(
                role = LLMMessageRole.user,
                content = validated.prompt,
            )
        }

        val llmRequest = LLMRequest.Chat(
            model = validated.model,
            messages = requestHistory,
            functions = emptyList(),
            temperature = currentSettings.temperature,
            maxTokens = validated.contextSize,
        )

        try {
            val llmResponse = executeChatRequest(validated.requestId, llmRequest, errorStatusCode = 500)
            val assistantText = llmResponse.contentOrThrow(statusCode = 500)
            val assistantMessage = LLMRequest.Message(
                role = LLMMessageRole.assistant,
                content = assistantText,
            )
            val response = AgentResponse(
                requestId = validated.requestId,
                conversationId = validated.conversationId,
                userMessageId = UUID.randomUUID().toString(),
                assistantMessageId = UUID.randomUUID().toString(),
                content = assistantText,
                model = validated.model,
                provider = providerForModel(validated.model, fallback = currentSettings.provider),
                contextSize = validated.contextSize,
                usage = llmResponse.usage.toAgentUsage(),
            )

            agentMutex.withLock {
                agentConversations[conversationKey] = requestHistory + assistantMessage
                rememberCompletedAgentRequestId(validated.requestId)
            }

            return response
        } finally {
            agentMutex.withLock {
                activeAgentRequestIds -= validated.requestId
                activeAgentConversations -= conversationKey
            }
        }
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

    private fun currentAgentHistory(conversationKey: AgentConversationKey): List<LLMRequest.Message> =
        agentConversations[conversationKey]
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

    private fun rememberCompletedAgentRequestId(requestId: String) {
        completedAgentRequestIds += requestId
        while (completedAgentRequestIds.size > MAX_COMPLETED_AGENT_REQUEST_IDS) {
            val oldestRequestId = completedAgentRequestIds.iterator().next()
            completedAgentRequestIds -= oldestRequestId
        }
    }

    private fun LLMResponse.Usage.toAgentUsage(): AgentUsage =
        AgentUsage(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            precachedTokens = precachedTokens,
        )

    private fun providerForModel(model: String, fallback: LlmProvider): String =
        LLMModel.entries.firstOrNull { candidate ->
            candidate.alias.equals(model, ignoreCase = true) || candidate.name.equals(model, ignoreCase = true)
        }?.provider?.name ?: fallback.name

    private companion object {
        const val MAX_COMPLETED_AGENT_REQUEST_IDS = 10_000
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Souz AI backend assistant. Answer directly and concisely in the user's language."
    }
}

private data class AgentConversationKey(
    val userId: String,
    val conversationId: String,
)

private data class ValidatedAgentRequest(
    val requestId: String,
    val userId: String,
    val conversationId: String,
    val prompt: String,
    val model: String,
    val contextSize: Int,
)

private fun AgentRequest.validated(): ValidatedAgentRequest {
    source.trim().takeIf { it.isNotEmpty() }
        ?: throw BackendRequestException(400, "source must not be empty.")
    locale.trim().takeIf { it.isNotEmpty() }
        ?: throw BackendRequestException(400, "locale must not be empty.")
    validateTimeZone(timeZone.trim())

    return ValidatedAgentRequest(
        requestId = requestId.requireUuid("requestId"),
        userId = userId.requireUuid("userId"),
        conversationId = conversationId.requireUuid("conversationId"),
        prompt = prompt.trim().takeIf { it.isNotEmpty() }
            ?: throw BackendRequestException(400, "prompt must not be empty."),
        model = model.trim().takeIf { it.isNotEmpty() }
            ?: throw BackendRequestException(400, "model must not be empty."),
        contextSize = contextSize.takeIf { it > 0 }
            ?: throw BackendRequestException(400, "contextSize must be positive."),
    )
}

private fun String.requireUuid(fieldName: String): String =
    trim().let { value ->
        runCatching { UUID.fromString(value).toString() }
            .getOrElse { throw BackendRequestException(400, "$fieldName must be a UUID.") }
    }

private fun validateTimeZone(value: String) {
    if (value.isEmpty()) {
        throw BackendRequestException(400, "timeZone must not be empty.")
    }
    try {
        ZoneId.of(value)
    } catch (e: DateTimeException) {
        throw BackendRequestException(400, "timeZone must be a valid time zone.")
    }
}

private object NoopTokenLogging : TokenLogging {
    override fun logTokenUsage(result: LLMResponse.Chat.Ok, body: LLMRequest.Chat) = Unit
}
