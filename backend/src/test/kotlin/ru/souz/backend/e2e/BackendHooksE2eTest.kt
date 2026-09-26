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

            // Simulate a lost process after a side effect: recovery must not replay this turn.
            sql { c ->
                c.prepareStatement("""
                    with crashed as (update agent_executions set status = 'running', finished_at = null where id = ? returning id)
                    update hook_receipts set status = 'running' where id in (select id from crashed)
                """.trimIndent()).use { s -> s.setObject(1, UUID.fromString(delivered)); s.executeUpdate() }
            }
            val pending = invoke().jsonBody()["receiptId"].asText()
            backend.close()
            val restartedLlm = E2eLlmApi()
            backend.createPeer(restartedLlm).use { peer ->
                val failed = peer.dependencies.hookService.status(owner, UUID.fromString(delivered))
                assertEquals("failed", failed.status)
                eventually("pending receipt after restart") {
                    peer.dependencies.hookService.status(owner, UUID.fromString(pending)).takeIf { it.status == "completed" }
                }
                assertEquals(1, restartedLlm.requests.size)
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
