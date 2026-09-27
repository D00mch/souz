package ru.souz.backend.e2e

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import ru.souz.backend.client.MessageSubmitFrame
import ru.souz.backend.client.PublicClientService
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.http.decodeClientFrame

class BackendPublicThreadStartupE2eTest {
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
            withErrorLogs(PublicClientService::class.java.name) { errors ->
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

    private suspend fun <T> withErrorLogs(loggerName: String, block: suspend (ConcurrentLinkedQueue<String>) -> T): T {
        val errors = ConcurrentLinkedQueue<String>()
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        val appender = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (event.level != Level.ERROR) return
                val causes = generateSequence(event.throwableProxy) { it.cause }.joinToString(" ") { it.message.orEmpty() }
                errors.add("${event.formattedMessage} $causes")
            }
        }.apply { start() }
        logger.addAppender(appender)
        return try {
            block(errors)
        } finally {
            logger.detachAppender(appender)
        }
    }
}
