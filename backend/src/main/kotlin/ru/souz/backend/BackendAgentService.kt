package ru.souz.backend

import java.util.LinkedHashSet
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.souz.db.SettingsProvider
import ru.souz.llms.LLMModel
import ru.souz.llms.LlmProvider

class BackendAgentService(
    private val baseSettingsProvider: SettingsProvider,
    private val runtimeCache: BackendConversationRuntimeCache,
    private val agentConversationExists: suspend (
        userId: String,
        conversationId: String,
    ) -> Boolean = { _, _ -> true },
) {
    private val agentMutex = Mutex()
    private val activeAgentRequestIds = LinkedHashSet<String>()
    private val activeAgentConversations = LinkedHashSet<AgentConversationKey>()
    private val completedAgentRequestIds = LinkedHashSet<String>()

    suspend fun sendAgentRequest(request: AgentRequest): AgentResponse {
        val validated = request.validated()
        if (!agentConversationExists(validated.userId, validated.conversationId)) {
            throw BackendRequestException(404, "User or conversation not found.")
        }
        val conversationKey = AgentConversationKey(validated.userId, validated.conversationId)
        agentMutex.withLock {
            when {
                validated.requestId in activeAgentRequestIds || validated.requestId in completedAgentRequestIds ->
                    throw BackendRequestException(409, "Duplicate requestId.")

                conversationKey in activeAgentConversations ->
                    throw BackendRequestException(409, "Conversation already has an active request.")
            }

            activeAgentRequestIds += validated.requestId
            activeAgentConversations += conversationKey
        }

        try {
            val runtime: BackendConversationRuntime = runtimeCache.getOrCreate(conversationKey, validated)
            val execution = runtime.execute(validated)
            rememberCompletedAgentRequestId(validated.requestId)

            return AgentResponse(
                requestId = validated.requestId,
                conversationId = validated.conversationId,
                userMessageId = UUID.randomUUID().toString(),
                assistantMessageId = UUID.randomUUID().toString(),
                content = execution.output,
                model = validated.model,
                provider = providerForModel(validated.model, fallback = baseSettingsProvider.gigaModel.provider),
                contextSize = validated.contextSize,
                usage = AgentUsage(
                    promptTokens = execution.usage.promptTokens,
                    completionTokens = execution.usage.completionTokens,
                    totalTokens = execution.usage.totalTokens,
                    precachedTokens = execution.usage.precachedTokens,
                ),
            )
        } finally {
            agentMutex.withLock {
                activeAgentRequestIds -= validated.requestId
                activeAgentConversations -= conversationKey
            }
        }
    }

    private fun rememberCompletedAgentRequestId(requestId: String) {
        completedAgentRequestIds += requestId
        while (completedAgentRequestIds.size > MAX_COMPLETED_AGENT_REQUEST_IDS) {
            val oldestRequestId = completedAgentRequestIds.iterator().next()
            completedAgentRequestIds -= oldestRequestId
        }
    }

    private fun providerForModel(model: String, fallback: LlmProvider): String =
        LLMModel.entries.firstOrNull { candidate ->
            candidate.alias.equals(model, ignoreCase = true) || candidate.name.equals(model, ignoreCase = true)
        }?.provider?.name ?: fallback.name

    private companion object {
        const val MAX_COMPLETED_AGENT_REQUEST_IDS = 10_000
    }
}
