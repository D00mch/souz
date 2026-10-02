package ru.souz.backend.e2e

import io.ktor.websocket.Frame
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.restJsonMapper

class BackendKnowledgeE2eTest {
    @Test
    fun `parent retrieves and searches truncated client tool output`() = knowledgeScenario()

    @Test
    fun `parent retrieves and searches offloaded subagent exhaustion progress`() = knowledgeScenario(progress = true)

    @Test
    fun `failed persistence keeps the original result inline without a reference`() = knowledgeScenario(failWrites = true)

    private fun knowledgeScenario(progress: Boolean = false, failWrites: Boolean = false) {
        val answer = if (progress) "progress-evidence ".repeat(100) else "head:" + "🙂".repeat(270_000) + ":tail"
        var knowledgeId: String? = null
        var expectedMatchStart: Int? = null
        var searched = false
        backendE2eTest(
            schemaPrefix = "e2e_knowledge",
            llm = E2eLlmApi { request ->
                val result = request.messages.lastOrNull { it.role == LLMMessageRole.function }
                when {
                    request.functions.map { it.name } == listOf("user.ask") ->
                        toolCallReply(request, "user.ask", mapOf("question" to "Report progress"))

                    result?.name in listOf("RunSkillCommand", "SpawnSubagent") -> {
                        val body = restJsonMapper.readTree(result!!.content)
                        if (failWrites) {
                            assertFalse(body.has("knowledgeId"))
                            assertTrue(result.content.contains(answer))
                            reply(request, "original result received")
                        } else {
                            knowledgeId = body["knowledgeId"].asText()
                            assertEquals(result.name, body["sourceTool"].asText())
                            toolCallReply(request, "GetKnowledge", mapOf("knowledgeId" to knowledgeId!!))
                        }
                    }

                    result?.name == "GetKnowledge" -> {
                        val body = restJsonMapper.readTree(result.content)
                        assertEquals(knowledgeId, body["knowledgeId"].asText())
                        assertEquals(!progress, body["truncated"].asBoolean())
                        if (progress) {
                            val text = body["text"].asText()
                            val report = restJsonMapper.readTree(text)
                            assertEquals("subagent_turn_limit", report["error"]["code"].asText())
                            assertEquals("incomplete", report["status"].asText())
                            assertEquals(8, report["progress"]["completedToolCallCount"].asInt())
                            expectedMatchStart = text.indexOf("progress-evidence")
                        } else {
                            val tail = body["tail"]
                            expectedMatchStart = tail["start"].asInt() + tail["text"].asText().indexOf(":tail")
                            assertTrue(body["omitted"]["end"].asInt() > body["omitted"]["start"].asInt())
                        }
                        toolCallReply(request, "SearchKnowledge", mapOf(
                            "knowledgeId" to knowledgeId!!,
                            "regex" to if (progress) "progress-evidence" else ":tail",
                            "charsBefore" to 0, "charsAfter" to 0, "maxMatches" to 2,
                        ))
                    }

                    result?.name == "SearchKnowledge" -> {
                        val body = restJsonMapper.readTree(result.content)
                        assertEquals(knowledgeId, body["knowledgeId"].asText())
                        assertEquals(expectedMatchStart, body["matches"][0]["start"].asInt())
                        searched = true
                        reply(request, "retained result received")
                    }

                    progress -> toolCallReply(request, "SpawnSubagent", mapOf(
                        "task" to "Ask repeatedly and report progress", "skillIds" to listOf("user.ask"), "maxTurns" to 8,
                    ))

                    else -> toolCallReply(request, "RunSkillCommand", mapOf(
                        "skillId" to "user.ask", "arguments" to mapOf("question" to "Return the full report"),
                    ))
                }
            },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            if (failWrites) backend.sql { connection ->
                connection.createStatement().use {
                    it.execute("alter table conversation_knowledge add constraint reject_knowledge_writes check (false)")
                }
            }
            var clientCalls = 0
            withPublicSocket(chatId) { session ->
                session.send(Frame.Text(messageFrame(chatId, userId, "knowledge", text = "Read the report")))
                assertEquals("accepted", readJson(session)["status"].asText())
                while (true) {
                    val frame = readJson(session)
                    when (frame["type"]?.asText()) {
                        "tool.call.started" -> {
                            assertEquals("user.ask", frame["payload"]["name"].asText())
                            clientCalls++
                            session.send(Frame.Text(json.writeValueAsString(mapOf(
                                "kind" to "tool.result", "chatId" to chatId, "threadId" to frame["threadId"].asText(),
                                "toolCallId" to frame["payload"]["toolCallId"].asText(),
                                "status" to "succeeded", "result" to mapOf("answer" to answer),
                            ))))
                            assertEquals("accepted", readJson(session)["status"].asText())
                        }
                        "thread.completed" -> break
                        "thread.failed", "thread.cancelled" -> error(frame.toString())
                    }
                }
            }
            assertEquals(if (progress) 8 else 1, clientCalls)
            assertEquals(!failWrites, expectedMatchStart != null)
            assertEquals(!failWrites, searched)
            backend.sql { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from conversation_knowledge").use {
                        assertTrue(it.next())
                        assertEquals(if (failWrites) 0 else 1, it.getInt(1))
                    }
                }
            }
        }
    }
}
