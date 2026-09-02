package ru.souz.backend.client.repository

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.execution.model.AgentExecution

sealed interface FollowUpInputResult {
    data class Accepted(val execution: AgentExecution) : FollowUpInputResult

    data class Rejected(
        val reason: RejectionReason,
        val currentExecution: AgentExecution?,
    ) : FollowUpInputResult

    enum class RejectionReason {
        EXECUTION_NOT_FOUND,
        NOT_ACCEPTING_INPUT,
        REVISION_MISMATCH,
    }
}

interface ClientInputRepository {
    suspend fun appendFollowUpInput(
        execution: AgentExecution,
        request: ClientRequest,
        content: String,
        metadata: Map<String, String>,
        latestDeviceContextJson: String,
        messageId: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
    ): FollowUpInputResult
}
