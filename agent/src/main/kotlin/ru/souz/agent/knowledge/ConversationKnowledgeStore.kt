package ru.souz.agent.knowledge

import java.util.UUID
import ru.souz.llms.ToolInvocationMeta

/** Conversation-scoped immutable storage for large, temporarily retained tool results. */
interface ConversationKnowledgeStore {
    suspend fun put(
        meta: ToolInvocationMeta,
        sourceTool: String,
        content: String,
    ): KnowledgeWriteResult

    suspend fun get(
        meta: ToolInvocationMeta,
        knowledgeId: String,
    ): KnowledgeEntry?

    suspend fun clearConversation(meta: ToolInvocationMeta)
}

sealed interface KnowledgeWriteResult {
    data class Stored(val entry: KnowledgeEntry) : KnowledgeWriteResult

    data object ConversationUnavailable : KnowledgeWriteResult
}

/**
 * Stored Knowledge metadata and retained content. Lengths use Kotlin [String] UTF-16 code units.
 *
 * [KnowledgeContent.Truncated] keeps the beginning and end in their original order and omits
 * a non-empty middle. Choosing those boundaries is a storage implementation detail.
 */
data class KnowledgeEntry(
    val id: String,
    val sourceTool: String,
    val originalLength: Int,
    val content: KnowledgeContent,
) {
    init {
        requireCanonicalUuid(id)
        require(sourceTool.isNotBlank()) { "Knowledge source tool must not be blank." }
        require(originalLength >= 0) { "Knowledge original length must not be negative." }
        when (content) {
            is KnowledgeContent.Complete -> require(content.content.length == originalLength) {
                "Complete Knowledge content length must equal its original length."
            }

            is KnowledgeContent.Truncated -> {
                require(content.head.isNotEmpty() && content.tail.isNotEmpty()) {
                    "Truncated Knowledge must retain a non-empty head and tail."
                }
                require(content.head.length.toLong() + content.tail.length < originalLength.toLong()) {
                    "Truncated Knowledge must omit a non-empty middle."
                }
            }
        }
    }

    val storedLength: Int
        get() = when (content) {
            is KnowledgeContent.Complete -> content.content.length
            is KnowledgeContent.Truncated -> content.head.length + content.tail.length
        }
}

/**
 * Retained Knowledge content.
 *
 * A truncated value contains the original beginning in [Truncated.head] and original end in
 * [Truncated.tail]. The storage implementation chooses whole-code-point boundaries and omits the
 * non-empty middle; callers do not need to calculate or track offsets.
 */
sealed interface KnowledgeContent {
    data class Complete(
        val content: String,
    ) : KnowledgeContent

    data class Truncated(
        val head: String,
        val tail: String,
    ) : KnowledgeContent
}

sealed class KnowledgeStoreException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class KnowledgeStoreUnavailableException(message: String) : KnowledgeStoreException(message)

class KnowledgeStorePersistenceException(
    message: String,
    cause: Throwable? = null,
) : KnowledgeStoreException(message, cause)

class KnowledgeStoreCorruptionException(
    message: String,
    cause: Throwable? = null,
) : KnowledgeStoreException(message, cause)

private fun requireCanonicalUuid(rawId: String) {
    val canonical = runCatching { UUID.fromString(rawId).toString() }.getOrNull()
    require(canonical == rawId) { "Knowledge ID must be a canonical UUID." }
}
