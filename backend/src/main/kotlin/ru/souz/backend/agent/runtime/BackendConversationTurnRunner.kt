package ru.souz.backend.agent.runtime

import kotlinx.coroutines.CancellationException
import java.util.UUID
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.spi.RoutedModel
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.llms.LLMResponse
import ru.souz.backend.plugins.BackendPluginRuntime
import ru.souz.backend.plugins.PluginExecutionBinding

internal sealed interface BackendConversationTurnOutcome {
    val usage: LLMResponse.Usage
    val session: AgentConversationSession
    val routedModel: RoutedModel?

    data class Completed(
        val output: String,
        override val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
        override val routedModel: RoutedModel? = null,
    ) : BackendConversationTurnOutcome

    data class WaitingOption(
        override val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
        override val routedModel: RoutedModel? = null,
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
    private val clientThreadRegistry: ClientThreadRuntimeRegistry? = null,
    private val plugins: BackendPluginRuntime,
) : BackendConversationTurnRunner {
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        val binding = plugins.acquire(conversationKey.userId, request.enabledTools)
        return try {
            runBound(conversationKey, request, eventSink, initialUsage, binding)
        } finally {
            binding.release()
        }
    }

    private suspend fun runBound(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
        binding: PluginExecutionBinding,
    ): BackendConversationTurnOutcome {
        val runtime = runtimeFactory.create(conversationKey, request, initialUsage, plugins = binding)
        val threadId = request.executionId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        return try {
            if (threadId != null) clientThreadRegistry?.attach(threadId, runtime)
            val execution = runtime.execute(
                request = request,
                eventSink = eventSink,
                onRuntimeReady = {
                    if (threadId != null) clientThreadRegistry?.markRuntimeReady(threadId, runtime)
                },
            )
            if (eventSink is BackendAgentRuntimeEventSink && eventSink.hasRequestedOption) {
                BackendConversationTurnOutcome.WaitingOption(
                    usage = execution.usage,
                    session = execution.session,
                    routedModel = execution.routedModel,
                )
            } else {
                BackendConversationTurnOutcome.Completed(
                    output = execution.output,
                    usage = execution.usage,
                    session = execution.session,
                    routedModel = execution.routedModel,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendConversationTurnException(
                cause = e,
                usage = runtime.currentUsage(),
            )
        } finally {
            if (threadId != null) clientThreadRegistry?.detach(threadId, runtime)
        }
    }
}
