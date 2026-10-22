package agent

import kotlinx.coroutines.test.runTest
import org.kodein.di.direct
import org.kodein.di.instance
import ru.souz.agent.AgentContextFactory
import ru.souz.agent.AgentExecutionResult
import ru.souz.agent.AgentId
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.skills.SkillId
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.McpToolProvider
import ru.souz.agent.skills.registry.SkillRegistryRepository
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.memory.ConversationMemoryRuntime
import ru.souz.memory.NoopConversationMemoryRuntime
import ru.souz.tool.RuntimePassThroughToolsFilter
import ru.souz.tool.ToolCategory
import ru.souz.tool.skills.ToolGetSkillByName
import ru.souz.tool.skills.ToolGetSkillsNamesByCategory
import ru.souz.tool.skills.ToolInvokeSkill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class AgentScenarioTestSupportTest {
    @Test
    fun `scenario catalog exposes only declared mocks and delegates invocation`() = runTest {
        val productionFileTool = RecordingTool("FindFilesByName", "production metadata")
        val productionBrowserTool = RecordingTool("CreateNewBrowserTab", "must stay unavailable")
        val mockedFileTool = RecordingTool("FindFilesByName", "mock metadata")
        val productionCatalog = catalog(
            ToolCategory.FILES to listOf(productionFileTool),
            ToolCategory.BROWSER to listOf(productionBrowserTool),
        )

        val scenarioCatalog = ScenarioAgentToolCatalog(
            productionCatalog = productionCatalog,
            mockedTools = listOf(mockedFileTool),
        )

        assertEquals(setOf(ToolCategory.FILES), scenarioCatalog.toolsByCategory.filterValues { it.isNotEmpty() }.keys)
        val exposedTool = scenarioCatalog.toolsByCategory.getValue(ToolCategory.FILES).getValue("FindFilesByName")
        assertEquals(productionFileTool.fn, exposedTool.fn)
        assertFalse(
            scenarioCatalog.toolsByCategory.values.any { "CreateNewBrowserTab" in it },
            "Undeclared browser tools must not be discoverable or executable.",
        )

        val meta = ToolInvocationMeta(userId = "scenario-test")
        val result = exposedTool.invoke(
            LLMResponse.FunctionCall(name = "FindFilesByName", arguments = mapOf("fileName" to "report.txt")),
            meta,
        )

        assertEquals("mock result", result.content)
        assertEquals(1, mockedFileTool.invocations)
        assertEquals(meta, mockedFileTool.lastMeta)
        assertEquals(0, productionFileTool.invocations)
        assertEquals(0, productionBrowserTool.invocations)
    }

    @Test
    fun `scenario catalog rejects duplicate and unknown mock names`() {
        val productionCatalog = catalog(
            ToolCategory.FILES to listOf(RecordingTool("FindFilesByName", "production")),
        )

        assertFailsWith<IllegalArgumentException> {
            ScenarioAgentToolCatalog(
                productionCatalog = productionCatalog,
                mockedTools = listOf(
                    RecordingTool("FindFilesByName", "first"),
                    RecordingTool("FindFilesByName", "second"),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioAgentToolCatalog(
                productionCatalog = productionCatalog,
                mockedTools = listOf(RecordingTool("UnknownTool", "unknown")),
            )
        }
    }

    @Test
    fun `agent selector defaults to graph and accepts storage values case insensitively`() {
        assertEquals(AgentId.GRAPH, parseScenarioAgentId(null))
        assertEquals(AgentId.GRAPH, parseScenarioAgentId(" GRAPH "))
        assertEquals(AgentId.SKILLS_GRAPH, parseScenarioAgentId("SkIlLs"))
        assertFailsWith<IllegalStateException> { parseScenarioAgentId("other") }
    }

    @Test
    fun `scenario DI keeps skills and host integrations hermetic`() = runTest {
        val mockedFileTool = RecordingTool("FindFilesByName", "mock metadata")
        val di = AgentScenarioTestSupport(LLMModel.LocalGemma4_E4B_It)
            .createScenarioDi(listOf(mockedFileTool))

        val catalog: AgentToolCatalog = di.direct.instance()
        assertEquals(setOf(ToolCategory.FILES), catalog.toolsByCategory.filterValues { it.isNotEmpty() }.keys)
        val exposedNames = catalog.toolsByCategory.values.flatMap { it.keys }
        assertEquals(listOf("FindFilesByName"), exposedNames)
        val contextFactory: AgentContextFactory = di.direct.instance()
        assertEquals(
            setOf("FindFilesByName"),
            contextFactory.create(AgentId.GRAPH).settings.tools.byName.keys,
        )

        val getSkillsNamesByCategory = di.direct.instance<ToolGetSkillsNamesByCategory>()
        val meta = ToolInvocationMeta(userId = "scenario-test")
        val getSkillsNamesResult = getSkillsNamesByCategory.invoke(
            LLMResponse.FunctionCall(
                name = getSkillsNamesByCategory.fn.name,
                arguments = mapOf("category" to ToolCategory.FILES.name),
            ),
            meta,
        )
        val skillIds = restJsonMapper.readTree(getSkillsNamesResult.content)
            .path("skillNames")
            .map { it.asText() }
        assertEquals(listOf("FindFilesByName"), skillIds)

        val getSkillByName = di.direct.instance<ToolGetSkillByName>()
        val getSkillResult = getSkillByName.invoke(
            LLMResponse.FunctionCall(
                name = getSkillByName.fn.name,
                arguments = mapOf("skillId" to "FindFilesByName"),
            ),
            meta,
        )
        assertEquals(
            "FindFilesByName",
            restJsonMapper.readTree(getSkillResult.content).path("skill").path("skillId").asText(),
        )

        val runSkill = di.direct.instance<ToolInvokeSkill>()
        val runSkillResult = runSkill.invoke(
            LLMResponse.FunctionCall(
                name = runSkill.fn.name,
                arguments = mapOf(
                    "skillId" to "FindFilesByName",
                    "arguments" to mapOf("fileName" to "report.txt"),
                ),
            ),
            meta,
        )
        assertEquals("mock result", runSkillResult.content)
        assertEquals(1, mockedFileTool.invocations)

        val repository: SkillRegistryRepository = di.direct.instance()
        assertTrue(repository.listSkills(meta.userId).isEmpty())
        assertTrue(repository.listSkillInventoryIds(meta.userId).isEmpty())
        assertNull(repository.loadSkillBundle(meta.userId, SkillId("installed-browser-skill")))
        assertTrue(di.direct.instance<McpToolProvider>().tools().isEmpty())
        assertSame(NoopConversationMemoryRuntime, di.direct.instance<ConversationMemoryRuntime>())
        val environment: AgentRuntimeEnvironment = di.direct.instance()
        assertNull(environment.defaultBrowserDisplayName)
        val now = ZonedDateTime.now(environment.clock)
        assertEquals(LocalDate.of(2026, 6, 1), now.toLocalDate())
        assertEquals(LocalTime.of(10, 0), now.toLocalTime())
        assertEquals("Europe/Moscow", environment.zoneId.id)
        assertEquals("ru-RU", environment.locale.toLanguageTag())

        val toolsFilter: AgentToolsFilter = di.direct.instance()
        assertSame(RuntimePassThroughToolsFilter, toolsFilter)
        assertSame(catalog.toolsByCategory, toolsFilter.applyFilter(catalog.toolsByCategory))
    }

    @Test
    fun `successful tool call followed by terminal provider failure does not pass`() = runTest {
        val trace = ScenarioExecutionTrace()
        trace.emit(toolFinished())
        trace.modelCall { LLMResponse.Chat.Error(503, "Provider unavailable") }

        assertFailsWith<AssertionError> { trace.assertCompleted(result("Provider unavailable")) }
    }

    @Test
    fun `recovered provider and tool failures allow a subsequent completed answer`() = runTest {
        val trace = ScenarioExecutionTrace()
        trace.modelCall { LLMResponse.Chat.Error(503, "Temporary failure") }
        trace.emit(AgentRuntimeEvent.ToolCallFailed("first", "Open", IllegalStateException("temporary"), 1))
        trace.emit(toolFinished())
        trace.modelCall { response("Telegram запущен.") }

        trace.assertCompleted(result("Telegram запущен."))
        assertEquals(2, trace.modelCalls)
        assertEquals(LLMResponse.Usage(10, 5, 15, 2), trace.usage)
        assertTrue(trace.report(2.seconds).contains("durationMs=2000"))
    }

    @Test
    fun `empty truncated and internal deliberation outputs do not pass`() = runTest {
        val incomplete = listOf(
            response(""),
            response("Telegram запущен.", finishReason = LLMResponse.FinishReason.length),
            response("<think>I need to launch the app.</think>"),
            response("Need to launch Telegram using the tool."),
            response("Проверяю приложение", reasoning = "Проверяю приложение"),
        )
        for (answer in incomplete) {
            val trace = ScenarioExecutionTrace()
            trace.emit(toolFinished())
            trace.modelCall { answer }
            assertFailsWith<AssertionError>(answer.toString()) {
                trace.assertCompleted(result(answer.choices.single().message.content))
            }
        }
    }

    @Test
    fun `final text cannot hide a pending call or failed earlier choice`() = runTest {
        val final = response("Telegram запущен.")
        val hiddenChoices = listOf(
            final.choices.single().copy(message = LLMResponse.Message(
                content = "",
                role = LLMMessageRole.assistant,
                functionCall = LLMResponse.FunctionCall("Open", mapOf("target" to "Telegram")),
                functionsStateId = "pending-call",
            )),
            final.choices.single().copy(finishReason = LLMResponse.FinishReason.length),
            final.choices.single().copy(finishReason = LLMResponse.FinishReason.blacklist),
            final.choices.single().copy(finishReason = LLMResponse.FinishReason.error),
        )
        for (hidden in hiddenChoices) {
            val trace = ScenarioExecutionTrace()
            trace.emit(toolFinished())
            trace.modelCall { final.copy(choices = listOf(hidden) + final.choices) }
            assertFailsWith<AssertionError>(hidden.toString()) {
                trace.assertCompleted(result("Telegram запущен."))
            }
        }
    }

    @Test
    fun `completion needs executed tool and matching final assistant history`() = runTest {
        val trace = ScenarioExecutionTrace()
        trace.modelCall { response("Telegram запущен.") }
        assertFailsWith<AssertionError> { trace.assertCompleted(result("Telegram запущен.")) }
        trace.emit(toolFinished())
        val completed = result("Telegram запущен.")
        assertFailsWith<AssertionError> {
            trace.assertCompleted(completed.copy(context = completed.context.copy(
                history = listOf(LLMRequest.Message(LLMMessageRole.function, "Opened")),
            )))
        }
        assertFailsWith<AssertionError> { trace.assertCompleted(completed.copy(output = "Timeout")) }
    }

    @Test
    fun `call bound prevents another provider request and usage includes every successful response`() = runTest {
        val trace = ScenarioExecutionTrace(maxModelCalls = 2)
        repeat(2) { trace.modelCall { response("Готово") } }
        var extraRequestExecuted = false
        assertFailsWith<AssertionError> {
            trace.modelCall {
                extraRequestExecuted = true
                response("Готово")
            }
        }
        assertFalse(extraRequestExecuted)
        assertEquals(2, trace.modelCalls)
        assertEquals(LLMResponse.Usage(20, 10, 30, 4), trace.usage)
    }
}

private fun toolFinished() = AgentRuntimeEvent.ToolCallFinished("call", "Open", "Opened", 1)

private fun response(
    output: String,
    finishReason: LLMResponse.FinishReason = LLMResponse.FinishReason.stop,
    reasoning: String? = null,
) = LLMResponse.Chat.Ok(
    choices = listOf(LLMResponse.Choice(
        LLMResponse.Message(output, LLMMessageRole.assistant, functionsStateId = null, reasoningContent = reasoning),
        index = 0,
        finishReason = finishReason,
    )),
    created = 0,
    model = "scenario-model",
    usage = LLMResponse.Usage(10, 5, 15, 2),
)

private fun result(output: String) = AgentExecutionResult(
    output,
    AgentContext(
        input = output,
        settings = AgentSettings("scenario-model", LlmProvider.OPENAI, 0f, emptyMap()),
        history = listOf(LLMRequest.Message(LLMMessageRole.assistant, output)),
        activeTools = emptyList(),
        systemPrompt = "",
    ),
)

private class RecordingTool(
    name: String,
    description: String,
) : LLMToolSetup {
    override val fn = LLMRequest.Function(
        name = name,
        description = description,
        parameters = LLMRequest.Parameters(
            type = "object",
            properties = mapOf("value" to LLMRequest.Property("string", "value")),
        ),
    )
    var invocations: Int = 0
    var lastMeta: ToolInvocationMeta? = null

    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
        invoke(functionCall, ToolInvocationMeta.localDefault())

    override suspend fun invoke(
        functionCall: LLMResponse.FunctionCall,
        meta: ToolInvocationMeta,
    ): LLMRequest.Message {
        invocations += 1
        lastMeta = meta
        return LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "mock result",
            name = functionCall.name,
        )
    }
}

private fun catalog(
    vararg categories: Pair<ToolCategory, List<LLMToolSetup>>,
): AgentToolCatalog = AgentToolCatalog(categories.toMap())
