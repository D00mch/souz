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
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMChatAPI
import ru.souz.llms.LLMException
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SubagentRunnerTest {
    @Test
    fun `each execution starts with only its task and selected tools`() = runTest {
        val requests = mutableListOf<LLMRequest.Chat>()
        val runner = runner { request ->
            requests += request
            response("done")
        }
        val selected = tool("Selected")
        val parentSettings = settings()

        val first = runner.run("first", listOf(selected), settings = parentSettings)
        val second = runner.run("second", emptyList(), settings = first.context.settings)

        assertEquals(listOf("child instructions", "first"), requests[0].messages.map { it.content })
        assertEquals(listOf("child instructions", "second"), requests[1].messages.map { it.content })
        assertEquals(listOf("Selected"), requests[0].functions.map { it.name })
        assertEquals(listOf("Selected"), first.context.settings.tools.byName.keys.toList())
        assertEquals(listOf(selected.fn), first.context.activeTools)
        assertTrue(requests[1].functions.isEmpty())
        assertTrue(second.context.settings.tools.byName.isEmpty())
        requests.forEach {
            assertEquals(parentSettings.model, it.model)
            assertEquals(parentSettings.temperature, it.temperature)
            assertEquals(parentSettings.contextSize, it.maxTokens)
        }
        assertEquals(listOf("ParentTool"), parentSettings.tools.byName.keys.toList())
    }

    @Test
    fun `fabricated call cannot reach a tool inherited from parent context`() = runTest {
        var forbiddenCalls = 0
        var requests = 0
        val forbidden = tool("Forbidden") { forbiddenCalls += 1; "secret" }
        val runner = runner { request ->
            assertTrue(request.functions.isEmpty())
            if (++requests == 1) response(toolName = "Forbidden") else {
                assertContains(request.messages.last().content, "no such function Forbidden")
                response("denied")
            }
        }

        val result = runner.run("task", emptyList(), settings = settings(forbidden))

        assertEquals("denied", result.output)
        assertEquals(0, forbiddenCalls)
        assertTrue(result.context.settings.tools.byName.isEmpty())
    }

    @Test
    fun `selected tools preserve full invocation metadata and child events stay private`() = runTest {
        val meta = ToolInvocationMeta(
            userId = "user",
            conversationId = "conversation",
            requestId = "request",
            locale = "en",
            timeZone = "Europe/Moscow",
            attributes = mapOf("clientSessionId" to "client-session", "connectionId" to "connection"),
        )
        var toolCalls = 0
        val selected = tool("Selected") {
            assertSame(meta, it)
            toolCalls += 1
            "tool result"
        }
        var requests = 0
        val runner = runner(streaming = true) { request ->
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

        val result = runner.run("task", listOf(selected), settings = settings(selected), meta = meta)

        assertEquals("child answer", result.output)
        assertEquals(1, toolCalls)
        assertSame(meta, result.context.toolInvocationMeta)
        assertSame(AgentRuntimeEventSink.NONE, result.context.runtimeEventSink)
        assertEquals(mapOf("Selected" to ToolCategory.FILES), result.context.settings.tools.categoryByName)
    }

    @Test
    fun `final answer on last allowed turn succeeds`() = runTest {
        for (limit in listOf(1, 2, 128)) {
            var requests = 0
            val runner = runner {
                if (++requests < limit) response(toolName = "Selected") else response("done")
            }

            assertEquals("done", runner.run("task", listOf(tool("Selected")), limit).output)
            assertEquals(limit, requests)
        }
    }

    @Test
    fun `turn limit prevents the next model request and default is 32`() = runTest {
        for (limit in listOf(1, 32, 128)) {
            var requests = 0
            var toolCalls = 0
            val runner = runner { requests += 1; response(toolName = "Selected") }
            val tools = listOf(tool("Selected") { toolCalls += 1; "result" })

            val error = assertFailsWith<SubagentTurnLimitException> {
                if (limit == 32) runner.execute("task", settings(), tools, "child instructions", ToolInvocationMeta.localDefault())
                else runner.run("task", tools, limit)
            }

            assertEquals(limit, error.maxTurns)
            assertEquals(limit, requests)
            assertEquals(limit, toolCalls)
        }
    }

    @Test
    fun `invalid turn limits or duplicate tool names fail before calling provider`() = runTest {
        val runner = runner { error("Provider must not be called") }
        for (limit in listOf(-1, 0, 129)) {
            assertFailsWith<IllegalArgumentException> { runner.run("task", emptyList(), limit) }
        }
        assertFailsWith<IllegalArgumentException> {
            runner.run("task", listOf(tool("Duplicate"), tool("Duplicate")))
        }
    }

    @Test
    fun `provider error is an explicit failure`() = runTest {
        val runner = runner { LLMResponse.Chat.Error(503, "provider unavailable") }

        val failure = assertFailsWith<IllegalStateException> { runner.run("task", emptyList()) }

        assertContains(failure.message.orEmpty(), "503")
        assertContains(failure.message.orEmpty(), "provider unavailable")
    }

    @Test
    fun `failed tool is propagated without graph retries`() = runTest {
        var toolCalls = 0
        val failure = LLMException(LLMResponse.Chat.Error(500, "failed tool"))
        val runner = runner { response(toolName = "Selected") }
        val selected = tool("Selected") { toolCalls += 1; throw failure }

        assertSame(failure, assertFailsWith<LLMException> { runner.run("task", listOf(selected)) })
        assertEquals(1, toolCalls)
    }

    @Test
    fun `parent cancellation cancels an active model or tool`() = runTest {
        for (duringTool in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val waitForCancellation: suspend () -> Nothing = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete(Unit)
                }
            }
            val runner = runner { if (duringTool) response(toolName = "Selected") else waitForCancellation() }
            val selected = tool("Selected") { waitForCancellation() }
            val execution = async { runner.run("task", listOf(selected)) }

            started.await()
            execution.cancelAndJoin()

            assertTrue(stopped.isCompleted)
            assertFailsWith<CancellationException> { execution.await() }
        }
    }

    @Test
    fun `cancellation immediately before graph completion cannot return a result`() = runTest {
        val runner = runner { response(toolName = "Selected") }
        val selected = tool("Selected") {
            currentCoroutineContext().cancel()
            "discarded"
        }
        var returned = false
        val execution = async {
            runner.run("task", listOf(selected))
            returned = true
        }

        assertFailsWith<CancellationException> { execution.await() }
        assertFalse(returned)
    }

    @Test
    fun `concurrent children do not cancel or share state with each other`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val runner = runner { request ->
            val task = request.messages.last().content
            if (task == "first") {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            response(task)
        }
        val first = async { runner.run("first", emptyList(), maxTurns = 1) }
        firstStarted.await()

        assertEquals("second", runner.run("second", emptyList(), maxTurns = 1).output)
        assertFalse(first.isCompleted)
        releaseFirst.complete(Unit)
        assertEquals("first", first.await().output)
    }

    private fun runner(
        streaming: Boolean = false,
        respond: suspend (LLMRequest.Chat) -> LLMResponse.Chat,
    ): SubagentRunner {
        val api = mockk<LLMChatAPI>()
        coEvery { api.message(any()) } coAnswers { respond(firstArg()) }
        coEvery { api.messageStream(any()) } coAnswers {
            val request = firstArg<LLMRequest.Chat>()
            flow { emit(respond(request)) }
        }
        return SubagentRunner(api, mockk<AgentSettingsProvider> { every { useStreaming } returns streaming })
    }

    private suspend fun SubagentRunner.run(
        task: String,
        tools: List<LLMToolSetup>,
        maxTurns: Int = 32,
        settings: AgentSettings = settings(),
        meta: ToolInvocationMeta = ToolInvocationMeta.localDefault(),
    ) = execute(task, settings, tools, "child instructions", meta, maxTurns)

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
