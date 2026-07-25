package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.Node
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.state.AgentContext
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toMessage
import ru.souz.memory.CompletedTurnEvidence
import ru.souz.memory.CompletedTurnEvidenceKind
import ru.souz.memory.CompletedTurnMemoryInput
import ru.souz.memory.ConversationId
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.MemoryContext
import ru.souz.memory.MemoryOwnerId
import ru.souz.memory.MemoryRetrievalRequest
import ru.souz.memory.MemorySessionId
import ru.souz.memory.NoopConversationMemoryRuntime

private const val INJECTED_MEMORY_PREFIX = "<souz_memory_context>\n"
private const val INJECTED_MEMORY_SUFFIX = "\n</souz_memory_context>"
internal const val INJECTED_MEMORY_MESSAGE_NAME = "souz.injected.memory"

internal fun LLMRequest.Message.isInjectedMemoryContextMessage(): Boolean =
    role == LLMMessageRole.user && name == INJECTED_MEMORY_MESSAGE_NAME

/** Nodes responsible for persistent conversation-memory recall and completed-turn capture. */
internal class NodesMemory(
    private val memoryRuntime: ConversationMemoryRuntime,
    private val captureScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(NodesMemory::class.java)

    /** Replaces the previous memory augmentation with memory relevant to the current user input. */
    fun recall(name: String = "Memory recall"): Node<String, String> = Node(name) { ctx ->
        val memoryBlock = retrieveMemoryBlock(ctx)
        val history = ctx.history
            .filterNot(LLMRequest.Message::isInjectedMemoryContextMessage)
            .toMutableList()

        memoryBlock?.let { block ->
            val message = LLMRequest.Message(
                role = LLMMessageRole.user,
                content = INJECTED_MEMORY_PREFIX + block + INJECTED_MEMORY_SUFFIX,
                name = INJECTED_MEMORY_MESSAGE_NAME,
            )
            val latestUserIndex = history.indexOfLast { it.role == LLMMessageRole.user }
            if (latestUserIndex >= 0) history.add(latestUserIndex, message) else history += message
        }

        ctx.map(history = history)
    }

    /**
     * Snapshots the completed turn before summarization can compact history, then schedules capture
     * only after the existing finalization node succeeds.
     */
    fun finalizeTurn(
        summarization: Node<LLMResponse.Chat.Ok, String>,
        name: String = "Memory-aware finalization",
    ): Node<LLMResponse.Chat.Ok, String> = object : Node<LLMResponse.Chat.Ok, String> {
        override val name: String = name

        override suspend fun execute(
            ctx: AgentContext<LLMResponse.Chat.Ok>,
            runtime: GraphRuntime,
        ): AgentContext<String> {
            val draft = if (memoryRuntime === NoopConversationMemoryRuntime) null else snapshotCompletedTurn(ctx)
            val finalizedContext = summarization.execute(ctx, runtime)
            draft?.let { scheduleCapture(it.toInput(finalizedContext.input)) }
            return finalizedContext
        }
    }

    private suspend fun retrieveMemoryBlock(ctx: AgentContext<String>): String? {
        if (ctx.input.isBlank() || memoryRuntime === NoopConversationMemoryRuntime) return null

        val memoryResult = try {
            memoryRuntime.retrieveMemory(
                MemoryRetrievalRequest(
                    context = ctx.toolInvocationMeta.toMemoryContext(),
                    query = ctx.input,
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Memory retrieval failed: {}", error.message)
            return null
        }

        val renderedBlock = memoryResult.renderedPromptBlock.orEmpty().trim()
        if (renderedBlock.isBlank()) return null

        try {
            ctx.runtimeEventSink.emit(AgentRuntimeEvent.MemoryPromptAugmented(renderedBlock, memoryResult.facts))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Memory augmentation trace failed: {}", error.message)
        }
        return renderedBlock
    }

    private fun snapshotCompletedTurn(ctx: AgentContext<LLMResponse.Chat.Ok>): CompletedTurnDraft? {
        val userMessageIndex = ctx.history.indexOfLast { it.role == LLMMessageRole.user }
        if (userMessageIndex < 0) {
            logger.warn("Memory capture skipped because the completed turn has no user message")
            return null
        }

        val turnMessages = ctx.history.drop(userMessageIndex + 1)
        val finalResponseMessages = ctx.input.choices.mapNotNull { it.toMessage() }
        val evidenceMessages = if (
            finalResponseMessages.isNotEmpty() &&
            turnMessages.size >= finalResponseMessages.size &&
            turnMessages.takeLast(finalResponseMessages.size) == finalResponseMessages
        ) {
            turnMessages.dropLast(finalResponseMessages.size)
        } else {
            turnMessages
        }
        val meta = ctx.toolInvocationMeta

        return CompletedTurnDraft(
            context = meta.toMemoryContext(),
            conversationId = meta.conversationId,
            userMessageId = meta.attributes["userMessageId"] ?: meta.requestId,
            assistantMessageId = meta.attributes["assistantMessageId"],
            userMessage = ctx.history[userMessageIndex].content,
            evidence = evidenceFrom(evidenceMessages),
        )
    }

    private fun evidenceFrom(messages: List<LLMRequest.Message>): List<CompletedTurnEvidence> {
        val candidates = messages.asSequence()
            .mapNotNull { it.toEvidence() }
            .take(MAX_EVIDENCE_SNIPPETS)
            .map { evidence -> evidence.copy(text = evidence.text.trimMiddle(MAX_EVIDENCE_CHARS)) }

        var remainingChars = MAX_TOTAL_EVIDENCE_CHARS
        return candidates.mapNotNull { evidence ->
            if (remainingChars <= 0) return@mapNotNull null
            val text = evidence.text.trimMiddle(minOf(MAX_EVIDENCE_CHARS, remainingChars))
            if (text.isBlank()) return@mapNotNull null
            remainingChars -= text.length
            evidence.copy(text = text)
        }.toList()
    }

    private fun scheduleCapture(input: CompletedTurnMemoryInput) {
        captureScope.launch {
            try {
                memoryRuntime.captureCompletedTurn(input)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.warn("Memory capture failed: {}", error.message)
            }
        }
    }

    private fun ToolInvocationMeta.toMemoryContext(): MemoryContext = MemoryContext(
        ownerId = MemoryOwnerId(userId),
        conversationId = conversationId?.let(::ConversationId),
        sessionId = conversationId?.let(::MemorySessionId),
        projectId = null,
    )

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

    private data class CompletedTurnDraft(
        val context: MemoryContext,
        val conversationId: String?,
        val userMessageId: String?,
        val assistantMessageId: String?,
        val userMessage: String,
        val evidence: List<CompletedTurnEvidence>,
    ) {
        fun toInput(assistantMessage: String): CompletedTurnMemoryInput = CompletedTurnMemoryInput(
            context = context,
            conversationId = conversationId,
            userMessageId = userMessageId,
            assistantMessageId = assistantMessageId,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            evidence = evidence,
        )
    }

    private companion object {
        private const val MAX_EVIDENCE_SNIPPETS = 16
        private const val MAX_EVIDENCE_CHARS = 6_000
        private const val MAX_TOTAL_EVIDENCE_CHARS = 24_000
    }
}
