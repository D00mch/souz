package ru.souz.backend.agent.session

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import ru.souz.agent.AgentId
import ru.souz.llms.LLMRequest

data class AgentConversationState(
    val userId: String,
    val chatId: UUID,
    val schemaVersion: Int,
    val activeAgentId: AgentId,
    val history: List<LLMRequest.Message>,
    val temperature: Float,
    val locale: Locale,
    val timeZone: ZoneId,
    val basedOnMessageSeq: Long,
    val updatedAt: Instant,
    val rowVersion: Long,
)
