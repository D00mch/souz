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
import ru.souz.db.SettingsProvider
import ru.souz.llms.EmbeddingsModel
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LlmProvider
import ru.souz.llms.VoiceRecognitionModel

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
    fun `concurrent active request for same conversation still returns 409`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = RecordingAgentApi(
            onFinalChatRequest = {
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

    private fun createService(api: RecordingAgentApi): BackendAgentService =
        BackendAgentService(
            baseSettingsProvider = FakeSettingsProvider(),
            llmApiFactory = { api },
            sessionRepository = InMemoryAgentSessionRepository(),
            logObjectMapper = jacksonObjectMapper(),
        )
}

private class RecordingAgentApi(
    private val onFinalChatRequest: (suspend () -> Unit)? = null,
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

        onFinalChatRequest?.invoke()
        val prompt = body.messages.lastOrNull { it.role == LLMMessageRole.user }?.content.orEmpty()
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

private fun RecordingAgentApi.finalChatRequests(): List<LLMRequest.Chat> =
    requests.filterNot { it.isClassificationRequest() }

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
