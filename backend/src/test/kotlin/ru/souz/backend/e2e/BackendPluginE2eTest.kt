package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.plugins.PluginOperator
import ru.souz.backend.plugins.PluginControl
import kotlinx.coroutines.withTimeout

class BackendPluginE2eTest {
    @Test
    fun `operator hot install reaches bootstrap and ordinary Skill execution without rebuilding services`(): Unit =
        backendE2eTest("e2e_plugins", startBackgroundServices = true) {
            val owner = UUID.randomUUID().toString()
            val chat = createPublicChat(owner)
            val explicit = UUID.randomUUID().toString()
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(explicit); jsonBody("""{"enabledTools":[]}""") }
            suspend fun names(user: String) = client.get(BackendHttpRoutes.BOOTSTRAP) { trusted(user) }
                .jsonBody()["capabilities"]["tools"].map { it["name"].asText() }.toSet()
            assertFalse(names(owner).any { it.startsWith("plugin.") })
            val operator = PluginOperator(backend.pluginRepository)
            operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v1"), "0"))
            eventually("plugin bootstrap") { true.takeIf { "plugin.reference.report" in names(owner) } }
            val selected = client.get(BackendHttpRoutes.SETTINGS) { trusted(explicit) }.jsonBody()["settings"]["enabledTools"]
            assertEquals(0, selected.size())
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(explicit)
                jsonBody("""{"enabledTools":["plugin.reference.report"]}""")
            }
            val explicitChat = createPublicChat(explicit)
            val defaults = client.get(BackendHttpRoutes.SETTINGS) { trusted(owner) }.jsonBody()["settings"]["enabledTools"]
            assertTrue(defaults.any { it.asText() == "plugin.reference.report" })

            suspend fun run(prompt: String, skill: String, arguments: Map<String, Any>, user: String = owner, target: String = chat): String {
                return runSkill(prompt, skill, arguments, user, target)
            }
            assertTrue(run("plugin-v1", "plugin.reference.report", mapOf("text" to "a,b\nc,d")).startsWith("v1:2:"))
            val state = json.readTree(run("plugin-state", "plugin.reference.state",
                mapOf("key" to "shared", "json" to "{\"saved\":true}", "userId" to explicit)))
            assertEquals(owner, state["owner"].asText())
            assertEquals("skill_unavailable", json.readTree(run("restricted", "plugin.reference.state",
                mapOf("key" to "shared"), explicit, explicitChat))["error"]["code"].asText())

            operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v2"), "1"))
            eventually("v2 applied") { true.takeIf { "plugin.reference.report_v2" in names(owner) } }
            assertTrue(run("compatible-v2", "plugin.reference.report", mapOf("text" to "a,b")).startsWith("v2:1:"))
            assertEquals("plugin_invalid_arguments",
                json.readTree(run("invalid-v2", "plugin.reference.report_v2", mapOf("text" to "old")))["error"]["code"].asText())
            operator.execute(listOf("disable", "reference", "2"))
            eventually("disabled plugin") { true.takeIf { names(owner).none { it.startsWith("plugin.reference.") } } }
            val preference = client.get(BackendHttpRoutes.SETTINGS) { trusted(explicit) }.jsonBody()["settings"]["enabledTools"]
            assertEquals(listOf("plugin.reference.report"), preference.map { it.asText() })
            assertEquals("skill_unavailable", json.readTree(run("disabled", "plugin.reference.report", mapOf("text" to "a")))["error"]["code"].asText())
            operator.execute(listOf("select", "reference", "v2", "3"))
            eventually("reenabled plugin") { true.takeIf { "plugin.reference.report" in names(owner) } }
            assertTrue(json.readTree(run("persistent-state", "plugin.reference.state", mapOf("key" to "shared")))["json"].asText().contains("saved"))
            assertTrue(run("explicit-reenabled", "plugin.reference.report", mapOf("text" to "a"), explicit, explicitChat).startsWith("v2:1:"))
            PluginControl().use { control ->
                operator.execute(listOf("install", System.getProperty("plugin.fixture.java"), "0"))
                eventually("Java plugin") { true.takeIf { "plugin.java_reference.fetch" in names(owner) } }
                assertEquals("ok", run("java-http", "plugin.java_reference.fetch", mapOf("url" to "${control.url}/fetch")))
            }

            val denied = client.post("/v1/plugins/install") {
                trusted(owner)
                jsonBody("""{"artifact":"arbitrary-user-code"}""")
            }
            assertEquals(HttpStatusCode.NotFound, denied.status)
            assertEquals(4, backend.pluginRepository.desired().single { it.id == "reference" }.revision)
        }
}

internal suspend fun BackendE2eScope.runSkill(
    prompt: String, skill: String, arguments: Map<String, Any>, owner: String, chat: String,
): String {
    llm.requestSkillForPrompt(prompt, skill, arguments)
    withPublicSocket(chat) { socket ->
        socket.send(Frame.Text(messageFrame(chat, owner, prompt, text = prompt)))
        withTimeout(30_000) {
            var thread: String? = null
            while (true) {
                val frame = readJson(socket)
                if (frame.path("requestId").asText() == prompt && frame.path("status").asText() == "accepted") {
                    thread = frame.path("thread").path("id").asText()
                }
                if (thread != null && frame.path("threadId").asText() == thread &&
                    frame.path("type").asText() == "thread.completed") break
            }
        }
    }
    return llm.requests.last().messages.last { it.name == "RunSkillCommand" }.content
}
