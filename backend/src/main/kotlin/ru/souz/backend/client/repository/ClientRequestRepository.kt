package ru.souz.backend.client.repository

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.execution.model.AgentExecution

data class ClientRequestKey(
    val chatId: UUID,
    val requestId: String,
    val kind: String,
    val payloadHash: String,
)

data class ClientFollowUpInput(
    val content: String,
    val metadata: Map<String, String>,
    val latestDeviceContextJson: String,
    val messageId: UUID = UUID.randomUUID(),
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)

data class ClientHistoryInput(
    val role: ChatRole,
    val content: String,
    val toolArgumentsJson: String? = null,
    val messageId: UUID = UUID.randomUUID(),
    val createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS),
)

sealed interface ClientRequestResult {
    data class Accepted(
        val request: ClientRequest,
        val execution: AgentExecution,
        val messageDelta: List<ChatMessage> = emptyList(),
    ) : ClientRequestResult

    data class HistoryAccepted(val request: ClientRequest) : ClientRequestResult

    data class Duplicate(val request: ClientRequest) : ClientRequestResult
    data class Rejected(val request: ClientRequest) : ClientRequestResult
    data object Conflict : ClientRequestResult
    data class Continue(val execution: AgentExecution) : ClientRequestResult
    data object CreateThread : ClientRequestResult
}

interface ClientRequestRepository {
    suspend fun resolveMessage(
        userId: String,
        key: ClientRequestKey,
        requestedThreadId: UUID?,
        newExecution: AgentExecution? = null,
        acceptedRequest: ClientRequest? = null,
        rejectedRequest: (AgentExecution?) -> ClientRequest,
    ): ClientRequestResult

    suspend fun commitFollowUp(
        userId: String,
        key: ClientRequestKey,
        threadId: UUID,
        afterSeq: Long,
        input: ClientFollowUpInput?,
        acceptedRequest: (Long) -> ClientRequest,
        rejectedRequest: (AgentExecution?) -> ClientRequest,
    ): ClientRequestResult

    suspend fun commitHistory(
        userId: String,
        key: ClientRequestKey,
        input: ClientHistoryInput,
        acceptedRequest: ClientRequest,
    ): ClientRequestResult

    suspend fun cancel(
        userId: String,
        key: ClientRequestKey,
        threadId: UUID,
        runtimeAvailable: Boolean,
        acceptedRequest: ClientRequest,
        rejectedRequest: (AgentExecution?) -> ClientRequest,
    ): ClientRequestResult
}
