package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.backend.plugins.PluginOperator
import ru.souz.llms.restJsonMapper

class BackendPluginSubagentE2eTest {
    @Test
    fun `child obeys parent selection and retains root version during update`() {
        val entered = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var childCalls = 0
        val name = "plugin.reference.state"
        backendE2eTest("e2e_plugin_child", startBackgroundServices = true, llm = E2eLlmApi { request ->
            when {
                request.functions.singleOrNull()?.name == name -> {
                    childCalls++
                    entered.complete(Unit)
                    proceed.await()
                    val result = request.messages.lastOrNull { it.name == name }
                    if (result != null) reply(request, result.content)
                    else toolCallReply(request, name, mapOf("key" to "child", "json" to "true", "userId" to "someone-else"))
                }
                request.messages.any { it.name == "SpawnSubagent" } -> reply(request, "parent completed")
                else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Store the child result", "skillIds" to listOf(name)))
            }
        }) {
            val owner = UUID.randomUUID().toString()
            val operator = PluginOperator(backend.pluginRepository)
            operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v1"), "0"))
            eventually("initial plugin") { true.takeIf { backend.pluginRuntime.snapshot.status.singleOrNull()?.appliedDigest != null } }

            suspend fun start(): String {
                val chat = createPublicChat(owner, requestId = UUID.randomUUID().toString())
                client.post(BackendHttpRoutes.chatMessages(chat)) { trusted(owner); jsonBody("""{"content":"Delegate"}""") }
                return chat
            }
            suspend fun finish(chat: String) = eventually("parent completion") {
                client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody()["items"]
                    .takeIf { it.last()["content"].asText() == "parent completed" }
            }
            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody("""{"enabledTools":[]}""") }
            finish(start())
            assertEquals(0, childCalls)
            val denied = llm.requests.last().messages.single { it.name == "SpawnSubagent" }
            assertEquals("skill_unavailable", json.readTree(denied.content)["error"]["code"].asText())

            client.patch(BackendHttpRoutes.SETTINGS) { trusted(owner); jsonBody("""{"enabledTools":["$name"]}""") }
            try {
                val chat = start()
                withTimeout(10_000) { entered.await() }
                operator.execute(listOf("install", System.getProperty("plugin.fixture.kotlin-v2"), "1"))
                val digest = backend.pluginRepository.desired().single().digest
                eventually("updated plugin with a pinned child") {
                    backend.pluginRuntime.snapshot.status.single().takeIf { it.appliedDigest == digest && it.liveVersions == 2 }
                }
                proceed.complete(Unit)
                finish(chat)
                val result = json.readTree(llm.requests.last().messages.single { it.name == "SpawnSubagent" }.content)
                val state = restJsonMapper.readTree(result["result"].asText())
                assertEquals(owner, state["owner"].asText())
                assertEquals("v1", state["version"].asText())
                assertEquals("true", state["json"].asText())
                eventually("retired parent version") { true.takeIf { backend.pluginRuntime.snapshot.status.single().liveVersions == 1 } }
                assertTrue(childCalls >= 2)
            } finally { proceed.complete(Unit) }
        }
    }
}
