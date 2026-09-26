package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.llms.LLMResponse
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.hookInput
import ru.souz.backend.hooks.sha256
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.runtime.sandbox.DefaultRuntimeSandboxFactory
import ru.souz.runtime.sandbox.RuntimeSandboxModeResolver
import ru.souz.runtime.sandbox.docker.DockerRuntimeSandbox

class BackendHooksE2eTest {
    @TempDir lateinit var workspace: Path
    private val owner = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    private val secret = "a".repeat(64)

    @Test
    fun `bearer ingress is isolated durable idempotent and reloadable without a user chat`() {
        writeHook()
        runHooks {
            setupOwner()
            for ((token, payload, expected) in listOf(
                Triple("bad", "{}", 401), Triple(secret, "bad json", 400),
                Triple(secret, "{} {}", 400), Triple(secret, "\"${"x".repeat(65_536)}\"", 413),
            )) assertEquals(expected, invoke(token = token, payload = payload).status.value)
            assertTrue(llm.requests.isEmpty())
            assertEquals(404, client.post("/hooks/missing") { jsonBody("{}"); header("Authorization", "Bearer $secret") }.status.value)
            val accepted = coroutineScope {
                List(4) { async { invoke(key = "same", payload = "{\"ownerUserId\":\"$other\"}") } }.awaitAll()
            }
            assertEquals(1, accepted.count { it.status == HttpStatusCode.Accepted })
            val ids = accepted.map { it.jsonBody()["receiptId"].asText() }.toSet()
            assertEquals(1, ids.size)
            val id = ids.single()
            val status = awaitStatus(id, "completed")
            assertEquals(owner, llm.requestLogContexts.single()["userId"])
            assertEquals(1, status["llmCalls"].asInt())
            assertTrue(status["totalTokens"].asInt() > 0)
            assertTrue(status["result"].asText().contains("Check the event"))
            assertEquals(1, llm.requests.size)
            assertTrue(llm.requests.single().conversationPrompt().contains("untrusted external event"))
            assertTrue(llm.requests.single().maxTokens <= 4096)
            assertEquals(409, invoke(key = "same", payload = "{}").status.value)
            assertEquals(404, client.get("/v1/hooks/receipts/$id") { trusted(other) }.status.value)
            assertEquals(0, client.get(BackendHttpRoutes.CHATS) { trusted(owner) }.jsonBody()["items"].size())

            val next = invoke(key = "next").jsonBody()["receiptId"].asText()
            val nextStatus = awaitStatus(next, "completed")
            assertNotEquals(status["execution"]["chatId"], nextStatus["execution"]["chatId"])
            assertEquals(2, llm.requests.size)
            assertTrue(other !in llm.requests.last().conversationPrompt(), "A new event must not inherit the previous event's context")

            val newSecret = "b".repeat(64)
            writeHook(token = newSecret)
            reload()
            assertEquals(401, invoke().status.value)
            assertEquals(202, invoke(token = newSecret).status.value)
            writeHook(token = newSecret, enabled = false)
            reload()
            assertEquals(503, invoke(token = newSecret).status.value)
            // Mixing auth modes cannot silently turn into an unauthenticated hook.
            Files.writeString(workspace.resolve("hooks/check/hook.yaml"), "verify: {}\n", java.nio.file.StandardOpenOption.APPEND)
            reload()
            assertEquals(404, invoke(token = newSecret).status.value)

        }
    }

    @Test
    fun `nested calls stop at persisted budget and queue admission is bounded`() {
        writeHook()
        val llm = E2eLlmApi().apply { requestSkill("ListActiveChannels", emptyMap()); pauseUntilReleased() }
        runHooks(HookConfig(setOf(owner), queuePerHook = 2, llmCallsPerEvent = 1, llmCallsPerUserPerDay = 1), llm) {
            setupOwner()
            val first = invoke().jsonBody()["receiptId"].asText()
            eventually("first provider request") { llm.requests.takeIf { it.size == 1 } }
            val second = invoke().jsonBody()["receiptId"].asText()
            assertEquals(429, invoke().status.value)
            llm.release()
            val failed = awaitStatus(first, "failed")
            assertEquals(1, failed["llmCalls"].asInt())
            awaitStatus(second, "failed")
            assertEquals(1, llm.requests.size, "No second provider call, including the agent's tool continuation")
        }
    }

    @Test
    fun `initial hook turns and option continuations share capacity regardless of hook ID order`() {
        for ((waitingHook, otherHook) in listOf("a" to "z", "z" to "a")) {
            writeHook(id = waitingHook, directory = waitingHook)
            writeHook(id = otherHook, directory = otherHook)
            val entered = Channel<String>(Channel.UNLIMITED)
            val release = Channel<Unit>(Channel.UNLIMITED)
            val options = ScriptedOptionTurnRunner()
            val runner = object : BackendConversationTurnRunner {
                override suspend fun run(conversationKey: AgentConversationKey, request: BackendConversationTurnRequest,
                    eventSink: AgentRuntimeEventSink, initialUsage: LLMResponse.Usage): BackendConversationTurnOutcome {
                    entered.send(requireNotNull(request.executionId))
                    release.receive()
                    return options.run(conversationKey, request, eventSink, initialUsage)
                }
            }
            runHooks(HookConfig(setOf(owner), concurrentExecutions = 1), runner = runner) {
                setupOwner()
                suspend fun answer(receipt: JsonNode) {
                    val chatId = receipt["execution"]["chatId"].asText()
                    val optionId = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(owner) }.jsonBody()["items"]
                        .first { it["type"].asText() == "option.requested" }["payload"]["optionId"].asText()
                    assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.optionAnswer(optionId)) {
                        trusted(owner); jsonBody("""{"selectedOptionIds":["a"]}""")
                    }.status)
                }
                withTimeout(15_000) {
                    val first = invoke(id = waitingHook).jsonBody()["receiptId"].asText()
                    assertEquals(first, entered.receive())
                    val second = invoke(id = otherHook).jsonBody()["receiptId"].asText()
                    assertNull(withTimeoutOrNull(350) { entered.receive() }, "Initial turn must wait for capacity")
                    release.send(Unit)
                    assertEquals(second, entered.receive(), "Waiting for an option must release capacity")
                    answer(awaitStatus(first, "waiting_option"))
                    assertNull(withTimeoutOrNull(350) { entered.receive() }, "Continuation must wait for capacity")
                    release.send(Unit)
                    assertEquals(first, entered.receive())
                    release.send(Unit)
                    awaitStatus(first, "completed")
                    answer(awaitStatus(second, "waiting_option"))
                    assertEquals(second, entered.receive())
                    release.send(Unit)
                    awaitStatus(second, "completed")
                }
            }
        }
    }

    @Test
    fun `docker workspace ownership and ambiguous IDs fail closed`() {
        fun userWorkspace(user: String) = workspace.resolve(Base64.getUrlEncoder().withoutPadding().encodeToString(user.toByteArray())).resolve("workspace")
        val ownRoot = userWorkspace(owner)
        val otherRoot = userWorkspace(other)
        writeHook(base = ownRoot)
        writeHook(base = otherRoot, id = "other", hookOwner = other)
        backendE2eTest("e2e_hook_owners", hookConfig = HookConfig(setOf(owner, other)), sandboxFactory = { settings ->
            DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "docker" }, dockerHostRoot = workspace,
                dockerSandboxCreator = { scope, root, image, name -> DockerRuntimeSandbox(scope, root, image, name, autoStart = false) })
        }) {
            setupOwner()
            setupOwner(other)
            val first = invoke().jsonBody()["receiptId"].asText()
            awaitStatus(first, "completed")
            val second = client.post("/hooks/other") { header("Authorization", "Bearer $secret"); jsonBody("{}") }.jsonBody()["receiptId"].asText()
            eventually("other owner execution") {
                client.get("/v1/hooks/receipts/$second") { trusted(other) }.jsonBody().takeIf { it["status"].asText() == "completed" }
            }
            assertEquals(setOf(owner, other), llm.requestLogContexts.map { it["userId"] }.toSet())
            assertEquals(404, client.get("/v1/hooks/receipts/$second") { trusted(owner) }.status.value)
            writeHook(base = ownRoot, hookOwner = other)
            reload()
            assertEquals(404, invoke().status.value)
            writeHook(base = ownRoot, id = "other")
            reload()
            assertEquals(404, client.post("/hooks/other") { header("Authorization", "Bearer $secret"); jsonBody("{}") }.status.value)
            Files.delete(ownRoot.resolve("hooks/check/hook.yaml"))
            Files.createSymbolicLink(ownRoot.resolve("hooks/check/hook.yaml"), otherRoot.resolve("hooks/check/hook.yaml"))
            reload()
            assertEquals(404, invoke().status.value)
        }
    }

    @Test
    fun `hook selects owned channel and restart recovers pending receipts without replaying running work`() {
        for ((initial, terminal) in listOf("running" to "failed", "cancelling" to "cancelled")) {
            writeHook()
            runHooks {
                setupOwner()
                val target = createPublicChat(owner)
                val prompt = "Send event to the selected channel"
                writeHook(prompt = prompt)
                reload()
                llm.requestSkillForPrompt(hookInput(prompt, "{}"), "SendMessageToChannel", mapOf(
                    "channelType" to "public_client", "channelId" to target, "text" to "hook delivery",
                ))
                val delivered = invoke().jsonBody()["receiptId"].asText()
                awaitStatus(delivered, "completed")
                assertTrue(client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
                    .any { it["content"].asText() == "hook delivery" })

                llm.pauseUntilReleased()
                val callCount = llm.requests.size
                val interrupted = UUID.fromString(invoke().jsonBody()["receiptId"].asText())
                eventually("provider call in progress") { llm.requests.takeIf { it.size > callCount } }
                val chatId = UUID.fromString(awaitStatus(interrupted.toString(), "running")["execution"]["chatId"].asText())
                val events = backend.dependencies.eventService.listByChat(owner, chatId)
                assertTrue(events.any { it.type.value == "execution.started" })
                assertTrue(events.none { it.type.value in setOf("execution.finished", "execution.failed", "execution.cancelled") })
                val pending = UUID.fromString(invoke().jsonBody()["receiptId"].asText())

                // Keep independent DB access after stopping the host; restore the interrupted
                // state and remove only the cancellation event produced by graceful test shutdown.
                PostgresDataSourceFactory.create(postgresAppConfig(sql { it.schema }).postgres).use { db ->
                    backend.close()
                    db.connection.use { c ->
                        c.prepareStatement("""
                            update agent_executions set status = ?, finished_at = null, cancel_requested = ?,
                              error_code = null, error_message = null where id = ?
                        """.trimIndent()).use { s ->
                            s.setString(1, initial); s.setBoolean(2, initial == "cancelling"); s.setObject(3, interrupted); s.executeUpdate()
                        }
                        c.prepareStatement("delete from agent_events where execution_id = ? and type = 'execution.cancelled'").use { s ->
                            s.setObject(1, interrupted); s.executeUpdate()
                        }
                    }
                    val restartedLlm = E2eLlmApi()
                    var terminalEventId: UUID? = null
                    for (attempt in 0..2) {
                        db.connection.use { c ->
                            // Attempt 1 models a crash between state and event writes;
                            // attempt 2 models a crash between event and receipt writes.
                            if (attempt == 1) c.prepareStatement("delete from agent_events where execution_id = ? and type = ?").use { s ->
                                s.setObject(1, interrupted); s.setString(2, "execution.$terminal"); s.executeUpdate()
                            }
                            c.prepareStatement("update hook_receipts set status = 'running' where id = ?").use { s ->
                                s.setObject(1, interrupted); s.executeUpdate()
                            }
                        }
                        backend.createPeer(restartedLlm).use { peer ->
                            assertEquals(terminal, peer.dependencies.hookService.status(owner, interrupted).status)
                            val event = peer.dependencies.eventService.listByChat(owner, chatId)
                                .filter { it.type.value in setOf("execution.finished", "execution.failed", "execution.cancelled") }.single()
                            assertEquals("execution.$terminal", event.type.value)
                            if (attempt == 2) assertEquals(terminalEventId, event.id)
                            terminalEventId = event.id
                            eventually("pending receipt after restart $initial/$attempt") {
                                peer.dependencies.hookService.status(owner, pending).also {
                                    check(it.status !in setOf("failed", "cancelled")) { "Pending receipt $initial/$attempt: $it" }
                                }.takeIf { it.status == "completed" }
                            }
                            peer.awaitExecution(pending)
                            assertEquals(1, restartedLlm.requests.size, "Interrupted work must not be replayed")
                        }
                    }
                }
            }
        }
    }

    private fun writeHook(token: String = secret, enabled: Boolean = true, prompt: String = "Check the event", base: Path = workspace, id: String = "check", hookOwner: String = owner, directory: String = "check") {
        val file = base.resolve("hooks/$directory/hook.yaml")
        Files.createDirectories(file.parent)
        Files.writeString(file, """
            version: 1
            hookId: $id
            ownerUserId: $hookOwner
            enabled: $enabled
            auth:
              type: bearer
              tokenSha256: ${sha256(token.toByteArray())}
            prompt: $prompt
        """.trimIndent())
    }

    private fun runHooks(config: HookConfig = HookConfig(setOf(owner)), llm: E2eLlmApi = E2eLlmApi(), runner: BackendConversationTurnRunner? = null, block: suspend BackendE2eScope.() -> Unit) =
        backendE2eTest("e2e_hooks", hookConfig = config, llm = llm, turnRunnerOverride = runner,
            featureFlags = BackendFeatureFlags(wsEvents = true, options = runner != null), sandboxFactory = { settings ->
            DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)
        }, block = block)

    private suspend fun BackendE2eScope.setupOwner(user: String = owner) {
        assertEquals(200, client.patch(BackendHttpRoutes.SETTINGS) {
            trusted(user)
            jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":false}""")
        }.status.value)
    }

    private suspend fun BackendE2eScope.reload() = client.post("/v1/hooks/reload") { trusted(owner) }.also { assertEquals(200, it.status.value) }

    private suspend fun BackendE2eScope.invoke(token: String = secret, payload: String = "{}", key: String? = null, id: String = "check") =
        client.post("/hooks/$id") {
            header("Authorization", "Bearer $token")
            header("X-User-Id", other)
            if (key != null) header("Idempotency-Key", key)
            jsonBody(payload)
        }

    private suspend fun BackendE2eScope.awaitStatus(id: String, expected: String): JsonNode {
        var last: JsonNode? = null
        return try {
            eventually("hook $id $expected") {
                val response = client.get("/v1/hooks/receipts/$id") { trusted(owner) }
                assertEquals(HttpStatusCode.OK, response.status)
                val text = response.bodyAsText()
                assertTrue(text.isNotBlank(), "Empty receipt response: ${response.headers}")
                json.readTree(text).also { last = it }
                    .takeIf { it["status"]?.asText() == expected }
            }
        } catch (error: AssertionError) { throw AssertionError("$expected; last receipt: $last", error) }
    }
}
