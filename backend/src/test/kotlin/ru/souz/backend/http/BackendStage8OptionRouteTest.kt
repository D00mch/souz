package ru.souz.backend.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.BackendConversationTurnOutcome
import ru.souz.backend.agent.runtime.BackendConversationTurnRunner
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.chat.model.ChatRole
import ru.souz.backend.config.BackendFeatureFlags
import ru.souz.backend.options.model.Option
import ru.souz.backend.options.model.OptionKind
import ru.souz.backend.options.model.OptionItem
import ru.souz.backend.options.model.OptionStatus
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider

private val stage8Json = jacksonObjectMapper()

class BackendStage8OptionRouteTest {
    @Test
    fun `option request persists option marks execution waiting and replays through event stream`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Option replay")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)
        val wsClient = createClient {
            install(WebSockets)
        }

        val response = client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need option"}""")
        }
        val payload = stage8Json.readTree(response.bodyAsText())
        val storedExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }
        val storedOption = runBlocking {
            context.optionRepository.listByExecution("user-a", chat.id, storedExecution.id).single()
        }
        val replayResponse = client.get("/v1/chats/${chat.id}/events?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val replayPayload = stage8Json.readTree(replayResponse.bodyAsText())
        val optionRequestedEvent = replayPayload["items"].first { it["type"].asText() == "option.requested" }
        val reconnectSession = runBlocking {
            wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=${optionRequestedEvent["seq"].asLong() - 1}") {
                trustedHeaders("user-a")
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["assistantMessage"].isNull)
        assertEquals("running", payload["execution"]["status"].asText())
        assertEquals(AgentExecutionStatus.WAITING_OPTION, storedExecution.status)
        assertEquals(OptionStatus.PENDING, storedOption.status)
        assertEquals(storedExecution.id, storedOption.executionId)
        assertEquals("single", optionRequestedEvent["payload"]["selectionMode"].asText())
        assertEquals("Alpha", optionRequestedEvent["payload"]["options"][0]["label"].asText())
        assertEquals(storedOption.id.toString(), reconnectSession.receiveEvent()["payload"]["optionId"].asText())
        runBlocking { reconnectSession.close() }
    }

    @Test
    fun `answer route emits option answered resumes same execution and finishes under same id`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Option answer")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need option"}""")
        }
        val waitingExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }
        val option = runBlocking {
            context.optionRepository.listByExecution("user-a", chat.id, waitingExecution.id).single()
        }
        val beforeAnswerEvents = client.get("/v1/chats/${chat.id}/events?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val beforeAnswerSeq = stage8Json.readTree(beforeAnswerEvents.bodyAsText())["items"].last()["seq"].asLong()

        val answerResponse = client.post("/v1/options/${option.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "selectedOptionIds": ["a"],
                  "freeText": "  because alpha  ",
                  "metadata": {
                    "source": "web-ui"
                  }
                }
                """.trimIndent()
            )
        }
        val answerPayload = stage8Json.readTree(answerResponse.bodyAsText())
        val replayAfterAnswer = client.get("/v1/chats/${chat.id}/events?afterSeq=$beforeAnswerSeq") {
            trustedHeaders("user-a")
        }
        val replayAfterAnswerPayload = stage8Json.readTree(replayAfterAnswer.bodyAsText())
        val answeredOption = runBlocking { assertNotNull(context.optionRepository.get("user-a", option.id)) }
        val storedExecution = runBlocking {
            assertNotNull(context.executionRepository.getByChat("user-a", chat.id, waitingExecution.id))
        }
        val visibleMessages = runBlocking { context.messageRepository.list("user-a", chat.id) }

        assertEquals(HttpStatusCode.OK, answerResponse.status)
        assertEquals(option.id.toString(), answerPayload["option"]["id"].asText())
        assertEquals("answered", answerPayload["option"]["status"].asText())
        assertEquals(waitingExecution.id.toString(), answerPayload["execution"]["id"].asText())
        assertEquals("running", answerPayload["execution"]["status"].asText())
        assertEquals(OptionStatus.ANSWERED, answeredOption.status)
        assertEquals(setOf("a"), answeredOption.answer?.selectedOptionIds)
        assertEquals("because alpha", answeredOption.answer?.freeText)
        assertEquals("web-ui", answeredOption.answer?.metadata?.get("source"))
        assertEquals(AgentExecutionStatus.COMPLETED, storedExecution.status)
        assertEquals(waitingExecution.id, storedExecution.id)
        assertEquals(
            listOf("need option", "continued after choosing Alpha"),
            visibleMessages.map { it.content }
        )
        assertEquals(2, visibleMessages.size)
        assertEquals(
            listOf("option.answered", "message.created", "message.completed", "execution.finished"),
            replayAfterAnswerPayload["items"].map { it["type"].asText() }
        )
        assertTrue(
            replayAfterAnswerPayload["items"].all { it["executionId"].asText() == waitingExecution.id.toString() }
        )
    }

    @Test
    fun `execution usage stays cumulative across waiting option and continuation`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Option usage")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need option"}""")
        }
        val waitingExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }
        val option = runBlocking {
            context.optionRepository.listByExecution("user-a", chat.id, waitingExecution.id).single()
        }

        assertEquals(5, waitingExecution.usage?.totalTokens)

        client.post("/v1/options/${option.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val completedExecution = runBlocking {
            context.executionRepository.getByChat("user-a", chat.id, waitingExecution.id)
        }

        assertEquals(12, completedExecution?.usage?.totalTokens)
        assertEquals(7, completedExecution?.usage?.completionTokens)
    }

    @Test
    fun `second answer for same option is rejected`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Repeat answer")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need option"}""")
        }
        val option = runBlocking {
            val execution = context.executionRepository.listByChat("user-a", chat.id).single()
            context.optionRepository.listByExecution("user-a", chat.id, execution.id).single()
        }

        client.post("/v1/options/${option.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val secondResponse = client.post("/v1/options/${option.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val secondPayload = stage8Json.readTree(secondResponse.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, secondResponse.status)
        assertEquals("invalid_request", secondPayload["error"]["code"].asText())
    }

    @Test
    fun `foreign option answer returns not found without leaking ownership`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Owned option")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need option"}""")
        }
        val option = runBlocking {
            val execution = context.executionRepository.listByChat("user-a", chat.id).single()
            context.optionRepository.listByExecution("user-a", chat.id, execution.id).single()
        }

        val foreignResponse = client.post("/v1/options/${option.id}/answer") {
            trustedHeaders("user-b")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val foreignPayload = stage8Json.readTree(foreignResponse.bodyAsText())
        val missingResponse = client.post("/v1/options/${UUID.randomUUID()}/answer") {
            trustedHeaders("user-b")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val missingPayload = stage8Json.readTree(missingResponse.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
        assertEquals("option_not_found", foreignPayload["error"]["code"].asText())
        assertEquals(HttpStatusCode.NotFound, missingResponse.status)
        assertEquals("option_not_found", missingPayload["error"]["code"].asText())
    }

    @Test
    fun `invalid option ids selection mode mismatches and expired options are controlled errors`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = stage8RouteTestContext(runner)
        installStage8Application(context)

        val singleOption = seedWaitingOption(context = context, userId = "user-a", selectionMode = "single")
        val tooManyOptionsResponse = client.post("/v1/options/${singleOption.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a","b"]}""")
        }
        val tooManyOptionsPayload = stage8Json.readTree(tooManyOptionsResponse.bodyAsText())

        val invalidOption = seedWaitingOption(context = context, userId = "user-a", selectionMode = "single")
        val invalidOptionResponse = client.post("/v1/options/${invalidOption.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["missing"]}""")
        }
        val invalidOptionPayload = stage8Json.readTree(invalidOptionResponse.bodyAsText())

        val wrongModeOption = seedWaitingOption(context = context, userId = "user-a", selectionMode = "mystery")
        val wrongModeResponse = client.post("/v1/options/${wrongModeOption.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val wrongModePayload = stage8Json.readTree(wrongModeResponse.bodyAsText())

        val expiredOption = seedWaitingOption(
            context = context,
            userId = "user-a",
            selectionMode = "single",
            expiresAt = Instant.parse("2026-04-30T09:59:00Z"),
        )
        val expiredResponse = client.post("/v1/options/${expiredOption.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"],"freeText":"   "}""")
        }
        val expiredPayload = stage8Json.readTree(expiredResponse.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, tooManyOptionsResponse.status)
        assertEquals("invalid_request", tooManyOptionsPayload["error"]["code"].asText())
        assertEquals(HttpStatusCode.BadRequest, invalidOptionResponse.status)
        assertEquals("invalid_request", invalidOptionPayload["error"]["code"].asText())
        assertEquals(HttpStatusCode.BadRequest, wrongModeResponse.status)
        assertEquals("invalid_request", wrongModePayload["error"]["code"].asText())
        assertEquals(HttpStatusCode.BadRequest, expiredResponse.status)
        assertEquals("invalid_request", expiredPayload["error"]["code"].asText())
    }

    @Test
    fun `answer route is disabled when options feature flag is off`() = testApplication {
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = "qwen-key"
                contextSize = 24_000
                temperature = 0.6f
                useStreaming = true
            },
            featureFlags = BackendFeatureFlags(
                wsEvents = true,
                streamingMessages = true,
                toolEvents = true,
                options = false,
            ),
        )
        installStage8Application(context)

        val option = seedWaitingOption(context = context, userId = "user-a")
        val response = client.post("/v1/options/${option.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val payload = stage8Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("feature_disabled", payload["error"]["code"].asText())
    }

    @Test
    fun `sync fallback returns waiting option with no assistant message`() = testApplication {
        val runner = ScriptedOptionTurnRunner()
        val context = routeTestContext(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                qwenChatKey = "qwen-key"
                contextSize = 24_000
                temperature = 0.6f
                useStreaming = true
            },
            featureFlags = BackendFeatureFlags(
                wsEvents = false,
                streamingMessages = true,
                toolEvents = true,
                options = true,
            ),
            turnRunner = runner,
        )
        val chat = chat(userId = "user-a", title = "Sync fallback")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        val response = client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need option"}""")
        }
        val payload = stage8Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["assistantMessage"].isNull)
        assertEquals("waiting_option", payload["execution"]["status"].asText())
    }
}

private fun ApplicationTestBuilder.installStage8Application(context: RouteTestContext) {
    this.application {
        backendApplication(
            agentService = context.agentService,
            bootstrapService = context.bootstrapService,
            selectedModel = { context.settingsProvider.gigaModel.alias },
            internalAgentToken = { "legacy-token" },
            trustedProxyToken = { "proxy-secret" },
            userSettingsService = context.userSettingsService,
            chatService = context.chatService,
            messageService = context.messageService,
            executionService = context.executionService,
            optionService = context.optionService,
            eventService = context.eventService,
            featureFlags = context.featureFlags,
        )
    }
}

private fun stage8RouteTestContext(runner: BackendConversationTurnRunner): RouteTestContext =
    routeTestContext(
        settingsProvider = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            qwenChatKey = "qwen-key"
            contextSize = 24_000
            temperature = 0.6f
            useStreaming = true
        },
        featureFlags = BackendFeatureFlags(
            wsEvents = true,
            streamingMessages = true,
            toolEvents = true,
            options = true,
        ),
        turnRunner = runner,
    )

private suspend fun DefaultClientWebSocketSession.receiveEvent(): JsonNode =
    stage8Json.readTree((incoming.receive() as Frame.Text).readText())

private fun seedWaitingOption(
    context: RouteTestContext,
    userId: String,
    selectionMode: String = "single",
    expiresAt: Instant? = null,
): Option {
    val chat = chat(userId = userId, title = "Seeded option ${UUID.randomUUID()}")
    val execution = AgentExecution(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chat.id,
        userMessageId = UUID.randomUUID(),
        assistantMessageId = null,
        status = AgentExecutionStatus.WAITING_OPTION,
        requestId = null,
        clientMessageId = null,
        model = LLMModel.Max,
        provider = LLMModel.Max.provider,
        startedAt = Instant.parse("2026-05-01T10:00:00Z"),
        finishedAt = null,
        cancelRequested = false,
        errorCode = null,
        errorMessage = null,
        usage = null,
        metadata = mapOf(
            "contextSize" to "24000",
            "temperature" to "0.6",
            "locale" to "ru-RU",
            "timeZone" to "Europe/Moscow",
            "streamingMessages" to "true",
        ),
    )
    val option = Option(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chat.id,
        executionId = execution.id,
        kind = OptionKind.GENERIC_SELECTION,
        title = "Select variant",
        selectionMode = selectionMode,
        options = listOf(
            OptionItem(id = "a", label = "Alpha", content = "alpha"),
            OptionItem(id = "b", label = "Beta", content = "beta"),
        ),
        payload = mapOf("origin" to "seed"),
        status = OptionStatus.PENDING,
        answer = null,
        createdAt = Instant.parse("2026-05-01T10:01:00Z"),
        expiresAt = expiresAt,
        answeredAt = null,
    )

    runBlocking {
        context.chatRepository.create(chat)
        context.executionRepository.create(execution)
        context.optionRepository.save(option)
        context.stateRepository.save(
            ru.souz.backend.agent.session.AgentConversationState(
                userId = userId,
                chatId = chat.id,
                schemaVersion = 1,
                activeAgentId = AgentId.default,
                history = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "need option")
                ),
                temperature = 0.6f,
                locale = Locale.forLanguageTag("ru-RU"),
                timeZone = ZoneId.of("Europe/Moscow"),
                basedOnMessageSeq = 0L,
                updatedAt = Instant.parse("2026-05-01T10:02:00Z"),
                rowVersion = 0L,
            )
        )
    }
    return option
}

private class ScriptedOptionTurnRunner : BackendConversationTurnRunner {
    private val conversationsWaitingForAnswer = LinkedHashSet<AgentConversationKey>()

    override suspend fun run(
        conversationKey: AgentConversationKey,
        request: BackendConversationTurnRequest,
        eventSink: AgentRuntimeEventSink,
        initialUsage: LLMResponse.Usage,
    ): BackendConversationTurnOutcome {
        return if (conversationsWaitingForAnswer.add(conversationKey)) {
            eventSink.emit(
                AgentRuntimeEvent.ChoiceRequested(
                    choiceId = OPTION_ID.toString(),
                    kind = OptionKind.GENERIC_SELECTION.value,
                    title = "Select variant",
                    selectionMode = "single",
                    options = listOf(
                        AgentRuntimeEvent.ChoiceRequested.ChoiceOption(
                            id = "a",
                            label = "Alpha",
                            content = "alpha",
                        ),
                        AgentRuntimeEvent.ChoiceRequested.ChoiceOption(
                            id = "b",
                            label = "Beta",
                            content = "beta",
                        ),
                    ),
                )
            )
            BackendConversationTurnOutcome.WaitingOption(
                usage = LLMResponse.Usage(
                    promptTokens = 3,
                    completionTokens = 2,
                    totalTokens = 5,
                    precachedTokens = 0,
                ),
                session = sessionFor(
                    prompt = request.prompt,
                    assistant = "waiting for option",
                )
            )
        } else {
            conversationsWaitingForAnswer.remove(conversationKey)
            BackendConversationTurnOutcome.Completed(
                output = "continued after choosing Alpha",
                usage = LLMResponse.Usage(
                    promptTokens = 5,
                    completionTokens = 7,
                    totalTokens = 12,
                    precachedTokens = 0,
                ),
                session = sessionFor(
                    prompt = request.prompt,
                    assistant = "continued after choosing Alpha",
                ),
            )
        }
    }
}

private fun sessionFor(
    prompt: String,
    assistant: String,
): AgentConversationSession =
    AgentConversationSession(
        activeAgentId = AgentId.default,
        history = listOf(
            LLMRequest.Message(role = LLMMessageRole.user, content = prompt),
            LLMRequest.Message(role = LLMMessageRole.assistant, content = assistant),
        ),
        temperature = 0.6f,
        locale = "ru-RU",
        timeZone = "Europe/Moscow",
    )

private val OPTION_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
