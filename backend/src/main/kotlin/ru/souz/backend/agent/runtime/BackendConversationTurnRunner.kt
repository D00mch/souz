package ru.souz.backend.agent.runtime

import kotlinx.coroutines.CancellationException
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendAgentRuntimeException
import ru.souz.backend.agent.model.BackendAgentRuntimeOutcome
import ru.souz.backend.agent.model.BackendAgentTurn
import ru.souz.backend.agent.model.BackendAgentTurnInput
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.execution.model.AgentExecutionRuntimeConfig
import ru.souz.llms.LLMResponse

@Deprecated("Use BackendAgentRuntimeOutcome.")
internal sealed interface BackendConversationTurnOutcome {
    val session: AgentConversationSession

    data class Completed(
        val output: String,
        val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome

    data class WaitingOption(
        val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome
}

@Deprecated("Use BackendAgentRuntimeException.")
internal class BackendConversationTurnException(
    cause: Throwable,
    val usage: LLMResponse.Usage,
) : RuntimeException(cause)

@Deprecated("Use BackendAgentRuntime directly.")
internal interface BackendConversationTurnRunner {
    suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
    ): BackendConversationTurnOutcome
}

@Deprecated("Use BackendAgentRuntime directly.")
internal class BackendConversationRuntimeTurnRunner(
    private val runtime: BackendAgentRuntime,
) : BackendConversationTurnRunner {
    constructor(runtimeFactory: BackendConversationRuntimeFactory) : this(runtimeFactory.asAgentRuntime())

    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        val turn = BackendAgentTurn(
            userId = conversationKey.userId,
            chatId = java.util.UUID.fromString(conversationKey.conversationId),
            executionId = java.util.UUID.fromString(request.executionId ?: conversationKey.conversationId),
            conversationId = conversationKey.conversationId,
            input = BackendAgentTurnInput.UserMessage(request.prompt),
            config = AgentExecutionRuntimeConfig(
                modelAlias = request.model,
                contextSize = request.contextSize,
                temperature = request.temperature,
                locale = request.locale,
                timeZone = request.timeZone,
                systemPrompt = request.systemPrompt,
                streamingMessages = request.streamingMessages == true,
                showToolEvents = false,
            ),
        )
        return try {
            when (val outcome = runtime.run(turn, eventSink, initialUsage)) {
                is BackendAgentRuntimeOutcome.Completed -> BackendConversationTurnOutcome.Completed(
                    output = outcome.output,
                    usage = outcome.usage,
                    session = outcome.session,
                )
                is BackendAgentRuntimeOutcome.WaitingOption -> BackendConversationTurnOutcome.WaitingOption(
                    usage = outcome.usage,
                    session = outcome.session,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackendAgentRuntimeException) {
            throw BackendConversationTurnException(e.cause ?: e, e.usage)
        }
    }
}

private fun BackendConversationRuntimeFactory.asAgentRuntime(): BackendAgentRuntime =
    BackendAgentRuntime(
        baseSettingsProvider = baseSettingsProvider,
        llmApiFactory = llmApiFactory,
        sessionRepository = sessionRepository,
        logObjectMapper = logObjectMapper,
        systemPrompt = systemPrompt,
        toolCatalog = toolCatalog,
        toolsFilter = toolsFilter,
    )
