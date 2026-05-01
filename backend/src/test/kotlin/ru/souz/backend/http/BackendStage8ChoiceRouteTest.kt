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
import ru.souz.backend.choices.model.Choice
import ru.souz.backend.choices.model.ChoiceKind
import ru.souz.backend.choices.model.ChoiceOption
import ru.souz.backend.choices.model.ChoiceStatus
import ru.souz.backend.execution.model.AgentExecution
import ru.souz.backend.execution.model.AgentExecutionStatus
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider

private val stage8Json = jacksonObjectMapper()

class BackendStage8ChoiceRouteTest {
    @Test
    fun `choice request persists choice marks execution waiting and replays through event stream`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Choice replay")
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
            setBody("""{"content":"need choice"}""")
        }
        val payload = stage8Json.readTree(response.bodyAsText())
        val storedExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }
        val storedChoice = runBlocking {
            context.choiceRepository.listByExecution("user-a", chat.id, storedExecution.id).single()
        }
        val replayResponse = client.get("/v1/chats/${chat.id}/events?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val replayPayload = stage8Json.readTree(replayResponse.bodyAsText())
        val choiceRequestedEvent = replayPayload["items"].first { it["type"].asText() == "choice.requested" }
        val reconnectSession = runBlocking {
            wsClient.webSocketSession("/v1/chats/${chat.id}/ws?afterSeq=${choiceRequestedEvent["seq"].asLong() - 1}") {
                trustedHeaders("user-a")
            }
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["assistantMessage"].isNull)
        assertEquals("running", payload["execution"]["status"].asText())
        assertEquals(AgentExecutionStatus.WAITING_CHOICE, storedExecution.status)
        assertEquals(ChoiceStatus.PENDING, storedChoice.status)
        assertEquals(storedExecution.id, storedChoice.executionId)
        assertEquals("single", choiceRequestedEvent["payload"]["selectionMode"].asText())
        assertEquals("Alpha", choiceRequestedEvent["payload"]["options"][0]["label"].asText())
        assertEquals(storedChoice.id.toString(), reconnectSession.receiveEvent()["payload"]["choiceId"].asText())
        runBlocking { reconnectSession.close() }
    }

    @Test
    fun `answer route emits choice answered resumes same execution and finishes under same id`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Choice answer")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need choice"}""")
        }
        val waitingExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }
        val choice = runBlocking {
            context.choiceRepository.listByExecution("user-a", chat.id, waitingExecution.id).single()
        }
        val beforeAnswerEvents = client.get("/v1/chats/${chat.id}/events?afterSeq=0") {
            trustedHeaders("user-a")
        }
        val beforeAnswerSeq = stage8Json.readTree(beforeAnswerEvents.bodyAsText())["items"].last()["seq"].asLong()

        val answerResponse = client.post("/v1/choices/${choice.id}/answer") {
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
        val answeredChoice = runBlocking { assertNotNull(context.choiceRepository.get("user-a", choice.id)) }
        val storedExecution = runBlocking {
            assertNotNull(context.executionRepository.getByChat("user-a", chat.id, waitingExecution.id))
        }
        val visibleMessages = runBlocking { context.messageRepository.list("user-a", chat.id) }

        assertEquals(HttpStatusCode.OK, answerResponse.status)
        assertEquals(choice.id.toString(), answerPayload["choice"]["id"].asText())
        assertEquals("answered", answerPayload["choice"]["status"].asText())
        assertEquals(waitingExecution.id.toString(), answerPayload["execution"]["id"].asText())
        assertEquals("running", answerPayload["execution"]["status"].asText())
        assertEquals(ChoiceStatus.ANSWERED, answeredChoice.status)
        assertEquals(setOf("a"), answeredChoice.answer?.selectedOptionIds)
        assertEquals("because alpha", answeredChoice.answer?.freeText)
        assertEquals("web-ui", answeredChoice.answer?.metadata?.get("source"))
        assertEquals(AgentExecutionStatus.COMPLETED, storedExecution.status)
        assertEquals(waitingExecution.id, storedExecution.id)
        assertEquals(
            listOf("need choice", "continued after choosing Alpha"),
            visibleMessages.map { it.content }
        )
        assertEquals(2, visibleMessages.size)
        assertEquals(
            listOf("choice.answered", "message.created", "message.completed", "execution.finished"),
            replayAfterAnswerPayload["items"].map { it["type"].asText() }
        )
        assertTrue(
            replayAfterAnswerPayload["items"].all { it["executionId"].asText() == waitingExecution.id.toString() }
        )
    }

    @Test
    fun `execution usage stays cumulative across waiting choice and continuation`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Choice usage")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need choice"}""")
        }
        val waitingExecution = runBlocking { context.executionRepository.listByChat("user-a", chat.id).single() }
        val choice = runBlocking {
            context.choiceRepository.listByExecution("user-a", chat.id, waitingExecution.id).single()
        }

        assertEquals(5, waitingExecution.usage?.totalTokens)

        client.post("/v1/choices/${choice.id}/answer") {
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
    fun `second answer for same choice is rejected`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Repeat answer")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need choice"}""")
        }
        val choice = runBlocking {
            val execution = context.executionRepository.listByChat("user-a", chat.id).single()
            context.choiceRepository.listByExecution("user-a", chat.id, execution.id).single()
        }

        client.post("/v1/choices/${choice.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val secondResponse = client.post("/v1/choices/${choice.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val secondPayload = stage8Json.readTree(secondResponse.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, secondResponse.status)
        assertEquals("invalid_request", secondPayload["error"]["code"].asText())
    }

    @Test
    fun `foreign choice answer returns not found without leaking ownership`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
        val context = stage8RouteTestContext(runner)
        val chat = chat(userId = "user-a", title = "Owned choice")
        runBlocking {
            context.chatRepository.create(chat)
        }
        installStage8Application(context)

        client.post("/v1/chats/${chat.id}/messages") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"content":"need choice"}""")
        }
        val choice = runBlocking {
            val execution = context.executionRepository.listByChat("user-a", chat.id).single()
            context.choiceRepository.listByExecution("user-a", chat.id, execution.id).single()
        }

        val foreignResponse = client.post("/v1/choices/${choice.id}/answer") {
            trustedHeaders("user-b")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val foreignPayload = stage8Json.readTree(foreignResponse.bodyAsText())
        val missingResponse = client.post("/v1/choices/${UUID.randomUUID()}/answer") {
            trustedHeaders("user-b")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val missingPayload = stage8Json.readTree(missingResponse.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, foreignResponse.status)
        assertEquals("choice_not_found", foreignPayload["error"]["code"].asText())
        assertEquals(HttpStatusCode.NotFound, missingResponse.status)
        assertEquals("choice_not_found", missingPayload["error"]["code"].asText())
    }

    @Test
    fun `invalid option ids selection mode mismatches and expired choices are controlled errors`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
        val context = stage8RouteTestContext(runner)
        installStage8Application(context)

        val singleChoice = seedWaitingChoice(context = context, userId = "user-a", selectionMode = "single")
        val tooManyOptionsResponse = client.post("/v1/choices/${singleChoice.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a","b"]}""")
        }
        val tooManyOptionsPayload = stage8Json.readTree(tooManyOptionsResponse.bodyAsText())

        val invalidOptionChoice = seedWaitingChoice(context = context, userId = "user-a", selectionMode = "single")
        val invalidOptionResponse = client.post("/v1/choices/${invalidOptionChoice.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["missing"]}""")
        }
        val invalidOptionPayload = stage8Json.readTree(invalidOptionResponse.bodyAsText())

        val wrongModeChoice = seedWaitingChoice(context = context, userId = "user-a", selectionMode = "mystery")
        val wrongModeResponse = client.post("/v1/choices/${wrongModeChoice.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val wrongModePayload = stage8Json.readTree(wrongModeResponse.bodyAsText())

        val expiredChoice = seedWaitingChoice(
            context = context,
            userId = "user-a",
            selectionMode = "single",
            expiresAt = Instant.parse("2026-04-30T09:59:00Z"),
        )
        val expiredResponse = client.post("/v1/choices/${expiredChoice.id}/answer") {
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
    fun `answer route is disabled when choices feature flag is off`() = testApplication {
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
                choices = false,
            ),
        )
        installStage8Application(context)

        val choice = seedWaitingChoice(context = context, userId = "user-a")
        val response = client.post("/v1/choices/${choice.id}/answer") {
            trustedHeaders("user-a")
            contentType(ContentType.Application.Json)
            setBody("""{"selectedOptionIds":["a"]}""")
        }
        val payload = stage8Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("feature_disabled", payload["error"]["code"].asText())
    }

    @Test
    fun `sync fallback returns waiting choice with no assistant message`() = testApplication {
        val runner = ScriptedChoiceTurnRunner()
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
                choices = true,
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
            setBody("""{"content":"need choice"}""")
        }
        val payload = stage8Json.readTree(response.bodyAsText())

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(payload["assistantMessage"].isNull)
        assertEquals("waiting_choice", payload["execution"]["status"].asText())
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
            choiceService = context.choiceService,
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
            choices = true,
        ),
        turnRunner = runner,
    )

private suspend fun DefaultClientWebSocketSession.receiveEvent(): JsonNode =
    stage8Json.readTree((incoming.receive() as Frame.Text).readText())

private fun seedWaitingChoice(
    context: RouteTestContext,
    userId: String,
    selectionMode: String = "single",
    expiresAt: Instant? = null,
): Choice {
    val chat = chat(userId = userId, title = "Seeded choice ${UUID.randomUUID()}")
    val execution = AgentExecution(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chat.id,
        userMessageId = UUID.randomUUID(),
        assistantMessageId = null,
        status = AgentExecutionStatus.WAITING_CHOICE,
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
    val choice = Choice(
        id = UUID.randomUUID(),
        userId = userId,
        chatId = chat.id,
        executionId = execution.id,
        kind = ChoiceKind.GENERIC_SELECTION,
        title = "Select variant",
        selectionMode = selectionMode,
        options = listOf(
            ChoiceOption(id = "a", label = "Alpha", content = "alpha"),
            ChoiceOption(id = "b", label = "Beta", content = "beta"),
        ),
        payload = mapOf("origin" to "seed"),
        status = ChoiceStatus.PENDING,
        answer = null,
        createdAt = Instant.parse("2026-05-01T10:01:00Z"),
        expiresAt = expiresAt,
        answeredAt = null,
    )

    runBlocking {
        context.chatRepository.create(chat)
        context.executionRepository.create(execution)
        context.choiceRepository.save(choice)
        context.stateRepository.save(
            ru.souz.backend.agent.session.AgentConversationState(
                userId = userId,
                chatId = chat.id,
                schemaVersion = 1,
                activeAgentId = AgentId.default,
                history = listOf(
                    LLMRequest.Message(role = LLMMessageRole.user, content = "need choice")
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
    return choice
}

private class ScriptedChoiceTurnRunner : BackendConversationTurnRunner {
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
                    choiceId = CHOICE_ID.toString(),
                    kind = ChoiceKind.GENERIC_SELECTION.value,
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
            BackendConversationTurnOutcome.WaitingChoice(
                usage = LLMResponse.Usage(
                    promptTokens = 3,
                    completionTokens = 2,
                    totalTokens = 5,
                    precachedTokens = 0,
                ),
                session = sessionFor(
                    prompt = request.prompt,
                    assistant = "waiting for choice",
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

private val CHOICE_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
