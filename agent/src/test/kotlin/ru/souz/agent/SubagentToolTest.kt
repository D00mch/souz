package ru.souz.agent

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import ru.souz.ToolLoopGraphBasedAgent
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.AgentTelemetry
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.restJsonMapper
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubagentToolTest {
    @Test
    fun `each tool invocation starts with only its task and selected tools`() = runTest {
        val requests = mutableListOf<LLMRequest.Chat>()
        val selected = tool("Selected")
        val parentSettings = settings()
        val subagent = subagent(prepare = { input, _ ->
            setup(parentSettings, if (input.task == "first") listOf(selected) else emptyList())
        }) { request -> requests += request; response("done") }

        assertEquals("done", subagent.call("first")["result"].asText())
        assertEquals("done", subagent.call("second")["result"].asText())

        assertEquals(listOf("child instructions", "first"), requests[0].messages.map { it.content })
        assertEquals(listOf("child instructions", "second"), requests[1].messages.map { it.content })
        assertEquals(listOf("Selected"), requests[0].functions.map { it.name })
        assertTrue(requests[1].functions.isEmpty())
        requests.forEach {
            assertEquals(parentSettings.model, it.model)
            assertEquals(parentSettings.temperature, it.temperature)
            assertEquals(parentSettings.contextSize, it.maxTokens)
        }
        assertEquals(listOf("ParentTool"), parentSettings.tools.byName.keys.toList())
    }

    @Test
    fun `fabricated call cannot reach an unselected tool in parent settings`() = runTest {
        var forbiddenCalls = 0
        var requests = 0
        val forbidden = tool("Forbidden") { forbiddenCalls += 1; "secret" }
        val subagent = subagent(prepare = { _, _ -> setup(settings(forbidden)) }) { request ->
            assertTrue(request.functions.isEmpty())
            if (++requests == 1) response(toolName = "Forbidden") else {
                assertContains(request.messages.last().content, "no such function Forbidden")
                response("denied")
            }
        }

        assertEquals("denied", subagent.call()["result"].asText())
        assertEquals(0, forbiddenCalls)
    }

    @Test
    fun `selected tools preserve metadata and telemetry with streaming`() = runTest {
        val meta = ToolInvocationMeta(
            userId = "user", conversationId = "conversation", requestId = "request",
            locale = "en", timeZone = "Europe/Moscow",
            attributes = mapOf("clientSessionId" to "client-session", "connectionId" to "connection"),
        )
        var toolCalls = 0
        val selected = tool("Selected") { assertSame(meta, it); toolCalls += 1; "tool result" }
        var requests = 0
        val categories = mutableListOf<String?>()
        val subagent = subagent(
            streaming = true,
            telemetry = AgentTelemetry { categories += it.toolCategory },
            prepare = { _, actualMeta ->
                assertSame(meta, actualMeta)
                setup(settings(selected), listOf(selected))
            },
        ) { request ->
            assertTrue(request.stream == true)
            if (++requests == 1) response(toolName = "Selected") else {
                val result = request.messages.last()
                assertEquals(LLMMessageRole.function, result.role)
                assertEquals("tool result", result.content)
                assertEquals("Selected", result.name)
                assertEquals("call-1", result.functionsStateId)
                response("child answer")
            }
        }

        assertEquals("child answer", subagent.call(meta = meta)["result"].asText())
        assertEquals(1, toolCalls)
        assertEquals(ToolCategory.FILES.name, categories.single())
    }

    @Test
    fun `final answer on last allowed turn succeeds`() = runTest {
        for (limit in listOf(1, 2, 128)) {
            var requests = 0
            val subagent = subagent(prepare = { _, _ -> setup(tools = listOf(tool("Selected"))) }) {
                if (++requests < limit) response(toolName = "Selected") else response("done")
            }
            assertEquals("done", subagent.call(maxTurns = limit)["result"].asText())
            assertEquals(limit, requests)
        }
    }

    @Test
    fun `turn limit prevents the next model request and default is 32`() = runTest {
        for (limit in listOf(1, 32, 128)) {
            var requests = 0
            var toolCalls = 0
            val selected = tool("Selected") { toolCalls += 1; "result" }
            val subagent = subagent(prepare = { _, _ -> setup(tools = listOf(selected)) }) {
                requests += 1; response(toolName = "Selected")
            }
            val error = subagent.call(maxTurns = limit.takeUnless { it == 32 })["error"]
            assertEquals("subagent_turn_limit", error["code"].asText())
            assertContains(error["message"].asText(), "$limit model turns")
            assertEquals(limit, requests)
            assertEquals(limit, toolCalls)
        }
    }

    @Test
    fun `invalid turn limits or duplicate tool names fail before calling provider`() = runTest {
        val subagent = subagent(prepare = { _, _ -> setup(tools = listOf(tool("Duplicate"), tool("Duplicate"))) }) {
            error("Provider must not be called")
        }
        for (limit in listOf(-1, 0, 129)) {
            assertEquals("invalid_subagent_input", subagent.call(maxTurns = limit)["error"]["code"].asText())
        }
        assertContains(subagent.call()["error"]["message"].asText(), "tool names must be unique")
    }

    @Test
    fun `provider error becomes a structured tool failure`() = runTest {
        val subagent = subagent { LLMResponse.Chat.Error(503, "provider unavailable") }
        val failure = subagent.call()["error"]
        assertEquals("subagent_failed", failure["code"].asText())
        assertContains(failure["message"].asText(), "503")
        assertContains(failure["message"].asText(), "provider unavailable")
    }

    @Test
    fun `failed child tool returns a failure without graph retries`() = runTest {
        var toolCalls = 0
        val selected = tool("Selected") { toolCalls += 1; throw LLMException(LLMResponse.Chat.Error(500, "failed tool")) }
        val subagent = subagent(prepare = { _, _ -> setup(tools = listOf(selected)) }) { response(toolName = "Selected") }
        assertEquals("subagent_failed", subagent.call()["error"]["code"].asText())
        assertEquals(1, toolCalls)
    }

    @Test
    fun `parent cancellation cancels an active model or tool`() = runTest {
        for (duringTool in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val waitForCancellation: suspend () -> Nothing = {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            val selected = tool("Selected") { waitForCancellation() }
            val subagent = subagent(prepare = { _, _ -> setup(tools = listOf(selected)) }) {
                if (duringTool) response(toolName = "Selected") else waitForCancellation()
            }
            val execution = async { subagent.call() }
            started.await()
            execution.cancelAndJoin()
            assertTrue(stopped.isCompleted)
            assertFailsWith<CancellationException> { execution.await() }
        }
    }

    @Test
    fun `agent cancellation or replacement stops execution and resets the turn budget`() = runTest {
        for (replace in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val agent = agent(maxTurns = 1) { request ->
                if (request.messages.last().content == "wait") {
                    started.complete(Unit)
                    awaitCancellation()
                }
                response("done")
            }
            val context = AgentContext("wait", settings(), emptyList(), emptyList(), "instructions")
            val execution = async { agent.execute(context) }
            started.await()

            if (!replace) {
                agent.cancelActiveJob()
                execution.join()
            }
            assertEquals("done", agent.execute(context.copy(input = "next")).output)
            assertFailsWith<CancellationException> { execution.await() }
        }
    }

    @Test
    fun `cancellation immediately before any implementation returns cannot return a result`() = runTest {
        val implementation = mockk<Agent>()
        coEvery { implementation.execute(any(), any(), any()) } coAnswers {
            currentCoroutineContext().cancel()
            AgentExecutionResult("discarded", firstArg())
        }
        val subagent = SubagentTool({ implementation }) { _, _ -> setup() }
        var returned = false
        val execution = async { subagent.call(); returned = true }
        assertFailsWith<CancellationException> { execution.await() }
        assertFalse(returned)
    }

    @Test
    fun `parent tool executor awaits result and overlapping invocations have separate graph state`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val subagent = subagent { request ->
            val task = request.messages.last().content
            if (task == "first") { firstStarted.complete(Unit); releaseFirst.await() }
            response(task)
        }
        val first = async {
            AgentToolExecutor().execute(settings(subagent), LLMResponse.FunctionCall(subagent.fn.name,
                mapOf("task" to "first", "maxTurns" to 1)))
        }
        firstStarted.await()
        assertEquals("second", subagent.call("second", maxTurns = 1)["result"].asText())
        assertFalse(first.isCompleted)
        releaseFirst.complete(Unit)
        val result = first.await()
        assertEquals(LLMMessageRole.function, result.role)
        assertEquals(SubagentTool.NAME, result.name)
        assertEquals("first", restJsonMapper.readTree(result.content)["result"].asText())
    }

    private fun subagent(
        streaming: Boolean = false,
        telemetry: AgentTelemetry = AgentTelemetry.NONE,
        prepare: suspend (SubagentTool.Input, ToolInvocationMeta) -> SubagentTool.Setup = { _, _ -> setup() },
        respond: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
    ): LLMToolSetup = SubagentTool({ maxTurns -> agent(streaming, telemetry, maxTurns, respond) }, prepare)

    private fun agent(
        streaming: Boolean = false,
        telemetry: AgentTelemetry = AgentTelemetry.NONE,
        maxTurns: Int = 32,
        respond: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
    ): Agent {
        val api = mockk<LLMChatAPI>()
        coEvery { api.message(any()) } coAnswers { respond(firstArg()) }
        coEvery { api.messageStream(any()) } coAnswers {
            val request = firstArg<LLMRequest.Chat>()
            flow { emit(respond(request)) }
        }
        val settings = mockk<AgentSettingsProvider> { every { useStreaming } returns streaming }
        return ToolLoopGraphBasedAgent(api, settings, maxTurns, telemetry)
    }

    private suspend fun LLMToolSetup.call(
        task: String = "task", maxTurns: Int? = null, meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) = restJsonMapper.readTree(invoke(LLMResponse.FunctionCall(fn.name, buildMap {
        put("task", task)
        maxTurns?.let { put("maxTurns", it) }
    }), meta).content)

    private fun setup(settings: AgentSettings = settings(), tools: List<LLMToolSetup> = emptyList()) =
        SubagentTool.Setup(settings, tools, "child instructions")

    private fun settings(parentTool: LLMToolSetup = tool("ParentTool")) = AgentSettings(
        model = "child-model",
        temperature = 0.3f,
        contextSize = 4096,
        toolsByCategory = mapOf(ToolCategory.FILES to mapOf(parentTool.fn.name to parentTool)),
    )

    private fun tool(
        name: String,
        execute: suspend (ToolInvocationMeta) -> String = { "{}" },
    ): LLMToolSetup = object : LLMToolSetup {
        override val fn = LLMRequest.Function(name, name, LLMRequest.Parameters("object", emptyMap()))

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall) =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall, meta: ToolInvocationMeta) =
            LLMRequest.Message(LLMMessageRole.function, execute(meta), name = name)
    }

    private fun response(content: String = "", toolName: String? = null) = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = LLMMessageRole.assistant,
                    functionCall = toolName?.let { LLMResponse.FunctionCall(it, emptyMap()) },
                    functionsStateId = toolName?.let { "call-1" },
                ),
                index = 0,
                finishReason = if (toolName == null) LLMResponse.FinishReason.stop else LLMResponse.FinishReason.function_call,
            ),
        ),
        created = 1,
        model = "child-model",
        usage = LLMResponse.Usage(1, 1, 2, 0),
    )
}
