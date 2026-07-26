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
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.agent.state.AgentTools
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesCommonKnowledgeTest {
    @Test
    fun `results at 4096 UTF-8 bytes stay inline and 4097 bytes are offloaded`() = runTest {
        val store = RecordingKnowledgeStore()
        val exact = "é".repeat(2_048)
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
            content = "x".repeat(4_097),
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
    fun `GetKnowledge results are never re-offloaded`() = runTest {
        val store = RecordingKnowledgeStore()
        val content = "k".repeat(10_000)

        val result = executeToolResult(
            content = content,
            store = store,
            functionName = "GetKnowledge",
            getKnowledgeToolName = "GetKnowledge",
        )

        assertEquals(content, result.content)
        assertTrue(store.puts.isEmpty())
    }

    @Test
    fun `multiple oversized results are offloaded independently`() = runTest {
        val store = RecordingKnowledgeStore()
        val first = FixedResultTool("FirstTool", "a".repeat(4_097))
        val second = FixedResultTool("SecondTool", "b".repeat(4_098))
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

        val result = nodesCommon(store).toolUseWithKnowledge("GetKnowledge").execute(context, runtime())

        assertEquals(listOf("FirstTool", "SecondTool"), store.puts.map { it.sourceTool })
        assertEquals(listOf(4_097, 4_098), store.puts.map { it.content.length })
        assertEquals(
            listOf("FirstTool", "SecondTool"),
            result.history.takeLast(2).map { restJsonMapper.readTree(it.content)["sourceTool"].textValue() },
        )
    }

    @Test
    fun `unavailable or failed storage keeps the result inline`() = runTest {
        val content = "x".repeat(4_097)
        val unavailable = RecordingKnowledgeStore(writeResult = KnowledgeWriteResult.ConversationUnavailable)
        val failed = RecordingKnowledgeStore(failure = IllegalStateException("disk failed"))

        assertEquals(content, executeToolResult(content, unavailable).content)
        assertEquals(content, executeToolResult(content, failed).content)
    }

    @Test
    fun `storage cancellation propagates`() = runTest {
        val store = RecordingKnowledgeStore(failure = CancellationException("cancelled"))

        assertFailsWith<CancellationException> {
            executeToolResult("x".repeat(4_097), store)
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
        getKnowledgeToolName: String = "GetKnowledge",
    ): LLMRequest.Message {
        val tool = FixedResultTool(functionName, returnedMessage)
        val context = toolContext(tool)
        val result = nodesCommon(store).toolUseWithKnowledge(getKnowledgeToolName).execute(context, runtime())
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

    private fun nodesCommon(knowledgeStore: ConversationKnowledgeStore?): NodesCommon = NodesCommon(
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
        knowledgeStore = knowledgeStore,
    )

    private fun runtime() = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 10)

    private fun assertReference(reference: JsonNode, originalLength: Int) {
        assertEquals(KNOWLEDGE_ID, reference["knowledgeId"].textValue())
        assertEquals("LargeTool", reference["sourceTool"].textValue())
        assertEquals(originalLength, reference["originalLength"].intValue())
        assertEquals(originalLength, reference["storedLength"].intValue())
        assertFalse(reference["truncated"].booleanValue())
        assertTrue(reference["instruction"].textValue().contains("GetKnowledge"))
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

    private companion object {
        private const val KNOWLEDGE_ID = "11111111-1111-1111-1111-111111111111"
    }
}
