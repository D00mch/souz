package ru.souz.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager

class TelemetryOutboxRepository(
    private val databasePath: Path,
    private val objectMapper: ObjectMapper,
    private val maxRows: Int = DEFAULT_MAX_ROWS,
) {
    private val l = LoggerFactory.getLogger(TelemetryOutboxRepository::class.java)
    private val lock = Any()
    private val jdbcUrl = "jdbc:sqlite:${databasePath.toAbsolutePath()}"

    init {
        Class.forName("org.sqlite.JDBC")
        Files.createDirectories(databasePath.toAbsolutePath().parent)
        connection().use { conn ->
            conn.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS telemetry_outbox (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        event_id TEXT NOT NULL UNIQUE,
                        event_type TEXT NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        payload_json TEXT NOT NULL,
                        attempt_count INTEGER NOT NULL DEFAULT 0,
                        next_attempt_at_ms INTEGER NOT NULL,
                        last_error TEXT
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_telemetry_outbox_ready
                    ON telemetry_outbox(next_attempt_at_ms, id)
                    """.trimIndent()
                )
            }
        }
    }

    fun enqueue(events: List<TelemetryEvent>) {
        if (events.isEmpty()) return
        synchronized(lock) {
            connection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        """
                        INSERT OR IGNORE INTO telemetry_outbox(
                            event_id,
                            event_type,
                            created_at_ms,
                            payload_json,
                            next_attempt_at_ms
                        ) VALUES (?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        events.forEach { event ->
                            statement.setString(1, event.eventId)
                            statement.setString(2, event.type)
                            statement.setLong(3, event.occurredAtMs)
                            statement.setString(4, objectMapper.writeValueAsString(event))
                            statement.setLong(5, event.occurredAtMs)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    trimOverflow(conn)
                    conn.commit()
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                    l.error("Failed to enqueue telemetry events", e)
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }
        }
    }

    fun loadReadyBatch(limit: Int, nowMs: Long = System.currentTimeMillis()): List<QueuedTelemetryEvent> =
        synchronized(lock) {
            connection().use { conn ->
                conn.prepareStatement(
                    """
                    SELECT id, payload_json, attempt_count
                    FROM telemetry_outbox
                    WHERE next_attempt_at_ms <= ?
                    ORDER BY created_at_ms ASC, id ASC
                    LIMIT ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setLong(1, nowMs)
                    statement.setInt(2, limit)
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    QueuedTelemetryEvent(
                                        rowId = resultSet.getLong("id"),
                                        event = objectMapper.readValue(
                                            resultSet.getString("payload_json"),
                                            TelemetryEvent::class.java,
                                        ),
                                        attemptCount = resultSet.getInt("attempt_count"),
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

    fun markFailed(rowIds: List<Long>, nextAttemptAtMs: Long, errorMessage: String?) {
        if (rowIds.isEmpty()) return
        synchronized(lock) {
            connection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        """
                        UPDATE telemetry_outbox
                        SET attempt_count = attempt_count + 1,
                            next_attempt_at_ms = ?,
                            last_error = ?
                        WHERE id = ?
                        """.trimIndent()
                    ).use { statement ->
                        rowIds.forEach { rowId ->
                            statement.setLong(1, nextAttemptAtMs)
                            statement.setString(2, errorMessage)
                            statement.setLong(3, rowId)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                    l.error("Failed to mark telemetry batch as failed", e)
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }
        }
    }

    fun delete(rowIds: List<Long>) {
        if (rowIds.isEmpty()) return
        synchronized(lock) {
            connection().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement("DELETE FROM telemetry_outbox WHERE id = ?").use { statement ->
                        rowIds.forEach { rowId ->
                            statement.setLong(1, rowId)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    runCatching { conn.rollback() }
                    l.error("Failed to delete delivered telemetry rows", e)
                } finally {
                    runCatching { conn.autoCommit = true }
                }
            }
        }
    }

    fun pendingCount(): Int = synchronized(lock) {
        connection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM telemetry_outbox").use { statement ->
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) resultSet.getInt(1) else 0
                }
            }
        }
    }

    private fun trimOverflow(conn: Connection) {
        conn.prepareStatement("SELECT COUNT(*) FROM telemetry_outbox").use { countStatement ->
            countStatement.executeQuery().use { resultSet ->
                val totalRows = if (resultSet.next()) resultSet.getInt(1) else 0
                val rowsToDelete = totalRows - maxRows
                if (rowsToDelete <= 0) return

                conn.prepareStatement(
                    """
                    DELETE FROM telemetry_outbox
                    WHERE id IN (
                        SELECT id
                        FROM telemetry_outbox
                        ORDER BY created_at_ms ASC, id ASC
                        LIMIT ?
                    )
                    """.trimIndent()
                ).use { deleteStatement ->
                    deleteStatement.setInt(1, rowsToDelete)
                    deleteStatement.executeUpdate()
                }
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(jdbcUrl)

    companion object {
        private const val DEFAULT_MAX_ROWS = 10_000
    }
}
