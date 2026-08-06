package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.skills.activation.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillBundleHasher
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.agent.skills.bundle.SkillManifest
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.agent.skills.registry.StoredSkill
import ru.souz.agent.skills.validation.SkillValidationRecord
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.TestConversationKnowledgeStore
import ru.souz.backend.client.BackendClientSkills
import ru.souz.backend.testCoreTool
import ru.souz.backend.testSearchMemoryTool
import ru.souz.backend.testSkillCoreTools
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.http.routeTestContext
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.session.AgentConversationSession
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.restJsonMapper
import ru.souz.runtime.sandbox.RuntimeSandbox
import ru.souz.runtime.sandbox.SandboxCommandExecutor
import ru.souz.runtime.sandbox.SandboxCommandRequest
import ru.souz.runtime.sandbox.SandboxCommandResult
import ru.souz.runtime.sandbox.SandboxMode
import ru.souz.runtime.sandbox.SandboxRuntimePaths
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.ToolRunSkillCommand

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
    fun `runtime includes enabled client skills in file-backed inventory`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            clientSkills = testClientSkills(),
        )
        val request = turnRequest().copy(clientToolsEnabled = true)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val finalRequest = api.finalRequests.single()
        val inventory = finalRequest.skillInventoryBlock()
        val toolBackedSection = inventory.toolBackedSkillSection()

        assertContains(inventory, "File-backed Skills (opaque skillId values only):")
        assertContains(inventory, "- skillId: \"device.media.open\"")
        assertContains(inventory, "- skillId: \"user.ask\"")
        assertFalse(toolBackedSection.contains("user.ask"))
        assertFalse(toolBackedSection.contains("device.media.open"))
        assertFalse(finalRequest.functions.any { it.name == "user.ask" || it.name == "device.media.open" })
    }

    @Test
    fun `runtime excludes client skills when they are disabled`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            clientSkills = testClientSkills(),
        )
        val request = turnRequest().copy(clientToolsEnabled = false)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val inventory = api.finalRequests.single().skillInventoryBlock()

        assertFalse(inventory.contains("- skillId: \"device.media.open\""))
        assertFalse(inventory.contains("- skillId: \"user.ask\""))
        assertFalse(api.finalRequests.single().functions.any { it.name == "user.ask" || it.name == "device.media.open" })
    }

    @Test
    fun `runtime resolves colliding client and user IDs through catalog fallback`() = runTest {
        val api = ScriptedReplyingChatApi(
            responses = listOf(
                functionResponse(
                    LLMResponse.FunctionCall(
                        name = "GetSkillByName",
                        arguments = mapOf("skillId" to "user.ask"),
                    ),
                ),
                functionResponse(
                    LLMResponse.FunctionCall(
                        name = "RunSkillCommand",
                        arguments = mapOf("skillId" to "user.ask"),
                    ),
                ),
                assistantResponse("assistant final"),
            ),
        )
        val commandInvocations = IntArray(1)
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            clientSkills = testClientSkills(),
            skillRegistryRepository = SingleBundleRepository(
                userSkillBundle(
                    skillId = "user.ask",
                    description = "USER-INSTALLED-OVERRIDE",
                    skillMarkdown = "USER-INSTALLED-OVERRIDE",
                ),
            ),
            runSkillCommandTool = countingRunSkillCommandTool(commandInvocations),
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
        )
        val request = turnRequest().copy(clientToolsEnabled = true)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val inventory = api.finalRequests.first().skillInventoryBlock()
        val getSkillResult = assertNotNull(api.finalRequests.getOrNull(1)?.toolMessages("GetSkillByName")?.singleOrNull())
        val runSkillResult = assertNotNull(api.finalRequests.getOrNull(2)?.toolMessages("RunSkillCommand")?.singleOrNull())

        assertEquals(1, inventory.countOccurrences("""- skillId: "user.ask"""))
        assertContains(getSkillResult.contentJson()["skill"]["description"].asText(), "Ask the user a concise clarification question")
        assertFalse(getSkillResult.contentJson()["skill"]["description"].asText().contains("USER-INSTALLED-OVERRIDE"))
        assertEquals("client_context_missing", runSkillResult.contentJson()["error"]["code"].asText())
        assertEquals(0, commandInvocations[0])
    }

    @Test
    fun `runtime disabled client tools never reach command runtime for RunSkillCommand`() = runTest {
        val api = ScriptedReplyingChatApi(
            responses = listOf(
                functionResponse(
                    LLMResponse.FunctionCall(
                        name = "RunSkillCommand",
                        arguments = mapOf("skillId" to "user.ask"),
                    ),
                ),
                assistantResponse("assistant final"),
            ),
        )
        val commandInvocations = IntArray(1)
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            clientSkills = testClientSkills(),
            runSkillCommandTool = countingRunSkillCommandTool(commandInvocations),
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
        )
        val request = turnRequest().copy(clientToolsEnabled = false)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val inventory = api.finalRequests.first().skillInventoryBlock()
        val runSkillResult = assertNotNull(api.finalRequests.getOrNull(1)?.toolMessages("RunSkillCommand")?.singleOrNull())

        assertFalse(inventory.contains("- skillId: \"user.ask\""))
        assertFalse(inventory.contains("- skillId: \"device.media.open\""))
        assertEquals("skill_not_found", runSkillResult.contentJson()["error"]["code"].asText())
        assertEquals(0, commandInvocations[0])
    }

    @Test
    fun `runtime keeps client skills with an explicit empty enabled tool snapshot`() = runTest {
        val api = ReplyingChatApi(classificationResponse = "FILES 100")
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            clientSkills = testClientSkills(),
        )
        val enabledTools = linkedSetOf<String>()
        val request = turnRequest().copy(clientToolsEnabled = true, enabledTools = enabledTools)

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val inventory = api.finalRequests.single().skillInventoryBlock()

        assertContains(inventory, "- skillId: \"device.media.open\"")
        assertContains(inventory, "- skillId: \"user.ask\"")
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
}

private fun runtimeFactory(
    settingsProvider: TestSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
    llmApiFactory: suspend (ru.souz.backend.llm.BackendLlmExecutionContext) -> LLMChatAPI,
    toolCatalog: ru.souz.agent.spi.AgentToolCatalog = BackendNoopAgentToolCatalog,
    clientSkills: BackendClientSkills? = null,
    configuredAgentId: AgentId = AgentId.GRAPH,
    sessionRepository: InMemoryAgentSessionRepository = InMemoryAgentSessionRepository(),
    skillRegistryRepository: SkillRegistryRepository = BackendNoopSkillRegistryRepository,
    legacySkillCommandTool: LLMToolSetup = testCoreTool("RunSkillCommand"),
    runSkillCommandTool: ToolRunSkillCommand = testSkillCoreTools().runSkillCommandTool,
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
        skillRegistryRepository = skillRegistryRepository,
        legacySkillCommandTool = legacySkillCommandTool,
        runSkillCommandTool = runSkillCommandTool,
        getKnowledgeTool = testCoreTool("GetKnowledge"),
        searchKnowledgeTool = testCoreTool("SearchKnowledge"),
        searchMemoryTool = testSearchMemoryTool(),
        knowledgeStore = TestConversationKnowledgeStore,
        agentBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

private fun testClientSkills(): BackendClientSkills {
    val context = routeTestContext()
    return BackendClientSkills(
        registry = context.clientThreadRegistry,
        toolCallRepository = context.toolCallRepository,
        eventService = context.eventService,
    )
}

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

private const val USER_ID = "backend-user"

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

private class ScriptedReplyingChatApi(
    private val responses: List<LLMResponse.Chat> = emptyList(),
    private val classificationResponse: String = "FILES 100",
) : LLMChatAPI {
    val finalRequests = mutableListOf<LLMRequest.Chat>()
    private var responseIndex = 0

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        if (!body.isClassificationRequest()) {
            finalRequests += body
        }
        return if (body.isClassificationRequest()) {
            reply(body, classificationResponse)
        } else {
            responses.getOrNull(responseIndex++) ?: reply(body, "assistant reply")
        }
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

private fun functionResponse(functionCall: LLMResponse.FunctionCall): LLMResponse.Chat.Ok =
    chatResponse(content = "", functionCall = functionCall)

private fun assistantResponse(content: String): LLMResponse.Chat.Ok =
    chatResponse(content = content)

private fun chatResponse(
    content: String,
    functionCall: LLMResponse.FunctionCall? = null,
): LLMResponse.Chat.Ok =
    LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = LLMMessageRole.assistant,
                    functionCall = functionCall,
                    functionsStateId = null,
                ),
                index = 0,
                finishReason = if (functionCall == null) LLMResponse.FinishReason.stop else LLMResponse.FinishReason.function_call,
            )
        ),
        created = System.currentTimeMillis(),
        model = "test-model",
        usage = LLMResponse.Usage(7, 3, 10, 0),
    )

private class ReplyingChatApi(
    classificationResponse: String = "HELP 90",
) : LLMChatAPI {
    private val delegate = ScriptedReplyingChatApi(classificationResponse = classificationResponse)
    val finalRequests get() = delegate.finalRequests

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat = delegate.message(body)

    override suspend fun messageStream(body: LLMRequest.Chat): Flow<LLMResponse.Chat> =
        delegate.messageStream(body)

    override suspend fun embeddings(body: LLMRequest.Embeddings): LLMResponse.Embeddings =
        delegate.embeddings(body)

    override suspend fun uploadFile(file: File): LLMResponse.UploadFile =
        delegate.uploadFile(file)

    override suspend fun downloadFile(fileId: String): String? =
        delegate.downloadFile(fileId)

    override suspend fun balance(): LLMResponse.Balance =
        delegate.balance()
}

private fun LLMRequest.Chat.isClassificationRequest(): Boolean =
    messages.any { message ->
        message.role == LLMMessageRole.system &&
            message.content.contains("Твоя задача — выбрать минимальный, но достаточный набор категорий")
    }

private fun LLMRequest.Chat.skillInventoryBlock(): String = messages
    .firstOrNull { it.role == LLMMessageRole.system }
    ?.content
    ?.substringAfter("<skill_inventory>", "")
    ?.substringBefore("</skill_inventory>", "")
    ?.trim()
    ?.replace("<skill_inventory>", "")
    ?: ""

private fun String.toolBackedSkillSection(): String = substringAfter("Tool-backed Skills by category:\n", "")
    .substringBefore("File-backed Skills (opaque skillId values only):", "")

private fun countingRunSkillCommandTool(commandInvocations: IntArray): ToolRunSkillCommand {
    val base = LocalRuntimeSandbox(
        scope = SandboxScope(userId = USER_ID),
        settingsProvider = TestSettingsProvider(),
    )
    val sandbox: RuntimeSandbox = object : RuntimeSandbox {
        override val mode: SandboxMode = base.mode
        override val scope: SandboxScope = base.scope
        override val runtimePaths: SandboxRuntimePaths = base.runtimePaths
        override val fileSystem: ru.souz.runtime.sandbox.SandboxFileSystem = base.fileSystem
        override val commandExecutor: SandboxCommandExecutor =
            object : SandboxCommandExecutor {
                override suspend fun execute(request: SandboxCommandRequest): SandboxCommandResult {
                    commandInvocations[0]++
                    return SandboxCommandResult(exitCode = 0, stdout = "", stderr = "")
                }
            }
    }
    return ToolRunSkillCommand(ToolInvocationRuntimeSandboxResolver.fixed(sandbox))
}

private fun userSkillBundle(
    skillId: String,
    description: String,
    skillMarkdown: String,
): SkillBundle = SkillBundle(
    skillId = SkillId(skillId),
    manifest = SkillManifest(
        name = "User skill",
        description = description,
        rawFrontmatter = "name: User skill",
    ),
    files = listOf(SkillFile("SKILL.md", skillMarkdown.toByteArray())),
    skillMarkdownBody = skillMarkdown,
)

private class SingleBundleRepository(
    private val bundle: SkillBundle,
) : SkillRegistryRepository {
    private val stored = StoredSkill(
        userId = USER_ID,
        skillId = bundle.skillId,
        manifest = bundle.manifest,
        bundleHash = SkillBundleHasher.hash(bundle),
        createdAt = Instant.EPOCH,
    )

    override suspend fun listSkills(userId: String): List<StoredSkill> = listOf(stored)

    override suspend fun getSkill(userId: String, skillId: SkillId): StoredSkill? =
        stored.takeIf { it.skillId == skillId }

    override suspend fun getSkillByName(userId: String, name: String): StoredSkill? =
        stored.takeIf { it.manifest.name == name }

    override suspend fun saveSkillBundle(userId: String, bundle: SkillBundle): StoredSkill = stored

    override suspend fun loadSkillBundle(userId: String, skillId: SkillId): SkillBundle? =
        bundle.takeIf { it.skillId == skillId }

    override suspend fun getValidation(
        userId: String,
        skillId: SkillId,
        bundleHash: String,
        policyVersion: String,
    ): SkillValidationRecord? = null

    override suspend fun saveValidation(record: SkillValidationRecord) = Unit
}

private fun String.countOccurrences(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = 0
    while (index <= this.length - needle.length) {
        val next = indexOf(needle, startIndex = index)
        if (next < 0) return count
        count++
        index = next + needle.length
    }
    return count
}

private fun LLMRequest.Chat.toolMessages(functionName: String): List<LLMResponse.Message> =
    messages.filter { it.role == LLMMessageRole.function && it.name == functionName }

private fun LLMRequest.Message.contentJson() = restJsonMapper.readTree(content)

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
