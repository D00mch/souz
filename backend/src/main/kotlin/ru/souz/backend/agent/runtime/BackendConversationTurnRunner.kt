package ru.souz.backend.agent.runtime

import ru.souz.agent.spi.AgentToolCatalog
import io.ktor.http.HttpStatusCode
import ru.souz.backend.http.BackendV1Exception
import ru.souz.plugins.PluginException
import ru.souz.backend.plugins.BackendPlugins
import kotlinx.coroutines.CancellationException
import java.util.UUID
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.llms.LLMResponse

internal sealed interface BackendConversationTurnOutcome {
    val usage: LLMResponse.Usage
    val session: AgentConversationSession

    data class Completed(
        val output: String,
        override val usage: LLMResponse.Usage,
        override val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome

    data class WaitingOption(
        override val usage: LLMResponse.Usage,
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
    private val clientThreadRegistry: ClientThreadRuntimeRegistry? = null,
    private val plugins: BackendPlugins? = null,
) : BackendConversationTurnRunner {
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        val lease = try { plugins?.registry?.acquire(request.pluginBindings) }
        catch (error: PluginException) {
            throw BackendV1Exception(HttpStatusCode.ServiceUnavailable,
                error.code, error.message ?: "Required plugin artifact is unavailable")
        }
        return try {
            runWithPlugins(conversationKey, request, eventSink, initialUsage,
                lease?.let { plugins?.catalog(it, conversationKey.userId) } ?: BackendNoopAgentToolCatalog)
        } finally { lease?.close() }
    }

    private suspend fun runWithPlugins(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
        catalog: AgentToolCatalog,
    ): BackendConversationTurnOutcome {
        val runtime = runtimeFactory.create(conversationKey, request, initialUsage, catalog)
        val threadId = request.executionId?.let { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }
        if (threadId != null) clientThreadRegistry?.attach(threadId, runtime)
        return try {
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
                )
            } else {
                BackendConversationTurnOutcome.Completed(
                    output = execution.output,
                    usage = execution.usage,
                    session = execution.session,
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
