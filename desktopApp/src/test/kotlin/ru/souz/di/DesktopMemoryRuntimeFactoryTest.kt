package ru.souz.di

import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryGraphSnapshot
import ru.souz.agent.memory.MemoryInjectionRequest
import ru.souz.agent.memory.MemoryInjectionResult
import ru.souz.agent.memory.MemoryMaintenanceService
import ru.souz.agent.memory.MemoryRuntimeServicesContract
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryTriggerType
import ru.souz.agent.memory.MemoryWriteInput
import ru.souz.agent.memory.MemoryWriteResult
import ru.souz.agent.memory.NoOpMemoryRuntimeServices
import ru.souz.db.SettingsProvider
import ru.souz.paths.DefaultSouzPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopMemoryRuntimeFactoryTest {
    @Test
    fun `factory falls back to no op runtime when sqlite init fails`() = runTest {
        val runtime = DesktopMemoryRuntimeFactory(
            paths = DefaultSouzPaths(stateRoot = Files.createTempDirectory("desktop-memory-fallback")),
            createRuntime = { throw IllegalStateException("sqlite unavailable") },
        ).create()

        assertSame(NoOpMemoryRuntimeServices, runtime)
        assertEquals(
            MemoryWriteResult(),
            runtime.write(
                MemoryWriteInput(
                    userMessage = "remember this",
                    scope = MemoryScope(MemoryScopeType.USER, "local-user"),
                    triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
                )
            )
        )
        assertEquals(
            MemoryInjectionResult(),
            runtime.inject(
                MemoryInjectionRequest(
                    queryText = "anything",
                    scope = MemoryScope(MemoryScopeType.USER, "local-user"),
                )
            )
        )
    }

    @Test
    fun `desktop runtime skips memory while disabled and resumes without recreation`() = runTest {
        var memoryEnabled = false
        val settingsProvider = mockk<SettingsProvider>(relaxed = true)
        every { settingsProvider.memoryEnabled } answers { memoryEnabled }
        val delegate = RecordingMemoryRuntime()
        val runtime = DesktopMemoryRuntimeFactory(
            paths = DefaultSouzPaths(stateRoot = Files.createTempDirectory("desktop-memory-toggle")),
            settingsProvider = settingsProvider,
            createRuntime = { delegate },
        ).create()

        val disabledWrite = runtime.write(
            MemoryWriteInput(
                userMessage = "remember this",
                scope = MemoryScope(MemoryScopeType.USER, "local-user"),
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )
        val disabledInjection = runtime.inject(
            MemoryInjectionRequest(
                queryText = "anything",
                scope = MemoryScope(MemoryScopeType.USER, "local-user"),
            )
        )

        assertEquals(MemoryWriteResult(), disabledWrite)
        assertEquals(MemoryInjectionResult(), disabledInjection)
        assertEquals(0, delegate.writeCalls)
        assertEquals(0, delegate.injectCalls)

        memoryEnabled = true

        val enabledWrite = runtime.write(
            MemoryWriteInput(
                userMessage = "remember this",
                scope = MemoryScope(MemoryScopeType.USER, "local-user"),
                triggerType = MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
            )
        )
        val enabledInjection = runtime.inject(
            MemoryInjectionRequest(
                queryText = "anything",
                scope = MemoryScope(MemoryScopeType.USER, "local-user"),
            )
        )

        assertEquals(1, delegate.writeCalls)
        assertEquals(1, delegate.injectCalls)
        assertEquals("delegate-write-1", enabledWrite.writeAttemptId)
        assertTrue(enabledInjection.renderedBlock.contains("delegate-memory"))
    }
}

private class RecordingMemoryRuntime : MemoryRuntimeServicesContract {
    var writeCalls: Int = 0
    var injectCalls: Int = 0

    override suspend fun write(input: MemoryWriteInput): MemoryWriteResult {
        writeCalls += 1
        return MemoryWriteResult(writeAttemptId = "delegate-write-$writeCalls")
    }

    override suspend fun inject(request: MemoryInjectionRequest): MemoryInjectionResult {
        injectCalls += 1
        return MemoryInjectionResult(renderedBlock = "delegate-memory-$injectCalls")
    }

    override suspend fun graphSnapshot(scope: MemoryScope): MemoryGraphSnapshot = MemoryGraphSnapshot()

    override suspend fun forgetFact(factId: String, at: Instant): Boolean = false

    override suspend fun invalidateFact(factId: String, at: Instant): Boolean = false

    override suspend fun rebuildProjection() = Unit
}
