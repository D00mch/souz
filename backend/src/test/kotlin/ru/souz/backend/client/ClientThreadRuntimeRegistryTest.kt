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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.backend.toolcall.repository.ToolCallContext
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntime
import ru.souz.backend.client.repository.ClientRequestResult

class ClientThreadRuntimeRegistryTest {
    @Test
    fun `terminal state is retained for pending acknowledgement and then released`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        registry.register(
            threadId,
            device("device-1"),
            "request-1",
        )

        registry.withTerminalTransition(threadId) { Unit }

        assertTrue(registry.contains(threadId))
        registry.ackSent(threadId, "request-1")
        assertFalse(registry.contains(threadId))
    }

    @Test
    fun `cancellation reserves its acknowledgement before terminal transition`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        registry.register(threadId, device("device-1"))
        val runtime = mockk<BackendConversationRuntime>(relaxed = true)
        registry.attach(threadId, runtime)
        registry.markRuntimeReady(threadId, runtime)

        val accepted = mockk<ClientRequestResult.Accepted>()
        val result = registry.commitCancellation(
            threadId = threadId,
            requestId = "cancel-1",
            commit = { accepted },
            afterAccepted = {},
        )

        assertTrue(result === accepted)
        registry.detach(threadId, runtime)
        assertTrue(registry.contains(threadId))
        registry.ackSent(threadId, "cancel-1")
        assertFalse(registry.contains(threadId))
    }

    @Test
    fun `cancelled accepted input retains acknowledgement gate and latest device`() = runBlocking {
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
                requestId = "message-2",
                device = latestDevice,
                commit = { mockk<ClientRequestResult.Accepted>() },
            )
        }

        val acknowledgement = async(start = CoroutineStart.UNDISPATCHED) {
            registry.awaitAcceptedInputAcks(threadId)
        }
        assertFalse(acknowledgement.isCompleted)
        val started = assertIs<BeginClientToolResult.Started>(
            registry.beginTool(threadId, PendingClientTool("tool-1"))
        )
        assertEquals(latestDevice, started.device)

        registry.clearTool(threadId, "tool-1")
        registry.ackSent(threadId, "message-2")
        acknowledgement.await()
    }

    @Test
    fun `channel waiters are scoped and cleaned after timeout cancellation or failure`() = runTest {
        val registry = ClientThreadRuntimeRegistry()
        val context = ToolCallContext("user", "chat", UUID.randomUUID().toString(), "tool")
        val timed = async(start = CoroutineStart.UNDISPATCHED) {
            registry.withChannelTool(context) { result -> withTimeoutOrNull(10) { result.await() } }
        }
        assertFalse(registry.isEmpty())
        assertFalse(registry.contains(UUID.fromString(context.executionId)))
        assertEquals(null, registry.channelTool(context.copy(userId = "other")))
        assertEquals(null, registry.channelTool(context.copy(chatId = "other")))
        assertEquals(null, timed.await())
        assertTrue(registry.isEmpty())
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            registry.withChannelTool(context) { it.await() }
        }
        cancelled.cancelAndJoin()
        assertTrue(registry.isEmpty())
        assertFailsWith<IllegalStateException> { registry.withChannelTool(context) { error("publish failed") } }
        assertTrue(registry.isEmpty())
    }

    private fun device(id: String) = ClientDevice(
        userId = UUID.randomUUID().toString(),
        deviceId = id,
        deviceType = "tv_box",
        capabilities = setOf("speech"),
    )
}
