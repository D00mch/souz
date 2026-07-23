package ru.souz.agent.nodes

import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentExecutionPause
import ru.souz.agent.runtime.AgentRuntimeEvent
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolBatch
import ru.souz.agent.runtime.AgentToolBatchCheckpoint
import ru.souz.agent.runtime.AgentToolBatchCheckpointPhase
import ru.souz.agent.runtime.AgentToolBatchCheckpointSink
import ru.souz.agent.runtime.AgentToolBatchResume
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.runtime.AgentToolInvocation
import ru.souz.agent.runtime.AgentToolInvocationAttributes
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.LLMToolSetup
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toMessage
import ru.souz.tool.ToolCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolBatchExecutionTest {
    @Test
    fun `persists complete ordered batch before call one and exact result after every call`() = runTest {
        val executionOrder = mutableListOf<String>()
        val receivedMeta = mutableMapOf<String, ToolInvocationMeta>()
        val response = toolResponse("first" to "provider-1", "second" to null)
        val checkpoints = mutableListOf<AgentToolBatchCheckpoint>()
        val checkpointExecutionSnapshots = mutableListOf<List<String>>()
        val context = toolContext(
            response = response,
            settings = settings(
                tool("first") { call, meta ->
                    executionOrder += call.name
                    receivedMeta[call.name] = meta
                    functionResult(call.name, "first-result")
                },
                tool("second") { call, meta ->
                    executionOrder += call.name
                    receivedMeta[call.name] = meta
                    functionResult(call.name, "second-result")
                },
            ),
            checkpointSink = AgentToolBatchCheckpointSink { checkpoint, _ ->
                checkpoints += checkpoint
                checkpointExecutionSnapshots += executionOrder.toList()
            },
        )

        val result = nodes().toolUse().execute(context, graphRuntime())

        assertEquals(listOf("first", "second"), executionOrder)
        assertEquals(
            listOf(
                AgentToolBatchCheckpointPhase.PLANNED,
                AgentToolBatchCheckpointPhase.RESULT_STORED,
                AgentToolBatchCheckpointPhase.RESULT_STORED,
            ),
            checkpoints.map { it.phase },
        )
        assertEquals(listOf(0, 1, 2), checkpoints.map { it.nextInvocationIndex })
        assertEquals(listOf(emptyList(), listOf("first"), listOf("first", "second")), checkpointExecutionSnapshots)
        assertEquals(context.history, checkpoints.first().history)
        assertEquals("first-result", checkpoints[1].history.last().content)
        assertEquals("second-result", checkpoints[2].history.last().content)
        assertEquals("provider-1", checkpoints[1].history.last().functionsStateId)
        assertNull(checkpoints[2].history.last().functionsStateId)
        assertEquals("second-result", result.input)
        assertEquals(2, result.history.takeLast(2).count { it.role == LLMMessageRole.function })

        val firstInvocation = checkpoints.first().batch.invocations[0]
        val secondInvocation = checkpoints.first().batch.invocations[1]
        assertNotEquals(firstInvocation.invocationId, secondInvocation.invocationId)
        assertEquals(
            firstInvocation.invocationId.toString(),
            receivedMeta.getValue("first").attributes[AgentToolInvocationAttributes.INVOCATION_ID],
        )
        assertEquals(
            secondInvocation.invocationId.toString(),
            receivedMeta.getValue("second").attributes[AgentToolInvocationAttributes.INVOCATION_ID],
        )
        assertEquals("execution-1", receivedMeta.getValue("first").attributes[AgentToolInvocationAttributes.EXECUTION_ID])
        assertEquals("provider-1", receivedMeta.getValue("first").attributes[AgentToolInvocationAttributes.PROVIDER_TOOL_CALL_ID])
    }

    @Test
    fun `permission pause preserves completed sibling and does not execute later siblings`() = runTest {
        val executionOrder = mutableListOf<String>()
        val checkpoints = mutableListOf<AgentToolBatchCheckpoint>()
        val events = mutableListOf<AgentRuntimeEvent>()
        val pause = TestPermissionPause()
        val response = toolResponse("first" to "provider-1", "permission" to "provider-2", "last" to "provider-3")
        val context = toolContext(
            response = response,
            settings = settings(
                tool("first") { call, _ ->
                    executionOrder += call.name
                    functionResult(call.name, "first-result")
                },
                tool("permission") { call, _ ->
                    executionOrder += call.name
                    throw pause
                },
                tool("last") { call, _ ->
                    executionOrder += call.name
                    functionResult(call.name, "must-not-run")
                },
            ),
            checkpointSink = AgentToolBatchCheckpointSink { checkpoint, _ -> checkpoints += checkpoint },
            eventSink = AgentRuntimeEventSink { events += it },
        )

        val thrown = assertFailsWith<TestPermissionPause> {
            nodes().toolUse().execute(context, graphRuntime())
        }

        assertTrue(thrown === pause)
        assertEquals(listOf("first", "permission"), executionOrder)
        assertEquals(listOf(0, 1), checkpoints.map { it.nextInvocationIndex })
        assertEquals("first-result", checkpoints.last().history.last().content)
        assertTrue(events.none { it is AgentRuntimeEvent.ToolCallFailed })
        assertEquals(2, events.count { it is AgentRuntimeEvent.ToolCallStarted })
        assertEquals(1, events.count { it is AgentRuntimeEvent.ToolCallFinished })
    }

    @Test
    fun `denial stores standard result without invoking denied call then continues remaining calls`() = runTest {
        val executed = mutableListOf<String>()
        val checkpoints = mutableListOf<AgentToolBatchCheckpoint>()
        val events = mutableListOf<AgentRuntimeEvent>()
        val batch = batch("cached", "denied", "remaining")
        val cachedResult = functionResult("cached", "cached-result").copy(functionsStateId = "provider-0")
        val context = resumeContext(
            settings = settings(
                tool("denied") { call, _ ->
                    executed += call.name
                    error("Denied tool must not be invoked")
                },
                tool("remaining") { call, _ ->
                    executed += call.name
                    functionResult(call.name, "remaining-result")
                },
            ),
            history = assistantBatchHistory(batch) + cachedResult,
            resume = AgentToolBatchResume.denied(
                batch = batch,
                nextInvocationIndex = 1,
                startedInvocationIds = setOf(batch.invocations[1].invocationId),
            ),
            checkpointSink = AgentToolBatchCheckpointSink { checkpoint, _ -> checkpoints += checkpoint },
            eventSink = AgentRuntimeEventSink { events += it },
        )

        val result = nodes().resumeToolUse().execute(context, graphRuntime())

        assertEquals(listOf("remaining"), executed)
        assertEquals(
            listOf(
                AgentToolBatchCheckpointPhase.RESUMING,
                AgentToolBatchCheckpointPhase.RESULT_STORED,
                AgentToolBatchCheckpointPhase.RESULT_STORED,
            ),
            checkpoints.map { it.phase },
        )
        assertEquals(listOf(1, 2, 3), checkpoints.map { it.nextInvocationIndex })
        assertEquals("cached-result", result.history[result.history.size - 3].content)
        assertEquals(AgentToolBatchResume.USER_DISAPPROVED_MESSAGE, result.history[result.history.size - 2].content)
        assertEquals("provider-1", result.history[result.history.size - 2].functionsStateId)
        assertEquals("remaining-result", result.history.last().content)
        val finished = events.filterIsInstance<AgentRuntimeEvent.ToolCallFinished>()
        assertEquals(listOf("provider-1", "provider-2"), finished.map { it.toolCallId })
        assertEquals(AgentToolBatchResume.USER_DISAPPROVED_MESSAGE, finished.first().result)
    }

    @Test
    fun `grant reenters exact invocation with resume identity and no duplicate started event`() = runTest {
        val received = mutableListOf<Pair<String, ToolInvocationMeta>>()
        val events = mutableListOf<AgentRuntimeEvent>()
        val batch = batch("permission", "remaining")
        val context = resumeContext(
            settings = settings(
                tool("permission") { call, meta ->
                    received += call.name to meta
                    functionResult(call.name, "allowed")
                },
                tool("remaining") { call, meta ->
                    received += call.name to meta
                    functionResult(call.name, "done")
                },
            ),
            history = assistantBatchHistory(batch),
            resume = AgentToolBatchResume(
                batch = batch,
                nextInvocationIndex = 0,
                resumePermissionId = "permission-request-1",
                startedInvocationIds = setOf(batch.invocations[0].invocationId),
            ),
            checkpointSink = AgentToolBatchCheckpointSink.NONE,
            eventSink = AgentRuntimeEventSink { events += it },
        )

        nodes().resumeToolUse().execute(context, graphRuntime())

        assertEquals(listOf("permission", "remaining"), received.map { it.first })
        assertEquals(
            batch.invocations[0].invocationId.toString(),
            received[0].second.attributes[AgentToolInvocationAttributes.INVOCATION_ID],
        )
        assertEquals("permission-request-1", received[0].second.attributes[AgentToolInvocationAttributes.RESUME_PERMISSION_ID])
        assertNull(received[1].second.attributes[AgentToolInvocationAttributes.RESUME_PERMISSION_ID])
        assertEquals(1, events.count { it is AgentRuntimeEvent.ToolCallStarted })
        assertEquals("provider-1", (events.single { it is AgentRuntimeEvent.ToolCallStarted } as AgentRuntimeEvent.ToolCallStarted).toolCallId)
        assertEquals(2, events.count { it is AgentRuntimeEvent.ToolCallFinished })
    }

    private fun nodes(): NodesCommon = NodesCommon(
        desktopInfoRepository = mockk<AgentDesktopInfoRepository>(relaxed = true),
        settingsProvider = mockk<AgentSettingsProvider>(relaxed = true),
        agentToolExecutor = AgentToolExecutor(),
        defaultBrowserProvider = mockk<DefaultBrowserProvider>(relaxed = true),
        runtimeEnvironment = SystemAgentRuntimeEnvironment,
    )

    private fun settings(vararg tools: LLMToolSetup): AgentSettings = AgentSettings(
        model = "test-model",
        temperature = 0f,
        toolsByCategory = mapOf(ToolCategory.FILES to tools.associateBy { it.fn.name }),
    )

    private fun tool(
        name: String,
        invoke: suspend (LLMResponse.FunctionCall, ToolInvocationMeta) -> LLMRequest.Message,
    ): LLMToolSetup = object : LLMToolSetup {
        override val fn: LLMRequest.Function = LLMRequest.Function(
            name = name,
            description = name,
            parameters = LLMRequest.Parameters("object", emptyMap()),
        )

        override suspend fun invoke(functionCall: LLMResponse.FunctionCall): LLMRequest.Message =
            invoke(functionCall, ToolInvocationMeta.localDefault())

        override suspend fun invoke(
            functionCall: LLMResponse.FunctionCall,
            meta: ToolInvocationMeta,
        ): LLMRequest.Message = invoke.invoke(functionCall, meta)
    }

    private fun toolResponse(vararg calls: Pair<String, String?>): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = calls.mapIndexed { index, (name, providerId) ->
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = "",
                    role = LLMMessageRole.assistant,
                    functionCall = LLMResponse.FunctionCall(name, mapOf("index" to index)),
                    functionsStateId = providerId,
                ),
                index = index,
                finishReason = LLMResponse.FinishReason.function_call,
            )
        },
        created = 1L,
        model = "test-model",
        usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun toolContext(
        response: LLMResponse.Chat.Ok,
        settings: AgentSettings,
        checkpointSink: AgentToolBatchCheckpointSink,
        eventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): AgentContext<LLMResponse.Chat.Ok> = AgentContext(
        input = response,
        settings = settings,
        history = listOf(
            LLMRequest.Message(LLMMessageRole.system, "system"),
            LLMRequest.Message(LLMMessageRole.user, "run tools"),
        ) + response.choices.mapNotNull { it.toMessage() },
        activeTools = settings.tools.byName.values.map { it.fn },
        systemPrompt = "system",
        toolInvocationMeta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "chat-1",
            requestId = "execution-1",
        ),
        runtimeEventSink = eventSink,
        toolBatchCheckpointSink = checkpointSink,
    )

    private fun batch(vararg names: String): AgentToolBatch = AgentToolBatch(
        batchId = UUID.randomUUID(),
        invocations = names.mapIndexed { index, name ->
            AgentToolInvocation(
                invocationId = UUID.randomUUID(),
                batchIndex = index,
                functionCall = LLMResponse.FunctionCall(name, mapOf("index" to index)),
                providerToolCallId = "provider-$index",
            )
        },
    )

    private fun assistantBatchHistory(batch: AgentToolBatch): List<LLMRequest.Message> = listOf(
        LLMRequest.Message(LLMMessageRole.system, "system"),
        LLMRequest.Message(LLMMessageRole.user, "run tools"),
    ) + batch.invocations.map { invocation ->
        LLMRequest.Message(
            role = LLMMessageRole.assistant,
            content = invocation.functionCall.name,
            functionsStateId = invocation.providerToolCallId,
        )
    }

    private fun resumeContext(
        settings: AgentSettings,
        history: List<LLMRequest.Message>,
        resume: AgentToolBatchResume,
        checkpointSink: AgentToolBatchCheckpointSink,
        eventSink: AgentRuntimeEventSink = AgentRuntimeEventSink.NONE,
    ): AgentContext<AgentToolBatchResume> = AgentContext(
        input = resume,
        settings = settings,
        history = history,
        activeTools = settings.tools.byName.values.map { it.fn },
        systemPrompt = "system",
        toolInvocationMeta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "chat-1",
            requestId = "execution-1",
        ),
        runtimeEventSink = eventSink,
        toolBatchCheckpointSink = checkpointSink,
    )

    private fun functionResult(name: String, content: String): LLMRequest.Message = LLMRequest.Message(
        role = LLMMessageRole.function,
        content = content,
        name = name,
    )

    private fun graphRuntime(): GraphRuntime = GraphRuntime(RetryPolicy(), maxSteps = 100)

    private class TestPermissionPause : AgentExecutionPause("waiting permission")
}
