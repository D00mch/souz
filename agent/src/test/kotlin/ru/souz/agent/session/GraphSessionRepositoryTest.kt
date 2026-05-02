package ru.souz.agent.session

import java.nio.file.Files
import java.nio.file.Path
import ru.souz.llms.restJsonMapper
import ru.souz.paths.DefaultSouzPaths
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphSessionRepositoryTest {
    private val createdPaths = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        createdPaths.asReversed().forEach { path ->
            runCatching { path.toFile().deleteRecursively() }
        }
        createdPaths.clear()
    }

    @Test
    fun `migrates legacy root session json files into sessions dir`() {
        val stateRoot = createTempDirectory("graph-session-migrate-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val legacyFile = paths.stateRoot.resolve("legacy-session.json")
        val session = sampleSession(id = "legacy-session")
        Files.createDirectories(paths.stateRoot)
        restJsonMapper.writerWithDefaultPrettyPrinter().writeValue(legacyFile.toFile(), session)

        val repository = GraphSessionRepository(paths = paths)

        val loaded = repository.loadById("legacy-session")

        assertNotNull(loaded)
        assertEquals(session, loaded)
        assertTrue(paths.sessionsDir.resolve("legacy-session.json").exists())
        assertFalse(legacyFile.exists())
    }

    @Test
    fun `saves sessions under sessions dir`() {
        val stateRoot = createTempDirectory("graph-session-save-")
        val paths = DefaultSouzPaths(stateRoot = stateRoot)
        val repository = GraphSessionRepository(paths = paths)
        val session = sampleSession(id = "saved-session")

        repository.save(session)

        assertTrue(paths.sessionsDir.resolve("saved-session.json").exists())
        assertEquals(1, repository.count())
    }

    private fun createTempDirectory(prefix: String): Path =
        Files.createTempDirectory(prefix).also(createdPaths::add)

    private fun sampleSession(id: String): GraphSession = GraphSession(
        id = id,
        startTime = 123L,
        endTime = 456L,
        initialInput = "hello",
        steps = listOf(
            GraphStepRecord(
                stepIndex = 0,
                nodeName = "start",
                timestamp = 123L,
                inputSummary = "in",
                outputSummary = "out",
                data = "{}",
            )
        ),
    )
}
