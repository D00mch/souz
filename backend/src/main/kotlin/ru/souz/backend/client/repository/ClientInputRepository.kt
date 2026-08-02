package ru.souz.backend.client.repository

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import ru.souz.backend.execution.model.AgentExecution

interface ClientInputRepository {
    suspend fun appendFollowUpInput(
        execution: AgentExecution,
        content: String,
        metadata: Map<String, String>,
        latestDeviceContextJson: String,
        messageId: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
    ): AgentExecution?
}
