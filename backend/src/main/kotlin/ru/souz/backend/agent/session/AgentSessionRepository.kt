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
        stateRepository.get(key.userId, key.chatId())?.toLegacySession()

    override suspend fun save(key: AgentConversationKey, session: AgentConversationSession) {
        val currentState = stateRepository.get(key.userId, key.chatId())
        stateRepository.save(
            AgentConversationState(
                userId = key.userId,
                chatId = key.chatId(),
                schemaVersion = currentState?.schemaVersion ?: DEFAULT_SCHEMA_VERSION,
                activeAgentId = session.activeAgentId,
                history = session.history,
                temperature = session.temperature,
                locale = session.locale.toLocale(),
                timeZone = session.timeZone.toZoneId(),
                basedOnMessageSeq = currentState?.basedOnMessageSeq ?: 0L,
                updatedAt = Instant.now(),
                rowVersion = currentState?.rowVersion ?: 0L,
            )
        )
    }
}

/** Legacy-compatible in-memory implementation backed by the new agent state repository. */
class InMemoryAgentSessionRepository(
    stateRepository: AgentStateRepository = MemoryAgentStateRepository(),
) : AgentSessionRepository by AgentStateBackedSessionRepository(stateRepository)

private fun AgentConversationKey.chatId(): UUID = UUID.fromString(conversationId)

private fun AgentConversationState.toLegacySession(): AgentConversationSession =
    AgentConversationSession(
        activeAgentId = activeAgentId,
        history = history,
        temperature = temperature,
        locale = locale.languageTagOrDefault(),
        timeZone = timeZone.id,
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
