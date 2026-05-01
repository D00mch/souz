package ru.souz.backend.agent.runtime

import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.llms.LLMResponse

internal sealed interface BackendConversationTurnOutcome {
    val session: AgentConversationSession

    data class Completed(
        val output: String,
        val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome

    data class WaitingChoice(
        val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome
}

internal class BackendConversationTurnException(
    cause: Throwable,
    val usage: LLMResponse.Usage,
) : RuntimeException(cause)

internal interface BackendConversationTurnRunner {
    suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage = LLMResponse.Usage(0, 0, 0, 0),
    ): BackendConversationTurnOutcome
}

internal class BackendConversationRuntimeTurnRunner(
    private val runtimeFactory: BackendConversationRuntimeFactory,
) : BackendConversationTurnRunner {
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        val runtime = runtimeFactory.create(conversationKey, request, initialUsage)
        return try {
            val execution = runtime.execute(
                request = request,
                persistSession = false,
                eventSink = eventSink,
            )
            if (eventSink is BackendAgentRuntimeEventSink && eventSink.hasRequestedChoice) {
                BackendConversationTurnOutcome.WaitingChoice(
                    usage = execution.usage,
                    session = execution.session,
                )
            } else {
                BackendConversationTurnOutcome.Completed(
                    output = execution.output,
                    usage = execution.usage,
                    session = execution.session,
                )
            }
        } catch (error: Throwable) {
            throw BackendConversationTurnException(
                cause = error,
                usage = runtime.currentUsage(),
            )
        }
    }
}
