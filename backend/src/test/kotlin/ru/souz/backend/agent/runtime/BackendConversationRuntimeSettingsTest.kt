package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.agent.skills.validation.SkillValidationStatus
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory

class BackendConversationRuntimeSettingsTest {
    @Test
    fun `runtime factory keeps compatibility with skill registry constructor argument`() = runTest {
        val capturedTimeouts = mutableListOf<Long>()
        val runtimeFactory = BackendConversationRuntimeFactory(
            baseSettingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                requestTimeoutMillis = 30_000L
            },
            llmApiFactory = { context ->
                capturedTimeouts += context.settingsProvider.requestTimeoutMillis
                ReplyingChatApi()
            },
            sessionRepository = InMemoryAgentSessionRepository(),
            logObjectMapper = jacksonObjectMapper(),
            systemPrompt = "backend test prompt",
            toolCatalog = BackendNoopAgentToolCatalog,
            skillRegistryRepository = UnusedSkillRegistryRepository,
            captureScope = backgroundScope,
        )
        val request = turnRequest().copy(requestTimeoutMillis = 45_000L)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(listOf(45_000L), capturedTimeouts)
    }

    @Test
    fun `runtime factory applies request timeout to request scoped llm settings provider`() = runTest {
        val capturedTimeouts = mutableListOf<Long>()
        val runtimeFactory = runtimeFactory(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                requestTimeoutMillis = 30_000L
            },
            llmApiFactory = { context ->
                capturedTimeouts += context.settingsProvider.requestTimeoutMillis
                ReplyingChatApi()
            },
        )
        val request = turnRequest().copy(requestTimeoutMillis = 45_000L)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(listOf(45_000L), capturedTimeouts)
    }

    @Test
    fun `runtime strips few shot examples when disabled`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(
                    name = "ListFiles",
                    fewShotExamples = listOf(
                        LLMRequest.FewShotExample(
                            request = "List project files",
                            params = mapOf("path" to "."),
                        )
                    ),
                ),
            ),
        )
        val request = turnRequest().copy(useFewShotExamples = false)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(emptyList(), api.finalRequests.single().functions.single().fewShotExamples.orEmpty())
    }

    @Test
    fun `runtime keeps few shot examples when enabled`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(
                    name = "ListFiles",
                    fewShotExamples = listOf(
                        LLMRequest.FewShotExample(
                            request = "List project files",
                            params = mapOf("path" to "."),
                        )
                    ),
                ),
            ),
        )
        val request = turnRequest().copy(useFewShotExamples = true)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(
            listOf(LLMRequest.FewShotExample(request = "List project files", params = mapOf("path" to "."))),
            api.finalRequests.single().functions.single().fewShotExamples.orEmpty(),
        )
    }
}

private fun runtimeFactory(
    settingsProvider: TestSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
    llmApiFactory: suspend (ru.souz.backend.llm.BackendLlmExecutionContext) -> LLMChatAPI,
    toolCatalog: ru.souz.agent.spi.AgentToolCatalog = BackendNoopAgentToolCatalog,
): BackendConversationRuntimeFactory =
    BackendConversationRuntimeFactory(
        baseSettingsProvider = settingsProvider,
        llmApiFactory = llmApiFactory,
        sessionRepository = InMemoryAgentSessionRepository(),
        logObjectMapper = jacksonObjectMapper(),
        systemPrompt = "backend test prompt",
        toolCatalog = toolCatalog,
        captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

private fun conversationKey(): AgentConversationKey =
    AgentConversationKey(
        userId = "user-a",
        conversationId = UUID.randomUUID().toString(),
    )

private fun turnRequest(): BackendConversationTurnRequest =
    BackendConversationTurnRequest(
        prompt = "List files in the project root.",
        model = LLMModel.Max.alias,
        contextSize = 24_000,
        locale = "ru-RU",
        timeZone = "Europe/Moscow",
        executionId = UUID.randomUUID().toString(),
        temperature = 0.6f,
        systemPrompt = "backend test prompt",
        streamingMessages = false,
        requestTimeoutMillis = 30_000L,
        useFewShotExamples = true,
    )

private fun singleToolCatalog(
    category: ToolCategory,
    tool: LLMToolSetup,
): ru.souz.agent.spi.AgentToolCatalog =
    object : ru.souz.agent.spi.AgentToolCatalog {
        override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
            mapOf(category to mapOf(tool.fn.name to tool))
    }

private fun fakeTool(
    name: String,
    fewShotExamples: List<LLMRequest.FewShotExample>,
): LLMToolSetup =
    object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = "test",
            parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            fewShotExamples = fewShotExamples,
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
            error("not used in tests")
    }

private class ReplyingChatApi(
    private val classificationResponse: String = "HELP 90",
) : LLMChatAPI {
    val finalRequests = mutableListOf<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat =
        if (body.isClassificationRequest()) {
            reply(body, classificationResponse)
        } else {
            finalRequests += body
            reply(body, "assistant reply")
        }

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        error("Streaming is not used in this test.")

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        error("Embeddings are not used in this test.")

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        error("File upload is not used in this test.")

    override suspend fun downloadFile(fileId: String): String? =
        error("File download is not used in this test.")

    override suspend fun balance(): LLMResponse.Balance =
        error("Balance is not used in this test.")
}

private fun LLMRequest.Chat.isClassificationRequest(): Boolean =
    messages.any { message ->
        message.role == LLMMessageRole.system &&
            message.content.contains("Твоя задача — выбрать минимальный, но достаточный набор категорий")
    }

private fun reply(body: LLMRequest.Chat, content: String): LLMResponse.Chat.Ok =
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
        model = body.model,
        usage = LLMResponse.Usage(7, 3, 10, 0),
    )

private object UnusedSkillRegistryRepository : SkillRegistryRepository {
    override suspend fun listSkills(userId: String): List<StoredSkill> = emptyList()

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? = null

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? = null

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill =
        error("Not used in backend runtime settings tests.")

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? = null

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit

    override suspend fun markValidationStatus(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
        status: SkillValidationStatus,
        reason: String?,
    ) = Unit

    override suspend fun invalidateOtherValidations(
        userId: String,
        skillId: SkillId,
        activeBundleHash: String,
        policyVersion: String,
        reason: String?,
    ) = Unit
}
