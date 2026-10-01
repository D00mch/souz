package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.newPostgresSchema

class BackendExecutionFinalizationE2eTest {
    @Test
    fun `stopped scope finalizes HTTP execution before startup without logging usage`() =
        backendE2eTest("e2e_before_start") {
            withTokenUsageLogs { logs ->
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                backend.applicationScope.cancelAndJoin()
                val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId)
                    jsonBody("""{"content":"never starts","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                }
                val executionId = UUID.fromString(sent.jsonBody()["execution"]["id"].asText())
                assertEquals("cancelled", threadStatus(chatId, executionId))
                assertTrue(llm.requests.isEmpty())
                assertTrue(logs.isEmpty())
                val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(1, events.count { it["type"].asText() == "execution.cancelled" })
            }
        }

    @Test
    fun `old runtime owner cannot renew a lease or cancel the replacement owner`() =
        backendE2eTest("e2e_owner_fencing", llm = E2eLlmApi().apply { hangUntilCancelled() }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val executionId = withPublicSocket(chatId) { socket ->
                socket.send(Frame.Text(messageFrame(chatId, userId, "own", text = "work")))
                UUID.fromString(readJson(socket)["thread"]["id"].asText())
            }
            llm.awaitPrompt("work")
            val started = checkNotNull(backend.executionRepository.get(userId, executionId))
            // Simulate ownership transfer while the old process still has a live job.
            sql { connection -> connection.prepareStatement("update agent_executions set runtime_owner = 'replacement' where id = ?")
                .use { it.setObject(1, executionId); assertEquals(1, it.executeUpdate()) } }
            assertNull(backend.executionRepository.refreshClientThreadLease(userId, UUID.fromString(chatId), executionId,
                checkNotNull(started.runtimeOwner), checkNotNull(started.runtimeLeaseUntil)))
            backend.dependencies.executionService.propagateCancellation(started)
            backend.awaitExecution(executionId)
            backend.dependencies.executionService.finalizeInterruptedExecution(started)
            assertEquals("running", threadStatus(chatId, executionId))
            val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertTrue(events.none { it["type"].asText().startsWith("thread.") })
        }

    @Test
    fun `saved option wait survives shutdown and resumes or explicitly cancels after restart`() {
        for (cancel in listOf(false, true)) {
            val schema = newPostgresSchema("e2e_wait_restart")
            val userId = UUID.randomUUID().toString()
            lateinit var chatId: String
            lateinit var executionId: UUID
            lateinit var optionId: String
            backendE2eTest("e2e_wait_save", schema = schema,
                featureFlags = BackendFeatureFlags(wsEvents = true, options = true),
                turnRunnerOverride = ScriptedOptionTurnRunner()) {
                withTokenUsageLogs { logs ->
                    chatId = createPublicChat(userId)
                    val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                        trusted(userId); jsonBody("""{"content":"need option","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                    }
                    executionId = UUID.fromString(sent.jsonBody()["execution"]["id"].asText())
                    backend.awaitExecution(executionId)
                    backend.applicationScope.cancelAndJoin()
                    val waiting = checkNotNull(backend.executionRepository.get(userId, executionId))
                    backend.dependencies.executionService.propagateCancellation(waiting)
                    assertEquals("waiting_option", threadStatus(chatId, executionId))
                    optionId = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .single { it["type"].asText() == "option.requested" }["payload"]["optionId"].asText()
                    assertTrue(logs.isEmpty())
                }
            }
            backendE2eTest("e2e_wait_resume", schema = schema,
                featureFlags = BackendFeatureFlags(wsEvents = true, options = true)) {
                withTokenUsageLogs { logs ->
                    val response = if (cancel) client.post(BackendHttpRoutes.cancelActive(chatId)) { trusted(userId) } else
                        client.post(BackendHttpRoutes.optionAnswer(optionId)) {
                            trusted(userId); jsonBody("""{"selectedOptionIds":["a"]}""")
                        }
                    assertEquals(HttpStatusCode.OK, response.status)
                    backend.awaitExecution(executionId)
                    assertEquals(if (cancel) "cancelled" else "completed", threadStatus(chatId, executionId))
                    assertEquals(if (cancel) 0 else 1, logs.size)
                    if (!cancel) {
                        assertTrue(llm.requests.first().messages.any { it.content == "waiting for option" })
                        assertEquals("15", logs.single()["kvpList"].first { it.has("total_tokens") }["total_tokens"].asText())
                    }
                    val terminal = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        .map { it["type"].asText() }.filter { it.startsWith("execution.") && it != "execution.started" }
                    assertEquals(listOf(if (cancel) "execution.cancelled" else "execution.finished"), terminal)
                }
            }
        }
    }

    @Test
    fun `recovery wins completion and late cancellation without another terminal event or usage log`() =
        backendE2eTest("e2e_recovery_race", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            withTokenUsageLogs { logs ->
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                val executionId = withPublicSocket(chatId) { socket ->
                    socket.send(Frame.Text(messageFrame(chatId, userId, "recover", text = "finish")))
                    UUID.fromString(readJson(socket)["thread"]["id"].asText())
                }
                llm.awaitPrompt("finish")
                val started = checkNotNull(backend.executionRepository.get(userId, executionId))
                withPeerBackend { peer ->
                    withTableLocked("agent_conversation_state", "share") {
                        llm.release()
                        awaitLockWait("agent_conversation_state")
                        sql { connection -> connection.prepareStatement(
                            "update agent_executions set runtime_lease_until = now() - interval '1 minute' where id = ?"
                        ).use { it.setObject(1, executionId); assertEquals(1, it.executeUpdate()) } }
                        repeat(2) { peer.backend.recoverClientThreads() }
                    }
                    backend.awaitExecution(executionId)
                    repeat(2) { backend.dependencies.executionService.propagateCancellation(started) }
                    assertEquals("failed", threadStatus(chatId, executionId))
                    assertTrue(logs.isEmpty())
                    withPublicSocket(chatId) { socket ->
                        val terminal = readJson(socket)
                        assertEquals("thread.failed", terminal["type"].asText())
                        assertEquals("internal_error", terminal["payload"]["error"]["code"].asText())
                    }
                    val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                    assertEquals(listOf("thread.failed"), events.map { it["type"].asText() }.filter { it.startsWith("thread.") })
                }
            }
        }

    @Test
    fun `shutdown cancels option handoff before its event or session is saved`() {
        for (table in listOf("agent_events", "agent_conversation_state")) {
            val releaseChoice = CompletableDeferred<Unit>()
            val runner = ScriptedOptionTurnRunner { releaseChoice.await() }
            backendE2eTest("e2e_option_handoff", featureFlags = BackendFeatureFlags(wsEvents = true, options = true),
                turnRunnerOverride = runner) {
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId); jsonBody("""{"content":"need option","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                }
                val executionId = UUID.fromString(sent.jsonBody()["execution"]["id"].asText())
                withTableLocked(table, "share") {
                    releaseChoice.complete(Unit)
                    awaitLockWait(table)
                    backend.applicationScope.cancel()
                }
                backend.applicationScope.cancelAndJoin()
                val execution = client.get("${BackendHttpRoutes.chatThread(chatId, executionId)}?clientType=backend") { trusted(userId) }.jsonBody()
                assertEquals("cancelled", execution["status"].asText())
                val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("execution.cancelled"), events.map { it["type"].asText() }.filter {
                    it in setOf("execution.finished", "execution.failed", "execution.cancelled")
                })
            }
        }
    }

    private suspend fun BackendE2eScope.threadStatus(chatId: String, executionId: UUID): String =
        client.get("${BackendHttpRoutes.chatThread(chatId, executionId)}?clientType=backend").jsonBody()["status"].asText()
}
