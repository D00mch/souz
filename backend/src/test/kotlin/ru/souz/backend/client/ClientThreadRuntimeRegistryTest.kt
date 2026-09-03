package ru.souz.backend.client

import io.mockk.mockk
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntime
import ru.souz.backend.client.repository.ClientRequestResult

class ClientThreadRuntimeRegistryTest {
    @Test
    fun `terminal state is retained for pending acknowledgement and then released`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val threadId = UUID.randomUUID()
        registry.register(
            threadId,
            ClientDevice(
                userId = UUID.randomUUID().toString(),
                deviceId = "device-1",
                deviceType = "tv_box",
                capabilities = setOf("speech"),
            ),
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
        registry.register(
            threadId,
            ClientDevice(
                userId = UUID.randomUUID().toString(),
                deviceId = "device-1",
                deviceType = "tv_box",
                capabilities = setOf("speech"),
            ),
        )
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
}
