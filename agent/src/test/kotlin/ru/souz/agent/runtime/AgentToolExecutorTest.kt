package ru.souz.agent.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.spi.AgentToolExecutionEvent
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.LlmProvider
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentToolExecutorTest {
    private val call = LLMResponse.FunctionCall("tool.read_file", mapOf("path" to "/tmp/file.txt"))
    private val result = LLMRequest.Message(LLMMessageRole.function, """{"path":"/tmp/file.txt"}""")

    @Test
    fun `successful execution preserves structured telemetry and runtime events`() = runTest {
        val telemetryEvents = mutableListOf<AgentToolExecutionEvent>()
        val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
        val executor = AgentToolExecutor(AgentTelemetry { telemetryEvents += it })
        val context = AgentExecutionLogContext(
            appSessionId = "app-session", requestId = "request-id", conversationId = "conversation-id",
            requestSource = "chat_ui", model = "test-model", provider = "test-provider",
        )
        val response = withContext(context.asCoroutineContext()) {
            executor.execute(settingsWithFileTool { _, _ -> result }, call,
                toolCallId = "call-1", eventSink = collectingSink(runtimeEvents))
        }
        assertSame(result, response)
        assertEquals(1, context.toolExecutionCount)
        val event = telemetryEvents.single()
        assertEquals(
            AgentToolExecutionEvent(
                appSessionId = "app-session", conversationId = "conversation-id", requestId = "request-id",
                requestSource = "chat_ui", model = "test-model", provider = "test-provider",
                functionName = call.name, toolCategory = ToolCategory.FILES.name,
                argumentKeys = listOf("path"), durationMs = event.durationMs,
            ), event,
        )
        assertTrue(event.success)
        assertNull(event.errorType)
        assertTrue(event.durationMs >= 0L)
        val finished = assertIs<AgentRuntimeEvent.ToolCallFinished>(runtimeEvents.last())
        assertEquals(
            listOf(
                AgentRuntimeEvent.ToolCallStarted("call-1", call.name, call.arguments),
                AgentRuntimeEvent.ToolCallFinished("call-1", call.name, result.content, finished.durationMs),
            ), runtimeEvents,
        )
        assertTrue(finished.durationMs >= 0L)
    }

    @Test
    fun `unknown and throwing tools retain their distinct failure behavior`() = runTest {
        val thrown = IllegalStateException("secret path: /tmp/private.txt")
        for (failure in listOf(null, thrown)) {
            val telemetryEvents = mutableListOf<AgentToolExecutionEvent>()
            val runtimeEvents = mutableListOf<AgentRuntimeEvent>()
            val executor = AgentToolExecutor(AgentTelemetry { telemetryEvents += it })
            val functionCall = if (failure == null) call.copy(name = "tool.missing") else call
            suspend fun execute() = executor.execute(
                settingsWithFileTool { _, _ -> throw thrown }, functionCall,
                toolCallId = "call-error", eventSink = collectingSink(runtimeEvents),
            )
            if (failure == null) {
                val response = execute()
                assertEquals(LLMMessageRole.function, response.role)
                assertEquals("""{"result":"no such function tool.missing"}""", response.content)
            } else {
                assertSame(failure, assertFailsWith<IllegalStateException> { execute() })
            }
            val event = telemetryEvents.single()
            assertEquals(functionCall.name, event.functionName)
            assertEquals(if (failure == null) null else ToolCategory.FILES.name, event.toolCategory)
            assertEquals(listOf("path"), event.argumentKeys)
            assertEquals(if (failure == null) "UnknownTool" else "IllegalStateException", event.errorType)
            assertFalse(event.success)
            assertTrue(event.durationMs >= 0L)
            assertEquals(2, runtimeEvents.size)
            assertEquals(AgentRuntimeEvent.ToolCallStarted("call-error", functionCall.name, call.arguments), runtimeEvents.first())
            val failed = assertIs<AgentRuntimeEvent.ToolCallFailed>(runtimeEvents.last())
            assertEquals("call-error", failed.toolCallId)
            assertEquals(functionCall.name, failed.name)
            assertSame(event.failure, failed.error)
            if (failure != null) assertSame(failure, event.failure)
            assertTrue(failed.durationMs >= 0L)
        }
    }

    @Test
    fun `passes default and explicit invocation metadata`() = runTest {
        val received = mutableListOf<ToolInvocationMeta>()
        val settings = settingsWithFileTool { _, meta ->
            received += meta
            result
        }
        val executor = AgentToolExecutor()
        executor.execute(settings, call)
        val explicit = ToolInvocationMeta(
            userId = "user-1", conversationId = "conversation-1", requestId = "request-1",
            locale = "en-US", timeZone = "America/New_York",
        )
        executor.execute(settings, call, explicit)
        assertEquals(listOf(ToolInvocationMeta.localDefault(), explicit), received)
    }

    @Test
    fun `records cancellation once when runtime failure delivery also cancels`() = runTest {
        var started = 0
        val events = mutableListOf<AgentToolExecutionEvent>()
        val telemetry = object : AgentTelemetry {
            override fun toolExecutionStarted(functionName: String) { started++ }
            override fun recordToolExecution(event: AgentToolExecutionEvent) { events += event }
        }
        val cancellation = CancellationException("cancel tool")
        assertFailsWith<CancellationException> {
            AgentToolExecutor(telemetry).execute(
                settingsWithFileTool { _, _ -> throw cancellation }, call,
                eventSink = object : AgentRuntimeEventSink {
                    override suspend fun emit(event: AgentRuntimeEvent) {
                        if (event is AgentRuntimeEvent.ToolCallFailed) throw CancellationException("cancel event delivery")
                    }
                },
            )
        }
        assertEquals(1, started)
        assertSame(cancellation, events.single().failure)
    }

    private fun settingsWithFileTool(
        callback: suspend (LLMResponse.FunctionCall, ToolInvocationMeta) -> LLMRequest.Message,
    ) = AgentSettings(
        model = "test-model", provider = LlmProvider.OPENAI, temperature = 0f,
        toolsByCategory = mapOf(ToolCategory.FILES to mapOf(call.name to object : LLMToolSetup {
            override val fn = LLMRequest.Function(
                name = call.name, description = "Read file",
                parameters = LLMRequest.Parameters(type = "object", properties = emptyMap()),
            )
            override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
                callback(functionCall, ToolInvocationMeta.localDefault())

            override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta) =
                callback(functionCall, meta)
        })),
    )

    private fun collectingSink(events: MutableList<AgentRuntimeEvent>) = object : AgentRuntimeEventSink {
        override suspend fun emit(event: AgentRuntimeEvent) { events += event }
    }
}
