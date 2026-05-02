package ru.souz.agent.session

import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import ru.souz.llms.restJsonMapper
import ru.souz.paths.DefaultSouzPaths
import ru.souz.paths.SouzPaths
import kotlin.streams.asSequence

/**
 * Stores and loads graph sessions
 * TODO: rewrite with SQLite
 */
class GraphSessionRepository(
    private val paths: SouzPaths = DefaultSouzPaths(),
) {
    private val l = LoggerFactory.getLogger(GraphSessionRepository::class.java)
    private val sessionsDir: Path by lazy {
        Files.createDirectories(paths.stateRoot)
        Files.createDirectories(paths.sessionsDir)
        paths.sessionsDir
    }

    fun save(session: GraphSession) {
        try {
            val file = sessionsDir.resolve("${session.id}.json").toFile()
            restJsonMapper.writerWithDefaultPrettyPrinter().writeValue(file, session)
            l.info("Session saved: ${session.id}")
        } catch (e: Exception) {
            l.error("Failed to save session: ${session.id}", e)
        }
    }

    /** Fetches sessions. New first. */
    fun loadAll(): List<GraphSession> {
        return try {
            Files.list(sessionsDir).use { stream ->
                stream.asSequence()
                    .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".json") }
                    .mapNotNull { path ->
                        runCatching { restJsonMapper.readValue<GraphSession>(path.toFile()) }.getOrNull()
                    }
                    .sortedByDescending { it.startTime }
                    .toList()
                }
        } catch (e: Exception) {
            l.error("Failed to load sessions", e)
            emptyList()
        }
    }

    fun loadById(sessionId: String): GraphSession? {
        return try {
            val file = sessionsDir.resolve("$sessionId.json").toFile()
            if (file.exists()) {
                restJsonMapper.readValue<GraphSession>(file)
            } else null
        } catch (e: Exception) {
            l.error("Failed to load session: $sessionId", e)
            null
        }
    }

    fun delete(sessionId: String): Boolean {
        return try {
            Files.deleteIfExists(sessionsDir.resolve("$sessionId.json"))
        } catch (e: Exception) {
            l.error("Failed to delete session: $sessionId", e)
            false
        }
    }

    /** @return Stored sessions count */
    fun count(): Int {
        return Files.list(sessionsDir).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".json") }.count()
        }.toInt()
    }
}
