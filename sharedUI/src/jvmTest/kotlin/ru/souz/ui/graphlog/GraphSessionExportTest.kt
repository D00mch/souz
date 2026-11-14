@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package ru.souz.ui.graphlog

import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import ru.souz.agent.session.GraphSession
import ru.souz.agent.session.GraphStepRecord
import ru.souz.llms.restJsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphSessionExportTest {
    @TempDir
    lateinit var directory: Path
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `exports the complete selected session including unfinished and failed steps`(finished: Boolean) = runTest(dispatcher) {
        val session = session().copy(endTime = if (finished) 1700000002000 else null)
        val destination = directory.resolve("selected.json")
        val exporter = GraphSessionExporter { suggestedName ->
            assertEquals("souz-session-selected-session.json", suggestedName)
            destination
        }
        val viewModel = GraphSessionExportViewModel(exporter::export)

        viewModel.export(session)?.join()
        advanceUntilIdle()
        // IO uses the real filesystem, and the resulting JSON is readable by the session mapper.
        assertEquals(session, restJsonMapper.readValue<GraphSession>(destination.toFile()))
        assertTrue(Files.readString(destination).contains("Проверь файл"))
        assertEquals(GraphSessionExportState(savedPath = destination), viewModel.uiState.value)
    }

    @Test
    fun `cancelling the save chooser writes nothing and reports no success or failure`() = runTest(dispatcher) {
        val existing = directory.resolve("existing.json")
        Files.writeString(existing, "existing contents")
        val exporter = GraphSessionExporter { null }
        val viewModel = GraphSessionExportViewModel(exporter::export)

        viewModel.export(session())?.join()
        advanceUntilIdle()

        assertEquals(GraphSessionExportState(), viewModel.uiState.value)
        assertEquals("existing contents", Files.readString(existing))
        Files.list(directory).use { assertEquals(listOf(existing), it.toList()) }
    }

    @Test
    fun `write failure is visible and export can be retried`() = runTest(dispatcher) {
        var destination = directory.resolve("missing-parent/session.json")
        val exporter = GraphSessionExporter { destination }
        val viewModel = GraphSessionExportViewModel(exporter::export)

        viewModel.export(session())?.join()
        advanceUntilIdle()
        assertEquals(GraphSessionExportState(failed = true), viewModel.uiState.value)
        assertFalse(Files.exists(destination))

        destination = directory.resolve("retry.json")
        viewModel.export(session())?.join()
        advanceUntilIdle()
        assertEquals(GraphSessionExportState(savedPath = destination), viewModel.uiState.value)
        assertEquals(session(), restJsonMapper.readValue<GraphSession>(destination.toFile()))
    }

    @Test
    fun `repeated clicks cannot open overlapping exports or replace the selected session`() = runTest(dispatcher) {
        val completed = CompletableDeferred<Path?>()
        val exported = mutableListOf<GraphSession>()
        val viewModel = GraphSessionExportViewModel { session ->
            exported += session
            completed.await()
        }

        viewModel.export(session())
        viewModel.export(session().copy(id = "another-session"))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isExporting)
        assertEquals(listOf(session()), exported)

        completed.complete(directory.resolve("selected.json"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isExporting)
    }

    @Test
    fun `coroutine cancellation is not presented as a failed export`() = runTest(dispatcher) {
        val completed = CompletableDeferred<Path?>()
        val viewModel = GraphSessionExportViewModel { completed.await() }
        viewModel.export(session())
        advanceUntilIdle()

        completed.cancel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isExporting)
        assertFalse(viewModel.uiState.value.failed)
        assertNull(viewModel.uiState.value.savedPath)
    }

    private fun session() = GraphSession(
        id = "selected-session",
        startTime = 1700000000000,
        initialInput = "Проверь файл\nwith \"quotes\" and emoji 🐈",
        steps = listOf(
            GraphStepRecord(0, "Tools::Read", 1700000000100, "input", "output", "{\"path\":\"test.txt\"}", "history\nline 2"),
            GraphStepRecord(1, "Tools::Failed", 1700000000200, "failure input", null, "{\"error\":\"synthetic failure\"}", null),
        ),
    )
}
