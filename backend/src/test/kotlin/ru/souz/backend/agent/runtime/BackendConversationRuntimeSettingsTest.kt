package ru.souz.backend.agent.runtime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.skills.SkillId
import ru.souz.agent.skills.bundle.SkillBundle
import ru.souz.agent.skills.bundle.SkillFile
import ru.souz.backend.TestSettingsProvider
import ru.souz.backend.agent.model.AgentConversationKey
import ru.souz.backend.agent.model.BackendConversationTurnRequest
import ru.souz.backend.agent.runtime.conversation.BackendConversationRuntimeFactory
import ru.souz.backend.agent.runtime.conversation.BackendExecutionToolCatalog
import ru.souz.backend.agent.runtime.conversation.testBackendConversationRuntimeFactory
import ru.souz.backend.agent.session.InMemoryAgentSessionRepository
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.runtime.sandbox.SandboxScope
import ru.souz.runtime.sandbox.ToolInvocationRuntimeSandboxResolver
import ru.souz.runtime.sandbox.local.LocalRuntimeSandbox
import ru.souz.skills.registry.FileSystemSkillRegistryRepository
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.SkillCommandExecutor

class BackendConversationRuntimeSettingsTest {
    @Test
    fun `runtime factory applies request timeout to request scoped llm settings provider`() = runTest {
        val capturedTimeouts = mutableListOf<Long>()
        val runtimeFactory = runtimeFactory(
            settingsProvider = TestSettingsProvider().apply {
                gigaChatKey = "giga-key"
                requestTimeoutMillis = 30_000L
            },
            llmApiFactory = { settingsProvider ->
                capturedTimeouts += settingsProvider.requestTimeoutMillis
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
    fun `execution catalog follows the few shot setting`() {
        val examples = listOf(LLMRequest.FewShotExample("List project files", mapOf("path" to ".")))
        val compiledTools = singleToolCatalog(
            ToolCategory.FILES,
            fakeTool("ListFiles", examples),
        )
        val withoutExamples = BackendExecutionToolCatalog(
            compiledToolCatalog = compiledTools,
            executionLlmToolCatalog = BackendNoopAgentToolCatalog,
            enabledCompiledToolNames = null,
            clientToolCatalog = BackendNoopAgentToolCatalog,
            includeFewShotExamples = false,
        )
        val withExamples = BackendExecutionToolCatalog(
            compiledToolCatalog = compiledTools,
            executionLlmToolCatalog = BackendNoopAgentToolCatalog,
            enabledCompiledToolNames = null,
            clientToolCatalog = BackendNoopAgentToolCatalog,
            includeFewShotExamples = true,
        )

        assertEquals(
            emptyList(),
            withoutExamples.toolsByCategory.getValue(ToolCategory.FILES)
                .getValue("ListFiles").fn.fewShotExamples.orEmpty(),
        )
        assertEquals(
            examples,
            withExamples.toolsByCategory.getValue(ToolCategory.FILES)
                .getValue("ListFiles").fn.fewShotExamples.orEmpty(),
        )
    }

    @Test
    fun `client tools remain available outside the compiled tool snapshot and selected category`() = runTest {
        val api = SkillLoopChatApi("user.ask")
        val clientTool = CapturingTool("user.ask")
        val clientTools = TestClientToolCatalog(toolOverrides = mapOf("user.ask" to clientTool))
        val key = conversationKey()
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(
                category = ToolCategory.FILES,
                tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
            ),
            clientToolCatalog = clientTools,
        )
        val request = turnRequest().copy(
            enabledTools = emptySet(),
            clientToolsEnabled = true,
        )

        runtimeFactory.create(key, request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(3, api.requests.size)
        assertTrue(api.requests.all { chat -> chat.functions.map { it.name }.toSet() == SKILL_CORE_TOOL_NAMES })
        assertTrue(api.requests.first().systemMessage().contains("user.ask"))
        assertTrue(api.requests.first().systemMessage().contains("device.media.open"))
        assertFalse(api.requests.first().systemMessage().contains("ListFiles"))
        assertEquals(mapOf("value" to "delegated"), clientTool.arguments)
        assertEquals(
            ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            ),
            clientTool.meta,
        )
    }

    @Test
    fun `proxy runtime does not create or advertise client tools`() = runTest {
        val api = ReplyingChatApi()
        val clientTools = TestClientToolCatalog()
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            clientToolCatalog = clientTools,
        )
        val request = turnRequest().copy(
            enabledTools = emptySet(),
            clientToolsEnabled = false,
        )

        runtimeFactory.create(conversationKey(), request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        val llmRequest = api.requests.single()
        assertFalse(llmRequest.systemMessage().contains("user.ask"))
        assertFalse(llmRequest.systemMessage().contains("device.media.open"))
    }

    @Test
    fun `runtime discovers and delegates a compiled Skill with request metadata`() = runTest {
        val api = SkillLoopChatApi("capture.skill")
        val captureTool = CapturingTool("capture.skill")
        val key = conversationKey()
        val request = turnRequest().copy(enabledTools = setOf("capture.skill"))
        val runtimeFactory = runtimeFactory(
            llmApiFactory = { api },
            toolCatalog = singleToolCatalog(ToolCategory.HELP, captureTool),
        )

        runtimeFactory.create(key, request).execute(
            request = request,
            persistSession = false,
            eventSink = AgentRuntimeEventSink.NONE,
        )

        assertEquals(3, api.requests.size)
        assertTrue(api.requests.first().systemMessage().contains("capture.skill"))
        assertEquals(mapOf("value" to "delegated"), captureTool.arguments)
        assertEquals(
            ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            ),
            captureTool.meta,
        )
    }

    @Test
    fun `runtime discovers and invokes a sandbox Skill with full request metadata and no validation call`() = runTest {
        val home = Files.createTempDirectory("backend-sandbox-skill-")
        try {
            val stateRoot = Files.createDirectories(home.resolve("state"))
            val key = conversationKey()
            val request = turnRequest()
            val skillId = SkillId("sandbox.echo")
            val sandbox = LocalRuntimeSandbox(
                scope = SandboxScope(userId = key.userId),
                settingsProvider = TestSettingsProvider(),
                homePath = home,
                stateRoot = stateRoot,
                workspaceRoot = home,
            )
            val commandInvocationMeta = mutableListOf<ToolInvocationMeta>()
            val commandExecutor = SkillCommandExecutor(
                sandboxResolver = ToolInvocationRuntimeSandboxResolver { meta ->
                    commandInvocationMeta += meta
                    sandbox
                }
            )
            val skillRegistry = FileSystemSkillRegistryRepository(sandbox)
            skillRegistry.saveSkillBundle(
                userId = key.userId,
                bundle = sandboxEchoSkillBundle(skillId),
            )
            val api = SkillLoopChatApi(
                skillId = skillId.value,
                invocationArguments = mapOf(
                    "runtime" to "BASH",
                    "scriptPath" to "scripts/echo.sh",
                    "args" to listOf("delegated"),
                    "stdin" to "request-input",
                    "timeoutMillis" to 1_000,
                ),
            )
            val runtimeFactory = testBackendConversationRuntimeFactory(
                baseSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
                llmApiFactory = { api },
                sessionRepository = InMemoryAgentSessionRepository(),
                logObjectMapper = jacksonObjectMapper(),
                systemPrompt = "backend test prompt",
                skillBundleProvider = skillRegistry,
                commandExecutor = commandExecutor,
                agentBackgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            val result = runtimeFactory.create(key, request).execute(
                request = request,
                persistSession = false,
                eventSink = AgentRuntimeEventSink.NONE,
            )

            val expectedMeta = ToolInvocationMeta(
                userId = key.userId,
                conversationId = key.conversationId,
                requestId = request.executionId,
                locale = request.locale,
                timeZone = request.timeZone,
            )
            assertEquals("done", result.output)
            assertEquals(3, api.requests.size)
            assertTrue(api.requests.first().systemMessage().contains("- skillId: \"${skillId.value}\""))
            assertTrue(api.requests[1].functionResult("GetSkillByName").contains("Run the bundled echo script"))
            assertTrue(api.requests[2].functionResult("RunSkillCommand").contains("sandbox.echo:delegated:request-input"))
            assertEquals(listOf(expectedMeta), commandInvocationMeta)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun `execution tool catalog captures an immutable enabled tool snapshot`() {
        val sourceCatalog = singleToolCatalog(
            category = ToolCategory.FILES,
            tool = fakeTool(name = "ListFiles", fewShotExamples = emptyList()),
        )
        val enabledTools = linkedSetOf("ListFiles")
        val executionCatalog = BackendExecutionToolCatalog(
            compiledToolCatalog = sourceCatalog,
            executionLlmToolCatalog = BackendNoopAgentToolCatalog,
            enabledCompiledToolNames = enabledTools,
            clientToolCatalog = BackendNoopAgentToolCatalog,
            includeFewShotExamples = true,
        )

        enabledTools.clear()

        assertEquals(
            setOf("ListFiles"),
            executionCatalog.toolsByCategory.values.flatMap { it.keys }.toSet(),
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (executionCatalog.toolsByCategory as MutableMap<ToolCategory, Map<String, LLMToolSetup>>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (executionCatalog.toolsByCategory.getValue(ToolCategory.FILES) as MutableMap<String, LLMToolSetup>)
                .clear()
        }
    }
}

private fun runtimeFactory(
    settingsProvider: TestSettingsProvider = TestSettingsProvider().apply { gigaChatKey = "giga-key" },
    llmApiFactory: suspend (ru.souz.db.SettingsProvider) -> LLMChatAPI,
    toolCatalog: ru.souz.agent.spi.AgentToolCatalog = BackendNoopAgentToolCatalog,
    clientToolCatalog: ru.souz.agent.spi.AgentToolCatalog = BackendNoopAgentToolCatalog,
): BackendConversationRuntimeFactory =
    testBackendConversationRuntimeFactory(
        baseSettingsProvider = settingsProvider,
        llmApiFactory = llmApiFactory,
        sessionRepository = InMemoryAgentSessionRepository(),
        logObjectMapper = jacksonObjectMapper(),
        systemPrompt = "backend test prompt",
        toolCatalog = toolCatalog,
        clientToolCatalog = clientToolCatalog,
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
        model = LLMModel.QwenMax.alias,
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

private class TestClientToolCatalog(
    private val toolOverrides: Map<String, LLMToolSetup> = emptyMap(),
) : ru.souz.agent.spi.AgentToolCatalog {
    override val toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>> =
        mapOf(
            ToolCategory.CHAT to mapOf(
                "user.ask" to (toolOverrides["user.ask"]
                    ?: fakeTool(name = "user.ask", fewShotExamples = emptyList())),
            ),
            ToolCategory.APPLICATIONS to mapOf(
                "device.media.open" to (toolOverrides["device.media.open"]
                    ?: fakeTool(name = "device.media.open", fewShotExamples = emptyList())),
            ),
        )
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

private class ReplyingChatApi : LLMChatAPI {
    val requests = mutableListOf<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        return reply(body, "assistant reply")
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

private class SkillLoopChatApi(
    private val skillId: String,
    private val invocationArguments: Map<String, Any> = mapOf("value" to "delegated"),
) : LLMChatAPI {
    val requests = mutableListOf<LLMRequest.Chat>()

    override suspend fun message(body: LLMRequest.Chat): LLMResponse.Chat {
        requests += body
        return when (requests.size) {
            1 -> toolCallReply(body, "GetSkillByName", mapOf("skillId" to skillId))
            2 -> toolCallReply(
                body,
                "RunSkillCommand",
                mapOf("skillId" to skillId, "arguments" to invocationArguments),
            )
            else -> reply(body, "done")
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

private fun sandboxEchoSkillBundle(skillId: SkillId): SkillBundle = SkillBundle.fromFiles(
    skillId = skillId,
    files = listOf(
        SkillFile(
            normalizedPath = "SKILL.md",
            content = """
                ---
                name: sandbox-echo
                description: Run a bundled echo script in the runtime sandbox.
                ---

                Run the bundled echo script with RunSkillCommand.
            """.trimIndent().toByteArray(),
        ),
        SkillFile(
            normalizedPath = "scripts/echo.sh",
            content = """
                printf '%s:%s:%s' "${'$'}SOUZ_SKILL_ID" "${'$'}1" "${'$'}(cat)"
            """.trimIndent().toByteArray(),
        ),
    ),
)

private fun LLMRequest.Chat.functionResult(name: String): String = messages.single { message ->
    message.role == LLMMessageRole.function && message.name == name
}.content

private class CapturingTool(name: String) : LLMToolSetup {
    var arguments: Map<String, Any>? = null
    var meta: ToolInvocationMeta? = null

    override val fn: LLMRequest.Function = LLMRequest.Function(
        name = name,
        description = "Capture a delegated Skill invocation.",
        parameters = LLMRequest.Parameters(type = "object"),
    )

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        error("Request metadata is required.")

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        arguments = functionCall.arguments
        this.meta = meta
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "{\"status\":\"ok\"}",
            name = functionCall.name,
        )
    }
}

private fun LLMRequest.Chat.systemMessage(): String = messages.first { it.role == ru.souz.llms.LLMMessageRole.system }.content

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

private fun toolCallReply(
    body: LLMRequest.Chat,
    name: String,
    arguments: Map<String, Any>,
): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
    choices = listOf(
        LLMResponse.Choice(
            message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = LLMResponse.FunctionCall(name, arguments),
                functionsStateId = "call-${name.lowercase()}",
            ),
            index = 0,
            finishReason = LLMResponse.FinishReason.function_call,
        )
    ),
    created = System.currentTimeMillis(),
    model = body.model,
    usage = LLMResponse.Usage(7, 3, 10, 0),
)

private val SKILL_CORE_TOOL_NAMES = setOf(
    "GetSkillByName",
    "GetSkillsByCategory",
    "GetSkillsNamesByCategory",
    "GetKnowledge",
    "SearchKnowledge",
    "SearchMemory",
    "RunSkillCommand",
)
