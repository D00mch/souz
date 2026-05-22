package ru.souz.di

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import ru.souz.agent.memory.MemoryInjectionRequest
import ru.souz.agent.memory.MemoryInjectionResult
import ru.souz.agent.memory.MemoryScope
import ru.souz.agent.memory.MemoryScopeType
import ru.souz.agent.memory.MemoryWriteInput
import ru.souz.agent.memory.MemoryWriteResult
import ru.souz.agent.memory.NoOpMemoryRuntimeServices
import ru.souz.paths.DefaultSouzPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
                    triggerType = ru.souz.agent.memory.MemoryTriggerType.EXPLICIT_REMEMBER_REQUEST,
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
}
