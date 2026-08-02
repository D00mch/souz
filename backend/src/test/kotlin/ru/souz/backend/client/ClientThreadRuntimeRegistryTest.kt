package ru.souz.backend.client

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

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
        )
        registry.registerAck(threadId, "request-1")

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

        val result = registry.acceptCancellation(
            threadId = threadId,
            requestId = "cancel-1",
            canAccept = { true },
            commit = { registry.withTerminalTransition(threadId) { "cancelled" } },
        )

        assertTrue(result == "cancelled")
        assertTrue(registry.contains(threadId))
        registry.ackSent(threadId, "cancel-1")
        assertFalse(registry.contains(threadId))
    }
}
