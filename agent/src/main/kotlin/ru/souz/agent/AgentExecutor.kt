package ru.souz.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
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
        val baseSeed = context.copy(
            input = input,
            runtimeEventSink = runtimeEventSink,
        )
        val augmentedSystemPrompt = buildMemorySystemPrompt(baseSeed)
        val memoryAddedBlock = memoryAddedBlock(
            baseSystemPrompt = baseSeed.systemPrompt,
            augmentedSystemPrompt = augmentedSystemPrompt,
        )
        val seed = memoryAddedBlock
            ?.let {
                emitMemoryPromptAugmented(runtimeEventSink, it)
                baseSeed.withSystemPrompt(augmentedSystemPrompt)
            }
            ?: baseSeed

        val result = agentById(agentId).executeWithTrace(seed, onStep)
        val normalizedResult = memoryAddedBlock
            ?.let { result.copy(context = result.context.restoreSystemPrompt(baseSeed.systemPrompt)) }
            ?: result

        captureCompletedTurn(baseSeed, input, result.output)
        return normalizedResult
    }

    private suspend fun buildMemorySystemPrompt(ctx: AgentContext<String>): String =
        try {
            memoryRuntime.buildSystemPrompt(
                baseSystemPrompt = ctx.systemPrompt,
                userMessage = ctx.input,
                conversationId = ctx.toolInvocationMeta.conversationId,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Memory retrieval failed: {}", e.message)
            ctx.systemPrompt
        }

    private suspend fun emitMemoryPromptAugmented(
        eventSink: AgentRuntimeEventSink,
        addedBlock: String,
    ) {
        try {
            eventSink.emit(AgentRuntimeEvent.MemoryPromptAugmented(addedBlock))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Memory augmentation trace failed: {}", e.message)
        }
    }

    private suspend fun captureCompletedTurn(
        ctx: AgentContext<String>,
        userMessage: String,
        assistantMessage: String,
    ) {
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

    private fun memoryAddedBlock(
        baseSystemPrompt: String,
        augmentedSystemPrompt: String,
    ): String? {
        if (augmentedSystemPrompt == baseSystemPrompt) return null
        val basePrefix = baseSystemPrompt.trimEnd()
        val addedBlock = when {
            basePrefix.isBlank() -> augmentedSystemPrompt
            augmentedSystemPrompt.startsWith("$basePrefix\n\n") ->
                augmentedSystemPrompt.removePrefix("$basePrefix\n\n")
            augmentedSystemPrompt.startsWith(basePrefix) ->
                augmentedSystemPrompt.removePrefix(basePrefix).trimStart()
            else -> augmentedSystemPrompt
        }
        return addedBlock.takeIf { it.isNotBlank() }
    }

    private fun AgentContext<String>.withSystemPrompt(prompt: String): AgentContext<String> =
        copy(
            systemPrompt = prompt,
            history = history.replaceSystemPromptIfPresent(prompt),
        )

    private fun AgentContext<String>.restoreSystemPrompt(prompt: String): AgentContext<String> =
        copy(
            systemPrompt = prompt,
            history = history.replaceSystemPromptIfPresent(prompt),
        )

    private fun List<LLMRequest.Message>.replaceSystemPromptIfPresent(systemPrompt: String): List<LLMRequest.Message> =
        when (firstOrNull()?.role) {
            LLMMessageRole.system -> listOf(first().copy(content = systemPrompt)) + drop(1)
            else -> this
        }

    private fun agentById(agentId: AgentId): TraceableAgent = agentProvider(normalizeAgentId(agentId))

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default
}
