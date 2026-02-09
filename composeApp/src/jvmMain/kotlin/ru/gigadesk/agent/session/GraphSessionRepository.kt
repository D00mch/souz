package ru.gigadesk.agent.session

import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.gigadesk.giga.gigaJsonMapper
import ru.gigadesk.tool.files.FilesToolUtil
import java.io.File

/**
 * Stores and loads graph sessions
 * TODO: rewrite with SQLite
 */
class GraphSessionRepository {
    private val l = LoggerFactory.getLogger(GraphSessionRepository::class.java)
    private val sessionsDir: File by lazy {
        File(FilesToolUtil.homeDirectory, "${FilesToolUtil.homeStr}/.local/state/gigadesk/").apply { mkdirs() }
    }

    fun save(session: GraphSession) {
        try {
            val file = File(sessionsDir, "${session.id}.json")
            gigaJsonMapper.writerWithDefaultPrettyPrinter().writeValue(file, session)
            l.info("Session saved: ${session.id}")
        } catch (e: Exception) {
            l.error("Failed to save session: ${session.id}", e)
        }
    }

    /** Fetches sessions. New first. */
    fun loadAll(): List<GraphSession> {
        return try {
            sessionsDir.listFiles { _, name -> name.endsWith(".json") }
                ?.mapNotNull { file ->
                    runCatching { gigaJsonMapper.readValue<GraphSession>(file) }.getOrNull()
                }
                ?.sortedByDescending { it.startTime }
                ?: emptyList()
        } catch (e: Exception) {
            l.error("Failed to load sessions", e)
            emptyList()
        }
    }

    fun loadById(sessionId: String): GraphSession? {
        return try {
            val file = File(sessionsDir, "$sessionId.json")
            if (file.exists()) {
                gigaJsonMapper.readValue<GraphSession>(file)
            } else null
        } catch (e: Exception) {
            l.error("Failed to load session: $sessionId", e)
            null
        }
    }

    fun delete(sessionId: String): Boolean {
        return try {
            File(sessionsDir, "$sessionId.json").delete()
        } catch (e: Exception) {
            l.error("Failed to delete session: $sessionId", e)
            false
        }
    }

    /** @return Stored sessions count */
    fun count(): Int {
        return sessionsDir.listFiles { _, name -> name.endsWith(".json") }?.size ?: 0
    }
}
