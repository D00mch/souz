package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.TestConversationKnowledgeStore
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.testBackendClientSkills
import ru.souz.backend.testCoreTool
import ru.souz.backend.testRunSkillCommandTool
import ru.souz.backend.testSearchMemoryTool
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentConversationSession
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
    fun `runtime resolves skills graph with only its core tools`() = runTest {
        val api = ReplyingChatApi()
        val settings = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            activeAgentId = AgentId.GRAPH
        }
        val runtimeFactory = runtimeFactory(
            settingsProvider = settings,
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            configuredAgentId = AgentId.SKILLS_GRAPH,
        )
        val request = turnRequest()

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(
            listOf(
                "GetSkillByName",
                "GetSkillsByCategory",
                "GetSkillsNamesByCategory",
                "GetKnowledge",
                "SearchKnowledge",
                "SearchMemory",
                "RunSkillCommand",
            ),
            api.finalRequests.single().functions.map { it.name },
        )
    }

    @Test
    fun `new runtime uses configured graph instead of shared jvm preference`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val settings = TestSettingsProvider().apply {
            gigaChatKey = "giga-key"
            activeAgentId = AgentId.SKILLS_GRAPH
        }
        val runtimeFactory = runtimeFactory(
            settingsProvider = settings,
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            configuredAgentId = AgentId.GRAPH,
        )
        val request = turnRequest()

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(listOf("ListFiles") + CLASSIC_SKILL_CORE_TOOLS, api.finalRequests.single().functions.map { it.name })
    }

    @Test
    fun `persisted conversation agent takes precedence over backend configuration`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val sessionRepository = InMemoryAgentSessionRepository()
        val key = conversationKey()
        sessionRepository.save(
            key,
            AgentConversationSession(
                activeAgentId = AgentId.GRAPH,
                history = emptyList(),
                temperature = 0.4f,
                locale = "en-US",
                timeZone = "UTC",
            )
        )
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            configuredAgentId = AgentId.SKILLS_GRAPH,
            sessionRepository = sessionRepository,
        )
        val request = turnRequest()

        runtimeFactory.create(key, request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(listOf("ListFiles") + CLASSIC_SKILL_CORE_TOOLS, api.finalRequests.single().functions.map { it.name })
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

        assertEquals(
            emptyList(),
            api.finalRequests.single().functions.single { it.name == "ListFiles" }.fewShotExamples.orEmpty(),
        )
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
            api.finalRequests.single().functions.single { it.name == "ListFiles" }.fewShotExamples.orEmpty(),
        )
    }

    @Test
    fun `runtime applies enabled tool snapshot to compiled tools`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
        )
        val enabledTools = linkedSetOf<String>()
        val request = turnRequest().copy(enabledTools = enabledTools)
        val runtime = runtimeFactory.create(conversationKey(), request)
        enabledTools += "ListFiles"

        runtime.execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(CLASSIC_SKILL_CORE_TOOLS, api.finalRequests.single().functions.map { it.name })
    }

    @Test
    fun `request tool catalog captures an immutable enabled tool snapshot`() {
        val sourceCatalog = singleToolCatalog(
            category = ToolCategory.FILES,
            tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
        )
        val enabledTools = linkedSetOf("ListFiles")
        val requestCatalog = BackendRequestToolCatalog(
            delegate = sourceCatalog,
            toolsFilter = BackendRequestToolsFilter(enabledTools),
        )

        enabledTools.clear()

        assertEquals(
            setOf("ListFiles"),
            requestCatalog.toolsByCategory.values.flatMap { it.keys }.toSet(),
        )
    }

    @Test
    fun `enabled client Skills appear as file backed inventory without direct schemas`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "CHAT 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            clientSkills = testBackendClientSkills(),
        )
        val request = turnRequest().copy(
            clientToolsEnabled = true,
            enabledTools = emptySet(),
        )

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val finalRequest = api.finalRequests.single()
        val inventory = finalRequest.skillInventoryBlock()
        val toolBacked = inventory.toolBackedSection()
        val fileBacked = inventory.fileBackedSection()
        assertContains(fileBacked, """- skillId: "device.media.open"""")
        assertContains(fileBacked, """- skillId: "user.ask"""")
        assertFalse(toolBacked.contains("device.media.open"))
        assertFalse(toolBacked.contains("user.ask"))
        assertFalse(finalRequest.functions.any { it.name in CLIENT_SKILL_IDS })
    }

    @Test
    fun `client Skill IDs cannot collide with direct tools`() = runTest {
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { ReplyingChatApi(classificationResponse = "CHAT 100") },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.CHAT,
                tool = fakeTool(name = "user.ask", fewShotExamples = emptyList()),
            ),
            clientSkills = testBackendClientSkills(),
        )

        val thrown = assertFailsWith<IllegalArgumentException> {
            runtimeFactory.create(
                conversationKey(),
                turnRequest().copy(clientToolsEnabled = true),
            )
        }

        assertContains(thrown.message.orEmpty(), "user.ask")
    }

    @Test
    fun `disabled client Skills are absent from inventory and direct schemas`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "CHAT 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            clientSkills = testBackendClientSkills(),
        )
        val request = turnRequest().copy(clientToolsEnabled = false)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val finalRequest = api.finalRequests.single()
        val inventory = finalRequest.skillInventoryBlock()
        CLIENT_SKILL_IDS.forEach { skillId ->
            assertFalse(inventory.contains(skillId))
        }
        assertFalse(finalRequest.functions.any { it.name in CLIENT_SKILL_IDS })
    }
}

private fun runtimeFactory(
    settingsProvider: TestSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
    llmApiFactory: suspend (ru.souz.backend.llm.BackendLlmExecutionContext) -> LLMChatAPI,
    toolCatalog: ru.souz.agent.spi.AgentToolCatalog = BackendNoopAgentToolCatalog,
    clientSkills: BackendClientSkills? = null,
    configuredAgentId: AgentId = AgentId.GRAPH,
    sessionRepository: InMemoryAgentSessionRepository = InMemoryAgentSessionRepository(),
): BackendConversationRuntimeFactory =
    BackendConversationRuntimeFactory(
        baseSettingsProvider = settingsProvider,
        llmApiFactory = llmApiFactory,
        sessionRepository = sessionRepository,
        logObjectMapper = jacksonObjectMapper(),
        systemPrompt = "backend test prompt",
        configuredAgentId = configuredAgentId,
        toolCatalog = toolCatalog,
        clientSkills = clientSkills,
        legacySkillCommandTool = testCoreTool("RunSkillCommand"),
        runSkillCommandTool = testRunSkillCommandTool(),
        getKnowledgeTool = testCoreTool("GetKnowledge"),
        searchKnowledgeTool = testCoreTool("SearchKnowledge"),
        searchMemoryTool = testSearchMemoryTool(),
        knowledgeStore = TestConversationKnowledgeStore,
        agentBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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

private val CLASSIC_SKILL_CORE_TOOLS = listOf(
    "GetSkillByName",
    "GetKnowledge",
    "SearchKnowledge",
    "SearchMemory",
    "RunSkillCommand",
)

private val CLIENT_SKILL_IDS = setOf("device.media.open", "user.ask")

private fun LLMRequest.Chat.skillInventoryBlock(): String {
    val systemMessage = messages.first { it.role == LLMMessageRole.system }.content
    val start = systemMessage.indexOf("<skill_inventory>")
    val end = systemMessage.indexOf("</skill_inventory>", start)
    check(start >= 0 && end >= start) { "Missing skill inventory block." }
    return systemMessage.substring(start, end + "</skill_inventory>".length)
}

private fun String.toolBackedSection(): String =
    substringAfter("Tool-backed Skills by category:\n")
        .substringBefore("File-backed Skills (opaque skillId values only):")

private fun String.fileBackedSection(): String =
    substringAfter("File-backed Skills (opaque skillId values only):")
        .substringBefore("</skill_inventory>")

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
