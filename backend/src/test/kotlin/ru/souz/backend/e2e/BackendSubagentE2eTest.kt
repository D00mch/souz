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
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMMessageRole

class BackendSubagentE2eTest {
    @Test
    fun `child client tool keeps routing while only parent final reaches public history`() =
        backendE2eTest(
            schemaPrefix = "e2e_subagent_client",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true, toolEvents = true),
            llm = E2eLlmApi { request ->
                val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                when {
                    request.functions.map { it.name } == listOf("user.ask") ->
                        if (result == null) toolCallReply(request, "user.ask", mapOf("question" to "Which genre?"))
                        else reply(request, "private child answer: Horror")

                    result?.name == "SpawnSubagent" -> reply(request, "parent final answer")
                    else -> toolCallReply(request, "SpawnSubagent", mapOf(
                        "task" to "Ask the user for a genre and report it.",
                        "skillIds" to listOf("user.ask"),
                    ))
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"streamingMessages":true}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)

            withPublicSocket(chatId) { session ->
                session.send(Frame.Text(messageFrame(chatId, userId, "delegate", text = "delegate the question", deviceId = "child-device")))
                val ack = readJson(session)
                assertEquals("accepted", ack["status"].asText())
                assertEquals("thread.status", readJson(session)["type"].asText())
                val started = readJson(session)
                val threadId = ack["thread"]["id"].asText()
                val payload = started["payload"]
                assertEquals("tool.call.started", started["type"].asText())
                assertEquals(chatId, started["chatId"].asText())
                assertEquals(threadId, started["threadId"].asText())
                assertEquals("user.ask", payload["name"].asText())
                assertEquals("child-device", payload["deviceId"].asText())
                assertEquals("Which genre?", payload["arguments"]["question"].asText())

                session.send(Frame.Text(
                    """{"kind":"tool.result","chatId":"$chatId","threadId":"$threadId","toolCallId":${payload["toolCallId"]},"status":"succeeded","result":{"answer":"Horror"}}"""
                ))
                assertEquals("accepted", readJson(session)["status"].asText())
                val terminal = readJson(session)
                assertEquals("thread.completed", terminal["type"].asText())
                assertEquals(threadId, terminal["threadId"].asText())
            }

            assertEquals(4, llm.requests.size)
            val childRequests = llm.requests.filter { it.functions.map { tool -> tool.name } == listOf("user.ask") }
            assertEquals(2, childRequests.size)
            assertEquals(2, childRequests.first().messages.size)
            assertFalse(childRequests.first().messages.any { "delegate the question" in it.content || "<skill_inventory>" in it.content })
            val parentResult = llm.requests.last().messages.single { it.name == "SpawnSubagent" }
            assertEquals("private child answer: Horror", json.readTree(parentResult.content)["result"].asText())
            assertTrue("private child answer: Horror" in llm.streamedChunks)

            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("parent final answer", messages.last()["content"].asText())
            val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("tool.call.started", "thread.completed"), events.map { it["type"].asText() })
            assertEquals("parent final answer", events.last()["payload"]["response"].asText())
        }

    @Test
    fun `HTTP execution accounts for child usage and stores only the parent response`() =
        backendE2eTest(
            schemaPrefix = "e2e_subagent_usage",
            featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true, toolEvents = true),
            llm = E2eLlmApi { request ->
                when {
                    request.functions.isEmpty() -> reply(request, "private child text")
                    request.messages.any { it.name == "SpawnSubagent" } -> reply(request, "parent synthesized answer")
                    else -> toolCallReply(request, "SpawnSubagent", mapOf("task" to "Produce a private child answer."))
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":true}""")
            }
            val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"Delegate and synthesize"}""")
            }
            assertEquals(HttpStatusCode.OK, sent.status)
            val events = eventually("parent completion with child usage") {
                client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"].takeIf { items ->
                    items.any { it["type"].asText() == "execution.finished" }
                }
            }
            assertEquals(3, llm.requests.size)
            assertTrue("private child text" in llm.streamedChunks)
            val finished = events.single { it["type"].asText() == "execution.finished" }["payload"]
            assertEquals(21, finished["promptTokens"].asInt())
            assertEquals(9, finished["completionTokens"].asInt())
            assertEquals(30, finished["totalTokens"].asInt())
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals(listOf("user", "assistant"), messages.map { it["role"].asText() })
            assertEquals("parent synthesized answer", messages.last()["content"].asText())
            val completed = events.single { it["type"].asText() == "message.completed" }
            assertEquals("parent synthesized answer", completed["payload"]["content"].asText())
        }

    @Test
    fun `child selection obeys client search exclusion and returns failure to parent`() =
        backendE2eTest("e2e_subagent_policy", llm = E2eLlmApi { request ->
            if (request.messages.any { it.name == "SpawnSubagent" }) reply(request, "parent recovered")
            else toolCallReply(request, "SpawnSubagent", mapOf(
                "task" to "Search the internet.",
                "skillIds" to listOf("InternetSearch"),
            ))
        }) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            val settings = client.patch(BackendHttpRoutes.SETTINGS) {
                trusted(userId)
                jsonBody("""{"enabledTools":["InternetSearch"]}""")
            }
            assertEquals(HttpStatusCode.OK, settings.status)
            withPublicSocket(chatId) { session ->
                session.send(Frame.Text(messageFrame(chatId, userId, "delegate-search")))
                assertEquals("accepted", readJson(session)["status"].asText())
                assertEquals("thread.status", readJson(session)["type"].asText())
                assertEquals("thread.completed", readJson(session)["type"].asText())
            }

            assertEquals(2, llm.requests.size)
            val inventory = llm.requests.first().messages.first().content
            assertTrue("web.search" in inventory)
            assertFalse("InternetSearch" in inventory)
            val toolResult = llm.requests.last().messages.single { it.name == "SpawnSubagent" }
            assertEquals("skill_not_found", json.readTree(toolResult.content)["error"]["code"].asText())
            val messages = client.get(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId) }.jsonBody()["items"]
            assertEquals("parent recovered", messages.last()["content"].asText())
        }
}
