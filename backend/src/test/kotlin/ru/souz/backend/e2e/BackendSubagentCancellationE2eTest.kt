package ru.souz.backend.e2e

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.restJsonMapper

class BackendSubagentCancellationE2eTest {
    @Test
    fun `cancelling a public child releases its client tool waiter for the parent`() = backendE2eTest(
        schemaPrefix = "e2e_cancel_child_tool",
        featureFlags = BackendFeatureFlags(wsEvents = true),
        llm = E2eLlmApi { request ->
            val lastTool = request.messages.lastOrNull { it.name != null }
            when {
                request.functions.map { it.name } == listOf("RunSkillCommand") ->
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "user.ask", "arguments" to mapOf("question" to "Child question?")))
                lastTool?.name == "SpawnSubagent" -> {
                    assertEquals("subagent_cancelled", restJsonMapper.readTree(lastTool.content)["error"]["code"].asText())
                    toolCallReply(request, "RunSkillCommand", mapOf("skillId" to "user.ask", "arguments" to mapOf("question" to "Parent question?")))
                }
                lastTool?.name == "RunSkillCommand" -> reply(request, "parent continued after client tool")
                else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Ask a question", "skillIds" to listOf("user.ask")))
            }
        },
    ) {
        val owner = UUID.randomUUID().toString()
        val chat = createPublicChat(owner)
        withPublicSocket(chat) { session ->
            session.send(Frame.Text(messageFrame(chat, owner, "delegate", text = "delegate a question")))
            val ack = readJson(session)
            assertEquals("accepted", ack["status"].asText())
            val execution = ack["thread"]["id"].asText()
            assertEquals("thread.status", readJson(session)["type"].asText())
            val childCall = readJson(session)
            assertEquals("tool.call.started", childCall["type"].asText())
            assertEquals("Child question?", childCall["payload"]["arguments"]["question"].asText())
            val listUrl = BackendHttpRoutes.subagents(chat, execution)
            val child = client.get(listUrl) { trusted(owner) }.jsonBody()["subagentIds"].single().asText()
            assertTrue(client.post(BackendHttpRoutes.cancelSubagent(chat, execution, child)) {
                trusted(owner)
            }.jsonBody()["cancellationRequested"].asBoolean())

            val parentCall = readJson(session)
            assertEquals("tool.call.started", parentCall["type"].asText())
            assertEquals("Parent question?", parentCall["payload"]["arguments"]["question"].asText())
            assertEquals(0, client.get(listUrl) { trusted(owner) }.jsonBody()["subagentIds"].size())
            session.send(Frame.Text(toolResultFrame(parentCall, "succeeded", mapOf("answer" to "Continue"))))
            assertEquals("accepted", readJson(session)["status"].asText())
            assertEquals("thread.completed", readJson(session)["type"].asText())
        }
        val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody()["items"]
        assertEquals("parent continued after client tool", messages.last()["content"].asText())
    }

    @Test
    fun `owned child cancellation resumes parent while parent cancellation stops both`() {
        for (cancelParent in listOf(false, true)) {
            val childStarted = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            backendE2eTest(
                schemaPrefix = "e2e_cancel_subagent",
                featureFlags = BackendFeatureFlags(wsEvents = true),
                llm = E2eLlmApi { request ->
                    when {
                        request.functions.isEmpty() -> {
                            childStarted.complete(Unit)
                            try { awaitCancellation() } finally {
                                withContext(NonCancellable) {
                                    cleanupStarted.complete(Unit)
                                    releaseCleanup.await()
                                }
                            }
                        }
                        request.messages.any { it.name == "SpawnSubagent" } -> reply(request, "parent continued")
                        else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "wait in child"))
                    }
                },
            ) {
                try {
                    val owner = UUID.randomUUID().toString()
                    val chat = createPublicChat(owner)
                    val otherChat = createPublicChat(owner, requestId = "other-chat")
                    val sent = client.post(BackendHttpRoutes.chatMessages(chat)) {
                        trusted(owner)
                        jsonBody("""{"content":"delegate","options":{"model":"${E2E_MODEL.alias}"}}""")
                    }
                    assertEquals(HttpStatusCode.OK, sent.status)
                    val execution = sent.jsonBody()["execution"]["id"].asText()
                    childStarted.await()
                    val listUrl = BackendHttpRoutes.subagents(chat, execution)
                    val listed = client.get(listUrl) { trusted(owner) }
                    assertEquals(HttpStatusCode.OK, listed.status)
                    val child = listed.jsonBody()["subagentIds"].single().asText()
                    UUID.fromString(child)
                    val cancelUrl = BackendHttpRoutes.cancelSubagent(chat, execution, child)

                    assertEquals(HttpStatusCode.Unauthorized, client.get(listUrl).status)
                    assertEquals(HttpStatusCode.Unauthorized, client.post(cancelUrl).status)
                    val foreign = UUID.randomUUID().toString()
                    assertEquals(HttpStatusCode.NotFound, client.get(listUrl) { trusted(foreign) }.status)
                    assertEquals(HttpStatusCode.NotFound, client.post(cancelUrl) { trusted(foreign) }.status)
                    for ((wrongChat, wrongExecution) in listOf(otherChat to execution, chat to UUID.randomUUID().toString())) {
                        assertEquals(HttpStatusCode.NotFound, client.get(BackendHttpRoutes.subagents(wrongChat, wrongExecution)) {
                            trusted(owner)
                        }.status)
                        assertEquals(HttpStatusCode.NotFound, client.post(BackendHttpRoutes.cancelSubagent(wrongChat, wrongExecution, child)) {
                            trusted(owner)
                        }.status)
                    }
                    assertEquals(HttpStatusCode.BadRequest, client.post(BackendHttpRoutes.cancelSubagent(chat, execution, "invalid")) {
                        trusted(owner)
                    }.status)
                    assertFalse(client.post(BackendHttpRoutes.cancelSubagent(chat, execution, UUID.randomUUID())) {
                        trusted(owner)
                    }.jsonBody()["cancellationRequested"].asBoolean())

                    if (cancelParent) {
                        assertEquals(HttpStatusCode.OK, client.post(BackendHttpRoutes.cancelExecution(chat, execution)) {
                            trusted(owner)
                        }.status)
                    } else {
                        val cancelled = client.post(cancelUrl) { trusted(owner) }
                        assertEquals(HttpStatusCode.OK, cancelled.status)
                        assertEquals(child, cancelled.jsonBody()["subagentId"].asText())
                        assertTrue(cancelled.jsonBody()["cancellationRequested"].asBoolean())
                    }
                    cleanupStarted.await()
                    assertEquals(listOf(child), client.get(listUrl) { trusted(owner) }.jsonBody()["subagentIds"].map { it.asText() })
                    assertTrue(client.post(cancelUrl) { trusted(owner) }.jsonBody()["cancellationRequested"].asBoolean())
                    releaseCleanup.complete(Unit)

                    val terminal = eventually("parent terminal event") {
                        client.get(BackendHttpRoutes.chatEvents(chat)) { trusted(owner) }.jsonBody()["items"]
                            .firstOrNull { it["type"].asText() == if (cancelParent) "execution.cancelled" else "execution.finished" }
                    }
                    assertEquals(execution, terminal["executionId"].asText())
                    assertEquals(0, client.get(listUrl) { trusted(owner) }.jsonBody()["subagentIds"].size())
                    assertFalse(client.post(cancelUrl) { trusted(owner) }.jsonBody()["cancellationRequested"].asBoolean())
                    val messages = client.get(BackendHttpRoutes.chatMessages(chat)) { trusted(owner) }.jsonBody()["items"]
                    assertEquals(if (cancelParent) listOf("delegate") else listOf("delegate", "parent continued"), messages.map { it["content"].asText() })
                    assertEquals(if (cancelParent) 2 else 3, llm.requests.size)
                    if (!cancelParent) {
                        val result = json.readTree(llm.requests.last().messages.single { it.name == "SpawnSubagent" }.content)
                        assertEquals("subagent_cancelled", result["error"]["code"].asText())
                        assertEquals(child, result["subagentId"].asText())
                    }
                } finally {
                    releaseCleanup.complete(Unit)
                }
            }
        }
    }
}
