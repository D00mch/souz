package ru.gigadesk.agent.session

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import ru.gigadesk.tool.files.FilesToolUtil
import java.io.File

/**
 * Репозиторий для сохранения и загрузки сессий графа на файловую систему.
 * Сессии хранятся в ~/.abledo/sessions/ как JSON файлы.
 * 
 * NOTE: Используем файловую систему, так как размер сессии может превышать лимит Preferences (8KB).
 */
class GraphSessionRepository {
    private val l = LoggerFactory.getLogger(GraphSessionRepository::class.java)
    private val objectMapper = jacksonObjectMapper()
    
    private val sessionsDir: File by lazy {
        File(FilesToolUtil.homeDirectory, ".abledo/sessions").apply { mkdirs() }
    }

    /**
     * Сохраняет сессию в файл
     */
    fun save(session: GraphSession) {
        try {
            val file = File(sessionsDir, "${session.id}.json")
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, session)
            l.info("Session saved: ${session.id}")
        } catch (e: Exception) {
            l.error("Failed to save session: ${session.id}", e)
        }
    }

    /**
     * Загружает все сессии, отсортированные по времени начала (новые первые)
     */
    fun loadAll(): List<GraphSession> {
        return try {
            sessionsDir.listFiles { _, name -> name.endsWith(".json") }
                ?.mapNotNull { file ->
                    runCatching { objectMapper.readValue<GraphSession>(file) }.getOrNull()
                }
                ?.sortedByDescending { it.startTime }
                ?: emptyList()
        } catch (e: Exception) {
            l.error("Failed to load sessions", e)
            emptyList()
        }
    }

    /**
     * Загружает сессию по ID
     */
    fun loadById(sessionId: String): GraphSession? {
        return try {
            val file = File(sessionsDir, "$sessionId.json")
            if (file.exists()) {
                objectMapper.readValue<GraphSession>(file)
            } else null
        } catch (e: Exception) {
            l.error("Failed to load session: $sessionId", e)
            null
        }
    }

    /**
     * Удаляет сессию по ID
     */
    fun delete(sessionId: String): Boolean {
        return try {
            File(sessionsDir, "$sessionId.json").delete()
        } catch (e: Exception) {
            l.error("Failed to delete session: $sessionId", e)
            false
        }
    }

    /**
     * Возвращает количество сохранённых сессий
     */
    fun count(): Int {
        return sessionsDir.listFiles { _, name -> name.endsWith(".json") }?.size ?: 0
    }
}
