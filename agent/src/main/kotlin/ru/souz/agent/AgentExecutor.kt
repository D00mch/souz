package ru.souz.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime

class AgentExecutor internal constructor(
    private val agentProvider: (AgentId) -> TraceableAgent,
    private val memoryRuntime: ConversationMemoryRuntime = NoopConversationMemoryRuntime,
    private val captureScope: CoroutineScope,
    val availableAgents: List<AgentId> = listOf(AgentId.GRAPH),
) {
    private val logger = LoggerFactory.getLogger(AgentExecutor::class.java)

    fun sideEffects(agentId: AgentId): Flow<String> = agentById(agentId).sideEffects

    fun cancelActiveJob(agentId: AgentId) {
        agentById(agentId).cancelActiveJob()
    }

    suspend fun execute(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
    ): AgentExecutionResult = executeWithTrace(
        agentId = agentId,
        context = context,
        input = input,
        eventSink = eventSink,
        onStep = null,
    )

    internal suspend fun executeWithTrace(
        agentId: AgentId,
        context: AgentContext<String>,
        input: String,
        eventSink: AgentRuntimeEventSink? = null,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val runtimeEventSink = eventSink ?: context.runtimeEventSink
        val seed = context.copy(
            input = input,
            runtimeEventSink = runtimeEventSink,
        )

        val result = agentById(agentId).executeWithTrace(seed, onStep)

        captureCompletedTurn(seed, input, result.output)
        return result
    }

    private fun captureCompletedTurn(
        ctx: AgentContext<String>,
        userMessage: String,
        assistantMessage: String,
    ) {
        captureScope.launch {
            try {
                memoryRuntime.captureCompletedTurn(
                    CompletedTurnMemoryInput(
                        conversationId = ctx.toolInvocationMeta.conversationId,
                        userMessageId = ctx.toolInvocationMeta.attributes["userMessageId"]
                            ?: ctx.toolInvocationMeta.requestId,
                        assistantMessageId = ctx.toolInvocationMeta.attributes["assistantMessageId"],
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Memory capture failed: {}", e.message)
            }
        }
    }

    private fun agentById(agentId: AgentId): TraceableAgent = agentProvider(normalizeAgentId(agentId))

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default
}
