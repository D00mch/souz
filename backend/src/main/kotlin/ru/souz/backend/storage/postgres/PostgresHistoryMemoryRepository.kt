package ru.souz.backend.storage.postgres

import com.fasterxml.jackson.module.kotlin.readValue
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource
import ru.souz.backend.chat.model.ChatMessage
import ru.souz.backend.memory.hindsight.HistoryMemoryDocument
import ru.souz.backend.memory.hindsight.HistoryMemoryFragment
import ru.souz.backend.memory.hindsight.HistoryMemorySource
import ru.souz.backend.memory.hindsight.historyMemoryDocuments

internal class PostgresHistoryMemoryRepository(
    private val dataSource: DataSource,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Caller holds the chat lock and commits this with the message and request receipt. */
    fun enqueue(connection: Connection, message: ChatMessage) {
        val now = clock.instant()
        connection.prepareStatement(
            """
            insert into history_memory_fragments as f(id, user_id, chat_id, first_seq, source_ids, created_at, available_at)
            values (coalesce((select id from history_memory_fragments
              where chat_id = ? and attempts = 0 and completed_at is null
                and cardinality(source_ids) < 16 and available_at > ?
              order by first_seq desc limit 1 for update), ?), ?, ?, ?, array[?]::uuid[], ?, ?)
            on conflict (id) do update set source_ids = f.source_ids || excluded.source_ids,
              available_at = case when cardinality(f.source_ids) = 15 then excluded.created_at
                else least(f.created_at + interval '5 minutes', excluded.available_at) end
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, message.chatId)
            statement.setInstant(2, now)
            statement.setObject(3, UUID.randomUUID())
            statement.setString(4, message.userId)
            statement.setObject(5, message.chatId)
            statement.setLong(6, message.seq)
            statement.setObject(7, message.id)
            statement.setInstant(8, now)
            statement.setInstant(9, now.plusSeconds(30))
            statement.executeUpdate()
        }
    }

    suspend fun claim(): HistoryMemoryFragment? = dataSource.write { connection ->
        connection.prepareStatement(
            """
            update history_memory_fragments set attempts = attempts + 1, lease_token = ?, lease_until = ?
            where id = (
              select f.id from history_memory_fragments f
              where f.completed_at is null and f.available_at <= ? and (f.lease_until is null or f.lease_until <= ?)
                and not exists (select 1 from history_memory_fragments older
                  where older.chat_id = f.chat_id and older.first_seq < f.first_seq and older.completed_at is null)
              order by f.available_at, f.id limit 1 for update of f skip locked
            ) returning *
            """.trimIndent(),
        ).use { statement ->
            val now = clock.instant()
            statement.setObject(1, UUID.randomUUID())
            statement.setInstant(2, now.plusSeconds(180))
            statement.setInstant(3, now)
            statement.setInstant(4, now)
            statement.executeQuery().use { if (it.next()) it.fragment() else null }
        }
    }

    suspend fun documents(fragment: HistoryMemoryFragment): List<HistoryMemoryDocument> {
        fragment.documents?.let { return it }
        val documents = dataSource.read { connection ->
            connection.prepareStatement(
                """
                select m.*, m.seq >= f.first_seq as current_source, (select u.content from messages u
                  where u.user_id = m.user_id and u.chat_id = m.chat_id and u.role = 'user' and u.seq <= m.seq
                  order by u.seq desc limit 1) as user_intent
                from history_memory_fragments f join messages m on m.user_id = f.user_id and m.chat_id = f.chat_id
                where f.id = ? and m.id = any(f.source_ids || array(
                  select unnest(source_ids) from history_memory_fragments
                  where chat_id = f.chat_id and first_seq < f.first_seq order by first_seq desc limit 16
                )) order by m.seq
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, fragment.id)
                statement.executeQuery().use { rows ->
                    val sources = mutableListOf<HistoryMemorySource>()
                    val preceding = mutableListOf<HistoryMemorySource>()
                    while (rows.next()) {
                        val target = if (rows.getBoolean("current_source")) sources else preceding
                        target += HistoryMemorySource(
                            rows.getString("id"), rows.getLong("seq"), rows.getString("role"),
                            rows.getString("content"), rows.instant("created_at").toString(), rows.getString("user_intent"),
                        )
                    }
                    historyMemoryDocuments(fragment.id, sources, preceding)
                }
            }
        }
        check(update(fragment, "payload = ?") { it.setJson(1, postgresStorageMapper.writeValueAsString(documents)) }) {
            "History memory lease lost"
        }
        return documents
    }

    suspend fun renew(fragment: HistoryMemoryFragment): Boolean =
        update(fragment, "lease_until = ?") { it.setInstant(1, clock.instant().plusSeconds(180)) }

    suspend fun complete(fragment: HistoryMemoryFragment): Boolean =
        update(fragment, "completed_at = ?, lease_token = null, lease_until = null") { it.setInstant(1, clock.instant()) }

    suspend fun retry(fragment: HistoryMemoryFragment): Boolean =
        update(fragment, "available_at = ?, lease_token = null, lease_until = null") {
            it.setInstant(1, clock.instant().plusSeconds(minOf(300L, 5L shl minOf(fragment.attempts - 1, 6))))
        }

    private suspend fun update(
        fragment: HistoryMemoryFragment,
        assignment: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Boolean = dataSource.write { connection ->
        connection.prepareStatement(
            "update history_memory_fragments set $assignment where id = ? and lease_token = ? and lease_until > ?",
        ).use { statement ->
            bind(statement)
            statement.setObject(2, fragment.id)
            statement.setObject(3, fragment.leaseToken)
            statement.setInstant(4, clock.instant())
            statement.executeUpdate() == 1
        }
    }
}

private fun ResultSet.fragment(): HistoryMemoryFragment = HistoryMemoryFragment(
    id = getObject("id", UUID::class.java),
    userId = getString("user_id"),
    chatId = getObject("chat_id", UUID::class.java),
    leaseToken = getObject("lease_token", UUID::class.java),
    attempts = getInt("attempts"),
    documents = getString("payload")?.let { postgresStorageMapper.readValue(it) },
)
