package ru.souz.backend.client

import io.mockk.coEvery
import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntime
import ru.souz.backend.client.repository.ClientRequestResult

class ClientThreadRuntimeRegistryTest {
    @Test
    fun `state replacements preserve readiness and removal waiters until tool and runtime finish`() = runTest {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        val device = device("device-1")
        val runtime = mockk<BackendConversationRuntime>(relaxed = true)
        registry.register(threadId, device)
        val ready = async { registry.awaitRuntimeAvailable(threadId) }
        val removed = async { registry.awaitRemoved(threadId) }
        testScheduler.runCurrent()

        registry.attach(threadId, runtime)
        registry.markRuntimeReady(threadId, runtime)
        assertTrue(ready.await())
        val pending = PendingClientTool("tool-1")
        assertEquals(BeginClientToolResult.Started(device), registry.beginTool(threadId, pending))
        registry.withTerminalTransition(threadId) { Unit }
        registry.detach(threadId, runtime)
        registry.clearTool(threadId, "other-tool")
        assertTrue(registry.contains(threadId))
        assertFalse(removed.isCompleted)

        val outcome = ClientToolOutcome("completed", null, null)
        assertFalse(registry.finishTool(threadId, "other-tool", outcome))
        assertTrue(registry.finishTool(threadId, "tool-1", outcome))
        assertEquals(outcome, pending.result.await())
        removed.await()
        assertFalse(registry.contains(threadId))
        assertFalse(registry.finishTool(threadId, "tool-1", outcome))
    }

    @Test
    fun `terminal state is retained for a pending tool and then released`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        registry.register(
            threadId,
            device("device-1"),
        )
        registry.beginTool(threadId, PendingClientTool("tool-1"))

        registry.withTerminalTransition(threadId) { Unit }

        assertTrue(registry.contains(threadId))
        registry.clearTool(threadId, "tool-1")
        assertFalse(registry.contains(threadId))
    }

    @Test
    fun `cancelled runtime is retained until detached`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        registry.register(threadId, device("device-1"))
        val runtime = mockk<BackendConversationRuntime>(relaxed = true)
        registry.attach(threadId, runtime)
        registry.markRuntimeReady(threadId, runtime)

        val accepted = mockk<ClientRequestResult.Accepted>()
        val result = registry.commitCancellation(
            threadId = threadId,
            commit = { accepted },
        )

        assertTrue(result === accepted)
        assertTrue(registry.contains(threadId))
        registry.detach(threadId, runtime)
        assertFalse(registry.contains(threadId))
    }

    @Test
    fun `cancelled accepted input retains the latest device`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        val initialDevice = device("device-1")
        val latestDevice = initialDevice.copy(deviceId = "device-2")
        val runtime = mockk<BackendConversationRuntime>()
        coEvery { runtime.commitActiveRunInput(any()) } coAnswers {
            firstArg<suspend (Long) -> ClientRequestResult>().invoke(0L)
            throw CancellationException("cancelled after accepted commit")
        }
        registry.register(threadId, initialDevice)
        registry.attach(threadId, runtime)

        assertFailsWith<CancellationException> {
            registry.acceptInput(
                threadId = threadId,
                device = latestDevice,
                commit = { mockk<ClientRequestResult.Accepted>() },
            )
        }

        val started = assertIs<BeginClientToolResult.Started>(
            registry.beginTool(threadId, PendingClientTool("tool-1"))
        )
        assertEquals(latestDevice, started.device)

        registry.clearTool(threadId, "tool-1")
    }

    private fun device(id: String) = ClientDevice(
        userId = UUID.randomUUID().toString(),
        deviceId = id,
        deviceType = "tv_box",
        capabilities = setOf("speech"),
    )
}
