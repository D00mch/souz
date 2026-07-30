package ru.souz.tool.knowledge

import kotlinx.coroutines.CancellationException
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta

/** Retrieves all content retained for a conversation-scoped Knowledge entry. */
class ToolGetKnowledge internal constructor(
    private val retriever: KnowledgeRetriever,
) : LLMToolSetup {
    internal constructor(
        knowledgeStore: ConversationKnowledgeStore,
    ) : this(KnowledgeRetriever(knowledgeStore))

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = NAME,
        description = "Retrieve all retained content for a temporary Knowledge entry. Truncated entries return their retained head and tail with the omitted range.",
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "knowledgeId" to LLMRequest.Property(
                    type = "string",
                    description = "Opaque Knowledge ID from a tool result in this conversation.",
                ),
            ),
            required = listOf("knowledgeId"),
        ),
        returnParameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf(
                "knowledgeId" to LLMRequest.Property("string", "The requested Knowledge ID."),
                "sourceTool" to LLMRequest.Property("string", "Tool that produced the stored result."),
                "originalLength" to LLMRequest.Property("integer", "Original UTF-16 content length."),
                "storedLength" to LLMRequest.Property("integer", "Retained UTF-16 content length."),
                "truncated" to LLMRequest.Property("boolean", "Whether storage omitted a middle range."),
                "text" to LLMRequest.Property("string", "Complete retained text for an untruncated entry."),
                "head" to LLMRequest.Property("object", "Retained head text and its original UTF-16 range."),
                "tail" to LLMRequest.Property("object", "Retained tail text and its original UTF-16 range."),
                "omitted" to LLMRequest.Property("object", "Omitted original UTF-16 range."),
                "error" to LLMRequest.Property("object", "A structured retrieval error."),
            ),
        ),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        val knowledgeId = try {
            functionCall.arguments.toKnowledgeId()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return knowledgeErrorMessage(
                functionCall.name,
                INVALID_ARGUMENTS,
                error.message ?: "GetKnowledge arguments are invalid.",
            )
        }
        return retriever.get(meta, knowledgeId).toMessage(functionCall.name) { it.fullResponse() }
    }

    private fun Map<String, Any>.toKnowledgeId(): String {
        val unknownArguments = keys - INPUT_ARGUMENT_NAMES
        if (unknownArguments.isNotEmpty()) {
            throw IllegalArgumentException(
                "Unknown GetKnowledge arguments: ${unknownArguments.sorted().joinToString()}.",
            )
        }
        return this["knowledgeId"] as? String
            ?: throw IllegalArgumentException("knowledgeId must be a string.")
    }

    private fun KnowledgeEntry.fullResponse(): Map<String, Any> = when (val retained = content) {
        is KnowledgeContent.Complete -> metadata() + ("text" to retained.content)
        is KnowledgeContent.Truncated -> {
            val tailStart = originalLength - retained.tail.length
            metadata() + mapOf(
                "head" to RetainedSegment(
                    text = retained.head,
                    start = 0,
                    end = retained.head.length,
                ),
                "tail" to RetainedSegment(
                    text = retained.tail,
                    start = tailStart,
                    end = originalLength,
                ),
                "omitted" to OffsetRange(
                    start = retained.head.length,
                    end = tailStart,
                ),
            )
        }
    }

    private data class RetainedSegment(
        val text: String,
        val start: Int,
        val end: Int,
    )

    private data class OffsetRange(
        val start: Int,
        val end: Int,
    )

    companion object {
        const val NAME = "GetKnowledge"
        private val INPUT_ARGUMENT_NAMES = setOf("knowledgeId")
    }
}
