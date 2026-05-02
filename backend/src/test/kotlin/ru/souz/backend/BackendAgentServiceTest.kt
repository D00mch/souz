package ru.souz.backend

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.AgentRequest
import ru.souz.backend.agent.runtime.BackendConversationRuntimeFactory
import ru.souz.backend.agent.runtime.BackendNoopAgentToolCatalog
import ru.souz.backend.agent.runtime.BackendNoopAgentToolsFilter
import ru.souz.backend.agent.service.BackendAgentService
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.AgentSessionRepository
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.backend.common.BackendRequestException
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.giga.toGiga
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel
import ru.souz.tool.FewShotExample
import ru.souz.tool.InputParamDescription
import ru.souz.tool.ReturnParameters
import ru.souz.tool.ReturnProperty
import ru.souz.tool.ToolCategory
import ru.souz.tool.ToolSetup
import ru.souz.tool.math.ToolCalculator

class BackendAgentServiceTest {
    @Test
    fun `backend module uses runtime and does not depend on composeApp`() {
        val rootDir = File(System.getProperty("user.dir"))
        val buildFile = listOf(
            File(rootDir, "backend/build.gradle.kts"),
            File(rootDir, "../backend/build.gradle.kts"),
        ).firstOrNull { it.exists() } ?: error("backend/build.gradle.kts not found")

        val buildScript = buildFile.readText()
        assertTrue("project(\":runtime\")" in buildScript)
        assertTrue("project(\":composeApp\")" !in buildScript)
    }

    @Test
    fun `agent service uses shared agent runtime path`() = runTest {
        val api = RecordingAgentApi()
        val service = createService(api)

        val response = service.sendAgentRequest(agentRequest(prompt = "Напиши план работ"))

        assertTrue(api.requests.any { it.isClassificationRequest() })
        assertTrue(api.finalChatRequests().isNotEmpty())
        assertEquals("agent reply to Напиши план работ", response.content)
    }

    @Test
    fun `conversation snapshot is loaded on each turn`() = runTest {
        val api = RecordingAgentApi()
        val sessionRepository = CountingAgentSessionRepository()
        val request = agentRequest(prompt = "Первая задача")
        val service = createService(api, sessionRepository = sessionRepository)

        service.sendAgentRequest(request)
        service.sendAgentRequest(request.copy(requestId = uuid(), prompt = "Вторая задача"))

        assertEquals(2, sessionRepository.loadCount)
    }

    @Test
    fun `session context persists across turns for same user and conversation`() = runTest {
        val api = RecordingAgentApi()
        val request = agentRequest(prompt = "Первая задача")
        val service = createService(api)

        service.sendAgentRequest(request)
        service.sendAgentRequest(request.copy(requestId = uuid(), prompt = "Вторая задача"))

        val lastChatRequest = api.finalChatRequests().last()
        val historyTexts = lastChatRequest.messages.map { it.content }
        assertTrue("Первая задача" in historyTexts)
        assertTrue(historyTexts.any { it.contains("agent reply to Первая задача") })
    }

    @Test
    fun `model can change between turns in same conversation`() = runTest {
        val api = RecordingAgentApi()
        val request = agentRequest(prompt = "Первая задача", model = LLMModel.Max.alias)
        val service = createService(api)

        service.sendAgentRequest(request)
        service.sendAgentRequest(
                request.copy(
                    requestId = uuid(),
                    prompt = "Вторая задача",
                    model = LLMModel.OpenAIGpt52.alias,
                )
        )

        val finalChatRequests = api.finalChatRequests()
        assertEquals(LLMModel.Max.alias, finalChatRequests.first().model)
        assertEquals(LLMModel.OpenAIGpt52.alias, finalChatRequests.last().model)
        val secondHistory = finalChatRequests.last().messages.map { it.content }
        assertTrue("Первая задача" in secondHistory)
    }

    @Test
    fun `context size can change between turns in same conversation`() = runTest {
        val api = RecordingAgentApi()
        val request = agentRequest(prompt = "Первая задача", contextSize = 8_000)
        val service = createService(api)

        service.sendAgentRequest(request)
        service.sendAgentRequest(
            request.copy(
                requestId = uuid(),
                prompt = "Вторая задача",
                contextSize = 32_000,
            )
        )

        val finalChatRequests = api.finalChatRequests()
        assertEquals(8_000, finalChatRequests.first().maxTokens)
        assertEquals(32_000, finalChatRequests.last().maxTokens)
        val secondHistory = finalChatRequests.last().messages.map { it.content }
        assertTrue(historyContainsConversationTurn(secondHistory, "Первая задача"))
        assertTrue(secondHistory.any { it.contains("agent reply to Первая задача") })
    }

    @Test
    fun `sessions are isolated by user and conversation`() = runTest {
        val api = RecordingAgentApi()
        val service = createService(api)

        val conversationId = uuid()
        service.sendAgentRequest(agentRequest(userId = uuid(), conversationId = conversationId, prompt = "A1"))
        service.sendAgentRequest(agentRequest(userId = uuid(), conversationId = conversationId, prompt = "B1"))
        service.sendAgentRequest(agentRequest(userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", conversationId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", prompt = "A2"))
        service.sendAgentRequest(agentRequest(userId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", conversationId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", prompt = "A3"))

        val lastChatRequest = api.finalChatRequests().last()
        val historyTexts = lastChatRequest.messages.map { it.content }
        assertTrue("A2" in historyTexts)
        assertTrue(historyTexts.any { it.contains("agent reply to A2") })
        assertTrue("B1" !in historyTexts)
    }

    @Test
    fun `duplicate requestId still returns 409`() = runTest {
        val request = agentRequest()
        val service = createService(RecordingAgentApi())

        service.sendAgentRequest(request)
        val error = assertFailsWith<BackendRequestException> {
            service.sendAgentRequest(request)
        }

        assertEquals(409, error.statusCode)
    }

    @Test
    fun `completed request ids refresh recency before eviction`() = runTest {
        val service = createService(RecordingAgentApi())
        val rememberCompletedRequestId = BackendAgentService::class.java
            .getDeclaredMethod("rememberCompletedAgentRequestId", String::class.java)
            .apply { isAccessible = true }
        val completedRequestIdsField = BackendAgentService::class.java
            .getDeclaredField("completedAgentRequestIds")
            .apply { isAccessible = true }

        repeat(10_000) { index ->
            rememberCompletedRequestId.invoke(service, "req-$index")
        }
        rememberCompletedRequestId.invoke(service, "req-0")
        rememberCompletedRequestId.invoke(service, "req-10000")

        @Suppress("UNCHECKED_CAST")
        val completedRequestIds = completedRequestIdsField.get(service) as Set<String>

        assertEquals(10_000, completedRequestIds.size)
        assertTrue("req-0" in completedRequestIds)
        assertTrue("req-10000" in completedRequestIds)
        assertTrue("req-1" !in completedRequestIds)
    }

    @Test
    fun `different conversations do not cancel each other`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = RecordingAgentApi(
            onFinalChatRequest = { request ->
                when (request.conversationPrompt()) {
                    "A1" -> firstStarted.complete(Unit)
                    "B1" -> secondStarted.complete(Unit)
                }
                release.await()
            }
        )
        val service = createService(api)

        val firstCall = async {
            service.sendAgentRequest(
                agentRequest(
                    userId = "11111111-1111-1111-1111-111111111111",
                    conversationId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                    prompt = "A1",
                )
            )
        }
        val secondCall = async {
            service.sendAgentRequest(
                agentRequest(
                    userId = "22222222-2222-2222-2222-222222222222",
                    conversationId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                    prompt = "B1",
                )
            )
        }

        firstStarted.await()
        secondStarted.await()
        release.complete(Unit)

        assertEquals("agent reply to A1", firstCall.await().content)
        assertEquals("agent reply to B1", secondCall.await().content)
    }

    @Test
    fun `concurrent active request for same conversation still returns 409`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = RecordingAgentApi(
            onFinalChatRequest = { _ ->
                started.complete(Unit)
                release.await()
            }
        )
        val request = agentRequest(
            userId = "11111111-1111-1111-1111-111111111111",
            conversationId = "22222222-2222-2222-2222-222222222222",
        )
        val service = createService(api)

        val firstCall = async { service.sendAgentRequest(request) }
        started.await()
        val error = assertFailsWith<BackendRequestException> {
            service.sendAgentRequest(request.copy(requestId = uuid()))
        }
        release.complete(Unit)
        firstCall.await()

        assertEquals(409, error.statusCode)
    }

    @Test
    fun `backend runtime executes shared tools`() = runTest {
        val api = ToolCallingAgentApi()
        val service = createService(
            api = api,
            toolCatalog = toolCatalog(ToolCategory.CALCULATOR to ToolCalculator().toGiga()),
        )

        val response = service.sendAgentRequest(agentRequest(prompt = "Сколько будет 2 + 3?"))

        assertEquals("tool result: 5", response.content)
        assertTrue(api.requests.any { request -> request.functions.any { fn -> fn.name == "Calculator" } })
        assertTrue(
            api.requests.any { request ->
                request.messages.any { message ->
                    message.role == LLMMessageRole.function &&
                        message.name == "Calculator" &&
                        message.content.contains("5")
                }
            }
        )
    }

    @Test
    fun `tool results persist across turns in same backend conversation`() = runTest {
        val api = ToolCallingAgentApi()
        val request = agentRequest(prompt = "Сколько будет 2 + 3?")
        val service = createService(
            api = api,
            toolCatalog = toolCatalog(ToolCategory.CALCULATOR to ToolCalculator().toGiga()),
        )

        service.sendAgentRequest(request)
        val secondResponse = service.sendAgentRequest(
            request.copy(
                requestId = uuid(),
                prompt = "А какой был прошлый результат?",
            )
        )

        assertEquals("tool result: 5", secondResponse.content)

        val secondTurnRequest = api.requests.finalChatRequests().last()
        val persistedToolResult = secondTurnRequest.messages.lastOrNull { message ->
            message.role == LLMMessageRole.function && message.name == "Calculator"
        }
        assertEquals("call_1", persistedToolResult?.functionsStateId)
        assertTrue(persistedToolResult?.content?.contains("5") == true)
    }

    @Test
    fun `backend runtime executes every tool call from one model reply`() = runTest {
        val api = MultiToolCallingAgentApi()
        val service = createService(
            api = api,
            toolCatalog = toolCatalog(ToolCategory.CALCULATOR to ToolCalculator().toGiga()),
        )

        val response = service.sendAgentRequest(agentRequest(prompt = "Посчитай 2 + 3 и 10 - 4"))

        assertEquals("tool results: 5, 6", response.content)

        val requestAfterTools = api.requests.finalChatRequests().last()
        val toolResults = requestAfterTools.messages.filter { message ->
            message.role == LLMMessageRole.function && message.name == "Calculator"
        }
        assertEquals(2, toolResults.size)
        assertTrue(toolResults.any { it.functionsStateId == "call_1" && it.content.contains("5") })
        assertTrue(toolResults.any { it.functionsStateId == "call_2" && it.content.contains("6") })
    }

    @Test
    fun `tool execution failures are returned as function messages`() = runTest {
        val api = FailingToolAgentApi()
        val service = createService(
            api = api,
            toolCatalog = toolCatalog(ToolCategory.CALCULATOR to ThrowingTool().toGiga()),
        )

        val response = service.sendAgentRequest(agentRequest(prompt = "Попробуй вызвать аварийный инструмент"))

        assertEquals("tool error handled", response.content)

        val requestAfterFailure = api.requests.finalChatRequests().last()
        val toolFailure = requestAfterFailure.messages.lastOrNull { message ->
            message.role == LLMMessageRole.function && message.name == "ExplodeTool"
        }
        assertEquals("explode_1", toolFailure?.functionsStateId)
        assertTrue(toolFailure?.content?.contains("Can't invoke function: boom") == true)
    }

    private fun createService(
        api: LLMChatAPI,
        sessionRepository: AgentSessionRepository = InMemoryAgentSessionRepository(),
        toolCatalog: AgentToolCatalog = BackendNoopAgentToolCatalog,
        toolsFilter: AgentToolsFilter = BackendNoopAgentToolsFilter,
    ): BackendAgentService {
        val settingsProvider = FakeSettingsProvider()
        return BackendAgentService(
            baseSettingsProvider = settingsProvider,
            runtimeFactory = BackendConversationRuntimeFactory(
                baseSettingsProvider = settingsProvider,
                llmApiFactory = { api },
                sessionRepository = sessionRepository,
                logObjectMapper = jacksonObjectMapper(),
                systemPrompt = "You are Souz AI backend assistant. Answer directly and concisely in the user's language.",
                toolCatalog = toolCatalog,
                toolsFilter = toolsFilter,
            ),
        )
    }
}

private fun toolCatalog(vararg tools: Pair<ToolCategory, LLMToolSetup>): AgentToolCatalog =
    object : AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
            tools.groupBy(keySelector = { it.first }, valueTransform = { it.second })
                .mapValues { (_, setups) -> setups.associateBy { it.fn.name } }
    }

private class RecordingAgentApi(
    private val onFinalChatRequest: (suspend (LLMRequest.Chat) -> Unit)? = null,
) : LLMChatAPI {
    val requests = ArrayList<LLMRequest.Chat>()
    private var completionCount = 0

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        if (body.isClassificationRequest()) {
            return LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "HELP 90",
                            role = LLMMessageRole.assistant,
                            functionCall = null,
                            functionsStateId = null,
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.stop,
                    )
                ),
                created = System.currentTimeMillis(),
                model = body.model,
                usage = LLMResponse.Usage(1, 1, 2, 0),
            )
        }

        onFinalChatRequest?.invoke(body)
        val prompt = body.conversationPrompt()
        completionCount += 1
        return LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = "agent reply to $prompt",
                        role = LLMMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = System.currentTimeMillis(),
            model = body.model,
            usage = LLMResponse.Usage(10 + completionCount, 5, 15 + completionCount, 0),
        )
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in backend agent tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in backend agent tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in backend agent tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in backend agent tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in backend agent tests.")
}

private class ToolCallingAgentApi : LLMChatAPI {
    val requests = ArrayList<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        return when {
            body.isClassificationRequest() -> reply("CALCULATOR 95")
            body.messages.any { it.role == LLMMessageRole.function && it.name == "Calculator" } ->
                reply("tool result: 5")

            body.functions.any { it.name == "Calculator" } ->
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = "",
                                role = LLMMessageRole.assistant,
                                functionCall = LLMResponse.FunctionCall(
                                    name = "Calculator",
                                    arguments = mapOf("expression" to "2 + 3"),
                                ),
                                functionsStateId = "call_1",
                            ),
                            index = 0,
                            finishReason = LLMResponse.FinishReason.function_call,
                        )
                    ),
                    created = System.currentTimeMillis(),
                    model = body.model,
                    usage = LLMResponse.Usage(4, 2, 6, 0),
                )

            else -> reply("unexpected")
        }
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in backend agent tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in backend agent tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in backend agent tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in backend agent tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in backend agent tests.")

    private fun reply(content: String): LLMResponse.Chat.Ok =
        LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = content,
                        role = LLMMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = System.currentTimeMillis(),
            model = LLMModel.Max.alias,
            usage = LLMResponse.Usage(4, 2, 6, 0),
        )
}

private class MultiToolCallingAgentApi : LLMChatAPI {
    val requests = ArrayList<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        return when {
            body.isClassificationRequest() -> reply("CALCULATOR 95")
            body.messages.count { it.role == LLMMessageRole.function && it.name == "Calculator" } >= 2 ->
                reply("tool results: 5, 6")

            body.functions.any { it.name == "Calculator" } ->
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        toolCallChoice(
                            index = 0,
                            stateId = "call_1",
                            expression = "2 + 3",
                        ),
                        toolCallChoice(
                            index = 1,
                            stateId = "call_2",
                            expression = "10 - 4",
                        ),
                    ),
                    created = System.currentTimeMillis(),
                    model = body.model,
                    usage = LLMResponse.Usage(4, 2, 6, 0),
                )

            else -> reply("unexpected")
        }
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in backend agent tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in backend agent tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in backend agent tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in backend agent tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in backend agent tests.")

    private fun toolCallChoice(
        index: Int,
        stateId: String,
        expression: String,
    ): LLMResponse.Choice =
        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = LLMResponse.FunctionCall(
                    name = "Calculator",
                    arguments = mapOf("expression" to expression),
                ),
                functionsStateId = stateId,
            ),
            index = index,
            finishReason = LLMResponse.FinishReason.function_call,
        )

    private fun reply(content: String): LLMResponse.Chat.Ok =
        LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = content,
                        role = LLMMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = System.currentTimeMillis(),
            model = LLMModel.Max.alias,
            usage = LLMResponse.Usage(4, 2, 6, 0),
        )
}

private class FailingToolAgentApi : LLMChatAPI {
    val requests = ArrayList<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        return when {
            body.isClassificationRequest() -> reply("CALCULATOR 95")
            body.messages.any { it.role == LLMMessageRole.function && it.name == "ExplodeTool" } ->
                reply("tool error handled")

            body.functions.any { it.name == "ExplodeTool" } ->
                LLMResponse.Chat.Ok(
                    choices = listOf(
                        LLMResponse.Choice(
                            message = LLMResponse.Message(
                                content = "",
                                role = LLMMessageRole.assistant,
                                functionCall = LLMResponse.FunctionCall(
                                    name = "ExplodeTool",
                                    arguments = mapOf("payload" to "boom"),
                                ),
                                functionsStateId = "explode_1",
                            ),
                            index = 0,
                            finishReason = LLMResponse.FinishReason.function_call,
                        )
                    ),
                    created = System.currentTimeMillis(),
                    model = body.model,
                    usage = LLMResponse.Usage(4, 2, 6, 0),
                )

            else -> reply("unexpected")
        }
    }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in backend agent tests.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in backend agent tests.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in backend agent tests.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in backend agent tests.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in backend agent tests.")

    private fun reply(content: String): LLMResponse.Chat.Ok =
        LLMResponse.Chat.Ok(
            choices = listOf(
                LLMResponse.Choice(
                    message = LLMResponse.Message(
                        content = content,
                        role = LLMMessageRole.assistant,
                        functionCall = null,
                        functionsStateId = null,
                    ),
                    index = 0,
                    finishReason = LLMResponse.FinishReason.stop,
                )
            ),
            created = System.currentTimeMillis(),
            model = LLMModel.Max.alias,
            usage = LLMResponse.Usage(4, 2, 6, 0),
        )
}

private class ThrowingTool : ToolSetup<ThrowingTool.Input> {
    data class Input(
        @InputParamDescription("Any payload that should trigger the tool")
        val payload: String,
    )

    override val name: String = "ExplodeTool"
    override val description: String = "Always fails so tests can verify tool error handling."
    override val fewShotExamples: List<FewShotExample> = emptyList()
    override val returnParameters: ReturnParameters = ReturnParameters(
        properties = mapOf("result" to ReturnProperty("string"))
    )

    override fun invoke(input: Input): String = error(input.payload)
}

private fun RecordingAgentApi.finalChatRequests(): List<LLMRequest.Chat> =
    requests.finalChatRequests()

private fun List<LLMRequest.Chat>.finalChatRequests(): List<LLMRequest.Chat> =
    filterNot { it.isClassificationRequest() }

private fun LLMRequest.Chat.conversationPrompt(): String =
    messages.lastOrNull { message ->
        message.role == LLMMessageRole.user && !message.content.contains("<context>")
    }?.content.orEmpty()

private fun historyContainsConversationTurn(historyTexts: List<String>, turn: String): Boolean =
    historyTexts.any { it == turn || it.endsWith("\n$turn") || it.contains("USER: $turn") }

private fun LLMRequest.Chat.isClassificationRequest(): Boolean =
    messages.any { message ->
        message.role == LLMMessageRole.system &&
            message.content.contains("Твоя задача — выбрать минимальный, но достаточный набор категорий")
    }

private class FakeSettingsProvider : SettingsProvider {
    private val promptOverrides = HashMap<Pair<AgentId, LLMModel>, String>()

    override var gigaChatKey: String? = null
    override var qwenChatKey: String? = null
    override var aiTunnelKey: String? = null
    override var anthropicKey: String? = null
    override var openaiKey: String? = null
    override var saluteSpeechKey: String? = null
    override var supportEmail: String? = null
    override var defaultCalendar: String? = null
    override var regionProfile: String = "ru"
    override var activeAgentId: AgentId = AgentId.default
    override var gigaModel: LLMModel = LLMModel.Max
    override var useFewShotExamples: Boolean = false
    override var useStreaming: Boolean = false
    override var notificationSoundEnabled: Boolean = true
    override var voiceInputReviewEnabled: Boolean = false
    override var safeModeEnabled: Boolean = true
    override var needsOnboarding: Boolean = false
    override var onboardingCompleted: Boolean = false
    override var requestTimeoutMillis: Long = 30_000
    override var contextSize: Int = 16_000
    override var initialWindowWidthDp: Int = 580
    override var initialWindowHeightDp: Int = 780
    override var temperature: Float = 0.7f
    override var forbiddenFolders: List<String> = emptyList()
    override var embeddingsModel: EmbeddingsModel = EmbeddingsModel.GigaEmbeddings
    override var voiceRecognitionModel: VoiceRecognitionModel = VoiceRecognitionModel.SaluteSpeech
    override var mcpServersJson: String? = null
    override var mcpServersFile: String? = null

    override fun getSystemPromptForAgentModel(agentId: AgentId, model: LLMModel): String? =
        promptOverrides[agentId to model]

    override fun setSystemPromptForAgentModel(agentId: AgentId, model: LLMModel, prompt: String?) {
        val key = agentId to model
        if (prompt.isNullOrBlank()) {
            promptOverrides.remove(key)
        } else {
            promptOverrides[key] = prompt
        }
    }
}

private class CountingAgentSessionRepository : AgentSessionRepository {
    private val delegate = InMemoryAgentSessionRepository()
    var loadCount: Int = 0
        private set

    override suspend fun load(key: AgentConversationKey): AgentConversationSession? {
        loadCount += 1
        return delegate.load(key)
    }

    override suspend fun save(key: AgentConversationKey, session: AgentConversationSession) {
        delegate.save(key, session)
    }
}

private fun agentRequest(
    requestId: String = uuid(),
    userId: String = uuid(),
    conversationId: String = uuid(),
    prompt: String = "Напиши короткое резюме проекта",
    model: String = LLMModel.Max.alias,
    contextSize: Int = 16_000,
    source: String = "web",
    locale: String = "ru-RU",
    timeZone: String = "Europe/Moscow",
): AgentRequest =
    AgentRequest(
        requestId = requestId,
        userId = userId,
        conversationId = conversationId,
        prompt = prompt,
        model = model,
        contextSize = contextSize,
        source = source,
        locale = locale,
        timeZone = timeZone,
    )

private fun uuid(): String = UUID.randomUUID().toString()
