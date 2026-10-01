package ru.souz.backend.e2e

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import ru.souz.backend.execution.service.AgentExecutionFinalizer
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMResponse

class BackendTokenUsageE2eTest {
    @Test
    fun `completion logs JSON usage and survives cancellation before its terminal event`() {
        listOf(false, true).forEach { publicThread ->
            val usage = if (publicThread) LLMResponse.Usage(0, 0, 0, 0) else LLMResponse.Usage(7, 3, 10, 2)
            backendE2eTest("e2e_token_usage", llm = E2eLlmApi { reply(it, "done").copy(usage = usage) }) {
                val logs = CopyOnWriteArrayList<JsonNode>()
                val logger = LoggerFactory.getLogger(AgentExecutionFinalizer::class.java) as Logger
                val encoder = JsonEncoder()
                val appender = object : AppenderBase<ILoggingEvent>() {
                    override fun append(event: ILoggingEvent) {
                        logs.add(json.readTree(encoder.encode(event)))
                        // Cancel after completion is stored, before the next suspending event write.
                        backend.applicationScope.cancel()
                    }
                }.apply { start() }
                logger.addAppender(appender)
                try {
                    val userId = UUID.randomUUID().toString()
                    val chatId = createPublicChat(userId)
                    val body = """{"content":"count tokens","clientMessageId":"usage","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}"""
                    val executionId = if (publicThread) withPublicSocket(chatId) { socket ->
                        socket.send(Frame.Text(messageFrame(chatId, userId, "usage")))
                        readJson(socket)["thread"]["id"].asText()
                    } else {
                        client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody(body) }
                            .jsonBody()["execution"]["id"].asText()
                    }
                    backend.awaitExecution(UUID.fromString(executionId))
                    if (!publicThread) {
                        val duplicate = client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody(body) }
                        assertEquals("completed", duplicate.jsonBody()["execution"]["status"].asText())
                        assertEquals(executionId, duplicate.jsonBody()["execution"]["id"].asText())
                    }
                    val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                    val terminalTypes = setOf("execution.finished", "execution.failed", "execution.cancelled",
                        "thread.completed", "thread.failed", "thread.cancelled")
                    assertEquals(listOf(if (publicThread) "thread.completed" else "execution.finished"),
                        events.map { it["type"].asText() }.filter { it in terminalTypes })
                    val record = logs.single()
                    assertEquals("INFO", record["level"].asText())
                    assertEquals(userId, record["mdc"]["userId"].asText())
                    assertEquals(chatId, record["mdc"]["chatId"].asText())
                    assertEquals(executionId, record["mdc"]["threadId"].asText())
                    assertEquals(mapOf(
                        "event" to "execution.token_usage",
                        "input_tokens" to usage.promptTokens.toString(),
                        "output_tokens" to usage.completionTokens.toString(),
                        "total_tokens" to usage.totalTokens.toString(),
                        "cached_input_tokens" to usage.precachedTokens.toString(),
                    ), record["kvpList"].associate {
                        val field = it.properties().single()
                        field.key to field.value.asText()
                    })
                    sql { connection ->
                        connection.prepareStatement("select status, usage_json from agent_executions where id = ?").use {
                            it.setObject(1, UUID.fromString(executionId))
                            it.executeQuery().use { rows ->
                                check(rows.next())
                                assertEquals("completed", rows.getString("status"))
                                assertEquals(usage.totalTokens, json.readTree(rows.getString("usage_json"))["totalTokens"].asInt())
                            }
                        }
                    }
                } finally {
                    logger.detachAppender(appender)
                    appender.stop()
                }
            }
        }
    }
}
