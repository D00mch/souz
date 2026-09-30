package ru.souz.backend.e2e

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.encoder.JsonEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.JsonNode
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.Frame
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory
import ru.souz.backend.app.BackendApplicationScope
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.execution.service.AgentExecutionFinalizer
import ru.souz.backend.http.BackendHttpRoutes
import ru.souz.llms.LLMResponse
import ru.souz.llms.restJsonMapper

class BackendTokenUsageE2eTest {
    @Test
    fun `JSON usage logs count cumulative streaming and nested calls once per execution`() {
        listOf(false, true).forEach { streaming ->
            withExecutionUsageLogs { logs ->
                backendE2eTest(
                    schemaPrefix = "e2e_token_usage",
                    featureFlags = BackendFeatureFlags(wsEvents = true, streamingMessages = true),
                    llm = if (streaming) E2eLlmApi() else E2eLlmApi { request ->
                        if (request.functions.any { it.name == "SpawnSubagent" } && request.messages.none { it.name == "SpawnSubagent" }) {
                            toolCallReply(request, "SpawnSubagent", mapOf("task" to "Answer privately"))
                                .copy(usage = LLMResponse.Usage(7, 3, 10, 2))
                        } else {
                            reply(request, "private answer").copy(usage = LLMResponse.Usage(7, 3, 10, 2))
                        }
                    },
                ) {
                    val userId = UUID.randomUUID().toString()
                    val chatId = createPublicChat(userId)
                    client.patch(BackendHttpRoutes.SETTINGS) {
                        trusted(userId)
                        jsonBody("""{"defaultModel":"${E2E_LOCAL_MODEL.alias}","streamingMessages":$streaming}""")
                    }
                    val body = """{"content":"private prompt","clientMessageId":"usage-request"}"""
                    val sent = client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody(body) }
                    assertEquals(HttpStatusCode.OK, sent.status)
                    val executionId = sent.jsonBody()["execution"]["id"].asText()
                    eventually("completed execution") {
                        client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                            .firstOrNull { it["type"].asText() == "execution.finished" }
                    }
                    val duplicate = client.post(BackendHttpRoutes.chatMessages(chatId)) { trusted(userId); jsonBody(body) }
                    assertEquals(executionId, duplicate.jsonBody()["execution"]["id"].asText())
                    val record = logs.single()
                    assertEquals("INFO", record["level"].asText())
                    val fields = record["mdc"]
                    assertEquals(userId, fields["userId"].asText())
                    assertEquals(chatId, fields["chatId"].asText())
                    assertEquals(executionId, fields["threadId"].asText())
                    val usage = record.usageFields()
                    assertEquals("execution.token_usage", usage["event"])
                    assertEquals(if (streaming) "7" else "21", usage["input_tokens"])
                    assertEquals(if (streaming) "3" else "9", usage["output_tokens"])
                    assertEquals(if (streaming) "10" else "30", usage["total_tokens"])
                    assertEquals(if (streaming) "0" else "6", usage["cached_input_tokens"])
                    assertFalse(record.toString().contains("private prompt"))
                    assertFalse(record.toString().contains("private answer"))
                }
            }
        }
    }

    @Test
    fun `zero usage is logged explicitly`() = withExecutionUsageLogs { logs ->
        backendE2eTest(
            schemaPrefix = "e2e_zero_usage",
            llm = E2eLlmApi { reply(it, "done").copy(usage = LLMResponse.Usage(0, 0, 0, 0)) },
        ) {
            val userId = UUID.randomUUID().toString()
            val chatId = createPublicChat(userId)
            client.post(BackendHttpRoutes.chatMessages(chatId)) {
                trusted(userId)
                jsonBody("""{"content":"zero usage","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
            }
            val fields = eventually("zero usage log") { logs.singleOrNull()?.usageFields() }
            listOf("input_tokens", "output_tokens", "total_tokens", "cached_input_tokens").forEach {
                assertEquals("0", fields[it])
            }
        }
    }

    @Test
    fun `cancellation after usage logging preserves completion and its terminal event`() {
        listOf(false, true).forEach { publicThread ->
            lateinit var applicationScope: BackendApplicationScope
            withExecutionUsageLogs(onUsage = { applicationScope.cancel() }) { logs ->
                backendE2eTest("e2e_usage_cancel") {
                    applicationScope = backend.applicationScope
                    val userId = UUID.randomUUID().toString()
                    val chatId = createPublicChat(userId)
                    val executionId = if (publicThread) withPublicSocket(chatId) { socket ->
                        socket.send(Frame.Text(messageFrame(chatId, userId, "cancel-after-usage")))
                        readJson(socket)["thread"]["id"].asText()
                    } else {
                        client.post(BackendHttpRoutes.chatMessages(chatId)) {
                            trusted(userId)
                            jsonBody("""{"content":"complete before shutdown","options":{"model":"${E2E_LOCAL_MODEL.alias}"}}""")
                        }.jsonBody()["execution"]["id"].asText()
                    }
                    backend.awaitExecution(UUID.fromString(executionId))
                    val events = client.get(BackendHttpRoutes.chatEvents(chatId)) { trusted(userId) }.jsonBody()["items"]
                    val terminalTypes = setOf("execution.finished", "execution.failed", "execution.cancelled",
                        "thread.completed", "thread.failed", "thread.cancelled")
                    assertEquals(listOf(if (publicThread) "thread.completed" else "execution.finished"),
                        events.map { it["type"].asText() }.filter { it in terminalTypes })
                    sql { connection ->
                        connection.prepareStatement("select status, usage_json from agent_executions where id = ?").use {
                            it.setObject(1, UUID.fromString(executionId))
                            it.executeQuery().use { rows ->
                                check(rows.next())
                                assertEquals("completed", rows.getString("status"))
                                assertEquals(logs.single().usageFields()["total_tokens"]?.toInt(),
                                    json.readTree(rows.getString("usage_json"))["totalTokens"].asInt())
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun withExecutionUsageLogs(onUsage: () -> Unit = {}, block: (List<JsonNode>) -> Unit) {
    val logs = CopyOnWriteArrayList<JsonNode>()
    val logger = LoggerFactory.getLogger(AgentExecutionFinalizer::class.java) as Logger
    val encoder = JsonEncoder()
    val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            logs.add(restJsonMapper.readTree(encoder.encode(event)))
            onUsage()
        }
    }.apply { start() }
    logger.addAppender(appender)
    try {
        block(logs)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}

internal fun JsonNode.usageFields(): Map<String, String> = get("kvpList").associate {
    val name = it.fieldNames().next()
    name to it[name].asText()
}
