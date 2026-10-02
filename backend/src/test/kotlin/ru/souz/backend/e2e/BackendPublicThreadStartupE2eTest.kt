package ru.souz.backend.e2e

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.backend.client.MessageSubmitFrame
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.http.decodeClientFrame
import ru.souz.backend.http.routes.PublicClientConnection

class BackendPublicThreadStartupE2eTest {
    @Test
    fun `blocked ACK holds only its connection including when startup fails`() {
        for (failStartup in listOf(false, true)) backendE2eTest("e2e_ws_local_ack") {
            if (failStartup) rejectStartupMessage()
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "submit", text = "startup")
            withServiceLogs { logs -> withMultiChatSocket { observer -> coroutineScope {
                observer.send(Frame.Text("""{"kind":"chat.subscribe","chatId":"$chatId","requestId":"watch"}"""))
                assertEquals("accepted", readJson(observer)["status"].asText())
                // Only the transport is controlled: a rendezvous write keeps the ACK unsent.
                val incoming = Channel<Frame>(Channel.UNLIMITED)
                val outgoing = Channel<Frame>()
                val writing = CompletableDeferred<Unit>()
                val socket = mockk<DefaultWebSocketServerSession> {
                    every { this@mockk.incoming } returns incoming
                    coEvery { send(any()) } coAnswers {
                        writing.complete(Unit)
                        outgoing.send(firstArg())
                    }
                }
                val chat = backend.dependencies.publicClientService.requireChat(UUID.fromString(chatId), "backend")
                val connection = launch {
                    PublicClientConnection(socket, backend.dependencies, "backend", chat, "blocked-ack").run(0)
                }
                try {
                    incoming.send(Frame.Text(submit))
                    withTimeout(10.seconds) {
                        writing.await()
                        val terminal = readJson(observer)
                        val threadId = UUID.fromString(terminal["threadId"].asText())
                        assertEquals(if (failStartup) "thread.failed" else "thread.completed", terminal["type"].asText())
                        backend.clientThreadRegistry.awaitRemoved(threadId)
                        val ack = json.readTree((outgoing.receive() as Frame.Text).readText())
                        assertEquals("accepted", ack["status"].asText())
                        assertEquals(threadId.toString(), ack["thread"]["id"].asText())
                        val status = json.readTree((outgoing.receive() as Frame.Text).readText())
                        assertEquals(if (failStartup) "failed" else "completed", status["status"].asText())
                        assertEquals(terminal, json.readTree((outgoing.receive() as Frame.Text).readText()))
                        assertEquals(listOf(terminal["type"].asText()), terminalEvents(threadId))
                    }
                    if (failStartup) assertTrue(logs.any { it.formattedMessage.startsWith("Thread startup failed") && it.throwableProxy != null })
                } finally {
                    connection.cancelAndJoin()
                    incoming.close()
                    outgoing.close()
                }
            } } }
        }
    }

    @Test
    fun `cancellation during preparation creates no execution`() = backendE2eTest("e2e_ws_preparation") {
        val userId = UUID.randomUUID().toString()
        val chatId = createPublicChat(userId)
        withTableLocked("user_settings", "access exclusive") {
            val caller = async { submitDirectly(chatId, messageFrame(chatId, userId, "preparing")) }
            awaitLockWait("user_settings")
            caller.cancel()
        }
        backend.applicationScope.cancelAndJoin()
        assertNull(threadIdOf(chatId))
        assertTrue(backend.clientThreadRegistry.isEmpty())
    }

    @Test
    fun `cancelled caller leaves application to complete and replay without retrying the ACK`() =
        backendE2eTest("e2e_ws_owned_startup") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "owned")
            withTableLocked("agent_executions", "share") {
                val caller = async { submitDirectly(chatId, submit) }
                awaitLockWait("agent_executions")
                withTimeout(2.seconds) { caller.cancelAndJoin() }
            }
            withPublicSocket(chatId) { session ->
                val terminal = withTimeout(10.seconds) { readJson(session) }
                assertEquals("thread.completed", terminal["type"].asText())
                val threadId = UUID.fromString(terminal["threadId"].asText())
                withTimeout(5.seconds) { backend.clientThreadRegistry.awaitRemoved(threadId) }
                session.send(Frame.Text(submit))
                val retry = readJson(session)
                assertTrue(retry["duplicate"].asBoolean())
                assertEquals(threadId.toString(), retry["thread"]["id"].asText())
                assertEquals("completed", readJson(session)["status"].asText())
                assertEquals(listOf("thread.completed"), terminalEvents(threadId))
                assertEquals(1, llm.requests.size)
            }
            withTimeout(5.seconds) { backend.shutdown() }
        }

    @Test
    fun `unacknowledged submit can call a device and complete`() = backendE2eTest(
        "e2e_ws_unacked_tool", llm = E2eLlmApi().apply { requestSkill("user.ask", mapOf("question" to "Continue?")) },
    ) {
        val userId = UUID.randomUUID().toString()
        val chatId = createPublicChat(userId)
        submitDirectly(chatId, messageFrame(chatId, userId, "unacked"))
        withPublicSocket(chatId) { session -> withTimeout(10.seconds) {
            val started = readJson(session)
            assertEquals("tool.call.started", started["type"].asText())
            val threadId = started["threadId"].asText()
            val toolCallId = started["payload"]["toolCallId"].asText()
            session.send(Frame.Text(
                """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"answer":"yes"}}"""
            ))
            assertEquals("accepted", readJson(session)["status"].asText())
            assertEquals("thread.completed", readJson(session)["type"].asText())
            backend.clientThreadRegistry.awaitRemoved(UUID.fromString(threadId))
        } }
    }

    @Test
    fun `shutdown joins acceptance and finalizes successful or failed startup without an ACK`() {
        for (failStartup in listOf(false, true)) backendE2eTest("e2e_ws_startup_shutdown") { withTokenUsageLogs { logs ->
            if (failStartup) rejectStartupMessage()
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            withTimeout(10.seconds) {
                withTableLocked("agent_executions", "share") {
                    val caller = async { submitDirectly(chatId, messageFrame(chatId, userId, "shutdown", text = "startup")) }
                    awaitLockWait("agent_executions")
                    caller.cancelAndJoin()
                    val stopping = launch(start = CoroutineStart.UNDISPATCHED) { backend.applicationScope.cancelAndJoin() }
                    assertFalse(stopping.isCompleted, "Shutdown must join the blocked acceptance.")
                }
            }
            val threadId = assertNotNull(threadIdOf(chatId))
            assertEquals(listOf(if (failStartup) "thread.failed" else "thread.cancelled"), terminalEvents(threadId))
            assertTrue(backend.clientThreadRegistry.isEmpty())
            assertTrue(llm.requests.isEmpty())
            assertTrue(logs.isEmpty())
        } }
    }

    @Test
    fun `acceptance failure after caller cancellation is logged and discards registration`() =
        backendE2eTest("e2e_ws_orphaned_failure") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            sql { connection -> connection.createStatement().use {
                it.execute("alter table client_requests add constraint e2e_acceptance_failure check (request_id <> 'orphaned')")
            } }
            withServiceLogs { logs ->
                withTableLocked("agent_executions", "share") {
                    val caller = async { submitDirectly(chatId, messageFrame(chatId, userId, "orphaned")) }
                    awaitLockWait("agent_executions")
                    withTimeout(2.seconds) { caller.cancelAndJoin() }
                }
                eventually("logged acceptance failure") {
                    logs.firstOrNull { it.formattedMessage.startsWith("Thread acceptance failed") && it.throwableProxy != null }
                }
                eventually("discarded registration") { Unit.takeIf { backend.clientThreadRegistry.isEmpty() } }
            }
            assertNull(threadIdOf(chatId))
        }

    private suspend fun BackendE2eScope.submitDirectly(chatId: String, submit: String) {
        val service = backend.dependencies.publicClientService
        service.handleMessage(service.requireChat(UUID.fromString(chatId), "backend"),
            json.readTree(submit).decodeClientFrame(MessageSubmitFrame::class.java))
    }

    private fun BackendE2eScope.rejectStartupMessage() = sql { connection -> connection.createStatement().use {
        it.execute("alter table messages add constraint e2e_startup_failure check (content <> 'startup')")
    } }

    private fun BackendE2eScope.threadIdOf(chatId: String): UUID? = sql { connection ->
        connection.prepareStatement("select id from agent_executions where chat_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(chatId))
            statement.executeQuery().use { rows -> if (rows.next()) rows.getObject("id", UUID::class.java) else null }
        }
    }

    private fun BackendE2eScope.terminalEvents(threadId: UUID): List<String> = sql { connection ->
        connection.prepareStatement("select type from agent_events where execution_id = ? and type like 'thread.%'").use { statement ->
            statement.setObject(1, threadId)
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString("type")) } }
        }
    }

    private suspend fun withServiceLogs(block: suspend (ConcurrentLinkedQueue<ILoggingEvent>) -> Unit) {
        val logs = ConcurrentLinkedQueue<ILoggingEvent>()
        val logger = LoggerFactory.getLogger(PublicClientService::class.java) as Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) { logs.add(event) }
        }.apply { start() }
        logger.addAppender(appender)
        try { block(logs) } finally { logger.detachAppender(appender); appender.stop() }
    }
}
