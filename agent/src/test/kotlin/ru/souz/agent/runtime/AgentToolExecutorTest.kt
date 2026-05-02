package ru.souz.agent.runtime

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolExecutorTest {
    @Test
    fun `reports successful tool execution through injected telemetry sink`() = runTest {
        val events = mutableListOf<AgentToolExecutionEvent>()
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val executor = AgentToolExecutor(
            telemetry = AgentTelemetry { event -> events += event },
        )
        val settings = settingsWithFileTool { functionCall ->
            LLMRequest.Message(
                role = LLMMessageRole.function,
                content = """{"path":"${functionCall.arguments["path"]}"}""",
            )
        }
        val logContext = AgentExecutionLogContext(
            appSessionId = "app-session",
            requestId = "request-id",
            conversationId = "conversation-id",
            requestSource = "chat_ui",
            model = "test-model",
            provider = "test-provider",
        )

        val response = withContext(logContext.asCoroutineContext()) {
            executor.execute(
                settings = settings,
                functionCall = LLMResponse.FunctionCall(
                    name = "tool.read_file",
                    arguments = mapOf("path" to "/tmp/file.txt"),
                ),
                toolCallId = "call-1",
                eventSink = CollectingAgentRuntimeEventSink(runtimeEvents),
            )
        }

        assertEquals(LLMMessageRole.function, response.role)
        assertEquals(1, logContext.toolExecutionCount)
        val event = events.single()
        assertEquals("app-session", event.appSessionId)
        assertEquals("conversation-id", event.conversationId)
        assertEquals("request-id", event.requestId)
        assertEquals("chat_ui", event.requestSource)
        assertEquals("test-model", event.model)
        assertEquals("test-provider", event.provider)
        assertEquals("tool.read_file", event.functionName)
        assertEquals(ToolCategory.FILES.name, event.toolCategory)
        assertEquals(listOf("path"), event.argumentKeys)
        assertTrue(event.success)
        assertNull(event.errorType)
        assertEquals(
            listOf(
                AgentRuntimeEvent.ToolCallStarted(
                    toolCallId = "call-1",
                    name = "tool.read_file",
                    arguments = mapOf("path" to "/tmp/file.txt"),
                ),
                AgentRuntimeEvent.ToolCallFinished(
                    toolCallId = "call-1",
                    name = "tool.read_file",
                    result = """{"path":"/tmp/file.txt"}""",
                    durationMs = runtimeEvents.filterIsInstance<AgentRuntimeEvent.ToolCallFinished>().single().durationMs,
                ),
            ),
            runtimeEvents,
        )
        assertTrue(runtimeEvents.filterIsInstance<AgentRuntimeEvent.ToolCallFinished>().single().durationMs >= 0L)
    }

    @Test
    fun `reports unknown tool calls through injected telemetry sink`() = runTest {
        val events = mutableListOf<AgentToolExecutionEvent>()
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val executor = AgentToolExecutor(
            telemetry = AgentTelemetry { event -> events += event },
        )
        val logContext = AgentExecutionLogContext(
            appSessionId = "app-session",
            requestId = "request-id",
            conversationId = "conversation-id",
            requestSource = "chat_ui",
            model = "test-model",
            provider = "test-provider",
        )

        val response = withContext(logContext.asCoroutineContext()) {
            executor.execute(
                settings = AgentSettings(
                    model = "test-model",
                    temperature = 0f,
                    toolsByCategory = emptyMap(),
                ),
                functionCall = LLMResponse.FunctionCall(
                    name = "tool.missing",
                    arguments = mapOf("query" to "kotlin"),
                ),
                toolCallId = "call-missing",
                eventSink = CollectingAgentRuntimeEventSink(runtimeEvents),
            )
        }

        assertEquals(LLMMessageRole.function, response.role)
        assertEquals("""{"result":"no such function tool.missing"}""", response.content)
        assertEquals(1, logContext.toolExecutionCount)
        val event = events.single()
        assertEquals("tool.missing", event.functionName)
        assertNull(event.toolCategory)
        assertEquals(listOf("query"), event.argumentKeys)
        assertFalse(event.success)
        assertEquals("UnknownTool", event.errorType)
        assertEquals(2, runtimeEvents.size)
        assertTrue(runtimeEvents[0] is AgentRuntimeEvent.ToolCallStarted)
        val failedEvent = runtimeEvents[1] as AgentRuntimeEvent.ToolCallFailed
        assertEquals("call-missing", failedEvent.toolCallId)
        assertEquals("tool.missing", failedEvent.name)
        assertEquals("UnknownTool", failedEvent.error::class.simpleName)
        assertTrue(failedEvent.durationMs >= 0L)
    }

    @Test
    fun `reports failures through injected telemetry sink`() = runTest {
        val events = mutableListOf<AgentToolExecutionEvent>()
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val executor = AgentToolExecutor(
            telemetry = AgentTelemetry { event -> events += event },
        )
        val settings = settingsWithFileTool {
            error("secret path: /tmp/private.txt")
        }

        val failure = assertFailsWith<IllegalStateException> {
            executor.execute(
                settings = settings,
                functionCall = LLMResponse.FunctionCall(
                    name = "tool.read_file",
                    arguments = mapOf("path" to "/tmp/private.txt"),
                ),
                toolCallId = "call-error",
                eventSink = CollectingAgentRuntimeEventSink(runtimeEvents),
            )
        }

        assertEquals("secret path: /tmp/private.txt", failure.message)
        val event = events.single()
        assertEquals("tool.read_file", event.functionName)
        assertEquals(ToolCategory.FILES.name, event.toolCategory)
        assertEquals(listOf("path"), event.argumentKeys)
        assertEquals("IllegalStateException", event.errorType)
        assertFalse(event.success)
        assertEquals(2, runtimeEvents.size)
        assertTrue(runtimeEvents[0] is AgentRuntimeEvent.ToolCallStarted)
        val failedEvent = runtimeEvents[1] as AgentRuntimeEvent.ToolCallFailed
        assertEquals("call-error", failedEvent.toolCallId)
        assertEquals("tool.read_file", failedEvent.name)
        assertEquals("secret path: /tmp/private.txt", failedEvent.error.message)
        assertTrue(failedEvent.durationMs >= 0L)
    }

    private fun settingsWithFileTool(
        invoke: suspend (LLMResponse.FunctionCall) -> LLMRequest.Message,
    ): AgentSettings = AgentSettings(
        model = "test-model",
        temperature = 0f,
        toolsByCategory = mapOf(
            ToolCategory.FILES to mapOf(
                "tool.read_file" to object : LLMToolSetup {
                    override val fn: LLMRequest.Function = LLMRequest.Function(
                        name = "tool.read_file",
                        description = "Read file",
                        parameters = LLMRequest.Parameters(
                            type = "object",
                            properties = emptyMap(),
                        ),
                    )

                    override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
                        invoke.invoke(functionCall)
                }
            )
        )
    )

    private class CollectingAgentRuntimeEventSink(
        private val events: MutableList<AgentRuntimeEvent>,
    ) : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) {
            events += event
        }
    }
}
