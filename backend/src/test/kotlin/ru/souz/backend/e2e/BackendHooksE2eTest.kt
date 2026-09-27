package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import ru.souz.backend.config.BackendConfigSource
import ru.souz.llms.http.ProviderHttpClients
import ru.souz.llms.http.providerHttpClientDefaults
import ru.souz.llms.restJsonMapper
import ru.souz.db.SettingsProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import kotlinx.coroutines.CompletableDeferred
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
import ru.souz.backend.app.BackendLlmLimits
import ru.souz.llms.LLMResponse
import org.junit.jupiter.api.io.TempDir
import ru.souz.backend.hooks.HookConfig
import ru.souz.backend.hooks.hookInput
import ru.souz.backend.hooks.sha256
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.storage.postgres.PostgresDataSourceFactory
import ru.souz.backend.storage.postgres.postgresAppConfig
import ru.souz.backend.storage.postgres.write
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
            val accepted = PostgresDataSourceFactory.create(postgresAppConfig(sql { it.schema }).postgres).use { db ->
                db.connection.use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { it.execute("select id from users where id = '$owner' for key share") }
                    coroutineScope {
                        val requests = async {
                            List(4) { async { invoke(key = "same", payload = "{\"ownerUserId\":\"$other\"}") } }.awaitAll()
                        }
                        // Admission must complete while an unrelated FK check holds KEY SHARE on this owner.
                        try { withTimeout(5_000) { requests.await() } } finally { connection.rollback() }
                    }
                }
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

            val target = createPublicChat(owner)
            val prompt = "Send event to the selected channel"
            writeHook(prompt = prompt)
            reload()
            llm.requestSkillForPrompt(hookInput(prompt, "{}"), "SendMessageToChannel", mapOf(
                "channelType" to "public_client", "channelId" to target, "text" to "hook delivery",
            ))
            awaitStatus(invoke().jsonBody()["receiptId"].asText(), "completed")
            assertTrue(client.get(BackendHttpRoutes.chatMessages(target)) { trusted(owner) }.jsonBody()["items"]
                .any { it["content"].asText() == "hook delivery" })

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
    fun `verifier authenticates raw events with a reloadable snapshot and its own deduplication key`() {
        val directory = Files.createDirectories(workspace.resolve("hooks/check"))
        Files.writeString(directory.resolve("hook.yaml"), """
            version: 1
            hookId: check
            ownerUserId: $owner
            verify: {runtime: PYTHON, script: verify.py}
            prompt: Check the event
        """.trimIndent())
        Files.writeString(directory.resolve("verify.py"), """
            import base64,json,pathlib,sys
            request=json.load(sys.stdin)
            assert request['method']=='POST' and request['path']=='/hooks/check'
            assert base64.b64decode(request['bodyBase64'])==b'raw bytes'
            assert 'x-user-id' not in request['headers'] and 'x-souz-proxy-auth' not in request['headers']
            assert pathlib.Path.cwd().parent==pathlib.Path('$workspace').resolve()
            accepted=request['headers'].get('authorization')==['Bearer $secret']
            print(json.dumps({'accept':accepted,'eventId':'verified','payload':{'normalized':True}}))
        """.trimIndent())
        runHooks {
            setupOwner()
            assertEquals(401, invoke(token = "bad", payload = "raw bytes").status.value)
            assertTrue(llm.requests.isEmpty())
            val first = invoke(payload = "raw bytes", key = "ignored").jsonBody()["receiptId"].asText()
            awaitStatus(first, "completed")
            Files.writeString(directory.resolve("verify.py"), "print('{\"accept\":false}')")
            val duplicate = invoke(payload = "raw bytes", key = "another-key")
            assertEquals(HttpStatusCode.OK, duplicate.status)
            assertEquals(first, duplicate.jsonBody()["receiptId"].asText())
            assertTrue(llm.requests.single().conversationPrompt().contains("normalized"))
            reload()
            assertEquals(401, invoke(payload = "raw bytes").status.value)
            Files.list(workspace).use { paths -> assertTrue(paths.noneMatch { it.fileName.toString().startsWith(".hook-verifier-") }) }
        }
    }

    @Test
    fun `nested calls stop at persisted budget and queue admission is bounded`() {
        writeHook()
        val llm = E2eLlmApi().apply { requestSkill("ListActiveChannels", emptyMap()); pauseUntilReleased() }
        runHooks(HookConfig(setOf(owner), queuePerHook = 2, llmCallsPerEvent = 1, llmCallsPerUserPerDay = 2), llm) {
            setupOwner()
            val first = invoke().jsonBody()["receiptId"].asText()
            eventually("first provider request") { llm.requests.takeIf { it.size == 1 } }
            val second = invoke().jsonBody()["receiptId"].asText()
            assertEquals(429, invoke().status.value)
            llm.release()
            val failed = awaitStatus(first, "failed")
            assertEquals(1, failed["llmCalls"].asInt())
            assertEquals(1, awaitStatus(second, "failed")["llmCalls"].asInt())
            // Failed per-event reservations must roll back the daily counter.
            assertEquals(2, llm.requests.size, "Each event gets one call, including nested tool continuations")
        }
    }

    @Test
    fun `summarization preserves smaller configured budgets and caps larger ones`() {
        writeHook()
        for (limit in listOf(512, 8192)) {
            val requests = java.util.concurrent.CopyOnWriteArrayList<JsonNode>()
            val http = HttpClient(MockEngine { request ->
                requests.add(restJsonMapper.readTree(request.body.toByteArray()))
                respond("""{"choices":[{"index":0,"message":{"role":"assistant","content":"summary"},"finish_reason":"stop"}],"created":1,"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }) { providerHttpClientDefaults() }
            val settings = object : BackendConfigSource {
                override fun env(key: String): String? = when (key) {
                    "OPENAI_SUMMARIZATION_MODEL" -> "summary-model"
                    "OPENAI_SUMMARIZATION_API_KEY" -> "summary-key"
                    "OPENAI_SUMMARIZATION_CONTEXT_SIZE" -> "1"
                    "OPENAI_SUMMARIZATION_PARAMETERS" -> """{"max_completion_tokens":$limit}"""
                    else -> null
                }
                override fun property(key: String): String? = null
            }
            backendE2eTest("e2e_hook_summary", hookConfig = HookConfig(setOf(owner)), sandboxFactory = ::localSandbox,
                settingsSource = settings, providerClients = ProviderHttpClients(standard = http, openAi = http)) {
                setupOwner()
                val result = awaitStatus(invoke().jsonBody()["receiptId"].asText(), "completed")
                assertEquals(minOf(limit, 4096), requests.single()["max_completion_tokens"].asInt())
                assertEquals(2, result["llmCalls"].asInt(), "The summary consumes the same durable event budget")
            }
        }
    }

    @Test
    fun `image generation holds provider capacity through HTTP completion without charging rejected calls`() {
        writeHook()
        writeHook(id = "second", directory = "second")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine {
            entered.complete(Unit)
            release.await()
            respond("""{"data":[{"b64_json":"aW1hZ2U="}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { providerHttpClientDefaults() }
        val settings = object : BackendConfigSource {
            override fun env(key: String): String? = "image-test-key".takeIf { key == "OPENAI_API_KEY" }
            override fun property(key: String): String? = null
        }
        val llm = E2eLlmApi().apply { requestSkill("GenerateImage", mapOf("prompt" to "A tree", "outputPath" to "$workspace/tree.png")) }
        backendE2eTest("e2e_hook_image", llm = llm, hookConfig = HookConfig(setOf(owner)), sandboxFactory = ::localSandbox,
            llmLimits = BackendLlmLimits(globalProviderConcurrency = 1), settingsSource = settings,
            providerClients = ProviderHttpClients(standard = http, openAi = http)) {
            setupOwner()
            val first = invoke().jsonBody()["receiptId"].asText()
            try {
                withTimeout(5_000) { entered.await() }
                val rejected = awaitStatus(invoke(id = "second").jsonBody()["receiptId"].asText(), "completed")
                assertEquals(3, rejected["llmCalls"].asInt(), "Only the three chat calls consume budget when image capacity is full")
                assertTrue(llm.requests.last().messages.any { it.content.contains("Global provider concurrency limit exceeded") })
            } finally {
                release.complete(Unit)
            }
            assertEquals(4, awaitStatus(first, "completed")["llmCalls"].asInt())
            assertEquals(4, awaitStatus(invoke(id = "second").jsonBody()["receiptId"].asText(), "completed")["llmCalls"].asInt())
            assertEquals("image", Files.readString(workspace.resolve("tree.png")))
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
    fun `restart resumes pending receipts and repairs terminal events without replaying interrupted work`() {
        writeHook()
        runHooks(llm = E2eLlmApi().apply { pauseUntilReleased() }) {
            setupOwner()
            val interrupted = UUID.fromString(invoke().jsonBody()["receiptId"].asText())
            eventually("provider call in progress") { llm.requests.singleOrNull() }
            val chatId = UUID.fromString(awaitStatus(interrupted.toString(), "running")["execution"]["chatId"].asText())
            val pending = UUID.fromString(invoke().jsonBody()["receiptId"].asText())
            PostgresDataSourceFactory.create(postgresAppConfig(sql { it.schema }).postgres).use { db ->
                backend.close()
                val restartedLlm = E2eLlmApi()
                var terminalEventId: UUID? = null
                for ((initial, terminal) in listOf("running" to "failed", "cancelling" to "cancelled")) for (attempt in 0..2) {
                    db.write { c ->
                        c.createStatement().use { s ->
                            if (attempt == 0) s.executeUpdate("""
                                update agent_executions set status = '$initial', finished_at = null,
                                  cancel_requested = ${initial == "cancelling"}, error_code = null, error_message = null
                                where id = '$interrupted'
                            """.trimIndent())
                            // Recover the execution, then model crashes before event and receipt writes.
                            if (attempt < 2) s.executeUpdate("""
                                delete from agent_events where execution_id = '$interrupted'
                                and type in ('execution.finished', 'execution.failed', 'execution.cancelled')
                            """.trimIndent())
                            s.executeUpdate("update hook_receipts set status = 'running' where id = '$interrupted'")
                        }
                    }
                    backend.createPeer(restartedLlm).use { peer ->
                        assertEquals(terminal, peer.dependencies.hookService.status(owner, interrupted).status)
                        val event = peer.dependencies.eventService.listByChat(owner, chatId)
                            .filter { it.type.value in setOf("execution.finished", "execution.failed", "execution.cancelled") }.single()
                        assertEquals("execution.$terminal", event.type.value)
                        if (attempt == 2) assertEquals(terminalEventId, event.id)
                        terminalEventId = event.id
                        eventually("pending receipt recovery") {
                            peer.dependencies.hookService.status(owner, pending).takeIf { it.status == "completed" }
                        }
                        peer.awaitExecution(pending)
                        assertEquals(1, restartedLlm.requests.size, "Interrupted work must not be replayed")
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
            featureFlags = BackendFeatureFlags(wsEvents = true, options = runner != null), sandboxFactory = ::localSandbox, block = block)

    private fun localSandbox(settings: SettingsProvider) =
        DefaultRuntimeSandboxFactory(settings, RuntimeSandboxModeResolver { "local" }, workspace, workspace.resolve("state"), workspace)

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

    private suspend fun BackendE2eScope.awaitStatus(id: String, expected: String): JsonNode =
        eventually("hook $id $expected") {
            val response = client.get("/v1/hooks/receipts/$id") { trusted(owner) }
            assertEquals(HttpStatusCode.OK, response.status)
            response.jsonBody().takeIf { it["status"].asText() == expected }
        }
}
