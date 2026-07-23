package ru.souz.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolBatchResume
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.memory.CompletedTurnEvidence
import ru.souz.memory.CompletedTurnEvidenceKind
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationId
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemorySessionId
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

    /**
     * Continues a stored semantic tool batch and rejoins the normal post-tool agent loop.
     * No synthetic user turn or pre-LLM discovery/classification nodes are executed.
     */
    suspend fun resumeToolBatch(
        agentId: AgentId,
        context: AgentContext<String>,
        resume: AgentToolBatchResume,
        eventSink: AgentRuntimeEventSink? = null,
    ): AgentExecutionResult = resumeToolBatchWithTrace(
        agentId = agentId,
        context = context,
        resume = resume,
        eventSink = eventSink,
        onStep = null,
    )

    internal suspend fun resumeToolBatchWithTrace(
        agentId: AgentId,
        context: AgentContext<String>,
        resume: AgentToolBatchResume,
        eventSink: AgentRuntimeEventSink? = null,
        onStep: GraphStepCallback?,
    ): AgentExecutionResult {
        val runtimeEventSink = eventSink ?: context.runtimeEventSink
        val seed = context.map(transform = { resume }).copy(runtimeEventSink = runtimeEventSink)
        return agentById(agentId).resumeToolBatchWithTrace(seed, onStep)
    }

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
        val evidenceCollector = CompletedTurnEvidenceCollector()
        val tracedOnStep: GraphStepCallback = { step, node, from, to ->
            evidenceCollector.collect(from.history, to.history)
            onStep?.invoke(step, node, from, to)
        }

        val result = agentById(agentId).executeWithTrace(seed, tracedOnStep)
        return result.copy(
            captureCompletedTurn = {
                captureCompletedTurn(
                    ctx = seed,
                    userMessage = input,
                    assistantMessage = result.output,
                    evidence = evidenceCollector.toEvidence(result.output),
                )
            },
        )
    }

    private fun captureCompletedTurn(
        ctx: AgentContext<String>,
        userMessage: String,
        assistantMessage: String,
        evidence: List<CompletedTurnEvidence>,
    ) {
        if (memoryRuntime === NoopConversationMemoryRuntime) return
        captureScope.launch {
            try {
                memoryRuntime.captureCompletedTurn(
                    CompletedTurnMemoryInput(
                        context = MemoryContext(
                            ownerId = MemoryOwnerId(ctx.toolInvocationMeta.userId),
                            conversationId = ctx.toolInvocationMeta.conversationId?.let(::ConversationId),
                            sessionId = ctx.toolInvocationMeta.conversationId?.let(::MemorySessionId),
                            projectId = null,
                        ),
                        conversationId = ctx.toolInvocationMeta.conversationId,
                        userMessageId = ctx.toolInvocationMeta.attributes["userMessageId"]
                            ?: ctx.toolInvocationMeta.requestId,
                        assistantMessageId = ctx.toolInvocationMeta.attributes["assistantMessageId"],
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        evidence = evidence,
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Memory capture failed: {}", error.message)
            }
        }
    }

    private fun agentById(agentId: AgentId): TraceableAgent = agentProvider(normalizeAgentId(agentId))

    private fun normalizeAgentId(agentId: AgentId): AgentId =
        if (agentId in availableAgents) agentId else AgentId.default

    private class CompletedTurnEvidenceCollector {
        private val collected = mutableListOf<CompletedTurnEvidence>()

        fun collect(fromHistory: List<LLMRequest.Message>, toHistory: List<LLMRequest.Message>) {
            if (collected.size >= MAX_EVIDENCE_SNIPPETS) return
            val newMessages = if (
                fromHistory.size <= toHistory.size &&
                toHistory.subList(0, fromHistory.size) == fromHistory
            ) {
                toHistory.drop(fromHistory.size)
            } else {
                val unmatchedPreviousMessages = fromHistory.toMutableList()
                toHistory.filter { message ->
                    val previousIndex = unmatchedPreviousMessages.indexOf(message)
                    if (previousIndex >= 0) {
                        unmatchedPreviousMessages.removeAt(previousIndex)
                        false
                    } else {
                        true
                    }
                }
            }
            newMessages.forEach { message ->
                if (collected.size >= MAX_EVIDENCE_SNIPPETS) return
                val evidence = message.toEvidence() ?: return@forEach
                collected += evidence.copy(text = evidence.text.trimMiddle(MAX_EVIDENCE_CHARS))
            }
        }

        fun toEvidence(finalAssistantMessage: String): List<CompletedTurnEvidence> {
            val finalAssistantText = finalAssistantMessage.trim().trimMiddle(MAX_EVIDENCE_CHARS)
            var remainingChars = MAX_TOTAL_EVIDENCE_CHARS
            return collected.asSequence().filterNot { evidence ->
                evidence.kind == CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS && evidence.text.trim() == finalAssistantText
            }.mapNotNull { evidence ->
                if (remainingChars <= 0) return@mapNotNull null
                val text = evidence.text.trimMiddle(minOf(MAX_EVIDENCE_CHARS, remainingChars))
                if (text.isBlank()) return@mapNotNull null
                remainingChars -= text.length
                evidence.copy(text = text)
            }.toList()
        }

        private fun LLMRequest.Message.toEvidence(): CompletedTurnEvidence? {
            val cleanText = content.trim().takeIf(String::isNotBlank) ?: return null
            return when (role) {
                LLMMessageRole.function -> CompletedTurnEvidence(
                    kind = CompletedTurnEvidenceKind.TOOL_OUTPUT,
                    sourceName = name?.trim()?.takeIf(String::isNotBlank) ?: functionsStateId,
                    text = cleanText,
                )
                LLMMessageRole.assistant -> {
                    if (functionsStateId != null) return null
                    CompletedTurnEvidence(
                        kind = CompletedTurnEvidenceKind.ASSISTANT_SYNTHESIS,
                        text = cleanText,
                    )
                }
                else -> null
            }
        }

        private fun String.trimMiddle(maxChars: Int): String {
            if (length <= maxChars) return this
            val marker = "\n...[truncated]...\n"
            if (maxChars <= marker.length) return take(maxChars.coerceAtLeast(0))
            val keep = (maxChars - marker.length).coerceAtLeast(0)
            val head = keep / 2
            val tail = keep - head
            return take(head) + marker + takeLast(tail)
        }

        private companion object {
            private const val MAX_EVIDENCE_SNIPPETS = 16
            private const val MAX_EVIDENCE_CHARS = 6_000
            private const val MAX_TOTAL_EVIDENCE_CHARS = 24_000
        }
    }
}
