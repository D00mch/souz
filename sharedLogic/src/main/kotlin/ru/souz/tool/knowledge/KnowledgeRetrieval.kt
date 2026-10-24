package ru.souz.tool.knowledge

import kotlinx.coroutines.CancellationException
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeStoreUnavailableException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper

/** Shared validation and storage retrieval for Knowledge tools. */
internal class KnowledgeRetriever(
    private val knowledgeStore: ConversationKnowledgeStore,
) {
    suspend fun get(
        meta: ToolInvocationMeta,
        requestedKnowledgeId: String,
    ): KnowledgeRetrievalResult {
        val knowledgeId = requestedKnowledgeId.trim()
        if (knowledgeId.isBlank()) {
            return KnowledgeRetrievalResult.Error(INVALID_ARGUMENTS, "knowledgeId must not be blank.")
        }

        return try {
            val entry = knowledgeStore.get(meta, knowledgeId)
                ?: return KnowledgeRetrievalResult.Error(
                    KNOWLEDGE_NOT_FOUND,
                    "Knowledge is unavailable in this conversation: $knowledgeId",
                )
            KnowledgeRetrievalResult.Found(entry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: KnowledgeStoreUnavailableException) {
            KnowledgeRetrievalResult.Error(
                CONVERSATION_UNAVAILABLE,
                error.message ?: "Knowledge requires an available conversation scope.",
            )
        } catch (error: Exception) {
            KnowledgeRetrievalResult.Error(
                STORAGE_FAILURE,
                error.message ?: "Knowledge storage failed.",
            )
        }
    }
}

internal sealed interface KnowledgeRetrievalResult {
    data class Found(val entry: KnowledgeEntry) : KnowledgeRetrievalResult
    data class Error(val code: String, val message: String) : KnowledgeRetrievalResult
}

internal fun KnowledgeEntry.metadata(): Map<String, Any> = linkedMapOf(
    "knowledgeId" to id,
    "sourceTool" to sourceTool,
    "originalLength" to originalLength,
    "storedLength" to storedLength,
    "truncated" to (content is KnowledgeContent.Truncated),
)

internal fun knowledgeFunctionMessage(
    functionName: String,
    response: Any,
): LLMRequest.Message = LLMRequest.Message(
    role = LLMMessageRole.function,
    content = restJsonMapper.writeValueAsString(response),
    name = functionName,
)

internal fun knowledgeErrorMessage(
    functionName: String,
    code: String,
    message: String,
): LLMRequest.Message = knowledgeFunctionMessage(
    functionName,
    mapOf("error" to KnowledgeError(code, message)),
)

internal fun KnowledgeRetrievalResult.toMessage(
    functionName: String,
    transform: (KnowledgeEntry) -> Any,
): LLMRequest.Message = when (this) {
    is KnowledgeRetrievalResult.Found -> knowledgeFunctionMessage(functionName, transform(entry))
    is KnowledgeRetrievalResult.Error -> knowledgeErrorMessage(functionName, code, message)
}

internal data class KnowledgeError(
    val code: String,
    val message: String,
)

internal const val INVALID_ARGUMENTS = "invalid_arguments"
internal const val INVALID_REGEX = "invalid_regex"
private const val KNOWLEDGE_NOT_FOUND = "knowledge_not_found"
private const val CONVERSATION_UNAVAILABLE = "conversation_unavailable"
private const val STORAGE_FAILURE = "storage_failure"
