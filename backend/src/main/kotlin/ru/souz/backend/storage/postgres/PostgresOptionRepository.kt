package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionAnswer
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.options.repository.OptionAnswerUpdateResult
import ru.souz.backend.options.repository.OptionRepository

class PostgresOptionRepository(
    private val dataSource: DataSource,
) : OptionRepository {
    override suspend fun save(option: Option): Option = dataSource.write { connection ->
        connection.ensureUser(option.userId)
        connection.prepareStatement(
            """
            insert into options(
                id,
                user_id,
                chat_id,
                execution_id,
                kind,
                title,
                selection_mode,
                options_json,
                payload_json,
                status,
                answer_json,
                created_at,
                expires_at,
                answered_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (id) do update
            set user_id = excluded.user_id,
                chat_id = excluded.chat_id,
                execution_id = excluded.execution_id,
                kind = excluded.kind,
                title = excluded.title,
                selection_mode = excluded.selection_mode,
                options_json = excluded.options_json,
                payload_json = excluded.payload_json,
                status = excluded.status,
                answer_json = excluded.answer_json,
                created_at = excluded.created_at,
                expires_at = excluded.expires_at,
                answered_at = excluded.answered_at
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, option.id)
            statement.setString(2, option.userId)
            statement.setObject(3, option.chatId)
            statement.setObject(4, option.executionId)
            statement.setString(5, option.kind.value)
            statement.setString(6, option.title)
            statement.setString(7, option.selectionMode)
            statement.setJson(8, postgresStorageMapper.writeValueAsString(option.options))
            statement.setJson(9, postgresStorageMapper.writeValueAsString(option.payload))
            statement.setString(10, option.status.value)
            statement.setJson(11, option.answer?.toStoredJson())
            statement.setInstant(12, option.createdAt)
            statement.setInstant(13, option.expiresAt)
            statement.setInstant(14, option.answeredAt)
            statement.executeUpdate()
        }
        option
    }

    override suspend fun get(userId: String, optionId: UUID): Option? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from options where user_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, optionId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toOption() else null
            }
        }
    }

    override suspend fun answerPending(
        userId: String,
        optionId: UUID,
        answer: OptionAnswer,
        answeredAt: Instant,
    ): OptionAnswerUpdateResult = dataSource.write { connection ->
        val updated = connection.prepareStatement(
            """
            update options
            set status = ?, answer_json = ?, answered_at = ?
            where user_id = ? and id = ? and status = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, OptionStatus.ANSWERED.value)
            statement.setJson(2, answer.toStoredJson())
            statement.setInstant(3, answeredAt)
            statement.setString(4, userId)
            statement.setObject(5, optionId)
            statement.setString(6, OptionStatus.PENDING.value)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toOption() else null
            }
        }
        when {
            updated != null -> OptionAnswerUpdateResult.Updated(updated)
            else -> {
                val current = connection.prepareStatement(
                    "select * from options where user_id = ? and id = ?"
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setObject(2, optionId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.toOption() else null
                    }
                }
                if (current == null) {
                    OptionAnswerUpdateResult.NotFound
                } else {
                    OptionAnswerUpdateResult.NotPending(current)
                }
            }
        }
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Option> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from options
            where user_id = ? and chat_id = ? and execution_id = ?
            order by created_at desc
            limit ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, chatId)
            statement.setObject(3, executionId)
            statement.setInt(4, limit)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.toOption())
                    }
                }
            }
        }
    }
}
