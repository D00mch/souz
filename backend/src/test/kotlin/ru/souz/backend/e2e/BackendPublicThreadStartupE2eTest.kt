package ru.souz.backend.e2e

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import ru.souz.backend.client.ClientThreadRuntimeRegistry
import ru.souz.backend.client.MessageSubmitFrame
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.decodeClientFrame

class BackendPublicThreadStartupE2eTest {
    @Test
    fun `lost acknowledgement releases the final event after the configured wait`() =
        backendE2eTest("e2e_ws_lost_ack_event", clientAckWaitMs = 500) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            withLogs(ClientThreadRuntimeRegistry::class.java.name, Level.WARN) { warnings ->
                val threadId = submitAndDisconnect(chatId, messageFrame(chatId, userId, "lost-ack", text = "lost ack"))
                val terminal = withPublicSocket(chatId) { session -> withTimeout(10.seconds) { readJson(session) } }
                assertEquals("thread.completed", terminal["type"].asText())
                assertEquals(threadId, terminal["threadId"].asText())
                val warning = eventually("lost acknowledgement warning") { warnings.firstOrNull { it.contains(threadId) } }
                assertTrue(warning.startsWith("Client acknowledgement not delivered within"), warning)
            }
        }

    @Test
    fun `thread waiting mid-run on a lost acknowledgement resumes after the configured wait`() =
        backendE2eTest(
            "e2e_ws_lost_ack_mid_run",
            llm = E2eLlmApi().apply { requestSkill("user.ask", mapOf("question" to "Continue?")) },
            clientAckWaitMs = 500,
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            // Accepted and started, but the acknowledgement is never sent.
            submitDirectly(chatId, messageFrame(chatId, userId, "lost-ack", text = "ask me"))
            withPublicSocket(chatId) { session ->
                val started = withTimeout(10.seconds) { readJson(session) }
                assertEquals("tool.call.started", started["type"].asText())
                val threadId = started["threadId"].asText()
                val toolCallId = started["payload"]["toolCallId"].asText()
                session.send(
                    Frame.Text(
                        """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":"$toolCallId","status":"succeeded","result":{"answer":"yes"}}"""
                    )
                )
                assertEquals("accepted", withTimeout(10.seconds) { readJson(session) }["status"].asText())
                assertEquals("thread.completed", withTimeout(10.seconds) { readJson(session) }["type"].asText())
            }
        }

    @Test
    fun `retried request within the wait gets its acknowledgement before the thread events`() =
        backendE2eTest("e2e_ws_lost_ack_retry") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "lost-ack", text = "lost ack")
            withLogs(ClientThreadRuntimeRegistry::class.java.name, Level.INFO) { logs ->
                val threadId = submitAndDisconnect(chatId, submit)
                eventually("stored thread completion", 10.seconds) {
                    threadStatus(chatId, threadId).takeIf { it == "completed" }
                }
                delay(1_100.milliseconds) // late enough for the retry to count as a slow delivery
                // The single-chat socket replays stored events before it reads the retried request.
                withPublicSocket(chatId) { session ->
                    session.send(Frame.Text(submit))
                    val ack = withTimeout(10.seconds) { readJson(session) }
                    val status = readJson(session)
                    val terminal = withTimeout(10.seconds) { readJson(session) }
                    assertEquals("ack", ack["kind"].asText())
                    assertTrue(ack["duplicate"].asBoolean())
                    assertEquals("thread.status", status["type"].asText())
                    assertEquals("thread.completed", terminal["type"].asText())
                    assertEquals(threadId, terminal["threadId"].asText())
                }
                val delivered = eventually("slow acknowledgement log") { logs.firstOrNull { it.contains(threadId) } }
                assertTrue(delivered.startsWith("Client acknowledgement delivered after"), delivered)
            }
        }

    @Test
    fun `shutdown releases a lost acknowledgement instead of waiting for it`() =
        backendE2eTest("e2e_ws_lost_ack_shutdown") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "lost-ack", text = "lost ack")
            val threadId = submitAndDisconnect(chatId, submit)
            eventually("stored thread completion", 10.seconds) {
                threadStatus(chatId, threadId).takeIf { it == "completed" }
            }
            coroutineScope {
                val stopping = async { backend.shutdown() }
                val stopped = withTimeoutOrNull(5.seconds) { stopping.await() } != null
                // A stuck shutdown would also block closing the test backend, so release it first.
                if (!stopped) acknowledgeByRetry(chatId, submit)
                assertTrue(stopped, "Shutdown did not finish within 5 seconds while thread $threadId waited.")
            }
        }

    @Test
    fun `startup failure is logged and reports thread failed after the acknowledgement`() =
        backendE2eTest("e2e_ws_startup_failure") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            sql { connection ->
                connection.createStatement().use {
                    it.execute("alter table messages add constraint e2e_startup_failure check (content <> 'fail startup')")
                }
            }
            withLogs(PublicClientService::class.java.name, Level.ERROR) { errors ->
                withPublicSocket(chatId) { session ->
                    session.send(Frame.Text(messageFrame(chatId, userId, "startup", text = "fail startup")))
                    val ack = readJson(session)
                    val status = readJson(session)
                    val terminal = withTimeout(10.seconds) { readJson(session) }
                    assertEquals("accepted", ack["status"].asText())
                    assertEquals("failed", status["status"].asText())
                    assertEquals("thread.failed", terminal["type"].asText())
                    assertEquals(ack["thread"]["id"].asText(), terminal["threadId"].asText())
                    assertEquals("internal_error", terminal["payload"]["error"]["code"].asText())
                }
                assertTrue(errors.any { it.contains("e2e_startup_failure") }, "Startup failure was not logged: $errors")
            }
        }

    @Test
    fun `startup failure during shutdown still stores thread failed`() =
        backendE2eTest("e2e_ws_startup_failure_shutdown", clientAckWaitMs = 500) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            sql { connection ->
                connection.createStatement().use {
                    it.execute("alter table messages add constraint e2e_startup_failure check (content <> 'fail startup')")
                }
            }
            val submit = messageFrame(chatId, userId, "startup", text = "fail startup")
            withTimeout(10.seconds) {
                withTableLocked("agent_executions", "share") {
                    launch { submitDirectly(chatId, submit) }
                    eventually("acceptance waiting for its execution row") { lockWaiting("agent_executions").takeIf { it } }
                    // The startup fails only after shutdown has cancelled application work.
                    launch { backend.applicationScope.cancelAndJoin() }
                    eventually("cancelled application work") {
                        backend.applicationScope.coroutineContext[Job]?.isCancelled?.takeIf { it }
                    }
                }
            }

            val threadId = assertNotNull(threadIdOf(chatId))
            assertEquals("thread.failed", eventually("stored final event") { terminalEventType(threadId) })
        }

    @Test
    fun `socket cancelled during preparation leaves no thread behind`() =
        backendE2eTest("e2e_ws_cancelled_preparation") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "prepared", text = "cancelled preparation")
            withTableLocked("user_settings", "access exclusive") {
                val socketWait = async { submitDirectly(chatId, submit) }
                eventually("preparation waiting for settings") { lockWaiting("user_settings").takeIf { it } }
                socketWait.cancel()
            }
            // Joining application work waits for any acceptance the socket could have handed over.
            backend.applicationScope.cancelAndJoin()
            assertNull(threadIdOf(chatId))
        }

    @Test
    fun `socket stops waiting at once while the application accepts and starts the thread`() =
        backendE2eTest("e2e_ws_owned_acceptance") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "owned", text = "owned acceptance")
            try {
                withTableLocked("agent_executions", "share") {
                    val socketWait = async { submitDirectly(chatId, submit) }
                    eventually("acceptance waiting for its execution row") { lockWaiting("agent_executions").takeIf { it } }
                    withTimeout(2.seconds) { socketWait.cancelAndJoin() }
                }
                val threadId = eventually("accepted thread") { threadIdOf(chatId) }
                eventually("completed thread", 10.seconds) {
                    threadStatus(chatId, threadId).takeIf { it == "completed" }
                }
            } finally {
                acknowledgeByRetry(chatId, submit)
            }
        }

    @Test
    fun `application shutdown waits until an accepted thread finishes starting`() =
        backendE2eTest("e2e_ws_shutdown_joins_acceptance") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val submit = messageFrame(chatId, userId, "stopping", text = "stopping acceptance")
            withTimeout(10.seconds) {
                withTableLocked("agent_executions", "share") {
                    // The stopping application cancels the socket's wait; the handed-over work still finishes.
                    launch { submitDirectly(chatId, submit) }
                    eventually("acceptance waiting for its execution row") { lockWaiting("agent_executions").takeIf { it } }
                    val stopping = launch { backend.applicationScope.cancelAndJoin() }
                    delay(300.milliseconds)
                    assertFalse(stopping.isCompleted, "Application work stopped before the accepted thread finished starting.")
                }
            }

            val threadId = assertNotNull(threadIdOf(chatId))
            assertEquals("running", threadStatus(chatId, threadId))
        }

    @Test
    fun `acceptance failure after the socket stopped waiting is logged`() =
        backendE2eTest("e2e_ws_orphaned_acceptance_failure") {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            sql { connection ->
                connection.createStatement().use {
                    it.execute("alter table client_requests add constraint e2e_orphaned_failure check (request_id <> 'orphaned')")
                }
            }
            val submit = messageFrame(chatId, userId, "orphaned", text = "orphaned acceptance")
            withLogs(PublicClientService::class.java.name, Level.ERROR) { errors ->
                withTableLocked("agent_executions", "share") {
                    val socketWait = async { submitDirectly(chatId, submit) }
                    eventually("acceptance waiting for its execution row") { lockWaiting("agent_executions").takeIf { it } }
                    withTimeout(2.seconds) { socketWait.cancelAndJoin() }
                }
                eventually("logged acceptance failure") { errors.firstOrNull { it.contains("e2e_orphaned_failure") } }
            }
            assertNull(threadIdOf(chatId))
        }

    // The socket handler calls the service the same way; cancelling this call is a socket that stopped waiting.
    private suspend fun BackendE2eScope.submitDirectly(chatId: String, submit: String): JsonNode {
        val service = backend.dependencies.publicClientService
        val chat = service.requireChat(UUID.fromString(chatId), "backend")
        return service.handleMessage(chat, json.readTree(submit).decodeClientFrame(MessageSubmitFrame::class.java))
            .response as JsonNode
    }

    // The client sends the request and closes at once, so the server cannot deliver its acknowledgement.
    private suspend fun BackendE2eScope.submitAndDisconnect(chatId: String, submit: String): String {
        webSocketClient().use { wsClient ->
            val session = wsClient.webSocketSession("${BackendHttpRoutes.chatWebSocket(chatId)}?clientType=backend")
            session.send(Frame.Text(submit))
            session.close()
        }
        return eventually("accepted thread", 10.seconds) { threadIdOf(chatId) }
    }

    // Pauses the backend at its first conflicting access to the table until the block ends. A share lock
    // on agent_executions lets reads and the chat row lock through but holds new execution rows.
    private suspend fun <T> BackendE2eScope.withTableLocked(
        table: String,
        mode: String,
        block: suspend CoroutineScope.() -> T,
    ): T = coroutineScope {
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) {
            sql { connection ->
                connection.autoCommit = false
                connection.createStatement().use { it.execute("lock table $table in $mode mode") }
                locked.complete(Unit)
                runBlocking { release.await() }
                connection.rollback()
            }
        }
        locked.await()
        try {
            block()
        } finally {
            release.complete(Unit)
            lock.join()
        }
    }

    private fun BackendE2eScope.lockWaiting(table: String): Boolean = sql { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "select count(*) from pg_locks where not granted and relation = '$table'::regclass"
            ).use { rows -> rows.next() && rows.getInt(1) > 0 }
        }
    }

    private fun BackendE2eScope.threadIdOf(chatId: String): String? = sql { connection ->
        connection.prepareStatement("select id from agent_executions where chat_id = ?").use { statement ->
            statement.setObject(1, UUID.fromString(chatId))
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString("id") else null }
        }
    }

    private fun BackendE2eScope.terminalEventType(threadId: String): String? = sql { connection ->
        connection.prepareStatement(
            "select type from agent_events where execution_id = ? and type in ('thread.completed', 'thread.failed', 'thread.cancelled')"
        ).use { statement ->
            statement.setObject(1, UUID.fromString(threadId))
            statement.executeQuery().use { rows -> if (rows.next()) rows.getString("type") else null }
        }
    }

    private suspend fun BackendE2eScope.threadStatus(chatId: String, threadId: String): String =
        client.get("${BackendHttpRoutes.chatThread(chatId, threadId)}?clientType=backend").jsonBody()["status"].asText()

    // A retried submit replays the stored acknowledgement; its status frame follows the acknowledgement release.
    private suspend fun BackendE2eScope.acknowledgeByRetry(chatId: String, submit: String) {
        withPublicSocket(chatId) { session ->
            session.send(Frame.Text(submit))
            do {
                val kind = readJson(session)["kind"].asText()
            } while (kind != "ack")
            readJson(session)
        }
    }

    private suspend fun <T> withLogs(
        loggerName: String,
        level: Level,
        block: suspend (ConcurrentLinkedQueue<String>) -> T,
    ): T {
        val messages = ConcurrentLinkedQueue<String>()
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (!event.level.isGreaterOrEqual(level)) return
                val causes = generateSequence(event.throwableProxy) { it.cause }.joinToString(" ") { it.message.orEmpty() }
                messages.add("${event.formattedMessage} $causes")
            }
        }.apply { start() }
        logger.addAppender(appender)
        return try {
            block(messages)
        } finally {
            logger.detachAppender(appender)
        }
    }
}
