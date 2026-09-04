package ru.souz.backend.storage.postgres

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientFollowUpInput
import ru.souz.backend.client.repository.ClientHistoryInput
import ru.souz.backend.client.repository.ClientRequestKey
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.client.repository.ClientRequestResult
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.execution.model.acceptsInput

class PostgresClientRequestRepository(
    private val dataSource: DataSource,
) : ClientRequestRepository {
    private val executionWriter = PostgresAgentExecutionRepository(dataSource)
    private val messageWriter = PostgresMessageRepository(dataSource)

    internal suspend fun get(chatId: UUID, requestId: String): ClientRequest? = dataSource.read { connection ->
        connection.findClientRequest(chatId, requestId)
    }

    override suspend fun resolveMessage(
        userId: String,
        key: ClientRequestKey,
        requestedThreadId: UUID?,
        newExecution: AgentExecution?,
        acceptedRequest: ClientRequest?,
        rejectedRequest: (AgentExecution?) -> ClientRequest,
    ): ClientRequestResult = serialize(userId, key) {
        val execution = if (requestedThreadId != null) {
            findExecution(userId, key.chatId, requestedThreadId)
        } else {
            findActiveExecution(userId, key.chatId)
        }
        when {
            execution != null && execution.status.acceptsInput() -> ClientRequestResult.Continue(execution)
            execution != null -> reject(key, execution, rejectedRequest)
            requestedThreadId != null -> reject(key, null, rejectedRequest)
            newExecution == null -> ClientRequestResult.CreateThread
            else -> {
                val request = requireNotNull(acceptedRequest) { "Accepted request is required for a new execution." }
                require(newExecution.userId == userId && newExecution.chatId == key.chatId)
                require(request.threadId == newExecution.id)
                request.requireKey(key)
                executionWriter.insert(this, newExecution)
                insertClientRequest(this, request)
                ClientRequestResult.Accepted(request, newExecution)
            }
        }
    }

    override suspend fun commitFollowUp(
        userId: String,
        key: ClientRequestKey,
        threadId: UUID,
        afterSeq: Long,
        input: ClientFollowUpInput?,
        acceptedRequest: (Long) -> ClientRequest,
        rejectedRequest: (AgentExecution?) -> ClientRequest,
    ): ClientRequestResult = serialize(userId, key) {
        val execution = findExecution(userId, key.chatId, threadId)
        if (execution == null || !execution.status.acceptsInput() || input == null) {
            reject(key, execution, rejectedRequest)
        } else {
            val revision = execution.revision + 1
            val request = acceptedRequest(revision).also {
                it.requireKey(key)
                require(it.threadId == threadId)
            }
            val updatedExecution = executionWriter.update(
                this,
                execution.copy(revision = revision, latestDeviceContextJson = input.latestDeviceContextJson),
            )
            val message = messageWriter.append(
                connection = this,
                userId = userId,
                chatId = key.chatId,
                role = ChatRole.USER,
                content = input.content,
                metadata = input.metadata + ("inputSeq" to revision.toString()),
                id = input.messageId,
                createdAt = input.createdAt,
            )
            val messageDelta = listMessages(
                userId = userId,
                chatId = key.chatId,
                afterSeq = afterSeq,
                throughSeq = message.seq,
            )
            insertClientRequest(this, request)
            ClientRequestResult.Accepted(request, updatedExecution, messageDelta)
        }
    }

    override suspend fun commitHistory(
        userId: String,
        key: ClientRequestKey,
        input: ClientHistoryInput,
        acceptedRequest: ClientRequest,
    ): ClientRequestResult = serialize(userId, key) {
        acceptedRequest.requireKey(key)
        require(acceptedRequest.threadId == null)
        messageWriter.append(
            connection = this,
            userId = userId,
            chatId = key.chatId,
            role = input.role,
            content = input.content,
            metadata = input.metadata,
            id = input.messageId,
            createdAt = input.createdAt,
        )
        insertClientRequest(this, acceptedRequest)
        ClientRequestResult.HistoryAccepted(acceptedRequest)
    }

    override suspend fun cancel(
        userId: String,
        key: ClientRequestKey,
        threadId: UUID,
        runtimeAvailable: Boolean,
        acceptedRequest: ClientRequest,
        rejectedRequest: (AgentExecution?) -> ClientRequest,
    ): ClientRequestResult = serialize(userId, key) {
        val execution = findExecution(userId, key.chatId, threadId)
        if (execution == null || !execution.status.acceptsInput() || !runtimeAvailable) {
            reject(key, execution, rejectedRequest)
        } else {
            acceptedRequest.requireKey(key)
            require(acceptedRequest.threadId == threadId)
            val cancelling = executionWriter.update(
                this,
                execution.copy(status = AgentExecutionStatus.CANCELLING, cancelRequested = true),
            )
            insertClientRequest(this, acceptedRequest)
            ClientRequestResult.Accepted(acceptedRequest, cancelling)
        }
    }

    private suspend fun serialize(
        userId: String,
        key: ClientRequestKey,
        mutation: Connection.() -> ClientRequestResult,
    ): ClientRequestResult = dataSource.write { connection ->
        connection.lockChat(userId, key.chatId)
        connection.findClientRequest(key.chatId, key.requestId)?.replay(key) ?: connection.mutation()
    }
}

private fun Connection.listMessages(
    userId: String,
    chatId: UUID,
    afterSeq: Long,
    throughSeq: Long,
): List<ChatMessage> = buildList {
    var cursor = afterSeq
    while (cursor < throughSeq) {
        val page = prepareStatement(
            """
            select * from messages
            where user_id = ? and chat_id = ? and seq > ? and seq <= ?
            order by seq asc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setLong(3, cursor)
            statement.setLong(4, throughSeq)
            statement.setInt(5, MESSAGE_DELTA_PAGE_SIZE)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) add(resultSet.toMessage())
                }
            }
        }
        if (page.isEmpty()) break
        addAll(page)
        cursor = page.last().seq
    }
}

private const val MESSAGE_DELTA_PAGE_SIZE = 500

private fun Connection.findClientRequest(chatId: UUID, requestId: String): ClientRequest? =
    prepareStatement("select * from client_requests where chat_id = ? and request_id = ?").use { statement ->
        statement.setObject(1, chatId)
        statement.setString(2, requestId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toClientRequest() else null
        }
    }

private fun Connection.findExecution(userId: String, chatId: UUID, threadId: UUID): AgentExecution? =
    prepareStatement(
        "select * from agent_executions where user_id = ? and chat_id = ? and id = ? for update"
    ).use { statement ->
        statement.setString(1, userId)
        statement.setObject(2, chatId)
        statement.setObject(3, threadId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toExecution() else null
        }
    }

private fun Connection.findActiveExecution(userId: String, chatId: UUID): AgentExecution? =
    prepareStatement(
        """
        select * from agent_executions
        where user_id = ? and chat_id = ?
          and status in ('queued', 'running', 'waiting_option', 'cancelling')
        order by started_at desc
        limit 1
        for update
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, userId)
        statement.setObject(2, chatId)
        statement.executeQuery().use { resultSet ->
            if (resultSet.next()) resultSet.toExecution() else null
        }
    }

private fun Connection.reject(
    key: ClientRequestKey,
    execution: AgentExecution?,
    requestFactory: (AgentExecution?) -> ClientRequest,
): ClientRequestResult.Rejected {
    val request = requestFactory(execution).also { it.requireKey(key) }
    insertClientRequest(this, request)
    return ClientRequestResult.Rejected(request)
}

private fun ClientRequest.replay(key: ClientRequestKey): ClientRequestResult =
    if (kind == key.kind && payloadHash == key.payloadHash) {
        ClientRequestResult.Duplicate(this)
    } else {
        ClientRequestResult.Conflict
    }

private fun ClientRequest.requireKey(key: ClientRequestKey) {
    require(chatId == key.chatId && requestId == key.requestId && kind == key.kind && payloadHash == key.payloadHash)
}

private fun ResultSet.toClientRequest(): ClientRequest = ClientRequest(
    chatId = getObject("chat_id", UUID::class.java),
    requestId = getString("request_id"),
    kind = getString("kind"),
    threadId = getObject("thread_id", UUID::class.java),
    payloadHash = getString("payload_hash"),
    ackJson = getString("ack_json"),
    receivedAt = instant("received_at"),
)

private fun insertClientRequest(connection: Connection, request: ClientRequest) {
    connection.prepareStatement(
        """
        insert into client_requests(
          chat_id, request_id, kind, thread_id, payload_hash, ack_json, received_at
        ) values (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
    ).use { statement ->
        statement.setObject(1, request.chatId)
        statement.setString(2, request.requestId)
        statement.setString(3, request.kind)
        statement.setObject(4, request.threadId)
        statement.setString(5, request.payloadHash)
        statement.setJson(6, request.ackJson)
        statement.setInstant(7, request.receivedAt)
        statement.executeUpdate()
    }
}
