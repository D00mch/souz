package ru.souz.backend.e2e

import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationRuntimeTurnRunner
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.plugins.PluginBinding
import ru.souz.backend.plugins.pluginArtifactConfig
import ru.souz.backend.plugins.pluginFixture
import ru.souz.backend.plugins.pluginJarUpload
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

class BackendPluginExecutionE2eTest {
    @Test
    fun `packaged Gmail runs through the production catalog and restores owner scoped state on another backend`() {
        assertFailsWith<ClassNotFoundException> { Class.forName("ru.souz.plugins.gmail.GmailPlugin") }
        val artifacts = pluginArtifactConfig()
        backendE2eTest(
            schemaPrefix = "e2e_plugin_gmail",
            configureApp = { it.copy(pluginArtifacts = artifacts, pluginAdminToken = ADMIN_TOKEN) },
        ) {
            val gmail = installPlugin("gmail", 0)
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val config = mapOf<String, Any>("config" to "primary")
            llm.requestSkillForPrompt("configure Gmail", "plugin.gmail.configure", config + mapOf(
                "mailboxEmail" to "owner@example.com", "topicName" to "projects/example/topics/mail",
                "channelType" to "orion", "channelId" to chat,
            ), "plugin.gmail.status" to config)
            backend.awaitExecution(submitPluginRequest(owner, chat, "configure Gmail"))
            val outputs = llm.requests.last().messages.filter { it.name == "RunSkillCommand" }
                .map { json.readTree(it.content) }
            assertEquals(listOf("configured", "needs_profile"), outputs.map { it["status"].asText() })

            val stranger = UUID.randomUUID().toString()
            val strangerChat = createPublicChat(stranger)
            llm.requestSkillForPrompt("inspect other mailbox", "plugin.gmail.status", config)
            backend.awaitExecution(submitPluginRequest(stranger, strangerChat, "inspect other mailbox"))
            assertEquals("gmail_connection_missing", json.readTree(llm.requests.last().messages
                .last { it.name == "RunSkillCommand" }.content)["error"]["code"].asText())
            backend.shutdown()

            val resumed = E2eLlmApi().apply { requestSkillForPrompt("restore Gmail", "plugin.gmail.status", config) }
            withPeerBackend(resumed) { peer -> with(peer) {
                backend.pluginRuntime.reconcile()
                awaitPlugin(gmail)
                backend.awaitExecution(submitPluginRequest(owner, chat, "restore Gmail"))
                val status = json.readTree(resumed.requests.last().messages.last { it.name == "RunSkillCommand" }.content)
                assertEquals("needs_profile", status["status"].asText())
                assertEquals(0, status["pending"].size())
            } }
        }
    }

    @Test
    fun `saved waits restore exact retired artifacts on a new backend and legacy waits acquire no plugins`() {
        for (legacy in listOf(false, true)) {
            val artifacts = pluginArtifactConfig()
            val wait = PluginOptionBoundary()
            backendE2eTest(
                schemaPrefix = "e2e_plugin_wait",
                featureFlags = BackendFeatureFlags(wsEvents = true, options = true),
                turnRunnerOverride = wait,
                llm = E2eLlmApi(::reportReply),
                configureApp = { it.copy(pluginArtifacts = artifacts, pluginAdminToken = ADMIN_TOKEN) },
            ) {
                wait.delegate = BackendConversationRuntimeTurnRunner(
                    backend.conversationRuntimeFactory, plugins = backend.pluginRuntime,
                )
                val v1 = installPlugin("kotlin-v1", 0)
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                val executionId = submitPluginRequest(user, chat, "read before waiting")
                val optionId = eventually("saved plugin option") {
                    client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(user) }.jsonBody()["items"]
                        .firstOrNull { it["type"].asText() == "option.requested" }
                }["payload"]["optionId"].asText()
                backend.awaitExecution(executionId)
                val waiting = requireNotNull(backend.executionRepository.get(user, executionId))
                assertEquals(AgentExecutionStatus.WAITING_OPTION, waiting.status)
                assertEquals(listOf(v1), restJsonMapper.readValue(waiting.metadata.getValue("pluginBindings"), Array<PluginBinding>::class.java).toList())
                assertTrue(waiting.metadata.getValue("enabledTools").contains(REPORT))
                assertEquals("reported-v1", wait.output)
                assertTrue(backend.pluginRuntime.snapshot.status.single().versions.all { it.executions == 0 && it.calls == 0 })

                val v2 = installPlugin("kotlin-v2", 1)
                assertEquals(setOf("v1", "v2"), backend.pluginRuntime.snapshot.status.single().versions.map { it.binding.version }.toSet())
                val retired = client.post("/admin/v1/plugins/reference/versions/v1/retire") {
                    header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
                    jsonBody("""{"expectedRevision":2}""")
                }
                assertEquals(HttpStatusCode.Accepted, retired.status)
                backend.pluginRuntime.reconcile()
                eventually("retired v1 unloaded during saved wait") {
                    true.takeIf { backend.pluginRuntime.snapshot.status.single().versions.none { it.binding.version == "v1" } }
                }
                // Changes apply to subsequent requests; a persisted continuation retains its own allowlist.
                assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                    trusted(user); jsonBody("""{"enabledTools":[]}""")
                }.status)
                if (legacy) sql { connection ->
                    connection.prepareStatement("update agent_executions set metadata = metadata - 'pluginBindings' where id = ?").use {
                        it.setObject(1, executionId)
                        assertEquals(1, it.executeUpdate())
                    }
                }
                backend.shutdown()

                withPeerBackend(E2eLlmApi(::reportReply)) { peer -> with(peer) {
                    backend.pluginRuntime.reconcile()
                    awaitPlugin(v2)
                    assertTrue(backend.pluginRuntime.snapshot.status.single().versions.none { it.binding.version == "v1" })
                    val answered = client.post(BackendHttpRoutes.optionAnswer(optionId)) {
                        trusted(user); jsonBody("""{"selectedOptionIds":["continue"]}""")
                    }
                    assertEquals(HttpStatusCode.OK, answered.status)
                    backend.awaitExecution(executionId)
                    val expected = if (legacy) "reported-skill_unavailable" else "reported-v1"
                    assertEquals(expected, lastAssistant(user, chat))
                    assertEquals(AgentExecutionStatus.COMPLETED, backend.executionRepository.get(user, executionId)?.status)
                    assertTrue(backend.pluginRuntime.snapshot.status.single().versions.none { it.binding.version == "v1" })

                    val denied = submitPluginRequest(user, chat, "changed allowlist")
                    backend.awaitExecution(denied)
                    assertEquals("reported-skill_unavailable", lastAssistant(user, chat))
                    assertEquals("[]", backend.executionRepository.get(user, denied)?.metadata?.get("pluginBindings"))
                    client.patch(BackendHttpRoutes.SETTINGS) {
                        trusted(user); jsonBody("""{"enabledTools":["$REPORT"]}""")
                    }.also { assertEquals(HttpStatusCode.OK, it.status) }
                    val fresh = submitPluginRequest(user, chat, "new logical request")
                    backend.awaitExecution(fresh)
                    assertEquals("reported-v2", lastAssistant(user, chat))
                    assertEquals(listOf(v2), restJsonMapper.readValue(
                        requireNotNull(backend.executionRepository.get(user, fresh)).metadata.getValue("pluginBindings"),
                        Array<PluginBinding>::class.java,
                    ).toList())
                } }
            }
        }
    }

    @Test
    fun `children retain the admitted plugin version and only selected handlers after a rollout`() {
        val artifacts = pluginArtifactConfig()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val childResults = mutableListOf<JsonNode>()
        backendE2eTest(
            schemaPrefix = "e2e_plugin_child",
            configureApp = { it.copy(pluginArtifacts = artifacts, pluginAdminToken = ADMIN_TOKEN) },
            llm = E2eLlmApi { request ->
                if (request.functions.none { it.name == "SpawnSubagent" }) {
                    assertEquals(listOf(REPORT), request.functions.map { it.name })
                    val result = request.messages.lastOrNull { it.name == REPORT }
                    if (result == null) {
                        assertFalse(request.messages.first().content.contains("plugin.reference.state"))
                        assertFalse(request.messages.first().content.contains("Client Skills are provided by channels"))
                        entered.complete(Unit)
                        release.await()
                        toolCallReply(request, REPORT, emptyMap())
                    } else {
                        childResults.add(restJsonMapper.readTree(result.content))
                        reply(request, "child checked exact version and restricted catalog")
                    }
                } else {
                    val spawned = request.messages.lastOrNull { it.name == "SpawnSubagent" }
                    val waited = request.messages.lastOrNull { it.name == "WaitSubagent" }
                    when {
                        spawned == null -> toolCallReply(request, "SpawnSubagent", mapOf(
                            "task" to "read the selected plugin", "skillIds" to listOf(REPORT),
                        ))
                        waited == null || restJsonMapper.readTree(waited.content)["status"].asText() != "completed" -> {
                            val child = restJsonMapper.readTree(spawned.content)["subagentId"].asText()
                            toolCallReply(request, "WaitSubagent", mapOf("subagentId" to child, "timeoutMs" to 30_000))
                        }
                        else -> reply(request, "parent finished")
                    }
                }
            },
        ) {
            installPlugin("kotlin-v1", 0)
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val execution = submitPluginRequest(user, chat, "delegate the plugin")
            try {
                entered.await()
                installPlugin("kotlin-v2", 1)
                client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody("""{"enabledTools":[]}""") }
            } finally { release.complete(Unit) }
            backend.awaitExecution(execution)
            assertEquals("v1", childResults.single()["version"].asText())
            assertFalse(childResults.single()["hostVisible"].asBoolean())
            assertEquals("parent finished", lastAssistant(user, chat))
        }
    }

    @Test
    fun `defaults gain installed plugins while explicit allowlists and reserved client names remain stable`() {
        val artifacts = pluginArtifactConfig()
        backendE2eTest(
            schemaPrefix = "e2e_plugin_catalog",
            configureApp = { it.copy(pluginArtifacts = artifacts, pluginAdminToken = ADMIN_TOKEN) },
        ) {
            val defaultsOwner = UUID.randomUUID().toString()
            val explicitOwner = UUID.randomUUID().toString()
            val defaultsChat = createPublicChat(defaultsOwner)
            createPublicChat(explicitOwner)
            assertFalse(client.get(BackendHttpRoutes.SETTINGS) { trusted(defaultsOwner) }.jsonBody()["settings"]["enabledTools"]
                .any { it.asText() == REPORT })
            val absent = "plugin.future.report"
            val explicit = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(explicitOwner); jsonBody("""{"enabledTools":["$absent"]}""")
            }
            assertEquals(HttpStatusCode.OK, explicit.status)
            assertEquals(listOf(absent), explicit.jsonBody()["settings"]["enabledTools"].map { it.asText() })
            installPlugin("kotlin-v1", 0)
            assertTrue(client.get(BackendHttpRoutes.SETTINGS) { trusted(defaultsOwner) }.jsonBody()["settings"]["enabledTools"]
                .any { it.asText() == REPORT })
            assertEquals(listOf(absent), client.get(BackendHttpRoutes.SETTINGS) { trusted(explicitOwner) }
                .jsonBody()["settings"]["enabledTools"].map { it.asText() })

            for (name in listOf(REPORT, "plugin.ghost.report")) {
                val declared = client.post(BackendHttpRoutes.CHATS) {
                    jsonBody("""{"userId":"$defaultsOwner","requestId":"reject-$name","clientType":"backend","tools":${clientToolsJson(name)}}""")
                }
                assertEquals(HttpStatusCode.BadRequest, declared.status)
                assertEquals("invalid_request", declared.jsonBody()["error"]["code"].asText())
            }
            // Old deployments could have persisted these declarations. Reading them must not revive them.
            val legacy = "plugin.ghost.report"
            sql { connection ->
                connection.prepareStatement("update chats set client_tools = ?::jsonb, discovered_client_tools = ?::jsonb where id = ?").use {
                    it.setString(1, clientToolsJson(legacy, "ordinary.tool"))
                    it.setString(2, """{"channelId":"$defaultsChat","tools":${clientToolsJson(legacy)}}""")
                    it.setObject(3, UUID.fromString(defaultsChat))
                    assertEquals(1, it.executeUpdate())
                }
            }
            llm.requestSkillForPrompt("legacy declaration", legacy, emptyMap())
            val execution = submitPluginRequest(defaultsOwner, defaultsChat, "legacy declaration")
            backend.awaitExecution(execution)
            val finalRequest = llm.requests.last()
            assertTrue(finalRequest.messages.first().content.contains("ordinary.tool"))
            assertFalse(finalRequest.messages.first().content.contains(legacy))
            val rejected = json.readTree(finalRequest.messages.last { it.name == "RunSkillCommand" }.content)
            assertEquals("skill_unavailable", rejected["error"]["code"].asText())
        }
    }

    private suspend fun BackendE2eScope.installPlugin(fixture: String, revision: Long): PluginBinding {
        val response = client.post("/admin/v1/plugins/install") {
            header(HttpHeaders.Authorization, "Bearer $ADMIN_TOKEN")
            setBody(pluginJarUpload(pluginFixture(fixture), revision))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val accepted = response.jsonBody()
        val binding = PluginBinding(accepted["id"].asText(), accepted["version"].asText(), accepted["digest"].asText())
        backend.pluginRuntime.reconcile()
        awaitPlugin(binding)
        return binding
    }

    private suspend fun BackendE2eScope.awaitPlugin(binding: PluginBinding) = eventually("plugin ${binding.version} applied", 15.seconds) {
        true.takeIf { binding in backend.pluginRuntime.snapshot.defaultBindings }
    }

    private suspend fun BackendE2eScope.submitPluginRequest(user: String, chat: String, prompt: String): UUID {
        val sent = client.post(BackendHttpRoutes.chatMessages(chat)) {
            trusted(user); jsonBody("""{"content":"$prompt"}""")
        }
        assertEquals(HttpStatusCode.OK, sent.status)
        return UUID.fromString(sent.jsonBody()["execution"]["id"].asText())
    }

    private suspend fun BackendE2eScope.lastAssistant(user: String, chat: String): String =
        client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(user) }.jsonBody()["items"]
            .last { it["role"].asText() == "assistant" }["content"].asText()

    private companion object {
        const val ADMIN_TOKEN = "plugin-execution-admin"
        const val REPORT = "plugin.reference.report"

        fun reportReply(request: LLMRequest.Chat): LLMResponse.Chat {
            val turn = request.messages.drop(request.messages.indexOfLast { it.role == LLMMessageRole.user }.coerceAtLeast(0))
            val output = turn.lastOrNull { it.name == "RunSkillCommand" }
            if (output != null) {
                val result = restJsonMapper.readTree(output.content)
                return reply(request, "reported-${result["version"]?.asText() ?: result["error"]["code"].asText()}")
            }
            return scriptedSkillReply(request, listOf(SkillScript(REPORT, emptyMap())))
        }
    }
}

/** There is no option-emitting product tool. Only the wait boundary is synthetic; the graph,
 * plugin invocation, returned session, acceptance, PostgreSQL handoff and resume are real. */
private class PluginOptionBoundary : BackendConversationTurnRunner {
    lateinit var delegate: BackendConversationTurnRunner
    var output: String? = null
    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        val result = delegate.run(conversationKey, request, eventSink, initialUsage) as BackendConversationTurnOutcome.Completed
        output = result.output
        eventSink.emit(AgentRuntimeEvent.ChoiceRequested(
            choiceId = UUID.randomUUID().toString(), kind = "generic_selection", title = "Continue the pinned request",
            selectionMode = "single",
            options = listOf(AgentRuntimeEvent.ChoiceRequested.ChoiceOption("continue", "Continue", "continue")),
        ))
        return BackendConversationTurnOutcome.WaitingOption(result.usage, result.session, result.routedModel)
    }
}
