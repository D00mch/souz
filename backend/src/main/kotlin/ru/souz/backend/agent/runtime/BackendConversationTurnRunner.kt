package ru.souz.backend.agent.runtime

import kotlinx.coroutines.CancellationException
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.events.service.AgentEventService
import ru.souz.backend.execution.model.AgentExecutionUsage
import ru.souz.backend.permission.repository.ParkPermissionCommand
import ru.souz.backend.permission.repository.PermissionWorkflowRepository
import ru.souz.llms.LLMResponse

internal sealed interface BackendConversationTurnOutcome {
    data class Completed(
        val output: String,
        val usage: LLMResponse.Usage,
        val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome

    data class WaitingOption(
        val usage: LLMResponse.Usage,
        val session: AgentConversationSession,
    ) : BackendConversationTurnOutcome

    data class WaitingPermission(
        val usage: LLMResponse.Usage,
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
    private val permissionWorkflowRepository: PermissionWorkflowRepository? = null,
    private val eventService: AgentEventService? = null,
) : BackendConversationTurnRunner {
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        val runtime = runtimeFactory.create(conversationKey, request, initialUsage)
        return try {
            val execution = request.permissionContinuation?.let { continuation ->
                runtime.resumePermission(
                    request = request,
                    continuation = continuation,
                    eventSink = eventSink,
                )
            } ?: runtime.execute(
                request = request,
                persistSession = false,
                eventSink = eventSink,
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
        } catch (pause: BackendPermissionDraft) {
            val repository = checkNotNull(permissionWorkflowRepository) {
                "Permission workflow repository is unavailable."
            }
            val usage = runtime.currentUsage()
            val transition = repository.park(
                ParkPermissionCommand(
                    executionId = pause.executionId,
                    userId = pause.userId,
                    chatId = pause.chatId,
                    invocationId = pause.invocationId,
                    toolCallId = pause.toolCallId,
                    toolName = pause.toolName,
                    description = pause.description,
                    displayParams = pause.displayParams,
                    promptHash = pause.promptHash,
                    usage = usage.toExecutionUsage(),
                )
            )
            transition.event?.let { event ->
                checkNotNull(eventService) { "Agent event service is unavailable." }
                    .publishPersisted(event)
            }
            BackendConversationTurnOutcome.WaitingPermission(
                usage = usage,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BackendConversationTurnException(
                cause = e,
                usage = runtime.currentUsage(),
            )
        }
    }
}

private fun LLMResponse.Usage.toExecutionUsage(): AgentExecutionUsage =
    AgentExecutionUsage(
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        totalTokens = totalTokens,
        precachedTokens = precachedTokens,
    )
