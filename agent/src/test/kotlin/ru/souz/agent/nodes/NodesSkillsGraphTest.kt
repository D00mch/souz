package ru.souz.agent.nodes

import com.fasterxml.jackson.databind.JsonNode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.knowledge.ConversationKnowledgeStore
import ru.souz.agent.knowledge.KnowledgeContent
import ru.souz.agent.knowledge.KnowledgeEntry
import ru.souz.agent.knowledge.KnowledgeWriteResult
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentRuntimeEnvironment
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentToolCatalog
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.AgentToolsFilter
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesSkillsGraphTest {
    @Test
    fun `prepare context restricts tools and augments history without changing the supplied prompt`() {
        val coreTool = FixedResultTool("CoreTool", "{}")
        val catalogTool = FixedResultTool("CatalogTool", "{}")
        val catalog = object : AgentToolCatalog {
            override val toolsByCategory = mapOf(
                ToolCategory.FILES to mapOf(catalogTool.fn.name to catalogTool),
            )
        }
        val suppliedPrompt = "A caller-provided system prompt."
        val context = AgentContext(
            input = "hello",
            settings = AgentSettings(
                model = "test",
                temperature = 0f,
                tools = AgentTools(catalog.toolsByCategory),
            ),
            history = listOf(
                LLMRequest.Message(LLMMessageRole.system, "obsolete effective prompt"),
                LLMRequest.Message(LLMMessageRole.user, "hello"),
            ),
            activeTools = listOf(catalogTool.fn),
            systemPrompt = suppliedPrompt,
        )

        val result = nodesSkillsGraph(null, catalog).prepareContext(context, listOf(coreTool))

        assertEquals(suppliedPrompt, result.systemPrompt)
        assertEquals(listOf(coreTool.fn), result.activeTools)
        assertEquals(mapOf(coreTool.fn.name to coreTool), result.settings.tools.byName)
        assertEquals(emptyMap(), result.settings.tools.byCategory)
        assertEquals(2, result.history.size)
        assertContains(result.history.first().content, suppliedPrompt)
        assertContains(result.history.first().content, "- CUSTOM")
        assertContains(result.history.first().content, "- FILES")
        assertFalse(result.history.first().content.contains("obsolete effective prompt"))
    }

    @Test
    fun `prepare context advertises categories from the current tool filter`() {
        val filesTool = FixedResultTool("FilesTool", "{}")
        val browserTool = FixedResultTool("BrowserTool", "{}")
        val catalog = object : AgentToolCatalog {
            override val toolsByCategory = mapOf(
                ToolCategory.FILES to mapOf(filesTool.fn.name to filesTool),
                ToolCategory.BROWSER to mapOf(browserTool.fn.name to browserTool),
            )
        }
        val filter = SwitchingToolsFilter(ToolCategory.FILES)
        val context = AgentContext(
            input = "hello",
            settings = AgentSettings(
                model = "test",
                temperature = 0f,
                tools = AgentTools(catalog.toolsByCategory),
            ),
            history = emptyList(),
            activeTools = emptyList(),
            systemPrompt = "system",
        )
        val graph = nodesSkillsGraph(null, catalog, filter)

        val filesPrompt = graph.prepareContext(context, listOf(filesTool)).history.first().content
        filter.allowedCategory = ToolCategory.BROWSER
        val browserPrompt = graph.prepareContext(context, listOf(filesTool)).history.first().content

        assertContains(filesPrompt, "- CUSTOM")
        assertContains(filesPrompt, "- FILES")
        assertFalse(filesPrompt.contains("- BROWSER"))
        assertContains(browserPrompt, "- CUSTOM")
        assertContains(browserPrompt, "- BROWSER")
        assertFalse(browserPrompt.contains("- FILES"))
    }

    @Test
    fun `results at 8192 UTF-8 bytes stay inline and 8193 bytes are offloaded`() = runTest {
        val store = RecordingKnowledgeStore()
        val exact = "é".repeat(4_096)
        val oversized = exact + "a"

        val exactResult = executeToolResult(exact, store)
        val oversizedResult = executeToolResult(oversized, store)

        assertEquals(exact, exactResult.content)
        assertEquals(oversized, store.puts.single().content)
        val reference = restJsonMapper.readTree(oversizedResult.content)
        assertReference(reference, originalLength = oversized.length)
    }

    @Test
    fun `offloading preserves all message metadata`() = runTest {
        val store = RecordingKnowledgeStore()
        val original = LLMRequest.Message(
            role = LLMMessageRole.function,
            content = "x".repeat(8_193),
            functionsStateId = "call-7",
            attachments = listOf("attachment-1"),
            name = "LargeTool",
        )

        val result = executeToolResult(original.content, store, original)

        assertEquals(original.role, result.role)
        assertEquals("call-1", result.functionsStateId)
        assertEquals(original.attachments, result.attachments)
        assertEquals(original.name, result.name)
        assertTrue(result.content.length < original.content.length)
    }

    @Test
    fun `Knowledge retrieval results are never re-offloaded`() = runTest {
        val store = RecordingKnowledgeStore()
        val content = "k".repeat(10_000)

        listOf("GetKnowledge", "SearchKnowledge").forEach { functionName ->
            val result = executeToolResult(
                content = content,
                store = store,
                functionName = functionName,
            )

            assertEquals(content, result.content)
        }
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `skill discovery results stay inline and preserve their structured JSON`() = runTest {
        val store = RecordingKnowledgeStore()
        val discoveryToolNames = setOf(
            "GetSkillByName",
            "GetSkillsByCategory",
            "GetSkillsNamesByCategory",
        )

        discoveryToolNames.forEach { functionName ->
            val content = restJsonMapper.writeValueAsString(
                linkedMapOf(
                    "tool" to functionName,
                    "payload" to "x".repeat(10_000),
                )
            )
            val result = executeToolResult(
                content = content,
                store = store,
                functionName = functionName,
                alwaysInlineToolNames = discoveryToolNames,
            )

            assertEquals(content, result.content)
            assertEquals(restJsonMapper.readTree(content), restJsonMapper.readTree(result.content))
        }
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `multiple oversized results are offloaded independently`() = runTest {
        val store = RecordingKnowledgeStore()
        val first = FixedResultTool("FirstTool", "a".repeat(8_193))
        val second = FixedResultTool("SecondTool", "b".repeat(8_194))
        val toolsByName = listOf(first, second).associateBy { it.fn.name }
        val choices = toolsByName.values.mapIndexed { index, tool ->
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = "",
                    role = LLMMessageRole.assistant,
                    functionCall = LLMResponse.FunctionCall(tool.fn.name, emptyMap()),
                    functionsStateId = "call-$index",
                ),
                index = index,
                finishReason = LLMResponse.FinishReason.function_call,
            )
        }
        val context = toolContext(first).copy(
            input = toolContext(first).input.copy(choices = choices),
            settings = AgentSettings(
                model = "test",
                temperature = 0f,
                tools = AgentTools(emptyMap(), toolsByName, emptyMap()),
            ),
            activeTools = toolsByName.values.map { it.fn },
        )

        val result = nodesSkillsGraph(store)
            .toolUseWithKnowledge(setOf("GetKnowledge", "SearchKnowledge"))
            .execute(context, runtime())

        assertEquals(listOf("FirstTool", "SecondTool"), store.puts.map { it.sourceTool })
        assertEquals(listOf(8_193, 8_194), store.puts.map { it.content.length })
        assertEquals(
            listOf("FirstTool", "SecondTool"),
            result.history.takeLast(2).map { restJsonMapper.readTree(it.content)["sourceTool"].textValue() },
        )
    }

    @Test
    fun `unavailable or failed storage keeps the result inline`() = runTest {
        val content = "x".repeat(8_193)
        val unavailable = RecordingKnowledgeStore(writeResult = KnowledgeWriteResult.ConversationUnavailable)
        val failed = RecordingKnowledgeStore(failure = IllegalStateException("disk failed"))

        assertEquals(content, executeToolResult(content, unavailable).content)
        assertEquals(content, executeToolResult(content, failed).content)
    }

    @Test
    fun `storage cancellation propagates`() = runTest {
        val store = RecordingKnowledgeStore(failure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            executeToolResult("x".repeat(8_193), store)
        }
    }

    private suspend fun executeToolResult(
        content: String,
        store: ConversationKnowledgeStore,
        returnedMessage: LLMRequest.Message = LLMRequest.Message(
            role = LLMMessageRole.function,
            content = content,
            name = "LargeTool",
        ),
        functionName: String = "LargeTool",
        alwaysInlineToolNames: Set<String> = setOf("GetKnowledge", "SearchKnowledge"),
    ): LLMRequest.Message {
        val tool = FixedResultTool(functionName, returnedMessage)
        val context = toolContext(tool)
        val result = nodesSkillsGraph(store).toolUseWithKnowledge(alwaysInlineToolNames).execute(context, runtime())
        return result.history.last()
    }

    private fun toolContext(tool: LLMToolSetup): AgentContext<LLMResponse.Chat.Ok> {
        val functionCall = LLMResponse.FunctionCall(tool.fn.name, emptyMap())
        return AgentContext(
            input = LLMResponse.Chat.Ok(
                choices = listOf(
                    LLMResponse.Choice(
                        message = LLMResponse.Message(
                            content = "",
                            role = LLMMessageRole.assistant,
                            functionCall = functionCall,
                            functionsStateId = "call-1",
                        ),
                        index = 0,
                        finishReason = LLMResponse.FinishReason.function_call,
                    )
                ),
                created = 1,
                model = "test",
                usage = LLMResponse.Usage(1, 1, 2, 0),
            ),
            settings = AgentSettings(
                model = "test",
                temperature = 0f,
                tools = AgentTools(emptyMap(), mapOf(tool.fn.name to tool), emptyMap()),
            ),
            history = listOf(LLMRequest.Message(LLMMessageRole.user, "run")),
            activeTools = listOf(tool.fn),
            systemPrompt = "system",
            toolInvocationMeta = ToolInvocationMeta(
                userId = "user-1",
                conversationId = "conversation-1",
                requestId = "request-1",
            ),
        )
    }

    private fun nodesSkillsGraph(
        knowledgeStore: ConversationKnowledgeStore?,
        toolCatalog: AgentToolCatalog = object : AgentToolCatalog {
            override val toolsByCategory = emptyMap<ToolCategory, Map<String, LLMToolSetup>>()
        },
        toolsFilter: AgentToolsFilter = PassThroughToolsFilter,
    ): NodesSkillsGraph {
        val nodesCommon = NodesCommon(
            desktopInfoRepository = mockk<AgentDesktopInfoRepository>(relaxed = true),
            settingsProvider = mockk<AgentSettingsProvider>(relaxed = true) {
                every { defaultCalendar } returns null
            },
            agentToolExecutor = AgentToolExecutor(),
            defaultBrowserProvider = DefaultBrowserProvider { null },
            runtimeEnvironment = object : AgentRuntimeEnvironment {
                override val locale: Locale = Locale.US
                override val zoneId: ZoneId = ZoneId.of("UTC")
            },
        )
        return NodesSkillsGraph(
            nodesCommon = nodesCommon,
            knowledgeStore = knowledgeStore,
            toolCatalog = toolCatalog,
            toolsFilter = toolsFilter,
        )
    }

    private fun runtime() = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)

    private fun assertReference(reference: JsonNode, originalLength: Int) {
        assertEquals(KNOWLEDGE_ID, reference["knowledgeId"].textValue())
        assertEquals("LargeTool", reference["sourceTool"].textValue())
        assertEquals(originalLength, reference["originalLength"].intValue())
        assertEquals(originalLength, reference["storedLength"].intValue())
        assertFalse(reference["truncated"].booleanValue())
        assertTrue(reference["instruction"].textValue().contains("GetKnowledge"))
        assertTrue(reference["instruction"].textValue().contains("SearchKnowledge"))
        assertEquals(6, reference.size())
    }

    private class FixedResultTool(
        name: String,
        private val result: LLMRequest.Message,
    ) : LLMToolSetup {
        constructor(name: String, content: String) : this(
            name,
            LLMRequest.Message(LLMMessageRole.function, content, name = name),
        )

        override val fn = LLMRequest.Function(
            name = name,
            description = name,
            parameters = LLMRequest.Parameters("object", emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message = result
    }

    private class RecordingKnowledgeStore(
        private val writeResult: KnowledgeWriteResult? = null,
        private val failure: Exception? = null,
    ) : ConversationKnowledgeStore {
        val puts = mutableListOf<Put>()

        override suspend fun put(
            meta: ToolInvocationMeta,
            sourceTool: String,
            content: String,
        ): KnowledgeWriteResult {
            failure?.let { throw it }
            puts += Put(meta, sourceTool, content)
            return writeResult ?: KnowledgeWriteResult.Stored(
                KnowledgeEntry(
                    id = KNOWLEDGE_ID,
                    sourceTool = sourceTool,
                    originalLength = content.length,
                    content = KnowledgeContent.Complete(content),
                )
            )
        }

        override suspend fun get(meta: ToolInvocationMeta, knowledgeId: String): KnowledgeEntry? = null

        override suspend fun clearConversation(meta: ToolInvocationMeta) = Unit

        data class Put(val meta: ToolInvocationMeta, val sourceTool: String, val content: String)
    }

    private object PassThroughToolsFilter : AgentToolsFilter {
        override fun applyFilter(
            toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        ): Map<ToolCategory, Map<String, LLMToolSetup>> = toolsByCategory
    }

    private class SwitchingToolsFilter(
        var allowedCategory: ToolCategory,
    ) : AgentToolsFilter {
        override fun applyFilter(
            toolsByCategory: Map<ToolCategory, Map<String, LLMToolSetup>>,
        ): Map<ToolCategory, Map<String, LLMToolSetup>> =
            toolsByCategory.filterKeys { it == allowedCategory }
    }

    private companion object {
        private const val KNOWLEDGE_ID = "11111111-1111-1111-1111-111111111111"
    }
}
