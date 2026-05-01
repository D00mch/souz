package ru.souz.backend.storage.postgres

import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceAnswer
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.choices.repository.ChoiceAnswerUpdateResult
import ru.souz.backend.choices.repository.ChoiceRepository

class PostgresChoiceRepository(
    private val dataSource: DataSource,
) : ChoiceRepository {
    override suspend fun save(choice: Choice): Choice = dataSource.write { connection ->
        connection.ensureUser(choice.userId)
        connection.prepareStatement(
            """
            insert into choices(
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
            statement.setObject(1, choice.id)
            statement.setString(2, choice.userId)
            statement.setObject(3, choice.chatId)
            statement.setObject(4, choice.executionId)
            statement.setString(5, choice.kind.value)
            statement.setString(6, choice.title)
            statement.setString(7, choice.selectionMode)
            statement.setJson(8, postgresStorageMapper.writeValueAsString(choice.options))
            statement.setJson(9, postgresStorageMapper.writeValueAsString(choice.payload))
            statement.setString(10, choice.status.value)
            statement.setJson(11, choice.answer?.toStoredJson())
            statement.setInstant(12, choice.createdAt)
            statement.setInstant(13, choice.expiresAt)
            statement.setInstant(14, choice.answeredAt)
            statement.executeUpdate()
        }
        choice
    }

    override suspend fun get(userId: String, choiceId: UUID): Choice? = dataSource.read { connection ->
        connection.prepareStatement(
            "select * from choices where user_id = ? and id = ?"
        ).use { statement ->
            statement.setString(1, userId)
            statement.setObject(2, choiceId)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChoice() else null
            }
        }
    }

    override suspend fun answerPending(
        userId: String,
        choiceId: UUID,
        answer: ChoiceAnswer,
        answeredAt: Instant,
    ): ChoiceAnswerUpdateResult = dataSource.write { connection ->
        val updated = connection.prepareStatement(
            """
            update choices
            set status = ?, answer_json = ?, answered_at = ?
            where user_id = ? and id = ? and status = ?
            returning *
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, ChoiceStatus.ANSWERED.value)
            statement.setJson(2, answer.toStoredJson())
            statement.setInstant(3, answeredAt)
            statement.setString(4, userId)
            statement.setObject(5, choiceId)
            statement.setString(6, ChoiceStatus.PENDING.value)
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.toChoice() else null
            }
        }
        when {
            updated != null -> ChoiceAnswerUpdateResult.Updated(updated)
            else -> {
                val current = connection.prepareStatement(
                    "select * from choices where user_id = ? and id = ?"
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setObject(2, choiceId)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) resultSet.toChoice() else null
                    }
                }
                if (current == null) {
                    ChoiceAnswerUpdateResult.NotFound
                } else {
                    ChoiceAnswerUpdateResult.NotPending(current)
                }
            }
        }
    }

    override suspend fun listByExecution(
        userId: String,
        chatId: UUID,
        executionId: UUID,
        limit: Int,
    ): List<Choice> = dataSource.read { connection ->
        connection.prepareStatement(
            """
            select * from choices
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
                        add(resultSet.toChoice())
                    }
                }
            }
        }
    }
}
