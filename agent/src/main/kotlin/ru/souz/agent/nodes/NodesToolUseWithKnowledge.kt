package ru.souz.agent.nodes

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import ru.souz.agent.graph.Node
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

internal class NodesToolUseWithKnowledge(
    private val nodesCommon: NodesCommon,
    private val knowledgeStore: ConversationKnowledgeStore?,
) {
    private val logger = LoggerFactory.getLogger(NodesToolUseWithKnowledge::class.java)

    /** Keeps exempt results unchanged and replaces other oversized results with Knowledge references. */
    fun node(
        alwaysInlineToolNames: Set<String>,
        name: String = "toolUse",
    ): Node<LLMResponse.Chat.Ok, String> = Node(name) { ctx ->
        val fnCallMessages = nodesCommon.executeFunctionCalls(ctx).map { (functionCall, message) ->
            if (
                functionCall.name in alwaysInlineToolNames ||
                message.content.toByteArray(Charsets.UTF_8).size <= KNOWLEDGE_OFFLOAD_THRESHOLD_BYTES
            ) {
                message
            } else {
                offloadToolResult(
                    message = message,
                    sourceTool = functionCall.name,
                    meta = ctx.toolInvocationMeta,
                )
            }
        }
        val history = ArrayList(ctx.history).apply { addAll(fnCallMessages) }
        ctx.map(history = history) { ctx.history.last().content }
    }

    private suspend fun offloadToolResult(
        message: LLMRequest.Message,
        sourceTool: String,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val store = knowledgeStore ?: run {
            logger.warn("Knowledge storage is unavailable; keeping oversized {} result inline", sourceTool)
            return message
        }
        return try {
            when (val writeResult = store.put(meta, sourceTool, message.content)) {
                KnowledgeWriteResult.ConversationUnavailable -> {
                    logger.warn("Conversation scope is unavailable; keeping oversized {} result inline", sourceTool)
                    message
                }

                is KnowledgeWriteResult.Stored -> {
                    val entry = writeResult.entry
                    message.copy(
                        content = restJsonMapper.writeValueAsString(
                            linkedMapOf(
                                "knowledgeId" to entry.id,
                                "sourceTool" to entry.sourceTool,
                                "originalLength" to entry.originalLength,
                                "storedLength" to entry.storedLength,
                                "truncated" to (entry.storedLength < entry.originalLength),
                                "instruction" to "Call GetKnowledge with this knowledgeId for all retained content, or SearchKnowledge for targeted regex retrieval.",
                            )
                        )
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("Knowledge storage failed; keeping oversized {} result inline", sourceTool, error)
            message
        }
    }
}

internal const val KNOWLEDGE_OFFLOAD_THRESHOLD_BYTES = 8_192
