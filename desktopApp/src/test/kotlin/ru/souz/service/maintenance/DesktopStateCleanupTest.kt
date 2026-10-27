package ru.souz.service.maintenance

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphSessionRepository
import ru.souz.paths.DefaultSouzPaths

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopStateCleanupTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val stateRoot get() = temporaryDirectory.resolve("state")
    private val now = Instant.parse("2026-10-27T12:00:00Z")
    private val dayMillis = Duration.ofDays(1).toMillis()

    @Test
    fun `only files strictly older than each retention period expire`() = runTest {
        val paths = DefaultSouzPaths(stateRoot)
        val repository = GraphSessionRepository(paths)
        val logCutoff = now.minus(Duration.ofDays(7))
        val sessionCutoff = now.minus(Duration.ofDays(30))
        val logs = listOf(-1L, 0L, 1L).mapIndexed { index, offset ->
            file("logs/giga.$index.log", logCutoff.plusMillis(offset))
        }
        val telemetry = file("logs/telemetry.old.0.log", logCutoff.minusMillis(1))
        listOf(-1L, 0L, 1L).forEachIndexed { index, offset ->
            repository.save(GraphSession("session-$index", sessionCutoff.toEpochMilli(), null, "input", emptyList()))
            Files.setLastModifiedTime(paths.sessionsDir.resolve("session-$index.json"), FileTime.from(sessionCutoff.plusMillis(offset)))
        }
        repository.save(GraphSession("recently-saved", sessionCutoff.minusSeconds(1).toEpochMilli(), null, "input", emptyList()))
        Files.setLastModifiedTime(paths.sessionsDir.resolve("recently-saved.json"), FileTime.from(now))

        val job = cleanup().start()
        runCurrent()

        assertFalse(Files.exists(logs[0]))
        assertFalse(Files.exists(telemetry))
        logs.drop(1).forEach { assertTrue(Files.exists(it)) }
        assertEquals(setOf("session-1", "session-2", "recently-saved"), repository.loadAll().map { it.id }.toSet())
        job.cancel()
    }

    @Test
    fun `daily schedule survives relaunch and stops with the application scope`() = runTest {
        val first = file("logs/first.log")
        val firstJob = cleanup().start()
        runCurrent()
        assertFalse(Files.exists(first))
        firstJob.cancel()
        advanceTimeBy(dayMillis / 2)

        val next = file("logs/next.log")
        val relaunchedJob = cleanup().start()
        runCurrent()
        assertTrue(Files.exists(next), "Relaunch must preserve the persisted daily limit")
        advanceTimeBy(dayMillis / 2 - 1)
        runCurrent()
        assertTrue(Files.exists(next))
        advanceTimeBy(1)
        runCurrent()
        assertFalse(Files.exists(next), "Relaunch must wait only the remaining interval")

        val later = file("sessions/later.json")
        advanceTimeBy(dayMillis)
        runCurrent()
        assertFalse(Files.exists(later), "Cleanup must repeat while the app stays open")
        backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        val afterShutdown = file("logs/after-shutdown.log")
        advanceTimeBy(dayMillis * 2)
        runCurrent()
        assertTrue(Files.exists(afterShutdown))
        assertTrue(relaunchedJob.isCancelled)
    }

    @Test
    fun `next launch cleans immediately after the application missed a day`() = runTest {
        val initialJob = cleanup().start()
        runCurrent()
        initialJob.cancel()
        advanceTimeBy(dayMillis * 3)
        val expired = file("sessions/missed.json")

        val nextJob = cleanup().start()
        runCurrent()

        assertFalse(Files.exists(expired))
        nextJob.cancel()
    }

    @Test
    fun `unrelated data nested directories and symbolic links are preserved`() = runTest {
        val preserved = listOf(
            "memory.db", "models/model.gguf", "native/bridge.dylib", "vector-index/index",
            "skills/skill.json", "knowledge/entry.json", "docker/state/sessions/session.json",
            "logs/notes.txt", "logs/archive/nested.log", "sessions/notes.txt", "sessions/archive/nested.json",
        ).map { file(it) }
        val outside = Files.writeString(temporaryDirectory.resolve("outside.json"), "keep")
        Files.setLastModifiedTime(outside, FileTime.from(Instant.EPOCH))
        val linkedLog = Files.createSymbolicLink(stateRoot.resolve("logs/link.log"), outside)
        val linkedSession = Files.createSymbolicLink(stateRoot.resolve("sessions/link.json"), outside)
        val expired = file("logs/expired.log")

        val job = cleanup().start()
        runCurrent()

        assertFalse(Files.exists(expired))
        (preserved + listOf(outside)).forEach { assertTrue(Files.exists(it), "Must preserve $it") }
        assertTrue(Files.isSymbolicLink(linkedLog))
        assertTrue(Files.isSymbolicLink(linkedSession))
        job.cancel()
    }

    @Test
    fun `symbolic link retention directories are not scanned`() = runTest {
        Files.createDirectories(stateRoot)
        val outsideLogs = Files.createDirectories(temporaryDirectory.resolve("other-logs"))
        val outsideSessions = Files.createDirectories(temporaryDirectory.resolve("other-sessions"))
        val oldLog = Files.writeString(outsideLogs.resolve("old.log"), "keep")
        val oldSession = Files.writeString(outsideSessions.resolve("old.json"), "keep")
        listOf(oldLog, oldSession).forEach { Files.setLastModifiedTime(it, FileTime.from(Instant.EPOCH)) }
        Files.createSymbolicLink(stateRoot.resolve("logs"), outsideLogs)
        Files.createSymbolicLink(stateRoot.resolve("sessions"), outsideSessions)

        val job = cleanup().start()
        runCurrent()

        assertTrue(Files.exists(oldLog))
        assertTrue(Files.exists(oldSession))
        job.cancel()
    }

    @Test
    fun `symbolic link state root is not scanned or written`() = runTest {
        val outsideRoot = Files.createDirectories(temporaryDirectory.resolve("other-state"))
        val outsideLogs = Files.createDirectories(outsideRoot.resolve("logs"))
        val oldLog = Files.writeString(outsideLogs.resolve("old.log"), "keep")
        Files.setLastModifiedTime(oldLog, FileTime.from(Instant.EPOCH))
        Files.createSymbolicLink(stateRoot, outsideRoot)

        val job = cleanup().start()
        runCurrent()

        assertTrue(Files.exists(oldLog))
        assertFalse(Files.exists(outsideRoot.resolve(".desktop-cleanup")))
        job.cancel()
    }

    @Test
    fun `an overlapping process cannot claim a cleanup attempt`() = runTest {
        val expired = file("logs/old.log")
        val job = FileChannel.open(stateRoot.resolve(".desktop-cleanup"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                cleanup().start().also {
                    runCurrent()
                    assertTrue(Files.exists(expired))
                    assertEquals(0, channel.size())
                }
            }
        }
        advanceTimeBy(dayMillis)
        runCurrent()
        assertFalse(Files.exists(expired))
        job.cancel()
    }

    @Test
    fun `a corrupt timestamp is replaced and missing directories do not stop future runs`() = runTest {
        file(".desktop-cleanup")
        val job = cleanup().start()
        runCurrent()
        assertEquals(now.toEpochMilli().toString(), Files.readString(stateRoot.resolve(".desktop-cleanup")))
        assertFalse(Files.exists(stateRoot.resolve("logs")))
        assertFalse(Files.exists(stateRoot.resolve("sessions")))

        val expired = file("sessions/new-directory.json")
        advanceTimeBy(dayMillis)
        runCurrent()
        assertFalse(Files.exists(expired))
        job.cancel()
    }

    @Test
    fun `desktop rollover uses seven days of hourly logs and daily telemetry`() {
        val configuration = javaClass.classLoader.getResourceAsStream("logback.xml")!!.use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        val appenders = configuration.getElementsByTagName("appender")
        val histories = (0 until appenders.length).map { appenders.item(it) as Element }
            .filter { it.getElementsByTagName("maxHistory").length > 0 }
            .associate { it.getAttribute("name") to it.getElementsByTagName("maxHistory").item(0).textContent }

        assertEquals(mapOf("FILE" to "168", "TELEMETRY_FILE" to "7"), histories)
    }

    private fun file(relativePath: String, modified: Instant = Instant.EPOCH): Path {
        val path = stateRoot.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, "test data")
        Files.setLastModifiedTime(path, FileTime.from(modified))
        return path
    }

    private fun TestScope.cleanup() = DesktopStateCleanup(
        stateRoot = stateRoot,
        scope = backgroundScope,
        clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = now.plusMillis(testScheduler.currentTime)
        },
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )
}
