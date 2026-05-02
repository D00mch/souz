package ru.souz.backend.agent.session

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import ru.souz.agent.AgentId
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.storage.memory.MemoryAgentStateRepository
import ru.souz.llms.LLMRequest

/** Persisted backend conversation snapshot used to resume the next agent turn. */
data class AgentConversationSession(
    val activeAgentId: AgentId,
    val history: List<LLMRequest.Message>,
    val temperature: Float,
    val locale: String,
    val timeZone: String,
    val basedOnMessageSeq: Long = 0L,
    val rowVersion: Long = 0L,
)

/** Storage contract for per-conversation backend agent state. */
interface AgentSessionRepository {
    suspend fun load(key: AgentConversationKey): AgentConversationSession?
    suspend fun save(key: AgentConversationKey, session: AgentConversationSession)
}

class AgentStateBackedSessionRepository(
    private val stateRepository: AgentStateRepository,
) : AgentSessionRepository {
    override suspend fun load(key: AgentConversationKey): AgentConversationSession? =
        stateRepository.get(key.userId, key.chatId())?.toConversationSession()

    override suspend fun save(key: AgentConversationKey, session: AgentConversationSession) {
        stateRepository.save(
            AgentConversationState(
                userId = key.userId,
                chatId = key.chatId(),
                schemaVersion = DEFAULT_SCHEMA_VERSION,
                activeAgentId = session.activeAgentId,
                history = session.history,
                temperature = session.temperature,
                locale = session.locale.toLocale(),
                timeZone = session.timeZone.toZoneId(),
                basedOnMessageSeq = session.basedOnMessageSeq,
                updatedAt = Instant.now(),
                rowVersion = session.rowVersion,
            )
        )
    }
}

/** In-memory implementation backed by the current agent state repository. */
class InMemoryAgentSessionRepository(
    stateRepository: AgentStateRepository = MemoryAgentStateRepository(),
) : AgentSessionRepository by AgentStateBackedSessionRepository(stateRepository)

private fun AgentConversationKey.chatId(): UUID = UUID.fromString(conversationId)

private fun AgentConversationState.toConversationSession(): AgentConversationSession =
    AgentConversationSession(
        activeAgentId = activeAgentId,
        history = history,
        temperature = temperature,
        locale = locale.languageTagOrDefault(),
        timeZone = timeZone.id,
        basedOnMessageSeq = basedOnMessageSeq,
        rowVersion = rowVersion,
    )

private fun String.toLocale(): Locale =
    Locale.forLanguageTag(this)
        .takeIf { it.language.isNotBlank() }
        ?: DEFAULT_LOCALE

private fun String.toZoneId(): ZoneId =
    runCatching { ZoneId.of(this) }.getOrDefault(ZoneId.systemDefault())

private fun Locale.languageTagOrDefault(): String =
    toLanguageTag().takeIf { it.isNotBlank() } ?: DEFAULT_LOCALE.toLanguageTag()

private const val DEFAULT_SCHEMA_VERSION: Int = 1
private val DEFAULT_LOCALE: Locale = Locale.forLanguageTag("ru-RU")
