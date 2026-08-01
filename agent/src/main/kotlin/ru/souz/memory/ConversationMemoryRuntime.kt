package ru.souz.memory

@JvmInline
value class MemoryOwnerId(val value: String)

fun interface MemoryOwnerProvider {
    fun currentOwnerId(): MemoryOwnerId
}

object LegacyMemoryOwnerProvider : MemoryOwnerProvider {
    override fun currentOwnerId(): MemoryOwnerId = MemoryOwnerId(LEGACY_OWNER_ID)
}

@JvmInline
value class MemorySessionId(val value: String)

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class ProjectId(val value: String)

data class MemoryContext(
    val ownerId: MemoryOwnerId,
    val conversationId: ConversationId?,
    val sessionId: MemorySessionId?,
    val projectId: ProjectId?,
)

fun legacyMemoryContext(): MemoryContext = MemoryContext(
    ownerId = MemoryOwnerId(LEGACY_OWNER_ID),
    conversationId = null,
    sessionId = null,
    projectId = null,
)

enum class RequestedMemoryScope {
    GLOBAL,
    PROJECT,
    CHAT,
    SESSION,
}

const val LEGACY_OWNER_ID: String = "local-legacy-owner"

enum class CompletedTurnEvidenceKind {
    TOOL_OUTPUT,
    ASSISTANT_SYNTHESIS,
}

data class CompletedTurnEvidence(
    val kind: CompletedTurnEvidenceKind,
    val sourceName: String? = null,
    val text: String,
)

/**
 * Completed conversation turn passed to memory capture after the assistant responds.
 */
data class CompletedTurnMemoryInput(
    val context: MemoryContext = legacyMemoryContext(),
    val conversationId: String?,
    val userMessageId: String?,
    val assistantMessageId: String?,
    val userMessage: String,
    val assistantMessage: String,
    val evidence: List<CompletedTurnEvidence> = emptyList(),
)

/**
 * Single fact reference included into the prompt augmentation.
 */
data class MemoryPromptFact(
    val factId: String,
    val scope: String,
    val score: Float,
)

data class MemoryRetrievalRequest(
    val context: MemoryContext,
    val query: String,
    val maxFacts: Int? = null,
    val maxPromptTokens: Int? = null,
)

data class MemoryRetrievalTrace(
    val candidateCountBySource: Map<String, Int> = emptyMap(),
    val selectedFactIds: List<String> = emptyList(),
    val promptTokenEstimate: Int = 0,
)

data class MemoryRetrievalResult(
    val renderedPromptBlock: String?,
    val facts: List<MemoryPromptFact> = emptyList(),
    val trace: MemoryRetrievalTrace = MemoryRetrievalTrace(),
)

object MemorySearchPolicy {
    const val DEFAULT_MAX_FACTS: Int = 8
    const val MAX_FACTS: Int = 16
    const val MAX_LEXICAL_HINTS: Int = 16
}

/**
 * Conversation-scoped entry point for prompt memory retrieval and post-turn capture.
 */
interface ConversationMemoryRuntime {
    data class SearchFact(
        val factId: String,
        val scope: String,
        val kind: String,
        val title: String,
        val body: String,
        val score: Float,
    )

    suspend fun retrieveMemory(
        request: MemoryRetrievalRequest,
    ): MemoryRetrievalResult = MemoryRetrievalResult(renderedPromptBlock = null)

    suspend fun searchMemory(
        context: MemoryContext,
        semanticQuery: String,
        lexicalHints: List<String>,
        maxFacts: Int,
    ): List<SearchFact> = emptyList()

    suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput)
}

/**
 * No-op runtime used when memory integration is disabled.
 */
object NoopConversationMemoryRuntime : ConversationMemoryRuntime {
    override suspend fun captureCompletedTurn(input: CompletedTurnMemoryInput) = Unit
}
