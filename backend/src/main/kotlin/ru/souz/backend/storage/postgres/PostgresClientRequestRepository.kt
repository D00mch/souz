package ru.souz.backend.storage.postgres

import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.client.model.ClientRequest
import ru.souz.backend.client.repository.ClientRequestRepository
import ru.souz.backend.execution.model.AgentExecution

class PostgresClientRequestRepository(
    private val dataSource: DataSource,
) : ClientRequestRepository {
    private val executionWriter = PostgresAgentExecutionRepository(dataSource)

    override suspend fun create(request: ClientRequest): ClientRequest = dataSource.write { connection ->
        insert(connection, request)
    }

    private fun insert(connection: java.sql.Connection, request: ClientRequest): ClientRequest {
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
        return request
    }

    override suspend fun createWithExecution(
        execution: AgentExecution,
        request: ClientRequest,
    ): AgentExecution = dataSource.write { connection ->
        require(request.chatId == execution.chatId) { "Client request and execution must belong to the same chat." }
        require(request.threadId == execution.id) { "Client request must reference the created execution." }
        executionWriter.insert(connection, execution)
        insert(connection, request)
        execution
    }

    override suspend fun get(chatId: UUID, requestId: String): ClientRequest? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from client_requests where chat_id = ? and request_id = ?"
        ).use { statement ->
            statement.setObject(1, chatId)
            statement.setString(2, requestId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) return@read null
                ClientRequest(
                    chatId = resultSet.getObject("chat_id", UUID::class.java),
                    requestId = resultSet.getString("request_id"),
                    kind = resultSet.getString("kind"),
                    threadId = resultSet.getObject("thread_id", UUID::class.java),
                    payloadHash = resultSet.getString("payload_hash"),
                    ackJson = resultSet.getString("ack_json"),
                    receivedAt = resultSet.instant("received_at"),
                )
            }
        }
    }
}
