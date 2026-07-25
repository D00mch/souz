package ru.souz.agent.nodes

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import ru.souz.agent.graph.GraphRuntime
import ru.souz.agent.graph.RetryPolicy
import ru.souz.agent.runtime.AgentRuntimeEventSink
import ru.souz.agent.runtime.AgentToolExecutor
import ru.souz.agent.spi.AgentDesktopInfoRepository
import ru.souz.agent.spi.AgentSettingsProvider
import ru.souz.agent.spi.DefaultBrowserProvider
import ru.souz.agent.spi.SystemAgentRuntimeEnvironment
import ru.souz.agent.state.AgentContext
import ru.souz.agent.state.AgentSettings
import ru.souz.db.StorredData
import ru.souz.db.StorredType
import ru.souz.llms.LLMMessageRole
import ru.souz.llms.LLMModel
import ru.souz.llms.LLMRequest
import ru.souz.llms.LLMResponse
import ru.souz.llms.ToolInvocationMeta
import ru.souz.llms.toSystemPromptMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NodesCommonTest {
    @Test
    fun `local model includes desktop search in additional context`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf(
            StorredData("Найден локальный факт", StorredType.GENERAL_FACT)
        )
        val nodesCommon = nodesCommon(desktopInfoRepository, calendar = "Work")
        val context = stringContext(
            input = "Проверь Telegram",
            model = LLMModel.LocalQwen3_4B_Instruct_2507.alias,
        )

        val result = nodesCommon.nodeAppendAdditionalData().execute(context, graphRuntime())
        val injectedContext = assertNotNull(result.history.firstOrNull { it.isInjectedContextMessage() })

        assertTrue(injectedContext.content.contains("Найден локальный факт"))
        assertTrue(injectedContext.content.contains("Календарь по умолчанию: Work"))
        assertTrue(injectedContext.content.contains("Текущие дата и время:"))
        coVerify(exactly = 1) { desktopInfoRepository.search(any(), any()) }
    }

    @Test
    fun `cloud model includes desktop search in additional context`() = runTest {
        val desktopInfoRepository = mockk<AgentDesktopInfoRepository>()
        coEvery { desktopInfoRepository.search(any(), any()) } returns listOf(
            StorredData("Найден локальный факт", StorredType.GENERAL_FACT)
        )
        val nodesCommon = nodesCommon(desktopInfoRepository)
        val context = stringContext(input = "Найди локальные данные", model = "gpt-5-nano")

        val result = nodesCommon.nodeAppendAdditionalData().execute(context, graphRuntime())
        val injectedContext = assertNotNull(result.history.firstOrNull { it.isInjectedContextMessage() })

        assertTrue(injectedContext.content.contains("Найден локальный факт"))
        coVerify(exactly = 1) { desktopInfoRepository.search(any(), any()) }
    }

    @Test
    fun `ordinary context replacement does not remove a user prompt containing context tags`() = runTest {
        val prompt = """
            <context>
            Background information. Use ONLY if strictly relevant to the user query. If irrelevant (e.g. chitchat), IGNORE completely. Do NOT reference this data in output.
            ---
            user-authored content
            </context>
        """.trimIndent()
        val nodesCommon = nodesCommon(mockk(relaxed = true))
        val context = stringContext(input = prompt, model = "gpt-model")

        val result = nodesCommon.nodeAppendAdditionalData().execute(context, graphRuntime())

        assertEquals(3, result.history.size)
        assertEquals(LLMMessageRole.system, result.history[0].role)
        assertTrue(result.history[1].isInjectedContextMessage())
        assertEquals(prompt, result.history[2].content)
    }

    @Test
    fun `tool use forwards invocation metadata to executor`() = runTest {
        val functionCall = LLMResponse.FunctionCall(
            name = "tool.read_file",
            arguments = mapOf("path" to "/tmp/file.txt"),
        )
        val meta = ToolInvocationMeta(
            userId = "user-1",
            conversationId = "conversation-1",
            requestId = "request-1",
        )
        val eventSink = object : AgentRuntimeEventSink {
            override suspend fun emit(event: ru.souz.agent.runtime.AgentRuntimeEvent) = Unit
        }
        val agentToolExecutor = mockk<AgentToolExecutor>()
        coEvery {
            agentToolExecutor.execute(
                settings = any(),
                functionCall = functionCall,
                meta = meta,
                toolCallId = "call-1",
                eventSink = eventSink,
            )
        } returns LLMRequest.Message(
            role = LLMMessageRole.function,
            content = """{"ok":true}""",
            name = functionCall.name,
        )
        val nodesCommon = NodesCommon(
            desktopInfoRepository = mockk(relaxed = true),
            settingsProvider = mockk { every { defaultCalendar } returns null },
            agentToolExecutor = agentToolExecutor,
            defaultBrowserProvider = mockk { every { defaultBrowserDisplayName() } returns null },
            runtimeEnvironment = SystemAgentRuntimeEnvironment,
        )
        val context = AgentContext(
            input = okResponse(
                content = "",
                functionCall = functionCall,
                functionsStateId = "call-1",
                finishReason = LLMResponse.FinishReason.function_call,
            ),
            settings = settings("gpt-5-nano"),
            history = listOf(
                "system".toSystemPromptMessage(),
                LLMRequest.Message(LLMMessageRole.user, "read file"),
            ),
            activeTools = emptyList(),
            systemPrompt = "system",
            toolInvocationMeta = meta,
            runtimeEventSink = eventSink,
        )

        val result = nodesCommon.toolUse().execute(context, graphRuntime())

        coVerify(exactly = 1) {
            agentToolExecutor.execute(
                settings = context.settings,
                functionCall = functionCall,
                meta = meta,
                toolCallId = "call-1",
                eventSink = eventSink,
            )
        }
        assertEquals("""{"ok":true}""", result.history.last().content)
        assertEquals("call-1", result.history.last().functionsStateId)
    }

    private fun nodesCommon(
        desktopInfoRepository: AgentDesktopInfoRepository,
        calendar: String? = null,
    ): NodesCommon = NodesCommon(
        desktopInfoRepository = desktopInfoRepository,
        settingsProvider = mockk<AgentSettingsProvider> { every { defaultCalendar } returns calendar },
        agentToolExecutor = mockk(relaxed = true),
        defaultBrowserProvider = mockk<DefaultBrowserProvider> { every { defaultBrowserDisplayName() } returns null },
        runtimeEnvironment = SystemAgentRuntimeEnvironment,
    )

    private fun stringContext(input: String, model: String): AgentContext<String> = AgentContext(
        input = input,
        settings = settings(model),
        history = listOf(
            "system".toSystemPromptMessage(),
            LLMRequest.Message(LLMMessageRole.user, input),
        ),
        activeTools = emptyList(),
        systemPrompt = "system",
    )

    private fun settings(model: String): AgentSettings = AgentSettings(
        model = model,
        temperature = 0.2f,
        toolsByCategory = emptyMap(),
    )

    private fun okResponse(
        content: String,
        functionCall: LLMResponse.FunctionCall? = null,
        functionsStateId: String? = null,
        finishReason: LLMResponse.FinishReason = LLMResponse.FinishReason.stop,
    ): LLMResponse.Chat.Ok = LLMResponse.Chat.Ok(
        choices = listOf(
            LLMResponse.Choice(
                message = LLMResponse.Message(
                    content = content,
                    role = LLMMessageRole.assistant,
                    functionCall = functionCall,
                    functionsStateId = functionsStateId,
                ),
                index = 0,
                finishReason = finishReason,
            )
        ),
        created = 1L,
        model = "test-model",
        usage = LLMResponse.Usage(1, 1, 2, 0),
    )

    private fun graphRuntime(): GraphRuntime = GraphRuntime(retryPolicy = RetryPolicy(), maxSteps = 20)
}
