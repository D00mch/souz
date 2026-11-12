package ru.souz.backend.e2e

import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readReason
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.souz.backend.attachments.AttachmentMinio
import ru.souz.backend.attachments.attachmentPng
import ru.souz.backend.client.LiveClientToolDispatcher
import ru.souz.backend.http.ClientFrameByteBudget
import ru.souz.backend.http.routes.PublicClientConnection

class BackendClientFrameAdmissionE2eTest {
    @Test
    fun `shared bytes span preparation and ACK and recover after delivery failure or disconnect`() {
        for (ending in listOf("ack", "write-failure", "disconnect")) backendE2eTest(
            "client_frame_bytes", attachments = AttachmentMinio.config(),
        ) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            withPublicSocket(chat) { observer -> coroutineScope {
                observer.send(Frame.Text(historyFrame(chat, "ready", "user", "ready")))
                assertEquals("accepted", readJson(observer)["status"].asText())
                val record = backend.dependencies.publicClientService.requireChat(UUID.fromString(chat), "backend")
                val dispatcher = LiveClientToolDispatcher(backend.clientThreadRegistry, backend.dependencies.eventService)
                val caller = async { dispatcher.call(record, "screen.capture", emptyMap(), Duration.ofSeconds(45)) }
                val event = readJson(observer)
                val frame = Frame.Text(toolResultFrame(event, "succeeded", mapOf("content" to listOf(
                    mapOf("type" to "image", "mimeType" to "image/png", "data" to Base64.getEncoder().encodeToString(attachmentPng)),
                    mapOf("type" to "text", "text" to "x".repeat(4096)),
                ))))
                val budget = ClientFrameByteBudget(frame.data.size * 6L)
                val incoming = Channel<Frame>(Channel.UNLIMITED)
                val writing = CompletableDeferred<Unit>()
                val allowWrite = CompletableDeferred<Unit>()
                val socket = mockk<DefaultWebSocketServerSession> {
                    every { this@mockk.incoming } returns incoming
                    coEvery { send(any()) } coAnswers {
                        writing.complete(Unit)
                        allowWrite.await()
                        if (ending == "write-failure") throw IOException("synthetic ACK failure")
                    }
                }
                // Only socket writes are controlled; normalization, dispatcher, SQL and S3 are real.
                val connection = async {
                    try {
                        PublicClientConnection(socket, backend.dependencies, "backend", null, "held", budget).run(0)
                        null
                    } catch (failure: IOException) {
                        failure
                    }
                }
                try {
                    withTableLocked("stored_attachments", "access exclusive") {
                        incoming.send(frame)
                        awaitLockWait("stored_attachments")
                        assertNull(budget.tryReserve(1), "Preparation must retain the encoded source budget")
                        assertFalse(caller.isCompleted)
                        // A different socket is rejected before even attempting to parse invalid JSON.
                        val peerIncoming = Channel<Frame>(1)
                        val closed = CompletableDeferred<CloseReason?>()
                        val peer = mockk<DefaultWebSocketServerSession>(relaxed = true) {
                            every { this@mockk.incoming } returns peerIncoming
                            coEvery { send(any()) } coAnswers { closed.complete((firstArg<Frame>() as Frame.Close).readReason()); Unit }
                        }
                        val rejected = launch {
                            PublicClientConnection(peer, backend.dependencies, "backend", null, "peer", budget).run(0)
                        }
                        peerIncoming.send(Frame.Text("invalid JSON"))
                        assertEquals("Client frame byte capacity exceeded.", withTimeout(5.seconds) { closed.await() }?.message)
                        withTimeout(5.seconds) { rejected.join() }
                        peerIncoming.close()
                        if (ending == "disconnect") {
                            connection.cancel()
                            assertNull(budget.tryReserve(1), "Cancellation cannot free bytes while SQL still retains them")
                        }
                    }
                    if (ending == "disconnect") {
                        withTimeout(5.seconds) { connection.join() }
                    } else {
                        withTimeout(5.seconds) { writing.await() }
                        assertNull(budget.tryReserve(1), "ACK backpressure must retain byte capacity")
                        assertFalse(caller.isCompleted, "The caller must wait for the ACK")
                        allowWrite.complete(Unit)
                    }
                    val outcome = withTimeout(5.seconds) { caller.await() }
                    assertEquals(if (ending == "ack") "succeeded" else "failed", outcome.status)
                    if (ending == "ack") assertEquals("image/png", outcome.files.single().mimeType)
                    if (ending == "write-failure") assertIs<IOException>(withTimeout(5.seconds) { connection.await() })
                    eventually("bytes released after worker and ACK exit") { budget.tryReserve(frame.data.size)?.also { it.close() } }
                } finally {
                    allowWrite.complete(Unit)
                    connection.cancelAndJoin()
                    caller.cancelAndJoin()
                    incoming.close()
                }
            } }
        }
    }
}
