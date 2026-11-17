package ru.souz.service.maintenance

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import ru.souz.agent.session.GraphSession
import ru.souz.db.SqliteGraphSessionRepository
import ru.souz.paths.DefaultSouzPaths

class DesktopLocalCacheCleanerTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val paths get() = DefaultSouzPaths(temporaryDirectory.resolve("state"))

    @Test
    fun `manual cleanup clears recent logs and graph history and preserves other data`() = runTest {
        val repository = sessions()
        repository.save(session("before-cleanup"))
        val logs = listOf("logs/giga.log", "logs/telemetry.log").map(::file)
        val preserved = listOf(
            "memory.db", "models/model.gguf", "native/bridge.dylib", "vector-index/index",
            "skills/skill.json", "knowledge/entry.json", ".desktop-cleanup",
            "logs/notes.txt", "logs/archive/nested.log", "sessions/legacy.json", "sessions/notes.txt", "sessions/archive/nested.json",
        ).map(::file)
        val outside = Files.writeString(temporaryDirectory.resolve("outside.json"), "keep")
        val linkedLog = Files.createSymbolicLink(paths.logsDir.resolve("linked.log"), outside)
        val linkedSession = Files.createSymbolicLink(paths.sessionsDir.resolve("linked.json"), outside)

        cleaner().clear()

        logs.forEach { assertEquals(0L, Files.size(it)) }
        assertEquals(0, repository.count())
        (preserved + listOf(outside)).forEach { assertEquals("keep", Files.readString(it), "Must preserve $it") }
        assertTrue(Files.isSymbolicLink(linkedLog))
        assertTrue(Files.isSymbolicLink(linkedSession))
        repository.save(session("after-cleanup"))
        assertEquals("after-cleanup", repository.loadById("after-cleanup")?.id)
    }

    @Test
    fun `configured logs are cleared and an open rolling appender keeps writing`() = runTest {
        val defaultLog = file("logs/default.log")
        val configuredLogs = Files.createDirectories(temporaryDirectory.resolve("configured-logs"))
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        try {
            val encoder = PatternLayoutEncoder().apply {
                this.context = context
                pattern = "%msg%n"
                start()
            }
            val appender = RollingFileAppender<ILoggingEvent>().apply {
                this.context = context
                this.encoder = encoder
            }
            val policy = TimeBasedRollingPolicy<ILoggingEvent>().apply {
                this.context = context
                setParent(appender)
                fileNamePattern = configuredLogs.resolve("giga.%d{yyyy-MM-dd_HH}.log").toString()
                start()
            }
            appender.rollingPolicy = policy
            appender.start()
            val logger = context.getLogger("cleanup-test").apply { addAppender(appender) }
            logger.info("before-cleanup")
            val activeFile = Path.of(appender.file)
            assertEquals("before-cleanup\n", Files.readString(activeFile))

            DesktopLocalCacheCleaner(paths, configuredLogs, sessions()).clear()

            assertEquals(0L, Files.size(activeFile))
            assertEquals("keep", Files.readString(defaultLog))
            logger.info("after-cleanup")
            assertEquals("after-cleanup\n", Files.readString(activeFile))
        } finally {
            context.stop()
        }
    }

    @Test
    fun `missing caches are harmless and repeated cleanup is safe`() = runTest {
        cleaner().clear()
        assertEquals(0, sessions().count())
        val log = file("logs/giga.log")
        cleaner().clear()
        cleaner().clear()
        assertEquals(0L, Files.size(log))
    }

    @Test
    fun `directory failure is reported after attempting the other cache`() = runTest {
        val invalidLogs = file("logs")
        val sessionFile = file("sessions/graph.json")
        sessions().save(session("clear-despite-log-failure"))

        val failure = assertFailsWith<IOException> { cleaner().clear() }

        assertEquals("Some local logs or graph sessions could not be cleared.", failure.message)
        assertTrue(Files.exists(sessionFile))
        assertEquals(0, sessions().count())
        assertEquals("keep", Files.readString(invalidLogs))
    }

    @Test
    fun `linked cache directories and state root cannot clear their targets`() = runTest {
        val outside = Files.createDirectories(temporaryDirectory.resolve("outside"))
        val outsideLog = Files.writeString(outside.resolve("giga.log"), "keep")
        val outsideSession = Files.writeString(outside.resolve("graph.json"), "keep")
        Files.createDirectories(paths.stateRoot)
        Files.createSymbolicLink(paths.logsDir, outside)
        Files.createSymbolicLink(paths.sessionsDir, outside)

        cleaner().clear()

        assertEquals("keep", Files.readString(outsideLog))
        assertEquals("keep", Files.readString(outsideSession))
        val linkedRoot = Files.createSymbolicLink(temporaryDirectory.resolve("linked-state"), paths.stateRoot)
        val linkedPaths = DefaultSouzPaths(linkedRoot)
        assertFailsWith<IllegalStateException> { DesktopLocalCacheCleaner(linkedPaths, outside, sessions()).clear() }
        assertEquals("keep", Files.readString(outsideLog))
    }

    private fun sessions() = SqliteGraphSessionRepository(paths.stateRoot.resolve("desktop.db"))

    private fun cleaner() = DesktopLocalCacheCleaner(paths, paths.logsDir, sessions())

    private fun file(relativePath: String): Path = paths.stateRoot.resolve(relativePath).also {
        Files.createDirectories(it.parent)
        Files.writeString(it, "keep")
    }

    private fun session(id: String) = GraphSession(id, System.currentTimeMillis(), null, "synthetic input", emptyList())
}
