package ru.souz.backend.http.routes

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.souz.backend.chat.model.Chat
import ru.souz.backend.events.bus.AgentEventStream
import ru.souz.backend.events.model.AgentEvent
import ru.souz.backend.events.model.AgentEventType
import ru.souz.backend.events.model.ThreadCompletedPayload
import ru.souz.backend.http.BackendHttpDependencies
import ru.souz.backend.personality.PersonalitySync

class PublicClientConnectionTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `personality sync waits for initial replay and catch-up writes`() = runTest {
        val chat = Chat(UUID.randomUUID(), "owner", null, false, Instant.EPOCH, Instant.EPOCH)
        val event = AgentEvent(UUID.randomUUID(), chat.userId, chat.id, UUID.randomUUID(), 1,
            AgentEventType.THREAD_COMPLETED, ThreadCompletedPayload("Done"), Instant.EPOCH)
        var closed = false
        val stream = AgentEventStream(listOf(event), Channel(), Channel(), { closed = true },
            { listOf(event.copy(seq = 2)) }, 0)
        val sync = mockk<PersonalitySync> { coEvery { connected(chat) } returns Unit }
        val deps = mockk<BackendHttpDependencies> {
            every { publicClientService } returns mockk()
            every { personalitySync } returns sync
            every { eventService } returns mockk {
                coEvery { openPublicStream(chat.userId, chat.id, 0) } returns stream
            }
        }
        val incoming = Channel<Frame>()
        val writing = List(2) { CompletableDeferred<Unit>() }
        val resume = List(2) { CompletableDeferred<Unit>() }
        var writes = 0
        val socket = mockk<DefaultWebSocketServerSession> {
            every { this@mockk.incoming } returns incoming
            coEvery { send(any()) } coAnswers {
                val index = writes++
                writing[index].complete(Unit)
                resume[index].await()
            }
        }
        val connection = launch { PublicClientConnection(socket, deps, "backend", chat, "replay-test").run(0) }
        try {
            for (index in writing.indices) {
                writing[index].await()
                runCurrent()
                coVerify(exactly = 0) { sync.connected(any()) }
                resume[index].complete(Unit)
            }
            runCurrent()
            coVerify(exactly = 1) { sync.connected(chat) }
        } finally {
            connection.cancelAndJoin()
            incoming.close()
        }
        assertTrue(closed)
    }
}
