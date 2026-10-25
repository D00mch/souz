package ru.souz.backend.e2e

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.plugins.PLUGIN_BINDINGS_METADATA
import ru.souz.backend.plugins.decodePluginBindings
import ru.souz.plugins.testing.pluginJar

class BackendPluginsE2eTest {
    @TempDir lateinit var directory: Path

    @Test fun `client discovery cannot replace a pinned plugin tool`() {
        pluginJar(directory.resolve("fixture.jar"))
        backendE2eTest("e2e_plugin_discovery", pluginDirectory = directory) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            llm.requestSkillForPrompt("discover", "orion.tools.list", emptyMap(), "fixture__version" to emptyMap())
            withMultiChatSocket { socket ->
                socket.send(Frame.Text(messageFrame(chat, owner, "discover", text = "discover")))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.status", readJson(socket)["type"].asText())
                val discovery = readJson(socket)
                assertEquals("orion.tools.list", discovery["payload"]["name"].asText())
                socket.send(Frame.Text(toolResultFrame(discovery, "succeeded", mapOf("tools" to listOf(mapOf(
                    "name" to "fixture__version", "description" to "Client replacement",
                    "parameters" to mapOf("type" to "object"),
                ))))))
                assertEquals("accepted", readJson(socket)["status"].asText())
                assertEquals("thread.completed", readJson(socket)["type"].asText())
                assertTrue(llm.requests.last().messages.any {
                    it.name == "RunSkillCommand" && it.content.contains("invalid_client_tool_catalogue")
                })
                assertEquals("1", lastSkillResult()["version"].asText())
            }
        }
    }

    @Test fun `existing users discover installed plugins and data survives opt-outs uninstall and restart`() =
        backendE2eTest("e2e_plugins_data", pluginDirectory = directory,
            pluginHttpClient = { HttpClient(MockEngine { respond("<title>Example &amp; title</title>") }) }) {
            val owner = UUID.randomUUID().toString()
            val foreign = UUID.randomUUID().toString()
            // Provision before installation and preserve an explicitly empty compiled-tool list.
            assertEquals(HttpStatusCode.OK, client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(owner); jsonBody("""{"enabledTools":[]}""")
            }.status)
            val artifact = installSavedLinks(directory.resolve("links.jar"))
            installSavedLinks(directory.resolve("other.jar"), "other_links")
            backend.reconcilePlugins()
            val bootstrap = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(owner) }.jsonBody()
            assertEquals(setOf("saved_links", "other_links"), bootstrap["capabilities"]["plugins"].map { it["id"].asText() }.toSet())
            assertTrue(bootstrap["capabilities"]["plugins"].all { it["enabled"].asBoolean() })
            assertEquals(0, bootstrap["settings"]["enabledTools"].size())

            val saved = callSkill(owner, "saved_links__save", mapOf("url" to "https://example.com"))
            assertEquals("Example & title", saved["title"].asText(), saved.toString())
            assertEquals("fetched", saved["fetchStatus"].asText())
            val key = saved["key"].asText()
            assertEquals(0, callSkill(foreign, "saved_links__list")["links"].size())
            assertFalse(callSkill(foreign, "saved_links__delete", mapOf("key" to key))["deleted"].asBoolean())
            assertEquals(0, callSkill(owner, "other_links__list")["links"].size())
            assertEquals(1, callSkill(owner, "saved_links__list")["links"].size())

            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody("""{"disabledPlugins":["saved_links","absent"]}""") }
            assertTrue(callSkill(owner, "saved_links__list").has("error"))
            assertFalse(client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(owner) }.jsonBody()["capabilities"]["plugins"]
                .single { it["id"].asText() == "saved_links" }["enabled"].asBoolean())
            Files.delete(artifact)
            backend.reconcilePlugins()
            installSavedLinks(artifact)
            backend.reconcilePlugins()
            assertTrue(callSkill(owner, "saved_links__list").has("error"))
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody("""{"disabledPlugins":[]}""") }

            // Close the first runtime, then reconstruct production DI over its durable schema/cache.
            backend.shutdown()
            withPeerBackend { peer ->
                with(peer) {
                    assertEquals(1, callSkill(owner, "saved_links__list")["links"].size())
                    assertTrue(callSkill(owner, "saved_links__delete", mapOf("key" to key))["deleted"].asBoolean())
                    assertEquals(0, callSkill(owner, "saved_links__list")["links"].size())
                }
            }
        }

    @Test fun `accepted execution keeps old generation while new executions use replacement`() {
        val artifact = pluginJar(directory.resolve("fixture.jar"))
        val llm = E2eLlmApi().apply {
            requestSkill("fixture__version", emptyMap())
            pausePromptUntilReleased("paused generation")
        }
        backendE2eTest("e2e_plugins_reload", pluginDirectory = directory, llm = llm) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val sent = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(user); jsonBody("""{"content":"paused generation","options":{"model":"${E2E_MODEL.alias}"}}""") }.jsonBody()
            eventually("paused LLM generation") {
                val stored = backend.executionRepository.getByChat(user, UUID.fromString(chat), UUID.fromString(sent["execution"]["id"].asText()))!!
                check(stored.status.name != "FAILED") { "${stored.errorCode}: ${stored.errorMessage}" }
                llm.requests.firstOrNull()
            }
            pluginJar(artifact, version = "2")
            backend.reconcilePlugins()
            assertEquals("2", client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(user) }.jsonBody()["capabilities"]["plugins"].single()["version"].asText())
            llm.releasePrompt("paused generation")
            backend.awaitExecution(UUID.fromString(sent["execution"]["id"].asText()))
            assertEquals("1", lastSkillResult()["version"].asText())
            assertEquals("2", callSkill(user, "fixture__version")["version"].asText())
            pluginJar(artifact, version = "bad", api = "99")
            backend.reconcilePlugins()
            assertEquals("2", callSkill(user, "fixture__version")["version"].asText())
        }
    }

    @Test fun `subagents receive only selected enabled plugin tools`() {
        pluginJar(directory.resolve("fixture.jar"))
        val llm = E2eLlmApi { request ->
            val last = request.messages.lastOrNull { it.role == ru.souz.llms.LLMMessageRole.function }
            when {
                request.functions.any { it.name == "fixture__version" } -> {
                    assertEquals(listOf("fixture__version"), request.functions.map { it.name })
                    if (last == null) toolCallReply(request, "fixture__version", emptyMap()) else reply(request, last.content)
                }
                last?.name == "SpawnSubagent" -> reply(request, last.content)
                else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Report plugin version", "skillIds" to listOf("fixture__version")))
            }
        }
        backendE2eTest("e2e_plugins_child", pluginDirectory = directory, llm = llm) {
            val user = UUID.randomUUID().toString()
            val chat = createPublicChat(user)
            val sent = client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(user); jsonBody("""{"content":"delegate","options":{"model":"${E2E_MODEL.alias}"}}""") }.jsonBody()
            backend.awaitExecution(UUID.fromString(sent["execution"]["id"].asText()))
            assertTrue(llm.requests.any { it.functions.map { it.name } == listOf("fixture__version") })
            val result = llm.requests.flatMap { it.messages }.last { it.name == "SpawnSubagent" }
            assertTrue(result.content.contains("version"), result.content)
        }
    }

    @Test fun `waiting executions resume exact artifacts after restart and legacy waits have no plugins`() {
        for (mode in listOf("cached", "missing", "legacy")) {
            val artifact = pluginJar(directory.resolve("fixture.jar"))
            backendE2eTest("e2e_plugins_resume", pluginDirectory = directory,
                featureFlags = BackendFeatureFlags(wsEvents = true, options = true)) {
                val user = UUID.randomUUID().toString()
                val chat = createPublicChat(user)
                val sent = client.post(BackendHttpRoutes.chatMessages(chat)) {
                    trusted(user); jsonBody("""{"content":"prepare option","options":{"model":"${E2E_MODEL.alias}"}}""")
                }.jsonBody()
                val executionId = UUID.fromString(sent["execution"]["id"].asText())
                backend.awaitExecution(executionId)
                val accepted = backend.executionRepository.getByChat(user, UUID.fromString(chat), executionId)!!
                val binding = decodePluginBindings(accepted.metadata[PLUGIN_BINDINGS_METADATA]).single()
                assertEquals("1", binding.version)
                // Seed the durable wait boundary; continuation uses production HTTP, repositories and runner.
                val optionId = seedWaitingOption(user, chat, executionId)
                client.patch(BackendHttpRoutes.SETTINGS) { trusted(user); jsonBody("""{"disabledPlugins":["fixture"]}""") }
                if (mode == "legacy") sql { connection ->
                    connection.prepareStatement("update agent_executions set metadata = metadata - 'pluginBindings' where id = ?").use {
                        it.setObject(1, executionId); assertEquals(1, it.executeUpdate())
                    }
                }
                backend.shutdown()
                pluginJar(artifact, version = "2")
                if (mode == "missing") Files.delete(directory.resolve(".cache/${binding.digest}.jar"))
                withPeerBackend(E2eLlmApi().apply { requestSkill("fixture__version", emptyMap()) }) { peer ->
                    with(peer) {
                        assertEquals("2", client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(user) }
                            .jsonBody()["capabilities"]["plugins"].single()["version"].asText())
                        val answer = client.post(BackendHttpRoutes.optionAnswer(optionId)) {
                            trusted(user); jsonBody("""{"selectedOptionIds":["a"]}""")
                        }
                        assertEquals(HttpStatusCode.OK, answer.status)
                        backend.awaitExecution(executionId)
                        val resumed = backend.executionRepository.getByChat(user, UUID.fromString(chat), executionId)!!
                        if (mode == "missing") {
                            assertEquals("FAILED", resumed.status.name)
                            assertEquals("plugin_artifact_missing", resumed.errorCode)
                            assertTrue(llm.requests.isEmpty())
                        } else {
                            assertEquals("COMPLETED", resumed.status.name, resumed.errorMessage)
                            if (mode == "legacy") assertTrue(lastSkillResult().has("error"))
                            else assertEquals("1", lastSkillResult()["version"].asText())
                        }
                    }
                }
            }
        }
    }

    private fun BackendE2eScope.seedWaitingOption(user: String, chat: String, executionId: UUID): UUID =
        UUID.randomUUID().also { optionId -> sql { connection ->
            connection.prepareStatement("update agent_executions set status = 'waiting_option', finished_at = null, assistant_message_id = null where id = ?").use {
                it.setObject(1, executionId); assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("delete from agent_events where execution_id = ? and type = 'execution.finished'").use {
                it.setObject(1, executionId); assertEquals(1, it.executeUpdate())
            }
            connection.prepareStatement("""
                insert into options(id, user_id, chat_id, execution_id, kind, selection_mode, options_json, status)
                values (?, ?, ?, ?, 'generic_selection', 'single', '[{"id":"a","label":"Alpha","content":"alpha"}]', 'pending')
            """.trimIndent()).use {
                it.setObject(1, optionId); it.setString(2, user); it.setObject(3, UUID.fromString(chat)); it.setObject(4, executionId)
                assertEquals(1, it.executeUpdate())
            }
        } }
}

private fun installSavedLinks(path: Path, id: String = "saved_links"): Path {
    JarFile(System.getProperty("souz.test.savedLinksJar")).use { original ->
        val manifest = original.manifest.apply { mainAttributes.putValue("Souz-Plugin-Id", id) }
        JarOutputStream(Files.newOutputStream(path), manifest).use { output ->
            original.entries().asSequence().filter { !it.name.equals("META-INF/MANIFEST.MF", ignoreCase = true) }.forEach { entry ->
                output.putNextEntry(java.util.jar.JarEntry(entry.name))
                original.getInputStream(entry).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }
    return path
}
