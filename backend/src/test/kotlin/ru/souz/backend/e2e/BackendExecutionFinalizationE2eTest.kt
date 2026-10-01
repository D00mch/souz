package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.execution.model.AgentExecutionStatus
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
    fun `completion and late cleanup preserve recovery cancellation and replacement ownership`() {
        for (winner in listOf("recovery", "cancellation", "owner")) {
            backendE2eTest("e2e_completion_race", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
                withTokenUsageLogs { logs ->
                    val userId = UUID.randomUUID().toString()
                    val chatId = createPublicChat(userId)
                    val executionId = withPublicSocket(chatId) { socket ->
                        socket.send(Frame.Text(messageFrame(chatId, userId, "race", text = "finish")))
                        UUID.fromString(readJson(socket)["thread"]["id"].asText())
                    }
                    llm.awaitPrompt("finish")
                    val started = checkNotNull(backend.executionRepository.get(userId, executionId))
                    val updatedAt = chatUpdatedAt(chatId)
                    withPeerBackend { peer ->
                        withTableLocked("chats", "exclusive") {
                            llm.release()
                            awaitLockWait("chats")
                            // Change durable ownership while finalization is waiting to acquire the chat lock.
                            val mutation = when (winner) {
                                "recovery" -> "runtime_lease_until = now() - interval '1 minute'"
                                "cancellation" -> "status = 'cancelling', cancel_requested = true"
                                else -> "runtime_owner = 'replacement'"
                            }
                            sql { connection -> connection.prepareStatement("update agent_executions set $mutation where id = ?")
                                .use { it.setObject(1, executionId); assertEquals(1, it.executeUpdate()) } }
                            if (winner == "recovery") {
                                assertEquals(1, peer.backend.executionRepository.failInterruptedClientThreads(Instant.now()).size)
                            }
                        }
                        backend.awaitExecution(executionId)
                        repeat(2) { peer.backend.recoverClientThreads() }
                        repeat(2) { backend.dependencies.executionService.propagateCancellation(started) }
                        assertNoTurnSaved(chatId, executionId)
                        assertEquals(updatedAt, chatUpdatedAt(chatId))
                        assertTrue(logs.isEmpty())
                        val status = when (winner) {
                            "recovery" -> "failed"
                            "cancellation" -> "cancelled"
                            else -> "running"
                        }
                        assertEquals(status, threadStatus(chatId, executionId))
                        val expectedEvents = if (winner == "owner") emptyList() else listOf("thread.$status")
                        val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                        assertEquals(expectedEvents, events.map { it["type"].asText() }.filter { it.startsWith("thread.") })
                        if (winner != "owner") withPublicSocket(chatId) { socket ->
                            assertEquals(expectedEvents.single(), readJson(socket)["type"].asText())
                        }
                    }
                }
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
                assertNoTurnSaved(chatId, executionId)
                val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("execution.cancelled"), events.map { it["type"].asText() }.filter {
                    it in setOf("execution.finished", "execution.failed", "execution.cancelled")
                })
            }
        }
    }

    @Test
    fun `another worker cancels option handoff without saving its continuation`() {
        val choiceReady = CompletableDeferred<Unit>()
        val releaseChoice = CompletableDeferred<Unit>()
        val runner = ScriptedOptionTurnRunner(afterChoice = { choiceReady.complete(Unit); releaseChoice.await() })
        backendE2eTest("e2e_option_peer_cancel", featureFlags = BackendFeatureFlags(wsEvents = true, options = true),
            turnRunnerOverride = runner) {
            withTokenUsageLogs { logs ->
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                    trusted(userId); jsonBody("""{"content":"need option","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                }
                val executionId = UUID.fromString(sent.jsonBody()["execution"]["id"].asText())
                choiceReady.await()
                val running = checkNotNull(backend.executionRepository.get(userId, executionId))
                withPeerBackend { peer ->
                    // The other worker commits cancellation while the original job is still live.
                    val cancelling = checkNotNull(peer.backend.executionRepository.transitionIfCurrent(running, AgentExecutionStatus.CANCELLING))
                    releaseChoice.complete(Unit)
                    backend.awaitExecution(executionId)
                    peer.backend.dependencies.executionService.propagateCancellation(cancelling)
                    assertEquals("cancelled", threadStatus(chatId, executionId))
                    assertNoTurnSaved(chatId, executionId)
                    assertTrue(logs.isEmpty())
                    val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                    assertEquals(1, events.count { it["type"].asText() == "execution.cancelled" })
                }
            }
        }
    }

    @Test
    fun `session write failure rolls back the completed message and chat update`() =
        backendE2eTest("e2e_completion_rollback", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            withTokenUsageLogs { logs ->
                val userId = UUID.randomUUID().toString()
                val chatId = createPublicChat(userId)
                val executionId = withPublicSocket(chatId) { socket ->
                    socket.send(Frame.Text(messageFrame(chatId, userId, "rollback", text = "finish")))
                    UUID.fromString(readJson(socket)["thread"]["id"].asText())
                }
                llm.awaitPrompt("finish")
                val updatedAt = chatUpdatedAt(chatId)
                // Reject the continuation write after the assistant insert has been attempted.
                sql { connection -> connection.createStatement().use {
                    it.execute("alter table agent_conversation_state add constraint reject_turn check (based_on_message_seq < 0)")
                } }
                llm.release()
                backend.awaitExecution(executionId)
                assertEquals("failed", threadStatus(chatId, executionId))
                assertNoTurnSaved(chatId, executionId)
                assertEquals(updatedAt, chatUpdatedAt(chatId))
                assertTrue(logs.isEmpty())
                val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                assertEquals(listOf("thread.failed"), events.map { it["type"].asText() }.filter { it.startsWith("thread.") })
            }
        }

    @Test
    fun `lifecycle transition preserves concurrent lease device and assistant updates`() =
        backendE2eTest("e2e_transition_fields", llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val executionId = withPublicSocket(chatId) { socket ->
                socket.send(Frame.Text(messageFrame(chatId, userId, "fields", text = "work")))
                UUID.fromString(readJson(socket)["thread"]["id"].asText())
            }
            llm.awaitPrompt("work")
            val stale = checkNotNull(backend.executionRepository.get(userId, executionId))
            val messageId = UUID.randomUUID()
            withPeerBackend { peer ->
                assertTrue(peer.backend.executionRepository.refreshClientThreadLease(
                    userId, UUID.fromString(chatId), executionId, checkNotNull(stale.runtimeOwner), Instant.now().plusSeconds(600),
                ) != null)
                sql { connection ->
                    connection.prepareStatement("""
                        insert into messages(id, user_id, chat_id, seq, role, content, metadata, created_at)
                        select ?, ?, ?, max(seq) + 1, 'assistant', 'peer response', '{}'::jsonb, now()
                        from messages where chat_id = ?
                    """.trimIndent()).use {
                        it.setObject(1, messageId); it.setString(2, userId)
                        it.setObject(3, UUID.fromString(chatId)); it.setObject(4, UUID.fromString(chatId)); it.executeUpdate()
                    }
                    connection.prepareStatement("""
                        update agent_executions set assistant_message_id = ?, latest_device_context = '{"device":"peer"}'::jsonb,
                            metadata = '{"source":"peer"}'::jsonb where id = ?
                    """.trimIndent()).use { it.setObject(1, messageId); it.setObject(2, executionId); it.executeUpdate() }
                }
                val fresh = checkNotNull(peer.backend.executionRepository.get(userId, executionId))
                val cancelling = checkNotNull(backend.executionRepository.transitionIfCurrent(stale, AgentExecutionStatus.CANCELLING))
                assertEquals(fresh.copy(status = AgentExecutionStatus.CANCELLING, cancelRequested = true), cancelling)
                backend.dependencies.executionService.propagateCancellation(cancelling)
                backend.awaitExecution(executionId)
                assertEquals("cancelled", threadStatus(chatId, executionId))
            }
        }

    private fun BackendE2eScope.assertNoTurnSaved(chatId: String, executionId: UUID) = sql { connection ->
        connection.prepareStatement("""
            select (select count(*) from messages where chat_id = ? and role = 'assistant'),
                (select count(*) from agent_conversation_state where chat_id = ?), assistant_message_id
            from agent_executions where id = ?
        """.trimIndent()).use {
            it.setObject(1, UUID.fromString(chatId)); it.setObject(2, UUID.fromString(chatId)); it.setObject(3, executionId)
            it.executeQuery().use { rows ->
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); assertEquals(0, rows.getInt(2)); assertNull(rows.getObject(3))
            }
        }
    }

    private fun BackendE2eScope.chatUpdatedAt(chatId: String): Instant = sql { connection ->
        connection.prepareStatement("select updated_at from chats where id = ?").use {
            it.setObject(1, UUID.fromString(chatId))
            it.executeQuery().use { rows -> check(rows.next()); rows.getTimestamp(1).toInstant() }
        }
    }

    private suspend fun BackendE2eScope.threadStatus(chatId: String, executionId: UUID): String =
        client.get("${BackendHttpRoutes.chatThread(chatId, executionId)}?clientType=backend").jsonBody()["status"].asText()
}
