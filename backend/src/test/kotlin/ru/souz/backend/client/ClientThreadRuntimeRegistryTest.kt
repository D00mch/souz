package ru.souz.backend.client

import io.mockk.coEvery
import io.mockk.coVerify
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
        val chatId = UUID.randomUUID()
        val threadId = UUID.randomUUID()
        registry.register(
            chatId = chatId,
            threadId = threadId,
            device = ClientDevice(
                userId = UUID.randomUUID().toString(),
                deviceId = "device-1",
                deviceType = "tv_box",
                capabilities = setOf("speech"),
            ),
            requestId = "request-1",
        )

        registry.withTerminalTransition(threadId) { Unit }

        assertTrue(registry.contains(threadId))
        registry.ackSent(threadId, "request-1")
        assertFalse(registry.contains(threadId))
    }

    @Test
    fun `cancellation reserves its acknowledgement before terminal transition`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val chatId = UUID.randomUUID()
        val threadId = UUID.randomUUID()
        registry.register(
            chatId = chatId,
            threadId = threadId,
            device = ClientDevice(
                userId = UUID.randomUUID().toString(),
                deviceId = "device-1",
                deviceType = "tv_box",
                capabilities = setOf("speech"),
            ),
        )
        val runtime = mockk<BackendConversationRuntime>(relaxed = true)
        registry.attach(threadId, runtime, historyEligible = true)
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
    fun `history watermark transfers when the runtime attaches before mailbox readiness`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val chatId = UUID.randomUUID()
        val threadId = UUID.randomUUID()
        val runtime = mockk<BackendConversationRuntime>()
        coEvery { runtime.notifyHistoryPending(7L) } returns true
        registry.register(chatId, threadId, device())

        assertFalse(registry.notifyHistoryPending(chatId, 7L))
        registry.attach(threadId, runtime, historyEligible = true)
        coVerify(exactly = 1) { runtime.notifyHistoryPending(7L) }

        registry.markRuntimeReady(threadId, runtime)

        coVerify(exactly = 1) { runtime.notifyHistoryPending(7L) }
    }

    @Test
    fun `discarding a concurrent candidate keeps the surviving chat notification index`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val chatId = UUID.randomUUID()
        val survivingThreadId = UUID.randomUUID()
        val discardedThreadId = UUID.randomUUID()
        val runtime = mockk<BackendConversationRuntime>()
        coEvery { runtime.notifyHistoryPending(9L) } returns true

        registry.register(chatId, survivingThreadId, device())
        registry.register(chatId, discardedThreadId, device())
        registry.discard(discardedThreadId)
        registry.attach(survivingThreadId, runtime, historyEligible = true)
        registry.markRuntimeReady(survivingThreadId, runtime)

        assertTrue(registry.notifyHistoryPending(chatId, 9L))
        coVerify(exactly = 1) { runtime.notifyHistoryPending(9L) }
    }

    @Test
    fun `discarding the indexed candidate transfers its history watermark to the survivor`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val chatId = UUID.randomUUID()
        val discardedThreadId = UUID.randomUUID()
        val survivingThreadId = UUID.randomUUID()
        val runtime = mockk<BackendConversationRuntime>()
        coEvery { runtime.notifyHistoryPending(11L) } returns true

        registry.register(chatId, discardedThreadId, device())
        registry.register(chatId, survivingThreadId, device())
        assertFalse(registry.notifyHistoryPending(chatId, 11L))
        registry.discard(discardedThreadId)
        registry.attach(survivingThreadId, runtime, historyEligible = true)
        registry.markRuntimeReady(survivingThreadId, runtime)

        coVerify(exactly = 1) { runtime.notifyHistoryPending(11L) }
    }

    @Test
    fun `option continuation ignores detached history until execute makes it eligible`() = runBlocking {
        val registry = ClientThreadRuntimeRegistry()
        val chatId = UUID.randomUUID()
        val threadId = UUID.randomUUID()
        val executeRuntime = mockk<BackendConversationRuntime>()
        val optionRuntime = mockk<BackendConversationRuntime>()
        val accepted = mockk<ClientRequestResult.Accepted>()
        coEvery { executeRuntime.notifyHistoryPending(7L) } returns false
        coEvery { optionRuntime.commitActiveRunInput(any()) } returns accepted
        coEvery { optionRuntime.notifyHistoryPending(13L) } returns true
        registry.register(chatId, threadId, device())
        registry.attach(threadId, executeRuntime, historyEligible = true)
        registry.markRuntimeReady(threadId, executeRuntime)
        assertFalse(registry.notifyHistoryPending(chatId, 7L))

        registry.detach(threadId, executeRuntime)
        registry.attach(threadId, optionRuntime, historyEligible = false)
        registry.markRuntimeReady(threadId, optionRuntime)

        assertFalse(registry.notifyHistoryPending(chatId, 8L))
        coVerify(exactly = 0) { optionRuntime.notifyHistoryPending(any()) }

        assertTrue(
            registry.acceptInput(
                threadId = threadId,
                requestId = "execute-after-option",
                device = device(),
                commit = { accepted },
            ) === accepted
        )
        assertTrue(registry.notifyHistoryPending(chatId, 13L))

        coVerify(exactly = 1) { optionRuntime.notifyHistoryPending(13L) }
    }

    private fun device() = ClientDevice(
        userId = UUID.randomUUID().toString(),
        deviceId = "device-1",
        deviceType = "tv_box",
        capabilities = setOf("speech"),
    )
}
