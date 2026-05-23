package ru.souz.ui.settings

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryMaintenanceService
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LocalUserId
import ru.souz.memory.SqliteMemoryStore
import ru.souz.paths.DefaultSouzPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryInspectorServiceTest {
    @Test
    fun `default scope prefers latest activity scope from store`() = runTest {
        val store = createStore("memory-inspector-default-scope")
        val threadScope = MemoryScope(MemoryScopeType.THREAD, "thread-42")
        store.logWriteAttempt(
            scope = threadScope,
            turnRef = "turn-1",
            triggerType = MemoryTriggerType.TASK_STATE_CHANGE,
            inputExcerpt = "Remember this preference.",
            candidatesJson = "[]",
            acceptedCount = 0,
            rejectedCount = 1,
            rejectionReasonsJson = """["LLM_OUTPUT_INVALID"]""",
        )
        val service = DefaultMemoryInspectorService(
            store = store,
            maintenanceService = noOpMaintenanceService(),
            settingsProvider = settingsProvider(),
        )

        assertEquals(threadScope, service.defaultScope())
    }

    @Test
    fun `default scope falls back to user scope when store is empty`() = runTest {
        val store = createStore("memory-inspector-empty-scope")
        val service = DefaultMemoryInspectorService(
            store = store,
            maintenanceService = noOpMaintenanceService(),
            settingsProvider = settingsProvider(),
        )

        assertEquals(MemoryScope(MemoryScopeType.USER, LocalUserId.default()), service.defaultScope())
    }

    @Test
    fun `diagnostics expose raw extraction details for empty attempt`() = runTest {
        val store = createStore("memory-inspector-empty-attempt")
        val scope = MemoryScope(MemoryScopeType.THREAD, "thread-42")
        store.logWriteAttempt(
            scope = scope,
            turnRef = "turn-2",
            triggerType = MemoryTriggerType.TASK_STATE_CHANGE,
            inputExcerpt = "My goal is to work at Anthropic.",
            candidatesJson = """
                {
                  "audits": [],
                  "rawOutput": "No durable facts to store.",
                  "rawOutputKind": "CONTENT",
                  "emptyReason": "NON_JSON_CONTENT"
                }
            """.trimIndent(),
            acceptedCount = 0,
            rejectedCount = 0,
            rejectionReasonsJson = null,
        )
        val service = DefaultMemoryInspectorService(
            store = store,
            maintenanceService = noOpMaintenanceService(),
            settingsProvider = settingsProvider(),
        )

        val diagnostics = service.loadDiagnostics(scope)
        val attempts = service.loadRejectedWrites(scope)

        assertEquals("NON_JSON_CONTENT", diagnostics.lastWriteAttempt?.emptyReason)
        assertEquals("No durable facts to store.", diagnostics.lastWriteAttempt?.rawExtractionOutput)
        assertTrue(attempts.any { it.emptyReason == "NON_JSON_CONTENT" })
    }

    private fun createStore(prefix: String): SqliteMemoryStore {
        val stateRoot = Files.createTempDirectory(prefix)
        return SqliteMemoryStore(paths = DefaultSouzPaths(stateRoot = stateRoot))
    }

    private fun settingsProvider(): SettingsProvider =
        mockk<SettingsProvider>(relaxed = true).also { settings ->
            every { settings.embeddingsModel } returns EmbeddingsModel.GigaEmbeddings
            every { settings.memoryEnabled } returns true
        }

    private fun noOpMaintenanceService(): MemoryMaintenanceService =
        object : MemoryMaintenanceService {
            override suspend fun forgetFact(factId: String, at: java.time.Instant): Boolean = false

            override suspend fun invalidateFact(factId: String, at: java.time.Instant): Boolean = false

            override suspend fun rebuildProjection() = Unit
        }
}
