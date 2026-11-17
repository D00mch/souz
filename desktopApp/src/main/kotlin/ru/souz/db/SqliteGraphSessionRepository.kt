package ru.souz.db

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.llms.restJsonMapper

internal class SqliteGraphSessionRepository(
    dbPath: Path,
    private val clock: Clock = Clock.systemUTC(),
) : GraphSessionRepository {
    private val store = DesktopSqliteStore(dbPath)
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun save(session: GraphSession) {
        try {
            val payload = restJsonMapper.writeValueAsString(session)
            store.withConnection { connection ->
                connection.prepareStatement("""
                    insert into graph_sessions(id, start_time, saved_at, payload) values (?, ?, ?, ?)
                    on conflict(id) do update set start_time = excluded.start_time,
                        saved_at = excluded.saved_at, payload = excluded.payload
                """.trimIndent()).use {
                    it.setString(1, session.id)
                    it.setLong(2, session.startTime)
                    it.setLong(3, clock.millis())
                    it.setString(4, payload)
                    it.executeUpdate()
                }
            }
        } catch (error: Exception) {
            logger.error("Failed to save session: ${session.id}", error)
        }
    }

    override fun loadAll(): List<GraphSession> = try {
        store.withConnection { connection ->
            connection.prepareStatement("select payload from graph_sessions order by start_time desc, id").use {
                it.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            runCatching { restJsonMapper.readValue<GraphSession>(rows.getString(1)) }
                                .getOrNull()?.let(::add)
                        }
                    }
                }
            }
        }
    } catch (error: Exception) {
        logger.error("Failed to load sessions", error)
        emptyList()
    }

    override fun loadById(sessionId: String): GraphSession? = try {
        store.withConnection { connection ->
            connection.prepareStatement("select payload from graph_sessions where id = ?").use {
                it.setString(1, sessionId)
                it.executeQuery().use { rows ->
                    if (rows.next()) restJsonMapper.readValue<GraphSession>(rows.getString(1)) else null
                }
            }
        }
    } catch (error: Exception) {
        logger.error("Failed to load session: $sessionId", error)
        null
    }

    override fun delete(sessionId: String): Boolean = try {
        store.withConnection { connection ->
            connection.prepareStatement("delete from graph_sessions where id = ?").use {
                it.setString(1, sessionId)
                it.executeUpdate() > 0
            }
        }
    } catch (error: Exception) {
        logger.error("Failed to delete session: $sessionId", error)
        false
    }

    override fun count(): Int = store.withConnection { connection ->
        connection.createStatement().use {
            it.executeQuery("select count(*) from graph_sessions").use { rows -> rows.next(); rows.getInt(1) }
        }
    }

    fun clearAll() = store.withConnection { connection ->
        connection.createStatement().use { it.executeUpdate("delete from graph_sessions") }
    }

    fun deleteSavedBefore(cutoff: Instant) = store.withConnection { connection ->
        connection.prepareStatement("delete from graph_sessions where saved_at < ?").use {
            it.setLong(1, cutoff.toEpochMilli())
            it.executeUpdate()
        }
    }
}
